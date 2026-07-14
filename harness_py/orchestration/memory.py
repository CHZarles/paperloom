"""Harness 记忆与 OpenAI Agents SDK Session 的适配层。

``ResearchMemory`` 是产品已经接受的研究记忆；``RequestBackedSession`` 则只是 SDK 为当前
Run 读取和追加消息的接口。两者名字相近，但生命周期完全不同。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from ..core.models import JsonMap


@dataclass(frozen=True)
class ResearchMemory:
    """从 ConversationState 投影出的、允许带进本轮的研究记忆。"""

    selected_paper_ids: list[str] = field(default_factory=list)
    selected_evidence_ids: list[str] = field(default_factory=list)
    evidence_items_by_id: dict[str, JsonMap] = field(default_factory=dict)


class RequestBackedSession:
    """只服务一次 SDK Run、由请求文本历史初始化的 Session。

    SDK 只要求 Session 提供增删查消息的方法，并不要求一定使用 SQLite 或远程数据库。本项目
    的长期状态由 Java/ConversationState 管理，所以这里使用最小内存实现，避免 SDK 再维护一
    份长期历史。
    """

    session_settings = None

    def __init__(self, session_id: str, history: list[JsonMap]):
        self.session_id = session_id
        # 只回放用户和助手文本。上一轮工具轨迹、临时错误和授权过程不应污染当前提示词。
        self._items: list[Any] = [
            {"role": str(item["role"]), "content": str(item["content"])}
            for item in history
            if item.get("role") in {"user", "assistant"}
            and str(item.get("content") or "").strip()
        ]

    async def get_items(self, limit: int | None = None) -> list[Any]:
        """供 Runner 在模型调用前读取已有历史。"""

        items = self._items if limit is None else self._items[-max(0, limit):]
        return list(items)

    async def add_items(self, items: list[Any]) -> None:
        """供 Runner 把本次运行产生的新消息追加到当前内存 Session。"""

        self._items.extend(items)

    async def pop_item(self) -> Any | None:
        """删除并返回最后一项；SDK 在回滚最近输入时可能使用。"""

        return self._items.pop() if self._items else None

    async def clear_session(self) -> None:
        """清空当前 Run 的 Session，不会直接修改 ConversationState。"""

        self._items.clear()


def request_session_input(history: list[Any], new_input: list[Any]) -> list[Any]:
    """规定 Runner 最终发送给模型的输入顺序：旧历史在前，本轮输入在后。"""

    return [*history, *new_input]
