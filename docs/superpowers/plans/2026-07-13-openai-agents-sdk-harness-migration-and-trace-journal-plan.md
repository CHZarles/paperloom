# OpenAI Agents SDK Harness Migration And Lossless Trace Journal Plan

**Status:** Planning only. This document does not modify runtime code.

**Goal:** Replace the hand-written Python ReAct orchestration with the OpenAI Agents SDK while
preserving current research behavior, separating transcript memory from research memory, and
creating a lossless canonical event journal for later reinforcement learning, cache analysis,
tool-process analysis, provider diagnostics, and future analyses that are not known yet.

**Scope:** Python harness only. Preserve the current synchronous diagnostic endpoint and the
NDJSON event-stream endpoint. Retrieval ranking, corpus parsing, upstream orchestration, frontend
behavior, and the offline judge are outside the initial migration.

**SDK baseline for the compatibility spike:** openai-agents 0.18.2, released 2026-07-11. Pin the
tested version; do not depend on an unbounded latest version.

**Current-worktree reconciliation, 2026-07-13:**

- harness_py/job_runtime.py has been deleted and is not part of the target.
- harness_py/service.py now executes turns directly from ThreadingHTTPServer and exposes JSON plus
  NDJSON endpoints.
- The Python harness no longer owns Redis queues or progress persistence.
- harness_py/agent_harness.py now emits model-call completion, usage, tool duration, and compact
  progress events.
- The service provider comes from EnvProviderConfigStore.
- product_db_dataset.py and tools.py now propagate parser, bounding-box, source-object, filename,
  and visual-evidence availability metadata.
- The current verified baseline is 62 passing Python tests and 15 hard-passing Golden cases.

## 1. Decision

Migrate the orchestration layer before implementing the full analytical archive, but define the
event contract and identifiers before the migration starts.

The SDK will own:

- the model/tool loop;
- tool invocation and tool-result continuation;
- request-scoped session transcript mechanics;
- lifecycle spans and hooks;
- model retry policy once provider compatibility is proven.

PaiSmart will continue to own:

- paper authorization and disclosure state;
- selected papers and selected evidence;
- the explicit request-history and research-memory input/output contract;
- evidence identity and citation validation;
- final answer rendering and the existing external artifact contract;
- NDJSON progress and terminal-result projection;
- raw provider transport capture;
- canonical event persistence, deduplication, privacy, and retention;
- all downstream RL, cache, evaluation, and debugging projections.

The Agents SDK trace dashboard must not become the only copy of execution data. SDK tracing is an
instrumentation source feeding the PaiSmart journal.

## 2. Current Behavior That Must Be Preserved

The migration is accepted only if these current properties remain true:

1. harness_py/agent_harness.py runs one continuous model-driven loop with no fixed round limit.
2. The model must finish through submit_research_answer, not through unvalidated free text.
3. A rejected final submission is returned to the model so it can repair citations or shape.
4. A final submission mixed with another tool call is rejected.
5. Multiple tool calls from one model response execute in model order.
6. ReadingCorpusTools is stateful per run:
   - candidate or identity tools authorize papers;
   - location search discloses location references;
   - read_locations accepts only disclosed references;
   - read evidence is accumulated by evidence ID.
7. Tool results have two forms:
   - the complete internal result;
   - the model-facing result after model_facing_payload removes private fields.
8. Only user and assistant text from prior turns is replayed to the next turn.
9. Previously selected evidence is injected separately and is not reconstructed from tool prose.
10. A turn without new citations preserves the previously selected evidence and papers.
11. Greetings and clarification answers can complete without corpus tools.
12. Paper-content answers that used read evidence require valid evidence citations.
13. Existing response fields, harness-run-trace/v2 artifacts, progress events, and CLI output remain
    compatible during migration.
14. Cancellation, timeout, provider failure, malformed tool arguments, and validation failure
    become and remain distinguishable terminal or repairable outcomes.
15. The fifteen committed Golden cases continue to pass the existing BehaviorScorer.
16. service.py keeps both current endpoints:
    - POST /v1/research/turn returns one JSON response for local diagnostics;
    - POST /v1/research/stream returns application/x-ndjson progress events followed by result or
      error.
    - progress lines retain generationId, sequence, timestamp, and the current camel-case payload
      fields; terminal result and error lines retain their existing shapes.
17. The Python service remains free of Redis and queue ownership. The upstream caller supplies
    history, authorized paper IDs, and research memory on every request and owns reconnectable
    product generation state.
18. The service continues to load its MiniMax provider from EnvProviderConfigStore, while CLI and
    judge commands may retain their existing db or env provider selection.
19. Evidence projections retain the current source metadata, including original filename, parser
    name and version, bounding box, table, figure, formula, and screenshot-availability fields.
20. The HTTP turn has no overall execution deadline. Provider-call timeout and retry behavior remain
    explicit per model attempt rather than becoming an undocumented SDK default.
21. ResearchHarnessService validates the request and loads the authorized product corpus before
    invoking LiveResearchChatHarness. The migration must capture request validation and corpus-load
    failures rather than starting the journal only after an Agent exists.

### 2.1 Known Baseline Gaps This Plan Must Close

These are defects in the current capture path, not behavior to reproduce:

- A successful submit_research_answer returns before it is appended to react_trace.
- ChatTurn.raw contains the provider response, but the run artifact retains only aggregate usage.
- The tool trace stores model_facing_payload, so private internal retrieval fields are already lost.
- LiveResearchChatHarness converts a partial technical failure into a new run with an empty trace.
- The agent raises a generic RuntimeError for cancellation and LiveResearchChatHarness catches every
  Exception, so cancellation can be converted into a technical-failure answer.
- The NDJSON handler currently passes a should_cancel callback that always returns false. A broken
  client connection therefore does not become an execution cancellation until another stream write
  fails.
- run_id is derived from case_id, so replaying the same logical turn does not create a distinct
  execution identity.
- NDJSON event sequence numbers are local to one HTTP connection and are not durable. A disconnected
  stream cannot prove that every execution event was preserved.
- The final result line is delivered over the same ephemeral connection and has no Python-side
  recovery source if delivery fails after the run completes.
- ConversationState copies react_trace into tool_traces, creating another independent trace copy.

