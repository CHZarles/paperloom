"""各层结果评估：检索指标、agent case 判定、协议轨迹回放。"""

from __future__ import annotations

import json
import time
from pathlib import Path

from ..orchestration.research_contract import (
    ActionRequested,
    AnswerContract,
    Phase,
    ProtocolFacts,
    ProtocolState,
    SubmissionIssueClass,
    SubmissionRequested,
    ValidationIssue,
    decide,
    validate_submission,
)
from ..utils.models import as_list, child_map
from .judge_model import MiniMaxJudgeModel
from .paperloom_common import _call_structured, _failure, _function_tool, _mapping, _records, _strings


def _target_funnel_metrics(rows: list[dict[str, object]]) -> dict[str, int]:
    excluded_statuses = {"FAILED_TECHNICAL", "LIMITED", "CANCELLED"}
    eligible = [row for row in rows if row.get("status") not in excluded_statuses]
    excluded = [row for row in rows if row.get("status") in excluded_statuses]
    checks = [check for row in eligible for check in _records(row.get("target_checks"))]
    return {
        "eligible_cases": len(eligible),
        "excluded_cases": len(excluded),
        "eligible_targets": len(checks),
        "excluded_targets": sum(len(_records(row.get("target_checks"))) for row in excluded),
        "returned": sum(bool(check.get("returned")) for check in checks),
        "read": sum(bool(check.get("read")) for check in checks),
        "cited": sum(bool(check.get("cited")) for check in checks),
    }


def _assess_agent_case(
    case: dict[str, object],
    run: dict[str, object],
    targets: dict[str, dict[str, object]],
    scope: set[str],
    protocol: dict[str, object] | None = None,
) -> dict[str, object]:
    trace = _records(run.get("react_trace"))
    ledger = _records(_mapping(run.get("evidence_ledger")).get("items"))
    answer = _mapping(run.get("research_answer"))
    returned_refs = {
        str(child_map(location).get("location_ref") or "")
        for item in trace if item.get("tool_name") == "search_paper_content"
        for location in as_list(_mapping(item.get("result")).get("locations"))
    }
    read_by_quote = {
        str(item.get("source_quote_ref") or item.get("evidence_id") or ""): item
        for item in ledger
        if item.get("source_quote_ref") or item.get("evidence_id")
    }
    cited = {str(value) for value in as_list(answer.get("cited_source_quote_refs")) if value}
    expected_contract = str(case.get("expected_contract") or "")
    actual_contract = str(answer.get("answer_contract") or "")
    contract_match = actual_contract == expected_contract
    provenance_applicable = actual_contract == AnswerContract.RESEARCH.value
    provenance_passed = _research_provenance_passed(run, read_by_quote) if provenance_applicable else None
    required = [targets[target_id] for target_id in _strings(case.get("required_target_ids"))]
    target_checks = [
        {
            "target_id": target.get("target_id"),
            "returned": target.get("location_ref") in returned_refs,
            "read": any(item.get("location_ref") == target.get("location_ref") for item in ledger),
            "cited": any(
                quote_ref in cited and item.get("location_ref") == target.get("location_ref")
                for quote_ref, item in read_by_quote.items()
            ),
        }
        for target in required
    ]
    expected_outcome = "abstained" if case.get("expected_outcome") == "insufficient_evidence" else case.get("expected_outcome")
    leaked_ids = sorted({
        str(item.get("paper_id") or "")
        for item in [*ledger, *_records(run.get("paper_candidates"))]
        if item.get("paper_id") and item.get("paper_id") not in scope
    })
    hard_failures: list[dict[str, object]] = []
    technical_run = run.get("status") == "FAILED_TECHNICAL"
    if technical_run:
        reason_code = str(_mapping(run.get("control")).get("reason_code") or run.get("status"))
        hard_failures.append(_failure("L3", "AGENT_TECHNICAL_FAILURE", reason_code, technical=True))
    elif run.get("status") in {"LIMITED", "CANCELLED"}:
        hard_failures.append(_failure("L3", "AGENT_TECHNICAL_FAILURE", str(run.get("status"))))
    if not technical_run and expected_contract and not contract_match:
        hard_failures.append(_failure("L3", "CONTRACT_MISMATCH", f"expected={expected_contract}, actual={actual_contract}"))
    if not technical_run and protocol is not None and not protocol.get("passed"):
        hard_failures.append(_failure("L3", "PROTOCOL_REPLAY_FAILED", str(protocol.get("failed_event_ids") or [])))
    if not technical_run and provenance_applicable and not provenance_passed:
        hard_failures.append(_failure("L3", "PROVENANCE_FAILED", str(case.get("case_id"))))
    if leaked_ids:
        hard_failures.append(_failure("L3", "SCOPE_LEAK", str(leaked_ids)))
    if cited - set(read_by_quote):
        hard_failures.append(_failure("L3", "UNRESOLVABLE_CITATION", str(sorted(cited - set(read_by_quote)))))
    if not technical_run and expected_contract == "RESEARCH" and expected_outcome == "answered" and not cited:
        hard_failures.append(_failure("L3", "MISSING_CITATION", str(case.get("case_id"))))
    if case.get("citation_policy") == "no_citation_without_evidence" and cited:
        hard_failures.append(_failure("L3", "CITATION_WITHOUT_EVIDENCE", str(sorted(cited))))
    if not technical_run and case.get("case_type") == "follow_up_ambiguity_control":
        clarification = (
            answer.get("outcome") == "needs_clarification"
            and actual_contract == AnswerContract.DIRECT.value
            and not cited
        )
        if not clarification:
            hard_failures.append(_failure("L3", "AMBIGUOUS_REFERENCE_NOT_CLARIFIED", str(case.get("case_id"))))
    return {
        "status": run.get("status"),
        "actual_outcome": answer.get("outcome"),
        "expected_outcome": expected_outcome,
        "outcome_match": answer.get("outcome") == expected_outcome,
        "expected_contract": expected_contract,
        "actual_contract": actual_contract,
        "contract_match": contract_match,
        "protocol_replay": protocol,
        "provenance_applicable": provenance_applicable,
        "provenance_passed": provenance_passed,
        "target_checks": target_checks,
        "citation_integrity": bool(_mapping(run.get("citation_validation")).get("passed")) and not (cited - set(read_by_quote)),
        "scope_isolated": not leaked_ids,
        "hard_failures": hard_failures,
    }


