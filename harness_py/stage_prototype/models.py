from __future__ import annotations

from dataclasses import asdict, dataclass, field
from enum import Enum
from typing import Any

from ..models import JsonMap, as_list, child_map


MAX_PROMPT_EVIDENCE_ITEMS = 12
_RESULT_STATUS_ABSENT = object()


class ExecutionStatus(str, Enum):
    COMPLETED = "COMPLETED"
    NEEDS_CLARIFICATION = "NEEDS_CLARIFICATION"
    INCOMPLETE_PRECISE = "INCOMPLETE_PRECISE"
    FAILED_TECHNICAL = "FAILED_TECHNICAL"


class ResearchOutcome(str, Enum):
    ANSWERED = "answered"
    NEEDS_CLARIFICATION = "needs_clarification"
    ABSTAINED = "abstained"
    PARTIAL = "partial"


def normalize_execution_status(value: object) -> str | None:
    allowed = {status.value for status in ExecutionStatus}
    return value if isinstance(value, str) and value in allowed else None


def execution_status_error(
    run_status: object,
    answer_status: object,
    result_status: object = _RESULT_STATUS_ABSENT,
) -> str:
    values = [("run.status", run_status), ("research_answer.status", answer_status)]
    if result_status is not _RESULT_STATUS_ABSENT:
        values.append(("result_status", result_status))
    normalized: list[str] = []
    for label, value in values:
        status = normalize_execution_status(value)
        if status is None:
            allowed = ", ".join(item.value for item in ExecutionStatus)
            return f"{label} must be one of {allowed}"
        normalized.append(status)
    if len(set(normalized)) != 1:
        return "execution statuses disagree"
    return ""


def normalize_research_outcome(value: object) -> str | None:
    allowed = {outcome.value for outcome in ResearchOutcome}
    return value if isinstance(value, str) and value in allowed else None


def research_outcome_error(
    status: object,
    outcome: object,
    *,
    outcome_present: bool = True,
) -> str:
    normalized_status = normalize_execution_status(status)
    if normalized_status == ExecutionStatus.FAILED_TECHNICAL.value:
        if not outcome_present or outcome is None:
            return ""
        return "technical failures cannot declare a research outcome"
    if not outcome_present or outcome is None:
        return "answer.outcome must be present for non-technical execution"
    normalized_outcome = normalize_research_outcome(outcome)
    if normalized_outcome is None:
        return "answer.outcome must be one of answered, needs_clarification, abstained, partial"
    if normalized_status == "NEEDS_CLARIFICATION" and normalized_outcome != "needs_clarification":
        return "NEEDS_CLARIFICATION requires outcome=needs_clarification"
    if normalized_status == "COMPLETED" and normalized_outcome == "needs_clarification":
        return "COMPLETED cannot declare outcome=needs_clarification"
    if normalized_status == "INCOMPLETE_PRECISE" and normalized_outcome not in {"abstained", "partial"}:
        return "INCOMPLETE_PRECISE requires outcome=abstained or partial"
    return ""


@dataclass(frozen=True)
class TurnFrame:
    turn_id: str
    question: str
    allowed_paper_ids: list[str]
    conversation_context: JsonMap = field(default_factory=dict)
    required_answer_field_names: list[str] = field(default_factory=list)


@dataclass(frozen=True)
class IntentFrame:
    primary_paradigm: str
    normalized_goal: str
    answer_shape: str
    paper_references: list[JsonMap]
    requested_aspects: list[str]
    constraints: JsonMap
    ambiguity: JsonMap
    confidence: float
    required_evidence_types: list[str] = field(default_factory=list)
    required_capabilities: list[str] = field(default_factory=list)
    actionability: str = "none"
    requires_corpus_observation: bool = False

    @property
    def ambiguity_status(self) -> str:
        return str(self.ambiguity.get("status") or "unambiguous")

    def to_dict(self) -> JsonMap:
        return asdict(self)

    @classmethod
    def from_dict(cls, value: JsonMap) -> IntentFrame:
        return cls(
            primary_paradigm=str(value.get("primary_paradigm") or ""),
            normalized_goal=str(value.get("normalized_goal") or ""),
            answer_shape=str(value.get("answer_shape") or "unknown"),
            paper_references=[child_map(item) for item in as_list(value.get("paper_references"))],
            requested_aspects=[str(item) for item in as_list(value.get("requested_aspects"))],
            constraints=child_map(value.get("constraints")),
            ambiguity=child_map(value.get("ambiguity")),
            confidence=float(value.get("confidence") or 0.0),
            required_evidence_types=[str(item) for item in as_list(value.get("required_evidence_types"))],
            required_capabilities=[str(item) for item in as_list(value.get("required_capabilities"))],
            actionability=str(value.get("actionability") or "none"),
            requires_corpus_observation=bool(value.get("requires_corpus_observation")),
        )


ParadigmFrame = IntentFrame


