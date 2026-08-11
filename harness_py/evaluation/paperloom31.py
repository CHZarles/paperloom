from __future__ import annotations

import hashlib
import json
import math
import os
import re
import subprocess
import time
import unicodedata
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from uuid import uuid4

import httpx
import yaml

from ..corpus.gateway import JavaCorpusGateway
from ..corpus.tools import ReadingCorpusTools
from ..orchestration.conversation import ConversationState
from ..orchestration.live_chat import LiveResearchChatHarness
from ..orchestration.research_contract import (
    ActionRequested,
    AnswerContract,
    Phase,
    ProtocolFacts,
    ProtocolState,
    SubmissionIssueClass,
    SubmissionRequested,
    ValidationIssue,
    decide,
    validate_submission,
)
from ..orchestration.runtime import build_harness_runtime
from ..transport.provider_config import ProviderConfig
from ..utils.models import GoldenDataset, as_list, child_map, utc_now_iso
from .judge_model import MiniMaxJudgeModel


CONFIG_SCHEMA_VERSION = "paperloom-benchmark-config/v1"
DATASET_ID = "paperloom-31-v1"
EXPECTED_PAPER_COUNT = 31
UPLOAD_CHUNK_BYTES = 5 * 1024 * 1024
SNAPSHOT_SCHEMA_VERSION = "paperloom-product-snapshot/v2"
QUERY_PROMPT_VERSION = "paperloom-query-generator-v3"
CASE_LAYOUT_VERSION = "paperloom-agent-case-layout-v4"
EVIDENCE_TYPES = {"PASSAGE", "TABLE", "FIGURE"}
MIN_PASSAGE_CHARS = 220
MIN_ANSWER_SPAN_CHARS = 40
RUN_SCHEMA_VERSION = "paperloom-benchmark-run/v2"


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


def scan_papers(
    papers_dir: str | Path,
    *,
    repo_root: str | Path = ".",
    expected_count: int = EXPECTED_PAPER_COUNT,
) -> list[ScannedPaper]:
    root = Path(repo_root).resolve()
    source = Path(papers_dir).resolve()
    paths = sorted(source.glob("*.pdf"), key=lambda item: item.name.casefold())
    if len(paths) != expected_count:
        raise PreparationError(
            "scan",
            "PDF_COUNT_MISMATCH",
            f"expected={expected_count}, actual={len(paths)}, directory={source}",
        )

    papers: list[ScannedPaper] = []
    for path in paths:
        try:
            relative = path.relative_to(root).as_posix()
        except ValueError as error:
            raise PreparationError(
                "scan", "PDF_OUTSIDE_REPOSITORY", str(path)
            ) from error
        md5, sha256, size_bytes = _hashes(path)
        papers.append(ScannedPaper(
            key=_paper_key(path.stem),
            path=path,
            relative_path=relative,
            md5=md5,
            sha256=sha256,
            size_bytes=size_bytes,
        ))

    _require_unique(papers, "key", lambda item: item.key)
    _require_unique(papers, "PDF content", lambda item: item.md5)
    return papers


def ensure_benchmark_config(
    path: str | Path,
    papers: list[ScannedPaper],
    *,
    generator_provider: str,
    generator_model: str,
) -> dict[str, object]:
    target = Path(path)
    desired_papers = [paper.config_entry() for paper in papers]
    if target.exists():
        with target.open("r", encoding="utf-8") as handle:
            existing = yaml.safe_load(handle) or {}
        if not isinstance(existing, dict):
            raise PreparationError("config", "CONFIG_INVALID", "config must be a YAML object")
        if existing.get("schema_version") != CONFIG_SCHEMA_VERSION:
            raise PreparationError("config", "CONFIG_SCHEMA_MISMATCH", str(existing.get("schema_version")))
        if existing.get("dataset_id") != DATASET_ID:
            raise PreparationError("config", "DATASET_ID_MISMATCH", str(existing.get("dataset_id")))
        if _manifest_rows(existing.get("papers")) != desired_papers:
            raise PreparationError("config", "PDF_MANIFEST_MISMATCH", str(target))
        return existing

    if not generator_provider.strip() or not generator_model.strip():
        raise PreparationError("config", "GENERATOR_IDENTITY_MISSING", "provider and model are required")
    config: dict[str, object] = {
        "schema_version": CONFIG_SCHEMA_VERSION,
        "dataset_id": DATASET_ID,
        "papers": desired_papers,
        "target_policy": {
            "per_paper": 1,
            "passage_count": 22,
            "table_count": 6,
            "figure_count": 3,
        },
        "generation": {
            "provider": generator_provider,
            "model": generator_model,
            "prompt_version": QUERY_PROMPT_VERSION,
            "temperature": 0,
            "max_attempts": 2,
        },
    }
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("x", encoding="utf-8") as handle:
        yaml.safe_dump(config, handle, allow_unicode=True, sort_keys=False)
    return config


def prepare_papers(
    papers: list[ScannedPaper],
    *,
    limit: int,
    api_base_url: str,
    env_path: str | Path = ".env",
    poll_timeout_seconds: int = 1800,
    poll_interval_seconds: float = 5.0,
) -> list[dict[str, object]]:
    if limit < 1 or limit > len(papers):
        raise PreparationError("prepare", "LIMIT_INVALID", f"limit must be between 1 and {len(papers)}")
    username, password = _credentials(env_path)

    with _ProductApi(api_base_url) as api:
        api.login(username, password)
        return [
            api.prepare(paper, poll_timeout_seconds, poll_interval_seconds)
            for paper in papers[:limit]
        ]


