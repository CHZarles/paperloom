from __future__ import annotations

import json
import base64
import threading
import tempfile
import unittest
from copy import deepcopy
from dataclasses import replace
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from unittest.mock import patch

import yaml
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from harness_py.core.models import GoldenDataset
from harness_py.corpus.product_db_dataset import build_product_dataset, summarize_product_corpus
from harness_py.corpus.tools import ReadingCorpusTools
from harness_py.evaluation.dataset import load_dataset
from harness_py.evaluation.golden_fixture import GoldenFixtureHarness
from harness_py.evaluation.scoring import BehaviorScorer, _scalar_string
from harness_py.orchestration.conversation import ConversationState
from harness_py.orchestration.legacy.harness import ResearchAgentHarness, _answer_validation_error
from harness_py.orchestration.legacy.llm import (
    ChatModel,
    ChatTurn,
    MiniMaxChatModel,
    ToolCall,
    _normalize_structured_arguments,
)
from harness_py.orchestration.live_chat import LiveResearchChatHarness
from harness_py.orchestration.research_skills import ResearchSkillRegistry
from harness_py.transport.provider_config import ProviderConfig, _database_name, decrypt_provider_key


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

        self.assertEqual(15, report["case_count"])
        self.assertEqual(0, report["failed_count"], report)
        self.assertTrue(all("evidence_ledger" in run for run in runs))
        self.assertTrue(all("citation_validation" in run for run in runs))

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

    def test_structural_scorer_normalizes_equivalent_power_of_ten_notation(self) -> None:
        self.assertEqual(_scalar_string("1e-9"), _scalar_string("10^-9"))
        self.assertEqual(_scalar_string("1e-9"), _scalar_string("10 ^ { - 9 }"))

    def test_agent_harness_executes_tools_and_scores_synthetic_case(self) -> None:
        dataset = self._synthetic_dataset()
        case = dataset.cases[0]

        run = ResearchAgentHarness(_ReactFixtureModel()).run_case(dataset, case)
        score = BehaviorScorer().score_case(dataset, case, run)

        self.assertTrue(score.hard_pass, score.to_dict())
        self.assertEqual(4, run["diagnostics"]["tool_call_count"])
        self.assertIn(
            "synthetic_anchor",
            {item.get("matched_anchor_id") for item in run["evidence_ledger"]["items"]},
        )
        self.assertEqual("COMPLETED", run["research_answer"]["status"])

    def test_agent_harness_downgrades_supported_claim_without_tool_evidence(self) -> None:
        dataset = self._synthetic_dataset()
        case = dataset.cases[0]

        run = ResearchAgentHarness(_ReactNoEvidenceModel()).run_case(dataset, case)

        self.assertEqual("INCOMPLETE_PRECISE", run["research_answer"]["status"])
        self.assertEqual("abstained", run["research_answer"]["outcome"])

    def test_metadata_answer_does_not_require_paper_content_citation(self) -> None:
        error = _answer_validation_error(
            {"outcome": "answered", "markdown": "There are five papers."},
            {},
            used_paper_evidence=False,
        )

        self.assertEqual("", error)

    def test_answer_after_reading_paper_evidence_still_requires_citation(self) -> None:
        error = _answer_validation_error(
            {"outcome": "answered", "markdown": "The paper proposes a new method."},
            {"ev_1": {"evidence_id": "ev_1", "citeable": True}},
            used_paper_evidence=True,
        )

        self.assertIn("paper-content evidence was read", error)

    def test_minimax_client_sends_openai_compatible_tool_request(self) -> None:
        captured = {"bodies": []}

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):  # noqa: N802
                length = int(self.headers["Content-Length"])
                captured["path"] = self.path
                captured["authorization"] = self.headers.get("Authorization")
                captured["body"] = json.loads(self.rfile.read(length).decode("utf-8"))
                captured["bodies"].append(captured["body"])
                body = {
                    "choices": [{
                        "message": {
                            "content": "",
                            "tool_calls": [{
                                "id": "call_1",
                                "type": "function",
                                "function": {
                                    "name": "search_paper_candidates",
                                    "arguments": "{\"query_text\":\"synthetic\"}",
                                },
                            }],
                        }
                    }]
                }
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps(body).encode("utf-8"))

            def log_message(self, *_args):
                return

        server = HTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            provider = ProviderConfig(
                scope="llm",
                provider="minimax",
                api_style="openai-compatible",
                api_base_url=f"http://127.0.0.1:{server.server_port}/v1",
                model="MiniMax-M3",
                api_key="secret",
            )
            turn = MiniMaxChatModel(provider, timeout_seconds=5).complete_required_tool(
                [{"role": "user", "content": "hi"}],
                [
                    {"type": "function", "function": {"name": "search_paper_candidates", "parameters": {"type": "object"}}},
                    {"type": "function", "function": {"name": "read_locations", "parameters": {"type": "object"}}},
                ],
                "search_paper_candidates",
                16,
            )
            MiniMaxChatModel(provider, timeout_seconds=5).complete_required_tool(
                [{"role": "user", "content": "submit"}],
                [{
                    "type": "function",
                    "function": {"name": "submit_claims", "parameters": {"type": "object"}},
                }],
                "submit_claims",
                16,
            )

            MiniMaxChatModel(provider, timeout_seconds=5).complete_required_tool(
                [{"role": "user", "content": "judge"}],
                [{
                    "type": "function",
                    "function": {"name": "submit_judgment", "parameters": {"type": "object"}},
                }],
                "submit_judgment",
                16,
            )
        finally:
            server.shutdown()
            server.server_close()

        self.assertEqual("/v1/chat/completions", captured["path"])
        self.assertEqual("Bearer secret", captured["authorization"])
        self.assertEqual({"type": "adaptive"}, captured["bodies"][0]["thinking"])
        self.assertEqual({"type": "disabled"}, captured["bodies"][1]["thinking"])
        self.assertEqual({"type": "disabled"}, captured["bodies"][2]["thinking"])
        self.assertEqual(
            {"type": "function", "function": {"name": "search_paper_candidates"}},
            captured["bodies"][0]["tool_choice"],
        )
        self.assertEqual(
            {"type": "function", "function": {"name": "submit_claims"}},
            captured["bodies"][1]["tool_choice"],
        )
        self.assertEqual(
            {"type": "function", "function": {"name": "submit_judgment"}},
            captured["bodies"][2]["tool_choice"],
        )
        self.assertEqual(
            ["search_paper_candidates", "read_locations"],
            [tool["function"]["name"] for tool in captured["bodies"][0]["tools"]],
        )
        self.assertEqual("search_paper_candidates", turn.tool_calls[0].name)
        self.assertEqual({"query_text": "synthetic"}, turn.tool_calls[0].arguments)

    def test_minimax_structured_text_wrappers_decode_objects_and_scalars(self) -> None:
        self.assertEqual(
            {
                "claim_id": "claim_1",
                "field_values": [{"name": "beta1", "value": "0.9"}],
            },
            _normalize_structured_arguments({
                "claim_id": {"$text": "claim_1"},
                "field_values": {"$text": '[{"name":"beta1","value":"0.9"}]'},
            }),
        )

    def test_provider_key_decrypts_java_secret_crypto_format(self) -> None:
        key = AESGCM.generate_key(bit_length=128)
        iv = b"123456789012"
        ciphertext = AESGCM(key).encrypt(iv, b"sk-test", None)
        encoded = f"{base64.b64encode(iv).decode()}:{base64.b64encode(ciphertext).decode()}"

        self.assertEqual("sk-test", decrypt_provider_key(encoded, base64.b64encode(key).decode()))

    def test_database_name_uses_the_jdbc_url_path(self) -> None:
        self.assertEqual("paismart", _database_name("jdbc:mysql://127.0.0.1:3306/paismart?useSSL=false"))
        self.assertEqual("paismart", _database_name(""))

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
                "searchableText": long_text,
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

        self.assertEqual(10, len(result["locations"]))
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
        self.assertEqual(10, parameters["top_k"]["maximum"])
        self.assertIn("paragraph", parameters["element_types"]["items"]["enum"])
        self.assertIn("table", parameters["element_types"]["items"]["enum"])

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

    def test_live_chat_harness_reuses_agent_trace_shape_without_golden_case(self) -> None:
        dataset = self._synthetic_dataset()

        run = LiveResearchChatHarness(_ReactFixtureModel()).run_question(dataset, "What is the synthetic answer?")

        self.assertEqual("COMPLETED", run["research_answer"]["status"])
        self.assertTrue(run["case_id"].startswith("live_chat_"))
        self.assertIn("evidence_ledger", run)
        self.assertIn("citation_validation", run)
        self.assertIn("react_trace", run)

    def test_live_conversation_harness_carries_memory_across_turns(self) -> None:
        dataset = self._synthetic_dataset()
        model = _ReactFixtureModel()
        harness = LiveResearchChatHarness(model)
        state = ConversationState.new("conversation_test")

        first_run, state = harness.run_turn(dataset, state, "What is the synthetic answer?")
        second_run, state = harness.run_turn(dataset, state, "Use the same evidence again.")

        self.assertEqual("COMPLETED", first_run["research_answer"]["status"])
        self.assertEqual("COMPLETED", second_run["research_answer"]["status"])
        self.assertEqual(2, state.turn_index)
        self.assertEqual(["synthetic_paper"], state.selected_paper_ids)
        self.assertEqual(1, len(state.selected_evidence_ids))
        self.assertEqual(
            first_run["research_answer"]["cited_evidence_ids"],
            second_run["research_answer"]["cited_evidence_ids"],
        )
        self.assertEqual(4, len(state.message_history))

    def test_live_chat_direct_greeting_bypasses_research(self) -> None:
        dataset = self._synthetic_dataset()
        harness = LiveResearchChatHarness(_ReactFixtureModel())
        state = ConversationState.new("direct_greeting")

        run, state = harness.run_turn(dataset, state, "hi")

        self.assertEqual("COMPLETED", run["research_answer"]["status"])
        self.assertEqual("conversation", run["research_answer"]["answer_type"])
        self.assertNotIn("stage_trace", run)
        self.assertEqual(0, run["diagnostics"]["tool_call_count"])

        run, state = harness.run_turn(dataset, state, "Recommend me some important paper")

        self.assertEqual("NEEDS_CLARIFICATION", run["research_answer"]["status"])
        self.assertEqual(0, run["diagnostics"]["tool_call_count"])
        self.assertIn("What topic", run["research_answer"]["markdown"])

        run, state = harness.run_turn(dataset, state, "What does RLHF mean?")

        self.assertEqual("COMPLETED", run["research_answer"]["status"])
        self.assertEqual("RLHF means Reinforcement Learning from Human Feedback.", run["research_answer"]["markdown"])
        self.assertEqual(6, len(state.message_history))

    def test_live_chat_visible_choice_resolves_from_history(self) -> None:
        dataset = self._synthetic_dataset()
        model = _ReactFixtureModel()
        harness = LiveResearchChatHarness(model)
        state = replace(
            ConversationState.new("recommendation_choice"),
            message_history=[{
                "role": "assistant",
                "turn_index": 1,
                "content": (
                    "Which kind of papers should I recommend?\n\n"
                    "1. Comparison papers\n2. Reproduction papers\n3. Systematic-review papers"
                ),
            }],
        )

        run, state = harness.run_turn(dataset, state, "the third")

        self.assertEqual("COMPLETED", run["research_answer"]["status"])
        self.assertIn("context_specific_brainstorming", run["skills_used"])
        self.assertNotIn("I'll treat", run["research_answer"]["markdown"])
        self.assertEqual(4, run["diagnostics"]["tool_call_count"])
        self.assertIn(
            "find_papers_by_identity",
            [
                call["tool_name"] for call in run["react_trace"]
            ],
        )
        self.assertIn(
            "find_reading_locations",
            [
                call["tool_name"] for call in run["react_trace"]
            ],
        )

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

    def test_cli_run_writes_optional_frontend_panes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            from harness_py.cli import main

            code = main(["--manifest", "research/golden-data/manifest.yaml", "run", "--out", tmp])

            self.assertEqual(0, code)
            report_path = Path(tmp) / "score_report.json"
            self.assertTrue(report_path.exists())
            report = json.loads(report_path.read_text(encoding="utf-8"))
            self.assertEqual(0, report["failed_count"])
            first_case = Path(tmp) / "transformer_adam_params_001"
            self.assertTrue((first_case / "evidence_ledger.json").exists())
            self.assertTrue((first_case / "citation_validation.json").exists())
            self.assertTrue((first_case / "react_trace.json").exists())

    def test_agent_run_rejects_unknown_case_before_provider_or_output(self) -> None:
        from harness_py.cli import main

        class StubProvider:
            def public_diagnostics(self):
                return {}

        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "must_not_exist"
            with patch(
                "harness_py.cli._live_model",
                return_value=(StubProvider(), object()),
            ) as live_model, patch("builtins.print"):
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
            live_model.assert_not_called()
            self.assertFalse(out.exists())

    def test_chat_shell_keeps_terminal_session_state(self) -> None:
        from harness_py.cli import run_chat_shell

        dataset = self._synthetic_dataset()
        state = ConversationState.new("shell_test")
        inputs = iter(["What is the answer?", "Use the same evidence.", "/state", "/exit"])
        outputs = []
        prompts = []

        def input_func(prompt):
            prompts.append(prompt)
            return next(inputs)

        with tempfile.TemporaryDirectory() as tmp:
            state_path = Path(tmp) / "state.json"
            final_state = run_chat_shell(
                dataset,
                _ShellFakeHarness(),
                state,
                state_path=str(state_path),
                input_func=input_func,
                output_func=outputs.append,
            )

            saved = ConversationState.load(state_path)

        self.assertEqual(2, final_state.turn_index)
        self.assertEqual(final_state, saved)
        self.assertEqual(["shell_ev_2"], final_state.selected_evidence_ids)
        self.assertEqual(["you> ", "you> ", "you> ", "you> "], prompts)
        self.assertTrue(any("Interactive chat ready" in output for output in outputs))
        self.assertTrue(any(output == "assistant" for output in outputs))
        self.assertTrue(any('"turn_index": 2' in output for output in outputs))

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


