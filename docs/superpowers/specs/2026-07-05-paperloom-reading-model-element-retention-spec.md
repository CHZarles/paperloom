# PaperLoom Reading Model Element Retention Spec

Date: 2026-07-05
Status: planned spec
Parent spec: `docs/superpowers/specs/2026-07-04-paperloom-reading-model-implementation-spec.md`
Prior phase: `docs/superpowers/specs/2026-07-04-paperloom-reading-model-structured-locations-spec.md`

## Purpose

This spec replaces the earlier diagnostics-only Quality Profile direction.

The corrected principle is:

```text
The Reading Model must not discard MinerU parsed information.
```

Some parser objects should not become top-level `PaperLocation` rows. A panel-only chart label such
as `(a) Recall` is a good example: it is too weak to be a standalone FIGURE location, but it is still
real paper content. When it belongs to a full figure/table, it should be attached to that parent
object, persisted with it, searchable through it, and traceable back to the PDF page and parser
object.

The next narrow phase is therefore:

```text
MinerU content_list item
-> ParsedPaper object
-> retained Reading Model Element
-> optional parent table/figure element
-> optional PAGE / SECTION / TABLE / FIGURE location
-> searchable retained text
-> real-PDF audit proves no parser object disappears
```

This phase still does not implement the full LLM-facing `read_locations`,
`find_reading_locations`, Source Quotes, Answer Guard, or Elasticsearch ranking stack. It does,
however, make the Reading Model a lossless-enough product layer that later search can index all
important parser-derived content, including non-location visual fragments.

## Why This Phase

The structured-location phase intentionally used conservative location rules:

- create FIGURE locations only for objects with a full figure caption or usable figure text
- skip panel-only chart labels as top-level FIGURE locations

That location policy is still reasonable. The mistake is treating "not a top-level location" as
"not retained by the Reading Model."

The 2026-07-05 sample `data/2412.08972.pdf` exposes the gap:

```text
16 parsed image/chart objects
8 FIGURE locations
8 panel-only or blank chart objects without FIGURE locations
```

Examples of non-location but important content:

```text
(a) Recall
(b) Precision
(a) Airline
(b) NBA
(a) Correctness
```

Those labels should not pollute the top-level figure list. They should attach to the corresponding
FIGURE when association is deterministic. Then a user searching the paper for `Recall`, `Precision`,
`Airline`, or chart panels can find the parent figure with the matched panel text.

## Grill Decisions And Recommended Answers

### 1. Should "without own location" mean discarded?

Recommended answer: **No**.

When no top-level location is created, the parser object must still be retained. "No location" must
not mean the parser object was dropped from product storage. Diagnostics and code paths should use
`locationNotCreatedReason` language instead of "skipped object" language.

### 2. What should be persisted?

Recommended answer: **A retained Reading Model Element for every MinerU content item that maps into
`ParsedPaper`**.

This includes headings, paragraphs, lists, footnotes, code blocks, tables, images, charts, formulas,
blank-labeled visual objects with bbox, and panel-only chart labels.

### 3. Should every retained element get a `PaperLocation`?

Recommended answer: **No**.

Retention and navigation are different concepts. Every important parser object should be retained
and searchable. Only useful navigation boundaries should become PAGE / SECTION / TABLE / FIGURE
locations.

### 4. Should panel-only charts be attached to the corresponding figure?

Recommended answer: **Yes, when the parent can be identified deterministically**.

Panel-only chart objects are not separate top-level figures. They are child elements of a parent
FIGURE when a same-page/order-near full-caption figure can be identified. If the parent is ambiguous,
retain the panel as page-scoped and mark `associationStatus=AMBIGUOUS` instead of guessing.

### 5. Should panel-only charts be searchable?

Recommended answer: **Yes**.

Persist them as `ReadingModelElement` rows with `elementType=CHART`, `captionSource` such as
`chart_caption_panel_only`, and searchable text such as `(a) Recall`. When attached to a parent
figure, search results should route to the parent figure location and expose the child match as
matched panel text. If no parent can be determined, route to the containing PAGE location.

### 6. Should table fragments be attached to the corresponding table?

Recommended answer: **Yes, under the same rule**.

If MinerU emits table body/caption fragments separately, attach child elements to the parent TABLE
when the association is deterministic. Do not split or merge human-visible table numbers in this
phase; preserve the fragments and their parent relationship so later table repair can make a better
choice.

### 7. Should this reintroduce table/figure version alignment?

Recommended answer: **No**.