def create_snapshot(
    config_path: str | Path,
    papers: list[ScannedPaper],
    *,
    snapshots_dir: str | Path,
    provider: ProviderConfig,
    api_base_url: str,
    env_path: str | Path = ".env",
) -> tuple[Path, str, dict[str, object]]:
    config = _load_config(config_path, papers)
    expected_titles = _expected_titles(config)
    generation = _mapping(config.get("generation"))
    if generation.get("provider") != provider.provider or generation.get("model") != provider.model:
        raise PreparationError(
            "snapshot",
            "GENERATOR_IDENTITY_MISMATCH",
            f"config={generation.get('provider')}/{generation.get('model')}, active={provider.provider}/{provider.model}",
        )
    if generation.get("prompt_version") != QUERY_PROMPT_VERSION:
        raise PreparationError(
            "snapshot",
            "GENERATOR_PROMPT_VERSION_MISMATCH",
            f"config={generation.get('prompt_version')}, active={QUERY_PROMPT_VERSION}",
        )

    product_states = _product_states(papers, expected_titles, api_base_url, env_path)
    gateway = JavaCorpusGateway(max_response_bytes=32 * 1024 * 1024)
    model_states, candidates = export_current_model_candidates(papers, gateway)
    targets = build_targets(candidates, _mapping(config.get("target_policy")))
    generator = MiniMaxJudgeModel(provider)

    for target in targets:
        state = product_states[str(target["product_paper_id"])]
        generated = _generate_target_question(
            generator,
            target,
            int(generation.get("max_attempts") or 2),
        )
        target.update({
            "query": generated["question"],
            "expected_answer": generated["expected_answer"],
            "answer_spans": generated["answer_spans"],
            "fact_keys": generated["fact_keys"],
        })
        state["metadata_query"] = state["benchmark_title"]

    cases = build_agent_cases(targets, product_states)
    paper_snapshots: dict[str, object] = {}
    for paper in papers:
        product = product_states[paper.md5]
        model_state = model_states[paper.md5]
        paper_snapshots[paper.key] = {
            "product_paper_id": paper.md5,
            "source_pdf_sha256": paper.sha256,
            "processing_status": product.get("processingStatus"),
            "model_version": model_state.get("model_version"),
            "model_status": model_state.get("model_status"),
            "parser_identity": {
                "name": model_state.get("parser_name"),
                "version": model_state.get("parser_version"),
            },
            "index_identity": model_state.get("retrieval_index_contract"),
            "retrieval_index_status": model_state.get("retrieval_index_status"),
            "publication_status": "PUBLISHED",
            "title": product.get("benchmark_title"),
            "parsed_title": product.get("paperTitle"),
            "authors": product.get("authors"),
            "year": product.get("publicationYear"),
            "venue": product.get("venue"),
            "doi": product.get("doi"),
            "arxiv_id": product.get("arxivId"),
            "metadata_query": product.get("metadata_query"),
        }

    snapshot: dict[str, object] = {
        "schema_version": SNAPSHOT_SCHEMA_VERSION,
        "dataset_id": DATASET_ID,
        "generated_at": utc_now_iso(),
        "config_sha256": file_sha256(config_path),
        "generator": {
            "provider": provider.provider,
            "model": provider.model,
            "prompt_versions": [QUERY_PROMPT_VERSION],
            "case_layout_version": CASE_LAYOUT_VERSION,
            "temperature": 0,
            "max_attempts": int(generation.get("max_attempts") or 2),
        },
        "candidate_summary": {
            "total": len(candidates),
            "by_type": {
                location_type: sum(candidate["location_type"] == location_type for candidate in candidates)
                for location_type in sorted(EVIDENCE_TYPES)
            },
        },
        "papers": paper_snapshots,
        "targets": {str(target["target_id"]): target for target in targets},
        "agent_cases": cases,
    }
    validate_snapshot(snapshot)
    canonical = json.dumps(snapshot, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    snapshot_sha256 = hashlib.sha256(canonical).hexdigest()
    target_path = Path(snapshots_dir) / f"{snapshot_sha256}.json"
    target_path.parent.mkdir(parents=True, exist_ok=True)
    if target_path.exists():
        raise PreparationError("snapshot", "SNAPSHOT_ALREADY_EXISTS", str(target_path))
    with target_path.open("x", encoding="utf-8") as handle:
        json.dump(snapshot, handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")
    return target_path, snapshot_sha256, snapshot


def export_current_model_candidates(
    papers: list[ScannedPaper],
    gateway: JavaCorpusGateway,
) -> tuple[dict[str, dict[str, object]], list[dict[str, object]]]:
    model_states: dict[str, dict[str, object]] = {}
    candidates: list[dict[str, object]] = []
    for paper in papers:
        response = gateway.post(
            "/internal/v1/corpus/locations/export",
            {"paper_id": paper.md5},
            timeout_seconds=60,
        )
        if response.get("model_status") != "READING_MODEL_READY" or response.get("retrieval_index_status") != "READY":
            raise PreparationError("export", "CURRENT_MODEL_NOT_READY", paper.relative_path)
        model_states[paper.md5] = response
        for raw in _records(response.get("candidates")):
            candidate = {
                **raw,
                "paper_key": paper.key,
                "source_span_hash": _text_sha256(str(raw.get("source_span") or "")),
            }
            _validate_candidate(candidate)
            if _eligible_candidate(candidate):
                candidates.append(candidate)
    return model_states, candidates


def build_targets(
    candidates: list[dict[str, object]],
    policy: dict[str, object],
) -> list[dict[str, object]]:
    passage_count = int(policy.get("passage_count") or 0)
    table_count = int(policy.get("table_count") or 0)
    figure_count = int(policy.get("figure_count") or 0)
    if passage_count + table_count + figure_count != EXPECTED_PAPER_COUNT or int(policy.get("per_paper") or 0) != 1:
        raise PreparationError("select", "TARGET_POLICY_INVALID", str(policy))

    figures = _first_distinct(candidates, "FIGURE", figure_count, set())
    used = {str(item["paper_key"]) for item in figures}
    tables = _first_distinct(candidates, "TABLE", table_count, used)
    used.update(str(item["paper_key"]) for item in tables)
    paper_keys = {str(item["paper_key"]) for item in candidates}
    passages: list[dict[str, object]] = []
    for paper_key in sorted(paper_keys - used):
        matching = [
            item for item in candidates
            if item["location_type"] == "PASSAGE" and item["paper_key"] == paper_key
        ]
        if not matching:
            raise PreparationError("select", "PASSAGE_TARGET_MISSING", paper_key)
        passages.append(min(matching, key=_candidate_rank))

    selected = [*figures, *tables, *passages]
    actual_types = {location_type: sum(item["location_type"] == location_type for item in selected) for location_type in EVIDENCE_TYPES}
    if len(selected) != EXPECTED_PAPER_COUNT or len({item["paper_key"] for item in selected}) != EXPECTED_PAPER_COUNT:
        raise PreparationError("select", "TARGET_COVERAGE_INVALID", str(actual_types))
    if actual_types != {"PASSAGE": passage_count, "TABLE": table_count, "FIGURE": figure_count}:
        raise PreparationError("select", "TARGET_TYPE_COUNT_INVALID", str(actual_types))
    return [
        {
            "target_id": f"target_{item['paper_key']}",
            "paper": item["paper_key"],
            "product_paper_id": item["product_paper_id"],
            "model_version": item["model_version"],
            "location_ref": item["location_ref"],
            "location_type": item["location_type"],
            "page": item["page"],
            "content": item["content"],
            "content_hash": item["content_hash"],
            "source_span_hash": item["source_span_hash"],
        }
        for item in sorted(selected, key=lambda value: str(value["paper_key"]))
    ]


def _load_config(path: str | Path, papers: list[ScannedPaper]) -> dict[str, object]:
    source = Path(path)
    if not source.exists():
        raise PreparationError("config", "CONFIG_MISSING", str(source))
    with source.open("r", encoding="utf-8") as handle:
        config = yaml.safe_load(handle) or {}
    if not isinstance(config, dict):
        raise PreparationError("config", "CONFIG_INVALID", str(source))
    if config.get("schema_version") != CONFIG_SCHEMA_VERSION or config.get("dataset_id") != DATASET_ID:
        raise PreparationError("config", "CONFIG_IDENTITY_INVALID", str(source))
    if _manifest_rows(config.get("papers")) != [paper.config_entry() for paper in papers]:
        raise PreparationError("config", "PDF_MANIFEST_MISMATCH", str(source))
    return config


def _product_states(
    papers: list[ScannedPaper],
    expected_titles: dict[str, str],
    api_base_url: str,
    env_path: str | Path,
) -> dict[str, dict[str, object]]:
    username, password = _credentials(env_path)
    with _ProductApi(api_base_url) as api:
        api.login(username, password)
        rows = {str(row.get("paperId") or ""): row for row in api.papers()}
    states: dict[str, dict[str, object]] = {}
    for paper in papers:
        row = rows.get(paper.md5)
        if not row:
            raise PreparationError("snapshot", "PRODUCT_PAPER_MISSING", paper.relative_path)
        if (
            str(row.get("processingStatus") or "").upper() != "COMPLETED"
            or not bool(row.get("searchable"))
            or str(row.get("libraryScope") or "").upper() != "GLOBAL"
        ):
            raise PreparationError("snapshot", "PRODUCT_PAPER_NOT_READY", paper.relative_path)
        parsed_title = str(row.get("paperTitle") or "").strip()
        expected_title = expected_titles[paper.key]
        if _identity_text(parsed_title) != _identity_text(expected_title):
            raise PreparationError(
                "snapshot",
                "PRODUCT_PAPER_TITLE_MISMATCH",
                f"{paper.relative_path}: expected={expected_title!r}, parsed={parsed_title!r}",
            )
        states[paper.md5] = {**row, "benchmark_title": expected_title}
    return states


def _manifest_rows(value: object) -> list[dict[str, str]]:
    return [
        {
            "id": str(row.get("id") or ""),
            "file": str(row.get("file") or ""),
            "source_pdf_sha256": str(row.get("source_pdf_sha256") or ""),
        }
        for row in _records(value)
    ]


def _expected_titles(config: dict[str, object]) -> dict[str, str]:
    titles = {
        str(row.get("id") or ""): str(row.get("title") or "").strip()
        for row in _records(config.get("papers"))
    }
    if len(titles) != EXPECTED_PAPER_COUNT or any(not title for title in titles.values()):
        raise PreparationError("config", "EXPECTED_PAPER_TITLE_MISSING", "every paper requires a canonical title")
    return titles


def _validate_candidate(candidate: dict[str, object]) -> None:
    location_type = str(candidate.get("location_type") or "").upper()
    content = str(candidate.get("content") or "")
    content_hash = str(candidate.get("content_hash") or "")
    source_span = str(candidate.get("source_span") or "")
    page = candidate.get("page")
    if location_type not in EVIDENCE_TYPES:
        raise PreparationError("export", "CANDIDATE_TYPE_INVALID", location_type)
    if not content.strip() or not isinstance(page, int) or page < 1 or not source_span.strip():
        raise PreparationError("export", "CANDIDATE_INCOMPLETE", str(candidate.get("location_ref")))
    if content_hash != _text_sha256(content):
        raise PreparationError("export", "CANDIDATE_CONTENT_HASH_MISMATCH", str(candidate.get("location_ref")))
    try:
        json.loads(source_span)
    except json.JSONDecodeError as error:
        raise PreparationError("export", "CANDIDATE_SOURCE_SPAN_INVALID", str(candidate.get("location_ref"))) from error
    candidate["location_type"] = location_type


def _eligible_candidate(candidate: dict[str, object]) -> bool:
    if candidate["location_type"] != "PASSAGE":
        return True
    section = _normalize_text(str(candidate.get("section") or ""))
    content = str(candidate["content"])
    return len(_normalize_text(content)) >= MIN_PASSAGE_CHARS and section not in {
        "references",
        "bibliography",
        "checklist",
    } and not _looks_like_bibliography(content)


def _looks_like_bibliography(content: str) -> bool:
    normalized = _normalize_text(content)
    years = len(re.findall(r"\b(?:19|20)\d{2}\b", normalized))
    markers = sum(normalized.count(marker) for marker in (" arxiv", " proceedings of ", " et al.", " conference on "))
    # ponytail: content fallback covers stale MinerU section labels; remove when passage sections are reliable.
    return years >= 4 and markers >= 4


def _candidate_rank(candidate: dict[str, object]) -> tuple[str, str]:
    raw = "|".join((
        DATASET_ID,
        str(candidate["paper_key"]),
        str(candidate["location_type"]),
        str(candidate["page"]),
        str(candidate["content_hash"]),
    ))
    return _text_sha256(raw), str(candidate["location_ref"])


def _first_distinct(
    candidates: list[dict[str, object]],
    location_type: str,
    count: int,
    excluded_papers: set[str],
) -> list[dict[str, object]]:
    selected: list[dict[str, object]] = []
    used = set(excluded_papers)
    for candidate in sorted(candidates, key=_candidate_rank):
        paper_key = str(candidate["paper_key"])
        if candidate["location_type"] != location_type or paper_key in used:
            continue
        selected.append(candidate)
        used.add(paper_key)
        if len(selected) == count:
            return selected
    raise PreparationError("select", f"{location_type}_TARGETS_INSUFFICIENT", f"expected={count}, actual={len(selected)}")


def _generate_target_question(
    model: MiniMaxJudgeModel,
    target: dict[str, object],
    max_attempts: int,
) -> dict[str, object]:
    units = _evidence_units(str(target["content"]))
    prompt = {
        "location_type": target["location_type"],
        "trusted_mineru_units": [
            {"unit_id": unit_id, "text": text}
            for unit_id, text in units.items()
        ],
    }
    tool = _function_tool(
        "submit_benchmark_question",
        {
            "question": {"type": "string"},
            "answer_unit_ids": {"type": "array", "items": {"type": "string"}, "minItems": 1},
            "fact_keys": {"type": "array", "items": {"type": "string"}, "minItems": 1},
        },
    )
    messages = [
        {
            "role": "system",
            "content": (
                "Generate one grounded PaperLoom benchmark item. Treat MinerU content as authoritative. "
                "Write one atomic question in Chinese answerable only from the supplied units. Avoid multi-part "
                "requests. Do not use outside knowledge or infer facts absent from those units. For comparisons, "
                "preserve the exact direction stated by the source. Preserve every scope qualifier needed to "
                "distinguish the fact, including appendix, figure or table, illustrative example versus general "
                "procedure, dataset or split, experiment phase, model size, metric, and condition. Never broaden a "
                "local fact into a global setting. answer_unit_ids must identify the exact units that fully answer "
                "the question. Do not ask for the paper title and do not mention internal IDs."
            ),
        },
        {"role": "user", "content": json.dumps(prompt, ensure_ascii=False)},
    ]
    last_error = "generator returned no structured result"
    for _attempt in range(max_attempts):
        try:
            result = _call_structured(model, messages, tool, 900)
            result["answer_spans"] = _resolve_answer_units(result.pop("answer_unit_ids", None), units)
            result["expected_answer"] = "\n\n".join(_strings(result["answer_spans"]))
            _validate_generated_answer(result, str(target["content"]), target)
            _verify_generated_question(model, result)
            return result
        except Exception as error:
            last_error = str(error)
            messages[-1] = {
                "role": "user",
                "content": json.dumps({**prompt, "previous_validation_error": last_error}, ensure_ascii=False),
            }
    raise PreparationError("generate", "TARGET_QUESTION_INVALID", f"{target['target_id']}: {last_error}")


def _verify_generated_question(model: MiniMaxJudgeModel, result: dict[str, object]) -> None:
    tool = _function_tool("submit_grounding_verdict", {
        "verdict": {"type": "string", "enum": ["PASS", "FAIL"]},
        "reason": {"type": "string"},
    })
    messages = [
        {
            "role": "system",
            "content": (
                "Verify a benchmark question against only the supplied evidence. Return PASS only when every part "
                "is directly and unambiguously answered by the evidence. Return FAIL for outside knowledge, missing "
                "requested details, reversed comparisons, unsupported singular/plural claims, ambiguous subjects, "
                "or omitted scope qualifiers that make an appendix, figure, table, example, dataset, phase, model, "
                "metric, or condition sound more general than the evidence. You must call submit_grounding_verdict."
            ),
        },
        {"role": "user", "content": json.dumps({
            "question": result["question"],
            "evidence": result["answer_spans"],
        }, ensure_ascii=False)},
    ]
    last_error: Exception | None = None
    for _attempt in range(2):
        try:
            verdict = _call_structured(model, messages, tool, 500)
            break
        except Exception as error:
            last_error = error
    else:
        raise ValueError(f"grounding verifier unavailable: {last_error}")
    if verdict.get("verdict") != "PASS":
        raise ValueError(f"grounding verifier rejected question: {verdict.get('reason')}")


def build_agent_cases(
    targets: list[dict[str, object]],
    product_states: dict[str, dict[str, object]],
) -> list[dict[str, object]]:
    ordered = sorted(targets, key=lambda item: str(item["target_id"]))
    by_type = {
        location_type: [item for item in ordered if item["location_type"] == location_type]
        for location_type in EVIDENCE_TYPES
    }
    single_targets = [by_type["TABLE"][0], by_type["FIGURE"][0], *by_type["PASSAGE"][:4]]
    cases = [
        {
            "case_id": f"single_{index:02d}",
            "case_type": "single_paper",
            "expected_contract": "RESEARCH",
            "question": f"请依据《{_paper_title(target, product_states)}》回答：{target['query']}",
            "history": [],
            "expected_outcome": "answered",
            "expected_answer": target["expected_answer"],
            "required_target_ids": [target["target_id"]],
            "expected_facts": target["fact_keys"],
            "answer_spans": target["answer_spans"],
            "citation_policy": "cite_each_required_target",
        }
        for index, target in enumerate(single_targets, start=1)
    ]

    remaining = [item for item in ordered if item not in single_targets]
    comparison_targets = remaining[:8]
    for index in range(4):
        left, right = comparison_targets[index * 2:index * 2 + 2]
        left_title = _paper_title(left, product_states)
        right_title = _paper_title(right, product_states)
        cases.append({
            "case_id": f"comparison_{index + 1:02d}",
            "case_type": "cross_paper_comparison",
            "expected_contract": "RESEARCH",
            "question": (
                f"请分别依据《{left_title}》和《{right_title}》回答：\n"
                f"1. {left['query']}\n"
                f"2. {right['query']}\n"
                "请分项回答并分别引用。"
            ),
            "history": [],
            "expected_outcome": "answered",
            "expected_answer": (
                f"《{left_title}》：{left['expected_answer']}\n\n"
                f"《{right_title}》：{right['expected_answer']}"
            ),
            "required_target_ids": [left["target_id"], right["target_id"]],
            "expected_facts": [
                *[f"{left['paper']}:{fact}" for fact in _strings(left["fact_keys"])],
                *[f"{right['paper']}:{fact}" for fact in _strings(right["fact_keys"])],
            ],
            "answer_spans": [*_strings(left["answer_spans"]), *_strings(right["answer_spans"])],
            "citation_policy": "cite_each_required_target",
        })

    follow_target = remaining[8]
    cases.append({
        "case_id": "follow_up_01",
        "case_type": "follow_up",
        "expected_contract": "RESEARCH",
        "question": "请为刚才的结论提供对应论文中的原文证据，并保留引用。",
        "history": [
            {"role": "user", "content": follow_target["query"]},
            {"role": "assistant", "content": follow_target["expected_answer"]},
        ],
        "expected_outcome": "answered",
        "expected_answer": "\n\n".join(_strings(follow_target["answer_spans"])),
        "required_target_ids": [follow_target["target_id"]],
        "expected_facts": follow_target["fact_keys"],
        "answer_spans": follow_target["answer_spans"],
        "citation_policy": "cite_each_required_target",
    })
    cases.append({
        "case_id": "missing_evidence_01",
        "case_type": "missing_evidence_control",
        "expected_contract": "RESEARCH",
        "question": "语料库中名为 PaperLoom Missing Evidence Control 2026 的论文提出了什么核心方法？",
        "history": [],
        "expected_outcome": "insufficient_evidence",
        "expected_answer": "语料库中不存在这篇论文，不能形成有证据支持的回答。",
        "required_target_ids": [],
        "expected_facts": [],
        "answer_spans": [],
        "citation_policy": "no_citation_without_evidence",
    })
    cases.extend([
        {
            "case_id": "direct_greeting_01",
            "case_type": "direct_protocol",
            "expected_contract": "DIRECT",
            "question": "你好",
            "history": [],
            "expected_outcome": "answered",
            "expected_answer": "简短问候并说明可以帮助检索、阅读和比较论文。",
            "required_target_ids": [],
            "expected_facts": [],
            "answer_spans": [],
            "citation_policy": "no_citation_without_evidence",
        },
        {
            "case_id": "direct_clarification_01",
            "case_type": "direct_protocol",
            "expected_contract": "DIRECT",
            "question": "推荐一些论文",
            "history": [],
            "expected_outcome": "needs_clarification",
            "expected_answer": "询问希望推荐什么主题的论文。",
            "required_target_ids": [],
            "expected_facts": [],
            "answer_spans": [],
            "citation_policy": "no_citation_without_evidence",
        },
        {
            "case_id": "catalog_inventory_01",
            "case_type": "catalog_protocol",
            "expected_contract": "CATALOG",
            "question": "这个论文库有多少篇论文？",
            "history": [],
            "expected_outcome": "answered",
            "expected_answer": str(len(product_states)),
            "required_target_ids": [],
            "expected_facts": [f"paper_count:{len(product_states)}"],
            "answer_spans": [],
            "citation_policy": "no_citation_without_evidence",
        },
        {
            "case_id": "research_llm_principles_01",
            "case_type": "research_recommendation",
            "expected_contract": "RESEARCH",
            "question": "推荐和大语言模型原理相关的论文，并说明推荐理由。",
            "history": [],
            "expected_outcome": "answered",
            "expected_answer": "推荐与大语言模型原理直接相关的语料库论文，并用原文证据说明理由。",
            "required_target_ids": [],
            "expected_facts": [],
            "answer_spans": [],
            "citation_policy": "cite_recommendation_reasons",
        },
    ])
    if len(cases) != 16:
        raise PreparationError("generate", "AGENT_CASE_COUNT_INVALID", str(len(cases)))
    return cases


def _paper_title(
    target: dict[str, object],
    product_states: dict[str, dict[str, object]],
) -> str:
    title = str(product_states.get(str(target["product_paper_id"]), {}).get("benchmark_title") or "").strip()
    if not title:
        raise PreparationError("generate", "PAPER_TITLE_MISSING", str(target["target_id"]))
    return title


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


def _validate_generated_answer(
    result: dict[str, object],
    content: str,
    target: dict[str, object],
) -> None:
    question = str(result.get("question") or "").strip()
    expected_answer = str(result.get("expected_answer") or "").strip()
    spans = _strings(result.get("answer_spans"))
    fact_keys = _strings(result.get("fact_keys"))
    if not question or not expected_answer or not spans or not fact_keys:
        raise ValueError("question, expected_answer, answer_spans and fact_keys are required")
    if not re.search(r"[\u3400-\u9fff]", question):
        raise ValueError("question must be written in Chinese")
    if len(_normalize_text(" ".join(spans))) < MIN_ANSWER_SPAN_CHARS:
        raise ValueError("answer spans are too short for a grounded benchmark question")
    normalized_content = _normalize_text(content)
    if any(_normalize_text(span) not in normalized_content for span in spans):
        raise ValueError("answer span is not present in MinerU content")
    normalized_question = _normalize_text(question)
    if _normalize_text(expected_answer) in normalized_question:
        raise ValueError("question copies the expected answer")
    if normalized_question == normalized_content:
        raise ValueError("question copies the full content")
    forbidden = [str(target.get("location_ref") or ""), str(target.get("product_paper_id") or "")]
    if any(value and value in question for value in forbidden):
        raise ValueError("question contains an internal identity")
    result["question"] = question
    result["expected_answer"] = expected_answer
    result["answer_spans"] = spans
    result["fact_keys"] = fact_keys


def validate_snapshot(snapshot: dict[str, object]) -> None:
    if snapshot.get("schema_version") != SNAPSHOT_SCHEMA_VERSION:
        raise PreparationError("validate", "SNAPSHOT_SCHEMA_MISMATCH", str(snapshot.get("schema_version")))
    if _mapping(snapshot.get("generator")).get("case_layout_version") != CASE_LAYOUT_VERSION:
        raise PreparationError("validate", "SNAPSHOT_CASE_LAYOUT_MISMATCH", str(snapshot.get("generator")))
    targets = _mapping(snapshot.get("targets"))
    papers = _mapping(snapshot.get("papers"))
    cases = _records(snapshot.get("agent_cases"))
    type_counts = {
        location_type: sum(_mapping(target).get("location_type") == location_type for target in targets.values())
        for location_type in EVIDENCE_TYPES
    }
    if len(papers) != EXPECTED_PAPER_COUNT or len(targets) != EXPECTED_PAPER_COUNT:
        raise PreparationError("validate", "SNAPSHOT_CORPUS_COUNT_INVALID", f"papers={len(papers)}, targets={len(targets)}")
    if type_counts != {"PASSAGE": 22, "TABLE": 6, "FIGURE": 3}:
        raise PreparationError("validate", "SNAPSHOT_TARGET_TYPES_INVALID", str(type_counts))
    if any(not str(_mapping(paper).get("metadata_query") or "").strip() for paper in papers.values()):
        raise PreparationError("validate", "SNAPSHOT_METADATA_QUERY_MISSING", "every paper requires metadata_query")

    target_rows = {target_id: _mapping(target) for target_id, target in targets.items()}
    for target_id, target in target_rows.items():
        content = _normalize_text(str(target.get("content") or ""))
        spans = _strings(target.get("answer_spans"))
        if not spans or any(_normalize_text(span) not in content for span in spans):
            raise PreparationError("validate", "SNAPSHOT_ANSWER_SPAN_INVALID", target_id)

    if len(cases) != 16:
        raise PreparationError("validate", "SNAPSHOT_AGENT_CASE_COUNT_INVALID", str(len(cases)))
    forbidden = {
        str(target.get(key) or "")
        for target in target_rows.values()
        for key in ("target_id", "location_ref", "product_paper_id", "paper")
    }
    forbidden.discard("")
    for case in cases:
        case_id = str(case.get("case_id") or "")
        if case.get("expected_contract") not in {"DIRECT", "CATALOG", "RESEARCH"}:
            raise PreparationError("validate", "SNAPSHOT_EXPECTED_CONTRACT_INVALID", case_id)
        required_ids = _strings(case.get("required_target_ids"))
        if any(target_id not in target_rows for target_id in required_ids):
            raise PreparationError("validate", "SNAPSHOT_REQUIRED_TARGET_MISSING", case_id)
        allowed_content = "\n".join(str(target_rows[target_id].get("content") or "") for target_id in required_ids)
        if any(_normalize_text(span) not in _normalize_text(allowed_content) for span in _strings(case.get("answer_spans"))):
            raise PreparationError("validate", "SNAPSHOT_CASE_ANSWER_SPAN_INVALID", case_id)
        visible_text = "\n".join([
            str(case.get("question") or ""),
            *[str(_mapping(message).get("content") or "") for message in _records(case.get("history"))],
        ])
        if any(identity in visible_text for identity in forbidden):
            raise PreparationError("validate", "SNAPSHOT_AGENT_CASE_IDENTITY_LEAK", case_id)


def _normalize_text(value: str) -> str:
    return " ".join(unicodedata.normalize("NFKC", value).split()).casefold()


def _identity_text(value: str) -> str:
    return "".join(character for character in unicodedata.normalize("NFKC", value) if character.isalnum()).casefold()


def _evidence_units(content: str) -> dict[str, str]:
    parts = [part.strip() for part in re.split(r"\r?\n+", content) if part.strip()]
    if not parts:
        raise ValueError("MinerU content has no evidence units")
    return {f"u{index:03d}": part for index, part in enumerate(parts, start=1)}


def _resolve_answer_units(value: object, units: dict[str, str]) -> list[str]:
    unit_ids = _strings(value)
    if not unit_ids or any(unit_id not in units for unit_id in unit_ids):
        raise ValueError("answer_unit_ids contains an unknown unit")
    return [units[unit_id] for unit_id in dict.fromkeys(unit_ids)]


def _strings(value: object) -> list[str]:
    return [str(item).strip() for item in _list(value) if str(item).strip()]


def _text_sha256(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def file_sha256(path: str | Path) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run_benchmark(
    snapshot_path: str | Path,
    *,
    config_path: str | Path,
    runs_dir: str | Path,
    provider: ProviderConfig,
    api_base_url: str,
    env_path: str | Path = ".env",
) -> tuple[Path, dict[str, object]]:
    snapshot_source = Path(snapshot_path)
    snapshot = _mapping(json.loads(snapshot_source.read_text(encoding="utf-8")))
    validate_snapshot(snapshot)
    snapshot_sha256 = _canonical_sha256(snapshot)
    if snapshot_source.stem != snapshot_sha256:
        raise PreparationError("run", "SNAPSHOT_HASH_MISMATCH", str(snapshot_source))
    if file_sha256(config_path) != snapshot.get("config_sha256"):
        raise PreparationError("run", "CONFIG_HASH_MISMATCH", str(config_path))

    run_id = f"{time.strftime('%Y%m%dT%H%M%SZ', time.gmtime())}-{uuid4().hex[:8]}"
    out = Path(runs_dir) / run_id
    out.mkdir(parents=True, exist_ok=False)
    (out / "agent").mkdir()
    (out / "judge").mkdir()

    username, password = _credentials(env_path)
    with _ProductApi(api_base_url) as api:
        api.login(username, password)
        admin = api.me()
        users = api.users()
        product_rows = api.papers()
    admin_id = int(admin.get("id") or 0)
    benchmark_user = next(
        (user for user in users if str(user.get("role") or "").upper() == "USER"),
        None,
    )
    if not benchmark_user:
        raise PreparationError(
            "run", "BENCHMARK_USER_MISSING", "create one ordinary USER account before running G0"
        )
    user_id = int(benchmark_user.get("id") or 0)
    if admin_id <= 0 or user_id <= 0:
        raise PreparationError("run", "BENCHMARK_USER_INVALID", "admin and ordinary user IDs are required")

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
        "provider": provider.public_diagnostics(),
        "generator": snapshot.get("generator"),
        "judge": {**provider.public_diagnostics(), "prompt_version": "paperloom-agent-judge-v1"},
        "benchmark_user": {
            "admin_user_id": admin_id,
            "ordinary_user_id": user_id,
            "ordinary_username": benchmark_user.get("username"),
        },
        "l0": l0,
        "g0": g0,
        "l1": l1,
        "l2": l2,
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
            for stage in (l0, g0, l1, l2, l3)
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
) -> dict[str, object]:
    rows: list[dict[str, object]] = []
    errors: list[dict[str, object]] = []
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
                request_id=f"paperloom31-l2-{target_id}",
                conversation_id=f"paperloom31-l2-{target_id}",
                user_id=user_id,
                scope_paper_ids=[paper_id],
            )
            tools = ReadingCorpusTools(reader.load_metadata_dataset(), reader)
            identity = tools.find_papers_by_identity({"paper_id": paper_id})
            if identity.get("status") != "resolved":
                raise ValueError("oracle paper identity did not resolve")
            search = tools.search_paper_content({
                "paper_ids": [paper_id],
                "query_text": target.get("query"),
                "element_types": [str(target.get("location_type") or "").lower()],
                "top_k": 10,
            })
            refs = [str(child_map(item).get("location_ref") or "") for item in as_list(search.get("locations"))]
            expected_ref = str(target.get("location_ref") or "")
            rank = _rank(refs, expected_ref)
            row["rank"] = rank
            row["returned_location_refs"] = refs
            if rank is not None:
                payload = tools.evidence_payloads_by_location_ref.get(expected_ref, {})
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
                    "source_span_hash": _text_sha256(str(payload.get("source_span_json") or "")) == target.get("source_span_hash"),
                    "source_span_read": _source_quotes_cover(item.get("source_quotes"), payload.get("source_span_json")),
                    "source_quote": bool(str(quote.get("source_quote_ref") or "")),
                }
                row["checks"] = checks
                row["source_quote_ref"] = quote.get("source_quote_ref")
                row["exact_read_passed"] = all(checks.values())
        except Exception as error:
            errors.append(_failure("L2", type(error).__name__, f"{target_id}: {error}", technical=True))
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
        usage = _mapping(_mapping(run.get("control")).get("usage")) or {
            key: _mapping(run.get("diagnostics")).get(key)
            for key in ("model_calls", "prompt_tokens", "completion_tokens", "total_tokens", "elapsed_ms")
        }
        if case.get("expected_contract") == "RESEARCH":
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
        "controlled_protocol_metrics": _controlled_protocol_metrics(rows),
        "by_harness_id": by_harness_id,
        "cases": rows,
        "technical_errors": errors,
    }


