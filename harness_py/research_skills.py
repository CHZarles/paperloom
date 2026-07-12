from __future__ import annotations

from dataclasses import asdict, dataclass

from .models import JsonMap


@dataclass(frozen=True)
class ResearchSkill:
    skill_id: str
    when_to_use: str
    instructions: tuple[str, ...]
    evidence_standard: str
    answer_guidance: str

    def to_dict(self) -> JsonMap:
        return asdict(self)


class ResearchSkillRegistry:
    def __init__(self) -> None:
        self.skills = {skill.skill_id: skill for skill in _SKILLS}

    def catalog(self) -> str:
        return "\n".join(
            f"- {skill.skill_id}: {skill.when_to_use}"
            for skill in self.skills.values()
        )

    def get(self, skill_id: str) -> JsonMap:
        skill = self.skills.get(str(skill_id or "").strip())
        if skill is None:
            return {
                "error": "unknown_research_skill",
                "available_skill_ids": sorted(self.skills),
            }
        return skill.to_dict()

    def tool_definition(self) -> JsonMap:
        return {
            "type": "function",
            "function": {
                "name": "get_research_skill",
                "description": "Load detailed guidance for one research paradigm when it helps the current task.",
                "parameters": {
                    "type": "object",
                    "required": ["skill_id"],
                    "properties": {
                        "skill_id": {
                            "type": "string",
                            "enum": sorted(self.skills),
                        }
                    },
                    "additionalProperties": False,
                },
            },
        }


def _skill(
    skill_id: str,
    when_to_use: str,
    instructions: tuple[str, ...],
    evidence_standard: str,
    answer_guidance: str,
) -> ResearchSkill:
    return ResearchSkill(skill_id, when_to_use, instructions, evidence_standard, answer_guidance)


_COMMON_STANDARD = (
    "Use exact passages returned by read_locations for paper-content claims. "
    "Treat candidate metadata and location previews as navigation only, and state evidence gaps plainly."
)


