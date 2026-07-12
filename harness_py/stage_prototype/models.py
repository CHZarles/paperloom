from __future__ import annotations

from dataclasses import asdict, dataclass, field
from enum import Enum
from typing import Any

from ..models import JsonMap, as_list, child_map


MAX_PROMPT_EVIDENCE_ITEMS = 16
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
class Obligation:
    obligation_id: str
    description: str
    kind: str = "claim"
    paper_scope: list[str] = field(default_factory=list)
    required: bool = True
    answer_field: str = ""

    @classmethod
    def from_dict(cls, value: JsonMap, index: int = 0) -> Obligation:
        return cls(
            obligation_id=str(value.get("id") or value.get("obligation_id") or f"obligation_{index + 1}"),
            description=str(value.get("description") or "").strip(),
            kind=str(value.get("kind") or "claim").strip(),
            paper_scope=unique_strings(as_list(value.get("paper_scope"))),
            required=bool(value.get("required", True)),
            answer_field=str(value.get("answer_field") or "").strip(),
        )

    def to_dict(self) -> JsonMap:
        return {
            "id": self.obligation_id,
            "description": self.description,
            "kind": self.kind,
            "paper_scope": list(self.paper_scope),
            "required": self.required,
            "answer_field": self.answer_field,
        }


@dataclass(frozen=True)
class TaskFrame:
    primary_paradigm: str
    normalized_goal: str
    answer_shape: str
    paper_references: list[JsonMap]
    requested_aspects: list[str]
    constraints: JsonMap
    ambiguity: JsonMap
    confidence: float
    obligations: list[Obligation] = field(default_factory=list)
    required_evidence_types: list[str] = field(default_factory=list)
    required_capabilities: list[str] = field(default_factory=list)
    actionability: str = "none"
    requires_corpus_observation: bool = False

    @property
    def ambiguity_status(self) -> str:
        return str(self.ambiguity.get("status") or "unambiguous")

    def to_dict(self) -> JsonMap:
        value = asdict(self)
        value["obligations"] = [item.to_dict() for item in self.obligations]
        return value

    @classmethod
    def from_dict(cls, value: JsonMap) -> TaskFrame:
        return cls(
            primary_paradigm=str(value.get("primary_paradigm") or ""),
            normalized_goal=str(value.get("normalized_goal") or ""),
            answer_shape=str(value.get("answer_shape") or "unknown"),
            paper_references=[child_map(item) for item in as_list(value.get("paper_references"))],
            requested_aspects=[str(item) for item in as_list(value.get("requested_aspects"))],
            constraints=child_map(value.get("constraints")),
            ambiguity=child_map(value.get("ambiguity")),
            confidence=float(value.get("confidence") or 0.0),
            obligations=[
                Obligation.from_dict(child_map(item), index)
                for index, item in enumerate(as_list(value.get("obligations")))
            ],
            required_evidence_types=[str(item) for item in as_list(value.get("required_evidence_types"))],
            required_capabilities=[str(item) for item in as_list(value.get("required_capabilities"))],
            actionability=str(value.get("actionability") or "none"),
            requires_corpus_observation=bool(value.get("requires_corpus_observation")),
        )


IntentFrame = TaskFrame
ParadigmFrame = TaskFrame


@dataclass(frozen=True)
class TurnDecision:
    route: str
    effective_goal: str
    task: JsonMap
    constraints: JsonMap
    primary_paradigm: str
    answer_shape: str
    obligations: list[Obligation] = field(default_factory=list)
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
            obligations=[
                Obligation.from_dict(child_map(item), index)
                for index, item in enumerate(as_list(value.get("obligations")))
            ],
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

    def to_task_frame(self) -> TaskFrame:
        constraints = dict(self.constraints)
        if self.task:
            constraints.setdefault("task", self.task)
        if self.assumption:
            constraints.setdefault("coverage_assumption", self.assumption)
        actionability = "blocking" if self.route == "clarify" else (
            "non_blocking" if self.assumption else "none"
        )
        ambiguity: JsonMap = {
            "status": "needs_user_choice" if self.route == "clarify" else "unambiguous",
        }
        if self.blocking_reason:
            ambiguity["reason"] = self.blocking_reason
        return TaskFrame(
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
            obligations=self.obligations,
            required_evidence_types=self.required_evidence_types,
            required_capabilities=self.required_capabilities,
            actionability=actionability,
            requires_corpus_observation=(
                self.requires_corpus_observation
                or (self.route == "research" and bool(self.obligations))
            ),
        )

    def to_intent_frame(self) -> TaskFrame:
        return self.to_task_frame()

    def active_task(self) -> JsonMap:
        return {
            "goal": self.effective_goal,
            "task": self.task,
            "constraints": self.constraints,
            "paradigm": self.primary_paradigm,
            "answer_shape": self.answer_shape,
            "obligations": [item.to_dict() for item in self.obligations],
            "assumption": self.assumption,
        }


