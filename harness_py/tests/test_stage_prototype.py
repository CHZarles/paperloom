from __future__ import annotations

import json
import unittest
from pathlib import Path

import yaml

from harness_py.agent_harness import ResearchAgentHarness
from harness_py.llm import ChatModel, ChatTurn, ToolCall
from harness_py.models import GoldenDataset
from harness_py.stage_prototype.intent import TurnInterpreter, _normalize_turn_decision
from harness_py.stage_prototype.models import (
    ExecutionStatus,
    Obligation,
    ResearchOutcome,
    TurnDecision,
    TurnFrame,
)
from harness_py.stage_prototype.plans import PARADIGM_RECIPES
from harness_py.tools import ReadingCorpusTools


class EvidenceBoundedHarnessTest(unittest.TestCase):
    def test_all_twenty_two_paradigms_are_explicit_recipes(self) -> None:
        self.assertEqual(22, len(PARADIGM_RECIPES))
        self.assertEqual(
            {
                "precision_fact_extraction",
                "methodology_reproduction",
                "deep_comparison",
                "association_influence_genealogy",
                "complex_multihop_reasoning",
                "uncertainty_knowledge_boundary",
                "ambiguity_resolution",
                "contradiction_resolution",
                "context_specific_brainstorming",
            },
            {item.paradigm_id for item in PARADIGM_RECIPES.values() if item.golden_backed},
        )
        self.assertTrue(all(item.semantic_steps for item in PARADIGM_RECIPES.values()))
        self.assertTrue(all(item.claim_roles for item in PARADIGM_RECIPES.values()))

    def test_execution_status_and_outcome_contracts_remain_stable(self) -> None:
        self.assertEqual(
            {"COMPLETED", "NEEDS_CLARIFICATION", "INCOMPLETE_PRECISE", "FAILED_TECHNICAL"},
            {item.value for item in ExecutionStatus},
        )
        self.assertEqual(
            {"answered", "needs_clarification", "abstained", "partial"},
            {item.value for item in ResearchOutcome},
        )

    def test_turn_interpreter_returns_minimal_evidence_obligations(self) -> None:
        dataset = _synthetic_dataset()
        decision = TurnInterpreter(_ExactFactHarnessModel()).interpret(
            TurnFrame(
                turn_id="turn_1",
                question="What is the synthetic answer?",
                allowed_paper_ids=["synthetic_paper"],
            ),
            dataset,
        )

        self.assertEqual("research", decision.route)
        self.assertEqual("precision_fact_extraction", decision.primary_paradigm)
        self.assertEqual(
            [
                Obligation(
                    "answer",
                    "Recover the requested answer value.",
                    "fact",
                    ["Synthetic Paper"],
                    True,
                    "answer",
                )
            ],
            decision.obligations,
        )

    def test_dependent_relation_edges_are_normalized_to_multihop(self) -> None:
        decision = TurnDecision(
            route="research",
            effective_goal="Trace A through B to C.",
            task={"verb": "trace", "object": "chain"},
            constraints={},
            primary_paradigm="association_influence_genealogy",
            answer_shape="citation_genealogy",
            obligations=[
                Obligation("a_to_b", "Establish A to B.", "relation", ["A", "B"]),
                Obligation("b_to_c", "Establish B to C.", "relation", ["B", "C"]),
                Obligation("disclaimer", "Do not invent A to C.", "analysis_step", ["A", "C"]),
            ],
        )

        normalized = _normalize_turn_decision(decision)

        self.assertEqual("complex_multihop_reasoning", normalized.primary_paradigm)
        self.assertEqual(["a_to_b", "b_to_c"], [item.obligation_id for item in normalized.obligations])
        self.assertTrue(all(item.kind == "hop" for item in normalized.obligations))

    def test_comparison_normalization_keeps_target_axis_cells_not_duplicate_rollups(self) -> None:
        decision = TurnDecision(
            route="research",
            effective_goal="Compare A and B.",
            task={"verb": "compare", "object": "papers"},
            constraints={},
            primary_paradigm="deep_comparison",
            answer_shape="comparison_matrix",
            obligations=[
                Obligation("a_role", "Determine A's role.", "comparison_cell", ["A"]),
                Obligation("b_role", "Determine B's role.", "comparison_cell", ["B"]),
                Obligation("role_rollup", "Compare both roles.", "comparison_cell", ["A", "B"]),
            ],
        )

        normalized = _normalize_turn_decision(decision)

        self.assertEqual(["a_role", "b_role"], [item.obligation_id for item in normalized.obligations])

    def test_precision_with_multiple_possible_papers_becomes_one_clarification(self) -> None:
        decision = TurnDecision(
            route="research",
            effective_goal="Research the attention paper.",
            task={"verb": "extract", "object": "paper facts"},
            constraints={},
            primary_paradigm="precision_fact_extraction",
            answer_shape="exact_fact",
            obligations=[
                Obligation("paper", "Resolve the paper.", "fact", ["A", "B"]),
            ],
        )

        normalized = _normalize_turn_decision(decision)

        self.assertEqual("clarify", normalized.route)
        self.assertEqual("ambiguity_resolution", normalized.primary_paradigm)
        self.assertEqual("free_text", normalized.pending_interaction["kind"])

    def test_exact_fact_runtime_is_evidence_bounded_and_renders_citations(self) -> None:
        dataset = _synthetic_dataset()

        run = ResearchAgentHarness(_ExactFactHarnessModel()).run_case(dataset, dataset.cases[0])

        self.assertEqual("COMPLETED", run["research_answer"]["status"])
        self.assertEqual("42", run["research_answer"]["fields"]["answer"])
        self.assertEqual(1, len(run["research_answer"]["cited_evidence_ids"]))
        self.assertNotIn("ev_", run["research_answer"]["markdown"])
        self.assertIn("Sources", run["research_answer"]["markdown"])
        self.assertEqual(
            ["collect_evidence", "construct_claims", "verify_claims", "render_answer"],
            [item["stage_name"] for item in run["stage_trace"]],
        )
        self.assertTrue(run["verification_pass"]["answer_reference_integrity_passed"])
        self.assertEqual(0, run["verification_pass"]["unsupported_claim_count"])

    def test_empty_location_result_can_be_reformulated_inside_one_retrieval_stage(self) -> None:
        dataset = _synthetic_dataset()
        model = _AdaptiveRetrievalModel()

        run = ResearchAgentHarness(model).run_case(dataset, dataset.cases[0])

        location_calls = [
            call
            for trace in run["stage_trace"]
            for call in trace["tool_calls"]
            if call["tool_name"] == "find_reading_locations"
        ]
        self.assertEqual(2, len(location_calls))
        self.assertEqual("unrelated vocabulary", location_calls[0]["arguments"]["query_text"])
        self.assertEqual("structured value", location_calls[1]["arguments"]["query_text"])
        self.assertEqual("COMPLETED", run["research_answer"]["status"])

    def test_section_hint_is_soft_and_cannot_hide_a_relevant_passage(self) -> None:
        tools = ReadingCorpusTools(_synthetic_dataset())
        tools.find_papers_by_identity({"paper_id": "synthetic_paper"})

        result = tools.find_reading_locations({
            "paper_ids": ["synthetic_paper"],
            "query_text": "structured value",
            "section_query": "Nonexistent Training Section",
            "top_k": 3,
        })

        self.assertEqual(["loc_1"], [item["location_ref"] for item in result["locations"]])

    def test_verifier_drops_unsupported_extra_claim_before_rendering(self) -> None:
        dataset = _synthetic_dataset()

        run = ResearchAgentHarness(_UnsupportedExtraHarnessModel()).run_case(dataset, dataset.cases[0])

        self.assertEqual("COMPLETED", run["research_answer"]["status"])
        self.assertIn("**answer:** 42", run["research_answer"]["markdown"])
        self.assertNotIn("causes universal improvement", run["research_answer"]["markdown"])
        self.assertEqual(1, run["verification_pass"]["unsupported_claim_count"])
        dropped = next(
            item for item in run["claim_graph"]["claims"]
            if item["claim_id"] == "extra_claim"
        )
        self.assertEqual("underdetermined", dropped["status"])

    def test_missing_evidence_produces_precise_abstention_without_freeform_claims(self) -> None:
        dataset = _synthetic_dataset()

        run = ResearchAgentHarness(_NoEvidenceHarnessModel()).run_case(dataset, dataset.cases[0])

        self.assertEqual("INCOMPLETE_PRECISE", run["research_answer"]["status"])
        self.assertEqual("abstained", run["research_answer"]["outcome"])
        self.assertEqual([], run["research_answer"]["cited_claim_ids"])
        self.assertTrue(run["evidence_ledger"]["missing_evidence"])

    def test_corpus_boundary_can_use_a_complete_nonciteable_corpus_observation(self) -> None:
        dataset = _synthetic_dataset()

        run = ResearchAgentHarness(_BoundaryHarnessModel()).run_case(dataset, dataset.cases[0])

        self.assertEqual("COMPLETED", run["research_answer"]["status"])
        self.assertEqual("abstained", run["research_answer"]["outcome"])
        self.assertEqual([], run["research_answer"]["cited_evidence_ids"])
        self.assertEqual("corpus_supported", run["claim_graph"]["claims"][0]["status"])

    def test_boundary_obligation_inside_multihop_answer_does_not_force_abstention(self) -> None:
        dataset = _synthetic_dataset()

        run = ResearchAgentHarness(_MixedBoundaryHarnessModel()).run_case(dataset, dataset.cases[0])

        self.assertEqual("COMPLETED", run["research_answer"]["status"])
        self.assertEqual("answered", run["research_answer"]["outcome"])
        self.assertEqual(2, len(run["research_answer"]["cited_claim_ids"]))

    def test_keep_verdict_with_unknown_evidence_is_dropped(self) -> None:
        dataset = _synthetic_dataset()

        run = ResearchAgentHarness(_UnknownVerifierEvidenceModel()).run_case(dataset, dataset.cases[0])

        self.assertEqual("INCOMPLETE_PRECISE", run["research_answer"]["status"])
        self.assertEqual([], run["research_answer"]["cited_claim_ids"])
        self.assertEqual(1, run["verification_pass"]["unsupported_claim_count"])

    def test_retrieval_plan_is_causal_and_contains_obligations_not_tool_history(self) -> None:
        dataset = _synthetic_dataset()

        run = ResearchAgentHarness(_ExactFactHarnessModel()).run_case(dataset, dataset.cases[0])
        plan = run["retrieval_plan"]

        self.assertEqual("answer", plan["obligations"][0]["id"])
        self.assertEqual(
            [
                "define_fact_slots",
                "collect_exact_evidence",
                "extract_atomic_facts",
                "verify_exact_values",
            ],
            [item["stage_name"] for item in plan["strategy_steps"]],
        )
        self.assertFalse(any("tool_call_id" in item for item in plan["strategy_steps"]))


