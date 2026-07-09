from __future__ import annotations

import json
from datetime import UTC, datetime
from typing import Any

from .llm import ChatModel
from .models import RUN_TRACE_SCHEMA_VERSION, GoldenDataset, JsonMap, as_list, child_map, stable_id
from .tools import ReadingCorpusTools, json_tool_content


class ResearchAgentHarness:
    """Real tool-using agent harness.

    The public interface is still one deep method: `run_case(dataset, case) -> HarnessRun`.
    Language understanding and answer drafting come from the model; corpus operations are exposed
    only through typed tools; verification remains deterministic.
    """

    harness_id = "python_minimax_agent_harness_v0"

    def __init__(self, model: ChatModel, max_turns: int = 8, max_completion_tokens: int = 3000):
        self.model = model
        self.max_turns = max_turns
        self.max_completion_tokens = max_completion_tokens

    def run_case(self, dataset: GoldenDataset, case: JsonMap) -> JsonMap:
        tools = ReadingCorpusTools(dataset)
        started_at = _now()
        messages = self._initial_messages(dataset, case)
        tool_trace: list[JsonMap] = []
        final_payload: JsonMap | None = None
        finish_reason = "max_turns"
        repair_attempted = False
        last_model_content = ""
        for _ in range(self.max_turns):
            turn = self.model.complete(messages, tools.definitions(), self.max_completion_tokens)
            last_model_content = turn.content
            if turn.tool_calls:
                assistant_message: JsonMap = {
                    "role": "assistant",
                    "content": turn.content,
                    "tool_calls": [
                        {
                            "id": call.id,
                            "type": "function",
                            "function": {
                                "name": call.name,
                                "arguments": json.dumps(call.arguments, ensure_ascii=False),
                            },
                        }
                        for call in turn.tool_calls
                    ],
                }
                messages.append(assistant_message)
                for call in turn.tool_calls:
                    result = tools.call(call.name, call.arguments)
                    tool_trace.append({
                        "tool_call_id": call.id,
                        "tool_name": call.name,
                        "arguments": call.arguments,
                        "result": result.payload,
                    })
                    messages.append({
                        "role": "tool",
                        "tool_call_id": call.id,
                        "name": call.name,
                        "content": json_tool_content(result),
                    })
                continue
            final_payload = _parse_final_payload(turn.content)
            finish_reason = "final_json" if final_payload else "unparseable_final"
            messages.append({"role": "assistant", "content": turn.content})
            if final_payload is None and not repair_attempted:
                repair_attempted = True
                finish_reason = "json_repair_requested"
                messages.append({
                    "role": "user",
                    "content": (
                        "Your previous response was not parseable final JSON. Return exactly one JSON "
                        "object matching the required schema. Do not include prose, markdown fences, "
                        "or additional commentary. Reuse only evidence_id values already returned by tools."
                    ),
                })
                continue
            break

        if final_payload is None and tool_trace:
            messages.append({
                "role": "user",
                "content": (
                    "Tool budget is exhausted. Do not call more tools. Return exactly one final JSON "
                    "object matching the required schema, using only evidence_id values already "
                    "returned by tools. Keep it compact enough to fit in the response."
                ),
            })
            turn = self.model.complete(messages, [], self.max_completion_tokens)
            last_model_content = turn.content
            final_payload = _parse_final_payload(turn.content)
            finish_reason = "forced_final_json" if final_payload else "forced_final_unparseable"
            messages.append({"role": "assistant", "content": turn.content})
            if final_payload is None:
                messages.append({
                    "role": "user",
                    "content": (
                        "Return only parseable JSON now. No prose, no markdown fences, no tool calls."
                    ),
                })
                turn = self.model.complete(messages, [], self.max_completion_tokens)
                last_model_content = turn.content
                final_payload = _parse_final_payload(turn.content)
                finish_reason = "forced_repair_json" if final_payload else "forced_repair_unparseable"
                messages.append({"role": "assistant", "content": turn.content})

        if final_payload is None:
            final_payload = _incomplete_payload(case, "model did not return parseable final JSON")

        ledger = self._evidence_ledger(case, tools, final_payload)
        intent = self._intent_frame(case, final_payload)
        plan = self._retrieval_plan(case, final_payload, tool_trace)
        claim_graph = self._claim_graph(case, final_payload, ledger)
        reasoning = self._reasoning(case, final_payload, claim_graph, ledger)
        verification = self._verification(case, intent, plan, ledger, claim_graph)
        answer = self._answer(case, final_payload, verification, claim_graph, ledger, reasoning)
        return {
            "schema_version": RUN_TRACE_SCHEMA_VERSION,
            "run_id": stable_id("run", str(case.get("id"))),
            "question_id": case.get("id"),
            "case_id": case.get("id"),
            "harness_id": self.harness_id,
            "started_at": started_at,
            "completed_at": _now(),
            "status": answer["status"],
            "result_status": answer["status"],
            "memory_update": child_map(final_payload.get("memory_update")),
            "intent_frame": intent,
            "retrieval_plan": plan,
            "evidence_ledger": ledger,
            "claim_graph": claim_graph,
            "reasoning_artifacts": reasoning,
            "verification_pass": verification,
            "research_answer": answer,
            "final_answer": answer,
            "diagnostics": {
                "finish_reason": finish_reason,
                "tool_call_count": len(tool_trace),
                "tool_trace": tool_trace,
                "last_model_content_preview": last_model_content[:1000],
            },
        }

    def _initial_messages(self, dataset: GoldenDataset, case: JsonMap) -> list[JsonMap]:
        paper_cards = [
            {
                "paper_id": paper_id,
                "title": child_map(record.get("identity")).get("title"),
                "year": child_map(record.get("identity")).get("year"),
                "venue": child_map(record.get("identity")).get("venue"),
            }
            for paper_id, record in dataset.paper_records_by_id.items()
        ]
        question = child_map(case.get("question")).get("text", "")
        return [
            {
                "role": "system",
                "content": (
                    "You are a precision-gated research agent. Use tools before making paper-content "
                    "claims. Do not rely on general world memory. You may use conversation_context "
                    "only to resolve follow-up references, selected papers, and previously cited "
                    "evidence ids. Final output must be one JSON object and no prose. "
                    "Use evidence_id values returned by tools. If evidence is missing, set status to "
                    "INCOMPLETE_PRECISE. If the question is ambiguous, set status to NEEDS_CLARIFICATION. "
                    "Keep the final JSON compact: at most 6 claims, concise claim text, summary under "
                    "160 words, no quoted source spans unless needed. "
                    "JSON schema: {intent_frame:{answer_type, ambiguity_status, entities, "
                    "required_evidence_types, required_capabilities}, claims:[{claim_id,text,status,"
                    "supporting_evidence_ids,refuting_evidence_ids}], reasoning_artifact:{type,title,payload}, "
                    "answer:{status,answer_type,summary,fields,sections,cited_claim_ids,cited_evidence_ids}, "
                    "memory_update:{selected_paper_ids,scope_paper_ids,selected_evidence_ids,"
                    "unresolved_choices,note}}."
                ),
            },
            {
                "role": "user",
                "content": json.dumps({
                    "question": question,
                    "available_papers": paper_cards,
                    "conversation_context": child_map(case.get("conversation_context")),
                    "instruction": "Use the available tools to inspect evidence, then return final JSON.",
                }, ensure_ascii=False),
            },
        ]

    def _evidence_ledger(self, case: JsonMap, tools: ReadingCorpusTools, final_payload: JsonMap) -> JsonMap:
        cited = set(as_list(child_map(final_payload.get("answer")).get("cited_evidence_ids")))
        items = []
        for evidence_id, item in tools.observations_by_evidence_id.items():
            if not cited or evidence_id in cited:
                items.append(item)
        return {
            "ledger_id": stable_id("ledger", str(case.get("id"))),
            "question_id": case.get("id"),
            "items": items,
            "rejected_items": [],
            "missing_evidence": as_list(final_payload.get("missing_evidence")),
        }

    def _intent_frame(self, case: JsonMap, final_payload: JsonMap) -> JsonMap:
        payload = child_map(final_payload.get("intent_frame"))
        question = child_map(case.get("question"))
        return {
            "intent_id": stable_id("intent", str(case.get("id"))),
            "question_id": case.get("id"),
            "raw_question": question.get("text", ""),
            "normalized_question": payload.get("normalized_question") or question.get("text", ""),
            "entities": as_list(payload.get("entities")),
            "paper_mentions": as_list(payload.get("paper_mentions")),
            "method_mentions": as_list(payload.get("method_mentions")),
            "dataset_mentions": as_list(payload.get("dataset_mentions")),
            "constraints": child_map(payload.get("constraints")),
            "answer_type": payload.get("answer_type") or child_map(child_map(final_payload.get("answer"))).get("answer_type") or "unknown",
            "ambiguity_status": payload.get("ambiguity_status", "unambiguous"),
            "required_evidence_types": as_list(payload.get("required_evidence_types")),
            "required_capabilities": as_list(payload.get("required_capabilities")),
        }

    def _retrieval_plan(self, case: JsonMap, final_payload: JsonMap, tool_trace: list[JsonMap]) -> JsonMap:
        strategies = [_strategy_from_tool_call(item["tool_name"]) for item in tool_trace]
        return {
            "plan_id": stable_id("plan", str(case.get("id"))),
            "question_id": case.get("id"),
            "target_entities": as_list(child_map(final_payload.get("intent_frame")).get("entities")),
            "strategy_steps": [
                {
                    "step_id": stable_id("step", f"{index}_{strategy}"),
                    "strategy": strategy,
                    "tool_name": tool_trace[index]["tool_name"],
                    "stop_condition": "model_requested_tool",
                }
                for index, strategy in enumerate(strategies)
            ],
            "expected_evidence_types": as_list(child_map(final_payload.get("intent_frame")).get("required_evidence_types")),
            "required_recall_targets": [],
            "hard_negative_policy": [],
            "stop_conditions": ["model_final_json", "verification_failure"],
        }

    def _claim_graph(self, case: JsonMap, final_payload: JsonMap, ledger: JsonMap) -> JsonMap:
        known_evidence_ids = {item.get("evidence_id") for item in as_list(ledger.get("items"))}
        claims = []
        for index, raw_claim in enumerate(as_list(final_payload.get("claims"))):
            claim = child_map(raw_claim)
            supporting = [value for value in as_list(claim.get("supporting_evidence_ids")) if value in known_evidence_ids]
            refuting = [value for value in as_list(claim.get("refuting_evidence_ids")) if value in known_evidence_ids]
            status = _normalize_claim_status(claim.get("status") or ("supported" if supporting else "underdetermined"))
            claims.append({
                "claim_id": claim.get("claim_id") or f"claim_{index + 1}",
                "text": claim.get("text") or "",
                "claim_type": claim.get("claim_type") or "model_claim",
                "supporting_evidence_ids": supporting,
                "refuting_evidence_ids": refuting,
                "status": status,
                "confidence": 1.0 if status == "supported" and supporting else 0.0,
                "depends_on_claim_ids": as_list(claim.get("depends_on_claim_ids")),
            })
        return {
            "graph_id": stable_id("claim_graph", str(case.get("id"))),
            "question_id": case.get("id"),
            "claims": claims,
            "edges": [],
        }

    def _reasoning(self, case: JsonMap, final_payload: JsonMap, claim_graph: JsonMap, ledger: JsonMap) -> list[JsonMap]:
        payload = child_map(final_payload.get("reasoning_artifact"))
        source_claim_ids = [claim.get("claim_id") for claim in as_list(claim_graph.get("claims")) if claim.get("claim_id")]
        source_evidence_ids = [item.get("evidence_id") for item in as_list(ledger.get("items")) if item.get("evidence_id")]
        return [{
            "artifact_id": stable_id("reasoning", str(case.get("id"))),
            "question_id": case.get("id"),
            "type": payload.get("type") or "evidence_report",
            "title": payload.get("title") or "agent reasoning",
            "source_claim_ids": source_claim_ids,
            "payload": payload.get("payload") or {"source_evidence_ids": source_evidence_ids},
        }]

    def _verification(self, case: JsonMap, intent: JsonMap, plan: JsonMap, ledger: JsonMap, claim_graph: JsonMap) -> JsonMap:
        evidence_ids = {item.get("evidence_id") for item in as_list(ledger.get("items"))}
        accepted_anchor_ids = {
            item.get("matched_anchor_id")
            for item in as_list(ledger.get("items"))
            if item.get("matched_anchor_id")
        }
        strategies = {
            child_map(step).get("strategy")
            for step in as_list(plan.get("strategy_steps"))
            if child_map(step).get("strategy")
        }
        unsupported = [
            claim for claim in as_list(claim_graph.get("claims"))
            if claim.get("status") == "supported"
            and (
                not as_list(claim.get("supporting_evidence_ids"))
                or not set(as_list(claim.get("supporting_evidence_ids"))) <= evidence_ids
            )
        ]
        ambiguous = intent.get("ambiguity_status") in {"ambiguous", "needs_user_choice"}
        failed_obligations = _failed_structural_obligations(case, accepted_anchor_ids, strategies)
        obligation_ids = [
            child_map(obligation).get("id")
            for obligation in as_list(child_map(case.get("required_trace")).get("obligations"))
            if child_map(obligation).get("id")
        ]
        return {
            "verification_id": stable_id("verification", str(case.get("id"))),
            "question_id": case.get("id"),
            "required_capabilities_attempted": as_list(intent.get("required_capabilities")),
            "required_evidence_status": [
                {"evidence_id": item.get("evidence_id"), "status": "accepted"}
                for item in as_list(ledger.get("items"))
            ],
            "unsupported_claim_count": len(unsupported),
            "contradicted_claim_count": sum(1 for claim in as_list(claim_graph.get("claims")) if claim.get("status") == "contradicted"),
            "missing_required_evidence": as_list(ledger.get("missing_evidence")),
            "missing_required_anchor_ids": [],
            "ambiguity_resolution": {"status": intent.get("ambiguity_status"), "requires_user_choice": ambiguous},
            "constraint_check_results": [],
            "abstention_required": False,
            "satisfied_trace_obligation_ids": [
                obligation_id for obligation_id in obligation_ids if obligation_id not in failed_obligations
            ],
            "failed_trace_obligation_ids": sorted(failed_obligations),
        }

    def _answer(
        self,
        case: JsonMap,
        final_payload: JsonMap,
        verification: JsonMap,
        claim_graph: JsonMap,
        ledger: JsonMap,
        reasoning: list[JsonMap],
    ) -> JsonMap:
        raw_answer = child_map(final_payload.get("answer"))
        status = _normalize_status(raw_answer.get("status") or "INCOMPLETE_PRECISE")
        if verification.get("unsupported_claim_count"):
            status = "INCOMPLETE_PRECISE"
        if child_map(verification.get("ambiguity_resolution")).get("requires_user_choice"):
            status = "NEEDS_CLARIFICATION"
        answer_type = _normalize_answer_type(
            raw_answer.get("answer_type") or child_map(final_payload.get("intent_frame")).get("answer_type") or "unknown"
        )
        return {
            "answer_id": stable_id("answer", str(case.get("id"))),
            "question_id": case.get("id"),
            "status": status,
            "answer_type": answer_type,
            "summary": raw_answer.get("summary") or "",
            "sections": as_list(raw_answer.get("sections")),
            "markdown": raw_answer.get("markdown") or raw_answer.get("summary") or "",
            "fields": child_map(raw_answer.get("fields")),
            "cited_claim_ids": as_list(raw_answer.get("cited_claim_ids")) or [
                claim.get("claim_id") for claim in as_list(claim_graph.get("claims")) if claim.get("claim_id")
            ],
            "cited_evidence_ids": as_list(raw_answer.get("cited_evidence_ids")) or [
                item.get("evidence_id") for item in as_list(ledger.get("items")) if item.get("evidence_id")
            ],
            "reasoning_artifact_ids": [artifact.get("artifact_id") for artifact in reasoning],
            "verification_id": verification.get("verification_id"),
        }