def _assess_agent_case(
    case: dict[str, object],
    run: dict[str, object],
    targets: dict[str, dict[str, object]],
    scope: set[str],
    protocol: dict[str, object] | None = None,
) -> dict[str, object]:
    trace = _records(run.get("react_trace"))
    ledger = _records(_mapping(run.get("evidence_ledger")).get("items"))
    answer = _mapping(run.get("research_answer"))
    returned_refs = {
        str(child_map(location).get("location_ref") or "")
        for item in trace if item.get("tool_name") == "search_paper_content"
        for location in as_list(_mapping(item.get("result")).get("locations"))
    }
    read_by_quote = {
        str(item.get("source_quote_ref") or item.get("evidence_id") or ""): item
        for item in ledger
        if item.get("source_quote_ref") or item.get("evidence_id")
    }
    cited = {str(value) for value in as_list(answer.get("cited_source_quote_refs")) if value}
    expected_contract = str(case.get("expected_contract") or "")
    actual_contract = str(answer.get("answer_contract") or "")
    contract_match = actual_contract == expected_contract
    provenance_applicable = actual_contract == AnswerContract.RESEARCH.value
    provenance_passed = _research_provenance_passed(run, read_by_quote) if provenance_applicable else None
    required = [targets[target_id] for target_id in _strings(case.get("required_target_ids"))]
    target_checks = [
        {
            "target_id": target.get("target_id"),
            "returned": target.get("location_ref") in returned_refs,
            "read": any(item.get("location_ref") == target.get("location_ref") for item in ledger),
            "cited": any(
                quote_ref in cited and item.get("location_ref") == target.get("location_ref")
                for quote_ref, item in read_by_quote.items()
            ),
        }
        for target in required
    ]
    expected_outcome = "abstained" if case.get("expected_outcome") == "insufficient_evidence" else case.get("expected_outcome")
    leaked_ids = sorted({
        str(item.get("paper_id") or "")
        for item in [*ledger, *_records(run.get("paper_candidates"))]
        if item.get("paper_id") and item.get("paper_id") not in scope
    })
    hard_failures: list[dict[str, object]] = []
    if run.get("status") in {"FAILED_TECHNICAL", "LIMITED", "CANCELLED"}:
        hard_failures.append(_failure("L3", "AGENT_TECHNICAL_FAILURE", str(run.get("status"))))
    if expected_contract and not contract_match:
        hard_failures.append(_failure("L3", "CONTRACT_MISMATCH", f"expected={expected_contract}, actual={actual_contract}"))
    if protocol is not None and not protocol.get("passed"):
        hard_failures.append(_failure("L3", "PROTOCOL_REPLAY_FAILED", str(protocol.get("failed_event_ids") or [])))
    if provenance_applicable and not provenance_passed:
        hard_failures.append(_failure("L3", "PROVENANCE_FAILED", str(case.get("case_id"))))
    if leaked_ids:
        hard_failures.append(_failure("L3", "SCOPE_LEAK", str(leaked_ids)))
    if cited - set(read_by_quote):
        hard_failures.append(_failure("L3", "UNRESOLVABLE_CITATION", str(sorted(cited - set(read_by_quote)))))
    if expected_contract == "RESEARCH" and expected_outcome == "answered" and not cited:
        hard_failures.append(_failure("L3", "MISSING_CITATION", str(case.get("case_id"))))
    if case.get("citation_policy") == "no_citation_without_evidence" and cited:
        hard_failures.append(_failure("L3", "CITATION_WITHOUT_EVIDENCE", str(sorted(cited))))
    return {
        "status": run.get("status"),
        "actual_outcome": answer.get("outcome"),
        "expected_outcome": expected_outcome,
        "outcome_match": answer.get("outcome") == expected_outcome,
        "expected_contract": expected_contract,
        "actual_contract": actual_contract,
        "contract_match": contract_match,
        "protocol_replay": protocol,
        "provenance_applicable": provenance_applicable,
        "provenance_passed": provenance_passed,
        "target_checks": target_checks,
        "citation_integrity": bool(_mapping(run.get("citation_validation")).get("passed")) and not (cited - set(read_by_quote)),
        "scope_isolated": not leaked_ids,
        "hard_failures": hard_failures,
    }


