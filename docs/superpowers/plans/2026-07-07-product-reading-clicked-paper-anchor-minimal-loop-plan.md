# Product Reading Clicked Paper Anchor Minimal Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Add the next smallest Product Reading caller loop: a structured clicked paper anchor can select a READY paper for outline, deterministic location listing, and in-paper location search without using ordinals, raw ids in the LLM prompt, or ambiguous identity matches.

**Architecture:** Keep the isolated Product Reading path and reuse the existing chat `referenceFocus` transport. `ChatHandler` converts explicit clicked paper focus into `clickedPaperHandles` reading-turn anchors, `ProductReadingConversationService` forwards those anchors, and `ProductReadingReActHarness` authorizes only those handles for navigation/search tools while adapters still enforce user permission, locked `SourceScope`, and READY status. This slice does not render new Product Reading paper cards; it wires the caller anchor contract that future paper-row UI can use.

**Tech Stack:** Java 17, Spring Boot, Jackson, JUnit 5, Mockito, Vue 3, TypeScript, Maven, pnpm.

## Global Constraints

- Keep the legacy Product path unchanged when `paperloom.react.reading-phase1.enabled=false`.
- Keep the Product Reading entry point behind `paperloom.react.reading-phase1.enabled=true`; do not promote the flag.
- Do not modify `ProductConversationService`, `ProductReActHarness`, or `ProductToolRegistry`.
- Do not add new LLM-facing tools; the Product Reading tool surface remains exactly nine tools.
- Do not accept ordinals, display row numbers, typed `[1]`, typed paper titles, or user-message text as clicked paper authorization.
- Do not expose raw `paperId`, SQL ids, `modelVersion`, `chunkRef`, Source Quote text, paper content, scores, ranks, or abstracts to the LLM through the anchor prompt.
- Clicked paper anchors are navigation/product-state anchors only. They are not Source Quotes and cannot support paper-content claims.
- A clicked paper anchor may authorize `get_paper_outline`, `list_paper_locations`, and `find_reading_locations`; it must not authorize `read_locations` without returned `locationRef` / `sectionRef`.
- Existing `clickedSourceQuoteRefs` and `trace_source_quotes` behavior remains unchanged.
- No new database schema, no clicked-row persistence table, no product-state card renderer, no UI pagination, and no promotion of the reading experiment flag.

---

## Current Evidence

- `find_papers_by_identity` now completes the planned nine-tool Product Reading catalog and explicitly defers clicked paper-row anchors as the next slice.
- `ProductReadingReActHarness` already keeps same-turn authorization state in `semanticPaperHandles` and `deterministicLocationPaperHandles`.
- `ProductReadingToolRegistry` descriptions already mention `paperHandles` returned by tools or clicked paper rows, but there is no caller path that supplies clicked paper handles.
- `ProductReadingConversationService` already sanitizes and forwards `clickedSourceQuoteRefs` through `readingTurnAnchors`; it is the natural seam for `clickedPaperHandles`.
- `ChatWebSocketHandler` already parses structured JSON `referenceFocus`, and `InputBox` already sends `referenceFocus` with chat messages.
- `ProductReferenceFocus` currently carries paper ids/titles and `sourceQuoteRef`, but not `paperHandle`.
- `ChatHandler` currently treats `sourceQuoteRef` as structured focus for the reading path and builds `clickedSourceQuoteRefs`; it does not build clicked paper anchors.

## Grill Outcomes

The user requested recommended answers when a decision needs an answer, so the recommended answers below are adopted.

### 1. What is the next project step?

Recommended answer: clicked paper anchors for Product Reading.

Adopted. After identity resolution, the remaining gap is not another tool; it is a caller anchor that lets a user resolve a selected paper explicitly without ordinals or hidden ambiguous-handle reuse.

### 2. What is the smallest closed loop?

Recommended answer: backend/caller anchor plumbing through the existing WebSocket `referenceFocus`.

Adopted. The loop is:

