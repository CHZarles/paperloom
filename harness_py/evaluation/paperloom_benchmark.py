"""基准执行入口：run_benchmark 按 L0/G0/L1/L2/L3 顺序跑完并落报告。"""

from __future__ import annotations

import json
import time
from pathlib import Path
from uuid import uuid4

import yaml

from ..corpus.gateway import JavaCorpusGateway
from ..corpus.tools import ReadingCorpusTools
from ..orchestration.conversation import ConversationState
from ..orchestration.live_chat import LiveResearchChatHarness
from ..orchestration.runtime import build_harness_runtime
from ..transport.provider_config import ProviderConfig
from ..utils.digest import canonical_sha256 as _canonical_sha256
from ..utils.digest import file_sha256
from ..utils.models import GoldenDataset, as_list, child_map, utc_now_iso
from .judge_model import MiniMaxJudgeModel
from .paperloom_assessment import (
    _assess_agent_case,
    _assess_protocol_trace,
    _controlled_protocol_metrics,
    _judge_agent_case,
    _rank,
    _retrieval_metrics,
    _source_quotes_cover,
    _target_funnel_metrics,
)
from .paperloom_common import (
    DATASET_ID,
    EVIDENCE_TYPES,
    EXPECTED_PAPER_COUNT,
    PreparationError,
    RUN_SCHEMA_VERSION,
    SOURCE_ALIGNED_QUERY_SCHEMA_VERSION,
    _failure,
    _git_revision,
    _mapping,
    _records,
    _stage_result,
    _text_sha256,
    _write_json,
)
from .paperloom_product_api import _ProductApi, _credentials
from .paperloom_snapshot import _load_source_aligned_queries, validate_snapshot