class _ReactFixtureModel(ChatModel):
    def complete(self, messages, tools, max_tokens):
        question = next(
            (
                str(item.get("content") or "")
                for item in reversed(messages)
                if item.get("role") == "user"
                and not str(item.get("content") or "").startswith("Finish this turn")
            ),
            "",
        )
        normalized = question.casefold()
        tool_results = {
            str(item.get("name") or ""): json.loads(str(item.get("content") or "{}"))
            for item in messages
            if item.get("role") == "tool"
        }
        prior_evidence = next(
            (
                json.loads(str(item.get("content") or "").split("\n", 1)[1])
                for item in messages
                if item.get("role") == "system"
                and str(item.get("content") or "").startswith("Previously cited evidence")
            ),
            [],
        )

        if normalized == "hi":
            return _react_tool_turn("submit_research_answer", {
                "outcome": "answered",
                "markdown": "Hi. What would you like to research?",
                "fields": {},
            })
        if "recommend" in normalized and "important paper" in normalized:
            return _react_tool_turn("submit_research_answer", {
                "outcome": "needs_clarification",
                "markdown": "Sure. What topic should the paper recommendations focus on?",
                "fields": {},
            })
        if normalized.startswith("what does rlhf mean"):
            return _react_tool_turn("submit_research_answer", {
                "outcome": "answered",
                "markdown": "RLHF means Reinforcement Learning from Human Feedback.",
                "fields": {},
            })

        recommendation = "the third" in normalized
        skill_id = "context_specific_brainstorming" if recommendation else "precision_fact_extraction"
        if "get_research_skill" not in tool_results:
            return _react_tool_turn("get_research_skill", {"skill_id": skill_id})

        if prior_evidence:
            evidence_id = str(prior_evidence[0]["evidence_id"])
            return _react_tool_turn("submit_research_answer", {
                "outcome": "answered",
                "markdown": f"The structured value is 42. [[{evidence_id}]]",
                "fields": {"answer": "42"},
            })
        if "find_papers_by_identity" not in tool_results:
            return _react_tool_turn("find_papers_by_identity", {"paper_id": "synthetic_paper"})
        if "find_reading_locations" not in tool_results:
            return _react_tool_turn("find_reading_locations", {
                "paper_ids": ["synthetic_paper"],
                "query_text": "structured value",
                "top_k": 3,
            })
        if "read_locations" not in tool_results:
            location = tool_results["find_reading_locations"]["locations"][0]["location_ref"]
            return _react_tool_turn("read_locations", {"location_refs": [location]})
        evidence_id = str(tool_results["read_locations"]["items"][0]["evidence_id"])
        if recommendation:
            return _react_tool_turn("submit_research_answer", {
                "outcome": "answered",
                "markdown": f"Synthetic Paper is the matching recommendation. [[{evidence_id}]]",
                "fields": {},
            })
        return _react_tool_turn("submit_research_answer", {
            "outcome": "answered",
            "markdown": f"The structured value is 42. [[{evidence_id}]]",
            "fields": {"answer": "42"},
        })


