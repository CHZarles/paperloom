from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml

from ..core.models import JsonMap, as_list, child_map
from .judge_model import JudgeModel


HUMAN_LABEL_SCHEMA_VERSION = "harness-human-labels/v1"
AGREEMENT_REPORT_SCHEMA_VERSION = "harness-judge-agreement/v1"
JUDGE_PROMPT_VERSION = "llm-judge/v5"


class CalibrationDataError(ValueError):
    pass


class JudgeProtocolError(ValueError):
    pass


@dataclass(frozen=True)
class HumanJudgment:
    decision: str
    task_fulfillment: str
    grounding: str
    overall: str
    note: str

    @classmethod
    def from_dict(cls, value: JsonMap, case_id: str) -> HumanJudgment:
        decision = _required_enum(value, "decision", {"pass", "fail"}, case_id)
        task = _required_enum(value, "task_fulfillment", {"pass", "fail"}, case_id)
        grounding = _required_enum(
            value,
            "grounding",
            {"pass", "fail", "not_applicable"},
            case_id,
        )
        overall = _required_enum(value, "overall", {"pass", "fail"}, case_id)
        expected_overall = _derived_overall(decision, task, grounding)
        if overall != expected_overall:
            raise CalibrationDataError(
                f"case {case_id} overall={overall} disagrees with dimensions; "
                f"expected {expected_overall}"
            )
        note = str(value.get("note") or "").strip()
        if not note:
            raise CalibrationDataError(f"case {case_id} requires a non-empty annotation note")
        return cls(decision, task, grounding, overall, note)

    def to_dict(self, include_note: bool = True) -> JsonMap:
        value: JsonMap = {
            "decision": self.decision,
            "task_fulfillment": self.task_fulfillment,
            "grounding": self.grounding,
            "overall": self.overall,
        }
        if include_note:
            value["note"] = self.note
        return value


@dataclass(frozen=True)
class CalibrationCase:
    case_id: str
    attempt: int
    run_id: str
    review: JsonMap
    answer: JsonMap
    cited_evidence: list[JsonMap]
    integrity_errors: list[str]
    human: HumanJudgment

    def judge_packet(self) -> JsonMap:
        review = {
            key: self.review.get(key)
            for key in ("user_request", "expected_behavior", "corpus_papers", "conversation")
            if self.review.get(key) is not None
        }
        answer = {
            key: self.answer.get(key)
            for key in (
                "status",
                "outcome",
                "summary",
                "markdown",
                "sections",
                "cited_evidence_ids",
            )
            if key in self.answer
        }
        return {
            "case_id": self.case_id,
            "attempt": self.attempt,
            "review": review,
            "answer": answer,
            "cited_evidence": self.cited_evidence,
            "citation_integrity_errors": self.integrity_errors,
        }


@dataclass(frozen=True)
class CalibrationSet:
    dataset_id: str
    source_baseline: str
    source_path: str
    source_sha256: str
    cases: list[CalibrationCase]


@dataclass(frozen=True)
class JudgeVerdict:
    decision: str
    task_fulfillment: str
    grounding: str
    grounding_issues: list[str]
    rationale: str

    @property
    def overall(self) -> str:
        return _derived_overall(self.decision, self.task_fulfillment, self.grounding)

    @classmethod
    def from_dict(cls, value: JsonMap) -> JudgeVerdict:
        decision = _judge_enum(value, "decision", {"pass", "fail"})
        task = _judge_enum(value, "task_fulfillment", {"pass", "fail"})
        grounding = _judge_enum(value, "grounding", {"pass", "fail", "not_applicable"})
        raw_issues = value.get("grounding_issues")
        if not isinstance(raw_issues, list):
            raise JudgeProtocolError("judge grounding_issues must be an array")
        grounding_issues: list[str] = []
        for item in raw_issues:
            for issue in _flatten_issue_text(item):
                if issue not in grounding_issues:
                    grounding_issues.append(issue)
        if grounding != "fail" and grounding_issues:
            raise JudgeProtocolError(
                "judge grounding must be fail when grounding_issues is non-empty"
            )
        rationale = str(value.get("rationale") or "").strip()
        return cls(decision, task, grounding, grounding_issues, rationale)

    def to_dict(self) -> JsonMap:
        return {
            "decision": self.decision,
            "task_fulfillment": self.task_fulfillment,
            "grounding": self.grounding,
            "overall": self.overall,
            "grounding_issues": self.grounding_issues,
            "rationale": self.rationale,
        }


