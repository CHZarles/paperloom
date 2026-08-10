"""面向产品的一回合研究编排器。

这层位于 HTTP/CLI 与具体 Agent Runtime 之间：它负责范围裁剪、输入组装、异常收口和
ConversationState 更新，但不参与模型如何选择工具。
"""

from __future__ import annotations

import hashlib
import logging
import os
from dataclasses import replace
from pathlib import Path
from typing import Callable

from .conversation import ConversationState
from ..utils.errors import HarnessCancelled, ResearchSystemError, RunLimitExceeded
from ..utils.models import RUN_TRACE_SCHEMA_VERSION, GoldenDataset, JsonMap, child_map, stable_id, utc_now_iso
from ..evaluation.eval_recorder import EvalRecorder, TraceRetention, prune_trace_runs
from .memory import ResearchMemory
from .runtime import HarnessRuntime, TurnExecutionInput, new_run_id
from .run_control import RunControl, RunLimits
from ..corpus.gateway import CorpusReader


class LiveResearchChatHarness:
    """把无状态 Runtime 包装成可连续对话的产品接口。"""

    def __init__(
        self,
        runtime: HarnessRuntime,
        eval_dump_dir: str | Path | None = None,
    ):
        self.runtime = runtime
        agent_trace_dir = os.getenv("AGENT_TRACE_DIR", "").strip()
        use_agent_trace = eval_dump_dir is None and bool(agent_trace_dir)
        eval_dump_dir = eval_dump_dir or agent_trace_dir or os.getenv("EVAL_DUMP_DIR")
        self.eval_dump_dir = Path(eval_dump_dir) if eval_dump_dir else None
        self.trace_retention = _trace_retention() if use_agent_trace and self.eval_dump_dir else None
        self.eval_capture_failures = 0
        if self.trace_retention:
            self._prune_traces()

    def run_turn(
        self,
        dataset: GoldenDataset,
        state: ConversationState,
        user_message: str,
        progress_listener: Callable[[JsonMap], None] | None = None,
        should_cancel: Callable[[], bool] | None = None,
        case_id_override: str = "",
        corpus_reader: CorpusReader | None = None,
        retry_context: JsonMap | None = None,
        run_limits: RunLimits | None = None,
        run_control: RunControl | None = None,
        request_id: str = "",
    ) -> tuple[JsonMap, ConversationState]:
        """执行一轮用户消息，返回 Run 和下一轮要持久化的状态。"""

        if not user_message.strip():
            raise ValueError("user_message is required")
        # 每一轮只看调用方授权的论文范围，不能依赖模型自行约束。
        scoped = _dataset_for_scope(dataset, state.effective_scope_paper_ids(dataset))

        # case_id 用于结果内部关联；run_id 则标识这一次真实执行。
        case_id = case_id_override or _live_case_id(scoped, state, user_message)
        run_id = new_run_id()
        control = run_control or RunControl(run_limits, should_cancel=should_cancel)
        recorder = self._open_recorder(run_id)
        if recorder:
            recorder.append(
                kind="run.started",
                operation_id="run",
                payload={
                    "case_id": case_id,
                    "request_id": request_id or None,
                    "conversation_id": state.conversation_id,
                    "turn_index": state.turn_index + 1,
                    "question": user_message,
                    "conversation_messages": state.model_messages(),
                    "research_memory": {
                        "selected_paper_ids": state.selected_paper_ids,
                        "selected_evidence_ids": state.selected_evidence_ids,
                        "evidence_items_by_id": state.evidence_items_by_id,
                    },
                    "corpus_paper_ids": sorted(scoped.paper_records_by_id),
                },
            )
        try:
            # Runtime 看不到完整 ConversationState，只接收执行所需的最小投影。
            result = self.runtime.run_turn(TurnExecutionInput(
                dataset=scoped,
                case_id=case_id,
                run_id=run_id,
                question=user_message,
                conversation_messages=state.model_messages(),
                research_memory=ResearchMemory(
                    selected_paper_ids=list(state.selected_paper_ids),
                    selected_evidence_ids=list(state.selected_evidence_ids),
                    evidence_items_by_id=dict(state.evidence_items_by_id),
                ),
                corpus_reader=corpus_reader,
                progress_listener=progress_listener,
                should_cancel=should_cancel,
                eval_recorder=recorder,
                retry_context=child_map(retry_context),
                run_limits=control.limits,
                run_control=control,
            ))
            run = result.run
        except RunLimitExceeded as error:
            run = _limited_run(run_id, case_id, user_message, error.reason_code, control)
        except ResearchSystemError as error:
            run = _technical_failure_run(
                run_id,
                case_id,
                user_message,
                str(error),
                reason_code=error.reason_code,
                control=control,
            )
        except (HarnessCancelled, BrokenPipeError, ConnectionResetError) as error:
            if recorder:
                recorder.append(
                    kind="run.error",
                    operation_id="run",
                    payload={"error_type": type(error).__name__, "message": str(error)},
                )
            run = _cancelled_run(run_id, case_id, user_message, control)
        except Exception as error:
            # 普通技术异常被收敛成 FAILED_TECHNICAL Run，调用方仍能得到稳定响应结构。
            if recorder:
                recorder.append(
                    kind="run.error",
                    operation_id="run",
                    payload={"error_type": type(error).__name__, "message": str(error)},
                )
            run = _technical_failure_run(
                run_id,
                case_id,
                user_message,
                str(error),
                reason_code="INTERNAL_UNEXPECTED",
                control=control,
            )
        self._finish_recorder(recorder, run)

        # 只有已经形成 Run 的结果才能推进跨回合记忆；临时 Context 不会被直接持久化。
        return run, state.updated_from_run(scoped, run, user_message)

    def _open_recorder(self, run_id: str) -> EvalRecorder | None:
        if self.eval_dump_dir is None:
            return None
        try:
            return EvalRecorder(self.eval_dump_dir, run_id)
        except Exception as error:
            # 评测数据落盘失败不能改变主回答，只通过计数和日志暴露。
            self.eval_capture_failures += 1
            logging.getLogger(__name__).error("eval capture open failed: %s", error)
            return None

    def _finish_recorder(self, recorder: EvalRecorder | None, result: JsonMap) -> None:
        if recorder and not recorder.finish(result):
            self.eval_capture_failures += 1
            logging.getLogger(__name__).error("eval capture failed for run_id=%s", recorder.run_id)
        if recorder and self.trace_retention:
            self._prune_traces()

    def _prune_traces(self) -> None:
        if self.eval_dump_dir is None or self.trace_retention is None:
            return
        try:
            prune_trace_runs(self.eval_dump_dir, self.trace_retention)
        except Exception as error:
            logging.getLogger(__name__).error("agent trace cleanup failed: %s", error)


