# Product Reading Outline Minimal Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `get_paper_outline` as the next Product Reading tool so a selected paper can be inspected structurally, then read through existing `read_locations` Source Quotes.

**Architecture:** Keep the isolated Product Reading path and add exactly one historical tool in this slice. `get_paper_outline` consumes explicit `paperHandle` values, reads only current READY Reading Model structure, returns section refs plus parser-quality metadata, and lets the existing harness disclose returned `sectionRef` values for `read_locations`. It does not read paper content, add hidden selection state, or touch the legacy Product ReAct harness.

**Tech Stack:** Java 17, Spring Boot, Spring Data JPA, Jackson, JUnit 5, Mockito, Maven.

## Global Constraints

- Keep the legacy Product path unchanged: do not modify `ProductReActHarness`, `ProductToolRegistry`, `ProductConversationService`, or `ChatHandler`.
- Keep the Product Reading entry point behind `paperloom.react.reading-phase1.enabled=true`; do not promote the flag.
- Add only `get_paper_outline` in this slice. Do not add `get_session_state`, `list_papers`, or `find_papers_by_identity`.
- The Product Reading tool surface after this slice is exactly six tools in this order: `search_paper_candidates`, `get_paper_outline`, `list_paper_locations`, `find_reading_locations`, `read_locations`, `trace_source_quotes`.
- `get_paper_outline` is deterministic navigation, not semantic search.
- `get_paper_outline` reads only current READY Reading Model metadata, `PaperSection`, and `PaperLocation` rows; it does not read `sectionText`, page text, table text, figure text, Source Quote text, or `searchableText`.
- `get_paper_outline` rejects raw ids, ordinals, semantic query fields, page ranges, output controls, and retrieval tuning controls.
- Returned `sectionRef` values are current-model SECTION `PaperLocation.locationRef` values and may authorize `read_locations` in the same turn.
- Returned outline data is navigation only and cannot support paper-content claims.

---

## Grill Outcomes

### 1. Are the current five Product Reading tools the full target?

Recommended answer: no.

Adopted. The current five tools close the Source Quote and clicked-source context loop, but the historical target still includes `get_session_state`, `list_papers`, `find_papers_by_identity`, and `get_paper_outline`.

### 2. Should the next slice add all four missing historical tools?

Recommended answer: no.

Adopted. The next smallest closed loop is one tool: `get_paper_outline`. It improves paper-content reading immediately because existing `search_paper_candidates`, `list_paper_locations`, and `read_locations` can already supply the rest of the loop.

### 3. Why choose `get_paper_outline` before `list_papers` or `find_papers_by_identity`?

Recommended answer: because it closes a structure-guided reading loop with the tools already implemented.

Adopted. The loop becomes:

```text
search_paper_candidates
-> get_paper_outline
-> read_locations on returned sectionRef values
-> EVIDENCE_ANSWER with Source Quotes
```

`list_papers` and `find_papers_by_identity` are still important, but they are paper-discovery improvements rather than the next content-reading closure.

### 4. Should `get_paper_outline` read or summarize paper content?

Recommended answer: no.

Adopted. It returns structure and quality signals only: title, filename, supported location types, parser quality, and section refs/headings/page spans. Paper-content claims still require `read_locations` or `trace_source_quotes`.

### 5. Should trace-returned `paperHandle` values be allowed for outline lookup?

Recommended answer: yes, for deterministic outline lookup only.

Adopted. A `paperHandle` disclosed by `trace_source_quotes` may call `get_paper_outline`, just as it may call `list_paper_locations`. It must not unlock `find_reading_locations`.

### 6. Should outline-returned `sectionRef` values authorize `read_locations`?

Recommended answer: yes.

Adopted. `get_paper_outline` returns current READY model SECTION location refs, so the harness should add them to the same-turn disclosed location set.

## File Structure

