# Page-Location Prototype

This directory holds the offline page-index evaluation layer for PaperLoom.

The goal is to test whether a paper/page locating step should sit before chunk-level evidence
selection. Keep generated inputs and run outputs local until the method beats chunk-first retrieval
on real product or professional benchmark cases.

The first service-backed bridge is `PaperPageWindowService`. It reads `paper_text_chunks` by
`paperId` and page range, then returns `SearchResult` rows that can go straight into
`EvidenceLedgerService`. It assumes the caller already checked paper access; the service is a
deterministic page inspector, not a public retrieval endpoint.

## Data Flow

1. Export PaperLoom chunks into chunk JSONL.
2. Convert product or benchmark evidence rules into page-location cases with `goldPageKeys`.
3. Run `PaperPageLocatorBenchmarkCli`.
4. Compare `pageRecallAt1`, `pageRecallAt3`, `pageMrr`, plus positive-score variants such as
   `positivePageRecallAt3`.
5. When the original RAG cases are available, pass them with `--rag-cases` and compare
   `chunkEvidenceHitAtK` against `windowEvidenceHitAtK`.

The next service-backed flow should replace step 1 with production retrieval/import data:

```text
production hybrid hits -> page-window ranking -> PaperPageWindowService.inspectPageWindow
-> EvidenceLedgerService -> standard scorecard
```

For scoped paper-QA experiments there is also an explicit `--candidate-source scoped-paper` mode:

```text
trusted scoped paper ids -> PaperPageWindowService.inspectPaper -> page-window ranking
-> PaperPageWindowService.inspectPageWindow -> EvidenceLedgerService -> standard scorecard
```

Use this only after the caller has already established the paper scope. It is a diagnostic harness
for separating first-stage paper-candidate misses from locator/window misses, not a literature-search
or library-discovery strategy.

The test-scoped `ServiceBackedPageWindowHarness` now implements that core orchestration against
`PaperRetrievalService`, `PaperPageWindowService`, and `EvidenceLedgerService`. It is ready to be
wrapped by a Spring-backed eval runner once QASPER/LitSearch rows are imported into eval-scoped
MySQL and Elasticsearch data.

`ServiceBackedPageWindowBenchmarkRunner` batches normal `RagBenchmarkCase` JSONL through the
harness and writes `run.json`, `scorecard.json`, and `report.md`. Use it for Product/QASPER evidence
runs after the eval papers/chunks are available through the live PaperLoom services.
`ServiceBackedPageWindowBenchmarkCli` provides the Spring-backed command-line entry point for that
runner and refreshes the top-level cheatsheet after each run.

Example service-backed run shape:

```bash
java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" \
  com.yizhaoqi.smartpai.eval.ServiceBackedPageWindowBenchmarkCli \
  --cases eval/rag/qasper/generated/qasper-dev-200-rag-cases.jsonl \
  --harness-id service-backed-page-window \
  --dataset-id qasper-dev-200 \
  --user-id <eval-user-db-id> \
  --query-planner scientific-qa \
  --window-radius 1 \
  --top-k 3
```

When running from a git worktree without a local `.env`, pass a `-Dspring.config.location=...`
system property before `-cp` so the CLI uses the repo-root `.env` plus main application YAML files.
Otherwise test resources can put the CLI on H2 instead of the imported PaperLoom eval rows.

Use this on scoped paper-QA cases only. It is not a replacement for the full product chat harness:
route-control cases, library inventory, and reference follow-up need the normal chat/evidence-ledger
benchmark path.

## Files

Generated files stay ignored:

```text
eval/rag/page-location/generated/
```

Typical generated files:

```text
product-rescue-smoke-chunks.jsonl
product-rescue-smoke-page-cases.jsonl
product-rescue-smoke-page-retrieved.jsonl
CHEATSHEET.md
```

Tracked prototype files:

```text
product-rescue-curated-page-cases.jsonl
harnesses.yaml
```

## Current Local Smoke

The current local smoke was generated from:

- `eval/rag/product-rescue-smoke.jsonl`
- MySQL `paper_text_chunks` for `6da506ce952a2c4d85928b3e0052f4f6`

Generated local files are ignored by git:

| File | Rows | SHA-256 |
|---|---:|---|
| `generated/product-rescue-smoke-chunks.jsonl` | 142 | `b63724115738adc60dcec90b74beb18f65a2500145a3222a48b6ad0620bf1767` |
| `generated/product-rescue-smoke-page-cases.jsonl` | 3 | `706be7d27627ba7b608acca3c138185f91ffa59e2879711c03945035d15a0260` |

Broad regex-derived prototype runs:

| Run | Planner | Cases | Page@1 | Page@3 | MRR | PosPage@1 | PosPage@3 | PosMRR |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| `2026-06-23T150730Z-page-index-offline-product-rescue-smoke-page-location` | none | 3 | 13.9% | 41.7% | 100.0% | 5.6% | 16.7% | 33.3% |
| `2026-06-23T155927Z-page-index-offline-planned-product-rescue-smoke-page-location` | page-locator | 3 | 13.9% | 41.7% | 100.0% | 13.9% | 41.7% | 100.0% |

Interpretation: raw lexical page ranking found the right pages mostly through fallback ordering for
generic concept and summary questions. The lightweight `page-locator` planner adds title, keyword,
and section-navigation terms, so every current `Page@3` hit is also a positive-scored hit. This is a
useful signal for page-first retrieval, but the current gold labels are broad because they are derived
from product evidence regexes. The next benchmark step is a small curated page-location set with exact
gold pages before promoting the method into production retrieval.

Curated exact-page prototype runs:

| Run | Harness | Radius | Cases | Page@1 | Page@3 | PosPage@3 | Window@1 | PosWindow@1 |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| `2026-06-23T160641Z-page-index-offline-product-rescue-curated-page-location` | page-index-offline | 0 | 6 | 33.3% | 83.3% | 33.3% | 33.3% | 16.7% |
| `2026-06-23T160952Z-page-index-planned-product-rescue-curated-page-location` | page-index-planned | 0 | 6 | 83.3% | 100.0% | 100.0% | 83.3% | 83.3% |
| `2026-06-23T162203Z-page-index-offline-window-product-rescue-curated-page-location` | page-index-offline | 2 | 6 | 33.3% | 83.3% | 33.3% | 83.3% | 33.3% |
| `2026-06-23T162204Z-page-index-planned-window-product-rescue-curated-page-location` | page-index-planned | 2 | 6 | 83.3% | 100.0% | 100.0% | 100.0% | 100.0% |

Product evidence-hit prototype runs:

| Run | Harness | Radius | Cases | ChunkEv@3 | WindowEv@1 | WindowEv@3 | PosPage@3 | PosWindow@1 |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| `2026-06-24T004500Z-page-index-offline-window-evidence-product-rescue-smoke-page-location` | page-index-offline | 2 | 3 | 0.0% | 100.0% | 100.0% | 16.7% | 22.2% |
| `2026-06-24T004600Z-page-index-planned-window-evidence-product-rescue-smoke-page-location` | page-index-planned | 2 | 3 | 0.0% | 100.0% | 100.0% | 41.7% | 47.2% |

Interpretation: exact page labels show that the planner-expanded page locator is not merely making
the old broad labels look better. It fixes Chinese paper-reading intents that raw lexical page
ranking treats as zero-signal fallback: related concepts, retrieval strategies, method/dataset
setup, and limitations/conclusion. The remaining miss at `Page@1` is the high-noise table query,
where page 5 scores above the exact table page 7 because both pages discuss experiment results. This
points to a production design of `locate_pages` followed by `inspect_page` over a small page window,
rather than single-page selection. With `--window-radius 2`, the planned locator reaches
`PosWindow@1=100.0%` on the curated set, which means the first inspected window contains the exact
gold page for every case.

Evidence-hit scoring is now available for runs that also provide the source RAG cases. The runner
uses the same `requiredEvidenceRegex` rules to score a lightweight chunk-first ranking and the
chunks returned by `inspect_page` over located page windows:

| Metric | Meaning |
|---|---|
| `chunkEvidenceHitAtK` | At least one top-k chunk set contains the required evidence regex. |
| `windowEvidenceHitAtK` | At least one top-k inspected page window contains the required evidence regex. |
| `chunkEvidenceCaseCount` / `windowEvidenceCaseCount` | Number of cases with useful evidence regexes. |

This keeps exact-page location and evidence-bearing context separate: a page window can be useful
even when its center page is not the exact gold page, as long as `inspect_page` recovers the
answer-bearing chunk, table, or figure.

