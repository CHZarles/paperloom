# Product Reading Browse/Search Paper Choice Cards Spec

Date: 2026-07-07

Status: implementation-ready minimal-loop spec.

## Summary

PaperLoom will extend the current Product Reading paper-choice card loop from
`find_papers_by_identity` to the two remaining paper-level selection tools:

```text
list_papers
search_paper_candidates
```

The feature reuses the existing `READING_PAPER_CHOICE` Product State Item shape and the existing
clicked-paper-anchor contract. It does not add LLM-facing tools, database tables, pagination,
facet UI, durable card replay, or any new evidence/citation behavior.

The target loop is:

```text
Product Reading calls list_papers or search_paper_candidates
-> harness captures sanitized paper rows as productStateItems
-> ChatHandler re-sanitizes and sends productStateItems in the completion payload
-> chat renders clickable paper rows with the existing component
-> user clicks a row
-> frontend sets referenceFocus.paperHandle and editable prompt text
-> next user message carries the clicked paper anchor
-> existing Product Reading anchor path authorizes paper navigation/search tools
```

## Related Docs

- Implementation plan:
  `docs/superpowers/plans/2026-07-07-product-reading-browse-search-paper-choice-cards-minimal-loop-plan.md`
- Product-state item ADR:
  `docs/adr/0010-use-product-state-items-for-reading-paper-choice-ui.md`
- Ambiguous identity ADR:
  `docs/adr/0009-do-not-authorize-ambiguous-paper-identity-matches.md`
- Product ReAct tool catalog:
  `docs/superpowers/specs/2026-07-02-product-react-tool-catalog-spec.md`
- Reading Model glossary:
  `CONTEXT.md`

## Current Code Evidence

The current worktree already has the first paper-choice card slice:

- `ProductTurnResult#productStateItems()`
  - Carries Product State Items from Product Reading harness result to `ChatHandler`.
- `ProductReadingReActHarness`
  - Captures `READING_PAPER_CHOICE` items from `find_papers_by_identity.matches[]`.
  - Already adds handles from `list_papers.items[]` and `search_paper_candidates.items[]` to same-turn authorization state.
- `ChatHandler`
  - Stores sanitized Product State Items by generation id.
  - Sends `productStateItems` only on non-failed completion payloads.
  - Currently accepts only `sourceTool=find_papers_by_identity`.
- `frontend/src/typings/api.d.ts`
  - Defines `ReadingPaperChoiceItem`.
  - Currently narrows `sourceTool` to `find_papers_by_identity`.
- `frontend/src/views/chat/modules/product-reading-paper-choice-list.vue`
  - Renders clickable Product Reading paper-choice rows.
  - Sets `referenceFocus.paperHandle`, `paperTitle`, and `originalFilename`.
  - Prefills `看这篇论文` only when the input is empty.

The Product Reading tool adapter already exposes paper-level result rows:

- `ProductReadingToolAdapter#listPapers(...)`
  - Returns `data.items[]`.
  - Rows contain `paperHandle`, title, `originalFilename`, authors, year, venue, and non-card fields such as `ordinal`, `catalogTopics`, and `paperTypes`.
- `ProductReadingToolAdapter#searchPaperCandidates(...)`
  - Returns `data.items[]`.
  - Rows contain `paperHandle`, title, authors, year, venue, and non-citeable `preview`.

## Problem

The Product Reading tool catalog can already disclose paper handles through three paper-level tools:

```text
list_papers
search_paper_candidates
find_papers_by_identity
```

Only identity results currently become clickable UI rows. Browse and search results can be used by
the LLM in the same turn, but the user cannot select those displayed paper choices through the
same explicit clicked-row path. That leaves the UI loop incomplete for common prompts such as:

```text
列出最近的论文
找 agent eval 相关论文
有没有 LoRA 相关论文
```

If the user wants to continue with one listed/searched paper, the product should let them click the
row instead of typing an ordinal, title, or raw identifier.

## Goals

