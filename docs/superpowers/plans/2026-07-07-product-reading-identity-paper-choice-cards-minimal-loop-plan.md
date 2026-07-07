# Product Reading Identity Paper Choice Cards Minimal Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the next smallest Product Reading UI loop: paper choices returned by `find_papers_by_identity` are rendered as clickable Product Reading paper cards that send the already-implemented `referenceFocus.paperHandle` anchor on the next user turn.

**Architecture:** Keep Product Reading isolated and do not add new LLM-facing tools. The reading harness derives sanitized paper-choice UI state from `find_papers_by_identity` tool results, `ChatHandler` sends that state with the completion payload, and the chat UI renders compact clickable paper rows that set `referenceFocus.paperHandle`. The click does not authorize content by itself until the next user message carries the structured anchor through the existing clicked-paper-anchor path.

**Tech Stack:** Java 17, Spring Boot, Jackson, JUnit 5, Mockito, Vue 3, TypeScript, Naive UI, Maven, pnpm.

## Global Constraints

- Keep the legacy Product path unchanged when `paperloom.react.reading-phase1.enabled=false`.
- Keep the Product Reading entry point behind `paperloom.react.reading-phase1.enabled=true`; do not promote the flag.
- Do not modify `ProductConversationService`, `ProductReActHarness`, or `ProductToolRegistry`.
- Do not add new LLM-facing tools; the Product Reading tool surface remains exactly nine tools.
- Use the existing clicked-paper-anchor contract: frontend clicks must send `referenceFocus.paperHandle`, not ordinals, display row numbers, paper titles, raw ids, or user-message text.
- Do not expose raw `paperId`, SQL ids, `modelVersion`, `chunkRef`, Source Quote text, paper content, scores, ranks, abstracts, semantic previews, or `locationRef` values in paper-choice card payloads.
- Product Reading paper choice cards are navigation/product-state only. They are not Source Quotes and cannot support paper-content claims.
- Ambiguous identity result handles remain non-authorizing inside the same LLM turn; only a later explicit clicked paper anchor can authorize outline/location/search tools.
- Existing `clickedPaperHandles`, `clickedSourceQuoteRefs`, `trace_source_quotes`, and `read_locations` behavior remains unchanged.
- No new database schema, no long-term paper-card persistence, no Product Reading cards for `list_papers` or `search_paper_candidates`, no clicked location/table/figure refs, no UI pagination, and no promotion of the reading experiment flag in this slice.

---

## Current Evidence

- The Product Reading LLM tool surface is complete at nine tools.
- `find_papers_by_identity` returns `matches[]` with `paperHandle`, title, filename, authors, year, venue, and match reasons.
- ADR `0009-do-not-authorize-ambiguous-paper-identity-matches.md` says ambiguous result cards may include `paperHandle` values so the UI can render choices, but those handles must not authorize same-turn reading.
- ADR `0010-use-product-state-items-for-reading-paper-choice-ui.md` records the UI-channel decision: paper choice rows come from backend-controlled Product State Items, not LLM prose, raw tool streams, Source Quotes, or long-term persisted card state.
- The clicked-paper-anchor slice added `referenceFocus.paperHandle` / `paperHandles`, backend bridge handling, and harness authorization for later turns.
- The frontend currently renders assistant markdown and Source Quote references, but it does not render Product Reading paper choices as clickable rows.
- `ToolProgressEvent` currently streams only tool names, so the lowest-risk UI payload is a sanitized completion payload derived from tool results, not raw tool-result streaming.

## Grill Outcomes

The user requested recommended answers when a decision needs an answer, so the recommended answers below are adopted.

### 1. What is the next project step?

Recommended answer: Product Reading identity paper choice cards in chat.

Adopted. After clicked paper anchors, the missing closed loop is letting the user select a concrete paper row from an identity result, especially an ambiguous identity result, without typing ordinals or titles.

### 2. What is the smallest closed loop?

Recommended answer: only `find_papers_by_identity` paper choices.

Adopted. The loop is:

```text
user asks for a specific paper
-> Product Reading calls find_papers_by_identity
-> harness captures sanitized identity matches as productStateItems
-> ChatHandler sends productStateItems with completion
-> chat UI renders clickable paper rows
-> user clicks a row
-> UI sets referenceFocus.paperHandle
-> next user message carries clickedPaperHandles through the existing anchor path
-> outline/location/search tools can use the selected paper
```

