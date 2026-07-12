from __future__ import annotations

import json
from dataclasses import dataclass, replace
from datetime import UTC, datetime
from time import perf_counter

from ..llm import ChatModel
from ..models import RUN_TRACE_SCHEMA_VERSION, GoldenDataset, JsonMap, as_list, child_map, stable_id
from ..tools import SEARCH_ELEMENT_TYPES, ReadingCorpusTools
from .intent import IntentRecognitionError, TurnInterpreter
from .models import (
    Claim,
    ClaimVerdict,
    EvidenceCoverage,
    ExecutionStatus,
    ResearchState,
    StageTrace,
    TaskFrame,
    TurnFrame,
    unique_strings,
)
from .plans import ParadigmRecipe, get_paradigm


MAX_SELECTED_PAPER_IDS = 6
MAX_CANDIDATE_ITEMS = 20
MAX_RETRIEVAL_TOOL_CALLS = 14
MAX_LOCATION_SEARCHES = 10


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


@dataclass(frozen=True)
class RetrievalResult:
    coverage: list[EvidenceCoverage]
    selected_paper_ids: list[str]
    tool_trace: list[JsonMap]


class EvidenceCollector:
    def __init__(
        self,
        model: ChatModel,
        tools: ReadingCorpusTools,
        max_completion_tokens: int,
    ):
        self.model = model
        self.tools = tools
        self.max_completion_tokens = max_completion_tokens

    def collect(
        self,
        turn: TurnFrame,
        task: TaskFrame,
        recipe: ParadigmRecipe,
        state: ResearchState,
        dataset: GoldenDataset,
    ) -> RetrievalResult:
        self.tools.authorized_paper_ids.update(
            paper_id for paper_id in state.authorized_paper_ids
            if paper_id in dataset.paper_records_by_id
        )
        self.tools.disclosed_location_refs.update(
            str(item.get("location_ref") or item.get("location"))
            for item in state.evidence_items_by_id.values()
            if str(item.get("location_ref") or item.get("location")) in self.tools.documents_by_location
        )
        tool_trace: list[JsonMap] = []
        plan = self._plan(turn, task, recipe, state, dataset, retry=False)
        state.retrieval_rounds.append(_compact_retrieval_round("initial", plan))
        target_map, target_observations = self._resolve_targets(
            plan,
            task,
            state,
            dataset,
            tool_trace,
        )
        evidence_by_obligation: dict[str, list[str]] = {
            item.obligation_id: [] for item in task.obligations
        }
        notes_by_obligation: dict[str, str] = {}
        self._apply_reuse(plan, state, evidence_by_obligation, notes_by_obligation)
        for obligation_id, observation_ids in target_observations.items():
            evidence_by_obligation.setdefault(obligation_id, []).extend(observation_ids)

        self._execute_evidence_queries(
            plan,
            target_map,
            state,
            dataset,
            tool_trace,
            evidence_by_obligation,
            notes_by_obligation,
            round_id="initial",
            relax_constraints=False,
        )
        required_uncovered = [
            item for item in task.obligations
            if item.required and not evidence_by_obligation.get(item.obligation_id)
        ]
        if required_uncovered and len(tool_trace) < MAX_RETRIEVAL_TOOL_CALLS - 1:
            retry_plan = self._plan(
                turn,
                replace(task, obligations=required_uncovered),
                recipe,
                state,
                dataset,
                retry=True,
                resolved_targets=target_map,
                previous_plan=plan,
            )
            state.retrieval_rounds.append(_compact_retrieval_round("retry", retry_plan))
            retry_targets, retry_observations = self._resolve_targets(
                retry_plan,
                task,
                state,
                dataset,
                tool_trace,
                existing_targets=target_map,
            )
            target_map.update(retry_targets)
            for obligation_id, observation_ids in retry_observations.items():
                evidence_by_obligation.setdefault(obligation_id, []).extend(observation_ids)
            self._execute_evidence_queries(
                retry_plan,
                target_map,
                state,
                dataset,
                tool_trace,
                evidence_by_obligation,
                notes_by_obligation,
                round_id="retry",
                relax_constraints=True,
            )

        coverage = [
            EvidenceCoverage(
                obligation_id=item.obligation_id,
                status="covered" if evidence_by_obligation.get(item.obligation_id) else "missing",
                evidence_ids=unique_strings(evidence_by_obligation.get(item.obligation_id, [])),
                note=(
                    notes_by_obligation.get(item.obligation_id)
                    or (
                        "covered by inspected evidence"
                        if evidence_by_obligation.get(item.obligation_id)
                        else "evidence not recovered"
                    )
                ),
            )
            for item in task.obligations
        ]
        selected = unique_strings(
            paper_id
            for item in coverage
            for evidence_id in item.evidence_ids
            for paper_id in [str(state.evidence_items_by_id.get(evidence_id, {}).get("paper_id") or "")]
            if paper_id in dataset.paper_records_by_id
        )[:MAX_SELECTED_PAPER_IDS]
        if not selected:
            selected = unique_strings(
                paper_id for paper_ids in target_map.values() for paper_id in paper_ids
                if paper_id in dataset.paper_records_by_id
            )[:MAX_SELECTED_PAPER_IDS]
        state.selected_paper_ids = selected
        state.authorized_paper_ids = unique_strings([
            *state.authorized_paper_ids,
            *selected,
        ])
        state.coverage_by_obligation_id = {
            item.obligation_id: item for item in coverage
        }
        state.missing_obligations = [
            {
                "obligation_id": item.obligation_id,
                "reason": item.note or "required evidence was not recovered",
            }
            for item in coverage
            if item.status == "missing"
        ]
        state.stage_trace.append(StageTrace(
            stage_name="collect_evidence",
            semantic_goal=recipe.semantic_steps[1] if len(recipe.semantic_steps) > 1 else "collect_evidence",
            status="completed",
            decision_summary=(
                f"Executed {len(state.retrieval_rounds)} bounded retrieval round(s) with "
                f"{len(tool_trace)} corpus tool call(s)."
            ),
            tool_calls=tool_trace,
            obligation_ids=[item.obligation_id for item in coverage],
            evidence_ids=unique_strings(
                evidence_id for item in coverage for evidence_id in item.evidence_ids
            ),
        ))
        return RetrievalResult(
            coverage=coverage,
            selected_paper_ids=selected,
            tool_trace=tool_trace,
        )

    def _plan(
        self,
        turn: TurnFrame,
        task: TaskFrame,
        recipe: ParadigmRecipe,
        state: ResearchState,
        dataset: GoldenDataset,
        *,
        retry: bool,
        resolved_targets: dict[str, list[str]] | None = None,
        previous_plan: JsonMap | None = None,
    ) -> JsonMap:
        tool = _retrieval_query_submission_tool(task)
        messages = [
            {
                "role": "system",
                "content": (
                    "EVIDENCE_QUERY_PLANNING\n"
                    "Plan one compact retrieval round and call submit_retrieval_queries exactly once. Do not answer "
                    "the user. The runtime will execute the plan against the fixed corpus.\n\n"
                    "Create the smallest set of paper targets and evidence queries that covers the supplied obligations. "
                    "For a paper present in the supplied corpus inventory, copy its exact paper_id into the target and "
                    "leave discovery_query empty. Use title or arxiv_id only when paper_id is unavailable. Use "
                    "discovery_query for topical candidate discovery, and browse_all only for corpus-wide discovery, "
                    "recommendation, or a corpus-boundary check. Each evidence query must name the obligations it serves "
                    "and one or more target ids. Make query_text either one short source-like phrase or 3-8 distinctive "
                    "content terms. Do not include the paper title, task verbs, broad context terms, synonym lists, or a "
                    "long restatement of the obligation. For an architecture-role cell, query the architecture noun "
                    "phrase itself rather than surrounding pre-training or application language. If one obligation needs "
                    "two independently stated source facts, emit two short queries for that same obligation. Combine "
                    "obligations only when one passage is likely to cover them.\n\n"
                    "On a retry round, only reformulate queries for the supplied still-uncovered obligations. Remove "
                    "overly narrow section or element constraints and do not repeat the same failed query. Candidate "
                    "cards and citation edges are navigation; paper-content facts still require read passages.\n\n"
                    f"PARADIGM: {recipe.paradigm_id}\n"
                    f"RETRIEVAL GUIDANCE: {recipe.retrieval_guidance}"
                ),
            },
            {
                "role": "user",
                "content": json.dumps({
                    "question": turn.question,
                    "task": task.to_dict(),
                    "conversation_context": turn.conversation_context,
                    "corpus": {
                        "paper_count": len(dataset.paper_records_by_id),
                        "reading_model_count": len(dataset.reading_models_by_paper_id),
                        "papers": [
                            {
                                "paper_id": paper_id,
                                "title": child_map(record.get("identity")).get("title"),
                            }
                            for paper_id, record in dataset.paper_records_by_id.items()
                        ],
                    },
                    "existing_state": state.to_prompt_dict(),
                    "retry": retry,
                    "resolved_targets": resolved_targets or {},
                    "previous_plan": previous_plan or {},
                }, ensure_ascii=False),
            },
        ]
        response = self.model.complete_required_tool(
            messages,
            [tool],
            "submit_retrieval_queries",
            self.max_completion_tokens,
        )
        calls = [call for call in response.tool_calls if call.name == "submit_retrieval_queries"]
        if len(calls) != 1:
            raise ValueError("evidence collector did not submit one retrieval query plan")
        return calls[0].arguments

    def _resolve_targets(
        self,
        plan: JsonMap,
        task: TaskFrame,
        state: ResearchState,
        dataset: GoldenDataset,
        tool_trace: list[JsonMap],
        existing_targets: dict[str, list[str]] | None = None,
    ) -> tuple[dict[str, list[str]], dict[str, list[str]]]:
        target_map = dict(existing_targets or {})
        observations_by_obligation: dict[str, list[str]] = {}
        obligations_by_id = {
            item.obligation_id: item for item in task.obligations
        }
        boundary_obligation_ids = [
            item.obligation_id for item in task.obligations
            if item.kind == "corpus_boundary"
        ]
        for index, raw in enumerate(as_list(plan.get("paper_targets"))):
            if len(tool_trace) >= MAX_RETRIEVAL_TOOL_CALLS - 2:
                break
            target = child_map(raw)
            target_id = str(target.get("target_id") or f"target_{index + 1}")
            if target_id in target_map and target_map[target_id]:
                continue
            identity_args = _target_identity_arguments(target, dataset)
            paper_ids: list[str] = []
            observation_ids: list[str] = []
            if identity_args:
                payload, observation_id = self._call_tool(
                    state,
                    dataset,
                    tool_trace,
                    f"identity_{index + 1}",
                    "find_papers_by_identity",
                    identity_args,
                )
                if payload.get("status") == "resolved":
                    paper_ids = [
                        str(item.get("paper_id")) for item in as_list(payload.get("matches"))
                        if str(item.get("paper_id")) in dataset.paper_records_by_id
                    ]
                if observation_id:
                    observation_ids.append(observation_id)
            query = str(target.get("discovery_query") or "")
            if (not paper_ids and query) or bool(target.get("browse_all")):
                payload, observation_id = self._call_tool(
                    state,
                    dataset,
                    tool_trace,
                    f"candidate_{index + 1}",
                    "search_paper_candidates",
                    {
                        "query_text": "" if target.get("browse_all") else query,
                        "limit": min(8, MAX_CANDIDATE_ITEMS),
                    },
                )
                paper_ids = [
                    str(item.get("paper_id")) for item in as_list(payload.get("candidates"))
                    if str(item.get("paper_id")) in dataset.paper_records_by_id
                ]
                paper_ids = paper_ids[
                    :MAX_SELECTED_PAPER_IDS if target.get("browse_all") or not identity_args else 1
                ]
                if observation_id:
                    observation_ids.append(observation_id)
            target_map[target_id] = unique_strings(paper_ids)
            coverage_obligation_ids = [
                obligation_id
                for obligation_id in unique_strings(as_list(target.get("coverage_obligation_ids")))
                if obligations_by_id.get(obligation_id)
                and obligations_by_id[obligation_id].kind == "corpus_boundary"
            ]
            if (
                observation_ids
                and task.primary_paradigm == "uncertainty_knowledge_boundary"
            ):
                coverage_obligation_ids = unique_strings([
                    *coverage_obligation_ids,
                    *boundary_obligation_ids,
                ])
            for obligation_id in coverage_obligation_ids:
                observations_by_obligation.setdefault(str(obligation_id), []).extend(observation_ids)
        return target_map, observations_by_obligation

    def _execute_evidence_queries(
        self,
        plan: JsonMap,
        target_map: dict[str, list[str]],
        state: ResearchState,
        dataset: GoldenDataset,
        tool_trace: list[JsonMap],
        evidence_by_obligation: dict[str, list[str]],
        notes_by_obligation: dict[str, str],
        *,
        round_id: str,
        relax_constraints: bool,
    ) -> set[str]:
        obligations_by_ref: dict[str, list[str]] = {}
        ref_groups: list[list[str]] = []
        empty_obligations: set[str] = set()
        planned_obligations: set[str] = set()
        for index, raw in enumerate(as_list(plan.get("evidence_queries"))):
            if (
                len(tool_trace) >= MAX_RETRIEVAL_TOOL_CALLS - 1
                or sum(
                    1 for call in tool_trace
                    if call.get("tool_name") == "find_reading_locations"
                ) >= MAX_LOCATION_SEARCHES
            ):
                break
            query = child_map(raw)
            obligation_ids = unique_strings(as_list(query.get("obligation_ids")))
            planned_obligations.update(obligation_ids)
            paper_ids = unique_strings(
                paper_id
                for target_id in as_list(query.get("target_ids"))
                for paper_id in target_map.get(str(target_id), [])
            )
            if not obligation_ids or not paper_ids:
                empty_obligations.update(obligation_ids)
                continue
            arguments: JsonMap = {
                "query_text": str(query.get("query_text") or ""),
                "paper_ids": paper_ids,
                "top_k": min(max(int(query.get("top_k") or 3), 1), 5),
            }
            section_query = str(query.get("section_query") or "").strip()
            element_types = [
                item for item in unique_strings(as_list(query.get("element_types")))
                if item in SEARCH_ELEMENT_TYPES
            ]
            if section_query and not relax_constraints:
                arguments["section_query"] = section_query
            if element_types and not relax_constraints:
                arguments["element_types"] = element_types
            payload, _ = self._call_tool(
                state,
                dataset,
                tool_trace,
                f"location_{round_id}_{index + 1}",
                "find_reading_locations",
                arguments,
            )
            locations = as_list(payload.get("locations"))
            if not locations:
                empty_obligations.update(obligation_ids)
                continue
            query_refs: list[str] = []
            for location in locations:
                ref = str(child_map(location).get("location_ref") or "")
                if not ref:
                    continue
                obligations_by_ref.setdefault(ref, []).extend(obligation_ids)
                query_refs.append(ref)
            if query_refs:
                ref_groups.append(unique_strings(query_refs))

        refs = _round_robin_refs(ref_groups, 20)
        if refs and len(tool_trace) < MAX_RETRIEVAL_TOOL_CALLS:
            payload, _ = self._call_tool(
                state,
                dataset,
                tool_trace,
                f"read_{round_id}",
                "read_locations",
                {"location_refs": refs[:20]},
            )
            items_by_ref = {
                str(child_map(item).get("location_ref") or child_map(item).get("location") or ""): child_map(item)
                for item in as_list(payload.get("items"))
            }
            for ref, obligation_ids in obligations_by_ref.items():
                item = items_by_ref.get(ref)
                if not item:
                    empty_obligations.update(obligation_ids)
                    continue
                evidence_id = str(item.get("evidence_id") or "")
                if not evidence_id:
                    continue
                for obligation_id in obligation_ids:
                    evidence_by_obligation.setdefault(obligation_id, []).append(evidence_id)
                    notes_by_obligation[obligation_id] = "covered by a passage retrieved for this obligation"
                    empty_obligations.discard(obligation_id)
        for obligation_id in planned_obligations:
            if not evidence_by_obligation.get(obligation_id):
                empty_obligations.add(obligation_id)
        return empty_obligations

    def _apply_reuse(
        self,
        plan: JsonMap,
        state: ResearchState,
        evidence_by_obligation: dict[str, list[str]],
        notes_by_obligation: dict[str, str],
    ) -> None:
        for raw in as_list(plan.get("reuse_evidence")):
            item = child_map(raw)
            obligation_id = str(item.get("obligation_id") or "")
            evidence_ids = _resolve_known_ids(
                unique_strings(as_list(item.get("evidence_ids"))),
                set(state.evidence_items_by_id),
            )
            if obligation_id and evidence_ids:
                evidence_by_obligation.setdefault(obligation_id, []).extend(evidence_ids)
                notes_by_obligation[obligation_id] = "covered by conversation evidence"

    def _call_tool(
        self,
        state: ResearchState,
        dataset: GoldenDataset,
        tool_trace: list[JsonMap],
        call_id: str,
        tool_name: str,
        arguments: JsonMap,
    ) -> tuple[JsonMap, str | None]:
        if len(tool_trace) >= MAX_RETRIEVAL_TOOL_CALLS:
            return {"error": "retrieval_tool_budget_exhausted"}, None
        payload = self.tools.call(tool_name, arguments).payload
        model_payload = _capture_tool_result(
            state,
            call_id,
            tool_name,
            arguments,
            payload,
            dataset,
            self.tools,
        )
        tool_trace.append({
            "tool_call_id": call_id,
            "tool_name": tool_name,
            "arguments": arguments,
            "allowed": "error" not in payload,
            "result": payload,
        })
        return payload, str(model_payload.get("corpus_observation_id") or "") or None


