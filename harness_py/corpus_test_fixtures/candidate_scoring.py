"""本地 BM25 打分与候选选择：in-memory 检索路径的排序策略。"""

from __future__ import annotations

import re
from typing import Any

from ..corpus.pages import normalize_text as _normalize
from ..utils.models import JsonMap, as_list, child_map
from .bm25 import Bm25Statistics, score as bm25_score, statistics as bm25_statistics
from .bm25 import token_overlap_ratio, tokenize
from .reading_documents import (
    ReadingDocument,
    _ScoredDocument,
    _normalize_identifier,
    _optional_int,
)


# 评分权重与常量：仅 in-memory 路径使用。
SECTION_SCORE_WEIGHT = 0.5
LEAD_SCORE_WEIGHT = 0.8
LEAD_TOKEN_LIMIT = 40
PASSAGE_SCORE_WEIGHT = 1.0
PASSAGE_TOKEN_LIMIT = 80
PASSAGE_TOKEN_STRIDE = 40
ADJACENT_PARAGRAPH_SCORE_WEIGHT = 0.8
ADJACENT_PARAGRAPH_TOKEN_LIMIT = 80
PAGE_GROUNDING_CANDIDATE_LIMIT = 3
TWO_PAPER_CANDIDATE_FLOOR_LIMIT = 7
MULTI_PAPER_CANDIDATE_FLOOR_LIMIT = 3
BROAD_QUERY_MIN_TOKENS = 6
BROAD_QUERY_BASE_CANDIDATES = 12
MULTI_PAPER_BASE_CANDIDATES = 12

GROUNDING_QUERY_STOPWORDS = frozenset({
    "a", "an", "and", "are", "as", "at", "by", "for", "from", "in", "is",
    "of", "on", "or", "the", "to", "with",
})


def _paper_card(paper_id: str, record: JsonMap) -> JsonMap:
    identity = child_map(record.get("identity"))
    abstract = str(record.get("abstract") or "").strip()
    product = child_map(record.get("product_db"))
    return {
        "paper_id": paper_id,
        "title": identity.get("title"),
        "authors": as_list(identity.get("authors")),
        "year": identity.get("year"),
        "venue": identity.get("venue"),
        "doi": identity.get("doi"),
        "arxiv_id": identity.get("arxiv_id"),
        "filename": product.get("original_filename"),
        "preview": abstract[:500],
    }


def _paper_relevance(query_tokens: set[str], query: str, paper_id: str, record: JsonMap) -> float:
    if not query_tokens:
        return 1.0
    identity = child_map(record.get("identity"))
    title = str(identity.get("title") or "")
    abstract = str(record.get("abstract") or "")
    metadata = " ".join([
        paper_id,
        " ".join(str(author) for author in as_list(identity.get("authors"))),
        str(identity.get("venue") or ""),
        str(identity.get("year") or ""),
        str(identity.get("doi") or ""),
        str(identity.get("arxiv_id") or ""),
        str(child_map(record.get("product_db")).get("original_filename") or ""),
    ])
    score = (
        3.0 * token_overlap_ratio(query_tokens, set(tokenize(title)))
        + token_overlap_ratio(query_tokens, set(tokenize(abstract)))
        + token_overlap_ratio(query_tokens, set(tokenize(metadata)))
    )
    normalized_query = _normalize(query)
    if normalized_query and normalized_query in _normalize(" ".join([title, abstract, metadata])):
        score += 2.0
    return score


def _section_hint_score(query: str, section: str, text: str) -> float:
    normalized_section = _normalize(section)
    normalized_lead = _normalize(text[:500])
    hints = [
        hint
        for item in re.split(r"[/|>]", query)
        if (hint := _normalize(item))
    ]
    if not hints:
        return 0.0
    best = 0.0
    for hint in hints:
        specificity = min(len(tokenize(hint)), 3)
        if hint in normalized_section:
            best = max(best, 0.5 + 0.5 * specificity)
        elif hint in normalized_lead:
            best = max(best, 0.75 + 0.5 * specificity)
        else:
            best = max(
                best,
                0.5 * token_overlap_ratio(set(tokenize(hint)), set(tokenize(section))),
            )
    return best


