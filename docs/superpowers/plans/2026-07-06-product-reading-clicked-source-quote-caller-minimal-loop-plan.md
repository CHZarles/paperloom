# Product Reading Clicked Source Quote Caller Minimal Loop Plan

Date: 2026-07-06

Status: reviewed planned next slice

Baseline commit: `fe660b6 feat: add product reading source quote trace loop`

## Goal

Build the smallest user-facing caller loop that passes a real clicked Source
Quote anchor into the already-isolated Product Reading path:

```text
reading answer returns sourceQuoteRef-backed reference
-> frontend renders a clickable source chip carrying sourceQuoteRef
-> user clicks the chip and asks a follow-up
-> WebSocket request sends referenceFocus.sourceQuoteRef
-> ChatHandler converts it to clickedSourceQuoteRefs
-> ProductReadingConversationService
-> trace_source_quotes
-> sourceQuoteRef-backed follow-up answer
```

This slice wires the existing service-level trace loop to the existing chat
caller surface behind the disabled-by-default reading experiment flag. It does
not merge the reading tool catalog into the legacy Product ReAct catalog.

## Current Evidence

- `ProductReadingConversationService` already accepts `effectiveScope` with
  `clickedSourceQuoteRefs`, sanitizes it, caps it, and forwards it to
  `ProductReadingReActHarness`.
- `ProductReadingReActHarness` already exposes exactly
  `search_paper_candidates`, `find_reading_locations`, `read_locations`, and
  `trace_source_quotes`.
- `trace_source_quotes` already validates conversation registration, paper
  visibility, and locked scope, then returns stored Source Quote content.
- `ChatWebSocketHandler` already accepts JSON chat payloads with
  `referenceFocus`.
- `ChatHandler` already converts Product turn references into
  `ReferenceInfo` mappings, stores generation reference mappings, streams final
  markdown, and records normal Product chat conversations.
- Frontend chat citation chips already render `[n]` spans from
  `referenceMappings`, and the input box already sends `referenceFocus`.
- Current frontend/reference DTOs do not carry `sourceQuoteRef`.
- `ChatHandler` currently resolves `referenceFocus.referenceNumber` through the
  legacy reference-detail path and requires a `paperId`; a Source Quote-only
  focus must bypass that legacy paper requirement.
- `ProductReadingConversationService` intentionally has no dependency on
  `ConversationService`; a chat caller integration must persist reading turns
  outside the reading service.

## Grill Decisions

### 1. What is the next project step?

Recommended answer:

Wire explicit clicked `sourceQuoteRef` anchors from the real chat caller to the
isolated Product Reading entry point.

Adopted.

Reason: the service-level trace loop is complete, but no real caller can yet
transport a clicked source chip into `clickedSourceQuoteRefs`.

### 2. What counts as the smallest closed loop?

Recommended answer:

Use the existing WebSocket chat surface and frontend citation chips, but only
behind `paperloom.react.reading-phase1.enabled=true`:

```text
turn 1: ChatHandler routes to ProductReadingConversationService
turn 1: ProductReading answer returns references containing sourceQuoteRef
turn 1: frontend renders the citation chip as a Source Quote chip
turn 2: clicking that chip sends referenceFocus.sourceQuoteRef
turn 2: ChatHandler passes clickedSourceQuoteRefs to ProductReadingConversationService
turn 2: trace_source_quotes returns the quote and final answer cites it
```

Adopted.

Do not add a separate endpoint, a new chat page, display citation-number
resolution, or conversation-history parsing.

### 3. Should the normal chat path switch by default?

Recommended answer:

No.

Adopted.

`paperloom.react.reading-phase1.enabled=false` must keep the current
`ProductConversationService -> ProductReActHarness -> ProductToolRegistry`
path unchanged. The new caller path is experimental and active only when the
existing reading flag is enabled.

### 4. Should `ProductConversationService` depend on reading classes?

Recommended answer:

No.

Adopted.

Keep the legacy Product service isolated. If a caller needs to choose the
reading service, do it in `ChatHandler`, which already owns the chat transport,
generation state, reference mappings, and normal turn orchestration.

### 5. Should there be a new HTTP endpoint for reading chat?

Recommended answer:

No.

Adopted.

Reusing the existing WebSocket JSON payload is the smallest caller integration:
it already transports `message` and `referenceFocus`, and it already streams the
answer.