def run_benchmark(
    snapshot_path: str | Path,
    *,
    source_aligned_queries_path: str | Path,
    config_path: str | Path,
    runs_dir: str | Path,
    provider: ProviderConfig,
    api_base_url: str,
    env_path: str | Path = ".env",
    benchmark_user_id: int,
    baseline_run_path: str | Path | None = None,
) -> tuple[Path, dict[str, object]]:
    snapshot_source = Path(snapshot_path)
    snapshot = _mapping(json.loads(snapshot_source.read_text(encoding="utf-8")))
    validate_snapshot(snapshot)
    snapshot_sha256 = _canonical_sha256(snapshot)
    if snapshot_source.stem != snapshot_sha256:
        raise PreparationError("run", "SNAPSHOT_HASH_MISMATCH", str(snapshot_source))
    if file_sha256(config_path) != snapshot.get("config_sha256"):
        raise PreparationError("run", "CONFIG_HASH_MISMATCH", str(config_path))
    source_aligned_queries = _load_source_aligned_queries(
        source_aligned_queries_path,
        snapshot,
    )
    source_aligned_query_file_sha256 = file_sha256(source_aligned_queries_path)
    provider_identity = provider.public_diagnostics()

    username, password = _credentials(env_path)
    with _ProductApi(api_base_url) as api:
        api.login(username, password)
        admin = api.me()
        users = api.users()
        benchmark_user = next(
            (
                user for user in users
                if str(user.get("id") or "") == str(benchmark_user_id)
                and str(user.get("role") or "").upper() == "USER"
            ),
            None,
        )
        if not benchmark_user:
            raise PreparationError(
                "run", "BENCHMARK_USER_INVALID", f"user {benchmark_user_id} does not exist or is not a USER"
            )
        starting_token_balances = api.user_usage(benchmark_user_id)
        product_rows = api.papers()
    admin_id = int(admin.get("id") or 0)
    user_id = benchmark_user_id
    if admin_id <= 0 or user_id <= 0:
        raise PreparationError("run", "BENCHMARK_USER_INVALID", "admin and ordinary user IDs are required")
    if baseline_run_path is not None:
        try:
            baseline = _mapping(json.loads(Path(baseline_run_path).read_text(encoding="utf-8")))
        except (OSError, json.JSONDecodeError) as error:
            raise PreparationError("run", "BASELINE_RUN_INVALID", str(error)) from error
        expected = {
            "snapshot": baseline.get("snapshot_sha256"),
            "config": baseline.get("config_sha256"),
            "source_aligned_queries": _mapping(
                _mapping(baseline.get("l2_source_aligned")).get("query_set")
            ).get("file_sha256"),
            "provider": baseline.get("provider"),
            "ordinary_user_id": _mapping(baseline.get("benchmark_user")).get("ordinary_user_id"),
            "starting_token_balances": _mapping(baseline.get("benchmark_user")).get("starting_token_balances"),
        }
        actual = {
            "snapshot": snapshot_sha256,
            "config": snapshot.get("config_sha256"),
            "source_aligned_queries": source_aligned_query_file_sha256,
            "provider": provider_identity,
            "ordinary_user_id": user_id,
            "starting_token_balances": starting_token_balances,
        }
        mismatches = [key for key in actual if actual[key] != expected[key]]
        if mismatches:
            raise PreparationError("run", "BASELINE_IDENTITY_MISMATCH", ", ".join(mismatches))

    run_id = f"{time.strftime('%Y%m%dT%H%M%SZ', time.gmtime())}-{uuid4().hex[:8]}"
    out = Path(runs_dir) / run_id
    out.mkdir(parents=True, exist_ok=False)
    (out / "agent").mkdir()
    (out / "judge").mkdir()

    started_at = utc_now_iso()
    gateway = JavaCorpusGateway(max_response_bytes=32 * 1024 * 1024)
    papers = _mapping(snapshot.get("papers"))
    targets = _mapping(snapshot.get("targets"))
    scope = [str(_mapping(paper).get("product_paper_id") or "") for paper in papers.values()]
    l0 = _run_l0(config_path, snapshot, product_rows, gateway)
    g0 = _run_g0(snapshot, product_rows, gateway, admin_id, user_id)
    l1 = _run_l1(snapshot, gateway, user_id)
    l2 = _run_l2(snapshot, gateway, user_id)

    harness = LiveResearchChatHarness(build_harness_runtime(provider), eval_dump_dir=out / "trace")
    judge = MiniMaxJudgeModel(provider)
    dataset_reader = gateway.reader(
        request_id=f"{run_id}-dataset",
        conversation_id=f"{run_id}-dataset",
        user_id=user_id,
        scope_paper_ids=scope,
    )
    dataset = dataset_reader.load_metadata_dataset()
    l3 = _run_l3(snapshot, dataset, gateway, user_id, harness, judge, out)
    l2_source_aligned = {
        **_run_l2(
            snapshot,
            gateway,
            user_id,
            query_by_target=source_aligned_queries,
            stage_name="L2_SOURCE_ALIGNED",
            request_label="l2-source-aligned",
        ),
        "query_set": {
            "schema_version": SOURCE_ALIGNED_QUERY_SCHEMA_VERSION,
            "file_sha256": source_aligned_query_file_sha256,
        },
    }

    agent_usage = {
        key: sum(int(_mapping(item.get("usage")).get(key) or 0) for item in _records(l3.get("cases")))
        for key in ("model_calls", "prompt_tokens", "completion_tokens", "total_tokens", "elapsed_ms")
    }
    hard_failures = [
        *(_records(l0.get("failures"))),
        *(_records(g0.get("failures"))),
        *[
            failure
            for case in _records(l3.get("cases"))
            for failure in _records(case.get("hard_failures"))
        ],
    ]
    beta_gate = {
        "passed": bool(l0.get("passed")) and bool(g0.get("passed")) and not hard_failures,
        "failure_count": len(hard_failures),
        "failures": hard_failures,
    }
    report: dict[str, object] = {
        "schema_version": RUN_SCHEMA_VERSION,
        "run_id": run_id,
        "dataset_id": DATASET_ID,
        "started_at": started_at,
        "completed_at": utc_now_iso(),
        "config_sha256": snapshot["config_sha256"],
        "snapshot_sha256": snapshot_sha256,
        "code_revision": _git_revision(),
        "provider": provider_identity,
        "generator": snapshot.get("generator"),
        "judge": {**provider_identity, "prompt_version": "paperloom-agent-judge-v1"},
        "benchmark_user": {
            "admin_user_id": admin_id,
            "ordinary_user_id": user_id,
            "ordinary_username": benchmark_user.get("username"),
            "starting_token_balances": starting_token_balances,
        },
        "l0": l0,
        "g0": g0,
        "l1": l1,
        "l2": l2,
        "l2_source_aligned": l2_source_aligned,
        "l3": l3,
        "usage": {
            "agent": agent_usage,
            "judge_requests": sum(
                _mapping(case.get("judge")).get("status") != "not_applicable"
                for case in _records(l3.get("cases"))
            ),
        },
        "technical_errors": [
            failure
            for stage in (l0, g0, l1, l2, l3, l2_source_aligned)
            for failure in _records(stage.get("technical_errors"))
        ],
        "baseline": {
            "established": all(bool(stage.get("executed")) for stage in (l0, g0, l1, l2, l3)),
            "stages": ["L0", "G0", "L1", "L2", "L3"],
        },
        "internal_beta_gate": beta_gate,
    }
    _write_json(out / "run.json", report)
    return out, report