class ClaimConstructor:
    def __init__(self, model: ChatModel, max_tokens: int):
        self.model = model
        self.max_tokens = max_tokens

    def construct(
        self,
        turn: TurnFrame,
        task: TaskFrame,
        recipe: ParadigmRecipe,
        state: ResearchState,
    ) -> list[Claim]:
        covered_required = [
            item.obligation_id for item in task.obligations
            if item.required
            and state.coverage_by_obligation_id.get(item.obligation_id)
            and state.coverage_by_obligation_id[item.obligation_id].status == "covered"
        ]
        tool = _claim_submission_tool(recipe, len(covered_required))
        response = self.model.complete_required_tool(
            self._messages(turn, task, recipe, state),
            [tool],
            "submit_claims",
            self.max_tokens,
        )
        calls = [call for call in response.tool_calls if call.name == "submit_claims"]
        if len(calls) != 1:
            raise ValueError(
                "claim constructor did not submit one claim set; "
                f"tool_calls={[call.name for call in response.tool_calls]}"
            )
        covered_obligations = {
            item.obligation_id for item in state.coverage_by_obligation_id.values()
            if item.status == "covered"
        }
        obligations_by_id = {
            item.obligation_id: item for item in task.obligations
        }
        allowed_fields = {
            item.answer_field for item in task.obligations if item.answer_field
        }
        known_evidence = set(state.evidence_items_by_id)
        claims: list[Claim] = []
        seen: set[str] = set()
        one_claim_per_obligation = recipe.paradigm_id == "deep_comparison"
        claimed_required_obligations: set[str] = set()
        for index, raw in enumerate(as_list(calls[0].arguments.get("claims"))):
            claim = Claim.from_dict(child_map(raw), index)
            obligation_evidence_ids = {
                evidence_id
                for obligation_id in claim.obligation_ids
                for evidence_id in state.coverage_by_obligation_id.get(
                    obligation_id,
                    EvidenceCoverage(obligation_id, "missing"),
                ).evidence_ids
            }
            boundary_only = bool(claim.obligation_ids) and all(
                obligations_by_id.get(obligation_id)
                and obligations_by_id[obligation_id].kind == "corpus_boundary"
                for obligation_id in claim.obligation_ids
            )
            evidence_ids = [
                evidence_id for evidence_id in _resolve_known_ids(
                    claim.evidence_ids,
                    obligation_evidence_ids & known_evidence,
                )
                if evidence_id in known_evidence
                and (
                    boundary_only
                    or state.evidence_items_by_id[evidence_id].get("citeable") is not False
                )
            ]
            if (
                not claim.text
                or claim.claim_id in seen
                or not set(claim.obligation_ids) <= covered_obligations
                or not claim.obligation_ids
                or not evidence_ids
                or claim.role not in recipe.claim_roles
                or (
                    recipe.paradigm_id == "uncertainty_knowledge_boundary"
                    and not boundary_only
                )
            ):
                continue
            required_claim_obligations = set(claim.obligation_ids) & set(covered_required)
            if (
                one_claim_per_obligation
                and required_claim_obligations <= claimed_required_obligations
            ):
                continue
            claim = replace(
                claim,
                evidence_ids=evidence_ids,
                field_values={
                    key: value for key, value in claim.field_values.items()
                    if key in allowed_fields
                },
            )
            seen.add(claim.claim_id)
            claims.append(claim)
            claimed_required_obligations.update(required_claim_obligations)
        state.claims_by_id = {item.claim_id: item for item in claims}
        state.stage_trace.append(StageTrace(
            stage_name="construct_claims",
            semantic_goal=recipe.semantic_steps[2] if len(recipe.semantic_steps) > 2 else "construct_claims",
            status="completed",
            decision_summary=f"Constructed {len(claims)} evidence-linked atomic claim(s).",
            obligation_ids=unique_strings(
                obligation_id for claim in claims for obligation_id in claim.obligation_ids
            ),
            evidence_ids=unique_strings(
                evidence_id for claim in claims for evidence_id in claim.evidence_ids
            ),
            claim_ids=[item.claim_id for item in claims],
        ))
        return claims

    def _messages(
        self,
        turn: TurnFrame,
        task: TaskFrame,
        recipe: ParadigmRecipe,
        state: ResearchState,
    ) -> list[JsonMap]:
        return [
            {
                "role": "system",
                "content": (
                    "ATOMIC_CLAIM_CONSTRUCTION\n"
                    "Construct the smallest sufficient set of atomic claims needed to satisfy the user's evidence "
                    "obligations. Call submit_claims exactly once. Do not write final Markdown.\n\n"
                    "Every claim must satisfy at least one supplied obligation and cite only supplied evidence ids. "
                    "Return exactly one atomic claim for every covered required obligation; do not omit a covered slot. "
                    "Preserve limiting words from the source such as default, used, similar, except, encoder, and "
                    "decoder. Do not add optional background, causes, tradeoffs, corpus details, implementation "
                    "components, or recommendations unless an obligation explicitly asks for them. A missing "
                    "obligation remains missing; do not fill it from external knowledge. Claim text must not contain "
                    "internal evidence ids or citation markers. A corpus-wide absence claim must satisfy a "
                    "corpus_boundary obligation and use an allowed boundary claim role; passages about other papers "
                    "cannot be turned into an absence claim for the requested paper. Do not infer 'no decoder', "
                    "'no cross-attention', an unmasked attention implementation, a loss function, or the absence of a "
                    "pre-training stage unless the cited passage states that point directly.\n\n"
                    "For any scalar obligation with a non-empty answer_field, return one field_values entry whose name "
                    "exactly equals answer_field and whose value is the exact scalar notation. For non-scalar claims, "
                    "field_values is empty.\n\n"
                    f"PARADIGM: {recipe.paradigm_id}\n"
                    f"OBLIGATION GUIDANCE: {recipe.obligation_guidance}\n"
                    f"ALLOWED CLAIM ROLES: {', '.join(recipe.claim_roles)}"
                ),
            },
            {
                "role": "user",
                "content": json.dumps({
                    "question": turn.question,
                    "effective_request": task.normalized_goal,
                    "constraints": {
                        key: value for key, value in task.constraints.items()
                        if key not in {"coverage_assumption", "task"}
                    },
                    "obligations": [item.to_dict() for item in task.obligations],
                    "coverage": [item.to_dict() for item in state.coverage_by_obligation_id.values()],
                    "evidence": [_evidence_prompt_item(item) for item in state.evidence_items_by_id.values()],
                }, ensure_ascii=False),
            },
        ]


