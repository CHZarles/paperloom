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
    catalog_answer_tool_definition,
    decide,
    direct_answer_tool_definition,
    render_catalog_submission,
    render_direct_submission,
    render_research_submission,
    research_agent_instructions,
    research_answer_tool_definition,
    validate_submission,
)
from harness_py.orchestration.research_skills import ResearchSkillRegistry


class ResearchProtocolTest(unittest.TestCase):
    def test_submission_transitions_are_table_driven(self) -> None:
        cases = [
            (
                "accepted direct",
                ProtocolState(),
                SubmissionRequested(
                    AnswerContract.DIRECT,
                    {"outcome": "answered", "markdown": "Hello."},
                    True,
                ),
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

    def test_submission_schemas_and_renderers_keep_direct_natural_and_catalog_authoritative(self) -> None:
        self.assertEqual("submit_direct_answer", direct_answer_tool_definition()["function"]["name"])
        self.assertEqual("submit_research_answer", research_answer_tool_definition()["function"]["name"])
        direct = {
            "outcome": "needs_clarification",
            "markdown": "我知道 vLLM，它是一个大模型推理与服务框架。\n\n你想了解哪一方面？",
        }
        self.assertTrue(validate_submission(AnswerContract.DIRECT, direct, ProtocolFacts()).accepted)
        self.assertEqual(
            direct["markdown"],
            render_direct_submission(direct)["markdown"],
        )
        self.assertEqual("needs_clarification", render_direct_submission(direct)["outcome"])

        facts = ProtocolFacts(catalog_results={
            "paper_result_1": {
                "matched_count": 31,
                "coverage": "complete",
                "papers": [{
                    "paper_id": "paper_1",
                    "title": "Attention Is All You Need",
                    "authors": ["Vaswani et al."],
                    "year": 2017,
                }],
            },
        })
        count = {"result_ref": "paper_result_1", "view": "COUNT", "language": "ZH_CN"}
        self.assertTrue(validate_submission(AnswerContract.CATALOG, count, facts).accepted)
        self.assertEqual("共找到 31 篇论文。", render_catalog_submission(count, facts)["markdown"])
        listing = {
            "result_ref": "paper_result_1",
            "view": "LIST",
            "paper_ids": ["paper_1"],
            "fields": ["title", "authors", "year"],
            "language": "ZH_CN",
        }
        self.assertEqual(
            "- 标题: Attention Is All You Need; 作者: Vaswani et al.; 年份: 2017",
            render_catalog_submission(listing, facts)["markdown"],
        )

        unknown = validate_submission(
            AnswerContract.CATALOG,
            {"result_ref": "missing", "view": "COUNT", "language": "ZH_CN"},
            facts,
        )
        self.assertEqual(SubmissionIssueClass.MISSING_CONTRACT_INPUT, unknown.issue_class)
        self.assertEqual(("UNKNOWN_CATALOG_RESULT_REF",), tuple(issue.code for issue in unknown.issues))

    def test_submission_tools_distinguish_metadata_lists_from_recommendation_reasons(self) -> None:
        direct_tool = direct_answer_tool_definition()["function"]
        catalog_description = catalog_answer_tool_definition()["function"]["description"]
        research_description = research_answer_tool_definition()["function"]["description"]

        self.assertEqual(
            ["markdown", "outcome"],
            sorted(direct_tool["parameters"]["required"]),
        )
        self.assertNotIn("kind", direct_tool["parameters"]["properties"])
        self.assertIn("natural conversational response", direct_tool["description"])
        self.assertIn("Do not use for recommendations with reasons", catalog_description)
        self.assertIn("recommendations with reasons", research_description)

    def test_agent_prompt_treats_do_you_know_as_a_question_about_the_topic(self) -> None:
        instructions = research_agent_instructions(ResearchSkillRegistry())

        self.assertIn("A bare familiarity check such as 'Do you know X?'", instructions)
        self.assertIn("briefly acknowledge X", instructions)
        self.assertIn("outcome=needs_clarification", instructions)
        self.assertIn("asks for a definition, details, mechanism, or comparison, use RESEARCH", instructions)

    def test_research_validation_binds_every_content_block_to_known_quotes(self) -> None:
        facts = ProtocolFacts(known_source_quotes={
            "source_quote_1": {"source_quote_ref": "source_quote_1", "citeable": True},
        })
        answered = {
            "outcome": "answered",
            "language": "EN",
            "markdown": "# Result\n\nSupported claim. [[source_quote_1]]",
        }
        valid = validate_submission(AnswerContract.RESEARCH, answered, facts)
        self.assertTrue(valid.accepted)
        rendered = render_research_submission(answered, facts)
        self.assertEqual(answered["markdown"], rendered["markdown"])
        self.assertEqual(["source_quote_1"], rendered["cited_source_quote_refs"])

        uncited = validate_submission(
            AnswerContract.RESEARCH,
            {"outcome": "answered", "language": "EN", "markdown": "Unsupported claim."},
            facts,
        )
        self.assertEqual(SubmissionIssueClass.MISSING_CONTRACT_INPUT, uncited.issue_class)
        self.assertEqual(("block_1",), uncited.issues[0].block_ids)

        malformed = validate_submission(
            AnswerContract.RESEARCH,
            {"outcome": "answered", "language": "EN", "markdown": "Claim. [[source_quote_ref]]"},
            facts,
        )
        self.assertEqual(SubmissionIssueClass.FORMAT_ISSUE, malformed.issue_class)
        self.assertEqual(("INVALID_SOURCE_MARKER",), tuple(issue.code for issue in malformed.issues))

        missing_language = validate_submission(
            AnswerContract.RESEARCH,
            {"outcome": "abstained", "abstention_reason": "NO_SUPPORTING_SOURCE"},
            facts,
        )
        self.assertFalse(missing_language.accepted)
        self.assertEqual(
            "当前论文库中没有找到足以支持回答的原文证据。",
            render_research_submission({
                "outcome": "abstained",
                "language": "ZH_CN",
                "abstention_reason": "NO_SUPPORTING_SOURCE",
            }, facts)["markdown"],
        )
        self.assertEqual(
            "No papers matching the question were found in the current corpus.",
            render_research_submission({
                "outcome": "abstained",
                "language": "EN",
                "abstention_reason": "NO_MATCHING_PAPER",
            }, facts)["markdown"],
        )


if __name__ == "__main__":
    unittest.main()