The current Product evidence-hit smoke is deliberately small and uses a lightweight offline
chunk-first ranker, so it should not be read as production hybrid-search performance. Its useful
signal is narrower: with radius-2 windows, `inspect_page` recovered all current product
`requiredEvidenceRegex` rules at top-1 window, while the lightweight chunk-first top-3 baseline did
not. The planned locator also improved positive-scored location quality over raw ranking, which is
the direction to verify next on QASPER-style evidence cases.

Service-backed Product paper-QA smoke:

| Run | Harness | Cases | Pass | WindowEv@1 | WindowEv@K | CandidateEv | ScopeLeak |
|---|---|---:|---:|---:|---:|---:|---:|
| `2026-06-24T124226Z-service-backed-page-window-product-rescue-paper-qa` | `service-backed-page-window` | 3 | 100.0% | 100.0% | 100.0% | - | 0.0% |
| `2026-06-24T162000Z-service-backed-scoped-page-window-product-rescue-paper-qa` | `service-backed-scoped-page-window` | 3 | 100.0% | 100.0% | 100.0% | 100.0% | 0.0% |
| `2026-06-24T162200Z-service-backed-scoped-diverse-window-product-rescue-paper-qa` | `service-backed-scoped-diverse-window` | 3 | 100.0% | 100.0% | 100.0% | 100.0% | 0.0% |
| `2026-06-24T173000Z-service-backed-scoped-diverse-window-k7-product-rescue-paper-qa` | `service-backed-scoped-diverse-window-k7` | 3 | 100.0% | 100.0% | 100.0% | 100.0% | 0.0% |

This run uses `eval/rag/product-rescue-paper-qa.jsonl`, not the full Product Rescue Smoke. The full
smoke still owns route-control, library inventory, and reference-follow-up behavior; the scoped
slice exists so the page-window harness can be scored only on paper-QA evidence recovery.

Service-backed QASPER paper-QA gate:

| Run | Harness | Cases | Pass | WindowEv@1 | WindowEv@K | CandidateEv | ScopeLeak |
|---|---|---:|---:|---:|---:|---:|---:|
| `2026-06-24T125506Z-service-backed-page-window-qasper-dev-200` | `service-backed-page-window` | 200 | 42.0% | 22.5% | 42.0% | - | 0.0% |
| `2026-06-24T162100Z-service-backed-scoped-page-window-qasper-dev-200` | `service-backed-scoped-page-window` | 200 | 42.5% | 22.5% | 42.5% | 78.0% | 0.0% |
| `2026-06-24T162300Z-service-backed-scoped-diverse-window-qasper-dev-200` | `service-backed-scoped-diverse-window` | 200 | 45.5% | 22.5% | 45.5% | 78.0% | 0.0% |
| `2026-06-24T171100Z-service-backed-scoped-diverse-window-k5-qasper-dev-200` | `service-backed-scoped-diverse-window-k5` | 200 | 53.0% | 22.5% | 53.0% | 78.0% | 0.0% |
| `2026-06-24T173100Z-service-backed-scoped-diverse-window-k7-qasper-dev-200` | `service-backed-scoped-diverse-window-k7` | 200 | 61.0% | 22.5% | 61.0% | 78.0% | 0.0% |
| `2026-06-24T164100Z-service-backed-scoped-center-diverse-window-qasper-dev-200` | center-diverse window probe | 200 | 44.5% | 22.5% | 44.5% | 78.0% | 0.0% |
| `2026-06-24T134155Z-service-backed-scoped-page-window-qasper-dev-200-target-rerank` | `service-backed-scoped-page-window` + target-term boost probe | 200 | 41.5% | 22.0% | 41.5% | 78.0% | 0.0% |
| `2026-06-24T144100Z-service-backed-scoped-page-window-qasper-dev-200-chunk-window` | `service-backed-scoped-page-window` + chunk-aware page rerank probe | 200 | 37.5% | 18.0% | 37.5% | 78.0% | 0.0% |

