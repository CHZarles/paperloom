from __future__ import annotations

import re
from dataclasses import dataclass, field

from .claim_evidence import answer_blocks, canonical_sha256, dataset_content_sha256
from .fact_assertions import contract_sha256, evaluate_fact_assertions, fact_assertion_contract
from .golden_case import case_expect
from ..core.models import SCORE_REPORT_SCHEMA_VERSION, GoldenDataset, JsonMap, as_list, child_map
from ..core.status import (
    ExecutionStatus,
    execution_status_error,
    normalize_research_outcome,
    research_outcome_error,
)


@dataclass(frozen=True)
class DimensionScore:
    status: str
    errors: list[str] = field(default_factory=list)
    details: JsonMap = field(default_factory=dict)

    def to_dict(self) -> JsonMap:
        value: JsonMap = {"status": self.status, "errors": self.errors}
        if self.details:
            value["details"] = self.details
        return value


@dataclass(frozen=True)
class CaseScore:
    case_id: str
    case_status: str
    dimensions: dict[str, DimensionScore]
    review_candidates: list[JsonMap] = field(default_factory=list)

    def to_dict(self) -> JsonMap:
        value: JsonMap = {
            "case_id": self.case_id,
            "case_status": self.case_status,
            "dimensions": {key: score.to_dict() for key, score in self.dimensions.items()},
        }
        if self.review_candidates:
            value["review_candidates"] = self.review_candidates
        return value