def _research_provenance_passed(
    run: dict[str, object],
    known_source_quotes: dict[str, dict[str, object]],
) -> bool:
    submission = next((
        item
        for item in reversed(_records(run.get("react_trace")))
        if item.get("tool_name") == "submit_research_answer"
        and _mapping(item.get("result")).get("accepted") is True
    ), None)
    if submission is None:
        return False
    draft = _mapping(submission.get("arguments"))
    return validate_submission(
        AnswerContract.RESEARCH,
        draft,
        ProtocolFacts(known_source_quotes=known_source_quotes),
    ).accepted


def _assess_protocol_trace(events_path: Path) -> dict[str, object]:
    if not events_path.is_file():
        return {
            "transition_count": 0,
            "passed_count": 0,
            "pass_rate": 0.0,
            "passed": False,
            "failed_event_ids": ["TRACE_MISSING"],
        }
    try:
        events = [
            _mapping(json.loads(line))
            for line in events_path.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]
    except (OSError, json.JSONDecodeError):
        return {
            "transition_count": 0,
            "passed_count": 0,
            "pass_rate": 0.0,
            "passed": False,
            "failed_event_ids": ["TRACE_INVALID"],
        }
    transitions = [event for event in events if event.get("kind") == "protocol.transition"]
    failed: list[str] = []
    for event in transitions:
        try:
            payload = _mapping(event.get("payload"))
            decision = decide(
                _protocol_state(_mapping(payload.get("before"))),
                _protocol_event(_mapping(payload.get("event"))),
                _protocol_facts(_mapping(payload.get("facts"))),
            )
            recorded = _mapping(payload.get("decision"))
            replayed = {
                "accepted": bool(decision.model_result.get("accepted")),
                "issue_codes": _strings(decision.model_result.get("issue_codes")),
            }
            if (
                replayed != {
                    "accepted": bool(recorded.get("accepted")),
                    "issue_codes": _strings(recorded.get("issue_codes")),
                }
                or _protocol_state_payload(decision.next_state) != _mapping(payload.get("after"))
            ):
                failed.append(str(event.get("event_id") or event.get("sequence") or "unknown"))
        except (KeyError, TypeError, ValueError):
            failed.append(str(event.get("event_id") or event.get("sequence") or "unknown"))
    count = len(transitions)
    passed_count = count - len(failed)
    return {
        "transition_count": count,
        "passed_count": passed_count,
        "pass_rate": passed_count / count if count else 0.0,
        "passed": count > 0 and not failed,
        "failed_event_ids": failed,
    }


