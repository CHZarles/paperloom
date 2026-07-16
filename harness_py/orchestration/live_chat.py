"""面向产品的一回合研究编排器。

这层位于 HTTP/CLI 与具体 Agent Runtime 之间：它负责范围裁剪、输入组装、异常收口和
ConversationState 更新，但不参与模型如何选择工具。
"""

from __future__ import annotations

import hashlib
import logging
import os
from dataclasses import replace
from datetime import UTC, datetime
from pathlib import Path
from typing import Callable

from .conversation import ConversationState
from ..core.errors import HarnessCancelled
from ..core.models import RUN_TRACE_SCHEMA_VERSION, GoldenDataset, JsonMap, stable_id
from ..evaluation.eval_recorder import EvalRecorder
from .memory import ResearchMemory
from .runtime import HarnessRuntime, TurnExecutionInput, new_run_id
from ..corpus.gateway import CorpusReader


class LiveResearchChatHarness:
    """把无状态 Runtime 包装成可连续对话的产品接口。"""

    def __init__(
        self,
        runtime: HarnessRuntime,
        eval_dump_dir: str | Path | None = None,
    ):
        self.runtime = runtime
        eval_dump_dir = eval_dump_dir or os.getenv("EVAL_DUMP_DIR")
        self.eval_dump_dir = Path(eval_dump_dir) if eval_dump_dir else None
        self.eval_capture_failures = 0

    def run_turn(
        self,
        dataset: GoldenDataset,
        state: ConversationState,
        user_message: str,
        progress_listener: Callable[[JsonMap], None] | None = None,
        should_cancel: Callable[[], bool] | None = None,
        case_id_override: str = "",
        corpus_reader: CorpusReader | None = None,
    ) -> tuple[JsonMap, ConversationState]:
        """执行一轮用户消息，返回 Run 和下一轮要持久化的状态。"""

        if not user_message.strip():
            raise ValueError("user_message is required")
        # 每一轮只看调用方授权的论文范围，不能依赖模型自行约束。
        scoped = _dataset_for_scope(dataset, state.effective_scope_paper_ids(dataset))

        # case_id 用于结果内部关联；run_id 则标识这一次真实执行。
        case_id = case_id_override or _live_case_id(scoped, state, user_message)
        run_id = new_run_id()
        recorder = self._open_recorder(run_id)
        if recorder:
            recorder.append(
                kind="run.started",
                operation_id="run",
                payload={
                    "case_id": case_id,
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
            ))
            run = result.run
        except (HarnessCancelled, BrokenPipeError, ConnectionResetError) as error:
            if recorder:
                recorder.append(
                    kind="run.error",
                    operation_id="run",
                    payload={"error_type": type(error).__name__, "message": str(error)},
                )
                self._finish_recorder(recorder, {
                    "run_id": run_id,
                    "status": "CANCELLED",
                    "error_type": type(error).__name__,
                    "message": str(error),
                })
            raise
        except Exception as error:
            # 普通技术异常被收敛成 FAILED_TECHNICAL Run，调用方仍能得到稳定响应结构。
            if recorder:
                recorder.append(
                    kind="run.error",
                    operation_id="run",
                    payload={"error_type": type(error).__name__, "message": str(error)},
                )
            run = _technical_failure_run(run_id, case_id, user_message, str(error))
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


def _technical_failure_run(run_id: str, case_id: str, question: str, message: str) -> JsonMap:
    answer = {
        "answer_id": stable_id("answer", case_id),
        "question_id": case_id,
        "status": "FAILED_TECHNICAL",
        "outcome": None,
        "answer_type": "technical_failure",
        "summary": "The research turn failed technically.",
        "markdown": "The research turn failed technically.",
        "fields": {},
        "cited_evidence_ids": [],
    }
    now = _now()
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
        "diagnostics": {
            "finish_reason": "react_runtime_failed",
            "tool_call_count": 0,
            "error": message,
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


def _now() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")