Do not modify `PaperTable` / `PaperFigure` versioning in this phase. The retained element store is
part of the Reading Model snapshot, like `PaperPage` and `PaperSection`. Existing table/figure
stores can remain as they are for now.

### 8. Should raw MinerU fields be preserved?

Recommended answer: **Yes**.

Store normalized product fields for search and display, and also store bounded `rawAttributesJson`
for audit/debug. This is not the same as making the product read raw MinerU artifacts directly; it is
retaining parser evidence inside the product-owned Reading Model.

## Boundary

In scope:

- Add a persisted `ReadingModelElement` / `PaperReadingElement` model.
- Persist one row for each `ParsedPaperElement`.
- Persist rows for table, figure/chart, and formula parser objects even when no TABLE / FIGURE /
  FORMULA location is created.
- Store normalized searchable text for every retained element.
- Store caption source, parent attachment, association status, and location policy for
  table/figure/chart elements.
- Store raw parser attributes JSON, bbox, page number, reading order, section title, parser
  name/version, and user/org visibility metadata.
- Keep PAGE / SECTION / TABLE / FIGURE location rules conservative.
- Add audit checks proving retained element counts match parser counts.
- Add search-input checks proving panel-only chart labels and formula/table text are present in
  retained searchable text.

Out of scope:

- Elasticsearch ranking/index lifecycle.
- LLM-facing `find_reading_locations`.
- LLM-facing `read_locations`.
- Source Quote creation.
- Answer Guard.
- Automatically promoting panel labels into FIGURE locations.
- Parent/child Figure Panel navigation.
- Version-aligning existing `PaperTable` / `PaperFigure` stores.
- Supporting legacy `img_caption` or generic `caption` without fresh real-output evidence.

## Domain Model

### Reading Model Element

A Reading Model Element is a retained product-owned representation of one parser object inside a
Reading Model.

Minimum fields:

```text
id
paperId
modelVersion
readingElementId
parserElementId
sourceObjectId
elementType
pageNumber
readingOrder
sectionTitle
parentReadingElementId nullable
parentSourceObjectId nullable
parentLocationRef nullable
attachmentRole nullable
associationStatus
locationRef nullable
locationType nullable
locationNotCreatedReason nullable
captionText
bodyText
searchableText
captionSource
bboxJson
rawAttributesJson
parserName
parserVersion
userId
orgTag
isPublic
createdAt
```

Rules:

- `readingElementId` is an opaque id scoped to one `paperId + modelVersion`.
- `parserElementId` stores MinerU/content-list identity when available; otherwise store the mapped
  element id such as reading order.
- `sourceObjectId` stores typed ids such as `table-12`, `figure-18`, or `formula-7` when available.
- `parentReadingElementId` stores the retained parent element when this object is a panel, caption,
  body fragment, or other child object.
- `parentSourceObjectId` stores the parent `tableId` or `figureId` when known.
- `parentLocationRef` stores the parent TABLE / FIGURE location ref when the parent has one.
- `attachmentRole` explains the child role, such as `PANEL_LABEL`, `PANEL_BODY`, `TABLE_BODY`, or
  `CAPTION_FRAGMENT`.
- `associationStatus` is `SELF`, `ATTACHED`, `UNATTACHED`, or `AMBIGUOUS`.
- `locationRef` is nullable because not every retained element is a navigable location.
- `locationNotCreatedReason` is nullable for elements that do have a location.
- `rawAttributesJson` preserves parser fields needed for future mapper fixes.
- `searchableText` is deterministic normalized text from caption/body/text fields.
- A blank visual object with bbox but no text should still be retained, with empty searchable text
  and raw attributes, because it may explain a visual asset or crop later.

Recommended `elementType` values:

```text
TITLE
HEADING
PARAGRAPH
LIST
FOOTNOTE
ASIDE
CODE
TABLE
IMAGE
CHART
FORMULA
UNKNOWN
```

Recommended `captionSource` values:

```text
table_caption
image_caption
chart_caption_full
chart_caption_panel_only
fallback_text
no_caption
not_applicable
```

Recommended `attachmentRole` values:

```text
PANEL_LABEL
PANEL_BODY
TABLE_BODY
TABLE_CAPTION
CAPTION_FRAGMENT
RAW_VISUAL_FRAGMENT
NONE
```

Recommended `associationStatus` values:

```text
SELF
ATTACHED
UNATTACHED
AMBIGUOUS
```

Recommended `locationNotCreatedReason` values:

```text
NOT_NAVIGATION_BOUNDARY
MISSING_PAGE
MISSING_ID
BLANK_SEARCHABLE_TEXT
PANEL_ONLY_CAPTION
FORMULA_LOCATION_DEFERRED
PAGE_TEXT_RETAINED_ONLY
```