class _ExactFactHarnessModel(ChatModel):
    def __init__(self) -> None:
        self.request_payloads: list[dict] = []

    def complete(self, messages, tools, max_tokens):
        system = str(messages[0].get("content") or "")
        if "TURN_DECISION" in system:
            return self._turn_decision(messages)
        if "EVIDENCE_QUERY_PLANNING" in system:
            return self._evidence_collection(messages, tools)
        if "ATOMIC_CLAIM_CONSTRUCTION" in system:
            return self._claim_construction(messages)
        if "ATOMIC_CLAIM_VERIFICATION" in system:
            return self._claim_verification(messages)
        raise AssertionError(f"unexpected model prompt: {system[:80]}")

    def _turn_decision(self, messages) -> ChatTurn:
        payload = json.loads(messages[-1]["content"])
        self.request_payloads.append(payload)
        context = payload.get("conversation_context") or {}
        obligation = {
            "id": "answer",
            "description": "Recover the requested answer value.",
            "kind": "fact",
            "paper_scope": ["Synthetic Paper"],
            "required": True,
            "answer_field": "answer",
        }
        return _tool_turn("submit_turn_decision", {
            "route": "research",
            "effective_goal": (
                "Use the previously selected evidence to answer again."
                if context.get("selected_evidence_refs")
                else "Recover the requested answer from Synthetic Paper."
            ),
            "task": {"verb": "extract", "object": "answer"},
            "constraints": {},
            "primary_paradigm": "precision_fact_extraction",
            "answer_shape": "exact_fact",
            "obligations": [obligation],
            "assumption": "",
            "blocking_reason": None,
            "direct_reply": "",
            "pending_interaction": {
                "interaction_id": "",
                "kind": "none",
                "question": "",
                "options": [],
            },
            "paper_references": [{"text": "Synthetic Paper"}],
            "requested_aspects": ["answer"],
            "requires_corpus_observation": True,
            "required_evidence_types": ["paragraph"],
            "required_capabilities": ["evidence_retrieval"],
            "confidence": 1.0,
        })

    def _evidence_collection(self, messages, tools) -> ChatTurn:
        user_payload = json.loads(messages[-1]["content"])
        self.request_payloads.append(user_payload)
        existing = user_payload.get("existing_state", {}).get("evidence", [])
        citeable_existing = [item for item in existing if item.get("citeable") is not False]
        if citeable_existing:
            return _retrieval_plan_turn(
                paper_targets=[],
                evidence_queries=[],
                reuse_evidence=[{
                    "obligation_id": "answer",
                    "evidence_ids": [citeable_existing[0]["evidence_id"]],
                }],
            )
        return _retrieval_plan_turn(
            paper_targets=[_synthetic_target()],
            evidence_queries=[_synthetic_query("structured value")],
        )

    def _claim_construction(self, messages) -> ChatTurn:
        payload = json.loads(messages[-1]["content"])
        self.request_payloads.append(payload)
        evidence_id = next(
            item["evidence_id"]
            for item in payload["evidence"]
            if item.get("citeable") is not False
        )
        return _tool_turn("submit_claims", {
            "claims": [{
                "claim_id": "answer_claim",
                "role": "fact",
                "text": "The structured value is 42.",
                "obligation_ids": ["answer"],
                "evidence_ids": [evidence_id],
                "field_values": [{"name": "answer", "value": "42"}],
            }],
            "summary": "Constructed the requested exact fact only.",
        })

    def _claim_verification(self, messages) -> ChatTurn:
        payload = json.loads(messages[-1]["content"])
        self.request_payloads.append(payload)
        claim = payload["claims"][0]
        return _tool_turn("submit_claim_verdicts", {
            "verdicts": [{
                "claim_id": claim["claim_id"],
                "verdict": "keep",
                "verified_text": claim["text"],
                "evidence_ids": claim["evidence_ids"],
                "field_values": claim["field_values"],
                "reason": "The passage directly supports the exact value.",
            }],
            "summary": "The atomic fact is supported.",
        })


