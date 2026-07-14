from __future__ import annotations

import hashlib
import json
import math
import re
from collections import Counter
from dataclasses import dataclass, field
from typing import Any

from ..core.models import GoldenDataset, JsonMap, as_list, child_map
from .pages import (
    contains_normalized_phrase as _contains_normalized_phrase,
    normalize_text as _normalize,
    page_matches as _page_matches,
)


SEARCH_ELEMENT_TYPES = (
    "paragraph",
    "heading",
    "table",
    "list",
    "image",
    "figure",
    "footnote",
    "chart",
    "formula",
    "aside",
)
SEARCH_RESULT_LIMIT = 20
SEARCH_SNIPPET_CHARS = 500
PAPER_RESULT_LIMIT = 100
MODEL_REDACTED_FIELDS = {"matched_anchor_id", "matched_anchor_ids", "evidence_anchor_id"}
BROAD_QUERY_MIN_TOKENS = 6
BROAD_QUERY_BASE_CANDIDATES = 12
MULTI_PAPER_BASE_CANDIDATES = 12
BM25_K1 = 1.2
BM25_B = 0.75
SECTION_SCORE_WEIGHT = 0.5
LEAD_SCORE_WEIGHT = 0.8
LEAD_TOKEN_LIMIT = 40
PASSAGE_SCORE_WEIGHT = 1.0
PASSAGE_TOKEN_LIMIT = 80
PASSAGE_TOKEN_STRIDE = 40
ADJACENT_PARAGRAPH_SCORE_WEIGHT = 0.8
ADJACENT_PARAGRAPH_TOKEN_LIMIT = 80
PAGE_GROUNDING_CANDIDATE_LIMIT = 3
TWO_PAPER_CANDIDATE_FLOOR_LIMIT = 7
MULTI_PAPER_CANDIDATE_FLOOR_LIMIT = 3
GROUNDING_QUERY_STOPWORDS = {
    "a", "an", "and", "are", "as", "at", "by", "for", "from", "in", "is",
    "of", "on", "or", "the", "to", "with",
}


@dataclass(frozen=True)
class ToolResult:
    name: str
    payload: JsonMap


@dataclass(frozen=True)
class _Bm25Statistics:
    document_count: int
    average_length: float
    document_frequency: dict[str, int]


def model_facing_payload(value: Any) -> Any:
    if isinstance(value, dict):
        return {
            key: model_facing_payload(item)
            for key, item in value.items()
            if key not in MODEL_REDACTED_FIELDS
        }
    if isinstance(value, list):
        return [model_facing_payload(item) for item in value]
    return value


@dataclass
class ReadingDocument:
    paper_id: str
    title: str
    paper_version: str
    location_ref: str
    element_type: str
    page: Any
    section: str
    text: str
    source_kind: str
    surface_kind: str = "reading_element"
    physical_page_projection: bool = False
    matched_anchor_id: str | None = None
    matched_anchor_ids: tuple[str, ...] = ()
    original_filename: str | None = None
    bbox_json: str | None = None
    parser_name: str | None = None
    parser_version: str | None = None
    table_id: str | None = None
    figure_id: str | None = None
    formula_id: str | None = None
    page_screenshot_available: bool = False
    pdf_evidence_available: bool = False
    table_screenshot_available: bool = False
    figure_screenshot_available: bool = False

    def evidence_id(self) -> str:
        raw = f"{self.paper_id}:{self.location_ref}:{self.element_type}:{self.page}"
        return "ev_" + hashlib.sha1(raw.encode("utf-8")).hexdigest()[:16]

    def to_location_candidate(self) -> JsonMap:
        return {
            "paper_id": self.paper_id,
            "title": self.title,
            "original_filename": self.original_filename,
            "paper_version": self.paper_version,
            "location_ref": self.location_ref,
            "section": self.section or "unsectioned",
            "page": self.page if self.page is not None else "unknown",
            "element_type": self.element_type,
            "preview": self.text[:SEARCH_SNIPPET_CHARS],
        }

    def to_evidence_item(self) -> JsonMap:
        return {
            "evidence_id": self.evidence_id(),
            "matched_anchor_id": self.matched_anchor_id,
            "matched_anchor_ids": list(self.matched_anchor_ids),
            "paper_id": self.paper_id,
            "title": self.title,
            "paper_version": self.paper_version,
            "section": self.section or "unsectioned",
            "page": self.page if self.page is not None else "unknown",
            "location": self.location_ref,
            "location_ref": self.location_ref,
            "element_type": self.element_type,
            "span_text": self.text,
            "bbox_or_cell_ref": self.bbox_json,
            "bbox_json": self.bbox_json,
            "parser_name": self.parser_name,
            "parser_version": self.parser_version,
            "source_kind": self.source_kind,
            "table_id": self.table_id,
            "figure_id": self.figure_id,
            "formula_id": self.formula_id,
            "page_screenshot_available": self.page_screenshot_available,
            "pdf_evidence_available": self.pdf_evidence_available,
            "table_screenshot_available": self.table_screenshot_available,
            "figure_screenshot_available": self.figure_screenshot_available,
            "retrieval_strategy": "source_quote_reading",
            "relevance_score": 1.0,
            "evidence_quality": "verified",
            "supports_claim_ids": [],
            "refutes_claim_ids": [],
        }


@dataclass(frozen=True)
class _ScoredDocument:
    score: float
    document: ReadingDocument
    document_order: int
    matched_query_tokens: frozenset[str]


