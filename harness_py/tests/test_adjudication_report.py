from __future__ import annotations

import json
import unittest
from pathlib import Path

from harness_py.evaluation.adjudication_report import (
    build_adjudication_report,
    load_adjudication_report,
    render_adjudication_markdown,
)


class AdjudicationReportTest(unittest.TestCase):
    def test_report_separates_conformance_from_human_semantic_quality(self) -> None:
        report = build_adjudication_report(
            labels_document={
                "schema_version": "paperloom-blind-answer-labels/v1",
                "labels": [
                    {
                        "case_id": "case_one",
                        "answer_a": _answer("pass", "pass", "pass", "pass", "A is correct."),
                        "answer_b": _answer("fail", "fail", "pass", "fail", "B should clarify."),
                        "preferred": "A",
                    },
                    {
                        "case_id": "case_two",
                        "answer_a": _answer(
                            "pass", "pass", "not_applicable", "pass", "A answers directly."
                        ),
                        "answer_b": _answer(
                            "pass", "pass", "fail", "fail", "B overstates the evidence."
                        ),
                        "preferred": "tie",
                    },
                ],
            },
            blind_map={
                "case_one": {"A": "model-alpha", "B": "model-beta"},
                "case_two": {"A": "model-beta", "B": "model-alpha"},
            },
            score_reports={
                "model-alpha": _score_report({"case_one": False, "case_two": True}),
                "model-beta": _score_report({"case_one": False, "case_two": True}),
            },
            input_paths={
                "labels": "labels.yaml",
                "blind_map": "blind-map.json",
                "score_reports": {
                    "model-alpha": "alpha-score.json",
                    "model-beta": "beta-score.json",
                },
            },
        )

        self.assertEqual("paperloom-human-adjudication-report/v1", report["schema_version"])
        self.assertEqual("hard_pass", report["metric_definitions"]["contract_anchor_conformance"]["source_field"])

        alpha = report["models"]["model-alpha"]
        self.assertEqual(1, alpha["contract_anchor_conformance"]["passed_count"])
        self.assertEqual(1, alpha["semantic_quality"]["overall"]["passed_count"])
        self.assertEqual(1, alpha["semantic_quality"]["grounding"]["failed_count"])
        self.assertEqual(1, alpha["preferences"]["preferred_count"])
        self.assertEqual(
            {
                "true_positive": 0,
                "false_negative": 1,
                "false_positive": 1,
                "true_negative": 0,
                "agreement_count": 0,
                "agreement_rate": 0.0,
            },
            alpha["scorer_confusion"],
        )

        beta = report["models"]["model-beta"]
        self.assertEqual(1, beta["contract_anchor_conformance"]["passed_count"])
        self.assertEqual(1, beta["semantic_quality"]["overall"]["passed_count"])
        self.assertEqual(1, beta["semantic_quality"]["grounding"]["not_applicable_count"])
        self.assertEqual(2, beta["scorer_confusion"]["agreement_count"])

        self.assertEqual(
            {
                "both_passed": 0,
                "both_failed": 0,
                "only_passed": {"model-alpha": 1, "model-beta": 1},
            },
            report["pairwise_overall"],
        )
        self.assertEqual({"by_model": {"model-alpha": 1, "model-beta": 0}, "tie_count": 1}, report["preferences"])

        markdown = render_adjudication_markdown(report)
        self.assertIn("合同/锚点一致性", markdown)
        self.assertIn("人工语义质量", markdown)
        self.assertIn("model-alpha", markdown)
        self.assertNotIn("Hard Pass 准确率", markdown)

    def test_report_rejects_case_mismatch(self) -> None:
        with self.assertRaisesRegex(ValueError, "case sets differ"):
            build_adjudication_report(
                labels_document={
                    "schema_version": "paperloom-blind-answer-labels/v1",
                    "labels": [{
                        "case_id": "case_one",
                        "answer_a": _answer("pass", "pass", "pass", "pass", "ok"),
                        "answer_b": _answer("pass", "pass", "pass", "pass", "ok"),
                        "preferred": "tie",
                    }],
                },
                blind_map={"different_case": {"A": "one", "B": "two"}},
                score_reports={},
            )

    def test_frozen_thirty_case_report_matches_the_committed_result(self) -> None:
        repo_root = Path(__file__).resolve().parents[2]
        review_root = repo_root / "research/golden-data/human-adjudication/2026-07-14"
        run_root = (
            repo_root
            / "research/golden-data/validation-runs/2026-07-14-single-path-model-comparison-v1"
        )
        report = load_adjudication_report(
            labels_path=review_root / "answer-labels-template.yaml",
            blind_map_path=review_root / "blind-map.json",
            score_report_paths={
                "gpt-5.5": run_root / "gpt-5.5/score_report.json",
                "minimax-m3": run_root / "minimax-m3/score_report.json",
            },
            display_paths_relative_to=repo_root,
        )

        self.assertEqual(30, report["case_count"])
        self.assertEqual(28, report["models"]["gpt-5.5"]["semantic_quality"]["overall"]["passed_count"])
        self.assertEqual(22, report["models"]["minimax-m3"]["semantic_quality"]["overall"]["passed_count"])
        self.assertEqual(14, report["models"]["gpt-5.5"]["scorer_confusion"]["false_negative"])
        self.assertEqual(8, report["models"]["minimax-m3"]["scorer_confusion"]["false_negative"])
        self.assertEqual(3, report["models"]["minimax-m3"]["scorer_confusion"]["false_positive"])
        self.assertEqual(
            {"by_model": {"gpt-5.5": 24, "minimax-m3": 4}, "tie_count": 2},
            report["preferences"],
        )
        self.assertEqual(2, len(report["models"]["gpt-5.5"]["genuine_failures"]))
        self.assertEqual(8, len(report["models"]["minimax-m3"]["genuine_failures"]))

        committed = review_root / "adjudication-report.json"
        if committed.exists():
            self.assertEqual(
                json.loads(committed.read_text(encoding="utf-8")),
                report,
            )


def _answer(
    decision: str,
    task_fulfillment: str,
    grounding: str,
    overall: str,
    note: str,
) -> dict[str, str]:
    return {
        "decision": decision,
        "task_fulfillment": task_fulfillment,
        "grounding": grounding,
        "overall": overall,
        "note": note,
    }


def _score_report(hard_pass_by_case: dict[str, bool]) -> dict[str, object]:
    passed = sum(hard_pass_by_case.values())
    return {
        "schema_version": "harness-score-report/v2",
        "case_count": len(hard_pass_by_case),
        "passed_count": passed,
        "failed_count": len(hard_pass_by_case) - passed,
        "scores": [
            {"case_id": case_id, "hard_pass": hard_pass}
            for case_id, hard_pass in hard_pass_by_case.items()
        ],
    }


if __name__ == "__main__":
    unittest.main()