```text
frontend or caller sends structured referenceFocus.paperHandle (preferred)
or structured referenceFocus.paperId from an existing product row
-> ChatHandler validates/converts it behind the reading flag
-> effectiveScope.clickedPaperHandles
-> ProductReadingConversationService readingTurnAnchors.clickedPaperHandles
-> ProductReadingReActHarness seeds same-turn paper-handle authorization
-> LLM can call get_paper_outline, list_paper_locations, or find_reading_locations
-> paper-content claims still require read_locations
```

Do not build a new endpoint or product-state paper-card renderer in this slice.

### 3. Should clicked paper anchors use `paperHandle` or `paperId`?

Recommended answer: prefer `paperHandle`; support backend conversion from structured `paperId` only as a caller bridge.

Adopted. New Product Reading UI rows should send `referenceFocus.paperHandle`. Existing product UI rows that only know `paperId` may send `referenceFocus.paperId`; `ChatHandler` may convert it with `ProductPaperHandleService#handleForPaperId` after existing source-scope checks. Raw `paperId` must not be forwarded to the reading harness or LLM prompt.

### 4. Should ambiguous identity output handles automatically become clicked paper anchors?

Recommended answer: no.

Adopted. Ambiguous identity results remain non-authorizing product-state choices. A clicked paper anchor must arrive from explicit caller state in a later user action or a concrete UI row, not from model memory of an ambiguous result.

### 5. Should a clicked paper anchor authorize semantic in-paper search?

Recommended answer: yes.

Adopted. A clicked paper row is an explicit paper selection, equivalent in authorization strength to an unambiguous identity match. It may seed both deterministic and semantic paper-handle sets.

### 6. Should a clicked paper anchor authorize direct reading?

Recommended answer: no.

Adopted. It may authorize navigation/search tools that return `locationRef` / `sectionRef`. `read_locations` still requires explicit current-turn location refs returned by outline, list, or search.

### 7. Should clicked paper anchors affect `trace_source_quotes`?

Recommended answer: no.

Adopted. Source Quote trace remains controlled only by explicit `clickedSourceQuoteRefs`. Paper anchors and Source Quote anchors are separate channels.

### 8. Should the normal Product chat path consume `paperHandle`?

Recommended answer: no.

Adopted. With the reading flag disabled, existing Product chat behavior remains unchanged. `paperHandle` is a Product Reading handle, not a legacy Product ReAct argument.

### 9. Should the plan add a new Product Reading UI card renderer now?

Recommended answer: no.

Adopted. That would mix anchor plumbing with product-state rendering. This slice only makes the anchor path available and testable.

### 10. Should `ProductReadingConversationService` depend on chat history or `ConversationService`?

Recommended answer: no.

Adopted. Keep it as the isolated reading entry point. `ChatHandler` remains the caller layer that owns effective scope, generation state, reference focus, persistence, and streaming.

### 11. Should clicked paper anchors be stored across turns automatically?

Recommended answer: no.

Adopted. The anchor is a current-turn explicit input. Future UI may resend it when the user clicks a row again; this slice does not create hidden paper selection memory.

### 12. Should source-scope and permission validation move into the harness?

Recommended answer: no.

Adopted. The harness only prevents hidden handle use before tool calls. The adapters and `ProductPaperHandleService` still enforce visibility, locked scope, and Current Reading Model READY status.

## Anchor Contract

Inbound WebSocket payload, preferred for new Product Reading paper rows:

```json
{
  "type": "user_message",
  "conversationId": "conversation-1",
  "message": "看这篇论文的方法部分",
  "referenceFocus": {
    "paperHandle": "paper_handle_abc",
    "paperTitle": "LoRA: Low-Rank Adaptation of Large Language Models"
  }
}
```

Inbound WebSocket payload, bridge for existing product rows that only carry product paper id:

```json
{
  "type": "user_message",
  "conversationId": "conversation-1",
  "message": "看这篇论文的方法部分",
  "referenceFocus": {
    "paperId": "raw-product-paper-id",
    "paperTitle": "LoRA: Low-Rank Adaptation of Large Language Models"
  }
}
```

Reading effective scope passed by `ChatHandler`:

```json
{
  "clickedPaperHandles": ["paper_handle_abc"],
  "clickedSourceQuoteRefs": ["source_quote_abc"]
}
```

