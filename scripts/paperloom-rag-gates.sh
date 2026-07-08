#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT"

DRY_RUN=0
if [[ "${1:-}" == "--dry-run" ]]; then
  DRY_RUN=1
  shift
fi

MODE="${1:-}"
if [[ -n "${1:-}" ]]; then
  shift
fi

usage() {
  cat <<USAGE
Usage:
  scripts/paperloom-rag-gates.sh [--dry-run] product-smoke --retrieval-corpus PRODUCT_LIBRARY
  scripts/paperloom-rag-gates.sh [--dry-run] qasper-dev-200 --retrieval-corpus EVAL_QASPER
  scripts/paperloom-rag-gates.sh [--dry-run] litsearch-full-summary --retrieval-corpus EVAL_LITSEARCH
  scripts/paperloom-rag-gates.sh [--dry-run] pdf-parser-smoke
  scripts/paperloom-rag-gates.sh [--dry-run] all-light

Modes:
  product-smoke           Run the live Product Rescue Smoke benchmark against the active backend.
  qasper-dev-200          Run the service-backed QASPER Dev 200 page-window gate.
  litsearch-full-summary  Summarize existing LitSearch Full import/benchmark state only.
  pdf-parser-smoke        Check real PDF parser output rows from the manifest.
  all-light               Run daily lightweight unit/dataset gates; no live benchmark artifact.
USAGE
}

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

print_cmd() {
  printf '+ '
  printf '%q ' "$@"
  printf '\n'
}

run_cmd() {
  print_cmd "$@"
  if [[ "$DRY_RUN" == "1" ]]; then
    return 0
  fi
  "$@"
}

timestamp() {
  date -u +%Y-%m-%dT%H:%M:%SZ
}

require_retrieval_corpus() {
  local expected="$1"
  shift
  local actual=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --retrieval-corpus)
        actual="${2:-}"
        shift 2
        ;;
      *)
        fail "unknown option for $MODE: $1"
        ;;
    esac
  done
  [[ -n "$actual" ]] || fail "missing --retrieval-corpus $expected"
  [[ "$actual" == "$expected" ]] || fail "--retrieval-corpus must be $expected for $MODE"
}

run_id() {
  local started_at="$1"
  local harness_id="$2"
  local dataset_id="$3"
  local stamp
  stamp=$(printf '%s' "$started_at" | sed 's/://g; s/\.//g')
  printf '%s-%s-%s' "$stamp" "$harness_id" "$dataset_id"
}

classpath_literal() {
  if [[ "$DRY_RUN" == "1" ]]; then
    printf '%s' 'target/test-classes:target/classes:$(cat target/test-classpath.txt)'
    return 0
  fi
  printf '%s:%s:%s' \
    "$ROOT/target/test-classes" \
    "$ROOT/target/classes" \
    "$(cat "$ROOT/target/test-classpath.txt")"
}

prepare_classpath() {
  run_cmd mvn -q -DskipTests test-compile dependency:build-classpath \
    -Dmdep.outputFile=target/test-classpath.txt \
    -Dmdep.scope=test
}

check_scorecard() {
  local run_dir="$1"
  local policy="$2"
  if [[ "$DRY_RUN" == "1" ]]; then
    echo "checkScorecard=$run_dir/scorecard.json policy=$policy"
    return 0
  fi
  python3 - "$run_dir/scorecard.json" "$policy" <<'PY'
import json
import sys
from pathlib import Path

scorecard_path = Path(sys.argv[1])
policy = sys.argv[2]
data = json.loads(scorecard_path.read_text())
metrics = data.get("metrics") or {}

def number(name):
    value = metrics.get(name, data.get(name, 0.0))
    return float(value or 0.0)

failures = []
if policy in {"product", "parser"} and number("passRate") < 1.0:
    failures.append(f"passRate={number('passRate'):.3f}")
if policy == "product" and number("citationMappingRate") < 1.0:
    failures.append(f"citationMappingRate={number('citationMappingRate'):.3f}")
if policy in {"product", "qasper"} and number("scopeLeakRate") != 0.0:
    failures.append(f"scopeLeakRate={number('scopeLeakRate'):.3f}")

if failures:
    print("scorecard failed gate: " + ", ".join(failures), file=sys.stderr)
    sys.exit(1)
print(f"scorecardOk={scorecard_path} policy={policy}")
PY
}

