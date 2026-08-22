"""把 Harness 业务工具适配成 OpenAI Agents SDK FunctionTool。

初学者可以先看三条主线：

- ``build_agent_tools``：收集工具定义；
- ``_function_tool``：把 JSON Schema 包成 SDK FunctionTool；
- ``tools_to_final_output``：告诉 Runner 哪个工具结果可以结束整个 Agent Run。

所有业务工具最终都返回 JSON 字符串。SDK 会把字符串作为 function-call output 放回下一次
模型输入，因此工具报错也可以成为模型能够阅读并修正的反馈。
"""

from __future__ import annotations

import json
from time import perf_counter
from typing import Any

from agents import FunctionTool, FunctionToolResult, ToolsToFinalOutputResult
from agents.run_context import RunContextWrapper
from agents.tool_context import ToolContext

from ...utils.models import JsonMap, as_list, child_map, stable_id
from ...utils.errors import ResearchSystemError, RunLimitExceeded
from ...corpus.gateway import CorpusGatewayError
from ...corpus.tools import model_facing_payload
from ..research_contract import (
    ActionRequested,
    AnswerContract,
    CATALOG_FINAL_TOOL_NAME,
    DIRECT_FINAL_TOOL_NAME,
    FINAL_TOOL_NAME,
    ProtocolFacts,
    catalog_answer_tool_definition,
    direct_answer_tool_definition,
    render_catalog_submission,
    render_direct_submission,
    render_research_submission,
    research_answer_tool_definition,
    submission_requested,
)
from ..run_output import (
    progress_evidence_ids,
    progress_input,
    progress_output,
    tool_trace_item,
)
from .context import ResearchRunContext
from .model import MAX_DIAGNOSTIC_HINT_CHARS, TEXT_NUDGE_TOOL_NAME, TOOL_ARGUMENT_REPAIR_PREFIX


_SUBMISSION_CONTRACTS = {
    DIRECT_FINAL_TOOL_NAME: AnswerContract.DIRECT,
    CATALOG_FINAL_TOOL_NAME: AnswerContract.CATALOG,
    FINAL_TOOL_NAME: AnswerContract.RESEARCH,
}
_DIAGNOSTIC_HINT_SCOPE = (
    "The message field is authoritative. Treat diagnostic_repair_hint as formatting advice only; "
    "ignore it if it conflicts with tool choice, arguments, evidence, or validation requirements."
)