1. Capture sanitized `READING_PAPER_CHOICE` items from `list_papers.data.items[]`.
2. Capture sanitized `READING_PAPER_CHOICE` items from `search_paper_candidates.data.items[]`.
3. Preserve existing `find_papers_by_identity` product-state card behavior.
4. Preserve existing same-turn authorization rules for all paper-level tools.
5. Re-sanitize the expanded source-tool set in `ChatHandler` before WebSocket delivery.
6. Render browse/search rows through the existing paper-choice component.
7. Keep click behavior unchanged: set `referenceFocus.paperHandle` and prefill editable prompt text.
8. Keep Product State Items as navigation UI state only, not evidence, citations, Source Quotes, or persisted history.

## Non-Goals

Do not implement in this slice:

- new LLM-facing tools
- changes to the nine-tool Product Reading catalog
- changes to `ProductConversationService`, `ProductReActHarness`, or `ProductToolRegistry`
- durable paper-card replay across history reload
- database schema changes
- `ChatGenerationStateService` product-state snapshots
- Product Reading cards for outline/location/read/trace tools
- clicked location, page, table, or figure refs
- facet UI for `list_papers(includeFacets=true)`
- UI pagination or output controls
- auto-send after clicking a paper row
- rendering of `search_paper_candidates.preview`
- promotion of `paperloom.react.reading-phase1.enabled`

Do not expose through `productStateItems`:

```text
paperId
SQL ids
modelVersion
chunkRef
Source Quote text
paper content
ordinal
preview
score
rank
abstracts
facets
catalogTopics
paperTypes
locationRef
sourceQuoteRef
```

## Terminology

Use existing glossary terms from `CONTEXT.md`:

- Product State Item
- Reading Paper Choice
- Paper Candidate Search
- Paper Library Browse
- Navigation Preview
- Reading Turn Anchor
- Source Quote

No new glossary term is required for this slice.

## Review Decisions

### 1. Which source tools may create paper-choice Product State Items?

Recommended answer:

Only the three paper-level selection tools:

```text
list_papers
search_paper_candidates
find_papers_by_identity
```

Adopted.

These tools return concrete Product Paper options with `paperHandle`. Other tools return paper
structure, reading locations, or evidence and must not create paper-choice cards.

### 2. Should browse/search use a new Product State Item kind?

Recommended answer:

No. Reuse `READING_PAPER_CHOICE`.

Adopted.

The domain concept is the same: one selectable Product Paper option. The origin is represented by
`sourceTool`.

### 3. Should `search_paper_candidates.preview` be copied?

Recommended answer:

No.

Adopted.

`preview` is a Navigation Preview in LLM tool output. It is explicitly non-citeable and should not
be rendered inside the paper-choice card until there is a separate preview UI contract.

### 4. Should cards be deduplicated across source tools?

Recommended answer:

Yes, by first-seen `paperHandle`.

Adopted.

If one ReAct turn calls multiple paper-level tools, one paper should render once. First-seen order
preserves the earliest disclosed path and keeps the card list stable.

### 5. Should cards be emitted only for `PRODUCT_STATE` final answers?

Recommended answer:

No.

Adopted.

Successful Product Reading turns may carry bounded Product State Items collected during tool use.
`ChatHandler` still omits them for failed completions.

### 6. Should this create an ADR?

Recommended answer:

No.

Adopted.

ADR 0010 already chooses Product State Items as the paper-choice UI channel and explicitly leaves
future browse/search cards to reuse that channel. This spec is an implementation-ready extension of
that decision, not a new architectural decision.

## Product State Item Contract

`READING_PAPER_CHOICE` items have this normalized shape:

```json
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
```

Allowed `sourceTool` values:

```text
list_papers
search_paper_candidates
find_papers_by_identity
```

Additional identity-only fields:

```json
{
  "matchReasons": ["TITLE_CONTAINS", "YEAR"],
  "identityStatus": "AMBIGUOUS",
  "ambiguous": true
}
```

Rules:

- `paperHandle` is required and must match `^paper_handle_[A-Za-z0-9_-]+$`.
- `kind` is always `READING_PAPER_CHOICE`.
- `sourceTool` is copied only after validating it is in the allowed set.
- `title`, `originalFilename`, `venue`, `identityStatus` are optional strings.
- `authors` and `matchReasons` are optional string arrays.
- `year` is optional numeric metadata.
- `matchReasons`, `identityStatus`, and `ambiguous` are copied only for `find_papers_by_identity`.
- Missing display metadata does not invalidate an item if `paperHandle` is valid.

