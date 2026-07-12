from __future__ import annotations

import json
from time import perf_counter
from typing import Any

from agents import FunctionTool, FunctionToolResult, ToolsToFinalOutputResult
from agents.run_context import RunContextWrapper
from agents.tool_context import ToolContext

from .agent_harness import (
    _answer_validation_error,
    _final_answer_tool,
    _progress_evidence_ids,
    _progress_input,
    _progress_output,
    _trace_item,
)
from .agents_context import ResearchRunContext
from .agents_model import TEXT_NUDGE_TOOL_NAME
from .llm import ToolCall, _normalize_structured_arguments
from .models import JsonMap, child_map
from .tools import model_facing_payload


FINAL_TOOL_NAME = "submit_research_answer"


def build_agent_tools(context: ResearchRunContext) -> list[FunctionTool]:
    definitions = [
        context.skills.tool_definition(),
        *context.corpus.definitions(),
        _final_answer_tool(),
        {
            "type": "function",
            "function": {
                "name": TEXT_NUDGE_TOOL_NAME,
                "description": "Continue after a text-only response and finish through the required submission tool.",
                "parameters": {
                    "type": "object",
                    "properties": {"content": {"type": "string"}},
                    "additionalProperties": False,
                },
            },
        },
    ]
    return [_function_tool(definition) for definition in definitions]


def tools_to_final_output(
    _run_context: RunContextWrapper[ResearchRunContext],
    results: list[FunctionToolResult],
) -> ToolsToFinalOutputResult:
    for result in results:
        if result.tool.name != FINAL_TOOL_NAME:
            continue
        payload = _json_map(result.output)
        if payload.get("accepted") and isinstance(payload.get("draft"), dict):
            return ToolsToFinalOutputResult(is_final_output=True, final_output=payload["draft"])
    return ToolsToFinalOutputResult(is_final_output=False, final_output=None)


def _function_tool(definition: JsonMap) -> FunctionTool:
    function = child_map(definition.get("function"))
    name = str(function.get("name") or "")

    async def invoke(tool_context: ToolContext[ResearchRunContext], raw_arguments: str) -> str:
        context = tool_context.context
        context.check_cancelled()
        arguments, parse_error = _arguments(raw_arguments)
        if parse_error:
            payload = {"error": "invalid_tool_arguments", "message": parse_error}
            context.trace.append(_trace_item(ToolCall(tool_context.tool_call_id, name, {}), payload))
            return json.dumps(payload, ensure_ascii=False)
        if name == TEXT_NUDGE_TOOL_NAME:
            return json.dumps({
                "continue": True,
                "message": (
                    "Finish by calling submit_research_answer as the only tool call. "
                    "Copy exact evidence IDs returned by read_locations; placeholders such as "
                    "[[evidence_id]] are invalid. Remove claims not directly supported by cited spans."
                ),
            }, ensure_ascii=False)
        if name == FINAL_TOOL_NAME:
            return _invoke_final(context, tool_context, arguments)
        try:
            return _invoke_domain(context, tool_context, name, arguments)
        except Exception as error:
            recorder = context.turn.eval_recorder
            if recorder:
                recorder.append(
                    kind="tool.error",
                    operation_id=tool_context.tool_call_id,
                    payload={
                        "tool_name": name,
                        "raw_arguments": raw_arguments,
                        "error_type": type(error).__name__,
                        "message": str(error),
                    },
                )
            raise

    return FunctionTool(
        name=name,
        description=str(function.get("description") or ""),
        params_json_schema=child_map(function.get("parameters")),
        on_invoke_tool=invoke,
        strict_json_schema=False,
        _use_default_failure_error_function=False,
    )