class LLMJudge:
    def __init__(self, model: JudgeModel, max_tokens: int = 1200):
        self.model = model
        self.max_tokens = max_tokens

    def judge(self, case: CalibrationCase) -> JudgeVerdict:
        tool_calls = self.model.complete_judgment(
            [
                {"role": "system", "content": _judge_system_prompt()},
                {
                    "role": "user",
                    "content": json.dumps(case.judge_packet(), ensure_ascii=False, sort_keys=True),
                },
            ],
            _judgment_tool(),
            self.max_tokens,
        )
        if len(tool_calls) != 1:
            raise JudgeProtocolError("judge must return exactly one submit_judgment tool call")
        tool_call = child_map(tool_calls[0])
        name = str(tool_call.get("name") or "")
        if name != "submit_judgment":
            raise JudgeProtocolError(
                f"judge returned tool {name!r}; expected 'submit_judgment'"
            )
        return JudgeVerdict.from_dict(child_map(tool_call.get("arguments")))


def load_calibration_cases(
    path: str | Path,
    repo_root: str | Path | None = None,
) -> CalibrationSet:
    labels_path = Path(path).resolve()
    root = Path(repo_root).resolve() if repo_root is not None else Path.cwd().resolve()
    raw = _load_yaml(labels_path)
    if raw.get("schema_version") != HUMAN_LABEL_SCHEMA_VERSION:
        raise CalibrationDataError(
            f"unsupported human label schema: {raw.get('schema_version')!r}"
        )
    dataset_id = str(raw.get("dataset_id") or "").strip()
    if not dataset_id:
        raise CalibrationDataError("human labels require dataset_id")

    cases: list[CalibrationCase] = []
    seen: set[tuple[str, int, str]] = set()
    for raw_label in as_list(raw.get("labels")):
        label = child_map(raw_label)
        case_id = str(label.get("case_id") or "").strip()
        run_id = str(label.get("run_id") or "").strip()
        attempt = label.get("attempt")
        if not case_id or not run_id or not isinstance(attempt, int) or isinstance(attempt, bool) or attempt < 1:
            raise CalibrationDataError("every human label requires case_id, run_id, and positive attempt")
        identity = (case_id, attempt, run_id)
        if identity in seen:
            raise CalibrationDataError(f"duplicate human label: {identity}")
        seen.add(identity)

        review = child_map(label.get("review"))
        user_request = str(review.get("user_request") or "").strip()
        expected_behavior = [
            str(item).strip()
            for item in as_list(review.get("expected_behavior"))
            if str(item).strip()
        ]
        if not user_request or not expected_behavior:
            raise CalibrationDataError(
                f"case {case_id} review requires user_request and expected_behavior"
            )
        answer = _load_json(_resolve_artifact(root, review.get("answer_path"), case_id, "answer"))
        ledger = _load_json(_resolve_artifact(root, review.get("evidence_path"), case_id, "evidence"))
        cited_evidence, integrity_errors = _project_cited_evidence(answer, ledger)
        human = HumanJudgment.from_dict(child_map(label.get("judgment")), case_id)
        cases.append(CalibrationCase(
            case_id=case_id,
            attempt=attempt,
            run_id=run_id,
            review=review,
            answer=answer,
            cited_evidence=cited_evidence,
            integrity_errors=integrity_errors,
            human=human,
        ))
    if not cases:
        raise CalibrationDataError("human labels contain no calibration cases")
    return CalibrationSet(
        dataset_id=dataset_id,
        source_baseline=str(raw.get("source_baseline") or ""),
        source_path=str(labels_path),
        source_sha256=hashlib.sha256(labels_path.read_bytes()).hexdigest(),
        cases=cases,
    )


