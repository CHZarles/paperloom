from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any

from .golden_case import case_expect
from .models import SCORE_REPORT_SCHEMA_VERSION, GoldenDataset, JsonMap, as_list, child_map
from .status import (
    ExecutionStatus,
    execution_status_error,
    normalize_research_outcome,
    research_outcome_error,
)


@dataclass(frozen=True)
class DimensionScore:
    status: str
    errors: list[str] = field(default_factory=list)

    def to_dict(self) -> JsonMap:
        return {"status": self.status, "errors": self.errors}


@dataclass(frozen=True)
class CaseScore:
    case_id: str
    hard_pass: bool
    dimensions: dict[str, DimensionScore]
    review: JsonMap

    def to_dict(self) -> JsonMap:
        return {
            "case_id": self.case_id,
            "hard_pass": self.hard_pass,
            "dimensions": {key: value.to_dict() for key, value in self.dimensions.items()},
            "review": self.review,
        }


class BehaviorScorer:
    def score_case(self, dataset: GoldenDataset, case: JsonMap, run: JsonMap) -> CaseScore:
        dimensions = {
            "outcome": self._score_outcome(case, run),
            "retrieval": self._score_retrieval(dataset, case, run),
            "content": self._score_content(case, run),
            "grounding": self._score_grounding(dataset, case, run),
        }
        criteria = [str(item) for item in as_list(case.get("review"))]
        criteria.extend(
            str(child_map(claim).get("text"))
            for claim in as_list(case_expect(case).get("claims"))
            if child_map(claim).get("text")
        )
        return CaseScore(
            case_id=str(case.get("id") or ""),
            hard_pass=all(score.status != "fail" for score in dimensions.values()),
            dimensions=dimensions,
            review={
                "status": "not_run" if criteria else "not_required",
                "criteria": criteria,
            },
        )

    def score_dataset(self, dataset: GoldenDataset, runs: list[JsonMap]) -> JsonMap:
        runs_by_case = {str(run.get("case_id") or run.get("question_id")): run for run in runs}
        scores = [
            self.score_case(dataset, case, runs_by_case.get(str(case.get("id")), {}))
            for case in dataset.cases
        ]
        passed = sum(1 for score in scores if score.hard_pass)
        return {
            "schema_version": SCORE_REPORT_SCHEMA_VERSION,
            "dataset_id": dataset.manifest.get("dataset_id"),
            "case_count": len(scores),
            "passed_count": passed,
            "failed_count": len(scores) - passed,
            "scores": [score.to_dict() for score in scores],
        }

    def _score_outcome(self, case: JsonMap, run: JsonMap) -> DimensionScore:
        expectation = case_expect(case)
        expected = str(expectation.get("outcome") or "")
        actual = _actual_outcome(run)
        errors = [] if expected == actual else [f"OUTCOME_MISMATCH:expected={expected}:actual={actual}"]
        return _dimension(errors)

    def _score_retrieval(self, dataset: GoldenDataset, case: JsonMap, run: JsonMap) -> DimensionScore:
        expectation = case_expect(case)
        evidence, errors = _validated_evidence(dataset, run)
        paper_ids = {str(item.get("paper_id")) for item in evidence if item.get("paper_id")}
        anchor_ids = {str(item.get("matched_anchor_id")) for item in evidence if item.get("matched_anchor_id")}
        for paper_id in as_list(child_map(expectation.get("papers")).get("required")):
            if paper_id not in paper_ids:
                errors.append(f"REQUIRED_PAPER_MISSING:{paper_id}")
        for paper_id in as_list(child_map(expectation.get("papers")).get("forbidden")):
            if paper_id in paper_ids:
                errors.append(f"FORBIDDEN_PAPER_ACCEPTED:{paper_id}")
        for anchor_id in as_list(child_map(expectation.get("evidence")).get("required")):
            if anchor_id not in anchor_ids:
                errors.append(f"REQUIRED_ANCHOR_MISSING:{anchor_id}")
        for anchor_id in as_list(child_map(expectation.get("evidence")).get("forbidden")):
            if anchor_id in anchor_ids:
                errors.append(f"FORBIDDEN_ANCHOR_ACCEPTED:{anchor_id}")
        configured = bool(child_map(expectation.get("papers")) or child_map(expectation.get("evidence")))
        return _dimension(errors, configured or bool(errors))

    def _score_content(self, case: JsonMap, run: JsonMap) -> DimensionScore:
        facts = child_map(case_expect(case).get("facts"))
        if not facts:
            return DimensionScore("not_applicable")
        actual = child_map(child_map(run.get("research_answer")).get("fields"))
        if not actual:
            return DimensionScore("not_run")
        errors: list[str] = []
        for key, expected in facts.items():
            normalized = _scalar_string(expected)
            if key in actual and _scalar_string(actual.get(key)) != normalized:
                errors.append(f"FACT_MISMATCH:{key}")
            elif key not in actual:
                errors.append(f"FACT_MISSING:{key}")
        return _dimension(errors)

    def _score_grounding(self, dataset: GoldenDataset, case: JsonMap, run: JsonMap) -> DimensionScore:
        expectation = case_expect(case)
        policy = str(expectation.get("citations") or "optional")
        evidence, errors = _validated_evidence(dataset, run)
        evidence_by_id = {
            str(item.get("evidence_id")): item
            for item in evidence
            if item.get("evidence_id")
        }
        answer = child_map(run.get("research_answer"))
        cited = {str(item) for item in as_list(answer.get("cited_evidence_ids")) if item}
        if policy == "required" and not cited:
            errors.append("CITATIONS_REQUIRED")
        if policy == "forbidden" and cited:
            errors.append("CITATIONS_FORBIDDEN")
        unknown = sorted(cited - set(evidence_by_id))
        if unknown:
            errors.append("UNKNOWN_CITED_EVIDENCE:" + ",".join(unknown))
        if policy == "required":
            for anchor_id in as_list(child_map(expectation.get("evidence")).get("required")):
                anchor_evidence = {
                    evidence_id
                    for evidence_id, item in evidence_by_id.items()
                    if item.get("matched_anchor_id") == anchor_id
                }
                if not (anchor_evidence & cited):
                    errors.append(f"REQUIRED_ANCHOR_NOT_CITED:{anchor_id}")
        configured = policy != "optional" or bool(cited)
        return _dimension(errors, configured or bool(errors))


