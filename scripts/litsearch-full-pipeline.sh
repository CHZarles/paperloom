#!/usr/bin/env bash
set -euo pipefail

TARGET_ROWS=64183
DEFAULT_INTERVAL_SECONDS=60
MAIN_ROOT="${PAPERLOOM_MAIN_ROOT:-/home/charles/PaiSmart}"
WT="${PAPERLOOM_WORKTREE:-$MAIN_ROOT/.worktrees/adaptive-source-set-rag}"
IMPORT_SCRIPT="$WT/scripts/litsearch-full-import.sh"
BENCHMARK_SCRIPT="$WT/scripts/litsearch-full-benchmark.sh"
TARGET_DIR="$WT/target"
LOG="$TARGET_DIR/litsearch-full-pipeline.log"
PIDFILE="$TARGET_DIR/litsearch-full-pipeline.pid"
STATEFILE="$TARGET_DIR/litsearch-full-pipeline.state"

usage() {
  cat <<USAGE
Usage:
  $0 start [--interval-seconds N]
  $0 status
  $0 summary
  $0 tail
  $0 stop

This is the unattended LitSearch Full pipeline:
  1. wait for any existing full import
  2. resume import until full paper count is $TARGET_ROWS
  3. start the full service-backed benchmark
  4. wait for benchmark completion

Logs:
  pipeline:  $LOG
  import:    $WT/target/litsearch-full-import.log
  benchmark: $WT/target/litsearch-full-benchmark.log
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

ensure_scripts() {
  [[ -x "$IMPORT_SCRIPT" ]] || fail "missing executable import script: $IMPORT_SCRIPT"
  [[ -x "$BENCHMARK_SCRIPT" ]] || fail "missing executable benchmark script: $BENCHMARK_SCRIPT"
  mkdir -p "$TARGET_DIR"
}

pipeline_pids() {
  ps -eo pid=,ppid=,cmd= | awk '
    /litsearch-full-pipeline.sh run-loop/ &&
    ! /awk / &&
    ! /rg / {
      print $1
    }
  '
}

import_pids() {
  ps -eo pid=,cmd= | awk '
    /LitSearchPaperLoomImportCli/ &&
    ! /awk / &&
    ! /rg / {
      print $1
    }
  '
}

benchmark_pids() {
  ps -eo pid=,cmd= | awk '
    /ServiceBackedLitSearchBenchmarkCli/ &&
    ! /awk / &&
    ! /rg / {
      print $1
    }
  '
}

full_count() {
  local count
  count=$("$IMPORT_SCRIPT" counts | awk '$1 == "full" { print $2 }')
  if [[ -z "$count" ]]; then
    echo 0
  else
    echo "$count"
  fi
}

state_write() {
  {
    echo "updated_at=$(date -Is)"
    printf '%s\n' "$@"
  } > "$STATEFILE"
}

start_pipeline() {
  local interval="$DEFAULT_INTERVAL_SECONDS"
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --interval-seconds)
        interval="${2:-}"
        shift 2
        ;;
      *)
        fail "unknown start option: $1"
        ;;
    esac
  done
  [[ "$interval" =~ ^[0-9]+$ && "$interval" -gt 0 ]] || fail "invalid interval: $interval"

  ensure_scripts

  local pids
  pids=$(pipeline_pids || true)
  if [[ -n "$pids" ]]; then
    ps -o pid,ppid,stat,etime,pcpu,pmem,comm -p $pids || true
    fail "pipeline is already running"
  fi

  {
    echo "requested_at=$(date -Is)"
    echo "interval_seconds=$interval"
    echo "target_rows=$TARGET_ROWS"
    echo "log=$LOG"
  } > "$STATEFILE"

  mapfile -t launcher < <(background_launcher)
  "${launcher[@]}" "$0" run-loop --interval-seconds "$interval" >> "$LOG" 2>&1 &
  echo $! > "$PIDFILE"
  echo "started pid=$(cat "$PIDFILE")"
  echo "log=$LOG"
  echo "state=$STATEFILE"
}

