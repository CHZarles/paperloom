from __future__ import annotations

import json
import unittest
from dataclasses import replace
from pathlib import Path

import yaml

from harness_py.dataset import load_artifact_contracts, load_dataset
from harness_py.golden_case import case_expect, case_messages, case_question
from harness_py.live_chat import LiveResearchChatHarness
from harness_py.llm import ChatModel, ChatTurn, ToolCall
from harness_py.models import GoldenDataset, as_list, child_map
from harness_py.scoring import BehaviorScorer
from harness_py.stage_prototype.intent import IntentRecognizer
from harness_py.stage_prototype.models import IntentFrame, ResearchState, StageResult, StageSpec, TurnFrame, normalize_claim
from harness_py.stage_prototype.plans import PARADIGM_DEFINITIONS
from harness_py.stage_prototype.runtime import (
    ParadigmDrivenHarness,
    StageRunner,
    _initial_research_state,
    _normalize_answer_status,
)
from harness_py.tools import ReadingCorpusTools


GOLDEN_PARADIGMS = {
    "precision_fact_extraction",
    "methodology_reproduction",
    "deep_comparison",
    "association_influence_genealogy",
    "complex_multihop_reasoning",
    "uncertainty_knowledge_boundary",
    "ambiguity_resolution",
    "contradiction_resolution",
}

ALL_REMAKE_PARADIGMS = {
    "precision_fact_extraction",
    "concept_tracing_definition",
    "deep_comparison",
    "methodology_reproduction",
    "contribution_critical_assessment",
    "association_influence_genealogy",
    "context_specific_brainstorming",
    "data_benchmark_provenance",
    "hypothetical_discussion",
    "multimodal_cross_domain_system_design",
    "theory_math_formal_proof",
    "author_institution_research_style",
    "dataset_contamination_generalization",
    "meta_analysis_systematic_review",
    "boundary_failure_counterexample",
    "future_prediction_open_problem",
    "complex_multihop_reasoning",
    "uncertainty_knowledge_boundary",
    "ambiguity_resolution",
    "contradiction_resolution",
    "structured_info_nontraditional_query",
    "complex_constraint_combination",
}


