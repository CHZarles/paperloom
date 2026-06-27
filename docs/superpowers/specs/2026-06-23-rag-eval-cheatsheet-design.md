# RAG Eval Cheatsheet Design

## Context

PaperLoom/CiteWeave is a research-paper RAG workbench. Its key product promise is not generic chat, but source-grounded paper reading: PDF upload, structured parsing, page-aware chunks, hybrid retrieval, answer generation, compact citations, and durable reference mappings.

The current branch already has:

- `eval/rag/product-rescue-smoke.jsonl` for product-regression cases.
- QASPER v0.3 downloaded locally under `eval/rag/qasper/raw/`.
- `QasperBenchmarkConverter` that converts answerable QASPER records with evidence into `RagBenchmarkCase`.
- `RagBenchmarkRunner`, `RagBenchmarkEvaluator`, and `RagBenchmarkReportWriter` for case execution and reporting.

What is missing is a durable experiment ledger. We need a cheatsheet that records which RAG harness was tried, on which dataset, with which scores, and with links to raw run artifacts.
The cheatsheet must stay concise: short benchmark rows, primary metric, compact quality summary, and run artifact link.

## Goals

1. Create a long-lived eval system for comparing RAG harness variants across datasets.
2. Generate a human-readable `eval/rag/CHEATSHEET.md` from real run artifacts instead of hand-entered scores.
3. Keep raw run evidence in JSON so future tooling can build trends, CI gates, or dashboards.
4. Support product smoke tests and open-source academic benchmarks in the same scoring vocabulary.
5. Preserve PaperLoom-specific failure classes: bad evidence, citation mapping failure, scope leak, false negative, and wrong route.
6. Include professional benchmark datasets, starting with QASPER for evidence QA and LitSearch for literature-search retrieval.

## Non-Goals

- Do not replace the current PaperLoom chat/RAG path in this phase.
- Do not make QASPER test split part of routine tuning.
- Do not require a frontend dashboard in the first version.
- Do not add a new database table for eval results yet; repo-local artifacts are enough for this phase.

## Dataset Layers

### Product Rescue Smoke

Path:

```text
eval/rag/product-rescue-smoke.jsonl
```

Purpose:

- Fast product guardrail.
- Covers scoped source QA, reference follow-up, route control, citation mapping, and bad evidence.
- Should become the default local gate before RAG changes are considered stable.

### QASPER Dev

Source:

```text
eval/rag/qasper/raw/extracted/qasper-dev-v0.3.json
```

Generated PaperLoom cases:

```text
eval/rag/qasper/generated/qasper-dev-200.jsonl
```

Purpose:

- Broader academic-paper evidence benchmark.
- Measures whether the system can retrieve and cite gold evidence paragraphs from research-paper full text.
- Dev split is allowed for iteration.

### LitSearch Full

Source:

```text
https://huggingface.co/datasets/princeton-nlp/LitSearch
```

Local layout:

```text
eval/rag/litsearch/raw/
eval/rag/litsearch/generated/
```

Purpose:

- Professional literature-search retrieval benchmark.
- Measures whether PaperLoom can retrieve gold papers for natural-language literature-search queries.
- Uses retrieval metrics such as `recallAt5`, `recallAt20`, and optional MRR instead of answer prose metrics.
- Full corpus is large, so raw and generated local files stay ignored.
- Local support includes JSON conversion for LitSearch query/corpus rows, retrieval-output scoring, and standard run artifact writing; a full run still requires corpus import into a dedicated eval index.

Observed public shape:

| Config | Split | Rows | Role |
|---|---|---:|---|
| `query` | `full` | 597 | Queries with gold `corpusids`. |
| `corpus_clean` | `full` | 64,183 | Clean title, abstract, citations, and full-paper text. |
| `corpus_s2orc` | `full` | 64,183 | S2ORC-style structured paper content. |

### QASPER Test

Source:

```text
eval/rag/qasper/raw/extracted/qasper-test-v0.3.json
```

Purpose:

- Sealed final evaluation after a RAG method is stable.
- Should not be used for day-to-day tuning.

## Benchmark Data Modeling And OCR Boundary

Professional text benchmarks should not enter PaperLoom through OCR by default. QASPER and LitSearch
already ship structured text and stable external ids. The eval system should keep those raw files as
source of truth, normalize them into JSONL, and only import them into PaperLoom's database/index when
the run specifically needs to exercise the live PaperLoom retrieval service.

The import path for service-backed eval is:

1. Keep raw benchmark files under ignored `eval/rag/<dataset>/raw/`.
2. Convert raw rows to normalized JSONL under ignored `eval/rag/<dataset>/generated/`.
3. For offline baselines, read the JSONL directly and write normal `scorecard.json` artifacts.
4. For PaperLoom-backed runs, create eval-scoped paper/chunk rows and an eval Elasticsearch alias.

Suggested modeling:

| Concept | LitSearch | QASPER |
|---|---|---|
| `paperId` | `litsearch:<corpusid>` | `qasper:<arxivId>` |
| `paperTitle` | `title` | `title` |
| `originalFilename` | `litsearch:<corpusid>.json` | `qasper:<arxivId>.json` |
| `isEval` | `true` | `true` |
| `sourceDataset` | `litsearch` | `qasper` |
| `externalCorpusId` | `corpusid` | arXiv id |
| `evalSplit` | `full` or sample id | `dev` / `test` |
| `orgTag` | `eval-litsearch` | `eval-qasper` |
| `processingStatus` | `COMPLETED` | `COMPLETED` |
| Elasticsearch index | `paper_search` / `paper_chunks` | `paper_search` / `paper_chunks` |

OCR is a separate benchmark dimension. It should be used only when evaluating PDF ingestion,
page parsing, OCR quality, or MinerU/OpenDataLoader behavior. Mixing OCR into QASPER or LitSearch
would make the score ambiguous: a failure might be caused by PDF parsing rather than RAG retrieval,
evidence selection, citation mapping, or answer generation.

## Harness Model

Each harness must have a stable id, human name, and short description. The first version can run only the current harness, while planned baselines appear as pending in the cheatsheet.

Initial harness ids:

```yaml
harnesses:
  - id: current-evidence-ledger
    name: Current Evidence Ledger Harness
    description: Router + deterministic-first planner + adaptive hybrid retrieval + evidence ledger + verifier.
    retrieval: adaptive-hybrid
    planner: deterministic-first
    verifier: enabled
    status: runnable

  - id: keyword-only-baseline
    name: Keyword Only Baseline
    description: Text-only retrieval with the same answer/evidence verifier.
    retrieval: text-only
    planner: deterministic-first
    verifier: enabled
    status: planned

  - id: semantic-only-baseline
    name: Semantic Only Baseline
    description: Semantic retrieval only with the same answer/evidence verifier.
    retrieval: semantic-only
    planner: deterministic-first
    verifier: enabled
    status: planned
```

Planned harnesses must not be reported as scored. Cheatsheet rows for planned harnesses should show `pending`.

## File Layout

```text
eval/rag/
  README.md
  CHEATSHEET.md
  harnesses.yaml
  product-rescue-smoke.jsonl
  qasper/
    README.md
    .gitignore
    raw/
    generated/
  litsearch/
    README.md
    .gitignore
    raw/
    generated/
  runs/
    <run-id>/
      run.json
      scorecard.json
      report.md
```

`raw/`, `generated/`, and `runs/` may be ignored if they are too large or environment-specific. `CHEATSHEET.md`, `harnesses.yaml`, dataset manifests, and small smoke datasets should be tracked.

## Run Artifact Schema

### `run.json`

Stores per-case evidence:

```json
{
  "runId": "2026-06-23T120000Z-current-evidence-ledger-product-rescue-smoke",
  "startedAt": "2026-06-23T12:00:00Z",
  "harnessId": "current-evidence-ledger",
  "datasetId": "product-rescue-smoke",
  "datasetPath": "eval/rag/product-rescue-smoke.jsonl",
  "gitCommit": "unknown-or-sha",
  "cases": [
    {
      "caseId": "grep_high_noise_zh",
      "query": "讲一讲高噪声场景",
      "passed": true,
      "route": "MANUAL_SOURCE_QA",
      "failures": [],
      "failureClass": [],
      "diagnostics": {
        "scannedCount": 6,
        "acceptedEvidenceCount": 3,
        "sourceCount": 1,
        "stopReason": "EXHAUSTED"
      }
    }
  ]
}
```

### `scorecard.json`

Stores aggregate metrics:

```json
{
  "runId": "2026-06-23T120000Z-current-evidence-ledger-product-rescue-smoke",
  "harnessId": "current-evidence-ledger",
  "datasetId": "product-rescue-smoke",
  "caseCount": 6,
  "passed": 6,
  "failed": 0,
  "passRate": 1.0,
  "routeAccuracy": 1.0,
  "answerRequiredHitRate": 1.0,
  "evidenceRequiredHitRate": 1.0,
  "citationMappingRate": 1.0,
  "badEvidenceRate": 0.0,
  "scopeLeakRate": 0.0,
  "falseNegativeRate": 0.0,
  "avgScannedCount": 6.0,
  "avgAcceptedEvidenceCount": 3.0,
  "fallbackRate": 0.0
}
```

## Metrics

| Metric | Meaning |
|---|---|
| `passRate` | Passed cases divided by total cases. |
| `routeAccuracy` | Cases without `INTENT_ROUTE` failure. |
| `answerRequiredHitRate` | Cases without `ANSWER_QUALITY` failure. |
| `evidenceRequiredHitRate` | Cases without `FALSE_NEGATIVE` evidence-missing failure. |
| `citationMappingRate` | Cases without `CITATION_MAPPING` failure. |
| `badEvidenceRate` | Cases with `BAD_EVIDENCE` failure. Lower is better. |
| `scopeLeakRate` | Cases with `SCOPE_CONTROL` failure. Lower is better. |
| `falseNegativeRate` | Cases with `FALSE_NEGATIVE` failure. Lower is better. |
| `avgScannedCount` | Average diagnostic scanned evidence count. |
| `avgAcceptedEvidenceCount` | Average diagnostic accepted evidence count. |
| `fallbackRate` | Average or ratio of cases where diagnostics say fallback was used. |
| `recallAt5` | Retrieval benchmark gold-paper recall in top 5. |
| `recallAt20` | Retrieval benchmark gold-paper recall in top 20. |
| `pageRecallAtK` / `windowRecallAtK` | Page-index benchmark recall for exact pages and inspected page windows. |
| `chunkEvidenceHitAtK` / `windowEvidenceHitAtK` | Whether chunk-first or inspected page-window evidence contains required evidence regexes. |

