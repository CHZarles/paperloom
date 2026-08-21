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
class ValidationIssue:
    code: str
    issue_class: SubmissionIssueClass
    block_ids: tuple[str, ...] = ()
    unknown_source_quote_refs: tuple[str, ...] = ()

    def to_dict(self) -> JsonMap:
        return {
            "code": self.code,
            **({"block_ids": list(self.block_ids)} if self.block_ids else {}),
            **(
                {"unknown_source_quote_refs": list(self.unknown_source_quote_refs)}
                if self.unknown_source_quote_refs else {}
            ),
        }


@dataclass(frozen=True)
class SubmissionValidation:
    issues: tuple[ValidationIssue, ...] = ()

    @property
    def accepted(self) -> bool:
        return not self.issues

    @property
    def issue_class(self) -> SubmissionIssueClass | None:
        if not self.issues:
            return None
        if any(issue.issue_class is SubmissionIssueClass.MISSING_CONTRACT_INPUT for issue in self.issues):
            return SubmissionIssueClass.MISSING_CONTRACT_INPUT
        return SubmissionIssueClass.FORMAT_ISSUE


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
    issues: tuple[ValidationIssue, ...] = ()

    def __post_init__(self) -> None:
        if self.accepted == (self.issue_class is not None):
            raise ValueError("accepted submissions must not have an issue class; rejected submissions must have one")
        if self.accepted and (self.issue_codes or self.issues):
            raise ValueError("accepted submissions must not have validation issues")
        if not self.accepted and not self.issue_codes:
            raise ValueError("rejected submissions must have at least one issue code")


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
SUBMISSION_TOOL_NAMES = frozenset(_SUBMISSION_TOOL_BY_CONTRACT.values())
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
_DIRECT_KINDS = frozenset({"GREETING", "CLARIFICATION", "CAPABILITIES", "OUT_OF_SCOPE"})
_LANGUAGES = frozenset({"ZH_CN", "EN"})
_CATALOG_VIEWS = frozenset({"COUNT", "LIST"})
_CATALOG_FIELDS = frozenset({"title", "authors", "year", "venue", "doi", "arxiv_id"})
_CATALOG_FIELD_LABELS = {
    "ZH_CN": {
        "title": "标题", "authors": "作者", "year": "年份", "venue": "来源", "doi": "DOI", "arxiv_id": "arXiv",
    },
    "EN": {
        "title": "Title", "authors": "Authors", "year": "Year", "venue": "Venue", "doi": "DOI", "arxiv_id": "arXiv",
    },
}
_RESEARCH_OUTCOMES = frozenset({"answered", "partial", "abstained"})
_ABSTENTION_REASONS = frozenset({"NO_MATCHING_PAPER", "NO_SUPPORTING_SOURCE", "OUT_OF_SCOPE"})
_RESEARCH_ABSTENTION_MESSAGES = {
    "ZH_CN": {
        "NO_MATCHING_PAPER": "当前论文库中没有找到与问题匹配的论文。",
        "NO_SUPPORTING_SOURCE": "当前论文库中没有找到足以支持回答的原文证据。",
        "OUT_OF_SCOPE": "这个问题超出了当前论文研究范围。",
    },
    "EN": {
        "NO_MATCHING_PAPER": "No papers matching the question were found in the current corpus.",
        "NO_SUPPORTING_SOURCE": "No source evidence sufficient to answer was found in the current corpus.",
        "OUT_OF_SCOPE": "This question is outside the current paper-research scope.",
    },
}


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
    if len(siblings) != 1 and SUBMISSION_TOOL_NAMES.intersection(siblings):
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
            "issues": [issue.to_dict() for issue in event.issues],
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


def validate_submission(
    contract: AnswerContract,
    payload: JsonMap,
    facts: ProtocolFacts,
) -> SubmissionValidation:
    if contract is AnswerContract.DIRECT:
        return _validate_direct_submission(payload)
    if contract is AnswerContract.CATALOG:
        return _validate_catalog_submission(payload, facts)
    return _validate_research_submission(payload, facts)


