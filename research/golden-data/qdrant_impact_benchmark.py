#!/usr/bin/env python3
"""Paired BM25/Qdrant retrieval benchmark over saved MiniMax Golden queries.

The benchmark deliberately leaves Golden Data untouched. It replays the model's
committed ``find_reading_locations`` calls, indexes the same Reading Models into
a dedicated Qdrant collection, and scores every retriever against the authored
evidence anchors.
"""

from __future__ import annotations

import argparse
import email.utils
import hashlib
import http.client
import json
import math
import os
import re
import statistics
import subprocess
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
import uuid
from collections import defaultdict
from dataclasses import dataclass, field, replace
from datetime import datetime
from pathlib import Path
from typing import Any, Callable, Iterable, Mapping, Sequence

import yaml

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT))

from harness_py.core.models import GoldenDataset, JsonMap, as_list, child_map  # noqa: E402
from harness_py.corpus.pages import contains_normalized_phrase, page_matches  # noqa: E402
from harness_py.corpus.tools import ReadingCorpusTools  # noqa: E402
from harness_py.evaluation.dataset import load_dataset  # noqa: E402
from harness_py.evaluation.paths import reading_model_path, resolve_authoring_path  # noqa: E402
from harness_py.transport.provider_config import (  # noqa: E402
    DockerMySqlProviderConfigStore,
    ProviderConfig,
)


SCHEMA_VERSION = "harness-qdrant-impact-report/v1"
CONFIG_SCHEMA_VERSION = "harness-qdrant-impact-benchmark/v1"
REQUEST_ATTEMPT_SCHEMA_VERSION = "harness-qdrant-request-attempt/v1"
PROVENANCE_SCHEMA_VERSION = "harness-qdrant-impact-provenance/v1"
DATASET_FINGERPRINT_ALGORITHM = "sha256-canonical-file-inventory/v1"
SAVED_QUERY_FINGERPRINT_ALGORITHM = "sha256-canonical-file-inventory/v1"
LATENCY_MEASUREMENT_SCOPE = "evaluation_depth"
LATENCY_INTERPRETATION = (
    "Offline service latency is measured while retrieving enough candidates for the "
    "configured evaluation depth. It is not native top_k workload latency and must not "
    "be presented as product request latency."
)
MODES = (
    "bm25",
    "qdrant_sparse",
    "qdrant_dense",
    "qdrant_hybrid_rrf",
    "qdrant_production_hybrid_coverage",
)
MAX_EMBEDDING_TEXT_CHARS = 12_000
TOKEN_PATTERN = re.compile(r"\w+", re.UNICODE)


@dataclass(frozen=True)
class RetryPolicy:
    max_attempts: int = 3
    initial_backoff_seconds: float = 0.5
    max_backoff_seconds: float = 4.0

    def __post_init__(self) -> None:
        if self.max_attempts < 1:
            raise ValueError("retry max_attempts must be positive")
        if self.initial_backoff_seconds < 0:
            raise ValueError("retry initial_backoff_seconds cannot be negative")
        if self.max_backoff_seconds < self.initial_backoff_seconds:
            raise ValueError(
                "retry max_backoff_seconds cannot be smaller than initial_backoff_seconds"
            )

    def delay_seconds(
        self,
        attempt: int,
        retry_after_seconds: float | None = None,
    ) -> float:
        exponential = self.initial_backoff_seconds * (2 ** max(0, attempt - 1))
        requested = max(0.0, retry_after_seconds or 0.0)
        return min(self.max_backoff_seconds, max(exponential, requested))


class RequestAttemptJournal:
    """Append-only evidence for every external request attempt."""

    def __init__(self, path: Path, *, secrets: Sequence[str] = ()):
        self.path = path
        self.secrets = tuple(secret for secret in secrets if secret)
        self._sequence = 0
        self._attempt_count = 0
        self._request_ids: set[str] = set()
        self._outcomes: dict[str, int] = defaultdict(int)
        self._services: dict[str, int] = defaultdict(int)
        self._total_backoff_ms = 0.0
        self._pacing_wait_count = 0
        self._total_pacing_ms = 0.0

    def record(self, event: Mapping[str, Any]) -> None:
        self._sequence += 1
        event_type = str(event.get("event_type") or "request_attempt")
        if event_type == "request_attempt":
            self._attempt_count += 1
            request_id = str(event.get("request_id") or "")
            if request_id:
                self._request_ids.add(request_id)
            outcome = str(event.get("outcome") or "unknown")
            service = str(event.get("service") or "unknown")
            self._outcomes[outcome] += 1
            self._services[service] += 1
            self._total_backoff_ms += float(event.get("backoff_ms") or 0.0)
        elif event_type == "pacing_wait":
            self._pacing_wait_count += 1
            self._total_pacing_ms += float(event.get("wait_ms") or 0.0)
        row = {
            "schema_version": REQUEST_ATTEMPT_SCHEMA_VERSION,
            "sequence": self._sequence,
            "recorded_at": datetime.now().astimezone().isoformat(),
            **dict(event),
        }
        encoded = json.dumps(
            _redact_json(row, self.secrets),
            ensure_ascii=True,
            sort_keys=True,
        )
        with self.path.open("a", encoding="utf-8") as handle:
            handle.write(encoded + "\n")
            handle.flush()

    def summary(self) -> JsonMap:
        return {
            "event_count": self._sequence,
            "attempt_count": self._attempt_count,
            "logical_request_count": len(self._request_ids),
            "outcomes": dict(sorted(self._outcomes.items())),
            "attempts_by_service": dict(sorted(self._services.items())),
            "total_backoff_ms": _round_ms(self._total_backoff_ms),
            "pacing_wait_count": self._pacing_wait_count,
            "total_pacing_ms": _round_ms(self._total_pacing_ms),
            "raw_artifact": str(self.path),
        }

    def record_pacing_wait(
        self,
        *,
        wait_seconds: float,
        minimum_interval_seconds: float,
    ) -> None:
        self.record({
            "event_type": "pacing_wait",
            "service": "embedding",
            "operation": "embedding query pacing",
            "wait_ms": _round_ms(wait_seconds * 1000.0),
            "minimum_interval_ms": _round_ms(
                minimum_interval_seconds * 1000.0
            ),
        })


@dataclass(frozen=True)
class BenchmarkConfig:
    path: Path
    manifest: Path
    saved_runs: Path
    saved_query_fills: Mapping[str, Path]
    embedding_env_path: Path
    embedding_mysql_container_env: str
    embedding_default_mysql_container: str
    embedding_batch_size: int
    embedding_timeout_seconds: float
    embedding_query_min_interval_seconds: float
    embedding_retry_policy: RetryPolicy
    qdrant_base_url_env: str
    qdrant_default_base_url: str
    qdrant_api_key_env: str
    qdrant_container_env: str
    qdrant_default_container: str
    qdrant_collection: str
    qdrant_timeout_seconds: float
    qdrant_retry_policy: RetryPolicy
    qdrant_upsert_batch_size: int
    cutoffs: tuple[int, ...]
    default_native_top_k: int
    max_top_k: int
    required_anchor_ids_by_case: Mapping[str, tuple[str, ...]]
    rrf_k: int
    candidate_limit_min: int
    candidate_limit_multiplier: int
    candidate_limit_max: int
    per_paper_limit_min: int
    per_paper_limit_multiplier: int
    per_paper_limit_max: int
    per_paper_search_max_papers: int
    element_type_hint_boost: float
    artifacts_root: Path
    run_prefix: str

    @classmethod
    def load(cls, path: str | Path) -> "BenchmarkConfig":
        config_path = _repo_path(path)
        raw = yaml.safe_load(config_path.read_text(encoding="utf-8")) or {}
        if raw.get("schema_version") != CONFIG_SCHEMA_VERSION:
            raise ValueError(
                f"unsupported benchmark config schema: {raw.get('schema_version')!r}"
            )
        dataset = child_map(raw.get("dataset"))
        embedding = child_map(raw.get("embedding"))
        embedding_retry = child_map(embedding.get("retry"))
        qdrant = child_map(raw.get("qdrant"))
        qdrant_retry = child_map(qdrant.get("retry"))
        retrieval = child_map(raw.get("retrieval"))
        artifacts = child_map(raw.get("artifacts"))
        cutoffs = tuple(sorted({int(value) for value in as_list(retrieval.get("cutoffs"))}))
        if not cutoffs or cutoffs[0] <= 0:
            raise ValueError("retrieval.cutoffs must contain positive integers")
        max_top_k = int(retrieval.get("max_top_k") or 20)
        if cutoffs[-1] > max_top_k:
            raise ValueError("retrieval.cutoffs cannot exceed retrieval.max_top_k")
        provider_source = str(embedding.get("provider_source") or "")
        if provider_source != "docker_mysql":
            raise ValueError("embedding.provider_source must be docker_mysql")
        return cls(
            path=config_path,
            manifest=_repo_path(_required(dataset, "manifest")),
            saved_runs=_repo_path(_required(dataset, "saved_minimax_runs")),
            saved_query_fills={
                str(case_id): _repo_path(run_root)
                for case_id, run_root in child_map(
                    dataset.get("saved_query_fills")
                ).items()
            },
            embedding_env_path=_repo_path(embedding.get("env_path") or ".env"),
            embedding_mysql_container_env=str(
                embedding.get("mysql_container_env") or "HARNESS_MYSQL_CONTAINER"
            ),
            embedding_default_mysql_container=str(
                embedding.get("default_mysql_container") or "paperloom-mysql"
            ),
            embedding_batch_size=max(1, int(embedding.get("batch_size") or 10)),
            embedding_timeout_seconds=max(
                1.0, float(embedding.get("request_timeout_seconds") or 90)
            ),
            embedding_query_min_interval_seconds=max(
                0.0,
                float(
                    embedding.get("query_min_interval_seconds")
                    if embedding.get("query_min_interval_seconds") is not None
                    else 1.05
                ),
            ),
            embedding_retry_policy=_retry_policy(
                embedding_retry,
                default_max_attempts=3,
                default_initial_backoff_seconds=1.0,
                default_max_backoff_seconds=8.0,
            ),
            qdrant_base_url_env=str(qdrant.get("base_url_env") or "QDRANT_BASE_URL"),
            qdrant_default_base_url=str(
                qdrant.get("default_base_url") or "http://127.0.0.1:6333"
            ).rstrip("/"),
            qdrant_api_key_env=str(qdrant.get("api_key_env") or "QDRANT_API_KEY"),
            qdrant_container_env=str(
                qdrant.get("container_env") or "PAPERLOOM_QDRANT_CONTAINER"
            ),
            qdrant_default_container=str(
                qdrant.get("default_container") or "paperloom-qdrant"
            ),
            qdrant_collection=str(_required(qdrant, "collection")),
            qdrant_timeout_seconds=max(
                1.0, float(qdrant.get("request_timeout_seconds") or 30)
            ),
            qdrant_retry_policy=_retry_policy(
                qdrant_retry,
                default_max_attempts=4,
                default_initial_backoff_seconds=0.25,
                default_max_backoff_seconds=2.0,
            ),
            qdrant_upsert_batch_size=max(
                1, int(qdrant.get("upsert_batch_size") or 64)
            ),
            cutoffs=cutoffs,
            default_native_top_k=max(
                1, int(retrieval.get("default_native_top_k") or 8)
            ),
            max_top_k=max_top_k,
            required_anchor_ids_by_case={
                str(case_id): tuple(
                    str(anchor_id)
                    for anchor_id in as_list(anchor_ids)
                    if anchor_id
                )
                for case_id, anchor_ids in child_map(
                    retrieval.get("audit_anchor_ids_by_case")
                ).items()
            },
            rrf_k=max(1, int(retrieval.get("rrf_k") or 60)),
            candidate_limit_min=max(
                1, int(retrieval.get("candidate_limit_min") or 40)
            ),
            candidate_limit_multiplier=max(
                1, int(retrieval.get("candidate_limit_multiplier") or 4)
            ),
            candidate_limit_max=max(
                1, int(retrieval.get("candidate_limit_max") or 100)
            ),
            per_paper_limit_min=max(
                1, int(retrieval.get("per_paper_limit_min") or 8)
            ),
            per_paper_limit_multiplier=max(
                1, int(retrieval.get("per_paper_limit_multiplier") or 2)
            ),
            per_paper_limit_max=max(
                1, int(retrieval.get("per_paper_limit_max") or 40)
            ),
            per_paper_search_max_papers=max(
                1, int(retrieval.get("per_paper_search_max_papers") or 8)
            ),
            element_type_hint_boost=float(
                retrieval.get("element_type_hint_boost") or 0.001
            ),
            artifacts_root=_repo_path(artifacts.get("root") or "research/golden-data/local-runs"),
            run_prefix=str(artifacts.get("run_prefix") or "qdrant-impact"),
        )

    def public_snapshot(self) -> JsonMap:
        return {
            "schema_version": CONFIG_SCHEMA_VERSION,
            "config_path": str(self.path),
            "manifest": str(self.manifest),
            "saved_minimax_runs": str(self.saved_runs),
            "saved_query_fills": {
                case_id: str(path)
                for case_id, path in self.saved_query_fills.items()
            },
            "embedding": {
                "provider_source": "docker_mysql",
                "mysql_container_env": self.embedding_mysql_container_env,
                "default_mysql_container": self.embedding_default_mysql_container,
                "batch_size": self.embedding_batch_size,
                "request_timeout_seconds": self.embedding_timeout_seconds,
                "query_min_interval_seconds": (
                    self.embedding_query_min_interval_seconds
                ),
                "retry": _retry_policy_snapshot(self.embedding_retry_policy),
            },
            "qdrant": {
                "base_url_env": self.qdrant_base_url_env,
                "collection": self.qdrant_collection,
                "request_timeout_seconds": self.qdrant_timeout_seconds,
                "retry": _retry_policy_snapshot(self.qdrant_retry_policy),
                "upsert_batch_size": self.qdrant_upsert_batch_size,
            },
            "retrieval": {
                "cutoffs": list(self.cutoffs),
                "default_native_top_k": self.default_native_top_k,
                "max_top_k": self.max_top_k,
                "audit_anchor_ids_by_case": {
                    case_id: list(anchor_ids)
                    for case_id, anchor_ids in self.required_anchor_ids_by_case.items()
                },
                "rrf_k": self.rrf_k,
                "candidate_limit_min": self.candidate_limit_min,
                "candidate_limit_multiplier": self.candidate_limit_multiplier,
                "candidate_limit_max": self.candidate_limit_max,
                "per_paper_limit_min": self.per_paper_limit_min,
                "per_paper_limit_multiplier": self.per_paper_limit_multiplier,
                "per_paper_limit_max": self.per_paper_limit_max,
                "per_paper_search_max_papers": self.per_paper_search_max_papers,
                "element_type_hint_boost": self.element_type_hint_boost,
            },
            "artifacts_root": str(self.artifacts_root),
        }