def _research_provenance_passed(
    run: dict[str, object],
    known_source_quotes: dict[str, dict[str, object]],
) -> bool:
    submission = next((
        item
        for item in reversed(_records(run.get("react_trace")))
        if item.get("tool_name") == "submit_research_answer"
        and _mapping(item.get("result")).get("accepted") is True
    ), None)
    if submission is None:
        return False
    draft = _mapping(submission.get("arguments"))
    return validate_submission(
        AnswerContract.RESEARCH,
        draft,
        ProtocolFacts(known_source_quotes=known_source_quotes),
    ).accepted


def _assess_protocol_trace(events_path: Path) -> dict[str, object]:
    if not events_path.is_file():
        return {
            "transition_count": 0,
            "passed_count": 0,
            "pass_rate": 0.0,
            "passed": False,
            "failed_event_ids": ["TRACE_MISSING"],
        }
    try:
        events = [
            _mapping(json.loads(line))
            for line in events_path.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]
    except (OSError, json.JSONDecodeError):
        return {
            "transition_count": 0,
            "passed_count": 0,
            "pass_rate": 0.0,
            "passed": False,
            "failed_event_ids": ["TRACE_INVALID"],
        }
    transitions = [event for event in events if event.get("kind") == "protocol.transition"]
    failed: list[str] = []
    for event in transitions:
        try:
            payload = _mapping(event.get("payload"))
            decision = decide(
                _protocol_state(_mapping(payload.get("before"))),
                _protocol_event(_mapping(payload.get("event"))),
                _protocol_facts(_mapping(payload.get("facts"))),
            )
            recorded = _mapping(payload.get("decision"))
            replayed = {
                "accepted": bool(decision.model_result.get("accepted")),
                "issue_codes": _strings(decision.model_result.get("issue_codes")),
            }
            if (
                replayed != {
                    "accepted": bool(recorded.get("accepted")),
                    "issue_codes": _strings(recorded.get("issue_codes")),
                }
                or decision.next_state.to_dict() != _mapping(payload.get("after"))
            ):
                failed.append(str(event.get("event_id") or event.get("sequence") or "unknown"))
        except (KeyError, TypeError, ValueError):
            failed.append(str(event.get("event_id") or event.get("sequence") or "unknown"))
    count = len(transitions)
    passed_count = count - len(failed)
    return {
        "transition_count": count,
        "passed_count": passed_count,
        "pass_rate": passed_count / count if count else 0.0,
        "passed": count > 0 and not failed,
        "failed_event_ids": failed,
    }


def _protocol_state(value: dict[str, object]) -> ProtocolState:
    contract = str(value.get("contract") or "")
    return ProtocolState(
        phase=Phase(str(value["phase"])),
        contract=AnswerContract(contract) if contract else None,
        submission_attempt=int(value.get("submission_attempt") or 0),
        validation_issues=tuple(_strings(value.get("validation_issues"))),
    )


def _protocol_event(value: dict[str, object]) -> ActionRequested | SubmissionRequested:
    if value.get("kind") == "ACTION_REQUESTED":
        return ActionRequested(str(value["tool_name"]))
    issue_class = str(value.get("issue_class") or "")
    resolved_class = SubmissionIssueClass(issue_class) if issue_class else None
    issues = tuple(
        ValidationIssue(
            code=str(item["code"]),
            issue_class=resolved_class or SubmissionIssueClass.FORMAT_ISSUE,
            block_ids=tuple(_strings(item.get("block_ids"))),
            unknown_source_quote_refs=tuple(_strings(item.get("unknown_source_quote_refs"))),
        )
        for item in _records(value.get("issues"))
    )
    return SubmissionRequested(
        contract=AnswerContract(str(value["contract"])),
        payload={},
        accepted=bool(value.get("accepted")),
        issue_class=resolved_class,
        issue_codes=tuple(_strings(value.get("issue_codes"))),
        issues=issues,
    )


