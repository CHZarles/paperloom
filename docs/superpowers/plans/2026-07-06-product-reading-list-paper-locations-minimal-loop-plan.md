# Product Reading List Paper Locations Minimal Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `list_paper_locations` as the fifth Product Reading tool so a traced Source Quote can be expanded into current READY Reading Model context without letting stale `locationRef` metadata bypass `read_locations` authorization.

**Architecture:** Keep the Product Reading path isolated from the legacy Product ReAct path. Register `list_paper_locations` in `ProductReadingToolRegistry`, implement deterministic current-model location enumeration in `ProductReadingToolAdapter`, and let `ProductReadingReActHarness` disclose only the current `locationRef` values returned by this tool. `trace_source_quotes` may disclose a `paperHandle` for deterministic current-model lookup, but its stored `locationRef` remains metadata and must not authorize `read_locations`.

**Tech Stack:** Java 17, Spring Boot, Spring Data JPA, Jackson, JUnit 5, Mockito, Maven.

## Global Constraints

- Keep the legacy Product path unchanged: do not modify `ProductReActHarness`, `ProductToolRegistry`, or `ProductConversationService`.
- Keep the Product Reading entry point behind `paperloom.react.reading-phase1.enabled=true`; do not promote the flag.
- Do not change database schema.
- Do not add frontend UI in this slice.
- Do not add an `expand_source_quote_context` tool.
- Do not implement the full nine-tool historical catalog in this slice.
- The next Product Reading tool surface is exactly five tools: `search_paper_candidates`, `list_paper_locations`, `find_reading_locations`, `read_locations`, and `trace_source_quotes`.
- The historical target catalog remains nine tools: `get_session_state`, `list_papers`, `search_paper_candidates`, `find_papers_by_identity`, `get_paper_outline`, `list_paper_locations`, `find_reading_locations`, `read_locations`, and `trace_source_quotes`.
- `list_paper_locations` is deterministic navigation, not semantic search.
- `list_paper_locations` reads only current READY Reading Model `PaperLocation` rows and does not read paper content.
- `list_paper_locations` rejects raw `paperId`, ordinals, semantic query fields, and retrieval tuning controls.
- `trace_source_quotes` output `locationRef` values are not accepted by `read_locations` unless a current-model reading-location tool separately returns those refs in the same turn.

---

## Grill Outcomes

### 1. Are four Product Reading tools enough?

Recommended answer: no.

Adopted. The current four tools are only the minimal Source Quote MVP:

```text
search_paper_candidates
find_reading_locations
read_locations
trace_source_quotes
```

They are enough for candidate search, semantic location search, reading selected locations, and resolving clicked Source Quote anchors. They are not the completed historical Product Reading tool design.

### 2. Should the next step add all missing historical tools?

Recommended answer: no.

Adopted. The next smallest closed loop is one new tool, `list_paper_locations`, not the remaining five tools. Adding all historical tools at once would mix paper browsing, identity lookup, outline inspection, and deterministic location navigation into one large review surface.

### 3. Why is `list_paper_locations` the next tool?

Recommended answer: because it closes the safe context-expansion loop after a clicked Source Quote.

Adopted. The loop becomes:

```text
trace_source_quotes(sourceQuoteRefs)
-> returns stored quote plus paperHandle/pageNumber/section metadata
-> list_paper_locations(paperHandles, pageRange, locationTypes)
-> returns current READY model locationRefs
-> read_locations(locationRefs)
-> returns new citeable Source Quotes
```

### 4. Should `trace_source_quotes` old `locationRef` metadata authorize `read_locations`?

Recommended answer: no.

Adopted. Stored quote metadata may belong to an older Reading Model version. Broader context must be re-resolved through `list_paper_locations` against the current READY Reading Model.

### 5. Should trace-returned `paperHandle` authorize semantic search?

Recommended answer: no.

Adopted. A trace result can disclose `paperHandle` for deterministic `list_paper_locations` lookup only. It must not unlock `find_reading_locations`; semantic search still requires a paper handle disclosed by `search_paper_candidates` or a future clicked paper row path.

### 6. Should the new tool read paper content?

Recommended answer: no.

Adopted. It returns navigation refs only. Paper-content claims still require `read_locations` or `trace_source_quotes` Source Quotes.

### 7. Should invalid page ranges be product statuses or argument failures?

Recommended answer: syntactically invalid page ranges are `INVALID_ARGUMENT`; current-model misses are `CURRENT_LOCATION_NOT_FOUND`.

Adopted. Non-positive page numbers and `from > to` are validator failures. A positive page range outside the current READY model page count is an adapter `INVALID_ARGUMENT` because it is provably outside the selected paper. A valid range with no current matching locations returns `CURRENT_LOCATION_NOT_FOUND`.

### 8. Should missing TABLE or FIGURE extraction be an error?

Recommended answer: no.

Adopted. If an optional requested `locationTypes` value has no current locations, return no locations for that type. If the whole result is empty, return `CURRENT_LOCATION_NOT_FOUND`.

### 9. Should we add `sectionRef` now?

Recommended answer: only if it already exists in current data.

Adopted. The current `PaperLocation` model has `sectionTitle` and `sourceObjectId`, but no explicit `sectionRef` column. Do not infer or invent `sectionRef` in this slice.

### 10. Should this change require frontend work?

Recommended answer: no.

Adopted. This slice is a backend ReAct tool-surface and harness change only.

## File Structure

- Modify `src/main/java/com/yizhaoqi/smartpai/service/ReadingToolArgumentValidator.java`: add `list_paper_locations` argument validation, page-range parsing, and forbidden-field checks.
- Modify `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingToolRegistry.java`: register the fifth tool, expose the closed schema, and delegate validated calls.
- Modify `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingToolAdapter.java`: resolve handles, verify visibility/scope/READY state, enumerate current-model `PaperLocation` rows, filter by page range and location type, and return navigation-only output.
- Modify `src/main/java/com/yizhaoqi/smartpai/service/ReadingToolOutputMapper.java`: add a mapper for deterministic listed `PaperLocation` cards.
- Modify `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarness.java`: accept the five-tool surface, update prompt policy, validate `list_paper_locations` calls, and disclose returned `locationRef` values for `read_locations`.
- Modify `src/test/java/com/yizhaoqi/smartpai/service/ReadingToolArgumentValidatorTest.java`: cover the new argument contract.
- Modify `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingToolRegistryTest.java`: cover exposure, closed schema, validation, and delegation.
- Modify `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingToolAdapterTest.java`: cover current READY model listing and status behavior.
- Modify `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarnessTest.java`: cover harness authorization, prompt text, tool order, and trace-to-list-to-read behavior.

