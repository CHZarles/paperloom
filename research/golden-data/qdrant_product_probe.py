#!/usr/bin/env python3
from __future__ import annotations

import argparse
import copy
import gc
import hashlib
import json
import multiprocessing
import os
import statistics
import subprocess
import sys
import time
import uuid
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Any, Callable

import httpx
import yaml

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT))

# ruff: noqa: E402

from harness_py.core.models import GoldenDataset, JsonMap, as_list, child_map
from harness_py.corpus.pages import contains_normalized_phrase, normalize_text, page_matches
from harness_py.corpus.product_db_dataset import DockerMySqlProductCorpusStore, summarize_product_corpus
from harness_py.corpus.tools import ReadingCorpusTools
from harness_py.evaluation.dataset import load_dataset
from harness_py.evaluation.golden_case import case_question
from harness_py.evaluation.scoring import BehaviorScorer
from harness_py.orchestration.conversation import ConversationState
from harness_py.orchestration.live_chat import LiveResearchChatHarness
from harness_py.orchestration.runtime import build_harness_runtime
from harness_py.transport.provider_config import EnvProviderConfigStore, _read_env_file


REPORT_SCHEMA_VERSION = "harness-qdrant-product-probe-report/v3"
RUN_STATE_SCHEMA_VERSION = "harness-qdrant-product-probe-run-state/v1"
ADJUDICATION_SCHEMA_VERSION = "harness-qdrant-product-probe-adjudication/v1"
QUALITY_CANDIDATE_BUDGET_POLICY = "strict_requested_top_k"
DEFAULT_QDRANT_COLLECTION = "paperloom_reading_locations_bm25_v1"
RUN_STAGES = ("setup", "quality", "latency", "model_smoke", "report")


@dataclass(frozen=True)
class QueryProbe:
    case_id: str
    query_index: int
    arguments: JsonMap
    required_anchor_ids: tuple[str, ...]


class RunJournal:
    def __init__(self, output: Path, argv: list[str]):
        now = int(time.time() * 1000)
        self.path = output / "run-state.json"
        self.current_stage: str | None = None
        self.state: JsonMap = {
            "schema_version": RUN_STATE_SCHEMA_VERSION,
            "status": "running",
            "created_at_epoch_ms": now,
            "updated_at_epoch_ms": now,
            "output": str(output),
            "argv": argv,
            "stages": {
                stage: {"status": "pending"}
                for stage in RUN_STAGES
            },
        }
        self._persist()

    def start(self, stage: str) -> None:
        self._require_stage(stage)
        self.current_stage = stage
        self._update_stage(stage, "in_progress", started_at_epoch_ms=int(time.time() * 1000))

    def complete(self, stage: str, *, artifacts: list[str] | None = None) -> None:
        self._require_stage(stage)
        fields: JsonMap = {"completed_at_epoch_ms": int(time.time() * 1000)}
        if artifacts:
            fields["artifacts"] = artifacts
        self._update_stage(stage, "completed", **fields)
        if self.current_stage == stage:
            self.current_stage = None

    def skip(self, stage: str, reason: str) -> None:
        self._require_stage(stage)
        self._update_stage(
            stage,
            "skipped",
            completed_at_epoch_ms=int(time.time() * 1000),
            reason=reason,
        )
        if self.current_stage == stage:
            self.current_stage = None

    def finish(self) -> None:
        self.state["status"] = "complete"
        self.state["completed_at_epoch_ms"] = int(time.time() * 1000)
        self._persist()

    def fail(self, error: BaseException) -> None:
        now = int(time.time() * 1000)
        if self.current_stage:
            self._update_stage(
                self.current_stage,
                "failed",
                completed_at_epoch_ms=now,
                error={"type": type(error).__name__, "message": str(error)},
            )
        self.state["status"] = "failed"
        self.state["completed_at_epoch_ms"] = now
        self.state["error"] = {"type": type(error).__name__, "message": str(error)}
        self._persist()

    def _require_stage(self, stage: str) -> None:
        if stage not in RUN_STAGES:
            raise ValueError(f"unknown run stage: {stage}")

    def _update_stage(self, stage: str, status: str, **fields: object) -> None:
        stages = child_map(self.state.get("stages"))
        stages[stage] = {**child_map(stages.get(stage)), "status": status, **fields}
        self._persist()

    def _persist(self) -> None:
        self.state["updated_at_epoch_ms"] = int(time.time() * 1000)
        _write_json(self.path, self.state)