class ParadigmPlanTest(unittest.TestCase):
    def test_all_golden_data_paradigms_have_explicit_plans(self) -> None:
        self.assertEqual(ALL_REMAKE_PARADIGMS, set(PARADIGM_DEFINITIONS))
        self.assertTrue(GOLDEN_PARADIGMS <= set(PARADIGM_DEFINITIONS))

        for paradigm_id, definition in PARADIGM_DEFINITIONS.items():
            self.assertTrue(definition.stages, paradigm_id)
            self.assertTrue(definition.stages[-1].produces_answer, paradigm_id)
            names = [stage.name for stage in definition.stages]
            self.assertEqual(len(names), len(set(names)), paradigm_id)
            self.assertTrue(all(stage.strategy for stage in definition.stages), paradigm_id)
        contracts = load_artifact_contracts("research/golden-data/artifact-contracts.yaml")
        answer_types = set(child_map(contracts.get("shared_enums")).get("answer_type") or [])
        self.assertTrue(
            {definition.default_answer_shape for definition in PARADIGM_DEFINITIONS.values()}
            <= answer_types
        )
        self.assertGreaterEqual(
            PARADIGM_DEFINITIONS["complex_multihop_reasoning"].stages[2].max_tool_calls,
            8,
        )
        self.assertTrue(
            PARADIGM_DEFINITIONS["uncertainty_knowledge_boundary"].stages[1].requires_new_evidence
        )

    def test_committed_cases_cover_every_golden_paradigm_as_primary(self) -> None:
        dataset = load_dataset("research/golden-data/manifest.yaml")
        primaries = {str(case.get("paradigm")) for case in dataset.cases}

        self.assertEqual(GOLDEN_PARADIGMS, primaries)

    def test_intent_recognizer_returns_typed_primary_paradigm(self) -> None:
        model = _IntentOnlyModel()
        dataset = _synthetic_dataset()
        turn = TurnFrame(
            turn_id="turn_1",
            question="Compare the two papers.",
            allowed_paper_ids=["synthetic_paper"],
        )

        intent = IntentRecognizer(model).recognize(turn, dataset)

        self.assertEqual("deep_comparison", intent.primary_paradigm)
        self.assertEqual("comparison_matrix", intent.answer_shape)
        self.assertEqual("unambiguous", intent.ambiguity_status)
        self.assertEqual(1, model.call_count)

    def test_intent_recognizer_routes_user_blocking_ambiguity_to_clarification(self) -> None:
        intent = IntentRecognizer(_ConflictedAmbiguityIntentModel()).recognize(
            TurnFrame(
                turn_id="turn_1",
                question="What does the benchmark paper conclude?",
                allowed_paper_ids=["synthetic_paper"],
            ),
            _synthetic_dataset(),
        )

        self.assertEqual("ambiguity_resolution", intent.primary_paradigm)
        self.assertEqual("ambiguity_clarification", intent.answer_shape)
        self.assertEqual("ambiguous", intent.ambiguity_status)

    def test_answer_status_aliases_normalize_to_completed(self) -> None:
        self.assertEqual("COMPLETED", _normalize_answer_status("ANSWERABLE"))
        self.assertEqual("COMPLETED", _normalize_answer_status("DRAFT_COMPLETE"))

    def test_supported_claim_without_accepted_evidence_becomes_underdetermined(self) -> None:
        claim = normalize_claim(
            {
                "claim_id": "unsupported",
                "text": "An unsupported recommendation.",
                "status": "supported",
                "supporting_evidence_ids": ["unknown_evidence"],
            },
            known_evidence_ids=set(),
            index=0,
        )

        self.assertEqual("underdetermined", claim["status"])

    def test_model_facing_candidate_cards_do_not_expose_evidence_ids(self) -> None:
        state = ResearchState.new("turn_1")
        state.paper_candidates_by_id["synthetic_paper"] = {
            "evidence_id": "paper_candidate_synthetic_paper",
            "paper_id": "synthetic_paper",
            "title": "Synthetic Paper",
            "citeable": False,
        }

        candidate = state.to_prompt_dict()["paper_candidates"][0]

        self.assertEqual("synthetic_paper", candidate["candidate_ref"])
        self.assertNotIn("evidence_id", candidate)

    def test_answer_stage_requires_structured_submission_tool(self) -> None:
        dataset = _synthetic_dataset()
        model = _RequiredStageResultModel()
        stage = StageSpec(
            name="recommend",
            instruction="Return a concise recommendation.",
            produces_answer=True,
        )
        intent = IntentFrame(
            primary_paradigm="context_specific_brainstorming",
            normalized_goal="Recommend a paper.",
            answer_shape="constraint_filter",
            paper_references=[],
            requested_aspects=[],
            constraints={},
            ambiguity={"status": "unambiguous"},
            confidence=1.0,
        )

        execution = StageRunner(model, ReadingCorpusTools(dataset)).run(
            stage,
            TurnFrame("turn_1", "Recommend a paper.", ["synthetic_paper"]),
            intent,
            ResearchState.new("turn_1"),
            dataset,
        )

        self.assertEqual("completed", execution.result.status)
        self.assertEqual(2, model.required_tool_calls)

    def test_evidence_stage_requires_find_read_and_structured_submission(self) -> None:
        dataset = _synthetic_dataset()
        model = _RequiredEvidenceSequenceModel()
        stage = PARADIGM_DEFINITIONS["precision_fact_extraction"].stages[2]
        intent = IntentFrame(
            primary_paradigm="precision_fact_extraction",
            normalized_goal="Find the exact synthetic answer.",
            answer_shape="exact_fact",
            paper_references=[{"paper_id": "synthetic_paper"}],
            requested_aspects=["answer"],
            constraints={},
            ambiguity={"status": "unambiguous"},
            confidence=1.0,
        )

        execution = StageRunner(model, ReadingCorpusTools(dataset)).run(
            stage,
            TurnFrame("turn_1", "What is the synthetic answer?", ["synthetic_paper"]),
            intent,
            _selected_state(),
            dataset,
        )

        self.assertEqual("completed", execution.result.status)
        self.assertEqual(
            ["find_reading_locations", "read_locations", "submit_stage_result"],
            model.required_tool_names,
        )
        self.assertEqual(
            {"find_reading_locations"},
            model.available_tool_names[0],
        )

    def test_conversation_selected_paper_authorizes_follow_up_reading(self) -> None:
        dataset = _synthetic_dataset()
        case = {
            **dataset.cases[0],
            "id": "conversation_follow_up",
            "question": {"text": "Compare the selected paper."},
            "conversation_context": {"selected_paper_ids": ["synthetic_paper"]},
        }
        intent = IntentFrame(
            primary_paradigm="deep_comparison",
            normalized_goal="Compare the selected paper.",
            answer_shape="comparison_matrix",
            paper_references=[{"paper_id": "synthetic_paper"}],
            requested_aspects=["answer"],
            constraints={},
            ambiguity={"status": "unambiguous"},
            confidence=1.0,
            requires_corpus_observation=True,
        )

        run = ParadigmDrivenHarness(_ConversationAuthorizedComparisonModel()).run_case_with_intent(
            dataset,
            case,
            intent,
        )

        self.assertEqual("COMPLETED", run["status"])
        self.assertEqual(
            ["find_reading_locations", "read_locations"],
            [
                call["tool_name"]
                for stage in run["stage_trace"]
                for call in stage["tool_calls"]
            ],
        )

    def test_conversation_evidence_is_available_to_the_follow_up_run(self) -> None:
        dataset = _synthetic_dataset()
        turn = TurnFrame(
            "turn_2",
            "Compare the selected paper.",
            ["synthetic_paper"],
            conversation_context={
                "selected_paper_ids": ["synthetic_paper"],
                "selected_evidence_refs": [{
                    "evidence_id": "ev_prior",
                    "paper_id": "synthetic_paper",
                    "title": "Synthetic Paper",
                    "section": "Results",
                    "page": 1,
                    "location": "reading_element_prior",
                    "element_type": "paragraph",
                    "span_text": "A previously read citeable passage.",
                }],
            },
        )

        state = _initial_research_state(turn, dataset)

        self.assertIn("ev_prior", state.evidence_items_by_id)
        self.assertIn("synthetic_paper", state.authorized_paper_ids)

    def test_stage_runner_executes_only_stage_tools_and_accepts_observed_evidence(self) -> None:
        dataset = _synthetic_dataset()
        tools = ReadingCorpusTools(dataset)
        stage = PARADIGM_DEFINITIONS["precision_fact_extraction"].stages[2]
        intent = IntentFrame(
            primary_paradigm="precision_fact_extraction",
            normalized_goal="Find the exact synthetic answer.",
            answer_shape="exact_fact",
            paper_references=[],
            requested_aspects=["answer"],
            constraints={},
            ambiguity={"status": "unambiguous"},
            confidence=1.0,
        )
        model = _EvidenceStageModel()

        execution = StageRunner(model, tools).run(
            stage,
            TurnFrame("turn_1", "What is the synthetic answer?", ["synthetic_paper"]),
            intent,
            _selected_state(),
            dataset,
        )

        self.assertEqual("completed", execution.result.status)
        self.assertEqual(1, len(execution.result.accepted_evidence_ids))
        self.assertEqual("find_reading_locations", execution.tool_trace[0]["tool_name"])

    def test_candidate_stage_caps_selected_papers_to_six(self) -> None:
        dataset = _synthetic_dataset()
        dataset = replace(
            dataset,
            paper_records_by_id={
                f"paper_{index}": {
                    "paper_id": f"paper_{index}",
                    "identity": {"title": f"Paper {index}", "year": 2026},
                }
                for index in range(9)
            },
            reading_models_by_paper_id={},
        )
        stage = StageSpec(
            name="resolve_candidates",
            instruction="Resolve a bounded candidate set.",
            allowed_tools=("search_paper_candidates",),
        )
        intent = IntentFrame(
            primary_paradigm="complex_constraint_combination",
            normalized_goal="Recommend a bounded set.",
            answer_shape="constraint_filter",
            paper_references=[],
            requested_aspects=[],
            constraints={},
            ambiguity={"status": "unambiguous"},
            confidence=1.0,
        )

        execution = StageRunner(_CandidateOverflowModel(), ReadingCorpusTools(dataset)).run(
            stage,
            TurnFrame("turn_1", "Recommend papers.", sorted(dataset.paper_records_by_id)),
            intent,
            ResearchState.new("turn_1"),
            dataset,
        )

        self.assertEqual(6, len(execution.result.selected_paper_ids))
        self.assertTrue(execution.result.diagnostics["candidate_cap_applied"])

    def test_answer_stage_repairs_supported_claim_without_evidence(self) -> None:
        dataset = _synthetic_dataset()
        stage = StageSpec(
            name="present_recommendation",
            instruction="Present a recommendation.",
            produces_answer=True,
        )
        intent = IntentFrame(
            primary_paradigm="context_specific_brainstorming",
            normalized_goal="Recommend a paper.",
            answer_shape="constraint_filter",
            paper_references=[],
            requested_aspects=[],
            constraints={},
            ambiguity={"status": "unambiguous"},
            confidence=1.0,
        )
        model = _UnsupportedClaimThenRepairModel()

        execution = StageRunner(model, ReadingCorpusTools(dataset)).run(
            stage,
            TurnFrame("turn_1", "Recommend a paper.", ["synthetic_paper"]),
            intent,
            ResearchState.new("turn_1"),
            dataset,
        )

        self.assertEqual(2, model.call_count)
        self.assertEqual("underdetermined", execution.result.claims[0]["status"])
        self.assertEqual("completed", execution.result.status)

    def test_evidence_stage_does_not_inject_search_when_model_declines_tools(self) -> None:
        dataset = _synthetic_dataset()
        stage = PARADIGM_DEFINITIONS["precision_fact_extraction"].stages[2]
        intent = IntentFrame(
            primary_paradigm="precision_fact_extraction",
            normalized_goal="Find the structured value forty two.",
            answer_shape="exact_fact",
            paper_references=[],
            requested_aspects=["answer"],
            constraints={},
            ambiguity={"status": "unambiguous"},
            confidence=1.0,
        )

        execution = StageRunner(_NoToolThenAcceptEvidenceModel(), ReadingCorpusTools(dataset)).run(
            stage,
            TurnFrame("turn_1", "What is the synthetic answer?", ["synthetic_paper"]),
            intent,
            _selected_state(),
            dataset,
        )

        self.assertEqual("incomplete_precise", execution.result.status)
        self.assertEqual([], execution.result.accepted_evidence_ids)
        self.assertEqual([], execution.tool_trace)

    def test_candidate_stage_does_not_inject_search_when_model_declines_tools(self) -> None:
        dataset = _synthetic_dataset()
        stage = StageSpec(
            name="resolve_candidates",
            instruction="Resolve candidate papers.",
            allowed_tools=("search_paper_candidates",),
        )
        intent = IntentFrame(
            primary_paradigm="complex_constraint_combination",
            normalized_goal="Recommend relevant papers.",
            answer_shape="constraint_filter",
            paper_references=[],
            requested_aspects=[],
            constraints={},
            ambiguity={"status": "unambiguous"},
            confidence=1.0,
            requires_corpus_observation=True,
        )

        execution = StageRunner(_NoToolThenAcceptCandidatesModel(), ReadingCorpusTools(dataset)).run(
            stage,
            TurnFrame("turn_1", "Recommend papers.", ["synthetic_paper"]),
            intent,
            ResearchState.new("turn_1"),
            dataset,
        )

        self.assertEqual("incomplete_precise", execution.result.status)
        self.assertEqual([], execution.result.selected_paper_ids)
        self.assertEqual([], execution.tool_trace)

    def test_evidence_stage_does_not_promote_observed_results_when_final_json_is_truncated(self) -> None:
        dataset = _synthetic_dataset()
        stage = PARADIGM_DEFINITIONS["precision_fact_extraction"].stages[2]
        intent = IntentFrame(
            primary_paradigm="precision_fact_extraction",
            normalized_goal="Find the structured value forty two.",
            answer_shape="exact_fact",
            paper_references=[],
            requested_aspects=["answer"],
            constraints={},
            ambiguity={"status": "unambiguous"},
            confidence=1.0,
        )

        execution = StageRunner(_TruncatedEvidenceResultModel(), ReadingCorpusTools(dataset)).run(
            stage,
            TurnFrame("turn_1", "What is the synthetic answer?", ["synthetic_paper"]),
            intent,
            _selected_state(),
            dataset,
        )

        self.assertEqual("incomplete_precise", execution.result.status)
        self.assertEqual([], execution.result.accepted_evidence_ids)
        self.assertEqual(1, len(execution.result.diagnostics["observed_evidence_ids"]))

    def test_nonblocking_answer_keeps_soft_assumptions_nonterminal(self) -> None:
        dataset = _synthetic_dataset()
        stage = StageSpec(
            name="present_options",
            instruction="Present evidence-bounded options.",
            produces_answer=True,
        )
        intent = IntentFrame(
            primary_paradigm="context_specific_brainstorming",
            normalized_goal="Recommend a broad reading set.",
            answer_shape="constraint_filter",
            paper_references=[],
            requested_aspects=[],
            constraints={},
            ambiguity={"status": "non_blocking"},
            confidence=1.0,
            actionability="non_blocking",
        )

        execution = StageRunner(_SoftMissingAnswerModel(), ReadingCorpusTools(dataset)).run(
            stage,
            TurnFrame("turn_1", "Recommend papers.", ["synthetic_paper"]),
            intent,
            ResearchState.new("turn_1"),
            dataset,
        )

        self.assertEqual("completed", execution.result.status)
        self.assertEqual("COMPLETED", execution.result.answer["status"])
        self.assertEqual([], execution.result.missing_obligations)

    def test_completed_uncertainty_stage_is_a_completed_answer(self) -> None:
        dataset = _synthetic_dataset()
        stage = StageSpec(
            name="report_known_and_unknown",
            instruction="Report the evidence boundary.",
            produces_answer=True,
        )
        intent = IntentFrame(
            primary_paradigm="uncertainty_knowledge_boundary",
            normalized_goal="Determine whether the claim is proven.",
            answer_shape="uncertainty_boundary",
            paper_references=[],
            requested_aspects=[],
            constraints={},
            ambiguity={"status": "unambiguous"},
            confidence=1.0,
        )

        execution = StageRunner(
            _CompletedBoundaryAnswerModel(),
            ReadingCorpusTools(dataset),
        ).run(
            stage,
            TurnFrame("turn_1", "Is the claim proven?", ["synthetic_paper"]),
            intent,
            ResearchState.new("turn_1"),
            dataset,
        )

        self.assertEqual("completed", execution.result.status)
        self.assertEqual("COMPLETED", execution.result.answer["status"])

    def test_nonterminal_stage_failure_does_not_abstain_after_later_recovery(self) -> None:
        dataset = _synthetic_dataset()
        run = ParadigmDrivenHarness(
            _NonterminalUnparseableContextModel("deep_comparison", "comparison_matrix"),
        ).run_case(dataset, dataset.cases[0])

        self.assertEqual("incomplete_precise", run["stage_trace"][1]["status"])
        self.assertEqual("COMPLETED", run["research_answer"]["status"])

    def test_evidence_stage_retries_when_model_claims_unobserved_evidence(self) -> None:
        dataset = _synthetic_dataset()
        tools = ReadingCorpusTools(dataset)
        stage = PARADIGM_DEFINITIONS["precision_fact_extraction"].stages[2]
        intent = IntentFrame(
            primary_paradigm="precision_fact_extraction",
            normalized_goal="Find the exact synthetic answer.",
            answer_shape="exact_fact",
            paper_references=[],
            requested_aspects=["answer"],
            constraints={},
            ambiguity={"status": "unambiguous"},
            confidence=1.0,
        )
        model = _UnobservedEvidenceThenToolModel()

        execution = StageRunner(model, tools).run(
            stage,
            TurnFrame("turn_1", "What is the synthetic answer?", ["synthetic_paper"]),
            intent,
            _selected_state(),
            dataset,
        )

        self.assertEqual("completed", execution.result.status)
        self.assertEqual(1, len(execution.result.accepted_evidence_ids))
        self.assertEqual("find_reading_locations", execution.tool_trace[0]["tool_name"])
        self.assertEqual(4, model.call_count)

    def test_evidence_stage_attempts_tools_before_precise_incompleteness(self) -> None:
        dataset = _synthetic_dataset()
        tools = ReadingCorpusTools(dataset)
        stage = PARADIGM_DEFINITIONS["precision_fact_extraction"].stages[2]
        intent = IntentFrame(
            primary_paradigm="precision_fact_extraction",
            normalized_goal="Find the exact synthetic answer.",
            answer_shape="exact_fact",
            paper_references=[],
            requested_aspects=["answer"],
            constraints={},
            ambiguity={"status": "unambiguous"},
            confidence=1.0,
        )
        model = _PrematureEvidenceAbstentionThenToolModel()

        execution = StageRunner(model, tools).run(
            stage,
            TurnFrame("turn_1", "What is the synthetic answer?", ["synthetic_paper"]),
            intent,
            _selected_state(),
            dataset,
        )

        self.assertEqual("completed", execution.result.status)
        self.assertEqual(1, len(execution.result.accepted_evidence_ids))
        self.assertEqual("find_reading_locations", execution.tool_trace[0]["tool_name"])
        self.assertEqual(4, model.call_count)

    def test_evidence_stage_can_complete_after_an_explicit_no_match_search(self) -> None:
        dataset = _synthetic_dataset()
        stage = replace(
            PARADIGM_DEFINITIONS["uncertainty_knowledge_boundary"].stages[1],
            requires_new_evidence=True,
        )
        intent = IntentFrame(
            primary_paradigm="uncertainty_knowledge_boundary",
            normalized_goal="Test an unsupported claim.",
            answer_shape="uncertainty_boundary",
            paper_references=[],
            requested_aspects=[],
            constraints={},
            ambiguity={"status": "unambiguous"},
            confidence=1.0,
        )

        execution = StageRunner(
            _ExplicitNoMatchModel(),
            ReadingCorpusTools(dataset),
        ).run(
            stage,
            TurnFrame("turn_1", "What is the unsupported detail?", ["synthetic_paper"]),
            intent,
            _selected_state(),
            dataset,
        )

        self.assertEqual("completed", execution.result.status)
        self.assertEqual([], execution.result.accepted_evidence_ids)
        self.assertFalse(execution.result.state_values["support_found"])
        self.assertEqual("find_reading_locations", execution.tool_trace[0]["tool_name"])
        self.assertIn(
            "no_matching_evidence",
            {item.get("reason") for item in execution.result.missing_obligations},
        )

    def test_semantic_state_stage_retries_a_premature_downstream_stop(self) -> None:
        dataset = _synthetic_dataset()
        stage = PARADIGM_DEFINITIONS["precision_fact_extraction"].stages[1]
        intent = IntentFrame(
            primary_paradigm="precision_fact_extraction",
            normalized_goal="Find the exact synthetic answer.",
            answer_shape="exact_fact",
            paper_references=[],
            requested_aspects=["answer"],
            constraints={},
            ambiguity={"status": "unambiguous"},
            confidence=1.0,
        )
        model = _PrematureStopThenCompleteModel()

        execution = StageRunner(model, ReadingCorpusTools(dataset)).run(
            stage,
            TurnFrame("turn_1", "What is the synthetic answer?", ["synthetic_paper"]),
            intent,
            _selected_state(),
            dataset,
        )

        self.assertEqual("completed", execution.result.status)
        self.assertEqual({"fact_slots": ["answer"]}, execution.result.state_values)
        self.assertEqual([], execution.result.claims)
        self.assertEqual({}, execution.result.answer)
        self.assertEqual(2, model.call_count)

    def test_out_of_stage_tool_call_forces_precise_incompleteness(self) -> None:
        dataset = _synthetic_dataset()
        tools = ReadingCorpusTools(dataset)
        stage = PARADIGM_DEFINITIONS["precision_fact_extraction"].stages[0]
        intent = IntentFrame(
            primary_paradigm="precision_fact_extraction",
            normalized_goal="Resolve the synthetic paper.",
            answer_shape="exact_fact",
            paper_references=[],
            requested_aspects=[],
            constraints={},
            ambiguity={"status": "unambiguous"},
            confidence=1.0,
        )

        execution = StageRunner(_OutOfStageToolModel(), tools).run(
            stage,
            TurnFrame("turn_1", "Resolve the synthetic paper.", ["synthetic_paper"]),
            intent,
            ResearchState.new("turn_1"),
            dataset,
        )

        self.assertEqual("incomplete_precise", execution.result.status)
        self.assertIn(
            "out_of_stage_tool_call",
            {item.get("reason") for item in execution.result.missing_obligations},
        )

    def test_stage_tool_budget_denies_excess_calls_without_discarding_sufficient_evidence(self) -> None:
        dataset = _synthetic_dataset()
        tools = ReadingCorpusTools(dataset)
        stage = replace(
            PARADIGM_DEFINITIONS["precision_fact_extraction"].stages[2],
            max_tool_calls=2,
        )
        intent = IntentFrame(
            primary_paradigm="precision_fact_extraction",
            normalized_goal="Find the exact synthetic answer.",
            answer_shape="exact_fact",
            paper_references=[],
            requested_aspects=["answer"],
            constraints={},
            ambiguity={"status": "unambiguous"},
            confidence=1.0,
        )

        execution = StageRunner(_TooManyToolCallsModel(), tools).run(
            stage,
            TurnFrame("turn_1", "What is the synthetic answer?", ["synthetic_paper"]),
            intent,
            _selected_state(),
            dataset,
        )

        self.assertEqual("completed", execution.result.status)
        self.assertEqual(3, len(execution.tool_trace))
        self.assertTrue(execution.tool_trace[0]["allowed"])
        self.assertTrue(execution.tool_trace[1]["allowed"])
        self.assertFalse(execution.tool_trace[2]["allowed"])
        self.assertTrue(execution.result.diagnostics["tool_budget_exhausted"])
        self.assertNotIn(
            "stage_tool_budget_exhausted",
            {item.get("reason") for item in execution.result.missing_obligations},
        )

    def test_stage_prompt_states_the_tool_budget_and_batching_rule(self) -> None:
        dataset = _synthetic_dataset()
        stage = PARADIGM_DEFINITIONS["precision_fact_extraction"].stages[0]
        model = _CaptureStagePromptModel()
        intent = IntentFrame(
            primary_paradigm="precision_fact_extraction",
            normalized_goal="Resolve the synthetic paper.",
            answer_shape="exact_fact",
            paper_references=[],
            requested_aspects=[],
            constraints={},
            ambiguity={"status": "unambiguous"},
            confidence=1.0,
        )

        StageRunner(model, ReadingCorpusTools(dataset)).run(
            stage,
            TurnFrame("turn_1", "Resolve the synthetic paper.", ["synthetic_paper"]),
            intent,
            ResearchState.new("turn_1"),
            dataset,
        )

        self.assertIn("At most 6 tool calls", model.system_prompt)
        self.assertIn("batch", model.system_prompt.lower())

    def test_semantic_stage_prompt_requires_compact_state_instead_of_a_draft_answer(self) -> None:
        dataset = _synthetic_dataset()
        stage = PARADIGM_DEFINITIONS["methodology_reproduction"].stages[1]
        model = _CaptureStagePromptModel()
        intent = IntentFrame(
            primary_paradigm="methodology_reproduction",
            normalized_goal="Reproduce the synthetic method.",
            answer_shape="reproduction_protocol",
            paper_references=[],
            requested_aspects=[],
            constraints={},
            ambiguity={"status": "unambiguous"},
            confidence=1.0,
        )

        StageRunner(model, ReadingCorpusTools(dataset)).run(
            stage,
            TurnFrame("turn_1", "Reproduce the synthetic method.", ["synthetic_paper"]),
            intent,
            ResearchState.new("turn_1"),
            dataset,
        )

        self.assertIn("state_values under 600 characters", model.system_prompt)
        self.assertIn("not a draft answer", model.system_prompt)

    def test_answer_stage_prompt_prevents_repeating_the_answer_across_artifacts(self) -> None:
        dataset = _synthetic_dataset()
        stage = PARADIGM_DEFINITIONS["contribution_critical_assessment"].stages[-1]
        model = _CaptureStagePromptModel()
        intent = IntentFrame(
            primary_paradigm="contribution_critical_assessment",
            normalized_goal="Assess the synthetic paper.",
            answer_shape="critical_assessment",
            paper_references=[],
            requested_aspects=[],
            constraints={},
            ambiguity={"status": "unambiguous"},
            confidence=1.0,
        )

        StageRunner(model, ReadingCorpusTools(dataset)).run(
            stage,
            TurnFrame("turn_1", "Assess the synthetic paper.", ["synthetic_paper"]),
            intent,
            ResearchState.new("turn_1"),
            dataset,
        )

        self.assertIn("Do not repeat the answer prose", model.system_prompt)
        self.assertIn("at most 6 atomic claims", model.system_prompt)
        self.assertIn("cite at most 12 evidence ids", model.system_prompt)
        self.assertIn("answer.markdown under 2500 characters", model.system_prompt)
        self.assertIn("remove detail instead of ending mid-sentence", model.system_prompt)

    def test_non_answer_identity_stage_discards_premature_claims_and_answer(self) -> None:
        dataset = _synthetic_dataset()
        stage = PARADIGM_DEFINITIONS["precision_fact_extraction"].stages[0]
        intent = IntentFrame(
            primary_paradigm="precision_fact_extraction",
            normalized_goal="Resolve the synthetic paper.",
            answer_shape="exact_fact",
            paper_references=[],
            requested_aspects=[],
            constraints={},
            ambiguity={"status": "unambiguous"},
            confidence=1.0,
        )

        execution = StageRunner(_PrematureIdentityOutputModel(), ReadingCorpusTools(dataset)).run(
            stage,
            TurnFrame("turn_1", "Resolve the synthetic paper.", ["synthetic_paper"]),
            intent,
            ResearchState.new("turn_1"),
            dataset,
        )

        self.assertEqual(["synthetic_paper"], execution.result.selected_paper_ids)
        self.assertEqual([], execution.result.claims)
        self.assertEqual({}, execution.result.answer)

    def test_resolved_identity_stage_retries_a_premature_downstream_stop(self) -> None:
        dataset = _synthetic_dataset()
        stage = PARADIGM_DEFINITIONS["methodology_reproduction"].stages[0]
        intent = IntentFrame(
            primary_paradigm="methodology_reproduction",
            normalized_goal="Reproduce the synthetic method.",
            answer_shape="reproduction_protocol",
            paper_references=[],
            requested_aspects=[],
            constraints={},
            ambiguity={"status": "unambiguous"},
            confidence=1.0,
        )
        model = _ResolvedIdentityStopThenCompleteModel()

        execution = StageRunner(model, ReadingCorpusTools(dataset)).run(
            stage,
            TurnFrame("turn_1", "Reproduce the synthetic method.", ["synthetic_paper"]),
            intent,
            ResearchState.new("turn_1"),
            dataset,
        )

        self.assertEqual("completed", execution.result.status)
        self.assertEqual(["synthetic_paper"], execution.result.selected_paper_ids)
        self.assertEqual(2, model.result_count)

    def test_identity_stage_retries_content_capability_confusion(self) -> None:
        dataset = _synthetic_dataset()
        stage = PARADIGM_DEFINITIONS["complex_multihop_reasoning"].stages[1]
        intent = IntentFrame(
            primary_paradigm="complex_multihop_reasoning",
            normalized_goal="Resolve the synthetic hop entity.",
            answer_shape="multi_hop_chain",
            paper_references=[],
            requested_aspects=[],
            constraints={},
            ambiguity={"status": "unambiguous"},
            confidence=1.0,
        )
        model = _IdentityCapabilityConfusionModel()

        execution = StageRunner(model, ReadingCorpusTools(dataset)).run(
            stage,
            TurnFrame("turn_1", "Resolve the synthetic hop entity.", ["synthetic_paper"]),
            intent,
            ResearchState.new("turn_1"),
            dataset,
        )

        self.assertEqual("completed", execution.result.status)
        self.assertEqual(["synthetic_paper"], execution.result.selected_paper_ids)
        self.assertEqual("find_papers_by_identity", execution.tool_trace[0]["tool_name"])
        self.assertEqual(3, model.call_count)

    def test_paradigm_harness_runs_online_stages_in_plan_order(self) -> None:
        dataset = _synthetic_dataset()
        model = _ExactFactHarnessModel()
        case = dataset.cases[0]

        run = ParadigmDrivenHarness(model).run_case(dataset, case)

        self.assertEqual("COMPLETED", run["status"])
        self.assertEqual("precision_fact_extraction", run["intent_frame"]["primary_paradigm"])
        self.assertEqual(
            [stage.name for stage in PARADIGM_DEFINITIONS["precision_fact_extraction"].stages],
            [stage["stage_name"] for stage in run["stage_trace"]],
        )
        first_stage = run["stage_trace"][0]
        self.assertEqual("paper_identity_resolution", first_stage["strategy"])
        self.assertEqual(
            ["search_paper_candidates", "find_papers_by_identity"],
            first_stage["allowed_tools"],
        )
        self.assertTrue(first_stage["completion_contract"])
        self.assertEqual(4, first_stage["max_model_turns"])
        self.assertEqual(6, first_stage["max_tool_calls"])
        self.assertIn(
            "synthetic_anchor",
            {item.get("matched_anchor_id") for item in run["evidence_ledger"]["items"]},
        )
        self.assertEqual("42", run["research_answer"]["fields"]["answer"])

    def test_paradigm_harness_reports_per_run_model_usage(self) -> None:
        dataset = _synthetic_dataset()

        run = ParadigmDrivenHarness(_UsageExactFactHarnessModel()).run_case(
            dataset,
            dataset.cases[0],
        )

        diagnostics = run["diagnostics"]
        self.assertEqual(8, diagnostics["model_call_count"])
        self.assertEqual(80, diagnostics["prompt_tokens"])
        self.assertEqual(32, diagnostics["completion_tokens"])
        self.assertEqual(112, diagnostics["total_tokens"])
        self.assertGreaterEqual(diagnostics["model_latency_ms"], 0)
        self.assertGreaterEqual(diagnostics["duration_ms"], diagnostics["model_latency_ms"])

    def test_all_committed_golden_cases_run_through_semantic_stage_plans(self) -> None:
        dataset = load_dataset("research/golden-data/manifest.yaml")
        dataset = replace(
            dataset,
            reading_models_by_paper_id={
                paper_id: json.loads(
                    Path(
                        "data/golden/transformer-bert-gpt/reading-models",
                        f"{paper_id}.reading-model.json",
                    ).read_text(encoding="utf-8")
                )
                for paper_id in dataset.paper_records_by_id
            },
        )
        scorer = BehaviorScorer()

        for case in dataset.cases:
            with self.subTest(case_id=case["id"]):
                run = LiveResearchChatHarness(_GoldenCaseStageModel(dataset, case)).run_case(dataset, case)
                score = scorer.score_case(dataset, case, run)

                self.assertTrue(score.hard_pass, score.to_dict())
                paradigm = run["intent_frame"]["primary_paradigm"]
                self.assertEqual(case.get("paradigm"), paradigm)
                if case_expect(case).get("outcome") != "needs_clarification":
                    self.assertEqual(
                        [stage.name for stage in PARADIGM_DEFINITIONS[paradigm].stages],
                        [stage["stage_name"] for stage in run["stage_trace"]],
                    )

    def test_all_twenty_two_plans_execute_through_the_generic_runtime(self) -> None:
        dataset = _synthetic_dataset()
        base_case = dataset.cases[0]
        contracts = load_artifact_contracts("research/golden-data/artifact-contracts.yaml")
        reasoning_contract = next(
            child_map(item)
            for item in as_list(contracts.get("artifacts"))
            if child_map(item).get("id") == "ReasoningArtifact"
        )
        allowed_artifact_types = set(as_list(reasoning_contract.get("allowed_types")))

        for paradigm_id, definition in PARADIGM_DEFINITIONS.items():
            with self.subTest(paradigm=paradigm_id):
                case = json.loads(json.dumps(base_case))
                case["id"] = f"synthetic_{paradigm_id}"
                case["paradigm"] = paradigm_id
                case["expect"]["outcome"] = (
                    "needs_clarification" if paradigm_id == "ambiguity_resolution" else "answered"
                )

                run = ParadigmDrivenHarness(
                    _GenericParadigmStageModel(paradigm_id, definition.default_answer_shape)
                ).run_case(dataset, case)

                expected_status = (
                    "NEEDS_CLARIFICATION"
                    if paradigm_id == "ambiguity_resolution" else "COMPLETED"
                )
                self.assertEqual(expected_status, run["status"], run)
                self.assertEqual(paradigm_id, run["intent_frame"]["primary_paradigm"])
                self.assertEqual(definition.default_answer_shape, run["research_answer"]["answer_type"])
                self.assertIn(run["reasoning_artifacts"][0]["type"], allowed_artifact_types)
                self.assertEqual(
                    [stage.name for stage in definition.stages],
                    [stage["stage_name"] for stage in run["stage_trace"]],
                )


