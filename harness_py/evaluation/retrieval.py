from __future__ import annotations

from collections.abc import Callable

from ..corpus.gateway import CorpusReader
from ..utils.models import GoldenDataset, JsonMap, as_list, child_map, utc_now_iso
from .claim_evidence import canonical_sha256, dataset_content_sha256


RETRIEVAL_REPORT_SCHEMA_VERSION = "harness-retrieval-report/v1"
RETRIEVAL_CUTOFFS = (1, 3, 5, 10, 20)


def evaluate_product_retrieval(
    dataset: GoldenDataset,
    claims: list[JsonMap],
    reader_for_claim: Callable[[JsonMap], CorpusReader],
) -> JsonMap:
    probes: list[JsonMap] = []
    skipped_claim_ids: list[str] = []
    technical_errors: list[JsonMap] = []

    for claim in claims:
        claim_id = str(claim.get("claim_id") or "")
        queries = [
            str(query).strip()
            for query in dataset.retrieval_queries_by_claim_id.get(claim_id, [])
            if str(query).strip()
        ]
        if not queries:
            skipped_claim_ids.append(claim_id)
            continue
        for query_index, query in enumerate(queries, start=1):
            probe_id = f"{claim_id}:{query_index}"
            try:
                reader = reader_for_claim(claim)
                response = reader.search_locations({
                    "paper_ids": _required_paper_ids(claim),
                    "query_text": query,
                    "top_k": max(RETRIEVAL_CUTOFFS),
                })
                probes.append(_score_probe(probe_id, claim, query, response))
            except Exception as error:
                technical_errors.append({
                    "probe_id": probe_id,
                    "claim_id": claim_id,
                    "error_type": type(error).__name__,
                    "message": str(error),
                })

    report: JsonMap = {
        "schema_version": RETRIEVAL_REPORT_SCHEMA_VERSION,
        "generated_at": utc_now_iso(),
        "dataset_id": dataset.manifest.get("dataset_id"),
        "dataset_content_sha256": dataset_content_sha256(dataset),
        "retrieval_queries_sha256": canonical_sha256(
            dataset.retrieval_queries_by_claim_id
        ),
        "retrieval_contract": {
            "unit": "golden-claim-evidence-requirement",
            "candidate": "product-location-returned-by-java-qdrant-mysql-path",
            "cutoffs": list(RETRIEVAL_CUTOFFS),
            "sha256": canonical_sha256({
                "schema_version": RETRIEVAL_REPORT_SCHEMA_VERSION,
                "cutoffs": RETRIEVAL_CUTOFFS,
            }),
        },
        "configured_claim_count": len(claims) - len(skipped_claim_ids),
        "skipped_claim_ids": skipped_claim_ids,
        "probe_count": len(probes) + len(technical_errors),
        "resolved_probe_count": len(probes),
        "technical_error_count": len(technical_errors),
        "technical_errors": technical_errors,
        "metrics": _aggregate(probes),
        "probes": probes,
    }
    return report


def _score_probe(probe_id: str, claim: JsonMap, query: str, response: JsonMap) -> JsonMap:
    locations = _unique_locations(response)
    requirements = [
        child_map(item)
        for item in as_list(claim.get("required_evidence"))
    ]
    requirement_scores: list[JsonMap] = []
    for requirement in requirements:
        paper_id = str(requirement.get("paper_id") or "")
        accepted = {
            str(item)
            for item in as_list(requirement.get("accepted_locations"))
            if str(item)
        }
        first_rank = next((
            index
            for index, location in enumerate(locations, start=1)
            if str(location.get("paper_id") or "") == paper_id
            and _location_ref(location) in accepted
        ), None)
        requirement_scores.append({
            "paper_id": paper_id,
            "accepted_locations": sorted(accepted),
            "first_relevant_rank": first_rank,
            "recall_at_k": {
                str(cutoff): first_rank is not None and first_rank <= cutoff
                for cutoff in RETRIEVAL_CUTOFFS
            },
        })

    return {
        "probe_id": probe_id,
        "claim_id": claim.get("claim_id"),
        "query_text": query,
        "paper_ids": _required_paper_ids(claim),
        "candidate_count": len(locations),
        "matched_count": response.get("matched_count"),
        "index_version": response.get("index_version"),
        "location_recall_at_k": {
            str(cutoff): _recall(requirement_scores, cutoff)
            for cutoff in RETRIEVAL_CUTOFFS
        },
        "claim_complete_at_k": {
            str(cutoff): all(_hit(item, cutoff) for item in requirement_scores)
            for cutoff in RETRIEVAL_CUTOFFS
        },
        "mean_reciprocal_rank": _mean_reciprocal_rank(requirement_scores),
        "requirements": requirement_scores,
        "candidates": [
            {
                "rank": rank,
                "paper_id": location.get("paper_id"),
                "location_ref": _location_ref(location),
                "element_type": location.get("element_type"),
                "page": location.get("page"),
                "section": location.get("section"),
                "sparse_score": location.get("sparse_score"),
                "dense_score": location.get("dense_score"),
                "fused_score": location.get("fused_score"),
            }
            for rank, location in enumerate(locations, start=1)
        ],
    }


def _aggregate(probes: list[JsonMap]) -> JsonMap:
    requirements = [
        child_map(requirement)
        for probe in probes
        for requirement in as_list(probe.get("requirements"))
    ]
    if not probes or not requirements:
        return {}
    return {
        "requirement_count": len(requirements),
        "location_recall_at_k": {
            str(cutoff): _recall(requirements, cutoff)
            for cutoff in RETRIEVAL_CUTOFFS
        },
        "claim_complete_rate_at_k": {
            str(cutoff): sum(
                bool(child_map(probe.get("claim_complete_at_k")).get(str(cutoff)))
                for probe in probes
            ) / len(probes)
            for cutoff in RETRIEVAL_CUTOFFS
        },
        "mean_reciprocal_rank": _mean_reciprocal_rank(requirements),
        "average_candidate_count": sum(
            int(probe.get("candidate_count") or 0)
            for probe in probes
        ) / len(probes),
    }


def _required_paper_ids(claim: JsonMap) -> list[str]:
    return list(dict.fromkeys(
        str(child_map(item).get("paper_id") or "")
        for item in as_list(claim.get("required_evidence"))
        if child_map(item).get("paper_id")
    ))


def _unique_locations(response: JsonMap) -> list[JsonMap]:
    unique: dict[str, JsonMap] = {}
    for raw in as_list(response.get("locations")):
        location = child_map(raw)
        location_ref = _location_ref(location)
        if location_ref and location_ref not in unique:
            unique[location_ref] = location
    return list(unique.values())


def _location_ref(location: JsonMap) -> str:
    return str(location.get("location_ref") or location.get("location") or "")


def _hit(requirement: JsonMap, cutoff: int) -> bool:
    rank = requirement.get("first_relevant_rank")
    return isinstance(rank, int) and rank <= cutoff


def _recall(requirements: list[JsonMap], cutoff: int) -> float:
    if not requirements:
        return 0.0
    return sum(_hit(requirement, cutoff) for requirement in requirements) / len(requirements)


def _mean_reciprocal_rank(requirements: list[JsonMap]) -> float:
    if not requirements:
        return 0.0
    return sum(
        1.0 / rank if isinstance(rank, int) and rank > 0 else 0.0
        for requirement in requirements
        for rank in [requirement.get("first_relevant_rank")]
    ) / len(requirements)
