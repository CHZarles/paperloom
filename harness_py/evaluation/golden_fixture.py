from __future__ import annotations

from datetime import UTC, datetime

from .fact_assertions import FACT_FIELDS_SCHEMA_VERSION
from .golden_case import case_expect, case_question
from ..core.models import RUN_TRACE_SCHEMA_VERSION, GoldenDataset, JsonMap, as_list, child_map, stable_id


class GoldenFixtureHarness:
    harness_id = "golden_fixture_v4"

    def run_case(self, dataset: GoldenDataset, case: JsonMap) -> JsonMap:
        expectation = case_expect(case)
        claims = [
            dataset.claims_by_id[str(claim_id)]
            for claim_id in as_list(expectation.get("required_claims"))
        ]
        evidence_by_location: dict[tuple[str, str], JsonMap] = {}
        claim_evidence: dict[str, list[JsonMap]] = {}
        for claim in claims:
            claim_id = str(claim["claim_id"])
            items: list[JsonMap] = []
            for raw_requirement in as_list(claim.get("required_evidence")):
                requirement = child_map(raw_requirement)
                paper_id = str(requirement["paper_id"])
                location_ref = str(as_list(requirement.get("accepted_locations"))[0])
                key = (paper_id, location_ref)
                item = evidence_by_location.setdefault(
                    key,
                    _fixture_evidence(dataset, claim, paper_id, location_ref),
                )
                if item not in items:
                    items.append(item)
            claim_evidence[claim_id] = items
        evidence = list(evidence_by_location.values())
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
        citation_numbers = {
            evidence_id: index
            for index, evidence_id in enumerate(cited, start=1)
        }
        markdown = "\n\n".join(
            str(claim["statement"]) + " " + " ".join(
                f"[{citation_numbers[item['evidence_id']]}]"
                for item in claim_evidence[str(claim["claim_id"])]
                if item["evidence_id"] in citation_numbers
            )
            for claim in claims
        ).strip()
        case_id = str(case["id"])
        answer = {
            "answer_id": stable_id("answer", case_id),
            "question_id": case_id,
            "status": status,
            "outcome": outcome,
            "outcome_reason": str(expectation.get("reason") or ""),
            "answer_type": "golden_fixture",
            "summary": case_question(case),
            "markdown": markdown or case_question(case),
            "fields_schema": FACT_FIELDS_SCHEMA_VERSION,
            "fields": dict(child_map(expectation.get("facts"))),
            "cited_evidence_ids": cited,
        }
        return _fixture_run(case_id, status, evidence, answer)


def _fixture_evidence(
    dataset: GoldenDataset,
    claim: JsonMap,
    paper_id: str,
    location_ref: str,
) -> JsonMap:
    identity = child_map(dataset.paper_records_by_id[paper_id].get("identity"))
    element_type = location_ref.split("_ref_", 1)[0]
    return {
        "evidence_id": stable_id("fixture_evidence", f"{paper_id}_{location_ref}"),
        "paper_id": paper_id,
        "title": identity.get("title") or paper_id,
        "paper_version": identity.get("version_label") or identity.get("year") or "unknown",
        "section": "golden claim fixture",
        "page": "unknown",
        "location": location_ref,
        "location_ref": location_ref,
        "element_type": element_type,
        "span_text": str(claim.get("statement") or ""),
        "retrieval_strategy": "golden_claim_fixture",
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