@dataclass
class ReadingCorpusTools:
    dataset: GoldenDataset
    observations_by_evidence_id: dict[str, JsonMap] = field(default_factory=dict)
    authorized_paper_ids: set[str] = field(default_factory=set)
    disclosed_location_refs: set[str] = field(default_factory=set)

    def __post_init__(self) -> None:
        self.documents = _build_documents(self.dataset)
        self.documents_by_location = {doc.location_ref: doc for doc in self.documents}

    def definitions(self) -> list[JsonMap]:
        definitions = [
            _tool_schema(
                "search_paper_candidates",
                (
                    "Search or browse candidate papers in the fixed corpus using title, abstract, "
                    "author, venue, year, and metadata. Results are authoritative for corpus counts, "
                    "inventories, identities, and metadata filters, but are not citeable paper-content "
                    "evidence. Use an empty query_text with a large limit to inspect the complete fixed "
                    "corpus in one call."
                ),
                {
                    "type": "object",
                    "properties": {
                        "query_text": {"type": "string"},
                        "paper_ids": {"type": "array", "items": {"type": "string"}},
                        "authors": {"type": "array", "items": {"type": "string"}},
                        "venues": {"type": "array", "items": {"type": "string"}},
                        "year_from": {"type": "integer"},
                        "year_to": {"type": "integer"},
                        "offset": {"type": "integer", "minimum": 0},
                        "limit": {"type": "integer", "minimum": 1, "maximum": PAPER_RESULT_LIMIT},
                    },
                    "additionalProperties": False,
                },
            ),
            _tool_schema(
                "find_papers_by_identity",
                (
                    "Resolve a specific paper from structured identity hints. Do not use this tool "
                    "for topical discovery, recommendations, or generic research questions."
                ),
                {
                    "type": "object",
                    "properties": {
                        "paper_id": {"type": "string"},
                        "title": {"type": "string"},
                        "filename": {"type": "string"},
                        "doi": {"type": "string"},
                        "arxiv_id": {"type": "string"},
                        "authors": {"type": "array", "items": {"type": "string"}},
                        "year": {"type": "integer"},
                    },
                    "additionalProperties": False,
                },
            ),
            _tool_schema(
                "find_reading_locations",
                (
                    "Find relevant locations inside previously disclosed candidate papers. Returns "
                    "non-citeable navigation previews and location refs. element_types are ranking "
                    "hints because parser labels can be noisy. Use read_locations before making "
                    "paper-content claims."
                ),
                {
                    "type": "object",
                    "required": ["paper_ids"],
                    "properties": {
                        "query_text": {"type": "string"},
                        "paper_ids": {"type": "array", "items": {"type": "string"}},
                        "section_query": {"type": "string"},
                        "element_types": {
                            "type": "array",
                            "items": {"type": "string", "enum": list(SEARCH_ELEMENT_TYPES)},
                        },
                        "page_from": {"type": "integer", "minimum": 1},
                        "page_to": {"type": "integer", "minimum": 1},
                        "top_k": {"type": "integer", "minimum": 1, "maximum": SEARCH_RESULT_LIMIT},
                    },
                    "additionalProperties": False,
                },
            ),
            _tool_schema(
                "read_locations",
                (
                    "Read exact paper content from location refs returned by find_reading_locations. "
                    "This is the only tool that creates citeable paper-content evidence."
                ),
                {
                    "type": "object",
                    "required": ["location_refs"],
                    "properties": {
                        "location_refs": {"type": "array", "items": {"type": "string"}},
                    },
                    "additionalProperties": False,
                },
            ),
        ]
        if self.dataset.citation_edges:
            definitions.append(_tool_schema(
                "get_citation_edges",
                (
                    "Traverse citation or lineage edges from a previously disclosed paper. Graph "
                    "edges are navigation metadata and do not support paper-content claims."
                ),
                {
                    "type": "object",
                    "required": ["paper_id"],
                    "properties": {
                        "paper_id": {"type": "string"},
                    },
                    "additionalProperties": False,
                },
            ))
        return definitions

    def call(self, name: str, arguments: JsonMap) -> ToolResult:
        if name == "search_paper_candidates":
            return ToolResult(name, self.search_paper_candidates(arguments))
        if name == "find_papers_by_identity":
            return ToolResult(name, self.find_papers_by_identity(arguments))
        if name == "find_reading_locations":
            return ToolResult(name, self.find_reading_locations(arguments))
        if name == "read_locations":
            return ToolResult(name, self.read_locations(arguments))
        if name == "get_citation_edges":
            return ToolResult(name, self.get_citation_edges(arguments))
        return ToolResult(name, {"error": f"unknown tool: {name}"})

    def search_paper_candidates(self, arguments: JsonMap) -> JsonMap:
        query = str(arguments.get("query_text") or "").strip()
        query_tokens = set(_tokens(query))
        allowed_ids = {str(value) for value in as_list(arguments.get("paper_ids")) if value}
        authors = {_normalize(str(value)) for value in as_list(arguments.get("authors")) if value}
        venues = {_normalize(str(value)) for value in as_list(arguments.get("venues")) if value}
        year_from = _optional_int(arguments.get("year_from"))
        year_to = _optional_int(arguments.get("year_to"))
        offset = max(0, int(arguments.get("offset") or 0))
        limit = max(1, min(int(arguments.get("limit") or 20), PAPER_RESULT_LIMIT))
        matches: list[tuple[float, str, JsonMap]] = []
        for paper_id, record in self.dataset.paper_records_by_id.items():
            identity = child_map(record.get("identity"))
            if allowed_ids and paper_id not in allowed_ids:
                continue
            if authors and not _matches_any_author(authors, as_list(identity.get("authors"))):
                continue
            if venues and _normalize(str(identity.get("venue") or "")) not in venues:
                continue
            year = _optional_int(identity.get("year"))
            if year_from is not None and (year is None or year < year_from):
                continue
            if year_to is not None and (year is None or year > year_to):
                continue
            score = _paper_relevance(query_tokens, query, paper_id, record)
            if query_tokens and score <= 0:
                continue
            matches.append((score, paper_id, record))
        matches.sort(key=lambda item: (-item[0], -(_optional_int(child_map(item[2].get("identity")).get("year")) or 0), item[1]))
        page = matches[offset:offset + limit]
        cards = [_paper_card(paper_id, record) for _, paper_id, record in page]
        # 只有先向模型公开的论文，后续才允许检索正文位置。
        self.authorized_paper_ids.update(str(card["paper_id"]) for card in cards)
        returned_through = offset + len(cards)
        return {
            "query_text": query,
            "candidates": cards,
            "matched_count": len(matches),
            "returned_count": len(cards),
            "coverage": "complete" if returned_through >= len(matches) else "truncated",
            "next_offset": returned_through if returned_through < len(matches) else None,
        }

    def find_papers_by_identity(self, arguments: JsonMap) -> JsonMap:
        hints = {
            key: value for key, value in arguments.items()
            if key in {"paper_id", "title", "filename", "doi", "arxiv_id", "authors", "year"}
            and value not in (None, "", [])
        }
        if not hints:
            return {"status": "not_found", "matches": [], "reason": "identity_hints_required"}
        matches: list[JsonMap] = []
        for paper_id, record in self.dataset.paper_records_by_id.items():
            if _matches_identity_hints(paper_id, record, hints):
                matches.append(_paper_card(paper_id, record))
        status = "not_found"
        if len(matches) == 1:
            status = "resolved"
            self.authorized_paper_ids.add(str(matches[0]["paper_id"]))
        elif len(matches) > 1:
            status = "ambiguous"
        return {"status": status, "matches": matches}

    def find_reading_locations(self, arguments: JsonMap) -> JsonMap:
        query = str(arguments.get("query_text") or "").strip()
        section_query = str(arguments.get("section_query") or "").strip()
        top_k = max(1, min(int(arguments.get("top_k") or 8), SEARCH_RESULT_LIMIT))
        paper_id_list = [str(value) for value in as_list(arguments.get("paper_ids")) if value]
        if not paper_id_list:
            return {"error": "paper_ids_required", "locations": []}
        unauthorized = [paper_id for paper_id in paper_id_list if paper_id not in self.authorized_paper_ids]
        if unauthorized:
            return {
                "error": "paper_not_authorized_for_reading",
                "unauthorized_paper_ids": unauthorized,
                "locations": [],
            }
        paper_ids = set(paper_id_list)
        element_types = set(str(value) for value in as_list(arguments.get("element_types")) if value)
        unsupported_types = sorted(element_types - set(SEARCH_ELEMENT_TYPES))
        if unsupported_types:
            return {
                "error": "unsupported_element_types",
                "unsupported_element_types": unsupported_types,
                "locations": [],
            }
        page_from = _optional_int(arguments.get("page_from"))
        page_to = _optional_int(arguments.get("page_to"))
        if page_from is not None and page_to is not None and page_from > page_to:
            return {"error": "invalid_page_range", "locations": []}
        query_token_list = _tokens(query)
        query_tokens = set(query_token_list)
        section_tokens = set(_tokens(section_query))
        all_eligible_documents: list[ReadingDocument] = []
        for document in self.documents:
            if document.paper_id not in paper_ids:
                continue
            page = _optional_int(document.page)
            if page_from is not None and (page is None or page < page_from):
                continue
            if page_to is not None and (page is None or page > page_to):
                continue
            all_eligible_documents.append(document)

        page_documents = [
            document
            for document in all_eligible_documents
            if document.surface_kind == "page"
        ]
        eligible_documents = [
            document
            for document in all_eligible_documents
            if document.surface_kind != "page"
        ]

        body_tokens = [_tokens(document.text) for document in eligible_documents]
        lead_token_lists = [tokens[:LEAD_TOKEN_LIMIT] for tokens in body_tokens]
        passage_token_lists = [
            _passage_token_windows(tokens) if document.physical_page_projection else []
            for document, tokens in zip(eligible_documents, body_tokens)
        ]
        section_token_lists = [
            [token for token in _tokens(document.section) if not token.isdigit()]
            for document in eligible_documents
        ]
        body_statistics = _bm25_statistics(body_tokens)
        lead_statistics = _bm25_statistics(lead_token_lists)
        passage_statistics = _bm25_statistics([
            passage
            for passages in passage_token_lists
            for passage in passages
        ])
        section_statistics = _bm25_statistics(section_token_lists)
        base_scores: list[float] = []
        for (
            document,
            document_body_tokens,
            document_lead_tokens,
            document_passages,
            document_section_tokens,
        ) in zip(
            eligible_documents,
            body_tokens,
            lead_token_lists,
            passage_token_lists,
            section_token_lists,
        ):
            score = (
                _bm25_score(query_token_list, document_body_tokens, body_statistics)
                if query_tokens
                else 1.0
            )
            if query_tokens:
                score += PASSAGE_SCORE_WEIGHT * max(
                    (
                        _bm25_score(query_token_list, passage, passage_statistics)
                        for passage in document_passages
                    ),
                    default=0.0,
                )
                score += LEAD_SCORE_WEIGHT * _bm25_score(
                    query_token_list,
                    document_lead_tokens,
                    lead_statistics,
                )
                score += SECTION_SCORE_WEIGHT * _bm25_score(
                    query_token_list,
                    document_section_tokens,
                    section_statistics,
                )
            if score > 0 and element_types and document.element_type in element_types:
                score += 0.25
            if section_tokens:
                score += _section_hint_score(section_query, document.section, document.text)
            if query and _normalize(query) in _normalize(document.text):
                score += 2.0
            base_scores.append(score)

        scored: list[_ScoredDocument] = []
        for index, document in enumerate(eligible_documents):
            score = base_scores[index]
            if query_tokens and score > 0:
                # Split paragraphs often carry one fact across adjacent reading elements.
                adjacent_scores = [
                    base_scores[neighbor_index]
                    for neighbor_index in (index - 1, index + 1)
                    if 0 <= neighbor_index < len(eligible_documents)
                    and eligible_documents[neighbor_index].paper_id == document.paper_id
                    and eligible_documents[neighbor_index].section == document.section
                    and eligible_documents[neighbor_index].element_type
                    == document.element_type
                    == "paragraph"
                    and (
                        not document.physical_page_projection
                        or len(body_tokens[index]) <= ADJACENT_PARAGRAPH_TOKEN_LIMIT
                    )
                    and (
                        not eligible_documents[neighbor_index].physical_page_projection
                        or len(body_tokens[neighbor_index]) <= ADJACENT_PARAGRAPH_TOKEN_LIMIT
                    )
                ]
                if adjacent_scores:
                    score += ADJACENT_PARAGRAPH_SCORE_WEIGHT * max(adjacent_scores)
            if score > 0:
                document_tokens = frozenset(body_tokens[index] + section_token_lists[index])
                scored.append(_ScoredDocument(
                    score=score,
                    document=document,
                    document_order=index,
                    matched_query_tokens=frozenset(query_tokens & document_tokens),
                ))
        scored.sort(key=lambda item: _scored_document_sort_key(item, paper_id_list))
        scored = _deduplicate_candidates(scored)
        broad_query = len(query_tokens) >= BROAD_QUERY_MIN_TOKENS
        # 多主题或多论文查询扩大候选面，避免比较任务只看到每篇论文的第一个表面匹配。
        expanded_candidate_floor = max(
            BROAD_QUERY_BASE_CANDIDATES if broad_query else 0,
            MULTI_PAPER_BASE_CANDIDATES if len(paper_id_list) > 1 else 0,
        )
        candidate_count = max(top_k, expanded_candidate_floor)
        page_scored, page_statistics = _score_page_documents(
            page_documents,
            query_token_list,
            section_query,
            paper_id_list,
        )
        page_scored = [
            item
            for item in page_scored
            if _repairs_semantic_page_grounding(item, scored)
        ]
        page_candidate_count = min(
            PAGE_GROUNDING_CANDIDATE_LIMIT,
            max(0, candidate_count - 1),
            len(page_scored),
        )
        selected_pages = _select_coverage_candidates(
            page_scored,
            paper_id_list,
            _query_term_weights(query_tokens, page_statistics),
            page_candidate_count,
            broad_query=False,
            balanced_paper_floor=True,
        ) if page_candidate_count else []
        selected = _select_coverage_candidates(
            scored,
            paper_id_list,
            _query_term_weights(query_tokens, body_statistics),
            max(1, candidate_count - len(selected_pages)),
            broad_query=broad_query,
            balanced_paper_floor=any(
                document.physical_page_projection
                for document in eligible_documents
            ),
        )
        selected = _interleave_page_candidates(selected, selected_pages)
        matched_count = len(scored) + len(page_scored)
        locations = [document.to_location_candidate() for document in selected]
        self.disclosed_location_refs.update(str(item["location_ref"]) for item in locations)
        return {
            "query_text": query,
            "locations": locations,
            "matched_count": matched_count,
            "returned_count": len(locations),
            "coverage": "complete" if len(locations) >= matched_count else "truncated",
        }

    def read_locations(self, arguments: JsonMap) -> JsonMap:
        location_refs = [str(value) for value in as_list(arguments.get("location_refs")) if value]
        if not location_refs:
            return {"error": "location_refs_required", "items": []}
        unauthorized = [ref for ref in location_refs if ref not in self.disclosed_location_refs]
        if unauthorized:
            return {
                "error": "location_not_disclosed_for_reading",
                "unauthorized_location_refs": unauthorized,
                "items": [],
            }
        docs = [self.documents_by_location[ref] for ref in location_refs if ref in self.documents_by_location]
        items = []
        seen: set[str] = set()
        for document in docs:
            if document.evidence_id() in seen:
                continue
            seen.add(document.evidence_id())
            item = document.to_evidence_item()
            # 只有真正读取过的位置才会生成可引用 evidence_id。
            self.observations_by_evidence_id[item["evidence_id"]] = item
            items.append(item)
        missing = [ref for ref in location_refs if ref not in self.documents_by_location]
        return {"items": items, "missing_location_refs": missing}

    def get_citation_edges(self, arguments: JsonMap) -> JsonMap:
        paper_id = str(arguments.get("paper_id") or "")
        if not self.dataset.citation_edges:
            return {"error": "citation_graph_unavailable", "edges": []}
        if paper_id not in self.authorized_paper_ids:
            return {"error": "paper_not_authorized_for_graph_traversal", "edges": []}
        edges = [
            dict(edge)
            for edge in self.dataset.citation_edges
            if edge.get("from_paper_id") == paper_id or edge.get("to_paper_id") == paper_id
        ]
        connected_ids = {
            str(value)
            for edge in edges
            for value in (edge.get("from_paper_id"), edge.get("to_paper_id"))
            if value in self.dataset.paper_records_by_id
        }
        self.authorized_paper_ids.update(connected_ids)
        return {
            "edges": edges,
            "papers": [
                _paper_card(candidate_id, self.dataset.paper_records_by_id[candidate_id])
                for candidate_id in sorted(connected_ids)
            ],
            "coverage": "complete",
        }


