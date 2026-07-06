# Paper Reading Model Real PDF Audit - 2026-07-04

## 2026-07-05 Follow-Up

The original structured audit reported `FIGURE locations = 0` because the mapper did not read
MinerU's current `image_caption` and `chart_caption` fields. The follow-up probe and mapper fix are
documented in:

```text
docs/qa/figure-caption-capture-probe-2026-07-05.md
```

On the 4-PDF caption probe sample, FIGURE locations improved from 0/21 figure objects to 12/21. The
remaining skipped objects were empty chart objects or panel-only chart labels, which should not
become standalone FIGURE locations.

## Purpose

Audit the Paper Reading Model structured-location phase with real paper PDFs instead of synthetic
parser fixtures.

The exercised path is:

```text
data/*.pdf
-> MinerU sidecar
-> ParsedPaper
-> PaperReadingModelService.replaceFromParsedPaper
-> paper_reading_models / paper_pages / paper_sections / paper_locations
```

This audit focuses on whether real MinerU output can produce durable PAGE, SECTION, TABLE, and
FIGURE reading coordinates. It does not validate the full upload, Kafka, Elasticsearch, MinIO,
retrieval, or chat-answering product path.

## Environment

- Date: 2026-07-04 CST
- Workspace: `/home/charles/PaiSmart`
- Java: 21.0.11, Spring Boot 3.4.2
- Test profile: `test`
- Test database: H2 in MySQL mode
- Disabled external product services in test context: Elasticsearch init, Kafka listener/autoconfig,
  admin bootstrap, paper bootstrap
- MinerU endpoint used by tests: `http://127.0.0.1:8010`
- Current local `data/` corpus size: 77 PDFs
- Audit sample size: 8 PDFs

MinerU health evidence after the verification runs:

```json
{"status":"healthy","version":"3.4.0","protocol_version":2,"queued_tasks":0,"processing_tasks":0,"completed_tasks":36,"failed_tasks":0,"max_concurrent_requests":1,"processing_window_size":8,"task_retention_seconds":86400,"task_cleanup_interval_seconds":300}
```

Important local runtime note: `127.0.0.1:8000` is occupied by another uvicorn service and returns
`404` for `/health`. Real MinerU commands in this workspace must explicitly set:

```bash
-Dpaper.parsing.mineru.base-url=http://127.0.0.1:8010
```

## Harness Changes

`PaperReadingModelRealPdfAuditTest` now records structured-location effects in addition to page
readability:

- injects `PaperSectionRepository`
- persists and reads `paper_sections`
- records SECTION, TABLE, FIGURE, and aggregate structured-location counts per PDF
- records table/figure skip reasons from build diagnostics
- validates PAGE, SECTION, TABLE, and FIGURE invariants for each audited PDF

The structured audit wrote per-PDF JSONL evidence to:

```text
target/paper-reading-model-structured-locations-audit/summary.jsonl
```

The disabled-by-default real PDF smoke test remains available for the narrower builder-only path:

- `src/test/java/com/yizhaoqi/smartpai/service/PaperReadingModelDataPdfSmokeTest.java`

## Validated Invariants

For every audited PDF, the test validates:

- parsing completes through the MinerU `PaperPdfParser` bean
- `PaperReadingModelService.replaceFromParsedPaper(...)` persists a `READING_MODEL_READY` model
- the persisted model is current
- model parser name/version match the parsed paper
- model readable page and character counts match persisted `paper_pages`
- every readable page has nonblank `pageText`
- PAGE location count equals readable page count
- SECTION location count equals persisted section count
- TABLE location count does not exceed parsed table count
- FIGURE location count does not exceed parsed figure count
- every typed location has parseable `sourceSpanJson`
- every non-PAGE typed location has a nonblank `sourceObjectId`
- every location ref uses the expected opaque prefix: `page_ref_`, `section_ref_`, `table_ref_`,
  or `figure_ref_`
- page source spans include page number, source element IDs, and source kinds

## Verification Commands

### MinerU health

```bash
curl -sS -m 3 http://127.0.0.1:8010/health
```

Result: HTTP service returned healthy JSON shown above.

### Structured real PDF audit over 8 PDFs

