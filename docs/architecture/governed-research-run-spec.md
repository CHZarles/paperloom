# Governed Research Run Specification

Status: Implemented in code; production promotion requires the benchmark gate in section 11.

## 1. Decision

PaperLoom will keep the current evidence and authorization protocol, but every live research run
will become a bounded and classifiable operation.

```text
Java ChatHandler
  -> ProductReadingConversationService
  -> PythonResearchHarnessClient (HTTP NDJSON, selected production path)
  -> ResearchHarnessService
  -> Agents SDK Runner
  -> Java Corpus API
```

The Redis worker remains a supported asynchronous transport. It must report the same terminal
contract, but this work does not migrate the selected HTTP path to Redis.

The governing rule is:

```text
The model may choose research actions.
The product, not the model, decides authorization, recoverability, resource limits, terminal state,
and what is recorded as a failure.
```

This specification replaces no evidence rule. The following chain remains mandatory:

```text
Java-authorized scope
  -> disclosed paper
  -> disclosed location
  -> exact read
  -> source quote
  -> accepted final submission
```

## 2. Problem Statement

The current path has a correct evidence boundary but an incomplete execution-control boundary.

| Current behavior | Consequence |
| --- | --- |
| `ReadingCorpusTools` delegates several model-controlled arguments directly to Java. Java returns an HTTP 400 for an invalid scoped paper ID. | A recoverable agent mistake can escape as a `RuntimeError`, then become `FAILED_TECHNICAL`. |
| `LiveResearchChatHarness` catches all ordinary exceptions as `FAILED_TECHNICAL`. | Agent input, protocol incompatibility, dependency failure, and programming bugs lose their distinct meanings. |
| The Runner has a 12-turn cap, but the request quota reserves one prompt plus one completion. | A multi-turn run has no pre-reserved cumulative token ceiling. |
| The HTTP stream has per-request provider timeouts but no Run deadline. | The duration of a whole research turn is not bounded by one policy. |
| The model Session replays tool output and rejected submissions. | A fixed number of turns does not by itself bound prompt growth or cumulative input cost tightly enough. |
| Product diagnostics contain a generic failure result and the frontend has only `STREAMING`, `COMPLETED`, `FAILED`, and `CANCELLED`. | A controlled limit stop cannot be distinguished from an infrastructure failure. |

The desired system makes the following statement true for every completed or terminal run:

```text
There is one terminal disposition, one stable reason code, one measured resource snapshot,
and an audit trail showing the last completed model/tool boundary.
```

## 3. Goals And Non-goals

### Goals

1. Preserve fixed scope, exact-read evidence, citation validation, and historical reference reopening.
2. Make every model-controlled tool error either a structured correction delivered to the same Runner or a documented terminal condition.
3. Bound model calls, total LLM token exposure, wall-clock duration, model-visible context, and model-visible tool payloads.
4. Make the Java quota decision match the maximum resource use that Python is allowed to incur.
5. Make HTTP and Redis transports produce the same run result, progress semantics, and terminal diagnostics.
6. Preserve enough telemetry to explain every controlled termination without exposing internal credentials, raw provider payloads, or authorization details to the user.

### Non-goals

1. Do not weaken or persist Python request-local disclosure state.
2. Do not add a planner, a second agent, provider-specific recovery prompts, silent retrieval fallback, or a new message broker.
3. Do not implement LLM-generated context summaries. The first version uses deterministic projections only.
4. Do not let a user or browser choose resource limits. Java resolves the policy server-side.
5. Do not convert a resource limit into an uncited answer or an automatic retry.

## 4. Terms And Invariants

### 4.1 Run disposition

`ExecutionStatus` gains one value:

| Status | Meaning | Answer requirement |
| --- | --- | --- |
| `COMPLETED` | The model submitted an accepted answer. | `outcome=answered` or another valid accepted outcome. |
| `NEEDS_CLARIFICATION` | The model submitted a valid blocking clarification. | `outcome=needs_clarification`. |
| `INCOMPLETE_PRECISE` | The model submitted a supported partial answer or abstention. | `outcome=partial` or `abstained`. |
| `LIMITED` | Product policy stopped the Run before an accepted submission. | Deterministic `outcome=abstained`; no citations and no factual paper claim. |
| `FAILED_TECHNICAL` | The system cannot continue safely. | No research outcome and no answer claim. |
| `CANCELLED` | The caller or transport cancelled the Run. | No research outcome and no answer claim. |