def _run_l0(
    config_path: str | Path,
    snapshot: dict[str, object],
    product_rows: list[dict[str, object]],
    gateway: JavaCorpusGateway,
) -> dict[str, object]:
    failures: list[dict[str, object]] = []
    config = _mapping(yaml.safe_load(Path(config_path).read_text(encoding="utf-8")))
    rows_by_id = {str(row.get("paperId") or ""): row for row in product_rows}
    targets = _mapping(snapshot.get("targets"))
    for paper in _records(config.get("papers")):
        paper_id = str(_mapping(_mapping(snapshot.get("papers")).get(str(paper.get("id") or ""))).get("product_paper_id") or "")
        source = Path(str(paper.get("file") or ""))
        if not source.exists() or file_sha256(source) != paper.get("source_pdf_sha256"):
            failures.append(_failure("L0", "PDF_HASH_MISMATCH", str(source)))
        product = rows_by_id.get(paper_id, {})
        if str(product.get("processingStatus") or "").upper() != "COMPLETED":
            failures.append(_failure("L0", "PROCESSING_NOT_COMPLETED", paper_id))
        try:
            exported = gateway.post("/internal/v1/corpus/locations/export", {"paper_id": paper_id})
            frozen = _mapping(_mapping(snapshot.get("papers")).get(str(paper.get("id") or "")))
            if (
                exported.get("model_status") != "READING_MODEL_READY"
                or exported.get("retrieval_index_status") != "READY"
                or exported.get("model_version") != frozen.get("model_version")
                or exported.get("retrieval_index_contract") != frozen.get("index_identity")
            ):
                failures.append(_failure("L0", "CURRENT_MODEL_CONTRACT_MISMATCH", paper_id))
        except Exception as error:
            failures.append(_failure("L0", type(error).__name__, str(error), technical=True))
    if len(targets) != EXPECTED_PAPER_COUNT:
        failures.append(_failure("L0", "TARGET_COUNT_MISMATCH", str(len(targets))))
    for target_id, raw in targets.items():
        target = _mapping(raw)
        if not all(str(target.get(key) or "").strip() for key in ("content", "source_span_hash", "query")):
            failures.append(_failure("L0", "TARGET_INCOMPLETE", str(target_id)))
    return _stage_result(failures, configured=EXPECTED_PAPER_COUNT, ready=EXPECTED_PAPER_COUNT - len(failures))


