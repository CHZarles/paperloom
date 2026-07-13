# Harness Golden Data V2

This directory is the offline evaluation workspace for the Python research harness. Runtime code
should only produce a complete run artifact and, when requested, persist raw ordered eval events.
Scoring, auditing, judge calibration, comparison, and research analysis happen here or in other
offline tooling.

Run every command below from the repository root, `/home/charles/PaiSmart`.

## Manifests

| Manifest | Scope | Current size | Use it for |
| --- | --- | --- | --- |
| `research/golden-data/manifest.yaml` | Original `transformer-bert-gpt` pack | 5 papers, 15 cases | Stable regression gate for the old Golden data |
| `research/golden-data/manifest-expanded.yaml` | Original pack plus `llm-agent-evaluation` | 14 papers, 30 cases | Broader research and benchmark coverage |

Keep the stable manifest small and backward-compatible. Add exploratory packs and cases to the
expanded manifest first; promote them into the stable gate only after their data, anchors, and
expected behavior are settled.

Authored data is split into:

- `manifest*.yaml`: indexes of paper packs and case files.
- `paper-packs/*.yaml`: paper identities, citation edges, parser/model paths, and stable evidence anchors.
- `cases/*.yaml`: conversation history plus observable outcome, retrieval, content, and citation expectations.
- `human-labels*.yaml`: fixed human judgments over saved live runs for judge calibration.
- `validation-runs/`: committed or local baseline run artifacts used by offline review.
- `judge-calibration/`: saved judge-calibration reports and analysis.

Runtime stages, skill choice, tool sequence, tool count, and exact prose are deliberately not Golden
expectations. Every authored evidence anchor requires a positive parseable `page`; audit and runtime
matching call the same helpers in `harness_py/corpus/pages.py`, so a readability refactor must not
change one side independently. Case IDs, expectations, paradigm labels, and authored
anchor quality signals are never exposed to the model.

## Expanded Corpus Assets

The `llm-agent-evaluation` pack keeps generated assets under
`research/golden-data/corpora/llm-agent-evaluation/` so the research workspace is self-contained.
Rebuild its pinned PDFs, extracted text, reading models, and asset inventory with:

```bash
python3 research/golden-data/build_llm_agent_assets.py
```

Run deterministic validation and audit after rebuilding. Do not treat regenerated files as valid
merely because the build script completed.

## 1. Deterministic Fixture Validation

`validate` loads the manifest, emits deterministic fixture runs in memory, and scores them against
the authored expectations. It does not call MiniMax and does not test the real Agents SDK loop.

Stable old Golden data:

```bash
python3 -m harness_py validate
```

Expanded data:

```bash
python3 -m harness_py \
  --manifest research/golden-data/manifest-expanded.yaml \
  validate
```

The command prints a score report to stdout and exits with code 0 only when `failed_count` is zero.
At the current revision the expected summaries are:

```text
stable:   case_count=15, passed_count=15, failed_count=0
expanded: case_count=30, passed_count=30, failed_count=0
```

Use this first after editing manifests, packs, cases, schemas, fixture generation, or deterministic
scoring.

## 2. Anchor And Parser Audit

`audit` verifies that each authored evidence anchor resolves against the referenced parsed reading
model on the authored positive page. It does not call the model.

Stable old Golden data:

```bash
python3 -m harness_py audit \
  --out /tmp/paismart-stable-anchor-audit.json
```

Expanded data:

```bash
python3 -m harness_py \
  --manifest research/golden-data/manifest-expanded.yaml \
  audit \
  --out /tmp/paismart-expanded-anchor-audit.json
```

The JSON report is written exactly to `--out`. At the current revision the expected summaries are:

```text
stable:   anchor_count=7, passed_count=7, failed_count=0
expanded: anchor_count=29, passed_count=29, failed_count=0
```

Use this after changing anchors, page numbers, parser output, corpus asset paths, or regenerated
reading models. A passing fixture validation does not imply a passing anchor audit.

## 3. Live Agents SDK Execution