## Tool Contract

Input:

```json
{
  "paperHandles": ["paper_handle_abc"],
  "pageRange": {
    "from": 3,
    "to": 3
  },
  "locationTypes": ["PAGE", "SECTION", "TABLE", "FIGURE"]
}
```

Required:

```text
paperHandles
```

Optional:

```text
pageRange
locationTypes
```

Output:

```json
{
  "paperHandles": ["paper_handle_abc"],
  "status": "OK",
  "supportedLocationTypesByPaper": {
    "paper_handle_abc": ["PAGE", "SECTION"]
  },
  "locations": [
    {
      "ordinal": 1,
      "locationRef": "page_ref_abc",
      "locationType": "PAGE",
      "paperHandle": "paper_handle_abc",
      "pageNumber": 3,
      "pageEndNumber": 3,
      "sectionTitle": "Methods",
      "label": "Page 3"
    }
  ],
  "constraints": {
    "previewIsSourceQuote": false,
    "locationRefIsSourceQuote": false,
    "paperContentClaimsAllowed": false
  }
}
```

Statuses:

```text
OK
CURRENT_LOCATION_NOT_FOUND
```

Argument failures use the existing registry-level `INVALID_ARGUMENT` shape.

### Forbidden Inputs

Reject these keys at any nesting depth:

```text
paperId
paperIds
paperRef
paperRefs
locationId
locationIds
chunkId
chunkIds
chunkRef
readingElementId
modelVersion
indexVersion
indexName
query
queryText
question
readingNeed
semanticNeed
topicText
ordinal
ordinals
candidateOrdinal
resultOrdinal
limit
topK
maxCandidates
pageSize
pageWindow
budget
rerank
rerankEnabled
searchMode
```

## Tasks

### Task 1: Argument Contract

**Files:**

- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ReadingToolArgumentValidator.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/service/ReadingToolArgumentValidatorTest.java`

**Interfaces:**

- Produces: `ReadingToolArgumentValidator.validateListPaperLocations(Map<String, Object>)`
- Produces: `ReadingToolArgumentValidator.pageRange(Object)`
- Produces: `ReadingToolArgumentValidator.PageRange(Integer from, Integer to)`
- Consumes: existing `stringList(Object)` and `locationTypes(Object)` helpers

- [ ] **Step 1: Write the failing validator test**

Add this test method to `ReadingToolArgumentValidatorTest`:

```java
@Test
void listPaperLocationsRequiresHandlesAndRejectsSemanticOrdinalAndRangeControls() {
    ReadingToolArgumentValidator.ValidationResult missingHandles =
            validator.validateListPaperLocations(Map.of());
    ReadingToolArgumentValidator.ValidationResult queryText =
            validator.validateListPaperLocations(Map.of(
                    "paperHandles", List.of("paper_handle_abc"),
                    "queryText", "methods"
            ));
    ReadingToolArgumentValidator.ValidationResult ordinal =
            validator.validateListPaperLocations(Map.of(
                    "paperHandles", List.of("paper_handle_abc"),
                    "ordinal", 1
            ));
    ReadingToolArgumentValidator.ValidationResult unsupportedTopLevel =
            validator.validateListPaperLocations(Map.of(
                    "paperHandles", List.of("paper_handle_abc"),
                    "metadataFilters", Map.of()
            ));
    ReadingToolArgumentValidator.ValidationResult badRange =
            validator.validateListPaperLocations(Map.of(
                    "paperHandles", List.of("paper_handle_abc"),
                    "pageRange", Map.of("from", 4, "to", 3)
            ));
    ReadingToolArgumentValidator.ValidationResult badType =
            validator.validateListPaperLocations(Map.of(
                    "paperHandles", List.of("paper_handle_abc"),
                    "locationTypes", List.of("PAGE", "APPENDIX")
            ));
    ReadingToolArgumentValidator.ValidationResult valid =
            validator.validateListPaperLocations(Map.of(
                    "paperHandles", List.of("paper_handle_abc"),
                    "pageRange", Map.of("from", 3, "to", 3),
                    "locationTypes", List.of("PAGE", "SECTION")
            ));

    assertFalse(missingHandles.valid());
    assertEquals("missing_argument", missingHandles.error());
    assertEquals("paperHandles", missingHandles.argument());
    assertFalse(queryText.valid());
    assertEquals("forbidden_argument", queryText.error());
    assertEquals("queryText", queryText.argument());
    assertFalse(ordinal.valid());
    assertEquals("forbidden_argument", ordinal.error());
    assertEquals("ordinal", ordinal.argument());
    assertFalse(unsupportedTopLevel.valid());
    assertEquals("unsupported_argument", unsupportedTopLevel.error());
    assertEquals("metadataFilters", unsupportedTopLevel.argument());
    assertFalse(badRange.valid());
    assertEquals("invalid_page_range", badRange.error());
    assertEquals("pageRange", badRange.argument());
    assertFalse(badType.valid());
    assertEquals("unsupported_location_type", badType.error());
    assertEquals("APPENDIX", badType.argument());
    assertTrue(valid.valid());
    assertEquals(new ReadingToolArgumentValidator.PageRange(3, 3),
            validator.pageRange(Map.of("from", 3, "to", 3)));
}
```

- [ ] **Step 2: Run the focused validator test and verify it fails**

Run:

```bash
mvn -q -Dtest=ReadingToolArgumentValidatorTest test
```

Expected: compilation fails because `validateListPaperLocations` and `PageRange` do not exist.

- [ ] **Step 3: Add minimal validator implementation**

In `ReadingToolArgumentValidator`, add:

```java
private static final Set<String> LIST_LOCATIONS_FORBIDDEN_ARGUMENTS = Set.of(
        "paperId",
        "paperIds",
        "paperRef",
        "paperRefs",
        "locationId",
        "locationIds",
        "chunkId",
        "chunkIds",
        "chunkRef",
        "readingElementId",
        "modelVersion",
        "indexVersion",
        "indexName",
        "query",
        "queryText",
        "question",
        "readingNeed",
        "semanticNeed",
        "topicText",
        "ordinal",
        "ordinals",
        "candidateOrdinal",
        "resultOrdinal",
        "limit",
        "topK",
        "maxCandidates",
        "pageSize",
        "pageWindow",
        "budget",
        "rerank",
        "rerankEnabled",
        "searchMode"
);
private static final Set<String> LIST_LOCATIONS_ALLOWED_ARGUMENTS =
        Set.of("paperHandles", "pageRange", "locationTypes");
