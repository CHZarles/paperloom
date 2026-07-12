# OpenAI Agents SDK Harness Migration With Minimal Eval Capture

**Status:** Planning only. This document does not modify runtime code.

**Goal:** Replace the hand-written Python ReAct orchestration with the OpenAI Agents SDK while
preserving current research behavior and keeping transcript memory separate from research memory.
Runtime data needed for evaluation and later research is recorded once as a simple append-only
sidecar; all interpretation, projection, aggregation, and dataset construction happens offline.

**Scope:** Python harness only. Preserve the current synchronous diagnostic endpoint and the
NDJSON event-stream endpoint. Retrieval ranking, corpus parsing, upstream orchestration, frontend
behavior, Java code, and the offline judge are outside the initial migration.

**Guiding constraint:** The harness is an orchestration system, not an analytics platform. Eval
capture must remain a leaf dependency: it can observe and persist facts, but it must not control
the model loop, memory, progress delivery, response construction, or artifact generation.

**SDK baseline for the compatibility spike:** openai-agents 0.18.2, released 2026-07-11. Pin the
tested version; do not depend on an unbounded latest version.

**Current-worktree reconciliation, 2026-07-13:**

- harness_py/job_runtime.py has been deleted and is not part of the target.
- harness_py/service.py executes turns directly from ThreadingHTTPServer and exposes JSON plus
  NDJSON endpoints.
- The Python harness no longer owns Redis queues or progress persistence.
- harness_py/agent_harness.py emits model-call completion, usage, tool duration, and compact
  progress events.
- The service provider comes from EnvProviderConfigStore.
- product_db_dataset.py and tools.py propagate parser, bounding-box, source-object, filename, and
  visual-evidence availability metadata.
- The current verified baseline is 62 passing Python tests and 15 hard-passing Golden cases.

## 1. Decision

Proceed with the Agents SDK migration, but separate it from eval-data architecture.

The migration is justified by clearer model orchestration, tool execution, lifecycle handling, and
request-scoped session mechanics. It is not a reason to build a journal, event-sourcing system, or
online analytical store.

The Agents SDK will own:

- the model/tool loop;
- tool invocation and tool-result continuation;
- request-scoped session transcript mechanics;
- lifecycle hooks;
- model retry policy once MiniMax compatibility is proven.

PaiSmart will continue to own:

- paper authorization and disclosure state;
- selected papers and selected evidence;
- the request-history and research-memory contract;
- evidence identity and citation validation;
- final answer rendering;
- current response, progress, CLI, and harness-run-trace/v2 contracts;
- MiniMax-specific request and response behavior.

A small EvalRecorder will own only:

- appending raw run facts to one per-run JSONL file;
- atomically writing one terminal result file;
- reporting whether capture completed successfully.

The recorder has no read path used by the running harness. Offline programs may later transform the
files into RL trajectories, cache reports, evaluation datasets, provider diagnostics, or any other
view.

Do not build these components in this migration:

- SQLite or another event database;
- a content-addressed blob store;
- exporters or export checkpoints;
- archive/object-store synchronization;
- online projectors;
- an integrity-auditor service;
- RL, cache, or evaluation tables inside the harness;
- a custom SDK tracing processor as the persistence layer.

## 2. Current Behavior That Must Be Preserved

The migration is accepted only if these current properties remain true:

1. harness_py/agent_harness.py runs one continuous model-driven loop with no product-imposed round
   limit.
2. The model must finish through submit_research_answer, not through unvalidated free text.
3. A rejected final submission is returned to the model so it can repair citations or shape.
4. A final submission mixed with another tool call is rejected.
5. Multiple tool calls from one model response execute in model order.
6. ReadingCorpusTools remains stateful per run:
   - candidate or identity tools authorize papers;
   - location search discloses location references;
   - read_locations accepts only disclosed references;
   - read evidence is accumulated by evidence ID.
7. Tool results retain two explicit forms:
   - the complete internal result;
   - the model-visible result after model_facing_payload removes private fields.
8. Only user and assistant text from prior turns is replayed to the next turn.
9. Previously selected evidence is injected separately and is not reconstructed from tool prose.
10. A turn without new citations preserves the previously selected evidence and papers.
11. Greetings and clarification answers can complete without corpus tools.
12. Paper-content answers that used read evidence require valid evidence citations.
13. Existing response fields, harness-run-trace/v2 artifacts, progress events, and CLI output remain
    compatible during migration.
14. Cancellation, timeout, provider failure, malformed tool arguments, and validation failure
    remain distinguishable outcomes.
15. The fifteen committed Golden cases continue to pass the existing BehaviorScorer.
16. service.py keeps both endpoints:
    - POST /v1/research/turn returns one JSON response;
    - POST /v1/research/stream returns application/x-ndjson progress events followed by result or
      error;
    - progress lines retain generationId, sequence, timestamp, and the current camel-case payload
      fields.
