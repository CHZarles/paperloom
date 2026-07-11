from __future__ import annotations

import json
from time import perf_counter

from ..llm import ChatModel
from ..models import GoldenDataset, JsonMap, child_map
from .models import IntentFrame, TurnDecision, TurnFrame
from .plans import PARADIGM_DEFINITIONS, get_paradigm, intent_catalog_text


class IntentRecognitionError(RuntimeError):
    pass


TURN_DECISION_TOOL: JsonMap = {
    "type": "function",
    "function": {
        "name": "submit_turn_decision",
        "description": "Return the single authoritative interpretation of the current conversation turn.",
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
                "assumption": {"type": "string"},
                "blocking_reason": {"type": ["string", "null"]},
                "direct_reply": {"type": "string"},
                "pending_interaction": {"type": ["object", "null"]},
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
                "assumption",
                "blocking_reason",
                "direct_reply",
                "pending_interaction",
                "requires_corpus_observation",
                "required_evidence_types",
                "required_capabilities",
            ],
            "additionalProperties": False,
        },
    },
}


class TurnInterpreter:
    def __init__(self, model: ChatModel, max_tokens: int = 1400):
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
        decision = TurnDecision.from_dict(calls[0].arguments)
        if decision.route == "direct":
            if not decision.direct_reply:
                raise IntentRecognitionError("direct TurnDecision requires direct_reply")
            has_active_work = bool(
                turn.conversation_context.get("active_task")
                or turn.conversation_context.get("pending_interaction")
            )
            if has_active_work and str(decision.task.get("verb") or "") != "cancel":
                raise IntentRecognitionError("direct TurnDecision cannot discard an active task")
            return decision
        if decision.route == "clarify":
            pending = decision.pending_interaction
            if (
                not decision.effective_goal
                or not pending.get("interaction_id")
                or not pending.get("question")
            ):
                raise IntentRecognitionError("clarify TurnDecision requires pending_interaction")
            return decision
        if decision.route != "research":
            raise IntentRecognitionError(f"unsupported turn route: {decision.route}")
        if not decision.effective_goal:
            raise IntentRecognitionError("research TurnDecision requires effective_goal")
        if not decision.task.get("verb") or not decision.task.get("object"):
            raise IntentRecognitionError("research TurnDecision requires task verb and object")
        if decision.primary_paradigm not in PARADIGM_DEFINITIONS:
            raise IntentRecognitionError(f"unsupported paradigm: {decision.primary_paradigm}")
        if decision.blocking_reason:
            raise IntentRecognitionError("research TurnDecision cannot retain a blocking_reason")
        return decision

    def _messages(self, turn: TurnFrame, dataset: GoldenDataset) -> list[JsonMap]:
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
                    "and words such as 'important' are not blocking; choose research and state an assumption.\n\n"
                    "When conversation_context contains active_task or pending_interaction, interpret the current "
                    "message as a continuation. Merge a resolved choice, confirmation, correction, or constraint "
                    "into effective_goal and choose research immediately. Never return a choice acknowledgement "
                    "as the completed user task.\n\n"
                    "Represent semantics as task.verb, task.object, and constraints. Select the paradigm from the "
                    "requested operation, not from a noun used only as a filter. For example, recommending "
                    "systematic-review papers is a recommendation with paper_type=systematic_review; conducting "
                    "a systematic review is meta_analysis_systematic_review. Every request to recommend or suggest "
                    "papers uses task.verb=recommend, task.object=papers, primary_paradigm="
                    "context_specific_brainstorming, and answer_shape=constraint_filter. Requested genres, topics, "
                    "years, and methods remain constraints. Use meta_analysis_systematic_review only when the user "
                    "asks the harness to conduct or synthesize a review across studies. For words such as "
                    "'important' or 'influential', use corpus-relative breadth and methodological salience unless "
                    "the available tools actually expose external citation metrics; do not require or claim "
                    "unobserved citation counts.\n\n"
                    "A corpus recommendation or paper-content answer sets requires_corpus_observation=true.\n\n"
                    "PARADIGM CATALOG\n"
                    f"{intent_catalog_text()}"
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
                    },
                }, ensure_ascii=False),
            },
        ]


