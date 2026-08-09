from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import replace
from pathlib import Path
from uuid import uuid4

from .utils.models import as_list, child_map
from .corpus.gateway import JavaCorpusGateway
from .evaluation.audit import audit_dataset
from .evaluation.claim_audit import audit_claim_locations
from .evaluation.claim_evidence import (
    canonical_sha256,
    dataset_content_sha256,
    file_sha256,
    generate_semantic_judgments,
    load_judge_gate,
    load_semantic_judgments,
)
from .evaluation.claim_judge import CLAIM_JUDGE_PROMPT_VERSION, ClaimEvidenceJudge
from .evaluation.claim_judge_calibration import (
    evaluate_claim_judge_calibration,
    load_claim_judge_labels,
)
from .evaluation.dataset import load_dataset
from .evaluation.golden_fixture import GoldenFixtureHarness
from .evaluation.golden_case import case_question, conversation_state_for_case
from .evaluation.judge import (
    LLMJudge,
    evaluate_calibration,
    load_calibration_cases,
)
from .evaluation.judge_model import MiniMaxJudgeModel
from .evaluation.product_runner import (
    GoldenJavaCorpusReader,
    load_product_corpus_map,
    product_reader_for_claim,
    product_reader_for_case,
    validate_product_scope,
)
from .evaluation.paperloom31 import (
    PreparationError,
    create_snapshot,
    ensure_benchmark_config,
    file_sha256 as benchmark_file_sha256,
    prepare_papers,
    run_benchmark,
    scan_papers,
)
from .evaluation.retrieval import evaluate_product_retrieval
from .evaluation.scoring import BehaviorScorer
from .orchestration.live_chat import LiveResearchChatHarness
from .orchestration.runtime import build_harness_runtime
from .transport.provider_config import DockerMySqlProviderConfigStore, EnvProviderConfigStore
from .transport.service import serve


