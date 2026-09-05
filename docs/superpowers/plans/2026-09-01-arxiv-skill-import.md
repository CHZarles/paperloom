# arXiv Discovery Skill And Confirmed Import — Implementation Plan

**Date:** 2026-09-01

**Status:** Proposed

**Goal:** Let the PaperLoom Research Agent discover papers through an arXiv Skill and, only after
explicit user confirmation, enqueue one exact paper version into the existing PaperLoom ingestion
pipeline. Imported papers become searchable in a later Agent Run after parsing and indexing finish.

**Architecture:** Skill-first, Java-owned state. The Python Harness exposes model-facing arXiv
discovery/import tools, but Java remains the authority for identity, authorization, quota, download,
MinIO, MySQL, and Kafka. The existing PDF parser and Qdrant indexing consumer remain unchanged.

**Tech Stack:** OpenAI Agents SDK, Python/httpx, Java 17/Spring Boot/WebClient, MySQL, MinIO,
Kafka, Qdrant, Vue 3. No new queue, parser, vector store, or generic Skill framework.

---

## 1. Current State

The repository already contains:

- a development-agent arXiv Skill with search, abstract, and PDF download commands at
  `.agents/skills/arxiv/`;
- a product Agent runtime whose model-visible tools are explicitly registered in
  `harness_py/orchestration/agents/tools.py`;
- a Java-owned internal Corpus API used by the Python Harness;
- `Paper` metadata fields for title, authors, year, abstract, DOI, and arXiv ID;
- MinIO merged-PDF storage, Kafka `PaperProcessingTask`, PDF parsing, and Qdrant indexing;
- Kafka producer idempotence, retry, and dead-letter handling.

The development Skill is not loaded by the product Research Agent. Product integration is therefore
not complete until model-facing tools and the Java import boundary are implemented.

---

## 2. Product Contract

### In Scope

- user asks the Research Agent to find external papers;
- Agent calls `search_arxiv` with a bounded query;
- search results contain exact versioned arXiv IDs and metadata, but no citeable evidence;
- Agent presents candidates and requests user confirmation;
- after confirmation, Agent calls `request_arxiv_import` for one permitted exact version;
- Java downloads, validates, persists, and queues the PDF for existing asynchronous processing;
- the imported paper becomes available to later Agent Runs after indexing reaches READY.

### Out Of Scope

- automatic import without explicit confirmation;
- scheduled topic subscriptions or background crawling;
- importing arbitrary model-provided URLs;
- using arXiv abstracts as paper-content evidence;
- reading a newly imported paper in the same Agent Run;
- citation graphs, recommendation feeds, or automatic version upgrades;
- general non-PDF enterprise-document ingestion.

---

## 3. Decisions

### D1. Treat arXiv As A Product Skill, Not A Raw CLI

Add one `ResearchSkill` entry named `arxiv_discovery_import` for behavioral guidance and a separate
typed tool group for execution. Do not invoke `arxiv_cli.py` through `subprocess` from the Harness.
The existing CLI remains a developer verification helper.

Model-facing tools:

```text
search_arxiv
request_arxiv_import
```

`search_arxiv` is read-only. `request_arxiv_import` is a controlled side effect and is exposed only
when the current turn contains a server-issued import permission for the selected arXiv ID.

### D2. Require Explicit User Confirmation

The first turn may search and return candidate cards. It must not import.

When the user clicks Import or sends an explicit follow-up instruction, Java starts a new turn with:

```text
allowed_arxiv_import_ids = ["2401.01234v2"]
```

The model never supplies or changes this allow-list. The import tool rejects IDs outside it.

### D3. Java Owns External Import State

Python must not write MySQL, MinIO, Kafka, or Qdrant. The import tool calls a Java internal endpoint
using the Harness's bound request, conversation, and user context. Java validates that the
conversation belongs to the user and that the selected ID is present in the server-issued allow-list.

The model-visible arguments contain only:

```json
{"arxiv_id":"2401.01234v2"}
```

They do not contain `user_id`, destination URLs, object keys, or processing status.

### D4. Pin Exact arXiv Versions

Search results and imports use full versioned IDs. A request for an unversioned ID is resolved to the
current exact version before confirmation. A later version is a different import candidate and does
not overwrite the earlier version.