@dataclass(frozen=True)
class EvidenceCoverage:
    obligation_id: str
    status: str
    evidence_ids: list[str] = field(default_factory=list)
    note: str = ""

    @classmethod
    def from_dict(cls, value: JsonMap) -> EvidenceCoverage:
        status = str(value.get("status") or "missing").strip().lower()
        if status not in {"covered", "missing"}:
            status = "missing"
        return cls(
            obligation_id=str(value.get("obligation_id") or value.get("id") or "").strip(),
            status=status,
            evidence_ids=unique_strings(as_list(value.get("evidence_ids"))),
            note=str(value.get("note") or "").strip(),
        )

    def to_dict(self) -> JsonMap:
        return asdict(self)


@dataclass(frozen=True)
class Claim:
    claim_id: str
    role: str
    text: str
    obligation_ids: list[str]
    evidence_ids: list[str]
    field_values: JsonMap = field(default_factory=dict)

    @classmethod
    def from_dict(cls, value: JsonMap, index: int = 0) -> Claim:
        return cls(
            claim_id=str(value.get("claim_id") or f"claim_{index + 1}"),
            role=str(value.get("role") or "answer").strip(),
            text=str(value.get("text") or "").strip(),
            obligation_ids=unique_strings(as_list(value.get("obligation_ids"))),
            evidence_ids=unique_strings(as_list(value.get("evidence_ids"))),
            field_values=_field_values(value.get("field_values")),
        )

    def to_dict(self) -> JsonMap:
        return asdict(self)


@dataclass(frozen=True)
class ClaimVerdict:
    claim_id: str
    verdict: str
    verified_text: str
    evidence_ids: list[str]
    field_values: JsonMap = field(default_factory=dict)
    reason: str = ""

    @classmethod
    def from_dict(cls, value: JsonMap) -> ClaimVerdict:
        verdict = str(value.get("verdict") or "drop").strip().lower()
        if verdict not in {"keep", "rewrite", "drop"}:
            verdict = "drop"
        return cls(
            claim_id=str(value.get("claim_id") or "").strip(),
            verdict=verdict,
            verified_text=str(value.get("verified_text") or "").strip(),
            evidence_ids=unique_strings(as_list(value.get("evidence_ids"))),
            field_values=_field_values(value.get("field_values")),
            reason=str(value.get("reason") or "").strip(),
        )

    def to_dict(self) -> JsonMap:
        return asdict(self)


@dataclass(frozen=True)
class StageTrace:
    stage_name: str
    semantic_goal: str
    status: str
    decision_summary: str = ""
    tool_calls: list[JsonMap] = field(default_factory=list)
    obligation_ids: list[str] = field(default_factory=list)
    evidence_ids: list[str] = field(default_factory=list)
    claim_ids: list[str] = field(default_factory=list)

    def to_dict(self) -> JsonMap:
        return asdict(self)


@dataclass
class ResearchState:
    turn_id: str
    authorized_paper_ids: list[str] = field(default_factory=list)
    selected_paper_ids: list[str] = field(default_factory=list)
    paper_candidates_by_id: dict[str, JsonMap] = field(default_factory=dict)
    evidence_items_by_id: dict[str, JsonMap] = field(default_factory=dict)
    coverage_by_obligation_id: dict[str, EvidenceCoverage] = field(default_factory=dict)
    claims_by_id: dict[str, Claim] = field(default_factory=dict)
    verdicts_by_claim_id: dict[str, ClaimVerdict] = field(default_factory=dict)
    missing_obligations: list[JsonMap] = field(default_factory=list)
    retrieval_rounds: list[JsonMap] = field(default_factory=list)
    stage_trace: list[StageTrace] = field(default_factory=list)
    memory_update: JsonMap = field(default_factory=dict)

    @classmethod
    def new(cls, turn_id: str) -> ResearchState:
        return cls(turn_id=turn_id)

    def to_prompt_dict(self) -> JsonMap:
        evidence_items = list(self.evidence_items_by_id.values())
        evidence_items.sort(key=lambda item: item.get("citeable") is False)
        return {
            "authorized_paper_ids": self.authorized_paper_ids,
            "selected_paper_ids": self.selected_paper_ids,
            "paper_candidates": [
                _paper_candidate_card(item)
                for item in self.paper_candidates_by_id.values()
            ],
            "evidence": [_evidence_card(item) for item in evidence_items[:MAX_PROMPT_EVIDENCE_ITEMS]],
            "coverage": [item.to_dict() for item in self.coverage_by_obligation_id.values()],
            "claims": [item.to_dict() for item in self.claims_by_id.values()],
            "verdicts": [item.to_dict() for item in self.verdicts_by_claim_id.values()],
            "missing_obligations": self.missing_obligations,
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
        "span_text": str(item.get("span_text") or "")[:1200],
        "citeable": item.get("citeable", True),
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


def _field_values(value: Any) -> JsonMap:
    if isinstance(value, list):
        return {
            str(child_map(item).get("name") or ""): child_map(item).get("value")
            for item in value
            if str(child_map(item).get("name") or "").strip()
        }
    parsed = child_map(value)
    text = parsed.get("$text")
    if isinstance(text, str):
        try:
            import json

            decoded = json.loads(text)
        except json.JSONDecodeError:
            decoded = None
        if isinstance(decoded, dict):
            return {str(key): item for key, item in decoded.items()}
    return parsed
