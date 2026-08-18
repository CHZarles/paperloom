# Pi Agent TypeScript Runtime Migration Design

Date: 2026-08-17

Status: Historical feasibility study. TypeScript migration was considered and explicitly not selected. No runtime
code, deployment, Redis contract, or Java behavior changed from this document.

> Current decision: keep the Python OpenAI Agents SDK Runner and harden its Provider protocol path in
> [PaperLoom Harness Runner Hardening Spec](paperloom-harness-runner-hardening-spec-2026-08-18.md).

## 1. Historical Proposal (Not Selected)

The considered option was to replace the production Python Harness Worker with one TypeScript Worker built on the
public, low-level Pi Agent interfaces. It was not selected.

The migration has three rules:

1. reuse Pi's Provider, standard messages, `EventStream`, and `agentLoop` instead of reimplementing them;
2. keep PaperLoom's Prompt, Tools, Contract, Validator, Trace, Run Limits, Retry/Cancel, and result semantics;
3. accept the TypeScript Worker only after it passes the existing PaperLoom-31 Benchmark and Redis lifecycle gates.

"Cut Pi down" means limiting the imported surface first. It does **not** mean copying the whole Pi repository on day
one. The first implementation pins the published packages. Source is vendored only if a required public interface is
missing or unstable in practice.

The production result is one Runtime, not permanent Python/TypeScript dual operation.

The current Python in-place repair is specified in
`paperloom-harness-runner-hardening-spec-2026-08-18.md`.

### 1.1 Non-Goals

- implementing or deploying the TypeScript Runtime in this design phase;
- changing Java, Redis v1, MySQL, Qdrant, or frontend contracts;
- porting the obsolete internal HTTP Harness mode;
- changing the four-process, one-Run-per-process concurrency policy;
- importing Pi's full Harness, Session, coding Tools, or persistence;
- redesigning the PaperLoom Prompt, adding Subagents, or adding context compaction before measurements require it;
- claiming a latency or Token improvement before the paired Benchmark exists.

## 2. Why Change The Runtime

This is not a rewrite for the sake of changing languages. The current Python implementation mixes two concerns that
need independent ownership:

```text
Provider wire behavior
  MiniMax request/response blocks, Tool Choice, Thinking replay

PaperLoom Agent behavior
  Prompt, authorized Tools, Contract, Validator, Trace, limits and publication
```

The 2026-08-14 production Run `run_8d1496878502420c8cbccd5b4c7270a0` exposed the cost of that coupling:

| Metric | Observed |
| --- | ---: |
| Harness duration | 343.3 s |
| Model calls | 28 |
| Cumulative Tokens | 632,527 |
| Plain-text responses discarded | 6 |
| Time spent on those discarded responses | 170.3 s |
| Model-selected `_continue_research_turn` calls | 4 |
| Submission attempts | 5, first 4 rejected |

Across 33 production traces using the same compatibility behavior, 24 contained at least one plain-text nudge; 39
responses and 675.2 seconds of model time were discarded. More Redis Workers cannot shorten those serial requests
inside one Agent loop.

Pi already owns the provider block conversion and low-level Tool loop. PaperLoom should own only the product rules
that distinguish it from a general coding Agent.

## 3. Architecture

### 3.1 Current

```text
Java Product
  -> research-harness-job/v1
  -> Redis Streams
  -> Python RedisResearchWorker
       -> ResearchHarnessService
       -> PaperLoom Harness
       -> OpenAI Agents SDK Runner
       -> custom MiniMax OpenAI-compatible Model
       -> Java Corpus HTTP
  -> research-harness-event/v1 + result-v1
  -> Java persistence / WebSocket
```

### 3.2 Target

```text
Java Product
  -> research-harness-job/v1                    unchanged
  -> Redis Streams                              unchanged
  -> TypeScript PaperLoom Worker
       -> PaperLoom Harness
            Prompt / Tools / Contract / Validator
            Trace / RunControl / Retry / Cancel
       -> pi-agent-core agentLoop
       -> pi-ai MiniMax CN Provider
            Anthropic Messages
       -> Java Corpus HTTP                       unchanged
  -> research-harness-event/v1 + result-v1      unchanged
  -> Java persistence / WebSocket               unchanged
```

