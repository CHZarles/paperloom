from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml

from .claim_evidence import (
    build_claim_judge_packet,
    canonical_sha256,
    dataset_content_sha256,
    file_sha256,
)
from .claim_judge import (
    CLAIM_JUDGE_CONTRACT_VERSION,
    CLAIM_JUDGE_PROMPT_VERSION,
    claim_judge_definition_sha256,
)
from .dataset import load_dataset
from .golden_case import case_expect
from .scoring import deterministic_claim_blocks
from ..core.models import GoldenDataset, JsonMap, as_list, child_map


CLAIM_JUDGE_LABEL_SCHEMA_VERSION = "harness-claim-judge-labels/v1"
CLAIM_JUDGE_AGREEMENT_SCHEMA_VERSION = "harness-claim-judge-agreement/v1"


@dataclass(frozen=True)
class ClaimJudgeLabel:
    case_id: str
    run_id: str
    packet: JsonMap
    expected_claims: dict[str, JsonMap]
    semantic_claim_ids: frozenset[str]
    expected_additional: str
    note: str
    artifact_hashes: JsonMap


@dataclass(frozen=True)
class ClaimJudgeLabelSet:
    dataset: GoldenDataset
    source_path: str
    source_sha256: str
    labels: list[ClaimJudgeLabel]


def load_claim_judge_labels(
    path: str | Path,
    repo_root: str | Path | None = None,
) -> ClaimJudgeLabelSet:
    source = Path(path).resolve()
    root = Path(repo_root).resolve() if repo_root else Path.cwd().resolve()
    document = _load_yaml(source)
    if document.get("schema_version") != CLAIM_JUDGE_LABEL_SCHEMA_VERSION:
        raise ValueError(f"unsupported claim-judge label schema: {document.get('schema_version')!r}")
    manifest = _resolve(root, document.get("manifest"), "manifest")
    dataset = load_dataset(manifest, repo_root=root)
    if document.get("dataset_id") != dataset.manifest.get("dataset_id"):
        raise ValueError("claim-judge label dataset_id mismatch")
    cases_by_id = {str(case.get("id") or ""): case for case in dataset.cases}

    labels: list[ClaimJudgeLabel] = []
    artifact_inventory: list[JsonMap] = []
    seen: set[str] = set()
    for raw_label in as_list(document.get("labels")):
        label = child_map(raw_label)
        case_id = str(label.get("case_id") or "")
        if not case_id or case_id in seen or case_id not in cases_by_id:
            raise ValueError(f"invalid or duplicate claim-judge label case: {case_id!r}")
        seen.add(case_id)
        run_id = str(label.get("run_id") or "")
        if not run_id:
            raise ValueError(f"claim-judge label {case_id} requires run_id")
        answer_path = _resolve(root, label.get("answer_path"), f"{case_id} answer")
        evidence_path = _resolve(root, label.get("evidence_path"), f"{case_id} evidence")
        answer = _load_json(answer_path)
        ledger = _load_json(evidence_path)
        run = {
            "run_id": run_id,
            "case_id": case_id,
            "research_answer": answer,
            "evidence_ledger": ledger,
        }
        case = cases_by_id[case_id]
        packet = build_claim_judge_packet(dataset, case, run)
        block_ids = {
            str(child_map(block).get("block_id") or "")
            for block in as_list(packet.get("answer_blocks"))
        }
        raw_claims = child_map(label.get("claims"))
        expected_claim_ids = {
            str(claim_id)
            for claim_id in as_list(case_expect(case).get("required_claims"))
        }
        if set(raw_claims) != expected_claim_ids:
            raise ValueError(f"claim-judge label {case_id} has wrong claim coverage")
        expected_claims: dict[str, JsonMap] = {}
        for claim_id, raw_expected in raw_claims.items():
            expected = child_map(raw_expected)
            verdict = str(expected.get("verdict") or "")
            matched = [str(item) for item in as_list(expected.get("matched_block_ids"))]
            if verdict not in {"expressed", "contradicted", "missing", "uncertain"}:
                raise ValueError(f"claim-judge label {case_id}.{claim_id} has invalid verdict")
            if (verdict == "expressed") != bool(matched) or not set(matched) <= block_ids:
                raise ValueError(f"claim-judge label {case_id}.{claim_id} has invalid blocks")
            expected_claims[str(claim_id)] = {
                "verdict": verdict,
                "matched_block_ids": matched,
            }
        additional = str(label.get("additional_claims") or "")
        if additional not in {"pass", "fail"}:
            raise ValueError(f"claim-judge label {case_id} has invalid additional verdict")
        note = str(label.get("note") or "").strip()
        if not note:
            raise ValueError(f"claim-judge label {case_id} requires a note")
        hashes = {
            "answer_path": str(answer_path),
            "answer_sha256": file_sha256(answer_path),
            "evidence_path": str(evidence_path),
            "evidence_sha256": file_sha256(evidence_path),
        }
        artifact_inventory.append({"case_id": case_id, **hashes})
        labels.append(ClaimJudgeLabel(
            case_id=case_id,
            run_id=run_id,
            packet=packet,
            expected_claims=expected_claims,
            semantic_claim_ids=frozenset(
                claim_id
                for claim_id in expected_claims
                if not deterministic_claim_blocks(
                    case,
                    dataset.claims_by_id[claim_id],
                    [child_map(block) for block in as_list(packet.get("answer_blocks"))],
                )
            ),
            expected_additional=additional,
            note=note,
            artifact_hashes=hashes,
        ))
    if not labels:
        raise ValueError("claim-judge label set is empty")
    source_sha256 = canonical_sha256({
        "labels_sha256": file_sha256(source),
        "dataset_content_sha256": dataset_content_sha256(dataset),
        "claim_catalog_sha256": canonical_sha256(dataset.claims_by_id),
        "artifacts": artifact_inventory,
    })
    return ClaimJudgeLabelSet(dataset, str(source), source_sha256, labels)


