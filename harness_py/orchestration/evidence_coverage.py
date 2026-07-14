from __future__ import annotations

"""Deterministic evidence-coverage checks for final research answers."""

import re
import unicodedata
from dataclasses import dataclass

from ..core.models import JsonMap, as_list, child_map
from .research_contract import CITATION_RE


CONTENT_RESEARCH_TOOLS = {"find_reading_locations", "read_locations"}
WEAK_EVIDENCE_TYPES = {"heading"}
CONTENT_CLAIM_RE = re.compile(
    r"\b(?:method|approach|architecture|finding|result|performance|score|accuracy|"
    r"success rate|use|uses|using|designed|built|evaluate|evaluates|evaluation|task|"
    r"dataset|environment|require|requires|fix|fixes|fixing|resolve|resolves|patch|test|tests)\b"
)


@dataclass(frozen=True)
class CoverageDecision:
    required_paper_ids: tuple[str, ...] = ()
    missing_candidate_paper_ids: tuple[str, ...] = ()
    missing_read_paper_ids: tuple[str, ...] = ()
    missing_cited_paper_ids: tuple[str, ...] = ()
    weak_only_paper_ids: tuple[str, ...] = ()

    @property
    def accepted(self) -> bool:
        return not any((
            self.missing_candidate_paper_ids,
            self.missing_read_paper_ids,
            self.missing_cited_paper_ids,
            self.weak_only_paper_ids,
        ))


def evaluate_evidence_coverage(
    *,
    draft: JsonMap,
    question: str,
    paper_records_by_id: dict[str, JsonMap],
    trace: list[JsonMap],
    known_evidence: dict[str, JsonMap],
) -> CoverageDecision:
    """Return only coverage gaps that can be proven from this run's state."""

    outcome = str(draft.get("outcome") or "")
    if outcome not in {"answered", "partial"}:
        return CoverageDecision()
    used_content_tool = any(
        str(item.get("tool_name") or "") in CONTENT_RESEARCH_TOOLS
        for item in trace
    )
    if not used_content_tool and not _contains_content_claim(
        str(draft.get("markdown") or ""),
        paper_records_by_id,
    ):
        return CoverageDecision()

    aliases = _paper_aliases(paper_records_by_id)
    markdown = str(draft.get("markdown") or "")
    mentioned_in_answer = _mentioned_papers(markdown, aliases)
    engaged, candidates, read = _research_state(trace, known_evidence)
    required = set(mentioned_in_answer)
    if outcome == "answered":
        required.update(_mentioned_papers(question, aliases) & engaged)
    if not required:
        return CoverageDecision()

    cited_ids = set(CITATION_RE.findall(markdown))
    cited_by_paper: dict[str, list[JsonMap]] = {}
    for evidence_id in cited_ids:
        item = child_map(known_evidence.get(evidence_id))
        paper_id = str(item.get("paper_id") or "")
        if paper_id:
            cited_by_paper.setdefault(paper_id, []).append(item)

    # Previously retained evidence is already past Candidate and Read, even when
    # those actions happened in an earlier conversation turn.
    candidates.update(read)
    missing_candidate: list[str] = []
    missing_read: list[str] = []
    missing_cited: list[str] = []
    weak_only: list[str] = []
    for paper_id in sorted(required):
        if paper_id not in candidates:
            missing_candidate.append(paper_id)
            continue
        if paper_id not in read:
            missing_read.append(paper_id)
            continue
        cited_items = cited_by_paper.get(paper_id, [])
        if not cited_items:
            missing_cited.append(paper_id)
            continue
        if not any(_is_substantive(item) for item in cited_items):
            weak_only.append(paper_id)

    return CoverageDecision(
        required_paper_ids=tuple(sorted(required)),
        missing_candidate_paper_ids=tuple(missing_candidate),
        missing_read_paper_ids=tuple(missing_read),
        missing_cited_paper_ids=tuple(missing_cited),
        weak_only_paper_ids=tuple(weak_only),
    )


def coverage_validation_error(
    decision: CoverageDecision,
    paper_records_by_id: dict[str, JsonMap],
) -> str:
    if decision.accepted:
        return ""
    messages: list[str] = []
    if decision.missing_candidate_paper_ids:
        messages.append(
            "unsupported paper mentions have no reading-location candidate: "
            + _paper_names(decision.missing_candidate_paper_ids, paper_records_by_id)
            + "; remove them if they are not essential to the user's request, otherwise search those papers for relevant evidence"
        )
    if decision.missing_read_paper_ids:
        messages.append(
            "paper mentions have reading-location candidates but no read evidence: "
            + _paper_names(decision.missing_read_paper_ids, paper_records_by_id)
            + "; remove nonessential mentions, otherwise call read_locations before answering"
        )
    if decision.missing_cited_paper_ids:
        messages.append(
            "evidence was read but the final answer does not cite evidence from "
            + _paper_names(decision.missing_cited_paper_ids, paper_records_by_id)
            + "; cite a returned evidence id or remove/qualify those claims"
        )
    if decision.weak_only_paper_ids:
        messages.append(
            "the final answer cites only heading/navigation evidence for "
            + _paper_names(decision.weak_only_paper_ids, paper_records_by_id)
            + "; read and cite substantive text, table, figure, or other content evidence"
        )
    return "evidence coverage incomplete: " + "; ".join(messages)


