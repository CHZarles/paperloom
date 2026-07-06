# PaperLoom Reading Model Persistence Closure Spec

Date: 2026-07-05
Status: implemented for persistence closure; retrieval/index migration remains out of scope
Prior phase: `docs/superpowers/specs/2026-07-05-paperloom-reading-model-element-retention-spec.md`
Research:
`paperloom-reading-retrieval-pipeline-research/reading-model-persistence-closure.md`
Spec readiness review:
`docs/qa/reading-model-persistence-closure-spec-readiness-review-2026-07-05.md`
Decision record:
`docs/adr/0004-use-reading-elements-as-canonical-typed-content.md`
Verification:
`docs/qa/paper-reading-model-persistence-closure-audit-2026-07-05.md`

## Purpose

This spec defines the next Reading Model persistence phase. It is intentionally a persistence spec,
not a retrieval spec.

The target is:

```text
PDF
-> MinerU output
-> PaperLoom-owned Current Reading Model
-> physical pages
-> canonical reading elements
-> navigation locations
-> visual assets and visual asset gaps
-> persistence inventory audit
```

The phase is done when every parser object and every parser image reference can be found again in
product-owned persistence, and every page-numbered retained object can be routed to a paper page.

## Corrected Principle

The Reading Model must not maintain several canonical stores for the same parser object.

Recommended canonical split:

```text
PaperReadingElement = canonical parser/content inventory
PaperLocation       = navigation coordinate
PaperVisualAsset    = visual evidence or visual evidence gap
PaperPage           = physical PDF page surface
PaperSection        = aggregate section text block
```

Do not continue treating these as canonical stores:

```text
paper_tables
paper_figures
paper_formulas
```

They should be removed during this refactor or temporarily treated only as derived projections while
call sites are moved.

## Boundary

In scope:

- Make `PaperPage` represent physical PDF pages, including textless pages.
- Create PAGE `PaperLocation` rows for all physical pages.
- Make `PaperReadingElement` the canonical persisted row for table, figure, chart, formula, panel,
  caption fragment, text, and visual-only parser objects.
- Ensure one MinerU `content_list` item maps to exactly one canonical `PaperReadingElement`.
- Make TABLE and FIGURE `PaperLocation` rows target `PaperReadingElement.readingElementId`.
- Keep SECTION locations targeting `PaperSection.sectionId`.
- Keep FORMULA locations deferred.
- Persist `PaperVisualAsset` rows for available parser images and missing/failed parser image refs.
- Link visual assets to `PaperReadingElement` through `readingElementId`.
- Remove or demote `paper_tables`, `paper_figures`, and `paper_formulas` as canonical stores.
- Extend real PDF audit to prove physical page, element inventory, visual evidence, typed content,
  and route closure.

Out of scope:

- final Elasticsearch ranking
- ReadingChunk and ReadingIndex rebuild
- LLM-facing `find_reading_locations`
- LLM-facing `read_locations`
- Source Quote persistence
- Answer Guard
- OCR/VLM interpretation
- automatic table splitting by human-visible table number
- public figure-panel navigation
- FORMULA `PaperLocation`

## Current Code Facts

Current code already has:

- `paper_reading_models` with `modelVersion`.
- `paper_pages`, currently only for readable pages.
- `paper_sections`.
- `paper_locations`, currently using `sourceObjectId` for section/table/figure source ids.
- `paper_reading_elements`, already retaining table/figure/formula objects and panel labels.
- `paper_visual_assets`, already storing page screenshots, crops, and `PARSER_IMAGE` assets.
- `paper_tables`, `paper_figures`, and `paper_formulas`, currently paper-level typed stores.
- legacy `paper_text_chunks`, `SearchResult`, and `PaperChunkDocument` fields such as `tableId`,
  `figureId`, and `formulaId`.

This phase should simplify the canonical model even if it requires broad edits. The project is still
pre-user, so do not preserve compatibility-only structures.

## Domain Decisions

### 1. What is one ReadingElement?

Recommended answer:

```text
One MinerU content_list item -> exactly one canonical PaperReadingElement.
```

Rules:

- `ParsedPaperTable`, `ParsedPaperFigure`, and `ParsedPaperFormula` may remain mapper conveniences,
  but they must not create extra persisted canonical identities.
- Caption arrays inside one MinerU image/table object remain fields on that same element unless
  MinerU emits separate `content_list` items.
