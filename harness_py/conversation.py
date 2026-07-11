from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field, replace
from pathlib import Path
from typing import Any

from .models import GoldenDataset, JsonMap, as_list, child_map


@dataclass(frozen=True)
class ConversationState:
    conversation_id: str
    turn_index: int = 0
    scope_paper_ids: list[str] = field(default_factory=list)
    selected_paper_ids: list[str] = field(default_factory=list)
    selected_evidence_ids: list[str] = field(default_factory=list)
    active_task: JsonMap = field(default_factory=dict)
    pending_interaction: JsonMap = field(default_factory=dict)
    message_history: list[JsonMap] = field(default_factory=list)
    evidence_items_by_id: dict[str, JsonMap] = field(default_factory=dict)
    claims_by_id: dict[str, JsonMap] = field(default_factory=dict)
    memory_notes: list[JsonMap] = field(default_factory=list)
    last_run_id: str | None = None
    last_answer_summary: str = ""

    @classmethod
    def new(
        cls,
        conversation_id: str,
        scope_paper_ids: list[str] | None = None,
    ) -> ConversationState:
        return cls(conversation_id=conversation_id, scope_paper_ids=_unique(scope_paper_ids or []))

    @classmethod
    def from_dict(cls, value: JsonMap) -> ConversationState:
        pending_interaction = _pending_interaction(value.get("pending_interaction"))
        return cls(
            conversation_id=str(value.get("conversation_id") or "live_conversation"),
            turn_index=int(value.get("turn_index") or 0),
            scope_paper_ids=[str(item) for item in as_list(value.get("scope_paper_ids"))],
            selected_paper_ids=[str(item) for item in as_list(value.get("selected_paper_ids"))],
            selected_evidence_ids=[str(item) for item in as_list(value.get("selected_evidence_ids"))],
            active_task=child_map(value.get("active_task")),
            pending_interaction=pending_interaction,
            message_history=[child_map(item) for item in as_list(value.get("message_history"))],
            evidence_items_by_id={
                str(key): child_map(item)
                for key, item in child_map(value.get("evidence_items_by_id")).items()
            },
            claims_by_id={
                str(key): child_map(item)
                for key, item in child_map(value.get("claims_by_id")).items()
            },
            memory_notes=[child_map(item) for item in as_list(value.get("memory_notes"))],
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

    def prompt_context(self, dataset: GoldenDataset, max_messages: int = 8, max_evidence: int = 10) -> JsonMap:
        scope_paper_ids = self.effective_scope_paper_ids(dataset)
        selected_evidence = [
            _evidence_prompt_card(self.evidence_items_by_id[evidence_id])
            for evidence_id in self.selected_evidence_ids
            if evidence_id in self.evidence_items_by_id
            and self.evidence_items_by_id[evidence_id].get("citeable") is not False
        ]
        if len(selected_evidence) < max_evidence:
            for evidence_id, item in self.evidence_items_by_id.items():
                if evidence_id in self.selected_evidence_ids:
                    continue
                if item.get("citeable") is False:
                    continue
                selected_evidence.append(_evidence_prompt_card(item))
                if len(selected_evidence) >= max_evidence:
                    break
        selected_paper_refs = [
            _selected_paper_ref(position, paper_id, dataset, self.evidence_items_by_id)
            for position, paper_id in enumerate(self.selected_paper_ids, start=1)
        ]
        return {
            "conversation_id": self.conversation_id,
            "turn_index": self.turn_index,
            "scope_paper_count": len(scope_paper_ids),
            "selected_paper_ids": self.selected_paper_ids,
            "selected_paper_refs": selected_paper_refs,
            "selected_evidence_ids": self.selected_evidence_ids,
            "selected_evidence_refs": selected_evidence[:max_evidence],
            "active_task": self.active_task,
            "pending_interaction": self.pending_interaction,
            "recent_messages": self.message_history[-max_messages:],
            "last_answer_summary": self.last_answer_summary,
            "memory_notes": self.memory_notes[-max_messages:],
        }

    def effective_scope_paper_ids(self, dataset: GoldenDataset) -> list[str]:
        known = set(dataset.paper_records_by_id)
        scoped = [paper_id for paper_id in self.scope_paper_ids if paper_id in known]
        return scoped or sorted(known)

    def with_pending_interaction(self, value: JsonMap) -> ConversationState:
        pending_interaction = _pending_interaction(value)
        if not pending_interaction:
            raise ValueError("pending interaction requires interaction_id and kind")
        return replace(self, pending_interaction=pending_interaction)

    def with_active_task(self, value: JsonMap) -> ConversationState:
        active_task = child_map(value)
        if not active_task.get("goal"):
            raise ValueError("active task requires goal")
        return replace(self, active_task=active_task)

    def updated_from_run(self, dataset: GoldenDataset, run: JsonMap, user_message: str) -> ConversationState:
        answer = child_map(run.get("research_answer"))
        memory_update = child_map(run.get("memory_update"))
        run_id = str(run.get("run_id") or "")
        ledger_items = {
            str(item.get("evidence_id")): child_map(item)
            for item in as_list(child_map(run.get("evidence_ledger")).get("items"))
            if child_map(item).get("evidence_id")
        }
        claims = {
            _claim_memory_id(run_id, child_map(claim).get("claim_id")): _claim_memory_item(run, claim)
            for claim in as_list(child_map(run.get("claim_graph")).get("claims"))
            if child_map(claim).get("claim_id")
        }
        selected_evidence_ids = _validated_ids(
            as_list(memory_update.get("selected_evidence_ids")) or as_list(answer.get("cited_evidence_ids")),
            {**self.evidence_items_by_id, **ledger_items},
        )
        if not selected_evidence_ids and str(answer.get("status") or "") != "COMPLETED":
            selected_evidence_ids = self.selected_evidence_ids
        derived_paper_ids = [
            str(ledger_items[evidence_id].get("paper_id"))
            for evidence_id in selected_evidence_ids
            if ledger_items.get(evidence_id, {}).get("paper_id")
        ]
        selected_paper_ids = _validated_ids(
            as_list(memory_update.get("selected_paper_ids")) or derived_paper_ids or self.selected_paper_ids,
            dataset.paper_records_by_id,
        )
        scope_paper_ids = _validated_ids(
            as_list(memory_update.get("scope_paper_ids")),
            dataset.paper_records_by_id,
        ) or self.scope_paper_ids
        pending_interaction = _pending_interaction(memory_update.get("pending_interaction"))
        if answer.get("status") == "NEEDS_CLARIFICATION" and not pending_interaction:
            pending_interaction = {
                "interaction_id": f"pending_{run_id or self.turn_index + 1}",
                "kind": "free_text",
                "question": str(answer.get("summary") or "Please clarify the intended interpretation."),
                "options": [],
            }
        elif answer.get("status") == "FAILED_TECHNICAL" and not pending_interaction:
            pending_interaction = self.pending_interaction
        answer_status = str(answer.get("status") or "")
        active_task = (
            {}
            if answer_status == "COMPLETED"
            else self.active_task
        )
        note = memory_update.get("note") or memory_update.get("notes")
        memory_notes = self.memory_notes
        if note:
            memory_notes = [
                *memory_notes,
                {"turn_index": self.turn_index + 1, "note": note, "run_id": run_id},
            ][-20:]
        return replace(
            self,
            turn_index=self.turn_index + 1,
            scope_paper_ids=_unique(scope_paper_ids),
            selected_paper_ids=_unique(selected_paper_ids),
            selected_evidence_ids=_unique(selected_evidence_ids),
            active_task=active_task,
            pending_interaction=pending_interaction,
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
                    "run_id": run_id,
                    "status": answer.get("status"),
                    "summary": answer.get("summary") or "",
                    "cited_evidence_ids": as_list(answer.get("cited_evidence_ids")),
                },
            ][-24:],
            evidence_items_by_id={**self.evidence_items_by_id, **ledger_items},
            claims_by_id={**self.claims_by_id, **claims},
            memory_notes=memory_notes,
            last_run_id=run_id,
            last_answer_summary=str(answer.get("summary") or ""),
        )


