from __future__ import annotations

import json
import os
import socket
import threading
import time
from dataclasses import dataclass
from typing import Any, Callable

from ..utils.models import JsonMap, child_map
from ..utils.errors import HarnessCancelled
from .service import ResearchHarnessService


DEFAULT_JOBS_KEY = "paperloom:research:harness:jobs"
DEFAULT_GROUP = "paperloom-research-harness"
DEFAULT_EVENTS_PREFIX = "paperloom:research:harness:events:"
DEFAULT_STATUS_PREFIX = "paperloom:research:harness:status:"
DEFAULT_CANCEL_PREFIX = "paperloom:research:harness:cancel:"
DEFAULT_LOCK_PREFIX = "paperloom:research:harness:lock:"


@dataclass(frozen=True)
class RedisWorkerConfig:
    redis_url: str
    worker_id: str
    group: str = DEFAULT_GROUP
    jobs_key: str = DEFAULT_JOBS_KEY
    events_prefix: str = DEFAULT_EVENTS_PREFIX
    status_prefix: str = DEFAULT_STATUS_PREFIX
    cancel_prefix: str = DEFAULT_CANCEL_PREFIX
    lock_prefix: str = DEFAULT_LOCK_PREFIX
    block_ms: int = 5000
    job_timeout_seconds: int = 900
    event_ttl_seconds: int = 1800
    event_trim_maxlen: int = 500
    heartbeat_seconds: int = 10
    stale_pending_seconds: int = 120
    max_concurrent_runs: int = 1


class RedisResearchEventSink:
    def __init__(self, client: Any, config: RedisWorkerConfig, generation_id: str) -> None:
        self.client = client
        self.config = config
        self.generation_id = generation_id
        self.sequence = 0

    def emit(self, event_type: str, payload: JsonMap | None = None) -> None:
        self.sequence += 1
        key = self.config.events_prefix + self.generation_id
        fields = {
            "schema_version": "research-harness-event/v1",
            "generation_id": self.generation_id,
            "sequence": str(self.sequence),
            "created_at_ms": str(_now_ms()),
            "type": event_type,
            "payload_json": json.dumps(payload or {}, ensure_ascii=False),
        }
        self.client.xadd(
            key,
            fields,
            maxlen=max(1, self.config.event_trim_maxlen),
            approximate=True,
        )
        self.client.expire(key, self.config.event_ttl_seconds)

    def progress(self, event: JsonMap) -> None:
        event_type = str(event.get("type") or "progress")
        payload = dict(event)
        payload.pop("type", None)
        self.emit(event_type, payload)


class RedisCancellationCheck:
    def __init__(self, client: Any, config: RedisWorkerConfig, generation_id: str) -> None:
        self.client = client
        self.config = config
        self.generation_id = generation_id

    def __call__(self) -> bool:
        return bool(self.client.exists(self.config.cancel_prefix + self.generation_id))