class BehaviorScorer:
    def __init__(
        self,
        semantic_judgments: JsonMap | None = None,
        judge_gate: JsonMap | None = None,
    ) -> None:
        self.semantic_judgments = child_map(semantic_judgments)
        self.judge_gate = child_map(judge_gate)
        if self.semantic_judgments:
            if self.judge_gate.get("accepted") is not True:
                raise ValueError(
                    "semantic judgments require accepted calibration and holdout gates"
                )
            judgment_identity = child_map(self.semantic_judgments.get("judge_identity"))
            gate_identity = child_map(self.judge_gate.get("judge_identity"))
            if judgment_identity != gate_identity:
                raise ValueError("semantic judgment model and prompt do not match the judge gate")

    def score_case(self, dataset: GoldenDataset, case: JsonMap, run: JsonMap) -> CaseScore:
        case_id = str(case.get("id") or "")
        expectation = case_expect(case)
        answer = child_map(run.get("research_answer"))
        blocks, block_errors = answer_blocks(answer)
        accepted_evidence = _accepted_evidence(run)
        evidence_ids = [str(item["evidence_id"]) for item in accepted_evidence]
        evidence_by_id = {
            str(item["evidence_id"]): item
            for item in accepted_evidence
        }
        cited_ids = [
            str(item).strip()
            for item in as_list(answer.get("cited_evidence_ids"))
            if str(item).strip()
        ]
        citation_errors = list(block_errors)
        duplicate_evidence = _duplicates(evidence_ids)
        if duplicate_evidence:
            citation_errors.append(
                "DUPLICATE_LEDGER_EVIDENCE:" + ",".join(duplicate_evidence)
            )
        duplicates = _duplicates(cited_ids)
        if duplicates:
            citation_errors.append("DUPLICATE_CITED_EVIDENCE:" + ",".join(duplicates))
        unknown_citations = sorted(set(cited_ids) - set(evidence_by_id))
        if unknown_citations:
            citation_errors.append("UNKNOWN_CITED_EVIDENCE:" + ",".join(unknown_citations))

        claims = [
            dataset.claims_by_id[str(claim_id)]
            for claim_id in as_list(expectation.get("required_claims"))
        ]
        semantic = self._semantic_case(case_id)
        matched_blocks, claim_states, required_claims = self._score_required_claims(
            case,
            claims,
            blocks,
            semantic,
        )
        grounding, review_candidates = self._score_grounding(
            case,
            run,
            claims,
            matched_blocks,
            claim_states,
            blocks,
            evidence_by_id,
            cited_ids,
            citation_errors,
        )
        dimensions = {
            "outcome": self._score_outcome(case, run),
            "required_claims": required_claims,
            "facts": self._score_facts(expectation, answer),
            "grounding": grounding,
            "source_policy": self._score_source_policy(
                claims,
                matched_blocks,
                claim_states,
                blocks,
                evidence_by_id,
            ),
            "additional_claims": self._score_additional_claims(run, semantic),
        }
        return CaseScore(
            case_id=case_id,
            case_status=_case_status(dimensions),
            dimensions=dimensions,
            review_candidates=review_candidates,
        )

    def score_dataset(self, dataset: GoldenDataset, runs: list[JsonMap]) -> JsonMap:
        run_case_ids = [
            str(run.get("case_id") or run.get("question_id") or "")
            for run in runs
        ]
        duplicate_run_cases = _duplicates(run_case_ids)
        if duplicate_run_cases:
            raise ValueError(f"duplicate saved runs for cases: {duplicate_run_cases}")
        expected_case_ids = {str(case.get("id") or "") for case in dataset.cases}
        extra_run_cases = sorted(set(run_case_ids) - expected_case_ids)
        if extra_run_cases:
            raise ValueError(f"saved runs contain unknown cases: {extra_run_cases}")
        runs_by_case = dict(zip(run_case_ids, runs, strict=True))
        scores = [
            self.score_case(dataset, case, runs_by_case.get(str(case.get("id")), {}))
            for case in dataset.cases
        ]
        passed = sum(score.case_status == "pass" for score in scores)
        failed = sum(score.case_status == "fail" for score in scores)
        review_required = len(scores) - passed - failed
        resolved = passed + failed
        review_candidates = _dedupe_candidates([
            candidate
            for score in scores
            for candidate in score.review_candidates
        ])
        report: JsonMap = {
            "schema_version": SCORE_REPORT_SCHEMA_VERSION,
            "scorer_contract": _scorer_contract(),
            "dataset_id": dataset.manifest.get("dataset_id"),
            "dataset_content_sha256": dataset_content_sha256(dataset),
            "claim_catalog_sha256": canonical_sha256(dataset.claims_by_id),
            "runs_content_sha256": canonical_sha256(runs),
            "run_artifact_sha256_by_case": {
                case_id: canonical_sha256(run)
                for case_id, run in sorted(runs_by_case.items())
            },
            "case_count": len(scores),
            "passed_count": passed,
            "failed_count": failed,
            "review_required_count": review_required,
            "resolved_count": resolved,
            "resolved_pass_rate": passed / resolved if resolved else None,
            "review_coverage": resolved / len(scores) if scores else 1.0,
            "review_candidates": review_candidates,
            "scores": [score.to_dict() for score in scores],
        }
        if self.semantic_judgments:
            report["semantic_judgments"] = {
                key: self.semantic_judgments.get(key)
                for key in (
                    "source_path",
                    "sha256",
                    "judge",
                    "judge_identity",
                    "prompt_version",
                )
                if self.semantic_judgments.get(key) is not None
            }
            report["judge_gate"] = self.judge_gate
        return report

    def _semantic_case(self, case_id: str) -> JsonMap:
        return child_map(child_map(self.semantic_judgments.get("cases_by_id")).get(case_id))

    def _score_outcome(self, case: JsonMap, run: JsonMap) -> DimensionScore:
        expected = str(case_expect(case).get("outcome") or "")
        actual = _actual_outcome(run)
        errors = [] if expected == actual else [
            f"OUTCOME_MISMATCH:expected={expected}:actual={actual}"
        ]
        return _dimension(errors)

    def _score_facts(self, expectation: JsonMap, answer: JsonMap) -> DimensionScore:
        facts = child_map(expectation.get("facts"))
        if not facts:
            return DimensionScore("not_applicable")
        result = evaluate_fact_assertions(facts, answer)
        return DimensionScore(result.status, result.errors)

    def _score_required_claims(
        self,
        case: JsonMap,
        claims: list[JsonMap],
        blocks: list[JsonMap],
        semantic: JsonMap,
    ) -> tuple[dict[str, list[str]], dict[str, str], DimensionScore]:
        known_block_ids = {str(block.get("block_id")) for block in blocks}
        semantic_claims = child_map(semantic.get("claims_by_id"))
        matched: dict[str, list[str]] = {}
        states: dict[str, str] = {}
        errors: list[str] = []
        reviews: list[str] = []
        authorities: dict[str, str] = {}

        for claim in claims:
            claim_id = str(claim.get("claim_id") or "")
            deterministic = _deterministic_claim_blocks(case, claim, blocks)
            if deterministic:
                matched[claim_id] = deterministic
                states[claim_id] = "pass"
                authorities[claim_id] = "deterministic"
                continue

            judgment = child_map(semantic_claims.get(claim_id))
            verdict = str(judgment.get("verdict") or "")
            raw_matched = [
                str(block_id)
                for block_id in as_list(judgment.get("matched_block_ids"))
            ]
            invalid_blocks = sorted(set(raw_matched) - known_block_ids)
            if invalid_blocks:
                states[claim_id] = "review_required"
                reviews.append(f"SEMANTIC_JUDGE_INVALID_BLOCK:{claim_id}")
                continue
            block_ids = list(dict.fromkeys(raw_matched))
            if verdict == "expressed" and block_ids:
                matched[claim_id] = block_ids
                states[claim_id] = "pass"
                authorities[claim_id] = "calibrated_semantic_judge"
            elif verdict in {"missing", "contradicted"}:
                states[claim_id] = "fail"
                errors.append(f"REQUIRED_CLAIM_{verdict.upper()}:{claim_id}")
            else:
                states[claim_id] = "review_required"
                reviews.append(f"SEMANTIC_JUDGMENT_REQUIRED:{claim_id}")

        status = "fail" if errors else "review_required" if reviews else "pass"
        details: JsonMap = {
            "matched_blocks": matched,
            "claim_statuses": states,
            "authorities": authorities,
        }
        return matched, states, DimensionScore(status, [*errors, *reviews], details)

    def _score_grounding(
        self,
        case: JsonMap,
        run: JsonMap,
        claims: list[JsonMap],
        matched_blocks: dict[str, list[str]],
        claim_states: dict[str, str],
        blocks: list[JsonMap],
        evidence_by_id: dict[str, JsonMap],
        cited_ids: list[str],
        citation_errors: list[str],
    ) -> tuple[DimensionScore, list[JsonMap]]:
        policy = str(case_expect(case).get("citations") or "optional")
        failures = list(citation_errors)
        reviews: list[str] = []
        candidates: list[JsonMap] = []
        if policy == "required" and not cited_ids:
            failures.append("CITATIONS_REQUIRED")
        if policy == "forbidden" and cited_ids:
            failures.append("CITATIONS_FORBIDDEN")

        blocks_by_id = {str(block.get("block_id")): block for block in blocks}
        for claim in claims:
            claim_id = str(claim.get("claim_id") or "")
            state = claim_states.get(claim_id)
            if state == "review_required":
                reviews.append(f"CLAIM_GROUNDING_PENDING:{claim_id}")
                continue
            if state != "pass":
                continue

            requirements = [
                child_map(item)
                for item in as_list(claim.get("required_evidence"))
            ]
            evaluated_blocks: list[tuple[list[JsonMap], list[str]]] = []
            for block_id in matched_blocks.get(claim_id, []):
                evidence = [
                    evidence_by_id[evidence_id]
                    for evidence_id in as_list(
                        child_map(blocks_by_id.get(block_id)).get("evidence_ids")
                    )
                    if evidence_id in evidence_by_id
                ]
                requirement_states = [
                    _requirement_state(requirement, evidence)
                    for requirement in requirements
                ]
                evaluated_blocks.append((evidence, requirement_states))

            if any(all(state == "accepted" for state in states) for _, states in evaluated_blocks):
                continue

            reviewable = [
                evidence
                for evidence, states in evaluated_blocks
                if all(state in {"accepted", "unknown_location"} for state in states)
            ]
            if reviewable:
                reviews.append(f"UNREVIEWED_CLAIM_LOCATION:{claim_id}")
                required_by_paper = {
                    str(requirement.get("paper_id") or ""): set(
                        str(item)
                        for item in as_list(requirement.get("accepted_locations"))
                    )
                    for requirement in requirements
                }
                for evidence in reviewable:
                    for item in evidence:
                        paper_id = str(item.get("paper_id") or "")
                        accepted = required_by_paper.get(paper_id)
                        location_ref = _location_ref(item)
                        if accepted is not None and location_ref and location_ref not in accepted:
                            candidates.append(_review_candidate(case, run, claim, item))
                continue

            accepted_somewhere = [
                any(states[index] == "accepted" for _, states in evaluated_blocks)
                for index in range(len(requirements))
            ]
            if evaluated_blocks and all(accepted_somewhere):
                failures.append(f"CLAIM_EVIDENCE_NOT_COLOCATED:{claim_id}")
                continue
            missing_papers = sorted({
                str(requirement.get("paper_id") or "")
                for index, requirement in enumerate(requirements)
                if not any(
                    states[index] in {"accepted", "unknown_location"}
                    for _, states in evaluated_blocks
                )
            })
            for paper_id in missing_papers:
                failures.append(
                    f"REQUIRED_CLAIM_EVIDENCE_MISSING:{claim_id}:{paper_id}"
                )

        status = "fail" if failures else "review_required" if reviews else "pass"
        return DimensionScore(status, [*failures, *reviews]), _dedupe_candidates(candidates)

    def _score_source_policy(
        self,
        claims: list[JsonMap],
        matched_blocks: dict[str, list[str]],
        claim_states: dict[str, str],
        blocks: list[JsonMap],
        evidence_by_id: dict[str, JsonMap],
    ) -> DimensionScore:
        blocks_by_id = {str(block.get("block_id")): block for block in blocks}
        errors: list[str] = []
        for claim in claims:
            claim_id = str(claim.get("claim_id") or "")
            if claim_states.get(claim_id) != "pass":
                continue
            forbidden = set(str(item) for item in as_list(claim.get("forbidden_paper_ids")))
            cited_papers = {
                str(evidence_by_id[evidence_id].get("paper_id") or "")
                for block_id in matched_blocks.get(claim_id, [])
                for evidence_id in as_list(
                    child_map(blocks_by_id.get(block_id)).get("evidence_ids")
                )
                if evidence_id in evidence_by_id
            }
            for paper_id in sorted(cited_papers & forbidden):
                errors.append(f"FORBIDDEN_PAPER_FOR_CLAIM:{claim_id}:{paper_id}")
        return _dimension(errors)

    def _score_additional_claims(self, run: JsonMap, semantic: JsonMap) -> DimensionScore:
        if str(run.get("harness_id") or "") == GOLDEN_FIXTURE_HARNESS_ID:
            return DimensionScore("pass", details={"authority": "golden_fixture"})
        judgment = child_map(semantic.get("additional_claims"))
        verdict = str(judgment.get("verdict") or "")
        if verdict == "fail":
            issues = [
                str(item)
                for item in as_list(judgment.get("grounding_issues"))
                if item
            ]
            return DimensionScore("fail", issues or ["ADDITIONAL_CLAIM_GROUNDING_FAILED"])
        if verdict == "pass":
            return DimensionScore(
                "pass",
                details={"authority": "calibrated_semantic_judge"},
            )
        return DimensionScore("review_required", ["ADDITIONAL_CLAIM_JUDGMENT_REQUIRED"])


