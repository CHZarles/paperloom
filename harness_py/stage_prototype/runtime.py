from __future__ import annotations

import json
from dataclasses import dataclass, replace
from datetime import UTC, datetime
from time import perf_counter

from ..llm import ChatModel
from ..models import RUN_TRACE_SCHEMA_VERSION, GoldenDataset, JsonMap, as_list, child_map, stable_id
from ..tools import ReadingCorpusTools, json_tool_content
from .intent import IntentRecognitionError, IntentRecognizer, _parse_json_object
from .models import (
    IntentFrame,
    ResearchState,
    StageResult,
    StageSpec,
    StageTrace,
    TurnFrame,
    normalize_claim,
    unique_strings,
)
from .plans import ParadigmDefinition, get_paradigm


MAX_SELECTED_PAPER_IDS = 6


@dataclass(frozen=True)
class StageExecution:
    result: StageResult
    tool_trace: list[JsonMap]
    last_model_content: str


@dataclass
class _RunMetricsModel(ChatModel):
    delegate: ChatModel
    model_call_count: int = 0
    prompt_tokens: int = 0
    completion_tokens: int = 0
    total_tokens: int = 0
    model_latency_seconds: float = 0.0

    def complete(self, messages: list[JsonMap], tools: list[JsonMap], max_tokens: int):
        started = perf_counter()
        turn = self.delegate.complete(messages, tools, max_tokens)
        return self._record(turn, started)

    def complete_required_tool(
        self,
        messages: list[JsonMap],
        tools: list[JsonMap],
        required_tool_name: str,
        max_tokens: int,
    ):
        started = perf_counter()
        turn = self.delegate.complete_required_tool(messages, tools, required_tool_name, max_tokens)
        return self._record(turn, started)

    def _record(self, turn, started: float):
        self.model_latency_seconds += perf_counter() - started
        self.model_call_count += 1
        usage = child_map(turn.raw.get("usage"))
        prompt_tokens = int(usage.get("prompt_tokens") or 0)
        completion_tokens = int(usage.get("completion_tokens") or 0)
        self.prompt_tokens += prompt_tokens
        self.completion_tokens += completion_tokens
        self.total_tokens += int(usage.get("total_tokens") or prompt_tokens + completion_tokens)
        return turn

    def diagnostics(self) -> JsonMap:
        return {
            "model_call_count": self.model_call_count,
            "prompt_tokens": self.prompt_tokens,
            "completion_tokens": self.completion_tokens,
            "total_tokens": self.total_tokens,
            "model_latency_ms": round(self.model_latency_seconds * 1000),
        }


