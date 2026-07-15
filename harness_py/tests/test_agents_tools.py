from __future__ import annotations

import asyncio
import json
import unittest
from dataclasses import replace

from agents.tool_context import ToolContext

from harness_py.orchestration.agents.context import ResearchRunContext
from harness_py.orchestration.agents.tools import (
    FINAL_TOOL_NAME,
    _invoke_final,
    _normalize_structured_arguments,
    build_agent_tools,
)
from harness_py.orchestration.agents.model import (
    TEXT_NUDGE_TOOL_NAME,
    TOOL_ARGUMENT_REPAIR_PREFIX,
)
from harness_py.orchestration.memory import ResearchMemory
from harness_py.orchestration.runtime import TurnExecutionInput
from harness_py.tests import test_harness_py as _harness_tests


class AgentsToolsTest(unittest.TestCase):
    def test_structured_text_wrappers_decode_objects_and_scalars(self) -> None:
        self.assertEqual(
            {
                "claim_id": "claim_1",
                "field_values": [{"name": "beta1", "value": "0.9"}],
            },
            _normalize_structured_arguments({
                "claim_id": {"$text": "claim_1"},
                "field_values": {"$text": '[{"name":"beta1","value":"0.9"}]'},
            }),
        )

    def test_internal_continuation_returns_the_malformed_argument_repair_message(self) -> None:
        dataset = _harness_tests.PythonHarnessPrototypeTest()._synthetic_dataset()
        context = ResearchRunContext(TurnExecutionInput(
            dataset=dataset,
            case_id="repair_arguments",
            run_id="run_repair_arguments",
            question="hello",
            conversation_messages=[],
            research_memory=ResearchMemory(),
        ))
        tool = next(
            item
            for item in build_agent_tools(context)
            if item.name == TEXT_NUDGE_TOOL_NAME
        )
        tool_context = ToolContext(
            context=context,
            tool_name=TEXT_NUDGE_TOOL_NAME,
            tool_call_id="call_repair",
            tool_arguments="{}",
        )
        requested = "Retry submit_research_answer with shorter valid JSON."

        output = asyncio.run(tool.on_invoke_tool(
            tool_context,
            json.dumps({"content": TOOL_ARGUMENT_REPAIR_PREFIX + requested}),
        ))

        self.assertEqual(requested, json.loads(output)["message"])

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

    def test_final_submission_rejects_missing_same_paper_citation(self) -> None:
        original = _harness_tests.PythonHarnessPrototypeTest()._synthetic_dataset()
        dataset = replace(
            original,
            paper_records_by_id={
                **original.paper_records_by_id,
                "other_paper": {"identity": {"title": "Other Paper"}},
            },
        )
        context = ResearchRunContext(TurnExecutionInput(
            dataset=dataset,
            case_id="coverage_final",
            run_id="run_coverage_final",
            question="Compare Synthetic Paper and Other Paper.",
            conversation_messages=[],
            research_memory=ResearchMemory(),
        ))
        context.trace.extend([
            {
                "tool_name": "find_reading_locations",
                "arguments": {"paper_ids": ["synthetic_paper", "other_paper"]},
                "result": {"locations": [
                    {"paper_id": "synthetic_paper", "location_ref": "loc_1"},
                    {"paper_id": "other_paper", "location_ref": "loc_other"},
                ]},
            },
            {
                "tool_name": "read_locations",
                "arguments": {"location_refs": ["loc_1", "loc_other"]},
                "result": {"items": [
                    {"evidence_id": "ev_synthetic", "paper_id": "synthetic_paper"},
                    {"evidence_id": "ev_other", "paper_id": "other_paper"},
                ]},
            },
        ])
        context.corpus.observations_by_evidence_id.update({
            "ev_synthetic": {
                "evidence_id": "ev_synthetic",
                "paper_id": "synthetic_paper",
                "element_type": "paragraph",
                "span_text": "Synthetic evidence.",
            },
            "ev_other": {
                "evidence_id": "ev_other",
                "paper_id": "other_paper",
                "element_type": "paragraph",
                "span_text": "Other evidence.",
            },
        })
        context.tool_call_groups["call_final"] = (FINAL_TOOL_NAME,)
        tool_context = ToolContext(
            context=context,
            tool_name=FINAL_TOOL_NAME,
            tool_call_id="call_final",
            tool_arguments="{}",
        )

        payload = json.loads(_invoke_final(context, tool_context, {
            "outcome": "answered",
            "markdown": "Synthetic Paper is supported [[ev_synthetic]], while Other Paper differs.",
        }))

        self.assertFalse(payload["accepted"])
        self.assertIn("Other Paper", payload["validation_error"])
        self.assertIn("does not cite evidence", payload["validation_error"])
