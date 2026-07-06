# Product Reading ReAct Minimal Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the smallest runnable Product Reading ReAct loop that lets the LLM call the Phase 1 reading tools, return paper candidates and reading-location navigation, and refuse paper-content claims until Source Quotes exist.

**Architecture:** The Phase 1 reading loop is intentionally incompatible with the legacy Product ReAct harness. The normal product chat path remains hardwired to `ProductConversationService -> ProductReActHarness -> ProductToolRegistry`. The reading path gets a separate entry service, `ProductReadingConversationService`, which calls only `ProductReadingReActHarness -> ProductReadingToolRegistry`. The disabled-by-default flag gates only this explicit reading experiment entry point; it must not switch the existing product chat service between harnesses.

**Tech Stack:** Spring Boot service beans, existing `LlmProviderRouter.completeReActTurn(...)`, existing `ProductTurnRequest` / `ProductTurnResult` / `AnswerEnvelope`, JUnit 5 + Mockito.

## Hard Isolation Contract

- Do not modify `ProductConversationService` production code for this loop.
- Do not inject `ProductReadingReActHarness`, `ProductReadingReactProperties`, or `ProductReadingConversationService` into `ProductConversationService`.
- Do not inject or call `ProductReActHarness` from any new reading-loop class.
- Do not inject or call `ProductToolRegistry` from any new reading-loop class.
- Do not add reading tools to `ProductToolRegistry`.
- Do not add legacy tools to `ProductReadingToolRegistry`.
- `ProductReadingReActHarness` must depend on `ProductReadingToolRegistry`, not on `ProductReActHarness`, `ProductToolRegistry`, or a generic selector that can run both.
- `ProductReadingConversationService` must depend on `ProductReadingReActHarness`, not on `ProductConversationService`, `ProductReActHarness`, `ProductToolRegistry`, `ConversationService`, or `ProductMemoryService`.
- `ProductReadingConversationService` must pass empty history and empty memory to `ProductReadingReActHarness` in Phase 1. This prevents legacy `paperRef`, `evidenceRef`, `citationRef`, and persisted reference-registry mappings from becoming reading-loop context.
- The reading prompt must treat any `paperRef`, `evidenceRef`, or `citationRef` found in user text as legacy/non-reading identifiers and must not call tools with them.
- Reading traces must be distinguishable from legacy traces with `artifactType=PRODUCT_READING_REACT_TURN` and `harnessKind=READING_PHASE1`. Do not write reading turns as plain `PRODUCT_REACT_TURN`.
- Sharing DTOs such as `ProductTurnRequest`, `ProductTurnResult`, and `AnswerEnvelope` is allowed only at the service boundary. Shared DTOs do not permit shared harness routing, shared tool registries, or mixed prompt policy.

## Global Constraints

- Phase 1 exposes exactly `search_paper_candidates` and `find_reading_locations`.
- Do not expose legacy tools in the Phase 1 reading registry: `find_papers`, `retrieve_evidence`, `inspect_reference`, `inspect_page`, `get_paper_metadata`, `resolve_papers`, `answer_without_product_state`.
- Do not implement `read_locations`, `trace_source_quotes`, `PaperSourceQuote`, Source Quote idempotency, or final Source Quote validation in this loop.
- No LLM-visible payload may contain raw `paperId`, `modelVersion`, `chunkRef`, `readingElementId`, matched-field diagnostics, routing diagnostics, rank, or scores.
- Phase 1 tools may support candidate-list answers, navigation answers, clarification answers, and not-enough-source-quotes answers only.
- Phase 1 must not produce `SOURCE_QUOTED_ANSWER`, paper-content summaries, recommendation reasons based on paper content, comparisons, or citations.
- Keep the loop isolated behind `paperloom.react.reading-phase1.enabled=false` by default.
- When the flag is false, the explicit reading entry service must fail closed without calling any harness.
- When the flag is true, the explicit reading entry service must call only `ProductReadingReActHarness`.
- The legacy product chat path must behave the same regardless of the reading flag.
- Phase 1 does not read or update legacy conversation memory.
- Phase 1 does not persist reading answers through the legacy conversation recorder.

---

## Grill Decisions

### 1. What is the smallest useful loop?

