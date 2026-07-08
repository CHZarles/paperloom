# Use evidence-first Golden Cases for harness eval

PaperLoom will treat the richer Golden Case schema as the source of truth for research-harness
evaluation, with flat `RagBenchmarkCase` JSONL rows generated only as compatibility projections.
This is a deliberate trade-off: authoring is heavier than answer-only QA, but it preserves the
evidence anchors, claim graph, answer contract, and trace obligations needed to evaluate an
auditable paper-research harness rather than a fluent citation bot.