def _protocol_facts(value: dict[str, object]) -> ProtocolFacts:
    return ProtocolFacts(
        known_source_quotes={ref: {} for ref in _strings(value.get("known_source_quote_refs"))},
        catalog_results={ref: {} for ref in _strings(value.get("catalog_result_refs"))},
        sibling_tool_names=tuple(_strings(value.get("sibling_tool_names"))),
    )


def _controlled_protocol_metrics(rows: list[dict[str, object]]) -> dict[str, object]:
    contract_rows = [row for row in rows if row.get("expected_contract")]
    transition_count = sum(
        int(_mapping(row.get("protocol_replay")).get("transition_count") or 0)
        for row in rows
    )
    replayed_count = sum(
        int(_mapping(row.get("protocol_replay")).get("passed_count") or 0)
        for row in rows
    )
    research_rows = [row for row in rows if row.get("provenance_applicable")]
    return {
        "contract_case_count": len(contract_rows),
        "contract_accuracy": (
            sum(bool(row.get("contract_match")) for row in contract_rows) / len(contract_rows)
            if contract_rows else 0.0
        ),
        "protocol_transition_count": transition_count,
        "protocol_replay_pass_rate": replayed_count / transition_count if transition_count else 0.0,
        "completed_research_run_count": len(research_rows),
        "provenance_pass_rate": (
            sum(row.get("provenance_passed") is True for row in research_rows) / len(research_rows)
            if research_rows else 0.0
        ),
    }


def _judge_agent_case(
    judge: MiniMaxJudgeModel,
    case: dict[str, object],
    run: dict[str, object],
) -> dict[str, object]:
    answer = _mapping(run.get("research_answer"))
    ledger = _records(_mapping(run.get("evidence_ledger")).get("items"))
    tool = _function_tool("submit_agent_judgment", {
        "answer_quality": {"type": "string", "enum": ["PASS", "FAIL", "UNCERTAIN"]},
        "grounding": {"type": "string", "enum": ["PASS", "FAIL", "UNCERTAIN"]},
        "reason": {"type": "string"},
    })
    recommendation = case.get("case_type") == "research_recommendation"
    instruction = (
        "Judge an open-ended paper recommendation only from the user question, actual answer, and cited evidence. "
        "PASS answer_quality when the recommended papers are directly relevant to the requested topic and each "
        "recommendation gives a substantive reason. PASS grounding only when the material recommendation reasons "
        "are supported by the cited evidence. Do not require one predetermined paper or fact."
        if recommendation else
        "Judge one benchmark answer only from the supplied expected answer, trusted answer spans, and actual cited "
        "evidence. PASS answer_quality when the requested facts are conveyed without contradiction; wording need "
        "not match. PASS grounding only when material claims are supported by cited evidence."
    )
    messages = [
        {
            "role": "system",
            "content": instruction,
        },
        {"role": "user", "content": json.dumps({
            "question": case.get("question"),
            "expected_outcome": case.get("expected_outcome"),
            "expected_answer": case.get("expected_answer"),
            "trusted_answer_spans": case.get("answer_spans"),
            "actual_outcome": answer.get("outcome"),
            "actual_answer": answer.get("markdown"),
            "cited_evidence": [
                {"paper_id": item.get("paper_id"), "location_ref": item.get("location_ref"), "text": item.get("span_text")}
                for item in ledger
                if (item.get("source_quote_ref") or item.get("evidence_id")) in as_list(answer.get("cited_source_quote_refs"))
            ],
        }, ensure_ascii=False)},
    ]
    started = time.monotonic()
    result = _call_structured(judge, messages, tool, 900)
    return {"status": "completed", "latency_ms": round((time.monotonic() - started) * 1000), **result}


def _retrieval_metrics(rows: list[dict[str, object]], cutoffs: tuple[int, ...]) -> dict[str, object]:
    count = len(rows)
    return {
        "count": count,
        **{f"recall_at_{cutoff}": sum(isinstance(row.get("rank"), int) and int(row["rank"]) <= cutoff for row in rows) / count if count else 0.0 for cutoff in cutoffs},
        "mrr": sum(1 / int(row["rank"]) for row in rows if isinstance(row.get("rank"), int)) / count if count else 0.0,
    }


def _rank(values: list[str], expected: str) -> int | None:
    try:
        return values.index(expected) + 1
    except ValueError:
        return None


def _source_quotes_cover(quotes: object, expected: object) -> bool:
    try:
        expected_value = json.loads(str(expected or ""))
        actual_values = [
            json.loads(str(child_map(quote).get("source_span_json") or ""))
            for quote in as_list(quotes)
        ]
    except json.JSONDecodeError:
        return False
    if not isinstance(expected_value, dict) or not actual_values:
        return False
    spans = [span for value in actual_values if isinstance(value, dict) for span in as_list(value.get("spans"))]
    return (
        all(value.get("locationType") == expected_value.get("locationType") for value in actual_values)
        and all(value.get("sourceObjectId") == expected_value.get("sourceObjectId") for value in actual_values)
        and all(span in spans for span in as_list(expected_value.get("spans")))
    )