class _AdaptiveRetrievalModel(_ExactFactHarnessModel):
    def _evidence_collection(self, messages, tools) -> ChatTurn:
        payload = json.loads(messages[-1]["content"])
        retry = bool(payload.get("retry"))
        return _retrieval_plan_turn(
            paper_targets=[] if retry else [_synthetic_target()],
            evidence_queries=[
                _synthetic_query("structured value" if retry else "unrelated vocabulary")
            ],
        )


class _UnsupportedExtraHarnessModel(_ExactFactHarnessModel):
    def _claim_construction(self, messages) -> ChatTurn:
        payload = json.loads(messages[-1]["content"])
        evidence_id = next(
            item["evidence_id"]
            for item in payload["evidence"]
            if item.get("citeable") is not False
        )
        return _tool_turn("submit_claims", {
            "claims": [
                {
                    "claim_id": "answer_claim",
                    "role": "fact",
                    "text": "The structured value is 42.",
                    "obligation_ids": ["answer"],
                    "evidence_ids": [evidence_id],
                    "field_values": [{"name": "answer", "value": "42"}],
                },
                {
                    "claim_id": "extra_claim",
                    "role": "fact",
                    "text": "The value causes universal improvement.",
                    "obligation_ids": ["answer"],
                    "evidence_ids": [evidence_id],
                    "field_values": [],
                },
            ],
            "summary": "Constructed one requested fact and one speculative extra claim.",
        })

    def _claim_verification(self, messages) -> ChatTurn:
        payload = json.loads(messages[-1]["content"])
        verdicts = []
        for claim in payload["claims"]:
            keep = claim["claim_id"] == "answer_claim"
            verdicts.append({
                "claim_id": claim["claim_id"],
                "verdict": "keep" if keep else "drop",
                "verified_text": claim["text"] if keep else "",
                "evidence_ids": claim["evidence_ids"] if keep else [],
                "field_values": claim["field_values"] if keep else [],
                "reason": "Directly supported." if keep else "The causal improvement claim is absent from the passage.",
            })
        return _tool_turn("submit_claim_verdicts", {
            "verdicts": verdicts,
            "summary": "Dropped the unsupported extra claim.",
        })


