from __future__ import annotations

from dataclasses import replace

from .conversation import ConversationState
from .models import GoldenDataset, JsonMap, as_list, child_map


OUTCOMES = {"answered", "needs_clarification", "abstained", "partial"}
CITATION_POLICIES = {"required", "optional", "forbidden"}


def case_messages(case: JsonMap) -> list[JsonMap]:
    return [child_map(item) for item in as_list(case.get("messages"))]


def case_question(case: JsonMap) -> str:
    messages = case_messages(case)
    return str(messages[-1].get("content") or "") if messages else ""


def case_history(case: JsonMap) -> list[JsonMap]:
    return case_messages(case)[:-1]


def case_expect(case: JsonMap) -> JsonMap:
    return child_map(case.get("expect"))


def pack_for_case(dataset: GoldenDataset, case: JsonMap) -> JsonMap:
    pack_id = str(case.get("paper_pack") or "")
    for pack in dataset.paper_packs:
        if str(pack.get("id") or "") == pack_id:
            return pack
    raise ValueError(f"unknown paper pack for case {case.get('id')}: {pack_id}")


def paper_ids_for_case(dataset: GoldenDataset, case: JsonMap) -> list[str]:
    return [
        str(child_map(paper).get("id"))
        for paper in as_list(pack_for_case(dataset, case).get("papers"))
        if child_map(paper).get("id")
    ]


def conversation_state_for_case(dataset: GoldenDataset, case: JsonMap) -> ConversationState:
    history: list[JsonMap] = []
    turn_index = 0
    for message in case_history(case):
        role = str(message.get("role") or "")
        content = str(message.get("content") or "")
        if role == "user":
            turn_index += 1
            history.append({"role": "user", "turn_index": turn_index, "content": content})
        else:
            history.append({
                "role": "assistant",
                "turn_index": max(turn_index, 1),
                "content": content,
                "summary": content,
            })
    return replace(
        ConversationState.new(
            f"golden_{case.get('id')}",
            scope_paper_ids=paper_ids_for_case(dataset, case),
        ),
        turn_index=turn_index,
        message_history=history,
    )
