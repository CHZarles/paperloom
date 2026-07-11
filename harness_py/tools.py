from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass, field
from typing import Any

from .models import GoldenDataset, JsonMap, as_list, child_map


SEARCH_ELEMENT_TYPES = (
    "paragraph",
    "heading",
    "table",
    "list",
    "image",
    "footnote",
    "chart",
    "formula",
    "aside",
)
SEARCH_RESULT_LIMIT = 10
SEARCH_SNIPPET_CHARS = 500
PAPER_RESULT_LIMIT = 100


@dataclass(frozen=True)
class ToolResult:
    name: str
    payload: JsonMap


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
    matched_anchor_id: str | None = None

    def evidence_id(self) -> str:
        raw = f"{self.paper_id}:{self.location_ref}:{self.element_type}:{self.page}"
        return "ev_" + hashlib.sha1(raw.encode("utf-8")).hexdigest()[:16]

    def to_location_candidate(self) -> JsonMap:
        return {
            "paper_id": self.paper_id,
            "title": self.title,
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
            "paper_id": self.paper_id,
            "title": self.title,
            "paper_version": self.paper_version,
            "section": self.section or "unsectioned",
            "page": self.page if self.page is not None else "unknown",
            "location": self.location_ref,
            "location_ref": self.location_ref,
            "element_type": self.element_type,
            "span_text": self.text,
            "bbox_or_cell_ref": None,
            "source_kind": self.source_kind,
            "retrieval_strategy": "source_quote_reading",
            "relevance_score": 1.0,
            "evidence_quality": "verified" if self.matched_anchor_id else "read",
            "supports_claim_ids": [],
            "refutes_claim_ids": [],
        }


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
                    "author, venue, year, and metadata. Results are non-citeable paper cards, not "
                    "paper-content evidence or recommendations. Use an empty query_text with a "
                    "large limit to inspect the complete fixed corpus in one call."
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
                    "non-citeable navigation previews and location refs. Use read_locations before "
                    "making paper-content claims."
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
        query_tokens = set(_tokens(query))
        section_tokens = set(_tokens(section_query))
        scored: list[tuple[float, ReadingDocument]] = []
        for document in self.documents:
            if document.paper_id not in paper_ids:
                continue
            if element_types and document.element_type not in element_types:
                continue
            page = _optional_int(document.page)
            if page_from is not None and (page is None or page < page_from):
                continue
            if page_to is not None and (page is None or page > page_to):
                continue
            document_tokens = set(_tokens(" ".join([document.section, document.text])))
            if section_tokens and not section_tokens <= set(_tokens(document.section)):
                continue
            score = _score_tokens(query_tokens, document_tokens) if query_tokens else 1.0
            if query and _normalize(query) in _normalize(document.text):
                score += 2.0
            if score > 0:
                scored.append((score, document))
        scored.sort(key=lambda item: item[0], reverse=True)
        selected = _diverse_documents(scored, paper_id_list, top_k)
        locations = [document.to_location_candidate() for document in selected]
        self.disclosed_location_refs.update(str(item["location_ref"]) for item in locations)
        return {
            "query_text": query,
            "locations": locations,
            "matched_count": len(scored),
            "returned_count": len(locations),
            "coverage": "complete" if len(locations) >= len(scored) else "truncated",
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
        title = str(identity.get("title") or paper_id)
        version = str(identity.get("version_label") or model.get("model_version") or "unknown")
        for element in as_list(model.get("reading_elements")):
            element_map = child_map(element)
            text = _first_text(element_map, ["searchableText", "bodyText", "captionText"])
            if not text:
                continue
            location = str(element_map.get("locationRef") or element_map.get("readingElementId") or element_map.get("id"))
            doc = ReadingDocument(
                paper_id=paper_id,
                title=title,
                paper_version=version,
                location_ref=location,
                element_type=str(element_map.get("elementType") or "paragraph").lower(),
                page=element_map.get("pageNumber"),
                section=str(element_map.get("sectionTitle") or ""),
                text=text,
                source_kind="reading_element",
            )
            doc.matched_anchor_id = _match_anchor(doc, anchors_by_paper.get(paper_id, []))
            documents.append(doc)
        for table in as_list(model.get("parsed_tables")):
            documents.extend(_special_document(paper_id, title, version, child_map(table), "table", "tableText", anchors_by_paper))
        for figure in as_list(model.get("parsed_figures")):
            documents.extend(_special_document(paper_id, title, version, child_map(figure), "figure", "figureText", anchors_by_paper))
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
                doc.matched_anchor_id = _match_anchor(doc, anchors_by_paper.get(paper_id, []))
                documents.append(doc)
    return documents


def _special_document(
    paper_id: str,
    title: str,
    version: str,
    raw: JsonMap,
    element_type: str,
    text_field: str,
    anchors_by_paper: dict[str, list[JsonMap]],
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
    )
    doc.matched_anchor_id = _match_anchor(doc, anchors_by_paper.get(paper_id, []))
    return [doc]


def _anchors_by_paper(dataset: GoldenDataset) -> dict[str, list[JsonMap]]:
    by_paper: dict[str, list[JsonMap]] = {}
    for anchor in dataset.anchors_by_id.values():
        by_paper.setdefault(str(anchor.get("paper_id")), []).append(anchor)
    return by_paper


def _match_anchor(document: ReadingDocument, anchors: list[JsonMap]) -> str | None:
    document_text = _normalize(document.text)
    for anchor in anchors:
        element = child_map(anchor.get("element"))
        page = element.get("page")
        if not _page_matches(page, document.page):
            continue
        exact_text = child_map(anchor.get("selector")).get("exact_text")
        normalized_quote = _normalize(str(exact_text or ""))
        if _contains_normalized_phrase(document_text, normalized_quote):
            return str(anchor.get("anchor_id"))
    return None


def _page_matches(anchor_page: Any, document_page: Any) -> bool:
    if anchor_page is None:
        return True
    parsed_anchor_page = _optional_int(anchor_page)
    parsed_document_page = _optional_int(document_page)
    return parsed_anchor_page is not None and parsed_document_page == parsed_anchor_page


def _contains_normalized_phrase(normalized_text: str, normalized_quote: str) -> bool:
    if not normalized_text or not normalized_quote:
        return False
    return f" {normalized_quote} " in f" {normalized_text} "


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
    if _normalize(query) and _normalize(query) in _normalize(" ".join([title, abstract, metadata])):
        score += 2.0
    return score


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


def _diverse_documents(
    scored: list[tuple[float, ReadingDocument]],
    paper_id_order: list[str],
    top_k: int,
) -> list[ReadingDocument]:
    by_paper: dict[str, list[ReadingDocument]] = {paper_id: [] for paper_id in paper_id_order}
    for _, document in scored:
        by_paper.setdefault(document.paper_id, []).append(document)
    selected: list[ReadingDocument] = []
    depth = 0
    while len(selected) < top_k:
        added = False
        for paper_id in paper_id_order:
            documents = by_paper.get(paper_id, [])
            if depth < len(documents):
                selected.append(documents[depth])
                added = True
                if len(selected) >= top_k:
                    break
        if not added:
            break
        depth += 1
    return selected


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


def _score_tokens(query_tokens: set[str], text_tokens: set[str]) -> float:
    if not query_tokens:
        return 0.0
    overlap = query_tokens & text_tokens
    return len(overlap) / max(len(query_tokens), 1)


def _normalize(value: str) -> str:
    return " ".join(_tokens(value))


def json_tool_content(result: ToolResult) -> str:
    return json.dumps(result.payload, ensure_ascii=False, sort_keys=True)