def _build_documents(dataset: GoldenDataset) -> list[ReadingDocument]:
    documents: list[ReadingDocument] = []
    anchors_by_paper = _anchors_by_paper(dataset)
    for paper_id, model in dataset.reading_models_by_paper_id.items():
        paper = dataset.paper_records_by_id.get(paper_id, {})
        identity = child_map(paper.get("identity"))
        product_db = child_map(paper.get("product_db"))
        title = str(identity.get("title") or paper_id)
        original_filename = str(product_db.get("original_filename") or "") or None
        version = str(identity.get("version_label") or model.get("model_version") or "unknown")
        diagnostics = child_map(model.get("diagnostics"))
        has_physical_page_projection = int(
            diagnostics.get("pagesBuiltFromPhysicalProjection") or 0
        ) > 0
        for element in as_list(model.get("reading_elements")):
            element_map = child_map(element)
            text = _first_text(element_map, ["searchableText", "bodyText", "captionText"])
            if not text:
                continue
            location = str(element_map.get("locationRef") or element_map.get("readingElementId") or element_map.get("id"))
            element_type = str(element_map.get("elementType") or "reading_element").lower()
            source_object_id = str(element_map.get("sourceObjectId") or "") or None
            doc = ReadingDocument(
                paper_id=paper_id,
                title=title,
                paper_version=version,
                location_ref=location,
                element_type=str(element_map.get("elementType") or "paragraph").lower(),
                page=element_map.get("pageNumber"),
                section=str(element_map.get("sectionTitle") or ""),
                text=text,
                source_kind=element_type,
                surface_kind="reading_element",
                physical_page_projection=has_physical_page_projection,
                original_filename=original_filename,
                bbox_json=str(element_map.get("bboxJson") or "") or None,
                parser_name=str(element_map.get("parserName") or model.get("parser_name") or "") or None,
                parser_version=str(element_map.get("parserVersion") or model.get("parser_version") or "") or None,
                table_id=source_object_id if element_type == "table" else None,
                figure_id=source_object_id if element_type in {"figure", "chart"} else None,
                formula_id=source_object_id if element_type == "formula" else None,
                page_screenshot_available=bool(element_map.get("pageScreenshotAvailable")),
                pdf_evidence_available=bool(element_map.get("pdfEvidenceAvailable")),
                table_screenshot_available=bool(element_map.get("tableScreenshotAvailable")),
                figure_screenshot_available=bool(element_map.get("figureScreenshotAvailable")),
            )
            documents.append(doc)
        if has_physical_page_projection:
            page_locations: dict[str, JsonMap] = {}
            for raw_location in as_list(model.get("locations")):
                location = child_map(raw_location)
                page_number = location.get("pageNumber")
                if (
                    str(location.get("locationType") or "").upper() == "PAGE"
                    and page_number is not None
                ):
                    page_locations[str(page_number)] = location
            for page in as_list(model.get("pages")):
                page_map = child_map(page)
                text = str(page_map.get("pageText") or "")
                page_number = page_map.get("pageNumber")
                if not text or page_number is None:
                    continue
                location = page_locations.get(str(page_number), {})
                location_ref = str(
                    location.get("locationRef")
                    or f"{paper_id}:page:{page_number}"
                )
                documents.append(ReadingDocument(
                    paper_id=paper_id,
                    title=title,
                    paper_version=version,
                    location_ref=location_ref,
                    element_type="page",
                    page=page_number,
                    section=str(location.get("sectionTitle") or ""),
                    text=text,
                    source_kind="page",
                    surface_kind="page",
                    original_filename=original_filename,
                    parser_name=str(page_map.get("parserName") or model.get("parser_name") or "") or None,
                    parser_version=str(page_map.get("parserVersion") or model.get("parser_version") or "") or None,
                ))
        for table in as_list(model.get("parsed_tables")):
            documents.extend(_special_document(paper_id, title, version, child_map(table), "table", "tableText"))
        for figure in as_list(model.get("parsed_figures")):
            documents.extend(_special_document(paper_id, title, version, child_map(figure), "figure", "figureText"))
        for formula in as_list(model.get("parsed_formulas")):
            formula_map = child_map(formula)
            text = " ".join(part for part in [str(formula_map.get("latex") or ""), str(formula_map.get("contextText") or "")] if part)
            if text:
                doc = ReadingDocument(
                    paper_id=paper_id,
                    title=title,
                    paper_version=version,
                    location_ref=str(formula_map.get("formulaId") or formula_map.get("elementId")),
                    element_type="formula",
                    page=formula_map.get("pageNumber"),
                    section=str(formula_map.get("sectionTitle") or ""),
                    text=text,
                    source_kind="formula",
                )
                documents.append(doc)
    _assign_anchor_owners(documents, anchors_by_paper)
    return documents