def _live_case_id(dataset: GoldenDataset, state: ConversationState, question: str) -> str:
    digest = hashlib.sha1(
        (
            state.conversation_id
            + "\n"
            + str(state.turn_index + 1)
            + "\n"
            + question
            + "\n"
            + "\n".join(sorted(dataset.paper_records_by_id))
        ).encode("utf-8")
    ).hexdigest()[:12]
    return f"live_chat_{digest}"


def _trace_retention() -> TraceRetention:
    return TraceRetention(
        max_age_seconds=_positive_env_int("AGENT_TRACE_RETENTION_DAYS", 7) * 86_400,
        max_bytes=_positive_env_int("AGENT_TRACE_MAX_BYTES", 10 * 1024 * 1024 * 1024),
        incomplete_grace_seconds=_positive_env_int("AGENT_TRACE_INCOMPLETE_GRACE_HOURS", 24) * 3_600,
    )


def _positive_env_int(name: str, default: int) -> int:
    raw = os.getenv(name, "").strip()
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError as error:
        raise ValueError(f"{name} must be a positive integer") from error
    if value <= 0:
        raise ValueError(f"{name} must be a positive integer")
    return value


def _technical_failure_run(
    run_id: str,
    case_id: str,
    question: str,
    message: str,
    *,
    reason_code: str = "INTERNAL_UNEXPECTED",
    control: RunControl | None = None,
) -> JsonMap:
    answer = {
        "answer_id": stable_id("answer", case_id),
        "question_id": case_id,
        "status": "FAILED_TECHNICAL",
        "outcome": None,
        "answer_type": "technical_failure",
        "summary": "The research turn failed technically.",
        "markdown": "The research turn failed technically.",
        "fields": {},
        "cited_source_quote_refs": [],
    }
    now = utc_now_iso()
    return {
        "schema_version": RUN_TRACE_SCHEMA_VERSION,
        "run_id": run_id,
        "question_id": case_id,
        "case_id": case_id,
        "harness_id": "python_skill_guided_react_harness_v1",
        "started_at": now,
        "completed_at": now,
        "status": "FAILED_TECHNICAL",
        "result_status": "FAILED_TECHNICAL",
        "memory_update": {},
        "skills_used": [],
        "react_trace": [],
        "paper_candidates": [],
        "evidence_ledger": {
            "ledger_id": stable_id("ledger", case_id),
            "question_id": case_id,
            "items": [],
            "rejected_items": [],
            "missing_evidence": [],
        },
        "citation_validation": {"passed": False, "error": "technical_failure"},
        "research_answer": answer,
        "final_answer": answer,
        "control": {
            "reason_code": reason_code,
            "terminal_disposition": "FAILED_TECHNICAL",
            **(control.to_dict() if control else {}),
        },
        "diagnostics": {
            "finish_reason": "react_runtime_failed",
            "tool_call_count": 0,
            "error": message,
        },
    }


