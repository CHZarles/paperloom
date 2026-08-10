from __future__ import annotations

import json
import logging
import os
import shutil
import threading
from dataclasses import dataclass
from pathlib import Path
from time import time
from time import monotonic_ns
from typing import Any

from ..utils.models import utc_now_iso


EVENT_SCHEMA = "harness-eval-event/v1"
RESULT_SCHEMA = "harness-eval-result/v1"


@dataclass(frozen=True)
class TraceRetention:
    max_age_seconds: int
    max_bytes: int
    incomplete_grace_seconds: int = 86_400

    def __post_init__(self) -> None:
        if self.max_age_seconds <= 0 or self.max_bytes <= 0 or self.incomplete_grace_seconds <= 0:
            raise ValueError("trace retention values must be positive")


class EvalRecorder:
    """Small append-only per-run recorder for offline evaluation data."""

    def __init__(self, root: str | Path, run_id: str):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        os.chmod(self.root, 0o700)
        self.run_id = run_id
        self.run_dir = self.root / run_id
        self.run_dir.mkdir(parents=True, exist_ok=False, mode=0o700)
        os.chmod(self.run_dir, 0o700)
        self.events_path = self.run_dir / "events.jsonl"
        self.result_path = self.run_dir / "result.json"
        self._handle = self.events_path.open("x", encoding="utf-8")
        os.chmod(self.events_path, 0o600)
        # 锁同时保护去重集合和序号，确保并发工具事件不重不漏。
        self._lock = threading.Lock()
        self._seen: set[str] = set()
        self._sequence = 0
        self._duplicates = 0
        self._ok = True
        self._error = ""

    @property
    def ok(self) -> bool:
        return self._ok

    def append(
        self,
        *,
        kind: str,
        operation_id: str,
        payload: dict[str, Any],
        attempt: int = 1,
        event_id: str | None = None,
    ) -> bool:
        if not self._ok:
            return False
        resolved_id = event_id or f"{self.run_id}:{operation_id}:{kind}:{attempt}"
        with self._lock:
            if resolved_id in self._seen:
                self._duplicates += 1
                return False
            sequence = self._sequence + 1
            event = {
                "schema_version": EVENT_SCHEMA,
                "event_id": resolved_id,
                "run_id": self.run_id,
                "sequence": sequence,
                "kind": kind,
                "recorded_at": utc_now_iso("milliseconds"),
                "monotonic_ns": monotonic_ns(),
                "operation_id": operation_id,
                "attempt": attempt,
                "payload": payload,
            }
            try:
                self._handle.write(json.dumps(event, ensure_ascii=False, separators=(",", ":")) + "\n")
                self._handle.flush()
                # 逐条刷盘比批量缓存慢，但这里优先保证进程异常时的数据完整性。
                os.fsync(self._handle.fileno())
            except Exception as error:
                self._fail(error)
                return False
            self._sequence = sequence
            self._seen.add(resolved_id)
            return True

    def finish(self, result: dict[str, Any]) -> bool:
        with self._lock:
            payload = {
                "schema_version": RESULT_SCHEMA,
                "run_id": self.run_id,
                "completed_at": utc_now_iso("milliseconds"),
                "event_count": self._sequence,
                "duplicate_event_count": self._duplicates,
                "capture_ok": self._ok,
                "capture_error": self._error or None,
                "result": result,
            }
            temp = self.run_dir / ".result.json.tmp"
            try:
                # 先完整写临时文件，再原子替换，读者不会看到半份结果。
                with temp.open("x", encoding="utf-8") as handle:
                    os.chmod(temp, 0o600)
                    json.dump(payload, handle, ensure_ascii=False, separators=(",", ":"))
                    handle.write("\n")
                    handle.flush()
                    os.fsync(handle.fileno())
                os.replace(temp, self.result_path)
            except Exception as error:
                self._fail(error)
                try:
                    temp.unlink(missing_ok=True)
                except OSError:
                    pass
            finally:
                try:
                    self._handle.close()
                except OSError:
                    pass
            return self._ok

    def _fail(self, error: Exception) -> None:
        self._ok = False
        if not self._error:
            self._error = f"{type(error).__name__}: {error}"
            logging.getLogger(__name__).error("eval capture failed for run_id=%s: %s", self.run_id, error)


def prune_trace_runs(
    root: str | Path,
    retention: TraceRetention,
    *,
    now: float | None = None,
) -> dict[str, int]:
    """Delete expired traces, then oldest completed runs until the directory is below its byte cap."""

    root_path = Path(root)
    if not root_path.exists():
        return {"deleted_runs": 0, "deleted_bytes": 0, "remaining_bytes": 0}

    current_time = time() if now is None else now
    runs: list[dict[str, Any]] = []
    for run_dir in root_path.iterdir():
        if not run_dir.is_dir():
            continue
        size, modified_at = _run_dir_stats(run_dir)
        runs.append({
            "path": run_dir,
            "size": size,
            "modified_at": modified_at,
            "completed": (run_dir / "result.json").is_file(),
            "deleted": False,
        })

    total_bytes = sum(int(run["size"]) for run in runs)
    deleted_runs = 0
    deleted_bytes = 0

    def remove(run: dict[str, Any]) -> None:
        nonlocal total_bytes, deleted_runs, deleted_bytes
        try:
            shutil.rmtree(run["path"])
        except OSError as error:
            logging.getLogger(__name__).warning("agent trace cleanup failed for %s: %s", run["path"], error)
            return
        run["deleted"] = True
        size = int(run["size"])
        total_bytes -= size
        deleted_runs += 1
        deleted_bytes += size

    for run in runs:
        age = max(0.0, current_time - float(run["modified_at"]))
        max_age = retention.max_age_seconds if run["completed"] else retention.incomplete_grace_seconds
        if age >= max_age:
            remove(run)

    completed = sorted(
        (run for run in runs if run["completed"] and not run["deleted"]),
        key=lambda run: float(run["modified_at"]),
    )
    for run in completed:
        if total_bytes <= retention.max_bytes:
            break
        remove(run)

    if total_bytes > retention.max_bytes:
        logging.getLogger(__name__).warning(
            "agent trace directory remains over limit because only active runs remain: bytes=%s limit=%s",
            total_bytes,
            retention.max_bytes,
        )
    return {
        "deleted_runs": deleted_runs,
        "deleted_bytes": deleted_bytes,
        "remaining_bytes": max(0, total_bytes),
    }


def _run_dir_stats(run_dir: Path) -> tuple[int, float]:
    size = 0
    modified_at = run_dir.stat().st_mtime
    for item in run_dir.iterdir():
        try:
            stat = item.stat(follow_symlinks=False)
        except OSError:
            continue
        modified_at = max(modified_at, stat.st_mtime)
        if item.is_file() and not item.is_symlink():
            size += stat.st_size
    return size, modified_at