class ClaimVerifier:
    def __init__(self, model: ChatModel, max_tokens: int):
        self.model = model
        self.max_tokens = max_tokens

    def verify(
        self,
        task: TaskFrame,
        recipe: ParadigmRecipe,
        state: ResearchState,
    ) -> list[ClaimVerdict]:
        claims = list(state.claims_by_id.values())
        if not claims:
            state.stage_trace.append(StageTrace(
                stage_name="verify_claims",
                semantic_goal=recipe.semantic_steps[-1],
                status="completed",
                decision_summary="No candidate claims were available for verification.",
            ))
            return []
        tool = _verdict_submission_tool(claims)
        response = self.model.complete_required_tool(
            self._messages(task, recipe, state, claims),
            [tool],
            "submit_claim_verdicts",
            self.max_tokens,
        )
        calls = [call for call in response.tool_calls if call.name == "submit_claim_verdicts"]
        if len(calls) != 1:
            raise ValueError("claim verifier did not submit one verdict set")
        raw_by_id = {
            str(child_map(raw).get("claim_id") or ""): child_map(raw)
            for raw in as_list(calls[0].arguments.get("verdicts"))
        }
        known_evidence = set(state.evidence_items_by_id)
        verdicts: list[ClaimVerdict] = []
        for claim in claims:
            raw = raw_by_id.get(claim.claim_id)
            if raw is None:
                verdict = ClaimVerdict(
                    claim_id=claim.claim_id,
                    verdict="drop",
                    verified_text="",
                    evidence_ids=[],
                    reason="verifier omitted this claim",
                )
            else:
                parsed = ClaimVerdict.from_dict(raw)
                evidence_ids = _resolve_known_ids(
                    parsed.evidence_ids,
                    known_evidence & set(claim.evidence_ids),
                )
                if parsed.verdict == "keep" and evidence_ids:
                    verdict = replace(
                        parsed,
                        verified_text=claim.text,
                        evidence_ids=evidence_ids,
                        field_values=dict(claim.field_values),
                    )
                elif parsed.verdict == "rewrite" and parsed.verified_text and evidence_ids:
                    verdict = replace(
                        parsed,
                        evidence_ids=evidence_ids,
                        field_values={
                            key: value for key, value in parsed.field_values.items()
                            if key in claim.field_values
                        },
                    )
                else:
                    verdict = replace(
                        parsed,
                        verdict="drop",
                        verified_text="",
                        evidence_ids=[],
                        field_values={},
                    )
            verdicts.append(verdict)
        state.verdicts_by_claim_id = {item.claim_id: item for item in verdicts}
        state.stage_trace.append(StageTrace(
            stage_name="verify_claims",
            semantic_goal=recipe.semantic_steps[-1],
            status="completed",
            decision_summary=(
                f"Verified {len(verdicts)} atomic claim(s): "
                f"{sum(item.verdict == 'keep' for item in verdicts)} kept, "
                f"{sum(item.verdict == 'rewrite' for item in verdicts)} rewritten, "
                f"{sum(item.verdict == 'drop' for item in verdicts)} dropped."
            ),
            evidence_ids=unique_strings(
                evidence_id for item in verdicts for evidence_id in item.evidence_ids
            ),
            claim_ids=[item.claim_id for item in verdicts],
        ))
        return verdicts

    def _messages(
        self,
        task: TaskFrame,
        recipe: ParadigmRecipe,
        state: ResearchState,
        claims: list[Claim],
    ) -> list[JsonMap]:
        evidence_ids = unique_strings(
            evidence_id for claim in claims for evidence_id in claim.evidence_ids
        )
        return [
            {
                "role": "system",
                "content": (
                    "ATOMIC_CLAIM_VERIFICATION\n"
                    "Verify each candidate claim using only its supplied evidence passages. Call "
                    "submit_claim_verdicts exactly once. Do not use external knowledge.\n\n"
                    "Use keep only when the cited passages support the exact wording and every qualifier. Use rewrite "
                    "to narrow the claim to what the passages directly support. Use drop when the core assertion, "
                    "causal explanation, scope, comparison, or qualifier is unsupported. Familiar or plausible facts "
                    "are still unsupported. Evidence for one clause does not support added clauses. Preserve source "
                    "distinctions such as recommended default versus experiment-specific use, similar versus identical, "
                    "and a causal decoder versus an entire architecture.\n\n"
                    "Do not infer an architecture exclusion such as 'no decoder' or 'no cross-attention' merely from an "
                    "encoder description. Do not infer masking behavior from the word bidirectional, a loss function from "
                    "training data or label smoothing, or the absence of a pre-training stage from silence. Rewrite away "
                    "any such clause unless the supplied passage states it directly.\n\n"
                    "A passage about a different paper cannot establish that the requested fact is absent from the "
                    "corpus. Keep a corpus-wide absence claim only when it is tied to a corpus_boundary obligation and "
                    "supported by a complete corpus observation.\n\n"
                    "For keep, copy the original claim meaning without expansion. For rewrite, return only the narrower "
                    "verified text and field values directly supported by the evidence."
                ),
            },
            {
                "role": "user",
                "content": json.dumps({
                    "paradigm": recipe.paradigm_id,
                    "obligation_kinds": {
                        item.obligation_id: item.kind for item in task.obligations
                    },
                    "claims": [item.to_dict() for item in claims],
                    "evidence": [
                        _evidence_prompt_item(state.evidence_items_by_id[evidence_id])
                        for evidence_id in evidence_ids
                        if evidence_id in state.evidence_items_by_id
                    ],
                }, ensure_ascii=False),
            },
        ]


