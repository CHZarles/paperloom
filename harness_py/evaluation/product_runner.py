from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

import yaml

from ..core.models import GoldenDataset, JsonMap, as_list, child_map
from ..corpus.gateway import JavaCorpusGateway, JavaCorpusGatewayReader
from ..corpus.pages import contains_normalized_phrase, normalize_text, page_matches
from .golden_case import paper_ids_for_case


PRODUCT_CORPUS_MAP_SCHEMA_VERSION = "harness-golden-product-corpus-map/v1"


@dataclass(frozen=True)
class ProductCorpusMap:
    dataset_id: str
    user_id: int
    product_paper_ids_by_golden_id: dict[str, str]


def load_product_corpus_map(
    path: str | Path,
    dataset: GoldenDataset,
) -> ProductCorpusMap:
    if not str(path or "").strip():
        raise ValueError("--product-corpus-map is required for the java-qdrant backend")
    source = Path(path)
    with source.open("r", encoding="utf-8") as handle:
        document = yaml.safe_load(handle) or {}
    if not isinstance(document, dict):
        raise ValueError("product corpus map must be a YAML object")
    if document.get("schema_version") != PRODUCT_CORPUS_MAP_SCHEMA_VERSION:
        raise ValueError(
            "unsupported product corpus map schema: "
            f"{document.get('schema_version')!r}"
        )
    expected_dataset_id = str(dataset.manifest.get("dataset_id") or "")
    actual_dataset_id = str(document.get("dataset_id") or "").strip()
    if actual_dataset_id != expected_dataset_id:
        raise ValueError(
            "product corpus map dataset_id mismatch: "
            f"expected={expected_dataset_id}, actual={actual_dataset_id}"
        )
    user_id = _positive_int(document.get("user_id"), "user_id")
    raw_papers = document.get("papers")
    if not isinstance(raw_papers, dict) or not raw_papers:
        raise ValueError("product corpus map papers must be a non-empty object")
    mapping = {
        str(golden_id).strip(): str(product_id).strip()
        for golden_id, product_id in raw_papers.items()
        if str(golden_id).strip() and str(product_id).strip()
    }
    unknown = sorted(set(mapping) - set(dataset.paper_records_by_id))
    if unknown:
        raise ValueError(f"product corpus map contains unknown Golden paper IDs: {unknown}")
    duplicates = _duplicates(mapping.values())
    if duplicates:
        raise ValueError(f"product corpus map reuses product paper IDs: {duplicates}")
    return ProductCorpusMap(
        dataset_id=actual_dataset_id,
        user_id=user_id,
        product_paper_ids_by_golden_id=mapping,
    )


def validate_product_scope(
    dataset: GoldenDataset,
    cases: list[JsonMap],
    corpus_map: ProductCorpusMap,
) -> None:
    required = {
        paper_id
        for case in cases
        for paper_id in paper_ids_for_case(dataset, case)
    }
    missing = sorted(required - set(corpus_map.product_paper_ids_by_golden_id))
    if missing:
        raise ValueError(
            "product corpus map does not cover the selected Golden paper scope: "
            f"{missing}"
        )


def product_reader_for_case(
    gateway: JavaCorpusGateway,
    dataset: GoldenDataset,
    case: JsonMap,
    corpus_map: ProductCorpusMap,
    *,
    request_id: str,
    conversation_id: str,
    cancel_check: Callable[[], bool] | None = None,
) -> GoldenJavaCorpusReader:
    golden_scope = paper_ids_for_case(dataset, case)
    missing = [
        paper_id
        for paper_id in golden_scope
        if paper_id not in corpus_map.product_paper_ids_by_golden_id
    ]
    if missing:
        raise ValueError(f"product corpus map is missing case papers: {missing}")
    mapping = {
        paper_id: corpus_map.product_paper_ids_by_golden_id[paper_id]
        for paper_id in golden_scope
    }
    delegate = gateway.reader(
        request_id=request_id,
        conversation_id=conversation_id,
        user_id=corpus_map.user_id,
        scope_paper_ids=list(mapping.values()),
        cancel_check=cancel_check,
    )
    return GoldenJavaCorpusReader(delegate=delegate, dataset=dataset, mapping=mapping)


