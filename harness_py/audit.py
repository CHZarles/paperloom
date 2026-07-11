from __future__ import annotations

import re

from .models import GoldenDataset, JsonMap, as_list, child_map


def audit_dataset(dataset: GoldenDataset) -> JsonMap:
    documents = _documents_by_paper(dataset)
    results: list[JsonMap] = []
    for anchor_id, anchor in sorted(dataset.anchors_by_id.items()):
        paper_id = str(anchor.get("paper_id") or "")
        page = child_map(anchor.get("element")).get("page")
        quote = str(child_map(anchor.get("selector")).get("exact_text") or "")
        normalized_quote = _normalize(quote)
        matched = [
            document
            for document in documents.get(paper_id, [])
            if (page is None or document["page"] is None or str(document["page"]) == str(page))
            and normalized_quote
            and normalized_quote in _normalize(str(document["text"]))
        ]
        results.append({
            "anchor_id": anchor_id,
            "paper_id": paper_id,
            "page": page,
            "status": "pass" if matched else "fail",
            "matched_location_refs": [str(item["location_ref"]) for item in matched],
        })
    failed = sum(1 for item in results if item["status"] == "fail")
    return {
        "schema_version": "harness-anchor-audit/v1",
        "dataset_id": dataset.manifest.get("dataset_id"),
        "anchor_count": len(results),
        "passed_count": len(results) - failed,
        "failed_count": failed,
        "anchors": results,
    }


def _documents_by_paper(dataset: GoldenDataset) -> dict[str, list[JsonMap]]:
    result: dict[str, list[JsonMap]] = {}
    for paper_id, model in dataset.reading_models_by_paper_id.items():
        for raw in as_list(model.get("reading_elements")):
            item = child_map(raw)
            text = str(item.get("searchableText") or item.get("bodyText") or item.get("captionText") or "")
            if not text:
                continue
            result.setdefault(paper_id, []).append({
                "page": item.get("pageNumber"),
                "location_ref": item.get("locationRef") or item.get("readingElementId") or item.get("id"),
                "text": text,
            })
    return result


def _normalize(value: str) -> str:
    return " ".join(re.findall(r"[a-zA-Z0-9_]+", value.casefold()))
