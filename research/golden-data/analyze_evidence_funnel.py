#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT))

from harness_py.core.models import JsonMap, as_list, child_map
from harness_py.corpus.pages import contains_normalized_phrase, normalize_text, page_matches
from harness_py.corpus.tools import ReadingCorpusTools
from harness_py.evaluation.dataset import load_dataset
from harness_py.evaluation.golden_case import case_expect
from harness_py.evaluation.scoring import BehaviorScorer


SCHEMA_VERSION = "harness-evidence-funnel/v1"


def main() -> int:
    args = _arguments()
    dataset = load_dataset(args.manifest)
    selected = set(args.case_id)
    rows: list[JsonMap] = []
    scorer = BehaviorScorer()
    corpus = ReadingCorpusTools(dataset)
    anchors_by_paper: dict[str, list[JsonMap]] = {}
    for anchor in dataset.anchors_by_id.values():
        anchors_by_paper.setdefault(str(anchor.get("paper_id") or ""), []).append(anchor)

    for case in dataset.cases:
        case_id = str(case.get("id") or "")
        if selected and case_id not in selected:
            continue
        run_path = args.runs / case_id / "harness_run.json"
        if not run_path.exists():
            continue
        run = json.loads(run_path.read_text(encoding="utf-8"))
        score = scorer.score_case(dataset, case, run).to_dict()
        rows.append(_analyze_case(corpus, anchors_by_paper, case, run, score, args.eval_dump))

    report = _report(args, rows)
    encoded = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(encoded, encoding="utf-8")
    if args.markdown_out:
        args.markdown_out.parent.mkdir(parents=True, exist_ok=True)
        args.markdown_out.write_text(_markdown(report), encoding="utf-8")
    print(encoded, end="")
    return 0


def _analyze_case(
    tools: ReadingCorpusTools,
    anchors_by_paper: dict[str, list[JsonMap]],
    case: JsonMap,
    run: JsonMap,
    score: JsonMap,
    eval_dump: Path | None,
) -> JsonMap:
    required = {
        str(anchor_id)
        for anchor_id in as_list(child_map(case_expect(case).get("evidence")).get("required"))
        if anchor_id
    }
    candidate: set[str] = set()
    read: set[str] = set()
    cited: set[str] = set()
    query_count = 0
    tools.authorized_paper_ids.clear()
    tools.disclosed_location_refs.clear()
    tools.observations_by_evidence_id.clear()

    for event in as_list(run.get("react_trace")):
        trace = child_map(event)
        name = str(trace.get("tool_name") or "")
        arguments = child_map(trace.get("arguments"))
        if name in {"search_paper_candidates", "find_papers_by_identity", "get_citation_edges"}:
            tools.call(name, arguments)
            continue
        if name != "find_reading_locations":
            continue
        query_count += 1
        # Candidate analysis measures ranking, not whether the historical turn
        # obtained paper authorization in exactly the same way.
        tools.authorized_paper_ids.update(
            str(value) for value in as_list(arguments.get("paper_ids")) if value
        )
        replayed = tools.find_reading_locations(arguments)
        for raw in as_list(replayed.get("locations")):
            location_ref = str(child_map(raw).get("location_ref") or "")
            document = tools.documents_by_location.get(location_ref)
            if document is not None:
                candidate.update(document.matched_anchor_ids)

    ledger = [
        child_map(item)
        for item in as_list(child_map(run.get("evidence_ledger")).get("items"))
    ]
    evidence_by_id = {
        str(item.get("evidence_id")): item
        for item in ledger
        if item.get("evidence_id")
    }
    for item in ledger:
        read.update(_evidence_anchors(item, anchors_by_paper))
    answer = child_map(run.get("research_answer"))
    for evidence_id in as_list(answer.get("cited_evidence_ids")):
        item = evidence_by_id.get(str(evidence_id))
        if item:
            cited.update(_evidence_anchors(item, anchors_by_paper))

    # Prior-turn evidence may be directly available without a current-turn find call.
    read.update(cited)
    candidate.update(read)
    candidate_hits = required & candidate
    read_hits = required & read
    cited_hits = required & cited
    technical = run.get("status") == "FAILED_TECHNICAL" or run.get("result_status") == "FAILED_TECHNICAL"
    stage = _stage(required, candidate_hits, read_hits, cited_hits, score, technical)
    validation = _validation_stats(eval_dump, str(run.get("run_id") or ""))
    return {
        "case_id": str(case.get("id") or ""),
        "stage": stage,
        "hard_pass": bool(score.get("hard_pass")),
        "required_anchor_count": len(required),
        "candidate_hit_count": len(candidate_hits),
        "read_hit_count": len(read_hits),
        "cited_hit_count": len(cited_hits),
        "missing_candidate_anchor_ids": sorted(required - candidate_hits),
        "candidate_not_read_anchor_ids": sorted(candidate_hits - read_hits),
        "read_not_cited_anchor_ids": sorted(read_hits - cited_hits),
        "candidate_query_count": query_count,
        "answer_validation_attempts": validation["attempts"],
        "answer_validation_rejections": validation["rejections"],
        "answer_validation_errors": validation["errors"],
        "dimensions": score.get("dimensions", {}),
    }