Recommended answer adopted:

```text
User question -> ProductReadingConversationService -> ProductReadingReActHarness -> search_paper_candidates -> optional find_reading_locations -> final AnswerEnvelope
```

The final answer may list candidate papers and candidate locations, or say that source-quoted reasons require `read_locations`. It must not claim methods/results/limitations/recommendation reasons from previews.

### 2. Should we wire the new reading registry into the existing ProductReActHarness directly?

Recommended answer adopted:

```text
No. Keep the legacy harness stable and add a separate Phase 1 reading harness.
```

Reason: the existing prompt and answer validation are built around old `paperRef` and `evidenceRef` semantics. Mixing old and new refs before Source Quotes exist is the main failure mode this phase is trying to avoid.

### 3. Should the existing ProductConversationService switch between legacy and reading harnesses?

Recommended answer adopted:

```text
No. The normal product chat service remains legacy-only. Reading Phase 1 has a separate explicit experiment service.
```

Reason: a feature flag inside the existing conversation service makes the two harnesses operationally compatible and easy to mix. This plan requires runtime and constructor-level separation instead.

### 4. Should the feature be enabled by default?

Recommended answer adopted:

```text
No. Ship the explicit reading entry point behind a disabled-by-default flag.
```

Reason: the loop is useful for manual product validation, but it is not yet a full paper-content QA path.

### 5. Should this loop add `read_locations` now to make answers feel complete?

Recommended answer adopted:

```text
No. The minimal closed loop stops at candidate and navigation answers.
```

Reason: adding Source Quote storage expands scope to persistence, idempotency, citation authorization, and final-answer validation. That is Phase 2.

### 6. What answer type should candidate-list/navigation answers use?

Recommended answer adopted:

```text
Use PRODUCT_STATE with stateClaims pointing to `search_paper_candidates` and/or `find_reading_locations`.
```

Reason: the current `AnswerType` enum has no dedicated `CANDIDATE_LIST` type, and these answers are product-tool-grounded but not evidence-grounded.

## File Structure

Create:

- `src/main/java/com/yizhaoqi/smartpai/config/ProductReadingReactProperties.java`
  - Holds `paperloom.react.reading-phase1.enabled`.
- `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarness.java`
  - Runs the isolated Phase 1 LLM/tool loop.
- `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingConversationService.java`
  - Explicit reading experiment entry point. It fails closed when the flag is false, calls only `ProductReadingReActHarness` when the flag is true, and passes no legacy history or memory.
- `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingTraceRecorder.java`
  - Records reading-loop traces as `PRODUCT_READING_REACT_TURN` with `harnessKind=READING_PHASE1`.
- `src/test/java/com/yizhaoqi/smartpai/config/ProductReadingReactPropertiesTest.java`
  - Covers disabled default.
- `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarnessTest.java`
  - Covers tool-call loop, final answer policy, exact tool surface, forbidden dependencies, and trace-safe output.
- `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingConversationServiceTest.java`
  - Covers fail-closed disabled behavior, enabled reading path, empty history/memory, and legacy service isolation.
- `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingTraceRecorderTest.java`
  - Covers distinct reading trace artifact type and harness marker.

Modify:

- `src/main/resources/application.yml`
  - Adds the disabled-by-default flag.

Do not modify:

- `ProductConversationService`
- `ProductToolRegistry`
- `ProductReadingToolRegistry`
- `ProductReadingToolAdapter`
- `ProductReActHarness`
- `ProductTraceRecorder`
- `ChatHandler`

## Task 1: Add Disabled-By-Default Reading React Flag

**Files:**
- Create: `src/main/java/com/yizhaoqi/smartpai/config/ProductReadingReactProperties.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/config/ProductReadingReactPropertiesTest.java`
- Modify: `src/main/resources/application.yml`

**Interfaces:**
- Produces: `ProductReadingReactProperties#isEnabled(): boolean`
- Consumes later: `ProductReadingConversationService`

- [ ] **Step 1: Write the failing disabled-default test**

Create `ProductReadingReactPropertiesTest.java`:

```java
package com.yizhaoqi.smartpai.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ProductReadingReactPropertiesTest {

    @Test
    void readingPhaseOneFlagDefaultsDisabled() {
        assertFalse(new ProductReadingReactProperties().isEnabled());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
mvn -q -Dtest=ProductReadingReactPropertiesTest test
```

Expected: compilation fails because `ProductReadingReactProperties` does not exist yet.

- [ ] **Step 3: Add properties class**

Create `ProductReadingReactProperties.java`:

```java
package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "paperloom.react.reading-phase1")
public class ProductReadingReactProperties {
    private boolean enabled = false;
}
```

- [ ] **Step 4: Add application config**

Add to `src/main/resources/application.yml`:

```yaml
paperloom:
  react:
    reading-phase1:
      enabled: ${PAPERLOOM_REACT_READING_PHASE1_ENABLED:false}
```

If `paperloom:` already exists in the file, merge these keys into the existing tree instead of creating a duplicate top-level key.

- [ ] **Step 5: Run test to verify it passes**

Run:

```bash
mvn -q -Dtest=ProductReadingReactPropertiesTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yizhaoqi/smartpai/config/ProductReadingReactProperties.java \
  src/test/java/com/yizhaoqi/smartpai/config/ProductReadingReactPropertiesTest.java \
  src/main/resources/application.yml
git commit -m "feat: add reading react phase one flag"
```

## Task 2: Add Separate Reading Trace Recorder

**Files:**
- Create: `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingTraceRecorder.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingTraceRecorderTest.java`

**Interfaces:**
- Consumes: `ProductTraceSink#submit(ProductTracePayload)`
- Produces: `ProductReadingTraceRecorder#recordReadingTurn(...)`

- [ ] **Step 1: Write the failing distinct-trace test**

Create `ProductReadingTraceRecorderTest.java`:

```java
package com.yizhaoqi.smartpai.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductReadingTraceRecorderTest {

    @Test
    void recordsReadingTurnWithDistinctArtifactAndHarnessKind() {
        List<ProductTracePayload> submitted = new ArrayList<>();
        ProductReadingTraceRecorder recorder = new ProductReadingTraceRecorder(submitted::add);
        ProductTurnRequest request = new ProductTurnRequest(
                7L,
                "conversation-1",
                "generation-1",
                "推荐 Agentic eval 相关论文",
                SourceScope.auto(),
                List.of(),
                Map.of(),
                ProductModelContext.defaults()
        );
        ProductTurnResult result = new ProductTurnResult(
                "x",
                new AnswerEnvelope(
                        AnswerType.CLARIFICATION_NEEDED,
                        "x",
                        List.of(),
                        List.of(),
                        List.of("x"),
                        List.of(),
                        List.of(),
                        ProductStopReason.ANSWER_SCHEMA_INVALID.name()
                ),
                List.of(),
                List.of(),
                ProductStopReason.ANSWER_SCHEMA_INVALID,
                ProductResultStatus.FAILED
        );

        recorder.recordReadingTurn(request, result, List.of(), List.of(), Instant.EPOCH, Instant.EPOCH);

        assertEquals(1, submitted.size());
        ProductTracePayload payload = submitted.get(0);
        assertEquals("PRODUCT_READING_REACT_TURN", payload.artifactType());
        assertEquals("PRODUCT_READING_REACT_TURN", payload.traceJson().get("artifactType"));
        assertEquals("READING_PHASE1", payload.traceJson().get("harnessKind"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
mvn -q -Dtest=ProductReadingTraceRecorderTest test
```

Expected: compilation fails because `ProductReadingTraceRecorder` does not exist yet.

- [ ] **Step 3: Implement separate reading trace recorder**

Create `ProductReadingTraceRecorder.java` as a small service that accepts `ProductTraceSink` in its constructor and submits a `ProductTracePayload` with:

```text
artifactType = PRODUCT_READING_REACT_TURN
harnessKind  = READING_PHASE1
traceVersion = 1
filename     = reading-turn-<safe generationId>.json
```

The trace JSON must include request ids, scope snapshot, input message, LLM calls, tool calls, answer envelope, references, stop reason, result status, and errors. It must not reuse `ProductTraceRecorder.record(...)`, because that method emits the legacy `PRODUCT_REACT_TURN` artifact.

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
mvn -q -Dtest=ProductReadingTraceRecorderTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yizhaoqi/smartpai/service/ProductReadingTraceRecorder.java \
  src/test/java/com/yizhaoqi/smartpai/service/ProductReadingTraceRecorderTest.java