17. The Python service remains free of Redis, queues, and Python-owned cross-turn service state.
18. The service continues to load MiniMax from EnvProviderConfigStore.
19. Evidence projections retain original filename, parser name and version, bounding box, table,
    figure, formula, source-object, and screenshot-availability metadata.
20. The HTTP turn has no overall execution deadline. Provider timeout and retry behavior remain
    explicit per model attempt.
21. ResearchHarnessService continues to validate the request and load the authorized corpus before
    invoking the agent runtime.

### 2.1 Baseline Gaps To Close

These are current defects, not behavior to reproduce:

- A successful submit_research_answer returns before it is added to react_trace.
- ChatTurn.raw holds the provider response, but the current run artifact loses it.
- The tool trace stores only model_facing_payload, so complete internal tool results are lost.
- LiveResearchChatHarness converts a partial technical failure into a new run with an empty trace.
- Cancellation is raised as a generic RuntimeError and may be converted into a technical-failure
  answer.
- The NDJSON handler currently passes a cancellation callback that always returns false.
- run_id is derived from case_id, so repeated executions can share an execution identity.
- ConversationState copies complete tool traces into tool_traces.

The SDK migration fixes the orchestration and cancellation defects. EvalRecorder captures raw
model and tool facts directly at their owners so it does not depend on react_trace being complete.

## 3. Non-Goals

- Do not change lexical retrieval, ranking, snippets, authorization, or evidence IDs.
- Do not introduce multi-agent handoffs.
- Do not switch MiniMax from Chat Completions to the Responses API.
- Do not migrate judge.py in the first slice.
- Do not enable provider token streaming in the first slice.
- Do not add Redis, a Python job queue, or Python-owned conversation persistence.
- Do not recreate harness_py/job_runtime.py.
- Do not add a cache yet.
- Do not define RL schemas, reward tables, evaluator packets, or cache-analysis schemas now.
- Do not promise access to hidden chain-of-thought. Preserve only reasoning fields explicitly
  returned by the provider.
- Do not rebuild existing runtime responses, progress events, or artifacts by replaying eval files.
- Do not send MiniMax prompts or tool outputs to the OpenAI trace backend unless separately
  approved.

## 4. Target Ownership Model

| Concern | Authoritative owner |
| --- | --- |
| Request validation, corpus loading, and response compatibility | ResearchHarnessService |
| NDJSON delivery and sequence | service.py stream handler |
| Agent orchestration | AgentsSdkHarnessRuntime |
| Per-run mutable tool state | ResearchRunContext |
| Model-visible transcript assembly | RequestBackedSession |
| Selected papers and evidence | Request-supplied ResearchMemory |
| Authorized corpus scope | request.scope and loaded GoldenDataset |
| Corpus behavior | Existing ReadingCorpusTools |
| Skill definitions | Existing ResearchSkillRegistry |
| Provider request/response behavior | MiniMax Agents Model adapter |
| Existing run artifact and progress payloads | Runtime compatibility builders |
| Passive offline-data capture | EvalRecorder |
| RL, cache, evaluation, and diagnostic datasets | Separate offline programs |

Four categories remain separate:

1. **Request transcript:** upstream-supplied user and assistant history plus current-run SDK items.
2. **Research memory:** selected evidence and papers supplied by the request and returned as the
   next memory projection.
3. **Compatibility outputs:** current response, progress, CLI, and run-artifact shapes required by
   existing callers.
4. **Eval dump:** a passive copy of raw process facts for offline use.

The SDK Session is request-scoped. Persistent cross-turn Sessions are a separate decision because
the current HTTP service is intentionally stateless between turns.

## 5. Proposed Python Module Map

Keep the repository's flat Python package style.

| Planned file | Responsibility |
| --- | --- |
| harness_py/runtime.py | SDK-neutral HarnessRuntime interface and turn input/result types |
| harness_py/agents_runtime.py | Agent construction, Runner configuration, and run lifecycle |
| harness_py/agents_context.py | ResearchRunContext and cancellation state |
| harness_py/agents_tools.py | FunctionTool adapters over current domain tools |
| harness_py/agents_model.py | MiniMax-compatible Agents SDK Model adapter |
| harness_py/errors.py | Shared cancellation and runtime error types |
| harness_py/memory.py | RequestBackedSession and ResearchMemory policy |
| harness_py/eval_recorder.py | Minimal per-run JSONL and terminal-result writer |
| harness_py/tests/test_agents_model.py | MiniMax request, response, retry, and normalization tests |
| harness_py/tests/test_agents_tools.py | Tool schema, ordering, raw/visible result, and state tests |
| harness_py/tests/test_agents_runtime.py | SDK loop, final submission, progress, and memory tests |
| harness_py/tests/test_service.py | JSON/NDJSON endpoint, disconnect, and ordering tests |
| harness_py/tests/test_eval_recorder.py | append, duplicate, partial-run, and atomic-result tests |

