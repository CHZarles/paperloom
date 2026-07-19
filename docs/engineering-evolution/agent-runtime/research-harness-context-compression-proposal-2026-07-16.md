# Research Harness Single-Session Context Management Proposal

Date: 2026-07-16

Status: Proposed.

## 1. Decision And Scope

PaperLoom only needs context management inside one product session:

- different product sessions have no relationship;
- there is no user-level memory;
- no context state is shared across product sessions;
- no new Java persistence field or table is required.

This proposal addresses the model context created during one research turn. A turn may use several
internal OpenAI Agents SDK Runner epochs, but all new state is request-local and is discarded when the
turn ends.

The minimal design is:

1. store complete tool results in a request-local Ledger;
2. send deterministic compact projections to the model;
3. rebuild one bounded working context and start a fresh Runner near the context budget;
4. use a fresh finalizer so rejected drafts do not accumulate.

V1 explicitly excludes:

- LLM context compression;
- generated paper summaries or paraphrases;
- immutable context archives;
- a Protected Context Manifest;
- vector retrieval for context;
- provider-side context editing;
- cross-session or user-level memory.

## 2. Current Problem

The current Runtime creates one `ResearchRunContext`, one `RequestBackedSession`, and one continuous
`Runner.run(max_turns=None)`.

Every later model call replays the earlier assistant calls, tool outputs, complete reads, repair
messages, rejected drafts, and validation errors.

The deployment-oriented run examined for this proposal had:

| Metric | Observed value |
| --- | ---: |
| Model calls | 16 |
| Corpus calls | 18 |
| Prompt growth | 3,125 -> 58,818 tokens |
| Cumulative model input | 599,327 tokens |
| Cumulative model output | 27,524 tokens |
| Cited evidence characters | 64,996 |

The problem is repeated representation, not the number of papers.

Current integration points:

| Responsibility | Code |
| --- | --- |
| Runner lifecycle | [`harness_py/orchestration/agents/runtime.py`](../../../harness_py/orchestration/agents/runtime.py) |
| Tool invocation and submission | [`harness_py/orchestration/agents/tools.py`](../../../harness_py/orchestration/agents/tools.py) |
| Request-local Context | [`harness_py/orchestration/agents/context.py`](../../../harness_py/orchestration/agents/context.py) |
| Corpus authorization and evidence | [`harness_py/corpus/tools.py`](../../../harness_py/corpus/tools.py) |
| Product-session history | [`ProductReadingConversationService.java`](../../../src/main/java/io/github/chzarles/paperloom/service/ProductReadingConversationService.java) |

## 3. Minimal Design

```text
Tool executes
  -> record full internal result in request-local Ledger
  -> return deterministic compact model projection
  -> build separate UI progress projection

Prompt approaches working budget
  -> build bounded model context from Ledger
  -> create fresh SDK Session and Runner

Research completes
  -> create fresh finalizer with selected exact evidence
  -> rehydrate request-local evidence when needed
  -> validate against authoritative Ledger state
```

Only two new implementation concepts are required:

1. `ResearchLedger`: full request-local tool-result records.
2. `build_model_context()`: one deterministic function for research and finalizer input.

Projection and rehydration stay as plain functions/tools in the existing modules. Do not add policy
interfaces, compressor adapters, archive classes, or persistence services.

### 3.1 Request-Local Ledger

Minimum shape:

```python
@dataclass
class ResearchLedger:
    tool_results_by_call_id: dict[str, JsonMap]
    tool_order: list[str]
```

Existing objects remain authoritative:

- `ReadingCorpusTools.authorized_paper_ids` controls paper access;
- `ReadingCorpusTools.disclosed_location_refs` controls location access;
- `ReadingCorpusTools.observations_by_evidence_id` stores exact evidence;
- final validation reads the Ledger plus existing Corpus state.

The Ledger does not duplicate authorization logic and is never persisted.

### 3.2 Tool Projection

`_invoke_domain()` becomes:

```text
internal result
  -> ledger.record(full result)
  -> project_tool_result(tool_name, internal result)
  -> model-visible output and trace

internal result
  -> existing progress_output(...)
  -> frontend progress event
```

