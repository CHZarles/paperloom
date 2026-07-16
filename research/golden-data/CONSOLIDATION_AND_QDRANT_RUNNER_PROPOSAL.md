# Golden Data Consolidation And Product-Qdrant Runner Proposal

Date: 2026-07-16
Status: implemented by the accompanying change

Accepted decision: [`ADR 0013`](../../docs/adr/0013-consolidate-golden-data-and-run-product-qdrant-evals.md)

## Decision

Use `research/golden-data/` as the only Golden Data management root. Corpus assets, authoring YAML,
evaluation configuration, saved baselines, adjudication, and operator documentation live below this
directory. Product and Harness source code remain in their normal modules; generated local runs remain
ignored.

Extend the existing `agent-run` Golden runner with an explicit `java-qdrant` corpus backend. This
backend keeps the Golden case, model provider, Agents SDK loop, scorer, and output artifacts, but routes
all paper search, location search, and exact reads through the same product boundary used in production:

```text
Golden case and logical paper IDs
-> Golden/product paper ID adapter
-> Python JavaCorpusGateway
-> Java Corpus API
-> Query Embedding
-> Qdrant dense/sparse retrieval and fusion
-> Current Reading Model generation validation
-> MySQL canonical location hydration
-> Golden Anchor matching and normal Harness scoring
```

There is no in-memory retrieval fallback in this profile. A missing paper mapping, Java failure,
Qdrant failure, stale generation, or MySQL hydration failure remains visible as a setup or technical
failure.

## Consolidated Layout

```text
research/golden-data/
  README.md
  SCHEMA.md
  CONSOLIDATION_AND_QDRANT_RUNNER_PROPOSAL.md
  manifest.yaml
  manifest-expanded.yaml
  artifact-contracts.yaml
  product-corpus-map.example.yaml
  paper-packs/
  cases/
  corpora/
    transformer-bert-gpt/
      pdfs/
      parser-artifacts/
      reading-models/
      text/
      visual-assets/
    llm-agent-evaluation/
      pdfs/
      reading-models/
      text/
  validation-runs/
  human-adjudication/
  judge-calibration/
  local-runs/                # ignored
```

`Paper Pack.data_dir` is relative to the directory containing the Golden manifest. The loader and
dataset fingerprint tools use this one rule. They no longer prepend `data/golden` or depend on a pack
escaping back into `research/golden-data` with `../..` segments.

Large regenerable PDF, parser, text, audit, and visual assets may remain ignored locally. Their
canonical Reading Model exports and authoring contracts continue to follow the repository's existing
tracking policy. Physical co-location and Git tracking are separate concerns.

## Product Paper Identity Mapping

Golden paper IDs are stable evaluation identities. Product paper IDs are installation-specific IDs
created by the upload and persistence path. The runner must not assume they are equal.

A local mapping file binds the two identities:

```yaml
schema_version: harness-golden-product-corpus-map/v1
dataset_id: harness_golden_seed
user_id: 1
papers:
  attention_is_all_you_need_2017: <product-paper-id>
  adam_2014: <product-paper-id>
```

The mapping must cover every paper in every selected case's fixed Paper Pack. This preserves the
authored corpus scope instead of silently narrowing a case to the papers currently available in the
product database. Product IDs must also be unique so one product paper cannot impersonate two Golden
papers.

The committed file is an example. Operators create the ignored
`research/golden-data/product-corpus-map.local.yaml` for their local or controlled test environment.

## Runner Interface

The historical in-memory baseline remains the default:

```bash
.venv-harness/bin/python -m harness_py \
  --manifest research/golden-data/manifest.yaml \
  agent-run \
  --corpus-backend golden-memory \
  --case-id transformer_adam_params_001 \
  --out research/golden-data/local-runs/stable-memory
```

The product-aligned Qdrant profile is explicit:

```bash
.venv-harness/bin/python -m harness_py \
  --manifest research/golden-data/manifest.yaml \
  agent-run \
  --corpus-backend java-qdrant \
  --product-corpus-map research/golden-data/product-corpus-map.local.yaml \
  --case-id transformer_adam_params_001 \
  --eval-dump research/golden-data/local-runs/stable-qdrant-eval \
  --out research/golden-data/local-runs/stable-qdrant
```

`JAVA_CORPUS_BASE_URL` and `RESEARCH_HARNESS_INTERNAL_TOKEN` keep their existing product-service
meaning. The mapping's `user_id` is sent to Java, and Java remains responsible for authorization,
Current Model selection, Qdrant retrieval, and canonical reads.

An opt-in wiring smoke exercises the real Java/Qdrant/MySQL search and read boundary without spending
model tokens:

```bash
GOLDEN_QDRANT_LIVE_SMOKE=1 \
GOLDEN_PRODUCT_CORPUS_MAP=research/golden-data/product-corpus-map.local.yaml \
.venv-harness/bin/python -m unittest \
  harness_py.tests.test_product_runner.ProductRunnerTest.test_real_java_qdrant_mysql_product_chain
```

## Scoring Contract

The adapter translates product paper IDs back to Golden paper IDs before the model and scorer observe
results. After `read_locations`, it matches the hydrated canonical text against authored Anchors using
the same positive-page and normalized-phrase rules as the offline corpus tools.

This permits the existing `BehaviorScorer` to compare the product path with historical Golden runs.
The limitation remains explicit: `hard_pass` is exact Anchor conformance, not human answer accuracy.
Qdrant experiments should continue to report exact-Anchor and adjudicated equivalent-evidence results
separately.

## Acceptance Criteria

1. No executable Golden path depends on `data/golden`.
2. Both Paper Packs resolve Reading Models below `research/golden-data/corpora/`.
3. Stable and expanded validation pass all 10 and 24 retrieval cases; Anchor audits retain their
   existing 7 and 29 Anchor counts.
4. `agent-run --corpus-backend java-qdrant` uses `JavaCorpusGatewayReader` for every selected case.
5. Logical/product paper IDs are translated in requests, candidates, locations, and Evidence Ledger
   items without changing Golden case contracts.
6. Product reads can receive exact Golden Anchor IDs and pass the existing scorer.
7. Missing, duplicate, or wrong-dataset mappings fail before model calls.
8. The default in-memory Golden baseline remains backward compatible.
