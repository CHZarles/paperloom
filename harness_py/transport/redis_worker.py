from __future__ import annotations

import json
import os
import socket
import threading
import time
import uuid
from dataclasses import dataclass
from typing import Any

from ..utils.errors import HarnessCancelled
from ..utils.models import JsonMap, child_map
from .service import ResearchHarnessService


DEFAULT_JOBS_KEY = "paperloom:research:harness:jobs"
DEFAULT_GROUP = "paperloom-research-harness"
DEFAULT_EVENTS_PREFIX = "paperloom:research:harness:events:"
DEFAULT_STATUS_PREFIX = "paperloom:research:harness:status:"
DEFAULT_CANCEL_PREFIX = "paperloom:research:harness:cancel:"
DEFAULT_LOCK_PREFIX = "paperloom:research:harness:lock:"


START_JOB_SCRIPT = """
local raw = redis.call('GET', KEYS[2])
local ok, status = pcall(cjson.decode, raw or '')
if ok and status['status'] == 'QUEUED' and redis.call('EXISTS', KEYS[3]) == 0 then
  redis.call('SET', KEYS[3], ARGV[3], 'PX', ARGV[4])
  redis.call('SET', KEYS[2], ARGV[5], 'EX', ARGV[6])
  redis.call('XADD', KEYS[4], 'MAXLEN', '~', ARGV[7], '*',
    'schema_version', 'research-harness-event/v1',
    'generation_id', ARGV[1], 'sequence', '1', 'created_at_ms', ARGV[8],
    'type', 'job_started', 'payload_json', ARGV[9])
  redis.call('EXPIRE', KEYS[4], ARGV[6])
  return 'STARTED'
end
if ok and (status['status'] == 'SUCCEEDED' or status['status'] == 'FAILED' or
    status['status'] == 'CANCELLED' or status['status'] == 'STALE_FAILED') then
  redis.call('XACK', KEYS[1], ARGV[2], ARGV[10])
  redis.call('XDEL', KEYS[1], ARGV[10])
  return 'CLEANED'
end
if ok and status['status'] == 'RUNNING' and redis.call('EXISTS', KEYS[3]) == 1 then
  redis.call('XACK', KEYS[1], ARGV[2], ARGV[10])
  redis.call('XDEL', KEYS[1], ARGV[10])
  return 'LIVE_DUPLICATE_CLEANED'
end
return 'INVALID'
"""

RENEW_LEASE_SCRIPT = """
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('PEXPIRE', KEYS[1], ARGV[2])
end
return 0
"""

RELEASE_LEASE_SCRIPT = """
if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('DEL', KEYS[1])
end
return 0
"""

PUBLISH_EVENT_SCRIPT = """
if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
redis.call('XADD', KEYS[2], 'MAXLEN', '~', ARGV[2], '*',
  'schema_version', ARGV[3], 'generation_id', ARGV[4],
  'sequence', ARGV[5], 'created_at_ms', ARGV[6],
  'type', ARGV[7], 'payload_json', ARGV[8])
redis.call('EXPIRE', KEYS[2], ARGV[9])
return 1
"""