class StageRunner:
    def __init__(
        self,
        model: ChatModel,
        tools: ReadingCorpusTools,
        max_completion_tokens: int = 2400,
        max_model_turns: int | None = None,
    ):
        self.model = model
        self.tools = tools
        self.max_completion_tokens = max_completion_tokens
        self.max_model_turns = max_model_turns

    def run(
        self,
        stage: StageSpec,
        turn: TurnFrame,
        intent: IntentFrame,
        state: ResearchState,
        dataset: GoldenDataset,
    ) -> StageExecution:
        self.tools.authorized_paper_ids.update(
            paper_id for paper_id in [*state.authorized_paper_ids, *state.selected_paper_ids]
            if paper_id in dataset.paper_records_by_id
        )
        self.tools.disclosed_location_refs.update(
            str(item.get("location_ref") or item.get("location"))
            for item in state.evidence_items_by_id.values()
            if str(item.get("paper_id") or "") in self.tools.authorized_paper_ids
            and str(item.get("location_ref") or item.get("location")) in self.tools.documents_by_location
        )
        messages = self._messages(stage, turn, intent, state, dataset)
        definitions = _filter_tool_definitions(self.tools.definitions(), set(stage.allowed_tools))
        tool_trace: list[JsonMap] = []
        observed_before = set(self.tools.observations_by_evidence_id)
        last_content = ""
        result: StageResult | None = None
        stage_status_repair_attempted = False
        evidence_contract_repair_attempted = False
        candidate_observation_repair_attempted = False
        answer_contract_repair_attempted = False
        submission_tool = _stage_result_submission_tool(stage)
        limit = min(stage.max_model_turns, self.max_model_turns) if self.max_model_turns else stage.max_model_turns

        for _ in range(max(1, limit)):
            required_tool = _required_stage_tool(stage, definitions, tool_trace, submission_tool)
            required_tool_name = ""
            if required_tool:
                required_tool_name = str(child_map(required_tool.get("function")).get("name"))
                response = self.model.complete_required_tool(
                    messages,
                    [required_tool],
                    required_tool_name,
                    self.max_completion_tokens,
                )
            else:
                response = self.model.complete(messages, definitions, self.max_completion_tokens)
            if (
                required_tool_name in stage.allowed_tools
                and not any(call.name == required_tool_name for call in response.tool_calls)
            ):
                messages.extend([
                    {"role": "assistant", "content": response.content},
                    {
                        "role": "user",
                        "content": (
                            f"This stage is not ready for submission. Call {required_tool_name} now; "
                            "do not return a StageResult until that tool call has been executed."
                        ),
                    },
                ])
                continue
            submitted_payload = _submitted_stage_result(response)
            last_content = (
                json.dumps(submitted_payload, ensure_ascii=False, sort_keys=True)
                if submitted_payload is not None else
                response.content
            )
            submission_error = (
                _stage_submission_error(stage, submitted_payload)
                if submitted_payload is not None else
                ""
            )
            if submission_error:
                messages.append({
                    "role": "user",
                    "content": (
                        f"The submit_stage_result call violated its contract: {submission_error}. "
                        "Call submit_stage_result again with a complete compact payload."
                    ),
                })
                continue
            if response.tool_calls and submitted_payload is None:
                messages.append({
                    "role": "assistant",
                    "content": response.content,
                    "tool_calls": [
                        {
                            "id": call.id,
                            "type": "function",
                            "function": {"name": call.name, "arguments": json.dumps(call.arguments)},
                        }
                        for call in response.tool_calls
                    ],
                })
                for call in response.tool_calls:
                    violation: str | None = None
                    if len(tool_trace) >= stage.max_tool_calls:
                        payload = {"error": "stage_tool_budget_exhausted", "tool_name": call.name}
                        allowed = False
                        violation = "stage_tool_budget_exhausted"
                    elif call.name not in stage.allowed_tools:
                        payload = {"error": "tool_not_allowed_for_stage", "tool_name": call.name}
                        allowed = False
                        violation = "out_of_stage_tool_call"
                    else:
                        payload = self.tools.call(call.name, call.arguments).payload
                        allowed = True
                    tool_trace.append({
                        "tool_call_id": call.id,
                        "tool_name": call.name,
                        "arguments": call.arguments,
                        "allowed": allowed,
                        "violation": violation,
                        "result": payload,
                    })
                    messages.append({
                        "role": "tool",
                        "tool_call_id": call.id,
                        "name": call.name,
                        "content": json.dumps(payload, ensure_ascii=False, sort_keys=True),
                    })
                continue

            payload = submitted_payload if submitted_payload is not None else _parse_json_object(response.content)
            if payload is not None:
                candidate = StageResult.from_dict(payload)
                observed_so_far = set(self.tools.observations_by_evidence_id) - observed_before
                evidence_tool_attempted = any(
                    item.get("allowed")
                    and item.get("tool_name") in {"find_reading_locations", "read_locations"}
                    for item in tool_trace
                )
                candidate_tool_attempted = any(
                    item.get("allowed")
                    and item.get("tool_name") in {"search_paper_candidates", "find_papers_by_identity"}
                    for item in tool_trace
                )
                candidate_observation_required = (
                    intent.requires_corpus_observation
                    and _is_identity_stage(stage)
                    and not _has_candidate_observation(state.stage_trace)
                    and not _selection_is_conversation_authorized(candidate, state)
                )
                explicit_no_match = _explicit_no_match(candidate, tool_trace)
                evidence_contract_needs_repair = (
                    candidate.status == "completed"
                    and not (set(candidate.accepted_evidence_ids) & observed_so_far)
                    and not explicit_no_match
                ) or (
                    candidate.status != "completed"
                    and not evidence_tool_attempted
                    and not explicit_no_match
                )
                if (
                    stage.requires_new_evidence
                    and evidence_contract_needs_repair
                    and not evidence_contract_repair_attempted
                ):
                    evidence_contract_repair_attempted = True
                    messages.extend([
                        {"role": "assistant", "content": last_content},
                        {
                            "role": "user",
                            "content": (
                                "This stage requires newly observed inspectable evidence and must "
                                "use the authorized candidate -> location -> read sequence before either "
                                "completing or declaring precise incompleteness. Candidate cards, location "
                                "previews, and citation edges are not evidence. Call find_reading_locations "
                                "and then read_locations now, then accept only evidence_id values returned "
                                "by read_locations."
                            ),
                        },
                    ])
                    continue
                if (
                    candidate_observation_required
                    and not candidate_tool_attempted
                    and not candidate_observation_repair_attempted
                ):
                    candidate_observation_repair_attempted = True
                    messages.extend([
                        {"role": "assistant", "content": last_content},
                        {
                            "role": "user",
                            "content": (
                                "This corpus-grounded answer requires a candidate observation before "
                                "completion. Call search_paper_candidates or find_papers_by_identity now, then return "
                                "a corrected compact StageResult with a bounded selected_paper_ids list."
                            ),
                        },
                    ])
                    continue
                answer_contract_error = _answer_contract_error(candidate, state, observed_so_far)
                if (
                    stage.produces_answer
                    and answer_contract_error
                    and not answer_contract_repair_attempted
                ):
                    answer_contract_repair_attempted = True
                    messages.extend([
                        {"role": "assistant", "content": last_content},
                        {
                            "role": "user",
                            "content": (
                                f"The answer contract is invalid: {answer_contract_error}. "
                                "Use at most six actual atomic claims. Every supported claim must cite "
                                "known evidence, and answer.cited_claim_ids / cited_evidence_ids must refer "
                                "only to claims and evidence present in the submitted StageResult or current "
                                "research state. Return a corrected submit_stage_result payload now."
                            ),
                        },
                    ])
                    continue
                if (
                    not stage_status_repair_attempted
                    and _should_repair_stage_status(stage, candidate)
                ):
                    stage_status_repair_attempted = True
                    messages.extend([
                        {"role": "assistant", "content": last_content},
                        {
                            "role": "user",
                            "content": (
                                "That status treats work assigned to a later stage as a blocker. "
                                "Complete only this stage's own semantic goal using the observations "
                                "and state already available. Do not publish claims, draft the final "
                                "answer, or stop merely because downstream evidence is not available "
                                "yet. "
                                f"{_stage_repair_guidance(stage)} "
                                "Return a corrected "
                                "StageResult JSON object using exactly this compact contract: "
                                f"{_stage_output_contract(stage)}"
                            ),
                        },
                    ])
                    continue
                result = candidate
                break
            messages.extend([
                {"role": "assistant", "content": last_content},
                {
                    "role": "user",
                    "content": "Return exactly one valid StageResult JSON object and no prose.",
                },
            ])

        if result is None:
            messages.append({
                "role": "user",
                "content": "Do not call more tools. Return the final compact StageResult JSON now.",
            })
            response = self.model.complete_required_tool(
                messages,
                [submission_tool],
                "submit_stage_result",
                self.max_completion_tokens,
            )
            submitted_payload = _submitted_stage_result(response)
            last_content = (
                json.dumps(submitted_payload, ensure_ascii=False, sort_keys=True)
                if submitted_payload is not None else
                response.content
            )
            payload = submitted_payload if submitted_payload is not None else _parse_json_object(response.content)
            if payload is not None and not _stage_submission_error(stage, payload):
                result = StageResult.from_dict(payload)
            else:
                result = StageResult(
                    status="incomplete_precise",
                    decision_summary="Stage model did not return a valid structured result.",
                    missing_obligations=[{"stage": stage.name, "reason": "invalid_stage_submission"}],
                )

        observed_now = set(self.tools.observations_by_evidence_id) - observed_before
        result = self._validate_result(stage, result, intent, state, dataset, observed_now, tool_trace)
        return StageExecution(result=result, tool_trace=tool_trace, last_model_content=last_content)

    def _messages(
        self,
        stage: StageSpec,
        turn: TurnFrame,
        intent: IntentFrame,
        state: ResearchState,
        dataset: GoldenDataset,
    ) -> list[JsonMap]:
        output_contract = _stage_output_contract(stage)
        tool_budget_guidance = (
            f"At most {stage.max_tool_calls} tool calls are permitted in this stage; parallel calls "
            "each count separately. Keep search top_k small and batch evidence IDs into one "
            "read_locations call whenever possible. "
            if stage.allowed_tools else
            "No tools are permitted in this stage. "
        )
        answer_size_guidance = (
            "Do not repeat the answer prose across decision_summary, claims, summary, sections, and "
            "markdown. Use at most 6 atomic claims with concise text and cite at most 12 evidence ids. "
            "Keep each claim to one sentence, answer.markdown under 2500 characters, and answer.summary "
            "under 300 characters. Finish the answer cleanly; when space is tight, remove detail instead "
            "of ending mid-sentence or mid-table. Omit answer.sections and answer.fields unless the "
            "question or required answer fields genuinely need them. StageResult.status is authoritative: "
            "a complete answer that reports uncertainty still uses completed / COMPLETED; use "
            "incomplete_precise only when this stage could not deliver its required answer. "
            if stage.produces_answer else ""
        )
        evidence_size_guidance = (
            "Accept at most 6 evidence ids in this stage; choose the smallest set that covers the "
            "selected papers and do not repeat metadata ids when a reading-model passage is available. "
            if stage.requires_new_evidence else ""
        )
        interaction_guidance = (
            "If this is a blocking clarification answer, put a typed pending interaction in "
            "memory_update.pending_interaction. It must contain interaction_id, kind "
            "(choice or free_text), question, and options with stable ids, labels, and task_patch values.\n\n"
            if stage.produces_answer and intent.actionability == "blocking" else ""
        )
        stepwise_tool_guidance = (
            "This stage exposes required tools one step at a time: first "
            "find_reading_locations, then read_locations after locations are returned. Call the "
            "tool available in the current turn. A later allowed tool not shown in the current API "
            "call is not unavailable; the harness will expose it at the next step.\n\n"
            if stage.requires_new_evidence
            and set(stage.allowed_tools) == {"find_reading_locations", "read_locations"} else
            ""
        )
        return [
            {
                "role": "system",
                "content": (
                    f"SEMANTIC_STAGE:{stage.name}\n"
                    "Execute exactly one online semantic research stage. The stage goal governs all "
                    "tool calls and output. Do not perform later stages. Use only the tools provided. "
                    f"{tool_budget_guidance}"
                    "Judge status against this stage's own goal, not the final research answer. Work "
                    "explicitly assigned to a later stage is not a missing obligation and must not "
                    "cause needs_clarification or incomplete_precise. "
                    "Accepted evidence ids must come from tool results or prior research state. "
                    "If this stage requires new evidence, call a provided search/read tool before "
                    "returning completed and accept at least one evidence_id observed in this stage. "
                    "Supported claims must cite accepted evidence ids. Return one JSON object and no prose.\n\n"
                    "For non-answer stages, keep state_values under 600 characters. Store only short "
                    "stage decisions, identifiers, axes, or slot names; it is not a draft answer, and "
                    "must not contain inferred paper facts that belong to later evidence stages.\n\n"
                    f"{answer_size_guidance}"
                    f"{evidence_size_guidance}"
                    f"{interaction_guidance}"
                    f"{stepwise_tool_guidance}"
                    f"STAGE GOAL\n{stage.instruction}\n\n"
                    "STAGE CONTRACT\n"
                    f"{_stage_execution_contract(stage)}\n\n"
                    "OUTPUT CONTRACT\n"
                    f"{output_contract}\n"
                    "Omit fields that are not in this contract. Keep decision_summary concise. "
                    "When required_answer_field_names is non-empty, use those exact keys in "
                    "answer.fields."
                ),
            },
            {
                "role": "user",
                "content": json.dumps({
                    "question": turn.question,
                    "intent": intent.to_dict(),
                    "required_answer_field_names": turn.required_answer_field_names,
                    "conversation_context": turn.conversation_context,
                    "corpus": {
                        "paper_count": len(dataset.paper_records_by_id),
                        "reading_model_count": len(dataset.reading_models_by_paper_id),
                    },
                    "research_state": state.to_prompt_dict(),
                }, ensure_ascii=False),
            },
        ]

    def _validate_result(
        self,
        stage: StageSpec,
        result: StageResult,
        intent: IntentFrame,
        state: ResearchState,
        dataset: GoldenDataset,
        observed_now: set[str],
        tool_trace: list[JsonMap],
    ) -> StageResult:
        known_papers = set(dataset.paper_records_by_id) & self.tools.authorized_paper_ids
        selected = unique_strings(
            paper_id for paper_id in result.selected_paper_ids if paper_id in known_papers
        )
        candidate_cap_applied = len(selected) > MAX_SELECTED_PAPER_IDS
        selected = selected[:MAX_SELECTED_PAPER_IDS]
        known_evidence = set(state.evidence_items_by_id) | set(self.tools.observations_by_evidence_id)
        accepted = [evidence_id for evidence_id in result.accepted_evidence_ids if evidence_id in known_evidence]
        rejected = [evidence_id for evidence_id in result.rejected_evidence_ids if evidence_id in known_evidence]
        claims = result.claims if stage.produces_answer else []
        answer = result.answer if stage.produces_answer else {}
        if not _stage_collects_evidence(stage):
            accepted = []
            rejected = []
        missing = list(result.missing_obligations)
        status = result.status

        violations = {
            str(item.get("violation"))
            for item in tool_trace
            if item.get("violation")
        }
        if "out_of_stage_tool_call" in violations:
            status = "incomplete_precise"
            missing.append({"stage": stage.name, "reason": "out_of_stage_tool_call"})
        candidate_observed = bool(state.authorized_paper_ids) or _has_candidate_observation(state.stage_trace) or any(
            item.get("allowed")
            and item.get("tool_name") in {"search_paper_candidates", "find_papers_by_identity"}
            and not child_map(item.get("result")).get("error")
            for item in tool_trace
        )
        if intent.requires_corpus_observation and _is_identity_stage(stage) and not candidate_observed:
            status = "incomplete_precise"
            missing.append({"stage": stage.name, "reason": "candidate_observation_required"})
        explicit_no_match = _explicit_no_match(result, tool_trace)
        if stage.requires_new_evidence and not (set(accepted) & observed_now) and not explicit_no_match:
            status = "incomplete_precise"
            missing.append({"stage": stage.name, "reason": "required_stage_evidence_not_accepted"})
        if stage.produces_answer and status == "completed" and not result.answer:
            status = "incomplete_precise"
            missing.append({"stage": stage.name, "reason": "answer_draft_missing"})
        if (
            stage.produces_answer
            and intent.actionability == "non_blocking"
            and status == "incomplete_precise"
            and answer
            and not _has_terminal_runtime_missing(missing)
        ):
            status = "completed"
            answer = {**answer, "status": "COMPLETED"}
            missing = []
        if stage.produces_answer and answer:
            answer = {
                **answer,
                "status": {
                    "completed": "COMPLETED",
                    "needs_clarification": "NEEDS_CLARIFICATION",
                    "incomplete_precise": "INCOMPLETE_PRECISE",
                }[status],
            }

        return replace(
            result,
            status=status,
            selected_paper_ids=selected,
            accepted_evidence_ids=unique_strings(accepted),
            rejected_evidence_ids=unique_strings(rejected),
            claims=claims,
            answer=answer,
            missing_obligations=missing,
            diagnostics={
                **result.diagnostics,
                "observed_evidence_ids": sorted(observed_now),
                "tool_call_count": len(tool_trace),
                "candidate_cap_applied": candidate_cap_applied,
                "tool_budget_exhausted": "stage_tool_budget_exhausted" in violations,
                "explicit_no_match": explicit_no_match,
            },
        )


