#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT"

COMMAND="start"
DRY_RUN="false"
HOST="${PAPERLOOM_FRONTEND_HOST:-127.0.0.1}"
PORT="${PAPERLOOM_FRONTEND_PORT:-9527}"
FRONTEND_DIR="${PAPERLOOM_FRONTEND_DIR:-frontend}"
PID_FILE="${PAPERLOOM_FRONTEND_PID_FILE:-.runtime/frontend-vite.pid}"
LOG_FILE="${PAPERLOOM_FRONTEND_LOG_FILE:-.runtime/logs/frontend-vite-${PORT}.log}"
HEALTH_TIMEOUT_SECONDS="${PAPERLOOM_FRONTEND_HEALTH_TIMEOUT_SECONDS:-120}"
SPA_MARKER='id="app"'

usage() {
  cat <<'EOF'
Usage: scripts/paperloom-start-frontend.sh [--dry-run] [--host HOST] [--port PORT] [--frontend-dir PATH] [--pid-file PATH] [--log-file PATH] [start|status|restart|stop]

Starts or checks the local PaperLoom Vite frontend used by launch readiness.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)
      DRY_RUN="true"
      shift
      ;;
    --host)
      HOST="$2"
      shift 2
      ;;
    --port)
      PORT="$2"
      LOG_FILE="${PAPERLOOM_FRONTEND_LOG_FILE:-.runtime/logs/frontend-vite-${PORT}.log}"
      shift 2
      ;;
    --frontend-dir)
      FRONTEND_DIR="$2"
      shift 2
      ;;
    --pid-file)
      PID_FILE="$2"
      shift 2
      ;;
    --log-file)
      LOG_FILE="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    start|status|restart|stop)
      COMMAND="$1"
      shift
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

FRONTEND_URL="http://${HOST}:${PORT}"

pid_alive() {
  [[ -f "$PID_FILE" ]] && ps -p "$(cat "$PID_FILE")" >/dev/null 2>&1
}

http_code() {
  curl -sS -o /dev/null -w '%{http_code}' "$FRONTEND_URL" 2>/dev/null || true
}

spa_shell_present() {
  local tmp
  local code
  tmp="$(mktemp)"
  code="$(curl -sS -m 5 -o "$tmp" -w '%{http_code}' "$FRONTEND_URL" 2>/dev/null || true)"
  if [[ "$code" != "200" ]]; then
    rm -f "$tmp"
    return 1
  fi
  if grep -q "$SPA_MARKER" "$tmp"; then
    rm -f "$tmp"
    return 0
  fi
  rm -f "$tmp"
  return 1
}

print_status() {
  local pid="missing"
  local alive="false"
  local code
  local shell="false"

  if [[ -f "$PID_FILE" ]]; then
    pid="$(cat "$PID_FILE")"
  fi
  if pid_alive; then
    alive="true"
  fi
  code="$(http_code)"
  if spa_shell_present; then
    shell="true"
  fi

  echo "frontend_pid=${pid}"
  echo "frontend_process_alive=${alive}"
  echo "frontend_url=${FRONTEND_URL}"
  echo "frontend_http_code=${code:-000}"
  echo "frontend_spa_shell=${shell}"
}

stop_frontend() {
  if ! pid_alive; then
    rm -f "$PID_FILE"
    echo "Frontend is not running."
    return 0
  fi

  local pid
  pid="$(cat "$PID_FILE")"
  if [[ "$DRY_RUN" == "true" ]]; then
    echo "dry_run=true"
    echo "would_stop_pid=${pid}"
    return 0
  fi

  kill "$pid" 2>/dev/null || true
  for _ in $(seq 1 15); do
    if ! ps -p "$pid" >/dev/null 2>&1; then
      rm -f "$PID_FILE"
      echo "Stopped frontend pid=${pid}."
      return 0
    fi
    sleep 1
  done
  kill -9 "$pid" 2>/dev/null || true
  rm -f "$PID_FILE"
  echo "Force-stopped frontend pid=${pid}."
}

start_frontend() {
  if pid_alive || spa_shell_present; then
    print_status
    return 0
  fi
  if [[ ! -d "$FRONTEND_DIR" ]]; then
    echo "Frontend directory not found: ${FRONTEND_DIR}" >&2
    exit 1
  fi
  if ! command -v pnpm >/dev/null 2>&1; then
    echo "pnpm is required to start the frontend." >&2
    exit 1
  fi

  if [[ "$DRY_RUN" == "true" ]]; then
    echo "dry_run=true"
    echo "frontend_dir=${FRONTEND_DIR}"
    echo "frontend_pid_file=${PID_FILE}"
    echo "frontend_log_file=${LOG_FILE}"
    echo "frontend_url=${FRONTEND_URL}"
    echo "frontend_command=pnpm --dir ${FRONTEND_DIR} dev -- --host ${HOST} --port ${PORT} --strictPort --open=false"
    return 0
  fi

  mkdir -p "$(dirname "$PID_FILE")" "$(dirname "$LOG_FILE")"
  BROWSER=none nohup pnpm --dir "$FRONTEND_DIR" dev -- \
    --host "$HOST" \
    --port "$PORT" \
    --strictPort \
    --open=false \
    > "$LOG_FILE" 2>&1 &
  echo $! > "$PID_FILE"

  for _ in $(seq 1 "$HEALTH_TIMEOUT_SECONDS"); do
    if ! pid_alive; then
      echo "Frontend exited before becoming healthy. Log: ${LOG_FILE}" >&2
      tail -n 80 "$LOG_FILE" >&2 || true
      exit 1
    fi
    if spa_shell_present; then
      echo "Frontend healthy at ${FRONTEND_URL}; pid=$(cat "$PID_FILE")"
      return 0
    fi
    sleep 1
  done

  echo "Frontend did not become healthy within ${HEALTH_TIMEOUT_SECONDS}s. Log: ${LOG_FILE}" >&2
  tail -n 80 "$LOG_FILE" >&2 || true
  exit 1
}

case "$COMMAND" in
  status)
    print_status
    ;;
  start)
    start_frontend
    ;;
  restart)
    stop_frontend
    start_frontend
    ;;
  stop)
    stop_frontend
    ;;
esac
