# Paper Reading Model Persistence Closure Audit - 2026-07-05

## Purpose

This note records the real-PDF verification for the Reading Model persistence-closure phase:

```text
PDF
-> MinerU output
-> PaperReadingModel
-> PaperPage
-> PaperReadingElement
-> PaperLocation
-> PaperVisualAsset / visual gap
```

The audit is intentionally about persistence closure, not final retrieval ranking.

## Command

```bash
mvn -q \
  -Dtest=PaperReadingModelRealPdfAuditTest \
  -Dpaperloom.reading-model.audit=true \
  -Dpaper.parsing.mineru.base-url=http://127.0.0.1:8010 \
  -Dpaperloom.reading-model.audit.pdfs=data/2412.08972.pdf \
  -Dpaperloom.reading-model.audit.output=target/paper-reading-model-persistence-closure-audit/summary.jsonl \
  test
```

Result:

```text
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

The first attempt failed because the local MinerU sidecar reset a task-status request while it was
still processing a task. After the sidecar returned to `processing_tasks=0`, the same command passed.

## Evidence File

```text
target/paper-reading-model-persistence-closure-audit/summary.jsonl
```

Audited PDF:

```text
data/2412.08972.pdf
```

## Summary

| Metric | Value |
| --- | ---: |
| Status | PASS |
| PDF renderer pages | 23 |
| Parser metadata pages | 23 |
| `paper_pages` rows | 23 |
| PAGE locations | 23 |
| Readable pages | 20 |
| Textless pages | 3 |
| MinerU content-list items | 209 |
| `paper_reading_elements` rows | 209 |
| Tables | 10 |
| Figures/charts | 16 |
| Formulas | 7 |
| TABLE locations | 10 |
| FIGURE locations | 8 |
| Formula locations | 0, deferred |
| Content-list image refs | 33 |
| Parser image rows, available | 0 |
| Parser image rows, missing | 0 |
| Parser image rows, storage failed | 33 |
| Parser image refs represented | 33 |
| Visual-only table/figure/chart elements | 1 |
| Visual-only elements with asset/gap | 1 |
| Visual asset/gap rows | 82 |
| Own-location routes | 18 |
| Parent-location routes | 7 |
| Page-location fallback routes | 184 |
| Unresolved routes | 0 |

The MinIO credentials in this local test environment are invalid, so parser images and rendered page
or element images could not be stored as available object keys. That is expected for this local
audit. The important persistence-closure result is that the failures were persisted as
`PaperVisualAsset` gap rows, rather than disappearing.

## Validated Invariants

- `pdfRendererPageCount == paperPageCount == pageLocationCount == 23`
- `contentListItemCount == readingElementCount == 209`
- Every content-list item with `img_path` has exactly one parser-image `PaperVisualAsset` row.
- Every parser-image row has a coherent status:
  `AVAILABLE`, `MISSING_IN_ARTIFACT`, or `STORAGE_FAILED`.
- Every visual-only table/figure/chart element has a visual asset or visual gap.
- Every page-numbered reading element routes by own location, parent location, or PAGE location.
- TABLE and FIGURE locations target `PaperReadingElement.readingElementId`.
- FORMULA elements are retained as `PaperReadingElement` and use page fallback; formula-specific
  locations remain deferred.

## Interpretation

For this PDF, persistence closure is proven at the data-model layer:

```text
physical pages -> PaperPage + PAGE PaperLocation
MinerU content_list items -> PaperReadingElement
content_list.img_path -> PaperVisualAsset available/missing/failed row
typed table/figure navigation -> PaperLocation target is readingElementId
page-numbered retained objects -> own/parent/page route
```

This does not claim final user-facing retrieval is complete. Ranking, ReadingChunk migration,
LLM-facing reading tools, OCR/VLM image understanding, and answer guarding remain follow-up work.