class ParadigmDrivenHarness:
    harness_id = "python_evidence_bounded_harness_v1"

    def __init__(self, model: ChatModel, max_turns: int = 8, max_completion_tokens: int = 3000):
        self.model = model
        self.max_turns = max_turns
        self.max_completion_tokens = max_completion_tokens

    def run_case(self, dataset: GoldenDataset, case: JsonMap) -> JsonMap:
        started_at = _now()
        run_started = perf_counter()
        turn = _turn_frame(dataset, case)
        scoped_dataset = _scope_dataset(dataset, turn.allowed_paper_ids)
        measured_model = _RunMetricsModel(self.model)
        try:
            decision = TurnInterpreter(
                measured_model,
                max_tokens=min(self.max_completion_tokens, 2400),
            ).interpret(turn, scoped_dataset)
            if decision.route != "research":
                return _decision_run(
                    case,
                    decision.route,
                    decision.direct_reply,
                    decision.pending_interaction,
                    started_at,
                    measured_model.diagnostics(),
                    round((perf_counter() - run_started) * 1000),
                    self.harness_id,
                )
            task = decision.to_task_frame()
        except (IntentRecognitionError, ValueError) as error:
            return _technical_failure_run(
                case,
                started_at,
                str(error),
                self.harness_id,
                measured_model.diagnostics(),
                round((perf_counter() - run_started) * 1000),
            )
        return self._run_with_task(
            dataset,
            case,
            turn,
            scoped_dataset,
            task,
            measured_model,
            started_at,
            run_started,
        )

    def run_case_with_intent(
        self,
        dataset: GoldenDataset,
        case: JsonMap,
        intent: TaskFrame,
    ) -> JsonMap:
        started_at = _now()
        run_started = perf_counter()
        turn = _turn_frame(dataset, case)
        scoped_dataset = _scope_dataset(dataset, turn.allowed_paper_ids)
        measured_model = _RunMetricsModel(self.model)
        return self._run_with_task(
            dataset,
            case,
            turn,
            scoped_dataset,
            intent,
            measured_model,
            started_at,
            run_started,
        )

    def _run_with_task(
        self,
        dataset: GoldenDataset,
        case: JsonMap,
        turn: TurnFrame,
        scoped_dataset: GoldenDataset,
        task: TaskFrame,
        measured_model: _RunMetricsModel,
        started_at: str,
        run_started: float,
    ) -> JsonMap:
        try:
            recipe = get_paradigm(task.primary_paradigm)
            tools = ReadingCorpusTools(scoped_dataset)
            state = _initial_research_state(turn, scoped_dataset)
            if task.requires_corpus_observation:
                EvidenceCollector(
                    measured_model,
                    tools,
                    self.max_completion_tokens,
                ).collect(turn, task, recipe, state, scoped_dataset)
            else:
                state.coverage_by_obligation_id = {
                    item.obligation_id: EvidenceCoverage(
                        obligation_id=item.obligation_id,
                        status="missing",
                        note="corpus observation was not requested",
                    )
                    for item in task.obligations
                }
            ClaimConstructor(measured_model, self.max_completion_tokens).construct(
                turn,
                task,
                recipe,
                state,
            )
            ClaimVerifier(measured_model, self.max_completion_tokens).verify(task, recipe, state)
            return _build_run(
                case=case,
                started_at=started_at,
                task=task,
                recipe=recipe,
                state=state,
                harness_id=self.harness_id,
                model_diagnostics=measured_model.diagnostics(),
                duration_ms=round((perf_counter() - run_started) * 1000),
            )
        except Exception as error:
            return _technical_failure_run(
                case,
                started_at,
                str(error),
                self.harness_id,
                measured_model.diagnostics(),
                round((perf_counter() - run_started) * 1000),
            )


