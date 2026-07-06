# Reading Model Persistence Closure Spec Readiness Review

Date: 2026-07-05
Reviewed document:

```text
paperloom-reading-retrieval-pipeline-research/reading-model-persistence-closure.md
```

Resulting spec:

```text
docs/superpowers/specs/2026-07-05-paperloom-reading-model-persistence-closure-spec.md
```

## Verdict

The document is directionally ready to become a spec.

Recommended answer:

```text
Yes, use it as the implementation direction and write a focused implementation spec from it.
```

It should not be implemented directly from the research document as-is. It is a good design
direction, but the spec must pin down several implementation-facing choices so the refactor does not
fan out into multiple half-canonical models.

The strongest part of the document is the simplification:

```text
PaperReadingElement = canonical parser/content inventory
PaperLocation = navigation coordinate
PaperVisualAsset = visual evidence or visual evidence gap
```

That is the right direction. It is simpler than version-aligning `paper_tables`, `paper_figures`, and
`paper_formulas`, because those tables would otherwise become duplicates of `paper_reading_elements`.

## Not Clear Enough Yet

### 1. What exactly is one ReadingElement?

Current ambiguity:

The document says every MinerU `content_list` item should map to one `PaperReadingElement`, while
current code also has derived `ParsedPaperTable`, `ParsedPaperFigure`, and `ParsedPaperFormula`
objects.

Recommended answer:

```text
One MinerU content_list item -> exactly one canonical PaperReadingElement.
```

Typed parser records are intermediate mapper conveniences, not extra persisted identities. Caption
arrays inside an image/table item should remain fields on the same element unless MinerU emits them
as separate `content_list` items.

Spec requirement:

- Add `contentListIndex`.
- Keep parser/raw identity as provenance.
- Avoid creating duplicate reading elements for the same table/figure/formula through both generic
  element and typed lists.

### 2. What replaces parser tableId / figureId?

Current ambiguity:

The document still mentions `sourceObjectId`, but the new direction says TABLE and FIGURE locations
should target `readingElementId`.

Recommended answer:

```text
Parser ids are provenance. ReadingElementId is the product identity.
```

Spec requirement:

- Rename or redefine `PaperLocation.sourceObjectId` as `targetId`.
- For SECTION, `targetId = sectionId`.
- For TABLE / FIGURE, `targetId = readingElementId`.
- Parser-specific ids, if retained, belong on `PaperReadingElement` as provenance fields or inside
  `rawAttributesJson`.

### 3. What is the authority for physical page count?

Current ambiguity:

MinerU-derived page count may be inferred from elements. That can miss textless pages.

Recommended answer:

```text
Use PDF renderer/document page count as the authority for Physical Page rows.
```

Spec requirement:

- `ParseService` or a build input must pass accurate PDF page count to the Reading Model builder.
- Parser metadata page count remains diagnostic.
- `paper_pages` and PAGE locations should be created for every physical page.

### 4. Where do table markdown, formula latex, and structured payloads go?

Current ambiguity:

The document says use `bodyText`, but also suggests optional `structuredPayloadJson`.

Recommended answer:

```text
Use bodyText for readable text and structuredPayloadJson for parser-specific structured payload.
```

Spec requirement:

- TABLE: `captionText`, readable table text in `bodyText`, markdown/html/shape metadata in
  `structuredPayloadJson`.
- FIGURE/CHART: caption in `captionText`, any parser text in `bodyText`.
- FORMULA: readable latex/context in `bodyText`, raw formula variants in `structuredPayloadJson` if
  needed.

This keeps one canonical table while avoiding overloaded free-text fields.

### 5. Is section context stored or resolved?

Current ambiguity:

The document says do not store `sectionLocationRef`, but still wants section awareness.

Recommended answer:

```text
Resolve section context from PaperSection page/order spans for now.
```

Spec requirement:

- Add a resolver contract:
  `paperId + modelVersion + pageNumber + readingOrder -> optional PaperSection`.
- Audit section resolution coverage.
- Do not block persistence closure on imperfect section assignment.

