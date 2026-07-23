from __future__ import annotations

"""Research Agent instructions and the deterministic final-answer contract."""

import re

from ..core.answer_blocks import uncited_material_blocks
from ..core.models import JsonMap
from .research_skills import ResearchSkillRegistry


CITATION_RE = re.compile(r"\[\[(ev_[A-Za-z0-9_-]+)\]\]")
_NUMERIC_CITATION_RE = re.compile(r"(?<!\[)\[(\d+)\]")
FINAL_TOOL_NAME = "submit_research_answer"


def research_agent_instructions(skills: ResearchSkillRegistry) -> str:
    return (
        "You are a paper-research agent operating in one continuous ReAct loop. Trust the conversation history: "
        "decide whether to answer directly, ask one useful clarification, research, continue searching, combine "
        "research skills, or abstain. There is no fixed stage sequence and no research-round limit.\n\n"
        "Keep ordinary conversation natural and concise. For a greeting, respond briefly. If a recommendation "
        "request is missing only its topic, use outcome=needs_clarification and ask only what topic to focus on; "
        "do not demand optional purpose, venue, year, or paper-type constraints.\n\n"
        "Paper cards and identity results are authoritative for corpus metadata such as paper count, title, author, "
        "year, venue, and identifiers. Answer corpus inventory and filtering questions directly from those results "
        "without paper-content citations. Before submitting any corpus count, list, or metadata-filter answer, you must "
        "call search_paper_candidates in the current turn; use an empty query and a sufficiently large limit for a "
        "complete inventory. Never reconstruct paper titles from conversation history or general knowledge. A previous "
        "assistant refusal is not proof that a request is impossible; use the available tool again when the current "
        "follow-up can be answered. Metadata does not support claims about methods, findings, performance, importance, "
        "or technical contributions.\n\n"
        "Use get_research_skill when a paradigm playbook would help. Skills are guidance, not gates, and may be "
        "combined. Candidate metadata and navigation previews are not citeable as paper content. Read exact locations "
        "before making paper-content claims. A citation does not license related general knowledge: every factual "
        "sentence, comparison, default value, and causal explanation must be directly entailed by a cited span_text. "
        "Cite with the exact syntax [[evidence_id]] and cite only evidence returned by read_locations or supplied as "
        "previous evidence. Never write numeric citations or a Sources section yourself; the harness renders those "
        "from evidence ids. For an exact-fact request, give the requested facts and source without extra rationale. "
        "Never substitute adjacent papers when the corpus lacks the requested topic; state the gap plainly.\n\n"
        "When you are ready to finish the turn, call submit_research_answer as the only tool call. Put all text the "
        "user should see in markdown. Use needs_clarification only for a genuinely blocking question. Use partial or "
        "abstained when the corpus cannot fully support the request. Do not expose internal skills, tool names, "
        "schemas, statuses, reasoning traces, evidence-id syntax, or validation rules in the user-facing answer.\n\n"
        "AVAILABLE RESEARCH SKILLS\n"
        f"{skills.catalog()}"
    )


def final_answer_tool_definition() -> JsonMap:
    return {
        "type": "function",
        "function": {
            "name": FINAL_TOOL_NAME,
            "description": "Submit the final user-visible response and finish the current conversation turn.",
            "parameters": {
                "type": "object",
                "required": ["outcome", "markdown"],
                "properties": {
                    "outcome": {
                        "type": "string",
                        "enum": ["answered", "needs_clarification", "partial", "abstained"],
                    },
                    "markdown": {
                        "type": "string",
                        "maxLength": 16000,
                        "description": (
                            "Natural user-facing answer. Paper-content claims must be supported by cited passages. "
                            "Corpus metadata may be answered directly from paper cards without citations. Do not add "
                            "defaults, comparisons, or causal explanations from general knowledge."
                        ),
                    },
                    "fields": {
                        "type": "object",
                        "additionalProperties": {"type": "string"},
                    },
                },
                "additionalProperties": False,
            },
        },
    }


def answer_validation_error(
    final: JsonMap,
    known_evidence: dict[str, JsonMap],
    used_paper_evidence: bool,
) -> str:
    outcome = str(final.get("outcome") or "")
    markdown = final.get("markdown")
    if outcome not in {"answered", "needs_clarification", "partial", "abstained"}:
        return "invalid outcome"
    if not isinstance(markdown, str) or not markdown.strip():
        return "markdown is required"
    if _NUMERIC_CITATION_RE.search(markdown):
        return "use [[evidence_id]] markers instead of numeric citations or a manually written Sources section"
    cited = set(CITATION_RE.findall(markdown))
    citeable = {
        evidence_id for evidence_id, item in known_evidence.items()
        if item.get("citeable") is not False
    }
    unknown = sorted(cited - citeable)
    if unknown:
        return "unknown cited evidence ids: " + ", ".join(unknown)
    if used_paper_evidence and outcome in {"answered", "partial"} and not cited:
        return "paper-content evidence was read, so cite a returned evidence item; do not mention this validation rule"
    if outcome in {"answered", "partial"} and (used_paper_evidence or cited):
        uncited_blocks = uncited_material_blocks({"markdown": markdown})
        if uncited_blocks:
            first = uncited_blocks[0]
            preview = str(first.get("text") or "")[:160]
            return (
                "every factual paragraph, list item, and table row must contain its own citation; "
                f"uncited {first.get('kind')} block: {preview!r}; attach a returned evidence id "
                "to that block or remove it"
            )
    if final.get("fields") is not None and not isinstance(final.get("fields"), dict):
        return "fields must be an object"
    return ""
