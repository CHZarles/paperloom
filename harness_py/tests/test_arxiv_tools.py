from __future__ import annotations

import unittest
import asyncio
import json

from agents.tool_context import ToolContext
from harness_py.orchestration.arxiv_tools import (
    MAX_RESULTS,
    redact_arguments,
    search_arxiv,
    tool_definition,
)
from harness_py.corpus.gateway import CorpusGatewayError
from harness_py.orchestration.agents.context import ResearchRunContext
from harness_py.orchestration.agents.tools import build_agent_tools
from harness_py.orchestration.memory import ResearchMemory
from harness_py.orchestration.runtime import TurnExecutionInput
from harness_py.tests import test_harness_py as _harness_tests


class FakeArxivReader:
    def __init__(self) -> None:
        self.calls: list[dict] = []

    def search_arxiv(self, arguments: dict) -> dict:
        self.calls.append(arguments)
        return {
            "results": [{
                "arxiv_id": "2401.01234v2",
                "title": "  A   Paper ",
                "authors": ["Ada"],
                "published": "2024-01-01T00:00:00Z",
                "updated": "2024-02-01T00:00:00Z",
                "abstract_preview": "Abstract.",
                "pdf_url": "https://arxiv.org/pdf/2401.01234v2",
            }, {
                "arxiv_id": "not-an-arxiv-id",
            }],
        }


class ErrorArxivReader:
    def __init__(self, status_code: int) -> None:
        self.status_code = status_code

    def search_arxiv(self, arguments: dict) -> dict:
        raise CorpusGatewayError(self.status_code, "ARXIV_ERROR", "safe")


class ArxivToolsTest(unittest.TestCase):
    def test_definition_is_bounded_and_does_not_expose_import(self) -> None:
        definition = tool_definition()["function"]
        self.assertEqual("search_arxiv", definition["name"])
        self.assertEqual(200, definition["parameters"]["properties"]["query_text"]["maxLength"])
        self.assertNotIn("request_arxiv_import", str(definition))

    def test_search_normalizes_metadata_and_rejects_invalid_input(self) -> None:
        reader = FakeArxivReader()
        result = search_arxiv(reader, {"query_text": "small models"})
        self.assertEqual(1, result["returned_count"])
        self.assertEqual("2401.01234v2", result["results"][0]["arxiv_id"])
        self.assertEqual("A Paper", result["results"][0]["title"])
        self.assertTrue(result["metadata_only"])
        self.assertEqual("bounded", result["coverage"])
        self.assertEqual(1, len(reader.calls))

        invalid = search_arxiv(reader, {})
        self.assertEqual("ARXIV_ARGUMENTS_INVALID", invalid["error"])
        self.assertEqual(1, len(reader.calls))

    def test_query_text_is_not_written_to_progress_arguments(self) -> None:
        redacted = redact_arguments({
            "query_text": "private research question",
            "authors": ["Ada"],
            "categories": ["cs.AI"],
        })
        self.assertNotIn("private research question", str(redacted))
        self.assertEqual({
            "query_text_chars": 25,
            "author_count": 1,
            "category_count": 1,
            "has_year_filter": False,
        }, redacted)

    def test_result_count_is_bounded(self) -> None:
        self.assertEqual(10, MAX_RESULTS)

    def test_gateway_errors_keep_argument_failures_distinct_from_unavailability(self) -> None:
        self.assertEqual(
            "ARXIV_ARGUMENTS_INVALID",
            search_arxiv(ErrorArxivReader(400), {"query_text": "paper"})["error"],
        )
        self.assertEqual(
            "ARXIV_UNAVAILABLE",
            search_arxiv(ErrorArxivReader(503), {"query_text": "paper"})["error"],
        )

    def test_production_tool_is_registered_and_second_call_is_blocked(self) -> None:
        reader = FakeArxivReader()
        context = ResearchRunContext(TurnExecutionInput(
            dataset=_harness_tests.PythonHarnessPrototypeTest()._synthetic_dataset(),
            case_id="arxiv_tool",
            run_id="run_arxiv_tool",
            question="find external papers",
            conversation_messages=[],
            research_memory=ResearchMemory(),
            arxiv_reader=reader,
        ))
        tool = next(item for item in build_agent_tools(context) if item.name == "search_arxiv")
        arguments = json.dumps({"query_text": "small models"})
        first = json.loads(asyncio.run(tool.on_invoke_tool(
            ToolContext(context=context, tool_name="search_arxiv", tool_call_id="call_1", tool_arguments=arguments),
            arguments,
        )))
        second = json.loads(asyncio.run(tool.on_invoke_tool(
            ToolContext(context=context, tool_name="search_arxiv", tool_call_id="call_2", tool_arguments=arguments),
            arguments,
        )))
        self.assertEqual(1, first["returned_count"])
        self.assertEqual("ARXIV_SEARCH_LIMIT_REACHED", second["error"])
        self.assertEqual(1, len(reader.calls))


if __name__ == "__main__":
    unittest.main()