- A table, figure, chart, formula, panel label, and visual-only object are all `PaperReadingElement`
  rows.
- Parser ids are provenance, not product identity.

### 2. What replaces parser tableId and figureId?

Recommended answer:

```text
readingElementId is the product identity for TABLE and FIGURE locations.
```

Rules:

- TABLE and FIGURE `PaperLocation` rows target `PaperReadingElement.readingElementId`.
- SECTION `PaperLocation` rows target `PaperSection.sectionId`.
- PAGE `PaperLocation` rows target the page number.
- Parser-specific ids can remain on `PaperReadingElement` as provenance fields or inside
  `rawAttributesJson`.

### 3. What is the physical page count authority?

Recommended answer:

```text
Use PDF document/render page count as the authority for Physical Page rows.
```

Rules:

- MinerU metadata page count remains diagnostic.
- A parser-inferred max page number is not enough because it can miss textless pages.
- `ParseService` or its parsing pipeline must provide the authoritative PDF page count to Reading
  Model building.

### 4. Where do structured payloads live?

Recommended answer:

```text
bodyText stores readable text. structuredPayloadJson stores structured parser payload.
```

Rules:

- TABLE: `captionText` stores caption; `bodyText` stores readable table text; `structuredPayloadJson`
  stores markdown/html/shape metadata when useful.
- IMAGE/CHART: `captionText` stores caption; `bodyText` stores readable figure/chart text.
- FORMULA: `bodyText` stores readable latex/context; `structuredPayloadJson` stores formula variants
  when useful.
- Do not reintroduce separate canonical table/figure/formula tables just to hold payload shape.

### 5. How is section context resolved?

Recommended answer:

```text
Resolve section context from PaperSection page/order spans for now.
```

Rules:

- Do not add `sectionLocationRef` to every element in this phase.
- Add or keep a resolver contract:
  `paperId + modelVersion + pageNumber + readingOrder -> optional PaperSection`.
- Imperfect section assignment must not block retention or route closure.

### 6. What happens to typed stores?

Recommended answer:

```text
Remove them as canonical stores during this refactor.
```

Rules:

- Replace `PaperTableService`, `PaperFigureService`, and `PaperFormulaService` canonical reads with
  ReadingElement-backed reads.
- Update product endpoints and visual asset code to use `PaperReadingElement`.
- If a temporary projection remains for compile sequencing, it must be derived and must not receive
  new canonical fields.

### 7. What happens to legacy chunk ids?

Recommended answer:

```text
Do not solve ReadingChunk here, but stop building new persistence around tableId/figureId/formulaId.
```

Rules:

- Legacy chunk fields may remain temporarily for compile and existing retrieval tests.
- They are not canonical ids.
- A later ReadingChunk spec should replace them with `locationRef` and/or `readingElementId`.

## Target Persistence Model

### PaperReadingModel

No major schema change is required.

Meaning:

```text
one versioned Reading Model snapshot for one Product Paper
```

Required behavior:

- `pageCount` should track physical page count when known.
- `readablePageCount` should track pages with readable text.
- `diagnosticsJson` must report physical page count, readable page count, textless page count, and
  persistence inventory counts.

### PaperPage

Meaning:

```text
one physical PDF page surface inside one modelVersion
```

Schema changes:

```text
add textStatus
allow pageText to be empty string
allow charCount = 0
```

Recommended `textStatus` values:

```text
READABLE
TEXTLESS
PARSER_MISSING
```

Rules:

- Create one `PaperPage` row for every physical PDF page.
- Create one PAGE `PaperLocation` for every physical PDF page.
- Textless pages are valid pages.
- PAGE existence does not imply citeable text.
- `READING_MODEL_READY` still requires at least one readable page.

### PaperSection

Keep `PaperSection` as a versioned aggregate text block.

Rules:

- `PaperSection` remains useful because a section is an aggregate of many reading elements, not one
  MinerU content item.
- SECTION `PaperLocation` targets `sectionId`.
- Section context for an element is resolved by page/order spans, not by storing a per-element
  section ref.

### PaperReadingElement

Meaning:

```text
the canonical retained product object for one MinerU content_list item
```

Schema changes:

```text
add contentListIndex
add sourceSpanJson
add structuredPayloadJson
remove or stop using parentSourceObjectId as canonical relationship
remove or stop using parentLocationRef as stored relationship
```

