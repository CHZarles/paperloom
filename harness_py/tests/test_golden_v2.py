from __future__ import annotations

import hashlib
import io
import json
import unittest
from contextlib import redirect_stdout
from copy import deepcopy
from dataclasses import replace
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

import yaml

from harness_py.cli import main
from harness_py.core.models import GoldenDataset
from harness_py.corpus.tools import ReadingCorpusTools
from harness_py.evaluation.dataset import load_dataset
from harness_py.evaluation.golden_case import paper_ids_for_case
from harness_py.evaluation.golden_fixture import GoldenFixtureHarness
from harness_py.evaluation.scoring import BehaviorScorer
from harness_py.orchestration.live_chat import _dataset_for_scope
from harness_py.orchestration.research_contract import research_agent_instructions
from harness_py.orchestration.research_skills import ResearchSkillRegistry


class GoldenV2Test(unittest.TestCase):
    def setUp(self) -> None:
        self.dataset = load_dataset("research/golden-data/manifest.yaml")

    def test_committed_dataset_is_v2_and_has_ten_retrieval_cases(self) -> None:
        self.assertEqual("harness-golden-data/v2", self.dataset.manifest["schema_version"])
        self.assertEqual(10, len(self.dataset.cases))
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

    def test_cli_defaults_to_the_stable_manifest(self) -> None:
        with patch("harness_py.cli.load_dataset", wraps=load_dataset) as loader:
            with redirect_stdout(io.StringIO()):
                code = main(["validate"])

        self.assertEqual(0, code)
        loader.assert_called_once_with("research/golden-data/manifest.yaml")

    def test_loader_rejects_a_v1_manifest(self) -> None:
        with TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "manifest.yaml"
            manifest.write_text(
                "schema_version: harness-golden-data/v1\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ValueError, "unsupported manifest schema"):
                load_dataset(manifest)

    def test_loader_rejects_a_manifest_without_dataset_identity(self) -> None:
        with TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "manifest.yaml"
            manifest.write_text(
                "schema_version: harness-golden-data/v2\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ValueError, "dataset_id"):
                load_dataset(manifest)

    def test_loader_rejects_authoring_files_outside_the_golden_root(self) -> None:
        with TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "manifest.yaml"
            manifest.write_text(
                "schema_version: harness-golden-data/v2\n"
                "dataset_id: fixture\n"
                "paper_packs: [../pack.yaml]\n"
                "case_files: []\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ValueError, "stay inside"):
                load_dataset(manifest)

    def test_expanded_dataset_is_an_isolated_superset_of_the_stable_dataset(self) -> None:
        expanded = load_dataset("research/golden-data/manifest-expanded.yaml")
        stable_pack_id = str(self.dataset.paper_packs[0]["id"])
        stable_case_ids = [str(case["id"]) for case in self.dataset.cases]
        expanded_cases_by_id = {
            str(case["id"]): case
            for case in expanded.cases
        }

        self.assertNotEqual(
            self.dataset.manifest["dataset_id"],
            expanded.manifest["dataset_id"],
        )
        for labels_path in (
            "research/golden-data/human-labels-llm-agent-evaluation.yaml",
            "research/golden-data/human-labels-llm-agent-evaluation-holdout.yaml",
        ):
            labels = yaml.safe_load(Path(labels_path).read_text(encoding="utf-8"))
            self.assertEqual(expanded.manifest["dataset_id"], labels["dataset_id"])
        self.assertEqual(
            self.dataset.paper_packs[0],
            next(pack for pack in expanded.paper_packs if pack["id"] == stable_pack_id),
        )
        self.assertEqual(
            self.dataset.cases,
            [expanded_cases_by_id[case_id] for case_id in stable_case_ids],
        )
        self.assertEqual(
            self.dataset.paper_records_by_id,
            {
                paper_id: expanded.paper_records_by_id[paper_id]
                for paper_id in self.dataset.paper_records_by_id
            },
        )
        self.assertEqual(
            self.dataset.reading_models_by_paper_id,
            {
                paper_id: expanded.reading_models_by_paper_id[paper_id]
                for paper_id in self.dataset.reading_models_by_paper_id
            },
        )
        self.assertEqual(
            self.dataset.anchors_by_id,
            {
                anchor_id: expanded.anchors_by_id[anchor_id]
                for anchor_id in self.dataset.anchors_by_id
            },
        )
        stable_scope = paper_ids_for_case(self.dataset, self.dataset.cases[0])
        stable_runtime_dataset = _dataset_for_scope(self.dataset, stable_scope)
        expanded_runtime_dataset = _dataset_for_scope(expanded, stable_scope)
        self.assertEqual(
            stable_runtime_dataset.paper_records_by_id,
            expanded_runtime_dataset.paper_records_by_id,
        )
        self.assertEqual(
            stable_runtime_dataset.reading_models_by_paper_id,
            expanded_runtime_dataset.reading_models_by_paper_id,
        )
        self.assertEqual(
            stable_runtime_dataset.citation_edges,
            expanded_runtime_dataset.citation_edges,
        )
        fixture = GoldenFixtureHarness()
        for stable_case in self.dataset.cases:
            expanded_case = expanded_cases_by_id[str(stable_case["id"])]
            self.assertEqual(
                paper_ids_for_case(self.dataset, stable_case),
                paper_ids_for_case(expanded, expanded_case),
            )
            stable_run = fixture.run_case(self.dataset, stable_case)
            expanded_run = fixture.run_case(expanded, expanded_case)
            for timestamp_field in ("started_at", "completed_at"):
                stable_run.pop(timestamp_field)
                expanded_run.pop(timestamp_field)
            self.assertEqual(stable_run, expanded_run)

    def test_active_manifests_only_include_cases_that_exercise_retrieval(self) -> None:
        expanded = load_dataset("research/golden-data/manifest-expanded.yaml")

        self.assertEqual(10, len(self.dataset.cases))
        self.assertEqual(24, len(expanded.cases))
        for case in expanded.cases:
            required = case.get("expect", {}).get("evidence", {}).get("required", [])
            self.assertTrue(required, case["id"])

    def test_expanded_dataset_does_not_change_the_harness_contract(self) -> None:
        expanded = load_dataset("research/golden-data/manifest-expanded.yaml")
        instructions = research_agent_instructions(ResearchSkillRegistry())

        self.assertEqual(
            "03b8316148bacf5fdc44254038cdb1260c5db716d79f4c8b79f862ccf379fa0a",
            hashlib.sha256(instructions.encode("utf-8")).hexdigest(),
            "expanded Golden Data must not change the established agent prompt",
        )
        self.assertEqual(
            ReadingCorpusTools(self.dataset).definitions(),
            ReadingCorpusTools(expanded).definitions(),
            "expanded Golden Data must not change model-visible corpus tools",
        )

    def test_committed_dataset_has_three_history_snapshots(self) -> None:
        history_cases = [case for case in self.dataset.cases if len(case["messages"]) > 1]
        self.assertEqual(3, len(history_cases))
        self.assertEqual(10, len(self.dataset.cases))

    def test_history_snapshot_becomes_live_conversation_context(self) -> None:
        from harness_py.evaluation.golden_case import case_question, conversation_state_for_case

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

        self.assertEqual(10, report["case_count"])
        self.assertEqual(0, report["failed_count"], report)
        self.assertTrue(all(score["hard_pass"] for score in report["scores"]))

    def test_committed_reading_models_use_the_authored_pack_data_directory(self) -> None:
        pack = self.dataset.paper_packs[0]
        self.assertEqual("corpora/transformer-bert-gpt", pack["data_dir"])
        expected_paths = {
            paper_id: (
                "research/golden-data/corpora/transformer-bert-gpt/reading-models/"
                f"{paper_id}.reading-model.json"
            )
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

    def test_all_golden_corpora_resolve_below_the_single_management_root(self) -> None:
        expanded = load_dataset("research/golden-data/manifest-expanded.yaml")

        self.assertTrue(all(
            str(pack["data_dir"]).startswith("corpora/")
            for pack in expanded.paper_packs
        ))
        self.assertTrue(all(
            str(record["source_assets"]["reading_model_path"]).startswith(
                "research/golden-data/corpora/"
            )
            for record in expanded.paper_records_by_id.values()
        ))
        self.assertFalse(any(
            "data/golden" in str(record["source_assets"]["reading_model_path"])
            for record in expanded.paper_records_by_id.values()
        ))

    def test_pack_data_directory_cannot_escape_the_golden_management_root(self) -> None:
        from harness_py.evaluation.paths import resolve_pack_data_dir

        with TemporaryDirectory() as directory:
            manifest = Path(directory) / "manifest.yaml"
            manifest.write_text("dataset_id: fixture\n", encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "stay inside"):
                resolve_pack_data_dir(manifest, "../outside")

    def test_all_authored_anchors_are_locatable_in_reading_models(self) -> None:
        from harness_py.evaluation.audit import audit_dataset

        report = audit_dataset(self.dataset)

        self.assertEqual(7, report["anchor_count"])
        self.assertEqual(0, report["failed_count"], report)
        self.assertTrue(all(item["status"] == "pass" for item in report["anchors"]))
        self.assertTrue(all(len(item["matched_location_refs"]) == 1 for item in report["anchors"]), report)

    def test_anchor_normalization_ignores_parser_inline_markup(self) -> None:
        from harness_py.corpus.pages import contains_normalized_phrase, normalize_text

        text = normalize_text("Using <sup>GAIA</sup>’s methodology, we devise 466 questions")
        quote = normalize_text("Using GAIA's methodology, we devise 466 questions")

        self.assertTrue(contains_normalized_phrase(text, quote))

    def test_anchor_normalization_repairs_parser_line_end_word_splits(self) -> None:
        from harness_py.corpus.pages import contains_normalized_phrase, normalize_text

        text = normalize_text("2,294 software engi- neering problems from GitHub")
        quote = normalize_text("2,294 software engineering problems from GitHub")

        self.assertTrue(contains_normalized_phrase(text, quote))

    def test_runtime_anchor_matcher_rejects_partial_overlap_unrelated_passage(self) -> None:
        from harness_py.corpus.tools import ReadingDocument, _match_anchors

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

        self.assertEqual((), _match_anchors(document, [anchor]))

    def test_runtime_anchor_matcher_requires_exact_quote_on_the_authored_page(self) -> None:
        from harness_py.corpus.tools import ReadingDocument, _match_anchors

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

        self.assertEqual((), _match_anchors(document(None, quote), [anchor]))
        self.assertEqual((), _match_anchors(document(6, quote), [anchor]))
        self.assertEqual(
            ("transformer_adam_training_params_span",),
            _match_anchors(document(7, f"Setup: {quote.upper()}."), [anchor]),
        )

    def test_runtime_anchor_matcher_keeps_multiple_anchors_in_one_element(self) -> None:
        from harness_py.corpus.tools import ReadingDocument, _match_anchors

        anchors = [{
            "anchor_id": anchor_id,
            "element": {"page": 1},
            "selector": {"exact_text": quote},
        } for anchor_id, quote in [
            ("summary_limit", "focus on the final success rate"),
            ("progress_metric", "fine-grained progress rate metric"),
        ]]
        document = ReadingDocument(
            paper_id="agentboard_2024",
            title="AgentBoard",
            paper_version="fixture",
            location_ref="agentboard_abstract",
            element_type="paragraph",
            page=1,
            section="Abstract",
            text=(
                "Current frameworks focus on the final success rate. "
                "AgentBoard offers a fine-grained progress rate metric."
            ),
            source_kind="reading_element",
        )

        self.assertEqual(
            ("summary_limit", "progress_metric"),
            _match_anchors(document, anchors),
        )

    def test_scorer_accepts_multiple_required_anchors_from_one_evidence_item(self) -> None:
        case = deepcopy(next(
            case for case in self.dataset.cases
            if case["id"] == "transformer_bert_confirmation_followup_001"
        ))
        run = GoldenFixtureHarness().run_case(self.dataset, case)
        anchor_ids = {
            "bert_transformer_encoder_background",
            "bert_masked_lm_pretraining",
        }
        items = run["evidence_ledger"]["items"]
        bert_items = [item for item in items if item.get("matched_anchor_id") in anchor_ids]
        self.assertEqual(2, len(bert_items))
        retained, removed = bert_items
        retained["matched_anchor_ids"] = sorted(anchor_ids)
        items.remove(removed)
        cited = run["research_answer"]["cited_evidence_ids"]
        cited.remove(removed["evidence_id"])

        score = BehaviorScorer().score_case(self.dataset, case, run)

        self.assertTrue(score.hard_pass, score.to_dict())

    def test_loader_rejects_an_anchor_without_a_positive_parseable_page(self) -> None:
        manifest = deepcopy(self.dataset.manifest)
        pack = deepcopy(self.dataset.paper_packs[0])
        cases = {"cases": deepcopy(self.dataset.cases)}
        manifest["paper_packs"] = ["paper-pack.yaml"]
        manifest["case_files"] = ["cases.yaml"]

        for invalid_page in (None, "", "page seven", 0, -1, 7.5, True):
            with self.subTest(page=invalid_page), TemporaryDirectory() as tmp:
                root = Path(tmp)
                broken_pack = deepcopy(pack)
                if invalid_page is None:
                    broken_pack["anchors"][0].pop("page", None)
                else:
                    broken_pack["anchors"][0]["page"] = invalid_page
                (root / "manifest.yaml").write_text(
                    yaml.safe_dump(manifest, sort_keys=False),
                    encoding="utf-8",
                )
                (root / "paper-pack.yaml").write_text(
                    yaml.safe_dump(broken_pack, sort_keys=False),
                    encoding="utf-8",
                )
                (root / "cases.yaml").write_text(
                    yaml.safe_dump(cases, sort_keys=False),
                    encoding="utf-8",
                )

                with self.assertRaisesRegex(ValueError, "positive parseable page"):
                    load_dataset(root / "manifest.yaml", repo_root=Path.cwd())

    def test_committed_runtime_anchor_tags_are_exact_and_page_constrained(self) -> None:
        from harness_py.corpus.tools import ReadingCorpusTools, _normalize

        tagged: dict[str, list[str]] = {}
        for document in ReadingCorpusTools(self.dataset).documents:
            if not document.matched_anchor_ids:
                continue
            for anchor_id in document.matched_anchor_ids:
                anchor = self.dataset.anchors_by_id[anchor_id]
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
                tagged.setdefault(anchor_id, []).append(document.location_ref)

        self.assertEqual(set(self.dataset.anchors_by_id), set(tagged), tagged)
        self.assertTrue(all(len(location_refs) == 1 for location_refs in tagged.values()), tagged)

    def test_physical_page_projection_grounds_a_cross_page_semantic_element(self) -> None:
        from harness_py.corpus.tools import ReadingCorpusTools

        paper_id = "bert_2018"
        anchor_id = "cross_page_anchor"
        model = {
            "paper_id": paper_id,
            "model_version": "physical-pages-v1",
            "diagnostics": {
                "pagesBuiltFromPhysicalProjection": 2,
                "pagesBuiltFromSemanticProjection": 0,
            },
            "reading_elements": [{
                "readingElementId": "semantic-paragraph",
                "elementType": "PARAGRAPH",
                "pageNumber": 1,
                "sectionTitle": "Implementation",
                "searchableText": (
                    "The paragraph starts on page one and continues with scripts "
                    "that reset the environment to a deterministic initial state."
                ),
            }],
            "pages": [{
                "pageNumber": 1,
                "pageText": "The paragraph starts on page one",
                "parserName": "MinerU",
                "parserVersion": "fixture",
            }, {
                "pageNumber": 2,
                "pageText": (
                    "and continues with scripts that reset the environment "
                    "to a deterministic initial state."
                ),
                "parserName": "MinerU",
                "parserVersion": "fixture",
            }],
            "locations": [{
                "locationRef": "page-ref-1",
                "locationType": "PAGE",
                "pageNumber": 1,
            }, {
                "locationRef": "page-ref-2",
                "locationType": "PAGE",
                "pageNumber": 2,
            }],
        }
        anchor = {
            "anchor_id": anchor_id,
            "paper_id": paper_id,
            "element": {"page": 2},
            "selector": {
                "exact_text": "reset the environment to a deterministic initial state",
            },
        }
        dataset = replace(
            self.dataset,
            reading_models_by_paper_id={paper_id: model},
            anchors_by_id={anchor_id: anchor},
        )
        tools = ReadingCorpusTools(dataset)
        tools.search_paper_candidates({"paper_ids": [paper_id], "limit": 1})

        result = tools.find_reading_locations({
            "paper_ids": [paper_id],
            "query_text": "scripts reset environment deterministic initial state",
            "top_k": 8,
        })

        self.assertIn("page-ref-2", [item["location_ref"] for item in result["locations"]])
        grounded = tools.documents_by_location["page-ref-2"]
        self.assertEqual((anchor_id,), grounded.matched_anchor_ids)
        self.assertEqual(2, grounded.page)

    def test_expanded_multi_paper_query_keeps_both_human_gap_anchors(self) -> None:
        from harness_py.corpus.tools import ReadingCorpusTools

        dataset = load_dataset("research/golden-data/manifest-expanded.yaml")
        tools = ReadingCorpusTools(dataset)
        paper_ids = ["gaia_2024", "webarena_2024"]
        tools.authorized_paper_ids.update(paper_ids)

        result = tools.find_reading_locations({
            "element_types": ["paragraph", "table"],
            "paper_ids": paper_ids,
            "query_text": "human performance gap",
            "top_k": 8,
        })
        matched = {
            anchor_id
            for item in result["locations"]
            for anchor_id in tools.documents_by_location[
                item["location_ref"]
            ].matched_anchor_ids
        }

        self.assertTrue({
            "gaia_human_gpt4_gap",
            "webarena_gpt4_human_gap",
        }.issubset(matched), matched)

    def test_multifacet_location_search_keeps_architecture_and_training_candidates(self) -> None:
        from harness_py.corpus.tools import ReadingCorpusTools

        tools = ReadingCorpusTools(self.dataset)
        tools.search_paper_candidates({
            "paper_ids": ["attention_is_all_you_need_2017", "bert_2018"],
            "limit": 2,
        })

        bert_result = tools.find_reading_locations({
            "paper_ids": ["bert_2018"],
            "query_text": (
                "pre-training masked language model next sentence prediction "
                "encoder-only bidirectional"
            ),
            "top_k": 8,
        })
        bert_locations = [item["location_ref"] for item in bert_result["locations"]]

        comparison_result = tools.find_reading_locations({
            "paper_ids": ["attention_is_all_you_need_2017", "bert_2018"],
            "query_text": (
                "encoder decoder bidirectional masked language model next sentence "
                "prediction training objective"
            ),
            "element_types": ["paragraph", "heading", "table"],
            "top_k": 20,
        })
        comparison_locations = [item["location_ref"] for item in comparison_result["locations"]]

        self.assertEqual(12, len(bert_locations))
        self.assertIn("reading_element_ff1d8830cd7544d0a166daf8466cd287", bert_locations)
        self.assertEqual(20, len(comparison_locations))
        self.assertIn("reading_element_d3f857764d464d7d931296ec82a0de85", comparison_locations)
        self.assertIn("reading_element_3f7beee8c6f3400d81fdce44c3bae795", comparison_locations)
        self.assertIn("reading_element_ff1d8830cd7544d0a166daf8466cd287", comparison_locations)

    def test_audit_reports_ambiguous_when_multiple_elements_match_the_same_anchor(self) -> None:
        from harness_py.evaluation.audit import audit_dataset

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
        from harness_py.evaluation.audit import audit_dataset

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

    def test_audit_rejects_an_anchor_with_an_invalid_authored_page(self) -> None:
        from harness_py.evaluation.audit import audit_dataset

        anchors = deepcopy(self.dataset.anchors_by_id)
        anchors["transformer_adam_training_params_span"]["element"]["page"] = "page seven"

        report = audit_dataset(replace(self.dataset, anchors_by_id=anchors))
        anchor = self._anchor_report(report, "transformer_adam_training_params_span")

        self.assertEqual("not_found", anchor["status"])
        self.assertEqual([], anchor["matched_location_refs"])

    def test_audit_reports_not_found_when_matching_element_has_no_location_ref(self) -> None:
        from harness_py.evaluation.audit import audit_dataset

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
        self.assertIn(
            "REQUIRED_PAPER_MISSING:attention_is_all_you_need_2017",
            score.dimensions["retrieval"].errors,
        )

    def test_scorer_rejects_forbidden_papers_anchors_and_citations(self) -> None:
        source_case = next(
            case for case in self.dataset.cases if case["id"] == "transformer_adam_params_001"
        )
        case = deepcopy(source_case)
        case["expect"]["papers"]["forbidden"] = ["attention_is_all_you_need_2017"]
        case["expect"]["evidence"]["forbidden"] = ["transformer_adam_training_params_span"]
        case["expect"]["citations"] = "forbidden"
        run = GoldenFixtureHarness().run_case(self.dataset, source_case)

        score = BehaviorScorer().score_case(self.dataset, case, run)

        self.assertFalse(score.hard_pass)
        self.assertIn(
            "FORBIDDEN_PAPER_ACCEPTED:attention_is_all_you_need_2017",
            score.dimensions["retrieval"].errors,
        )
        self.assertIn(
            "FORBIDDEN_ANCHOR_ACCEPTED:transformer_adam_training_params_span",
            score.dimensions["retrieval"].errors,
        )
        self.assertIn("CITATIONS_FORBIDDEN", score.dimensions["grounding"].errors)

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

    def test_scorer_reads_user_visible_facts_instead_of_treating_arbitrary_fields_as_fact_contract(self) -> None:
        case = next(
            case for case in self.dataset.cases
            if case["id"] == "adam_constraint_refinement_followup_001"
        )
        run = GoldenFixtureHarness().run_case(self.dataset, case)
        run["research_answer"]["markdown"] = (
            "The Transformer paper uses β₂ = 0.98. "
            "[[ev_07f6cc721a8f6053]]"
        )
        run["research_answer"].pop("fields_schema")
        run["research_answer"]["fields"] = {"evidence_id": "ev_07f6cc721a8f6053"}

        score = BehaviorScorer().score_case(self.dataset, case, run)

        self.assertTrue(score.hard_pass, score.to_dict())
        self.assertEqual("pass", score.dimensions["content"].status)

    def test_scorer_rejects_a_wrong_user_visible_fact_even_when_arbitrary_fields_are_empty(self) -> None:
        case = next(
            case for case in self.dataset.cases
            if case["id"] == "adam_constraint_refinement_followup_001"
        )
        run = GoldenFixtureHarness().run_case(self.dataset, case)
        run["research_answer"]["markdown"] = "The Transformer paper uses β₂ = 0.999."
        run["research_answer"].pop("fields_schema")
        run["research_answer"]["fields"] = {}

        score = BehaviorScorer().score_case(self.dataset, case, run)

        self.assertFalse(score.hard_pass)
        self.assertEqual("fail", score.dimensions["content"].status)
        self.assertIn("FACT_MISMATCH:beta2", score.dimensions["content"].errors)

    def test_scorer_does_not_match_an_expected_value_from_an_unrelated_claim(self) -> None:
        case = next(
            case for case in self.dataset.cases
            if case["id"] == "adam_constraint_refinement_followup_001"
        )
        run = GoldenFixtureHarness().run_case(self.dataset, case)
        run["research_answer"]["markdown"] = (
            "The Transformer paper uses β₂ = 0.999. "
            "A separate accuracy result is 0.98."
        )
        run["research_answer"].pop("fields_schema")
        run["research_answer"]["fields"] = {}

        score = BehaviorScorer().score_case(self.dataset, case, run)

        self.assertFalse(score.hard_pass)
        self.assertIn("FACT_MISMATCH:beta2", score.dimensions["content"].errors)

    def test_scorer_normalizes_user_visible_scientific_notation(self) -> None:
        source_case = next(
            case for case in self.dataset.cases
            if case["id"] == "transformer_adam_params_001"
        )
        case = deepcopy(source_case)
        case["expect"]["facts"] = {"epsilon": "1e-9"}
        run = GoldenFixtureHarness().run_case(self.dataset, source_case)
        run["research_answer"]["markdown"] = "The paper uses ε = 10⁻⁹."
        run["research_answer"].pop("fields_schema")
        run["research_answer"]["fields"] = {}

        score = BehaviorScorer().score_case(self.dataset, case, run)

        self.assertTrue(score.hard_pass, score.to_dict())
        self.assertEqual("pass", score.dimensions["content"].status)

    def test_scorer_normalizes_arbitrary_power_of_ten_exponents(self) -> None:
        source_case = next(
            case for case in self.dataset.cases
            if case["id"] == "transformer_adam_params_001"
        )
        case = deepcopy(source_case)
        case["expect"]["facts"] = {"epsilon": "1e-12"}
        run = GoldenFixtureHarness().run_case(self.dataset, source_case)
        run["research_answer"]["markdown"] = "The paper uses ε = 1 × 10⁻¹²."
        run["research_answer"].pop("fields_schema")
        run["research_answer"]["fields"] = {}

        score = BehaviorScorer().score_case(self.dataset, case, run)

        self.assertTrue(score.hard_pass, score.to_dict())
        self.assertEqual("pass", score.dimensions["content"].status)

    def test_scorer_matches_a_complete_optimizer_fact_set_in_user_visible_markdown(self) -> None:
        case = next(
            case for case in self.dataset.cases
            if case["id"] == "transformer_optimizer_reproduction_001"
        )
        run = GoldenFixtureHarness().run_case(self.dataset, case)
        run["research_answer"]["markdown"] = """
        | Field | Exact value from the Transformer paper |
        | --- | --- |
        | Optimizer | Adam |
        | β₁ | 0.9 |
        | β₂ | 0.98 |
        | ε | 10⁻⁹ |
        """
        run["research_answer"].pop("fields_schema")
        run["research_answer"]["fields"] = {}

        score = BehaviorScorer().score_case(self.dataset, case, run)

        self.assertTrue(score.hard_pass, score.to_dict())
        self.assertEqual("pass", score.dimensions["content"].status)

    def test_scorer_matches_a_normalized_phrase_fact_in_user_visible_markdown(self) -> None:
        case = next(
            case for case in self.dataset.cases
            if case["id"] == "bert_transformer_role_001"
        )
        run = GoldenFixtureHarness().run_case(self.dataset, case)
        run["research_answer"]["markdown"] = (
            "BERT uses a bidirectional Transformer encoder."
        )
        run["research_answer"].pop("fields_schema")
        run["research_answer"]["fields"] = {}

        score = BehaviorScorer().score_case(self.dataset, case, run)

        self.assertTrue(score.hard_pass, score.to_dict())
        self.assertEqual("pass", score.dimensions["content"].status)

    def test_scorer_matches_a_labeled_count_written_as_a_word(self) -> None:
        dataset = load_dataset("research/golden-data/manifest-expanded.yaml")
        case = next(
            case for case in dataset.cases
            if case["id"] == "agentbench_environment_inventory_001"
        )
        run = GoldenFixtureHarness().run_case(dataset, case)
        run["research_answer"]["markdown"] = (
            "AgentBench evaluates agents in eight interactive environments."
        )
        run["research_answer"].pop("fields_schema")
        run["research_answer"]["fields"] = {}

        score = BehaviorScorer().score_case(dataset, case, run)

        self.assertTrue(score.hard_pass, score.to_dict())
        self.assertEqual("pass", score.dimensions["content"].status)

    def test_scorer_matches_multiple_labeled_counts_and_percentages(self) -> None:
        dataset = load_dataset("research/golden-data/manifest-expanded.yaml")
        case = next(
            case for case in dataset.cases
            if case["id"] == "gaia_dataset_gap_001"
        )
        run = GoldenFixtureHarness().run_case(dataset, case)
        run["research_answer"]["markdown"] = (
            "GAIA contains 466 questions. Human annotators solve 92%, while "
            "GPT-4 with plugins reaches 15%."
        )
        run["research_answer"].pop("fields_schema")
        run["research_answer"]["fields"] = {}

        score = BehaviorScorer().score_case(dataset, case, run)

        self.assertTrue(score.hard_pass, score.to_dict())
        self.assertEqual("pass", score.dimensions["content"].status)

    def test_scorer_rejects_percentages_attached_to_the_wrong_subjects(self) -> None:
        dataset = load_dataset("research/golden-data/manifest-expanded.yaml")
        case = next(
            case for case in dataset.cases
            if case["id"] == "gaia_dataset_gap_001"
        )
        run = GoldenFixtureHarness().run_case(dataset, case)
        run["research_answer"]["markdown"] = (
            "GAIA contains 466 questions. Human annotators solve 15%, while "
            "GPT-4 with plugins reaches 92%."
        )
        run["research_answer"].pop("fields_schema")
        run["research_answer"]["fields"] = {}

        score = BehaviorScorer().score_case(dataset, case, run)

        self.assertFalse(score.hard_pass)
        self.assertIn("FACT_MISMATCH:human_success_rate", score.dimensions["content"].errors)
        self.assertIn("FACT_MISMATCH:gpt4_plugins_success_rate", score.dimensions["content"].errors)

    def test_scorer_matches_a_versus_comparison_with_qualified_model_name(self) -> None:
        dataset = load_dataset("research/golden-data/manifest-expanded.yaml")
        case = next(
            case for case in dataset.cases
            if case["id"] == "gaia_dataset_gap_001"
        )
        run = GoldenFixtureHarness().run_case(dataset, case)
        run["research_answer"]["markdown"] = (
            "GAIA contains 466 questions. Human respondents obtain 92% versus "
            "15% for GPT-4 equipped with plugins."
        )
        run["research_answer"].pop("fields_schema")
        run["research_answer"]["fields"] = {}

        score = BehaviorScorer().score_case(dataset, case, run)

        self.assertTrue(score.hard_pass, score.to_dict())
        self.assertEqual("pass", score.dimensions["content"].status)

    def test_scorer_matches_an_application_count_expressed_as_domains(self) -> None:
        dataset = load_dataset("research/golden-data/manifest-expanded.yaml")
        case = next(
            case for case in dataset.cases
            if case["id"] == "webarena_reproduction_protocol_001"
        )
        run = GoldenFixtureHarness().run_case(dataset, case)
        run["research_answer"]["markdown"] = (
            "WebArena provides four self-hosted web domains."
        )
        run["research_answer"].pop("fields_schema")
        run["research_answer"]["fields"] = {}

        score = BehaviorScorer().score_case(dataset, case, run)

        self.assertTrue(score.hard_pass, score.to_dict())
        self.assertEqual("pass", score.dimensions["content"].status)

    def test_scorer_matches_an_application_count_expressed_as_apps(self) -> None:
        dataset = load_dataset("research/golden-data/manifest-expanded.yaml")
        case = next(
            case for case in dataset.cases
            if case["id"] == "webarena_reproduction_protocol_001"
        )
        run = GoldenFixtureHarness().run_case(dataset, case)
        run["research_answer"]["markdown"] = (
            "Self-host four fully functional apps plus utilities."
        )
        run["research_answer"].pop("fields_schema")
        run["research_answer"]["fields"] = {}

        score = BehaviorScorer().score_case(dataset, case, run)

        self.assertTrue(score.hard_pass, score.to_dict())
        self.assertEqual("pass", score.dimensions["content"].status)

    def test_scorer_requires_review_instead_of_passing_an_unsupported_fact_type(self) -> None:
        source_case = next(
            case for case in self.dataset.cases
            if case["id"] == "transformer_adam_params_001"
        )
        case = deepcopy(source_case)
        case["expect"]["facts"] = {"unsupported_semantic_relation": "expected"}
        run = GoldenFixtureHarness().run_case(self.dataset, source_case)
        run["research_answer"]["markdown"] = "The expected relation is present."
        run["research_answer"].pop("fields_schema")
        run["research_answer"]["fields"] = {}

        score = BehaviorScorer().score_case(self.dataset, case, run)

        self.assertFalse(score.hard_pass)
        self.assertEqual("review_required", score.dimensions["content"].status)
        self.assertIn(
            "FACT_ASSERTION_UNSUPPORTED:unsupported_semantic_relation",
            score.dimensions["content"].errors,
        )

    def test_scorer_preserves_a_definite_fact_failure_when_another_fact_needs_review(self) -> None:
        source_case = next(
            case for case in self.dataset.cases
            if case["id"] == "transformer_adam_params_001"
        )
        case = deepcopy(source_case)
        case["expect"]["facts"] = {
            "beta2": "0.98",
            "unsupported_semantic_relation": "expected",
        }
        run = GoldenFixtureHarness().run_case(self.dataset, source_case)
        run["research_answer"]["markdown"] = "The Transformer uses β₂ = 0.999."
        run["research_answer"].pop("fields_schema")
        run["research_answer"]["fields"] = {}

        score = BehaviorScorer().score_case(self.dataset, case, run)

        self.assertFalse(score.hard_pass)
        self.assertEqual("fail", score.dimensions["content"].status)
        self.assertIn("FACT_MISMATCH:beta2", score.dimensions["content"].errors)
        self.assertIn(
            "FACT_ASSERTION_UNSUPPORTED:unsupported_semantic_relation",
            score.dimensions["content"].errors,
        )

    def test_scorer_rejects_an_unknown_declared_fact_fields_schema(self) -> None:
        case = next(
            case for case in self.dataset.cases
            if case["id"] == "adam_constraint_refinement_followup_001"
        )
        run = GoldenFixtureHarness().run_case(self.dataset, case)
        run["research_answer"]["fields_schema"] = "golden-facts/v999"
        run["research_answer"]["markdown"] = "The Transformer uses β₂ = 0.98."

        score = BehaviorScorer().score_case(self.dataset, case, run)

        self.assertFalse(score.hard_pass)
        self.assertEqual("review_required", score.dimensions["content"].status)
        self.assertIn(
            "FACT_FIELDS_SCHEMA_UNSUPPORTED:golden-facts/v999",
            score.dimensions["content"].errors,
        )

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

    def test_scorer_does_not_grade_skill_selection_or_tool_order(self) -> None:
        case = next(case for case in self.dataset.cases if case["id"] == "transformer_adam_params_001")
        run = GoldenFixtureHarness().run_case(self.dataset, case)
        run["skills_used"] = ["deep_comparison"]
        run["react_trace"] = list(reversed(run.get("react_trace", [])))

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

    def test_scorer_requires_an_explicit_runtime_outcome(self) -> None:
        case = next(
            case for case in self.dataset.cases if case["id"] == "transformer_adam_params_001"
        )
        run = GoldenFixtureHarness().run_case(self.dataset, case)
        del run["research_answer"]["outcome"]

        score = BehaviorScorer().score_case(self.dataset, case, run)

        self.assertFalse(score.hard_pass)
        self.assertIn(
            "OUTCOME_MISMATCH:expected=answered:actual=missing",
            score.dimensions["outcome"].errors,
        )

    def test_scorer_never_treats_a_technical_failure_as_a_research_outcome(self) -> None:
        case = next(
            case for case in self.dataset.cases if case["id"] == "transformer_adam_params_001"
        )
        run = GoldenFixtureHarness().run_case(self.dataset, case)
        run["status"] = "FAILED_TECHNICAL"
        run["research_answer"]["status"] = "FAILED_TECHNICAL"

        score = BehaviorScorer().score_case(self.dataset, case, run)

        self.assertFalse(score.hard_pass)
        self.assertIn(
            "OUTCOME_MISMATCH:expected=answered:actual=technical_failure",
            score.dimensions["outcome"].errors,
        )

    def test_scorer_rejects_an_outcome_inconsistent_with_execution_status(self) -> None:
        case = deepcopy(
            next(
                case
                for case in self.dataset.cases
                if case["id"] == "transformer_adam_params_001"
            )
        )
        case["id"] = "needs_clarification_fixture"
        case["expect"] = {
            "outcome": "needs_clarification",
            "citations": "forbidden",
        }
        run = GoldenFixtureHarness().run_case(self.dataset, case)
        run["status"] = "COMPLETED"
        run["research_answer"]["status"] = "COMPLETED"

        score = BehaviorScorer().score_case(self.dataset, case, run)

        self.assertFalse(score.hard_pass)
        self.assertIn(
            "OUTCOME_MISMATCH:expected=needs_clarification:actual=invalid",
            score.dimensions["outcome"].errors,
        )

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