## Retention Rules

### Text Elements

Persist headings, titles, paragraphs, lists, footnotes, aside text, and code blocks.

Rules:

- PAGE and SECTION text remains built from readable text elements.
- Each text element is also retained as a Reading Model Element.
- `searchableText` is normalized original text.
- If a text element is blank, retain it only when it has useful raw attributes or bbox; otherwise it
  may be counted but not stored.

### Tables

Persist every parsed table as a Reading Model Element.

Rules:

- If a table also gets a TABLE location, store that `locationRef` on the retained element.
- If a table fragment belongs to a parent table, store `parentReadingElementId`,
  `parentSourceObjectId`, and `parentLocationRef` when available.
- A table with blank caption but nonblank body still gets searchable text from the body.
- A table with missing body/caption still gets retained with raw attributes and
  `locationNotCreatedReason=BLANK_SEARCHABLE_TEXT`.
- Do not split merged human-visible tables in this phase, but preserve enough text/raw attributes to
  support a later split.

### Figures And Charts

Persist every parsed image/chart object as a Reading Model Element.

Rules:

- A full `image_caption` or full guarded `chart_caption` may create a FIGURE location.
- Panel-only `chart_caption` values do not create top-level FIGURE locations.
- Panel-only values are attached to the corresponding parent figure when a deterministic parent is
  found.
- Panel-only values are still persisted in `captionText` and `searchableText`.
- `captionSource=chart_caption_panel_only` identifies the case.
- `locationNotCreatedReason=PANEL_ONLY_CAPTION` explains why there is no FIGURE location.
- `parentReadingElementId`, `parentSourceObjectId`, and `parentLocationRef` point at the parent
  figure when available.
- Empty chart/image objects are still retained when they have bbox or raw attributes, but may have
  empty `searchableText`.

### Parent Association Rules

For chart/image children, identify a parent figure with deterministic local evidence:

1. Prefer a full-caption figure/chart object on the same page whose `chart_caption` array contains
   both the full figure caption and the panel label.
2. Otherwise use the nearest same-page full-caption figure/chart object within a bounded reading
   order window.
3. If multiple candidates tie or the nearest candidate is outside the window, keep the child
   retained but mark `associationStatus=AMBIGUOUS` or `UNATTACHED`.

Recommended first window:

```text
parent.readingOrder - 5 <= child.readingOrder <= parent.readingOrder + 5
```

Rules:

- Never drop a child because association is ambiguous.
- Never create a fake FIGURE location just to attach a child.
- Search for attached child text should return the parent figure location.
- Search for unattached child text should return the containing page location.
- The audit should count attached, ambiguous, and unattached retained children separately.

For `data/2412.08972.pdf`, examples that must be retained and searchable:

```text
(a) Recall
(b) Precision
(a) Airline
(b) NBA
(a) Correctness
```

### Formulas

Persist every parsed formula as a Reading Model Element.

Rules:

- Formula text remains embedded in PAGE / SECTION text when available.
- No FORMULA location is created in this phase.
- `locationNotCreatedReason=FORMULA_LOCATION_DEFERRED`.
- `searchableText` should include the normalized formula/latex text so users can search formulas
  later.

## Searchability Contract

This phase does not need to expose the final LLM-facing search tool, but it must create a durable
search input.

Rules:

- Every retained element with nonblank `searchableText` must be queryable by repository/service in
  tests.
- Later ReadingChunk / ReadingIndex construction must consume retained Reading Model Elements, not
  only top-level `PaperLocation` rows.
- If an element has no own `locationRef` but has `parentLocationRef`, search maps it to the parent
  TABLE / FIGURE location.
- If an element has neither own nor parent location ref, search maps it to the containing PAGE
  location for reading context.
- Search result previews may show retained element text, but final answer citations still require
  future Source Quote creation.

Minimum service/repository query:

```text
findByPaperIdAndModelVersionAndSearchableTextContaining(...)
```

This is not the final product ranking API. It is a proof that retained panel labels, formulas,
table bodies, and figure captions are not lost before indexing.

## Builder/Persistence Flow

Recommended flow:

```text
ParsedPaper
-> PaperReadingModelBuilder
-> pages
-> sections
-> locations
-> readingElements
-> PaperReadingModelService persists all in one Reading Model transaction
```

Update `PaperReadingModelBuildResult` to include:

```text
List<PaperReadingElement> readingElements
```