def _score_page_documents(
    documents: list[ReadingDocument],
    query_tokens: list[str],
    section_query: str,
    paper_id_order: list[str],
) -> tuple[list[_ScoredDocument], Bm25Statistics]:
    body_tokens = [tokenize(document.text) for document in documents]
    lead_tokens = [tokens[:LEAD_TOKEN_LIMIT] for tokens in body_tokens]
    section_tokens = [
        [token for token in tokenize(document.section) if not token.isdigit()]
        for document in documents
    ]
    body_statistics = bm25_statistics(body_tokens)
    lead_statistics = bm25_statistics(lead_tokens)
    section_statistics = bm25_statistics(section_tokens)
    query_token_set = set(query_tokens)
    normalized_query = _normalize(" ".join(query_tokens))
    scored: list[_ScoredDocument] = []
    for index, document in enumerate(documents):
        score = bm25_score(query_tokens, body_tokens[index], body_statistics)
        score += LEAD_SCORE_WEIGHT * bm25_score(
            query_tokens, lead_tokens[index], lead_statistics
        )
        score += SECTION_SCORE_WEIGHT * bm25_score(
            query_tokens, section_tokens[index], section_statistics
        )
        if section_query:
            score += _section_hint_score(section_query, document.section, document.text)
        if normalized_query and normalized_query in _normalize(document.text):
            score += 2.0
        if score <= 0:
            continue
        document_tokens = frozenset(body_tokens[index] + section_tokens[index])
        scored.append(_ScoredDocument(
            score=score,
            document=document,
            document_order=index,
            matched_query_tokens=frozenset(query_token_set & document_tokens),
        ))
    scored.sort(key=lambda item: _scored_document_sort_key(item, paper_id_order))
    return _deduplicate_candidates(scored), body_statistics


def _interleave_page_candidates(
    semantic_documents: list[ReadingDocument],
    page_documents: list[ReadingDocument],
) -> list[ReadingDocument]:
    combined = list(semantic_documents)
    inserted_by_paper: dict[str, int] = {}
    for page in page_documents:
        first_paper_index = next(
            (
                index
                for index, document in enumerate(combined)
                if document.paper_id == page.paper_id
            ),
            -1,
        )
        if first_paper_index < 0:
            combined.append(page)
            continue
        insert_at = first_paper_index + 1 + inserted_by_paper.get(page.paper_id, 0)
        combined.insert(insert_at, page)
        inserted_by_paper[page.paper_id] = inserted_by_paper.get(page.paper_id, 0) + 1
    return combined


def _repairs_semantic_page_grounding(
    page: _ScoredDocument,
    semantic_documents: list[_ScoredDocument],
) -> bool:
    page_number = _optional_int(page.document.page)
    same_page_tokens: set[str] = set()
    for semantic in semantic_documents:
        if (
            semantic.document.paper_id == page.document.paper_id
            and _optional_int(semantic.document.page) == page_number
        ):
            same_page_tokens.update(
                set(semantic.matched_query_tokens) - GROUNDING_QUERY_STOPWORDS
            )
    page_tokens = set(page.matched_query_tokens) - GROUNDING_QUERY_STOPWORDS
    uncovered_tokens = page_tokens - same_page_tokens
    if len(uncovered_tokens) < 2:
        return False

    return any(
        semantic.document.paper_id == page.document.paper_id
        and _optional_int(semantic.document.page) != page_number
        and len(
            uncovered_tokens
            & (set(semantic.matched_query_tokens) - GROUNDING_QUERY_STOPWORDS)
        ) >= 2
        and _shares_token_span(page.document.text, semantic.document.text)
        for semantic in semantic_documents
    )


