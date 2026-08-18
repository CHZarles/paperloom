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
from harness_py.utils.errors import ResearchSystemError


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
        context.synthetic_repair_call_ids = {"call_repair"}

        output = asyncio.run(tool.on_invoke_tool(
            tool_context,
            json.dumps({"content": TOOL_ARGUMENT_REPAIR_PREFIX + requested}),
        ))

        self.assertEqual(requested, json.loads(output)["message"])
        self.assertNotIn("call_repair", context.synthetic_repair_call_ids)

    def test_internal_continuation_requests_submission_of_the_existing_plain_text_draft(self) -> None:
        dataset = _harness_tests.PythonHarnessPrototypeTest()._synthetic_dataset()
        context = ResearchRunContext(TurnExecutionInput(
            dataset=dataset,
            case_id="finalize_draft",
            run_id="run_finalize_draft",
            question="hello",
            conversation_messages=[],
            research_memory=ResearchMemory(),
        ))
        context.corpus.observations_by_evidence_id["source_quote_1"] = {
            "source_quote_ref": "source_quote_1",
            "title": "Paper One",
            "section": "2.2 Standard Attention",
            "page": 4,
        }
        context.synthetic_repair_call_ids = {"call_finalize"}
        tool = next(
            item
            for item in build_agent_tools(context)
            if item.name == TEXT_NUDGE_TOOL_NAME
        )

        output = asyncio.run(tool.on_invoke_tool(
            ToolContext(
                context=context,
                tool_name=TEXT_NUDGE_TOOL_NAME,
                tool_call_id="call_finalize",
                tool_arguments="{}",
            ),
            json.dumps({"content": "A complete draft with source [1]."}),
        ))

        payload = json.loads(output)
        self.assertEqual("finalize_existing_draft", payload["mode"])
        self.assertIn("Do not return Markdown as assistant text", payload["message"])
        self.assertIn("markdown argument", payload["message"])
        self.assertIn("replace numeric citations", payload["message"])
        self.assertEqual(
            [{
                "source_quote_ref": "source_quote_1",
                "title": "Paper One",
                "section": "2.2 Standard Attention",
                "page": 4,
            }],
            payload["allowed_source_quotes"],
        )
        self.assertNotIn("draft", payload)
        self.assertNotIn("call_finalize", context.synthetic_repair_call_ids)

    def test_internal_continuation_requires_evidence_or_a_non_research_submission_without_quotes(self) -> None:
        dataset = _harness_tests.PythonHarnessPrototypeTest()._synthetic_dataset()
        context = ResearchRunContext(TurnExecutionInput(
            dataset=dataset,
            case_id="finalize_without_quotes",
            run_id="run_finalize_without_quotes",
            question="hello",
            conversation_messages=[],
            research_memory=ResearchMemory(),
        ))
        context.synthetic_repair_call_ids = {"call_finalize"}
        tool = next(
            item
            for item in build_agent_tools(context)
            if item.name == TEXT_NUDGE_TOOL_NAME
        )

        output = asyncio.run(tool.on_invoke_tool(
            ToolContext(
                context=context,
                tool_name=TEXT_NUDGE_TOOL_NAME,
                tool_call_id="call_finalize",
                tool_arguments="{}",
            ),
            json.dumps({"content": "A research-looking draft."}),
        ))

        payload = json.loads(output)
        self.assertEqual("acquire_evidence_or_submit_non_research", payload["mode"])
        self.assertIn("read_paper_content", payload["message"])
        self.assertIn("Direct or Catalog", payload["message"])
        self.assertEqual([], payload["allowed_source_quotes"])

    def test_internal_continuation_rejects_an_unregistered_call(self) -> None:
        dataset = _harness_tests.PythonHarnessPrototypeTest()._synthetic_dataset()
        context = ResearchRunContext(TurnExecutionInput(
            dataset=dataset,
            case_id="unregistered_repair",
            run_id="run_unregistered_repair",
            question="hello",
            conversation_messages=[],
            research_memory=ResearchMemory(),
        ))
        context.synthetic_repair_call_ids = set()
        tool = next(
            item
            for item in build_agent_tools(context)
            if item.name == TEXT_NUDGE_TOOL_NAME
        )

        with self.assertRaisesRegex(ResearchSystemError, "PROVIDER_TOOL_PROTOCOL_VIOLATION"):
            asyncio.run(tool.on_invoke_tool(
                ToolContext(
                    context=context,
                    tool_name=TEXT_NUDGE_TOOL_NAME,
                    tool_call_id="call_model_selected",
                    tool_arguments="{}",
                ),
                json.dumps({"content": ""}),
            ))

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

        tool = next(item for item in build_agent_tools(context) if item.name == FINAL_TOOL_NAME)
        payload = json.loads(asyncio.run(tool.on_invoke_tool(
            tool_context,
            json.dumps({
                "outcome": "answered",
                "language": "EN",
                "markdown": "Hello.",
                "fields": {},
            }),
        )))

        self.assertFalse(payload["accepted"])
        self.assertEqual(["SUBMISSION_TOOL_GROUP_INVALID"], payload["issue_codes"])
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
        tool = next(item for item in build_agent_tools(context) if item.name == FINAL_TOOL_NAME)
        for call_id, markdown, expected_issue in (
            ("call_uncited", "An uncited paper claim.", "UNCITED_CONTENT_BLOCK"),
            ("call_think", "<think>hidden</think> Supported. [[source_quote_synthetic]]", "INTERNAL_REASONING_NOT_ALLOWED"),
        ):
            context.tool_call_groups[call_id] = (FINAL_TOOL_NAME,)
            rejected = json.loads(asyncio.run(tool.on_invoke_tool(
                ToolContext(
                    context=context,
                    tool_name=FINAL_TOOL_NAME,
                    tool_call_id=call_id,
                    tool_arguments="{}",
                ),
                json.dumps({"outcome": "answered", "language": "EN", "markdown": markdown}),
            )))
            self.assertFalse(rejected["accepted"])
            self.assertIn(expected_issue, rejected["issue_codes"])

        context.tool_call_groups["call_final"] = (FINAL_TOOL_NAME,)
        payload = json.loads(asyncio.run(tool.on_invoke_tool(
            ToolContext(
                context=context,
                tool_name=FINAL_TOOL_NAME,
                tool_call_id="call_final",
                tool_arguments="{}",
            ),
            json.dumps({
                "outcome": "answered",
                "language": "EN",
                "markdown": "Synthetic Paper is supported [[source_quote_synthetic]], while Other Paper differs.",
            }),
        )))

        self.assertTrue(payload["accepted"])
        self.assertEqual("RESEARCH", payload["draft"]["answer_contract"])

    def test_catalog_submission_uses_the_current_tool_result_ledger(self) -> None:
        dataset = _harness_tests.PythonHarnessPrototypeTest()._synthetic_dataset()
        context = ResearchRunContext(TurnExecutionInput(
            dataset=dataset,
            case_id="catalog_ledger",
            run_id="run_catalog_ledger",
            question="how many papers",
            conversation_messages=[],
            research_memory=ResearchMemory(),
        ))
        tools = {tool.name: tool for tool in build_agent_tools(context)}
        context.tool_call_groups["call_search"] = ("search_paper_candidates",)
        search_result = json.loads(asyncio.run(tools["search_paper_candidates"].on_invoke_tool(
            ToolContext(
                context=context,
                tool_name="search_paper_candidates",
                tool_call_id="call_search",
                tool_arguments="{}",
            ),
            json.dumps({"query_text": "", "limit": 100}),
        )))
        result_ref = search_result["paper_result_ref"]

        context.tool_call_groups["call_catalog"] = ("submit_catalog_answer",)
        submission = json.loads(asyncio.run(tools["submit_catalog_answer"].on_invoke_tool(
            ToolContext(
                context=context,
                tool_name="submit_catalog_answer",
                tool_call_id="call_catalog",
                tool_arguments="{}",
            ),
            json.dumps({
                "result_ref": result_ref,
                "view": "COUNT",
                "language": "EN",
            }),
        )))

        self.assertIn(result_ref, context.catalog_results_by_ref)
        self.assertEqual("Found 1 paper.", submission["draft"]["markdown"])
        self.assertEqual("CATALOG", submission["draft"]["answer_contract"])

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
