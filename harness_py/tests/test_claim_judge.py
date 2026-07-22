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
                self.tools = getattr(self, "tools", []) + [tool]
                name = tool["function"]["name"]
                if name == "submit_claim_match":
                    return [{
                        "name": name,
                        "arguments": {
                            "claim_id": "claim_a",
                            "verdict": "expressed",
                            "matched_block_id": "block_1",
                        },
                    }]
                if name == "submit_claim_contradiction":
                    return [{
                        "name": name,
                        "arguments": {
                            "claim_id": "claim_a",
                            "contradicted": False,
                            "contradicting_block_id": "",
                        },
                    }]
                block_id = tool["function"]["parameters"]["properties"]["block_id"]["enum"][0]
                return [{
                    "name": name,
                    "arguments": {
                        "block_id": block_id,
                        "verdict": "supported",
                        "issues": [],
                    },
                }]

        model = Model()
        verdict = ClaimEvidenceJudge(model).judge({
            "required_claims": [{"claim_id": "claim_a", "statement": "Claim A."}],
            "answer_blocks": [
                {
                    "block_id": "block_1",
                    "text": "Claim A.",
                    "evidence": [{"span_text": "Claim A."}],
                },
                {"block_id": "block_2", "text": "Extra.", "evidence": []},
            ],
        })

        self.assertEqual("fail", verdict.additional_claims["verdict"])
        self.assertEqual(
            [
                "submit_claim_match",
                "submit_claim_match",
                "submit_claim_contradiction",
                "submit_block_grounding",
            ],
            [tool["function"]["name"] for tool in model.tools],
        )

    def test_claim_judge_conservatively_normalizes_issues_on_non_failing_block(self) -> None:
        class Model:
            def complete_judgment(self, _messages, tool, _max_tokens):
                name = tool["function"]["name"]
                if name == "submit_claim_match":
                    return [{
                        "name": name,
                        "arguments": {
                            "claim_id": "claim_a",
                            "verdict": "expressed",
                            "matched_block_id": "block_1",
                        },
                    }]
                if name == "submit_claim_contradiction":
                    return [{
                        "name": name,
                        "arguments": {
                            "claim_id": "claim_a",
                            "contradicted": False,
                            "contradicting_block_id": "",
                        },
                    }]
                return [{
                    "name": name,
                    "arguments": {
                        "block_id": "block_1",
                        "verdict": "supported",
                        "issues": ["This extra clause lacks evidence."],
                    },
                }]

        verdict = ClaimEvidenceJudge(Model()).judge({
            "required_claims": [{"claim_id": "claim_a", "statement": "Claim A."}],
            "answer_blocks": [{
                "block_id": "block_1",
                "text": "Claim A and extra.",
                "evidence": [{"span_text": "Claim A."}],
            }],
        })

        self.assertEqual("fail", verdict.additional_claims["verdict"])

    def test_uncited_organizational_block_is_not_material(self) -> None:
        class Model:
            def complete_judgment(self, _messages, tool, _max_tokens):
                name = tool["function"]["name"]
                if name == "submit_claim_match":
                    arguments = {
                        "claim_id": "claim_a",
                        "verdict": "expressed",
                        "matched_block_id": "block_1",
                    }
                elif name == "submit_claim_contradiction":
                    arguments = {
                        "claim_id": "claim_a",
                        "contradicted": False,
                        "contradicting_block_id": "",
                    }
                else:
                    arguments = {
                        "block_id": "block_1",
                        "verdict": "supported",
                        "issues": [],
                    }
                return [{"name": name, "arguments": arguments}]

        verdict = ClaimEvidenceJudge(Model()).judge({
            "required_claims": [{"claim_id": "claim_a", "statement": "Claim A."}],
            "answer_blocks": [{
                "block_id": "block_1",
                "text": "Claim A.",
                "evidence": [{"span_text": "Claim A."}],
            }, {
                "block_id": "block_2",
                "kind": "heading",
                "text": "Here is the requested comparison.",
                "evidence": [],
            }],
        })

        self.assertEqual("pass", verdict.additional_claims["verdict"])

    def test_contradiction_overrides_an_expressed_match(self) -> None:
        class Model:
            def complete_judgment(self, _messages, tool, _max_tokens):
                self.tools = getattr(self, "tools", []) + [tool]
                name = tool["function"]["name"]
                if name == "submit_claim_match":
                    arguments = {
                        "claim_id": "claim_a",
                        "verdict": "expressed",
                        "matched_block_id": "block_2",
                    }
                elif name == "submit_claim_contradiction":
                    arguments = {
                        "claim_id": "claim_a",
                        "contradicted": True,
                        "contradicting_block_id": "block_1",
                    }
                else:
                    block_id = tool["function"]["parameters"]["properties"]["block_id"]["enum"][0]
                    arguments = {
                        "block_id": block_id,
                        "verdict": "supported",
                        "issues": [],
                    }
                return [{"name": name, "arguments": arguments}]

        model = Model()
        verdict = ClaimEvidenceJudge(model).judge({
            "required_claims": [{"claim_id": "claim_a", "statement": "Do not rank them."}],
            "answer_blocks": [{
                "block_id": "block_1",
                "text": "Benchmark A ranks above benchmark B.",
                "evidence": [{"span_text": "Benchmark A ranks above benchmark B."}],
            }, {
                "block_id": "block_2",
                "text": "The benchmarks are not directly comparable.",
                "evidence": [{"span_text": "The benchmarks are not directly comparable."}],
            }],
        })

        self.assertEqual("contradicted", verdict.claims[0]["verdict"])
        self.assertEqual([], verdict.claims[0]["matched_block_ids"])
        self.assertEqual(
            [
                "submit_claim_match",
                "submit_claim_match",
                "submit_claim_contradiction",
                "submit_block_grounding",
                "submit_block_grounding",
            ],
            [tool["function"]["name"] for tool in model.tools],
        )

    def test_claim_matching_checks_later_answer_block_batches(self) -> None:
        class Model:
            def complete_judgment(self, _messages, tool, _max_tokens):
                name = tool["function"]["name"]
                if name == "submit_claim_match":
                    block_ids = tool["function"]["parameters"]["properties"][
                        "matched_block_id"
                    ]["enum"]
                    matched = "block_9" if "block_9" in block_ids else ""
                    arguments = {
                        "claim_id": "claim_a",
                        "verdict": "expressed" if matched else "missing",
                        "matched_block_id": matched,
                    }
                elif name == "submit_claim_contradiction":
                    arguments = {
                        "claim_id": "claim_a",
                        "contradicted": False,
                        "contradicting_block_id": "",
                    }
                else:
                    block_id = tool["function"]["parameters"]["properties"]["block_id"][
                        "enum"
                    ][0]
                    arguments = {"block_id": block_id, "material": False}
                return [{"name": name, "arguments": arguments}]

        verdict = ClaimEvidenceJudge(Model()).judge({
            "required_claims": [{"claim_id": "claim_a", "statement": "Final answer."}],
            "answer_blocks": [
                {
                    "block_id": f"block_{index}",
                    "text": "Final answer." if index == 9 else f"Heading {index}",
                    "evidence": [],
                }
                for index in range(1, 10)
            ],
        })

        self.assertEqual("expressed", verdict.claims[0]["verdict"])
        self.assertEqual(["block_9"], verdict.claims[0]["matched_block_ids"])

    def test_claim_matching_prefers_a_complete_cited_block(self) -> None:
        class Model:
            def complete_judgment(self, _messages, tool, _max_tokens):
                name = tool["function"]["name"]
                if name == "submit_claim_match":
                    block_ids = tool["function"]["parameters"]["properties"][
                        "matched_block_id"
                    ]["enum"]
                    matched = "block_1" if "block_1" in block_ids else "block_2"
                    arguments = {
                        "claim_id": "claim_a",
                        "verdict": "expressed",
                        "matched_block_id": matched,
                    }
                elif name == "submit_claim_contradiction":
                    arguments = {
                        "claim_id": "claim_a",
                        "contradicted": False,
                        "contradicting_block_id": "",
                    }
                else:
                    arguments = {
                        "block_id": "block_2",
                        "verdict": "supported",
                        "issues": [],
                    }
                return [{"name": name, "arguments": arguments}]

        verdict = ClaimEvidenceJudge(Model()).judge({
            "required_claims": [{"claim_id": "claim_a", "statement": "Claim A."}],
            "answer_blocks": [{
                "block_id": "block_1",
                "text": "Claim A.",
                "evidence": [],
            }, {
                "block_id": "block_2",
                "text": "Claim A.",
                "evidence": [{"span_text": "Claim A."}],
            }],
        })

        self.assertEqual(["block_2"], verdict.claims[0]["matched_block_ids"])

    def test_exact_required_only_answer_has_no_additional_material(self) -> None:
        class Model:
            def complete_judgment(self, _messages, tool, _max_tokens):
                name = tool["function"]["name"]
                if name == "submit_claim_contradiction":
                    return [{
                        "name": name,
                        "arguments": {
                            "claim_id": "claim_a",
                            "contradicted": False,
                            "contradicting_block_id": "",
                        },
                    }]
                if name != "submit_claim_match":
                    raise AssertionError("exact required-only answer should not need another call")
                return [{
                    "name": name,
                    "arguments": {
                        "claim_id": "claim_a",
                        "verdict": "expressed",
                        "matched_block_id": "block_1",
                    },
                }]

        verdict = ClaimEvidenceJudge(Model()).judge({
            "required_claims": [{"claim_id": "claim_a", "statement": "Claim A."}],
            "answer_blocks": [{"block_id": "block_1", "text": "Claim A.", "evidence": []}],
        })

        self.assertEqual("pass", verdict.additional_claims["verdict"])

    def test_claim_judge_retries_one_missing_tool_call(self) -> None:
        class Model:
            call_count = 0

            def complete_judgment(self, _messages, tool, _max_tokens):
                self.call_count += 1
                if self.call_count == 1:
                    return []
                name = tool["function"]["name"]
                if name == "submit_claim_contradiction":
                    return [{
                        "name": name,
                        "arguments": {
                            "claim_id": "claim_a",
                            "contradicted": False,
                            "contradicting_block_id": "",
                        },
                    }]
                return [{
                    "name": name,
                    "arguments": {
                        "claim_id": "claim_a",
                        "verdict": "expressed",
                        "matched_block_id": "block_1",
                    },
                }]

        model = Model()
        verdict = ClaimEvidenceJudge(model).judge({
            "required_claims": [{"claim_id": "claim_a", "statement": "Claim A."}],
            "answer_blocks": [{"block_id": "block_1", "text": "Claim A.", "evidence": []}],
        })

        self.assertEqual("expressed", verdict.claims[0]["verdict"])
        self.assertEqual(4, model.call_count)

    def test_claim_candidate_requires_isolated_completeness_verification(self) -> None:
        class Model:
            def complete_judgment(self, messages, tool, _max_tokens):
                name = tool["function"]["name"]
                if name == "submit_claim_contradiction":
                    arguments = {
                        "claim_id": "claim_a",
                        "contradicted": False,
                        "contradicting_block_id": "",
                    }
                elif str(messages[0]["content"]).startswith(
                    "CLAIM_COMPLETENESS_VERIFIER"
                ):
                    arguments = {
                        "claim_id": "claim_a",
                        "verdict": "missing",
                        "matched_block_id": "",
                    }
                else:
                    arguments = {
                        "claim_id": "claim_a",
                        "verdict": "expressed",
                        "matched_block_id": "block_1",
                    }
                return [{"name": name, "arguments": arguments}]

        verdict = ClaimEvidenceJudge(Model()).judge({
            "required_claims": [{
                "claim_id": "claim_a",
                "statement": "Adam uses beta1, beta2, and epsilon.",
            }],
            "answer_blocks": [{
                "block_id": "block_1",
                "kind": "table_row",
                "text": "Optimizer | Adam",
                "evidence": [],
            }],
        })

        self.assertEqual("missing", verdict.claims[0]["verdict"])
        self.assertEqual([], verdict.claims[0]["matched_block_ids"])

    def test_committed_claim_judge_labels_are_valid_and_can_pass_exact_judgments(self) -> None:
        for path, expected_count in (
            ("research/golden-data/human-labels-claim-judge-calibration.yaml", 7),
            ("research/golden-data/human-labels-claim-judge-holdout.yaml", 7),
            ("research/golden-data/human-labels-claim-judge-holdout-v9.yaml", 7),
            ("research/golden-data/human-labels-claim-judge-holdout-v14d.yaml", 7),
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

    def test_calibration_does_not_call_an_alternate_expressed_block_a_false_pass(self) -> None:
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

        self.assertTrue(report["accepted"])
        self.assertEqual(0, report["false_pass_count"])

    def test_calibration_rejects_an_unexpected_expressed_claim_as_false_pass(self) -> None:
        label_set = load_claim_judge_labels(
            "research/golden-data/human-labels-claim-judge-calibration.yaml"
        )
        judge = _LabelJudge(label_set)
        judge.claim_overrides = {
            "webarena_reproduction_protocol_001": {
                "webarena_functional_correctness": ["block_17"],
            },
        }
        judge.verdict_overrides = {
            "webarena_reproduction_protocol_001": {
                "webarena_functional_correctness": "expressed",
            },
        }
        judge.additional_overrides = {
            "webarena_reproduction_protocol_001": "pass",
        }

        report = evaluate_claim_judge_calibration(label_set, judge)

        self.assertFalse(report["accepted"])
        self.assertEqual(1, report["false_pass_count"])


class _LabelJudge:
    def __init__(self, label_set) -> None:
        self.labels = {label.case_id: label for label in label_set.labels}
        self.claim_overrides = {}
        self.verdict_overrides = {}
        self.additional_overrides = {}

    def judge(self, packet):
        label = self.labels[str(packet["case_id"])]
        overrides = self.claim_overrides.get(label.case_id, {})
        verdicts = self.verdict_overrides.get(label.case_id, {})
        claims = []
        for claim_id, expected in label.expected_claims.items():
            matched = overrides.get(claim_id, expected["matched_block_ids"])
            claims.append({
                "claim_id": claim_id,
                "verdict": verdicts.get(claim_id, expected["verdict"]),
                "matched_block_ids": matched,
            })
        additional = self.additional_overrides.get(label.case_id, label.expected_additional)
        return ClaimEvidenceJudgeVerdict(claims, {
            "verdict": additional,
            "grounding_issues": (
                ["Human-labelled unsupported addition."]
                if additional == "fail"
                else []
            ),
        })


if __name__ == "__main__":
    unittest.main()
