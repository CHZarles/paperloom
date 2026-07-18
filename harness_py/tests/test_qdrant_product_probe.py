from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "research/golden-data/qdrant_product_probe.py"
SPEC = importlib.util.spec_from_file_location("qdrant_product_probe", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class QdrantProductProbeTest(unittest.TestCase):
    def test_script_path_is_anchored_to_test_file_not_working_directory(self) -> None:
        self.assertTrue(SCRIPT.is_file())
        self.assertEqual(REPO_ROOT, SCRIPT.parents[2])

    def test_quality_report_uses_every_configured_case_and_best_cross_query_rank(self) -> None:
        required = {"case": {"anchor"}, "case_without_hit": {"missing"}}
        bm25 = [
            {"case_id": "case", "elapsed_ms": 2, "candidates": []},
            {
                "case_id": "case",
                "elapsed_ms": 3,
                "candidates": [{"rank": 4, "anchor_ids": ["anchor"]}],
            },
        ]
        qdrant = [{
            "case_id": "case",
            "elapsed_ms": 10,
            "candidates": [{"rank": 2, "anchor_ids": ["anchor"]}],
        }]

        report = MODULE._quality_report(required, {
            "python_bm25": bm25,
            "java_qdrant_lexical": qdrant,
        })

        self.assertEqual(2, report["summary"]["python_bm25"]["case_count"])
        self.assertEqual(1, report["summary"]["python_bm25"]["candidate_hits"])
        self.assertEqual(4, report["summary"]["python_bm25"]["median_best_rank"])
        self.assertEqual(2, report["summary"]["java_qdrant_lexical"]["median_best_rank"])
        self.assertEqual(1, len(report["transitions"]["improved"]))
        self.assertEqual(
            "strict_requested_top_k",
            report["conditions"]["candidate_budget_policy"],
        )

    def test_configured_case_without_saved_location_query_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            case_id = "case_without_query"
            trace = root / case_id / "react_trace.json"
            trace.parent.mkdir(parents=True)
            trace.write_text(json.dumps([{"tool_name": "read_locations"}]), encoding="utf-8")
            selected = {
                case_id: {
                    "expect": {"evidence": {"required": ["anchor"]}},
                }
            }

            with self.assertRaisesRegex(RuntimeError, "no saved find_reading_locations query"):
                MODULE._load_query_probes(
                    {"saved_runs": str(root)},
                    selected,
                    {"paper": "product-paper"},
                )

    def test_bm25_quality_is_truncated_to_saved_requested_top_k(self) -> None:
        class FakeTools:
            def __init__(self, _dataset):
                self.authorized_paper_ids = set()
                self.documents = []

            def find_reading_locations(self, _arguments):
                return {
                    "matched_count": 12,
                    "locations": [
                        {"paper_id": "paper", "location_ref": f"ref-{index}"}
                        for index in range(12)
                    ],
                }

        probe = MODULE.QueryProbe(
            "case",
            0,
            {"paper_ids": ["paper"], "query_text": "query", "top_k": 5},
            ("anchor",),
        )
        with patch.object(MODULE, "ReadingCorpusTools", FakeTools):
            rows = MODULE._run_bm25_quality(object(), [probe])

        self.assertEqual(5, rows[0]["returned_count"])
        self.assertEqual(12, rows[0]["raw_returned_count"])
        self.assertEqual([1, 2, 3, 4, 5], [item["rank"] for item in rows[0]["candidates"]])

    def test_output_paths_are_unique_by_default_and_existing_explicit_path_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with patch.object(MODULE, "REPO_ROOT", root):
                first = MODULE._reserve_output(None)
                second = MODULE._reserve_output(None)
            self.assertNotEqual(first, second)
            existing = root / "already-exists"
            existing.mkdir()
            with self.assertRaisesRegex(RuntimeError, "refusing to overwrite"):
                MODULE._reserve_output(existing)

    def test_run_journal_preserves_completed_stage_when_later_stage_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            journal = MODULE.RunJournal(output, ["probe"])
            journal.start("setup")
            journal.complete("setup", artifacts=["run-context.json"])
            journal.start("quality")
            journal.fail(RuntimeError("late failure"))

            state = json.loads((output / "run-state.json").read_text(encoding="utf-8"))
            self.assertEqual("failed", state["status"])
            self.assertEqual("completed", state["stages"]["setup"]["status"])
            self.assertEqual("failed", state["stages"]["quality"]["status"])

    def test_anchor_matching_requires_paper_page_and_normalized_quote(self) -> None:
        anchors = {
            "a": {
                "paper_id": "paper",
                "element": {"page": 3},
                "selector": {"exact_text": "Fine-grained progress rate"},
            }
        }

        matched = MODULE._matching_anchor_ids({
            "paper_id": "paper",
            "page": 3,
            "span_text": "A FINE-GRAINED   progress rate is reported.",
        }, anchors)

        self.assertEqual({"a"}, matched)
        self.assertEqual(set(), MODULE._matching_anchor_ids({
            "paper_id": "paper",
            "page": 4,
            "span_text": "Fine-grained progress rate",
        }, anchors))

    def test_scope_keeps_target_and_caps_requested_size(self) -> None:
        self.assertEqual(["target", "a"], MODULE._scope_with_target(
            ["a", "b", "target"], "target", 2
        ))
        self.assertEqual(["target", "a", "b"], MODULE._scope_with_target(
            ["a", "b", "target"], "target", 99
        ))

    def test_latency_rows_require_exact_scope_ids_and_model_versions(self) -> None:
        row = {
            "scope_size": 2,
            "scope_paper_ids": ["paper-a", "paper-b"],
            "scope_model_versions": {"paper-a": "v1", "paper-b": "v2"},
        }
        bm25_row = {
            **row,
            "modes": {
                "narrow": {"search_ms": [1.0]},
                "broad": {"search_ms": [2.0]},
            },
        }
        qdrant_row = {**row, "mode": "narrow", "search_ms": [3.0]}
        report = MODULE._latency_report([2], 8, 0, 1, [bm25_row], [qdrant_row])
        self.assertEqual(["paper-a", "paper-b"], report["python_bm25"][0]["scope_paper_ids"])

        invalid = {**bm25_row, "scope_model_versions": {"paper-a": "v1"}}
        with self.assertRaisesRegex(RuntimeError, "incomplete scope model versions"):
            MODULE._latency_report([2], 8, 0, 1, [invalid], [])

    def test_latency_benchmark_only_uses_cases_with_an_explicit_query(self) -> None:
        cases = MODULE._configured_latency_cases({
            "cases": [
                {"case_id": "quality-only"},
                {"case_id": "latency", "latency_query": "saved query"},
            ]
        })

        self.assertEqual(["latency"], [case["case_id"] for case in cases])
        with self.assertRaisesRegex(RuntimeError, "declare latency_query"):
            MODULE._configured_latency_cases({"cases": [{"case_id": "quality-only"}]})

    def test_model_contract_requires_remote_provider_model_style_and_token_parity(self) -> None:
        local = {
            "provider": "minimax",
            "model": "MiniMax-M3",
            "api_style": "openai-compatible",
        }
        health = {
            "status": "ok",
            "provider": dict(local),
            "max_completion_tokens": 3000,
        }

        contract = MODULE._validate_model_contract(local, health, 3000)

        self.assertTrue(contract["parity_verified"])
        self.assertTrue(contract["max_completion_tokens_match"])
        mismatch = copy.deepcopy(health)
        mismatch["provider"]["model"] = "different-model"
        with self.assertRaisesRegex(RuntimeError, "model-provider parity"):
            MODULE._validate_model_contract(local, mismatch, 3000)
        mismatch = copy.deepcopy(health)
        mismatch["max_completion_tokens"] = 4096
        with self.assertRaisesRegex(RuntimeError, "max_completion_tokens parity"):
            MODULE._validate_model_contract(local, mismatch, 3000)

    def test_committed_adjudication_is_frozen_complete_and_human_labelled(self) -> None:
        adjudication = MODULE._load_yaml(
            REPO_ROOT / "research/golden-data/qdrant-product-probe-adjudication.yaml"
        )
        source = json.loads(
            (REPO_ROOT / adjudication["source"]).read_text(encoding="utf-8")
        )
        required = {
            "agentbench_environment_inventory_001": {
                "agentbench_eight_environments",
                "agentbench_failure_obstacles",
            },
            "gaia_dataset_gap_001": {
                "gaia_human_gpt4_gap",
                "gaia_question_construction",
            },
            "agentboard_progress_metric_001": {
                "agentboard_final_success_rate_limit",
                "agentboard_progress_rate_metric",
            },
        }

        result = MODULE._apply_adjudication(adjudication, required, source["queries"])

        self.assertEqual(4, result["reviewed_exact_misses"])
        self.assertEqual(6, result["adjudicated_hits"])
        self.assertEqual("human", result["label_source"])

    def test_adjudication_rejects_hash_rank_duplicates_exact_hits_and_incomplete_review(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            source_path = Path(temporary) / "qdrant-quality.json"
            rows = [{
                "case_id": "case",
                "query_index": 0,
                "candidates": [{
                    "rank": 2,
                    "location_ref": "ref",
                    "anchor_ids": [],
                }],
            }]
            source_path.write_text(json.dumps({"queries": rows}), encoding="utf-8")
            source_hash = hashlib.sha256(source_path.read_bytes()).hexdigest()
            decision = {
                "case_id": "case",
                "anchor_id": "anchor",
                "status": "equivalent",
                "label_source": "human",
                "query_index": 0,
                "rank": 2,
                "location_ref": "ref",
                "reason": "Human review found equivalent evidence.",
            }
            base = {
                "schema_version": MODULE.ADJUDICATION_SCHEMA_VERSION,
                "source": str(source_path),
                "source_sha256": source_hash,
                "decisions": [decision],
            }
            required = {"case": {"anchor"}}

            bad_hash = {**base, "source_sha256": "0" * 64}
            with self.assertRaisesRegex(RuntimeError, "source hash mismatch"):
                MODULE._apply_adjudication(bad_hash, required, rows)

            wrong_rank = copy.deepcopy(base)
            wrong_rank["decisions"][0]["rank"] = 1
            with self.assertRaisesRegex(RuntimeError, "rank mismatch"):
                MODULE._apply_adjudication(wrong_rank, required, rows)

            duplicate = copy.deepcopy(base)
            duplicate["decisions"].append(copy.deepcopy(decision))
            with self.assertRaisesRegex(RuntimeError, "duplicate adjudication"):
                MODULE._apply_adjudication(duplicate, required, rows)

            incomplete = copy.deepcopy(base)
            with self.assertRaisesRegex(RuntimeError, "review every exact miss"):
                MODULE._apply_adjudication(
                    incomplete,
                    {"case": {"anchor", "second-anchor"}},
                    rows,
                )

            exact_rows = copy.deepcopy(rows)
            exact_rows[0]["candidates"][0]["anchor_ids"] = ["anchor"]
            exact_path = Path(temporary) / "exact-quality.json"
            exact_path.write_text(json.dumps({"queries": exact_rows}), encoding="utf-8")
            exact = copy.deepcopy(base)
            exact["source"] = str(exact_path)
            exact["source_sha256"] = hashlib.sha256(exact_path.read_bytes()).hexdigest()
            with self.assertRaisesRegex(RuntimeError, "targets an exact hit"):
                MODULE._apply_adjudication(exact, required, exact_rows)

    def test_provenance_records_content_hash(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "source.txt"
            path.write_text("evidence", encoding="utf-8")
            provenance = MODULE._file_provenance(path)

        self.assertEqual(hashlib.sha256(b"evidence").hexdigest(), provenance["sha256"])


if __name__ == "__main__":
    unittest.main()