RECOVER_JOB_SCRIPT = """
if redis.call('EXISTS', KEYS[3]) == 1 then return {'LIVE'} end
local claimed = redis.call('XCLAIM', KEYS[1], ARGV[2], ARGV[3], ARGV[4], ARGV[5])
if #claimed == 0 then return {'RACE_LOST'} end
local fields = claimed[1][2]
local generation = nil
for i = 1, #fields, 2 do
  if fields[i] == 'generation_id' then generation = fields[i + 1] end
end
if generation ~= ARGV[1] then return {'MISMATCH'} end
local raw = redis.call('GET', KEYS[2])
local ok, status = pcall(cjson.decode, raw or '')
local sequence = 0
local latest = redis.call('XREVRANGE', KEYS[4], '+', '-', 'COUNT', 1)
if #latest > 0 then
  local latest_fields = latest[1][2]
  for i = 1, #latest_fields, 2 do
    if latest_fields[i] == 'sequence' then sequence = tonumber(latest_fields[i + 1]) or 0 end
  end
end
if ok and status['status'] == 'QUEUED' then
  redis.call('SET', KEYS[3], ARGV[6], 'PX', ARGV[7])
  redis.call('SET', KEYS[2], ARGV[8], 'EX', ARGV[9])
  redis.call('XADD', KEYS[4], 'MAXLEN', '~', ARGV[10], '*',
    'schema_version', 'research-harness-event/v1',
    'generation_id', ARGV[1], 'sequence', tostring(sequence + 1), 'created_at_ms', ARGV[11],
    'type', 'job_started', 'payload_json', ARGV[12])
  redis.call('EXPIRE', KEYS[4], ARGV[9])
  return {'EXECUTE', tostring(sequence + 1), claimed[1]}
end
if ok and (status['status'] == 'SUCCEEDED' or status['status'] == 'FAILED' or
    status['status'] == 'CANCELLED' or status['status'] == 'STALE_FAILED') then
  redis.call('XACK', KEYS[1], ARGV[2], ARGV[5])
  redis.call('XDEL', KEYS[1], ARGV[5])
  return {'CLEANED'}
end
redis.call('XADD', KEYS[4], 'MAXLEN', '~', ARGV[10], '*',
  'schema_version', 'research-harness-event/v1',
  'generation_id', ARGV[1], 'sequence', tostring(sequence + 1), 'created_at_ms', ARGV[11],
  'type', 'job_failed', 'payload_json', ARGV[13])
redis.call('XADD', KEYS[4], 'MAXLEN', '~', ARGV[10], '*',
  'schema_version', 'research-harness-event/v1',
  'generation_id', ARGV[1], 'sequence', tostring(sequence + 2), 'created_at_ms', ARGV[11],
  'type', 'error', 'payload_json', ARGV[14])
redis.call('EXPIRE', KEYS[4], ARGV[9])
redis.call('SET', KEYS[2], ARGV[15], 'EX', ARGV[9])
redis.call('XACK', KEYS[1], ARGV[2], ARGV[5])
redis.call('XDEL', KEYS[1], ARGV[5])
return {'FAILED_CLOSED'}
"""

COMMIT_TERMINAL_SCRIPT = """
if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
local events = cjson.decode(ARGV[2])
for _, event in ipairs(events) do
  redis.call('XADD', KEYS[2], 'MAXLEN', '~', ARGV[3], '*',
    'schema_version', event['schema_version'],
    'generation_id', event['generation_id'],
    'sequence', tostring(event['sequence']),
    'created_at_ms', tostring(event['created_at_ms']),
    'type', event['type'], 'payload_json', event['payload_json'])
end
redis.call('EXPIRE', KEYS[2], ARGV[4])
redis.call('SET', KEYS[3], ARGV[5], 'EX', ARGV[4])
redis.call('XACK', KEYS[4], ARGV[6], ARGV[7])
redis.call('XDEL', KEYS[4], ARGV[7])
redis.call('DEL', KEYS[1])
return 1
"""


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
    lease_ttl_seconds: int = 60
    stale_pending_seconds: int = 120
    max_concurrent_runs: int = 1

    def __post_init__(self) -> None:
        if not (0 < self.heartbeat_seconds < self.lease_ttl_seconds <= self.stale_pending_seconds < self.job_timeout_seconds):
            raise ValueError("heartbeat < lease_ttl <= stale_pending < job_timeout is required")
        if self.lease_ttl_seconds < 3 * self.heartbeat_seconds:
            raise ValueError("lease_ttl must be at least three heartbeat intervals")


class LeaseState:
    def __init__(self) -> None:
        self.lost = threading.Event()