## 3. Non-Goals

- Do not change lexical retrieval, ranking, snippets, authorization, or evidence IDs.
- Do not introduce multi-agent handoffs.
- Do not switch MiniMax from Chat Completions to the OpenAI Responses API.
- Do not migrate judge.py in the first slice.
- Do not enable provider token streaming in the first slice. Preserve the existing NDJSON
  lifecycle-event stream.
- Do not add Redis, a Python job queue, or Python-owned cross-turn conversation persistence.
- Do not recreate the deleted harness_py/job_runtime.py. service.py and its direct HTTP execution
  path are the authoritative Python transport.
- Do not add a cache yet; define cache events so one can be added later.
- Do not promise access to hidden chain-of-thought. Preserve only reasoning data explicitly
  returned by the provider and store it under restricted access.
- Do not persist the same complete trace independently in conversation state, run artifacts,
  NDJSON delivery payloads, and the analytical archive.
- Do not send sensitive MiniMax prompts or tool outputs to the OpenAI trace backend unless that is
  separately approved.

## 4. Target Ownership Model

| Concern | Authoritative owner |
| --- | --- |
| Request validation, corpus loading, and response compatibility | ResearchHarnessService |
| NDJSON event delivery | service.py stream handler and ProgressProjector |
| Agent orchestration | AgentsSdkHarnessRuntime |
| Local run dependencies and mutable tool state | ResearchRunContext |
| Model-visible transcript assembly | RequestBackedSession |
| Selected papers and evidence | Request-supplied ResearchMemory |
| Authorized corpus scope | request.scope and the loaded GoldenDataset |
| Corpus behavior | Existing ReadingCorpusTools |
| Skill definitions | Existing ResearchSkillRegistry |
| Provider-specific request and response behavior | MiniMax Agents Model adapter |
| Canonical execution history | TraceJournal |
| Existing run JSON, progress events, RL rows, reports | Projections from TraceJournal |

Three data categories must remain conceptually separate:

1. **Request transcript:** upstream-supplied user and assistant history plus current-run SDK items.
2. **Research memory:** upstream-supplied selected evidence and selected papers, returned as the
   next memory projection. Authorized scope is a separate request field.
3. **Execution journal:** the only Python-owned durable history, containing immutable facts about
   what happened during each execution.

The SDK Session adapter is request-scoped in this plan. Persistent cross-turn Sessions are a
separate future architecture decision because the current Python service is intentionally
stateless between HTTP turns.

## 5. Proposed Python Module Map

Keep the repository's flat Python package style.

| Planned file | Responsibility |
| --- | --- |
| harness_py/runtime.py | SDK-neutral HarnessRuntime interface and turn input/result types |
| harness_py/agents_runtime.py | Agent construction, Runner configuration, and run lifecycle |
| harness_py/agents_context.py | ResearchRunContext and cancellation state |
| harness_py/agents_tools.py | FunctionTool adapters over current schemas and domain methods |
| harness_py/agents_model.py | MiniMax-compatible Agents SDK Model or provider adapter |
| harness_py/errors.py | Shared cancellation and runtime error types |
| harness_py/memory.py | RequestBackedSession, ResearchMemory types, and input/output policy |
| harness_py/trace_journal.py | Event envelope, local WAL, blob references, and integrity checks |
| harness_py/trace_projection.py | Legacy run artifact, progress, RL, and analytical projections |
| harness_py/tests/test_agents_model.py | MiniMax request, response, retry, and normalization contracts |
| harness_py/tests/test_agents_tools.py | Tool schemas, serial execution, raw/visible results, and state |
| harness_py/tests/test_agents_runtime.py | SDK loop, tools, final submission, and memory tests |
| harness_py/tests/test_service.py | JSON and NDJSON endpoint contract, disconnect, and ordering tests |
| harness_py/tests/test_trace_journal.py | completeness, idempotency, crash, and export tests |

Existing modules retained as domain implementations:

- harness_py/tools.py
- harness_py/research_skills.py
- harness_py/conversation.py during compatibility migration
- harness_py/provider_config.py
- harness_py/product_db_dataset.py
- harness_py/scoring.py
- harness_py/contracts.py
- harness_py/service.py

### 5.1 Current File Disposition

| Current path | Planned disposition |
| --- | --- |
| harness_py/service.py | Keep both endpoints; add runtime seam, journal creation, and disconnect-aware progress sink |
| harness_py/agent_harness.py | Keep as legacy runtime until cutover; later remove only the manual loop and message plumbing |
| harness_py/live_chat.py | Keep conversation wrapper; depend on HarnessRuntime and stop swallowing cancellation |
| harness_py/conversation.py | Keep CLI/chat state compatibility; separate request Session policy and remove full trace copies |
| harness_py/llm.py | Keep for judge.py and legacy runtime; do not delete during the first migration |
| harness_py/tools.py | Keep as the authoritative retrieval and evidence implementation |
| harness_py/product_db_dataset.py | Keep corpus and visual-asset loading behavior unchanged |
| harness_py/provider_config.py | Keep env-backed service configuration and existing db/env CLI behavior |
| harness_py/cli.py | Keep commands and serve contract; add reversible runtime selection |
| harness_py/job_runtime.py | Deleted; do not recreate |

## 6. Target Architecture

~~~plantuml
@startuml
!pragma layout smetana
title Target Python Harness Architecture
left to right direction
skinparam componentStyle rectangle

actor "Upstream caller" as Caller

package "harness_py" {
  component "ResearchHarnessService\nJSON + NDJSON endpoints" as Service
  component "NDJSON stream handler" as Stream
  component "DockerMySqlProductCorpusStore" as CorpusStore
  interface "HarnessRuntime" as Runtime
  component "AgentsSdkHarnessRuntime" as AgentsRuntime
  component "OpenAI Agents SDK\nAgent + Runner" as SDK
  component "ResearchRunContext" as Context
  component "FunctionTool adapters" as ToolAdapters
  component "ReadingCorpusTools" as Corpus
  component "ResearchSkillRegistry" as Skills
  component "MiniMax Agents Model" as Model

  interface "Conversation Session" as Session
  component "RequestBackedSession\none HTTP turn" as SessionImpl
  component "Request-supplied\nResearchMemory" as Memory

  component "JournalTracingProcessor" as Processor
  component "TraceJournal" as Journal
  database "Local SQLite WAL" as WAL
  storage "Content-addressed blobs" as Blobs
  component "ProgressProjector" as Progress
  component "RunArtifactProjector" as Artifacts
}

