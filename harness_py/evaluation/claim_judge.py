from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass

from .judge import JudgeProtocolError
from .judge_model import JudgeModel
from ..core.models import JsonMap, as_list, child_map


CLAIM_JUDGE_PROMPT_VERSION = "claim-evidence-judge/v3"
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
    """Run narrow model judgments, then combine them under the v4 contract."""

    def __init__(self, model: JudgeModel, max_tokens: int = 2200):
        self.model = model
        self.max_tokens = max_tokens

    def judge(self, packet: JsonMap) -> ClaimEvidenceJudgeVerdict:
        claims = [child_map(item) for item in as_list(packet.get("required_claims"))]
        blocks = [child_map(item) for item in as_list(packet.get("answer_blocks"))]
        claim_ids = [str(item.get("claim_id") or "") for item in claims]
        block_ids = [str(item.get("block_id") or "") for item in blocks]
        if not claim_ids or any(not claim_id for claim_id in claim_ids):
            raise JudgeProtocolError("claim judge packet requires claim IDs")
        if len(claim_ids) != len(set(claim_ids)):
            raise JudgeProtocolError("claim judge packet contains duplicate claim IDs")
        if not block_ids or any(not block_id for block_id in block_ids):
            raise JudgeProtocolError("claim judge packet requires answer block IDs")
        if len(block_ids) != len(set(block_ids)):
            raise JudgeProtocolError("claim judge packet contains duplicate answer block IDs")

        text_blocks = [
            {"block_id": block["block_id"], "text": str(block.get("text") or "")}
            for block in blocks
        ]
        claim_results = [
            self._judge_claim(packet, claim, text_blocks, block_ids)
            for claim in claims
        ]
        additional = self._judge_additional(packet, claim_results)
        return ClaimEvidenceJudgeVerdict(claim_results, additional)

    def _judge_claim(
        self,
        packet: JsonMap,
        claim: JsonMap,
        text_blocks: list[JsonMap],
        block_ids: list[str],
    ) -> JsonMap:
        claim_id = str(claim.get("claim_id") or "")
        request = {
            "contract_version": CLAIM_JUDGE_CONTRACT_VERSION,
            "case_id": packet.get("case_id"),
            "user_request": packet.get("user_request"),
            "required_claim": {
                "claim_id": claim_id,
                "statement": str(claim.get("statement") or ""),
            },
            "answer_outcome": packet.get("answer_outcome"),
            "answer_blocks": text_blocks,
        }
        calls = self.model.complete_judgment(
            [
                {"role": "system", "content": _claim_prompt()},
                {"role": "user", "content": _json(request)},
            ],
            _claim_tool(claim_id, block_ids),
            self.max_tokens,
        )
        arguments = _one_tool_arguments(calls, "submit_claim_match")
        return _parse_claim(arguments, claim_id, block_ids)

    def _judge_additional(self, packet: JsonMap, claims: list[JsonMap]) -> JsonMap:
        if _contains_only_exact_required_claims(packet, claims):
            return {"verdict": "pass", "grounding_issues": []}
        request = {
            **packet,
            "matched_required_claims": claims,
        }
        calls = self.model.complete_judgment(
            [
                {"role": "system", "content": _additional_prompt()},
                {"role": "user", "content": _json(request)},
            ],
            _additional_tool(),
            self.max_tokens,
        )
        arguments = _one_tool_arguments(calls, "submit_additional_grounding")
        return _parse_additional(arguments)


def _one_tool_arguments(calls: list[JsonMap], expected_name: str) -> JsonMap:
    if len(calls) != 1:
        raise JudgeProtocolError(f"claim judge must return exactly one {expected_name} call")
    call = child_map(calls[0])
    if call.get("name") != expected_name:
        raise JudgeProtocolError(f"claim judge returned the wrong tool for {expected_name}")
    return child_map(call.get("arguments"))


def _parse_claim(value: JsonMap, expected_claim_id: str, block_ids: list[str]) -> JsonMap:
    claim_id = str(value.get("claim_id") or "")
    verdict = str(value.get("verdict") or "")
    matched = [str(item) for item in as_list(value.get("matched_block_ids"))]
    if claim_id != expected_claim_id:
        raise JudgeProtocolError(f"claim judge returned invalid claim ID: {claim_id}")
    if verdict not in CLAIM_VERDICTS:
        raise JudgeProtocolError(f"claim judge returned invalid verdict for {claim_id}")
    if len(matched) != len(set(matched)) or not set(matched) <= set(block_ids):
        raise JudgeProtocolError(f"claim judge returned invalid blocks for {claim_id}")
    if len(matched) > 1:
        raise JudgeProtocolError(f"claim judge returned multiple blocks for {claim_id}")
    if verdict == "expressed" and not matched:
        verdict = "missing"
    elif verdict != "expressed" and matched:
        matched = []
    return {
        "claim_id": claim_id,
        "verdict": verdict,
        "matched_block_ids": matched,
    }