class ParadigmDrivenHarness:
    harness_id = "python_paradigm_stage_harness_v1"

    def __init__(self, model: ChatModel, max_turns: int = 8, max_completion_tokens: int = 3000):
        self.model = model
        self.max_turns = max_turns
        self.max_completion_tokens = max_completion_tokens

    def run_case(self, dataset: GoldenDataset, case: JsonMap) -> JsonMap:
        run_started = perf_counter()
        started_at = _now()
        turn = _turn_frame(dataset, case)
        scoped_dataset = _scope_dataset(dataset, turn.allowed_paper_ids)
        measured_model = _RunMetricsModel(self.model)
        try:
            intent = IntentRecognizer(
                measured_model,
                max_tokens=min(self.max_completion_tokens, 1600),
            ).recognize(turn, scoped_dataset)
            paradigm = get_paradigm(intent.primary_paradigm)
        except (IntentRecognitionError, ValueError) as error:
            return _technical_failure_run(
                case,
                started_at,
                str(error),
                self.harness_id,
                measured_model.diagnostics(),
                round((perf_counter() - run_started) * 1000),
            )

        return self._run_with_intent(
            dataset,
            case,
            turn,
            scoped_dataset,
            intent,
            paradigm,
            measured_model,
            started_at,
            run_started,
        )

    def run_case_with_intent(
        self,
        dataset: GoldenDataset,
        case: JsonMap,
        intent: IntentFrame,
    ) -> JsonMap:
        run_started = perf_counter()
        started_at = _now()
        turn = _turn_frame(dataset, case)
        scoped_dataset = _scope_dataset(dataset, turn.allowed_paper_ids)
        measured_model = _RunMetricsModel(self.model)
        try:
            paradigm = get_paradigm(intent.primary_paradigm)
        except ValueError as error:
            return _technical_failure_run(
                case,
                started_at,
                str(error),
                self.harness_id,
                measured_model.diagnostics(),
                round((perf_counter() - run_started) * 1000),
            )
        return self._run_with_intent(
            dataset,
            case,
            turn,
            scoped_dataset,
            intent,
            paradigm,
            measured_model,
            started_at,
            run_started,
        )

    def _run_with_intent(
        self,
        dataset: GoldenDataset,
        case: JsonMap,
        turn: TurnFrame,
        scoped_dataset: GoldenDataset,
        intent: IntentFrame,
        paradigm: ParadigmDefinition,
        measured_model: _RunMetricsModel,
        started_at: str,
        run_started: float,
    ) -> JsonMap:

        tools = ReadingCorpusTools(scoped_dataset)
        runner = StageRunner(
            measured_model,
            tools,
            max_completion_tokens=self.max_completion_tokens,
            max_model_turns=self.max_turns,
        )
        state = _initial_research_state(turn, scoped_dataset)
        last_content = ""
        for stage in paradigm.stages:
            execution = runner.run(stage, turn, intent, state, scoped_dataset)
            last_content = execution.last_model_content
            _apply_stage(state, stage, execution, intent, tools, scoped_dataset)
            if _is_terminal_stage_result(stage, execution.result, intent):
                break

        return _build_run(
            case=case,
            started_at=started_at,
            intent=intent,
            paradigm=paradigm,
            state=state,
            last_model_content=last_content,
            harness_id=self.harness_id,
            model_diagnostics=measured_model.diagnostics(),
            duration_ms=round((perf_counter() - run_started) * 1000),
        )


