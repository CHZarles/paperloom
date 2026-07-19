from __future__ import annotations

import importlib.util
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

import yaml


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "research/golden-data/qdrant_impact_adjudication.py"
SPEC = importlib.util.spec_from_file_location("qdrant_impact_adjudication", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class QdrantImpactAdjudicationTest(unittest.TestCase):
    def test_recomputes_exact_and_adjudicated_totals(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            paths = self._write_fixture(Path(directory))

            result = MODULE.build_adjudication_report(
                paths["adjudication"],
                repo_root=directory,
            )

        method = result["methods"]["retriever"]
        self.assertEqual(1, method["exact"]["anchor_hits"])
        self.assertEqual(0, method["exact"]["full_cases"])
        self.assertEqual(2, method["adjudicated"]["anchor_hits"])
        self.assertEqual(1, method["adjudicated"]["full_cases"])
        self.assertEqual(1, method["adjudicated"]["equivalent_additions"])
        self.assertEqual(1, method["adjudicated"]["insufficient"])
        self.assertEqual(0, method["review"]["unreviewed_miss_count"])
        self.assertTrue(
            all(item["source_binding_verified"] for item in method["decisions"])
        )
        self.assertTrue(
            all(
                item["semantic_equivalence_automatically_proven"] is False
                for item in method["decisions"]
            )
        )
        self.assertEqual("human", result["validation_scope"]["semantic_decision_authority"])
        self.assertFalse(
            result["validation_scope"]["semantic_equivalence_automatically_proven"]
        )

    def test_rejects_a_decision_that_is_not_an_obligation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            paths = self._write_fixture(Path(directory))
            adjudication = yaml.safe_load(paths["adjudication"].read_text())
            adjudication["methods"]["retriever"]["decisions"][0][
                "anchor_id"
            ] = "not-required"
            paths["adjudication"].write_text(yaml.safe_dump(adjudication))

            with self.assertRaisesRegex(
                MODULE.AdjudicationError,
                "not a required case/anchor obligation",
            ):
                MODULE.build_adjudication_report(
                    paths["adjudication"],
                    repo_root=directory,
                )

    def test_rejects_a_location_that_is_not_at_the_stated_rank(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            paths = self._write_fixture(Path(directory))
            adjudication = yaml.safe_load(paths["adjudication"].read_text())
            adjudication["methods"]["retriever"]["decisions"][0]["rank"] = 1
            paths["adjudication"].write_text(yaml.safe_dump(adjudication))

            with self.assertRaisesRegex(
                MODULE.AdjudicationError,
                "exactly at rank 1",
            ):
                MODULE.build_adjudication_report(
                    paths["adjudication"],
                    repo_root=directory,
                )

    def test_rejects_incomplete_review_of_exact_misses(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            paths = self._write_fixture(Path(directory))
            adjudication = yaml.safe_load(paths["adjudication"].read_text())
            adjudication["methods"]["retriever"]["decisions"].pop()
            paths["adjudication"].write_text(yaml.safe_dump(adjudication))

            with self.assertRaisesRegex(
                MODULE.AdjudicationError,
                "incomplete human review of exact misses",
            ):
                MODULE.build_adjudication_report(
                    paths["adjudication"],
                    repo_root=directory,
                )

    def test_rejects_changed_source_artifact_after_manual_review(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            paths = self._write_fixture(Path(directory))
            paths["queries"].write_text(
                paths["queries"].read_text() + "\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                MODULE.AdjudicationError,
                "source queries sha256 mismatch",
            ):
                MODULE.build_adjudication_report(
                    paths["adjudication"],
                    repo_root=directory,
                )

    def test_rejects_changed_dataset_content_after_manual_review(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            paths = self._write_fixture(Path(directory))
            paths["reading_model"].write_text(
                '{"changed":true}\n',
                encoding="utf-8",
            )

            with self.assertRaisesRegex(
                MODULE.AdjudicationError,
                "frozen dataset content fingerprint mismatch",
            ):
                MODULE.build_adjudication_report(
                    paths["adjudication"],
                    repo_root=directory,
                )

    def _write_fixture(self, root: Path) -> dict[str, Path]:
        manifest_path = root / "manifest.yaml"
        pack_path = root / "pack.yaml"
        case_path = root / "cases.yaml"
        reading_model_path = (
            root
            / "fixture/reading-models/paper-a.reading-model.json"
        )
        reading_model_path.parent.mkdir(parents=True)
        manifest_path.write_text(yaml.safe_dump({
            "paper_packs": ["pack.yaml"],
            "case_files": ["cases.yaml"],
        }))
        pack_path.write_text(yaml.safe_dump({
            "data_dir": "fixture",
            "papers": [{"id": "paper-a"}],
        }))
        case_path.write_text(yaml.safe_dump({"cases": []}))
        reading_model_path.write_text('{"model":"fixture"}\n', encoding="utf-8")

        query_path = root / "queries.jsonl"
        rows = [
            self._query(
                "case-a",
                "query-a",
                ["anchor-a1", "anchor-a2"],
                ["exact-a1", "equivalent-a2"],
                [["anchor-a1"], []],
            ),
            self._query(
                "case-b",
                "query-b",
                ["anchor-b1"],
                ["insufficient-b1"],
                [[]],
            ),
        ]
        query_path.write_text(
            "".join(json.dumps(row) + "\n" for row in rows),
            encoding="utf-8",
        )

        dataset_content = MODULE.dataset_content_provenance(
            manifest_path,
            repo_root=root,
        )
        report_path = root / "report.json"
        report_path.write_text(json.dumps({
            "schema_version": "harness-qdrant-impact-report/v1",
            "status": "complete",
            "raw_query_artifact": str(query_path),
            "provenance": {"dataset_content": dataset_content},
            "preflight": {
                "evidence_case_count": 2,
                "required_anchor_obligation_count": 3,
            },
            "metrics": {
                "modes": {
                    "retriever": {
                        "headline_case_union_exact_anchor": {
                            "eligible_case_count": 2,
                            "full_case_count": 0,
                            "hit_count": 1,
                            "required_count": 3,
                        },
                        "full_case_coverage": {
                            "anchor_hit_count": 1,
                            "anchor_required_count": 3,
                            "eligible_case_count": 2,
                            "full_case_count": 0,
                        },
                    }
                }
            },
        }), encoding="utf-8")

        adjudication_path = root / "adjudication.yaml"
        adjudication_path.write_text(yaml.safe_dump({
            "schema_version": "harness-qdrant-impact-adjudication/v1",
            "source_report": str(report_path),
            "source_queries": str(query_path),
            "review_basis": "synthetic evidence",
            "review_type": "test",
            "review_authority": "human",
            "frozen_sources": {
                "source_report_sha256": hashlib.sha256(
                    report_path.read_bytes()
                ).hexdigest(),
                "source_queries_sha256": hashlib.sha256(
                    query_path.read_bytes()
                ).hexdigest(),
                "dataset_manifest": str(manifest_path),
                "dataset_content_sha256": dataset_content["sha256"],
            },
            "methods": {
                "retriever": {
                    "exact": {
                        "anchor_hits": 1,
                        "anchor_required": 3,
                        "full_cases": 0,
                        "eligible_cases": 2,
                    },
                    "adjudicated": {
                        "equivalent_additions": 1,
                        "insufficient": 1,
                        "anchor_hits": 2,
                        "anchor_required": 3,
                        "full_cases": 1,
                        "eligible_cases": 2,
                    },
                    "decisions": [
                        {
                            "case_id": "case-a",
                            "anchor_id": "anchor-a2",
                            "status": "equivalent",
                            "query_id": "query-a",
                            "rank": 2,
                            "location_ref": "equivalent-a2",
                            "reason": "Equivalent synthetic span.",
                        },
                        {
                            "case_id": "case-b",
                            "anchor_id": "anchor-b1",
                            "status": "insufficient",
                            "query_id": "query-b",
                            "rank": 1,
                            "location_ref": "insufficient-b1",
                            "reason": "Insufficient synthetic span.",
                        },
                    ],
                }
            },
        }), encoding="utf-8")
        return {
            "adjudication": adjudication_path,
            "queries": query_path,
            "reading_model": reading_model_path,
            "report": report_path,
        }

    @staticmethod
    def _query(
        case_id: str,
        query_id: str,
        required_anchor_ids: list[str],
        location_refs: list[str],
        matched_anchor_ids_by_rank: list[list[str]],
    ) -> dict[str, object]:
        return {
            "case_id": case_id,
            "query_id": query_id,
            "case_required_anchor_ids": required_anchor_ids,
            "relevant_anchor_ids": required_anchor_ids,
            "modes": {
                "retriever": {
                    "native": {
                        "location_refs": location_refs,
                        "matched_anchor_ids_by_rank": matched_anchor_ids_by_rank,
                        "hit_anchor_ids": sorted({
                            anchor_id
                            for anchor_ids in matched_anchor_ids_by_rank
                            for anchor_id in anchor_ids
                        }),
                    }
                }
            },
        }


if __name__ == "__main__":
    unittest.main()