def _special_document(
    paper_id: str,
    title: str,
    version: str,
    raw: JsonMap,
    element_type: str,
    text_field: str,
) -> list[ReadingDocument]:
    text = str(raw.get(text_field) or raw.get("caption") or "")
    if not text:
        return []
    doc = ReadingDocument(
        paper_id=paper_id,
        title=title,
        paper_version=version,
        location_ref=str(raw.get(f"{element_type}Id") or raw.get("elementId")),
        element_type=element_type,
        page=raw.get("pageNumber"),
        section=str(raw.get("sectionTitle") or ""),
        text=text,
        source_kind=element_type,
        surface_kind="parsed_typed",
    )
    return [doc]


def _assign_anchor_owners(
    documents: list[ReadingDocument],
    anchors_by_paper: dict[str, list[JsonMap]],
) -> None:
    for document in documents:
        document.matched_anchor_id = None
        document.matched_anchor_ids = ()

    documents_by_paper: dict[str, list[ReadingDocument]] = {}
    for document in documents:
        documents_by_paper.setdefault(document.paper_id, []).append(document)

    owned: dict[int, list[str]] = {}
    for paper_id, anchors in anchors_by_paper.items():
        paper_documents = documents_by_paper.get(paper_id, [])
        for anchor in anchors:
            candidates = [
                document
                for document in paper_documents
                if _match_anchors(document, [anchor])
            ]
            if not candidates:
                continue
            chosen = min(
                candidates,
                key=lambda document: (
                    _anchor_surface_priority(document.surface_kind),
                    paper_documents.index(document),
                ),
            )
            owned.setdefault(id(chosen), []).append(str(anchor.get("anchor_id")))

    for document in documents:
        matched = tuple(owned.get(id(document), []))
        document.matched_anchor_ids = matched
        document.matched_anchor_id = matched[0] if matched else None