class _IntentOnlyModel(ChatModel):
    def __init__(self):
        self.call_count = 0

    def complete(self, messages, tools, max_tokens):
        self.call_count += 1
        return ChatTurn(content=json.dumps({
            "primary_paradigm": "deep_comparison",
            "normalized_goal": "Compare the selected papers.",
            "answer_shape": "comparison_matrix",
            "paper_references": [],
            "requested_aspects": [],
            "constraints": {},
            "ambiguity": {"status": "none"},
            "confidence": 0.97,
        }))


class _ConflictedAmbiguityIntentModel(ChatModel):
    def complete(self, messages, tools, max_tokens):
        return ChatTurn(content=json.dumps({
            "primary_paradigm": "precision_fact_extraction",
            "normalized_goal": "Extract a paper conclusion.",
            "answer_shape": "exact_fact",
            "paper_references": [],
            "requested_aspects": ["conclusion"],
            "constraints": {},
            "ambiguity": {
                "status": "ambiguous",
                "candidates": ["AgentBench", "AgentBoard"],
                "clarification_question": "Which benchmark paper do you mean?",
            },
            "confidence": 0.8,
        }))


class _EvidenceStageModel(ChatModel):
    def complete(self, messages, tools, max_tokens):
        tool_messages = [message for message in messages if message.get("role") == "tool"]
        if not tool_messages:
            return ChatTurn(content="", tool_calls=[ToolCall(
                id="call_search",
                name="find_reading_locations",
                arguments={"query_text": "structured value forty two", "paper_ids": ["synthetic_paper"]},
            )])
        if tool_messages[-1].get("name") == "find_reading_locations":
            return _read_disclosed_locations(tool_messages, "call_read")
        evidence_id = _evidence_ids(tool_messages)[0]
        return ChatTurn(content=json.dumps({
            "status": "completed",
            "decision_summary": "Located the exact source span.",
            "accepted_evidence_ids": [evidence_id],
        }))


