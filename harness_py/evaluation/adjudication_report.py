from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

import yaml

from .fact_assertions import contract_sha256
from ..core.models import (
    JsonMap,
    child_map,
)


REPORT_SCHEMA_VERSION = "paperloom-human-adjudication-report/v1"
LABEL_SCHEMA_VERSION = "paperloom-blind-answer-labels/v1"
_BINARY_LABELS = {"pass", "fail"}
_GROUNDING_LABELS = {"pass", "fail", "not_applicable"}
_PREFERENCES = {"A", "B", "tie"}
_SEMANTIC_FIELDS = ("decision", "task_fulfillment", "grounding", "overall")
_HISTORICAL_SCORE_REPORT_SCHEMAS = {
    "harness-score-report/v2",
    "harness-score-report/v3",
}


def load_adjudication_report(
    *,
    labels_path: Path,
    blind_map_path: Path,
    score_report_paths: dict[str, Path],
    display_paths_relative_to: Path | None = None,
) -> JsonMap:
    labels_path = labels_path.resolve()
    blind_map_path = blind_map_path.resolve()
    resolved_scores = {
        model_id: path.resolve()
        for model_id, path in score_report_paths.items()
    }
    score_reports = {
        model_id: _load_json(path)
        for model_id, path in resolved_scores.items()
    }
    relative_to = display_paths_relative_to.resolve() if display_paths_relative_to else None
    return build_adjudication_report(
        labels_document=_load_yaml(labels_path),
        blind_map=_load_json(blind_map_path),
        score_reports=score_reports,
        input_paths={
            "labels": _display_path(labels_path, relative_to),
            "blind_map": _display_path(blind_map_path, relative_to),
            "score_reports": {
                model_id: _display_path(path, relative_to)
                for model_id, path in sorted(resolved_scores.items())
            },
        },
    )