_SKILLS = (
    _skill(
        "precision_fact_extraction",
        "Locate exact facts, values, parameters, roles, or definitions in source papers.",
        ("Identify the requested fact slots and qualifiers.", "Read the smallest passage that covers them.", "Verify values, units, and source identity."),
        _COMMON_STANDARD,
        "Answer with the exact value and its source context.",
    ),
    _skill(
        "methodology_reproduction",
        "Reconstruct a method, experiment, implementation recipe, or reproduction checklist.",
        ("Identify the required procedure and parameter items.", "Prefer the original method paper.", "Separate reported settings from inferred defaults."),
        _COMMON_STANDARD,
        "Use a concise ordered checklist and name any missing settings.",
    ),
    _skill(
        "deep_comparison",
        "Compare papers, methods, or systems over explicit semantic dimensions.",
        ("Choose comparison axes from the request.", "Read evidence for every target-axis cell.", "Preserve asymmetric scope and missing cells."),
        _COMMON_STANDARD,
        "Use a compact comparison table or aligned sections.",
    ),
    _skill(
        "association_influence_genealogy",
        "Trace citations, influences, or research lineage and explain supported relationships.",
        ("Resolve the papers in the lineage.", "Use citation edges for navigation when available.", "Read passages that establish the technical relationship."),
        _COMMON_STANDARD,
        "Present an ordered lineage and explain each supported edge.",
    ),
    _skill(
        "complex_multihop_reasoning",
        "Compose independently evidenced hops whose intermediate conclusions are required.",
        ("Decompose the requested chain into hops.", "Retrieve each hop independently.", "Do not replace missing hops with a plausible direct claim."),
        _COMMON_STANDARD,
        "Show the short evidence-backed chain and its unresolved links.",
    ),
    _skill(
        "uncertainty_knowledge_boundary",
        "Determine what the corpus supports, does not support, or leaves uncertain.",
        ("Test corpus coverage for the requested claim.", "Read the closest relevant evidence when useful.", "Separate supported context from the unsupported boundary."),
        _COMMON_STANDARD,
        "State the supported boundary naturally and offer one useful next action.",
    ),
    _skill(
        "ambiguity_resolution",
        "Resolve a genuinely blocking identity, reference, relation, or constraint ambiguity.",
        ("Identify the single blocking ambiguity.", "Ask one clear question that lets research proceed."),
        "Do not retrieve merely to hide a blocking ambiguity.",
        "Ask one concise, user-understandable clarification question.",
    ),
    _skill(
        "contradiction_resolution",
        "Compare competing source claims and adjudicate or preserve their conflict.",
        ("Read both primary passages.", "Preserve qualifiers such as default, measured, recommended, and conditional.", "Resolve by scope only when the evidence supports it."),
        _COMMON_STANDARD,
        "State both claims, the scope difference, and the resulting conclusion.",
    ),
    _skill(
        "context_specific_brainstorming",
        "Recommend or generate options for a concrete user context.",
        ("Use the user's stated constraints without demanding optional ones.", "Inspect a bounded candidate set.", "Read evidence for why each selected item fits."),
        _COMMON_STANDARD,
        "Return a bounded shortlist with a brief reason for each choice.",
    ),
    _skill(
        "concept_tracing_definition",
        "Define a concept and trace its origin or changes in meaning.",
        ("Identify the definition points requested.", "Read primary definition passages in chronological order.", "Distinguish original meaning from later usage."),
        _COMMON_STANDARD,
        "Use a definition followed by a compact timeline.",
    ),
    _skill(
        "contribution_critical_assessment",
        "Assess claimed contributions, novelty, scope, limitations, and later challenges.",
        ("Read contribution and limitation passages.", "Separate author claims from your assessment.", "Ground criticism in evidence or label it as interpretation."),
        _COMMON_STANDARD,
        "Separate contributions, evidence, limitations, and assessment.",
    ),
    _skill(
        "data_benchmark_provenance",
        "Trace dataset or benchmark construction, filtering, annotation, versions, and evaluation.",
        ("Identify the provenance stages requested.", "Read construction and release passages.", "Preserve version and split distinctions."),
        _COMMON_STANDARD,
        "Present a provenance record or chronological sequence.",
    ),
    _skill(
        "hypothetical_discussion",
        "Analyze a counterfactual while grounding its factual baseline.",
        ("Separate the factual baseline from changed assumptions.", "Retrieve evidence for the baseline.", "Label derived consequences as conditional."),
        _COMMON_STANDARD,
        "Separate evidence, assumptions, and conditional consequences.",
    ),
    _skill(
        "multimodal_cross_domain_system_design",
        "Compose evidence-backed components across modalities or domains into a system design.",
        ("Identify components, interfaces, and constraints.", "Read evidence for borrowed components.", "Label proposed integration choices as design decisions."),
        _COMMON_STANDARD,
        "Describe the system, interfaces, evidence basis, and open design risks.",
    ),
    _skill(
        "theory_math_formal_proof",
        "Recover and explain a formal statement, derivation, proof, or assumption chain.",
        ("Identify assumptions and requested formal steps.", "Read formulas with surrounding explanatory text.", "Do not silently fill missing derivation steps."),
        _COMMON_STANDARD,
        "Present assumptions, steps, conclusion, and any missing proof link.",
    ),
    _skill(
        "author_institution_research_style",
        "Analyze an author or institution's research trajectory over a defined corpus and period.",
        ("Resolve authorship and time scope.", "Read representative papers for substantive style claims.", "Avoid generalizing from metadata alone."),
        _COMMON_STANDARD,
        "Present periods, recurring themes, representative evidence, and caveats.",
    ),
    _skill(
        "dataset_contamination_generalization",
        "Distinguish contamination, leakage, shortcuts, and genuine distribution shift.",
        ("Identify observations that distinguish the mechanisms.", "Read overlap, split, control, and out-of-distribution evidence.", "Keep diagnosis proportional to evidence."),
        _COMMON_STANDARD,
        "Report observations, plausible mechanism, and diagnostic uncertainty.",
    ),
    _skill(
        "meta_analysis_systematic_review",
        "Synthesize a defined literature set using explicit inclusion and comparison rules.",
        ("Define scope and inclusion rules from the request.", "Inspect the complete scoped candidate set.", "Read evidence for each included study and compare consistent outcomes."),
        _COMMON_STANDARD,
        "Report scope, included studies, extracted findings, and synthesis limits.",
    ),
    _skill(
        "boundary_failure_counterexample",
        "Test a broad claim with failures, negative results, and counterexamples.",
        ("State the broad claim precisely.", "Retrieve direct counterexample evidence.", "Do not treat missing evidence as a counterexample."),
        _COMMON_STANDARD,
        "State the claim, counterexamples, and narrower valid boundary.",
    ),
    _skill(
        "future_prediction_open_problem",
        "Identify open problems and make bounded forecasts.",
        ("Separate observed trends, named open problems, assumptions, and forecast horizon.", "Read trend and limitation evidence.", "Keep forecasts explicitly conditional."),
        _COMMON_STANDARD,
        "Separate evidence-backed open problems from bounded speculation.",
    ),
    _skill(
        "structured_info_nontraditional_query",
        "Retrieve and interpret tables, figures, formulas, algorithms, or appendices.",
        ("Identify the exact structured target and fields.", "Use available structured locations and surrounding context.", "State when the current corpus lacks the required modality."),
        _COMMON_STANDARD,
        "Return the extracted structured values and a bounded interpretation.",
    ),
    _skill(
        "complex_constraint_combination",
        "Find or compare candidates under multiple simultaneous hard and soft constraints.",
        ("Separate hard constraints from preferences.", "Inspect candidates broadly enough to test the intersection.", "Never silently relax a hard constraint."),
        _COMMON_STANDARD,
        "Return surviving candidates, constraint evidence, and explicit gaps.",
    ),
)
