# Product Reading Browse/Search Paper Choice Cards Minimal Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the existing Product Reading paper-choice card loop from identity results to `list_papers` and `search_paper_candidates`, so browse and paper-candidate search rows can be clicked and carried as `referenceFocus.paperHandle` on the next user turn.

**Architecture:** Keep the isolated Product Reading path and the existing nine-tool LLM surface. The reading harness derives sanitized `READING_PAPER_CHOICE` Product State Items from paper-level tool outputs, `ChatHandler` defensively re-sanitizes those items before the WebSocket completion payload, and the current chat paper-choice component renders the rows without adding another renderer. The click path remains the already-implemented clicked-paper-anchor path; cards do not read content, cite evidence, persist history, or authorize ambiguous identity matches in the same turn.

**Tech Stack:** Java 17, Spring Boot, Jackson, JUnit 5, Mockito, Vue 3, TypeScript, Naive UI, Maven, pnpm.

## Global Constraints

- Keep the legacy Product path unchanged when `paperloom.react.reading-phase1.enabled=false`.
- Keep the Product Reading entry point behind `paperloom.react.reading-phase1.enabled=true`; do not promote the flag.
- Do not modify `ProductConversationService`, `ProductReActHarness`, or `ProductToolRegistry`.
- Do not add new LLM-facing tools; the Product Reading tool surface remains exactly nine tools.
- Reuse the existing clicked-paper-anchor contract: frontend clicks must send `referenceFocus.paperHandle`, not ordinals, display row numbers, paper titles, raw ids, or user-message text.
- Extend only paper-level Product State Items for `list_papers`, `search_paper_candidates`, and existing `find_papers_by_identity`; do not add cards for `get_paper_outline`, `list_paper_locations`, `find_reading_locations`, `read_locations`, or `trace_source_quotes`.
- Do not expose raw `paperId`, SQL ids, `modelVersion`, `chunkRef`, Source Quote text, paper content, internal scores, ranks, ordinals, abstracts, semantic previews, facets, paper types, catalog topics, or `locationRef` values in paper-choice card payloads.
- Product Reading paper choice cards are navigation/product-state only. They are not Source Quotes and cannot support paper-content claims.
- `search_paper_candidates.preview` remains a non-citeable LLM tool-output field only; do not copy it into `productStateItems` in this slice.
- Existing same-turn tool authorization from `list_papers`, `search_paper_candidates`, and unambiguous `find_papers_by_identity` remains unchanged.
- Ambiguous identity result handles remain non-authorizing inside the same LLM turn; only a later explicit clicked paper anchor can authorize outline/location/search tools.
- Do not persist `productStateItems` to MySQL, conversation history, Redis, or `ChatGenerationStateService` generation snapshots.
- No new database schema, no UI pagination, no facet UI, no clicked location/table/figure refs, no auto-send on card click, and no promotion of the reading experiment flag in this slice.

---

## Current Evidence

- `ProductReadingToolRegistry` already exposes the complete nine-tool catalog in order: `get_session_state`, `list_papers`, `search_paper_candidates`, `find_papers_by_identity`, `get_paper_outline`, `list_paper_locations`, `find_reading_locations`, `read_locations`, and `trace_source_quotes`.
- `ProductReadingToolAdapter#listPapers(...)` returns `data.items[]` with `paperHandle`, title, filename, authors, year, venue, and non-selection fields such as `ordinal`, `catalogTopics`, and `paperTypes`.
- `ProductReadingToolAdapter#searchPaperCandidates(...)` returns `data.items[]` with `paperHandle`, title, authors, year, venue, and a non-citeable `preview`.
- `ProductReadingReActHarness#updateState(...)` already discloses handles from `list_papers` and `search_paper_candidates` for same-turn navigation/search authorization.
- The previous slice added `ProductTurnResult#productStateItems()`, backend identity-card capture, `ChatHandler` completion delivery, frontend typing, and `product-reading-paper-choice-list.vue`.
- Current frontend `ReadingPaperChoiceItem.sourceTool` is narrowed to `find_papers_by_identity`; the next slice only needs to widen that union and keep the existing renderer/click behavior.

