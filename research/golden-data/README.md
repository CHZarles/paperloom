# Harness Golden Data

This directory contains the runnable source-of-truth smoke dataset for the evidence-first Golden
Case schema.

Canonical entry point:

```text
research/golden-data/manifest.yaml
```

Planning and authoring aids:

```text
research/golden-data/artifact-contracts.yaml
research/golden-data/seed-60-selection.yaml
research/golden-data/paper-packs/seed-60-paper-pack-manifest.yaml
```

Commands after implementation:

```bash
mvn -q -Dtest=GoldenDatasetCommittedDataTest test
mvn -q -Dtest=GoldenDatasetCliTest test
```

Generated compatibility JSONL is not canonical. Regenerate it from `manifest.yaml` with the golden
dataset CLI when a downstream flat `RagBenchmarkCase` runner needs it.
