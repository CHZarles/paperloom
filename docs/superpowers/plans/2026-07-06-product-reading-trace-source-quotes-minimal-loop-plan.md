# Product Reading Trace Source Quotes Minimal Loop Plan

Date: 2026-07-06

Status: planned next slice

Baseline commit: `ebbe2c6 fix: keep read locations output quote-only`

## Goal

Build the smallest deterministic follow-up loop for the isolated Product Reading
path:

```text
previous read_locations
-> conversation_source_quotes registration
-> explicit clickedSourceQuoteRefs on a later reading turn
-> trace_source_quotes
-> sourceQuoteRef-backed follow-up answer
```

The loop remains service-level only and stays on the separate reading path:

```text
ProductReadingConversationService
-> ProductReadingReActHarness
-> ProductReadingToolRegistry
```

Do not merge this path into `ProductConversationService`, `ProductReActHarness`,
`ProductToolRegistry`, `ChatHandler`, WebSocket, HTTP routes, or the frontend in
this slice.

## Current Evidence

- The source-quote MVP already exposes `search_paper_candidates`,
  `find_reading_locations`, and `read_locations` in the isolated reading
  registry.
- `read_locations` persists `PaperSourceQuote` rows and writes
  `conversation_source_quotes`.
- The reading harness only authorizes final answers from Source Quotes returned
  by successful tools in the current turn.
- `ProductReadingConversationService` already has an `effectiveScope` overload
  that is not used by the reading harness yet.
- The product requirements identify deterministic follow-up from citations and
  source chips as a core capability.

## Grill Decisions

### 1. What is the next project step?

Recommended answer:

Add `trace_source_quotes` to the isolated reading path before UI, normal chat
integration, or richer history/memory follow-up.

Adopted.

Reason: the previous slice can create and cite Source Quotes, but cannot use
previously read quotes in a later turn. The smallest missing product capability
is deterministic continuation from a known Source Quote.

### 2. What counts as the smallest closed loop?

Recommended answer:

A service-level Product Reading ReAct integration test is enough:

```text
turn 1 returns a sourceQuoteRef from read_locations
turn 2 receives that ref as explicit clickedSourceQuoteRefs
turn 2 LLM calls trace_source_quotes
turn 2 final answer cites the returned sourceQuoteRef
```

Adopted.

Do not add a browser click handler, WebSocket event, HTTP endpoint, or normal
chat route yet.

### 3. Should user text like "explain [1]" be resolved from history now?

Recommended answer:

No.

Adopted.

This slice uses only explicit caller-provided `clickedSourceQuoteRefs`. It does
not parse display citation numbers, scan old assistant markdown, load full
conversation history, or rely on memory. Numbered follow-up resolution is a
later slice once the UI or chat route passes stable source-chip anchors.

### 4. Should `conversation_source_quotes` authorize final answers directly?

Recommended answer:

No.

Adopted.

The registry only authorizes the `trace_source_quotes` tool to return a quote.
The final-answer support set remains current-turn tool output from
`read_locations` and `trace_source_quotes`.

### 5. Which refs may `trace_source_quotes` accept?

Recommended answer:

Only explicit `sourceQuoteRefs` that were disclosed to the harness as
`clickedSourceQuoteRefs` for this turn, plus any refs returned by
`read_locations` earlier in the same turn if needed.

Adopted.

The harness must reject arbitrary refs invented by the model or typed into the
user message unless they are also present in the explicit clicked-ref anchor set.

### 6. Should stale Reading Model versions invalidate existing Source Quotes?

Recommended answer:

No.

Adopted.

Persisted Source Quotes are durable evidence snapshots. `trace_source_quotes`
reads the stored quote content from `paper_source_quotes`; it does not require
the old `locationRef` to resolve in the current Reading Model. It must still
check conversation registration, paper visibility, and locked scope.

### 7. Should tracing create new Source Quotes or resplit content?

Recommended answer:

No.

Adopted.

Tracing returns existing persisted quote rows only. New quote creation remains
owned by `read_locations`.

