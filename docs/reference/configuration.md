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

## Paper Discovery

Optional overrides for the arXiv discovery surface. Defaults apply when the variables are unset.

| Variable | Default | Purpose |
| --- | --- | --- |
| `PAPER_DISCOVERY_ARXIV_API_BASE_URL` | `https://export.arxiv.org` | arXiv Atom API base URL |
| `PAPER_DISCOVERY_ARXIV_PDF_BASE_URL` | `https://arxiv.org` | PDF download base URL |
| `PAPER_DISCOVERY_ARXIV_TIMEOUT_SECONDS` | `25` | Per-request timeout |
| `PAPER_DISCOVERY_ARXIV_MAX_PDF_BYTES` | `52428800` | Maximum accepted PDF size |
| `PAPER_DISCOVERY_ARXIV_IMPORTS_PER_HOUR` | `20` | Per-user hourly import budget; `0` disables the check |

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
| `PAPER_PARSING_MINERU_ACCESS_KEY` | MinerU API management Access Key; exchanged for a short-lived access token |
| `PAPER_PARSING_MINERU_SECRET_KEY` | MinerU API management Secret Key; signs the token-exchange challenge |
| `PAPER_PARSING_MINERU_AUTH_BASE_URL` | OpenXLab SSO token service used to exchange the key pair |
| `PAPER_PARSING_MINERU_API_TOKEN` | Legacy manually issued token; alternative to the Access Key / Secret Key pair |
| `PAPER_PARSING_MINERU_MODEL_VERSION` | `pipeline`, `vlm` (default), or `MinerU-HTML` |

The optional alternative parser is intended for explicit experiments, not silent production
fallback.

## Research Harness and Models

| Variable | Purpose |
| --- | --- |
| `RESEARCH_HARNESS_BASE_URL` | Internal harness HTTP endpoint; the only research transport |
| `RESEARCH_HARNESS_INTERNAL_TOKEN` | Required shared internal-service credential; blank tokens reject Corpus requests |
| `RESEARCH_HARNESS_MAX_ACTIVE_GENERATIONS` | Concurrent research turns allowed in flight |
| `RESEARCH_HARNESS_USER_RETRY_MAX_PER_MESSAGE` | Maximum product-level regenerate attempts for one completed assistant answer |
| `RESEARCH_HARNESS_HTTP_REQUEST_TIMEOUT_SECONDS` | Total HTTP research-generation timeout; defaults to 930 seconds |
| `JAVA_CORPUS_BASE_URL` | Java Corpus API base URL used by Python; local default is `http://127.0.0.1:8081` |
| `JAVA_CORPUS_MAX_RESPONSE_BYTES` | Maximum accepted Java Corpus API response body; default is 8 MiB |
| `RESEARCH_HARNESS_PYTHON` | Python executable for local launcher |
| `MINIMAX_API_BASE_URL`, `MINIMAX_API_KEY`, `MINIMAX_MODEL` | Bootstrap values for the default research model provider; the admin page persists provider rows in MySQL `model_provider_configs` and those rows take precedence |
| `QDRANT_CONTRACT` | `sparse-only-v1` (default) or `sparse-dense-v1`; picks which Qdrant collection schema to use |
| `AGENT_TRACE_DIR` | Private production directory for complete per-Run model, tool, validation, and result JSON |
| `AGENT_TRACE_RETENTION_DAYS` | Delete completed Agent traces older than this many days; default `7` |
| `AGENT_TRACE_MAX_BYTES` | Delete oldest completed Runs until total trace storage is below this byte limit; default `10737418240` |
| `AGENT_TRACE_INCOMPLETE_GRACE_HOURS` | Keep unfinished Run directories this long before treating them as abandoned; default `24` |
| `EVAL_DUMP_DIR` | Optional offline-evaluation saved-run root; live service prefers `AGENT_TRACE_DIR` |

The production research path is Java -> HTTP -> the single `harness_py serve` process. No load
balancer is required between Java and Python. The earlier Redis Streams worker transport
(`harness_py worker`) was removed in `b0f83ec` (2026-11-04, *collapse to a single HTTP harness
transport*); the HTTP server is now the only research path, not a development fallback.

The Python service does not connect to MySQL or Qdrant. It calls the Java Corpus API with the locked
scope; Java owns the hybrid Qdrant retrieval (sparse BM25 + dense MiniMax embedding, RRF-fused),
permission/current-model validation, and canonical reads.

## Secret Rules

- Commit variable names and empty examples, never real values.
- Do not place provider keys in frontend variables.
- Do not log internal tokens or authorization headers in saved traces.
- Rotate credentials after accidental disclosure, even when the file was later deleted.
- Keep production `.env` files outside deployment artifacts and backups intended for sharing.
