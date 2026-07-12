from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from ..core.models import JsonMap


@dataclass(frozen=True)
class ResearchMemory:
    selected_paper_ids: list[str] = field(default_factory=list)
    selected_evidence_ids: list[str] = field(default_factory=list)
    evidence_items_by_id: dict[str, JsonMap] = field(default_factory=dict)


class RequestBackedSession:
    """One-run SDK Session seeded only from request-provided text history."""

    session_settings = None

    def __init__(self, session_id: str, history: list[JsonMap]):
        self.session_id = session_id
        self._items: list[Any] = [
            {"role": str(item["role"]), "content": str(item["content"])}
            for item in history
            if item.get("role") in {"user", "assistant"}
            and str(item.get("content") or "").strip()
        ]

    async def get_items(self, limit: int | None = None) -> list[Any]:
        items = self._items if limit is None else self._items[-max(0, limit):]
        return list(items)

    async def add_items(self, items: list[Any]) -> None:
        self._items.extend(items)

    async def pop_item(self) -> Any | None:
        return self._items.pop() if self._items else None

    async def clear_session(self) -> None:
        self._items.clear()


def request_session_input(history: list[Any], new_input: list[Any]) -> list[Any]:
    return [*history, *new_input]