### 6. What should the frontend send when a Source Quote chip is clicked?

Recommended answer:

Send a stable product anchor, not display text:

```json
{
  "referenceFocus": {
    "sourceQuoteRef": "source_quote_...",
    "referenceNumber": 1
  }
}
```

Adopted.

The backend converts this to:

```json
{
  "clickedSourceQuoteRefs": ["source_quote_..."]
}
```

The user message can still be plain language such as `explain this source`.

### 7. Should display citations such as `[1]` be resolved now?

Recommended answer:

No.

Adopted.

The chip click carries the stable `sourceQuoteRef`. Text such as `[1]`,
`source_quote_...`, or "the first citation" is not authorization and remains
out of scope.

### 8. Should frontend-provided `sourceQuoteRef` be trusted as evidence?

Recommended answer:

No.

Adopted.

The frontend value is only an input anchor. Evidence support still requires:

1. `ChatHandler` forwards it as `clickedSourceQuoteRefs`.
2. The reading harness allows `trace_source_quotes` only for clicked refs.
3. `trace_source_quotes` validates `conversation_source_quotes`.
4. The final answer cites only Source Quotes returned in the current turn.

### 9. Should Source Quote references require `paperId` to be clickable?

Recommended answer:

No.

Adopted.

For this minimum loop, a Source Quote chip can be actionable with
`sourceQuoteRef` alone. PDF/evidence-panel navigation from a Source Quote can
come later. The chip's first job is to create a deterministic follow-up anchor.

### 10. Should the source evidence panel be expanded now?

Recommended answer:

No.

Adopted.

Do not build a full Source Quote evidence page in this slice. If a clicked
Source Quote reference lacks legacy PDF evidence fields, the frontend can set
chat `referenceFocus` directly and prefill/focus the input with a follow-up
prompt.

### 11. Should reading turns be persisted in conversation history?

Recommended answer:

Yes.

Adopted.

The chat caller integration must record reading answers and reference mappings
so refreshed chat history still contains clickable Source Quote chips. Keep
that persistence in the chat caller layer or a small shared helper; do not add
`ConversationService` to `ProductReadingConversationService`.

### 12. Should trace output metadata unlock more tools?

Recommended answer:

No.

Adopted.

`paperHandle` and `locationRef` returned by `trace_source_quotes` remain display
metadata. They must not authorize later `find_reading_locations` or
`read_locations` calls.

### 13. Should `referenceNumber` on a Source Quote chip trigger legacy reference-detail resolution?

Recommended answer:

No.

Adopted.

A Source Quote chip may include `referenceNumber` for display continuity, but
the stable authorization anchor is `sourceQuoteRef`. If `sourceQuoteRef` is
present, `ChatHandler` must not require the old reference-detail endpoint to
resolve a `paperId` before the turn can proceed.

### 14. Should typed display citations still work in the legacy chat path?

Recommended answer:

Yes, for the legacy path only.

Adopted.

Do not break existing reference-number follow-up in normal Product chat. The
new reading caller loop must build `clickedSourceQuoteRefs` only from structured
`referenceFocus.sourceQuoteRef`, never from `firstCitedReferenceNumber(...)` or
user message text.

## Non-Goals

- No display citation-number resolution such as parsing `[1]`.
- No parsing raw `source_quote_...` from user text.
- No conversation-history search for old Source Quotes.
- No Product Reading dependency inside `ProductConversationService`.
- No migration of the legacy `ProductToolRegistry` to the reading catalog.
- No frontend Source Quote evidence-detail page.
- No PDF/page/table/figure screenshot follow-up from Source Quote metadata.
- No new database schema.
- No rename of `paperloom.react.reading-phase1.enabled`.
- No behavior change when `paperloom.react.reading-phase1.enabled=false`.

## Implementation Tasks

### Task 1: Carry `sourceQuoteRef` Through Reference DTOs

Modify:

- `src/main/java/com/yizhaoqi/smartpai/service/ProductReferenceFocus.java`
- `src/main/java/com/yizhaoqi/smartpai/handler/ChatWebSocketHandler.java`
- `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java`
- `frontend/src/typings/api.d.ts`

Required behavior:

- `referenceFocus.sourceQuoteRef` is parsed from inbound WebSocket JSON.
- `ProductReferenceFocus` stores a sanitized single `sourceQuoteRef`.
- `ChatHandler#hasReferenceFocus(...)` treats `sourceQuoteRef` as structured
  focus even when `referenceNumber` is absent.
- If a structured focus contains `sourceQuoteRef`, `ChatHandler` preserves it
  through `structuredReferenceFocus(...)`, `resolveReferenceFocus(...)`, and any
  enrichment path.
- A Source Quote-only focus does not require `paperId`, `paperIds`, or a
  resolvable legacy `referenceNumber`.
- `ChatHandler.ReferenceInfo` can carry `sourceQuoteRef`.
- Serialized generation reference mappings include `sourceQuoteRef`.
- Deserialized generation reference mappings preserve `sourceQuoteRef`.
- Frontend `ReferenceEvidence` and `Scope` include optional `sourceQuoteRef`.

Do not parse source refs from `message` text.

### Task 2: Route Reading Experiment Turns From `ChatHandler`

Modify:

- `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java`
- tests under `src/test/java/com/yizhaoqi/smartpai/service/`

Required behavior:

- Inject `ProductReadingConversationService` and
  `ProductReadingReactProperties` into `ChatHandler`; keep constructor/test
  setup explicit so existing unit tests do not accidentally route through the
  reading branch.
- When `paperloom.react.reading-phase1.enabled=false`, keep using
  `ProductConversationService` exactly as today. A submitted
  `referenceFocus.sourceQuoteRef` is ignored by the legacy Product path and
  must not become hidden evidence there.
- When `paperloom.react.reading-phase1.enabled=true`, call
  `ProductReadingConversationService`.
- If inbound structured `ProductReferenceFocus#sourceQuoteRef` is present, add
  `clickedSourceQuoteRefs` to the effective scope passed to the reading service.
- Do not derive `clickedSourceQuoteRefs` from typed `[1]`, typed
  `source_quote_...`, resolved legacy reference details, Redis history, or
  Product memory.
- Reading branch streams the result through existing generation state and
  WebSocket completion machinery.
- Reading branch persists conversation text and reference mappings through the
  chat caller layer, not through `ProductReadingConversationService`. Avoid
  double-persisting normal Product turns, because `ProductConversationService`
  already records those.
- Reading branch must record the completed turn through
  `ConversationService#recordConversation(...)` with the same final markdown,
  reference mappings, and effective scope used by normal chat history. The
  existing Redis generation/history updates are not enough by themselves.

### Task 3: Preserve Source Quote Reference Mappings

Modify:

- `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java`
- `src/test/java/com/yizhaoqi/smartpai/service/ChatHandlerProductHarnessTest.java`

Required behavior:

- `productReferenceMappings(...)` copies `sourceQuoteRef` from
  `ProductTurnResult.references()`.
- `productReferenceMappings(...)` maps Source Quote reference `content` to
  `matchedChunkText`, `evidenceSnippet`, and `anchorText` so existing chat
  reference payloads can display useful text without exposing internal fields.
- `ReferenceInfo` accepts references with `sourceQuoteRef` even when `paperId`
  is absent.
- `toSerializableReferenceMappings(...)` includes `sourceQuoteRef`.
- `getReferenceDetail(...)` / deserialization preserves `sourceQuoteRef`.
- Existing legacy evidence refs continue to serialize unchanged.

### Task 4: Make Frontend Citation Chips Source-Quote-Aware

Modify:

- `frontend/src/views/chat/modules/chat-message.vue`
- `frontend/src/views/chat/modules/input-box.vue`
- optionally `frontend/src/views/chat/modules/reference-evidence-page.vue`
- optionally `frontend/src/views/chat/modules/source-evidence-panel.vue`

Required behavior:

- A `[n]` chip is actionable if its reference mapping has either legacy
  `paperId` evidence data or `sourceQuoteRef`.
- For references with `sourceQuoteRef`, clicking the chip sets
  `chatStore.referenceFocus` with that `sourceQuoteRef` and the reference
  number.
- Source Quote-only chips must not show as muted merely because `paperId` is
  absent.
- If a Source Quote mapping also has legacy `paperId` evidence data, preserve
  current PDF/evidence behavior and include `sourceQuoteRef` in the focus
  payload when the user chooses to ask about the reference.
