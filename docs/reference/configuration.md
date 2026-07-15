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
| Qdrant | `QDRANT_BASE_URL`, `QDRANT_API_KEY`, `QDRANT_COLLECTION` | Shared dense/sparse candidate index for Current Reading Models; API key is mandatory in production and Compose |

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
| `PAPER_PARSING_MINERU_BASE_URL` | MinerU API base URL |
| `PAPERLOOM_MINERU_VENV_BIN` | Local launcher path containing `mineru-api` |

The optional alternative parser is intended for explicit experiments, not silent production
fallback.

## Research Harness and Models

| Variable | Purpose |
| --- | --- |
| `RESEARCH_HARNESS_BASE_URL` | Internal harness endpoint used by Java |
| `RESEARCH_HARNESS_INTERNAL_TOKEN` | Required shared internal-service credential; blank tokens reject Corpus requests |
| `JAVA_CORPUS_BASE_URL` | Java Corpus API base URL used by Python; local default is `http://127.0.0.1:8081` |
| `JAVA_CORPUS_MAX_RESPONSE_BYTES` | Maximum accepted Java Corpus API response body; default is 8 MiB |
| `RESEARCH_HARNESS_PYTHON` | Python executable for local launcher |
| `MINIMAX_API_BASE_URL`, `MINIMAX_API_KEY`, `MINIMAX_MODEL` | Default research model provider |
| `EMBEDDING_API_URL`, `EMBEDDING_API_KEY`, `EMBEDDING_API_MODEL` | Java-owned indexing and query embedding provider |
| `EVAL_DUMP_DIR` | Optional saved-run output root |

The Python service does not connect to MySQL or Qdrant. It calls the Java Corpus API with the locked
scope; Java owns embedding, Qdrant retrieval, permission/current-model validation, and canonical reads.

## Secret Rules

- Commit variable names and empty examples, never real values.
- Do not place provider keys in frontend variables.
- Do not log internal tokens or authorization headers in saved traces.
- Rotate credentials after accidental disclosure, even when the file was later deleted.
- Keep production `.env` files outside deployment artifacts and backups intended for sharing.
