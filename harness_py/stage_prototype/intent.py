from __future__ import annotations

import json
import re
from dataclasses import replace
from time import perf_counter

from ..llm import ChatModel
from ..models import GoldenDataset, JsonMap, child_map
from .models import Obligation, TaskFrame, TurnDecision, TurnFrame
from .plans import PARADIGM_RECIPES, get_paradigm, intent_catalog_text


class IntentRecognitionError(RuntimeError):
    pass


TURN_DECISION_TOOL: JsonMap = {
    "type": "function",
    "function": {
        "name": "submit_turn_decision",
        "description": "Return the single authoritative interpretation and evidence obligations for the current turn.",
        "parameters": {
            "type": "object",
            "properties": {
                "route": {"type": "string", "enum": ["direct", "clarify", "research"]},
                "effective_goal": {"type": "string"},
                "task": {
                    "type": "object",
                    "properties": {
                        "verb": {"type": "string"},
                        "object": {"type": "string"},
                    },
                    "additionalProperties": True,
                },
                "constraints": {"type": "object", "additionalProperties": True},
                "primary_paradigm": {"type": "string"},
                "answer_shape": {"type": "string"},
                "obligations": {
                    "type": "array",
                    "maxItems": 16,
                    "items": {
                        "type": "object",
                        "required": [
                            "id", "description", "kind", "paper_scope", "required", "answer_field"
                        ],
                        "properties": {
                            "id": {"type": "string", "maxLength": 80},
                            "description": {"type": "string", "maxLength": 320},
                            "kind": {
                                "type": "string",
                                "enum": [
                                    "fact",
                                    "comparison_cell",
                                    "relation",
                                    "hop",
                                    "procedure_item",
                                    "conflict_side",
                                    "conflict_resolution",
                                    "recommendation",
                                    "corpus_boundary",
                                    "clarification",
                                    "analysis_step",
                                ],
                            },
                            "paper_scope": {
                                "type": "array",
                                "maxItems": 8,
                                "items": {"type": "string"},
                            },
                            "required": {"type": "boolean"},
                            "answer_field": {"type": "string", "maxLength": 80},
                        },
                        "additionalProperties": False,
                    },
                },
                "assumption": {"type": "string"},
                "blocking_reason": {"type": ["string", "null"]},
                "direct_reply": {"type": "string"},
                "pending_interaction": {
                    "type": "object",
                    "required": ["interaction_id", "kind", "question", "options"],
                    "properties": {
                        "interaction_id": {"type": "string", "maxLength": 100},
                        "kind": {"type": "string", "enum": ["none", "choice", "free_text"]},
                        "question": {"type": "string", "maxLength": 500},
                        "options": {
                            "type": "array",
                            "maxItems": 6,
                            "items": {
                                "type": "object",
                                "required": ["id", "label", "task_patch"],
                                "properties": {
                                    "id": {"type": "string", "maxLength": 80},
                                    "label": {"type": "string", "maxLength": 160},
                                    "task_patch": {"type": "object", "additionalProperties": True},
                                },
                                "additionalProperties": False,
                            },
                        },
                    },
                    "additionalProperties": False,
                },
                "paper_references": {"type": "array", "items": {"type": "object"}},
                "requested_aspects": {"type": "array", "items": {"type": "string"}},
                "required_evidence_types": {"type": "array", "items": {"type": "string"}},
                "required_capabilities": {"type": "array", "items": {"type": "string"}},
                "requires_corpus_observation": {"type": "boolean"},
                "confidence": {"type": "number"},
            },
            "required": [
                "route",
                "effective_goal",
                "task",
                "constraints",
                "primary_paradigm",
                "answer_shape",
                "obligations",
                "assumption",
                "blocking_reason",
                "direct_reply",
                "pending_interaction",
                "paper_references",
                "requested_aspects",
                "requires_corpus_observation",
                "required_evidence_types",
                "required_capabilities",
                "confidence",
            ],
            "additionalProperties": False,
        },
    },
}


