from __future__ import annotations

# Product Reading Model 行数据的 GoldenDataset 形状转换：transport/service.py 在
# health/preflight 里对 Java Corpus Gateway 返回的行做汇总；测试用它构造
# 与产品同形状的内存数据集。生产数据读取本身走 transport/service.py:JavaCorpusGateway。

import json
import re
from dataclasses import dataclass
from pathlib import Path

from ..utils.models import GoldenDataset, JsonMap, as_list


@dataclass(frozen=True)
class ProductCorpusSummary:
    paper_count: int
    reading_model_count: int
    reading_element_count: int
    paper_ids: list[str]

    def to_dict(self) -> JsonMap:
        return {
            "paper_count": self.paper_count,
            "reading_model_count": self.reading_model_count,
            "reading_element_count": self.reading_element_count,
            "paper_ids": self.paper_ids,
        }


def build_product_dataset(
    paper_rows: list[JsonMap],
    element_rows: list[JsonMap],
    visual_asset_rows: list[JsonMap] | None = None,
) -> GoldenDataset:
    paper_records: dict[str, JsonMap] = {}
    reading_models: dict[str, JsonMap] = {}
    elements_by_paper: dict[str, list[JsonMap]] = {}
    visual_assets = _visual_assets_by_paper(visual_asset_rows or [])
    for row in element_rows:
        paper_id = str(row.get("paper_id") or "")
        if not paper_id:
            continue
        elements_by_paper.setdefault(paper_id, []).append(_reading_element(row, visual_assets.get(paper_id, {})))

    for row in paper_rows:
        paper_id = str(row.get("paper_id") or "")
        if not paper_id:
            continue
        title = _first_non_blank(row.get("title"), row.get("original_filename"), paper_id)
        paper_records[paper_id] = {
            "paper_id": paper_id,
            "identity": {
                "title": title,
                "authors": _authors(row.get("authors")),
                "year": row.get("year"),
                "venue": row.get("venue"),
                "doi": row.get("doi"),
                "arxiv_id": row.get("arxiv_id"),
                "version_label": row.get("model_version"),
            },
            "abstract": row.get("abstract"),
            "product_db": {
                "original_filename": row.get("original_filename"),
                "user_id": row.get("user_id"),
                "updated_at": row.get("updated_at"),
            },
            "source_assets": {
                "reading_model_source": "product_db",
                "reading_model_version": row.get("model_version"),
            },
        }
        reading_models[paper_id] = {
            "paper_id": paper_id,
            "model_version": row.get("model_version"),
            "model_status": row.get("model_status"),
            "parser_name": row.get("parser_name"),
            "parser_version": row.get("parser_version"),
            "page_count": row.get("page_count"),
            "readable_page_count": row.get("readable_page_count"),
            "readable_char_count": row.get("readable_char_count"),
            "reading_elements": elements_by_paper.get(paper_id, []),
        }

    return GoldenDataset(
        root=Path(".").resolve(),
        manifest_path=Path("product-db-live-corpus"),
        manifest={
            "schema_version": "product-db-live-corpus/v1",
            "dataset_id": "product-db-live-corpus",
        },
        paper_packs=[],
        cases=[],
        paper_records_by_id=paper_records,
        anchors_by_id={},
        citation_edges=[],
        reading_models_by_paper_id=reading_models,
        load_warnings=[],
    )


def summarize_product_corpus(dataset: GoldenDataset) -> ProductCorpusSummary:
    reading_element_count = sum(
        len(as_list(model.get("reading_elements")))
        for model in dataset.reading_models_by_paper_id.values()
    )
    return ProductCorpusSummary(
        paper_count=len(dataset.paper_records_by_id),
        reading_model_count=len(dataset.reading_models_by_paper_id),
        reading_element_count=reading_element_count,
        paper_ids=sorted(dataset.paper_records_by_id),
    )


def _reading_element(row: JsonMap, visual_assets: JsonMap | None = None) -> JsonMap:
    visual_assets = visual_assets or {}
    page_number = row.get("pageNumber")
    element_type = str(row.get("elementType") or "").lower()
    source_object_id = row.get("sourceObjectId")
    table_id = source_object_id if element_type == "table" else None
    figure_id = source_object_id if element_type in {"figure", "chart"} else None
    page_screenshots = set(as_list(visual_assets.get("pageScreenshots")))
    table_screenshots = set(as_list(visual_assets.get("tableScreenshots")))
    figure_screenshots = set(as_list(visual_assets.get("figureScreenshots")))
    return {
        "id": row.get("id") or row.get("readingElementId"),
        "readingElementId": row.get("readingElementId") or row.get("id"),
        "contentListIndex": row.get("contentListIndex"),
        "parserElementId": row.get("parserElementId"),
        "sourceObjectId": row.get("sourceObjectId"),
        "elementType": row.get("elementType") or "paragraph",
        "pageNumber": page_number,
        "readingOrder": row.get("readingOrder"),
        "sectionTitle": row.get("sectionTitle"),
        "parentReadingElementId": row.get("parentReadingElementId"),
        "attachmentRole": row.get("attachmentRole"),
        "associationStatus": row.get("associationStatus"),
        "locationRef": row.get("locationRef") or row.get("readingElementId") or row.get("id"),
        "locationType": row.get("locationType"),
        "captionText": row.get("captionText"),
        "bodyText": row.get("bodyText"),
        "searchableText": row.get("searchableText") or row.get("bodyText") or row.get("captionText"),
        "captionSource": row.get("captionSource"),
        "parserImagePath": row.get("parserImagePath"),
        "bboxJson": row.get("bboxJson"),
        "sourceSpanJson": row.get("sourceSpanJson"),
        "structuredPayloadJson": row.get("structuredPayloadJson"),
        "rawAttributesJson": row.get("rawAttributesJson"),
        "parserName": row.get("parserName"),
        "parserVersion": row.get("parserVersion"),
        "pageScreenshotAvailable": page_number in page_screenshots,
        "pdfEvidenceAvailable": bool(page_screenshots),
        "tableScreenshotAvailable": bool(table_id and table_id in table_screenshots),
        "figureScreenshotAvailable": bool(figure_id and figure_id in figure_screenshots),
    }


def _visual_assets_by_paper(rows: list[JsonMap]) -> dict[str, JsonMap]:
    result: dict[str, JsonMap] = {}
    for row in rows:
        paper_id = str(row.get("paper_id") or "")
        if not paper_id:
            continue
        item = result.setdefault(paper_id, {
            "pageScreenshots": [],
            "tableScreenshots": [],
            "figureScreenshots": [],
        })
        asset_type = str(row.get("asset_type") or "")
        if asset_type == "PAGE_SCREENSHOT" and row.get("page_number") is not None:
            item["pageScreenshots"].append(row.get("page_number"))
        elif asset_type == "TABLE_CROP" and row.get("table_id"):
            item["tableScreenshots"].append(row.get("table_id"))
        elif asset_type in {"FIGURE_CROP", "CHART_CROP"} and row.get("figure_id"):
            item["figureScreenshots"].append(row.get("figure_id"))
    return result


def _authors(value: object) -> list[str]:
    if isinstance(value, list):
        return [str(item).strip() for item in value if str(item).strip()]
    if not value:
        return []
    text = str(value).strip()
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError:
        parsed = None
    if isinstance(parsed, list):
        return [str(item).strip() for item in parsed if str(item).strip()]
    return [part.strip() for part in re.split(r";|\n", text) if part.strip()]


def _first_non_blank(*values: object) -> str:
    for value in values:
        if value is not None and str(value).strip():
            return str(value).strip()
    return ""


