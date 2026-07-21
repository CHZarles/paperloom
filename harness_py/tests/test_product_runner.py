from __future__ import annotations

import io
import json
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

import httpx
import yaml

from harness_py.cli import main
from harness_py.core.models import GoldenDataset
from harness_py.corpus.gateway import JavaCorpusGateway, JavaCorpusGatewayReader
from harness_py.corpus.tools import ReadingCorpusTools
from harness_py.evaluation.golden_fixture import GoldenFixtureHarness
from harness_py.evaluation.dataset import load_dataset
from harness_py.evaluation.golden_case import (
    case_question,
    conversation_state_for_case,
    paper_ids_for_case,
)
from harness_py.evaluation.product_runner import (
    GoldenJavaCorpusReader,
    ProductCorpusMap,
    load_product_corpus_map,
    product_reader_for_case,
    validate_product_scope,
)
from harness_py.evaluation.scoring import BehaviorScorer
from harness_py.orchestration.live_chat import LiveResearchChatHarness
from harness_py.orchestration.runtime import TurnExecutionResult


class FakeJavaGateway:
    def __init__(self) -> None:
        self.calls: list[tuple[str, dict]] = []

    def post(self, path: str, payload: dict) -> dict:
        self.calls.append((path, payload))
        if path.endswith("/papers/search"):
            key = "matches" if payload.get("identity") else "candidates"
            return {
                "status": "resolved" if key == "matches" else None,
                key: [{
                    "paper_id": "product-a",
                    "title": "Paper A",
                    "authors": ["Ada"],
                    "year": 2026,
                    "venue": "TestConf",
                    "filename": "paper-a.pdf",
                    "preview": "Abstract.",
                }],
                "returned_count": 1,
            }
        if path.endswith("/locations/search"):
            return {
                "locations": [{
                    "paper_id": "product-a",
                    "location_ref": "location-a",
                    "page": 3,
                    "element_type": "section",
                    "preview": "Exact canonical content.",
                }],
                "returned_count": 1,
            }
        if path.endswith("/locations/read"):
            return {
                "items": [{
                    "paper_id": "product-a",
                    "title": "Paper A",
                    "paper_version": "rm-product-a",
                    "location_ref": "location-a",
                    "element_type": "section",
                    "page": 3,
                    "section": "Methods",
                    "span_text": "Exact canonical content.",
                }],
                "missing_location_refs": [],
            }
        raise AssertionError(path)


