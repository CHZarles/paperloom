from __future__ import annotations

import json

from harness_py.transport.redis_worker import (
    COMMIT_TERMINAL_SCRIPT,
    PUBLISH_EVENT_SCRIPT,
    RECOVER_JOB_SCRIPT,
    RELEASE_LEASE_SCRIPT,
    RENEW_LEASE_SCRIPT,
    START_JOB_SCRIPT,
    RedisResearchWorker,
    RedisWorkerConfig,
)
from harness_py.utils.errors import HarnessCancelled


JOBS = "paperloom:research:harness:jobs"
GROUP = "paperloom-research-harness"


class FakeRedis:
    def __init__(
        self,
        job_fields: dict[str, str],
        *,
        status: str = "QUEUED",
        pending: list[tuple[str, dict[str, str]]] | None = None,
        values: dict[str, str] | None = None,
    ) -> None:
        self.job_fields = job_fields
        self.read = False
        self.pending = list(pending or [])
        self.events: list[tuple[str, dict[str, str]]] = []
        self.values = dict(values or {})
        self.acks: list[tuple[str, str, str]] = []
        self.stream_deletes: list[tuple[str, str]] = []
        self.claims: list[tuple[str, str]] = []
        generation_id = job_fields.get("generation_id")
        if generation_id and f"paperloom:research:harness:status:{generation_id}" not in self.values:
            self.values[f"paperloom:research:harness:status:{generation_id}"] = json.dumps({"status": status})

    def eval(self, script, numkeys, *values):
        keys = list(values[:numkeys])
        args = list(values[numkeys:])
        if script == START_JOB_SCRIPT:
            return self._start(keys, args)
        if script == PUBLISH_EVENT_SCRIPT:
            if self.values.get(keys[0]) != args[0]:
                return 0
            self._event(keys[1], args[2:8])
            return 1
        if script == COMMIT_TERMINAL_SCRIPT:
            if self.values.get(keys[0]) != args[0]:
                return 0
            for event in json.loads(args[1]):
                self.events.append((keys[1], {key: str(value) for key, value in event.items()}))
            self.values[keys[2]] = args[4]
            self.xack(keys[3], args[5], args[6])
            self.xdel(keys[3], args[6])
            self.values.pop(keys[0], None)
            return 1
        if script == RECOVER_JOB_SCRIPT:
            return self._recover(keys, args)
        if script == RENEW_LEASE_SCRIPT:
            return 1 if self.values.get(keys[0]) == args[0] else 0
        if script == RELEASE_LEASE_SCRIPT:
            if self.values.get(keys[0]) != args[0]:
                return 0
            self.values.pop(keys[0], None)
            return 1
        raise AssertionError("unexpected Redis script")

    def _start(self, keys, args):
        status = json.loads(self.values.get(keys[1], "{}"))
        if status.get("status") == "QUEUED" and keys[2] not in self.values:
            self.values[keys[2]] = args[2]
            self.values[keys[1]] = args[4]
            self._event(keys[3], ["research-harness-event/v1", args[0], "1", args[7], "job_started", args[8]])
            return "STARTED"
        if status.get("status") in {"SUCCEEDED", "FAILED", "CANCELLED", "STALE_FAILED"}:
            self.xack(keys[0], args[1], args[9])
            self.xdel(keys[0], args[9])
            return "CLEANED"
        return "INVALID"

    def _recover(self, keys, args):
        if keys[2] in self.values:
            return ["LIVE"]
        message_id = args[4]
        message = next((entry for entry in self.pending if entry[0] == message_id), None)
        if message is None:
            return ["RACE_LOST"]
        self.claims.append((message_id, args[2]))
        status = json.loads(self.values.get(keys[1], "{}"))
        if status.get("status") == "QUEUED":
            self.values[keys[2]] = args[5]
            self.values[keys[1]] = args[7]
            self._event(keys[3], ["research-harness-event/v1", args[0], "1", args[10], "job_started", args[11]])
            return ["EXECUTE", "1", [message_id, message[1]]]
        if status.get("status") in {"SUCCEEDED", "FAILED", "CANCELLED", "STALE_FAILED"}:
            self.xack(keys[0], args[1], message_id)
            self.xdel(keys[0], message_id)
            return ["CLEANED"]
        self._event(keys[3], ["research-harness-event/v1", args[0], "1", args[10], "job_failed", args[12]])
        self._event(keys[3], ["research-harness-event/v1", args[0], "2", args[10], "error", args[13]])
        self.values[keys[1]] = args[14]
        self.xack(keys[0], args[1], message_id)
        self.xdel(keys[0], message_id)
        return ["FAILED_CLOSED"]

    def _event(self, key, values):
        fields = dict(zip(
            ["schema_version", "generation_id", "sequence", "created_at_ms", "type", "payload_json"],
            map(str, values),
        ))
        self.events.append((key, fields))

    def xpending_range(self, name, groupname, min, max, count, consumername=None, idle=None):
        return [{"message_id": message_id} for message_id, _fields in self.pending[:count]]

    def xrange(self, name, min, max, count=None):
        return [(message_id, fields) for message_id, fields in self.pending if message_id == min]

    def xreadgroup(self, groupname, consumername, streams, count, block):
        if self.read:
            return []
        self.read = True
        return [(next(iter(streams)), [("1780000000000-0", self.job_fields)])]

    def exists(self, key):
        return key in self.values

    def xack(self, key, group, message_id):
        self.acks.append((key, group, message_id))
        return 1

    def xdel(self, key, message_id):
        self.stream_deletes.append((key, message_id))
        self.pending = [entry for entry in self.pending if entry[0] != message_id]
        return 1

    def pipeline(self, transaction=True):
        return self

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return False

    def execute(self):
        return []