git commit -m "feat: add reading react trace recorder"
```

## Task 3: Implement ProductReadingReActHarness Loop

**Files:**
- Create: `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarness.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarnessTest.java`

**Interfaces:**
- Consumes: `ProductReadingToolRegistry#listTools()`
- Consumes: `ProductReadingToolRegistry#execute(String, Map<String,Object>, ProductToolContext)`
- Consumes: `ProductReadingTraceRecorder#recordReadingTurn(...)`
- Produces: `ProductReadingReActHarness#run(ProductTurnRequest): ProductTurnResult`

- [ ] **Step 1: Write the failing forbidden-dependency test**

Add this reflection helper and test to `ProductReadingReActHarnessTest`:

```java
@Test
void readingHarnessHasNoLegacyHarnessOrToolRegistryDependency() {
    assertNoFieldOrConstructorDependency(ProductReadingReActHarness.class, ProductReActHarness.class);
    assertNoFieldOrConstructorDependency(ProductReadingReActHarness.class, ProductToolRegistry.class);
}

private void assertNoFieldOrConstructorDependency(Class<?> owner, Class<?> forbiddenType) {
    for (java.lang.reflect.Field field : owner.getDeclaredFields()) {
        assertNotEquals(forbiddenType, field.getType(), owner.getSimpleName() + " field must not use " + forbiddenType.getSimpleName());
    }
    for (java.lang.reflect.Constructor<?> constructor : owner.getDeclaredConstructors()) {
        for (Class<?> parameterType : constructor.getParameterTypes()) {
            assertNotEquals(forbiddenType, parameterType, owner.getSimpleName() + " constructor must not use " + forbiddenType.getSimpleName());
        }
    }
}
```

- [ ] **Step 2: Write the failing exact-tool-surface test**

Add a test that stubs `ProductReadingToolRegistry#listTools()` with exactly these names:

```text
search_paper_candidates
find_reading_locations
```

The test must verify `LlmProviderRouter.completeReActTurn(...)` receives exactly those two tools and does not receive:

```text
find_papers
retrieve_evidence
inspect_reference
inspect_page
get_paper_metadata
resolve_papers
answer_without_product_state
```

- [ ] **Step 3: Write the failing candidate-list loop test**

Add a test that:

- stubs the first LLM turn to call `search_paper_candidates`;
- stubs `ProductReadingToolRegistry#execute(...)` to return one candidate with a `paperHandle`;
- stubs the second LLM turn to return a JSON `AnswerEnvelope` with `answerType=PRODUCT_STATE`;
- asserts the final result is `ProductResultStatus.COMPLETED`;
- asserts `references()` is empty;
- verifies `ProductReadingTraceRecorder#recordReadingTurn(...)` is called.

- [ ] **Step 4: Run tests to verify they fail**

Run:

```bash
mvn -q -Dtest=ProductReadingReActHarnessTest test
```

Expected: compilation fails because `ProductReadingReActHarness` does not exist yet.

- [ ] **Step 5: Implement isolated harness**

Create `ProductReadingReActHarness.java` with a public constructor that accepts only these dependencies, in this order:

```text
LlmProviderRouter llmProviderRouter
ProductReadingToolRegistry toolRegistry
ObjectMapper objectMapper
ProductReadingTraceRecorder traceRecorder
```

Required behavior:

- get tools from `ProductReadingToolRegistry#listTools()`;
- reject startup if the listed tool names are not exactly `search_paper_candidates` and `find_reading_locations`;
- build a reading-only system prompt that names only those two tools;
- call `LlmProviderRouter.completeReActTurn(...)` with only the reading tools;
- execute tool calls through `ProductReadingToolRegistry#execute(...)`;
- append tool results as navigation-only context;
- require at least one successful reading-tool call before accepting a final answer;
- parse the final answer as `AnswerEnvelope`;
- return no references and no citations;
- call `ProductReadingTraceRecorder#recordReadingTurn(...)` for all terminal outcomes.

The system prompt must state:

