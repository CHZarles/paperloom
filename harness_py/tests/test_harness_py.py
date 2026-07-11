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

import yaml
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from harness_py.agent_harness import ResearchAgentHarness
from harness_py.conversation import ConversationState
from harness_py.dataset import load_artifact_contracts, load_dataset
from harness_py.golden_fixture import GoldenFixtureHarness
from harness_py.live_chat import LiveResearchChatHarness
from harness_py.llm import ChatModel, ChatTurn, MiniMaxChatModel, ToolCall
from harness_py.models import GoldenDataset
from harness_py.provider_config import ProviderConfig, decrypt_provider_key
from harness_py.product_db_dataset import build_product_dataset, summarize_product_corpus
from harness_py.scoring import BehaviorScorer, _scalar_string
from harness_py.tests.test_stage_prototype import _ExactFactHarnessModel
from harness_py.tools import ReadingCorpusTools


class PythonHarnessPrototypeTest(unittest.TestCase):
    def test_committed_seed_dataset_runs_without_failed_cases(self) -> None:
        dataset = load_dataset("research/golden-data/manifest.yaml")
        harness = GoldenFixtureHarness()
        runs = [harness.run_case(dataset, case) for case in dataset.cases]

        report = BehaviorScorer().score_dataset(dataset, runs)

        self.assertEqual(9, report["case_count"])
        self.assertEqual(0, report["failed_count"], report)
        self.assertTrue(all("intent_frame" in run for run in runs))
        self.assertTrue(all("evidence_ledger" in run for run in runs))
        self.assertTrue(all("verification_pass" in run for run in runs))

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

    def test_scorer_rejects_supported_claim_without_evidence(self) -> None:
        dataset = self._synthetic_dataset()
        case = dataset.cases[0]
        run = GoldenFixtureHarness().run_case(dataset, case)
        broken = deepcopy(run)
        broken["claim_graph"]["claims"][0]["supporting_evidence_ids"] = []

        score = BehaviorScorer().score_case(dataset, case, broken)

        self.assertIn(
            "SUPPORTED_CLAIM_WITHOUT_EVIDENCE:claim_1",
            score.dimensions["grounding"].errors,
        )

    def test_structural_scorer_normalizes_equivalent_power_of_ten_notation(self) -> None:
        self.assertEqual(_scalar_string("1e-9"), _scalar_string("10^-9"))
        self.assertEqual(_scalar_string("1e-9"), _scalar_string("10 ^ { - 9 }"))

    def test_agent_harness_executes_tools_and_scores_synthetic_case(self) -> None:
        dataset = self._synthetic_dataset()
        case = dataset.cases[0]

        run = ResearchAgentHarness(_ExactFactHarnessModel()).run_case(dataset, case)
        score = BehaviorScorer().score_case(dataset, case, run)

        self.assertTrue(score.hard_pass, score.to_dict())
        self.assertEqual(3, run["diagnostics"]["tool_call_count"])
        self.assertIn(
            "synthetic_anchor",
            {item.get("matched_anchor_id") for item in run["evidence_ledger"]["items"]},
        )
        self.assertEqual("COMPLETED", run["research_answer"]["status"])

    def test_agent_harness_downgrades_supported_claim_without_tool_evidence(self) -> None:
        dataset = self._synthetic_dataset()
        case = dataset.cases[0]

        run = ResearchAgentHarness(_NoEvidenceStageModel()).run_case(dataset, case)

        self.assertEqual("INCOMPLETE_PRECISE", run["research_answer"]["status"])
        self.assertTrue(run["evidence_ledger"]["missing_evidence"])

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
                    "function": {"name": "submit_stage_result", "parameters": {"type": "object"}},
                }],
                "submit_stage_result",
                16,
            )
        finally:
            server.shutdown()
            server.server_close()

        self.assertEqual("/v1/chat/completions", captured["path"])
        self.assertEqual("Bearer secret", captured["authorization"])
        self.assertEqual({"type": "adaptive"}, captured["bodies"][0]["thinking"])
        self.assertEqual({"type": "disabled"}, captured["bodies"][1]["thinking"])
        self.assertNotIn("tool_choice", captured["bodies"][0])
        self.assertEqual(
            ["search_paper_candidates", "read_locations"],
            [tool["function"]["name"] for tool in captured["bodies"][0]["tools"]],
        )
        self.assertEqual("search_paper_candidates", turn.tool_calls[0].name)
        self.assertEqual({"query_text": "synthetic"}, turn.tool_calls[0].arguments)

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

        run = LiveResearchChatHarness(_ExactFactHarnessModel()).run_question(dataset, "What is the synthetic answer?")

        self.assertEqual("COMPLETED", run["research_answer"]["status"])
        self.assertTrue(run["case_id"].startswith("live_chat_"))
        self.assertIn("intent_frame", run)
        self.assertIn("evidence_ledger", run)
        self.assertIn("verification_pass", run)

    def test_live_conversation_harness_carries_memory_across_turns(self) -> None:
        dataset = self._synthetic_dataset()
        model = _ExactFactHarnessModel()
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
        harness = LiveResearchChatHarness(_ActionFirstRecommendationModel())
        state = ConversationState.new("direct_greeting")

        run, state = harness.run_turn(dataset, state, "hi")

        self.assertEqual("COMPLETED", run["research_answer"]["status"])
        self.assertEqual("conversation", run["research_answer"]["answer_type"])
        self.assertEqual([], run["stage_trace"])
        self.assertEqual(0, run["diagnostics"]["tool_call_count"])
        self.assertEqual({}, state.pending_interaction)
        self.assertEqual({}, state.active_task)

        run, state = harness.run_turn(dataset, state, "Recommend me some important paper")

        self.assertEqual("COMPLETED", run["research_answer"]["status"])
        self.assertEqual("context_specific_brainstorming", run["intent_frame"]["primary_paradigm"])
        self.assertEqual("non_blocking", run["intent_frame"]["actionability"])
        self.assertGreaterEqual(run["diagnostics"]["tool_call_count"], 2)
        self.assertEqual({}, state.pending_interaction)
        self.assertEqual({}, state.active_task)

    def test_live_chat_pending_choice_resumes_active_task(self) -> None:
        dataset = self._synthetic_dataset()
        model = _ActionFirstRecommendationModel()
        harness = LiveResearchChatHarness(model)
        state = ConversationState.new("recommendation_choice").with_active_task({
            "goal": "Recommend important papers from the current corpus.",
            "task": {"verb": "recommend", "object": "papers"},
            "constraints": {},
            "paradigm": "context_specific_brainstorming",
        })
        state = state.with_pending_interaction({
            "interaction_id": "paper_kind_01",
            "kind": "choice",
            "question": "Which kind of papers should I recommend?",
            "options": [
                {
                    "id": "comparison",
                    "label": "Comparison papers",
                    "task_patch": {"constraints": {"paper_type": "comparison"}},
                },
                {
                    "id": "reproduction",
                    "label": "Reproduction papers",
                    "task_patch": {"constraints": {"paper_type": "reproduction"}},
                },
                {
                    "id": "systematic_review",
                    "label": "Systematic-review papers",
                    "task_patch": {"constraints": {"paper_type": "systematic_review"}},
                },
            ],
        })

        run, state = harness.run_turn(dataset, state, "the third")

        self.assertEqual("COMPLETED", run["research_answer"]["status"])
        self.assertEqual("non_blocking", run["intent_frame"]["actionability"])
        self.assertEqual({}, state.pending_interaction)
        self.assertEqual({}, state.active_task)
        self.assertEqual("context_specific_brainstorming", run["intent_frame"]["primary_paradigm"])
        self.assertEqual(
            "systematic_review",
            run["intent_frame"]["constraints"]["paper_type"],
        )
        self.assertNotIn("I'll treat", run["research_answer"]["markdown"])
        self.assertGreaterEqual(run["diagnostics"]["tool_call_count"], 2)
        self.assertIn(
            "search_paper_candidates",
            [
                call["tool_name"]
                for trace in run["stage_trace"]
                for call in trace["tool_calls"]
            ],
        )
        self.assertIn(
            "find_reading_locations",
            [
                call["tool_name"]
                for trace in run["stage_trace"]
                for call in trace["tool_calls"]
            ],
        )
        self.assertTrue(all("available_papers" not in payload for payload in model.request_payloads))
        self.assertTrue(
            all(
                "scope_paper_ids" not in payload["conversation_context"]
                for payload in model.request_payloads
                if "conversation_context" in payload
            )
        )

    def test_conversation_state_round_trips_to_json(self) -> None:
        state = ConversationState.new("round_trip", ["paper_a"])
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "state.json"

            state.save(path)
            loaded = ConversationState.load(path)

        self.assertEqual(state, loaded)

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

        self.assertEqual(
            [
                {"position": 1, "paper_id": "paper_a", "title": "Paper A"},
                {"position": 2, "paper_id": "paper_b", "title": "Paper B"},
            ],
            context["selected_paper_refs"],
        )
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
            "claim_graph": {"claims": []},
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
            self.assertTrue((first_case / "intent_frame.json").exists())
            self.assertTrue((first_case / "stage_trace.json").exists())
            self.assertTrue((first_case / "evidence_ledger.json").exists())
            self.assertTrue((first_case / "verification_pass.json").exists())

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
        self.assertTrue(any("assistant [COMPLETED]" in output for output in outputs))
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
            "parser_evidence": {
                "verification_status": "verified",
                "matched_text": "structured value forty two",
                "page": 1,
                "source_kind": "reading_element",
            },
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