def _parse_additional(value: JsonMap) -> JsonMap:
    verdict = str(value.get("verdict") or "")
    issues = [
        str(item).strip()
        for item in as_list(value.get("grounding_issues"))
        if str(item).strip()
    ]
    if verdict not in {"pass", "fail", "uncertain"}:
        raise JudgeProtocolError("claim judge returned invalid additional-claims verdict")
    if verdict == "fail" and not issues:
        raise JudgeProtocolError("additional-claims failure requires an issue")
    if verdict == "pass" and issues:
        verdict = "fail"
    return {"verdict": verdict, "grounding_issues": issues}


def _claim_prompt() -> str:
    return """CLAIM_MATCH_JUDGE
Evaluate only whether one answer block textually expresses the complete required claim. Never use
outside knowledge or attached evidence. Return exactly one submit_claim_match tool call.

Use expressed only when one block, read in isolation, makes the entire authored claim true without
adding an actor, action, object, comparison side, mechanism, or qualifier by inference. Return the
single strongest self-contained block. A heading, setup, adjacent block, topical overlap, partial
statement, or plausible implication is missing. Use contradicted only for a clear incompatible
conclusion. A required claim that rejects a comparison or ranking is contradicted when the answer
explicitly makes that comparison or ranking. Use uncertain only when a block's own wording genuinely
has two meanings.

Relation direction matters. A block about how tasks or reference solutions were constructed does not
state how generated outputs are evaluated. A block saying a reset is needed does not state that a
system provides a mechanism that restores an original state. A block describing one side of a
comparison does not express both sides. Exact wording is unnecessary; complete meaning is mandatory."""


def _additional_prompt() -> str:
    return """ADDITIONAL_GROUNDING_JUDGE
Evaluate only the supplied answer blocks, their attached evidence, and matched required claims. Never
use outside knowledge. Return exactly one submit_additional_grounding tool call.

Audit every answer block and return one overall verdict. Ignore only the precise meaning of a matched
required claim, headings, formatting, and clearly labelled opinion. Every other factual clause must be
directly supported by evidence attached to that same block. Fail for an absent, contradicted,
overstated, partly supported, or uncited clause and name each material defect in grounding_issues.
Other clauses in a required-claim block are still additional material. Pass only when no defect exists.
Do not fail merely because a required claim is missing; additional grounding covers only factual
material the answer actually asserts. Use uncertain only when support genuinely cannot be decided
from the supplied text. Familiar facts, plausible inference, raw citation-like text, and evidence
attached elsewhere do not count."""


def _claim_tool(claim_id: str, block_ids: list[str]) -> JsonMap:
    return {
        "type": "function",
        "function": {
            "name": "submit_claim_match",
            "description": "Judge one required claim and select its strongest complete block.",
            "parameters": {
                "type": "object",
                "required": ["claim_id", "verdict", "matched_block_ids"],
                "properties": {
                    "claim_id": {"type": "string", "enum": [claim_id]},
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
    }


def _additional_tool() -> JsonMap:
    return {
        "type": "function",
        "function": {
            "name": "submit_additional_grounding",
            "description": "Judge grounding of material beyond the matched required claims.",
            "parameters": {
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
    }


def claim_judge_definition_sha256() -> str:
    definition = {
        "contract_version": CLAIM_JUDGE_CONTRACT_VERSION,
        "prompt_version": CLAIM_JUDGE_PROMPT_VERSION,
        "claim_prompt": _claim_prompt(),
        "claim_tool": _claim_tool("<claim_id>", ["<block_id>"]),
        "additional_prompt": _additional_prompt(),
        "additional_tool": _additional_tool(),
        "exact_required_only": "deterministic-pass/v1",
    }
    encoded = json.dumps(
        definition,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _json(value: JsonMap) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True)


def _contains_only_exact_required_claims(packet: JsonMap, claims: list[JsonMap]) -> bool:
    statement_by_claim = {
        str(child_map(item).get("claim_id") or ""): str(
            child_map(item).get("statement") or ""
        )
        for item in as_list(packet.get("required_claims"))
    }
    exact_by_block = {
        str(as_list(claim.get("matched_block_ids"))[0]): _normalize(
            statement_by_claim.get(str(claim.get("claim_id") or ""), "")
        )
        for claim in claims
        if claim.get("verdict") == "expressed"
        and len(as_list(claim.get("matched_block_ids"))) == 1
    }
    blocks = [child_map(item) for item in as_list(packet.get("answer_blocks"))]
    return bool(blocks) and all(
        _normalize(str(block.get("text") or ""))
        == exact_by_block.get(str(block.get("block_id") or ""), "")
        for block in blocks
    )


def _normalize(value: str) -> str:
    return re.sub(r"\s+", " ", value.casefold()).strip().rstrip(".")
