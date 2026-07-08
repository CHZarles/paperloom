#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$ROOT"

if [[ "${1:-}" != "--yes-i-know-this-deletes-product-runtime" ]]; then
  echo "Refusing to reset without explicit confirmation."
  echo "Usage: scripts/paperloom-reset-product-runtime.sh --yes-i-know-this-deletes-product-runtime"
  exit 2
fi

TMP_FILES=()
TMP_FILE_RESULT=""
cleanup_tmp_files() {
  local file

  for file in "${TMP_FILES[@]}"; do
    rm -f "$file"
  done
}
trap cleanup_tmp_files EXIT

make_tmp_file() {
  local file

  file="$(mktemp)"
  TMP_FILES+=("$file")
  TMP_FILE_RESULT="$file"
}

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
KAFKA_CONTAINER="${PAPERLOOM_KAFKA_CONTAINER:-pai_smart_kafka}"
KAFKA_BOOTSTRAP_SERVERS="${PAPERLOOM_KAFKA_BOOTSTRAP_SERVERS:-localhost:9092}"

ES_SCHEME="${ES_SCHEME:-http}"
ES_HOST="${ES_HOST:-localhost}"
ES_PORT="${ES_PORT:-9200}"
ES_USER="${ES_USER:-elastic}"
ES_INSECURE="${ES_INSECURE:-false}"
MINIO_BUCKET_NAME_VALUE="${MINIO_BUCKET_NAME_VALUE:-uploads}"

ADMIN_COUNT_BEFORE=0
MINIO_BUCKET_EXISTS=0
VERIFY_FAILURES=0
declare -A ES_INDEX_EXISTS=()

# Product runtime table policy:
# these tables are reset because they contain uploaded paper PDFs, derived parser/vector artifacts,
# and chat/session runtime state. paperloom_eval is never modified here. Future product-runtime
# tables must be added to this list, reset_mysql, and verify_product_db_counts together.
PRODUCT_DB_TABLES=(
  conversations
  conversation_sessions
  conversation_source_quotes
  paper_conversation_reference
  paper_collection_papers
  paper_collections
  paper_visual_assets
  paper_parser_artifacts
  paper_source_quotes
  paper_reading_elements
  paper_locations
  paper_sections
  paper_pages
  paper_reading_models
  paper_tables
  paper_figures
  paper_formulas
  paper_text_chunks
  document_vectors
  chunk_info
  file_upload
)
OPTIONAL_PRODUCT_DB_TABLES=(
  paper_processing_tasks
)
PRODUCT_ES_INDICES=(
  paper_chunks
  paper_search
)
PRODUCT_KAFKA_TOPICS=(
  paper-processing-topic
  paper-processing-dlt
)

fail() {
  echo "$*" >&2
  exit 1
}

mysql_db() {
  docker exec -i pai_smart_mysql sh -lc 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot "$1"' sh "$1"
}

mysql_query() {
  docker exec pai_smart_mysql sh -lc 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot "$@"' sh "$@"
}

mysql_scalar() {
  local schema="$1"
  local sql="$2"
  local value

  value="$(mysql_query "$schema" -N -B -e "$sql")"
  printf '%s\n' "$value" | sed -n '1p' | tr -d '\r'
}

mysql_table_exists() {
  local schema="$1"
  local table="$2"
  local count

  count="$(mysql_scalar "$schema" "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${schema}' AND table_name='${table}';")"
  [[ "$count" == "1" ]]
}

mysql_count_table() {
  local schema="$1"
  local table="$2"

  mysql_scalar "$schema" "SELECT COUNT(*) FROM \`${table}\`;"
}