```bash
mvn -q \
  -Dtest=PaperReadingModelRealPdfAuditTest \
  -Dpaperloom.reading-model.audit=true \
  -Dpaper.parsing.mineru.base-url=http://127.0.0.1:8010 \
  -Dpaperloom.reading-model.audit.output=target/paper-reading-model-structured-locations-audit/summary.jsonl \
  test
```

Surefire result:

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 904.2 s
```

### Real PDF smoke over 3 PDFs

```bash
mvn -q \
  -Dtest=PaperReadingModelDataPdfSmokeTest \
  -Dpaperloom.reading-model.real-pdf=true \
  -Dpaper.parsing.mineru.base-url=http://127.0.0.1:8010 \
  test
```

Surefire result:

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 161.2 s
```

## Structured Audit Summary

Summary from `target/paper-reading-model-structured-locations-audit/summary.jsonl`:

| Metric | Value |
| --- | ---: |
| PDFs audited | 8 |
| Passing PDFs | 8 |
| Failing PDFs | 0 |
| Total PDF size | 70.8 MB |
| Metadata pages | 512 |
| Readable pages persisted | 498 |
| PAGE locations persisted | 498 |
| Page coverage | 97.3% |
| `paper_sections` rows | 404 |
| SECTION locations | 404 |
| Parsed tables | 340 |
| TABLE locations | 283 |
| Parsed figures | 193 |
| FIGURE locations | 0 |
| Structured locations total | 687 |
| All locations total | 1,185 |
| Readable characters persisted | 1,097,506 |
| Parsed elements | 3,437 |
| Parsed formulas | 59 |
| Elements skipped for missing page number | 0 |
| Elements skipped for blank text | 476 |
| Tables skipped for missing page number | 0 |
| Tables skipped for missing id | 0 |
| Tables skipped for blank text/markdown/caption | 57 |
| Figures skipped for missing page number | 0 |
| Figures skipped for missing id | 0 |
| Figures skipped for blank caption/text | 193 |
| Metadata pages without readable text | 14 |
| Sum of per-PDF parse/build durations | 961.4 s |
| Slowest PDF | `data/2505.04620.pdf`, 670.8 s |

Per-PDF structured evidence:

| PDF | Time s | Pages | Readable | PAGE loc | Sections | SECTION loc | Tables | TABLE loc | Figures | FIGURE loc | Structured loc | Table skips | Figure skips |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `data/2601.09032v1.pdf` | 23.8 | 12 | 12 | 12 | 37 | 37 | 0 | 0 | 2 | 0 | 37 | 0 | 2 |
| `data/2408.15769.pdf` | 30.8 | 17 | 17 | 17 | 26 | 26 | 2 | 2 | 1 | 0 | 28 | 0 | 1 |
| `data/2503.16416.pdf` | 31.6 | 25 | 16 | 16 | 33 | 33 | 2 | 2 | 2 | 0 | 35 | 0 | 2 |
| `data/2412.08972.pdf` | 37.0 | 23 | 18 | 18 | 38 | 38 | 10 | 10 | 16 | 0 | 48 | 0 | 16 |
| `data/2401.13178.pdf` | 58.7 | 38 | 38 | 38 | 61 | 61 | 20 | 20 | 24 | 0 | 81 | 0 | 24 |
| `data/2503.05244.pdf` | 40.8 | 34 | 34 | 34 | 67 | 67 | 9 | 9 | 8 | 0 | 76 | 0 | 8 |
| `data/2308.03688.pdf` | 67.8 | 58 | 58 | 58 | 77 | 77 | 6 | 6 | 45 | 0 | 83 | 0 | 45 |
| `data/2505.04620.pdf` | 670.8 | 305 | 305 | 305 | 65 | 65 | 291 | 234 | 95 | 0 | 299 | 57 | 95 |

## Parsing Effects

The structured-location pass turns the 498 readable pages into 1,185 addressable locations:

- 498 PAGE locations, one per readable page
- 404 SECTION locations, backed by 404 `paper_sections` rows
- 283 TABLE locations, backed by existing parsed table objects
- 0 FIGURE locations in this real sample, because every parsed figure lacked nonblank caption/text

The most useful positive signal is that no readable element was skipped for a missing page number.
That supports the central Reading Model assumption that MinerU output can be normalized into
page-addressable rows without reopening raw MinerU artifacts.