def submission_requested(
    contract: AnswerContract,
    payload: JsonMap,
    facts: ProtocolFacts,
) -> SubmissionRequested:
    validation = validate_submission(contract, payload, facts)
    return SubmissionRequested(
        contract=contract,
        payload=payload,
        accepted=validation.accepted,
        issue_class=validation.issue_class,
        issue_codes=tuple(issue.code for issue in validation.issues),
        issues=validation.issues,
    )


def render_direct_submission(payload: JsonMap) -> JsonMap:
    kind = str(payload["kind"])
    language = str(payload["language"])
    if kind == "CLARIFICATION":
        return _normalized_answer("needs_clarification", _single_line(payload["question"]), AnswerContract.DIRECT)
    messages = {
        "ZH_CN": {
            "GREETING": "你好，我可以帮你检索、阅读和比较论文。",
            "CAPABILITIES": (
                "我可以检索论文、阅读原文、比较方法，并基于可追溯证据回答问题。"
            ),
            "OUT_OF_SCOPE": "这个请求不在论文研究范围内。",
        },
        "EN": {
            "GREETING": "Hello. I can help you search, read, and compare papers.",
            "CAPABILITIES": (
                "I can search papers, read source text, compare methods, "
                "and answer with traceable evidence."
            ),
            "OUT_OF_SCOPE": "This request is outside the paper-research scope.",
        },
    }
    return _normalized_answer("answered", messages[language][kind], AnswerContract.DIRECT)


def render_catalog_submission(payload: JsonMap, facts: ProtocolFacts) -> JsonMap:
    result = facts.catalog_results[str(payload["result_ref"])]
    language = str(payload["language"])
    if payload["view"] == "COUNT":
        count = int(result["matched_count"])
        markdown = (
            f"共找到 {count} 篇论文。"
            if language == "ZH_CN"
            else f"Found {count} {'paper' if count == 1 else 'papers'}."
        )
        return _normalized_answer("answered", markdown, AnswerContract.CATALOG)

    papers = [item for item in result["papers"] if isinstance(item, dict)]
    raw_paper_ids = payload.get("paper_ids")
    selected = set(
        raw_paper_ids
        if raw_paper_ids is not None
        else [str(item.get("paper_id") or "") for item in papers]
    )
    fields = list(payload.get("fields") or ["title", "authors", "year"])
    lines = [
        "- " + "; ".join(
            f"{_CATALOG_FIELD_LABELS[language][field]}: {_catalog_value(paper.get(field))}"
            for field in fields
            if _has_catalog_value(paper.get(field))
        )
        for paper in papers
        if str(paper.get("paper_id") or "") in selected
    ]
    if not lines:
        lines = ["未找到论文。" if language == "ZH_CN" else "No papers found."]
    if str(result.get("coverage") or "").casefold() != "complete":
        lines.append("\n仅显示部分结果。" if language == "ZH_CN" else "\nOnly partial results are shown.")
    return _normalized_answer("answered", "\n".join(lines), AnswerContract.CATALOG)


def render_research_submission(payload: JsonMap, facts: ProtocolFacts) -> JsonMap:
    outcome = str(payload["outcome"])
    language = str(payload["language"])
    markdown = (
        _RESEARCH_ABSTENTION_MESSAGES[language][str(payload["abstention_reason"])]
        if outcome == "abstained"
        else str(payload["markdown"])
    )
    return {
        "outcome": outcome,
        "markdown": markdown,
        "fields": dict(payload.get("fields") or {}),
        "cited_source_quote_refs": list(dict.fromkeys(CITATION_RE.findall(markdown))),
        "answer_contract": AnswerContract.RESEARCH.value,
    }