class RedisResearchWorker:
    def __init__(self, client: Any, config: RedisWorkerConfig, service: ResearchHarnessService | None = None) -> None:
        self.client = client
        self.config = config
        self.service = service or ResearchHarnessService()

    def run_forever(self) -> None:
        self._ensure_group()
        while True:
            self.run_once()

    def run_once(self) -> bool:
        if self._reclaim_stale_pending():
            return True
        messages = self.client.xreadgroup(
            groupname=self.config.group,
            consumername=self.config.worker_id,
            streams={self.config.jobs_key: ">"},
            count=1,
            block=max(100, self.config.block_ms),
        )
        if not messages:
            return False
        for _stream_name, stream_messages in messages:
            for message_id, fields in stream_messages:
                self._handle_message(str(message_id), _string_map(fields))
                return True
        return False

    def _handle_message(self, message_id: str, fields: dict[str, str]) -> None:
        generation_id = str(fields.get("generation_id") or "").strip()
        if not generation_id:
            self.client.xack(self.config.jobs_key, self.config.group, message_id)
            return

        sink = RedisResearchEventSink(self.client, self.config, generation_id)
        started_at_ms = _now_ms()
        status = self._read_status(generation_id)
        if _status_is_terminal(status):
            self.client.xack(self.config.jobs_key, self.config.group, message_id)
            return
        if status.get("status") == "RUNNING":
            sink.emit("job_failed", _technical_terminal_payload("StalePendingJob", started_at_ms))
            self._terminal(sink, generation_id, "STALE_FAILED", "error", {
                "error_type": "StalePendingJob",
                "message": "research worker disappeared after starting this job",
            })
            self.client.xack(self.config.jobs_key, self.config.group, message_id)
            return

        cancel_check = RedisCancellationCheck(self.client, self.config, generation_id)
        lock_key = self.config.lock_prefix + generation_id
        lock_value = json.dumps({
            "worker_id": self.config.worker_id,
            "attempt": int(fields.get("attempt") or 1),
            "acquired_at_ms": _now_ms(),
        })
        if not self.client.set(lock_key, lock_value, nx=True, ex=self.config.job_timeout_seconds):
            return

        stop_heartbeat = threading.Event()
        heartbeat = _start_lock_heartbeat(
            self.client,
            lock_key,
            self.config.job_timeout_seconds,
            self.config.heartbeat_seconds,
            stop_heartbeat,
        )
        try:
            payload = json.loads(fields.get("payload_json") or "{}")
            if not isinstance(payload, dict):
                raise ValueError("payload_json must be a JSON object")
            if cancel_check():
                terminal = _cancelled_terminal_payload(started_at_ms)
                sink.emit("job_cancelled", terminal)
                self._terminal(sink, generation_id, "CANCELLED", "cancelled", terminal)
                self.client.xack(self.config.jobs_key, self.config.group, message_id)
                return

            self._write_status(generation_id, "RUNNING", worker_id=self.config.worker_id, message_id=message_id)
            sink.emit("job_started", {"worker_id": self.config.worker_id, "attempt": int(fields.get("attempt") or 1)})
            retry = child_map(payload.get("retry"))
            if retry:
                sink.emit("retry_started", {
                    "kind": retry.get("kind"),
                    "retry_of_generation_id": retry.get("retry_of_generation_id"),
                    "retry_of_conversation_record_id": retry.get("retry_of_conversation_record_id"),
                    "answer_slot_id": retry.get("answer_slot_id"),
                    "target_revision": retry.get("target_revision"),
                })
                sink.emit("retry_context_loaded", {
                    "previous_cited_evidence_count": len(retry.get("previous_cited_evidence_ids") or []),
                    "previous_answer_chars": len(str(retry.get("previous_answer_markdown") or "")),
                })

            response = self.service.run_job(payload, sink.progress, cancel_check)
            terminal = _terminal_payload(response)
            status = str(response.get("status") or "")
            if status == "LIMITED":
                sink.emit("run_limited", terminal)
                sink.emit("job_completed", terminal)
            elif status == "CANCELLED":
                sink.emit("job_cancelled", terminal)
            elif status == "FAILED_TECHNICAL":
                sink.emit("job_failed", terminal)
            else:
                sink.emit("answer_completed")
                sink.emit("job_completed", terminal)
            self._terminal(sink, generation_id, "SUCCEEDED" if status not in {"CANCELLED", "FAILED_TECHNICAL"} else status,
                           "result", response)
            self.client.xack(self.config.jobs_key, self.config.group, message_id)
        except HarnessCancelled as error:
            terminal = _cancelled_terminal_payload(started_at_ms)
            sink.emit("job_cancelled", terminal)
            self._terminal(sink, generation_id, "CANCELLED", "cancelled", terminal)
            self.client.xack(self.config.jobs_key, self.config.group, message_id)
        except Exception as error:
            terminal = _technical_terminal_payload(type(error).__name__, started_at_ms)
            sink.emit("job_failed", terminal)
            self._terminal(sink, generation_id, "FAILED", "error", {
                "error_type": type(error).__name__,
                "message": str(error),
            })
            self.client.xack(self.config.jobs_key, self.config.group, message_id)
        finally:
            stop_heartbeat.set()
            heartbeat.join(timeout=1)
            self.client.delete(lock_key)

    def _terminal(self, sink: RedisResearchEventSink, generation_id: str, status: str, event_type: str, payload: JsonMap) -> None:
        sink.emit(event_type, payload)
        self._write_status(generation_id, status, terminal=True, error_type=payload.get("error_type"), message=payload.get("message"))

    def _reclaim_stale_pending(self) -> bool:
        if not hasattr(self.client, "xautoclaim"):
            return False
        result = self.client.xautoclaim(
            name=self.config.jobs_key,
            groupname=self.config.group,
            consumername=self.config.worker_id,
            min_idle_time=max(1, self.config.stale_pending_seconds) * 1000,
            start_id="0-0",
            count=1,
        )
        messages = _xautoclaim_messages(result)
        if not messages:
            return False
        message_id, fields = messages[0]
        self._handle_message(str(message_id), _string_map(fields))
        return True

    def _read_status(self, generation_id: str) -> dict[str, Any]:
        if not hasattr(self.client, "get"):
            return {}
        raw = self.client.get(self.config.status_prefix + generation_id)
        if not raw:
            return {}
        try:
            parsed = json.loads(str(raw))
        except (TypeError, ValueError):
            return {}
        return parsed if isinstance(parsed, dict) else {}

    def _write_status(
        self,
        generation_id: str,
        status: str,
        *,
        worker_id: str | None = None,
        message_id: str | None = None,
        terminal: bool = False,
        error_type: object = None,
        message: object = None,
    ) -> None:
        now = _now_ms()
        value = {
            "schema_version": "research-harness-status/v1",
            "generation_id": generation_id,
            "status": status,
            "worker_id": worker_id or self.config.worker_id,
            "job_stream_id": message_id or "",
            "attempt": 1,
            "created_at_ms": now,
            "started_at_ms": now if status == "RUNNING" else "",
            "updated_at_ms": now,
            "terminal_at_ms": now if terminal else "",
            "error_type": "" if error_type is None else str(error_type),
            "message": "" if message is None else str(message),
        }
        key = self.config.status_prefix + generation_id
        self.client.set(key, json.dumps(value, ensure_ascii=False), ex=self.config.event_ttl_seconds)

    def _ensure_group(self) -> None:
        try:
            self.client.xgroup_create(
                name=self.config.jobs_key,
                groupname=self.config.group,
                id="0",
                mkstream=True,
            )
        except Exception as error:
            if "BUSYGROUP" not in str(error):
                raise