def evaluate_calibration(
    calibration: CalibrationSet,
    judge: Any,
    judge_metadata: JsonMap | None = None,
) -> JsonMap:
    case_reports: list[JsonMap] = []
    dimension_matches = {
        "decision": 0,
        "task_fulfillment": 0,
        "grounding": 0,
        "overall": 0,
    }
    full_agreement_count = 0
    false_pass_count = 0
    false_failure_count = 0
    technical_error_count = 0

    for case in calibration.cases:
        human = case.human.to_dict()
        try:
            verdict = judge.judge(case)
            judged = verdict.to_dict()
            matches = {
                dimension: human[dimension] == judged[dimension]
                for dimension in dimension_matches
            }
            for dimension, matched in matches.items():
                if matched:
                    dimension_matches[dimension] += 1
            full_match = all(matches.values())
            if full_match:
                full_agreement_count += 1
            if human["overall"] == "fail" and judged["overall"] == "pass":
                false_pass_count += 1
            if human["overall"] == "pass" and judged["overall"] == "fail":
                false_failure_count += 1
            case_reports.append({
                "case_id": case.case_id,
                "attempt": case.attempt,
                "run_id": case.run_id,
                "human": human,
                "judge": judged,
                "matches": matches,
                "full_match": full_match,
            })
        except Exception as error:
            technical_error_count += 1
            case_reports.append({
                "case_id": case.case_id,
                "attempt": case.attempt,
                "run_id": case.run_id,
                "human": human,
                "judge": {
                    "status": "technical_error",
                    "error_type": type(error).__name__,
                    "error": str(error),
                },
                "matches": {dimension: False for dimension in dimension_matches},
                "full_match": False,
            })

    case_count = len(calibration.cases)
    metadata = dict(judge_metadata or {})
    metadata["prompt_version"] = JUDGE_PROMPT_VERSION
    return {
        "schema_version": AGREEMENT_REPORT_SCHEMA_VERSION,
        "dataset_id": calibration.dataset_id,
        "source_baseline": calibration.source_baseline,
        "source_path": calibration.source_path,
        "source_sha256": calibration.source_sha256,
        "judge": metadata,
        "case_count": case_count,
        "full_agreement_count": full_agreement_count,
        "overall_agreement_count": dimension_matches["overall"],
        "disagreement_count": case_count - full_agreement_count,
        "false_pass_count": false_pass_count,
        "false_failure_count": false_failure_count,
        "technical_error_count": technical_error_count,
        "dimension_agreement": {
            dimension: {
                "matched_count": count,
                "case_count": case_count,
            }
            for dimension, count in dimension_matches.items()
        },
        "accepted": (
            case_count > 0
            and full_agreement_count == case_count
            and technical_error_count == 0
            and false_pass_count == 0
        ),
        "cases": case_reports,
    }


def _project_cited_evidence(answer: JsonMap, ledger: JsonMap) -> tuple[list[JsonMap], list[str]]:
    evidence_by_id: dict[str, JsonMap] = {}
    integrity_errors: list[str] = []
    for raw_item in as_list(ledger.get("items")):
        item = child_map(raw_item)
        evidence_id = str(item.get("evidence_id") or "").strip()
        if not evidence_id:
            continue
        if evidence_id in evidence_by_id:
            integrity_errors.append(f"duplicate evidence id: {evidence_id}")
            continue
        evidence_by_id[evidence_id] = item

    projected: list[JsonMap] = []
    seen_citations: set[str] = set()
    for raw_evidence_id in as_list(answer.get("cited_evidence_ids")):
        evidence_id = str(raw_evidence_id).strip()
        if not evidence_id or evidence_id in seen_citations:
            continue
        seen_citations.add(evidence_id)
        item = evidence_by_id.get(evidence_id)
        if item is None:
            integrity_errors.append(f"unknown cited evidence id: {evidence_id}")
            continue
        projected.append({
            key: item.get(key)
            for key in (
                "evidence_id",
                "paper_id",
                "title",
                "section",
                "page",
                "element_type",
                "span_text",
            )
            if item.get(key) is not None
        })
    return projected, integrity_errors


