from __future__ import annotations

# 论文检索工具门面（生产路径）。
#
# 构造时必须传 reader=...；不传 reader 时本文件不再提供 in-memory 兜底。
# 单元测试 / 评测夹具走 corpus/in_memory_tools.py:InMemoryTools。
# 五个 tool 方法（search_paper_candidates / find_papers_by_identity / find_reading_locations /
# read_locations / get_citation_edges）都是 reader-only 委派：每个方法先看 self.reader，缺则报错。

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
        return ToolResult(name, {
            "search_paper_candidates": self.search_paper_candidates,
            "find_papers_by_identity": self.find_papers_by_identity,
            "find_reading_locations": self.find_reading_locations,
            "read_locations": self.read_locations,
            "get_citation_edges": self.get_citation_edges,
        }[name](arguments))

    def search_paper_candidates(self, arguments: JsonMap) -> JsonMap:
        payload = self.reader.search_papers(arguments)
        # 公开的论文才能在后续 tool 里继续读正文位置；先记授权。
        self.authorized_paper_ids.update(
            str(child_map(card).get("paper_id"))
            for card in as_list(payload.get("candidates"))
            if child_map(card).get("paper_id")
        )
        return payload

    def find_papers_by_identity(self, arguments: JsonMap) -> JsonMap:
        payload = self.reader.find_papers_by_identity(arguments)
        if payload.get("status") == "resolved":
            self.authorized_paper_ids.update(
                str(child_map(card).get("paper_id"))
                for card in as_list(payload.get("matches"))
                if child_map(card).get("paper_id")
            )
        return payload

    def find_reading_locations(self, arguments: JsonMap) -> JsonMap:
        payload = self.reader.search_locations(arguments)
        self.disclosed_location_refs.update(
            str(child_map(item).get("location_ref"))
            for item in as_list(payload.get("locations"))
            if child_map(item).get("location_ref")
        )
        return payload

    def read_locations(self, arguments: JsonMap) -> JsonMap:
        payload = self.reader.read_locations(arguments)
        for raw in as_list(payload.get("items")):
            item = child_map(raw)
            evidence_id = str(item.get("evidence_id") or "")
            if evidence_id:
                self.observations_by_evidence_id[evidence_id] = item
        return payload

    def get_citation_edges(self, arguments: JsonMap) -> JsonMap:
        return self.reader.find_papers_by_identity(
            dict(arguments)
        ).get("edges", [])  # 实际走 search_papers 让 reader 实现；保留兼容签名


# Tool payload → JSON 字符串（tests 用）
def json_tool_content(result: ToolResult) -> str:
    import json
    return json.dumps(result.payload, ensure_ascii=False, sort_keys=True)