`LIMITED` is a normal, persisted product result, not a transport failure. Its deterministic user
message is:

```text
This research request reached its execution limit before a verifiable answer was ready. Narrow the question or start a new turn.
```

The frontend may localize this text. It must not present it as a completed research answer and must
not call it an internal service error.

### 4.2 Tool disposition

Every non-final tool invocation has exactly one disposition:

| Tool disposition | Meaning | Runner behavior |
| --- | --- | --- |
| `SUCCESS` | The call completed, including an empty but valid result. | Continue. |
| `RECOVERABLE_AGENT_ERROR` | Model arguments, prerequisite state, ambiguity, or a rejected submission can be corrected without changing the product state. | Return a structured tool result and continue. |
| `TERMINAL_SYSTEM_ERROR` | Service authentication, unavailable dependency, cancellation, contract violation, deadline, or resource limit prevents a safe continuation. | Stop under the mapped Run disposition. |

An empty search is `SUCCESS`, not an error. A lack of evidence remains a model decision to submit a
partial answer or abstention. This distinction is mandatory.

### 4.3 Security invariants

1. Java remains the authority for user access and current Reading Model validity.
2. Python preflight checks do not replace Java scope enforcement; they avoid sending ordinary agent errors to Java.
3. A recoverable error never disclose a paper, a location, a source quote, access rules, or an unredacted backend exception.
4. A `LIMITED`, `FAILED_TECHNICAL`, or `CANCELLED` run must not promote request-local evidence into durable conversation memory.
5. Exact source content remains in the request-local evidence ledger. Any bounded model projection is not a new evidence source.

## 5. Run Policy Contract

### 5.1 Ownership

Java owns policy selection and quota reservation. Python owns policy enforcement at model and tool
boundaries. Both sides record the resolved policy and actual use.

Java creates one immutable `RunLimits` value for each generation. It derives it from server-side
configuration and the selected model context, then includes it in the existing Harness request.
The browser never supplies this object.

### 5.2 Wire format

Run limits govern execution rounds, elapsed time, and payload size. They do not preflight token
usage or set a provider output-token cap.

```json
{
  "options": {
    "include_trace": true,
    "run_limits": {
      "schema_version": "paperloom-run-limits/v1",
      "max_model_calls": 12,
      "max_wall_clock_ms": 600000,
      "max_model_visible_tool_chars": 16000,
      "max_history_chars": 32000
    }
  }
}
```

Initial values are rollout defaults, not user-facing guarantees. A change to any default requires a
benchmark report containing quality, latency, cumulative token use, `LIMITED` rate, and technical
failure rate. The values are deliberately server constants in the first implementation; do not add
per-user tuning controls.

### 5.3 Meaning Of Each Limit

| Field | Enforcement point | Terminal code |
| --- | --- | --- |
| `max_model_calls` | Before another SDK model call starts. | `RUN_MODEL_CALL_LIMIT` |
| `max_wall_clock_ms` | Before/after every model and Corpus operation; also wraps the whole Runner. | `RUN_DEADLINE_EXCEEDED` |
| `max_model_visible_tool_chars` | When projecting a successful tool result for the model. | `RUN_CONTEXT_BUDGET_EXHAUSTED` only when no legal projection fits |
| `max_history_chars` | When building request-local Session history. | `RUN_CONTEXT_BUDGET_EXHAUSTED` only when mandatory current inputs cannot fit |

Before a run, Java atomically reserves one LLM token solely to reject users with no remaining
balance. It settles the provider-reported actual usage once the run ends. Python neither estimates
request tokens nor passes `max_tokens` to the provider.

### 5.4 Deadline And Cancellation

`deadline_monotonic` is calculated once at `ResearchHarnessService.run_job` entry and placed in
`TurnExecutionInput`. It is not recalculated on a retry or a new SDK epoch.

The following operations must check the same control object before beginning and after completing:

- SDK `on_llm_start` and `on_llm_end`;
- provider adapter before sending an HTTP request;
- every `ReadingCorpusTools` call;
- `JavaCorpusGatewayReader._post` before and after Java I/O;
- final submission validation;
- HTTP stream and Redis worker terminal publication.

