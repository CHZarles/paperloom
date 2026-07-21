from __future__ import annotations

import hashlib
import json
import re
from pathlib import Path
from typing import Any

from ..core.models import GoldenDataset, JsonMap, as_list, child_map
from .claim_judge import (
    CLAIM_JUDGE_CONTRACT_VERSION,
    CLAIM_JUDGE_PROMPT_VERSION,
    claim_judge_definition_sha256,
)
from .golden_case import case_expect, case_question


SEMANTIC_JUDGMENT_SCHEMA_VERSION = "harness-semantic-judgments/v1"
_NUMERIC_CITATION = re.compile(r"(?<!\[)\[(\d+)\]")
_EVIDENCE_CITATION = re.compile(r"\[\[(ev_[A-Za-z0-9_-]+)\]\]")
_LEGACY_EVIDENCE_CITATION = re.compile(r"(?<!\[)\[(ev_[A-Za-z0-9_-]+)\](?!\])")
_LIST_ITEM = re.compile(r"^\s*(?:[-+*]|\d+[.)])\s+")
_TABLE_SEPARATOR = re.compile(r"^\s*\|?(?:\s*:?-{3,}:?\s*\|)+\s*$")


def answer_blocks(answer: JsonMap) -> tuple[list[JsonMap], list[str]]:
    markdown = _answer_body(str(answer.get("markdown") or ""))
    cited_ids = [
        str(item).strip()
        for item in as_list(answer.get("cited_evidence_ids"))
        if str(item).strip()
    ]
    raw_blocks = _markdown_blocks(markdown)
    blocks: list[JsonMap] = []
    errors: list[str] = []
    for index, raw in enumerate(raw_blocks, start=1):
        evidence_ids: list[str] = []
        for direct in [
            *_EVIDENCE_CITATION.findall(raw),
            *_LEGACY_EVIDENCE_CITATION.findall(raw),
        ]:
            if direct not in evidence_ids:
                evidence_ids.append(direct)
        for raw_number in _NUMERIC_CITATION.findall(raw):
            number = int(raw_number)
            if number < 1 or number > len(cited_ids):
                errors.append(f"CITATION_NUMBER_OUT_OF_RANGE:{number}")
                continue
            evidence_id = cited_ids[number - 1]
            if evidence_id not in evidence_ids:
                evidence_ids.append(evidence_id)
        text = _EVIDENCE_CITATION.sub("", raw)
        text = _LEGACY_EVIDENCE_CITATION.sub("", text)
        text = _NUMERIC_CITATION.sub("", text)
        text = re.sub(r"\s+", " ", text).strip(" |\t")
        if not text and not evidence_ids:
            continue
        blocks.append({
            "block_id": f"block_{index}",
            "text": text,
            "evidence_ids": evidence_ids,
        })
    return blocks, list(dict.fromkeys(errors))


def dataset_content_sha256(dataset: GoldenDataset) -> str:
    return canonical_sha256({
        "schema_version": dataset.manifest.get("schema_version"),
        "dataset_id": dataset.manifest.get("dataset_id"),
        "paper_packs": dataset.paper_packs,
        "cases": dataset.cases,
    })


