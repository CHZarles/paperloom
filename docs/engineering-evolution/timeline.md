# Engineering Evolution Timeline

This timeline highlights changes that shaped the current PaperLoom contract. Dates refer to the
recorded engineering work, not formal product releases.

## June 2026: Narrow the Product Around Papers

The system stopped presenting itself as a general document assistant and adopted research-paper PDFs
as the product subject. Product papers and benchmark corpora became separate data domains, followed
by explicit paper scope and collection semantics.

Evidence:

- [ADR 0001](../adr/0001-separate-product-papers-from-eval-corpora.md)
- [ADR 0002](../adr/0002-lock-conversation-scope-with-source-set-snapshots.md)
- [Product Requirements](../reference/product-requirements.md)

## Late June 2026: Fail Closed on Task Routing

A library-status question incorrectly entered paper QA and returned unrelated citations. The repair
separated typed task routing from deterministic capability execution, introduced a product-paper
source of truth, and rejected unresolved semantic routing instead of silently retrieving.

Evidence: [Chat Routing Refactor](chat-routing-refactor.md)

## Early July 2026: Make the Reading Model Canonical

Parser output, typed paper content, physical pages, reading locations, and visual assets were
consolidated around a product-owned reading model. Search chunks became projections derived from the
current model rather than an independent truth.

Evidence:

- [Minimal System](reading-model/minimal-system.md)
- [Persistence Closure](reading-model/persistence-closure.md)
- [ADR 0003](../adr/0003-use-paper-locations-for-reading-structure.md)
- [ADR 0004](../adr/0004-use-reading-elements-as-canonical-typed-content.md)
- [ADR 0005](../adr/0005-derive-reading-chunks-from-current-reading-model.md)

## July 2026: Evaluate Behavior, Not Only Answers

Golden cases evolved from flat question-answer rows into evidence-first contracts. Candidate, read,
cited, outcome, and hard-pass stages became separately observable. Offline-first schema validation
preceded live model evaluation.

Evidence:

- [ADR 0011](../adr/0011-use-evidence-first-golden-cases-for-harness-eval.md)
- [ADR 0012](../adr/0012-build-golden-schema-runtime-as-offline-eval-first.md)
- [Evaluation System](../evaluation/README.md)

## July 2026: Simplify the Agent Runtime

Stage-heavy orchestration was replaced by a smaller domain layer over the OpenAI Agents SDK. Later
experiments tested evidence-coverage gates, bounded parallel samples, deterministic selection, and a
second provider protocol. Several experiments raised cost or reduced pass rate; those negative
results now guide the next work on evidence selection.

Practice reports:

- [Golden Data and Harness Evolution](https://chzarles.github.io/paperloom/practice/evaluation/golden-data-harness-evolution)
- [Best-of-2 Orchestration Experiment](https://chzarles.github.io/paperloom/practice/evaluation/best-of-two-agent-orchestration)
- [Provider Migration Experiment](https://chzarles.github.io/paperloom/practice/evaluation/provider-migration-experiment)