def _validate_direct_submission(payload: JsonMap) -> SubmissionValidation:
    issues = _shape_issues(payload, {"kind", "language", "question"}, {"kind", "language"})
    kind = payload.get("kind")
    language = payload.get("language")
    if not isinstance(kind, str) or kind not in _DIRECT_KINDS:
        issues.append(_format_issue("DIRECT_KIND_INVALID"))
    if not isinstance(language, str) or language not in _LANGUAGES:
        issues.append(_format_issue("LANGUAGE_INVALID"))
    question = payload.get("question")
    if kind == "CLARIFICATION":
        if (
            not isinstance(question, str)
            or not question.strip()
            or len(question) > 500
            or "\n" in question
            or _DOUBLE_BRACKET_MARKER_RE.search(question)
        ):
            issues.append(_format_issue("CLARIFICATION_QUESTION_INVALID"))
    elif question is not None:
        issues.append(_format_issue("DIRECT_QUESTION_NOT_ALLOWED"))
    return SubmissionValidation(tuple(_unique_issues(issues)))


def _validate_catalog_submission(payload: JsonMap, facts: ProtocolFacts) -> SubmissionValidation:
    issues = _shape_issues(
        payload,
        {"result_ref", "view", "paper_ids", "fields", "language"},
        {"result_ref", "view", "language"},
    )
    result_ref = payload.get("result_ref")
    view = payload.get("view")
    if not isinstance(result_ref, str) or not result_ref.strip():
        issues.append(_format_issue("CATALOG_RESULT_REF_INVALID"))
    elif result_ref not in facts.catalog_results:
        issues.append(_missing_issue("UNKNOWN_CATALOG_RESULT_REF"))
    if not isinstance(view, str) or view not in _CATALOG_VIEWS:
        issues.append(_format_issue("CATALOG_VIEW_INVALID"))
    if not isinstance(payload.get("language"), str) or payload.get("language") not in _LANGUAGES:
        issues.append(_format_issue("LANGUAGE_INVALID"))

    paper_ids = payload.get("paper_ids")
    if paper_ids is not None and (
        not isinstance(paper_ids, list)
        or any(not isinstance(value, str) or not value for value in paper_ids)
        or len(set(paper_ids)) != len(paper_ids)
    ):
        issues.append(_format_issue("CATALOG_PAPER_IDS_INVALID"))
    fields = payload.get("fields")
    if fields is not None and (
        not isinstance(fields, list)
        or not fields
        or any(not isinstance(value, str) or value not in _CATALOG_FIELDS for value in fields)
        or len(set(fields)) != len(fields)
    ):
        issues.append(_format_issue("CATALOG_FIELDS_INVALID"))

    if isinstance(result_ref, str) and result_ref in facts.catalog_results:
        result = facts.catalog_results[result_ref]
        papers = result.get("papers")
        if not isinstance(result.get("matched_count"), int) or not isinstance(papers, list):
            raise ValueError("Catalog Result is missing matched_count or papers")
        known_ids = {
            str(item.get("paper_id") or "")
            for item in papers
            if isinstance(item, dict) and item.get("paper_id")
        }
        if isinstance(paper_ids, list) and not set(paper_ids) <= known_ids:
            issues.append(_format_issue("CATALOG_PAPER_ID_NOT_IN_RESULT"))
    if view == "COUNT" and (paper_ids is not None or fields is not None):
        issues.append(_format_issue("CATALOG_COUNT_OPTIONS_NOT_ALLOWED"))
    return SubmissionValidation(tuple(_unique_issues(issues)))