def _shares_token_span(left: str, right: str, width: int = 8) -> bool:
    left_tokens = tokenize(left)
    right_tokens = tokenize(right)
    if len(left_tokens) < width or len(right_tokens) < width:
        return False
    right_spans = {
        tuple(right_tokens[index:index + width])
        for index in range(len(right_tokens) - width + 1)
    }
    return any(
        tuple(left_tokens[index:index + width]) in right_spans
        for index in range(len(left_tokens) - width + 1)
    )


def _matches_identity_hints(paper_id: str, record: JsonMap, hints: JsonMap) -> bool:
    identity = child_map(record.get("identity"))
    product = child_map(record.get("product_db"))
    if hints.get("paper_id") and str(hints["paper_id"]) != paper_id:
        return False
    if hints.get("title") and _normalize(str(hints["title"])) not in _normalize(str(identity.get("title") or "")):
        return False
    if hints.get("filename") and _normalize(str(hints["filename"])) not in _normalize(str(product.get("original_filename") or "")):
        return False
    if hints.get("doi") and _normalize_identifier(hints["doi"]) != _normalize_identifier(identity.get("doi")):
        return False
    if hints.get("arxiv_id") and _normalize_identifier(hints["arxiv_id"]) != _normalize_identifier(identity.get("arxiv_id")):
        return False
    if hints.get("year") is not None and _optional_int(hints["year"]) != _optional_int(identity.get("year")):
        return False
    requested_authors = {
        _normalize(str(value)) for value in as_list(hints.get("authors")) if str(value).strip()
    }
    if requested_authors and not _matches_all_authors(requested_authors, as_list(identity.get("authors"))):
        return False
    return True


def _matches_any_author(requested: set[str], actual: list[Any]) -> bool:
    actual_names = [_normalize(str(value)) for value in actual]
    return any(
        requested_name in actual_name or actual_name in requested_name
        for requested_name in requested
        for actual_name in actual_names
        if requested_name and actual_name
    )


def _matches_all_authors(requested: set[str], actual: list[Any]) -> bool:
    actual_names = [_normalize(str(value)) for value in actual]
    return all(
        any(
            requested_name in actual_name or actual_name in requested_name
            for actual_name in actual_names
            if actual_name
        )
        for requested_name in requested
    )


