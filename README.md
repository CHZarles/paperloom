# PaperLoom

PaperLoom is an evidence-grounded RAG workbench for reading research paper PDFs.

It helps users upload papers, process them asynchronously, retrieve page-aware chunks, ask questions over indexed papers, and reopen the exact evidence references used in previous answers.

## Current Positioning

PaperLoom is not a generic enterprise knowledge-base assistant. The product subject is the research paper:

- PDF paper upload
- asynchronous parsing and indexing
- page-aware chunk storage
- Elasticsearch hybrid retrieval
- source-grounded chat answers
- persistent reference mappings in MySQL
- paper library with basic editable metadata
- organization/public/private access control

The current system supports a credible MVP story:

> PaperLoom supports PDF paper upload, asynchronous parsing/indexing, page-aware chunk retrieval, source-grounded chat, and persistent evidence references.

## What It Does Not Claim Yet

The current codebase does not claim automatic research understanding features such as:

- automatic title/author/venue/DOI extraction from arbitrary PDFs
- claim/method/experiment/limitation extraction
- citation graph construction
- coordinate-level PDF highlighting
- fully structured multi-paper comparison

Those can be added later, but they are not part of the current MVP contract.

## Architecture

- Backend: Spring Boot
- Frontend: Vue 3 + TypeScript
- Persistence: MySQL
- Cache/session/transient generation state: Redis
- Retrieval: Elasticsearch
- Async processing: Kafka
- Object storage: MinIO
- PDF parsing/chunking: Apache Tika + PDFBox

## Core Workflow

1. A user uploads a PDF paper through `/api/v1/papers/upload/*`.
2. PaperLoom stores upload metadata in MySQL and the PDF in MinIO.
3. Kafka triggers asynchronous paper processing.
4. The backend parses the PDF, stores page-aware chunks, creates embeddings, and indexes chunks into Elasticsearch.
5. Chat retrieval uses the indexed paper chunks to construct source-grounded answers.
6. Assistant messages persist their reference mappings in MySQL, so historical citations can be reopened without relying on Redis.

## API Shape

The public PaperLoom API uses paper terminology:

- `paperId`: stable paper identifier, currently the PDF content hash
- `paperTitle`: display title, defaulting to the uploaded filename unless edited
- `originalFilename`: uploaded PDF filename
- `isPublic`: public visibility flag
- `uploadStatus`: upload/merge state
- `processingStatus`: parse/vectorization/indexing state

Common endpoints:

- `GET /api/v1/papers?scope=accessible`
- `POST /api/v1/papers/upload/chunk`
- `POST /api/v1/papers/upload/merge`
- `GET /api/v1/papers/upload/status?paperId=...`
- `GET /api/v1/papers/{paperId}/preview`
- `GET /api/v1/papers/{paperId}/page-preview?pageNumber=...`
- `GET /api/v1/papers/{paperId}/download`
- `DELETE /api/v1/papers/{paperId}`
- `POST /api/v1/papers/{paperId}/reindex`
- `POST /api/v1/papers/{paperId}/vectorization/retry`
- `PATCH /api/v1/papers/{paperId}/metadata`
- `GET /api/v1/papers/search/hybrid?query=...&topK=...`
- `GET /api/v1/papers/reference-detail?conversationRecordId=...&referenceNumber=...`

## Local Development

Backend is usually run from the IDE with hot deployment. After Java changes, prefer compiling instead of restarting:

```bash
mvn -q -DskipTests compile
```

Run targeted backend tests:

```bash
mvn -q -Dtest=ConversationServiceTest,PaperControllerContractTest test
```

Frontend typecheck:

```bash
cd frontend && pnpm typecheck
```

The local frontend development target is usually:

```text
http://localhost:9527
```

## Persistence Rule

Redis is used for cache, current session state, and short-lived streaming generation state. Persistent chat history and evidence references must live in MySQL.

For source evidence, the durable path is:

```text
Conversation.referenceMappingsJson -> /api/v1/papers/reference-detail -> PDF/page/chunk preview
```
