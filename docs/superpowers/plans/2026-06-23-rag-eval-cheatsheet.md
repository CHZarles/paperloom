# RAG Eval Cheatsheet Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a durable, concise PaperLoom RAG eval ledger that records harness scores across product and professional benchmarks, including QASPER and LitSearch.

**Architecture:** Keep eval tooling repo-local and test-scoped. `harnesses.yaml` registers harnesses and benchmarks, `RagEvalRunWriter` writes run artifacts, `RagScorecard` aggregates metrics, and `RagCheatsheetWriter` renders the short `CHEATSHEET.md`.
The current routed-retrieval decision is pinned in `eval/rag/RETRIEVAL_STRATEGY_DECISION.md`.

**Tech Stack:** Java 17 test utilities, JUnit 5, Jackson JSON, Markdown, local JSONL datasets.

---

### Task 1: Scorecard Aggregation

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/RagScorecard.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/eval/RagScorecardTest.java`

- [x] **Step 1: Write failing tests**

Add tests for aggregate pass rate, PaperLoom failure classes, diagnostics averages, fallback rate, and retrieval metrics such as `recallAt20`.

- [x] **Step 2: Run red test**

Run:

```bash
mvn -q -Dtest=RagScorecardTest test
```

Expected before implementation: compilation fails because `RagScorecard` is missing.

- [x] **Step 3: Implement scorecard**

Implement `RagScorecard.from(runId, startedAt, harnessId, datasetId, run, additionalMetrics)` and store all metrics as 0.0 to 1.0 doubles.

- [x] **Step 4: Verify green**

Run:

```bash
mvn -q -Dtest=RagScorecardTest test
```

Expected after implementation: exit code 0.

### Task 2: Run Artifact Writer

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/RagEvalRunWriter.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/eval/RagEvalRunWriterTest.java`

- [x] **Step 1: Write failing tests**

Assert that a run writes:

```text
run.json
scorecard.json
report.md
```

- [x] **Step 2: Run red test**

Run:

```bash
mvn -q -Dtest=RagEvalRunWriterTest test
```

Expected before implementation: compilation fails because `RagEvalRunWriter` is missing.

- [x] **Step 3: Implement writer**

Write metadata, per-case route/failure/diagnostics rows, aggregate scorecard, and a compact Markdown report.

- [x] **Step 4: Verify green**

Run:

```bash
mvn -q -Dtest=RagEvalRunWriterTest test
```

Expected after implementation: exit code 0.

### Task 3: Harness And Benchmark Registry

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/RagBenchmarkRegistry.java`
- Create: `eval/rag/harnesses.yaml`
- Test: `src/test/java/com/yizhaoqi/smartpai/eval/RagBenchmarkRegistryTest.java`

- [x] **Step 1: Write failing tests**

Assert that the registry loads:

```text
current-evidence-ledger
qasper-dev-200
litsearch-full
```

- [x] **Step 2: Run red test**

Run:

```bash
mvn -q -Dtest=RagBenchmarkRegistryTest test
```

Expected before implementation: compilation fails because `RagBenchmarkRegistry` is missing.

- [x] **Step 3: Implement registry**

Read the simple YAML shape used by `eval/rag/harnesses.yaml` and expose harness and benchmark definitions.

- [x] **Step 4: Verify green**

Run:

```bash
mvn -q -Dtest=RagBenchmarkRegistryTest test
```

Expected after implementation: exit code 0.

### Task 4: Cheatsheet Writer

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/RagCheatsheetWriter.java`
- Create: `eval/rag/CHEATSHEET.md`
- Test: `src/test/java/com/yizhaoqi/smartpai/eval/RagCheatsheetWriterTest.java`

- [x] **Step 1: Write failing tests**

Assert the writer renders concise rows with `Harness`, `Benchmark`, `Tier`, `Cases`, `Primary`, `Quality`, and `Run`.

- [x] **Step 2: Run red test**

Run:

```bash
mvn -q -Dtest=RagCheatsheetWriterTest test
```

Expected before implementation: compilation fails because `RagCheatsheetWriter` is missing.

- [x] **Step 3: Implement writer**

Read scorecards from `eval/rag/runs/`, skip malformed scorecards with warnings, and render pending rows for unscored benchmark-harness pairs.

- [x] **Step 4: Verify green**

Run:

```bash
mvn -q -Dtest=RagCheatsheetWriterTest test
```

Expected after implementation: exit code 0.

### Task 5: LitSearch Dataset Documentation

**Files:**
- Create: `eval/rag/litsearch/README.md`
- Create: `eval/rag/litsearch/.gitignore`
- Modify: `eval/rag/README.md`
- Modify: `docs/superpowers/specs/2026-06-23-rag-eval-cheatsheet-design.md`

- [x] **Step 1: Document public source**

Record:

```text
https://huggingface.co/datasets/princeton-nlp/LitSearch
https://github.com/princeton-nlp/LitSearch
https://arxiv.org/abs/2407.18940
```

- [x] **Step 2: Document local storage**

Keep raw and generated LitSearch files ignored:

```text
eval/rag/litsearch/raw/
eval/rag/litsearch/generated/
```

- [x] **Step 3: Document metrics**

Use `recallAt5`, `recallAt20`, and optional MRR for LitSearch retrieval runs.

### Task 6: Verification

**Files:**
- Existing eval tests and docs.

- [x] **Step 1: Run focused eval tests**

Run:

```bash
mvn -q -Dtest=QasperBenchmarkConverterTest,RagBenchmarkEvaluatorTest,RagBenchmarkDatasetTest,RagBenchmarkReportWriterTest,RagScorecardTest,RagEvalRunWriterTest,RagBenchmarkRegistryTest,RagCheatsheetWriterTest test
```

Expected: exit code 0.