class RedisResearchEventSink:
    def __init__(
        self,
        client: Any,
        config: RedisWorkerConfig,
        generation_id: str,
        owner_token: str,
        lease_state: LeaseState,
        *,
        initial_sequence: int = 1,
    ) -> None:
        self.client = client
        self.config = config
        self.generation_id = generation_id
        self.owner_token = owner_token
        self.lease_state = lease_state
        self.sequence = initial_sequence

    def event_fields(self, event_type: str, payload: JsonMap | None = None) -> dict[str, str]:
        self.sequence += 1
        return {
            "schema_version": "research-harness-event/v1",
            "generation_id": self.generation_id,
            "sequence": str(self.sequence),
            "created_at_ms": str(_now_ms()),
            "type": event_type,
            "payload_json": json.dumps(payload or {}, ensure_ascii=False),
        }

    def emit(self, event_type: str, payload: JsonMap | None = None) -> None:
        fields = self.event_fields(event_type, payload)
        try:
            published = self.client.eval(
                PUBLISH_EVENT_SCRIPT,
                2,
                self.config.lock_prefix + self.generation_id,
                self.config.events_prefix + self.generation_id,
                self.owner_token,
                str(max(1, self.config.event_trim_maxlen)),
                fields["schema_version"],
                fields["generation_id"],
                fields["sequence"],
                fields["created_at_ms"],
                fields["type"],
                fields["payload_json"],
                str(self.config.event_ttl_seconds),
            )
        except Exception:
            self.lease_state.lost.set()
            raise
        if int(published or 0) != 1:
            self.lease_state.lost.set()
            raise HarnessCancelled("research lease ownership lost")

    def progress(self, event: JsonMap) -> None:
        event_type = str(event.get("type") or "progress")
        payload = dict(event)
        payload.pop("type", None)
        self.emit(event_type, payload)


class RedisCancellationCheck:
    def __init__(
        self,
        client: Any,
        config: RedisWorkerConfig,
        generation_id: str,
        lease_state: LeaseState,
    ) -> None:
        self.client = client
        self.config = config
        self.generation_id = generation_id
        self.lease_state = lease_state

    def __call__(self) -> bool:
        return self.lease_state.lost.is_set() or bool(
            self.client.exists(self.config.cancel_prefix + self.generation_id)
        )