## Grill Outcomes

The user requested recommended answers when a decision needs an answer, so the recommended answers below are adopted without another interview round.

### 1. What is the next project step?

Recommended answer: Product Reading browse/search paper choice cards.

Adopted. Identity paper-choice cards proved the `productStateItems` transport and clicked-paper-anchor loop. The next smallest product-visible gap is that `list_papers` and `search_paper_candidates` can disclose selectable paper handles to the LLM, but the user still cannot click those browse/search rows in chat.

### 2. Should the next slice include both `list_papers` and `search_paper_candidates`, or only one?

Recommended answer: include both.

Adopted. Both tools return paper-level result arrays at `data.items[]`, both already disclose `paperHandle`, and both use the same click contract. Implementing only one would leave the same UI gap open in the adjacent paper-selection path without meaningfully shrinking the backend boundary.

### 3. Should this create a new Product State Item kind?

Recommended answer: no.

Adopted. Reuse `READING_PAPER_CHOICE` and widen `sourceTool` to the allowed paper-level tools. A browsed or searched paper row is still a concrete Product Paper choice with a `paperHandle`.

### 4. Should search candidate previews be copied into card payloads?

Recommended answer: no.

Adopted. `search_paper_candidates.preview` is explicitly non-citeable navigation text. The minimal UI closure should copy only paper-selection metadata and avoid introducing preview rendering until there is a separate, clearly non-evidence Navigation Preview UI contract.

### 5. Should browse/search cards affect same-turn authorization?

Recommended answer: no new behavior.

Adopted. The harness already adds `paperHandle` values from `list_papers` and `search_paper_candidates` to same-turn authorization state. Product State Items are only frontend navigation state for a later explicit user action.

### 6. Should cards be emitted only when the final answer is `PRODUCT_STATE`?

Recommended answer: no.

Adopted. Keep the current `ProductTurnResult` behavior: successful turns may carry Product State Items collected during the turn. The card payload is navigation state and remains bounded, sanitized, and optional. Failed turns still omit completion product-state payloads through `ChatHandler`.

### 7. Should list/search cards expose facets, catalog topics, paper types, ranks, ordinals, or scores?

Recommended answer: no.

Adopted. This slice is only for selecting a Product Paper by `paperHandle`. Facets and richer browse controls need a separate UI contract; ranks, scores, ordinals, and topic/type metadata are not selection identity.

### 8. Should clicking a browse/search card auto-send a prompt?

Recommended answer: no.

Adopted. Preserve the current identity-card behavior: clicking sets `referenceFocus.paperHandle` and pre-fills an editable prompt such as `看这篇论文`; the user explicitly sends the next message.

### 9. Should the frontend add a second component for browse/search rows?

Recommended answer: no.

Adopted. `product-reading-paper-choice-list.vue` already renders generic Product Paper choices. It should remain the single renderer for `READING_PAPER_CHOICE` items and filter source tools defensively.

### 10. Should this slice add durable card replay across history reload?

Recommended answer: no.

Adopted. Product State Items remain current-turn completion payload state. Durable card replay requires a separate persistence decision and is deferred.

## Review Outcomes

The user requested a review with recommended answers, so the recommended answers below are adopted.

### 11. Should browse/search paper choices reuse the existing `READING_PAPER_CHOICE` contract exactly?

Recommended answer: yes.

Adopted. A row from `list_papers`, `search_paper_candidates`, or `find_papers_by_identity` is the same product-state concept: a concrete Product Paper option selected by `paperHandle`. The variant is carried by `sourceTool`, not a new item kind.

### 12. Should the harness and `ChatHandler` both sanitize source tools?

Recommended answer: yes.

Adopted. The harness constructs the intended Product State Items, but the WebSocket delivery boundary must still accept only the explicit paper-choice source-tool set: `list_papers`, `search_paper_candidates`, and `find_papers_by_identity`.

### 13. Should `preview` be stripped at both backend boundaries?

Recommended answer: yes.

