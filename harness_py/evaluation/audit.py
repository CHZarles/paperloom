from __future__ import annotations

import re

from ..core.models import GoldenDataset, JsonMap, as_list, child_map
from ..corpus.pages import parse_positive_page


def audit_dataset(dataset: GoldenDataset) -> JsonMap:
    documents = _documents_by_paper(dataset)
    results: list[JsonMap] = []
    for anchor_id, anchor in sorted(dataset.anchors_by_id.items()):
        paper_id = str(anchor.get("paper_id") or "")
        page = child_map(anchor.get("element")).get("page")
        quote = str(child_map(anchor.get("selector")).get("exact_text") or "")
        normalized_quote = _normalize(quote)
        matched_refs = _unique_location_refs(
            document
            for document in documents.get(paper_id, [])
            if _matches_anchor(normalized_quote, page, document)
        )
        status = _status_for_matches(matched_refs)
        results.append({
            "anchor_id": anchor_id,
            "paper_id": paper_id,
            "page": page,
            "status": status,
            "matched_location_refs": matched_refs,
        })
    passed = sum(1 for item in results if item["status"] == "pass")
    return {
        "schema_version": "harness-anchor-audit/v1",
        "dataset_id": dataset.manifest.get("dataset_id"),
        "anchor_count": len(results),
        "passed_count": passed,
        "failed_count": len(results) - passed,
        "anchors": results,
    }


def _documents_by_paper(dataset: GoldenDataset) -> dict[str, list[JsonMap]]:
    result: dict[str, list[JsonMap]] = {}
    for paper_id, model in dataset.reading_models_by_paper_id.items():
        for raw in as_list(model.get("reading_elements")):
            item = child_map(raw)
            text = str(item.get("searchableText") or item.get("bodyText") or item.get("captionText") or "")
            location_ref = _location_ref(item)
            if not text or not location_ref:
                continue
            result.setdefault(paper_id, []).append({
                "page": item.get("pageNumber"),
                "location_ref": location_ref,
                "text": text,
            })
    return result


def _matches_anchor(normalized_quote: str, anchor_page: object, document: JsonMap) -> bool:
    if not normalized_quote:
        return False
    if not _page_matches(anchor_page, document.get("page")):
        return False
    return _contains_normalized_phrase(_normalize(str(document.get("text") or "")), normalized_quote)


def _page_matches(anchor_page: object, document_page: object) -> bool:
    parsed_anchor_page = parse_positive_page(anchor_page)
    parsed_document_page = parse_positive_page(document_page)
    return parsed_anchor_page is not None and parsed_document_page == parsed_anchor_page


def _location_ref(item: JsonMap) -> str:
    for key in ("locationRef", "readingElementId", "id"):
        value = item.get(key)
        if isinstance(value, str) and value.strip():
            return value
    return ""


def _contains_normalized_phrase(normalized_text: str, normalized_quote: str) -> bool:
    if not normalized_text or not normalized_quote:
        return False
    if normalized_text == normalized_quote:
        return True
    return f" {normalized_quote} " in f" {normalized_text} "


def _unique_location_refs(documents) -> list[str]:
    unique: list[str] = []
    seen: set[str] = set()
    for document in documents:
        location_ref = str(document["location_ref"])
        if location_ref in seen:
            continue
        seen.add(location_ref)
        unique.append(location_ref)
    return unique


def _status_for_matches(matched_refs: list[str]) -> str:
    if len(matched_refs) == 1:
        return "pass"
    if matched_refs:
        return "ambiguous"
    return "not_found"


def _normalize(value: str) -> str:
    return " ".join(re.findall(r"[a-zA-Z0-9_]+", value.casefold()))
