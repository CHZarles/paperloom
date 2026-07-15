from __future__ import annotations

import json
import tempfile
import threading
import unittest
from collections.abc import AsyncIterator
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from agents import Model, ModelResponse, ModelSettings, ModelTracing, Usage
from openai.types.responses import ResponseFunctionToolCall

from harness_py.orchestration.agents.runtime import AgentsSdkHarnessRuntime
from harness_py.orchestration.conversation import ConversationState
from harness_py.orchestration.live_chat import LiveResearchChatHarness
from harness_py.transport.provider_config import ProviderConfig
from harness_py.tests import test_harness_py as _harness_tests


class AgentsRuntimeTest(unittest.TestCase):
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
        self.assertEqual(1, len(run["research_answer"]["cited_evidence_ids"]))
        self.assertEqual("python_openai_agents_sdk_harness_v1", run["harness_id"])
        self.assertTrue(run["run_id"].startswith("run_"))
        self.assertEqual(1, state.turn_index)
        self.assertIn("answer.validation", _event_kinds(progress, run))
        self.assertIn("run.started", {event["kind"] for event in events})
        self.assertIn("tool.completed", {event["kind"] for event in events})
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
                        "markdown": "Recovered after malformed tool arguments.",
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
                                    "name": "submit_research_answer",
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

        self.assertEqual(2, len(requests))
        self.assertEqual("COMPLETED", run["status"])
        self.assertEqual(
            "Recovered after malformed tool arguments.",
            run["research_answer"]["markdown"],
        )

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
            name, arguments = "find_reading_locations", {
                "paper_ids": ["synthetic_paper"],
                "query_text": "structured value",
                "top_k": 3,
            }
        elif self.call_count == 4:
            locations = outputs["call_3"]["locations"]
            name, arguments = "read_locations", {"location_refs": [locations[0]["location_ref"]]}
        else:
            evidence_id = outputs["call_4"]["items"][0]["evidence_id"]
            name, arguments = "submit_research_answer", {
                "outcome": "answered",
                "markdown": f"The structured value is 42. [[{evidence_id}]]",
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