Existing domain modules remain authoritative:

- harness_py/tools.py
- harness_py/research_skills.py
- harness_py/provider_config.py
- harness_py/product_db_dataset.py
- harness_py/scoring.py
- harness_py/contracts.py
- harness_py/service.py

### 5.1 Current File Disposition

| Current path | Planned disposition |
| --- | --- |
| harness_py/service.py | Keep both endpoints; add runtime selection and disconnect-aware cancellation |
| harness_py/agent_harness.py | Keep as legacy runtime until cutover; later remove only manual orchestration |
| harness_py/live_chat.py | Depend on HarnessRuntime and stop swallowing cancellation |
| harness_py/conversation.py | Keep compatibility; stop persisting full tool-trace copies |
| harness_py/llm.py | Keep for judge.py and the legacy runtime during migration |
| harness_py/tools.py | Keep as authoritative retrieval and evidence implementation |
| harness_py/product_db_dataset.py | Keep corpus and visual-asset loading unchanged |
| harness_py/provider_config.py | Keep current env and CLI provider selection behavior |
| harness_py/cli.py | Keep commands; add reversible runtime selection |
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
  component "ResearchHarnessService\nJSON + NDJSON" as Service
  component "NDJSON stream handler" as Stream
  interface "HarnessRuntime" as Runtime
  component "AgentsSdkHarnessRuntime" as AgentsRuntime
  component "OpenAI Agents SDK\nAgent + Runner" as SDK
  component "RequestBackedSession" as Session
  component "ResearchRunContext" as Context
  component "FunctionTool adapters" as ToolAdapters
  component "ReadingCorpusTools" as Corpus
  component "ResearchSkillRegistry" as Skills
  component "MiniMax Model adapter" as Model
  component "Compatibility result builder" as ResultBuilder
  component "EvalRecorder\noptional leaf writer" as Recorder
  storage "run_id/events.jsonl\nrun_id/result.json" as EvalFiles
}

cloud "MiniMax Chat Completions" as Provider
database "Product corpus DB" as ProductDB
component "Offline analysis scripts" as Offline

Caller --> Service : history, scope, research_memory
Service --> Runtime
AgentsRuntime ..|> Runtime
Service --> AgentsRuntime
AgentsRuntime --> SDK
AgentsRuntime --> Session
AgentsRuntime --> Context
SDK --> Model
Model --> Provider
SDK --> ToolAdapters
ToolAdapters --> Context
Context --> Corpus
Context --> Skills
Service --> ProductDB : load authorized corpus

AgentsRuntime --> ResultBuilder
ResultBuilder --> Service
AgentsRuntime --> Stream : progress events
Stream --> Caller : NDJSON progress + result
Service --> Caller : JSON result

Service ..> Recorder : run input and terminal result
Model ..> Recorder : raw requests and responses
ToolAdapters ..> Recorder : raw/internal/visible tool data
AgentsRuntime ..> Recorder : validation, cancellation, errors
Recorder --> EvalFiles
Offline --> EvalFiles : read only

note bottom of Recorder
  No runtime component reads from EvalRecorder.
  Capture never drives orchestration or output.
end note
@enduml
~~~

## 7. Runtime Design

### 7.1 Harness Runtime Seam

Introduce one SDK-neutral interface:

~~~text
HarnessRuntime.run_turn(TurnExecutionInput) -> TurnExecutionResult
~~~

TurnExecutionInput contains the dataset, logical request ID, unique execution run ID,
conversation ID, user message, transcript snapshot, research-memory snapshot, progress listener,
cancellation token, runtime options, and an optional EvalRecorder.

TurnExecutionResult contains the accepted research answer, next research-memory projection, usage,
terminal status, run ID, and compatibility run artifact. JSON and NDJSON framing stays in
service.py.

Keep the current implementation behind LegacyHarnessRuntime until cutover. The legacy and SDK
adapters share only the SDK-neutral input and result types.

### 7.2 Research Run Context

Create one ResearchRunContext per execution. It contains:

- logical request ID and unique run ID;
- conversation ID and turn index;
- corpus snapshot metadata and authorized paper IDs;
- one per-run ReadingCorpusTools instance;
- ResearchSkillRegistry;
- prior evidence and selected paper IDs;
- final-submission state;
- ordered model-call and tool-call identifiers;
- progress listener;
- cancellation token;
- optional EvalRecorder reference.

The context is never serialized directly into a model prompt. Only an explicit prompt projection
may become model-visible.

### 7.3 Tool Adapters

Use explicit FunctionTool objects first and reuse the current JSON schemas. Do not silently change
required fields, descriptions, limits, or additionalProperties behavior.

Each invocation:

1. checks cancellation;
2. records one tool-start fact when eval capture is enabled;
3. calls the existing ResearchSkillRegistry or ReadingCorpusTools implementation;
4. captures the complete internal result;
5. derives model_facing_payload;
6. captures the model-visible result and state change in the same completion fact;
7. returns only the model-visible result to the SDK.

