#!/usr/bin/env python3
"""Verify and summarize manual adjudication of the Qdrant impact benchmark."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any, Mapping, Sequence

import yaml


REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT))

from harness_py.evaluation.paths import reading_model_path, resolve_authoring_path  # noqa: E402


ADJUDICATION_SCHEMA_VERSION = "harness-qdrant-impact-adjudication/v1"
REPORT_SCHEMA_VERSION = "harness-qdrant-impact-adjudication-report/v1"
BENCHMARK_REPORT_SCHEMA_VERSION = "harness-qdrant-impact-report/v1"
DATASET_FINGERPRINT_ALGORITHM = "sha256-canonical-file-inventory/v1"
ALLOWED_STATUSES = frozenset({"equivalent", "insufficient"})
REVIEW_AUTHORITY = "human"


class AdjudicationError(ValueError):
    """Raised when the adjudication cannot be proved from its source artifacts."""


def build_adjudication_report(
    adjudication_path: str | Path,
    *,
    repo_root: str | Path = REPO_ROOT,
) -> dict[str, Any]:
    """Validate an adjudication file and recompute all reported totals."""

    root = Path(repo_root).resolve()
    adjudication_file = _resolve_path(adjudication_path, root)
    adjudication = _load_yaml(adjudication_file)
    if adjudication.get("schema_version") != ADJUDICATION_SCHEMA_VERSION:
        raise AdjudicationError(
            "unsupported adjudication schema: "
            f"{adjudication.get('schema_version')!r}"
        )

    source_report_value = _required_string(adjudication, "source_report")
    source_queries_value = _required_string(adjudication, "source_queries")
    source_report_path = _resolve_path(source_report_value, root)
    source_queries_path = _resolve_path(source_queries_value, root)
    frozen_sources = _mapping(adjudication.get("frozen_sources"), "frozen_sources")
    _verify_frozen_hash(
        source_report_path,
        _required_sha256(frozen_sources, "source_report_sha256", "frozen_sources"),
        "source report",
    )
    _verify_frozen_hash(
        source_queries_path,
        _required_sha256(frozen_sources, "source_queries_sha256", "frozen_sources"),
        "source queries",
    )
    dataset_manifest_value = _required_string(
        frozen_sources,
        "dataset_manifest",
        "frozen_sources",
    )
    dataset_manifest_path = _resolve_path(dataset_manifest_value, root)
    dataset_content = dataset_content_provenance(
        dataset_manifest_path,
        repo_root=root,
    )
    expected_dataset_sha256 = _required_sha256(
        frozen_sources,
        "dataset_content_sha256",
        "frozen_sources",
    )
    if dataset_content["sha256"] != expected_dataset_sha256:
        raise AdjudicationError(
            "frozen dataset content fingerprint mismatch: expected "
            f"{expected_dataset_sha256}, observed {dataset_content['sha256']}"
        )

    review_authority = _required_string(adjudication, "review_authority")
    if review_authority != REVIEW_AUTHORITY:
        raise AdjudicationError(
            f"review_authority must be {REVIEW_AUTHORITY!r}; semantic equivalence "
            "is a human adjudication, not an automated proof"
        )
    review_basis = _required_string(adjudication, "review_basis")
    review_type = _required_string(adjudication, "review_type")
    source_report = _load_json(source_report_path)
    query_rows = _load_jsonl(source_queries_path)
    _verify_source_report(source_report, source_queries_path, root)
    _verify_embedded_dataset_fingerprint(
        source_report,
        expected_dataset_sha256,
    )

    query_index, obligations_by_case = _index_queries(query_rows)
    obligations = {
        (case_id, anchor_id)
        for case_id, anchor_ids in obligations_by_case.items()
        for anchor_id in anchor_ids
    }
    _verify_preflight_counts(source_report, obligations_by_case, obligations)

    raw_methods = _mapping(adjudication.get("methods"), "methods")
    if not raw_methods:
        raise AdjudicationError("methods must not be empty")

    method_reports: dict[str, Any] = {}
    for method in sorted(raw_methods):
        method_spec = _mapping(raw_methods[method], f"methods.{method}")
        exact_hits = _exact_hits(method, query_rows, obligations)
        exact_totals = _totals(obligations_by_case, exact_hits)
        _verify_source_method(source_report, method, exact_totals)
        _verify_declared_counts(
            _mapping(method_spec.get("exact"), f"methods.{method}.exact"),
            exact_totals,
            fields=("anchor_hits", "anchor_required", "full_cases", "eligible_cases"),
            context=f"methods.{method}.exact",
        )

        accepted = set(exact_hits)
        reviewed_misses: set[tuple[str, str]] = set()
        verified_decisions: list[dict[str, Any]] = []
        status_counts: dict[str, int] = defaultdict(int)
        seen_decisions: set[tuple[str, str]] = set()

        decisions = _sequence(method_spec.get("decisions"), f"methods.{method}.decisions")
        for index, raw_decision in enumerate(decisions, start=1):
            context = f"methods.{method}.decisions[{index}]"
            decision = _mapping(raw_decision, context)
            case_id = _required_string(decision, "case_id", context)
            anchor_id = _required_string(decision, "anchor_id", context)
            key = (case_id, anchor_id)
            if key not in obligations:
                raise AdjudicationError(
                    f"{context} is not a required case/anchor obligation: {key!r}"
                )
            if key in seen_decisions:
                raise AdjudicationError(
                    f"{context} duplicates the decision for obligation {key!r}"
                )
            if key in exact_hits:
                raise AdjudicationError(
                    f"{context} adjudicates an anchor already found exactly: {key!r}"
                )
            seen_decisions.add(key)
            reviewed_misses.add(key)

            status = _required_string(decision, "status", context)
            if status not in ALLOWED_STATUSES:
                raise AdjudicationError(
                    f"{context}.status must be one of {sorted(ALLOWED_STATUSES)}"
                )
            query_id = _required_string(decision, "query_id", context)
            row = query_index.get(query_id)
            if row is None:
                raise AdjudicationError(f"{context} references unknown query {query_id!r}")
            if str(row.get("case_id") or "") != case_id:
                raise AdjudicationError(
                    f"{context} query {query_id!r} belongs to "
                    f"{row.get('case_id')!r}, not {case_id!r}"
                )

            rank = _positive_int(decision.get("rank"), f"{context}.rank")
            location_ref = _required_string(decision, "location_ref", context)
            native = _native_result(row, method, query_id)
            location_refs = _string_list(
                native.get("location_refs"),
                f"query {query_id!r} method {method!r} native.location_refs",
            )
            if rank > len(location_refs):
                raise AdjudicationError(
                    f"{context}.rank={rank} exceeds the {len(location_refs)} "
                    f"returned locations for query {query_id!r}"
                )
            observed_location_ref = location_refs[rank - 1]
            if observed_location_ref != location_ref:
                raise AdjudicationError(
                    f"{context} expected {location_ref!r} exactly at rank {rank}, "
                    f"but observed {observed_location_ref!r}"
                )

            status_counts[status] += 1
            if status == "equivalent":
                accepted.add(key)
            reason = _required_string(decision, "reason", context)
            verified_decisions.append({
                "anchor_id": anchor_id,
                "case_id": case_id,
                "location_ref": location_ref,
                "query_id": query_id,
                "rank": rank,
                "reason": reason,
                "status": status,
                "source_binding_verified": True,
                "semantic_decision_authority": REVIEW_AUTHORITY,
                "semantic_equivalence_automatically_proven": False,
            })

        exact_misses = obligations - exact_hits
        unreviewed_misses = exact_misses - reviewed_misses
        if unreviewed_misses:
            formatted = ", ".join(
                f"{case_id}/{anchor_id}"
                for case_id, anchor_id in sorted(unreviewed_misses)
            )
            raise AdjudicationError(
                f"methods.{method} has incomplete human review of exact misses: "
                f"{formatted}"
            )
        adjudicated_totals = {
            **_totals(obligations_by_case, accepted),
            "equivalent_additions": status_counts["equivalent"],
            "insufficient": status_counts["insufficient"],
        }
        _verify_declared_counts(
            _mapping(
                method_spec.get("adjudicated"),
                f"methods.{method}.adjudicated",
            ),
            adjudicated_totals,
            fields=(
                "equivalent_additions",
                "insufficient",
                "anchor_hits",
                "anchor_required",
                "full_cases",
                "eligible_cases",
            ),
            context=f"methods.{method}.adjudicated",
        )

        remaining_misses = obligations - accepted
        method_reports[method] = {
            "adjudicated": adjudicated_totals,
            "decisions": sorted(
                verified_decisions,
                key=lambda item: (
                    item["case_id"],
                    item["anchor_id"],
                    item["query_id"],
                ),
            ),
            "exact": exact_totals,
            "review": {
                "exact_miss_count": len(exact_misses),
                "remaining_miss_count": len(remaining_misses),
                "reviewed_miss_count": len(reviewed_misses),
                "unreviewed_miss_count": len(exact_misses - reviewed_misses),
            },
        }

    return {
        "schema_version": REPORT_SCHEMA_VERSION,
        "status": "source_binding_verified",
        "review_basis": review_basis,
        "review_type": review_type,
        "validation_scope": {
            "automated_checks": (
                "Frozen source hashes, dataset fingerprint, exact-hit recomputation, "
                "decision-to-ranked-location binding, declared totals, and complete "
                "review coverage of every exact miss."
            ),
            "semantic_decision_authority": REVIEW_AUTHORITY,
            "semantic_equivalence_automatically_proven": False,
            "interpretation": (
                "Equivalent and insufficient labels remain human judgments. This tool "
                "verifies their frozen source binding and accounting, not their semantics."
            ),
        },
        "sources": {
            "adjudication": _display_path(adjudication_file, root),
            "adjudication_sha256": _sha256(adjudication_file),
            "dataset_content": dataset_content,
            "dataset_manifest": dataset_manifest_value,
            "query_results": source_queries_value,
            "query_results_sha256": _sha256(source_queries_path),
            "source_report": source_report_value,
            "source_report_sha256": _sha256(source_report_path),
        },
        "workload": {
            "eligible_case_count": len(obligations_by_case),
            "query_count": len(query_rows),
            "required_anchor_obligation_count": len(obligations),
        },
        "methods": method_reports,
    }


def _index_queries(
    rows: Sequence[Mapping[str, Any]],
) -> tuple[dict[str, Mapping[str, Any]], dict[str, frozenset[str]]]:
    query_index: dict[str, Mapping[str, Any]] = {}
    obligations_by_case: dict[str, frozenset[str]] = {}
    for index, row in enumerate(rows, start=1):
        context = f"query_results line {index}"
        query_id = _required_string(row, "query_id", context)
        case_id = _required_string(row, "case_id", context)
        if query_id in query_index:
            raise AdjudicationError(f"duplicate query_id {query_id!r}")
        anchor_ids = frozenset(
            _string_list(row.get("case_required_anchor_ids"), f"{context}.case_required_anchor_ids")
        )
        if not anchor_ids:
            raise AdjudicationError(f"{context} has no required anchor obligations")
        previous = obligations_by_case.get(case_id)
        if previous is not None and previous != anchor_ids:
            raise AdjudicationError(
                f"case {case_id!r} has inconsistent required anchor obligations"
            )
        obligations_by_case[case_id] = anchor_ids
        query_index[query_id] = row
    if not query_index:
        raise AdjudicationError("source_queries contains no query rows")
    return query_index, obligations_by_case


def _exact_hits(
    method: str,
    rows: Sequence[Mapping[str, Any]],
    obligations: set[tuple[str, str]],
) -> set[tuple[str, str]]:
    hits: set[tuple[str, str]] = set()
    for row in rows:
        query_id = str(row.get("query_id") or "")
        case_id = str(row.get("case_id") or "")
        native = _native_result(row, method, query_id)
        location_refs = _string_list(
            native.get("location_refs"),
            f"query {query_id!r} method {method!r} native.location_refs",
        )
        raw_matches = _sequence(
            native.get("matched_anchor_ids_by_rank"),
            f"query {query_id!r} method {method!r} native.matched_anchor_ids_by_rank",
        )
        if len(raw_matches) != len(location_refs):
            raise AdjudicationError(
                f"query {query_id!r} method {method!r} has {len(location_refs)} "
                f"locations but {len(raw_matches)} rank match rows"
            )
        observed_anchor_ids: set[str] = set()
        for rank, raw_anchor_ids in enumerate(raw_matches, start=1):
            for anchor_id in _string_list(
                raw_anchor_ids,
                f"query {query_id!r} method {method!r} rank {rank} anchor ids",
            ):
                observed_anchor_ids.add(anchor_id)
        relevant_anchor_ids = set(
            _string_list(
                row.get("relevant_anchor_ids"),
                f"query {query_id!r} relevant_anchor_ids",
            )
        )
        for anchor_id in relevant_anchor_ids:
            key = (case_id, anchor_id)
            if key not in obligations:
                raise AdjudicationError(
                    f"query {query_id!r} scores an anchor outside the required "
                    f"obligations: {key!r}"
                )
        declared_anchor_ids = set(
            _string_list(
                native.get("hit_anchor_ids"),
                f"query {query_id!r} method {method!r} native.hit_anchor_ids",
            )
        )
        recomputed_anchor_ids = observed_anchor_ids & relevant_anchor_ids
        if recomputed_anchor_ids != declared_anchor_ids:
            raise AdjudicationError(
                f"query {query_id!r} method {method!r} hit_anchor_ids do not "
                "match the relevant matched_anchor_ids_by_rank"
            )
        hits.update((case_id, anchor_id) for anchor_id in declared_anchor_ids)
    return hits


def _totals(
    obligations_by_case: Mapping[str, frozenset[str]],
    hits: set[tuple[str, str]],
) -> dict[str, Any]:
    full_cases = sum(
        1
        for case_id, anchor_ids in obligations_by_case.items()
        if all((case_id, anchor_id) in hits for anchor_id in anchor_ids)
    )
    anchor_required = sum(len(anchor_ids) for anchor_ids in obligations_by_case.values())
    eligible_cases = len(obligations_by_case)
    return {
        "anchor_hits": len(hits),
        "anchor_recall": len(hits) / anchor_required if anchor_required else 0.0,
        "anchor_required": anchor_required,
        "eligible_cases": eligible_cases,
        "full_case_rate": full_cases / eligible_cases if eligible_cases else 0.0,
        "full_cases": full_cases,
    }


def _verify_source_report(
    report: Mapping[str, Any],
    query_path: Path,
    repo_root: Path,
) -> None:
    if report.get("schema_version") != BENCHMARK_REPORT_SCHEMA_VERSION:
        raise AdjudicationError(
            f"unsupported source report schema: {report.get('schema_version')!r}"
        )
    if report.get("status") != "complete":
        raise AdjudicationError(
            f"source report is not complete: status={report.get('status')!r}"
        )
    raw_query_artifact = _required_string(report, "raw_query_artifact", "source report")
    reported_query_path = _resolve_path(raw_query_artifact, repo_root)
    if reported_query_path != query_path.resolve():
        raise AdjudicationError(
            "source_report.raw_query_artifact does not reference source_queries: "
            f"{reported_query_path} != {query_path.resolve()}"
        )


def _verify_embedded_dataset_fingerprint(
    report: Mapping[str, Any],
    expected_sha256: str,
) -> None:
    raw_provenance = report.get("provenance")
    if raw_provenance is None:
        return
    provenance = _mapping(raw_provenance, "source report provenance")
    dataset_content = _mapping(
        provenance.get("dataset_content"),
        "source report provenance.dataset_content",
    )
    observed = _required_sha256(
        dataset_content,
        "sha256",
        "source report provenance.dataset_content",
    )
    if observed != expected_sha256:
        raise AdjudicationError(
            "source report embedded dataset fingerprint does not match the frozen "
            f"adjudication dataset: {observed} != {expected_sha256}"
        )


def _verify_preflight_counts(
    report: Mapping[str, Any],
    obligations_by_case: Mapping[str, frozenset[str]],
    obligations: set[tuple[str, str]],
) -> None:
    preflight = _mapping(report.get("preflight"), "source report preflight")
    expected = {
        "evidence_case_count": len(obligations_by_case),
        "required_anchor_obligation_count": len(obligations),
    }
    _verify_numeric_fields(preflight, expected, "source report preflight")


def _verify_source_method(
    report: Mapping[str, Any],
    method: str,
    exact_totals: Mapping[str, Any],
) -> None:
    metrics = _mapping(report.get("metrics"), "source report metrics")
    modes = _mapping(metrics.get("modes"), "source report metrics.modes")
    mode = _mapping(modes.get(method), f"source report method {method!r}")
    headline = _mapping(
        mode.get("headline_case_union_exact_anchor"),
        f"source report method {method!r} headline",
    )
    _verify_numeric_fields(headline, {
        "eligible_case_count": exact_totals["eligible_cases"],
        "full_case_count": exact_totals["full_cases"],
        "hit_count": exact_totals["anchor_hits"],
        "required_count": exact_totals["anchor_required"],
    }, f"source report method {method!r} headline")

    full_case = _mapping(
        mode.get("full_case_coverage"),
        f"source report method {method!r} full_case_coverage",
    )
    _verify_numeric_fields(full_case, {
        "anchor_hit_count": exact_totals["anchor_hits"],
        "anchor_required_count": exact_totals["anchor_required"],
        "eligible_case_count": exact_totals["eligible_cases"],
        "full_case_count": exact_totals["full_cases"],
    }, f"source report method {method!r} full_case_coverage")


def _verify_declared_counts(
    declared: Mapping[str, Any],
    recomputed: Mapping[str, Any],
    *,
    fields: Sequence[str],
    context: str,
) -> None:
    _verify_numeric_fields(
        declared,
        {field: recomputed[field] for field in fields},
        context,
    )


def _verify_numeric_fields(
    actual: Mapping[str, Any],
    expected: Mapping[str, Any],
    context: str,
) -> None:
    for field, expected_value in expected.items():
        actual_value = actual.get(field)
        if isinstance(actual_value, bool) or not isinstance(actual_value, int):
            raise AdjudicationError(f"{context}.{field} must be an integer")
        if actual_value != expected_value:
            raise AdjudicationError(
                f"{context}.{field} declares {actual_value}, "
                f"but the source artifacts recompute to {expected_value}"
            )


def dataset_content_provenance(
    manifest_path: str | Path,
    *,
    repo_root: str | Path = REPO_ROOT,
) -> dict[str, Any]:
    """Hash the manifest, authoring files, and Reading Models under review."""

    root = Path(repo_root).resolve()
    manifest_file = Path(manifest_path).resolve()
    manifest = _load_yaml(manifest_file)
    paths = {manifest_file}
    pack_paths: list[Path] = []
    for raw_path in _optional_sequence(manifest.get("paper_packs"), "paper_packs"):
        path = resolve_authoring_path(manifest_file, raw_path)
        paths.add(path)
        pack_paths.append(path)
    for raw_path in _optional_sequence(manifest.get("case_files"), "case_files"):
        paths.add(resolve_authoring_path(manifest_file, raw_path))
    for pack_path in pack_paths:
        pack = _load_yaml(pack_path)
        data_dir = _required_string(pack, "data_dir", str(pack_path))
        for index, raw_paper in enumerate(
            _optional_sequence(pack.get("papers"), f"{pack_path}.papers"),
            start=1,
        ):
            paper = _mapping(raw_paper, f"{pack_path}.papers[{index}]")
            paper_id = _required_string(
                paper,
                "id",
                f"{pack_path}.papers[{index}]",
            )
            paths.add(reading_model_path(manifest_file, data_dir, paper_id))
    records = [_file_record(path, root) for path in sorted(paths)]
    return {
        "algorithm": DATASET_FINGERPRINT_ALGORITHM,
        "sha256": _canonical_json_sha256(records),
        "file_count": len(records),
        "files": records,
    }


def _file_record(path: Path, repo_root: Path) -> dict[str, Any]:
    resolved = path.resolve()
    record: dict[str, Any] = {
        "path": _display_path(resolved, repo_root),
        "exists": resolved.is_file(),
    }
    if resolved.is_file():
        content = resolved.read_bytes()
        record.update({
            "bytes": len(content),
            "sha256": hashlib.sha256(content).hexdigest(),
        })
    return record


def _canonical_json_sha256(value: Any) -> str:
    encoded = json.dumps(
        value,
        ensure_ascii=True,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _verify_frozen_hash(path: Path, expected: str, context: str) -> None:
    observed = _sha256(path)
    if observed != expected:
        raise AdjudicationError(
            f"{context} sha256 mismatch: expected {expected}, observed {observed}"
        )


def _native_result(
    row: Mapping[str, Any],
    method: str,
    query_id: str,
) -> Mapping[str, Any]:
    modes = _mapping(row.get("modes"), f"query {query_id!r} modes")
    mode = _mapping(modes.get(method), f"query {query_id!r} method {method!r}")
    return _mapping(mode.get("native"), f"query {query_id!r} method {method!r} native")


def _load_yaml(path: Path) -> Mapping[str, Any]:
    raw = yaml.safe_load(path.read_text(encoding="utf-8"))
    return _mapping(raw, str(path))


def _load_json(path: Path) -> Mapping[str, Any]:
    raw = json.loads(path.read_text(encoding="utf-8"))
    return _mapping(raw, str(path))


def _load_jsonl(path: Path) -> list[Mapping[str, Any]]:
    rows: list[Mapping[str, Any]] = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        rows.append(_mapping(json.loads(line), f"{path}:{line_number}"))
    return rows


def _resolve_path(value: str | Path, repo_root: Path) -> Path:
    path = Path(value)
    if not path.is_absolute():
        path = repo_root / path
    return path.resolve()


def _display_path(path: Path, repo_root: Path) -> str:
    try:
        return str(path.relative_to(repo_root))
    except ValueError:
        return str(path)


def _mapping(value: Any, context: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise AdjudicationError(f"{context} must be an object")
    return value


def _sequence(value: Any, context: str) -> Sequence[Any]:
    if not isinstance(value, list):
        raise AdjudicationError(f"{context} must be a list")
    return value


def _optional_sequence(value: Any, context: str) -> Sequence[Any]:
    if value is None:
        return ()
    return _sequence(value, context)


def _string_list(value: Any, context: str) -> list[str]:
    raw = _sequence(value, context)
    result: list[str] = []
    for index, item in enumerate(raw, start=1):
        if not isinstance(item, str) or not item:
            raise AdjudicationError(f"{context}[{index}] must be a non-empty string")
        result.append(item)
    if len(result) != len(set(result)):
        raise AdjudicationError(f"{context} contains duplicate values")
    return result


def _required_string(
    mapping: Mapping[str, Any],
    field: str,
    context: str = "adjudication",
) -> str:
    value = mapping.get(field)
    if not isinstance(value, str) or not value:
        raise AdjudicationError(f"{context}.{field} must be a non-empty string")
    return value


def _required_sha256(
    mapping: Mapping[str, Any],
    field: str,
    context: str,
) -> str:
    value = _required_string(mapping, field, context).lower()
    if re.fullmatch(r"[0-9a-f]{64}", value) is None:
        raise AdjudicationError(f"{context}.{field} must be a lowercase sha256 hex digest")
    return value


def _positive_int(value: Any, context: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 1:
        raise AdjudicationError(f"{context} must be a positive integer")
    return value


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Verify Qdrant benchmark adjudication decisions against the frozen "
            "report and query-results artifact."
        )
    )
    parser.add_argument(
        "--adjudication",
        type=Path,
        default=REPO_ROOT
        / "research/golden-data/qdrant-impact-benchmark-adjudication.yaml",
    )
    parser.add_argument(
        "--out",
        type=Path,
        help="Optional JSON output path; the report is always printed to stdout.",
    )
    return parser.parse_args()


def main() -> int:
    args = _arguments()
    try:
        report = build_adjudication_report(args.adjudication)
        encoded = json.dumps(report, indent=2, sort_keys=True, ensure_ascii=True) + "\n"
        if args.out is not None:
            output_path = _resolve_path(args.out, REPO_ROOT)
            output_path.parent.mkdir(parents=True, exist_ok=True)
            output_path.write_text(encoded, encoding="utf-8")
        print(encoded, end="")
        return 0
    except (AdjudicationError, OSError, json.JSONDecodeError, yaml.YAMLError) as error:
        print(f"adjudication verification failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