## Backend Harness Requirements

### Capture Points

`ProductReadingReActHarness#updateState(...)` must append product-state choices before returning
from the paper-level tool branches.

For `list_papers`:

```text
toolName = list_papers
rows = toolResult.data().items
sourceTool = list_papers
identityStatus = absent
ambiguous = absent
```

For `search_paper_candidates`:

```text
toolName = search_paper_candidates
rows = toolResult.data().items
sourceTool = search_paper_candidates
identityStatus = absent
ambiguous = absent
```

For `find_papers_by_identity`:

```text
toolName = find_papers_by_identity
rows = toolResult.data().matches
sourceTool = find_papers_by_identity
identityStatus = toolResult.data().status
ambiguous = toolResult.data().ambiguous
```

### Sanitization

The harness sanitizer must:

1. Accept only valid `paper_handle_...` handles.
2. Deduplicate by `paperHandle` across all paper-level source tools.
3. Preserve first-seen order.
4. Cap total items at 10.
5. Construct fresh `LinkedHashMap` instances.
6. Copy only public selection metadata:

```text
kind
sourceTool
paperHandle
title
originalFilename
authors
year
venue
matchReasons
identityStatus
ambiguous
```

7. Omit forbidden fields:

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
modelVersion
chunkRef
content
abstract
```

### Authorization

This feature must not change same-turn authorization:

- Handles from `list_papers` remain authorized for same-turn paper navigation/search.
- Handles from `search_paper_candidates` remain authorized for same-turn paper navigation/search.
- Handles from unambiguous `find_papers_by_identity` remain authorized for same-turn paper navigation/search.
- Handles from ambiguous `find_papers_by_identity` remain product-state choices only and are not same-turn authorization.
- Product State Items themselves are not evidence and do not authorize `read_locations`.

## Chat Delivery Requirements

`ChatHandler` must continue to treat the WebSocket completion payload as a delivery boundary.

Requirements:

1. Replace the single-source identity check with an allowed source-tool set:

```text
list_papers
search_paper_candidates
find_papers_by_identity
```

2. Accept only `READING_PAPER_CHOICE` items with a valid allowed `sourceTool` and valid `paperHandle`.
3. Copy only the normalized Product State Item fields.
4. Copy `matchReasons`, `identityStatus`, and `ambiguous` only when `sourceTool=find_papers_by_identity`.
5. Deduplicate by first-seen `paperHandle`.
6. Cap total items at 10.
7. Store non-empty sanitized lists in generation-scoped memory only.
8. Include `productStateItems` only on non-failed completion payloads.
9. Clear stored Product State Items in generation cleanup.
10. Do not persist Product State Items to conversation history, MySQL, Redis, or generation snapshots.
11. Do not emit Product State Items from the legacy Product harness path.

## Frontend Requirements

`Api.Chat.ReadingPaperChoiceItem.sourceTool` must be widened to:

```ts
'list_papers' | 'search_paper_candidates' | 'find_papers_by_identity'
```

The existing `product-reading-paper-choice-list.vue` component remains the only renderer for
`READING_PAPER_CHOICE` items.

The component must:

- require `kind === 'READING_PAPER_CHOICE'`
- require a valid `paperHandle`
- require `sourceTool` in the allowed paper-choice set
- render title or filename fallback
- render filename when present
- render authors/year/venue metadata when present
- render match-reason chips only when present
- render the `AMBIGUOUS` tag only when `ambiguous === true`
- use the existing icon button click action
- set `referenceFocus.paperHandle`
- set `referenceFocus.paperTitle` when available
- set `referenceFocus.originalFilename` when available
- prefill `看这篇论文` only when the input is empty
- not auto-send the message

The component must not reference or render:

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

## Failure Behavior

- Invalid handles are skipped.
- Unsupported `sourceTool` values are skipped.
- Duplicate handles are skipped after the first valid item.
- Empty or invalid-only Product State Item lists are omitted from completion payloads.
- Failed Product Reading turns omit `productStateItems`.
- Legacy Product turns omit `productStateItems`.
- If the completion payload is missed by the frontend, no durable replay is required in this slice.

## Test Requirements

### Backend Harness Tests

Add or update `ProductReadingReActHarnessTest` cases for:

- `list_papers` rows create sanitized `READING_PAPER_CHOICE` items.
- `search_paper_candidates` rows create sanitized `READING_PAPER_CHOICE` items without `preview`.
- `find_papers_by_identity` behavior remains unchanged.
- invalid handles are skipped.
- duplicate paper handles across paper-level tools are deduplicated.
- the total card list is capped at 10.
- forbidden fields are not copied into Product State Items.
- non-paper-choice tools do not create paper-choice Product State Items.
- ambiguous identity matches still do not authorize same-turn reading.
- list/search same-turn authorization remains unchanged.

### Chat Delivery Tests

Add or update `ChatHandlerProductHarnessTest` cases for:

- reading completion sends list/search paper-choice Product State Items.
- reading completion keeps identity paper-choice Product State Items.
- reading completion rejects unsupported source tools.
- reading completion strips preview, facets, topics, paper types, ordinals, raw ids, refs, scores, and ranks.
- reading completion deduplicates and caps Product State Items.
- invalid-only Product State Items are omitted.
- failed reading completion omits Product State Items.
- legacy Product completion omits Product State Items.
- Product State Items are not persisted through `ConversationService`.

### Frontend Tests/Checks

At minimum:

- Typecheck passes after widening `sourceTool`.
- Search confirms the renderer does not reference forbidden payload keys.
- Manual component inspection confirms click behavior remains unchanged.

## Verification Gates

Run:

```bash
mvn -q -Dtest=ProductReadingReActHarnessTest test
mvn -q -Dtest=ChatHandlerProductHarnessTest test
mvn -q -Dtest=ReadingToolArgumentValidatorTest,ProductReadingToolRegistryTest,ProductReadingToolAdapterTest,ProductReadingReActHarnessTest test
mvn -q -Dtest=ProductConversationServiceTest,ProductReActHarnessTest,ProductToolRegistryTest test
mvn -q -DskipTests compile
cd frontend && pnpm typecheck
mvn -q test
git diff --check
```

Run search gates:

```bash
rg -n "productStateItems|READING_PAPER_CHOICE|ProductReadingReActHarness|ProductReadingToolRegistry" \
  src/main/java/com/yizhaoqi/smartpai/service/ProductConversationService.java \
  src/main/java/com/yizhaoqi/smartpai/service/ProductReActHarness.java \
  src/main/java/com/yizhaoqi/smartpai/service/ProductToolRegistry.java
