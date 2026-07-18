# Deployment Guide

This document describes the production shape rather than a provider-specific recipe. PaperLoom has
multiple stateful services and should be deployed as an application stack, not as one standalone JAR.

## Components

Current assistant and evidence path:

- Folio static frontend
- Spring Boot backend
- Python research harness
- MinerU parser service
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
- private network access for data services, MinerU, and the research harness;
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
3. Start MinerU and confirm its health endpoint.
4. Start the research harness and confirm its internal health endpoint.
5. Start the backend and verify authentication and dependency health.
6. If canonical Current Reading Models were imported without their lexical index, call the
   destructive `POST /api/v1/admin/retrieval/rebuild-all` operation once with an administrator token
   and allow a long request timeout.
7. Publish the frontend assets.
8. Run an authenticated upload, processing, research, and reference-reopen smoke test.

For the research portion of that smoke test, verify that Java sends `user_id` and a locked paper
scope, Python calls the Java Corpus API, Qdrant returns only current scoped locations, and returned
citations reopen exact MySQL content.

## Release Verification

At minimum, verify:

- login and token refresh;
- private and public paper authorization;
- PDF upload and processing-state transitions;
- parser artifact and visual asset persistence;
- paper search and scoped paper QA;
- streamed answer completion and cancellation;
- historical conversation reload;
- reference detail reopening;
- restart behavior for backend and harness processes.

## Backups

Back up MySQL and MinIO together because metadata and objects form one logical paper record. Preserve
Qdrant snapshots for recovery speed, but treat the collection as rebuildable from Current Reading
Models. Redis and Kafka should not be the only location of user-visible durable state.