### 8. Should trace output expose raw ids or internals?

Recommended answer:

No.

Adopted.

LLM-visible trace output may include the same display-safe fields as
`read_locations`: `sourceQuoteRef`, `paperHandle`, `paperTitle`, `locationRef`,
`locationType`, `pageNumber`, `pageEndNumber`, `sectionTitle`, `contentKind`,
and `content`. It must not expose `paperId`, `modelVersion`, `contentHash`,
`splitPolicyVersion`, parser ids, score/rank fields, or routing diagnostics.

### 9. Should the tool surface become the full long-term reading catalog?

Recommended answer:

No.

Adopted.

For this slice, the isolated reading tool surface becomes exactly:

```text
search_paper_candidates
find_reading_locations
read_locations
trace_source_quotes
```

No inspect-page, visual crop, semantic reranking, source expansion, or normal
product tool migration yet.

### 10. Should ordinary words such as score and rank be forbidden in follow-up answers?

Recommended answer:

No.

Adopted.

As in the source-quote MVP, reject internal score/rank fields in tool output,
but allow paper text and final answers to discuss scores or rankings when
supported by returned Source Quotes.

## Non-Goals

- No frontend source-chip click UI.
- No WebSocket or HTTP route.
- No normal product chat wiring.
- No `ProductConversationService`, `ProductReActHarness`, or
  `ProductToolRegistry` changes.
- No display citation number resolution such as `[1]`.
- No conversation-history or memory follow-up.
- No parsing raw `source_quote_...` values from user text as authorization.
- No new Source Quote creation inside `trace_source_quotes`.
- No Reading Model current-version requirement for already persisted Source
  Quotes.
- No visual asset, page screenshot, bbox, table crop, or figure crop tracing.
- No source quote expansion beyond the selected persisted quote rows.

## Implementation Tasks

### Task 1: Add Source Quote Trace Service

Create:

- `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingSourceQuoteTraceService.java`
- `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingSourceQuoteTraceServiceTest.java`

Responsibilities:

- Accept explicit `sourceQuoteRefs` plus `ProductToolContext`.
- Deduplicate and cap refs with internal constants.
- For each ref, require
  `ConversationSourceQuoteRepository#findFirstByConversationIdAndSourceQuoteRef`.
- Resolve the quote through
  `PaperSourceQuoteRepository#findFirstBySourceQuoteRef`.
- Check paper visibility and locked scope through `ProductPaperHandleService`.
- Resolve `paperHandle` and `paperTitle` at trace time for display metadata.
- Return existing persisted quote content only; do not reread page/section/table
  content and do not resplit.
- Return per-ref status:
  `OK`, `SOURCE_QUOTE_NOT_IN_CONVERSATION`, `SOURCE_QUOTE_NOT_FOUND`, or
  `SOURCE_QUOTE_UNAVAILABLE`.

LLM-visible quote output may include:

- `sourceQuoteRef`
- `paperHandle`
- `paperTitle`
- `locationRef`
- `locationType`
- `pageNumber`
- `pageEndNumber`
- `sectionTitle`
- `contentKind`
- `content`

LLM-visible quote output must not include:

- raw `paperId`
- `modelVersion`
- `contentHash`
- `splitPolicyVersion`
- parser ids
- score/rank fields
- matched fields
- routing diagnostics

### Task 2: Pass Explicit Clicked Source Quote Anchors

Modify:

- `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingConversationService.java`
- `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingConversationServiceTest.java`

Use the existing `effectiveScope` service-level parameter as the temporary
reading experiment input.

Accepted key:

```text
clickedSourceQuoteRefs
```

Behavior:

- If `effectiveScope.clickedSourceQuoteRefs` is absent, keep the current default:
  empty history and empty memory/anchors.
- If present, sanitize it to distinct `source_quote_...` strings.
- Pass the sanitized clicked refs into the isolated reading harness as a small
  internal turn-anchor map.
- Do not treat arbitrary conversation memory as citation authorization.
- Do not expose clicked refs to the legacy product chat path.

