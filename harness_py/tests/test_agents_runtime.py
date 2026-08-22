from __future__ import annotations

import json
import tempfile
import threading
import unittest
from collections.abc import AsyncIterator
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import AsyncMock, patch

from agents import Model, ModelResponse, ModelSettings, ModelTracing, Usage
from agents.run_config import CallModelData, ModelInputData
from openai.types.responses import ResponseFunctionToolCall

from harness_py.orchestration.agents.runtime import (
    AgentsSdkHarnessRuntime,
    _latest_final_submission_only,
)
from harness_py.orchestration.agents.model import (
    DIAGNOSTIC_FALLBACK_HINT,
    TEXT_NUDGE_TOOL_NAME,
    TOOL_ARGUMENT_REPAIR_PREFIX,
)
from harness_py.orchestration.conversation import ConversationState
from harness_py.orchestration.live_chat import LiveResearchChatHarness
from harness_py.utils.errors import HarnessCancelled, RunLimitExceeded
from harness_py.transport.provider_config import ProviderConfig
from harness_py.tests import test_harness_py as _harness_tests


class AgentsRuntimeTest(unittest.TestCase):
    def test_model_input_keeps_only_latest_rejected_final_submission(self) -> None:
        research_call = {"type": "function_call", "name": "read_paper_content", "call_id": "read_1"}
        research_output = {"type": "function_call_output", "call_id": "read_1", "output": "evidence"}
        first_final = {"type": "function_call", "name": "submit_catalog_answer", "call_id": "final_1"}
        first_error = {"type": "function_call_output", "call_id": "final_1", "output": "rejected"}
        latest_final = {"type": "function_call", "name": "submit_research_answer", "call_id": "final_2"}
        latest_error = {"type": "function_call_output", "call_id": "final_2", "output": "rejected"}

        filtered = _latest_final_submission_only(CallModelData(
            model_data=ModelInputData(
                input=[
                    research_call,
                    research_output,
                    first_final,
                    first_error,
                    latest_final,
                    latest_error,
                ],
                instructions="research",
            ),
            agent=None,  # type: ignore[arg-type]
            context=None,
        ))

        self.assertEqual(
            [research_call, research_output, latest_final, latest_error],
            filtered.input,
        )

    def test_model_input_drops_a_superseded_plain_text_draft_after_submission(self) -> None:
        research_call = {"type": "function_call", "name": "read_paper_content", "call_id": "read_1"}
        research_output = {"type": "function_call_output", "call_id": "read_1", "output": "evidence"}
        draft_call = {
            "type": "function_call",
            "name": TEXT_NUDGE_TOOL_NAME,
            "call_id": "draft_1",
            "arguments": json.dumps({"content": "Complete draft."}),
        }
        draft_output = {"type": "function_call_output", "call_id": "draft_1", "output": "finalize it"}
        final_call = {"type": "function_call", "name": "submit_research_answer", "call_id": "final_1"}
        final_error = {"type": "function_call_output", "call_id": "final_1", "output": "rejected"}

        filtered = _latest_final_submission_only(CallModelData(
            model_data=ModelInputData(
                input=[research_call, research_output, draft_call, draft_output, final_call, final_error],
                instructions="research",
            ),
            agent=None,  # type: ignore[arg-type]
            context=None,
        ))

        self.assertEqual([research_call, research_output, final_call, final_error], filtered.input)

    def test_model_input_keeps_only_the_latest_plain_text_draft_before_submission(self) -> None:
        first_call = {
            "type": "function_call",
            "name": TEXT_NUDGE_TOOL_NAME,
            "call_id": "draft_1",
            "arguments": json.dumps({"content": "First draft."}),
        }
        first_output = {"type": "function_call_output", "call_id": "draft_1", "output": "finalize it"}
        latest_call = {
            "type": "function_call",
            "name": TEXT_NUDGE_TOOL_NAME,
            "call_id": "draft_2",
            "arguments": json.dumps({"content": "Latest draft."}),
        }
        latest_output = {"type": "function_call_output", "call_id": "draft_2", "output": "finalize it"}

        filtered = _latest_final_submission_only(CallModelData(
            model_data=ModelInputData(
                input=[first_call, first_output, latest_call, latest_output],
                instructions="research",
            ),
            agent=None,  # type: ignore[arg-type]
            context=None,
        ))

        self.assertEqual([latest_call, latest_output], filtered.input)

    def test_model_input_keeps_a_later_malformed_argument_repair(self) -> None:
        final_call = {"type": "function_call", "name": "submit_research_answer", "call_id": "final_1"}
        final_error = {"type": "function_call_output", "call_id": "final_1", "output": "rejected"}
        repair_call = {
            "type": "function_call",
            "name": TEXT_NUDGE_TOOL_NAME,
            "call_id": "repair_1",
            "arguments": json.dumps({"content": TOOL_ARGUMENT_REPAIR_PREFIX + "Retry valid JSON."}),
        }
        repair_output = {"type": "function_call_output", "call_id": "repair_1", "output": "retry"}

        filtered = _latest_final_submission_only(CallModelData(
            model_data=ModelInputData(
                input=[final_call, final_error, repair_call, repair_output],
                instructions="research",
            ),
            agent=None,  # type: ignore[arg-type]
            context=None,
        ))

        self.assertEqual([final_call, final_error, repair_call, repair_output], filtered.input)

    def test_sdk_runtime_executes_stateful_tools_and_validated_final_submission(self) -> None:
        dataset = _harness_tests.PythonHarnessPrototypeTest()._synthetic_dataset()
        progress: list[dict] = []
        with tempfile.TemporaryDirectory() as tmp:
            harness = LiveResearchChatHarness(
                AgentsSdkHarnessRuntime(model=_ScriptedAgentsModel()),
                eval_dump_dir=tmp,
            )

            run, state = harness.run_turn(
                dataset,
                ConversationState.new("agents_sdk_test"),
                "What is the synthetic answer?",
                progress_listener=progress.append,
            )
            run_dir = Path(tmp) / run["run_id"]
            events = [
                json.loads(line)
                for line in (run_dir / "events.jsonl").read_text(encoding="utf-8").splitlines()
            ]
            result = json.loads((run_dir / "result.json").read_text(encoding="utf-8"))

        self.assertEqual("COMPLETED", run["status"])
        self.assertEqual("42", run["research_answer"]["fields"]["answer"])
        self.assertEqual(1, len(run["research_answer"]["cited_source_quote_refs"]))
        self.assertEqual("python_openai_agents_sdk_harness_v2", run["harness_id"])
        self.assertEqual("RESEARCH", run["research_answer"]["answer_contract"])
        self.assertTrue(run["run_id"].startswith("run_"))
        self.assertEqual(1, state.turn_index)
        self.assertIn("answer.validation", _event_kinds(progress, run))
        submissions = [
            item for item in run["react_trace"]
            if item.get("tool_name") == "submit_research_answer"
        ]
        self.assertEqual(2, len(submissions))
        self.assertFalse(submissions[0]["result"]["accepted"])
        self.assertIn("UNKNOWN_SOURCE_REF", submissions[0]["result"]["issue_codes"])
        self.assertTrue(submissions[1]["result"]["accepted"])
        self.assertIn("run.started", {event["kind"] for event in events})
        self.assertIn("tool.completed", {event["kind"] for event in events})
        self.assertIn("protocol.transition", {event["kind"] for event in events})
        self.assertTrue(result["capture_ok"])
        self.assertEqual(run["run_id"], result["result"]["run_id"])

    def test_runtime_recovers_after_provider_truncates_tool_arguments(self) -> None:
        requests: list[dict] = []

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self) -> None:
                length = int(self.headers.get("Content-Length") or 0)
                request = json.loads(self.rfile.read(length).decode("utf-8"))
                requests.append(request)
                for message in request.get("messages", []):
                    for tool_call in message.get("tool_calls", []):
                        json.loads(tool_call["function"]["arguments"])

                if len(requests) == 1:
                    arguments = '{"outcome":"answered","markdown":"truncated'
                    finish_reason = "length"
                else:
                    arguments = json.dumps({
                        "outcome": "answered",
                        "markdown": "Hello. How can I help?",
                    })
                    finish_reason = "tool_calls"
                body = json.dumps({
                    "id": f"response_{len(requests)}",
                    "object": "chat.completion",
                    "created": len(requests),
                    "model": "MiniMax-M3",
                    "choices": [{
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": "",
                            "tool_calls": [{
                                "id": f"call_{len(requests)}",
                                "type": "function",
                                "function": {
                                    "name": "submit_research_answer" if len(requests) == 1 else "submit_direct_answer",
                                    "arguments": arguments,
                                },
                            }],
                        },
                        "finish_reason": finish_reason,
                    }],
                    "usage": {
                        "prompt_tokens": 10,
                        "completion_tokens": 5,
                        "total_tokens": 15,
                    },
                }).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, format, *args) -> None:
                return

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        provider = ProviderConfig(
            scope="llm",
            provider="minimax",
            api_style="openai-compatible",
            api_base_url=f"http://127.0.0.1:{server.server_port}/v1",
            model="MiniMax-M3",
            api_key="test-key",
        )
        dataset = _harness_tests.PythonHarnessPrototypeTest()._synthetic_dataset()
        harness = LiveResearchChatHarness(AgentsSdkHarnessRuntime(provider=provider))

        try:
            run, _ = harness.run_turn(
                dataset,
                ConversationState.new("malformed_arguments"),
                "Hello",
            )
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

        self.assertEqual(2, len(requests), run["diagnostics"])
        self.assertEqual(1, run["diagnostics"]["provider_protocol_repair_count"])
        self.assertTrue(all(
            "_continue_research_turn" not in {
                tool["function"]["name"]
                for tool in request.get("tools", [])
            }
            for request in requests
        ))
        self.assertEqual("COMPLETED", run["status"], run["diagnostics"])
        self.assertEqual(
            "Hello. How can I help?",
            run["research_answer"]["markdown"],
        )
        self.assertEqual("DIRECT", run["research_answer"]["answer_contract"])

    def test_runtime_recovers_a_plain_text_clarification_with_an_explicit_submission_nudge(self) -> None:
        requests: list[dict] = []
        clarification = "请告诉我你想了解 vLLM 的哪个具体方面。"

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self) -> None:
                length = int(self.headers.get("Content-Length") or 0)
                request = json.loads(self.rfile.read(length).decode("utf-8"))
                requests.append(request)
                tool_messages = [
                    json.loads(message["content"])
                    for message in request.get("messages", [])
                    if message.get("role") == "tool"
                ]
                repair_message = str(tool_messages[-1].get("message") or "") if tool_messages else ""
                follows_nudge = (
                    "submit_direct_answer with outcome=needs_clarification" in repair_message
                    and "markdown argument" in repair_message
                )
                message = {
                    "role": "assistant",
                    "content": "" if follows_nudge else clarification,
                }
                if follows_nudge:
                    message["tool_calls"] = [{
                        "id": "call_submit_direct",
                        "type": "function",
                        "function": {
                            "name": "submit_direct_answer",
                            "arguments": json.dumps({
                                "outcome": "needs_clarification",
                                "markdown": clarification,
                            }, ensure_ascii=False),
                        },
                    }]
                body = json.dumps({
                    "id": f"response_{len(requests)}",
                    "object": "chat.completion",
                    "created": len(requests),
                    "model": "MiniMax-M3",
                    "choices": [{
                        "index": 0,
                        "message": message,
                        "finish_reason": "tool_calls" if follows_nudge else "stop",
                    }],
                    "usage": {
                        "prompt_tokens": 10,
                        "completion_tokens": 5,
                        "total_tokens": 15,
                    },
                }, ensure_ascii=False).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, format, *args) -> None:
                return

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        provider = ProviderConfig(
            scope="llm",
            provider="minimax",
            api_style="openai-compatible",
            api_base_url=f"http://127.0.0.1:{server.server_port}/v1",
            model="MiniMax-M3",
            api_key="test-key",
        )
        harness = LiveResearchChatHarness(AgentsSdkHarnessRuntime(provider=provider))

        try:
            run, _ = harness.run_turn(
                _harness_tests.PythonHarnessPrototypeTest()._synthetic_dataset(),
                ConversationState.new("plain_text_clarification"),
                "比如",
            )
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

        self.assertEqual(2, len(requests), run["diagnostics"])
        self.assertEqual("NEEDS_CLARIFICATION", run["status"], run["diagnostics"])
        self.assertEqual(1, run["diagnostics"]["provider_protocol_repair_count"])
        self.assertEqual(clarification, run["research_answer"]["markdown"])
        self.assertEqual("DIRECT", run["research_answer"]["answer_contract"])
        self.assertEqual(0, run["diagnostics"]["diagnostic_model_calls"])

    def test_runtime_diagnoses_the_second_plain_text_response_once(self) -> None:
        repair_hint = "Call submit_direct_answer now; do not emit assistant content."
        result = _run_plain_text_diagnosis_case(
            json.dumps({
                "diagnosis": "The model repeated text instead of using the required submission tool.",
                "repair_hint": repair_hint,
            }),
        )

        self.assertEqual("COMPLETED", result["run"]["status"], result["run"])
        self.assertEqual(4, len(result["requests"]))
        self.assertIn(repair_hint, result["applied_hint"])
        self.assertEqual(1, result["run"]["diagnostics"]["diagnostic_model_calls"])
        self.assertEqual(6, result["run"]["diagnostics"]["diagnostic_total_tokens"])
        self.assertEqual(51, result["run"]["control"]["usage"]["total_tokens"])
        self.assertTrue(result["run"]["diagnostics"]["diagnostic_repair_succeeded"])
        self.assertIn("message field is authoritative", result["applied_message"])
        self.assertIn("repairing_response", {event.get("type") for event in result["progress"]})
        self.assertTrue(
            {"diagnosis.started", "diagnosis.completed", "repair.applied"}
            <= {event["kind"] for event in result["events"]}
        )

    def test_runtime_applies_diagnosis_hint_to_an_empty_draft(self) -> None:
        repair_hint = "Return one valid submission Tool Call."
        result = _run_plain_text_diagnosis_case(
            json.dumps({
                "diagnosis": "Thinking tags contained no publishable response.",
                "repair_hint": repair_hint,
            }),
            plain_draft="<think>Internal reasoning only.</think>",
        )

        self.assertEqual("COMPLETED", result["run"]["status"], result["run"])
        self.assertEqual(repair_hint, result["applied_hint"])

    def test_runtime_uses_the_fixed_hint_when_diagnosis_returns_invalid_json(self) -> None:
        result = _run_plain_text_diagnosis_case("This is not JSON.")

        self.assertEqual("COMPLETED", result["run"]["status"], result["run"])
        self.assertEqual(4, len(result["requests"]))
        self.assertEqual(DIAGNOSTIC_FALLBACK_HINT, result["applied_hint"])
        self.assertIn("do not submit it yet: call read_paper_content", result["applied_message"])
        self.assertNotIn("submit_direct_answer", result["applied_hint"])
        self.assertEqual(6, result["run"]["diagnostics"]["diagnostic_total_tokens"])
        self.assertEqual(
            ["invalid_json"],
            [
                event["payload"]["failure_kind"]
                for event in result["events"]
                if event["kind"] == "diagnosis.failed"
            ],
        )

    def test_runtime_rejects_non_string_diagnostic_fields(self) -> None:
        result = _run_plain_text_diagnosis_case(json.dumps({
            "diagnosis": {"cause": "plain text"},
            "repair_hint": ["call submit_direct_answer"],
        }))

        self.assertEqual("COMPLETED", result["run"]["status"], result["run"])
        self.assertEqual(DIAGNOSTIC_FALLBACK_HINT, result["applied_hint"])
        self.assertEqual(
            ["invalid_json"],
            [
                event["payload"]["failure_kind"]
                for event in result["events"]
                if event["kind"] == "diagnosis.failed"
            ],
        )

    def test_runtime_uses_the_fixed_hint_when_diagnosis_provider_fails(self) -> None:
        result = _run_plain_text_diagnosis_case("", diagnostic_status=503)

        self.assertEqual("COMPLETED", result["run"]["status"], result["run"])
        self.assertEqual(4, len(result["requests"]))
        self.assertEqual(DIAGNOSTIC_FALLBACK_HINT, result["applied_hint"])
        self.assertEqual(0, result["run"]["diagnostics"]["diagnostic_total_tokens"])
        self.assertEqual(
            ["provider_5xx"],
            [
                event["payload"]["failure_kind"]
                for event in result["events"]
                if event["kind"] == "diagnosis.failed"
            ],
        )

    def test_runtime_fails_immediately_when_plain_text_continues_after_diagnosis(self) -> None:
        result = _run_plain_text_diagnosis_case(
            json.dumps({
                "diagnosis": "The function-call protocol was ignored.",
                "repair_hint": "Use one submission tool call only.",
            }),
            post_diagnosis_plain=True,
        )

        self.assertEqual("FAILED_TECHNICAL", result["run"]["status"])
        self.assertEqual("PROVIDER_TOOL_PROTOCOL_VIOLATION", result["run"]["control"]["reason_code"])
        self.assertEqual(1, result["run"]["diagnostics"]["diagnostic_model_calls"])
        self.assertEqual(6, result["run"]["diagnostics"]["diagnostic_total_tokens"])
        self.assertGreaterEqual(result["run"]["diagnostics"]["diagnostic_latency_ms"], 0)
        self.assertFalse(result["run"]["diagnostics"]["diagnostic_repair_succeeded"])
        self.assertEqual(4, len(result["requests"]))
        self.assertIn(
            "PLAIN_TEXT_RESPONSE_AFTER_DIAGNOSIS",
            [
                event["payload"].get("failure_kind")
                for event in result["events"]
                if event["kind"] == "model.error"
            ],
        )

    def test_cancelled_turn_returns_a_terminal_run(self) -> None:
        dataset = _harness_tests.PythonHarnessPrototypeTest()._synthetic_dataset()

        class CancelledRuntime:
            def run_turn(self, _turn):
                raise HarnessCancelled("cancelled")

        run, state = LiveResearchChatHarness(CancelledRuntime()).run_turn(
            dataset,
            ConversationState.new("cancelled_agents_test"),
            "Stop",
        )

        self.assertEqual("CANCELLED", run["status"])
        self.assertEqual("RUN_CANCELLED", run["control"]["reason_code"])
        self.assertEqual(1, state.turn_index)

    def test_failure_runs_preserve_diagnostic_metrics(self) -> None:
        dataset = _harness_tests.PythonHarnessPrototypeTest()._synthetic_dataset()
        cases = [
            (RunLimitExceeded("RUN_MODEL_CALL_LIMIT_EXCEEDED"), "LIMITED"),
            (HarnessCancelled("cancelled"), "CANCELLED"),
            (RuntimeError("unexpected"), "FAILED_TECHNICAL"),
        ]

        for error, expected_status in cases:
            with self.subTest(expected_status=expected_status):
                runtime = AgentsSdkHarnessRuntime(model=_LoopingAgentsModel())

                async def fail(context, failure=error):
                    context.diagnostic_model_calls = 1
                    context.record_diagnostic_usage(4, 2, 6, 7)
                    raise failure

                with patch.object(runtime, "_run_agent", new=AsyncMock(side_effect=fail)):
                    run, _ = LiveResearchChatHarness(runtime).run_turn(
                        dataset,
                        ConversationState.new(f"diagnostic_{expected_status}"),
                        "Hello",
                    )

                self.assertEqual(expected_status, run["status"])
                self.assertEqual(1, run["diagnostics"]["diagnostic_model_calls"])
                self.assertEqual(6, run["diagnostics"]["diagnostic_total_tokens"])
                self.assertEqual(7, run["diagnostics"]["diagnostic_latency_ms"])
                self.assertEqual(6, run["diagnostics"]["total_tokens"])

    def test_runtime_stops_before_a_seventeenth_model_turn(self) -> None:
        dataset = _harness_tests.PythonHarnessPrototypeTest()._synthetic_dataset()
        model = _LoopingAgentsModel()
        harness = LiveResearchChatHarness(AgentsSdkHarnessRuntime(model=model))

        run, _ = harness.run_turn(
            dataset,
            ConversationState.new("model_turn_limit"),
            "Keep researching forever.",
        )

        self.assertEqual("LIMITED", run["status"])
        self.assertEqual("RUN_MODEL_CALL_LIMIT_EXCEEDED", run["control"]["reason_code"])
        self.assertEqual(16, model.call_count)