@dataclass(frozen=True)
class IndexedDocument:
    paper_id: str
    model_version: str
    location_ref: str
    page_number: int | None
    page_end_number: int | None
    location_type: str
    section: str
    searchable_text: str
    canonical_char_count: int
    element_types: tuple[str, ...]
    reading_element_ids: tuple[str, ...]
    anchor_ids: tuple[str, ...]

    def payload(self, generation: str) -> JsonMap:
        payload: JsonMap = {
            "benchmark_generation": generation,
            "paper_id": self.paper_id,
            "model_version": self.model_version,
            "location_ref": self.location_ref,
            "element_type": self.element_types[0] if self.element_types else "page",
            "element_types": list(self.element_types),
            "location_type": self.location_type,
            "reading_element_ids": list(self.reading_element_ids),
            "text_hash": hashlib.sha256(
                self.searchable_text.encode("utf-8")
            ).hexdigest(),
        }
        if self.page_number is not None:
            payload["page_number"] = self.page_number
        if self.page_end_number is not None:
            payload["page_end_number"] = self.page_end_number
        if self.section:
            payload["section_path"] = self.section
        return payload


@dataclass(frozen=True)
class QueryTask:
    query_id: str
    case_id: str
    call_index: int
    source_run_root: str
    arguments: JsonMap
    retrieval_query: str
    paper_ids: tuple[str, ...]
    element_types: tuple[str, ...]
    page_from: int | None
    page_to: int | None
    native_top_k: int
    relevant_anchor_ids: tuple[str, ...]
    case_required_anchor_ids: tuple[str, ...]


@dataclass(frozen=True)
class SearchHit:
    location_ref: str
    score: float
    payload: JsonMap


@dataclass
class FusedHit:
    location_ref: str
    payload: JsonMap
    dense_score: float = 0.0
    sparse_score: float = 0.0
    fused_score: float = 0.0


@dataclass
class _IndexGroup:
    location: JsonMap
    texts: list[str] = field(default_factory=list)
    element_types: list[str] = field(default_factory=list)
    reading_element_ids: list[str] = field(default_factory=list)

    def add(self, element: JsonMap, text: str) -> None:
        _append_unique(self.texts, text.strip())
        element_type = str(element.get("elementType") or "").strip().lower()
        if element_type:
            _append_unique(self.element_types, element_type)
        element_id = str(element.get("readingElementId") or "").strip()
        if element_id:
            _append_unique(self.reading_element_ids, element_id)


def build_index_documents(dataset: GoldenDataset) -> list[IndexedDocument]:
    anchors_by_paper: dict[str, list[JsonMap]] = defaultdict(list)
    for anchor in dataset.anchors_by_id.values():
        anchors_by_paper[str(anchor.get("paper_id") or "")].append(anchor)

    documents: list[IndexedDocument] = []
    for paper_id, model in dataset.reading_models_by_paper_id.items():
        locations = [child_map(item) for item in as_list(model.get("locations"))]
        locations_by_ref = {
            str(location.get("locationRef")): location
            for location in locations
            if location.get("locationRef")
        }
        page_locations = {
            int(location["pageNumber"]): location
            for location in locations
            if str(location.get("locationType") or "").upper() == "PAGE"
            and _optional_int(location.get("pageNumber")) is not None
        }
        elements = [child_map(item) for item in as_list(model.get("reading_elements"))]
        elements_by_id = {
            str(element.get("readingElementId")): element
            for element in elements
            if element.get("readingElementId")
        }
        groups: dict[str, _IndexGroup] = {}
        for element in elements:
            text = _first_nonblank(
                element.get("searchableText"),
                element.get("bodyText"),
                element.get("captionText"),
            )
            if not text:
                continue
            location = _routed_location(
                element, locations_by_ref, page_locations, elements_by_id
            )
            if not location or not location.get("locationRef"):
                continue
            location_ref = str(location["locationRef"])
            groups.setdefault(location_ref, _IndexGroup(location)).add(element, text)

        model_version = str(model.get("model_version") or model.get("modelVersion") or "")
        paper_documents: list[IndexedDocument] = []
        for location_ref, group in groups.items():
            canonical_location_text = "\n".join(group.texts)
            searchable_text = canonical_location_text[:MAX_EMBEDDING_TEXT_CHARS]
            page_number = _optional_int(group.location.get("pageNumber"))
            anchor_ids = tuple(
                str(anchor.get("anchor_id"))
                for anchor in anchors_by_paper.get(paper_id, [])
                if _anchor_matches(anchor, canonical_location_text, page_number)
            )
            paper_documents.append(IndexedDocument(
                paper_id=paper_id,
                model_version=model_version,
                location_ref=location_ref,
                page_number=page_number,
                page_end_number=_optional_int(group.location.get("pageEndNumber")),
                location_type=str(group.location.get("locationType") or "UNKNOWN").upper(),
                section=str(group.location.get("sectionTitle") or ""),
                searchable_text=searchable_text,
                canonical_char_count=len(canonical_location_text),
                element_types=tuple(group.element_types),
                reading_element_ids=tuple(group.reading_element_ids),
                anchor_ids=anchor_ids,
            ))
        indexed_paper_anchors = {
            anchor_id
            for document in paper_documents
            for anchor_id in document.anchor_ids
        }
        for anchor in anchors_by_paper.get(paper_id, []):
            anchor_id = str(anchor.get("anchor_id") or "")
            if not anchor_id or anchor_id in indexed_paper_anchors:
                continue
            matching_indices = [
                index
                for index, document in enumerate(paper_documents)
                if _anchor_text_matches(anchor, document.searchable_text)
            ]
            for index in matching_indices:
                document = paper_documents[index]
                paper_documents[index] = replace(
                    document,
                    anchor_ids=tuple(dict.fromkeys(document.anchor_ids + (anchor_id,))),
                )
            if matching_indices:
                indexed_paper_anchors.add(anchor_id)
        documents.extend(paper_documents)
    documents.sort(key=lambda item: (
        item.paper_id,
        item.page_number if item.page_number is not None else 10**9,
        item.location_ref,
    ))
    return documents


def load_saved_query_tasks(
    dataset: GoldenDataset,
    saved_runs: Path,
    saved_query_fills: Mapping[str, Path],
    default_native_top_k: int,
    max_top_k: int,
    required_anchor_ids_by_case: Mapping[str, tuple[str, ...]],
) -> list[QueryTask]:
    tasks: list[QueryTask] = []
    for case in dataset.cases:
        case_id = str(case.get("id") or "")
        required = tuple(required_anchor_ids_by_case.get(case_id, ()))
        source_run_root = saved_runs
        trace = _load_case_trace(source_run_root, case_id)
        if not _find_location_events(trace) and case_id in saved_query_fills:
            source_run_root = saved_query_fills[case_id]
            trace = _load_case_trace(source_run_root, case_id)
        call_index = 0
        for event in trace:
            event_map = child_map(event)
            if event_map.get("tool_name") != "find_reading_locations":
                continue
            call_index += 1
            arguments = dict(child_map(event_map.get("arguments")))
            paper_ids = tuple(
                str(value) for value in as_list(arguments.get("paper_ids")) if value
            )
            query_text = str(arguments.get("query_text") or "").strip()
            section_query = str(arguments.get("section_query") or "").strip()
            retrieval_query = " ".join(
                value for value in (query_text, section_query) if value
            )
            native_top_k = max(
                1,
                min(
                    int(arguments.get("top_k") or default_native_top_k),
                    max_top_k,
                ),
            )
            relevant = tuple(
                anchor_id
                for anchor_id in required
                if str(
                    child_map(dataset.anchors_by_id.get(anchor_id)).get("paper_id") or ""
                ) in paper_ids
            )
            tasks.append(QueryTask(
                query_id=f"{case_id}:find:{call_index:02d}",
                case_id=case_id,
                call_index=call_index,
                source_run_root=str(source_run_root),
                arguments=arguments,
                retrieval_query=retrieval_query,
                paper_ids=paper_ids,
                element_types=tuple(
                    str(value).strip().lower()
                    for value in as_list(arguments.get("element_types"))
                    if str(value).strip()
                ),
                page_from=_optional_int(arguments.get("page_from")),
                page_to=_optional_int(arguments.get("page_to")),
                native_top_k=native_top_k,
                relevant_anchor_ids=relevant,
                case_required_anchor_ids=required,
            ))
    return tasks


def preflight_summary(
    dataset: GoldenDataset,
    documents: Sequence[IndexedDocument],
    tasks: Sequence[QueryTask],
) -> JsonMap:
    indexed_anchors = {
        anchor_id for document in documents for anchor_id in document.anchor_ids
    }
    all_anchors = set(dataset.anchors_by_id)
    cases_with_saved_queries = {task.case_id for task in tasks}
    required_by_case = {
        task.case_id: set(task.case_required_anchor_ids)
        for task in tasks
        if task.case_required_anchor_ids
    }
    evidence_cases = [
        case
        for case in dataset.cases
        if str(case.get("id") or "") in required_by_case
        and str(case.get("id") or "") in cases_with_saved_queries
    ]
    source_counts: dict[str, int] = defaultdict(int)
    native_budget_counts: dict[str, int] = defaultdict(int)
    for task in tasks:
        source_counts[task.source_run_root] += 1
        native_budget_counts[str(task.native_top_k)] += 1
    return {
        "dataset_id": dataset.manifest.get("dataset_id"),
        "paper_count": len(dataset.paper_records_by_id),
        "case_count": len(dataset.cases),
        "anchor_count": len(all_anchors),
        "indexed_point_count": len(documents),
        "indexed_anchor_count": len(indexed_anchors),
        "missing_indexed_anchor_ids": sorted(all_anchors - indexed_anchors),
        "saved_query_count": len(tasks),
        "scored_query_count": sum(bool(task.relevant_anchor_ids) for task in tasks),
        "evidence_case_count": len(evidence_cases),
        "required_anchor_obligation_count": sum(
            len(required_by_case[str(case.get("id") or "")])
            for case in evidence_cases
        ),
        "saved_query_count_by_source": dict(sorted(source_counts.items())),
        "native_top_k_distribution": dict(
            sorted(native_budget_counts.items(), key=lambda item: int(item[0]))
        ),
        "native_top_k_clamped_query_count": sum(
            int(task.arguments.get("top_k") or task.native_top_k) > task.native_top_k
            for task in tasks
        ),
        "cases_with_saved_queries": len(cases_with_saved_queries),
        "cases_without_saved_queries": sorted(
            str(case.get("id") or "")
            for case in dataset.cases
            if str(case.get("id") or "") not in cases_with_saved_queries
        ),
    }