def _apply_stage(
    state: ResearchState,
    stage: StageSpec,
    execution: StageExecution,
    intent: IntentFrame,
    tools: ReadingCorpusTools,
    dataset: GoldenDataset,
) -> None:
    result = execution.result
    state.selected_paper_ids = unique_strings([
        *state.selected_paper_ids,
        *(paper_id for paper_id in result.selected_paper_ids if paper_id in dataset.paper_records_by_id),
    ])
    state.authorized_paper_ids = unique_strings([
        *state.authorized_paper_ids,
        *state.selected_paper_ids,
    ])
    for paper_id in result.selected_paper_ids:
        if paper_id in dataset.paper_records_by_id:
            state.paper_candidates_by_id[paper_id] = _paper_candidate_observation(dataset, paper_id)
    for evidence_id in result.accepted_evidence_ids:
        item = tools.observations_by_evidence_id.get(evidence_id)
        if item:
            state.evidence_items_by_id[evidence_id] = dict(item)
    for evidence_id in result.rejected_evidence_ids:
        item = tools.observations_by_evidence_id.get(evidence_id)
        if item:
            rejected = dict(item)
            rejected["evidence_quality"] = "rejected"
            state.rejected_evidence.append(rejected)

    known_evidence_ids = set(state.evidence_items_by_id)
    claim_ids: list[str] = []
    for index, raw_claim in enumerate(result.claims):
        claim = normalize_claim(raw_claim, known_evidence_ids, len(state.claims_by_id) + index)
        state.claims_by_id[claim["claim_id"]] = claim
        claim_ids.append(claim["claim_id"])
        for evidence_id in claim["supporting_evidence_ids"]:
            item = dict(state.evidence_items_by_id[evidence_id])
            item["supports_claim_ids"] = unique_strings([*as_list(item.get("supports_claim_ids")), claim["claim_id"]])
            state.evidence_items_by_id[evidence_id] = item
        for evidence_id in claim["refuting_evidence_ids"]:
            item = dict(state.evidence_items_by_id[evidence_id])
            item["refutes_claim_ids"] = unique_strings([*as_list(item.get("refutes_claim_ids")), claim["claim_id"]])
            state.evidence_items_by_id[evidence_id] = item

    state.state_values = {**state.state_values, **result.state_values}
    if _is_terminal_stage_result(stage, result, intent):
        state.missing_obligations.extend(result.missing_obligations)
    if result.answer:
        state.answer_draft = result.answer
    state.memory_update = {**state.memory_update, **result.memory_update}
    state.stage_trace.append(StageTrace(
        stage_name=stage.name,
        semantic_goal=stage.instruction,
        strategy=stage.strategy,
        completion_contract=_stage_execution_contract(stage),
        allowed_tools=list(stage.allowed_tools),
        max_model_turns=stage.max_model_turns,
        max_tool_calls=stage.max_tool_calls,
        status=result.status,
        decision_summary=result.decision_summary,
        tool_calls=execution.tool_trace,
        accepted_evidence_ids=result.accepted_evidence_ids,
        claim_ids=claim_ids,
        missing_obligations=result.missing_obligations,
    ))


def _build_run(
    case: JsonMap,
    started_at: str,
    intent: IntentFrame,
    paradigm: ParadigmDefinition,
    state: ResearchState,
    last_model_content: str,
    harness_id: str,
    model_diagnostics: JsonMap,
    duration_ms: int,
) -> JsonMap:
    case_id = str(case.get("id") or "turn")
    plan = _retrieval_plan(case, intent, paradigm, state)
    verification = _verification(case, intent, state, plan)
    answer = _research_answer(case_id, intent, state, verification)
    artifact = _reasoning_artifact(case_id, intent, state, answer)
    memory_update = {
        **state.memory_update,
        "selected_paper_ids": state.memory_update.get("selected_paper_ids") or state.selected_paper_ids,
        "selected_evidence_ids": state.memory_update.get("selected_evidence_ids") or answer["cited_evidence_ids"],
    }
    return {
        "schema_version": RUN_TRACE_SCHEMA_VERSION,
        "run_id": stable_id("run", case_id),
        "question_id": case_id,
        "case_id": case_id,
        "harness_id": harness_id,
        "started_at": started_at,
        "completed_at": _now(),
        "status": answer["status"],
        "result_status": answer["status"],
        "memory_update": memory_update,
        "intent_frame": _intent_artifact(case_id, case, intent),
        "retrieval_plan": plan,
        "stage_trace": [trace.to_dict() for trace in state.stage_trace],
        "paper_candidates": list(state.paper_candidates_by_id.values()),
        "evidence_ledger": {
            "ledger_id": stable_id("ledger", case_id),
            "question_id": case_id,
            "items": [
                *state.paper_candidates_by_id.values(),
                *state.evidence_items_by_id.values(),
            ],
            "rejected_items": state.rejected_evidence,
            "missing_evidence": state.missing_obligations,
        },
        "claim_graph": {
            "graph_id": stable_id("claim_graph", case_id),
            "question_id": case_id,
            "claims": list(state.claims_by_id.values()),
            "edges": [],
        },
        "reasoning_artifacts": [artifact],
        "verification_pass": verification,
        "research_answer": answer,
        "final_answer": answer,
        "diagnostics": {
            "finish_reason": "semantic_stage_plan_completed" if answer["status"] == "COMPLETED" else "semantic_stage_plan_stopped",
            "paradigm": intent.primary_paradigm,
            "completed_stage_count": sum(1 for trace in state.stage_trace if trace.status == "completed"),
            "stage_count": len(state.stage_trace),
            "tool_call_count": sum(len(trace.tool_calls) for trace in state.stage_trace),
            **model_diagnostics,
            "duration_ms": duration_ms,
            "last_model_content_preview": last_model_content[:1000],
        },
    }


def _intent_artifact(case_id: str, case: JsonMap, intent: IntentFrame) -> JsonMap:
    references = intent.paper_references
    mentions = [str(item.get("text") or item.get("paper_id") or "") for item in references if item]
    return {
        "intent_id": stable_id("intent", case_id),
        "question_id": case_id,
        "raw_question": case.get("raw_question") or _case_question(case),
        "normalized_question": intent.normalized_goal,
        "primary_paradigm": intent.primary_paradigm,
        "entities": unique_strings([*mentions, *intent.requested_aspects]),
        "paper_mentions": unique_strings(mentions),
        "method_mentions": [],
        "dataset_mentions": [],
        "constraints": intent.constraints,
        "answer_type": intent.answer_shape,
        "ambiguity_status": intent.ambiguity_status,
        "actionability": intent.actionability,
        "requires_corpus_observation": intent.requires_corpus_observation,
        "required_evidence_types": intent.required_evidence_types,
        "required_capabilities": intent.required_capabilities,
    }


