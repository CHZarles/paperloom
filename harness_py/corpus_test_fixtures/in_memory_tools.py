# InMemoryTools：corpus/tools.py 的测试夹具双胞胎。
#
# 生产路径走 Java 委派（corpus/gateway.py:JavaCorpusGatewayReader）；
# 这个类只给单元测试 + 评测夹具（tests/ + evaluation/golden_*）做不依赖 Java 的本地 BM25 评分。
# 公共字段 / Tool 协议与 ReadingCorpusTools 完全一致；5 个 tool 方法覆盖父类同名方法，
# 把 self.reader 委托替换为基于本地 ReadingDocument 集合的 BM25 评分。
#
# 文档模型在 corpus_test_fixtures/reading_documents.py，打分与候选选择在
# corpus_test_fixtures/candidate_scoring.py；本模块只保留 Tool 协议实现。

from __future__ import annotations

from dataclasses import dataclass, field

from ..corpus.pages import normalize_text as _normalize
from ..corpus.tools import (
    EVIDENCE_ELEMENT_TYPES,
    ReadingCorpusTools,
    SEARCH_RESULT_LIMIT,
    recoverable_error,
)
from ..utils.models import JsonMap, as_list, child_map
from .bm25 import score as bm25_score, statistics as bm25_statistics
from .bm25 import query_term_weights, tokenize
from .candidate_scoring import (
    ADJACENT_PARAGRAPH_SCORE_WEIGHT,
    ADJACENT_PARAGRAPH_TOKEN_LIMIT,
    BROAD_QUERY_BASE_CANDIDATES,
    BROAD_QUERY_MIN_TOKENS,
    LEAD_SCORE_WEIGHT,
    LEAD_TOKEN_LIMIT,
    MULTI_PAPER_BASE_CANDIDATES,
    PAGE_GROUNDING_CANDIDATE_LIMIT,
    PASSAGE_SCORE_WEIGHT,
    SECTION_SCORE_WEIGHT,
    _deduplicate_candidates,
    _interleave_page_candidates,
    _matches_any_author,
    _matches_identity_hints,
    _paper_card,
    _paper_relevance,
    _passage_token_windows,
    _repairs_semantic_page_grounding,
    _score_page_documents,
    _scored_document_sort_key,
    _select_coverage_candidates,
)
from .preflight import (
    identity_search_preflight,
    invalid_integer as _invalid_integer,
    normalize_paper_search_arguments,
    paper_search_preflight,
    reading_location_preflight,
    reading_paper_preflight,
    reading_structure_preflight,
)
from .reading_documents import ReadingDocument, _ScoredDocument, _build_documents, _optional_int


