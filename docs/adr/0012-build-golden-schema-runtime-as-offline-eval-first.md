# Build Golden Schema runtime as offline eval first

PaperLoom will make the Golden Case schema runnable first as an offline harness package that loads
YAML from `research/golden-data`, validates the authored data, and scores committed Harness Run
Trace fixtures. This keeps the richer schema executable without changing product chat behavior,
while leaving a clear path to connect live Product Reading traces to the same scorer.
