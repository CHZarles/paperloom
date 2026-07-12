# Paradigm-Driven Semantic Stage Harness Prototype

## Current Lightweight Decision

This section supersedes the plan-compiler and dynamic-registry portions of the original proposal
below. After review, the prototype was deliberately simplified:

- one structured LLM call selects one primary paradigm;
- each paradigm maps to one short, explicit, ordered semantic-stage list;
- stages run online with stage-scoped tools and patch live evidence/claim state immediately;
- strategies and tool permissions are declared on each stage, not inferred by keyword matching;
- deterministic code enforces provenance, references, statuses, and final verification;
- there is no generic workflow compiler, dynamic DAG, or post-ReAct stage reconstruction.

All 22 paradigms in `research/remake.md` now have explicit lightweight plans. Eight are backed by
current primary golden cases: precision fact extraction, methodology reproduction, deep comparison,
association/influence genealogy, complex multi-hop reasoning, uncertainty/knowledge boundary,
ambiguity resolution, and contradiction resolution. The other fourteen remain provisional until
new golden cases exercise them.

## Objective

Build a Python prototype that answers one architectural question:

> Can a paradigm-driven semantic stage runtime govern retrieval and reasoning more faithfully than
> the current global ReAct loop followed by post-hoc artifact construction?

The prototype must execute the existing nine golden cases and demonstrate that different question
paradigms produce meaningfully different semantic stage plans. It must also preserve the existing
precision gate: no completed paper-content claim without inspectable evidence.

This is an experimental runtime, not a compatibility layer around the current implementation. The
existing Python harness may be replaced or bypassed where that makes the experiment clearer.

## Current Problem

The current `ResearchAgentHarness` runs one unrestricted model/tool loop, receives a nearly complete
answer payload, and then derives `IntentFrame`, `RetrievalPlan`, `EvidenceLedger`, `ClaimGraph`, and
`VerificationPass` afterward. Consequently:

- the retrieval plan describes tool calls after they happened;
- evidence and claims do not govern subsequent execution;
- every paradigm shares one top-level control flow;
- the model is asked to solve intent, retrieval, reasoning, answer construction, and memory updates
  in one response;
- trace artifacts look staged, but the runtime is not stage-driven.

The new runtime must make semantic stages causal. A stage plan exists before its authorized tool
calls, and every completed stage patches the shared research state before the next stage begins.

## Considered Approaches

### A. One Hard-Coded Pipeline Per Paradigm

Create one Python workflow for each of the 22 paradigms in `research/remake.md`.

Advantages:

- easy to inspect;
- deterministic stage ordering;
- straightforward initial tests.

Rejected because it replaces keyword routing with workflow routing, duplicates retrieval logic,
and makes mixed-paradigm questions difficult to represent.

### B. Fully Free-Form LLM Workflow Generation

Ask the model to invent arbitrary stage names, contracts, dependencies, and tool policies for every
turn.

Advantages:

- maximum flexibility;
- naturally adapts to unfamiliar questions.

Rejected because arbitrary stages cannot be reliably gated, replayed, scored, or rendered in a
stable frontend. It also makes failures difficult to localize.

### C. Typed Semantic Stage Registry With LLM Planning

Use a small registry of reusable semantic stage kinds. An LLM produces a multi-label
`ParadigmFrame`, then compiles a question-specific `SemanticStagePlan` by selecting and configuring
registered stage kinds.

This is the selected approach. It gives the LLM semantic control while retaining typed contracts,
tool authorization, replayability, and deterministic verification.

## External Interface

The prototype retains one deep external interface:

```python
ParadigmStageHarness(model).run_turn(dataset, turn_frame) -> ResearchTurnResult
```

`TurnFrame` contains:

- user message;
- conversation state;
- permitted paper scope;
- explicit paper/evidence anchors;
- model, stage, tool, and token budgets;
- optional golden-case expectations in eval mode.

`ResearchTurnResult` contains:

- final status and `ResearchAnswer`;
- `ParadigmFrame`;
- compiled `SemanticStagePlan`;
- ordered `StageRun` records;
- incrementally built `EvidenceLedger` and `ClaimGraph`;
- final `VerificationPass`;
- conversation state patch;
- eval-only `ScoreReport` outside the runtime.

## Runtime Shape

```text
TurnFrame
-> Paradigm Framing
-> Semantic Plan Compilation
-> Stage Runtime
     -> Stage 1: LLM/tool/hybrid execution
     -> validate and apply state patch
     -> Stage 2: LLM/tool/hybrid execution
     -> validate and apply state patch
     -> optional explicit PlanPatch
-> Final Verification
-> Answer Presentation
```

There is no global ReAct loop. A semantic stage may use a bounded internal model/tool loop, but its
goal, completion contract, permitted capabilities, and budget are fixed before execution.

## Core Artifacts

### ParadigmFrame

Produced by an LLM with schema validation.