```

Add the public validator:

```java
public ValidationResult validateListPaperLocations(Map<String, Object> arguments) {
    Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
    String forbiddenArgument = firstForbiddenArgument(safeArguments, LIST_LOCATIONS_FORBIDDEN_ARGUMENTS);
    if (forbiddenArgument != null) {
        return ValidationResult.invalid("forbidden_argument", forbiddenArgument);
    }
    String unsupportedArgument = firstUnsupportedTopLevelArgument(safeArguments, LIST_LOCATIONS_ALLOWED_ARGUMENTS);
    if (unsupportedArgument != null) {
        return ValidationResult.invalid("unsupported_argument", unsupportedArgument);
    }
    if (stringList(safeArguments.get("paperHandles")).isEmpty()) {
        return ValidationResult.invalid("missing_argument", "paperHandles");
    }
    if (safeArguments.containsKey("pageRange")) {
        ValidationResult pageRangeResult = validatePageRange(safeArguments.get("pageRange"));
        if (!pageRangeResult.valid()) {
            return pageRangeResult;
        }
    }
    if (safeArguments.containsKey("locationTypes")) {
        ValidationResult locationTypesResult = validateLocationTypes(safeArguments.get("locationTypes"));
        if (!locationTypesResult.valid()) {
            return locationTypesResult;
        }
    }
    return ValidationResult.validResult();
}
```

Add page-range parsing:

```java
public PageRange pageRange(Object value) {
    if (!(value instanceof Map<?, ?> rawRange)) {
        return null;
    }
    Integer from = integerValue(rawRange.get("from"));
    Integer to = integerValue(rawRange.get("to"));
    if (from == null || to == null) {
        return null;
    }
    return new PageRange(from, to);
}

private ValidationResult validatePageRange(Object value) {
    PageRange range = pageRange(value);
    if (range == null || range.from() < 1 || range.to() < 1 || range.from() > range.to()) {
        return ValidationResult.invalid("invalid_page_range", "pageRange");
    }
    return ValidationResult.validResult();
}

private Integer integerValue(Object value) {
    if (value instanceof Integer integer) {
        return integer;
    }
    if (value instanceof Long longValue
            && longValue >= Integer.MIN_VALUE
            && longValue <= Integer.MAX_VALUE) {
        return longValue.intValue();
    }
    if (value instanceof Number number) {
        double doubleValue = number.doubleValue();
        if (Math.floor(doubleValue) == doubleValue
                && doubleValue >= Integer.MIN_VALUE
                && doubleValue <= Integer.MAX_VALUE) {
            return (int) doubleValue;
        }
    }
    return null;
}
```

Add the record near `ValidationResult`:

```java
public record PageRange(Integer from, Integer to) {
}
```

- [ ] **Step 4: Run the validator tests and verify they pass**

Run:

```bash
mvn -q -Dtest=ReadingToolArgumentValidatorTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```bash
git add src/main/java/com/yizhaoqi/smartpai/service/ReadingToolArgumentValidator.java \
  src/test/java/com/yizhaoqi/smartpai/service/ReadingToolArgumentValidatorTest.java
git commit -m "test: cover list paper locations arguments"
```

### Task 2: Adapter Deterministic Listing

**Files:**

- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingToolAdapter.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ReadingToolOutputMapper.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingToolAdapterTest.java`

**Interfaces:**

- Consumes: `ReadingToolArgumentValidator.PageRange`
- Produces: `ProductReadingToolAdapter.listPaperLocations(List<String>, ReadingToolArgumentValidator.PageRange, List<PaperLocationType>, ProductToolContext)`
- Produces: `ReadingToolOutputMapper.listedLocationCard(PaperLocation, String, int)`

- [ ] **Step 1: Write the failing adapter test for current-model listing**

Add this test method to `ProductReadingToolAdapterTest`:

```java
@Test
void listPaperLocationsReturnsCurrentReadyModelRefsForPageRangeAndTypes() throws Exception {
    PaperReadingModel readyModel = model("ready-paper", "model-v1");
    readyModel.setPageCount(12);
    PaperLocation page = location("ready-paper", "page_ref_3", PaperLocationType.PAGE);
    page.setPageNumber(3);
    page.setPageEndNumber(3);
    page.setSectionTitle("Methods");
    PaperLocation section = location("ready-paper", "section_ref_methods", PaperLocationType.SECTION);
    section.setPageNumber(3);
    section.setPageEndNumber(5);
    section.setSectionTitle("Methods");
    PaperLocation laterPage = location("ready-paper", "page_ref_9", PaperLocationType.PAGE);
    laterPage.setPageNumber(9);

    when(handleService.resolvePaperHandle("paper_handle_ready")).thenReturn(Optional.of("ready-paper"));
    when(handleService.isPaperVisibleToUser("ready-paper", 7L, SourceScope.auto())).thenReturn(true);
    when(handleService.hasCurrentReadyReadingModel("ready-paper")).thenReturn(true);
    when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("ready-paper"))
            .thenReturn(Optional.of(readyModel));
    when(locationRepository.findByPaperIdAndModelVersionOrderByPageNumberAscIdAsc("ready-paper", "model-v1"))
            .thenReturn(List.of(page, section, laterPage));

    ProductToolResult result = adapter.listPaperLocations(
            List.of("paper_handle_ready"),
            new ReadingToolArgumentValidator.PageRange(3, 3),
            List.of(PaperLocationType.PAGE, PaperLocationType.SECTION),
            new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
    );

    assertTrue(result.success());
    assertEquals("list_paper_locations", result.toolName());
    assertEquals(ProductToolEffect.PAPER_DISCOVERY, result.effect());
    assertEquals("OK", result.data().get("status"));
    assertEquals(List.of("paper_handle_ready"), result.data().get("paperHandles"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> locations = (List<Map<String, Object>>) result.data().get("locations");
    assertEquals(2, locations.size());
    assertEquals("page_ref_3", locations.get(0).get("locationRef"));
    assertEquals("PAGE", locations.get(0).get("locationType"));
    assertEquals("Page 3", locations.get(0).get("label"));
    assertEquals("section_ref_methods", locations.get(1).get("locationRef"));
    assertEquals("SECTION", locations.get(1).get("locationType"));
    assertEquals("Methods", locations.get(1).get("label"));
    @SuppressWarnings("unchecked")
    Map<String, List<String>> supported =
            (Map<String, List<String>>) result.data().get("supportedLocationTypesByPaper");
    assertEquals(List.of("PAGE", "SECTION"), supported.get("paper_handle_ready"));

    String json = objectMapper.writeValueAsString(result.data());
    assertFalse(json.contains("ready-paper"));
    assertFalse(json.contains("paperId"));
    assertFalse(json.contains("model-v1"));
    assertFalse(json.contains("modelVersion"));
}
```

- [ ] **Step 2: Write the failing adapter test for unavailable current context**

Add:

```java
@Test
void listPaperLocationsReturnsCurrentLocationNotFoundForInvisibleUnreadyOrEmptyCurrentLocations() {
    when(handleService.resolvePaperHandle("paper_handle_missing")).thenReturn(Optional.empty());
    when(handleService.resolvePaperHandle("paper_handle_unready")).thenReturn(Optional.of("unready-paper"));
    when(handleService.isPaperVisibleToUser("unready-paper", 7L, SourceScope.auto())).thenReturn(true);
    when(handleService.hasCurrentReadyReadingModel("unready-paper")).thenReturn(false);
    when(handleService.resolvePaperHandle("paper_handle_ready")).thenReturn(Optional.of("ready-paper"));
    when(handleService.isPaperVisibleToUser("ready-paper", 7L, SourceScope.auto())).thenReturn(true);
    when(handleService.hasCurrentReadyReadingModel("ready-paper")).thenReturn(true);
    when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("ready-paper"))
            .thenReturn(Optional.of(model("ready-paper", "model-v1")));
    when(locationRepository.findByPaperIdAndModelVersionOrderByPageNumberAscIdAsc("ready-paper", "model-v1"))
            .thenReturn(List.of(location("ready-paper", "page_ref_1", PaperLocationType.PAGE)));

    ProductToolContext context =
            new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto());

    assertEquals("CURRENT_LOCATION_NOT_FOUND",
            adapter.listPaperLocations(List.of("paper_handle_missing"), null, List.of(), context)
                    .data().get("status"));
    assertEquals("CURRENT_LOCATION_NOT_FOUND",
            adapter.listPaperLocations(List.of("paper_handle_unready"), null, List.of(), context)
                    .data().get("status"));
    assertEquals("CURRENT_LOCATION_NOT_FOUND",
            adapter.listPaperLocations(
                            List.of("paper_handle_ready"),
                            new ReadingToolArgumentValidator.PageRange(4, 5),
                            List.of(PaperLocationType.PAGE),
                            context)
                    .data().get("status"));
}
```

- [ ] **Step 3: Write the failing adapter test for page count rejection**

Add:

```java
@Test
void listPaperLocationsRejectsPageRangeOutsideCurrentModelPageCount() {
    PaperReadingModel readyModel = model("ready-paper", "model-v1");
    readyModel.setPageCount(3);
    when(handleService.resolvePaperHandle("paper_handle_ready")).thenReturn(Optional.of("ready-paper"));
    when(handleService.isPaperVisibleToUser("ready-paper", 7L, SourceScope.auto())).thenReturn(true);
    when(handleService.hasCurrentReadyReadingModel("ready-paper")).thenReturn(true);
    when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("ready-paper"))
            .thenReturn(Optional.of(readyModel));
    when(locationRepository.findByPaperIdAndModelVersionOrderByPageNumberAscIdAsc("ready-paper", "model-v1"))
            .thenReturn(List.of(location("ready-paper", "page_ref_1", PaperLocationType.PAGE)));

    ProductToolResult result = adapter.listPaperLocations(
            List.of("paper_handle_ready"),
            new ReadingToolArgumentValidator.PageRange(4, 4),
            List.of(PaperLocationType.PAGE),
            new ProductToolContext(7L, "conversation-1", "generation-1", SourceScope.auto())
    );

    assertFalse(result.success());
    assertEquals("INVALID_ARGUMENT", result.data().get("status"));
    assertEquals("pageRange", result.data().get("argument"));
}
```

- [ ] **Step 4: Run the focused adapter tests and verify they fail**

Run:

```bash
mvn -q -Dtest=ProductReadingToolAdapterTest test
```

Expected: compilation fails because `listPaperLocations` and `listedLocationCard` do not exist.

- [ ] **Step 5: Add the output mapper**

In `ReadingToolOutputMapper`, add:

```java
public Map<String, Object> listedLocationCard(com.yizhaoqi.smartpai.model.PaperLocation location,
                                              String paperHandle,
                                              int ordinal) {
    Map<String, Object> card = new LinkedHashMap<>();
    card.put("ordinal", ordinal);
    card.put("paperHandle", paperHandle);
    card.put("locationRef", location.getLocationRef());
    card.put("locationType", location.getLocationType() == null ? "" : location.getLocationType().name());
    card.put("pageNumber", location.getPageNumber());
    card.put("pageEndNumber", location.getPageEndNumber());
    card.put("sectionTitle", location.getSectionTitle());
    card.put("label", locationLabel(location));
    return card;
}

