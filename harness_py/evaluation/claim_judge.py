from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass

from .judge import JudgeProtocolError
from .judge_model import JudgeModel
from ..core.answer_blocks import NON_MATERIAL_UNCITED_BLOCK_KINDS
from ..core.models import JsonMap, as_list, child_map


CLAIM_JUDGE_PROMPT_VERSION = "claim-evidence-judge/v15"
CLAIM_JUDGE_CONTRACT_VERSION = "claim-evidence-semantic-judgment/v1"
CLAIM_JUDGE_GATE_POLICY_VERSION = "claim-judge-gate/case-disposition-v3"
CLAIM_JUDGE_PROTOCOL_ATTEMPTS = 3
CLAIM_JUDGE_SEMANTIC_ATTEMPTS = 2
CLAIM_JUDGE_BLOCK_BATCH_SIZE = 8
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
        request_base = {
            "contract_version": CLAIM_JUDGE_CONTRACT_VERSION,
            "case_id": packet.get("case_id"),
            "required_claim": {
                "claim_id": claim_id,
                "statement": str(claim.get("statement") or ""),
            },
        }
        result = self._select_claim_candidate(request_base, claim_id, text_blocks)
        cited_block_ids = {
            str(block.get("block_id") or "")
            for block in as_list(packet.get("answer_blocks"))
            if as_list(child_map(block).get("evidence"))
        }
        matched_ids = as_list(result.get("matched_block_ids"))
        if (
            result.get("verdict") == "expressed"
            and matched_ids
            and str(matched_ids[0]) not in cited_block_ids
        ):
            cited_text_blocks = [
                block for block in text_blocks
                if str(block.get("block_id") or "") in cited_block_ids
            ]
            if cited_text_blocks:
                cited_result = self._select_claim_candidate(
                    request_base,
                    claim_id,
                    cited_text_blocks,
                )
                cited_result = self._verify_claim_candidate(
                    request_base,
                    claim_id,
                    cited_result,
                    cited_text_blocks,
                )
                if cited_result.get("verdict") == "expressed":
                    result = cited_result
                else:
                    result = self._verify_claim_candidate(
                        request_base,
                        claim_id,
                        result,
                        text_blocks,
                    )
            else:
                result = self._verify_claim_candidate(
                    request_base,
                    claim_id,
                    result,
                    text_blocks,
                )
        else:
            result = self._verify_claim_candidate(
                request_base,
                claim_id,
                result,
                text_blocks,
            )
        contradiction = self._judge_contradiction(
            {**request_base, "answer_blocks": text_blocks},
            claim_id,
            block_ids,
        )
        if contradiction:
            result = {
                "claim_id": claim_id,
                "verdict": "contradicted",
                "matched_block_ids": [],
            }
        return result

    def _select_claim_candidate(
        self,
        request_base: JsonMap,
        claim_id: str,
        text_blocks: list[JsonMap],
    ) -> JsonMap:
        results = []
        for _attempt in range(CLAIM_JUDGE_SEMANTIC_ATTEMPTS):
            result = self._match_claim_batches(request_base, claim_id, text_blocks)
            results.append(result)
            if result.get("verdict") == "expressed":
                return result
        verdict = (
            "uncertain"
            if any(result.get("verdict") == "uncertain" for result in results)
            else "missing"
        )
        return {"claim_id": claim_id, "verdict": verdict, "matched_block_ids": []}

    def _verify_claim_candidate(
        self,
        request_base: JsonMap,
        claim_id: str,
        result: JsonMap,
        text_blocks: list[JsonMap],
    ) -> JsonMap:
        if result.get("verdict") != "expressed":
            return result
        matched = [str(item) for item in as_list(result.get("matched_block_ids"))]
        if len(matched) != 1:
            return {"claim_id": claim_id, "verdict": "missing", "matched_block_ids": []}
        block_id = matched[0]
        candidate = next(
            (
                block for block in text_blocks
                if str(block.get("block_id") or "") == block_id
            ),
            None,
        )
        if candidate is None:
            return {"claim_id": claim_id, "verdict": "missing", "matched_block_ids": []}
        arguments = self._complete_arguments(
            [
                {"role": "system", "content": _claim_verification_prompt()},
                {
                    "role": "user",
                    "content": _json({**request_base, "answer_blocks": [candidate]}),
                },
            ],
            _claim_tool(claim_id, [block_id]),
            "submit_claim_match",
        )
        verified = _parse_claim(arguments, claim_id, [block_id])
        if verified.get("verdict") == "expressed":
            return verified
        return {
            "claim_id": claim_id,
            "verdict": (
                "uncertain" if verified.get("verdict") == "uncertain" else "missing"
            ),
            "matched_block_ids": [],
        }

    def _match_claim_batches(
        self,
        request_base: JsonMap,
        claim_id: str,
        text_blocks: list[JsonMap],
    ) -> JsonMap:
        batch_results = []
        for offset in range(0, len(text_blocks), CLAIM_JUDGE_BLOCK_BATCH_SIZE):
            batch = text_blocks[offset:offset + CLAIM_JUDGE_BLOCK_BATCH_SIZE]
            batch_ids = [str(block["block_id"]) for block in batch]
            arguments = self._complete_arguments(
                [
                    {"role": "system", "content": _claim_prompt()},
                    {
                        "role": "user",
                        "content": _json({**request_base, "answer_blocks": batch}),
                    },
                ],
                _claim_tool(claim_id, batch_ids),
                "submit_claim_match",
            )
            batch_results.append(_parse_claim(arguments, claim_id, batch_ids))
        return _combine_claim_batches(claim_id, batch_results)

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
            self._judge_additional_block(packet, child_map(block))
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
        block: JsonMap,
    ) -> JsonMap:
        block_id = str(block.get("block_id") or "")
        if not as_list(block.get("evidence")):
            if _is_structural_block(block):
                return {"block_id": block_id, "verdict": "not_material", "issues": []}
            return {
                "block_id": block_id,
                "verdict": "unsupported",
                "issues": ["Factual answer block has no attached evidence."],
            }
        request = {
            "contract_version": CLAIM_JUDGE_CONTRACT_VERSION,
            "case_id": packet.get("case_id"),
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
    matched_block_id = str(value.get("matched_block_id") or "")
    matched = [matched_block_id] if matched_block_id else []
    if claim_id != expected_claim_id:
        raise JudgeProtocolError(f"claim judge returned invalid claim ID: {claim_id}")
    if verdict not in CLAIM_VERDICTS:
        raise JudgeProtocolError(f"claim judge returned invalid verdict for {claim_id}")
    if not set(matched) <= set(block_ids):
        raise JudgeProtocolError(f"claim judge returned invalid blocks for {claim_id}")
    if verdict == "expressed" and not matched:
        verdict = "missing"
    elif verdict != "expressed" and matched:
        matched = []
    return {
        "claim_id": claim_id,
        "verdict": verdict,
        "matched_block_ids": matched,
    }


def _combine_claim_batches(claim_id: str, results: list[JsonMap]) -> JsonMap:
    expressed = next(
        (result for result in results if result.get("verdict") == "expressed"),
        None,
    )
    if expressed is not None:
        return expressed
    verdict = (
        "uncertain"
        if any(result.get("verdict") == "uncertain" for result in results)
        else "missing"
    )
    return {"claim_id": claim_id, "verdict": verdict, "matched_block_ids": []}


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
    if verdict not in {"supported", "unsupported", "uncertain"}:
        raise JudgeProtocolError("block grounding judge returned an invalid verdict")
    if verdict == "unsupported" and not issues:
        raise JudgeProtocolError("unsupported block requires an issue")
    if verdict == "supported" and issues:
        verdict = "unsupported"
    return {"block_id": block_id, "verdict": verdict, "issues": issues}


def _is_structural_block(block: JsonMap) -> bool:
    return str(block.get("kind") or "") in NON_MATERIAL_UNCITED_BLOCK_KINDS


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

Do not fill an unnamed actor, method, system, or value from the authored claim itself. For example, a
block that lists parameters for "the adaptive optimizer" does not state that the optimizer is Adam.

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

Set contradicted=true when the answer adopts a conclusion that makes the required claim false; identify
one block that asserts it. Check the entire answer even when another block appears to express the
required claim. An answer that asserts both positions is internally inconsistent and is contradicted.
A quotation, rejected position, hypothetical, missing detail, partial answer, omission, different
topic, weak implication, or uncertainty is not a contradiction. Pay close attention to negation and
relation direction. If the required claim says values from different benchmarks are not directly
comparable or must not be ranked, an answer that declares such a ranking is a contradiction even when
it later adds a comparability caveat. Otherwise return contradicted=false and omit
contradicting_block_id or return it as an empty string."""


def _claim_verification_prompt() -> str:
    return """CLAIM_COMPLETENESS_VERIFIER
Evaluate whether the single supplied answer block, by itself, explicitly expresses the entire required
claim. This is a conservative verification step, not a search for a plausible connection. Never use
the question, a heading, adjacent blocks, attached evidence, or outside knowledge. Return exactly one
submit_claim_match tool call.

Use expressed only when every entity, relation, comparison side, value, and qualifier in the required
claim appears in the block or a faithful paraphrase. A row naming only an optimizer does not express a
configuration claim that also requires parameter values from other rows. A descriptive property does
not express a recommendation or selection unless the block itself makes that choice. Partial support,
topical overlap, and implications are missing. For example, "SWE-bench contains problems from real
GitHub issues" does not itself say "Use SWE-bench" or select it as the benchmark. Use uncertain only
for genuinely ambiguous wording. A block that directly gives a mechanism or procedure for performing
an action does express that capability without repeating the surrounding use occasion. For a claim
that reconciles two results because they measure different properties in different settings, the block
must identify the measured property or setting on both sides; naming only different feedback sources
is incomplete."""


def _additional_prompt() -> str:
    return """ANSWER_BLOCK_GROUNDING_JUDGE
Evaluate only the supplied answer_block and evidence attached to that block. Never use outside
knowledge or evidence from another block. Return exactly one submit_block_grounding tool call.

Audit every factual clause actually asserted in the block, including conclusions, summaries, repeated
answer claims, comparisons, and bibliographic details. Do not assume a clause is supported because it
resembles a required answer. Do not decide whether the answer completely fulfills a required claim and
do not invent omitted facts; only judge the factual text present in this block.

Return supported only when the attached evidence directly supports every factual clause. Return
unsupported when one clause is absent from the attached evidence, contradicted, overstated, or only
partly supported, and name the material defect in issues. Use uncertain only when the supplied text
genuinely cannot resolve support. Familiar facts, plausible inference, facts cited in another block,
and citation-like text are not evidence."""


def _claim_tool(claim_id: str, block_ids: list[str]) -> JsonMap:
    return {
        "type": "function",
        "function": {
            "name": "submit_claim_match",
            "description": "Judge one required claim and select its strongest complete block.",
            "parameters": {
                "type": "object",
                "required": ["claim_id", "verdict", "matched_block_id"],
                "properties": {
                    "claim_id": {"type": "string", "enum": [claim_id]},
                    "verdict": {"type": "string", "enum": sorted(CLAIM_VERDICTS)},
                    "matched_block_id": {
                        "type": "string",
                        "enum": ["", *block_ids],
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
                        "enum": ["supported", "unsupported", "uncertain"],
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
        "semantic_attempts": CLAIM_JUDGE_SEMANTIC_ATTEMPTS,
        "claim_block_batch_size": CLAIM_JUDGE_BLOCK_BATCH_SIZE,
        "claim_block_selection": "prefer-complete-block-with-attached-evidence/v1",
        "claim_prompt": _claim_prompt(),
        "claim_tool": _claim_tool("<claim_id>", ["<block_id>"]),
        "claim_candidate_verification": "isolated-block/v1",
        "claim_verification_prompt": _claim_verification_prompt(),
        "contradiction_prompt": _contradiction_prompt(),
        "contradiction_tool": _contradiction_tool("<claim_id>", ["<block_id>"]),
        "additional_prompt": _additional_prompt(),
        "additional_tool": _additional_tool("<block_id>"),
        "uncited_block_rule": {
            "not_material_kinds": sorted(NON_MATERIAL_UNCITED_BLOCK_KINDS),
            "all_other_kinds": "unsupported",
        },
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
