from __future__ import annotations

from dataclasses import dataclass

from .models import StageSpec


CANDIDATE_TOOLS = ("search_paper_candidates", "find_papers_by_identity")
EVIDENCE_TOOLS = ("find_reading_locations", "read_locations")
LINEAGE_TOOLS = ("get_citation_edges", "find_reading_locations", "read_locations")


@dataclass(frozen=True)
class ParadigmDefinition:
    paradigm_id: str
    description: str
    default_answer_shape: str
    stages: tuple[StageSpec, ...]


def _stage(
    name: str,
    instruction: str,
    tools: tuple[str, ...] = (),
    *,
    strategy: str,
    evidence: bool = False,
    answer: bool = False,
    max_model_turns: int = 4,
    max_tool_calls: int = 6,
) -> StageSpec:
    return StageSpec(
        name=name,
        instruction=instruction,
        strategy=strategy,
        allowed_tools=tools,
        requires_new_evidence=evidence,
        produces_answer=answer,
        max_model_turns=max_model_turns,
        max_tool_calls=max_tool_calls,
    )


PARADIGM_DEFINITIONS: dict[str, ParadigmDefinition] = {
    "precision_fact_extraction": ParadigmDefinition(
        paradigm_id="precision_fact_extraction",
        description="Locate and extract exact facts, values, parameters, roles, or definitions from source papers.",
        default_answer_shape="exact_fact",
        stages=(
            _stage(
                "resolve_target_paper",
                "Resolve the exact paper or papers whose content can answer the request. Do not infer content yet.",
                CANDIDATE_TOOLS,
                strategy="paper_identity_resolution",
            ),
            _stage(
                "define_fact_slots",
                "Translate the request into explicit fact slots, values, units, qualifiers, and source constraints.",
                strategy="fact_slot_definition",
            ),
            _stage(
                "locate_exact_evidence",
                "Search and read exact source locations that jointly satisfy every requested fact slot.",
                EVIDENCE_TOOLS,
                strategy="lexical_search",
                evidence=True,
                max_tool_calls=10,
            ),
            _stage(
                "extract_exact_facts",
                "Extract atomic evidence-linked claims and populate the requested answer fields without adding unstated facts.",
                strategy="exact_fact_synthesis",
                answer=True,
            ),
        ),
    ),
    "methodology_reproduction": ParadigmDefinition(
        paradigm_id="methodology_reproduction",
        description="Reconstruct a method, experiment, implementation recipe, or reproduction checklist.",
        default_answer_shape="reproduction_protocol",
        stages=(
            _stage(
                "resolve_method_source",
                "Resolve the original method or experiment source and exclude later defaults unless requested.",
                CANDIDATE_TOOLS,
                strategy="paper_identity_resolution",
            ),
            _stage(
                "define_reproduction_requirements",
                "Define a compact retrieval checklist containing only the procedure, parameter, data, environment, evaluation, and hidden-assumption slots that must be recovered. Do not fill those slots yet.",
                strategy="reproduction_requirement_definition",
            ),
            _stage(
                "retrieve_procedure_and_parameters",
                "Search and read source evidence for the procedure, parameter values, training conditions, and evaluation setup.",
                EVIDENCE_TOOLS,
                strategy="lexical_search",
                evidence=True,
                max_tool_calls=8,
            ),
            _stage(
                "build_reproduction_checklist",
                "Create an evidence-linked reproduction checklist and explicitly name any unrecovered requirements.",
                strategy="reproduction_synthesis",
                answer=True,
            ),
        ),
    ),
    "deep_comparison": ParadigmDefinition(
        paradigm_id="deep_comparison",
        description="Compare multiple papers, methods, or systems over explicit semantic dimensions.",
        default_answer_shape="comparison_matrix",
        stages=(
            _stage(
                "resolve_comparison_targets",
                "Resolve every comparison target and preserve the user's source and version constraints.",
                CANDIDATE_TOOLS,
                strategy="paper_identity_resolution",
            ),
            _stage(
                "establish_comparison_axes",
                "Derive the smallest set of semantic axes needed to answer the requested comparison.",
                strategy="comparison_axis_induction",
            ),
            _stage(
                "retrieve_evidence_per_axis",
                "Search and read evidence for every target-axis cell; do not silently omit asymmetric or missing cells.",
                EVIDENCE_TOOLS,
                strategy="lexical_search",
                evidence=True,
                max_tool_calls=12,
            ),
            _stage(
                "synthesize_comparison",
                "Build atomic claims and an evidence-linked comparison matrix covering every requested axis.",
                strategy="comparison_synthesis",
                answer=True,
            ),
        ),
    ),
    "association_influence_genealogy": ParadigmDefinition(
        paradigm_id="association_influence_genealogy",
        description=(
            "Trace citations, influences, or research lineage when the relationship map itself is "
            "the requested result and each supported edge can be explained independently."
        ),
        default_answer_shape="citation_genealogy",
        stages=(
            _stage(
                "resolve_lineage_nodes",
                "Resolve the papers or methods that form the requested lineage endpoints and intermediate nodes.",
                CANDIDATE_TOOLS,
                strategy="paper_identity_resolution",
            ),
            _stage(
                "trace_relationship_edges",
                "Inspect citation edges and source passages that state the technical relationship between lineage nodes.",
                LINEAGE_TOOLS,
                strategy="citation_graph_traversal",
                evidence=True,
                max_tool_calls=8,
            ),
            _stage(
                "explain_lineage_mechanism",
                "Explain what technical mechanism, architecture, objective, or method passes across each supported edge.",
                strategy="lineage_interpretation",
            ),
            _stage(
                "render_genealogy",
                "Produce a directed evidence-linked genealogy and a concise explanation of each supported relationship.",
                strategy="genealogy_synthesis",
                answer=True,
            ),
        ),
    ),
    "complex_multihop_reasoning": ParadigmDefinition(
        paradigm_id="complex_multihop_reasoning",
        description=(
            "Compose two or more independently evidenced hops whose intermediate conclusions are "
            "required to establish the final answer; preserve every dependency and intermediate node."
        ),
        default_answer_shape="multi_hop_chain",
        stages=(
            _stage(
                "decompose_reasoning_hops",
                "Decompose the requested conclusion into an ordered chain of independently supportable semantic hops.",
                strategy="multihop_decomposition",
            ),
            _stage(
                "resolve_hop_entities",
                "Resolve the papers, methods, datasets, or claims needed by every hop.",
                CANDIDATE_TOOLS,
                strategy="paper_identity_resolution",
            ),
            _stage(
                "retrieve_evidence_per_hop",
                "Search and read evidence for every hop, preserving source identity and any citation relationships.",
                LINEAGE_TOOLS,
                strategy="citation_graph_traversal",
                evidence=True,
                max_tool_calls=12,
            ),
            _stage(
                "link_supported_hops",
                "Construct an evidence-linked claim chain and stop if any required hop remains unsupported.",
                strategy="multihop_synthesis",
                answer=True,
            ),
        ),
    ),
    "uncertainty_knowledge_boundary": ParadigmDefinition(
        paradigm_id="uncertainty_knowledge_boundary",
        description="Determine what the available corpus supports, does not support, or leaves uncertain.",
        default_answer_shape="uncertainty_boundary",
        stages=(
            _stage(
                "define_requested_claim",
                "State the exact claim the user wants supported and the evidence that would be required to support it.",
                strategy="knowledge_boundary_definition",
            ),
            _stage(
                "search_available_support",
                "Search the allowed corpus for the target identity and relevant evidence; record a precise no-match when absent.",
                CANDIDATE_TOOLS + EVIDENCE_TOOLS,
                strategy="lexical_search",
                evidence=True,
            ),
            _stage(
                "test_support_boundary",
                "Separate supported facts, unsupported inferences, and missing evidence without filling gaps from model memory.",
                strategy="knowledge_boundary_testing",
            ),
            _stage(
                "report_known_and_unknown",
                "Return an uncertainty-boundary answer with what is known, what is unsupported, and what evidence is missing.",
                strategy="uncertainty_synthesis",
                answer=True,
            ),
        ),
    ),
    "ambiguity_resolution": ParadigmDefinition(
        paradigm_id="ambiguity_resolution",
        description="Identify multiple plausible interpretations and ask one focused clarification question.",
        default_answer_shape="ambiguity_clarification",
        stages=(
            _stage(
                "enumerate_interpretations",
                "Enumerate the smallest plausible interpretation set using the request and conversation context.",
                strategy="ambiguity_enumeration",
            ),
            _stage(
                "inspect_candidate_identities",
                "Inspect paper metadata only when it helps present concrete choices; do not select for the user.",
                CANDIDATE_TOOLS,
                strategy="paper_identity_resolution",
            ),
            _stage(
                "formulate_clarification",
                "Ask one focused clarification question that lets the user choose among the plausible interpretations.",
                strategy="ambiguity_clarification",
                answer=True,
            ),
        ),
    ),
    "contradiction_resolution": ParadigmDefinition(
        paradigm_id="contradiction_resolution",
        description="Retrieve competing claims, compare their contexts, and adjudicate or preserve the conflict.",
        default_answer_shape="contradiction_arbitration",
        stages=(
            _stage(
                "identify_competing_claims",
                "Materialize each competing claim, source hint, value, and context that must be resolved.",
                strategy="conflict_framing",
            ),
            _stage(
                "resolve_conflict_sources",
                "Resolve the primary sources and versions associated with every competing claim.",
                CANDIDATE_TOOLS,
                strategy="paper_identity_resolution",
            ),
            _stage(
                "retrieve_each_side",
                "Search and read primary evidence for every side of the conflict, including defaults and experiment-specific settings.",
                EVIDENCE_TOOLS,
                strategy="negative_evidence_search",
                evidence=True,
                max_tool_calls=10,
            ),
            _stage(
                "adjudicate_context_difference",
                "Determine whether the claims truly contradict or refer to different settings, versions, populations, or definitions.",
                strategy="conflict_adjudication",
            ),
            _stage(
                "present_conflict_resolution",
                "Produce an evidence-linked conflict matrix and explicit resolution without hiding either side.",
                strategy="contradiction_synthesis",
                answer=True,
            ),
        ),
    ),
    "concept_tracing_definition": ParadigmDefinition(
        paradigm_id="concept_tracing_definition",
        description="Define a concept precisely and trace its origin and meaning changes across primary sources.",
        default_answer_shape="definition_trace",
        stages=(
            _stage(
                "resolve_concept_sources",
                "Resolve the origin source and the smallest set of later sources needed to trace the concept's evolution.",
                CANDIDATE_TOOLS,
                strategy="paper_identity_resolution",
            ),
            _stage(
                "define_concept_scope",
                "Specify the concept boundary, competing meanings, and the definition dimensions that must remain stable across sources.",
                strategy="concept_scope_definition",
            ),
            _stage(
                "retrieve_definition_evolution_evidence",
                "Search and read direct definition statements and evolution evidence for every required source and transition.",
                LINEAGE_TOOLS,
                strategy="definition_trace_retrieval",
                evidence=True,
                max_tool_calls=12,
            ),
            _stage(
                "synthesize_definition_trace",
                "Produce an evidence-linked definition and ordered evolution trace without projecting later meanings backward.",
                strategy="definition_trace_synthesis",
                answer=True,
            ),
        ),
    ),
    "contribution_critical_assessment": ParadigmDefinition(
        paradigm_id="contribution_critical_assessment",
        description="Separate claimed contributions from supported novelty, scope, limitations, and later challenges.",
        default_answer_shape="critical_assessment",
        stages=(
            _stage(
                "frame_contribution_claims",
                "Decompose the requested assessment into claimed contributions, novelty criteria, scope, and challenge questions.",
                strategy="contribution_claim_framing",
            ),
            _stage(
                "resolve_primary_and_challenge_sources",
                "Resolve the primary paper and any comparison, limitation, replication, or challenge sources needed by the assessment.",
                CANDIDATE_TOOLS,
                strategy="paper_identity_resolution",
            ),
            _stage(
                "retrieve_contribution_and_limitation_evidence",
                "Retrieve direct evidence for each claimed contribution, its demonstrated effect, stated limitations, and counterevidence.",
                EVIDENCE_TOOLS,
                strategy="critical_evidence_retrieval",
                evidence=True,
                max_tool_calls=12,
            ),
            _stage(
                "weigh_contribution_evidence",
                "Distinguish demonstrated contribution, plausible interpretation, engineering effect, and unsupported overclaim.",
                strategy="critical_evidence_weighing",
            ),
            _stage(
                "present_critical_assessment",
                "Present an evidence-linked assessment that preserves strengths, limits, and uncertainty.",
                strategy="critical_assessment_synthesis",
                answer=True,
            ),
        ),
    ),
    "context_specific_brainstorming": ParadigmDefinition(
        paradigm_id="context_specific_brainstorming",
        description="Generate options for a concrete user context while grounding transfer assumptions in literature evidence.",
        default_answer_shape="constraint_filter",
        stages=(
            _stage(
                "select_and_ground_candidates",
                "Frame the user's objective and constraints. Browse the fixed corpus with exactly one search_paper_candidates call using query_text='' and limit=100; do not issue separate category or synonym searches. Select at most four candidates from that complete card set. Then call find_reading_locations exactly once across those paper ids, omit element_types unless the user explicitly requested one, and set top_k at least to the number of selected papers. Finally call read_locations exactly once for the returned location refs. Candidate cards and navigation previews are not evidence.",
                ("search_paper_candidates", "find_reading_locations", "read_locations"),
                strategy="bounded_candidate_grounding",
                evidence=True,
                max_model_turns=4,
                max_tool_calls=3,
            ),
            _stage(
                "recommend",
                "Present up to four evidence-bounded recommendations, concise reasons, tradeoffs, and the corpus-coverage assumption used.",
                strategy="evidence_bounded_recommendation",
                answer=True,
            ),
        ),
    ),
    "data_benchmark_provenance": ParadigmDefinition(
        paradigm_id="data_benchmark_provenance",
        description="Trace how a dataset or benchmark was constructed, versioned, filtered, annotated, and evaluated.",
        default_answer_shape="data_provenance",
        stages=(
            _stage(
                "resolve_dataset_benchmark_sources",
                "Resolve the canonical dataset or benchmark paper and any required version or revision sources.",
                CANDIDATE_TOOLS,
                strategy="paper_identity_resolution",
            ),
            _stage(
                "define_provenance_fields",
                "Define the required provenance fields: source data, collection, filtering, annotation, splits, versions, and known limitations.",
                strategy="provenance_schema_definition",
            ),
            _stage(
                "retrieve_construction_and_version_evidence",
                "Retrieve source evidence for every required provenance field and version transition.",
                EVIDENCE_TOOLS,
                strategy="provenance_evidence_retrieval",
                evidence=True,
                max_tool_calls=12,
            ),
            _stage(
                "reconcile_versions_and_splits",
                "Reconcile naming, split, preprocessing, and version differences without merging incompatible records.",
                strategy="provenance_reconciliation",
            ),
            _stage(
                "present_provenance_record",
                "Produce an evidence-linked provenance record with explicit version and limitation boundaries.",
                strategy="provenance_synthesis",
                answer=True,
            ),
        ),
    ),
    "hypothetical_discussion": ParadigmDefinition(
        paradigm_id="hypothetical_discussion",
        description="Analyze a counterfactual by grounding the factual baseline and propagating only explicit changed assumptions.",
        default_answer_shape="counterfactual_analysis",
        stages=(
            _stage(
                "formalize_counterfactual",
                "Separate the factual baseline, the changed assumption, held-fixed conditions, and the requested consequence.",
                strategy="counterfactual_framing",
            ),
            _stage(
                "resolve_baseline_sources",
                "Resolve the primary sources needed to establish the factual baseline and relevant constraints.",
                CANDIDATE_TOOLS,
                strategy="paper_identity_resolution",
            ),
            _stage(
                "retrieve_baseline_and_constraint_evidence",
                "Retrieve evidence for the baseline mechanism, dependencies, and any empirical or theoretical constraints on the hypothetical.",
                EVIDENCE_TOOLS,
                strategy="counterfactual_baseline_retrieval",
                evidence=True,
                max_tool_calls=10,
            ),
            _stage(
                "propagate_changed_assumptions",
                "Propagate the changed assumption through supported dependencies and label speculative branches explicitly.",
                strategy="counterfactual_propagation",
            ),
            _stage(
                "present_counterfactual_analysis",
                "Present baseline facts, changed assumptions, supported consequences, and unresolved speculation separately.",
                strategy="counterfactual_synthesis",
                answer=True,
            ),
        ),
    ),
    "multimodal_cross_domain_system_design": ParadigmDefinition(
        paradigm_id="multimodal_cross_domain_system_design",
        description="Compose evidence-backed components across modalities or domains into a coherent system design.",
        default_answer_shape="system_design",
        stages=(
            _stage(
                "frame_system_requirements",
                "Define modalities, domain constraints, interfaces, latency or resource limits, and the required end-to-end behavior.",
                strategy="system_requirement_framing",
            ),
            _stage(
                "resolve_component_sources",
                "Resolve representative sources for every required system component and interface.",
                CANDIDATE_TOOLS,
                strategy="paper_identity_resolution",
            ),
            _stage(
                "establish_component_interfaces",
                "Define the semantic input, output, supervision, and failure contract at each component boundary.",
                strategy="component_interface_design",
            ),
            _stage(
                "retrieve_component_evidence",
                "Retrieve evidence for component capabilities, assumptions, resource needs, and integration constraints.",
                EVIDENCE_TOOLS,
                strategy="component_evidence_retrieval",
                evidence=True,
                max_tool_calls=12,
            ),
            _stage(
                "synthesize_system_design",
                "Produce an evidence-linked architecture with interfaces, tradeoffs, risks, and unresolved integration questions.",
                strategy="system_design_synthesis",
                answer=True,
            ),
        ),
    ),
    "theory_math_formal_proof": ParadigmDefinition(
        paradigm_id="theory_math_formal_proof",
        description="Recover and explain a formal statement, derivation, proof, or mathematical assumption chain from sources.",
        default_answer_shape="formal_derivation",
        stages=(
            _stage(
                "formalize_target_statement",
                "State the theorem, formula, derivation target, symbols, assumptions, and requested level of rigor.",
                strategy="formal_statement_framing",
            ),
            _stage(
                "resolve_theory_sources",
                "Resolve the primary formal source and any prerequisite or critique sources required for the derivation.",
                CANDIDATE_TOOLS,
                strategy="paper_identity_resolution",
            ),
            _stage(
                "retrieve_formula_and_proof_evidence",
                "Retrieve formulas, definitions, proof steps, and stated assumptions from inspectable source locations.",
                EVIDENCE_TOOLS,
                strategy="formula_proof_retrieval",
                evidence=True,
                max_tool_calls=12,
            ),
            _stage(
                "reconstruct_derivation",
                "Reconstruct the derivation step by step, linking every nontrivial step to evidence or a named standard rule.",
                strategy="formal_derivation_reconstruction",
            ),
            _stage(
                "verify_assumptions_and_scope",
                "Check symbol consistency, hidden assumptions, approximation boundaries, and whether the conclusion actually follows.",
                strategy="formal_assumption_verification",
            ),
            _stage(
                "present_formal_derivation",
                "Present the verified derivation with assumptions, equations, and unresolved proof gaps separated.",
                strategy="formal_derivation_synthesis",
                answer=True,
            ),
        ),
    ),
    "author_institution_research_style": ParadigmDefinition(
        paradigm_id="author_institution_research_style",
        description="Analyze an author or institution's research trajectory and style over a defined corpus and time window.",
        default_answer_shape="research_profile",
        stages=(
            _stage(
                "resolve_author_institution_scope",
                "Resolve the author or institution identity, time window, and representative paper corpus without conflating namesakes.",
                CANDIDATE_TOOLS,
                strategy="paper_identity_resolution",
            ),
            _stage(
                "define_style_dimensions",
                "Define evidence-testable style dimensions such as problem choice, method preference, collaboration, evaluation, and topic shifts.",
                strategy="research_style_dimension_definition",
            ),
            _stage(
                "retrieve_representative_work_evidence",
                "Retrieve representative evidence across the time window for every claimed trajectory or style dimension.",
                EVIDENCE_TOOLS,
                strategy="research_trajectory_retrieval",
                evidence=True,
                max_tool_calls=14,
            ),
            _stage(
                "infer_trajectory_with_coverage",
                "Infer changes only where corpus coverage is sufficient and preserve publication or attribution gaps.",
                strategy="research_trajectory_inference",
            ),
            _stage(
                "present_research_profile",
                "Present an evidence-linked trajectory and style profile with explicit coverage limits.",
                strategy="research_profile_synthesis",
                answer=True,
            ),
        ),
    ),
    "dataset_contamination_generalization": ParadigmDefinition(
        paradigm_id="dataset_contamination_generalization",
        description="Distinguish contamination, leakage, shortcut learning, and genuine distribution shift in evaluation evidence.",
        default_answer_shape="generalization_audit",
        stages=(
            _stage(
                "frame_generalization_hypothesis",
                "Materialize the suspected contamination, leakage, shortcut, or distribution-shift mechanisms and their observable signatures.",
                strategy="generalization_hypothesis_framing",
            ),
            _stage(
                "resolve_dataset_evaluation_sources",
                "Resolve dataset construction, evaluation, replication, and shift-analysis sources needed for the audit.",
                CANDIDATE_TOOLS,
                strategy="paper_identity_resolution",
            ),
            _stage(
                "retrieve_construction_overlap_shift_evidence",
                "Retrieve evidence about data construction, overlap tests, split integrity, corruption, domain shift, and performance changes.",
                EVIDENCE_TOOLS,
                strategy="generalization_evidence_retrieval",
                evidence=True,
                max_tool_calls=14,
            ),
            _stage(
                "adjudicate_contamination_vs_shift",
                "Compare observed signatures against competing explanations and identify what additional test would discriminate them.",
                strategy="generalization_cause_adjudication",
            ),
            _stage(
                "present_generalization_audit",
                "Present supported diagnoses, rejected explanations, and missing tests in a structured audit.",
                strategy="generalization_audit_synthesis",
                answer=True,
            ),
        ),
    ),
    "meta_analysis_systematic_review": ParadigmDefinition(
        paradigm_id="meta_analysis_systematic_review",
        description="Synthesize a defined literature set using explicit inclusion, extraction, quality, and comparison rules.",
        default_answer_shape="systematic_review",
        stages=(
            _stage(
                "define_review_protocol",
                "Define the review question, inclusion and exclusion criteria, outcome fields, quality checks, and stopping rule.",
                strategy="review_protocol_definition",
            ),
            _stage(
                "resolve_eligible_studies",
                "Resolve candidate and eligible studies while recording exclusions and duplicate versions.",
                CANDIDATE_TOOLS,
                strategy="paper_identity_resolution",
                max_tool_calls=12,
            ),
            _stage(
                "retrieve_study_evidence",
                "Retrieve comparable methods, populations, interventions, outcomes, and limitations for every included study.",
                EVIDENCE_TOOLS,
                strategy="systematic_review_retrieval",
                evidence=True,
                max_tool_calls=16,
            ),
            _stage(
                "normalize_outcomes_and_quality",
                "Normalize incompatible outcome definitions and assess study quality before aggregation.",
                strategy="review_outcome_normalization",
            ),
            _stage(
                "synthesize_systematic_review",
                "Produce an evidence-linked synthesis with coverage, heterogeneity, quality, and unresolved disagreement.",
                strategy="systematic_review_synthesis",
                answer=True,
            ),
        ),
    ),
    "boundary_failure_counterexample": ParadigmDefinition(
        paradigm_id="boundary_failure_counterexample",
        description="Test a broad claim by retrieving failures, negative results, and counterexamples that delimit its valid scope.",
        default_answer_shape="boundary_counterexample",
        stages=(
            _stage(
                "formalize_claim_boundary",
                "State the universal or broad claim, its quantifiers, operating conditions, and what would count as a counterexample.",
                strategy="boundary_claim_formalization",
            ),
            _stage(
                "resolve_counterexample_sources",
                "Resolve primary support, failure, replication, and counterexample sources relevant to the claim boundary.",
                CANDIDATE_TOOLS,
                strategy="paper_identity_resolution",
            ),
            _stage(
                "retrieve_support_and_counterevidence",
                "Retrieve direct support, negative results, boundary conditions, and counterexample evidence without favoring the positive claim.",
                EVIDENCE_TOOLS,
                strategy="counterexample_retrieval",
                evidence=True,
                max_tool_calls=12,
            ),
            _stage(
                "test_boundary_and_counterexample",
                "Determine where the claim holds, where it fails, and whether the counterexample is genuinely comparable.",
                strategy="boundary_testing",
            ),
            _stage(
                "present_boundary_report",
                "Present the supported scope, counterexamples, and remaining uncertainty without averaging away failure cases.",
                strategy="boundary_report_synthesis",
                answer=True,
            ),
        ),
    ),
    "future_prediction_open_problem": ParadigmDefinition(
        paradigm_id="future_prediction_open_problem",
        description="Identify open problems and make bounded forecasts by separating source evidence, trend inference, and speculation.",
        default_answer_shape="evidence_forecast",
        stages=(
            _stage(
                "define_forecast_horizon",
                "Define the forecast horizon, target event, evidence cutoff, success criterion, and acceptable uncertainty.",
                strategy="forecast_scope_definition",
            ),
            _stage(
                "resolve_trend_and_position_sources",
                "Resolve recent trend, position, limitation, and roadmap sources relevant to the forecast or open problem.",
                CANDIDATE_TOOLS,
                strategy="paper_identity_resolution",
                max_tool_calls=12,
            ),
            _stage(
                "retrieve_trend_and_open_problem_evidence",
                "Retrieve direct evidence for persistent blockers, recent changes, competing trajectories, and claimed open problems.",
                EVIDENCE_TOOLS,
                strategy="trend_evidence_retrieval",
                evidence=True,
                max_tool_calls=16,
            ),
            _stage(
                "separate_evidence_trend_speculation",
                "Separate source facts, trend extrapolations, assumptions, and speculative branches with calibrated confidence.",
                strategy="forecast_uncertainty_partitioning",
            ),
            _stage(
                "present_evidence_forecast",
                "Present open problems and bounded forecasts with supporting evidence, alternatives, and falsification signals.",
                strategy="evidence_forecast_synthesis",
                answer=True,
            ),
        ),
    ),
    "structured_info_nontraditional_query": ParadigmDefinition(
        paradigm_id="structured_info_nontraditional_query",
        description="Retrieve and interpret tables, figures, formulas, algorithms, appendices, or other structured paper elements.",
        default_answer_shape="figure_table_formula_interpretation",
        stages=(
            _stage(
                "classify_structured_target",
                "Identify the requested structured element type, semantic fields, cross-cell or cross-panel relations, and output shape.",
                strategy="structured_target_classification",
            ),
            _stage(
                "resolve_structured_sources",
                "Resolve the exact paper, version, section, figure, table, formula, algorithm, or appendix target.",
                CANDIDATE_TOOLS,
                strategy="paper_identity_resolution",
            ),
            _stage(
                "retrieve_structured_elements",
                "Search and read the required structured elements and their surrounding captions, labels, and explanatory context.",
                EVIDENCE_TOOLS,
                strategy="structured_element_retrieval",
                evidence=True,
                max_tool_calls=12,
            ),
            _stage(
                "interpret_structured_elements",
                "Interpret cells, curves, formulas, steps, and cross-references without flattening away structural relationships.",
                strategy="structured_element_interpretation",
            ),
            _stage(
                "present_structured_answer",
                "Produce an evidence-linked structured interpretation whose rows, cells, equations, or steps cite their source elements.",
                strategy="structured_answer_synthesis",
                answer=True,
            ),
        ),
    ),
    "complex_constraint_combination": ParadigmDefinition(
        paradigm_id="complex_constraint_combination",
        description="Find or compare candidates under multiple simultaneous hard and soft constraints without silently relaxing any constraint.",
        default_answer_shape="constraint_filter",
        stages=(
            _stage(
                "normalize_constraints",
                "Separate hard constraints, soft preferences, exclusions, ranges, dependencies, and tie-breaking rules.",
                strategy="constraint_normalization",
            ),
            _stage(
                "resolve_candidate_scope",
                "Resolve the candidate paper or method scope and reject out-of-scope identities before evidence retrieval.",
                CANDIDATE_TOOLS,
                strategy="paper_identity_resolution",
                max_tool_calls=12,
            ),
            _stage(
                "retrieve_evidence_per_constraint",
                "Retrieve inspectable evidence for every candidate-constraint cell, including explicit no-match records.",
                EVIDENCE_TOOLS,
                strategy="constraint_evidence_retrieval",
                evidence=True,
                max_tool_calls=16,
            ),
            _stage(
                "apply_constraint_intersection",
                "Apply hard constraints as an intersection, then rank surviving candidates only by declared soft preferences.",
                strategy="constraint_intersection",
            ),
            _stage(
                "verify_no_constraint_relaxation",
                "Verify that no hard constraint was weakened, inferred from metadata alone, or hidden by a ranking score.",
                strategy="constraint_verification",
            ),
            _stage(
                "present_constraint_filter",
                "Present survivors, rejected candidates, per-constraint evidence, and named missing cells.",
                strategy="constraint_filter_synthesis",
                answer=True,
            ),
        ),
    ),
}


def get_paradigm(paradigm_id: str) -> ParadigmDefinition:
    try:
        return PARADIGM_DEFINITIONS[paradigm_id]
    except KeyError as error:
        raise ValueError(f"unsupported paradigm: {paradigm_id}") from error


def intent_catalog_text() -> str:
    return "\n".join(
        f"- {definition.paradigm_id}: {definition.description} Default answer shape: {definition.default_answer_shape}."
        for definition in PARADIGM_DEFINITIONS.values()
    )
