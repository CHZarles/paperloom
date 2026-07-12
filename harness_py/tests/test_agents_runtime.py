from __future__ import annotations

import json
import tempfile
import unittest
from collections.abc import AsyncIterator
from pathlib import Path

from agents import Model, ModelResponse, ModelSettings, ModelTracing, Usage
from openai.types.responses import ResponseFunctionToolCall

from harness_py.agents_runtime import AgentsSdkHarnessRuntime
from harness_py.conversation import ConversationState
from harness_py.live_chat import LiveResearchChatHarness
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