def _anchor_surface_priority(surface_kind: str) -> int:
    return {
        "reading_element": 0,
        "page": 1,
        "parsed_typed": 2,
    }.get(surface_kind, 3)


def _anchors_by_paper(dataset: GoldenDataset) -> dict[str, list[JsonMap]]:
    by_paper: dict[str, list[JsonMap]] = {}
    for anchor in dataset.anchors_by_id.values():
        by_paper.setdefault(str(anchor.get("paper_id")), []).append(anchor)
    return by_paper


def _assign_matched_anchors(document: ReadingDocument, anchors: list[JsonMap]) -> None:
    matched = _match_anchors(document, anchors)
    document.matched_anchor_ids = matched
    document.matched_anchor_id = matched[0] if matched else None


def _match_anchors(document: ReadingDocument, anchors: list[JsonMap]) -> tuple[str, ...]:
    document_text = _normalize(document.text)
    matched: list[str] = []
    for anchor in anchors:
        element = child_map(anchor.get("element"))
        page = element.get("page")
        if not _page_matches(page, document.page):
            continue
        exact_text = child_map(anchor.get("selector")).get("exact_text")
        normalized_quote = _normalize(str(exact_text or ""))
        if _contains_normalized_phrase(document_text, normalized_quote):
            matched.append(str(anchor.get("anchor_id")))
    return tuple(matched)


