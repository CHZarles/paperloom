from __future__ import annotations

import json
from contextlib import contextmanager
from contextvars import ContextVar
from typing import Any, Iterator
from uuid import uuid4

import httpx
from agents import ItemHelpers, ModelSettings, OpenAIChatCompletionsModel
from openai import AsyncOpenAI
from openai.types.responses import ResponseFunctionToolCall

from ...transport.provider_config import ProviderConfig
from .context import ResearchRunContext


_ACTIVE_CONTEXT: ContextVar[ResearchRunContext | None] = ContextVar(
    "paismart_agents_context",
    default=None,
)
TEXT_NUDGE_TOOL_NAME = "_continue_research_turn"


@contextmanager
def bind_research_context(context: ResearchRunContext) -> Iterator[None]:
    token = _ACTIVE_CONTEXT.set(context)
    try:
        yield
    finally:
        _ACTIVE_CONTEXT.reset(token)


class MiniMaxAgentsModel(OpenAIChatCompletionsModel):
    """Agents SDK Chat Completions adapter configured for the active MiniMax provider."""

    def __init__(
        self,
        provider: ProviderConfig,
        *,
        timeout_seconds: int = 90,
        max_attempts: int = 2,
    ) -> None:
        self.provider = provider
        http_client = httpx.AsyncClient(event_hooks={
            "request": [self._record_request],
            "response": [self._record_response],
        })
        client = AsyncOpenAI(
            api_key=provider.api_key,
            base_url=provider.api_base_url.rstrip("/") + "/",
            timeout=timeout_seconds,
            max_retries=max(0, max_attempts - 1),
            http_client=http_client,
        )
        super().__init__(model=provider.model, openai_client=client)

    def research_settings(self, max_completion_tokens: int) -> ModelSettings:
        extra_body = None
        if self.provider.model.casefold() == "minimax-m3":
            extra_body = {"thinking": {"type": "adaptive"}}
        return ModelSettings(
            temperature=0.0,
            top_p=1.0,
            max_tokens=max_completion_tokens,
            tool_choice="required",
            parallel_tool_calls=True,
            extra_body=extra_body,
        )

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


def _safe_headers(headers: httpx.Headers) -> dict[str, str]:
    blocked = {"authorization", "cookie", "set-cookie", "x-api-key"}
    return {
        key: value
        for key, value in headers.items()
        if key.casefold() not in blocked
    }


def _json_or_text(value: str) -> Any:
    try:
        return json.loads(value)
    except json.JSONDecodeError:
        return value