def build_agent_tools(context: ResearchRunContext) -> list[FunctionTool]:
    """创建本轮 Agent 可见的全部工具。

    工具由四类组成：研究方法指导、语料工具、最终提交工具，以及修复截断工具调用的内部
    继续工具。工具列表按请求创建，因为 Corpus 能力可能随 Dataset 改变。
    """

    definitions = [
        # get_research_skill：让模型按需读取某一种研究范式的详细指导。
        context.skills.tool_definition(),
        # Corpus 会根据本轮数据决定是否提供 citation graph 等可选工具。
        *context.corpus.definitions(),
        # 三种最终提交使用互斥 Schema，由模型显式选择 Answer Contract。
        direct_answer_tool_definition(),
        catalog_answer_tool_definition(),
        research_answer_tool_definition(),
        # 这是内部协议工具，不是用户功能。MiniMaxAgentsModel 用它修复损坏的工具参数。
        {
            "type": "function",
            "function": {
                "name": TEXT_NUDGE_TOOL_NAME,
                "description": "Repair malformed function-call arguments before retrying the required submission tool.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "content": {"type": "string"},
                        "diagnostic_repair_hint": {"type": "string"},
                    },
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
    """决定一批工具结果是否已经构成整个 Agent Run 的最终输出。

    SDK 默认会在工具执行后继续调用模型。本项目只有一种例外：任一提交工具返回 accepted，
    且带有结构化 draft。其他工具，即使执行成功，也不能提前结束研究。
    """

    for result in results:
        if result.tool.name not in _SUBMISSION_CONTRACTS:
            continue
        payload = _json_map(result.output)
        if payload.get("accepted") and isinstance(payload.get("draft"), dict):
            # Runner.run(...) 返回后，调用方会在 result.final_output 读到这个 draft。
            return ToolsToFinalOutputResult(is_final_output=True, final_output=payload["draft"])
    return ToolsToFinalOutputResult(is_final_output=False, final_output=None)


def _function_tool(definition: JsonMap) -> FunctionTool:
    """把项目通用的 function definition 转成 SDK FunctionTool。"""

    function = child_map(definition.get("function"))
    name = str(function.get("name") or "")

    async def invoke(tool_context: ToolContext[ResearchRunContext], raw_arguments: str) -> str:
        """SDK 真正执行工具时调用的统一入口。"""

        # tool_context.context 就是 Runner.run(context=...) 传入的 ResearchRunContext。
        context = tool_context.context
        context.check_cancelled()

        # SDK 保留模型原始参数字符串；业务层只接受解析后的 JSON object。
        arguments, parse_error = _arguments(raw_arguments)
        if parse_error:
            # 参数解析失败属于可恢复的模型错误：返回结构化错误，让同一个 Agent Run 修正参数。
            payload = {
                "error": "invalid_tool_arguments",
                "error_code": "TOOL_ARGUMENTS_INVALID",
                "recoverable": True,
                "next_action": name,
            }
            context.trace.append(tool_trace_item(tool_context.tool_call_id, name, {}, payload))
            context.emit_progress({
                "type": "tool_completed",
                "tool": name,
                "status": "recoverable_error",
                "durationMs": 0,
                "input": {},
                "output": progress_output(name, payload),
                "evidenceIds": [],
            })
            context.control.after_boundary("tool_completed", tool_context.tool_call_id)
            return json.dumps(payload, ensure_ascii=False)
        if name == TEXT_NUDGE_TOOL_NAME:
            if tool_context.tool_call_id not in context.synthetic_repair_call_ids:
                context.control.terminal_reason = "PROVIDER_TOOL_PROTOCOL_VIOLATION"
                raise ResearchSystemError("PROVIDER_TOOL_PROTOCOL_VIOLATION")
            context.synthetic_repair_call_ids.remove(tool_context.tool_call_id)
            # 模型适配器把纯文本响应转换成这个内部调用。这里不接受纯文本为最终答案，而是
            # 明确提醒模型继续使用 submit_research_answer 协议。
            content = str(arguments.get("content") or "")
            diagnostic_repair_hint = str(arguments.get("diagnostic_repair_hint") or "")[:MAX_DIAGNOSTIC_HINT_CHARS]
            if diagnostic_repair_hint:
                recorder = context.turn.eval_recorder
                if recorder:
                    recorder.append(
                        kind="repair.applied",
                        operation_id=tool_context.tool_call_id,
                        payload={
                            "reason_code": "PLAIN_TEXT_RESPONSE_REQUIRES_SUBMISSION",
                            "repair_hint": diagnostic_repair_hint,
                        },
                    )
            repair_message = (
                content.removeprefix(TOOL_ARGUMENT_REPAIR_PREFIX)
                if content.startswith(TOOL_ARGUMENT_REPAIR_PREFIX)
                else ""
            )
            if content and not repair_message:
                quote_cards = _allowed_source_quote_cards(context)
                if quote_cards:
                    mode = "finalize_existing_draft"
                    message = (
                        "Treat the content in the immediately preceding _continue_research_turn call as an "
                        "existing draft. Do not return Markdown as assistant text. Return exactly one "
                        "submit_research_answer function call with no other content, and put the corrected Draft "
                        "in its markdown argument. Do not regenerate or summarize it. Preserve supported draft "
                        "content, use only the allowed "
                        "source_quote_ref values below, cite every factual Markdown block required by the "
                        "Research contract, replace numeric citations such as [1] and the trailing Sources "
                        "list with inline [[source_quote_ref]] markers, and remove claims that these quotes "
                        "do not support."
                    )
                else:
                    mode = "acquire_evidence_or_submit_non_research"
                    message = (
                        "Treat the content in the immediately preceding _continue_research_turn call as an "
                        "existing unpublished draft. Do not return it as assistant text again. If it is a Direct "
                        "answer, return exactly one submit_direct_answer with outcome=needs_clarification only when "
                        "one blocking question remains, otherwise outcome=answered; put the existing draft in its "
                        "markdown argument and return no other content. If it is a Research answer, do not submit it "
                        "yet: call read_paper_content for exact evidence first. Direct or Catalog answers do not need "
                        "Source Quotes, but Catalog still requires a current paper_result_ref. Do not infer references "
                        "from search previews or human-readable Sources labels."
                    )
                if diagnostic_repair_hint:
                    message = f"{message} {_DIAGNOSTIC_HINT_SCOPE}"
                payload = _bounded_model_payload(context, name, {
                    "continue": True,
                    "mode": mode,
                    "message": message,
                    "allowed_source_quotes": quote_cards,
                    "diagnostic_repair_hint": diagnostic_repair_hint or None,
                })
                return json.dumps(payload, ensure_ascii=False)
            message = repair_message or (
                "Respond by calling exactly one of submit_direct_answer, submit_catalog_answer, or "
                "submit_research_answer as the only tool call. Select ZH_CN or EN from the conversation. "
                "For Research, copy exact source_quote_ref values returned by read_paper_content; "
                "placeholders such as [[source_quote_ref]] are invalid."
            )
            if diagnostic_repair_hint:
                message = f"{message} {_DIAGNOSTIC_HINT_SCOPE}"
            payload = {
                "continue": True,
                "message": message,
            }
            if diagnostic_repair_hint:
                payload["diagnostic_repair_hint"] = diagnostic_repair_hint
            return json.dumps(payload, ensure_ascii=False)
        facts = _protocol_facts(context, tool_context.tool_call_id, name)
        action = context.apply_protocol(
            ActionRequested(name),
            facts,
            tool_call_id=tool_context.tool_call_id,
        )
        if not action.model_result.get("accepted"):
            return _finish_protocol_rejection(context, tool_context, name, arguments, action.model_result)
        if name in _SUBMISSION_CONTRACTS:
            return _invoke_submission(context, tool_context, name, arguments, facts)
        try:
            return _invoke_domain(context, tool_context, name, arguments)
        except CorpusGatewayError as error:
            raise ResearchSystemError(_corpus_reason_code(error)) from error
        except Exception as error:
            # 非预期业务异常继续抛给 SDK，但先记录足够的定位信息。
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
        # 项目现有 Schema 并非全部满足 SDK strict schema 的限制，因此由业务代码自行校验。
        strict_json_schema=False,
        # 保留原始异常语义，不使用 SDK 默认的通用失败文案覆盖它。
        _use_default_failure_error_function=False,
    )


def _invoke_domain(
    context: ResearchRunContext,
    tool_context: ToolContext[ResearchRunContext],
    name: str,
    arguments: JsonMap,
) -> str:
    """执行 Research Skill 或 Corpus 工具，并同步维护轨迹与进度。"""

    started = perf_counter()
    # 保存调用前快照，便于观察这个工具到底授权了哪些论文、位置或证据。
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
        "input": progress_input(name, arguments),
    })
    if name == "get_research_skill":
        # Skill 是提示指导，不修改 Corpus 授权状态，但需要记录本轮实际采用了哪些方法。
        skill_id = str(arguments.get("skill_id") or "")
        internal = context.skills.get(skill_id)
        if "error" not in internal and skill_id not in context.skills_used:
            context.skills_used.append(skill_id)
    else:
        # 所有语料工具统一走 ReadingCorpusTools.call，授权链也在其中执行。
        internal = context.corpus.call(name, arguments).payload

    # internal 可能包含仅供系统记录的字段；只把明确允许的部分返回给模型。
    model_payload = child_map(model_facing_payload(internal))
    paper_result_ref = ""
    if name in {"search_paper_candidates", "find_papers_by_identity"} and "error" not in model_payload:
        paper_result_ref = stable_id("paper_result", tool_context.tool_call_id)
        model_payload = {**model_payload, "paper_result_ref": paper_result_ref}
    visible = _bounded_model_payload(context, name, model_payload)
    if paper_result_ref:
        _remember_catalog_result(context, name, internal, visible, paper_result_ref)

    # react_trace 保存“模型调用了什么、模型看到了什么”，不保存任意内部对象。
    context.trace.append(tool_trace_item(tool_context.tool_call_id, name, arguments, visible))
    _record_eval_tool(context, tool_context, name, arguments, internal, visible, before)
    context.emit_progress({
        "type": "tool_completed",
        "tool": name,
        "status": "recoverable_error" if "error" in visible else "success",
        "durationMs": round((perf_counter() - started) * 1000),
        "input": progress_input(name, arguments),
        "output": progress_output(name, visible),
        "evidenceIds": progress_evidence_ids(visible),
    })
    context.check_cancelled()
    context.control.after_boundary("tool_completed", tool_context.tool_call_id)
    # FunctionTool 输出使用字符串；SDK 会把它包装成 tool output 继续下一轮模型调用。
    return json.dumps(visible, ensure_ascii=False)


