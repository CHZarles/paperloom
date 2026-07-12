from __future__ import annotations

import json
import unittest

from agents.tool_context import ToolContext

from harness_py.agents_context import ResearchRunContext
from harness_py.agents_tools import FINAL_TOOL_NAME, _invoke_final
from harness_py.memory import ResearchMemory
from harness_py.runtime import TurnExecutionInput
from harness_py.tests import test_harness_py as _harness_tests


class AgentsToolsTest(unittest.TestCase):
    def test_mixed_final_and_research_calls_are_rejected(self) -> None:
        dataset = _harness_tests.PythonHarnessPrototypeTest()._synthetic_dataset()
        context = ResearchRunContext(TurnExecutionInput(
            dataset=dataset,
            case_id="mixed_final",
            run_id="run_mixed_final",
            question="hello",
            conversation_messages=[],
            research_memory=ResearchMemory(),
        ))
        context.tool_call_groups["call_final"] = (FINAL_TOOL_NAME, "get_research_skill")
        tool_context = ToolContext(
            context=context,
            tool_name=FINAL_TOOL_NAME,
            tool_call_id="call_final",
            tool_arguments="{}",
        )

        payload = json.loads(_invoke_final(context, tool_context, {
            "outcome": "answered",
            "markdown": "Hello.",
            "fields": {},
        }))

        self.assertFalse(payload["accepted"])
        self.assertIn("only tool call", payload["validation_error"])
        self.assertFalse(context.trace[-1]["result"]["accepted"])
