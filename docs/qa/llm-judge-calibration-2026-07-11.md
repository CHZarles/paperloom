# LLM Judge Calibration Result

Date: 2026-07-11
Status: Accepted on the four-case development calibration set

## Command

```bash
python3 -m harness_py judge-calibrate \
  --labels research/golden-data/human-labels.yaml \
  --out eval/rag/runs/llm-judge-calibration
```

Durable JSON report:

`docs/qa/llm-judge-calibration-2026-07-11.json`

## Result

| Measure | Result |
|---|---:|
| Labelled runs | 4 |
| Full three-dimension agreement | 4/4 |
| Decision agreement | 4/4 |
| Task-fulfillment agreement | 4/4 |
| Grounding agreement | 4/4 |
| Overall agreement | 4/4 |
| False passes | 0 |
| False failures | 0 |
| Technical judge errors | 0 |
| Accepted | yes |

Judge configuration:

- Provider: MiniMax
- Model: MiniMax-M3
- Prompt version: `llm-judge/v5`
- Provider source: active product DB `llm` configuration
- Semantic calls: one required `submit_judgment` call per run

An immediately preceding full v5 calibration also reached 4/4, after the prompt was changed to keep
decision, task fulfillment, and grounding independent and to enforce strict clause-level citation
completeness.

## Calibration Cases

| Case | Human overall | Judge overall | Important signal |
|---|---|---|---|
| `bert_transformer_role_001` | pass | pass | Semantically correct answer is not penalized for a private field-name mismatch. |
| `bert_vs_transformer_comparison_001` | fail | fail | Correct prose still fails grounding when cited passages do not support named loss or objective details. |
| `attention_paper_ambiguous_001` | fail | fail | The judge separates the wrong answer-vs-clarify decision from unsupported recommendation details. |
| `gpt5_architecture_boundary_001` | pass | pass | A clear corpus-boundary answer passes despite the private `partial` outcome enum. |

## Implemented Shape

```text
human-labels.yaml
-> validated answer and evidence artifacts
-> private-field-free judge packet
-> one structured LLM judgment
-> deterministic overall derivation
-> human/judge agreement report
```

The judge prompt never receives human verdicts or annotation notes. It also excludes `answer_type`,
private answer fields, exact anchor tags, stage traces, tool counts, and internal location IDs.

## Remaining Limitation

This is a development calibration, not a general judge-validity claim. MiniMax-M3 is judging outputs
created by MiniMax-M3, and only four labelled runs are included. The next valid measurement is to
human-label the remaining eight runs without looking at judge output, freeze prompt v5, and run those
eight once as a holdout set.