cloud "MiniMax\nChat Completions" as Provider
database "Product corpus DB" as ProductDB

Caller --> Service : history, scope,\nresearch_memory
Service --> Stream : /v1/research/stream
Service --> Journal : request + corpus-load lifecycle
Service --> CorpusStore : authorized paper IDs
CorpusStore --> ProductDB
Service --> Runtime
AgentsRuntime ..|> Runtime
Service --> AgentsRuntime
AgentsRuntime --> SDK
AgentsRuntime --> Context
AgentsRuntime --> Session
SessionImpl ..|> Session
AgentsRuntime --> Memory

SDK --> Model
Model --> Provider
SDK --> ToolAdapters
ToolAdapters --> Context
Context --> Corpus
Context --> Skills

SDK --> Processor
Processor --> Journal : semantic spans
Model --> Journal : HTTP attempts and raw payloads
ToolAdapters --> Journal : raw and model-visible I/O
Journal --> WAL
Journal --> Blobs
Journal --> Progress
Progress --> Stream : ordered NDJSON events
Stream --> Caller : progress + result/error
AgentsRuntime --> Artifacts
Artifacts --> Journal : read canonical facts
Artifacts --> Service
Service --> Caller : /v1/research/turn JSON
@enduml
~~~

## 7. Runtime Design

### 7.1 Harness Runtime Seam

Introduce one small interface that is independent of OpenAI SDK classes:

~~~text
HarnessRuntime.run_turn(TurnExecutionInput) -> TurnExecutionResult
~~~

TurnExecutionInput contains the dataset, request ID, conversation ID, user message, transcript
snapshot, research memory snapshot, progress sink, disconnect-aware cancellation token, and runtime
options. TraceJournal is opened by ResearchHarnessService before corpus loading and passed into the
runtime so setup failures and SDK execution share one run identity.

TurnExecutionResult contains the accepted research answer, next research-memory projection, usage,
terminal status, run ID, and journal reference. JSON and NDJSON response construction stays in
service.py, outside the SDK adapter.

Keep the existing runtime behind LegacyHarnessRuntime until cutover. This creates a real seam with
two adapters and provides immediate rollback.

### 7.2 Research Run Context

Create one ResearchRunContext per execution. It contains:

- request_id: stable logical request or generation ID;
- run_id: unique ID for this execution attempt;
- conversation_id and turn index;
- corpus snapshot ID and authorized paper IDs;
- one per-run ReadingCorpusTools instance;
- ResearchSkillRegistry;
- prior evidence and selected paper IDs;
- final-submission state;
- model-response tool-call groups;
- TraceJournal handle;
- NDJSON-compatible progress publisher;
- cancellation token.

The context is local and must never be serialized into the model prompt as a Python object. Only an
explicit prompt projection may become model-visible.

### 7.3 Tool Adapters

Use explicit FunctionTool objects first, not decorator-generated schemas. Reuse the current JSON
schemas exactly so the migration does not silently change required fields, descriptions, limits,
or additionalProperties behavior.

Each tool invocation performs this sequence:

1. Read the current SDK tool call ID and raw argument string from ToolContext.
2. Check cancellation.
3. Append tool.call.started with the exact raw and parsed arguments.
4. Call the existing ResearchSkillRegistry or ReadingCorpusTools implementation.
5. Capture the complete internal result before redaction.
6. Produce model_facing_payload for the model.
7. Capture the corpus authorization, disclosed-location, and evidence state delta.
8. Append tool.call.completed or tool.call.failed.
9. Return only the model-visible result to the SDK.

Use RunConfig with ToolExecutionConfig(max_function_tool_concurrency=1). The SDK otherwise starts
function calls concurrently, which would break the stateful search-then-read authorization model.

### 7.4 Final Submission

Retain submit_research_answer as a real tool.

The submission tool returns a typed internal SubmissionResult:

~~~text
accepted
draft
validation_error
tool_call_id
model_call_id
~~~

An on_llm_end hook records all tool call IDs produced by the current model response before tool
execution begins. The submission tool uses that group to reject a final call that has siblings.

Configure a custom ToolsToFinalOutputFunction:

- exactly one accepted submit_research_answer result: stop and return the draft;
- rejected submission: continue the model loop with the validation result;
- mixed final and research tools: continue after returning the rejection;
- ordinary research tools: continue.

The provider compatibility spike must prove that MiniMax honors tool_choice=required. Keep
Agent.reset_tool_choice disabled so every model turn must select a tool. If MiniMax does not honor
required tool choice, the MiniMax Model adapter must implement the current text-only response nudge
without adding a second orchestration framework.

Use max_turns=None to preserve the current no-round-limit behavior. Any later budget limit is a
separate product decision.

### 7.5 Prompt Construction

Preserve the current ordering and visibility rules:

1. agent instructions;
2. user and assistant text history only;
3. one curated previous-evidence context item;
4. current user message;
5. current-run tool calls and results managed by the SDK.

Use session_input_callback to filter persisted SDK history. Previous tool calls, tool outputs, and
reasoning items may remain in the request-scoped Session for the current execution, but they are not
returned as cross-turn model history. The canonical journal, not Session persistence, owns audit
history.

Snapshot the exact outgoing request during the compatibility phase and compare it with the legacy
request. Prompt movement is treated as a behavioral change, not a formatting refactor.

### 7.6 MiniMax Model Adapter

First test OpenAIChatCompletionsModel with an AsyncOpenAI client configured with the MiniMax base
URL and key.

The generic adapter is accepted only if it preserves:

- MiniMax-M3 thinking.type=adaptive for research calls;
- disabled thinking for existing required-tool judge calls that remain on the legacy adapter;
- temperature, top_p, and maximum completion tokens;
- exact function schemas and tool choice;
- tool call IDs and multiple tool calls;
- the MiniMax structured argument wrapper normalization using the $text convention;
- raw usage and finish metadata;
- retryable HTTP status handling;
- provider response IDs and raw response fields needed by the journal.

