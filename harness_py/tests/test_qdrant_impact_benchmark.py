from __future__ import annotations

import importlib.util
import hashlib
import io
import json
import math
import sys
import tempfile
import unittest
import urllib.error
from collections import defaultdict
from pathlib import Path
from unittest import mock

from harness_py.corpus.tools import ReadingCorpusTools
from harness_py.evaluation.dataset import load_dataset
from harness_py.transport.provider_config import ProviderConfig


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT_PATH = REPO_ROOT / "research/golden-data/qdrant_impact_benchmark.py"
CONFIG_PATH = REPO_ROOT / "research/golden-data/qdrant-impact-benchmark.yaml"


def _load_benchmark_module():
    spec = importlib.util.spec_from_file_location(
        "qdrant_impact_benchmark_for_test", SCRIPT_PATH
    )
    if spec is None or spec.loader is None:
        raise RuntimeError("could not load qdrant impact benchmark module")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


benchmark = _load_benchmark_module()


class _JsonResponse:
    def __init__(self, payload, status: int = 200):
        self.status = status
        self.body = json.dumps(payload).encode("utf-8")

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return False

    def read(self) -> bytes:
        return self.body


def _http_error(status: int, body: bytes, headers=None) -> urllib.error.HTTPError:
    return urllib.error.HTTPError(
        "https://example.invalid/request",
        status,
        "request failed",
        headers or {},
        io.BytesIO(body),
    )


class QdrantImpactBenchmarkTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.config = benchmark.BenchmarkConfig.load(CONFIG_PATH)
        cls.dataset = load_dataset(cls.config.manifest)
        cls.documents = benchmark.build_index_documents(cls.dataset)
        cls.tasks = benchmark.load_saved_query_tasks(
            cls.dataset,
            cls.config.saved_runs,
            cls.config.saved_query_fills,
            cls.config.default_native_top_k,
            cls.config.max_top_k,
        )

    def test_frozen_workload_combines_primary_and_explicit_fill(self) -> None:
        preflight = benchmark.preflight_summary(
            self.dataset, self.documents, self.tasks
        )

        self.assertEqual(69, preflight["saved_query_count"])
        self.assertEqual(24, preflight["evidence_case_count"])
        self.assertEqual(48, preflight["required_anchor_obligation_count"])
        self.assertEqual(7, preflight["native_top_k_clamped_query_count"])
        self.assertEqual(58, preflight["saved_query_count_by_source"][
            str(self.config.saved_runs)
        ])
        fill_root = self.config.saved_query_fills[
            "mint_vs_tau_interaction_comparison_001"
        ]
        self.assertEqual(11, preflight["saved_query_count_by_source"][str(fill_root)])
        mint_tasks = [
            task
            for task in self.tasks
            if task.case_id == "mint_vs_tau_interaction_comparison_001"
        ]
        self.assertEqual(11, len(mint_tasks))
        self.assertTrue(all(task.source_run_root == str(fill_root) for task in mint_tasks))

    def test_document_embedding_batch_matches_java_product_configuration(self) -> None:
        self.assertEqual(10, self.config.embedding_batch_size)

    def test_provenance_hashes_all_reproducibility_inputs_and_git_state(self) -> None:
        provenance = benchmark.build_benchmark_provenance(
            self.config,
            self.tasks,
        )

        self.assertEqual(
            hashlib.sha256(SCRIPT_PATH.read_bytes()).hexdigest(),
            provenance["benchmark_script"]["sha256"],
        )
        self.assertEqual(
            hashlib.sha256(CONFIG_PATH.read_bytes()).hexdigest(),
            provenance["benchmark_config"]["sha256"],
        )
        self.assertEqual(
            hashlib.sha256(self.config.manifest.read_bytes()).hexdigest(),
            provenance["dataset_manifest"]["sha256"],
        )
        expected_source_count = len({
            (task.source_run_root, task.case_id)
            for task in self.tasks
        })
        saved_sources = provenance["saved_query_sources"]
        self.assertEqual(expected_source_count, saved_sources["file_count"])
        self.assertEqual(len(self.tasks), saved_sources["extracted_query_count"])
        self.assertEqual(64, len(saved_sources["sha256"]))
        self.assertEqual(64, len(saved_sources["extracted_queries_sha256"]))
        self.assertEqual(
            benchmark.DATASET_FINGERPRINT_ALGORITHM,
            provenance["dataset_content"]["algorithm"],
        )
        self.assertEqual(64, len(provenance["dataset_content"]["sha256"]))
        self.assertIn("worktree_dirty", provenance["git"])
        self.assertIn("relevant_status_porcelain", provenance["git"])

    def test_index_projection_preserves_all_authored_anchor_targets(self) -> None:
        preflight = benchmark.preflight_summary(
            self.dataset, self.documents, self.tasks
        )
        granularity = benchmark.index_granularity_metrics(self.documents)

        self.assertEqual(789, preflight["indexed_point_count"])
        self.assertEqual(29, preflight["indexed_anchor_count"])
        self.assertEqual([], preflight["missing_indexed_anchor_ids"])
        self.assertTrue(
            all(
                0 < len(document.searchable_text) <= benchmark.MAX_EMBEDDING_TEXT_CHARS
                for document in self.documents
            )
        )
        self.assertEqual(
            len(self.documents),
            granularity["searchable_span_chars"]["count"],
        )
        self.assertEqual(
            len(self.documents),
            sum(granularity["point_count_by_location_type"].values()),
        )

    def test_current_bm25_frozen_case_union_baseline_is_44_of_48(self) -> None:
        tools = ReadingCorpusTools(self.dataset)
        hits_by_case: dict[str, set[str]] = defaultdict(set)
        cases_with_queries = {task.case_id for task in self.tasks}
        for task in self.tasks:
            tools.authorized_paper_ids.update(task.paper_ids)
            arguments = dict(task.arguments)
            arguments["top_k"] = task.native_top_k
            payload = tools.find_reading_locations(arguments)
            self.assertNotIn("error", payload, task.query_id)
            for location in payload["locations"]:
                document = tools.documents_by_location[location["location_ref"]]
                hits_by_case[task.case_id].update(document.matched_anchor_ids)

        hit_count = 0
        required_count = 0
        full_case_count = 0
        eligible_case_count = 0
        for case in self.dataset.cases:
            case_id = str(case["id"])
            required = set(case.get("expect", {}).get("evidence", {}).get("required", []))
            if not required or case_id not in cases_with_queries:
                continue
            matched = required & hits_by_case[case_id]
            eligible_case_count += 1
            hit_count += len(matched)
            required_count += len(required)
            full_case_count += matched == required

        self.assertEqual((44, 48), (hit_count, required_count))
        self.assertEqual((20, 24), (full_case_count, eligible_case_count))

    def test_sparse_vector_matches_java_hash_and_term_frequency_contract(self) -> None:
        vector = benchmark.sparse_vector("attention attention transformer")

        self.assertEqual([907776075, 1618509095], vector["indices"])
        self.assertEqual(1.0, vector["values"][0])
        self.assertAlmostEqual(1.0 + math.log(2), vector["values"][1])
        self.assertEqual(vector, benchmark.sparse_vector("attention attention transformer"))

    def test_rrf_and_production_coverage_are_deterministic(self) -> None:
        dense = [
            benchmark.SearchHit("a1", 0.9, {"paper_id": "a"}),
            benchmark.SearchHit("a2", 0.8, {"paper_id": "a"}),
            benchmark.SearchHit("b1", 0.7, {"paper_id": "b"}),
        ]
        sparse = [
            benchmark.SearchHit("a2", 9.0, {"paper_id": "a"}),
            benchmark.SearchHit("a1", 8.0, {"paper_id": "a"}),
            benchmark.SearchHit("b1", 7.0, {"paper_id": "b"}),
        ]

        fused = benchmark.fuse_hits(dense, sparse, rrf_k=60)
        covered = benchmark.select_paper_coverage(fused, ("a", "b"), 2)

        self.assertEqual(["a1", "a2", "b1"], [hit.location_ref for hit in fused])
        self.assertEqual({"a", "b"}, {hit.payload["paper_id"] for hit in covered})
        self.assertEqual(
            ["a1", "b1"],
            [hit.location_ref for hit in covered],
        )

    def test_embedding_contract_supports_minimax_without_exposing_key(self) -> None:
        provider = ProviderConfig(
            scope="embedding",
            provider="minimax",
            api_style="minimax-embedding",
            api_base_url="https://api.minimaxi.com/v1",
            model="embo-01",
            api_key="private-key",
        )

        request = benchmark.embedding_request_body(provider, ["one"], "query")
        vectors, tokens = benchmark.parse_embedding_response(
            provider,
            {
                "base_resp": {"status_code": 0},
                "vectors": [[0.1, 0.2]],
                "total_tokens": 3,
            },
            ["one"],
        )

        self.assertEqual(
            {"model": "embo-01", "texts": ["one"], "type": "query"},
            request,
        )
        self.assertEqual([[0.1, 0.2]], vectors)
        self.assertEqual(3, tokens)
        self.assertNotIn("private-key", str(provider.public_diagnostics()))

    def test_embedding_retries_timeout_and_rate_limit_with_attempt_evidence(self) -> None:
        provider = ProviderConfig(
            scope="embedding",
            provider="minimax",
            api_style="minimax-embedding",
            api_base_url="https://api.minimaxi.com/v1",
            model="embo-01",
            api_key="private-key",
        )
        sleeps: list[float] = []
        with tempfile.TemporaryDirectory() as directory:
            journal_path = Path(directory) / "request_attempts.jsonl"
            journal = benchmark.RequestAttemptJournal(
                journal_path,
                secrets=(provider.api_key,),
            )
            gateway = benchmark.EmbeddingGateway(
                provider,
                batch_size=1,
                timeout_seconds=90,
                retry_policy=benchmark.RetryPolicy(
                    max_attempts=3,
                    initial_backoff_seconds=0.1,
                    max_backoff_seconds=0.5,
                ),
                attempt_journal=journal,
                sleeper=sleeps.append,
            )
            rate_limit = _http_error(
                429,
                b'{"error":"private-key rate limited"}',
                {"Retry-After": "0.3"},
            )
            success = _JsonResponse({
                "base_resp": {"status_code": 0},
                "vectors": [[0.1, 0.2]],
                "total_tokens": 3,
            })
            with mock.patch.object(
                benchmark.urllib.request,
                "urlopen",
                side_effect=[TimeoutError("timed out"), rate_limit, success],
            ) as urlopen:
                vector, tokens, _ = gateway.embed_query("one")

            rows = [
                json.loads(line)
                for line in journal_path.read_text(encoding="utf-8").splitlines()
            ]

        self.assertEqual([0.1, 0.2], vector)
        self.assertEqual(3, tokens)
        self.assertEqual(3, urlopen.call_count)
        self.assertEqual([0.1, 0.3], sleeps)
        self.assertEqual(
            ["retry_scheduled", "retry_scheduled", "succeeded"],
            [row["outcome"] for row in rows],
        )
        self.assertEqual(1, len({row["request_id"] for row in rows}))
        self.assertEqual(1, len({row["request_body_sha256"] for row in rows}))
        self.assertNotIn("private-key", json.dumps(rows))
        self.assertEqual(
            {
                "attempt_count": 3,
                "logical_request_count": 1,
                "outcomes": {"retry_scheduled": 2, "succeeded": 1},
                "total_backoff_ms": 400.0,
            },
            {
                key: journal.summary()[key]
                for key in (
                    "attempt_count",
                    "logical_request_count",
                    "outcomes",
                    "total_backoff_ms",
                )
            },
        )

    def test_qdrant_retries_5xx_and_network_failure_then_succeeds(self) -> None:
        sleeps: list[float] = []
        with tempfile.TemporaryDirectory() as directory:
            journal_path = Path(directory) / "request_attempts.jsonl"
            journal = benchmark.RequestAttemptJournal(journal_path)
            gateway = benchmark.QdrantGateway(
                base_url="http://127.0.0.1:6333",
                api_key="",
                collection="benchmark",
                timeout_seconds=30,
                upsert_batch_size=64,
                retry_policy=benchmark.RetryPolicy(
                    max_attempts=3,
                    initial_backoff_seconds=0.25,
                    max_backoff_seconds=0.5,
                ),
                attempt_journal=journal,
                sleeper=sleeps.append,
            )
            with mock.patch.object(
                benchmark.urllib.request,
                "urlopen",
                side_effect=[
                    _http_error(503, b'{"status":"busy"}'),
                    urllib.error.URLError("connection reset"),
                    _JsonResponse({"result": {"count": 7}}),
                ],
            ) as urlopen:
                count = gateway.count_generation("generation-a")
            rows = [
                json.loads(line)
                for line in journal_path.read_text(encoding="utf-8").splitlines()
            ]

        self.assertEqual(7, count)
        self.assertEqual(3, urlopen.call_count)
        self.assertEqual([0.25, 0.5], sleeps)
        self.assertEqual([503, None, 200], [row.get("status_code") for row in rows])
        self.assertTrue(all(row["service"] == "qdrant" for row in rows))

    def test_retry_budget_is_bounded_and_terminal_failure_is_recorded(self) -> None:
        sleeps: list[float] = []
        with tempfile.TemporaryDirectory() as directory:
            journal_path = Path(directory) / "request_attempts.jsonl"
            journal = benchmark.RequestAttemptJournal(journal_path)
            with mock.patch.object(
                benchmark.urllib.request,
                "urlopen",
                side_effect=[
                    TimeoutError("first"),
                    TimeoutError("second"),
                    TimeoutError("third"),
                ],
            ) as urlopen:
                with self.assertRaisesRegex(RuntimeError, "third"):
                    benchmark._post_json(
                        "https://example.invalid/embeddings",
                        {"texts": ["one"]},
                        headers={},
                        timeout_seconds=1,
                        operation="embedding request",
                        retry_policy=benchmark.RetryPolicy(
                            max_attempts=3,
                            initial_backoff_seconds=0.1,
                            max_backoff_seconds=0.2,
                        ),
                        attempt_journal=journal,
                        sleeper=sleeps.append,
                    )
            rows = [
                json.loads(line)
                for line in journal_path.read_text(encoding="utf-8").splitlines()
            ]

        self.assertEqual(3, urlopen.call_count)
        self.assertEqual([0.1, 0.2], sleeps)
        self.assertEqual("failed", rows[-1]["outcome"])
        self.assertEqual(0.0, rows[-1]["backoff_ms"])

    def test_permanent_http_error_is_not_retried(self) -> None:
        sleeps: list[float] = []
        with mock.patch.object(
            benchmark.urllib.request,
            "urlopen",
            side_effect=_http_error(400, b'{"error":"invalid request"}'),
        ) as urlopen:
            with self.assertRaisesRegex(RuntimeError, "HTTP 400"):
                benchmark._post_json(
                    "https://example.invalid/embeddings",
                    {"texts": ["one"]},
                    headers={},
                    timeout_seconds=1,
                    operation="embedding request",
                    retry_policy=benchmark.RetryPolicy(max_attempts=4),
                    sleeper=sleeps.append,
                )

        self.assertEqual(1, urlopen.call_count)
        self.assertEqual([], sleeps)

    def test_query_pacing_does_not_slow_document_batches(self) -> None:
        provider = ProviderConfig(
            scope="embedding",
            provider="minimax",
            api_style="minimax-embedding",
            api_base_url="https://api.minimaxi.com/v1",
            model="embo-01",
            api_key="private-key",
        )
        now = [0.0]
        sleeps: list[float] = []

        def sleep(seconds: float) -> None:
            sleeps.append(seconds)
            now[0] += seconds

        with tempfile.TemporaryDirectory() as directory:
            journal = benchmark.RequestAttemptJournal(
                Path(directory) / "request_attempts.jsonl"
            )
            gateway = benchmark.EmbeddingGateway(
                provider,
                batch_size=1,
                timeout_seconds=90,
                query_min_interval_seconds=1.05,
                attempt_journal=journal,
                sleeper=sleep,
                clock=lambda: now[0],
            )
            responses = [
                _JsonResponse({
                    "base_resp": {"status_code": 0},
                    "vectors": [[0.1]],
                    "total_tokens": 1,
                })
                for _ in range(4)
            ]
            with mock.patch.object(
                benchmark.urllib.request,
                "urlopen",
                side_effect=responses,
            ):
                gateway.embed_documents(["db-one", "db-two"])
                gateway.embed_query("query-one")
                _, _, second_query_duration_ms = gateway.embed_query("query-two")

            summary = journal.summary()

        self.assertEqual([1.05], sleeps)
        self.assertEqual(0.0, second_query_duration_ms)
        self.assertEqual(1, summary["pacing_wait_count"])
        self.assertEqual(1050.0, summary["total_pacing_ms"])

    def test_mysql_container_resolution_is_explicit_and_reproducible(self) -> None:
        with mock.patch.dict(
            benchmark.os.environ,
            {self.config.embedding_mysql_container_env: "from-env"},
        ):
            self.assertEqual(
                ("from-cli", "cli:--mysql-container"),
                benchmark.resolve_mysql_container(self.config, "from-cli"),
            )
            self.assertEqual(
                (
                    "from-env",
                    f"env:{self.config.embedding_mysql_container_env}",
                ),
                benchmark.resolve_mysql_container(self.config, None),
            )
        with mock.patch.dict(benchmark.os.environ, {}, clear=True):
            self.assertEqual(
                (
                    self.config.embedding_default_mysql_container,
                    "config:default_mysql_container",
                ),
                benchmark.resolve_mysql_container(self.config, None),
            )

    def test_qdrant_filter_scopes_generation_papers_and_pages(self) -> None:
        result = benchmark._qdrant_filter(
            "generation-a", ("paper-a", "paper-b"), 2, 7
        )

        self.assertEqual(
            {"value": "generation-a"}, result["must"][0]["match"]
        )
        self.assertEqual(
            {"any": ["paper-a", "paper-b"]}, result["must"][1]["match"]
        )
        self.assertEqual({"gte": 2, "lte": 7}, result["must"][2]["range"])

    def test_summary_reports_fixed_native_case_union_and_regressions(self) -> None:
        anchor_id = "bert_transformer_encoder_background"
        hit = benchmark._ranking_artifact(
            (anchor_id,), ("hit-location",), ((anchor_id,),)
        )
        miss = benchmark._ranking_artifact(
            (anchor_id,), ("miss-location",), ((),)
        )

        def mode(value):
            return {
                "evaluation_depth_latency": {
                    "measurement_scope": benchmark.LATENCY_MEASUREMENT_SCOPE,
                    "evaluation_depth": max(self.config.cutoffs),
                    "native_top_k": max(self.config.cutoffs),
                    "native_workload_latency_measured": False,
                    "total_ms": 1.0,
                    "components_ms": {"synthetic": 1.0},
                    "interpretation": benchmark.LATENCY_INTERPRETATION,
                },
                "fixed": {str(cutoff): value for cutoff in self.config.cutoffs},
                "native": value,
            }

        row = {
            "query_id": "bert_transformer_role_001:find:01",
            "case_id": "bert_transformer_role_001",
            "relevant_anchor_ids": [anchor_id],
            "modes": {
                "bm25": mode(hit),
                "qdrant_sparse": mode(miss),
                "qdrant_dense": mode(hit),
                "qdrant_hybrid_rrf": mode(hit),
                "qdrant_production_hybrid_coverage": mode(hit),
            },
        }

        report = benchmark.summarize_results(
            self.dataset, [row], self.config.cutoffs
        )

        self.assertEqual(
            1.0,
            report["modes"]["bm25"]["recall_at"]["1"]["value"],
        )
        self.assertEqual(
            1,
            report["modes"]["bm25"]["headline_case_union_exact_anchor"][
                "full_case_count"
            ],
        )
        sparse_transition = report["miss_transitions"]["case_union_native"][
            "qdrant_sparse"
        ]
        self.assertEqual(1, sparse_transition["counts"]["regressed"])
        self.assertEqual(anchor_id, sparse_transition["changed"][0]["anchor_id"])
        latency = report["modes"]["bm25"]["evaluation_depth_latency"]
        self.assertEqual("evaluation_depth", latency["measurement_scope"])
        self.assertFalse(latency["native_workload_latency_measured"])
        self.assertNotIn("latency_ms", report["modes"]["bm25"])

    def test_query_artifact_never_labels_offline_latency_as_native(self) -> None:
        task = self.tasks[0]
        artifact = benchmark._query_mode_artifact(
            task,
            {"native": (), **{cutoff: () for cutoff in self.config.cutoffs}},
            {"native": (), **{cutoff: () for cutoff in self.config.cutoffs}},
            {"total_ms": 3.0, "components_ms": {"search": 3.0}},
            self.config.cutoffs,
        )

        latency = artifact["evaluation_depth_latency"]
        self.assertEqual("evaluation_depth", latency["measurement_scope"])
        self.assertFalse(latency["native_workload_latency_measured"])
        self.assertEqual(max(max(self.config.cutoffs), task.native_top_k), latency["evaluation_depth"])
        self.assertNotIn("latency_ms", artifact)


if __name__ == "__main__":
    unittest.main()