The provider HTTP timeout is `min(provider_timeout, remaining_deadline)`. Java Corpus HTTP timeout
is `min(corpus_timeout, remaining_deadline)`. The outer async Runner is additionally wrapped by the
remaining deadline. A timeout can therefore end a blocked operation, rather than merely being
noticed before the next operation.

`HarnessCancelled` maps only to `CANCELLED`. A client disconnect, WebSocket stop, Redis cancel key,
or Java `Future` cancellation must reach the same control object. A worker lock heartbeat is not a
Run deadline and must not be used as one.

### 5.5 RunControl API

`RunControl` is request-local and is the only mutable owner of the execution counters. It is created
before the Harness starts and is never reconstructed from a prompt or a progress event.

```python
@dataclass
class RunControl:
    limits: RunLimits
    started_monotonic: float
    deadline_monotonic: float
    model_calls_started: int = 0
    prompt_tokens: int = 0
    completion_tokens: int = 0
    total_tokens: int = 0
    terminal_reason: str | None = None
    last_completed_boundary: dict[str, str] | None = None

    def before_model_call(self, provider_request: JsonMap) -> int: ...
    def record_model_usage(self, usage: Usage) -> None: ...
    def before_tool_call(self, tool_name: str) -> None: ...
    def after_boundary(self, kind: str, operation_id: str) -> None: ...
    def check_cancelled_or_expired(self) -> None: ...
```

`before_model_call` either returns the clamped provider output limit or raises a typed controlled
termination. `record_model_usage` is called exactly once for every provider response, including a
response later rejected by final-answer validation. `after_boundary` is called only after a completed
model call, tool call, or final validation; it is the source of `last_completed_boundary`.

`ResearchRunContext` may expose these counters for trace construction, but it must not maintain a
second independent token or deadline calculation. `MAX_AGENT_TURNS` is removed as a standalone
policy constant and becomes the resolved `RunLimits.max_model_calls` value.

## 6. Error Contract

### 6.1 Stable reason codes

The following codes are the v1 public internal contract. They appear in model-visible recoverable
tool results, progress events, diagnostics, and server logs. User-facing text is mapped in Java or
the frontend and must not expose raw exception messages.

| Code | Disposition | Model-visible action |
| --- | --- | --- |
| `TOOL_ARGUMENTS_INVALID` | Recoverable | Correct the current tool arguments. |
| `PAPER_ID_NOT_DISCLOSED` | Recoverable | Use `search_paper_candidates` or `find_papers_by_identity`. |
| `LOCATION_NOT_DISCLOSED` | Recoverable | Search or inspect a disclosed paper first. |
| `IDENTITY_AMBIGUOUS` | Recoverable | Ask a narrow clarification or use a more specific identity hint. |
| `QUERY_HAS_NO_MATCHES` | Success | Reformulate, answer partially, or abstain. |
| `FINAL_ANSWER_REJECTED` | Recoverable | Correct exactly the validation message. |
| `PROVIDER_TIMEOUT` | Terminal system error | None. |
| `PROVIDER_UNAVAILABLE` | Terminal system error | None. |
| `PROVIDER_PROTOCOL_INVALID` | Terminal system error | None. |
| `CORPUS_UNAVAILABLE` | Terminal system error | None. |
| `CORPUS_AUTHENTICATION_FAILED` | Terminal system error | None. |
| `CORPUS_CONTRACT_VIOLATION` | Terminal system error | None. |
| `RUN_MODEL_CALL_LIMIT` | Controlled limit | None. |
| `RUN_TOKEN_BUDGET_EXHAUSTED` | Controlled limit | None. |
| `RUN_TOKEN_BUDGET_OVERSHOOT` | Controlled limit | None. |
| `RUN_CONTEXT_BUDGET_EXHAUSTED` | Controlled limit | None. |
| `SOURCE_UNIT_EXCEEDS_MODEL_BUDGET` | Recoverable | Choose another location or answer partially. |
| `RUN_DEADLINE_EXCEEDED` | Controlled limit | None. |
| `RUN_CANCELLED` | Cancelled | None. |
| `INTERNAL_UNEXPECTED` | Terminal system error | None. |