If any item is lost, implement the Agents SDK Model interface in agents_model.py using the existing
MiniMax request and parse behavior. Do not distort corpus tools to accommodate a provider adapter.

Run only one retry layer. Initially retain provider-level retries. Adopt SDK ModelRetrySettings only
after every attempt is journaled and replay safety has been tested.

## 8. Memory Design

### 8.1 Migration Rule

Do not change memory authority while changing the agent runner. The upstream request remains the
source of cross-turn history and research memory, and the Python response returns the next research
memory. This matches the current stateless service contract.

Use one RequestBackedSession per run, seeded from request.history. Do not introduce RedisSession,
SQLAlchemySession, OpenAI Conversations, previous_response_id, or any other second cross-turn state
owner in this migration.

### 8.2 Transcript Session

The request-scoped Session may store full SDK items during the run, while session_input_callback
selects the current model-visible history. It must satisfy:

- history remains chronological;
- upstream history is seeded exactly once for the execution;
- the current user message appears once;
- provider or runner retries do not duplicate current-run items;
- previous-turn tool calls and tool outputs are not replayed;
- CLI reset continues to clear ConversationState;
- service requests do not depend on process-local Session state from an earlier HTTP request.

### 8.3 Research Memory

Model the existing service research_memory payload directly:

~~~text
selected_paper_ids
selected_evidence_ids
evidence_items_by_id
~~~

ResearchMemory has no Python persistence interface in this plan. service.py constructs it from
request.research_memory; conversation history and authorized scope remain separate TurnExecutionInput
fields. The runtime returns a deterministic next-memory projection in the terminal result.

ConversationState continues to carry conversation_id, scope_paper_ids, message_history, last_run_id,
and last_answer_summary for chat and chat-shell compatibility. Those fields are not silently added
to the HTTP research_memory contract.

The projection preserves current behavior:

- merge newly read evidence by evidence ID;
- select newly cited evidence when present;
- otherwise retain previously selected evidence and papers;
- never use candidate cards as citeable evidence;
- store run references, not another complete copy of the tool trace.

If Python-owned persistent memory is later proposed, it requires a separate decision covering
authority, idempotency, concurrent turns, reset semantics, and migration of the upstream contract.

## 9. Lossless Trace Journal

### 9.1 Event Envelope

Every event uses one versioned envelope:

~~~json
{
  "schema_version": "harness-event/v1",
  "event_id": "uuidv7-or-ulid",
  "request_id": "logical-request-id",
  "run_id": "unique-execution-id",
  "conversation_id": "conversation-id",
  "turn_id": "stable-turn-id",
  "sequence": 17,
  "kind": "tool.call.completed",
  "span_id": "sdk-or-local-span-id",
  "parent_span_id": "parent-span-id",
  "operation_id": "logical-operation-id",
  "attempt": 1,
  "occurred_at": "UTC timestamp",
  "monotonic_ns": 123456789,
  "producer": {
    "service": "harness_py",
    "sdk_version": "0.18.2",
    "git_revision": "revision"
  },
  "payload_ref": {
    "sha256": "content-hash",
    "media_type": "application/json",
    "sensitivity": "user_content"
  }
}
~~~

### 9.2 Identity Rules

- request_id identifies one logical requested generation.
- run_id identifies one actual execution attempt.
- The existing NDJSON generationId remains request_id. An upstream retry uses the same request ID
  only when it is the same logical generation, but receives a new run_id in the journal.
- span_id identifies one logical operation within that execution.
- attempt distinguishes real provider retries.
- event_id is generated before persistence and reused when the write itself is retried.
- sequence is allocated and inserted atomically with the event.
- provider tool_call_id is correlation metadata, not the event primary key.
- NDJSON sequence is a projection of canonical event order for one delivery stream; it is not an
  event identity and is allowed to restart for a new execution attempt.

Never deduplicate events by payload hash. Two identical calls at different times are two real
actions. Deduplicate only the storage of identical payload bytes.

### 9.3 Canonical Event Families

Run:

- run.started
- run.context.snapshot
- run.completed
- run.failed
- run.cancelled
- run.abandoned

Service and corpus:

- request.received
- request.validated
- request.rejected
- corpus.load.started
- corpus.load.completed
- corpus.load.failed
- delivery.started
- delivery.completed
- delivery.failed

Model:

- model.call.started
- model.call.completed
- model.call.failed
- model.transport.attempt.started
- model.transport.attempt.completed
- model.transport.attempt.failed
- model.retry.scheduled
- model.response.parsed

Tool:

- tool.call.started
- tool.call.completed
- tool.call.failed
- tool.state.changed
- tool.output.projected

Memory:

- memory.request.loaded
- memory.input.selected
- memory.output.projected

Answer:

- answer.submission.received
- answer.validation.failed
- answer.validation.passed
- answer.rendered

Future-safe:

- cache.lookup
- cache.hit
- cache.miss
- cache.write
- cache.evict
- feedback.recorded
- reward.assigned
- judge.completed

### 9.4 Raw And Derived Payloads

Capture these losslessly:

- parsed service request after secret stripping, request-validation result, and requested scope;
- corpus-load query parameters, selected paper IDs, row counts, dataset summary, and load failure;
- exact provider request bytes after removing authorization secrets;
- response status, selected headers, exact response bytes, and provider request ID;
- parsed model output and normalized tool calls;
- provider-exposed reasoning fields, when present;
- exact tool argument string and parsed arguments;
- complete internal tool result;
- model-visible redacted tool result;
- prompt, instructions, tool schemas, model settings, and their hashes;
- corpus snapshot, paper IDs, paper versions, parser versions, visual-asset availability, bounding
  boxes, source object IDs, original filenames, and code revision;
- usage and provider cache metadata;
- exceptions, cancellation, timeout, and retry decisions.

Large or repeated payloads use content-addressed blobs keyed by SHA-256. Events contain references,
so repeated prompts, schemas, evidence, and tool outputs are stored once without erasing distinct
event occurrences.

### 9.5 Persistence

Use a two-tier design:

1. A synchronous local SQLite WAL is the first durable write and enforces primary and unique keys.
2. Content-addressed files hold compressed large payloads.
3. An exporter copies committed events and blobs to the long-term analytical store.
4. Export is idempotent by event ID and blob hash.
5. NDJSON remains an ephemeral delivery projection, not the analytical source of truth.
6. The progress projection uses canonical journal ordering rather than allocating an unrelated
   second execution sequence in service.py.
7. TraceJournal is safe under concurrent ThreadingHTTPServer request threads. Use a serialized
   writer or one SQLite connection per thread, WAL mode, a busy timeout, per-run atomic sequence
   allocation, and atomic blob publication.

Minimum tables:

~~~text
runs
  run_id primary key
  request_id
  conversation_id
  status
  started_at
  completed_at
  final_sequence
  event_count
  final_event_hash

events
  event_id primary key
  run_id
  sequence
  kind
  span_id
  parent_span_id
  attempt
  payload_hash
  unique(run_id, sequence)
  unique(run_id, idempotency_key)

blobs
  sha256 primary key
  size
  media_type
  compression
  sensitivity
  storage_uri

exports
  sink
  event_id
  status
  attempt
  unique(sink, event_id)
~~~

### 9.6 Completeness Invariants

- A run has exactly one run.started event.
- A run has at most one terminal run event.
- Every started model, tool, memory-projection, and export span has one terminal event or is recovered as
  abandoned.
- Sequences are contiguous inside a closed run.
- The closing run event records event_count, final_sequence, and the final event hash.
- Recovery scans open runs and records run.abandoned without pretending their external outcome is
  known.
- A network disconnect after a request was sent may produce outcome_unknown; it must not be
  rewritten as a definite failure.
- A broken NDJSON connection marks the progress sink disconnected, requests cancellation at the
  next safe boundary, and leaves the partial journal recoverable.
- Journal failure is never silently ignored. Strict mode stops the run; buffered mode must first
  commit to a local durable spool.

## 10. SDK Tracing Integration

Configure a JournalTracingProcessor with set_trace_processors so SDK traces are written locally and
the default OpenAI exporter is replaced.

The processor is global to the Python process and therefore must be thread-safe. It must correlate
every callback through SDK trace and span IDs and must never keep one mutable current-run variable
shared across HTTP request threads.

Use SDK tracing as the single source for semantic agent, generation, function-tool, guardrail, and
handoff span lifecycle. Do not emit duplicate semantic lifecycle events from RunHooks.

Use:

- the current SDK trace and span IDs for hierarchy;
- the model adapter for transport-attempt child events;
- the tool adapters for raw input, raw result, model-visible output, and state-delta child events;
- RunHooks only for cancellation checks and journal-backed progress projection.

ProgressProjector maps canonical events to the existing event vocabulary, including job_started,
model_call_started, model_call_completed, tool_started, tool_completed, answer_completed, result,
and error. service.py adds transport framing but does not independently rediscover model or tool
lifecycle.

Keep trace input/output capture enabled only because the processor is local and access controlled.
If a later OpenAI exporter is added, it receives a separately redacted projection.

## 11. Derived Views

The journal is canonical. Everything below is rebuilt from it:

- harness-run-trace/v2;
- react_trace;
- paper_candidates;
- evidence_ledger;
- citation_validation;
- token and latency diagnostics;
- UI progress events;
- NDJSON delivery events and delivery failures;
- provider retry reports;
- cache hit reports;
- RL trajectories;
- debugging bundles;
- offline evaluator packets.

### 11.1 RL Projection

One model call becomes one trajectory step:

~~~text
observation
  exact model-visible instructions, history, tools, and context

action
  assistant content, reasoning data exposed by the provider, and tool calls

environment response
  tool results, validation feedback, errors, or cancellation

policy metadata
  provider, model, settings, prompt hash, tool-schema hash, code revision

reward
  a later immutable event linked to run_id and action span_id

done
  accepted final answer, failure, cancellation, or abandonment
~~~

Delayed human or judge rewards append new events. Historical trajectory events are not mutated.

### 11.2 Cache Projection

Future cache events include namespace, key hash, cache policy version, hit or miss, entry ID, age,
TTL, value hash, source span, and bypass reason. A hit is an action and remains an event even when
the referenced value blob already exists.

## 12. End-To-End Turn Sequence

~~~plantuml
@startuml
title One Agents SDK Research Turn
autonumber

actor Caller
participant ResearchHarnessService as Service
participant "NDJSON progress sink" as Stream
participant DockerMySqlProductCorpusStore as CorpusStore
participant AgentsSdkHarnessRuntime as Runtime
participant TraceJournal as Journal
participant RequestBackedSession as Session
participant "Request ResearchMemory" as Memory
participant "Agents SDK Runner" as Runner
participant JournalTracingProcessor as Processor
participant MiniMaxModel as Model
participant "MiniMax API" as Provider
participant FunctionToolAdapter as Tool
participant ReadingCorpusTools as Corpus
participant RunArtifactProjector as Projector

Caller -> Service : POST /v1/research/stream\nhistory + scope + research_memory
Service -> Stream : open application/x-ndjson
Service -> Journal : run.started + request.received
Journal -> Stream : project job_started
Stream --> Caller : ordered NDJSON event
Service -> Journal : request.validated
Service -> CorpusStore : load_dataset(authorized paper IDs)
CorpusStore --> Service : GoldenDataset + visual asset metadata
Service -> Journal : corpus.load.completed + context snapshot
note over Service,Journal
  Request rejection or corpus-load failure closes the same run
  and emits the existing error line without entering Runner.
end note
Service -> Runtime : run_turn(input + journal)
Runtime -> Session : seed request.history once
Runtime -> Memory : load request value
Runtime -> Runner : run_sync(agent, input, context, session,\nmax_turns=None, tool concurrency=1)

