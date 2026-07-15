from __future__ import annotations

import hashlib
import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Protocol

import httpx

from ..core.models import GoldenDataset, JsonMap, as_list, child_map


class CorpusReader(Protocol):
    def search_papers(self, arguments: JsonMap) -> JsonMap:
        ...

    def find_papers_by_identity(self, arguments: JsonMap) -> JsonMap:
        ...

    def search_locations(self, arguments: JsonMap) -> JsonMap:
        ...

    def read_locations(self, arguments: JsonMap) -> JsonMap:
        ...


class JavaCorpusGateway:
    """Reusable HTTP client for the Java-owned corpus and Qdrant retrieval plane."""

    def __init__(
        self,
        base_url: str | None = None,
        internal_token: str | None = None,
        *,
        client: httpx.Client | None = None,
    ):
        base_url = (base_url or os.getenv("JAVA_CORPUS_BASE_URL") or "http://127.0.0.1:8081").rstrip("/")
        token = internal_token if internal_token is not None else os.getenv("RESEARCH_HARNESS_INTERNAL_TOKEN", "")
        headers = {"Accept": "application/json"}
        if token.strip():
            headers["Authorization"] = f"Bearer {token.strip()}"
        self.client = client or httpx.Client(
            base_url=base_url,
            headers=headers,
            timeout=httpx.Timeout(20.0, connect=5.0),
            limits=httpx.Limits(max_connections=64, max_keepalive_connections=16),
        )

    def reader(
        self,
        *,
        request_id: str,
        conversation_id: str,
        user_id: int,
        scope_paper_ids: list[str],
    ) -> JavaCorpusGatewayReader:
        return JavaCorpusGatewayReader(
            gateway=self,
            request_id=request_id,
            conversation_id=conversation_id,
            user_id=user_id,
            scope_paper_ids=scope_paper_ids,
        )

    def post(self, path: str, payload: JsonMap) -> JsonMap:
        response = self.client.post(path, json=payload)
        if response.status_code != 200:
            message = response.text[:1000]
            raise RuntimeError(f"Java Corpus API returned HTTP {response.status_code}: {message}")
        result = response.json()
        if not isinstance(result, dict):
            raise RuntimeError("Java Corpus API response must be a JSON object")
        return result