- [x] **Step 2: Run compile check**

Run:

```bash
mvn -q -DskipTests compile
```

Expected: exit code 0.

### Next Slice: Make LitSearch Runnable

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/LitSearchBenchmarkConverter.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/LitSearchBenchmarkConverterTest.java`
- Create: `src/test/resources/eval/litsearch-mini-query.json`
- Create: `src/test/resources/eval/litsearch-mini-corpus.json`

- [x] **Step 1: Add mini fixture**

Create a tiny query fixture with one query and two gold `corpusids`, plus a tiny corpus fixture with title, abstract, and full-paper text.

- [x] **Step 2: Add converter test**

Assert converted cases preserve query text, gold paper ids, and `taskType=LITSEARCH_RETRIEVAL`.

- [x] **Step 3: Implement converter**

Read LitSearch query JSON or JSONL and write PaperLoom retrieval cases under `eval/rag/litsearch/generated/`.

- [x] **Step 4: Add retrieval scorer**

Compute `recallAt5` and `recallAt20` from retrieved paper ids against gold `corpusids`.

### Next Slice: Add A LitSearch Keyword Baseline

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/LitSearchKeywordBaselineCli.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/LitSearchKeywordBaselineCliTest.java`
- Modify: `eval/rag/harnesses.yaml`
- Modify: `eval/rag/README.md`
- Modify: `eval/rag/litsearch/README.md`

- [x] **Step 1: Add failing test**

Use the mini LitSearch query and corpus fixtures to assert that the keyword baseline writes
`retrievedCorpusIds`, score artifacts, and a cheatsheet row.

- [x] **Step 2: Implement baseline**

Implement weighted keyword retrieval with title > abstract > full text, then reuse
`LitSearchRetrievalScoreCli` for scoring and artifact writing.

- [x] **Step 3: Document use**

Document the baseline as a reproducible floor for LitSearch retrieval, not as the target RAG method.

