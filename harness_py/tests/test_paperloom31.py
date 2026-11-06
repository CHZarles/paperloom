import hashlib
import io
import json
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import MagicMock, patch

import yaml

from harness_py.cli import main
from harness_py.evaluation.paperloom_assessment import (
    _assess_agent_case,
    _assess_protocol_trace,
    _controlled_protocol_metrics,
    _retrieval_metrics,
    _source_quotes_cover,
    _target_funnel_metrics,
)
from harness_py.evaluation.paperloom_benchmark import _run_l2, _run_l3, run_benchmark
from harness_py.evaluation.paperloom_common import PreparationError
from harness_py.evaluation.paperloom_prepare import (
    build_agent_cases,
    build_targets,
    ensure_benchmark_config,
    scan_papers,
)
from harness_py.evaluation.paperloom_snapshot import (
    _load_source_aligned_queries,
    _looks_like_bibliography,
    validate_snapshot,
)


class Paperloom31PreparationTest(unittest.TestCase):
    def test_source_aligned_query_contract(self) -> None:
        snapshot = {"dataset_id": "paperloom-31-v1", "targets": {"target_1": {"query": "问题"}}}
        query_hash = hashlib.sha256("问题".encode("utf-8")).hexdigest()
        valid = {
            "schema_version": "paperloom-source-aligned-queries/v1",
            "dataset_id": "paperloom-31-v1",
            "targets": {"target_1": {
                "original_query_sha256": query_hash,
                "source_aligned_query": "Question",
            }},
        }
        invalid = [
            ("schema_version", {**valid, "schema_version": "unknown"}, snapshot),
            ("dataset_id", {**valid, "dataset_id": "other"}, snapshot),
            ("targets", {**valid, "targets": {"target_2": {}}}, snapshot),
            ("original_query_sha256", {**valid, "targets": {"target_1": {
                **valid["targets"]["target_1"], "original_query_sha256": "stale",
            }}}, snapshot),
            ("source_aligned_query", {**valid, "targets": {"target_1": {
                **valid["targets"]["target_1"], "source_aligned_query": " padded ",
            }}}, snapshot),
            ("source_aligned_query", {**valid, "targets": {"target_1": {
                **valid["targets"]["target_1"], "source_aligned_query": "",
            }}}, snapshot),
        ]

        with TemporaryDirectory() as directory:
            path = Path(directory) / "queries.yaml"
            path.write_text(yaml.safe_dump(valid, allow_unicode=True), encoding="utf-8")
            self.assertEqual({"target_1": "Question"}, _load_source_aligned_queries(path, snapshot))
            for expected, value, source_snapshot in invalid:
                path.write_text(yaml.safe_dump(value, allow_unicode=True), encoding="utf-8")
                with self.subTest(expected=expected), self.assertRaisesRegex(PreparationError, expected):
                    _load_source_aligned_queries(path, source_snapshot)

            malformed = Path(directory) / "malformed.yaml"
            malformed.write_text("targets: [\n", encoding="utf-8")
            for source in (Path(directory) / "missing.yaml", malformed):
                with self.subTest(source=source.name), self.assertRaises(PreparationError):
                    _load_source_aligned_queries(source, snapshot)

    def test_l2_query_sets_share_one_execution_path(self) -> None:
        source_span = '{"locationType":"PASSAGE","sourceObjectId":"p1","spans":[]}'
        content = "canonical content"
        snapshot = {
            "targets": {
                "target_1": {
                    "product_paper_id": "paper_1",
                    "query": "原始问题",
                    "location_ref": "location_1",
                    "location_type": "PASSAGE",
                    "page": 1,
                    "content_hash": hashlib.sha256(content.encode()).hexdigest(),
                    "source_span_hash": hashlib.sha256(source_span.encode()).hexdigest(),
                }
            }
        }
        calls: dict[str, object] = {"fail": False}

        class Reader:
            def load_metadata_dataset(self):
                return object()

        class Gateway:
            def post(self, path, body):
                calls.setdefault("exports", []).append((path, body))
                return {"candidates": [{"location_ref": "location_1", "source_span": source_span}]}

            def reader(self, **arguments):
                calls["reader"] = arguments
                return Reader()

        class Tools:
            def __init__(self, dataset, reader):
                pass

            def find_papers_by_identity(self, arguments):
                return {"status": "resolved"}

            def search_paper_content(self, arguments):
                if calls["fail"]:
                    raise TimeoutError("retrieval timed out")
                calls["search"] = arguments
                return {"locations": [{"location_ref": "location_1"}]}

            def read_paper_content(self, arguments):
                return {"items": [{
                    "location_ref": "location_1",
                    "paper_id": "paper_1",
                    "page": 1,
                    "span_text": content,
                    "source_quotes": [{
                        "location_ref": "location_1",
                        "source_quote_ref": "quote_1",
                        "source_span_json": source_span,
                    }],
                }]}

        with patch("harness_py.evaluation.paperloom_benchmark.ReadingCorpusTools", Tools):
            result = _run_l2(
                snapshot,
                Gateway(),
                7,
                query_by_target={"target_1": "Original question"},
                stage_name="L2_SOURCE_ALIGNED",
                request_label="l2-source-aligned",
            )

        self.assertEqual(
            {
                "paper_ids": ["paper_1"],
                "query_text": "Original question",
                "element_types": ["passage"],
                "top_k": 10,
            },
            calls["search"],
        )
        self.assertEqual(["paper_1"], calls["reader"]["scope_paper_ids"])
        self.assertEqual("paperloom31-l2-source-aligned-target_1", calls["reader"]["request_id"])
        self.assertEqual([], result["technical_errors"])
        self.assertEqual(1, result["exact_read_passed"])
        self.assertEqual([
            ("/internal/v1/corpus/locations/export", {"paper_id": "paper_1"})
        ], calls["exports"])

        calls.clear()
        calls["fail"] = False
        with patch("harness_py.evaluation.paperloom_benchmark.ReadingCorpusTools", Tools):
            _run_l2(snapshot, Gateway(), 7)

        self.assertEqual("原始问题", calls["search"]["query_text"])
        self.assertEqual("paperloom31-l2-target_1", calls["reader"]["request_id"])

        calls["fail"] = True
        with patch("harness_py.evaluation.paperloom_benchmark.ReadingCorpusTools", Tools):
            failed = _run_l2(
                snapshot,
                Gateway(),
                7,
                query_by_target={"target_1": "Original question"},
                stage_name="L2_SOURCE_ALIGNED",
                request_label="l2-source-aligned",
            )
        self.assertEqual("L2_SOURCE_ALIGNED", failed["technical_errors"][0]["stage"])

    def test_paperloom31_cli_contract(self) -> None:
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as raised:
            main(["paperloom31", "run", "--snapshot", "snapshot.json"])
        self.assertEqual(2, raised.exception.code)

        for errors, expected_code in (([], 0), ([{"stage": "L2_SOURCE_ALIGNED"}], 2)):
            report = {
                "l1": {"metrics": {}},
                "l2": {"metrics": {}},
                "l2_source_aligned": {
                    "metrics": {"recall_at_5": 0.8},
                    "technical_errors": errors,
                },
                "l3": {"hard_passed": 1},
            }
            output = io.StringIO()
            with self.subTest(errors=bool(errors)), \
                    patch("harness_py.cli._provider", return_value=object()), \
                    patch("harness_py.cli.run_benchmark", return_value=(Path("saved-run"), report)) as run, \
                    redirect_stdout(output):
                code = main([
                    "paperloom31", "run", "--snapshot", "snapshot.json",
                    "--source-aligned-queries", "queries.yaml",
                    "--benchmark-user-id", "7",
                    "--baseline-run", "baseline/run.json",
                ])
            summary = json.loads(output.getvalue())
            self.assertEqual(expected_code, code)
            self.assertEqual("queries.yaml", run.call_args.kwargs["source_aligned_queries_path"])
            self.assertEqual(7, run.call_args.kwargs["benchmark_user_id"])
            self.assertEqual("baseline/run.json", run.call_args.kwargs["baseline_run_path"])
            self.assertEqual({"recall_at_5": 0.8}, summary["l2_source_aligned_metrics"])
            self.assertEqual("saved-run", summary["out"])

    def test_benchmark_runs_source_aligned_l2_after_l3(self) -> None:
        order: list[str] = []
        empty_stage = {"executed": True, "passed": True, "failures": [], "technical_errors": []}

        def run_l2(snapshot, gateway, user_id, **arguments):
            order.append("source" if arguments.get("query_by_target") is not None else "raw")
            return {"executed": True, "targets": [], "technical_errors": []}

        with TemporaryDirectory() as directory:
            root = Path(directory)
            config_path = root / "benchmark.yaml"
            config_path.write_text("config", encoding="utf-8")
            snapshot_path = root / "snapshot.json"
            snapshot_path.write_text(json.dumps({
                "dataset_id": "paperloom-31-v1",
                "config_sha256": hashlib.sha256(b"config").hexdigest(),
                "papers": {},
                "targets": {"target_1": {"query": "问题"}},
            }), encoding="utf-8")
            query_path = root / "queries.yaml"
            query_path.write_text(yaml.safe_dump({
                "schema_version": "paperloom-source-aligned-queries/v1",
                "dataset_id": "paperloom-31-v1",
                "targets": {"target_1": {
                    "original_query_sha256": hashlib.sha256("问题".encode("utf-8")).hexdigest(),
                    "source_aligned_query": "Question",
                }},
            }, allow_unicode=True), encoding="utf-8")
            query_file_hash = hashlib.sha256(query_path.read_bytes()).hexdigest()
            baseline_path = root / "baseline.json"
            baseline_path.write_text(json.dumps({
                "snapshot_sha256": "snapshot",
                "config_sha256": hashlib.sha256(b"config").hexdigest(),
                "provider": {},
                "benchmark_user": {
                    "ordinary_user_id": 3,
                    "starting_token_balances": {"llm": 999, "embedding": 2000},
                },
                "l2_source_aligned": {"query_set": {"file_sha256": query_file_hash}},
            }), encoding="utf-8")
            api = MagicMock()
            api.__enter__.return_value = api
            api.me.return_value = {"id": 1}
            api.users.return_value = [
                {"id": 2, "username": "wrong", "role": "USER"},
                {"id": 3, "username": "benchmark", "role": "USER"},
            ]
            api.user_usage.return_value = {"llm": 1000, "embedding": 2000}
            api.papers.return_value = []
            gateway = MagicMock()
            gateway.reader.return_value.load_metadata_dataset.return_value = object()
            provider = MagicMock()
            provider.public_diagnostics.return_value = {}

            def run_l3(*arguments, **keywords):
                order.append("l3")
                query_path.unlink()
                return {"executed": True, "cases": [], "technical_errors": []}

            with patch("harness_py.evaluation.paperloom_benchmark.validate_snapshot"), \
                    patch("harness_py.evaluation.paperloom_benchmark._canonical_sha256", return_value="snapshot"), \
                    patch("harness_py.evaluation.paperloom_benchmark._credentials", return_value=("admin", "password")), \
                    patch("harness_py.evaluation.paperloom_benchmark._ProductApi", return_value=api), \
                    patch("harness_py.evaluation.paperloom_benchmark.JavaCorpusGateway", return_value=gateway), \
                    patch("harness_py.evaluation.paperloom_benchmark._run_l0", return_value=empty_stage), \
                    patch("harness_py.evaluation.paperloom_benchmark._run_g0", return_value=empty_stage), \
                    patch("harness_py.evaluation.paperloom_benchmark._run_l1", return_value=empty_stage), \
                    patch("harness_py.evaluation.paperloom_benchmark._run_l2", side_effect=run_l2), \
                    patch("harness_py.evaluation.paperloom_benchmark.build_harness_runtime"), \
                    patch("harness_py.evaluation.paperloom_benchmark.LiveResearchChatHarness"), \
                    patch("harness_py.evaluation.paperloom_benchmark.MiniMaxJudgeModel"), \
                    patch("harness_py.evaluation.paperloom_benchmark._run_l3", side_effect=run_l3):
                with self.assertRaisesRegex(PreparationError, "BASELINE_IDENTITY_MISMATCH"):
                    run_benchmark(
                        snapshot_path,
                        source_aligned_queries_path=query_path,
                        config_path=config_path,
                        runs_dir=root / "runs",
                        provider=provider,
                        api_base_url="http://example.test",
                        benchmark_user_id=3,
                        baseline_run_path=baseline_path,
                    )
                self.assertEqual([], order)
                _, report = run_benchmark(
                    snapshot_path,
                    source_aligned_queries_path=query_path,
                    config_path=config_path,
                    runs_dir=root / "runs",
                    provider=provider,
                    api_base_url="http://example.test",
                    benchmark_user_id=3,
                )

        self.assertEqual(["raw", "l3", "source"], order)
        self.assertEqual("paperloom-benchmark-run/v3", report["schema_version"])
        self.assertEqual(query_file_hash, report["l2_source_aligned"]["query_set"]["file_sha256"])
        self.assertEqual({
            "admin_user_id": 1,
            "ordinary_user_id": 3,
            "ordinary_username": "benchmark",
            "starting_token_balances": {"llm": 1000, "embedding": 2000},
        }, report["benchmark_user"])

    def test_checked_in_source_aligned_queries_contain_31_targets(self) -> None:
        root = Path(__file__).resolve().parents[2]
        query_set = yaml.safe_load(
            (root / "research/benchmark/paperloom-31-v1/source-aligned-queries-v1.yaml")
            .read_text(encoding="utf-8")
        )
        self.assertEqual(31, len(query_set["targets"]))

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
            self.assertEqual("paperloom-query-generator-v3", first["generation"]["prompt_version"])

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
            paper_key = "paper_01_attention_is_all_you_need" if index == 0 else f"paper_{index:02d}"
            product_id = f"product_{index:02d}"
            title = f"Paper {index:02d}"
            content = f"trusted evidence {index:02d}"
            target = {
                "target_id": f"target_{index:02d}",
                "paper": paper_key,
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
            papers[paper_key] = {
                "product_paper_id": product_id,
                "metadata_query": title,
            }

        cases = build_agent_cases(targets, product_states)
        snapshot = {
            "schema_version": "paperloom-product-snapshot/v2",
            "generator": {"case_layout_version": "paperloom-agent-case-layout-v5"},
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
        by_id = {case["case_id"]: case for case in cases}
        follow_up = by_id["follow_up_01"]
        self.assertEqual("请为刚才的结论提供对应论文中的原文证据，并保留引用。", follow_up["question"])
        self.assertEqual("请依据《Paper 12》回答：Question 12?", follow_up["history"][0]["content"])
        self.assertEqual("RESEARCH", follow_up["expected_contract"])
        ambiguous = by_id["follow_up_ambiguous_01"]
        self.assertEqual("该论文发表于哪个会议？", ambiguous["history"][0]["content"])
        self.assertEqual("DIRECT", ambiguous["expected_contract"])
        self.assertEqual("needs_clarification", ambiguous["expected_outcome"])
        self.assertEqual(17, len(cases))
        self.assertEqual("DIRECT", by_id["direct_greeting_01"]["expected_contract"])
        self.assertEqual("CATALOG", by_id["catalog_inventory_01"]["expected_contract"])
        recommendation = by_id["research_llm_principles_01"]
        self.assertEqual("RESEARCH", recommendation["expected_contract"])
        self.assertEqual("research_recommendation", recommendation["case_type"])
        self.assertEqual([], recommendation["required_target_ids"])
        self.assertEqual([], recommendation["answer_spans"])
        self.assertEqual("cite_recommendation_reasons", recommendation["citation_policy"])

    def test_agent_gate_treats_exact_target_coverage_as_diagnostic(self) -> None:
        case = {
            "case_id": "single_01",
            "expected_contract": "RESEARCH",
            "expected_outcome": "answered",
            "required_target_ids": ["target_1"],
        }
        targets = {"target_1": {"target_id": "target_1", "location_ref": "exact_location"}}
        run = {
            "status": "COMPLETED",
            "react_trace": [{
                "tool_name": "search_paper_content",
                "result": {"locations": [{"location_ref": "alternate_section"}]},
            }, {
                "tool_name": "submit_research_answer",
                "arguments": {
                    "outcome": "answered",
                    "language": "EN",
                    "markdown": "Supported answer. [[source_quote_1]]",
                },
                "result": {"accepted": True},
            }],
            "evidence_ledger": {"items": [{
                "paper_id": "paper_1",
                "location_ref": "alternate_section",
                "source_quote_ref": "source_quote_1",
            }]},
            "research_answer": {
                "outcome": "answered",
                "answer_contract": "RESEARCH",
                "cited_source_quote_refs": ["source_quote_1"],
            },
            "citation_validation": {"passed": True},
        }

        assessment = _assess_agent_case(case, run, targets, {"paper_1"})

        self.assertEqual([], assessment["hard_failures"])
        self.assertTrue(assessment["provenance_passed"])
        self.assertEqual(
            {"target_id": "target_1", "returned": False, "read": False, "cited": False},
            assessment["target_checks"][0],
        )

        run["research_answer"] = {
            "outcome": "answered",
            "answer_contract": "RESEARCH",
            "cited_source_quote_refs": [],
        }
        missing = _assess_agent_case(case, run, targets, {"paper_1"})
        self.assertEqual("MISSING_CITATION", missing["hard_failures"][0]["code"])

        case.update({
            "case_type": "research_recommendation",
            "required_target_ids": [],
            "citation_policy": "cite_recommendation_reasons",
        })
        run["research_answer"]["cited_source_quote_refs"] = ["source_quote_1"]
        recommendation = _assess_agent_case(case, run, targets, {"paper_1"})
        self.assertEqual([], recommendation["hard_failures"])

    def test_agent_gate_reports_provider_failure_without_funnel_failures(self) -> None:
        case = {
            "case_id": "comparison_01",
            "expected_contract": "RESEARCH",
            "expected_outcome": "answered",
            "required_target_ids": ["target_1"],
        }
        run = {
            "status": "FAILED_TECHNICAL",
            "control": {"reason_code": "PROVIDER_UNAVAILABLE"},
            "react_trace": [],
            "evidence_ledger": {"items": []},
            "research_answer": {},
        }

        assessment = _assess_agent_case(
            case, run, {"target_1": {"target_id": "target_1", "location_ref": "location_1"}},
            {"paper_1"}, {"passed": False, "failed_event_ids": []},
        )

        self.assertEqual([{
            "stage": "L3",
            "code": "AGENT_TECHNICAL_FAILURE",
            "detail": "PROVIDER_UNAVAILABLE",
            "technical": True,
        }], assessment["hard_failures"])
        self.assertEqual(
            {"target_id": "target_1", "returned": False, "read": False, "cited": False},
            assessment["target_checks"][0],
        )

    def test_agent_stage_skips_judge_after_provider_failure(self) -> None:
        dataset = MagicMock()
        dataset.paper_records_by_id = {"paper_1": {}}
        gateway = MagicMock()
        harness = MagicMock()
        harness.run_turn.return_value = ({
            "run_id": "run_provider_error",
            "status": "FAILED_TECHNICAL",
            "result_status": "FAILED_TECHNICAL",
            "control": {"reason_code": "PROVIDER_UNAVAILABLE"},
            "react_trace": [],
            "evidence_ledger": {"items": []},
            "research_answer": {},
        }, None)
        judge = MagicMock()
        snapshot = {"agent_cases": [{
            "case_id": "comparison_01",
            "expected_contract": "RESEARCH",
            "expected_outcome": "answered",
            "question": "Compare two papers",
            "required_target_ids": [],
        }]}

        with TemporaryDirectory() as directory:
            out = Path(directory)
            (out / "agent").mkdir()
            (out / "judge").mkdir()
            with patch("harness_py.evaluation.paperloom_benchmark._assess_protocol_trace", return_value={"passed": False}), \
                    patch("harness_py.evaluation.paperloom_benchmark._judge_agent_case") as judge_case:
                stage = _run_l3(snapshot, dataset, gateway, 1, harness, judge, out)

        judge_case.assert_not_called()
        self.assertEqual("not_applicable", stage["cases"][0]["judge"]["status"])
        self.assertEqual("PROVIDER_UNAVAILABLE", stage["technical_errors"][0]["detail"])
        self.assertEqual(0, stage["target_funnel"]["eligible_targets"])

    def test_target_funnel_excludes_provider_failures(self) -> None:
        rows = [
            {"status": "COMPLETED", "target_checks": [
                {"returned": True, "read": True, "cited": True},
                {"returned": True, "read": False, "cited": False},
            ]},
            {"status": "FAILED_TECHNICAL", "target_checks": [
                {"returned": False, "read": False, "cited": False},
            ]},
        ]

        self.assertEqual({
            "eligible_cases": 1,
            "excluded_cases": 1,
            "eligible_targets": 2,
            "excluded_targets": 1,
            "returned": 2,
            "read": 1,
            "cited": 1,
        }, _target_funnel_metrics(rows))

    def test_protocol_trace_replays_and_aggregates_without_a_judge(self) -> None:
        event = {
            "event_id": "event_1",
            "kind": "protocol.transition",
            "payload": {
                "before": {
                    "phase": "ACTIVE",
                    "contract": None,
                    "submission_attempt": 0,
                    "validation_issues": [],
                },
                "event": {
                    "kind": "SUBMISSION_REQUESTED",
                    "contract": "DIRECT",
                    "accepted": True,
                    "issue_class": None,
                    "issue_codes": [],
                    "issues": [],
                },
                "facts": {
                    "known_source_quote_refs": [],
                    "catalog_result_refs": [],
                    "sibling_tool_names": ["submit_direct_answer"],
                },
                "decision": {"accepted": True, "issue_codes": []},
                "after": {
                    "phase": "COMPLETE",
                    "contract": "DIRECT",
                    "submission_attempt": 1,
                    "validation_issues": [],
                },
            },
        }
        with TemporaryDirectory() as directory:
            path = Path(directory) / "events.jsonl"
            path.write_text(json.dumps(event) + "\n", encoding="utf-8")
            replay = _assess_protocol_trace(path)

        metrics = _controlled_protocol_metrics([{
            "expected_contract": "CATALOG",
            "contract_match": True,
            "protocol_replay": replay,
            "provenance_applicable": False,
        }])
        self.assertTrue(replay["passed"])
        self.assertEqual(1.0, metrics["contract_accuracy"])
        self.assertEqual(1.0, metrics["protocol_replay_pass_rate"])


if __name__ == "__main__":
    unittest.main()
