# Research Harness Artifact Contracts

Canonical machine-readable contract: `research/golden-data/artifact-contracts.yaml`.

This document names the runtime artifacts emitted by the v2 semantic-stage harness. The artifacts
remain inspectable for product diagnostics and frontend panes, but golden evaluation scores only
observable behavior through `BehaviorScorer`.

The trace is also the release gate. A completed Product Reading turn may be shown only when the
canonical `VerificationPass` is valid and the `ResearchAnswer` is linked back to the claim graph,
evidence ledger, and reasoning artifact. If that gate fails, the product must emit a precise
incomplete result rather than lower the evidence standard.

## Artifact Flow

```text
UserQuestion / GoldenCase messages
-> HarnessRun
-> IntentFrame
-> RetrievalPlan
-> EvidenceLedger
-> ClaimGraph
-> ReasoningArtifact
-> VerificationPass
-> ResearchAnswer

GoldenCase expectations + HarnessRun
-> ScoreReport (eval mode only)
```

## Contracts

### HarnessRun

Top-level executable boundary for a research question or Golden Case. Product mode may persist its
child artifacts for optional frontend review panes. Eval mode writes a separate deterministic
`ScoreReport`.

### IntentFrame

Typed interpretation of the user request. It records the raw and normalized question, entities,
paper and method mentions, constraints, answer type, ambiguity status, required evidence types, and
required capabilities.

### RetrievalPlan

Explicit plan for how the harness will search. It must show target entities, retrieval strategies,
expected evidence types, hard-negative policy, recall targets, and stop conditions. Retrieval plans
are runtime diagnostics and do not affect golden `hard_pass`.

### EvidenceLedger

Immutable ledger of accepted, rejected, and missing evidence. Every evidence item must carry paper
identity, paper version, section/page/location, element type, source span or cell reference,
retrieval strategy, quality labels, and claim links.

### ClaimGraph

Atomic claims with support/refutation edges. Final answers are allowed to use only claims that are
present here, and every supported claim must link to evidence.

### ReasoningArtifact

The structured synthesis product for the question shape: comparison table, timeline, citation graph,
reproduction checklist, conflict matrix, constraint table, uncertainty report, definition trace, or
multi-hop chain.

### VerificationPass

Pre-answer audit. It checks attempted capabilities, evidence status, unsupported claims,
contradictions, ambiguity handling, constraints, abstention requirements, stage completion, answer
references, and required corpus observation.

### ResearchAnswer

The final answer presented to the user. It must cite claim ids, evidence ids, reasoning artifact ids,
and the verification id. Its `outcome` is one of `answered`, `needs_clarification`, `abstained`, or
`partial`, independent of execution `status`; completed uncertainty reports and completed limited
answers therefore remain `COMPLETED`. Technical failures do not declare a research outcome. The
answer must not add unsupported substantive claims.

### ScoreReport

Eval-only deterministic scoring result. Each case reports independent `outcome`, `retrieval`,
`content`, and `grounding` dimensions plus a separate review status. It does not grade intent labels,
paradigms, stage order, tool counts, runtime claim ids, or exact answer prose, and it does not use an
LLM judge.