def _judge_system_prompt() -> str:
    return """LLM_JUDGE
Evaluate one paper-research response using only the supplied review packet. Do not use external
knowledge. Return exactly one submit_judgment tool call.

Judge the dimensions independently. decision considers only whether answer, clarification, or
corpus-boundary handling is the correct action; factual or citation defects must not change decision.
task_fulfillment considers factual correctness and requested coverage, not citation completeness; a
grounding failure alone must not change task_fulfillment.

grounding is STRICT CITATION COMPLETENESS, not factual plausibility. Inspect every falsifiable clause
in the answer, including ancillary details, and list every unsupported, contradicted, or only partly
supported clause in grounding_issues. There is no minor-inference exception. Familiar, standard, or
likely facts are still unsupported when absent from the supplied passages. A citation supporting one
part of a sentence does not support added details.

Hard rule: evidence that only describes architecture or autoregressive generation does not support an
unstated loss function, cross-entropy objective, supervision regime, training dataset, task objective,
result, or tradeoff. Never call such additions implied. grounding must be fail whenever
grounding_issues or citation_integrity_errors is non-empty. Use not_applicable only when the response
makes no research claim.

Equivalent passages are acceptable; exact Golden anchors are unnecessary. Ignore answer_type,
private fields, paradigms, stages, tool counts, and hidden reasoning. A concise rationale is useful but
optional. Do not mention or predict a human label."""


def _judgment_tool() -> JsonMap:
    return {
        "type": "function",
        "function": {
            "name": "submit_judgment",
            "description": "Submit the three independent semantic evaluation dimensions.",
            "parameters": {
                "type": "object",
                "required": [
                    "decision",
                    "task_fulfillment",
                    "grounding",
                    "grounding_issues",
                ],
                "properties": {
                    "decision": {"type": "string", "enum": ["pass", "fail"]},
                    "task_fulfillment": {"type": "string", "enum": ["pass", "fail"]},
                    "grounding": {
                        "type": "string",
                        "enum": ["pass", "fail", "not_applicable"],
                    },
                    "grounding_issues": {
                        "type": "array",
                        "maxItems": 12,
                        "items": {"type": "string", "maxLength": 500},
                    },
                    "rationale": {"type": "string", "maxLength": 900},
                },
                "additionalProperties": False,
            },
        },
    }


def _derived_overall(decision: str, task: str, grounding: str) -> str:
    return (
        "pass"
        if decision == "pass" and task == "pass" and grounding in {"pass", "not_applicable"}
        else "fail"
    )


def _required_enum(value: JsonMap, key: str, allowed: set[str], case_id: str) -> str:
    actual = str(value.get(key) or "")
    if actual not in allowed:
        raise CalibrationDataError(
            f"case {case_id} judgment.{key} must be one of {sorted(allowed)}"
        )
    return actual


def _judge_enum(value: JsonMap, key: str, allowed: set[str]) -> str:
    actual = str(value.get(key) or "")
    if actual not in allowed:
        raise JudgeProtocolError(
            f"judge {key}={actual!r} must be one of {sorted(allowed)}"
        )
    return actual


def _flatten_issue_text(value: Any) -> list[str]:
    if isinstance(value, str):
        text = value.strip()
        return [text] if text else []
    if isinstance(value, list):
        return [text for item in value for text in _flatten_issue_text(item)]
    if isinstance(value, dict):
        return [text for item in value.values() for text in _flatten_issue_text(item)]
    return []


def _resolve_artifact(root: Path, raw_path: Any, case_id: str, label: str) -> Path:
    if not raw_path:
        raise CalibrationDataError(f"case {case_id} is missing {label}_path")
    path = Path(str(raw_path))
    if not path.is_absolute():
        path = root / path
    if not path.is_file():
        raise CalibrationDataError(f"case {case_id} {label} artifact is missing: {path}")
    return path


def _load_yaml(path: Path) -> JsonMap:
    try:
        value = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    except (OSError, yaml.YAMLError) as error:
        raise CalibrationDataError(f"cannot load human labels {path}: {error}") from error
    if not isinstance(value, dict):
        raise CalibrationDataError(f"human label root must be a mapping: {path}")
    return value


def _load_json(path: Path) -> JsonMap:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise CalibrationDataError(f"cannot load calibration artifact {path}: {error}") from error
    if not isinstance(value, dict):
        raise CalibrationDataError(f"calibration artifact root must be an object: {path}")
    return value
