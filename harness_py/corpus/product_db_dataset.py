from __future__ import annotations

import json
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

from ..core.models import GoldenDataset, JsonMap, as_list
from ..transport.provider_config import DockerMySqlProviderConfigStore, _database_name, _mysql_quote


CommandRunner = Callable[[list[str]], subprocess.CompletedProcess[str]]


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


class DockerMySqlProductCorpusStore:
    """Loads a live product paper corpus from the local MySQL container.

    The returned object intentionally uses the same in-memory shape as golden datasets so the
    research tools and agent loop do not care whether their corpus came from fixtures or product DB.
    """

    def __init__(
        self,
        env_path: str | Path = ".env",
        container_env: str = "HARNESS_MYSQL_CONTAINER",
        default_container: str = "paperloom-mysql",
        command_runner: CommandRunner | None = None,
    ):
        provider_store = DockerMySqlProviderConfigStore(
            env_path=env_path,
            container_env=container_env,
            default_container=default_container,
        )
        self.env_path = Path(env_path)
        self.container = provider_store.container
        self.env = provider_store.env
        self.command_runner = command_runner or _run_command

    def load_dataset(
        self,
        paper_ids: list[str] | None = None,
        query: str | None = None,
        limit: int = 30,
    ) -> GoldenDataset:
        paper_rows = self._query_json_lines(_paper_rows_sql(paper_ids or [], query or "", limit))
        if not paper_rows:
            return build_product_dataset([], [])
        element_rows = self._query_json_lines(_element_rows_sql({
            str(row["paper_id"]): str(row["model_version"])
            for row in paper_rows
            if row.get("paper_id") and row.get("model_version")
        }))
        visual_asset_rows = self._query_json_lines(_visual_asset_rows_sql([
            str(row["paper_id"])
            for row in paper_rows
            if row.get("paper_id")
        ]))
        return build_product_dataset(paper_rows, element_rows, visual_asset_rows)

    def _query_json_lines(self, sql: str) -> list[JsonMap]:
        db_name = _database_name(self.env.get("SPRING_DATASOURCE_URL", ""))
        user = self.env.get("SPRING_DATASOURCE_USERNAME", "root")
        password = self.env.get("SPRING_DATASOURCE_PASSWORD", "")
        command = [
            "docker",
            "exec",
            "-e",
            f"MYSQL_PWD={password}",
            self.container,
            "/usr/bin/mysql",
            "-N",
            "-B",
            "-r",
            f"-u{user}",
            "-D",
            db_name,
            "-e",
            sql,
        ]
        result = self.command_runner(command)
        if result.returncode != 0:
            raise RuntimeError(f"product DB query failed: {result.stderr.strip()}")
        rows: list[JsonMap] = []
        for line in result.stdout.splitlines():
            if not line.strip():
                continue
            parsed = json.loads(line)
            if isinstance(parsed, dict):
                rows.append(parsed)
        return rows


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
                "org_tag": row.get("org_tag"),
                "is_public": bool(row.get("is_public")),
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