class _ReactNoEvidenceModel(ChatModel):
    def complete(self, messages, tools, max_tokens):
        return _react_tool_turn("submit_research_answer", {
            "outcome": "abstained",
            "markdown": "The current corpus does not contain enough evidence to answer this request.",
            "fields": {},
        })


def _react_tool_turn(name: str, arguments: dict) -> ChatTurn:
    return ChatTurn(
        content="",
        tool_calls=[ToolCall(id=f"call_{name}", name=name, arguments=arguments)],
    )


class _ActionFirstRecommendationModel(ChatModel):
    def __init__(self) -> None:
        self.request_payloads: list[dict] = []

    def complete(self, messages, tools, max_tokens):
        system = str(messages[0].get("content") or "")
        if "TURN_ROUTING" in system:
            payload = json.loads(messages[-1]["content"])
            self.request_payloads.append(payload)
            user_message = str(payload["user_message"]).strip().casefold()
            if user_message == "hi":
                arguments = {
                    "action": "answer",
                    "context_mode": "continue",
                    "message": "Hi. What would you like to research?",
                    "effective_request": "",
                    "paradigm": "",
                }
            elif user_message.startswith("what does rlhf mean"):
                arguments = {
                    "action": "answer",
                    "context_mode": "continue",
                    "message": "RLHF means Reinforcement Learning from Human Feedback.",
                    "effective_request": "",
                    "paradigm": "",
                }
            elif "important paper" in user_message:
                arguments = {
                    "action": "clarify",
                    "context_mode": "continue",
                    "message": "Sure. What topic are you interested in?",
                    "effective_request": "Recommend important papers.",
                    "paradigm": "",
                }
            else:
                arguments = {
                    "action": "research",
                    "context_mode": "continue",
                    "message": "",
                    "effective_request": "Recommend systematic-review papers from the current corpus.",
                    "paradigm": "context_specific_brainstorming",
                }
            return ChatTurn(content="", tool_calls=[ToolCall(
                id="turn_action",
                name="submit_turn_action",
                arguments=arguments,
            )])
        if "RESEARCH_TASK_PLANNING" in system:
            return ChatTurn(content="", tool_calls=[ToolCall(
                id="research_task",
                name="submit_turn_decision",
                arguments={
                    "route": "research",
                    "effective_goal": "Recommend systematic-review papers from the current corpus.",
                    "task": {"verb": "recommend", "object": "papers"},
                    "constraints": {"paper_type": "systematic_review"},
                    "primary_paradigm": "context_specific_brainstorming",
                    "answer_shape": "constraint_filter",
                    "obligations": [{
                        "id": "recommendation",
                        "description": "Select and justify one paper matching the requested paper type.",
                        "kind": "recommendation",
                        "paper_scope": [],
                        "required": True,
                        "answer_field": "",
                    }],
                    "assumption": "Use broad corpus coverage within the selected paper type.",
                    "blocking_reason": None,
                    "direct_reply": "",
                    "pending_interaction": {
                        "interaction_id": "",
                        "kind": "none",
                        "question": "",
                        "options": [],
                    },
                    "paper_references": [],
                    "requested_aspects": ["systematic review"],
                    "requires_corpus_observation": True,
                    "required_evidence_types": ["metadata", "paragraph"],
                    "required_capabilities": ["candidate_retrieval", "evidence_retrieval"],
                    "confidence": 1.0,
                },
            )])
        if "TURN_DECISION" in system:
            payload = json.loads(messages[-1]["content"])
            self.request_payloads.append(payload)
            user_message = str(payload["user_message"]).strip().casefold()
            context = payload["conversation_context"]
            if user_message == "hi":
                arguments = {
                    "route": "direct",
                    "effective_goal": "",
                    "task": {},
                    "constraints": {},
                    "primary_paradigm": "",
                    "answer_shape": "conversation",
                    "obligations": [],
                    "assumption": "",
                    "blocking_reason": None,
                    "direct_reply": "Hi. What would you like to research?",
                    "pending_interaction": {
                        "interaction_id": "",
                        "kind": "none",
                        "question": "",
                        "options": [],
                    },
                    "paper_references": [],
                    "requested_aspects": [],
                    "requires_corpus_observation": False,
                    "required_evidence_types": [],
                    "required_capabilities": [],
                    "confidence": 1.0,
                }
            elif context.get("pending_interaction"):
                arguments = {
                    "route": "research",
                    "effective_goal": "Recommend systematic-review papers from the current corpus.",
                    "task": {"verb": "recommend", "object": "papers"},
                    "constraints": {"paper_type": "systematic_review"},
                    "primary_paradigm": "context_specific_brainstorming",
                    "answer_shape": "constraint_filter",
                    "obligations": [{
                        "id": "recommendation",
                        "description": "Select and justify one paper matching the requested paper type.",
                        "kind": "recommendation",
                        "paper_scope": [],
                        "required": True,
                        "answer_field": "",
                    }],
                    "assumption": "Use broad corpus coverage within the selected paper type.",
                    "blocking_reason": None,
                    "direct_reply": "",
                    "pending_interaction": {
                        "interaction_id": "",
                        "kind": "none",
                        "question": "",
                        "options": [],
                    },
                    "paper_references": [],
                    "requested_aspects": ["systematic review"],
                    "requires_corpus_observation": True,
                    "required_evidence_types": ["metadata", "paragraph"],
                    "required_capabilities": ["candidate_retrieval", "evidence_retrieval"],
                    "confidence": 1.0,
                }
            else:
                arguments = {
                    "route": "clarify",
                    "effective_goal": "Recommend important papers after the user supplies a topic or goal.",
                    "task": {"verb": "recommend", "object": "papers"},
                    "constraints": {},
                    "primary_paradigm": "ambiguity_resolution",
                    "answer_shape": "ambiguity_clarification",
                    "obligations": [],
                    "assumption": "",
                    "blocking_reason": "A recommendation topic or goal is missing.",
                    "direct_reply": "Sure. What topic are you interested in, and are you looking for an overview, benchmarks, or methods?",
                    "pending_interaction": {
                        "interaction_id": "recommendation_topic",
                        "kind": "free_text",
                        "question": "What topic are you interested in, and are you looking for an overview, benchmarks, or methods?",
                        "options": [],
                    },
                    "paper_references": [],
                    "requested_aspects": [],
                    "requires_corpus_observation": False,
                    "required_evidence_types": [],
                    "required_capabilities": [],
                    "confidence": 1.0,
                }
            return ChatTurn(content="", tool_calls=[ToolCall(
                id="turn_decision",
                name="submit_turn_decision",
                arguments=arguments,
            )])
        if "EVIDENCE_QUERY_PLANNING" in system:
            payload = json.loads(messages[-1]["content"])
            self.request_payloads.append(payload)
            existing = [
                item for item in payload.get("existing_state", {}).get("evidence", [])
                if item.get("citeable") is not False
            ]
            if existing:
                return _recommendation_plan(reuse_evidence=[{
                    "obligation_id": "recommendation_selection",
                    "evidence_ids": [existing[0]["evidence_id"]],
                }])
            return _recommendation_plan()
        if "ATOMIC_CLAIM_CONSTRUCTION" in system:
            payload = json.loads(messages[-1]["content"])
            self.request_payloads.append(payload)
            evidence_id = next(
                item["evidence_id"]
                for item in payload["evidence"]
                if item.get("citeable") is not False
            )
            return ChatTurn(content="", tool_calls=[ToolCall(
                id="submit_recommendation_claim",
                name="submit_claims",
                arguments={
                    "claims": [{
                        "claim_id": "recommendation_claim",
                        "role": "recommendation",
                        "text": "Synthetic Paper is relevant because it reports the requested structured result.",
                        "obligation_ids": ["recommendation_selection"],
                        "evidence_ids": [evidence_id],
                        "field_values": [],
                    }],
                    "summary": "Constructed one bounded recommendation.",
                },
            )])
        if "ATOMIC_CLAIM_VERIFICATION" in system:
            payload = json.loads(messages[-1]["content"])
            self.request_payloads.append(payload)
            claim = payload["claims"][0]
            return ChatTurn(content="", tool_calls=[ToolCall(
                id="verify_recommendation_claim",
                name="submit_claim_verdicts",
                arguments={
                    "verdicts": [{
                        "claim_id": claim["claim_id"],
                        "verdict": "keep",
                        "verified_text": claim["text"],
                        "evidence_ids": claim["evidence_ids"],
                        "field_values": [],
                        "reason": "The recommendation rationale is directly supported.",
                    }],
                    "summary": "Recommendation verified.",
                },
            )])
        raise AssertionError(f"unexpected model prompt: {system[:80]}")


