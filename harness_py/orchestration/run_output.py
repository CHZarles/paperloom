from __future__ import annotations

"""Build the stable product Run and project tool progress/trace payloads."""

from datetime import UTC, datetime

from ..core.models import RUN_TRACE_SCHEMA_VERSION, JsonMap, as_list, child_map, stable_id
from ..corpus.tools import ReadingCorpusTools
from .research_contract import CITATION_RE, FINAL_TOOL_NAME


def build_harness_run(
    *,
    case_id: str,
    final: JsonMap,
    prior_evidence: dict[str, JsonMap],
    corpus: ReadingCorpusTools,
    trace: list[JsonMap],
    skills_used: list[str],
    started_at: str,
    duration_ms: int,
    diagnostics: JsonMap,
    harness_id: str,
) -> JsonMap:
    outcome = str(final["outcome"])
    status = {
        "answered": "COMPLETED",
        "needs_clarification": "NEEDS_CLARIFICATION",
        "partial": "INCOMPLETE_PRECISE",
        "abstained": "INCOMPLETE_PRECISE",
    }[outcome]
    raw_markdown = str(final["markdown"]).strip()
    cited_ids = _unique(CITATION_RE.findall(raw_markdown))
    all_evidence = {**prior_evidence, **corpus.observations_by_evidence_id}
    cited_evidence = {
        evidence_id: all_evidence[evidence_id]
        for evidence_id in cited_ids
        if evidence_id in all_evidence
    }
    markdown = _render_citations(raw_markdown, cited_ids, cited_evidence)
    selected_paper_ids = _unique(
        str(cited_evidence[evidence_id].get("paper_id") or "")
        for evidence_id in cited_ids
        if cited_evidence.get(evidence_id, {}).get("paper_id")
    )
    answer = {
        "answer_id": stable_id("answer", case_id),
        "question_id": case_id,
        "status": status,
        "outcome": outcome,
        "answer_type": skills_used[-1] if skills_used else "conversation",
        "summary": markdown[:400],
        "markdown": markdown,
        "fields": child_map(final.get("fields")),
        "cited_evidence_ids": cited_ids,
    }
    evidence_items = list(corpus.observations_by_evidence_id.values())
    for evidence_id, item in cited_evidence.items():
        if evidence_id not in corpus.observations_by_evidence_id:
            evidence_items.append(item)
    return {
        "schema_version": RUN_TRACE_SCHEMA_VERSION,
        "run_id": stable_id("run", case_id),
        "question_id": case_id,
        "case_id": case_id,
        "harness_id": harness_id,
        "started_at": started_at,
        "completed_at": _now(),
        "status": status,
        "result_status": status,
        "memory_update": {
            "selected_paper_ids": selected_paper_ids,
            "selected_evidence_ids": cited_ids,
        },
        "skills_used": skills_used,
        "react_trace": trace,
        "paper_candidates": _paper_candidates(trace),
        "evidence_ledger": {
            "ledger_id": stable_id("ledger", case_id),
            "question_id": case_id,
            "items": evidence_items,
            "coverage": [],
            "rejected_items": [],
            "missing_evidence": [],
        },
        "citation_validation": {
            "passed": True,
            "cited_evidence_ids": cited_ids,
            "corpus_tools_used": any(
                item["tool_name"] in _corpus_tool_names(corpus) for item in trace
            ),
        },
        "research_answer": answer,
        "final_answer": answer,
        "diagnostics": {
            "finish_reason": "model_submitted_answer",
            "tool_call_count": sum(
                1 for item in trace if item["tool_name"] != FINAL_TOOL_NAME
            ),
            "skills_used": skills_used,
            **diagnostics,
            "duration_ms": duration_ms,
        },
    }


def tool_trace_item(
    call_id: str,
    name: str,
    arguments: JsonMap,
    payload: JsonMap,
) -> JsonMap:
    return {
        "tool_call_id": call_id,
        "tool_name": name,
        "arguments": arguments,
        "result": payload,
    }


def progress_input(tool_name: str, arguments: JsonMap) -> JsonMap:
    if tool_name == "search_paper_candidates":
        return {
            "query": arguments.get("query_text") or arguments.get("query"),
            "limit": arguments.get("limit"),
        }
    if tool_name == "find_reading_locations":
        return {
            "paperIds": as_list(arguments.get("paper_ids")),
            "query": arguments.get("query_text"),
            "limit": arguments.get("top_k"),
        }
    if tool_name == "read_locations":
        location_refs = as_list(arguments.get("location_refs"))
        return {"locationRefs": location_refs, "locationCount": len(location_refs)}
    if tool_name == "get_citation_edges":
        return {"paperId": arguments.get("paper_id")}
    if tool_name == "get_research_skill":
        return {"skillId": arguments.get("skill_id")}
    return {
        key: value
        for key, value in arguments.items()
        if key in {"paper_id", "paper_ids", "query", "query_text", "limit", "top_k"}
    }


