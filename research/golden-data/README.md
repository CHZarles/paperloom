# Harness Golden Data V2

Canonical entry point: `research/golden-data/manifest.yaml`.

Authored data has three parts:

- `manifest.yaml`: paper-pack and case-file index.
- `paper-packs/*.yaml`: paper identities, citation edges, and stable evidence anchors.
- `cases/*.yaml`: message history plus observable outcome, paper, evidence, fact, and citation expectations.

Runtime stages and tool counts are deliberately not golden expectations.
Every authored anchor requires a positive parseable `page`; audit and runtime matching use that same
page constraint. Golden execution uses opaque conversation ids and keeps case, expectation,
paradigm, and authored anchor labels out of model-facing messages and tool payloads.

Commands:

```bash
python3 -m harness_py validate
python3 -m harness_py audit
python3 -m harness_py agent-run --out /tmp/paismart-golden-v2-live
```

`validate` is deterministic scorer validation. `audit` verifies parser coverage. `agent-run` evaluates the real MiniMax-backed harness. Their failures are reported separately.

The Seed-60 files are planning documents, not executable cases.