def _parse_final_payload(content: str) -> JsonMap | None:
    text = content.strip()
    if text.startswith("```"):
        text = text.strip("`")
        if text.startswith("json"):
            text = text[4:].strip()
    if not text.startswith("{") and "{" in text and "}" in text:
        text = text[text.find("{"): text.rfind("}") + 1]
    try:
        payload = json.loads(text)
    except json.JSONDecodeError:
        return None
    return payload if isinstance(payload, dict) else None


def _incomplete_payload(case: JsonMap, reason: str) -> JsonMap:
    return {
        "intent_frame": {
            "answer_type": "unknown",
            "ambiguity_status": "unambiguous",
            "entities": [],
            "required_evidence_types": [],
            "required_capabilities": [],
        },
        "claims": [],
        "reasoning_artifact": {"type": "uncertainty_boundary_report", "title": "incomplete", "payload": {"reason": reason}},
        "answer": {
            "status": "INCOMPLETE_PRECISE",
            "answer_type": "unknown",
            "summary": reason,
            "fields": {},
            "sections": [],
            "cited_claim_ids": [],
            "cited_evidence_ids": [],
        },
        "missing_evidence": [{"reason": reason, "question_id": case.get("id")}],
    }


def _strategy_from_tool_call(tool_name: str) -> str:
    return {
        "list_papers": "paper_identity_resolution",
        "find_papers_by_identity": "paper_identity_resolution",
        "search_reading_locations": "lexical_search",
        "read_locations": "source_quote_reading",
        "get_citation_edges": "citation_graph_traversal",
    }.get(tool_name, "unknown_tool_strategy")


