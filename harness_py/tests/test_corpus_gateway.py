from __future__ import annotations

import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import httpx

from harness_py.core.errors import HarnessCancelled
from harness_py.corpus.gateway import JavaCorpusGateway, JavaCorpusGatewayReader
from harness_py.corpus.tools import ReadingCorpusTools


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
            return {
                "query_text": payload.get("query_text", ""),
                "locations": [{
                    "paper_id": "paper-a",
                    "title": "Paper A",
                    "paper_version": "rm-1",
                    "location_ref": "location_ref_a",
                    "section": "Methods",
                    "page": 3,
                    "element_type": "paragraph",
                    "preview": "Candidate only.",
                }],
                "matched_count": 1,
                "returned_count": 1,
                "coverage": "complete",
                "index_version": "test-index",
            }
        if path.endswith("/locations/read"):
            return {
                "items": [{
                    "paper_id": "paper-a",
                    "title": "Paper A",
                    "paper_version": "rm-1",
                    "location_ref": "location_ref_a",
                    "element_type": "section",
                    "page": 3,
                    "section": "Methods",
                    "span_text": "Exact canonical content.",
                    "parser_name": "mineru",
                    "parser_version": "1",
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
        location_result = tools.find_reading_locations({
            "paper_ids": ["paper-a"],
            "query_text": "canonical content",
            "top_k": 8,
        })

        self.assertEqual(1, paper_result["returned_count"])
        self.assertEqual("location_ref_a", location_result["locations"][0]["location_ref"])
        self.assertEqual("complete", location_result["coverage"])
        self.assertNotIn("evidence_id", location_result["locations"][0])
        self.assertEqual({}, tools.observations_by_evidence_id)

        read_result = tools.read_locations({"location_refs": ["location_ref_a"]})

        self.assertEqual("Exact canonical content.", read_result["items"][0]["span_text"])
        evidence_id = read_result["items"][0]["evidence_id"]
        self.assertEqual("ev_e04a65dc77b9655f", evidence_id)
        self.assertIn(evidence_id, tools.observations_by_evidence_id)
        self.assertEqual("section", read_result["items"][0]["element_type"])
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
        tools = ReadingCorpusTools(reader.load_metadata_dataset(), reader=reader)

        result = tools.read_locations({"location_refs": ["location_ref_hidden"]})

        self.assertEqual("location_not_disclosed_for_reading", result["error"])
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
        in_memory_definitions = ReadingCorpusTools(dataset).definitions()

        self.assertEqual(in_memory_definitions, java_definitions)

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
