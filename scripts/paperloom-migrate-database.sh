#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
MYSQL_CONTAINER="${PAPERLOOM_MYSQL_CONTAINER:-paperloom-mysql}"
TARGET_SCHEMA="${PAPERLOOM_DB_SCHEMA:-paperloom}"
SOURCE_SCHEMA=""
APPLY=false

usage() {
  printf '%s\n' \
    "Usage: $0 --source-schema NAME [--target-schema NAME] [--apply]" \
    "" \
    "Without --apply, the command only validates and prints the migration plan."
}

validate_identifier() {
  local value="$1"
  local label="$2"
  [[ "$value" =~ ^[A-Za-z0-9_]+$ ]] || {
    echo "Invalid ${label}: ${value}" >&2
    exit 2
  }
}

mysql_scalar() {
  local sql="$1"
  docker exec "$MYSQL_CONTAINER" sh -lc \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N -B -e "$1"' sh "$sql"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --source-schema)
      SOURCE_SCHEMA="${2:-}"
      shift 2
      ;;
    --target-schema)
      TARGET_SCHEMA="${2:-}"
      shift 2
      ;;
    --apply)
      APPLY=true
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

[[ -n "$SOURCE_SCHEMA" ]] || {
  echo "--source-schema is required" >&2
  usage >&2
  exit 2
}
validate_identifier "$SOURCE_SCHEMA" "source schema"
validate_identifier "$TARGET_SCHEMA" "target schema"
[[ "$SOURCE_SCHEMA" != "$TARGET_SCHEMA" ]] || {
  echo "Source and target schemas must differ." >&2
  exit 2
}

source_exists="$(mysql_scalar "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='${SOURCE_SCHEMA}';")"
[[ "$source_exists" == "1" ]] || {
  echo "Source schema does not exist: ${SOURCE_SCHEMA}" >&2
  exit 1
}

target_tables="$(mysql_scalar "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${TARGET_SCHEMA}';")"
[[ "$target_tables" == "0" ]] || {
  echo "Target schema is not empty: ${TARGET_SCHEMA} (${target_tables} tables)" >&2
  exit 1
}

echo "container=${MYSQL_CONTAINER}"
echo "source_schema=${SOURCE_SCHEMA}"
echo "target_schema=${TARGET_SCHEMA}"

if [[ "$APPLY" != "true" ]]; then
  echo "dry_run=true"
  echo "Re-run with --apply after backing up the Docker volumes."
  exit 0
fi

backup_dir="${PAPERLOOM_BACKUP_DIR:-$ROOT/.runtime/backups}"
mkdir -p "$backup_dir"
backup_file="${backup_dir}/${SOURCE_SCHEMA}-$(date -u +%Y%m%dT%H%M%SZ).sql"

docker exec "$MYSQL_CONTAINER" sh -lc \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --routines --triggers "$1"' \
  sh "$SOURCE_SCHEMA" >"$backup_file"

docker exec "$MYSQL_CONTAINER" sh -lc \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -e "CREATE DATABASE IF NOT EXISTS \`$1\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"' \
  sh "$TARGET_SCHEMA"

docker exec -i "$MYSQL_CONTAINER" sh -lc \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot "$1"' sh "$TARGET_SCHEMA" <"$backup_file"

source_tables="$(mysql_scalar "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${SOURCE_SCHEMA}';")"
target_tables="$(mysql_scalar "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${TARGET_SCHEMA}';")"
[[ "$source_tables" == "$target_tables" ]] || {
  echo "Table-count verification failed: source=${source_tables}, target=${target_tables}" >&2
  exit 1
}

echo "backup=${backup_file}"
echo "table_count=${target_tables}"
echo "migration=complete"