class FakeService:
    def __init__(self) -> None:
        self.calls = 0

    def run_job(self, payload, progress_listener, should_cancel):
        self.calls += 1
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


def config(worker_id="worker-1"):
    return RedisWorkerConfig(redis_url="redis://unused", worker_id=worker_id, block_ms=1)


def job(generation_id="generation-1"):
    payload = {"request_id": generation_id, "scope": {"paper_ids": ["paper-1"]}}
    return {
        "generation_id": generation_id,
        "attempt": "1",
        "payload_json": json.dumps(payload),
    }


def test_worker_commits_one_owned_terminal_result():
    client = FakeRedis(job())
    service = FakeService()
    worker = RedisResearchWorker(client, config(), service=service)

    assert worker.run_once() is True

    assert service.calls == 1
    assert [fields["type"] for _key, fields in client.events] == [
        "job_started", "calling_tool", "answer_completed", "job_completed", "result"
    ]
    assert client.acks == [(JOBS, GROUP, "1780000000000-0")]
    assert "paperloom:research:harness:lock:generation-1" not in client.values


def test_cancelled_run_commits_cancelled_terminal():
    client = FakeRedis(job())
    worker = RedisResearchWorker(client, config(), service=CancelledService())

    worker.run_once()

    assert client.events[-1][1]["type"] == "cancelled"
    assert json.loads(client.values["paperloom:research:harness:status:generation-1"])["status"] == "CANCELLED"


def test_live_stale_candidate_is_observed_without_claim():
    fields = job("generation-live")
    lock_key = "paperloom:research:harness:lock:generation-live"
    client = FakeRedis(
        {},
        pending=[("1-0", fields)],
        values={
            lock_key: "owner-A",
            "paperloom:research:harness:status:generation-live": json.dumps({"status": "RUNNING"}),
        },
    )
    worker = RedisResearchWorker(client, config("worker-B"), service=ExplodingService())

    assert worker.run_once() is True

    assert client.claims == []
    assert client.acks == []
    assert client.values[lock_key] == "owner-A"


def test_stale_queued_candidate_is_claimed_and_executed_once():
    fields = job("generation-queued")
    client = FakeRedis(
        {}, pending=[("2-0", fields)], values={
            "paperloom:research:harness:status:generation-queued": json.dumps({"status": "QUEUED"})
        }
    )
    service = FakeService()
    worker = RedisResearchWorker(client, config("worker-B"), service=service)

    worker.run_once()

    assert service.calls == 1
    assert client.claims == [("2-0", "worker-B")]
    assert client.acks == [(JOBS, GROUP, "2-0")]


def test_stale_running_candidate_fails_closed_without_rerun():
    fields = job("generation-stale")
    client = FakeRedis(
        {}, pending=[("3-0", fields)], values={
            "paperloom:research:harness:status:generation-stale": json.dumps({"status": "RUNNING"})
        }
    )
    worker = RedisResearchWorker(client, config("worker-B"), service=ExplodingService())

    worker.run_once()

    assert client.events[-1][1]["type"] == "error"
    assert json.loads(client.values["paperloom:research:harness:status:generation-stale"])["status"] == "STALE_FAILED"
    assert client.acks == [(JOBS, GROUP, "3-0")]


def test_wrong_owner_cannot_renew_release_publish_or_commit():
    generation_id = "generation-owned"
    lock_key = f"paperloom:research:harness:lock:{generation_id}"
    client = FakeRedis({}, values={lock_key: "owner-B"})

    assert client.eval(RENEW_LEASE_SCRIPT, 1, lock_key, "owner-A", "60000") == 0
    assert client.eval(RELEASE_LEASE_SCRIPT, 1, lock_key, "owner-A") == 0
    assert client.eval(
        PUBLISH_EVENT_SCRIPT, 2, lock_key, f"events:{generation_id}",
        "owner-A", "10", "v1", generation_id, "1", "1", "progress", "{}", "60"
    ) == 0
    assert client.eval(
        COMMIT_TERMINAL_SCRIPT, 4, lock_key, f"events:{generation_id}", f"status:{generation_id}", JOBS,
        "owner-A", "[]", "10", "60", "{}", GROUP, "4-0"
    ) == 0

    assert client.values[lock_key] == "owner-B"
    assert client.events == []
    assert client.acks == []


def test_config_rejects_unsafe_lease_timing():
    try:
        RedisWorkerConfig(
            redis_url="redis://unused",
            worker_id="worker",
            heartbeat_seconds=10,
            lease_ttl_seconds=20,
        )
    except ValueError as error:
        assert "three heartbeat" in str(error)
    else:
        raise AssertionError("unsafe lease timing accepted")