`paper_not_authorized_for_reading` is retained as a compatibility alias in the first release, but
the canonical error code is `PAPER_ID_NOT_DISCLOSED`. It describes request-local disclosure state,
not whether the user owns a paper.

### 6.2 Model-visible recoverable result

Existing success payloads remain tool-specific. Error payloads gain a stable envelope while retaining
the legacy `error` string until all prompt and test consumers move to `error_code`.

```json
{
  "error": "paper_not_authorized_for_reading",
  "error_code": "PAPER_ID_NOT_DISCLOSED",
  "recoverable": true,
  "next_action": "find_papers_by_identity",
  "unauthorized_paper_ids": ["2606.07649v2.pdf"],
  "locations": []
}
```

No `message` field in this envelope may contain a Java exception, scope membership, internal token,
or SQL/Qdrant detail. The tool description and `next_action` are sufficient for the model to repair
the call.

### 6.3 Local Corpus preflight

All model-controlled Corpus arguments must be validated before Java I/O. The checks must be factored
into shared pure helpers so production `ReadingCorpusTools` and `InMemoryTools` do not drift.

The shared checks cover at least:

- required IDs and empty strings;
- duplicate and maximum paper/location reference counts;
- disclosed-paper and disclosed-location prerequisites;
- enum membership for element and structure types;
- integer/range constraints including `page_from <= page_to`;
- mutual requirements such as a section reference belonging to an already disclosed paper;
- identity ambiguity without implicit authorization.

After this preflight, an HTTP 400 from Java is a `CORPUS_CONTRACT_VIOLATION`, not a model correction.
The gateway must expose a typed `CorpusGatewayError(status_code, error_code, safe_message)` instead
of encoding an HTTP response inside `RuntimeError` text. Java continues to enforce scope as the
security backstop.

### 6.4 Provider adaptation

The model adapter has only two compatibility responsibilities:

1. A text-only response is synthesized into `submit_research_answer(outcome, markdown)` and passes
   the ordinary final-answer validation.
2. A malformed or truncated function-call argument is converted into the repair continuation tool.

It must not convert a valid text answer into a normal continuation step. It must not convert a
provider transport or protocol exception into a tool call.

## 7. Context And Projection Contract

The request-local Corpus state and complete evidence remain authoritative. Model-visible output is a
bounded projection, separate from the UI progress projection and offline Eval capture.

### 7.1 Required behavior

1. Keep the complete internal tool result in the request-local trace/ledger for final validation and audit.
2. Project successful metadata and location results deterministically to at most the configured
   character budget.
3. Never truncate an atomic table, figure, formula, or cited source span into text that could change
   its meaning. If a single exact evidence unit exceeds the budget, return the recoverable
   `SOURCE_UNIT_EXCEEDS_MODEL_BUDGET` result with its location metadata and no partial text. The
   model must choose another location or submit a partial/abstained answer; v1 does not add a
   substring-reading tool merely to satisfy a context budget.
4. Deduplicate repeated candidates, repeated reads, obsolete repair errors, and older rejected final
   drafts before they become later model input.
5. Keep the current user question, active validation error, selected exact evidence, and current
   authorization state mandatory when trimming context.

`model_facing_payload` is the initial projection point. `progress_output` remains an independent UI
projection. Neither projection authorizes evidence or replaces the full exact-read result.

Projection order is deterministic:

1. Preserve the current user question in full. Reject an HTTP request whose question alone exceeds
   the server request size limit before a generation is created.
2. Keep the most recent complete user/assistant message pairs until `max_history_chars` is reached.
   Do not slice a historic message midway; omit the next whole pair instead.
3. Preserve the latest active final-validation error and the current set of cited/selected evidence
   cards before optional candidate previews.
4. For candidates and locations, retain original result order and project only identity, title,
   navigation metadata, and the bounded preview. Never rerank while projecting.
5. For exact reads, include whole source-quote units in read order until the call budget is full.
   The full units remain in request-local state even when omitted from the model projection.
6. Remove duplicate IDs and superseded repair errors before Session replay.

The projection function returns both the model payload and its serialized byte count. The provider
adapter is still the final authority for the full provider-request upper bound because only it sees
the tool schemas and provider-specific wire representation.