def _build_run(
    case: JsonMap,
    started_at: str,
    task: TaskFrame,
    recipe: ParadigmRecipe,
    state: ResearchState,
    harness_id: str,
    model_diagnostics: JsonMap,
    duration_ms: int,
) -> JsonMap:
    case_id = str(case.get("id") or "turn")
    verified_claims = _verified_claims(state)
    verified_obligations = {
        obligation_id
        for claim in verified_claims
        for obligation_id in claim["obligation_ids"]
    }
    missing_required = [
        item for item in task.obligations
        if item.required and item.obligation_id not in verified_obligations
    ]
    boundary_answer = (
        task.primary_paradigm == "uncertainty_knowledge_boundary"
        and any(claim["role"] == "unsupported_boundary" for claim in verified_claims)
    )
    if boundary_answer:
        status = ExecutionStatus.COMPLETED.value
        outcome = "abstained"
    elif not verified_claims:
        status = ExecutionStatus.INCOMPLETE_PRECISE.value
        outcome = "abstained"
    elif missing_required:
        status = ExecutionStatus.INCOMPLETE_PRECISE.value
        outcome = "partial"
    else:
        status = ExecutionStatus.COMPLETED.value
        outcome = "answered"

    answer = _render_answer(
        case_id,
        task,
        recipe,
        state,
        verified_claims,
        missing_required,
        status,
        outcome,
    )
    plan = _retrieval_plan(case, task, recipe, state)
    verification = _verification(case, task, state, verified_claims, missing_required)
    state.stage_trace.append(StageTrace(
        stage_name="render_answer",
        semantic_goal="render_verified_claims",
        status="completed",
        decision_summary="Rendered only verified claims.",
        evidence_ids=list(answer["cited_evidence_ids"]),
        claim_ids=list(answer["cited_claim_ids"]),
    ))
    artifact = _reasoning_artifact(case_id, task, recipe, state, answer)
    memory_update = {
        **state.memory_update,
        "selected_paper_ids": state.selected_paper_ids,
        "selected_evidence_ids": answer["cited_evidence_ids"],
    }
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
        "memory_update": memory_update,
        "intent_frame": _intent_artifact(case_id, case, task),
        "retrieval_plan": plan,
        "stage_trace": [item.to_dict() for item in state.stage_trace],
        "paper_candidates": list(state.paper_candidates_by_id.values()),
        "evidence_ledger": {
            "ledger_id": stable_id("ledger", case_id),
            "question_id": case_id,
            "items": [
                *state.paper_candidates_by_id.values(),
                *state.evidence_items_by_id.values(),
            ],
            "coverage": [item.to_dict() for item in state.coverage_by_obligation_id.values()],
            "rejected_items": [],
            "missing_evidence": [
                {
                    "obligation_id": item.obligation_id,
                    "description": item.description,
                    "reason": "no verified claim satisfied this required obligation",
                }
                for item in missing_required
            ],
        },
        "claim_graph": {
            "graph_id": stable_id("claim_graph", case_id),
            "question_id": case_id,
            "claims": _claim_graph_items(state),
            "edges": [],
        },
        "reasoning_artifacts": [artifact],
        "verification_pass": verification,
        "research_answer": answer,
        "final_answer": answer,
        "diagnostics": {
            "finish_reason": "evidence_bounded_answer_rendered",
            "paradigm": task.primary_paradigm,
            "completed_stage_count": len(state.stage_trace),
            "stage_count": len(state.stage_trace),
            "tool_call_count": sum(len(item.tool_calls) for item in state.stage_trace),
            **model_diagnostics,
            "duration_ms": duration_ms,
        },
    }


def _retrieval_query_submission_tool(task: TaskFrame) -> JsonMap:
    obligation_ids = [item.obligation_id for item in task.obligations] or ["none"]
    return {
        "type": "function",
        "function": {
            "name": "submit_retrieval_queries",
            "description": "Submit a compact paper-target and evidence-query plan for one retrieval round.",
            "parameters": {
                "type": "object",
                "required": ["paper_targets", "evidence_queries", "reuse_evidence"],
                "properties": {
                    "paper_targets": {
                        "type": "array",
                        "maxItems": 8,
                        "items": {
                            "type": "object",
                            "required": [
                                "target_id",
                                "paper_id",
                                "title",
                                "arxiv_id",
                                "discovery_query",
                                "browse_all",
                                "coverage_obligation_ids",
                            ],
                            "properties": {
                                "target_id": {"type": "string", "maxLength": 80},
                                "paper_id": {"type": "string", "maxLength": 100},
                                "title": {"type": "string", "maxLength": 240},
                                "arxiv_id": {"type": "string", "maxLength": 40},
                                "discovery_query": {"type": "string", "maxLength": 160},
                                "browse_all": {"type": "boolean"},
                                "coverage_obligation_ids": {
                                    "type": "array",
                                    "maxItems": 16,
                                    "items": {"type": "string", "enum": obligation_ids},
                                },
                            },
                            "additionalProperties": False,
                        },
                    },
                    "evidence_queries": {
                        "type": "array",
                        "maxItems": 12,
                        "items": {
                            "type": "object",
                            "required": [
                                "query_id",
                                "obligation_ids",
                                "target_ids",
                                "query_text",
                            ],
                            "properties": {
                                "query_id": {"type": "string", "maxLength": 80},
                                "obligation_ids": {
                                    "type": "array",
                                    "minItems": 1,
                                    "maxItems": 16,
                                    "items": {"type": "string", "enum": obligation_ids},
                                },
                                "target_ids": {
                                    "type": "array",
                                    "minItems": 1,
                                    "maxItems": 8,
                                    "items": {"type": "string"},
                                },
                                "query_text": {"type": "string", "maxLength": 140},
                                "section_query": {"type": "string", "maxLength": 100},
                                "element_types": {
                                    "type": "array",
                                    "maxItems": 5,
                                    "items": {
                                        "type": "string",
                                        "enum": list(SEARCH_ELEMENT_TYPES),
                                    },
                                },
                                "top_k": {"type": "integer", "minimum": 1, "maximum": 5},
                            },
                            "additionalProperties": False,
                        },
                    },
                    "reuse_evidence": {
                        "type": "array",
                        "maxItems": 16,
                        "items": {
                            "type": "object",
                            "required": ["obligation_id", "evidence_ids"],
                            "properties": {
                                "obligation_id": {"type": "string", "enum": obligation_ids},
                                "evidence_ids": {
                                    "type": "array",
                                    "maxItems": 8,
                                    "items": {"type": "string"},
                                },
                            },
                            "additionalProperties": False,
                        },
                    },
                },
                "additionalProperties": False,
            },
        },
    }


