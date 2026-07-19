from __future__ import annotations

import json
import base64
import tempfile
import unittest
from copy import deepcopy
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch

import yaml
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from harness_py.core.models import GoldenDataset
from harness_py.corpus.product_db_dataset import build_product_dataset, summarize_product_corpus
from harness_py.corpus.tools import ReadingCorpusTools
from harness_py.evaluation.dataset import load_dataset
from harness_py.evaluation.fact_assertions import _scalar_string
from harness_py.evaluation.golden_fixture import GoldenFixtureHarness
from harness_py.evaluation.scoring import BehaviorScorer
from harness_py.orchestration.conversation import ConversationState
from harness_py.orchestration.research_contract import answer_validation_error
from harness_py.orchestration.research_skills import ResearchSkillRegistry
from harness_py.transport.provider_config import decrypt_provider_key


class PythonHarnessPrototypeTest(unittest.TestCase):
    def test_all_twenty_two_paradigms_are_available_as_optional_skills(self) -> None:
        skills = ResearchSkillRegistry().skills

        self.assertEqual(22, len(skills))
        self.assertIn("precision_fact_extraction", skills)
        self.assertIn("complex_constraint_combination", skills)

    def test_committed_seed_dataset_runs_without_failed_cases(self) -> None:
        dataset = load_dataset("research/golden-data/manifest.yaml")
        harness = GoldenFixtureHarness()
        runs = [harness.run_case(dataset, case) for case in dataset.cases]

        report = BehaviorScorer().score_dataset(dataset, runs)

        self.assertEqual(10, report["case_count"])
        self.assertEqual(0, report["failed_count"], report)
        self.assertTrue(all("evidence_ledger" in run for run in runs))
        self.assertTrue(all("citation_validation" in run for run in runs))

    def test_score_report_records_the_exact_scorer_contract(self) -> None:
        dataset = load_dataset("research/golden-data/manifest.yaml")
        runs = [GoldenFixtureHarness().run_case(dataset, case) for case in dataset.cases]

        report = BehaviorScorer().score_dataset(dataset, runs)

        self.assertEqual("harness-score-report/v3", report["schema_version"])
        contract = report["scorer_contract"]
        self.assertEqual("behavior-scorer/v3", contract["version"])
        self.assertEqual(
            "typed-markdown-facts/v1",
            contract["fact_assertions"]["version"],
        )
        self.assertRegex(contract["sha256"], r"^[0-9a-f]{64}$")

    def test_cli_rescore_reuses_saved_runs_without_calling_a_model(self) -> None:
        from harness_py.cli import main

        dataset = load_dataset("research/golden-data/manifest.yaml")
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            runs_root = root / "runs"
            out = root / "rescored.json"
            for case in dataset.cases:
                case_dir = runs_root / str(case["id"])
                case_dir.mkdir(parents=True)
                run = GoldenFixtureHarness().run_case(dataset, case)
                (case_dir / "harness_run.json").write_text(
                    json.dumps(run),
                    encoding="utf-8",
                )

            with patch("builtins.print"):
                code = main([
                    "--manifest",
                    "research/golden-data/manifest.yaml",
                    "rescore",
                    "--runs",
                    str(runs_root),
                    "--out",
                    str(out),
                ])

            report = json.loads(out.read_text(encoding="utf-8"))
            self.assertEqual(0, code)
            self.assertEqual(10, report["passed_count"])
            self.assertEqual("behavior-scorer/v3", report["scorer_contract"]["version"])

    def test_synthetic_dataset_proves_harness_is_data_driven(self) -> None:
        dataset = self._synthetic_dataset()
        case = dataset.cases[0]
        run = GoldenFixtureHarness().run_case(dataset, case)
        score = BehaviorScorer().score_case(dataset, case, run)

        self.assertTrue(score.hard_pass, score.to_dict())
        self.assertIn(
            "synthetic_anchor",
            {item.get("matched_anchor_id") for item in run["evidence_ledger"]["items"]},
        )
        self.assertEqual("42", run["research_answer"]["fields"]["answer"])

    def test_model_payload_redacts_all_golden_anchor_tags(self) -> None:
        from harness_py.corpus.tools import model_facing_payload

        payload = model_facing_payload({
            "evidence_id": "ev_1",
            "matched_anchor_id": "anchor_a",
            "matched_anchor_ids": ["anchor_a", "anchor_b"],
            "dense_score": 0.91,
            "sparse_score": 0.82,
            "fused_score": 0.03,
            "index_version": "reading-model-v1",
            "span_text": "Visible evidence.",
        })

        self.assertEqual({"evidence_id": "ev_1", "span_text": "Visible evidence."}, payload)

    def test_structural_scorer_normalizes_equivalent_power_of_ten_notation(self) -> None:
        self.assertEqual(_scalar_string("1e-9"), _scalar_string("10^-9"))
        self.assertEqual(_scalar_string("1e-9"), _scalar_string("10 ^ { - 9 }"))

    def test_metadata_answer_does_not_require_paper_content_citation(self) -> None:
        error = answer_validation_error(
            {"outcome": "answered", "markdown": "There are five papers."},
            {},
            used_paper_evidence=False,
        )

        self.assertEqual("", error)

    def test_answer_after_reading_paper_evidence_still_requires_citation(self) -> None:
        error = answer_validation_error(
            {"outcome": "answered", "markdown": "The paper proposes a new method."},
            {"ev_1": {"evidence_id": "ev_1", "citeable": True}},
            used_paper_evidence=True,
        )

        self.assertIn("paper-content evidence was read", error)

    def test_provider_key_decrypts_java_secret_crypto_format(self) -> None:
        key = AESGCM.generate_key(bit_length=128)
        iv = b"123456789012"
        ciphertext = AESGCM(key).encrypt(iv, b"sk-test", None)
        encoded = f"{base64.b64encode(iv).decode()}:{base64.b64encode(ciphertext).decode()}"

        self.assertEqual("sk-test", decrypt_provider_key(encoded, base64.b64encode(key).decode()))

    def test_product_db_rows_build_generic_reading_corpus(self) -> None:
        dataset = build_product_dataset(
            paper_rows=[{
                "paper_id": "paper_live",
                "title": "Live Paper",
                "authors": "Ada Example; Bo Example",
                "year": 2026,
                "model_version": "rm_live",
                "model_status": "READING_MODEL_READY",
                "parser_name": "parser",
                "parser_version": "1",
                "page_count": 3,
                "readable_page_count": 3,
                "readable_char_count": 100,
            }],
            element_rows=[{
                "paper_id": "paper_live",
                "model_version": "rm_live",
                "readingElementId": "el_live_1",
                "locationRef": "paper_live:p1:el1",
                "elementType": "paragraph",
                "pageNumber": 1,
                "sectionTitle": "Evaluation",
                "searchableText": "The paper defines evaluation dimensions for live harness testing.",
            }],
        )

        tools = ReadingCorpusTools(dataset)
        tools.search_paper_candidates({"paper_ids": ["paper_live"], "limit": 1})
        result = tools.find_reading_locations({
            "query_text": "evaluation dimensions",
            "paper_ids": ["paper_live"],
            "top_k": 1,
        })

        self.assertEqual(["Ada Example", "Bo Example"], dataset.paper_records_by_id["paper_live"]["identity"]["authors"])
        self.assertEqual(1, summarize_product_corpus(dataset).reading_element_count)
        self.assertEqual("paper_live", result["locations"][0]["paper_id"])
        self.assertEqual("paper_live:p1:el1", result["locations"][0]["location_ref"])

    def test_search_tool_caps_results_and_returns_compact_snippets(self) -> None:
        long_text = "evaluation " + ("supporting detail " * 100)
        dataset = build_product_dataset(
            paper_rows=[{
                "paper_id": "paper_live",
                "title": "Live Paper",
                "model_version": "rm_live",
                "model_status": "READING_MODEL_READY",
            }],
            element_rows=[{
                "paper_id": "paper_live",
                "model_version": "rm_live",
                "readingElementId": f"el_{index}",
                "locationRef": f"paper_live:p1:el{index}",
                "elementType": "paragraph",
                "pageNumber": 1,
                "searchableText": f"{long_text} candidate {index}",
            } for index in range(12)],
        )
        tools = ReadingCorpusTools(dataset)
        tools.search_paper_candidates({"paper_ids": ["paper_live"], "limit": 1})

        result = tools.find_reading_locations({
            "query_text": "evaluation",
            "paper_ids": ["paper_live"],
            "top_k": 20,
        })
        definition = next(
            item for item in tools.definitions()
            if item["function"]["name"] == "find_reading_locations"
        )
        parameters = definition["function"]["parameters"]["properties"]

        self.assertEqual(12, len(result["locations"]))
        self.assertLessEqual(len(result["locations"][0]["preview"]), 500)
        self.assertNotIn("evidence_id", result["locations"][0])
        self.assertEqual({}, tools.observations_by_evidence_id)
        invalid = tools.find_reading_locations({
            "query_text": "evaluation",
            "paper_ids": ["paper_live"],
            "element_types": ["abstract"],
        })
        self.assertEqual("unsupported_element_types", invalid["error"])
        read = tools.read_locations({"location_refs": [result["locations"][0]["location_ref"]]})
        evidence_id = read["items"][0]["evidence_id"]
        self.assertGreater(len(tools.observations_by_evidence_id[evidence_id]["span_text"]), 500)
        self.assertEqual(20, parameters["top_k"]["maximum"])
        self.assertIn("paragraph", parameters["element_types"]["items"]["enum"])
        self.assertIn("table", parameters["element_types"]["items"]["enum"])

    def test_location_search_deduplicates_identical_text_on_the_same_page(self) -> None:
        dataset = build_product_dataset(
            paper_rows=[{
                "paper_id": "paper_live",
                "title": "Live Paper",
                "model_version": "rm_live",
                "model_status": "READING_MODEL_READY",
            }],
            element_rows=[{
                "paper_id": "paper_live",
                "model_version": "rm_live",
                "readingElementId": f"el_{index}",
                "locationRef": f"paper_live:p1:el{index}",
                "elementType": "figure" if index else "image",
                "pageNumber": 1,
                "sectionTitle": "Architecture",
                "searchableText": "Figure 1: The model architecture.",
            } for index in range(2)],
        )
        tools = ReadingCorpusTools(dataset)
        tools.search_paper_candidates({"paper_ids": ["paper_live"], "limit": 1})

        result = tools.find_reading_locations({
            "query_text": "model architecture",
            "paper_ids": ["paper_live"],
            "top_k": 2,
        })

        self.assertEqual(1, len(result["locations"]))
        self.assertEqual(1, result["matched_count"])
        self.assertEqual("complete", result["coverage"])

    def test_candidate_search_reports_complete_or_truncated_coverage(self) -> None:
        dataset = build_product_dataset(
            paper_rows=[{
                "paper_id": f"paper_{index}",
                "title": f"Agent Evaluation {index}",
                "abstract": "Evaluation methods for language-model agents.",
                "model_status": "READING_MODEL_READY",
            } for index in range(3)],
            element_rows=[],
        )
        tools = ReadingCorpusTools(dataset)

        first = tools.search_paper_candidates({"query_text": "agent evaluation", "limit": 2})
        second = tools.search_paper_candidates({
            "query_text": "agent evaluation",
            "limit": 2,
            "offset": first["next_offset"],
        })

        self.assertEqual("truncated", first["coverage"])
        self.assertEqual(3, first["matched_count"])
        self.assertEqual("complete", second["coverage"])
        self.assertTrue(first["candidates"][0]["preview"])

    def test_ambiguous_identity_does_not_authorize_reading(self) -> None:
        dataset = build_product_dataset(
            paper_rows=[{
                "paper_id": paper_id,
                "title": title,
                "model_status": "READING_MODEL_READY",
            } for paper_id, title in [
                ("agent_eval_a", "Agent Evaluation Benchmark"),
                ("agent_eval_b", "Agent Evaluation Survey"),
            ]],
            element_rows=[{
                "paper_id": "agent_eval_a",
                "readingElementId": "el_a",
                "locationRef": "agent_eval_a:p1:el1",
                "elementType": "paragraph",
                "pageNumber": 1,
                "searchableText": "A benchmark for evaluating agents.",
            }],
        )
        tools = ReadingCorpusTools(dataset)

        identity = tools.find_papers_by_identity({"title": "Agent Evaluation"})
        locations = tools.find_reading_locations({
            "query_text": "benchmark",
            "paper_ids": ["agent_eval_a"],
        })

        self.assertEqual("ambiguous", identity["status"])
        self.assertEqual("paper_not_authorized_for_reading", locations["error"])
        self.assertNotIn("get_citation_edges", {
            item["function"]["name"] for item in tools.definitions()
        })

    def test_conversation_state_round_trips_to_json(self) -> None:
        state = ConversationState.new("round_trip", ["paper_a"])
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "state.json"

            state.save(path)
            loaded = ConversationState.load(path)

        self.assertEqual(state, loaded)

    def test_reset_clears_messages_and_research_memory(self) -> None:
        state = replace(
            ConversationState.new("replace_context"),
            selected_paper_ids=["synthetic_paper"],
            selected_evidence_ids=["old_evidence"],
            message_history=[{
                "role": "user",
                "turn_index": 1,
                "content": "Recommend RLHF papers.",
            }],
            evidence_items_by_id={
                "old_evidence": {
                    "evidence_id": "old_evidence",
                    "paper_id": "synthetic_paper",
                    "span_text": "Old context evidence.",
                }
            },
        )

        reset = state.reset()

        self.assertEqual([], reset.message_history)
        self.assertEqual([], reset.selected_paper_ids)
        self.assertEqual({}, reset.evidence_items_by_id)

    def test_conversation_prompt_exposes_ordered_paper_refs_not_candidate_evidence(self) -> None:
        dataset = build_product_dataset(
            paper_rows=[
                {"paper_id": "paper_a", "title": "Paper A"},
                {"paper_id": "paper_b", "title": "Paper B"},
            ],
            element_rows=[],
        )
        state = replace(
            ConversationState.new("conversation_refs"),
            selected_paper_ids=["paper_a", "paper_b"],
            selected_evidence_ids=["ev_quote"],
            evidence_items_by_id={
                "paper_candidate_a": {
                    "evidence_id": "paper_candidate_a",
                    "paper_id": "paper_a",
                    "title": "Paper A",
                    "element_type": "paper_candidate",
                    "citeable": False,
                },
                "paper_candidate_b": {
                    "evidence_id": "paper_candidate_b",
                    "paper_id": "paper_b",
                    "title": "Paper B",
                    "element_type": "paper_candidate",
                    "citeable": False,
                },
                "ev_quote": {
                    "evidence_id": "ev_quote",
                    "paper_id": "paper_a",
                    "title": "Paper A",
                    "element_type": "paragraph",
                    "span_text": "Quoted evidence.",
                },
            },
        )

        context = state.prompt_context(dataset)

        self.assertEqual(["ev_quote"], [item["evidence_id"] for item in context["selected_evidence_refs"]])

    def test_incomplete_turn_preserves_selected_evidence_memory(self) -> None:
        dataset = self._synthetic_dataset()
        state = replace(
            ConversationState.new("preserve_evidence"),
            selected_paper_ids=["synthetic_paper"],
            selected_evidence_ids=["ev_existing"],
            evidence_items_by_id={
                "ev_existing": {
                    "evidence_id": "ev_existing",
                    "paper_id": "synthetic_paper",
                    "title": "Synthetic Paper",
                    "element_type": "paragraph",
                    "span_text": "Existing evidence.",
                }
            },
        )
        run = {
            "run_id": "run_incomplete",
            "research_answer": {
                "status": "INCOMPLETE_PRECISE",
                "summary": "Evidence retrieval was incomplete.",
                "cited_evidence_ids": [],
            },
            "memory_update": {},
            "evidence_ledger": {"items": []},
        }

        updated = state.updated_from_run(dataset, run, "Continue the comparison.")

        self.assertEqual(["ev_existing"], updated.selected_evidence_ids)

    def test_package_source_does_not_embed_seed_domain_identifiers(self) -> None:
        forbidden = [
            "attention_is_all_you_need_2017",
            "bert_2018",
            "transformer_adam_training_params_span",
            "gpt3_2020",
            "Adam beta2",
        ]
        package_files = [
            path
            for path in Path("harness_py").glob("*.py")
            if path.name not in {"__init__.py", "__main__.py"}
        ]
        source = "\n".join(path.read_text(encoding="utf-8") for path in package_files)

        for value in forbidden:
            self.assertNotIn(value, source)

    def test_agent_run_rejects_unknown_case_before_provider_or_output(self) -> None:
        from harness_py.cli import main

        class StubProvider:
            def public_diagnostics(self):
                return {}

        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "must_not_exist"
            with patch(
                "harness_py.cli._live_harness",
                return_value=(StubProvider(), object()),
            ) as live_harness, patch("builtins.print"):
                code = main([
                    "--manifest",
                    "research/golden-data/manifest.yaml",
                    "agent-run",
                    "--case-id",
                    "unknown_case_id",
                    "--out",
                    str(out),
                ])

            self.assertNotEqual(0, code)
            live_harness.assert_not_called()
            self.assertFalse(out.exists())

    def _synthetic_dataset(self) -> GoldenDataset:
        paper = {
            "paper_id": "synthetic_paper",
            "identity": {
                "title": "Synthetic Paper",
                "year": 2026,
                "version_label": "fixture",
            },
            "source_assets": {"reading_model_path": "synthetic.reading-model.json"},
        }
        anchor = {
            "anchor_id": "synthetic_anchor",
            "paper_id": "synthetic_paper",
            "element": {
                "type": "paragraph",
                "page": 1,
                "section": "Result",
                "location_hint": "result paragraph",
            },
            "selector": {"exact_text": "structured value forty two"},
        }
        case = {
            "schema_version": "harness-golden-case/v2",
            "id": "synthetic_case",
            "paradigm": "precision_fact_extraction",
            "paper_pack": "synthetic_pack",
            "messages": [{"role": "user", "content": "What is the synthetic answer?"}],
            "expect": {
                "outcome": "answered",
                "papers": {"required": ["synthetic_paper"]},
                "evidence": {"required": ["synthetic_anchor"]},
                "facts": {"answer": "42"},
                "citations": "required",
            },
        }
        return GoldenDataset(
            root=Path("."),
            manifest_path=Path("synthetic.yaml"),
            manifest=yaml.safe_load("dataset_id: synthetic\n"),
            paper_packs=[{
                "schema_version": "harness-paper-pack/v2",
                "id": "synthetic_pack",
                "papers": [{"id": "synthetic_paper", "title": "Synthetic Paper", "year": 2026}],
                "anchors": [{
                    "id": "synthetic_anchor",
                    "paper": "synthetic_paper",
                    "page": 1,
                    "section": "Result",
                    "quote": "structured value forty two",
                }],
            }],
            cases=[case],
            paper_records_by_id={"synthetic_paper": paper},
            anchors_by_id={"synthetic_anchor": anchor},
            citation_edges=[],
            reading_models_by_paper_id={
                "synthetic_paper": {
                    "paper_id": "synthetic_paper",
                    "reading_elements": [{
                        "id": "el_1",
                        "readingElementId": "el_1",
                        "locationRef": "loc_1",
                        "elementType": "paragraph",
                        "pageNumber": 1,
                        "sectionTitle": "Result",
                        "searchableText": "The structured value forty two is the answer.",
                    }],
                }
            },
        )


if __name__ == "__main__":
    unittest.main()