def build_adjudication_report(
    *,
    labels_document: JsonMap,
    blind_map: JsonMap,
    score_reports: dict[str, JsonMap],
    input_paths: JsonMap | None = None,
) -> JsonMap:
    labels, case_ids = _validate_labels(labels_document)
    models = _validate_blind_map(blind_map, case_ids)
    conformance_by_model = _validate_score_reports(score_reports, models, case_ids)

    field_counts = {
        model_id: {field: Counter() for field in _SEMANTIC_FIELDS}
        for model_id in models
    }
    confusion = {model_id: Counter() for model_id in models}
    failures = {model_id: [] for model_id in models}
    preference_counts = Counter({model_id: 0 for model_id in models})
    pairwise = {
        "both_passed": 0,
        "both_failed": 0,
        "only_passed": {model_id: 0 for model_id in models},
    }
    case_rows: list[JsonMap] = []

    for label in labels:
        case_id = str(label["case_id"])
        answers_by_model: dict[str, JsonMap] = {}
        passing_models: list[str] = []
        for answer_label, key in (("A", "answer_a"), ("B", "answer_b")):
            model_id = str(child_map(blind_map[case_id])[answer_label])
            answer = child_map(label[key])
            conformance = conformance_by_model[model_id][case_id]
            semantic_pass = answer["overall"] == "pass"
            if semantic_pass:
                passing_models.append(model_id)
            for field in _SEMANTIC_FIELDS:
                field_counts[model_id][field][str(answer[field])] += 1
            confusion[model_id][_confusion_key(conformance, semantic_pass)] += 1

            failed_dimensions = [
                field
                for field in _SEMANTIC_FIELDS
                if answer[field] == "fail"
            ]
            if not semantic_pass:
                failures[model_id].append({
                    "case_id": case_id,
                    "answer_label": answer_label,
                    "failed_dimensions": failed_dimensions,
                    "note": str(answer["note"]),
                })
            answers_by_model[model_id] = {
                "answer_label": answer_label,
                "contract_anchor_conformance": conformance,
                "semantic_quality": {
                    field: answer[field]
                    for field in _SEMANTIC_FIELDS
                },
                "note": str(answer["note"]),
            }

        if len(passing_models) == 2:
            pairwise["both_passed"] += 1
        elif not passing_models:
            pairwise["both_failed"] += 1
        else:
            pairwise["only_passed"][passing_models[0]] += 1

        preferred = str(label["preferred"])
        preferred_model = "tie"
        if preferred != "tie":
            preferred_model = str(child_map(blind_map[case_id])[preferred])
            preference_counts[preferred_model] += 1
        case_rows.append({
            "case_id": case_id,
            "preferred_model": preferred_model,
            "answers": {
                model_id: answers_by_model[model_id]
                for model_id in models
            },
        })

    model_reports: dict[str, JsonMap] = {}
    for model_id in models:
        conformance_passed = sum(conformance_by_model[model_id].values())
        grounding = field_counts[model_id]["grounding"]
        model_confusion = confusion[model_id]
        agreement = model_confusion["true_positive"] + model_confusion["true_negative"]
        model_reports[model_id] = {
            "contract_anchor_conformance": _binary_summary(
                conformance_passed,
                len(case_ids),
            ),
            "semantic_quality": {
                "decision": _binary_summary(
                    field_counts[model_id]["decision"]["pass"],
                    len(case_ids),
                ),
                "task_fulfillment": _binary_summary(
                    field_counts[model_id]["task_fulfillment"]["pass"],
                    len(case_ids),
                ),
                "grounding": _grounding_summary(grounding),
                "overall": _binary_summary(
                    field_counts[model_id]["overall"]["pass"],
                    len(case_ids),
                ),
            },
            "preferences": {
                "preferred_count": preference_counts[model_id],
                "preferred_rate": _rate(preference_counts[model_id], len(case_ids)),
            },
            "scorer_confusion": {
                "true_positive": model_confusion["true_positive"],
                "false_negative": model_confusion["false_negative"],
                "false_positive": model_confusion["false_positive"],
                "true_negative": model_confusion["true_negative"],
                "agreement_count": agreement,
                "agreement_rate": _rate(agreement, len(case_ids)),
            },
            "genuine_failures": failures[model_id],
        }

    return {
        "schema_version": REPORT_SCHEMA_VERSION,
        "case_count": len(case_ids),
        "model_ids": models,
        "input_paths": input_paths or {},
        "metric_definitions": {
            "contract_anchor_conformance": {
                "source_field": "hard_pass",
                "meaning": (
                    "Deterministic compliance with the authored outcome, paper, anchor, "
                    "fact, and citation contract. It is not answer accuracy."
                ),
            },
            "semantic_quality": {
                "source": "frozen_human_labels",
                "meaning": (
                    "Human judgement of decision, task fulfilment, grounding, and overall answer quality."
                ),
            },
        },
        "preferences": {
            "by_model": {
                model_id: preference_counts[model_id]
                for model_id in models
            },
            "tie_count": sum(1 for label in labels if label["preferred"] == "tie"),
        },
        "pairwise_overall": pairwise,
        "models": model_reports,
        "cases": case_rows,
        "execution_policy": {
            "offline_only": True,
            "model_rerun_required": False,
        },
    }


