#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT"

COMMAND="${1:-start}"
WORKER_ID="${RESEARCH_HARNESS_WORKER_ID:-harness-$(hostname)-$$}"
REDIS_URL="${RESEARCH_HARNESS_REDIS_URL:-redis://127.0.0.1:6379/0}"
PID_FILE="${RESEARCH_HARNESS_WORKER_PID_FILE:-.runtime/research-harness-worker.pid}"
LOG_FILE="${RESEARCH_HARNESS_WORKER_LOG_FILE:-.runtime/logs/research-harness-worker.log}"

load_env() {
  local line key value
  [[ -f .env ]] || return 0
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ -z "${line//[[:space:]]/}" || "$line" =~ ^[[:space:]]*# ]] && continue
    [[ "$line" =~ ^[[:space:]]*([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]] || continue
    key="${BASH_REMATCH[1]}"
    value="${BASH_REMATCH[2]}"
    value="${value#\"}"; value="${value%\"}"
    export "${key}=${value}"
  done <.env
}

pid_alive() {
  [[ -f "$PID_FILE" ]] && ps -p "$(cat "$PID_FILE")" >/dev/null 2>&1
}

status() {
  echo "harness_worker_pid=$([[ -f "$PID_FILE" ]] && cat "$PID_FILE" || echo missing)"
  echo "harness_worker_process_alive=$(pid_alive && echo true || echo false)"
  echo "harness_worker_id=${WORKER_ID}"
  echo "harness_worker_redis_url=${REDIS_URL}"
}

stop() {
  if ! pid_alive; then
    rm -f "$PID_FILE"
    echo "Research harness worker is not managed by this script."
    return 0
  fi
  local pid
  pid="$(cat "$PID_FILE")"
  kill "$pid" 2>/dev/null || true
  for _ in $(seq 1 15); do
    if ! ps -p "$pid" >/dev/null 2>&1; then
      rm -f "$PID_FILE"
      echo "Stopped research harness worker pid=${pid}."
      return 0
    fi
    sleep 1
  done
  kill -9 "$pid" 2>/dev/null || true
  rm -f "$PID_FILE"
}

start() {
  if pid_alive; then
    status
    return 0
  fi
  load_env
  local python_bin="${RESEARCH_HARNESS_PYTHON:-}"
  if [[ -z "$python_bin" ]]; then
    if [[ -x .venv-harness/bin/python ]]; then
      python_bin=.venv-harness/bin/python
    else
      python_bin=python3
    fi
  fi
  if ! "$python_bin" -c 'import redis, agents' >/dev/null 2>&1; then
    echo "Redis client or OpenAI Agents SDK is unavailable for ${python_bin}." >&2
    echo "Create .venv-harness and install harness_py/requirements.lock." >&2
    exit 1
  fi
  mkdir -p "$(dirname "$PID_FILE")" "$(dirname "$LOG_FILE")"
  nohup "$python_bin" -u -m harness_py worker \
    --redis-url "$REDIS_URL" \
    --worker-id "$WORKER_ID" \
    --group "${RESEARCH_HARNESS_REDIS_GROUP:-paperloom-research-harness}" \
    --max-concurrent-runs "${RESEARCH_HARNESS_WORKER_MAX_CONCURRENT_RUNS:-1}" \
    --job-timeout-seconds "${RESEARCH_HARNESS_JOB_TIMEOUT_SECONDS:-900}" \
    --event-ttl-seconds "${RESEARCH_HARNESS_EVENT_TTL_SECONDS:-1800}" \
    --heartbeat-seconds "${RESEARCH_HARNESS_WORKER_HEARTBEAT_SECONDS:-10}" \
    --stale-pending-seconds "${RESEARCH_HARNESS_STALE_PENDING_SECONDS:-120}" \
    >"$LOG_FILE" 2>&1 &
  echo $! >"$PID_FILE"
  echo "Started research harness worker; pid=$(cat "$PID_FILE"); worker_id=${WORKER_ID}"
}

case "$COMMAND" in
  start) start ;;
  status) status ;;
  restart) stop; start ;;
  stop) stop ;;
  *) echo "Usage: $0 [start|status|restart|stop]" >&2; exit 2 ;;
esac
