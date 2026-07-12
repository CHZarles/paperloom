from __future__ import annotations

import hashlib
from dataclasses import replace
from datetime import UTC, datetime

from .agent_harness import ResearchAgentHarness
from .conversation import ConversationState
from .golden_case import case_question, conversation_state_for_case
from .llm import ChatModel
from .models import RUN_TRACE_SCHEMA_VERSION, GoldenDataset, JsonMap, stable_id
from .stage_prototype.intent import TurnInterpreter
from .stage_prototype.models import TurnDecision, TurnFrame


class LiveResearchChatHarness:
    """Live chat wrapper around the same artifact-producing agent loop."""

    def __init__(self, model: ChatModel, max_turns: int = 8, max_completion_tokens: int = 3000):
        self.model = model
        self.max_completion_tokens = max_completion_tokens
        self.agent = ResearchAgentHarness(
            model,
            max_turns=max_turns,
            max_completion_tokens=max_completion_tokens,
        )

    def run_question(self, dataset: GoldenDataset, question: str) -> JsonMap:
        run, _state = self.run_turn(
            dataset,
            ConversationState.new("transient_live_chat"),
            question,
        )
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
    ) -> tuple[JsonMap, ConversationState]:
        if not user_message.strip():
            raise ValueError("user_message is required")
        scoped_dataset = _dataset_for_scope(dataset, state.effective_scope_paper_ids(dataset))
        case = _live_case(scoped_dataset, user_message, state)
        turn = TurnFrame(
            turn_id=str(case["id"]),
            question=user_message,
            allowed_paper_ids=sorted(scoped_dataset.paper_records_by_id),
            conversation_context=state.prompt_context(scoped_dataset),
        )
        interpreter = TurnInterpreter(
            self.model,
            max_tokens=min(self.max_completion_tokens, 2400),
        )
        try:
            decision = interpreter.interpret(turn, scoped_dataset)
        except Exception as error:
            run = _turn_failure_run(case, str(error), interpreter.last_diagnostics)
            return run, state.updated_from_run(scoped_dataset, run, user_message)

        if decision.route == "direct":
            run = _decision_only_run(case, decision, interpreter.last_diagnostics)
            return run, state.updated_from_run(scoped_dataset, run, user_message)

        state = state.with_active_task(decision.active_task())
        if decision.route == "clarify":
            state = state.with_pending_interaction(decision.pending_interaction)
            run = _decision_only_run(
                case,
                decision,
                interpreter.last_diagnostics,
                pending_interaction=state.pending_interaction,
            )
            return run, state.updated_from_run(scoped_dataset, run, user_message)

        state = replace(state, pending_interaction={})
        research_case = _live_case(
            scoped_dataset,
            user_message,
            state,
            effective_goal=decision.effective_goal,
        )
        run = self.agent.run_case_with_intent(
            scoped_dataset,
            research_case,
            decision.to_intent_frame(),
        )
        _merge_turn_decision_diagnostics(run, interpreter.last_diagnostics)
        return run, state.updated_from_run(scoped_dataset, run, user_message)


def _live_case(
    dataset: GoldenDataset,
    question: str,
    state: ConversationState | None = None,
    effective_goal: str = "",
) -> JsonMap:
    if not question.strip():
        raise ValueError("question is required")
    state = state or ConversationState.new("transient_live_chat")
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
    paper_ids = sorted(dataset.paper_records_by_id)
    return {
        "id": f"live_chat_{digest}",
        "question": {"text": effective_goal or question},
        "raw_question": question,
        "conversation_context": state.prompt_context(dataset),
        "corpus_scope": {"allowed_paper_ids": paper_ids},
    }


