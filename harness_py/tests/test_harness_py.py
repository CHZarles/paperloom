from __future__ import annotations

import json
import base64
import threading
import tempfile
import unittest
from copy import deepcopy
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

import yaml
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from harness_py.agent_harness import ResearchAgentHarness
from harness_py.conversation import ConversationState
from harness_py.dataset import load_artifact_contracts, load_dataset
from harness_py.harness import ContractDrivenHarness
from harness_py.live_chat import LiveResearchChatHarness
from harness_py.llm import ChatModel, ChatTurn, MiniMaxChatModel, ToolCall
from harness_py.models import GoldenDataset
from harness_py.provider_config import ProviderConfig, decrypt_provider_key
from harness_py.product_db_dataset import build_product_dataset, summarize_product_corpus
from harness_py.scoring import TraceScorer
from harness_py.tools import ReadingCorpusTools


class PythonHarnessPrototypeTest(unittest.TestCase):
    def test_committed_seed_dataset_runs_without_failed_cases(self) -> None:
        dataset = load_dataset("research/golden-data/manifest.yaml")
        contracts = load_artifact_contracts("research/golden-data/artifact-contracts.yaml")
        harness = ContractDrivenHarness()
        runs = [harness.run_case(dataset, case) for case in dataset.cases]

        report = TraceScorer(contracts).score_dataset(dataset, runs)

        self.assertEqual(7, report["case_count"])
        self.assertEqual(0, report["failed_count"], report)
        self.assertTrue(all("intent_frame" in run for run in runs))
        self.assertTrue(all("evidence_ledger" in run for run in runs))
        self.assertTrue(all("verification_pass" in run for run in runs))

    def test_synthetic_dataset_proves_harness_is_data_driven(self) -> None:
        dataset = self._synthetic_dataset()
        case = dataset.cases[0]
        run = ContractDrivenHarness().run_case(dataset, case)
        score = TraceScorer().score_case(dataset, case, run)

        self.assertTrue(score.passed, score.to_dict())
        self.assertEqual("synthetic_anchor", run["evidence_ledger"]["items"][0]["matched_anchor_id"])
        self.assertEqual("42", run["research_answer"]["fields"]["answer"])

    def test_scorer_rejects_supported_claim_without_evidence(self) -> None:
        dataset = self._synthetic_dataset()
        case = dataset.cases[0]
        run = ContractDrivenHarness().run_case(dataset, case)
        broken = deepcopy(run)
        broken["claim_graph"]["claims"][0]["supporting_evidence_ids"] = []

        score = TraceScorer().score_case(dataset, case, broken)

        self.assertFalse(score.passed)
        self.assertIn("SUPPORTED_CLAIM_WITHOUT_EVIDENCE:claim_answer", score.errors)

    def test_agent_harness_executes_tools_and_scores_synthetic_case(self) -> None:
        dataset = self._synthetic_dataset()
        case = dataset.cases[0]

        run = ResearchAgentHarness(_ToolUsingFakeModel()).run_case(dataset, case)
        score = TraceScorer().score_case(dataset, case, run)

        self.assertTrue(score.passed, score.to_dict())
        self.assertEqual(1, run["diagnostics"]["tool_call_count"])
        self.assertEqual("synthetic_anchor", run["evidence_ledger"]["items"][0]["matched_anchor_id"])
        self.assertEqual("COMPLETED", run["research_answer"]["status"])

    def test_agent_harness_repairs_unparseable_final_json(self) -> None:
        dataset = self._synthetic_dataset()
        case = dataset.cases[0]

        run = ResearchAgentHarness(_RepairingFakeModel(), max_turns=4).run_case(dataset, case)

        self.assertEqual("COMPLETED", run["research_answer"]["status"])
        self.assertEqual("final_json", run["diagnostics"]["finish_reason"])

    def test_agent_harness_forces_final_json_after_tool_budget(self) -> None:
        dataset = self._synthetic_dataset()
        case = dataset.cases[0]

        run = ResearchAgentHarness(_ForcedFinalFakeModel(), max_turns=1).run_case(dataset, case)

        self.assertEqual("COMPLETED", run["research_answer"]["status"])
        self.assertEqual("forced_final_json", run["diagnostics"]["finish_reason"])

    def test_agent_harness_downgrades_supported_claim_without_tool_evidence(self) -> None:
        dataset = self._synthetic_dataset()
        case = dataset.cases[0]

        run = ResearchAgentHarness(_NoEvidenceFakeModel()).run_case(dataset, case)

        self.assertEqual("INCOMPLETE_PRECISE", run["research_answer"]["status"])
        self.assertEqual(1, run["verification_pass"]["unsupported_claim_count"])

    def test_minimax_client_sends_openai_compatible_tool_request(self) -> None:
        captured = {}

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):  # noqa: N802
                length = int(self.headers["Content-Length"])
                captured["path"] = self.path
                captured["authorization"] = self.headers.get("Authorization")
                captured["body"] = json.loads(self.rfile.read(length).decode("utf-8"))
                body = {
                    "choices": [{
                        "message": {
                            "content": "",
                            "tool_calls": [{
                                "id": "call_1",
                                "type": "function",
                                "function": {
                                    "name": "list_papers",
                                    "arguments": "{\"query\":\"synthetic\"}",
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
            turn = MiniMaxChatModel(provider, timeout_seconds=5).complete(
                [{"role": "user", "content": "hi"}],
                [{"type": "function", "function": {"name": "list_papers", "parameters": {"type": "object"}}}],
                16,
            )
        finally:
            server.shutdown()
            server.server_close()

        self.assertEqual("/v1/chat/completions", captured["path"])
        self.assertEqual("Bearer secret", captured["authorization"])
        self.assertEqual({"type": "disabled"}, captured["body"]["thinking"])
        self.assertEqual("list_papers", turn.tool_calls[0].name)
        self.assertEqual({"query": "synthetic"}, turn.tool_calls[0].arguments)

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

        result = ReadingCorpusTools(dataset).search_reading_locations({
            "query": "evaluation dimensions",
            "paper_ids": ["paper_live"],
            "top_k": 1,
        })

        self.assertEqual(["Ada Example", "Bo Example"], dataset.paper_records_by_id["paper_live"]["identity"]["authors"])
        self.assertEqual(1, summarize_product_corpus(dataset).reading_element_count)
        self.assertEqual("paper_live", result["results"][0]["paper_id"])
        self.assertEqual("paper_live:p1:el1", result["results"][0]["location"])

    def test_live_chat_harness_reuses_agent_trace_shape_without_golden_case(self) -> None:
        dataset = self._synthetic_dataset()

        run = LiveResearchChatHarness(_ToolUsingFakeModel()).run_question(dataset, "What is the synthetic answer?")

        self.assertEqual("COMPLETED", run["research_answer"]["status"])
        self.assertTrue(run["case_id"].startswith("live_chat_"))
        self.assertIn("intent_frame", run)
        self.assertIn("evidence_ledger", run)
        self.assertIn("verification_pass", run)

    def test_live_conversation_harness_carries_memory_across_turns(self) -> None:
        dataset = self._synthetic_dataset()
        model = _ConversationMemoryFakeModel()
        harness = LiveResearchChatHarness(model)
        state = ConversationState.new("conversation_test")

        first_run, state = harness.run_turn(dataset, state, "What is the synthetic answer?")
        second_run, state = harness.run_turn(dataset, state, "Use the same evidence again.")

        self.assertEqual("COMPLETED", first_run["research_answer"]["status"])
        self.assertEqual("COMPLETED", second_run["research_answer"]["status"])
        self.assertEqual(2, state.turn_index)
        self.assertEqual(["synthetic_paper"], state.selected_paper_ids)
        self.assertEqual([model.first_evidence_id], state.selected_evidence_ids)
        self.assertEqual(model.first_evidence_id, second_run["diagnostics"]["tool_trace"][0]["arguments"]["evidence_ids"][0])
        self.assertEqual([model.first_evidence_id], model.second_context["selected_evidence_ids"])
        self.assertEqual(model.first_evidence_id, model.second_context["selected_evidence_refs"][0]["evidence_id"])
        self.assertEqual(4, len(state.message_history))

    def test_conversation_state_round_trips_to_json(self) -> None:
        state = ConversationState.new("round_trip", ["paper_a"])
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "state.json"

            state.save(path)
            loaded = ConversationState.load(path)

        self.assertEqual(state, loaded)

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
            "id": "synthetic_case",
            "question": {"text": "What is the synthetic answer?"},
            "capability_tags": ["precision_fact_extraction"],
            "expected_result": {"kind": "answered", "answer_type": "exact_fact"},
            "expected_intent": {
                "entities": ["synthetic entity"],
                "ambiguity_status": "unambiguous",
                "required_evidence_types": ["paragraph"],
            },
            "expected_retrieval_plan": {"required_strategies": ["lexical_search"]},
            "corpus_scope": {
                "required_paper_ids": ["synthetic_paper"],
                "allowed_paper_ids": ["synthetic_paper"],
            },
            "gold_evidence": {"required_anchor_ids": ["synthetic_anchor"], "forbidden_anchor_ids": []},
            "gold_claims": [{
                "claim_id": "claim_answer",
                "required": True,
                "canonical_text": "The synthetic answer is 42.",
                "expected_status": "supported",
                "support_anchor_ids": ["synthetic_anchor"],
                "exact_value": "42",
            }],
            "answer_contract": {
                "type": "exact_fact",
                "required_fields": {"answer": "42"},
            },
            "required_trace": {
                "obligations": [{
                    "id": "retrieve_synthetic_anchor",
                    "phase": "evidence",
                    "severity": "critical",
                    "must_include_anchor_ids": ["synthetic_anchor"],
                }]
            },
        }
        return GoldenDataset(
            root=Path("."),
            manifest_path=Path("synthetic.yaml"),
            manifest=yaml.safe_load("dataset_id: synthetic\n"),
            paper_packs=[],
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


class _ToolUsingFakeModel(ChatModel):
    def complete(self, messages, tools, max_tokens):
        tool_messages = [message for message in messages if message.get("role") == "tool"]
        if not tool_messages:
            return ChatTurn(
                content="",
                tool_calls=[
                    ToolCall(
                        id="call_1",
                        name="search_reading_locations",
                        arguments={"query": "forty two", "paper_ids": ["synthetic_paper"], "top_k": 3},
                    )
                ],
            )
        tool_result = json.loads(tool_messages[-1]["content"])
        evidence_id = tool_result["results"][0]["evidence_id"]
        payload = {
            "intent_frame": {
                "answer_type": "exact_fact",
                "ambiguity_status": "unambiguous",
                "entities": ["synthetic entity"],
                "required_evidence_types": ["paragraph"],
                "required_capabilities": ["precision_fact_extraction"],
            },
            "claims": [{
                "claim_id": "claim_answer",
                "text": "The synthetic answer is 42.",
                "status": "supported",
                "supporting_evidence_ids": [evidence_id],
                "refuting_evidence_ids": [],
            }],
            "reasoning_artifact": {
                "type": "exact_fact_card",
                "title": "synthetic answer",
                "payload": {"source_evidence_ids": [evidence_id]},
            },
            "answer": {
                "status": "COMPLETED",
                "answer_type": "exact_fact",
                "summary": "The synthetic answer is 42.",
                "fields": {"answer": "42"},
                "sections": [],
                "cited_claim_ids": ["claim_answer"],
                "cited_evidence_ids": [evidence_id],
            },
        }
        return ChatTurn(content=json.dumps(payload))


class _RepairingFakeModel(ChatModel):
    def complete(self, messages, tools, max_tokens):
        tool_messages = [message for message in messages if message.get("role") == "tool"]
        if not tool_messages:
            return ChatTurn(
                content="",
                tool_calls=[
                    ToolCall(
                        id="call_1",
                        name="search_reading_locations",
                        arguments={"query": "forty two", "paper_ids": ["synthetic_paper"], "top_k": 3},
                    )
                ],
            )
        if not any("not parseable final JSON" in str(message.get("content")) for message in messages):
            return ChatTurn(content="The answer is 42.")
        evidence_id = json.loads(tool_messages[-1]["content"])["results"][0]["evidence_id"]
        return ChatTurn(content=json.dumps({
            "intent_frame": {
                "answer_type": "exact_fact",
                "ambiguity_status": "unambiguous",
                "entities": ["synthetic entity"],
                "required_evidence_types": ["paragraph"],
                "required_capabilities": ["precision_fact_extraction"],
            },
            "claims": [{
                "claim_id": "claim_answer",
                "text": "The synthetic answer is 42.",
                "status": "supported",
                "supporting_evidence_ids": [evidence_id],
                "refuting_evidence_ids": [],
            }],
            "reasoning_artifact": {
                "type": "exact_fact_card",
                "title": "synthetic answer",
                "payload": {"source_evidence_ids": [evidence_id]},
            },
            "answer": {
                "status": "OK",
                "answer_type": "exact_fact",
                "summary": "The synthetic answer is 42.",
                "fields": {"answer": "42"},
                "sections": [],
                "cited_claim_ids": ["claim_answer"],
                "cited_evidence_ids": [evidence_id],
            },
        }))


class _ForcedFinalFakeModel(ChatModel):
    def complete(self, messages, tools, max_tokens):
        tool_messages = [message for message in messages if message.get("role") == "tool"]
        if tools:
            return ChatTurn(
                content="",
                tool_calls=[
                    ToolCall(
                        id="call_1",
                        name="search_reading_locations",
                        arguments={"query": "forty two", "paper_ids": ["synthetic_paper"], "top_k": 3},
                    )
                ],
            )
        evidence_id = json.loads(tool_messages[-1]["content"])["results"][0]["evidence_id"]
        return ChatTurn(content=json.dumps({
            "intent_frame": {
                "answer_type": "exact_fact",
                "ambiguity_status": "unambiguous",
                "entities": ["synthetic entity"],
                "required_evidence_types": ["paragraph"],
                "required_capabilities": ["precision_fact_extraction"],
            },
            "claims": [{
                "claim_id": "claim_answer",
                "text": "The synthetic answer is 42.",
                "status": "supported",
                "supporting_evidence_ids": [evidence_id],
                "refuting_evidence_ids": [],
            }],
            "reasoning_artifact": {
                "type": "exact_fact_card",
                "title": "synthetic answer",
                "payload": {"source_evidence_ids": [evidence_id]},
            },
            "answer": {
                "status": "COMPLETED",
                "answer_type": "exact_fact",
                "summary": "The synthetic answer is 42.",
                "fields": {"answer": "42"},
                "sections": [],
                "cited_claim_ids": ["claim_answer"],
                "cited_evidence_ids": [evidence_id],
            },
        }))


class _NoEvidenceFakeModel(ChatModel):
    def complete(self, messages, tools, max_tokens):
        return ChatTurn(content=json.dumps({
            "intent_frame": {
                "answer_type": "exact_fact",
                "ambiguity_status": "unambiguous",
                "entities": ["synthetic entity"],
                "required_evidence_types": ["paragraph"],
                "required_capabilities": ["precision_fact_extraction"],
            },
            "claims": [{
                "claim_id": "claim_answer",
                "text": "The synthetic answer is 42.",
                "status": "supported",
                "supporting_evidence_ids": ["fake_evidence"],
                "refuting_evidence_ids": [],
            }],
            "reasoning_artifact": {
                "type": "exact_fact_card",
                "title": "synthetic answer",
                "payload": {},
            },
            "answer": {
                "status": "COMPLETED",
                "answer_type": "exact_fact",
                "summary": "The synthetic answer is 42.",
                "fields": {"answer": "42"},
                "sections": [],
                "cited_claim_ids": ["claim_answer"],
                "cited_evidence_ids": ["fake_evidence"],
            },
        }))


class _ConversationMemoryFakeModel(ChatModel):
    def __init__(self):
        self.completed_turns = 0
        self.first_evidence_id = ""
        self.second_context = {}

    def complete(self, messages, tools, max_tokens):
        tool_messages = [message for message in messages if message.get("role") == "tool"]
        if not tool_messages:
            if self.completed_turns == 0:
                return ChatTurn(
                    content="",
                    tool_calls=[
                        ToolCall(
                            id="call_1",
                            name="search_reading_locations",
                            arguments={"query": "forty two", "paper_ids": ["synthetic_paper"], "top_k": 3},
                        )
                    ],
                )
            user_payload = json.loads(messages[-1]["content"])
            self.second_context = user_payload["conversation_context"]
            return ChatTurn(
                content="",
                tool_calls=[
                    ToolCall(
                        id="call_2",
                        name="read_locations",
                        arguments={"evidence_ids": self.second_context["selected_evidence_ids"]},
                    )
                ],
            )
        tool_payload = json.loads(tool_messages[-1]["content"])
        if self.completed_turns == 0:
            evidence_id = tool_payload["results"][0]["evidence_id"]
            self.first_evidence_id = evidence_id
            self.completed_turns = 1
            return ChatTurn(content=json.dumps(self._payload(evidence_id, "first turn")))
        evidence_id = tool_payload["items"][0]["evidence_id"]
        self.completed_turns = 2
        return ChatTurn(content=json.dumps(self._payload(evidence_id, "follow-up turn")))

    def _payload(self, evidence_id, note):
        return {
            "intent_frame": {
                "answer_type": "exact_fact",
                "ambiguity_status": "unambiguous",
                "entities": ["synthetic entity"],
                "required_evidence_types": ["paragraph"],
                "required_capabilities": ["precision_fact_extraction"],
            },
            "claims": [{
                "claim_id": "claim_answer",
                "text": "The synthetic answer is 42.",
                "status": "supported",
                "supporting_evidence_ids": [evidence_id],
                "refuting_evidence_ids": [],
            }],
            "reasoning_artifact": {
                "type": "exact_fact_card",
                "title": "synthetic answer",
                "payload": {"source_evidence_ids": [evidence_id]},
            },
            "answer": {
                "status": "COMPLETED",
                "answer_type": "exact_fact",
                "summary": "The synthetic answer is 42.",
                "fields": {"answer": "42"},
                "sections": [],
                "cited_claim_ids": ["claim_answer"],
                "cited_evidence_ids": [evidence_id],
            },
            "memory_update": {
                "selected_paper_ids": ["synthetic_paper"],
                "selected_evidence_ids": [evidence_id],
                "note": note,
            },
        }


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