### Next Slice: Make Corpus Download Reproducible

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/LitSearchDatasetServerDownloader.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/LitSearchDatasetServerDownloaderTest.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/LitSearchCorpusJsonlWriter.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/LitSearchPaperDocumentDataset.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/eval/LitSearchKeywordBaselineCli.java`
- Modify: `eval/rag/litsearch/README.md`

- [x] **Step 1: Add failing test**

Assert HuggingFace dataset-server corpus pages can be merged into `LitSearchPaperDocument` JSONL,
then consumed by the keyword baseline.

- [x] **Step 2: Implement corpus JSONL tools**

Add a corpus JSONL writer CLI and a corpus JSONL loader. Keep raw/generated files ignored.

- [x] **Step 3: Implement resumable downloader**

Add a dataset-server downloader CLI with skip-existing behavior, retry, proxy support, and offset
pagination.

- [x] **Step 4: Download a real corpus sample**

Download `corpus_clean` offset 0 length 10 and merge it into
`eval/rag/litsearch/generated/litsearch-corpus-clean-sample-10.jsonl`, then use the downloader to
create a 20-row sample JSONL.

- [x] **Step 5: Document full corpus route**

Document the resumable full-corpus download and merge command. The full 64,183-paper score remains
a future run because the complete corpus has not been downloaded yet.

### Next Slice: Correct Partial Pages And Run A Real-Corpus Sanity Score

**Files:**
- Modify: `src/test/java/com/yizhaoqi/smartpai/eval/LitSearchDatasetServerDownloader.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/eval/LitSearchDatasetServerDownloaderTest.java`
- Modify: `eval/rag/litsearch/README.md`

- [x] **Step 1: Add failing test for bad cached pages**

Assert a cached page with fewer rows than expected is redownloaded instead of skipped.

- [x] **Step 2: Implement row-count validation**

Only skip an existing page when its `rows` array length equals the expected page size.

- [x] **Step 3: Download full-size first pages**

Use the downloader to fetch offsets `0`, `100`, and `200` as 100-row pages and merge them into
`litsearch-corpus-clean-sample-300.jsonl`.

- [x] **Step 4: Run sanity baseline**

Run `keyword-only-baseline` against all 597 LitSearch queries using the 300-paper corpus sample.
Record the run as a sanity artifact under `datasetId=litsearch-sample-300`, not as the full
professional score.

### Next Slice: Extend LitSearch Corpus Coverage

**Files:**
- Modify: `eval/rag/litsearch/README.md`

- [x] **Step 1: Download next page batch**

Download offsets `300..2,200` as 20 additional 100-row `corpus_clean` pages.

- [x] **Step 2: Merge exact offset sequence**

Merge offsets `0,100,...,2,200` into `litsearch-corpus-clean-sample-2300.jsonl` using an explicit
offset sequence so older sample files such as `page-00010.json` cannot contaminate the corpus.

- [x] **Step 3: Run expanded sanity baseline**

Run `keyword-only-baseline` over all 597 LitSearch queries using the 2,300-paper corpus sample.
Record the run as `datasetId=litsearch-sample-2300`, not as the professional full score.

### Next Slice: Continue LitSearch Corpus Download Under Rate Limit

**Files:**
- Modify: `src/test/java/com/yizhaoqi/smartpai/eval/LitSearchDatasetServerDownloader.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/eval/LitSearchDatasetServerDownloaderTest.java`
- Modify: `eval/rag/litsearch/README.md`

- [x] **Step 1: Add page pacing**

Add `--page-delay-millis` so long dataset-server downloads can avoid HuggingFace 429 rate limits.

- [x] **Step 2: Download next corpus batch**

Download offsets `5,000..7,900` with page delay after an initial 429 at offset `5,000`.

- [x] **Step 3: Merge 8,000-paper sample**

Merge offsets `0,100,...,7,900` into `litsearch-corpus-clean-sample-8000.jsonl`.

- [x] **Step 4: Run expanded sanity baseline**

Run `keyword-only-baseline` over all 597 LitSearch queries using the 8,000-paper corpus sample.
Record the run as `datasetId=litsearch-sample-8000`, not as the professional full score.

### Next Slice: Document Benchmark Import Boundary

**Files:**
- Modify: `docs/superpowers/specs/2026-06-23-rag-eval-cheatsheet-design.md`
- Modify: `eval/rag/README.md`
- Modify: `eval/rag/litsearch/README.md`
- Modify: `eval/rag/qasper/README.md`

- [x] **Step 1: Define the OCR boundary**

Document that QASPER and LitSearch are structured-text benchmarks and should not run through OCR
for normal RAG eval. OCR belongs in a separate PDF-ingestion benchmark.

- [x] **Step 2: Define eval-scoped DB modeling**

Document eval-scoped import markers such as `sourceDataset`, `externalCorpusId`, `evalSplit`,
`orgTag`, `paperId`, and `processingStatus=COMPLETED`.

- [x] **Step 3: Define service-backed import targets**

Document MySQL paper/chunk import and dedicated Elasticsearch eval aliases such as
`paperloom-eval-litsearch`, while keeping raw/generated benchmark artifacts ignored.

### Next Slice: Continue Full LitSearch Download

**Files:**
- Modify: `eval/rag/litsearch/README.md`

2026-06-24 continuation note: current local coverage was verified through `offset=15900`
(`0..15,999` row indexes) and the 16k retrieved output was scored into a normal run artifact.
The next download attempt at `offset=16000` is blocked by TCP connect timeouts to HuggingFace from
this environment, including direct `curl`, Java downloader, and the local `127.0.0.1:8118` proxy.
Keep the resume offset at `16000` until network access recovers.

2026-06-24T07:56:22Z probe: direct dataset-server row request, direct HuggingFace dataset page
request, and `127.0.0.1:8118` proxy request all still timed out during TCP connect / CONNECT.
No new corpus pages were downloaded; `LitSearch Full` remains pending.

2026-06-24T08:07:15Z probe: repeated direct dataset-server row request, direct HuggingFace dataset
page request, and `127.0.0.1:8118` proxy request all still timed out during TCP connect / CONNECT.
No new corpus pages were downloaded; resume offset remains `16000`.

- [ ] **Step 1: Download next corpus batch**

Resume `corpus_clean` from offset `16,000` using `--page-delay-millis 4000` or slower to avoid
HuggingFace dataset-server rate limits.

- [ ] **Step 2: Verify exact page coverage**

Check that every expected 100-row page has matching `rows` length and row indexes before merging.

- [ ] **Step 3: Merge exact offset sequence**

Merge only explicit offsets with `LitSearchCorpusJsonlWriter`; do not use a broad glob that could
include old 10-row sample pages.

- [ ] **Step 4: Run sample sanity baseline**

Run `keyword-only-baseline` with a sample-specific dataset id. Do not report the result as
`LitSearch Full` until all 64,183 corpus rows are present.

### Next Slice: Paper RAG Method Exploration Notes

**Files:**
- Create: `eval/rag/RAG_METHOD_EXPLORATION.md`
- Modify: `eval/rag/README.md`
- Modify: `eval/rag/litsearch/README.md`

- [x] **Step 1: Record the product/user frame**

Describe PaperLoom as a research-paper RAG workbench for researchers, students, engineers, and
reviewers who need page-grounded evidence rather than generic chat.

- [x] **Step 2: Map the current RAG insertion points**

Document how `PaperChunkBuilder`, `PaperTextChunk`, `VectorizationService`, `PaperQueryPlanner`,
`HybridSearchService`, `EvidenceToolExecutor`, and `EvidenceAnswerGenerator` currently connect.

- [x] **Step 3: Define method hypotheses**

Document metadata-boosted hybrid retrieval, page-index-first retrieval, vector retrieval plus
locator APIs, citation-neighborhood expansion, and query decomposition for literature search.

- [x] **Step 4: Tie methods to benchmark gates**

Use Product Rescue Smoke, QASPER Dev, LitSearch, and next-candidate professional benchmarks as the
acceptance ladder.

### Next Slice: Offline Page-Index Prototype

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/PaperPageDocument.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/PaperPageIndexBuilder.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/PaperPageLocator.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/PaperPageHit.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/PaperPageLocatorScorer.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/PaperPageIndexTest.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/PaperPageLocatorScorerTest.java`
- Modify: `eval/rag/README.md`
- Modify: `eval/rag/RAG_METHOD_EXPLORATION.md`

- [x] **Step 1: Add failing page-index tests**

Assert that chunk-level `SearchResult` rows can be grouped into page documents that preserve
`paperId`, `paperTitle`, `pageNumber`, `chunkIds`, `sectionTitles`, `sourceKinds`, `tableIds`, and
page text.

- [x] **Step 2: Implement page aggregation**

Build `PaperPageDocument` rows from chunk-level evidence while preserving stable same-page metadata.

- [x] **Step 3: Add page locator tests**

Assert that experiment/table-like queries rank the experiment table page first and can expand to
same-paper neighbor pages.

- [x] **Step 4: Implement page locator**

Rank pages with lightweight title/text/section/source-kind signals and same-paper neighbor
expansion. Keep it offline and eval-scoped.

- [x] **Step 5: Add page locator scorer**

Compute `pageRecallAtK` and `pageMrr` so page-first retrieval can be compared against chunk-first
retrieval in future benchmark runs.