def _paper_card(paper_id: str, record: JsonMap) -> JsonMap:
    identity = child_map(record.get("identity"))
    abstract = str(record.get("abstract") or "").strip()
    product = child_map(record.get("product_db"))
    return {
        "paper_id": paper_id,
        "title": identity.get("title"),
        "authors": as_list(identity.get("authors")),
        "year": identity.get("year"),
        "venue": identity.get("venue"),
        "doi": identity.get("doi"),
        "arxiv_id": identity.get("arxiv_id"),
        "filename": product.get("original_filename"),
        "preview": abstract[:SEARCH_SNIPPET_CHARS],
    }


def _paper_relevance(query_tokens: set[str], query: str, paper_id: str, record: JsonMap) -> float:
    if not query_tokens:
        return 1.0
    identity = child_map(record.get("identity"))
    title = str(identity.get("title") or "")
    abstract = str(record.get("abstract") or "")
    metadata = " ".join([
        paper_id,
        " ".join(str(author) for author in as_list(identity.get("authors"))),
        str(identity.get("venue") or ""),
        str(identity.get("year") or ""),
        str(identity.get("doi") or ""),
        str(identity.get("arxiv_id") or ""),
        str(child_map(record.get("product_db")).get("original_filename") or ""),
    ])
    score = (
        3.0 * _score_tokens(query_tokens, set(_tokens(title)))
        + _score_tokens(query_tokens, set(_tokens(abstract)))
        + _score_tokens(query_tokens, set(_tokens(metadata)))
    )
    normalized_query = _normalize(query)
    if normalized_query and normalized_query in _normalize(" ".join([title, abstract, metadata])):
        score += 2.0
    return score


def _score_page_documents(
    documents: list[ReadingDocument],
    query_tokens: list[str],
    section_query: str,
    paper_id_order: list[str],
) -> tuple[list[_ScoredDocument], _Bm25Statistics]:
    body_tokens = [_tokens(document.text) for document in documents]
    lead_tokens = [tokens[:LEAD_TOKEN_LIMIT] for tokens in body_tokens]
    section_tokens = [
        [token for token in _tokens(document.section) if not token.isdigit()]
        for document in documents
    ]
    body_statistics = _bm25_statistics(body_tokens)
    lead_statistics = _bm25_statistics(lead_tokens)
    section_statistics = _bm25_statistics(section_tokens)
    query_token_set = set(query_tokens)
    normalized_query = _normalize(" ".join(query_tokens))
    scored: list[_ScoredDocument] = []
    for index, document in enumerate(documents):
        score = _bm25_score(query_tokens, body_tokens[index], body_statistics)
        score += LEAD_SCORE_WEIGHT * _bm25_score(
            query_tokens,
            lead_tokens[index],
            lead_statistics,
        )
        score += SECTION_SCORE_WEIGHT * _bm25_score(
            query_tokens,
            section_tokens[index],
            section_statistics,
        )
        if section_query:
            score += _section_hint_score(section_query, document.section, document.text)
        if normalized_query and normalized_query in _normalize(document.text):
            score += 2.0
        if score <= 0:
            continue
        document_tokens = frozenset(body_tokens[index] + section_tokens[index])
        scored.append(_ScoredDocument(
            score=score,
            document=document,
            document_order=index,
            matched_query_tokens=frozenset(query_token_set & document_tokens),
        ))
    scored.sort(key=lambda item: _scored_document_sort_key(item, paper_id_order))
    return _deduplicate_candidates(scored), body_statistics


def _interleave_page_candidates(
    semantic_documents: list[ReadingDocument],
    page_documents: list[ReadingDocument],
) -> list[ReadingDocument]:
    combined = list(semantic_documents)
    inserted_by_paper: dict[str, int] = {}
    for page in page_documents:
        first_paper_index = next(
            (
                index
                for index, document in enumerate(combined)
                if document.paper_id == page.paper_id
            ),
            -1,
        )
        if first_paper_index < 0:
            combined.append(page)
            continue
        insert_at = first_paper_index + 1 + inserted_by_paper.get(page.paper_id, 0)
        combined.insert(insert_at, page)
        inserted_by_paper[page.paper_id] = inserted_by_paper.get(page.paper_id, 0) + 1
    return combined


def _repairs_semantic_page_grounding(
    page: _ScoredDocument,
    semantic_documents: list[_ScoredDocument],
) -> bool:
    page_number = _optional_int(page.document.page)
    same_page_tokens: set[str] = set()
    for semantic in semantic_documents:
        if (
            semantic.document.paper_id == page.document.paper_id
            and _optional_int(semantic.document.page) == page_number
        ):
            same_page_tokens.update(
                set(semantic.matched_query_tokens) - GROUNDING_QUERY_STOPWORDS
            )
    page_tokens = set(page.matched_query_tokens) - GROUNDING_QUERY_STOPWORDS
    uncovered_tokens = page_tokens - same_page_tokens
    if len(uncovered_tokens) < 2:
        return False

    return any(
        semantic.document.paper_id == page.document.paper_id
        and _optional_int(semantic.document.page) != page_number
        and len(
            uncovered_tokens
            & (set(semantic.matched_query_tokens) - GROUNDING_QUERY_STOPWORDS)
        ) >= 2
        and _shares_token_span(page.document.text, semantic.document.text)
        for semantic in semantic_documents
    )


