# LLM-Agent Evaluation Golden Pack Baseline

Date: 2026-07-13
Provider: MiniMax-M3
Dataset: `harness_golden_seed`
Base commit: `367d9a34e120ce2d2f6d2b1d36cec1db589d3915`

## Expansion

- Added 9 pinned papers, 22 authored anchors, 2 verified citation edges, and 15 cases.
- The expanded manifest contains 14 papers, 29 anchors, and 30 cases.
- Coverage includes 14 of the 22 paradigms in `research/remake.md`.
- All four outcomes are represented: `answered`, `needs_clarification`, `abstained`, and `partial`.
- Six cases contain multi-turn history, including ordinal selection and a topic change.

## Dataset Health

- Deterministic fixture validation: 30/30 cases passed.
- Anchor audit: 29/29 anchors matched exactly one page-constrained reading element.
- Asset inventory: 9/9 pinned PDFs and reading models generated without missing-paper warnings.
- The stable seed manifest remains unchanged, while the expanded manifest keeps the original
  five-paper cases scoped to their original pack.
- Focused Golden V2 tests: 26/26 passed. No broader unrelated suite was run.

## Pre-Expansion Baseline

The immediately preceding live reports already covered all 15 original cases with the same harness
and model, so they were merged instead of issuing duplicate model calls:

- original behavior set: 10/12 hard pass
- corpus-metadata set: 3/3 hard pass
- merged baseline: 13/15 hard pass

Merged report:
`research/golden-data/validation-runs/2026-07-13-pre-expansion-merged/score_report.json`.

Structured fields marked `not_run` remain explicitly unproven rather than being interpreted as
content passes.

## First Live Run

The new 15-case pack was run once. Ordinary Golden failures were not retried.

| Dimension | Pass | Fail | Not run | N/A |
| --- | ---: | ---: | ---: | ---: |
| Outcome | 14 | 1 | 0 | 0 |
| Retrieval | 4 | 10 | 0 | 1 |
| Content | 0 | 0 | 3 | 12 |
| Grounding | 5 | 10 | 0 | 0 |

Hard pass: **5/15**.

Run diagnostics: 700,345 ms total duration, 696,441 ms model latency, 130 model calls, 160 tool
calls, and 1,844,861 total reported tokens.

The dominant failure is incomplete evidence coverage rather than paper identity resolution:

- `REQUIRED_ANCHOR_NOT_CITED`: 17
- `REQUIRED_ANCHOR_MISSING`: 16
- `REQUIRED_PAPER_MISSING`: 5
- `CITATIONS_REQUIRED`: 1
- `OUTCOME_MISMATCH`: 1

The partial-outcome case exposed a concrete behavior gap: the harness ranked incompatible benchmark
percentages instead of returning a bounded partial answer.

Artifacts: `research/golden-data/validation-runs/2026-07-13-llm-agent-evaluation-v1/`.

## Human Labels And Judge

- Calibration labels: 6 cases.
- Blind holdout labels: 9 cases.
- Calibration agreement: 6/6 overall, 6/6 grounding, zero false passes.
- Holdout agreement after retrying one judge protocol error: 9/9 overall, 8/9 grounding, zero false
  passes, and zero technical errors.
- The plan gate of at least 85% overall and grounding agreement with zero false passes is satisfied.

The judge still disagrees on task-fulfillment severity in three holdout cases. Overall and grounding
are suitable for the current release signal; task-fulfillment disagreement remains visible rather
than being folded into a composite score.

Merged holdout report:
`research/golden-data/judge-calibration/llm-agent-evaluation/2026-07-13-holdout-v1-merged/agreement_report.json`.

## Manual Behavior Set

The live run includes the five planned natural behavior checks without additional duplicate runs:

- comparison: `mint_vs_tau_interaction_comparison_001`
- recommendation: `agent_evaluation_recommendation_001`
- clarification: `agent_benchmark_ambiguous_001`
- knowledge boundary: `cross_benchmark_score_ranking_partial_001`
- topic change: `benchmark_topic_change_followup_001`

The failures remain useful harness signals. They are not parser, manifest, missing-asset, or transport
failures.