Use `(user_id, arxiv_id)` as the product idempotency key. Use the downloaded PDF MD5 as the existing
canonical `paperId` and MinIO object identity.

### D5. Keep Import Asynchronous

Import success means the PDF was accepted and processing was queued. It does not mean the paper is
searchable. Return one of:

```text
already_exists
queued
processing
ready
failed
```

The current Agent Run reports the state and stops treating the new paper as research evidence. A
later turn rebuilds source scope after the paper becomes READY.

### D6. Reuse The Existing Processing Pipeline

The import service writes the PDF to `merged/{paperId}`, creates the user's `Paper` row with arXiv
metadata, and publishes the existing `PaperProcessingTask`. Do not create an arXiv-specific parser,
indexer, Kafka consumer, or vector collection.

---

## 4. Runtime Flow

```text
User asks for external papers
  -> Research Agent loads arxiv_discovery_import guidance
  -> search_arxiv Tool
  -> Java arXiv search adapter
  -> Agent returns candidate cards (metadata only)

User confirms exact candidate
  -> Java starts confirmation turn with allowed_arxiv_import_ids
  -> request_arxiv_import Tool
  -> Java validates user + conversation + allowed ID
  -> Java downloads exact PDF version to bounded temp storage
  -> validate host, redirects, size, content type, and %PDF- header
  -> compute MD5
  -> reuse existing searchable artifact or write merged/{paperId} to MinIO
  -> persist Paper metadata and processing state
  -> publish existing PaperProcessingTask
  -> existing Consumer parses and indexes
  -> later Agent Run can add the READY paper to source scope
```

---

## 5. File Changes

### Python Harness

**Modify:** `harness_py/orchestration/research_skills.py`

- add `arxiv_discovery_import` guidance;
- state that metadata is discovery-only and imports require explicit confirmation.

**Add:** `harness_py/orchestration/arxiv_tools.py`

- define `search_arxiv` and `request_arxiv_import` schemas;
- validate bounded arguments;
- delegate execution to the bound Java gateway;
- redact internal fields before returning model-visible payloads.

**Modify:** `harness_py/orchestration/agents/context.py`

- attach the arXiv tool group to each production run;
- carry immutable `allowed_arxiv_import_ids` from server-owned turn input.

**Modify:** `harness_py/orchestration/agents/tools.py`

- add arXiv tool definitions to `build_agent_tools`;
- trace calls using the existing Tool trace path;
- keep import results non-evidentiary.

**Modify:** `harness_py/orchestration/research_contract.py`

- permit `search_arxiv` during discovery/catalog work;
- permit `request_arxiv_import` only when a confirmation allow-list exists;
- never allow either tool during final-submission repair.

**Modify:** `harness_py/corpus/gateway.py`

- add typed `search_arxiv` and `request_arxiv_import` gateway methods;
- append bound request/conversation/user context outside model arguments.

### Java Backend

**Add:** `src/main/java/io/github/chzarles/paperloom/service/ArxivClient.java`

- query the arXiv Atom API with bounded limits and timeouts;
- parse metadata with the JDK XML parser;
- normalize full versioned IDs;
- download only allow-listed arXiv HTTPS PDF URLs;
- stream into a temporary file while enforcing a maximum size.

**Add:** `src/main/java/io/github/chzarles/paperloom/service/ArxivImportService.java`

- validate the context-bound user and selected version;
- enforce `(user_id, arxiv_id)` idempotency;
- compute PDF MD5 and reuse existing canonical searchable artifacts when possible;
- store new PDFs in MinIO and persist metadata;
- publish the existing `PaperProcessingTask`;
- record stable failure states and clean temporary files.

**Add:** `src/main/java/io/github/chzarles/paperloom/controller/InternalArxivController.java`

- `POST /internal/v1/arxiv/search`;
- `POST /internal/v1/arxiv/import`;
- require the existing internal Harness token;
- validate conversation/user binding and import permission;
- never accept arbitrary source URLs or model-provided user IDs.

**Modify:** `src/main/java/io/github/chzarles/paperloom/repository/PaperRepository.java`

- add per-user exact-version lookup.

**Add:** `docs/databases/migrations/2026-09-01-add-arxiv-import-idempotency.sql`

- normalize existing non-null arXiv IDs if needed;
- add an index or unique constraint for `(user_id, arxiv_id)` after duplicate audit.