class _ScriptedAgentsModel(Model):
    def __init__(self) -> None:
        self.call_count = 0

    async def get_response(
        self,
        system_instructions,
        input,
        model_settings,
        tools,
        output_schema,
        handoffs,
        tracing: ModelTracing,
        *,
        previous_response_id,
        conversation_id,
        prompt,
    ) -> ModelResponse:
        self.call_count += 1
        outputs = _tool_outputs(input)
        if self.call_count == 1:
            name, arguments = "get_research_skill", {"skill_id": "precision_fact_extraction"}
        elif self.call_count == 2:
            name, arguments = "find_papers_by_identity", {"paper_id": "synthetic_paper"}
        elif self.call_count == 3:
            name, arguments = "search_paper_content", {
                "paper_ids": ["synthetic_paper"],
                "query_text": "structured value",
                "top_k": 3,
            }
        elif self.call_count == 4:
            locations = outputs["call_3"]["locations"]
            name, arguments = "read_paper_content", {"location_refs": [locations[0]["location_ref"]]}
        elif self.call_count == 5:
            name, arguments = "submit_research_answer", {
                "outcome": "answered",
                "language": "EN",
                "markdown": "The structured value is 42. [[source_quote_fake]]",
                "fields": {"answer": "42"},
            }
        else:
            source_quote_ref = outputs["call_4"]["items"][0]["source_quotes"][0]["source_quote_ref"]
            name, arguments = "submit_research_answer", {
                "outcome": "answered",
                "language": "EN",
                "markdown": f"The structured value is 42. [[{source_quote_ref}]]",
                "fields": {"answer": "42"},
            }
        return ModelResponse(
            output=[ResponseFunctionToolCall(
                arguments=json.dumps(arguments),
                call_id=f"call_{self.call_count}",
                name=name,
                type="function_call",
            )],
            usage=Usage(requests=1, input_tokens=10, output_tokens=5, total_tokens=15),
            response_id=None,
        )

    def stream_response(self, *args, **kwargs) -> AsyncIterator:
        raise NotImplementedError