@dataclass
class InMemoryTools(ReadingCorpusTools):
    # 不传 reader 时使用该子类做本地 BM25 评分；保留 ReadingCorpusTools 全部字段。
    # 不要给子类加与父类同名的 tool 字段：公共字段全部从父类继承。
    documents: list[ReadingDocument] = field(init=False, default_factory=list)
    documents_by_location: dict[str, ReadingDocument] = field(init=False, default_factory=dict)

    def __post_init__(self) -> None:
        # 跳过 ReadingCorpusTools.__post_init__ 对 self.reader 的依赖；本地展开 documents 一次。
        self.documents = _build_documents(self.dataset)
        self.documents_by_location = {doc.location_ref: doc for doc in self.documents}

    # ---- 论文元数据 ----

    def search_paper_candidates(self, arguments: JsonMap) -> JsonMap:
        arguments = normalize_paper_search_arguments(arguments)
        error = paper_search_preflight(arguments)
        if error:
            return error
        query = str(arguments.get("query_text") or "").strip()
        query_tokens = set(tokenize(query))
        allowed_ids = {str(value) for value in as_list(arguments.get("paper_ids")) if value}
        authors = {_normalize(str(value)) for value in as_list(arguments.get("authors")) if value}
        venues = {_normalize(str(value)) for value in as_list(arguments.get("venues")) if value}
        year_from = _optional_int(arguments.get("year_from"))
        year_to = _optional_int(arguments.get("year_to"))
        offset = max(0, int(arguments.get("offset") or 0))
        limit = max(1, min(int(arguments.get("limit") or 20), 100))
        matches: list[tuple[float, str, JsonMap]] = []
        for paper_id, record in self.dataset.paper_records_by_id.items():
            identity = child_map(record.get("identity"))
            if allowed_ids and paper_id not in allowed_ids:
                continue
            if authors and not _matches_any_author(authors, as_list(identity.get("authors"))):
                continue
            if venues and _normalize(str(identity.get("venue") or "")) not in venues:
                continue
            year = _optional_int(identity.get("year"))
            if year_from is not None and (year is None or year < year_from):
                continue
            if year_to is not None and (year is None or year > year_to):
                continue
            score = _paper_relevance(query_tokens, query, paper_id, record)
            if query_tokens and score <= 0:
                continue
            matches.append((score, paper_id, record))
        matches.sort(key=lambda item: (-item[0], -(_optional_int(child_map(item[2].get("identity")).get("year")) or 0), item[1]))
        page = matches[offset:offset + limit]
        cards = [_paper_card(paper_id, record) for _, paper_id, record in page]
        # 只有先向模型公开的论文，后续才允许检索正文位置。
        self.authorized_paper_ids.update(str(card["paper_id"]) for card in cards)
        returned_through = offset + len(cards)
        return {
            "query_text": query,
            "candidates": cards,
            "matched_count": len(matches),
            "returned_count": len(cards),
            "coverage": "complete" if returned_through >= len(matches) else "truncated",
            "next_offset": returned_through if returned_through < len(matches) else None,
        }

    def find_papers_by_identity(self, arguments: JsonMap) -> JsonMap:
        error = identity_search_preflight(arguments)
        if error:
            return error
        hints = {
            key: value
            for key, value in arguments.items()
            if key in {"paper_id", "title", "filename", "doi", "arxiv_id", "authors", "year"}
            and value not in (None, "", [])
        }
        if not hints:
            return {"status": "not_found", "matches": [], "reason": "identity_hints_required"}
        matches: list[JsonMap] = []
        for paper_id, record in self.dataset.paper_records_by_id.items():
            if _matches_identity_hints(paper_id, record, hints):
                matches.append(_paper_card(paper_id, record))
        status = "not_found"
        if len(matches) == 1:
            status = "resolved"
            self.authorized_paper_ids.add(str(matches[0]["paper_id"]))
        elif len(matches) > 1:
            status = "ambiguous"
        return {"status": status, "matches": matches, "reason": None}

    # ---- 位置 + 引文 ----

    def search_paper_content(self, arguments: JsonMap) -> JsonMap:
        paper_id_list, error = reading_paper_preflight(
            arguments,
            self.authorized_paper_ids,
            "locations",
        )
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
        query = str(arguments.get("query_text") or "").strip()
        section_query = str(arguments.get("section_query") or "").strip()
        top_k = max(1, min(int(arguments.get("top_k") or 8), 20))
        paper_ids = set(paper_id_list)
        element_types = {str(value) for value in as_list(arguments.get("element_types")) if value}
        unsupported_types = sorted(element_types - set(EVIDENCE_ELEMENT_TYPES))
        if unsupported_types:
            return recoverable_error(
                "unsupported_element_types",
                "TOOL_ARGUMENTS_INVALID",
                "search_paper_content",
                "locations",
                unsupported_element_types=unsupported_types,
            )
        page_from = _optional_int(arguments.get("page_from"))
        page_to = _optional_int(arguments.get("page_to"))
        if page_from is not None and page_to is not None and page_from > page_to:
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

        query_token_list = tokenize(query)
        query_tokens = set(query_token_list)
        section_tokens = set(tokenize(section_query))
        all_eligible_documents: list[ReadingDocument] = []
        for document in self.documents:
            if document.paper_id not in paper_ids:
                continue
            page = _optional_int(document.page)
            if page_from is not None and (page is None or page < page_from):
                continue
            if page_to is not None and (page is None or page > page_to):
                continue
            all_eligible_documents.append(document)

        page_documents = [
            document for document in all_eligible_documents
            if document.surface_kind == "page"
        ]
        eligible_documents = [
            document for document in all_eligible_documents
            if document.surface_kind != "page"
            and (not element_types or document.evidence_element_type() in element_types)
        ]

        body_tokens = [tokenize(document.text) for document in eligible_documents]
        lead_token_lists = [tokens[:LEAD_TOKEN_LIMIT] for tokens in body_tokens]
        passage_token_lists = [
            _passage_token_windows(tokens) if document.physical_page_projection else []
            for document, tokens in zip(eligible_documents, body_tokens)
        ]
        section_token_lists = [
            [token for token in tokenize(document.section) if not token.isdigit()]
            for document in eligible_documents
        ]
        body_statistics = bm25_statistics(body_tokens)
        lead_statistics = bm25_statistics(lead_token_lists)
        passage_statistics = bm25_statistics([
            passage for passages in passage_token_lists for passage in passages
        ])
        section_statistics = bm25_statistics(section_token_lists)
        base_scores: list[float] = []
        for (
            document,
            document_body_tokens,
            document_lead_tokens,
            document_passages,
            document_section_tokens,
        ) in zip(
            eligible_documents,
            body_tokens,
            lead_token_lists,
            passage_token_lists,
            section_token_lists,
        ):
            score = (
                bm25_score(query_token_list, document_body_tokens, body_statistics)
                if query_tokens else 1.0
            )
            if query_tokens:
                score += PASSAGE_SCORE_WEIGHT * max(
                    (
                        bm25_score(query_token_list, passage, passage_statistics)
                        for passage in document_passages
                    ),
                    default=0.0,
                )
                score += LEAD_SCORE_WEIGHT * bm25_score(
                    query_token_list, document_lead_tokens, lead_statistics
                )
                score += SECTION_SCORE_WEIGHT * bm25_score(
                    query_token_list, document_section_tokens, section_statistics
                )
            if section_tokens:
                score += _section_hint_score(section_query, document.section, document.text)
            if query and _normalize(query) in _normalize(document.text):
                score += 2.0
            base_scores.append(score)

        scored: list[_ScoredDocument] = []
        for index, document in enumerate(eligible_documents):
            score = base_scores[index]
            if query_tokens and score > 0:
                # 跨段段落常把同一事实拆到相邻 reading elements。
                adjacent_scores = [
                    base_scores[neighbor_index]
                    for neighbor_index in (index - 1, index + 1)
                    if 0 <= neighbor_index < len(eligible_documents)
                    and eligible_documents[neighbor_index].paper_id == document.paper_id
                    and eligible_documents[neighbor_index].section == document.section
                    and eligible_documents[neighbor_index].element_type
                    == document.element_type
                    == "paragraph"
                    and (
                        not document.physical_page_projection
                        or len(body_tokens[index]) <= ADJACENT_PARAGRAPH_TOKEN_LIMIT
                    )
                    and (
                        not eligible_documents[neighbor_index].physical_page_projection
                        or len(body_tokens[neighbor_index]) <= ADJACENT_PARAGRAPH_TOKEN_LIMIT
                    )
                ]
                if adjacent_scores:
                    score += ADJACENT_PARAGRAPH_SCORE_WEIGHT * max(adjacent_scores)
            if score > 0:
                document_tokens = frozenset(body_tokens[index] + section_token_lists[index])
                scored.append(_ScoredDocument(
                    score=score,
                    document=document,
                    document_order=index,
                    matched_query_tokens=frozenset(query_tokens & document_tokens),
                ))
        scored.sort(key=lambda item: _scored_document_sort_key(item, paper_id_list))
        scored = _deduplicate_candidates(scored)
        broad_query = len(query_tokens) >= BROAD_QUERY_MIN_TOKENS
        # 多主题或多论文查询扩大候选面，避免比较任务只看到每篇论文的第一个表面匹配。
        expanded_candidate_floor = max(
            BROAD_QUERY_BASE_CANDIDATES if broad_query else 0,
            MULTI_PAPER_BASE_CANDIDATES if len(paper_id_list) > 1 else 0,
        )
        candidate_count = max(top_k, expanded_candidate_floor)
        page_scored, page_statistics = _score_page_documents(
            page_documents,
            query_token_list,
            section_query,
            paper_id_list,
        )
        page_scored = [
            item for item in page_scored
            if _repairs_semantic_page_grounding(item, scored)
        ]
        page_candidate_count = min(
            PAGE_GROUNDING_CANDIDATE_LIMIT,
            max(0, candidate_count - 1),
            len(page_scored),
        )
        selected_pages = _select_coverage_candidates(
            page_scored,
            paper_id_list,
            query_term_weights(query_tokens, page_statistics),
            page_candidate_count,
            broad_query=False,
            balanced_paper_floor=True,
        ) if page_candidate_count else []
        selected = _select_coverage_candidates(
            scored,
            paper_id_list,
            query_term_weights(query_tokens, body_statistics),
            max(1, candidate_count - len(selected_pages)),
            broad_query=broad_query,
            balanced_paper_floor=any(
                document.physical_page_projection
                for document in eligible_documents
            ),
        )
        selected = _interleave_page_candidates(selected, selected_pages)
        matched_count = len(scored) + len(page_scored)
        locations = [document.to_location_candidate() for document in selected]
        self.disclosed_location_refs.update(str(item["location_ref"]) for item in locations)
        return {
            "query_text": query,
            "locations": locations,
            "matched_count": matched_count,
            "returned_count": len(locations),
            "coverage": "complete" if len(locations) >= matched_count else "truncated",
        }

    def read_paper_content(self, arguments: JsonMap) -> JsonMap:
        location_refs, error = reading_location_preflight(arguments, self.disclosed_location_refs, "items")
        if error:
            return error
        docs = [self.documents_by_location[ref] for ref in location_refs if ref in self.documents_by_location]
        items: list[JsonMap] = []
        seen: set[str] = set()
        for document in docs:
            if document.source_quote_ref() in seen:
                continue
            seen.add(document.source_quote_ref())
            item = document.to_read_item()
            source_quote = child_map(item["source_quotes"][0])
            self.observations_by_evidence_id[document.source_quote_ref()] = {
                **item,
                **source_quote,
                "source_quote_ref": document.source_quote_ref(),
            }
            items.append(item)
        missing = [ref for ref in location_refs if ref not in self.documents_by_location]
        return {"items": items, "missing_location_refs": missing}

    def get_paper_structure(self, arguments: JsonMap) -> JsonMap:
        paper_id_list, error = reading_structure_preflight(
            arguments,
            self.authorized_paper_ids,
            "items",
        )
        if error:
            return error
        paper_ids = set(paper_id_list)
        structure_type = str(arguments.get("structure_type") or "SECTION").upper()
        if structure_type == "PAGE":
            page_from = _optional_int(arguments.get("page_from"))
            page_to = _optional_int(arguments.get("page_to"))
            items = [
                {
                    "paper_id": doc.paper_id,
                    "title": doc.title,
                    "location_ref": doc.location_ref,
                    "structure_type": "PAGE",
                    "page_from": doc.page,
                    "page_to": doc.page,
                    "section_title": doc.section or None,
                }
                for doc in self.documents
                if doc.paper_id in paper_ids
                and doc.surface_kind == "page"
                and (page_from is None or (_optional_int(doc.page) or 0) >= page_from)
                and (page_to is None or (_optional_int(doc.page) or 0) <= page_to)
            ]
        else:
            query = _normalize(str(arguments.get("section_query") or ""))
            seen: set[tuple[str, str]] = set()
            items = []
            for doc in self.documents:
                key = (doc.paper_id, doc.section)
                if doc.paper_id not in paper_ids or not doc.section or key in seen:
                    continue
                if query and query not in _normalize(doc.section):
                    continue
                seen.add(key)
                items.append({
                    "paper_id": doc.paper_id,
                    "title": doc.title,
                    "location_ref": doc.location_ref,
                    "structure_type": "SECTION",
                    "section_title": doc.section,
                    "section_level": 1,
                    "section_path": doc.section,
                    "display_order": len(items) + 1,
                    "page_from": doc.page,
                    "page_to": doc.page,
                })
        self.disclosed_location_refs.update(str(item["location_ref"]) for item in items)
        return {"structure_type": structure_type, "items": items}

    def get_citation_edges(self, arguments: JsonMap) -> JsonMap:
        paper_id = str(arguments.get("paper_id") or "").strip()
        if not paper_id:
            return recoverable_error(
                "paper_id_required", "TOOL_ARGUMENTS_INVALID", "find_papers_by_identity", "edges"
            )
        if paper_id not in self.authorized_paper_ids:
            return recoverable_error(
                "paper_not_authorized_for_reading", "PAPER_ID_NOT_DISCLOSED", "find_papers_by_identity", "edges",
                unauthorized_paper_ids=[paper_id],
            )
        if not self.dataset.citation_edges:
            return {"edges": [], "papers": [], "coverage": "unavailable"}
        edges = [
            dict(edge)
            for edge in self.dataset.citation_edges
            if edge.get("from_paper_id") == paper_id or edge.get("to_paper_id") == paper_id
        ]
        connected_ids = {
            str(value)
            for edge in edges
            for value in (edge.get("from_paper_id"), edge.get("to_paper_id"))
            if value in self.dataset.paper_records_by_id
        }
        self.authorized_paper_ids.update(connected_ids)
        return {
            "edges": edges,
            "papers": [
                _paper_card(candidate_id, self.dataset.paper_records_by_id[candidate_id])
                for candidate_id in sorted(connected_ids)
            ],
            "coverage": "complete",
        }


# ElementType 白名单：与 harness 工具 schema 同步。
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
