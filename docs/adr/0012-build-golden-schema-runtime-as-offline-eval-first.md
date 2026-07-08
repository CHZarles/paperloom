# Build Golden Schema runtime as offline eval first

PaperLoom will make the Golden Case schema runnable first as a test-scoped offline eval package
that loads YAML from `research/golden-data`, validates the authored data, scores committed Harness
Run Trace fixtures, and exports compatibility `RagBenchmarkCase` JSONL. This keeps the richer
schema executable without changing product chat behavior or eval database tables, while leaving a
clear path to later connect live Product Reading traces to the same scorer.
