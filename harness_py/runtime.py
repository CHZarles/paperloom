from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING, Callable, Protocol
from uuid import uuid4

from .agent_harness import ResearchAgentHarness
from .memory import ResearchMemory
from .models import GoldenDataset, JsonMap

if TYPE_CHECKING:
    from .eval_recorder import EvalRecorder
    from .llm import ChatModel


ProgressListener = Callable[[JsonMap], None]
CancellationCheck = Callable[[], bool]


def new_run_id() -> str:
    return f"run_{uuid4().hex}"


@dataclass(frozen=True)
class TurnExecutionInput:
    dataset: GoldenDataset
    case_id: str
    run_id: str
    question: str
    conversation_messages: list[JsonMap]
    research_memory: ResearchMemory
    progress_listener: ProgressListener | None = None
    should_cancel: CancellationCheck | None = None
    eval_recorder: EvalRecorder | None = None


@dataclass(frozen=True)
class TurnExecutionResult:
    run: JsonMap


class HarnessRuntime(Protocol):
    def run_turn(self, turn: TurnExecutionInput) -> TurnExecutionResult:
        ...


class LegacyHarnessRuntime:
    """Compatibility adapter around the hand-written ReAct loop."""

    def __init__(self, model: ChatModel, max_completion_tokens: int = 3000):
        self.agent = ResearchAgentHarness(model, max_completion_tokens=max_completion_tokens)

    def run_turn(self, turn: TurnExecutionInput) -> TurnExecutionResult:
        run = self.agent.run_turn(
            turn.dataset,
            case_id=turn.case_id,
            question=turn.question,
            conversation_messages=turn.conversation_messages,
            prior_evidence=turn.research_memory.evidence_items_by_id,
            selected_paper_ids=turn.research_memory.selected_paper_ids,
            progress_listener=turn.progress_listener,
            should_cancel=turn.should_cancel,
        )
        run["run_id"] = turn.run_id
        return TurnExecutionResult(run=run)
