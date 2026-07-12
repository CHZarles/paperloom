from __future__ import annotations

import json
from time import perf_counter
from typing import Any

from agents import (
    Agent,
    Model,
    ModelResponse,
    ModelSettings,
    RunConfig,
    RunHooks,
    Runner,
    ToolExecutionConfig,
)

from .agent_harness import _build_run, research_agent_instructions
from .agents_context import ResearchRunContext
from .agents_model import MiniMaxAgentsModel, bind_research_context
from .agents_tools import build_agent_tools, tools_to_final_output
from .memory import RequestBackedSession, request_session_input
from .models import JsonMap
from .provider_config import ProviderConfig
from .runtime import HarnessRuntime, TurnExecutionInput, TurnExecutionResult


class ResearchRunHooks(RunHooks[ResearchRunContext]):
    async def on_llm_start(
        self,
        context,
        agent,
        system_prompt: str | None,
        input_items: list[Any],
    ) -> None:
        context.context.begin_model_call()

    async def on_llm_end(
        self,
        context,
        agent,
        response: ModelResponse,
    ) -> None:
        tool_calls = [
            (str(getattr(item, "call_id", "")), str(getattr(item, "name", "")))
            for item in response.output
            if getattr(item, "type", "") == "function_call"
            and getattr(item, "call_id", None)
            and getattr(item, "name", None)
        ]
        context.context.register_tool_group(tool_calls)
        usage = response.usage
        context.context.complete_model_call(
            int(usage.input_tokens or 0),
            int(usage.output_tokens or 0),
            int(usage.total_tokens or 0),
        )


class AgentsSdkHarnessRuntime(HarnessRuntime):
    harness_id = "python_openai_agents_sdk_harness_v1"

    def __init__(
        self,
        provider: ProviderConfig | None = None,
        *,
        max_completion_tokens: int = 3000,
        model: Model | None = None,
        model_settings: ModelSettings | None = None,
    ) -> None:
        if model is None and provider is None:
            raise ValueError("provider or model is required")
        self.model = model or MiniMaxAgentsModel(provider)  # type: ignore[arg-type]
        self.max_completion_tokens = max_completion_tokens
        self.model_settings = model_settings

    def run_turn(self, turn: TurnExecutionInput) -> TurnExecutionResult:
        context = ResearchRunContext(turn)
        tools = build_agent_tools(context)
        settings = self.model_settings
        if settings is None and isinstance(self.model, MiniMaxAgentsModel):
            settings = self.model.research_settings(self.max_completion_tokens)
        settings = settings or ModelSettings(
            max_tokens=self.max_completion_tokens,
            tool_choice="required",
            parallel_tool_calls=True,
        )
        agent = Agent[ResearchRunContext](
            name="PaiSmart Research Harness",
            instructions=research_agent_instructions(context.skills),
            model=self.model,
            model_settings=settings,
            tools=tools,
            tool_use_behavior=tools_to_final_output,
            reset_tool_choice=False,
        )
        session = RequestBackedSession(turn.run_id, turn.conversation_messages)
        input_items: list[JsonMap] = []
        if turn.research_memory.evidence_items_by_id:
            input_items.append({
                "role": "system",
                "content": "Previously cited evidence available for follow-up questions:\n" + json.dumps(
                    [_evidence_card(item) for item in turn.research_memory.evidence_items_by_id.values()],
                    ensure_ascii=False,
                ),
            })
        input_items.append({"role": "user", "content": turn.question})
        run_config = RunConfig(
            tracing_disabled=True,
            session_input_callback=request_session_input,
            tool_execution=ToolExecutionConfig(max_function_tool_concurrency=1),
        )
        with bind_research_context(context):
            result = Runner.run_sync(
                agent,
                input_items,
                context=context,
                max_turns=None,
                hooks=ResearchRunHooks(),
                run_config=run_config,
                session=session,
            )
        context.check_cancelled()
        final = result.final_output if isinstance(result.final_output, dict) else context.final_draft
        if not isinstance(final, dict):
            raise RuntimeError("Agents SDK run ended without an accepted submit_research_answer")
        run = _build_run(
            case_id=turn.case_id,
            final=final,
            prior_evidence=turn.research_memory.evidence_items_by_id,
            corpus=context.corpus,
            trace=context.trace,
            skills_used=context.skills_used,
            started_at=context.started_at,
            duration_ms=round((perf_counter() - context.started_monotonic) * 1000),
            diagnostics={
                "model_call_count": context.model_call_count,
                "prompt_tokens": context.prompt_tokens,
                "completion_tokens": context.completion_tokens,
                "total_tokens": context.total_tokens,
                "model_latency_ms": context.model_latency_ms,
            },
            harness_id=self.harness_id,
        )
        run["run_id"] = turn.run_id
        return TurnExecutionResult(run=run)


def _evidence_card(item: JsonMap) -> JsonMap:
    return {
        "evidence_id": item.get("evidence_id"),
        "paper_id": item.get("paper_id"),
        "title": item.get("title"),
        "section": item.get("section"),
        "page": item.get("page"),
        "span_text": str(item.get("span_text") or "")[:900],
    }