def _bounded_model_payload(context: ResearchRunContext, name: str, payload: JsonMap) -> JsonMap:
    limit = context.control.limits.max_model_visible_tool_chars
    if _payload_size(payload) <= limit:
        return payload
    if name == "read_paper_content":
        return _bounded_read_payload(context, payload, limit)
    for key in ("candidates", "matches", "locations", "items", "papers", "allowed_source_quotes"):
        values = as_list(payload.get(key))
        if not values:
            continue
        projected: list[object] = []
        for value in values:
            candidate = {**payload, key: [*projected, value]}
            if _payload_size(candidate) > limit:
                break
            projected.append(value)
        if projected:
            return {**payload, key: projected}
    raise RunLimitExceeded("RUN_CONTEXT_BUDGET_EXHAUSTED")


def _bounded_read_payload(context: ResearchRunContext, payload: JsonMap, limit: int) -> JsonMap:
    items = as_list(payload.get("items"))
    projected: list[object] = []
    for item in items:
        candidate = {**payload, "items": [*projected, item]}
        if _payload_size(candidate) > limit:
            break
        projected.append(item)
    visible_refs = {
        str(child_map(quote).get("source_quote_ref") or "")
        for item in projected
        for quote in as_list(child_map(item).get("source_quotes"))
        if child_map(quote).get("source_quote_ref")
    }
    all_refs = {
        str(child_map(quote).get("source_quote_ref") or "")
        for item in items
        for quote in as_list(child_map(item).get("source_quotes"))
        if child_map(quote).get("source_quote_ref")
    }
    for ref in all_refs - visible_refs:
        context.corpus.observations_by_evidence_id.pop(ref, None)
    if projected:
        omitted_location_refs = [
            str(child_map(item).get("location_ref") or "")
            for item in items[len(projected):]
            if child_map(item).get("location_ref")
        ]
        return {
            **payload,
            "items": projected,
            "truncated": bool(omitted_location_refs),
            "omitted_location_refs": omitted_location_refs,
        }
    return {
        "error": "source_unit_exceeds_model_budget",
        "error_code": "SOURCE_UNIT_EXCEEDS_MODEL_BUDGET",
        "recoverable": True,
        "next_action": "get_paper_structure",
        "omitted_location_refs": [
            str(child_map(item).get("location_ref") or "")
            for item in items
            if child_map(item).get("location_ref")
        ],
        "locations": [
            {
                "paper_id": child_map(item).get("paper_id"),
                "location_ref": child_map(item).get("location_ref"),
                "page": child_map(item).get("page"),
                "section": child_map(item).get("section"),
            }
            for item in items
        ],
    }


