from __future__ import annotations

import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer

from harness_py.evaluation.judge_model import MiniMaxJudgeModel
from harness_py.transport.provider_config import ProviderConfig


class JudgeModelTest(unittest.TestCase):
    def test_minimax_judge_forces_submit_judgment(self) -> None:
        captured: dict = {}

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):  # noqa: N802
                length = int(self.headers["Content-Length"])
                captured["path"] = self.path
                captured["body"] = json.loads(self.rfile.read(length).decode("utf-8"))
                body = json.dumps({
                    "choices": [{
                        "message": {
                            "tool_calls": [{
                                "function": {
                                    "name": "submit_judgment",
                                    "arguments": '{"decision":"pass"}',
                                },
                            }],
                        },
                    }],
                }).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *_args):
                return

        server = HTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            model = MiniMaxJudgeModel(ProviderConfig(
                scope="llm",
                provider="minimax",
                api_style="openai-compatible",
                api_base_url=f"http://127.0.0.1:{server.server_port}/v1",
                model="MiniMax-M3",
                api_key="secret",
            ))
            calls = model.complete_judgment(
                [{"role": "user", "content": "judge"}],
                {
                    "type": "function",
                    "function": {
                        "name": "submit_judgment",
                        "parameters": {"type": "object"},
                    },
                },
                100,
            )
        finally:
            server.shutdown()
            server.server_close()

        self.assertEqual("/v1/chat/completions", captured["path"])
        self.assertEqual({"type": "disabled"}, captured["body"]["thinking"])
        self.assertEqual(
            {"type": "function", "function": {"name": "submit_judgment"}},
            captured["body"]["tool_choice"],
        )
        self.assertEqual(
            [{"name": "submit_judgment", "arguments": {"decision": "pass"}}],
            calls,
        )


if __name__ == "__main__":
    unittest.main()