loop Until accepted final submission
  Runner -> Processor : generation span started
  Processor -> Journal : model.call.started
  Journal -> Stream : project model_call_started
  Stream --> Caller : NDJSON event
  Runner -> Model : get_response(...)
  Model -> Journal : transport.attempt.started
  Model -> Provider : POST /chat/completions

  alt Provider succeeds
    Provider --> Model : raw response
    Model -> Journal : transport.attempt.completed + raw blob
    Model --> Runner : parsed ModelResponse
    Processor -> Journal : model.call.completed
    Journal -> Stream : project model_call_completed
    Stream --> Caller : NDJSON event
  else Retryable failure
    Provider --> Model : error or timeout
    Model -> Journal : transport.attempt.failed
    Model -> Journal : model.retry.scheduled
  end

  opt Model emitted tool calls
    loop Calls execute serially in model order
      Runner -> Tool : invoke with ToolContext
      Tool -> Journal : tool.call.started + raw arguments
      Journal -> Stream : project tool_started
      Stream --> Caller : NDJSON event
      Tool -> Corpus : existing domain call
      Corpus --> Tool : internal result + state mutation
      Tool -> Journal : internal result + visible result + state delta
      Journal -> Stream : project tool_completed
      Stream --> Caller : NDJSON event
      Tool --> Runner : model-visible result
    end
  end

  alt Final submission rejected
    Tool -> Journal : answer.validation.failed
    Runner -> Model : validation result in next turn
  else Final submission accepted
    Tool -> Journal : answer.validation.passed
    Runner --> Runtime : final ResearchAnswerDraft
  end
end

Runtime -> Session : read current-run new items
Runtime -> Memory : project next research_memory
Runtime -> Projector : build compatible run artifact
Projector -> Journal : read canonical events
Runtime -> Journal : run.completed + closure manifest
Runtime --> Service : TurnExecutionResult
Journal -> Stream : project answer_completed
Stream --> Caller : NDJSON answer_completed
Service --> Caller : result line with existing response payload

alt Broken pipe or reset connection
  Stream -> Runtime : mark transport disconnected
  Runtime -> Journal : delivery.failed + run.cancelled at safe boundary
else Failure or process recovery
  Runtime -> Journal : run.failed / run.abandoned
  Journal -> Stream : project error when connection remains writable
end
@enduml
~~~

## 13. Journal And Projection Flow

~~~plantuml
@startuml
!pragma layout smetana
title Canonical Journal And Derived Data
left to right direction
skinparam componentStyle rectangle

package "Canonical event sources" {
  component "Service request and corpus load" as ServiceEvents
  component "SDK semantic spans" as SDKSpans
  component "Model transport attempts" as Transport
  component "Tool raw and visible I/O" as ToolIO
  component "Request memory projections" as MemoryEvents
  component "Answer validation" as AnswerEvents
  component "Future cache and feedback" as FutureEvents
}

component TraceJournal as Journal
database "SQLite WAL" as WAL
storage "SHA-256 blob store" as BlobStore
component "Idempotent exporter" as Exporter
database "Long-term event archive" as Archive
storage "Long-term object store" as ObjectStore
component "Integrity auditor" as Auditor

package "Derived projections" {
  component "Legacy run artifact" as Legacy
  component "UI progress" as UI
  component "RL trajectory" as RL
  component "Cache analysis" as Cache
  component "Provider diagnostics" as Diagnostics
  component "Evaluation dataset" as Eval
}

ServiceEvents --> Journal
SDKSpans --> Journal
Transport --> Journal
ToolIO --> Journal
MemoryEvents --> Journal
AnswerEvents --> Journal
FutureEvents --> Journal

Journal --> WAL
Journal --> BlobStore
WAL --> Exporter
BlobStore --> Exporter
Exporter --> Archive
Exporter --> ObjectStore
Archive --> Auditor
ObjectStore --> Auditor

Archive --> Legacy
Archive --> UI
Archive --> RL
Archive --> Cache
Archive --> Diagnostics
Archive --> Eval
@enduml
~~~

## 14. Implementation Sequence

### Task 0: Freeze The Baseline

**Purpose:** Make behavioral drift visible before adding the SDK.

**Actions:**

- Record the current Python version, provider settings, prompt hash, tool-schema hash, and Git
  revision.
- Record the current audited baseline: 62 Python tests pass and all 15 Golden cases hard-pass.
- Add deterministic contract coverage for service.py before changing its runtime:
  - /health reports ndjson-stream transport;
  - /v1/research/turn returns the current JSON shape;
  - /v1/research/stream orders job, model, tool, answer, and result lines;
  - malformed requests and stream-time failures use the current error vocabulary.
- Capture deterministic transcripts for:
  - direct greeting;
  - exact fact with search, location, read, and citation;
  - invalid final citation followed by repair;
  - prior-evidence reuse;
  - multiple tool calls in one model response;
  - technical failure after partial progress.
- Record current JSON response, NDJSON transcript, evidence metadata, and harness-run-trace/v2
  examples.

**Gate:** No migration work begins until failures in the existing baseline are separated from new
SDK regressions.

### Task 1: Pin Dependencies And Run A MiniMax Compatibility Spike

**Planned files:**

- Create harness_py/requirements.in.
- Create harness_py/requirements.lock.
- Create harness_py/tests/test_agents_model.py.

**Actions:**

- Pin openai-agents 0.18.2 and its resolved dependency set.
- Test the generic OpenAIChatCompletionsModel against the existing local HTTP fixture.
- Verify request body, tool schemas, tool choice, thinking, usage, errors, retry behavior, and $text
  argument normalization.
- Verify run_sync works in the current ThreadingHTTPServer request thread.
- Verify tool_choice=required and max_turns=None with MiniMax-compatible responses.
- Keep the service's provider source as EnvProviderConfigStore and keep model calls non-streamed;
  the existing NDJSON event stream is a separate transport concern.

**Decision gate:**

- If every provider contract is preserved, use the generic Chat Completions model.
- Otherwise implement a custom MiniMax Model adapter before continuing.

### Task 2: Introduce HarnessRuntime Without Changing Behavior

**Planned files:**

- Create harness_py/runtime.py.
- Modify harness_py/live_chat.py.
- Modify harness_py/service.py.
- Modify harness_py/cli.py.
- Create harness_py/tests/test_service.py.
- Add focused interface and endpoint tests.

**Actions:**

- Define TurnExecutionInput and TurnExecutionResult.
- Wrap the existing ResearchAgentHarness as LegacyHarnessRuntime.
- Make service, live chat, Golden runs, and CLI depend on HarnessRuntime.
- Keep service.py responsible for request validation, authorized corpus loading, JSON/NDJSON
  framing, and journal creation; it must not inspect SDK result internals.
