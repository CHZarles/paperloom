from __future__ import annotations

import unittest
from copy import deepcopy

from harness_py.dataset import load_dataset
from harness_py.golden_fixture import GoldenFixtureHarness
from harness_py.scoring import BehaviorScorer


class GoldenV2Test(unittest.TestCase):
    def setUp(self) -> None:
        self.dataset = load_dataset("research/golden-data/manifest.yaml")

    def test_committed_dataset_is_v2_and_has_twelve_cases(self) -> None:
        self.assertEqual("harness-golden-data/v2", self.dataset.manifest["schema_version"])
        self.assertEqual(12, len(self.dataset.cases))
        self.assertEqual(5, len(self.dataset.paper_records_by_id))
        self.assertEqual(7, len(self.dataset.anchors_by_id))
        self.assertEqual(5, len(self.dataset.reading_models_by_paper_id))
        for case in self.dataset.cases:
            self.assertEqual("harness-golden-case/v2", case["schema_version"])
            self.assertEqual("user", case["messages"][-1]["role"])
            for removed in (
                "question",
                "expected_intent",
                "expected_retrieval_plan",
                "gold_evidence",
                "gold_claims",
                "answer_contract",
                "required_trace",
                "compatibility_projection",
            ):
                self.assertNotIn(removed, case)

    def test_committed_dataset_has_three_history_snapshots(self) -> None:
        history_cases = [case for case in self.dataset.cases if len(case["messages"]) > 1]
        self.assertEqual(3, len(history_cases))
        self.assertEqual(12, len(self.dataset.cases))

    def test_history_snapshot_becomes_live_conversation_context(self) -> None:
        from harness_py.golden_case import case_question, conversation_state_for_case

        case = next(case for case in self.dataset.cases if case["id"] == "bert_choice_followup_001")
        state = conversation_state_for_case(self.dataset, case)

        self.assertEqual("The second.", case_question(case))
        self.assertEqual(1, state.turn_index)
        self.assertEqual(2, len(state.message_history))
        self.assertEqual("assistant", state.message_history[-1]["role"])
        self.assertIn("BERT", state.message_history[-1]["summary"])

    def test_fixture_passes_all_committed_cases(self) -> None:
        runs = [GoldenFixtureHarness().run_case(self.dataset, case) for case in self.dataset.cases]
        report = BehaviorScorer().score_dataset(self.dataset, runs)

        self.assertEqual(12, report["case_count"])
        self.assertEqual(0, report["failed_count"], report)
        self.assertTrue(all(score["hard_pass"] for score in report["scores"]))

    def test_committed_reading_models_use_the_authored_pack_data_directory(self) -> None:
        pack = self.dataset.paper_packs[0]
        self.assertEqual("transformer-bert-gpt", pack["data_dir"])
        expected_paths = {
            paper_id: f"data/golden/transformer-bert-gpt/reading-models/{paper_id}.reading-model.json"
            for paper_id in self.dataset.paper_records_by_id
        }

        self.assertEqual(
            expected_paths,
            {
                paper_id: record["source_assets"]["reading_model_path"]
                for paper_id, record in self.dataset.paper_records_by_id.items()
            },
        )
        self.assertEqual(set(expected_paths), set(self.dataset.reading_models_by_paper_id))
        self.assertEqual([], self.dataset.load_warnings)

    def test_scorer_rejects_a_missing_required_anchor(self) -> None:
        case = next(case for case in self.dataset.cases if case["id"] == "transformer_adam_params_001")
        run = GoldenFixtureHarness().run_case(self.dataset, case)
        broken = deepcopy(run)
        broken["evidence_ledger"]["items"] = []

        score = BehaviorScorer().score_case(self.dataset, case, broken)

        self.assertFalse(score.hard_pass)
        self.assertIn(
            "REQUIRED_ANCHOR_MISSING:transformer_adam_training_params_span",
            score.dimensions["retrieval"].errors,
        )

    def test_scorer_rejects_a_forged_anchor_paper_pairing(self) -> None:
        source_case = next(
            case for case in self.dataset.cases if case["id"] == "transformer_adam_params_001"
        )
        run = GoldenFixtureHarness().run_case(self.dataset, source_case)
        case = deepcopy(source_case)
        case["expect"]["papers"] = {}
        case["expect"]["evidence"] = {}
        case["expect"]["citations"] = "optional"
        broken = deepcopy(run)
        broken["evidence_ledger"]["items"][0]["paper_id"] = "bert_2018"
        broken["research_answer"]["cited_evidence_ids"] = []
        broken["research_answer"]["cited_claim_ids"] = []
        broken["claim_graph"]["claims"] = []

        score = BehaviorScorer().score_case(self.dataset, case, broken)

        error = (
            "MATCHED_ANCHOR_PAPER_MISMATCH:transformer_adam_training_params_span:"
            "expected=attention_is_all_you_need_2017:actual=bert_2018"
        )
        self.assertFalse(score.hard_pass)
        self.assertIn(error, score.dimensions["retrieval"].errors)
        self.assertIn(error, score.dimensions["grounding"].errors)

    def test_scorer_rejects_a_wrong_structured_fact(self) -> None:
        case = next(case for case in self.dataset.cases if case["id"] == "transformer_adam_params_001")
        run = GoldenFixtureHarness().run_case(self.dataset, case)
        broken = deepcopy(run)
        broken["research_answer"]["fields"]["beta2"] = "0.999"

        score = BehaviorScorer().score_case(self.dataset, case, broken)

        self.assertFalse(score.hard_pass)
        self.assertIn("FACT_MISMATCH:beta2", score.dimensions["content"].errors)

    def test_scorer_rejects_a_missing_structured_fact_with_matching_nested_value(self) -> None:
        case = next(case for case in self.dataset.cases if case["id"] == "transformer_adam_params_001")
        run = GoldenFixtureHarness().run_case(self.dataset, case)
        broken = deepcopy(run)
        fields = broken["research_answer"]["fields"]
        fields.pop("beta2")
        fields["notes"] = "0.98"

        score = BehaviorScorer().score_case(self.dataset, case, broken)

        self.assertFalse(score.hard_pass)
        self.assertIn("FACT_MISSING:beta2", score.dimensions["content"].errors)

    def test_scorer_does_not_grade_internal_paradigm_or_stage_order(self) -> None:
        case = next(case for case in self.dataset.cases if case["id"] == "transformer_adam_params_001")
        run = GoldenFixtureHarness().run_case(self.dataset, case)
        run["intent_frame"]["primary_paradigm"] = "deep_comparison"
        run["stage_trace"] = list(reversed(run.get("stage_trace", [])))

        score = BehaviorScorer().score_case(self.dataset, case, run)

        self.assertTrue(score.hard_pass, score.to_dict())

    def test_scorer_does_not_grade_runtime_claim_ids(self) -> None:
        case = next(case for case in self.dataset.cases if case["id"] == "transformer_adam_params_001")
        run = GoldenFixtureHarness().run_case(self.dataset, case)
        run["research_answer"]["cited_claim_ids"] = ["runtime_claim_42"]

        score = BehaviorScorer().score_case(self.dataset, case, run)

        self.assertTrue(score.hard_pass, score.to_dict())

    def test_scorer_does_not_grade_outcome_reason_prose(self) -> None:
        source_case = next(
            case for case in self.dataset.cases if case["id"] == "transformer_adam_params_001"
        )
        case = deepcopy(source_case)
        case["expect"]["reason"] = "The answer is complete."
        run = GoldenFixtureHarness().run_case(self.dataset, source_case)
        broken = deepcopy(run)
        broken["research_answer"]["outcome_reason"] = (
            "The answer is complete because the evidence was sufficient."
        )

        score = BehaviorScorer().score_case(self.dataset, case, broken)

        self.assertTrue(score.hard_pass, score.to_dict())
        self.assertEqual("pass", score.dimensions["outcome"].status)


if __name__ == "__main__":
    unittest.main()
