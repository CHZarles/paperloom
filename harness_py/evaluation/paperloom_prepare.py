"""基准准备：扫描论文、生成 config、挑选 target、构建 agent cases。"""

from __future__ import annotations

import json
import re
from pathlib import Path

import yaml

from .judge_model import MiniMaxJudgeModel
from .paperloom_common import (
    CONFIG_SCHEMA_VERSION,
    DATASET_ID,
    EVIDENCE_TYPES,
    EXPECTED_PAPER_COUNT,
    MIN_ANSWER_SPAN_CHARS,
    MIN_PASSAGE_CHARS,
    PreparationError,
    QUERY_PROMPT_VERSION,
    ScannedPaper,
    _call_structured,
    _function_tool,
    _hashes,
    _manifest_rows,
    _normalize_text,
    _paper_key,
    _require_unique,
    _strings,
    _text_sha256,
)
from .paperloom_product_api import _ProductApi, _credentials


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
    follow_title = _paper_title(follow_target, product_states)
    cases.append({
        "case_id": "follow_up_01",
        "case_type": "follow_up",
        "expected_contract": "RESEARCH",
        "question": "请为刚才的结论提供对应论文中的原文证据，并保留引用。",
        "history": [
            {"role": "user", "content": f"请依据《{follow_title}》回答：{follow_target['query']}"},
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
        "case_id": "follow_up_ambiguous_01",
        "case_type": "follow_up_ambiguity_control",
        "expected_contract": "DIRECT",
        "question": "请为刚才的结论提供对应论文中的原文证据，并保留引用。",
        "history": [
            {"role": "user", "content": "该论文发表于哪个会议？"},
            {"role": "assistant", "content": "Published as a conference paper at ICLR 2022"},
        ],
        "expected_outcome": "needs_clarification",
        "expected_answer": "询问用户所指的是哪篇论文。",
        "required_target_ids": [],
        "expected_facts": [],
        "answer_spans": [],
        "citation_policy": "no_citation_without_evidence",
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
    if len(cases) != 17:
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
    # Short numeric answers are valid when the source span contains the full surrounding claim.
    if len(_normalize_text(" ".join(spans))) < MIN_ANSWER_SPAN_CHARS and len(_normalize_text(content)) < MIN_PASSAGE_CHARS:
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