def _validate_research_submission(payload: JsonMap, facts: ProtocolFacts) -> SubmissionValidation:
    issues = _shape_issues(
        payload,
        {"outcome", "language", "markdown", "fields", "abstention_reason"},
        {"outcome", "language"},
    )
    outcome = payload.get("outcome")
    markdown = payload.get("markdown")
    if not isinstance(payload.get("language"), str) or payload.get("language") not in _LANGUAGES:
        issues.append(_format_issue("LANGUAGE_INVALID"))
    if not isinstance(outcome, str) or outcome not in _RESEARCH_OUTCOMES:
        issues.append(_format_issue("RESEARCH_OUTCOME_INVALID"))
        return SubmissionValidation(tuple(_unique_issues(issues)))
    if payload.get("fields") is not None and (
        not isinstance(payload["fields"], dict)
        or any(not isinstance(key, str) or not isinstance(value, str) for key, value in payload["fields"].items())
    ):
        issues.append(_format_issue("RESEARCH_FIELDS_INVALID"))
    if outcome == "abstained":
        if (isinstance(markdown, str) and markdown.strip()) or markdown not in (None, ""):
            issues.append(_format_issue("ABSTENTION_MARKDOWN_NOT_ALLOWED"))
        reason = payload.get("abstention_reason")
        if not isinstance(reason, str) or reason not in _ABSTENTION_REASONS:
            issues.append(_format_issue("ABSTENTION_REASON_INVALID"))
        return SubmissionValidation(tuple(_unique_issues(issues)))
    if payload.get("abstention_reason") is not None:
        issues.append(_format_issue("ABSTENTION_REASON_NOT_ALLOWED"))
    if not isinstance(markdown, str) or not markdown.strip():
        issues.append(_format_issue("RESEARCH_MARKDOWN_REQUIRED"))
        return SubmissionValidation(tuple(_unique_issues(issues)))
    if re.search(r"</?think(?:\s[^>]*)?>", markdown, flags=re.IGNORECASE):
        issues.append(_format_issue("INTERNAL_REASONING_NOT_ALLOWED"))
    if _NUMERIC_CITATION_RE.search(markdown):
        issues.append(_format_issue("NUMERIC_CITATION_NOT_ALLOWED"))
    invalid_markers = sorted({
        marker.strip()
        for marker in _DOUBLE_BRACKET_MARKER_RE.findall(markdown)
        if marker.strip() == "source_quote_ref"
        or not re.fullmatch(r"source_quote_[A-Za-z0-9_-]+", marker.strip())
    })
    if invalid_markers:
        issues.append(_format_issue("INVALID_SOURCE_MARKER"))

    citeable = {
        ref
        for ref, item in facts.known_source_quotes.items()
        if item.get("citeable") is not False and item.get("source_quote_ref") == ref
    }
    unknown = tuple(sorted(set(CITATION_RE.findall(markdown)) - citeable - set(invalid_markers)))
    if unknown:
        issues.append(ValidationIssue(
            "UNKNOWN_SOURCE_REF",
            SubmissionIssueClass.MISSING_CONTRACT_INPUT,
            unknown_source_quote_refs=unknown,
        ))
    blocks, _ = answer_blocks({"markdown": markdown})
    content_blocks = [
        block for block in blocks
        if block.get("kind") not in NON_MATERIAL_UNCITED_BLOCK_KINDS
        and str(block.get("text") or "").strip()
    ]
    if not content_blocks:
        issues.append(_format_issue("RESEARCH_CONTENT_REQUIRED"))
    marker_syntax_invalid = bool(invalid_markers or _NUMERIC_CITATION_RE.search(markdown))
    uncited = () if marker_syntax_invalid else tuple(
        str(block.get("block_id") or "")
        for block in content_blocks
        if not block.get("evidence_ids")
    )
    if uncited:
        issues.append(ValidationIssue(
            "UNCITED_CONTENT_BLOCK",
            SubmissionIssueClass.MISSING_CONTRACT_INPUT,
            block_ids=uncited,
        ))
    if not citeable:
        issues.append(_missing_issue("NO_KNOWN_SOURCE_QUOTE"))
    return SubmissionValidation(tuple(_unique_issues(issues)))


def _shape_issues(payload: JsonMap, allowed: set[str], required: set[str]) -> list[ValidationIssue]:
    issues: list[ValidationIssue] = []
    if set(payload) - allowed:
        issues.append(_format_issue("SUBMISSION_FIELDS_UNKNOWN"))
    if required - set(payload):
        issues.append(_format_issue("SUBMISSION_FIELDS_MISSING"))
    return issues


def _format_issue(code: str) -> ValidationIssue:
    return ValidationIssue(code, SubmissionIssueClass.FORMAT_ISSUE)


def _missing_issue(code: str) -> ValidationIssue:
    return ValidationIssue(code, SubmissionIssueClass.MISSING_CONTRACT_INPUT)


