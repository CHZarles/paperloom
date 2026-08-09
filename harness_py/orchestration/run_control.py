from __future__ import annotations

from dataclasses import asdict, dataclass
from time import monotonic
from typing import Callable

from ..utils.errors import HarnessCancelled, RunLimitExceeded


@dataclass(frozen=True)
class RunLimits:
    max_wall_clock_ms: int = 600000
    max_model_visible_tool_chars: int = 16000
    max_history_chars: int = 32000

    def __post_init__(self) -> None:
        if any(value <= 0 for value in asdict(self).values()):
            raise ValueError("RunLimits values must be positive")

    def to_wire(self) -> dict[str, int | str]:
        return {"schema_version": "paperloom-run-limits/v1", **asdict(self)}


class RunControl:
    def __init__(
        self,
        limits: RunLimits | None = None,
        *,
        should_cancel: Callable[[], bool] | None = None,
        clock: Callable[[], float] = monotonic,
    ) -> None:
        self.limits = limits or RunLimits()
        self._clock = clock
        self._should_cancel = should_cancel or (lambda: False)
        self.started_monotonic = clock()
        self.deadline_monotonic = self.started_monotonic + self.limits.max_wall_clock_ms / 1000
        self.model_calls_started = 0
        self.prompt_tokens = 0
        self.completion_tokens = 0
        self.total_tokens = 0
        self.terminal_reason: str | None = None
        self.last_completed_boundary: dict[str, str] | None = None

    def start_model_call(self) -> None:
        self.check_cancelled_or_expired()
        self.model_calls_started += 1

    def record_model_usage(self, prompt_tokens: int, completion_tokens: int, total_tokens: int) -> None:
        self.prompt_tokens += max(0, prompt_tokens)
        self.completion_tokens += max(0, completion_tokens)
        self.total_tokens += max(0, total_tokens)

    def before_tool_call(self) -> None:
        self.check_cancelled_or_expired()

    def after_boundary(self, kind: str, operation_id: str) -> None:
        self.last_completed_boundary = {"kind": kind, "operation_id": operation_id}
        self.check_cancelled_or_expired()

    def check_cancelled_or_expired(self) -> None:
        if self._should_cancel():
            raise HarnessCancelled("research job cancelled")
        if self._clock() >= self.deadline_monotonic:
            self._limit("RUN_DEADLINE_EXCEEDED")

    def remaining_seconds(self) -> float:
        self.check_cancelled_or_expired()
        return max(0.0, self.deadline_monotonic - self._clock())

    def elapsed_ms(self) -> int:
        return round(max(0.0, self._clock() - self.started_monotonic) * 1000)

    def to_dict(self) -> dict[str, object]:
        return {
            "limits": self.limits.to_wire(),
            "usage": {
                "model_calls": self.model_calls_started,
                "prompt_tokens": self.prompt_tokens,
                "completion_tokens": self.completion_tokens,
                "total_tokens": self.total_tokens,
                "elapsed_ms": self.elapsed_ms(),
            },
            "last_completed_boundary": self.last_completed_boundary,
        }

    def _limit(self, reason_code: str) -> None:
        self.terminal_reason = reason_code
        raise RunLimitExceeded(reason_code)


__all__ = ["RunControl", "RunLimitExceeded", "RunLimits"]
