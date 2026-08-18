"""OpenAI-compatible Provider 到 Agents SDK ``Model`` 的适配层。

Agents SDK 不要求底层一定是 OpenAI 托管模型；只要实现 SDK 的 Model 契约即可。本项目的
MiniMax 使用 Chat Completions；Codex/OpenAI 类 Provider 可以使用 Responses API。两条路径
共享请求观测和“纯文本响应继续走工具协议”的兼容逻辑。
"""

from __future__ import annotations

import json
import asyncio
import hashlib
import re
from contextlib import contextmanager
from contextvars import ContextVar
from typing import Any, Iterator
from urllib.parse import urlparse
from uuid import uuid4

import httpx
from agents import (
    ItemHelpers,
    ModelSettings,
    OpenAIChatCompletionsModel,
    OpenAIResponsesModel,
)
from openai import APIConnectionError, APIStatusError, APITimeoutError, AsyncOpenAI
from openai.types.responses import ResponseFunctionToolCall

from ...transport.provider_config import ProviderConfig
from ...utils.errors import ResearchSystemError, RunLimitExceeded
from .context import ResearchRunContext


_ACTIVE_CONTEXT: ContextVar[ResearchRunContext | None] = ContextVar(
    "paperloom_agents_context",
    default=None,
)
TEXT_NUDGE_TOOL_NAME = "_continue_research_turn"
TOOL_ARGUMENT_REPAIR_PREFIX = "[tool_arguments_repair] "
MAX_PROVIDER_PROTOCOL_REPAIRS = 2


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
    hide_internal_continuation_tool = False

    async def close(self) -> None:
        await self._client.close()

    async def get_response(
        self,
        system_instructions,
        input,
        model_settings,
        tools,
        output_schema,
        handoffs,
        tracing,
        previous_response_id=None,
        conversation_id=None,
        prompt=None,
    ):
        context = _ACTIVE_CONTEXT.get()
        provider_tools = (
            [tool for tool in tools if getattr(tool, "name", "") != TEXT_NUDGE_TOOL_NAME]
            if self.hide_internal_continuation_tool
            else tools
        )
        try:
            response = await asyncio.wait_for(
                super().get_response(
                    system_instructions,
                    input,
                    model_settings,
                    provider_tools,
                    output_schema,
                    handoffs,
                    tracing,
                    previous_response_id=previous_response_id,
                    conversation_id=conversation_id,
                    prompt=prompt,
                ),
                timeout=context.control.remaining_seconds() if context else None,
            )
        except asyncio.TimeoutError as error:
            if context:
                context.control.terminal_reason = "RUN_DEADLINE_EXCEEDED"
            raise RunLimitExceeded("RUN_DEADLINE_EXCEEDED") from error
        except Exception as error:
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
            reason_code = _provider_reason_code(error)
            if reason_code:
                raise ResearchSystemError(reason_code) from error
            raise

        if any(
            getattr(item, "type", "") == "function_call"
            and getattr(item, "name", "") == TEXT_NUDGE_TOOL_NAME
            for item in response.output
        ):
            _raise_protocol_violation(context, response, "MODEL_SELECTED_INTERNAL_CONTINUATION")

        repaired_output = []
        repaired_items: list[tuple[int, Any, Any]] = []
        for index, item in enumerate(response.output):
            repaired = _repair_function_call(item)
            repaired_output.append(repaired)
            if repaired is not item:
                repaired_items.append((index, item, repaired))

        if repaired_items:
            if not _consume_protocol_repair(context):
                _raise_protocol_violation(context, response, "TOOL_ARGUMENTS_INVALID_OR_TRUNCATED")
            for index, item, repaired in repaired_items:
                if context:
                    context.synthetic_repair_call_ids.add(str(repaired.call_id))
                _record_model_transform(
                    context,
                    reason_code="TOOL_ARGUMENTS_INVALID_OR_TRUNCATED",
                    source=_function_call_payload(item),
                    target=_function_call_payload(repaired),
                    event_suffix=str(getattr(item, "call_id", "") or index),
                )
        response.output = repaired_output
        if any(getattr(item, "type", "") == "function_call" for item in response.output):
            return response

        if not _consume_protocol_repair(context):
            _raise_protocol_violation(context, response, "PLAIN_TEXT_RESPONSE_REQUIRES_SUBMISSION")

        text = "\n".join(
            value
            for value in (ItemHelpers.extract_text(item) for item in response.output)
            if value
        )
        text = re.sub(r"<think(?:\s[^>]*)?>.*?</think>\s*", "", text, flags=re.IGNORECASE | re.DOTALL).strip()
        nudge_call = ResponseFunctionToolCall(
            arguments=json.dumps({"content": text}, ensure_ascii=False),
            call_id=f"call_text_nudge_{uuid4().hex}",
            name=TEXT_NUDGE_TOOL_NAME,
            type="function_call",
        )
        if context:
            context.synthetic_repair_call_ids.add(nudge_call.call_id)
        _record_model_transform(
            context,
            reason_code="PLAIN_TEXT_RESPONSE_REQUIRES_SUBMISSION",
            source={
                "type": "assistant_text",
                "draft_chars": len(text),
                "draft_sha256": hashlib.sha256(text.encode("utf-8")).hexdigest(),
            },
            target={
                "type": "function_call",
                "call_id": nudge_call.call_id,
                "name": nudge_call.name,
                "arguments_redacted": True,
            },
            event_suffix="plain_text",
        )
        response.output = [nudge_call]
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

    hide_internal_continuation_tool = True

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

    def research_settings(self) -> ModelSettings:
        """返回适合研究工具循环的供应商设置。"""

        extra_body = None
        if self.provider.model.casefold() == "minimax-m3":
            # 这是 MiniMax 特有参数，所以留在 Model 适配层，不扩散到 Runtime 和业务工具。
            extra_body = {"thinking": {"type": "adaptive"}}
        return ModelSettings(
            temperature=0.0,
            top_p=1.0,
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

    def research_settings(self) -> ModelSettings:
        return ModelSettings(
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
    http_client = httpx.AsyncClient(trust_env=not _is_loopback_url(provider.api_base_url), event_hooks={
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


def _is_loopback_url(url: str) -> bool:
    return urlparse(url).hostname in {"127.0.0.1", "::1", "localhost"}


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


def _provider_reason_code(error: Exception) -> str:
    if isinstance(error, (asyncio.TimeoutError, httpx.TimeoutException, APITimeoutError)):
        return "PROVIDER_TIMEOUT"
    if isinstance(error, (httpx.HTTPError, APIConnectionError)):
        return "PROVIDER_UNAVAILABLE"
    if isinstance(error, APIStatusError):
        return "PROVIDER_UNAVAILABLE" if error.status_code >= 500 else "PROVIDER_PROTOCOL_INVALID"
    return ""


def _consume_protocol_repair(context: ResearchRunContext | None) -> bool:
    if context is None or context.protocol_repair_count >= MAX_PROVIDER_PROTOCOL_REPAIRS:
        return False
    context.protocol_repair_count += 1
    return True


def _raise_protocol_violation(
    context: ResearchRunContext | None,
    response: Any,
    failure_kind: str,
) -> None:
    reason_code = "PROVIDER_TOOL_PROTOCOL_VIOLATION"
    if context:
        context.control.terminal_reason = reason_code
        recorder = context.turn.eval_recorder
        if recorder:
            recorder.append(
                kind="model.error",
                operation_id=context.current_model_call_id,
                attempt=context.current_transport_attempt(),
                payload={
                    "error_type": "ProviderToolProtocolViolation",
                    "reason_code": reason_code,
                    "failure_kind": failure_kind,
                    "output": [_provider_output_shape(item) for item in response.output],
                },
            )
        usage = response.usage
        context.complete_model_call(
            int(usage.input_tokens or 0),
            int(usage.output_tokens or 0),
            int(usage.total_tokens or 0),
        )
    raise ResearchSystemError(reason_code)


def _provider_output_shape(item: Any) -> dict[str, object]:
    shape: dict[str, object] = {"type": str(getattr(item, "type", "") or "")}
    for key in ("call_id", "name", "status"):
        value = getattr(item, key, None)
        if value:
            shape[key] = str(value)
    return shape


def _record_model_transform(
    context: ResearchRunContext | None,
    *,
    reason_code: str,
    source: JsonMap,
    target: JsonMap,
    event_suffix: str,
) -> None:
    recorder = context.turn.eval_recorder if context else None
    if not context or not recorder:
        return
    attempt = context.current_transport_attempt()
    recorder.append(
        kind="model.output_transformed",
        operation_id=context.current_model_call_id,
        attempt=attempt,
        event_id=(
            f"{context.turn.run_id}:{context.current_model_call_id}:"
            f"model.output_transformed:{attempt}:{event_suffix}"
        ),
        payload={
            "reason_code": reason_code,
            "source": source,
            "target": target,
        },
    )


def _function_call_payload(item: Any) -> JsonMap:
    return {
        "type": str(getattr(item, "type", "") or ""),
        "call_id": str(getattr(item, "call_id", "") or ""),
        "name": str(getattr(item, "name", "") or ""),
        "arguments": getattr(item, "arguments", None),
    }


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
        parsed = json.loads(raw_arguments)
        if isinstance(parsed, dict):
            return item
    except (json.JSONDecodeError, TypeError):
        pass
    tool_name = str(getattr(item, "name", "") or "tool")
    message = (
        f"{TOOL_ARGUMENT_REPAIR_PREFIX}The previous {tool_name} call had invalid or truncated "
        "JSON arguments. Retry that call with a shorter valid JSON object."
    )
    return item.model_copy(update={
        "name": TEXT_NUDGE_TOOL_NAME,
        "arguments": json.dumps({"content": message}, ensure_ascii=False),
    })
