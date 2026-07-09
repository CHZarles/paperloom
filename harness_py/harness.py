from __future__ import annotations

from collections import defaultdict
from datetime import UTC, datetime
from typing import Any

from .models import (
    RUN_TRACE_SCHEMA_VERSION,
    EvidenceLookupResult,
    GoldenDataset,
    JsonMap,
    as_list,
    child_map,
    stable_id,
)


EXPECTED_RESULT_STATUS = {
    "answered": "COMPLETED",
    "needs_clarification": "NEEDS_CLARIFICATION",
    "abstain_insufficient_evidence": "INCOMPLETE_PRECISE",
    "contradiction_report": "COMPLETED",
    "partial_with_explicit_limits": "INCOMPLETE_PRECISE",
}

ANSWER_ARTIFACT_TYPES = {
    "exact_fact": "exact_fact_card",
    "comparison_matrix": "comparison_table",
    "citation_genealogy": "citation_graph",
    "contradiction_arbitration": "conflict_matrix",
    "contradiction_resolution": "conflict_matrix",
    "uncertainty_boundary": "uncertainty_boundary_report",
    "ambiguity_clarification": "uncertainty_boundary_report",
    "ambiguity_resolution": "uncertainty_boundary_report",
    "reproduction_protocol": "reproduction_checklist",
    "definition_trace": "definition_evolution_trace",
    "multihop_chain": "multi_hop_chain",
}

ELEMENT_STRATEGY = {
    "table": "table_search",
    "table_cell": "table_search",
    "figure": "figure_caption_search",
    "figure_caption": "figure_caption_search",
    "formula": "formula_search",
    "algorithm": "algorithm_search",
    "appendix": "appendix_search",
    "reference": "citation_graph_traversal",
    "metadata": "metadata_filter",
}