class TurnInterpreter:
    def __init__(self, model: ChatModel, max_tokens: int = 2400):
        self.model = model
        self.max_tokens = max_tokens
        self.last_diagnostics: JsonMap = {}

    def interpret(self, turn: TurnFrame, dataset: GoldenDataset) -> TurnDecision:
        started = perf_counter()
        response = self.model.complete_required_tool(
            self._messages(turn, dataset),
            [TURN_DECISION_TOOL],
            "submit_turn_decision",
            self.max_tokens,
        )
        usage = child_map(response.raw.get("usage"))
        prompt_tokens = int(usage.get("prompt_tokens") or 0)
        completion_tokens = int(usage.get("completion_tokens") or 0)
        self.last_diagnostics = {
            "model_call_count": 1,
            "prompt_tokens": prompt_tokens,
            "completion_tokens": completion_tokens,
            "total_tokens": int(usage.get("total_tokens") or prompt_tokens + completion_tokens),
            "model_latency_ms": round((perf_counter() - started) * 1000),
        }
        calls = [call for call in response.tool_calls if call.name == "submit_turn_decision"]
        if len(calls) != 1:
            raise IntentRecognitionError("turn model did not submit one structured TurnDecision")
        decision = _normalize_turn_decision(TurnDecision.from_dict(calls[0].arguments), turn)
        self._validate(decision, turn)
        return decision

    def _validate(self, decision: TurnDecision, turn: TurnFrame) -> None:
        if decision.route == "direct":
            if not decision.direct_reply:
                raise IntentRecognitionError("direct TurnDecision requires direct_reply")
            has_active_work = bool(
                turn.conversation_context.get("active_task")
                or turn.conversation_context.get("pending_interaction")
            )
            if has_active_work and str(decision.task.get("verb") or "") != "cancel":
                raise IntentRecognitionError("direct TurnDecision cannot discard an active task")
            return
        if decision.route == "clarify":
            pending = decision.pending_interaction
            if (
                not decision.effective_goal
                or not pending.get("interaction_id")
                or not pending.get("question")
            ):
                raise IntentRecognitionError("clarify TurnDecision requires pending_interaction")
            return
        if decision.route != "research":
            raise IntentRecognitionError(f"unsupported turn route: {decision.route}")
        if not decision.effective_goal:
            raise IntentRecognitionError("research TurnDecision requires effective_goal")
        if not decision.task.get("verb") or not decision.task.get("object"):
            raise IntentRecognitionError("research TurnDecision requires task verb and object")
        if decision.primary_paradigm not in PARADIGM_RECIPES:
            raise IntentRecognitionError(f"unsupported paradigm: {decision.primary_paradigm}")
        if decision.requires_corpus_observation and not decision.obligations:
            raise IntentRecognitionError("corpus-grounded research requires evidence obligations")
        obligation_ids = [item.obligation_id for item in decision.obligations]
        if len(set(obligation_ids)) != len(obligation_ids):
            raise IntentRecognitionError("research obligations require unique ids")
        if any(not item.description for item in decision.obligations):
            raise IntentRecognitionError("research obligations require descriptions")

    def _messages(self, turn: TurnFrame, dataset: GoldenDataset) -> list[JsonMap]:
        obligation_guidance = "\n".join(
            f"- {recipe.paradigm_id}: {recipe.obligation_guidance}"
            for recipe in PARADIGM_RECIPES.values()
        )
        return [
            {
                "role": "system",
                "content": (
                    "TURN_DECISION\n"
                    "You are the sole owner of conversation-turn interpretation for a paper research harness. "
                    "Call submit_turn_decision exactly once. Do not answer through ordinary assistant prose.\n\n"
                    "Choose direct for greetings or ordinary conversation that requires no paper research. "
                    "Choose clarify only when unresolved paper identity, unresolved conversation reference, "
                    "or a missing hard constraint makes research unsafe. Broad scope, missing preferences, "
                    "and words such as 'important' are not blocking; choose research and state an assumption. A bare "
                    "paper nickname, topic fragment, or paper title with no requested action is blocking: ask one focused "
                    "question about the intended paper or task instead of silently turning it into a summary.\n\n"
                    "When conversation_context contains active_task or pending_interaction, interpret the current "
                    "message as a continuation. Merge a resolved choice, confirmation, correction, or constraint "
                    "into effective_goal and choose research immediately. Never return a choice acknowledgement "
                    "as the completed user task. Preserve prior active-task obligations when they remain relevant.\n\n"
                    "Recent messages are also authoritative when structured pending state is unavailable. If the last "
                    "assistant message offered numbered choices or proposed comparison axes and the current user message "
                    "selects, confirms, or refines that offer, resolve it against that message and resume the original "
                    "task immediately. Do not reinterpret the selected paper as a new request for summary or critique.\n\n"
                    "pending_interaction is always an object. For direct or research routes, use kind=none with empty "
                    "interaction_id, question, and options. For clarify, use kind=choice or free_text and provide a "
                    "stable interaction_id, one focused question, and optional choices with id, label, and task_patch.\n\n"
                    "For research, create the smallest complete set of semantic evidence obligations. An obligation "
                    "states what must be established, not which tool to call and not the expected answer. Write obligation "
                    "descriptions as neutral questions or slots: do not embed a guessed value, architecture exclusion, "
                    "causal explanation, or other candidate answer in the description. Split exact "
                    "fact slots, comparison target-axis cells, conflict sides, and multi-hop edges. Do not create "
                    "obligations for optional background, generic explanations, or unrequested recommendations. "
                    "Use paper_scope for titles, paper ids, or source descriptions explicitly constrained by the user. "
                    "For a requested scalar fact, set answer_field to the concise user-facing field name, such as "
                    "beta1 rather than a source-prefixed name; otherwise set answer_field to an empty string. Evidence "
                    "quotes, source locations, and background distinctions are support for an obligation, not separate "
                    "obligations unless the user explicitly requested them. Use corpus_boundary for a corpus-coverage "
                    "decision.\n\n"
                    "Select the paradigm from the requested operation, not from a noun used only as a filter. Every "
                    "request to recommend or suggest papers uses task.verb=recommend, task.object=papers, and "
                    "primary_paradigm=context_specific_brainstorming. Use meta_analysis_systematic_review only when "
                    "the user asks to conduct a review across studies. Use complex_multihop_reasoning when the answer "
                    "depends on composing two or more separately evidenced hops through an intermediate node; create "
                    "one kind=hop obligation per requested hop. Use association_influence_genealogy for a direct lineage "
                    "or citation relationship, not for a dependent multi-hop chain. A prohibition such as 'do not invent "
                    "a direct edge' is a claim constraint, not a separate absence-search obligation unless the user "
                    "explicitly asks to verify that the edge is absent. For words such as 'important', use corpus-relative "
                    "breadth and methodological salience unless tools expose external citation metrics.\n\n"
                    "The user-visible research corpus is the fixed paper inventory supplied below. If a named paper or "
                    "model is absent and the user asks for its paper-grounded details, use "
                    "uncertainty_knowledge_boundary and create one corpus_boundary obligation describing the unsupported "
                    "scope. Do not invent separate fact-slot obligations for details that the inventory cannot cover, and "
                    "do not infer an absent model's architecture from an earlier related paper.\n\n"
                    "A corpus recommendation or paper-content answer sets requires_corpus_observation=true.\n\n"
                    "PARADIGM CATALOG\n"
                    f"{intent_catalog_text()}\n\n"
                    "OBLIGATION GUIDANCE\n"
                    f"{obligation_guidance}"
                ),
            },
            {
                "role": "user",
                "content": json.dumps({
                    "user_message": turn.question,
                    "conversation_context": turn.conversation_context,
                    "corpus": {
                        "paper_count": len(dataset.paper_records_by_id),
                        "reading_model_count": len(dataset.reading_models_by_paper_id),
                        "papers": [
                            {
                                "paper_id": paper_id,
                                "title": child_map(record.get("identity")).get("title"),
                                "year": child_map(record.get("identity")).get("year"),
                            }
                            for paper_id, record in dataset.paper_records_by_id.items()
                        ],
                    },
                }, ensure_ascii=False),
            },
        ]