GOLDEN_FIXTURE_HARNESS_ID = "golden_fixture_v4"


def _scorer_contract() -> JsonMap:
    contract = {
        "version": "behavior-scorer/v4",
        "case_status_rule": "fail-before-review-before-pass/v1",
        "claim_evidence": "golden-claims-same-block-location/v1",
        "semantic_judgments": "calibrated-offline-claim-block-judge/v1",
        "fact_assertions": fact_assertion_contract(),
    }
    return {**contract, "sha256": contract_sha256(contract)}


def _case_status(dimensions: dict[str, DimensionScore]) -> str:
    statuses = {score.status for score in dimensions.values()}
    if "fail" in statuses:
        return "fail"
    if "review_required" in statuses:
        return "review_required"
    return "pass"


def _dimension(errors: list[str]) -> DimensionScore:
    return DimensionScore("fail" if errors else "pass", list(dict.fromkeys(errors)))


def _accepted_evidence(run: JsonMap) -> list[JsonMap]:
    return [
        item
        for raw_item in as_list(child_map(run.get("evidence_ledger")).get("items"))
        if (item := child_map(raw_item)).get("evidence_id")
        and item.get("citeable") is not False
        and item.get("evidence_quality") != "rejected"
        and item.get("element_type") != "paper_candidate"
    ]