def _retrieval_plan(case: JsonMap, intent: IntentFrame, paradigm: ParadigmDefinition, state: ResearchState) -> JsonMap:
    steps: list[JsonMap] = []
    for index, stage in enumerate(paradigm.stages):
        steps.append({
            "step_id": stable_id("stage", f"{index + 1}_{stage.name}"),
            "stage_name": stage.name,
            "semantic_goal": stage.instruction,
            "strategy": stage.strategy,
            "allowed_tools": list(stage.allowed_tools),
            "stop_condition": "stage_contract_satisfied_or_precise_stop",
        })
    for trace in state.stage_trace:
        for call in trace.tool_calls:
            steps.append({
                "step_id": stable_id("tool", str(call.get("tool_call_id"))),
                "stage_name": trace.stage_name,
                "strategy": _tool_strategy(str(call.get("tool_name"))),
                "tool_name": call.get("tool_name"),
                "stop_condition": "tool_observation_returned",
            })
    scope = child_map(case.get("corpus_scope"))
    return {
        "plan_id": stable_id("plan", str(case.get("id"))),
        "question_id": case.get("id"),
        "paradigm": intent.primary_paradigm,
        "actionability": intent.actionability,
        "target_entities": [str(item.get("text") or item.get("paper_id") or "") for item in intent.paper_references],
        "strategy_steps": steps,
        "expected_evidence_types": intent.required_evidence_types,
        "required_recall_targets": as_list(scope.get("required_paper_ids")),
        "hard_negative_policy": as_list(scope.get("hard_negative_paper_ids")),
        "stop_conditions": ["needs_clarification", "incomplete_precise", "all_stages_completed"],
    }


def _verification(case: JsonMap, intent: IntentFrame, state: ResearchState, plan: JsonMap) -> JsonMap:
    evidence_ids = set(state.evidence_items_by_id)
    unsupported = [
        claim for claim in state.claims_by_id.values()
        if claim.get("status") == "supported"
        and (
            not as_list(claim.get("supporting_evidence_ids"))
            or not set(as_list(claim.get("supporting_evidence_ids"))) <= evidence_ids
        )
    ]
    strategies = {
        child_map(step).get("strategy")
        for step in as_list(plan.get("strategy_steps"))
        if child_map(step).get("strategy")
    }
    anchors = {
        item.get("matched_anchor_id")
        for item in state.evidence_items_by_id.values()
        if item.get("matched_anchor_id")
    }
    satisfied: list[str] = []
    failed: list[str] = []
    for raw in as_list(child_map(case.get("required_trace")).get("obligations")):
        obligation = child_map(raw)
        obligation_id = str(obligation.get("id") or "")
        if not obligation_id:
            continue
        missing_anchor = any(anchor_id not in anchors for anchor_id in as_list(obligation.get("must_include_anchor_ids")))
        missing_strategy = any(strategy not in strategies for strategy in as_list(obligation.get("must_include_strategy")))
        if missing_anchor or missing_strategy:
            failed.append(obligation_id)
        else:
            satisfied.append(obligation_id)
    stage_needs_clarification = (
        intent.actionability == "blocking"
        and any(trace.status == "needs_clarification" for trace in state.stage_trace)
    )
    stage_incomplete = any(
        trace.status == "incomplete_precise"
        and _stage_is_terminal_for_intent(trace.stage_name, intent)
        for trace in state.stage_trace
    )
    corpus_observation_required = intent.requires_corpus_observation
    corpus_observation_passed = (
        not corpus_observation_required
        or bool(state.authorized_paper_ids)
        or _has_candidate_observation(state.stage_trace)
    )
    missing_required_evidence = list(state.missing_obligations)
    if not corpus_observation_passed:
        missing_required_evidence.append({
            "reason": "corpus_candidate_observation_not_attempted",
            "required_tools": ["search_paper_candidates", "find_papers_by_identity"],
        })
    raw_answer = state.answer_draft
    answer_status = _normalize_answer_status(raw_answer.get("status") or "COMPLETED")
    cited_evidence = set(str(item) for item in as_list(raw_answer.get("cited_evidence_ids")))
    cited_claims = set(str(item) for item in as_list(raw_answer.get("cited_claim_ids")))
    unknown_answer_refs = bool(cited_evidence - evidence_ids or cited_claims - set(state.claims_by_id))
    return {
        "verification_id": stable_id("verification", str(case.get("id"))),
        "question_id": case.get("id"),
        "required_capabilities_attempted": intent.required_capabilities,
        "required_evidence_status": [
            {"evidence_id": evidence_id, "status": "accepted"}
            for evidence_id in state.evidence_items_by_id
        ],
        "unsupported_claim_count": len(unsupported),
        "contradicted_claim_count": sum(1 for claim in state.claims_by_id.values() if claim.get("status") == "contradicted"),
        "missing_required_evidence": missing_required_evidence,
        "ambiguity_resolution": {
            "status": intent.ambiguity_status,
            "requires_user_choice": stage_needs_clarification,
        },
        "constraint_check_results": [],
        "abstention_required": (
            stage_incomplete
            or not corpus_observation_passed
            or bool(state.missing_obligations)
            or answer_status == "INCOMPLETE_PRECISE"
        ),
        "satisfied_trace_obligation_ids": satisfied,
        "failed_trace_obligation_ids": failed,
        "stage_contracts_passed": not stage_needs_clarification and not stage_incomplete,
        "answer_reference_integrity_passed": not unknown_answer_refs,
        "corpus_observation_required": corpus_observation_required,
        "corpus_observation_passed": corpus_observation_passed,
    }


def _research_answer(case_id: str, intent: IntentFrame, state: ResearchState, verification: JsonMap) -> JsonMap:
    raw = state.answer_draft
    cited_claim_ids = [
        str(item) for item in as_list(raw.get("cited_claim_ids"))
        if str(item) in state.claims_by_id
    ] or list(state.claims_by_id)
    cited_evidence_ids = [
        str(item) for item in as_list(raw.get("cited_evidence_ids"))
        if str(item) in state.evidence_items_by_id
    ] or list(state.evidence_items_by_id)
    status = _normalize_answer_status(raw.get("status") or "COMPLETED")
    if child_map(verification.get("ambiguity_resolution")).get("requires_user_choice"):
        status = "NEEDS_CLARIFICATION"
    elif (
        verification.get("unsupported_claim_count")
        or verification.get("failed_trace_obligation_ids")
        or not verification.get("stage_contracts_passed")
        or not verification.get("answer_reference_integrity_passed")
        or verification.get("abstention_required")
    ):
        status = "INCOMPLETE_PRECISE"
    summary = str(raw.get("summary") or "")
    if not summary:
        if status == "NEEDS_CLARIFICATION":
            summary = str(intent.ambiguity.get("clarification_question") or "Please clarify the intended interpretation.")
        elif status == "INCOMPLETE_PRECISE":
            summary = "The available evidence does not satisfy every required research obligation."
        else:
            summary = " ".join(str(claim.get("text") or "") for claim in state.claims_by_id.values())
    return {
        "answer_id": stable_id("answer", case_id),
        "question_id": case_id,
        "status": status,
        "answer_type": str(raw.get("answer_type") or intent.answer_shape),
        "summary": summary,
        "sections": as_list(raw.get("sections")),
        "markdown": str(raw.get("markdown") or summary),
        "fields": _normalize_answer_fields(raw.get("fields")),
        "cited_claim_ids": cited_claim_ids,
        "cited_evidence_ids": cited_evidence_ids,
        "reasoning_artifact_ids": [stable_id("reasoning", case_id)],
        "verification_id": verification.get("verification_id"),
    }


