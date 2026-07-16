from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import replace
from pathlib import Path
from uuid import uuid4

from .corpus.gateway import JavaCorpusGateway
from .evaluation.audit import audit_dataset
from .evaluation.dataset import load_dataset
from .evaluation.golden_fixture import GoldenFixtureHarness
from .evaluation.golden_case import case_question, conversation_state_for_case
from .evaluation.judge import LLMJudge, evaluate_calibration, load_calibration_cases
from .evaluation.judge_model import MiniMaxJudgeModel
from .evaluation.product_runner import (
    load_product_corpus_map,
    product_reader_for_case,
    validate_product_scope,
)
from .evaluation.scoring import BehaviorScorer
from .orchestration.live_chat import LiveResearchChatHarness
from .orchestration.runtime import build_harness_runtime
from .transport.provider_config import DockerMySqlProviderConfigStore, EnvProviderConfigStore
from .transport.service import serve


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run the Python research harness prototype.")
    parser.add_argument("--manifest", default="research/golden-data/manifest.yaml")
    subcommands = parser.add_subparsers(dest="command", required=True)
    subcommands.add_parser("validate", help="Load the dataset and validate deterministic fixture traces.")
    audit_parser = subcommands.add_parser("audit", help="Verify authored anchors against parsed reading models.")
    audit_parser.add_argument(
        "--out",
        default="research/golden-data/local-runs/stable-anchor-audit.json",
    )
    agent_parser = subcommands.add_parser(
        "agent-run",
        help="Run the real tool-using agent through the Java/Qdrant product corpus.",
    )
    agent_parser.add_argument("--out", default="eval/rag/runs/python-minimax-agent")
    agent_parser.add_argument("--case-id", action="append", default=[])
    agent_parser.add_argument(
        "--product-corpus-map",
        default=os.getenv("GOLDEN_PRODUCT_CORPUS_MAP", ""),
        help="Golden-to-product paper mapping required by agent-run.",
    )
    _add_runtime_options(agent_parser)
    serve_parser = subcommands.add_parser("serve", help="Run the internal HTTP research harness service.")
    serve_parser.add_argument("--host", default="127.0.0.1")
    serve_parser.add_argument("--port", type=int, default=8091)
    serve_parser.add_argument("--internal-token", default="")
    serve_parser.add_argument("--max-tokens", type=int, default=3000)
    judge_parser = subcommands.add_parser(
        "judge-calibrate",
        help="Compare one LLM judge with fixed human-labelled harness runs.",
    )
    judge_parser.add_argument("--labels", default="research/golden-data/human-labels.yaml")
    judge_parser.add_argument("--out", default="eval/rag/runs/llm-judge-calibration")
    judge_parser.add_argument("--provider-source", choices=["db", "env"], default="db")
    judge_parser.add_argument("--max-tokens", type=int, default=1200)
    args = parser.parse_args(argv)

    if args.command == "validate":
        dataset = load_dataset(args.manifest)
        harness = GoldenFixtureHarness()
        runs = [harness.run_case(dataset, case) for case in dataset.cases]
        report = BehaviorScorer().score_dataset(dataset, runs)
        print(json.dumps(report, indent=2, sort_keys=True))
        return 0 if report["failed_count"] == 0 else 1
    if args.command == "audit":
        report = audit_dataset(load_dataset(args.manifest))
        target = Path(args.out)
        target.parent.mkdir(parents=True, exist_ok=True)
        _write_json(target, report)
        print(json.dumps(report, indent=2, sort_keys=True))
        return 0 if report["failed_count"] == 0 else 1
    if args.command == "agent-run":
        dataset = load_dataset(args.manifest)
        selected = set(args.case_id)
        cases_by_id = {str(case.get("id")): case for case in dataset.cases}
        unknown_case_ids = sorted(selected - set(cases_by_id))
        if unknown_case_ids:
            print(json.dumps({
                "error": "unknown_case_id",
                "case_ids": unknown_case_ids,
            }, indent=2, sort_keys=True), file=sys.stderr)
            return 2
        cases = [case for case in dataset.cases if not selected or case.get("id") in selected]
        try:
            corpus_map = load_product_corpus_map(args.product_corpus_map, dataset)
            validate_product_scope(dataset, cases, corpus_map)
            corpus_gateway = JavaCorpusGateway()
        except Exception as error:
            print(json.dumps({
                "error": "golden_product_corpus_setup_failed",
                "error_type": type(error).__name__,
                "message": str(error),
            }, indent=2, sort_keys=True), file=sys.stderr)
            return 2
        provider, harness = _live_harness(
            args.provider_source,
            args.max_tokens,
            args.eval_dump,
        )
        runs = []
        for case in cases:
            state = conversation_state_for_case(dataset, case)
            reader = product_reader_for_case(
                corpus_gateway,
                dataset,
                case,
                corpus_map,
                request_id=f"golden-{case['id']}-{uuid4().hex}",
                conversation_id=state.conversation_id,
            )
            run, _ = harness.run_turn(
                dataset,
                state,
                case_question(case),
                case_id_override=str(case["id"]),
                corpus_reader=reader,
            )
            run.setdefault("diagnostics", {})["corpus_backend"] = "java-qdrant"
            runs.append(run)
        report = BehaviorScorer().score_dataset(
            dataset if not selected else _dataset_with_cases(dataset, cases),
            runs,
        )
        out = _write_runs(args.out, runs, report)
        print(json.dumps({
            "out": str(out),
            "provider": provider.public_diagnostics(),
            "runtime": "agents_sdk",
            "corpus_backend": "java-qdrant",
            "eval_capture_failed_count": harness.eval_capture_failures,
            "score_report": report,
        }, indent=2, sort_keys=True))
        if args.eval_dump and harness.eval_capture_failures:
            return 2
        return 0 if report["failed_count"] == 0 else 1
    if args.command == "serve":
        serve(
            host=args.host,
            port=args.port,
            internal_token=args.internal_token,
            max_completion_tokens=args.max_tokens,
        )
        return 0
    if args.command == "judge-calibrate":
        try:
            calibration = load_calibration_cases(args.labels)
            provider, model = _judge_model(args.provider_source)
        except Exception as error:
            print(json.dumps({
                "error": "judge_calibration_setup_failed",
                "error_type": type(error).__name__,
                "message": str(error),
            }, indent=2, sort_keys=True), file=sys.stderr)
            return 2
        report = evaluate_calibration(
            calibration,
            LLMJudge(model, max_tokens=args.max_tokens),
            judge_metadata=provider.public_diagnostics(),
        )
        out = Path(args.out)
        out.mkdir(parents=True, exist_ok=True)
        report_path = out / "agreement_report.json"
        _write_json(report_path, report)
        print(json.dumps({
            "out": str(out),
            "agreement_report": report,
        }, indent=2, sort_keys=True, ensure_ascii=False))
        if report["technical_error_count"]:
            return 2
        return 0 if report["accepted"] else 1
    return 2


