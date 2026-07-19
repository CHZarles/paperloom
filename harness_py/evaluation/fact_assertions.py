from __future__ import annotations

import hashlib
import json
import re
import unicodedata
from dataclasses import dataclass

from ..core.models import JsonMap, child_map


FACT_FIELDS_SCHEMA_VERSION = "golden-facts/v1"
FACT_ASSERTION_CONTRACT_VERSION = "typed-markdown-facts/v1"


@dataclass(frozen=True)
class FactAssertionResult:
    status: str
    errors: list[str]


@dataclass(frozen=True)
class FactDefinition:
    kind: str
    labels: tuple[str, ...]


_FACT_DEFINITIONS = {
    "optimizer": FactDefinition("labeled_scalar", ("optimizer",)),
    "beta1": FactDefinition("labeled_scalar", ("beta1",)),
    "beta2": FactDefinition("labeled_scalar", ("beta2",)),
    "epsilon": FactDefinition("labeled_scalar", ("epsilon",)),
    "transformer_role": FactDefinition("normalized_phrase", ()),
    "environment_count": FactDefinition(
        "labeled_scalar",
        ("environment", "environments"),
    ),
    "question_count": FactDefinition(
        "labeled_scalar",
        ("question", "questions"),
    ),
    "human_success_rate": FactDefinition(
        "labeled_scalar",
        ("human", "human annotator", "human annotators"),
    ),
    "gpt4_plugins_success_rate": FactDefinition(
        "labeled_scalar",
        (
            "gpt-4 with plugins",
            "gpt4 with plugins",
            "gpt-4 equipped with plugins",
            "gpt4 equipped with plugins",
            "gpt-4 plugins",
            "gpt4 plugins",
        ),
    ),
    "application_count": FactDefinition(
        "labeled_scalar",
        ("application", "applications", "app", "apps", "domain", "domains"),
    ),
}

_NUMBER_WORDS = {
    "zero": "0",
    "one": "1",
    "two": "2",
    "three": "3",
    "four": "4",
    "five": "5",
    "six": "6",
    "seven": "7",
    "eight": "8",
    "nine": "9",
    "ten": "10",
    "eleven": "11",
    "twelve": "12",
}

_SUPERSCRIPT_EXPONENT = re.compile(r"(?<=\d)([⁺⁻]?[⁰¹²³⁴⁵⁶⁷⁸⁹]+)")
_SUPERSCRIPT_TRANSLATION = str.maketrans({
    "⁰": "0",
    "¹": "1",
    "²": "2",
    "³": "3",
    "⁴": "4",
    "⁵": "5",
    "⁶": "6",
    "⁷": "7",
    "⁸": "8",
    "⁹": "9",
    "⁺": "+",
    "⁻": "-",
})
_POWER_OF_TEN = re.compile(
    r"(?<![\w.])(?:(\d+(?:\.\d+)?)\s*(?:x|×|\*)\s*)?"
    r"10\s*\^\s*\{?\s*([+-]?)\s*(\d+)\s*\}?(?!\d)"
)
_SCIENTIFIC_LITERAL = re.compile(
    r"(?<![\w.])(\d+(?:\.\d+)?)\s*e\s*([+-]?)\s*(\d+)(?![\w.])"
)


def fact_assertion_contract() -> JsonMap:
    return {
        "version": FACT_ASSERTION_CONTRACT_VERSION,
        "declared_fields_schema": FACT_FIELDS_SCHEMA_VERSION,
        "definitions": {
            key: {
                "kind": definition.kind,
                "labels": list(definition.labels),
            }
            for key, definition in sorted(_FACT_DEFINITIONS.items())
        },
        "normalization": "unicode-nfkc-greek-subscript-scientific-number-word-v1",
        "claim_units": "markdown-line-sentence-contrast-clause-v1",
    }


def contract_sha256(value: JsonMap) -> str:
    encoded = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def evaluate_fact_assertions(facts: JsonMap, answer: JsonMap) -> FactAssertionResult:
    fields_schema = str(answer.get("fields_schema") or "")
    if fields_schema == FACT_FIELDS_SCHEMA_VERSION:
        return _evaluate_structured_facts(facts, child_map(answer.get("fields")))
    if fields_schema:
        return FactAssertionResult(
            "review_required",
            [f"FACT_FIELDS_SCHEMA_UNSUPPORTED:{fields_schema}"],
        )

    markdown = str(answer.get("markdown") or "")
    errors: list[str] = []
    unsupported: list[str] = []
    for key, expected in facts.items():
        definition = _FACT_DEFINITIONS.get(str(key))
        if definition is None:
            unsupported.append(str(key))
            continue
        if definition.kind == "labeled_scalar":
            _match_labeled_scalar(
                markdown,
                str(key),
                str(expected),
                definition.labels,
                errors,
            )
        elif definition.kind == "normalized_phrase":
            _match_normalized_phrase(markdown, str(key), str(expected), errors)
    if unsupported:
        unsupported_errors = [
            f"FACT_ASSERTION_UNSUPPORTED:{key}"
            for key in unsupported
        ]
        if errors:
            return FactAssertionResult("fail", [*errors, *unsupported_errors])
        return FactAssertionResult("review_required", unsupported_errors)
    return FactAssertionResult("fail" if errors else "pass", errors)


