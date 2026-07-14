# PaperLoom Reading Model Persistence Closure Research

Date: 2026-07-05
Status: historical simplification record. Later implementation and verification are captured in the
current code, ADRs, and
[`../verification/reading-model-persistence-closure-spec-readiness-review-2026-07-05.md`](../verification/reading-model-persistence-closure-spec-readiness-review-2026-07-05.md).

## Purpose

This document reviews and simplifies the next Reading Model persistence phase. The target is still
the same:

```text
MinerU output
-> PaperLoom-owned persisted model
-> no parser information lost
-> every retained thing can be inspected and routed to paper/page/context
```

But the earlier plan was too column-heavy. It tried to close persistence by adding many direct links
to many tables. That would make the model harder to reason about and create several ways for the
same table, figure, or formula to drift.

Recommended simplification:

```text
Make PaperReadingElement the canonical object inventory.
Keep PaperLocation as navigation only.
Keep PaperVisualAsset as visual evidence only.
Do not keep paper_tables / paper_figures / paper_formulas as canonical stores.
```

## Non-Goals

Do not implement in this phase:

- final Elasticsearch ranking
- LLM-facing `find_reading_locations`
- LLM-facing `read_locations`
- Source Quote persistence
- Answer Guard
- OCR/VLM semantic interpretation
- table splitting by human-visible table number
- figure-panel navigation as a public feature

This is a persistence closure. It should make later retrieval possible, but it should not design
retrieval ranking yet.

## Grill Findings

### Finding 1: Typed stores duplicate ReadingElement

The previous document proposed adding `modelVersion`, `readingElementId`, `parserElementId`,
`locationRef`, page refs, section refs, raw attributes, and image paths to:

```text
paper_tables
paper_figures
paper_formulas
```

That is a warning sign. Once those fields are added, the typed stores become near-duplicates of
`paper_reading_elements`.

Recommended answer:

```text
Do not version-align typed stores as canonical tables.
Fold table/figure/formula content into PaperReadingElement.
Delete the typed stores or turn them into non-canonical projections later.
```

Rationale:

- Table text, table markdown, figure caption, figure text, formula latex, bbox, raw parser fields,
  and parser image path all fit naturally on a retained reading element.
- `paper_locations` can point to the retained element for TABLE and FIGURE navigation.
- `paper_visual_assets` can point to the same retained element for crops and parser images.
- This removes an entire class of sync bugs.

### Finding 2: Direct context-ref columns are mostly redundant

The previous document proposed adding fields such as:

```text
pageLocationRef
sectionId
sectionLocationRef
containerLocationRef
searchableStatus
visualStatus
```

Some of this is useful as derived state, but most of it should not be duplicated as first-pass
schema.

Recommended answer:

```text
Store the facts needed to derive context, not every derived context link.
```

Keep:

```text
paperId
modelVersion
pageNumber
readingOrder
elementType
locationRef
parentReadingElementId
associationStatus
attachmentRole
bboxJson
rawAttributesJson
sourceSpanJson
```

Derive:

```text
pageLocationRef        from paperId + modelVersion + pageNumber + PAGE location
sectionLocationRef     from paper_sections page/order span
parentLocationRef      from parentReadingElementId -> parent.locationRef
searchableStatus       from searchableText/captionText/bodyText
visualStatus           from parserImagePath + visual asset rows
```

Rationale:

The more stored context refs we add, the more rebuild code must keep in sync. The Current Reading
Model is immutable by `modelVersion`, so deterministic joins are acceptable and easier to audit.

### Finding 3: Visual asset gaps do need persistence

One thing should not be derived away: missing image bytes.

If MinerU emits:

```json
{"img_path": "images/chart-1.jpg"}
```

and `raw-result.zip` does not contain that file, the product must persist that fact. A log line is
not enough.

Recommended answer:

```text
Add asset status to PaperVisualAsset and allow missing/failed asset rows.
```

Minimum statuses:

```text
AVAILABLE
MISSING_IN_ARTIFACT
STORAGE_FAILED
RENDER_FAILED
```

Rationale:

The user needs to inspect why an image exists in parser metadata but cannot be displayed. That is
product state.

### Finding 4: PAGE should mean physical page

Current PAGE rows are readable pages. That is too narrow for visual inspection.

Recommended answer:

```text
Persist one PaperPage and one PAGE PaperLocation per physical PDF page.
```

Readable text becomes page content state, not page existence.

Suggested page text state:

```text
READABLE
TEXTLESS
PARSER_MISSING
```

Rationale:

A textless page can still contain a figure, chart, table, or screenshot. Visual-only elements need a
durable page route even when no textual Source Quote can be produced from that page.

### Finding 5: FORMULA location remains deferred

Recommended answer:

```text
Do not add FORMULA location yet.
Persist formulas as PaperReadingElement with elementType=FORMULA.
Route formula hits to PAGE, and later to SECTION if section resolution is available.
```

Rationale:

Formula citation and formula navigation are separate product decisions. The persistence requirement
is retention plus page/bbox/source-span awareness, not a new location type.

## Simplified Target Data Model

The simplified model has seven persistent concepts.

### 1. Parser Artifact

Keep:

```text
paper_parser_artifacts
object storage
```

Purpose:

Store raw MinerU evidence:

```text
raw-result.zip
content_list.json
middle.json
markdown
```

No new manifest table is required for the next slice. The audit can inspect `raw-result.zip`, and
`paper_visual_assets` will persist image-reference success or failure.

### 2. Reading Model

Keep:

```text
paper_reading_models
```

Purpose:

One versioned snapshot per build.

Important fields:

```text
paperId
modelVersion
isCurrent
modelStatus
pageCount
readablePageCount
diagnosticsJson
```

No simplification needed here.

### 3. Page

Keep and slightly widen:

```text
paper_pages
```

Recommended meaning:

```text
one row per physical PDF page in the modelVersion
```

Minimum fields:

```text
paperId
modelVersion
pageNumber
pageText
textHash
charCount
textStatus
sourceSpanJson
parserName
parserVersion
permission fields
```

Notes:

- `pageText` may be empty for textless pages.
- `charCount=0` is valid.
- PAGE readiness still requires at least one readable page.
- PAGE existence does not imply citeable text.

### 4. Section

Keep:

```text
paper_sections
```

Purpose:

Sections are not one MinerU object; they are aggregate reading blocks. Keeping `paper_sections` is
still useful and not a duplicate of `paper_reading_elements`.

Minimum fields stay close to current:

```text
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
sourceSpanJson
```

Do not add per-element `sectionLocationRef` yet. Resolve section context from page/order ranges.

### 5. Reading Element

Keep and make canonical:

```text
paper_reading_elements
```

Recommended meaning:

```text
one canonical retained product object for each MinerU content_list item
```

This includes:

```text
paragraph
heading
list
footnote
code
table
image
chart
formula
caption fragment
panel label
visual-only object
```

Minimum fields:

```text
paperId
modelVersion
readingElementId
contentListIndex
parserElementId
sourceObjectId
elementType
pageNumber
readingOrder
sectionTitle
parentReadingElementId
attachmentRole
associationStatus
locationRef
locationNotCreatedReason
captionText
bodyText
searchableText
captionSource
parserImagePath
bboxJson
sourceSpanJson
rawAttributesJson
parserName
parserVersion
permission fields
```

Fields to avoid in the first simplification pass:

```text
pageLocationRef
sectionLocationRef
parentLocationRef
parentSourceObjectId
searchableStatus
visualStatus
containerLocationRef
containerSourceObjectId
```

Why:

- Page route is derivable from `pageNumber`.
- Parent route is derivable from `parentReadingElementId`.
- Section route is derivable from section page/order spans.
- Searchable/visual status is derivable from text and visual asset rows.

For table/figure/formula, use the same fields:

```text
TABLE   -> captionText + bodyText/tableMarkdown + rawAttributesJson
IMAGE   -> captionText + bodyText/figureText + parserImagePath + bboxJson
CHART   -> captionText + bodyText/figureText + parserImagePath + bboxJson
FORMULA -> bodyText/latex/context + bboxJson
```

If table markdown or structured formula payload needs a cleaner place than `bodyText`, add one
generic field:

```text
structuredPayloadJson
```

Do not create three separate typed canonical stores for that.

### 6. Location

Keep:

```text
paper_locations
```

Recommended meaning:

```text
navigation coordinate only
```

Location targets:

```text
PAGE    -> pageNumber
SECTION -> sectionId
TABLE   -> readingElementId of TABLE element
FIGURE  -> readingElementId of IMAGE/CHART element
```

Recommended schema cleanup:

```text
rename sourceObjectId -> targetId
```

If renaming is too much for the next small slice, keep the column name but change the convention:

```text
For TABLE/FIGURE, sourceObjectId stores the target readingElementId.
For SECTION, sourceObjectId stores sectionId.
For PAGE, sourceObjectId is null.
```

This eliminates separate parser table ids and figure ids as navigation targets.

Location creation policy remains conservative:

- PAGE: every physical page
- SECTION: useful heading/section groups
- TABLE: table element with usable readable payload
- FIGURE: figure/chart element with usable full caption or figure text
- FORMULA: deferred

### 7. Visual Asset

Keep and simplify:

```text
paper_visual_assets
```

Recommended meaning:

```text
visual evidence or visual evidence gap
```

Minimum fields:

```text
paperId
modelVersion
assetType
assetStatus
readingElementId
pageNumber
parserImagePath
bboxJson
objectKey nullable
contentType
widthPx
heightPx
sha256
failureReason
permission fields
```

Fields to avoid as canonical links:

```text
tableId
figureId
sourceObjectId
parserElementId
pageLocationRef
sectionLocationRef
```

Why:

- Table/figure identity should come through `readingElementId`.
- Parser identity can be read from the linked `PaperReadingElement`.
- Page and section context can be resolved from the linked element.

`objectKey` must become nullable if the table stores missing/failed assets.

## Tables To Retire Or Demote

Recommended answer:

```text
Retire paper_tables, paper_figures, and paper_formulas as canonical persistence.
```

Options:

1. Delete them during the refactor and update services to query `paper_reading_elements`.
2. Keep them temporarily as derived projections only, but do not add new canonical fields to them.

Because the project is still pre-user and compatibility is not required, prefer option 1 when the
implementation phase starts.

Mapping:

| Old store | New canonical source |
| --- | --- |
| `paper_tables` | `paper_reading_elements.elementType=TABLE` |
| `paper_figures` | `paper_reading_elements.elementType in IMAGE, CHART` |
| `paper_formulas` | `paper_reading_elements.elementType=FORMULA` |
| table/figure screenshots | `paper_visual_assets.readingElementId` |
| table/figure location | `paper_locations.sourceObjectId/targetId -> readingElementId` |

## Route Resolution

A retained element should be routable without storing every route as a column.

Recommended algorithm:

```text
if element.locationRef exists:
    route to own location
else if element.parentReadingElementId exists and parent.locationRef exists:
    route to parent location
else if element.pageNumber exists and PAGE location exists:
    route to PAGE location
else:
    route unresolved, with persisted reason on element
```

Section awareness:

```text
find paper_sections where:
  pageNumberFrom <= element.pageNumber <= pageNumberTo
  and readingOrderFrom/To contains element.readingOrder when present
```

This can be implemented as a resolver service and audited. It does not need per-element
`sectionLocationRef` in the first pass.

## Simplified Closed Loops

### Loop 1: Physical Page Closure

Target:

```text
Every physical PDF page has PaperPage + PAGE location.
```

Audit:

```text
pdfRendererPageCount
paperPageCount
pageLocationCount
readablePageCount
textlessPageCount
pageScreenshotAvailableCount
```

### Loop 2: Element Inventory Closure

Target:

```text
Every MinerU content_list item has one PaperReadingElement.
```

Audit:

```text
contentListItemCount
readingElementCount
readingElementsWithRawAttributesCount
readingElementsWithBboxCount
readingElementsWithOwnLocationCount
readingElementsWithParentCount
readingElementsWithoutTextCount
```

### Loop 3: Visual Evidence Closure

Target:

```text
Every content_list.img_path has an AVAILABLE or missing/failed PaperVisualAsset row.
```

Audit:

```text
contentListImageReferenceCount
parserImageAvailableCount
parserImageMissingCount
parserImageStorageFailedCount
visualOnlyElementCount
visualOnlyElementWithAssetOrGapCount
```

### Loop 4: Navigation Closure

Target:

```text
Every retained element can route to own, parent, page, or explicit unresolved route status.
```

Audit:

```text
routeOwnLocationCount
routeParentLocationCount
routePageLocationCount
routeUnresolvedCount
```