def _evidence_prompt_card(item: JsonMap) -> JsonMap:
    return {
        "evidence_id": item.get("evidence_id"),
        "paper_id": item.get("paper_id"),
        "title": item.get("title"),
        "section": item.get("section"),
        "page": item.get("page"),
        "location": item.get("location"),
        "element_type": item.get("element_type"),
        "span_text": str(item.get("span_text") or "")[:700],
    }


def _selected_paper_ref(
    position: int,
    paper_id: str,
    dataset: GoldenDataset,
    evidence_items_by_id: dict[str, JsonMap],
) -> JsonMap:
    candidate = next(
        (
            item for item in evidence_items_by_id.values()
            if item.get("paper_id") == paper_id and item.get("element_type") == "paper_candidate"
        ),
        {},
    )
    identity = child_map(child_map(dataset.paper_records_by_id.get(paper_id)).get("identity"))
    return {
        "position": position,
        "paper_id": paper_id,
        "title": candidate.get("title") or identity.get("title") or paper_id,
    }


def _claim_memory_id(run_id: str, claim_id: Any) -> str:
    return f"{run_id}:{claim_id}" if run_id else str(claim_id)


def _claim_memory_item(run: JsonMap, claim: Any) -> JsonMap:
    value = child_map(claim)
    return {
        "run_id": run.get("run_id"),
        "claim_id": value.get("claim_id"),
        "text": value.get("text"),
        "status": value.get("status"),
        "supporting_evidence_ids": as_list(value.get("supporting_evidence_ids")),
    }


def _validated_ids(values: list[Any], known: dict[str, Any]) -> list[str]:
    return _unique(str(value) for value in values if str(value) in known)


def _unique(values: Any) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for value in values:
        text = str(value)
        if not text or text in seen:
            continue
        seen.add(text)
        result.append(text)
    return result


def _pending_interaction(value: Any) -> JsonMap:
    interaction = child_map(value)
    if not interaction:
        return {}
    interaction_id = str(interaction.get("interaction_id") or "").strip()
    kind = str(interaction.get("kind") or "").strip()
    if not interaction_id or kind not in {"choice", "free_text"}:
        return {}
    options: list[JsonMap] = []
    for item in as_list(interaction.get("options")):
        option = child_map(item)
        option_id = str(option.get("id") or "").strip()
        if not option_id:
            continue
        options.append({
            "id": option_id,
            "label": str(option.get("label") or option_id),
            "task_patch": child_map(option.get("task_patch")),
        })
    return {
        "interaction_id": interaction_id,
        "kind": kind,
        "question": str(interaction.get("question") or ""),
        "options": options,
    }
