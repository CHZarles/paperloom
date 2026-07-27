from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any


GOLDEN_SCHEMA_VERSION = "harness-golden-data/v3"
GOLDEN_CASE_SCHEMA_VERSION = "harness-golden-case/v3"
PAPER_PACK_SCHEMA_VERSION = "harness-paper-pack/v2"
GOLDEN_CLAIM_SCHEMA_VERSION = "harness-golden-claims/v1"
RUN_TRACE_SCHEMA_VERSION = "harness-run-trace/v2"
SCORE_REPORT_SCHEMA_VERSION = "harness-score-report/v4"


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
    claims_by_id: dict[str, JsonMap] = field(default_factory=dict)
    load_warnings: list[str] = field(default_factory=list)


def as_list(value: Any) -> list[Any]:
    if value is None:
        return []
    if isinstance(value, list):
        return value
    return [value]


def child_map(value: Any) -> JsonMap:
    return value if isinstance(value, dict) else {}


def utc_now_iso(timespec: str = "seconds") -> str:
    return datetime.now(UTC).isoformat(timespec=timespec).replace("+00:00", "Z")


def unique_strings(values) -> list[str]:
    if values is None:
        return []
    seen: set[str] = set()
    result: list[str] = []
    for value in values:
        text = str(value or "")
        if text and text not in seen:
            seen.add(text)
            result.append(text)
    return result


def stable_id(prefix: str, raw: str) -> str:
    cleaned = "".join(ch if ch.isalnum() or ch in ("_", "-") else "_" for ch in raw)
    cleaned = cleaned.strip("_")
    return f"{prefix}_{cleaned}" if cleaned else prefix
