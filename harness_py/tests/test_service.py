from __future__ import annotations

import unittest

from harness_py.evaluation.golden_fixture import GoldenFixtureHarness
from harness_py.orchestration.live_chat import LiveResearchChatHarness
from harness_py.orchestration.runtime import TurnExecutionResult
from harness_py.transport.service import ResearchHarnessService
from harness_py.tests import test_harness_py as _harness_tests


class ServiceTest(unittest.TestCase):
    def test_run_job_preserves_response_and_research_memory_contract(self) -> None:
        dataset = _harness_tests.PythonHarnessPrototypeTest()._synthetic_dataset()

        class CorpusStore:
            def load_dataset(self, **kwargs):
                return dataset

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
            corpus_store=CorpusStore(),
        )

        response = service.run_turn({
            "request_id": "request_1",
            "conversation_id": "conversation_1",
            "user_message": "What is the synthetic answer?",
            "history": [],
            "scope": {"paper_ids": ["synthetic_paper"]},
            "research_memory": {},
            "options": {"include_trace": True},
        })

        self.assertEqual("request_1", response["request_id"])
        self.assertEqual("COMPLETED", response["status"])
        self.assertEqual("42", response["answer"]["fields"]["answer"])
        self.assertEqual(1, len(response["research_memory"]["selected_evidence_ids"]))
        self.assertIn("tool_calls", response["trace"])
