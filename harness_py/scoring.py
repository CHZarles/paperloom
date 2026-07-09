from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from .contracts import ArtifactContractValidator
from .models import SCORE_REPORT_SCHEMA_VERSION, GoldenDataset, JsonMap, as_list, child_map


STATUS_EQUIVALENTS = {
    "answered": "COMPLETED",
    "completed": "COMPLETED",
    "ok_precise": "COMPLETED",
    "resolved": "COMPLETED",
    "needs_clarification": "NEEDS_CLARIFICATION",
    "abstain_insufficient_evidence": "INCOMPLETE_PRECISE",
    "incomplete_precise": "INCOMPLETE_PRECISE",
    "contradiction_report": "COMPLETED",
    "partial_with_explicit_limits": "INCOMPLETE_PRECISE",
}


@dataclass(frozen=True)
class CaseScore:
    case_id: str
    passed: bool
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    layer_scores: JsonMap = field(default_factory=dict)

    def to_dict(self) -> JsonMap:
        return {
            "case_id": self.case_id,
            "passed": self.passed,
            "errors": self.errors,
            "warnings": self.warnings,
            "layer_scores": self.layer_scores,
        }


class TraceScorer:
    """Generic scorer over GoldenCase contracts and emitted trace artifacts."""

    def __init__(self, artifact_contracts: JsonMap | None = None):
        self.contract_validator = ArtifactContractValidator(artifact_contracts) if artifact_contracts else None

    def score_case(self, dataset: GoldenDataset, case: JsonMap, run: JsonMap) -> CaseScore:
        errors: list[str] = []
        warnings: list[str] = []
        case_id = str(case.get("id", ""))
        if run.get("case_id") not in {None, case_id}:
            errors.append(f"CASE_ID_MISMATCH expected={case_id} actual={run.get('case_id')}")
        if run.get("question_id") != case_id:
            errors.append(f"QUESTION_ID_MISMATCH expected={case_id} actual={run.get('question_id')}")
        if self.contract_validator:
            validation = self.contract_validator.validate_run(run)
            errors.extend(f"CONTRACT:{error}" for error in validation.errors)
            warnings.extend(f"CONTRACT:{warning}" for warning in validation.warnings)

        self._score_expected_result(case, run, errors)
        self._score_required_papers(case, run, errors)
        self._score_evidence(case, run, errors)
        self._score_claims(case, run, errors)
        self._score_answer(case, run, errors)
        self._score_trace_obligations(case, run, errors, warnings)
        self._score_verification(case, run, errors)
        return CaseScore(
            case_id=case_id,
            passed=not errors,
            errors=errors,
            warnings=warnings,
            layer_scores=self._layer_scores(case, run, errors, warnings),
        )

    def score_dataset(self, dataset: GoldenDataset, runs: list[JsonMap]) -> JsonMap:
        runs_by_case = {str(run.get("case_id") or run.get("question_id")): run for run in runs}
        scores = [self.score_case(dataset, case, runs_by_case.get(str(case.get("id")), {})) for case in dataset.cases]
        passed = sum(1 for score in scores if score.passed)
        return {
            "schema_version": SCORE_REPORT_SCHEMA_VERSION,
            "dataset_id": dataset.manifest.get("dataset_id"),
            "case_count": len(scores),
            "passed_count": passed,
            "failed_count": len(scores) - passed,
            "scores": [score.to_dict() for score in scores],
        }

    def _score_expected_result(self, case: JsonMap, run: JsonMap, errors: list[str]) -> None:
        expected_kind = child_map(case.get("expected_result")).get("kind")
        if not expected_kind:
            return
        expected_status = _normalize_status(expected_kind)
        actual_status = _normalize_status(run.get("status") or run.get("result_status"))
        if expected_status != actual_status:
            errors.append(f"STATUS_MISMATCH expected={expected_status} actual={actual_status}")
        expected_answer_type = child_map(case.get("expected_result")).get("answer_type")
        actual_answer_type = child_map(run.get("research_answer")).get("answer_type") or child_map(run.get("intent_frame")).get("answer_type")
        if expected_answer_type and expected_answer_type != actual_answer_type:
            errors.append(f"ANSWER_TYPE_MISMATCH expected={expected_answer_type} actual={actual_answer_type}")

    def _score_required_papers(self, case: JsonMap, run: JsonMap, errors: list[str]) -> None:
        actual_papers = {
            item.get("paper_id")
            for item in as_list(child_map(run.get("evidence_ledger")).get("items"))
            if item.get("paper_id")
        }
        for paper_id in as_list(child_map(case.get("corpus_scope")).get("required_paper_ids")):
            if paper_id not in actual_papers:
                errors.append(f"REQUIRED_PAPER_MISSING:{paper_id}")

    def _score_evidence(self, case: JsonMap, run: JsonMap, errors: list[str]) -> None:
        ledger = child_map(run.get("evidence_ledger"))
        actual_anchors = {
            item.get("matched_anchor_id")
            for item in as_list(ledger.get("items"))
            if item.get("matched_anchor_id")
        }
        for anchor_id in as_list(child_map(case.get("gold_evidence")).get("required_anchor_ids")):
            if anchor_id not in actual_anchors:
                errors.append(f"REQUIRED_ANCHOR_MISSING:{anchor_id}")
        for anchor_id in as_list(child_map(case.get("gold_evidence")).get("forbidden_anchor_ids")):
            if anchor_id in actual_anchors:
                errors.append(f"FORBIDDEN_ANCHOR_ACCEPTED:{anchor_id}")

    def _score_claims(self, case: JsonMap, run: JsonMap, errors: list[str]) -> None:
        claims_by_id = {
            claim.get("claim_id"): claim
            for claim in as_list(child_map(run.get("claim_graph")).get("claims"))
            if claim.get("claim_id")
        }
        evidence_by_anchor = {
            item.get("matched_anchor_id"): item.get("evidence_id")
            for item in as_list(child_map(run.get("evidence_ledger")).get("items"))
            if item.get("matched_anchor_id")
        }
        for raw_claim in as_list(case.get("gold_claims")):
            expected = child_map(raw_claim)
            claim_id = expected.get("claim_id")
            actual = child_map(claims_by_id.get(claim_id))
            if expected.get("required") and not actual:
                errors.append(f"REQUIRED_CLAIM_MISSING:{claim_id}")
                continue
            if not actual:
                continue
            if expected.get("expected_status") and actual.get("status") != expected.get("expected_status"):
                errors.append(f"CLAIM_STATUS_MISMATCH:{claim_id}")
            for anchor_id in as_list(expected.get("support_anchor_ids")):
                evidence_id = evidence_by_anchor.get(anchor_id)
                if evidence_id and evidence_id not in as_list(actual.get("supporting_evidence_ids")):
                    errors.append(f"CLAIM_SUPPORT_LINK_MISSING:{claim_id}:{anchor_id}")
            if actual.get("status") == "supported" and not actual.get("supporting_evidence_ids"):
                errors.append(f"SUPPORTED_CLAIM_WITHOUT_EVIDENCE:{claim_id}")

    def _score_answer(self, case: JsonMap, run: JsonMap, errors: list[str]) -> None:
        answer = child_map(run.get("research_answer"))
        cited_claim_ids = set(as_list(answer.get("cited_claim_ids")))
        cited_evidence_ids = set(as_list(answer.get("cited_evidence_ids")))
        claim_ids = {claim.get("claim_id") for claim in as_list(child_map(run.get("claim_graph")).get("claims"))}
        evidence_ids = {item.get("evidence_id") for item in as_list(child_map(run.get("evidence_ledger")).get("items"))}
        if not cited_claim_ids.issubset(claim_ids):
            errors.append("ANSWER_CITES_UNKNOWN_CLAIM")
        if not cited_evidence_ids.issubset(evidence_ids):
            errors.append("ANSWER_CITES_UNKNOWN_EVIDENCE")
        required_fields = child_map(child_map(case.get("answer_contract")).get("required_fields"))
        actual_fields = child_map(answer.get("fields"))
        for key, expected_value in required_fields.items():
            if str(actual_fields.get(key)) != str(expected_value):
                errors.append(f"ANSWER_FIELD_MISMATCH:{key}")

    def _score_trace_obligations(self, case: JsonMap, run: JsonMap, errors: list[str], warnings: list[str]) -> None:
        verification = child_map(run.get("verification_pass"))
        satisfied = set(as_list(verification.get("satisfied_trace_obligation_ids")))
        failed = set(as_list(verification.get("failed_trace_obligation_ids")))
        plan_strategies = {
            child_map(step).get("strategy")
            for step in as_list(child_map(run.get("retrieval_plan")).get("strategy_steps"))
            if child_map(step).get("strategy")
        }
        anchors = {
            item.get("matched_anchor_id")
            for item in as_list(child_map(run.get("evidence_ledger")).get("items"))
            if item.get("matched_anchor_id")
        }
        for raw_obligation in as_list(child_map(case.get("required_trace")).get("obligations")):
            obligation = child_map(raw_obligation)
            obligation_id = obligation.get("id")
            if not obligation_id:
                continue
            if obligation_id in failed:
                errors.append(f"TRACE_OBLIGATION_FAILED:{obligation_id}")
            for anchor_id in as_list(obligation.get("must_include_anchor_ids")):
                if anchor_id not in anchors:
                    errors.append(f"TRACE_OBLIGATION_ANCHOR_MISSING:{obligation_id}:{anchor_id}")
            for strategy in as_list(obligation.get("must_include_strategy")):
                if strategy not in plan_strategies:
                    errors.append(f"TRACE_OBLIGATION_STRATEGY_MISSING:{obligation_id}:{strategy}")
            if obligation.get("must_include"):
                warnings.append(f"TRACE_OBLIGATION_TEXT_REVIEW_ONLY:{obligation_id}")
            if obligation_id not in satisfied:
                errors.append(f"TRACE_OBLIGATION_NOT_SATISFIED:{obligation_id}")

    def _score_verification(self, case: JsonMap, run: JsonMap, errors: list[str]) -> None:
        verification = child_map(run.get("verification_pass"))
        expected_kind = child_map(case.get("expected_result")).get("kind")
        if expected_kind == "answered" and verification.get("unsupported_claim_count", 0) > 0:
            errors.append("UNSUPPORTED_CLAIMS_PRESENT")
        if expected_kind == "answered" and verification.get("missing_required_evidence"):
            errors.append("ANSWERED_WITH_MISSING_EVIDENCE")
        if expected_kind == "abstain_insufficient_evidence" and verification.get("abstention_required") is not True:
            errors.append("ABSTENTION_NOT_RECORDED")
        if expected_kind == "needs_clarification":
            ambiguity = child_map(verification.get("ambiguity_resolution"))
            if ambiguity.get("requires_user_choice") is not True:
                errors.append("CLARIFICATION_NOT_RECORDED")

    def _layer_scores(self, case: JsonMap, run: JsonMap, errors: list[str], warnings: list[str]) -> JsonMap:
        required_anchor_count = len(as_list(child_map(case.get("gold_evidence")).get("required_anchor_ids")))
        actual_anchor_count = len([
            item for item in as_list(child_map(run.get("evidence_ledger")).get("items"))
            if item.get("matched_anchor_id")
        ])
        required_claim_count = len([claim for claim in as_list(case.get("gold_claims")) if child_map(claim).get("required")])
        actual_claim_count = len(as_list(child_map(run.get("claim_graph")).get("claims")))
        return {
            "retrieval": {
                "required_anchor_count": required_anchor_count,
                "accepted_anchor_count": actual_anchor_count,
            },
            "claim_graph": {
                "required_claim_count": required_claim_count,
                "actual_claim_count": actual_claim_count,
            },
            "verification": {
                "error_count": len(errors),
                "warning_count": len(warnings),
            },
        }


