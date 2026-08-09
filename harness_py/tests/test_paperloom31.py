from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
import hashlib

from harness_py.evaluation.paperloom31 import (
    build_agent_cases,
    build_targets,
    ensure_benchmark_config,
    scan_papers,
    validate_snapshot,
    _assess_agent_case,
    _looks_like_bibliography,
    _retrieval_metrics,
    _source_quotes_cover,
)


class Paperloom31PreparationTest(unittest.TestCase):
    def test_retrieval_metrics_use_one_based_rank(self) -> None:
        self.assertEqual(
            {"count": 3, "recall_at_1": 1 / 3, "recall_at_3": 2 / 3, "mrr": 4 / 9},
            _retrieval_metrics([{"rank": 1}, {"rank": 3}, {"rank": None}], (1, 3)),
        )

    def test_multiple_page_quotes_cover_one_location_span(self) -> None:
        expected = '{"locationType":"PASSAGE","sourceObjectId":"p1","spans":[{"id":1},{"id":2}]}'
        quotes = [
            {"source_span_json": '{"locationType":"PASSAGE","sourceObjectId":"p1","spans":[{"id":1}]}'},
            {"source_span_json": '{"locationType":"PASSAGE","sourceObjectId":"p1","spans":[{"id":2}]}'},
        ]
        self.assertTrue(_source_quotes_cover(quotes, expected))

    def test_scan_and_config_are_stable(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            papers_dir = root / "papers"
            papers_dir.mkdir()
            for index in range(1, 32):
                (papers_dir / f"{index:02d}-paper.pdf").write_bytes(
                    b"%PDF-1.4\n" + str(index).encode("ascii")
                )

            papers = scan_papers(papers_dir, repo_root=root)
            config_path = root / "benchmark.yaml"
            first = ensure_benchmark_config(
                config_path,
                papers,
                generator_provider="minimax",
                generator_model="MiniMax-M2.5",
            )
            second = ensure_benchmark_config(
                config_path,
                papers,
                generator_provider="ignored-on-rerun",
                generator_model="ignored-on-rerun",
            )

            self.assertEqual(first, second)
            self.assertEqual(31, len(first["papers"]))

    def test_target_selector_enforces_one_paper_and_fixed_type_counts(self) -> None:
        self.assertTrue(_looks_like_bibliography(
            "A et al. 2020 arXiv. B et al. 2021 arXiv. C et al. 2022 proceedings of X. D et al. 2023 conference on Y."
        ))
        candidates = []
        for paper_index in range(31):
            for location_type in ("PASSAGE", "TABLE", "FIGURE"):
                content = f"paper {paper_index} {location_type}"
                candidates.append({
                    "paper_key": f"paper_{paper_index:02d}",
                    "product_paper_id": f"product_{paper_index:02d}",
                    "model_version": "rm-1",
                    "location_ref": f"{location_type.lower()}_{paper_index:02d}",
                    "location_type": location_type,
                    "page": 1,
                    "content": content,
                    "content_hash": hashlib.sha256(content.encode()).hexdigest(),
                    "source_span_hash": hashlib.sha256(b"{}").hexdigest(),
                })

        targets = build_targets(candidates, {
            "per_paper": 1,
            "passage_count": 22,
            "table_count": 6,
            "figure_count": 3,
        })

        self.assertEqual(31, len(targets))
        self.assertEqual(31, len({target["paper"] for target in targets}))
        self.assertEqual(
            {"PASSAGE": 22, "TABLE": 6, "FIGURE": 3},
            {
                location_type: sum(target["location_type"] == location_type for target in targets)
                for location_type in ("PASSAGE", "TABLE", "FIGURE")
            },
        )

    def test_agent_cases_are_deterministic_and_snapshot_is_valid(self) -> None:
        targets = []
        product_states = {}
        papers = {}
        for index in range(31):
            location_type = "PASSAGE" if index < 22 else "TABLE" if index < 28 else "FIGURE"
            product_id = f"product_{index:02d}"
            title = f"Paper {index:02d}"
            content = f"trusted evidence {index:02d}"
            target = {
                "target_id": f"target_{index:02d}",
                "paper": f"paper_{index:02d}",
                "product_paper_id": product_id,
                "location_ref": f"location_{index:02d}",
                "location_type": location_type,
                "content": content,
                "query": f"Question {index:02d}?",
                "expected_answer": f"Answer {index:02d}",
                "answer_spans": [content],
                "fact_keys": [f"fact_{index:02d}"],
            }
            targets.append(target)
            product_states[product_id] = {"benchmark_title": title}
            papers[f"paper_{index:02d}"] = {
                "product_paper_id": product_id,
                "metadata_query": title,
            }

        cases = build_agent_cases(targets, product_states)
        snapshot = {
            "papers": papers,
            "targets": {target["target_id"]: target for target in targets},
            "agent_cases": cases,
        }

        validate_snapshot(snapshot)
        self.assertEqual("请依据《Paper 22》回答：Question 22?", cases[0]["question"])
        self.assertEqual(
            "请分别依据《Paper 04》和《Paper 05》回答：\n"
            "1. Question 04?\n2. Question 05?\n请分项回答并分别引用。",
            cases[6]["question"],
        )
        self.assertEqual("请为刚才的结论提供对应论文中的原文证据，并保留引用。", cases[10]["question"])

    def test_agent_gate_treats_exact_target_coverage_as_diagnostic(self) -> None:
        case = {
            "case_id": "single_01",
            "expected_outcome": "answered",
            "required_target_ids": ["target_1"],
        }
        targets = {"target_1": {"target_id": "target_1", "location_ref": "exact_location"}}
        run = {
            "status": "COMPLETED",
            "react_trace": [{
                "tool_name": "search_paper_content",
                "result": {"locations": [{"location_ref": "alternate_section"}]},
            }],
            "evidence_ledger": {"items": [{
                "paper_id": "paper_1",
                "location_ref": "alternate_section",
                "source_quote_ref": "source_quote_1",
            }]},
            "research_answer": {
                "outcome": "answered",
                "cited_source_quote_refs": ["source_quote_1"],
            },
            "citation_validation": {"passed": True},
        }

        assessment = _assess_agent_case(case, run, targets, {"paper_1"})

        self.assertEqual([], assessment["hard_failures"])
        self.assertEqual(
            {"target_id": "target_1", "returned": False, "read": False, "cited": False},
            assessment["target_checks"][0],
        )

        run["research_answer"] = {"outcome": "answered", "cited_source_quote_refs": []}
        missing = _assess_agent_case(case, run, targets, {"paper_1"})
        self.assertEqual("MISSING_CITATION", missing["hard_failures"][0]["code"])


if __name__ == "__main__":
    unittest.main()