def _evidence_anchors(
    item: JsonMap,
    anchors_by_paper: dict[str, list[JsonMap]],
) -> set[str]:
    anchors = {
        str(value)
        for value in as_list(item.get("matched_anchor_ids"))
        if value
    }
    if item.get("matched_anchor_id"):
        anchors.add(str(item["matched_anchor_id"]))
    paper_id = str(item.get("paper_id") or "")
    normalized_text = normalize_text(str(item.get("span_text") or ""))
    for anchor in anchors_by_paper.get(paper_id, []):
        element = child_map(anchor.get("element"))
        selector = child_map(anchor.get("selector"))
        quote = normalize_text(str(selector.get("exact_text") or ""))
        if page_matches(element.get("page"), item.get("page")) and contains_normalized_phrase(
            normalized_text,
            quote,
        ):
            anchors.add(str(anchor.get("anchor_id") or ""))
    return anchors


def _stage(
    required: set[str],
    candidate: set[str],
    read: set[str],
    cited: set[str],
    score: JsonMap,
    technical: bool,
) -> str:
    if technical:
        return "technical_error"
    if required - candidate:
        return "candidate_missing"
    if required - read:
        return "candidate_present_not_read"
    if required - cited:
        return "read_not_cited"
    dimensions = child_map(score.get("dimensions"))
    if child_map(dimensions.get("outcome")).get("status") == "fail":
        return "cited_but_outcome_failed"
    if not score.get("hard_pass"):
        return "cited_but_content_failed"
    return "hard_pass"


def _validation_stats(eval_dump: Path | None, run_id: str) -> JsonMap:
    path = eval_dump / run_id / "events.jsonl" if eval_dump and run_id else None
    if path is None or not path.exists():
        return {"attempts": 0, "rejections": 0, "errors": []}
    attempts = 0
    rejections = 0
    errors: list[str] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        if event.get("kind") != "answer.validation":
            continue
        attempts += 1
        payload = child_map(event.get("payload"))
        if not payload.get("accepted"):
            rejections += 1
            error = str(payload.get("validation_error") or "")
            if error and error not in errors:
                errors.append(error)
    return {"attempts": attempts, "rejections": rejections, "errors": errors}


def _report(args: argparse.Namespace, rows: list[JsonMap]) -> JsonMap:
    required = sum(int(row["required_anchor_count"]) for row in rows)
    candidates = sum(int(row["candidate_hit_count"]) for row in rows)
    reads = sum(int(row["read_hit_count"]) for row in rows)
    cited = sum(int(row["cited_hit_count"]) for row in rows)
    stages = Counter(str(row["stage"]) for row in rows)
    technical_errors = stages["technical_error"]
    completed_cases = len(rows) - technical_errors
    return {
        "schema_version": SCHEMA_VERSION,
        "manifest": str(args.manifest),
        "runs": str(args.runs),
        "eval_dump": str(args.eval_dump) if args.eval_dump else None,
        "case_count": len(rows),
        "completed_case_count": completed_cases,
        "technical_error_count": technical_errors,
        "required_anchor_count": required,
        "candidate_hit_count": candidates,
        "read_hit_count": reads,
        "cited_hit_count": cited,
        "candidate_recall": _ratio(candidates, required),
        "read_conversion": _ratio(reads, candidates),
        "cited_conversion": _ratio(cited, reads),
        "hard_pass_rate": _ratio(stages["hard_pass"], completed_cases),
        "stage_counts": dict(sorted(stages.items())),
        "cases": rows,
    }


def _markdown(report: JsonMap) -> str:
    lines = [
        "# Evidence Funnel Report",
        "",
        f"- Cases: `{report['case_count']}`",
        f"- Technical Errors: `{report['technical_error_count']}`",
        f"- Candidate Recall: `{report['candidate_hit_count']}/{report['required_anchor_count']}`",
        f"- Read Conversion: `{report['read_conversion']:.1%}`",
        f"- Cited Conversion: `{report['cited_conversion']:.1%}`",
        f"- Hard Pass Rate: `{report['hard_pass_rate']:.1%}`",
        "",
        "| Case | Stage | Candidate | Read | Cited | Hard Pass |",
        "| --- | --- | ---: | ---: | ---: | --- |",
    ]
    for row in as_list(report.get("cases")):
        item = child_map(row)
        required = int(item.get("required_anchor_count") or 0)
        lines.append(
            f"| `{item.get('case_id')}` | `{item.get('stage')}` | "
            f"{item.get('candidate_hit_count')}/{required} | "
            f"{item.get('read_hit_count')}/{required} | "
            f"{item.get('cited_hit_count')}/{required} | "
            f"{'yes' if item.get('hard_pass') else 'no'} |"
        )
    return "\n".join(lines) + "\n"


def _ratio(numerator: int, denominator: int) -> float:
    return numerator / denominator if denominator else 0.0


def _arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Classify Golden run failures across Candidate, Read, Cited, and outcome stages."
    )
    parser.add_argument(
        "--manifest",
        type=Path,
        default=REPO_ROOT / "research/golden-data/manifest-expanded.yaml",
    )
    parser.add_argument("--runs", type=Path, required=True)
    parser.add_argument("--eval-dump", type=Path)
    parser.add_argument("--case-id", action="append", default=[])
    parser.add_argument("--out", type=Path)
    parser.add_argument("--markdown-out", type=Path)
    return parser.parse_args()


if __name__ == "__main__":
    raise SystemExit(main())