def _run_g0(
    snapshot: dict[str, object],
    product_rows: list[dict[str, object]],
    gateway: JavaCorpusGateway,
    admin_id: int,
    user_id: int,
) -> dict[str, object]:
    failures: list[dict[str, object]] = []
    papers = _mapping(snapshot.get("papers"))
    targets = _mapping(snapshot.get("targets"))
    scope = [str(_mapping(paper).get("product_paper_id") or "") for paper in papers.values()]
    owned = {str(row.get("paperId") or "") for row in product_rows}
    published = {
        str(row.get("paperId") or "")
        for row in product_rows
        if str(row.get("libraryScope") or "").upper() == "GLOBAL"
    }
    if not set(scope) <= owned:
        failures.append(_failure("G0", "ADMIN_OWNERSHIP_MISSING", str(sorted(set(scope) - owned))))
    if not set(scope) <= published:
        failures.append(_failure("G0", "PUBLICATION_MISSING", str(sorted(set(scope) - published))))
    try:
        reader = gateway.reader(
            request_id="paperloom31-g0-access",
            conversation_id="paperloom31-g0-access",
            user_id=user_id,
            scope_paper_ids=scope,
        )
        visible = reader.search_papers({"query_text": "", "limit": 100}).get("candidates")
        visible_ids = {str(child_map(item).get("paper_id") or "") for item in as_list(visible)}
        if not set(scope) <= visible_ids:
            failures.append(_failure("G0", "ORDINARY_USER_ACCESS_MISSING", str(sorted(set(scope) - visible_ids))))

        excluded = scope[0]
        included = scope[1:]
        excluded_target = next(
            _mapping(target) for target in targets.values()
            if _mapping(target).get("product_paper_id") == excluded
        )
        isolated = gateway.reader(
            request_id="paperloom31-g0-isolation",
            conversation_id="paperloom31-g0-isolation",
            user_id=user_id,
            scope_paper_ids=included,
        )
        candidates = isolated.search_papers({"query_text": "", "limit": 100}).get("candidates")
        locations = isolated.search_locations({
            "paper_ids": included,
            "query_text": excluded_target.get("query"),
            "top_k": 20,
        }).get("locations")
        read = isolated.read_locations({"location_refs": [excluded_target.get("location_ref")]})
        leaked = (
            any(child_map(item).get("paper_id") == excluded for item in as_list(candidates))
            or any(child_map(item).get("paper_id") == excluded for item in as_list(locations))
            or bool(as_list(read.get("items")))
        )
        if leaked:
            failures.append(_failure("G0", "EXCLUDED_PAPER_LEAK", excluded))
    except Exception as error:
        failures.append(_failure("G0", type(error).__name__, str(error), technical=True))
    return _stage_result(failures, admin_user_id=admin_id, ordinary_user_id=user_id)


def _run_l1(
    snapshot: dict[str, object],
    gateway: JavaCorpusGateway,
    user_id: int,
) -> dict[str, object]:
    scope = [str(_mapping(paper).get("product_paper_id") or "") for paper in _mapping(snapshot.get("papers")).values()]
    reader = gateway.reader(request_id="paperloom31-l1", conversation_id="paperloom31-l1", user_id=user_id, scope_paper_ids=scope)
    tools = ReadingCorpusTools(reader.load_metadata_dataset(), reader)
    rows: list[dict[str, object]] = []
    errors: list[dict[str, object]] = []
    for paper_key, raw in _mapping(snapshot.get("papers")).items():
        paper = _mapping(raw)
        try:
            result = tools.search_paper_candidates({"query_text": paper.get("metadata_query"), "limit": 5})
            ids = [str(child_map(item).get("paper_id") or "") for item in as_list(result.get("candidates"))]
            expected = str(paper.get("product_paper_id") or "")
            rows.append({"paper": paper_key, "query": paper.get("metadata_query"), "rank": _rank(ids, expected), "returned_paper_ids": ids})
        except Exception as error:
            errors.append(_failure("L1", type(error).__name__, f"{paper_key}: {error}", technical=True))
            rows.append({"paper": paper_key, "rank": None, "returned_paper_ids": []})
    return {"executed": True, "query_count": len(rows), "metrics": _retrieval_metrics(rows, (1, 3, 5)), "queries": rows, "technical_errors": errors}