def build_benchmark_provenance(
    config: BenchmarkConfig,
    tasks: Sequence[QueryTask],
    *,
    repo_root: str | Path = REPO_ROOT,
) -> JsonMap:
    """Freeze the code, configuration, dataset, and saved-query inputs for a run."""

    root = Path(repo_root).resolve()
    script_path = Path(__file__).resolve()
    trace_paths = sorted({
        trace_path
        for task in tasks
        if (
            trace_path := _case_trace_path(
                Path(task.source_run_root),
                task.case_id,
            )
        )
        is not None
    })
    saved_query_sources = _file_inventory(
        trace_paths,
        root,
        algorithm=SAVED_QUERY_FINGERPRINT_ALGORITHM,
    )
    extracted_queries = [
        {
            "arguments": task.arguments,
            "call_index": task.call_index,
            "case_id": task.case_id,
            "query_id": task.query_id,
            "source_run_root": task.source_run_root,
        }
        for task in tasks
    ]
    relevant_paths = [
        script_path,
        config.path,
        config.manifest,
        *trace_paths,
    ]
    return {
        "schema_version": PROVENANCE_SCHEMA_VERSION,
        "benchmark_script": _file_record(script_path, root),
        "benchmark_config": _file_record(config.path, root),
        "dataset_manifest": _file_record(config.manifest, root),
        "dataset_content": dataset_content_provenance(config.manifest, repo_root=root),
        "saved_query_sources": {
            **saved_query_sources,
            "extracted_query_count": len(tasks),
            "extracted_queries_sha256": _canonical_json_sha256(extracted_queries),
        },
        "git": _git_provenance(root, relevant_paths),
    }


def dataset_content_provenance(
    manifest_path: str | Path,
    *,
    repo_root: str | Path = REPO_ROOT,
) -> JsonMap:
    """Hash every authoring and Reading Model file loaded by a Golden manifest."""

    root = Path(repo_root).resolve()
    manifest_file = Path(manifest_path).resolve()
    manifest = yaml.safe_load(manifest_file.read_text(encoding="utf-8")) or {}
    if not isinstance(manifest, Mapping):
        raise ValueError(f"dataset manifest must be an object: {manifest_file}")
    paths = {manifest_file}
    pack_paths: list[Path] = []
    for raw_path in as_list(manifest.get("paper_packs")):
        path = resolve_authoring_path(manifest_file, raw_path)
        paths.add(path)
        pack_paths.append(path)
    for raw_path in as_list(manifest.get("case_files")):
        paths.add(resolve_authoring_path(manifest_file, raw_path))
    for pack_path in pack_paths:
        pack = yaml.safe_load(pack_path.read_text(encoding="utf-8")) or {}
        if not isinstance(pack, Mapping):
            raise ValueError(f"paper pack must be an object: {pack_path}")
        data_dir = str(pack.get("data_dir") or "").strip()
        if not data_dir:
            raise ValueError(f"paper pack is missing data_dir: {pack_path}")
        for raw_paper in as_list(pack.get("papers")):
            paper_id = str(child_map(raw_paper).get("id") or "").strip()
            if not paper_id:
                raise ValueError(f"paper pack contains a paper without id: {pack_path}")
            paths.add(reading_model_path(manifest_file, data_dir, paper_id))
    return _file_inventory(
        sorted(paths),
        root,
        algorithm=DATASET_FINGERPRINT_ALGORITHM,
    )


def _file_inventory(
    paths: Sequence[Path],
    repo_root: Path,
    *,
    algorithm: str,
) -> JsonMap:
    records = [_file_record(path, repo_root) for path in sorted(set(paths))]
    return {
        "algorithm": algorithm,
        "sha256": _canonical_json_sha256(records),
        "file_count": len(records),
        "files": records,
    }


def _file_record(path: Path, repo_root: Path) -> JsonMap:
    resolved = path.resolve()
    record: JsonMap = {
        "path": _display_path(resolved, repo_root),
        "exists": resolved.is_file(),
    }
    if resolved.is_file():
        content = resolved.read_bytes()
        record.update({
            "bytes": len(content),
            "sha256": hashlib.sha256(content).hexdigest(),
        })
    return record


def _canonical_json_sha256(value: Any) -> str:
    encoded = json.dumps(
        value,
        ensure_ascii=True,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _git_provenance(repo_root: Path, relevant_paths: Sequence[Path]) -> JsonMap:
    try:
        head = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=repo_root,
            check=True,
            text=True,
            capture_output=True,
        ).stdout.strip()
        worktree_status = subprocess.run(
            ["git", "status", "--porcelain=v1", "--untracked-files=all"],
            cwd=repo_root,
            check=True,
            text=True,
            capture_output=True,
        ).stdout
        pathspecs = [
            _display_path(path.resolve(), repo_root)
            for path in relevant_paths
        ]
        relevant_status = subprocess.run(
            [
                "git",
                "status",
                "--porcelain=v1",
                "--untracked-files=all",
                "--",
                *pathspecs,
            ],
            cwd=repo_root,
            check=True,
            text=True,
            capture_output=True,
        ).stdout
        return {
            "available": True,
            "head_commit": head,
            "worktree_dirty": bool(worktree_status.strip()),
            "worktree_status_porcelain_sha256": hashlib.sha256(
                worktree_status.encode("utf-8")
            ).hexdigest(),
            "relevant_paths_dirty": bool(relevant_status.strip()),
            "relevant_status_porcelain": relevant_status.splitlines(),
        }
    except (FileNotFoundError, subprocess.CalledProcessError) as error:
        return {
            "available": False,
            "error": str(error),
            "worktree_dirty": None,
        }


