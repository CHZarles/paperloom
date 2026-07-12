from __future__ import annotations

from datetime import UTC, datetime

from .golden_case import case_expect, case_question
from ..core.models import RUN_TRACE_SCHEMA_VERSION, GoldenDataset, JsonMap, as_list, child_map, stable_id


class GoldenFixtureHarness:
    harness_id = "golden_fixture_v2"

    def run_case(self, dataset: GoldenDataset, case: JsonMap) -> JsonMap:
        expectation = case_expect(case)
        evidence = [
            _fixture_evidence(dataset, str(anchor_id))
            for anchor_id in as_list(child_map(expectation.get("evidence")).get("required"))
        ]
        outcome = str(expectation.get("outcome"))
        status = {
            "answered": "COMPLETED",
            "needs_clarification": "NEEDS_CLARIFICATION",
            "abstained": "INCOMPLETE_PRECISE",
            "partial": "INCOMPLETE_PRECISE",
        }[outcome]
        cited = []
        if expectation.get("citations", "optional") != "forbidden":
            cited = [str(item["evidence_id"]) for item in evidence]
        case_id = str(case["id"])
        answer = {
            "answer_id": stable_id("answer", case_id),
            "question_id": case_id,
            "status": status,
            "outcome": outcome,
            "outcome_reason": str(expectation.get("reason") or ""),
            "answer_type": "golden_fixture",
            "summary": case_question(case),
            "markdown": case_question(case),
            "fields": dict(child_map(expectation.get("facts"))),
            "cited_evidence_ids": cited,
        }
        return _fixture_run(case_id, status, evidence, answer)


def _fixture_evidence(dataset: GoldenDataset, anchor_id: str) -> JsonMap:
    anchor = dataset.anchors_by_id[anchor_id]
    paper_id = str(anchor["paper_id"])
    identity = child_map(dataset.paper_records_by_id[paper_id].get("identity"))
    element = child_map(anchor.get("element"))
    return {
        "evidence_id": stable_id("fixture_evidence", anchor_id),
        "matched_anchor_id": anchor_id,
        "paper_id": paper_id,
        "title": identity.get("title") or paper_id,
        "paper_version": identity.get("version_label") or identity.get("year") or "unknown",
        "section": element.get("section") or "unsectioned",
        "page": element.get("page") or "unknown",
        "location": f"golden_anchor:{anchor_id}",
        "element_type": element.get("type") or "paragraph",
        "span_text": child_map(anchor.get("selector")).get("exact_text") or "",
        "retrieval_strategy": "golden_fixture",
        "relevance_score": 1.0,
        "evidence_quality": "verified",
        "supports_claim_ids": [],
        "refutes_claim_ids": [],
    }


def _fixture_run(
    case_id: str,
    status: str,
    evidence: list[JsonMap],
    answer: JsonMap,
) -> JsonMap:
    now = datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")
    return {
        "schema_version": RUN_TRACE_SCHEMA_VERSION,
        "run_id": stable_id("run", case_id),
        "case_id": case_id,
        "question_id": case_id,
        "harness_id": GoldenFixtureHarness.harness_id,
        "started_at": now,
        "completed_at": now,
        "status": status,
        "result_status": status,
        "skills_used": [],
        "react_trace": [],
        "paper_candidates": [],
        "evidence_ledger": {
            "ledger_id": stable_id("ledger", case_id),
            "question_id": case_id,
            "items": evidence,
            "rejected_items": [],
            "missing_evidence": [],
        },
        "citation_validation": {
            "passed": True,
            "cited_evidence_ids": list(answer["cited_evidence_ids"]),
            "corpus_tools_used": False,
        },
        "research_answer": answer,
        "final_answer": answer,
        "diagnostics": {
            "finish_reason": "golden_fixture",
            "tool_call_count": 0,
        },
    }