- Modify `src/main/java/com/yizhaoqi/smartpai/service/ReadingToolArgumentValidator.java`: add `get_paper_outline` argument validation and parser helpers.
- Modify `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingToolRegistry.java`: register the sixth tool, expose its closed schema, and delegate validated calls.
- Modify `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingToolAdapter.java`: resolve handles, verify visibility/scope/READY state, build current-model outlines from `PaperRepository`, `PaperReadingModelRepository`, `PaperSectionRepository`, and `PaperLocationRepository`.
- Modify `src/main/java/com/yizhaoqi/smartpai/service/ReadingToolOutputMapper.java`: add paper outline, parser quality, and section card mappers.
- Modify `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarness.java`: accept the six-tool surface, update prompt policy, validate `get_paper_outline`, and disclose returned `sectionRef` values for `read_locations`.
- Modify `src/test/java/com/yizhaoqi/smartpai/service/ReadingToolArgumentValidatorTest.java`: cover the new closed argument contract.
- Modify `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingToolRegistryTest.java`: cover exposure, schema, validation, and delegation.
- Modify `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingToolAdapterTest.java`: cover current READY outline building and LLM-visible output safety.
- Modify `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarnessTest.java`: cover authorization, prompt policy, and outline-to-read behavior.

## Tool Contract

### `get_paper_outline`

Input:

```json
{
  "paperHandles": ["paper_handle_abc"]
}
```

Output:

```json
{
  "paperHandles": ["paper_handle_abc"],
  "status": "OK",
  "papers": [
    {
      "paperHandle": "paper_handle_abc",
      "title": "Agentic Eval Benchmark",
      "originalFilename": "agentic-eval.pdf",
      "supportedLocationTypes": ["PAGE", "SECTION", "TABLE", "FIGURE"],
      "parserQuality": {
        "pageTextCoverage": 1.0,
        "outlineConfidence": "HIGH",
        "warnings": []
      },
      "sections": [
        {
          "sectionRef": "section_ref_methods",
          "heading": "Methods",
          "sectionRole": "METHODS",
          "level": 1,
          "pageStart": 3,
          "pageEnd": 5
        }
      ]
    }
  ],
  "constraints": {
    "outlineIsSourceQuote": false,
    "paperContentClaimsAllowed": false
  }
}
```

Status values:

```text
OK
PAPER_HANDLE_UNAVAILABLE
READING_MODEL_UNAVAILABLE
OUTLINE_UNAVAILABLE
INVALID_ARGUMENT
```

Parser quality MVP:

```text
pageTextCoverage = readablePageCount / pageCount, capped to [0.0, 1.0], or 0.0 with PAGE_COVERAGE_UNKNOWN warning when unavailable
outlineConfidence = HIGH when at least one current SECTION location resolves to a PaperSection, otherwise LOW
warnings = PAGE_COVERAGE_UNKNOWN, PARTIAL_PAGE_TEXT_COVERAGE, NO_SECTION_OUTLINE as applicable
```

Section role MVP:

```text
ABSTRACT       heading contains abstract
INTRODUCTION  heading contains introduction
METHODS       heading contains method, approach, model, algorithm, implementation
RESULTS       heading contains result, experiment, evaluation, benchmark
DISCUSSION    heading contains discussion, analysis
LIMITATIONS   heading contains limitation, failure
CONCLUSION    heading contains conclusion, future
RELATED_WORK  heading contains related work, background
APPENDIX      heading contains appendix, supplementary
OTHER         fallback
```

## Task 1: Validator And Registry Contract

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ReadingToolArgumentValidator.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingToolRegistry.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/service/ReadingToolArgumentValidatorTest.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingToolRegistryTest.java`

**Interfaces:**
- Produces: `ReadingToolArgumentValidator#validateGetPaperOutline(Map<String, Object>)`
- Produces: `ProductReadingToolAdapter#getPaperOutline(List<String>, ProductToolContext)`
- Produces: registry tool schema for `get_paper_outline`

- [ ] **Step 1: Write failing validator tests**

Add to `ReadingToolArgumentValidatorTest`:

```java
@Test
void getPaperOutlineRequiresHandlesAndRejectsSemanticOrdinalsAndControls() {
    ReadingToolArgumentValidator.ValidationResult missing =
            validator.validateGetPaperOutline(Map.of());
    ReadingToolArgumentValidator.ValidationResult query =
            validator.validateGetPaperOutline(Map.of(
                    "paperHandles", List.of("paper_handle_abc"),
                    "queryText", "methods"
            ));
    ReadingToolArgumentValidator.ValidationResult ordinal =
            validator.validateGetPaperOutline(Map.of(
                    "paperHandles", List.of("paper_handle_abc"),
                    "ordinal", 1
            ));
    ReadingToolArgumentValidator.ValidationResult pageRange =
            validator.validateGetPaperOutline(Map.of(
                    "paperHandles", List.of("paper_handle_abc"),
                    "pageRange", Map.of("from", 1, "to", 2)
            ));
    ReadingToolArgumentValidator.ValidationResult valid =
            validator.validateGetPaperOutline(Map.of(
                    "paperHandles", List.of("paper_handle_abc")
            ));

    assertFalse(missing.valid());
    assertEquals("missing_argument", missing.error());
    assertEquals("paperHandles", missing.argument());
    assertFalse(query.valid());
    assertEquals("forbidden_argument", query.error());
    assertEquals("queryText", query.argument());
    assertFalse(ordinal.valid());
    assertEquals("forbidden_argument", ordinal.error());
    assertEquals("ordinal", ordinal.argument());
    assertFalse(pageRange.valid());
    assertEquals("forbidden_argument", pageRange.error());
    assertEquals("pageRange", pageRange.argument());
    assertTrue(valid.valid());
}
```

