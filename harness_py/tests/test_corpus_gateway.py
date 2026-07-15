from __future__ import annotations

import unittest

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
        read_result = tools.read_locations({"location_refs": ["location_ref_a"]})

        self.assertEqual(1, paper_result["returned_count"])
        self.assertEqual("location_ref_a", location_result["locations"][0]["location_ref"])
        self.assertEqual("Exact canonical content.", read_result["items"][0]["span_text"])
        evidence_id = read_result["items"][0]["evidence_id"]
        self.assertIn(evidence_id, tools.observations_by_evidence_id)
        self.assertEqual("paragraph", read_result["items"][0]["element_type"])
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
            reader.load_metadata_dataset()
        self.assertEqual([], gateway.calls)


if __name__ == "__main__":
    unittest.main()