class _LoopingAgentsModel(Model):
    def __init__(self) -> None:
        self.call_count = 0

    async def get_response(
        self,
        system_instructions,
        input,
        model_settings,
        tools,
        output_schema,
        handoffs,
        tracing: ModelTracing,
        *,
        previous_response_id,
        conversation_id,
        prompt,
    ) -> ModelResponse:
        self.call_count += 1
        if self.call_count > 16:
            raise AssertionError("Runner requested a seventeenth model turn")
        return ModelResponse(
            output=[ResponseFunctionToolCall(
                arguments=json.dumps({"skill_id": "precision_fact_extraction"}),
                call_id=f"loop_{self.call_count}",
                name="get_research_skill",
                type="function_call",
            )],
            usage=Usage(requests=1, input_tokens=1, output_tokens=1, total_tokens=2),
            response_id=None,
        )

    def stream_response(self, *args, **kwargs) -> AsyncIterator:
        raise NotImplementedError


def _run_plain_text_diagnosis_case(
    diagnostic_content: str,
    *,
    diagnostic_status: int = 200,
    post_diagnosis_plain: bool = False,
    plain_draft: str = "Unsubmitted draft.",
) -> dict:
    requests: list[dict] = []
    progress: list[dict] = []
    diagnostic_seen = False
    applied_hint = ""
    applied_message = ""

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self) -> None:
            nonlocal diagnostic_seen, applied_hint, applied_message
            length = int(self.headers.get("Content-Length") or 0)
            request = json.loads(self.rfile.read(length).decode("utf-8"))
            requests.append(request)
            if not request.get("tools"):
                diagnostic_seen = True
                if diagnostic_status != 200:
                    self._send(diagnostic_status, {"error": "diagnosis unavailable"})
                    return
                message = {"role": "assistant", "content": diagnostic_content}
                usage = {"prompt_tokens": 4, "completion_tokens": 2, "total_tokens": 6}
            elif diagnostic_seen:
                tool_messages = [
                    json.loads(message["content"])
                    for message in request.get("messages", [])
                    if message.get("role") == "tool"
                ]
                applied_hint = str(tool_messages[-1].get("diagnostic_repair_hint") or "")
                applied_message = str(tool_messages[-1].get("message") or "")
                if post_diagnosis_plain:
                    message = {"role": "assistant", "content": "Still plain text."}
                else:
                    message = {
                        "role": "assistant",
                        "content": "",
                        "tool_calls": [{
                            "id": "call_submit_direct_after_diagnosis",
                            "type": "function",
                            "function": {
                                "name": "submit_direct_answer",
                                "arguments": json.dumps({
                                    "outcome": "answered",
                                    "markdown": "Recovered answer.",
                                }),
                            },
                        }],
                    }
                usage = {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
            else:
                message = {"role": "assistant", "content": plain_draft}
                usage = {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
            self._send(200, {
                "id": f"response_{len(requests)}",
                "object": "chat.completion",
                "created": len(requests),
                "model": "MiniMax-M3",
                "choices": [{
                    "index": 0,
                    "message": message,
                    "finish_reason": "tool_calls" if message.get("tool_calls") else "stop",
                }],
                "usage": usage,
            })

        def _send(self, status: int, payload: dict) -> None:
            body = json.dumps(payload).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, format, *args) -> None:
            return

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    provider = ProviderConfig(
        scope="llm",
        provider="minimax",
        api_style="openai-compatible",
        api_base_url=f"http://127.0.0.1:{server.server_port}/v1",
        model="MiniMax-M3",
        api_key="test-key",
    )
    try:
        with tempfile.TemporaryDirectory() as tmp:
            harness = LiveResearchChatHarness(
                AgentsSdkHarnessRuntime(provider=provider),
                eval_dump_dir=tmp,
            )
            run, _ = harness.run_turn(
                _harness_tests.PythonHarnessPrototypeTest()._synthetic_dataset(),
                ConversationState.new("plain_text_online_diagnosis"),
                "Hello",
                progress_listener=progress.append,
            )
            events = [
                json.loads(line)
                for line in (Path(tmp) / run["run_id"] / "events.jsonl").read_text().splitlines()
            ]
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)
    return {
        "run": run,
        "requests": requests,
        "progress": progress,
        "events": events,
        "applied_hint": applied_hint,
        "applied_message": applied_message,
    }


def _tool_outputs(input_value) -> dict[str, dict]:
    if not isinstance(input_value, list):
        return {}
    result: dict[str, dict] = {}
    for item in input_value:
        if not isinstance(item, dict) or item.get("type") != "function_call_output":
            continue
        output = item.get("output")
        if isinstance(output, str):
            result[str(item.get("call_id"))] = json.loads(output)
    return result


def _event_kinds(progress: list[dict], run: dict) -> set[str]:
    kinds = {str(item.get("type")) for item in progress}
    if any(item.get("tool_name") == "submit_research_answer" for item in run["react_trace"]):
        kinds.add("answer.validation")
    return kinds
