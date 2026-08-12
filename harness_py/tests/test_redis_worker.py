from __future__ import annotations

import json

from harness_py.transport.redis_worker import RedisResearchWorker, RedisWorkerConfig
from harness_py.utils.errors import HarnessCancelled


class FakeRedis:
    def __init__(
        self,
        job_fields: dict[str, str],
        *,
        pending: list[tuple[str, dict[str, str]]] | None = None,
        values: dict[str, str] | None = None,
    ) -> None:
        self.job_fields = job_fields
        self.read = False
        self.pending = pending or []
        self.events: list[tuple[str, dict[str, str]]] = []
        self.values: dict[str, str] = dict(values or {})
        self.acks: list[tuple[str, str, str]] = []
        self.stream_deletes: list[tuple[str, str]] = []
        self.deleted: list[str] = []
        self.expired: list[tuple[str, int]] = []

    def xautoclaim(self, name, groupname, consumername, min_idle_time, start_id, count):
        if not self.pending:
            return ("0-0", [])
        message = self.pending.pop(0)
        return ("0-0", [message])

    def xreadgroup(self, groupname, consumername, streams, count, block):
        if self.read:
            return []
        self.read = True
        key = next(iter(streams))
        return [(key, [("1780000000000-0", self.job_fields)])]

    def set(self, key, value, nx=False, ex=None):
        if nx and key in self.values:
            return False
        self.values[key] = value
        return True

    def get(self, key):
        return self.values.get(key)

    def xadd(self, key, fields, maxlen=None, approximate=True):
        self.events.append((key, dict(fields)))
        return f"event-{len(self.events)}"

    def expire(self, key, seconds):
        self.expired.append((key, seconds))
        return True

    def exists(self, key):
        return key in self.values

    def xack(self, key, group, message_id):
        self.acks.append((key, group, message_id))
        return 1

    def xdel(self, key, message_id):
        self.stream_deletes.append((key, message_id))
        return 1

    def pipeline(self, transaction=True):
        return self

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return False

    def execute(self):
        return []

    def delete(self, key):
        self.deleted.append(key)
        return 1


class FakeService:
    def run_job(self, payload, progress_listener, should_cancel):
        progress_listener({"type": "calling_tool", "tool": "search_papers"})
        return {
            "request_id": payload["request_id"],
            "status": "COMPLETED",
            "answer": {"markdown": "ok"},
            "citations": [],
            "usage": {"total_tokens": 7},
        }


class CancelledService:
    def run_job(self, payload, progress_listener, should_cancel):
        raise HarnessCancelled("research job cancelled")


class ExplodingService:
    def run_job(self, payload, progress_listener, should_cancel):
        raise AssertionError("stale running jobs must not be re-executed")


def test_worker_consumes_job_and_writes_ordered_terminal_events():
    payload = {
        "request_id": "generation-1",
        "conversation_id": "conversation-1",
        "user_id": 7,
        "user_message": "question",
        "scope": {"paper_ids": ["paper-1"]},
        "retry": {
            "kind": "USER_UNSATISFIED",
            "retry_of_generation_id": "generation-0",
            "retry_of_conversation_record_id": 12,
            "answer_slot_id": 12,
            "target_revision": 2,
            "previous_cited_evidence_ids": ["ev_1"],
            "previous_answer_markdown": "old",
        },
    }
    client = FakeRedis({
        "generation_id": "generation-1",
        "attempt": "1",
        "payload_json": json.dumps(payload),
    })
    worker = RedisResearchWorker(
        client,
        RedisWorkerConfig(redis_url="redis://unused", worker_id="worker-1", block_ms=1),
        service=FakeService(),
    )

    assert worker.run_once() is True

    event_types = [fields["type"] for _key, fields in client.events]
    assert event_types == [
        "job_started",
        "retry_started",
        "retry_context_loaded",
        "calling_tool",
        "answer_completed",
        "job_completed",
        "result",
    ]
    assert [int(fields["sequence"]) for _key, fields in client.events] == [1, 2, 3, 4, 5, 6, 7]
    assert client.acks == [("paperloom:research:harness:jobs", "paperloom-research-harness", "1780000000000-0")]
    assert client.stream_deletes == [("paperloom:research:harness:jobs", "1780000000000-0")]
    assert "paperloom:research:harness:lock:generation-1" in client.deleted
    result_payload = json.loads(client.events[-1][1]["payload_json"])
    assert result_payload["usage"]["total_tokens"] == 7


def test_worker_writes_cancelled_terminal_event_for_runtime_cancel():
    payload = {
        "request_id": "generation-2",
        "conversation_id": "conversation-1",
        "user_id": 7,
        "user_message": "question",
        "scope": {"paper_ids": ["paper-1"]},
    }
    client = FakeRedis({
        "generation_id": "generation-2",
        "attempt": "1",
        "payload_json": json.dumps(payload),
    })
    worker = RedisResearchWorker(
        client,
        RedisWorkerConfig(redis_url="redis://unused", worker_id="worker-1", block_ms=1),
        service=CancelledService(),
    )

    assert worker.run_once() is True

    assert client.events[-1][1]["type"] == "cancelled"
    assert client.acks == [("paperloom:research:harness:jobs", "paperloom-research-harness", "1780000000000-0")]
    assert client.stream_deletes == [("paperloom:research:harness:jobs", "1780000000000-0")]