Java remains the fact source for authorization, locked Paper Scope, MySQL conversation state, Qdrant retrieval,
quota settlement, Retry ownership, and final persistence. The TypeScript Worker must not connect directly to MySQL
or Qdrant.

The old internal HTTP Harness mode is not migrated. Production and migration verification use the existing Redis
transport.

## 4. Feasibility Evidence

### 4.1 Package And Machine Compatibility

The evaluation used these published packages:

| Item | Observed |
| --- | --- |
| `@earendil-works/pi-ai` | `0.84.1`, MIT |
| `@earendil-works/pi-agent-core` | `0.84.1`, MIT |
| Required Node | `>=22.19.0` |
| Local Node/npm | `v22.23.1` / `10.9.8` |
| MiniMax Provider | `minimax-cn` |
| API | `anthropic-messages` |
| Base URL | `https://api.minimaxi.com/anthropic` |
| Catalog Model | `MiniMax-M3` |

The public package exports include `agentLoop`, `runAgentLoop`, `AgentMessage`, `AgentEvent`, `StreamFn`, Tool hooks,
`shouldStopAfterTurn`, `prepareNextTurn`, `EventStream`, `createModels`, and `minimaxCnProvider`. The migration does not
need Pi's private internals for the first version.

A clean npm install of the two packages on the local machine produced 98 installed packages and about 131 MB of
`node_modules`; the two Pi packages themselves occupy about 8 MB. This is deployment overhead, not a capacity
blocker. It is not a reason to fork Pi before a working candidate exists.

The production server is currently incompatible with this Pi version:

| Production fact | Observed |
| --- | --- |
| OS | Debian GNU/Linux 13, x86_64 |
| System Node/npm | `v20.19.2` / `9.2.0` |
| Node path | `/usr/bin/node` |
| Worker fleet | four `paperloom-harness-worker@` processes |
| Per-process concurrency | one Run |
| Available memory | about 84 GiB at inspection time |
| Free root disk | about 526 GB at inspection time |

Node 20 is the only deployment blocker discovered. Install an isolated, pinned Node 22 Runtime and use its absolute
path in the new systemd unit. Do not replace `/usr/bin/node`, because the existing frontend toolchain may depend on
the system installation.

### 4.2 Real MiniMax Tool Loop Probe

A real `MiniMax-M3` probe ran outside the repository through Pi's low-level loop:

```text
model -> echo_probe -> ToolResult -> model -> submit_probe -> terminate
```

Observed result:

```json
{
  "ok": true,
  "provider": "minimax-cn",
  "api": "anthropic-messages",
  "model": "MiniMax-M3",
  "modelCalls": 2,
  "toolCalls": ["echo_probe", "submit_probe"],
  "requiredToolChoiceCount": 2,
  "replayIncludedToolResult": true,
  "replayIncludedThinking": true,
  "stopReasons": ["toolUse", "toolUse"],
  "totalTokens": 1188
}
```

This proves the narrow technical path: Pi can preserve Thinking and Tool Results across requests and execute the
two-pass `model -> tool -> model -> submit` loop against the real MiniMax CN endpoint. It does not prove PaperLoom
quality, long-run reliability, or Benchmark parity.

### 4.3 Two Important Provider Findings

#### Tool Choice

Pi's `SimpleStreamOptions` does not contain provider-specific `toolChoice`. Consequently,
`models.streamSimple()` does not forward Anthropic `tool_choice`.

PaperLoom must resolve and validate the only production model at Worker startup, then supply a narrow `StreamFn`
that calls `models.stream()`:

```ts
const miniMax = models.getModel("minimax-cn", "MiniMax-M3");
if (!miniMax || !hasApi(miniMax, "anthropic-messages")) {
  throw new Error("MiniMax-M3 Anthropic model is unavailable");
}

const streamModel: StreamFn = (_model, context, options = {}) =>
  models.stream(miniMax, context, {
    ...options,
    toolChoice: "any",
    thinkingEnabled: true,
  });
```

The production version must narrow the model to `anthropic-messages`, pass the configured API key and timeout, and
record payload/response metadata. It must not use `streamSimple()` as the Provider Adapter.

`tool_choice: any` is a Provider request, not a business correctness proof. In another deliberately weakly specified
probe, MiniMax returned plain text on the second pass even though both recorded payloads contained
`tool_choice: {type: "any"}`. Therefore PaperLoom still needs a deterministic failure path: text without a legal Tool
Call is `PROVIDER_TOOL_PROTOCOL_VIOLATION`; it is never published and never converted into a synthetic continuation.

#### Thinking Effort

In Pi `0.84.1`, the `MiniMax-M3` catalog entry has `reasoning: true` but no `forceAdaptiveThinking` compatibility flag.
The Anthropic adapter therefore emits budget-based Thinking. Passing `effort: "medium"` does not place
`output_config.effort` in the MiniMax payload.

The first candidate should use `thinkingEnabled: true` and a measured `thinkingBudgetTokens`. It must not claim that
`effort: "medium"` controls MiniMax until a recorded payload proves it. The final budget is a Benchmark parameter,
not a design-time guess.

## 5. Message Contracts

There are three different message protocols. They must not be collapsed into one type.

### 5.1 Java To Worker: PaperLoom-Owned

The authoritative transport fixtures remain:

- [`job-v1.json`](../../contracts/research-harness/job-v1.json)
- [`event-v1.json`](../../contracts/research-harness/event-v1.json)
- [`result-v1.json`](../../contracts/research-harness/result-v1.json)

The TypeScript Worker parses the existing JSON fields and rejects invalid trust-boundary input. It may use the
TypeBox dependency already required by Pi; it should not add Zod or Ajv for these small fixed contracts.

Pi types must never appear in Redis payloads. This keeps Java independent from Pi package upgrades.

### 5.2 Provider Transcript: Pi-Owned

Inside one Run, use Pi's existing standard message union directly:

```text
UserMessage

AssistantMessage
  content[] = text | thinking | toolCall
  usage
  stopReason

ToolResultMessage
  toolCallId
  toolName
  content[]
  details
  isError
```

The complete `AssistantMessage`, including Thinking signatures and Tool Calls, and the matching `ToolResultMessage`
are replayed on the next model pass. Do not reconstruct a reduced assistant message by hand.

PaperLoom Tool results use two projections:

- `content`: bounded text/JSON visible to the model;
- `details`: structured PaperLoom state and trace data, not relied on as Provider-visible evidence.

### 5.3 Product Trace: PaperLoom-Owned

Pi `AgentEvent` values feed the existing PaperLoom trace vocabulary. Redis progress events and saved evaluation
traces remain stable even if Pi adds event variants.

Provider payload hooks record redacted request metadata. Agent events record model and Tool lifecycle. PaperLoom
Contract transitions, authorization facts, validation results, and Source Quote identity remain explicit
PaperLoom trace records; they must not be inferred later from prose.

## 6. Module Design

The target is a few deep modules with small interfaces, not a framework around Pi.

### 6.1 Worker Transport

Single responsibility:

```text
consume job-v1
  -> own Redis lease / heartbeat / stale recovery
  -> create one AbortController
  -> call runTurn
  -> publish ordered event-v1 and one terminal result
  -> atomically commit status + ACK
```

This module owns no Prompt, Tool, Contract, Provider, or answer validation logic. The existing Lua ownership checks,
sequence rules, `XPENDING`/`XCLAIM` recovery, terminal commit, TTLs, and cancellation key semantics are behavioral
requirements, not Python implementation details.