class _UnobservedEvidenceThenToolModel(ChatModel):
    def __init__(self):
        self.call_count = 0

    def complete(self, messages, tools, max_tokens):
        self.call_count += 1
        tool_messages = [message for message in messages if message.get("role") == "tool"]
        if self.call_count == 1:
            return ChatTurn(content=json.dumps({
                "status": "completed",
                "decision_summary": "Claimed evidence without observing it.",
                "accepted_evidence_ids": ["invented_evidence"],
            }))
        if not tool_messages:
            return ChatTurn(content="", tool_calls=[ToolCall(
                id="call_search_after_repair",
                name="find_reading_locations",
                arguments={"query_text": "structured value forty two", "paper_ids": ["synthetic_paper"]},
            )])
        if tool_messages[-1].get("name") == "find_reading_locations":
            return _read_disclosed_locations(tool_messages, "call_read_after_repair")
        evidence_id = _evidence_ids(tool_messages)[0]
        return ChatTurn(content=json.dumps({
            "status": "completed",
            "decision_summary": "Accepted observed evidence after repair.",
            "accepted_evidence_ids": [evidence_id],
        }))


class _PrematureEvidenceAbstentionThenToolModel(ChatModel):
    def __init__(self):
        self.call_count = 0

    def complete(self, messages, tools, max_tokens):
        self.call_count += 1
        tool_messages = [message for message in messages if message.get("role") == "tool"]
        if self.call_count == 1:
            return ChatTurn(content=json.dumps({
                "status": "incomplete_precise",
                "decision_summary": "Evidence still needs to be searched.",
                "missing_obligations": [{"reason": "search_not_attempted"}],
            }))
        if not tool_messages:
            return ChatTurn(content="", tool_calls=[ToolCall(
                id="call_search_after_abstention",
                name="find_reading_locations",
                arguments={"query_text": "structured value forty two", "paper_ids": ["synthetic_paper"]},
            )])
        if tool_messages[-1].get("name") == "find_reading_locations":
            return _read_disclosed_locations(tool_messages, "call_read_after_abstention")
        evidence_id = _evidence_ids(tool_messages)[0]
        return ChatTurn(content=json.dumps({
            "status": "completed",
            "decision_summary": "Accepted observed evidence after attempting retrieval.",
            "accepted_evidence_ids": [evidence_id],
        }))