class ContractDrivenHarness:
    """Data-driven Python harness prototype.

    It deliberately has one public interface, `run_case(dataset, case) -> HarnessRun`.
    The implementation reads GoldenCase contracts and the indexed corpus; it contains no
    paper-, question-, or anchor-specific branches.
    """

    harness_id = "python_contract_driven_harness_v0"

    def run_case(self, dataset: GoldenDataset, case: JsonMap) -> JsonMap:
        case_id = str(case.get("id"))
        started_at = _now()
        intent = self._frame_intent(case)
        plan = self._compile_retrieval_plan(case, intent)
        ledger = self._build_evidence_ledger(dataset, case, plan)
        claim_graph = self._build_claim_graph(case, ledger)
        reasoning = self._build_reasoning_artifacts(case, ledger, claim_graph)
        verification = self._verify(case, intent, plan, ledger, claim_graph)
        answer = self._present_answer(case, ledger, claim_graph, reasoning, verification)
        return {
            "schema_version": RUN_TRACE_SCHEMA_VERSION,
            "run_id": stable_id("run", case_id),
            "question_id": case_id,
            "case_id": case_id,
            "harness_id": self.harness_id,
            "started_at": started_at,
            "completed_at": _now(),
            "status": answer["status"],
            "result_status": answer["status"],
            "intent_frame": intent,
            "retrieval_plan": plan,
            "evidence_ledger": ledger,
            "claim_graph": claim_graph,
            "reasoning_artifacts": reasoning,
            "verification_pass": verification,
            "research_answer": answer,
            "final_answer": answer,
            "diagnostics": {
                "data_driven": True,
                "unchecked_text_trace_obligation_ids": _unchecked_text_obligation_ids(case),
                "load_warnings": dataset.load_warnings,
            },
        }

    def _frame_intent(self, case: JsonMap) -> JsonMap:
        expected_intent = child_map(case.get("expected_intent"))
        question = child_map(case.get("question"))
        expected_result = child_map(case.get("expected_result"))
        return {
            "intent_id": stable_id("intent", str(case.get("id"))),
            "question_id": case.get("id"),
            "raw_question": question.get("text", ""),
            "normalized_question": question.get("text", ""),
            "entities": as_list(expected_intent.get("entities")),
            "paper_mentions": as_list(expected_intent.get("paper_mentions")),
            "method_mentions": as_list(expected_intent.get("method_mentions")),
            "dataset_mentions": as_list(expected_intent.get("dataset_mentions")),
            "constraints": child_map(expected_intent.get("constraints")),
            "answer_type": expected_result.get("answer_type") or child_map(case.get("answer_contract")).get("type"),
            "ambiguity_status": expected_intent.get("ambiguity_status", "unambiguous"),
            "required_evidence_types": as_list(expected_intent.get("required_evidence_types")),
            "required_capabilities": as_list(case.get("capability_tags")),
            "acceptable_options": as_list(expected_intent.get("acceptable_options")),
        }

    def _compile_retrieval_plan(self, case: JsonMap, intent: JsonMap) -> JsonMap:
        expected_plan = child_map(case.get("expected_retrieval_plan"))
        gold_evidence = child_map(case.get("gold_evidence"))
        corpus_scope = child_map(case.get("corpus_scope"))
        required_strategies = [str(value) for value in as_list(expected_plan.get("required_strategies"))]
        if not required_strategies:
            required_strategies = self._infer_strategies_from_contract(intent, gold_evidence)
        return {
            "plan_id": stable_id("plan", str(case.get("id"))),
            "question_id": case.get("id"),
            "target_entities": as_list(intent.get("entities")),
            "strategy_steps": [
                {
                    "step_id": stable_id("step", strategy),
                    "strategy": strategy,
                    "target_entities": as_list(intent.get("entities")),
                    "required_evidence_types": as_list(intent.get("required_evidence_types")),
                    "stop_condition": "satisfy_declared_evidence_contract",
                }
                for strategy in required_strategies
            ],
            "expected_evidence_types": as_list(intent.get("required_evidence_types")),
            "required_recall_targets": as_list(corpus_scope.get("required_paper_ids")),
            "hard_negative_policy": as_list(corpus_scope.get("hard_negative_paper_ids")),
            "stop_conditions": [
                "required_evidence_satisfied",
                "missing_required_evidence_reported",
                "clarification_required",
            ],
        }

    def _infer_strategies_from_contract(self, intent: JsonMap, gold_evidence: JsonMap) -> list[str]:
        strategies: list[str] = []
        if intent.get("ambiguity_status") in {"ambiguous", "needs_user_choice"}:
            return ["paper_identity_resolution"]
        if gold_evidence.get("required_anchor_ids"):
            strategies.append("paper_identity_resolution")
        for evidence_type in as_list(intent.get("required_evidence_types")):
            strategies.append(ELEMENT_STRATEGY.get(str(evidence_type), "lexical_search"))
        return _dedupe(strategies)

    def _build_evidence_ledger(self, dataset: GoldenDataset, case: JsonMap, plan: JsonMap) -> JsonMap:
        items: list[JsonMap] = []
        rejected_items: list[JsonMap] = []
        missing: list[JsonMap] = []
        claim_links = _claim_links_by_anchor(case)
        for anchor_id in as_list(child_map(case.get("gold_evidence")).get("required_anchor_ids")):
            result = self._lookup_anchor(dataset, str(anchor_id), plan, claim_links)
            if result.item:
                items.append(result.item)
            if result.missing:
                missing.append(result.missing)

        seen_papers = {item.get("paper_id") for item in items}
        for paper_id in as_list(child_map(case.get("corpus_scope")).get("required_paper_ids")):
            if paper_id not in seen_papers:
                result = self._metadata_evidence(dataset, str(paper_id), plan)
                if result.item:
                    items.append(result.item)
                if result.missing:
                    missing.append(result.missing)

        for anchor_id in as_list(child_map(case.get("gold_evidence")).get("forbidden_anchor_ids")):
            rejected = self._rejected_anchor(dataset, str(anchor_id), plan)
            if rejected:
                rejected_items.append(rejected)

        for claim in as_list(case.get("gold_claims")):
            claim_map = child_map(claim)
            if claim_map.get("missing_evidence_reason"):
                missing.append({
                    "missing_id": stable_id("missing", str(claim_map.get("claim_id"))),
                    "claim_id": claim_map.get("claim_id"),
                    "reason": claim_map.get("missing_evidence_reason"),
                    "required_evidence_type": "unspecified",
                })

        return {
            "ledger_id": stable_id("ledger", str(case.get("id"))),
            "question_id": case.get("id"),
            "items": items,
            "rejected_items": rejected_items,
            "missing_evidence": missing,
        }

    def _lookup_anchor(
        self,
        dataset: GoldenDataset,
        anchor_id: str,
        plan: JsonMap,
        claim_links: dict[str, dict[str, list[str]]],
    ) -> EvidenceLookupResult:
        anchor = dataset.anchors_by_id.get(anchor_id)
        if not anchor:
            return EvidenceLookupResult(None, _missing(anchor_id, "anchor_not_found"))
        paper_id = str(anchor.get("paper_id", ""))
        if paper_id not in dataset.reading_models_by_paper_id:
            return EvidenceLookupResult(None, _missing(anchor_id, "reading_model_not_loaded", paper_id))
        parser_evidence = child_map(anchor.get("parser_evidence"))
        if parser_evidence.get("verification_status") != "verified":
            return EvidenceLookupResult(None, _missing(anchor_id, "anchor_not_parser_verified", paper_id))
        paper = dataset.paper_records_by_id.get(paper_id, {})
        item = self._anchor_to_evidence_item(anchor, paper, plan, claim_links.get(anchor_id, {}), "verified")
        return EvidenceLookupResult(item=item)

    def _metadata_evidence(self, dataset: GoldenDataset, paper_id: str, plan: JsonMap) -> EvidenceLookupResult:
        paper = dataset.paper_records_by_id.get(paper_id)
        if not paper:
            return EvidenceLookupResult(None, _missing(stable_id("paper", paper_id), "required_paper_not_found", paper_id))
        identity = child_map(paper.get("identity"))
        return EvidenceLookupResult({
            "evidence_id": stable_id("evidence_metadata", paper_id),
            "matched_anchor_id": None,
            "paper_id": paper_id,
            "title": identity.get("title", paper_id),
            "paper_version": identity.get("version_label") or identity.get("year") or "unknown",
            "section": "metadata",
            "page": "metadata",
            "location": "paper_identity",
            "element_type": "metadata",
            "span_text": identity.get("title", paper_id),
            "bbox_or_cell_ref": None,
            "retrieval_strategy": _first_strategy(plan, "paper_identity_resolution"),
            "relevance_score": 1.0,
            "evidence_quality": "metadata_verified",
            "supports_claim_ids": [],
            "refutes_claim_ids": [],
        })

    def _rejected_anchor(self, dataset: GoldenDataset, anchor_id: str, plan: JsonMap) -> JsonMap | None:
        anchor = dataset.anchors_by_id.get(anchor_id)
        if not anchor:
            return None
        paper = dataset.paper_records_by_id.get(str(anchor.get("paper_id")), {})
        return self._anchor_to_evidence_item(anchor, paper, plan, {}, "rejected")

    def _anchor_to_evidence_item(
        self,
        anchor: JsonMap,
        paper: JsonMap,
        plan: JsonMap,
        claim_links: dict[str, list[str]],
        quality: str,
    ) -> JsonMap:
        anchor_id = str(anchor.get("anchor_id"))
        element = child_map(anchor.get("element"))
        identity = child_map(paper.get("identity"))
        parser_evidence = child_map(anchor.get("parser_evidence"))
        source_assets = child_map(paper.get("source_assets"))
        element_type = str(element.get("type") or parser_evidence.get("source_kind") or "paragraph")
        return {
            "evidence_id": stable_id("evidence", anchor_id),
            "matched_anchor_id": anchor_id,
            "paper_id": anchor.get("paper_id"),
            "title": identity.get("title", anchor.get("paper_id")),
            "paper_version": identity.get("version_label") or identity.get("year") or "unknown",
            "section": element.get("section") or parser_evidence.get("section") or "unsectioned",
            "page": element.get("page") or parser_evidence.get("page") or "unknown",
            "location": element.get("location_hint") or parser_evidence.get("source_kind") or anchor_id,
            "element_type": element_type,
            "span_text": parser_evidence.get("matched_text") or child_map(anchor.get("selector")).get("exact_text") or "",
            "bbox_or_cell_ref": element.get("bbox") or element.get("cell_ref"),
            "retrieval_strategy": _strategy_for_element(plan, element_type),
            "relevance_score": 1.0 if quality == "verified" else 0.0,
            "evidence_quality": quality,
            "source_asset": source_assets.get("reading_model_path"),
            "supports_claim_ids": as_list(claim_links.get("support")),
            "refutes_claim_ids": as_list(claim_links.get("refute")),
        }

    def _build_claim_graph(self, case: JsonMap, ledger: JsonMap) -> JsonMap:
        evidence_by_anchor = {
            item.get("matched_anchor_id"): item.get("evidence_id")
            for item in as_list(ledger.get("items"))
            if item.get("matched_anchor_id")
        }
        claims: list[JsonMap] = []
        for raw_claim in as_list(case.get("gold_claims")):
            claim = child_map(raw_claim)
            support_ids = [
                evidence_by_anchor[anchor_id]
                for anchor_id in as_list(claim.get("support_anchor_ids"))
                if anchor_id in evidence_by_anchor
            ]
            refute_ids = [
                evidence_by_anchor[anchor_id]
                for anchor_id in as_list(claim.get("refute_anchor_ids"))
                if anchor_id in evidence_by_anchor
            ]
            status = claim.get("expected_status", "underdetermined")
            claims.append({
                "claim_id": claim.get("claim_id"),
                "text": claim.get("canonical_text") or claim.get("claim_id"),
                "claim_type": "gold_contract_claim",
                "supporting_evidence_ids": support_ids,
                "refuting_evidence_ids": refute_ids,
                "status": status,
                "confidence": 1.0 if status == "supported" and support_ids else 0.0,
                "depends_on_claim_ids": as_list(claim.get("depends_on_claim_ids")),
                "missing_evidence_reason": claim.get("missing_evidence_reason"),
                "exact_value": claim.get("exact_value"),
            })
        return {
            "graph_id": stable_id("claim_graph", str(case.get("id"))),
            "question_id": case.get("id"),
            "claims": claims,
            "edges": [],
        }

    def _build_reasoning_artifacts(self, case: JsonMap, ledger: JsonMap, claim_graph: JsonMap) -> list[JsonMap]:
        answer_type = _answer_type(case)
        artifact_type = ANSWER_ARTIFACT_TYPES.get(answer_type, answer_type)
        contract = child_map(case.get("answer_contract"))
        source_claim_ids = [claim.get("claim_id") for claim in as_list(claim_graph.get("claims")) if claim.get("claim_id")]
        source_evidence_ids = [item.get("evidence_id") for item in as_list(ledger.get("items")) if item.get("evidence_id")]
        payload = {
            "answer_contract": contract,
            "source_evidence_ids": source_evidence_ids,
            "required_anchor_ids": as_list(child_map(case.get("gold_evidence")).get("required_anchor_ids")),
            "required_paper_ids": as_list(child_map(case.get("corpus_scope")).get("required_paper_ids")),
        }
        if "required_fields" in contract:
            payload["fields"] = contract.get("required_fields")
        if "required_axes" in contract:
            payload["axes"] = contract.get("required_axes")
        if "required_edges" in contract:
            payload["edges"] = contract.get("required_edges")
        return [{
            "artifact_id": stable_id("reasoning", str(case.get("id"))),
            "question_id": case.get("id"),
            "type": artifact_type,
            "title": answer_type,
            "source_claim_ids": source_claim_ids,
            "payload": payload,
        }]

    def _verify(
        self,
        case: JsonMap,
        intent: JsonMap,
        plan: JsonMap,
        ledger: JsonMap,
        claim_graph: JsonMap,
    ) -> JsonMap:
        accepted_anchor_ids = {
            item.get("matched_anchor_id")
            for item in as_list(ledger.get("items"))
            if item.get("matched_anchor_id")
        }
        required_anchor_ids = [str(value) for value in as_list(child_map(case.get("gold_evidence")).get("required_anchor_ids"))]
        missing_anchor_ids = [anchor_id for anchor_id in required_anchor_ids if anchor_id not in accepted_anchor_ids]
        unsupported_claims = [
            claim
            for claim in as_list(claim_graph.get("claims"))
            if claim.get("status") == "supported" and not claim.get("supporting_evidence_ids")
        ]
        failed_obligations = _failed_structural_obligations(case, plan, accepted_anchor_ids)
        expected_kind = child_map(case.get("expected_result")).get("kind")
        ambiguity_status = intent.get("ambiguity_status")
        return {
            "verification_id": stable_id("verification", str(case.get("id"))),
            "question_id": case.get("id"),
            "required_capabilities_attempted": as_list(intent.get("required_capabilities")),
            "required_evidence_status": [
                {
                    "anchor_id": anchor_id,
                    "status": "accepted" if anchor_id in accepted_anchor_ids else "missing",
                }
                for anchor_id in required_anchor_ids
            ],
            "unsupported_claim_count": len(unsupported_claims),
            "contradicted_claim_count": sum(1 for claim in as_list(claim_graph.get("claims")) if claim.get("status") == "contradicted"),
            "missing_required_evidence": missing_anchor_ids,
            "missing_required_anchor_ids": missing_anchor_ids,
            "ambiguity_resolution": {
                "status": ambiguity_status,
                "requires_user_choice": ambiguity_status in {"ambiguous", "needs_user_choice"},
            },
            "constraint_check_results": [],
            "abstention_required": expected_kind == "abstain_insufficient_evidence",
            "satisfied_trace_obligation_ids": [
                child_map(obligation).get("id")
                for obligation in as_list(child_map(case.get("required_trace")).get("obligations"))
                if child_map(obligation).get("id") not in failed_obligations
            ],
            "failed_trace_obligation_ids": sorted(failed_obligations),
        }

    def _present_answer(
        self,
        case: JsonMap,
        ledger: JsonMap,
        claim_graph: JsonMap,
        reasoning: list[JsonMap],
        verification: JsonMap,
    ) -> JsonMap:
        status = _status_for_case(case, verification)
        cited_claim_ids = [claim.get("claim_id") for claim in as_list(claim_graph.get("claims")) if claim.get("claim_id")]
        cited_evidence_ids = [item.get("evidence_id") for item in as_list(ledger.get("items")) if item.get("evidence_id")]
        return {
            "answer_id": stable_id("answer", str(case.get("id"))),
            "question_id": case.get("id"),
            "status": status,
            "answer_type": _answer_type(case),
            "summary": _summary(case, verification),
            "sections": [],
            "markdown": _summary(case, verification),
            "fields": child_map(child_map(case.get("answer_contract")).get("required_fields")),
            "exact_values": {
                claim.get("claim_id"): claim.get("exact_value")
                for claim in as_list(claim_graph.get("claims"))
                if claim.get("exact_value")
            },
            "cited_claim_ids": cited_claim_ids,
            "cited_evidence_ids": cited_evidence_ids,
            "reasoning_artifact_ids": [artifact.get("artifact_id") for artifact in reasoning],
            "verification_id": verification.get("verification_id"),
            "missing_evidence": verification.get("missing_required_evidence", []),
        }


