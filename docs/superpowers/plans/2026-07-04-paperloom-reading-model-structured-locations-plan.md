# PaperLoom Reading Model Structured Locations Plan

Spec: `docs/superpowers/specs/2026-07-04-paperloom-reading-model-structured-locations-spec.md`
Date: 2026-07-04

## Goal

Add one closed-loop Reading Model enhancement: persist structured SECTION, TABLE, and FIGURE
locations alongside required PAGE locations, without building Elasticsearch indexing or LLM tools.

The loop to prove:

```text
ParsedPaper
-> PaperReadingModelBuilder
-> PaperPage + PaperSection + typed PaperLocation
-> PaperReadingModelService persistence
-> focused tests + real PDF audit counts
```

## Guardrails

- Do not implement `ReadingChunk`, `ReadingIndex`, `find_reading_locations`, `read_locations`,
  Source Quotes, or Answer Guard.
- Do not make SECTION/TABLE/FIGURE required for `READING_MODEL_READY`.
- Do not read MinerU raw artifacts after `ParsedPaper`; the builder consumes only `ParsedPaper`.
- Do not expose raw table ids, figure ids, section ids, model versions, SQL ids, or parser ids to
  future LLM-facing APIs. They may exist inside DB/source-span metadata only.
- Keep all new refs opaque: `section_ref_...`, `table_ref_...`, `figure_ref_...`.

## Task 1: Add Structured Location Schema

Files:

- Create: `src/main/java/com/yizhaoqi/smartpai/model/PaperSection.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/repository/PaperSectionRepository.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/model/PaperLocation.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/repository/PaperLocationRepository.java`

Steps:

1. Add `PaperSection` with fields from the spec:
   `paperId`, `modelVersion`, `sectionId`, `sectionTitle`, `sectionLevel`, `pageNumberFrom`,
   `pageNumberTo`, `readingOrderFrom`, `readingOrderTo`, `sectionText`, `textHash`, `charCount`,
   `sourceSpanJson`, parser fields, permission fields, and `createdAt`.
2. Add indexes for:
   - `paper_id,model_version,page_number_from`
   - `paper_id,model_version,section_id`
3. Add nullable `sourceObjectId`, `pageEndNumber`, and `displayOrder` to `PaperLocation`.
4. Add repository methods:
   - `PaperSectionRepository.findByPaperIdAndModelVersionOrderByPageNumberFromAscDisplayOrderAsc`
   - `PaperSectionRepository.countByPaperIdAndModelVersion`
   - `PaperLocationRepository.findByPaperIdAndModelVersionAndLocationTypeOrderByPageNumberAscIdAsc`
5. Keep `PaperLocation.pageNumber` required. For multi-page sections, store the first page in
   `pageNumber` and the end page in `pageEndNumber`.

Verification:

```bash
mvn -q -Dtest=PaperReadingModelRepositoryTest test
```

Expected:

- Repository tests compile and pass.

## Task 2: Extend Build Result And Service Persistence

Files:

- Modify: `src/main/java/com/yizhaoqi/smartpai/service/PaperReadingModelBuildResult.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/PaperReadingModelService.java`
- Modify: existing service tests as needed.

Steps:

1. Add `List<PaperSection> sections` to `PaperReadingModelBuildResult`.
2. Inject `PaperSectionRepository` into `PaperReadingModelService`.
3. Persist sections between pages and locations:

   ```text
   pageRepository.saveAll(result.pages())
   sectionRepository.saveAll(result.sections())
   locationRepository.saveAll(result.locations())
   ```

4. Keep the current-model flip unchanged.
5. Ensure failed builds still do not save pages, sections, or locations.

Verification:

```bash
mvn -q -Dtest=PaperReadingModelServiceTest,PaperReadingModelServicePersistenceTest test
```

Expected:

- Successful model persists sections and locations under one `modelVersion`.
- Failed model does not replace current model and does not persist sections.

## Task 3: Build SECTION Locations

Files:

- Modify: `src/main/java/com/yizhaoqi/smartpai/service/PaperReadingModelBuilder.java`
- Create or extend: `src/test/java/com/yizhaoqi/smartpai/service/PaperReadingModelStructuredLocationBuilderTest.java`

Steps:

1. Keep the current readable-element collection logic.
2. Add section grouping after readable elements are sorted:
   - start a section on nonblank HEADING text
   - use nonblank `sectionTitle` as a section signal when no heading has started
   - do not create synthetic whole-paper sections
3. Create one `PaperSection` and one SECTION `PaperLocation` per section.
4. Set:
   - `section_ref_...`
   - `sourceObjectId=sectionId`
   - `contentKind=SECTION_TEXT`
   - `pageNumber=pageNumberFrom`
   - `pageEndNumber=pageNumberTo`
   - deterministic `displayOrder`
5. Extend SourceSpan JSON with location type, source object id, page range, element ids,
   reading-order range, source kinds, and bbox list when present.