private String locationLabel(com.yizhaoqi.smartpai.model.PaperLocation location) {
    if (location.getLocationType() == com.yizhaoqi.smartpai.model.PaperLocationType.PAGE
            && location.getPageNumber() != null) {
        return "Page " + location.getPageNumber();
    }
    if (!SearchText.isBlank(location.getSectionTitle())) {
        return location.getSectionTitle();
    }
    if (location.getLocationType() != null && location.getPageNumber() != null) {
        return location.getLocationType().name() + " on page " + location.getPageNumber();
    }
    return location.getLocationType() == null ? "Location" : location.getLocationType().name();
}
```

- [ ] **Step 6: Add the adapter method**

In `ProductReadingToolAdapter`, add:

```java
private static final String LIST_LOCATIONS_TOOL_NAME = "list_paper_locations";
```

Add the public method:

```java
@Transactional(readOnly = true)
public ProductToolResult listPaperLocations(List<String> paperHandles,
                                            ReadingToolArgumentValidator.PageRange pageRange,
                                            List<PaperLocationType> locationTypes,
                                            ProductToolContext context) {
    ProductToolContext safeContext = safeContext(context);
    List<String> safePaperHandles = sanitizePaperHandles(paperHandles);
    List<PaperLocationType> safeLocationTypes = locationTypes == null ? List.of() : List.copyOf(locationTypes);
    if (safePaperHandles.isEmpty()) {
        return invalidArgument(LIST_LOCATIONS_TOOL_NAME, "paperHandles");
    }

    Map<String, String> paperIdByHandle = new LinkedHashMap<>();
    Map<String, PaperReadingModel> modelByHandle = new LinkedHashMap<>();
    for (String paperHandle : safePaperHandles) {
        Optional<String> paperId = handleService.resolvePaperHandle(paperHandle);
        if (paperId.isEmpty()
                || !handleService.isPaperVisibleToUser(paperId.get(), safeContext.userId(), safeContext.lockedScope())
                || !handleService.hasCurrentReadyReadingModel(paperId.get())) {
            return listLocationStatus(safePaperHandles, "CURRENT_LOCATION_NOT_FOUND", List.of(), Map.of());
        }
        Optional<PaperReadingModel> currentModel = modelRepository.findFirstByPaperIdAndIsCurrentTrue(paperId.get())
                .filter(model -> model.getModelStatus() == PaperReadingModelStatus.READING_MODEL_READY);
        if (currentModel.isEmpty()) {
            return listLocationStatus(safePaperHandles, "CURRENT_LOCATION_NOT_FOUND", List.of(), Map.of());
        }
        if (pageRangeOutsideModel(pageRange, currentModel.get())) {
            return invalidArgument(LIST_LOCATIONS_TOOL_NAME, "pageRange");
        }
        paperIdByHandle.put(paperHandle, paperId.get());
        modelByHandle.put(paperHandle, currentModel.get());
    }

    Map<String, List<String>> supportedLocationTypesByHandle = new LinkedHashMap<>();
    List<Map<String, Object>> locations = new ArrayList<>();
    int ordinal = 1;
    for (String paperHandle : safePaperHandles) {
        String paperId = paperIdByHandle.get(paperHandle);
        PaperReadingModel model = modelByHandle.get(paperHandle);
        List<PaperLocation> currentLocations =
                locationRepository.findByPaperIdAndModelVersionOrderByPageNumberAscIdAsc(
                        paperId,
                        model.getModelVersion()
                );
        supportedLocationTypesByHandle.put(paperHandle, supportedLocationTypes(currentLocations));
        for (PaperLocation location : currentLocations) {
            if (!matchesLocationType(location, safeLocationTypes) || !matchesPageRange(location, pageRange)) {
                continue;
            }
            if (SearchText.isBlank(location.getLocationRef())) {
                continue;
            }
            locations.add(outputMapper.listedLocationCard(location, paperHandle, ordinal++));
        }
    }

    return listLocationStatus(
            safePaperHandles,
            locations.isEmpty() ? "CURRENT_LOCATION_NOT_FOUND" : "OK",
            locations,
            supportedLocationTypesByHandle
    );
}
```

Add helpers:

```java
private boolean pageRangeOutsideModel(ReadingToolArgumentValidator.PageRange pageRange,
                                      PaperReadingModel model) {
    if (pageRange == null || model == null || model.getPageCount() == null || model.getPageCount() < 1) {
        return false;
    }
    return pageRange.from() > model.getPageCount() || pageRange.to() > model.getPageCount();
}

private boolean matchesLocationType(PaperLocation location, List<PaperLocationType> locationTypes) {
    if (locationTypes == null || locationTypes.isEmpty()) {
        return true;
    }
    return location != null && locationTypes.contains(location.getLocationType());
}

private boolean matchesPageRange(PaperLocation location, ReadingToolArgumentValidator.PageRange pageRange) {
    if (pageRange == null) {
        return true;
    }
    if (location == null || location.getPageNumber() == null) {
        return false;
    }
    int start = location.getPageNumber();
    int end = location.getPageEndNumber() == null ? start : location.getPageEndNumber();
    return start <= pageRange.to() && end >= pageRange.from();
}

private List<String> supportedLocationTypes(List<PaperLocation> locations) {
    LinkedHashSet<String> types = new LinkedHashSet<>();
    for (PaperLocation location : locations == null ? List.<PaperLocation>of() : locations) {
        if (location != null && location.getLocationType() != null) {
            types.add(location.getLocationType().name());
        }
    }
    return List.copyOf(types);
}

private ProductToolResult listLocationStatus(List<String> paperHandles,
                                             String status,
                                             List<Map<String, Object>> locations,
                                             Map<String, List<String>> supportedLocationTypesByHandle) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("paperHandles", paperHandles == null ? List.of() : paperHandles);
    data.put("status", status);
    data.put("supportedLocationTypesByPaper", supportedLocationTypesByHandle == null
            ? Map.of()
            : supportedLocationTypesByHandle);
    data.put("locations", locations == null ? List.of() : locations);
    data.put("constraints", locationConstraints(status));
    return new ProductToolResult(LIST_LOCATIONS_TOOL_NAME, true, data, ProductToolEffect.PAPER_DISCOVERY);
}
```

- [ ] **Step 7: Run the adapter tests and verify they pass**

Run:

```bash
mvn -q -Dtest=ProductReadingToolAdapterTest test
```

Expected: PASS.

- [ ] **Step 8: Commit**

Run:

```bash
git add src/main/java/com/yizhaoqi/smartpai/service/ProductReadingToolAdapter.java \
  src/main/java/com/yizhaoqi/smartpai/service/ReadingToolOutputMapper.java \
  src/test/java/com/yizhaoqi/smartpai/service/ProductReadingToolAdapterTest.java
git commit -m "feat: add deterministic paper location listing"
```

### Task 3: Registry Surface

**Files:**

- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingToolRegistry.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingToolRegistryTest.java`

**Interfaces:**

- Consumes: `ReadingToolArgumentValidator.validateListPaperLocations(Map<String, Object>)`
- Consumes: `ProductReadingToolAdapter.listPaperLocations(List<String>, ReadingToolArgumentValidator.PageRange, List<PaperLocationType>, ProductToolContext)`
- Produces: `list_paper_locations` closed JSON schema

- [ ] **Step 1: Update registry tests first**

In `ProductReadingToolRegistryTest.exposesOnlySourceQuoteReadingToolsWithClosedSchemas`, change the expected names to:

```java
assertEquals(List.of(
        "search_paper_candidates",
        "list_paper_locations",
        "find_reading_locations",
        "read_locations",
        "trace_source_quotes"
), names);
```

Add assertions:

```java
AgentToolRegistry.AgentTool listTool = tools.stream()
        .filter(tool -> "list_paper_locations".equals(tool.name()))
        .findFirst()
        .orElseThrow();
String listSchemaJson = objectMapper.writeValueAsString(listTool.parameters());
assertTrue(listSchemaJson.contains("pageRange"));
assertTrue(listSchemaJson.contains("locationTypes"));
assertFalse(listSchemaJson.contains("queryText"));
assertFalse(listSchemaJson.contains("query"));
```

In `delegatesValidatedSearchLocationAndReadCalls`, add:

