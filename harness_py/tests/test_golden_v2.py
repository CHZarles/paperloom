from __future__ import annotations

import json
import unittest
from copy import deepcopy
from dataclasses import replace
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

from harness_py.dataset import load_dataset
from harness_py.golden_fixture import GoldenFixtureHarness
from harness_py.models import GoldenDataset
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

    def test_all_authored_anchors_are_locatable_in_reading_models(self) -> None:
        from harness_py.audit import audit_dataset

        report = audit_dataset(self.dataset)

        self.assertEqual(7, report["anchor_count"])
        self.assertEqual(0, report["failed_count"], report)
        self.assertTrue(all(item["status"] == "pass" for item in report["anchors"]))
        self.assertTrue(all(len(item["matched_location_refs"]) == 1 for item in report["anchors"]), report)

    def test_runtime_anchor_matcher_rejects_partial_overlap_unrelated_passage(self) -> None:
        from harness_py.tools import ReadingDocument, _match_anchor

        anchor = self.dataset.anchors_by_id["transformer_adam_training_params_span"]
        document = ReadingDocument(
            paper_id="attention_is_all_you_need_2017",
            title="Attention Is All You Need",
            paper_version="fixture",
            location_ref="unrelated_page_7_passage",
            element_type="paragraph",
            page=7,
            section="Unrelated discussion",
            text="The Adam optimizer training parameters differ across experiments.",
            source_kind="reading_element",
        )

        self.assertIsNone(_match_anchor(document, [anchor]))

    def test_runtime_anchor_matcher_requires_exact_quote_on_the_authored_page(self) -> None:
        from harness_py.tools import ReadingDocument, _match_anchor

        anchor = self.dataset.anchors_by_id["transformer_adam_training_params_span"]
        quote = anchor["selector"]["exact_text"]

        def document(page, text):
            return ReadingDocument(
                paper_id="attention_is_all_you_need_2017",
                title="Attention Is All You Need",
                paper_version="fixture",
                location_ref=f"page_{page}",
                element_type="paragraph",
                page=page,
                section="5.3 Optimizer",
                text=text,
                source_kind="reading_element",
            )

        self.assertIsNone(_match_anchor(document(None, quote), [anchor]))
        self.assertIsNone(_match_anchor(document(6, quote), [anchor]))
        self.assertEqual(
            "transformer_adam_training_params_span",
            _match_anchor(document(7, f"Setup: {quote.upper()}."), [anchor]),
        )

    def test_committed_runtime_anchor_tags_are_exact_and_page_constrained(self) -> None:
        from harness_py.tools import ReadingCorpusTools, _normalize

        tagged: dict[str, list[str]] = {}
        for document in ReadingCorpusTools(self.dataset).documents:
            if not document.matched_anchor_id:
                continue
            anchor = self.dataset.anchors_by_id[document.matched_anchor_id]
            anchor_page = anchor["element"]["page"]
            normalized_quote = _normalize(anchor["selector"]["exact_text"])
            normalized_text = _normalize(document.text)

            self.assertIsNotNone(document.page, document.location_ref)
            self.assertEqual(str(anchor_page), str(document.page), document.location_ref)
            self.assertIn(
                f" {normalized_quote} ",
                f" {normalized_text} ",
                document.location_ref,
            )
            tagged.setdefault(document.matched_anchor_id, []).append(document.location_ref)

        self.assertEqual(set(self.dataset.anchors_by_id), set(tagged), tagged)
        self.assertTrue(all(len(location_refs) == 1 for location_refs in tagged.values()), tagged)

    def test_audit_reports_ambiguous_when_multiple_elements_match_the_same_anchor(self) -> None:
        from harness_py.audit import audit_dataset

        dataset = self._dataset_with_anchor_quote(
            "bert_masked_lm_pretraining",
            "masked LM",
        )

        report = audit_dataset(dataset)
        anchor = self._anchor_report(report, "bert_masked_lm_pretraining")

        self.assertEqual(1, report["failed_count"], report)
        self.assertEqual("ambiguous", anchor["status"])
        self.assertEqual(2, len(anchor["matched_location_refs"]))

    def test_audit_reports_not_found_when_page_constrained_match_has_no_page(self) -> None:
        from harness_py.audit import audit_dataset

        dataset = self._dataset_with_reading_element(
            "attention_is_all_you_need_2017",
            "reading_element_260d1b8af78b454dbe0f610483436730",
            pageNumber=None,
        )

        report = audit_dataset(dataset)
        anchor = self._anchor_report(report, "transformer_adam_training_params_span")

        self.assertEqual(1, report["failed_count"], report)
        self.assertEqual("not_found", anchor["status"])
        self.assertEqual([], anchor["matched_location_refs"])

    def test_audit_reports_not_found_when_matching_element_has_no_location_ref(self) -> None:
        from harness_py.audit import audit_dataset

        dataset = self._dataset_with_reading_element(
            "attention_is_all_you_need_2017",
            "reading_element_260d1b8af78b454dbe0f610483436730",
            locationRef=None,
            readingElementId=None,
            id=None,
        )

        report = audit_dataset(dataset)
        anchor = self._anchor_report(report, "transformer_adam_training_params_span")

        self.assertEqual(1, report["failed_count"], report)
        self.assertEqual("not_found", anchor["status"])
        self.assertEqual([], anchor["matched_location_refs"])
        self.assertNotIn("None", anchor["matched_location_refs"])

    def test_cli_audit_returns_nonzero_and_writes_report_when_failures_exist(self) -> None:
        from harness_py.cli import main

        report = {
            "schema_version": "harness-anchor-audit/v1",
            "dataset_id": "fixture",
            "anchor_count": 1,
            "passed_count": 0,
            "failed_count": 1,
            "anchors": [{
                "anchor_id": "broken_anchor",
                "paper_id": "paper_1",
                "page": 1,
                "status": "not_found",
                "matched_location_refs": [],
            }],
        }

        with TemporaryDirectory() as tmp:
            output = Path(tmp) / "audit.json"
            with patch("harness_py.cli.load_dataset", return_value=self.dataset), patch(
                "harness_py.cli.audit_dataset",
                return_value=report,
            ), patch("builtins.print"):
                code = main(["--manifest", "research/golden-data/manifest.yaml", "audit", "--out", str(output)])

            self.assertEqual(1, code)
            self.assertEqual(report, json.loads(output.read_text(encoding="utf-8")))

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

    def _anchor_report(self, report: dict, anchor_id: str) -> dict:
        return next(item for item in report["anchors"] if item["anchor_id"] == anchor_id)

    def _dataset_with_anchor_quote(self, anchor_id: str, quote: str) -> GoldenDataset:
        anchors = deepcopy(self.dataset.anchors_by_id)
        anchors[anchor_id]["selector"]["exact_text"] = quote
        return replace(self.dataset, anchors_by_id=anchors)

    def _dataset_with_reading_element(
        self,
        paper_id: str,
        reading_element_id: str,
        **updates,
    ) -> GoldenDataset:
        reading_models = deepcopy(self.dataset.reading_models_by_paper_id)
        elements = reading_models[paper_id]["reading_elements"]
        for item in elements:
            if item.get("readingElementId") == reading_element_id:
                item.update(updates)
                break
        else:
            raise AssertionError(f"missing reading element {reading_element_id}")
        return replace(self.dataset, reading_models_by_paper_id=reading_models)


if __name__ == "__main__":
    unittest.main()