es_request() {
  local method="$1"
  local path="$2"
  local data="${3:-}"
  local allow_404="${4:-false}"
  local response_body
  local curl_exit
  local curl_args=(-sS -H 'Content-Type: application/json')

  make_tmp_file
  response_body="$TMP_FILE_RESULT"

  if [[ -n "$ES_USER" || -n "$ES_PASSWORD" ]]; then
    curl_args+=(-u "${ES_USER}:${ES_PASSWORD}")
  fi

  if [[ "$ES_INSECURE" == "true" ]]; then
    curl_args+=(-k)
  fi

  ES_HTTP_CODE=""
  ES_RESPONSE_BODY_FILE="$response_body"

  set +e
  if [[ -n "$data" ]]; then
    ES_HTTP_CODE="$(curl "${curl_args[@]}" \
      -o "$response_body" \
      -w '%{http_code}' \
      -X "$method" \
      "${ES_SCHEME}://${ES_HOST}:${ES_PORT}${path}" \
      -d "$data")"
  else
    ES_HTTP_CODE="$(curl "${curl_args[@]}" \
      -o "$response_body" \
      -w '%{http_code}' \
      -X "$method" \
      "${ES_SCHEME}://${ES_HOST}:${ES_PORT}${path}")"
  fi
  curl_exit=$?
  set -e

  if [[ "$curl_exit" -ne 0 ]]; then
    echo "Elasticsearch request failed for ${method} ${path}: curl exited ${curl_exit}." >&2
    if [[ -s "$response_body" ]]; then
      cat "$response_body" >&2
      echo >&2
    fi
    return "$curl_exit"
  fi

  if [[ "$ES_HTTP_CODE" =~ ^2[0-9][0-9]$ ]]; then
    return 0
  fi

  if [[ "$allow_404" == "true" && "$ES_HTTP_CODE" == "404" ]]; then
    return 0
  fi

  echo "Elasticsearch request failed for ${method} ${path}: HTTP ${ES_HTTP_CODE}." >&2
  if [[ -s "$response_body" ]]; then
    cat "$response_body" >&2
    echo >&2
  fi
  return 1
}

es_count_index() {
  local index_name="$1"
  local count

  if [[ "${ES_INDEX_EXISTS[$index_name]:-0}" != "1" ]]; then
    printf '0\n'
    return 0
  fi

  es_request GET "/${index_name}/_count"
  count="$(sed -n 's/.*"count"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$ES_RESPONSE_BODY_FILE" | sed -n '1p')"
  if [[ ! "$count" =~ ^[0-9]+$ ]]; then
    echo "Could not parse Elasticsearch count for ${index_name}." >&2
    cat "$ES_RESPONSE_BODY_FILE" >&2
    echo >&2
    return 1
  fi

  printf '%s\n' "$count"
}

redis_command() {
  local command="$1"

  docker exec pai_smart_redis sh -lc '
    if [ -n "$1" ]; then
      redis-cli --no-auth-warning -a "$1" "$2"
    else
      redis-cli "$2"
    fi
  ' sh "$REDIS_PASSWORD_VALUE" "$command"
}

minio_bucket_status() {
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

    status=missing
    while IFS= read -r line; do
      last_field=
      for field in $line; do
        last_field="$field"
      done
      if [ "$last_field" = "${bucket}/" ]; then
        status=present
      fi
    done <"$bucket_list"

    printf "%s\n" "$status"
  ' sh "$MINIO_BUCKET_NAME_VALUE"
}

minio_object_count() {
  if [[ "$MINIO_BUCKET_EXISTS" != "1" ]]; then
    printf '0\n'
    return 0
  fi

  docker exec pai_smart_minio sh -lc '
    set -eu

    bucket="$1"
    object_list="$(mktemp)"

    cleanup() {
      rm -f "$object_list"
    }
    trap cleanup EXIT

    mc alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
    mc ls --recursive "local/${bucket}" >"$object_list"
    wc -l <"$object_list" | tr -d " "
  ' sh "$MINIO_BUCKET_NAME_VALUE"
}