### 6. How aggressive is typed-store removal?

Current ambiguity:

The document says delete or demote `paper_tables`, `paper_figures`, and `paper_formulas`, but current
code still has controllers, services, visual crop code, retrieval enrichment, and tests depending on
them.

Recommended answer:

```text
Spec the removal as part of this refactor. Do not preserve compatibility-only stores.
```

Spec requirement:

- Replace `PaperTableService`, `PaperFigureService`, and `PaperFormulaService` with
  ReadingElement-backed query methods or remove them.
- Update `PaperController` typed endpoints to read from `paper_reading_elements`.
- Update `PaperVisualAssetService` crop generation to consume ReadingElements, not `PaperTable` /
  `PaperFigure` rows.
- Update `HybridSearchService` evidence enrichment to use ReadingElements or defer it until the
  ReadingChunk phase.

### 7. What should happen to legacy chunk fields?

Current ambiguity:

`paper_text_chunks`, `SearchResult`, and `PaperChunkDocument` still carry `tableId`, `figureId`, and
`formulaId`.

Recommended answer:

```text
Do not solve ReadingChunk in this spec, but stop making new persistence depend on typed ids.
```

Spec requirement:

- Keep legacy chunk fields only as temporary legacy retrieval metadata if needed for compile.
- Do not treat them as canonical IDs.
- The later ReadingChunk spec should replace these with `locationRef` and/or `readingElementId`.

## Is It Time To Write A Spec?

Yes, with one constraint:

```text
Write a persistence-closure spec, not a retrieval spec.
```

Recommended spec title:

```text
PaperLoom Reading Model Persistence Closure Spec
```

Recommended location:

```text
docs/superpowers/specs/2026-07-05-paperloom-reading-model-persistence-closure-spec.md
```

The spec should be implementation-facing and should include concrete tasks. It should not re-open
the broader RAG pipeline yet.

## Recommended Spec Shape

### Task 1: Physical Page Model

- Create `PaperPage` rows for all physical pages.
- Create PAGE `PaperLocation` rows for all physical pages.
- Add `textStatus`.
- READY still requires at least one readable page.

### Task 2: Canonical ReadingElement Inventory

- Add `contentListIndex`.
- Add `sourceSpanJson`.
- Add `structuredPayloadJson`.
- Ensure one canonical element per MinerU `content_list` item.
- Fold table/figure/formula canonical content into ReadingElement.

### Task 3: Location Target Simplification

- Rename or redefine `sourceObjectId` as `targetId`.
- SECTION target is `sectionId`.
- TABLE / FIGURE target is `readingElementId`.
- FORMULA location remains deferred.

### Task 4: Visual Asset Status

- Add `assetStatus`.
- Make `objectKey` nullable for missing/failed assets.
- Persist missing parser image rows.
- Link parser image assets and rendered crops through `readingElementId`.

### Task 5: Remove Or Demote Typed Stores

- Stop persisting canonical `paper_tables`, `paper_figures`, and `paper_formulas`.
- Replace product typed queries with ReadingElement-backed queries.
- Remove compatibility code unless it is needed only to keep a temporary compile path.

### Task 6: Persistence Inventory Audit

- Audit raw `content_list` count vs reading element count.
- Audit physical page count vs page rows and PAGE locations.
- Audit `img_path` count vs visual asset statuses.
- Audit route resolution: own, parent, page, unresolved.

## Spec Readiness Checklist

Ready:

- The domain direction is clear.
- The simplification is valuable and removes duplicate canonical stores.
- The non-goals are clear enough.
- The ADR records the core architectural decision.

Needs to be pinned down in the spec:

- exact `ReadingElement` identity rule
- physical page count authority
- `targetId` / `sourceObjectId` cleanup
- structured payload field shape
- typed-store removal impact on services/controllers/tests
- legacy chunk fields as temporary non-canonical metadata

Final recommendation:

```text
Proceed to spec.
Use the research document as the direction.
Do not start implementation until the spec resolves the seven unclear points above.
```
