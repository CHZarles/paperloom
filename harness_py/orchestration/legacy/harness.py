from __future__ import annotations

import json
import re
from datetime import UTC, datetime
from time import perf_counter
from typing import Callable

from ...core.errors import HarnessCancelled
from ...core.models import RUN_TRACE_SCHEMA_VERSION, GoldenDataset, JsonMap, as_list, child_map, stable_id
from ...corpus.tools import ReadingCorpusTools, model_facing_payload
from ...evaluation.golden_case import case_history, case_question, paper_ids_for_case
from ..research_skills import ResearchSkillRegistry
from .llm import ChatModel, ChatTurn, ToolCall


_CITATION_RE = re.compile(r"\[\[(ev_[A-Za-z0-9_-]+)\]\]")
_NUMERIC_CITATION_RE = re.compile(r"(?<!\[)\[(\d+)\]")
_FINAL_TOOL = "submit_research_answer"


class ResearchAgentHarness:
    """One model-driven ReAct loop with research skills and corpus tools."""

    harness_id = "python_skill_guided_react_harness_v1"

    def __init__(self, model: ChatModel, max_completion_tokens: int = 3000):
        self.model = model
        self.max_completion_tokens = max_completion_tokens
        self.skills = ResearchSkillRegistry()

    def run_case(self, dataset: GoldenDataset, case: JsonMap) -> JsonMap:
        allowed = paper_ids_for_case(dataset, case)
        scoped = _scope_dataset(dataset, allowed)
        return self.run_turn(
            scoped,
            case_id=str(case.get("id") or "turn"),
            question=case_question(case),
            conversation_messages=case_history(case),
        )

    def run_turn(
        self,
        dataset: GoldenDataset,
        *,
        case_id: str,
        question: str,
        conversation_messages: list[JsonMap],
        prior_evidence: dict[str, JsonMap] | None = None,
        selected_paper_ids: list[str] | None = None,
        progress_listener: Callable[[JsonMap], None] | None = None,
        should_cancel: Callable[[], bool] | None = None,
    ) -> JsonMap:
        started_at = _now()
        started = perf_counter()
        corpus = ReadingCorpusTools(dataset)
        prior_evidence = dict(prior_evidence or {})
        corpus.authorized_paper_ids.update(
            paper_id for paper_id in selected_paper_ids or []
            if paper_id in dataset.paper_records_by_id
        )
        corpus.disclosed_location_refs.update(
            str(item.get("location_ref") or item.get("location") or "")
            for item in prior_evidence.values()
            if str(item.get("location_ref") or item.get("location") or "") in corpus.documents_by_location
        )
        messages = [
            {"role": "system", "content": self._system_prompt()},
            *self._conversation_messages(conversation_messages),
        ]
        if prior_evidence:
            messages.append({
                "role": "system",
                "content": "Previously cited evidence available for follow-up questions:\n" + json.dumps(
                    [_evidence_card(item) for item in prior_evidence.values()],
                    ensure_ascii=False,
                ),
            })
        messages.append({"role": "user", "content": question})

        definitions = [
            self.skills.tool_definition(),
            *corpus.definitions(),
            _final_answer_tool(),
        ]
        trace: list[JsonMap] = []
        skills_used: list[str] = []
        model_call_count = 0
        prompt_tokens = 0
        completion_tokens = 0
        total_tokens = 0
        model_latency_ms = 0

        # 旧运行时只保留一条连续工具循环，最终答案由同一套确定性校验收口。
        while True:
            _raise_if_cancelled(should_cancel)
            _emit_progress(progress_listener, {
                "type": "model_call_started",
                "attempt": model_call_count + 1,
            })
            model_started = perf_counter()
            response = self.model.complete(messages, definitions, self.max_completion_tokens)
            call_latency_ms = round((perf_counter() - model_started) * 1000)
            model_latency_ms += call_latency_ms
            model_call_count += 1
            usage = child_map(response.raw.get("usage"))
            call_prompt_tokens = int(usage.get("prompt_tokens") or 0)
            call_completion_tokens = int(usage.get("completion_tokens") or 0)
            call_total_tokens = int(
                usage.get("total_tokens")
                or call_prompt_tokens + call_completion_tokens
            )
            prompt_tokens += call_prompt_tokens
            completion_tokens += call_completion_tokens
            total_tokens += call_total_tokens
            _emit_progress(progress_listener, {
                "type": "model_call_completed",
                "attempt": model_call_count,
                "durationMs": call_latency_ms,
                "usage": {
                    "promptTokens": call_prompt_tokens,
                    "completionTokens": call_completion_tokens,
                    "totalTokens": call_total_tokens,
                    "cumulativeTotalTokens": total_tokens,
                },
            })

            if not response.tool_calls:
                messages.append({"role": "assistant", "content": response.content})
                messages.append({
                    "role": "user",
                    "content": "Finish this turn by calling submit_research_answer. Continue researching first if needed.",
                })
                continue

            messages.append(_assistant_tool_message(response))
            final_calls = [call for call in response.tool_calls if call.name == _FINAL_TOOL]
            if final_calls and len(response.tool_calls) == 1:
                final = child_map(final_calls[0].arguments)
                validation_error = _answer_validation_error(
                    final,
                    {**prior_evidence, **corpus.observations_by_evidence_id},
                    bool(corpus.observations_by_evidence_id),
                )
                if validation_error:
                    payload = {"accepted": False, "error": validation_error}
                    trace.append(_trace_item(final_calls[0], payload))
                    messages.append(_tool_message(final_calls[0], payload))
                    continue
                trace.append(_trace_item(final_calls[0], {"accepted": True}))
                return _build_run(
                    case_id=case_id,
                    final=final,
                    prior_evidence=prior_evidence,
                    corpus=corpus,
                    trace=trace,
                    skills_used=skills_used,
                    started_at=started_at,
                    duration_ms=round((perf_counter() - started) * 1000),
                    diagnostics={
                        "model_call_count": model_call_count,
                        "prompt_tokens": prompt_tokens,
                        "completion_tokens": completion_tokens,
                        "total_tokens": total_tokens,
                        "model_latency_ms": model_latency_ms,
                    },
                    harness_id=self.harness_id,
                )

            if final_calls:
                for call in final_calls:
                    payload = {
                        "accepted": False,
                        "error": "submit_research_answer must be the only tool call in the final step",
                    }
                    trace.append(_trace_item(call, payload))
                    messages.append(_tool_message(call, payload))

            for call in response.tool_calls:
                if call.name == _FINAL_TOOL:
                    continue
                _raise_if_cancelled(should_cancel)
                _emit_progress(progress_listener, {
                    "type": "tool_started",
                    "tool": call.name,
                    "input": _progress_input(call.name, call.arguments),
                })
                tool_started = perf_counter()
                if call.name == "get_research_skill":
                    skill_id = str(call.arguments.get("skill_id") or "")
                    payload = self.skills.get(skill_id)
                    if "error" not in payload and skill_id not in skills_used:
                        skills_used.append(skill_id)
                else:
                    payload = model_facing_payload(corpus.call(call.name, call.arguments).payload)
                trace.append(_trace_item(call, payload))
                messages.append(_tool_message(call, payload))
                visible = child_map(payload)
                _emit_progress(progress_listener, {
                    "type": "tool_completed",
                    "tool": call.name,
                    "status": "success" if "error" not in visible else "failed",
                    "durationMs": round((perf_counter() - tool_started) * 1000),
                    "input": _progress_input(call.name, call.arguments),
                    "output": _progress_output(call.name, visible),
                    "evidenceIds": _progress_evidence_ids(visible),
                })

    @staticmethod
    def _conversation_messages(messages: list[JsonMap]) -> list[JsonMap]:
        result: list[JsonMap] = []
        for item in messages:
            role = str(item.get("role") or "")
            if role not in {"user", "assistant"}:
                continue
            content = str(item.get("content") or item.get("summary") or "").strip()
            if content:
                result.append({"role": role, "content": content})
        return result

    def _system_prompt(self) -> str:
        return research_agent_instructions(self.skills)


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
            "assistant refusal is not proof that a request is impossible; "
            "use the available tool again when the current follow-up can be answered. Metadata does not support claims "
            "about methods, findings, performance, importance, or technical contributions.\n\n"
            "Use get_research_skill when a paradigm playbook would help. Skills are guidance, not gates, and may be "
            "combined. Candidate metadata and navigation previews are not citeable as paper content. Read exact locations before making "
            "paper-content claims. A citation does not license related general knowledge: every factual sentence, "
            "comparison, default value, and causal explanation must be directly entailed by a cited span_text. Cite "
            "with the exact syntax [[evidence_id]] and cite only evidence returned by read_locations or supplied as "
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