```

Expected: no output.

```bash
rg -n '"paperId"|"ordinal"|"preview"|"score"|"rank"|"locationRef"|"sourceQuoteRef"|"catalogTopics"|"paperTypes"|"facets"' \
  frontend/src/views/chat/modules/product-reading-paper-choice-list.vue
```

Expected: no output.

```bash
rg -n "READING_PAPER_CHOICE|productStateItems|identityStatus" \
  src/main/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarness.java \
  src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java
```

Expected: hits only in product-state capture, sanitizer, and completion delivery code.

## Acceptance Criteria

- `list_papers` can produce clickable paper-choice rows through `productStateItems`.
- `search_paper_candidates` can produce clickable paper-choice rows through `productStateItems`.
- `find_papers_by_identity` paper-choice rows still work.
- Product State Items use only the normalized `READING_PAPER_CHOICE` contract.
- `sourceTool` is limited to the three allowed paper-level tools.
- `search_paper_candidates.preview` does not enter Product State Items.
- Cards do not expose raw ids, ordinals, scores, ranks, facets, topics, paper types, location refs, Source Quote refs, or paper content.
- Clicks set `referenceFocus.paperHandle` and do not auto-send.
- Same-turn authorization behavior is unchanged.
- Ambiguous identity behavior is unchanged.
- Source Quote behavior is unchanged.
- Legacy Product behavior is unchanged when the reading flag is disabled.
- No new LLM-facing tools, DB schema, durable card persistence, generation snapshot storage, location cards, or pagination are added.
- All verification gates pass.