An oversized exact-read unit may remain in an operator-only audit record, but it must not enter
`observations_by_evidence_id`, the citeable evidence ledger, or cross-turn memory unless its whole
source text was returned to the model. Final-answer validation therefore cannot accept a citation to
evidence the model did not receive.

This specification does not yet introduce multi-epoch context compression. If measured runs cannot
meet the hard context budget with deterministic projection, the existing context-management proposal
may be implemented as a separate decision. It must consume this RunLimits and error contract rather
than introduce another one.

## 8. Result, Progress, And Product Mapping

### 8.1 Harness result

Every `run` gains a `control` object. Existing fields remain during rollout.

```json
{
  "status": "LIMITED",
  "result_status": "LIMITED",
  "control": {
    "reason_code": "RUN_TOKEN_BUDGET_EXHAUSTED",
    "terminal_disposition": "LIMITED",
    "limits": {"schema_version": "paperloom-run-limits/v1"},
    "usage": {
      "model_calls": 7,
      "prompt_tokens": 41200,
      "completion_tokens": 8200,
      "total_tokens": 49400,
      "elapsed_ms": 143821
    },
    "last_completed_boundary": {
      "kind": "tool_completed",
      "operation_id": "call_read_7"
    }
  }
}
```

For `FAILED_TECHNICAL`, `reason_code` is one of the terminal system codes. Diagnostics may include a
redacted detail for operators. The user-visible answer must contain no raw stack trace.

### 8.2 Progress events

All transports publish the same semantic events:

```text
job_started
model_call_started
model_call_completed
tool_started
tool_completed
run_limited
job_completed
job_failed
job_cancelled
```

`tool_completed` includes `status=success|recoverable_error`. `run_limited`, `job_failed`, and
`job_cancelled` include `reasonCode`, cumulative usage, and elapsed time. `answer_completed` is
replaced by `job_completed` for successful and limited result publication; an HTTP/Redis adapter may
temporarily emit both names for frontend compatibility.

Progress event order is monotonic by `sequence`. A terminal event is emitted exactly once and no
`tool_started` or `model_call_started` event may follow it.

### 8.3 Java and frontend mapping

Add `LIMITED` to Python `ExecutionStatus`, Java `ProductResultStatus`, and `ProductStopReason` with
specific stop reasons:

```text
MAX_MODEL_CALLS
TOKEN_BUDGET_EXHAUSTED
CONTEXT_BUDGET_EXHAUSTED
DEADLINE_EXCEEDED
```

`ChatHandler.finishReadingHarness` must persist a limited result and its deterministic abstention
message. It must throw only for `FAILED_TECHNICAL`. The existing generation transport state remains:

```text
STREAMING -> COMPLETED   (COMPLETED, NEEDS_CLARIFICATION, INCOMPLETE_PRECISE, LIMITED)
STREAMING -> FAILED      (FAILED_TECHNICAL)
STREAMING -> CANCELLED   (CANCELLED)
```

`GenerationSnapshot.diagnostics` gains `resultStatus`, `stopReason`, `reasonCode`, and `usage`.
No database migration is required: generation state and diagnostics are already Redis payloads, and
the normal limited assistant response is persisted as a conversation record.

The frontend renders a `LIMITED` result as a finished assistant message with a limit notice and a
normal new-turn affordance. It renders `FAILED` as an error and `CANCELLED` as cancelled. It does not
derive completion from the presence of `answer_completed`; it consumes the terminal status and
reason code.

`LiveResearchChatHarness` must catch typed controlled termination and cancellation before its broad
`Exception` handler. The broad handler is reserved for `INTERNAL_UNEXPECTED` and explicitly mapped
terminal dependency/contract errors. It must record the error class and reason code before it creates
the failed Run.

## 9. Implementation Plan

### Phase 1: Contract and shared primitives

1. Add `RunLimits`, `RunControl`, `RunLimitExceeded`, `CorpusGatewayError`, and the reason-code
   enum to `harness_py`.
2. Add `LIMITED` and `CANCELLED` validation to `harness_py/utils/status.py` and result builders.
3. Add Java equivalents for result/stop statuses and a server-side policy resolver.
4. Add the `control` result field and terminal progress-event schema without changing the current
   authorization ladder.