```text
Paper previews are navigation only, not Source Quotes.
Reading-location previews are navigation only, not Source Quotes.
locationRef values are navigation refs, not Source Quotes.
Phase 1 can answer candidate-list, navigation, clarification, and not-enough-source-quotes answers.
Phase 1 cannot answer paper-content methods, results, limitations, comparisons, recommendation reasons, citations, or source-quoted answers.
If the user asks for paper-content reasons, answer INSUFFICIENT_EVIDENCE and say read_locations / Source Quotes are required.
EVIDENCE_ANSWER is forbidden in Phase 1.
paperRef, evidenceRef, and citationRef are legacy identifiers for the old harness. Do not use them as reading tool arguments.
```

- [ ] **Step 6: Run tests to verify they pass**

Run:

```bash
mvn -q -Dtest=ProductReadingReActHarnessTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarness.java \
  src/test/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarnessTest.java
git commit -m "feat: add isolated reading react minimal loop"
```

## Task 4: Enforce Phase 1 Final Answer Boundary

**Files:**
- Modify: `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarnessTest.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarness.java`

**Interfaces:**
- Consumes: `ProductReadingReActHarness#run(ProductTurnRequest)`
- Produces: rejection of `EVIDENCE_ANSWER` and non-empty `evidenceBasedClaims`

- [ ] **Step 1: Write the failing evidence-answer rejection test**

Add a test that:

- performs one successful `search_paper_candidates` call;
- returns a final JSON envelope with `answerType=EVIDENCE_ANSWER`;
- includes one non-empty `evidenceBasedClaims` entry;
- asserts `ProductResultStatus.FAILED`;
- asserts `ProductStopReason.ANSWER_SCHEMA_INVALID`;
- asserts the failure message says Phase 1 has no Source Quotes;
- asserts `references()` is empty.

- [ ] **Step 2: Write the failing non-empty evidence-claims rejection test**

Add a test that:

- performs one successful `search_paper_candidates` call;
- returns a final JSON envelope with `answerType=PRODUCT_STATE`;
- includes one non-empty `evidenceBasedClaims` entry;
- asserts the same failure outcome as the evidence-answer test.

- [ ] **Step 3: Run tests to verify they fail if boundary is missing**

Run:

```bash
mvn -q -Dtest=ProductReadingReActHarnessTest test
```

Expected: FAIL if the harness accepts evidence answers or evidence claims.

- [ ] **Step 4: Tighten validation**

Ensure `ProductReadingReActHarness` accepts final envelopes only when:

```java
private boolean validPhaseOneEnvelope(AnswerEnvelope envelope) {
    if (envelope == null) {
        return false;
    }
    if (envelope.answerType() == AnswerType.EVIDENCE_ANSWER) {
        return false;
    }
    return envelope.evidenceBasedClaims().isEmpty();
}
```

- [ ] **Step 5: Run boundary tests**

Run:

```bash
mvn -q -Dtest=ProductReadingReActHarnessTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarness.java \
  src/test/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarnessTest.java
git commit -m "test: enforce reading react phase one answer boundary"
```

## Task 5: Add Separate Reading Experiment Entry Service

**Files:**
- Create: `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingConversationService.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingConversationServiceTest.java`

**Interfaces:**
- Consumes: `ProductReadingReactProperties#isEnabled()`
- Consumes: `ProductReadingReActHarness#run(ProductTurnRequest)`
- Produces: explicit reading experiment service without legacy harness compatibility

- [ ] **Step 1: Write the failing legacy-service-isolation test**

Create `ProductReadingConversationServiceTest.java` and add:

```java
@Test
void legacyProductConversationServiceDoesNotAcceptReadingDependencies() {
    assertNoFieldOrConstructorDependency(ProductConversationService.class, ProductReadingReActHarness.class);
    assertNoFieldOrConstructorDependency(ProductConversationService.class, ProductReadingReactProperties.class);
    assertNoFieldOrConstructorDependency(ProductConversationService.class, ProductReadingConversationService.class);
}
```

Reuse the reflection helper from the reading harness test.

- [ ] **Step 2: Write the failing reading-service-dependency test**

Add a test that asserts `ProductReadingConversationService` has no field or constructor dependency on:

```text
ProductConversationService
ProductReActHarness
ProductToolRegistry
ConversationService
ProductMemoryService
```

- [ ] **Step 3: Write the failing disabled-service test**

Add a test that:

- constructs `ProductReadingConversationService` with `ProductReadingReactProperties#setEnabled(false)`;
- calls `runTurn(...)`;
- verifies `ProductReadingReActHarness#run(...)` is never called;
- asserts the result is `ProductResultStatus.FAILED`;
- asserts the stop reason is `ProductStopReason.ANSWER_SCHEMA_INVALID`;
- asserts the answer explains that Reading Phase 1 is disabled.

- [ ] **Step 4: Write the failing enabled-service test**

Add a test that:

- constructs `ProductReadingConversationService` with `ProductReadingReactProperties#setEnabled(true)`;
- stubs `ProductReadingReActHarness#run(...)` to return a `PRODUCT_STATE` result;
- calls `runTurn(...)`;
- verifies `ProductReadingReActHarness#run(...)` is called once;
- captures the `ProductTurnRequest` passed to the harness;
- asserts `request.history()` is empty;
- asserts `request.memory()` is empty;
- asserts the returned result is exactly the harness result.

This test must not create, mock, inject, or verify `ProductReActHarness`, `ProductToolRegistry`, `ConversationService`, or `ProductMemoryService`; those dependencies must be absent from the reading entry service.

- [ ] **Step 5: Run tests to verify they fail**

Run:

```bash
mvn -q -Dtest=ProductReadingConversationServiceTest test
```

Expected: compilation fails because `ProductReadingConversationService` does not exist yet.

- [ ] **Step 6: Implement `ProductReadingConversationService`**

Create a Spring service with a public constructor that accepts only these dependencies, in this order:

```text
ProductReadingReActHarness readingHarness
ProductReadingReactProperties properties
```

Required behavior:

- if `properties.isEnabled()` is false, return a failed `ProductTurnResult` without calling any harness;
- build a `ProductTurnRequest` with the same public inputs as the normal product turn path;
- set `history` to `List.of()`;
- set `memory` to `Map.of()`;
- call only `ProductReadingReActHarness#run(...)` when enabled;
- do not load or update `ProductMemoryService`;
- do not load `ConversationService` history;
- do not persist the answer in this phase unless a caller explicitly adds a reading-specific product route in a later plan;
- do not call or delegate to `ProductConversationService`.

- [ ] **Step 7: Run tests to verify they pass**

Run:

```bash
mvn -q -Dtest=ProductReadingConversationServiceTest test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/yizhaoqi/smartpai/service/ProductReadingConversationService.java \
  src/test/java/com/yizhaoqi/smartpai/service/ProductReadingConversationServiceTest.java
git commit -m "feat: add separate reading react entry service"
```

## Task 6: Focused End-To-End Verification

**Files:**
- No production changes expected.
- Test: focused Maven and grep commands.

**Interfaces:**
- Consumes: all previous tasks
- Produces: verified minimal closed loop and verified legacy incompatibility

- [ ] **Step 1: Run reading tool registry and adapter tests**

Run:

```bash
mvn -q -Dtest=ProductReadingToolRegistryTest,ProductReadingToolAdapterTest,ProductPaperHandleServiceTest,ReadingToolArgumentValidatorTest,ReadingToolOutputMapperTest test
```

Expected: PASS.

- [ ] **Step 2: Run reading harness, reading trace, and reading entry tests**

Run:

```bash
mvn -q -Dtest=ProductReadingReactPropertiesTest,ProductReadingTraceRecorderTest,ProductReadingReActHarnessTest,ProductReadingConversationServiceTest test
```

Expected: PASS.

- [ ] **Step 3: Run legacy product conversation tests**

Run:

```bash
mvn -q -Dtest=ProductConversationServiceTest,ProductReActHarnessTest,ProductToolRegistryTest test
```

Expected: PASS, with no constructor or routing changes required for `ProductConversationService`.

- [ ] **Step 4: Verify source-code isolation by search**

Run:

```bash
rg -n "ProductReadingReActHarness|ProductReadingReactProperties|ProductReadingConversationService" src/main/java/com/yizhaoqi/smartpai/service/ProductConversationService.java
```