```java
ProductToolResult listedLocationsResult = new ProductToolResult(
        "list_paper_locations",
        true,
        Map.of("status", "OK", "locations", List.of()),
        ProductToolEffect.PAPER_DISCOVERY
);
when(adapter.listPaperLocations(
        List.of("paper_handle_abc"),
        new ReadingToolArgumentValidator.PageRange(3, 3),
        List.of(PaperLocationType.PAGE),
        context
)).thenReturn(listedLocationsResult);

assertEquals(listedLocationsResult, registry.execute("list_paper_locations", Map.of(
        "paperHandles", List.of("paper_handle_abc"),
        "pageRange", Map.of("from", 3, "to", 3),
        "locationTypes", List.of("PAGE")
), context));
verify(adapter).listPaperLocations(
        List.of("paper_handle_abc"),
        new ReadingToolArgumentValidator.PageRange(3, 3),
        List.of(PaperLocationType.PAGE),
        context
);
```

Add an import:

```java
import com.yizhaoqi.smartpai.model.PaperLocationType;
```

- [ ] **Step 2: Run the registry test and verify it fails**

Run:

```bash
mvn -q -Dtest=ProductReadingToolRegistryTest test
```

Expected: FAIL because the registry still exposes four tools.

- [ ] **Step 3: Register `list_paper_locations`**

In `ProductReadingToolRegistry`, add:

```java
private static final String LIST_LOCATIONS_TOOL_NAME = "list_paper_locations";
```

Change the constructor tool list to:

```java
this.tools = List.of(
        searchPaperCandidatesTool(),
        listPaperLocationsTool(),
        findReadingLocationsTool(),
        readLocationsTool(),
        traceSourceQuotesTool()
);
```

Add the switch case:

```java
case LIST_LOCATIONS_TOOL_NAME -> executeListPaperLocations(safeArguments, safeContext);
```

Add the executor:

```java
private ProductToolResult executeListPaperLocations(Map<String, Object> arguments, ProductToolContext context) {
    ReadingToolArgumentValidator.ValidationResult validation = validator.validateListPaperLocations(arguments);
    if (!validation.valid()) {
        return invalidArgument(LIST_LOCATIONS_TOOL_NAME, validation);
    }
    return adapter.listPaperLocations(
            validator.stringList(arguments.get("paperHandles")),
            validator.pageRange(arguments.get("pageRange")),
            validator.locationTypes(arguments.get("locationTypes")),
            context
    );
}
```

Add the schema:

```java
private AgentToolRegistry.AgentTool listPaperLocationsTool() {
    return new AgentToolRegistry.AgentTool(
            LIST_LOCATIONS_TOOL_NAME,
            "List deterministic current READY paper locations for explicit paperHandles. Returns locationRefs only; it does not read content and does not accept semantic query text.",
            objectSchema(Map.of(
                    "paperHandles", arrayStringSchema("Opaque paper handles returned by PaperLoom tools or clicked paper rows."),
                    "pageRange", objectSchema(Map.of(
                            "from", integerSchema("1-based inclusive start page."),
                            "to", integerSchema("1-based inclusive end page.")
                    ), List.of("from", "to")),
                    "locationTypes", arrayEnumSchema(
                            "Optional deterministic location type filter.",
                            List.of("PAGE", "SECTION", "TABLE", "FIGURE")
                    )
            ), List.of("paperHandles"))
    );
}
```

Add integer schema helper:

```java
private Map<String, Object> integerSchema(String description) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "integer");
    schema.put("description", description);
    return schema;
}
```

- [ ] **Step 4: Run the registry test and verify it passes**

Run:

```bash
mvn -q -Dtest=ProductReadingToolRegistryTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```bash
git add src/main/java/com/yizhaoqi/smartpai/service/ProductReadingToolRegistry.java \
  src/test/java/com/yizhaoqi/smartpai/service/ProductReadingToolRegistryTest.java
git commit -m "feat: expose list paper locations reading tool"
```

### Task 4: Harness Authorization and Prompt Policy

**Files:**

- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarness.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarnessTest.java`

**Interfaces:**

- Consumes: `list_paper_locations` tool result key `locations`
- Produces: same-turn disclosure of only current `locationRef` values returned by `list_paper_locations`
- Produces: separate paper-handle authorization for deterministic listing after `trace_source_quotes`

- [ ] **Step 1: Update five-tool harness surface tests**

In `ProductReadingReActHarnessTest`, update every `List.of` expected tool-name list and the `readingTools()` helper to:

```java
return List.of(
        tool("search_paper_candidates"),
        tool("list_paper_locations"),
        tool("find_reading_locations"),
        tool("read_locations"),
        tool("trace_source_quotes")
);
```

Update prompt assertions in `passesExactlyReadingToolsToLlmAndPromptContainsNoLegacyToolNames`:

```java
assertTrue(promptMessages.contains("list_paper_locations"));
assertTrue(promptMessages.contains("deterministic"));
assertTrue(promptMessages.contains("semantic"));
assertTrue(promptMessages.contains("trace_source_quotes returned locationRef values are metadata"));
```

- [ ] **Step 2: Add a test that list output authorizes read**

Add:

```java
@Test
void readLocationsCanUseRefsDisclosedByListPaperLocations() {
    LlmProviderRouter llm = mock(LlmProviderRouter.class);
    ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
    ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
    List<AgentToolRegistry.AgentTool> tools = readingTools();
    when(registry.listTools()).thenReturn(tools);
    when(registry.execute(eq("search_paper_candidates"), any(), any())).thenReturn(searchResult());
    when(registry.execute(eq("list_paper_locations"), any(), any())).thenReturn(listLocationsResult());
    when(registry.execute(eq("read_locations"), any(), any())).thenReturn(readResult());
    when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
            .thenReturn(toolCallTurn("call_1", "search_paper_candidates", Map.of("queryText", "Agentic eval")))
            .thenReturn(toolCallTurn("call_2", "list_paper_locations", Map.of(
                    "paperHandles", List.of("paper_handle_abc"),
                    "pageRange", Map.of("from", 3, "to", 3),
                    "locationTypes", List.of("PAGE")
            )))
            .thenReturn(toolCallTurn("call_3", "read_locations", Map.of(
                    "locationRefs", List.of("page_ref_abc")
            )))
            .thenReturn(finalTurn("""
                    {
                      "answerType": "EVIDENCE_ANSWER",
                      "answer": "Page 3 reports a score improvement {{sourceQuoteRef:source_quote_abc}}.",
                      "evidenceBasedClaims": [
                        {
                          "claim": "Page 3 reports a score improvement.",
                          "sourceQuoteRefs": ["source_quote_abc"]
                        }
                      ],
                      "stateClaims": [],
                      "limitations": [],
                      "nonEvidenceNotes": [],
                      "missingFields": [],
                      "reason": ""
                    }
                    """));
    ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

    ProductTurnResult result = harness.run(request("Read page 3"));

    assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
    verify(registry).execute(eq("list_paper_locations"), any(), any());
    verify(registry).execute(eq("read_locations"), any(), any());
}
```