def _claim_submission_tool(recipe: ParadigmRecipe, required_claim_count: int) -> JsonMap:
    return {
        "type": "function",
        "function": {
            "name": "submit_claims",
            "description": "Submit the minimal atomic claim set for the covered obligations.",
            "parameters": {
                "type": "object",
                "required": ["claims"],
                "properties": {
                    "claims": {
                        "type": "array",
                        "minItems": required_claim_count,
                        "maxItems": 16,
                        "items": {
                            "type": "object",
                            "required": [
                                "claim_id", "role", "text", "obligation_ids", "evidence_ids", "field_values"
                            ],
                            "properties": {
                                "claim_id": {"type": "string", "maxLength": 80},
                                "role": {"type": "string", "enum": list(recipe.claim_roles)},
                                "text": {"type": "string", "maxLength": 420},
                                "obligation_ids": {
                                    "type": "array",
                                    "maxItems": 8,
                                    "items": {"type": "string"},
                                },
                                "evidence_ids": {
                                    "type": "array",
                                    "maxItems": 8,
                                    "items": {"type": "string"},
                                },
                                "field_values": {
                                    "type": "array",
                                    "maxItems": 8,
                                    "items": {
                                        "type": "object",
                                        "required": ["name", "value"],
                                        "properties": {
                                            "name": {"type": "string", "maxLength": 80},
                                            "value": {"type": "string", "maxLength": 200},
                                        },
                                        "additionalProperties": False,
                                    },
                                },
                            },
                            "additionalProperties": False,
                        },
                    },
                },
                "additionalProperties": False,
            },
        },
    }


def _verdict_submission_tool(claims: list[Claim]) -> JsonMap:
    claim_ids = [item.claim_id for item in claims]
    return {
        "type": "function",
        "function": {
            "name": "submit_claim_verdicts",
            "description": "Submit one evidence-support verdict for every candidate claim.",
            "parameters": {
                "type": "object",
                "required": ["verdicts"],
                "properties": {
                    "verdicts": {
                        "type": "array",
                        "minItems": len(claims),
                        "maxItems": len(claims),
                        "items": {
                            "type": "object",
                            "required": [
                                "claim_id", "verdict", "verified_text", "evidence_ids", "field_values", "reason"
                            ],
                            "properties": {
                                "claim_id": {"type": "string", "enum": claim_ids},
                                "verdict": {"type": "string", "enum": ["keep", "rewrite", "drop"]},
                                "verified_text": {"type": "string", "maxLength": 420},
                                "evidence_ids": {
                                    "type": "array",
                                    "maxItems": 8,
                                    "items": {"type": "string"},
                                },
                                "field_values": {
                                    "type": "array",
                                    "maxItems": 8,
                                    "items": {
                                        "type": "object",
                                        "required": ["name", "value"],
                                        "properties": {
                                            "name": {"type": "string", "maxLength": 80},
                                            "value": {"type": "string", "maxLength": 200},
                                        },
                                        "additionalProperties": False,
                                    },
                                },
                                "reason": {"type": "string", "maxLength": 180},
                            },
                            "additionalProperties": False,
                        },
                    },
                },
                "additionalProperties": False,
            },
        },
    }


def _compact_retrieval_round(round_id: str, plan: JsonMap) -> JsonMap:
    return {
        "round": round_id,
        "paper_targets": [
            {
                "target_id": target.get("target_id"),
                "paper_id": target.get("paper_id"),
                "title": target.get("title"),
                "arxiv_id": target.get("arxiv_id"),
                "discovery_query": target.get("discovery_query"),
                "browse_all": bool(target.get("browse_all")),
                "coverage_obligation_ids": unique_strings(
                    as_list(target.get("coverage_obligation_ids"))
                ),
            }
            for target in (child_map(item) for item in as_list(plan.get("paper_targets")))
        ],
        "evidence_queries": [
            {
                "query_id": query.get("query_id"),
                "obligation_ids": unique_strings(as_list(query.get("obligation_ids"))),
                "target_ids": unique_strings(as_list(query.get("target_ids"))),
                "query_text": query.get("query_text"),
                "section_query": query.get("section_query"),
                "element_types": unique_strings(as_list(query.get("element_types"))),
                "top_k": query.get("top_k"),
            }
            for query in (child_map(item) for item in as_list(plan.get("evidence_queries")))
        ],
        "reuse_evidence": [
            {
                "obligation_id": item.get("obligation_id"),
                "evidence_ids": unique_strings(as_list(item.get("evidence_ids"))),
            }
            for item in (child_map(raw) for raw in as_list(plan.get("reuse_evidence")))
        ],
    }


def _target_identity_arguments(target: JsonMap, dataset: GoldenDataset) -> JsonMap:
    paper_id = str(target.get("paper_id") or "").strip()
    if paper_id in dataset.paper_records_by_id:
        return {"paper_id": paper_id}
    for key in ("title", "arxiv_id"):
        value = str(target.get(key) or "").strip()
        if value and value.lower() not in {"unknown", "none", "null", "n/a"}:
            return {key: value}
    return {}


def _resolve_known_ids(values: list[str], known_ids: set[str]) -> list[str]:
    by_lower = {item.lower(): item for item in known_ids}
    return unique_strings(
        by_lower[value.lower()]
        for value in values
        if value.lower() in by_lower
    )


def _round_robin_refs(groups: list[list[str]], limit: int) -> list[str]:
    selected: list[str] = []
    seen: set[str] = set()
    depth = 0
    while len(selected) < limit:
        added = False
        for group in groups:
            if depth >= len(group):
                continue
            ref = group[depth]
            if ref not in seen:
                seen.add(ref)
                selected.append(ref)
                if len(selected) >= limit:
                    break
            added = True
        if not added:
            break
        depth += 1
    return selected


def _capture_tool_result(
    state: ResearchState,
    call_id: str,
    tool_name: str,
    arguments: JsonMap,
    payload: JsonMap,
    dataset: GoldenDataset,
    tools: ReadingCorpusTools,
) -> JsonMap:
    model_payload = dict(payload)
    if tool_name in {"search_paper_candidates", "find_papers_by_identity"}:
        cards = as_list(payload.get("candidates")) or as_list(payload.get("matches"))
        for raw_card in cards[:MAX_CANDIDATE_ITEMS]:
            card = child_map(raw_card)
            paper_id = str(card.get("paper_id") or "")
            if paper_id not in dataset.paper_records_by_id:
                continue
            state.paper_candidates_by_id[paper_id] = _paper_candidate_observation(dataset, paper_id, card)
        observation_id = stable_id("corpus_observation", call_id)
        candidate_titles = [str(child_map(card).get("title") or "") for card in cards[:MAX_CANDIDATE_ITEMS]]
        observation = {
            "evidence_id": observation_id,
            "paper_id": None,
            "title": "Corpus candidate search",
            "paper_version": "current_scope",
            "section": "corpus_index",
            "page": "metadata",
            "location": tool_name,
            "location_ref": tool_name,
            "element_type": "corpus_search",
            "span_text": json.dumps({
                "query": arguments,
                "status": payload.get("status"),
                "matched_count": payload.get("matched_count", len(cards)),
                "coverage": payload.get("coverage"),
                "candidate_titles": candidate_titles,
            }, ensure_ascii=False),
            "source_kind": "corpus_search",
            "retrieval_strategy": tool_name,
            "relevance_score": 1.0,
            "evidence_quality": "corpus_observation",
            "citeable": False,
            "supports_claim_ids": [],
            "refutes_claim_ids": [],
        }
        state.evidence_items_by_id[observation_id] = observation
        model_payload["corpus_observation_id"] = observation_id
    if tool_name == "read_locations":
        for raw_item in as_list(payload.get("items")):
            item = child_map(raw_item)
            evidence_id = str(item.get("evidence_id") or "")
            if evidence_id:
                state.evidence_items_by_id[evidence_id] = dict(item)
    state.authorized_paper_ids = unique_strings([
        *state.authorized_paper_ids,
        *(paper_id for paper_id in tools.authorized_paper_ids if paper_id in dataset.paper_records_by_id),
    ])
    return model_payload


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
            "citeable": True,
            "supports_claim_ids": [],
            "refutes_claim_ids": [],
        }
    for paper_id in state.authorized_paper_ids:
        state.paper_candidates_by_id[paper_id] = _paper_candidate_observation(dataset, paper_id)
    return state