Adopted. `search_paper_candidates.preview` stays available in LLM tool output as non-citeable navigation text, but it must not enter `productStateItems`. This prevents the frontend card from looking like evidence or recommendation rationale.

### 14. Should de-duplication happen across all three paper-level source tools?

Recommended answer: yes, by first-seen `paperHandle`.

Adopted. If one turn calls multiple paper-level tools, a paper should render once. The first sanitized item wins because it reflects the model's earliest disclosed paper-choice path and preserves current bounded ordering.

### 15. Should list/search paper choices carry identity-only fields?

Recommended answer: no.

Adopted. `matchReasons`, `identityStatus`, and `ambiguous` belong only to `find_papers_by_identity`. `list_papers` and `search_paper_candidates` items carry selection metadata only.

### 16. Should Product State Items be suppressed for successful `EVIDENCE_ANSWER` turns?

Recommended answer: no, not in this slice.

Adopted. Product State Items are bounded navigation affordances collected during the successful turn. Suppressing them based on answer type would add a second policy dimension before there is evidence of UI noise. Failed completions still omit them.

### 17. Should this review create a new ADR or glossary term?

Recommended answer: no.

Adopted. ADR `0010-use-product-state-items-for-reading-paper-choice-ui.md` already records that future browse/search paper cards can reuse the same Product State Item channel, and `CONTEXT.md` already defines Product State Item, Reading Paper Choice, Paper Candidate Search, Paper Library Browse, and Navigation Preview. The implementation spec should reference those terms rather than invent new domain language.

## Product State Payload Contract

Completion payload fragment after a `list_papers` call:

```json
{
  "type": "completion",
  "generationId": "generation-1",
  "conversationId": "conversation-1",
  "status": "finished",
  "productStateItems": [
    {
      "kind": "READING_PAPER_CHOICE",
      "sourceTool": "list_papers",
      "paperHandle": "paper_handle_abc",
      "title": "Agentic Eval Benchmark",
      "originalFilename": "agentic-eval.pdf",
      "authors": ["Ada Lovelace"],
      "year": 2025,
      "venue": "NeurIPS"
    }
  ]
}
```

Completion payload fragment after a `search_paper_candidates` call:

```json
{
  "type": "completion",
  "generationId": "generation-1",
  "conversationId": "conversation-1",
  "status": "finished",
  "productStateItems": [
    {
      "kind": "READING_PAPER_CHOICE",
      "sourceTool": "search_paper_candidates",
      "paperHandle": "paper_handle_xyz",
      "title": "LoRA: Low-Rank Adaptation of Large Language Models",
      "authors": ["Edward Hu", "Yelong Shen"],
      "year": 2021,
      "venue": "ICLR"
    }
  ]
}
```

Fields intentionally absent from both payloads:

```text
paperId
ordinal
preview
score
rank
locationRef
sourceQuoteRef
catalogTopics
paperTypes
facets
```

Frontend click output stays unchanged:

```json
{
  "referenceFocus": {
    "paperHandle": "paper_handle_abc",
    "paperTitle": "Agentic Eval Benchmark",
    "originalFilename": "agentic-eval.pdf"
  }
}
```

## File Map

- Modify `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarness.java`: capture sanitized paper-choice Product State Items from `list_papers.data.items[]` and `search_paper_candidates.data.items[]`.
- Modify `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java`: accept the expanded paper-choice `sourceTool` set at the delivery boundary.
- Modify `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarnessTest.java`: cover browse/search Product State Item capture, sanitization, de-duplication, and cap behavior.
- Modify `src/test/java/com/yizhaoqi/smartpai/service/ChatHandlerProductHarnessTest.java`: cover completion delivery for list/search source tools and rejection of unsupported source tools.
- Modify `frontend/src/typings/api.d.ts`: widen `ReadingPaperChoiceItem.sourceTool`.
- Modify `frontend/src/views/chat/modules/product-reading-paper-choice-list.vue`: filter allowed paper-choice source tools defensively while preserving the existing click behavior.