Tool calls from one model response execute serially in model order. This preserves the stateful
search, disclosure, and read authorization model.

Expected domain errors remain structured tool results. Unexpected exceptions remain runtime
errors and are not converted into generic tool prose.

### 7.4 Final Submission

Retain submit_research_answer as a real tool.

The submission result contains:

~~~text
accepted
draft
validation_error
tool_call_id
model_call_id
~~~

Record the current model response's tool-call group before execution. The submission tool rejects
a final call that has sibling research calls.

Configure final-output handling so:

- one accepted submit_research_answer ends the run;
- a rejected submission returns validation feedback to the model;
- a mixed final/research response is rejected and continued;
- ordinary research tools continue the loop;
- provider free text cannot bypass final validation.

Preserve the current no-product-round-limit behavior. Any later token, turn, or time budget is a
separate product decision.

### 7.5 Prompt Construction

Preserve current ordering and visibility:

1. agent instructions;
2. prior user and assistant text only;
3. one curated previous-evidence context item;
4. current user message;
5. current-run tool calls and results managed by the SDK.

Use the Session input callback to prevent previous-turn tool calls, tool outputs, and reasoning
items from being replayed. Snapshot the exact outgoing MiniMax request during compatibility tests.
Prompt movement is a behavior change, not a formatting refactor.

### 7.6 MiniMax Model Adapter

First test OpenAIChatCompletionsModel with AsyncOpenAI configured for the MiniMax base URL and key.

The generic adapter is accepted only if it preserves:

- MiniMax-M3 adaptive thinking for research calls;
- temperature, top_p, and maximum completion tokens;
- exact function schemas and required tool choice;
- tool-call IDs and multiple tool calls;
- MiniMax structured-argument normalization using the $text convention;
- raw usage and finish metadata;
- retryable HTTP status behavior;
- provider response IDs and raw response data required by EvalRecorder.

If raw request/response data or another provider contract is hidden, implement the Agents SDK Model
interface in agents_model.py using the current MiniMax request and parse behavior. This remains a
provider adapter, not a tracing framework.

Run only one retry layer. Retain provider-level retries first; adopt SDK retry settings only after
attempt numbering and behavior are covered by tests.

### 7.7 Progress And Compatibility Outputs

Progress and existing artifacts are built directly from live runtime state:

- lifecycle hooks emit the current compact progress vocabulary;
- tool adapters emit current tool progress fields;
- the runtime result builder produces react_trace, evidence ledger, paper candidates, citation
  validation, usage, and diagnostics;
- service.py produces the existing JSON and NDJSON terminal payloads.

Do not read or replay events.jsonl to build any online response. The eval dump may be incomplete or
disabled without changing the model result.

## 8. Memory Design

### 8.1 Migration Rule

Do not change memory authority while changing the runner. The upstream request remains the source
of cross-turn history and research memory. The Python response returns the next research memory.

Use one RequestBackedSession per execution, seeded from request.history. Do not introduce
RedisSession, SQLAlchemySession, OpenAI Conversations, previous_response_id, or another cross-turn
state owner in this migration.

### 8.2 Transcript Session

The request-scoped Session may store full SDK items during the current run, while its input callback
selects model-visible history. It must ensure:

- upstream history is seeded once;
- the current user message appears once;
- retries do not duplicate current-run items;
- previous-turn tool calls and outputs are not replayed;
- service requests do not depend on process-local state from an earlier request;
- CLI reset continues to clear ConversationState.

### 8.3 Research Memory

Model the existing service payload directly:

~~~text
selected_paper_ids
selected_evidence_ids
evidence_items_by_id
~~~

ResearchMemory has no Python persistence interface in this plan. The runtime returns a deterministic
next-memory projection.

Preserve current behavior:

- merge newly read evidence by evidence ID;
- select newly cited evidence when present;
- otherwise retain previous selected evidence and papers;
- never treat candidate cards as citeable evidence;
- store run references in conversation state, not another full trace copy.

## 9. Minimal Eval Capture

### 9.1 Contract

EvalRecorder is deliberately small. It has three operations:

~~~text
open(root, run_id)
append(event)
finish(result)
~~~

When capture is enabled, one execution writes:

~~~text
<EVAL_DUMP_DIR>/<run_id>/events.jsonl
<EVAL_DUMP_DIR>/<run_id>/result.json
~~~

events.jsonl contains ordered process facts. result.json contains the terminal result or terminal
error exactly once. An interrupted process may leave events.jsonl without result.json; offline
readers treat that directory as an incomplete run.

Do not add a database schema, exporter, blob layer, projection framework, or storage abstraction.
If the local file format later becomes insufficient, replace it behind EvalRecorder in a separate
project.

### 9.2 Minimal Event Shape

Each line is one JSON object:

~~~json
{
  "schema_version": "harness-eval-event/v1",
  "event_id": "stable-run-local-event-id",
  "run_id": "unique-execution-id",
  "sequence": 7,
  "kind": "tool.completed",
  "recorded_at": "UTC timestamp",
  "monotonic_ns": 123456789,
  "operation_id": "model-or-tool-call-id",
  "attempt": 1,
  "payload": {}
}
~~~

Keep the envelope small. Request ID, conversation ID, provider settings, code revision, corpus
snapshot, and input memory belong in the initial run.started payload rather than being copied into
every line.

The plan defines capture ownership, not a large taxonomy. The initial kinds are sufficient:

- run.started;
- model.request;
- model.response;
- model.error;
- tool.started;
- tool.completed;
- tool.error;
- answer.validation;
- run.error.

The full terminal response is stored only in result.json, not duplicated in a run.completed event.
Future cache or reward code may append its own plainly named events when that code exists; no
future analytical schema is designed now.

### 9.3 Capture Points

| Fact | Single owner | Stored data |
| --- | --- | --- |
| Run input | AgentsSdkHarnessRuntime | request context, transcript projection, research memory, corpus snapshot, tool schemas, prompt/version metadata |
| Provider request | MiniMax model adapter | exact secret-stripped request body, model settings, logical call ID, attempt |
| Provider response | MiniMax model adapter | exact raw response, normalized content/tool calls, usage, finish metadata, provider reasoning fields if exposed |
| Provider failure | MiniMax model adapter | attempt, error type, status, selected response headers/body, retry decision |
| Tool start | FunctionTool adapter | tool-call ID, model-call ID, raw and parsed arguments |
| Tool completion | FunctionTool adapter | complete internal result, exact model-visible output, authorization/evidence state change, duration |
| Tool failure | FunctionTool adapter | structured domain error or unexpected exception |
| Final validation | submit_research_answer adapter | submitted draft, cited IDs, accepted flag, validation error |
| Terminal result | Runtime/service boundary | existing result payload or terminal error, next research memory, aggregate usage |

Capture each fact at its owner, before another layer can redact, normalize away, or overwrite it.
Do not reconstruct raw provider or tool data from react_trace.

Provider-exposed reasoning is retained. Hidden chain-of-thought is neither available nor required.
Large external corpus assets are referenced by stable evidence/source identifiers and snapshot
metadata; the recorder does not copy document binaries or screenshots into every run.

### 9.4 No Missing Or Duplicate Records

Keep correctness rules simple and testable:

1. Every execution attempt receives a new random run_id, even when request_id or case_id is reused.
2. One EvalRecorder instance and one file handle own one run directory.
3. A per-run lock serializes append calls; different HTTP requests use different recorders.
4. sequence increases once per successfully appended line.
5. event_id is stable for one logical boundary, using run_id, operation_id, kind, and attempt.
6. The recorder keeps a small in-memory set of written event IDs. A repeated event ID is skipped
   and reported as a capture defect rather than written twice.
7. The event ID enters the written set only after the complete newline-terminated record is flushed.
8. Model and tool adapters use try/except/finally so every started operation produces either a
   completion or an error record during normal process execution.
9. Each complete line is flushed immediately. The recorder calls fsync at run start, model/tool
   boundaries, and terminal finalization.
10. result.json is written to a temporary file, flushed, and atomically renamed.
11. The same run_id is never reopened after process restart; a retry is a new execution directory.
12. Offline readers ignore a non-newline-terminated final fragment after a hard crash.

Do not deduplicate by payload hash. Two identical model requests or tool results can be two real
attempts. Duplicate prevention means one record per logical occurrence, not one copy per unique
payload value.

Do not truncate, sample, or summarize captured JSON in the recorder. Repetition compresses well
offline and is preferable to losing information in the live harness.

### 9.5 Failure And Security Policy

- Capture is disabled when EVAL_DUMP_DIR is unset.
- EvalRecorder never changes the model decision, tool result, progress stream, or API response.
- A write failure marks capture as failed, emits a structured local error, and stops further writes
  for that run rather than repeatedly disturbing orchestration.
- An explicit eval-data collection command treats capture failure or a missing result.json as a
  failed collection run and exits nonzero.
- The HTTP service may still return the computed answer; it must not report a complete eval capture
  when capture failed.
- Authorization headers, API keys, internal tokens, and database credentials are stripped before
  serialization.
- Run directories and files use owner-only permissions by default.
- Retention, encryption at rest, compression, upload, Parquet conversion, reward joining, and data
  governance remain offline or operational concerns.

This policy keeps runtime behavior independent while making missing capture detectable. The system
does not claim exactly-once persistence across a machine or disk failure; it guarantees one
detectable local record per completed capture boundary under normal process execution and preserves
all completed lines after a process crash.

## 10. SDK Tracing Policy

Do not use SDK tracing as the eval database.

