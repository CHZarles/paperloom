from __future__ import annotations

import hashlib
from dataclasses import replace
from datetime import UTC, datetime
from typing import Callable

from .conversation import ConversationState
from .errors import HarnessCancelled
from .golden_case import case_question, conversation_state_for_case
from .llm import ChatModel
from .memory import ResearchMemory
from .models import RUN_TRACE_SCHEMA_VERSION, GoldenDataset, JsonMap, stable_id
from .runtime import HarnessRuntime, LegacyHarnessRuntime, TurnExecutionInput, new_run_id


class LiveResearchChatHarness:
    """Conversation wrapper around the same skill-guided ReAct agent used by golden cases."""

    def __init__(self, runtime_or_model: HarnessRuntime | ChatModel, max_completion_tokens: int = 3000):
        self.runtime: HarnessRuntime = (
            LegacyHarnessRuntime(runtime_or_model, max_completion_tokens=max_completion_tokens)
            if isinstance(runtime_or_model, ChatModel)
            else runtime_or_model
        )

    def run_question(self, dataset: GoldenDataset, question: str) -> JsonMap:
        run, _ = self.run_turn(dataset, ConversationState.new("transient_live_chat"), question)
        return run

    def run_case(self, dataset: GoldenDataset, case: JsonMap) -> JsonMap:
        state = conversation_state_for_case(dataset, case)
        run, _ = self.run_turn(dataset, state, case_question(case))
        run["case_id"] = str(case["id"])
        run["question_id"] = str(case["id"])
        return run

    def run_turn(
        self,
        dataset: GoldenDataset,
        state: ConversationState,
        user_message: str,
        progress_listener: Callable[[JsonMap], None] | None = None,
        should_cancel: Callable[[], bool] | None = None,
    ) -> tuple[JsonMap, ConversationState]:
        if not user_message.strip():
            raise ValueError("user_message is required")
        scoped = _dataset_for_scope(dataset, state.effective_scope_paper_ids(dataset))
        case_id = _live_case_id(scoped, state, user_message)
        run_id = new_run_id()
        try:
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
                progress_listener=progress_listener,
                should_cancel=should_cancel,
            ))
            run = result.run
        except HarnessCancelled:
            raise
        except Exception as error:
            run = _technical_failure_run(run_id, case_id, user_message, str(error))
        return run, state.updated_from_run(scoped, run, user_message)


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