class EmbeddingGateway:
    def __init__(
        self,
        provider: ProviderConfig,
        *,
        batch_size: int,
        timeout_seconds: float,
        query_min_interval_seconds: float = 0.0,
        retry_policy: RetryPolicy | None = None,
        attempt_journal: RequestAttemptJournal | None = None,
        sleeper: Callable[[float], None] | None = None,
        clock: Callable[[], float] | None = None,
    ):
        self.provider = provider
        self.batch_size = max(1, batch_size)
        self.timeout_seconds = max(1.0, timeout_seconds)
        self.query_min_interval_seconds = max(0.0, query_min_interval_seconds)
        self.retry_policy = retry_policy or RetryPolicy()
        self.attempt_journal = attempt_journal
        self.sleeper = sleeper or time.sleep
        self.clock = clock or time.perf_counter
        self._last_query_request_started: float | None = None

    def embed_documents(self, texts: Sequence[str]) -> tuple[list[list[float]], int, float]:
        return self._embed(texts, usage_type="db")

    def embed_query(self, text: str) -> tuple[list[float], int, float]:
        vectors, tokens, duration_ms = self._embed([text], usage_type="query")
        if len(vectors) != 1:
            raise RuntimeError("embedding provider did not return exactly one query vector")
        return vectors[0], tokens, duration_ms

    def _embed(
        self,
        texts: Sequence[str],
        *,
        usage_type: str,
    ) -> tuple[list[list[float]], int, float]:
        vectors: list[list[float]] = []
        total_tokens = 0
        active_duration_seconds = 0.0
        batch_count = math.ceil(len(texts) / self.batch_size)
        for start in range(0, len(texts), self.batch_size):
            batch = list(texts[start:start + self.batch_size])
            batch_index = (start // self.batch_size) + 1
            if usage_type == "query":
                self._pace_query_request()
            request_started = self.clock()
            payload = embedding_request_body(self.provider, batch, usage_type)
            response = _post_json(
                _url(self.provider.api_base_url, "/embeddings"),
                payload,
                headers={
                    "Authorization": f"Bearer {self.provider.api_key}",
                },
                timeout_seconds=self.timeout_seconds,
                operation="embedding request",
                retry_policy=self.retry_policy,
                attempt_journal=self.attempt_journal,
                sleeper=self.sleeper,
                attempt_context={
                    "usage_type": usage_type,
                    "batch_index": batch_index,
                    "batch_count": batch_count,
                    "input_count": len(batch),
                },
            )
            active_duration_seconds += self.clock() - request_started
            batch_vectors, batch_tokens = parse_embedding_response(
                self.provider, response, batch
            )
            if len(batch_vectors) != len(batch):
                raise RuntimeError(
                    "embedding result count does not match input text count"
                )
            vectors.extend(batch_vectors)
            total_tokens += batch_tokens
        duration_ms = active_duration_seconds * 1000.0
        return vectors, total_tokens, duration_ms

    def _pace_query_request(self) -> None:
        now = self.clock()
        if self._last_query_request_started is not None:
            elapsed = now - self._last_query_request_started
            wait_seconds = max(0.0, self.query_min_interval_seconds - elapsed)
            if wait_seconds > 0:
                self.sleeper(wait_seconds)
                if self.attempt_journal is not None:
                    self.attempt_journal.record_pacing_wait(
                        wait_seconds=wait_seconds,
                        minimum_interval_seconds=self.query_min_interval_seconds,
                    )
                now = self.clock()
        self._last_query_request_started = now


def embedding_request_body(
    provider: ProviderConfig,
    texts: Sequence[str],
    usage_type: str,
) -> JsonMap:
    if provider.api_style == "minimax-embedding":
        return {
            "model": provider.model,
            "texts": list(texts),
            "type": "query" if usage_type == "query" else "db",
        }
    return {
        "model": provider.model,
        "input": list(texts),
        "encoding_format": "float",
    }


def parse_embedding_response(
    provider: ProviderConfig,
    response: Mapping[str, Any],
    input_texts: Sequence[str],
) -> tuple[list[list[float]], int]:
    if provider.api_style == "minimax-embedding":
        base_response = child_map(response.get("base_resp"))
        status_code = int(base_response.get("status_code") or 0)
        if status_code != 0:
            status_message = str(base_response.get("status_msg") or "request failed")
            raise RuntimeError(
                f"MiniMax Embedding API error {status_code}: {status_message}"
            )
        raw_vectors = as_list(response.get("vectors"))
        token_count = int(response.get("total_tokens") or 0)
    else:
        raw_vectors = [child_map(item).get("embedding") for item in as_list(response.get("data"))]
        usage = child_map(response.get("usage"))
        token_count = int(usage.get("total_tokens") or usage.get("input_tokens") or 0)
    vectors = [
        [float(value) for value in as_list(raw_vector)]
        for raw_vector in raw_vectors
        if isinstance(raw_vector, (list, tuple))
    ]
    if token_count <= 0:
        token_count = estimate_embedding_tokens(input_texts)
    return vectors, token_count


def estimate_embedding_tokens(texts: Sequence[str]) -> int:
    return sum(max(1, math.ceil(len(text) / 4)) for text in texts)


class QdrantGateway:
    def __init__(
        self,
        *,
        base_url: str,
        api_key: str,
        collection: str,
        timeout_seconds: float,
        upsert_batch_size: int,
        retry_policy: RetryPolicy | None = None,
        attempt_journal: RequestAttemptJournal | None = None,
        sleeper: Callable[[float], None] | None = None,
    ):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.collection = collection
        self.timeout_seconds = max(1.0, timeout_seconds)
        self.upsert_batch_size = max(1, upsert_batch_size)
        self.retry_policy = retry_policy or RetryPolicy()
        self.attempt_journal = attempt_journal
        self.sleeper = sleeper or time.sleep

    def ensure_collection(self, dimension: int) -> None:
        if dimension <= 0:
            raise ValueError("Qdrant dense vector dimension must be positive")
        existing, status = self._request(
            "GET", self._collection_path(), allow_not_found=True
        )
        if status == 404:
            self._request(
                "PUT",
                self._collection_path(),
                {
                    "vectors": {
                        "dense": {"size": dimension, "distance": "Cosine"}
                    },
                    "sparse_vectors": {
                        "sparse": {"index": {"on_disk": True}}
                    },
                },
            )
        else:
            params = child_map(
                child_map(child_map(existing.get("result")).get("config")).get("params")
            )
            configured = int(
                child_map(child_map(params.get("vectors")).get("dense")).get("size")
                or 0
            )
            if configured != dimension:
                raise RuntimeError(
                    f"dedicated Qdrant collection dimension is {configured}, expected {dimension}; "
                    "remove the benchmark collection or choose a new collection name"
                )
            if "sparse" not in child_map(params.get("sparse_vectors")):
                raise RuntimeError(
                    "dedicated Qdrant collection is missing the named sparse vector"
                )
        for field_name, field_schema in (
            ("benchmark_generation", "keyword"),
            ("paper_id", "keyword"),
            ("page_number", "integer"),
            ("element_types", "keyword"),
        ):
            self._request(
                "PUT",
                self._collection_path() + "/index?wait=true",
                {"field_name": field_name, "field_schema": field_schema},
            )

    def upsert(
        self,
        documents: Sequence[IndexedDocument],
        vectors: Sequence[Sequence[float]],
        generation: str,
    ) -> float:
        if len(documents) != len(vectors):
            raise ValueError("Qdrant document/vector counts differ")
        started = time.perf_counter()
        for start in range(0, len(documents), self.upsert_batch_size):
            points = []
            for document, vector in zip(
                documents[start:start + self.upsert_batch_size],
                vectors[start:start + self.upsert_batch_size],
            ):
                sparse = sparse_vector(document.searchable_text)
                points.append({
                    "id": point_id(document, generation),
                    "vector": {
                        "dense": list(vector),
                        "sparse": sparse,
                    },
                    "payload": document.payload(generation),
                })
            self._request(
                "PUT",
                self._collection_path() + "/points?wait=true",
                {"points": points},
            )
        return (time.perf_counter() - started) * 1000.0

    def count_generation(self, generation: str) -> int:
        response, _ = self._request(
            "POST",
            self._collection_path() + "/points/count",
            {
                "filter": _qdrant_filter(generation, (), None, None),
                "exact": True,
            },
        )
        return int(child_map(response.get("result")).get("count") or 0)

    def cleanup_generation(self, generation: str) -> None:
        self._request(
            "POST",
            self._collection_path() + "/points/delete?wait=true",
            {"filter": _qdrant_filter(generation, (), None, None)},
        )

    def search_dense(
        self,
        vector: Sequence[float],
        *,
        generation: str,
        paper_ids: Sequence[str],
        page_from: int | None,
        page_to: int | None,
        limit: int,
    ) -> tuple[list[SearchHit], float]:
        return self._search(
            {"name": "dense", "vector": list(vector)},
            generation=generation,
            paper_ids=paper_ids,
            page_from=page_from,
            page_to=page_to,
            limit=limit,
        )

    def search_sparse(
        self,
        vector: JsonMap,
        *,
        generation: str,
        paper_ids: Sequence[str],
        page_from: int | None,
        page_to: int | None,
        limit: int,
    ) -> tuple[list[SearchHit], float]:
        if not as_list(vector.get("indices")):
            return [], 0.0
        return self._search(
            {"name": "sparse", "vector": vector},
            generation=generation,
            paper_ids=paper_ids,
            page_from=page_from,
            page_to=page_to,
            limit=limit,
        )

    def _search(
        self,
        vector: JsonMap,
        *,
        generation: str,
        paper_ids: Sequence[str],
        page_from: int | None,
        page_to: int | None,
        limit: int,
    ) -> tuple[list[SearchHit], float]:
        started = time.perf_counter()
        response, _ = self._request(
            "POST",
            self._collection_path() + "/points/search",
            {
                "vector": vector,
                "filter": _qdrant_filter(
                    generation, paper_ids, page_from, page_to
                ),
                "limit": max(1, limit),
                "with_payload": True,
            },
        )
        duration_ms = (time.perf_counter() - started) * 1000.0
        hits = [
            SearchHit(
                location_ref=str(child_map(item.get("payload")).get("location_ref") or ""),
                score=float(item.get("score") or 0.0),
                payload=dict(child_map(item.get("payload"))),
            )
            for item in as_list(response.get("result"))
            if child_map(item).get("payload")
            and child_map(child_map(item).get("payload")).get("location_ref")
        ]
        return hits, duration_ms

    def _collection_path(self) -> str:
        return "/collections/" + urllib.parse.quote(self.collection, safe="")

    def _request(
        self,
        method: str,
        path: str,
        payload: JsonMap | None = None,
        *,
        allow_not_found: bool = False,
    ) -> tuple[JsonMap, int]:
        headers = {"Accept": "application/json"}
        if payload is not None:
            headers["Content-Type"] = "application/json"
        if self.api_key:
            headers["api-key"] = self.api_key
        return _request_json(
            _url(self.base_url, path),
            method=method,
            payload=payload,
            headers=headers,
            timeout_seconds=self.timeout_seconds,
            service="qdrant",
            operation=f"Qdrant {method} {path}",
            target=path,
            retry_policy=self.retry_policy,
            attempt_journal=self.attempt_journal,
            sleeper=self.sleeper,
            allow_not_found=allow_not_found,
            attempt_context={"collection": self.collection},
        )


class BenchmarkRunner:
    def __init__(
        self,
        config: BenchmarkConfig,
        dataset: GoldenDataset,
        documents: Sequence[IndexedDocument],
        tasks: Sequence[QueryTask],
        embeddings: EmbeddingGateway,
        qdrant: QdrantGateway,
        generation: str,
    ):
        self.config = config
        self.dataset = dataset
        self.documents = list(documents)
        self.tasks = list(tasks)
        self.embeddings = embeddings
        self.qdrant = qdrant
        self.generation = generation
        self.document_by_ref = {
            document.location_ref: document for document in self.documents
        }
        self.bm25 = ReadingCorpusTools(dataset)

    def build_index(self) -> JsonMap:
        started = time.perf_counter()
        vectors, tokens, embedding_ms = self.embeddings.embed_documents(
            [document.searchable_text for document in self.documents]
        )
        if not vectors or not vectors[0]:
            raise RuntimeError("embedding provider returned no index vectors")
        dimension = len(vectors[0])
        if any(len(vector) != dimension for vector in vectors):
            raise RuntimeError("embedding provider returned inconsistent vector dimensions")
        ensure_started = time.perf_counter()
        self.qdrant.ensure_collection(dimension)
        ensure_ms = (time.perf_counter() - ensure_started) * 1000.0
        upsert_ms = self.qdrant.upsert(self.documents, vectors, self.generation)
        written = self.qdrant.count_generation(self.generation)
        if written != len(self.documents):
            raise RuntimeError(
                f"Qdrant indexed {written} of {len(self.documents)} expected benchmark points"
            )
        return {
            "duration_ms": _round_ms((time.perf_counter() - started) * 1000.0),
            "embedding_duration_ms": _round_ms(embedding_ms),
            "collection_prepare_duration_ms": _round_ms(ensure_ms),
            "qdrant_upsert_duration_ms": _round_ms(upsert_ms),
            "actual_embedding_tokens": tokens,
            "point_count": written,
            "dense_dimension": dimension,
            "embedding_model": self.embeddings.provider.model,
            "embedding_provider": self.embeddings.provider.provider,
            "embedding_api_style": self.embeddings.provider.api_style,
            "qdrant_collection": self.qdrant.collection,
            "benchmark_generation": self.generation,
            "granularity": index_granularity_metrics(self.documents),
        }

    def run_queries(self) -> list[JsonMap]:
        return [self._run_query(task) for task in self.tasks]

    def iter_query_results(self) -> Iterable[JsonMap]:
        for task in self.tasks:
            yield self._run_query(task)

    def _run_query(self, task: QueryTask) -> JsonMap:
        evaluation_depth = max(self.config.cutoffs[-1], task.native_top_k)
        bm25_ranked, bm25_native, bm25_latency = self._run_bm25(
            task, evaluation_depth
        )
        query_vector, query_tokens, embedding_ms = self.embeddings.embed_query(
            task.retrieval_query
        )
        sparse_started = time.perf_counter()
        query_sparse = sparse_vector(task.retrieval_query)
        sparse_build_ms = (time.perf_counter() - sparse_started) * 1000.0
        global_limit = self._candidate_limit(evaluation_depth)
        dense_hits, dense_ms = self.qdrant.search_dense(
            query_vector,
            generation=self.generation,
            paper_ids=task.paper_ids,
            page_from=task.page_from,
            page_to=task.page_to,
            limit=global_limit,
        )
        sparse_hits, sparse_ms = self.qdrant.search_sparse(
            query_sparse,
            generation=self.generation,
            paper_ids=task.paper_ids,
            page_from=task.page_from,
            page_to=task.page_to,
            limit=global_limit,
        )

        per_paper_dense: dict[str, list[SearchHit]] = {}
        per_paper_sparse: dict[str, list[SearchHit]] = {}
        per_paper_dense_ms = 0.0
        per_paper_sparse_ms = 0.0
        if self._uses_per_paper_search(task.paper_ids, evaluation_depth):
            per_paper_limit = self._per_paper_limit(evaluation_depth)
            for paper_id in task.paper_ids:
                paper_dense, paper_dense_ms = self.qdrant.search_dense(
                    query_vector,
                    generation=self.generation,
                    paper_ids=(paper_id,),
                    page_from=task.page_from,
                    page_to=task.page_to,
                    limit=per_paper_limit,
                )
                paper_sparse, paper_sparse_ms = self.qdrant.search_sparse(
                    query_sparse,
                    generation=self.generation,
                    paper_ids=(paper_id,),
                    page_from=task.page_from,
                    page_to=task.page_to,
                    limit=per_paper_limit,
                )
                per_paper_dense[paper_id] = paper_dense
                per_paper_sparse[paper_id] = paper_sparse
                per_paper_dense_ms += paper_dense_ms
                per_paper_sparse_ms += paper_sparse_ms

        hybrid = fuse_hits(
            dense_hits,
            sparse_hits,
            rrf_k=self.config.rrf_k,
        )
        budgets = sorted(set(self.config.cutoffs + (task.native_top_k,)))
        production_rankings = {
            budget: self._production_ranking(
                task,
                budget,
                dense_hits,
                sparse_hits,
                per_paper_dense,
                per_paper_sparse,
            )
            for budget in budgets
        }
        refs_by_mode_and_budget: dict[str, dict[int | str, list[str]]] = {
            "bm25": {
                **{cutoff: bm25_ranked[:cutoff] for cutoff in self.config.cutoffs},
                # The current adapter deliberately expands broad/multi-paper calls
                # beyond requested top_k. Native metrics preserve what the model
                # actually receives; fixed Recall@K still uses strict prefixes.
                "native": bm25_native,
            },
            "qdrant_sparse": {
                **{
                    cutoff: [hit.location_ref for hit in sparse_hits[:cutoff]]
                    for cutoff in self.config.cutoffs
                },
                "native": [
                    hit.location_ref for hit in sparse_hits[:task.native_top_k]
                ],
            },
            "qdrant_dense": {
                **{
                    cutoff: [hit.location_ref for hit in dense_hits[:cutoff]]
                    for cutoff in self.config.cutoffs
                },
                "native": [
                    hit.location_ref for hit in dense_hits[:task.native_top_k]
                ],
            },
            "qdrant_hybrid_rrf": {
                **{
                    cutoff: [hit.location_ref for hit in hybrid[:cutoff]]
                    for cutoff in self.config.cutoffs
                },
                "native": [
                    hit.location_ref for hit in hybrid[:task.native_top_k]
                ],
            },
            "qdrant_production_hybrid_coverage": {
                **{
                    cutoff: [hit.location_ref for hit in production_rankings[cutoff]]
                    for cutoff in self.config.cutoffs
                },
                "native": [
                    hit.location_ref
                    for hit in production_rankings[task.native_top_k]
                ],
            },
        }
        latency = {
            "bm25": {
                "total_ms": bm25_latency,
                "components_ms": {"in_memory_retrieval": bm25_latency},
            },
            "qdrant_sparse": {
                "total_ms": sparse_build_ms + sparse_ms,
                "components_ms": {
                    "sparse_vector_build": sparse_build_ms,
                    "qdrant_sparse_search": sparse_ms,
                },
            },
            "qdrant_dense": {
                "total_ms": embedding_ms + dense_ms,
                "components_ms": {
                    "query_embedding": embedding_ms,
                    "qdrant_dense_search": dense_ms,
                },
            },
            "qdrant_hybrid_rrf": {
                "total_ms": embedding_ms + dense_ms + sparse_build_ms + sparse_ms,
                "components_ms": {
                    "query_embedding": embedding_ms,
                    "sparse_vector_build": sparse_build_ms,
                    "qdrant_dense_search": dense_ms,
                    "qdrant_sparse_search": sparse_ms,
                },
            },
            "qdrant_production_hybrid_coverage": {
                "total_ms": (
                    embedding_ms
                    + dense_ms
                    + sparse_build_ms
                    + sparse_ms
                    + per_paper_dense_ms
                    + per_paper_sparse_ms
                ),
                "components_ms": {
                    "query_embedding": embedding_ms,
                    "sparse_vector_build": sparse_build_ms,
                    "qdrant_dense_search": dense_ms,
                    "qdrant_sparse_search": sparse_ms,
                    "qdrant_per_paper_dense_search": per_paper_dense_ms,
                    "qdrant_per_paper_sparse_search": per_paper_sparse_ms,
                },
            },
        }
        modes = {
            mode: _query_mode_artifact(
                task,
                refs_by_mode_and_budget[mode],
                self._anchor_ids_for_refs(mode, refs_by_mode_and_budget[mode]),
                latency[mode],
                self.config.cutoffs,
            )
            for mode in MODES
        }
        return {
            "query_id": task.query_id,
            "case_id": task.case_id,
            "call_index": task.call_index,
            "source_run_root": task.source_run_root,
            "arguments": task.arguments,
            "retrieval_query": task.retrieval_query,
            "paper_ids": list(task.paper_ids),
            "element_types": list(task.element_types),
            "native_top_k": task.native_top_k,
            "evaluation_depth": evaluation_depth,
            "relevant_anchor_ids": list(task.relevant_anchor_ids),
            "case_required_anchor_ids": list(task.case_required_anchor_ids),
            "query_embedding_tokens": query_tokens,
            "modes": modes,
        }

    def _run_bm25(
        self, task: QueryTask, evaluation_depth: int
    ) -> tuple[list[str], list[str], float]:
        self.bm25.authorized_paper_ids.update(task.paper_ids)
        evaluation_arguments = dict(task.arguments)
        evaluation_arguments["top_k"] = evaluation_depth
        started = time.perf_counter()
        evaluation_payload = self.bm25.find_reading_locations(evaluation_arguments)
        latency_ms = (time.perf_counter() - started) * 1000.0
        if evaluation_payload.get("error"):
            raise RuntimeError(
                f"BM25 replay failed for {task.query_id}: {evaluation_payload['error']}"
            )
        evaluation_refs = [
            str(child_map(item).get("location_ref") or "")
            for item in as_list(evaluation_payload.get("locations"))
            if child_map(item).get("location_ref")
        ]
        if task.native_top_k == evaluation_depth:
            return evaluation_refs, evaluation_refs, latency_ms
        native_arguments = dict(task.arguments)
        native_arguments["top_k"] = task.native_top_k
        native_payload = self.bm25.find_reading_locations(native_arguments)
        if native_payload.get("error"):
            raise RuntimeError(
                f"BM25 native replay failed for {task.query_id}: {native_payload['error']}"
            )
        native_refs = [
            str(child_map(item).get("location_ref") or "")
            for item in as_list(native_payload.get("locations"))
            if child_map(item).get("location_ref")
        ]
        return evaluation_refs, native_refs, latency_ms

    def _production_ranking(
        self,
        task: QueryTask,
        budget: int,
        global_dense: Sequence[SearchHit],
        global_sparse: Sequence[SearchHit],
        per_paper_dense: Mapping[str, Sequence[SearchHit]],
        per_paper_sparse: Mapping[str, Sequence[SearchHit]],
    ) -> list[FusedHit]:
        candidate_limit = self._candidate_limit(budget)
        dense_lists: list[Sequence[SearchHit]] = [global_dense[:candidate_limit]]
        sparse_lists: list[Sequence[SearchHit]] = [global_sparse[:candidate_limit]]
        if self._uses_per_paper_search(task.paper_ids, budget):
            per_paper_limit = self._per_paper_limit(budget)
            dense_lists.extend(
                per_paper_dense.get(paper_id, ())[:per_paper_limit]
                for paper_id in task.paper_ids
            )
            sparse_lists.extend(
                per_paper_sparse.get(paper_id, ())[:per_paper_limit]
                for paper_id in task.paper_ids
            )
        ranked = fuse_hit_lists(
            dense_lists,
            sparse_lists,
            rrf_k=self.config.rrf_k,
            element_type_hints=set(task.element_types),
            element_type_hint_boost=self.config.element_type_hint_boost,
        )
        return select_paper_coverage(ranked, task.paper_ids, budget)

    def _candidate_limit(self, top_k: int) -> int:
        return min(
            self.config.candidate_limit_max,
            max(
                self.config.candidate_limit_min,
                top_k * self.config.candidate_limit_multiplier,
            ),
        )

    def _per_paper_limit(self, top_k: int) -> int:
        return min(
            self.config.per_paper_limit_max,
            max(
                self.config.per_paper_limit_min,
                top_k * self.config.per_paper_limit_multiplier,
            ),
        )

    def _uses_per_paper_search(
        self, paper_ids: Sequence[str], top_k: int
    ) -> bool:
        return (
            1 < len(paper_ids) <= self.config.per_paper_search_max_papers
            and len(paper_ids) <= top_k
        )

    def _anchor_ids_for_refs(
        self,
        mode: str,
        refs_by_budget: Mapping[int | str, Sequence[str]],
    ) -> dict[int | str, list[list[str]]]:
        if mode == "bm25":
            return {
                budget: [
                    list(self.bm25.documents_by_location[ref].matched_anchor_ids)
                    if ref in self.bm25.documents_by_location
                    else []
                    for ref in refs
                ]
                for budget, refs in refs_by_budget.items()
            }
        return {
            budget: [
                list(self.document_by_ref[ref].anchor_ids)
                if ref in self.document_by_ref
                else []
                for ref in refs
            ]
            for budget, refs in refs_by_budget.items()
        }


def fuse_hits(
    dense_hits: Sequence[SearchHit],
    sparse_hits: Sequence[SearchHit],
    *,
    rrf_k: int,
) -> list[FusedHit]:
    return fuse_hit_lists([dense_hits], [sparse_hits], rrf_k=rrf_k)


def fuse_hit_lists(
    dense_hit_lists: Sequence[Sequence[SearchHit]],
    sparse_hit_lists: Sequence[Sequence[SearchHit]],
    *,
    rrf_k: int,
    element_type_hints: set[str] | None = None,
    element_type_hint_boost: float = 0.0,
) -> list[FusedHit]:
    fused: dict[str, FusedHit] = {}
    for dense, hit_lists in ((True, dense_hit_lists), (False, sparse_hit_lists)):
        for hits in hit_lists:
            for rank, hit in enumerate(hits, start=1):
                current = fused.setdefault(
                    hit.location_ref,
                    FusedHit(hit.location_ref, dict(hit.payload)),
                )
                current.fused_score += 1.0 / (rrf_k + rank)
                if dense:
                    current.dense_score = hit.score
                else:
                    current.sparse_score = hit.score
    hints = element_type_hints or set()
    if hints and element_type_hint_boost:
        for hit in fused.values():
            payload_types = {
                str(value).strip().lower()
                for value in as_list(hit.payload.get("element_types"))
                if str(value).strip()
            }
            payload_type = str(hit.payload.get("element_type") or "").strip().lower()
            if hints & (payload_types | {payload_type}):
                hit.fused_score += element_type_hint_boost
    return sorted(
        fused.values(), key=lambda hit: (-hit.fused_score, hit.location_ref)
    )


def select_paper_coverage(
    ranked: Sequence[FusedHit],
    paper_order: Sequence[str],
    top_k: int,
) -> list[FusedHit]:
    if len(paper_order) > top_k:
        return list(ranked[:top_k])
    selected: list[FusedHit] = []
    selected_refs: set[str] = set()
    for paper_id in paper_order:
        candidate = next(
            (
                hit
                for hit in ranked
                if str(hit.payload.get("paper_id") or "") == paper_id
                and hit.location_ref not in selected_refs
            ),
            None,
        )
        if candidate is not None and len(selected) < top_k:
            selected.append(candidate)
            selected_refs.add(candidate.location_ref)
    for hit in ranked:
        if len(selected) >= top_k:
            break
        if hit.location_ref not in selected_refs:
            selected.append(hit)
            selected_refs.add(hit.location_ref)
    return sorted(
        selected, key=lambda hit: (-hit.fused_score, hit.location_ref)
    )


def sparse_vector(text: str) -> JsonMap:
    frequencies: dict[int, int] = {}
    for token in TOKEN_PATTERN.findall(_java_normalize(text)):
        if len(token) < 2:
            continue
        token_index = int.from_bytes(
            hashlib.sha256(token.encode("utf-8")).digest()[:4],
            byteorder="big",
            signed=False,
        ) & 0x7FFFFFFF
        frequencies[token_index] = frequencies.get(token_index, 0) + 1
    indices = sorted(frequencies)
    return {
        "indices": indices,
        "values": [1.0 + math.log(frequencies[index]) for index in indices],
    }


def point_id(document: IndexedDocument, generation: str) -> str:
    digest = bytearray(hashlib.sha256(
        (
            document.paper_id
            + "\n"
            + document.model_version
            + "\n"
            + document.location_ref
            + "\n"
            + generation
        ).encode("utf-8")
    ).digest()[:16])
    digest[6] = (digest[6] & 0x0F) | 0x80
    digest[8] = (digest[8] & 0x3F) | 0x80
    return str(uuid.UUID(bytes=bytes(digest)))


def _query_mode_artifact(
    task: QueryTask,
    refs_by_budget: Mapping[int | str, Sequence[str]],
    anchors_by_budget: Mapping[int | str, Sequence[Sequence[str]]],
    latency: Mapping[str, Any],
    cutoffs: Sequence[int],
) -> JsonMap:
    evaluation_depth = max(cutoffs[-1], task.native_top_k)
    fixed: JsonMap = {}
    for cutoff in cutoffs:
        fixed[str(cutoff)] = _ranking_artifact(
            task.relevant_anchor_ids,
            refs_by_budget.get(cutoff, ()),
            anchors_by_budget.get(cutoff, ()),
        )
    native = _ranking_artifact(
        task.relevant_anchor_ids,
        refs_by_budget.get("native", ()),
        anchors_by_budget.get("native", ()),
    )
    return {
        "evaluation_depth_latency": {
            "measurement_scope": LATENCY_MEASUREMENT_SCOPE,
            "evaluation_depth": evaluation_depth,
            "native_top_k": task.native_top_k,
            "native_workload_latency_measured": False,
            "total_ms": _round_ms(float(latency.get("total_ms") or 0.0)),
            "components_ms": {
                key: _round_ms(float(value))
                for key, value in child_map(latency.get("components_ms")).items()
            },
            "interpretation": LATENCY_INTERPRETATION,
        },
        "fixed": fixed,
        "native": native,
    }


def _ranking_artifact(
    relevant_anchor_ids: Sequence[str],
    location_refs: Sequence[str],
    anchor_ids_by_rank: Sequence[Sequence[str]],
) -> JsonMap:
    relevant = set(relevant_anchor_ids)
    hit_anchor_ids = sorted(
        relevant
        & {
            anchor_id
            for anchor_ids in anchor_ids_by_rank
            for anchor_id in anchor_ids
        }
    )
    first_relevant_rank = next(
        (
            rank
            for rank, anchor_ids in enumerate(anchor_ids_by_rank, start=1)
            if relevant & set(anchor_ids)
        ),
        None,
    )
    return {
        "location_refs": list(location_refs),
        "matched_anchor_ids_by_rank": [list(values) for values in anchor_ids_by_rank],
        "hit_anchor_ids": hit_anchor_ids,
        "missing_anchor_ids": sorted(relevant - set(hit_anchor_ids)),
        "hit_count": len(hit_anchor_ids),
        "required_count": len(relevant),
        "recall": _ratio(len(hit_anchor_ids), len(relevant)),
        "first_relevant_rank": first_relevant_rank,
        "reciprocal_rank": (
            1.0 / first_relevant_rank if first_relevant_rank is not None else 0.0
        ),
    }


def summarize_results(
    dataset: GoldenDataset,
    rows: Sequence[JsonMap],
    cutoffs: Sequence[int],
) -> JsonMap:
    modes = {
        mode: _summarize_mode(dataset, rows, mode, cutoffs)
        for mode in MODES
    }
    query_micro_transitions = {
        mode: {
            "fixed": {
                str(cutoff): _miss_transitions(rows, mode, str(cutoff))
                for cutoff in cutoffs
            },
            "native": _miss_transitions(rows, mode, "native"),
        }
        for mode in MODES
        if mode != "bm25"
    }
    case_union_transitions = {
        mode: _case_union_miss_transitions(dataset, rows, mode)
        for mode in MODES
        if mode != "bm25"
    }
    return {
        "benchmark_scope": {
            "type": "offline_retrieval_algorithm_isolation",
            "scoring_boundary": (
                "Qdrant payload is used only for location_ref routing. Anchor scoring resolves "
                "that ref against the local canonical Reading Model projection; payload is never "
                "treated as evidence."
            ),
            "not_covered": (
                "Java authorization, active-generation validation, MySQL hydration, and the "
                "Python-to-Java product transport are outside this paired benchmark."
            ),
            "latency_boundary": LATENCY_INTERPRETATION,
        },
        "quality_metric": {
            "name": "exact_authored_anchor_retrieval_proxy",
            "interpretation": (
                "Measures whether retrieved locations contain the authored exact span anchor. "
                "A miss can still be semantically equivalent evidence and should be inspected "
                "before being classified as a user-visible relevance failure."
            ),
        },
        "query_micro_metric": {
            "interpretation": (
                "Recall@K, MRR, and native-budget query metrics count an anchor again for "
                "each saved reformulation that targets its paper. Use the case-union headline "
                "for one obligation per Golden case; use query-micro metrics to diagnose query "
                "sensitivity."
            ),
        },
        "latency_metric": {
            "name": "offline_evaluation_depth_service_latency",
            "measurement_scope": LATENCY_MEASUREMENT_SCOPE,
            "native_workload_latency_measured": False,
            "interpretation": LATENCY_INTERPRETATION,
        },
        "modes": modes,
        "miss_transitions": {
            "query_micro": query_micro_transitions,
            "case_union_native": case_union_transitions,
        },
    }


def _summarize_mode(
    dataset: GoldenDataset,
    rows: Sequence[JsonMap],
    mode: str,
    cutoffs: Sequence[int],
) -> JsonMap:
    scored_rows = [row for row in rows if as_list(row.get("relevant_anchor_ids"))]
    fixed_recall: JsonMap = {}
    fixed_mrr: JsonMap = {}
    for cutoff in cutoffs:
        artifacts = [
            child_map(
                child_map(child_map(row.get("modes")).get(mode)).get("fixed")
            ).get(str(cutoff))
            for row in scored_rows
        ]
        rankings = [child_map(item) for item in artifacts if isinstance(item, dict)]
        fixed_recall[str(cutoff)] = _micro_recall(rankings)
        fixed_mrr[str(cutoff)] = _mean_metric(rankings, "reciprocal_rank")
    native_rankings = [
        child_map(child_map(child_map(row.get("modes")).get(mode)).get("native"))
        for row in scored_rows
    ]
    all_native_rankings = [
        child_map(child_map(child_map(row.get("modes")).get(mode)).get("native"))
        for row in rows
    ]
    native_returned_counts = [
        float(len(as_list(item.get("location_refs"))))
        for item in all_native_rankings
    ]
    latencies = []
    for row in rows:
        mode_artifact = child_map(child_map(row.get("modes")).get(mode))
        latency = child_map(mode_artifact.get("evaluation_depth_latency"))
        if latency.get("measurement_scope") != LATENCY_MEASUREMENT_SCOPE:
            raise ValueError(
                f"query {row.get('query_id')!r} mode {mode!r} is missing "
                "evaluation-depth latency metadata"
            )
        if latency.get("native_workload_latency_measured") is not False:
            raise ValueError(
                f"query {row.get('query_id')!r} mode {mode!r} incorrectly "
                "labels offline latency as native-workload latency"
            )
        latencies.append(float(latency.get("total_ms") or 0.0))
    full_case = _full_case_coverage(dataset, rows, mode)
    return {
        "query_count": len(rows),
        "scored_query_count": len(scored_rows),
        "recall_at": fixed_recall,
        "mrr_at": fixed_mrr,
        "native_budget_recall": _micro_recall(native_rankings),
        "native_budget_mrr": _mean_metric(native_rankings, "reciprocal_rank"),
        "native_returned_location_count": _distribution(
            native_returned_counts
        ),
        "headline_case_union_exact_anchor": {
            "hit_count": full_case["anchor_hit_count"],
            "required_count": full_case["anchor_required_count"],
            "recall": full_case["anchor_recall"],
            "full_case_count": full_case["full_case_count"],
            "eligible_case_count": full_case["eligible_case_count"],
            "full_case_rate": full_case["full_case_rate"],
        },
        "full_case_coverage": full_case,
        "evaluation_depth_latency": {
            "measurement_scope": LATENCY_MEASUREMENT_SCOPE,
            "native_workload_latency_measured": False,
            "statistics_ms": latency_statistics(latencies),
            "interpretation": LATENCY_INTERPRETATION,
        },
    }


def _micro_recall(rankings: Sequence[JsonMap]) -> JsonMap:
    numerator = sum(int(item.get("hit_count") or 0) for item in rankings)
    denominator = sum(int(item.get("required_count") or 0) for item in rankings)
    return {
        "hit_count": numerator,
        "required_count": denominator,
        "value": _ratio(numerator, denominator),
    }


def _mean_metric(rankings: Sequence[JsonMap], field_name: str) -> JsonMap:
    values = [float(item.get(field_name) or 0.0) for item in rankings]
    return {
        "query_count": len(values),
        "value": statistics.fmean(values) if values else 0.0,
    }


def _full_case_coverage(
    dataset: GoldenDataset,
    rows: Sequence[JsonMap],
    mode: str,
) -> JsonMap:
    rows_by_case: dict[str, list[JsonMap]] = defaultdict(list)
    for row in rows:
        rows_by_case[str(row.get("case_id") or "")].append(row)
    cases: list[JsonMap] = []
    full_count = 0
    hit_count = 0
    required_count = 0
    for case in dataset.cases:
        case_id = str(case.get("id") or "")
        case_rows = rows_by_case.get(case_id, [])
        required = {
            str(value)
            for row in case_rows
            for value in as_list(row.get("case_required_anchor_ids"))
            if value
        }
        if not required or not case_rows:
            continue
        hits = {
            str(anchor_id)
            for row in case_rows
            for anchor_id in as_list(
                child_map(
                    child_map(child_map(row.get("modes")).get(mode)).get("native")
                ).get("hit_anchor_ids")
            )
        }
        matched = required & hits
        is_full = matched == required
        full_count += int(is_full)
        hit_count += len(matched)
        required_count += len(required)
        cases.append({
            "case_id": case_id,
            "full_coverage": is_full,
            "hit_anchor_ids": sorted(matched),
            "missing_anchor_ids": sorted(required - matched),
            "hit_count": len(matched),
            "required_count": len(required),
        })
    return {
        "eligible_case_count": len(cases),
        "full_case_count": full_count,
        "full_case_rate": _ratio(full_count, len(cases)),
        "anchor_hit_count": hit_count,
        "anchor_required_count": required_count,
        "anchor_recall": _ratio(hit_count, required_count),
        "cases": cases,
    }


def _miss_transitions(
    rows: Sequence[JsonMap],
    candidate_mode: str,
    budget: str,
) -> JsonMap:
    counts = {
        "recovered": 0,
        "regressed": 0,
        "retained_hit": 0,
        "retained_miss": 0,
    }
    details: list[JsonMap] = []
    for row in rows:
        relevant = {str(value) for value in as_list(row.get("relevant_anchor_ids"))}
        if not relevant:
            continue
        baseline = _budget_artifact(row, "bm25", budget)
        candidate = _budget_artifact(row, candidate_mode, budget)
        baseline_hits = {str(value) for value in as_list(baseline.get("hit_anchor_ids"))}
        candidate_hits = {str(value) for value in as_list(candidate.get("hit_anchor_ids"))}
        for anchor_id in sorted(relevant):
            baseline_hit = anchor_id in baseline_hits
            candidate_hit = anchor_id in candidate_hits
            transition = (
                "retained_hit"
                if baseline_hit and candidate_hit
                else "regressed"
                if baseline_hit
                else "recovered"
                if candidate_hit
                else "retained_miss"
            )
            counts[transition] += 1
            if transition in {"recovered", "regressed"}:
                details.append({
                    "query_id": row.get("query_id"),
                    "case_id": row.get("case_id"),
                    "anchor_id": anchor_id,
                    "transition": transition,
                })
    return {"counts": counts, "changed": details}


def _case_union_miss_transitions(
    dataset: GoldenDataset,
    rows: Sequence[JsonMap],
    candidate_mode: str,
) -> JsonMap:
    rows_by_case: dict[str, list[JsonMap]] = defaultdict(list)
    for row in rows:
        rows_by_case[str(row.get("case_id") or "")].append(row)
    counts = {
        "recovered": 0,
        "regressed": 0,
        "retained_hit": 0,
        "retained_miss": 0,
    }
    details: list[JsonMap] = []
    for case in dataset.cases:
        case_id = str(case.get("id") or "")
        case_rows = rows_by_case.get(case_id, [])
        required = {
            str(value)
            for row in case_rows
            for value in as_list(row.get("case_required_anchor_ids"))
            if value
        }
        if not required or not case_rows:
            continue
        baseline_hits = {
            str(anchor_id)
            for row in case_rows
            for anchor_id in as_list(
                _budget_artifact(row, "bm25", "native").get("hit_anchor_ids")
            )
        }
        candidate_hits = {
            str(anchor_id)
            for row in case_rows
            for anchor_id in as_list(
                _budget_artifact(row, candidate_mode, "native").get(
                    "hit_anchor_ids"
                )
            )
        }
        for anchor_id in sorted(required):
            baseline_hit = anchor_id in baseline_hits
            candidate_hit = anchor_id in candidate_hits
            transition = (
                "retained_hit"
                if baseline_hit and candidate_hit
                else "regressed"
                if baseline_hit
                else "recovered"
                if candidate_hit
                else "retained_miss"
            )
            counts[transition] += 1
            if transition in {"recovered", "regressed"}:
                details.append({
                    "case_id": case_id,
                    "anchor_id": anchor_id,
                    "transition": transition,
                    "baseline_location_refs": sorted({
                        str(ref)
                        for row in case_rows
                        for ref in as_list(
                            _budget_artifact(row, "bm25", "native").get(
                                "location_refs"
                            )
                        )
                    }),
                    "candidate_location_refs": sorted({
                        str(ref)
                        for row in case_rows
                        for ref in as_list(
                            _budget_artifact(row, candidate_mode, "native").get(
                                "location_refs"
                            )
                        )
                    }),
                })
    return {"counts": counts, "changed": details}


def _budget_artifact(row: JsonMap, mode: str, budget: str) -> JsonMap:
    mode_artifact = child_map(child_map(row.get("modes")).get(mode))
    if budget == "native":
        return child_map(mode_artifact.get("native"))
    return child_map(child_map(mode_artifact.get("fixed")).get(budget))


def latency_statistics(values: Sequence[float]) -> JsonMap:
    ordered = sorted(float(value) for value in values)
    if not ordered:
        return {
            "count": 0,
            "mean": 0.0,
            "p50": 0.0,
            "p95": 0.0,
            "max": 0.0,
        }
    return {
        "count": len(ordered),
        "mean": _round_ms(statistics.fmean(ordered)),
        "p50": _round_ms(_percentile(ordered, 0.50)),
        "p95": _round_ms(_percentile(ordered, 0.95)),
        "max": _round_ms(ordered[-1]),
    }


def index_granularity_metrics(
    documents: Sequence[IndexedDocument],
) -> JsonMap:
    by_location_type: dict[str, int] = defaultdict(int)
    by_element_type: dict[str, int] = defaultdict(int)
    by_paper: dict[str, int] = defaultdict(int)
    span_chars: list[float] = []
    canonical_chars: list[float] = []
    elements_per_point: list[float] = []
    truncated_count = 0
    for document in documents:
        by_location_type[document.location_type] += 1
        by_paper[document.paper_id] += 1
        span_chars.append(float(len(document.searchable_text)))
        canonical_chars.append(float(document.canonical_char_count))
        truncated_count += int(
            document.canonical_char_count > len(document.searchable_text)
        )
        elements_per_point.append(float(len(document.reading_element_ids)))
        for element_type in document.element_types:
            by_element_type[element_type] += 1
    return {
        "point_count_by_location_type": dict(sorted(by_location_type.items())),
        "point_count_by_element_type_membership": dict(
            sorted(by_element_type.items())
        ),
        "point_count_by_paper": dict(sorted(by_paper.items())),
        "searchable_span_chars": _distribution(span_chars),
        "canonical_span_chars": _distribution(canonical_chars),
        "truncated_point_count": truncated_count,
        "indexed_to_canonical_char_ratio": _ratio(
            int(sum(span_chars)), int(sum(canonical_chars))
        ),
        "reading_elements_per_point": _distribution(elements_per_point),
    }


def _distribution(values: Sequence[float]) -> JsonMap:
    ordered = sorted(values)
    if not ordered:
        return {
            "count": 0,
            "sum": 0.0,
            "mean": 0.0,
            "p50": 0.0,
            "p95": 0.0,
            "max": 0.0,
        }
    return {
        "count": len(ordered),
        "sum": round(sum(ordered), 3),
        "mean": round(statistics.fmean(ordered), 3),
        "p50": round(_percentile(ordered, 0.50), 3),
        "p95": round(_percentile(ordered, 0.95), 3),
        "max": round(ordered[-1], 3),
    }


def _qdrant_filter(
    generation: str,
    paper_ids: Sequence[str],
    page_from: int | None,
    page_to: int | None,
) -> JsonMap:
    must: list[JsonMap] = [
        {"key": "benchmark_generation", "match": {"value": generation}}
    ]
    normalized_paper_ids = [paper_id for paper_id in paper_ids if paper_id]
    if len(normalized_paper_ids) == 1:
        must.append({
            "key": "paper_id",
            "match": {"value": normalized_paper_ids[0]},
        })
    elif normalized_paper_ids:
        must.append({
            "key": "paper_id",
            "match": {"any": normalized_paper_ids},
        })
    if page_from is not None or page_to is not None:
        page_range: JsonMap = {}
        if page_from is not None:
            page_range["gte"] = page_from
        if page_to is not None:
            page_range["lte"] = page_to
        must.append({"key": "page_number", "range": page_range})
    return {"must": must}


def resolve_qdrant_access(config: BenchmarkConfig) -> tuple[str, str, str]:
    base_url = (
        os.getenv(config.qdrant_base_url_env) or config.qdrant_default_base_url
    ).strip().rstrip("/")
    api_key = (os.getenv(config.qdrant_api_key_env) or "").strip()
    if api_key:
        return base_url, api_key, f"env:{config.qdrant_api_key_env}"
    container = (
        os.getenv(config.qdrant_container_env) or config.qdrant_default_container
    ).strip()
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
    except (FileNotFoundError, subprocess.CalledProcessError):
        return base_url, "", "none"
    prefix = "QDRANT__SERVICE__API_KEY="
    for line in result.stdout.splitlines():
        if line.startswith(prefix):
            return base_url, line[len(prefix):].strip(), f"container:{container}"
    return base_url, "", "none"


def resolve_mysql_container(
    config: BenchmarkConfig,
    requested: str | None,
) -> tuple[str, str]:
    if requested and requested.strip():
        return requested.strip(), "cli:--mysql-container"
    configured = os.getenv(config.embedding_mysql_container_env)
    if configured and configured.strip():
        return configured.strip(), f"env:{config.embedding_mysql_container_env}"
    return config.embedding_default_mysql_container, "config:default_mysql_container"


def _load_case_trace(run_root: Path, case_id: str) -> list[Any]:
    source_path = _case_trace_path(run_root, case_id)
    if source_path is None:
        return []
    if source_path.name == "react_trace.json":
        loaded = json.loads(source_path.read_text(encoding="utf-8"))
        return list(loaded) if isinstance(loaded, list) else []
    return as_list(
        json.loads(source_path.read_text(encoding="utf-8")).get("react_trace")
    )


def _case_trace_path(run_root: Path, case_id: str) -> Path | None:
    for filename in ("react_trace.json", "harness_run.json"):
        path = run_root / case_id / filename
        if path.is_file():
            return path.resolve()
    return None


def _find_location_events(trace: Sequence[Any]) -> list[JsonMap]:
    return [
        child_map(event)
        for event in trace
        if child_map(event).get("tool_name") == "find_reading_locations"
    ]


def _routed_location(
    element: JsonMap,
    locations_by_ref: Mapping[str, JsonMap],
    page_locations: Mapping[int, JsonMap],
    elements_by_id: Mapping[str, JsonMap],
) -> JsonMap | None:
    own_ref = str(element.get("locationRef") or "")
    if own_ref in locations_by_ref:
        return locations_by_ref[own_ref]
    parent = elements_by_id.get(str(element.get("parentReadingElementId") or ""))
    parent_ref = str(child_map(parent).get("locationRef") or "")
    if parent_ref in locations_by_ref:
        return locations_by_ref[parent_ref]
    page_number = _optional_int(element.get("pageNumber"))
    return page_locations.get(page_number) if page_number is not None else None


def _anchor_matches(
    anchor: JsonMap,
    searchable_text: str,
    page_number: int | None,
) -> bool:
    element = child_map(anchor.get("element"))
    return page_matches(element.get("page"), page_number) and _anchor_text_matches(
        anchor, searchable_text
    )


def _anchor_text_matches(anchor: JsonMap, searchable_text: str) -> bool:
    exact_text = str(child_map(anchor.get("selector")).get("exact_text") or "")
    return contains_normalized_phrase(
        _benchmark_normalize_text(searchable_text),
        _benchmark_normalize_text(exact_text),
    )


def _benchmark_normalize_text(value: str) -> str:
    without_markup = re.sub(r"</?[a-zA-Z][^>]*>", " ", value)
    return " ".join(re.findall(r"[a-zA-Z0-9_]+", without_markup.casefold()))


def _post_json(
    url: str,
    payload: Mapping[str, Any],
    *,
    headers: Mapping[str, str],
    timeout_seconds: float,
    operation: str,
    retry_policy: RetryPolicy | None = None,
    attempt_journal: RequestAttemptJournal | None = None,
    sleeper: Callable[[float], None] | None = None,
    attempt_context: Mapping[str, Any] | None = None,
) -> JsonMap:
    response, _ = _request_json(
        url,
        method="POST",
        payload=payload,
        headers={
            "Accept": "application/json",
            "Content-Type": "application/json",
            **headers,
        },
        timeout_seconds=timeout_seconds,
        service="embedding",
        operation=operation,
        target=urllib.parse.urlsplit(url).path,
        retry_policy=retry_policy or RetryPolicy(),
        attempt_journal=attempt_journal,
        sleeper=sleeper or time.sleep,
        attempt_context=attempt_context,
    )
    return response


def _request_json(
    url: str,
    *,
    method: str,
    payload: Mapping[str, Any] | None,
    headers: Mapping[str, str],
    timeout_seconds: float,
    service: str,
    operation: str,
    target: str,
    retry_policy: RetryPolicy,
    attempt_journal: RequestAttemptJournal | None,
    sleeper: Callable[[float], None],
    allow_not_found: bool = False,
    attempt_context: Mapping[str, Any] | None = None,
) -> tuple[JsonMap, int]:
    body = (
        None
        if payload is None
        else json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
    )
    request_id = str(uuid.uuid4())
    for attempt in range(1, retry_policy.max_attempts + 1):
        started = time.perf_counter()
        try:
            request = urllib.request.Request(
                url,
                data=body,
                headers=dict(headers),
                method=method,
            )
            with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
                raw = response.read()
                status = int(response.status)
                parsed = json.loads(raw.decode("utf-8")) if raw else {}
            _record_request_attempt(
                attempt_journal,
                request_id=request_id,
                service=service,
                operation=operation,
                method=method,
                target=target,
                attempt=attempt,
                max_attempts=retry_policy.max_attempts,
                outcome="succeeded",
                retryable=False,
                duration_ms=(time.perf_counter() - started) * 1000.0,
                body=body,
                status_code=status,
                response_body=raw,
                context=attempt_context,
            )
            return child_map(parsed), status
        except urllib.error.HTTPError as error:
            response_body = error.read()
            status = int(error.code)
            if allow_not_found and status == 404:
                _record_request_attempt(
                    attempt_journal,
                    request_id=request_id,
                    service=service,
                    operation=operation,
                    method=method,
                    target=target,
                    attempt=attempt,
                    max_attempts=retry_policy.max_attempts,
                    outcome="allowed_not_found",
                    retryable=False,
                    duration_ms=(time.perf_counter() - started) * 1000.0,
                    body=body,
                    status_code=status,
                    response_body=response_body,
                    context=attempt_context,
                )
                return {}, status
            retryable = _is_transient_http_status(status)
            should_retry = retryable and attempt < retry_policy.max_attempts
            backoff = (
                retry_policy.delay_seconds(attempt, _retry_after_seconds(error.headers))
                if should_retry
                else 0.0
            )
            response_text = response_body.decode("utf-8", errors="replace")
            _record_request_attempt(
                attempt_journal,
                request_id=request_id,
                service=service,
                operation=operation,
                method=method,
                target=target,
                attempt=attempt,
                max_attempts=retry_policy.max_attempts,
                outcome="retry_scheduled" if should_retry else "failed",
                retryable=retryable,
                duration_ms=(time.perf_counter() - started) * 1000.0,
                body=body,
                status_code=status,
                response_body=response_body,
                error_type=type(error).__name__,
                error_message=f"HTTP {status}: {response_text[:1000]}",
                backoff_seconds=backoff,
                context=attempt_context,
            )
            if not should_retry:
                raise RuntimeError(
                    f"{operation} failed with HTTP {status}: {response_text[:1000]}"
                ) from error
            sleeper(backoff)
        except (
            urllib.error.URLError,
            TimeoutError,
            ConnectionError,
            http.client.IncompleteRead,
        ) as error:
            should_retry = attempt < retry_policy.max_attempts
            backoff = retry_policy.delay_seconds(attempt) if should_retry else 0.0
            reason = _network_error_message(error)
            _record_request_attempt(
                attempt_journal,
                request_id=request_id,
                service=service,
                operation=operation,
                method=method,
                target=target,
                attempt=attempt,
                max_attempts=retry_policy.max_attempts,
                outcome="retry_scheduled" if should_retry else "failed",
                retryable=True,
                duration_ms=(time.perf_counter() - started) * 1000.0,
                body=body,
                error_type=type(error).__name__,
                error_message=reason,
                backoff_seconds=backoff,
                context=attempt_context,
            )
            if not should_retry:
                raise RuntimeError(f"{operation} failed: {reason}") from error
            sleeper(backoff)
    raise AssertionError("retry loop exhausted without returning or raising")


def _record_request_attempt(
    journal: RequestAttemptJournal | None,
    *,
    request_id: str,
    service: str,
    operation: str,
    method: str,
    target: str,
    attempt: int,
    max_attempts: int,
    outcome: str,
    retryable: bool,
    duration_ms: float,
    body: bytes | None,
    status_code: int | None = None,
    response_body: bytes | None = None,
    error_type: str | None = None,
    error_message: str | None = None,
    backoff_seconds: float = 0.0,
    context: Mapping[str, Any] | None = None,
) -> None:
    if journal is None:
        return
    event: JsonMap = {
        "event_type": "request_attempt",
        "request_id": request_id,
        "service": service,
        "operation": operation,
        "method": method,
        "target": target,
        "attempt": attempt,
        "max_attempts": max_attempts,
        "outcome": outcome,
        "retryable": retryable,
        "duration_ms": _round_ms(duration_ms),
        "request_body_bytes": len(body or b""),
        "request_body_sha256": hashlib.sha256(body or b"").hexdigest(),
        "backoff_ms": _round_ms(backoff_seconds * 1000.0),
    }
    if status_code is not None:
        event["status_code"] = status_code
    if response_body is not None:
        event["response_body_bytes"] = len(response_body)
        event["response_body_sha256"] = hashlib.sha256(response_body).hexdigest()
    if error_type:
        event["error_type"] = error_type
    if error_message:
        event["error"] = error_message
    if context:
        event["context"] = dict(context)
    journal.record(event)


def _is_transient_http_status(status: int) -> bool:
    return status in {408, 425, 429} or 500 <= status <= 599


def _retry_after_seconds(headers: Any) -> float | None:
    if headers is None:
        return None
    raw = headers.get("Retry-After")
    if raw is None:
        return None
    try:
        return max(0.0, float(raw))
    except (TypeError, ValueError):
        pass
    try:
        requested_at = email.utils.parsedate_to_datetime(str(raw))
        now = datetime.now(requested_at.tzinfo).timestamp()
        return max(0.0, requested_at.timestamp() - now)
    except (TypeError, ValueError, OverflowError):
        return None


def _network_error_message(error: BaseException) -> str:
    if isinstance(error, urllib.error.URLError):
        return str(error.reason)
    if isinstance(error, http.client.IncompleteRead):
        return (
            f"incomplete response: received {len(error.partial)} bytes, "
            f"expected {error.expected} more"
        )
    return str(error)


def _arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Compare the current in-memory BM25 retrieval with real Qdrant sparse, "
            "dense, hybrid RRF, and production-style hybrid+coverage retrieval."
        )
    )
    parser.add_argument(
        "--config",
        type=Path,
        default=REPO_ROOT / "research/golden-data/qdrant-impact-benchmark.yaml",
    )
    parser.add_argument(
        "--out",
        type=Path,
        help="Run directory below research/golden-data/local-runs.",
    )
    parser.add_argument(
        "--preflight",
        action="store_true",
        help="Validate the frozen workload and index projection without external calls.",
    )
    parser.add_argument(
        "--cleanup",
        action="store_true",
        help="Delete this run's Qdrant generation after artifacts are written.",
    )
    parser.add_argument(
        "--mysql-container",
        help=(
            "MySQL container holding model_provider_configs; overrides both the "
            "benchmark config default and its configured environment variable."
        ),
    )
    return parser.parse_args()