class _PrematureStopThenCompleteModel(ChatModel):
    def __init__(self):
        self.call_count = 0

    def complete(self, messages, tools, max_tokens):
        self.call_count += 1
        if self.call_count == 1:
            return ChatTurn(content=json.dumps({
                "status": "needs_clarification",
                "decision_summary": "Values need to be retrieved by the next stage.",
                "claims": [{
                    "claim_id": "premature_claim",
                    "text": "The answer is probably in the paper.",
                    "status": "supported",
                    "supporting_evidence_ids": [],
                }],
                "answer": {"status": "NEEDS_CLARIFICATION"},
                "missing_obligations": [{"reason": "downstream_retrieval_pending"}],
            }))
        return ChatTurn(content=json.dumps({
            "status": "completed",
            "decision_summary": "Defined the requested fact slot.",
            "state_values": {"fact_slots": ["answer"]},
            "claims": [{
                "claim_id": "still_premature",
                "text": "This state stage should not publish a content claim.",
                "status": "supported",
                "supporting_evidence_ids": [],
            }],
            "answer": {"status": "COMPLETED", "summary": "Too early."},
        }))


class _ExplicitNoMatchModel(ChatModel):
    def complete(self, messages, tools, max_tokens):
        tool_messages = [message for message in messages if message.get("role") == "tool"]
        if not tool_messages:
            return ChatTurn(content="", tool_calls=[ToolCall(
                id="search_no_match",
                name="find_reading_locations",
                arguments={"query_text": "zzzznonexistent", "paper_ids": ["synthetic_paper"]},
            )])
        return ChatTurn(content=json.dumps({
            "status": "completed",
            "decision_summary": "The authorized search returned no matching evidence.",
            "state_values": {"support_found": False},
            "missing_obligations": [{"reason": "no_matching_evidence"}],
        }))


class _OutOfStageToolModel(ChatModel):
    def complete(self, messages, tools, max_tokens):
        tool_messages = [message for message in messages if message.get("role") == "tool"]
        if not tool_messages:
            return ChatTurn(content="", tool_calls=[ToolCall(
                id="call_forbidden",
                name="find_reading_locations",
                arguments={"query_text": "synthetic", "paper_ids": ["synthetic_paper"]},
            )])
        return ChatTurn(content=json.dumps({
            "status": "completed",
            "decision_summary": "Used a forbidden tool.",
        }))


class _TooManyToolCallsModel(ChatModel):
    def complete(self, messages, tools, max_tokens):
        tool_messages = [message for message in messages if message.get("role") == "tool"]
        if not tool_messages:
            return ChatTurn(content="", tool_calls=[ToolCall(
                id="call_allowed_search",
                name="find_reading_locations",
                arguments={"query_text": "structured value forty two", "paper_ids": ["synthetic_paper"]},
            )])
        if not any(message.get("name") == "read_locations" for message in tool_messages):
            location_refs = [
                str(item["location_ref"])
                for item in as_list(json.loads(tool_messages[-1]["content"]).get("locations"))
            ]
            return ChatTurn(content="", tool_calls=[
                ToolCall(
                    id="call_allowed_read",
                    name="read_locations",
                    arguments={"location_refs": location_refs},
                ),
                ToolCall(
                    id="call_over_budget",
                    name="find_reading_locations",
                    arguments={"query_text": "another search", "paper_ids": ["synthetic_paper"]},
                ),
            ])
        evidence_id = _evidence_ids(tool_messages)[0]
        return ChatTurn(content=json.dumps({
            "status": "completed",
            "decision_summary": "Accepted the first search result.",
            "accepted_evidence_ids": [evidence_id],
        }))


class _PrematureIdentityOutputModel(ChatModel):
    def complete(self, messages, tools, max_tokens):
        tool_messages = [message for message in messages if message.get("role") == "tool"]
        if not tool_messages:
            return ChatTurn(content="", tool_calls=[ToolCall(
                id="resolve_before_premature_output",
                name="find_papers_by_identity",
                arguments={"title": "Synthetic Paper"},
            )])
        return ChatTurn(content=json.dumps({
            "status": "completed",
            "decision_summary": "Resolved the target paper.",
            "selected_paper_ids": ["synthetic_paper"],
            "accepted_evidence_ids": ["made_up_metadata_evidence"],
            "claims": [{
                "claim_id": "identity_claim",
                "text": "The paper contains the answer.",
                "status": "supported",
                "supporting_evidence_ids": [],
            }],
            "answer": {"status": "COMPLETED", "summary": "Too early."},
        }))


class _CaptureStagePromptModel(ChatModel):
    def __init__(self):
        self.system_prompt = ""

    def complete(self, messages, tools, max_tokens):
        self.system_prompt = str(messages[0].get("content") or "")
        return _stage_result(selected_paper_ids=["synthetic_paper"])


class _ResolvedIdentityStopThenCompleteModel(ChatModel):
    def __init__(self):
        self.result_count = 0

    def complete(self, messages, tools, max_tokens):
        tool_messages = [message for message in messages if message.get("role") == "tool"]
        if not tool_messages:
            return ChatTurn(content="", tool_calls=[ToolCall(
                id="resolve_before_stop",
                name="find_papers_by_identity",
                arguments={"title": "Synthetic Paper"},
            )])
        self.result_count += 1
        status = "incomplete_precise" if self.result_count == 1 else "completed"
        return ChatTurn(content=json.dumps({
            "status": status,
            "decision_summary": "Resolved the source; content retrieval belongs to a later stage.",
            "selected_paper_ids": ["synthetic_paper"],
            "state_values": {"source_resolved": True},
            "missing_obligations": (
                [{"reason": "downstream_content_retrieval_pending"}]
                if self.result_count == 1 else []
            ),
        }))


class _IdentityCapabilityConfusionModel(ChatModel):
    def __init__(self):
        self.call_count = 0

    def complete(self, messages, tools, max_tokens):
        self.call_count += 1
        tool_messages = [message for message in messages if message.get("role") == "tool"]
        if self.call_count == 1:
            return ChatTurn(content=json.dumps({
                "status": "incomplete_precise",
                "decision_summary": "Cannot read paper content in this identity stage.",
                "state_values": {"entity_hint": "Synthetic Paper"},
                "missing_obligations": [{"reason": "content_tools_unavailable"}],
            }))
        if not tool_messages:
            return ChatTurn(content="", tool_calls=[ToolCall(
                id="call_identity_after_repair",
                name="find_papers_by_identity",
                arguments={"title": "Synthetic Paper"},
            )])
        return ChatTurn(content=json.dumps({
            "status": "completed",
            "decision_summary": "Resolved the synthetic paper identity.",
            "selected_paper_ids": ["synthetic_paper"],
            "state_values": {"source_resolved": True},
        }))


class _ExactFactHarnessModel(ChatModel):
    def complete(self, messages, tools, max_tokens):
        system = str(messages[0].get("content") or "")
        if "TURN_DECISION" in system:
            payload = json.loads(messages[-1]["content"])
            return ChatTurn(content="", tool_calls=[ToolCall(
                id="turn_decision_exact_fact",
                name="submit_turn_decision",
                arguments={
                    "route": "research",
                    "effective_goal": str(payload["user_message"]),
                    "task": {"verb": "extract", "object": "paper_fact"},
                    "constraints": {},
                    "primary_paradigm": "precision_fact_extraction",
                    "answer_shape": "exact_fact",
                    "assumption": "",
                    "blocking_reason": None,
                    "direct_reply": "",
                    "pending_interaction": None,
                    "paper_references": [{"text": "Synthetic Paper"}],
                    "requested_aspects": ["answer"],
                    "requires_corpus_observation": True,
                    "required_evidence_types": ["paragraph"],
                    "required_capabilities": ["evidence_retrieval"],
                    "confidence": 1.0,
                },
            )])
        if "INTENT_RECOGNITION" in system:
            return ChatTurn(content=json.dumps({
                "primary_paradigm": "precision_fact_extraction",
                "normalized_goal": "Find the exact synthetic answer.",
                "answer_shape": "exact_fact",
                "paper_references": [{"text": "Synthetic Paper"}],
                "requested_aspects": ["answer"],
                "constraints": {},
                "ambiguity": {"status": "unambiguous"},
                "confidence": 1.0,
                "required_evidence_types": ["paragraph"],
            }))

        stage_name = _stage_name(system)
        tool_messages = [message for message in messages if message.get("role") == "tool"]
        if stage_name == "resolve_target_paper":
            if not tool_messages:
                return ChatTurn(content="", tool_calls=[ToolCall(
                    id="resolve_synthetic_paper",
                    name="find_papers_by_identity",
                    arguments={"title": "Synthetic Paper"},
                )])
            return _stage_result(selected_paper_ids=["synthetic_paper"], state_values={"target": "synthetic_paper"})
        if stage_name == "define_fact_slots":
            return _stage_result(state_values={"fact_slots": ["answer"]})
        if stage_name == "locate_exact_evidence" and not tool_messages:
            return ChatTurn(content="", tool_calls=[ToolCall(
                id="call_search",
                name="find_reading_locations",
                arguments={"query_text": "structured value forty two", "paper_ids": ["synthetic_paper"]},
            )])
        if stage_name == "locate_exact_evidence":
            if tool_messages[-1].get("name") == "find_reading_locations":
                return _read_disclosed_locations(tool_messages, "call_read")
            evidence_id = _evidence_ids(tool_messages)[0]
            return _stage_result(accepted_evidence_ids=[evidence_id])
        if stage_name == "extract_exact_facts":
            request = json.loads(messages[-1]["content"])
            state = request["research_state"]
            answer_field = (request["required_answer_field_names"] or ["answer"])[0]
            evidence_id = next(
                item["evidence_id"]
                for item in state["evidence"]
                if item.get("matched_anchor_id") == "synthetic_anchor"
            )
            return _stage_result(
                claims=[{
                    "claim_id": "claim_answer",
                    "text": "The synthetic answer is 42.",
                    "status": "supported",
                    "supporting_evidence_ids": [evidence_id],
                }],
                answer={
                    "status": "ANSWERABLE",
                    "answer_type": "exact_fact",
                    "summary": "The synthetic answer is 42.",
                    "fields": {answer_field: {"type": "integer", "value": "42"}},
                    "cited_claim_ids": ["claim_answer"],
                    "cited_evidence_ids": [evidence_id],
                },
            )
        raise AssertionError(f"unexpected stage: {stage_name}")