def _dimension(errors: list[str], configured: bool = True) -> DimensionScore:
    if not configured:
        return DimensionScore("not_applicable")
    return DimensionScore("fail" if errors else "pass", errors)


def _accepted_evidence(run: JsonMap) -> list[JsonMap]:
    return [
        child_map(item)
        for item in as_list(child_map(run.get("evidence_ledger")).get("items"))
        if child_map(item).get("evidence_id")
        and child_map(item).get("citeable") is not False
        and child_map(item).get("evidence_quality") != "rejected"
        and child_map(item).get("element_type") != "paper_candidate"
    ]


def _validated_evidence(dataset: GoldenDataset, run: JsonMap) -> tuple[list[JsonMap], list[str]]:
    evidence: list[JsonMap] = []
    errors: list[str] = []
    for item in _accepted_evidence(run):
        anchor_id = str(item.get("matched_anchor_id") or "")
        if not anchor_id:
            evidence.append(item)
            continue
        anchor = dataset.anchors_by_id.get(anchor_id)
        if anchor is None:
            errors.append(f"UNKNOWN_MATCHED_ANCHOR:{anchor_id}")
            continue
        expected_paper_id = str(anchor.get("paper_id") or "")
        actual_paper_id = str(item.get("paper_id") or "")
        if actual_paper_id != expected_paper_id:
            errors.append(
                f"MATCHED_ANCHOR_PAPER_MISMATCH:{anchor_id}:"
                f"expected={expected_paper_id}:actual={actual_paper_id}"
            )
            continue
        evidence.append(item)
    return evidence, errors


def _actual_outcome(run: JsonMap) -> str:
    answer = child_map(run.get("research_answer"))
    status_values = [run.get("status"), answer.get("status")]
    if "result_status" in run:
        status_values.append(run.get("result_status"))
    if ExecutionStatus.FAILED_TECHNICAL.value in status_values:
        return "technical_failure"
    result_status = (
        {"result_status": run.get("result_status")}
        if "result_status" in run else
        {}
    )
    if execution_status_error(run.get("status"), answer.get("status"), **result_status):
        return "invalid"
    if "outcome" not in answer or answer.get("outcome") is None:
        return "missing"
    explicit = normalize_research_outcome(answer.get("outcome"))
    if explicit is None:
        return "invalid"
    if research_outcome_error(answer.get("status"), answer.get("outcome")):
        return "invalid"
    if explicit:
        return explicit
    return "invalid"


def _scalar_string(value: Any) -> str:
    if isinstance(value, float):
        value = f"{value:g}"
    text = str(value).strip().replace(chr(0x2212), "-")
    compact = re.sub(r"\s+", "", text).strip("$")
    power = re.fullmatch(r"10\^\{?([+-]?\d+)\}?", compact)
    if power:
        text = f"1e{int(power.group(1))}"
    return text.replace("e-0", "e-").replace("e+0", "e+")