- Do not implement JournalTracingProcessor.
- Do not make SDK trace callbacks a second semantic event stream.
- Explicitly disable the default remote trace export for the MiniMax runtime unless separate
  approval is given.
- Run hooks remain available for cancellation and current progress events.
- SDK trace/span identifiers may be included as optional correlation fields in EvalRecorder events,
  but the recorder does not depend on them.

This avoids double capture between hooks, tracing processors, model adapters, and tool adapters.

## 11. End-To-End Turn Sequence

~~~plantuml
@startuml
title One Agents SDK Research Turn With Passive Eval Capture
autonumber

actor Caller
participant ResearchHarnessService as Service
participant "NDJSON progress sink" as Stream
participant AgentsSdkHarnessRuntime as Runtime
participant EvalRecorder as Recorder
participant RequestBackedSession as Session
participant "Agents SDK Runner" as Runner
participant MiniMaxModel as Model
participant "MiniMax API" as Provider
participant FunctionToolAdapter as Tool
participant ReadingCorpusTools as Corpus

Caller -> Service : request
Service -> Service : validate and load authorized corpus
Service -> Recorder : open(run_id), run.started
Service -> Runtime : run_turn(input, optional recorder)
Runtime -> Session : seed request history once
Runtime -> Runner : run agent
Runtime -> Stream : job/model progress
Stream --> Caller : ordered NDJSON

loop Until accepted final submission
  Runner -> Model : model call
  Model -> Recorder : model.request
  Model -> Provider : chat completion

  alt Provider succeeds
    Provider --> Model : raw response
    Model -> Recorder : model.response
    Model --> Runner : normalized response
  else Provider fails
    Provider --> Model : error
    Model -> Recorder : model.error
  end

  opt Tool calls
    loop Serially in model order
      Runner -> Tool : invoke
      Tool -> Recorder : tool.started
      Tool -> Corpus : existing domain call
      Corpus --> Tool : internal result and state change
      Tool -> Recorder : tool.completed or tool.error
      Tool --> Runner : model-visible result
      Tool -> Stream : tool progress
    end
  end

  opt Final submission
    Tool -> Recorder : answer.validation
  end
end

Runner --> Runtime : accepted draft
Runtime -> Runtime : build compatibility artifact and next memory
Runtime --> Service : TurnExecutionResult
Service -> Recorder : finish(result.json via atomic rename)
Service --> Caller : unchanged JSON or NDJSON result

note over Recorder
  If the process crashes, completed JSONL lines remain.
  Missing result.json marks the run incomplete offline.
end note
@enduml
~~~

## 12. Implementation Sequence

### Task 0: Freeze The Baseline

**Purpose:** Make behavioral drift visible before adding the SDK.

**Actions:**

- Record Python version, provider settings, prompt hash, tool-schema hash, and Git revision.
- Record the current audited baseline: 62 Python tests and 15 Golden hard passes.
- Add deterministic service contract coverage for both JSON and NDJSON endpoints.
- Capture deterministic transcripts for greeting, exact-fact research, invalid-citation repair,
  prior-evidence reuse, multiple tool calls, and partial technical failure.
- Record current response, evidence metadata, progress transcript, and harness-run-trace/v2
  examples.

**Gate:** Existing failures are separated from SDK regressions.

### Task 1: Pin Dependencies And Run A MiniMax Compatibility Spike

**Planned files:**

- Create harness_py/requirements.in.
- Create harness_py/requirements.lock.
- Create harness_py/tests/test_agents_model.py.

**Actions:**

- Pin openai-agents 0.18.2 and its resolved dependencies.
- Test the generic Chat Completions model against the local HTTP fixture.
- Verify request body, tools, required tool choice, thinking, usage, raw response availability,
  errors, retries, and $text normalization.
- Verify the synchronous runner can be called from the current ThreadingHTTPServer request thread.
- Keep provider calls non-streamed; NDJSON remains a lifecycle-event stream.

**Gate:** Select the generic model only if all MiniMax contracts are preserved; otherwise build the
custom adapter.

### Task 2: Introduce HarnessRuntime Without Changing Behavior

**Planned files:**

- Create harness_py/runtime.py.
- Modify harness_py/live_chat.py.
- Modify harness_py/service.py.
- Modify harness_py/cli.py.
- Create harness_py/tests/test_service.py.

**Actions:**

- Define TurnExecutionInput and TurnExecutionResult.
- Wrap ResearchAgentHarness as LegacyHarnessRuntime.
- Make service, live chat, Golden runs, and CLI depend on HarnessRuntime.
- Add legacy and agents_sdk runtime selection, with legacy as the initial default.
- Give each execution a unique run_id independent of case_id.

**Gate:** Existing deterministic tests remain compatible.

### Task 3: Port Context And Tools

**Planned files:**

- Create harness_py/agents_context.py.
- Create harness_py/agents_tools.py.
- Create harness_py/tests/test_agents_tools.py.