The model and UI projections must be separate.

| Tool | Model projection |
| --- | --- |
| Paper search | Query, count, coverage, rank, paper ID, title, year, venue, short preview |
| Identity search | Status and minimal matches |
| Location search | Query, location ref, paper ID, section, page, type, focused preview |
| Read | Exact evidence with repeated parser, asset, and index metadata removed |
| Citation graph | Unique relevant edges and compact paper identities |
| Skill | Full guidance on first use; skill ID/version after a fresh epoch |
| Error | Current error code and repair instruction |

Deterministically remove duplicate cards, consumed location previews, repeated reads, resolved errors,
obsolete repair payloads, and rejected drafts older than the latest draft.

For a large read result:

1. keep the full canonical text in the Ledger;
2. send the full exact text when it fits;
3. otherwise reuse the query-focused location preview only if it is an exact substring;
4. preserve complete table, figure, and formula units;
5. fall back to the full text when exact projection is ambiguous.

No LLM rewrites paper content.

### 3.3 Bounded Model Context

`build_model_context(context, phase, budget)` returns a plain JSON-compatible projection. It does not
mutate the Ledger.

Research input contains:

1. phase instructions;
2. bounded recent messages from the current product session;
3. current user question;
4. active validation and coverage gaps;
5. compact candidate and unread-location state;
6. selected exact evidence;
7. a compact inventory of omitted request-local evidence.

Exact evidence selection is deterministic:

1. include all exact evidence when it fits;
2. include IDs selected by `prepare_research_answer`;
3. include evidence matching normalized terms from the question or recent tool queries;
4. fill the remaining evidence budget by recency.

Reuse the existing Harness term normalization. Do not add embeddings.

An omitted-evidence card contains only:

```json
{
  "evidence_id": "ev_123",
  "paper_id": "paper_a",
  "title": "Paper title",
  "section": "Evaluation",
  "page": 7,
  "element_type": "paragraph"
}
```

If all cards do not fit, include per-paper counts and the most relevant/recent cards. The model can
search the request-local Ledger through rehydration.

Initial budget hypotheses:

```text
working_context_budget = 64K prompt tokens
soft_checkpoint        = 45K
mandatory_checkpoint   = 54K
```

These values remain configurable and must be tuned from measured quality and latency.

## 4. Runner And Finalizer Flow

### 4.1 Fresh Research Epoch

Check the context budget only after a complete model response and its whole tool group have executed.

Before ending the current Runner:

1. build the next model context from the full Ledger;
2. verify that it fits the hard input budget plus output reserve;
3. set `context.epoch_terminal = {"kind": "checkpoint"}`;
4. let `tools_to_final_output()` stop the SDK Runner with a string sentinel.

The outer Runtime starts another Runner with:

- the same request-local `ResearchRunContext`;
- the same `ReadingCorpusTools`;
- a new `RequestBackedSession`;
- new research Agent configuration.

The new SDK Session contains only selected messages from the current product session. Reusing the old
SDK Session would replay the tool transcript and defeat the checkpoint.

There is no Raw Carry-Forward Capsule. The next model input is built directly from the Ledger before
the old Runner ends. If projection fails below the hard budget, continue the old Runner. If no valid
context can fit near the hard limit, return the existing precise partial or technical outcome.

### 4.2 Request-Local Rehydration

Add one tool:

```json
{
  "name": "rehydrate_research_context",
  "arguments": {
    "evidence_ids": ["ev_123"],
    "paper_ids": ["paper_a"],
    "query_text": "deployment limitations",
    "limit": 10
  }
}
```

Behavior:

- return exact requested Evidence IDs;
- otherwise use existing lexical term matching over request-local evidence;
- optionally filter by paper ID;
- enforce bounded result count and output size;
- return only evidence already authorized and read in this turn or supplied by the current product
  session's existing prior-evidence path;
- never create Evidence IDs or expand paper scope.

The research Agent and finalizer may both use this tool.

### 4.3 Fresh Finalizer