def _deterministic_claim_blocks(
    case: JsonMap,
    claim: JsonMap,
    blocks: list[JsonMap],
) -> list[str]:
    statement = _normalize(str(claim.get("statement") or ""))
    exact = [
        str(block.get("block_id"))
        for block in blocks
        if statement and statement.rstrip(".") in _normalize(str(block.get("text") or ""))
    ]
    if exact:
        return exact
    facts = child_map(case_expect(case).get("facts"))
    fact_keys = [str(item) for item in as_list(claim.get("fact_keys"))]
    if not fact_keys or any(key not in facts for key in fact_keys):
        return []
    selected = {key: facts[key] for key in fact_keys}
    return [
        str(block.get("block_id"))
        for block in blocks
        if evaluate_fact_assertions(
            selected,
            {"markdown": str(block.get("text") or "")},
        ).status == "pass"
    ]


def _requirement_state(requirement: JsonMap, evidence: list[JsonMap]) -> str:
    paper_id = str(requirement.get("paper_id") or "")
    accepted = {
        str(item)
        for item in as_list(requirement.get("accepted_locations"))
    }
    from_paper = [
        item
        for item in evidence
        if str(item.get("paper_id") or "") == paper_id
    ]
    if any(_location_ref(item) in accepted for item in from_paper):
        return "accepted"
    if any(_location_ref(item) for item in from_paper):
        return "unknown_location"
    return "missing"


