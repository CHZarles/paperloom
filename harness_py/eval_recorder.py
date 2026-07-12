from __future__ import annotations

import json
import os
import threading
from datetime import UTC, datetime
from pathlib import Path
from time import monotonic_ns
from typing import Any


EVENT_SCHEMA = "harness-eval-event/v1"
RESULT_SCHEMA = "harness-eval-result/v1"


class EvalRecorder:
    """Small append-only per-run recorder for offline evaluation data."""

    def __init__(self, root: str | Path, run_id: str):
        self.run_id = run_id
        self.run_dir = Path(root) / run_id
        self.run_dir.mkdir(parents=True, exist_ok=False, mode=0o700)
        os.chmod(self.run_dir, 0o700)
        self.events_path = self.run_dir / "events.jsonl"
        self.result_path = self.run_dir / "result.json"
        self._handle = self.events_path.open("x", encoding="utf-8")
        os.chmod(self.events_path, 0o600)
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
                "recorded_at": _now(),
                "monotonic_ns": monotonic_ns(),
                "operation_id": operation_id,
                "attempt": attempt,
                "payload": payload,
            }
            try:
                self._handle.write(json.dumps(event, ensure_ascii=False, separators=(",", ":")) + "\n")
                self._handle.flush()
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
                "completed_at": _now(),
                "event_count": self._sequence,
                "duplicate_event_count": self._duplicates,
                "capture_ok": self._ok,
                "capture_error": self._error or None,
                "result": result,
            }
            temp = self.run_dir / ".result.json.tmp"
            try:
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


def _now() -> str:
    return datetime.now(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")
