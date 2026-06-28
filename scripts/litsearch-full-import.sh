#!/usr/bin/env bash
set -euo pipefail

TARGET_ROWS=64183
DEFAULT_BATCH_SIZE=2000
MAIN_ROOT="${PAPERLOOM_MAIN_ROOT:-/home/charles/PaiSmart}"
WT="${PAPERLOOM_WORKTREE:-$MAIN_ROOT/.worktrees/adaptive-source-set-rag}"
ENV_FILE="${PAPERLOOM_ENV_FILE:-$MAIN_ROOT/.env}"
MYSQL_CONTAINER="${PAPERLOOM_MYSQL_CONTAINER:-pai_smart_mysql}"
CORPUS="$WT/eval/rag/litsearch/generated/litsearch-corpus-clean-full.jsonl"
QUERY="$WT/eval/rag/litsearch/generated/litsearch-full-query.jsonl"
TARGET_DIR="$WT/target"
LOG="$TARGET_DIR/litsearch-full-import.log"
PIDFILE="$TARGET_DIR/litsearch-full-import.pid"
STATEFILE="$TARGET_DIR/litsearch-full-import.state"

usage() {
  cat <<USAGE
Usage:
  $0 start [--batch-size N] [--start-offset N]
  $0 status
  $0 summary
  $0 counts
  $0 tail
  $0 stop

Defaults:
  main root:     $MAIN_ROOT
  worktree:      $WT
  env file:      $ENV_FILE
  corpus:        $CORPUS
  target rows:   $TARGET_ROWS
  batch size:    $DEFAULT_BATCH_SIZE
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
  [[ -f "$CORPUS" ]] || fail "missing LitSearch corpus JSONL: $CORPUS"
  [[ -f "$QUERY" ]] || fail "missing LitSearch query JSONL: $QUERY"
  local corpus_rows query_rows
  corpus_rows=$(wc -l < "$CORPUS" | tr -d ' ')
  query_rows=$(wc -l < "$QUERY" | tr -d ' ')
  [[ "$corpus_rows" == "$TARGET_ROWS" ]] || fail "expected $TARGET_ROWS corpus rows, got $corpus_rows"
  [[ "$query_rows" == "597" ]] || fail "expected 597 query rows, got $query_rows"
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
  ps -eo pid=,ppid=,cmd= | awk '
    /LitSearchPaperLoomImportCli/ &&
    ! /awk / &&
    ! /rg / {
      print $1
    }
  '
}

java_import_rows() {
  ps -eo pid=,args= | awk '
    $2 == "java" &&
    /LitSearchPaperLoomImportCli/ &&
    ! /awk / &&
    ! /rg / {
      print
    }
  '
}

assert_not_running() {
  local pids
  pids=$(running_pids || true)
  if [[ -n "$pids" ]]; then
    echo "Import appears to be running already:"
    ps -fp $pids || true
    fail "not starting a duplicate importer"
  fi
}

auto_start_offset() {
  local batch_size="$1"
  local count
  count=$(full_count)
  [[ -n "$count" ]] || count=0
  if (( count >= TARGET_ROWS )); then
    echo "$TARGET_ROWS"
    return
  fi
  echo $(( (count / batch_size) * batch_size ))
}