def _review_candidate(case: JsonMap, run: JsonMap, claim: JsonMap, evidence: JsonMap) -> JsonMap:
    return {
        "claim_id": claim.get("claim_id"),
        "case_id": case.get("id"),
        "run_id": run.get("run_id"),
        "paper_id": evidence.get("paper_id"),
        "evidence_id": evidence.get("evidence_id"),
        "location_ref": _location_ref(evidence),
        "returned_text": evidence.get("span_text") or "",
    }


def _dedupe_candidates(candidates: list[JsonMap]) -> list[JsonMap]:
    unique: dict[tuple[str, str, str, str], JsonMap] = {}
    for candidate in candidates:
        key = (
            str(candidate.get("claim_id") or ""),
            str(candidate.get("case_id") or ""),
            str(candidate.get("paper_id") or ""),
            str(candidate.get("location_ref") or ""),
        )
        unique.setdefault(key, candidate)
    return [unique[key] for key in sorted(unique)]


def _duplicates(values: list[str]) -> list[str]:
    seen: set[str] = set()
    duplicates: set[str] = set()
    for value in values:
        if value in seen:
            duplicates.add(value)
        seen.add(value)
    return sorted(duplicates)


def _location_ref(item: JsonMap) -> str:
    return str(item.get("location_ref") or item.get("location") or "").strip()


def _normalize(value: str) -> str:
    return re.sub(r"\s+", " ", value.casefold()).strip()


def _actual_outcome(run: JsonMap) -> str:
    answer = child_map(run.get("research_answer"))
    status_values = [run.get("status"), answer.get("status")]
    if "result_status" in run:
        status_values.append(run.get("result_status"))
    if ExecutionStatus.FAILED_TECHNICAL.value in status_values:
        return "technical_failure"
    status_error = (
        execution_status_error(run.get("status"), answer.get("status"), run.get("result_status"))
        if "result_status" in run
        else execution_status_error(run.get("status"), answer.get("status"))
    )
    if status_error:
        return "invalid"
    if "outcome" not in answer or answer.get("outcome") is None:
        return "missing"
    explicit = normalize_research_outcome(answer.get("outcome"))
    if explicit is None:
        return "invalid"
    if research_outcome_error(answer.get("status"), answer.get("outcome")):
        return "invalid"
    return explicit