def _unique_issues(issues: list[ValidationIssue]) -> list[ValidationIssue]:
    return list({issue.code: issue for issue in issues}.values())


def _normalized_answer(outcome: str, markdown: str, contract: AnswerContract) -> JsonMap:
    return {
        "outcome": outcome,
        "markdown": markdown,
        "fields": {},
        "cited_source_quote_refs": [],
        "answer_contract": contract.value,
    }


def _catalog_value(value: object) -> str:
    if isinstance(value, list):
        return ", ".join(_single_line(item) for item in value)
    return _single_line(value)


def _has_catalog_value(value: object) -> bool:
    return value is not None and value != "" and value != []


def _single_line(value: object) -> str:
    return (
        " ".join(str(value).split())
        .replace("\\", "\\\\")
        .replace("[", "\\[")
        .replace("]", "\\]")
    )


def research_agent_instructions(skills: ResearchSkillRegistry) -> str:
    return (
        "You are a paper-research agent operating in one continuous ReAct loop. Trust the conversation history: "
        "decide whether to answer directly, ask one useful clarification, research, continue searching, combine "
        "research skills, or abstain. There is no fixed stage sequence and no research-round limit.\n\n"
        "Your first priority is to answer the user's actual question clearly and accurately with the least research "
        "and detail needed for a complete, evidence-backed answer. Match the depth and structure to the user's "
        "wording and conversation context. If no depth is requested, prefer the shortest complete answer. An "
        "introductory definition question such as 'what is X?' or '什么是 X' with no explicit request for detail "
        "needs only one or two short paragraphs: define the concept, state its core mechanism, and optionally give "
        "one useful contrast or example. Do not include headings, equations, paper history, applications, surveys, "
        "or neighboring techniques in that answer. Retrieved evidence constrains what you may claim; it does not "
        "determine which topics belong in the answer. Stop researching once the evidence directly supports the "
        "requested scope; related material is not a reason to broaden either the search or the answer. Answer depth "
        "and evidence contract are independent: A short factual technical definition still uses RESEARCH, reads the "
        "minimum exact evidence, and finishes with submit_research_answer. Least research never permits an unsupported "
        "substantive answer through submit_direct_answer.\n\n"
        "Resolve references such as 'this paper', 'that paper', 'the previous conclusion', or their Chinese "
        "equivalents only when the conversation names exactly one paper or the current research memory has exactly "
        "one selected paper. If the reference has no unique antecedent, treat it as a blocking ambiguity: use "
        "submit_direct_answer with one CLARIFICATION asking which paper the user means. Do not search the whole "
        "corpus and guess an identity from a venue, year, answer fragment, or general knowledge.\n\n"
        "Keep ordinary conversation natural and concise. For a greeting, use submit_direct_answer. If a recommendation "
        "request is missing only its topic, submit one CLARIFICATION asking what topic to focus on; "
        "do not demand optional purpose, venue, year, or paper-type constraints. A recommendation request with a "
        "stated topic is not missing a blocking input: research it instead of asking for optional preferences.\n\n"
        "Choose CATALOG only when every requested output is a corpus count or one of these metadata fields: title, "
        "authors, year, venue, DOI, or arXiv ID. If any requested output requires judging paper content, including "
        "methods, findings, relevance, importance, comparisons, or reasons for a recommendation, choose RESEARCH and "
        "read source evidence. In particular, recommending papers and explaining why is RESEARCH, not CATALOG.\n\n"
        "Paper cards and identity results are authoritative for corpus metadata such as paper count, title, author, "
        "year, venue, and identifiers. Answer corpus inventory and filtering questions with submit_catalog_answer "
        "using the paper_result_ref returned by the current-turn discovery result "
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
        "If retrieved notation appears malformed, inconsistent, or unexplained, state only the supported idea in "
        "words instead of reproducing an uncertain equation. Never present paper-specific notation as a universal definition. "
        "Never substitute adjacent papers when the corpus lacks the requested topic; state the gap plainly. "
        "After a rejected final submission, correct every issue named by the validator in the next submission, "
        "preserve parts that already answer the user well, and make a proportional correction. Prefer citing, "
        "narrowing, or deleting an unsupported claim. For a citation-only rejection, do not add sections or topics; "
        "keep the same answer scope while adding evidence or removing unsupported text. Rewrite or expand other parts "
        "only when necessary to restore correctness or coherence; do not introduce new topics merely to satisfy "
        "citation validation. "
        "Do not reload a research skill already used. Reuse existing evidence, and call another corpus tool only "
        "when the correction needs evidence that is not already present.\n\n"
        "When you are ready to finish the turn: Do not return Markdown as assistant text. Call exactly one "
        "submission tool as the only response and put user-facing answer text only in that tool's arguments. Use "
        "submit_direct_answer only for a greeting, one blocking clarification, capabilities, or an out-of-scope request. "
        "Use submit_catalog_answer only for counts and metadata-only lists from a current paper_result_ref. Use "
        "submit_research_answer for every paper-content judgment; put ANSWERED or PARTIAL text in markdown, or use a "
        "structured ABSTAINED reason when the corpus cannot support an answer. Select ZH_CN or EN from the conversation; "
        "the runtime does not infer the response language. Do not expose internal skills, tool names, "
        "schemas, statuses, reasoning traces, evidence-id syntax, or validation rules in the user-facing answer.\n\n"
        "AVAILABLE RESEARCH SKILLS\n"
        f"{skills.catalog()}"
    )