### Next Slice: Page-Location Benchmark Runner

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/PaperPageLocatorCase.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/PaperPageLocatorCaseDataset.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/PaperPageChunk.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/PaperPageChunkDataset.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/PaperPageLocatorBenchmarkCli.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/PaperPageLocatorBenchmarkCliTest.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/eval/RagCheatsheetWriter.java`
- Modify: `eval/rag/README.md`
- Modify: `eval/rag/RAG_METHOD_EXPLORATION.md`

- [x] **Step 1: Add failing benchmark CLI test**

Use temporary JSONL cases and chunks to assert a page-location run writes retrieved page keys,
`scorecard.json`, and a cheatsheet row.

- [x] **Step 2: Add JSONL loaders**

Load page-location cases with `goldPageKeys` and chunk rows with page/section/source metadata.

- [x] **Step 3: Implement page-location runner**

Build page documents, rank pages per case, write `retrievedPageKeys`, and write normal PaperLoom
run artifacts with `pageRecallAt1`, `pageRecallAtK`, and `pageMrr`.

- [x] **Step 4: Render page metrics concisely**

Render primary metric labels such as `Page@3` in `CHEATSHEET.md`.

- [x] **Step 5: Document use**

Document the case/chunk JSONL shape and keep this harness offline until it beats chunk-first
retrieval on real QASPER/Product evidence-page cases.

### Next Slice: Product Smoke Page-Location Sample

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/PaperPageLocationCaseGenerator.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/PaperPageLocationCaseGeneratorTest.java`
- Create: `eval/rag/page-location/.gitignore`
- Create: `eval/rag/page-location/README.md`
- Modify: `src/test/java/com/yizhaoqi/smartpai/eval/PaperPageLocatorScorer.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/eval/PaperPageLocatorScorerTest.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/eval/RagCheatsheetWriter.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/eval/RagCheatsheetWriterTest.java`
- Modify: `eval/rag/RAG_METHOD_EXPLORATION.md`

- [x] **Step 1: Add failing case-generator test**

Use product-style `requiredEvidenceRegex` plus chunk JSONL to assert `goldPageKeys` are derived from
matching scoped paper chunks.

- [x] **Step 2: Implement evidence-regex case generator**

Generate page-location cases from `RagBenchmarkCase` rows and chunk JSONL. Skip cases without scoped
paper ids, without useful evidence regexes, or without matching chunk pages.

- [x] **Step 3: Export current Product Smoke chunks**

Export local MySQL `paper_text_chunks` for `6da506ce952a2c4d85928b3e0052f4f6` into ignored
`eval/rag/page-location/generated/product-rescue-smoke-chunks.jsonl`.

- [x] **Step 4: Generate and run page-location smoke**

Generate 3 page-location cases from `eval/rag/product-rescue-smoke.jsonl` and run
`page-index-offline` at top-k 3.

- [x] **Step 5: Add positive-score page metrics**

Add `positivePageRecallAtK` and `positivePageMrr` so zero-score fallback pages do not make the
prototype look stronger than it is.

- [x] **Step 6: Document result and next method signal**

Record the local smoke result: `Page@3 41.7%`, `PosPage@3 16.7%`, and note that generic Chinese
concept/summary queries need query expansion or locator APIs before production promotion.

### Next Slice: Page-Window Evidence-Hit Scoring

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/PaperEvidenceHitScorer.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/PaperEvidenceHitScorerTest.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/eval/PaperPageLocatorBenchmarkCli.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/eval/PaperPageLocatorBenchmarkCliTest.java`
- Modify: `eval/rag/README.md`
- Modify: `eval/rag/page-location/README.md`
- Modify: `eval/rag/RAG_METHOD_EXPLORATION.md`

- [x] **Step 1: Add failing evidence scorer tests**

Assert `requiredEvidenceRegex` can be scored against top-k chunk evidence and top-k inspected
page windows, while generic regexes such as `.` are ignored.

- [x] **Step 2: Implement evidence-hit scorer**

Compute `chunkEvidenceCaseCount`, `chunkEvidenceHitAtK`, `windowEvidenceCaseCount`, and
`windowEvidenceHitAtK` from benchmark evidence regexes.

- [x] **Step 3: Add failing CLI integration test**

Use a nearby-table case where chunk-first top1 finds the wrong table, but a radius-2 inspected page
window contains the answer-bearing table evidence.

- [x] **Step 4: Wire optional `--rag-cases` into page-location runs**

Load matching RAG benchmark cases, score a lightweight chunk-first rank, inspect located page
windows, and merge evidence-hit metrics into the normal `scorecard.json`.

- [x] **Step 5: Document the method signal**

Document that page/window recall and evidence hit are separate signals: the locator must find a
useful page window, and `inspect_page` must recover answer-bearing evidence.

### Next Slice: Product Rescue Evidence-Hit Run

**Files:**
- Modify ignored local generated files under `eval/rag/page-location/generated/`
- Modify: `src/test/java/com/yizhaoqi/smartpai/eval/RagCheatsheetWriter.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/eval/RagCheatsheetWriterTest.java`
- Modify: `eval/rag/page-location/README.md`
- Modify: `eval/rag/RAG_METHOD_EXPLORATION.md`

- [x] **Step 1: Run Product evidence-hit benchmark**

Run `page-index-offline` and `page-index-planned` over
`generated/product-rescue-smoke-page-cases.jsonl`, `product-rescue-smoke.jsonl`, and exported
Product Rescue chunks with `--window-radius 2 --top-k 3`.

- [x] **Step 2: Record local result**

Observed on 3 Product evidence cases:

| Harness | ChunkEv@3 | WindowEv@1 | WindowEv@3 | PosPage@3 | PosWindow@1 |
|---|---:|---:|---:|---:|---:|
| `page-index-offline` | 0.0% | 100.0% | 100.0% | 16.7% | 22.2% |
| `page-index-planned` | 0.0% | 100.0% | 100.0% | 41.7% | 47.2% |

- [x] **Step 3: Keep cheatsheet labels concise**

Add `ChunkEv@K` and `WindowEv@K` labels so generated page-location cheatsheets stay readable.

- [x] **Step 4: Document interpretation**

Record that this is a lightweight offline chunk-first baseline, not production hybrid retrieval.
The useful signal is that page-window inspection recovered all current product evidence regexes,
and the next evidence-hit comparison should move to QASPER-style cases.

### Next Slice: QASPER Paragraph-Window Evidence Smoke

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/QasperPageWindowDatasetWriter.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/QasperPageWindowDatasetWriterTest.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/eval/PaperPageLocatorBenchmarkCli.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/eval/PaperPageLocatorBenchmarkCliTest.java`
- Modify ignored local generated files under `eval/rag/qasper/generated/`
- Modify: `eval/rag/qasper/README.md`
- Modify: `eval/rag/RAG_METHOD_EXPLORATION.md`