preflight_mysql() {
  local unknown_user_fks

  if ! mysql_query paismart -N -e "SELECT 1;" >/dev/null; then
    fail "MySQL preflight failed for paismart."
  fi

  if ! mysql_query paperloom_eval -N -e "SELECT 1;" >/dev/null; then
    fail "MySQL preflight failed for paperloom_eval."
  fi

  ADMIN_COUNT_BEFORE="$(mysql_scalar paismart "SELECT COUNT(*) FROM users WHERE username='admin';")"
  if [[ ! "$ADMIN_COUNT_BEFORE" =~ ^[0-9]+$ || "$ADMIN_COUNT_BEFORE" -lt 1 ]]; then
    fail "Admin user preflight failed: admin_count=${ADMIN_COUNT_BEFORE:-empty}."
  fi

  unknown_user_fks="$(mysql_query paismart -N -B -e "
SELECT CONCAT(TABLE_NAME, '.', COLUMN_NAME)
FROM information_schema.KEY_COLUMN_USAGE
WHERE TABLE_SCHEMA='paismart'
  AND REFERENCED_TABLE_NAME='users'
  AND TABLE_NAME NOT IN ('conversations', 'conversation_sessions', 'paper_collections', 'invite_codes', 'organization_tags')
ORDER BY TABLE_NAME, COLUMN_NAME;
")"
  if [[ -n "$unknown_user_fks" ]]; then
    echo "Refusing to delete non-admin users; unhandled users FK dependencies exist:" >&2
    echo "$unknown_user_fks" >&2
    exit 1
  fi
}

preflight_es() {
  local index_name

  es_request GET "/_cluster/health"

  # A fresh dev stack may not have product ES indices yet. Missing product indices are treated as
  # already empty and skipped during delete/verify; auth, transport, and other HTTP errors fail.
  for index_name in "${PRODUCT_ES_INDICES[@]}"; do
    es_request GET "/${index_name}" "" true
    if [[ "$ES_HTTP_CODE" == "404" ]]; then
      ES_INDEX_EXISTS["$index_name"]=0
      echo "Elasticsearch index ${index_name} is missing; treating it as already empty."
    else
      ES_INDEX_EXISTS["$index_name"]=1
    fi
  done
}

preflight_redis() {
  local result

  if ! result="$(redis_command PING 2>&1)"; then
    echo "Redis preflight PING failed." >&2
    echo "$result" >&2
    exit 1
  fi

  if [[ "$result" != "PONG" ]]; then
    fail "Redis preflight PING returned unexpected output: ${result}"
  fi
}

preflight_minio() {
  local status

  if ! status="$(minio_bucket_status 2>&1)"; then
    echo "MinIO preflight failed." >&2
    echo "$status" >&2
    exit 1
  fi

  case "$status" in
    present)
      MINIO_BUCKET_EXISTS=1
      ;;
    missing)
      MINIO_BUCKET_EXISTS=0
      echo "MinIO bucket ${MINIO_BUCKET_NAME_VALUE} is missing; treating it as already empty."
      ;;
    *)
      fail "MinIO preflight returned unexpected bucket status: ${status}"
      ;;
  esac
}

preflight_kafka() {
  if ! docker exec "$KAFKA_CONTAINER" sh -lc '/opt/bitnami/kafka/bin/kafka-topics.sh --bootstrap-server "$1" --list >/dev/null' sh "$KAFKA_BOOTSTRAP_SERVERS"; then
    fail "Kafka preflight failed for container ${KAFKA_CONTAINER}."
  fi
}

preflight() {
  echo "Preflight: checking MySQL, Elasticsearch, Redis, MinIO, and Kafka."
  preflight_mysql
  preflight_es
  preflight_redis
  preflight_minio
  preflight_kafka
}