Primary files:

```text
harness_py/orchestration/runtime.py
harness_py/orchestration/agents/context.py
harness_py/orchestration/run_output.py
harness_py/utils/status.py
harness_py/transport/service.py
src/main/java/.../ResearchHarnessPayloadFactory.java
src/main/java/.../ResearchHarnessResultMapper.java
src/main/java/.../ProductResultStatus.java
src/main/java/.../ProductStopReason.java
```

### Phase 2: Recoverable tool errors

1. Extract shared Corpus preflight validators from production and in-memory tool behavior.
2. Make every validator return the v1 recoverable envelope.
3. Change `JavaCorpusGateway.post` to raise `CorpusGatewayError` with safe structured information.
4. Map local validation failures to recoverable tool output and all unexpected Java 400/401/5xx
   conditions to documented terminal codes.
5. Retain the current `paper_not_authorized_for_reading` key as an alias while tests migrate to
   `PAPER_ID_NOT_DISCLOSED`.

Primary files:

```text
harness_py/corpus/tools.py
harness_py/corpus/gateway.py
harness_py/corpus_test_fixtures/in_memory_tools.py
harness_py/orchestration/agents/tools.py
src/main/java/.../InternalCorpusController.java
```

### Phase 3: Budget and deadline enforcement

1. Build `RunControl` once in `ResearchHarnessService`; thread it through `TurnExecutionInput`,
   `ResearchRunContext`, the provider adapter, and the Java Corpus reader.
2. Check model-call count and deadline in Hooks before the SDK performs a call.
3. Compute serialized-request upper bounds in the provider adapter, clamp per-call output tokens,
   and record actual usage after every response.
4. Apply remaining deadline to provider and Corpus I/O; catch the control exceptions outside
   `Runner.run` and build a `LIMITED` result rather than `FAILED_TECHNICAL`.
5. Resolve `RunLimits` in Java, reserve the complete Run token ceiling atomically, and settle/release
   against the cumulative usage returned by Python on every terminal path.
6. Use the same deadline in the Redis worker rather than relying on its lock heartbeat.

Primary files:

```text
harness_py/orchestration/agents/runtime.py
harness_py/orchestration/agents/model.py
harness_py/orchestration/agents/context.py
harness_py/corpus/gateway.py
harness_py/transport/service.py
harness_py/transport/redis_worker.py
src/main/java/.../PythonResearchHarnessClient.java
src/main/java/.../UsageQuotaService.java
src/main/java/.../UsageBalanceQuotaService.java
```

The balance gate atomically reserves one token before dispatch. A failed reservation creates no
generation and makes no Harness request; a completed run is settled from provider-reported usage.

### Phase 4: Bounded projection and product rendering

1. Bound model-visible history and tool payloads deterministically, while retaining full internal
   evidence for audit and citation validation.
2. Emit `run_limited`, `job_completed`, `job_failed`, and `job_cancelled` consistently in HTTP and Redis.
3. Map the new status and diagnostics through `ResearchHarnessResultMapper`, `ChatHandler`, Redis
   generation state, TypeScript declarations, and chat message rendering.
4. Update maintained architecture, product requirement, and prompt text so they no longer describe
   unlimited rounds or the old text-only continuation behavior.

Primary files:

```text
harness_py/corpus/tools.py
harness_py/orchestration/memory.py
harness_py/orchestration/run_output.py
src/main/java/.../ResearchHarnessResultMapper.java
src/main/java/.../ChatHandler.java
src/main/java/.../ChatGenerationStateService.java
frontend/src/typings/api.d.ts
frontend/src/store/modules/chat/index.ts
frontend/src/views/chat/modules/chat-message.vue
```

## 10. Deployment And Compatibility

Deploy in this order:

1. Java recognizes `LIMITED`, `control`, and the new terminal diagnostics but continues to send the
   old `max_completion_tokens` field.
2. Python accepts absent `run_limits` by resolving the documented server defaults, then begins
   returning `control` and `LIMITED`.
3. Java begins sending resolved `run_limits` and reserves by the new complete-Run ceiling.
4. Frontend consumes `reasonCode` and limited terminal results. Only then remove compatibility
   emission of `answer_completed` and legacy recoverable error strings.