The structured-location effect is strongest for sections and tables. Sections were available in all
sample PDFs, and table locations were materialized for 283 of 340 parsed tables. The 57 skipped
tables were all skipped because the parsed table object had no nonblank table text, markdown, or
caption; none were skipped for missing page number or missing id.

The main unresolved extraction gap is figures. MinerU produced 193 figure objects in the sample, but
the mapper surfaced them without nonblank caption or figure text, so the builder correctly skipped
all of them under the current rule. This is visible in diagnostics as
`figuresSkippedBlankText=193`, not hidden behind `READING_MODEL_READY`.

## Reliability Assessment

For the scoped Reading Model contract, the implementation is reliable on the audited sample: every
PDF reached `READING_MODEL_READY`, every readable page received exactly one PAGE location, every
persisted section received exactly one SECTION location, and typed-location counts were recorded in
diagnostics.

The implementation is not a full production ingestion reliability claim. The audit used H2 rather
than MySQL and deliberately disabled Kafka, Elasticsearch, MinIO, and the upload/vectorization
worker path. It validates the builder and persistence layer after parsing, not the entire product
ingestion lifecycle.

## Findings

1. Core structured-location contract passed on real PDFs.

   Evidence: 8/8 audit PDFs passed. The run persisted 498 PAGE locations, 404 SECTION locations,
   283 TABLE locations, and 0 invalid over-counts for TABLE or FIGURE locations.

2. SECTION rows and SECTION locations match one-to-one.

   Evidence: the audit persisted 404 `paper_sections` rows and 404 SECTION locations. The test
   asserts this equality per PDF.

3. TABLE location creation is useful but depends on nonblank table payload.

   Evidence: 283 of 340 parsed tables became TABLE locations. The remaining 57 were skipped because
   no nonblank `tableText`, `tableMarkdown`, or `caption` was available.

4. FIGURE parser payloads need follow-up before figure locations appear in real data.

   Evidence: all 193 parsed figures were skipped for blank caption/text. No figure was skipped for
   missing page number or missing id.

5. Page coverage is good but not perfect.

   Evidence: 498 readable pages out of 512 metadata pages, or 97.3%. Two PDFs had pages without
   readable text: `data/2503.16416.pdf` had 9 and `data/2412.08972.pdf` had 5.

6. The biggest operational risk remains latency.

   Evidence: `data/2505.04620.pdf` took 670.8 seconds by itself. The MinerU sidecar reports
   `max_concurrent_requests=1`, so long papers can monopolize local parsing.

7. The local default MinerU URL is unsafe on this machine.

   Evidence: `127.0.0.1:8000` is not MinerU here and returns `/health` 404. Tests and backend runs
   must set `PAPER_PARSING_MINERU_BASE_URL=http://127.0.0.1:8010` or the equivalent Maven property.

## Recommendations

1. Keep the structured-location audit disabled by default.

   It is too slow for every local test run, but it is valuable as a release, nightly, or manual
   reliability audit.

2. Add a mapper follow-up for figure captions/text.

   The builder is intentionally conservative: it creates FIGURE locations only when the parsed
   figure has useful text. The real audit shows that the current MinerU mapping path is not yet
   surfacing figure captions/text for this sample.

3. Add page-level diagnostics for unreadable metadata pages.

   The aggregate `pagesWithoutText` value is useful, but future audits should record exact page
   numbers so reviewers can distinguish blank pages from extraction misses.

4. Surface structured counts and skip reasons in product or admin diagnostics.

   A paper can be `READING_MODEL_READY` while still having skipped tables/figures. That is
   acceptable if visible and risky if hidden.

5. Run a larger corpus before calling this production-ready.

   This audit covered 8 real PDFs out of the current 77 local `data/*.pdf` files. A useful next gate
   would run all local PDFs or a stratified sample by size/page count and fail on unexpected parse
   exceptions, missing page numbers, or low page coverage.

## Verdict

For this phase, the Reading Model structured-location implementation passes the real-PDF audit.

The phase is strong enough to continue to the next planned ReadingChunk work, with three caveats:
figure caption/text mapping needs follow-up, partial page coverage must remain visible, and
long-paper MinerU latency needs operational handling before this can be treated as production-grade
ingestion reliability.
