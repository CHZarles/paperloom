from __future__ import annotations

import unittest

from harness_py.evaluation.claim_judge import (
    ClaimEvidenceJudge,
    ClaimEvidenceJudgeVerdict,
)
from harness_py.evaluation.claim_judge_calibration import (
    evaluate_claim_judge_calibration,
    load_claim_judge_labels,
)


class ClaimJudgeTest(unittest.TestCase):
    def test_claim_judge_enforces_complete_claim_and_block_coverage(self) -> None:
        class Model:
            def complete_judgment(self, _messages, tool, _max_tokens):
                self.tool = tool
                return [{
                    "name": "submit_claim_judgment",
                    "arguments": {
                        "claims": [{
                            "claim_id": "claim_a",
                            "verdict": "expressed",
                            "matched_block_ids": ["block_1"],
                        }],
                        "additional_claims": {
                            "verdict": "fail",
                            "grounding_issues": ["Extra unsupported statement."],
                        },
                    },
                }]

        model = Model()
        verdict = ClaimEvidenceJudge(model).judge({
            "required_claims": [{"claim_id": "claim_a", "statement": "Claim A."}],
            "answer_blocks": [
                {"block_id": "block_1", "text": "Claim A.", "evidence": []},
                {"block_id": "block_2", "text": "Extra.", "evidence": []},
            ],
        })

        self.assertEqual("fail", verdict.additional_claims["verdict"])
        function = model.tool["function"]
        self.assertEqual("submit_claim_judgment", function["name"])

    def test_claim_judge_conservatively_normalizes_issues_on_non_failing_block(self) -> None:
        class Model:
            def complete_judgment(self, _messages, _tool, _max_tokens):
                return [{
                    "name": "submit_claim_judgment",
                    "arguments": {
                        "claims": [{
                            "claim_id": "claim_a",
                            "verdict": "expressed",
                            "matched_block_ids": ["block_1"],
                        }],
                        "additional_claims": {
                            "verdict": "pass",
                            "grounding_issues": ["This extra clause lacks evidence."],
                        },
                    },
                }]

        verdict = ClaimEvidenceJudge(Model()).judge({
            "required_claims": [{"claim_id": "claim_a", "statement": "Claim A."}],
            "answer_blocks": [{"block_id": "block_1", "text": "Claim A and extra.", "evidence": []}],
        })

        self.assertEqual("fail", verdict.additional_claims["verdict"])

    def test_committed_claim_judge_labels_are_valid_and_can_pass_exact_judgments(self) -> None:
        for path, expected_count in (
            ("research/golden-data/human-labels-claim-judge-calibration.yaml", 6),
            ("research/golden-data/human-labels-claim-judge-holdout.yaml", 11),
        ):
            with self.subTest(path=path):
                label_set = load_claim_judge_labels(path)
                expected_by_case = {label.case_id: label for label in label_set.labels}

                class Judge:
                    def judge(self, packet):
                        label = expected_by_case[str(packet["case_id"])]
                        claims = [
                            {"claim_id": claim_id, **expected}
                            for claim_id, expected in label.expected_claims.items()
                        ]
                        return ClaimEvidenceJudgeVerdict(claims, {
                            "verdict": label.expected_additional,
                            "grounding_issues": (
                                ["Human-labelled unsupported addition."]
                                if label.expected_additional == "fail"
                                else []
                            ),
                        })

                report = evaluate_claim_judge_calibration(
                    label_set,
                    Judge(),
                    judge_metadata={"provider": "test", "model": "judge"},
                )

                self.assertEqual(expected_count, report["case_count"])
                self.assertTrue(report["accepted"], report)
                self.assertEqual(0, report["false_pass_count"])

    def test_calibration_accepts_safe_semantic_subset_and_ignores_deterministic_blocks(self) -> None:
        label_set = load_claim_judge_labels(
            "research/golden-data/human-labels-claim-judge-calibration.yaml"
        )
        judge = _LabelJudge(label_set)
        judge.claim_overrides = {
            "bert_vs_transformer_comparison_001": {
                "bert_masked_language_model_objective": ["block_4"],
            },
            "agentbench_environment_inventory_001": {
                "agentbench_eight_environments": ["block_1", "block_3"],
            },
        }

        report = evaluate_claim_judge_calibration(label_set, judge)

        self.assertTrue(report["accepted"], report)
        agentbench = next(
            case for case in report["cases"]
            if case["case_id"] == "agentbench_environment_inventory_001"
        )
        self.assertIsNone(agentbench["claim_matches"]["agentbench_eight_environments"])

    def test_calibration_rejects_an_unsafe_semantic_block_as_false_pass(self) -> None:
        label_set = load_claim_judge_labels(
            "research/golden-data/human-labels-claim-judge-calibration.yaml"
        )
        judge = _LabelJudge(label_set)
        judge.claim_overrides = {
            "bert_vs_transformer_comparison_001": {
                "transformer_bert_architecture_comparison": ["block_8"],
            },
        }

        report = evaluate_claim_judge_calibration(label_set, judge)

        self.assertFalse(report["accepted"])
        self.assertEqual(1, report["false_pass_count"])


class _LabelJudge:
    def __init__(self, label_set) -> None:
        self.labels = {label.case_id: label for label in label_set.labels}
        self.claim_overrides = {}

    def judge(self, packet):
        label = self.labels[str(packet["case_id"])]
        overrides = self.claim_overrides.get(label.case_id, {})
        claims = []
        for claim_id, expected in label.expected_claims.items():
            matched = overrides.get(claim_id, expected["matched_block_ids"])
            claims.append({
                "claim_id": claim_id,
                "verdict": expected["verdict"],
                "matched_block_ids": matched,
            })
        return ClaimEvidenceJudgeVerdict(claims, {
            "verdict": label.expected_additional,
            "grounding_issues": (
                ["Human-labelled unsupported addition."]
                if label.expected_additional == "fail"
                else []
            ),
        })


if __name__ == "__main__":
    unittest.main()