def _reasoning_artifact(case_id: str, intent: IntentFrame, state: ResearchState, answer: JsonMap) -> JsonMap:
    artifact_type = {
        "exact_fact": "exact_fact_card",
        "comparison_matrix": "comparison_table",
        "reproduction_protocol": "reproduction_checklist",
        "reproduction_checklist": "reproduction_checklist",
        "citation_genealogy": "citation_graph",
        "definition_trace": "definition_evolution_trace",
        "critical_assessment": "critical_assessment_report",
        "multi_hop_chain": "multi_hop_chain",
        "uncertainty_boundary": "uncertainty_boundary_report",
        "ambiguity_clarification": "uncertainty_boundary_report",
        "contradiction_arbitration": "conflict_matrix",
        "constraint_filter": "constraint_filter_table",
        "figure_table_formula_interpretation": "structured_evidence_interpretation",
        "data_provenance": "provenance_record",
        "counterfactual_analysis": "counterfactual_analysis",
        "system_design": "system_architecture",
        "formal_derivation": "formal_derivation",
        "research_profile": "research_trajectory",
        "generalization_audit": "generalization_audit",
        "systematic_review": "systematic_review",
        "boundary_counterexample": "boundary_counterexample_report",
        "evidence_forecast": "evidence_forecast",
    }.get(answer.get("answer_type"), "exact_fact_card")
    return {
        "artifact_id": stable_id("reasoning", case_id),
        "question_id": case_id,
        "type": artifact_type,
        "title": intent.normalized_goal or "Research result",
        "source_claim_ids": list(state.claims_by_id),
        "payload": {
            "answer_fields": answer.get("fields", {}),
            "stage_trace": [trace.to_dict() for trace in state.stage_trace],
            "source_evidence_ids": list(state.evidence_items_by_id),
        },
    }


def _turn_frame(dataset: GoldenDataset, case: JsonMap) -> TurnFrame:
    scope = child_map(case.get("corpus_scope"))
    allowed = [str(item) for item in as_list(scope.get("allowed_paper_ids"))]
    if not allowed:
        allowed = sorted(dataset.paper_records_by_id)
    return TurnFrame(
        turn_id=str(case.get("id") or "turn"),
        question=_case_question(case),
        allowed_paper_ids=allowed,
        conversation_context=child_map(case.get("conversation_context")),
        required_answer_field_names=[str(item) for item in as_list(case.get("required_answer_field_names"))],
    )


def _case_question(case: JsonMap) -> str:
    direct = str(child_map(case.get("question")).get("text") or "")
    if direct:
        return direct
    messages = [child_map(item) for item in as_list(case.get("messages"))]
    return str(messages[-1].get("content") or "") if messages else ""


def _scope_dataset(dataset: GoldenDataset, allowed_paper_ids: list[str]) -> GoldenDataset:
    allowed = set(allowed_paper_ids)
    return replace(
        dataset,
        paper_records_by_id={key: value for key, value in dataset.paper_records_by_id.items() if key in allowed},
        reading_models_by_paper_id={key: value for key, value in dataset.reading_models_by_paper_id.items() if key in allowed},
        citation_edges=[
            edge for edge in dataset.citation_edges
            if edge.get("from_paper_id") in allowed or edge.get("to_paper_id") in allowed
        ],
    )


def _initial_research_state(turn: TurnFrame, dataset: GoldenDataset) -> ResearchState:
    state = ResearchState.new(turn.turn_id)
    state.authorized_paper_ids = unique_strings(
        paper_id
        for paper_id in as_list(turn.conversation_context.get("selected_paper_ids"))
        if str(paper_id) in dataset.paper_records_by_id
    )
    for raw_item in as_list(turn.conversation_context.get("selected_evidence_refs")):
        item = child_map(raw_item)
        evidence_id = str(item.get("evidence_id") or "")
        paper_id = str(item.get("paper_id") or "")
        if not evidence_id or paper_id not in dataset.paper_records_by_id:
            continue
        state.authorized_paper_ids = unique_strings([*state.authorized_paper_ids, paper_id])
        state.evidence_items_by_id[evidence_id] = {
            **item,
            "evidence_id": evidence_id,
            "paper_id": paper_id,
            "location_ref": item.get("location_ref") or item.get("location"),
            "retrieval_strategy": "conversation_memory",
            "evidence_quality": "conversation_memory",
            "supports_claim_ids": [],
            "refutes_claim_ids": [],
        }
    for paper_id in state.authorized_paper_ids:
        state.paper_candidates_by_id[paper_id] = _paper_candidate_observation(dataset, paper_id)
    return state


def _filter_tool_definitions(definitions: list[JsonMap], allowed: set[str]) -> list[JsonMap]:
    return [
        definition for definition in definitions
        if child_map(definition.get("function")).get("name") in allowed
    ]


def _required_stage_tool(
    stage: StageSpec,
    definitions: list[JsonMap],
    tool_trace: list[JsonMap],
    submission_tool: JsonMap,
) -> JsonMap | None:
    if stage.produces_answer:
        return submission_tool
    if not stage.requires_new_evidence:
        return None

    successful_read = any(
        item.get("allowed")
        and item.get("tool_name") == "read_locations"
        and not child_map(item.get("result")).get("error")
        for item in tool_trace
    )
    if successful_read:
        return submission_tool

    if set(stage.allowed_tools) != {"find_reading_locations", "read_locations"}:
        return None

    definitions_by_name = {
        str(child_map(definition.get("function")).get("name")): definition
        for definition in definitions
    }
    location_calls = [
        item for item in tool_trace
        if item.get("allowed") and item.get("tool_name") == "find_reading_locations"
    ]
    if not location_calls:
        return definitions_by_name.get("find_reading_locations")

    location_result = child_map(location_calls[-1].get("result"))
    if location_result.get("error") or not as_list(location_result.get("locations")):
        return submission_tool

    read_attempted = any(
        item.get("allowed") and item.get("tool_name") == "read_locations"
        for item in tool_trace
    )
    if not read_attempted:
        return definitions_by_name.get("read_locations")
    return submission_tool


