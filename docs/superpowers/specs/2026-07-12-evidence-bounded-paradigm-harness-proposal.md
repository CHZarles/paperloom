# Evidence-Bounded Paradigm Harness Proposal

## Decision

Rewrite the disposable Python harness around a small evidence-bounded runtime:

```text
conversation history + current turn
-> TaskFrame
-> paradigm recipe and evidence obligations
-> bounded online retrieval stage
-> atomic claim construction
-> one semantic claim verification pass
-> deterministic answer rendering
```

Keep paradigm-driven semantic behavior, but remove the generic stage machinery that currently
serializes every intermediate thought into a large `StageResult` contract. The new runtime should
have one conversation decision, one adaptive retrieval loop, paradigm-specific claim construction,
one verification call, and no free-form factual prose outside verified claims.

This is a harness rewrite, not a compatibility migration. Existing Python tools, database adapters,
provider configuration, and conversation storage can stay. The current stage runner and its repair
branches can be deleted once the replacement passes the Golden cases.

## Evidence From The Current Baseline

The eight human-labelled holdout runs provide enough evidence for the next rewrite. Human results
are:

| Dimension | Passes |
|---|---:|
| Correct action or decision | 7/8 |
| Task fulfillment | 4/8 |
| Grounding | 1/8 |
| Overall | 1/8 |

The failures are concentrated rather than broad:

1. One exact-fact request incorrectly abstained even though the source passage was present.
2. Six answers found useful evidence but added unsupported or overstated material.
3. The three conversational follow-ups were resolved without another clarification, so the current
   full-history `TurnDecision` should be retained.
4. The current LLM judge produced three false passes and must not yet be an acceptance gate.

The retrieval backend itself is not yet the demonstrated bottleneck. Calling
`find_reading_locations` directly with the failed query, `Adam optimizer beta1 beta2 epsilon
hyperparameters`, returns the exact Transformer optimizer passage first. The failure therefore sits
in retrieval orchestration: one bad or over-constrained location call can end the evidence stage,
and the runtime does not let the model reformulate after an empty result.

The grounding defect is also architectural. The current answer stage creates claims, citations, and
free-form Markdown in the same model call. Runtime verification checks that cited IDs exist, but it
does not check whether the cited passage supports the claim. Consequently the answer can cite valid
evidence and still add claims such as:

- an optimizer setting was deliberately tuned with a learning-rate schedule;
- GPT-3 reused the GPT-2 training pipeline wholesale;
- the original Transformer as a whole is left-to-right;
- BERT removed the original decoder's causal mask;
- a paper used architectural components or corpora not stated in the cited passages.

Prompt reminders alone have not prevented this behavior.

## Design Principles

1. **Minimal sufficient answer.** Answer the requested obligations and stop. Background, causal
   explanation, comparison, and recommendations are included only when requested or required by the
   paradigm.
2. **Evidence before claims.** Paper-content claims can only be constructed from passages returned
   by `read_locations`.
3. **No untracked factual prose.** Every visible material sentence or table cell must originate from
   a verified claim with evidence IDs.
4. **One owner per decision.** `TaskFrame` owns conversation interpretation, the retrieval stage owns
   evidence collection, claim construction owns semantic synthesis, and verification owns support
   decisions.
5. **Paradigms define obligations, not infrastructure.** The 22 paradigms remain explicit recipes,
   but they reuse a small execution kernel rather than a generic workflow language.
6. **Bounded adaptation, not fallback stacking.** Retrieval may reformulate a failed query within
   one stage. There is no secondary harness, keyword route, judge ensemble, or weaker fallback
   answer.
7. **Artifacts are optional views.** The chat answer is primary. Intent, plan, evidence, claims, and
   verification remain inspectable panes without appearing in normal chat.

## Runtime Shape

### 1. Interpret The Turn

Use the existing forced `submit_turn_decision` call with full conversation context. Replace its
research payload with a compact `TaskFrame`:

```text
route: direct | clarify | research
effective_request
primary_paradigm
constraints
paper_references
answer_shape
obligations[]
assumption
```

An obligation is a semantic question that must be answered, not a stage description:

```text
id
description
kind: fact | comparison_cell | relation | hop | procedure_item | conflict_side | recommendation
paper_scope[]
required: true | false
```

For example, the Transformer optimizer question produces three obligations for beta1, beta2, and
epsilon. The comparison question produces target-axis obligations. The multi-hop question produces
one obligation for each edge.

Keep the current conversation rules:

- greetings can return directly;
- genuinely blocking identity or reference ambiguity asks one question;
- broadness is not automatically blocking;
- a confirmation, ordinal choice, or refinement updates the active task and resumes research in the
  same turn.