- [x] **Step 1: Add QASPER page-window writer**

Convert QASPER structured JSON into three aligned artifacts: RAG cases, paragraph chunks, and
page-location cases. Use paragraph ordinal as an eval-scoped window id; do not call it real PDF
page numbering.

- [x] **Step 2: Scope page locator by RAG case**

When `--rag-cases` is provided, restrict page ranking to the case's scoped/expected paper ids so
multi-paper QASPER samples do not leak across papers.

- [x] **Step 3: Generate QASPER Dev 50 paragraph-window smoke**

Generated local ignored files:

| File | Rows | SHA-256 |
|---|---:|---|
| `qasper-dev-50-rag-cases.jsonl` | 50 | `fe2f48228733b446296320a0342733935086f7ba4f442376faafb7056c550212` |
| `qasper-dev-50-page-cases.jsonl` | 44 | `78600bf5b430aa6041a4ce7c0dc5f1e8bc77694475e14fc68c3a78c803c4cf71` |
| `qasper-dev-50-paragraph-chunks.jsonl` | 896 | `714184c37a653cc547a4c82c558cf2ff113b5ba0c7d2922b0824964b5a14d9a5` |

- [x] **Step 4: Run offline and planned page-window benchmarks**

Observed with `--window-radius 1 --top-k 3`:

| Harness | Cases | ChunkEv@3 | WindowEv@1 | WindowEv@3 | Page@3 |
|---|---:|---:|---:|---:|---:|
| `page-index-offline` | 44 | 25.0% | 13.6% | 29.5% | 22.7% |
| `page-index-planned` | 44 | 25.0% | 18.2% | 27.3% | 20.5% |
| `page-index-scientific-qa` | 44 | 25.0% | 38.6% | 56.8% | 40.9% |

- [x] **Step 5: Record method interpretation**

Raw paragraph-window inspection gives a small top-3 lift over the lightweight chunk-first baseline.
The product-oriented locator planner improves top-1 but hurts top-3 on this QASPER slice. A
scientific-QA planner with baseline/comparison, dataset/corpus/language, model/algorithm,
evaluation/results, and annotation/crowdsourcing facets is much better on this slice:
`WindowEv@3=56.8%` and `Page@3=40.9%`. This motivated the larger QASPER Dev 200 run below.

- [x] **Step 6: Scale scientific-QA page-window benchmark to QASPER Dev 200**

Generated local ignored files:

| File | Rows | SHA-256 |
|---|---:|---|
| `qasper-dev-200-rag-cases.jsonl` | 200 | `02a91f02eee8d5ec7045ef40c0ee1ebf47d8ff6e918fd245233d4ac647c142f1` |
| `qasper-dev-200-page-cases.jsonl` | 156 | `8636e2c274bf6fd1cf440b9db3377e274d6acf6089343e54b9f4313a05b8d4e4` |
| `qasper-dev-200-paragraph-chunks.jsonl` | 3418 | `ff5a19e2d746ab8a668a17fd435f5531e7f87687e77d3d7895865a8c38af5246` |

Observed with `--window-radius 1 --top-k 3`:

| Harness | Cases | ChunkEv@3 | WindowEv@1 | WindowEv@3 | Page@3 |
|---|---:|---:|---:|---:|---:|
| `page-index-offline` | 156 | 34.6% | 21.2% | 43.6% | 33.3% |
| `page-index-planned` | 156 | 34.6% | 23.7% | 43.6% | 31.4% |
| `page-index-scientific-qa` | 156 | 34.6% | 28.8% | 54.5% | 37.8% |

The scientific-QA planner holds up beyond the Dev 50 smoke. It improves `WindowEv@3` by 10.9 points
over raw/page-locator and `Page@3` by 4.5 points over raw on this larger slice. Next method step:
build a service-backed locator harness over eval-scoped MySQL/Elasticsearch imports.

### Next Slice: Expand LitSearch And Add SAG Candidate

- [x] **Step 1: Expand LitSearch corpus coverage**

Downloaded `corpus_clean` pages `11000..15900` and merged a 16,000-paper snapshot.

| File | Rows | SHA-256 |
|---|---:|---|
| `litsearch-corpus-clean-sample-16000.jsonl` | 16,000 | `b80406f87d2104ae0aba27f223d5a203d991a062e160c93ae033e2df1908f018` |

- [x] **Step 2: Run 16k keyword sanity floor**

Observed with all 597 LitSearch queries and top-20 retrieval:

| Harness | Corpus | Recall@5 | Recall@20 | MRR |
|---|---:|---:|---:|---:|
| `keyword-only-baseline` | 16,000 / 64,183 papers | 15.0% | 20.7% | 10.1% |

This is still not `LitSearch Full`; it only expands the partial-corpus sanity floor.

- [x] **Step 3: Add SAG as a retrieval-strategy candidate**

