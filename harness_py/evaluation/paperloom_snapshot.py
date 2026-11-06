"""快照构建与校验：把 corpus 冻结成可复现的 benchmark 输入。"""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path

import yaml

from ..corpus.gateway import JavaCorpusGateway
from ..transport.provider_config import ProviderConfig
from ..utils.digest import file_sha256
from ..utils.models import utc_now_iso
from .judge_model import MiniMaxJudgeModel
from .paperloom_common import (
    CASE_LAYOUT_VERSION,
    DATASET_ID,
    EVIDENCE_TYPES,
    EXPECTED_PAPER_COUNT,
    MIN_PASSAGE_CHARS,
    PreparationError,
    QUERY_PROMPT_VERSION,
    SNAPSHOT_SCHEMA_VERSION,
    SOURCE_ALIGNED_QUERY_SCHEMA_VERSION,
    ScannedPaper,
    _identity_text,
    _mapping,
    _normalize_text,
    _records,
    _strings,
    _text_sha256,
)
from .paperloom_prepare import _generate_target_question, _load_config, build_agent_cases, build_targets
from .paperloom_product_api import _ProductApi, _credentials


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


def _expected_titles(config: dict[str, object]) -> dict[str, str]:
    titles = {
        str(row.get("id") or ""): str(row.get("title") or "").strip()
        for row in _records(config.get("papers"))
    }
    if len(titles) != EXPECTED_PAPER_COUNT or any(not title for title in titles.values()):
        raise PreparationError("config", "EXPECTED_PAPER_TITLE_MISSING", "every paper requires a canonical title")
    return titles


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

    if len(cases) != 17:
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


def _load_source_aligned_queries(
    path: str | Path,
    snapshot: dict[str, object],
) -> dict[str, str]:
    source = Path(path)
    try:
        value = _mapping(yaml.safe_load(source.read_text(encoding="utf-8")))
    except (OSError, yaml.YAMLError) as error:
        raise PreparationError(
            "run",
            "SOURCE_ALIGNED_QUERY_INVALID",
            f"{source}: {error}",
        ) from error
    if value.get("schema_version") != SOURCE_ALIGNED_QUERY_SCHEMA_VERSION:
        raise PreparationError("run", "SOURCE_ALIGNED_QUERY_INVALID", "schema_version")
    if value.get("dataset_id") != snapshot.get("dataset_id"):
        raise PreparationError("run", "SOURCE_ALIGNED_QUERY_INVALID", "dataset_id")
    rows = _mapping(value.get("targets"))
    targets = _mapping(snapshot.get("targets"))
    if set(rows) != set(targets):
        raise PreparationError("run", "SOURCE_ALIGNED_QUERY_INVALID", "targets")
    queries: dict[str, str] = {}
    for target_id, row in rows.items():
        record = _mapping(row)
        expected_hash = _text_sha256(str(_mapping(targets[target_id]).get("query") or ""))
        if record.get("original_query_sha256") != expected_hash:
            raise PreparationError(
                "run",
                "SOURCE_ALIGNED_QUERY_INVALID",
                f"{target_id}: original_query_sha256",
            )
        query = record.get("source_aligned_query")
        if not isinstance(query, str) or not query or query != query.strip():
            raise PreparationError(
                "run",
                "SOURCE_ALIGNED_QUERY_INVALID",
                f"{target_id}: source_aligned_query",
            )
        queries[target_id] = query
    return queries


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