- The input prompt may default to `解释这个引用` / `explain this source`.
- The outgoing WebSocket payload includes `referenceFocus.sourceQuoteRef`.
- If a reference has both legacy evidence fields and `sourceQuoteRef`, preserve
  both; do not drop current PDF evidence behavior.

### Task 5: End-To-End Minimal Caller Test

Add backend tests that prove:

```text
flag false -> ChatHandler calls ProductConversationService, not reading service
flag true -> first chat turn calls ProductReadingConversationService
flag true + referenceFocus.sourceQuoteRef -> clickedSourceQuoteRefs reaches ProductReadingConversationService
flag true + typed "[1]" without sourceQuoteRef -> no clickedSourceQuoteRefs
flag false + referenceFocus.sourceQuoteRef -> ProductConversationService path unchanged
sourceQuoteRef-only focus with referenceNumber -> does not require paperId detail resolution
sourceQuoteRef-only focus without referenceNumber -> is still recognized as structured focus
reading result references with sourceQuoteRef are serialized to generation state
reading result references with sourceQuoteRef are persisted to MySQL conversation history
```

Add frontend verification:

```text
pnpm typecheck
```

If a focused component test harness exists, add a source-quote chip interaction
test. If not, keep the slice to type-level and backend integration coverage.

## Verification

Focused backend tests:

```bash
mvn -q -Dtest=ChatWebSocketHandlerTest test
mvn -q -Dtest=ChatHandlerProductHarnessTest,ProductReadingConversationServiceTest test
mvn -q -Dtest=ProductReadingReActHarnessTest,ProductReadingToolRegistryTest,ProductReadingToolAdapterTest test
```

Legacy isolation tests:

```bash
mvn -q -Dtest=ProductConversationServiceTest,ProductReActHarnessTest,ProductToolRegistryTest test
```

Frontend checks:

```bash
cd frontend && pnpm typecheck
```

Full gates:

```bash
mvn -q -DskipTests test
mvn -q test
git diff --check
```

Search gates:

```bash
rg -n "ProductReadingConversationService|ProductReadingReactProperties|ProductReadingReActHarness|ProductReadingToolRegistry" src/main/java/com/yizhaoqi/smartpai/service/ProductConversationService.java
rg -n "ProductReActHarness|ProductToolRegistry|ProductConversationService|ProductMemoryService|\\bConversationService\\b" src/main/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarness.java src/main/java/com/yizhaoqi/smartpai/service/ProductReadingConversationService.java
rg -n "clickedSourceQuoteRefs" src/main/java frontend/src
```

Expected:

- First two search gates produce no matches.
- Third search gate shows only explicit caller-anchor plumbing, not user-text
  parsing or legacy memory authorization.

## Acceptance Criteria

- With `paperloom.react.reading-phase1.enabled=false`, existing normal product
  chat behavior is unchanged, including legacy reference-number follow-up.
- With `paperloom.react.reading-phase1.enabled=true`, the existing chat caller
  can invoke `ProductReadingConversationService`.
- A reading answer can stream and persist references containing
  `sourceQuoteRef` in both generation state and conversation history.
- The frontend can render an actionable citation chip backed by
  `sourceQuoteRef`.
- Clicking that chip sends `referenceFocus.sourceQuoteRef` in the next chat
  request.
- The backend converts the clicked source quote focus into
  `clickedSourceQuoteRefs`.
- A Source Quote-only focus with no `paperId` is accepted as a reading anchor
  and does not fail legacy reference-detail resolution.
- A Source Quote-only focus without `referenceNumber` is still treated as a
  structured clicked-source focus.
- The reading harness can call `trace_source_quotes` for that clicked ref and
  produce a source-quoted follow-up answer.
- User-typed `[1]` or `source_quote_...` still does not authorize tracing.
- `ProductConversationService`, `ProductReActHarness`, and
  `ProductToolRegistry` remain isolated from the reading path.
- No new database schema is required.

## Next Slice After This

After this caller loop passes, decide whether to:

1. build a richer Source Quote evidence-detail panel,
2. add display citation-number follow-up resolution,
3. promote the reading harness beyond the disabled experiment flag, or
4. add source expansion / nearby-context reading for traced Source Quotes.

Do not start those until the clicked Source Quote caller loop is verified.
