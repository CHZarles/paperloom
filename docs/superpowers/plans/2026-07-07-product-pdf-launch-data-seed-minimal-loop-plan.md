# Product PDF Launch Data Seed Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the smallest repeatable loop that builds the 30-PDF launch dataset through the same upload/merge/status boundary the frontend uses.

**Architecture:** A test-scoped launch seed CLI reads the existing 30-PDF manifest, computes the frontend-compatible MD5 paper id, uploads each PDF through `/api/v1/papers/upload/chunk`, calls `/api/v1/papers/upload/merge`, then polls `/api/v1/papers/uploads` until each paper is frontend-searchable. It writes normal RAG eval artifacts so the data-build step is inspectable before the live reading smoke and trace eval run.

**Tech Stack:** Java 21 test-scope eval harness, JDK `HttpClient`, existing `RagEvalRunWriter`, existing `ProductPdfParserSmokeRunner.ManifestCase`, PaperLoom HTTP API.

## Global Constraints

- Do not add Product Reading LLM-facing tools; the surface remains exactly 9 tools.
- Do not fake successful traces, successful uploads, parser artifacts, or searchable frontend status.
- Do not bypass `paperloom.react.reading-phase1.enabled`.
- Do not replace the existing live smoke, trace eval, or parser smoke gates.
- Use the active PaperLoom backend HTTP API for launch data seeding evidence.
- Keep `eval/rag/pdf-parser/product-pdf-launch-30-manifest.jsonl` as the 30-PDF data contract.

---

## Grill Decisions

1. Should the next loop add more Product Reading tools?
   - Recommended answer: no. The blocker is not tool count; it is that the launch dataset is not being built through the product boundary.

2. What is the smallest useful loop after the live smoke gate exists?
   - Recommended answer: a 30-PDF launch data seeder that uploads/merges the manifest PDFs through the real HTTP API and proves the frontend paper list sees them as searchable.

3. Should this seeder write parser artifacts or database rows directly?
   - Recommended answer: no. Direct DB/import shortcuts would not prove the frontend-to-backend upload path.

4. Should the seeder run the live reading smoke and trace eval itself?
   - Recommended answer: no. Keep data seeding as one gate, then run the already-existing live smoke, trace eval, and parser smoke gates.

5. What counts as seeded?
   - Recommended answer: every manifest PDF has been chunk-uploaded, merged, and appears in `/papers/uploads` with upload completed, `processingStatus=COMPLETED`, actual embedding usage present, and `actualChunkCount > 0`.

6. What if runtime dependencies are unavailable?
   - Recommended answer: fail with explicit eval artifacts and diagnostics. Do not mark launch-ready; return to the next smallest loop only if the failure is a product gap rather than missing local runtime.

## File Structure

- Create `src/test/java/com/yizhaoqi/smartpai/eval/ProductPdfLaunchDataSeedRunner.java`
  - Owns manifest loading, MD5 computation, chunk slicing, merge sequencing, status polling, pass/fail evaluation, and eval artifact writing.
- Create `src/test/java/com/yizhaoqi/smartpai/eval/ProductPdfLaunchDataSeedHttpClient.java`
  - Owns authenticated HTTP calls to upload chunk, merge, and list uploaded papers.
- Create `src/test/java/com/yizhaoqi/smartpai/eval/ProductPdfLaunchDataSeedCli.java`
  - Owns login, option parsing, client construction, and CLI execution.
- Create `src/test/java/com/yizhaoqi/smartpai/eval/ProductPdfLaunchDataSeedRunnerTest.java`
  - Covers MD5/chunk upload sequencing, merge, polling, missing file failures, and frontend-searchable validation.
- Create `src/test/java/com/yizhaoqi/smartpai/eval/ProductPdfLaunchDataSeedHttpClientTest.java`
  - Covers multipart upload request shape, merge JSON shape, auth headers, and paper-list parsing.
- Modify `eval/rag/harnesses.yaml`
  - Register the `product-pdf-launch-data-seed` harness/benchmark.
- Modify `eval/rag/README.md`
  - Document the launch gate order and data seed command.
- Modify `src/test/java/com/yizhaoqi/smartpai/eval/RagBenchmarkRegistryTest.java`
  - Assert the registry contains the new seed harness and benchmark.

## Tasks