class _UsageExactFactHarnessModel(_ExactFactHarnessModel):
    def complete(self, messages, tools, max_tokens):
        turn = super().complete(messages, tools, max_tokens)
        return replace(turn, raw={
            "usage": {
                "prompt_tokens": 10,
                "completion_tokens": 4,
                "total_tokens": 14,
            },
        })


class _RequiredStageResultModel(ChatModel):
    def __init__(self) -> None:
        self.required_tool_calls = 0

    def complete(self, messages, tools, max_tokens):
        return ChatTurn(content="not structured")

    def complete_required_tool(self, messages, tools, required_tool_name, max_tokens):
        self.required_tool_calls += 1
        arguments = {}
        if self.required_tool_calls > 1:
            arguments = {
                "status": "completed",
                "decision_summary": "Returned the required structured result.",
                "claims": [],
                "answer": {
                    "status": "COMPLETED",
                    "answer_type": "constraint_filter",
                    "summary": "Synthetic Paper is recommended.",
                    "cited_claim_ids": [],
                    "cited_evidence_ids": [],
                },
            }
        return ChatTurn(content="", tool_calls=[ToolCall(
            id="submit_stage_result",
            name="submit_stage_result",
            arguments=arguments,
        )])


class _RequiredEvidenceSequenceModel(ChatModel):
    def __init__(self) -> None:
        self.required_tool_names: list[str] = []
        self.available_tool_names: list[set[str]] = []

    def complete(self, messages, tools, max_tokens):
        return _stage_result(
            status="incomplete_precise",
            missing_obligations=[{"reason": "tools_not_called"}],
        )

    def complete_required_tool(self, messages, tools, required_tool_name, max_tokens):
        name = required_tool_name
        self.required_tool_names.append(name)
        self.available_tool_names.append({tool["function"]["name"] for tool in tools})
        tool_messages = [message for message in messages if message.get("role") == "tool"]
        if name == "find_reading_locations":
            arguments = {
                "query_text": "structured value forty two",
                "paper_ids": ["synthetic_paper"],
                "top_k": 1,
            }
        elif name == "read_locations":
            locations = json.loads(tool_messages[-1]["content"])["locations"]
            arguments = {"location_refs": [item["location_ref"] for item in locations]}
        else:
            evidence_ids = _evidence_ids(tool_messages)
            arguments = {
                "status": "completed",
                "decision_summary": "Found and read the required evidence.",
                "accepted_evidence_ids": evidence_ids,
            }
        return ChatTurn(content="", tool_calls=[ToolCall(
            id=f"required_{name}",
            name=name,
            arguments=arguments,
        )])


class _ConversationAuthorizedComparisonModel(ChatModel):
    def complete(self, messages, tools, max_tokens):
        stage_name = _stage_name(str(messages[0].get("content") or ""))
        tool_messages = [message for message in messages if message.get("role") == "tool"]
        if stage_name == "resolve_comparison_targets":
            return _stage_result(selected_paper_ids=["synthetic_paper"])
        if stage_name == "establish_comparison_axes":
            return _stage_result(state_values={"axes": ["answer"]})
        if stage_name == "retrieve_evidence_per_axis" and not tool_messages:
            return ChatTurn(content="", tool_calls=[ToolCall(
                id="find_follow_up_evidence",
                name="find_reading_locations",
                arguments={
                    "query_text": "structured value forty two",
                    "paper_ids": ["synthetic_paper"],
                    "top_k": 1,
                },
            )])
        if stage_name == "retrieve_evidence_per_axis":
            if tool_messages[-1].get("name") == "find_reading_locations":
                payload = json.loads(tool_messages[-1]["content"])
                if payload.get("error"):
                    return _stage_result(
                        status="incomplete_precise",
                        missing_obligations=[{"reason": payload["error"]}],
                    )
                return _read_disclosed_locations(tool_messages, "read_follow_up_evidence")
            return _stage_result(accepted_evidence_ids=_evidence_ids(tool_messages))
        if stage_name == "synthesize_comparison":
            state = json.loads(messages[-1]["content"])["research_state"]
            evidence_id = state["evidence"][0]["evidence_id"]
            return _stage_result(
                claims=[{
                    "claim_id": "follow_up_claim",
                    "text": "The synthetic answer is 42.",
                    "status": "supported",
                    "supporting_evidence_ids": [evidence_id],
                }],
                answer={
                    "status": "COMPLETED",
                    "answer_type": "comparison_matrix",
                    "summary": "The selected paper reports 42.",
                    "cited_claim_ids": ["follow_up_claim"],
                    "cited_evidence_ids": [evidence_id],
                },
            )
        raise AssertionError(f"unexpected stage: {stage_name}")


