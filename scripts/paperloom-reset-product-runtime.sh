#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT"

if [[ "${1:-}" != "--yes-i-know-this-deletes-product-runtime" ]]; then
  echo "Refusing to reset without explicit confirmation."
  echo "Usage: scripts/paperloom-reset-product-runtime.sh --yes-i-know-this-deletes-product-runtime"
  exit 2
fi

env_value() {
  local key="$1"

  [[ -f .env ]] || return 0

  awk -F= -v key="$key" '
    /^[[:space:]]*#/ { next }
    /^[[:space:]]*$/ { next }
    {
      name = $1
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", name)
      if (name == key) {
        print substr($0, index($0, "=") + 1)
        exit
      }
    }
  ' .env
}

ES_SCHEME="${ELASTICSEARCH_SCHEME:-$(env_value ELASTICSEARCH_SCHEME)}"
ES_HOST="${ELASTICSEARCH_HOST:-$(env_value ELASTICSEARCH_HOST)}"
ES_PORT="${ELASTICSEARCH_PORT:-$(env_value ELASTICSEARCH_PORT)}"
ES_USER="${ELASTICSEARCH_USERNAME:-$(env_value ELASTICSEARCH_USERNAME)}"
ES_PASSWORD="${ELASTICSEARCH_PASSWORD:-$(env_value ELASTICSEARCH_PASSWORD)}"
ES_INSECURE="${ELASTICSEARCH_INSECURE_TRUST_ALL_CERTIFICATES:-$(env_value ELASTICSEARCH_INSECURE_TRUST_ALL_CERTIFICATES)}"
REDIS_PASSWORD_VALUE="${SPRING_DATA_REDIS_PASSWORD:-$(env_value SPRING_DATA_REDIS_PASSWORD)}"
MINIO_BUCKET_NAME_VALUE="${MINIO_BUCKET_NAME:-$(env_value MINIO_BUCKET_NAME)}"

ES_SCHEME="${ES_SCHEME:-http}"
ES_HOST="${ES_HOST:-localhost}"
ES_PORT="${ES_PORT:-9200}"
ES_USER="${ES_USER:-elastic}"
ES_INSECURE="${ES_INSECURE:-false}"
MINIO_BUCKET_NAME_VALUE="${MINIO_BUCKET_NAME_VALUE:-uploads}"

mysql_db() {
  docker exec -i pai_smart_mysql sh -lc 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$1"' sh "$1"
}

mysql_query() {
  docker exec pai_smart_mysql sh -lc 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$@"' sh "$@"
}

delete_es_index_docs() {
  local index_name="$1"
  local es_url="${ES_SCHEME}://${ES_HOST}:${ES_PORT}/${index_name}/_delete_by_query?conflicts=proceed"
  local curl_args=(-sS -H 'Content-Type: application/json')
  local response_body
  local http_code
  local curl_exit

  if [[ -n "$ES_USER" || -n "$ES_PASSWORD" ]]; then
    curl_args+=(-u "${ES_USER}:${ES_PASSWORD}")
  fi

  if [[ "$ES_INSECURE" == "true" ]]; then
    curl_args+=(-k)
  fi

  response_body="$(mktemp)"

  set +e
  http_code="$(curl "${curl_args[@]}" \
    -o "$response_body" \
    -w '%{http_code}' \
    -X POST "$es_url" \
    -d '{"query":{"match_all":{}}}')"
  curl_exit=$?
  set -e

  if [[ "$curl_exit" -ne 0 ]]; then
    echo "Elasticsearch cleanup failed for ${index_name}: curl exited ${curl_exit}." >&2
    if [[ -s "$response_body" ]]; then
      cat "$response_body" >&2
      echo >&2
    fi
    rm -f "$response_body"
    return "$curl_exit"
  fi

  if [[ ! "$http_code" =~ ^2[0-9][0-9]$ ]]; then
    echo "Elasticsearch cleanup failed for ${index_name}: HTTP ${http_code}." >&2
    if [[ -s "$response_body" ]]; then
      cat "$response_body" >&2
      echo >&2
    fi
    rm -f "$response_body"
    return 1
  fi

  rm -f "$response_body"
}

