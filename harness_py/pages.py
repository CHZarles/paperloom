from __future__ import annotations


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