- Add runtime selection: legacy, agents_sdk, and replay comparison.
- Keep legacy as the default.

**Gate:** All existing tests pass byte-for-byte where deterministic.

### Task 3: Port Context And Tools

**Planned files:**

- Create harness_py/agents_context.py.
- Create harness_py/agents_tools.py.
- Create harness_py/tests/test_agents_tools.py.

**Actions:**

- Instantiate one ResearchRunContext and ReadingCorpusTools per run.
- Wrap the existing tool schemas with explicit FunctionTool objects.
- Preserve complete internal results and model-facing redacted results.
- Preserve evidence provenance and visual-asset fields now produced by product_db_dataset.py and
  tools.py.
- Set maximum function-tool concurrency to one.
- Dynamically enable get_citation_edges only when the corpus supports it.
- Preserve tool error payloads rather than replacing expected domain errors with generic SDK
  exceptions.
- Add cancellation checks around every tool.

**Gate:** The same scripted model calls produce the same ordered tool results and corpus state.

### Task 4: Port The Agent Loop And Final Submission

**Planned files:**

- Create harness_py/agents_runtime.py.
- Create harness_py/errors.py.
- Add final-submission and prompt-parity tests.

**Actions:**

- Move the current system prompt into Agent instructions without semantic rewriting.
- Build the exact current history and prior-evidence input.
- Configure max_turns=None.
- Record each model response's tool-call group before tool execution.
- Implement submit_research_answer validation.
- Implement custom ToolsToFinalOutputFunction.
- Reject mixed final and research calls.
- Prove invalid citations return to the model for repair.
- Prove a text-only response cannot bypass final validation.
- Move the cancellation exception to a shared Python harness error type and let cancellation pass
  through LiveResearchChatHarness without conversion to a technical-failure answer.
- Make the NDJSON sink set the cancellation token after BrokenPipeError or ConnectionResetError.
- Ensure a disconnected client stops at the next model/tool boundary and the journal remains
  terminally classified.
- Aggregate SDK usage into the existing diagnostics shape.

**Gate:** Synthetic ReAct tests and all final-answer validation tests pass through the SDK runtime.

### Task 5: Adopt Sessions Without Changing Memory Authority

**Planned files:**

- Create harness_py/memory.py.
- Modify harness_py/conversation.py only through an adapter.
- Add session merge, retry, reset, and deduplication tests.

**Actions:**

- Implement RequestBackedSession for the first cut.
- Use session_input_callback to replay only user and assistant text.
- Inject selected evidence exactly once.
- Separate ResearchMemory from transcript items.
- Preserve the current incomplete-turn memory behavior.
- Return the next ResearchMemory through the existing service response.
- Preserve ConversationState JSON persistence for chat and chat-shell.
- Prove that two HTTP requests with the same conversation_id share no hidden process-local Session
  state beyond the history and memory explicitly supplied in each request.

**Gate:** Multi-turn service and CLI tests pass with no prompt duplication, no hidden Python
cross-turn state, and no loss of selected evidence.

### Task 6: Add The Canonical Trace Journal

**Planned files:**

- Create harness_py/trace_journal.py.
- Create harness_py/tests/test_trace_journal.py.

**Actions:**

- Implement event and blob schemas.
- Implement atomic sequence allocation and event insertion in SQLite WAL.
- Support concurrent request threads without cross-run sequence or context leakage.
- Implement idempotent event writes and content-addressed blobs.
- Implement run closure and recovery of open runs.
- Implement sensitivity labels and secret stripping.
- Implement the SDK JournalTracingProcessor.
- Start the journal in ResearchHarnessService and capture request validation plus product-corpus
  loading before the SDK runtime starts.
- Instrument MiniMax transport attempts and tool detail events.
- Record NDJSON delivery success, disconnect, and terminal-result delivery state without treating
  delivery events as model or tool execution events.
- Disable the default OpenAI trace exporter.

**Gate:** Fault-injection and concurrent-request tests prove no silent loss, no duplicate canonical
records, and no cross-run event contamination.

### Task 7: Rebuild Existing Artifacts As Projections

**Planned files:**

- Create harness_py/trace_projection.py.
- Modify harness_py/agent_harness.py compatibility helpers.
- Modify harness_py/service.py.
- Modify harness_py/conversation.py.
- Extend contract tests.

**Actions:**

- Generate react_trace, evidence ledger, paper candidates, usage, and diagnostics from journal facts.
- Generate the current compact progress vocabulary from the same canonical events.
- Use canonical projection order for NDJSON sequence numbers.
- Keep both service endpoint contracts unchanged.
- Replace ConversationState.tool_traces copies with run references after readers have migrated.
- Preserve current citation rendering, answer IDs, and expanded evidence provenance fields.

**Gate:** Existing artifact and frontend-contract tests pass; journal-to-artifact replay is
deterministic.

### Task 8: Compare Runtimes And Cut Over

**Actions:**

- Run legacy and SDK runtimes against deterministic recorded model responses.
- Run all fifteen Golden cases through the SDK runtime.
- Run selected live MiniMax smokes for direct, exact-fact, comparison, recommendation, and
  prior-evidence reuse cases.
- Run one /v1/research/stream smoke and verify ordered progress plus the terminal result line.
- Force a client disconnect during a model/tool loop and verify cancellation plus partial-journal
  retention.
- Compare status, outcome, fields, citations, tool authorization, token usage, and memory patches.
- Use replay comparison instead of issuing duplicate live provider calls in production.
- Enable the SDK runtime behind a reversible configuration flag.
- Keep legacy rollback available through an agreed stability window.

**Gate:** No unexplained hard-score regression, no memory duplication, no incomplete closed trace,
no NDJSON ordering regression, and no external response-contract change.

### Task 9: Remove Superseded Orchestration

Remove only after the stability gate:

- the hand-written while loop;
- manual assistant tool-message construction;
- manual tool-result message construction;
- duplicate progress instrumentation;
- final run-only trace accumulation;
- full trace copies in conversation state.

Retain:

- ReadingCorpusTools;
- ResearchSkillRegistry;
- citation validation and deterministic rendering;
- Golden data and BehaviorScorer;
- legacy ChatModel paths still used by judge.py until separately migrated.

## 15. Verification Matrix

| Requirement | Authoritative proof |
| --- | --- |
| Provider compatibility | Local HTTP contract tests inspect exact request and parsed response |
| Tool schema parity | Snapshot comparison of legacy definitions and SDK FunctionTool schemas |
| Sequential stateful tools | Multi-call test where search authorization is required by the next call |
| Final submission only | Mixed-call, invalid-citation, and text-only response tests |
| Conversation parity | Existing multi-turn, greeting, choice, reset, and evidence-reuse tests |
| Stateless service memory | Repeated HTTP requests prove only supplied history and memory are visible |
| NDJSON compatibility | Endpoint tests assert content type, event ordering, sequence, result, and error |
| Disconnect handling | Broken-pipe test proves cancellation and partial-journal retention |
| Concurrent request isolation | Two interleaved stream requests keep independent context and sequence |
| Evidence metadata parity | Projection tests preserve parser, bbox, object ID, filename, and asset flags |
| Setup failure visibility | Invalid request and corpus-load tests close a journaled run before Runner |
| No lost partial trace | Inject failure after every start boundary and inspect journal |
| No duplicate events | Retry the same append and export using the same event ID |
| Real retries preserved | Two provider attempts have different attempt values and events |
| Blob deduplication | Repeated identical payloads produce one blob and multiple references |
| Artifact compatibility | Existing run, service, CLI, and contract tests |
| Research behavior | Fifteen Golden cases have zero hard failures |
| Rollback | Runtime flag switches back to legacy without data migration |

## 16. Required Verification Commands During Implementation

From the PaiSmart repository root:

~~~bash
python3 -m unittest \
  harness_py.tests.test_harness_py \
  harness_py.tests.test_golden_v2 \
  harness_py.tests.test_judge -v

python3 -m unittest \
  harness_py.tests.test_agents_model \
  harness_py.tests.test_agents_tools \
  harness_py.tests.test_agents_runtime \
  harness_py.tests.test_service \
  harness_py.tests.test_trace_journal -v

python3 -m harness_py validate
python3 -m harness_py audit
python3 -m py_compile harness_py/*.py harness_py/tests/*.py
~~~

Add live MiniMax smokes only after deterministic tests pass. Live results are evidence for provider
compatibility, not replacements for deterministic acceptance tests.

## 17. Rollback Strategy

- Preserve LegacyHarnessRuntime until the SDK runtime passes the stability window.
- Keep external request, response, memory, and artifact versions unchanged during cutover.
- Keep journal writes additive; legacy and SDK runtimes can both write the same event envelope.
- A failed SDK run may be replayed through legacy only when the original request is side-effect
  safe. Record the second execution with a new run_id.
- Never hide SDK failure by overwriting its run. Both attempts remain linked by request_id.
- RequestBackedSession is reconstructed from every request, so rollback does not require migrating
  Python-owned conversation state.

## 18. Principal Risks And Mitigations

| Risk | Mitigation |
| --- | --- |
| MiniMax differs from OpenAI Chat Completions semantics | Compatibility spike and custom Model adapter |
| SDK executes function tools concurrently | ToolExecutionConfig with concurrency one |
| SDK accepts unvalidated text as final output | Required tool choice plus explicit compatibility test |
| Session replays old tool outputs | session_input_callback filters model-visible history |
| Request-scoped Session leaks across HTTP turns | Construct a new Session for every request and test isolation |
| NDJSON client disconnect leaves expensive work running | Disconnect-aware cancellation token and partial journal |
| Progress and journal sequences diverge | Derive progress ordering from canonical journal events |
| SDK tracing loses process-crash data | Synchronous local WAL and recovery audit |
| Default tracing exports sensitive data | Replace default processors with local JournalTracingProcessor |
| SDK upgrade changes behavior | Pin 0.18.2 and run contract tests before every upgrade |
| SDK types leak throughout the codebase | HarnessRuntime, RequestBackedSession, and TraceJournal seams |
| Legacy and journal artifacts drift | Build artifacts as projections and compare deterministic replay |
| Provider retries are counted twice | Enable only one retry layer |
| Local and remote archive temporarily diverge | Idempotent exporter with explicit checkpoints |

## 19. Completion Criteria

The migration and journal project is complete only when:

1. The production Python harness runs through AgentsSdkHarnessRuntime.
2. All current Python tests pass.
3. The fifteen committed Golden cases have zero hard failures.
4. MiniMax provider contract tests prove required request and response behavior.
5. Tool calls remain sequential and authorization-safe.
6. Final answers cannot bypass citation validation.
7. RequestBackedSession and ResearchMemory are separate, and neither creates hidden Python
   cross-turn state.
8. No closed run contains a sequence gap or open span.
9. Retried event writes and exports create no duplicate records.
10. Identical large payloads are stored once by hash.
11. A failed or cancelled turn retains its partial execution history.
12. Both JSON and NDJSON service contracts and all artifact schemas remain compatible or are
    deliberately versioned.
13. RL trajectories can be reconstructed entirely from journal events and referenced blobs.
14. Cache events can be added without changing the event envelope.
15. The legacy runtime has passed the rollback window and can be removed without losing a required
    caller or judge path.
16. Evidence projections preserve the current parser, location, bounding-box, source-object, and
    visual-asset metadata.
17. Request validation and corpus-load failures are present in the same canonical journal as agent
    execution failures.

## 20. Official SDK References

- Sessions: https://openai.github.io/openai-agents-python/sessions/
- Running agents and max_turns: https://openai.github.io/openai-agents-python/running_agents/
- Agent tool-use behavior: https://openai.github.io/openai-agents-python/agents/
- Function tools and ToolContext: https://openai.github.io/openai-agents-python/tools/
- Local run context: https://openai.github.io/openai-agents-python/context/
- Tracing and custom processors: https://openai.github.io/openai-agents-python/tracing/
- Models and non-OpenAI providers: https://openai.github.io/openai-agents-python/models/
- SDK release v0.18.2: https://github.com/openai/openai-agents-python/releases/tag/v0.18.2