Keep the current topology during migration: four processes and one active Run per process. Changing concurrency at
the same time would make failures and performance changes harder to attribute.

### 6.2 PaperLoom Harness

The external interface stays small:

```ts
runTurn(job, dependencies, signal, emit): Promise<ResearchResultV1>
```

It owns:

- building the per-Run Prompt and bounded history;
- request-local Contract, Corpus, Evidence, Retry, Trace, and RunControl state;
- PaperLoom Tool definitions and Java Corpus calls;
- deterministic Tool authorization and submission validation;
- normalized answer, citation, memory, usage, and trace output.

It does not own Redis, MySQL, Qdrant, or Provider wire conversion.

### 6.3 Provider Adapter

Use Pi's `StreamFn` as the real Provider seam. Do not introduce another generic Provider interface.

The MiniMax Adapter owns:

- selecting `minimax-cn` / `MiniMax-M3`;
- calling `models.stream()`, not `streamSimple()`;
- Anthropic `toolChoice: "any"`;
- Thinking configuration proven by recorded payloads;
- API key, remaining deadline, AbortSignal, and transport retry policy;
- provider error mapping and request/response trace hooks.

A test fake is the second Adapter at this seam. That is enough variability to justify the seam; factories and plugin
registries are unnecessary.

### 6.4 Pi Agent Loop

Call low-level `agentLoop`/`runAgentLoop` with PaperLoom configuration:

```text
toolExecution = sequential
getSteeringMessages = absent
getFollowUpMessages = absent
Session = absent
Compaction = absent
```

Use `beforeToolCall` for RunControl and Contract checks. Tools execute against the request-local Harness state. An
accepted Submission returns `terminate: true`; a rejected Submission returns bounded validation feedback and keeps
the loop active. `shouldStopAfterTurn` handles accepted completion, cancellation, limits, and protocol failure before
another Provider request can begin.

Pi exits immediately on Provider `error` or `aborted` stop reasons before `shouldStopAfterTurn` runs. The outer
`runTurn` result mapper must therefore inspect the final Assistant message and map those two reasons to PaperLoom's
technical-failure or cancellation terminal result.

If one assistant response contains a Submission and sibling Tool Calls, PaperLoom's existing exclusivity rule rejects
the Submission. Sequential execution is mandatory because Corpus authorization state grows after each Tool Result.

### 6.5 Java Corpus Adapter

Use Node's native `fetch` and `AbortSignal` for the existing Java internal endpoints. Do not add Axios. Preserve:

- internal bearer authentication;
- `request_id`, `conversation_id`, `user_id`, and locked `paper_ids` on every relevant call;
- response byte limits and bounded model-visible projections;
- deadline/cancel checks before and after I/O;
- the current Paper -> Location -> Source Quote authorization chain.

## 7. Pi Surface To Keep And Remove

### Keep

| Pi surface | Reason |
| --- | --- |
| `pi-ai` `Message` types | Provider-independent transcript |
| `AssistantMessageEventStream` / `EventStream` | Streaming and observable lifecycle |
| `minimaxCnProvider` | Official MiniMax CN Anthropic configuration |
| Anthropic Messages adapter | Thinking, Tool Call, and Tool Result conversion |
| low-level `agentLoop` | `model -> tool -> model` control loop |
| `AgentTool` + TypeBox schema | Tool input validation |
| sequential Tool execution | PaperLoom request-state correctness |
| `AbortSignal` | Provider, Corpus, and Tool cancellation |
| before/after Tool hooks | Contract, Trace, and RunControl integration |
| `prepareNextTurn` / `shouldStopAfterTurn` | bounded context and deterministic stopping |

### Do Not Import

| Pi surface | Reason |
| --- | --- |
| `AgentHarness` | PaperLoom already has a product Harness |
| JSONL Session repository | Java/MySQL is the cross-Turn source of truth |
| Compaction and branch summarization | not required for parity; measure first |
| Steering/follow-up queues | Java creates explicit new jobs |
| Pi Prompt templates and Skills | PaperLoom Prompt and Research Skill already exist |
| Bash/File/Edit/Write/Image tools | unrelated and unsafe for this product Worker |
| Coding Agent environment | unrelated to paper research |
| other Provider factories | one production Provider in the first migration |
| Pi persistence | would create a second conversation fact source |

