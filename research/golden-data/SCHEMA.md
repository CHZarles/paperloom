# Harness Golden Data Schema v3

The active answer-scoring contract is defined by:

- `docs/evaluation/golden-claim-evidence-scoring-v4-proposal-2026-07-20.md`
- `research/golden-data/manifest.yaml`
- `research/golden-data/claims/*.yaml`
- `research/golden-data/cases/*.yaml`

Golden runs use only the Java/Qdrant product corpus. Stable Golden paper IDs are bound to
installation-specific product IDs by `harness-golden-product-corpus-map/v1`.

## Manifest

An active `harness-golden-data/v3` manifest contains `paper_packs`, `claim_files`, and `case_files`.
Every referenced authoring file must remain below `research/golden-data/`.

## Golden Claims

`harness-golden-claims/v1` files contain a mapping of reusable claim IDs:

```yaml
schema_version: harness-golden-claims/v1
claims:
  example_claim:
    statement: The fact the answer must express.
    fact_keys: [optional_typed_fact]
    required_evidence:
      - paper_id: paper_a
        accepted_locations:
          - section_ref_current_product_location
    forbidden_paper_ids: [paper_b]
```

Each `required_evidence` entry is mandatory. `accepted_locations` may contain only current product
`page_ref_`, `section_ref_`, `table_ref_`, or `figure_ref_` values. They are not Anchor IDs, Qdrant
point IDs, or copied source text.

`forbidden_paper_ids` is claim-scoped. A citation from a forbidden paper fails only when it is attached
to an answer block that expresses that claim.

## Cases

Every `harness-golden-case/v3` case ends with a user message and declares:

```yaml
expect:
  outcome: answered
  required_claims: [example_claim]
  facts: {optional_typed_fact: value}
  citations: required
```

The removed v2 `expect.papers`, `expect.evidence`, and inline `expect.claims` fields are rejected.
Exact Anchors in Paper Packs are parser/index audit probes only and never affect v4 answer status.

## Score Reports

`harness-score-report/v4` uses `pass`, `fail`, and `review_required`. It reports resolved pass rate and
review coverage separately. Unknown locations from a required paper produce maintenance candidates;
they are never promoted automatically.

Typed facts are deterministic. Paraphrased claims and additional material claims require an offline
semantic judgment whose exact model and prompt passed both frozen calibration and holdout gates.
Historical v2/v3 runs and score reports remain immutable.

The offline report uses `claim-evidence-semantic-judgment/v1`. For each required claim it records one
strongest self-contained answer block, or a non-expressed verdict. Additional material is judged once
for the whole answer. Calibration labels may list several human-acceptable blocks; a judge may choose
any one of them, while a returned block outside that set is an unsafe false pass.

Every new product `agent-run` directory also contains `run_manifest.json`. It content-addresses the
Golden/product corpus map, observed canonical locations, and retrieval trace, providing one immutable
corpus/index observation without adding paper versions or changing the Java, Qdrant, or MySQL schemas.