Add helper:

```java
private ProductToolResult listLocationsResult() {
    return new ProductToolResult(
            "list_paper_locations",
            true,
            Map.of(
                    "paperHandles", List.of("paper_handle_abc"),
                    "status", "OK",
                    "locations", List.of(Map.of(
                            "locationRef", "page_ref_abc",
                            "paperHandle", "paper_handle_abc",
                            "locationType", "PAGE",
                            "pageNumber", 3,
                            "label", "Page 3"
                    )),
                    "constraints", Map.of(
                            "previewIsSourceQuote", false,
                            "locationRefIsSourceQuote", false,
                            "paperContentClaimsAllowed", false
                    )
            ),
            ProductToolEffect.PAPER_DISCOVERY
    );
}
```

- [ ] **Step 3: Add a test that trace can lead to list but not direct read**

Replace or extend `traceOutputDoesNotDiscloseLocationRefsForReadLocations` with:

```java
@Test
void tracePaperHandleCanBeUsedForListButTraceLocationRefStillCannotBeReadDirectly() {
    LlmProviderRouter llm = mock(LlmProviderRouter.class);
    ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
    ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
    List<AgentToolRegistry.AgentTool> tools = readingTools();
    when(registry.listTools()).thenReturn(tools);
    when(registry.execute(eq("trace_source_quotes"), any(), any())).thenReturn(traceResult());
    when(registry.execute(eq("list_paper_locations"), any(), any())).thenReturn(listLocationsResult());
    when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
            .thenReturn(toolCallTurn("call_1", "trace_source_quotes", Map.of(
                    "sourceQuoteRefs", List.of("source_quote_abc")
            )))
            .thenReturn(toolCallTurn("call_2", "list_paper_locations", Map.of(
                    "paperHandles", List.of("paper_handle_abc"),
                    "pageRange", Map.of("from", 3, "to", 3),
                    "locationTypes", List.of("PAGE")
            )))
            .thenReturn(toolCallTurn("call_3", "read_locations", Map.of(
                    "locationRefs", List.of("page_ref_old")
            )));
    ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

    ProductTurnResult result = harness.run(requestWithClickedRefs(
            "Continue around this source",
            List.of("source_quote_abc")
    ));

    assertEquals(ProductResultStatus.FAILED, result.resultStatus());
    assertEquals(ProductStopReason.TOOL_FAILED, result.stopReason());
    verify(registry).execute(eq("list_paper_locations"), any(), any());
    verify(registry, never()).execute(eq("read_locations"), any(), any());
}
```

- [ ] **Step 4: Add a test that trace-returned paperHandle does not authorize semantic search**

Add:

```java
@Test
void tracePaperHandleDoesNotAuthorizeFindReadingLocations() {
    LlmProviderRouter llm = mock(LlmProviderRouter.class);
    ProductReadingToolRegistry registry = mock(ProductReadingToolRegistry.class);
    ProductReadingTraceRecorder traceRecorder = mock(ProductReadingTraceRecorder.class);
    List<AgentToolRegistry.AgentTool> tools = readingTools();
    when(registry.listTools()).thenReturn(tools);
    when(registry.execute(eq("trace_source_quotes"), any(), any())).thenReturn(traceResult());
    when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
            .thenReturn(toolCallTurn("call_1", "trace_source_quotes", Map.of(
                    "sourceQuoteRefs", List.of("source_quote_abc")
            )))
            .thenReturn(toolCallTurn("call_2", "find_reading_locations", Map.of(
                    "paperHandles", List.of("paper_handle_abc"),
                    "queryText", "nearby context"
            )));
    ProductReadingReActHarness harness = new ProductReadingReActHarness(llm, registry, objectMapper, traceRecorder);

    ProductTurnResult result = harness.run(requestWithClickedRefs(
            "Search around this source",
            List.of("source_quote_abc")
    ));

    assertEquals(ProductResultStatus.FAILED, result.resultStatus());
    assertEquals(ProductStopReason.TOOL_FAILED, result.stopReason());
    verify(registry, never()).execute(eq("find_reading_locations"), any(), any());
}
```

- [ ] **Step 5: Run harness tests and verify they fail**

Run:

```bash
mvn -q -Dtest=ProductReadingReActHarnessTest test
```

Expected: FAIL because the harness still requires exactly four tools and does not process `list_paper_locations`.

- [ ] **Step 6: Update required tool names**

In `ProductReadingReActHarness`, change `REQUIRED_TOOL_NAMES` to:

```java
private static final List<String> REQUIRED_TOOL_NAMES = List.of(
        "search_paper_candidates",
        "list_paper_locations",
        "find_reading_locations",
        "read_locations",
        "trace_source_quotes"
);
```

- [ ] **Step 7: Split paper-handle authorization by use**

Change `ReadingTurnState` fields to:

```java
private final Set<String> clickedSourceQuoteRefs;
private final Set<String> semanticPaperHandles = new LinkedHashSet<>();
private final Set<String> deterministicLocationPaperHandles = new LinkedHashSet<>();
private final Set<String> disclosedLocationRefs = new LinkedHashSet<>();
private final Set<String> allowedSourceQuoteRefs = new LinkedHashSet<>();
private final Map<String, Map<String, Object>> sourceQuotePayloads = new LinkedHashMap<>();
```

In `validateToolCall`, use:

```java
if ("find_reading_locations".equals(toolName)) {
    List<String> paperHandles = stringList(arguments.get("paperHandles"));
    if (paperHandles.isEmpty() || !state.semanticPaperHandles.containsAll(paperHandles)) {
        return ToolCallValidation.rejected("hidden_paper_handle");
    }
}
if ("list_paper_locations".equals(toolName)) {
    List<String> paperHandles = stringList(arguments.get("paperHandles"));
    if (paperHandles.isEmpty() || !state.deterministicLocationPaperHandles.containsAll(paperHandles)) {
        return ToolCallValidation.rejected("hidden_paper_handle");
    }
}
```

- [ ] **Step 8: Update state after tool results**

In `updateState`, for search results:

```java
state.semanticPaperHandles.add(paperHandle);
state.deterministicLocationPaperHandles.add(paperHandle);
```

Add handling for listed locations:

```java
if ("list_paper_locations".equals(toolName)) {
    for (Map<String, Object> location : mapList(toolResult.data().get("locations"))) {
        String locationRef = stringValue(location.get("locationRef"));
        if (!locationRef.isBlank()) {
            state.disclosedLocationRefs.add(locationRef);
        }
    }
    return;
}
```