Do not retain the older raw-JSON `IntentRecognizer` path after migration.

### 2. Select A Paradigm Recipe

Each recipe is a small Python definition, not a dynamic DAG and not a model-invented workflow. It
declares:

```text
obligation guidance
retrieval guidance
claim roles
answer layout
```

The plan is generated before tool use from `TaskFrame + recipe`. It is causal and inspectable, but
it contains no post-hoc tool narration.

Only the Golden-backed recipes need to be implemented in the first rewrite:

| Paradigm | Semantic work |
|---|---|
| `precision_fact_extraction` | Resolve requested slots, retrieve their exact source passage, return only slot values and necessary qualifiers. |
| `methodology_reproduction` | Define requested checklist items, recover paper-specific values, render only those items and explicit gaps. |
| `deep_comparison` | Define requested axes, collect one evidence-backed cell per target and axis, preserve asymmetric distinctions. |
| `association_influence_genealogy` | Resolve nodes, recover a directly stated relation, describe only what that relation establishes. |
| `complex_multihop_reasoning` | Define independent hops, collect evidence per hop, preserve intermediate nodes, prohibit unsupported shortcut edges. |
| `contradiction_resolution` | Collect both source statements, distinguish their scope or conditions, resolve only the apparent conflict supported by those statements. |
| `context_specific_brainstorming` | Resolve conversation constraints, inspect bounded candidates, justify each selected paper from evidence. |
| `uncertainty_knowledge_boundary` | Test corpus coverage for required obligations and state the precise unsupported boundary. |
| `ambiguity_resolution` | Ask one focused question and perform no retrieval until the blocking ambiguity is resolved. |

The remaining paradigms can be added as recipes after this slice; they do not require runtime
changes.

### 3. Collect Evidence In One Online Stage

Replace separate identity and evidence stage calls with one bounded LLM/tool loop. The loop receives
the obligations and the recipe's retrieval guidance, and can use the existing tools:

- `search_paper_candidates`
- `find_papers_by_identity`
- `find_reading_locations`
- `read_locations`
- `get_citation_edges` when available

The loop maintains an `EvidenceCoverage` map:

```text
obligation_id
status: covered | missing
evidence_ids[]
note
```

Required behavior:

1. Resolve or discover candidate papers.
2. Search locations for uncovered obligations.
3. Read selected locations.
4. Attach read evidence to obligations.
5. Stop when every required obligation is covered or the bounded search budget is exhausted.

An empty location result does not immediately terminate the stage. The model may make at most two
additional location searches with changed wording, section constraints, or fewer constraints. This
is ordinary retrieval adaptation inside the semantic stage, not a second fallback mechanism.

Do not change the lexical retrieval implementation yet. The failed optimizer query demonstrably
retrieves the correct passage when called correctly. Revisit hybrid or embedding retrieval only if
new Golden runs show recall failures after orchestration is fixed.

### 4. Construct Atomic Claims

The paradigm-specific synthesis call receives only:

- the effective request and constraints;
- the obligation coverage map;
- the exact evidence passages;
- the recipe's allowed claim roles.

It returns atomic claims, not final Markdown:

```text
claim_id
role
text
obligation_ids[]
evidence_ids[]
```

Rules:

- Every claim must satisfy at least one requested obligation.
- Every paper-content claim must cite read evidence.
- A claim must preserve qualifiers such as `default`, `used`, `similar`, `except`, and `decoder`.
- A missing obligation produces a gap; it does not authorize generic background.
- The model cannot add a `why`, tradeoff, corpus detail, implementation component, or causal story
  unless that content is an obligation and is directly supported.

This removes the current failure mode where answer Markdown contains factual statements that are
absent from the structured claim list.

### 5. Verify Claims Once

Run one structured semantic verification call over the small atomic claim set. For each claim it
must return:

```text
claim_id
verdict: keep | rewrite | drop
verified_text
evidence_ids[]
reason
```

The verifier sees no Golden expectations and no human labels. It checks only whether the supplied
passages support the exact wording and qualifiers. A `rewrite` may narrow a claim to what the
evidence actually states; it cannot add information. There is no verifier retry, voting, self-review
loop, or judge ensemble.

The current LLM-as-judge implementation remains an offline evaluator. Do not reuse its aggregate
pass/fail prompt as the runtime verifier. Atomic claim verification is narrower and should be tested
directly through the Golden answers.

### 6. Render The Answer Deterministically

Render only kept or rewritten claims. The renderer may add headings, labels, punctuation, and
citation markers, but no new factual sentence.

