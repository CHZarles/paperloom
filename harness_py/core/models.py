from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


GOLDEN_SCHEMA_VERSION = "harness-golden-data/v2"
GOLDEN_CASE_SCHEMA_VERSION = "harness-golden-case/v2"
PAPER_PACK_SCHEMA_VERSION = "harness-paper-pack/v2"
RUN_TRACE_SCHEMA_VERSION = "harness-run-trace/v2"
SCORE_REPORT_SCHEMA_VERSION = "harness-score-report/v3"
SUPPORTED_SCORE_REPORT_SCHEMA_VERSIONS = frozenset({
    "harness-score-report/v2",
    SCORE_REPORT_SCHEMA_VERSION,
})
ARTIFACT_CONTRACT_SCHEMA_VERSION = "research-harness-artifacts/v2"


JsonMap = dict[str, Any]


@dataclass(frozen=True)
class GoldenDataset:
    root: Path
    manifest_path: Path
    manifest: JsonMap
    paper_packs: list[JsonMap]
    cases: list[JsonMap]
    paper_records_by_id: dict[str, JsonMap]
    anchors_by_id: dict[str, JsonMap]
    citation_edges: list[JsonMap]
    reading_models_by_paper_id: dict[str, JsonMap]
    load_warnings: list[str] = field(default_factory=list)


@dataclass(frozen=True)
class EvidenceLookupResult:
    item: JsonMap | None
    missing: JsonMap | None = None


def as_list(value: Any) -> list[Any]:
    if value is None:
        return []
    if isinstance(value, list):
        return value
    return [value]


def child_map(value: Any) -> JsonMap:
    return value if isinstance(value, dict) else {}


def non_empty(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, (list, dict, tuple, set)):
        return bool(value)
    return True


def stable_id(prefix: str, raw: str) -> str:
    cleaned = "".join(ch if ch.isalnum() or ch in ("_", "-") else "_" for ch in raw)
    cleaned = cleaned.strip("_")
    return f"{prefix}_{cleaned}" if cleaned else prefix