reset_mysql() {
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
CALL paperloom_reset_delete_if_exists('conversation_source_quotes');
CALL paperloom_reset_delete_if_exists('paper_conversation_reference');
CALL paperloom_reset_delete_if_exists('paper_collection_papers');
CALL paperloom_reset_delete_if_exists('paper_collections');
CALL paperloom_reset_delete_if_exists('paper_visual_assets');
CALL paperloom_reset_delete_if_exists('paper_parser_artifacts');
CALL paperloom_reset_delete_if_exists('paper_source_quotes');
CALL paperloom_reset_delete_if_exists('paper_reading_elements');
CALL paperloom_reset_delete_if_exists('paper_locations');
CALL paperloom_reset_delete_if_exists('paper_sections');
CALL paperloom_reset_delete_if_exists('paper_pages');
CALL paperloom_reset_delete_if_exists('paper_reading_models');
CALL paperloom_reset_delete_if_exists('paper_tables');
CALL paperloom_reset_delete_if_exists('paper_figures');
CALL paperloom_reset_delete_if_exists('paper_formulas');
CALL paperloom_reset_delete_if_exists('paper_text_chunks');
CALL paperloom_reset_delete_if_exists('document_vectors');
CALL paperloom_reset_delete_if_exists('chunk_info');
CALL paperloom_reset_delete_if_exists('paper_processing_tasks');
CALL paperloom_reset_delete_if_exists('file_upload');

DELETE FROM recharge_orders
WHERE user_id NOT IN (SELECT CAST(id AS CHAR) FROM users WHERE username = 'admin');

DELETE FROM user_token_record
WHERE user_id NOT IN (SELECT CAST(id AS CHAR) FROM users WHERE username = 'admin');

DELETE FROM user_daily_chat_count
WHERE user_id NOT IN (SELECT CAST(id AS CHAR) FROM users WHERE username = 'admin');

DELETE ic
FROM invite_codes ic
LEFT JOIN users u ON u.id = ic.created_by
WHERE u.id IS NULL OR u.username <> 'admin';

UPDATE organization_tags child
LEFT JOIN organization_tags parent ON parent.tag_id = child.parent_tag
SET child.parent_tag = NULL
WHERE parent.tag_id REGEXP '^(eval-|paperloom-eval-)';

DELETE FROM organization_tags
WHERE tag_id REGEXP '^(eval-|paperloom-eval-)';

UPDATE organization_tags child
LEFT JOIN organization_tags parent ON parent.tag_id = child.parent_tag
LEFT JOIN users parent_creator ON parent_creator.id = parent.created_by
SET child.parent_tag = NULL
WHERE child.parent_tag IS NOT NULL
  AND (parent.tag_id IS NULL OR parent_creator.id IS NULL OR parent_creator.username <> 'admin');

DELETE ot
FROM organization_tags ot
LEFT JOIN users u ON u.id = ot.created_by
WHERE u.id IS NULL OR u.username <> 'admin';

DELETE FROM users WHERE username <> 'admin';

DROP PROCEDURE IF EXISTS paperloom_reset_delete_if_exists;

SET FOREIGN_KEY_CHECKS=1;
SQL
}

reset_es() {
  local index_name

  for index_name in "${PRODUCT_ES_INDICES[@]}"; do
    if [[ "${ES_INDEX_EXISTS[$index_name]:-0}" != "1" ]]; then
      echo "Skipping Elasticsearch delete for missing index ${index_name}."
      continue
    fi

    es_request DELETE "/${index_name}"
    ES_INDEX_EXISTS["$index_name"]=0
  done
}

reset_redis() {
  local result

  if ! result="$(redis_command FLUSHDB 2>&1)"; then
    echo "Redis FLUSHDB failed." >&2
    echo "$result" >&2
    exit 1
  fi

  if [[ "$result" != "OK" ]]; then
    fail "Redis FLUSHDB returned unexpected output: ${result}"
  fi
}

reset_minio() {
  local object_count

  if [[ "$MINIO_BUCKET_EXISTS" != "1" ]]; then
    echo "Skipping MinIO cleanup because bucket ${MINIO_BUCKET_NAME_VALUE} is missing."
    return 0
  fi

  if ! object_count="$(minio_object_count 2>&1)"; then
    echo "MinIO object count failed before cleanup." >&2
    echo "$object_count" >&2
    exit 1
  fi

  if [[ "$object_count" == "0" ]]; then
    echo "MinIO bucket ${MINIO_BUCKET_NAME_VALUE} is already empty."
    return 0
  fi

  docker exec pai_smart_minio sh -lc '
    set -eu

    bucket="$1"
    mc alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
    mc rm --recursive --force "local/${bucket}" >/dev/null
  ' sh "$MINIO_BUCKET_NAME_VALUE"
}