def mentioned_paper_ids(
    text: str,
    paper_records_by_id: dict[str, JsonMap],
) -> set[str]:
    """Return high-confidence paper mentions using the coverage policy aliases."""

    return _mentioned_papers(text, _paper_aliases(paper_records_by_id))


def _research_state(
    trace: list[JsonMap],
    known_evidence: dict[str, JsonMap],
) -> tuple[set[str], set[str], set[str]]:
    engaged: set[str] = set()
    candidates: set[str] = set()
    read: set[str] = {
        str(item.get("paper_id") or "")
        for raw in known_evidence.values()
        if (item := child_map(raw)).get("paper_id")
    }
    for event in trace:
        name = str(event.get("tool_name") or "")
        arguments = child_map(event.get("arguments"))
        result = child_map(event.get("result"))
        if name == "search_paper_candidates":
            for raw in as_list(result.get("candidates")):
                paper_id = str(child_map(raw).get("paper_id") or "")
                if paper_id:
                    engaged.add(paper_id)
        elif name == "find_papers_by_identity":
            for key in ("matches", "papers"):
                for raw in as_list(result.get(key)):
                    paper_id = str(child_map(raw).get("paper_id") or "")
                    if paper_id:
                        engaged.add(paper_id)
        elif name == "find_reading_locations":
            engaged.update(str(value) for value in as_list(arguments.get("paper_ids")) if value)
            for raw in as_list(result.get("locations")):
                paper_id = str(child_map(raw).get("paper_id") or "")
                if paper_id:
                    engaged.add(paper_id)
                    candidates.add(paper_id)
        elif name == "read_locations":
            for raw in as_list(result.get("items")):
                paper_id = str(child_map(raw).get("paper_id") or "")
                if paper_id:
                    engaged.add(paper_id)
                    read.add(paper_id)
    engaged.update(read)
    return engaged, candidates, read


def _paper_aliases(paper_records_by_id: dict[str, JsonMap]) -> dict[str, str]:
    owners: dict[str, set[str]] = {}
    for paper_id, record in paper_records_by_id.items():
        title = _paper_title(record, paper_id)
        raw_aliases = {title, paper_id.replace("_", " ")}
        prefix = re.split(r"[:：]", title, maxsplit=1)[0].strip()
        if prefix and prefix != title and len(prefix) <= 60:
            raw_aliases.add(prefix)
        for raw_alias in raw_aliases:
            alias = _normalize(raw_alias)
            if len(alias.replace(" ", "")) >= 3:
                owners.setdefault(alias, set()).add(paper_id)
    return {
        alias: next(iter(paper_ids))
        for alias, paper_ids in owners.items()
        if len(paper_ids) == 1
    }


def _mentioned_papers(text: str, aliases: dict[str, str]) -> set[str]:
    normalized = f" {_normalize(text)} "
    return {
        paper_id
        for alias, paper_id in aliases.items()
        if f" {alias} " in normalized
    }


def _paper_title(record: JsonMap, paper_id: str) -> str:
    identity = child_map(record.get("identity"))
    return str(record.get("title") or identity.get("title") or paper_id)


def _contains_content_claim(markdown: str, paper_records_by_id: dict[str, JsonMap]) -> bool:
    residual = unicodedata.normalize("NFKC", CITATION_RE.sub(" ", markdown))
    for paper_id, record in paper_records_by_id.items():
        identity = child_map(record.get("identity"))
        title = _paper_title(record, paper_id)
        values: list[object] = [
            title,
            re.split(r"[:：]", title, maxsplit=1)[0].strip(),
            identity.get("year"),
            identity.get("venue"),
            identity.get("doi"),
            identity.get("arxiv_id"),
            identity.get("version_label"),
        ]
        values.extend(as_list(identity.get("authors")))
        for value in values:
            text = str(value or "").strip()
            if len(text) >= 2:
                residual = re.sub(re.escape(text), " ", residual, flags=re.IGNORECASE)
    return bool(CONTENT_CLAIM_RE.search(_normalize(residual)))


def _paper_names(
    paper_ids: tuple[str, ...],
    paper_records_by_id: dict[str, JsonMap],
) -> str:
    return ", ".join(
        _paper_title(child_map(paper_records_by_id.get(paper_id)), paper_id)
        for paper_id in paper_ids
    )


def _is_substantive(item: JsonMap) -> bool:
    if item.get("citeable") is False or item.get("evidence_quality") == "rejected":
        return False
    element_type = str(item.get("element_type") or item.get("source_kind") or "").lower()
    if element_type in WEAK_EVIDENCE_TYPES:
        return False
    return bool(str(item.get("span_text") or "").strip())


def _normalize(value: str) -> str:
    plain = unicodedata.normalize("NFKC", value).casefold()
    return " ".join("".join(character if character.isalnum() else " " for character in plain).split())