Use two plain instruction functions and two tool catalogs:

```text
Research Agent
  corpus tools
  get_research_skill
  rehydrate_research_context
  prepare_research_answer

Finalizer
  rehydrate_research_context
  request_more_research
  submit_research_answer
```

`prepare_research_answer` ends the research Runner:

```json
{
  "evidence_ids": ["ev_123"],
  "counterevidence_ids": ["ev_456"],
  "unresolved_questions": [],
  "answer_plan": ["deployment constraints", "evaluation", "limitations"]
}
```

The finalizer input contains the question, answer plan, selected exact evidence, counterevidence,
active coverage constraints, and compact omitted-evidence cards.

Every finalizer attempt uses a fresh SDK Session:

- accepted submission: build the product result;
- rejected submission: keep only the latest draft and deterministic error, then start one fresh repair
  attempt;
- genuine evidence gap: `request_more_research` starts a fresh research epoch.

The finalizer may cite only Evidence IDs whose exact text appeared in its initial input or was returned
by rehydration during that attempt.

Repeated identical draft/error hashes stop with the existing no-progress outcome.

## 5. Code Changes

### 5.1 `ResearchRunContext`

Add:

```python
ledger: ResearchLedger
phase: str
epoch_id: int
epoch_terminal: JsonMap | None
selected_evidence_ids: set[str]
finalizer_visible_evidence_ids: set[str]
latest_rejected_draft: JsonMap | None
active_validation_error: str
```

Existing Corpus, trace, token, latency, cancellation, and tool-group state remains.

### 5.2 `tools.py`

- Record complete domain-tool results in the Ledger.
- Return deterministic model projections.
- Build UI progress from full internal results.
- Add `rehydrate_research_context`, `prepare_research_answer`, and
  `request_more_research`.
- Read coverage state from the Ledger rather than the compact trace.
- Store typed phase transitions in `context.epoch_terminal`.

### 5.3 `runtime.py`

Refactor the current single `Runner.run()` into one outer loop:

```python
while True:
    context.phase = "research"
    await run_research_epoch(
        context,
        build_model_context(context, "research", budget),
        session=new_epoch_session(),
    )
    terminal = take_epoch_terminal(context)

    if terminal["kind"] == "checkpoint":
        continue

    context.phase = "finalizer"
    await run_finalizer(
        context,
        build_model_context(context, "finalizer", budget),
        session=new_finalizer_session(),
    )
    terminal = take_epoch_terminal(context)

    if terminal["kind"] == "accepted":
        return terminal["draft"]
    if terminal["kind"] == "research_gap":
        continue
    update_finalizer_repair_state(context, terminal)
```

Create and close the provider Model once around the outer loop.

### 5.4 Instructions And History

Split the current instruction function:

- research instructions finish through `prepare_research_answer`;
- finalizer instructions allow only rehydration, a research-gap request, or submission.

Only current-product-session history is sent to Python. Apply the existing
`maxHistoryCharacters` and `recentHistoryMessages` settings.

No Java schema migration or context-memory persistence is required.

## 6. User-Visible Behavior

- Stored questions, answers, references, artifacts, and research events do not change.
- Folio continues loading historical messages from Java/MySQL.
- Messages omitted from the model prompt remain visible in the product session.
- Different product sessions share no messages, evidence, or context state.
- The existing prior-citation follow-up behavior inside the current product session remains unchanged.
- New answer wording or citation selection may change because the model receives a smaller working
  context; rollout must demonstrate non-inferior quality.

Internal fresh SDK Sessions are not visible to the user.

## 7. Safety And Failure Handling

Invariants:

- Full tool results remain in the request-local Ledger until the turn ends.
- Exact paper evidence is never rewritten by an LLM.
- Projection never expands paper or location authorization.
- Model-visible evidence is exact full text or a verified exact substring.
- Validation reads authoritative Ledger and Corpus state.
- Every Runner epoch and finalizer attempt uses a fresh SDK Session.
- Rehydration returns only known request-local evidence.
- The finalizer cites only exact evidence visible during its attempt.
- UI progress is independent from model projection.
- No context state crosses product-session boundaries.