def _verified_claims(state: ResearchState) -> list[JsonMap]:
    result: list[JsonMap] = []
    for claim in state.claims_by_id.values():
        verdict = state.verdicts_by_claim_id.get(claim.claim_id)
        if verdict is None or verdict.verdict == "drop":
            continue
        text = claim.text if verdict.verdict == "keep" else verdict.verified_text
        fields = claim.field_values if verdict.verdict == "keep" else verdict.field_values
        if not text or not verdict.evidence_ids:
            continue
        result.append({
            "claim_id": claim.claim_id,
            "role": claim.role,
            "text": text,
            "obligation_ids": claim.obligation_ids,
            "evidence_ids": verdict.evidence_ids,
            "field_values": fields,
            "verdict": verdict.verdict,
        })
    return result


def _render_answer(
    case_id: str,
    task: TaskFrame,
    recipe: ParadigmRecipe,
    state: ResearchState,
    claims: list[JsonMap],
    missing_required: list,
    status: str,
    outcome: str,
) -> JsonMap:
    citation_numbers: dict[str, int] = {}
    cited_evidence_ids: list[str] = []
    for claim in claims:
        for evidence_id in claim["evidence_ids"]:
            item = state.evidence_items_by_id.get(evidence_id, {})
            if item.get("citeable") is False or evidence_id in citation_numbers:
                continue
            cited_evidence_ids.append(evidence_id)
            citation_numbers[evidence_id] = len(cited_evidence_ids)

    rendered_lines = _render_claim_lines(recipe.answer_layout, claims, citation_numbers)
    if not rendered_lines:
        rendered_lines = ["No requested claim was verified from the available corpus evidence."]
    if missing_required:
        rendered_lines.extend([
            "",
            "Unresolved from the available corpus evidence:",
            *[
                f"- {item.answer_field or item.description}"
                for item in missing_required
            ],
        ])
    source_lines = []
    for evidence_id in cited_evidence_ids:
        item = state.evidence_items_by_id[evidence_id]
        location = ", ".join(
            part for part in [
                str(item.get("section") or "").strip(),
                f"p. {item.get('page')}" if item.get("page") not in {None, "", "unknown"} else "",
            ]
            if part
        )
        source_lines.append(
            f"[{citation_numbers[evidence_id]}] {item.get('title') or item.get('paper_id')}"
            + (f", {location}" if location else "")
        )
    markdown = "\n".join(rendered_lines)
    if source_lines:
        markdown += "\n\nSources\n" + "\n".join(source_lines)
    fields: JsonMap = {}
    for claim in claims:
        fields.update(child_map(claim.get("field_values")))
    summary = str(claims[0]["text"] if claims else rendered_lines[0])[:300]
    return {
        "answer_id": stable_id("answer", case_id),
        "question_id": case_id,
        "status": status,
        "outcome": outcome,
        "answer_type": task.answer_shape or recipe.default_answer_shape,
        "summary": summary,
        "sections": [],
        "markdown": markdown,
        "fields": fields,
        "cited_claim_ids": [str(item["claim_id"]) for item in claims],
        "cited_evidence_ids": cited_evidence_ids,
        "reasoning_artifact_ids": [stable_id("reasoning", case_id)],
        "verification_id": stable_id("verification", case_id),
    }


def _render_claim_lines(
    layout: str,
    claims: list[JsonMap],
    citation_numbers: dict[str, int],
) -> list[str]:
    def line(claim: JsonMap, prefix: str = "- ") -> str:
        markers = "".join(
            f"[{citation_numbers[evidence_id]}]"
            for evidence_id in claim["evidence_ids"]
            if evidence_id in citation_numbers
        )
        suffix = f" {markers}" if markers else ""
        return f"{prefix}{claim['text']}{suffix}"

    if layout == "checklist":
        return [line(item, "- [x] ") for item in claims]
    if layout in {"chain", "genealogy", "recommendation", "timeline", "derivation"}:
        return [line(item, f"{index}. ") for index, item in enumerate(claims, start=1)]
    if layout == "comparison":
        rows = ["| Dimension | Evidence-backed comparison |", "|---|---|"]
        claims_by_role: dict[str, list[JsonMap]] = {}
        for item in claims:
            claims_by_role.setdefault(str(item["role"]), []).append(item)
        for role, role_claims in claims_by_role.items():
            cells: list[str] = []
            for item in role_claims:
                markers = "".join(
                    f"[{citation_numbers[evidence_id]}]"
                    for evidence_id in item["evidence_ids"]
                    if evidence_id in citation_numbers
                )
                text = str(item["text"]).replace("|", "\\|")
                cells.append(f"{text} {markers}".strip())
            rows.append(
                f"| {role.replace('_', ' ').title()} | {'<br>'.join(cells)} |"
            )
        return rows
    if layout == "contradiction":
        labels = {"source_a": "Source A", "source_b": "Source B", "resolution": "Resolution"}
        return [line(item, f"- **{labels.get(item['role'], item['role'].title())}:** ") for item in claims]
    if layout == "boundary":
        labels = {"supported_context": "Supported context", "unsupported_boundary": "Corpus boundary"}
        return [line(item, f"- **{labels.get(item['role'], item['role'].title())}:** ") for item in claims]
    if layout == "exact_fact":
        rows: list[str] = []
        for item in claims:
            markers = "".join(
                f"[{citation_numbers[evidence_id]}]"
                for evidence_id in item["evidence_ids"]
                if evidence_id in citation_numbers
            )
            suffix = f" {markers}" if markers else ""
            field_values = child_map(item.get("field_values"))
            if field_values:
                rows.extend(
                    f"- **{name}:** {value}{suffix}"
                    for name, value in field_values.items()
                )
            else:
                rows.append(line(item))
        return rows
    return [line(item) for item in claims]


def _claim_graph_items(state: ResearchState) -> list[JsonMap]:
    items: list[JsonMap] = []
    for claim in state.claims_by_id.values():
        verdict = state.verdicts_by_claim_id.get(claim.claim_id)
        supported = verdict is not None and verdict.verdict in {"keep", "rewrite"}
        corpus_supported = supported and verdict is not None and all(
            state.evidence_items_by_id.get(evidence_id, {}).get("citeable") is False
            for evidence_id in verdict.evidence_ids
        )
        text = (
            claim.text
            if verdict is None or verdict.verdict != "rewrite"
            else verdict.verified_text
        )
        items.append({
            "claim_id": claim.claim_id,
            "text": text,
            "claim_type": claim.role,
            "status": (
                "corpus_supported"
                if corpus_supported else
                "supported" if supported else "underdetermined"
            ),
            "supporting_evidence_ids": verdict.evidence_ids if supported and verdict else [],
            "refuting_evidence_ids": [],
            "obligation_ids": claim.obligation_ids,
            "verification_verdict": verdict.verdict if verdict else "not_run",
            "verification_reason": verdict.reason if verdict else "",
        })
    return items


def _verification(
    case: JsonMap,
    task: TaskFrame,
    state: ResearchState,
    verified_claims: list[JsonMap],
    missing_required: list,
) -> JsonMap:
    dropped = [
        item for item in state.verdicts_by_claim_id.values()
        if item.verdict == "drop"
    ]
    rewritten = [
        item for item in state.verdicts_by_claim_id.values()
        if item.verdict == "rewrite"
    ]
    return {
        "verification_id": stable_id("verification", str(case.get("id"))),
        "question_id": case.get("id"),
        "required_capabilities_attempted": task.required_capabilities,
        "required_evidence_status": [item.to_dict() for item in state.coverage_by_obligation_id.values()],
        "claim_verdicts": [item.to_dict() for item in state.verdicts_by_claim_id.values()],
        "unsupported_claim_count": len(dropped),
        "rewritten_claim_count": len(rewritten),
        "contradicted_claim_count": 0,
        "missing_required_evidence": [
            {
                "obligation_id": item.obligation_id,
                "description": item.description,
            }
            for item in missing_required
        ],
        "ambiguity_resolution": {
            "status": task.ambiguity_status,
            "requires_user_choice": False,
        },
        "constraint_check_results": [],
        "abstention_required": not verified_claims,
        "satisfied_trace_obligation_ids": [
            item.obligation_id for item in task.obligations
            if any(item.obligation_id in claim["obligation_ids"] for claim in verified_claims)
        ],
        "failed_trace_obligation_ids": [item.obligation_id for item in missing_required],
        "stage_contracts_passed": True,
        "answer_reference_integrity_passed": True,
        "corpus_observation_required": task.requires_corpus_observation,
        "corpus_observation_passed": bool(state.evidence_items_by_id) or not task.requires_corpus_observation,
    }