Recorded SAG (`https://github.com/Zleap-AI/SAG`) as a structured expansion candidate:
initial ES/page hits, event/entity extraction, SQL-style expansion, then evidence-ledger scoring.
Local notes live in `eval/rag/sag/README.md`.

### Next Slice: Service-Backed Page-Window Bridge

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/model/PaperTextChunk.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/repository/PaperTextChunkRepository.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/service/PaperPageWindowService.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/service/PaperPageWindowServiceTest.java`
- Modify: `eval/rag/README.md`
- Modify: `eval/rag/RAG_METHOD_EXPLORATION.md`
- Modify: `eval/rag/page-location/README.md`
- Modify: `eval/rag/litsearch/README.md`

- [x] **Step 1: Add failing page-window service test**

Assert that a page-window inspector reads `paperId` plus page range, then returns ledger-ready
`SearchResult` rows preserving paper title, original filename, page number, section, source kind,
table id, bbox, and retrieval route metadata.

- [x] **Step 2: Add MySQL page-window query support**

Add a repository method for `paperId` and page range ordered by page/chunk, plus explicit chunk id
column mapping and page/chunk index metadata for `paper_text_chunks`.

- [x] **Step 3: Implement service-backed page inspector**

Add `PaperPageWindowService.inspectPageWindow(...)`. It does not change chat behavior and assumes
the caller has already established paper access.

- [x] **Step 4: Record method boundary**

Document the bridge as the production-side support for the next service-backed locator harness:
hybrid first-stage hits -> page-window ranking -> MySQL page inspection -> evidence ledger ->
standard scorecard.

### Next Slice: LitSearch Facet Paper Baseline

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/LitSearchFacetPaperBaselineCli.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/LitSearchFacetPaperBaselineCliTest.java`
- Modify: `eval/rag/harnesses.yaml`
- Modify: `eval/rag/litsearch/README.md`
- Modify: `eval/rag/RAG_METHOD_EXPLORATION.md`

- [x] **Step 1: Add failing facet baseline test**

Assert that a paper with broad title/abstract facet coverage outranks a decoy paper that repeats one
query term many times in full text.

- [x] **Step 2: Implement paper-level facet scoring**

Add an offline LitSearch CLI that scores title, abstract, and full text with capped body term
frequency, title/abstract coverage bonuses, phrase bonuses, and normal scorecard writing.

- [x] **Step 3: Register harness and run 16k sanity score**

Register `facet-paper-baseline` as a LitSearch runnable harness and run it over all 597 LitSearch
queries with the 16,000-paper local corpus sample.

Observed:

| Harness | Corpus | Recall@5 | Recall@20 | MRR |
|---|---:|---:|---:|---:|
| `keyword-only-baseline` | 16,000 / 64,183 papers | 15.0% | 20.7% | 10.1% |
| `facet-paper-baseline` | 16,000 / 64,183 papers | 22.2% | 26.9% | 18.6% |

Interpretation: literature-search routing should start with a paper-level title/abstract/metadata
retrieval stage before page or chunk evidence selection. This remains a partial-corpus result, not
the professional `LitSearch Full` score.

### Next Slice: Production Literature-Search Planner Boundary

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/PaperQueryPlanner.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/PaperRetrievalService.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/service/PaperQueryPlannerTest.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/service/PaperRetrievalServiceTest.java`
- Modify: `eval/rag/RAG_METHOD_EXPLORATION.md`

- [x] **Step 1: Add failing planner/retrieval tests**

Assert recommendation/related-paper queries become `LITERATURE_SEARCH`, strip recommendation
boilerplate, add title/abstract and related-work facets, and prefer paper-level title/abstract
coverage over repeated single-term body matches.

- [x] **Step 2: Implement low-risk production boundary**

Add `LITERATURE_SEARCH` intent and `RetrievalPlan.paperLevelSearch()`. Mark matching results as
`PAPER_LEVEL` / `EXPANDED_PAPER_LEVEL`, then apply light title/evidence/section reranking for paper
recommendation routes without changing QA route signatures.

- [x] **Step 3: Document two-stage strategy**

Record the resulting route split: literature search starts with paper-level candidate retrieval;
paper QA continues through hybrid/page-window evidence inspection; known references/pages use
deterministic inspection.

### Next Slice: Literature-Search Paper Candidate Collapse

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/PaperRetrievalService.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/service/PaperRetrievalServiceTest.java`
- Modify: `eval/rag/RAG_METHOD_EXPLORATION.md`

- [x] **Step 1: Add failing duplicate-paper candidate test**

Assert that `LITERATURE_SEARCH` returns one best evidence snippet per `paperId`, so multiple chunks
from the same paper cannot crowd out other paper candidates in recommendation output.

- [x] **Step 2: Collapse only paper-level searches**

After reranking, collapse `LITERATURE_SEARCH` results by `paperId` while leaving QA/scoped evidence
retrieval chunk-level.

- [x] **Step 3: Document recommendation semantics**

Record that recommendation/library discovery returns paper candidates, while QA still returns
chunk/page evidence.