PROVIDER_SOURCE_CHOICES = ("db", "env", "openai-env")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run the Python research harness prototype.")
    parser.add_argument("--manifest", default="research/golden-data/manifest.yaml")
    subcommands = parser.add_subparsers(dest="command", required=True)
    paperloom31_parser = subcommands.add_parser(
        "paperloom31",
        help="Prepare and run the PaperLoom-31 automated benchmark.",
    )
    paperloom31_commands = paperloom31_parser.add_subparsers(
        dest="paperloom31_command",
        required=True,
    )
    paperloom31_prepare = paperloom31_commands.add_parser(
        "prepare",
        help="Scan the corpus and prepare the first N papers through the product upload path.",
    )
    paperloom31_prepare.add_argument("--papers-dir", default="papers")
    paperloom31_prepare.add_argument(
        "--config",
        default="research/benchmark/paperloom-31-v1/benchmark.yaml",
    )
    paperloom31_prepare.add_argument("--limit", type=int, default=1)
    paperloom31_prepare.add_argument(
        "--api-base-url",
        default=os.getenv("PAPERLOOM_API_BASE_URL", "http://127.0.0.1:8081/api/v1"),
    )
    paperloom31_prepare.add_argument("--env-file", default=".env")
    paperloom31_prepare.add_argument("--provider-source", choices=PROVIDER_SOURCE_CHOICES, default="env")
    paperloom31_prepare.add_argument("--poll-timeout-seconds", type=int, default=1800)
    paperloom31_prepare.add_argument("--poll-interval-seconds", type=float, default=5.0)
    paperloom31_snapshot = paperloom31_commands.add_parser(
        "snapshot",
        help="Export Current Reading Model candidates and freeze generated benchmark cases.",
    )
    paperloom31_snapshot.add_argument("--papers-dir", default="papers")
    paperloom31_snapshot.add_argument(
        "--config",
        default="research/benchmark/paperloom-31-v1/benchmark.yaml",
    )
    paperloom31_snapshot.add_argument(
        "--snapshots-dir",
        default="research/benchmark/local/snapshots",
    )
    paperloom31_snapshot.add_argument(
        "--api-base-url",
        default=os.getenv("PAPERLOOM_API_BASE_URL", "http://127.0.0.1:8081/api/v1"),
    )
    paperloom31_snapshot.add_argument("--env-file", default=".env")
    paperloom31_snapshot.add_argument("--provider-source", choices=PROVIDER_SOURCE_CHOICES, default="env")
    paperloom31_run = paperloom31_commands.add_parser(
        "run",
        help="Run L0/G0/L1/L2/L3 against one immutable PaperLoom-31 snapshot.",
    )
    paperloom31_run.add_argument("--snapshot", required=True)
    paperloom31_run.add_argument(
        "--config",
        default="research/benchmark/paperloom-31-v1/benchmark.yaml",
    )
    paperloom31_run.add_argument("--runs-dir", default="research/benchmark/local/runs")
    paperloom31_run.add_argument(
        "--api-base-url",
        default=os.getenv("PAPERLOOM_API_BASE_URL", "http://127.0.0.1:8081/api/v1"),
    )
    paperloom31_run.add_argument("--env-file", default=".env")
    paperloom31_run.add_argument("--provider-source", choices=PROVIDER_SOURCE_CHOICES, default="env")
    subcommands.add_parser("validate", help="Load the dataset and validate deterministic fixture traces.")
    audit_parser = subcommands.add_parser("audit", help="Verify authored anchors against parsed reading models.")
    audit_parser.add_argument(
        "--out",
        default="research/golden-data/local-runs/stable-anchor-audit.json",
    )
    claim_audit_parser = subcommands.add_parser(
        "claim-audit",
        help="Resolve every accepted Golden claim location through the product corpus API.",
    )
    claim_audit_parser.add_argument("--product-corpus-map", required=True)
    claim_audit_parser.add_argument("--out", required=True)
    retrieval_parser = subcommands.add_parser(
        "retrieval-eval",
        help="Evaluate Java/Qdrant product location recall without running the answer agent.",
    )
    retrieval_parser.add_argument("--product-corpus-map", required=True)
    retrieval_parser.add_argument("--claim-id", action="append", default=[])
    retrieval_parser.add_argument("--out", required=True)
    rescore_parser = subcommands.add_parser(
        "rescore",
        help="Rescore saved harness_run.json artifacts without calling a model.",
    )
    rescore_parser.add_argument("--runs", required=True)
    rescore_parser.add_argument("--out", required=True)
    rescore_parser.add_argument("--semantic-judgments")
    rescore_parser.add_argument("--calibration-report")
    rescore_parser.add_argument("--holdout-report")
    rescore_parser.add_argument("--candidates-out")
    semantic_parser = subcommands.add_parser(
        "judge-saved-runs",
        help="Generate offline claim/block judgments for saved runs without rerunning the agent.",
    )
    semantic_parser.add_argument("--runs", required=True)
    semantic_parser.add_argument("--out", required=True)
    semantic_parser.add_argument("--provider-source", choices=PROVIDER_SOURCE_CHOICES, default="db")
    semantic_parser.add_argument("--max-tokens", type=int, default=2200)
    agent_parser = subcommands.add_parser(
        "agent-run",
        help="Run the real tool-using agent through the Java/Qdrant product corpus.",
    )
    agent_parser.add_argument("--out", default="research/golden-data/local-runs/python-minimax-agent")
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
    worker_parser = subcommands.add_parser("worker", help="Run a Redis Streams research harness worker.")
    worker_parser.add_argument("--redis-url", default=os.getenv("RESEARCH_HARNESS_REDIS_URL", "redis://127.0.0.1:6379/0"))
    worker_parser.add_argument("--group", default=os.getenv("RESEARCH_HARNESS_REDIS_GROUP", "paperloom-research-harness"))
    worker_parser.add_argument("--worker-id", default=os.getenv("RESEARCH_HARNESS_WORKER_ID", ""))
    worker_parser.add_argument("--jobs-key", default=os.getenv("RESEARCH_HARNESS_REDIS_JOBS_STREAM", "paperloom:research:harness:jobs"))
    worker_parser.add_argument("--events-prefix", default=os.getenv("RESEARCH_HARNESS_REDIS_EVENTS_PREFIX", "paperloom:research:harness:events:"))
    worker_parser.add_argument("--status-prefix", default=os.getenv("RESEARCH_HARNESS_REDIS_STATUS_PREFIX", "paperloom:research:harness:status:"))
    worker_parser.add_argument("--cancel-prefix", default=os.getenv("RESEARCH_HARNESS_REDIS_CANCEL_PREFIX", "paperloom:research:harness:cancel:"))
    worker_parser.add_argument("--lock-prefix", default=os.getenv("RESEARCH_HARNESS_REDIS_LOCK_PREFIX", "paperloom:research:harness:lock:"))
    worker_parser.add_argument("--block-ms", type=int, default=int(os.getenv("RESEARCH_HARNESS_WORKER_BLOCK_MS", "5000")))
    worker_parser.add_argument("--max-concurrent-runs", type=int, default=int(os.getenv("RESEARCH_HARNESS_WORKER_MAX_CONCURRENT_RUNS", "1")))
    worker_parser.add_argument("--job-timeout-seconds", type=int, default=int(os.getenv("RESEARCH_HARNESS_JOB_TIMEOUT_SECONDS", "900")))
    worker_parser.add_argument("--event-ttl-seconds", type=int, default=int(os.getenv("RESEARCH_HARNESS_EVENT_TTL_SECONDS", "1800")))
    worker_parser.add_argument("--event-trim-maxlen", type=int, default=int(os.getenv("RESEARCH_HARNESS_EVENT_TRIM_MAXLEN", "500")))
    worker_parser.add_argument("--heartbeat-seconds", type=int, default=int(os.getenv("RESEARCH_HARNESS_WORKER_HEARTBEAT_SECONDS", "10")))
    worker_parser.add_argument("--stale-pending-seconds", type=int, default=int(os.getenv("RESEARCH_HARNESS_STALE_PENDING_SECONDS", "120")))
    judge_parser = subcommands.add_parser(
        "judge-calibrate",
        help="Compare one LLM judge with fixed human-labelled harness runs.",
    )
    judge_parser.add_argument("--labels", default="research/golden-data/human-labels.yaml")
    judge_parser.add_argument("--out", default="research/golden-data/local-runs/llm-judge-calibration")
    judge_parser.add_argument("--provider-source", choices=PROVIDER_SOURCE_CHOICES, default="db")
    judge_parser.add_argument("--max-tokens", type=int, default=1200)
    claim_judge_parser = subcommands.add_parser(
        "claim-judge-calibrate",
        help="Evaluate the v4 claim/block judge against frozen labels.",
    )
    claim_judge_parser.add_argument("--labels", required=True)
    claim_judge_parser.add_argument("--out", required=True)
    claim_judge_parser.add_argument("--provider-source", choices=PROVIDER_SOURCE_CHOICES, default="db")
    claim_judge_parser.add_argument("--max-tokens", type=int, default=2200)
    args = parser.parse_args(argv)

    if args.command == "paperloom31" and args.paperloom31_command == "prepare":
        try:
            papers = scan_papers(args.papers_dir)
            provider = _provider(args.provider_source)
            config_path = Path(args.config)
            ensure_benchmark_config(
                config_path,
                papers,
                generator_provider=provider.provider,
                generator_model=provider.model,
            )
            prepared = prepare_papers(
                papers,
                limit=args.limit,
                api_base_url=args.api_base_url,
                env_path=args.env_file,
                poll_timeout_seconds=args.poll_timeout_seconds,
                poll_interval_seconds=args.poll_interval_seconds,
            )
        except PreparationError as error:
            print(json.dumps({"error": "paperloom31_preparation_failed", **error.as_dict()}, indent=2), file=sys.stderr)
            return 2
        except Exception as error:
            print(json.dumps({
                "error": "paperloom31_preparation_failed",
                "stage": "prepare",
                "code": type(error).__name__,
                "detail": str(error),
            }, indent=2), file=sys.stderr)
            return 2
        print(json.dumps({
            "dataset_id": "paperloom-31-v1",
            "config": str(config_path),
            "config_sha256": benchmark_file_sha256(config_path),
            "configured_paper_count": len(papers),
            "prepared_paper_count": len(prepared),
            "papers": prepared,
        }, indent=2, ensure_ascii=False))
        return 0
    if args.command == "paperloom31" and args.paperloom31_command == "snapshot":
        try:
            papers = scan_papers(args.papers_dir)
            provider = _provider(args.provider_source)
            path, snapshot_sha256, snapshot = create_snapshot(
                args.config,
                papers,
                snapshots_dir=args.snapshots_dir,
                provider=provider,
                api_base_url=args.api_base_url,
                env_path=args.env_file,
            )
        except PreparationError as error:
            print(json.dumps({"error": "paperloom31_snapshot_failed", **error.as_dict()}, indent=2), file=sys.stderr)
            return 2
        except Exception as error:
            print(json.dumps({
                "error": "paperloom31_snapshot_failed",
                "stage": "snapshot",
                "code": type(error).__name__,
                "detail": str(error),
            }, indent=2), file=sys.stderr)
            return 2
        targets = child_map(snapshot.get("targets"))
        print(json.dumps({
            "dataset_id": snapshot.get("dataset_id"),
            "snapshot": str(path),
            "snapshot_sha256": snapshot_sha256,
            "candidate_summary": snapshot.get("candidate_summary"),
            "target_count": len(targets),
            "target_types": {
                location_type: sum(
                    child_map(target).get("location_type") == location_type
                    for target in targets.values()
                )
                for location_type in ("PASSAGE", "TABLE", "FIGURE")
            },
            "agent_case_count": len(snapshot.get("agent_cases") or []),
        }, indent=2, ensure_ascii=False))
        return 0
    if args.command == "paperloom31" and args.paperloom31_command == "run":
        try:
            provider = _provider(args.provider_source)
            path, report = run_benchmark(
                args.snapshot,
                config_path=args.config,
                runs_dir=args.runs_dir,
                provider=provider,
                api_base_url=args.api_base_url,
                env_path=args.env_file,
            )
        except PreparationError as error:
            print(json.dumps({"error": "paperloom31_run_failed", **error.as_dict()}, indent=2), file=sys.stderr)
            return 2
        except Exception as error:
            print(json.dumps({
                "error": "paperloom31_run_failed",
                "stage": "run",
                "code": type(error).__name__,
                "detail": str(error),
            }, indent=2), file=sys.stderr)
            return 2
        print(json.dumps({
            "out": str(path),
            "run_id": report.get("run_id"),
            "baseline": report.get("baseline"),
            "internal_beta_gate": report.get("internal_beta_gate"),
            "l1_metrics": child_map(report.get("l1")).get("metrics"),
            "l2_metrics": child_map(report.get("l2")).get("metrics"),
            "l3_hard_passed": child_map(report.get("l3")).get("hard_passed"),
        }, indent=2, ensure_ascii=False))
        return 0

    if args.command == "validate":
        dataset = load_dataset(args.manifest)
        harness = GoldenFixtureHarness()
        runs = [harness.run_case(dataset, case) for case in dataset.cases]
        report = BehaviorScorer().score_dataset(dataset, runs)
        print(json.dumps(report, indent=2, sort_keys=True))
        return 0 if report["failed_count"] == 0 and report["review_required_count"] == 0 else 1
    if args.command == "audit":
        report = audit_dataset(load_dataset(args.manifest))
        target = Path(args.out)
        target.parent.mkdir(parents=True, exist_ok=True)
        _write_json(target, report)
        print(json.dumps(report, indent=2, sort_keys=True))
        return 0 if report["failed_count"] == 0 else 1
    if args.command == "claim-audit":
        try:
            target = Path(args.out)
            _require_new_path(target)
            dataset = load_dataset(args.manifest)
            corpus_map = load_product_corpus_map(args.product_corpus_map, dataset)
            required_papers = {
                str(child_map(item).get("paper_id") or "")
                for claim in dataset.claims_by_id.values()
                for item in as_list(claim.get("required_evidence"))
            }
            missing = sorted(
                required_papers - set(corpus_map.product_paper_ids_by_golden_id)
            )
            if missing:
                raise ValueError(f"product corpus map is missing claim papers: {missing}")
            mapping = {
                paper_id: corpus_map.product_paper_ids_by_golden_id[paper_id]
                for paper_id in required_papers
            }
            delegate = JavaCorpusGateway().reader(
                request_id=f"golden-claim-audit-{uuid4().hex}",
                conversation_id=f"golden-claim-audit-{uuid4().hex}",
                user_id=corpus_map.user_id,
                scope_paper_ids=list(mapping.values()),
            )
            reader = GoldenJavaCorpusReader(delegate, mapping)
            report = audit_claim_locations(dataset, reader)
        except Exception as error:
            print(json.dumps({
                "error": "claim_location_audit_failed",
                "error_type": type(error).__name__,
                "message": str(error),
            }, indent=2, sort_keys=True), file=sys.stderr)
            return 2
        target.parent.mkdir(parents=True, exist_ok=True)
        _write_json(target, report)
        print(json.dumps({"out": str(target), "claim_location_audit": report}, indent=2))
        return 0 if report["failed_count"] == 0 else 1
    if args.command == "retrieval-eval":
        dataset = load_dataset(args.manifest)
        selected = set(args.claim_id)
        unknown_claim_ids = sorted(selected - set(dataset.claims_by_id))
        if unknown_claim_ids:
            print(json.dumps({
                "error": "unknown_claim_id",
                "claim_ids": unknown_claim_ids,
            }, indent=2, sort_keys=True), file=sys.stderr)
            return 2
        claims = [
            claim
            for claim_id, claim in dataset.claims_by_id.items()
            if not selected or claim_id in selected
        ]
        target = Path(args.out)
        try:
            _require_new_path(target)
            corpus_map = load_product_corpus_map(args.product_corpus_map, dataset)
            gateway = JavaCorpusGateway()
            report = evaluate_product_retrieval(
                dataset,
                claims,
                lambda claim: product_reader_for_claim(
                    gateway,
                    claim,
                    corpus_map,
                    request_id=f"golden-retrieval-{claim['claim_id']}-{uuid4().hex}",
                    conversation_id=f"golden-retrieval-{claim['claim_id']}-{uuid4().hex}",
                ),
            )
        except Exception as error:
            print(json.dumps({
                "error": "product_retrieval_evaluation_failed",
                "error_type": type(error).__name__,
                "message": str(error),
            }, indent=2, sort_keys=True), file=sys.stderr)
            return 2
        target.parent.mkdir(parents=True, exist_ok=True)
        _write_json(target, report)
        print(json.dumps({
            "out": str(target),
            "configured_claim_count": report["configured_claim_count"],
            "probe_count": report["probe_count"],
            "technical_error_count": report["technical_error_count"],
            "metrics": report["metrics"],
        }, indent=2, sort_keys=True))
        return 2 if report["technical_error_count"] else 0
    if args.command == "rescore":
        dataset = load_dataset(args.manifest)
        runs_root = Path(args.runs)
        try:
            runs = [
                _load_saved_case_run(runs_root, str(case["id"]))
                for case in dataset.cases
            ]
            semantic = None
            gate = None
            semantic_options = (
                args.semantic_judgments,
                args.calibration_report,
                args.holdout_report,
            )
            if any(semantic_options) and not all(semantic_options):
                raise ValueError(
                    "--semantic-judgments, --calibration-report, and --holdout-report "
                    "must be supplied together"
                )
            if args.semantic_judgments:
                semantic = load_semantic_judgments(args.semantic_judgments, dataset, runs)
                gate = load_judge_gate(args.calibration_report, args.holdout_report)
            target = Path(args.out)
            _require_new_path(target)
            if args.candidates_out:
                _require_new_path(Path(args.candidates_out))
            report = BehaviorScorer(semantic, gate).score_dataset(dataset, runs)
            report["source_run_directory"] = str(runs_root.resolve())
        except (OSError, ValueError, json.JSONDecodeError) as error:
            print(json.dumps({
                "error": "saved_run_rescore_failed",
                "error_type": type(error).__name__,
                "message": str(error),
            }, indent=2, sort_keys=True), file=sys.stderr)
            return 2
        target.parent.mkdir(parents=True, exist_ok=True)
        _write_json(target, report)
        if args.candidates_out:
            candidate_target = Path(args.candidates_out)
            candidate_target.parent.mkdir(parents=True, exist_ok=True)
            _write_json(candidate_target, {
                "schema_version": "harness-claim-location-candidates/v1",
                "dataset_id": dataset.manifest.get("dataset_id"),
                "score_report": str(target),
                "candidates": report["review_candidates"],
            })
        print(json.dumps({
            "out": str(target),
            "runs": str(runs_root),
            "score_report": report,
        }, indent=2, sort_keys=True))
        return 0 if report["failed_count"] == 0 and report["review_required_count"] == 0 else 1
    if args.command == "judge-saved-runs":
        dataset = load_dataset(args.manifest)
        runs_root = Path(args.runs)
        target = Path(args.out)
        try:
            _require_new_path(target)
            runs = [
                _load_saved_case_run(runs_root, str(case["id"]))
                for case in dataset.cases
            ]
            provider, model = _judge_model(args.provider_source)
            report = generate_semantic_judgments(
                dataset,
                runs,
                ClaimEvidenceJudge(model, max_tokens=args.max_tokens),
                judge_metadata=provider.public_diagnostics(),
                prompt_version=CLAIM_JUDGE_PROMPT_VERSION,
            )
        except Exception as error:
            print(json.dumps({
                "error": "saved_run_judgment_failed",
                "error_type": type(error).__name__,
                "message": str(error),
            }, indent=2, sort_keys=True), file=sys.stderr)
            return 2
        target.parent.mkdir(parents=True, exist_ok=True)
        _write_json(target, report)
        print(json.dumps({
            "out": str(target),
            "runs": str(runs_root),
            "case_count": len(report["cases"]),
            "technical_error_count": report["technical_error_count"],
            "judge": report["judge"],
        }, indent=2, sort_keys=True))
        return 2 if report["technical_error_count"] else 0
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
            _require_new_path(Path(args.out))
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
        run_manifest = _product_run_manifest(
            dataset if not selected else _dataset_with_cases(dataset, cases),
            args.product_corpus_map,
            runs,
        )
        out = _write_runs(args.out, runs, report, run_manifest=run_manifest)
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
        return 0 if report["failed_count"] == 0 and report["review_required_count"] == 0 else 1
    if args.command == "serve":
        serve(
            host=args.host,
            port=args.port,
            internal_token=args.internal_token,
        )
        return 0
    if args.command == "worker":
        from .transport.redis_worker import RedisWorkerConfig, run_worker, worker_id

        run_worker(RedisWorkerConfig(
            redis_url=args.redis_url,
            worker_id=worker_id(args.worker_id),
            group=args.group,
            jobs_key=args.jobs_key,
            events_prefix=args.events_prefix,
            status_prefix=args.status_prefix,
            cancel_prefix=args.cancel_prefix,
            lock_prefix=args.lock_prefix,
            block_ms=args.block_ms,
            job_timeout_seconds=args.job_timeout_seconds,
            event_ttl_seconds=args.event_ttl_seconds,
            event_trim_maxlen=args.event_trim_maxlen,
            heartbeat_seconds=args.heartbeat_seconds,
            stale_pending_seconds=args.stale_pending_seconds,
            max_concurrent_runs=args.max_concurrent_runs,
        ))
        return 0
    if args.command == "judge-calibrate":
        try:
            out = Path(args.out)
            _require_new_path(out)
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
    if args.command == "claim-judge-calibrate":
        try:
            target = Path(args.out)
            _require_new_path(target)
            labels = load_claim_judge_labels(args.labels)
            provider, model = _judge_model(args.provider_source)
            report = evaluate_claim_judge_calibration(
                labels,
                ClaimEvidenceJudge(model, max_tokens=args.max_tokens),
                judge_metadata=provider.public_diagnostics(),
            )
        except Exception as error:
            print(json.dumps({
                "error": "claim_judge_calibration_failed",
                "error_type": type(error).__name__,
                "message": str(error),
            }, indent=2, sort_keys=True), file=sys.stderr)
            return 2
        target.parent.mkdir(parents=True, exist_ok=True)
        _write_json(target, report)
        print(json.dumps({
            "out": str(target),
            "claim_judge_agreement": report,
        }, indent=2, sort_keys=True))
        if report["technical_error_count"]:
            return 2
        return 0 if report["accepted"] else 1
    return 2


