from __future__ import annotations

import os
import hashlib
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import httpx

from harness_py.utils.errors import HarnessCancelled
from harness_py.corpus.gateway import JavaCorpusGateway, JavaCorpusGatewayReader
from harness_py.corpus_test_fixtures.in_memory_tools import InMemoryTools
from harness_py.corpus.tools import ReadingCorpusTools, model_facing_payload


class FakeGateway:
    def __init__(self):
        self.calls: list[tuple[str, dict]] = []

    def post(self, path: str, payload: dict) -> dict:
        self.calls.append((path, payload))
        if path.endswith("/papers/search"):
            if payload.get("identity"):
                return {
                    "status": "resolved",
                    "matches": [self._paper_card()],
                }
            return {
                "query_text": payload.get("query_text", ""),
                "candidates": [self._paper_card()],
                "matched_count": 1,
                "returned_count": 1,
                "coverage": "complete",
                "next_offset": None,
            }
        if path.endswith("/locations/search"):
            content = "Exact canonical content."
            return {
                "query_text": payload.get("query_text", ""),
                "locations": [{
                    "paper_id": "paper-a",
                    "title": "Paper A",
                    "paper_version": "rm-1",
                    "location_ref": "location_ref_a",
                    "section": "Methods",
                    "page": 3,
                    "element_type": "passage",
                    "preview": "Candidate only.",
                }],
                "matched_count": 1,
                "returned_count": 1,
                "coverage": "complete",
                "index_version": "test-index",
                "evidence_payloads": [{
                    "paper_id": "paper-a",
                    "model_version": "rm-1",
                    "location_ref": "location_ref_a",
                    "location_type": "PASSAGE",
                    "content_text": content,
                    "content_hash": hashlib.sha256(content.encode("utf-8")).hexdigest(),
                    "source_span_json": "{\"spans\":[]}",
                }],
            }
        if path.endswith("/locations/read"):
            return {
                "items": [{
                    "paper_id": "paper-a",
                    "title": "Paper A",
                    "paper_version": "rm-1",
                    "location_ref": "location_ref_a",
                    "element_type": "passage",
                    "page": 3,
                    "page_end": 4,
                    "section": "Methods",
                    "span_text": "Exact canonical content.",
                    "bbox_json": "{\"coordinateSystem\":\"top_left_1000\"}",
                    "parser_name": "mineru",
                    "parser_version": "1",
                    "page_screenshot_available": True,
                    "pdf_evidence_available": True,
                    "table_screenshot_available": False,
                    "figure_screenshot_available": False,
                    "asset_warnings": [],
                    "source_quotes": [{
                        "source_quote_ref": "source_quote_a",
                        "paper_id": "paper-a",
                        "paper_version": "rm-1",
                        "location_ref": "location_ref_a",
                        "page": 3,
                        "page_end": 3,
                        "section": "Methods",
                        "content_kind": "TABLE",
                        "content": "Exact canonical content.",
                    }],
                }],
                "missing_location_refs": [],
            }
        raise AssertionError(path)

    def _paper_card(self) -> dict:
        return {
            "paper_id": "paper-a",
            "title": "Paper A",
            "authors": ["Ada"],
            "year": 2026,
            "venue": "TestConf",
            "filename": "paper-a.pdf",
            "preview": "Abstract.",
        }


