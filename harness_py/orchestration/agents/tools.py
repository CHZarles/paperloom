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

from ...core.models import JsonMap, child_map
from ...corpus.tools import model_facing_payload
from ..evidence_coverage import coverage_validation_error, evaluate_evidence_coverage
from ..research_contract import (
    FINAL_TOOL_NAME,
    answer_validation_error,
    final_answer_tool_definition,
)
from ..run_output import (
    progress_evidence_ids,
    progress_input,
    progress_output,
    tool_trace_item,
)
from .context import ResearchRunContext
from .model import TEXT_NUDGE_TOOL_NAME


def build_agent_tools(context: ResearchRunContext) -> list[FunctionTool]:
    """创建本轮 Agent 可见的全部工具。

    工具由四类组成：研究方法指导、语料工具、最终提交工具，以及供应商返回纯文本时使用的
    内部继续工具。工具列表按请求创建，因为 Corpus 能力可能随 Dataset 改变。
    """

    definitions = [
        # get_research_skill：让模型按需读取某一种研究范式的详细指导。
        context.skills.tool_definition(),
        # Corpus 会根据本轮数据决定是否提供 citation graph 等可选工具。
        *context.corpus.definitions(),
        # 最终答案也建模成工具，这样可以在 Runner 循环内部做确定性校验。
        final_answer_tool_definition(),
        # 这是内部协议工具，不是用户功能。MiniMaxAgentsModel 在收到纯文本响应时调用它。
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
    """决定一批工具结果是否已经构成整个 Agent Run 的最终输出。

    SDK 默认会在工具执行后继续调用模型。本项目只有一种例外：最终提交工具返回 accepted，
    且带有结构化 draft。其他工具，即使执行成功，也不能提前结束研究。
    """

    for result in results:
        if result.tool.name != FINAL_TOOL_NAME:
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
            payload = {"error": "invalid_tool_arguments", "message": parse_error}
            context.trace.append(tool_trace_item(tool_context.tool_call_id, name, {}, payload))
            return json.dumps(payload, ensure_ascii=False)
        if name == TEXT_NUDGE_TOOL_NAME:
            # 模型适配器把纯文本响应转换成这个内部调用。这里不接受纯文本为最终答案，而是
            # 明确提醒模型继续使用 submit_research_answer 协议。
            return json.dumps({
                "continue": True,
                "message": (
                    "Finish by calling submit_research_answer as the only tool call. "
                    "Copy exact evidence IDs returned by read_locations; placeholders such as "
                    "[[evidence_id]] are invalid. Remove claims not directly supported by cited spans."
                ),
            }, ensure_ascii=False)
        if name == FINAL_TOOL_NAME:
            # 最终工具走单独的校验分支，不能当作普通 Corpus 工具分发。
            return _invoke_final(context, tool_context, arguments)
        try:
            return _invoke_domain(context, tool_context, name, arguments)
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
    visible = child_map(model_facing_payload(internal))

    # react_trace 保存“模型调用了什么、模型看到了什么”，不保存任意内部对象。
    context.trace.append(tool_trace_item(tool_context.tool_call_id, name, arguments, visible))
    _record_eval_tool(context, tool_context, name, arguments, internal, visible, before)
    context.emit_progress({
        "type": "tool_completed",
        "tool": name,
        "status": "success" if "error" not in visible else "failed",
        "durationMs": round((perf_counter() - started) * 1000),
        "input": progress_input(name, arguments),
        "output": progress_output(name, visible),
        "evidenceIds": progress_evidence_ids(visible),
    })
    context.check_cancelled()
    # FunctionTool 输出使用字符串；SDK 会把它包装成 tool output 继续下一轮模型调用。
    return json.dumps(visible, ensure_ascii=False)


def _invoke_final(
    context: ResearchRunContext,
    tool_context: ToolContext[ResearchRunContext],
    draft: JsonMap,
) -> str:
    """校验模型提交的最终答案；通过时将草稿标记为可结束 Run。"""

    # 最终提交必须独占一步，避免同批工具调用在答案确定后继续改变证据状态。
    siblings = context.tool_call_groups.get(tool_context.tool_call_id, (FINAL_TOOL_NAME,))
    validation_error = ""
    if len(siblings) != 1:
        validation_error = "submit_research_answer must be the only tool call in the final step"
    else:
        # 可引用证据来自两部分：上一轮已经接受的记忆，以及本轮 read_locations 的真实结果。
        known_evidence = {
            **context.turn.research_memory.evidence_items_by_id,
            **context.corpus.observations_by_evidence_id,
        }
        validation_error = answer_validation_error(
            draft,
            known_evidence,
            bool(context.corpus.observations_by_evidence_id),
        )
        if not validation_error:
            validation_error = coverage_validation_error(
                evaluate_evidence_coverage(
                    draft=draft,
                    question=context.turn.question,
                    paper_records_by_id=context.turn.dataset.paper_records_by_id,
                    trace=context.trace,
                    known_evidence=known_evidence,
                ),
                context.turn.dataset.paper_records_by_id,
            )
    accepted = not validation_error
    visible = {"accepted": accepted}
    if validation_error:
        visible["error"] = validation_error
    context.trace.append(
        tool_trace_item(tool_context.tool_call_id, FINAL_TOOL_NAME, draft, visible)
    )
    if accepted:
        # tools_to_final_output 会读取工具返回值；这里再保存一份是为了提供稳健兜底。
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
