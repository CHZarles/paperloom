from __future__ import annotations

from dataclasses import dataclass, field

from .models import JsonMap


@dataclass(frozen=True)
class ResearchMemory:
    selected_paper_ids: list[str] = field(default_factory=list)
    selected_evidence_ids: list[str] = field(default_factory=list)
    evidence_items_by_id: dict[str, JsonMap] = field(default_factory=dict)
