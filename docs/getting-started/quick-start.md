# Quick Start

This guide starts a local PaperLoom development environment. It separates infrastructure, the PDF
parser, the research harness, the Spring Boot backend, and the Folio frontend so failures remain
visible instead of being hidden behind a single launcher.

## Requirements

- Git
- Java 17
- Maven 3.8 or newer
- Node.js 18.20 or newer
- pnpm 8.7 or newer
- Python 3.11 or newer
- Docker with Docker Compose v2
- MinerU installed separately for real PDF ingestion

Recommended local capacity is at least 8 GB of free memory because the repository's full local stack
starts MySQL, MinIO, Redis, Kafka, Elasticsearch, the backend, and the parser together. The current
assistant answer path itself uses Java, the Python harness, MySQL, MinIO-backed evidence assets, and
the model provider; Elasticsearch does not contribute assistant evidence.

## 1. Configure the Environment

From the repository root:

```bash
cp .env.example .env
```

Fill every secret required by the services you will start. For the current assistant path, review:

```text
SPRING_DATASOURCE_PASSWORD
MINIO_ACCESS_KEY
MINIO_SECRET_KEY
JWT_SECRET_KEY
RESEARCH_HARNESS_INTERNAL_TOKEN
MINIMAX_API_KEY or another configured model provider
```

The full Docker Compose stack also requires `SPRING_DATA_REDIS_PASSWORD` and
`ELASTICSEARCH_PASSWORD`. Embedding credentials belong to the independent indexing/search path and
are not used by the Python research harness.

Generate a JWT secret with:

```bash
openssl rand -base64 32
```

For the first local boot, set `ADMIN_BOOTSTRAP_ENABLED=true` and choose a strong administrator
password. Set it back to `false` after the account has been created.

## 2. Start Data Services

```bash
docker compose --env-file .env -f docs/docker-compose.yaml up -d
docker compose --env-file .env -f docs/docker-compose.yaml ps
```

The default local ports are:

| Service | Port |
| --- | ---: |
| MySQL | `3306` |
| Redis | `6379` |
| Kafka | `9092` |
| MinIO API | `9000` |
| MinIO console | `9001` |
| Elasticsearch | `9200` |

Use the corresponding `*_HOST_PORT` variables when those ports are already occupied.

MySQL is the direct corpus source for current research turns. Kafka supports upload processing,
Redis supports separate transient product concerns, and Elasticsearch supports maintained
standalone/indexing behavior; none of those distinctions changes the Java-authorized paper scope.

## 3. Start MinerU

PaperLoom expects a self-hosted MinerU API. Install MinerU using its official instructions, then
point the launcher at the environment containing `mineru-api`:

```bash
export PAPERLOOM_MINERU_VENV_BIN="$HOME/.local/share/paperloom-mineru/.venv/bin"
scripts/paperloom-start-mineru.sh start
scripts/paperloom-start-mineru.sh status
```

The default endpoint is `http://127.0.0.1:8000/health`. Set
`PAPER_PARSING_MINERU_BASE_URL` when using a different host or port.

Paper processing fails explicitly when MinerU is unavailable. It does not silently downgrade the
normal product path to a weaker parser.

## 4. Start the Research Harness

```bash
python3 -m venv .venv-harness
.venv-harness/bin/pip install -r harness_py/requirements.lock
scripts/paperloom-start-harness.sh start
scripts/paperloom-start-harness.sh status
```

The harness health endpoint is `http://127.0.0.1:8091/health`.

## 5. Start the Backend

For normal development:

```bash
mvn spring-boot:run
```

Or build and use the managed launcher:

```bash
mvn -DskipTests package
scripts/paperloom-start-backend.sh start
```

The backend listens on `http://localhost:8081` by default. A `401` or `403` response from an
authenticated endpoint still proves that the HTTP server is reachable.

## 6. Start Folio

```bash
cd frontend
corepack pnpm install
corepack pnpm dev
```

Open `http://localhost:9527` and sign in with the bootstrapped administrator account.

## 7. Verify the Paper Loop

1. Upload a research-paper PDF from the Library.
2. Confirm parsing completes and the current Reading Model reaches `READING_MODEL_READY`.
3. Open a new research conversation and select the paper as a source.
4. Ask a question that requires an inspectable page, table, figure, or formula.
5. Open at least one returned reference and confirm it resolves to the expected paper evidence.

The chat check proves the live path only when Python loads the selected paper from MySQL and performs
its in-memory Reading Element retrieval. A successful independent search-index write is not evidence
that chat used that index.

## Shutdown

```bash
scripts/paperloom-start-backend.sh stop
scripts/paperloom-start-harness.sh stop
scripts/paperloom-start-mineru.sh stop
docker compose --env-file .env -f docs/docker-compose.yaml down
```

Named Docker volumes are preserved unless you explicitly remove them.