def _stage_result_submission_tool(stage: StageSpec) -> JsonMap:
    required = ["status", "decision_summary"]
    if stage.produces_answer:
        required.append("answer")
    properties: JsonMap = {
        "status": {
            "type": "string",
            "enum": ["completed", "needs_clarification", "incomplete_precise"],
        },
        "decision_summary": {"type": "string", "maxLength": 300},
        "missing_obligations": {
            "type": "array",
            "maxItems": 8,
            "items": {"type": "object"},
        },
    }
    if stage.produces_answer:
        properties.update({
            "claims": {
                "type": "array",
                "maxItems": 6,
                "items": {
                    "type": "object",
                    "required": ["claim_id", "text", "status", "supporting_evidence_ids"],
                    "properties": {
                        "claim_id": {"type": "string", "maxLength": 80},
                        "text": {"type": "string", "maxLength": 320},
                        "claim_type": {"type": "string", "maxLength": 80},
                        "status": {
                            "type": "string",
                            "enum": [
                                "supported",
                                "partially_supported",
                                "underdetermined",
                                "contradicted",
                            ],
                        },
                        "supporting_evidence_ids": {
                            "type": "array",
                            "maxItems": 4,
                            "items": {"type": "string"},
                        },
                        "refuting_evidence_ids": {
                            "type": "array",
                            "maxItems": 4,
                            "items": {"type": "string"},
                        },
                        "depends_on_claim_ids": {
                            "type": "array",
                            "maxItems": 4,
                            "items": {"type": "string"},
                        },
                        "confidence": {"type": "number"},
                    },
                    "additionalProperties": False,
                },
            },
            "state_values": {"type": "object"},
            "answer": {
                "type": "object",
                "required": ["status", "answer_type", "summary", "cited_claim_ids", "cited_evidence_ids"],
                "properties": {
                    "status": {
                        "type": "string",
                        "enum": ["COMPLETED", "NEEDS_CLARIFICATION", "INCOMPLETE_PRECISE"],
                    },
                    "answer_type": {"type": "string"},
                    "summary": {"type": "string", "maxLength": 300},
                    "markdown": {"type": "string", "maxLength": 2500},
                    "fields": {"type": "object"},
                    "sections": {"type": "array"},
                    "cited_claim_ids": {
                        "type": "array",
                        "maxItems": 6,
                        "items": {"type": "string"},
                    },
                    "cited_evidence_ids": {
                        "type": "array",
                        "maxItems": 12,
                        "items": {"type": "string"},
                    },
                },
                "additionalProperties": False,
            },
            "memory_update": {"type": "object"},
        })
    elif stage.requires_new_evidence:
        properties.update({
            "selected_paper_ids": {
                "type": "array",
                "maxItems": MAX_SELECTED_PAPER_IDS,
                "items": {"type": "string"},
            },
            "accepted_evidence_ids": {
                "type": "array",
                "maxItems": 6,
                "items": {"type": "string"},
            },
            "rejected_evidence_ids": {
                "type": "array",
                "maxItems": 6,
                "items": {"type": "string"},
            },
            "state_values": {
                "type": "object",
                "properties": {"support_found": {"type": "boolean"}},
                "additionalProperties": False,
            },
        })
    else:
        properties["state_values"] = {"type": "object"}
        if stage.allowed_tools:
            properties["selected_paper_ids"] = {
                "type": "array",
                "maxItems": MAX_SELECTED_PAPER_IDS,
                "items": {"type": "string"},
            }
    return {
        "type": "function",
        "function": {
            "name": "submit_stage_result",
            "description": (
                f"Submit the final structured result for semantic stage {stage.name}. "
                "This is an output contract, not a retrieval action."
            ),
            "parameters": {
                "type": "object",
                "required": required,
                "properties": properties,
                "additionalProperties": False,
            },
        },
    }


def _submitted_stage_result(response) -> JsonMap | None:
    for call in response.tool_calls:
        if call.name == "submit_stage_result":
            return call.arguments
    return None


def _stage_submission_error(stage: StageSpec, payload: JsonMap) -> str:
    missing = [
        name for name in ("status", "decision_summary")
        if not str(payload.get(name) or "").strip()
    ]
    if stage.produces_answer and not child_map(payload.get("answer")):
        missing.append("answer")
    if missing:
        return "missing required fields: " + ", ".join(missing)
    return ""


def _is_semantic_state_stage(stage: StageSpec) -> bool:
    return not stage.allowed_tools and not stage.requires_new_evidence and not stage.produces_answer


def _should_repair_stage_status(stage: StageSpec, result: StageResult) -> bool:
    if result.status not in {"needs_clarification", "incomplete_precise"}:
        return False
    if _is_semantic_state_stage(stage):
        return True
    if stage.produces_answer or stage.requires_new_evidence:
        return False
    if _is_identity_stage(stage):
        return bool(result.selected_paper_ids) or result.status == "incomplete_precise"
    if _stage_collects_evidence(stage):
        return bool(result.state_values)
    return bool(result.selected_paper_ids)


def _stage_collects_evidence(stage: StageSpec) -> bool:
    return "read_locations" in stage.allowed_tools


def _explicit_no_match(result: StageResult, tool_trace: list[JsonMap]) -> bool:
    precise_no_match = any(
        item.get("allowed") and _tool_result_proves_no_match(item)
        for item in tool_trace
    )
    return (
        precise_no_match
        and result.state_values.get("support_found") is False
        and bool(result.missing_obligations)
    )


def _tool_result_proves_no_match(call: JsonMap) -> bool:
    result = child_map(call.get("result"))
    name = str(call.get("tool_name") or "")
    if name == "search_paper_candidates":
        return result.get("coverage") == "complete" and int(result.get("matched_count") or 0) == 0
    if name == "find_papers_by_identity":
        return result.get("status") == "not_found"
    if name == "find_reading_locations":
        return not result.get("error") and int(result.get("matched_count") or 0) == 0
    if name == "read_locations":
        return not result.get("error") and not as_list(result.get("items"))
    return False


def _is_identity_stage(stage: StageSpec) -> bool:
    tools = set(stage.allowed_tools)
    return (
        stage.strategy == "bounded_candidate_grounding"
        or (bool(tools) and tools <= {"search_paper_candidates", "find_papers_by_identity"})
    )


def _selection_is_conversation_authorized(result: StageResult, state: ResearchState) -> bool:
    authorized = set(state.authorized_paper_ids)
    selected = set(result.selected_paper_ids)
    return bool(authorized) and (not selected or selected <= authorized)


def _has_candidate_observation(stage_trace) -> bool:
    return any(
        call.get("allowed")
        and call.get("tool_name") in {"search_paper_candidates", "find_papers_by_identity"}
        for trace in stage_trace
        for call in trace.tool_calls
    )


def _answer_contract_error(
    result: StageResult,
    state: ResearchState,
    observed_evidence_ids: set[str],
) -> str:
    known_evidence_ids = set(state.evidence_items_by_id) | observed_evidence_ids
    for claim in result.claims:
        status = str(claim.get("status") or "").lower()
        if status not in {"supported", "support", "verified", "true"}:
            continue
        evidence_ids = {str(item) for item in as_list(claim.get("supporting_evidence_ids"))}
        if not evidence_ids or not evidence_ids <= known_evidence_ids:
            return "a supported claim lacks known supporting evidence"

    known_claim_ids = set(state.claims_by_id) | {
        str(claim.get("claim_id"))
        for claim in result.claims
        if str(claim.get("claim_id") or "")
    }
    cited_claim_ids = {str(item) for item in as_list(result.answer.get("cited_claim_ids"))}
    cited_evidence_ids = {str(item) for item in as_list(result.answer.get("cited_evidence_ids"))}
    unknown_claim_ids = sorted(cited_claim_ids - known_claim_ids)
    unknown_evidence_ids = sorted(cited_evidence_ids - known_evidence_ids)
    if unknown_claim_ids:
        return "answer cites undefined claim ids: " + ", ".join(unknown_claim_ids[:6])
    if unknown_evidence_ids:
        return "answer cites unknown evidence ids: " + ", ".join(unknown_evidence_ids[:6])
    return ""


def _has_terminal_runtime_missing(missing_obligations: list[JsonMap]) -> bool:
    terminal_reasons = {
        "out_of_stage_tool_call",
        "required_stage_evidence_not_accepted",
        "unparseable_stage_result",
        "invalid_stage_submission",
        "answer_draft_missing",
    }
    return any(str(item.get("reason")) in terminal_reasons for item in missing_obligations)


def _is_terminal_stage_result(stage: StageSpec, result: StageResult, intent: IntentFrame) -> bool:
    if result.status == "incomplete_precise":
        return stage.requires_new_evidence or stage.produces_answer
    if result.status == "needs_clarification":
        return stage.produces_answer and intent.actionability == "blocking"
    return False


def _stage_is_terminal_for_intent(stage_name: str, intent: IntentFrame) -> bool:
    for stage in get_paradigm(intent.primary_paradigm).stages:
        if stage.name == stage_name:
            return stage.requires_new_evidence or stage.produces_answer
    return True