Recipes choose a simple layout:

- exact fact: compact key-value list;
- reproduction: checklist;
- comparison: table;
- genealogy: directed edge list;
- multi-hop: numbered chain;
- contradiction: side A, side B, resolution;
- recommendation: bounded ranked or grouped list;
- boundary: supported and unsupported obligations.

This makes `ResearchAnswer` a projection of verified claims instead of another source of semantic
content.

## Artifact And Frontend Model

Keep the existing external artifact names so the frontend can expose optional panes:

```text
IntentFrame       <- projection of TaskFrame
RetrievalPlan     <- recipe + obligations, created before retrieval
EvidenceLedger    <- read passages and obligation coverage
ClaimGraph        <- candidate and verified claims
VerificationPass <- per-claim keep/rewrite/drop decisions
ResearchAnswer    <- deterministic rendering of verified claims
```

`StageTrace` remains useful for debugging but should contain only stage name, tool calls, compact
decisions, and status. It must not be required for reading the answer and must not contain hidden
chain-of-thought.

## Code Change Map

Keep with small adjustments:

- `harness_py/conversation.py`
- `harness_py/dataset.py`
- `harness_py/product_db_dataset.py`
- `harness_py/provider_config.py`
- `harness_py/llm.py`
- `harness_py/tools.py`
- `harness_py/live_chat.py`

Rewrite:

- `harness_py/stage_prototype/models.py`: replace generic `StageResult` and opaque `state_values`
  with `TaskFrame`, `Obligation`, `EvidenceCoverage`, `Claim`, and `ClaimVerdict`.
- `harness_py/stage_prototype/plans.py`: replace long generic stage declarations with explicit
  paradigm recipes.
- `harness_py/stage_prototype/runtime.py`: replace `StageRunner` repair machinery with the six-step
  runtime above.

Delete after the new path is active:

- the old `IntentRecognizer` raw JSON parser;
- generic stage status repair and answer-contract repair branches;
- forced one-search-then-submit behavior;
- opaque `state_values` handoffs;
- free-form answer Markdown supplied by the synthesis model;
- verification that treats an existing evidence ID as proof of semantic support.

Do not add Java code, new database tables, a workflow compiler, a new tool catalogue, or legacy
compatibility layers.

## Implementation Sequence

### Slice 1: Contracts And Runtime Skeleton

1. Introduce the new task, obligation, coverage, claim, and verdict contracts.
2. Extend `TurnDecision` to emit obligations.
3. Add the small paradigm recipe registry for the nine Golden-backed paradigms.
4. Emit the existing optional artifact names from the new state.

### Slice 2: Adaptive Retrieval

1. Implement one retrieval loop with obligation coverage.
2. Permit bounded query reformulation after an empty location result.
3. Prove the Transformer optimizer passage is recovered for
   `transformer_adam_params_001`.

### Slice 3: Claim-Bounded Answers

1. Implement paradigm-specific atomic claim construction.
2. Implement one claim verification call.
3. Render answers only from verified claims.
4. Remove the old free-form answer path.

### Slice 4: Golden And User Validation

1. Re-run the eight human-labelled cases and preserve new artifacts in the repository.
2. Review changed answers manually; do not use the current judge as the gate.
3. Run one terminal chat session against product database papers.
4. Fix only failure modes observed in these outputs.

## Acceptance Criteria

The rewrite is ready for the next corpus expansion when:

1. The exact Transformer optimizer case retrieves and answers beta1, beta2, and epsilon.
2. All three follow-up cases resume the active task without redundant clarification.
3. Every visible material claim maps to a verified claim and read evidence.
4. No answer adds an unsupported causal explanation, component list, training detail, or shortcut
   relationship.
5. At least seven of the eight current human-labelled cases pass overall on a fresh harness run,
   with zero grounding false passes in human review.
6. One product-database terminal conversation feels useful without requiring the user to inspect
   internal panes.
7. The focused Python tests pass. Java is not run.

After these criteria pass, create a fresh 8-12 case blind holdout for the LLM judge. Do not tune or
claim acceptance against the already observed holdout. Downloading the larger agent-evaluation paper
pack should happen only after this harness loop is reliable.

## Explicit Non-Goals

- No prediction of future user questions.
- No question-specific keyword routing.
- No dynamic stage or DAG generation.
- No second retrieval backend in this slice.
- No multiple judges, voting, self-critique loops, or fuzzy output parsing.
- No requirement that users inspect intermediate artifacts.
- No attempt to validate all 22 paradigms before the Golden-backed recipes work.