## Task 1: Harness Paper-Level Product State Capture

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarness.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarnessTest.java`

**Interfaces:**
- Consumes: `ProductToolResult` from `list_papers`, `search_paper_candidates`, and `find_papers_by_identity`.
- Produces: `ProductTurnResult#productStateItems()` containing sanitized `READING_PAPER_CHOICE` maps.
- Preserves: existing same-turn authorization rules in `updateState(...)`.

- [ ] Add failing test `listPapersResultReturnsBrowsePaperChoiceProductState`.
  - Mock `list_papers` returning `items[]` with `paperHandle`, title, `originalFilename`, authors, year, venue, plus forbidden keys `ordinal`, `preview`, `score`, `rank`, `paperId`, `locationRef`, and `sourceQuoteRef`.
  - Assert the final `ProductTurnResult#productStateItems()` has one item with `kind=READING_PAPER_CHOICE`, `sourceTool=list_papers`, public paper metadata, and none of the forbidden keys.

- [ ] Add failing test `searchPaperCandidatesResultReturnsSearchPaperChoiceProductStateWithoutPreview`.
  - Mock `search_paper_candidates` returning `items[]` with a valid `paperHandle`, title, authors, year, venue, and `preview`.
  - Assert the final product-state item has `sourceTool=search_paper_candidates` and does not contain `preview`, `identityStatus`, or `ambiguous`.

- [ ] Add failing test `paperChoiceProductStateDedupesAcrossPaperLevelToolsAndCapsAtTen`.
  - In one harness turn, return `list_papers` items and then `search_paper_candidates` items with overlapping handles and more than ten total valid handles.
  - Assert first-seen order wins, duplicates are removed, invalid handles are skipped, and the list is capped at 10.

- [ ] Add failing test `nonPaperChoiceToolsDoNotCreatePaperChoiceProductState`.
  - Mock `get_paper_outline`, `list_paper_locations`, `find_reading_locations`, `read_locations`, and `trace_source_quotes` with handle-like fields.
  - Assert no `READING_PAPER_CHOICE` items are added from those tools.

- [ ] Refactor `appendIdentityPaperChoices(...)` into paper-level helpers.
  - Keep `IDENTITY_TOOL_NAME = "find_papers_by_identity"`.
  - Add `LIST_PAPERS_TOOL_NAME = "list_papers"` and `SEARCH_TOOL_NAME = "search_paper_candidates"`.
  - Add an allowed source-tool branch:

```java
if (LIST_PAPERS_TOOL_NAME.equals(toolName) || SEARCH_TOOL_NAME.equals(toolName)) {
    appendPaperChoiceItems(toolName, toolResult.data().get("items"), state, null, null);
}
if (IDENTITY_TOOL_NAME.equals(toolName)) {
    appendPaperChoiceItems(toolName, toolResult.data().get("matches"), state, identityStatus, ambiguous);
}
```

- [ ] Sanitize paper-level choice rows.
  - Require `paperHandle` matching `^paper_handle_[A-Za-z0-9_-]+$`.
  - Set `kind=READING_PAPER_CHOICE`.
  - Set `sourceTool` to exactly `list_papers`, `search_paper_candidates`, or `find_papers_by_identity`.
  - Copy only `paperHandle`, `title`, `originalFilename`, `authors`, `year`, `venue`, and identity-only `matchReasons`, `identityStatus`, `ambiguous`.
  - Do not copy `ordinal`, `preview`, `score`, `rank`, `paperId`, `locationRef`, `sourceQuoteRef`, `catalogTopics`, `paperTypes`, `facets`, raw content, or Source Quote text.

- [ ] Preserve existing authorization logic.
  - `list_papers` and `search_paper_candidates` still add handles to `semanticPaperHandles` and `deterministicLocationPaperHandles`.
  - Unambiguous identity still authorizes same-turn navigation/search.
  - Ambiguous identity still does not authorize same-turn navigation/search.

Verification:

```bash
mvn -q -Dtest=ProductReadingReActHarnessTest test
```

Expected: PASS.

