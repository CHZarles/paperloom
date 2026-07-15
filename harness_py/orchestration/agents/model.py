"""OpenAI-compatible Provider 到 Agents SDK ``Model`` 的适配层。

Agents SDK 不要求底层一定是 OpenAI 托管模型；只要实现 SDK 的 Model 契约即可。本项目的
MiniMax 使用 Chat Completions；Codex/OpenAI 类 Provider 可以使用 Responses API。两条路径
共享请求观测和“纯文本响应继续走工具协议”的兼容逻辑。
"""

from __future__ import annotations

import json
from contextlib import contextmanager
from contextvars import ContextVar
from typing import Any, Iterator
from uuid import uuid4

import httpx
from agents import (
    ItemHelpers,
    ModelSettings,
    OpenAIChatCompletionsModel,
    OpenAIResponsesModel,
)
from openai import AsyncOpenAI
from openai.types.responses import ResponseFunctionToolCall

from ...transport.provider_config import ProviderConfig
from .context import ResearchRunContext


_ACTIVE_CONTEXT: ContextVar[ResearchRunContext | None] = ContextVar(
    "paperloom_agents_context",
    default=None,
)
TEXT_NUDGE_TOOL_NAME = "_continue_research_turn"
TOOL_ARGUMENT_REPAIR_PREFIX = "[tool_arguments_repair] "


@contextmanager
def bind_research_context(context: ResearchRunContext) -> Iterator[None]:
    """让底层 HTTP hooks 能取得当前 Run Context。

    ``ContextVar`` 会跟随 async 调用链传播，但不同并发任务之间相互隔离，适合把请求级状态
    传到 httpx event hook，而无需修改 SDK 的 ``get_response`` 方法签名。
    """

    token = _ACTIVE_CONTEXT.set(context)
    try:
        yield
    finally:
        _ACTIVE_CONTEXT.reset(token)


class _ObservedOpenAIModel:
    """共享 OpenAI-compatible 模型的记录、关闭和工具协议兼容逻辑。"""

    provider: ProviderConfig

    async def close(self) -> None:
        await self._client.close()

    async def get_response(self, *args, **kwargs):
        try:
            response = await super().get_response(*args, **kwargs)
        except Exception as error:
            context = _ACTIVE_CONTEXT.get()
            recorder = context.turn.eval_recorder if context else None
            if context and recorder:
                recorder.append(
                    kind="model.error",
                    operation_id=context.current_model_call_id,
                    attempt=context.current_transport_attempt(),
                    payload={
                        "error_type": type(error).__name__,
                        "message": str(error),
                    },
                )
            raise

        response.output = [
            _repair_function_call(item)
            for item in response.output
        ]
        if any(getattr(item, "type", "") == "function_call" for item in response.output):
            return response

        text = "\n".join(
            value
            for value in (ItemHelpers.extract_text(item) for item in response.output)
            if value
        )
        response.output = [ResponseFunctionToolCall(
            arguments=json.dumps({"content": text}, ensure_ascii=False),
            call_id=f"call_text_nudge_{uuid4().hex}",
            name=TEXT_NUDGE_TOOL_NAME,
            type="function_call",
        )]
        return response

    async def _record_request(self, request: httpx.Request) -> None:
        context = _ACTIVE_CONTEXT.get()
        recorder = context.turn.eval_recorder if context else None
        if not context or not recorder:
            return
        attempt = context.next_transport_attempt()
        body = (await request.aread()).decode("utf-8", errors="replace")
        recorder.append(
            kind="model.request",
            operation_id=context.current_model_call_id,
            attempt=attempt,
            payload={
                "method": request.method,
                "url": str(request.url),
                "headers": _safe_headers(request.headers),
                "body": _json_or_text(body),
            },
        )

    async def _record_response(self, response: httpx.Response) -> None:
        context = _ACTIVE_CONTEXT.get()
        recorder = context.turn.eval_recorder if context else None
        if not context or not recorder:
            return
        body = (await response.aread()).decode("utf-8", errors="replace")
        recorder.append(
            kind="model.response",
            operation_id=context.current_model_call_id,
            attempt=context.current_transport_attempt(),
            payload={
                "status_code": response.status_code,
                "headers": _safe_headers(response.headers),
                "body": _json_or_text(body),
            },
        )


