from __future__ import annotations

import asyncio
import json
import unittest
from dataclasses import replace

from agents.tool_context import ToolContext

from harness_py.orchestration.agents.context import ResearchRunContext
from harness_py.orchestration.agents.tools import (
    FINAL_TOOL_NAME,
    _bounded_read_payload,
    _invoke_final,
    _normalize_structured_arguments,
    build_agent_tools,
)
from harness_py.orchestration.agents.model import (
    TEXT_NUDGE_TOOL_NAME,
    TOOL_ARGUMENT_REPAIR_PREFIX,
)
from harness_py.orchestration.memory import ResearchMemory
from harness_py.orchestration.research_contract import (
    AnswerContract,
    ProtocolFacts,
    SubmissionIssueClass,
    SubmissionRequested,
    ValidationIssue,
)
from harness_py.orchestration.runtime import TurnExecutionInput
from harness_py.tests import test_harness_py as _harness_tests


class AgentsToolsTest(unittest.TestCase):
    def test_context_applies_protocol_and_records_only_replayable_projection(self) -> None:
        events = []

        class Recorder:
            def append(self, **event):
                events.append(event)

        dataset = _harness_tests.PythonHarnessPrototypeTest()._synthetic_dataset()
        context = ResearchRunContext(TurnExecutionInput(
            dataset=dataset,
            case_id="protocol_adapter",
            run_id="run_protocol_adapter",
            question="hello",
            conversation_messages=[],
            research_memory=ResearchMemory(),
            eval_recorder=Recorder(),
        ))
        event = SubmissionRequested(
            contract=AnswerContract.RESEARCH,
            payload={"markdown": "draft must not be copied"},
            accepted=False,
            issue_class=SubmissionIssueClass.MISSING_CONTRACT_INPUT,
            issue_codes=("UNKNOWN_SOURCE_REF",),
            issues=(ValidationIssue(
                "UNKNOWN_SOURCE_REF",
                SubmissionIssueClass.MISSING_CONTRACT_INPUT,
                unknown_source_quote_refs=("source_quote_missing",),
            ),),
        )

        decision = context.apply_protocol(
            event,
            ProtocolFacts(known_source_quotes={
                "source_quote_1": {"span_text": "source text must not be copied"},
            }),
            tool_call_id="call_protocol",
        )

        self.assertEqual(AnswerContract.RESEARCH, decision.next_state.contract)
        self.assertEqual(decision.next_state, context.protocol_state)
        self.assertEqual("protocol.transition", events[0]["kind"])
        serialized = json.dumps(events[0], ensure_ascii=False)
        self.assertNotIn("draft must not be copied", serialized)
        self.assertNotIn("source text must not be copied", serialized)

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

    def test_final_submission_keeps_cross_paper_coverage_as_offline_diagnostic(self) -> None:
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
                "tool_name": "search_paper_content",
                "arguments": {"paper_ids": ["synthetic_paper", "other_paper"]},
                "result": {"locations": [
                    {"paper_id": "synthetic_paper", "location_ref": "loc_1"},
                    {"paper_id": "other_paper", "location_ref": "loc_other"},
                ]},
            },
            {
                "tool_name": "read_paper_content",
                "arguments": {"location_refs": ["loc_1", "loc_other"]},
                "result": {"items": [
                    {"paper_id": "synthetic_paper", "source_quotes": [{"source_quote_ref": "source_quote_synthetic"}]},
                    {"paper_id": "other_paper", "source_quotes": [{"source_quote_ref": "source_quote_other"}]},
                ]},
            },
        ])
        context.corpus.observations_by_evidence_id.update({
            "source_quote_synthetic": {
                "source_quote_ref": "source_quote_synthetic",
                "paper_id": "synthetic_paper",
                "element_type": "paragraph",
                "span_text": "Synthetic evidence.",
            },
            "source_quote_other": {
                "source_quote_ref": "source_quote_other",
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
            "markdown": "Synthetic Paper is supported [[source_quote_synthetic]], while Other Paper differs.",
        }))

        self.assertTrue(payload["accepted"])
        self.assertIsNone(payload["validation_error"])

        for call_id, markdown, expected_error in (
            ("call_uncited", "An uncited paper claim.", "require citations"),
            ("call_think", "<think>hidden</think> Supported. [[source_quote_synthetic]]", "internal reasoning"),
        ):
            context.tool_call_groups[call_id] = (FINAL_TOOL_NAME,)
            rejected = json.loads(_invoke_final(
                context,
                ToolContext(
                    context=context,
                    tool_name=FINAL_TOOL_NAME,
                    tool_call_id=call_id,
                    tool_arguments="{}",
                ),
                {"outcome": "answered", "markdown": markdown},
            ))
            self.assertFalse(rejected["accepted"])
            self.assertIn(expected_error, rejected["validation_error"])

    def test_bounded_read_reports_omitted_locations(self) -> None:
        dataset = _harness_tests.PythonHarnessPrototypeTest()._synthetic_dataset()
        context = ResearchRunContext(TurnExecutionInput(
            dataset=dataset,
            case_id="bounded_read",
            run_id="run_bounded_read",
            question="hello",
            conversation_messages=[],
            research_memory=ResearchMemory(),
        ))
        items = [
            {
                "location_ref": f"loc_{index}",
                "content": "x" * 80,
                "source_quotes": [{"source_quote_ref": f"source_quote_{index}"}],
            }
            for index in (1, 2)
        ]
        context.corpus.observations_by_evidence_id.update({
            f"source_quote_{index}": {"source_quote_ref": f"source_quote_{index}"}
            for index in (1, 2)
        })
        payload = {"items": items}
        first_item_size = len(json.dumps(
            {"items": items[:1]},
            ensure_ascii=False,
            separators=(",", ":"),
        ))

        visible = _bounded_read_payload(context, payload, first_item_size)

        self.assertTrue(visible["truncated"])
        self.assertEqual(["loc_2"], visible["omitted_location_refs"])
        self.assertNotIn("source_quote_2", context.corpus.observations_by_evidence_id)
