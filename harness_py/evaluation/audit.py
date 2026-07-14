from __future__ import annotations

from ..core.models import GoldenDataset, JsonMap, as_list, child_map
from ..corpus.pages import contains_normalized_phrase, normalize_text, page_matches


def audit_dataset(dataset: GoldenDataset) -> JsonMap:
    documents = _documents_by_paper(dataset)
    results: list[JsonMap] = []
    for anchor_id, anchor in sorted(dataset.anchors_by_id.items()):
        paper_id = str(anchor.get("paper_id") or "")
        page = child_map(anchor.get("element")).get("page")
        quote = str(child_map(anchor.get("selector")).get("exact_text") or "")
        normalized_quote = normalize_text(quote)
        paper_documents = documents.get(paper_id, [])
        element_matches = [
            document
            for document in paper_documents
            if document.get("surface_kind") == "reading_element"
            and _matches_anchor(normalized_quote, page, document)
        ]
        page_matches = [
            document
            for document in paper_documents
            if document.get("surface_kind") == "page"
            and _matches_anchor(normalized_quote, page, document)
        ]
        matched_refs = _unique_location_refs(element_matches or page_matches)
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
                "surface_kind": "reading_element",
            })
        diagnostics = child_map(model.get("diagnostics"))
        if int(diagnostics.get("pagesBuiltFromPhysicalProjection") or 0) <= 0:
            continue
        page_locations: dict[str, JsonMap] = {}
        for raw_location in as_list(model.get("locations")):
            location = child_map(raw_location)
            page_number = location.get("pageNumber")
            if (
                str(location.get("locationType") or "").upper() == "PAGE"
                and page_number is not None
            ):
                page_locations[str(page_number)] = location
        for raw in as_list(model.get("pages")):
            item = child_map(raw)
            text = str(item.get("pageText") or "")
            page_number = item.get("pageNumber")
            if not text or page_number is None:
                continue
            location = page_locations.get(str(page_number), {})
            location_ref = str(location.get("locationRef") or f"{paper_id}:page:{page_number}")
            result.setdefault(paper_id, []).append({
                "page": page_number,
                "location_ref": location_ref,
                "text": text,
                "surface_kind": "page",
            })
    return result


def _matches_anchor(normalized_quote: str, anchor_page: object, document: JsonMap) -> bool:
    if not normalized_quote:
        return False
    if not page_matches(anchor_page, document.get("page")):
        return False
    return contains_normalized_phrase(normalize_text(str(document.get("text") or "")), normalized_quote)


def _location_ref(item: JsonMap) -> str:
    for key in ("locationRef", "readingElementId", "id"):
        value = item.get(key)
        if isinstance(value, str) and value.strip():
            return value
    return ""


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