def render_adjudication_markdown(report: JsonMap) -> str:
    models = [str(model_id) for model_id in report["model_ids"]]
    lines = [
        "# 人工盲审与合同一致性报告",
        "",
        "本报告将原始 `hard_pass` 保留为**合同/锚点一致性**，不将它解释为回答准确率。",
        "**人工语义质量**独立来自冻结的盲审标签。本报告只读取已保存产物，不重跑模型。",
        "",
        "## 总览",
        "",
        "| 模型 | 合同/锚点一致性 | 人工总体评分 | 事实依据 | 盲审偏好 |",
        "| --- | ---: | ---: | ---: | ---: |",
    ]
    for model_id in models:
        model = child_map(child_map(report["models"])[model_id])
        conformance = child_map(model["contract_anchor_conformance"])
        semantic = child_map(model["semantic_quality"])
        overall = child_map(semantic["overall"])
        grounding = child_map(semantic["grounding"])
        preferences = child_map(model["preferences"])
        lines.append(
            f"| `{model_id}` | {_fraction(conformance, 'passed_count', 'total_count')} "
            f"| {_fraction(overall, 'passed_count', 'total_count')} "
            f"| {_fraction(grounding, 'passed_count', 'applicable_count')} "
            f"| {preferences['preferred_count']}/{report['case_count']} |"
        )

    lines.extend([
        "",
        "## 评分器混淆矩阵",
        "",
        "| 模型 | 合同与人工均通过 | 假阴性 | 假阳性 | 合同与人工均失败 | 一致率 |",
        "| --- | ---: | ---: | ---: | ---: | ---: |",
    ])
    for model_id in models:
        confusion = child_map(child_map(report["models"])[model_id])["scorer_confusion"]
        lines.append(
            f"| `{model_id}` | {confusion['true_positive']} | {confusion['false_negative']} "
            f"| {confusion['false_positive']} | {confusion['true_negative']} "
            f"| {_percent(float(confusion['agreement_rate']))} |"
        )

    pairwise = child_map(report["pairwise_overall"])
    lines.extend([
        "",
        "## 成对人工总体判定",
        "",
        f"- 两个模型都通过：`{pairwise['both_passed']}/{report['case_count']}`",
        f"- 两个模型都失败：`{pairwise['both_failed']}/{report['case_count']}`",
    ])
    for model_id in models:
        lines.append(
            f"- 只有 `{model_id}` 通过："
            f"`{child_map(pairwise['only_passed'])[model_id]}/{report['case_count']}`"
        )

    lines.extend(["", "## 人工确认的真实失败", ""])
    for model_id in models:
        failures = list(child_map(report["models"])[model_id]["genuine_failures"])
        lines.append(f"### {model_id} ({len(failures)})")
        lines.append("")
        if not failures:
            lines.append("- 无。")
        for failure in failures:
            dimensions = ", ".join(failure["failed_dimensions"])
            lines.append(
                f"- `{failure['case_id']}` [{dimensions}]：{_markdown_text(str(failure['note']))}"
            )
        lines.append("")

    lines.extend([
        "## 边界",
        "",
        "- 本报告不修改 Golden Case、Prompt、路径、Reading Model 或生产编排。",
        "- 只有运行时或模型行为发生实质变化后，才需要重跑模型。",
        "",
    ])
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Join frozen blind human labels with saved deterministic score reports."
        )
    )
    parser.add_argument("--labels", type=Path, required=True)
    parser.add_argument("--blind-map", type=Path, required=True)
    parser.add_argument(
        "--score-report",
        action="append",
        default=[],
        metavar="MODEL=PATH",
        help="Saved harness score report; repeat once per model.",
    )
    parser.add_argument("--json-out", type=Path)
    parser.add_argument("--markdown-out", type=Path)
    parser.add_argument("--relative-to", type=Path)
    args = parser.parse_args(argv)

    try:
        score_paths = _parse_score_report_paths(args.score_report)
        report = load_adjudication_report(
            labels_path=args.labels,
            blind_map_path=args.blind_map,
            score_report_paths=score_paths,
            display_paths_relative_to=args.relative_to,
        )
        encoded = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
        if args.json_out:
            _write_text(args.json_out, encoded)
        if args.markdown_out:
            _write_text(args.markdown_out, render_adjudication_markdown(report))
        if not args.json_out and not args.markdown_out:
            print(encoded, end="")
        return 0
    except (OSError, ValueError, json.JSONDecodeError, yaml.YAMLError) as error:
        print(f"adjudication report failed: {error}", file=sys.stderr)
        return 2