def _run_l2(
    snapshot: dict[str, object],
    gateway: JavaCorpusGateway,
    user_id: int,
    *,
    query_by_target: dict[str, str] | None = None,
    stage_name: str = "L2",
    request_label: str = "l2",
) -> dict[str, object]:
    rows: list[dict[str, object]] = []
    errors: list[dict[str, object]] = []
    exported_by_paper: dict[str, dict[str, dict[str, object]]] = {}
    for target_id, raw in _mapping(snapshot.get("targets")).items():
        target = _mapping(raw)
        paper_id = str(target.get("product_paper_id") or "")
        row: dict[str, object] = {
            "target_id": target_id,
            "location_type": target.get("location_type"),
            "rank": None,
            "exact_read_passed": False,
        }
        try:
            reader = gateway.reader(
                request_id=f"paperloom31-{request_label}-{target_id}",
                conversation_id=f"paperloom31-{request_label}-{target_id}",
                user_id=user_id,
                scope_paper_ids=[paper_id],
            )
            tools = ReadingCorpusTools(reader.load_metadata_dataset(), reader)
            identity = tools.find_papers_by_identity({"paper_id": paper_id})
            if identity.get("status") != "resolved":
                raise ValueError("oracle paper identity did not resolve")
            search = tools.search_paper_content({
                "paper_ids": [paper_id],
                "query_text": (
                    query_by_target[target_id]
                    if query_by_target is not None
                    else target.get("query")
                ),
                "element_types": [str(target.get("location_type") or "").lower()],
                "top_k": 10,
            })
            refs = [str(child_map(item).get("location_ref") or "") for item in as_list(search.get("locations"))]
            expected_ref = str(target.get("location_ref") or "")
            rank = _rank(refs, expected_ref)
            row["rank"] = rank
            row["returned_location_refs"] = refs
            if rank is not None:
                if paper_id not in exported_by_paper:
                    exported = gateway.post("/internal/v1/corpus/locations/export", {"paper_id": paper_id})
                    exported_by_paper[paper_id] = {
                        str(item.get("location_ref") or ""): item
                        for item in _records(exported.get("candidates"))
                    }
                source_span = str(exported_by_paper[paper_id].get(expected_ref, {}).get("source_span") or "")
                read = tools.read_paper_content({"location_refs": [expected_ref]})
                item = next(
                    (child_map(value) for value in as_list(read.get("items")) if child_map(value).get("location_ref") == expected_ref),
                    {},
                )
                quote = next(
                    (child_map(value) for value in as_list(item.get("source_quotes")) if child_map(value).get("location_ref") == expected_ref),
                    {},
                )
                checks = {
                    "location": bool(item),
                    "paper": item.get("paper_id") == paper_id,
                    "page": item.get("page") == target.get("page"),
                    "content_hash": _text_sha256(str(item.get("span_text") or "")) == target.get("content_hash"),
                    "source_span_hash": _text_sha256(source_span) == target.get("source_span_hash"),
                    "source_span_read": _source_quotes_cover(item.get("source_quotes"), source_span),
                    "source_quote": bool(str(quote.get("source_quote_ref") or "")),
                }
                row["checks"] = checks
                row["source_quote_ref"] = quote.get("source_quote_ref")
                row["exact_read_passed"] = all(checks.values())
        except Exception as error:
            errors.append(_failure(stage_name, type(error).__name__, f"{target_id}: {error}", technical=True))
        rows.append(row)
    by_type = {
        location_type: _retrieval_metrics(
            [row for row in rows if row.get("location_type") == location_type], (1, 3, 5, 10)
        )
        for location_type in sorted(EVIDENCE_TYPES)
    }
    return {
        "executed": True,
        "target_count": len(rows),
        "metrics": _retrieval_metrics(rows, (1, 3, 5, 10)),
        "by_type": by_type,
        "exact_read_passed": sum(bool(row.get("exact_read_passed")) for row in rows),
        "targets": rows,
        "technical_errors": errors,
    }


