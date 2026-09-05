from __future__ import annotations

import re
from typing import Any

from ..corpus.gateway import ArxivReader, CorpusGatewayError
from ..utils.models import JsonMap, as_list, child_map

MAX_RESULTS = 10
MAX_QUERY_CHARS = 200
MAX_ABSTRACT_CHARS = 1_000
MAX_CALLS_PER_TURN = 1
_ARXIV_ID = re.compile(r"(?:\d{4}\.\d{4,5}|[A-Za-z-]+(?:\.[A-Za-z-]+)?/\d{7})(?:v\d+)?$")


def tool_definition() -> JsonMap:
    return {
        "type": "function",
        "function": {
            "name": "search_arxiv",
            "description": (
                "Search arXiv for external paper candidates when the user explicitly asks for external or arXiv "
                "papers. This returns bounded discovery metadata only, never paper evidence; do not use it for "
                "ordinary questions about the current PaperLoom corpus."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "query_text": {"type": "string", "maxLength": MAX_QUERY_CHARS},
                    "authors": {
                        "type": "array",
                        "maxItems": 5,
                        "items": {"type": "string", "maxLength": 100},
                    },
                    "categories": {
                        "type": "array",
                        "maxItems": 5,
                        "items": {"type": "string", "maxLength": 32},
                    },
                    "year_from": {"type": "integer", "minimum": 1900, "maximum": 2100},
                    "year_to": {"type": "integer", "minimum": 1900, "maximum": 2100},
                },
                "additionalProperties": False,
            },
        },
    }


def search_arxiv(reader: ArxivReader | None, arguments: JsonMap) -> JsonMap:
    error = _validate(arguments)
    if error:
        return error
    if reader is None:
        return _error("ARXIV_UNAVAILABLE", "arXiv search is unavailable")
    try:
        response = reader.search_arxiv(arguments)
    except CorpusGatewayError as error:
        if error.status_code == 400:
            return _error("ARXIV_ARGUMENTS_INVALID", "arXiv search arguments are invalid")
        return _error("ARXIV_UNAVAILABLE", "arXiv is temporarily unavailable")
    except Exception:
        return _error("ARXIV_UNAVAILABLE", "arXiv is temporarily unavailable")

    results: list[JsonMap] = []
    for raw in as_list(response.get("results")):
        item = child_map(raw)
        arxiv_id = str(item.get("arxiv_id") or "").strip()
        if not _ARXIV_ID.fullmatch(arxiv_id):
            continue
        results.append({
            "arxiv_id": arxiv_id,
            "title": _compact(item.get("title"), 200),
            "authors": [
                _compact(author, 100)
                for author in as_list(item.get("authors"))
                if isinstance(author, str) and author.strip()
            ][:20],
            "published": _compact(item.get("published"), 40),
            "updated": _compact(item.get("updated"), 40),
            "abstract_preview": _compact(item.get("abstract_preview"), MAX_ABSTRACT_CHARS),
            "pdf_url": _safe_pdf_url(item.get("pdf_url"), arxiv_id),
            "metadata_only": True,
        })
        if len(results) >= MAX_RESULTS:
            break
    return {
        "results": results,
        "returned_count": len(results),
        "coverage": "bounded",
        "metadata_only": True,
    }


def redact_arguments(arguments: JsonMap) -> JsonMap:
    """Keep long-lived progress/trace records free of the research query text."""

    return {
        "query_text_chars": len(str(arguments.get("query_text") or "")),
        "author_count": len(as_list(arguments.get("authors"))),
        "category_count": len(as_list(arguments.get("categories"))),
        "has_year_filter": arguments.get("year_from") is not None or arguments.get("year_to") is not None,
    }


def _validate(arguments: JsonMap) -> JsonMap | None:
    query = arguments.get("query_text", "")
    if not isinstance(query, str) or len(query.strip()) > MAX_QUERY_CHARS:
        return _error("ARXIV_ARGUMENTS_INVALID", "query_text is invalid")
    authors = arguments.get("authors", [])
    categories = arguments.get("categories", [])
    if not _bounded_strings(authors, 5, 100) or not _bounded_strings(categories, 5, 32):
        return _error("ARXIV_ARGUMENTS_INVALID", "arXiv filters are invalid")
    year_from = arguments.get("year_from")
    year_to = arguments.get("year_to")
    if year_from is not None and (not isinstance(year_from, int) or year_from < 1900 or year_from > 2100):
        return _error("ARXIV_ARGUMENTS_INVALID", "year_from is invalid")
    if year_to is not None and (not isinstance(year_to, int) or year_to < 1900 or year_to > 2100):
        return _error("ARXIV_ARGUMENTS_INVALID", "year_to is invalid")
    if year_from is not None and year_to is not None and year_from > year_to:
        return _error("ARXIV_ARGUMENTS_INVALID", "year_from must not exceed year_to")
    if not query.strip() and not authors and not categories and year_from is None and year_to is None:
        return _error("ARXIV_ARGUMENTS_INVALID", "at least one arXiv search condition is required")
    return None


def _bounded_strings(value: Any, max_items: int, max_chars: int) -> bool:
    return isinstance(value, list) and len(value) <= max_items and all(
        isinstance(item, str) and 0 < len(item.strip()) <= max_chars for item in value
    )


def _compact(value: object, max_chars: int) -> str:
    text = " ".join(str(value or "").split())
    return text if len(text) <= max_chars else text[:max_chars].rstrip()


def _safe_pdf_url(value: object, arxiv_id: str) -> str:
    url = str(value or "").strip()
    if url.startswith("https://arxiv.org/pdf/") or url.startswith("https://export.arxiv.org/pdf/"):
        return url
    return f"https://arxiv.org/pdf/{arxiv_id}"


def _error(code: str, message: str) -> JsonMap:
    return {"error": code, "message": message, "recoverable": True}