- [ ] **Step 2: Write failing registry tests**

Update `ProductReadingToolRegistryTest#exposesOnlySourceQuoteReadingToolsWithClosedSchemas` to expect:

```java
assertEquals(List.of(
        "search_paper_candidates",
        "get_paper_outline",
        "list_paper_locations",
        "find_reading_locations",
        "read_locations",
        "trace_source_quotes"
), names);
```

Add a delegation assertion:

```java
ProductToolResult outlineResult = new ProductToolResult(
        "get_paper_outline",
        true,
        Map.of("status", "OK", "papers", List.of()),
        ProductToolEffect.PAPER_DISCOVERY
);
when(adapter.getPaperOutline(List.of("paper_handle_abc"), context)).thenReturn(outlineResult);

assertEquals(outlineResult, registry.execute("get_paper_outline", Map.of(
        "paperHandles", List.of("paper_handle_abc")
), context));
verify(adapter).getPaperOutline(List.of("paper_handle_abc"), context);
```

- [ ] **Step 3: Run tests to verify they fail**

Run:

```bash
mvn -q -Dtest=ReadingToolArgumentValidatorTest,ProductReadingToolRegistryTest test
```

Expected: FAIL because `validateGetPaperOutline` and `ProductReadingToolAdapter#getPaperOutline` do not exist, and the registry still exposes five tools.

- [ ] **Step 4: Implement validator and registry**

In `ReadingToolArgumentValidator`, add:

```java
private static final Set<String> OUTLINE_FORBIDDEN_ARGUMENTS = Set.of(
        "paperId", "paperIds", "paperRef", "paperRefs",
        "locationId", "locationIds", "locationRef", "locationRefs",
        "chunkId", "chunkIds", "chunkRef", "readingElementId",
        "modelVersion", "indexVersion", "indexName",
        "query", "queryText", "question", "readingNeed", "semanticNeed", "topicText",
        "ordinal", "ordinals", "candidateOrdinal", "resultOrdinal",
        "pageRange", "mapMode", "depth", "includeText", "includeContent",
        "limit", "topK", "maxCandidates", "pageSize", "budget",
        "rerank", "rerankEnabled", "searchMode"
);
private static final Set<String> OUTLINE_ALLOWED_ARGUMENTS = Set.of("paperHandles");

public ValidationResult validateGetPaperOutline(Map<String, Object> arguments) {
    Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
    String forbiddenArgument = firstForbiddenArgument(safeArguments, OUTLINE_FORBIDDEN_ARGUMENTS);
    if (forbiddenArgument != null) {
        return ValidationResult.invalid("forbidden_argument", forbiddenArgument);
    }
    String unsupportedArgument = firstUnsupportedTopLevelArgument(safeArguments, OUTLINE_ALLOWED_ARGUMENTS);
    if (unsupportedArgument != null) {
        return ValidationResult.invalid("unsupported_argument", unsupportedArgument);
    }
    if (stringList(safeArguments.get("paperHandles")).isEmpty()) {
        return ValidationResult.invalid("missing_argument", "paperHandles");
    }
    return ValidationResult.validResult();
}
```

In `ProductReadingToolRegistry`, add `GET_OUTLINE_TOOL_NAME`, insert `getPaperOutlineTool()` after `searchPaperCandidatesTool()`, validate with `validateGetPaperOutline`, and delegate:

```java
return adapter.getPaperOutline(validator.stringList(arguments.get("paperHandles")), context);
```

- [ ] **Step 5: Run tests to verify they pass**

Run:

```bash
mvn -q -Dtest=ReadingToolArgumentValidatorTest,ProductReadingToolRegistryTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yizhaoqi/smartpai/service/ReadingToolArgumentValidator.java \
  src/main/java/com/yizhaoqi/smartpai/service/ProductReadingToolRegistry.java \
  src/test/java/com/yizhaoqi/smartpai/service/ReadingToolArgumentValidatorTest.java \
  src/test/java/com/yizhaoqi/smartpai/service/ProductReadingToolRegistryTest.java
git commit -m "feat: expose reading paper outline tool"
```

## Task 2: Adapter And Output Mapping

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingToolAdapter.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ReadingToolOutputMapper.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingToolAdapterTest.java`

**Interfaces:**
- Consumes: `ProductPaperHandleService#resolvePaperHandle(String)`
- Consumes: `ProductPaperHandleService#isPaperVisibleToUser(String, Long, SourceScope)`
- Consumes: `ProductPaperHandleService#hasCurrentReadyReadingModel(String)`
- Produces: `ProductReadingToolAdapter#getPaperOutline(List<String>, ProductToolContext)`
- Produces: `ReadingToolOutputMapper#paperOutline(...)`

- [ ] **Step 1: Write failing adapter tests**

Add a test named `getPaperOutlineReturnsCurrentReadyStructureAndSectionRefsOnly`.

Assertions:

```java
assertTrue(result.success());
assertEquals("OK", result.data().get("status"));
assertEquals(List.of("paper_handle_ready"), result.data().get("paperHandles"));
String json = objectMapper.writeValueAsString(result.data());
assertTrue(json.contains("section_ref_methods"));
assertTrue(json.contains("Methods"));
assertTrue(json.contains("outlineConfidence"));
assertFalse(json.contains("ready-paper"));
assertFalse(json.contains("model-v1"));
assertFalse(json.contains("paperId"));
assertFalse(json.contains("modelVersion"));
assertFalse(json.contains("sectionText"));
```

Mock data:

```java
when(handleService.resolvePaperHandle("paper_handle_ready")).thenReturn(Optional.of("ready-paper"));
when(handleService.isPaperVisibleToUser("ready-paper", 7L, SourceScope.auto())).thenReturn(true);
when(handleService.hasCurrentReadyReadingModel("ready-paper")).thenReturn(true);
when(modelRepository.findFirstByPaperIdAndIsCurrentTrue("ready-paper"))
        .thenReturn(Optional.of(model("ready-paper", "model-v1")));
when(paperRepository.findFirstByPaperIdOrderByCreatedAtDesc("ready-paper"))
        .thenReturn(Optional.of(paper("ready-paper", "Agentic Eval Benchmark", "agentic-eval.pdf")));
when(sectionRepository.findByPaperIdAndModelVersionOrderByPageNumberFromAscDisplayOrderAsc("ready-paper", "model-v1"))
        .thenReturn(List.of(section("ready-paper", "model-v1", "sec-methods", "Methods", 1, 3, 5, 1)));
when(locationRepository.findByPaperIdAndModelVersionOrderByPageNumberAscIdAsc("ready-paper", "model-v1"))
        .thenReturn(List.of(
                location("ready-paper", "page_ref_1", PaperLocationType.PAGE),
                sectionLocation("ready-paper", "section_ref_methods", "sec-methods", "Methods", 3, 5)
        ));
```

- [ ] **Step 2: Add repository dependencies**

Inject `PaperRepository` and `PaperSectionRepository` into `ProductReadingToolAdapter`. Update existing tests' constructor calls.

- [ ] **Step 3: Implement outline mapping**

Add `getPaperOutline(...)` to `ProductReadingToolAdapter`.

Implementation rules:

```text
1. Sanitize `paperHandles`; empty means INVALID_ARGUMENT.
2. Resolve each handle with `ProductPaperHandleService`.
3. Check visibility, locked SourceScope, and current READY Reading Model.
4. Load the latest Paper row for title and original filename.
5. Load current PaperSection rows ordered by page and display order.
6. Load current PaperLocation rows ordered by page and id.
7. Build section cards only when a SECTION location's `sourceObjectId` matches `PaperSection.sectionId`.
8. Do not copy `PaperSection.sectionText` to output.
9. Compute supported location types from current locations.
10. Return LLM-visible handles/refs only.
```

Add mapper methods in `ReadingToolOutputMapper`:

```java
public Map<String, Object> paperOutline(
        String paperHandle,
        Paper paper,
        PaperReadingModel model,
        List<String> supportedLocationTypes,
        Map<String, Object> parserQuality,
        List<Map<String, Object>> sections
) { ... }

public Map<String, Object> sectionOutlineCard(
        PaperSection section,
        PaperLocation sectionLocation
) { ... }
```