Failure responses:

| Failure | Response |
| --- | --- |
| Tool projection throws | Return that tool's full original result |
| New model context is not smaller | Continue the old Runner below the hard budget |
| Exact evidence exceeds the budget | Select a bounded working set and expose rehydration |
| A valid context still cannot fit | Return the existing precise partial or technical outcome |
| Rehydration requests unknown evidence | Return a structured non-authorizing error |
| Finalizer cites unseen evidence | Reject and start one fresh repair attempt |
| The same repair repeats | Stop with the existing no-progress outcome |

## 8. Telemetry And Evaluation

Record:

- actual prompt tokens per request;
- repeated versus unique tool-output characters;
- full versus projected result size;
- included and omitted evidence characters;
- rehydration calls and returned IDs;
- Runner epoch and finalizer attempt counts;
- rejected-draft bytes avoided;
- cumulative tokens and p50/p95 latency;
- model/UI projection differences.

Run paired baseline and projected tests on:

- the engineering-deployment question;
- broad multi-paper synthesis;
- contradictory or negative evidence;
- tables, figures, formulas, and long sections;
- validation rejection followed by repair;
- follow-ups inside the same product session;
- insufficient-evidence and corpus-boundary behavior.

Required quality gates:

- zero authorization and Evidence ID invariant failures;
- no regression in required-paper coverage;
- no severe qualifier, negation, limitation, or counterevidence loss;
- human-blind task fulfillment is non-inferior to the current Runner;
- prompt size and cumulative replay decrease materially.

## 9. Rollout

### PR 1: Ledger And Observation

- Add the request-local Ledger.
- Separate model and UI projections.
- Refactor validation to read authoritative state.
- Add replay and size telemetry.
- Keep model-visible output unchanged.

### PR 2: Deterministic Projection

- Enable tool-specific projections and deterministic deletion.
- Add `build_model_context()` in observe mode.
- Verify evidence, trace, and UI invariants.

### PR 3: Fresh Research Epoch

- Add `epoch_terminal`.
- Create a new SDK Session per epoch.
- Enable threshold checkpoints.
- Add request-local rehydration.
- Keep one-Runner behavior behind a rollback flag.

### PR 4: Fresh Finalizer

- Add phase instructions and tool catalogs.
- Add `prepare_research_answer` and `request_more_research`.
- End every submission attempt.
- Run canary evaluation and decide default rollout.

No PR adds cross-session storage.

## 10. Acceptance Criteria

- Existing product-session messages and references reload unchanged.
- No message, evidence, or context memory crosses product-session boundaries.
- The Ledger is request-local and discarded after the turn.
- Model and UI projections are separate.
- Validation uses full authoritative state.
- Every checkpoint excludes the old SDK tool transcript.
- No LLM-generated paper-content summary is used.
- Omitted evidence can be found through request-local rehydration.
- Every cited Evidence ID had exact text visible to the finalizer.
- Rejected full drafts do not accumulate across attempts.
- The deployment case retains required evidence and supported claims.
- Cumulative input and maximum prompt size decrease materially.
- One-Runner rollback remains available during rollout.

Initial efficiency targets:

```text
maximum prompt size on long cases: reduce by at least 40%
cumulative input on the deployment case: reduce by at least 50%
old rejected drafts in a finalizer request: at most one
cross-session context records created: zero
authorization invariant failures: zero
```

Deferred until measurements justify them:

- LLM context compression;
- immutable archives and archive selectors;
- Protected Context Manifest extraction;
- cross-session or user-level memory;
- Java context persistence;
- vector context retrieval;
- provider-side context editing;
- disk spooling;
- exact-passage Evidence ID migration.

## Final Recommendation

Implement the request-local Ledger and deterministic projection first. Then add fresh Runner epochs and
a fresh finalizer.

This directly fixes the measured replay problem while preserving exact evidence and current
product-session behavior. Do not add an LLM compressor or durable archive until this simpler design has
been measured and shown to be insufficient.