def main() -> int:
    args = _arguments()
    config = BenchmarkConfig.load(args.config)
    dataset = load_dataset(config.manifest)
    documents = build_index_documents(dataset)
    tasks = load_saved_query_tasks(
        dataset,
        config.saved_runs,
        config.saved_query_fills,
        config.default_native_top_k,
        config.max_top_k,
        config.required_anchor_ids_by_case,
    )
    preflight = preflight_summary(dataset, documents, tasks)
    if preflight["missing_indexed_anchor_ids"]:
        raise RuntimeError(
            "benchmark index projection cannot locate authored anchors: "
            + ", ".join(preflight["missing_indexed_anchor_ids"])
        )
    if args.preflight:
        print(json.dumps(preflight, indent=2, sort_keys=True, ensure_ascii=True))
        return 0

    provenance = build_benchmark_provenance(config, tasks)
    output_dir = _output_dir(config, args.out)
    output_dir.mkdir(parents=True, exist_ok=False)
    _write_json(output_dir / "benchmark_config.json", config.public_snapshot())
    _write_json(output_dir / "preflight.json", preflight)
    provenance_path = output_dir / "provenance.json"
    _write_json(provenance_path, provenance)
    provenance_artifact = {
        "path": str(provenance_path),
        "sha256": hashlib.sha256(provenance_path.read_bytes()).hexdigest(),
    }

    mysql_container, mysql_container_source = resolve_mysql_container(
        config, args.mysql_container
    )
    try:
        provider_store = DockerMySqlProviderConfigStore(
            env_path=config.embedding_env_path,
            container_env=config.embedding_mysql_container_env,
            default_container=config.embedding_default_mysql_container,
        )
        provider_store.container = mysql_container
        provider = provider_store.load_active_provider("embedding")
    except Exception:
        message = "failed to load the active embedding provider from Docker/MySQL"
        _write_json(output_dir / "run.json", {
            "schema_version": SCHEMA_VERSION,
            "status": "failed",
            "error": message,
            "provider_config_source": {
                "type": "docker_mysql",
                "mysql_container": mysql_container,
                "mysql_container_source": mysql_container_source,
            },
            "provenance": provenance_artifact,
        })
        print(message, file=sys.stderr)
        return 1
    base_url, qdrant_api_key, qdrant_key_source = resolve_qdrant_access(config)
    generation = str(uuid.uuid4())
    secrets = [provider.api_key, qdrant_api_key]
    request_attempt_path = output_dir / "request_attempts.jsonl"
    attempt_journal = RequestAttemptJournal(
        request_attempt_path,
        secrets=secrets,
    )
    qdrant = QdrantGateway(
        base_url=base_url,
        api_key=qdrant_api_key,
        collection=config.qdrant_collection,
        timeout_seconds=config.qdrant_timeout_seconds,
        upsert_batch_size=config.qdrant_upsert_batch_size,
        retry_policy=config.qdrant_retry_policy,
        attempt_journal=attempt_journal,
    )
    embeddings = EmbeddingGateway(
        provider,
        batch_size=config.embedding_batch_size,
        timeout_seconds=config.embedding_timeout_seconds,
        query_min_interval_seconds=config.embedding_query_min_interval_seconds,
        retry_policy=config.embedding_retry_policy,
        attempt_journal=attempt_journal,
    )
    runner = BenchmarkRunner(
        config,
        dataset,
        documents,
        tasks,
        embeddings,
        qdrant,
        generation,
    )
    try:
        index_report = runner.build_index()
        _write_json(output_dir / "index.json", index_report)
        query_path = output_dir / "query_results.jsonl"
        rows: list[JsonMap] = []
        with query_path.open("w", encoding="utf-8") as handle:
            for row in runner.iter_query_results():
                rows.append(row)
                handle.write(json.dumps(row, ensure_ascii=True, sort_keys=True) + "\n")
                handle.flush()
        summary = summarize_results(dataset, rows, config.cutoffs)
        report = {
            "schema_version": SCHEMA_VERSION,
            "generated_at": datetime.now().astimezone().isoformat(),
            "status": "complete",
            "preflight": preflight,
            "index": index_report,
            "embedding_provider": provider.public_diagnostics(),
            "provider_config_source": {
                "type": "docker_mysql",
                "mysql_container": mysql_container,
                "mysql_container_source": mysql_container_source,
            },
            "qdrant": {
                "base_url": base_url,
                "collection": config.qdrant_collection,
                "api_key_source": qdrant_key_source,
                "has_api_key": bool(qdrant_api_key),
            },
            "provenance": provenance,
            "metrics": summary,
            "raw_query_artifact": str(query_path),
            "request_attempts": attempt_journal.summary(),
        }
        _write_json(output_dir / "report.json", report)
        _write_json(output_dir / "run.json", {
            "schema_version": SCHEMA_VERSION,
            "status": "complete",
            "report": str(output_dir / "report.json"),
            "query_results": str(query_path),
            "request_attempts": str(request_attempt_path),
            "request_attempt_summary": attempt_journal.summary(),
            "provenance": provenance_artifact,
        })
        headline = child_map(
            child_map(summary.get("modes")).get(
                "qdrant_production_hybrid_coverage"
            )
        ).get("headline_case_union_exact_anchor")
        print(json.dumps({
            "status": "complete",
            "output_dir": str(output_dir),
            "production_qdrant_headline": headline,
        }, indent=2, sort_keys=True))
        return 0
    except Exception as error:
        message = _redact(str(error), secrets)
        _write_json(output_dir / "run.json", {
            "schema_version": SCHEMA_VERSION,
            "status": "failed",
            "error": message,
            "request_attempts": str(request_attempt_path),
            "request_attempt_summary": attempt_journal.summary(),
            "provenance": provenance_artifact,
            "provider_config_source": {
                "type": "docker_mysql",
                "mysql_container": mysql_container,
                "mysql_container_source": mysql_container_source,
            },
        })
        print(message, file=sys.stderr)
        return 1
    finally:
        if args.cleanup:
            try:
                qdrant.cleanup_generation(generation)
            except Exception as cleanup_error:
                print(
                    "benchmark cleanup failed: "
                    + _redact(str(cleanup_error), secrets),
                    file=sys.stderr,
                )