class _GoldenCaseStageModel(ChatModel):
    def __init__(self, dataset: GoldenDataset, case: dict):
        self.dataset = dataset
        self.case = case
        self.required_anchor_ids = [
            str(item)
            for item in as_list(child_map(case_expect(case).get("evidence")).get("required"))
        ]

    def complete(self, messages, tools, max_tokens):
        system = str(messages[0].get("content") or "")
        if "TURN_DECISION" in system:
            expectation = case_expect(self.case)
            outcome = str(expectation.get("outcome"))
            paradigm = str(self.case.get("paradigm"))
            definition = PARADIGM_DEFINITIONS[paradigm]
            if outcome == "needs_clarification":
                arguments = {
                    "route": "clarify",
                    "effective_goal": case_question(self.case),
                    "task": {"verb": "clarify", "object": "paper_identity"},
                    "constraints": {},
                    "primary_paradigm": "ambiguity_resolution",
                    "answer_shape": "ambiguity_clarification",
                    "assumption": "",
                    "blocking_reason": "ambiguous_paper_identity",
                    "direct_reply": "",
                    "pending_interaction": {
                        "interaction_id": f"choice_{self.case['id']}",
                        "kind": "free_text",
                        "question": "Which paper do you mean?",
                        "options": [],
                    },
                    "paper_references": [],
                    "requested_aspects": [],
                    "required_evidence_types": [],
                    "required_capabilities": [],
                    "requires_corpus_observation": False,
                    "confidence": 1.0,
                }
            else:
                arguments = {
                    "route": "research",
                    "effective_goal": " ".join(
                        str(message.get("content") or "")
                        for message in case_messages(self.case)
                        if message.get("role") == "user"
                    ),
                    "task": {"verb": "research", "object": "papers"},
                    "constraints": {},
                    "primary_paradigm": paradigm,
                    "answer_shape": definition.default_answer_shape,
                    "assumption": "",
                    "blocking_reason": None,
                    "direct_reply": "",
                    "pending_interaction": None,
                    "paper_references": [],
                    "requested_aspects": [],
                    "required_evidence_types": [],
                    "required_capabilities": [paradigm],
                    "requires_corpus_observation": True,
                    "confidence": 1.0,
                }
            return ChatTurn(content="", tool_calls=[ToolCall(
                id=f"turn_{self.case['id']}",
                name="submit_turn_decision",
                arguments=arguments,
            )])
        return self._complete_research_turn(messages, tools, max_tokens)

    def _complete_research_turn(self, messages, tools, max_tokens):
        system = str(messages[0].get("content") or "")
        if "INTENT_RECOGNITION" in system:
            return ChatTurn(content=json.dumps(self._intent_payload()))
        stage_name = _stage_name(system)
        tool_messages = [message for message in messages if message.get("role") == "tool"]

        if stage_name in {
            "resolve_target_paper",
            "resolve_method_source",
            "resolve_comparison_targets",
            "resolve_lineage_nodes",
            "resolve_hop_entities",
            "resolve_conflict_sources",
        }:
            if not tool_messages:
                return ChatTurn(content="", tool_calls=[
                    ToolCall(
                        id=f"resolve_{paper_id}",
                        name="find_papers_by_identity",
                        arguments={"paper_id": paper_id},
                    )
                    for paper_id in self._required_paper_ids()
                ])
            return _stage_result(selected_paper_ids=self._required_paper_ids())
        if stage_name == "search_available_support":
            if not tool_messages:
                return ChatTurn(content="", tool_calls=[ToolCall(
                    id="find_missing_target",
                    name="find_papers_by_identity",
                    arguments={"title": "GPT-5"},
                )])
            return _stage_result(
                state_values={"support_found": False},
                missing_obligations=[{
                    "stage": stage_name,
                    "reason": "No verified GPT-5 architecture paper is present in the corpus.",
                }],
            )
        if stage_name in {
            "locate_exact_evidence",
            "retrieve_procedure_and_parameters",
            "retrieve_evidence_per_axis",
            "trace_relationship_edges",
            "retrieve_evidence_per_hop",
            "retrieve_each_side",
        }:
            return self._evidence_stage(stage_name, tool_messages)
        if stage_name == "formulate_clarification":
            return ChatTurn(content=json.dumps({
                "status": "needs_clarification",
                "decision_summary": "The phrase names multiple plausible paper families.",
                "state_values": {
                    "options": ["Attention Is All You Need", "earlier neural attention papers"],
                },
                "answer": {
                    "status": "NEEDS_CLARIFICATION",
                    "answer_type": "ambiguity_clarification",
                    "summary": "Which attention paper do you mean: Attention Is All You Need or an earlier neural attention paper?",
                    "fields": {
                        "options": ["Attention Is All You Need", "earlier neural attention papers"],
                    },
                    "sections": [],
                    "cited_claim_ids": [],
                    "cited_evidence_ids": [],
                },
            }))
        if stage_name == "report_known_and_unknown":
            return _stage_result(
                status="incomplete_precise",
                claims=[{
                    "claim_id": "claim_1",
                    "text": "The available corpus does not verify GPT-5 architecture details.",
                    "status": "underdetermined",
                    "supporting_evidence_ids": [],
                }],
                answer={
                    "status": "INCOMPLETE_PRECISE",
                    "answer_type": "uncertainty_boundary",
                    "summary": "The available corpus does not support GPT-5 architecture details.",
                    "sections": [
                        {"id": "what_is_known", "text": "The corpus contains earlier GPT work."},
                        {"id": "what_is_not_supported", "text": "GPT-5 architecture details are not verified."},
                        {"id": "missing_evidence", "text": "No verified GPT-5 architecture paper is present."},
                    ],
                    "fields": {},
                    "cited_claim_ids": ["claim_1"],
                    "cited_evidence_ids": [],
                },
            )
        if stage_name in {
            "extract_exact_facts",
            "build_reproduction_checklist",
            "synthesize_comparison",
            "render_genealogy",
            "link_supported_hops",
            "present_conflict_resolution",
        }:
            return self._answer_stage(stage_name, messages)
        return _stage_result(state_values={stage_name: "completed"})

    def _intent_payload(self) -> dict:
        paradigm = str(self.case.get("paradigm"))
        ambiguity_status = "ambiguous" if paradigm == "ambiguity_resolution" else "unambiguous"
        ambiguity = {"status": ambiguity_status}
        if paradigm == "ambiguity_resolution":
            ambiguity.update({
                "candidates": ["Attention Is All You Need", "earlier neural attention papers"],
                "clarification_question": "Which attention paper do you mean?",
            })
        return {
            "primary_paradigm": paradigm,
            "normalized_goal": case_question(self.case),
            "answer_shape": PARADIGM_DEFINITIONS[paradigm].default_answer_shape,
            "paper_references": [],
            "requested_aspects": [],
            "constraints": {},
            "ambiguity": ambiguity,
            "confidence": 1.0,
            "required_evidence_types": [],
            "required_capabilities": [paradigm],
        }

    def _evidence_stage(self, stage_name: str, tool_messages: list[dict]) -> ChatTurn:
        if not tool_messages:
            calls = []
            if stage_name in {"trace_relationship_edges", "retrieve_evidence_per_hop"}:
                calls.append(ToolCall(
                    id="citation_edges",
                    name="get_citation_edges",
                    arguments={"paper_id": self._required_paper_ids()[0]},
                ))
            for index, anchor_id in enumerate(self.required_anchor_ids):
                anchor = child_map(self.dataset.anchors_by_id[anchor_id])
                parser = child_map(anchor.get("parser_evidence"))
                selector = child_map(anchor.get("selector"))
                query = str(parser.get("matched_text") or selector.get("exact_text") or anchor_id)
                calls.append(ToolCall(
                    id=f"search_{index}",
                    name="find_reading_locations",
                    arguments={
                        "query_text": query,
                        "paper_ids": [str(anchor.get("paper_id"))],
                        "top_k": 5,
                    },
                ))
            return ChatTurn(content="", tool_calls=calls)
        if not any(message.get("name") == "read_locations" for message in tool_messages):
            return _read_disclosed_locations(tool_messages, f"read_{stage_name}")
        accepted = []
        for message in tool_messages:
            payload = json.loads(message["content"])
            for item in as_list(payload.get("items")):
                if child_map(item).get("matched_anchor_id") in self.required_anchor_ids:
                    accepted.append(str(child_map(item).get("evidence_id")))
        return _stage_result(accepted_evidence_ids=sorted(set(accepted)))

    def _answer_stage(self, stage_name: str, messages: list[dict]) -> ChatTurn:
        state = json.loads(messages[-1]["content"])["research_state"]
        evidence_by_anchor = {
            item.get("matched_anchor_id"): item.get("evidence_id")
            for item in as_list(state.get("evidence"))
            if item.get("matched_anchor_id")
        }
        claims = []
        cited_evidence = []
        expectation = case_expect(self.case)
        for index, raw_claim in enumerate(as_list(expectation.get("claims")), start=1):
            claim = child_map(raw_claim)
            support = [
                evidence_by_anchor[anchor_id]
                for anchor_id in as_list(claim.get("evidence"))
                if anchor_id in evidence_by_anchor
            ]
            claims.append({
                "claim_id": f"claim_{index}",
                "text": claim.get("text"),
                "status": "supported",
                "supporting_evidence_ids": support,
                "refuting_evidence_ids": [],
            })
            cited_evidence.extend(support)
        fields = dict(child_map(expectation.get("facts")))
        if not claims and fields:
            support = list(evidence_by_anchor.values())
            for index, (key, value) in enumerate(fields.items(), start=1):
                claims.append({
                    "claim_id": f"claim_{index}",
                    "text": f"{key}: {value}",
                    "status": "supported",
                    "supporting_evidence_ids": support,
                    "refuting_evidence_ids": [],
                })
            cited_evidence.extend(support)
        answer_type = PARADIGM_DEFINITIONS[str(self.case.get("paradigm"))].default_answer_shape
        return _stage_result(
            claims=claims,
            answer={
                "status": "COMPLETED",
                "answer_type": answer_type,
                "summary": " ".join(str(claim.get("text") or "") for claim in as_list(expectation.get("claims"))),
                "fields": fields,
                "sections": [],
                "cited_claim_ids": [str(claim.get("claim_id")) for claim in claims],
                "cited_evidence_ids": sorted(set(cited_evidence)),
            },
        )

    def _required_paper_ids(self) -> list[str]:
        return [str(item) for item in as_list(child_map(case_expect(self.case).get("papers")).get("required"))]


class _GenericParadigmStageModel(ChatModel):
    def __init__(self, paradigm_id: str, answer_shape: str):
        self.paradigm_id = paradigm_id
        self.answer_shape = answer_shape

    def complete_required_tool(self, messages, tools, required_tool_name, max_tokens):
        name = required_tool_name
        tool_messages = [message for message in messages if message.get("role") == "tool"]
        if name == "find_reading_locations":
            return ChatTurn(content="", tool_calls=[ToolCall(
                id="required_generic_search",
                name=name,
                arguments={
                    "query_text": "structured value forty two",
                    "paper_ids": ["synthetic_paper"],
                },
            )])
        if name == "read_locations":
            return _read_disclosed_locations(tool_messages, "required_generic_read")
        if name == "submit_stage_result":
            request = next(
                json.loads(message["content"])
                for message in messages
                if message.get("role") == "user"
                and str(message.get("content") or "").lstrip().startswith("{")
            )
            system = str(messages[0].get("content") or "")
            if "Produce evidence-linked claims and the final answer draft" in system:
                arguments = json.loads(self._answer_turn(request).content)
            elif any(message.get("name") == "read_locations" for message in tool_messages):
                arguments = {
                    "status": "completed",
                    "decision_summary": "Submitted the evidence stage result.",
                    "selected_paper_ids": ["synthetic_paper"],
                    "accepted_evidence_ids": sorted(set(_evidence_ids(tool_messages))),
                    "state_values": {"evidence_collected": True},
                }
            else:
                return self.complete(messages, tools, max_tokens)
            return ChatTurn(content="", tool_calls=[ToolCall(
                id="required_generic_submission",
                name=name,
                arguments=arguments,
            )])
        return self.complete(messages, tools, max_tokens)

    def complete(self, messages, tools, max_tokens):
        system = str(messages[0].get("content") or "")
        if "INTENT_RECOGNITION" in system:
            return ChatTurn(content=json.dumps({
                "primary_paradigm": self.paradigm_id,
                "normalized_goal": "Exercise the configured semantic plan.",
                "answer_shape": self.answer_shape,
                "paper_references": [{"paper_id": "synthetic_paper"}],
                "requested_aspects": ["answer"],
                "constraints": {},
                "ambiguity": {
                    "status": (
                        "ambiguous" if self.paradigm_id == "ambiguity_resolution" else "unambiguous"
                    )
                },
                "confidence": 1.0,
                "required_evidence_types": ["paragraph"],
            }))

        request = json.loads(messages[-1]["content"])
        tool_messages = [message for message in messages if message.get("role") == "tool"]
        tool_names = {
            child_map(tool.get("function")).get("name")
            for tool in tools
        }
        if "Produce evidence-linked claims and the final answer draft" in system:
            return self._answer_turn(request)
        if "find_reading_locations" in tool_names:
            if not tool_messages:
                if "search_paper_candidates" in tool_names:
                    return ChatTurn(content="", tool_calls=[ToolCall(
                        id="call_generic_candidates",
                        name="search_paper_candidates",
                        arguments={"query_text": "synthetic", "limit": 1},
                    )])
                return ChatTurn(content="", tool_calls=[ToolCall(
                    id="call_generic_search",
                    name="find_reading_locations",
                    arguments={
                        "query_text": "structured value forty two",
                        "paper_ids": ["synthetic_paper"],
                    },
                )])
            if not any(message.get("name") == "find_reading_locations" for message in tool_messages):
                return ChatTurn(content="", tool_calls=[ToolCall(
                    id="call_generic_search",
                    name="find_reading_locations",
                    arguments={
                        "query_text": "structured value forty two",
                        "paper_ids": ["synthetic_paper"],
                    },
                )])
            if not any(message.get("name") == "read_locations" for message in tool_messages):
                return _read_disclosed_locations(tool_messages, "call_generic_read")
            evidence_ids = _evidence_ids(tool_messages)
            return _stage_result(
                selected_paper_ids=["synthetic_paper"],
                accepted_evidence_ids=sorted(set(evidence_ids)),
                state_values={"evidence_collected": True},
            )
        if tool_names:
            if not tool_messages:
                return ChatTurn(content="", tool_calls=[ToolCall(
                    id="call_generic_identity",
                    name="find_papers_by_identity",
                    arguments={"paper_id": "synthetic_paper"},
                )])
            return _stage_result(
                selected_paper_ids=["synthetic_paper"],
                state_values={"identity_resolved": True},
            )
        return _stage_result(state_values={"semantic_stage_completed": True})

    def _answer_turn(self, request: dict) -> ChatTurn:
        if self.paradigm_id == "ambiguity_resolution":
            return ChatTurn(content=json.dumps({
                "status": "needs_clarification",
                "decision_summary": "Produced the paradigm-specific clarification artifact.",
                "claims": [],
                "answer": {
                    "status": "NEEDS_CLARIFICATION",
                    "answer_type": self.answer_shape,
                    "summary": "Choose an interpretation.",
                    "fields": {"answer": "42"},
                    "cited_claim_ids": [],
                    "cited_evidence_ids": [],
                },
            }))
        evidence_id = next(
            item["evidence_id"]
            for item in request["research_state"]["evidence"]
            if item.get("matched_anchor_id") == "synthetic_anchor"
        )
        return ChatTurn(content=json.dumps({
            "status": "completed",
            "decision_summary": "Produced the paradigm-specific terminal artifact.",
            "claims": [{
                "claim_id": "claim_answer",
                "text": "The synthetic answer is 42.",
                "status": "supported",
                "supporting_evidence_ids": [evidence_id],
            }],
            "answer": {
                "status": "COMPLETED",
                "answer_type": self.answer_shape,
                "summary": "The synthetic answer is 42.",
                "fields": {"answer": "42"},
                "cited_claim_ids": ["claim_answer"],
                "cited_evidence_ids": [evidence_id],
            },
        }))


