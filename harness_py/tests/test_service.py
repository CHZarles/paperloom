from __future__ import annotations

import unittest

from starlette.testclient import TestClient

from harness_py.evaluation.golden_fixture import GoldenFixtureHarness
from harness_py.orchestration.live_chat import LiveResearchChatHarness
from harness_py.orchestration.runtime import TurnExecutionResult
from harness_py.transport.service import (
    InFlightRegistry,
    ResearchHarnessService,
    _service_app,
)
from harness_py.tests import test_harness_py as _harness_tests


class ServiceTest(unittest.TestCase):
    def test_service_rejects_removed_direct_corpus_store_argument(self) -> None:
        with self.assertRaises(TypeError):
            ResearchHarnessService(
                provider=object(),
                harness=object(),
                corpus_store=object(),
            )

    def test_health_exposes_harmless_model_runtime_contract(self) -> None:
        class Provider:
            def public_diagnostics(self):
                return {
                    "provider": "minimax",
                    "model": "MiniMax-M3",
                    "api_style": "openai-compatible",
                    "has_api_key": True,
                }

        service = ResearchHarnessService(
            provider=Provider(),
            harness=object(),
            corpus_gateway=object(),
        )

        with TestClient(_service_app(service, "")) as client:
            response = client.get("/health")

        self.assertEqual(200, response.status_code)
        self.assertEqual("MiniMax-M3", response.json()["provider"]["model"])
        self.assertNotIn("api_key", response.json()["provider"])
        self.assertEqual(0, response.json()["in_flight"])
        self.assertEqual(0, response.json()["eval_capture_failures"])

    def test_in_flight_registry_counts_register_and_unregister(self) -> None:
        registry = InFlightRegistry()

        registry.register(request_id="req_1", conversation_id="c_1", user_id=7)
        registry.register(request_id="req_2", conversation_id="c_2", user_id=8)

        self.assertEqual(2, registry.count())
        snapshot = registry.snapshot()
        self.assertEqual(2, len(snapshot))
        self.assertEqual({"c_1", "c_2"}, {entry["conversation_id"] for entry in snapshot})
        self.assertTrue(all(entry["state"] == "running" for entry in snapshot))
        self.assertEqual({7, 8}, {entry["user_id"] for entry in snapshot})

        registry.set_run_id("req_1", "run_abc")
        registry.unregister("req_1")
        self.assertEqual(1, registry.count())

        # 重复注销 / 对不存在的 request_id 操作是无副作用的。
        registry.unregister("req_1")
        registry.unregister("nope")
        registry.set_run_id("nope", "run_x")
        registry.set_state("nope", "cancelling")
        self.assertEqual(1, registry.count())

    def test_run_job_preserves_response_and_research_memory_contract(self) -> None:
        dataset = _harness_tests.PythonHarnessPrototypeTest()._synthetic_dataset()

        class CorpusReader:
            def load_metadata_dataset(self):
                return dataset

        class CorpusGateway:
            def reader(self, **kwargs):
                self.arguments = kwargs
                return CorpusReader()

        class Provider:
            def public_diagnostics(self):
                return {"provider": "test"}

        class FixtureRuntime:
            def run_turn(self, turn):
                self.retry_context = turn.retry_context
                self.run_limits = turn.run_limits
                run = GoldenFixtureHarness().run_case(turn.dataset, turn.dataset.cases[0])
                run["run_id"] = turn.run_id
                return TurnExecutionResult(run=run)

        gateway = CorpusGateway()
        runtime = FixtureRuntime()
        service = ResearchHarnessService(
            provider=Provider(),
            harness=LiveResearchChatHarness(runtime),
            corpus_gateway=gateway,
        )

        response = service.run_turn({
            "request_id": "request_1",
            "conversation_id": "conversation_1",
            "user_id": 7,
            "user_message": "What is the synthetic answer?",
            "history": [],
            "scope": {"paper_ids": ["synthetic_paper"]},
            "research_memory": {},
            "retry": {
                "kind": "USER_UNSATISFIED",
                "answer_slot_id": 12,
                "target_revision": 2,
                "previous_answer_markdown": "old",
            },
            "options": {
                "include_trace": True,
                "run_limits": {
                    "schema_version": "paperloom-run-limits/v1",
                    "max_wall_clock_ms": 10000,
                    "max_model_visible_tool_chars": 1000,
                    "max_history_chars": 1000,
                },
            },
        })

        self.assertEqual("request_1", response["request_id"])
        self.assertTrue(str(response["run_id"]).startswith("run_"))
        self.assertEqual("COMPLETED", response["status"])
        self.assertEqual("42", response["answer"]["fields"]["answer"])
        self.assertEqual(1, len(response["research_memory"]["selected_evidence_ids"]))
        self.assertIn("tool_calls", response["trace"])
        self.assertEqual(7, gateway.arguments["user_id"])
        self.assertEqual(["synthetic_paper"], gateway.arguments["scope_paper_ids"])
        self.assertEqual("USER_UNSATISFIED", runtime.retry_context["kind"])
        self.assertEqual(12, runtime.retry_context["answer_slot_id"])

    def test_run_job_registers_before_and_unregisters_after_execution(self) -> None:
        import threading
        import time

        dataset = _harness_tests.PythonHarnessPrototypeTest()._synthetic_dataset()

        class CorpusReader:
            def load_metadata_dataset(self):
                return dataset

        class CorpusGateway:
            def reader(self, **kwargs):
                return CorpusReader()

        class Provider:
            def public_diagnostics(self):
                return {"provider": "test"}

        entered = threading.Event()
        release = threading.Event()

        class SlowRuntime:
            def run_turn(self, turn):
                entered.set()
                release.wait(timeout=5)
                run = GoldenFixtureHarness().run_case(turn.dataset, turn.dataset.cases[0])
                run["run_id"] = turn.run_id
                return TurnExecutionResult(run=run)

        service = ResearchHarnessService(
            provider=Provider(),
            harness=LiveResearchChatHarness(SlowRuntime()),
            corpus_gateway=CorpusGateway(),
        )

        result = {}

        def worker():
            result["response"] = service.run_job(
                {
                    "request_id": "request_inflight",
                    "conversation_id": "conversation_inflight",
                    "user_id": 7,
                    "user_message": "What is the synthetic answer?",
                    "history": [],
                    "scope": {"paper_ids": ["synthetic_paper"]},
                    "research_memory": {},
                    "options": {"include_trace": False},
                },
                lambda _event: None,
                lambda: False,
            )

        thread = threading.Thread(target=worker, daemon=True)
        thread.start()
        entered.wait(timeout=5)

        # 执行期间注册表可见该在途回合。
        self.assertEqual(1, service.in_flight.count())
        snapshot = service.in_flight.snapshot()
        self.assertEqual("conversation_inflight", snapshot[0]["conversation_id"])
        self.assertEqual("running", snapshot[0]["state"])

        release.set()
        thread.join(timeout=5)

        # 执行结束后注销，注册表归零。
        self.assertEqual(0, service.in_flight.count())
        self.assertEqual("COMPLETED", result["response"]["status"])

    def test_draining_returns_503_with_retry_after(self) -> None:
        class Provider:
            def public_diagnostics(self):
                return {"provider": "test"}

        service = ResearchHarnessService(
            provider=Provider(),
            harness=object(),
            corpus_gateway=object(),
        )
        service.shutting_down.set()

        with TestClient(_service_app(service, "")) as client:
            turn_response = client.post(
                "/v1/research/turn",
                json={"user_message": "hi"},
            )
            stream_response = client.post(
                "/v1/research/stream",
                json={"user_message": "hi"},
            )

        self.assertEqual(503, turn_response.status_code)
        self.assertEqual("draining", turn_response.json()["error"])
        self.assertEqual("30", turn_response.headers["Retry-After"])
        self.assertEqual(503, stream_response.status_code)
        self.assertEqual("draining", stream_response.json()["error"])

    def test_defer_unregister_keeps_entry_until_terminal_event_is_emitted(self) -> None:
        dataset = _harness_tests.PythonHarnessPrototypeTest()._synthetic_dataset()

        class CorpusReader:
            def load_metadata_dataset(self):
                return dataset

        class CorpusGateway:
            def reader(self, **kwargs):
                return CorpusReader()

        class Provider:
            def public_diagnostics(self):
                return {"provider": "test"}

        class FixtureRuntime:
            def run_turn(self, turn):
                run = GoldenFixtureHarness().run_case(turn.dataset, turn.dataset.cases[0])
                run["run_id"] = turn.run_id
                return TurnExecutionResult(run=run)

        service = ResearchHarnessService(
            provider=Provider(),
            harness=LiveResearchChatHarness(FixtureRuntime()),
            corpus_gateway=CorpusGateway(),
        )
        payload = {
            "request_id": "request_defer",
            "conversation_id": "conversation_defer",
            "user_id": 7,
            "user_message": "What is the synthetic answer?",
            "history": [],
            "scope": {"paper_ids": ["synthetic_paper"]},
            "research_memory": {},
            "options": {"include_trace": False},
        }

        # 流式路径用 defer_unregister=True：run_job 返回后条目仍在，等待终态事件发出后再注销。
        service.run_job(payload, lambda _event: None, lambda: False, defer_unregister=True)

        self.assertEqual(1, service.in_flight.count())
        snapshot = service.in_flight.snapshot()
        self.assertEqual("request_defer", snapshot[0]["request_id"])
        self.assertTrue(snapshot[0]["run_id"].startswith("run_"))

        service.in_flight.unregister("request_defer")
        self.assertEqual(0, service.in_flight.count())