def _select_coverage_candidates(
    scored: list[_ScoredDocument],
    paper_id_order: list[str],
    query_term_weights_dict: dict[str, float],
    top_k: int,
    *,
    broad_query: bool,
    balanced_paper_floor: bool = False,
) -> list[ReadingDocument]:
    remaining = list(scored)
    selected: list[_ScoredDocument] = []
    covered_tokens: set[str] = set()

    def select_first(paper_id: str, predicate) -> bool:
        for index, item in enumerate(remaining):
            if item.document.paper_id == paper_id and predicate(item.document):
                selected.append(remaining.pop(index))
                covered_tokens.update(item.matched_query_tokens)
                return True
        return False

    paper_floor = 1
    if balanced_paper_floor:
        paper_floor_limit = (
            TWO_PAPER_CANDIDATE_FLOOR_LIMIT
            if len(paper_id_order) == 2
            else MULTI_PAPER_CANDIDATE_FLOOR_LIMIT
        )
        paper_floor = min(
            paper_floor_limit,
            max(1, top_k // max(1, len(paper_id_order))),
        )
    for paper_id in paper_id_order:
        if len(selected) >= top_k:
            break
        select_first(paper_id, lambda document: True)

    if broad_query or balanced_paper_floor:
        for paper_id in paper_id_order:
            if len(selected) >= top_k:
                break
            select_first(paper_id, _is_abstract_document)

    for _ in range(max(0, paper_floor - 1)):
        for paper_id in paper_id_order:
            if len(selected) >= top_k:
                break
            select_first(paper_id, lambda document: True)

    # Broad comparisons also need major-section representatives before detail passages.
    if broad_query and len(paper_id_order) > 1:
        slots_per_paper = max(1, top_k // len(paper_id_order))
        major_section_count = min(3, max(0, slots_per_paper - 2))
        for paper_id in paper_id_order:
            selected_sections: set[str] = set()
            for _ in range(major_section_count):
                if len(selected) >= top_k:
                    break
                selected_index = next(
                    (
                        index
                        for index, item in enumerate(remaining)
                        if item.document.paper_id == paper_id
                        and _is_major_section(item.document.section)
                        and _normalized_section(item.document.section) not in selected_sections
                    ),
                    -1,
                )
                if selected_index < 0:
                    break
                item = remaining.pop(selected_index)
                selected.append(item)
                covered_tokens.update(item.matched_query_tokens)
                selected_sections.add(_normalized_section(item.document.section))

    while remaining and len(selected) < top_k:
        best_index = -1
        best_gain = 0.0
        for index, item in enumerate(remaining):
            gain = sum(
                query_term_weights_dict.get(token, 0.0)
                for token in item.matched_query_tokens - covered_tokens
            )
            if gain > best_gain:
                best_index = index
                best_gain = gain
        if best_index < 0:
            break
        item = remaining.pop(best_index)
        selected.append(item)
        covered_tokens.update(item.matched_query_tokens)

    context_slots = min(2, top_k // 6) if broad_query else 0
    relevance_slots = max(len(selected), top_k - context_slots)
    relevance_count = max(0, relevance_slots - len(selected))
    selected.extend(remaining[:relevance_count])
    del remaining[:relevance_count]

    while remaining and len(selected) < top_k:
        companion_index = -1
        neighbor_index = -1
        for index, item in enumerate(remaining):
            neighbor_index = next(
                (
                    selected_index
                    for selected_index, chosen in enumerate(selected)
                    if _is_adjacent_paragraph(item, chosen)
                ),
                -1,
            )
            if neighbor_index >= 0:
                companion_index = index
                break
        if companion_index < 0:
            break
        item = remaining.pop(companion_index)
        neighbor = selected[neighbor_index]
        insert_at = neighbor_index + (item.document_order > neighbor.document_order)
        selected.insert(insert_at, item)

    selected.extend(remaining[:max(0, top_k - len(selected))])
    return [item.document for item in selected]


def _is_adjacent_paragraph(left: _ScoredDocument, right: _ScoredDocument) -> bool:
    return (
        left.document.paper_id == right.document.paper_id
        and left.document.section == right.document.section
        and left.document.element_type == right.document.element_type == "paragraph"
        and abs(left.document_order - right.document_order) == 1
    )


def _is_abstract_document(document: ReadingDocument) -> bool:
    return _normalized_section(document.section) == "abstract"


def _is_major_section(section: str) -> bool:
    return re.match(r"^\s*\d+\s+\S", section or "") is not None


def _normalized_section(section: str) -> str:
    return _normalize(re.sub(r"^\s*\d+(?:\.\d+)*\s*", "", section or ""))


def _deduplicate_candidates(scored: list[_ScoredDocument]) -> list[_ScoredDocument]:
    selected: list[_ScoredDocument] = []
    seen_text: set[tuple[str, str, str]] = set()
    for item in scored:
        key = (
            item.document.paper_id,
            str(item.document.page),
            _normalize(item.document.text),
        )
        if key in seen_text:
            continue
        seen_text.add(key)
        selected.append(item)
    return selected


def _scored_document_sort_key(
    item: _ScoredDocument,
    paper_id_order: list[str],
) -> tuple[float, int, int, str]:
    paper_order = {paper_id: index for index, paper_id in enumerate(paper_id_order)}
    page = _optional_int(item.document.page)
    return (
        -item.score,
        paper_order.get(item.document.paper_id, len(paper_order)),
        page if page is not None else 10**9,
        item.document.location_ref,
    )


def _passage_token_windows(tokens: list[str]) -> list[list[str]]:
    if len(tokens) <= PASSAGE_TOKEN_LIMIT:
        return [tokens]
    windows: list[list[str]] = []
    for start in range(0, len(tokens), PASSAGE_TOKEN_STRIDE):
        window = tokens[start:start + PASSAGE_TOKEN_LIMIT]
        if not window:
            break
        windows.append(window)
        if start + PASSAGE_TOKEN_LIMIT >= len(tokens):
            break
    return windows
