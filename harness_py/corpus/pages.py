from __future__ import annotations

import re


def parse_positive_page(value: object) -> int | None:
    if value is None or isinstance(value, bool):
        return None
    if isinstance(value, int):
        page = value
    elif isinstance(value, float):
        if not value.is_integer():
            return None
        page = int(value)
    elif isinstance(value, str):
        stripped = value.strip()
        if not stripped:
            return None
        try:
            page = int(stripped)
        except ValueError:
            return None
    else:
        return None
    return page if page > 0 else None


def page_matches(expected: object, actual: object) -> bool:
    """Match pages with the same strict positive-integer rule used by authoring validation."""
    expected_page = parse_positive_page(expected)
    return expected_page is not None and parse_positive_page(actual) == expected_page


def normalize_text(value: str) -> str:
    return " ".join(re.findall(r"[a-zA-Z0-9_]+", value.casefold()))


def contains_normalized_phrase(text: str, phrase: str) -> bool:
    if not text or not phrase:
        return False
    return f" {phrase} " in f" {text} "