run_loop() {
  local interval="$DEFAULT_INTERVAL_SECONDS"
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --interval-seconds)
        interval="${2:-}"
        shift 2
        ;;
      *)
        fail "unknown run-loop option: $1"
        ;;
    esac
  done

  ensure_scripts
  trap 'echo "pipeline received stop signal at $(date -Is)"; exit 143' TERM INT

  echo "=== litsearch full pipeline started $(date -Is) ==="
  while true; do
    local count import_running benchmark_running
    count=$(full_count)
    import_running=$(import_pids || true)
    benchmark_running=$(benchmark_pids || true)

    echo "[$(date -Is)] full_count=$count import_running=$([[ -n "$import_running" ]] && echo yes || echo no) benchmark_running=$([[ -n "$benchmark_running" ]] && echo yes || echo no)"

    if (( count < TARGET_ROWS )); then
      state_write "phase=import" "full_count=$count" "target_rows=$TARGET_ROWS"
      if [[ -z "$import_running" ]]; then
        echo "[$(date -Is)] import is not running; starting/resuming import"
        "$IMPORT_SCRIPT" start
      fi
      sleep "$interval"
      continue
    fi

    if (( count > TARGET_ROWS )); then
      state_write "phase=failed" "reason=full_count_exceeds_target" "full_count=$count" "target_rows=$TARGET_ROWS"
      fail "full count exceeds target: $count > $TARGET_ROWS"
    fi

    if [[ -n "$import_running" ]]; then
      state_write "phase=waiting-for-import-exit" "full_count=$count" "target_rows=$TARGET_ROWS"
      echo "[$(date -Is)] target count reached, waiting for import process to exit"
      sleep "$interval"
      continue
    fi

    if [[ -z "$benchmark_running" ]]; then
      state_write "phase=benchmark-starting" "full_count=$count" "target_rows=$TARGET_ROWS"
      echo "[$(date -Is)] starting full benchmark"
      "$BENCHMARK_SCRIPT" start
      sleep 5
      benchmark_running=$(benchmark_pids || true)
      if [[ -z "$benchmark_running" ]]; then
        state_write "phase=failed" "reason=benchmark_exited_before_observed" "full_count=$count" "target_rows=$TARGET_ROWS"
        echo "[$(date -Is)] benchmark exited before it could be observed; check benchmark log"
        "$BENCHMARK_SCRIPT" status || true
        exit 1
      fi
    fi

    state_write "phase=benchmark-running" "full_count=$count" "target_rows=$TARGET_ROWS"
    while [[ -n "$(benchmark_pids || true)" ]]; do
      echo "[$(date -Is)] benchmark still running"
      sleep "$interval"
    done

    state_write "phase=done" "full_count=$count" "target_rows=$TARGET_ROWS"
    echo "=== litsearch full pipeline completed $(date -Is) ==="
    "$BENCHMARK_SCRIPT" status || true
    exit 0
  done
}

status() {
  ensure_scripts

  echo "Pipeline:"
  local pids
  pids=$(pipeline_pids || true)
  if [[ -n "$pids" ]]; then
    ps -o pid,ppid,stat,etime,pcpu,pmem,comm -p $pids || true
  else
    echo "not running"
  fi

  echo
  echo "State:"
  if [[ -f "$STATEFILE" ]]; then
    cat "$STATEFILE"
  else
    echo "no state yet: $STATEFILE"
  fi

  echo
  echo "Import:"
  "$IMPORT_SCRIPT" status || true

  echo
  echo "Benchmark:"
  "$BENCHMARK_SCRIPT" status || true

  echo
  echo "Recent pipeline log:"
  if [[ -f "$LOG" ]]; then
    tail -80 "$LOG"
  else
    echo "no log yet: $LOG"
  fi
}

summary() {
  ensure_scripts

  local pipeline import benchmark count
  pipeline=$(pipeline_pids || true)
  import=$(import_pids || true)
  benchmark=$(benchmark_pids || true)
  count=$(full_count)
  [[ -n "$count" ]] || count=0

  echo "pipeline_running=$([[ -n "$pipeline" ]] && echo yes || echo no)"
  if [[ -n "$pipeline" ]]; then
    echo "pipeline_pids=$(echo "$pipeline" | tr '\n' ' ' | sed 's/[[:space:]]*$//')"
  fi
  if [[ -f "$STATEFILE" ]]; then
    awk -F= '/^(phase|updated_at|full_count|target_rows)=/ { print }' "$STATEFILE"
  else
    echo "phase=not-started"
  fi
  echo "litsearch_full_papers=$count/$TARGET_ROWS"
  echo "import_running=$([[ -n "$import" ]] && echo yes || echo no)"
  if [[ -n "$import" ]]; then
    echo "import_pids=$(echo "$import" | tr '\n' ' ' | sed 's/[[:space:]]*$//')"
  fi
  echo "benchmark_running=$([[ -n "$benchmark" ]] && echo yes || echo no)"
  if [[ -n "$benchmark" ]]; then
    echo "benchmark_pids=$(echo "$benchmark" | tr '\n' ' ' | sed 's/[[:space:]]*$//')"
  fi
}

stop_pipeline() {
  ensure_scripts

  local pids
  pids=$(pipeline_pids || true)
  if [[ -n "$pids" ]]; then
    ps -o pid,ppid,stat,etime,pcpu,pmem,comm -p $pids || true
    for pid in $pids; do
      kill -- "-$pid" 2>/dev/null || kill "$pid" 2>/dev/null || true
    done
    echo "sent TERM to pipeline"
  else
    echo "pipeline not running"
  fi

  "$BENCHMARK_SCRIPT" stop || true
  "$IMPORT_SCRIPT" stop || true
}

cmd="${1:-}"
shift || true
case "$cmd" in
  start)
    start_pipeline "$@"
    ;;
  run-loop)
    run_loop "$@"
    ;;
  status)
    status
    ;;
  summary)
    summary
    ;;
  tail)
    [[ -f "$LOG" ]] || fail "no log yet: $LOG"
    tail -f "$LOG"
    ;;
  stop)
    stop_pipeline
    ;;
  ""|-h|--help|help)
    usage
    ;;
  *)
    usage
    fail "unknown command: $cmd"
    ;;
esac