def _protocol_state(value: dict[str, object]) -> ProtocolState:
    contract = str(value.get("contract") or "")
    return ProtocolState(
        phase=Phase(str(value["phase"])),
        contract=AnswerContract(contract) if contract else None,
        submission_attempt=int(value.get("submission_attempt") or 0),
        validation_issues=tuple(_strings(value.get("validation_issues"))),
    )


def _protocol_state_payload(state: ProtocolState) -> dict[str, object]:
    return {
        "phase": state.phase.value,
        "contract": state.contract.value if state.contract else None,
        "submission_attempt": state.submission_attempt,
        "validation_issues": list(state.validation_issues),
    }


def _protocol_event(value: dict[str, object]) -> ActionRequested | SubmissionRequested:
    if value.get("kind") == "ACTION_REQUESTED":
        return ActionRequested(str(value["tool_name"]))
    issue_class = str(value.get("issue_class") or "")
    resolved_class = SubmissionIssueClass(issue_class) if issue_class else None
    issues = tuple(
        ValidationIssue(
            code=str(item["code"]),
            issue_class=resolved_class or SubmissionIssueClass.FORMAT_ISSUE,
            block_ids=tuple(_strings(item.get("block_ids"))),
            unknown_source_quote_refs=tuple(_strings(item.get("unknown_source_quote_refs"))),
        )
        for item in _records(value.get("issues"))
    )
    return SubmissionRequested(
        contract=AnswerContract(str(value["contract"])),
        payload={},
        accepted=bool(value.get("accepted")),
        issue_class=resolved_class,
        issue_codes=tuple(_strings(value.get("issue_codes"))),
        issues=issues,
    )


