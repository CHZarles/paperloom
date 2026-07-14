from __future__ import annotations

import asyncio
import json
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from agents import FunctionTool, ModelTracing

from harness_py.evaluation.eval_recorder import EvalRecorder
from harness_py.orchestration.agents.context import ResearchRunContext
from harness_py.orchestration.agents.model import (
    MiniMaxAgentsModel,
    OpenAIResponsesAgentsModel,
    bind_research_context,
    provider_agents_model,
)
from harness_py.orchestration.memory import ResearchMemory
from harness_py.orchestration.runtime import TurnExecutionInput
from harness_py.transport.provider_config import ProviderConfig
from harness_py.tests import test_harness_py as _harness_tests


class AgentsModelTest(unittest.TestCase):
    def test_provider_factory_selects_responses_api_for_codex_provider(self) -> None:
        model = provider_agents_model(ProviderConfig(
            scope="llm",
            provider="codex",
            api_style="responses",
            api_base_url="https://example.invalid/v1",
            model="gpt-5.3-codex-spark",
            api_key="test-key",
        ))
        try:
            self.assertIsInstance(model, OpenAIResponsesAgentsModel)
            settings = model.research_settings(2048)
            self.assertEqual("required", settings.tool_choice)
            self.assertEqual(2048, settings.max_tokens)
        finally:
            asyncio.run(model.close())

    def test_minimax_adapter_preserves_required_tool_and_thinking_settings(self) -> None:
        captured: dict = {}

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self) -> None:
                length = int(self.headers.get("Content-Length") or 0)
                captured.update(json.loads(self.rfile.read(length).decode("utf-8")))
                body = json.dumps({
                    "id": "response_1",
                    "object": "chat.completion",
                    "created": 1,
                    "model": "MiniMax-M3",
                    "choices": [{
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": "",
                            "tool_calls": [{
                                "id": "call_submit",
                                "type": "function",
                                "function": {
                                    "name": "submit_research_answer",
                                    "arguments": json.dumps({
                                        "outcome": "answered",
                                        "markdown": "Done.",
                                    }),
                                },
                            }],
                        },
                        "finish_reason": "tool_calls",
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
        model = MiniMaxAgentsModel(ProviderConfig(
            scope="llm",
            provider="minimax",
            api_style="openai-compatible",
            api_base_url=f"http://127.0.0.1:{server.server_port}/v1",
            model="MiniMax-M3",
            api_key="test-key",
        ))
        tool = FunctionTool(
            name="submit_research_answer",
            description="Finish",
            params_json_schema={"type": "object", "additionalProperties": True},
            on_invoke_tool=lambda context, raw: raw,
            strict_json_schema=False,
        )

        dataset = _harness_tests.PythonHarnessPrototypeTest()._synthetic_dataset()

        async def invoke(context: ResearchRunContext) -> None:
            try:
                with bind_research_context(context):
                    await model.get_response(
                        "System prompt",
                        [{"role": "user", "content": "Hello"}],
                        model.research_settings(1234),
                        [tool],
                        None,
                        [],
                        ModelTracing.DISABLED,
                        previous_response_id=None,
                        conversation_id=None,
                        prompt=None,
                    )
            finally:
                await model.close()

        with tempfile.TemporaryDirectory() as tmp:
            recorder = EvalRecorder(tmp, "run_model_test")
            context = ResearchRunContext(TurnExecutionInput(
                dataset=dataset,
                case_id="model_test",
                run_id="run_model_test",
                question="Hello",
                conversation_messages=[],
                research_memory=ResearchMemory(),
                eval_recorder=recorder,
            ))
            context.current_model_call_id = "model_1"
            try:
                asyncio.run(invoke(context))
            finally:
                server.shutdown()
                server.server_close()
                thread.join(timeout=2)
            recorder.finish({"status": "COMPLETED"})
            events_text = recorder.events_path.read_text(encoding="utf-8")
            event_kinds = {
                json.loads(line)["kind"]
                for line in events_text.splitlines()
            }

        self.assertEqual("required", captured["tool_choice"])
        self.assertEqual({"type": "adaptive"}, captured["thinking"])
        self.assertEqual(1234, captured["max_tokens"])
        self.assertEqual(0.0, captured["temperature"])
        self.assertEqual(1.0, captured["top_p"])
        self.assertEqual("submit_research_answer", captured["tools"][0]["function"]["name"])
        self.assertEqual({"model.request", "model.response"}, event_kinds)
        self.assertNotIn("test-key", events_text)