def _payload_size(payload: JsonMap) -> int:
    return len(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))


def _invoke_submission(
    context: ResearchRunContext,
    tool_context: ToolContext[ResearchRunContext],
    name: str,
    draft: JsonMap,
    facts: ProtocolFacts,
) -> str:
    """校验并规范化一种最终提交。"""

    contract = _SUBMISSION_CONTRACTS[name]
    event = submission_requested(contract, draft, facts)
    decision = context.apply_protocol(event, facts, tool_call_id=tool_context.tool_call_id)
    payload = dict(decision.model_result)
    normalized: JsonMap | None = None
    if decision.model_result.get("accepted"):
        normalized = {
            AnswerContract.DIRECT: lambda: render_direct_submission(draft),
            AnswerContract.CATALOG: lambda: render_catalog_submission(draft, facts),
            AnswerContract.RESEARCH: lambda: render_research_submission(draft, facts),
        }[contract]()
        payload["draft"] = normalized
        # tools_to_final_output 会读取工具返回值；这里再保存一份是为了提供稳健兜底。
        context.final_draft = normalized
    context.trace.append(tool_trace_item(tool_context.tool_call_id, name, draft, payload))
    recorded = {
        "accepted": bool(decision.model_result.get("accepted")),
        "draft": normalized,
        "tool_call_id": tool_context.tool_call_id,
        "model_call_id": context.tool_call_models.get(tool_context.tool_call_id),
    }
    recorder = context.turn.eval_recorder
    if recorder:
        recorder.append(
            kind="answer.validation",
            operation_id=tool_context.tool_call_id,
            payload={**recorded, "submitted_draft": draft, "validation_result": payload},
        )
    context.control.after_boundary("answer_validation", tool_context.tool_call_id)
    return json.dumps(payload, ensure_ascii=False)


