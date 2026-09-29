#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT"

load_env() {
  local line key value
  [[ -f .env ]] || return 0
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ -z "${line//[[:space:]]/}" || "$line" =~ ^[[:space:]]*# ]] && continue
    [[ "$line" =~ ^[[:space:]]*([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]] || continue
    key="${BASH_REMATCH[1]}"
    value="${BASH_REMATCH[2]}"
    value="${value#\"}"
    value="${value%\"}"
    if [[ -z "${!key+x}" ]]; then
      export "${key}=${value}"
    fi
  done <.env
}

load_env

COMMAND="${1:-start}"
MCP_HOST="${PAPERLOOM_MCP_HOST:-0.0.0.0}"
MCP_PORT="${PAPERLOOM_MCP_PORT:-8090}"
MCP_PUBLIC_URL="${PAPERLOOM_MCP_PUBLIC_URL:-}"
JAVA_BASE_URL="${PAPERLOOM_MCP_JAVA_BASE_URL:-http://127.0.0.1:${SERVER_PORT:-8081}}"
PYTHON="${PAPERLOOM_MCP_PYTHON:-.venv-mcp/bin/python}"
PID_FILE="${PAPERLOOM_MCP_PID_FILE:-.runtime/mcp-${MCP_PORT}.pid}"
LOG_FILE="${PAPERLOOM_MCP_LOG_FILE:-.runtime/logs/mcp-${MCP_PORT}.log}"
PROBE_URL="http://127.0.0.1:${MCP_PORT}/.well-known/oauth-protected-resource"

pid_alive() {
  [[ -f "$PID_FILE" ]] && ps -p "$(cat "$PID_FILE")" >/dev/null 2>&1
}

http_code() {
  curl -sS -o /dev/null -w '%{http_code}' "$PROBE_URL" 2>/dev/null || true
}

status() {
  echo "mcp_pid=$([[ -f "$PID_FILE" ]] && cat "$PID_FILE" || echo missing)"
  echo "mcp_process_alive=$(pid_alive && echo true || echo false)"
  echo "mcp_probe_url=${PROBE_URL}"
  echo "mcp_http_code=$(http_code)"
}

stop() {
  if ! pid_alive; then
    rm -f "$PID_FILE"
    echo "MCP is not running."
    return 0
  fi
  local pid
  pid="$(cat "$PID_FILE")"
  kill "$pid" 2>/dev/null || true
  for _ in $(seq 1 20); do
    if ! ps -p "$pid" >/dev/null 2>&1; then
      rm -f "$PID_FILE"
      echo "Stopped MCP pid=${pid}."
      return 0
    fi
    sleep 1
  done
  kill -9 "$pid" 2>/dev/null || true
  rm -f "$PID_FILE"
}

start() {
  if pid_alive || [[ "$(http_code)" != "000" ]]; then
    status
    return 0
  fi
  [[ -x "$PYTHON" ]] || {
    echo "MCP Python not found or not executable: ${PYTHON}" >&2
    echo "Create .venv-mcp and install paperloom_mcp/requirements.lock first." >&2
    exit 1
  }
  [[ -n "$MCP_PUBLIC_URL" ]] || {
    echo "PAPERLOOM_MCP_PUBLIC_URL is required for the deployed HTTP MCP endpoint." >&2
    exit 1
  }
  mkdir -p "$(dirname "$PID_FILE")" "$(dirname "$LOG_FILE")"
  nohup "$PYTHON" -m paperloom_mcp \
    --transport http \
    --host "$MCP_HOST" \
    --port "$MCP_PORT" \
    --java-base-url "$JAVA_BASE_URL" \
    --public-url "$MCP_PUBLIC_URL" \
    >"$LOG_FILE" 2>&1 &
  echo $! >"$PID_FILE"
  for _ in $(seq 1 30); do
    if ! pid_alive; then
      tail -n 100 "$LOG_FILE" >&2 || true
      exit 1
    fi
    if [[ "$(http_code)" != "000" ]]; then
      echo "MCP healthy at ${PROBE_URL}; pid=$(cat "$PID_FILE")"
      return 0
    fi
    sleep 1
  done
  tail -n 100 "$LOG_FILE" >&2 || true
  exit 1
}

case "$COMMAND" in
  start) start ;;
  status) status ;;
  restart) stop; start ;;
  stop) stop ;;
  *) echo "Usage: $0 [start|status|restart|stop]" >&2; exit 2 ;;
esac
