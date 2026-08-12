from __future__ import annotations

import json
import os
import threading
import time
import unittest
import uuid

from harness_py.transport.redis_worker import (
    COMMIT_TERMINAL_SCRIPT,
    PUBLISH_EVENT_SCRIPT,
    RedisResearchWorker,
    RedisWorkerConfig,
)


@unittest.skipUnless(os.getenv("RESEARCH_HARNESS_REDIS_SMOKE_URL"), "real Redis smoke is opt-in")
class RedisWorkerRealTest(unittest.TestCase):
    def setUp(self) -> None:
        import redis

        self.client = redis.Redis.from_url(
            os.environ["RESEARCH_HARNESS_REDIS_SMOKE_URL"], decode_responses=True
        )
        self.prefix = f"paperloom:test:lease:{uuid.uuid4()}:"
        self.jobs = self.prefix + "jobs"
        self.group = self.prefix + "group"
        self.config = RedisWorkerConfig(
            redis_url=os.environ["RESEARCH_HARNESS_REDIS_SMOKE_URL"],
            worker_id="worker-B",
            group=self.group,
            jobs_key=self.jobs,
            events_prefix=self.prefix + "events:",
            status_prefix=self.prefix + "status:",
            cancel_prefix=self.prefix + "cancel:",
            lock_prefix=self.prefix + "lock:",
            heartbeat_seconds=1,
            lease_ttl_seconds=3,
            stale_pending_seconds=3,
            job_timeout_seconds=10,
            block_ms=100,
        )
        self.client.xgroup_create(self.jobs, self.group, id="0", mkstream=True)

    def tearDown(self) -> None:
        keys = self.client.keys(self.prefix + "*")
        if keys:
            self.client.delete(*keys)

    def test_live_candidate_is_not_claimed(self) -> None:
        generation = "generation-live"
        message_id = self.client.xadd(self.jobs, self._fields(generation))
        self.client.set(self.config.status_prefix + generation, json.dumps({"status": "RUNNING"}))
        self.client.set(self.config.lock_prefix + generation, "owner-A", px=10_000)
        self.client.xreadgroup(self.group, "worker-A", {self.jobs: ">"}, count=1)
        time.sleep(3.1)

        RedisResearchWorker(self.client, self.config, service=ForbiddenService()).run_once()

        pending = self.client.xpending_range(self.jobs, self.group, message_id, message_id, 1)
        self.assertEqual("worker-A", pending[0]["consumer"])
        self.assertEqual("owner-A", self.client.get(self.config.lock_prefix + generation))

    def test_fresh_job_commits_exactly_one_terminal_result(self) -> None:
        generation = "generation-fresh"
        message_id = self.client.xadd(self.jobs, self._fields(generation))
        self.client.set(self.config.status_prefix + generation, json.dumps({"status": "QUEUED"}))

        service = CountingService()
        RedisResearchWorker(self.client, self.config, service=service).run_once()

        events = self.client.xrange(self.config.events_prefix + generation)
        self.assertEqual(1, service.calls)
        self.assertEqual(1, sum(fields["type"] == "result" for _id, fields in events))
        self.assertEqual([], self.client.xpending_range(self.jobs, self.group, message_id, message_id, 1))
        self.assertEqual("SUCCEEDED", json.loads(self.client.get(self.config.status_prefix + generation))["status"])
        self.assertIsNone(self.client.get(self.config.lock_prefix + generation))

    def test_two_recoverers_only_apply_one_stale_failure(self) -> None:
        generation = "generation-stale"
        message_id = self.client.xadd(self.jobs, self._fields(generation))
        self.client.set(self.config.status_prefix + generation, json.dumps({"status": "RUNNING"}))
        self.client.xreadgroup(self.group, "worker-A", {self.jobs: ">"}, count=1)
        time.sleep(3.1)

        barrier = threading.Barrier(3)
        errors: list[Exception] = []

        def recover(worker_id: str) -> None:
            try:
                config = RedisWorkerConfig(**{**self.config.__dict__, "worker_id": worker_id})
                barrier.wait()
                RedisResearchWorker(self.client, config, service=ForbiddenService()).run_once()
            except Exception as error:
                errors.append(error)

        threads = [threading.Thread(target=recover, args=(worker_id,)) for worker_id in ("worker-B", "worker-C")]
        for thread in threads:
            thread.start()
        barrier.wait()
        for thread in threads:
            thread.join()

        self.assertEqual([], errors)
        events = self.client.xrange(self.config.events_prefix + generation)
        self.assertEqual(1, sum(fields["type"] == "error" for _id, fields in events))
        self.assertEqual([], self.client.xpending_range(self.jobs, self.group, message_id, message_id, 1))
        self.assertEqual("STALE_FAILED", json.loads(self.client.get(self.config.status_prefix + generation))["status"])

    def test_two_recoverers_only_execute_one_queued_job(self) -> None:
        generation = "generation-queued"
        message_id = self.client.xadd(self.jobs, self._fields(generation))
        self.client.set(self.config.status_prefix + generation, json.dumps({"status": "QUEUED"}))
        self.client.xreadgroup(self.group, "worker-A", {self.jobs: ">"}, count=1)
        time.sleep(3.1)

        service = CountingService()
        self._run_two_recoverers(service)

        self.assertEqual(1, service.calls)
        self.assertEqual([], self.client.xpending_range(self.jobs, self.group, message_id, message_id, 1))

    def test_old_owner_cannot_write_after_stale_failure(self) -> None:
        generation = "generation-old-owner"
        message_id = self.client.xadd(self.jobs, self._fields(generation))
        self.client.set(self.config.status_prefix + generation, json.dumps({"status": "RUNNING"}))
        self.client.xreadgroup(self.group, "worker-A", {self.jobs: ">"}, count=1)
        time.sleep(3.1)
        RedisResearchWorker(self.client, self.config, service=ForbiddenService()).run_once()

        lock_key = self.config.lock_prefix + generation
        event_key = self.config.events_prefix + generation
        self.assertEqual(0, self.client.eval(
            PUBLISH_EVENT_SCRIPT, 2, lock_key, event_key,
            "old-owner", "10", "v1", generation, "99", "1", "progress", "{}", "60"
        ))
        self.assertEqual(0, self.client.eval(
            COMMIT_TERMINAL_SCRIPT, 4, lock_key, event_key,
            self.config.status_prefix + generation, self.jobs,
            "old-owner", "[]", "10", "60", "{}", self.group, message_id
        ))
        events = self.client.xrange(event_key)
        self.assertEqual(1, sum(fields["type"] == "error" for _id, fields in events))

    def _run_two_recoverers(self, service) -> None:
        barrier = threading.Barrier(3)
        errors: list[Exception] = []

        def recover(worker_id: str) -> None:
            try:
                config = RedisWorkerConfig(**{**self.config.__dict__, "worker_id": worker_id})
                barrier.wait()
                RedisResearchWorker(self.client, config, service=service).run_once()
            except Exception as error:
                errors.append(error)

        threads = [threading.Thread(target=recover, args=(worker_id,)) for worker_id in ("worker-B", "worker-C")]
        for thread in threads:
            thread.start()
        barrier.wait()
        for thread in threads:
            thread.join()
        self.assertEqual([], errors)

    @staticmethod
    def _fields(generation: str) -> dict[str, str]:
        return {
            "generation_id": generation,
            "attempt": "1",
            "payload_json": json.dumps({"request_id": generation}),
        }


class ForbiddenService:
    def run_job(self, payload, progress_listener, should_cancel):
        raise AssertionError("stale running job was executed")


class CountingService:
    def __init__(self) -> None:
        self.calls = 0
        self.lock = threading.Lock()

    def run_job(self, payload, progress_listener, should_cancel):
        with self.lock:
            self.calls += 1
        return {
            "request_id": payload["request_id"],
            "status": "COMPLETED",
            "answer": {"markdown": "ok"},
            "citations": [],
            "usage": {"total_tokens": 1},
        }


if __name__ == "__main__":
    unittest.main()
