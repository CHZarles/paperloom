from __future__ import annotations

import unittest

from starlette.testclient import TestClient

from harness_py.evaluation.golden_fixture import GoldenFixtureHarness
from harness_py.orchestration.live_chat import LiveResearchChatHarness
from harness_py.orchestration.runtime import TurnExecutionResult
from harness_py.transport.service import (
    InFlightRegistry,
    ReadinessProbe,
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

    def test_readiness_probe_reports_ready_and_not_ready(self) -> None:
        class Provider:
            def public_diagnostics(self):
                return {"provider": "minimax", "has_api_key": True}

        service = ResearchHarnessService(
            provider=Provider(),
            harness=object(),
            corpus_gateway=object(),
        )

        # 探测成功：ready。
        service.readiness._corpus_probe = lambda: None
        service.readiness.refresh_corpus()
        ok, reasons = service.readiness.ready()
        self.assertTrue(ok)
        self.assertEqual([], reasons)

        # 探测失败：not ready，带 reason。
        def failing_probe():
            raise RuntimeError("down")

        service.readiness._corpus_probe = failing_probe
        service.readiness.refresh_corpus()
        ok, reasons = service.readiness.ready()
        self.assertFalse(ok)
        self.assertIn("corpus_unavailable", reasons[0])

    def test_ready_endpoint_maps_readiness_to_http_status(self) -> None:
        class Provider:
            def public_diagnostics(self):
                return {"provider": "minimax", "has_api_key": True}

        service = ResearchHarnessService(
            provider=Provider(),
            harness=object(),
            corpus_gateway=object(),
        )
        service.readiness._corpus_probe = lambda: None
        service.readiness.refresh_corpus()

        with TestClient(_service_app(service, "")) as client:
            ok_response = client.get("/ready")
            self.assertEqual(200, ok_response.status_code)
            self.assertEqual("ok", ok_response.json()["status"])
            self.assertEqual(0, ok_response.json()["in_flight"])

        # 探测失败后 /ready 返回 503 + reason。
        def failing_probe():
            raise RuntimeError("down")

        service.readiness._corpus_probe = failing_probe
        service.readiness.refresh_corpus()

        with TestClient(_service_app(service, "")) as client:
            fail_response = client.get("/ready")
            self.assertEqual(503, fail_response.status_code)
            self.assertIn("corpus_unavailable", fail_response.json()["reason"])


class ProviderStoreTest(unittest.TestCase):
    def _provider(self, name: str):
        from harness_py.transport.provider_config import ProviderConfig

        return ProviderConfig(
            scope="llm",
            provider=name,
            api_style="openai-compatible",
            api_base_url="https://example.invalid/v1",
            model=f"model-{name}",
            api_key=f"sk-{name}",
        )

    def test_store_mode_reloads_provider_and_rebuilds_only_on_change(self) -> None:
        class FakeStore:
            def __init__(self):
                self.provider = None

            def load_active_provider(self, scope):
                assert scope == "llm"
                return self.provider

        store = FakeStore()
        store.provider = self._provider("a")
        service = ResearchHarnessService(provider_store=store, corpus_gateway=object())

        first = service._harness_for_run()
        self.assertEqual("a", service.provider.provider)
        # 相同配置重复加载不重建 harness。
        self.assertIs(first, service._harness_for_run())

        store.provider = self._provider("b")
        second = service._harness_for_run()
        self.assertIsNot(first, second)
        self.assertEqual("b", service.provider.provider)

    def test_store_mode_run_turn_fails_closed_and_leaves_registry_empty(self) -> None:
        class BrokenStore:
            def load_active_provider(self, scope):
                raise RuntimeError("no active provider row found for scope=llm")

        service = ResearchHarnessService(provider_store=BrokenStore(), corpus_gateway=object())

        with self.assertRaises(RuntimeError):
            service.run_turn({
                "request_id": "request_no_provider",
                "conversation_id": "conversation_1",
                "user_id": 7,
                "user_message": "hi",
                "scope": {"paper_ids": ["synthetic_paper"]},
            })
        self.assertEqual(0, service.in_flight.count())

    def test_store_mode_reports_unconfigured_provider_until_loaded(self) -> None:
        class NotLoadedStore:
            def load_active_provider(self, scope):
                raise RuntimeError("no active provider row found for scope=llm")

        service = ResearchHarnessService(provider_store=NotLoadedStore(), corpus_gateway=object())

        self.assertEqual({}, service.provider_diagnostics())
        ok, reasons = service.readiness.ready()
        self.assertFalse(ok)
        self.assertIn("provider_unconfigured", reasons)

        with TestClient(_service_app(service, "")) as client:
            response = client.get("/health")

        self.assertEqual(200, response.status_code)
        self.assertEqual({}, response.json()["provider"])