def _run_l3(
    snapshot: dict[str, object],
    dataset: GoldenDataset,
    gateway: JavaCorpusGateway,
    user_id: int,
    harness: LiveResearchChatHarness,
    judge: MiniMaxJudgeModel,
    out: Path,
) -> dict[str, object]:
    scope = sorted(dataset.paper_records_by_id)
    targets = {target_id: _mapping(target) for target_id, target in _mapping(snapshot.get("targets")).items()}
    rows: list[dict[str, object]] = []
    errors: list[dict[str, object]] = []
    for raw_case in _records(snapshot.get("agent_cases")):
        case = _mapping(raw_case)
        case_id = str(case.get("case_id") or "")
        state = ConversationState.from_dict({
            "conversation_id": f"paperloom31-{case_id}",
            "scope_paper_ids": scope,
            "message_history": case.get("history") or [],
        })
        reader = gateway.reader(
            request_id=f"paperloom31-l3-{case_id}",
            conversation_id=state.conversation_id,
            user_id=user_id,
            scope_paper_ids=scope,
        )
        run, _ = harness.run_turn(
            dataset,
            state,
            str(case.get("question") or ""),
            case_id_override=case_id,
            corpus_reader=reader,
        )
        _write_json(out / "agent" / f"{case_id}.json", run)
        protocol = _assess_protocol_trace(
            out / "trace" / str(run.get("run_id") or "") / "events.jsonl"
        )
        assessment = _assess_agent_case(case, run, targets, set(scope), protocol)
        errors.extend(
            failure for failure in _records(assessment.get("hard_failures"))
            if failure.get("technical")
        )
        usage = _mapping(_mapping(run.get("control")).get("usage")) or {
            key: _mapping(run.get("diagnostics")).get(key)
            for key in ("model_calls", "prompt_tokens", "completion_tokens", "total_tokens", "elapsed_ms")
        }
        if run.get("status") in {"FAILED_TECHNICAL", "LIMITED", "CANCELLED"}:
            judgment = {"status": "not_applicable", "reason": "agent_run_not_completed"}
        elif case.get("expected_contract") == "RESEARCH":
            try:
                judgment = _judge_agent_case(judge, case, run)
            except Exception as error:
                errors.append(_failure("L3_JUDGE", type(error).__name__, f"{case_id}: {error}", technical=True))
                judgment = {"status": "technical_error", "error": str(error)}
        else:
            judgment = {"status": "not_applicable"}
        _write_json(out / "judge" / f"{case_id}.json", judgment)
        rows.append({
            "case_id": case_id,
            "harness_id": run.get("harness_id"),
            **assessment,
            "usage": usage,
            "judge": judgment,
        })
    by_harness_id = {
        harness_id: _controlled_protocol_metrics([
            row for row in rows if str(row.get("harness_id") or "unknown") == harness_id
        ])
        for harness_id in sorted({str(row.get("harness_id") or "unknown") for row in rows})
    }
    return {
        "executed": True,
        "case_count": len(rows),
        "hard_passed": sum(not _records(row.get("hard_failures")) for row in rows),
        "target_funnel": _target_funnel_metrics(rows),
        "controlled_protocol_metrics": _controlled_protocol_metrics(rows),
        "by_harness_id": by_harness_id,
        "cases": rows,
        "technical_errors": errors,
    }
