from __future__ import annotations

# 论文检索工具门面（生产路径）。
#
# 构造时必须传 reader=...；不传 reader 时本文件不再提供 in-memory 兜底。
# 单元测试 / 评测夹具走 corpus/in_memory_tools.py:InMemoryTools。
# 内容工具只公开 search_paper_content / get_paper_structure / read_paper_content；底层 transport
# 仍可使用 locations/search 和 locations/read，避免把接口迁移泄露给模型。

from dataclasses import dataclass, field
from typing import Any

from ..utils.models import JsonMap, as_list, child_map
from .gateway import CorpusReader


# 公开常量：工具 schema 和 payload 大小限制需要；in-memory 路径共用同一组。
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
EVIDENCE_ELEMENT_TYPES = ("passage", "table", "figure")
SEARCH_RESULT_LIMIT = 20
SEARCH_SNIPPET_CHARS = 500
PAPER_RESULT_LIMIT = 100
MODEL_REDACTED_FIELDS = {
    "matched_anchor_id",
    "matched_anchor_ids",
    "evidence_anchor_id",
    "dense_score",
    "sparse_score",
    "fused_score",
    "index_version",
    "evidence_payloads",
    "source_span_json",
    "bbox_json",
    "bbox_or_cell_ref",
    "parser_name",
    "parser_version",
}


@dataclass(frozen=True)
class ToolResult:
    name: str
    payload: JsonMap


def model_facing_payload(value: Any) -> Any:
    # 把内部 Run payload 转成模型可见 payload，剥掉 redacted fields（dense_score 等）。
    if isinstance(value, dict):
        return {
            key: model_facing_payload(item)
            for key, item in value.items()
            if key not in MODEL_REDACTED_FIELDS
        }
    if isinstance(value, list):
        return [model_facing_payload(item) for item in value]
    return value


def _tool_schema(name: str, description: str, parameters: JsonMap) -> JsonMap:
    return {
        "type": "function",
        "function": {
            "name": name,
            "description": description,
            "parameters": parameters,
        },
    }