### Product Confirmation UI

**Modify:** the chat result rendering path that displays structured catalog candidates.

- render arXiv candidate title, authors, year, abstract preview, and exact version;
- provide an Import action;
- start a confirmation turn with only the clicked exact arXiv ID allow-listed;
- display queued, processing, ready, already-exists, and failed states.

Do not add a second full arXiv search application to the knowledge-base page in the first version.

---

## 6. Delivery Tasks

### Task 1. Add The Read-Only Search Skill

- [ ] Add the `arxiv_discovery_import` ResearchSkill guidance.
- [ ] Add `search_arxiv` schema and Java gateway method.
- [ ] Implement bounded Java Atom search and response mapping.
- [ ] Mark search results as non-evidentiary metadata.
- [ ] Add protocol and trace tests.

**Gate:** the Agent can search arXiv, cannot cite search metadata as paper evidence, and cannot import.

### Task 2. Add Confirmation-Bound Import

- [ ] Add immutable `allowed_arxiv_import_ids` to turn context.
- [ ] Expose `request_arxiv_import` only when the allow-list is non-empty.
- [ ] Reject IDs outside the server-issued allow-list.
- [ ] Add candidate Import UI and confirmation-turn wiring.

**Gate:** a normal research turn cannot import; only the exact user-confirmed candidate can be
requested in the confirmation turn.

### Task 3. Implement Safe PDF Acquisition

- [ ] Resolve the exact version and metadata again in Java.
- [ ] Disable arbitrary redirects and allow only approved arXiv hosts.
- [ ] Stream to temp storage with timeout and size bounds.
- [ ] Validate content type and PDF magic bytes.
- [ ] Compute MD5 and always clean temporary files.

**Gate:** invalid IDs, oversized files, non-PDF responses, redirect abuse, timeout, and disconnects
fail without creating a searchable Paper row.

### Task 4. Reuse Existing Ingestion

- [ ] Store or reuse `merged/{paperId}`.
- [ ] Persist arXiv metadata and initial processing state.
- [ ] Publish the existing `PaperProcessingTask`.
- [ ] Return stable asynchronous import states.
- [ ] Reuse existing processing retry behavior.

**Gate:** an imported paper follows the same parser/indexer path as an uploaded PDF and becomes
searchable without arXiv-specific processing code.

### Task 5. Add Idempotency And Recovery

- [ ] Add exact-version per-user lookup and database constraint.
- [ ] Return the existing state for repeated imports.
- [ ] Make concurrent imports produce one user Paper row and one processing task.
- [ ] Mark persistence or Kafka publication failures as retryable FAILED state.
- [ ] Avoid deleting a shared MinIO object referenced by another Paper row.

**Gate:** repeated and concurrent Tool calls do not create duplicate user assets or duplicate
processing work.

### Task 6. Verify End To End

- [ ] Unit-test Atom parsing, ID normalization, bounds, and host allow-listing.
- [ ] Unit-test Tool availability and confirmation allow-list enforcement.
- [ ] Test duplicate, concurrent, and cross-user imports.
- [ ] Test download failure, invalid PDF, Kafka failure, retry, and DLT behavior.
- [ ] Run existing Java, Harness, and frontend checks.
- [ ] Perform one live smoke flow with a small public arXiv paper.

**Gate:** `Agent search -> user confirmation -> queued import -> existing processing -> READY -> next
Agent Run can retrieve and cite the paper` completes without bypassing source scope.

---

## 7. Verification Commands

```bash
mvn -q -DskipTests compile
mvn test
.venv-harness/bin/python -m unittest discover -s harness_py/tests
cd frontend && pnpm typecheck && pnpm lint && pnpm test:e2e
```

Live verification additionally requires Java, Redis, Kafka, MinIO, Qdrant, the Harness worker, and
an outbound network path to arXiv.

---

## 8. Resume Claim Gate

Do not claim an end-to-end arXiv ingestion Skill until Tasks 1–6 pass. After verification, the
defensible resume statement is:

> Added an arXiv discovery Skill to the Research Agent and connected confirmed paper imports to the
> existing MinIO, Kafka, parsing, and indexing pipeline, turning external paper discovery into
> searchable knowledge assets without bypassing user scope.