All rates should be stored as 0.0 to 1.0 in JSON and rendered as percentages in Markdown.

## Cheatsheet Format

`eval/rag/CHEATSHEET.md` is generated from `scorecard.json` files plus `harnesses.yaml`.

Example:

```markdown
# PaperLoom RAG Eval Cheatsheet

Last updated: 2026-06-23T12:00:00Z

| Harness | Dataset | Cases | Pass | Evidence Hit | Citation Map | Bad Evidence | Scope Leak | False Negative | Run |
|---|---|---:|---:|---:|---:|---:|---:|---:|---|
| current-evidence-ledger | product-rescue-smoke | 6 | 100.0% | 100.0% | 100.0% | 0.0% | 0.0% | 0.0% | `runs/2026-06-23T120000Z-current-evidence-ledger-product-rescue-smoke/` |
| current-evidence-ledger | qasper-dev-200 | 200 | pending | pending | pending | pending | pending | pending | - |

## Notes

- Product rescue smoke is the fast product gate.
- QASPER dev is the broad academic evidence benchmark.
- QASPER test stays sealed until method selection is stable.
```

Implementation should render the shorter production cheatsheet format:

```markdown
| Harness | Benchmark | Tier | Cases | Primary | Quality | Run |
|---|---|---|---:|---:|---|---|
| current-evidence-ledger | QASPER Dev 200 | professional | 200 | pending Pass | - | - |
| current-evidence-ledger | LitSearch Full | professional | 597 queries / 64,183 papers | pending Recall@20 | - | - |
```

The `Primary` column is benchmark-specific:

- product smoke and QASPER use `passRate`.
- LitSearch uses `recallAt20`.

## Data Flow

1. Convert external benchmark datasets into `RagBenchmarkCase` JSONL where needed.
2. Load dataset with `RagBenchmarkDataset`.
3. Run a named harness with `RagBenchmarkRunner`.
4. Evaluate each actual answer with `RagBenchmarkEvaluator`.
5. Aggregate verdicts into `RagScorecard`.
6. Write `run.json`, `scorecard.json`, and `report.md`.
7. Rebuild `CHEATSHEET.md` from available scorecards.

For LitSearch, the run path is retrieval-only: convert query rows into retrieval cases, run harness retrieval, compute `recallAt5` and `recallAt20`, then store those metrics in `scorecard.json`.

For page-location experiments, the run path is evidence-oriented: convert benchmark evidence rules
into exact-page cases, run page/window location, optionally join the original RAG cases with
`--rag-cases`, then store page recall, window recall, and evidence-hit metrics in `scorecard.json`.

## Error Handling

- Missing dataset file should fail the run with a clear path.
- Empty dataset should produce a scorecard with zero cases and a warning in `report.md`.
- Unknown harness id should fail before running any cases.
- Malformed scorecard JSON should be skipped by cheatsheet generation and listed in a warning section.
- Planned harnesses should render as `pending`, not failed.

## Testing Strategy

Add focused tests for:

1. `RagScorecard` aggregates failure classes and diagnostics correctly.
2. `RagEvalRunWriter` writes all expected artifact files.
3. `RagCheatsheetWriter` renders a leaderboard from scorecards.
4. Planned harnesses render as pending.
5. Malformed or missing scorecards do not corrupt the cheatsheet.
6. QASPER converter output remains loadable by `RagBenchmarkDataset`.
7. `RagBenchmarkRegistry` loads QASPER and LitSearch professional benchmark entries from `harnesses.yaml`.

Run command:

```bash
mvn -q -Dtest=QasperBenchmarkConverterTest,RagBenchmarkEvaluatorTest,RagBenchmarkDatasetTest,RagBenchmarkReportWriterTest,RagScorecardTest,RagEvalRunWriterTest,RagBenchmarkRegistryTest,RagCheatsheetWriterTest test
```

## First Implementation Slice

The first implementation slice should not alter production RAG behavior. It should only add eval infrastructure:

1. `RagScorecard`
2. `RagEvalRunWriter`
3. `RagBenchmarkRegistry`
4. `RagCheatsheetWriter`
5. `harnesses.yaml`
6. generated first `CHEATSHEET.md`
7. documentation updates in `eval/rag/README.md`
8. LitSearch dataset documentation under `eval/rag/litsearch/`

After this slice, future work can add runnable harness variants such as keyword-only, semantic-only, reranked hybrid, and planner variants.
