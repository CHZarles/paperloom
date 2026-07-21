from __future__ import annotations

"""Narrow OpenAI-compatible model client used only by the offline LLM judge."""

import json
import time
import urllib.error
import urllib.request
from typing import Protocol

from ..core.models import JsonMap, child_map
from ..transport.provider_config import ProviderConfig


class JudgeModel(Protocol):
    def complete_judgment(
        self,
        messages: list[JsonMap],
        tool: JsonMap,
        max_tokens: int,
    ) -> list[JsonMap]:
        ...


class MiniMaxJudgeModel:
    """Request exactly one structured judgment from a MiniMax-compatible endpoint."""

    def __init__(
        self,
        provider: ProviderConfig,
        timeout_seconds: int = 90,
        max_attempts: int = 2,
    ) -> None:
        self.provider = provider
        self.timeout_seconds = timeout_seconds
        self.max_attempts = max_attempts

    def complete_judgment(
        self,
        messages: list[JsonMap],
        tool: JsonMap,
        max_tokens: int,
    ) -> list[JsonMap]:
        function_name = str(child_map(tool.get("function")).get("name") or "")
        if not function_name:
            raise ValueError("judge tool requires a function name")
        payload: JsonMap = {
            "model": self.provider.model,
            "messages": messages,
            "stream": False,
            "temperature": 0.0,
            "top_p": 1.0,
            "max_tokens": max_tokens,
            "tools": [tool],
            "tool_choice": {
                "type": "function",
                "function": {"name": function_name},
            },
        }
        if self.provider.model.casefold() == "minimax-m3":
            payload["thinking"] = {"type": "disabled"}

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
                return _tool_calls(raw)
            except urllib.error.HTTPError as error:
                last_error = error
                if error.code not in {429, 500, 502, 503, 504, 529} or attempt >= self.max_attempts:
                    response_text = error.read().decode("utf-8", errors="replace")[:1000]
                    raise RuntimeError(
                        f"MiniMax API rejected request: status={error.code}, body={response_text}"
                    ) from error
            except Exception as error:
                last_error = error
                if attempt >= self.max_attempts:
                    raise
            time.sleep(min(0.2 * attempt, 1.0))
        raise RuntimeError("MiniMax API request failed") from last_error


def _tool_calls(raw: JsonMap) -> list[JsonMap]:
    choices = raw.get("choices") or [{}]
    message = child_map(child_map(choices[0]).get("message"))
    calls: list[JsonMap] = []
    for raw_call in message.get("tool_calls") or []:
        function = child_map(child_map(raw_call).get("function"))
        arguments = function.get("arguments") or "{}"
        if isinstance(arguments, str):
            try:
                parsed = json.loads(arguments)
            except json.JSONDecodeError:
                parsed = {}
        else:
            parsed = arguments if isinstance(arguments, dict) else {}
        calls.append({
            "name": str(function.get("name") or ""),
            "arguments": _normalize_structured_arguments(parsed),
        })
    return calls


def _normalize_structured_arguments(value):
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