def _stage_execution_contract(stage: StageSpec) -> str:
    if stage.produces_answer:
        return "Produce evidence-linked claims and the final answer draft for this paradigm."
    if stage.requires_new_evidence:
        return (
            "Find candidate locations, read the selected locations, and accept only evidence ids "
            "returned by read_locations that satisfy this stage's semantic goal. Candidate cards, "
            "navigation previews, and citation metadata are insufficient."
        )
    if _is_identity_stage(stage):
        return (
            "Resolve identity only. Complete with the unambiguous target paper ids in "
            "selected_paper_ids. Paper-content retrieval belongs to a later stage and is not a "
            "missing obligation here."
        )
    if _is_semantic_state_stage(stage):
        return "Complete this semantic transformation in state_values without performing later stages."
    return "Complete only this stage's semantic goal and record its result in state_values."


def _stage_repair_guidance(stage: StageSpec) -> str:
    if _is_identity_stage(stage):
        return (
            "Use the available paper cards or identity tools and return selected_paper_ids; do not "
            "evaluate paper-content evidence in this stage."
        )
    return "Record this stage's completed semantic result in state_values."


def _stage_output_contract(stage: StageSpec) -> str:
    statuses = "completed|needs_clarification|incomplete_precise"
    if stage.produces_answer:
        return (
            "{status:" + statuses + ", decision_summary:string, "
            "claims:[{claim_id,text,claim_type?,status,supporting_evidence_ids,"
            "refuting_evidence_ids?,depends_on_claim_ids?}], state_values:{}, "
            "missing_obligations:[{}], answer:{status:COMPLETED|NEEDS_CLARIFICATION|INCOMPLETE_PRECISE,"
            "answer_type,summary,markdown?,fields?:{},"
            "sections?:[],cited_claim_ids:[string],cited_evidence_ids:[string]}, memory_update:{}}. "
            "Answer fields must use direct scalar/list/object values; do not wrap a value in "
            "{type,value,notation}."
        )
    if _stage_collects_evidence(stage):
        return (
            "{status:" + statuses + ", decision_summary:string, selected_paper_ids:[string], "
            "accepted_evidence_ids:[string], rejected_evidence_ids:[string], state_values:{}, "
            "missing_obligations:[{}]}"
        )
    if stage.allowed_tools:
        return (
            "{status:" + statuses + ", decision_summary:string, selected_paper_ids:[string], "
            "state_values:{}, missing_obligations:[{}]}"
        )
    return (
        "{status:" + statuses + ", decision_summary:string, state_values:{}, "
        "missing_obligations:[{}]}"
    )


def _paper_candidate_observation(dataset: GoldenDataset, paper_id: str) -> JsonMap:
    record = dataset.paper_records_by_id[paper_id]
    identity = child_map(record.get("identity"))
    model = child_map(dataset.reading_models_by_paper_id.get(paper_id))
    return {
        "evidence_id": stable_id("paper_candidate", paper_id),
        "paper_id": paper_id,
        "title": identity.get("title") or paper_id,
        "paper_version": identity.get("version_label") or model.get("model_version") or identity.get("year") or "unknown",
        "authors": as_list(identity.get("authors")),
        "year": identity.get("year"),
        "venue": identity.get("venue"),
        "section": "paper_metadata",
        "page": "metadata",
        "location": "paper_candidate",
        "element_type": "paper_candidate",
        "span_text": str(record.get("abstract") or identity.get("title") or paper_id)[:500],
        "retrieval_strategy": "paper_identity_resolution",
        "relevance_score": 1.0,
        "evidence_quality": "metadata_verified",
        "citeable": False,
        "supports_claim_ids": [],
        "refutes_claim_ids": [],
    }


def _tool_strategy(tool_name: str) -> str:
    return {
        "search_paper_candidates": "paper_candidate_search",
        "find_papers_by_identity": "paper_identity_resolution",
        "find_reading_locations": "reading_location_search",
        "read_locations": "source_quote_reading",
        "get_citation_edges": "citation_graph_traversal",
    }.get(tool_name, "semantic_stage_execution")


def _normalize_answer_status(value: object) -> str:
    raw = str(value or "").lower()
    return {
        "answered": "COMPLETED",
        "answerable": "COMPLETED",
        "completed": "COMPLETED",
        "completed_precise": "COMPLETED",
        "draft_complete": "COMPLETED",
        "ok": "COMPLETED",
        "ok_precise": "COMPLETED",
        "resolved": "COMPLETED",
        "contradiction_report": "COMPLETED",
        "needs_clarification": "NEEDS_CLARIFICATION",
        "clarify": "NEEDS_CLARIFICATION",
        "incomplete": "INCOMPLETE_PRECISE",
        "incomplete_precise": "INCOMPLETE_PRECISE",
        "abstained": "INCOMPLETE_PRECISE",
    }.get(raw, str(value or "INCOMPLETE_PRECISE").upper())


def _normalize_answer_fields(value: object) -> JsonMap:
    fields = child_map(value)
    normalized: JsonMap = {}
    for key, field_value in fields.items():
        field_map = child_map(field_value)
        normalized[key] = field_map.get("value") if "value" in field_map else field_value
    return normalized


def _technical_failure_run(
    case: JsonMap,
    started_at: str,
    reason: str,
    harness_id: str,
    model_diagnostics: JsonMap | None = None,
    duration_ms: int = 0,
) -> JsonMap:
    case_id = str(case.get("id") or "turn")
    answer = {
        "answer_id": stable_id("answer", case_id),
        "question_id": case_id,
        "status": "FAILED_TECHNICAL",
        "answer_type": "unknown",
        "summary": reason,
        "sections": [],
        "markdown": reason,
        "fields": {},
        "cited_claim_ids": [],
        "cited_evidence_ids": [],
        "reasoning_artifact_ids": [],
        "verification_id": stable_id("verification", case_id),
    }
    return {
        "schema_version": RUN_TRACE_SCHEMA_VERSION,
        "run_id": stable_id("run", case_id),
        "question_id": case_id,
        "case_id": case_id,
        "harness_id": harness_id,
        "started_at": started_at,
        "completed_at": _now(),
        "status": "FAILED_TECHNICAL",
        "result_status": "FAILED_TECHNICAL",
        "memory_update": {},
        "intent_frame": {
            "question_id": case_id,
            "raw_question": _case_question(case),
            "normalized_question": _case_question(case),
            "entities": [], "paper_mentions": [], "method_mentions": [], "dataset_mentions": [],
            "constraints": {}, "answer_type": "unknown", "ambiguity_status": "unknown",
            "required_evidence_types": [], "required_capabilities": [],
        },
        "retrieval_plan": {
            "plan_id": stable_id("plan", case_id), "question_id": case_id, "target_entities": [],
            "strategy_steps": [], "expected_evidence_types": [], "required_recall_targets": [],
            "hard_negative_policy": [], "stop_conditions": ["technical_failure"],
        },
        "stage_trace": [],
        "evidence_ledger": {"ledger_id": stable_id("ledger", case_id), "question_id": case_id, "items": [], "rejected_items": [], "missing_evidence": []},
        "claim_graph": {"graph_id": stable_id("claim_graph", case_id), "question_id": case_id, "claims": [], "edges": []},
        "reasoning_artifacts": [],
        "verification_pass": {
            "verification_id": stable_id("verification", case_id), "question_id": case_id,
            "required_capabilities_attempted": [], "required_evidence_status": [],
            "unsupported_claim_count": 0, "contradicted_claim_count": 0,
            "missing_required_evidence": [], "ambiguity_resolution": {"status": "technical_failure"},
            "constraint_check_results": [], "abstention_required": False,
            "satisfied_trace_obligation_ids": [], "failed_trace_obligation_ids": [],
        },
        "research_answer": answer,
        "final_answer": answer,
        "diagnostics": {
            "finish_reason": "intent_recognition_failed",
            "error": reason,
            **(model_diagnostics or {}),
            "duration_ms": duration_ms,
        },
    }


def _now() -> str:
    return datetime.now(UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z")