def _output_dir(config: BenchmarkConfig, requested: Path | None) -> Path:
    root = config.artifacts_root.resolve()
    if requested is None:
        stamp = datetime.now().astimezone().strftime("%Y-%m-%d-%H%M%S")
        output = root / f"{stamp}-{config.run_prefix}"
    else:
        output = _repo_path(requested).resolve()
    if output != root and root not in output.parents:
        raise ValueError(f"benchmark output must be below {root}")
    return output


def _write_json(path: Path, value: Any) -> None:
    path.write_text(
        json.dumps(value, indent=2, sort_keys=True, ensure_ascii=True) + "\n",
        encoding="utf-8",
    )


def _redact(value: str, secrets: Sequence[str]) -> str:
    redacted = value
    for secret in secrets:
        if secret:
            redacted = redacted.replace(secret, "[REDACTED]")
    return redacted


def _redact_json(value: Any, secrets: Sequence[str]) -> Any:
    if isinstance(value, str):
        return _redact(value, secrets)
    if isinstance(value, list):
        return [_redact_json(item, secrets) for item in value]
    if isinstance(value, tuple):
        return [_redact_json(item, secrets) for item in value]
    if isinstance(value, Mapping):
        return {
            str(key): _redact_json(item, secrets)
            for key, item in value.items()
        }
    return value