def _normalize_status(value: Any) -> str:
    if value is None:
        return ""
    raw = str(value).lower()
    return STATUS_EQUIVALENTS.get(raw, str(value).upper())


class StructuralTraceScorer:
    """Scorer for real agent runs where model claim ids are not gold claim ids."""

    def score_case(self, dataset: GoldenDataset, case: JsonMap, run: JsonMap) -> CaseScore:
        errors: list[str] = []
        warnings: list[str] = []
        case_id = str(case.get("id", ""))
        expected_status = _normalize_status(child_map(case.get("expected_result")).get("kind"))
        actual_status = _normalize_status(run.get("status") or run.get("result_status"))
        if expected_status and expected_status != actual_status:
            errors.append(f"STATUS_MISMATCH expected={expected_status} actual={actual_status}")
        expected_answer_type = child_map(case.get("expected_result")).get("answer_type")
        actual_answer_type = _normalize_answer_type(
            child_map(run.get("research_answer")).get("answer_type")
            or child_map(run.get("intent_frame")).get("answer_type")
        )
        if expected_answer_type and actual_answer_type and expected_answer_type != actual_answer_type:
            errors.append(f"ANSWER_TYPE_MISMATCH expected={expected_answer_type} actual={actual_answer_type}")
        self._score_required_papers(case, run, errors)
        self._score_required_anchors(case, run, errors)
        self._score_answer_fields(case, run, errors)
        self._score_supported_claims(run, errors)
        self._score_minimum_claim_coverage(case, run, errors)
        return CaseScore(
            case_id=case_id,
            passed=not errors,
            errors=errors,
            warnings=warnings,
            layer_scores=TraceScorer()._layer_scores(case, run, errors, warnings),
        )

    def score_dataset(self, dataset: GoldenDataset, runs: list[JsonMap]) -> JsonMap:
        runs_by_case = {str(run.get("case_id") or run.get("question_id")): run for run in runs}
        scores = [self.score_case(dataset, case, runs_by_case.get(str(case.get("id")), {})) for case in dataset.cases]
        passed = sum(1 for score in scores if score.passed)
        return {
            "schema_version": SCORE_REPORT_SCHEMA_VERSION,
            "dataset_id": dataset.manifest.get("dataset_id"),
            "case_count": len(scores),
            "passed_count": passed,
            "failed_count": len(scores) - passed,
            "scores": [score.to_dict() for score in scores],
            "scoring_mode": "structural_agent",
        }

    def _score_required_papers(self, case: JsonMap, run: JsonMap, errors: list[str]) -> None:
        actual_papers = {
            item.get("paper_id")
            for item in as_list(child_map(run.get("evidence_ledger")).get("items"))
            if item.get("paper_id")
        }
        for paper_id in as_list(child_map(case.get("corpus_scope")).get("required_paper_ids")):
            if paper_id not in actual_papers:
                errors.append(f"REQUIRED_PAPER_MISSING:{paper_id}")

    def _score_required_anchors(self, case: JsonMap, run: JsonMap, errors: list[str]) -> None:
        actual_anchors = {
            item.get("matched_anchor_id")
            for item in as_list(child_map(run.get("evidence_ledger")).get("items"))
            if item.get("matched_anchor_id")
        }
        for anchor_id in as_list(child_map(case.get("gold_evidence")).get("required_anchor_ids")):
            if anchor_id not in actual_anchors:
                errors.append(f"REQUIRED_ANCHOR_MISSING:{anchor_id}")
        for anchor_id in as_list(child_map(case.get("gold_evidence")).get("forbidden_anchor_ids")):
            if anchor_id in actual_anchors:
                errors.append(f"FORBIDDEN_ANCHOR_ACCEPTED:{anchor_id}")

    def _score_answer_fields(self, case: JsonMap, run: JsonMap, errors: list[str]) -> None:
        answer = child_map(run.get("research_answer"))
        fields = child_map(answer.get("fields"))
        required_fields = child_map(child_map(case.get("answer_contract")).get("required_fields"))
        for key, expected_value in required_fields.items():
            if _scalar_string(fields.get(key)) != _scalar_string(expected_value):
                errors.append(f"ANSWER_FIELD_MISMATCH:{key}")
        present_values = {_scalar_string(value) for value in fields.values()}
        for claim in as_list(case.get("gold_claims")):
            exact_value = child_map(claim).get("exact_value")
            if exact_value is not None and _scalar_string(exact_value) not in present_values:
                errors.append(f"CLAIM_EXACT_VALUE_MISSING:{child_map(claim).get('claim_id')}:{exact_value}")

    def _score_supported_claims(self, run: JsonMap, errors: list[str]) -> None:
        evidence_ids = {
            item.get("evidence_id")
            for item in as_list(child_map(run.get("evidence_ledger")).get("items"))
            if item.get("evidence_id")
        }
        for claim in as_list(child_map(run.get("claim_graph")).get("claims")):
            claim_map = child_map(claim)
            if _normalize_claim_status(claim_map.get("status")) == "supported":
                support = set(as_list(claim_map.get("supporting_evidence_ids")))
                if not support:
                    errors.append(f"SUPPORTED_CLAIM_WITHOUT_EVIDENCE:{claim_map.get('claim_id')}")
                if not support <= evidence_ids:
                    errors.append(f"SUPPORTED_CLAIM_CITES_UNKNOWN_EVIDENCE:{claim_map.get('claim_id')}")

    def _score_minimum_claim_coverage(self, case: JsonMap, run: JsonMap, errors: list[str]) -> None:
        contract = child_map(case.get("answer_contract"))
        minimum = contract.get("minimum_supported_claims")
        if minimum is None:
            required_claim_count = len([claim for claim in as_list(case.get("gold_claims")) if child_map(claim).get("required")])
            minimum = 1 if required_claim_count else 0
        supported = [
            claim for claim in as_list(child_map(run.get("claim_graph")).get("claims"))
            if _normalize_claim_status(child_map(claim).get("status")) == "supported"
        ]
        if int(minimum or 0) > len(supported):
            errors.append(f"SUPPORTED_CLAIM_COUNT_TOO_LOW:expected_at_least={minimum},actual={len(supported)}")


def _normalize_answer_type(value: Any) -> str:
    raw = str(value or "").lower()
    return {
        "factual": "exact_fact",
        "fact": "exact_fact",
        "factoid": "exact_fact",
        "exact": "exact_fact",
        "architecture_details": "exact_fact",
        "factual_specification": "exact_fact",
        "descriptive": "exact_fact",
        "compare": "comparison_matrix",
        "comparison": "comparison_matrix",
        "genealogy": "citation_genealogy",
        "citation_graph": "citation_genealogy",
        "conflict": "contradiction_arbitration",
        "contradiction": "contradiction_arbitration",
        "resolution": "contradiction_arbitration",
        "uncertainty": "uncertainty_boundary",
        "clarification": "ambiguity_clarification",
    }.get(raw, raw)


def _normalize_claim_status(value: Any) -> str:
    raw = str(value or "").lower()
    return {
        "support": "supported",
        "verified": "supported",
        "true": "supported",
        "unsupported": "underdetermined",
        "unknown": "underdetermined",
    }.get(raw, raw)


def _scalar_string(value: Any) -> str:
    if isinstance(value, float):
        value = f"{value:g}"
    text = str(value)
    return text.replace("e-0", "e-").replace("e+0", "e+")