@dataclass(frozen=True)
class TurnDecision:
    route: str
    effective_goal: str
    task: JsonMap
    constraints: JsonMap
    primary_paradigm: str
    answer_shape: str
    assumption: str = ""
    blocking_reason: str | None = None
    direct_reply: str = ""
    pending_interaction: JsonMap = field(default_factory=dict)
    paper_references: list[JsonMap] = field(default_factory=list)
    requested_aspects: list[str] = field(default_factory=list)
    required_evidence_types: list[str] = field(default_factory=list)
    required_capabilities: list[str] = field(default_factory=list)
    requires_corpus_observation: bool = False
    confidence: float = 0.0

    @classmethod
    def from_dict(cls, value: JsonMap) -> TurnDecision:
        return cls(
            route=str(value.get("route") or "").strip().lower(),
            effective_goal=str(value.get("effective_goal") or "").strip(),
            task=child_map(value.get("task")),
            constraints=child_map(value.get("constraints")),
            primary_paradigm=str(value.get("primary_paradigm") or "").strip(),
            answer_shape=str(value.get("answer_shape") or "unknown").strip(),
            assumption=str(value.get("assumption") or "").strip(),
            blocking_reason=(
                str(value.get("blocking_reason")).strip()
                if value.get("blocking_reason") not in {None, ""}
                else None
            ),
            direct_reply=str(value.get("direct_reply") or "").strip(),
            pending_interaction=child_map(value.get("pending_interaction")),
            paper_references=[child_map(item) for item in as_list(value.get("paper_references"))],
            requested_aspects=[str(item) for item in as_list(value.get("requested_aspects"))],
            required_evidence_types=[str(item) for item in as_list(value.get("required_evidence_types"))],
            required_capabilities=[str(item) for item in as_list(value.get("required_capabilities"))],
            requires_corpus_observation=bool(value.get("requires_corpus_observation")),
            confidence=float(value.get("confidence") or 0.0),
        )

    def to_intent_frame(self) -> IntentFrame:
        constraints = dict(self.constraints)
        if self.task:
            constraints.setdefault("task", self.task)
        if self.assumption:
            constraints.setdefault("coverage_assumption", self.assumption)
        actionability = "blocking" if self.route == "clarify" else (
            "non_blocking" if self.assumption else "none"
        )
        ambiguity = {
            "status": "needs_user_choice" if self.route == "clarify" else "unambiguous",
        }
        if self.blocking_reason:
            ambiguity["reason"] = self.blocking_reason
        return IntentFrame(
            primary_paradigm=(
                "ambiguity_resolution" if self.route == "clarify" else self.primary_paradigm
            ),
            normalized_goal=self.effective_goal,
            answer_shape=self.answer_shape,
            paper_references=self.paper_references,
            requested_aspects=self.requested_aspects,
            constraints=constraints,
            ambiguity=ambiguity,
            confidence=self.confidence,
            required_evidence_types=self.required_evidence_types,
            required_capabilities=self.required_capabilities,
            actionability=actionability,
            requires_corpus_observation=self.requires_corpus_observation,
        )

    def active_task(self) -> JsonMap:
        return {
            "goal": self.effective_goal,
            "task": self.task,
            "constraints": self.constraints,
            "paradigm": self.primary_paradigm,
            "answer_shape": self.answer_shape,
            "assumption": self.assumption,
        }


@dataclass(frozen=True)
class StageSpec:
    name: str
    instruction: str
    strategy: str = "semantic_stage_execution"
    allowed_tools: tuple[str, ...] = ()
    requires_new_evidence: bool = False
    produces_answer: bool = False
    max_model_turns: int = 4
    max_tool_calls: int = 6

    def to_dict(self) -> JsonMap:
        value = asdict(self)
        value["allowed_tools"] = list(self.allowed_tools)
        return value


@dataclass(frozen=True)
class StageResult:
    status: str
    decision_summary: str
    selected_paper_ids: list[str] = field(default_factory=list)
    accepted_evidence_ids: list[str] = field(default_factory=list)
    rejected_evidence_ids: list[str] = field(default_factory=list)
    claims: list[JsonMap] = field(default_factory=list)
    state_values: JsonMap = field(default_factory=dict)
    missing_obligations: list[JsonMap] = field(default_factory=list)
    answer: JsonMap = field(default_factory=dict)
    memory_update: JsonMap = field(default_factory=dict)
    diagnostics: JsonMap = field(default_factory=dict)

    @classmethod
    def from_dict(cls, value: JsonMap) -> StageResult:
        return cls(
            status=_normalize_stage_status(value.get("status")),
            decision_summary=str(value.get("decision_summary") or ""),
            selected_paper_ids=[str(item) for item in as_list(value.get("selected_paper_ids"))],
            accepted_evidence_ids=[str(item) for item in as_list(value.get("accepted_evidence_ids"))],
            rejected_evidence_ids=[str(item) for item in as_list(value.get("rejected_evidence_ids"))],
            claims=[child_map(item) for item in as_list(value.get("claims"))],
            state_values=child_map(value.get("state_values")),
            missing_obligations=[child_map(item) for item in as_list(value.get("missing_obligations"))],
            answer=child_map(value.get("answer")),
            memory_update=child_map(value.get("memory_update")),
            diagnostics=child_map(value.get("diagnostics")),
        )