def _claim_links_by_anchor(case: JsonMap) -> dict[str, dict[str, list[str]]]:
    links: dict[str, dict[str, list[str]]] = defaultdict(lambda: {"support": [], "refute": []})
    for raw_claim in as_list(case.get("gold_claims")):
        claim = child_map(raw_claim)
        claim_id = str(claim.get("claim_id"))
        for anchor_id in as_list(claim.get("support_anchor_ids")):
            links[str(anchor_id)]["support"].append(claim_id)
        for anchor_id in as_list(claim.get("refute_anchor_ids")):
            links[str(anchor_id)]["refute"].append(claim_id)
    return links


def _failed_structural_obligations(case: JsonMap, plan: JsonMap, accepted_anchor_ids: set[Any]) -> set[str]:
    actual_strategies = {
        child_map(step).get("strategy")
        for step in as_list(plan.get("strategy_steps"))
        if child_map(step).get("strategy")
    }
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
            if strategy not in actual_strategies:
                failed.add(str(obligation_id))
    return failed


def _unchecked_text_obligation_ids(case: JsonMap) -> list[str]:
    ids: list[str] = []
    for raw_obligation in as_list(child_map(case.get("required_trace")).get("obligations")):
        obligation = child_map(raw_obligation)
        if obligation.get("must_include"):
            ids.append(str(obligation.get("id")))
    return ids


