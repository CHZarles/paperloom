from __future__ import annotations

import hashlib
from dataclasses import replace

from .agent_harness import ResearchAgentHarness
from .conversation import ConversationState
from .llm import ChatModel
from .models import GoldenDataset, JsonMap


class LiveResearchChatHarness:
    """Live chat wrapper around the same artifact-producing agent loop."""

    def __init__(self, model: ChatModel, max_turns: int = 8, max_completion_tokens: int = 3000):
        self.agent = ResearchAgentHarness(
            model,
            max_turns=max_turns,
            max_completion_tokens=max_completion_tokens,
        )

    def run_question(self, dataset: GoldenDataset, question: str) -> JsonMap:
        run, _state = self.run_turn(
            dataset,
            ConversationState.new("transient_live_chat"),
            question,
        )
        return run

    def run_turn(
        self,
        dataset: GoldenDataset,
        state: ConversationState,
        user_message: str,
    ) -> tuple[JsonMap, ConversationState]:
        if not user_message.strip():
            raise ValueError("user_message is required")
        scoped_dataset = _dataset_for_scope(dataset, state.effective_scope_paper_ids(dataset))
        run = self.agent.run_case(scoped_dataset, _live_case(scoped_dataset, user_message, state))
        return run, state.updated_from_run(scoped_dataset, run, user_message)


def _live_case(dataset: GoldenDataset, question: str, state: ConversationState | None = None) -> JsonMap:
    if not question.strip():
        raise ValueError("question is required")
    state = state or ConversationState.new("transient_live_chat")
    digest = hashlib.sha1(
        (
            state.conversation_id
            + "\n"
            + str(state.turn_index + 1)
            + "\n"
            + question
            + "\n"
            + "\n".join(sorted(dataset.paper_records_by_id))
        ).encode("utf-8")
    ).hexdigest()[:12]
    paper_ids = sorted(dataset.paper_records_by_id)
    return {
        "id": f"live_chat_{digest}",
        "question": {"text": question},
        "conversation_context": state.prompt_context(dataset),
        "capability_tags": ["live_research_chat"],
        "expected_result": {"kind": "live_answer"},
        "expected_intent": {},
        "expected_retrieval_plan": {},
        "corpus_scope": {
            "required_paper_ids": [],
            "allowed_paper_ids": paper_ids,
        },
        "gold_evidence": {
            "required_anchor_ids": [],
            "forbidden_anchor_ids": [],
        },
        "gold_claims": [],
        "answer_contract": {"type": "live_research_answer"},
        "required_trace": {"obligations": []},
    }


def _dataset_for_scope(dataset: GoldenDataset, paper_ids: list[str]) -> GoldenDataset:
    scoped = set(paper_ids)
    if not scoped:
        return dataset
    return replace(
        dataset,
        paper_records_by_id={
            paper_id: record
            for paper_id, record in dataset.paper_records_by_id.items()
            if paper_id in scoped
        },
        reading_models_by_paper_id={
            paper_id: model
            for paper_id, model in dataset.reading_models_by_paper_id.items()
            if paper_id in scoped
        },
        citation_edges=[
            edge for edge in dataset.citation_edges
            if edge.get("from_paper_id") in scoped or edge.get("to_paper_id") in scoped
        ],
    )