## 8. Capability Migration Matrix

| Current Python owner | Required behavior | TypeScript owner | Acceptance evidence |
| --- | --- | --- | --- |
| `agents/model.py` | Provider blocks, errors, usage, tracing | Provider Adapter | real two-pass MiniMax probe |
| `agents/runtime.py` | Prompt assembly and Agent loop | Harness + Pi loop | Direct/Catalog/Research cases |
| `research_contract.py` | Contract state machine and Validator | deterministic Contract module | differential fixtures + L3 replay |
| `agents/context.py` | request-local mutable state | Run state | monotonic Paper/Location/Quote tests |
| `agents/tools.py` | Corpus, Skill, and Submission Tools | Tool adapters | Tool result parity and submission gates |
| `corpus/tools.py` | bounded model projection | Corpus Tool module | existing projection fixtures |
| `corpus/gateway.py` | authenticated Java HTTP | Corpus Adapter | live Java scope checks |
| `conversation.py` | history and accepted memory | Harness input/output mapping | follow-up and Retry cases |
| `run_control.py` | deadline, cancellation, usage | RunControl | boundary and cancel checks |
| `run_output.py` | normalized answer/citations/trace | result builder | `result-v1` fixture and Java mapper |
| `transport/service.py` | job validation and Turn assembly | `runTurn` entry | `job-v1` fixture |
| `transport/redis_worker.py` | lease, recovery, events, terminal commit | Worker Transport | real Redis integration scenarios |

The related production code is about 4,754 Python lines. This is not a line-for-line translation target. It is a
warning that Provider proof alone does not prove the Worker migration.

Python evaluation code may remain temporarily as an offline Benchmark driver. It must call the TypeScript candidate
through the existing Redis contract and must not remain as a second production Agent Runtime.

## 9. Behavior That Must Remain Identical

### Prompt And Context

- preserve the current Research instructions and three explicit Submission Tools;
- keep whole recent user/assistant pairs within `max_history_chars`;
- place the current user question last;
- carry prior accepted Evidence as bounded trusted Run context, not fake historical Tool Calls;
- carry Retry metadata and the previous answer as feedback context, not accepted truth;
- preserve complete Pi assistant/tool blocks only inside the current Run.

Pi exposes one `systemPrompt` rather than arbitrary system-role messages. The candidate composes static Research
instructions plus bounded previous-evidence and Retry sections into one per-Run system prompt. It does not introduce
a Pi Session.

### Contract And Publication

- `DIRECT`, `CATALOG`, and `RESEARCH` remain mutually exclusive;
- the model selects a Contract only through an explicit Submission Tool;
- Research publication still requires exact `source_quote_ref` identity and block citation coverage;
- invalid drafts and plain text are never user-visible answers;
- `_continue_research_turn` is not exposed or recreated;
- only an accepted Submission ends with a publishable answer.

### Run Limits

First achieve parity with `paperloom-run-limits/v1`:

```text
max_wall_clock_ms
max_model_visible_tool_chars
max_history_chars
```

Do not silently add model-call or Token fields to v1 while changing languages. If the candidate still needs numeric
call/Token/submission circuit breakers after Provider correction, version that policy separately and let Java remain
the server-owned policy source. A repeated identical validation failure may terminate as no progress, but it must be
introduced and measured as an explicit behavior change after the parity checkpoint.

The Provider Adapter checks the shared deadline before every request. The Corpus Adapter and Tool hooks check the
same RunControl. A Redis cancel key, lost lease, deadline, or system shutdown aborts the same `AbortController`.

### Retry And Cancel

User Retry is a new `job-v1` containing Java-loaded history, prior answer, citation IDs, answer slot, and revision. It
is not Pi's follow-up queue and does not overwrite historical MySQL rows.