`agent-run` runs the actual `LiveResearchChatHarness` through MiniMax and the default OpenAI Agents
SDK runtime. This is the only Golden command that tests model orchestration, tool use, final answer
validation, and live evidence grounding together.

Install the pinned environment first:

```bash
python3 -m venv .venv-harness
.venv-harness/bin/python -m pip install -r harness_py/requirements.lock
```

Run one stable case:

```bash
.venv-harness/bin/python -m harness_py agent-run \
  --case-id transformer_adam_params_001 \
  --runtime agents_sdk \
  --out /tmp/paismart-stable-live
```

Run selected expanded cases by repeating `--case-id`:

```bash
.venv-harness/bin/python -m harness_py \
  --manifest research/golden-data/manifest-expanded.yaml \
  agent-run \
  --case-id agentbench_environment_inventory_001 \
  --case-id gaia_dataset_gap_001 \
  --runtime agents_sdk \
  --eval-dump /tmp/paismart-expanded-eval \
  --out /tmp/paismart-expanded-live
```

Omit `--case-id` to run every case in the selected manifest. Unknown case IDs exit with code 2.
`validate` and `audit` intentionally operate on the complete selected manifest; case-level selection
is supported only for live `agent-run`.

Provider configuration defaults to `--provider-source db`. Use `--provider-source env` when running
against `MINIMAX_API_BASE_URL`, `MINIMAX_API_KEY`, and `MINIMAX_MODEL` from the environment.

Live hard-score failures are not the same as fixture or anchor failures. Model behavior can vary, so
start with one or two representative cases and inspect the saved run before spending tokens on a
whole-manifest execution.

## 4. Optional LLM-Judge Calibration

The deterministic scorer handles observable structure and grounding. Natural answer quality and
semantic criteria are evaluated separately by a calibrated LLM judge against fixed human labels.
Judge calibration is optional for ordinary harness development and must not become part of the live
answer path.

Calibration set:

```bash
.venv-harness/bin/python -m harness_py judge-calibrate \
  --labels research/golden-data/human-labels-llm-agent-evaluation.yaml \
  --provider-source env \
  --out /tmp/paismart-judge-calibration
```

Holdout set, after the judge prompt and decision rules are frozen:

```bash
.venv-harness/bin/python -m harness_py judge-calibrate \
  --labels research/golden-data/human-labels-llm-agent-evaluation-holdout.yaml \
  --provider-source env \
  --out /tmp/paismart-judge-holdout
```

Each labels file points to saved answer and evidence artifacts. Do not overwrite those baseline runs
without re-reviewing the human labels. The command writes `agreement_report.json`, exits with code 2
on technical judge errors, and exits with code 1 when the configured agreement threshold is not
accepted.

## Artifacts

`agent-run --out OUT` writes offline-readable case artifacts:

```text
OUT/score_report.json
OUT/<case_id>/harness_run.json
OUT/<case_id>/research_answer.json
OUT/<case_id>/evidence_ledger.json
OUT/<case_id>/citation_validation.json
OUT/<case_id>/skills_used.json
OUT/<case_id>/react_trace.json
OUT/<case_id>/paper_candidates.json
```

`--eval-dump EVAL_DIR` adds the raw runtime journal:

```text
EVAL_DIR/<run_id>/events.jsonl
EVAL_DIR/<run_id>/result.json
```

The JSONL file is ordered by `sequence` and deduplicated by `event_id`; `result.json` is written
atomically. The live harness never reads these files. Derive tool statistics, provider comparisons,
reward data, trace summaries, or new research fields offline rather than expanding the runtime
recorder.

## Recommended Check Order

For data-only changes:

```text
validate -> audit -> selected live cases -> optional judge calibration
```

For orchestration changes:

```text
focused Python tests -> stable validate -> stable audit -> one stable live case
-> selected expanded live cases -> optional judge calibration
```

A result is not a full Golden pass merely because one layer succeeded. Report fixture validation,
anchor audit, live hard scoring, and judge calibration separately.
