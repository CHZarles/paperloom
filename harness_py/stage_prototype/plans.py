from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class ParadigmRecipe:
    paradigm_id: str
    description: str
    default_answer_shape: str
    obligation_guidance: str
    retrieval_guidance: str
    claim_roles: tuple[str, ...]
    answer_layout: str
    semantic_steps: tuple[str, ...]
    golden_backed: bool = False


def _recipe(
    paradigm_id: str,
    description: str,
    answer_shape: str,
    obligation_guidance: str,
    retrieval_guidance: str,
    claim_roles: tuple[str, ...],
    layout: str,
    steps: tuple[str, ...],
    *,
    golden_backed: bool = False,
) -> ParadigmRecipe:
    return ParadigmRecipe(
        paradigm_id=paradigm_id,
        description=description,
        default_answer_shape=answer_shape,
        obligation_guidance=obligation_guidance,
        retrieval_guidance=retrieval_guidance,
        claim_roles=claim_roles,
        answer_layout=layout,
        semantic_steps=steps,
        golden_backed=golden_backed,
    )


PARADIGM_RECIPES: dict[str, ParadigmRecipe] = {
    "precision_fact_extraction": _recipe(
        "precision_fact_extraction",
        "Locate and extract exact facts, values, parameters, roles, or definitions from source papers.",
        "exact_fact",
        "Create one obligation per requested fact slot, including qualifiers, units, and source constraints.",
        "Resolve the named source, then search and read the smallest passage that jointly covers the fact slots.",
        ("fact",),
        "exact_fact",
        ("define_fact_slots", "collect_exact_evidence", "extract_atomic_facts", "verify_exact_values"),
        golden_backed=True,
    ),
    "methodology_reproduction": _recipe(
        "methodology_reproduction",
        "Reconstruct a method, experiment, implementation recipe, or reproduction checklist.",
        "reproduction_protocol",
        "Create one obligation per requested procedure or parameter item; do not add unrequested checklist scope.",
        "Prefer the original method paper and recover paper-specific settings rather than generic defaults.",
        ("checklist_item", "gap"),
        "checklist",
        ("define_checklist", "collect_procedure_evidence", "build_checklist_claims", "verify_checklist"),
        golden_backed=True,
    ),
    "deep_comparison": _recipe(
        "deep_comparison",
        "Compare multiple papers, methods, or systems over explicit semantic dimensions.",
        "comparison_matrix",
        "Create obligations for each requested target-axis cell and preserve asymmetric architecture or scope distinctions.",
        "Retrieve evidence for every target on every requested axis; do not fill missing cells from general knowledge.",
        ("comparison", "gap"),
        "comparison",
        ("define_comparison_cells", "collect_cell_evidence", "align_target_claims", "verify_comparison"),
        golden_backed=True,
    ),
    "association_influence_genealogy": _recipe(
        "association_influence_genealogy",
        "Trace citations, influences, or research lineage and explain each supported relationship.",
        "citation_genealogy",
        "Create one obligation per requested edge and one for the technical meaning of each edge.",
        "Use citation navigation when available, but read the source passage that states the relationship before claiming it.",
        ("edge", "relationship"),
        "genealogy",
        ("resolve_nodes", "collect_edge_evidence", "state_relationships", "verify_edges"),
        golden_backed=True,
    ),
    "complex_multihop_reasoning": _recipe(
        "complex_multihop_reasoning",
        "Compose independently evidenced hops whose intermediate conclusions are required for the final answer.",
        "multi_hop_chain",
        "Create one obligation per hop and retain every required intermediate node.",
        "Retrieve each hop independently and never replace the chain with an unsupported direct edge.",
        ("hop", "chain_boundary"),
        "chain",
        ("decompose_hops", "collect_hop_evidence", "compose_chain", "verify_each_hop"),
        golden_backed=True,
    ),
    "uncertainty_knowledge_boundary": _recipe(
        "uncertainty_knowledge_boundary",
        "Determine what the available corpus supports, does not support, or leaves uncertain.",
        "uncertainty_boundary",
        "Create obligations for every requested fact and for the corpus-coverage decision itself.",
        "Inspect corpus candidates and read the closest relevant source only when it is needed to explain the boundary.",
        ("supported_context", "unsupported_boundary"),
        "boundary",
        ("test_corpus_coverage", "collect_boundary_evidence", "state_supported_scope", "verify_boundary"),
        golden_backed=True,
    ),
    "ambiguity_resolution": _recipe(
        "ambiguity_resolution",
        "Identify multiple plausible interpretations and ask one focused clarification question.",
        "ambiguity_clarification",
        "Represent the unresolved identity, reference, relation, or hard constraint as one blocking obligation.",
        "Do not retrieve until the blocking ambiguity is resolved.",
        ("clarification",),
        "clarification",
        ("identify_blocker", "request_one_clarification"),
        golden_backed=True,
    ),
    "contradiction_resolution": _recipe(
        "contradiction_resolution",
        "Retrieve competing claims, compare their contexts, and adjudicate or preserve the conflict.",
        "contradiction_arbitration",
        "Create obligations for each source statement and for the narrow scope distinction that resolves or preserves the conflict.",
        "Read both primary passages and preserve words such as default, used, recommended, measured, and conditional.",
        ("source_a", "source_b", "resolution"),
        "contradiction",
        ("define_conflict_sides", "collect_both_sources", "distinguish_scope", "verify_resolution"),
        golden_backed=True,
    ),
    "context_specific_brainstorming": _recipe(
        "context_specific_brainstorming",
        "Generate options for a concrete user context while grounding transfer assumptions in literature evidence.",
        "constraint_filter",
        "Create obligations for the user's selection constraints and for the reason each recommended item fits.",
        "Inspect a bounded candidate set, then read evidence for each selected recommendation; candidate metadata alone is insufficient.",
        ("recommendation", "fit", "caveat"),
        "recommendation",
        ("resolve_selection_constraints", "collect_candidate_evidence", "select_bounded_options", "verify_recommendations"),
        golden_backed=True,
    ),
    "concept_tracing_definition": _recipe(
        "concept_tracing_definition",
        "Define a concept precisely and trace its origin and meaning changes across primary sources.",
        "definition_trace",
        "Create obligations for the initial definition and each requested change in meaning.",
        "Retrieve primary definition passages in chronological order.",
        ("definition", "change"),
        "timeline",
        ("define_trace_points", "collect_definition_evidence", "trace_changes", "verify_trace"),
    ),
    "contribution_critical_assessment": _recipe(
        "contribution_critical_assessment",
        "Separate claimed contributions from supported novelty, scope, limitations, and later challenges.",
        "critical_assessment",
        "Create obligations for contributions, evidence, limitations, and counterevidence requested by the user.",
        "Read claim and limitation passages before evaluating contribution strength.",
        ("contribution", "limitation", "assessment"),
        "assessment",
        ("define_assessment_axes", "collect_claims_and_limits", "weigh_evidence", "verify_assessment"),
    ),
    "data_benchmark_provenance": _recipe(
        "data_benchmark_provenance",
        "Trace how a dataset or benchmark was constructed, versioned, filtered, annotated, and evaluated.",
        "data_provenance",
        "Create obligations for every requested provenance stage or version transition.",
        "Retrieve construction, annotation, split, filtering, and release passages from primary sources.",
        ("provenance_step", "version", "gap"),
        "timeline",
        ("define_provenance_steps", "collect_provenance_evidence", "assemble_history", "verify_provenance"),
    ),
    "hypothetical_discussion": _recipe(
        "hypothetical_discussion",
        "Analyze a counterfactual by grounding the factual baseline and propagating only explicit changed assumptions.",
        "counterfactual_analysis",
        "Separate factual-baseline obligations from changed assumptions and derived consequences.",
        "Retrieve evidence for the factual baseline; label consequences as conditional rather than source facts.",
        ("baseline", "assumption", "conditional_consequence"),
        "analysis",
        ("define_baseline", "collect_baseline_evidence", "propagate_assumption", "verify_boundary"),
    ),
    "multimodal_cross_domain_system_design": _recipe(
        "multimodal_cross_domain_system_design",
        "Compose evidence-backed components across modalities or domains into a coherent system design.",
        "system_design",
        "Create obligations for components, interfaces, constraints, and evidence-backed design choices.",
        "Retrieve evidence for each borrowed component and keep proposed integration choices clearly labelled.",
        ("component", "interface", "design_choice"),
        "system",
        ("define_components", "collect_component_evidence", "compose_interfaces", "verify_design_basis"),
    ),
    "theory_math_formal_proof": _recipe(
        "theory_math_formal_proof",
        "Recover and explain a formal statement, derivation, proof, or mathematical assumption chain from sources.",
        "formal_derivation",
        "Create obligations for assumptions, intermediate steps, and the requested conclusion.",
        "Read formulas and surrounding explanatory text for each formal step.",
        ("assumption", "derivation_step", "conclusion"),
        "derivation",
        ("define_formal_steps", "collect_formula_evidence", "reconstruct_derivation", "verify_steps"),
    ),
    "author_institution_research_style": _recipe(
        "author_institution_research_style",
        "Analyze an author or institution's research trajectory and style over a defined corpus and time window.",
        "research_profile",
        "Create obligations for time periods, themes, methods, and recurring evidence requested by the user.",
        "Resolve authorship metadata, then read representative paper passages for substantive style claims.",
        ("period", "theme", "style"),
        "profile",
        ("define_profile_scope", "collect_representative_evidence", "trace_trajectory", "verify_profile"),
    ),
    "dataset_contamination_generalization": _recipe(
        "dataset_contamination_generalization",
        "Distinguish contamination, leakage, shortcut learning, and genuine distribution shift in evaluation evidence.",
        "generalization_audit",
        "Create obligations for each suspected mechanism and the observations that distinguish them.",
        "Retrieve dataset overlap, split, control, and out-of-distribution evidence before diagnosing a mechanism.",
        ("observation", "mechanism", "diagnosis"),
        "audit",
        ("define_diagnostic_tests", "collect_evaluation_evidence", "distinguish_mechanisms", "verify_diagnosis"),
    ),
    "meta_analysis_systematic_review": _recipe(
        "meta_analysis_systematic_review",
        "Synthesize a defined literature set using explicit inclusion, extraction, quality, and comparison rules.",
        "systematic_review",
        "Create obligations for scope, inclusion rules, extracted outcomes, quality, and synthesis dimensions.",
        "Inspect the complete scoped candidate set and read evidence for every included study.",
        ("study", "outcome", "quality", "synthesis"),
        "review",
        ("define_review_protocol", "collect_included_studies", "extract_outcomes", "verify_synthesis"),
    ),
    "boundary_failure_counterexample": _recipe(
        "boundary_failure_counterexample",
        "Test a broad claim by retrieving failures, negative results, and counterexamples that delimit its valid scope.",
        "boundary_counterexample",
        "Create obligations for the broad claim, counterexamples, and the resulting valid boundary.",
        "Prefer direct failure evidence and avoid treating missing evidence as a counterexample.",
        ("claim", "counterexample", "boundary"),
        "boundary",
        ("define_tested_claim", "collect_failures", "derive_boundary", "verify_counterexamples"),
    ),
    "future_prediction_open_problem": _recipe(
        "future_prediction_open_problem",
        "Identify open problems and make bounded forecasts by separating source evidence, trend inference, and speculation.",
        "evidence_forecast",
        "Create obligations for observed trends, named open problems, assumptions, and forecast horizon.",
        "Retrieve trend and limitation evidence; keep forecasts explicitly conditional.",
        ("trend", "open_problem", "forecast"),
        "forecast",
        ("define_forecast_basis", "collect_trend_evidence", "form_bounded_forecast", "verify_evidence_boundary"),
    ),
    "structured_info_nontraditional_query": _recipe(
        "structured_info_nontraditional_query",
        "Retrieve and interpret tables, figures, formulas, algorithms, appendices, or other structured paper elements.",
        "figure_table_formula_interpretation",
        "Create obligations for the requested structured element, fields, and interpretation.",
        "Search the requested element type and read the structured element plus necessary context.",
        ("structured_value", "interpretation"),
        "structured",
        ("identify_structured_target", "collect_structured_evidence", "extract_values", "verify_interpretation"),
    ),
    "complex_constraint_combination": _recipe(
        "complex_constraint_combination",
        "Find or compare candidates under multiple simultaneous hard and soft constraints without silently relaxing any constraint.",
        "constraint_filter",
        "Create one obligation per hard constraint and separate optional ranking preferences.",
        "Inspect candidates broadly, then read evidence for every surviving candidate-constraint cell.",
        ("constraint_match", "rejection", "gap"),
        "constraint_filter",
        ("define_constraints", "collect_candidate_cells", "intersect_hard_constraints", "verify_no_relaxation"),
    ),
}


PARADIGM_DEFINITIONS = PARADIGM_RECIPES
ParadigmDefinition = ParadigmRecipe


def get_paradigm(paradigm_id: str) -> ParadigmRecipe:
    try:
        return PARADIGM_RECIPES[paradigm_id]
    except KeyError as error:
        raise ValueError(f"unsupported paradigm: {paradigm_id}") from error


def intent_catalog_text() -> str:
    return "\n".join(
        f"- {recipe.paradigm_id}: {recipe.description} Default answer shape: {recipe.default_answer_shape}."
        for recipe in PARADIGM_RECIPES.values()
    )