Cancel must abort an in-flight MiniMax stream and Java Corpus request, publish the existing cancelled terminal event,
and never publish a partial answer. Provider transport retry is a separate concern and must not be described as user
Retry.

## 10. Benchmark Plan

### 10.1 Historical Baseline

The existing milestone remains the historical control:

- Design/code milestone: `184d2b9`
- Snapshot: `a61e8c1c240b2e8873b88d20da497b9ec0b98d9631c77a3e05c0634eeb92ecd3`
- Passed Run: `20260812T020304Z-cd6e7648`
- L1 Recall@1: `1.0000`
- L2 Recall@10: `0.80645`
- L3 Hard Pass: `17 / 17`
- Contract Accuracy, Protocol Replay, Provenance: `1.0000`
- Internal Beta Gate: passed

The saved Run manifest records `code_revision=1aedde69`, whose direct child is `184d2b9`. Therefore the saved scores
are a valid historical behavior baseline, but not a perfectly clean binary performance A/B at `184d2b9`. Before any
resume or production claim says "improved X%", run one fresh Python control and the TypeScript candidate from recorded
clean commits against the same Snapshot, Java corpus, Model, and configuration.

The historical Run also records 93 Agent model calls, 984,113 Agent Tokens, and 1,205,791 ms across 17 L3 cases. These
are comparison observations, not promised service-level targets.

### 10.2 Hard Gates

The TypeScript candidate must satisfy all of these:

- load the unchanged `job-v1` fixture and emit compatible event/result fixtures;
- pass Direct, Catalog, Research, Follow-up, ambiguity, missing-evidence, and open-recommendation cases;
- L3 Hard Pass `17 / 17`;
- Contract Accuracy, Protocol Replay, and Provenance `1.0000`;
- zero new Scope leak, fabricated citation, rejected-draft publication, or technical failure;
- preserve Retry answer-slot semantics and Cancel terminal semantics;
- pass Redis fresh job, duplicate, lease renewal/loss, stale reclaim, terminal commit, and ACK scenarios;
- preserve Java result mapping and quota settlement;
- contain no model-visible `_continue_research_turn`;
- turn a no-Tool response into one bounded technical terminal result, not another model pass.

L1/L2 retrieval should remain unchanged because Java/Qdrant are unchanged, but the final cutover run still records the
full suite so the assumption is checked rather than asserted.

### 10.3 Performance Comparison

Correctness is the hard gate. Performance is compared only after correctness passes:

1. run the same representative Direct, Catalog, Research, Follow-up, and long-repair cases three times on Python and
   TypeScript;
2. compare medians for model calls, prompt/completion/total Tokens, Provider time, Tool time, and end-to-end time;
3. run the complete 17-case L3 once for the acceptance artifact;
4. investigate a median model-call or Token regression over 10%; do not hide it by adding Workers or timeouts;
5. report measured medians and sample size, not the best run or a circuit-breaker ceiling.

The reproduced long case must show that plain-text nudge time and model-selected internal continuation calls are both
zero. No performance improvement is claimed in this design document.

## 11. Delivery Sequence

### Phase 0: Design And Feasibility

Complete in this document:

- inspect Python responsibilities and Redis contracts;
- inspect Pi `0.84.1` public interfaces;
- run a real MiniMax Tool loop probe;
- verify production Node and Worker topology;
- define parity and cutover gates.

### Phase 1: Small Provider/Loop Candidate

- create an isolated TypeScript package with exact Pi versions and lockfile;
- use Node ESM, built-in `fetch`, `AbortController`, and `node:test`;
- implement only the MiniMax `StreamFn`, one fake Tool, one Submission Tool, and Trace capture;
- save the real payload assertions proving Tool Choice and Thinking behavior;
- do not connect production Redis.

### Phase 2: Deterministic PaperLoom Core

