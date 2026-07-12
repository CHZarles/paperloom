from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field, replace
from pathlib import Path

from .models import GoldenDataset, JsonMap, as_list, child_map


@dataclass(frozen=True)
class ConversationState:
    conversation_id: str
    turn_index: int = 0
    scope_paper_ids: list[str] = field(default_factory=list)
    selected_paper_ids: list[str] = field(default_factory=list)
    selected_evidence_ids: list[str] = field(default_factory=list)
    message_history: list[JsonMap] = field(default_factory=list)
    evidence_items_by_id: dict[str, JsonMap] = field(default_factory=dict)
    tool_traces: list[JsonMap] = field(default_factory=list)
    last_run_id: str | None = None
    last_answer_summary: str = ""

    @classmethod
    def new(
        cls,
        conversation_id: str,
        scope_paper_ids: list[str] | None = None,
    ) -> ConversationState:
        return cls(
            conversation_id=conversation_id,
            scope_paper_ids=_unique(scope_paper_ids or []),
        )

    @classmethod
    def from_dict(cls, value: JsonMap) -> ConversationState:
        return cls(
            conversation_id=str(value.get("conversation_id") or "live_conversation"),
            turn_index=int(value.get("turn_index") or 0),
            scope_paper_ids=_strings(value.get("scope_paper_ids")),
            selected_paper_ids=_strings(value.get("selected_paper_ids")),
            selected_evidence_ids=_strings(value.get("selected_evidence_ids")),
            message_history=[
                _message_item(child_map(item))
                for item in as_list(value.get("message_history"))
                if _message_item(child_map(item))
            ],
            evidence_items_by_id={
                str(key): child_map(item)
                for key, item in child_map(value.get("evidence_items_by_id")).items()
            },
            tool_traces=[child_map(item) for item in as_list(value.get("tool_traces"))],
            last_run_id=value.get("last_run_id"),
            last_answer_summary=str(value.get("last_answer_summary") or ""),
        )

    @classmethod
    def load(cls, path: str | Path) -> ConversationState:
        with Path(path).open("r", encoding="utf-8") as handle:
            payload = json.load(handle)
        if not isinstance(payload, dict):
            raise ValueError(f"conversation state must be a JSON object: {path}")
        return cls.from_dict(payload)

    def to_dict(self) -> JsonMap:
        return asdict(self)

    def save(self, path: str | Path) -> None:
        target = Path(path)
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open("w", encoding="utf-8") as handle:
            json.dump(self.to_dict(), handle, indent=2, sort_keys=True, ensure_ascii=False)
            handle.write("\n")

    def effective_scope_paper_ids(self, dataset: GoldenDataset) -> list[str]:
        known = set(dataset.paper_records_by_id)
        scoped = [paper_id for paper_id in self.scope_paper_ids if paper_id in known]
        return scoped or sorted(known)

    def model_messages(self) -> list[JsonMap]:
        return [
            {"role": item["role"], "content": item["content"]}
            for item in self.message_history
            if item.get("role") in {"user", "assistant"} and item.get("content")
        ]

    def prompt_context(self, dataset: GoldenDataset | None = None) -> JsonMap:
        return {
            "conversation_id": self.conversation_id,
            "turn_index": self.turn_index,
            "recent_messages": self.model_messages(),
            "selected_paper_ids": self.selected_paper_ids,
            "selected_evidence_ids": self.selected_evidence_ids,
            "selected_evidence_refs": [
                _evidence_card(self.evidence_items_by_id[evidence_id])
                for evidence_id in self.selected_evidence_ids
                if evidence_id in self.evidence_items_by_id
            ],
        }

    def reset(self) -> ConversationState:
        return replace(
            self,
            turn_index=0,
            selected_paper_ids=[],
            selected_evidence_ids=[],
            message_history=[],
            evidence_items_by_id={},
            tool_traces=[],
            last_run_id=None,
            last_answer_summary="",
        )

    def updated_from_run(
        self,
        dataset: GoldenDataset,
        run: JsonMap,
        user_message: str,
    ) -> ConversationState:
        answer = child_map(run.get("research_answer"))
        run_id = str(run.get("run_id") or "")
        ledger_items = {
            str(item.get("evidence_id")): child_map(item)
            for item in as_list(child_map(run.get("evidence_ledger")).get("items"))
            if child_map(item).get("evidence_id")
        }
        cited_ids = _validated_ids(
            as_list(answer.get("cited_evidence_ids")),
            {**self.evidence_items_by_id, **ledger_items},
        )
        selected_evidence_ids = cited_ids or self.selected_evidence_ids
        cited_papers = _validated_ids(
            [
                child_map({**self.evidence_items_by_id, **ledger_items}.get(evidence_id)).get("paper_id")
                for evidence_id in cited_ids
            ],
            dataset.paper_records_by_id,
        )
        selected_paper_ids = cited_papers or self.selected_paper_ids
        assistant_text = str(answer.get("markdown") or answer.get("summary") or "").strip()
        return replace(
            self,
            turn_index=self.turn_index + 1,
            selected_paper_ids=_unique(selected_paper_ids),
            selected_evidence_ids=_unique(selected_evidence_ids),
            message_history=[
                *self.message_history,
                {
                    "role": "user",
                    "turn_index": self.turn_index + 1,
                    "content": user_message,
                },
                {
                    "role": "assistant",
                    "turn_index": self.turn_index + 1,
                    "content": assistant_text,
                    "run_id": run_id,
                    "cited_evidence_ids": cited_ids,
                },
            ],
            evidence_items_by_id={**self.evidence_items_by_id, **ledger_items},
            tool_traces=[
                *self.tool_traces,
                {
                    "turn_index": self.turn_index + 1,
                    "run_id": run_id,
                    "skills_used": as_list(run.get("skills_used")),
                    "tool_calls": as_list(run.get("react_trace")),
                },
            ],
            last_run_id=run_id,
            last_answer_summary=str(answer.get("summary") or ""),
        )


def _message_item(item: JsonMap) -> JsonMap:
    role = str(item.get("role") or "")
    content = str(item.get("content") or item.get("summary") or "").strip()
    if role not in {"user", "assistant"} or not content:
        return {}
    return {
        "role": role,
        "turn_index": int(item.get("turn_index") or 0),
        "content": content,
        **({"run_id": item.get("run_id")} if item.get("run_id") else {}),
        **(
            {"cited_evidence_ids": _strings(item.get("cited_evidence_ids"))}
            if item.get("cited_evidence_ids") else {}
        ),
    }


def _evidence_card(item: JsonMap) -> JsonMap:
    return {
        "evidence_id": item.get("evidence_id"),
        "paper_id": item.get("paper_id"),
        "title": item.get("title"),
        "section": item.get("section"),
        "page": item.get("page"),
        "span_text": str(item.get("span_text") or "")[:900],
    }


def _strings(value) -> list[str]:
    return _unique(str(item) for item in as_list(value) if item is not None and str(item))


def _validated_ids(values, known: dict[str, object]) -> list[str]:
    return _unique(str(value) for value in values if str(value) in known)


def _unique(values) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for value in values:
        text = str(value or "")
        if text and text not in seen:
            seen.add(text)
            result.append(text)
    return result