def _protocol_facts(context: ResearchRunContext, tool_call_id: str, tool_name: str) -> ProtocolFacts:
    return ProtocolFacts(
        known_source_quotes={
            **context.turn.research_memory.evidence_items_by_id,
            **context.corpus.observations_by_evidence_id,
        },
        catalog_results=context.catalog_results_by_ref,
        sibling_tool_names=context.tool_call_groups.get(tool_call_id, (tool_name,)),
    )


def _allowed_source_quote_cards(context: ResearchRunContext) -> list[JsonMap]:
    quotes = {
        **context.turn.research_memory.evidence_items_by_id,
        **context.corpus.observations_by_evidence_id,
    }
    return [
        {
            "source_quote_ref": ref,
            "title": str(child_map(quotes[ref]).get("title") or ""),
            "section": str(child_map(quotes[ref]).get("section") or ""),
            "page": child_map(quotes[ref]).get("page"),
        }
        for ref in sorted(quotes)
    ]


def _finish_protocol_rejection(
    context: ResearchRunContext,
    tool_context: ToolContext[ResearchRunContext],
    name: str,
    arguments: JsonMap,
    result: JsonMap,
) -> str:
    context.trace.append(tool_trace_item(tool_context.tool_call_id, name, arguments, result))
    context.emit_progress({
        "type": "tool_completed",
        "tool": name,
        "status": "recoverable_error",
        "durationMs": 0,
        "input": progress_input(name, arguments),
        "output": progress_output(name, result),
        "evidenceIds": [],
    })
    context.control.after_boundary("tool_completed", tool_context.tool_call_id)
    return json.dumps(result, ensure_ascii=False)


def _remember_catalog_result(
    context: ResearchRunContext,
    name: str,
    internal: JsonMap,
    visible: JsonMap,
    result_ref: str,
) -> None:
    key = "candidates" if name == "search_paper_candidates" else "matches"
    papers = [child_map(item) for item in as_list(visible.get(key)) if isinstance(item, dict)]
    all_papers = as_list(internal.get(key))
    matched_count = (
        int(internal.get("matched_count"))
        if isinstance(internal.get("matched_count"), int)
        else len(all_papers)
    )
    source_complete = name == "find_papers_by_identity" or internal.get("coverage") == "complete"
    context.catalog_results_by_ref[result_ref] = {
        "matched_count": matched_count,
        "coverage": "complete" if source_complete and len(papers) >= matched_count else "truncated",
        "papers": papers,
    }


def _record_eval_tool(
    context: ResearchRunContext,
    tool_context: ToolContext[ResearchRunContext],
    name: str,
    arguments: JsonMap,
    internal: JsonMap,
    visible: JsonMap,
    before: JsonMap,
) -> None:
    """记录工具内部结果和状态变化；未启用记录器时立即返回。"""

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


def _corpus_reason_code(error: CorpusGatewayError) -> str:
    if error.status_code == 401:
        return "CORPUS_AUTHENTICATION_FAILED"
    if error.status_code >= 500:
        return "CORPUS_UNAVAILABLE"
    return "CORPUS_CONTRACT_VIOLATION"


def _arguments(raw: str) -> tuple[JsonMap, str]:
    """把模型工具参数解析成字典，并把常见供应商结构差异规范化。"""

    try:
        value: Any = json.loads(raw or "{}")
    except json.JSONDecodeError as error:
        return {}, str(error)
    value = _normalize_structured_arguments(value)
    if not isinstance(value, dict):
        return {}, "tool arguments must decode to an object"
    return value, ""


def _json_map(value: object) -> JsonMap:
    """兼容 SDK 可能返回的字典或 JSON 字符串工具结果。"""

    if isinstance(value, dict):
        return value
    if isinstance(value, str):
        try:
            parsed = json.loads(value)
        except json.JSONDecodeError:
            return {}
        return parsed if isinstance(parsed, dict) else {}
    return {}


def _normalize_structured_arguments(value: Any) -> Any:
    """Decode OpenAI-compatible providers that wrap structured values in ``$text``."""

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