Expected: no output and exit code `1`.

Run:

```bash
rg -n "ProductReActHarness|ProductToolRegistry|ProductConversationService|ProductMemoryService|\\bConversationService\\b" src/main/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarness.java src/main/java/com/yizhaoqi/smartpai/service/ProductReadingConversationService.java
```

Expected: no output and exit code `1`.

Run:

```bash
rg -n "find_papers|retrieve_evidence|inspect_reference|inspect_page|get_paper_metadata|resolve_papers|answer_without_product_state" src/main/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarness.java src/main/java/com/yizhaoqi/smartpai/service/ProductReadingToolRegistry.java
```

Expected: no output and exit code `1`.

- [ ] **Step 5: Run compile gate**

Run:

```bash
mvn -q -DskipTests test
```

Expected: exit code `0`.

- [ ] **Step 6: Run full test gate**

Run:

```bash
mvn -q test
```

Expected: exit code `0`. Existing noisy logs and H2 `paperloom_eval` schema warnings are acceptable only if Maven exits `0`.

- [ ] **Step 7: Check diff hygiene**

Run:

```bash
git diff --check
```

Expected: no output and exit code `0`.

- [ ] **Step 8: Commit verification-only changes if any**

If no files changed during verification, do not create a commit. If a small test cleanup was needed:

```bash
git add <changed-files>
git commit -m "test: verify isolated reading react minimal loop"
```

## Acceptance Criteria

- Existing `ProductConversationService` remains hardwired to `ProductReActHarness`.
- Existing `ProductConversationService` has no field or constructor dependency on `ProductReadingReActHarness`, `ProductReadingReactProperties`, or `ProductReadingConversationService`.
- Existing `ProductConversationService` is not modified by this plan.
- `paperloom.react.reading-phase1.enabled=false` leaves the normal product chat path unchanged and makes `ProductReadingConversationService` fail closed.
- `paperloom.react.reading-phase1.enabled=true` enables only the explicit `ProductReadingConversationService` entry point.
- `ProductReadingConversationService` calls only `ProductReadingReActHarness`; it never calls `ProductConversationService`, `ProductReActHarness`, or `ProductToolRegistry`.
- `ProductReadingConversationService` has no dependency on `ConversationService` or `ProductMemoryService` in Phase 1.
- `ProductReadingConversationService` passes empty history and empty memory into the reading harness.
- `ProductReadingReActHarness` has no dependency on `ProductReActHarness` or `ProductToolRegistry`.
- `ProductReadingReActHarness` lists exactly `search_paper_candidates` and `find_reading_locations`.
- The reading prompt and reading tool list contain no legacy tool names.
- Reading traces use `artifactType=PRODUCT_READING_REACT_TURN` and `harnessKind=READING_PHASE1`.
- Legacy traces continue using their existing artifact type; reading traces are never emitted as plain `PRODUCT_REACT_TURN`.
- The reading harness can complete a candidate-list answer after `search_paper_candidates`.
- The reading harness can complete a navigation answer after `find_reading_locations`.
- The reading harness rejects `EVIDENCE_ANSWER` and non-empty `evidenceBasedClaims`.
- Final answers from this loop have no references and no citations.
- No Phase 1 output exposes raw `paperId`, `modelVersion`, `chunkRef`, `readingElementId`, matched-field diagnostics, routing diagnostics, rank, or score.
- No `read_locations`, `trace_source_quotes`, `PaperSourceQuote`, Source Quote registry, or citation rendering is implemented in this plan.

## Self-Review

- Spec coverage: This plan implements isolated runtime wiring for the two already-built Phase 1 tools, preserves the Source Quote boundary, and leaves Phase 2 out of scope.
- Incompatibility coverage: The plan forbids shared harness routing, shared tool registries, legacy-service flag switching, and legacy trace artifacts for reading turns.
- Placeholder scan: Every task has concrete file paths, commands, and expected outcomes; there are no unresolved fill-in markers or open-ended test instructions.
- Type consistency: The central new type is `ProductReadingReActHarness#run(ProductTurnRequest): ProductTurnResult`; the explicit entry type is `ProductReadingConversationService#runTurn(...)`.