class _NonterminalUnparseableContextModel(_GenericParadigmStageModel):
    def __init__(self, paradigm_id: str, answer_shape: str) -> None:
        super().__init__(paradigm_id, answer_shape)
        self.context_attempts = 0

    def complete(self, messages, tools, max_tokens):
        system = str(messages[0].get("content") or "")
        if "SEMANTIC_STAGE:establish_comparison_axes" in system and self.context_attempts < 5:
            self.context_attempts += 1
            return ChatTurn(content="not a StageResult")
        return super().complete(messages, tools, max_tokens)


class _CandidateOverflowModel(ChatModel):
    def complete(self, messages, tools, max_tokens):
        tool_messages = [message for message in messages if message.get("role") == "tool"]
        if not tool_messages:
            return ChatTurn(content="", tool_calls=[ToolCall(
                id="browse_candidates",
                name="search_paper_candidates",
                arguments={"limit": 9},
            )])
        candidates = json.loads(tool_messages[-1]["content"])["candidates"]
        return _stage_result(selected_paper_ids=[item["paper_id"] for item in candidates])


class _UnsupportedClaimThenRepairModel(ChatModel):
    def __init__(self) -> None:
        self.call_count = 0

    def complete(self, messages, tools, max_tokens):
        self.call_count += 1
        status = "supported" if self.call_count == 1 else "underdetermined"
        return _stage_result(
            claims=[{
                "claim_id": "recommendation_claim",
                "text": "Synthetic Paper is recommended.",
                "status": status,
                "supporting_evidence_ids": [],
            }],
            answer={
                "status": "COMPLETED",
                "answer_type": "constraint_filter",
                "summary": "Synthetic Paper is a candidate.",
                "cited_claim_ids": ["recommendation_claim"],
                "cited_evidence_ids": [],
            },
        )


class _NoToolThenAcceptEvidenceModel(ChatModel):
    def complete(self, messages, tools, max_tokens):
        tool_messages = [message for message in messages if message.get("role") == "tool"]
        if not tool_messages:
            return _stage_result(accepted_evidence_ids=[])
        evidence_id = json.loads(tool_messages[-1]["content"])["items"][0]["evidence_id"]
        return _stage_result(accepted_evidence_ids=[evidence_id])


class _NoToolThenAcceptCandidatesModel(ChatModel):
    def complete(self, messages, tools, max_tokens):
        tool_messages = [message for message in messages if message.get("role") == "tool"]
        if not tool_messages:
            return _stage_result(selected_paper_ids=[])
        paper_id = json.loads(tool_messages[-1]["content"])["candidates"][0]["paper_id"]
        return _stage_result(selected_paper_ids=[paper_id])


class _TruncatedEvidenceResultModel(ChatModel):
    def complete(self, messages, tools, max_tokens):
        tool_messages = [message for message in messages if message.get("role") == "tool"]
        if not tool_messages:
            return ChatTurn(content="", tool_calls=[ToolCall(
                id="search_evidence",
                name="find_reading_locations",
                arguments={"query_text": "structured value", "paper_ids": ["synthetic_paper"]},
            )])
        if tool_messages[-1].get("name") == "find_reading_locations":
            return _read_disclosed_locations(tool_messages, "read_evidence")
        return ChatTurn(content="{\"status\": \"completed\"")


class _SoftMissingAnswerModel(ChatModel):
    def complete(self, messages, tools, max_tokens):
        return ChatTurn(content=json.dumps({
            "status": "incomplete_precise",
            "decision_summary": "The exact preference is unknown, so use a stated assumption.",
            "claims": [{
                "claim_id": "preference_gap",
                "text": "The user did not state a subtopic preference.",
                "status": "underdetermined",
                "supporting_evidence_ids": [],
            }],
            "missing_obligations": [{"reason": "preference_not_specified"}],
            "answer": {
                "status": "INCOMPLETE_PRECISE",
                "answer_type": "constraint_filter",
                "summary": "Using a broad coverage assumption.",
                "markdown": "Using a broad coverage assumption.",
                "cited_claim_ids": ["preference_gap"],
                "cited_evidence_ids": [],
            },
        }))


class _CompletedBoundaryAnswerModel(ChatModel):
    def complete(self, messages, tools, max_tokens):
        return _stage_result(
            claims=[{
                "claim_id": "boundary_claim",
                "text": "The available evidence does not prove the claim.",
                "status": "underdetermined",
                "supporting_evidence_ids": [],
            }],
            answer={
                "status": "INCOMPLETE_PRECISE",
                "answer_type": "uncertainty_boundary",
                "summary": "The conclusion remains uncertain.",
                "markdown": "The conclusion remains uncertain.",
                "cited_claim_ids": ["boundary_claim"],
                "cited_evidence_ids": [],
            },
        )


def _stage_result(**values) -> ChatTurn:
    return ChatTurn(content=json.dumps({
        "status": "completed",
        "decision_summary": "Stage completed.",
        **values,
    }))


def _stage_name(system: str) -> str:
    marker = "SEMANTIC_STAGE:"
    return system.split(marker, 1)[1].splitlines()[0].strip()


def _read_disclosed_locations(tool_messages: list[dict], call_id: str) -> ChatTurn:
    location_refs = []
    for message in tool_messages:
        payload = json.loads(message["content"])
        location_refs.extend(
            str(item["location_ref"])
            for item in as_list(payload.get("locations"))
            if child_map(item).get("location_ref")
        )
    return ChatTurn(content="", tool_calls=[ToolCall(
        id=call_id,
        name="read_locations",
        arguments={"location_refs": sorted(set(location_refs))},
    )])


def _evidence_ids(tool_messages: list[dict]) -> list[str]:
    return [
        str(item["evidence_id"])
        for message in tool_messages
        for item in as_list(json.loads(message["content"]).get("items"))
        if child_map(item).get("evidence_id")
    ]


def _selected_state(turn_id: str = "turn_1") -> ResearchState:
    state = ResearchState.new(turn_id)
    state.selected_paper_ids = ["synthetic_paper"]
    return state


def _synthetic_dataset() -> GoldenDataset:
    paper = {
        "paper_id": "synthetic_paper",
        "identity": {"title": "Synthetic Paper", "year": 2026, "version_label": "fixture"},
    }
    anchor = {
        "anchor_id": "synthetic_anchor",
        "paper_id": "synthetic_paper",
        "element": {"type": "paragraph", "page": 1, "section": "Result"},
        "parser_evidence": {
            "verification_status": "verified",
            "matched_text": "structured value forty two",
            "page": 1,
        },
    }
    case = {
        "schema_version": "harness-golden-case/v2",
        "id": "synthetic_case",
        "paradigm": "precision_fact_extraction",
        "paper_pack": "synthetic_pack",
        "messages": [{"role": "user", "content": "What is the synthetic answer?"}],
        "expect": {
            "outcome": "answered",
            "papers": {"required": ["synthetic_paper"]},
            "evidence": {"required": ["synthetic_anchor"]},
            "facts": {"answer": "42"},
            "citations": "required",
        },
    }
    return GoldenDataset(
        root=Path("."),
        manifest_path=Path("synthetic.yaml"),
        manifest=yaml.safe_load("dataset_id: synthetic\n"),
        paper_packs=[{
            "schema_version": "harness-paper-pack/v2",
            "id": "synthetic_pack",
            "papers": [{"id": "synthetic_paper", "title": "Synthetic Paper", "year": 2026}],
            "anchors": [{
                "id": "synthetic_anchor",
                "paper": "synthetic_paper",
                "page": 1,
                "section": "Result",
                "quote": "structured value forty two",
            }],
        }],
        cases=[case],
        paper_records_by_id={"synthetic_paper": paper},
        anchors_by_id={"synthetic_anchor": anchor},
        citation_edges=[],
        reading_models_by_paper_id={
            "synthetic_paper": {
                "paper_id": "synthetic_paper",
                "reading_elements": [{
                    "readingElementId": "el_1",
                    "locationRef": "loc_1",
                    "elementType": "paragraph",
                    "pageNumber": 1,
                    "sectionTitle": "Result",
                    "searchableText": "The structured value forty two is the answer.",
                }],
            }
        },
    )


if __name__ == "__main__":
    unittest.main()