- port Contract, Validator, RunControl, result builder, bounded history, and request-local state;
- replay saved Python fixtures through both implementations;
- resolve every semantic difference before adding the real Agent loop;
- keep Redis and deployment unchanged.

### Phase 3: Prompt, Corpus, Tools, Trace, Retry/Cancel

- port the current Prompt and Research Skill without redesigning them;
- port Java Corpus HTTP and PaperLoom Tool adapters;
- connect the deterministic core to Pi `agentLoop`;
- produce the existing normalized result and trace shapes;
- run focused Direct/Catalog/Research/Retry/Cancel checks.

### Phase 4: Redis Worker

- use the standard Node `redis` client rather than writing a Redis protocol client;
- port the existing Lua ownership and terminal-commit behavior without changing keys or schema versions;
- run the existing real-Redis lifecycle scenarios against dedicated test key prefixes;
- retain four processes with one Run each.

### Phase 5: Benchmark And Cutover

- run the paired Python control and TypeScript candidate;
- pass all hard gates before interpreting performance;
- install isolated Node 22 on the server;
- stop Python Workers only when there are no live jobs;
- start four TypeScript Workers with the same keys/group and verify a real product Run;
- roll back by stopping TypeScript and restarting Python if any gate fails.

Do not run Python and TypeScript Workers simultaneously against the production keys. The shared consumer group would
randomly route jobs, while different groups would compete to publish the same status/event keys.

## 12. Package Versus Vendoring

Start with exact dependencies:

```text
@earendil-works/pi-ai         0.84.1
@earendil-works/pi-agent-core 0.84.1
```

Commit the lockfile and import only the low-level surfaces listed in Section 7. This gives the smallest maintenance
burden and the fastest path to a meaningful Benchmark.

Vendor only the required Pi module when at least one concrete condition occurs:

- a needed low-level export is removed or cannot express PaperLoom's behavior;
- Provider Tool/Thinking replay needs a patch that upstream will not accept;
- measured cold-start, package, or supply-chain cost becomes operationally material;
- a pinned upgrade cannot be isolated behind the existing `StreamFn` seam.

If vendoring becomes necessary, copy only the Agent Loop and MiniMax/Anthropic modules used by PaperLoom, retain MIT
notices and upstream commit identity, and delete all unused Provider, Session, Compaction, coding Tool, and Harness
code. Do not maintain a full Pi fork.

## 13. Completion And Deletion Conditions

The migration is complete only when:

1. the TypeScript Worker passes every hard gate and the recorded Benchmark comparison;
2. the production systemd units run the isolated Node 22 executable and four TypeScript Workers;
3. Retry, Cancel, lease recovery, quota settlement, result persistence, and frontend status are verified end to end;
4. no production path starts `python -m harness_py worker`;
5. Python evaluation, if retained, no longer imports the Python production Runtime;
6. the Python Provider, Agent Runtime, Redis Worker, and obsolete HTTP Harness are deleted after the rollback window;
7. deployment and operations documents reference only the TypeScript Worker.

There is no database migration and no Java/Redis protocol migration. Rollback is a Worker executable switch, not a
data rollback.

## 14. Feasibility Conclusion

The migration is feasible, but it is not a drop-in package swap.

Proven now:

- Pi exposes the required low-level seams;
- its MiniMax CN Provider can perform a real two-pass Tool loop;
- Thinking and Tool Results can be replayed correctly;
- PaperLoom can enforce Anthropic Tool Choice through `models.stream()`;
- the server has ample capacity for the Node package footprint.

Not proven yet:

- the 715-line deterministic Contract/Validator has exact TypeScript parity;
- Redis lease/recovery behavior survives the port;
- the full Prompt/Tool/Trace/Retry/Cancel behavior passes PaperLoom-31;
- latency or Token usage improves;
- the production Node 22 Runtime is installed.

The next implementation step is Phase 1 only: a small, locked TypeScript Provider/Loop candidate with recorded
payload assertions. It must not begin by copying all 4,754 Python lines or the full Pi repository.
