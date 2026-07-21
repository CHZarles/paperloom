from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass

from .judge import JudgeProtocolError
from .judge_model import JudgeModel
from ..core.models import JsonMap, as_list, child_map


CLAIM_JUDGE_PROMPT_VERSION = "claim-evidence-judge/v2"
CLAIM_JUDGE_CONTRACT_VERSION = "claim-evidence-semantic-judgment/v1"
CLAIM_VERDICTS = {"expressed", "contradicted", "missing", "uncertain"}


@dataclass(frozen=True)
class ClaimEvidenceJudgeVerdict:
    claims: list[JsonMap]
    additional_claims: JsonMap

    def to_dict(self) -> JsonMap:
        return {
            "claims": self.claims,
            "additional_claims": self.additional_claims,
        }


class ClaimEvidenceJudge:
    def __init__(self, model: JudgeModel, max_tokens: int = 2200):
        self.model = model
        self.max_tokens = max_tokens

    def judge(self, packet: JsonMap) -> ClaimEvidenceJudgeVerdict:
        claim_ids = [
            str(child_map(item).get("claim_id") or "")
            for item in as_list(packet.get("required_claims"))
        ]
        block_ids = [
            str(child_map(item).get("block_id") or "")
            for item in as_list(packet.get("answer_blocks"))
        ]
        if not claim_ids or any(not claim_id for claim_id in claim_ids):
            raise JudgeProtocolError("claim judge packet requires claim IDs")
        if not block_ids or any(not block_id for block_id in block_ids):
            raise JudgeProtocolError("claim judge packet requires answer block IDs")
        tool_calls = self.model.complete_judgment(
            [
                {"role": "system", "content": _system_prompt()},
                {
                    "role": "user",
                    "content": json.dumps(packet, ensure_ascii=False, sort_keys=True),
                },
            ],
            _judgment_tool(claim_ids, block_ids),
            self.max_tokens,
        )
        if len(tool_calls) != 1:
            raise JudgeProtocolError(
                "claim judge must return exactly one submit_claim_judgment tool call"
            )
        tool_call = child_map(tool_calls[0])
        if tool_call.get("name") != "submit_claim_judgment":
            raise JudgeProtocolError("claim judge returned the wrong tool")
        return _parse_verdict(child_map(tool_call.get("arguments")), claim_ids, block_ids)


def _parse_verdict(
    value: JsonMap,
    expected_claim_ids: list[str],
    expected_block_ids: list[str],
) -> ClaimEvidenceJudgeVerdict:
    claims: list[JsonMap] = []
    seen_claims: set[str] = set()
    for raw_claim in as_list(value.get("claims")):
        claim = child_map(raw_claim)
        claim_id = str(claim.get("claim_id") or "")
        verdict = str(claim.get("verdict") or "")
        matched = [str(item) for item in as_list(claim.get("matched_block_ids"))]
        if claim_id not in expected_claim_ids or claim_id in seen_claims:
            raise JudgeProtocolError(f"claim judge returned invalid claim ID: {claim_id}")
        if verdict not in CLAIM_VERDICTS:
            raise JudgeProtocolError(f"claim judge returned invalid verdict for {claim_id}")
        if len(matched) != len(set(matched)) or not set(matched) <= set(expected_block_ids):
            raise JudgeProtocolError(f"claim judge returned invalid blocks for {claim_id}")
        if len(matched) > 1:
            raise JudgeProtocolError(f"claim judge returned multiple blocks for {claim_id}")
        if (verdict == "expressed") != bool(matched):
            raise JudgeProtocolError(
                f"claim judge expressed verdict and matched blocks disagree for {claim_id}"
            )
        seen_claims.add(claim_id)
        claims.append({
            "claim_id": claim_id,
            "verdict": verdict,
            "matched_block_ids": matched,
        })
    if seen_claims != set(expected_claim_ids):
        raise JudgeProtocolError("claim judge did not return every required claim exactly once")

    raw_additional = child_map(value.get("additional_claims"))
    additional_verdict = str(raw_additional.get("verdict") or "")
    issues = [
        str(item).strip()
        for item in as_list(raw_additional.get("grounding_issues"))
        if str(item).strip()
    ]
    if additional_verdict not in {"pass", "fail", "uncertain"}:
        raise JudgeProtocolError("claim judge returned invalid additional-claims verdict")
    if additional_verdict == "fail" and not issues:
        raise JudgeProtocolError("additional-claims failure requires an issue")
    if additional_verdict == "pass" and issues:
        additional_verdict = "fail"
    return ClaimEvidenceJudgeVerdict(
        claims=claims,
        additional_claims={
            "verdict": additional_verdict,
            "grounding_issues": issues,
        },
    )


