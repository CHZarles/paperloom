from __future__ import annotations

import unittest

from harness_py.orchestration.research_contract import (
    ActionRequested,
    AnswerContract,
    Phase,
    ProtocolFacts,
    ProtocolState,
    SubmissionIssueClass,
    SubmissionRequested,
    decide,
)


class ResearchProtocolTest(unittest.TestCase):
    def test_submission_transitions_are_table_driven(self) -> None:
        cases = [
            (
                "accepted direct",
                ProtocolState(),
                SubmissionRequested(AnswerContract.DIRECT, {"kind": "GREETING"}, True),
                Phase.COMPLETE,
                AnswerContract.DIRECT,
            ),
            (
                "catalog needs a result",
                ProtocolState(),
                SubmissionRequested(
                    AnswerContract.CATALOG,
                    {"result_ref": "missing"},
                    False,
                    SubmissionIssueClass.MISSING_CONTRACT_INPUT,
                    ("UNKNOWN_CATALOG_RESULT_REF",),
                ),
                Phase.ACTIVE,
                AnswerContract.CATALOG,
            ),
            (
                "research needs a source quote",
                ProtocolState(),
                SubmissionRequested(
                    AnswerContract.RESEARCH,
                    {"markdown": "Unsupported."},
                    False,
                    SubmissionIssueClass.MISSING_CONTRACT_INPUT,
                    ("UNCITED_CONTENT_BLOCK",),
                ),
                Phase.ACTIVE,
                AnswerContract.RESEARCH,
            ),
            (
                "research format repair",
                ProtocolState(),
                SubmissionRequested(
                    AnswerContract.RESEARCH,
                    {"markdown": "Bad marker."},
                    False,
                    SubmissionIssueClass.FORMAT_ISSUE,
                    ("INVALID_SOURCE_MARKER",),
                ),
                Phase.REPAIR,
                AnswerContract.RESEARCH,
            ),
        ]

        for name, state, event, phase, contract in cases:
            with self.subTest(name=name):
                decision = decide(state, event, ProtocolFacts())

                self.assertEqual(phase, decision.next_state.phase)
                self.assertEqual(contract, decision.next_state.contract)
                self.assertEqual(1, decision.next_state.submission_attempt)

    def test_action_guards_reject_illegal_or_mixed_calls_without_changing_contract(self) -> None:
        research_repair = ProtocolState(
            phase=Phase.REPAIR,
            contract=AnswerContract.RESEARCH,
            submission_attempt=1,
        )
        cases = [
            (
                "initial discovery",
                ProtocolState(),
                ActionRequested("search_paper_candidates"),
                ProtocolFacts(),
                True,
            ),
            (
                "mixed submission group",
                ProtocolState(),
                ActionRequested("search_paper_candidates"),
                ProtocolFacts(sibling_tool_names=(
                    "search_paper_candidates",
                    "submit_research_answer",
                )),
                False,
            ),
            (
                "catalog cannot read content",
                ProtocolState(contract=AnswerContract.CATALOG),
                ActionRequested("read_paper_content"),
                ProtocolFacts(),
                False,
            ),
            (
                "repair cannot research",
                research_repair,
                ActionRequested("read_paper_content"),
                ProtocolFacts(),
                False,
            ),
            (
                "repair can resubmit",
                research_repair,
                ActionRequested("submit_research_answer"),
                ProtocolFacts(),
                True,
            ),
            (
                "complete is terminal",
                ProtocolState(phase=Phase.COMPLETE, contract=AnswerContract.RESEARCH),
                ActionRequested("submit_research_answer"),
                ProtocolFacts(),
                False,
            ),
        ]

        for name, state, event, facts, accepted in cases:
            with self.subTest(name=name):
                decision = decide(state, event, facts)

                self.assertEqual(accepted, decision.model_result["accepted"])
                self.assertEqual(state.phase, decision.next_state.phase)
                self.assertEqual(state.contract, decision.next_state.contract)

        mismatch = decide(
            ProtocolState(contract=AnswerContract.RESEARCH),
            SubmissionRequested(AnswerContract.CATALOG, {"result_ref": "result_1"}, True),
            ProtocolFacts(),
        )
        self.assertEqual("PROTOCOL_ERROR", mismatch.model_result["error_code"])
        self.assertEqual(AnswerContract.RESEARCH, mismatch.next_state.contract)


if __name__ == "__main__":
    unittest.main()
