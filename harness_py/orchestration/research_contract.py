"""Research Agent instructions and the deterministic final-answer contract."""

from __future__ import annotations

import re
from collections.abc import Mapping
from dataclasses import dataclass, field, replace
from enum import Enum

from ..utils.models import JsonMap
from ..utils.answer_blocks import NON_MATERIAL_UNCITED_BLOCK_KINDS, answer_blocks
from .research_skills import ResearchSkillRegistry


CITATION_RE = re.compile(r"\[\[(source_quote_[A-Za-z0-9_-]+)\]\]")
_DOUBLE_BRACKET_MARKER_RE = re.compile(r"\[\[([^\]]+)]]")
_NUMERIC_CITATION_RE = re.compile(r"(?<!\[)\[(\d+)\]")
FINAL_TOOL_NAME = "submit_research_answer"
DIRECT_FINAL_TOOL_NAME = "submit_direct_answer"
CATALOG_FINAL_TOOL_NAME = "submit_catalog_answer"


class AnswerContract(str, Enum):
    DIRECT = "DIRECT"
    CATALOG = "CATALOG"
    RESEARCH = "RESEARCH"


class Phase(str, Enum):
    ACTIVE = "ACTIVE"
    REPAIR = "REPAIR"
    COMPLETE = "COMPLETE"


class SubmissionIssueClass(str, Enum):
    FORMAT_ISSUE = "FORMAT_ISSUE"
    MISSING_CONTRACT_INPUT = "MISSING_CONTRACT_INPUT"


@dataclass(frozen=True)
class ProtocolState:
    phase: Phase = Phase.ACTIVE
    contract: AnswerContract | None = None
    submission_attempt: int = 0
    validation_issues: tuple[str, ...] = ()


@dataclass(frozen=True)
class ProtocolFacts:
    known_source_quotes: Mapping[str, JsonMap] = field(default_factory=dict)
    catalog_results: Mapping[str, JsonMap] = field(default_factory=dict)
    sibling_tool_names: tuple[str, ...] = ()


@dataclass(frozen=True)
class ActionRequested:
    tool_name: str


@dataclass(frozen=True)
class SubmissionRequested:
    contract: AnswerContract
    payload: JsonMap
    accepted: bool
    issue_class: SubmissionIssueClass | None = None
    issue_codes: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if self.accepted == (self.issue_class is not None):
            raise ValueError("accepted submissions must not have an issue class; rejected submissions must have one")


ProtocolEvent = ActionRequested | SubmissionRequested


@dataclass(frozen=True)
class ProtocolDecision:
    next_state: ProtocolState
    accepted_answer: JsonMap | None
    model_result: JsonMap


_SUBMISSION_TOOL_BY_CONTRACT = {
    AnswerContract.DIRECT: DIRECT_FINAL_TOOL_NAME,
    AnswerContract.CATALOG: CATALOG_FINAL_TOOL_NAME,
    AnswerContract.RESEARCH: FINAL_TOOL_NAME,
}
_SUBMISSION_TOOL_NAMES = frozenset(_SUBMISSION_TOOL_BY_CONTRACT.values())
_CATALOG_TOOL_NAMES = frozenset({
    "search_paper_candidates",
    "find_papers_by_identity",
    CATALOG_FINAL_TOOL_NAME,
})
_RESEARCH_TOOL_NAMES = frozenset({
    "get_research_skill",
    "search_paper_candidates",
    "find_papers_by_identity",
    "search_paper_content",
    "get_paper_structure",
    "read_paper_content",
    "get_citation_edges",
    FINAL_TOOL_NAME,
})
_INITIAL_TOOL_NAMES = _CATALOG_TOOL_NAMES | _RESEARCH_TOOL_NAMES | {DIRECT_FINAL_TOOL_NAME}


def allowed_tool_names(state: ProtocolState) -> frozenset[str]:
    if state.phase is Phase.COMPLETE:
        return frozenset()
    if state.phase is Phase.REPAIR:
        return frozenset({_SUBMISSION_TOOL_BY_CONTRACT[state.contract]}) if state.contract else frozenset()
    if state.contract is AnswerContract.CATALOG:
        return _CATALOG_TOOL_NAMES
    if state.contract is AnswerContract.RESEARCH:
        return _RESEARCH_TOOL_NAMES
    if state.contract is AnswerContract.DIRECT:
        return frozenset({DIRECT_FINAL_TOOL_NAME})
    return _INITIAL_TOOL_NAMES


def decide(
    state: ProtocolState,
    event: ProtocolEvent,
    facts: ProtocolFacts,
) -> ProtocolDecision:
    """Return the deterministic protocol decision for one requested action."""

    if isinstance(event, ActionRequested):
        return _decide_action(state, event, facts)
    return _decide_submission(state, event)


def _decide_action(
    state: ProtocolState,
    event: ActionRequested,
    facts: ProtocolFacts,
) -> ProtocolDecision:
    siblings = facts.sibling_tool_names or (event.tool_name,)
    if len(siblings) != 1 and _SUBMISSION_TOOL_NAMES.intersection(siblings):
        return _protocol_error(state, "SUBMISSION_TOOL_GROUP_INVALID")
    if event.tool_name not in allowed_tool_names(state):
        return _protocol_error(state, "ACTION_NOT_ALLOWED")
    return ProtocolDecision(
        next_state=state,
        accepted_answer=None,
        model_result={"accepted": True},
    )