Minimum fields after this phase:

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
structuredPayloadJson
rawAttributesJson
parserName
parserVersion
userId
orgTag
isPublic
```

Fields deliberately not added:

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

Derivation rules:

- Page route derives from `paperId + modelVersion + pageNumber`.
- Section context derives from `paper_sections` page/order spans.
- Parent route derives from `parentReadingElementId -> parent.locationRef`.
- Searchable status derives from `captionText`, `bodyText`, and `searchableText`.
- Visual status derives from `parserImagePath` and `paper_visual_assets`.

Element type rules:

```text
TABLE   -> canonical table object
IMAGE   -> canonical figure/image object
CHART   -> canonical chart/figure object
FORMULA -> canonical formula object
```

Do not persist another canonical table/figure/formula row for these same objects.

### PaperLocation

Meaning:

```text
navigation coordinate only
```

Schema choice:

Recommended rename:

```text
sourceObjectId -> targetId
```

If the implementation chooses not to rename immediately, the column semantics must still change:

```text
PAGE    -> target is pageNumber, sourceObjectId null
SECTION -> sourceObjectId stores sectionId
TABLE   -> sourceObjectId stores readingElementId
FIGURE  -> sourceObjectId stores readingElementId
```

Rules:

- PAGE locations exist for every physical page.
- SECTION locations point to `PaperSection.sectionId`.
- TABLE locations point to TABLE `PaperReadingElement.readingElementId`.
- FIGURE locations point to IMAGE/CHART `PaperReadingElement.readingElementId`.
- FORMULA locations are not added in this phase.
- `locationRef` remains the opaque external reading coordinate.

### PaperVisualAsset

Meaning:

```text
visual evidence or visual evidence gap
```

Schema changes:

```text
add assetStatus
make objectKey nullable
link primarily by readingElementId
stop using tableId and figureId as canonical links
```

Recommended `assetStatus` values:

```text
AVAILABLE
MISSING_IN_ARTIFACT
STORAGE_FAILED
RENDER_FAILED
```

Minimum fields after this phase:

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
userId
orgTag
isPublic
```

Rules:

- A successful parser image stores `assetStatus=AVAILABLE` and a nonblank `objectKey`.
- A missing parser image stores `assetStatus=MISSING_IN_ARTIFACT`, `parserImagePath`, and
  `readingElementId` when resolvable.
- Storage failures should persist `STORAGE_FAILED` when enough context exists to record the gap.
- Render failures should persist `RENDER_FAILED` when the intended crop/page asset is known.
- Page screenshots may have no `readingElementId`; table/figure/chart crops should link to a
  reading element.

### Retired Canonical Stores

These should stop being canonical:

```text
paper_tables
paper_figures
paper_formulas
```

Mapping:

| Previous canonical store | New canonical source |
| --- | --- |
| `paper_tables` | `paper_reading_elements.elementType=TABLE` |
| `paper_figures` | `paper_reading_elements.elementType in IMAGE, CHART` |
| `paper_formulas` | `paper_reading_elements.elementType=FORMULA` |
| table/figure screenshots | `paper_visual_assets.readingElementId` |
| table/figure navigation | `paper_locations target -> readingElementId` |

## Builder Rules

### Physical Pages

Input:

```text
authoritative physical page count
ParsedPaper elements
```

Rules:

1. Create pages from `1..physicalPageCount`.
2. Build `pageText` by grouping readable parser text by page.
3. Set `textStatus=READABLE` when page text is nonblank.
4. Set `textStatus=TEXTLESS` when the page exists but no readable text was found.
5. Set `textStatus=PARSER_MISSING` only when page existence is known but parser output is missing or
   unusable for that page.
6. Create one PAGE location for every physical page.
7. PAGE location `contentKind` should distinguish readable text from page surface:

```text
PAGE_TEXT
PAGE_SURFACE
```

### Reading Elements

Input:

```text
MinerU content_list item order
ParsedPaperElement
typed mapper data derived from the same item
```

Rules:

1. Create exactly one `PaperReadingElement` per content list item.
2. Set `contentListIndex` from the 1-based content list order.
3. Set `readingElementId` as a product-owned opaque id.
4. Store parser id, parser type, parser image path, bbox, and raw attributes as provenance.
5. Fill normalized product fields:
   - `captionText`
   - `bodyText`
   - `searchableText`
   - `structuredPayloadJson`