@dataclass
class GoldenJavaCorpusReader:
    """Translate stable Golden identities around the product Java/Qdrant corpus path."""

    delegate: JavaCorpusGatewayReader
    dataset: GoldenDataset
    mapping: dict[str, str]
    reverse_mapping: dict[str, str] = field(init=False)

    def __post_init__(self) -> None:
        self.reverse_mapping = {product: golden for golden, product in self.mapping.items()}
        if len(self.reverse_mapping) != len(self.mapping):
            raise ValueError("Golden/product paper mapping must be one-to-one")

    def search_papers(self, arguments: JsonMap) -> JsonMap:
        response = self.delegate.search_papers(self._request_arguments(arguments))
        return self._response_with_papers(response, "candidates")

    def find_papers_by_identity(self, arguments: JsonMap) -> JsonMap:
        response = self.delegate.find_papers_by_identity(self._request_arguments(arguments))
        return self._response_with_papers(response, "matches")

    def search_locations(self, arguments: JsonMap) -> JsonMap:
        response = self.delegate.search_locations(self._request_arguments(arguments))
        return self._response_with_papers(response, "locations")

    def read_locations(self, arguments: JsonMap) -> JsonMap:
        response = self.delegate.read_locations(arguments)
        items: list[JsonMap] = []
        for raw in as_list(response.get("items")):
            item = dict(child_map(raw))
            item["paper_id"] = self._golden_id(item.get("paper_id"))
            matched = self._matched_anchor_ids(item)
            item["matched_anchor_ids"] = matched
            item["matched_anchor_id"] = matched[0] if matched else None
            items.append(item)
        return {
            **response,
            "items": items,
        }

    def _request_arguments(self, arguments: JsonMap) -> JsonMap:
        translated = dict(arguments)
        if "paper_ids" in translated:
            translated["paper_ids"] = [
                self._product_id(paper_id)
                for paper_id in as_list(translated.get("paper_ids"))
            ]
        if translated.get("paper_id"):
            translated["paper_id"] = self._product_id(translated.get("paper_id"))
        return translated

    def _response_with_papers(self, response: JsonMap, field_name: str) -> JsonMap:
        items = []
        for raw in as_list(response.get(field_name)):
            item = dict(child_map(raw))
            item["paper_id"] = self._golden_id(item.get("paper_id"))
            items.append(item)
        return {
            **response,
            field_name: items,
        }

    def _product_id(self, value: object) -> str:
        golden_id = str(value or "").strip()
        if golden_id not in self.mapping:
            raise ValueError(f"Golden paper is outside the mapped product scope: {golden_id}")
        return self.mapping[golden_id]

    def _golden_id(self, value: object) -> str:
        product_id = str(value or "").strip()
        if product_id not in self.reverse_mapping:
            raise ValueError(
                f"Java returned a paper outside the mapped product scope: {product_id}"
            )
        return self.reverse_mapping[product_id]

    def _matched_anchor_ids(self, evidence: JsonMap) -> list[str]:
        paper_id = str(evidence.get("paper_id") or "")
        page = evidence.get("page")
        text = normalize_text(str(evidence.get("span_text") or ""))
        return [
            anchor_id
            for anchor_id, anchor in self.dataset.anchors_by_id.items()
            if str(anchor.get("paper_id") or "") == paper_id
            and page_matches(child_map(anchor.get("element")).get("page"), page)
            and contains_normalized_phrase(
                text,
                normalize_text(str(child_map(anchor.get("selector")).get("exact_text") or "")),
            )
        ]


def _positive_int(value: object, field_name: str) -> int:
    try:
        parsed = int(value)
    except (TypeError, ValueError) as error:
        raise ValueError(f"product corpus map {field_name} must be a positive integer") from error
    if parsed <= 0:
        raise ValueError(f"product corpus map {field_name} must be a positive integer")
    return parsed


def _duplicates(values) -> list[str]:
    seen: set[str] = set()
    duplicate: set[str] = set()
    for value in values:
        if value in seen:
            duplicate.add(value)
        seen.add(value)
    return sorted(duplicate)