class _NoEvidenceHarnessModel(_ExactFactHarnessModel):
    def _evidence_collection(self, messages, tools) -> ChatTurn:
        payload = json.loads(messages[-1]["content"])
        return _retrieval_plan_turn(
            paper_targets=[] if payload.get("retry") else [_synthetic_target()],
            evidence_queries=[_synthetic_query("unrelated vocabulary")],
        )

    def _claim_construction(self, messages) -> ChatTurn:
        return _tool_turn("submit_claims", {
            "claims": [],
            "summary": "No claims can be constructed without evidence.",
        })


class _UnknownVerifierEvidenceModel(_ExactFactHarnessModel):
    def _claim_verification(self, messages) -> ChatTurn:
        claim = json.loads(messages[-1]["content"])["claims"][0]
        return _tool_turn("submit_claim_verdicts", {
            "verdicts": [{
                "claim_id": claim["claim_id"],
                "verdict": "keep",
                "verified_text": claim["text"],
                "evidence_ids": ["unknown_evidence"],
                "field_values": claim["field_values"],
                "reason": "Invalid fixture verdict.",
            }],
            "summary": "Invalid fixture verdict.",
        })


class _BoundaryHarnessModel(ChatModel):
    def complete(self, messages, tools, max_tokens):
        system = str(messages[0].get("content") or "")
        if "TURN_DECISION" in system:
            return _tool_turn("submit_turn_decision", {
                "route": "research",
                "effective_goal": "Determine whether the corpus covers GPT-5 architecture.",
                "task": {"verb": "verify", "object": "GPT-5 architecture coverage"},
                "constraints": {},
                "primary_paradigm": "uncertainty_knowledge_boundary",
                "answer_shape": "uncertainty_boundary",
                "obligations": [{
                    "id": "gpt5_boundary",
                    "description": "Determine whether GPT-5 architecture is covered by the corpus.",
                    "kind": "corpus_boundary",
                    "paper_scope": ["GPT-5"],
                    "required": True,
                    "answer_field": "",
                }],
                "assumption": "",
                "blocking_reason": None,
                "direct_reply": "",
                "pending_interaction": {
                    "interaction_id": "",
                    "kind": "none",
                    "question": "",
                    "options": [],
                },
                "paper_references": [{"text": "GPT-5"}],
                "requested_aspects": ["architecture"],
                "requires_corpus_observation": True,
                "required_evidence_types": ["corpus_search"],
                "required_capabilities": ["candidate_retrieval"],
                "confidence": 1.0,
            })
        if "EVIDENCE_QUERY_PLANNING" in system:
            return _retrieval_plan_turn(
                paper_targets=[{
                    "target_id": "gpt5",
                    "paper_id": "",
                    "title": "GPT-5",
                    "arxiv_id": "",
                    "discovery_query": "GPT-5",
                    "browse_all": False,
                    "coverage_obligation_ids": [],
                }],
                evidence_queries=[],
            )
        if "ATOMIC_CLAIM_CONSTRUCTION" in system:
            payload = json.loads(messages[-1]["content"])
            observation = next(item for item in payload["evidence"] if item.get("citeable") is False)
            return _tool_turn("submit_claims", {
                "claims": [{
                    "claim_id": "boundary_claim",
                    "role": "unsupported_boundary",
                    "text": "The available corpus does not contain evidence that verifies GPT-5 architecture details.",
                    "obligation_ids": ["gpt5_boundary"],
                    "evidence_ids": [observation["evidence_id"]],
                    "field_values": [],
                }],
                "summary": "Constructed the corpus boundary claim.",
            })
        if "ATOMIC_CLAIM_VERIFICATION" in system:
            claim = json.loads(messages[-1]["content"])["claims"][0]
            return _tool_turn("submit_claim_verdicts", {
                "verdicts": [{
                    "claim_id": claim["claim_id"],
                    "verdict": "keep",
                    "verified_text": claim["text"],
                    "evidence_ids": claim["evidence_ids"],
                    "field_values": [],
                    "reason": "The complete corpus search supports this boundary.",
                }],
                "summary": "Verified the corpus boundary.",
            })
        raise AssertionError(f"unexpected model prompt: {system[:80]}")