def _decision_only_run(
    case: JsonMap,
    decision: TurnDecision,
    diagnostics: JsonMap,
    pending_interaction: JsonMap | None = None,
) -> JsonMap:
    case_id = str(case["id"])
    clarify = decision.route == "clarify"
    status = "NEEDS_CLARIFICATION" if clarify else "COMPLETED"
    outcome = "needs_clarification" if clarify else "answered"
    pending = pending_interaction or {}
    markdown = decision.direct_reply or _clarification_markdown(pending)
    answer_type = "ambiguity_clarification" if clarify else "conversation"
    intent = decision.to_intent_frame() if clarify else None
    intent_frame = {
        "intent_id": stable_id("intent", case_id),
        "question_id": case_id,
        "raw_question": case.get("raw_question") or case["question"]["text"],
        "normalized_question": decision.effective_goal or case["question"]["text"],
        "primary_paradigm": intent.primary_paradigm if intent else "direct_conversation",
        "entities": [],
        "paper_mentions": [],
        "method_mentions": [],
        "dataset_mentions": [],
        "constraints": intent.constraints if intent else {},
        "answer_type": answer_type,
        "ambiguity_status": "needs_user_choice" if clarify else "unambiguous",
        "actionability": "blocking" if clarify else "none",
        "requires_corpus_observation": False,
        "required_evidence_types": [],
        "required_capabilities": [],
    }
    answer = {
        "answer_id": stable_id("answer", case_id),
        "question_id": case_id,
        "status": status,
        "outcome": outcome,
        "answer_type": answer_type,
        "summary": markdown[:400],
        "markdown": markdown,
        "fields": {},
        "sections": [],
        "cited_claim_ids": [],
        "cited_evidence_ids": [],
        "reasoning_artifact_ids": [],
        "verification_id": stable_id("verification", case_id),
    }
    now = _now()
    return {
        "schema_version": RUN_TRACE_SCHEMA_VERSION,
        "run_id": stable_id("run", case_id),
        "question_id": case_id,
        "case_id": case_id,
        "harness_id": "python_single_decision_turn_harness_v1",
        "started_at": now,
        "completed_at": now,
        "status": status,
        "result_status": status,
        "memory_update": {"pending_interaction": pending or None},
        "intent_frame": intent_frame,
        "retrieval_plan": {
            "plan_id": stable_id("plan", case_id),
            "question_id": case_id,
            "paradigm": intent_frame["primary_paradigm"],
            "actionability": intent_frame["actionability"],
            "target_entities": [],
            "required_recall_targets": [],
            "expected_evidence_types": [],
            "strategy_steps": [],
            "hard_negative_policy": [],
            "stop_conditions": ["request_user_input"] if clarify else ["direct_reply"],
        },
        "stage_trace": [],
        "evidence_ledger": {
            "ledger_id": stable_id("ledger", case_id),
            "question_id": case_id,
            "items": [],
            "rejected_items": [],
            "missing_evidence": [],
        },
        "claim_graph": {
            "graph_id": stable_id("claim_graph", case_id),
            "question_id": case_id,
            "claims": [],
            "edges": [],
        },
        "reasoning_artifacts": [],
        "verification_pass": {
            "verification_id": stable_id("verification", case_id),
            "question_id": case_id,
            "ambiguity_resolution": {
                "status": "pending_user_input" if clarify else "not_required",
            },
            "constraint_check_results": [],
            "required_evidence_status": [],
            "required_capabilities_attempted": [],
            "missing_required_evidence": [],
            "unsupported_claim_count": 0,
            "contradicted_claim_count": 0,
            "abstention_required": False,
            "satisfied_trace_obligation_ids": [],
            "failed_trace_obligation_ids": [],
        },
        "research_answer": answer,
        "final_answer": answer,
        "diagnostics": {
            "finish_reason": "turn_decision_clarification" if clarify else "turn_decision_direct",
            "paradigm": intent_frame["primary_paradigm"],
            "completed_stage_count": 0,
            "stage_count": 0,
            "tool_call_count": 0,
            **diagnostics,
            "duration_ms": int(diagnostics.get("model_latency_ms") or 0),
            "last_model_content_preview": "",
        },
    }


def _turn_failure_run(case: JsonMap, message: str, diagnostics: JsonMap) -> JsonMap:
    decision = TurnDecision(
        route="direct",
        effective_goal="",
        task={},
        constraints={},
        primary_paradigm="",
        answer_shape="unknown",
        direct_reply=message,
    )
    run = _decision_only_run(case, decision, diagnostics)
    run["status"] = "FAILED_TECHNICAL"
    run["result_status"] = "FAILED_TECHNICAL"
    run["research_answer"]["status"] = "FAILED_TECHNICAL"
    run["research_answer"]["outcome"] = None
    run["final_answer"]["status"] = "FAILED_TECHNICAL"
    run["final_answer"]["outcome"] = None
    run["diagnostics"]["finish_reason"] = "turn_decision_failed"
    run["diagnostics"]["error"] = message
    return run


def _merge_turn_decision_diagnostics(run: JsonMap, decision: JsonMap) -> None:
    diagnostics = run.setdefault("diagnostics", {})
    for field in ("model_call_count", "prompt_tokens", "completion_tokens", "total_tokens", "model_latency_ms"):
        diagnostics[field] = int(diagnostics.get(field) or 0) + int(decision.get(field) or 0)
    diagnostics["duration_ms"] = int(diagnostics.get("duration_ms") or 0) + int(
        decision.get("model_latency_ms") or 0
    )


def _clarification_markdown(pending: JsonMap) -> str:
    question = str(pending.get("question") or "Please clarify your request.")
    options = pending.get("options") or []
    if not options:
        return question
    rendered = "\n".join(
        f"{index}. {option.get('label') or option.get('id')}"
        for index, option in enumerate(options, start=1)
    )
    return f"{question}\n\n{rendered}"


def _now() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")


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