def canonical_sha256(value: object) -> str:
    encoded = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def file_sha256(path: str | Path) -> str:
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_semantic_judgments(
    path: str | Path,
    dataset: GoldenDataset,
    runs: list[JsonMap] | None = None,
) -> JsonMap:
    source = Path(path)
    value = json.loads(source.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("semantic judgment report must be an object")
    if value.get("schema_version") != SEMANTIC_JUDGMENT_SCHEMA_VERSION:
        raise ValueError(
            f"unsupported semantic judgment schema: {value.get('schema_version')!r}"
        )
    if value.get("judgment_contract") != CLAIM_JUDGE_CONTRACT_VERSION:
        raise ValueError("semantic judgment report uses the wrong judgment contract")
    expected_dataset = str(dataset.manifest.get("dataset_id") or "")
    if str(value.get("dataset_id") or "") != expected_dataset:
        raise ValueError("semantic judgment dataset_id mismatch")
    if value.get("dataset_content_sha256") != dataset_content_sha256(dataset):
        raise ValueError("semantic judgment dataset content hash mismatch")
    if value.get("claim_catalog_sha256") != canonical_sha256(dataset.claims_by_id):
        raise ValueError("semantic judgment claim catalog hash mismatch")
    if runs is not None and value.get("runs_content_sha256") != canonical_sha256(runs):
        raise ValueError("semantic judgment saved-run hash mismatch")
    cases = [child_map(item) for item in as_list(value.get("cases"))]
    case_ids = [str(item.get("case_id") or "") for item in cases]
    if not case_ids or any(not case_id for case_id in case_ids):
        raise ValueError("semantic judgments require case IDs")
    if len(case_ids) != len(set(case_ids)):
        raise ValueError("semantic judgments contain duplicate case IDs")
    expected_case_ids = {str(case.get("id") or "") for case in dataset.cases}
    if set(case_ids) != expected_case_ids:
        raise ValueError("semantic judgments do not cover the dataset cases exactly")
    normalized_cases: list[JsonMap] = []
    cases_by_dataset_id = {str(case.get("id") or ""): case for case in dataset.cases}
    for item in cases:
        case_id = str(item.get("case_id") or "")
        claim_items = [child_map(claim) for claim in as_list(item.get("claims"))]
        claim_ids = [str(claim.get("claim_id") or "") for claim in claim_items]
        expected_claim_ids = {
            str(claim_id)
            for claim_id in as_list(
                case_expect(cases_by_dataset_id[case_id]).get("required_claims")
            )
        }
        if set(claim_ids) != expected_claim_ids or len(claim_ids) != len(set(claim_ids)):
            raise ValueError(f"semantic judgments have invalid claim coverage for {case_id}")
        for claim in claim_items:
            verdict = str(claim.get("verdict") or "")
            if verdict not in {"expressed", "contradicted", "missing", "uncertain"}:
                raise ValueError(f"semantic judgment has invalid verdict for {case_id}")
            if not isinstance(claim.get("matched_block_ids"), list):
                raise ValueError(f"semantic judgment requires matched block IDs for {case_id}")
        additional = child_map(item.get("additional_claims"))
        if additional.get("verdict") not in {"pass", "fail", "uncertain"}:
            raise ValueError(f"semantic judgment has invalid additional-claim verdict for {case_id}")
        normalized_cases.append({
            **item,
            "claims_by_id": {
                str(claim["claim_id"]): claim
                for claim in claim_items
            },
        })
    prompt_version = str(value.get("prompt_version") or "")
    if child_map(value.get("judge")).get("definition_sha256") != claim_judge_definition_sha256():
        raise ValueError("semantic judgment report uses a stale judge definition")
    identity = judge_identity(child_map(value.get("judge")), prompt_version)
    return {
        **value,
        "source_path": str(source),
        "sha256": file_sha256(source),
        "judge_identity": identity,
        "cases_by_id": {
            str(item["case_id"]): item
            for item in normalized_cases
        },
    }


def load_judge_gate(calibration_path: str | Path, holdout_path: str | Path) -> JsonMap:
    sources = []
    identities: list[JsonMap] = []
    dataset_ids: list[str] = []
    for label, raw_path in (("calibration", calibration_path), ("holdout", holdout_path)):
        path = Path(raw_path)
        report = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(report, dict) or report.get("accepted") is not True:
            raise ValueError(f"semantic judge {label} gate is not accepted: {path}")
        if report.get("judgment_contract") != CLAIM_JUDGE_CONTRACT_VERSION:
            raise ValueError(f"semantic judge {label} gate uses the wrong contract: {path}")
        if int(report.get("technical_error_count") or 0) != 0:
            raise ValueError(f"semantic judge {label} gate has technical errors: {path}")
        if int(report.get("false_pass_count") or 0) != 0:
            raise ValueError(f"semantic judge {label} gate has false passes: {path}")
        source_sha256 = str(report.get("source_sha256") or "")
        if not re.fullmatch(r"[0-9a-f]{64}", source_sha256):
            raise ValueError(f"semantic judge {label} gate lacks a frozen source hash: {path}")
        dataset_ids.append(str(report.get("dataset_id") or ""))
        judge = child_map(report.get("judge"))
        if judge.get("definition_sha256") != claim_judge_definition_sha256():
            raise ValueError(f"semantic judge {label} gate uses a stale judge definition: {path}")
        identity = judge_identity(judge, str(judge.get("prompt_version") or ""))
        if not all(identity.values()):
            raise ValueError(f"semantic judge {label} gate has incomplete judge identity: {path}")
        identities.append(identity)
        sources.append({
            "kind": label,
            "path": str(path),
            "sha256": file_sha256(path),
            "source_path": report.get("source_path"),
            "source_sha256": source_sha256,
            "judgment_contract": report.get("judgment_contract"),
            "judge": judge,
        })
    if identities[0] != identities[1]:
        raise ValueError("calibration and holdout gates use different judge models or prompts")
    if not dataset_ids[0] or dataset_ids[0] != dataset_ids[1]:
        raise ValueError("calibration and holdout gates use different datasets")
    return {
        "accepted": True,
        "judge_identity": identities[0],
        "sources": sources,
    }


def judge_identity(judge: JsonMap, prompt_version: str) -> JsonMap:
    return {
        "provider": str(judge.get("provider") or ""),
        "model": str(judge.get("model") or ""),
        "prompt_version": prompt_version,
        "definition_sha256": str(judge.get("definition_sha256") or ""),
    }


def generate_semantic_judgments(
    dataset: GoldenDataset,
    runs: list[JsonMap],
    judge: Any,
    *,
    judge_metadata: JsonMap,
    prompt_version: str,
) -> JsonMap:
    if prompt_version != CLAIM_JUDGE_PROMPT_VERSION:
        raise ValueError("semantic judgment generation requires the current claim-judge prompt")
    runs_by_case = {
        str(run.get("case_id") or run.get("question_id")): run
        for run in runs
    }
    reports: list[JsonMap] = []
    technical_error_count = 0
    for case in dataset.cases:
        case_id = str(case.get("id") or "")
        run = child_map(runs_by_case.get(case_id))
        packet = build_claim_judge_packet(dataset, case, run)
        try:
            verdict = judge.judge(packet).to_dict()
            report = {
                "case_id": case_id,
                **verdict,
            }
        except Exception as error:
            technical_error_count += 1
            report = {
                "case_id": case_id,
                "claims": [
                    {
                        "claim_id": str(claim_id),
                        "verdict": "uncertain",
                        "matched_block_ids": [],
                    }
                    for claim_id in as_list(case_expect(case).get("required_claims"))
                ],
                "additional_claims": {
                    "verdict": "uncertain",
                    "grounding_issues": [],
                    "technical_error": {
                        "error_type": type(error).__name__,
                        "error": str(error),
                    },
                },
            }
        reports.append(report)

    metadata = dict(judge_metadata)
    metadata["prompt_version"] = prompt_version
    metadata["definition_sha256"] = claim_judge_definition_sha256()
    return {
        "schema_version": SEMANTIC_JUDGMENT_SCHEMA_VERSION,
        "judgment_contract": CLAIM_JUDGE_CONTRACT_VERSION,
        "dataset_id": dataset.manifest.get("dataset_id"),
        "dataset_content_sha256": dataset_content_sha256(dataset),
        "claim_catalog_sha256": canonical_sha256(dataset.claims_by_id),
        "runs_content_sha256": canonical_sha256(runs),
        "judge": metadata,
        "prompt_version": prompt_version,
        "technical_error_count": technical_error_count,
        "cases": reports,
    }


def build_claim_judge_packet(
    dataset: GoldenDataset,
    case: JsonMap,
    run: JsonMap,
) -> JsonMap:
    answer = child_map(run.get("research_answer"))
    blocks, block_errors = answer_blocks(answer)
    evidence_by_id = {
        str(item.get("evidence_id")): item
        for raw_item in as_list(child_map(run.get("evidence_ledger")).get("items"))
        if (item := child_map(raw_item)).get("evidence_id")
    }
    return {
        "contract_version": "claim-evidence-semantic-judgment/v1",
        "case_id": str(case.get("id") or ""),
        "user_request": case_question(case),
        "required_claims": [
            {
                "claim_id": str(claim_id),
                "statement": str(
                    dataset.claims_by_id[str(claim_id)].get("statement") or ""
                ),
            }
            for claim_id in as_list(case_expect(case).get("required_claims"))
        ],
        "answer_outcome": answer.get("outcome"),
        "answer_blocks": [
            {
                "block_id": block.get("block_id"),
                "text": block.get("text"),
                "evidence": [
                    _judge_evidence(evidence_by_id[evidence_id])
                    for evidence_id in as_list(block.get("evidence_ids"))
                    if evidence_id in evidence_by_id
                ],
            }
            for block in blocks
        ],
        "citation_integrity_errors": [
            *block_errors,
            *[
                f"UNKNOWN_CITED_EVIDENCE:{evidence_id}"
                for block in blocks
                for evidence_id in as_list(block.get("evidence_ids"))
                if evidence_id not in evidence_by_id
            ],
        ],
    }


def _judge_evidence(item: JsonMap) -> JsonMap:
    return {
        key: item.get(key)
        for key in (
            "evidence_id",
            "paper_id",
            "title",
            "section",
            "page",
            "location_ref",
            "element_type",
            "span_text",
        )
        if item.get(key) is not None
    }


def _answer_body(markdown: str) -> str:
    marker = re.search(r"(?:^|\n)Sources\s*\n", markdown)
    return markdown[:marker.start()].rstrip() if marker else markdown


def _markdown_blocks(markdown: str) -> list[str]:
    blocks: list[str] = []
    paragraph: list[str] = []

    def flush() -> None:
        if paragraph:
            blocks.append("\n".join(paragraph).strip())
            paragraph.clear()

    for line in markdown.splitlines():
        stripped = line.strip()
        if not stripped:
            flush()
            continue
        if _TABLE_SEPARATOR.match(stripped):
            flush()
            continue
        if _LIST_ITEM.match(line) or ("|" in stripped and stripped.startswith("|")):
            flush()
            blocks.append(stripped)
            continue
        if stripped.startswith("#"):
            flush()
            blocks.append(stripped.lstrip("#").strip())
            continue
        paragraph.append(stripped)
    flush()
    return blocks
