# Product Launch Runtime Preflight Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the smallest preflight gate that proves whether the active local runtime can even run the 30-PDF seed, live reading smoke, trace eval, and parser smoke gates.

**Architecture:** A test-scoped CLI reads `.env`, checks launch-critical endpoints and secrets without starting services, and writes standard RAG eval artifacts. It must fail explicitly when backend, DB, MinerU, LLM keys, embedding keys, or trace config are not ready, so the next gate failure is not discovered halfway through a 30-PDF import.

**Tech Stack:** Java 21 test-scope eval harness, JDK `HttpClient`, raw TCP socket probes, existing `RagEvalRunWriter`.

## Global Constraints

- Do not start Docker, backend, MinerU, frontend, or database services automatically.
- Do not fake successful runtime checks.
- Do not replace the 30-PDF seed, Product Reading live smoke, trace eval, or parser smoke gates.
- Use `.env` as the default local launch configuration source, with CLI overrides for env path, API base, credentials, and timeout.
- Keep Product Reading tool surface exactly 9 tools.

---

## Grill Decisions

1. Should the next loop try to run the whole launch again?
   - Recommended answer: no. The current failures are runtime availability/config failures. Running expensive gates again without a preflight wastes time and produces noisy evidence.

2. What is the smallest useful loop?
   - Recommended answer: a runtime preflight eval gate that checks backend login, MySQL TCP, Redis TCP, Kafka TCP, MinIO health, Elasticsearch health, MinerU health, nonblank LLM key, nonblank embedding key, and trace enabled/root config.

3. Should the preflight start or mutate local services?
   - Recommended answer: no. It should only observe and report. Service startup is an operator action; this gate tells us exactly what is missing.

4. Should the preflight use direct DB auth?
   - Recommended answer: not in this minimal loop. TCP reachability plus the later parser smoke’s Spring/JPA startup is enough to separate config reachability from schema/content correctness.

5. What counts as launch-ready for this loop?
   - Recommended answer: preflight passes, then the 30-PDF data seed passes, then Product Reading live smoke passes, then trace eval passes 9/9, then the 30-PDF parser smoke passes.

6. What if only infrastructure containers are up but backend/MinerU/keys are missing?
   - Recommended answer: fail the preflight with explicit `RUNTIME_UNAVAILABLE` or `CONFIG_MISSING` classes. The product is not launch-ready.

## File Structure

- Create `src/test/java/com/yizhaoqi/smartpai/eval/ProductLaunchRuntimePreflightRunner.java`
  - Loads `.env`, derives probe cases, runs them through an injected probe, writes eval artifacts.
- Create `src/test/java/com/yizhaoqi/smartpai/eval/ProductLaunchRuntimePreflightProbe.java`
  - Implements TCP, HTTP, login, nonblank config, and trace config probes.
- Create `src/test/java/com/yizhaoqi/smartpai/eval/ProductLaunchRuntimePreflightCli.java`
  - Parses CLI options and runs the preflight.
- Create `src/test/java/com/yizhaoqi/smartpai/eval/ProductLaunchRuntimePreflightRunnerTest.java`
  - Covers env parsing, case generation, pass/fail artifact writing, and missing secrets.
- Create `src/test/java/com/yizhaoqi/smartpai/eval/ProductLaunchRuntimePreflightProbeTest.java`
  - Covers HTTP health, login success/failure, and TCP reachability.
- Modify `eval/rag/harnesses.yaml`
  - Register `product-launch-runtime-preflight`.
- Modify `eval/rag/README.md`
  - Document the preflight command and launch gate order.
- Modify `src/test/java/com/yizhaoqi/smartpai/eval/RagBenchmarkRegistryTest.java`
  - Assert registry coverage.

## Tasks

### Task 1: Runner Contract

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/ProductLaunchRuntimePreflightRunnerTest.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/ProductLaunchRuntimePreflightRunner.java`

**Interfaces:**
- Produces: `ProductLaunchRuntimePreflightRunner.run(Options): Path`
- Produces: nested `RuntimeProbe`, `ProbeRequest`, `ProbeResult`, `Options`

- [ ] **Step 1: Write failing tests**

Add tests that write a temporary `.env`, use a fake probe, and assert:
- all required case ids are emitted;
- missing `DEEPSEEK_API_KEY` and `EMBEDDING_API_KEY` produce failed cases;
- failed probes write failure classes and diagnostics into `run.json`;
- a fully passing fake probe writes a 100% scorecard.

- [ ] **Step 2: Verify RED**

Run:

```bash
mvn -q -Dtest=ProductLaunchRuntimePreflightRunnerTest test
```

Expected: compilation fails because the runner does not exist.

- [ ] **Step 3: Implement minimal runner**

Implement `.env` parsing, request generation, probe invocation, and eval artifact writing.

- [ ] **Step 4: Verify GREEN**

Run:

```bash
mvn -q -Dtest=ProductLaunchRuntimePreflightRunnerTest test
```

Expected: tests pass.

### Task 2: Probe and CLI

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/ProductLaunchRuntimePreflightProbeTest.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/ProductLaunchRuntimePreflightProbe.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/ProductLaunchRuntimePreflightCli.java`

**Interfaces:**
- Consumes: `ProductLaunchRuntimePreflightRunner.RuntimeProbe`
- Produces: runnable `ProductLaunchRuntimePreflightCli`

- [ ] **Step 1: Write failing tests**

Use JDK `HttpServer` and `ServerSocket` to verify:
- HTTP health passes on accepted status;
- login passes only when the response has `data.token`;
- TCP probe passes when a socket accepts connections and fails otherwise.

- [ ] **Step 2: Verify RED**

Run:

```bash
mvn -q -Dtest=ProductLaunchRuntimePreflightProbeTest test
```

Expected: compilation fails because the probe does not exist.

- [ ] **Step 3: Implement minimal probe and CLI**

Implement TCP socket check, HTTP GET status check, login POST check, and CLI options.

- [ ] **Step 4: Verify GREEN**

Run:

```bash
mvn -q -Dtest=ProductLaunchRuntimePreflightRunnerTest,ProductLaunchRuntimePreflightProbeTest test
```

Expected: tests pass.

### Task 3: Registry and Docs

**Files:**
- Modify: `eval/rag/harnesses.yaml`
- Modify: `eval/rag/README.md`
- Modify: `src/test/java/com/yizhaoqi/smartpai/eval/RagBenchmarkRegistryTest.java`

**Interfaces:**
- Consumes: new CLI/harness names from Tasks 1-2.
- Produces: documented preflight-first launch sequence.

- [ ] **Step 1: Write failing registry assertion**

Add assertions for `product-launch-runtime-preflight`.

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

- [x] **Step 1: Run focused verification**

```bash
mvn -q -Dtest=ProductLaunchRuntimePreflightRunnerTest,ProductLaunchRuntimePreflightProbeTest,RagBenchmarkRegistryTest test
git diff --check
```

- [x] **Step 2: Run current local preflight**

```bash
mvn -q -DskipTests test-compile exec:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=com.yizhaoqi.smartpai.eval.ProductLaunchRuntimePreflightCli \
  -Dexec.args="--run-id 2026-07-07-product-launch-runtime-preflight-local-audit --timeout-seconds 5"
```

Expected in the current observed environment: failed scorecard with backend unavailable, MySQL configured port unavailable, MinerU unavailable, and missing model/embedding keys.

## Self-Review

- Spec coverage: the plan covers runtime dependency detection, actionable eval artifacts, registry/docs, and launch gate ordering.
- Placeholder scan: no deferred placeholders.
- Type consistency: runner/probe/CLI names match across tasks.
