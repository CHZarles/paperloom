# Retrieval Method Benchmark Workflow

## Status

Draft. This workflow is being grilled and is not implementation-ready yet.

## Purpose

Evaluate a new retrieval method against PaperLoom's existing RAG benchmark suite and record the
results using the existing benchmark ledger conventions.

The workflow does not create a new benchmark by default. It treats the current benchmark suite as
the source of truth:

- `product-rescue-paper-qa`
- `qasper-dev-200`
- `litsearch-full`
- `litsearch-service-slice-k5`
- any future benchmark registered in `eval/rag/harnesses.yaml`

The first intended run of this workflow is:

```text
Method: VectifyAI/PageIndex
Question: Does PageIndex outperform the current routed retrieval strategy on PaperLoom benchmarks?
```

## Trigger

Event-triggered: run when a candidate retrieval method is proposed for evaluation.

Examples:

- A user proposes a GitHub project, paper, API, or local algorithm.
- A local retrieval change needs to be compared against the current strategy.
- A benchmark result suggests a specific retrieval bottleneck that needs an ablation.

## Current Decision

This workflow is long-lived and reusable. PageIndex is only the first method instance.

PaperLoom already has benchmark definitions and scorecard conventions. This workflow must reuse
those rather than inventing a separate evaluation format.

## Open Questions

1. Which benchmark subset is mandatory for every method?
2. What result threshold is enough to say a method is better than the current strategy?
3. Should external methods be tested through their original runtime, a local reimplementation, or a
   PaperLoom-shaped adapter?
4. What artifacts must be written for each run beyond `run.json`, `scorecard.json`, and `report.md`?