def _shares_token_span(left: str, right: str, width: int = 8) -> bool:
    left_tokens = _tokens(left)
    right_tokens = _tokens(right)
    if len(left_tokens) < width or len(right_tokens) < width:
        return False
    right_spans = {
        tuple(right_tokens[index:index + width])
        for index in range(len(right_tokens) - width + 1)
    }
    return any(
        tuple(left_tokens[index:index + width]) in right_spans
        for index in range(len(left_tokens) - width + 1)
    )


def _matches_identity_hints(paper_id: str, record: JsonMap, hints: JsonMap) -> bool:
    identity = child_map(record.get("identity"))
    product = child_map(record.get("product_db"))
    if hints.get("paper_id") and str(hints["paper_id"]) != paper_id:
        return False
    if hints.get("title") and _normalize(str(hints["title"])) not in _normalize(str(identity.get("title") or "")):
        return False
    if hints.get("filename") and _normalize(str(hints["filename"])) not in _normalize(str(product.get("original_filename") or "")):
        return False
    if hints.get("doi") and _normalize_identifier(hints["doi"]) != _normalize_identifier(identity.get("doi")):
        return False
    if hints.get("arxiv_id") and _normalize_identifier(hints["arxiv_id"]) != _normalize_identifier(identity.get("arxiv_id")):
        return False
    if hints.get("year") is not None and _optional_int(hints["year"]) != _optional_int(identity.get("year")):
        return False
    requested_authors = {
        _normalize(str(value)) for value in as_list(hints.get("authors")) if str(value).strip()
    }
    if requested_authors and not _matches_all_authors(requested_authors, as_list(identity.get("authors"))):
        return False
    return True


def _matches_any_author(requested: set[str], actual: list[Any]) -> bool:
    actual_names = [_normalize(str(value)) for value in actual]
    return any(
        requested_name in actual_name or actual_name in requested_name
        for requested_name in requested
        for actual_name in actual_names
        if requested_name and actual_name
    )


def _matches_all_authors(requested: set[str], actual: list[Any]) -> bool:
    actual_names = [_normalize(str(value)) for value in actual]
    return all(
        any(
            requested_name in actual_name or actual_name in requested_name
            for actual_name in actual_names
            if actual_name
        )
        for requested_name in requested
    )


