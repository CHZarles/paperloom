from __future__ import annotations

from agents import ModelSettings, OpenAIChatCompletionsModel
from openai import AsyncOpenAI

from .provider_config import ProviderConfig


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
        client = AsyncOpenAI(
            api_key=provider.api_key,
            base_url=provider.api_base_url.rstrip("/") + "/",
            timeout=timeout_seconds,
            max_retries=max(0, max_attempts - 1),
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
