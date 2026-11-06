#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import json
import math
import subprocess
from pathlib import Path


def load(path: Path) -> dict[str, object]:
    return json.loads(path.read_text(encoding="utf-8"))


def percentile(values: list[float], value: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * value / 100
    lower, upper = math.floor(position), math.ceil(position)
    if lower == upper:
        return ordered[lower]
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def stats(values: list[float]) -> dict[str, float | int | None]:
    return {
        "count": len(values),
        "mean": round(sum(values) / len(values), 3) if values else None,
        "p50": round(percentile(values, 50), 3) if values else None,
        "p95": round(percentile(values, 95), 3) if values else None,
        "max": round(max(values), 3) if values else None,
    }


def pdf_pages(path: Path) -> int | None:
    result = subprocess.run(
        ["mdls", "-raw", "-name", "kMDItemNumberOfPages", str(path)],
        text=True,
        capture_output=True,
        check=False,
    )
    try:
        return int(result.stdout.strip())
    except ValueError:
        return None


def write_csv(path: Path, rows: list[dict[str, object]]) -> None:
    if not rows:
        path.write_text("\n", encoding="utf-8")
        return
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def verify_location(
    item: dict[str, object],
    read_items_by_quote_ref: dict[str, dict[str, object]],
) -> tuple[bool, str, object]:
    paper_id = str(item.get("paper_id") or "")
    location_ref = str(item.get("location_ref") or item.get("location") or "")
    source_quote_ref = str(item.get("source_quote_ref") or "")
    if not paper_id or not location_ref or not source_quote_ref:
        return False, "missing_identity", None
    observed = read_items_by_quote_ref.get(source_quote_ref)
    if not observed:
        return False, "citation_missing_from_read_trace", None
    passed = (
        str(observed.get("paper_id") or "") == paper_id
        and observed.get("page") == item.get("page")
        and str(observed.get("location_ref") or observed.get("location") or "") == location_ref
        and bool(observed.get("pdf_evidence_available"))
        and bool(observed.get("page_screenshot_available"))
    )
    return passed, "verified_current_agent_read_trace" if passed else "identity_mismatch", observed.get("page")


def build(args: argparse.Namespace) -> dict[str, object]:
    run = load(args.run_dir / "run.json")
    snapshot = load(args.snapshot)
    ws_complete = load(args.ws_complete)
    ws_cancel = load(args.ws_cancel)
    idempotency = load(args.idempotency)
    citation_review = load(args.citation_review)
    concurrent_parse = load(args.concurrent_parse) if args.concurrent_parse else None
    reviewed_citations = {
        (str(row.get("case_id")), str(row.get("source_quote_ref"))): row
        for row in citation_review.get("reviews") or []
        if isinstance(row, dict)
    }
    assessments = {
        str(row.get("case_id")): row
        for row in (run.get("l3") or {}).get("cases") or []
    }
    snapshot_cases = {
        str(row.get("case_id")): row
        for row in snapshot.get("agent_cases") or []
        if isinstance(row, dict)
    }
    case_rows: list[dict[str, object]] = []
    citation_rows: list[dict[str, object]] = []

    for path in sorted((args.run_dir / "agent").glob("*.json")):
        agent = load(path)
        case_id = str(agent.get("case_id") or path.stem)
        assessment = assessments.get(case_id, {})
        judge = assessment.get("judge") or {}
        target_checks = assessment.get("target_checks") or []
        cited_refs = (agent.get("final_answer") or {}).get("cited_source_quote_refs") or []
        trace = [event for event in agent.get("react_trace") or [] if isinstance(event, dict)]
        search_top_k = [
            {
                "tool_name": event.get("tool_name"),
                "arguments": event.get("arguments"),
                "result": event.get("result"),
            }
            for event in trace
            if event.get("tool_name") in {"search_paper_candidates", "search_paper_content"}
        ]
        read_items = [
            item
            for event in trace
            if event.get("tool_name") == "read_paper_content"
            for item in (event.get("result") or {}).get("items") or []
            if isinstance(item, dict)
        ]
        case_rows.append({
            "case_id": case_id,
            "case_type": snapshot_cases.get(case_id, {}).get("case_type"),
            "question": snapshot_cases.get(case_id, {}).get("question"),
            "status": agent.get("status"),
            "outcome": (agent.get("final_answer") or {}).get("outcome"),
            "final_answer_markdown": (agent.get("final_answer") or {}).get("markdown"),
            "citation_count": len(cited_refs),
            "citation_integrity": assessment.get("citation_integrity"),
            "grounding_judge": judge.get("grounding"),
            "answer_quality_judge": judge.get("answer_quality"),
            "required_target_count": len(target_checks),
            "required_targets_read": sum(bool(row.get("read")) for row in target_checks),
            "required_targets_cited": sum(bool(row.get("cited")) for row in target_checks),
            "elapsed_ms": (agent.get("control") or {}).get("usage", {}).get("elapsed_ms"),
            "model_calls": (agent.get("control") or {}).get("usage", {}).get("model_calls"),
            "total_tokens": (agent.get("control") or {}).get("usage", {}).get("total_tokens"),
            "read_location_refs": json.dumps([
                item.get("location_ref") or item.get("location") for item in read_items
            ], ensure_ascii=False),
            "search_top_k": json.dumps(search_top_k, ensure_ascii=False),
            "source_agent_artifact": str(path),
        })
        ledger = {
            str(item.get("source_quote_ref")): item
            for item in (agent.get("evidence_ledger") or {}).get("items") or []
            if isinstance(item, dict) and item.get("source_quote_ref")
        }
        read_items_by_quote_ref: dict[str, dict[str, object]] = {}
        for read_item in read_items:
            for quote in read_item.get("source_quotes") or []:
                if isinstance(quote, dict) and quote.get("source_quote_ref"):
                    read_items_by_quote_ref[str(quote["source_quote_ref"])] = read_item
        proxy_supported = judge.get("grounding") == "PASS" and bool(assessment.get("citation_integrity"))
        for source_quote_ref in cited_refs:
            item = ledger.get(str(source_quote_ref), {})
            location_passed, location_detail, observed_page = verify_location(item, read_items_by_quote_ref)
            explicit_review = reviewed_citations.get((case_id, str(source_quote_ref)))
            supported = proxy_supported or bool(explicit_review and explicit_review.get("verdict") == "supported")
            citation_rows.append({
                "case_id": case_id,
                "source_quote_ref": source_quote_ref,
                "paper_id": item.get("paper_id"),
                "title": item.get("title"),
                "page": item.get("page"),
                "section": item.get("section"),
                "location_ref": item.get("location_ref") or item.get("location"),
                "support_proxy": "supported" if supported else "unsupported_or_unverified",
                "support_basis": "ai_assistant_evidence_review" if explicit_review
                else "case_level_grounding_judge_and_citation_integrity",
                "support_rationale": explicit_review.get("rationale") if explicit_review else "",
                "human_reviewed": False,
                "location_success": location_passed,
                "location_verification": location_detail,
                "read_trace_page": observed_page,
                "pdf_evidence_available": item.get("pdf_evidence_available"),
                "source_span_present": bool(item.get("source_span_json")),
                "evidence_text": item.get("content") or item.get("span_text"),
            })

    retrieval_targets = (run.get("l2") or {}).get("targets") or []
    retrieval_rows = [{
        "target_id": row.get("target_id"),
        "location_type": row.get("location_type"),
        "rank": row.get("rank"),
        "recall_at_5": isinstance(row.get("rank"), int) and row["rank"] <= 5,
        "recall_at_10": isinstance(row.get("rank"), int) and row["rank"] <= 10,
        "exact_read_passed": row.get("exact_read_passed"),
        "returned_location_refs": json.dumps(row.get("returned_location_refs") or [], ensure_ascii=False),
    } for row in retrieval_targets]

    page_rows = []
    for paper in sorted(args.papers_dir.glob("*.pdf")):
        pages = pdf_pages(paper)
        page_rows.append({
            "filename": paper.name,
            "pages": pages,
            "size_bucket": "small_<=25" if pages is not None and pages <= 25
            else "medium_26_99" if pages is not None and pages < 100
            else "large_100_299" if pages is not None and pages < 300
            else "xlarge_300_plus" if pages is not None else "unknown",
        })

    applicable_cases = [row for row in case_rows if int(row["required_target_count"] or 0) > 0]
    total_citations = len(citation_rows)
    supported_citations = sum(row["support_proxy"] == "supported" for row in citation_rows)
    located_citations = sum(bool(row["location_success"]) for row in citation_rows)
    parse_ready = int((run.get("l0") or {}).get("ready") or 0)
    parse_configured = int((run.get("l0") or {}).get("configured") or 0)
    summary_data = {
        "schema_version": "paperloom-quantitative-evaluation/v1",
        "run_id": run.get("run_id"),
        "code_revision": run.get("code_revision"),
        "dataset": {
            "pdf_count": len(page_rows),
            "total_pages": sum(int(row["pages"] or 0) for row in page_rows),
            "page_buckets": {
                bucket: sum(row["size_bucket"] == bucket for row in page_rows)
                for bucket in ("small_<=25", "medium_26_99", "large_100_299", "xlarge_300_plus", "unknown")
            },
            "human_authored_golden_case_count": 24,
            "frozen_generated_target_count": len((snapshot.get("targets") or {})),
            "standard_evidence_question_inventory": 24 + len((snapshot.get("targets") or {})),
            "executed_retrieval_question_count": len(retrieval_rows),
            "executed_agent_case_count": len(case_rows),
            "generated_citation_count": total_citations,
        },
        "p0": {
            "citation_accuracy": {
                "value": supported_citations / total_citations if total_citations else None,
                "numerator": supported_citations,
                "denominator": total_citations,
                "status": "provisional_proxy_not_human_reviewed",
            },
            "unsupported_citation_rate": {
                "value": (total_citations - supported_citations) / total_citations if total_citations else None,
                "numerator": total_citations - supported_citations,
                "denominator": total_citations,
                "status": "provisional_proxy_not_human_reviewed",
            },
            "citation_location_success_rate": {
                "value": located_citations / total_citations if total_citations else None,
                "numerator": located_citations,
                "denominator": total_citations,
                "status": "verified_current_agent_read_trace",
            },
            "retrieval_recall_at_5": (run.get("l2") or {}).get("metrics", {}).get("recall_at_5"),
            "retrieval_recall_at_10": (run.get("l2") or {}).get("metrics", {}).get("recall_at_10"),
            "evidence_read_coverage": {
                "value": sum(int(row["required_targets_read"]) == int(row["required_target_count"]) for row in applicable_cases)
                / len(applicable_cases) if applicable_cases else None,
                "numerator": sum(int(row["required_targets_read"]) == int(row["required_target_count"]) for row in applicable_cases),
                "denominator": len(applicable_cases),
            },
            "ttft_ms": ws_complete.get("metrics", {}).get("ttft_ms"),
        },
        "p0_failure_details": {
            "citation_location_failures": [
                {
                    "case_id": row["case_id"],
                    "source_quote_ref": row["source_quote_ref"],
                    "ledger_page": row["page"],
                    "read_trace_page": row["read_trace_page"],
                    "location_ref": row["location_ref"],
                }
                for row in citation_rows if not row["location_success"]
            ],
            "evidence_read_missed_cases": [
                {
                    "case_id": row["case_id"],
                    "read": row["required_targets_read"],
                    "required": row["required_target_count"],
                }
                for row in applicable_cases
                if int(row["required_targets_read"]) != int(row["required_target_count"])
            ],
        },
        "p1": {
            "complete_answer_latency_ms": ws_complete.get("metrics", {}).get("complete_ms"),
            "cancel_latency_ms": ws_cancel.get("metrics", {}).get("cancel_ms"),
            "parse_and_index_latency_ms": {"status": "not_executed", "reason": "没有 300+ 页 PDF；重新执行至少 5 轮 MinerU 解析会消耗外部服务配额。历史时间戳受批量重建影响，未用于冒充本轮耗时。"},
            "parse_success_rate": {
                "value": parse_ready / parse_configured if parse_configured else None,
                "numerator": parse_ready,
                "denominator": parse_configured,
                "status": "current_state_cross_section_not_fresh_repetitions",
            },
            "message_retry_idempotency": idempotency,
        },
        "p2": {
            "concurrent_parse": {
                "status": "executed",
                "source": concurrent_parse.get("source"),
                "repetitions": concurrent_parse.get("repetitions"),
                "sample_count": concurrent_parse.get("sample_count"),
                "runtime_topology": concurrent_parse.get("runtime_topology"),
                "summary": concurrent_parse.get("summary"),
            } if concurrent_parse else {
                "status": "not_executed",
                "reason": "没有可用的并发解析运行结果。",
            }
        },
        "agent_elapsed_ms": stats([float(row["elapsed_ms"]) for row in case_rows if row.get("elapsed_ms") is not None]),
        "limitations": [
            "只有 24 条人工编写 Golden Cases；另外 31 条是模型生成并冻结的证据 Target，因此 55 条库存不等于 55 条人工标注。",
            "PDF 共 31 篇，比要求上限多 1 篇，且没有 300+ 页文档。",
            "引用支持性主要使用现有 Case 级 Grounding Judge 作为逐引用代理，2 条额外引用由 AI 助手核对；正式人工逐引用复核仍未完成。",
            "TTFT 和完整回答延迟来自 5 次 WebSocket 直接问候；检索与回答质量来自另一轮 PaperLoom-31 运行。",
            "并发解析使用 5 页 PDF 和 MinerU 云服务；结果反映该样本及当时外部服务状态，不能外推到 300+ 页 PDF。",
        ],
    }
    raw = {
        "summary": summary_data,
        "cases": case_rows,
        "citations": citation_rows,
        "retrieval": retrieval_rows,
        "pdfs": page_rows,
        "websocket_complete": ws_complete,
        "websocket_cancel": ws_cancel,
        "concurrent_parse": concurrent_parse,
    }
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "raw-results.json").write_text(json.dumps(raw, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (args.out / "summary.json").write_text(json.dumps(summary_data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    write_csv(args.out / "cases.csv", case_rows)
    write_csv(args.out / "citations.csv", citation_rows)
    write_csv(args.out / "retrieval.csv", retrieval_rows)
    write_csv(args.out / "pdfs.csv", page_rows)
    answers_by_case = {str(row["case_id"]): row.get("final_answer_markdown") for row in case_rows}
    write_csv(args.out / "human-citation-review.csv", [{
        "case_id": row["case_id"],
        "source_quote_ref": row["source_quote_ref"],
        "answer_markdown": answers_by_case.get(str(row["case_id"])),
        "paper_id": row["paper_id"],
        "title": row["title"],
        "ledger_page": row["page"],
        "read_trace_page": row["read_trace_page"],
        "location_ref": row["location_ref"],
        "evidence_text": row["evidence_text"],
        "automated_support_suggestion": row["support_proxy"],
        "human_support_verdict": "",
        "human_location_verdict": "",
        "human_notes": "",
        "reviewer": "",
        "reviewed_at": "",
    } for row in citation_rows])
    paper_titles = {
        str(key): value.get("title")
        for key, value in (snapshot.get("papers") or {}).items()
        if isinstance(value, dict)
    }
    write_csv(args.out / "golden-target-human-review.csv", [{
        "target_id": target_id,
        "paper_key": target.get("paper"),
        "paper_title": paper_titles.get(str(target.get("paper"))),
        "product_paper_id": target.get("product_paper_id"),
        "page": target.get("page"),
        "location_type": target.get("location_type"),
        "location_ref": target.get("location_ref"),
        "question": target.get("query"),
        "expected_answer": target.get("expected_answer"),
        "evidence_content": target.get("content"),
        "human_question_valid": "",
        "human_answer_valid": "",
        "human_evidence_valid": "",
        "human_page_valid": "",
        "human_notes": "",
        "reviewer": "",
        "reviewed_at": "",
    } for target_id, target in sorted((snapshot.get("targets") or {}).items()) if isinstance(target, dict)])
    latency_rows = []
    for source, document in (("complete", ws_complete), ("cancel", ws_cancel)):
        for index, row in enumerate(document.get("runs") or [], 1):
            latency_rows.append({
                "source": source,
                "iteration": index,
                "success": row.get("success"),
                "ttft_ms": row.get("ttft_ms"),
                "first_content_ms": row.get("first_content_ms"),
                "complete_ms": row.get("complete_ms"),
                "cancel_ms": row.get("cancel_ms"),
            })
    write_csv(args.out / "latency.csv", latency_rows)
    (args.out / "report.md").write_text(render_markdown(summary_data), encoding="utf-8")
    (args.out / "HUMAN_REVIEW_GUIDE.md").write_text(HUMAN_REVIEW_GUIDE, encoding="utf-8")
    return summary_data


def percent(value: object) -> str:
    return "N/A" if value is None else f"{float(value) * 100:.2f}%"


HUMAN_REVIEW_GUIDE = """# 人工复核指南

## 引用复核

打开 `human-citation-review.csv`，逐行填写：

- `human_support_verdict`：只能填 `supported`、`unsupported` 或 `uncertain`。
- `human_location_verdict`：只能填 `pass` 或 `fail`；核对 PDF、页码、位置和证据文本。
- `human_notes`：`unsupported`、`uncertain` 或定位失败时必须说明原因。
- `reviewer`、`reviewed_at`：填写复核人和 ISO 8601 时间。

判断单位是引用所附着的具体论述，而不是整篇回答。证据只支持论文身份、背景或邻近事实时，不能判为
`supported`。同一引用同时支撑多个论述时，任一实质论述缺少支持就判为 `unsupported`。

## Golden Target 复核

打开 `golden-target-human-review.csv`，逐行把四个 `human_*_valid` 字段填写为 `true` 或 `false`：

- 问题必须清晰、只有基于所列证据可回答的含义；
- Expected Answer 不得超出证据；
- Evidence Content 必须包含完整标准证据；
- PDF 页码必须与实际页面一致。

任一项为 `false` 的 Target 不得计入人工 Golden Case。31 条全部通过后，才能把当前 24 条人工 Case
与这些 Target 合并为 55 条人工复核题目。
"""


def render_markdown(data: dict[str, object]) -> str:
    dataset, p0, p1, p2 = data["dataset"], data["p0"], data["p1"], data["p2"]
    concurrent = p2["concurrent_parse"]
    if concurrent["status"] == "executed":
        topology = concurrent["runtime_topology"]
        concurrent_text = (
            "### 并发解析\n\n"
            f"使用 {topology['kafka_topic_partitions']} 个 Kafka 分区、"
            f"{topology['listener_concurrency']} 个 Consumer；共 {concurrent['sample_count']} 个全新任务。"
            "逐任务记录见 `concurrent-parse.json` 和 `concurrent-parse.csv`。\n\n"
            "| 并发任务 | 样本数 | 成功率 | P50 | P95 | 最大值 | 每批实际分区数 |\n"
            "| ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n"
            + "\n".join(
                f"| {row['concurrency']} | {row['sample_count']} | {percent(row['success_rate'])} | "
                f"{row['latency_ms']['p50_ms']} ms | {row['latency_ms']['p95_ms']} ms | "
                f"{row['latency_ms']['max_ms']} ms | "
                f"{row['observed_distinct_partitions']['min']}–{row['observed_distinct_partitions']['max']} |"
                for row in concurrent["summary"]
            )
        )
    else:
        concurrent_text = f"并发解析：未执行。{concurrent['reason']}"
    return f"""# PaperLoom 量化评测报告

运行：`{data['run_id']}`，代码：`{data['code_revision']}`。

## 数据规模

- PDF：{dataset['pdf_count']} 篇，共 {dataset['total_pages']} 页；300+ 页样本为 {dataset['page_buckets']['xlarge_300_plus']}。
- 标准证据题目库存：{dataset['standard_evidence_question_inventory']} 条（人工编写 {dataset['human_authored_golden_case_count']}，模型生成并冻结 {dataset['frozen_generated_target_count']}）。
- 本轮检索题：{dataset['executed_retrieval_question_count']}；Agent Case：{dataset['executed_agent_case_count']}；生成引用：{dataset['generated_citation_count']}。

## P0

| 指标 | 结果 | 样本/说明 |
| --- | ---: | --- |
| 引用准确率 | {percent(p0['citation_accuracy']['value'])} | {p0['citation_accuracy']['numerator']}/{p0['citation_accuracy']['denominator']}，临时代理，未经人工逐引用复核 |
| 无依据引用率 | {percent(p0['unsupported_citation_rate']['value'])} | {p0['unsupported_citation_rate']['numerator']}/{p0['unsupported_citation_rate']['denominator']}，临时代理 |
| 引用定位成功率 | {percent(p0['citation_location_success_rate']['value'])} | {p0['citation_location_success_rate']['numerator']}/{p0['citation_location_success_rate']['denominator']}，当前 Agent read trace 校验 PDF/页码/位置 |
| Recall@5 | {percent(p0['retrieval_recall_at_5'])} | {dataset['executed_retrieval_question_count']} 条冻结 Target |
| Recall@10 | {percent(p0['retrieval_recall_at_10'])} | {dataset['executed_retrieval_question_count']} 条冻结 Target |
| 证据阅读覆盖率 | {percent(p0['evidence_read_coverage']['value'])} | {p0['evidence_read_coverage']['numerator']}/{p0['evidence_read_coverage']['denominator']} |
| TTFT P50 / P95 | {p0['ttft_ms']['p50_ms']} / {p0['ttft_ms']['p95_ms']} ms | 真实 WebSocket，5 次 |

失败明细：引用定位的 2 个失败均为同一 `location_ref` 在 Evidence Ledger 与 Agent read trace 中页码相差 1 页；证据阅读未覆盖 Case 为 `comparison_03`（1/2）和 `single_04`（0/1）。逐条记录见 `citations.csv` 和 `cases.csv`。

## P1/P2

- 完整回答延迟 P50/P95：{p1['complete_answer_latency_ms']['p50_ms']} / {p1['complete_answer_latency_ms']['p95_ms']} ms（5 次直接问候）。
- 取消延迟 P50/P95：{p1['cancel_latency_ms']['p50_ms']} / {p1['cancel_latency_ms']['p95_ms']} ms（5 次）。
- 当前解析与索引成功率：{percent(p1['parse_success_rate']['value'])}（{p1['parse_success_rate']['numerator']}/{p1['parse_success_rate']['denominator']}，存量横截面）。
- Kafka 重复投递：5 次均被消费并走幂等短路，数据库/MinIO/Qdrant 新增重复记录 0。
- Kafka 重投仍刷新了 `file_upload.merged_at`，虽然没有产生重复记录，但不是完全无副作用。
- 解析耗时：未执行。{p1['parse_and_index_latency_ms']['reason']}
{concurrent_text}

## 限制

""" + "".join(f"- {item}\n" for item in data["limitations"]) + """

## 待人工复核

- `human-citation-review.csv`：39 条引用支持性和定位复核。
- `golden-target-human-review.csv`：31 条模型生成 Target 的问题、答案、证据和页码复核。
- `HUMAN_REVIEW_GUIDE.md`：字段取值和判定标准。未完成这些表格前，不得把临时引用指标或 55 条库存标为正式人工结果。

## 复现入口

```bash
.venv-harness/bin/python -m harness_py paperloom31 run \\
  --snapshot research/benchmark/local/snapshots/a61e8c1c240b2e8873b88d20da497b9ec0b98d9631c77a3e05c0634eeb92ecd3.json \\
  --config research/benchmark/paperloom-31-v1/benchmark.yaml \\
  --runs-dir research/benchmark/local/runs --provider-source env

.venv-harness/bin/python -m scripts.evaluation.run_websocket_latency \\
  --mode complete --repetitions 5 --question '你好' --out ws-complete.json

.venv-harness/bin/python -m scripts.evaluation.run_websocket_latency \\
  --mode cancel --repetitions 5 \\
  --question '请比较 BERT 和 GPT-3 的训练目标，并分别引用原文证据。' \\
  --out ws-cancel.json

SERVER_PORT=8082 \\
SPRING_KAFKA_TOPIC_PAPER_PROCESSING=paper-processing-benchmark \\
SPRING_KAFKA_TOPIC_DLT=paper-processing-benchmark-dlt \\
SPRING_KAFKA_CONSUMER_GROUP_ID=paper-processing-benchmark \\
SPRING_KAFKA_TOPIC_PARTITIONS=10 \\
SPRING_KAFKA_LISTENER_CONCURRENCY=10 \\
./scripts/paperloom-start-backend.sh start

.venv-harness/bin/python -m scripts.evaluation.run_concurrent_parse \\
  --source papers/07-DistilBERT.pdf --levels 1 2 5 10 --repetitions 5 \\
  --api-base-url http://127.0.0.1:8082/api/v1 \\
  --kafka-partitions 10 --listener-concurrency 10 --out concurrent-parse.json
```

汇总入口为 `scripts/evaluation/build_quantitative_report.py`。Kafka 重投的前后计数和目标消息身份保存在 `kafka-idempotency.json`。
"""


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--snapshot", type=Path, required=True)
    parser.add_argument("--ws-complete", type=Path, required=True)
    parser.add_argument("--ws-cancel", type=Path, required=True)
    parser.add_argument("--idempotency", type=Path, required=True)
    parser.add_argument("--citation-review", type=Path, required=True)
    parser.add_argument("--concurrent-parse", type=Path)
    parser.add_argument("--papers-dir", type=Path, default=Path("papers"))
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    summary_data = build(args)
    print(json.dumps({"out": str(args.out), "p0": summary_data["p0"]}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
