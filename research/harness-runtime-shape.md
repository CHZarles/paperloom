# Research Harness Runtime Shape

This document fixes the runtime shape before further TDD. The harness is not a generic chat loop,
not a prompt wrapper around search, and not a graceful degradation system. It is a precision-gated
research instrument: if the evidence contract cannot be satisfied, the result is
`INCOMPLETE_PRECISE` with named missing evidence.

Related contracts:

- `research/harness-artifact-contracts.md`
- `research/golden-data/artifact-contracts.yaml`
- `docs/qa/product-session-pm-review-2026-07-08.md`

## Non-Negotiables

1. No fallback answers. A failed retrieval route produces missing evidence, not a weaker answer.
2. No hard-coded semantic intent matching. Java may validate schemas, enums, refs, identity, scope,
   and permissions; it must not infer research intent from keyword `contains(...)` checks.
3. No unready data. Product Reading scope contains only current READY PaperLoom Reading Models from
   the user's Product Paper Library.
4. No raw internals in user-visible text. Handles, refs, tool names, parser enums, SQL ids, and
   dynamic raw ids are hidden payload, never prose.
5. No unsupported substantive claims. Every answer sentence that says something about paper content
   must map to a ClaimGraph node, and each supported node must map to inspectable evidence.

## External Interface

The harness should expose one deep interface:

```text
runResearchTurn(TurnFrame) -> ResearchTurnResult
```

`TurnFrame` contains:

- user request
- conversation scope
- current session reading state
- explicit UI anchors, such as clicked paper, location, or source quote
- model/runtime limits

`ResearchTurnResult` contains:

- status: `COMPLETED`, `NEEDS_CLARIFICATION`, `INCOMPLETE_PRECISE`, or `FAILED_TECHNICAL`
- user-facing `ResearchAnswer`
- persisted trace artifacts
- persisted UI artifacts
- state patch for selected paper, selected location, selected quote, and next action

Everything else is implementation detail behind this interface.

## Runtime Pipeline

```text
TurnFrame / GoldenCase
-> HarnessRun
-> IntentFrame
-> RetrievalPlan
-> Tool-Gated Observations
-> EvidenceLedger
-> ClaimGraph
-> ReasoningArtifact
-> VerificationPass
-> ResearchAnswer
-> Persisted UI Artifacts
-> ScoreReport (eval mode only)
```

The answer is the last artifact, not the source of truth. The trace is the release gate.

## Modules

### 1. TurnFrame Builder

Creates the canonical turn input from chat text, locked Product Paper Library scope, persisted
session state, and explicit UI anchors.

Rules:

- Clicked UI anchors are navigation authority, not evidence.
- Persisted source quote anchors authorize `trace_source_quotes`, not final claims by themselves.
- Empty or stale session state may be ignored only when it has no durable records; it must not
  silently select an unrelated paper.

### 2. Intent Framer

Produces a typed `IntentFrame` from the turn. This is the only place where language understanding
belongs.

Required output includes:

- normalized question
- paper, method, dataset, metric, section, and constraint mentions
- answer type
- ambiguity status
- required evidence types
- required capabilities
- location query plans when paper-location search is needed

Invalid schema, ambiguous identity, or missing required fields stops the run with
`INCOMPLETE_PRECISE` or `NEEDS_CLARIFICATION`. Java must not repair this by keyword routing.

### 3. Plan Compiler

Converts the `IntentFrame` into a typed `RetrievalPlan`.

Each plan step declares:

- target entity
- retrieval strategy
- required evidence type
- required recall target
- hard-negative policy
- stop condition
- tool capability required

The plan may be adaptive only by producing a new explicit plan step from a verified missing-evidence
record. It must not substitute a lower-precision route.

Examples:

- If table evidence is required and table retrieval is unavailable, stop with missing table
  evidence.
- If semantic location search returns no match, record the no-match and stop or ask a focused
  clarification. Do not browse outline sections as a semantic substitute.
- If paper identity is ambiguous, present the choice set. Do not choose from title similarity.

### 4. Tool Gate

Executes only tool calls authorized by the `RetrievalPlan`, `TurnFrame`, and session state.

The Product Reading operational surface can remain the current nine tools:

```text
get_session_state
list_papers
search_paper_candidates
find_papers_by_identity
get_paper_outline
list_paper_locations
find_reading_locations
read_locations
trace_source_quotes
```

The Python exploration harness deliberately exposes a smaller model-facing surface:

```text
search_paper_candidates
find_papers_by_identity
find_reading_locations
read_locations
get_citation_edges  # only when graph data exists
```

Its enforced information path is `paper candidate -> reading location -> citeable evidence`.
Candidate cards, ambiguous identity matches, location previews, and citation edges are navigation
state rather than paper-content evidence. Only `read_locations` may create citeable content
evidence. Candidate search must report `complete` or `truncated` in-corpus coverage, and the runtime
must not inject fallback tool calls when the model declines an authorized retrieval action.