reset_kafka() {
  local topic

  for topic in "${PRODUCT_KAFKA_TOPICS[@]}"; do
    docker exec "$KAFKA_CONTAINER" sh -lc '
      set -eu
      bootstrap="$1"
      topic="$2"
      /opt/bitnami/kafka/bin/kafka-topics.sh \
        --bootstrap-server "$bootstrap" \
        --delete \
        --if-exists \
        --topic "$topic" >/dev/null
    ' sh "$KAFKA_BOOTSTRAP_SERVERS" "$topic"
  done

  for topic in "${PRODUCT_KAFKA_TOPICS[@]}"; do
    docker exec "$KAFKA_CONTAINER" sh -lc '
      set -eu
      bootstrap="$1"
      topic="$2"
      for _ in $(seq 1 30); do
        if ! /opt/bitnami/kafka/bin/kafka-topics.sh --bootstrap-server "$bootstrap" --list | grep -Fx "$topic" >/dev/null; then
          exit 0
        fi
        sleep 1
      done
      echo "Kafka topic still exists after delete: ${topic}" >&2
      exit 1
    ' sh "$KAFKA_BOOTSTRAP_SERVERS" "$topic"
  done
}

record_verification_failure() {
  local message="$1"

  echo "$message" >&2
  VERIFY_FAILURES=$((VERIFY_FAILURES + 1))
}

print_assert_zero() {
  local label="$1"
  local count="$2"

  echo "${label} ${count}"
  if [[ ! "$count" =~ ^[0-9]+$ ]]; then
    record_verification_failure "${label} is not a numeric count: ${count}"
    return
  fi

  if [[ "$count" -ne 0 ]]; then
    record_verification_failure "${label} expected 0 but found ${count}"
  fi
}

verify_admin_count() {
  local admin_count

  admin_count="$(mysql_scalar paismart "SELECT COUNT(*) FROM users WHERE username='admin';")"
  echo "admin_count ${admin_count}"

  if [[ ! "$admin_count" =~ ^[0-9]+$ ]]; then
    record_verification_failure "admin_count is not numeric: ${admin_count}"
    return
  fi

  if [[ "$admin_count" -lt 1 ]]; then
    record_verification_failure "admin_count must be at least 1"
  fi

  if [[ "$admin_count" -ne "$ADMIN_COUNT_BEFORE" ]]; then
    record_verification_failure "admin_count changed from ${ADMIN_COUNT_BEFORE} to ${admin_count}"
  fi
}

verify_product_db_counts() {
  local table
  local count
  local label

  for table in "${PRODUCT_DB_TABLES[@]}"; do
    count="$(mysql_count_table paismart "$table")"
    case "$table" in
      file_upload)
        label="product_papers"
        ;;
      paper_text_chunks)
        label="product_chunks"
        ;;
      paper_collection_papers)
        label="product_collection_memberships"
        ;;
      paper_collections)
        label="product_collections"
        ;;
      *)
        label="$table"
        ;;
    esac
    print_assert_zero "$label" "$count"
  done

  for table in "${OPTIONAL_PRODUCT_DB_TABLES[@]}"; do
    if mysql_table_exists paismart "$table"; then
      count="$(mysql_count_table paismart "$table")"
      print_assert_zero "$table" "$count"
    else
      echo "${table} 0"
      echo "${table}_status absent"
    fi
  done
}