def _protocol_facts(value: dict[str, object]) -> ProtocolFacts:
    return ProtocolFacts(
        known_source_quotes={ref: {} for ref in _strings(value.get("known_source_quote_refs"))},
        catalog_results={ref: {} for ref in _strings(value.get("catalog_result_refs"))},
        sibling_tool_names=tuple(_strings(value.get("sibling_tool_names"))),
    )


def _controlled_protocol_metrics(rows: list[dict[str, object]]) -> dict[str, object]:
    contract_rows = [row for row in rows if row.get("expected_contract")]
    transition_count = sum(
        int(_mapping(row.get("protocol_replay")).get("transition_count") or 0)
        for row in rows
    )
    replayed_count = sum(
        int(_mapping(row.get("protocol_replay")).get("passed_count") or 0)
        for row in rows
    )
    research_rows = [row for row in rows if row.get("provenance_applicable")]
    return {
        "contract_case_count": len(contract_rows),
        "contract_accuracy": (
            sum(bool(row.get("contract_match")) for row in contract_rows) / len(contract_rows)
            if contract_rows else 0.0
        ),
        "protocol_transition_count": transition_count,
        "protocol_replay_pass_rate": replayed_count / transition_count if transition_count else 0.0,
        "completed_research_run_count": len(research_rows),
        "provenance_pass_rate": (
            sum(row.get("provenance_passed") is True for row in research_rows) / len(research_rows)
            if research_rows else 0.0
        ),
    }