def _limited_run(
    run_id: str,
    case_id: str,
    question: str,
    reason_code: str,
    control: RunControl,
) -> JsonMap:
    markdown = (
        "This research request reached its execution limit before a verifiable answer was ready. "
        "Narrow the question or start a new turn."
    )
    answer = {
        "answer_id": stable_id("answer", case_id),
        "question_id": case_id,
        "status": "LIMITED",
        "outcome": "abstained",
        "answer_type": "execution_limited",
        "summary": markdown,
        "markdown": markdown,
        "fields": {},
        "cited_source_quote_refs": [],
    }
    now = utc_now_iso()
    return {
        "schema_version": RUN_TRACE_SCHEMA_VERSION,
        "run_id": run_id,
        "question_id": case_id,
        "case_id": case_id,
        "harness_id": "python_skill_guided_react_harness_v1",
        "started_at": now,
        "completed_at": now,
        "status": "LIMITED",
        "result_status": "LIMITED",
        "memory_update": {},
        "skills_used": [],
        "react_trace": [],
        "paper_candidates": [],
        "evidence_ledger": {
            "ledger_id": stable_id("ledger", case_id),
            "question_id": case_id,
            "items": [],
            "rejected_items": [],
            "missing_evidence": [],
        },
        "citation_validation": {"passed": False, "error": reason_code},
        "research_answer": answer,
        "final_answer": answer,
        "control": {
            "reason_code": reason_code,
            "terminal_disposition": "LIMITED",
            **control.to_dict(),
        },
        "diagnostics": {
            "finish_reason": reason_code,
            "tool_call_count": 0,
            "control": control.to_dict(),
        },
    }


def _cancelled_run(
    run_id: str,
    case_id: str,
    question: str,
    control: RunControl,
) -> JsonMap:
    answer = {
        "answer_id": stable_id("answer", case_id),
        "question_id": case_id,
        "status": "CANCELLED",
        "outcome": None,
        "answer_type": "cancelled",
        "summary": "",
        "markdown": "",
        "fields": {},
        "cited_source_quote_refs": [],
    }
    now = utc_now_iso()
    return {
        "schema_version": RUN_TRACE_SCHEMA_VERSION,
        "run_id": run_id,
        "question_id": case_id,
        "case_id": case_id,
        "harness_id": "python_skill_guided_react_harness_v1",
        "started_at": now,
        "completed_at": now,
        "status": "CANCELLED",
        "result_status": "CANCELLED",
        "memory_update": {},
        "skills_used": [],
        "react_trace": [],
        "paper_candidates": [],
        "evidence_ledger": {
            "ledger_id": stable_id("ledger", case_id),
            "question_id": case_id,
            "items": [],
            "rejected_items": [],
            "missing_evidence": [],
        },
        "citation_validation": {"passed": False, "error": "RUN_CANCELLED"},
        "research_answer": answer,
        "final_answer": answer,
        "control": {
            "reason_code": "RUN_CANCELLED",
            "terminal_disposition": "CANCELLED",
            **control.to_dict(),
        },
        "diagnostics": {
            "finish_reason": "RUN_CANCELLED",
            "tool_call_count": 0,
            "control": control.to_dict(),
        },
    }
def _dataset_for_scope(dataset: GoldenDataset, paper_ids: list[str]) -> GoldenDataset:
    scoped = set(paper_ids)
    if not scoped:
        return dataset
    return replace(
        dataset,
        paper_records_by_id={
            paper_id: record
            for paper_id, record in dataset.paper_records_by_id.items()
            if paper_id in scoped
        },
        reading_models_by_paper_id={
            paper_id: model
            for paper_id, model in dataset.reading_models_by_paper_id.items()
            if paper_id in scoped
        },
        citation_edges=[
            edge for edge in dataset.citation_edges
            if edge.get("from_paper_id") in scoped or edge.get("to_paper_id") in scoped
        ],
    )