def run_worker(config: RedisWorkerConfig) -> None:
    redis_module = _redis_module()
    client = redis_module.Redis.from_url(config.redis_url, decode_responses=True)
    worker = RedisResearchWorker(client, config)
    print(json.dumps({
        "status": "ready",
        "transport": "redis-streams",
        "worker_id": config.worker_id,
        "group": config.group,
        "jobs_key": config.jobs_key,
        "max_concurrent_runs": config.max_concurrent_runs,
    }, indent=2, sort_keys=True))
    worker.run_forever()


def worker_id(default: str = "") -> str:
    if default:
        return default
    return f"harness-{socket.gethostname()}-{os.getpid()}"


def _redis_module() -> Any:
    try:
        import redis
    except ImportError as error:
        raise RuntimeError("redis-py is required for `harness_py worker`; install harness_py/requirements.lock") from error
    return redis


def _start_lock_heartbeat(
    client: Any,
    lock_key: str,
    timeout_seconds: int,
    heartbeat_seconds: int,
    stop: threading.Event,
) -> threading.Thread:
    def run() -> None:
        while not stop.wait(max(1, heartbeat_seconds)):
            client.expire(lock_key, timeout_seconds)

    thread = threading.Thread(target=run, name="research-harness-lock-heartbeat", daemon=True)
    thread.start()
    return thread


def _string_map(fields: Any) -> dict[str, str]:
    return {str(key): str(value) for key, value in dict(fields).items()}


def _xautoclaim_messages(result: Any) -> list[tuple[Any, Any]]:
    if not isinstance(result, (list, tuple)) or len(result) < 2:
        return []
    messages = result[1]
    if not isinstance(messages, list):
        return []
    return [(message_id, fields) for message_id, fields in messages]


def _status_is_terminal(status: dict[str, Any]) -> bool:
    return str(status.get("status") or "") in {"SUCCEEDED", "FAILED", "CANCELLED", "STALE_FAILED"}


def _terminal_payload(response: JsonMap) -> JsonMap:
    control = child_map(response.get("control"))
    usage = child_map(control.get("usage"))
    return {
        "status": response.get("status"),
        "reasonCode": control.get("reason_code"),
        "usage": usage,
        "elapsedMs": usage.get("elapsed_ms"),
    }


def _cancelled_terminal_payload(started_at_ms: int) -> JsonMap:
    elapsed_ms = max(0, _now_ms() - started_at_ms)
    return {
        "status": "CANCELLED",
        "reasonCode": "RUN_CANCELLED",
        "usage": {
            "model_calls": 0,
            "prompt_tokens": 0,
            "completion_tokens": 0,
            "total_tokens": 0,
            "elapsed_ms": elapsed_ms,
        },
        "elapsedMs": elapsed_ms,
    }


def _technical_terminal_payload(error_type: str, started_at_ms: int) -> JsonMap:
    elapsed_ms = max(0, _now_ms() - started_at_ms)
    return {
        "status": "FAILED_TECHNICAL",
        "reasonCode": "INTERNAL_UNEXPECTED",
        "usage": {
            "model_calls": 0,
            "prompt_tokens": 0,
            "completion_tokens": 0,
            "total_tokens": 0,
            "elapsed_ms": elapsed_ms,
        },
        "elapsedMs": elapsed_ms,
        "errorType": error_type,
        "message": "The research service stopped unexpectedly.",
    }


def _now_ms() -> int:
    return int(time.time() * 1000)