class _MixedBoundaryHarnessModel(ChatModel):
    def complete(self, messages, tools, max_tokens):
        system = str(messages[0].get("content") or "")
        if "TURN_DECISION" in system:
            return _tool_turn("submit_turn_decision", {
                "route": "research",
                "effective_goal": "Build one supported hop and state the prohibited direct-edge boundary.",
                "task": {"verb": "build", "object": "two-part chain"},
                "constraints": {"prohibited_direct_edge": True},
                "primary_paradigm": "complex_multihop_reasoning",
                "answer_shape": "multi_hop_chain",
                "obligations": [
                    {
                        "id": "hop",
                        "description": "Establish the supported synthetic hop.",
                        "kind": "hop",
                        "paper_scope": ["Synthetic Paper"],
                        "required": True,
                        "answer_field": "",
                    },
                    {
                        "id": "boundary",
                        "description": "State the direct-edge boundary.",
                        "kind": "corpus_boundary",
                        "paper_scope": ["Synthetic Paper"],
                        "required": True,
                        "answer_field": "",
                    },
                ],
                "assumption": "",
                "blocking_reason": None,
                "direct_reply": "",
                "pending_interaction": {
                    "interaction_id": "",
                    "kind": "none",
                    "question": "",
                    "options": [],
                },
                "paper_references": [{"text": "Synthetic Paper"}],
                "requested_aspects": ["hop", "boundary"],
                "requires_corpus_observation": True,
                "required_evidence_types": ["paragraph", "corpus_search"],
                "required_capabilities": ["evidence_retrieval"],
                "confidence": 1.0,
            })
        if "EVIDENCE_QUERY_PLANNING" in system:
            return _retrieval_plan_turn(
                paper_targets=[{
                    **_synthetic_target(),
                    "coverage_obligation_ids": ["boundary"],
                }],
                evidence_queries=[{
                    **_synthetic_query("structured value"),
                    "obligation_ids": ["hop"],
                }],
            )
        if "ATOMIC_CLAIM_CONSTRUCTION" in system:
            payload = json.loads(messages[-1]["content"])
            citeable = next(item for item in payload["evidence"] if item.get("citeable") is not False)
            observation = next(item for item in payload["evidence"] if item.get("citeable") is False)
            return _tool_turn("submit_claims", {
                "claims": [
                    {
                        "claim_id": "hop_claim",
                        "role": "hop",
                        "text": "The synthetic hop establishes the structured value 42.",
                        "obligation_ids": ["hop"],
                        "evidence_ids": [citeable["evidence_id"]],
                        "field_values": [],
                    },
                    {
                        "claim_id": "boundary_claim",
                        "role": "chain_boundary",
                        "text": "No unsupported direct edge is asserted.",
                        "obligation_ids": ["boundary"],
                        "evidence_ids": [observation["evidence_id"]],
                        "field_values": [],
                    },
                ],
                "summary": "Constructed the hop and boundary claims.",
            })
        if "ATOMIC_CLAIM_VERIFICATION" in system:
            claims = json.loads(messages[-1]["content"])["claims"]
            return _tool_turn("submit_claim_verdicts", {
                "verdicts": [
                    {
                        "claim_id": claim["claim_id"],
                        "verdict": "keep",
                        "verified_text": claim["text"],
                        "evidence_ids": claim["evidence_ids"],
                        "field_values": [],
                        "reason": "Supported by the supplied evidence.",
                    }
                    for claim in claims
                ],
                "summary": "Verified both claims.",
            })
        raise AssertionError(f"unexpected model prompt: {system[:80]}")


