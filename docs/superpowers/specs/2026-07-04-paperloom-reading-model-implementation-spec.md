# PaperLoom Reading Model Implementation Spec

Date: 2026-07-04
Status: working spec

## Purpose

This spec focuses only on making the PaperLoom Reading Model real.

The target is:

```text
PDF
-> MinerU
-> ParsedPaper
-> PaperReadingModel
-> PaperPage
-> PAGE PaperLocation
-> SourceSpan
-> READING_MODEL_READY
```

This spec does not implement retrieval tools, Source Quote answer rules, chunk search, or
Conversation memory. Those come after Reading Model persistence is proven.

## Problem To Solve

PaperLoom already parses PDFs into `ParsedPaper`, then stores chunks, tables, figures, formulas, and
visual assets. It does not yet have a product-owned reading model that says:

- which parser output became readable product pages
- which durable page locations exist
- whether the paper has enough readable page content to enter later retrieval
- how readable text traces back to parser elements, page numbers, and optional bounding boxes

Without this layer, later tools would either read parser artifacts directly or infer readiness from
legacy chunk/vectorization state. Both are the wrong boundary.

## Boundary

In scope:

- Persist `PaperReadingModel`.
- Persist `PaperPage`.
- Persist PAGE `PaperLocation`.
- Store page-level `SourceSpan` metadata.
- Build the model from `ParsedPaper`.
- Hook the builder into `ParseService` after parsing and artifact persistence.
- Mark `READING_MODEL_READY` only when page-level readable content exists.
- Record build diagnostics so unknown MinerU details can be resolved by running real PDFs.

Out of scope:

- `ReadingChunk`.
- Elasticsearch indexing.
- `find_reading_locations`.
- `read_locations`.
- `trace_source_quotes`.
- `PaperSourceQuote`.
- Answer Guard.
- Reliable SECTION / TABLE / FIGURE modeling.
- PageIndex, summaries, rerankers, GraphRAG, visual retrieval.

## Readiness Terms

This spec uses one narrow state:

```text
READING_MODEL_READY
```

It means PaperLoom has persisted readable pages and PAGE locations for the paper.

It does not mean the paper is ready for LLM retrieval. Retrieval readiness is later:

```text
READING_MODEL_READY + READING_INDEX_READY
```

Do not expose model-only readiness to ReAct tools as a searchable paper.

## Current Code Anchors

Use these as the first implementation touch points:

- `ParseService.parseAndSave(...)`
  - already has `paperId`, `ParsedPaper`, `userId`, `orgTag`, and `isPublic`
  - call Reading Model build after parser artifact save and before chunking
- `ParsedPaper`
  - contains parser name/version, metadata, elements, tables, figures, formulas, artifacts
- `ParsedPaperElement`
  - contains `elementId`, `pageNumber`, `readingOrder`, `elementType`, `text`,
    `sectionTitle`, `sectionLevel`, `boundingBox`, and `rawAttributes`
- `MinerUOutputMapper`
  - already maps MinerU `content_list` items into `ParsedPaperElement`

## Domain Model

### PaperReadingModel

One build attempt for one Product Paper.

Minimum fields:

```text
id
paperId
modelVersion
modelStatus: READING_MODEL_BUILDING | READING_MODEL_READY | READING_MODEL_FAILED
isCurrent
parserName
parserVersion
pageCount
readablePageCount
readableCharCount
failureReason
diagnosticsJson
createdAt
updatedAt
```

Rules:

- `modelVersion` is an internal opaque version for one Reading Model build.
- A successful new model becomes `isCurrent=true`.
- Previous models are kept for audit/debug, but only one model per paper may be current.
- A failed new build does not become current.
- Do not infer this state from `paper.vectorizationStatus`.

### PaperPage

A page of readable text owned by the Reading Model.

Minimum fields:

```text
id
paperId
modelVersion
pageNumber
pageText
textHash
charCount
sourceSpanJson
parserName
parserVersion
userId
orgTag
isPublic
createdAt
```

Rules:

- One row per readable page.
- `pageText` is reconstructed from parser elements on that page.
- `pageText` is original readable text, not summary text and not enriched retrieval text.
- Page rows are versioned by `modelVersion`.
- A page with only blank text is not a readable page.

### PaperLocation

A durable location inside one Reading Model.

Phase 1 only requires PAGE locations.

Minimum fields:

```text
id
locationRef
paperId
modelVersion
locationType: PAGE
pageNumber
sectionTitle
sourceSpanJson
contentKind
userId
orgTag
isPublic
createdAt
```

Rules:

- Every `PaperPage` must have exactly one PAGE `PaperLocation`.
- `locationRef` is opaque, such as `page_ref_...`.
- `locationRef` is stable only inside its `modelVersion`.
- Rebuilding the Reading Model may create new `locationRef` values.
- Old `locationRef` values must not be used as current reading coordinates after rebuild.

### SourceSpan

MVP does not need a separate `SourceSpan` table.

Store SourceSpan as JSON on `PaperPage` and `PaperLocation`.

Minimum shape:

```json
{
  "parserName": "MinerU",
  "parserVersion": "...",
  "pageNumber": 1,
  "elementIds": ["..."],
  "readingOrderFrom": 1,
  "readingOrderTo": 12,
  "bbox": null,
  "sourceKinds": ["PARAGRAPH", "TABLE", "FIGURE"],
  "rawArtifactRef": null
}
```

Rules:

- `SourceSpan` proves where the readable page/location came from.
- `SourceSpan` is not a Source Quote.
- Exact character offsets are not required for phase 1.
- If MinerU gives useful bbox data, store it; if not, do not block readiness.

## Builder Rules

Build `PaperPage` from `ParsedPaper.elements`.

Element inclusion:

- include elements with non-blank `text`
- require valid positive `pageNumber`
- sort by `pageNumber`, then `readingOrder`
- include titles, headings, paragraphs, lists, captions, formulas, table text, and figure readable text
- ignore blank elements
- record elements skipped because of missing page number or blank text in diagnostics

Text normalization:

- trim each element text
- normalize line endings to `\n`
- remove NUL characters
- join element blocks with a blank line
- do not rewrite, summarize, translate, or enrich text

Page readiness:

```text
READING_MODEL_READY if:
- at least one PaperPage exists
- every PaperPage has pageText with non-blank text
- every PaperPage has exactly one PAGE PaperLocation
```

Failure:

```text
READING_MODEL_FAILED if:
- ParsedPaper is missing
- ParsedPaper.elements is empty
- no element has both valid pageNumber and non-blank text
```

Do not fail Reading Model readiness because of:

- missing section tree
- missing table structure
- missing figure structure
- missing formula structure
- missing bbox
- missing page screenshot
- missing summaries

## Service Flow

Implement a small service boundary:

```text
PaperReadingModelService.replaceFromParsedPaper(
  paperId,
  parsedPaper,
  userId,
  orgTag,
  isPublic
)
```

Flow:

```text
create PaperReadingModel(modelStatus=READING_MODEL_BUILDING, isCurrent=false)
-> build PaperPage rows
-> build PAGE PaperLocation rows
-> persist pages and locations
-> set modelStatus=READING_MODEL_READY
-> set this model isCurrent=true
-> set previous current models for same paper isCurrent=false
```

Validation failure flow:

```text
create or update PaperReadingModel(modelStatus=READING_MODEL_FAILED)
-> store failureReason and diagnosticsJson
-> do not make it current
-> abort downstream reading-dependent work
```

Transaction rule:

- Persist model, pages, locations, and current-model flip in one transaction.
- If persistence fails, no partial current model should be visible.
- If database persistence fails before the FAILED row can be saved, surface the processing error
  rather than pretending a readable model exists.

## Integration Point

In `ParseService.parseAndSave(...)`, call the service here:

```text
ParsedPaper parsedPaper = paperPdfParser.parse(...)
updatePaperMetadata(...)
paperParserArtifactService.saveParserArtifact(...)
paperReadingModelService.replaceFromParsedPaper(...)
paperTableService.replaceTables(...)
paperFigureService.replaceFigures(...)
paperFormulaService.replaceFormulas(...)
paperVisualAssetService.replaceVisualAssets(...)
paperChunkBuilder.buildChunks(...)
```

Reason:

- parser output exists
- parser artifacts are saved for debugging
- product identity and permission metadata are available
- chunk/index phases can later depend on Reading Model identity

## Implementation Phases

### Phase 1: Persist The Model Skeleton

Create:

- `PaperReadingModel`
- `PaperPage`
- `PaperLocation`
- repositories for the three entities

Acceptance:

- repositories can save and query by `paperId + modelVersion`
- one paper can have multiple historical models
- only one current model is allowed by service logic

### Phase 2: Build PAGE Reading Model In Memory

Create a builder that converts `ParsedPaper` into:

- page objects
- PAGE location objects
- diagnostics

Acceptance:

- fake `ParsedPaper` with two pages creates two `PaperPage` rows and two PAGE locations
- blank elements are skipped
- elements without page number are skipped and counted
- no readable numbered text produces `READING_MODEL_FAILED`

### Phase 3: Persist And Replace Current Model

Create `PaperReadingModelService.replaceFromParsedPaper(...)`.

Acceptance:

- successful build makes the new model current
- failed build does not replace the previous current model
- repeated rebuild creates a new `modelVersion`
- pages and locations always bind to the same `modelVersion`

### Phase 4: Wire Into ParseService

Call the service after parser artifact save.

Acceptance:

- successful PDF parse creates current Reading Model rows
- parse with no readable numbered text does not enter reading-ready state
- existing chunk/table/figure code can still run only after Reading Model succeeds

### Phase 5: Run Real MinerU Samples

Run against a small set:

- born-digital text-heavy PDF
- scanned or OCR-heavy PDF
- table-heavy PDF
- figure-heavy PDF
- formula-heavy PDF

Capture per paper:

```text
parserName
parserVersion
elementCount
elementsWithPageNumber
elementsWithText
elementsSkippedNoPage
elementsSkippedBlankText
readablePageCount
readableCharCount
pagesWithoutText
hasAnyBbox
locationCount
modelStatus
failureReason
```

Acceptance:

- the spec can be revised from observed MinerU output instead of guessing
- any failing paper has an explicit failure reason

## Unknowns To Resolve By Implementation

Do not block phase 1 on these:

- whether MinerU page numbers are always present
- how reliable bbox is across PDFs
- whether figure `image_text` is useful enough for future FIGURE locations
- whether table HTML should become TABLE location text later
- whether section titles are stable enough for SECTION locations
- whether raw artifact refs need MinIO object keys or parser artifact ids

For each unknown, record observed facts in `diagnosticsJson` first. Promote it into schema only when
the implementation proves it is stable and useful.

## Tests To Add

Minimum tests:

- `PaperReadingModelBuilderTest`
- `PaperReadingModelServiceTest`
- `PaperReadingModelSourceSpanTest`
- `ParseServiceReadingModelIntegrationTest`

Required cases:

- two-page parsed paper builds two pages and two locations
- page text keeps parser reading order
- blank text is skipped
- missing page numbers do not create ready pages
- no readable numbered text fails the model
- failed rebuild does not replace previous current model
- `SourceSpan` includes parser name, parser version, page number, element ids, and reading order range

## Done For This Spec

Reading Model phase is done when:

```text
PDF parse
-> ParsedPaper exists
-> paper_reading_models current row exists
-> paper_pages rows exist
-> paper_locations PAGE rows exist
-> every page row has sourceSpanJson
-> model_status=READING_MODEL_READY
```

And when failure is visible:

```text
PDF parse
-> no readable numbered page text
-> paper_reading_models row exists
-> model_status=READING_MODEL_FAILED
-> failureReason explains why
```

Only after this is true should the project continue to `ReadingChunk`, index, tools, and Source
Quote.