class JavaCorpusClient:
    def __init__(self, base_url: str, token: str, min_interval_seconds: float = 0.0):
        headers = {"Accept": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        self.client = httpx.Client(
            base_url=base_url.rstrip("/"),
            headers=headers,
            timeout=httpx.Timeout(30.0, connect=5.0),
        )
        self.min_interval_seconds = max(0.0, min_interval_seconds)
        self.last_search_at = 0.0

    def search_locations(
        self,
        *,
        user_id: int,
        scope_paper_ids: list[str],
        arguments: JsonMap,
    ) -> tuple[JsonMap, float]:
        payload = {
            "request_id": f"qdrant-impact-{uuid.uuid4().hex[:12]}",
            "conversation_id": "qdrant-impact-benchmark",
            "user_id": user_id,
            "scope_paper_ids": scope_paper_ids,
            "paper_ids": _strings(arguments.get("paper_ids")),
            "query_text": str(arguments.get("query_text") or ""),
            "section_query": str(arguments.get("section_query") or ""),
            "element_types": _strings(arguments.get("element_types")),
            "page_from": arguments.get("page_from"),
            "page_to": arguments.get("page_to"),
            "top_k": int(arguments.get("top_k") or 8),
        }
        now = time.perf_counter()
        remaining = self.min_interval_seconds - (now - self.last_search_at)
        if remaining > 0:
            time.sleep(remaining)
        started = time.perf_counter()
        response = self.client.post("/internal/v1/corpus/locations/search", json=payload)
        elapsed_ms = (time.perf_counter() - started) * 1000
        self.last_search_at = time.perf_counter()
        response.raise_for_status()
        body = response.json()
        if not isinstance(body, dict):
            raise RuntimeError("Java Corpus search response must be an object")
        return body, elapsed_ms

    def read_locations(
        self,
        *,
        user_id: int,
        scope_paper_ids: list[str],
        location_refs: list[str],
    ) -> JsonMap:
        response = self.client.post("/internal/v1/corpus/locations/read", json={
            "request_id": f"qdrant-impact-read-{uuid.uuid4().hex[:12]}",
            "conversation_id": "qdrant-impact-benchmark",
            "user_id": user_id,
            "scope_paper_ids": scope_paper_ids,
            "location_refs": location_refs,
        })
        response.raise_for_status()
        body = response.json()
        if not isinstance(body, dict):
            raise RuntimeError("Java Corpus read response must be an object")
        return body


def main() -> int:
    args = _arguments()
    output = _reserve_output(args.out)
    journal = RunJournal(output, sys.argv)
    try:
        result = _run_product_probe(args, output, journal)
    except BaseException as error:
        journal.fail(error)
        raise
    journal.finish()
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


def _run_product_probe(args: argparse.Namespace, output: Path, journal: RunJournal) -> JsonMap:
    journal.start("setup")
    config = _load_yaml(args.config)
    _validate_config(config)
    env = _read_env_file(args.env_file)
    token = (os.getenv("RESEARCH_HARNESS_INTERNAL_TOKEN") or env.get(
        "RESEARCH_HARNESS_INTERNAL_TOKEN", ""
    )).strip()
    if not token:
        raise RuntimeError("RESEARCH_HARNESS_INTERNAL_TOKEN is required")

    golden = load_dataset(str(_resolve(config["golden_manifest"])))
    selected_cases = _selected_cases(golden, config)
    required_by_case = _required_anchors_by_case(selected_cases)
    paper_id_map = {
        str(key): str(value)
        for key, value in child_map(config.get("paper_id_map")).items()
    }
    probes = _load_query_probes(config, selected_cases, paper_id_map)
    remapped_anchors = _remapped_anchors(golden, paper_id_map)

    store = DockerMySqlProductCorpusStore(
        env_path=args.env_file,
        default_container=args.mysql_container,
    )
    selected_paper_id_map = _selected_paper_id_map(selected_cases, paper_id_map)
    product_dataset = store.load_dataset(
        paper_ids=sorted(set(selected_paper_id_map.values())),
        limit=max(1, len(selected_paper_id_map)),
    )
    product_dataset = replace(product_dataset, anchors_by_id=remapped_anchors)
    unmatched_authored_anchor_ids = _verify_product_probe_corpus(
        product_dataset, selected_cases, paper_id_map)
    product_index_contracts = _product_index_contracts(store)
    model_versions = {
        str(row["paper_id"]): str(row.get("model_version") or "")
        for row in product_index_contracts
        if row.get("paper_id")
    }
    selection = {
        "configured_case_ids": _configured_case_ids(config),
        "selected_case_ids": list(selected_cases),
        "selected_paper_id_map": selected_paper_id_map,
        "query_count_by_case": {
            case_id: sum(1 for probe in probes if probe.case_id == case_id)
            for case_id in selected_cases
        },
        "required_anchor_ids_by_case": {
            case_id: sorted(anchor_ids)
            for case_id, anchor_ids in required_by_case.items()
        },
        "unmatched_authored_anchor_ids": unmatched_authored_anchor_ids,
    }
    git = _git_state()
    provenance = _provenance_snapshot(args.config, config, selected_cases)
    contracts = {
        "quality_candidate_budget_policy": QUALITY_CANDIDATE_BUDGET_POLICY,
        "selected_reading_models": [
            row
            for row in product_index_contracts
            if str(row.get("paper_id") or "") in set(selected_paper_id_map.values())
        ],
        "retrieval_index": _retrieval_index_contract_snapshot(store),
    }
    run_context = {
        "schema_version": REPORT_SCHEMA_VERSION,
        "dataset_id": golden.manifest.get("dataset_id"),
        "git": git,
        "provenance": provenance,
        "selection": selection,
        "product_corpus": summarize_product_corpus(product_dataset).to_dict(),
        "contracts": contracts,
    }
    _write_json(output / "run-context.json", run_context)
    journal.complete("setup", artifacts=["run-context.json"])

    java = JavaCorpusClient(
        args.java_base_url,
        token,
        min_interval_seconds=float(config.get("query_min_interval_seconds") or 0),
    )
    journal.start("quality")
    bm25_path = output / "bm25-quality.json"
    qdrant_path = output / "qdrant-quality.json"
    bm25_rows = _run_bm25_quality(
        product_dataset,
        probes,
        checkpoint=lambda rows: _write_json(bm25_path, {"queries": rows}),
    )
    qdrant_rows = _run_java_quality(
        java,
        int(config.get("user_id") or 1),
        probes,
        remapped_anchors,
        checkpoint=lambda rows: _write_json(qdrant_path, {"queries": rows}),
    )
    quality = _quality_report(required_by_case, {
        "python_bm25": bm25_rows,
        "java_qdrant_lexical": qdrant_rows,
    })
    adjudication_path = config.get("adjudication")
    if adjudication_path:
        quality["equivalent_evidence_adjudication"] = _apply_adjudication(
            _load_yaml(_resolve(adjudication_path)),
            required_by_case,
            qdrant_rows,
        )
    _write_json(output / "quality-report.json", quality)
    journal.complete(
        "quality",
        artifacts=["bm25-quality.json", "qdrant-quality.json", "quality-report.json"],
    )

    all_paper_ids = [
        str(row["paper_id"])
        for row in product_index_contracts
        if row.get("paper_id")
    ]
    latency = None
    if args.skip_latency:
        journal.skip("latency", "--skip-latency")
    else:
        journal.start("latency")
        latency = _run_latency_benchmark(
            config=config,
            probes=probes,
            all_paper_ids=all_paper_ids,
            paper_model_versions=model_versions,
            java=java,
            env_file=args.env_file,
            mysql_container=args.mysql_container,
            checkpoint=lambda value: _write_json(output / "latency-progress.json", value),
        )
        _write_json(output / "latency.json", latency)
        journal.complete("latency", artifacts=["latency.json", "latency-progress.json"])

    model_smoke = None
    if not args.run_model_smoke:
        journal.skip("model_smoke", "--run-model-smoke was not requested")
        harness_health: JsonMap = {
            "queried": False,
            "reason": "model_smoke_disabled",
        }
    else:
        journal.start("model_smoke")
        model_smoke = _run_model_smoke(
            config=config,
            golden=golden,
            selected_cases=selected_cases,
            product_dataset=product_dataset,
            paper_id_map=paper_id_map,
            remapped_anchors=remapped_anchors,
            harness_base_url=args.harness_base_url,
            token=token,
            output=output / "model-smoke",
            max_completion_tokens=args.max_completion_tokens,
            env_file=args.env_file,
        )
        _write_json(output / "model-smoke" / "report.json", model_smoke)
        harness_health = child_map(child_map(model_smoke.get("model_contract")).get("harness_health"))
        journal.complete("model_smoke", artifacts=["model-smoke/report.json"])

    journal.start("report")
    infrastructure = _infrastructure_snapshot(
        qdrant_container=args.qdrant_container,
        qdrant_base_url=args.qdrant_base_url,
        qdrant_collection=args.qdrant_collection or _effective_value(
            "QDRANT_COLLECTION", env, DEFAULT_QDRANT_COLLECTION
        ),
    )
    contracts["qdrant"] = child_map(infrastructure.get("qdrant")).get("contract", {})
    report = {
        "schema_version": REPORT_SCHEMA_VERSION,
        "created_at_epoch_ms": int(time.time() * 1000),
        "git": git,
        "provenance": provenance,
        "dataset_id": golden.manifest.get("dataset_id"),
        "selection": selection,
        "product_corpus": summarize_product_corpus(product_dataset).to_dict(),
        "contracts": contracts,
        "harness_health": harness_health,
        "quality": quality,
        "latency": latency,
        "model_smoke": model_smoke,
        "infrastructure": infrastructure,
        "limitations": [
            f"The probe covers {len(selected_paper_id_map)} mapped product Reading Models.",
            f"The probe covers {len(selected_cases)} evidence-bearing Golden cases.",
            "Exact-anchor recall can mark semantically equivalent evidence as a miss; no equivalence credit is added without an explicit adjudication file.",
            "The lexical product path performs no query embedding call.",
            "A single MiniMax pair is a wiring and failure-localization smoke, not a stable model-quality estimate.",
        ],
    }
    _write_json(output / "report.json", report)
    (output / "report.md").write_text(_markdown_report(report), encoding="utf-8")
    journal.complete("report", artifacts=["report.json", "report.md"])
    return {
        "out": str(output),
        "quality": quality["summary"],
        "latency_available": latency is not None,
        "model_smoke_available": model_smoke is not None,
    }


def _load_query_probes(
    config: JsonMap,
    selected_cases: dict[str, JsonMap],
    paper_id_map: dict[str, str],
) -> list[QueryProbe]:
    run_root = _resolve(config["saved_runs"])
    fills = {
        str(case_id): _resolve(path)
        for case_id, path in child_map(config.get("saved_query_fills")).items()
    }
    probes: list[QueryProbe] = []
    for case_id, case in selected_cases.items():
        trace_path = fills.get(case_id, run_root) / case_id / "react_trace.json"
        trace = json.loads(trace_path.read_text(encoding="utf-8"))
        if not isinstance(trace, list):
            raise RuntimeError(f"saved react trace must be a list: {trace_path}")
        required = tuple(
            str(item)
            for item in as_list(child_map(child_map(case.get("expect")).get("evidence")).get("required"))
        )
        query_index = 0
        for event in trace:
            if str(event.get("tool_name") or "") != "find_reading_locations":
                continue
            arguments = copy.deepcopy(child_map(event.get("arguments")))
            arguments["paper_ids"] = [
                paper_id_map.get(str(paper_id), str(paper_id))
                for paper_id in _strings(arguments.get("paper_ids"))
            ]
            probes.append(QueryProbe(case_id, query_index, arguments, required))
            query_index += 1
        if query_index == 0:
            raise RuntimeError(
                "configured Golden case has no saved find_reading_locations query: "
                f"{case_id}: {trace_path}"
            )
    return probes


def _run_bm25_quality(
    dataset: GoldenDataset,
    probes: list[QueryProbe],
    checkpoint: Callable[[list[JsonMap]], None] | None = None,
) -> list[JsonMap]:
    by_case: dict[str, list[QueryProbe]] = {}
    for probe in probes:
        by_case.setdefault(probe.case_id, []).append(probe)
    rows: list[JsonMap] = []
    for case_probes in by_case.values():
        tools = ReadingCorpusTools(dataset)
        tools.authorized_paper_ids.update(
            paper_id
            for probe in case_probes
            for paper_id in _strings(probe.arguments.get("paper_ids"))
        )
        anchor_ids_by_ref = _document_anchor_ids(tools)
        for probe in case_probes:
            started = time.perf_counter()
            result = tools.find_reading_locations(probe.arguments)
            elapsed_ms = (time.perf_counter() - started) * 1000
            requested_top_k = int(probe.arguments.get("top_k") or 8)
            raw_locations = as_list(result.get("locations"))
            candidates = []
            for rank, raw in enumerate(raw_locations[:requested_top_k], start=1):
                item = child_map(raw)
                location_ref = str(item.get("location_ref") or "")
                candidates.append({
                    "rank": rank,
                    "paper_id": item.get("paper_id"),
                    "location_ref": location_ref,
                    "anchor_ids": sorted(anchor_ids_by_ref.get(location_ref, set())),
                })
            rows.append(_query_row(
                probe,
                elapsed_ms,
                candidates,
                result,
                raw_returned_count=len(raw_locations),
            ))
            if checkpoint:
                checkpoint(rows)
    return rows


def _run_java_quality(
    java: JavaCorpusClient,
    user_id: int,
    probes: list[QueryProbe],
    anchors: dict[str, JsonMap],
    checkpoint: Callable[[list[JsonMap]], None] | None = None,
) -> list[JsonMap]:
    rows: list[JsonMap] = []
    for probe in probes:
        scope = _strings(probe.arguments.get("paper_ids"))
        result, elapsed_ms = java.search_locations(
            user_id=user_id,
            scope_paper_ids=scope,
            arguments=probe.arguments,
        )
        raw_locations = [child_map(item) for item in as_list(result.get("locations"))]
        requested_top_k = int(probe.arguments.get("top_k") or 8)
        locations = raw_locations[:requested_top_k]
        refs = [str(item.get("location_ref") or "") for item in locations if item.get("location_ref")]
        read = java.read_locations(
            user_id=user_id,
            scope_paper_ids=scope,
            location_refs=refs,
        ) if refs else {"items": []}
        content_by_ref = {
            str(item.get("location_ref")): item
            for item in (child_map(raw) for raw in as_list(read.get("items")))
            if item.get("location_ref")
        }
        candidates = []
        for rank, item in enumerate(locations, start=1):
            location_ref = str(item.get("location_ref") or "")
            content = content_by_ref.get(location_ref, item)
            candidates.append({
                "rank": rank,
                "paper_id": item.get("paper_id"),
                "location_ref": location_ref,
                "anchor_ids": sorted(_matching_anchor_ids(content, anchors)),
                "dense_score": item.get("dense_score"),
                "sparse_score": item.get("sparse_score"),
                "fused_score": item.get("fused_score"),
            })
        rows.append(_query_row(
            probe,
            elapsed_ms,
            candidates,
            result,
            raw_returned_count=len(raw_locations),
        ))
        if checkpoint:
            checkpoint(rows)
    return rows


def _query_row(
    probe: QueryProbe,
    elapsed_ms: float,
    candidates: list[JsonMap],
    result: JsonMap,
    *,
    raw_returned_count: int | None = None,
) -> JsonMap:
    return {
        "case_id": probe.case_id,
        "query_index": probe.query_index,
        "query_text": str(probe.arguments.get("query_text") or ""),
        "section_query": str(probe.arguments.get("section_query") or ""),
        "paper_ids": _strings(probe.arguments.get("paper_ids")),
        "requested_top_k": int(probe.arguments.get("top_k") or 8),
        "elapsed_ms": round(elapsed_ms, 3),
        "matched_count": result.get("matched_count"),
        "returned_count": len(candidates),
        "raw_returned_count": (
            len(candidates) if raw_returned_count is None else raw_returned_count
        ),
        "candidate_budget_policy": QUALITY_CANDIDATE_BUDGET_POLICY,
        "index_version": result.get("index_version"),
        "candidates": candidates,
    }


def _quality_report(
    required_by_case: dict[str, set[str]],
    methods: dict[str, list[JsonMap]],
) -> JsonMap:
    summaries = {
        method: _summarize_quality(required_by_case, rows)
        for method, rows in methods.items()
    }
    bm25_ranks = _best_anchor_ranks(required_by_case, methods["python_bm25"])
    qdrant_ranks = _best_anchor_ranks(required_by_case, methods["java_qdrant_lexical"])
    improved = []
    regressed = []
    unchanged = []
    for key in sorted(bm25_ranks):
        before = bm25_ranks[key]
        after = qdrant_ranks[key]
        row = {"case_id": key[0], "anchor_id": key[1], "bm25_rank": before, "qdrant_rank": after}
        if _rank_value(after) < _rank_value(before):
            improved.append(row)
        elif _rank_value(after) > _rank_value(before):
            regressed.append(row)
        else:
            unchanged.append(row)
    return {
        "conditions": {
            "candidate_budget_policy": QUALITY_CANDIDATE_BUDGET_POLICY,
            "description": (
                "Both methods are scored only within each saved query's requested top_k; "
                "expanded adapter candidates are not scored."
            ),
        },
        "summary": summaries,
        "transitions": {
            "improved": improved,
            "regressed": regressed,
            "unchanged": unchanged,
        },
    }


def _summarize_quality(required_by_case: dict[str, set[str]], rows: list[JsonMap]) -> JsonMap:
    ranks = _best_anchor_ranks(required_by_case, rows)
    finite = [rank for rank in ranks.values() if rank is not None]
    required_count = len(ranks)
    hit_count = len(finite)
    full_cases = 0
    for case_id, required in required_by_case.items():
        if required and all(ranks[(case_id, anchor_id)] is not None for anchor_id in required):
            full_cases += 1
    latencies = [float(row.get("elapsed_ms") or 0) for row in rows]
    return {
        "case_count": len(required_by_case),
        "required_anchor_count": required_count,
        "candidate_hits": hit_count,
        "candidate_recall": hit_count / required_count if required_count else 0.0,
        "full_case_coverage": full_cases,
        "mrr": sum(1.0 / rank for rank in finite) / required_count if required_count else 0.0,
        "median_best_rank": statistics.median(finite) if finite else None,
        "query_latency_ms": _distribution(latencies),
    }


def _best_anchor_ranks(
    required_by_case: dict[str, set[str]],
    rows: list[JsonMap],
) -> dict[tuple[str, str], int | None]:
    result = {
        (case_id, anchor_id): None
        for case_id, required in required_by_case.items()
        for anchor_id in required
    }
    for row in rows:
        case_id = str(row.get("case_id") or "")
        for candidate in as_list(row.get("candidates")):
            item = child_map(candidate)
            rank = int(item.get("rank") or 0)
            for anchor_id in _strings(item.get("anchor_ids")):
                key = (case_id, anchor_id)
                if key not in result or rank <= 0:
                    continue
                current = result[key]
                if current is None or rank < current:
                    result[key] = rank
    return result


def _rank_value(value: int | None) -> float:
    return float(value) if value is not None else float("inf")


def _apply_adjudication(
    adjudication: JsonMap,
    required_by_case: dict[str, set[str]],
    qdrant_rows: list[JsonMap],
) -> JsonMap:
    if str(adjudication.get("schema_version") or "") != ADJUDICATION_SCHEMA_VERSION:
        raise RuntimeError(
            "unsupported product-probe adjudication schema: "
            f"{adjudication.get('schema_version')!r}"
        )
    source_value = str(adjudication.get("source") or "").strip()
    declared_hash = str(adjudication.get("source_sha256") or "").strip().lower()
    if not source_value or len(declared_hash) != 64 or any(
        char not in "0123456789abcdef" for char in declared_hash
    ):
        raise RuntimeError("adjudication must declare source and a SHA-256 source_sha256")
    source_path = _resolve(source_value)
    observed_hash = _sha256_path(source_path)
    if observed_hash != declared_hash:
        raise RuntimeError(
            "adjudication source hash mismatch: "
            f"expected={declared_hash} observed={observed_hash}: {source_path}"
        )
    source_payload = json.loads(source_path.read_text(encoding="utf-8"))
    if not isinstance(source_payload, dict) or not isinstance(source_payload.get("queries"), list):
        raise RuntimeError(f"adjudication source must contain a queries list: {source_path}")
    source_rows = [child_map(item) for item in source_payload["queries"]]
    required = {
        (case_id, anchor_id)
        for case_id, anchor_ids in required_by_case.items()
        for anchor_id in anchor_ids
    }
    exact_ranks = _best_anchor_ranks(required_by_case, qdrant_rows)
    source_exact_ranks = _best_anchor_ranks(required_by_case, source_rows)
    exact_misses = {key for key, rank in exact_ranks.items() if rank is None}
    source_exact_misses = {key for key, rank in source_exact_ranks.items() if rank is None}
    if exact_misses != source_exact_misses:
        raise RuntimeError(
            "current exact-miss set differs from the frozen adjudication source; "
            "a new human review is required"
        )
    accepted = {key for key, rank in exact_ranks.items() if rank is not None}
    raw_decisions = adjudication.get("decisions")
    if not isinstance(raw_decisions, list):
        raise RuntimeError("adjudication decisions must be a list")
    verified: list[JsonMap] = []
    reviewed_keys: set[tuple[str, str]] = set()
    for raw in raw_decisions:
        if not isinstance(raw, dict):
            raise RuntimeError("each adjudication decision must be an object")
        decision = child_map(raw)
        status = str(decision.get("status") or "").strip()
        if status not in {"equivalent", "not_equivalent"}:
            raise RuntimeError(f"unsupported adjudication status: {status!r}")
        if str(decision.get("label_source") or "").strip() != "human":
            raise RuntimeError("semantic-equivalence decisions must set label_source: human")
        key = (str(decision.get("case_id") or ""), str(decision.get("anchor_id") or ""))
        if key not in required:
            raise RuntimeError(f"adjudication references a non-required anchor: {key}")
        if key in reviewed_keys:
            raise RuntimeError(f"duplicate adjudication decision: {key}")
        if exact_ranks[key] is not None or source_exact_ranks[key] is not None:
            raise RuntimeError(f"adjudication decision targets an exact hit: {key}")
        reviewed_keys.add(key)
        query_index = _required_int(decision.get("query_index"), "query_index", minimum=0)
        declared_rank = _required_int(decision.get("rank"), "rank", minimum=1)
        location_ref = str(decision.get("location_ref") or "")
        if not location_ref:
            raise RuntimeError(f"adjudication location_ref is required: {key}")
        reason = str(decision.get("reason") or "").strip()
        if not reason:
            raise RuntimeError(f"adjudication reason is required: {key}")
        source_candidate = _adjudicated_candidate(
            source_rows, key, query_index, location_ref, "frozen source"
        )
        current_candidate = _adjudicated_candidate(
            qdrant_rows, key, query_index, location_ref, "current run"
        )
        source_rank = int(source_candidate.get("rank") or 0)
        current_rank = int(current_candidate.get("rank") or 0)
        if source_rank != declared_rank or current_rank != declared_rank:
            raise RuntimeError(
                f"adjudication rank mismatch for {key}: declared={declared_rank} "
                f"source={source_rank} current={current_rank}"
            )
        if status == "equivalent":
            accepted.add(key)
        verified.append({
            **decision,
            "observed_rank": current_rank,
        })
    if reviewed_keys != exact_misses:
        missing = sorted(exact_misses - reviewed_keys)
        extra = sorted(reviewed_keys - exact_misses)
        raise RuntimeError(
            "adjudication must review every exact miss exactly once: "
            f"missing={missing} extra={extra}"
        )
    full_cases = 0
    for case_id in required_by_case:
        case_required = {key for key in required if key[0] == case_id}
        if case_required and case_required <= accepted:
            full_cases += 1
    exact_hit_count = sum(1 for rank in exact_ranks.values() if rank is not None)
    return {
        "schema_version": ADJUDICATION_SCHEMA_VERSION,
        "source": source_value,
        "source_sha256": observed_hash,
        "label_source": "human",
        "required_anchor_count": len(required),
        "exact_anchor_hits": exact_hit_count,
        "reviewed_exact_misses": len(reviewed_keys),
        "equivalent_evidence_additions": len(accepted) - exact_hit_count,
        "not_equivalent_count": sum(
            1 for decision in verified if decision.get("status") == "not_equivalent"
        ),
        "adjudicated_hits": len(accepted),
        "adjudicated_recall": len(accepted) / len(required) if required else 0.0,
        "full_case_coverage": full_cases,
        "decisions": verified,
    }


def _adjudicated_candidate(
    rows: list[JsonMap],
    key: tuple[str, str],
    query_index: int,
    location_ref: str,
    label: str,
) -> JsonMap:
    row = next((
        item for item in rows
        if str(item.get("case_id") or "") == key[0]
        and int(item.get("query_index") or 0) == query_index
    ), None)
    if row is None:
        raise RuntimeError(f"adjudication query is missing from {label}: {key}: query={query_index}")
    candidate = next((
        child_map(item) for item in as_list(row.get("candidates"))
        if str(child_map(item).get("location_ref") or "") == location_ref
    ), None)
    if candidate is None:
        raise RuntimeError(
            f"adjudicated candidate is missing from {label}: {key}: {location_ref}"
        )
    return candidate


def _run_latency_benchmark(
    *,
    config: JsonMap,
    probes: list[QueryProbe],
    all_paper_ids: list[str],
    paper_model_versions: dict[str, str],
    java: JavaCorpusClient,
    env_file: Path,
    mysql_container: str,
    checkpoint: Callable[[JsonMap], None] | None = None,
) -> JsonMap:
    repeats = max(1, int(config.get("latency_repeats") or 3))
    warmups = max(0, int(config.get("latency_warmups") or 1))
    top_k = max(1, int(config.get("latency_top_k") or 8))
    scope_sizes = [int(value) for value in as_list(config.get("scope_sizes"))]
    probe_by_case_and_query = {
        (probe.case_id, str(probe.arguments.get("query_text") or "")): probe
        for probe in probes
    }
    cases = _configured_latency_cases(config)
    bm25_rows: list[JsonMap] = []
    qdrant_rows: list[JsonMap] = []
    context = multiprocessing.get_context("spawn")

    for configured in cases:
        case_id = str(configured.get("case_id") or "")
        query_text = str(configured.get("latency_query") or "")
        probe = probe_by_case_and_query.get((case_id, query_text))
        if probe is None:
            raise RuntimeError(f"latency query is not present in saved trace: {case_id}: {query_text}")
        target_paper_id = _strings(probe.arguments.get("paper_ids"))[0]
        for requested_size in scope_sizes:
            scope = _scope_with_target(all_paper_ids, target_paper_id, requested_size)
            scope_model_versions = _scope_model_versions(scope, paper_model_versions)
            worker_payload = {
                "env_file": str(env_file),
                "mysql_container": mysql_container,
                "scope_paper_ids": scope,
                "target_paper_id": target_paper_id,
                "query_text": query_text,
                "section_query": str(probe.arguments.get("section_query") or ""),
                "element_types": _strings(probe.arguments.get("element_types")),
                "top_k": top_k,
                "warmups": warmups,
                "repeats": repeats,
            }
            queue = context.Queue()
            process = context.Process(target=_bm25_latency_worker, args=(worker_payload, queue))
            process.start()
            process.join(180)
            if process.is_alive():
                process.terminate()
                process.join(5)
                raise RuntimeError(f"BM25 latency worker timed out: {case_id}: scope={requested_size}")
            if process.exitcode != 0:
                raise RuntimeError(f"BM25 latency worker failed: {case_id}: exit={process.exitcode}")
            worker_result = queue.get(timeout=5)
            if worker_result.get("error"):
                raise RuntimeError(
                    f"BM25 latency worker failed: {worker_result.get('error')}: "
                    f"{worker_result.get('message')}"
                )
            observed_versions = child_map(worker_result.get("scope_model_versions"))
            if observed_versions != scope_model_versions:
                raise RuntimeError(
                    f"BM25 latency worker loaded unexpected model versions: {case_id}: "
                    f"expected={scope_model_versions} observed={observed_versions}"
                )
            worker_result.update({"case_id": case_id, "scope_size": len(scope)})
            worker_result.update({
                "scope_paper_ids": scope,
                "scope_model_versions": scope_model_versions,
                "target_paper_id": target_paper_id,
            })
            bm25_rows.append(worker_result)
            if checkpoint:
                checkpoint(_latency_report(scope_sizes, top_k, warmups, repeats, bm25_rows, qdrant_rows))

            for mode, requested_papers in (
                ("narrow", [target_paper_id]),
                ("broad", scope),
            ):
                arguments = {
                    "paper_ids": requested_papers,
                    "query_text": query_text,
                    "section_query": str(probe.arguments.get("section_query") or ""),
                    "element_types": _strings(probe.arguments.get("element_types")),
                    "top_k": top_k,
                }
                for _ in range(warmups):
                    java.search_locations(
                        user_id=int(config.get("user_id") or 1),
                        scope_paper_ids=scope,
                        arguments=arguments,
                    )
                timings = []
                returned_counts = []
                for _ in range(repeats):
                    result, elapsed_ms = java.search_locations(
                        user_id=int(config.get("user_id") or 1),
                        scope_paper_ids=scope,
                        arguments=arguments,
                    )
                    timings.append(elapsed_ms)
                    returned_counts.append(int(result.get("returned_count") or 0))
                qdrant_rows.append({
                    "case_id": case_id,
                    "scope_size": len(scope),
                    "scope_paper_ids": scope,
                    "scope_model_versions": scope_model_versions,
                    "target_paper_id": target_paper_id,
                    "mode": mode,
                    "search_ms": [round(value, 3) for value in timings],
                    "search_distribution_ms": _distribution(timings),
                    "returned_counts": returned_counts,
                })
                if checkpoint:
                    checkpoint(_latency_report(
                        scope_sizes, top_k, warmups, repeats, bm25_rows, qdrant_rows
                    ))

    return _latency_report(scope_sizes, top_k, warmups, repeats, bm25_rows, qdrant_rows)


def _configured_latency_cases(config: JsonMap) -> list[JsonMap]:
    cases = [
        child_map(item)
        for item in as_list(config.get("cases"))
        if str(child_map(item).get("latency_query") or "").strip()
    ]
    if not cases:
        raise RuntimeError("at least one configured case must declare latency_query")
    return cases


def _latency_report(
    scope_sizes: list[int],
    top_k: int,
    warmups: int,
    repeats: int,
    bm25_rows: list[JsonMap],
    qdrant_rows: list[JsonMap],
) -> JsonMap:
    _validate_latency_scope_metadata("python_bm25", bm25_rows)
    _validate_latency_scope_metadata("java_qdrant_lexical", qdrant_rows)
    return {
        "conditions": {
            "scope_sizes": scope_sizes,
            "top_k": top_k,
            "warmups": warmups,
            "repeats": repeats,
            "modes": {
                "narrow": "Load the full locked scope, then search one disclosed target paper.",
                "broad": "Search all papers in the locked scope with the same query.",
            },
        },
        "python_bm25": bm25_rows,
        "java_qdrant_lexical": qdrant_rows,
        "aggregate": _latency_aggregate(bm25_rows, qdrant_rows),
    }


def _validate_latency_scope_metadata(method: str, rows: list[JsonMap]) -> None:
    for row in rows:
        paper_ids = _strings(row.get("scope_paper_ids"))
        versions = {
            str(key): str(value or "")
            for key, value in child_map(row.get("scope_model_versions")).items()
        }
        if not paper_ids or int(row.get("scope_size") or 0) != len(paper_ids):
            raise RuntimeError(f"{method} latency row has invalid scope_paper_ids")
        if set(versions) != set(paper_ids) or any(not versions[paper_id] for paper_id in paper_ids):
            raise RuntimeError(f"{method} latency row has incomplete scope model versions")


def _scope_model_versions(
    scope_paper_ids: list[str],
    paper_model_versions: dict[str, str],
) -> dict[str, str]:
    missing = [
        paper_id
        for paper_id in scope_paper_ids
        if not paper_model_versions.get(paper_id)
    ]
    if missing:
        raise RuntimeError("latency scope is missing model versions: " + ", ".join(missing))
    return {
        paper_id: paper_model_versions[paper_id]
        for paper_id in scope_paper_ids
    }


def _bm25_latency_worker(payload: JsonMap, queue) -> None:
    try:
        before_rss = _rss_bytes()
        store = DockerMySqlProductCorpusStore(
            env_path=payload["env_file"],
            default_container=str(payload["mysql_container"]),
        )
        started = time.perf_counter()
        dataset = store.load_dataset(
            paper_ids=_strings(payload.get("scope_paper_ids")),
            limit=max(1, len(_strings(payload.get("scope_paper_ids")))),
        )
        load_ms = (time.perf_counter() - started) * 1000
        after_load_rss = _rss_bytes()
        started = time.perf_counter()
        tools = ReadingCorpusTools(dataset)
        tools.authorized_paper_ids.update(_strings(payload.get("scope_paper_ids")))
        tools_init_ms = (time.perf_counter() - started) * 1000
        after_tools_rss = _rss_bytes()
        base_arguments = {
            "query_text": str(payload.get("query_text") or ""),
            "section_query": str(payload.get("section_query") or ""),
            "element_types": _strings(payload.get("element_types")),
            "top_k": int(payload.get("top_k") or 8),
        }
        modes = {
            "narrow": [str(payload["target_paper_id"])],
            "broad": _strings(payload.get("scope_paper_ids")),
        }
        mode_results = {}
        for mode, paper_ids in modes.items():
            arguments = {**base_arguments, "paper_ids": paper_ids}
            for _ in range(int(payload.get("warmups") or 0)):
                tools.find_reading_locations(arguments)
            timings = []
            returned_counts = []
            for _ in range(int(payload.get("repeats") or 1)):
                started = time.perf_counter()
                result = tools.find_reading_locations(arguments)
                timings.append((time.perf_counter() - started) * 1000)
                returned_counts.append(int(result.get("returned_count") or 0))
            mode_results[mode] = {
                "search_ms": [round(value, 3) for value in timings],
                "search_distribution_ms": _distribution(timings),
                "returned_counts": returned_counts,
            }
        summary = summarize_product_corpus(dataset)
        searchable_chars = sum(len(document.text) for document in tools.documents)
        queue.put({
            "load_ms": round(load_ms, 3),
            "tools_init_ms": round(tools_init_ms, 3),
            "rss_before_bytes": before_rss,
            "rss_after_load_bytes": after_load_rss,
            "rss_after_tools_bytes": after_tools_rss,
            "rss_growth_bytes": max(0, after_tools_rss - before_rss),
            "paper_count": summary.paper_count,
            "scope_paper_ids": _strings(payload.get("scope_paper_ids")),
            "scope_model_versions": {
                paper_id: str(dataset.reading_models_by_paper_id[paper_id].get("model_version") or "")
                for paper_id in _strings(payload.get("scope_paper_ids"))
            },
            "reading_element_count": summary.reading_element_count,
            "reading_document_count": len(tools.documents),
            "searchable_chars": searchable_chars,
            "modes": mode_results,
        })
    except Exception as error:
        queue.put({"error": type(error).__name__, "message": str(error)})
        raise
    finally:
        gc.collect()


def _latency_aggregate(bm25_rows: list[JsonMap], qdrant_rows: list[JsonMap]) -> JsonMap:
    by_method: dict[str, list[float]] = {
        "python_bm25_narrow_search": [],
        "python_bm25_broad_search": [],
        "java_qdrant_narrow_search": [],
        "java_qdrant_broad_search": [],
    }
    setup = []
    memory = []
    for row in bm25_rows:
        setup.append(float(row.get("load_ms") or 0) + float(row.get("tools_init_ms") or 0))
        memory.append(float(row.get("rss_growth_bytes") or 0))
        modes = child_map(row.get("modes"))
        by_method["python_bm25_narrow_search"].extend(
            float(value) for value in as_list(child_map(modes.get("narrow")).get("search_ms"))
        )
        by_method["python_bm25_broad_search"].extend(
            float(value) for value in as_list(child_map(modes.get("broad")).get("search_ms"))
        )
    for row in qdrant_rows:
        key = f"java_qdrant_{row.get('mode')}_search"
        by_method[key].extend(float(value) for value in as_list(row.get("search_ms")))
    return {
        "bm25_turn_setup_ms": _distribution(setup),
        "bm25_worker_rss_growth_bytes": _distribution(memory),
        **{key: _distribution(values) for key, values in by_method.items()},
    }


def _run_model_smoke(
    *,
    config: JsonMap,
    golden: GoldenDataset,
    selected_cases: dict[str, JsonMap],
    product_dataset: GoldenDataset,
    paper_id_map: dict[str, str],
    remapped_anchors: dict[str, JsonMap],
    harness_base_url: str,
    token: str,
    output: Path,
    max_completion_tokens: int,
    env_file: Path,
) -> JsonMap:
    output.mkdir(parents=True, exist_ok=True)
    provider = EnvProviderConfigStore(env_path=env_file).load_active_provider("llm")
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    qdrant_client = httpx.Client(
        base_url=harness_base_url.rstrip("/"),
        headers=headers,
        timeout=httpx.Timeout(300.0, connect=5.0),
    )
    harness_health = _harness_health(qdrant_client, harness_base_url)
    _write_json(output / "harness-health.json", harness_health)
    model_contract = _validate_model_contract(
        provider.public_diagnostics(),
        harness_health,
        max_completion_tokens,
    )
    _write_json(output / "model-contract.json", model_contract)
    bm25_harness = LiveResearchChatHarness(
        build_harness_runtime(provider, max_completion_tokens=max_completion_tokens)
    )
    reverse_paper_ids = {product_id: golden_id for golden_id, product_id in paper_id_map.items()}
    scorer = BehaviorScorer()
    rows = []

    for case_id, case in selected_cases.items():
        golden_paper_ids = _case_paper_ids(case)
        product_paper_ids = [paper_id_map[paper_id] for paper_id in golden_paper_ids]
        scoped_dataset = _dataset_for_papers(product_dataset, product_paper_ids)
        question = case_question(case)

        state = ConversationState.from_dict({
            "conversation_id": f"qdrant-impact-bm25-{case_id}",
            "scope_paper_ids": product_paper_ids,
        })
        started = time.perf_counter()
        bm25_run, _ = bm25_harness.run_turn(scoped_dataset, state, question)
        bm25_elapsed_ms = (time.perf_counter() - started) * 1000
        bm25_scored_run = _prepare_run_for_golden_scoring(
            bm25_run,
            reverse_paper_ids,
            remapped_anchors,
        )
        bm25_score = scorer.score_case(golden, case, bm25_scored_run).to_dict()
        case_dir = output / case_id
        case_dir.mkdir(parents=True, exist_ok=True)
        _write_json(case_dir / "bm25-run.json", bm25_run)
        _write_json(case_dir / "bm25-score.json", bm25_score)

        request = {
            "request_id": f"qdrant-impact-qdrant-{case_id}-{uuid.uuid4().hex[:8]}",
            "conversation_id": f"qdrant-impact-qdrant-{case_id}",
            "user_id": int(config.get("user_id") or 1),
            "user_message": question,
            "scope": {"paper_ids": product_paper_ids},
            "options": {"include_trace": True},
        }
        started = time.perf_counter()
        response = qdrant_client.post("/v1/research/turn", json=request)
        qdrant_elapsed_ms = (time.perf_counter() - started) * 1000
        response.raise_for_status()
        qdrant_response = response.json()
        _write_json(case_dir / "qdrant-response.json", qdrant_response)
        qdrant_run = {
            "case_id": case_id,
            "status": qdrant_response.get("status"),
            "research_answer": qdrant_response.get("answer", {}),
            "evidence_ledger": child_map(qdrant_response.get("trace")).get("evidence_ledger", {}),
            "citation_validation": child_map(qdrant_response.get("trace")).get("citation_validation", {}),
            "diagnostics": {
                **child_map(qdrant_response.get("usage")),
                "finish_reason": child_map(qdrant_response.get("trace")).get("finish_reason"),
            },
        }
        qdrant_scored_run = _prepare_run_for_golden_scoring(
            qdrant_run,
            reverse_paper_ids,
            remapped_anchors,
        )
        qdrant_score = scorer.score_case(golden, case, qdrant_scored_run).to_dict()
        _write_json(case_dir / "qdrant-score.json", qdrant_score)
        row = {
            "case_id": case_id,
            "bm25": {
                "hard_pass": bm25_score["hard_pass"],
                "dimensions": bm25_score["dimensions"],
                "elapsed_ms": round(bm25_elapsed_ms, 3),
                "usage": child_map(bm25_run.get("diagnostics")),
            },
            "qdrant": {
                "hard_pass": qdrant_score["hard_pass"],
                "dimensions": qdrant_score["dimensions"],
                "elapsed_ms": round(qdrant_elapsed_ms, 3),
                "usage": child_map(qdrant_response.get("usage")),
            },
        }
        rows.append(row)
        _write_json(output / "progress.json", _model_smoke_report(model_contract, rows))

    return _model_smoke_report(model_contract, rows)


def _model_smoke_report(model_contract: JsonMap, rows: list[JsonMap]) -> JsonMap:
    return {
        "model_contract": model_contract,
        "sample_count_per_method": 1,
        "case_count": len(rows),
        "bm25_hard_pass": sum(1 for row in rows if child_map(row.get("bm25")).get("hard_pass")),
        "qdrant_hard_pass": sum(1 for row in rows if child_map(row.get("qdrant")).get("hard_pass")),
        "cases": rows,
    }


def _harness_health(client: httpx.Client, harness_base_url: str) -> JsonMap:
    response = client.get("/health")
    response.raise_for_status()
    payload = response.json()
    if not isinstance(payload, dict):
        raise RuntimeError("Harness /health response must be an object")
    if str(payload.get("status") or "") != "ok":
        raise RuntimeError(f"Harness /health is not ok: {payload.get('status')!r}")
    return {
        "queried": True,
        "url": harness_base_url.rstrip("/") + "/health",
        **payload,
    }


def _validate_model_contract(
    local_provider: JsonMap,
    harness_health: JsonMap,
    max_completion_tokens: int,
) -> JsonMap:
    remote_provider = child_map(harness_health.get("provider"))
    mismatches = {
        field: {
            "local_bm25": local_provider.get(field),
            "remote_qdrant": remote_provider.get(field),
        }
        for field in ("provider", "model", "api_style")
        if str(local_provider.get(field) or "") != str(remote_provider.get(field) or "")
    }
    if mismatches:
        raise RuntimeError(f"BM25/Qdrant model-provider parity check failed: {mismatches}")
    remote_max = harness_health.get("max_completion_tokens")
    max_tokens_match: bool | None = None
    if remote_max is not None:
        if isinstance(remote_max, bool):
            raise RuntimeError("Harness max_completion_tokens must be an integer")
        try:
            observed_max = int(remote_max)
        except (TypeError, ValueError) as error:
            raise RuntimeError("Harness max_completion_tokens must be an integer") from error
        max_tokens_match = observed_max == max_completion_tokens
        if not max_tokens_match:
            raise RuntimeError(
                "BM25/Qdrant max_completion_tokens parity check failed: "
                f"local={max_completion_tokens} remote={observed_max}"
            )
    return {
        "parity_verified": True,
        "required_provider_fields": ["provider", "model", "api_style"],
        "local_bm25_provider": local_provider,
        "remote_qdrant_provider": remote_provider,
        "local_max_completion_tokens": max_completion_tokens,
        "remote_max_completion_tokens": remote_max,
        "max_completion_tokens_match": max_tokens_match,
        "harness_health": harness_health,
    }


def _prepare_run_for_golden_scoring(
    run: JsonMap,
    reverse_paper_ids: dict[str, str],
    anchors: dict[str, JsonMap],
) -> JsonMap:
    prepared = copy.deepcopy(run)
    ledger = child_map(prepared.get("evidence_ledger"))
    for raw in as_list(ledger.get("items")):
        item = child_map(raw)
        product_paper_id = str(item.get("paper_id") or "")
        matched = sorted(_matching_anchor_ids(item, anchors))
        item["matched_anchor_ids"] = matched
        item["matched_anchor_id"] = matched[0] if matched else None
        item["paper_id"] = reverse_paper_ids.get(product_paper_id, product_paper_id)
    return prepared


def _dataset_for_papers(dataset: GoldenDataset, paper_ids: list[str]) -> GoldenDataset:
    selected = set(paper_ids)
    return replace(
        dataset,
        paper_records_by_id={
            key: value for key, value in dataset.paper_records_by_id.items() if key in selected
        },
        reading_models_by_paper_id={
            key: value for key, value in dataset.reading_models_by_paper_id.items() if key in selected
        },
        anchors_by_id={
            key: value
            for key, value in dataset.anchors_by_id.items()
            if str(value.get("paper_id") or "") in selected
        },
    )


def _infrastructure_snapshot(
    *,
    qdrant_container: str,
    qdrant_base_url: str,
    qdrant_collection: str,
) -> JsonMap:
    snapshot: JsonMap = {
        "qdrant": _container_snapshot(qdrant_container, "/qdrant/storage"),
    }
    qdrant_key = _container_environment_value(qdrant_container, "QDRANT__SERVICE__API_KEY")
    qdrant_headers = {"api-key": qdrant_key} if qdrant_key else {}
    try:
        response = httpx.get(
            qdrant_base_url.rstrip("/") + "/collections/" + qdrant_collection,
            headers=qdrant_headers,
            timeout=10,
        )
        response.raise_for_status()
        result = child_map(response.json().get("result"))
        snapshot["qdrant"].update({
            "points_count": result.get("points_count"),
            "indexed_vectors_count": result.get("indexed_vectors_count"),
            "segments_count": result.get("segments_count"),
            "collection_status": result.get("status"),
            "api_key_configured": bool(qdrant_key),
            "contract": _qdrant_contract(qdrant_base_url, qdrant_collection, result),
        })
    except Exception as error:
        snapshot["qdrant"]["inspection_error"] = f"{type(error).__name__}: {error}"

    return snapshot


def _qdrant_contract(base_url: str, collection: str, result: JsonMap) -> JsonMap:
    config = child_map(result.get("config"))
    params = child_map(config.get("params"))
    vectors = child_map(params.get("vectors"))
    sparse_vectors = child_map(params.get("sparse_vectors"))
    sparse = child_map(sparse_vectors.get("lexical_bm25_v1"))
    return {
        "inspection_base_url": base_url.rstrip("/"),
        "collection": collection,
        "dense_vector_names": sorted(vectors),
        "sparse_vector_name": "lexical_bm25_v1" if sparse else None,
        "sparse_modifier": sparse.get("modifier"),
        "sparse_index": sparse.get("index"),
        "payload_schema_fields": sorted(child_map(result.get("payload_schema"))),
    }


def _container_snapshot(container: str, data_path: str) -> JsonMap:
    result: JsonMap = {"container": container}
    try:
        stats = subprocess.run(
            ["docker", "stats", "--no-stream", "--format", "{{json .}}", container],
            check=True,
            text=True,
            capture_output=True,
        )
        payload = json.loads(stats.stdout.strip())
        result.update({
            "memory_usage": payload.get("MemUsage"),
            "memory_percent": payload.get("MemPerc"),
            "cpu_percent_snapshot": payload.get("CPUPerc"),
        })
    except Exception as error:
        result["stats_error"] = f"{type(error).__name__}: {error}"
    try:
        apparent = subprocess.run(
            ["docker", "exec", container, "du", "-sb", data_path],
            check=True,
            text=True,
            capture_output=True,
        )
        allocated = subprocess.run(
            ["docker", "exec", container, "du", "-sk", data_path],
            check=True,
            text=True,
            capture_output=True,
        )
        result["data_path_apparent_bytes"] = int(apparent.stdout.split()[0])
        result["data_path_allocated_bytes"] = int(allocated.stdout.split()[0]) * 1024
    except Exception as error:
        result["disk_error"] = f"{type(error).__name__}: {error}"
    return result


def _selected_cases(golden: GoldenDataset, config: JsonMap) -> dict[str, JsonMap]:
    case_ids = _configured_case_ids(config)
    available = {
        str(case.get("id")): case
        for case in golden.cases
    }
    missing = [case_id for case_id in case_ids if case_id not in available]
    if missing:
        raise RuntimeError("configured Golden cases are missing: " + ", ".join(missing))
    return {case_id: available[case_id] for case_id in case_ids}


def _configured_case_ids(config: JsonMap) -> list[str]:
    raw_cases = config.get("cases")
    if not isinstance(raw_cases, list) or not raw_cases:
        raise RuntimeError("product-probe config cases must be a non-empty list")
    case_ids: list[str] = []
    seen: set[str] = set()
    for raw in raw_cases:
        if not isinstance(raw, dict):
            raise RuntimeError("each configured product-probe case must be an object")
        case_id = str(raw.get("case_id") or "").strip()
        if not case_id:
            raise RuntimeError("each configured product-probe case must have case_id")
        if case_id in seen:
            raise RuntimeError(f"duplicate configured product-probe case: {case_id}")
        seen.add(case_id)
        case_ids.append(case_id)
    return case_ids


def _required_anchors_by_case(selected_cases: dict[str, JsonMap]) -> dict[str, set[str]]:
    return {
        case_id: {
            str(anchor_id)
            for anchor_id in as_list(
                child_map(child_map(case.get("expect")).get("evidence")).get("required")
            )
            if str(anchor_id)
        }
        for case_id, case in selected_cases.items()
    }


def _selected_paper_id_map(
    selected_cases: dict[str, JsonMap],
    paper_id_map: dict[str, str],
) -> dict[str, str]:
    selected_papers = {
        paper_id
        for case in selected_cases.values()
        for paper_id in _case_paper_ids(case)
    }
    return {
        paper_id: paper_id_map[paper_id]
        for paper_id in sorted(selected_papers)
        if paper_id in paper_id_map
    }


def _validate_config(config: JsonMap) -> None:
    if str(config.get("schema_version") or "") != "harness-qdrant-product-probes/v2":
        raise RuntimeError(f"unsupported product-probe config schema: {config.get('schema_version')!r}")
    for field in ("golden_manifest", "saved_runs"):
        if not str(config.get(field) or "").strip():
            raise RuntimeError(f"product-probe config field is required: {field}")
    policy = str(config.get("quality_candidate_budget_policy") or "").strip()
    if policy != QUALITY_CANDIDATE_BUDGET_POLICY:
        raise RuntimeError(
            "product-probe quality_candidate_budget_policy must be "
            f"{QUALITY_CANDIDATE_BUDGET_POLICY!r}"
        )
    _configured_case_ids(config)


def _remapped_anchors(
    golden: GoldenDataset,
    paper_id_map: dict[str, str],
) -> dict[str, JsonMap]:
    result = {}
    for anchor_id, anchor in golden.anchors_by_id.items():
        golden_paper_id = str(anchor.get("paper_id") or "")
        if golden_paper_id not in paper_id_map:
            continue
        remapped = copy.deepcopy(anchor)
        remapped["paper_id"] = paper_id_map[golden_paper_id]
        result[anchor_id] = remapped
    return result


def _verify_product_probe_corpus(
    dataset: GoldenDataset,
    selected_cases: dict[str, JsonMap],
    paper_id_map: dict[str, str],
) -> list[str]:
    required_golden_papers = {
        paper_id
        for case in selected_cases.values()
        for paper_id in _case_paper_ids(case)
    }
    missing_mapping = sorted(required_golden_papers - set(paper_id_map))
    if missing_mapping:
        raise RuntimeError("product paper mapping is missing: " + ", ".join(missing_mapping))
    missing_product = sorted(set(paper_id_map.values()) - set(dataset.paper_records_by_id))
    if missing_product:
        raise RuntimeError("mapped product papers are unavailable: " + ", ".join(missing_product))
    tools = ReadingCorpusTools(dataset)
    found = {
        anchor_id
        for document in tools.documents
        for anchor_id in document.matched_anchor_ids
    }
    required_anchors = {
        str(anchor_id)
        for case in selected_cases.values()
        for anchor_id in as_list(child_map(child_map(case.get("expect")).get("evidence")).get("required"))
    }
    missing_anchors = sorted(required_anchors - found)
    return missing_anchors


def _case_paper_ids(case: JsonMap) -> list[str]:
    return _strings(child_map(child_map(case.get("expect")).get("papers")).get("required"))


def _document_anchor_ids(tools: ReadingCorpusTools) -> dict[str, set[str]]:
    result: dict[str, set[str]] = {}
    for document in tools.documents:
        result.setdefault(document.location_ref, set()).update(document.matched_anchor_ids)
    return result


def _matching_anchor_ids(item: JsonMap, anchors: dict[str, JsonMap]) -> set[str]:
    paper_id = str(item.get("paper_id") or "")
    page = item.get("page") if item.get("page") is not None else item.get("pageNumber")
    text = str(
        item.get("span_text")
        or item.get("searchableText")
        or item.get("bodyText")
        or item.get("preview")
        or ""
    )
    normalized = normalize_text(text)
    matched = set()
    for anchor_id, anchor in anchors.items():
        if str(anchor.get("paper_id") or "") != paper_id:
            continue
        anchor_page = child_map(anchor.get("element")).get("page")
        quote = normalize_text(str(child_map(anchor.get("selector")).get("exact_text") or ""))
        if quote and page_matches(anchor_page, page) and contains_normalized_phrase(normalized, quote):
            matched.add(anchor_id)
    return matched


def _product_index_contracts(store: DockerMySqlProductCorpusStore) -> list[JsonMap]:
    rows = store._query_json_lines("""
select json_object(
  'paper_id', m.paper_id,
  'model_version', m.model_version,
  'retrieval_index_status', m.retrieval_index_status,
  'retrieval_index_contract', m.retrieval_index_contract,
  'retrieval_indexed_location_count', m.retrieval_indexed_location_count,
  'retrieval_indexed_at', cast(m.retrieval_indexed_at as char)
) as row_json
from paper_reading_models m
where m.is_current = 1
  and m.model_status = 'READING_MODEL_READY'
  and m.retrieval_index_status = 'READY'
order by m.paper_id
""".strip())
    return rows


def _retrieval_index_contract_snapshot(store: DockerMySqlProductCorpusStore) -> JsonMap:
    try:
        rows = store._query_json_lines("""
select json_object(
  'control_name', control_name,
  'status', full_rebuild_status,
  'active_index_contract', active_index_contract,
  'lexical_average_document_length', lexical_average_document_length
) as row_json
from paper_retrieval_control
where control_name = 'QDRANT_FULL_REBUILD'
""".strip())
    except Exception as error:
        return {"inspection_error": f"{type(error).__name__}: {error}"}
    if len(rows) != 1:
        return {
            "inspection_error": f"expected one active retrieval index contract, found {len(rows)}",
            "control_row_count": len(rows),
        }
    return rows[0]


def _scope_with_target(all_paper_ids: list[str], target: str, requested_size: int) -> list[str]:
    size = max(1, min(requested_size, len(all_paper_ids)))
    result = [target]
    result.extend(paper_id for paper_id in all_paper_ids if paper_id != target)
    return result[:size]


def _distribution(values: list[float]) -> JsonMap:
    if not values:
        return {"count": 0, "min": None, "p50": None, "p95": None, "max": None, "mean": None}
    ordered = sorted(values)
    return {
        "count": len(ordered),
        "min": round(ordered[0], 3),
        "p50": round(statistics.median(ordered), 3),
        "p95": round(_percentile(ordered, 0.95), 3),
        "max": round(ordered[-1], 3),
        "mean": round(statistics.fmean(ordered), 3),
    }


def _percentile(ordered: list[float], fraction: float) -> float:
    if len(ordered) == 1:
        return ordered[0]
    position = (len(ordered) - 1) * fraction
    lower = int(position)
    upper = min(lower + 1, len(ordered) - 1)
    weight = position - lower
    return ordered[lower] * (1 - weight) + ordered[upper] * weight


def _rss_bytes() -> int:
    status = Path("/proc/self/status").read_text(encoding="utf-8")
    for line in status.splitlines():
        if line.startswith("VmRSS:"):
            return int(line.split()[1]) * 1024
    return 0


def _container_environment_value(container: str, name: str) -> str:
    try:
        result = subprocess.run(
            [
                "docker",
                "inspect",
                "--format",
                "{{range .Config.Env}}{{println .}}{{end}}",
                container,
            ],
            check=True,
            text=True,
            capture_output=True,
        )
    except subprocess.CalledProcessError:
        return ""
    prefix = name + "="
    for line in result.stdout.splitlines():
        if line.startswith(prefix):
            return line[len(prefix):]
    return ""


def _markdown_report(report: JsonMap) -> str:
    quality = child_map(report.get("quality"))
    summary = child_map(quality.get("summary"))
    bm25 = child_map(summary.get("python_bm25"))
    qdrant = child_map(summary.get("java_qdrant_lexical"))
    adjudicated = child_map(quality.get("equivalent_evidence_adjudication"))
    latency = child_map(report.get("latency"))
    aggregate = child_map(latency.get("aggregate"))
    model = child_map(report.get("model_smoke"))
    lines = [
        "# Product BM25 and Java/Qdrant lexical paired probe",
        "",
        f"- Git revision: `{child_map(report.get('git')).get('revision')}`",
        f"- Git dirty: `{child_map(report.get('git')).get('dirty')}`",
        f"- Dataset: `{report.get('dataset_id')}`",
        "- Scope: three mapped product Reading Models corresponding to papers in the expanded Golden corpus.",
        f"- Candidate budget: `{child_map(quality.get('conditions')).get('candidate_budget_policy')}`.",
        "",
        "## Retrieval quality",
        "",
        "| Method | Required anchors | Hits | Recall | Full cases | MRR |",
        "| --- | ---: | ---: | ---: | ---: | ---: |",
        _quality_markdown_row("Python BM25", bm25),
        _quality_markdown_row("Java/Qdrant lexical BM25", qdrant),
        "",
    ]
    if adjudicated:
        lines.extend([
            "Exact-anchor matching can treat an equivalent passage as a miss. The frozen human-labelled review found:",
            "",
            f"- Adjudicated Qdrant evidence recall: {adjudicated.get('adjudicated_hits')}/{adjudicated.get('required_anchor_count')} "
            f"({float(adjudicated.get('adjudicated_recall') or 0):.1%})",
            f"- Fully covered cases after review: {adjudicated.get('full_case_coverage')}/{qdrant.get('case_count')}",
            "",
        ])
    if aggregate:
        lines.extend([
            "## Latency",
            "",
            "The BM25 setup row includes loading the locked corpus from MySQL and building in-memory documents.",
            "Qdrant search rows include lexical query encoding, Qdrant retrieval, and MySQL hydration.",
            "",
            "```json",
            json.dumps(aggregate, ensure_ascii=False, indent=2),
            "```",
            "",
        ])
    if model:
        lines.extend([
            "## MiniMax focus smoke",
            "",
            f"- BM25 hard pass: {model.get('bm25_hard_pass')}/{model.get('case_count')}",
            f"- Qdrant hard pass: {model.get('qdrant_hard_pass')}/{model.get('case_count')}",
            "- One sample per method and case; this is not a stable model-quality estimate.",
            "",
        ])
    lines.extend([
        "## Limits",
        "",
        *[f"- {item}" for item in as_list(report.get("limitations"))],
        "",
    ])
    return "\n".join(lines)


def _quality_markdown_row(name: str, value: JsonMap) -> str:
    return (
        f"| {name} | {value.get('required_anchor_count')} | {value.get('candidate_hits')} | "
        f"{float(value.get('candidate_recall') or 0):.1%} | {value.get('full_case_coverage')} | "
        f"{float(value.get('mrr') or 0):.3f} |"
    )


def _write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    temporary.replace(path)


def _load_yaml(path: Path) -> JsonMap:
    value = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise RuntimeError(f"YAML root must be an object: {path}")
    return value


def _resolve(value: object) -> Path:
    path = Path(str(value))
    return path if path.is_absolute() else REPO_ROOT / path


def _reserve_output(configured: Path | None) -> Path:
    if configured is None:
        day = time.strftime("%Y-%m-%d")
        stamp = time.strftime("%H%M%S")
        output = (
            REPO_ROOT
            / "research/golden-data/local-runs"
            / f"{day}-qdrant-impact"
            / f"product-probe-{stamp}-{uuid.uuid4().hex[:8]}"
        )
    else:
        output = configured.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    try:
        output.mkdir(exist_ok=False)
    except FileExistsError as error:
        raise RuntimeError(f"refusing to overwrite existing output directory: {output}") from error
    return output.resolve()


def _provenance_snapshot(
    config_path: Path,
    config: JsonMap,
    selected_cases: dict[str, JsonMap],
) -> JsonMap:
    files: dict[str, Path] = {
        "probe_script": Path(__file__).resolve(),
        "config": config_path.resolve(),
        "golden_manifest": _resolve(config["golden_manifest"]),
    }
    adjudication = str(config.get("adjudication") or "").strip()
    if adjudication:
        files["adjudication"] = _resolve(adjudication)
    golden_cases = str(config.get("golden_cases") or "").strip()
    if golden_cases:
        files["golden_cases"] = _resolve(golden_cases)
    saved_runs = _resolve(config["saved_runs"])
    fills = {
        str(case_id): _resolve(path)
        for case_id, path in child_map(config.get("saved_query_fills")).items()
    }
    files.update({
        f"saved_trace:{case_id}": fills.get(case_id, saved_runs) / case_id / "react_trace.json"
        for case_id in selected_cases
    })
    return {
        name: _file_provenance(path)
        for name, path in files.items()
    }


def _file_provenance(path: Path) -> JsonMap:
    resolved = path.resolve()
    return {
        "path": _display_path(resolved),
        "sha256": _sha256_path(resolved),
        "bytes": resolved.stat().st_size,
    }


def _display_path(path: Path) -> str:
    try:
        return str(path.relative_to(REPO_ROOT))
    except ValueError:
        return str(path)


def _sha256_path(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def _git_state() -> JsonMap:
    revision = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=REPO_ROOT,
        check=True,
        text=True,
        capture_output=True,
    ).stdout.strip()
    status = subprocess.run(
        ["git", "status", "--porcelain=v1", "--untracked-files=all"],
        cwd=REPO_ROOT,
        check=True,
        text=True,
        capture_output=True,
    ).stdout.splitlines()
    return {
        "revision": revision,
        "dirty": bool(status),
        "status_porcelain": status,
    }


def _effective_value(name: str, file_env: dict[str, str], default: str = "") -> str:
    return str(os.getenv(name) or file_env.get(name) or default).strip()


def _required_int(value: object, field: str, *, minimum: int) -> int:
    if isinstance(value, bool):
        raise RuntimeError(f"adjudication {field} must be an integer >= {minimum}")
    try:
        parsed = int(value)
    except (TypeError, ValueError) as error:
        raise RuntimeError(f"adjudication {field} must be an integer >= {minimum}") from error
    if parsed < minimum:
        raise RuntimeError(f"adjudication {field} must be an integer >= {minimum}")
    return parsed


def _optional_int(value: object) -> int | None:
    try:
        return int(str(value))
    except (TypeError, ValueError):
        return None


def _strings(value: object) -> list[str]:
    result = []
    seen = set()
    for item in as_list(value):
        text = str(item or "").strip()
        if text and text not in seen:
            seen.add(text)
            result.append(text)
    return result


def _arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Compare the former in-memory BM25 path with live Java/Qdrant lexical BM25."
    )
    parser.add_argument(
        "--config",
        type=Path,
        default=REPO_ROOT / "research/golden-data/qdrant-product-probes.yaml",
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=None,
    )
    parser.add_argument("--env-file", type=Path, default=REPO_ROOT / ".env")
    parser.add_argument("--mysql-container", default="pai_smart_mysql")
    parser.add_argument("--qdrant-container", default="paperloom-qdrant")
    parser.add_argument("--qdrant-base-url", default="http://127.0.0.1:6333")
    parser.add_argument("--qdrant-collection", default="")
    parser.add_argument("--java-base-url", default="http://127.0.0.1:8081")
    parser.add_argument("--harness-base-url", default="http://127.0.0.1:8091")
    parser.add_argument("--skip-latency", action="store_true")
    parser.add_argument("--run-model-smoke", action="store_true")
    parser.add_argument("--max-completion-tokens", type=int, default=3000)
    return parser.parse_args()


if __name__ == "__main__":
    raise SystemExit(main())
