#!/usr/bin/env bash
set -euo pipefail

# 智能探索 Discovery Runner（pi 宿主）启动脚本，风格对齐 paperloom-start-backend.sh。

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT"

COMMAND="${1:-start}"
PORT="${DISCOVERY_RUNNER_PORT:-8092}"
PID_FILE="${PAPERLOOM_RUNNER_PID_FILE:-.runtime/runner-${PORT}.pid}"
LOG_FILE="${PAPERLOOM_RUNNER_LOG_FILE:-.runtime/logs/runner-${PORT}.log}"
RUNNER_DIR="${PAPERLOOM_RUNNER_DIR:-runner}"
PROBE_URL="http://127.0.0.1:${PORT}/health"

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
    if [[ -z "${!key+x}" ]]; then
      export "${key}=${value}"
    fi
  done <.env
}

pid_alive() {
  [[ -f "$PID_FILE" ]] && ps -p "$(cat "$PID_FILE")" >/dev/null 2>&1
}

http_code() {
  curl -sS -o /dev/null -w '%{http_code}' "$PROBE_URL" 2>/dev/null || true
}

healthy() {
  [[ "$(http_code)" == "200" ]]
}

status() {
  echo "runner_pid=$([[ -f "$PID_FILE" ]] && cat "$PID_FILE" || echo missing)"
  echo "runner_process_alive=$(pid_alive && echo true || echo false)"
  echo "runner_probe_url=${PROBE_URL}"
  echo "runner_http_code=$(http_code)"
}

stop() {
  if ! pid_alive; then
    rm -f "$PID_FILE"
    echo "Runner is not running."
    return 0
  fi
  local pid
  pid="$(cat "$PID_FILE")"
  kill "$pid" 2>/dev/null || true
  for _ in $(seq 1 20); do
    if ! ps -p "$pid" >/dev/null 2>&1; then
      rm -f "$PID_FILE"
      echo "Stopped runner pid=${pid}."
      return 0
    fi
    sleep 1
  done
  kill -9 "$pid" 2>/dev/null || true
  rm -f "$PID_FILE"
}

start() {
  if pid_alive || healthy; then
    status
    return 0
  fi
  [[ -d "$RUNNER_DIR/node_modules/@earendil-works/pi-coding-agent" ]] || {
    echo "Runner 依赖未安装：cd $RUNNER_DIR && npm install --ignore-scripts" >&2
    exit 1
  }
  if [[ -z "${DISCOVERY_RUNNER_INTERNAL_TOKEN:-}" ]]; then
    echo "DISCOVERY_RUNNER_INTERNAL_TOKEN 未设置（要求与后端 discovery.runner.internal-token 一致）" >&2
    exit 1
  fi
  if [[ -z "${OPENGO_API_KEY:-}" && -z "${MINIMAX_API_KEY:-}" ]]; then
    echo "OPENGO_API_KEY（或 MINIMAX_API_KEY）未设置" >&2
    exit 1
  fi
  mkdir -p "$(dirname "$PID_FILE")" "$(dirname "$LOG_FILE")"
  # 本地后端默认在 SERVER_PORT(8081)；docker 环境用环境变量覆盖
  export JAVA_INTERNAL_BASE_URL="${JAVA_INTERNAL_BASE_URL:-http://127.0.0.1:${SERVER_PORT:-8081}}"
  (
    cd "$RUNNER_DIR"
    PORT="$PORT" nohup node runner.mjs >>"$ROOT/$LOG_FILE" 2>&1 &
    echo $! >"$ROOT/$PID_FILE"
  )
  for _ in $(seq 1 30); do
    if ! pid_alive; then
      tail -n 50 "$LOG_FILE" >&2 || true
      exit 1
    fi
    if healthy; then
      echo "Runner healthy at ${PROBE_URL}; pid=$(cat "$PID_FILE")"
      return 0
    fi
    sleep 1
  done
  tail -n 50 "$LOG_FILE" >&2 || true
  exit 1
}

load_env

case "$COMMAND" in
  start) start ;;
  status) status ;;
  restart) stop; start ;;
  stop) stop ;;
  *) echo "Usage: $0 [start|status|restart|stop]" >&2; exit 2 ;;
esac