6. For TABLE/IMAGE/CHART/FORMULA elements, do not create a second canonical persisted row.
7. For panel-only chart labels, keep them as elements and attach to a parent element when
   deterministic.
8. For ambiguous child/parent association, keep the child with `associationStatus=AMBIGUOUS`.
9. For visual-only objects, keep the element even when `searchableText` is blank if there is page,
   bbox, raw attributes, or parser image path evidence.

### TABLE And FIGURE Locations

Rules:

1. Create TABLE locations only for TABLE elements with:
   - positive `pageNumber`
   - nonblank readable payload from caption/body/structured table text
2. Create FIGURE locations only for IMAGE/CHART elements with:
   - positive `pageNumber`
   - nonblank full caption or figure text
3. Do not create FIGURE locations for panel-only chart labels.
4. Location target is `readingElementId`.
5. Location `sourceSpanJson` includes the target reading element id, page, bbox, element type, and
   parser provenance.

### FORMULA

Rules:

1. Persist FORMULA as `PaperReadingElement`.
2. Do not create FORMULA `PaperLocation`.
3. Set `locationNotCreatedReason=FORMULA_LOCATION_DEFERRED`.
4. Formula route falls back to PAGE unless the formula later gets its own location in a separate
   phase.

### Visual Assets

Rules:

1. Extract parser image bytes from MinerU `raw-result.zip`.
2. For each content list item with `img_path`, create a visual asset row.
3. If bytes are found and storage succeeds:

```text
assetStatus=AVAILABLE
objectKey=...
```

4. If bytes are absent:

```text
assetStatus=MISSING_IN_ARTIFACT
objectKey=null
failureReason=...
```

5. If object storage fails after the intended asset is known:

```text
assetStatus=STORAGE_FAILED
objectKey=null
failureReason=...
```

6. Rendered page screenshots and crops should also use `assetStatus`.
7. Parser images and rendered crops for the same table/figure/chart should both link to the same
   `readingElementId`.

## Implementation Tasks

### Task 1: Physical Page Model

Files likely affected:

- `src/main/java/com/yizhaoqi/smartpai/model/PaperPage.java`
- `src/main/java/com/yizhaoqi/smartpai/service/ParseService.java`
- `src/main/java/com/yizhaoqi/smartpai/service/PaperReadingModelBuilder.java`
- `src/main/java/com/yizhaoqi/smartpai/service/PaperReadingModelService.java`
- `src/test/java/com/yizhaoqi/smartpai/service/PaperReadingModelBuilderTest.java`
- `src/test/java/com/yizhaoqi/smartpai/service/PaperReadingModelServicePersistenceTest.java`

Steps:

1. Add page text status to `PaperPage`.
2. Change the builder input so it can use authoritative PDF page count.
3. Create `PaperPage` rows for all physical pages.
4. Create PAGE locations for all physical pages.
5. Keep readable char count and readable page count based only on pages with text.
6. Update diagnostics:

```text
physicalPageCount
parserMetadataPageCount
readablePageCount
textlessPageCount
pageLocationCount
```

Acceptance:

- A synthetic 3-page paper with text on only page 1 persists 3 `PaperPage` rows and 3 PAGE
  locations.
- READY succeeds when at least one page is readable.
- PAGE location count equals physical page count.

### Task 2: Canonical ReadingElement Inventory

Files likely affected:

- `src/main/java/com/yizhaoqi/smartpai/model/PaperReadingElement.java`
- `src/main/java/com/yizhaoqi/smartpai/paper/parser/MinerUOutputMapper.java`
- `src/main/java/com/yizhaoqi/smartpai/paper/parser/ParsedPaperElement.java`
- `src/main/java/com/yizhaoqi/smartpai/service/PaperReadingModelBuilder.java`
- `src/test/java/com/yizhaoqi/smartpai/service/PaperReadingModelElementBuilderTest.java`

Steps:

1. Add `contentListIndex`, `sourceSpanJson`, and `structuredPayloadJson`.
2. Ensure `MinerUOutputMapper` carries content list order into parsed elements.
3. Build one canonical reading element per content list item.
4. Merge typed table/figure/formula payloads into that same element.
5. Stop relying on separate persisted typed rows for canonical data.
6. Preserve raw attributes and parser image path.
7. Update diagnostics:

