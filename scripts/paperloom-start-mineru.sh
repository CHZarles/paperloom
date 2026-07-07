#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT"

COMMAND="start"
DRY_RUN="false"
HOST="${PAPERLOOM_MINERU_HOST:-127.0.0.1}"
PORT="${PAPERLOOM_MINERU_PORT:-8000}"
VENV_BIN="${PAPERLOOM_MINERU_VENV_BIN:-/home/charles/.local/share/paperloom-mineru/.venv/bin}"
ENV_FILE="${PAPERLOOM_MINERU_ENV_FILE:-.runtime/mineru-gpu-env.txt}"
PID_FILE="${PAPERLOOM_MINERU_PID_FILE:-.runtime/mineru-api.pid}"
LOG_FILE="${PAPERLOOM_MINERU_LOG_FILE:-.runtime/logs/mineru-api-${PORT}.log}"
HEALTH_TIMEOUT_SECONDS="${PAPERLOOM_MINERU_HEALTH_TIMEOUT_SECONDS:-120}"

load_env_file() {
  local file="$1"
  local line key value

  [[ -f "$file" ]] || return 0
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ -z "${line//[[:space:]]/}" || "$line" =~ ^[[:space:]]*# ]] && continue
    [[ "$line" =~ ^[[:space:]]*([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]] || continue
    key="${BASH_REMATCH[1]}"
    value="${BASH_REMATCH[2]}"
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    if [[ ${#value} -ge 2 ]]; then
      if [[ "${value:0:1}" == "\"" && "${value: -1}" == "\"" ]] || [[ "${value:0:1}" == "'" && "${value: -1}" == "'" ]]; then
        value="${value:1:${#value}-2}"
      fi
    fi
    export "${key}=${value}"
  done < "$file"
}

usage() {
  cat <<'EOF'
Usage: scripts/paperloom-start-mineru.sh [--dry-run] [--host HOST] [--port PORT] [--env PATH] [--venv-bin PATH] [start|status|restart|stop]

Starts or checks the local MinerU API sidecar used by launch readiness.
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
      LOG_FILE="${PAPERLOOM_MINERU_LOG_FILE:-.runtime/logs/mineru-api-${PORT}.log}"
      shift 2
      ;;
    --env)
      ENV_FILE="$2"
      shift 2
      ;;
    --venv-bin)
      VENV_BIN="$2"
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

MINERU_API="${VENV_BIN}/mineru-api"
HEALTH_URL="http://${HOST}:${PORT}/health"

pid_alive() {
  [[ -f "$PID_FILE" ]] && ps -p "$(cat "$PID_FILE")" >/dev/null 2>&1
}

health_code() {
  curl -sS -o /dev/null -w '%{http_code}' "$HEALTH_URL" 2>/dev/null || true
}

print_status() {
  local pid="missing"
  local alive="false"
  local code

  if [[ -f "$PID_FILE" ]]; then
    pid="$(cat "$PID_FILE")"
  fi
  if pid_alive; then
    alive="true"
  fi
  code="$(health_code)"

  echo "mineru_pid=${pid}"
  echo "mineru_process_alive=${alive}"
  echo "mineru_health_url=${HEALTH_URL}"
  echo "mineru_health_http_code=${code:-000}"
}

stop_mineru() {
  if ! pid_alive; then
    rm -f "$PID_FILE"
    echo "MinerU is not running."
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
      echo "Stopped MinerU pid=${pid}."
      return 0
    fi
    sleep 1
  done
  kill -9 "$pid" 2>/dev/null || true
  rm -f "$PID_FILE"
  echo "Force-stopped MinerU pid=${pid}."
}

start_mineru() {
  if pid_alive; then
    print_status
    return 0
  fi
  if [[ ! -x "$MINERU_API" ]]; then
    echo "MinerU API executable not found or not executable: ${MINERU_API}" >&2
    exit 1
  fi

  if [[ "$DRY_RUN" == "true" ]]; then
    echo "dry_run=true"
    echo "mineru_api=${MINERU_API}"
    echo "mineru_env_file=${ENV_FILE}"
    echo "mineru_pid_file=${PID_FILE}"
    echo "mineru_log_file=${LOG_FILE}"
    echo "mineru_health_url=${HEALTH_URL}"
    return 0
  fi

  mkdir -p "$(dirname "$PID_FILE")" "$(dirname "$LOG_FILE")"
  load_env_file "$ENV_FILE"

  nohup "$MINERU_API" --host "$HOST" --port "$PORT" > "$LOG_FILE" 2>&1 &
  echo $! > "$PID_FILE"

  for _ in $(seq 1 "$HEALTH_TIMEOUT_SECONDS"); do
    if ! pid_alive; then
      echo "MinerU exited before becoming healthy. Log: ${LOG_FILE}" >&2
      tail -n 80 "$LOG_FILE" >&2 || true
      exit 1
    fi
    if [[ "$(health_code)" == "200" ]]; then
      echo "MinerU healthy at ${HEALTH_URL}; pid=$(cat "$PID_FILE")"
      return 0
    fi
    sleep 1
  done

  echo "MinerU did not become healthy within ${HEALTH_TIMEOUT_SECONDS}s. Log: ${LOG_FILE}" >&2
  tail -n 80 "$LOG_FILE" >&2 || true
  exit 1
}

case "$COMMAND" in
  status)
    print_status
    ;;
  start)
    start_mineru
    ;;
  restart)
    stop_mineru
    start_mineru
    ;;
  stop)
    stop_mineru
    ;;
esac