def _evaluate_structured_facts(facts: JsonMap, fields: JsonMap) -> FactAssertionResult:
    errors: list[str] = []
    for key, expected in facts.items():
        if key not in fields:
            errors.append(f"FACT_MISSING:{key}")
        elif _scalar_string(fields.get(key)) != _scalar_string(expected):
            errors.append(f"FACT_MISMATCH:{key}")
    return FactAssertionResult("fail" if errors else "pass", errors)


def _match_labeled_scalar(
    markdown: str,
    key: str,
    expected: str,
    labels: tuple[str, ...],
    errors: list[str],
) -> None:
    normalized_expected = _scalar_string(expected)
    labeled_units = [
        unit
        for unit in _claim_units(markdown)
        if any(_contains_term(unit, label) for label in labels)
    ]
    if not labeled_units:
        errors.append(f"FACT_MISSING:{key}")
        return
    if not any(_contains_term(unit, normalized_expected) for unit in labeled_units):
        errors.append(f"FACT_MISMATCH:{key}")


def _match_normalized_phrase(
    markdown: str,
    key: str,
    expected: str,
    errors: list[str],
) -> None:
    if not any(_contains_term(unit, expected) for unit in _claim_units(markdown)):
        errors.append(f"FACT_MISSING:{key}")


def _claim_units(markdown: str) -> list[str]:
    units: list[str] = []
    for raw_line in markdown.splitlines():
        stripped = _normalize_text(raw_line).strip(" |\t-*#")
        if not stripped:
            continue
        for sentence in re.split(r"(?<=[.!?;])\s+", stripped):
            units.extend(
                part.strip(" ,")
                for part in re.split(
                    r"\b(?:while|whereas|versus|vs\.?)\b",
                    sentence,
                )
                if part.strip(" ,")
            )
    return units


def _normalize_text(value: str) -> str:
    marked_exponents = _SUPERSCRIPT_EXPONENT.sub(
        lambda match: "^" + match.group(1).translate(_SUPERSCRIPT_TRANSLATION),
        value,
    )
    text = unicodedata.normalize("NFKC", marked_exponents).lower()
    text = text.replace("β", "beta").replace("ε", "epsilon")
    text = text.replace("−", "-").replace("–", "-")
    text = re.sub(r"\bbeta[\s_]+([12])\b", r"beta\1", text)
    text = _POWER_OF_TEN.sub(_canonical_power_of_ten, text)
    text = _SCIENTIFIC_LITERAL.sub(_canonical_scientific_literal, text)
    for word, number in _NUMBER_WORDS.items():
        text = re.sub(rf"\b{word}\b", number, text)
    return re.sub(r"\s+", " ", text).strip()


def _canonical_power_of_ten(match: re.Match[str]) -> str:
    coefficient = _canonical_decimal(match.group(1) or "1")
    exponent = int(f"{match.group(2)}{match.group(3)}")
    return f"{coefficient}e{exponent}"


def _canonical_scientific_literal(match: re.Match[str]) -> str:
    coefficient = _canonical_decimal(match.group(1))
    exponent = int(f"{match.group(2)}{match.group(3)}")
    return f"{coefficient}e{exponent}"


def _canonical_decimal(value: str) -> str:
    if "." not in value:
        return value.lstrip("0") or "0"
    whole, fraction = value.split(".", 1)
    whole = whole.lstrip("0") or "0"
    fraction = fraction.rstrip("0")
    return f"{whole}.{fraction}" if fraction else whole


def _contains_term(text: str, term: str) -> bool:
    normalized = _normalize_text(term)
    return re.search(
        rf"(?<![\w.]){re.escape(normalized)}(?!\w|\.\d)",
        text,
    ) is not None


def _scalar_string(value: object) -> str:
    if isinstance(value, float):
        value = f"{value:g}"
    text = str(value).strip().replace(chr(0x2212), "-")
    compact = re.sub(r"\s+", "", text).strip("$")
    power = re.fullmatch(r"10\^\{?([+-]?\d+)\}?", compact)
    if power:
        text = f"1e{int(power.group(1))}"
    return text.replace("e-0", "e-").replace("e+0", "e+").lower()
