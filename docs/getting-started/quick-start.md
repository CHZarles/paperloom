# Quick Start

This guide starts a local PaperLoom development environment. It separates infrastructure, the PDF
parser, the research harness, the Spring Boot backend, and the Citewise frontend so failures remain
visible instead of being hidden behind a single launcher.

## Requirements

- Git
- Java 17
- Maven 3.8 or newer
- Node.js 18.20 or newer
- pnpm 8.7 or newer
- Python 3.11 or newer
- Docker with Docker Compose v2
- A MinerU Cloud API token for real PDF ingestion

Recommended local capacity is at least 6 GB of free memory because the repository's full local stack
starts MySQL, MinIO, Redis, Kafka, Qdrant, and the backend together.

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
QDRANT_API_KEY
MINIMAX_API_KEY
```

The full Docker Compose stack also requires `SPRING_DATA_REDIS_PASSWORD`. Java builds and queries the
Sparse-only Qdrant Reading Model index locally; no embedding provider is required.

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
| Qdrant HTTP | `6333` |
| Qdrant gRPC | `6334` |

Use the corresponding `*_HOST_PORT` variables when those ports are already occupied.

MySQL is the canonical paper source. Qdrant stores a rebuildable candidate index over Current Reading
Models. The Compose service requires an API key and binds its host ports to loopback; Java must use
the same key. Kafka supports upload processing and Redis supports separate transient product concerns.

## 3. Configure MinerU Cloud API

Create an Access Key / Secret Key pair in [MinerU API management](https://mineru.net/apiManage/docs),
then set it outside the repository or in your local `.env`:

```bash
PAPER_PARSING_MINERU_BASE_URL=https://mineru.net
PAPER_PARSING_MINERU_ACCESS_KEY=replace-with-your-access-key
PAPER_PARSING_MINERU_SECRET_KEY=replace-with-your-secret-key
PAPER_PARSING_MINERU_MODEL_VERSION=vlm
```

PaperLoom exchanges the Access Key / Secret Key pair for a short-lived MinerU access token, requests
a signed upload URL, uploads the PDF directly to it, then polls the batch result and stores the
returned ZIP artifact. The normal product path fails explicitly if the credentials are absent or
MinerU rejects the task; it does not silently downgrade to a weaker parser. A legacy manually issued
`PAPER_PARSING_MINERU_API_TOKEN` is still accepted as an alternative to the key pair.

## 4. Start the Research Harness

```bash
python3 -m venv .venv-harness
.venv-harness/bin/pip install -r harness_py/requirements.lock
scripts/paperloom-start-harness.sh start
scripts/paperloom-start-harness.sh status
```

The harness health endpoint is `http://127.0.0.1:8091/health`.

To expose the authenticated PaperLoom MCP tools on loopback:

```bash
uv venv .venv-mcp
uv pip sync paperloom_mcp/requirements.lock --python .venv-mcp/bin/python
.venv-mcp/bin/python -m paperloom_mcp --host 127.0.0.1 --port 8090
```

MCP clients connect to `http://127.0.0.1:8090/mcp`. The local MCP adapter advertises OAuth protected
resource metadata at `/.well-known/oauth-protected-resource` and starts the browser authorization
flow on the first tool call that needs a token. The user enters the PaperLoom password on the
PaperLoom login page; the adapter receives the code at its loopback callback and exchanges it with
PKCE. The first local client is pre-registered as `paperloom-local-mcp` with the loopback callback
`http://127.0.0.1:8095/oauth/callback`. Authorization Code + PKCE is required and the default issuer
is `http://127.0.0.1:8081` (override it with `paperloom.oauth2.issuer` when the backend uses another
URL).

The server exposes paper reading plus the normal PDF upload flow: `upload_paper_chunk` and
`get_paper_upload_status`. OAuth access tokens need `paper.read` for reading and `paper.upload` for
uploading. Upload chunks are base64 encoded. When the uploaded chunk set is complete, the MCP server
invokes the normal merge operation automatically. In stdio mode, the local access and refresh token
are stored in `~/.paperloom/mcp-token.json` with owner-only permissions (override it with
`PAPERLOOM_OAUTH_TOKEN_FILE`).

For a desktop client such as OpenCode, use the stdio transport so the client starts the MCP adapter
itself. Add this to the project's `opencode.json` (or merge it into the existing `mcp` object):

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "paperloom": {
      "type": "local",
      "command": [
        "/absolute/path/to/paperloom/scripts/paperloom-mcp-stdio.sh"
      ],
      "enabled": true
    }
  }
}
```

OpenCode then launches the stdio adapter when it starts. The launcher also starts the Java backend
through the repository's existing backend script. Docker data services must already be running.
The HTTP transport remains available for clients that connect to a shared endpoint.

For a deployed server, run the HTTP MCP process on the server and put it behind the same reverse
proxy or load balancer as the authorization server. The Python MCP process does not open a browser
and does not need a local callback on the server; the remote client opens the user's browser and
receives the authorization code on its own loopback callback.

```bash
uv venv .venv-mcp
uv pip sync paperloom_mcp/requirements.lock --python .venv-mcp/bin/python
export PAPERLOOM_MCP_PUBLIC_URL=https://mcp.example.com
export PAPERLOOM_OAUTH_ISSUER=https://paperloom.example.com
scripts/paperloom-start-mcp.sh start
```

The backend must use the same public issuer (`PAPERLOOM_OAUTH_ISSUER`). The proxy must forward
`/mcp`, `/.well-known/oauth-protected-resource`, the authorization server discovery endpoints,
`/oauth2/authorize`, `/oauth2/token`, `/oauth2/jwks`, `/login`, and `/oauth2/consent`. Remote
OpenCode configuration is then only a URL:

```json
{
  "mcp": {
    "paperloom": {
      "type": "remote",
      "url": "https://mcp.example.com/mcp",
      "enabled": true,
      "oauth": {
        "scope": "paper.read paper.upload"
      }
    }
  }
}
```

OpenCode discovers the protected resource metadata, dynamically registers a public PKCE client,
opens the PaperLoom login page, and sends the resulting Bearer token to the deployed MCP process.
No Python package or MCP process is required on the client machine in this mode.

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

If canonical Current Reading Models were imported without their lexical index, rebuild the complete
Qdrant collection once after the backend starts:

```bash
curl -X POST http://localhost:8081/api/v1/admin/retrieval/rebuild-all \
  -H "Authorization: Bearer $ADMIN_JWT"
```

This is a destructive, synchronous lexical rebuild and does not call an embedding provider. Newly
processed papers are indexed into Qdrant automatically.

## 6. Start Citewise

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

The chat check proves the live path only when Python calls the Java Corpus API, Java retrieves a
Current `location_ref` from Qdrant, and `read_locations` reopens exact MySQL content. A Qdrant write
alone is not evidence that the model read or cited that location.

## Shutdown

```bash
scripts/paperloom-start-backend.sh stop
scripts/paperloom-start-harness.sh stop
docker compose --env-file .env -f docs/docker-compose.yaml down
```

Named Docker volumes are preserved unless you explicitly remove them.
