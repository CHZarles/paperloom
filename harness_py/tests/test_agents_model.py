from __future__ import annotations

import asyncio
import json
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import AsyncMock, patch

from agents import FunctionTool, ModelResponse, ModelTracing, OpenAIChatCompletionsModel, Usage
from openai.types.responses import ResponseOutputMessage, ResponseOutputText

from harness_py.evaluation.eval_recorder import EvalRecorder
from harness_py.orchestration.agents.context import ResearchRunContext
from harness_py.orchestration.agents.model import (
    MiniMaxAgentsModel,
    OpenAIResponsesAgentsModel,
    TEXT_NUDGE_TOOL_NAME,
    TOOL_ARGUMENT_REPAIR_PREFIX,
    bind_research_context,
    provider_agents_model,
)
from harness_py.orchestration.memory import ResearchMemory
from harness_py.orchestration.runtime import TurnExecutionInput
from harness_py.transport.provider_config import ProviderConfig
from harness_py.tests import test_harness_py as _harness_tests
from harness_py.utils.errors import ResearchSystemError


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
            settings = model.research_settings()
            self.assertEqual("required", settings.tool_choice)
            self.assertIsNone(settings.max_tokens)
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
        internal_tool = FunctionTool(
            name=TEXT_NUDGE_TOOL_NAME,
            description="Internal continuation",
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
                        model.research_settings(),
                        [tool, internal_tool],
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
        self.assertNotIn("max_tokens", captured)
        self.assertEqual(0.0, captured["temperature"])
        self.assertEqual(1.0, captured["top_p"])
        self.assertEqual("submit_research_answer", captured["tools"][0]["function"]["name"])
        self.assertEqual(
            ["submit_research_answer"],
            [item["function"]["name"] for item in captured["tools"]],
        )
        self.assertEqual({"model.request", "model.response"}, event_kinds)
        self.assertNotIn("test-key", events_text)

    def test_provider_protocol_recovery_allows_three_then_stops(self) -> None:
        model = MiniMaxAgentsModel(ProviderConfig(
            scope="llm",
            provider="minimax",
            api_style="openai-compatible",
            api_base_url="https://example.invalid/v1",
            model="MiniMax-M3",
            api_key="test-key",
        ))
        raw_response = ModelResponse(
            output=[ResponseOutputMessage(
                id="message_1",
                content=[ResponseOutputText(
                    annotations=[],
                    text="A direct model answer.",
                    type="output_text",
                )],
                role="assistant",
                status="completed",
                type="message",
            )],
            usage=Usage(requests=1, input_tokens=3, output_tokens=2, total_tokens=5),
            response_id="response_1",
        )
        dataset = _harness_tests.PythonHarnessPrototypeTest()._synthetic_dataset()
        context = ResearchRunContext(TurnExecutionInput(
            dataset=dataset,
            case_id="repair_budget",
            run_id="run_repair_budget",
            question="Hello",
            conversation_messages=[],
            research_memory=ResearchMemory(),
        ))
        context.protocol_repair_count = 2
        context.begin_model_call()

        async def invoke():
            try:
                with bind_research_context(context), patch.object(
                    OpenAIChatCompletionsModel,
                    "get_response",
                    new=AsyncMock(return_value=raw_response),
                ):
                    repaired = await model.get_response(
                        "System prompt",
                        [{"role": "user", "content": "Hello"}],
                        model.research_settings(),
                        [],
                        None,
                        [],
                        ModelTracing.DISABLED,
                        previous_response_id=None,
                        conversation_id=None,
                        prompt=None,
                    )
                    context.begin_model_call()
                    with self.assertRaisesRegex(ResearchSystemError, "PROVIDER_TOOL_PROTOCOL_VIOLATION"):
                        await model.get_response(
                            "System prompt",
                            [{"role": "user", "content": "Hello"}],
                            model.research_settings(),
                            [],
                            None,
                            [],
                            ModelTracing.DISABLED,
                            previous_response_id=None,
                            conversation_id=None,
                            prompt=None,
                        )
                    return repaired
            finally:
                await model.close()

        response = asyncio.run(invoke())

        self.assertEqual(TEXT_NUDGE_TOOL_NAME, response.output[0].name)
        self.assertEqual(3, context.protocol_repair_count)
        self.assertEqual(5, context.total_tokens)

    def test_text_only_response_requires_an_explicit_submission_tool(self) -> None:
        model = MiniMaxAgentsModel(ProviderConfig(
            scope="llm",
            provider="minimax",
            api_style="openai-compatible",
            api_base_url="https://example.invalid/v1",
            model="MiniMax-M3",
            api_key="test-key",
        ))
        raw_response = ModelResponse(
            output=[ResponseOutputMessage(
                id="message_1",
                content=[ResponseOutputText(
                    annotations=[],
                    text="A direct model answer.",
                    type="output_text",
                )],
                role="assistant",
                status="completed",
                type="message",
            )],
            usage=Usage(requests=1, input_tokens=1, output_tokens=1, total_tokens=2),
            response_id="response_1",
        )
        dataset = _harness_tests.PythonHarnessPrototypeTest()._synthetic_dataset()
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        recorder = EvalRecorder(temporary.name, "run_text_response")
        context = ResearchRunContext(TurnExecutionInput(
            dataset=dataset,
            case_id="text_response",
            run_id="run_text_response",
            question="Hello",
            conversation_messages=[],
            research_memory=ResearchMemory(),
            eval_recorder=recorder,
        ))
        context.current_model_call_id = "model_1"

        async def invoke():
            try:
                with bind_research_context(context), patch.object(
                    OpenAIChatCompletionsModel,
                    "get_response",
                    new=AsyncMock(return_value=raw_response),
                ):
                    return await model.get_response(
                        "System prompt",
                        [{"role": "user", "content": "Hello"}],
                        model.research_settings(),
                        [],
                        None,
                        [],
                        ModelTracing.DISABLED,
                        previous_response_id=None,
                        conversation_id=None,
                        prompt=None,
                    )
            finally:
                await model.close()

        response = asyncio.run(invoke())
        recorder.finish({"status": "COMPLETED"})
        events = [
            json.loads(line)
            for line in recorder.events_path.read_text(encoding="utf-8").splitlines()
        ]
        transformed = next(event for event in events if event["kind"] == "model.output_transformed")

        self.assertEqual(TEXT_NUDGE_TOOL_NAME, response.output[0].name)
        self.assertEqual(
            {"content": "A direct model answer."},
            json.loads(response.output[0].arguments),
        )
        self.assertEqual(1, context.protocol_repair_count)
        self.assertIn(response.output[0].call_id, context.synthetic_repair_call_ids)
        self.assertEqual(len("A direct model answer."), transformed["payload"]["source"]["draft_chars"])
        self.assertEqual(64, len(transformed["payload"]["source"]["draft_sha256"]))
        self.assertTrue(transformed["payload"]["target"]["arguments_redacted"])
        self.assertNotIn("A direct model answer.", json.dumps(transformed, ensure_ascii=False))

    def test_text_only_response_does_not_publish_think_block(self) -> None:
        model = MiniMaxAgentsModel(ProviderConfig(
            scope="llm",
            provider="minimax",
            api_style="openai-compatible",
            api_base_url="https://example.invalid/v1",
            model="MiniMax-M3",
            api_key="test-key",
        ))
        raw_response = ModelResponse(
            output=[ResponseOutputMessage(
                id="message_1",
                content=[ResponseOutputText(
                    annotations=[],
                    text="<think>Internal reasoning.</think>\n\nA direct model answer.",
                    type="output_text",
                )],
                role="assistant",
                status="completed",
                type="message",
            )],
            usage=Usage(requests=1, input_tokens=1, output_tokens=1, total_tokens=2),
            response_id="response_1",
        )
        dataset = _harness_tests.PythonHarnessPrototypeTest()._synthetic_dataset()
        context = ResearchRunContext(TurnExecutionInput(
            dataset=dataset,
            case_id="think_response",
            run_id="run_think_response",
            question="Hello",
            conversation_messages=[],
            research_memory=ResearchMemory(),
        ))

        async def invoke():
            try:
                with bind_research_context(context), patch.object(
                    OpenAIChatCompletionsModel,
                    "get_response",
                    new=AsyncMock(return_value=raw_response),
                ):
                    return await model.get_response(
                        "System prompt",
                        [{"role": "user", "content": "Hello"}],
                        model.research_settings(),
                        [],
                        None,
                        [],
                        ModelTracing.DISABLED,
                        previous_response_id=None,
                        conversation_id=None,
                        prompt=None,
                    )
            finally:
                await model.close()

        response = asyncio.run(invoke())

        self.assertEqual(TEXT_NUDGE_TOOL_NAME, response.output[0].name)
        self.assertEqual(
            {"content": "A direct model answer."},
            json.loads(response.output[0].arguments),
        )

    def test_malformed_tool_arguments_become_a_valid_repair_call(self) -> None:
        class Handler(BaseHTTPRequestHandler):
            def do_POST(self) -> None:
                length = int(self.headers.get("Content-Length") or 0)
                self.rfile.read(length)
                body = json.dumps({
                    "id": "response_malformed",
                    "object": "chat.completion",
                    "created": 1,
                    "model": "MiniMax-M3",
                    "choices": [{
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": "",
                            "tool_calls": [{
                                "id": "call_truncated",
                                "type": "function",
                                "function": {
                                    "name": "submit_research_answer",
                                    "arguments": '{"outcome":"answered","markdown":"truncated',
                                },
                            }],
                        },
                        "finish_reason": "length",
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

        async def invoke(context: ResearchRunContext):
            try:
                with bind_research_context(context):
                    return await model.get_response(
                        "System prompt",
                        [{"role": "user", "content": "Hello"}],
                        model.research_settings(),
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

        dataset = _harness_tests.PythonHarnessPrototypeTest()._synthetic_dataset()
        with tempfile.TemporaryDirectory() as tmp:
            recorder = EvalRecorder(tmp, "run_repair_test")
            context = ResearchRunContext(TurnExecutionInput(
                dataset=dataset,
                case_id="repair_test",
                run_id="run_repair_test",
                question="Hello",
                conversation_messages=[],
                research_memory=ResearchMemory(),
                eval_recorder=recorder,
            ))
            context.current_model_call_id = "model_1"
            try:
                response = asyncio.run(invoke(context))
            finally:
                server.shutdown()
                server.server_close()
                thread.join(timeout=2)
            recorder.finish({"status": "COMPLETED"})
            events = [
                json.loads(line)
                for line in recorder.events_path.read_text(encoding="utf-8").splitlines()
            ]

        self.assertEqual(1, len(response.output))
        repaired = response.output[0]
        self.assertEqual(TEXT_NUDGE_TOOL_NAME, repaired.name)
        payload = json.loads(repaired.arguments)
        self.assertTrue(payload["content"].startswith(TOOL_ARGUMENT_REPAIR_PREFIX))
        self.assertIn("submit_research_answer", payload["content"])
        transformed = next(event for event in events if event["kind"] == "model.output_transformed")
        self.assertEqual("TOOL_ARGUMENTS_INVALID_OR_TRUNCATED", transformed["payload"]["reason_code"])
        self.assertEqual("submit_research_answer", transformed["payload"]["source"]["name"])
        self.assertEqual(TEXT_NUDGE_TOOL_NAME, transformed["payload"]["target"]["name"])
        self.assertEqual(1, context.protocol_repair_count)
        self.assertIn(repaired.call_id, context.synthetic_repair_call_ids)