```text
contentListItemCount
readingElementCount
tableElementCount
figureElementCount
formulaElementCount
visualOnlyElementCount
readingElementsWithoutTextCount
```

Acceptance:

- `contentListItemCount == readingElementCount`.
- Every TABLE/IMAGE/CHART/FORMULA content item appears exactly once as a reading element.
- Visual-only chart with `img_path` and blank text is retained.
- Table markdown or HTML is available through `structuredPayloadJson` when present.

### Task 3: Location Target Simplification

Files likely affected:

- `src/main/java/com/yizhaoqi/smartpai/model/PaperLocation.java`
- `src/main/java/com/yizhaoqi/smartpai/service/PaperReadingModelBuilder.java`
- `src/main/java/com/yizhaoqi/smartpai/service/PaperReadingElementSearchService.java`
- `src/test/java/com/yizhaoqi/smartpai/service/PaperReadingModelStructuredLocationBuilderTest.java`
- `src/test/java/com/yizhaoqi/smartpai/service/PaperReadingModelElementServiceTest.java`

Steps:

1. Rename `sourceObjectId` to `targetId` or redefine the existing column semantics.
2. Point SECTION locations at `sectionId`.
3. Point TABLE locations at TABLE `readingElementId`.
4. Point FIGURE locations at IMAGE/CHART `readingElementId`.
5. Store own `locationRef` on the target reading element.
6. Update route resolution:

```text
own location
parent element own location
PAGE location
unresolved
```

7. Remove canonical use of `parentLocationRef`.

Acceptance:

- Every TABLE and FIGURE location resolves to one reading element in the same `paperId +
  modelVersion`.
- Attached panel-only chart searches route to the parent reading element location.
- Unattached panel-only chart searches route to PAGE.
- No TABLE/FIGURE location depends on parser table id or parser figure id.

### Task 4: Visual Asset Status

Files likely affected:

- `src/main/java/com/yizhaoqi/smartpai/model/PaperVisualAsset.java`
- `src/main/java/com/yizhaoqi/smartpai/service/PaperVisualAssetService.java`
- `src/main/java/com/yizhaoqi/smartpai/repository/PaperVisualAssetRepository.java`
- `src/test/java/com/yizhaoqi/smartpai/service/PaperVisualAssetServiceTest.java`

Steps:

1. Add `assetStatus`.
2. Make `objectKey` nullable.
3. Use `readingElementId` as the primary table/figure/chart visual link.
4. Persist `MISSING_IN_ARTIFACT` rows for parser image paths missing from `raw-result.zip`.
5. Persist `STORAGE_FAILED` or `RENDER_FAILED` rows when the intended asset is known.
6. Keep page screenshots linked by `paperId + modelVersion + pageNumber`.
7. Update asset lookup methods to support `readingElementId`.

Acceptance:

- A parser image path with bytes creates `AVAILABLE`.
- A parser image path without bytes creates `MISSING_IN_ARTIFACT`.
- Every `img_path` in content list has exactly one parser-image visual asset row or visual gap row.
- Table/figure/chart crops link to reading elements, not tableId/figureId.

### Task 5: Remove Or Demote Typed Stores

Files likely affected:

- `src/main/java/com/yizhaoqi/smartpai/model/PaperTable.java`
- `src/main/java/com/yizhaoqi/smartpai/model/PaperFigure.java`
- `src/main/java/com/yizhaoqi/smartpai/model/PaperFormula.java`
- `src/main/java/com/yizhaoqi/smartpai/service/PaperTableService.java`
- `src/main/java/com/yizhaoqi/smartpai/service/PaperFigureService.java`
- `src/main/java/com/yizhaoqi/smartpai/service/PaperFormulaService.java`
- `src/main/java/com/yizhaoqi/smartpai/controller/PaperController.java`
- `src/main/java/com/yizhaoqi/smartpai/service/ParseService.java`
- `src/main/java/com/yizhaoqi/smartpai/service/HybridSearchService.java`

Steps:

1. Stop calling `replaceTables`, `replaceFigures`, and `replaceFormulas` as canonical persistence in
   `ParseService`.