Those are transport operations, not the harness capability model. The capability model is larger:
identity resolution, paper candidate retrieval, structured location retrieval, quote reading,
source quote tracing, table evidence, figure evidence, formula evidence, algorithm evidence,
citation graph traversal, negative evidence search, and version comparison. Missing capability
means incomplete, not fallback.

The gate rejects:

- unknown tools
- out-of-plan tools
- paper handles not disclosed or persisted as selected anchors
- ambiguous identity results used as selected papers
- location search without a validated typed query plan
- final answers before required observations exist

### 5. Observation Normalizer

Transforms tool results into normalized observations. Observations are still not evidence until the
ledger accepts them.

Each observation records:

- source tool operation
- plan step id
- product paper identity
- current reading model identity
- location or source quote identity
- element type
- raw availability gaps, such as missing visual asset or missing page text
- rejection reason when unusable

### 6. Evidence Ledger

Accepts, rejects, or records missing evidence.

Accepted evidence must be inspectable:

- paper identity
- title
- current reading model/version
- page/section/location
- element type
- source quote or exact source span
- retrieval strategy
- quality labels
- claim links

Metadata can support discovery and paper selection. It cannot support paper-content claims unless
the answer contract explicitly asks for metadata.

### 7. Claim Graph Builder

Builds atomic claims only from accepted evidence or explicit missing-evidence records.

Claim statuses:

- `supported`
- `contradicted`
- `underdetermined`
- `ambiguous`

The graph is the only source for final answer content. The model may draft wording, but the harness
must reject any substantive claim that is absent from the graph.

### 8. Reasoning Artifact Builder

Creates the question-specific inspection object:

- exact fact card
- comparison table
- reproduction checklist
- citation genealogy
- conflict matrix
- constraint filter table
- uncertainty boundary report
- multi-hop chain

Every cell, node, step, or row must link back to claim ids or evidence ids. If the links cannot be
made, the artifact is invalid.

### 9. Verification Pass

This is the release gate.

It checks:

- required capabilities attempted
- required evidence satisfied
- unsupported claim count is zero
- contradictions are surfaced when relevant
- ambiguity was resolved or clarification is required
- scope stayed locked to current READY Product Papers
- no raw internal ids leak into visible answer text
- UI artifacts are persisted and reopenable
- final answer cites only allowed claim and evidence ids

If verification fails, the result is `INCOMPLETE_PRECISE`. This is not fallback behavior; it is the
precise product result for unmet evidence obligations.

### 10. Answer Presenter

Renders a short human answer from verified artifacts.

Shape:

```text
I understand your goal as: <goal>.

Short answer: <one or two useful sentences>.

Start here: <paper/location/claim>, because <reason>.

How to verify: <citation chip/page/section/card>.

Not verified yet: <missing evidence or unavailable visual/source proof>.

Next step: <one concrete action>.
```

The presenter is deterministic and conservative. It does not invent recommendations to make the UI
feel complete.

## Frontend Shape

The frontend should show the whole decision path as inspectable product artifacts, not as raw tool
logs.

Primary artifacts:

- `GoalCard`
- `PaperShortlist`
- `ReadingPlan`
- `ClaimEvidencePanel`

Trace inspection artifacts:

- Intent summary
- Retrieval plan steps
- Evidence ledger summary
- Claim graph summary
- Reasoning artifact
- Verification summary

User interactions create explicit anchors for the next turn:

- choose paper
- open locations
- read location
- open source quote
- ask about this citation
- revise goal

Those anchors are state, not evidence. They authorize the next plan step but do not support claims.

## Status Semantics

`COMPLETED` means every required evidence and trace obligation for the answer contract passed.

`NEEDS_CLARIFICATION` means the harness found a precise ambiguity that the user can resolve with one
focused choice.

`INCOMPLETE_PRECISE` means the harness knows exactly which evidence, capability, identity, or
artifact obligation is missing.

`FAILED_TECHNICAL` means infrastructure failed before the research contract could be evaluated.

There is no status for "answered with weaker evidence."

## What Must Be Removed Or Prevented

- semantic intent routing by string contains checks
- semantic no-match fallback to outline browsing
- metadata-only beginner paper roles presented as verified reading recommendations
- old or unready PDFs in Product Reading scope
- source quote registry entries treated as current-turn evidence without tracing
- tool output prose exposed as the product answer
- Product State Items treated as durable evidence

## First Implementation Implication

Before adding more tests, the existing code should be judged against this shape:

1. Does every completed turn pass through the trace release gate?
2. Can every visible claim be traced to accepted ledger evidence?
3. Does every missing precision case become `INCOMPLETE_PRECISE` or `NEEDS_CLARIFICATION`?
4. Are all intent and retrieval decisions typed artifacts rather than Java keyword routes?
5. Are UI anchors persisted and replayable after reload without becoming evidence?
6. Is Product Reading scope limited to current READY reading models only?

Only after these answers are clear should the next TDD slice be chosen.