### Next Slice: Service-Backed Locator Harness Core

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/ServiceBackedPageWindowHarness.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/ServiceBackedPageWindowHarnessTest.java`
- Modify: `eval/rag/page-location/harnesses.yaml`
- Modify: `eval/rag/README.md`
- Modify: `eval/rag/RAG_METHOD_EXPLORATION.md`
- Modify: `eval/rag/page-location/README.md`

- [x] **Step 1: Add failing harness test**

Assert that production first-stage retrieval can hit a nearby page, then service-backed page-window
inspection recovers the answer-bearing chunk and packs it into an evidence ledger.

- [x] **Step 2: Implement harness orchestration**

Add `ServiceBackedPageWindowHarness`: `PaperRetrievalService` first-stage hits -> page document
aggregation -> scientific-QA/page-locator query planning -> page-window ranking ->
`PaperPageWindowService.inspectPageWindow` -> `EvidenceLedgerService.fromSearchResults`.

- [x] **Step 3: Register prototype harness**

Record `service-backed-page-window` in the page-location prototype registry. Do not add a main
cheatsheet score until a real service-backed Product/QASPER run writes scorecard artifacts.

### Next Slice: Service-Backed Locator Benchmark Runner

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/ServiceBackedPageWindowBenchmarkRunner.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/ServiceBackedPageWindowBenchmarkRunnerTest.java`
- Modify: `eval/rag/README.md`
- Modify: `eval/rag/RAG_METHOD_EXPLORATION.md`
- Modify: `eval/rag/page-location/README.md`

- [x] **Step 1: Add failing runner test**

Use two benchmark cases and a mocked `ServiceBackedPageWindowHarness` to assert the runner writes
standard `run.json`, `scorecard.json`, and `report.md` artifacts with `windowEvidenceHitAt1`.

- [x] **Step 2: Implement batch runner**

Load `RagBenchmarkCase` JSONL, run the service-backed page-window harness per case, convert inspected
page-window evidence into `RagBenchmarkActual` / `RagBenchmarkVerdict`, compute
`windowEvidenceHitAtK`, and write artifacts through `RagEvalRunWriter`.

- [x] **Step 3: Keep main cheatsheet clean**

Document the runner as a service-backed eval building block, but do not add a main cheatsheet score
until a real Product/QASPER service-backed run exists.

### Next Slice: SAG-Style Fast-Mode Prototype

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/SagStyleExpansionHarness.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/SagStyleExpansionHarnessTest.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/SagStyleBenchmarkRunner.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/SagStyleBenchmarkRunnerTest.java`
- Create: `eval/rag/sag/.gitignore`
- Modify: `eval/rag/sag/README.md`
- Modify: `eval/rag/README.md`
- Modify: `eval/rag/RAG_METHOD_EXPLORATION.md`

- [x] **Step 1: Add failing SAG fast-mode test**

Assert that query entity hits can expand through shared chunk entities to an answer-bearing result
chunk. The first implementation attempt exposed title-driven over-expansion, so per-chunk event
entities now come from section and chunk text, not repeated paper title metadata.

- [x] **Step 2: Implement in-memory SAG-style expansion**

Treat each chunk as one event, extract lexical entities, match query entities, expand through
entities from matched events, and rank result/table-like chunks.

- [x] **Step 3: Add scorecard runner and CLI**

Load `RagBenchmarkCase` JSONL and page chunks, run SAG fast-mode per case, compute
`chunkEvidenceHitAtK`, and write standard run artifacts.

- [x] **Step 4: Run QASPER Dev 200 prototype**

Observed:

| Harness | Dataset | Cases | ChunkEv@1 | ChunkEv@3 |
|---|---|---:|---:|---:|
| `sag-style-fast-mode` | `qasper-dev-200-page-window` | 200 | 0.5% | 5.0% |

Run artifacts are ignored under:

```text
eval/rag/sag/generated/runs/2026-06-24T140000Z-sag-style-fast-mode-qasper-dev-200/
```

Interpretation: naive standalone SAG-style entity matching is not competitive. Keep SAG as a
post-retrieval expansion candidate only, gated against the page-window scientific-QA baseline.

### Next Slice: Minimal Inspect-Page Tool Boundary

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/PlannerActionType.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/PlannerAction.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/EvidencePlanner.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/EvidenceToolExecutor.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/service/EvidencePlannerTest.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/service/EvidenceToolExecutorTest.java`
- Modify: `eval/rag/README.md`
- Modify: `eval/rag/RAG_METHOD_EXPLORATION.md`

- [x] **Step 1: Add failing inspect-page executor test**

Assert `INSPECT_PAGE` with a scoped `paperId`, `pageNumber`, and `windowRadius` calls
`PaperPageWindowService.inspectPageWindow(...)` and returns an evidence ledger.

- [x] **Step 2: Add planner action fields without breaking old calls**

Add `pageNumber` and `windowRadius` to `PlannerAction`, while preserving the existing five-argument
constructor used throughout the current code/tests.

- [x] **Step 3: Wire executor and planner parser**

Add `PlannerActionType.INSPECT_PAGE`, parse optional `pageNumber/windowRadius` from planner JSON,
and route the action through `EvidenceToolExecutor` to deterministic MySQL page-window inspection.

- [x] **Step 4: Document boundary**

Record that `INSPECT_PAGE` is safe once a trusted paper id and page number are known, while
`LOCATE_PAGES` remains eval-gated until service-backed scorecards prove a retrieval win.

### Next Slice: Metadata-Injected Keyword Retrieval Field

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/entity/PaperChunkDocument.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/VectorizationService.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/HybridSearchService.java`
- Modify: `src/main/resources/es-mappings/paper_chunks.json`
- Modify: `src/test/java/com/yizhaoqi/smartpai/config/EsMappingContractTest.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/service/VectorizationServiceTest.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/service/HybridSearchServicePlanTest.java`
- Modify: `eval/rag/RAG_METHOD_EXPLORATION.md`

- [x] **Step 1: Add failing metadata retrieval-field tests**

Assert ES serialization/mapping include `retrievalTextContent`, vectorization indexes raw
`textContent` plus metadata-injected retrieval text, and the keyword branch exposes
`retrievalTextContent` as its match field.

- [x] **Step 2: Implement retrieval text without changing evidence text**

Add `retrievalTextContent` to `PaperChunkDocument` and ES mapping. Keep `textContent` as the raw
chunk evidence; during vectorization, fill `retrievalTextContent` from title, original filename,
abstract, section, source kind, evidence role, and raw chunk text.

- [x] **Step 3: Switch keyword recall field**

Change the `HybridSearchService` text branch from matching raw `textContent` to matching
`retrievalTextContent`, while semantic vector search and downstream answer evidence continue using
raw chunk text.

### Next Slice: LitSearch Corpus Coverage Verifier

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/LitSearchCorpusCoverageVerifier.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/LitSearchCorpusCoverageVerifierTest.java`
- Modify: `eval/rag/litsearch/README.md`
- Modify: `eval/rag/README.md`