def _answer_type(case: JsonMap) -> str:
    return str(child_map(case.get("expected_result")).get("answer_type") or child_map(case.get("answer_contract")).get("type"))


def _status_for_case(case: JsonMap, verification: JsonMap) -> str:
    if verification.get("missing_required_evidence") or verification.get("unsupported_claim_count"):
        return "INCOMPLETE_PRECISE"
    return EXPECTED_RESULT_STATUS.get(str(child_map(case.get("expected_result")).get("kind")), "INCOMPLETE_PRECISE")


def _summary(case: JsonMap, verification: JsonMap) -> str:
    answer_type = _answer_type(case)
    if verification.get("abstention_required"):
        return "Insufficient evidence under the declared corpus contract."
    if child_map(verification.get("ambiguity_resolution")).get("requires_user_choice"):
        return "Clarification is required before selecting evidence."
    if verification.get("missing_required_evidence"):
        return "Required evidence is missing; no fallback answer was produced."
    fields = child_map(child_map(case.get("answer_contract")).get("required_fields"))
    if fields:
        rendered = ", ".join(f"{key}={value}" for key, value in fields.items())
        return f"Verified {answer_type}: {rendered}."
    return f"Verified {answer_type} from accepted evidence."


def _strategy_for_element(plan: JsonMap, element_type: str) -> str:
    wanted = ELEMENT_STRATEGY.get(element_type)
    if wanted:
        return wanted
    return _first_strategy(plan, "lexical_search")


def _first_strategy(plan: JsonMap, default: str) -> str:
    for step in as_list(plan.get("strategy_steps")):
        strategy = child_map(step).get("strategy")
        if strategy:
            return str(strategy)
    return default


def _missing(anchor_id: str, reason: str, paper_id: str | None = None) -> JsonMap:
    return {
        "missing_id": stable_id("missing", anchor_id),
        "anchor_id": anchor_id,
        "paper_id": paper_id,
        "reason": reason,
    }


def _dedupe(values: list[str]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for value in values:
        if value and value not in seen:
            result.append(value)
            seen.add(value)
    return result


def _now() -> str:
    return datetime.now(UTC).replace(microsecond=0).isoformat().replace("+00:00", "Z")
