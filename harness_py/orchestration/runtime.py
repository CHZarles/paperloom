"""产品编排层与具体 Agent 实现之间的最小运行时契约。

上层只认识 ``HarnessRuntime.run_turn``，具体 Agents SDK 细节不会扩散到产品编排层。新能力
通常应扩展 Agent、Tool 或 Context，而不是不断扩大这个接口。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING, Callable, Protocol
from uuid import uuid4

from ..core.models import GoldenDataset, JsonMap
from .memory import ResearchMemory

if TYPE_CHECKING:
    from ..corpus.gateway import CorpusReader
    from ..evaluation.eval_recorder import EvalRecorder
    from ..transport.provider_config import ProviderConfig


ProgressListener = Callable[[JsonMap], None]
CancellationCheck = Callable[[], bool]


def new_run_id() -> str:
    return f"run_{uuid4().hex}"


@dataclass(frozen=True)
class TurnExecutionInput:
    """执行一个研究回合所需的全部输入。

    该对象冻结后交给 Runtime；本轮可变状态由 ResearchRunContext 另行创建。
    """

    dataset: GoldenDataset
    case_id: str
    run_id: str
    question: str
    conversation_messages: list[JsonMap]
    research_memory: ResearchMemory
    corpus_reader: CorpusReader | None = None
    progress_listener: ProgressListener | None = None
    should_cancel: CancellationCheck | None = None
    eval_recorder: EvalRecorder | None = None


@dataclass(frozen=True)
class TurnExecutionResult:
    """Runtime 的统一返回值，当前只包含标准化 Harness Run。"""

    run: JsonMap


class HarnessRuntime(Protocol):
    """所有研究运行时必须实现的唯一方法。"""

    def run_turn(self, turn: TurnExecutionInput) -> TurnExecutionResult:
        ...


def build_harness_runtime(
    provider: ProviderConfig,
    *,
    max_completion_tokens: int = 3000,
) -> HarnessRuntime:
    """Construct the only production runtime: OpenAI Agents SDK."""

    from .agents.runtime import AgentsSdkHarnessRuntime

    return AgentsSdkHarnessRuntime(
        provider,
        max_completion_tokens=max_completion_tokens,
    )