def _retry_policy(
    raw: Mapping[str, Any],
    *,
    default_max_attempts: int,
    default_initial_backoff_seconds: float,
    default_max_backoff_seconds: float,
) -> RetryPolicy:
    return RetryPolicy(
        max_attempts=int(raw.get("max_attempts") or default_max_attempts),
        initial_backoff_seconds=float(
            raw.get("initial_backoff_seconds")
            if raw.get("initial_backoff_seconds") is not None
            else default_initial_backoff_seconds
        ),
        max_backoff_seconds=float(
            raw.get("max_backoff_seconds")
            if raw.get("max_backoff_seconds") is not None
            else default_max_backoff_seconds
        ),
    )


def _retry_policy_snapshot(policy: RetryPolicy) -> JsonMap:
    return {
        "max_attempts": policy.max_attempts,
        "initial_backoff_seconds": policy.initial_backoff_seconds,
        "max_backoff_seconds": policy.max_backoff_seconds,
    }


def _url(base_url: str, path: str) -> str:
    return base_url.rstrip("/") + "/" + path.lstrip("/")


def _repo_path(value: str | Path) -> Path:
    path = Path(value)
    return path if path.is_absolute() else REPO_ROOT / path


def _display_path(path: Path, repo_root: Path) -> str:
    try:
        return str(path.relative_to(repo_root))
    except ValueError:
        return str(path)


def _required(value: Mapping[str, Any], key: str) -> Any:
    result = value.get(key)
    if result in (None, ""):
        raise ValueError(f"benchmark config field is required: {key}")
    return result


def _first_nonblank(*values: Any) -> str:
    return next(
        (str(value).strip() for value in values if str(value or "").strip()),
        "",
    )


def _append_unique(values: list[str], value: str) -> None:
    if value and value not in values:
        values.append(value)


def _optional_int(value: Any) -> int | None:
    try:
        return int(value) if value not in (None, "") else None
    except (TypeError, ValueError):
        return None


def _java_normalize(value: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", value or "").lower().split())


def _ratio(numerator: int, denominator: int) -> float:
    return numerator / denominator if denominator else 0.0


def _percentile(ordered: Sequence[float], percentile: float) -> float:
    if len(ordered) == 1:
        return ordered[0]
    position = (len(ordered) - 1) * percentile
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    fraction = position - lower
    return ordered[lower] * (1.0 - fraction) + ordered[upper] * fraction


def _round_ms(value: float) -> float:
    return round(value, 3)


if __name__ == "__main__":
    raise SystemExit(main())
