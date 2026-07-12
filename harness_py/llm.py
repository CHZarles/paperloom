from __future__ import annotations

import json
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Any

from .models import JsonMap
from .provider_config import ProviderConfig


@dataclass(frozen=True)
class ToolCall:
    id: str
    name: str
    arguments: JsonMap


@dataclass(frozen=True)
class ChatTurn:
    content: str
    tool_calls: list[ToolCall] = field(default_factory=list)
    raw: JsonMap = field(default_factory=dict)


class ChatModel:
    def complete(self, messages: list[JsonMap], tools: list[JsonMap], max_tokens: int) -> ChatTurn:
        raise NotImplementedError

    def complete_required_tool(
        self,
        messages: list[JsonMap],
        tools: list[JsonMap],
        required_tool_name: str,
        max_tokens: int,
    ) -> ChatTurn:
        return self.complete(messages, tools, max_tokens)


class MiniMaxChatModel(ChatModel):
    def __init__(
        self,
        provider: ProviderConfig,
        timeout_seconds: int = 90,
        temperature: float = 0.1,
        top_p: float = 0.9,
        max_attempts: int = 2,
    ):
        self.provider = provider
        self.timeout_seconds = timeout_seconds
        self.temperature = temperature
        self.top_p = top_p
        self.max_attempts = max_attempts

    def complete(self, messages: list[JsonMap], tools: list[JsonMap], max_tokens: int) -> ChatTurn:
        return self._complete(messages, tools, max_tokens)

    def complete_required_tool(
        self,
        messages: list[JsonMap],
        tools: list[JsonMap],
        required_tool_name: str,
        max_tokens: int,
    ) -> ChatTurn:
        tool_names = {
            str((tool.get("function") or {}).get("name") or "")
            for tool in tools
        }
        if not required_tool_name or required_tool_name not in tool_names:
            raise ValueError("required tool must be present in the available tools")
        thinking_type = (
            "disabled"
            if required_tool_name in {
                "submit_judgment",
                "submit_turn_decision",
                "submit_evidence_coverage",
                "submit_retrieval_queries",
                "submit_claims",
                "submit_claim_verdicts",
            }
            else None
        )
        return self._complete(
            messages,
            tools,
            max_tokens,
            thinking_type=thinking_type,
            required_tool_name=required_tool_name,
        )

    def _complete(
        self,
        messages: list[JsonMap],
        tools: list[JsonMap],
        max_tokens: int,
        thinking_type: str | None = None,
        required_tool_name: str | None = None,
    ) -> ChatTurn:
        payload: JsonMap = {
            "model": self.provider.model,
            "messages": messages,
            "stream": False,
            "temperature": self.temperature,
            "top_p": self.top_p,
            "max_tokens": max_tokens,
        }
        if self.provider.model.lower() == "minimax-m3":
            payload["thinking"] = {
                "type": thinking_type or ("adaptive" if tools else "disabled")
            }
        if tools:
            payload["tools"] = tools
        if required_tool_name:
            payload["tool_choice"] = {
                "type": "function",
                "function": {"name": required_tool_name},
            }

        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(
            self.provider.api_base_url.rstrip("/") + "/chat/completions",
            data=body,
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.provider.api_key}",
            },
            method="POST",
        )
        last_error: Exception | None = None
        for attempt in range(1, self.max_attempts + 1):
            try:
                with urllib.request.urlopen(request, timeout=self.timeout_seconds) as response:
                    raw = json.loads(response.read().decode("utf-8"))
                return _parse_openai_compatible_turn(raw)
            except urllib.error.HTTPError as error:
                last_error = error
                if error.code not in {429, 500, 502, 503, 504, 529} or attempt >= self.max_attempts:
                    response_text = error.read().decode("utf-8", errors="replace")[:1000]
                    raise RuntimeError(f"MiniMax API rejected request: status={error.code}, body={response_text}") from error
            except Exception as error:
                last_error = error
                if attempt >= self.max_attempts:
                    raise
            time.sleep(min(0.2 * attempt, 1.0))
        raise RuntimeError("MiniMax API request failed") from last_error


def _parse_openai_compatible_turn(raw: JsonMap) -> ChatTurn:
    choice = (raw.get("choices") or [{}])[0]
    message = choice.get("message") or {}
    tool_calls: list[ToolCall] = []
    for raw_call in message.get("tool_calls") or []:
        function = raw_call.get("function") or {}
        arguments = function.get("arguments") or "{}"
        if isinstance(arguments, str):
            try:
                parsed_arguments = json.loads(arguments)
            except json.JSONDecodeError:
                parsed_arguments = {}
        elif isinstance(arguments, dict):
            parsed_arguments = arguments
        else:
            parsed_arguments = {}
        parsed_arguments = _normalize_structured_arguments(parsed_arguments)
        tool_calls.append(ToolCall(
            id=str(raw_call.get("id") or f"call_{len(tool_calls) + 1}"),
            name=str(function.get("name") or raw_call.get("name") or ""),
            arguments=parsed_arguments,
        ))
    content = message.get("content") or ""
    if not isinstance(content, str):
        content = json.dumps(content, ensure_ascii=False)
    return ChatTurn(content=content, tool_calls=tool_calls, raw=raw)


def _normalize_structured_arguments(value: Any) -> Any:
    if isinstance(value, list):
        return [_normalize_structured_arguments(item) for item in value]
    if not isinstance(value, dict):
        return value
    if set(value) == {"$text"} and isinstance(value["$text"], str):
        try:
            decoded = json.loads(value["$text"])
        except json.JSONDecodeError:
            return value["$text"]
        return _normalize_structured_arguments(decoded)
    return {
        str(key): _normalize_structured_arguments(item)
        for key, item in value.items()
    }