**Actions:**

- Instantiate one ResearchRunContext and ReadingCorpusTools per execution.
- Wrap current schemas with explicit FunctionTool objects.
- Preserve complete internal and model-visible results.
- Preserve evidence provenance and visual-asset metadata.
- Force serial tool execution.
- Preserve structured domain errors.
- Add cancellation checks before and after every tool boundary.

**Gate:** Scripted calls produce the same ordered results and corpus state.

### Task 4: Port The Agent Loop And Final Submission

**Planned files:**

- Create harness_py/agents_runtime.py.
- Create harness_py/errors.py.
- Create harness_py/tests/test_agents_runtime.py.

**Actions:**

- Move the current system prompt without semantic rewriting.
- Build the same history and prior-evidence input.
- Preserve no product-imposed round limit.
- Implement submit_research_answer validation and final-output handling.
- Reject mixed final and research calls.
- Prove invalid citations return to the model for repair.
- Prevent free-text final bypass.
- Let cancellation pass through LiveResearchChatHarness without becoming a technical-failure answer.
- Make broken NDJSON connections set the cancellation token.
- Build existing progress and run artifacts directly from live runtime state.

**Gate:** Synthetic ReAct and final-answer tests pass through the SDK runtime.

### Task 5: Adopt Sessions Without Changing Memory Authority

**Planned files:**

- Create harness_py/memory.py.
- Modify harness_py/conversation.py through an adapter.

**Actions:**

- Implement RequestBackedSession.
- Replay only user and assistant text across turns.
- Inject previous selected evidence once.
- Keep ResearchMemory separate from transcript items.
- Preserve service response and CLI conversation behavior.
- Remove complete tool-trace copies from ConversationState after compatibility readers migrate.

**Gate:** Multi-turn tests show no prompt duplication, hidden process-local state, or evidence loss.

### Task 6: Add Minimal EvalRecorder

**Planned files:**

- Create harness_py/eval_recorder.py.
- Create harness_py/tests/test_eval_recorder.py.
- Modify agents_model.py, agents_tools.py, agents_runtime.py, and service.py only at their natural
  capture boundaries.

**Actions:**

- Enable capture only through EVAL_DUMP_DIR or an explicit CLI/eval option.
- Create one exclusive directory and JSONL writer per run.
- Append run, model, tool, validation, and error facts at their single owners.
- Store the complete terminal result once through atomic result.json replacement.
- Use stable event IDs, an in-memory duplicate guard, immediate flushes, and boundary fsync.
- Strip credentials and retain full non-secret JSON without sampling.
- Disable default remote SDK trace export.
- Keep response, progress, memory, and artifact builders independent of the recorder.
- Make eval collection fail visibly when capture is incomplete.

**Gate:** Forced failures leave readable completed lines, successful runs have one result.json,
duplicate event IDs are not written twice, secrets are absent, and enabling capture does not change
the runtime result.

### Task 7: Compare Runtimes And Cut Over

**Actions:**

- Compare legacy and SDK runtimes against deterministic recorded model responses.
- Run all fifteen Golden cases through the SDK runtime.
- Run selected live MiniMax smokes for direct, exact-fact, comparison, recommendation, and
  prior-evidence reuse cases.
- Run both service endpoints and verify progress plus terminal payloads.
- Force a client disconnect and verify cancellation.
- Run with eval capture enabled and inspect complete, failed, cancelled, and interrupted directories.
- Enable the SDK runtime behind a reversible configuration flag.
- Keep legacy rollback through an agreed stability window.

**Gate:** No unexplained hard-score regression, memory duplication, ordering regression, response
contract change, duplicate eval event, or undetected incomplete capture.

### Task 8: Remove Superseded Orchestration

Remove only after the stability gate:

- the hand-written while loop;
- manual assistant/tool message continuation;
- duplicate progress instrumentation;
- final run-only trace accumulation that is no longer required;
- full trace copies in conversation state.

Retain:

- ReadingCorpusTools;
- ResearchSkillRegistry;
- citation validation and rendering;
- Golden data and BehaviorScorer;
- legacy ChatModel paths still used by judge.py until separately migrated.

## 13. Verification Matrix