For trace results, add only paper handles and Source Quotes:

```java
if ("trace_source_quotes".equals(toolName)) {
    for (Map<String, Object> sourceQuote : mapList(toolResult.data().get("sourceQuotes"))) {
        String paperHandle = stringValue(sourceQuote.get("paperHandle"));
        if (!paperHandle.isBlank()) {
            state.deterministicLocationPaperHandles.add(paperHandle);
        }
        String sourceQuoteRef = stringValue(sourceQuote.get("sourceQuoteRef"));
        if (!sourceQuoteRef.isBlank()) {
            state.allowedSourceQuoteRefs.add(sourceQuoteRef);
            state.sourceQuotePayloads.put(sourceQuoteRef, new LinkedHashMap<>(sourceQuote));
        }
    }
    return;
}
```

Keep `read_locations` adding citeable Source Quotes exactly as before.

- [ ] **Step 9: Update the system prompt**

Replace the tool-policy section with:

```text
You are PaperLoom Product Reading ReAct Source Quote MVP.
Available tools are exactly search_paper_candidates, list_paper_locations, find_reading_locations, read_locations, and trace_source_quotes.
Use search_paper_candidates for paper candidate discovery.
Use find_reading_locations for semantic in-paper location search; it requires queryText and paperHandles disclosed by search_paper_candidates in this turn.
Use list_paper_locations for deterministic section/page/table/figure refs; it requires paperHandles disclosed by search_paper_candidates or trace_source_quotes in this turn.
Use read_locations only after explicit locationRef values were returned by find_reading_locations or list_paper_locations in this turn.
Use trace_source_quotes only for sourceQuoteRefs listed in this turn's explicit clicked Source Quote anchors.
trace_source_quotes returned locationRef values are metadata, not read_locations input.
To read broader context around a traced Source Quote, call list_paper_locations with the traced paperHandle and pageNumber or location type, then call read_locations with refs returned by list_paper_locations.
```

Keep the existing Source Quote citation rules and forbidden-token rules.

- [ ] **Step 10: Run harness tests and verify they pass**

Run:

```bash
mvn -q -Dtest=ProductReadingReActHarnessTest test
```

Expected: PASS.

- [ ] **Step 11: Commit**

Run:

```bash
git add src/main/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarness.java \
  src/test/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarnessTest.java
git commit -m "feat: allow deterministic reading location listing"
```

### Task 5: Integration Gates

**Files:**

- Inspect: `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingConversationService.java`
- Inspect: `src/main/java/com/yizhaoqi/smartpai/service/ProductConversationService.java`
- Inspect: `src/main/java/com/yizhaoqi/smartpai/service/ProductReActHarness.java`
- Inspect: `src/main/java/com/yizhaoqi/smartpai/service/ProductToolRegistry.java`
- Test: existing Product Reading and legacy Product tests

**Interfaces:**

- Consumes: all changes from Tasks 1-4
- Produces: verified five-tool Product Reading surface with legacy Product path unchanged

- [ ] **Step 1: Run focused Product Reading tests**

Run:

```bash
mvn -q -Dtest=ProductReadingToolRegistryTest,ReadingToolArgumentValidatorTest,ProductReadingToolAdapterTest,ProductReadingReActHarnessTest test
```

Expected: PASS.

- [ ] **Step 2: Run caller and flag regression tests**

Run:

```bash
mvn -q -Dtest=ProductReadingConversationServiceTest,ChatHandlerProductHarnessTest test
```

Expected: PASS.

- [ ] **Step 3: Run legacy Product regression tests**

Run:

```bash
mvn -q -Dtest=ProductConversationServiceTest,ProductReActHarnessTest,ProductToolRegistryTest test
```

Expected: PASS.

- [ ] **Step 4: Run compile and full test gates**

Run:

```bash
mvn -q -DskipTests test
mvn -q test
git diff --check
```

Expected: all commands PASS.

- [ ] **Step 5: Run isolation searches**

Run:

```bash
rg -n "list_paper_locations" src/main/java src/test/java docs
rg -n "ProductReadingConversationService|ProductReadingReactProperties|ProductReadingReActHarness|ProductReadingToolRegistry" src/main/java/com/yizhaoqi/smartpai/service/ProductConversationService.java
rg -n "ProductReActHarness|ProductToolRegistry|ProductConversationService|ProductMemoryService|\\bConversationService\\b" src/main/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarness.java src/main/java/com/yizhaoqi/smartpai/service/ProductReadingConversationService.java
```

Expected:

- The first search finds the new Product Reading tool implementation, tests, and docs.
- The second search finds no accidental Product Reading dependency in `ProductConversationService.java`.
- The third search finds no accidental legacy Product dependency in `ProductReadingReActHarness.java` or `ProductReadingConversationService.java`.

- [ ] **Step 6: Commit final verification-only adjustments if needed**

If test-only expectation updates were required after the focused commits, run:

```bash
git add src/test/java/com/yizhaoqi/smartpai/service/ProductReadingConversationServiceTest.java \
  src/test/java/com/yizhaoqi/smartpai/service/ChatHandlerProductHarnessTest.java
git commit -m "test: cover reading list location integration"
```

Expected: commit only if those files changed.

## Completion Criteria

- `ProductReadingToolRegistry.listTools()` exposes exactly five tools in this order:

```text
search_paper_candidates
list_paper_locations
find_reading_locations
read_locations
trace_source_quotes
```

- `list_paper_locations` requires `paperHandles`.
- `list_paper_locations` accepts optional `pageRange` and `locationTypes`.
- `list_paper_locations` rejects semantic query fields, raw ids, ordinals, and tuning controls.
- `list_paper_locations` resolves `paperHandle` through `ProductPaperHandleService`.
- `list_paper_locations` checks user visibility, locked `SourceScope`, and current READY Reading Model state.
- `list_paper_locations` returns only current-model `locationRef` values.
- `read_locations` accepts refs returned by `list_paper_locations` in the same turn.
- `read_locations` still rejects refs that came only from `trace_source_quotes` metadata.
- `trace_source_quotes` can disclose a `paperHandle` for deterministic `list_paper_locations`.
- `trace_source_quotes` does not disclose paper handles for `find_reading_locations`.
- No legacy Product harness or registry behavior changes.
- Focused Product Reading tests pass.
- Legacy Product tests pass.
- `git diff --check` passes.

## Deferred Historical Tools

These remain out of scope for this next minimal closed loop:

```text
get_session_state
list_papers
find_papers_by_identity
get_paper_outline
```

Add them only after the `trace_source_quotes -> list_paper_locations -> read_locations` context-expansion loop is implemented and verified.