def test_worker_writes_cancelled_terminal_event_for_pre_start_cancel_key():
    payload = {
        "request_id": "generation-cancelled",
        "conversation_id": "conversation-1",
        "user_id": 7,
        "user_message": "question",
        "scope": {"paper_ids": ["paper-1"]},
    }
    client = FakeRedis(
        {
            "generation_id": "generation-cancelled",
            "attempt": "1",
            "payload_json": json.dumps(payload),
        },
        values={
            "paperloom:research:harness:cancel:generation-cancelled": "1",
        },
    )
    worker = RedisResearchWorker(
        client,
        RedisWorkerConfig(redis_url="redis://unused", worker_id="worker-1", block_ms=1),
        service=ExplodingService(),
    )

    assert worker.run_once() is True

    assert client.events[-1][1]["type"] == "cancelled"
    assert client.acks == [("paperloom:research:harness:jobs", "paperloom-research-harness", "1780000000000-0")]
    assert client.stream_deletes == [("paperloom:research:harness:jobs", "1780000000000-0")]


def test_worker_skips_job_when_generation_lock_is_held():
    payload = {
        "request_id": "generation-locked",
        "conversation_id": "conversation-1",
        "user_id": 7,
        "user_message": "question",
        "scope": {"paper_ids": ["paper-1"]},
    }
    client = FakeRedis(
        {
            "generation_id": "generation-locked",
            "attempt": "1",
            "payload_json": json.dumps(payload),
        },
        values={
            "paperloom:research:harness:lock:generation-locked": "held-by-other-worker",
        },
    )
    worker = RedisResearchWorker(
        client,
        RedisWorkerConfig(redis_url="redis://unused", worker_id="worker-1", block_ms=1),
        service=ExplodingService(),
    )

    assert worker.run_once() is True

    assert client.events == []
    assert client.acks == []
    assert "paperloom:research:harness:lock:generation-locked" not in client.deleted


def test_worker_reclaims_stale_pending_job_that_never_started():
    payload = {
        "request_id": "generation-reclaim",
        "conversation_id": "conversation-1",
        "user_id": 7,
        "user_message": "question",
        "scope": {"paper_ids": ["paper-1"]},
    }
    fields = {
        "generation_id": "generation-reclaim",
        "attempt": "1",
        "payload_json": json.dumps(payload),
    }
    client = FakeRedis(
        {},
        pending=[("1780000000001-0", fields)],
        values={
            "paperloom:research:harness:status:generation-reclaim": json.dumps({
                "status": "QUEUED",
                "generation_id": "generation-reclaim",
            })
        },
    )
    worker = RedisResearchWorker(
        client,
        RedisWorkerConfig(redis_url="redis://unused", worker_id="worker-2", block_ms=1, stale_pending_seconds=1),
        service=FakeService(),
    )

    assert worker.run_once() is True

    event_types = [fields["type"] for _key, fields in client.events]
    assert event_types[-1] == "result"
    assert client.acks == [("paperloom:research:harness:jobs", "paperloom-research-harness", "1780000000001-0")]
    assert client.stream_deletes == [("paperloom:research:harness:jobs", "1780000000001-0")]


def test_worker_marks_stale_running_pending_job_failed_without_rerun():
    payload = {
        "request_id": "generation-stale",
        "conversation_id": "conversation-1",
        "user_id": 7,
        "user_message": "question",
        "scope": {"paper_ids": ["paper-1"]},
    }
    fields = {
        "generation_id": "generation-stale",
        "attempt": "1",
        "payload_json": json.dumps(payload),
    }
    client = FakeRedis(
        {},
        pending=[("1780000000002-0", fields)],
        values={
            "paperloom:research:harness:status:generation-stale": json.dumps({
                "status": "RUNNING",
                "generation_id": "generation-stale",
            })
        },
    )
    worker = RedisResearchWorker(
        client,
        RedisWorkerConfig(redis_url="redis://unused", worker_id="worker-2", block_ms=1, stale_pending_seconds=1),
        service=ExplodingService(),
    )

    assert worker.run_once() is True

    assert client.events[-1][1]["type"] == "error"
    payload_json = json.loads(client.events[-1][1]["payload_json"])
    assert payload_json["error_type"] == "StalePendingJob"
    assert client.acks == [("paperloom:research:harness:jobs", "paperloom-research-harness", "1780000000002-0")]
    assert client.stream_deletes == [("paperloom:research:harness:jobs", "1780000000002-0")]


def test_worker_does_not_fail_reclaimed_job_while_original_worker_lock_is_alive():
    generation_id = "generation-still-running"
    fields = {
        "generation_id": generation_id,
        "attempt": "1",
        "payload_json": json.dumps({"request_id": generation_id}),
    }
    client = FakeRedis(
        {},
        pending=[("1780000000003-0", fields)],
        values={
            f"paperloom:research:harness:status:{generation_id}": json.dumps({"status": "RUNNING"}),
            f"paperloom:research:harness:lock:{generation_id}": "held-by-original-worker",
        },
    )
    worker = RedisResearchWorker(
        client,
        RedisWorkerConfig(redis_url="redis://unused", worker_id="worker-2", stale_pending_seconds=1),
        service=ExplodingService(),
    )

    assert worker.run_once() is True

    assert client.events == []
    assert client.acks == []
    assert client.stream_deletes == []