- [x] **Step 1: Add failing coverage tests**

Assert the verifier reports the next safe resume offset at the first missing page, stops at the
first page whose `row_idx` sequence is wrong, and accepts the final short page.

- [x] **Step 2: Implement raw-page coverage verifier**

Scan dataset-server JSON pages by expected offset, verify `rows` length and contiguous `row_idx`,
and return `complete`, `contiguousRows`, `nextResumeOffset`, `validPages`, and problem messages.

- [x] **Step 3: Add CLI and verify local LitSearch state**

Add a small CLI entrypoint and run it on local raw pages. Current output:

```text
complete=false
contiguousRows=16000
nextResumeOffset=16000
validPages=160
problem=missing page at offset 16000: eval/rag/litsearch/raw/litsearch-corpus-clean-page-16000.json
```

### Next Slice: LitSearch Full/Sample Mislabel Guard

**Files:**
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/LitSearchDatasetIdGuard.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/eval/LitSearchKeywordBaselineCli.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/eval/LitSearchFacetPaperBaselineCli.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/eval/LitSearchKeywordBaselineCliTest.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/eval/LitSearchFacetPaperBaselineCliTest.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/eval/LitSearchRetrievalScoreCliTest.java`
- Modify: `eval/rag/litsearch/README.md`

- [x] **Step 1: Add failing mislabel tests**

Assert keyword and facet baseline CLIs reject obvious mini/sample corpus filenames when
`--dataset-id litsearch-full` would report the run as the professional full benchmark.

- [x] **Step 2: Add shared dataset-id guard**

Refuse `litsearch-full` for corpus filenames containing `mini` or `sample`; instruct the caller to
use a sample-specific dataset id instead.

- [x] **Step 3: Guard scorer gold size**

`LitSearchRetrievalScoreCli` refuses `litsearch-full` unless the gold file contains exactly 597
query cases, so mini/dev gold files cannot create professional full benchmark rows.

- [x] **Step 4: Fix mini test fixtures**

Change mini LitSearch scorer/baseline test registries to use `litsearch-mini` instead of teaching
the bad pattern of scoring a mini corpus as `LitSearch Full`.

### Next Slice: Production Paper-Level Search Index Boundary

**Files:**
- Create: `src/main/java/com/yizhaoqi/smartpai/entity/PaperSearchDocument.java`
- Create: `src/main/resources/es-mappings/paper_search.json`
- Create: `src/test/java/com/yizhaoqi/smartpai/entity/PaperSearchDocumentContractTest.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/service/ElasticsearchServiceTest.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/config/PaperSearchIndex.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/config/EsIndexInitializer.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ElasticsearchService.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/VectorizationService.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/service/VectorizationServiceTest.java`

- [x] **Step 1: Add failing paper-search boundary tests**

Assert `PaperSearchDocument` serializes title, abstract, authors, venue/year, DOI/arXiv id, access
fields, and `searchText`; assert `paper_search` has a mapping; assert Elasticsearch writes metadata
documents to a dedicated index and deletes metadata with chunk docs.

- [x] **Step 2: Implement metadata document and mapping**

Add `PaperSearchDocument.from(Paper, userId, orgTag, isPublic)` and `paper_search.json` for
paper-level metadata retrieval without adding a MySQL table or paper-level vectors.

- [x] **Step 3: Wire indexing and cleanup**

Add `PaperSearchIndex.PAPER_INDEX_NAME`, initialize the index at startup, write one metadata document
during vectorization, and delete both chunk and paper metadata documents when a paper is physically
removed.

- [x] **Step 4: Route literature search through paper_search**

`LITERATURE_SEARCH` now queries `paper_search` for first-stage candidates, scopes existing chunk
retrieval to those paper ids for citeable evidence, and falls back to the chunk-index bridge when
metadata candidates or scoped evidence are empty.

- [ ] **Step 5: Score the routed strategy**

Run service-backed Product, QASPER, and LitSearch-sample scorecards for the routed
`paper_search -> chunk evidence` strategy before treating it as validated.

- [x] **Step 5a: Add service-backed LitSearch scoring bridge**

`ServiceBackedLitSearchBenchmarkRunner` loads LitSearch gold query JSONL, calls production
`PaperRetrievalService`, normalizes imported `litsearch:<corpusid>` paper ids back to
`retrievedCorpusIds`, writes retrieved JSONL, and delegates to the existing LitSearch scorer and
cheatsheet writer.

- [ ] **Step 5b: Run a real LitSearch sample through PaperLoom services**

Import a LitSearch sample into eval-scoped MySQL/Elasticsearch rows, then run
`ServiceBackedLitSearchBenchmarkRunner` and compare its `Recall@20`/MRR against
`keyword-only-baseline` and `facet-paper-baseline`.

- [x] **Step 5b.1: Add LitSearch PaperLoom import bridge**

`LitSearchPaperLoomImporter` and `LitSearchPaperLoomImportCli` read structured LitSearch corpus
JSONL, create eval-scoped PaperLoom `Paper` and `PaperTextChunk` rows, index `paper_search` metadata
docs, and index chunk docs with eval placeholder vectors. This keeps LitSearch out of OCR and
preserves `paperId=litsearch:<corpusid>` for service-backed scoring.