Do not render `list_papers` or `search_paper_candidates` cards in this slice. They can reuse the same shape later, but identity ambiguity is the smallest useful closure.

### 3. Should card data come from the LLM final answer?

Recommended answer: no.

Adopted. The card payload must be derived from sanitized tool results, not parsed from assistant prose. The LLM may ask the user to choose, but the UI rows come from backend-controlled product state.

### 4. Should ambiguous identity handles authorize reading in the same turn?

Recommended answer: no.

Adopted. This preserves ADR 0009. The UI can render ambiguous `paperHandle` values as choices, but the harness must not add those handles to same-turn authorization. The click becomes a new explicit caller anchor on a later turn.

### 5. Should clicking a paper card auto-send a prompt?

Recommended answer: no.

Adopted. Clicking the row sets `referenceFocus.paperHandle` and pre-fills a short editable prompt such as `看这篇论文`. The user still sends the next message explicitly.

### 6. Should card payloads be stored in the database now?

Recommended answer: no.

Adopted. This slice is a current-chat closed loop. Cards are delivered with the current completion payload and held in the frontend message state. Long-term conversation-history card persistence would require a separate persistence contract and is deferred.

### 7. Should cards include abstracts, previews, ranks, scores, or raw ids?

Recommended answer: no.

Adopted. Cards include only product-state identity metadata needed for selection: `paperHandle`, title, filename, authors, year, venue, match reasons, source tool, and ambiguity status.

### 8. Should legacy Product chat consume or render these cards?

Recommended answer: no.

Adopted. Product Reading identity cards are emitted only from `ProductReadingReActHarness` results and only consumed by the chat UI as Product Reading product state.

### 9. Should Source Quote behavior change?

Recommended answer: no.

Adopted. Paper choice cards are not Source Quotes. Existing Source Quote click, trace, and citation rendering remain unchanged.

## Review Outcomes

The user requested a review with recommended answers, so the recommended answers below are adopted.

### 10. Should Product Reading paper choices become long-lived conversation history?

Recommended answer: no.

Adopted. Paper choice cards are current-turn Product State Items delivered with the WebSocket completion payload. They are not stored in MySQL conversation history and are not required in `ChatGenerationStateService` generation snapshots in this slice. If the completion payload is missed, the user can ask again; durable card replay is deferred with long-term paper-card persistence.

### 11. Should `ChatHandler` trust `ProductTurnResult#productStateItems()` without checking it?

Recommended answer: no.

Adopted. The reading harness is the primary sanitizer, but `ChatHandler` must still defensively re-sanitize the completion payload before putting `productStateItems` on the wire. The delivery boundary should accept only `READING_PAPER_CHOICE` items with valid `paperHandle` values and should copy only the public paper-choice fields.

### 12. Where should `identityStatus` and `ambiguous` come from?

Recommended answer: from the `find_papers_by_identity` top-level result, not from each match row.

Adopted. Match rows provide paper display metadata and `paperHandle`; the surrounding tool result provides identity resolution status. Product-state items should map top-level `status` to `identityStatus` and top-level `ambiguous` to `ambiguous`.

### 13. Should this slice introduce a typed Java product-state class hierarchy?

Recommended answer: no.

Adopted. Keep `ProductTurnResult` as `List<Map<String,Object>> productStateItems` for the minimal completion-payload bridge, but make every producer and delivery boundary construct fresh sanitized maps. A typed hierarchy can be introduced when more Product State Item kinds are added.

### 14. Should the UI expose row numbers for paper choices?

Recommended answer: no.

Adopted. The UI may visually group and order rows, but neither props nor click payloads should carry ordinals. The only selection identity is `paperHandle`.

## Product State Payload Contract

Completion payload fragment:

```json
{
  "type": "completion",
  "generationId": "generation-1",
  "conversationId": "conversation-1",
  "status": "finished",
  "productStateItems": [
    {
      "kind": "READING_PAPER_CHOICE",
      "sourceTool": "find_papers_by_identity",
      "ambiguous": true,
      "identityStatus": "AMBIGUOUS",
      "paperHandle": "paper_handle_abc",
      "title": "LoRA: Low-Rank Adaptation of Large Language Models",
      "originalFilename": "lora.pdf",
      "authors": ["Edward Hu", "Yelong Shen"],
      "year": 2021,
      "venue": "ICLR",
      "matchReasons": ["TITLE_CONTAINS", "YEAR"]
    }
  ]
}
```

Frontend click output:

```json
{
  "referenceFocus": {
    "paperHandle": "paper_handle_abc",
    "paperTitle": "LoRA: Low-Rank Adaptation of Large Language Models",
    "originalFilename": "lora.pdf"
  }
}
```

## Task 1: Backend Product State Result Contract

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ProductTurnResult.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarness.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarnessTest.java`

**Interfaces:**
- Produces: `ProductTurnResult#productStateItems()`.
- Produces: `READING_PAPER_CHOICE` items derived from `find_papers_by_identity.matches[]`.
- Preserves: existing `ProductTurnResult` constructor call sites through a backward-compatible overload.

- [ ] Add `List<Map<String,Object>> productStateItems` to `ProductTurnResult`.
- [ ] Keep the existing constructor signature by adding an overload that defaults `productStateItems` to `List.of()`.
- [ ] In the compact constructor, defensively copy product-state item maps into fresh `LinkedHashMap` instances before storing an immutable list.
- [ ] Add `productStateItems` to `ReadingTurnState`.
- [ ] When `updateState(...)` sees a successful `find_papers_by_identity` result, append sanitized product-state choices before the ambiguous-result authorization guard returns.
- [ ] Include both ambiguous and unambiguous identity matches in `productStateItems`; same-turn authorization remains controlled by existing `ambiguous == false` logic.
- [ ] Sanitize paper choices:
  - require `paperHandle` matching `^paper_handle_[A-Za-z0-9_-]+$`;
  - set `kind` to `READING_PAPER_CHOICE`;
  - set `sourceTool` to `find_papers_by_identity`;
  - copy `identityStatus` from the top-level tool-result `status`;
  - copy `ambiguous` from the top-level tool-result `ambiguous`;
  - copy only match-row `title`, `originalFilename`, `authors`, `year`, `venue`, and `matchReasons`;
  - do not copy `ordinal`, raw `paperId`, scores, ranks, abstracts, previews, paper content, Source Quote text, or location refs.
- [ ] De-duplicate by `paperHandle` while preserving first-seen order.
- [ ] Cap paper choice cards at 10 for this slice.
- [ ] Return `state.productStateItems` from successful final results.
- [ ] Add tests:
  - ambiguous identity result returns `productStateItems` with two `READING_PAPER_CHOICE` items.
  - ambiguous identity result still does not authorize `get_paper_outline`, `list_paper_locations`, or `find_reading_locations`.
  - unambiguous identity result returns one product-state item and still authorizes reading navigation/search as before.
  - invalid or missing `paperHandle` match rows are ignored.
  - duplicate paper handles are de-duplicated and the list is capped at 10.
  - product-state item maps are defensive copies, not aliases to raw tool-result match maps.
  - product-state item payload contains no `paperId`, `ordinal`, `preview`, `score`, `rank`, `locationRef`, or Source Quote text.
  - non-identity tools do not emit paper choice items in this slice.

Verification:

```bash
mvn -q -Dtest=ProductReadingReActHarnessTest test
```

Expected: PASS.

## Task 2: Chat Completion Delivery

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/service/ChatHandlerProductHarnessTest.java`

**Interfaces:**
- Consumes: `ProductTurnResult#productStateItems()`.
- Produces: WebSocket completion payload key `productStateItems`.
- Preserves: legacy Product completion payload when reading flag is false.