def _system_prompt() -> str:
    return """CLAIM_EVIDENCE_JUDGE
Evaluate only the supplied answer blocks, required claims, and block-attached evidence. Never use
outside knowledge. Return exactly one submit_claim_judgment tool call.

REQUIRED CLAIMS
For each required claim, judge answer meaning only. Use expressed only when at least one listed block
clearly states the complete authored meaning without changing its conclusion. Use contradicted only
for a clear incompatible conclusion, missing when absent, and uncertain when wording is genuinely
ambiguous. A merely suggested, incomplete, or inferred claim is missing, not uncertain. Evaluate
every block in isolation. For expressed, return exactly one block: the strongest self-contained
expression of the complete claim. Never return a heading, setup, adjacent context, or a block that
states only one half of the claim. Evidence quality and citation completeness do not change this
verdict.

ADDITIONAL MATERIAL
Audit every answer block, but return only one overall additional_claims verdict. Ignore the meaning
already covered by a required claim, headings, formatting, and clearly labelled opinion. Fail when any
other factual clause is absent, contradicted, overstated, partly supported, or lacks evidence attached
to that same block. Name each material defect in grounding_issues. Pass only when no such defect exists.
The required-claim exclusion is narrow: other clauses in the same block are still additional material.
Use uncertain only when support genuinely cannot be decided from the supplied text.

Be literal and conservative. Familiar facts still require attached evidence. Architecture evidence
does not prove a loss, training objective, dataset, causal mechanism, result, motivation, or tradeoff.
Do not excuse ancillary details, plausible inference, background knowledge, or a citation elsewhere.
Exact Golden wording is unnecessary, but the evidence text must support the actual clause."""


def _judgment_tool(claim_ids: list[str], block_ids: list[str]) -> JsonMap:
    return {
        "type": "function",
        "function": {
            "name": "submit_claim_judgment",
            "description": "Judge required claim expression and additional block grounding.",
            "parameters": {
                "type": "object",
                "required": ["claims", "additional_claims"],
                "properties": {
                    "claims": {
                        "type": "array",
                        "minItems": len(claim_ids),
                        "maxItems": len(claim_ids),
                        "items": {
                            "type": "object",
                            "required": ["claim_id", "verdict", "matched_block_ids"],
                            "properties": {
                                "claim_id": {"type": "string", "enum": claim_ids},
                                "verdict": {"type": "string", "enum": sorted(CLAIM_VERDICTS)},
                                "matched_block_ids": {
                                    "type": "array",
                                    "maxItems": 1,
                                    "uniqueItems": True,
                                    "items": {"type": "string", "enum": block_ids},
                                },
                            },
                            "additionalProperties": False,
                        },
                    },
                    "additional_claims": {
                        "type": "object",
                        "required": ["verdict", "grounding_issues"],
                        "properties": {
                            "verdict": {
                                "type": "string",
                                "enum": ["pass", "fail", "uncertain"],
                            },
                            "grounding_issues": {
                                "type": "array",
                                "maxItems": 8,
                                "items": {"type": "string", "maxLength": 500},
                            },
                        },
                        "additionalProperties": False,
                    },
                },
                "additionalProperties": False,
            },
        },
    }


def claim_judge_definition_sha256() -> str:
    definition = {
        "contract_version": CLAIM_JUDGE_CONTRACT_VERSION,
        "prompt_version": CLAIM_JUDGE_PROMPT_VERSION,
        "system_prompt": _system_prompt(),
        "tool": _judgment_tool(["<claim_id>"], ["<block_id>"]),
    }
    encoded = json.dumps(
        definition,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()