6. Add diagnostics: `sectionCount`, `sectionLocationCount`, `sectionsSkippedBlankText`.

Verification:

```bash
mvn -q -Dtest=PaperReadingModelStructuredLocationBuilderTest,PaperReadingModelBuilderTest test
```

Expected:

- Multi-section fake paper produces section rows and SECTION locations.
- Multi-page section has correct page range.
- Duplicate titles create distinct refs.
- Existing PAGE builder behavior still passes.

## Task 4: Build TABLE And FIGURE Locations

Files:

- Modify: `src/main/java/com/yizhaoqi/smartpai/service/PaperReadingModelBuilder.java`
- Extend: `src/test/java/com/yizhaoqi/smartpai/service/PaperReadingModelStructuredLocationBuilderTest.java`

Steps:

1. For each `ParsedPaperTable`, create a TABLE location only when:
   - `tableId` is nonblank
   - `pageNumber` is positive
   - at least one of `tableText`, `tableMarkdown`, or `caption` is nonblank
2. For each `ParsedPaperFigure`, create a FIGURE location only when:
   - `figureId` is nonblank
   - `pageNumber` is positive
   - at least one of `caption` or `figureText` is nonblank
3. Set:
   - `table_ref_...` / `figure_ref_...`
   - `sourceObjectId=tableId` / `figureId`
   - `contentKind=TABLE` / `FIGURE`
   - page start/end to the object page
   - deterministic `displayOrder`
4. Include table/figure id, element id, bbox, parser metadata, and source kind in SourceSpan JSON.
5. Add diagnostics:
   - `tableCount`, `tableLocationCount`, `tablesSkippedNoPage`, `tablesSkippedNoId`,
     `tablesSkippedBlankText`
   - `figureCount`, `figureLocationCount`, `figuresSkippedNoPage`, `figuresSkippedNoId`,
     `figuresSkippedBlankText`
   - `structuredLocationCount`

Verification:

```bash
mvn -q -Dtest=PaperReadingModelStructuredLocationBuilderTest test
```

Expected:

- Usable table/figure parser objects create typed locations.
- Invalid table/figure parser objects are skipped and counted.
- PAGE readiness does not fail when no tables or figures exist.

## Task 5: Extend Real PDF Audit

Files:

- Modify: `src/test/java/com/yizhaoqi/smartpai/service/PaperReadingModelRealPdfAuditTest.java`
- Modify: `docs/qa/paper-reading-model-real-pdf-audit-2026-07-04.md` after running the audit.

Steps:

1. Add `PaperSectionRepository` to the audit.
2. Record per PDF:
   - `sectionCount`
   - `sectionLocationCount`
   - `tableLocationCount`
   - `figureLocationCount`
   - `structuredLocationCount`
   - skip counts from diagnostics
3. Validate:
   - PAGE location count equals readable page count
   - SECTION location count equals persisted section count
   - TABLE location count does not exceed parsed table count
   - FIGURE location count does not exceed parsed figure count
   - all typed locations have `sourceSpanJson` and `sourceObjectId` except PAGE
4. Keep the audit disabled by default.

Verification:

```bash
mvn -q \
  -Dtest=PaperReadingModelRealPdfAuditTest \
  -Dpaperloom.reading-model.audit=true \
  -Dpaper.parsing.mineru.base-url=http://127.0.0.1:8010 \
  test
```

Expected:

- Audit passes when MinerU is available.
- Report shows structured-location counts and any skipped table/figure reasons.

## Task 6: Final Focused Verification

Run:

```bash
mvn -q \
  -Dtest=PaperReadingModelRepositoryTest,PaperReadingModelBuilderTest,PaperReadingModelStructuredLocationBuilderTest,PaperReadingModelServiceTest,PaperReadingModelServicePersistenceTest,ParseServiceStructuredParserTest \
  test
```

Run:

```bash
mvn -q -DskipTests compile
```

Optional real PDF smoke:

```bash
mvn -q \
  -Dtest=PaperReadingModelDataPdfSmokeTest \
  -Dpaperloom.reading-model.real-pdf=true \
  -Dpaper.parsing.mineru.base-url=http://127.0.0.1:8010 \
  test
```

## Completion Criteria

This phase is complete when:

- current model builds still create PAGE locations
- structured SECTION/TABLE/FIGURE locations are persisted when parser data supports them
- sections are readable from `paper_sections`
- typed location counts are visible in diagnostics
- no structured-location absence blocks `READING_MODEL_READY`
- focused tests pass
- real PDF audit has been rerun or the reason for not rerunning it is recorded

## Next Phase After This

Build `ReadingChunk` from typed locations:

```text
PAGE / SECTION / TABLE / FIGURE PaperLocation
-> ReadingChunk(originalText, searchText)
-> paperloom_reading_chunks
-> current modelVersion filtering
```

Do not start that phase until this one has passed its focused tests and at least one real-PDF smoke.
