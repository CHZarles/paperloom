# Golden Data V2 Live Baseline

Date: 2026-07-11
Status: Complete live measurement, no harness changes made
Provider: MiniMax-M3 through `https://api.minimaxi.com/v1`
Dataset: `harness_golden_seed`, 12 cases, 5 papers, 7 authored anchors

## Execution Notes

The first all-case command stopped before writing case artifacts because MiniMax returned HTTP 529
`overloaded_error`. The cases were then run independently so one transport failure could not discard
the rest of the baseline.

The per-case wrapper retried every non-zero CLI exit. This matters because `agent-run` exits non-zero
for an ordinary Golden hard failure as well as for an execution error. Consequently, the nine failing
cases were sampled three times. Every per-case attempt produced a score report; there were no
per-case transport failures. The first attempt is the primary baseline, while the additional attempts
show whether a failure was persistent.

Artifacts:

```text
/tmp/paismart-golden-live-20260711-cases
```

## Headline Result

- First-attempt hard pass: **3/12**
- Failing cases that recovered in two additional samples: **0/9**
- Total sampled runs: **30**
- Total hard passes across all samples: **3/30**

The three passing cases were:

1. `transformer_to_bert_genealogy_001`
2. `adam_beta2_conflict_001`
3. `gpt3_to_transformer_multihop_001`

## First-Attempt Dimensions

| Dimension | Pass | Fail | Not applicable |
|---|---:|---:|---:|
| Outcome | 9 | 3 | 0 |
| Retrieval | 6 | 4 | 2 |
| Content | 0 | 5 | 7 |
| Grounding | 6 | 5 | 1 |

The content result is the strongest blocker: every case with authored structured facts failed that
dimension.

## Case Results

| Case | First attempt | Repeated result | Main observation |
|---|---|---|---|
| `transformer_adam_params_001` | Fail | 0/3 pass | One sample found and cited the exact passage and wrote the correct prose, but `answer.fields` remained empty. Two samples ended incomplete without evidence. |
| `attention_paper_ambiguous_001` | Fail | 0/3 pass | Two samples converted the ambiguous phrase into a four-paper recommendation instead of asking a clarification; one sample ended technically incomplete. |
| `gpt5_architecture_boundary_001` | Fail | 0/3 pass | The harness did not emit `outcome=abstained`; two samples had no outcome and one emitted `partial`. |
| `bert_transformer_role_001` | Fail | 0/3 pass | Correct BERT evidence was found. The model emitted semantically useful fields such as `architectural_role`, but not the Golden key `transformer_role`. |
| `bert_vs_transformer_comparison_001` | Fail | 0/3 pass | Both papers were often selected, but the evidence set did not cover all three required comparison anchors. |
| `transformer_to_bert_genealogy_001` | Pass | Not retried | Correct direct relationship, evidence, and citation. |
| `adam_beta2_conflict_001` | Pass | Not retried | Correctly separated Adam's recommended default from the Transformer paper's experiment setting. |
| `transformer_optimizer_reproduction_001` | Fail | 0/3 pass | Correct source passage was retrieved, but all four structured fields were absent. |
| `gpt3_to_transformer_multihop_001` | Pass | Not retried | Correct two-hop chain and no invented direct GPT-3-to-Transformer citation. |
| `bert_choice_followup_001` | Fail | 0/3 pass | The conversation usually resolved to BERT, but evidence selection was unstable and `transformer_role` was always absent. |
| `transformer_bert_confirmation_followup_001` | Fail | 0/3 pass | The requested comparison was understood, but BERT directionality and masked-LM anchors were not both accepted and cited. |
| `adam_constraint_refinement_followup_001` | Fail | 0/3 pass | The refinement correctly selected the Transformer value and evidence, but `beta2` was absent from `answer.fields`. |

## Persistent Failure Signals

Across all 30 sampled runs:

| Error family | Count |
|---|---:|
| `FACT_MISSING` | 30 |
| `REQUIRED_ANCHOR_MISSING` | 18 |
| `REQUIRED_ANCHOR_NOT_CITED` | 18 |
| `OUTCOME_MISMATCH` | 10 |
| `REQUIRED_PAPER_MISSING` | 4 |
| `CITATIONS_REQUIRED` | 3 |
| `CITATIONS_FORBIDDEN` | 2 |

## Interpretation

The baseline does not suggest a general inability to perform research. The strongest cases are
genealogy, contradiction arbitration, and explicit multi-hop reasoning. The failures cluster around
three contracts:

1. **Structured answer fields:** correct prose and evidence do not reliably become stable field keys.
2. **Decision outcomes:** ambiguity and knowledge-boundary turns do not reliably produce
   clarification or abstention outcomes.
3. **Evidence coverage:** comparison and history-refinement turns often select the right paper but do
   not collect the complete minimal evidence set.

These are harness-level signals. Expanding the corpus before recording them would make later failures
harder to attribute.

## Baseline Rule For Future Runs

- Run each case once for the primary score.
- Retry only transport failures that did not produce a score report.
- Do not retry an ordinary Golden hard failure as though it were an API failure.
- Keep provider, model, dataset commit, and output path with every report.
- Compare dimension deltas and terminal-chat quality, not only the aggregate hard-pass count.