### Task 1: Runner Contract

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/ProductPdfLaunchDataSeedRunnerTest.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/ProductPdfLaunchDataSeedRunner.java`

**Interfaces:**
- Produces: `ProductPdfLaunchDataSeedRunner.run(Options): Path`
- Produces: nested `LaunchDataSeedClient`, `UploadChunkRequest`, `MergeRequest`, `PaperStatus`, `Options`

- [ ] **Step 1: Write failing tests**

Add tests that create a tiny local PDF, run the runner with a fake client, and assert:
- MD5 paper id matches the uploaded content.
- Chunks are uploaded in order using the configured chunk size.
- Merge is called after upload.
- A case passes only when `/papers/uploads` status is frontend-searchable.
- Missing files fail without calling the client.

- [ ] **Step 2: Verify RED**

Run:

```bash
mvn -q -Dtest=ProductPdfLaunchDataSeedRunnerTest test
```

Expected: compilation fails because `ProductPdfLaunchDataSeedRunner` does not exist.

- [ ] **Step 3: Implement minimal runner**

Implement manifest loading through `ProductPdfParserSmokeRunner.loadManifest`, MD5 hashing, chunk slicing, merge calls, status polling, failure classes, and `RagEvalRunWriter` output.

- [ ] **Step 4: Verify GREEN**

Run:

```bash
mvn -q -Dtest=ProductPdfLaunchDataSeedRunnerTest test
```

Expected: tests pass.

### Task 2: HTTP Client and CLI

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/ProductPdfLaunchDataSeedHttpClientTest.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/ProductPdfLaunchDataSeedHttpClient.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/ProductPdfLaunchDataSeedCli.java`

**Interfaces:**
- Consumes: `ProductPdfLaunchDataSeedRunner.LaunchDataSeedClient`
- Produces: `ProductPdfLaunchDataSeedCli` runnable with `exec:java`

- [ ] **Step 1: Write failing tests**

Use JDK `HttpServer` to verify:
- `/papers/upload/chunk` receives multipart form data with `paperId`, `chunkIndex`, `totalSize`, `paperTitle`, `totalChunks`, `isPublic`, and file bytes.
- `/papers/upload/merge` receives JSON `{paperId, paperTitle}`.
- `/papers/uploads` parses upload/searchability fields into `PaperStatus`.

- [ ] **Step 2: Verify RED**

Run:

```bash
mvn -q -Dtest=ProductPdfLaunchDataSeedHttpClientTest test
```

Expected: compilation fails because the HTTP client does not exist.

- [ ] **Step 3: Implement minimal client and CLI**

Implement login in the CLI through `/users/login`, authenticated upload/merge/list calls in the client, and options for API base, manifest, run id, chunk size, poll attempts, poll interval, username, password, and timeout.

- [ ] **Step 4: Verify GREEN**

Run:

```bash
mvn -q -Dtest=ProductPdfLaunchDataSeedHttpClientTest,ProductPdfLaunchDataSeedRunnerTest test
```

Expected: tests pass.

### Task 3: Registry and Docs

**Files:**
- Modify: `eval/rag/harnesses.yaml`
- Modify: `eval/rag/README.md`
- Modify: `src/test/java/com/yizhaoqi/smartpai/eval/RagBenchmarkRegistryTest.java`

**Interfaces:**
- Consumes: new CLI and runner names from Tasks 1-2.
- Produces: documented launch gate sequence.

- [ ] **Step 1: Write failing registry assertion**

Add assertions for `product-pdf-launch-data-seed` in `RagBenchmarkRegistryTest`.

- [ ] **Step 2: Verify RED**

Run:

```bash
mvn -q -Dtest=RagBenchmarkRegistryTest test
```

Expected: fails because the registry entry is missing.

- [ ] **Step 3: Add registry/docs**

Add the harness/benchmark YAML entries and README command.

- [ ] **Step 4: Verify GREEN**

Run:

```bash
mvn -q -Dtest=RagBenchmarkRegistryTest test
```

Expected: test passes.

### Task 4: Launch Probe

**Files:**
- No code files required unless a test reveals a bug.

- [ ] **Step 1: Run focused verification**

```bash
mvn -q -Dtest=ProductPdfLaunchDataSeedRunnerTest,ProductPdfLaunchDataSeedHttpClientTest,RagBenchmarkRegistryTest test
git diff --check
```

- [ ] **Step 2: Probe the local launch runtime**

```bash
mvn -q -DskipTests test-compile exec:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=com.yizhaoqi.smartpai.eval.ProductPdfLaunchDataSeedCli \
  -Dexec.args="--run-id 2026-07-07-product-pdf-launch-data-seed-local-audit --poll-attempts 1 --timeout-seconds 10"
```

Expected when runtime is unavailable: explicit connection/runtime failure. Expected when runtime is available: a run directory whose scorecard reports 30/30 seeded.

- [ ] **Step 3: If seeded, run downstream gates**

```bash
mvn -q -DskipTests test-compile exec:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=com.yizhaoqi.smartpai.eval.ProductReadingLiveLaunchSmokeCli

mvn -q -DskipTests test-compile exec:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=com.yizhaoqi.smartpai.eval.ProductReadingLaunchTraceEvalCli

mvn -q -DskipTests test-compile exec:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=com.yizhaoqi.smartpai.eval.ProductPdfParserSmokeCli \
  -Dexec.args="--manifest eval/rag/pdf-parser/product-pdf-launch-30-manifest.jsonl"
```

Expected: all gates pass before launch-ready can be claimed.

## Self-Review

- Spec coverage: the plan covers launch data build, frontend status proof, eval artifacts, and runtime failure diagnostics.
- Placeholder scan: no `TBD`, `TODO`, or deferred implementation steps.
- Type consistency: runner/client/CLI names match across tasks and docs.