Implementation note:

The smallest code change can place the sanitized anchor list in
`ProductTurnRequest.memory()` under `clickedSourceQuoteRefs`. The harness must
treat only this explicit key as turn anchors, and final answer authorization must
still require successful `trace_source_quotes` output.

### Task 3: Expose `trace_source_quotes`

Modify:

- `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingToolRegistry.java`
- `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingToolAdapter.java`
- `src/main/java/com/yizhaoqi/smartpai/service/ReadingToolArgumentValidator.java`

Tool surface becomes exactly:

```text
search_paper_candidates
find_reading_locations
read_locations
trace_source_quotes
```

`trace_source_quotes` input:

```json
{
  "sourceQuoteRefs": ["source_quote_..."]
}
```

Reject:

- `paperId`, `paperIds`, `modelVersion`, `locationRef`, `locationRefs`
- `chunkRef`, `readingElementId`, `pageNumber`, `pageWindow`
- `query`, `queryText`, `question`, `semanticNeed`, `readingNeed`
- `limit`, `topK`, `pageSize`, `maxQuotes`, `maxChars`, `budget`
- `splitPolicyVersion`, `contentHash`, `quoteKinds`
- ordinals, display citation numbers, and candidate-list references

Return `ProductToolEffect.EVIDENCE` on successful trace calls.

### Task 4: Upgrade The Reading Harness

Modify:

- `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarness.java`
- `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarnessTest.java`

Required behavior:

- Require the four-tool surface in order:
  `search_paper_candidates`, `find_reading_locations`, `read_locations`,
  `trace_source_quotes`.
- Prompt the LLM that `trace_source_quotes` is the only way to use previously
  clicked Source Quotes.
- Track `clickedSourceQuoteRefs` from the explicit reading turn anchors.
- Reject `trace_source_quotes` calls for refs outside the clicked anchor set
  unless they were returned earlier by `read_locations` in the same turn.
- Track Source Quotes returned by both `read_locations` and
  `trace_source_quotes`.
- Allow `EVIDENCE_ANSWER` only when every cited ref came from successful
  current-turn `read_locations` or `trace_source_quotes` output.
- Continue requiring visible
  `{{sourceQuoteRef:source_quote_...}}` markers and claim-level
  `sourceQuoteRefs`.
- Continue rejecting legacy `evidenceRefs`, `evidenceRef`, `citationRef`,
  model-generated numbered citations, raw internal fields, and location previews
  as evidence.
- Continue allowing ordinary source-backed paper words such as score or rank.

### Task 5: Update Trace Metadata

Modify:

- `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingTraceRecorder.java`
- `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingTraceRecorderTest.java`

Required behavior:

- Keep `artifactType=PRODUCT_READING_REACT_TURN`.
- Bump trace version for the trace-source-quotes loop.
- Add or update stage marker:

```text
readingLoopStage=TRACE_SOURCE_QUOTES_MVP
harnessKind=READING_TRACE_SOURCE_QUOTES_MVP
```

- Trace payloads include `trace_source_quotes` tool calls, returned
  `sourceQuoteRef` values, rendered `references`, stop reason, and result
  status.

### Task 6: Keep Service Isolation

Invariants:

- No dependency from `ProductConversationService` to reading classes.
- No dependency from `ProductReadingConversationService` to
  `ProductConversationService`, `ProductReActHarness`, `ProductToolRegistry`,
  `ConversationService`, or `ProductMemoryService`.
- `paperloom.react.reading-phase1.enabled=false` still fails closed.
- No normal chat or frontend caller uses the new clicked-ref effective-scope key
  in this slice.

## Verification

Focused tests:

```bash
mvn -q -Dtest=ProductReadingSourceQuoteTraceServiceTest test
mvn -q -Dtest=ProductReadingToolRegistryTest,ProductReadingToolAdapterTest,ReadingToolArgumentValidatorTest,ReadingToolOutputMapperTest test
mvn -q -Dtest=ProductReadingReActHarnessTest,ProductReadingConversationServiceTest,ProductReadingTraceRecorderTest test
```

