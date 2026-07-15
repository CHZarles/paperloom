#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

import yaml

REPO_ROOT = Path(__file__).resolve().parents[2]
ROOT = Path(__file__).resolve().parent / "corpora" / "llm-agent-evaluation"
PACK_PATH = Path(__file__).resolve().parent / "paper-packs" / "llm-agent-evaluation.yaml"
JAVA_MAIN = "io.github.chzarles.paperloom.eval.GoldenReadingModelBuildCli"
sys.path.insert(0, str(REPO_ROOT))

from harness_py.corpus.pages import contains_normalized_phrase, normalize_text, page_matches

PAPERS = {
    "react_2023": {
        "title": "ReAct: Synergizing Reasoning and Acting in Language Models",
        "arxiv": "2210.03629v3",
    },
    "agentbench_2024": {
        "title": "AgentBench: Evaluating LLMs as Agents",
        "arxiv": "2308.03688v3",
    },
    "gaia_2024": {
        "title": "GAIA: a benchmark for General AI Assistants",
        "arxiv": "2311.12983v1",
    },
    "webarena_2024": {
        "title": "WebArena: A Realistic Web Environment for Building Autonomous Agents",
        "arxiv": "2307.13854v4",
    },
    "swebench_2024": {
        "title": "SWE-bench: Can Language Models Resolve Real-World GitHub Issues?",
        "arxiv": "2310.06770v3",
    },
    "mint_2024": {
        "title": "MINT: Evaluating LLMs in Multi-turn Interaction with Tools and Language Feedback",
        "arxiv": "2309.10691v3",
    },
    "agentboard_2024": {
        "title": "AgentBoard: An Analytical Evaluation Board of Multi-turn LLM Agents",
        "arxiv": "2401.13178v2",
    },
    "tau_bench_2024": {
        "title": "tau-bench: A Benchmark for Tool-Agent-User Interaction in Real-World Domains",
        "arxiv": "2406.12045v1",
    },
    "toolsandbox_2024": {
        "title": "ToolSandbox: A Stateful, Conversational, Interactive Evaluation Benchmark for LLM Tool Use Capabilities",
        "arxiv": "2408.04682v2",
    },
}


def main() -> None:
    args = _arguments()
    selected = args.paper_id or list(PAPERS)
    unknown = sorted(set(selected) - set(PAPERS))
    if unknown:
        raise SystemExit(f"unknown paper ids: {', '.join(unknown)}")
    if args.publish and set(selected) != set(PAPERS):
        raise SystemExit("--publish requires rebuilding all configured papers")

    pdf_dir = ROOT / "pdfs"
    staging_dir = args.staging_dir.resolve()
    for directory in (pdf_dir, staging_dir):
        directory.mkdir(parents=True, exist_ok=True)

    classpath = "" if args.validate_staging else _java_classpath()
    anchors_by_paper = _anchors_by_paper()
    inventory = []
    for paper_id in selected:
        metadata = PAPERS[paper_id]
        pdf = pdf_dir / f"{paper_id}.pdf"
        _download_pdf(pdf, metadata["arxiv"])
        output = staging_dir / f"{paper_id}.reading-model.json"
        if args.validate_staging:
            if not output.exists():
                raise RuntimeError(f"missing staged model: {output}")
            summary = _staged_model_summary(output)
        else:
            summary = _build_model(
                classpath=classpath,
                paper_id=paper_id,
                title=metadata["title"],
                pdf=pdf,
                output=output,
                mineru_base_url=args.mineru_base_url,
            )
        model = json.loads(output.read_text(encoding="utf-8"))
        audit = _validate_model(paper_id, model, anchors_by_paper.get(paper_id, []))
        inventory.append({
            **summary,
            "arxiv_version": metadata["arxiv"],
            "source_url": f"https://arxiv.org/pdf/{metadata['arxiv']}",
            "source_pdf_sha256": model["source_pdf_sha256"],
            "model_version": model["model_version"],
            "anchor_audit": audit,
        })
        print(json.dumps({"paper_id": paper_id, "status": "PASS", **audit}, sort_keys=True))

    audit_dir = staging_dir.parent / "generated-audit"
    audit_dir.mkdir(parents=True, exist_ok=True)
    inventory_path = audit_dir / "asset-inventory.json"
    inventory_path.write_text(
        json.dumps({"papers": inventory}, ensure_ascii=True, indent=2) + "\n",
        encoding="utf-8",
    )

    if args.publish:
        _publish(staging_dir, inventory_path)
    print(json.dumps({
        "paper_count": len(inventory),
        "staging_dir": str(staging_dir),
        "inventory": str(inventory_path),
        "published": bool(args.publish),
    }, sort_keys=True))