def direct_answer_tool_definition() -> JsonMap:
    return _submission_tool_definition(
        DIRECT_FINAL_TOOL_NAME,
        "Submit a greeting, one blocking clarification, a capability response, or an out-of-scope response.",
        {
            "type": "object",
            "required": ["kind", "language"],
            "properties": {
                "kind": {"type": "string", "enum": sorted(_DIRECT_KINDS)},
                "language": {"type": "string", "enum": sorted(_LANGUAGES)},
                "question": {"type": "string", "maxLength": 500},
            },
            "additionalProperties": False,
        },
    )


def catalog_answer_tool_definition() -> JsonMap:
    return _submission_tool_definition(
        CATALOG_FINAL_TOOL_NAME,
        "Submit only a corpus count or metadata-only list. Do not use for recommendations with reasons or any "
        "judgment about paper content.",
        {
            "type": "object",
            "required": ["result_ref", "view", "language"],
            "properties": {
                "result_ref": {"type": "string"},
                "view": {"type": "string", "enum": sorted(_CATALOG_VIEWS)},
                "paper_ids": {"type": "array", "items": {"type": "string"}},
                "fields": {"type": "array", "items": {"type": "string", "enum": sorted(_CATALOG_FIELDS)}},
                "language": {"type": "string", "enum": sorted(_LANGUAGES)},
            },
            "additionalProperties": False,
        },
    )


def research_answer_tool_definition() -> JsonMap:
    return _submission_tool_definition(
        FINAL_TOOL_NAME,
        "Submit a source-bound paper-content judgment that answers only the user's requested scope and matches the "
        "requested depth, including recommendations with reasons, or a partial answer or structured abstention. "
        "Retrieved related material is not a reason to add unrequested sections.",
        {
            "type": "object",
            "required": ["outcome", "language"],
            "properties": {
                "outcome": {"type": "string", "enum": sorted(_RESEARCH_OUTCOMES)},
                "language": {"type": "string", "enum": sorted(_LANGUAGES)},
                "markdown": {"type": "string", "maxLength": 16000},
                "fields": {"type": "object", "additionalProperties": {"type": "string"}},
                "abstention_reason": {"type": "string", "enum": sorted(_ABSTENTION_REASONS)},
            },
            "additionalProperties": False,
        },
    )


def _submission_tool_definition(name: str, description: str, parameters: JsonMap) -> JsonMap:
    return {
        "type": "function",
        "function": {
            "name": name,
            "description": description,
            "parameters": parameters,
        },
    }
