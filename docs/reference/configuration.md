# Configuration Reference

PaperLoom reads local development values from the repository-root `.env`. Production deployments
should inject the same variables through their secret and configuration system.

## Application

| Variable | Purpose |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | Spring profile, normally `dev` locally |
| `SERVER_PORT` | Backend HTTP port |
| `APP_TIMEZONE` | Application timezone |
| `SECURITY_ALLOWED_ORIGINS` | Explicit browser-origin allowlist |

## Data Services

| Group | Representative variables | Current role |
| --- | --- | --- |
| MySQL | `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | Canonical product state and exact Reading Model content |
| MinIO | `MINIO_ENDPOINT`, `MINIO_PUBLIC_URL`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY`, `MINIO_BUCKET_NAME` | PDFs, parser artifacts, screenshots, and crops |
| Redis | `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT`, `SPRING_DATA_REDIS_PASSWORD` | Separate transient product concerns; not assistant evidence |
| Kafka | `SPRING_KAFKA_BOOTSTRAP_SERVERS` | Upload-processing delivery; not assistant retrieval |
| Qdrant | `QDRANT_BASE_URL`, `QDRANT_API_KEY`, `QDRANT_COLLECTION`, `QDRANT_CONTRACT` | Hybrid (sparse BM25 + dense MiniMax embedding) candidate index for Current Reading Models; API key is mandatory in production and Compose |

Host-port overrides for `docs/docker-compose.yaml` use `MYSQL_HOST_PORT`, `REDIS_HOST_PORT`,
`MINIO_API_HOST_PORT`, `MINIO_CONSOLE_HOST_PORT`, `QDRANT_HTTP_HOST_PORT`, and
`QDRANT_GRPC_HOST_PORT`.

## Authentication

| Variable | Purpose |
| --- | --- |
| `JWT_SECRET_KEY` | Base64 signing secret |
| `ADMIN_BOOTSTRAP_ENABLED` | Enables one-time initial administrator creation |
| `ADMIN_BOOTSTRAP_USERNAME` | Initial administrator username |
| `ADMIN_BOOTSTRAP_PASSWORD` | Initial administrator password |
| `APP_AUTH_REGISTRATION_MODE` | `OPEN`, `INVITE_ONLY`, or `CLOSED` |
| `APP_AUTH_INVITE_REQUIRED` | Requires an invitation during registration |

## Parser

| Variable | Purpose |
| --- | --- |
| `PAPER_PARSING_PROVIDER` | Normal product value is `mineru` |
| `PAPER_PARSING_MINERU_BASE_URL` | MinerU cloud API base URL, normally `https://mineru.net` |
| `PAPER_PARSING_MINERU_API_TOKEN` | MinerU API management token; required to parse uploads |
| `PAPER_PARSING_MINERU_MODEL_VERSION` | `pipeline`, `vlm` (default), or `MinerU-HTML` |

The optional alternative parser is intended for explicit experiments, not silent production
fallback.

## Research Harness and Models

| Variable | Purpose |
| --- | --- |
| `RESEARCH_HARNESS_TRANSPORT` | `redis` for the scalable worker-pool path, `http` for local debug or rollback |
| `RESEARCH_HARNESS_BASE_URL` | Internal harness HTTP endpoint used only when `RESEARCH_HARNESS_TRANSPORT=http` |
| `RESEARCH_HARNESS_INTERNAL_TOKEN` | Required shared internal-service credential; blank tokens reject Corpus requests |
| `RESEARCH_HARNESS_USER_RETRY_MAX_PER_MESSAGE` | Maximum product-level regenerate attempts for one completed assistant answer |
| `RESEARCH_HARNESS_REDIS_URL` | Redis URL used by `harness_py worker`; Java uses the Spring Redis settings |
| `RESEARCH_HARNESS_REDIS_JOBS_STREAM` | Redis Stream key for Java-to-worker research jobs |
| `RESEARCH_HARNESS_REDIS_EVENTS_PREFIX` | Redis Stream prefix for short-lived worker progress/results |
| `RESEARCH_HARNESS_REDIS_STATUS_PREFIX` | Redis key prefix for short-lived runtime status |
| `RESEARCH_HARNESS_REDIS_CANCEL_PREFIX` | Redis key prefix for cross-process cancellation |
| `RESEARCH_HARNESS_REDIS_LOCK_PREFIX` | Redis key prefix for worker execution locks |
| `RESEARCH_HARNESS_QUEUE_MAX_DEPTH` | Java fail-fast queue depth limit for online research turns |
| `RESEARCH_HARNESS_EVENT_READ_TIMEOUT_SECONDS` | Java wait time for a terminal Redis event before failing the generation |
| `RESEARCH_HARNESS_EVENT_TTL_SECONDS` | Worker TTL for event/status streams and keys |
| `RESEARCH_HARNESS_STALE_PENDING_SECONDS` | Worker reclaim threshold for stale Redis Stream pending jobs |
| `RESEARCH_HARNESS_WORKER_MAX_CONCURRENT_RUNS` | Keep at `1` for V1; scale by running more worker processes |
| `JAVA_CORPUS_BASE_URL` | Java Corpus API base URL used by Python; local default is `http://127.0.0.1:8081` |
| `JAVA_CORPUS_MAX_RESPONSE_BYTES` | Maximum accepted Java Corpus API response body; default is 8 MiB |
| `RESEARCH_HARNESS_PYTHON` | Python executable for local launcher |
| `MINIMAX_API_BASE_URL`, `MINIMAX_API_KEY`, `MINIMAX_MODEL` | Default research model provider |
| `QDRANT_CONTRACT` | `sparse-only-v1` (default) or `sparse-dense-v1`; picks which Qdrant collection schema to use |
| `EVAL_DUMP_DIR` | Optional saved-run output root |

The production research path is Java -> Redis Streams -> one or more `harness_py worker` processes.
No load balancer is required between Java and Python. The HTTP harness server can stay available for
development and emergency rollback, but it is not the scalable production path.

The Python service does not connect to MySQL or Qdrant. It calls the Java Corpus API with the locked
scope; Java owns the hybrid Qdrant retrieval (sparse BM25 + dense MiniMax embedding, RRF-fused),
permission/current-model validation, and canonical reads.

## Secret Rules

- Commit variable names and empty examples, never real values.
- Do not place provider keys in frontend variables.
- Do not log internal tokens or authorization headers in saved traces.
- Rotate credentials after accidental disclosure, even when the file was later deleted.
- Keep production `.env` files outside deployment artifacts and backups intended for sharing.