Reading harness memory:

```json
{
  "readingTurnAnchors": {
    "clickedPaperHandles": ["paper_handle_abc"],
    "clickedSourceQuoteRefs": ["source_quote_abc"]
  }
}
```

Prompt policy:

```text
Explicit clicked paper anchors for this turn:
["paper_handle_abc"]
Clicked paper anchors are navigation only, not Source Quotes.
Use clicked paper handles only with get_paper_outline, list_paper_locations, or find_reading_locations.
```

## Task 1: Reference Focus Contract

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ProductReferenceFocus.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/handler/ChatWebSocketHandler.java`
- Modify: `frontend/src/typings/api.d.ts`
- Modify: `src/test/java/com/yizhaoqi/smartpai/handler/ChatWebSocketHandlerTest.java`

**Interfaces:**
- Produces: `ProductReferenceFocus#paperHandle()` and `ProductReferenceFocus#paperHandles()`.
- Produces: inbound JSON `referenceFocus.paperHandle` and `referenceFocus.paperHandles`.
- Preserves: existing `sourceQuoteRef`, `paperId`, `paperIds`, `referenceNumber`, and legacy scope parsing.

- [x] Add `paperHandles` and `paperHandle` fields to `ProductReferenceFocus`.
- [x] Sanitize paper handles with pattern `^paper_handle_[A-Za-z0-9_-]+$`.
- [x] Normalize a single `paperHandle` into `paperHandles` the same way `paperId` normalizes into `paperIds`.
- [x] Keep constructors source-compatible by adding an overload for the existing field list.
- [x] Parse `paperHandle` and `paperHandles` in `ChatWebSocketHandler#parseReferenceFocus`.
- [x] Extend `frontend/src/typings/api.d.ts` `Api.Chat.Scope` with optional `paperHandle?: string` and `paperHandles?: string[]`.
- [x] Add a `ChatWebSocketHandlerTest` case that parses `referenceFocus.paperHandle`.
- [x] Add a `ProductReferenceFocus` unit-style assertion through existing tests that invalid paper handles are ignored.

Verification:

```bash
mvn -q -Dtest=ChatWebSocketHandlerTest test
```

Expected: PASS.

## Task 2: Chat Caller Anchor Mapping

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/service/ChatHandlerProductHarnessTest.java`

**Interfaces:**
- Consumes: `ProductReferenceFocus#paperHandle()`, `#paperHandles()`, `#paperId()`, and `#paperIds()`.
- Consumes: `ProductPaperHandleService#handleForPaperId(String)`.
- Produces: `effectiveScope.clickedPaperHandles`.

- [x] Inject `ProductPaperHandleService` into `ChatHandler`.
- [x] Treat `paperHandle`, `paperHandles`, `paperId`, and `paperIds` as structured focus only when `paperloom.react.reading-phase1.enabled=true`.
- [x] Preserve legacy behavior when the reading flag is disabled; do not route legacy Product chat through paper handles.
- [x] In `structuredReferenceFocus`, preserve `paperHandle` and `paperHandles`.
- [x] Build clicked paper handles from valid `referenceFocus.paperHandles`.
- [x] If no `paperHandle` exists and the reading flag is enabled, convert structured `paperId` / `paperIds` to handles with `ProductPaperHandleService#handleForPaperId`.
- [x] Run existing `ConversationScopeService#assertReferenceFocusWithinScope` before converting raw product paper ids.
- [x] Never add raw `paperId` to `effectiveScope.clickedPaperHandles`.
- [x] Do not derive clicked paper handles from user message text, display citations, ordinals, paper titles, or previous tool output.
- [x] Add `ChatHandlerProductHarnessTest` coverage:
  - flag false plus `referenceFocus.paperHandle` uses legacy `ProductConversationService` path unchanged.
  - flag true plus `referenceFocus.paperHandle` passes `clickedPaperHandles` to `ProductReadingConversationService`.
  - flag true plus `referenceFocus.paperId` converts to `clickedPaperHandles`.
  - typed paper handle in `message` without structured focus does not pass `clickedPaperHandles`.
  - out-of-scope `paperId` is still rejected by source-scope validation.

