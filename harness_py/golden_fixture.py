from __future__ import annotations

from datetime import UTC, datetime

from .golden_case import case_expect, case_question
from .models import RUN_TRACE_SCHEMA_VERSION, GoldenDataset, JsonMap, as_list, child_map, stable_id


class GoldenFixtureHarness:
    harness_id = "golden_fixture_v2"

    def run_case(self, dataset: GoldenDataset, case: JsonMap) -> JsonMap:
        expectation = case_expect(case)
        evidence = [
            _fixture_evidence(dataset, str(anchor_id))
            for anchor_id in as_list(child_map(expectation.get("evidence")).get("required"))
        ]
        evidence_by_anchor = {
            str(item["matched_anchor_id"]): str(item["evidence_id"])
            for item in evidence
        }
        claims = _fixture_claims(expectation, evidence_by_anchor)
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
            "sections": [],
            "markdown": case_question(case),
            "fields": dict(child_map(expectation.get("facts"))),
            "cited_claim_ids": [str(claim["claim_id"]) for claim in claims],
            "cited_evidence_ids": cited,
            "reasoning_artifact_ids": [stable_id("reasoning", case_id)],
            "verification_id": stable_id("verification", case_id),
        }
        return _fixture_run(case_id, status, evidence, claims, answer)


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


def _fixture_claims(expectation: JsonMap, evidence_by_anchor: dict[str, str]) -> list[JsonMap]:
    claims: list[JsonMap] = []
    authored = [child_map(item) for item in as_list(expectation.get("claims"))]
    for index, claim in enumerate(authored, start=1):
        support = [
            evidence_by_anchor[str(anchor_id)]
            for anchor_id in as_list(claim.get("evidence"))
            if str(anchor_id) in evidence_by_anchor
        ]
        claims.append(_fixture_claim(index, str(claim.get("text") or ""), support))
    if not claims:
        all_evidence = list(evidence_by_anchor.values())
        for index, (key, value) in enumerate(child_map(expectation.get("facts")).items(), start=1):
            claims.append(_fixture_claim(index, f"{key}: {value}", all_evidence))
    return claims


def _fixture_claim(index: int, text: str, support: list[str]) -> JsonMap:
    return {
        "claim_id": f"claim_{index}",
        "text": text,
        "status": "supported" if support else "underdetermined",
        "supporting_evidence_ids": support,
        "refuting_evidence_ids": [],
    }


def _fixture_run(
    case_id: str,
    status: str,
    evidence: list[JsonMap],
    claims: list[JsonMap],
    answer: JsonMap,
) -> JsonMap:
    now = datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")
    verification_id = stable_id("verification", case_id)
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
        "intent_frame": {
            "intent_id": stable_id("intent", case_id),
            "question_id": case_id,
            "primary_paradigm": "golden_fixture",
        },
        "retrieval_plan": {
            "plan_id": stable_id("plan", case_id),
            "question_id": case_id,
            "strategy_steps": [],
        },
        "stage_trace": [],
        "evidence_ledger": {
            "ledger_id": stable_id("ledger", case_id),
            "question_id": case_id,
            "items": evidence,
            "rejected_items": [],
            "missing_evidence": [],
        },
        "claim_graph": {
            "graph_id": stable_id("claim_graph", case_id),
            "question_id": case_id,
            "claims": claims,
            "edges": [],
        },
        "reasoning_artifacts": [{"reasoning_id": stable_id("reasoning", case_id)}],
        "verification_pass": {
            "verification_id": verification_id,
            "question_id": case_id,
            "unsupported_claim_count": 0,
            "missing_required_evidence": [],
        },
        "research_answer": answer,
        "final_answer": answer,
    }