def _paper_rows_sql(paper_ids: list[str], query: str, limit: int) -> str:
    filters = ["m.is_current = 1", "m.model_status = 'READING_MODEL_READY'"]
    if paper_ids:
        filters.append("m.paper_id in (" + ",".join(_mysql_quote(paper_id) for paper_id in paper_ids) + ")")
    for term in _query_terms(query):
        like = _mysql_quote("%" + _escape_like(term) + "%")
        filters.append(
            "("
            "m.paper_id like " + like + " escape '\\\\' or "
            "f.paper_title like " + like + " escape '\\\\' or "
            "f.file_name like " + like + " escape '\\\\' or "
            "f.authors like " + like + " escape '\\\\' or "
            "f.abstract_text like " + like + " escape '\\\\' or "
            "f.arxiv_id like " + like + " escape '\\\\'"
            ")"
        )
    safe_limit = max(1, min(int(limit or 30), 1000))
    return f"""
select json_object(
  'paper_id', m.paper_id,
  'title', coalesce(nullif(f.paper_title, ''), nullif(f.file_name, ''), m.paper_id),
  'original_filename', f.file_name,
  'authors', f.authors,
  'year', f.publication_year,
  'venue', f.venue,
  'abstract', f.abstract_text,
  'doi', f.doi,
  'arxiv_id', f.arxiv_id,
  'user_id', f.user_id,
  'org_tag', f.org_tag,
  'is_public', f.is_public + 0,
  'model_version', m.model_version,
  'model_status', m.model_status,
  'parser_name', m.parser_name,
  'parser_version', m.parser_version,
  'page_count', m.page_count,
  'readable_page_count', m.readable_page_count,
  'readable_char_count', m.readable_char_count,
  'updated_at', cast(m.updated_at as char)
) as row_json
from paper_reading_models m
left join (
  select f1.*
  from file_upload f1
  join (
    select file_md5, max(id) as id
    from file_upload
    group by file_md5
  ) latest on latest.id = f1.id
) f on f.file_md5 = m.paper_id
where {" and ".join(filters)}
order by m.updated_at desc
limit {safe_limit}
""".strip()


def _element_rows_sql(model_versions_by_paper: dict[str, str]) -> str:
    if not model_versions_by_paper:
        return "select json_object() where false"
    filters = [
        "(e.paper_id = " + _mysql_quote(paper_id)
        + " and e.model_version = " + _mysql_quote(model_version) + ")"
        for paper_id, model_version in model_versions_by_paper.items()
    ]
    return f"""
select json_object(
  'paper_id', e.paper_id,
  'model_version', e.model_version,
  'id', e.reading_element_id,
  'readingElementId', e.reading_element_id,
  'contentListIndex', e.content_list_index,
  'parserElementId', e.parser_element_id,
  'sourceObjectId', e.source_object_id,
  'elementType', e.element_type,
  'pageNumber', e.page_number,
  'readingOrder', e.reading_order,
  'sectionTitle', e.section_title,
  'parentReadingElementId', e.parent_reading_element_id,
  'attachmentRole', e.attachment_role,
  'associationStatus', e.association_status,
  'locationRef', e.location_ref,
  'locationType', cast(e.location_type as char),
  'captionText', e.caption_text,
  'bodyText', e.body_text,
  'searchableText', e.searchable_text,
  'captionSource', e.caption_source,
  'parserImagePath', e.parser_image_path,
  'bboxJson', e.bbox_json,
  'sourceSpanJson', e.source_span_json,
  'structuredPayloadJson', e.structured_payload_json,
  'rawAttributesJson', e.raw_attributes_json,
  'parserName', e.parser_name,
  'parserVersion', e.parser_version
) as row_json
from paper_reading_elements e
where {" or ".join(filters)}
order by e.paper_id, coalesce(e.reading_order, e.id), e.id
""".strip()


def _visual_asset_rows_sql(paper_ids: list[str]) -> str:
    if not paper_ids:
        return "select json_object() where false"
    return f"""
select json_object(
  'paper_id', a.paper_id,
  'asset_type', a.asset_type,
  'page_number', a.page_number,
  'table_id', a.table_id,
  'figure_id', a.figure_id,
  'reading_element_id', a.reading_element_id,
  'bbox_json', a.bbox_json
) as row_json
from paper_visual_assets a
where a.asset_status = 'AVAILABLE'
  and a.paper_id in ({",".join(_mysql_quote(paper_id) for paper_id in paper_ids)})
order by a.paper_id, a.page_number, a.id
""".strip()


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


def _escape_like(value: str) -> str:
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")


def _query_terms(query: str) -> list[str]:
    terms = re.findall(r"[A-Za-z0-9_]+", query)
    return [term for term in terms[:8] if term]


def _run_command(command: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, text=True, encoding="utf-8", errors="replace", capture_output=True)