class MiniMaxAgentsModel(_ObservedOpenAIModel, OpenAIChatCompletionsModel):
    """Agents SDK Chat Completions Model for the current MiniMax Provider."""

    def __init__(
        self,
        provider: ProviderConfig,
        *,
        timeout_seconds: int = 90,
        max_attempts: int = 2,
    ) -> None:
        self.provider = provider

        client = _client(self, provider, timeout_seconds, max_attempts)

        # 父类负责把 SDK Model 输入翻译成 Chat Completions 请求，再把响应翻译回 SDK item。
        super().__init__(model=provider.model, openai_client=client)

    def research_settings(self, max_completion_tokens: int) -> ModelSettings:
        """返回适合研究工具循环的供应商设置。"""

        extra_body = None
        if self.provider.model.casefold() == "minimax-m3":
            # 这是 MiniMax 特有参数，所以留在 Model 适配层，不扩散到 Runtime 和业务工具。
            extra_body = {"thinking": {"type": "adaptive"}}
        return ModelSettings(
            temperature=0.0,
            top_p=1.0,
            max_tokens=max_completion_tokens,
            # 每一步必须产生工具调用；最终用户答案也通过 submit_research_answer 工具提交。
            tool_choice="required",
            # 允许模型一次规划多个工具；Runtime 会为了授权状态一致性将实际执行串行化。
            parallel_tool_calls=True,
            extra_body=extra_body,
        )



class OpenAIResponsesAgentsModel(_ObservedOpenAIModel, OpenAIResponsesModel):
    """Agents SDK Responses API model for GPT/Codex-compatible Providers."""

    def __init__(
        self,
        provider: ProviderConfig,
        *,
        timeout_seconds: int = 90,
        max_attempts: int = 2,
    ) -> None:
        self.provider = provider
        client = _client(self, provider, timeout_seconds, max_attempts)
        super().__init__(model=provider.model, openai_client=client)

    def research_settings(self, max_completion_tokens: int) -> ModelSettings:
        return ModelSettings(
            max_tokens=max_completion_tokens,
            tool_choice="required",
            parallel_tool_calls=True,
            store=False,
        )


def provider_agents_model(provider: ProviderConfig):
    """Create the SDK model matching the Provider's declared wire API."""

    if provider.api_style.casefold() in {"responses", "openai-responses"}:
        return OpenAIResponsesAgentsModel(provider)
    return MiniMaxAgentsModel(provider)


def _client(
    owner: _ObservedOpenAIModel,
    provider: ProviderConfig,
    timeout_seconds: int,
    max_attempts: int,
) -> AsyncOpenAI:
    http_client = httpx.AsyncClient(event_hooks={
        "request": [owner._record_request],
        "response": [owner._record_response],
    })
    return AsyncOpenAI(
        api_key=provider.api_key,
        base_url=provider.api_base_url.rstrip("/") + "/",
        timeout=timeout_seconds,
        max_retries=max(0, max_attempts - 1),
        http_client=http_client,
    )


def _safe_headers(headers: httpx.Headers) -> dict[str, str]:
    """删除认证和 Cookie，避免诊断数据泄露密钥。"""

    blocked = {"authorization", "cookie", "set-cookie", "x-api-key"}
    return {
        key: value
        for key, value in headers.items()
        if key.casefold() not in blocked
    }


def _json_or_text(value: str) -> Any:
    """优先以 JSON 保存传输正文；不是 JSON 时保留原始文本。"""

    try:
        return json.loads(value)
    except json.JSONDecodeError:
        return value


def _repair_function_call(item: Any) -> Any:
    """Replace malformed provider arguments before the SDK replays them.

    Some Chat Completions providers can truncate a function-call argument string at the token
    limit. The SDK correctly returns a tool error, but its next request also replays the malformed
    assistant call. MiniMax rejects that history with HTTP 400 before the model can repair it.
    Converting only the malformed call into the existing continuation tool keeps the transcript
    valid and gives the model one explicit repair instruction.
    """

    if getattr(item, "type", "") != "function_call":
        return item
    raw_arguments = getattr(item, "arguments", None)
    try:
        json.loads(raw_arguments)
        return item
    except (json.JSONDecodeError, TypeError):
        tool_name = str(getattr(item, "name", "") or "tool")
        message = (
            f"{TOOL_ARGUMENT_REPAIR_PREFIX}The previous {tool_name} call had invalid or truncated "
            "JSON arguments. Retry that call with a shorter valid JSON object."
        )
        return item.model_copy(update={
            "name": TEXT_NUDGE_TOOL_NAME,
            "arguments": json.dumps({"content": message}, ensure_ascii=False),
        })