def _add_runtime_options(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--provider-source", choices=PROVIDER_SOURCE_CHOICES, default="db")
    parser.add_argument("--eval-dump", default=os.getenv("EVAL_DUMP_DIR", ""))


def _live_harness(
    provider_source: str,
    eval_dump: str,
):
    provider = _provider(provider_source)
    runtime = build_harness_runtime(provider)
    return provider, LiveResearchChatHarness(runtime, eval_dump_dir=eval_dump or None)


def _judge_model(provider_source: str):
    provider = _provider(provider_source)
    return provider, MiniMaxJudgeModel(provider)


def _provider(provider_source: str):
    if provider_source == "db":
        store = DockerMySqlProviderConfigStore()
    elif provider_source == "openai-env":
        store = EnvProviderConfigStore(
            api_base_url_env="OPENAI_BASE_URL",
            api_key_env="OPENAI_API_KEY",
            model_env="OPENAI_MODEL",
            provider="openai",
            api_style="openai-compatible",
        )
    else:
        store = EnvProviderConfigStore()
    return store.load_active_provider("llm")


def _dataset_with_cases(dataset, cases):
    return replace(dataset, cases=cases)


def _write_runs(
    out_path: str,
    runs: list[dict],
    report: dict,
    *,
    run_manifest: dict | None = None,
) -> Path:
    out = Path(out_path)
    out.mkdir(parents=True, exist_ok=False)
    for run in runs:
        case_dir = out / str(run["case_id"])
        case_dir.mkdir(parents=True, exist_ok=True)
        _write_json(case_dir / "harness_run.json", run)
        _write_child_artifacts(case_dir, run)
    if run_manifest is not None:
        manifest_path = out / "run_manifest.json"
        _write_json(manifest_path, run_manifest)
        report["run_manifest"] = {
            "path": str(manifest_path.resolve()),
            "sha256": file_sha256(manifest_path),
        }
    _write_json(out / "score_report.json", report)
    return out


def _product_run_manifest(dataset, corpus_map_path: str, runs: list[dict]) -> dict:
    locations: dict[tuple[str, str], dict] = {}
    retrieval_observations = []
    for run in runs:
        case_id = str(run.get("case_id") or run.get("question_id") or "")
        for raw_item in as_list(child_map(run.get("evidence_ledger")).get("items")):
            item = child_map(raw_item)
            paper_id = str(item.get("paper_id") or "")
            location_ref = str(item.get("location_ref") or item.get("location") or "")
            if not paper_id or not location_ref:
                continue
            locations[(paper_id, location_ref)] = {
                "paper_id": paper_id,
                "location_ref": location_ref,
                "element_type": item.get("element_type"),
                "text_sha256": canonical_sha256(str(item.get("span_text") or "")),
            }
        retrieval_observations.append({
            "case_id": case_id,
            "paper_candidates": as_list(run.get("paper_candidates")),
            "react_trace": as_list(run.get("react_trace")),
        })
    corpus_map_sha256 = file_sha256(corpus_map_path)
    generation_material = {
        "product_corpus_map_sha256": corpus_map_sha256,
        "locations": [locations[key] for key in sorted(locations)],
        "retrieval_observations": retrieval_observations,
    }
    return {
        "schema_version": "harness-benchmark-run-manifest/v1",
        "dataset_id": dataset.manifest.get("dataset_id"),
        "dataset_content_sha256": dataset_content_sha256(dataset),
        "claim_catalog_sha256": canonical_sha256(dataset.claims_by_id),
        "case_ids": [str(run.get("case_id") or run.get("question_id") or "") for run in runs],
        "corpus_backend": "java-qdrant",
        "product_corpus_map": {
            "path": str(Path(corpus_map_path).resolve()),
            "sha256": corpus_map_sha256,
        },
        "corpus_index_generation": {
            "scheme": "content-addressed-run-observation/v1",
            "sha256": canonical_sha256(generation_material),
            "observed_location_count": len(locations),
        },
    }


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


def _load_saved_case_run(runs_root: Path, case_id: str) -> dict:
    path = runs_root / case_id / "harness_run.json"
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"saved run is not an object: {path}")
    actual_case_id = str(value.get("case_id") or value.get("question_id") or "")
    if actual_case_id != case_id:
        raise ValueError(
            f"saved run case mismatch: expected={case_id}, actual={actual_case_id or '<missing>'}"
        )
    return value


def _write_json(path: Path, value: object) -> None:
    with path.open("w", encoding="utf-8") as handle:
        json.dump(value, handle, indent=2, sort_keys=True, ensure_ascii=False)
        handle.write("\n")


def _require_new_path(path: Path) -> None:
    if path.exists():
        raise ValueError(f"refusing to overwrite existing artifact: {path}")