@dataclass
class ReadingCorpusTools:
    """论文检索工具门面。

    构造时传 reader=... 走 Java 委派（生产路径，corpus/gateway.py:JavaCorpusGatewayReader
    调 Java Corpus API）；不传 reader 走 corpus/in_memory_tools.py:InMemoryTools（测试夹具）。
    五个公开 tool 方法全部 reader-only 委派，并合并授权集。
    """

    dataset: Any  # GoldenDataset — required in practice
    reader: CorpusReader | None = None
    observations_by_evidence_id: dict[str, JsonMap] = field(default_factory=dict)
    authorized_paper_ids: set[str] = field(default_factory=set)
    disclosed_location_refs: set[str] = field(default_factory=set)
    evidence_payloads_by_location_ref: dict[str, JsonMap] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if self.reader is None:
            raise ValueError(
                "ReadingCorpusTools requires a CorpusReader; "
                "for in-memory test fixtures, use corpus.in_memory_tools.InMemoryTools."
            )

    def definitions(self) -> list[JsonMap]:
        # 工具 schema：枚举值与 SEARCH_RESULT_LIMIT / PAPER_RESULT_LIMIT / SEARCH_ELEMENT_TYPES
        # 三组常量保持单一来源；schema 暴露给模型层（orchestration/agents/tools.py）。
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
                "search_paper_content",
                (
                    "Search relevant passages, tables, and figures inside previously disclosed candidate papers. "
                    "Returns non-citeable previews and location refs. Read selected refs before making "
                    "paper-content claims."
                ),
                {
                    "type": "object",
                    "required": ["paper_ids"],
                    "properties": {
                        "query_text": {"type": "string"},
                        "paper_ids": {"type": "array", "items": {"type": "string"}},
                        "section_query": {"type": "string"},
                        "section_refs": {"type": "array", "items": {"type": "string"}},
                        "element_types": {
                            "type": "array",
                            "items": {"type": "string", "enum": list(EVIDENCE_ELEMENT_TYPES)},
                        },
                        "page_from": {"type": "integer", "minimum": 1},
                        "page_to": {"type": "integer", "minimum": 1},
                        "top_k": {"type": "integer", "minimum": 1, "maximum": SEARCH_RESULT_LIMIT},
                    },
                    "additionalProperties": False,
                },
            ),
            _tool_schema(
                "get_paper_structure",
                (
                    "Browse the section outline or page structure of previously disclosed papers. This returns "
                    "only navigation metadata and location refs, never paper body text or citations."
                ),
                {
                    "type": "object",
                    "required": ["paper_ids"],
                    "properties": {
                        "paper_ids": {"type": "array", "items": {"type": "string"}},
                        "structure_type": {"type": "string", "enum": ["SECTION", "PAGE"]},
                        "section_query": {"type": "string"},
                        "page_from": {"type": "integer", "minimum": 1},
                        "page_to": {"type": "integer", "minimum": 1},
                    },
                    "additionalProperties": False,
                },
            ),
            _tool_schema(
                "read_paper_content",
                (
                    "Read exact content from location refs returned by search_paper_content or get_paper_structure. "
                    "Returns source_quote_ref values; only those values can be cited in the final answer."
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
        return ToolResult(name, {
            "search_paper_candidates": self.search_paper_candidates,
            "find_papers_by_identity": self.find_papers_by_identity,
            "search_paper_content": self.search_paper_content,
            "get_paper_structure": self.get_paper_structure,
            "read_paper_content": self.read_paper_content,
            "get_citation_edges": self.get_citation_edges,
        }[name](arguments))

    def search_paper_candidates(self, arguments: JsonMap) -> JsonMap:
        arguments = normalize_paper_search_arguments(arguments)
        error = paper_search_preflight(arguments)
        if error:
            return error
        payload = self.reader.search_papers(arguments)
        # 公开的论文才能在后续 tool 里继续读正文位置；先记授权。
        self.authorized_paper_ids.update(
            str(child_map(card).get("paper_id"))
            for card in as_list(payload.get("candidates"))
            if child_map(card).get("paper_id")
        )
        return payload

    def find_papers_by_identity(self, arguments: JsonMap) -> JsonMap:
        error = identity_search_preflight(arguments)
        if error:
            return error
        payload = self.reader.find_papers_by_identity(arguments)
        if payload.get("status") == "resolved":
            self.authorized_paper_ids.update(
                str(child_map(card).get("paper_id"))
                for card in as_list(payload.get("matches"))
                if child_map(card).get("paper_id")
            )
        return payload

    def search_paper_content(self, arguments: JsonMap) -> JsonMap:
        _, error = reading_paper_preflight(arguments, self.authorized_paper_ids, "locations")
        if error:
            return error
        if _invalid_integer(arguments, "top_k", minimum=1, maximum=SEARCH_RESULT_LIMIT):
            return recoverable_error(
                "invalid_top_k", "TOOL_ARGUMENTS_INVALID", "search_paper_content", "locations"
            )
        if any(_invalid_integer(arguments, key, minimum=1, maximum=None) for key in ("page_from", "page_to")):
            return recoverable_error(
                "invalid_page_range", "TOOL_ARGUMENTS_INVALID", "search_paper_content", "locations"
            )
        unsupported_types = sorted({
            str(value) for value in as_list(arguments.get("element_types")) if value
        } - set(EVIDENCE_ELEMENT_TYPES))
        if unsupported_types:
            return recoverable_error(
                "unsupported_element_types",
                "TOOL_ARGUMENTS_INVALID",
                "search_paper_content",
                "locations",
                unsupported_element_types=unsupported_types,
            )
        if _invalid_page_range(arguments):
            return recoverable_error(
                "invalid_page_range",
                "TOOL_ARGUMENTS_INVALID",
                "search_paper_content",
                "locations",
            )
        if as_list(arguments.get("section_refs")):
            _, error = reading_location_preflight(arguments, self.disclosed_location_refs, "locations", key="section_refs")
            if error:
                return error
        payload = self.reader.search_locations(arguments)
        disclosed = {
            str(child_map(item).get("location_ref"))
            for item in as_list(payload.get("locations"))
            if child_map(item).get("location_ref")
        }
        self.disclosed_location_refs.update(
            disclosed
        )
        self.evidence_payloads_by_location_ref.update({
            location_ref: item
            for raw in as_list(payload.get("evidence_payloads"))
            if (item := child_map(raw))
            if (location_ref := str(item.get("location_ref") or "").strip()) in disclosed
        })
        return payload

    def get_paper_structure(self, arguments: JsonMap) -> JsonMap:
        _, error = reading_structure_preflight(arguments, self.authorized_paper_ids, "items")
        if error:
            return error
        payload = self.reader.get_structure(arguments)
        self.disclosed_location_refs.update(
            str(child_map(item).get("location_ref"))
            for item in as_list(payload.get("items"))
            if child_map(item).get("location_ref")
        )
        return payload

    def read_paper_content(self, arguments: JsonMap) -> JsonMap:
        refs, error = reading_location_preflight(arguments, self.disclosed_location_refs, "items")
        if error:
            return error
        payload = self.reader.read_locations({
            **arguments,
            "_evidence_payloads": [
                self.evidence_payloads_by_location_ref[ref]
                for ref in dict.fromkeys(refs)
                if ref in self.evidence_payloads_by_location_ref
            ],
        })
        for raw in as_list(payload.get("items")):
            item = child_map(raw)
            for raw_quote in as_list(item.get("source_quotes")):
                quote = child_map(raw_quote)
                source_quote_ref = str(quote.get("source_quote_ref") or "")
                if source_quote_ref:
                    self.observations_by_evidence_id[source_quote_ref] = {
                        **item,
                        **quote,
                        "source_quote_ref": source_quote_ref,
                    }
        return payload

    def get_citation_edges(self, arguments: JsonMap) -> JsonMap:
        return self.reader.find_papers_by_identity(
            dict(arguments)
        ).get("edges", [])  # 实际走 search_papers 让 reader 实现；保留兼容签名


def _paper_ids(arguments: JsonMap) -> list[str]:
    return list(dict.fromkeys(
        str(value).strip()
        for value in as_list(arguments.get("paper_ids"))
        if str(value).strip()
    ))


def paper_search_preflight(arguments: JsonMap) -> JsonMap | None:
    for key in ("paper_ids", "authors", "venues"):
        values = [str(value).strip() if value is not None else "" for value in as_list(arguments.get(key))]
        if any(not value for value in values) or len(set(values)) != len(values) or len(values) > PAPER_RESULT_LIMIT:
            return recoverable_error(
                f"{key}_invalid", "TOOL_ARGUMENTS_INVALID", "search_paper_candidates", "candidates"
            )
    for key, minimum, maximum in (
        ("offset", 0, None),
        ("limit", 1, PAPER_RESULT_LIMIT),
        ("year_from", 0, None),
        ("year_to", 0, None),
    ):
        if _invalid_integer(arguments, key, minimum=minimum, maximum=maximum):
            return recoverable_error(
                f"{key}_invalid", "TOOL_ARGUMENTS_INVALID", "search_paper_candidates", "candidates"
            )
    if _invalid_page_range(arguments, "year_from", "year_to"):
        return recoverable_error(
            "invalid_year_range", "TOOL_ARGUMENTS_INVALID", "search_paper_candidates", "candidates"
        )
    return None


def normalize_paper_search_arguments(arguments: JsonMap) -> JsonMap:
    return {
        key: value
        for key, value in arguments.items()
        if not (key in {"year_from", "year_to"} and value == 0)
    }


def identity_search_preflight(arguments: JsonMap) -> JsonMap | None:
    identity_keys = {"paper_id", "title", "filename", "doi", "arxiv_id", "authors", "year"}
    hints = {key: value for key, value in arguments.items() if key in identity_keys and value not in (None, "", [])}
    if not hints:
        return recoverable_error(
            "identity_hints_required", "TOOL_ARGUMENTS_INVALID", "search_paper_candidates", "matches"
        )
    if _invalid_integer(arguments, "year", minimum=0, maximum=None):
        return recoverable_error(
            "year_invalid", "TOOL_ARGUMENTS_INVALID", "search_paper_candidates", "matches"
        )
    authors = [str(value).strip() if value is not None else "" for value in as_list(arguments.get("authors"))]
    if any(not author for author in authors) or len(set(authors)) != len(authors) or len(authors) > PAPER_RESULT_LIMIT:
        return recoverable_error(
            "authors_invalid", "TOOL_ARGUMENTS_INVALID", "search_paper_candidates", "matches"
        )
    for key in identity_keys - {"authors", "year"}:
        if key in arguments and arguments[key] is not None and not str(arguments[key]).strip():
            return recoverable_error(
                f"{key}_invalid", "TOOL_ARGUMENTS_INVALID", "search_paper_candidates", "matches"
            )
    return None


def reading_paper_preflight(
    arguments: JsonMap,
    authorized_paper_ids: set[str],
    result_key: str,
) -> tuple[list[str], JsonMap | None]:
    raw_paper_ids = as_list(arguments.get("paper_ids"))
    paper_ids = [str(value).strip() if value is not None else "" for value in raw_paper_ids]
    if any(not paper_id for paper_id in paper_ids):
        return [], recoverable_error(
            "paper_ids_invalid",
            "TOOL_ARGUMENTS_INVALID",
            "find_papers_by_identity",
            result_key,
        )
    if not paper_ids:
        return [], recoverable_error(
            "paper_ids_required",
            "TOOL_ARGUMENTS_INVALID",
            "find_papers_by_identity",
            result_key,
        )
    if len(set(paper_ids)) != len(paper_ids) or len(paper_ids) > SEARCH_RESULT_LIMIT:
        return [], recoverable_error(
            "paper_ids_invalid",
            "TOOL_ARGUMENTS_INVALID",
            "find_papers_by_identity",
            result_key,
        )
    unauthorized = [paper_id for paper_id in paper_ids if paper_id not in authorized_paper_ids]
    if unauthorized:
        return paper_ids, recoverable_error(
            "paper_not_authorized_for_reading",
            "PAPER_ID_NOT_DISCLOSED",
            "find_papers_by_identity",
            result_key,
            unauthorized_paper_ids=unauthorized,
        )
    return paper_ids, None


def reading_structure_preflight(
    arguments: JsonMap,
    authorized_paper_ids: set[str],
    result_key: str,
) -> tuple[list[str], JsonMap | None]:
    paper_ids, error = reading_paper_preflight(arguments, authorized_paper_ids, result_key)
    if error:
        return paper_ids, error
    if str(arguments.get("structure_type") or "SECTION").upper() not in {"SECTION", "PAGE"}:
        return paper_ids, recoverable_error(
            "invalid_structure_type", "TOOL_ARGUMENTS_INVALID", "get_paper_structure", result_key
        )
    if (
        any(_invalid_integer(arguments, key, minimum=1, maximum=None) for key in ("page_from", "page_to"))
        or _invalid_page_range(arguments)
    ):
        return paper_ids, recoverable_error(
            "invalid_page_range", "TOOL_ARGUMENTS_INVALID", "get_paper_structure", result_key
        )
    return paper_ids, None


def reading_location_preflight(
    arguments: JsonMap,
    disclosed_location_refs: set[str],
    result_key: str,
    *,
    key: str = "location_refs",
) -> tuple[list[str], JsonMap | None]:
    next_action = "read_paper_content" if key == "location_refs" else "search_paper_content"
    refs = [str(value).strip() if value is not None else "" for value in as_list(arguments.get(key))]
    if not refs or any(not ref for ref in refs):
        return [], recoverable_error("location_refs_required", "TOOL_ARGUMENTS_INVALID", next_action, result_key)
    if len(set(refs)) != len(refs) or len(refs) > SEARCH_RESULT_LIMIT:
        return [], recoverable_error("location_refs_invalid", "TOOL_ARGUMENTS_INVALID", next_action, result_key)
    undisclosed = [ref for ref in refs if ref not in disclosed_location_refs]
    if undisclosed:
        return [], recoverable_error(
            "location_ref_not_disclosed", "LOCATION_NOT_DISCLOSED", next_action, result_key,
            location_refs=undisclosed,
        )
    return refs, None


def recoverable_error(
    error: str,
    error_code: str,
    next_action: str,
    result_key: str,
    **details: Any,
) -> JsonMap:
    return {
        "error": error,
        "error_code": error_code,
        "recoverable": True,
        "next_action": next_action,
        **details,
        result_key: [],
    }


def _invalid_page_range(arguments: JsonMap, lower_key: str = "page_from", upper_key: str = "page_to") -> bool:
    try:
        lower = arguments.get(lower_key)
        upper = arguments.get(upper_key)
        return lower is not None and upper is not None and int(lower) > int(upper)
    except (TypeError, ValueError):
        return True


def _invalid_integer(arguments: JsonMap, key: str, *, minimum: int, maximum: int | None) -> bool:
    value = arguments.get(key)
    if value is None:
        return False
    try:
        number = int(value)
    except (TypeError, ValueError):
        return True
    return isinstance(value, bool) or number < minimum or (maximum is not None and number > maximum)