def _tool_turn(name: str, arguments: dict, call_id: str | None = None) -> ChatTurn:
    return ChatTurn(
        content="",
        tool_calls=[ToolCall(id=call_id or f"call_{name}", name=name, arguments=arguments)],
    )


def _retrieval_plan_turn(
    *,
    paper_targets: list[dict],
    evidence_queries: list[dict],
    reuse_evidence: list[dict] | None = None,
) -> ChatTurn:
    return _tool_turn("submit_retrieval_queries", {
        "paper_targets": paper_targets,
        "evidence_queries": evidence_queries,
        "reuse_evidence": reuse_evidence or [],
    })


def _synthetic_target() -> dict:
    return {
        "target_id": "synthetic",
        "paper_id": "synthetic_paper",
        "title": "Synthetic Paper",
        "arxiv_id": "",
        "discovery_query": "",
        "browse_all": False,
        "coverage_obligation_ids": [],
    }


def _synthetic_query(query_text: str) -> dict:
    return {
        "query_id": "synthetic_answer_query",
        "obligation_ids": ["answer"],
        "target_ids": ["synthetic"],
        "query_text": query_text,
        "section_query": "",
        "element_types": ["paragraph"],
        "top_k": 3,
    }


def _synthetic_dataset() -> GoldenDataset:
    paper = {
        "paper_id": "synthetic_paper",
        "identity": {
            "title": "Synthetic Paper",
            "year": 2026,
            "version_label": "fixture",
        },
        "source_assets": {"reading_model_path": "synthetic.reading-model.json"},
    }
    anchor = {
        "anchor_id": "synthetic_anchor",
        "paper_id": "synthetic_paper",
        "element": {
            "type": "paragraph",
            "page": 1,
            "section": "Result",
            "location_hint": "result paragraph",
        },
        "selector": {"exact_text": "structured value forty two"},
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
                    "id": "el_1",
                    "readingElementId": "el_1",
                    "locationRef": "loc_1",
                    "elementType": "paragraph",
                    "pageNumber": 1,
                    "sectionTitle": "Result",
                    "searchableText": "The structured value forty two is the answer 42.",
                }],
            }
        },
    )


if __name__ == "__main__":
    unittest.main()
