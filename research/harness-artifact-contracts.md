# Research Harness Artifact Contracts

Canonical machine-readable contract: `research/golden-data/artifact-contracts.yaml`.

This document names the V0 trace artifacts that the research harness must emit. The answer is only
the final artifact; eval and frontend inspection should primarily judge the trace before it.

## Artifact Flow

```text
Question
-> IntentFrame
-> RetrievalPlan
-> EvidenceLedger
-> ClaimGraph
-> ReasoningArtifact
-> VerificationPass
-> ResearchAnswer
```

## Contracts

### IntentFrame

Typed interpretation of the user request. It records the raw and normalized question, entities,
paper and method mentions, constraints, answer type, ambiguity status, required evidence types, and
required capabilities.

### RetrievalPlan

Explicit plan for how the harness will search. It must show target entities, retrieval strategies,
expected evidence types, hard-negative policy, recall targets, and stop conditions. Retrieval plans
are part of the visible product, not hidden implementation detail.

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
contradictions, ambiguity handling, constraints, abstention requirements, and trace obligations.

### ResearchAnswer

The final answer presented to the user. It must cite claim ids, evidence ids, reasoning artifact ids,
and the verification id. It must not add unsupported substantive claims.