def task_frame_for_decision(decision: TurnDecision) -> TaskFrame:
    recipe = get_paradigm(
        "ambiguity_resolution" if decision.route == "clarify" else decision.primary_paradigm
    )
    task = decision.to_task_frame()
    if task.answer_shape == "unknown":
        return TaskFrame(
            **{
                **task.to_dict(),
                "answer_shape": recipe.default_answer_shape,
                "obligations": task.obligations,
            }
        )
    return task


def _normalize_turn_decision(
    decision: TurnDecision,
    turn: TurnFrame | None = None,
) -> TurnDecision:
    unresolved_precision_scope = (
        decision.route == "research"
        and decision.primary_paradigm == "precision_fact_extraction"
        and any(len(set(obligation.paper_scope)) > 1 for obligation in decision.obligations)
    )
    if unresolved_precision_scope:
        return replace(
            decision,
            route="clarify",
            primary_paradigm="ambiguity_resolution",
            answer_shape=get_paradigm("ambiguity_resolution").default_answer_shape,
            obligations=[Obligation(
                obligation_id="clarify_paper_and_task",
                description="Resolve the intended paper and requested research action.",
                kind="clarification",
                required=True,
            )],
            blocking_reason="paper identity or requested action is unresolved",
            direct_reply="",
            pending_interaction={
                "interaction_id": "clarify_paper_and_task",
                "kind": "free_text",
                "question": "Which paper do you mean, and what would you like to know about it?",
                "options": [],
            },
            requires_corpus_observation=False,
        )

    recent_messages = (
        turn.conversation_context.get("recent_messages", [])
        if turn is not None else []
    )
    selected_paper = str(decision.constraints.get("selected_paper") or "").strip()
    if selected_paper and len(recent_messages) >= 2:
        selected_scope = next(
            (
                scope
                for obligation in decision.obligations
                for scope in obligation.paper_scope
                if scope
            ),
            selected_paper,
        )
        focus = str(decision.constraints.get("focus") or "the active research goal")
        decision = replace(
            decision,
            task={"verb": "recommend", "object": "paper"},
            primary_paradigm="context_specific_brainstorming",
            answer_shape=get_paradigm("context_specific_brainstorming").default_answer_shape,
            obligations=[Obligation(
                obligation_id="selected_paper_fit",
                description=f"Explain from source evidence why the selected paper fits {focus}.",
                kind="recommendation",
                paper_scope=[selected_scope],
                required=True,
                answer_field="",
            )],
        )

    if decision.primary_paradigm == "precision_fact_extraction":
        requested_count = len([
            aspect for aspect in decision.requested_aspects
            if aspect not in {
                scope
                for obligation in decision.obligations
                for scope in obligation.paper_scope
            }
        ])
        scalar_obligations = [
            obligation for obligation in decision.obligations
            if obligation.required and obligation.answer_field
        ]
        if requested_count and len(scalar_obligations) > requested_count:
            decision = replace(
                decision,
                obligations=scalar_obligations[:requested_count],
            )

    if decision.primary_paradigm == "deep_comparison":
        targets = _comparison_targets(decision)
        aspects = [
            aspect for aspect in decision.requested_aspects
            if aspect not in set(targets)
        ]
        if len(targets) >= 2 and aspects:
            decision = replace(
                decision,
                obligations=[
                    Obligation(
                        obligation_id=f"{_slug(target)}_{_slug(aspect)}"[:80],
                        description=f"Determine {aspect} for {target} from source evidence.",
                        kind="comparison_cell",
                        paper_scope=[target],
                        required=True,
                        answer_field=f"{_slug(target)}_{_slug(aspect)}"[:80],
                    )
                    for aspect in aspects
                    for target in targets
                ],
            )
        target_cells = [
            obligation for obligation in decision.obligations
            if obligation.required
            and obligation.kind == "comparison_cell"
            and len(set(obligation.paper_scope)) == 1
        ]
        target_scopes = {
            obligation.paper_scope[0] for obligation in target_cells
        }
        if len(target_cells) >= 2 and len(target_scopes) >= 2:
            decision = replace(
                decision,
                obligations=[
                    replace(
                        obligation,
                        description=(
                            f"Determine {(obligation.answer_field or 'the requested comparison cell').replace('_', ' ')} "
                            f"for {obligation.paper_scope[0]} from source evidence."
                        ),
                    )
                    for obligation in target_cells
                ],
            )

    if decision.primary_paradigm == "association_influence_genealogy":
        edge_obligations = [
            obligation for obligation in decision.obligations
            if obligation.required
            and obligation.kind == "relation"
            and len(set(obligation.paper_scope)) >= 2
        ]
        if edge_obligations:
            decision = replace(decision, obligations=edge_obligations)

    if decision.primary_paradigm == "contradiction_resolution":
        side_obligations: list[Obligation] = []
        seen_scopes: set[str] = set()
        for obligation in decision.obligations:
            if not obligation.required or len(set(obligation.paper_scope)) != 1:
                continue
            scope = obligation.paper_scope[0]
            if scope in seen_scopes:
                continue
            seen_scopes.add(scope)
            side_obligations.append(replace(obligation, kind="conflict_side"))
        resolution = next(
            (
                replace(obligation, kind="conflict_resolution")
                for obligation in decision.obligations
                if obligation.required
                and (
                    obligation.kind == "conflict_resolution"
                    or len(set(obligation.paper_scope)) >= 2
                )
            ),
            None,
        )
        if len(side_obligations) >= 2 and resolution is not None:
            decision = replace(
                decision,
                obligations=[*side_obligations[:2], resolution],
            )

    edge_obligations = []
    seen_edges: set[tuple[str, ...]] = set()
    for obligation in decision.obligations:
        edge = tuple(sorted(set(obligation.paper_scope)))
        if obligation.required and obligation.kind in {"relation", "hop"} and len(edge) >= 2:
            if edge not in seen_edges:
                seen_edges.add(edge)
                edge_obligations.append(replace(obligation, kind="hop", answer_field=""))
    dependent_chain = any(
        set(left.paper_scope) & set(right.paper_scope)
        and len(set(left.paper_scope) | set(right.paper_scope)) >= 3
        for index, left in enumerate(edge_obligations)
        for right in edge_obligations[index + 1:]
    )
    if not dependent_chain:
        return decision
    return replace(
        decision,
        primary_paradigm="complex_multihop_reasoning",
        answer_shape=get_paradigm("complex_multihop_reasoning").default_answer_shape,
        obligations=edge_obligations,
    )


def _comparison_targets(decision: TurnDecision) -> list[str]:
    targets: list[str] = []
    for obligation in decision.obligations:
        if len(set(obligation.paper_scope)) != 1:
            continue
        target = obligation.paper_scope[0]
        if target and target not in targets:
            targets.append(target)
    if len(targets) >= 2:
        return targets
    for reference in decision.paper_references:
        target = str(reference.get("paper_id") or reference.get("text") or "").strip()
        if target and target not in targets:
            targets.append(target)
    return targets


def _slug(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", value.lower()).strip("_") or "item"
