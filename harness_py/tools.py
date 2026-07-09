from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass, field
from typing import Any

from .models import GoldenDataset, JsonMap, as_list, child_map


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

    def to_evidence_item(self, strategy: str, score: float = 1.0) -> JsonMap:
        return {
            "evidence_id": self.evidence_id(),
            "matched_anchor_id": self.matched_anchor_id,
            "paper_id": self.paper_id,
            "title": self.title,
            "paper_version": self.paper_version,
            "section": self.section or "unsectioned",
            "page": self.page if self.page is not None else "unknown",
            "location": self.location_ref,
            "element_type": self.element_type,
            "span_text": self.text[:1200],
            "bbox_or_cell_ref": None,
            "retrieval_strategy": strategy,
            "relevance_score": round(float(score), 4),
            "evidence_quality": "verified" if self.matched_anchor_id else "candidate",
            "supports_claim_ids": [],
            "refutes_claim_ids": [],
        }


@dataclass
class ReadingCorpusTools:
    dataset: GoldenDataset
    observations_by_evidence_id: dict[str, JsonMap] = field(default_factory=dict)

    def __post_init__(self) -> None:
        self.documents = _build_documents(self.dataset)
        self.documents_by_location = {doc.location_ref: doc for doc in self.documents}
        self.documents_by_evidence_id = {doc.evidence_id(): doc for doc in self.documents}

    def definitions(self) -> list[JsonMap]:
        return [
            _tool_schema(
                "list_papers",
                "List paper cards in the current corpus. Use before choosing a paper.",
                {
                    "type": "object",
                    "properties": {
                        "query": {"type": "string"},
                        "limit": {"type": "integer", "minimum": 1, "maximum": 20},
                    },
                },
            ),
            _tool_schema(
                "find_papers_by_identity",
                "Resolve paper identity by title words, paper id, arXiv id, author, or year.",
                {
                    "type": "object",
                    "properties": {
                        "query": {"type": "string"},
                        "paper_id": {"type": "string"},
                        "year": {"type": "integer"},
                        "limit": {"type": "integer", "minimum": 1, "maximum": 20},
                    },
                },
            ),
            _tool_schema(
                "search_reading_locations",
                "Search reading-model locations for source evidence. Returns evidence ids to cite.",
                {
                    "type": "object",
                    "required": ["query"],
                    "properties": {
                        "query": {"type": "string"},
                        "paper_ids": {"type": "array", "items": {"type": "string"}},
                        "element_types": {"type": "array", "items": {"type": "string"}},
                        "top_k": {"type": "integer", "minimum": 1, "maximum": 20},
                    },
                },
            ),
            _tool_schema(
                "read_locations",
                "Read exact source text for evidence ids or location refs returned by search.",
                {
                    "type": "object",
                    "properties": {
                        "evidence_ids": {"type": "array", "items": {"type": "string"}},
                        "location_refs": {"type": "array", "items": {"type": "string"}},
                    },
                },
            ),
            _tool_schema(
                "get_citation_edges",
                "List citation or lineage edges in the current paper pack.",
                {
                    "type": "object",
                    "properties": {
                        "paper_id": {"type": "string"},
                    },
                },
            ),
        ]

    def call(self, name: str, arguments: JsonMap) -> ToolResult:
        if name == "list_papers":
            return ToolResult(name, self.list_papers(arguments))
        if name == "find_papers_by_identity":
            return ToolResult(name, self.find_papers_by_identity(arguments))
        if name == "search_reading_locations":
            return ToolResult(name, self.search_reading_locations(arguments))
        if name == "read_locations":
            return ToolResult(name, self.read_locations(arguments))
        if name == "get_citation_edges":
            return ToolResult(name, self.get_citation_edges(arguments))
        return ToolResult(name, {"error": f"unknown tool: {name}"})

    def list_papers(self, arguments: JsonMap) -> JsonMap:
        query = str(arguments.get("query") or "")
        limit = int(arguments.get("limit") or 10)
        query_tokens = set(_tokens(query))
        cards: list[JsonMap] = []
        for paper_id, record in self.dataset.paper_records_by_id.items():
            identity = child_map(record.get("identity"))
            haystack = " ".join([
                str(identity.get("title", "")),
                " ".join(str(author) for author in as_list(identity.get("authors"))),
                str(identity.get("year", "")),
                str(identity.get("arxiv_id", "")),
                paper_id,
            ])
            if query_tokens and not (query_tokens & set(_tokens(haystack))):
                continue
            cards.append(_paper_card(paper_id, record))
        return {"papers": cards[: max(1, min(limit, 20))], "total": len(cards)}

    def find_papers_by_identity(self, arguments: JsonMap) -> JsonMap:
        query = str(arguments.get("query") or arguments.get("paper_id") or "")
        year = arguments.get("year")
        limit = int(arguments.get("limit") or 10)
        query_tokens = set(_tokens(query))
        matches: list[JsonMap] = []
        for paper_id, record in self.dataset.paper_records_by_id.items():
            identity = child_map(record.get("identity"))
            if year and str(identity.get("year")) != str(year):
                continue
            haystack = " ".join([
                paper_id,
                str(identity.get("title", "")),
                str(identity.get("arxiv_id", "")),
                " ".join(str(author) for author in as_list(identity.get("authors"))),
            ])
            score = _score_tokens(query_tokens, set(_tokens(haystack)))
            if query and score <= 0 and query.lower() not in haystack.lower():
                continue
            card = _paper_card(paper_id, record)
            card["match_score"] = score
            matches.append(card)
        matches.sort(key=lambda card: card.get("match_score", 0), reverse=True)
        return {"matches": matches[: max(1, min(limit, 20))], "total": len(matches)}

    def search_reading_locations(self, arguments: JsonMap) -> JsonMap:
        query = str(arguments.get("query") or "")
        top_k = max(1, min(int(arguments.get("top_k") or 8), 20))
        paper_ids = set(str(value) for value in as_list(arguments.get("paper_ids")) if value)
        element_types = set(str(value) for value in as_list(arguments.get("element_types")) if value)
        query_tokens = set(_tokens(query))
        scored: list[tuple[float, ReadingDocument]] = []
        for document in self.documents:
            if paper_ids and document.paper_id not in paper_ids:
                continue
            if element_types and document.element_type not in element_types:
                continue
            score = _score_tokens(query_tokens, set(_tokens(document.text)))
            if query.lower() and query.lower() in document.text.lower():
                score += 2.0
            if score > 0:
                scored.append((score, document))
        scored.sort(key=lambda item: item[0], reverse=True)
        results = []
        for score, document in scored[:top_k]:
            item = document.to_evidence_item("lexical_search", score)
            self.observations_by_evidence_id[item["evidence_id"]] = item
            results.append(item)
        return {"query": query, "results": results, "total_candidates": len(scored)}

    def read_locations(self, arguments: JsonMap) -> JsonMap:
        docs: list[ReadingDocument] = []
        for evidence_id in as_list(arguments.get("evidence_ids")):
            document = self.documents_by_evidence_id.get(str(evidence_id))
            if document:
                docs.append(document)
        for location_ref in as_list(arguments.get("location_refs")):
            document = self.documents_by_location.get(str(location_ref))
            if document:
                docs.append(document)
        items = []
        seen: set[str] = set()
        for document in docs:
            if document.evidence_id() in seen:
                continue
            seen.add(document.evidence_id())
            item = document.to_evidence_item("read_locations", 1.0)
            self.observations_by_evidence_id[item["evidence_id"]] = item
            items.append(item)
        return {"items": items}

    def get_citation_edges(self, arguments: JsonMap) -> JsonMap:
        paper_id = str(arguments.get("paper_id") or "")
        edges = [
            edge
            for edge in self.dataset.citation_edges
            if not paper_id or edge.get("from_paper_id") == paper_id or edge.get("to_paper_id") == paper_id
        ]
        return {"edges": edges}


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
    document_tokens = set(_tokens(document.text))
    for anchor in anchors:
        parser = child_map(anchor.get("parser_evidence"))
        element = child_map(anchor.get("element"))
        page = parser.get("page") or element.get("page")
        if page is not None and document.page is not None and str(page) != str(document.page):
            continue
        matched_text = parser.get("matched_text") or child_map(anchor.get("selector")).get("exact_text")
        if matched_text and _normalize(str(matched_text)) in document_text:
            return str(anchor.get("anchor_id"))
        if matched_text:
            anchor_tokens = set(_tokens(str(matched_text)))
            if anchor_tokens and _score_tokens(anchor_tokens, document_tokens) >= 0.25:
                return str(anchor.get("anchor_id"))
    return None


def _paper_card(paper_id: str, record: JsonMap) -> JsonMap:
    identity = child_map(record.get("identity"))
    return {
        "paper_id": paper_id,
        "title": identity.get("title"),
        "authors": as_list(identity.get("authors")),
        "year": identity.get("year"),
        "venue": identity.get("venue"),
        "arxiv_id": identity.get("arxiv_id"),
    }


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