verify_user_dependent_counts() {
  print_assert_zero "non_admin_users" "$(mysql_scalar paismart "SELECT COUNT(*) FROM users WHERE username <> 'admin';")"
  print_assert_zero "user_token_record_non_admin" "$(mysql_scalar paismart "SELECT COUNT(*) FROM user_token_record WHERE user_id NOT IN (SELECT CAST(id AS CHAR) FROM users WHERE username='admin');")"
  print_assert_zero "user_daily_chat_count_non_admin" "$(mysql_scalar paismart "SELECT COUNT(*) FROM user_daily_chat_count WHERE user_id NOT IN (SELECT CAST(id AS CHAR) FROM users WHERE username='admin');")"
  print_assert_zero "recharge_orders_non_admin" "$(mysql_scalar paismart "SELECT COUNT(*) FROM recharge_orders WHERE user_id NOT IN (SELECT CAST(id AS CHAR) FROM users WHERE username='admin');")"
  print_assert_zero "invite_codes_non_admin_created" "$(mysql_scalar paismart "SELECT COUNT(*) FROM invite_codes ic LEFT JOIN users u ON u.id = ic.created_by WHERE u.id IS NULL OR u.username <> 'admin';")"
  print_assert_zero "organization_tags_non_admin_created" "$(mysql_scalar paismart "SELECT COUNT(*) FROM organization_tags ot LEFT JOIN users u ON u.id = ot.created_by WHERE u.id IS NULL OR u.username <> 'admin';")"
  print_assert_zero "organization_tags_eval_residue" "$(mysql_scalar paismart "SELECT COUNT(*) FROM organization_tags WHERE tag_id REGEXP '^(eval-|paperloom-eval-)';")"
}

verify_eval_counts() {
  echo "eval_litsearch_papers $(mysql_scalar paperloom_eval "SELECT COUNT(*) FROM eval_papers WHERE corpus='litsearch';")"
  echo "eval_qasper_papers $(mysql_scalar paperloom_eval "SELECT COUNT(*) FROM eval_papers WHERE corpus='qasper';")"
}

verify_es_counts() {
  local index_name
  local count

  for index_name in "${PRODUCT_ES_INDICES[@]}"; do
    count="$(es_count_index "$index_name")"
    print_assert_zero "${index_name}_es_docs" "$count"
    if [[ "${ES_INDEX_EXISTS[$index_name]:-0}" != "1" ]]; then
      echo "${index_name}_status missing_treated_as_empty"
    fi
  done
}

verify_redis_count() {
  local dbsize

  if ! dbsize="$(redis_command DBSIZE 2>&1)"; then
    echo "Redis DBSIZE verification failed." >&2
    echo "$dbsize" >&2
    exit 1
  fi

  print_assert_zero "redis_dbsize" "$dbsize"
}

verify_minio_count() {
  local object_count

  if ! object_count="$(minio_object_count 2>&1)"; then
    echo "MinIO object count verification failed." >&2
    echo "$object_count" >&2
    exit 1
  fi

  print_assert_zero "minio_upload_objects" "$object_count"
  if [[ "$MINIO_BUCKET_EXISTS" != "1" ]]; then
    echo "minio_upload_bucket_status missing_treated_as_empty"
  fi
}

verify_kafka_topics() {
  local topic
  local exists

  for topic in "${PRODUCT_KAFKA_TOPICS[@]}"; do
    exists="$(docker exec "$KAFKA_CONTAINER" sh -lc '
      set -eu
      bootstrap="$1"
      topic="$2"
      if /opt/bitnami/kafka/bin/kafka-topics.sh --bootstrap-server "$bootstrap" --list | grep -Fx "$topic" >/dev/null; then
        echo 1
      else
        echo 0
      fi
    ' sh "$KAFKA_BOOTSTRAP_SERVERS" "$topic")"
    print_assert_zero "${topic}_kafka_topic_exists" "$exists"
  done
}

verify_reset() {
  echo "Verification counts:"
  verify_admin_count
  verify_product_db_counts
  verify_user_dependent_counts
  verify_eval_counts
  verify_es_counts
  verify_redis_count
  verify_minio_count
  verify_kafka_topics

  if [[ "$VERIFY_FAILURES" -ne 0 ]]; then
    fail "Reset verification failed with ${VERIFY_FAILURES} failed postcondition(s)."
  fi
}

echo "Preserving: admin user and paperloom_eval benchmark corpus."
echo "Deleting: product papers, product collections, product chunks, product chat/session history, Redis runtime keys, product ES docs, product MinIO objects."

preflight
reset_mysql
reset_es
reset_redis
reset_minio
reset_kafka
verify_reset
