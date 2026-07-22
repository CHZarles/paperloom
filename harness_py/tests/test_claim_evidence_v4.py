from __future__ import annotations

import unittest
from pathlib import Path

from harness_py.core.models import GoldenDataset
from harness_py.evaluation.claim_audit import audit_claim_locations
from harness_py.evaluation.claim_evidence import answer_blocks, generate_semantic_judgments
from harness_py.evaluation.claim_judge import CLAIM_JUDGE_PROMPT_VERSION
from harness_py.evaluation.dataset import load_dataset
from harness_py.evaluation.golden_fixture import GoldenFixtureHarness
from harness_py.evaluation.scoring import BehaviorScorer


class ClaimEvidenceV4Test(unittest.TestCase):
    def test_numbered_citations_are_reconstructed_per_answer_block(self) -> None:
        blocks, errors = answer_blocks({
            "markdown": (
                "First claim. [1]\n\n"
                "- Second claim. [2]\n\n"
                "Sources\n[1] Paper A\n[2] Paper B"
            ),
            "cited_evidence_ids": ["ev_a", "ev_b"],
        })

        self.assertEqual([], errors)
        self.assertEqual(
            [("block_1", ["ev_a"]), ("block_2", ["ev_b"])],
            [(block["block_id"], block["evidence_ids"]) for block in blocks],
        )
        self.assertEqual(["paragraph", "list_item"], [block["kind"] for block in blocks])

    def test_markdown_structure_is_preserved_as_block_kinds(self) -> None:
        blocks, errors = answer_blocks({
            "markdown": (
                "# Comparison\n\n"
                "| Benchmark | Result |\n"
                "| --- | --- |\n"
                "| A | 10 [1] |\n\n"
                "Conclusion. [2]\n\n"
                "Sources\n[1] Paper A\n[2] Paper B"
            ),
            "cited_evidence_ids": ["ev_a", "ev_b"],
        })

        self.assertEqual([], errors)
        self.assertEqual(
            ["heading", "table_header", "table_row", "paragraph"],
            [block["kind"] for block in blocks],
        )
        self.assertEqual(
            [[], [], ["ev_a"], ["ev_b"]],
            [block["evidence_ids"] for block in blocks],
        )

    def test_legacy_direct_evidence_citation_is_reconstructed_for_saved_runs(self) -> None:
        blocks, errors = answer_blocks({
            "markdown": "Saved claim. [ev_a]",
            "cited_evidence_ids": ["ev_a"],
        })

        self.assertEqual([], errors)
        self.assertEqual(["ev_a"], blocks[0]["evidence_ids"])

    def test_accepted_location_passes_and_forbidden_paper_elsewhere_is_allowed(self) -> None:
        dataset, case = _dataset()
        run = _run(
            "Required claim. [1]\n\nUnrelated context. [2]",
            [
                _evidence("ev_required", "paper_a", "section_ref_required"),
                _evidence("ev_forbidden", "paper_b", "section_ref_context"),
            ],
        )
        score = BehaviorScorer().score_case(dataset, case, run)

        self.assertEqual("pass", score.case_status, score.to_dict())
        self.assertEqual("pass", score.dimensions["grounding"].status)
        self.assertEqual("pass", score.dimensions["source_policy"].status)

    def test_unknown_correct_paper_location_requires_review_and_is_exported(self) -> None:
        dataset, case = _dataset()
        run = _run(
            "Required claim. [1]",
            [_evidence("ev_new", "paper_a", "section_ref_new")],
        )
        score = BehaviorScorer().score_case(dataset, case, run)

        self.assertEqual("review_required", score.case_status)
        self.assertEqual("review_required", score.dimensions["grounding"].status)
        self.assertEqual(
            {
                "claim_id",
                "case_id",
                "run_id",
                "paper_id",
                "evidence_id",
                "location_ref",
                "returned_text",
            },
            set(score.review_candidates[0]),
        )
        self.assertEqual("section_ref_new", score.review_candidates[0]["location_ref"])

    def test_every_multi_paper_requirement_must_be_satisfied(self) -> None:
        dataset, case = _dataset(multi_paper=True)
        run = _run(
            "Required claim. [1]",
            [_evidence("ev_required", "paper_a", "section_ref_required")],
        )
        score = BehaviorScorer().score_case(dataset, case, run)

        self.assertEqual("fail", score.case_status)
        self.assertIn(
            "REQUIRED_CLAIM_EVIDENCE_MISSING:required_claim:paper_c",
            score.dimensions["grounding"].errors,
        )

    def test_multi_paper_evidence_must_be_in_the_same_claim_block(self) -> None:
        dataset, case = _dataset(multi_paper=True)
        run = _run(
            "Required claim. [1]\n\nRequired claim. [2]",
            [
                _evidence("ev_a", "paper_a", "section_ref_required"),
                _evidence("ev_c", "paper_c", "table_ref_required"),
            ],
        )

        score = BehaviorScorer().score_case(dataset, case, run)

        self.assertEqual("fail", score.case_status)
        self.assertIn(
            "CLAIM_EVIDENCE_NOT_COLOCATED:required_claim",
            score.dimensions["grounding"].errors,
        )

    def test_calibrated_semantic_judgment_can_match_a_paraphrased_block(self) -> None:
        dataset, case = _dataset()
        run = _run(
            "This is a faithful paraphrase. [1]",
            [_evidence("ev_required", "paper_a", "section_ref_required")],
        )
        identity = {
            "provider": "test",
            "model": "judge",
            "prompt_version": "prompt-v1",
        }
        semantic = {
            "judge_identity": identity,
            "cases_by_id": {
                "case": {
                    "claims_by_id": {
                        "required_claim": {
                            "verdict": "expressed",
                            "matched_block_ids": ["block_1"],
                        }
                    },
                    "additional_claims": {"verdict": "pass"},
                }
            },
        }

        score = BehaviorScorer(
            semantic,
            {"accepted": True, "judge_identity": identity},
        ).score_case(dataset, case, run)

        self.assertEqual("pass", score.case_status, score.to_dict())

    def test_typed_fact_failure_remains_deterministic(self) -> None:
        dataset = load_dataset("research/golden-data/manifest.yaml")
        case = next(
            item for item in dataset.cases
            if item["id"] == "transformer_adam_params_001"
        )
        run = GoldenFixtureHarness().run_case(dataset, case)
        run["research_answer"]["fields"]["beta2"] = "0.999"

        score = BehaviorScorer().score_case(dataset, case, run)

        self.assertEqual("fail", score.case_status)
        self.assertIn("FACT_MISMATCH:beta2", score.dimensions["facts"].errors)

    def test_claim_location_audit_resolves_supported_product_locations(self) -> None:
        dataset, _ = _dataset()

        class Reader:
            def read_locations(self, arguments):
                return {
                    "items": [
                        {
                            "paper_id": "paper_a",
                            "location_ref": location_ref,
                            "element_type": "section",
                            "span_text": "Canonical claim evidence.",
                        }
                        for location_ref in arguments["location_refs"]
                    ],
                    "missing_location_refs": [],
                }

        report = audit_claim_locations(dataset, Reader())

        self.assertEqual(1, report["passed_count"])
        self.assertEqual(0, report["failed_count"])

    def test_saved_run_judge_matches_blocks_without_exposing_anchor_tags(self) -> None:
        dataset, _ = _dataset()
        evidence = _evidence("ev_required", "paper_a", "section_ref_required")
        evidence["matched_anchor_ids"] = ["audit_only_anchor"]
        run = _run("This is a faithful paraphrase. [1]", [evidence])

        class Verdict:
            def to_dict(self):
                return {
                    "claims": [{
                        "claim_id": "required_claim",
                        "verdict": "expressed",
                        "matched_block_ids": ["block_1"],
                    }],
                    "blocks": [{
                        "block_id": "block_1",
                        "verdict": "not_material",
                        "issues": [],
                    }],
                    "additional_claims": {
                        "verdict": "pass",
                        "grounding_issues": [],
                    },
                }

        class Judge:
            def __init__(self) -> None:
                self.packets = []

            def judge(self, packet):
                self.packets.append(packet)
                self.assert_clean(packet)
                return Verdict()

            @staticmethod
            def assert_clean(value):
                assert all(
                    "matched_anchor_ids" not in item
                    for block in value["answer_blocks"]
                    for item in block["evidence"]
                )

        judge = Judge()
        report = generate_semantic_judgments(
            dataset,
            [run],
            judge,
            judge_metadata={"provider": "test", "model": "judge"},
            prompt_version=CLAIM_JUDGE_PROMPT_VERSION,
        )

        self.assertEqual("expressed", report["cases"][0]["claims"][0]["verdict"])
        self.assertEqual(["block_1"], report["cases"][0]["claims"][0]["matched_block_ids"])
        self.assertEqual("pass", report["cases"][0]["additional_claims"]["verdict"])
        self.assertEqual("claim-evidence-semantic-judgment/v1", report["judgment_contract"])
        self.assertEqual(0, report["technical_error_count"])
        self.assertEqual(1, len(judge.packets))

    def test_committed_manifests_are_v4_and_fixture_cleanly_passes(self) -> None:
        for manifest, expected_count in (
            ("research/golden-data/manifest.yaml", 10),
            ("research/golden-data/manifest-expanded.yaml", 24),
        ):
            with self.subTest(manifest=manifest):
                dataset = load_dataset(manifest)
                runs = [GoldenFixtureHarness().run_case(dataset, case) for case in dataset.cases]
                report = BehaviorScorer().score_dataset(dataset, runs)

                self.assertEqual("harness-golden-data/v3", dataset.manifest["schema_version"])
                self.assertEqual("harness-score-report/v4", report["schema_version"])
                self.assertEqual(expected_count, report["passed_count"], report)
                self.assertEqual(0, report["failed_count"], report)
                self.assertEqual(0, report["review_required_count"], report)