`sectionOutlineCard` output fields:

```text
sectionRef = sectionLocation.locationRef
heading = section.sectionTitle
sectionRole = roleFromHeading(section.sectionTitle)
level = section.sectionLevel
pageStart = section.pageNumberFrom
pageEnd = section.pageNumberTo
```

- [ ] **Step 4: Run adapter tests**

Run:

```bash
mvn -q -Dtest=ProductReadingToolAdapterTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yizhaoqi/smartpai/service/ProductReadingToolAdapter.java \
  src/main/java/com/yizhaoqi/smartpai/service/ReadingToolOutputMapper.java \
  src/test/java/com/yizhaoqi/smartpai/service/ProductReadingToolAdapterTest.java
git commit -m "feat: build reading paper outlines"
```

## Task 3: Harness Authorization And Prompt Policy

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarness.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarnessTest.java`

**Interfaces:**
- Consumes: `get_paper_outline` tool output with `papers[].sections[].sectionRef`
- Produces: same-turn authorization for `read_locations(locationRefs=[sectionRef])`

- [ ] **Step 1: Write failing harness tests**

Update every helper that defines the Product Reading tool list to include:

```java
tool("get_paper_outline")
```

between `search_paper_candidates` and `list_paper_locations`.

Add test `outlineReturnedSectionRefsCanBeRead`:

```java
when(registry.execute(eq("search_paper_candidates"), any(), any())).thenReturn(searchResult());
when(registry.execute(eq("get_paper_outline"), any(), any())).thenReturn(outlineResult());
when(registry.execute(eq("read_locations"), any(), any())).thenReturn(readResult());
when(llm.completeReActTurn(eq("7"), any(), eq(tools), anyInt()))
        .thenReturn(toolCallTurn("call_1", "search_paper_candidates", Map.of("queryText", "Agentic eval")))
        .thenReturn(toolCallTurn("call_2", "get_paper_outline", Map.of(
                "paperHandles", List.of("paper_handle_abc")
        )))
        .thenReturn(toolCallTurn("call_3", "read_locations", Map.of(
                "locationRefs", List.of("section_ref_methods")
        )))
        .thenReturn(finalTurn("""
                {
                  "answerType": "EVIDENCE_ANSWER",
                  "answer": "The Methods section describes the evaluation setup {{sourceQuoteRef:source_quote_abc}}.",
                  "evidenceBasedClaims": [
                    {
                      "claim": "The Methods section describes the evaluation setup.",
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
```

Expected result:

```java
assertEquals(ProductResultStatus.COMPLETED, result.resultStatus());
verify(registry).execute(eq("get_paper_outline"), any(), any());
verify(registry).execute(eq("read_locations"), any(), any());
```

Add test `rejectsHiddenPaperHandleBeforeOutline` that calls `get_paper_outline` before any disclosure and expects `TOOL_FAILED`.

- [ ] **Step 2: Update the required tool surface**

Change `REQUIRED_TOOL_NAMES` to:

```java
private static final List<String> REQUIRED_TOOL_NAMES = List.of(
        "search_paper_candidates",
        "get_paper_outline",
        "list_paper_locations",
        "find_reading_locations",
        "read_locations",
        "trace_source_quotes"
);
```

- [ ] **Step 3: Add outline authorization**

In `validateToolCall`, add:

```java
if ("get_paper_outline".equals(toolName)) {
    List<String> paperHandles = stringList(arguments.get("paperHandles"));
    if (paperHandles.isEmpty() || !state.deterministicLocationPaperHandles.containsAll(paperHandles)) {
        return ToolCallValidation.rejected("hidden_paper_handle");
    }
}
```

In `updateState`, add:

```java
if ("get_paper_outline".equals(toolName)) {
    for (Map<String, Object> paper : mapList(toolResult.data().get("papers"))) {
        for (Map<String, Object> section : mapList(paper.get("sections"))) {
            String sectionRef = stringValue(section.get("sectionRef"));
            if (!sectionRef.isBlank()) {
                state.disclosedLocationRefs.add(sectionRef);
            }
        }
    }
    return;
}
```

- [ ] **Step 4: Update prompt policy**

Add to `systemPrompt`:

```text
Use get_paper_outline after choosing papers when structure, section choices, or parser quality is needed.
get_paper_outline requires paperHandles disclosed by search_paper_candidates or trace_source_quotes in this turn.
get_paper_outline returns sectionRef values for navigation only; they are not Source Quotes.
Paper outlines and parserQuality are navigation only.
```

- [ ] **Step 5: Run harness tests**

Run:

```bash
mvn -q -Dtest=ProductReadingReActHarnessTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarness.java \
  src/test/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarnessTest.java
git commit -m "feat: authorize outline-guided reading"
```

## Task 4: Verification And Isolation Gates

**Files:**
- Modify only if focused tests reveal expectation drift:
  - `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingConversationServiceTest.java`
  - `src/test/java/com/yizhaoqi/smartpai/service/ChatHandlerProductHarnessTest.java`

**Interfaces:**
- Consumes: all changes from Tasks 1-3.
- Produces: verified six-tool Product Reading outline loop.

- [ ] **Step 1: Run focused Product Reading tests**

Run:

```bash
mvn -q -Dtest=ReadingToolArgumentValidatorTest,ProductReadingToolRegistryTest,ProductReadingToolAdapterTest,ProductReadingReActHarnessTest test
```

Expected: PASS.

- [ ] **Step 2: Run compile gate**

Run:

```bash
mvn -q -DskipTests compile
```

Expected: PASS.

- [ ] **Step 3: Run isolation searches**

Run:

```bash
rg -n "get_paper_outline|ProductReadingReActHarness|ProductReadingToolRegistry" src/main/java/com/yizhaoqi/smartpai/service/ProductConversationService.java src/main/java/com/yizhaoqi/smartpai/service/ProductToolRegistry.java src/main/java/com/yizhaoqi/smartpai/service/ProductReActHarness.java
```

Expected: no output.

Run:

```bash
rg -n '"(paperId|modelVersion|readingElementId|sectionText|chunkRef|score|rank)"' src/main/java/com/yizhaoqi/smartpai/service/ProductReadingToolAdapter.java src/main/java/com/yizhaoqi/smartpai/service/ReadingToolOutputMapper.java src/main/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarness.java
```

Expected: no hits in LLM-visible output construction. Constructor injection and repository lookup code may still reference Java names such as `paperId`; output maps must not expose those keys.

- [ ] **Step 4: Run diff hygiene**

Run:

```bash
git diff --check
```

Expected: PASS.

- [ ] **Step 5: Commit verification-only adjustments if needed**

Only if test expectation files changed:

```bash
git add src/test/java/com/yizhaoqi/smartpai/service/ProductReadingConversationServiceTest.java \
  src/test/java/com/yizhaoqi/smartpai/service/ChatHandlerProductHarnessTest.java
git commit -m "test: cover reading outline integration"
```

## Completion Criteria

- `ProductReadingToolRegistry.listTools()` exposes exactly six tools in this order:

```text
search_paper_candidates
get_paper_outline
list_paper_locations
find_reading_locations
read_locations
trace_source_quotes
```

- `get_paper_outline` requires `paperHandles`.
- `get_paper_outline` rejects semantic query fields, raw ids, ordinals, page ranges, output controls, and tuning controls.
- `get_paper_outline` resolves `paperHandle` through `ProductPaperHandleService`.
- `get_paper_outline` checks user visibility, locked `SourceScope`, and current READY Reading Model state.
- `get_paper_outline` returns only current-model SECTION `sectionRef` values.
- `get_paper_outline` does not include section/page/table/figure content, `sectionText`, `searchableText`, raw `paperId`, `modelVersion`, or internal ids in LLM-visible output.
- `read_locations` accepts `sectionRef` values returned by `get_paper_outline` in the same turn.
- `trace_source_quotes` can disclose a `paperHandle` for deterministic `get_paper_outline`.
- `trace_source_quotes` still does not disclose paper handles for `find_reading_locations`.
- No legacy Product harness or registry behavior changes.
- Focused Product Reading tests pass.
- `mvn -q -DskipTests compile` passes.
- `git diff --check` passes.

## Deferred Historical Tools

These remain out of scope for this next minimal closed loop:

```text
get_session_state
list_papers
find_papers_by_identity
```

Recommended next slice after this one:

```text
get_session_state + list_papers
```

Reason: once outline-guided content reading is closed, the next smallest product-state loop is deterministic scope/count/browse. `find_papers_by_identity` should follow after browse because it shares the same deterministic paper-card contract and can reuse the browse/filter machinery.