def _arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Build Golden Reading Models through the production MinerU pipeline."
    )
    parser.add_argument("--paper-id", action="append", choices=sorted(PAPERS))
    parser.add_argument(
        "--staging-dir",
        type=Path,
        default=ROOT / "staging-reading-models",
    )
    parser.add_argument(
        "--mineru-base-url",
        default=os.getenv("PAPERLOOM_MINERU_BASE_URL", "http://127.0.0.1:8000"),
    )
    parser.add_argument("--publish", action="store_true")
    parser.add_argument(
        "--validate-staging",
        action="store_true",
        help="Validate existing staged models without invoking MinerU again.",
    )
    return parser.parse_args()


def _download_pdf(pdf: Path, arxiv_version: str) -> None:
    if pdf.exists() and pdf.stat().st_size > 0:
        return
    subprocess.run(
        [
            "curl",
            "-fL",
            "--retry",
            "3",
            f"https://arxiv.org/pdf/{arxiv_version}",
            "-o",
            str(pdf),
        ],
        check=True,
    )


def _java_classpath() -> str:
    classpath_file = REPO_ROOT / "target" / "runtime-classpath.txt"
    subprocess.run(
        [
            "mvn",
            "-q",
            "-DskipTests",
            "compile",
            "dependency:build-classpath",
            f"-Dmdep.outputFile={classpath_file}",
            "-Dmdep.scope=runtime",
        ],
        cwd=REPO_ROOT,
        check=True,
    )
    return os.pathsep.join([
        str(REPO_ROOT / "target" / "classes"),
        classpath_file.read_text(encoding="utf-8").strip(),
    ])


def _build_model(
    *,
    classpath: str,
    paper_id: str,
    title: str,
    pdf: Path,
    output: Path,
    mineru_base_url: str,
) -> dict:
    command = [
        "java",
        "-cp",
        classpath,
        JAVA_MAIN,
        "--pack-id",
        "llm_agent_evaluation",
        "--paper-id",
        paper_id,
        "--model-version",
        f"rm_golden_{paper_id}_mineru_v2",
        "--pdf",
        str(pdf),
        "--output",
        str(output),
        "--artifacts-dir",
        str(output.parent.parent / "parser-artifacts" / paper_id),
        "--source-pdf-path",
        str(pdf.relative_to(REPO_ROOT)),
        "--title",
        title,
        "--mineru-base-url",
        mineru_base_url,
    ]
    result = subprocess.run(
        command,
        cwd=REPO_ROOT,
        check=True,
        text=True,
        capture_output=True,
    )
    lines = [line for line in result.stdout.splitlines() if line.strip()]
    if not lines:
        raise RuntimeError(f"{paper_id} builder returned no summary")
    return json.loads(lines[-1])


def _staged_model_summary(output: Path) -> dict:
    model = json.loads(output.read_text(encoding="utf-8"))
    return {
        "paperId": model.get("paper_id"),
        "output": str(output),
        "parserName": model.get("parser_name"),
        "parserVersion": model.get("parser_version"),
        "pageCount": len(model.get("pages") or []),
        "sectionCount": len(model.get("sections") or []),
        "readingElementCount": len(model.get("reading_elements") or []),
        "tableCount": len(model.get("parsed_tables") or []),
        "figureCount": len(model.get("parsed_figures") or []),
        "formulaCount": len(model.get("parsed_formulas") or []),
    }


def _anchors_by_paper() -> dict[str, list[dict]]:
    pack = yaml.safe_load(PACK_PATH.read_text(encoding="utf-8"))
    anchors: dict[str, list[dict]] = {}
    for anchor in pack.get("anchors", []):
        anchors.setdefault(str(anchor["paper"]), []).append(anchor)
    return anchors