## Task 2: Completion Delivery Sanitizer Widening

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/service/ChatHandlerProductHarnessTest.java`

**Interfaces:**
- Consumes: `ProductTurnResult#productStateItems()` with `READING_PAPER_CHOICE` maps.
- Produces: WebSocket completion payload key `productStateItems`.
- Preserves: legacy Product completion payload and failed completion behavior.

- [ ] Add failing test `readingCompletionSendsListAndSearchPaperChoiceProductStateItems`.
  - Mock `ProductReadingConversationService#runTurn(...)` returning product-state items with `sourceTool=list_papers` and `sourceTool=search_paper_candidates`.
  - Assert completion payload includes both items after sanitization.

- [ ] Add failing test `readingCompletionRejectsUnsupportedPaperChoiceSourceTool`.
  - Return a `READING_PAPER_CHOICE` item with `sourceTool=get_paper_outline`.
  - Assert completion payload omits `productStateItems`.

- [ ] Add failing test `readingCompletionStripsSearchPreviewAndBrowseFacetsFromPaperChoiceItems`.
  - Return list/search items containing `preview`, `catalogTopics`, `paperTypes`, `facets`, `ordinal`, and raw ids.
  - Assert completion payload copies only public paper-choice metadata.

- [ ] Replace the single `FIND_PAPERS_BY_IDENTITY_TOOL` equality check with an allowed set.

```java
private static final Set<String> PAPER_CHOICE_SOURCE_TOOLS = Set.of(
        "list_papers",
        "search_paper_candidates",
        "find_papers_by_identity"
);
```

- [ ] In `sanitizeReadingPaperChoiceItem(...)`, copy the validated `sourceTool` value instead of forcing every item to `find_papers_by_identity`.

- [ ] Preserve current delivery guards.
  - Include `productStateItems` only for non-failed completion payloads.
  - Keep de-duplication by `paperHandle` and cap at 10.
  - Keep cleanup in `cleanupGenerationState(...)`.
  - Do not add persistence or generation-snapshot storage.

Verification:

```bash
mvn -q -Dtest=ChatHandlerProductHarnessTest test
```

Expected: PASS.

## Task 3: Frontend Type and Renderer Widening

**Files:**
- Modify: `frontend/src/typings/api.d.ts`
- Modify: `frontend/src/views/chat/modules/product-reading-paper-choice-list.vue`

**Interfaces:**
- Consumes: `Api.Chat.ProductStateItem[]` from assistant messages.
- Produces: unchanged `chatStore.setReferenceFocus({ paperHandle, paperTitle, originalFilename })` click behavior.
- Preserves: no auto-send; prompt remains editable.

- [ ] Widen `ReadingPaperChoiceItem.sourceTool`.

```ts
sourceTool: 'list_papers' | 'search_paper_candidates' | 'find_papers_by_identity';
```

- [ ] Keep identity-only fields optional.

```ts
matchReasons?: string[] | null;
identityStatus?: string | null;
ambiguous?: boolean | null;
```

- [ ] Add an allowed-source check in `product-reading-paper-choice-list.vue`.

```ts
const paperChoiceSourceTools = new Set([
  'list_papers',
  'search_paper_candidates',
  'find_papers_by_identity'
]);
```

- [ ] Update `isPaperChoice(...)` so it requires:
  - `item.kind === 'READING_PAPER_CHOICE'`;
  - valid `paperHandle`;
  - allowed `sourceTool`.

- [ ] Preserve current display behavior.
  - Continue showing title, filename when present, authors/year/venue metadata, identity match-reason chips when present, and the `AMBIGUOUS` tag only when `item.ambiguous` is true.
  - Do not render `preview`, facets, ordinals, rank, score, raw ids, or location refs.
  - Keep click behavior unchanged.

Verification:

```bash
cd frontend && pnpm typecheck
```

Expected: PASS.

## Task 4: Regression and Isolation Gates

**Files:**
- Inspect only; no production edits expected beyond Tasks 1-3.

**Interfaces:**
- Verifies: Product Reading behavior, legacy Product isolation, frontend type safety, and payload hygiene.

- [ ] Run focused Product Reading tests:

```bash
mvn -q -Dtest=ReadingToolArgumentValidatorTest,ProductReadingToolRegistryTest,ProductReadingToolAdapterTest,ProductReadingReActHarnessTest test
```

Expected: PASS.

- [ ] Run legacy Product isolation tests:

```bash
mvn -q -Dtest=ProductConversationServiceTest,ProductReActHarnessTest,ProductToolRegistryTest test
```

Expected: PASS.

- [ ] Run backend compile:

```bash
mvn -q -DskipTests compile
```

Expected: PASS.

- [ ] Run frontend typecheck:

```bash
cd frontend && pnpm typecheck
```

Expected: PASS.

- [ ] Verify legacy Product files remain isolated:

```bash
rg -n "productStateItems|READING_PAPER_CHOICE|ProductReadingReActHarness|ProductReadingToolRegistry" \
  src/main/java/com/yizhaoqi/smartpai/service/ProductConversationService.java \
  src/main/java/com/yizhaoqi/smartpai/service/ProductReActHarness.java \
  src/main/java/com/yizhaoqi/smartpai/service/ProductToolRegistry.java
```

Expected: no output.

- [ ] Verify the frontend paper-choice component does not reference forbidden payload keys:

```bash
rg -n '"paperId"|"ordinal"|"preview"|"score"|"rank"|"locationRef"|"sourceQuoteRef"|"catalogTopics"|"paperTypes"|"facets"' \
  frontend/src/views/chat/modules/product-reading-paper-choice-list.vue
```

Expected: no output.

- [ ] Verify product-state references are limited to sanitizer and delivery paths:

```bash
rg -n "READING_PAPER_CHOICE|productStateItems|identityStatus" \
  src/main/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarness.java \
  src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java
```

Expected: hits only in product-state capture, sanitizer, and completion delivery code.

- [ ] Run full backend tests:

```bash
mvn -q test
```

Expected: PASS.

- [ ] Run diff hygiene:

```bash
git diff --check
```

Expected: PASS.

## Completion Criteria

- `list_papers` tool results can produce sanitized `READING_PAPER_CHOICE` product-state items.
- `search_paper_candidates` tool results can produce sanitized `READING_PAPER_CHOICE` product-state items.
- Existing `find_papers_by_identity` paper-choice behavior remains unchanged.
- Browse/search card payloads include only public paper-selection metadata and do not include preview text, ordinals, raw ids, scores, ranks, facets, catalog topics, paper types, Source Quote refs, location refs, or paper content.
- Cards render through the existing Product Reading paper-choice component.
- Clicking any identity, browse, or search paper row sets `referenceFocus.paperHandle` and an editable prompt for the next user turn.
- The next user turn uses the existing clicked-paper-anchor path; no ordinal, title, raw id, or message text is used as authorization.
- Existing same-turn authorization from `list_papers`, `search_paper_candidates`, and unambiguous identity results remains unchanged.
- Ambiguous identity results still do not authorize same-turn reading.
- Existing Source Quote click/trace/read behavior remains unchanged.
- With `paperloom.react.reading-phase1.enabled=false`, legacy Product chat behavior remains unchanged.
- No new LLM-facing tools, no new DB schema, no long-term card persistence, no generation-snapshot product-state storage, no Product Reading location cards, and no UI pagination are added.
- Focused backend tests, Product Reading regression tests, legacy isolation tests, compile, frontend typecheck, full backend tests, search gates, and `git diff --check` pass.

## Deferred Work

This slice intentionally does not add:

```text
Navigation Preview rendering for search_paper_candidates.preview
facet UI for list_papers(includeFacets=true)
Product Reading location cards
clicked reading-location refs
clicked table/figure/page refs
paper-card persistence across full history reload
LLM-controlled pagination or output controls
promotion of the reading experiment flag
```

Recommended next slice after this one:

```text
Product Reading Navigation Preview snippets for paper candidate and reading-location search results
```

Reason: browse/search paper cards complete clickable paper selection across all paper-level tools. The next remaining UI value is showing bounded non-evidence previews clearly as navigation aids without confusing them for Source Quotes.