@dataclass
class JavaCorpusGatewayReader:
    gateway: JavaCorpusGateway
    request_id: str
    conversation_id: str
    user_id: int
    scope_paper_ids: list[str]
    candidates_by_location_ref: dict[str, JsonMap] = field(default_factory=dict)

    def load_metadata_dataset(self) -> GoldenDataset:
        records: dict[str, JsonMap] = {}
        offset = 0
        while True:
            response = self.search_papers({"query_text": "", "offset": offset, "limit": 100})
            for raw in as_list(response.get("candidates")):
                card = child_map(raw)
                paper_id = str(card.get("paper_id") or "").strip()
                if paper_id:
                    records[paper_id] = _paper_record(card)
            next_offset = response.get("next_offset")
            if next_offset is None:
                break
            offset = int(next_offset)
        return GoldenDataset(
            root=Path(".").resolve(),
            manifest_path=Path("java-corpus-gateway"),
            manifest={
                "schema_version": "java-corpus-gateway/v1",
                "dataset_id": "java-corpus-gateway",
            },
            paper_packs=[],
            cases=[],
            paper_records_by_id=records,
            anchors_by_id={},
            citation_edges=[],
            reading_models_by_paper_id={},
            load_warnings=[],
        )

    def search_papers(self, arguments: JsonMap) -> JsonMap:
        return self.gateway.post("/internal/v1/corpus/papers/search", {
            **self._context(),
            "query_text": str(arguments.get("query_text") or ""),
            "paper_ids": _strings(arguments.get("paper_ids")),
            "authors": _strings(arguments.get("authors")),
            "venues": _strings(arguments.get("venues")),
            "year_from": arguments.get("year_from"),
            "year_to": arguments.get("year_to"),
            "offset": int(arguments.get("offset") or 0),
            "limit": int(arguments.get("limit") or 20),
        })

    def find_papers_by_identity(self, arguments: JsonMap) -> JsonMap:
        identity = {
            key: value
            for key, value in arguments.items()
            if key in {"paper_id", "title", "filename", "doi", "arxiv_id", "authors", "year"}
            and value not in (None, "", [])
        }
        return self.gateway.post("/internal/v1/corpus/papers/search", {
            **self._context(),
            "identity": identity,
        })

    def search_locations(self, arguments: JsonMap) -> JsonMap:
        response = self.gateway.post("/internal/v1/corpus/locations/search", {
            **self._context(),
            "paper_ids": _strings(arguments.get("paper_ids")),
            "query_text": str(arguments.get("query_text") or ""),
            "section_query": str(arguments.get("section_query") or ""),
            "element_types": _strings(arguments.get("element_types")),
            "page_from": arguments.get("page_from"),
            "page_to": arguments.get("page_to"),
            "top_k": int(arguments.get("top_k") or 8),
        })
        for raw in as_list(response.get("locations")):
            candidate = child_map(raw)
            location_ref = str(candidate.get("location_ref") or "").strip()
            if location_ref:
                self.candidates_by_location_ref[location_ref] = candidate
        return response

    def read_locations(self, arguments: JsonMap) -> JsonMap:
        response = self.gateway.post("/internal/v1/corpus/locations/read", {
            **self._context(),
            "location_refs": _strings(arguments.get("location_refs")),
        })
        items: list[JsonMap] = []
        for raw in as_list(response.get("items")):
            item = child_map(raw)
            location_ref = str(item.get("location_ref") or "")
            candidate = self.candidates_by_location_ref.get(location_ref, {})
            element_type = str(candidate.get("element_type") or item.get("element_type") or "paragraph")
            page = item.get("page")
            evidence_id = _evidence_id(
                str(item.get("paper_id") or ""),
                location_ref,
                element_type,
                page,
            )
            source_object_id = str(item.get("source_object_id") or "") or None
            items.append({
                "evidence_id": evidence_id,
                "matched_anchor_id": None,
                "matched_anchor_ids": [],
                "paper_id": item.get("paper_id"),
                "title": item.get("title"),
                "paper_version": item.get("paper_version"),
                "section": item.get("section") or "unsectioned",
                "page": page if page is not None else "unknown",
                "location": location_ref,
                "location_ref": location_ref,
                "element_type": element_type,
                "span_text": item.get("span_text") or "",
                "bbox_or_cell_ref": item.get("bbox_json"),
                "bbox_json": item.get("bbox_json"),
                "parser_name": item.get("parser_name"),
                "parser_version": item.get("parser_version"),
                "source_kind": "canonical_location_read",
                "table_id": source_object_id if element_type == "table" else None,
                "figure_id": source_object_id if element_type in {"figure", "chart"} else None,
                "formula_id": source_object_id if element_type == "formula" else None,
                "page_screenshot_available": False,
                "pdf_evidence_available": False,
                "table_screenshot_available": False,
                "figure_screenshot_available": False,
                "retrieval_strategy": "source_quote_reading",
                "relevance_score": 1.0,
                "evidence_quality": "verified",
                "supports_claim_ids": [],
                "refutes_claim_ids": [],
            })
        return {
            "items": items,
            "missing_location_refs": _strings(response.get("missing_location_refs")),
        }

    def _context(self) -> JsonMap:
        return {
            "request_id": self.request_id,
            "conversation_id": self.conversation_id,
            "user_id": self.user_id,
            "scope_paper_ids": self.scope_paper_ids,
        }


def _paper_record(card: JsonMap) -> JsonMap:
    return {
        "paper_id": card.get("paper_id"),
        "identity": {
            "title": card.get("title"),
            "authors": as_list(card.get("authors")),
            "year": card.get("year"),
            "venue": card.get("venue"),
            "doi": card.get("doi"),
            "arxiv_id": card.get("arxiv_id"),
        },
        "abstract": card.get("preview"),
        "product_db": {"original_filename": card.get("filename")},
        "source_assets": {"reading_model_source": "java_corpus_gateway"},
    }


def _evidence_id(paper_id: str, location_ref: str, element_type: str, page: object) -> str:
    raw = f"{paper_id}:{location_ref}:{element_type}:{page}"
    return "ev_" + hashlib.sha1(raw.encode("utf-8")).hexdigest()[:16]


def _strings(value: object) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for item in as_list(value):
        text = str(item or "").strip()
        if text and text not in seen:
            seen.add(text)
            result.append(text)
    return result