Update `PaperReadingModelService.replaceFromParsedPaper(...)` to persist retained elements with
pages, sections, and locations.

Diagnostics should change from "skipped object" language toward:

```text
tableLocationNotCreatedCount
figureLocationNotCreatedCount
formulaLocationDeferredCount
retainedElementCount
retainedElementsWithSearchableTextCount
```

Do not expose old "skipped object" diagnostics for retained parser objects. The product concept is
"retained without top-level location," not "discarded."

## Real PDF Audit

Extend `PaperReadingModelRealPdfAuditTest` with retention checks.

For each audited PDF:

- retained element count is at least the count of mapped parser elements plus parsed tables,
  figures, and formulas, after accounting for intentional duplicate mapping rules
- every parsed figure/chart object has a retained Reading Model Element
- every panel-only chart caption is retained
- every retained element has parseable `rawAttributesJson`
- every retained element with text has nonblank `searchableText`
- every TABLE / FIGURE location has a matching retained element
- non-location retained elements map to a PAGE location when page text exists

Add audit fields:

```text
retainedElementCount
retainedElementsWithSearchableTextCount
retainedTableElementCount
retainedFigureElementCount
retainedFormulaElementCount
retainedPanelOnlyChartCount
attachedPanelOnlyChartCount
ambiguousPanelOnlyChartCount
unattachedPanelOnlyChartCount
tableLocationNotCreatedCount
figureLocationNotCreatedCount
formulaLocationDeferredCount
panelOnlyChartSearchableExamples
```

Recommended verification command:

```bash
mvn -q \
  -Dtest=PaperReadingModelRealPdfAuditTest \
  -Dpaperloom.reading-model.audit=true \
  -Dpaper.parsing.mineru.base-url=http://127.0.0.1:8010 \
  -Dpaperloom.reading-model.audit.pdfs=data/2412.08972.pdf \
  -Dpaperloom.reading-model.audit.output=target/paper-reading-model-element-retention-audit/summary.jsonl \
  test
```

## Tests To Add

Minimum tests:

- `PaperReadingModelElementBuilderTest`
- `PaperReadingModelElementServiceTest`
- extend `MinerUOutputMapperTest`
- extend `PaperReadingModelRealPdfAuditTest`

Required cases:

- every parsed text element creates a retained element
- every parsed table creates a retained element
- blank-caption nonblank-body table is retained and searchable
- table without TABLE location is still retained
- every parsed image/chart creates a retained element
- full `image_caption` creates retained element plus FIGURE location
- full guarded `chart_caption` creates retained element plus FIGURE location
- panel-only `chart_caption` creates retained searchable child element without its own FIGURE
  location
- panel-only child attaches to the nearest deterministic parent FIGURE when possible
- ambiguous panel-only child is retained and searchable without being falsely attached
- panel-only labels such as `(a) Recall` are queryable by retained-element repository/service
- formula creates retained searchable element without FORMULA location
- raw attributes JSON is persisted for retained table/figure/chart/formula elements
- no legacy `img_caption` or generic `caption` source is counted unless future real-output evidence
  supports it

## Done For This Phase

This phase is done when:

```text
MinerU/ParsedPaper content
-> retained Reading Model Elements exist for all important parser objects
-> top-level locations remain conservative
-> non-location chart panels/formulas/tables are persisted
-> chart panels/table fragments attach to parent figure/table when deterministic
-> retained searchable text includes panel-only labels
-> real PDF audit proves retention on data/2412.08972.pdf
```

Success criteria:

- focused unit tests pass
- compile passes
- real PDF audit proves `data/2412.08972.pdf` retains all 16 image/chart objects
- examples such as `(a) Recall` and `(b) Precision` are found through retained-element search
- attached panel matches route to their parent FIGURE location rather than a noisy standalone
  location
- the audit report distinguishes "no top-level location" from "not retained"

## Explicit Non-Goals

Do not implement:

- final Elasticsearch ranking
- `find_reading_locations`
- `read_locations`
- Source Quote creation
- Answer Guard
- automatic figure-panel hierarchy
- automatic caption reassignment
- table splitting for merged table objects
- version-aligning existing `PaperTable` / `PaperFigure` rows

## Next Phase After This

After retention is proven, choose the next phase from evidence:

```text
if retained panel labels need user navigation:
  design Figure Panel locations or sub-locations
else if retained element search is enough:
  build ReadingChunk / ReadingIndex from retained elements and locations
else:
  improve mapper normalization for the retained raw attributes
```

The key rule for all later phases: search and retrieval must consume retained Reading Model
Elements, not only top-level locations.
