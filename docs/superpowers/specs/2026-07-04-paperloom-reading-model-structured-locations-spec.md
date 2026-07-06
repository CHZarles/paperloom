# PaperLoom Reading Model Structured Locations Spec

Date: 2026-07-04
Status: planned spec
Parent spec: `docs/superpowers/specs/2026-07-04-paperloom-reading-model-implementation-spec.md`

## Purpose

This spec defines the next narrow phase after PAGE Reading Model persistence.

The target is:

```text
ParsedPaper
-> PaperReadingModelBuilder
-> PaperPage
-> PaperSection
-> PAGE / SECTION / TABLE / FIGURE PaperLocation
-> SourceSpan
-> READING_MODEL_READY
```

This phase improves Reading Model structure so later retrieval can list, search, and read sections,
tables, and figures through the same `locationRef` boundary. It is intentionally not the
Elasticsearch Reading Index phase.

## Boundary

In scope:

- Keep PAGE locations as the required readiness baseline.
- Add SECTION locations derived from `ParsedPaperElement.sectionTitle`, headings, and reading order.
- Add TABLE locations derived from `ParsedPaper.tables`.
- Add FIGURE locations derived from `ParsedPaper.figures`.
- Add persisted `PaperSection` rows so SECTION locations have owned readable text.
- Add enough `PaperLocation` metadata to list typed locations and resolve their source object.
- Add diagnostics for structured-location coverage.
- Extend the real PDF audit to report SECTION / TABLE / FIGURE location counts.

Out of scope:

- Elasticsearch `ReadingChunk` / `ReadingIndex`.
- `find_reading_locations`.
- `read_locations`.
- `trace_source_quotes`.
- `PaperSourceQuote`.
- Answer Guard.
- Formula locations.
- Hierarchical table-of-contents editing.
- LLM summaries, section summaries, query rewrite, rerankers, GraphRAG, visual retrieval.

## Recommended Decisions Adopted

### 1. `PaperLocation` Is The Unified Reading Coordinate

All navigable Reading Model structures become `PaperLocation` rows:

```text
PAGE    -> page_ref_...
SECTION -> section_ref_...
TABLE   -> table_ref_...
FIGURE  -> figure_ref_...
```

`PaperTable`, `PaperFigure`, and `PaperSection` hold content and structure. `PaperLocation` holds
the durable coordinate for listing, reading, and later indexing.

### 2. Use A Small `PaperSection` Table

Do not store section text inside `PaperLocation.sourceSpanJson`.

Create `PaperSection` rows for section content because SECTION locations need a readable source
block that is narrower than a full page and recoverable without rereading parser artifacts.

### 3. This Phase Builds A Typed Location Index, Not Search Indexing

"Index section/table/figure" in this phase means:

```text
current modelVersion -> typed paper_locations rows -> deterministic list/filter/read precursor
```

It does not mean BM25/vector indexing yet. The later `ReadingChunk` phase will index these
locations into Elasticsearch.

### 4. TABLE And FIGURE Reuse Existing Structured Stores

TABLE locations point at `PaperTable.tableId`; FIGURE locations point at `PaperFigure.figureId`.
Those stores already keep captions, table text, figure text, bbox JSON, screenshots, and parser
metadata.

This phase may create the location rows from `ParsedPaper` before `PaperTableService` and
`PaperFigureService` persist their rows, but the ids must match exactly.

### 5. FORMULA Locations Are Deferred

Formula text remains inside PAGE / SECTION text for now. FORMULA needs its own location type,
read policy, and citation behavior later; adding it in this phase would enlarge the loop without
improving the section/table/figure path.

## Domain Model Additions

### PaperSection

A persisted section text block owned by one Reading Model version.

Minimum fields:

```text
id
paperId
modelVersion
sectionId
sectionTitle
sectionLevel
pageNumberFrom
pageNumberTo
readingOrderFrom
readingOrderTo
sectionText
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

- A section belongs to one `paperId + modelVersion`.
- `sectionId` is internal and stable only inside `modelVersion`.
- `sectionText` is original readable text reconstructed from parser elements.
- Do not summarize, translate, enrich, or rewrite section text.
- A section with only a heading and no body text may be kept if it helps outline navigation, but it
  must not become a citeable Source Quote later without readable content.
- Section hierarchy is best-effort. This phase records `sectionLevel` and ordering, not a full tree.

### PaperLocation

Extend `PaperLocation` for typed source-object resolution.

Add nullable fields:

```text
sourceObjectId
pageEndNumber
displayOrder
```

Rules:

- PAGE: `sourceObjectId` is null, `pageNumber == pageEndNumber`.
- SECTION: `sourceObjectId == PaperSection.sectionId`, page range is section page span.
- TABLE: `sourceObjectId == PaperTable.tableId`, page range is the table page.
- FIGURE: `sourceObjectId == PaperFigure.figureId`, page range is the figure page.
- `displayOrder` preserves deterministic outline/list order inside `paperId + modelVersion`.
- `locationRef` remains opaque and version-scoped.

### SourceSpan

Extend SourceSpan JSON shape for typed locations:

```json
{
  "parserName": "MinerU",
  "parserVersion": "self-hosted",
  "pageNumber": 3,
  "pageNumberFrom": 3,
  "pageNumberTo": 4,
  "locationType": "SECTION",
  "sourceObjectId": "section_...",
  "elementIds": ["..."],
  "readingOrderFrom": 21,
  "readingOrderTo": 44,
  "bbox": null,
  "sourceKinds": ["HEADING", "PARAGRAPH", "TABLE"],
  "rawArtifactRef": null
}
```

Rules:

- PAGE SourceSpan keeps the phase-1 shape and may include the new fields.
- SECTION SourceSpan is element-range based.
- TABLE SourceSpan includes `tableId`, `elementId`, page number, bbox, and source kind `TABLE`.
- FIGURE SourceSpan includes `figureId`, `elementId`, page number, bbox, detection source, and
  source kind `FIGURE`.
- SourceSpan still proves provenance only. It is not a Source Quote.

## Builder Rules

### PAGE

Keep existing PAGE behavior unchanged:

- PAGE locations are required.
- PAGE text drives `READING_MODEL_READY`.
- Missing section/table/figure structure never fails readiness.

### SECTION

Build sections from readable elements:

1. Sort readable elements by page number then reading order.
2. Start a section when an element is `HEADING` with nonblank text.
3. If elements have a nonblank `sectionTitle` before the first heading, group them under that
   section title.
4. If no section signal exists, do not create a synthetic section for the whole paper in this phase;
   PAGE locations already cover the fallback.
5. Section body includes readable elements until the next section start.
6. Store heading text as part of `sectionText`.
7. Record page range, reading-order range, source kinds, and element ids.

Acceptance edge cases:

- Duplicate section titles produce distinct `sectionId` values.
- Multi-page sections produce one SECTION location with `pageNumberFrom` and `pageNumberTo`.
- Blank headings do not create sections.
- If section extraction creates zero sections, the model can still be READY with PAGE locations.

### TABLE

Create one TABLE location for each parsed table with:

- valid `tableId`
- valid positive `pageNumber`
- usable text from `tableText`, `tableMarkdown`, or `caption`

TABLE location content is later read from `PaperTable`, not from `PaperLocation`.

Skip and count tables that have no page number, no id, or no usable text.

### FIGURE

Create one FIGURE location for each parsed figure with:

- valid `figureId`
- valid positive `pageNumber`
- usable text from `caption` or `figureText`

FIGURE location content is later read from `PaperFigure`, not from `PaperLocation`.

Skip and count figures that have no page number, no id, or no usable caption/text.

## Diagnostics

Add these fields to `PaperReadingModel.diagnosticsJson`:

```text
pageLocationCount
sectionCount
sectionLocationCount
tableCount
tableLocationCount
figureCount
figureLocationCount
structuredLocationCount
tablesSkippedNoPage
tablesSkippedNoId
tablesSkippedBlankText
figuresSkippedNoPage
figuresSkippedNoId
figuresSkippedBlankText
sectionsSkippedBlankText
locationCount
```

Rules:

- `locationCount` includes PAGE + SECTION + TABLE + FIGURE.
- Existing `readablePageCount`, `readableCharCount`, and `pagesWithoutText` semantics do not change.
- A mismatch between parsed tables and TABLE locations is diagnostic, not a readiness failure.

## Integration Point

Replace the current builder/service output:

```text
PaperReadingModelBuildResult(
  pages,
  locations,
  diagnosticsJson
)
```

with:

```text
PaperReadingModelBuildResult(
  pages,
  sections,
  locations,
  diagnosticsJson
)
```

`PaperReadingModelService.replaceFromParsedPaper(...)` persists:

```text
PaperReadingModel BUILDING
-> PaperPage rows
-> PaperSection rows
-> PaperLocation rows
-> PaperReadingModel READY/current
```

The service remains the Reading Model transaction boundary. Existing table/figure/formula services
remain in `ParseService` after Reading Model creation for now.

## Tests To Add

Minimum tests:

- `PaperReadingModelStructuredLocationBuilderTest`
- `PaperReadingModelStructuredLocationServiceTest`
- extend `PaperReadingModelRealPdfAuditTest`

Required cases:

- a parsed paper with two headings creates two SECTION locations and two `PaperSection` rows
- a section spanning pages records `pageNumberFrom` and `pageNumberTo`
- duplicate section titles create distinct section refs
- parsed table creates TABLE location with `sourceObjectId=tableId`
- parsed figure creates FIGURE location with `sourceObjectId=figureId`
- table/figure without text or page is skipped and counted
- PAGE readiness still succeeds when SECTION/TABLE/FIGURE counts are zero
- service persists pages, sections, and all typed locations under the same `modelVersion`
- real PDF audit reports SECTION/TABLE/FIGURE counts without reading MinerU artifacts directly

## Done For This Phase

This phase is done when:

```text
ParsedPaper
-> current PaperReadingModel exists
-> paper_pages rows exist
-> paper_sections rows may exist
-> paper_locations contains PAGE rows
-> paper_locations may contain SECTION / TABLE / FIGURE rows
-> diagnostics report structured-location coverage
-> focused tests pass
-> real PDF smoke/audit reports the new counts
```

Non-goal reminder:

```text
No ReadingChunk, Elasticsearch, LLM tool, SourceQuote, or Answer Guard work in this phase.
```

After this phase, run a narrow Reading Model Element Retention pass before `ReadingChunk` creation.
That pass should persist every important MinerU-derived parser object as a searchable Reading Model
Element, even when the object is not useful as a top-level PAGE / SECTION / TABLE / FIGURE location.
