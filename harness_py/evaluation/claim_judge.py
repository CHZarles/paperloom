from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass

from .judge import JudgeProtocolError
from .judge_model import JudgeModel
from ..core.models import JsonMap, as_list, child_map


CLAIM_JUDGE_PROMPT_VERSION = "claim-evidence-judge/v4"
CLAIM_JUDGE_CONTRACT_VERSION = "claim-evidence-semantic-judgment/v1"
CLAIM_JUDGE_PROTOCOL_ATTEMPTS = 2
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
        arguments = self._complete_arguments(
            [
                {"role": "system", "content": _claim_prompt()},
                {"role": "user", "content": _json(request)},
            ],
            _claim_tool(claim_id, block_ids),
            "submit_claim_match",
        )
        result = _parse_claim(arguments, claim_id, block_ids)
        if result["verdict"] in {"missing", "uncertain"}:
            contradiction = self._judge_contradiction(request, claim_id, block_ids)
            if contradiction:
                result = {
                    "claim_id": claim_id,
                    "verdict": "contradicted",
                    "matched_block_ids": [],
                }
        return result

    def _judge_contradiction(
        self,
        request: JsonMap,
        claim_id: str,
        block_ids: list[str],
    ) -> bool:
        arguments = self._complete_arguments(
            [
                {"role": "system", "content": _contradiction_prompt()},
                {"role": "user", "content": _json(request)},
            ],
            _contradiction_tool(claim_id, block_ids),
            "submit_claim_contradiction",
        )
        return _parse_contradiction(arguments, claim_id, block_ids)

    def _judge_additional(self, packet: JsonMap, claims: list[JsonMap]) -> JsonMap:
        if _contains_only_exact_required_claims(packet, claims):
            return {"verdict": "pass", "grounding_issues": []}
        results = [
            self._judge_additional_block(packet, claims, child_map(block))
            for block in as_list(packet.get("answer_blocks"))
        ]
        issues = [
            f"{result['block_id']}: {issue}"
            for result in results
            if result["verdict"] == "unsupported"
            for issue in as_list(result.get("issues"))
        ]
        if issues:
            return {"verdict": "fail", "grounding_issues": issues}
        if any(result["verdict"] == "uncertain" for result in results):
            return {"verdict": "uncertain", "grounding_issues": []}
        return {"verdict": "pass", "grounding_issues": []}

    def _judge_additional_block(
        self,
        packet: JsonMap,
        claims: list[JsonMap],
        block: JsonMap,
    ) -> JsonMap:
        block_id = str(block.get("block_id") or "")
        request = {
            "contract_version": CLAIM_JUDGE_CONTRACT_VERSION,
            "case_id": packet.get("case_id"),
            "user_request": packet.get("user_request"),
            "required_claims": packet.get("required_claims"),
            "required_claim_results": claims,
            "answer_block": block,
            "citation_integrity_errors": packet.get("citation_integrity_errors"),
        }
        arguments = self._complete_arguments(
            [
                {"role": "system", "content": _additional_prompt()},
                {"role": "user", "content": _json(request)},
            ],
            _additional_tool(block_id),
            "submit_block_grounding",
        )
        return _parse_additional_block(arguments, block_id)

    def _complete_arguments(
        self,
        messages: list[JsonMap],
        tool: JsonMap,
        expected_name: str,
    ) -> JsonMap:
        last_error: JudgeProtocolError | None = None
        for _attempt in range(CLAIM_JUDGE_PROTOCOL_ATTEMPTS):
            calls = self.model.complete_judgment(messages, tool, self.max_tokens)
            try:
                return _one_tool_arguments(calls, expected_name)
            except JudgeProtocolError as error:
                last_error = error
        assert last_error is not None
        raise last_error


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


def _parse_contradiction(
    value: JsonMap,
    expected_claim_id: str,
    block_ids: list[str],
) -> bool:
    claim_id = str(value.get("claim_id") or "")
    contradicted = value.get("contradicted")
    block_id = str(value.get("contradicting_block_id") or "")
    if claim_id != expected_claim_id or not isinstance(contradicted, bool):
        raise JudgeProtocolError("claim contradiction judge returned an invalid result")
    if contradicted and block_id not in block_ids:
        raise JudgeProtocolError("claim contradiction judge returned an invalid block")
    if not contradicted and block_id:
        raise JudgeProtocolError("claim contradiction judge returned a block without contradiction")
    return contradicted