| Requirement | Authoritative proof |
| --- | --- |
| Provider compatibility | Local HTTP contract tests inspect exact request and response handling |
| Tool schema parity | Snapshot comparison of legacy and SDK tool definitions |
| Sequential stateful tools | Multi-call test where one tool authorizes the next |
| Final submission only | Mixed-call, invalid-citation, and text-only response tests |
| Conversation parity | Existing multi-turn, greeting, reset, and evidence-reuse tests |
| Stateless service memory | Repeated HTTP requests expose only supplied history and memory |
| NDJSON compatibility | Endpoint tests assert content type, ordering, sequence, result, and error |
| Disconnect handling | Broken-pipe test proves cancellation at the next safe boundary |
| Evidence metadata parity | Tests preserve parser, bbox, source ID, filename, and asset flags |
| Unique execution identity | Repeated case/request IDs still create different run directories |
| No missing normal-boundary data | Fault injection after model/tool start produces completion or error |
| No duplicate eval events | Repeated append with one event ID writes one line |
| Partial-run readability | Abrupt stop leaves prior complete lines readable and no result.json |
| Atomic terminal result | Readers never observe a partially written result.json |
| Cross-request isolation | Interleaved requests write only to their own run directories |
| Secret stripping | API keys, auth headers, tokens, and credentials never appear in dump files |
| Runtime independence | Capture enabled/disabled produces the same model result and progress |
| Research behavior | Fifteen Golden cases have zero hard failures |
| Rollback | Runtime flag returns to legacy without eval-data migration |

## 14. Required Verification Commands

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
  harness_py.tests.test_eval_recorder -v

python3 -m harness_py validate
python3 -m harness_py audit
python3 -m py_compile harness_py/*.py harness_py/tests/*.py
~~~

Live MiniMax smokes run only after deterministic tests pass. They are provider-compatibility
evidence, not replacements for deterministic acceptance tests.

## 15. Rollback Strategy

- Preserve LegacyHarnessRuntime through the stability window.
- Keep external request, response, memory, progress, and artifact versions unchanged during cutover.
- Eval files are runtime-independent and require no rollback migration.
- A failed SDK request may be rerun through legacy only when the request is side-effect safe; the
  second execution receives a new run_id.
- Never overwrite or reopen the failed SDK run directory.
- RequestBackedSession is reconstructed from every request, so rollback does not migrate
  Python-owned conversation state.

## 16. Principal Risks And Mitigations

| Risk | Mitigation |
| --- | --- |
| MiniMax differs from OpenAI semantics | Compatibility spike and custom Model adapter |
| SDK executes stateful tools concurrently | Force one-at-a-time execution |
| SDK accepts unvalidated text | Required final tool plus explicit tests |
| Session replays old tool output | Request-scoped Session input filtering |
| Cancellation becomes technical failure | Shared cancellation type and narrow exception handling |
| Broken client keeps expensive work running | Disconnect-aware cancellation token |
| Generic SDK adapter hides raw provider data | Custom MiniMax Model adapter |
| Capture logic spreads through orchestration | One optional EvalRecorder and single-owner call sites |
| Hooks and adapters double-record facts | No custom tracing processor; explicit capture ownership |
| Capture blocks or changes answers | Leaf-writer policy and result-parity tests |
| Process crash leaves partial output | JSONL completed-line recovery and absent result marker |
| Same request overwrites prior data | Random run ID and exclusive directory creation |
| Sensitive data is exported remotely | Disable default remote tracing and strip credentials locally |
| SDK upgrade changes behavior | Pin version and rerun contract tests |

## 17. Completion Criteria

The migration is complete only when:

1. Production Python orchestration runs through AgentsSdkHarnessRuntime.
2. All current Python tests pass.
3. The fifteen committed Golden cases have zero hard failures.
4. MiniMax contract tests prove required request, response, tool, thinking, and retry behavior.
5. Tool calls remain sequential and authorization-safe.
6. Final answers cannot bypass citation validation.
7. RequestBackedSession and ResearchMemory remain separate and create no hidden cross-turn state.
8. JSON, NDJSON, CLI, memory, progress, evidence, and run-artifact contracts remain compatible or
   are deliberately versioned.
9. Cancellation and technical failure are no longer conflated.
10. Every enabled eval run has one unique directory.
11. A completed eval run has readable events.jsonl and exactly one atomic result.json.
12. A crashed or interrupted run preserves all completed JSONL records and is detectable by the
    missing result.json.
13. No run contains duplicate event IDs.
14. Raw provider responses, complete internal tool results, model-visible tool results, validation,
    errors, retries, and terminal output are available for offline processing.
15. Eval capture is never read to drive orchestration, progress, memory, or online responses.
16. No SQLite journal, blob store, exporter, projector, online RL/cache schema, or audit service is
    introduced.
17. The legacy runtime passes the rollback window and can be removed without losing a required
    caller or judge path.

## 18. Official SDK References

- Sessions: https://openai.github.io/openai-agents-python/sessions/
- Running agents: https://openai.github.io/openai-agents-python/running_agents/
- Agent tool-use behavior: https://openai.github.io/openai-agents-python/agents/
- Function tools and ToolContext: https://openai.github.io/openai-agents-python/tools/
- Local run context: https://openai.github.io/openai-agents-python/context/
- Tracing configuration: https://openai.github.io/openai-agents-python/tracing/
- Models and non-OpenAI providers: https://openai.github.io/openai-agents-python/models/
- SDK release v0.18.2: https://github.com/openai/openai-agents-python/releases/tag/v0.18.2