- [ ] Add a generation-scoped in-memory map in `ChatHandler` for `productStateItems`, similar to diagnostics/reference mappings.
- [ ] Add a `sanitizeProductStateItems(...)` helper in `ChatHandler`.
- [ ] The `ChatHandler` helper accepts only `READING_PAPER_CHOICE` items with `sourceTool=find_papers_by_identity` and valid `paperHandle`.
- [ ] The `ChatHandler` helper copies only `kind`, `sourceTool`, `paperHandle`, `title`, `originalFilename`, `authors`, `year`, `venue`, `matchReasons`, `identityStatus`, and `ambiguous`.
- [ ] The `ChatHandler` helper de-duplicates by `paperHandle`, caps at 10, and returns fresh maps.
- [ ] In `runReadingHarness(...)`, after a successful Product Reading result, store defensively sanitized `answer.productStateItems()` for the generation when non-empty.
- [ ] In `sendCompletionNotification(...)`, include `productStateItems` only for non-failed completion payloads and only when the stored list is non-empty.
- [ ] Clear the generation product-state map in `cleanupGenerationState(...)`.
- [ ] Do not send product-state items from `runProductHarness(...)`.
- [ ] Do not persist product-state items to `ConversationService` or database in this slice.
- [ ] Do not add `productStateItems` to `ChatGenerationStateService` generation snapshots in this slice.
- [ ] Add tests:
  - reading completion sends `productStateItems` when `ProductReadingConversationService` returns them.
  - reading completion omits invalid/empty product state.
  - reading completion strips forbidden keys from mocked `ProductTurnResult#productStateItems()`.
  - reading completion de-duplicates and caps product-state items.
  - legacy Product completion omits `productStateItems`.
  - failed reading completion omits `productStateItems`.

Verification:

```bash
mvn -q -Dtest=ChatHandlerProductHarnessTest test
```

Expected: PASS.

## Task 3: Frontend Product State Types And Store Wiring

**Files:**
- Modify: `frontend/src/typings/api.d.ts`
- Modify: `frontend/src/views/chat/modules/input-box.vue`
- Modify if needed: `frontend/src/store/modules/chat/index.ts`

**Interfaces:**
- Consumes: WebSocket completion `productStateItems`.
- Produces: `Api.Chat.Message.productStateItems`.
- Preserves: existing `referenceMappings`, `diagnostics`, and Source Quote behavior.

- [ ] Add `Api.Chat.ProductStateItem` union with `ReadingPaperChoiceItem`.
- [ ] Define `ReadingPaperChoiceItem` fields:
  - `kind: 'READING_PAPER_CHOICE'`
  - `sourceTool: 'find_papers_by_identity'`
  - `paperHandle: string`
  - `title?: string | null`
  - `originalFilename?: string | null`
  - `authors?: string[] | null`
  - `year?: number | null`
  - `venue?: string | null`
  - `matchReasons?: string[] | null`
  - `identityStatus?: string | null`
  - `ambiguous?: boolean | null`
- [ ] Add `productStateItems?: ProductStateItem[]` to `Api.Chat.Message`.
- [ ] In `input-box.vue#handleCompletionPayload`, copy `payload.productStateItems` onto the active assistant message only when it is an array.
- [ ] Ensure `productStateItems` is not overwritten by chunk handling.
- [ ] Do not add `productStateItems` to `Api.Chat.GenerationSnapshot` in this slice.
- [ ] If generation snapshot or loaded conversation messages do not contain `productStateItems`, treat that as expected for this slice.

Verification:

```bash
cd frontend && pnpm typecheck
```

Expected: PASS.

## Task 4: Frontend Identity Paper Choice Renderer

**Files:**
- Create: `frontend/src/views/chat/modules/product-reading-paper-choice-list.vue`
- Modify: `frontend/src/views/chat/modules/chat-message.vue`

**Interfaces:**
- Consumes: `Api.Chat.ReadingPaperChoiceItem[]`.
- Produces: `chatStore.setReferenceFocus({ paperHandle, paperTitle, originalFilename })`.
- Preserves: markdown answer rendering, Source Quote reference clicks, and feedback/retry actions.

- [ ] Create a compact unframed paper-choice list component rendered below assistant markdown.
- [ ] Filter for `kind === 'READING_PAPER_CHOICE'` and valid `paperHandle`.
- [ ] Render each row with title, filename, authors/year/venue metadata, and match reason chips.
- [ ] Render ambiguous identity choices with a subtle state label, but do not instruct the user with long explanatory copy.
- [ ] Add a button with a message/arrow icon for selecting the paper.
- [ ] On click, call:

```ts
chatStore.setReferenceFocus({
  paperHandle: item.paperHandle,
  paperTitle: item.title || undefined,
  originalFilename: item.originalFilename || undefined
});
```