```text
primary_paradigm
secondary_paradigms[]
confidence
answer_contract
semantic_obligations[]
ambiguity_status
required_evidence_types[]
required_capabilities[]
```

Paradigms are multi-label. They are planning priors, not hard routes.

### SemanticStagePlan

Compiled by an LLM from the `ParadigmFrame`, current research state, available stage kinds, and
available tool capabilities.

```text
plan_id
stages[]
edges[]
global_stop_conditions[]
```

Every plan must pass deterministic validation before execution:

- all stage kinds are registered;
- dependencies reference existing stages;
- required capabilities exist or are explicitly marked missing;
- stage tool policies are subsets of available tools;
- terminal stages include verification and presentation obligations;
- cycles are rejected in the prototype.

### StageSpec

```text
stage_id
stage_kind
semantic_goal
input_refs[]
preconditions[]
completion_contract
permitted_tools[]
executor: llm | tool | hybrid | deterministic
max_model_turns
max_tool_calls
failure_policy
```

The semantic goal and completion contract are question-specific. `stage_kind` comes from the
registry.

### StageResult

```text
stage_id
status: completed | incomplete | needs_clarification | failed_technical
decision_summary
observations[]
evidence_patch
claim_patch
reasoning_artifact_patch
conversation_state_patch
missing_obligations[]
proposed_plan_patch
diagnostics
```

Raw chain-of-thought is never stored. `decision_summary` is a concise inspectable rationale linked
to evidence, claims, or missing obligations.

### ResearchState

The shared live state contains:

- current `ParadigmFrame` and plan;
- accepted, rejected, and missing evidence;
- claim graph;
- resolved paper identities;
- comparison axes or conflict axes;
- conversation memory and explicit UI anchors;
- completed stage ids;
- remaining semantic obligations;
- budgets and tool-call history.

`EvidenceLedger` and `ClaimGraph` are state projections, not stages. They are patched after every
stage and govern later execution.

## Semantic Stage Registry

The first prototype uses this stable registry:

```text
frame_semantic_obligations
resolve_target_identity
clarify_ambiguity
define_fact_slots
establish_comparison_axes
retrieve_supporting_evidence
retrieve_counterevidence
trace_genealogy
test_knowledge_boundary
extract_atomic_claims
adjudicate_conflict
check_coverage
synthesize_answer
verify_research_contract
```

These are semantic operators rather than output-construction steps. The LLM configures their goals
and contracts for the current question.

## LLM and Deterministic Responsibilities

LLM-driven work:

- paradigm framing and semantic obligation discovery;
- stage-plan compilation;
- question-specific stage goals;
- retrieval query formulation;
- comparison-axis induction;
- semantic relevance and sufficiency proposals;
- claim extraction and conflict interpretation;
- answer synthesis from verified claims.

Deterministic enforcement:

- paper scope, permissions, and current reading-model status;
- stage schema and dependency validation;
- stage-level tool authorization and budgets;
- evidence identity, provenance, and inspectability;
- reference integrity between evidence, claims, and answers;
- unsupported-claim rejection;
- final status calculation;
- persisted artifact shape.

An LLM may propose evidence acceptance, but deterministic code verifies that the referenced
observation exists and has the required provenance. Semantic relevance remains an explicit model
decision recorded in the stage result.

## Stage Execution

For each ready stage, the runtime:

1. constructs a stage prompt containing only the stage goal, required state slice, completion
   contract, and permitted tools;
2. runs a bounded model/tool loop for that stage;
3. parses a typed `StageResult`;
4. validates every referenced observation, evidence id, claim id, and state patch;
5. atomically applies valid patches to `ResearchState`;
6. records missing obligations instead of silently weakening the stage;
7. activates dependent stages only after preconditions pass.

Independent stages may execute in parallel later, but the first prototype runs them sequentially so
state transitions remain easy to inspect.

## Adaptive Planning

Adaptation occurs only through an explicit `PlanPatch` proposed by a stage and accepted by the plan
validator.

A patch may:

- add a registered semantic stage;
- revise a later stage's inputs or completion contract;
- mark an unavailable capability as a named missing obligation;
- terminate with clarification or precise incompleteness.

A patch may not:

- remove already recorded evidence or claims;
- bypass verification;
- authorize an unavailable tool;
- replace required evidence with metadata or weaker evidence;
- change paper scope without an explicit conversation/UI state decision.

## Initial Plan Families

The nine committed golden cases support all eight implemented primary plan families.

### Exact Fact

```text
resolve_target_identity
-> define_fact_slots
-> retrieve_supporting_evidence
-> extract_atomic_claims
-> check_coverage
-> verify_research_contract
-> synthesize_answer
```

### Ambiguity Clarification

```text
frame_semantic_obligations
-> resolve_target_identity
-> clarify_ambiguity
-> verify_research_contract
```

No paper is silently selected.

### Knowledge Boundary

```text
resolve_target_identity
-> test_knowledge_boundary
-> retrieve_counterevidence
-> verify_research_contract
-> synthesize_answer
```

