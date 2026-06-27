# Figure And Table Evidence Gate

This gate separates table/figure evidence claims from generic text QA. It is intentionally narrower
than `product-rescue-paper-qa`: the cases here should prove that PaperLoom can answer from table or
figure evidence and keep citation mappings usable for source preview.

## Current Scope

Current committed product evidence supports table-centered smoke cases only. The active product paper
has table chunks, but the strategy audit records `0` figure chunks for the current product fixture.
Therefore:

- table text evidence and citations are runnable now
- table crop availability is guarded by backend reference-mapping tests and the evidence panel state
- figure reference fields and missing-crop fallback are guarded by backend tests
- figure-heavy QA is not promoted until a committed figure-bearing PDF fixture and passing run exist

## Dataset

Seed cases:

```text
eval/rag/figure-table/product-figure-table-smoke.jsonl
```

The current cases are standard `RagBenchmarkCase` rows scoped to the committed product paper:

- `grep_table1_text_citation`: Table 1 evidence must be cited.
- `grep_table2_noise_text_citation`: high-noise/session-limit result table evidence must be cited.

Do not add custom JSON fields to this file unless the runner is updated first; the existing RAG
dataset loader expects the standard benchmark-case schema.

## Runtime Notes

Use the same service-backed paper-QA runner shape as Product Paper-QA slices, with this dataset as
`--cases` and `--dataset-id product-figure-table-smoke`. A passing run proves only the cases present
in this file. It does not prove figure-heavy or multimodal QA until figure cases are added.

Backend unit tests cover the evidence-contract requirements that the JSONL scorer cannot express:

- table references preserve `tableText`, `tableMarkdown`, `tableId`, and crop availability
- figure references preserve `figureId`, caption/snippet text, and crop availability
- text-only figure fallback does not claim visual inspection

## Promotion Rule

PaperLoom can claim table evidence support for the current product slice after the table smoke cases
and reference-mapping tests pass. PaperLoom cannot claim figure-heavy QA or mature multimodal paper
RAG until a committed figure-bearing PDF fixture has passing figure text and figure crop cases.
