#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT"

COMMAND="${1:-start}"
HOST="${RESEARCH_HARNESS_HOST:-127.0.0.1}"
PORT="${RESEARCH_HARNESS_PORT:-8091}"
PID_FILE="${RESEARCH_HARNESS_PID_FILE:-.runtime/research-harness.pid}"
LOG_FILE="${RESEARCH_HARNESS_LOG_FILE:-.runtime/logs/research-harness-${PORT}.log}"
HEALTH_URL="http://${HOST}:${PORT}/health"

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

health_code() {
  curl -sS -o /dev/null -w '%{http_code}' "$HEALTH_URL" 2>/dev/null || true
}

status() {
  echo "harness_pid=$([[ -f "$PID_FILE" ]] && cat "$PID_FILE" || echo missing)"
  echo "harness_process_alive=$(pid_alive && echo true || echo false)"
  echo "harness_health_url=${HEALTH_URL}"
  echo "harness_health_http_code=$(health_code)"
}

stop() {
  if ! pid_alive; then
    rm -f "$PID_FILE"
    echo "Research harness is not managed by this script."
    return 0
  fi
  local pid
  pid="$(cat "$PID_FILE")"
  kill "$pid" 2>/dev/null || true
  for _ in $(seq 1 15); do
    if ! ps -p "$pid" >/dev/null 2>&1; then
      rm -f "$PID_FILE"
      echo "Stopped research harness pid=${pid}."
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
  if [[ "$(health_code)" == "200" ]]; then
    echo "Research harness is already healthy at ${HEALTH_URL}."
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
  if ! "$python_bin" -c 'import agents' >/dev/null 2>&1; then
    echo "OpenAI Agents SDK is unavailable for ${python_bin}." >&2
    echo "Create .venv-harness and install harness_py/requirements.lock." >&2
    exit 1
  fi
  mkdir -p "$(dirname "$PID_FILE")" "$(dirname "$LOG_FILE")"
  nohup "$python_bin" -u -m harness_py serve \
    --host "$HOST" \
    --port "$PORT" \
    --internal-token "${RESEARCH_HARNESS_INTERNAL_TOKEN:-}" \
    >"$LOG_FILE" 2>&1 &
  echo $! >"$PID_FILE"
  for _ in $(seq 1 60); do
    if ! pid_alive; then
      tail -n 80 "$LOG_FILE" >&2 || true
      exit 1
    fi
    if [[ "$(health_code)" == "200" ]]; then
      echo "Research harness healthy at ${HEALTH_URL}; pid=$(cat "$PID_FILE")"
      return 0
    fi
    sleep 1
  done
  tail -n 80 "$LOG_FILE" >&2 || true
  exit 1
}

case "$COMMAND" in
  start) start ;;
  status) status ;;
  restart) stop; start ;;
  stop) stop ;;
  *) echo "Usage: $0 [start|status|restart|stop]" >&2; exit 2 ;;
esac