class JavaCorpusGatewayTest(unittest.TestCase):
    def test_gateway_loads_internal_token_from_env_file(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            env_path = Path(temporary) / ".env"
            env_path.write_text("RESEARCH_HARNESS_INTERNAL_TOKEN=local-token\n", encoding="utf-8")
            with patch.dict(os.environ, {"RESEARCH_HARNESS_INTERNAL_TOKEN": ""}):
                gateway = JavaCorpusGateway(env_path=env_path)
            try:
                self.assertEqual("Bearer local-token", gateway.client.headers["Authorization"])
            finally:
                gateway.client.close()

    def test_metadata_dataset_is_built_from_locked_scope_without_java_io(self) -> None:
        gateway = FakeGateway()
        reader = JavaCorpusGatewayReader(
            gateway=gateway,
            request_id="request-1",
            conversation_id="conversation-1",
            user_id=7,
            scope_paper_ids=["paper-a", "paper-b"],
        )

        dataset = reader.load_metadata_dataset()
        scoped_record = dataset.paper_records_by_id["paper-a"]

        self.assertEqual({"paper-a", "paper-b"}, set(dataset.paper_records_by_id))
        self.assertEqual([], gateway.calls)

        reader.search_papers({"paper_ids": ["paper-a"]})

        self.assertEqual(
            "Paper A",
            scoped_record["identity"]["title"],
        )

    def test_gateway_keeps_tool_authorization_and_exact_read_contract(self) -> None:
        gateway = FakeGateway()
        reader = JavaCorpusGatewayReader(
            gateway=gateway,
            request_id="request-1",
            conversation_id="conversation-1",
            user_id=7,
            scope_paper_ids=["paper-a"],
        )
        dataset = reader.load_metadata_dataset()
        tools = ReadingCorpusTools(dataset, reader=reader)

        paper_result = tools.search_paper_candidates({"query_text": "", "limit": 100})
        location_result = tools.search_paper_content({
            "paper_ids": ["paper-a"],
            "query_text": "canonical content",
            "top_k": 8,
        })

        self.assertEqual(1, paper_result["returned_count"])
        self.assertEqual("location_ref_a", location_result["locations"][0]["location_ref"])
        self.assertEqual("complete", location_result["coverage"])
        self.assertNotIn("evidence_payloads", model_facing_payload(location_result))
        self.assertNotIn("evidence_id", location_result["locations"][0])
        self.assertEqual({}, tools.observations_by_evidence_id)

        read_result = tools.read_paper_content({"location_refs": ["location_ref_a"]})

        self.assertEqual("Exact canonical content.", read_result["items"][0]["span_text"])
        source_quote_ref = read_result["items"][0]["source_quotes"][0]["source_quote_ref"]
        self.assertEqual("source_quote_a", source_quote_ref)
        self.assertIn(source_quote_ref, tools.observations_by_evidence_id)
        self.assertEqual("table", tools.observations_by_evidence_id[source_quote_ref]["element_type"])
        self.assertEqual("TABLE", tools.observations_by_evidence_id[source_quote_ref]["source_kind"])
        self.assertEqual("passage", read_result["items"][0]["element_type"])
        self.assertEqual("TEXT", read_result["items"][0]["source_kind"])
        self.assertEqual(4, read_result["items"][0]["page_end"])
        self.assertTrue(read_result["items"][0]["pdf_evidence_available"])
        self.assertTrue(read_result["items"][0]["page_screenshot_available"])
        self.assertEqual("{\"coordinateSystem\":\"top_left_1000\"}", read_result["items"][0]["bbox_json"])
        read_request = next(payload for path, payload in gateway.calls if path.endswith("/locations/read"))
        self.assertEqual("Exact canonical content.", read_request["evidence_payloads"][0]["content_text"])
        self.assertTrue(all(payload["user_id"] == 7 for _, payload in gateway.calls))
        self.assertTrue(all(payload["scope_paper_ids"] == ["paper-a"] for _, payload in gateway.calls))

    def test_read_rejects_location_not_disclosed_by_search(self) -> None:
        gateway = FakeGateway()
        reader = JavaCorpusGatewayReader(
            gateway=gateway,
            request_id="request-1",
            conversation_id="conversation-1",
            user_id=7,
            scope_paper_ids=["paper-a"],
        )
        tools = InMemoryTools(reader.load_metadata_dataset())

        result = tools.read_paper_content({"location_refs": ["location_ref_hidden"]})

        self.assertEqual("location_ref_not_disclosed", result["error"])
        self.assertFalse(any(path.endswith("/locations/read") for path, _ in gateway.calls))

    def test_java_and_in_memory_adapters_share_the_same_core_tool_contract(self) -> None:
        gateway = FakeGateway()
        reader = JavaCorpusGatewayReader(
            gateway=gateway,
            request_id="request-1",
            conversation_id="conversation-1",
            user_id=7,
            scope_paper_ids=["paper-a"],
        )
        dataset = reader.load_metadata_dataset()

        java_definitions = ReadingCorpusTools(dataset, reader=reader).definitions()
        in_memory_definitions = InMemoryTools(dataset).definitions()

        self.assertEqual(in_memory_definitions, java_definitions)

    def test_content_tools_reject_undisclosed_paper_ids_before_calling_java(self) -> None:
        gateway = FakeGateway()
        reader = JavaCorpusGatewayReader(
            gateway=gateway,
            request_id="request-1",
            conversation_id="conversation-1",
            user_id=7,
            scope_paper_ids=["paper-a"],
        )
        tools = ReadingCorpusTools(reader.load_metadata_dataset(), reader=reader)

        search = tools.search_paper_content({"paper_ids": ["paper-a.pdf"]})
        structure = tools.get_paper_structure({"paper_ids": ["paper-a.pdf"]})

        self.assertEqual("paper_not_authorized_for_reading", search["error"])
        self.assertEqual("PAPER_ID_NOT_DISCLOSED", search["error_code"])
        self.assertTrue(search["recoverable"])
        self.assertEqual(["paper-a.pdf"], search["unauthorized_paper_ids"])
        self.assertEqual("paper_not_authorized_for_reading", structure["error"])
        self.assertEqual("PAPER_ID_NOT_DISCLOSED", structure["error_code"])
        self.assertEqual(["paper-a.pdf"], structure["unauthorized_paper_ids"])
        self.assertEqual([], gateway.calls)

    def test_invalid_model_arguments_are_recoverable_before_java_io(self) -> None:
        gateway = FakeGateway()
        reader = JavaCorpusGatewayReader(
            gateway=gateway,
            request_id="request-1",
            conversation_id="conversation-1",
            user_id=7,
            scope_paper_ids=["paper-a"],
        )
        tools = ReadingCorpusTools(reader.load_metadata_dataset(), reader=reader)

        invalid_search = tools.search_paper_candidates({"limit": 0})
        tools.authorized_paper_ids.add("paper-a")
        invalid_content = tools.search_paper_content({"paper_ids": ["paper-a"], "top_k": "bad"})

        self.assertEqual("TOOL_ARGUMENTS_INVALID", invalid_search["error_code"])
        self.assertEqual("TOOL_ARGUMENTS_INVALID", invalid_content["error_code"])
        self.assertEqual([], gateway.calls)

    def test_gateway_rejects_oversized_response(self) -> None:
        client = httpx.Client(
            base_url="http://corpus.test",
            transport=httpx.MockTransport(lambda _request: httpx.Response(200, content=b"x" * 33)),
        )
        gateway = JavaCorpusGateway(client=client, max_response_bytes=32)

        with self.assertRaisesRegex(RuntimeError, "size limit"):
            gateway.post("/internal/v1/corpus/papers/search", {})

    def test_reader_checks_cancellation_before_corpus_request(self) -> None:
        gateway = FakeGateway()
        reader = JavaCorpusGatewayReader(
            gateway=gateway,
            request_id="request-1",
            conversation_id="conversation-1",
            user_id=7,
            scope_paper_ids=["paper-a"],
            cancel_check=lambda: True,
        )

        with self.assertRaises(HarnessCancelled):
            reader.search_papers({"query_text": ""})
        self.assertEqual([], gateway.calls)


if __name__ == "__main__":
    unittest.main()