def _select_coverage_candidates(
    scored: list[_ScoredDocument],
    paper_id_order: list[str],
    query_term_weights: dict[str, float],
    top_k: int,
    *,
    broad_query: bool,
    balanced_paper_floor: bool = False,
) -> list[ReadingDocument]:
    remaining = list(scored)
    selected: list[_ScoredDocument] = []
    covered_tokens: set[str] = set()

    def select_first(paper_id: str, predicate) -> bool:
        for index, item in enumerate(remaining):
            if item.document.paper_id == paper_id and predicate(item.document):
                selected.append(remaining.pop(index))
                covered_tokens.update(item.matched_query_tokens)
                return True
        return False

    paper_floor = 1
    if balanced_paper_floor:
        paper_floor_limit = (
            TWO_PAPER_CANDIDATE_FLOOR_LIMIT
            if len(paper_id_order) == 2
            else MULTI_PAPER_CANDIDATE_FLOOR_LIMIT
        )
        paper_floor = min(
            paper_floor_limit,
            max(1, top_k // max(1, len(paper_id_order))),
        )
    for paper_id in paper_id_order:
        if len(selected) >= top_k:
            break
        select_first(paper_id, lambda document: True)

    if broad_query or balanced_paper_floor:
        for paper_id in paper_id_order:
            if len(selected) >= top_k:
                break
            select_first(paper_id, _is_abstract_document)

    for _ in range(max(0, paper_floor - 1)):
        for paper_id in paper_id_order:
            if len(selected) >= top_k:
                break
            select_first(paper_id, lambda document: True)

    # Broad comparisons also need major-section representatives before detail passages.
    if broad_query and len(paper_id_order) > 1:
        slots_per_paper = max(1, top_k // len(paper_id_order))
        major_section_count = min(3, max(0, slots_per_paper - 2))
        for paper_id in paper_id_order:
            selected_sections: set[str] = set()
            for _ in range(major_section_count):
                if len(selected) >= top_k:
                    break
                selected_index = next(
                    (
                        index
                        for index, item in enumerate(remaining)
                        if item.document.paper_id == paper_id
                        and _is_major_section(item.document.section)
                        and _normalized_section(item.document.section) not in selected_sections
                    ),
                    -1,
                )
                if selected_index < 0:
                    break
                item = remaining.pop(selected_index)
                selected.append(item)
                covered_tokens.update(item.matched_query_tokens)
                selected_sections.add(_normalized_section(item.document.section))

    while remaining and len(selected) < top_k:
        best_index = -1
        best_gain = 0.0
        for index, item in enumerate(remaining):
            gain = sum(
                query_term_weights.get(token, 0.0)
                for token in item.matched_query_tokens - covered_tokens
            )
            if gain > best_gain:
                best_index = index
                best_gain = gain
        if best_index < 0:
            break
        item = remaining.pop(best_index)
        selected.append(item)
        covered_tokens.update(item.matched_query_tokens)

    context_slots = min(2, top_k // 6) if broad_query else 0
    relevance_slots = max(len(selected), top_k - context_slots)
    relevance_count = max(0, relevance_slots - len(selected))
    selected.extend(remaining[:relevance_count])
    del remaining[:relevance_count]

    while remaining and len(selected) < top_k:
        companion_index = -1
        neighbor_index = -1
        for index, item in enumerate(remaining):
            neighbor_index = next(
                (
                    selected_index
                    for selected_index, chosen in enumerate(selected)
                    if _is_adjacent_paragraph(item, chosen)
                ),
                -1,
            )
            if neighbor_index >= 0:
                companion_index = index
                break
        if companion_index < 0:
            break
        item = remaining.pop(companion_index)
        neighbor = selected[neighbor_index]
        insert_at = neighbor_index + (item.document_order > neighbor.document_order)
        selected.insert(insert_at, item)

    selected.extend(remaining[:max(0, top_k - len(selected))])
    return [item.document for item in selected]


def _is_adjacent_paragraph(left: _ScoredDocument, right: _ScoredDocument) -> bool:
    return (
        left.document.paper_id == right.document.paper_id
        and left.document.section == right.document.section
        and left.document.element_type == right.document.element_type == "paragraph"
        and abs(left.document_order - right.document_order) == 1
    )


def _is_abstract_document(document: ReadingDocument) -> bool:
    return _normalized_section(document.section) == "abstract"


def _is_major_section(section: str) -> bool:
    return re.match(r"^\s*\d+\s+\S", section or "") is not None


def _normalized_section(section: str) -> str:
    return _normalize(re.sub(r"^\s*\d+(?:\.\d+)*\s*", "", section or ""))


def _deduplicate_candidates(scored: list[_ScoredDocument]) -> list[_ScoredDocument]:
    selected: list[_ScoredDocument] = []
    seen_text: set[tuple[str, str, str]] = set()
    for item in scored:
        key = (
            item.document.paper_id,
            str(item.document.page),
            _normalize(item.document.text),
        )
        if key in seen_text:
            continue
        seen_text.add(key)
        selected.append(item)
    return selected


def _scored_document_sort_key(
    item: _ScoredDocument,
    paper_id_order: list[str],
) -> tuple[float, int, int, str]:
    paper_order = {
        paper_id: index
        for index, paper_id in enumerate(paper_id_order)
    }
    page = _optional_int(item.document.page)
    return (
        -item.score,
        paper_order.get(item.document.paper_id, len(paper_order)),
        page if page is not None else 10**9,
        item.document.location_ref,
    )


def _optional_int(value: Any) -> int | None:
    try:
        return int(value) if value not in (None, "") else None
    except (TypeError, ValueError):
        return None


def _normalize_identifier(value: Any) -> str:
    return str(value or "").strip().lower()


def _tool_schema(name: str, description: str, parameters: JsonMap) -> JsonMap:
    return {
        "type": "function",
        "function": {
            "name": name,
            "description": description,
            "parameters": parameters,
        },
    }


def _first_text(value: JsonMap, fields: list[str]) -> str:
    for field_name in fields:
        raw = value.get(field_name)
        if raw:
            return str(raw)
    return ""


def _tokens(value: str) -> list[str]:
    return re.findall(r"[a-zA-Z0-9_]+", value.lower())


def _passage_token_windows(tokens: list[str]) -> list[list[str]]:
    if len(tokens) <= PASSAGE_TOKEN_LIMIT:
        return [tokens]
    windows: list[list[str]] = []
    for start in range(0, len(tokens), PASSAGE_TOKEN_STRIDE):
        window = tokens[start:start + PASSAGE_TOKEN_LIMIT]
        if not window:
            break
        windows.append(window)
        if start + PASSAGE_TOKEN_LIMIT >= len(tokens):
            break
    return windows


def _bm25_statistics(documents: list[list[str]]) -> _Bm25Statistics:
    document_frequency: Counter[str] = Counter()
    total_length = 0
    for tokens in documents:
        document_frequency.update(set(tokens))
        total_length += len(tokens)
    document_count = len(documents)
    return _Bm25Statistics(
        document_count=document_count,
        average_length=(total_length / document_count) if document_count else 0.0,
        document_frequency=dict(document_frequency),
    )


def _bm25_score(
    query_tokens: list[str],
    document_tokens: list[str],
    statistics: _Bm25Statistics,
) -> float:
    if not query_tokens or not document_tokens or not statistics.document_count:
        return 0.0
    term_frequency = Counter(document_tokens)
    document_length = len(document_tokens)
    average_length = statistics.average_length or 1.0
    score = 0.0
    for token in set(query_tokens):
        frequency = term_frequency.get(token, 0)
        if not frequency:
            continue
        inverse_document_frequency = _bm25_idf(token, statistics)
        denominator = frequency + BM25_K1 * (
            1 - BM25_B + BM25_B * document_length / average_length
        )
        score += inverse_document_frequency * frequency * (BM25_K1 + 1) / denominator
    return score


def _bm25_idf(token: str, statistics: _Bm25Statistics) -> float:
    document_frequency = statistics.document_frequency.get(token, 0)
    return math.log(
        1 + (
            statistics.document_count - document_frequency + 0.5
        ) / (document_frequency + 0.5)
    )


def _query_term_weights(
    query_tokens: set[str],
    statistics: _Bm25Statistics,
) -> dict[str, float]:
    return {
        token: _bm25_idf(token, statistics)
        for token in query_tokens
    }


def _score_tokens(query_tokens: set[str], text_tokens: set[str]) -> float:
    if not query_tokens:
        return 0.0
    overlap = query_tokens & text_tokens
    return len(overlap) / len(query_tokens)


def _section_hint_score(query: str, section: str, text: str) -> float:
    normalized_section = _normalize(section)
    normalized_lead = _normalize(text[:500])
    hints = [
        hint
        for item in re.split(r"[/|>]", query)
        if (hint := _normalize(item))
    ]
    if not hints:
        return 0.0
    best = 0.0
    for hint in hints:
        specificity = min(len(_tokens(hint)), 3)
        if hint in normalized_section:
            best = max(best, 0.5 + 0.5 * specificity)
        elif hint in normalized_lead:
            best = max(best, 0.75 + 0.5 * specificity)
        else:
            best = max(
                best,
                0.5 * _score_tokens(set(_tokens(hint)), set(_tokens(section))),
            )
    return best


def json_tool_content(result: ToolResult) -> str:
    return json.dumps(result.payload, ensure_ascii=False, sort_keys=True)