product_smoke() {
  require_retrieval_corpus PRODUCT_LIBRARY "$@"
  local started_at harness_id dataset_id id run_dir
  started_at=$(timestamp)
  harness_id="current-evidence-ledger"
  dataset_id="product-rescue-smoke"
  id=$(run_id "$started_at" "$harness_id" "$dataset_id")
  run_dir="eval/rag/runs/$id"
  prepare_classpath
  echo "runDir=$run_dir"
  run_cmd java -cp "$(classpath_literal)" \
    com.yizhaoqi.smartpai.eval.RagLiveBenchmarkCli \
    --dataset eval/rag/product-rescue-smoke.jsonl \
    --runs-root eval/rag/runs \
    --registry eval/rag/harnesses.yaml \
    --cheatsheet eval/rag/CHEATSHEET.md \
    --harness-id "$harness_id" \
    --dataset-id "$dataset_id" \
    --run-id "$id" \
    --started-at "$started_at"
  check_scorecard "$run_dir" product
}

qasper_dev_200() {
  require_retrieval_corpus EVAL_QASPER "$@"
  local started_at harness_id dataset_id id run_dir cases
  cases="eval/rag/qasper/generated/qasper-dev-200-service-cases.jsonl"
  [[ "$DRY_RUN" == "1" || -f "$cases" ]] || fail "missing QASPER service cases: $cases"
  started_at=$(timestamp)
  harness_id="service-backed-scoped-diverse-window"
  dataset_id="qasper-dev-200"
  id=$(run_id "$started_at" "$harness_id" "$dataset_id")
  run_dir="eval/rag/runs/$id"
  prepare_classpath
  echo "runDir=$run_dir"
  run_cmd java -cp "$(classpath_literal)" \
    com.yizhaoqi.smartpai.eval.ServiceBackedPageWindowBenchmarkCli \
    --cases "$cases" \
    --runs-root eval/rag/runs \
    --registry eval/rag/harnesses.yaml \
    --cheatsheet eval/rag/CHEATSHEET.md \
    --harness-id "$harness_id" \
    --dataset-id "$dataset_id" \
    --run-id "$id" \
    --started-at "$started_at" \
    --user-id 1 \
    --retrieval-corpus EVAL_QASPER \
    --query-planner scientific-qa-diverse-windows \
    --candidate-source scoped-paper \
    --window-radius 1 \
    --top-k 3
  check_scorecard "$run_dir" qasper
}

pdf_parser_smoke() {
  local started_at harness_id dataset_id id run_dir
  started_at=$(timestamp)
  harness_id="product-pdf-parser-smoke"
  dataset_id="product-pdf-parser-smoke"
  id=$(run_id "$started_at" "$harness_id" "$dataset_id")
  run_dir="eval/rag/runs/$id"
  prepare_classpath
  echo "runDir=$run_dir"
  run_cmd java -cp "$(classpath_literal)" \
    com.yizhaoqi.smartpai.eval.ProductPdfParserSmokeCli \
    --manifest eval/rag/pdf-parser/product-pdf-smoke-manifest.jsonl \
    --runs-root eval/rag/runs \
    --harness-id "$harness_id" \
    --dataset-id "$dataset_id" \
    --run-id "$id" \
    --started-at "$started_at"
  check_scorecard "$run_dir" parser
}

litsearch_full_summary() {
  require_retrieval_corpus EVAL_LITSEARCH "$@"
  if [[ "$DRY_RUN" == "1" ]]; then
    run_cmd scripts/litsearch-full-pipeline.sh summary
    echo "runDir=not-created (summary only)"
    return 0
  fi
  local summary
  summary=$(scripts/litsearch-full-pipeline.sh summary)
  printf '%s\n' "$summary"
  echo "runDir=not-created (summary only)"
  if grep -q '^litsearch_full_papers=64183/64183$' <<<"$summary"; then
    echo "litsearchFullReady=true"
  else
    echo "litsearchFullReady=false"
    echo "This is not a fresh LitSearch Full benchmark run. Do not report partial samples as litsearch-full."
  fi
}

all_light() {
  run_cmd mvn -q -Dtest=EvidenceQualityTest,PaperQueryPlannerTest,PaperRetrievalServiceTest,ChatHandlerReferenceEvidenceTest,ProductPdfParserSmokeRunnerTest,RagBenchmarkEvaluatorTest,RagBenchmarkDatasetTest,RagBenchmarkReportWriterTest,RagBenchmarkRegistryTest test
  echo "runDir=not-created (all-light uses unit/dataset gates only)"
}

case "$MODE" in
  product-smoke)
    product_smoke "$@"
    ;;
  qasper-dev-200)
    qasper_dev_200 "$@"
    ;;
  litsearch-full-summary)
    litsearch_full_summary "$@"
    ;;
  pdf-parser-smoke)
    [[ $# -eq 0 ]] || fail "unknown option for $MODE: $1"
    pdf_parser_smoke
    ;;
  all-light)
    [[ $# -eq 0 ]] || fail "unknown option for $MODE: $1"
    all_light
    ;;
  ""|-h|--help|help)
    usage
    ;;
  *)
    usage
    fail "unknown mode: $MODE"
    ;;
esac