start_import() {
  local batch_size="$DEFAULT_BATCH_SIZE"
  local start_offset=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --batch-size)
        batch_size="${2:-}"
        shift 2
        ;;
      --start-offset)
        start_offset="${2:-}"
        shift 2
        ;;
      *)
        fail "unknown start option: $1"
        ;;
    esac
  done
  [[ "$batch_size" =~ ^[0-9]+$ && "$batch_size" -gt 0 ]] || fail "invalid batch size: $batch_size"

  ensure_inputs
  prepare_classpath
  assert_not_running

  if [[ -z "$start_offset" ]]; then
    start_offset=$(auto_start_offset "$batch_size")
  fi
  [[ "$start_offset" =~ ^[0-9]+$ ]] || fail "invalid start offset: $start_offset"
  if (( start_offset >= TARGET_ROWS )); then
    echo "LitSearch Full already has $TARGET_ROWS imported papers."
    db_counts
    exit 0
  fi

  mkdir -p "$TARGET_DIR"
  {
    echo "requested_at=$(date -Is)"
    echo "start_offset=$start_offset"
    echo "batch_size=$batch_size"
    echo "target_rows=$TARGET_ROWS"
    echo "log=$LOG"
  } > "$STATEFILE"

  mapfile -t launcher < <(background_launcher)
  "${launcher[@]}" bash -c '
    set -euo pipefail
    MAIN_ROOT="$1"
    WT="$2"
    CORPUS="$3"
    TARGET_ROWS="$4"
    BATCH_SIZE="$5"
    START_OFFSET="$6"
    CP="$7"

    cd "$MAIN_ROOT"
    for (( start=START_OFFSET; start<TARGET_ROWS; start+=BATCH_SIZE )); do
      limit=$BATCH_SIZE
      remaining=$(( TARGET_ROWS - start ))
      if (( remaining < limit )); then
        limit=$remaining
      fi
      echo "=== litsearch full import start-offset=${start} limit=${limit} $(date -Is) ==="
      java -cp "$CP" \
        com.yizhaoqi.smartpai.eval.LitSearchPaperLoomImportCli \
        --corpus "$CORPUS" \
        --retrieval-corpus EVAL_LITSEARCH \
        --eval-split full \
        --start-offset "$start" \
        --limit "$limit" \
        --max-chunk-characters 1800 \
        --index-batch-size 500
      echo "=== litsearch full import completed start-offset=${start} limit=${limit} $(date -Is) ==="
    done
  ' bash "$MAIN_ROOT" "$WT" "$CORPUS" "$TARGET_ROWS" "$batch_size" "$start_offset" "$(classpath)" >> "$LOG" 2>&1 &

  echo $! > "$PIDFILE"
  echo "started pid=$(cat "$PIDFILE")"
  echo "log=$LOG"
  echo "state=$STATEFILE"
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
  echo "Recent import log:"
  if [[ -f "$LOG" ]]; then
    grep -E "===|importedPapers=|importedChunks=|Exception|ERROR|failed|失败" "$LOG" | tail -80 || true
  else
    echo "no log yet: $LOG"
  fi
}

summary() {
  local pids count java_rows
  pids=$(running_pids || true)
  count=$(full_count)
  java_rows=$(java_import_rows || true)
  [[ -n "$count" ]] || count=0

  echo "import_running=$([[ -n "$pids" ]] && echo yes || echo no)"
  if [[ -n "$pids" ]]; then
    echo "import_pids=$(echo "$pids" | tr '\n' ' ' | sed 's/[[:space:]]*$//')"
  fi
  if [[ -n "$java_rows" ]]; then
    echo "$java_rows" | awk '
      {
        pid=$1
        start=""
        limit=""
        for (i=1; i<=NF; i++) {
          if ($i == "--start-offset") start=$(i+1)
          if ($i == "--limit") limit=$(i+1)
        }
        if (start != "") print "current_batch_start_offset=" start
        if (limit != "") print "current_batch_limit=" limit
        print "current_java_pid=" pid
      }
    '
  fi
  echo "litsearch_full_papers=$count/$TARGET_ROWS"
  if [[ -f "$STATEFILE" ]]; then
    awk -F= '/^(batch_size|target_rows|log)=/ { print }' "$STATEFILE"
  fi
}

stop_import() {
  local pids
  pids=$(running_pids || true)
  if [[ -z "$pids" ]]; then
    echo "not running"
    return
  fi
  echo "stopping:"
  ps -o pid,ppid,stat,etime,pcpu,pmem,comm -p $pids || true
  for pid in $pids; do
    kill -- "-$pid" 2>/dev/null || kill "$pid" 2>/dev/null || true
  done
  echo "sent TERM. Re-run start later; it will resume from the current batch boundary."
}

cmd="${1:-}"
shift || true
case "$cmd" in
  start)
    start_import "$@"
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
    stop_import
    ;;
  ""|-h|--help|help)
    usage
    ;;
  *)
    usage
    fail "unknown command: $cmd"
    ;;
esac