The answer is an uncertainty-boundary report when required evidence is absent.

### Deep Comparison

```text
resolve_target_identity
-> establish_comparison_axes
-> retrieve_supporting_evidence per paper and axis
-> extract_atomic_claims
-> check_coverage
-> verify_research_contract
-> synthesize_answer
```

### Citation Genealogy

```text
resolve_target_identity
-> trace_genealogy
-> retrieve_supporting_evidence for each edge
-> extract_atomic_claims
-> verify_research_contract
-> synthesize_answer
```

### Contradiction Arbitration

```text
resolve_target_identity
-> retrieve_supporting_evidence for each source
-> retrieve_counterevidence
-> adjudicate_conflict
-> extract_atomic_claims
-> verify_research_contract
-> synthesize_answer
```

Methodology reproduction and complex multi-hop remain secondary labels in the current data; the
prototype must not claim independent coverage for those paradigms.

## Conversation Memory

The existing `ConversationState` remains the durable turn-to-turn memory. The stage runtime reads
it through `TurnFrame` and emits a validated state patch.

Stage-local scratch data is ephemeral. Only these durable records cross turns:

- selected and scoped paper ids;
- selected evidence ids and inspectable evidence cards;
- prior answer summaries;
- unresolved choices;
- durable claims linked to evidence;
- explicit user/UI anchors.

Previous answers may resolve references such as "the first two", but they are not evidence. Any new
paper-content claim must still pass through evidence stages.

## Frontend and Terminal Inspection

Each `StageRun` is independently inspectable and can back an optional frontend pane. The pane may
show:

- semantic goal;
- status and completion contract;
- permitted capabilities;
- tool-call summary;
- evidence and claim patches;
- decision summary;
- missing obligations;
- accepted plan patch.

The terminal prototype prints the full stage plan before execution and a compact state diff after
each stage. It never prints raw chain-of-thought.

## Prototype Placement and Commands

The experimental implementation will live under `harness_py/stage_prototype/` so it is clearly
separate from the current runtime.

Primary command:

```bash
python3 -m harness_py stage-prototype --case-id transformer_adam_params_001
```

The command prints:

- `ParadigmFrame`;
- compiled stage graph;
- each `StageRun` and state patch;
- final standard harness artifacts;
- structural score against the selected golden case.

An optional live-corpus smoke command may be added only after all nine golden cases execute through
the new stage runtime.

## Error and Status Semantics

- Invalid paradigm or plan JSON receives one schema-repair attempt, then `FAILED_TECHNICAL`.
- Unresolved paper identity produces `NEEDS_CLARIFICATION`.
- A stage that cannot satisfy an evidence obligation produces `INCOMPLETE_PRECISE` with named
  missing obligations.
- Infrastructure failure before a semantic result produces `FAILED_TECHNICAL`.
- `COMPLETED` requires every final claim to reference accepted evidence and every required stage
  contract to pass.
- There is no fallback to a weaker plan or metadata-only paper-content answer.

## Testing Strategy

Deterministic tests use scripted model adapters to prove runtime behavior independently of MiniMax
variability. Live MiniMax runs are smoke evidence, not the sole release gate.

Required tests:

1. each of the six answer contracts compiles to a distinct semantic plan family;
2. every tool call is authorized by the currently executing stage;
3. an out-of-stage tool call is rejected;
4. stage patches update the ledger and claim graph before dependent stages execute;
5. supported claims without accepted evidence cannot complete;
6. ambiguity stops before arbitrary paper selection;
7. knowledge-boundary cases preserve named missing evidence;
8. comparison coverage fails when one required paper-axis cell is unsupported;
9. contradiction arbitration preserves both source claims and the contextual resolution;
10. explicit plan patches can add a registered stage but cannot bypass verification;
11. all nine committed golden cases execute through the same generic stage runtime;
12. existing deterministic golden validation remains green.

## Acceptance Criteria

The prototype answers the design question successfully when:

- all nine golden cases run through the paradigm-driven stage runtime;
- at least six observably different stage-plan families are produced;
- plans exist before and authorize tool calls;
- the ledger and claim graph are incrementally patched during execution;
- stage completion and failure are visible in exported artifacts;
- all completed answers have zero unsupported claims;
- ambiguity and uncertainty cases return their required non-answer statuses;
- the existing golden dataset validator still passes;
- one MiniMax smoke demonstrates a model-driven paradigm frame, plan, and stage execution.

## Non-Goals

- implementing all 22 paradigms;
- production Java integration;
- arbitrary cyclic or parallel stage graphs;
- exposing chain-of-thought;
- replacing the current frontend;
- optimizing latency or token cost before the stage model is validated;
- preserving the current Python harness internals for compatibility.

## Expected Outcome

The experiment should make one decision possible: either absorb the semantic stage runtime into the
main harness, or reject it with concrete evidence about planner reliability, stage granularity, or
cost. The prototype itself is disposable; the validated stage contracts and evaluation findings are
the durable output.
