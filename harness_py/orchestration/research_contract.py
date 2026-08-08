"""Research Agent instructions and the deterministic final-answer contract."""

from __future__ import annotations

import re
from ..utils.models import JsonMap
from .research_skills import ResearchSkillRegistry


CITATION_RE = re.compile(r"\[\[(source_quote_[A-Za-z0-9_-]+)\]\]")
_DOUBLE_BRACKET_MARKER_RE = re.compile(r"\[\[([^\]]+)]]")
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
        "or technical contributions. Before calling search_paper_content, get_paper_structure, or read_paper_content, you must first call "
        "search_paper_candidates or find_papers_by_identity in the current turn to obtain valid paper_ids; pass only "
        "the paper_id values returned by those discovery tools. Filenames such as paper_2018.pdf are not paper_ids "
        "and will be rejected. Without that prerequisite call, every read attempt returns "
        "paper_not_authorized_for_reading, so the answer cannot cite any paper content.\n\n"
        "Use get_research_skill when a paradigm playbook would help. Skills are guidance, not gates, and may be "
        "combined. Candidate metadata and navigation previews are not citeable as paper content. Read exact locations "
        "before making paper-content claims. A citation does not license related general knowledge: every factual "
        "sentence, comparison, default value, and causal explanation must be directly entailed by a cited span_text. "
        "Cite with the exact syntax [[source_quote_...]], replacing it with an actual source_quote_ref returned by "
        "read_paper_content in this run. Never write the placeholder [[source_quote_ref]], numeric "
        "citations, or a Sources section yourself; the harness renders those from evidence ids. Put each evidence "
        "marker in the same Markdown paragraph, list item, or table row as the factual claim it supports; a citation "
        "after a list does not support the preceding items. For an exact-fact request, prefer one complete cited "
        "sentence containing the subject and all requested values, without extra rationale. "
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
                            "Natural user-facing answer. Cite paper-content evidence when the answer relies on it. "
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
) -> str:
    outcome = str(final.get("outcome") or "")
    markdown = final.get("markdown")
    if outcome not in {"answered", "needs_clarification", "partial", "abstained"}:
        return "invalid outcome"
    if not isinstance(markdown, str) or not markdown.strip():
        return "markdown is required"
    if _NUMERIC_CITATION_RE.search(markdown):
        return "use exact [[source_quote_...]] markers instead of numeric citations or a manually written Sources section"
    invalid_markers = sorted({
        marker.strip()
        for marker in _DOUBLE_BRACKET_MARKER_RE.findall(markdown)
        if not re.fullmatch(r"source_quote_[A-Za-z0-9_-]+", marker.strip())
    })
    if invalid_markers:
        return "invalid evidence markers: " + ", ".join(f"[[{marker}]]" for marker in invalid_markers)
    cited = set(CITATION_RE.findall(markdown))
    citeable = {
        source_quote_ref for source_quote_ref, item in known_evidence.items()
        if item.get("citeable") is not False and item.get("source_quote_ref") == source_quote_ref
    }
    unknown = sorted(cited - citeable)
    if unknown:
        return "unknown cited source quote refs: " + ", ".join(unknown)
    if final.get("fields") is not None and not isinstance(final.get("fields"), dict):
        return "fields must be an object"
    return ""