def _decide_submission(
    state: ProtocolState,
    event: SubmissionRequested,
) -> ProtocolDecision:
    if state.phase is Phase.COMPLETE:
        return _protocol_error(state, "ACTION_NOT_ALLOWED")
    if state.contract is not None and state.contract is not event.contract:
        return _protocol_error(state, "CONTRACT_MISMATCH")
    if _SUBMISSION_TOOL_BY_CONTRACT[event.contract] not in allowed_tool_names(state):
        return _protocol_error(state, "ACTION_NOT_ALLOWED")

    attempt = state.submission_attempt + 1
    if event.accepted:
        next_state = ProtocolState(
            phase=Phase.COMPLETE,
            contract=event.contract,
            submission_attempt=attempt,
        )
        return ProtocolDecision(
            next_state=next_state,
            accepted_answer=event.payload,
            model_result={"accepted": True, "contract": event.contract.value},
        )

    phase = (
        Phase.ACTIVE
        if event.issue_class is SubmissionIssueClass.MISSING_CONTRACT_INPUT
        and event.contract is not AnswerContract.DIRECT
        else Phase.REPAIR
    )
    next_state = ProtocolState(
        phase=phase,
        contract=event.contract,
        submission_attempt=attempt,
        validation_issues=event.issue_codes,
    )
    return ProtocolDecision(
        next_state=next_state,
        accepted_answer=None,
        model_result={
            "accepted": False,
            "error_code": "FINAL_SUBMISSION_REJECTED",
            "contract": event.contract.value,
            "issue_class": event.issue_class.value,
            "issue_codes": list(event.issue_codes),
            "allowed_next_actions": sorted(allowed_tool_names(next_state)),
        },
    )


def _protocol_error(state: ProtocolState, issue_code: str) -> ProtocolDecision:
    return ProtocolDecision(
        next_state=replace(state, validation_issues=(issue_code,)),
        accepted_answer=None,
        model_result={
            "accepted": False,
            "error_code": "PROTOCOL_ERROR",
            "issue_codes": [issue_code],
            "allowed_next_actions": sorted(allowed_tool_names(state)),
        },
    )


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
        "If paper discovery returns no candidates, retry once using only the requested paper title as query_text and "
        "omit inferred author, venue, and year filters. Do not claim that a paper is absent from the corpus until this "
        "unconstrained title search also returns no candidates.\n\n"
        "Use get_research_skill when a paradigm playbook would help. Skills are guidance, not gates, and may be "
        "combined. Candidate metadata and navigation previews are not citeable as paper content. Read exact locations "
        "before making paper-content claims. A citation does not license related general knowledge: every factual "
        "sentence, comparison, default value, and causal explanation must be directly entailed by a cited span_text. "
        "Cite with the exact syntax [[source_quote_...]], replacing it with an actual source_quote_ref returned by "
        "read_paper_content in this run. Never write the placeholder [[source_quote_ref]], numeric "
        "citations, or a Sources section yourself; the harness renders those from evidence ids. Put each evidence "
        "marker in the same Markdown paragraph, list item, or table row as the factual claim it supports; a citation "
        "after a list does not support the preceding items. For an exact-fact request, prefer one complete cited "
        "sentence containing the subject and all requested values, without extra rationale. Use Markdown headings "
        "for structural labels; do not put a standalone bold-only label in its own paragraph. "
        "Never substitute adjacent papers when the corpus lacks the requested topic; state the gap plainly. "
        "After a rejected final submission, correct every issue named by the validator in the next submission. "
        "Do not reload a research skill already used. Reuse existing evidence, and call another corpus tool only "
        "when the correction needs evidence that is not already present.\n\n"
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
    *,
    require_content_citations: bool = False,
) -> str:
    outcome = str(final.get("outcome") or "")
    markdown = final.get("markdown")
    if outcome not in {"answered", "needs_clarification", "partial", "abstained"}:
        return "invalid outcome"
    if not isinstance(markdown, str) or not markdown.strip():
        return "markdown is required"
    if re.search(r"</?think(?:\s[^>]*)?>", markdown, flags=re.IGNORECASE):
        return "remove internal reasoning from markdown"
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
    if require_content_citations and outcome in {"answered", "partial"}:
        if not citeable:
            return "read_paper_content is required before answering paper-content claims"
        blocks, _ = answer_blocks({"markdown": markdown})
        uncited = [
            block
            for block in blocks
            if block.get("kind") not in NON_MATERIAL_UNCITED_BLOCK_KINDS
            and not block.get("evidence_ids")
        ]
        if uncited:
            return "paper-content answer blocks require citations: " + "; ".join(
                f"{block.get('block_id')} {block.get('kind')}: {str(block.get('text') or '')[:200]}"
                for block in uncited
            )
    if final.get("fields") is not None and not isinstance(final.get("fields"), dict):
        return "fields must be an object"
    return ""