Verification:

```bash
mvn -q -Dtest=ChatHandlerProductHarnessTest test
```

Expected: PASS.

## Task 3: Reading Conversation Anchor Forwarding

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingConversationService.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingConversationServiceTest.java`

**Interfaces:**
- Consumes: `effectiveScope.clickedPaperHandles`.
- Produces: `ProductTurnRequest.memory().readingTurnAnchors.clickedPaperHandles`.
- Preserves: `clickedSourceQuoteRefs` behavior and cap.

- [x] Add `MAX_CLICKED_PAPER_HANDLES = 20`.
- [x] Add a `PAPER_HANDLE_PATTERN` for `^paper_handle_[A-Za-z0-9_-]+$`.
- [x] Add `clickedPaperHandles(Map<String,Object>)`, mirroring `clickedSourceQuoteRefs(...)`.
- [x] Accept lists and arrays; ignore unsupported scalar/map values.
- [x] De-duplicate while preserving order.
- [x] Cap to 20 handles.
- [x] Merge `clickedPaperHandles` and `clickedSourceQuoteRefs` under one `readingTurnAnchors` object when either exists.
- [x] Add tests:
  - valid clicked paper handles reach `ProductTurnRequest.memory()`.
  - invalid handles are ignored.
  - arrays and lists work.
  - cap is 20.
  - clicked Source Quote behavior remains unchanged when paper handles are absent.
  - both anchor types can be present together.

Verification:

```bash
mvn -q -Dtest=ProductReadingConversationServiceTest test
```

Expected: PASS.

## Task 4: Harness Authorization From Clicked Paper Anchors

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarness.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarnessTest.java`

**Interfaces:**
- Consumes: `readingTurnAnchors.clickedPaperHandles`.
- Produces: seeded entries in `semanticPaperHandles` and `deterministicLocationPaperHandles`.
- Preserves: hidden-handle rejection for handles not disclosed by tools or clicked anchors.

- [x] Add `clickedPaperHandles` to `ReadingTurnState`.
- [x] Sanitize clicked paper handles in the harness using the same `paper_handle_...` pattern.
- [x] Seed both `semanticPaperHandles` and `deterministicLocationPaperHandles` from clicked paper handles.
- [x] Add clicked paper handles to the system prompt as explicit current-turn anchors.
- [x] State in the prompt that clicked paper anchors are navigation only and not Source Quotes.
- [x] Keep `read_locations` validation unchanged; clicked paper anchors must not disclose location refs.
- [x] Add tests:
  - clicked paper handle can feed `get_paper_outline`.
  - clicked paper handle can feed `list_paper_locations`.
  - clicked paper handle can feed `find_reading_locations`.
  - clicked paper handle cannot feed `read_locations` without returned refs.
  - hidden paper handles remain rejected when not in clicked anchors and not returned by tools.
  - invalid clicked paper handles are ignored.
  - clicked Source Quote trace behavior remains unchanged.

Verification:

```bash
mvn -q -Dtest=ProductReadingReActHarnessTest test
```

Expected: PASS.

## Task 5: Minimal Frontend Caller Payload

**Files:**
- Modify: `frontend/src/views/chat/modules/input-box.vue`
- Modify: `frontend/src/views/chat/modules/source-evidence-panel.vue`
- Modify if needed: `frontend/src/views/chat/modules/chat-message.vue`

**Interfaces:**
- Consumes: `Api.Chat.Scope.paperHandle`, `paperHandles`, `paperId`, `paperIds`.
- Produces: outbound WebSocket `referenceFocus` preserving paper handles and paper ids.
- Preserves: existing Source Quote click behavior and PDF/evidence panel behavior.

- [x] Keep `input-box.vue#outgoingReferenceFocus` as a structural copy so `paperHandle` and `paperHandles` are sent.
- [x] Update `referenceFocusLabel` to prefer `paperTitle`, then `originalFilename`, then `"Selected paper"` when `paperHandle` or `paperId` is present.
- [x] In `source-evidence-panel.vue`, allow `Ask about this` when either `paperId` or `sourceQuoteRef` is present; do not require matched text for a paper anchor follow-up.
- [x] Preserve existing `sourceQuoteRef` behavior for Source Quote chips.
- [x] Do not create a new paper-card renderer in this slice.

