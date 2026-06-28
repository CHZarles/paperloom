#!/usr/bin/env bash
set -euo pipefail

TARGET_ROWS=64183
MAIN_ROOT="${PAPERLOOM_MAIN_ROOT:-/home/charles/PaiSmart}"
WT="${PAPERLOOM_WORKTREE:-$MAIN_ROOT/.worktrees/adaptive-source-set-rag}"
ENV_FILE="${PAPERLOOM_ENV_FILE:-$MAIN_ROOT/.env}"
MYSQL_CONTAINER="${PAPERLOOM_MYSQL_CONTAINER:-pai_smart_mysql}"
TARGET_DIR="$WT/target"
LOG="$TARGET_DIR/litsearch-full-benchmark.log"
PIDFILE="$TARGET_DIR/litsearch-full-benchmark.pid"
STATEFILE="$TARGET_DIR/litsearch-full-benchmark.state"

GOLD="$WT/eval/rag/litsearch/generated/litsearch-full-query.jsonl"
RUNS_ROOT="$WT/eval/rag/runs"
REGISTRY="$WT/eval/rag/harnesses.yaml"
CHEATSHEET="$WT/eval/rag/CHEATSHEET.md"
GENERATED="$WT/eval/rag/litsearch/generated"

usage() {
  cat <<USAGE
Usage:
  $0 start
  $0 status
  $0 summary
  $0 counts
  $0 tail
  $0 stop

This script refuses to run unless LitSearch imported eval papers are exactly $TARGET_ROWS.
Run scripts/litsearch-full-import.sh start first.
USAGE
}

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

background_launcher() {
  if command -v setsid >/dev/null 2>&1; then
    printf '%s\n' nohup setsid
  else
    printf '%s\n' nohup
  fi
}

ensure_inputs() {
  [[ -f "$ENV_FILE" ]] || fail "missing env file: $ENV_FILE"
  [[ -f "$GOLD" ]] || fail "missing gold file: $GOLD"
  [[ -f "$REGISTRY" ]] || fail "missing registry: $REGISTRY"
  [[ -f "$CHEATSHEET" ]] || fail "missing cheatsheet: $CHEATSHEET"
  mkdir -p "$TARGET_DIR" "$GENERATED" "$RUNS_ROOT"
  local gold_rows
  gold_rows=$(wc -l < "$GOLD" | tr -d ' ')
  [[ "$gold_rows" == "597" ]] || fail "expected 597 LitSearch query rows, got $gold_rows"
}

classpath_ready() {
  [[ -f "$WT/target/test-classpath.txt" && -d "$WT/target/test-classes" && -d "$WT/target/classes" ]] || return 1
  python3 - "$WT/target/test-classpath.txt" <<'PY'
from pathlib import Path
import sys

entries = Path(sys.argv[1]).read_text().strip().split(":")
missing = [entry for entry in entries if entry and not Path(entry).exists()]
sys.exit(1 if missing else 0)
PY
}

prepare_classpath() {
  mkdir -p "$TARGET_DIR"
  if ! classpath_ready; then
    echo "Preparing Maven test classpath..."
    (
      cd "$WT"
      mvn -q -DskipTests test-compile dependency:build-classpath \
        -Dmdep.outputFile=target/test-classpath.txt \
        -Dmdep.scope=test
    )
  fi
}

classpath() {
  printf '%s:%s:%s' \
    "$WT/target/test-classes" \
    "$WT/target/classes" \
    "$(cat "$WT/target/test-classpath.txt")"
}

db_counts() {
  python3 - "$ENV_FILE" "$MYSQL_CONTAINER" <<'PY'
from pathlib import Path
import re
import subprocess
import sys

env_file = Path(sys.argv[1])
container = sys.argv[2]

def parse_env(path):
    values = {}
    for raw in path.read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.strip()
        if len(value) >= 2 and ((value[0] == value[-1] == '"') or (value[0] == value[-1] == "'")):
            value = value[1:-1]
        values[key.strip()] = value
    return values

env = parse_env(env_file)
user = env.get("SPRING_DATASOURCE_USERNAME", "root")
password = env.get("SPRING_DATASOURCE_PASSWORD", "")
url = env.get("SPRING_DATASOURCE_URL", "")
match = re.search(r"jdbc:mysql://([^/:?]+)(?::(\d+))?/([^?]+)", url)
db = match.group(3) if match else "PaiSmart"
query = (
    "select p.split, count(distinct p.id) as papers, count(c.id) as chunks "
    "from paperloom_eval.eval_papers p "
    "left join paperloom_eval.eval_chunks c on c.corpus=p.corpus and c.paper_id=p.paper_id "
    "where p.corpus='litsearch' "
    "group by p.split order by p.split;"
)
cmd = [
    "docker", "exec",
    "-e", f"MYSQL_PWD={password}",
    container,
    "mysql", f"-u{user}", "--batch", "--raw", db, "-e", query,
]
result = subprocess.run(cmd, text=True, capture_output=True, timeout=60)
print(result.stdout, end="")
if result.returncode != 0:
    print(result.stderr, file=sys.stderr, end="")
sys.exit(result.returncode)
PY
}

full_count() {
  db_counts | awk '$1 == "full" { print $2 }'
}

running_pids() {
  ps -eo pid=,cmd= | awk '
    /ServiceBackedLitSearchBenchmarkCli/ &&
    ! /awk / &&
    ! /rg / {
      print $1
    }
  '
}

import_running_pids() {
  ps -eo pid=,cmd= | awk '
    /LitSearchPaperLoomImportCli/ &&
    ! /awk / &&
    ! /rg / {
      print $1
    }
  '
}