def _normalize_status(value: Any) -> str:
    raw = str(value or "").lower()
    return {
        "completed": "COMPLETED",
        "completed_precise": "COMPLETED",
        "complete": "COMPLETED",
        "complete_precise": "COMPLETED",
        "answered": "COMPLETED",
        "ok": "COMPLETED",
        "ok_precise": "COMPLETED",
        "success": "COMPLETED",
        "resolved": "COMPLETED",
        "needs_clarification": "NEEDS_CLARIFICATION",
        "clarify": "NEEDS_CLARIFICATION",
        "incomplete": "INCOMPLETE_PRECISE",
        "incomplete_precise": "INCOMPLETE_PRECISE",
        "abstained": "INCOMPLETE_PRECISE",
    }.get(raw, str(value or "INCOMPLETE_PRECISE").upper())


def _normalize_answer_type(value: Any) -> str:
    raw = str(value or "").lower()
    return {
        "factual": "exact_fact",
        "fact": "exact_fact",
        "factoid": "exact_fact",
        "exact": "exact_fact",
        "architecture_details": "exact_fact",
        "factual_specification": "exact_fact",
        "descriptive": "exact_fact",
        "compare": "comparison_matrix",
        "comparison": "comparison_matrix",
        "genealogy": "citation_genealogy",
        "citation_graph": "citation_genealogy",
        "conflict": "contradiction_arbitration",
        "contradiction": "contradiction_arbitration",
        "resolution": "contradiction_arbitration",
        "uncertainty": "uncertainty_boundary",
        "clarification": "ambiguity_clarification",
    }.get(raw, raw)


def _normalize_claim_status(value: Any) -> str:
    raw = str(value or "").lower()
    return {
        "support": "supported",
        "verified": "supported",
        "true": "supported",
        "unsupported": "underdetermined",
        "unknown": "underdetermined",
    }.get(raw, raw)


def _failed_structural_obligations(case: JsonMap, accepted_anchor_ids: set[Any], strategies: set[Any]) -> set[str]:
    failed: set[str] = set()
    for raw_obligation in as_list(child_map(case.get("required_trace")).get("obligations")):
        obligation = child_map(raw_obligation)
        obligation_id = obligation.get("id")
        if not obligation_id:
            continue
        for anchor_id in as_list(obligation.get("must_include_anchor_ids")):
            if anchor_id not in accepted_anchor_ids:
                failed.add(str(obligation_id))
        for strategy in as_list(obligation.get("must_include_strategy")):
            if strategy not in strategies:
                failed.add(str(obligation_id))
    return failed


def _now() -> str:
    return datetime.now(UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z")