class RedisResearchWorker:
    def __init__(self, client: Any, config: RedisWorkerConfig, service: ResearchHarnessService | None = None) -> None:
        self.client = client
        self.config = config
        self.service = service or ResearchHarnessService()
        self._pending_cursor = "-"

    def run_forever(self) -> None:
        self._ensure_group()
        while True:
            self.run_once()

    def run_once(self) -> bool:
        if self._recover_stale_pending():
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
                self._start_fresh(str(message_id), _string_map(fields))
                return True
        return False

    def _start_fresh(self, message_id: str, fields: dict[str, str]) -> None:
        generation_id = str(fields.get("generation_id") or "").strip()
        if not generation_id:
            self._ack_job(message_id)
            return
        owner_token = str(uuid.uuid4())
        started_at_ms = _now_ms()
        result = self.client.eval(
            START_JOB_SCRIPT,
            4,
            self.config.jobs_key,
            self.config.status_prefix + generation_id,
            self.config.lock_prefix + generation_id,
            self.config.events_prefix + generation_id,
            generation_id,
            self.config.group,
            owner_token,
            str(self.config.lease_ttl_seconds * 1000),
            json.dumps(self._status(generation_id, "RUNNING", message_id=message_id, now=started_at_ms)),
            str(self.config.event_ttl_seconds),
            str(max(1, self.config.event_trim_maxlen)),
            str(started_at_ms),
            json.dumps({"worker_id": self.config.worker_id, "attempt": int(fields.get("attempt") or 1)}),
            message_id,
        )
        if _text(result) == "STARTED":
            self._execute(message_id, fields, generation_id, owner_token, started_at_ms)

    def _execute(
        self,
        message_id: str,
        fields: dict[str, str],
        generation_id: str,
        owner_token: str,
        started_at_ms: int,
        initial_sequence: int = 1,
    ) -> None:
        lease_state = LeaseState()
        sink = RedisResearchEventSink(
            self.client,
            self.config,
            generation_id,
            owner_token,
            lease_state,
            initial_sequence=initial_sequence,
        )
        cancel_check = RedisCancellationCheck(self.client, self.config, generation_id, lease_state)
        stop_heartbeat = threading.Event()
        heartbeat = _start_lock_heartbeat(
            self.client,
            self.config.lock_prefix + generation_id,
            owner_token,
            self.config.lease_ttl_seconds,
            self.config.heartbeat_seconds,
            stop_heartbeat,
            lease_state,
        )
        terminal_events: list[dict[str, str]] = []
        terminal_status = "FAILED"
        terminal_type = "error"
        terminal_payload: JsonMap = {"error_type": "HarnessError", "message": "The Python research harness failed"}
        try:
            payload = json.loads(fields.get("payload_json") or "{}")
            if not isinstance(payload, dict):
                raise ValueError("payload_json must be a JSON object")
            if cancel_check():
                raise HarnessCancelled("research job cancelled")

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
            response_status = str(response.get("status") or "")
            if response_status == "LIMITED":
                terminal_events.extend([sink.event_fields("run_limited", terminal), sink.event_fields("job_completed", terminal)])
            elif response_status == "CANCELLED":
                terminal_events.append(sink.event_fields("job_cancelled", terminal))
            elif response_status == "FAILED_TECHNICAL":
                terminal_events.append(sink.event_fields("job_failed", terminal))
            else:
                terminal_events.extend([sink.event_fields("answer_completed"), sink.event_fields("job_completed", terminal)])

            if response_status == "CANCELLED":
                terminal_status, terminal_type, terminal_payload = "CANCELLED", "cancelled", terminal
            elif response_status == "FAILED_TECHNICAL":
                terminal_status, terminal_type = "FAILED", "error"
                terminal_payload = {"error_type": "HarnessTechnicalFailure", "message": "The research service stopped unexpectedly."}
            else:
                terminal_status, terminal_type, terminal_payload = "SUCCEEDED", "result", response
        except HarnessCancelled:
            if lease_state.lost.is_set():
                return
            terminal = _cancelled_terminal_payload(started_at_ms)
            terminal_events.append(sink.event_fields("job_cancelled", terminal))
            terminal_status, terminal_type, terminal_payload = "CANCELLED", "cancelled", terminal
        except Exception as error:
            if lease_state.lost.is_set():
                return
            terminal = _technical_terminal_payload(type(error).__name__, started_at_ms)
            terminal_events.append(sink.event_fields("job_failed", terminal))
            terminal_status, terminal_type = "FAILED", "error"
            terminal_payload = {"error_type": type(error).__name__, "message": str(error)}
        finally:
            stop_heartbeat.set()
            heartbeat.join(timeout=1)

        terminal_events.append(sink.event_fields(terminal_type, terminal_payload))
        self._commit_terminal(
            message_id,
            generation_id,
            owner_token,
            terminal_status,
            terminal_type,
            terminal_payload,
            terminal_events,
        )

    def _commit_terminal(
        self,
        message_id: str,
        generation_id: str,
        owner_token: str,
        status: str,
        event_type: str,
        payload: JsonMap,
        events: list[dict[str, str]],
    ) -> bool:
        now = _now_ms()
        committed = self.client.eval(
            COMMIT_TERMINAL_SCRIPT,
            4,
            self.config.lock_prefix + generation_id,
            self.config.events_prefix + generation_id,
            self.config.status_prefix + generation_id,
            self.config.jobs_key,
            owner_token,
            json.dumps(events, ensure_ascii=False),
            str(max(1, self.config.event_trim_maxlen)),
            str(self.config.event_ttl_seconds),
            json.dumps(self._status(
                generation_id,
                status,
                message_id=message_id,
                terminal=True,
                error_type=payload.get("error_type") if event_type == "error" else None,
                message=payload.get("message") if event_type in {"error", "cancelled"} else None,
                now=now,
            ), ensure_ascii=False),
            self.config.group,
            message_id,
        )
        return int(committed or 0) == 1

    def _recover_stale_pending(self) -> bool:
        candidates = self.client.xpending_range(
            self.config.jobs_key,
            self.config.group,
            self._pending_cursor,
            "+",
            10,
            idle=max(1, self.config.stale_pending_seconds) * 1000,
        )
        if not candidates:
            self._pending_cursor = "-"
            return False
        for candidate in candidates:
            message_id = _text(candidate.get("message_id"))
            self._pending_cursor = f"({message_id}"
            entries = self.client.xrange(self.config.jobs_key, message_id, message_id, count=1)
            if not entries:
                continue
            fields = _string_map(entries[0][1])
            generation_id = str(fields.get("generation_id") or "").strip()
            if not generation_id:
                self._ack_job(message_id)
                return True
            owner_token = str(uuid.uuid4())
            now = _now_ms()
            technical = _technical_terminal_payload("StalePendingJob", now)
            error = {
                "error_type": "StalePendingJob",
                "message": "research worker disappeared after starting this job",
            }
            result = self.client.eval(
                RECOVER_JOB_SCRIPT,
                4,
                self.config.jobs_key,
                self.config.status_prefix + generation_id,
                self.config.lock_prefix + generation_id,
                self.config.events_prefix + generation_id,
                generation_id,
                self.config.group,
                self.config.worker_id,
                str(max(1, self.config.stale_pending_seconds) * 1000),
                message_id,
                owner_token,
                str(self.config.lease_ttl_seconds * 1000),
                json.dumps(self._status(generation_id, "RUNNING", message_id=message_id, now=now)),
                str(self.config.event_ttl_seconds),
                str(max(1, self.config.event_trim_maxlen)),
                str(now),
                json.dumps({"worker_id": self.config.worker_id, "attempt": int(fields.get("attempt") or 1), "recovered": True}),
                json.dumps(technical),
                json.dumps(error),
                json.dumps(self._status(
                    generation_id,
                    "STALE_FAILED",
                    terminal=True,
                    error_type="StalePendingJob",
                    message=error["message"],
                    now=now,
                )),
            )
            outcome = _text(result[0] if isinstance(result, (list, tuple)) and result else result)
            if outcome == "EXECUTE":
                self._execute(
                    message_id,
                    fields,
                    generation_id,
                    owner_token,
                    now,
                    initial_sequence=int(_text(result[1])),
                )
                return True
            if outcome in {"FAILED_CLOSED", "CLEANED", "MISMATCH"}:
                return True
        return True

    def _status(
        self,
        generation_id: str,
        status: str,
        *,
        message_id: str = "",
        terminal: bool = False,
        error_type: object = None,
        message: object = None,
        now: int | None = None,
    ) -> JsonMap:
        timestamp = _now_ms() if now is None else now
        return {
            "schema_version": "research-harness-status/v1",
            "generation_id": generation_id,
            "status": status,
            "worker_id": self.config.worker_id,
            "job_stream_id": message_id,
            "attempt": 1,
            "created_at_ms": timestamp,
            "started_at_ms": timestamp if status == "RUNNING" else "",
            "updated_at_ms": timestamp,
            "terminal_at_ms": timestamp if terminal else "",
            "error_type": "" if error_type is None else str(error_type),
            "message": "" if message is None else str(message),
        }

    def _ack_job(self, message_id: str) -> None:
        with self.client.pipeline(transaction=True) as pipeline:
            pipeline.xack(self.config.jobs_key, self.config.group, message_id)
            pipeline.xdel(self.config.jobs_key, message_id)
            pipeline.execute()

    def _ensure_group(self) -> None:
        try:
            self.client.xgroup_create(name=self.config.jobs_key, groupname=self.config.group, id="0", mkstream=True)
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
    return default or f"harness-{socket.gethostname()}-{os.getpid()}"


def _redis_module() -> Any:
    try:
        import redis
    except ImportError as error:
        raise RuntimeError("redis-py is required for `harness_py worker`; install harness_py/requirements.lock") from error
    return redis


def _start_lock_heartbeat(
    client: Any,
    lock_key: str,
    owner_token: str,
    lease_ttl_seconds: int,
    heartbeat_seconds: int,
    stop: threading.Event,
    lease_state: LeaseState,
) -> threading.Thread:
    def run() -> None:
        while not stop.wait(max(1, heartbeat_seconds)):
            try:
                renewed = client.eval(
                    RENEW_LEASE_SCRIPT,
                    1,
                    lock_key,
                    owner_token,
                    str(lease_ttl_seconds * 1000),
                )
            except Exception:
                lease_state.lost.set()
                return
            if int(renewed or 0) != 1:
                lease_state.lost.set()
                return

    thread = threading.Thread(target=run, name="research-harness-lock-heartbeat", daemon=True)
    thread.start()
    return thread


def _string_map(fields: Any) -> dict[str, str]:
    return {_text(key): _text(value) for key, value in dict(fields).items()}


def _text(value: Any) -> str:
    if isinstance(value, bytes):
        return value.decode("utf-8")
    return "" if value is None else str(value)


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