def evaluate_claim_judge_calibration(
    label_set: ClaimJudgeLabelSet,
    judge: Any,
    *,
    judge_metadata: JsonMap | None = None,
) -> JsonMap:
    cases: list[JsonMap] = []
    full_agreement = 0
    false_passes = 0
    technical_errors = 0
    for label in label_set.labels:
        try:
            actual = judge.judge(label.packet).to_dict()
            actual_claims = {
                str(child_map(item).get("claim_id") or ""): child_map(item)
                for item in as_list(actual.get("claims"))
            }
            claim_matches = {
                claim_id: (
                    _semantic_claim_match(expected, child_map(actual_claims.get(claim_id)))
                    if claim_id in label.semantic_claim_ids
                    else None
                )
                for claim_id, expected in label.expected_claims.items()
            }
            actual_additional = str(
                child_map(actual.get("additional_claims")).get("verdict") or ""
            )
            additional_match = actual_additional == label.expected_additional
            full_match = all(
                matched is not False
                for matched in claim_matches.values()
            ) and additional_match
            full_agreement += int(full_match)
            false_pass = any(
                _semantic_claim_false_pass(
                    expected,
                    child_map(actual_claims.get(claim_id)),
                )
                for claim_id, expected in label.expected_claims.items()
                if claim_id in label.semantic_claim_ids
            ) or (
                label.expected_additional == "fail"
                and actual_additional == "pass"
            )
            false_passes += int(false_pass)
            cases.append({
                "case_id": label.case_id,
                "run_id": label.run_id,
                "human": {
                    "claims": label.expected_claims,
                    "additional_claims": label.expected_additional,
                    "note": label.note,
                },
                "judge": actual,
                "claim_matches": claim_matches,
                "claim_scopes": {
                    claim_id: (
                        "semantic" if claim_id in label.semantic_claim_ids else "deterministic"
                    )
                    for claim_id in label.expected_claims
                },
                "additional_claims_match": additional_match,
                "full_match": full_match,
                "false_pass": false_pass,
                "artifact_hashes": label.artifact_hashes,
            })
        except Exception as error:
            technical_errors += 1
            cases.append({
                "case_id": label.case_id,
                "run_id": label.run_id,
                "human": {
                    "claims": label.expected_claims,
                    "additional_claims": label.expected_additional,
                    "note": label.note,
                },
                "judge": {
                    "status": "technical_error",
                    "error_type": type(error).__name__,
                    "error": str(error),
                },
                "full_match": False,
                "false_pass": False,
                "artifact_hashes": label.artifact_hashes,
            })
    count = len(label_set.labels)
    metadata = dict(judge_metadata or {})
    metadata["prompt_version"] = CLAIM_JUDGE_PROMPT_VERSION
    metadata["definition_sha256"] = claim_judge_definition_sha256()
    return {
        "schema_version": CLAIM_JUDGE_AGREEMENT_SCHEMA_VERSION,
        "judgment_contract": CLAIM_JUDGE_CONTRACT_VERSION,
        "dataset_id": label_set.dataset.manifest.get("dataset_id"),
        "dataset_content_sha256": dataset_content_sha256(label_set.dataset),
        "claim_catalog_sha256": canonical_sha256(label_set.dataset.claims_by_id),
        "source_path": label_set.source_path,
        "source_sha256": label_set.source_sha256,
        "judge": metadata,
        "case_count": count,
        "semantic_claim_count": sum(
            len(label.semantic_claim_ids)
            for label in label_set.labels
        ),
        "full_agreement_count": full_agreement,
        "disagreement_count": count - full_agreement,
        "false_pass_count": false_passes,
        "technical_error_count": technical_errors,
        "accepted": (
            count > 0
            and full_agreement == count
            and false_passes == 0
            and technical_errors == 0
        ),
        "cases": cases,
    }


def _semantic_claim_match(expected: JsonMap, actual: JsonMap) -> bool:
    expected_verdict = str(expected.get("verdict") or "")
    actual_verdict = str(actual.get("verdict") or "")
    if actual_verdict != expected_verdict:
        return False
    expected_blocks = set(as_list(expected.get("matched_block_ids")))
    actual_blocks = set(as_list(actual.get("matched_block_ids")))
    if expected_verdict != "expressed":
        return not actual_blocks
    # Human labels enumerate every block that is safe to use for deterministic grounding.
    # The judge needs one safe expression, not exhaustive recall of repeated answer wording.
    return bool(actual_blocks) and actual_blocks <= expected_blocks


def _semantic_claim_false_pass(expected: JsonMap, actual: JsonMap) -> bool:
    if str(actual.get("verdict") or "") != "expressed":
        return False
    if str(expected.get("verdict") or "") != "expressed":
        return True
    allowed_blocks = set(as_list(expected.get("matched_block_ids")))
    actual_blocks = set(as_list(actual.get("matched_block_ids")))
    return not actual_blocks or not actual_blocks <= allowed_blocks


def _resolve(root: Path, raw_path: Any, label: str) -> Path:
    if not raw_path:
        raise ValueError(f"claim-judge labels require {label} path")
    path = Path(str(raw_path))
    if not path.is_absolute():
        path = root / path
    path = path.resolve()
    if not path.is_file():
        raise ValueError(f"claim-judge {label} file is missing: {path}")
    return path


def _load_yaml(path: Path) -> JsonMap:
    value = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    if not isinstance(value, dict):
        raise ValueError(f"claim-judge labels must be a mapping: {path}")
    return value


def _load_json(path: Path) -> JsonMap:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"claim-judge artifact must be an object: {path}")
    return value
