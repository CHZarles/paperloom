from __future__ import annotations

from ..corpus.tools import PAPER_RESULT_LIMIT, SEARCH_RESULT_LIMIT, recoverable_error
from ..utils.models import JsonMap, as_list


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
        if invalid_integer(arguments, key, minimum=minimum, maximum=maximum):
            return recoverable_error(
                f"{key}_invalid", "TOOL_ARGUMENTS_INVALID", "search_paper_candidates", "candidates"
            )
    if _invalid_range(arguments, "year_from", "year_to"):
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
    if invalid_integer(arguments, "year", minimum=0, maximum=None):
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
    paper_ids = [str(value).strip() if value is not None else "" for value in as_list(arguments.get("paper_ids"))]
    if any(not paper_id for paper_id in paper_ids):
        return [], recoverable_error(
            "paper_ids_invalid", "TOOL_ARGUMENTS_INVALID", "find_papers_by_identity", result_key
        )
    if not paper_ids:
        return [], recoverable_error(
            "paper_ids_required", "TOOL_ARGUMENTS_INVALID", "find_papers_by_identity", result_key
        )
    if len(set(paper_ids)) != len(paper_ids) or len(paper_ids) > SEARCH_RESULT_LIMIT:
        return [], recoverable_error(
            "paper_ids_invalid", "TOOL_ARGUMENTS_INVALID", "find_papers_by_identity", result_key
        )
    unauthorized = [paper_id for paper_id in paper_ids if paper_id not in authorized_paper_ids]
    if unauthorized:
        return paper_ids, recoverable_error(
            "paper_not_authorized_for_reading", "PAPER_ID_NOT_DISCLOSED", "find_papers_by_identity", result_key,
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
    if any(invalid_integer(arguments, key, minimum=1, maximum=None) for key in ("page_from", "page_to")) \
            or _invalid_range(arguments, "page_from", "page_to"):
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
        return [], recoverable_error(
            "location_refs_required", "TOOL_ARGUMENTS_INVALID", next_action, result_key
        )
    if len(set(refs)) != len(refs) or len(refs) > SEARCH_RESULT_LIMIT:
        return [], recoverable_error(
            "location_refs_invalid", "TOOL_ARGUMENTS_INVALID", next_action, result_key
        )
    undisclosed = [ref for ref in refs if ref not in disclosed_location_refs]
    if undisclosed:
        return [], recoverable_error(
            "location_ref_not_disclosed", "LOCATION_NOT_DISCLOSED", next_action, result_key,
            location_refs=undisclosed,
        )
    return refs, None


def invalid_integer(arguments: JsonMap, key: str, *, minimum: int, maximum: int | None) -> bool:
    value = arguments.get(key)
    if value is None:
        return False
    try:
        number = int(value)
    except (TypeError, ValueError):
        return True
    return isinstance(value, bool) or number < minimum or (maximum is not None and number > maximum)


def _invalid_range(arguments: JsonMap, lower_key: str, upper_key: str) -> bool:
    try:
        lower = arguments.get(lower_key)
        upper = arguments.get(upper_key)
        return lower is not None and upper is not None and int(lower) > int(upper)
    except (TypeError, ValueError):
        return True