@dataclass(frozen=True)
class StageTrace:
    stage_name: str
    semantic_goal: str
    strategy: str
    completion_contract: str
    allowed_tools: list[str]
    max_model_turns: int
    max_tool_calls: int
    status: str
    decision_summary: str
    tool_calls: list[JsonMap]
    accepted_evidence_ids: list[str]
    claim_ids: list[str]
    missing_obligations: list[JsonMap]

    def to_dict(self) -> JsonMap:
        return asdict(self)


@dataclass
class ResearchState:
    turn_id: str
    authorized_paper_ids: list[str] = field(default_factory=list)
    selected_paper_ids: list[str] = field(default_factory=list)
    paper_candidates_by_id: dict[str, JsonMap] = field(default_factory=dict)
    evidence_items_by_id: dict[str, JsonMap] = field(default_factory=dict)
    rejected_evidence: list[JsonMap] = field(default_factory=list)
    missing_obligations: list[JsonMap] = field(default_factory=list)
    claims_by_id: dict[str, JsonMap] = field(default_factory=dict)
    state_values: JsonMap = field(default_factory=dict)
    stage_trace: list[StageTrace] = field(default_factory=list)
    answer_draft: JsonMap = field(default_factory=dict)
    memory_update: JsonMap = field(default_factory=dict)

    @classmethod
    def new(cls, turn_id: str) -> ResearchState:
        return cls(turn_id=turn_id)

    def to_prompt_dict(self) -> JsonMap:
        evidence_items = list(self.evidence_items_by_id.values())
        evidence_items.sort(key=lambda item: item.get("element_type") == "metadata")
        return {
            "authorized_paper_ids": self.authorized_paper_ids,
            "selected_paper_ids": self.selected_paper_ids,
            "paper_candidates": [
                _paper_candidate_card(item)
                for item in self.paper_candidates_by_id.values()
            ],
            "evidence": [_evidence_card(item) for item in evidence_items[:MAX_PROMPT_EVIDENCE_ITEMS]],
            "claims": list(self.claims_by_id.values()),
            "state_values": self.state_values,
            "missing_obligations": self.missing_obligations,
        }


def normalize_claim(raw: JsonMap, known_evidence_ids: set[str], index: int) -> JsonMap:
    supporting = [
        str(item) for item in as_list(raw.get("supporting_evidence_ids"))
        if str(item) in known_evidence_ids
    ]
    refuting = [
        str(item) for item in as_list(raw.get("refuting_evidence_ids"))
        if str(item) in known_evidence_ids
    ]
    status = str(raw.get("status") or ("supported" if supporting else "underdetermined")).lower()
    status = {
        "support": "supported",
        "verified": "supported",
        "true": "supported",
        "unsupported": "underdetermined",
        "unknown": "underdetermined",
    }.get(status, status)
    if status == "supported" and not supporting:
        status = "underdetermined"
    return {
        "claim_id": str(raw.get("claim_id") or f"claim_{index + 1}"),
        "text": str(raw.get("text") or ""),
        "claim_type": str(raw.get("claim_type") or "semantic_stage_claim"),
        "supporting_evidence_ids": supporting,
        "refuting_evidence_ids": refuting,
        "status": status,
        "confidence": float(raw.get("confidence") or (1.0 if status == "supported" and supporting else 0.0)),
        "depends_on_claim_ids": [str(item) for item in as_list(raw.get("depends_on_claim_ids"))],
    }


def unique_strings(values: Any) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for value in values:
        text = str(value)
        if text and text not in seen:
            seen.add(text)
            result.append(text)
    return result


def _evidence_card(item: JsonMap) -> JsonMap:
    return {
        "evidence_id": item.get("evidence_id"),
        "paper_id": item.get("paper_id"),
        "title": item.get("title"),
        "section": item.get("section"),
        "page": item.get("page"),
        "element_type": item.get("element_type"),
        "span_text": str(item.get("span_text") or "")[:700],
    }


def _paper_candidate_card(item: JsonMap) -> JsonMap:
    return {
        "candidate_ref": item.get("paper_id"),
        "paper_id": item.get("paper_id"),
        "title": item.get("title"),
        "authors": as_list(item.get("authors")),
        "year": item.get("year"),
        "venue": item.get("venue"),
        "preview": str(item.get("span_text") or "")[:500],
        "citeable": False,
    }


def _normalize_stage_status(value: Any) -> str:
    raw = str(value or "completed").lower()
    return {
        "ok": "completed",
        "complete": "completed",
        "completed_precise": "completed",
        "needs_user_choice": "needs_clarification",
        "clarify": "needs_clarification",
        "incomplete": "incomplete_precise",
        "abstained": "incomplete_precise",
    }.get(raw, raw)