def _validate_labels(labels_document: JsonMap) -> tuple[list[JsonMap], list[str]]:
    if labels_document.get("schema_version") != LABEL_SCHEMA_VERSION:
        raise ValueError(
            f"unsupported human-label schema: {labels_document.get('schema_version')!r}"
        )
    raw_labels = labels_document.get("labels")
    if not isinstance(raw_labels, list) or not raw_labels:
        raise ValueError("human labels must contain a non-empty labels list")

    labels: list[JsonMap] = []
    case_ids: list[str] = []
    for raw_label in raw_labels:
        label = child_map(raw_label)
        case_id = str(label.get("case_id") or "")
        if not case_id:
            raise ValueError("human label is missing case_id")
        if case_id in case_ids:
            raise ValueError(f"duplicate human-label case: {case_id}")
        for answer_key in ("answer_a", "answer_b"):
            answer = child_map(label.get(answer_key))
            for field in ("decision", "task_fulfillment", "overall"):
                if answer.get(field) not in _BINARY_LABELS:
                    raise ValueError(
                        f"invalid {case_id}.{answer_key}.{field}: {answer.get(field)!r}"
                    )
            if answer.get("grounding") not in _GROUNDING_LABELS:
                raise ValueError(
                    f"invalid {case_id}.{answer_key}.grounding: {answer.get('grounding')!r}"
                )
            if not str(answer.get("note") or "").strip():
                raise ValueError(f"missing {case_id}.{answer_key}.note")
        if label.get("preferred") not in _PREFERENCES:
            raise ValueError(f"invalid {case_id}.preferred: {label.get('preferred')!r}")
        labels.append(label)
        case_ids.append(case_id)
    return labels, case_ids


def _validate_blind_map(blind_map: JsonMap, case_ids: list[str]) -> list[str]:
    label_cases = set(case_ids)
    map_cases = set(blind_map)
    if label_cases != map_cases:
        raise ValueError(
            "human-label and blind-map case sets differ: "
            f"labels_only={sorted(label_cases - map_cases)}, "
            f"map_only={sorted(map_cases - label_cases)}"
        )
    models: set[str] = set()
    for case_id in case_ids:
        pair = child_map(blind_map.get(case_id))
        if set(pair) != {"A", "B"}:
            raise ValueError(f"blind map requires A and B for {case_id}")
        identities = [str(pair[label] or "") for label in ("A", "B")]
        if not all(identities) or identities[0] == identities[1]:
            raise ValueError(f"blind map requires two distinct models for {case_id}")
        models.update(identities)
    if len(models) != 2:
        raise ValueError(f"blind map must contain exactly two models, found {sorted(models)}")
    return sorted(models)


def _validate_score_reports(
    score_reports: dict[str, JsonMap],
    models: list[str],
    case_ids: list[str],
) -> dict[str, dict[str, bool]]:
    if set(score_reports) != set(models):
        raise ValueError(
            "score-report models differ from blind-map models: "
            f"expected={models}, actual={sorted(score_reports)}"
        )
    expected_cases = set(case_ids)
    result: dict[str, dict[str, bool]] = {}
    report_contracts: set[str] = set()
    for model_id in models:
        report = child_map(score_reports[model_id])
        schema_version = str(report.get("schema_version") or "")
        if schema_version not in _HISTORICAL_SCORE_REPORT_SCHEMAS:
            raise ValueError(
                f"unsupported score-report schema for {model_id}: "
                f"{report.get('schema_version')!r}"
            )
        scorer_contract = child_map(report.get("scorer_contract"))
        if schema_version == "harness-score-report/v3" and not scorer_contract.get("sha256"):
            raise ValueError(f"score report for {model_id} is missing scorer_contract")
        if schema_version == "harness-score-report/v3":
            contract_payload = dict(scorer_contract)
            claimed_hash = str(contract_payload.pop("sha256"))
            if contract_sha256(contract_payload) != claimed_hash:
                raise ValueError(f"invalid scorer_contract hash for {model_id}")
        report_contracts.add(json.dumps(
            {
                "schema_version": schema_version,
                "scorer_contract": scorer_contract,
            },
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ))
        raw_scores = report.get("scores")
        if not isinstance(raw_scores, list):
            raise ValueError(f"score report for {model_id} is missing scores")
        by_case: dict[str, bool] = {}
        for raw_score in raw_scores:
            score = child_map(raw_score)
            case_id = str(score.get("case_id") or "")
            if not case_id or case_id in by_case:
                raise ValueError(f"invalid or duplicate score case for {model_id}: {case_id!r}")
            hard_pass = score.get("hard_pass")
            if not isinstance(hard_pass, bool):
                raise ValueError(f"invalid hard_pass for {model_id}.{case_id}")
            by_case[case_id] = hard_pass
        if set(by_case) != expected_cases:
            raise ValueError(
                f"human-label and score-report case sets differ for {model_id}: "
                f"labels_only={sorted(expected_cases - set(by_case))}, "
                f"scores_only={sorted(set(by_case) - expected_cases)}"
            )
        passed = sum(by_case.values())
        if report.get("case_count") != len(by_case):
            raise ValueError(f"case_count mismatch for {model_id}")
        if report.get("passed_count") != passed:
            raise ValueError(f"passed_count mismatch for {model_id}")
        if report.get("failed_count") != len(by_case) - passed:
            raise ValueError(f"failed_count mismatch for {model_id}")
        result[model_id] = by_case
    if len(report_contracts) != 1:
        raise ValueError("score reports use different scorer contracts")
    return result