def _dataset(*, multi_paper: bool = False) -> tuple[GoldenDataset, dict]:
    requirements = [{
        "paper_id": "paper_a",
        "accepted_locations": ["section_ref_required"],
    }]
    if multi_paper:
        requirements.append({
            "paper_id": "paper_c",
            "accepted_locations": ["table_ref_required"],
        })
    case = {
        "id": "case",
        "expect": {
            "outcome": "answered",
            "required_claims": ["required_claim"],
            "citations": "required",
        },
    }
    return GoldenDataset(
        root=Path.cwd(),
        manifest_path=Path("manifest.yaml"),
        manifest={"schema_version": "harness-golden-data/v3", "dataset_id": "fixture"},
        paper_packs=[],
        cases=[case],
        paper_records_by_id={
            paper_id: {"paper_id": paper_id, "identity": {"title": paper_id}}
            for paper_id in ("paper_a", "paper_b", "paper_c")
        },
        anchors_by_id={},
        citation_edges=[],
        reading_models_by_paper_id={},
        claims_by_id={
            "required_claim": {
                "claim_id": "required_claim",
                "statement": "Required claim.",
                "required_evidence": requirements,
                "forbidden_paper_ids": ["paper_b"],
            }
        },
    ), case


def _run(markdown: str, evidence: list[dict]) -> dict:
    cited = [item["evidence_id"] for item in evidence]
    answer = {
        "status": "COMPLETED",
        "outcome": "answered",
        "markdown": markdown,
        "cited_evidence_ids": cited,
    }
    return {
        "run_id": "run_case",
        "case_id": "case",
        "harness_id": "golden_fixture_v4",
        "status": "COMPLETED",
        "result_status": "COMPLETED",
        "research_answer": answer,
        "evidence_ledger": {"items": evidence},
    }


def _evidence(evidence_id: str, paper_id: str, location_ref: str) -> dict:
    return {
        "evidence_id": evidence_id,
        "paper_id": paper_id,
        "location_ref": location_ref,
        "element_type": "section",
        "span_text": f"Evidence from {paper_id} at {location_ref}.",
        "evidence_quality": "verified",
    }


if __name__ == "__main__":
    unittest.main()