assert_ready_for_full() {
  local count
  count=$(full_count)
  [[ -n "$count" ]] || count=0
  if (( count != TARGET_ROWS )); then
    db_counts
    fail "litsearch-full requires exactly $TARGET_ROWS imported full papers; current full count is $count"
  fi

  local import_pids
  import_pids=$(import_running_pids || true)
  if [[ -n "$import_pids" ]]; then
    ps -o pid,ppid,stat,etime,pcpu,pmem,comm -p $import_pids || true
    fail "import is still running; wait before benchmarking"
  fi
}

assert_not_running() {
  local pids
  pids=$(running_pids || true)
  if [[ -n "$pids" ]]; then
    ps -o pid,ppid,stat,etime,pcpu,pmem,comm -p $pids || true
    fail "benchmark is already running"
  fi
}

start_benchmark() {
  ensure_inputs
  prepare_classpath
  assert_not_running
  assert_ready_for_full

  local started_at stamp run_id retrieved
  started_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  stamp=$(date -u +%Y%m%dT%H%M%SZ)
  run_id="${stamp}-current-evidence-ledger-litsearch-full"
  retrieved="$GENERATED/service-backed-litsearch-full-${stamp}-retrieved.jsonl"

  {
    echo "requested_at=$(date -Is)"
    echo "started_at=$started_at"
    echo "run_id=$run_id"
    echo "retrieved=$retrieved"
    echo "log=$LOG"
  } > "$STATEFILE"

  mapfile -t launcher < <(background_launcher)
  "${launcher[@]}" bash -c '
    set -euo pipefail
    MAIN_ROOT="$1"
    CP="$2"
    GOLD="$3"
    RETRIEVED="$4"
    RUNS_ROOT="$5"
    REGISTRY="$6"
    CHEATSHEET="$7"
    RUN_ID="$8"
    STARTED_AT="$9"

    cd "$MAIN_ROOT"
    java -cp "$CP" \
      com.yizhaoqi.smartpai.eval.ServiceBackedLitSearchBenchmarkCli \
      --gold "$GOLD" \
      --retrieved "$RETRIEVED" \
      --runs-root "$RUNS_ROOT" \
      --registry "$REGISTRY" \
      --cheatsheet "$CHEATSHEET" \
      --harness-id current-evidence-ledger \
      --dataset-id litsearch-full \
      --run-id "$RUN_ID" \
      --started-at "$STARTED_AT" \
      --user-id eval-litsearch-user \
      --retrieval-corpus EVAL_LITSEARCH \
      --scope-imported-only true \
      --eval-split full \
      --top-k 20
  ' bash "$MAIN_ROOT" "$(classpath)" "$GOLD" "$retrieved" "$RUNS_ROOT" "$REGISTRY" "$CHEATSHEET" "$run_id" "$started_at" >> "$LOG" 2>&1 &

  echo $! > "$PIDFILE"
  echo "started pid=$(cat "$PIDFILE")"
  echo "run_id=$run_id"
  echo "retrieved=$retrieved"
  echo "log=$LOG"
}

status() {
  echo "Process:"
  local pids
  pids=$(running_pids || true)
  if [[ -n "$pids" ]]; then
    ps -o pid,ppid,stat,etime,pcpu,pmem,comm -p $pids || true
  else
    echo "not running"
  fi

  echo
  echo "Counts:"
  db_counts || true

  echo
  echo "State:"
  if [[ -f "$STATEFILE" ]]; then
    cat "$STATEFILE"
  else
    echo "no state yet: $STATEFILE"
  fi

  echo
  echo "Recent benchmark log:"
  if [[ -f "$LOG" ]]; then
    grep -E "retrieved=|runDir=|caseCount|recallAt|mrr|Exception|ERROR|failed|失败" "$LOG" | tail -80 || true
  else
    echo "no log yet: $LOG"
  fi
}

summary() {
  local pids count
  pids=$(running_pids || true)
  count=$(full_count)
  [[ -n "$count" ]] || count=0

  echo "benchmark_running=$([[ -n "$pids" ]] && echo yes || echo no)"
  if [[ -n "$pids" ]]; then
    echo "benchmark_pids=$(echo "$pids" | tr '\n' ' ' | sed 's/[[:space:]]*$//')"
  fi
  echo "litsearch_full_papers=$count/$TARGET_ROWS"
  if [[ -f "$STATEFILE" ]]; then
    awk -F= '/^(run_id|retrieved|log|started_at)=/ { print }' "$STATEFILE"
  fi
}

stop_benchmark() {
  local pids
  pids=$(running_pids || true)
  if [[ -z "$pids" ]]; then
    echo "not running"
    return
  fi
  ps -o pid,ppid,stat,etime,pcpu,pmem,comm -p $pids || true
  for pid in $pids; do
    kill -- "-$pid" 2>/dev/null || kill "$pid" 2>/dev/null || true
  done
  echo "sent TERM"
}

cmd="${1:-}"
shift || true
case "$cmd" in
  start)
    start_benchmark "$@"
    ;;
  status)
    status
    ;;
  summary)
    summary
    ;;
  counts)
    db_counts
    ;;
  tail)
    [[ -f "$LOG" ]] || fail "no log yet: $LOG"
    tail -f "$LOG"
    ;;
  stop)
    stop_benchmark
    ;;
  ""|-h|--help|help)
    usage
    ;;
  *)
    usage
    fail "unknown command: $cmd"
    ;;
esac