class _NoEvidenceStageModel(_ExactFactHarnessModel):
    def complete(self, messages, tools, max_tokens):
        system = str(messages[0].get("content") or "")
        if "SEMANTIC_STAGE:locate_exact_evidence" in system:
            return ChatTurn(content=json.dumps({
                "status": "completed",
                "decision_summary": "Claimed completion without evidence.",
                "accepted_evidence_ids": [],
            }))
        return super().complete(messages, tools, max_tokens)


class _ActionFirstRecommendationModel(ChatModel):
    def __init__(self) -> None:
        self.request_payloads: list[dict] = []

    def complete(self, messages, tools, max_tokens):
        system = str(messages[0].get("content") or "")
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
                    "assumption": "",
                    "blocking_reason": None,
                    "direct_reply": "Hi. What would you like to research?",
                    "pending_interaction": None,
                    "requires_corpus_observation": False,
                    "required_evidence_types": [],
                    "required_capabilities": [],
                }
            elif context.get("pending_interaction"):
                arguments = {
                    "route": "research",
                    "effective_goal": "Recommend systematic-review papers from the current corpus.",
                    "task": {"verb": "recommend", "object": "papers"},
                    "constraints": {"paper_type": "systematic_review"},
                    "primary_paradigm": "context_specific_brainstorming",
                    "answer_shape": "constraint_filter",
                    "assumption": "Use broad corpus coverage within the selected paper type.",
                    "blocking_reason": None,
                    "direct_reply": "",
                    "pending_interaction": None,
                    "requires_corpus_observation": True,
                    "required_evidence_types": ["metadata", "paragraph"],
                    "required_capabilities": ["candidate_retrieval", "evidence_retrieval"],
                }
            else:
                arguments = {
                    "route": "research",
                    "effective_goal": "Recommend a representative set of important papers from the current corpus.",
                    "task": {"verb": "recommend", "object": "papers"},
                    "constraints": {},
                    "primary_paradigm": "context_specific_brainstorming",
                    "answer_shape": "constraint_filter",
                    "assumption": "Important means representative and methodologically influential within this corpus.",
                    "blocking_reason": None,
                    "direct_reply": "",
                    "pending_interaction": None,
                    "requires_corpus_observation": True,
                    "required_evidence_types": ["metadata", "paragraph"],
                    "required_capabilities": ["candidate_retrieval", "evidence_retrieval"],
                }
            return ChatTurn(content="", tool_calls=[ToolCall(
                id="turn_decision",
                name="submit_turn_decision",
                arguments=arguments,
            )])
        if "INTENT_RECOGNITION" in system:
            self.request_payloads.append(json.loads(messages[-1]["content"]))
            return ChatTurn(content=json.dumps({
                "primary_paradigm": "context_specific_brainstorming",
                "normalized_goal": "Recommend a broad, evidence-backed set from the corpus.",
                "answer_shape": "constraint_filter",
                "paper_references": [],
                "requested_aspects": ["representative coverage"],
                "constraints": {"scope": "broad"},
                "ambiguity": {"status": "ambiguous"},
                "actionability": "non_blocking",
                "requires_corpus_observation": True,
                "confidence": 1.0,
                "required_evidence_types": ["metadata", "paragraph"],
                "required_capabilities": ["candidate_retrieval", "evidence_retrieval"],
            }))

        self.request_payloads.append(json.loads(messages[-1]["content"]))
        stage_name = system.split("SEMANTIC_STAGE:", 1)[1].splitlines()[0].strip()
        tool_messages = [message for message in messages if message.get("role") == "tool"]
        tool_names = {
            tool["function"]["name"]
            for tool in tools
        }
        if stage_name in {"resolve_candidate_methods", "select_candidates"}:
            if not tool_messages:
                return ChatTurn(content="", tool_calls=[ToolCall(
                    id="candidate_list",
                    name="search_paper_candidates",
                    arguments={"query_text": "synthetic", "limit": 4},
                )])
            return _stage_turn(selected_paper_ids=["synthetic_paper"])
        if stage_name == "select_and_ground_candidates":
            if not tool_messages:
                return ChatTurn(content="", tool_calls=[ToolCall(
                    id="candidate_list",
                    name="search_paper_candidates",
                    arguments={"query_text": "synthetic", "limit": 4},
                )])
            if tool_messages[-1].get("name") == "search_paper_candidates":
                return ChatTurn(content="", tool_calls=[ToolCall(
                    id="recommendation_evidence",
                    name="find_reading_locations",
                    arguments={"query_text": "structured value", "paper_ids": ["synthetic_paper"]},
                )])
            if tool_messages[-1].get("name") == "find_reading_locations":
                locations = json.loads(tool_messages[-1]["content"])["locations"]
                return ChatTurn(content="", tool_calls=[ToolCall(
                    id="read_recommendation_evidence",
                    name="read_locations",
                    arguments={"location_refs": [item["location_ref"] for item in locations]},
                )])
            evidence_ids = [
                item["evidence_id"]
                for message in tool_messages
                for item in json.loads(message["content"]).get("items", [])
            ]
            return _stage_turn(
                selected_paper_ids=["synthetic_paper"],
                accepted_evidence_ids=evidence_ids,
            )
        if stage_name in {"retrieve_transfer_relevant_evidence", "ground_candidates"}:
            if not tool_messages:
                return ChatTurn(content="", tool_calls=[ToolCall(
                    id="recommendation_evidence",
                    name="find_reading_locations",
                    arguments={"query_text": "structured value", "paper_ids": ["synthetic_paper"]},
                )])
            if tool_messages[-1].get("name") == "find_reading_locations":
                locations = json.loads(tool_messages[-1]["content"])["locations"]
                return ChatTurn(content="", tool_calls=[ToolCall(
                    id="read_recommendation_evidence",
                    name="read_locations",
                    arguments={"location_refs": [item["location_ref"] for item in locations]},
                )])
            evidence_ids = [
                item["evidence_id"]
                for message in tool_messages
                for item in json.loads(message["content"]).get("items", [])
            ]
            return _stage_turn(
                selected_paper_ids=["synthetic_paper"],
                accepted_evidence_ids=evidence_ids,
            )
        if stage_name in {"present_contextual_options", "recommend"}:
            evidence_id = next(
                item["evidence_id"]
                for item in json.loads(messages[-1]["content"])["research_state"]["evidence"]
                if item["element_type"] == "paragraph"
            )
            return _stage_turn(
                claims=[{
                    "claim_id": "recommendation_claim",
                    "text": "Synthetic Paper is relevant to the requested broad coverage.",
                    "status": "supported",
                    "supporting_evidence_ids": [evidence_id],
                }],
                answer={
                    "status": "COMPLETED",
                    "answer_type": "constraint_filter",
                    "summary": "Synthetic Paper is the available representative recommendation.",
                    "markdown": "Synthetic Paper is the available representative recommendation.",
                    "cited_claim_ids": ["recommendation_claim"],
                    "cited_evidence_ids": [evidence_id],
                },
            )
        return _stage_turn(state_values={"stage": stage_name, "tools": sorted(tool_names)})


def _stage_turn(**values) -> ChatTurn:
    return ChatTurn(content=json.dumps({
        "status": "completed",
        "decision_summary": "Stage completed for the action-first recommendation regression.",
        **values,
    }))


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
                "cited_claim_ids": [f"claim_{turn}"],
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
            "claim_graph": {
                "claims": [{
                    "claim_id": f"claim_{turn}",
                    "text": f"Shell claim {turn}.",
                    "status": "supported",
                    "supporting_evidence_ids": [evidence_id],
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