class ProductRunnerTest(unittest.TestCase):
    def test_product_reader_translates_identity_and_preserves_location_grounding(self) -> None:
        dataset = self._dataset()
        gateway = FakeJavaGateway()
        delegate = JavaCorpusGatewayReader(
            gateway=gateway,
            request_id="request-1",
            conversation_id="conversation-1",
            user_id=7,
            scope_paper_ids=["product-a"],
        )
        reader = GoldenJavaCorpusReader(
            delegate=delegate,
            mapping={"golden-a": "product-a"},
        )
        tools = ReadingCorpusTools(dataset, reader=reader)

        paper_result = tools.search_paper_candidates({"paper_ids": ["golden-a"]})
        location_result = tools.find_reading_locations({
            "paper_ids": ["golden-a"],
            "query_text": "canonical content",
            "top_k": 8,
        })
        read_result = tools.read_locations({"location_refs": ["location-a"]})

        self.assertEqual("golden-a", paper_result["candidates"][0]["paper_id"])
        self.assertEqual("golden-a", location_result["locations"][0]["paper_id"])
        evidence = read_result["items"][0]
        self.assertEqual("golden-a", evidence["paper_id"])
        self.assertNotIn("matched_anchor_ids", evidence)
        self.assertTrue(all(
            call[1]["scope_paper_ids"] == ["product-a"]
            for call in gateway.calls
        ))
        self.assertEqual(
            ["product-a"],
            next(payload for path, payload in gateway.calls if path.endswith("/locations/search"))[
                "paper_ids"
            ],
        )

        run = {
            "harness_id": "golden_fixture_v4",
            "status": "COMPLETED",
            "result_status": "COMPLETED",
            "research_answer": {
                "status": "COMPLETED",
                "outcome": "answered",
                "markdown": "The canonical content is exact. [1]",
                "cited_evidence_ids": [evidence["evidence_id"]],
            },
            "evidence_ledger": {"items": [evidence]},
        }
        score = BehaviorScorer().score_case(dataset, dataset.cases[0], run)
        self.assertTrue(score.case_status == "pass", score.to_dict())

    def test_mapping_validation_rejects_wrong_dataset_duplicates_and_missing_scope(self) -> None:
        dataset = self._dataset()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "map.yaml"
            path.write_text(yaml.safe_dump({
                "schema_version": "harness-golden-product-corpus-map/v1",
                "dataset_id": "wrong",
                "user_id": 7,
                "papers": {"golden-a": "product-a"},
            }), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "dataset_id mismatch"):
                load_product_corpus_map(path, dataset)

            path.write_text(yaml.safe_dump({
                "schema_version": "harness-golden-product-corpus-map/v1",
                "dataset_id": "dataset-a",
                "user_id": 7,
                "papers": {
                    "golden-a": "product-a",
                    "golden-b": "product-a",
                },
            }), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "reuses product paper IDs"):
                load_product_corpus_map(path, dataset)

        incomplete = ProductCorpusMap(
            dataset_id="dataset-a",
            user_id=7,
            product_paper_ids_by_golden_id={},
        )
        with self.assertRaisesRegex(ValueError, "does not cover"):
            validate_product_scope(dataset, dataset.cases, incomplete)

    def test_live_harness_tool_calls_cross_the_java_http_boundary_without_memory_fallback(self) -> None:
        dataset = self._dataset()
        requests: list[tuple[str, dict]] = []

        def handle(request: httpx.Request) -> httpx.Response:
            payload = json.loads(request.content)
            requests.append((request.url.path, payload))
            if request.url.path.endswith("/papers/search"):
                return httpx.Response(200, json={
                    "candidates": [{
                        "paper_id": "product-a",
                        "title": "Paper A",
                        "preview": "Abstract.",
                    }],
                    "returned_count": 1,
                })
            if request.url.path.endswith("/locations/search"):
                return httpx.Response(200, json={
                    "locations": [{
                        "paper_id": "product-a",
                        "location_ref": "location-a",
                        "page": 3,
                        "element_type": "section",
                        "preview": "Exact canonical content.",
                    }],
                    "returned_count": 1,
                })
            if request.url.path.endswith("/locations/read"):
                return httpx.Response(200, json={
                    "items": [{
                        "paper_id": "product-a",
                        "title": "Paper A",
                        "paper_version": "rm-product-a",
                        "location_ref": "location-a",
                        "element_type": "section",
                        "page": 3,
                        "section": "Methods",
                        "span_text": "Exact canonical content.",
                    }],
                    "missing_location_refs": [],
                })
            return httpx.Response(404)

        gateway = JavaCorpusGateway(
            client=httpx.Client(
                base_url="http://java.test",
                transport=httpx.MockTransport(handle),
            )
        )
        delegate = gateway.reader(
            request_id="request-1",
            conversation_id="conversation-1",
            user_id=7,
            scope_paper_ids=["product-a", "product-b"],
        )
        reader = GoldenJavaCorpusReader(
            delegate=delegate,
            mapping={"golden-a": "product-a", "golden-b": "product-b"},
        )

        class ToolCallingRuntime:
            def run_turn(self, turn):
                tools = ReadingCorpusTools(turn.dataset, reader=turn.corpus_reader)
                tools.search_paper_candidates({"paper_ids": ["golden-a"]})
                tools.find_reading_locations({
                    "paper_ids": ["golden-a"],
                    "query_text": "canonical content",
                    "top_k": 8,
                })
                evidence = tools.read_locations({
                    "location_refs": ["location-a"],
                })["items"][0]
                return TurnExecutionResult(run={
                    "run_id": turn.run_id,
                    "case_id": turn.case_id,
                    "status": "COMPLETED",
                    "result_status": "COMPLETED",
                    "research_answer": {
                        "status": "COMPLETED",
                        "outcome": "answered",
                        "cited_evidence_ids": [evidence["evidence_id"]],
                    },
                    "evidence_ledger": {"items": [evidence]},
                    "diagnostics": {},
                })

        state = conversation_state_for_case(dataset, dataset.cases[0])
        run, _ = LiveResearchChatHarness(ToolCallingRuntime()).run_turn(
            dataset,
            state,
            case_question(dataset.cases[0]),
            case_id_override="case-a",
            corpus_reader=reader,
        )

        self.assertEqual("COMPLETED", run["status"])
        self.assertEqual(
            [
                "/internal/v1/corpus/papers/search",
                "/internal/v1/corpus/locations/search",
                "/internal/v1/corpus/locations/read",
            ],
            [path for path, _ in requests],
        )
        self.assertTrue(all(
            payload["scope_paper_ids"] == ["product-a", "product-b"]
            for _, payload in requests
        ))
        self.assertNotIn("matched_anchor_ids", run["evidence_ledger"]["items"][0])

    def test_cli_passes_a_product_reader_to_the_golden_runner(self) -> None:
        dataset = self._dataset()

        class Provider:
            def public_diagnostics(self):
                return {"provider": "test"}

        class Harness:
            eval_capture_failures = 0

            def __init__(self) -> None:
                self.reader = None

            def run_case(self, _dataset, _case):
                raise AssertionError("java-qdrant must not call the in-memory run_case path")

            def run_turn(self, turn_dataset, state, _question, **kwargs):
                self.reader = kwargs.get("corpus_reader")
                run = GoldenFixtureHarness().run_case(turn_dataset, turn_dataset.cases[0])
                return run, state

        class Gateway:
            def reader(self, **kwargs):
                return object()

        harness = Harness()
        with tempfile.TemporaryDirectory() as directory:
            mapping = Path(directory) / "map.yaml"
            mapping.write_text(yaml.safe_dump({
                "schema_version": "harness-golden-product-corpus-map/v1",
                "dataset_id": "dataset-a",
                "user_id": 7,
                "papers": {
                    "golden-a": "product-a",
                    "golden-b": "product-b",
                },
            }), encoding="utf-8")
            out = Path(directory) / "out"
            with patch("harness_py.cli.load_dataset", return_value=dataset), patch(
                "harness_py.cli.JavaCorpusGateway",
                return_value=Gateway(),
            ), patch(
                "harness_py.cli._live_harness",
                return_value=(Provider(), harness),
            ), redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                code = main([
                    "--manifest", "ignored.yaml",
                    "agent-run",
                    "--product-corpus-map", str(mapping),
                    "--out", str(out),
                ])
            saved_run = json.loads(
                (out / "case-a" / "harness_run.json").read_text(encoding="utf-8")
            )

        self.assertEqual(0, code)
        self.assertIsInstance(harness.reader, GoldenJavaCorpusReader)
        self.assertEqual("java-qdrant", saved_run["diagnostics"]["corpus_backend"])

    def test_cli_rejects_missing_java_qdrant_map_before_loading_the_provider(self) -> None:
        dataset = self._dataset()
        with patch("harness_py.cli.load_dataset", return_value=dataset), patch(
            "harness_py.cli._live_harness",
        ) as live_harness, redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            code = main([
                "--manifest", "ignored.yaml",
                "agent-run",
            ])

        self.assertEqual(2, code)
        live_harness.assert_not_called()

    def test_cli_rejects_removed_compatibility_entries(self) -> None:
        for arguments in (
            ["agent-run", "--corpus-backend", "golden-memory"],
            ["run"],
            ["chat"],
            ["chat-shell"],
        ):
            with self.subTest(arguments=arguments):
                with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                    with self.assertRaises(SystemExit) as raised:
                        main(arguments)

                self.assertEqual(2, raised.exception.code)

    @unittest.skipUnless(
        os.getenv("GOLDEN_QDRANT_LIVE_SMOKE") == "1",
        "set GOLDEN_QDRANT_LIVE_SMOKE=1 to exercise the real Java/Qdrant/MySQL stack",
    )
    def test_real_java_qdrant_mysql_product_chain(self) -> None:
        manifest = os.getenv(
            "GOLDEN_QDRANT_MANIFEST",
            "research/golden-data/manifest.yaml",
        )
        mapping_path = os.environ["GOLDEN_PRODUCT_CORPUS_MAP"]
        case_id = os.getenv("GOLDEN_QDRANT_CASE_ID", "transformer_adam_params_001")
        dataset = load_dataset(manifest)
        case = next(item for item in dataset.cases if item["id"] == case_id)
        corpus_map = load_product_corpus_map(mapping_path, dataset)
        validate_product_scope(dataset, [case], corpus_map)
        state = conversation_state_for_case(dataset, case)
        reader = product_reader_for_case(
            JavaCorpusGateway(),
            dataset,
            case,
            corpus_map,
            request_id="golden-live-smoke",
            conversation_id=state.conversation_id,
        )
        tools = ReadingCorpusTools(dataset, reader=reader)
        scope = paper_ids_for_case(dataset, case)
        paper_result = tools.search_paper_candidates({
            "paper_ids": scope,
            "limit": len(scope),
        })
        self.assertEqual(len(scope), paper_result["returned_count"])
        locations = tools.find_reading_locations({
            "paper_ids": scope,
            "query_text": case_question(case),
            "top_k": 8,
        })["locations"]
        self.assertTrue(locations)
        read = tools.read_locations({
            "location_refs": [locations[0]["location_ref"]],
        })
        self.assertTrue(read["items"])

    def _dataset(self) -> GoldenDataset:
        return GoldenDataset(
            root=Path(".").resolve(),
            manifest_path=Path("manifest.yaml"),
            manifest={
                "schema_version": "harness-golden-data/v3",
                "dataset_id": "dataset-a",
            },
            paper_packs=[{
                "id": "pack-a",
                "papers": [{"id": "golden-a"}, {"id": "golden-b"}],
            }],
            cases=[{
                "id": "case-a",
                "paper_pack": "pack-a",
                "messages": [{"role": "user", "content": "Read the canonical content."}],
                "expect": {
                    "outcome": "answered",
                    "required_claims": ["canonical_content"],
                    "citations": "required",
                },
            }],
            paper_records_by_id={
                "golden-a": {
                    "paper_id": "golden-a",
                    "identity": {"title": "Paper A"},
                },
                "golden-b": {
                    "paper_id": "golden-b",
                    "identity": {"title": "Paper B"},
                },
            },
            anchors_by_id={
                "anchor-a": {
                    "anchor_id": "anchor-a",
                    "paper_id": "golden-a",
                    "element": {"page": 3},
                    "selector": {"exact_text": "Exact canonical content."},
                },
            },
            citation_edges=[],
            reading_models_by_paper_id={},
            claims_by_id={
                "canonical_content": {
                    "claim_id": "canonical_content",
                    "statement": "The canonical content is exact.",
                    "required_evidence": [{
                        "paper_id": "golden-a",
                        "accepted_locations": ["location-a"],
                    }],
                    "forbidden_paper_ids": [],
                    "fact_keys": [],
                }
            },
        )


if __name__ == "__main__":
    unittest.main()
