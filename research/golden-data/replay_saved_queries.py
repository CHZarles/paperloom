#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import yaml

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT))

from harness_py.corpus.tools import ReadingCorpusTools
from harness_py.evaluation.dataset import load_dataset


def main() -> None:
    args = _arguments()
    dataset = load_dataset(args.manifest)
    selected_case_ids = {
        str(item["id"])
        for item in yaml.safe_load(args.cases.read_text(encoding="utf-8"))["cases"]
    }
    rows = []
    hit_count = 0
    required_count = 0

    for case in dataset.cases:
        case_id = str(case["id"])
        if case_id not in selected_case_ids:
            continue
        required = list(
            case.get("expect", {}).get("evidence", {}).get("required", [])
        )
        if not required:
            continue
        trace_path = args.runs / case_id / "react_trace.json"
        trace = json.loads(trace_path.read_text(encoding="utf-8"))
        tools = ReadingCorpusTools(dataset)
        found: set[str] = set()
        query_call_count = 0

        for event in trace:
            tool_name = str(event.get("tool_name") or "")
            arguments = event.get("arguments") or {}
            if tool_name in {
                "search_paper_candidates",
                "find_papers_by_identity",
                "get_citation_edges",
            }:
                tools.call(tool_name, arguments)
                continue
            if tool_name != "find_reading_locations":
                continue
            query_call_count += 1
            result = tools.find_reading_locations(arguments)
            for location in result.get("locations", []):
                document = tools.documents_by_location.get(
                    str(location.get("location_ref") or "")
                )
                if document is not None:
                    found.update(document.matched_anchor_ids)

        missing = sorted(set(required) - found)
        case_hit_count = len(required) - len(missing)
        hit_count += case_hit_count
        required_count += len(required)
        rows.append({
            "case_id": case_id,
            "hits": case_hit_count,
            "required": len(required),
            "missing": missing,
            "query_calls": query_call_count,
        })

    report = {
        "schema_version": "harness-saved-query-replay/v1",
        "manifest": str(args.manifest),
        "runs": str(args.runs),
        "candidate_hits": hit_count,
        "candidate_required": required_count,
        "candidate_recall": hit_count / required_count if required_count else 0.0,
        "cases": rows,
    }
    encoded = json.dumps(report, ensure_ascii=True, indent=2) + "\n"
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(encoded, encoding="utf-8")
    print(encoded, end="")


def _arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Replay saved find_reading_locations queries against the current corpus."
    )
    parser.add_argument(
        "--manifest",
        type=Path,
        default=REPO_ROOT / "research/golden-data/manifest-expanded.yaml",
    )
    parser.add_argument(
        "--cases",
        type=Path,
        default=REPO_ROOT / "research/golden-data/cases/llm-agent-evaluation.yaml",
    )
    parser.add_argument(
        "--runs",
        type=Path,
        default=(
            REPO_ROOT
            / "research/golden-data/validation-runs/2026-07-13-llm-agent-evaluation-v1"
        ),
    )
    parser.add_argument("--out", type=Path)
    return parser.parse_args()


if __name__ == "__main__":
    main()
