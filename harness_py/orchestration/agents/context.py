"""单次 Agent Run 的业务 Context。

OpenAI Agents SDK 的 Context 可以理解为“依赖注入容器 + 本轮工作区”。Runner 会把它原样
传给每个工具和 Hook，但不会自动持久化它，也不会把它直接展示给模型。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, datetime
from time import perf_counter

from ...core.errors import HarnessCancelled
from ...core.models import JsonMap
from ...corpus.tools import ReadingCorpusTools
from ..research_skills import ResearchSkillRegistry
from ..runtime import TurnExecutionInput


@dataclass
class ResearchRunContext:
    """保存一次研究回合中会变化的全部状态。

    这个对象只活一个 ``Runner.run``。跨回合信息必须进入 ``ConversationState``，否则服务
    重启或下一次请求时就会丢失。
    """

    # 产品层整理好的不可变输入：问题、历史、旧证据、取消函数等。
    turn: TurnExecutionInput
    # 以下对象和字段都属于本轮运行，由 __post_init__ 或默认工厂创建。
    corpus: ReadingCorpusTools = field(init=False)
    skills: ResearchSkillRegistry = field(default_factory=ResearchSkillRegistry)
    trace: list[JsonMap] = field(default_factory=list)
    skills_used: list[str] = field(default_factory=list)
    model_call_count: int = 0
    prompt_tokens: int = 0
    completion_tokens: int = 0
    total_tokens: int = 0
    model_latency_ms: int = 0
    started_at: str = field(default_factory=lambda: _now())
    started_monotonic: float = field(default_factory=perf_counter)
    current_model_call_id: str = ""
    current_model_started: float = 0.0
    tool_call_groups: dict[str, tuple[str, ...]] = field(default_factory=dict)
    tool_call_models: dict[str, str] = field(default_factory=dict)
    transport_attempts: dict[str, int] = field(default_factory=dict)
    final_draft: JsonMap | None = None

    def __post_init__(self) -> None:
        # 每轮创建独立的 Corpus 工具实例，因此授权集合不会泄漏到其他用户或其他请求。
        self.corpus = ReadingCorpusTools(self.turn.dataset, reader=self.turn.corpus_reader)
        # 上一轮已经引用过的论文和位置可直接用于追问，其余仍走工具授权链。
        self.corpus.authorized_paper_ids.update(
            paper_id
            for paper_id in self.turn.research_memory.selected_paper_ids
            if paper_id in self.turn.dataset.paper_records_by_id
        )
        self.corpus.disclosed_location_refs.update(
            str(item.get("location_ref") or item.get("location") or "")
            for item in self.turn.research_memory.evidence_items_by_id.values()
            if self.turn.corpus_reader is not None
            or str(item.get("location_ref") or item.get("location") or "")
            in self.corpus.documents_by_location
        )

    def check_cancelled(self) -> None:
        """在模型调用和工具调用边界响应上游取消。"""

        if self.turn.should_cancel and self.turn.should_cancel():
            raise HarnessCancelled("research job cancelled")

    def emit_progress(self, event: JsonMap) -> None:
        """把进度事件交给服务层；没有监听器时是空操作。"""

        if self.turn.progress_listener:
            self.turn.progress_listener(event)

    def begin_model_call(self) -> str:
        """开始一次模型请求，并分配本轮内稳定的 model_call_id。"""

        self.check_cancelled()
        self.model_call_count += 1
        self.current_model_call_id = f"model_{self.model_call_count}"
        self.current_model_started = perf_counter()
        self.emit_progress({"type": "model_call_started", "attempt": self.model_call_count})
        return self.current_model_call_id

    def complete_model_call(self, prompt_tokens: int, completion_tokens: int, total_tokens: int) -> None:
        """结束一次模型请求，把耗时和 Token 累加到整个研究回合。"""

        duration_ms = round((perf_counter() - self.current_model_started) * 1000)
        self.model_latency_ms += duration_ms
        self.prompt_tokens += prompt_tokens
        self.completion_tokens += completion_tokens
        self.total_tokens += total_tokens
        self.emit_progress({
            "type": "model_call_completed",
            "attempt": self.model_call_count,
            "durationMs": duration_ms,
            "usage": {
                "promptTokens": prompt_tokens,
                "completionTokens": completion_tokens,
                "totalTokens": total_tokens,
                "cumulativeTotalTokens": self.total_tokens,
            },
        })

    def register_tool_group(self, call_ids_and_names: list[tuple[str, str]]) -> None:
        """记录同一次模型响应产生的全部工具调用。

        最终提交工具必须是该组唯一成员；否则同组其他工具可能在答案确定后继续改变证据状态。
        """

        names = tuple(name for _, name in call_ids_and_names)
        for call_id, _ in call_ids_and_names:
            self.tool_call_groups[call_id] = names
            self.tool_call_models[call_id] = self.current_model_call_id

    def next_transport_attempt(self) -> int:
        """为当前模型调用的 HTTP 重试分配递增序号。"""

        attempt = self.transport_attempts.get(self.current_model_call_id, 0) + 1
        self.transport_attempts[self.current_model_call_id] = attempt
        return attempt

    def current_transport_attempt(self) -> int:
        """返回当前模型调用正在记录的 HTTP 尝试序号。"""

        return self.transport_attempts.get(self.current_model_call_id, 1)

    def state_snapshot(self) -> JsonMap:
        """返回工具调用前后可比较的授权状态快照。"""

        return {
            "authorized_paper_ids": sorted(self.corpus.authorized_paper_ids),
            "disclosed_location_refs": sorted(self.corpus.disclosed_location_refs),
            "evidence_ids": sorted(self.corpus.observations_by_evidence_id),
        }


def _now() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")