def _validate_model(paper_id: str, model: dict, anchors: list[dict]) -> dict:
    if model.get("paper_id") != paper_id:
        raise RuntimeError(f"{paper_id}: exported paper_id mismatch")
    if model.get("parser_name") != "MinerU":
        raise RuntimeError(f"{paper_id}: expected MinerU parser")
    elements = list(model.get("reading_elements") or [])
    if not elements:
        raise RuntimeError(f"{paper_id}: no reading elements")
    pages = list(model.get("pages") or [])
    expected_page_count = int(model.get("pdf_page_count") or 0)
    if not pages or len(pages) != expected_page_count:
        raise RuntimeError(
            f"{paper_id}: physical page projection mismatch "
            f"({len(pages)} != {expected_page_count})"
        )
    diagnostics = dict(model.get("diagnostics") or {})
    if int(diagnostics.get("pagesBuiltFromPhysicalProjection") or 0) != expected_page_count:
        raise RuntimeError(f"{paper_id}: not all pages came from the physical projection")
    if int(diagnostics.get("pagesBuiltFromSemanticProjection") or 0) != 0:
        raise RuntimeError(f"{paper_id}: semantic elements were used as physical page text")
    sectioned = [
        item
        for item in elements
        if str(item.get("sectionTitle") or "").strip().lower() not in {"", "unsectioned"}
    ]
    headings = [
        item for item in elements
        if str(item.get("elementType") or "").upper() == "HEADING"
    ]
    element_types = sorted({
        str(item.get("elementType") or "").upper()
        for item in elements
        if item.get("elementType")
    })
    if not sectioned or not headings:
        raise RuntimeError(f"{paper_id}: production structure missing")
    orders = [
        int(item["readingOrder"])
        for item in elements
        if item.get("readingOrder") is not None
    ]
    if not orders or len(orders) != len(set(orders)):
        raise RuntimeError(f"{paper_id}: readingOrder must be present and unique")

    page_locations = {
        int(item["pageNumber"]): item
        for item in list(model.get("locations") or [])
        if str(item.get("locationType") or "").upper() == "PAGE"
        and item.get("pageNumber") is not None
    }
    if len(page_locations) != expected_page_count:
        raise RuntimeError(f"{paper_id}: PAGE location inventory mismatch")

    anchor_results = []
    for anchor in anchors:
        quote = normalize_text(str(anchor.get("quote") or ""))
        matched = []
        for page in pages:
            text = str(page.get("pageText") or "")
            if (
                text
                and page_matches(anchor.get("page"), page.get("pageNumber"))
                and contains_normalized_phrase(normalize_text(text), quote)
            ):
                location = page_locations.get(int(page["pageNumber"]))
                matched.append(str(location.get("locationRef")))
        unique = list(dict.fromkeys(matched))
        if len(unique) != 1:
            raise RuntimeError(
                f"{paper_id}: anchor {anchor['id']} matched {len(unique)} physical pages"
            )
        anchor_results.append({
            "anchor_id": anchor["id"],
            "location_ref": unique[0],
            "surface": "PAGE",
        })

    return {
        "reading_element_count": len(elements),
        "sectioned_element_count": len(sectioned),
        "heading_count": len(headings),
        "element_types": element_types,
        "anchor_count": len(anchor_results),
        "anchors": anchor_results,
    }


def _publish(staging_dir: Path, inventory_path: Path) -> None:
    target_dir = ROOT / "reading-models"
    target_dir.mkdir(parents=True, exist_ok=True)
    for paper_id in PAPERS:
        source = staging_dir / f"{paper_id}.reading-model.json"
        if not source.exists():
            raise RuntimeError(f"missing staged model: {source}")
    for paper_id in PAPERS:
        source = staging_dir / f"{paper_id}.reading-model.json"
        target = target_dir / source.name
        temporary = target.with_suffix(target.suffix + ".tmp")
        shutil.copy2(source, temporary)
        os.replace(temporary, target)
    target_inventory = ROOT / "generated-audit" / "asset-inventory.json"
    target_inventory.parent.mkdir(parents=True, exist_ok=True)
    temporary_inventory = target_inventory.with_suffix(target_inventory.suffix + ".tmp")
    shutil.copy2(inventory_path, temporary_inventory)
    os.replace(temporary_inventory, target_inventory)


if __name__ == "__main__":
    main()