Legacy isolation tests:

```bash
mvn -q -Dtest=ProductConversationServiceTest,ProductReActHarnessTest,ProductToolRegistryTest test
```

Full gates:

```bash
mvn -q -DskipTests test
mvn -q test
git diff --check
```

Search gates:

```bash
rg -n "ProductReadingReActHarness|ProductReadingConversationService|ProductReadingToolRegistry" src/main/java/com/yizhaoqi/smartpai/service/ProductConversationService.java
rg -n "ProductReActHarness|ProductToolRegistry|ProductConversationService|ProductMemoryService|\\bConversationService\\b" src/main/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarness.java src/main/java/com/yizhaoqi/smartpai/service/ProductReadingConversationService.java
rg -n '"(paperId|modelVersion|readingElementId|splitPolicyVersion|contentHash|score|rank)"' src/main/java/com/yizhaoqi/smartpai/service/ProductReadingToolAdapter.java src/main/java/com/yizhaoqi/smartpai/service/ReadingToolOutputMapper.java src/main/java/com/yizhaoqi/smartpai/service/ProductReadingLocationReadService.java src/main/java/com/yizhaoqi/smartpai/service/ProductReadingSourceQuoteTraceService.java src/main/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarness.java
```

The first two search gates should produce no matches. The third should not match
LLM-visible output construction.

Required negative tests:

- `trace_source_quotes` rejects refs not present in
  `conversation_source_quotes`.
- `trace_source_quotes` rejects missing `paper_source_quotes` rows.
- `trace_source_quotes` rejects invisible or out-of-scope papers.
- the harness rejects hidden `sourceQuoteRef` values before
  `trace_source_quotes`.
- the harness rejects final answers that cite clicked refs before a successful
  `trace_source_quotes` call returns them.
- final answers with marker but no claim-level `sourceQuoteRefs` are rejected.
- final answers with claim-level `sourceQuoteRefs` but no visible marker are
  rejected.
- legacy `evidenceRefs` / `citationRef` support is rejected.
- numbered display citations such as `[1]` are rejected when model-generated.
- serialized LLM-visible trace output does not contain raw ids, model versions,
  split policy, content hashes, parser ids, score/rank fields, matched fields,
  or routing diagnostics.

## Acceptance Criteria

- `ProductReadingToolRegistry#listTools()` returns exactly
  `search_paper_candidates`, `find_reading_locations`, `read_locations`, and
  `trace_source_quotes`.
- `trace_source_quotes` accepts only explicit `sourceQuoteRefs`.
- `ProductReadingConversationService` can pass explicit
  `clickedSourceQuoteRefs` into the isolated reading harness without using
  history, memory, normal chat, WebSocket, HTTP, or frontend code.
- `trace_source_quotes` requires the selected ref to be registered in the same
  conversation.
- `trace_source_quotes` checks paper visibility and locked scope.
- `trace_source_quotes` returns stored persisted Source Quote content, not
  reread or newly split content.
- Existing Source Quotes remain traceable even if their old `locationRef` is no
  longer current, as long as the paper is still visible and in scope.
- The harness rejects hidden `sourceQuoteRef` values before
  `trace_source_quotes`.
- The harness can complete a follow-up answer from a clicked Source Quote.
- Source-quoted follow-up answers use visible sourceQuoteRef markers and
  claim-level `sourceQuoteRefs`, all from current-turn trace/read support.
- The harness rejects clicked refs as final-answer support until
  `trace_source_quotes` returns them in the current turn.
- Normal product chat remains unchanged and isolated from this reading path.
- UI wiring, normal chat integration, display citation number resolution, and
  conversation-history follow-up remain unimplemented in this slice.

## Next Slice After This

After this plan passes, add the smallest caller integration that supplies
`clickedSourceQuoteRefs` from a real source chip click to the isolated reading
entry point. Only after clicked source chips work should the project consider
display citation number follow-up, normal product chat integration, or broader
conversation memory/history support.
