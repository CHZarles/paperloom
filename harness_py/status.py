from __future__ import annotations

from enum import Enum


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
    return "" if len(set(normalized)) == 1 else "execution statuses disagree"


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
        return "" if not outcome_present or outcome is None else "technical failures cannot declare a research outcome"
    if not outcome_present or outcome is None:
        return "answer.outcome must be present for non-technical execution"
    normalized_outcome = normalize_research_outcome(outcome)
    if normalized_outcome is None:
        return "answer.outcome must be one of answered, needs_clarification, abstained, partial"
    if normalized_status == ExecutionStatus.NEEDS_CLARIFICATION.value and normalized_outcome != "needs_clarification":
        return "NEEDS_CLARIFICATION requires outcome=needs_clarification"
    if normalized_status == ExecutionStatus.COMPLETED.value and normalized_outcome == "needs_clarification":
        return "COMPLETED cannot declare outcome=needs_clarification"
    if normalized_status == ExecutionStatus.INCOMPLETE_PRECISE.value and normalized_outcome not in {"abstained", "partial"}:
        return "INCOMPLETE_PRECISE requires outcome=abstained or partial"
    return ""
