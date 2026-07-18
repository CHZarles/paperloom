#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT"

COMMAND="${1:-start}"
PORT="${SERVER_PORT:-8081}"
PID_FILE="${PAPERLOOM_BACKEND_PID_FILE:-.runtime/backend-${PORT}.pid}"
LOG_FILE="${PAPERLOOM_BACKEND_LOG_FILE:-.runtime/logs/backend-${PORT}.log}"
JAR="${PAPERLOOM_BACKEND_JAR:-target/paperloom-server-0.1.0-SNAPSHOT.jar}"
MYSQL_CONTAINER="${PAPERLOOM_MYSQL_CONTAINER:-paperloom-mysql}"
PROBE_URL="http://127.0.0.1:${PORT}/api/v1/users/me"

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

apply_local_ports() {
  local mysql_container mysql_port
  mysql_container="${PAPERLOOM_MYSQL_CONTAINER:-$MYSQL_CONTAINER}"
  mysql_port="$(docker port "$mysql_container" 3306/tcp 2>/dev/null | sed -n '1s/.*://p' || true)"
  if [[ -n "$mysql_port" && "${SPRING_DATASOURCE_URL:-}" == jdbc:mysql://localhost:3306/* ]]; then
    export SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL/jdbc:mysql:\/\/localhost:3306\//jdbc:mysql:\/\/localhost:${mysql_port}\/}"
  fi
  export RESEARCH_HARNESS_BASE_URL="${RESEARCH_HARNESS_BASE_URL:-http://127.0.0.1:8091}"
}

pid_alive() {
  [[ -f "$PID_FILE" ]] && ps -p "$(cat "$PID_FILE")" >/dev/null 2>&1
}

http_code() {
  curl -sS -o /dev/null -w '%{http_code}' "$PROBE_URL" 2>/dev/null || true
}

healthy() {
  [[ "$(http_code)" != "000" ]]
}

status() {
  echo "backend_pid=$([[ -f "$PID_FILE" ]] && cat "$PID_FILE" || echo missing)"
  echo "backend_process_alive=$(pid_alive && echo true || echo false)"
  echo "backend_probe_url=${PROBE_URL}"
  echo "backend_http_code=$(http_code)"
}

stop() {
  if ! pid_alive; then
    rm -f "$PID_FILE"
    echo "Backend is not running."
    return 0
  fi
  local pid
  pid="$(cat "$PID_FILE")"
  kill "$pid" 2>/dev/null || true
  for _ in $(seq 1 20); do
    if ! ps -p "$pid" >/dev/null 2>&1; then
      rm -f "$PID_FILE"
      echo "Stopped backend pid=${pid}."
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
  [[ -f "$JAR" ]] || { echo "Backend jar not found: ${JAR}" >&2; exit 1; }
  load_env
  PORT="${SERVER_PORT:-$PORT}"
  apply_local_ports
  mkdir -p "$(dirname "$PID_FILE")" "$(dirname "$LOG_FILE")"
  nohup java -jar "$JAR" >"$LOG_FILE" 2>&1 &
  echo $! >"$PID_FILE"
  for _ in $(seq 1 120); do
    if ! pid_alive; then
      tail -n 100 "$LOG_FILE" >&2 || true
      exit 1
    fi
    if healthy; then
      echo "Backend healthy at ${PROBE_URL}; pid=$(cat "$PID_FILE")"
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
