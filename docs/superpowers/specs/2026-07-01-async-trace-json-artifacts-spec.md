# Async Trace JSON Artifacts Spec

Date: 2026-07-01

Status: user-confirmed design target.

## Goal

PaperLoom Product ReAct trace should become a business-decoupled JSON artifact stream.

The product runtime only records what happened. Eval is an offline consumer of those JSON files.
The business system must not score, interpret, expose, or persist eval state.

Short form:

```text
trace is eval input
business produces trace JSON
offline eval consumes trace JSON
```

## Non-Goals

Do not build an online eval system in this phase.

Do not add:

- eval scoring
- eval database tables
- trace ids or trace paths in business tables
- trace status in chat/conversation records
- frontend trace display
- API endpoints for trace retrieval
- Kafka trace topics
- retry or durable trace queues
- schema-heavy eval sample abstractions
- redaction, retention, compression, or cleanup automation

Do not let trace write success or failure affect business behavior.

## Core Rules

Trace is best-effort observability.

Rules:

- trace writes are asynchronous
- trace writes are JSON files on disk
- trace writes are append-only artifacts
- trace write failure only logs
- trace queue full drops the new artifact and logs
- trace does not change `ProductTurnResult`
- trace does not change `stopReason`
- trace does not change `resultStatus`
- trace failure must never become `ProductStopReason.TRACE_WRITE_FAILED`
- trace failure must never turn a completed product answer into `DEGRADED`
- trace does not affect final answer persistence
- trace does not affect reference persistence
- business tables do not know trace exists

## Storage Layout

Trace root is configurable and defaults to:

```text
data/traces/product-react
```

Turn trace file:

```text
data/traces/product-react/conversation-{conversationId}/turn-{generationId}.json
```

Memory compression trace file:

```text
data/traces/product-react/conversation-{conversationId}/turn-{generationId}.memory-{timestamp}.json
```

Rationale:

- one Product ReAct turn is easy to inspect manually
- one memory call is a separate artifact, avoiding async read-modify-write races
- offline eval can group files by `generationId`
- no per-key locking is needed

All files should be written via temp file and atomic move:

```text
*.tmp -> final .json
```

## Artifact Types

Every trace JSON must include `artifactType`.

Supported first-phase types:

```text
PRODUCT_REACT_TURN
PRODUCT_MEMORY_COMPRESSION
```

Turn artifact shape is weakly structured JSON:

```json
{
  "artifactType": "PRODUCT_REACT_TURN",
  "traceVersion": 1,
  "conversationId": "...",
  "generationId": "...",
  "userId": 1,
  "scopeSnapshot": {},
  "startedAt": "...",
  "finishedAt": "...",
  "input": {},
  "llmCalls": [],
  "toolCalls": [],
  "retrievals": [],
  "diagnostics": {},
  "answerEnvelope": {},
  "references": [],
  "stopReason": "COMPLETED",
  "resultStatus": "COMPLETED",
  "errors": []
}
```

Memory artifact shape:

```json
{
  "artifactType": "PRODUCT_MEMORY_COMPRESSION",
  "traceVersion": 1,
  "conversationId": "...",
  "generationId": "...",
  "createdAt": "...",
  "memoryCompressionCall": {},
  "diagnostics": {}
}
```

The outer fields needed for routing and file names are stable. The inner trace content remains
`Map<String, Object>` because trace/eval is still exploratory.

## Data Captured

First phase records complete raw debug context.

Turn trace should include:

- user message
- history summary/count and memory input
- locked scope snapshot
- prompt messages
- full product tool catalog
- raw LLM response
- tool call arguments
- tool results
- harness validation/rejection events
- answer envelope
- rendered reference snapshot
- stop reason
- result status
- timestamps
- diagnostics

Memory compression trace should include:

- memory prompt messages
- raw LLM memory response
- parsed/sanitized memory
- memory update success or error
- token metadata where available
- diagnostics

No first-phase redaction or truncation.

Boundary:

- local/development disk only
- not returned to frontend
- not exposed through API
- not written to business tables

Productionization later requires retention, disk quota, compression, access control, and sensitive
data handling.

## Async Sink Design

Use a thin sink boundary.

Recommended types:

```java
public record ProductTracePayload(
        String conversationId,
        String generationId,
        String artifactType,
        String filename,
        Map<String, Object> traceJson
) {}
```

```java
public interface ProductTraceSink {
    void submit(ProductTracePayload payload);
}
```

First implementation:

```text
AsyncDiskProductTraceSink
```

Responsibilities:

- bounded queue
- dedicated writer executor
- JSON serialization
- directory creation
- temp file write
- atomic move
- queue full drop with warn log
- write failure log
- graceful shutdown best effort

`ProductTraceRecorder` remains responsible for building trace JSON payloads. The sink only writes.

## Configuration

Add simple properties:

```yaml
paperloom:
  trace:
    enabled: true
    root: data/traces/product-react
    queue-capacity: 1000
    writer-threads: 1
```

Defaults:

```text
enabled=true
root=data/traces/product-react
queue-capacity=1000
writer-threads=1
```

Queue full behavior:

```text
drop new artifact
warn trace_dropped_queue_full with conversationId, generationId, artifactType, queueSize
```

Write failure behavior:

```text
log trace_write_failed with conversationId, generationId, artifactType, targetPath
do not change ProductTurnResult, stopReason, resultStatus, final answer, or reference persistence
```

No summary file is written for failed or dropped traces in phase one.

## Required Code Changes

Expected implementation scope:

- add `ProductTracePayload`
- add `ProductTraceSink`
- add `AsyncDiskProductTraceSink`
- add trace properties/config
- change `ProductTraceRecorder.record(...)` to submit async turn artifact
- change `ProductTraceRecorder.recordMemoryUpdate(...)` to submit standalone memory artifact
- remove old read-modify-write memory append behavior
- ensure `ProductReActHarness.withTraceStatus(...)` never changes business result for trace failure
- update tests for async best-effort trace behavior

Do not change:

- business database schema
- frontend
- benchmark/eval harnesses
- answer envelope semantics
- reference registry persistence
- Product ReAct tool behavior, except removing trace-induced degradation

## Acceptance Criteria

1. A successful Product ReAct turn submits a `PRODUCT_REACT_TURN` JSON artifact asynchronously.
2. A memory compression update submits a `PRODUCT_MEMORY_COMPRESSION` JSON artifact asynchronously.
3. Memory compression no longer reads and appends to `turn-{generationId}.json`.
4. Trace write failure does not change `ProductTurnResult`.
5. Queue full drops trace artifact and logs, without blocking business execution.
6. Trace files include `artifactType`.
7. Trace files are not written to business tables.
8. Existing Product ReAct tests still pass.
9. `mvn -q -DskipTests compile` passes.

## Verification Commands

Recommended targeted verification:

```bash
mvn -q -Dtest=ProductReActHarnessTest,ProductMemoryServiceTest test
mvn -q -DskipTests compile
```

Runtime smoke:

```text
send one Product ReAct chat turn
confirm turn-{generationId}.json appears
confirm memory artifact appears after memory update when memory update runs
confirm UI/chat response is unchanged if trace root is unwritable
```
