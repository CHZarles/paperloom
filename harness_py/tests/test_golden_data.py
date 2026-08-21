from __future__ import annotations

import hashlib
import io
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

import yaml

from harness_py.cli import main
from harness_py.corpus_test_fixtures.in_memory_tools import InMemoryTools
from harness_py.evaluation.dataset import load_dataset
from harness_py.evaluation.golden_case import paper_ids_for_case
from harness_py.evaluation.golden_fixture import GoldenFixtureHarness
from harness_py.orchestration.live_chat import _dataset_for_scope
from harness_py.orchestration.research_contract import research_agent_instructions
from harness_py.orchestration.research_skills import ResearchSkillRegistry


class GoldenDataTest(unittest.TestCase):
    def setUp(self) -> None:
        self.dataset = load_dataset("research/golden-data/manifest.yaml")

    def test_committed_dataset_is_v3_and_has_ten_claim_cases(self) -> None:
        self.assertEqual("harness-golden-data/v3", self.dataset.manifest["schema_version"])
        self.assertEqual(10, len(self.dataset.cases))
        self.assertEqual(5, len(self.dataset.paper_records_by_id))
        self.assertEqual(7, len(self.dataset.anchors_by_id))
        self.assertEqual(5, len(self.dataset.reading_models_by_paper_id))
        self.assertEqual(10, len(self.dataset.claims_by_id))
        for case in self.dataset.cases:
            self.assertEqual("harness-golden-case/v3", case["schema_version"])
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
                "schema_version: harness-golden-data/v3\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ValueError, "dataset_id"):
                load_dataset(manifest)

    def test_loader_rejects_authoring_files_outside_the_golden_root(self) -> None:
        with TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "manifest.yaml"
            manifest.write_text(
                "schema_version: harness-golden-data/v3\n"
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
            required = case.get("expect", {}).get("required_claims", [])
            self.assertTrue(required, case["id"])

    def test_expanded_dataset_does_not_change_the_harness_contract(self) -> None:
        expanded = load_dataset("research/golden-data/manifest-expanded.yaml")
        instructions = research_agent_instructions(ResearchSkillRegistry())

        self.assertEqual(
            "a276ac18da5b4bc0947d67fb62f2ec1ce982243c52ee78fb1352b4c3c48c48e5",
            hashlib.sha256(instructions.encode("utf-8")).hexdigest(),
            "expanded Golden Data must not change the established agent prompt",
        )
        self.assertEqual(
            InMemoryTools(self.dataset).definitions(),
            InMemoryTools(expanded).definitions(),
            "expanded Golden Data must not change model-visible corpus tools",
        )

    def test_recommendation_guidance_converges_citations_before_researching_again(self) -> None:
        skills = ResearchSkillRegistry()
        recommendation = skills.get("context_specific_brainstorming")
        instructions = research_agent_instructions(skills)

        self.assertIn("same Markdown block", recommendation["answer_guidance"])
        self.assertIn("Do not repeat", recommendation["answer_guidance"])
        self.assertIn("correct every issue named by the validator", instructions)
        self.assertIn("Do not reload a research skill already used", instructions)
        self.assertIn("recommending papers and explaining why is RESEARCH, not CATALOG", instructions)
        self.assertIn("If the reference has no unique antecedent", instructions)
        self.assertIn("Do not return Markdown as assistant text", instructions)

    def test_agent_prompt_preserves_answer_scope_and_repairs_proportionally(self) -> None:
        instructions = research_agent_instructions(ResearchSkillRegistry())

        self.assertIn("shortest complete answer", instructions)
        self.assertIn("does not determine which topics belong in the answer", instructions)
        self.assertIn("Stop researching once the evidence directly supports the requested scope", instructions)
        self.assertIn("make a proportional correction", instructions)
        self.assertIn("For a citation-only rejection, do not add sections or topics", instructions)
        self.assertIn("paper-specific notation as a universal definition", instructions)

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