2. Move typed list/detail behavior to ReadingElement-backed query methods.
3. Move table/figure screenshot lookup to `PaperVisualAsset.readingElementId`.
4. Delete typed stores when all call sites are migrated.
5. If temporary compile projections remain, mark them derived and keep them out of the persistence
   closure acceptance criteria.
6. Do not add `modelVersion` or new canonical link fields to typed stores.

Acceptance:

- New parse pipeline does not create canonical `paper_tables`, `paper_figures`, or `paper_formulas`
  rows.
- Product table/figure/formula inspection reads from `paper_reading_elements`.
- Visual asset crop generation consumes reading elements.
- The only canonical persisted row for a table, figure, chart, or formula is `PaperReadingElement`.

### Task 6: Persistence Inventory Audit

Files likely affected:

- `src/test/java/com/yizhaoqi/smartpai/service/PaperReadingModelRealPdfAuditTest.java`
- `docs/qa/paper-reading-model-real-pdf-audit-2026-07-04.md`
- optional new QA note under `docs/qa/`

Steps:

1. Extend the real PDF audit to compare raw parser inventory with product persistence.
2. Write per-PDF JSONL evidence.
3. Keep audit disabled by default.
4. Audit physical pages:

```text
pdfRendererPageCount
parserMetadataPageCount
paperPageCount
pageLocationCount
readablePageCount
textlessPageCount
```

5. Audit element inventory:

```text
contentListItemCount
readingElementCount
tableElementCount
figureElementCount
formulaElementCount
visualOnlyElementCount
readingElementsWithoutTextCount
```

6. Audit visual evidence:

```text
contentListImageReferenceCount
parserImageAvailableCount
parserImageMissingCount
parserImageStorageFailedCount
visualOnlyElementWithAssetOrGapCount
```

7. Audit routing:

```text
routeOwnLocationCount
routeParentLocationCount
routePageLocationCount
routeUnresolvedCount
```

Acceptance:

- Audit proves no `content_list` item disappeared.
- Audit proves every `img_path` is represented by `PaperVisualAsset`.
- Audit proves every page-numbered element has own, parent, or PAGE route.
- Audit reports unresolved route and missing visual evidence counts explicitly.

## Verification Commands

Focused unit and persistence tests:

```bash
mvn -q \
  -Dtest=PaperReadingModelBuilderTest,PaperReadingModelElementBuilderTest,PaperReadingModelStructuredLocationBuilderTest,PaperReadingModelServicePersistenceTest,PaperReadingModelElementServiceTest,PaperVisualAssetServiceTest \
  test
```

Compile gate:

```bash
mvn -q -DskipTests test
```

Real PDF audit when MinerU sidecar is available:

```bash
mvn -q \
  -Dtest=PaperReadingModelRealPdfAuditTest \
  -Dpaperloom.reading-model.audit=true \
  -Dpaper.parsing.mineru.base-url=http://127.0.0.1:8010 \
  -Dpaperloom.reading-model.audit.output=target/paper-reading-model-persistence-closure-audit/summary.jsonl \
  test
```

The known unrelated RAG benchmark/readiness failures are not part of this persistence closure gate.

## Done For This Phase

This phase is done when:

```text
physical PDF pages
-> PaperPage + PAGE PaperLocation

MinerU content_list items
-> canonical PaperReadingElement

TABLE / FIGURE navigation
-> PaperLocation target is readingElementId

content_list.img_path
-> PaperVisualAsset AVAILABLE/MISSING/FAILED

tables / figures / formulas
-> canonical data lives in PaperReadingElement

real PDF audit
-> proves page, element, visual, and route closure
```

Success criteria:

- The focused unit and persistence tests pass.
- Compile passes.
- The real PDF audit passes on at least `data/2412.08972.pdf`.
- The audit proves `contentListItemCount == readingElementCount`.
- The audit proves `pdfRendererPageCount == paperPageCount == pageLocationCount`.
- The audit proves every parser `img_path` has an available or missing/failed visual asset row.
- The audit proves route unresolved count is zero for elements with valid page numbers, unless the
  unresolved reason is explicitly persisted and justified.

## Explicit Non-Goals For Follow-Up Specs

Do not add these to the implementation under this spec:

- ReadingChunk generation from the new model.
- Elasticsearch index migration.
- LLM-facing search/read tools.
- Source Quote creation.
- Answer Guard.
- Formula-specific navigation and citation policy.
- OCR/VLM image understanding.