Verification:

```bash
cd frontend && pnpm typecheck
```

Expected: PASS.

## Task 6: Verification And Isolation Gates

- [x] Run focused backend tests:

```bash
mvn -q -Dtest=ChatWebSocketHandlerTest,ChatHandlerProductHarnessTest,ProductReadingConversationServiceTest,ProductReadingReActHarnessTest test
```

- [x] Run Product Reading regression tests:

```bash
mvn -q -Dtest=ReadingToolArgumentValidatorTest,ProductReadingToolRegistryTest,ProductReadingToolAdapterTest,ProductReadingReActHarnessTest test
```

- [x] Run legacy Product isolation tests:

```bash
mvn -q -Dtest=ProductConversationServiceTest,ProductReActHarnessTest,ProductToolRegistryTest test
```

- [x] Run compile gate:

```bash
mvn -q -DskipTests compile
```

- [x] Run frontend typecheck:

```bash
cd frontend && pnpm typecheck
```

- [x] Run isolation searches:

```bash
rg -n "clickedPaperHandles|paperHandle|ProductReadingConversationService|ProductReadingReActHarness|ProductReadingToolRegistry" src/main/java/com/yizhaoqi/smartpai/service/ProductConversationService.java src/main/java/com/yizhaoqi/smartpai/service/ProductReActHarness.java src/main/java/com/yizhaoqi/smartpai/service/ProductToolRegistry.java
```

Expected: no output.

```bash
rg -n "clickedPaperHandles" src/main/java frontend/src
```

Expected: hits only in explicit caller-anchor plumbing, request DTO/type definitions, reading conversation service, reading harness, and tests.

- [x] Run full backend tests:

```bash
mvn -q test
```

- [x] Run diff hygiene:

```bash
git diff --check
```

Expected: PASS.

## Completion Criteria

- `referenceFocus.paperHandle` and `referenceFocus.paperHandles` can be parsed from structured WebSocket JSON.
- `referenceFocus.paperId` from existing product rows can be converted to `paperHandle` only inside the reading caller branch.
- Raw `paperId` is not forwarded to `ProductReadingReActHarness` memory or prompt.
- `ProductReadingConversationService` forwards sanitized, de-duplicated, capped `clickedPaperHandles`.
- `ProductReadingReActHarness` seeds same-turn authorization from clicked paper handles.
- Clicked paper handles can feed `get_paper_outline`, `list_paper_locations`, and `find_reading_locations`.
- Clicked paper handles cannot feed `read_locations` until a current-turn location ref is returned.
- Hidden paper handles are still rejected.
- Invalid clicked paper handles are ignored.
- Ambiguous identity result handles remain non-authorizing unless a later explicit clicked paper anchor supplies the selected handle.
- `clickedSourceQuoteRefs` and `trace_source_quotes` behavior remain unchanged.
- With `paperloom.react.reading-phase1.enabled=false`, legacy Product chat behavior remains unchanged.
- `ProductConversationService`, `ProductReActHarness`, and `ProductToolRegistry` remain isolated from Product Reading classes and `clickedPaperHandles`.
- No new LLM-facing tools, no new database schema, and no new product-state card renderer are added.
- Focused backend tests, Product Reading regression tests, legacy isolation tests, compile, frontend typecheck, full backend tests, search gates, and `git diff --check` pass.

## Deferred Work

This slice intentionally does not add:

```text
Product Reading paper-card renderer in chat
clickable ambiguous identity result rows
clicked reading-location refs
clicked table/figure/page refs
paper-row persistence across turns
LLM-controlled pagination or output controls
promotion of the reading experiment flag
```

Recommended next slice after this one:

```text
Product Reading product-state paper cards in chat
```

Reason: once the clicked paper anchor contract exists, the UI can safely render identity/list/search paper choices as clickable cards that send `paperHandle` without relying on ordinals.