def progress_output(tool_name: str, payload: JsonMap) -> JsonMap:
    if tool_name == "search_paper_candidates":
        candidates = [child_map(item) for item in as_list(payload.get("candidates"))]
        return {
            "resultCount": len(candidates),
            "papers": [
                {"paperId": item.get("paper_id"), "title": item.get("title")}
                for item in candidates[:50]
            ],
        }
    if tool_name == "find_reading_locations":
        locations = [child_map(item) for item in as_list(payload.get("locations"))]
        return {
            "resultCount": len(locations),
            "locations": [
                {
                    "paperId": item.get("paper_id"),
                    "title": item.get("title"),
                    "section": item.get("section"),
                    "page": item.get("page"),
                    "locationRef": item.get("location_ref"),
                }
                for item in locations[:50]
            ],
        }
    if tool_name == "read_locations":
        items = [child_map(item) for item in as_list(payload.get("items"))]
        return {
            "readCount": len(items),
            "evidenceCount": len(items),
            "pages": _unique(
                item.get("page") for item in items
                if item.get("page") not in {None, "", "unknown"}
            ),
            "evidence": [
                {
                    "evidenceId": item.get("evidence_id"),
                    "evidenceRef": item.get("evidence_id"),
                    "paperId": item.get("paper_id"),
                    "title": item.get("title"),
                    "originalFilename": item.get("original_filename"),
                    "section": item.get("section"),
                    "page": item.get("page"),
                    "pageEndNumber": item.get("page_end"),
                    "locationRef": item.get("location_ref"),
                    "elementType": item.get("element_type"),
                    "sourceKind": item.get("source_kind"),
                    "bboxJson": item.get("bbox_json") or item.get("bbox_or_cell_ref"),
                    "parserName": item.get("parser_name"),
                    "parserVersion": item.get("parser_version"),
                    "tableId": item.get("table_id"),
                    "figureId": item.get("figure_id"),
                    "formulaId": item.get("formula_id"),
                    "pdfEvidenceAvailable": item.get("pdf_evidence_available"),
                    "pageScreenshotAvailable": item.get("page_screenshot_available"),
                    "tableScreenshotAvailable": item.get("table_screenshot_available"),
                    "figureScreenshotAvailable": item.get("figure_screenshot_available"),
                    "assetWarnings": item.get("asset_warnings") or [],
                    "quote": str(item.get("span_text") or "")[:300],
                }
                for item in items[:50]
            ],
        }
    if tool_name == "get_citation_edges":
        return {"edgeCount": len(as_list(payload.get("edges")))}
    if tool_name == "get_research_skill":
        return {"skillId": payload.get("skill_id"), "found": "error" not in payload}
    return {
        "resultCount": max(
            len(as_list(payload.get("items"))),
            len(as_list(payload.get("matches"))),
            len(as_list(payload.get("papers"))),
        ),
        "error": payload.get("error"),
    }


def progress_evidence_ids(payload: JsonMap) -> list[str]:
    return _unique(
        child_map(item).get("evidence_id")
        for item in as_list(payload.get("items"))
        if child_map(item).get("evidence_id")
    )


def _render_citations(
    markdown: str,
    cited_ids: list[str],
    evidence: dict[str, JsonMap],
) -> str:
    numbers = {evidence_id: index for index, evidence_id in enumerate(cited_ids, start=1)}
    rendered = CITATION_RE.sub(lambda match: f"[{numbers[match.group(1)]}]", markdown)
    if not cited_ids:
        return rendered
    sources = []
    for evidence_id in cited_ids:
        item = evidence[evidence_id]
        location = ", ".join(
            part for part in [
                str(item.get("section") or "").strip(),
                f"p. {item.get('page')}" if item.get("page") not in {None, "", "unknown"} else "",
            ]
            if part
        )
        sources.append(
            f"[{numbers[evidence_id]}] {item.get('title') or item.get('paper_id')}"
            + (f", {location}" if location else "")
        )
    return rendered + "\n\nSources\n" + "\n".join(sources)


def _paper_candidates(trace: list[JsonMap]) -> list[JsonMap]:
    candidates: dict[str, JsonMap] = {}
    for item in trace:
        result = child_map(item.get("result"))
        for raw in [
            *as_list(result.get("candidates")),
            *as_list(result.get("matches")),
            *as_list(result.get("papers")),
        ]:
            card = child_map(raw)
            paper_id = str(card.get("paper_id") or "")
            if paper_id:
                candidates[paper_id] = {
                    **card,
                    "evidence_id": f"paper_candidate_{paper_id}",
                    "element_type": "paper_candidate",
                    "citeable": False,
                }
    return list(candidates.values())


def _corpus_tool_names(corpus: ReadingCorpusTools) -> set[str]:
    return {
        str(child_map(tool.get("function")).get("name") or "")
        for tool in corpus.definitions()
    }


def _unique(values) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for value in values:
        text = str(value or "")
        if text and text not in seen:
            seen.add(text)
            result.append(text)
    return result


def _now() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")