def _intent_artifact(case_id: str, case: JsonMap, task: TaskFrame) -> JsonMap:
    references = task.paper_references
    mentions = [str(item.get("text") or item.get("paper_id") or "") for item in references if item]
    return {
        "intent_id": stable_id("intent", case_id),
        "question_id": case_id,
        "raw_question": case.get("raw_question") or _case_question(case),
        "normalized_question": task.normalized_goal,
        "primary_paradigm": task.primary_paradigm,
        "entities": unique_strings([*mentions, *task.requested_aspects]),
        "paper_mentions": unique_strings(mentions),
        "method_mentions": [],
        "dataset_mentions": [],
        "constraints": task.constraints,
        "answer_type": task.answer_shape,
        "ambiguity_status": task.ambiguity_status,
        "actionability": task.actionability,
        "requires_corpus_observation": task.requires_corpus_observation,
        "required_evidence_types": task.required_evidence_types,
        "required_capabilities": task.required_capabilities,
        "obligations": [item.to_dict() for item in task.obligations],
    }


def _retrieval_plan(
    case: JsonMap,
    task: TaskFrame,
    recipe: ParadigmRecipe,
    state: ResearchState,
) -> JsonMap:
    return {
        "plan_id": stable_id("plan", str(case.get("id"))),
        "question_id": case.get("id"),
        "paradigm": task.primary_paradigm,
        "actionability": task.actionability,
        "target_entities": [
            str(item.get("text") or item.get("paper_id") or "")
            for item in task.paper_references
        ],
        "obligations": [item.to_dict() for item in task.obligations],
        "query_rounds": state.retrieval_rounds,
        "strategy_steps": [
            {
                "step_id": stable_id("stage", f"{index + 1}_{name}"),
                "stage_name": name,
                "semantic_goal": name,
                "strategy": name,
                "allowed_tools": (
                    [
                        "search_paper_candidates",
                        "find_papers_by_identity",
                        "find_reading_locations",
                        "read_locations",
                        "get_citation_edges",
                    ]
                    if index == 1 else []
                ),
                "stop_condition": "semantic_obligation_satisfied",
            }
            for index, name in enumerate(recipe.semantic_steps)
        ],
        "expected_evidence_types": task.required_evidence_types,
        "required_recall_targets": [],
        "hard_negative_policy": [],
        "stop_conditions": ["all_required_obligations_verified", "precise_evidence_gap"],
    }


def _reasoning_artifact(
    case_id: str,
    task: TaskFrame,
    recipe: ParadigmRecipe,
    state: ResearchState,
    answer: JsonMap,
) -> JsonMap:
    return {
        "artifact_id": stable_id("reasoning", case_id),
        "question_id": case_id,
        "type": recipe.answer_layout,
        "title": task.normalized_goal or "Research result",
        "source_claim_ids": list(state.claims_by_id),
        "payload": {
            "answer_fields": answer.get("fields", {}),
            "obligation_coverage": [item.to_dict() for item in state.coverage_by_obligation_id.values()],
            "claim_verdicts": [item.to_dict() for item in state.verdicts_by_claim_id.values()],
            "source_evidence_ids": list(state.evidence_items_by_id),
        },
    }


def _paper_candidate_observation(
    dataset: GoldenDataset,
    paper_id: str,
    card: JsonMap | None = None,
) -> JsonMap:
    record = dataset.paper_records_by_id[paper_id]
    identity = child_map(record.get("identity"))
    model = child_map(dataset.reading_models_by_paper_id.get(paper_id))
    card = card or {}
    return {
        "evidence_id": stable_id("paper_candidate", paper_id),
        "paper_id": paper_id,
        "title": card.get("title") or identity.get("title") or paper_id,
        "paper_version": identity.get("version_label") or model.get("model_version") or identity.get("year") or "unknown",
        "authors": as_list(card.get("authors")) or as_list(identity.get("authors")),
        "year": card.get("year") or identity.get("year"),
        "venue": card.get("venue") or identity.get("venue"),
        "section": "paper_metadata",
        "page": "metadata",
        "location": "paper_candidate",
        "element_type": "paper_candidate",
        "span_text": str(card.get("preview") or record.get("abstract") or identity.get("title") or paper_id)[:500],
        "retrieval_strategy": "paper_identity_resolution",
        "relevance_score": 1.0,
        "evidence_quality": "metadata_verified",
        "citeable": False,
        "supports_claim_ids": [],
        "refutes_claim_ids": [],
    }


def _evidence_prompt_item(item: JsonMap) -> JsonMap:
    return {
        "evidence_id": item.get("evidence_id"),
        "paper_id": item.get("paper_id"),
        "title": item.get("title"),
        "section": item.get("section"),
        "page": item.get("page"),
        "element_type": item.get("element_type"),
        "span_text": str(item.get("span_text") or "")[:1400],
        "citeable": item.get("citeable", True),
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


def _decision_run(
    case: JsonMap,
    route: str,
    direct_reply: str,
    pending_interaction: JsonMap,
    started_at: str,
    diagnostics: JsonMap,
    duration_ms: int,
    harness_id: str,
) -> JsonMap:
    case_id = str(case.get("id") or "turn")
    clarify = route == "clarify"
    status = "NEEDS_CLARIFICATION" if clarify else "COMPLETED"
    outcome = "needs_clarification" if clarify else "answered"
    markdown = direct_reply or str(pending_interaction.get("question") or "Please clarify your request.")
    answer = {
        "answer_id": stable_id("answer", case_id),
        "question_id": case_id,
        "status": status,
        "outcome": outcome,
        "answer_type": "ambiguity_clarification" if clarify else "conversation",
        "summary": markdown[:300],
        "markdown": markdown,
        "sections": [],
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
        "status": status,
        "result_status": status,
        "memory_update": {"pending_interaction": pending_interaction or None},
        "intent_frame": {"question_id": case_id, "primary_paradigm": "ambiguity_resolution" if clarify else "direct_conversation"},
        "retrieval_plan": {"question_id": case_id, "strategy_steps": []},
        "stage_trace": [],
        "evidence_ledger": {"question_id": case_id, "items": [], "rejected_items": [], "missing_evidence": []},
        "claim_graph": {"question_id": case_id, "claims": [], "edges": []},
        "reasoning_artifacts": [],
        "verification_pass": {"question_id": case_id, "unsupported_claim_count": 0},
        "research_answer": answer,
        "final_answer": answer,
        "diagnostics": {**diagnostics, "duration_ms": duration_ms, "finish_reason": f"turn_decision_{route}"},
    }


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
        "outcome": None,
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
        "intent_frame": {"question_id": case_id, "primary_paradigm": "unknown"},
        "retrieval_plan": {"question_id": case_id, "strategy_steps": []},
        "stage_trace": [],
        "evidence_ledger": {"question_id": case_id, "items": [], "rejected_items": [], "missing_evidence": []},
        "claim_graph": {"question_id": case_id, "claims": [], "edges": []},
        "reasoning_artifacts": [],
        "verification_pass": {"question_id": case_id, "unsupported_claim_count": 0},
        "research_answer": answer,
        "final_answer": answer,
        "diagnostics": {
            "finish_reason": "evidence_bounded_runtime_failed",
            "error": reason,
            **(model_diagnostics or {}),
            "duration_ms": duration_ms,
        },
    }


def _now() -> str:
    return datetime.now(UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z")