- [ ] If the input is empty, prefill:

```ts
chatStore.input.message = '看这篇论文';
```

- [ ] Do not auto-send the message.
- [ ] Do not include raw `paperId` or ordinals in the UI component props or emitted payload.
- [ ] Add the component below assistant markdown in `chat-message.vue` and keep Source Quote click handling unchanged.
- [ ] Ensure long paper titles and filenames wrap cleanly on mobile and desktop.

Verification:

```bash
cd frontend && pnpm typecheck
```

Expected: PASS.

## Task 5: Verification And Isolation Gates

- [ ] Run focused backend tests:

```bash
mvn -q -Dtest=ProductReadingReActHarnessTest,ChatHandlerProductHarnessTest test
```

- [ ] Run Product Reading regression tests:

```bash
mvn -q -Dtest=ReadingToolArgumentValidatorTest,ProductReadingToolRegistryTest,ProductReadingToolAdapterTest,ProductReadingReActHarnessTest test
```

- [ ] Run legacy Product isolation tests:

```bash
mvn -q -Dtest=ProductConversationServiceTest,ProductReActHarnessTest,ProductToolRegistryTest test
```

- [ ] Run compile gate:

```bash
mvn -q -DskipTests compile
```

- [ ] Run frontend typecheck:

```bash
cd frontend && pnpm typecheck
```

- [ ] Run isolation searches:

```bash
rg -n "productStateItems|READING_PAPER_CHOICE|ProductReadingReActHarness|ProductReadingToolRegistry" src/main/java/com/yizhaoqi/smartpai/service/ProductConversationService.java src/main/java/com/yizhaoqi/smartpai/service/ProductReActHarness.java src/main/java/com/yizhaoqi/smartpai/service/ProductToolRegistry.java
```

Expected: no output.

```bash
rg -n "\"paperId\"|\"ordinal\"|\"preview\"|\"score\"|\"rank\"|\"locationRef\"|\"sourceQuoteRef\"" frontend/src/views/chat/modules/product-reading-paper-choice-list.vue
```

Expected: no output.

```bash
rg -n "READING_PAPER_CHOICE|productStateItems|identityStatus" src/main/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarness.java src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java
```

Expected: hits only in the paper-choice sanitizer, product-state result wiring, and completion delivery code.

- [ ] Run full backend tests:

```bash
mvn -q test
```

- [ ] Run diff hygiene:

```bash
git diff --check
```

Expected: PASS.

## Completion Criteria

- `find_papers_by_identity` tool results can produce sanitized `READING_PAPER_CHOICE` product-state items.
- Ambiguous identity choices render as clickable paper rows but do not authorize same-turn reading.
- Clicking a paper row sets `referenceFocus.paperHandle` and an editable prompt for the next user turn.
- The next user turn uses the existing clicked-paper-anchor path; no ordinal, title, raw id, or message text is used as authorization.
- Paper choice payloads do not expose raw `paperId`, SQL ids, `modelVersion`, `chunkRef`, `ordinal`, Source Quote text, paper content, abstracts, previews, scores, ranks, or location refs.
- Existing Source Quote click/trace/read behavior remains unchanged.
- With `paperloom.react.reading-phase1.enabled=false`, legacy Product chat behavior remains unchanged.
- No new LLM-facing tools, no new DB schema, no long-term card persistence, no Product Reading cards for `list_papers` or `search_paper_candidates`, and no UI pagination are added.
- Focused backend tests, Product Reading regression tests, legacy isolation tests, compile, frontend typecheck, full backend tests, search gates, and `git diff --check` pass.

## Deferred Work

This slice intentionally does not add:

```text
Product Reading list_papers paper cards
Product Reading search_paper_candidates paper cards
clicked reading-location refs
clicked table/figure/page refs
paper-card persistence across full history reload
LLM-controlled pagination or output controls
promotion of the reading experiment flag
```

Recommended next slice after this one:

```text
Product Reading browse/search paper cards using the same productStateItems contract
```

Reason: identity choice cards prove the UI card contract and clicked-paper-anchor loop on the highest-value ambiguous-selection path before widening the same renderer to browse/search result sets.