def _judge_agent_case(
    judge: MiniMaxJudgeModel,
    case: dict[str, object],
    run: dict[str, object],
) -> dict[str, object]:
    answer = _mapping(run.get("research_answer"))
    ledger = _records(_mapping(run.get("evidence_ledger")).get("items"))
    tool = _function_tool("submit_agent_judgment", {
        "answer_quality": {"type": "string", "enum": ["PASS", "FAIL", "UNCERTAIN"]},
        "grounding": {"type": "string", "enum": ["PASS", "FAIL", "UNCERTAIN"]},
        "reason": {"type": "string"},
    })
    recommendation = case.get("case_type") == "research_recommendation"
    instruction = (
        "Judge an open-ended paper recommendation only from the user question, actual answer, and cited evidence. "
        "PASS answer_quality when the recommended papers are directly relevant to the requested topic and each "
        "recommendation gives a substantive reason. PASS grounding only when the material recommendation reasons "
        "are supported by the cited evidence. Do not require one predetermined paper or fact."
        if recommendation else
        "Judge one benchmark answer only from the supplied expected answer, trusted answer spans, and actual cited "
        "evidence. PASS answer_quality when the requested facts are conveyed without contradiction; wording need "
        "not match. PASS grounding only when material claims are supported by cited evidence."
    )
    messages = [
        {
            "role": "system",
            "content": instruction,
        },
        {"role": "user", "content": json.dumps({
            "question": case.get("question"),
            "expected_outcome": case.get("expected_outcome"),
            "expected_answer": case.get("expected_answer"),
            "trusted_answer_spans": case.get("answer_spans"),
            "actual_outcome": answer.get("outcome"),
            "actual_answer": answer.get("markdown"),
            "cited_evidence": [
                {"paper_id": item.get("paper_id"), "location_ref": item.get("location_ref"), "text": item.get("span_text")}
                for item in ledger
                if (item.get("source_quote_ref") or item.get("evidence_id")) in as_list(answer.get("cited_source_quote_refs"))
            ],
        }, ensure_ascii=False)},
    ]
    started = time.monotonic()
    result = _call_structured(judge, messages, tool, 900)
    return {"status": "completed", "latency_ms": round((time.monotonic() - started) * 1000), **result}


def _retrieval_metrics(rows: list[dict[str, object]], cutoffs: tuple[int, ...]) -> dict[str, object]:
    count = len(rows)
    return {
        "count": count,
        **{f"recall_at_{cutoff}": sum(isinstance(row.get("rank"), int) and int(row["rank"]) <= cutoff for row in rows) / count if count else 0.0 for cutoff in cutoffs},
        "mrr": sum(1 / int(row["rank"]) for row in rows if isinstance(row.get("rank"), int)) / count if count else 0.0,
    }


def _rank(values: list[str], expected: str) -> int | None:
    try:
        return values.index(expected) + 1
    except ValueError:
        return None