def _final_answer_tool() -> JsonMap:
    return {
        "type": "function",
        "function": {
            "name": _FINAL_TOOL,
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


def _answer_validation_error(final: JsonMap, known_evidence: dict[str, JsonMap], used_paper_evidence: bool) -> str:
    outcome = str(final.get("outcome") or "")
    markdown = final.get("markdown")
    if outcome not in {"answered", "needs_clarification", "partial", "abstained"}:
        return "invalid outcome"
    if not isinstance(markdown, str) or not markdown.strip():
        return "markdown is required"
    if _NUMERIC_CITATION_RE.search(markdown):
        return "use [[evidence_id]] markers instead of numeric citations or a manually written Sources section"
    cited = set(_CITATION_RE.findall(markdown))
    citeable = {
        evidence_id for evidence_id, item in known_evidence.items()
        if item.get("citeable") is not False
    }
    unknown = sorted(cited - citeable)
    if unknown:
        return "unknown cited evidence ids: " + ", ".join(unknown)
    if used_paper_evidence and outcome in {"answered", "partial"} and not cited:
        return "paper-content evidence was read, so cite a returned evidence item; do not mention this validation rule"
    if final.get("fields") is not None and not isinstance(final.get("fields"), dict):
        return "fields must be an object"
    return ""


def _build_run(
    *,
    case_id: str,
    final: JsonMap,
    prior_evidence: dict[str, JsonMap],
    corpus: ReadingCorpusTools,
    trace: list[JsonMap],
    skills_used: list[str],
    started_at: str,
    duration_ms: int,
    diagnostics: JsonMap,
    harness_id: str,
) -> JsonMap:
    outcome = str(final["outcome"])
    status = {
        "answered": "COMPLETED",
        "needs_clarification": "NEEDS_CLARIFICATION",
        "partial": "INCOMPLETE_PRECISE",
        "abstained": "INCOMPLETE_PRECISE",
    }[outcome]
    raw_markdown = str(final["markdown"]).strip()
    cited_ids = _unique(_CITATION_RE.findall(raw_markdown))
    all_evidence = {**prior_evidence, **corpus.observations_by_evidence_id}
    cited_evidence = {
        evidence_id: all_evidence[evidence_id]
        for evidence_id in cited_ids
        if evidence_id in all_evidence
    }
    markdown = _render_citations(raw_markdown, cited_ids, cited_evidence)
    selected_paper_ids = _unique(
        str(cited_evidence[evidence_id].get("paper_id") or "")
        for evidence_id in cited_ids
        if cited_evidence.get(evidence_id, {}).get("paper_id")
    )
    answer = {
        "answer_id": stable_id("answer", case_id),
        "question_id": case_id,
        "status": status,
        "outcome": outcome,
        "answer_type": skills_used[-1] if skills_used else "conversation",
        "summary": markdown[:400],
        "markdown": markdown,
        "fields": child_map(final.get("fields")),
        "cited_evidence_ids": cited_ids,
    }
    evidence_items = list(corpus.observations_by_evidence_id.values())
    for evidence_id, item in cited_evidence.items():
        if evidence_id not in corpus.observations_by_evidence_id:
            evidence_items.append(item)
    return {
        "schema_version": RUN_TRACE_SCHEMA_VERSION,
        "run_id": stable_id("run", case_id),
        "question_id": case_id,
        "case_id": case_id,
        "harness_id": harness_id,
        "started_at": started_at,
        "completed_at": _now(),
        "status": status,
        "result_status": status,
        "memory_update": {
            "selected_paper_ids": selected_paper_ids,
            "selected_evidence_ids": cited_ids,
        },
        "skills_used": skills_used,
        "react_trace": trace,
        "paper_candidates": _paper_candidates(trace),
        "evidence_ledger": {
            "ledger_id": stable_id("ledger", case_id),
            "question_id": case_id,
            "items": evidence_items,
            "coverage": [],
            "rejected_items": [],
            "missing_evidence": [],
        },
        "citation_validation": {
            "passed": True,
            "cited_evidence_ids": cited_ids,
            "corpus_tools_used": any(item["tool_name"] in _corpus_tool_names(corpus) for item in trace),
        },
        "research_answer": answer,
        "final_answer": answer,
        "diagnostics": {
            "finish_reason": "model_submitted_answer",
            "tool_call_count": sum(1 for item in trace if item["tool_name"] != _FINAL_TOOL),
            "skills_used": skills_used,
            **diagnostics,
            "duration_ms": duration_ms,
        },
    }


def _assistant_tool_message(response: ChatTurn) -> JsonMap:
    return {
        "role": "assistant",
        "content": response.content or "",
        "tool_calls": [
            {
                "id": call.id,
                "type": "function",
                "function": {
                    "name": call.name,
                    "arguments": json.dumps(call.arguments, ensure_ascii=False),
                },
            }
            for call in response.tool_calls
        ],
    }


def _tool_message(call: ToolCall, payload: JsonMap) -> JsonMap:
    return {
        "role": "tool",
        "tool_call_id": call.id,
        "name": call.name,
        "content": json.dumps(payload, ensure_ascii=False),
    }


def _trace_item(call: ToolCall, payload: JsonMap) -> JsonMap:
    return {
        "tool_call_id": call.id,
        "tool_name": call.name,
        "arguments": call.arguments,
        "result": payload,
    }


def _render_citations(markdown: str, cited_ids: list[str], evidence: dict[str, JsonMap]) -> str:
    numbers = {evidence_id: index for index, evidence_id in enumerate(cited_ids, start=1)}
    rendered = _CITATION_RE.sub(lambda match: f"[{numbers[match.group(1)]}]", markdown)
    if not cited_ids:
        return rendered
    sources = []
    for evidence_id in cited_ids:
        item = evidence[evidence_id]
        location = ", ".join(
            part for part in [
                str(item.get("section") or "").strip(),
                f"p. {item.get('page')}" if item.get("page") not in {None, "", "unknown"} else "",
            ]
            if part
        )
        sources.append(
            f"[{numbers[evidence_id]}] {item.get('title') or item.get('paper_id')}"
            + (f", {location}" if location else "")
        )
    return rendered + "\n\nSources\n" + "\n".join(sources)


def _paper_candidates(trace: list[JsonMap]) -> list[JsonMap]:
    candidates: dict[str, JsonMap] = {}
    for item in trace:
        result = child_map(item.get("result"))
        for raw in [
            *as_list(result.get("candidates")),
            *as_list(result.get("matches")),
            *as_list(result.get("papers")),
        ]:
            card = child_map(raw)
            paper_id = str(card.get("paper_id") or "")
            if paper_id:
                candidates[paper_id] = {
                    **card,
                    "evidence_id": f"paper_candidate_{paper_id}",
                    "element_type": "paper_candidate",
                    "citeable": False,
                }
    return list(candidates.values())


def _emit_progress(listener: Callable[[JsonMap], None] | None, event: JsonMap) -> None:
    if listener:
        listener(event)


def _raise_if_cancelled(should_cancel: Callable[[], bool] | None) -> None:
    if should_cancel and should_cancel():
        raise HarnessCancelled("research job cancelled")


def _progress_input(tool_name: str, arguments: JsonMap) -> JsonMap:
    if tool_name == "search_paper_candidates":
        return {
            "query": arguments.get("query_text") or arguments.get("query"),
            "limit": arguments.get("limit"),
        }
    if tool_name == "find_reading_locations":
        return {
            "paperIds": as_list(arguments.get("paper_ids")),
            "query": arguments.get("query_text"),
            "limit": arguments.get("top_k"),
        }
    if tool_name == "read_locations":
        location_refs = as_list(arguments.get("location_refs"))
        return {
            "locationRefs": location_refs,
            "locationCount": len(location_refs),
        }
    if tool_name == "get_citation_edges":
        return {"paperId": arguments.get("paper_id")}
    if tool_name == "get_research_skill":
        return {"skillId": arguments.get("skill_id")}
    return {
        key: value
        for key, value in arguments.items()
        if key in {"paper_id", "paper_ids", "query", "query_text", "limit", "top_k"}
    }


def _progress_output(tool_name: str, payload: JsonMap) -> JsonMap:
    if tool_name == "search_paper_candidates":
        candidates = [child_map(item) for item in as_list(payload.get("candidates"))]
        return {
            "resultCount": len(candidates),
            "papers": [
                {
                    "paperId": item.get("paper_id"),
                    "title": item.get("title"),
                }
                for item in candidates[:10]
            ],
        }
    if tool_name == "find_reading_locations":
        locations = [child_map(item) for item in as_list(payload.get("locations"))]
        return {
            "resultCount": len(locations),
            "locations": [
                {
                    "paperId": item.get("paper_id"),
                    "title": item.get("title"),
                    "section": item.get("section"),
                    "page": item.get("page"),
                    "locationRef": item.get("location_ref"),
                }
                for item in locations[:10]
            ],
        }
    if tool_name == "read_locations":
        items = [child_map(item) for item in as_list(payload.get("items"))]
        return {
            "readCount": len(items),
            "evidenceCount": len(items),
            "pages": _unique(item.get("page") for item in items if item.get("page") not in {None, "", "unknown"}),
            "evidence": [
                {
                    "evidenceId": item.get("evidence_id"),
                    "paperId": item.get("paper_id"),
                    "title": item.get("title"),
                    "section": item.get("section"),
                    "page": item.get("page"),
                    "quote": str(item.get("span_text") or "")[:300],
                }
                for item in items[:10]
            ],
        }
    if tool_name == "get_citation_edges":
        return {"edgeCount": len(as_list(payload.get("edges")))}
    if tool_name == "get_research_skill":
        return {"skillId": payload.get("skill_id"), "found": "error" not in payload}
    return {
        "resultCount": max(
            len(as_list(payload.get("items"))),
            len(as_list(payload.get("matches"))),
            len(as_list(payload.get("papers"))),
        ),
        "error": payload.get("error"),
    }


def _progress_evidence_ids(payload: JsonMap) -> list[str]:
    return _unique(
        child_map(item).get("evidence_id")
        for item in as_list(payload.get("items"))
        if child_map(item).get("evidence_id")
    )


def _evidence_card(item: JsonMap) -> JsonMap:
    return {
        "evidence_id": item.get("evidence_id"),
        "paper_id": item.get("paper_id"),
        "title": item.get("title"),
        "section": item.get("section"),
        "page": item.get("page"),
        "span_text": str(item.get("span_text") or "")[:900],
    }


def _corpus_tool_names(corpus: ReadingCorpusTools) -> set[str]:
    return {
        str(child_map(tool.get("function")).get("name") or "")
        for tool in corpus.definitions()
    }


def _scope_dataset(dataset: GoldenDataset, allowed_paper_ids: list[str]) -> GoldenDataset:
    from dataclasses import replace

    allowed = set(allowed_paper_ids)
    if not allowed:
        return dataset
    return replace(
        dataset,
        paper_records_by_id={
            paper_id: record
            for paper_id, record in dataset.paper_records_by_id.items()
            if paper_id in allowed
        },
        reading_models_by_paper_id={
            paper_id: model
            for paper_id, model in dataset.reading_models_by_paper_id.items()
            if paper_id in allowed
        },
        citation_edges=[
            edge for edge in dataset.citation_edges
            if edge.get("from_paper_id") in allowed or edge.get("to_paper_id") in allowed
        ],
    )


def _unique(values) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for value in values:
        text = str(value or "")
        if text and text not in seen:
            seen.add(text)
            result.append(text)
    return result


def _now() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")
