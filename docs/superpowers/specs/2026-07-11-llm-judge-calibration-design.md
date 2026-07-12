# Lightweight LLM Judge Calibration Proposal

Date: 2026-07-11
Status: Implemented and calibrated on the four-case development set

## Decision

Add one LLM-based semantic judge for Golden harness runs. The judge replaces exact field-name and
exact anchor matching as the primary user-quality signal, while retaining a small deterministic
preflight for malformed files and citation references.

The judge is calibrated against the four human-labelled runs in
`research/golden-data/human-labels.yaml`. It does not inspect harness stages, tool counts,
`answer_type`, private field names, exact Golden anchor IDs, or hidden reasoning.

## Review Contract

Each run is judged on three independent dimensions:

```text
decision             correct action: answer, clarify, or state a corpus boundary
task_fulfillment     factually correct and complete execution of that action
grounding            material claims supported by cited corpus passages
```

`overall` is derived rather than generated:

```text
overall = pass
only when decision=pass
and task_fulfillment=pass
and grounding in {pass, not_applicable}
```

The judge receives the same review substance as the human annotator:

- user request;
- human-authored expected-behavior rubric;
- optional fixed corpus inventory;
- user-visible answer content and status;
- cited evidence excerpts;
- deterministic citation-integrity errors, if any.

Human labels and annotation notes are never included in the model prompt.

## Architecture

```text
human-labels.yaml
       |
       v
CalibrationLoader -- validates labels, answer files, evidence files, and citation references
       |
       v
JudgePacket -- removes private trace data and keeps only reviewable content
       |
       v
LLMJudge -- one required structured tool call per run
       |
       v
AgreementReport -- compares judge dimensions with fixed human dimensions
```

The external interface is one command:

```bash
python3 -m harness_py judge-calibrate \
  --labels research/golden-data/human-labels.yaml \
  --out eval/rag/runs/llm-judge-calibration
```

The command uses the existing provider configuration source, defaulting to the active product DB
provider. It writes `agreement_report.json` and exits:

- `0` when every case agrees on all dimensions;
- `1` when semantic disagreements remain;
- `2` when a judge call or calibration artifact fails technically.

## Judge Protocol

The judge makes exactly one semantic model call for each valid calibration run. It must call a
single `submit_judgment` tool with:

```json
{
  "decision": "pass",
  "task_fulfillment": "pass",
  "grounding": "pass",
  "grounding_issues": [],
  "rationale": "Optional concise explanation of the decisive rubric and evidence checks."
}
```

There is no prose-JSON fallback, fuzzy parser, self-critique loop, judge ensemble, majority vote, or
case-specific rule. Transport retries remain the responsibility of the existing provider client.

The normal judge prompt requires the model to:

- use only the supplied corpus and passages;
- evaluate dimensions independently;
- accept equivalent evidence passages;
- apply strict clause-level citation completeness and list unsupported claims in `grounding_issues`;
- reject unsupported claims, including ancillary and unnecessary extra claims, without a
  "minor inference" exception;
- use `not_applicable` only for a response with no research claim;
- ignore private enums and runtime structure;
- optionally provide a concise audit rationale.

## Agreement Report

The report records:

- model and prompt version;
- per-case human and judge dimensions;
- per-dimension match flags;
- full-case and overall agreement counts;
- false passes and false failures;
- technical judge errors;
- one `accepted` result.

Calibration is accepted only when all labelled cases match on every dimension and there are no
technical errors. The initial target is four of four full matches with zero false passes.

## Calibration Discipline

The four current labels are the development calibration set. Prompt changes must remain generic and
must not mention case IDs, paper IDs, expected labels, or known verdicts. Once the prompt matches the
four cases, freeze its version before labelling and running the remaining eight Golden runs as a
holdout set.

Using MiniMax-M3 to judge MiniMax-M3 outputs is acceptable for harness exploration, but the report
must identify the judge model. Scores remain provisional until an independent judge model is used.

## Non-Goals

- No harness behavior changes in this slice.
- No replacement of citation-ID integrity checks with an LLM.
- No scoring of stages, tools, paradigms, `answer_type`, or private fields.
- No automatic prompt optimization from human labels.
- No multiple judges, confidence routing, or retry-based voting.
- No Java execution.

## Acceptance Evidence

Implementation is complete when:

1. committed calibration labels load without exposing judgments to the judge packet;
2. malformed labels and invalid structured judge responses are rejected;
3. agreement accounting distinguishes false passes, false failures, and dimension disagreements;
4. the CLI writes a reproducible agreement report containing provider and prompt metadata;
5. all Python harness tests pass;
6. one live four-case calibration report is produced and inspected.

## Implementation Result

The implementation is complete in `harness_py/judge.py` and exposed through
`python3 -m harness_py judge-calibrate`. Prompt version `llm-judge/v5` reached full agreement on all
four human-labelled runs:

```text
full agreement       4/4
decision agreement   4/4
task agreement       4/4
grounding agreement  4/4
false passes          0
false failures        0
technical errors      0
```

The durable result is recorded in `docs/qa/llm-judge-calibration-2026-07-11.md` and
`docs/qa/llm-judge-calibration-2026-07-11.json`. The prompt is now frozen for the next eight-case
holdout evaluation.