def _recommendation_plan(reuse_evidence: list[dict] | None = None) -> ChatTurn:
    return ChatTurn(content="", tool_calls=[ToolCall(
        id="submit_recommendation_plan",
        name="submit_retrieval_queries",
        arguments={
            "paper_targets": [] if reuse_evidence else [{
                "target_id": "candidate_pool",
                "paper_id": "",
                "title": "",
                "arxiv_id": "",
                "discovery_query": "synthetic",
                "browse_all": False,
                "coverage_obligation_ids": [],
            }],
            "evidence_queries": [] if reuse_evidence else [{
                "query_id": "recommendation_evidence",
                "obligation_ids": ["recommendation_selection"],
                "target_ids": ["candidate_pool"],
                "query_text": "structured value",
                "section_query": "",
                "element_types": ["paragraph"],
                "top_k": 3,
            }],
            "reuse_evidence": reuse_evidence or [],
            "summary": "Planned one bounded recommendation retrieval round.",
        },
    )])


class _ShellFakeHarness:
    def run_turn(self, dataset, state, user_message):
        turn = state.turn_index + 1
        evidence_id = f"shell_ev_{turn}"
        run = {
            "run_id": f"shell_run_{turn}",
            "case_id": f"shell_case_{turn}",
            "research_answer": {
                "status": "COMPLETED",
                "answer_type": "exact_fact",
                "summary": f"Shell answer {turn}.",
                "markdown": f"Shell answer {turn}.",
                "cited_evidence_ids": [evidence_id],
            },
            "evidence_ledger": {
                "items": [{
                    "evidence_id": evidence_id,
                    "paper_id": "synthetic_paper",
                    "title": "Synthetic Paper",
                    "section": "Result",
                    "page": 1,
                    "location": f"loc_{turn}",
                    "element_type": "paragraph",
                    "span_text": "The structured value forty two is the answer.",
                }]
            },
            "diagnostics": {
                "tool_call_count": 1,
                "finish_reason": "final_json",
            },
            "memory_update": {
                "selected_paper_ids": ["synthetic_paper"],
                "selected_evidence_ids": [evidence_id],
            },
        }
        return run, state.updated_from_run(dataset, run, user_message)


if __name__ == "__main__":
    unittest.main()
