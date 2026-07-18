# Evaluation System

PaperLoom evaluates an evidence-bounded agent, not only the wording of its final answer. The
evaluation system asks whether the runtime found the right paper, exposed the right location, read
usable evidence, cited that evidence, chose the right outcome, respected the tool protocol, and did
so at an acceptable cost and latency.

## Evaluation Layers

| Layer | Primary question |
| --- | --- |
| Candidate | Did retrieval expose the required paper and location? |
| Read | Did the Agent inspect the source material needed for the answer? |
| Cited | Did the final answer cite evidence created by that read? |
| Evidence quality | Is the cited span substantive and relevant rather than only a heading or preview? |
| Outcome | Should the system answer, clarify, partially answer, abstain, or report technical failure? |
| Content | Are expected facts and claim obligations satisfied? |
| Trace | Did required observable actions and authorization transitions occur? |
| Efficiency | What did the run cost in calls, tokens, latency, retries, and failures? |

A higher Candidate score does not guarantee a better answer. The remaining failure may be in
location choice, reading, evidence selection, citation coverage, outcome selection, or final
validation.

## Per-Run Capture

Set `EVAL_DUMP_DIR` or pass `--eval-dump DIR` to create a private directory for each run:

```text
<DIR>/<run_id>/events.jsonl
<DIR>/<run_id>/result.json
```

`events.jsonl` is append-only, sequence-numbered, deduplicated by event ID, flushed, and `fsync`ed
after each event. `result.json` is written through a temporary file and atomically replaced after
the run finishes. Capture failure is logged and counted but does not change the product answer.

The recorder currently captures:

| Event group | Recorded data |
| --- | --- |
| Run input | Question, conversation messages, scoped corpus IDs, selected papers, previous evidence, and turn identity |
| Model transport | Sanitized HTTP method, URL, non-secret headers, request body, response body, status, error, and retry attempt |
| Tool execution | Tool name, raw and parsed arguments, start/completion/error, internal result, and model-visible result |
| Authorization state | Disclosed papers, disclosed locations, Evidence IDs, and before/after state snapshots |
| Final submission | Submitted draft, acceptance, validation error, tool-call grouping, and cited evidence |
| Run result | Outcome, answer, Evidence Ledger, citations, trace, token usage, latency, finish reason, and failures |

Authorization, cookie, API-key, and set-cookie headers are removed. Request and response bodies can
still contain user text, paper content, prompts, and model output, so eval dumps must be treated as
sensitive local data.

## Offline Run Artifacts

CLI evaluation can additionally write:

```text
OUT/score_report.json
OUT/<case_id>/harness_run.json
OUT/<case_id>/research_answer.json
OUT/<case_id>/evidence_ledger.json
OUT/<case_id>/citation_validation.json
OUT/<case_id>/react_trace.json
OUT/<case_id>/paper_candidates.json
OUT/<case_id>/conversation_state.json
```

These files separate raw facts from derived scoring. A later analyzer can change the scoring policy
without rerunning every model call, as long as the original observable events and run artifacts were
retained.

## Evidence-First Golden Cases

The Golden Data schema does not require one exact prose answer. It stores obligations that can be
checked across different valid phrasings:

- conversation messages and fixed paper pack;
- required and forbidden papers;
- evidence anchors and required or forbidden evidence;
- expected facts and claim obligations;
- expected outcome and citation policy;
- answer-structure and visible-trace requirements;
- human labels and LLM-judge calibration results.

See:

- [`research/golden-data/README.md`](../../research/golden-data/README.md)
- [`research/golden-data/SCHEMA.md`](../../research/golden-data/SCHEMA.md)
- [`harness_py/README.md`](../../harness_py/README.md)
- [`Golden Data architecture and retrieval evolution review`](golden-data-architecture-and-retrieval-evolution-2026-07-16.md)

## What The Data Can Improve

### Qdrant Retrieval

Candidate queries, returned locations, Qdrant scores, chosen reads, and required anchors can identify
false negatives, over-broad queries, weak section boosts, poor coverage allocation, and cases where
adjacent context should or should not help. This supports measured tuning of the Java/Qdrant product
retriever.

### Dense Retrieval And Reranking

Accepted reads can become positive query-location pairs. Disclosed but skipped or rejected locations
can provide carefully filtered hard negatives. Held-out Golden Cases can then test whether an
embedding retriever, hybrid candidate generator, or reranker improves Candidate, Read, Cited, and
Hard Pass rather than merely offline similarity.

### Agent Tool Policy

Observable tool sequences reveal when the Agent searches too broadly, fails to reformulate, reads too
little, reads repeatedly, cites only one side of a comparison, or submits before coverage is
complete. That data can improve prompts, tool descriptions, budgets, deterministic gates, or a
learned policy model.

### Provider And Cost Routing

Per-run quality, token, latency, retry, and failure data can support routing by question complexity
instead of sending every turn to the most expensive model. Provider migration experiments should
compare behavior and tool-protocol adherence, not only API compatibility.

### Judge Calibration

Human labels and deterministic protocol checks can measure judge agreement, false positives, false
negatives, and threshold stability. The judge remains a calibrated supplement; it does not replace
permission, evidence-ID, citation, and trace validators.

### Distilling A Local Small Model

Strong third-party APIs can act as teachers when their runs pass deterministic gates and, where
needed, human review. Suitable supervision includes:

- the user-visible accepted answer;
- structured outcome and answer fields;
- observable tool calls and arguments;
- tool results available to the teacher;
- selected Evidence IDs and citation placement;
- correction attempts after a rejected submission.

Do not train on hidden chain-of-thought. Distill observable actions and accepted outputs. Before any
training export, filter technical failures and invalid trajectories, deduplicate near-identical
examples, remove secrets and personal data, respect paper and provider licensing, and keep paper,
question, and conversation families separated across train, validation, and test splits.

## Retrieval Benchmark Boundary

The `eval/rag/` directory contains method exploration and scorecards for product paper QA,
literature search, QASPER, LitSearch, parser behavior, page location, and figure/table retrieval.

Important rules:

- benchmark corpora remain outside product paper storage and default product scope;
- parser evaluation and retrieval evaluation are separate experiments;
- structured-text benchmarks should not be forced through OCR only to resemble production input;
- small samples must not be reported as complete benchmark results;
- a new retrieval strategy remains eval-gated until it improves product behavior on held-out cases.

## Public Experiment Reports

Selected experiments are rewritten as engineering-practice articles on the
[project site](https://chzarles.github.io/paperloom/practice/). Reports include negative results,
cost, latency, and failure analysis rather than publishing only successful runs.
