"""ReadingDocument：把 GoldenDataset 拍平成可本地检索的文档与锚点归属。"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass
from typing import Any

from ..corpus.pages import (
    contains_normalized_phrase as _contains_normalized_phrase,
    normalize_text as _normalize,
    page_matches as _page_matches,
)
from ..utils.models import GoldenDataset, JsonMap, as_list, child_map


@dataclass
class ReadingDocument:
    # 本地 BM25 评分的最小数据载体。
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

    def source_quote_ref(self) -> str:
        raw = f"{self.paper_id}:{self.location_ref}:{self.element_type}:{self.page}"
        return "source_quote_" + hashlib.sha1(raw.encode("utf-8")).hexdigest()[:16]

    def evidence_element_type(self) -> str:
        if self.element_type in {"table"}:
            return "table"
        if self.element_type in {"figure", "image", "chart"}:
            return "figure"
        return "passage"

    def to_location_candidate(self) -> JsonMap:
        return {
            "paper_id": self.paper_id,
            "title": self.title,
            "original_filename": self.original_filename,
            "paper_version": self.paper_version,
            "location_ref": self.location_ref,
            "section": self.section or "unsectioned",
            "page": self.page if self.page is not None else "unknown",
            "element_type": self.evidence_element_type(),
            "preview": self.text[:500],
        }

    def to_read_item(self) -> JsonMap:
        return {
            "paper_id": self.paper_id,
            "title": self.title,
            "paper_version": self.paper_version,
            "section": self.section or "unsectioned",
            "page": self.page if self.page is not None else "unknown",
            "location": self.location_ref,
            "location_ref": self.location_ref,
            "element_type": self.evidence_element_type(),
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
            "source_quotes": [{
                "source_quote_ref": self.source_quote_ref(),
                "paper_id": self.paper_id,
                "paper_version": self.paper_version,
                "location_ref": self.location_ref,
                "page": self.page if self.page is not None else "unknown",
                "page_end": self.page if self.page is not None else "unknown",
                "section": self.section or "unsectioned",
                "content": self.text,
            }],
        }


@dataclass(frozen=True)
class _ScoredDocument:
    score: float
    document: ReadingDocument
    document_order: int
    matched_query_tokens: frozenset[str]


def _optional_int(value: Any) -> int | None:
    try:
        return int(value) if value not in (None, "") else None
    except (TypeError, ValueError):
        return None


def _normalize_identifier(value: Any) -> str:
    return str(value or "").strip().lower()


def _first_text(value: JsonMap, fields: list[str]) -> str:
    for field_name in fields:
        raw = value.get(field_name)
        if raw:
            return str(raw)
    return ""


def _build_documents(dataset: GoldenDataset) -> list[ReadingDocument]:
    # 把 GoldenDataset 拍平为按 (paper_id, page/section) 切分的 ReadingDocument 列表。
    # 字段有 reading_element / page / formula / table / figure 五类。
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