### Loop 5: Typed Content Closure

Target:

```text
Tables, figures, charts, and formulas are retained as ReadingElement rows.
```

Audit:

```text
tableElementCount
tableLocationCount
figureElementCount
figureLocationCount
formulaElementCount
formulaLocationDeferredCount
typedElementWithoutCanonicalRowCount
```

## Recommended Implementation Phases

### Phase 1: Make PAGE Physical

Work:

- Persist `PaperPage` for every PDF page.
- Create PAGE `PaperLocation` for every PDF page.
- Add page `textStatus`.
- Keep READY gated on at least one readable page.

Done when:

```text
pdf page count == paper_pages count == PAGE location count
```

### Phase 2: Make ReadingElement Canonical

Work:

- Add `contentListIndex`.
- Add `sourceSpanJson`.
- Add optional `structuredPayloadJson` only if table/formula payload needs it.
- Ensure all tables, figures, charts, formulas, panel labels, and visual-only objects are retained
  here.
- Stop treating `paper_tables`, `paper_figures`, and `paper_formulas` as canonical stores.

Done when:

```text
content_list item count == paper_reading_elements count
```

### Phase 3: Simplify Location Targets

Work:

- For TABLE and FIGURE locations, target `readingElementId`.
- For SECTION locations, target `sectionId`.
- For PAGE locations, target page number.
- Keep FORMULA location deferred.

Done when:

```text
Every non-PAGE location resolves to either PaperSection or PaperReadingElement.
```

### Phase 4: Persist Visual Asset Gaps

Work:

- Add `assetStatus`.
- Allow `objectKey` to be null for failed/missing assets.
- Persist missing parser image rows.
- Link parser images, rendered crops, and missing asset refs by `readingElementId`.

Done when:

```text
Every img_path has AVAILABLE/MISSING/FAILED visual asset state.
```

### Phase 5: Remove Or Demote Typed Stores

Work:

- Replace table/figure/formula services with ReadingElement-backed queries.
- Remove typed tables if no longer needed.
- If temporary projections remain, mark them derived and do not add new canonical links.

Done when:

```text
There is one canonical row for a table, figure, chart, or formula: PaperReadingElement.
```

### Phase 6: Add Persistence Inventory Audit

Work:

- Extend the real PDF audit to compare raw `content_list`, reading elements, pages, locations, and
  visual assets.
- Do not require Elasticsearch or LLM tools.

Done when the audit proves:

```text
no content_list item disappeared
no img_path is only a log line
no page-numbered element lacks a PAGE route
```

## Decisions

### Decision 1: Should typed tables/figures/formulas stay canonical?

Recommended answer:

```text
No. Use PaperReadingElement as canonical.
```

### Decision 2: Should we store pageLocationRef and sectionLocationRef on every element?

Recommended answer:

```text
No. Derive them from modelVersion + page/order.
```

### Decision 3: Should missing parser images create rows?

Recommended answer:

```text
Yes. Missing visual evidence is product state.
```

### Decision 4: Should PAGE locations exist for textless pages?

Recommended answer:

```text
Yes. PAGE is a physical navigation coordinate, not a readable-text guarantee.
```

### Decision 5: Should formula navigation be added now?

Recommended answer:

```text
No. Retain formulas as elements and route them to page for now.
```

## Definition Of Done

This persistence closure is done when a real PDF audit can prove:

1. Every physical page has `PaperPage` and PAGE `PaperLocation`.
2. Every MinerU `content_list` item has exactly one canonical `PaperReadingElement`.
3. Tables, figures, charts, formulas, panel labels, and visual-only objects are all represented as
   reading elements.
4. TABLE and FIGURE locations resolve to reading elements, not parser-specific table/figure stores.
5. Formula elements are retained and page-routable, with FORMULA location explicitly deferred.
6. Every `img_path` has a visual asset row with `AVAILABLE`, `MISSING_IN_ARTIFACT`, or failure status.
7. Every page-numbered element routes to own, parent, or PAGE location.
8. Unresolved route and missing visual evidence counts are explicit audit fields.
9. `paper_tables`, `paper_figures`, and `paper_formulas` are either removed or treated only as derived
   projections.

After this, ReadingChunk and ReadingIndex can be generated from one coherent model instead of trying
to reconcile several semi-canonical stores.
