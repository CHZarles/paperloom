from __future__ import annotations

from typing import Protocol

from .claim_evidence import canonical_sha256
from ..core.models import GoldenDataset, JsonMap, as_list, child_map


CLAIM_LOCATION_AUDIT_SCHEMA_VERSION = "harness-claim-location-audit/v1"
_EXPECTED_TYPES = {
    "page_ref_": {"page"},
    "section_ref_": {"section"},
    "table_ref_": {"table"},
    "figure_ref_": {"figure", "chart"},
}


class LocationReader(Protocol):
    def read_locations(self, arguments: JsonMap) -> JsonMap:
        ...


def audit_claim_locations(
    dataset: GoldenDataset,
    reader: LocationReader,
    *,
    batch_size: int = 20,
) -> JsonMap:
    expected_by_ref: dict[str, str] = {}
    references: list[JsonMap] = []
    for claim_id, claim in dataset.claims_by_id.items():
        for raw_requirement in as_list(claim.get("required_evidence")):
            requirement = child_map(raw_requirement)
            paper_id = str(requirement.get("paper_id") or "")
            for raw_location in as_list(requirement.get("accepted_locations")):
                location_ref = str(raw_location)
                previous = expected_by_ref.setdefault(location_ref, paper_id)
                if previous != paper_id:
                    raise ValueError(
                        f"accepted location {location_ref} is assigned to multiple papers"
                    )
                references.append({
                    "claim_id": claim_id,
                    "paper_id": paper_id,
                    "location_ref": location_ref,
                })

    returned_by_ref: dict[str, JsonMap] = {}
    missing: set[str] = set()
    location_refs = sorted(expected_by_ref)
    for offset in range(0, len(location_refs), batch_size):
        batch = location_refs[offset:offset + batch_size]
        response = reader.read_locations({"location_refs": batch})
        missing.update(str(item) for item in as_list(response.get("missing_location_refs")))
        for raw_item in as_list(response.get("items")):
            item = child_map(raw_item)
            location_ref = str(item.get("location_ref") or item.get("location") or "")
            if location_ref in returned_by_ref:
                raise ValueError(f"product corpus returned duplicate location {location_ref}")
            returned_by_ref[location_ref] = item

    rows: list[JsonMap] = []
    for reference in references:
        location_ref = str(reference["location_ref"])
        expected_paper = str(reference["paper_id"])
        item = returned_by_ref.get(location_ref)
        errors: list[str] = []
        if item is None or location_ref in missing:
            errors.append("LOCATION_NOT_RESOLVED")
        else:
            actual_paper = str(item.get("paper_id") or "")
            if actual_paper != expected_paper:
                errors.append(
                    f"LOCATION_PAPER_MISMATCH:expected={expected_paper}:actual={actual_paper}"
                )
            expected_types = next(
                (types for prefix, types in _EXPECTED_TYPES.items() if location_ref.startswith(prefix)),
                set(),
            )
            element_type = str(item.get("element_type") or "").lower()
            if element_type not in expected_types:
                errors.append(
                    f"LOCATION_TYPE_MISMATCH:expected={sorted(expected_types)}:actual={element_type}"
                )
            if not str(item.get("span_text") or "").strip():
                errors.append("LOCATION_TEXT_EMPTY")
        rows.append({
            **reference,
            "status": "fail" if errors else "pass",
            "errors": errors,
            "element_type": item.get("element_type") if item else None,
            "text_sha256": (
                canonical_sha256(str(item.get("span_text") or ""))
                if item else None
            ),
        })

    failed = sum(row["status"] == "fail" for row in rows)
    return {
        "schema_version": CLAIM_LOCATION_AUDIT_SCHEMA_VERSION,
        "dataset_id": dataset.manifest.get("dataset_id"),
        "claim_catalog_sha256": canonical_sha256(dataset.claims_by_id),
        "claim_count": len(dataset.claims_by_id),
        "reference_count": len(rows),
        "unique_location_count": len(location_refs),
        "passed_count": len(rows) - failed,
        "failed_count": failed,
        "locations": rows,
    }
