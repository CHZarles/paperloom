# Harness Golden Data Schema

The canonical behavior-first v2 design is summarized by:

- `docs/adr/0011-use-evidence-first-golden-cases-for-harness-eval.md`
- `docs/adr/0012-build-golden-schema-runtime-as-offline-eval-first.md`
- `research/golden-data/README.md`

The executable examples are:

- `research/golden-data/manifest.yaml`
- `research/golden-data/paper-packs/transformer-bert-gpt.yaml`
- `research/golden-data/cases/core.yaml`

All Paper Pack `data_dir` values are relative to `research/golden-data/`, and corpus assets live under
`research/golden-data/corpora/<pack-id>/`. Product-aligned Golden runs use the separate
`harness-golden-product-corpus-map/v1` identity map described in
`research/golden-data/CONSOLIDATION_AND_QDRANT_RUNNER_PROPOSAL.md`.

V1 intent, retrieval-plan, trace-obligation, and compatibility-projection fields are no longer supported by the Python harness.