def _parse_additional_block(value: JsonMap, expected_block_id: str) -> JsonMap:
    block_id = str(value.get("block_id") or "")
    verdict = str(value.get("verdict") or "")
    issues = [
        str(item).strip()
        for item in as_list(value.get("issues"))
        if str(item).strip()
    ]
    if block_id != expected_block_id:
        raise JudgeProtocolError("block grounding judge returned the wrong block")
    if verdict not in {"supported", "unsupported", "not_material", "uncertain"}:
        raise JudgeProtocolError("block grounding judge returned an invalid verdict")
    if verdict == "unsupported" and not issues:
        raise JudgeProtocolError("unsupported block requires an issue")
    if verdict in {"supported", "not_material"} and issues:
        verdict = "unsupported"
    return {"block_id": block_id, "verdict": verdict, "issues": issues}


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

Treat a Markdown table row as one block and read its cells together. A comparison row can express a
complete claim when its cells explicitly state every required side and qualifier.

Relation direction matters. A block about how tasks or reference solutions were constructed does not
state how generated outputs are evaluated. A block saying a reset is needed does not state that a
system provides a mechanism that restores an original state. A block describing one side of a
comparison does not express both sides. Exact wording is unnecessary; complete meaning is mandatory."""


def _contradiction_prompt() -> str:
    return """CLAIM_CONTRADICTION_JUDGE
Evaluate only whether the answer text clearly asserts the opposite of, or an incompatible conclusion
with, the one required claim. Never use outside knowledge or attached evidence. Return exactly one
submit_claim_contradiction tool call.

Set contradicted=true only when a specific answer block makes the required claim false; identify that
one block. A missing detail, partial answer, omission, different topic, weak implication, or uncertainty
is not a contradiction. Pay close attention to negation and relation direction. If the required claim
says values from different benchmarks are not directly comparable or must not be ranked, an answer
that explicitly compares or ranks those values is a contradiction. Otherwise return contradicted=false
and omit contradicting_block_id or return it as an empty string."""


def _additional_prompt() -> str:
    return """ANSWER_BLOCK_GROUNDING_JUDGE
Evaluate only the supplied answer_block and evidence attached to that block. Never use outside
knowledge or evidence from another block. Return exactly one submit_block_grounding tool call.

This is not a second required-claim grade. First subtract every clause whose whole factual meaning is
contained in any required claim statement. Subtract it even when it is a paraphrase, repetition, or
only one component of a required claim, and even when the claim result is missing, contradicted,
uncertain, or matched to another block. Do not decide here whether a required claim is complete,
correct, or properly cited; the claim and deterministic source checks do that elsewhere.

Also ignore headings, formatting, transition text, and clearly labelled opinion. If nothing material
remains, return not_material. For each remaining factual clause, require direct support in evidence
attached to this block. Return supported when all such clauses are supported. Return unsupported when
one is absent from the evidence, contradicted, overstated, only partly supported, or uncited, and name
the material defect in issues. Use uncertain only when the supplied text genuinely cannot resolve
support. Familiar facts, plausible inference, and citation-like text are not evidence."""


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


def _contradiction_tool(claim_id: str, block_ids: list[str]) -> JsonMap:
    return {
        "type": "function",
        "function": {
            "name": "submit_claim_contradiction",
            "description": "Detect a clear textual contradiction of one required claim.",
            "parameters": {
                "type": "object",
                "required": ["claim_id", "contradicted"],
                "properties": {
                    "claim_id": {"type": "string", "enum": [claim_id]},
                    "contradicted": {"type": "boolean"},
                    "contradicting_block_id": {
                        "type": "string",
                        "enum": ["", *block_ids],
                    },
                },
                "additionalProperties": False,
            },
        },
    }


def _additional_tool(block_id: str) -> JsonMap:
    return {
        "type": "function",
        "function": {
            "name": "submit_block_grounding",
            "description": "Judge only additional factual material in one answer block.",
            "parameters": {
                "type": "object",
                "required": ["block_id", "verdict", "issues"],
                "properties": {
                    "block_id": {"type": "string", "enum": [block_id]},
                    "verdict": {
                        "type": "string",
                        "enum": ["supported", "unsupported", "not_material", "uncertain"],
                    },
                    "issues": {
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
        "protocol_attempts": CLAIM_JUDGE_PROTOCOL_ATTEMPTS,
        "claim_prompt": _claim_prompt(),
        "claim_tool": _claim_tool("<claim_id>", ["<block_id>"]),
        "contradiction_prompt": _contradiction_prompt(),
        "contradiction_tool": _contradiction_tool("<claim_id>", ["<block_id>"]),
        "additional_prompt": _additional_prompt(),
        "additional_tool": _additional_tool("<block_id>"),
        "deterministic_exact_required_only_rule": {
            "version": "deterministic-pass/v1",
            "normalization": "casefold, collapse whitespace, strip, remove trailing periods",
            "condition": (
                "every answer block equals the complete required-claim statement expressed "
                "and matched to that block"
            ),
            "result": {"verdict": "pass", "grounding_issues": []},
        },
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