flush_redis_runtime_cache() {
  local result

  if ! result="$(docker exec pai_smart_redis sh -lc '
    if [ -n "$1" ]; then
      redis-cli --no-auth-warning -a "$1" FLUSHDB
    else
      redis-cli FLUSHDB
    fi
  ' sh "$REDIS_PASSWORD_VALUE" 2>&1)"; then
    echo "Redis FLUSHDB failed." >&2
    echo "$result" >&2
    return 1
  fi

  if [[ "$result" != "OK" ]]; then
    echo "Redis FLUSHDB returned unexpected output: ${result}" >&2
    return 1
  fi
}

delete_minio_product_objects() {
  docker exec pai_smart_minio sh -lc '
    set -eu

    bucket="$1"
    bucket_list="$(mktemp)"

    cleanup() {
      rm -f "$bucket_list"
    }
    trap cleanup EXIT

    mc alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
    mc ls local >"$bucket_list"

    if ! awk -v bucket="${bucket}/" '\''$NF == bucket { found = 1 } END { exit found ? 0 : 1 }'\'' "$bucket_list"; then
      echo "MinIO bucket ${bucket} does not exist; skipping product object cleanup."
      exit 0
    fi

    mc rm --recursive --force "local/${bucket}" >/dev/null
  ' sh "$MINIO_BUCKET_NAME_VALUE"
}

echo "Preserving: admin user and paperloom_eval benchmark corpus."
echo "Deleting: product papers, product chunks, product chat/session history, Redis runtime keys, product ES docs, product MinIO objects."

mysql_db paismart <<'SQL'
SET FOREIGN_KEY_CHECKS=0;

DROP PROCEDURE IF EXISTS paperloom_reset_delete_if_exists;

DELIMITER //
CREATE PROCEDURE paperloom_reset_delete_if_exists(IN target_table VARCHAR(64))
BEGIN
  IF EXISTS (
    SELECT 1
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = target_table
  ) THEN
    SET @paperloom_reset_sql = CONCAT('DELETE FROM `', REPLACE(target_table, '`', '``'), '`');
    PREPARE paperloom_reset_stmt FROM @paperloom_reset_sql;
    EXECUTE paperloom_reset_stmt;
    DEALLOCATE PREPARE paperloom_reset_stmt;
  END IF;
END//
DELIMITER ;

CALL paperloom_reset_delete_if_exists('conversations');
CALL paperloom_reset_delete_if_exists('conversation_sessions');
CALL paperloom_reset_delete_if_exists('paper_visual_assets');
CALL paperloom_reset_delete_if_exists('paper_parser_artifacts');
CALL paperloom_reset_delete_if_exists('paper_tables');
CALL paperloom_reset_delete_if_exists('paper_figures');
CALL paperloom_reset_delete_if_exists('paper_formulas');
CALL paperloom_reset_delete_if_exists('paper_text_chunks');
CALL paperloom_reset_delete_if_exists('document_vectors');
CALL paperloom_reset_delete_if_exists('chunk_info');
CALL paperloom_reset_delete_if_exists('paper_processing_tasks');
CALL paperloom_reset_delete_if_exists('file_upload');

DELETE FROM users WHERE username <> 'admin';

DROP PROCEDURE IF EXISTS paperloom_reset_delete_if_exists;

SET FOREIGN_KEY_CHECKS=1;
SQL

delete_es_index_docs paper_chunks
delete_es_index_docs paper_search

flush_redis_runtime_cache
delete_minio_product_objects

mysql_query paismart -N -e "
SELECT 'admin_count', COUNT(*) FROM users WHERE username='admin';
SELECT 'product_papers', COUNT(*) FROM file_upload;
SELECT 'product_chunks', COUNT(*) FROM paper_text_chunks;
SELECT 'conversations', COUNT(*) FROM conversations;
"
mysql_query paperloom_eval -N -e "
SELECT 'eval_litsearch_papers', COUNT(*) FROM eval_papers WHERE corpus='litsearch';
SELECT 'eval_qasper_papers', COUNT(*) FROM eval_papers WHERE corpus='qasper';
"
