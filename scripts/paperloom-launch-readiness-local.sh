#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT"

DRY_RUN="false"
ENV_FILE="${PAPERLOOM_ENV_FILE:-.env}"
OVERLAY_ENV_FILE="${PAPERLOOM_LAUNCH_ENV_FILE:-.runtime/product-launch.env}"
RUN_ID="${PAPERLOOM_LAUNCH_RUN_ID:-$(date -u +%Y-%m-%d-product-launch-readiness-local-%H%M%S)}"
TIMEOUT_SECONDS="${PAPERLOOM_LAUNCH_TIMEOUT_SECONDS:-5}"
MYSQL_CONTAINER="${PAPERLOOM_MYSQL_CONTAINER:-pai_smart_mysql}"

usage() {
  cat <<'EOF'
Usage: scripts/paperloom-launch-readiness-local.sh [--dry-run] [--env PATH] [--overlay-env PATH] [--run-id ID] [--timeout-seconds N]

Runs ProductLaunchReadinessCli with local non-secret launch overrides. Put secrets in the ignored
overlay env file, not in git.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)
      DRY_RUN="true"
      shift
      ;;
    --env)
      ENV_FILE="$2"
      shift 2
      ;;
    --overlay-env)
      OVERLAY_ENV_FILE="$2"
      shift 2
      ;;
    --run-id)
      RUN_ID="$2"
      shift 2
      ;;
    --timeout-seconds)
      TIMEOUT_SECONDS="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

source_env_file() {
  local file="$1"
  local overwrite="${2:-false}"
  local line key value

  if [[ ! -f "$file" ]]; then
    return 0
  fi

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
    if [[ "$overwrite" != "true" && -n "${!key:-}" ]]; then
      continue
    fi
    if [[ -z "$value" && -n "${!key:-}" ]]; then
      continue
    fi
    export "${key}=${value}"
  done < "$file"
}

mysql_published_port() {
  if ! command -v docker >/dev/null 2>&1; then
    return 0
  fi
  docker port "$MYSQL_CONTAINER" 3306/tcp 2>/dev/null \
    | sed -n '1s/.*://p' \
    | tr -d '\r'
}

apply_local_overrides() {
  local mysql_port

  export PAPERLOOM_REACT_READING_PHASE1_ENABLED="${PAPERLOOM_REACT_READING_PHASE1_ENABLED:-true}"
  export PAPER_PARSING_MINERU_BASE_URL="${PAPER_PARSING_MINERU_BASE_URL:-http://127.0.0.1:8000}"

  mysql_port="$(mysql_published_port)"
  if [[ -n "$mysql_port" && "$mysql_port" != "3306" ]]; then
    if [[ "${SPRING_DATASOURCE_URL:-}" == jdbc:mysql://localhost:3306/* ]]; then
      export SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL/jdbc:mysql:\/\/localhost:3306\//jdbc:mysql:\/\/localhost:${mysql_port}\/}"
    fi
  fi
}

secret_state() {
  local key="$1"
  if [[ -n "${!key:-}" ]]; then
    echo "present"
    return 0
  fi
  echo "missing"
}

safe_summary() {
  echo "run_id=${RUN_ID}"
  echo "env_file=${ENV_FILE}"
  echo "overlay_env_file=${OVERLAY_ENV_FILE}"
  echo "spring_datasource_url=${SPRING_DATASOURCE_URL:-}"
  echo "mineru_base_url=${PAPER_PARSING_MINERU_BASE_URL:-}"
  echo "reading_phase_flag=${PAPERLOOM_REACT_READING_PHASE1_ENABLED:-}"
  echo "deepseek_api_key=$(secret_state DEEPSEEK_API_KEY)"
  echo "embedding_api_key=$(secret_state EMBEDDING_API_KEY)"
}

source_env_file "$ENV_FILE" false
source_env_file "$OVERLAY_ENV_FILE" true
apply_local_overrides

if [[ "$DRY_RUN" == "true" ]]; then
  echo "dry_run=true"
  safe_summary
  exit 0
fi

mvn -q -DskipTests test-compile exec:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=com.yizhaoqi.smartpai.eval.ProductLaunchReadinessCli \
  -Dexec.args="--run-id ${RUN_ID} --timeout-seconds ${TIMEOUT_SECONDS} --env ${ENV_FILE}"