def _add_runtime_options(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--provider-source", choices=["db", "env"], default="db")
    parser.add_argument("--eval-dump", default=os.getenv("EVAL_DUMP_DIR", ""))
    parser.add_argument("--max-tokens", type=int, default=3000)


def _live_harness(
    provider_source: str,
    max_completion_tokens: int,
    eval_dump: str,
):
    provider = _provider(provider_source)
    runtime = build_harness_runtime(
        provider,
        max_completion_tokens=max_completion_tokens,
    )
    return provider, LiveResearchChatHarness(runtime, eval_dump_dir=eval_dump or None)


def _judge_model(provider_source: str):
    provider = _provider(provider_source)
    return provider, MiniMaxJudgeModel(provider)


def _provider(provider_source: str):
    store = DockerMySqlProviderConfigStore() if provider_source == "db" else EnvProviderConfigStore()
    return store.load_active_provider("llm")


def _dataset_with_cases(dataset, cases):
    return replace(dataset, cases=cases)


def _write_runs(out_path: str, runs: list[dict], report: dict) -> Path:
    out = Path(out_path)
    out.mkdir(parents=True, exist_ok=True)
    for run in runs:
        case_dir = out / str(run["case_id"])
        case_dir.mkdir(parents=True, exist_ok=True)
        _write_json(case_dir / "harness_run.json", run)
        _write_child_artifacts(case_dir, run)
    _write_json(out / "score_report.json", report)
    return out


def _write_child_artifacts(case_dir: Path, run: dict) -> None:
    for field in (
        "evidence_ledger",
        "citation_validation",
        "research_answer",
        "skills_used",
        "react_trace",
        "paper_candidates",
    ):
        value = run.get(field, []) if field in {"skills_used", "react_trace", "paper_candidates"} else run[field]
        _write_json(case_dir / f"{field}.json", value)


def _write_json(path: Path, value: object) -> None:
    with path.open("w", encoding="utf-8") as handle:
        json.dump(value, handle, indent=2, sort_keys=True, ensure_ascii=False)
        handle.write("\n")