The scoped-paper run removes first-stage paper-candidate misses but improves only one net case over
the first-stage service-backed run. It also shows that 71 QASPER cases have evidence in the
scoped-paper candidate set but miss the selected page windows. A target-term boost probe hurt this
score, dropping to 83/200 and increasing candidate-hit/window-miss cases to 73, so broad target-word
boosting is now an opt-in eval variant (`scientific-qa-targets`) rather than default behavior. A
chunk-aware page rerank probe hurt more, dropping to 75/200 with unchanged `CandidateEv=78.0%`, so
candidate-chunk lexical carry-over is also an opt-in eval variant
(`scientific-qa-chunk-window`). The non-overlapping diverse-window selector is the first positive
service-backed variant: it raises QASPER to 91/200 without hurting the Product Paper-QA slice. Keep
`LOCATE_PAGES` eval-gated; the bottleneck is now locator query planning, page/window ranking, and
inspected-window evidence selection. A center-diverse relaxation scored 89/200, so strict
non-overlap remains the better current coverage heuristic. A five-window high-recall budget reaches
106/200, but raises inspected context from 8.90 to 14.77 chunks per case on average. A seven-window
deep-recall budget reaches 122/200 and leaves 34 candidate-hit/window-miss cases, but raises
inspected context to 20.13 chunks per case on average.

## Case Format

```json
{"id":"grep_high_noise_zh","query":"讲一讲高噪声场景","goldPageKeys":["6da506ce952a2c4d85928b3e0052f4f6:7"]}
```

## Chunk Format

```json
{"paperId":"6da506ce952a2c4d85928b3e0052f4f6","paperTitle":"Is Grep All You Need? How Agent Harnesses Reshape Agentic Search","pageNumber":7,"chunkId":42,"sectionTitle":"Experiment 2","sourceKind":"TEXT","text":"..."}
```

## Commands

After compiling test classes:

```bash
java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" \
  com.yizhaoqi.smartpai.eval.PaperPageLocationCaseGenerator \
  --rag-cases eval/rag/product-rescue-smoke.jsonl \
  --chunks eval/rag/page-location/generated/product-rescue-smoke-chunks.jsonl \
  --output eval/rag/page-location/generated/product-rescue-smoke-page-cases.jsonl

java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" \
  com.yizhaoqi.smartpai.eval.PaperPageLocatorBenchmarkCli \
  --cases eval/rag/page-location/generated/product-rescue-smoke-page-cases.jsonl \
  --chunks eval/rag/page-location/generated/product-rescue-smoke-chunks.jsonl \
  --retrieved eval/rag/page-location/generated/product-rescue-smoke-page-retrieved.jsonl \
  --registry eval/rag/page-location/generated/harnesses.yaml \
  --cheatsheet eval/rag/page-location/generated/CHEATSHEET.md \
  --harness-id page-index-offline \
  --dataset-id product-rescue-smoke-page-location \
  --query-planner page-locator \
  --top-k 3
```

Curated exact-page run:

```bash
java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" \
  com.yizhaoqi.smartpai.eval.PaperPageLocatorBenchmarkCli \
  --cases eval/rag/page-location/product-rescue-curated-page-cases.jsonl \
  --chunks eval/rag/page-location/generated/product-rescue-smoke-chunks.jsonl \
  --retrieved eval/rag/page-location/generated/product-rescue-curated-page-retrieved-planned.jsonl \
  --registry eval/rag/page-location/harnesses.yaml \
  --cheatsheet eval/rag/page-location/generated/CHEATSHEET.md \
  --harness-id page-index-planned \
  --dataset-id product-rescue-curated-page-location \
  --query-planner page-locator \
  --window-radius 2 \
  --top-k 3
```

Evidence-hit run over matching RAG cases:

```bash
java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" \
  com.yizhaoqi.smartpai.eval.PaperPageLocatorBenchmarkCli \
  --cases eval/rag/page-location/generated/product-rescue-smoke-page-cases.jsonl \
  --rag-cases eval/rag/product-rescue-smoke.jsonl \
  --chunks eval/rag/page-location/generated/product-rescue-smoke-chunks.jsonl \
  --retrieved eval/rag/page-location/generated/product-rescue-smoke-page-retrieved-evidence.jsonl \
  --registry eval/rag/page-location/harnesses.yaml \
  --cheatsheet eval/rag/page-location/generated/CHEATSHEET.md \
  --harness-id page-index-planned \
  --dataset-id product-rescue-smoke-page-location \
  --query-planner page-locator \
  --window-radius 2 \
  --top-k 3
```
