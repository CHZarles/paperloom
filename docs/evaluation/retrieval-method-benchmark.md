# Retrieval Method Benchmark Workflow

This workflow evaluates a candidate retrieval change against PaperLoom's current in-memory BM25
baseline without confusing repository availability with product promotion.

## Baseline

The live assistant currently:

1. receives a Java-authorized paper scope;
2. loads paper metadata and Reading Elements directly from MySQL;
3. ranks locations in Python memory with BM25 plus passage, lead, section, phrase, adjacency, and
   coverage heuristics;
4. lets the Agent decide which disclosed locations to read;
5. validates citations and cross-paper coverage at final submission.

A candidate method must be compared with this end-to-end behavior, not only with a standalone
similarity metric.

## Candidate Methods

Examples include:

- a BM25 tokenization or weighting change;
- a new passage or section candidate policy;
- an embedding retriever derived from canonical Reading Elements;
- lexical and dense candidate fusion;
- a cross-encoder or small-model reranker;
- an external retrieval system behind a PaperLoom-shaped adapter.

The Reading Model remains the source of canonical content and provenance. A candidate retrieval
method is a derived projection, not a replacement paper model.

## Evaluation Questions

Every experiment should answer:

1. Does the method expose more required locations at a fixed candidate budget?
2. Does the Agent read and cite those improved candidates?
3. Does Hard Pass improve on held-out cases?
4. Which question, paper, and element types improve or regress?
5. What happens to latency, tokens, model calls, infrastructure cost, and technical failure?
6. Does the method preserve the authorization ladder and exact source reopening?

## Data Boundary

- Product papers and benchmark corpora remain separate.
- A benchmark adapter may reuse corpus and tool code but cannot insert eval papers into a user's
  library or default scope.
- Train, validation, and test splits must be isolated by paper and question family when a learned
  retriever or reranker is involved.
- Parser quality, retrieval quality, and Agent policy should be reported separately before an
  aggregate result is interpreted.

## Required Runs

Use at least:

- deterministic fixture tests for tool authorization and candidate ordering;
- the relevant evidence-first Golden Cases;
- a held-out set not used for tuning;
- real PDF cases representing text-heavy, table-heavy, figure-heavy, formula-heavy, and multi-paper
  questions when the method claims to help them;
- a product smoke run through Java, Python, final validation, persistence, and reference reopening.

Small exploratory samples must be labeled as such and must not be reported as complete benchmark
results.

## Metrics

Report raw counts and rates for:

| Dimension | Examples |
| --- | --- |
| Retrieval | Required-paper coverage, required-anchor Candidate coverage, recall at the actual tool budget |
| Agent behavior | Read coverage, Cited coverage, reformulation rate, duplicate reads, submission corrections |
| Answer | Outcome accuracy, expected facts, claim obligations, citation policy, Hard Pass |
| Efficiency | Candidate count, model calls, tool calls, prompt/completion tokens, wall time, provider cost |
| Reliability | Parser gaps, tool errors, model retries, timeouts, cancellations, technical failures |

Similarity-only metrics may be included for diagnosis, but they cannot decide promotion by
themselves.

## Artifacts

Retain enough facts to reproduce the comparison:

- method configuration and code revision;
- corpus and split manifest;
- per-case `harness_run`, answer, Evidence Ledger, citation validation, trace, and candidates;
- optional `events.jsonl` and `result.json` transport capture;
- aggregate score report;
- a Markdown report with improvements, regressions, cost, and unresolved questions.

Do not publish sensitive eval dumps without removing user content, secrets, licensed paper text, and
provider-specific restricted data.

## Promotion Gate

A candidate enters the live assistant path only when it:

1. preserves Java-authorized scope and paper/location disclosure rules;
2. preserves exact retained evidence and historical reference reopening;
3. improves or maintains held-out Candidate, Read, Cited, and Hard Pass;
4. has acceptable latency, cost, and operational complexity;
5. does not hide regressions behind a larger candidate or model-call budget;
6. passes product smoke and updates maintained architecture documentation.

If the candidate only improves offline recall, keep it as an experiment until the Agent and final
answer also benefit.