def _invoke_domain(
    context: ResearchRunContext,
    tool_context: ToolContext[ResearchRunContext],
    name: str,
    arguments: JsonMap,
) -> str:
    started = perf_counter()
    before = context.state_snapshot()
    recorder = context.turn.eval_recorder
    if recorder:
        recorder.append(
            kind="tool.started",
            operation_id=tool_context.tool_call_id,
            payload={
                "tool_name": name,
                "model_call_id": context.tool_call_models.get(tool_context.tool_call_id),
                "raw_arguments": tool_context.tool_arguments,
                "arguments": arguments,
            },
        )
    context.emit_progress({
        "type": "tool_started",
        "tool": name,
        "input": _progress_input(name, arguments),
    })
    if name == "get_research_skill":
        skill_id = str(arguments.get("skill_id") or "")
        internal = context.skills.get(skill_id)
        if "error" not in internal and skill_id not in context.skills_used:
            context.skills_used.append(skill_id)
    else:
        internal = context.corpus.call(name, arguments).payload
    visible = child_map(model_facing_payload(internal))
    context.trace.append(_trace_item(ToolCall(tool_context.tool_call_id, name, arguments), visible))
    _record_eval_tool(context, tool_context, name, arguments, internal, visible, before)
    context.emit_progress({
        "type": "tool_completed",
        "tool": name,
        "status": "success" if "error" not in visible else "failed",
        "durationMs": round((perf_counter() - started) * 1000),
        "input": _progress_input(name, arguments),
        "output": _progress_output(name, visible),
        "evidenceIds": _progress_evidence_ids(visible),
    })
    context.check_cancelled()
    return json.dumps(visible, ensure_ascii=False)


def _invoke_final(
    context: ResearchRunContext,
    tool_context: ToolContext[ResearchRunContext],
    draft: JsonMap,
) -> str:
    siblings = context.tool_call_groups.get(tool_context.tool_call_id, (FINAL_TOOL_NAME,))
    validation_error = ""
    if len(siblings) != 1:
        validation_error = "submit_research_answer must be the only tool call in the final step"
    else:
        validation_error = _answer_validation_error(
            draft,
            {
                **context.turn.research_memory.evidence_items_by_id,
                **context.corpus.observations_by_evidence_id,
            },
            bool(context.corpus.observations_by_evidence_id),
        )
    accepted = not validation_error
    visible = {"accepted": accepted}
    if validation_error:
        visible["error"] = validation_error
    context.trace.append(
        _trace_item(ToolCall(tool_context.tool_call_id, FINAL_TOOL_NAME, draft), visible)
    )
    if accepted:
        context.final_draft = draft
    payload = {
        "accepted": accepted,
        "draft": draft if accepted else None,
        "validation_error": validation_error or None,
        "tool_call_id": tool_context.tool_call_id,
        "model_call_id": context.tool_call_models.get(tool_context.tool_call_id),
    }
    recorder = context.turn.eval_recorder
    if recorder:
        recorder.append(
            kind="answer.validation",
            operation_id=tool_context.tool_call_id,
            payload={**payload, "submitted_draft": draft},
        )
    return json.dumps(payload, ensure_ascii=False)


def _record_eval_tool(
    context: ResearchRunContext,
    tool_context: ToolContext[ResearchRunContext],
    name: str,
    arguments: JsonMap,
    internal: JsonMap,
    visible: JsonMap,
    before: JsonMap,
) -> None:
    recorder = context.turn.eval_recorder
    if not recorder:
        return
    recorder.append(
        kind="tool.completed",
        operation_id=tool_context.tool_call_id,
        payload={
            "tool_name": name,
            "model_call_id": context.tool_call_models.get(tool_context.tool_call_id),
            "raw_arguments": tool_context.tool_arguments,
            "arguments": arguments,
            "internal_result": internal,
            "model_visible_result": visible,
            "state_before": before,
            "state_after": context.state_snapshot(),
        },
    )


def _arguments(raw: str) -> tuple[JsonMap, str]:
    try:
        value: Any = json.loads(raw or "{}")
    except json.JSONDecodeError as error:
        return {}, str(error)
    value = _normalize_structured_arguments(value)
    if not isinstance(value, dict):
        return {}, "tool arguments must decode to an object"
    return value, ""


def _json_map(value: object) -> JsonMap:
    if isinstance(value, dict):
        return value
    if isinstance(value, str):
        try:
            parsed = json.loads(value)
        except json.JSONDecodeError:
            return {}
        return parsed if isinstance(parsed, dict) else {}
    return {}
