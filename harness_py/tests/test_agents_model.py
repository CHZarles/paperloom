from __future__ import annotations

import asyncio
import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from agents import FunctionTool, ModelTracing

from harness_py.agents_model import MiniMaxAgentsModel
from harness_py.provider_config import ProviderConfig


class AgentsModelTest(unittest.TestCase):
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

        async def invoke() -> None:
            try:
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

        try:
            asyncio.run(invoke())
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

        self.assertEqual("required", captured["tool_choice"])
        self.assertEqual({"type": "adaptive"}, captured["thinking"])
        self.assertEqual(1234, captured["max_tokens"])
        self.assertEqual(0.0, captured["temperature"])
        self.assertEqual(1.0, captured["top_p"])
        self.assertEqual("submit_research_answer", captured["tools"][0]["function"]["name"])
