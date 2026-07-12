from __future__ import annotations

from dataclasses import dataclass, field
from .models import JsonMap, as_list, child_map
from .status import execution_status_error, research_outcome_error


@dataclass(frozen=True)
class ContractValidation:
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    @property
    def passed(self) -> bool:
        return not self.errors


class ArtifactContractValidator:
    """Generic structural validator for the trace artifact contract YAML."""

    def __init__(self, contracts: JsonMap):
        self.contracts = contracts
        self.artifacts = {
            str(artifact.get("id")): artifact
            for artifact in as_list(contracts.get("artifacts"))
            if isinstance(artifact, dict) and artifact.get("id")
        }
        self.enums = child_map(contracts.get("shared_enums"))

    def validate_run(self, run: JsonMap) -> ContractValidation:
        errors: list[str] = []
        warnings: list[str] = []
        self._validate_required_fields("HarnessRun", run, errors)
        artifact_values = {
            "EvidenceLedger": run.get("evidence_ledger"),
            "ResearchAnswer": run.get("research_answer"),
        }
        for artifact_id, value in artifact_values.items():
            self._validate_required_fields(artifact_id, child_map(value), errors)

        self._validate_evidence_items(child_map(run.get("evidence_ledger")), errors, warnings)
        self._validate_execution_statuses(run, errors)
        self._validate_answer_outcome(child_map(run.get("research_answer")), errors)
        return ContractValidation(errors=errors, warnings=warnings)

    def _validate_required_fields(self, artifact_id: str, value: JsonMap, errors: list[str], suffix: str = "") -> None:
        contract = self.artifacts.get(artifact_id)
        if not contract:
            errors.append(f"contract missing for {artifact_id}")
            return
        if not isinstance(value, dict):
            errors.append(f"{artifact_id}{suffix} must be an object")
            return
        for field_name in as_list(contract.get("required_fields")):
            if (
                artifact_id == "ResearchAnswer"
                and field_name == "outcome"
                and value.get("status") == "FAILED_TECHNICAL"
            ):
                continue
            if not _has_field(value, str(field_name)):
                errors.append(f"{artifact_id}{suffix}.{field_name} missing")

    def _validate_answer_outcome(self, answer: JsonMap, errors: list[str]) -> None:
        error = research_outcome_error(
            answer.get("status"),
            answer.get("outcome"),
            outcome_present="outcome" in answer,
        )
        if error:
            errors.append(f"ResearchAnswer.outcome invalid: {error}")

    def _validate_execution_statuses(self, run: JsonMap, errors: list[str]) -> None:
        answer = child_map(run.get("research_answer"))
        result_status = (
            {"result_status": run.get("result_status")}
            if "result_status" in run else
            {}
        )
        error = execution_status_error(run.get("status"), answer.get("status"), **result_status)
        if error:
            errors.append(f"HarnessRun execution status invalid: {error}")

    def _validate_evidence_items(self, ledger: JsonMap, errors: list[str], warnings: list[str]) -> None:
        contract = self.artifacts.get("EvidenceLedger", {})
        for bucket in ("items", "rejected_items"):
            for index, item in enumerate(as_list(ledger.get(bucket))):
                item_map = child_map(item)
                for field_name in as_list(contract.get("item_required_fields")):
                    if field_name == "bbox_or_cell_ref":
                        continue
                    if not _has_field(item_map, str(field_name)):
                        errors.append(f"EvidenceLedger.{bucket}[{index}].{field_name} missing")
                if item_map.get("evidence_quality") not in {"verified", "rejected", "metadata_verified"}:
                    warnings.append(f"EvidenceLedger.{bucket}[{index}].evidence_quality is nonstandard")

def _has_field(value: JsonMap, field_name: str) -> bool:
    return field_name in value and value.get(field_name) is not None
