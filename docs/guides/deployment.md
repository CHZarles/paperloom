# Deployment Guide

This document describes the production shape rather than a provider-specific recipe. PaperLoom has
multiple stateful services and should be deployed as an application stack, not as one standalone JAR.

Wuyun 上通过 Cloudflare Tunnel 完成私有源站和公网域名部署的完整实录见
[Wuyun + Cloudflare Tunnel 部署实录](wuyun-cloudflare-tunnel-deployment.md)。

## Components

Current assistant and evidence path:

- Citewise static frontend
- Spring Boot backend
- Python research harness (`harness_py serve`)
- MinerU cloud parsing API
- MySQL
- MinIO
- Qdrant

Full repository support services:

- Kafka for asynchronous upload-processing delivery
- Redis for separate transient product concerns

## Build Artifacts

Backend:

```bash
mvn clean package -DskipTests
```

Frontend:

```bash
cd frontend
corepack pnpm install --frozen-lockfile
corepack pnpm build
```

Project site:

```bash
cd site
npm ci
npm run docs:build
```

## Configuration

Create deployment-specific environment configuration outside the repository. The complete variable
groups are listed in [Configuration Reference](../reference/configuration.md).

Production requirements include:

- unique database, Redis, MinIO, Qdrant, JWT, and internal-service secrets;
- TLS at the public edge and trusted certificates for internal HTTPS connections;
- `ADMIN_BOOTSTRAP_ENABLED=false` after initial provisioning;
- explicit CORS origins;
- private network access for data services and the research harness;
- MinerU credentials (`PAPER_PARSING_MINERU_ACCESS_KEY` / `PAPER_PARSING_MINERU_SECRET_KEY`) stored
  as deployment secrets;
- `RESEARCH_HARNESS_BASE_URL` pointing at the local `harness_py serve` process;
- persistent volumes and a tested backup policy;
- resource limits suitable for Qdrant, embedding traffic, and PDF parsing.

Keep Qdrant on a private network, require a non-empty API key in every production deployment, and
test snapshots. The backend production profile fails closed when `qdrant.api-key` or the internal
Harness token is blank.

## Reverse Proxy

The example [Nginx configuration](../nginx.conf) serves the frontend and proxies the product API and
WebSocket endpoint. Review hostnames, TLS paths, body limits, timeouts, and security headers before
using it.

Long-running PDF parsing and streamed research responses need timeouts that are longer than a normal
CRUD API. WebSocket upgrade headers must be forwarded explicitly.

## Process Order

1. Start and verify MySQL, Redis, Kafka, MinIO, and Qdrant.
2. Apply the database schema or allow the configured migration mechanism to run.
3. Configure the MinerU cloud API token and verify it with one disposable PDF upload.
4. Start the research harness:

   ```bash
   .venv-harness/bin/python -m harness_py serve \
     --host 127.0.0.1 --port 8091 --internal-token "$RESEARCH_HARNESS_INTERNAL_TOKEN"
   ```

   One process serves every research turn; raise `RESEARCH_HARNESS_MAX_ACTIVE_GENERATIONS` to allow
   more turns in flight. There is no worker pool.
5. Start the backend and verify authentication and dependency health.
6. If canonical Current Reading Models were imported without their lexical index, call the
   destructive `POST /api/v1/admin/retrieval/rebuild-all` operation once with an administrator token
   and allow a long request timeout.
7. Publish the frontend assets.
8. Run an authenticated upload, processing, research, and reference-reopen smoke test.

For the research portion of that smoke test, verify that Java streams the turn to
`/v1/research/stream`, Python calls the Java Corpus API with `user_id` and a locked paper scope,
Qdrant returns only current scoped locations, and returned citations reopen exact MySQL content.

## Release Verification

At minimum, verify:

- login and token refresh;
- private and public paper authorization;
- PDF upload and processing-state transitions;
- parser artifact and visual asset persistence;
- paper search and scoped paper QA;
- streamed answer completion and cancellation;
- answer regenerate on a completed assistant message: the visible message is replaced, revision
  history still exposes the previous answer and its references;
- historical conversation reload;
- reference detail reopening;
- restart behavior for backend and harness processes.

## Backups

Back up MySQL and MinIO together because metadata and objects form one logical paper record. Preserve
Qdrant snapshots for recovery speed, but treat the collection as rebuildable from Current Reading
Models. Redis and Kafka should not be the only location of user-visible durable state.