def _binary_summary(passed: int, total: int) -> JsonMap:
    return {
        "passed_count": passed,
        "failed_count": total - passed,
        "total_count": total,
        "pass_rate": _rate(passed, total),
    }


def _grounding_summary(counts: Counter[str]) -> JsonMap:
    passed = counts["pass"]
    failed = counts["fail"]
    not_applicable = counts["not_applicable"]
    applicable = passed + failed
    return {
        "passed_count": passed,
        "failed_count": failed,
        "not_applicable_count": not_applicable,
        "applicable_count": applicable,
        "pass_rate": _rate(passed, applicable),
    }


def _confusion_key(conformance: bool, semantic_pass: bool) -> str:
    if conformance and semantic_pass:
        return "true_positive"
    if not conformance and semantic_pass:
        return "false_negative"
    if conformance and not semantic_pass:
        return "false_positive"
    return "true_negative"


def _rate(numerator: int, denominator: int) -> float:
    return numerator / denominator if denominator else 0.0


def _fraction(values: JsonMap, numerator: str, denominator: str) -> str:
    count = int(values[numerator])
    total = int(values[denominator])
    return f"`{count}/{total} = {_percent(_rate(count, total))}`"


def _percent(rate: float) -> str:
    return f"{rate * 100:.1f}%"


def _markdown_text(value: str) -> str:
    return " ".join(value.split()).replace("|", "\\|")


def _parse_score_report_paths(raw_values: list[str]) -> dict[str, Path]:
    paths: dict[str, Path] = {}
    for raw_value in raw_values:
        if "=" not in raw_value:
            raise ValueError(f"score report must use MODEL=PATH: {raw_value!r}")
        model_id, raw_path = raw_value.split("=", 1)
        model_id = model_id.strip()
        raw_path = raw_path.strip()
        if not model_id or not raw_path or model_id in paths:
            raise ValueError(f"invalid or duplicate score report: {raw_value!r}")
        paths[model_id] = Path(raw_path)
    if not paths:
        raise ValueError("at least one --score-report MODEL=PATH is required")
    return paths


def _load_json(path: Path) -> JsonMap:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"JSON root must be an object: {path}")
    return data


def _load_yaml(path: Path) -> JsonMap:
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    if not isinstance(data, dict):
        raise ValueError(f"YAML root must be a mapping: {path}")
    return data


def _display_path(path: Path, relative_to: Path | None) -> str:
    if relative_to is not None:
        try:
            return path.relative_to(relative_to).as_posix()
        except ValueError:
            pass
    return str(path)


def _write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


if __name__ == "__main__":
    raise SystemExit(main())