def _source_quotes_cover(quotes: object, expected: object) -> bool:
    try:
        expected_value = json.loads(str(expected or ""))
        actual_values = [
            json.loads(str(child_map(quote).get("source_span_json") or ""))
            for quote in as_list(quotes)
        ]
    except json.JSONDecodeError:
        return False
    if not isinstance(expected_value, dict) or not actual_values:
        return False
    spans = [span for value in actual_values if isinstance(value, dict) for span in as_list(value.get("spans"))]
    return (
        all(value.get("locationType") == expected_value.get("locationType") for value in actual_values)
        and all(value.get("sourceObjectId") == expected_value.get("sourceObjectId") for value in actual_values)
        and all(span in spans for span in as_list(expected_value.get("spans")))
    )


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


def _canonical_sha256(value: object) -> str:
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


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


class _ProductApi:
    def __init__(self, base_url: str) -> None:
        self.client = httpx.Client(
            base_url=base_url.rstrip("/"),
            timeout=httpx.Timeout(600.0, connect=5.0),
            trust_env=False,
        )

    def __enter__(self) -> _ProductApi:
        return self

    def __exit__(self, *_args: object) -> None:
        self.client.close()

    def login(self, username: str, password: str) -> None:
        payload = self._request("POST", "/users/login", json={"username": username, "password": password})
        token = str(_mapping(payload.get("data")).get("token") or "")
        if not token:
            raise PreparationError("login", "TOKEN_MISSING", "login response did not contain a token")
        self.client.headers["Authorization"] = f"Bearer {token}"

    def papers(self) -> list[dict[str, object]]:
        return _records(self._request("GET", "/papers/uploads").get("data"))

    def me(self) -> dict[str, object]:
        return _mapping(self._request("GET", "/users/me").get("data"))

    def users(self) -> list[dict[str, object]]:
        return _records(self._request("GET", "/admin/users").get("data"))

    def prepare(
        self,
        paper: ScannedPaper,
        poll_timeout_seconds: int,
        poll_interval_seconds: float,
    ) -> dict[str, object]:
        row = self._paper(paper.md5)
        if row and int(row.get("sourceFileSizeBytes") or 0) != paper.size_bytes:
            raise PreparationError("upload", "EXISTING_FILE_MISMATCH", paper.relative_path)

        if not row:
            self._upload_missing_chunks(paper, set())
            self._request("POST", "/papers/upload/merge", json={
                "paperId": paper.md5,
                "paperTitle": paper.path.name,
            })
        elif not _upload_completed(row):
            if str(row.get("uploadStatus") or "").upper() != "MERGING" and row.get("uploadStatus") != 2:
                uploaded = self._uploaded_chunks(paper.md5)
                self._upload_missing_chunks(paper, uploaded)
                self._request("POST", "/papers/upload/merge", json={
                    "paperId": paper.md5,
                    "paperTitle": paper.path.name,
                })
        elif str(row.get("processingStatus") or "").upper() == "FAILED":
            self._request("POST", f"/papers/{paper.md5}/processing/retry")

        ready = self._wait_until_ready(paper, poll_timeout_seconds, poll_interval_seconds)
        if str(ready.get("libraryScope") or "").upper() != "GLOBAL":
            self._request("POST", f"/admin/papers/{paper.md5}/publication")
        return {
            "paper_key": paper.key,
            "paper_id": paper.md5,
            "file": paper.relative_path,
            "source_pdf_sha256": paper.sha256,
            "processing_status": ready.get("processingStatus"),
            "retrieval_indexed_location_count": ready.get("retrievalIndexedLocationCount"),
            "publication_status": "PUBLISHED",
        }

    def _upload_missing_chunks(self, paper: ScannedPaper, uploaded: set[int]) -> None:
        total_chunks = math.ceil(paper.size_bytes / UPLOAD_CHUNK_BYTES)
        with paper.path.open("rb") as handle:
            for chunk_index in range(total_chunks):
                chunk = handle.read(UPLOAD_CHUNK_BYTES)
                if chunk_index in uploaded:
                    continue
                self._request(
                    "POST",
                    "/papers/upload/chunk",
                    data={
                        "paperId": paper.md5,
                        "chunkIndex": str(chunk_index),
                        "totalSize": str(paper.size_bytes),
                        "paperTitle": paper.path.name,
                        "totalChunks": str(total_chunks),
                    },
                    files={"file": (paper.path.name, chunk, "application/pdf")},
                )

    def _uploaded_chunks(self, paper_id: str) -> set[int]:
        payload = self._request("GET", "/papers/upload/status", params={"paperId": paper_id})
        return {
            int(value)
            for value in _list(_mapping(payload.get("data")).get("uploaded"))
            if isinstance(value, int) or str(value).isdigit()
        }

    def _wait_until_ready(
        self,
        paper: ScannedPaper,
        timeout_seconds: int,
        interval_seconds: float,
    ) -> dict[str, object]:
        deadline = time.monotonic() + timeout_seconds
        last: dict[str, object] = {}
        while time.monotonic() < deadline:
            last = self._paper(paper.md5)
            status = str(last.get("processingStatus") or "").upper()
            if status == "FAILED":
                raise PreparationError(
                    "processing",
                    "PAPER_PROCESSING_FAILED",
                    str(last.get("processingErrorMessage") or paper.relative_path),
                )
            if status == "COMPLETED" and bool(last.get("searchable")):
                return last
            time.sleep(interval_seconds)
        raise PreparationError(
            "processing",
            "PAPER_PROCESSING_TIMEOUT",
            f"paper={paper.relative_path}, status={last.get('processingStatus')}, timeout={timeout_seconds}s",
        )

    def _paper(self, paper_id: str) -> dict[str, object]:
        payload = self._request("GET", "/papers/uploads")
        return next((row for row in _records(payload.get("data")) if row.get("paperId") == paper_id), {})

    def _request(self, method: str, path: str, **kwargs: object) -> dict[str, object]:
        try:
            response = self.client.request(method, path, **kwargs)
        except httpx.HTTPError as error:
            raise PreparationError("http", "PRODUCT_API_UNAVAILABLE", str(error)) from error
        try:
            payload = response.json()
        except ValueError:
            payload = {}
        if response.is_error:
            detail = str(_mapping(payload).get("message") or response.text[:500] or response.reason_phrase)
            raise PreparationError("http", f"HTTP_{response.status_code}", f"{path}: {detail}")
        if not isinstance(payload, dict):
            raise PreparationError("http", "PRODUCT_RESPONSE_INVALID", path)
        return payload


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


def _upload_completed(row: dict[str, object]) -> bool:
    return row.get("uploadStatus") in {1, "COMPLETED"}


def _credentials(env_path: str | Path) -> tuple[str, str]:
    env = _read_env(env_path)
    username = (os.getenv("PAPERLOOM_BENCHMARK_USERNAME") or env.get("ADMIN_BOOTSTRAP_USERNAME") or "admin").strip()
    password = (
        os.getenv("PAPERLOOM_BENCHMARK_PASSWORD")
        or env.get("PAPERLOOM_SMOKE_PASSWORD")
        or env.get("ADMIN_BOOTSTRAP_PASSWORD")
        or ""
    ).strip()
    if not password:
        raise PreparationError("login", "ADMIN_PASSWORD_MISSING", "set PAPERLOOM_BENCHMARK_PASSWORD")
    return username, password


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


def _mapping(value: object) -> dict[str, object]:
    return value if isinstance(value, dict) else {}


def _list(value: object) -> list[object]:
    return value if isinstance(value, list) else []


def _records(value: object) -> list[dict[str, object]]:
    return [item for item in _list(value) if isinstance(item, dict)]
