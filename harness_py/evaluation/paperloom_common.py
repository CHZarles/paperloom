"""31 篇基准的共享常量、错误类型与小工具。"""

from __future__ import annotations

import hashlib
import json
import re
import subprocess
import unicodedata
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

from .judge_model import MiniMaxJudgeModel


CONFIG_SCHEMA_VERSION = "paperloom-benchmark-config/v1"
DATASET_ID = "paperloom-31-v1"
EXPECTED_PAPER_COUNT = 31
UPLOAD_CHUNK_BYTES = 5 * 1024 * 1024
SNAPSHOT_SCHEMA_VERSION = "paperloom-product-snapshot/v2"
QUERY_PROMPT_VERSION = "paperloom-query-generator-v3"
CASE_LAYOUT_VERSION = "paperloom-agent-case-layout-v5"
EVIDENCE_TYPES = {"PASSAGE", "TABLE", "FIGURE"}
MIN_PASSAGE_CHARS = 220
MIN_ANSWER_SPAN_CHARS = 40
RUN_SCHEMA_VERSION = "paperloom-benchmark-run/v3"
SOURCE_ALIGNED_QUERY_SCHEMA_VERSION = "paperloom-source-aligned-queries/v1"


class PreparationError(RuntimeError):
    def __init__(self, stage: str, code: str, detail: str) -> None:
        self.stage = stage
        self.code = code
        self.detail = detail
        super().__init__(f"{stage}:{code}: {detail}")

    def as_dict(self) -> dict[str, str]:
        return {"stage": self.stage, "code": self.code, "detail": self.detail}


@dataclass(frozen=True)
class ScannedPaper:
    key: str
    path: Path
    relative_path: str
    md5: str
    sha256: str
    size_bytes: int

    def config_entry(self) -> dict[str, str]:
        return {
            "id": self.key,
            "file": self.relative_path,
            "source_pdf_sha256": self.sha256,
        }


def _strings(value: object) -> list[str]:
    return [str(item).strip() for item in _list(value) if str(item).strip()]


def _list(value: object) -> list[object]:
    return value if isinstance(value, list) else []


def _records(value: object) -> list[dict[str, object]]:
    return [item for item in _list(value) if isinstance(item, dict)]


def _mapping(value: object) -> dict[str, object]:
    return value if isinstance(value, dict) else {}


def _normalize_text(value: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", value).split()).casefold()


def _identity_text(value: str) -> str:
    return "".join(character for character in unicodedata.normalize("NFKC", value) if character.isalnum()).casefold()


def _text_sha256(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _failure(stage: str, code: str, detail: str, *, technical: bool = False) -> dict[str, object]:
    return {"stage": stage, "code": code, "detail": detail, "technical": technical}


def _stage_result(failures: list[dict[str, object]], **values: object) -> dict[str, object]:
    return {
        "executed": True,
        "passed": not failures,
        **values,
        "failures": failures,
        "technical_errors": [failure for failure in failures if failure.get("technical")],
    }


def _git_revision() -> str:
    result = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=Path(__file__).resolve().parents[2],
        text=True,
        capture_output=True,
        check=False,
    )
    return result.stdout.strip() or "unknown"


def _write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def _read_env(path: str | Path) -> dict[str, str]:
    result: dict[str, str] = {}
    source = Path(path)
    if not source.exists():
        return result
    for line in source.read_text(encoding="utf-8", errors="ignore").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, raw = stripped.split("=", 1)
        result[key.strip()] = raw.strip().strip('"').strip("'")
    return result


def _hashes(path: Path) -> tuple[str, str, int]:
    md5 = hashlib.md5(usedforsecurity=False)
    sha256 = hashlib.sha256()
    size = 0
    with path.open("rb") as handle:
        first = handle.read(5)
        if first != b"%PDF-":
            raise PreparationError("scan", "PDF_HEADER_INVALID", str(path))
        md5.update(first)
        sha256.update(first)
        size += len(first)
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            md5.update(chunk)
            sha256.update(chunk)
            size += len(chunk)
    return md5.hexdigest(), sha256.hexdigest(), size


def _paper_key(stem: str) -> str:
    normalized = re.sub(r"[^a-z0-9]+", "_", stem.casefold()).strip("_")
    if not normalized:
        raise PreparationError("scan", "PAPER_KEY_INVALID", stem)
    return f"paper_{normalized}"


def _require_unique(
    papers: list[ScannedPaper],
    field_name: str,
    value: Callable[[ScannedPaper], object],
) -> None:
    seen: set[object] = set()
    duplicates: set[object] = set()
    for paper in papers:
        resolved = value(paper)
        if resolved in seen:
            duplicates.add(resolved)
        seen.add(resolved)
    if duplicates:
        raise PreparationError("scan", "DUPLICATE_" + field_name.upper().replace(" ", "_"), str(sorted(duplicates)))


def _function_tool(name: str, properties: dict[str, object]) -> dict[str, object]:
    return {
        "type": "function",
        "function": {
            "name": name,
            "description": "Submit one frozen PaperLoom benchmark item.",
            "parameters": {
                "type": "object",
                "properties": properties,
                "required": list(properties),
                "additionalProperties": False,
            },
        },
    }


def _call_structured(
    model: MiniMaxJudgeModel,
    messages: list[dict[str, object]],
    tool: dict[str, object],
    max_tokens: int,
) -> dict[str, object]:
    expected_name = str(_mapping(tool.get("function")).get("name") or "")
    calls = model.complete_judgment(messages, tool, max_tokens)
    result = next((_mapping(call.get("arguments")) for call in calls if call.get("name") == expected_name), {})
    if not result:
        raise ValueError(f"model did not call {expected_name}")
    return result


def _manifest_rows(value: object) -> list[dict[str, str]]:
    return [
        {
            "id": str(row.get("id") or ""),
            "file": str(row.get("file") or ""),
            "source_pdf_sha256": str(row.get("source_pdf_sha256") or ""),
        }
        for row in _records(value)
    ]