No schema migration is needed. Rollback is a server-side policy/configuration rollback plus a Python
version rollback; Java must continue to tolerate an absent `control` field and unknown progress
fields throughout the rollout.

## 11. Verification Matrix

### Python unit and contract tests

| Scenario | Required assertion |
| --- | --- |
| Text-only provider result | One synthesized final submission; no continuation call. |
| Truncated function arguments | One repair call, then valid retry; no malformed transcript replay. |
| Filename passed as `paper_id` | Recoverable `PAPER_ID_NOT_DISCLOSED`; Java is not called. |
| Every invalid Corpus enum/range/ref | Recoverable result with a stable code and next action. |
| Java 400 after successful preflight | `CORPUS_CONTRACT_VIOLATION`; terminal, no message parsing. |
| Java 401/503 | `CORPUS_AUTHENTICATION_FAILED`/`CORPUS_UNAVAILABLE`; terminal. |
| Empty search | Success with an empty result, never technical failure. |
| Rejected final submission | Recoverable validation result and another allowed model turn. |
| Repeated non-final loop | `LIMITED` with `RUN_MODEL_CALL_LIMIT` exactly at the configured call count. |
| Token reserve insufficient before a call | No provider request; `LIMITED/RUN_TOKEN_BUDGET_EXHAUSTED`. |
| Provider usage overshoot | Usage recorded; no next call; `LIMITED/RUN_TOKEN_BUDGET_OVERSHOOT`. |
| Deadline during provider or Corpus I/O | Bounded elapsed time and `LIMITED/RUN_DEADLINE_EXCEEDED`. |
| Cancellation at every boundary | `CANCELLED`, no active Run, no durable memory update. |
| Large repeated tool outputs | Projected request stays below the input and payload budgets; exact evidence remains citeable. |
| One oversized source unit | No partial source text reaches the model; stable recoverable code identifies the location. |

### Java and frontend tests

| Scenario | Required assertion |
| --- | --- |
| Payload creation | Java sends server-resolved `run_limits`; client input cannot override it. |
| Quota | Reservation covers the configured complete-Run ceiling; settlement records actual cumulative use and releases unused reserve. |
| Harness `LIMITED` response | Mapper returns `ProductResultStatus.LIMITED` and the correct stop reason. |
| ChatHandler | A limited result persists an abstention message and clears the active generation without `markFailed`. |
| Technical failure | A failed result remains failed and does not persist as a normal assistant answer. |
| WebSocket and generation snapshot | Terminal reason/usage is persisted once and is replayable after reconnect. |
| Frontend | `LIMITED` is rendered as a finished limit notice; `FAILED` remains an error; no spinner continues after any terminal event. |
| HTTP/Redis parity | The same scripted limit, failure, and cancellation produces the same `status`, `reasonCode`, usage fields, and one terminal event. |
| Browser regression | Compare papers, follow up, cancel, retry, reopen a table/quote, and load historic conversation all preserve existing evidence behavior. |

### Promotion gate

Before enabling the limits for all production turns, run the existing product corpus/Golden suite and
report, per provider and question class:

```text
answer/citation quality
candidate -> read -> cited coverage
p50/p95 wall-clock latency
p50/p95 cumulative prompt, completion, and total tokens
LIMITED rate by reason code
technical failure rate by reason code
cancellation completion rate
authorization or citation invariant violations (must be zero)
```

The change is rejected if it causes an authorization or citation invariant violation, introduces an
unclassified terminal event, or improves cost only by converting evidence-required answers into
uncited answers.

## 12. Explicitly Rejected Shortcuts

- Increasing `MAX_AGENT_TURNS` is not governance; it increases the maximum failure cost.
- Returning a raw Java 400 to the model is not a repair protocol.
- Catching all exceptions and returning a generic failed answer hides the boundary that failed.
- Treating a controlled limit as `FAILED_TECHNICAL` makes product analytics and retry behavior wrong.
- Reserving one model completion for a multi-call agent loop is not a quota guarantee.
- Moving the selected HTTP path to Redis does not solve error semantics, token accounting, or context growth.
- Summarizing evidence with an LLM to fit a budget would add another ungoverned model step; deterministic projection comes first.