class IntentRecognizer:
    def __init__(self, model: ChatModel, max_tokens: int = 1400):
        self.model = model
        self.max_tokens = max_tokens

    def recognize(self, turn: TurnFrame, dataset: GoldenDataset) -> IntentFrame:
        messages = self._messages(turn, dataset)
        response = self.model.complete(messages, [], self.max_tokens)
        payload = _parse_json_object(response.content)
        if payload is None:
            messages.extend([
                {"role": "assistant", "content": response.content},
                {
                    "role": "user",
                    "content": "Return exactly one valid JSON object matching the required intent schema.",
                },
            ])
            response = self.model.complete(messages, [], self.max_tokens)
            payload = _parse_json_object(response.content)
        if payload is None:
            raise IntentRecognitionError("intent model did not return parseable JSON")
        intent = IntentFrame.from_dict(payload)
        if intent.primary_paradigm not in PARADIGM_DEFINITIONS:
            raise IntentRecognitionError(f"unsupported paradigm: {intent.primary_paradigm}")
        ambiguity = _normalize_ambiguity(intent.ambiguity)
        actionability = _normalize_actionability(
            payload.get("actionability") if "actionability" in payload else None,
            ambiguity,
        )
        primary_paradigm = intent.primary_paradigm
        if actionability == "blocking":
            primary_paradigm = "ambiguity_resolution"
        definition = get_paradigm(primary_paradigm)
        answer_shape = intent.answer_shape
        if primary_paradigm != intent.primary_paradigm or answer_shape == "unknown":
            answer_shape = definition.default_answer_shape
        return IntentFrame(
            primary_paradigm=primary_paradigm,
            normalized_goal=intent.normalized_goal or turn.question,
            answer_shape=answer_shape,
            paper_references=intent.paper_references,
            requested_aspects=intent.requested_aspects,
            constraints=intent.constraints,
            ambiguity=ambiguity,
            confidence=intent.confidence,
            required_evidence_types=intent.required_evidence_types,
            required_capabilities=intent.required_capabilities,
            actionability=actionability,
            requires_corpus_observation=intent.requires_corpus_observation,
        )

    def _messages(self, turn: TurnFrame, dataset: GoldenDataset) -> list[JsonMap]:
        return [
            {
                "role": "system",
                "content": (
                    "INTENT_RECOGNITION\n"
                    "Classify one research turn. Choose exactly one primary_paradigm from the catalog. "
                    "Do not answer the research question and do not retrieve evidence. Use conversation "
                    "context only to resolve references such as 'the first two'. Extract constraints and "
                    "the requested answer shape. Return one JSON object and no prose.\n\n"
                    "PARADIGM CATALOG\n"
                    f"{intent_catalog_text()}\n\n"
                    "PARADIGM SELECTION RULES\n"
                    "- Label actionability as none when the target is clear, non_blocking when a useful "
                    "corpus-grounded answer can proceed under a stated assumption or coverage policy, "
                    "and blocking only when proceeding risks the wrong identity, version, relation, or "
                    "hard constraint. Broadness or a missing preference is non_blocking, not blocking.\n"
                    "- Choose complex_multihop_reasoning over association_influence_genealogy when "
                    "the user explicitly requires two or more dependent hops, an intermediate node, "
                    "or a conclusion that is invalid if the hops are collapsed into a direct edge.\n"
                    "- Choose association_influence_genealogy when the citation or influence graph "
                    "itself is the result and its edges can be reported independently.\n"
                    "- Choose methodology_reproduction over precision_fact_extraction when the user "
                    "asks for a procedure, protocol, experiment design, or reproduction checklist, "
                    "even when some requested items are exact values.\n"
                    "- Choose ambiguity_resolution when a user decision is required before any other "
                    "research paradigm can be executed safely.\n\n"
                    "- Choose uncertainty_knowledge_boundary when the requested entity, version, time "
                    "period, or required evidence is absent from the available corpus. Do not choose "
                    "precision_fact_extraction merely because the user asks for exact details that the "
                    "corpus cannot support.\n"
                    "- Choose concept_tracing_definition for origin and meaning evolution; choose "
                    "association_influence_genealogy for influence or citation relationships.\n"
                    "- Choose contribution_critical_assessment when novelty, limitations, or overclaim "
                    "must be judged; choose deep_comparison for a symmetric comparison over declared axes.\n"
                    "- Choose data_benchmark_provenance for construction and version history; choose "
                    "dataset_contamination_generalization for leakage, overlap, shortcuts, or shift diagnosis.\n"
                    "- Choose hypothetical_discussion when one assumption is deliberately changed; "
                    "choose future_prediction_open_problem for a time-bounded forecast from trend evidence.\n"
                    "- Choose multimodal_cross_domain_system_design when components and interfaces must "
                    "form an architecture; choose context_specific_brainstorming for evidence-bounded options.\n"
                    "- Choose theory_math_formal_proof for derivation or proof reconstruction; choose "
                    "structured_info_nontraditional_query when the main target is a table, figure, formula, or algorithm.\n"
                    "- Choose meta_analysis_systematic_review for protocol-driven synthesis across many "
                    "studies; choose boundary_failure_counterexample when failures delimit a broad claim.\n"
                    "- Choose complex_constraint_combination when all hard constraints must be intersected "
                    "without relaxation, and author_institution_research_style for a scoped research trajectory.\n\n"
                    "Set requires_corpus_observation true when the intended answer recommends papers "
                    "from this corpus or makes corpus-grounded factual claims.\n\n"
                    "JSON schema: {primary_paradigm, normalized_goal, answer_shape, actionability, "
                    "requires_corpus_observation:boolean, "
                    "paper_references:[{text?,paper_id?,kind?}], requested_aspects:[string], "
                    "constraints:{}, ambiguity:{status,candidates?,clarification_question?}, "
                    "confidence:number, required_evidence_types:[string], required_capabilities:[string]}."
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
                    },
                }, ensure_ascii=False),
            },
        ]


def _parse_json_object(content: str) -> JsonMap | None:
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


def _normalize_ambiguity(value: JsonMap) -> JsonMap:
    ambiguity = dict(value or {})
    raw_status = str(ambiguity.get("status") or "unambiguous").lower()
    ambiguity["status"] = {
        "none": "unambiguous",
        "clear": "unambiguous",
        "low": "unambiguous",
        "resolved": "unambiguous",
    }.get(raw_status, raw_status)
    return ambiguity


def _normalize_actionability(value: object, ambiguity: JsonMap) -> str:
    raw = str(value or "").strip().lower()
    aliases = {
        "actionable": "none",
        "clear": "none",
        "unambiguous": "none",
        "assumption": "non_blocking",
        "proceed": "non_blocking",
        "needs_clarification": "blocking",
        "ambiguous": "blocking",
    }
    normalized = aliases.get(raw, raw)
    if normalized in {"none", "non_blocking", "blocking"}:
        return normalized
    return "blocking" if str(ambiguity.get("status")) in {"ambiguous", "needs_clarification"} else "none"
