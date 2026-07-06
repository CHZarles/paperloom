# Product Reading Source Quote Minimal Loop Plan

Date: 2026-07-06

Status: planned next slice

Baseline commit: `3ca2b3a feat: add paper reading retrieval minimal loop`

## Goal

Build the smallest next closed loop for the isolated Product Reading path:

```text
search_paper_candidates
-> find_reading_locations
-> read_locations
-> sourceQuoteRef-backed final answer
```

The loop remains disabled by default and stays on the separate reading path:

```text
ProductReadingConversationService
-> ProductReadingReActHarness
-> ProductReadingToolRegistry
```

Do not merge this path into `ProductConversationService`, `ProductReActHarness`,
`ProductToolRegistry`, `ChatHandler`, or the frontend in this slice.

## Current Evidence

- Phase 1 already exposes `search_paper_candidates` and `find_reading_locations`
  through the isolated reading registry.
- Phase 1 can return paper candidates and reading-location navigation.
- Phase 1 intentionally cannot answer paper-content questions because it has no
  Source Quotes.
- The next required product-reading tool in the long-term catalog is
  `read_locations`.
- Current persisted Reading Model sources are enough for a minimal reader:
  `PaperLocation`, `PaperPage`, `PaperSection`, and `PaperReadingElement`.

## Grill Decisions

### 1. What is the next project step?

Recommended answer:

Add `read_locations` and Source Quote persistence before any UI or normal chat
integration.

Adopted.

Reason: a user-visible route without Source Quotes would still only navigate.
The missing product capability is citeable paper-content reading.

### 2. What counts as the smallest closed loop?

Recommended answer:

A service-level Product Reading ReAct integration test is enough for this slice:
the LLM calls the three reading tools in one turn and the harness returns a final
answer grounded in returned `sourceQuoteRef` values.

Adopted.

Do not add a WebSocket or HTTP route yet. That is the next slice after the
source-quote loop is proven.

### 3. Should this implement `trace_source_quotes`?

Recommended answer:

No.

Adopted.

`trace_source_quotes` is the follow-up slice for clicked source chips and
previously read Source Quotes. This slice only needs Source Quotes returned by
the current turn's `read_locations`.

### 4. Is MySQL persistence required, or can refs be in memory?

Recommended answer:

Use MySQL now.

Adopted.

`sourceQuoteRef` identity must survive duplicate reads and later trace work.
Redis or turn-local memory is not acceptable for the idempotency mapping.

### 5. Is the conversation Source Quote registry required now?

Recommended answer:

Create and write the registry now, but do not rely on it for follow-up answers
until `trace_source_quotes` exists.

Adopted.

This keeps the Phase 2 data boundary correct without expanding the loop into
history, memory, clicked source chips, or old conversation reference mappings.

### 6. Which location types must read in the minimum slice?

Recommended answer:

Support PAGE and SECTION text as required. Support TABLE and FIGURE best-effort
from retained `PaperReadingElement` text or caption. If a selected location has
no readable original text, return a read status instead of fabricating content.

Adopted.

### 7. Should the answer type enum be renamed now?

Recommended answer:

No.

Adopted.

Use the existing code-level `EVIDENCE_ANSWER` as the source-quoted answer type
inside the isolated reading harness. Validate `sourceQuoteRef` support there.
Do not start a broader enum migration in this slice.

### 8. Should the model be allowed to cite location previews?

Recommended answer:

No.

Adopted.

Only `sourceQuoteRef` values returned by `read_locations` can ground
paper-content claims. Paper cards, location refs, and previews remain navigation
only.

### 9. Should hidden `paperHandle` values be accepted?

Recommended answer:

No.

Adopted.

The isolated reading harness must reject `find_reading_locations` calls that use
`paperHandle` values not disclosed by this turn's `search_paper_candidates`
result. This slice has no clicked-paper entry point, so there is no other valid
disclosure source yet.

### 10. How should `sourceQuoteRef` values be generated?

Recommended answer:

Generate random opaque refs and let the MySQL idempotency key decide reuse.

Adopted.

`sourceQuoteRef` must use the `source_quote_` prefix followed by a random,
non-meaningful token. It must not encode or derive from raw `paperId`,
`modelVersion`, `locationRef`, `splitIndex`, or `contentHash`. Duplicate reads
reuse the existing row and its existing `sourceQuoteRef`.

### 11. Should words like `score` and `rank` be globally forbidden in final text?

Recommended answer:

No.

Adopted.

Tool payloads must not expose internal score/rank fields, but source-quoted
paper content and final answers may legitimately discuss scores, rankings, or
ranked results when supported by `sourceQuoteRef` values. Validation should
reject internal keys and raw ids, not ordinary words that appear in paper text.

### 12. Should reading traces keep the Phase 1 label?

Recommended answer:

No.

Adopted.

Source Quote turns must be distinguishable from navigation-only Phase 1 turns.
Keep the artifact family `PRODUCT_READING_REACT_TURN`, but add an explicit
source-quote stage marker and bump the trace version.

## Non-Goals

- No `trace_source_quotes`.
- No normal product chat wiring.
- No frontend route or UI.
- No legacy `ProductToolRegistry` changes.
- No Elasticsearch Reading Chunk index.
- No semantic reranking, hidden query rewrite, or summarization.
- No source quote follow-up from history or memory.
- No final public answer-type rename.
- No source quote creation from paper-card previews, location previews, or
  `searchableText`.
- No accepting hidden `paperHandle` or `locationRef` values outside the current
  tool-disclosure chain.
- No using the conversation Source Quote registry as final-answer support yet;
  this slice writes it for the later `trace_source_quotes` slice.

## Implementation Tasks

### Task 1: Persist Source Quotes

Create:

- `src/main/java/com/yizhaoqi/smartpai/model/PaperSourceQuote.java`
- `src/main/java/com/yizhaoqi/smartpai/repository/PaperSourceQuoteRepository.java`
- `src/main/java/com/yizhaoqi/smartpai/model/ConversationSourceQuote.java`
- `src/main/java/com/yizhaoqi/smartpai/repository/ConversationSourceQuoteRepository.java`

Update:

- `docs/databases/ddl.sql`

Minimum `paper_source_quotes` fields:

- `source_quote_ref`
- `paper_id`
- `model_version`
- `location_ref`
- `location_type`
- `page_number`
- `page_end_number`
- `section_title`
- `content_kind`
- `content`
- `content_hash`
- `split_policy_version`
- `split_index`
- `source_span_json`
- `created_at`

Minimum idempotency key:

```text
paper_id + model_version + location_ref + split_policy_version + split_index + content_hash
```

Minimum `conversation_source_quotes` fields:

- `conversation_id`
- `source_quote_ref`
- `first_seen_turn_id`
- `user_id`
- `created_at`

Unique keys:

- `paper_source_quotes.source_quote_ref`
- source quote idempotency key
- `conversation_id + source_quote_ref`

Ref generation:

- New rows get `source_quote_` plus a random opaque token.
- The token must not be a hash or encoding of `paperId`, `modelVersion`,
  `locationRef`, `splitIndex`, `contentHash`, user id, org id, title, filename,
  or parser id.
- Duplicate reads first look up by the idempotency key. If a concurrent insert
  wins, catch the unique-key failure and re-read the existing row.

Repository methods:

- `PaperSourceQuoteRepository` must support lookup by `sourceQuoteRef` and by
  the full idempotency key.
- `ConversationSourceQuoteRepository` must support lookup by
  `conversationId + sourceQuoteRef`.
- Re-registering the same quote in the same conversation must keep the original
  `firstSeenTurnId`.

### Task 2: Add Deterministic Reading And Splitting

Create:

- `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingLocationReadService.java`

Update:

- `src/main/java/com/yizhaoqi/smartpai/repository/PaperPageRepository.java`
- `src/main/java/com/yizhaoqi/smartpai/repository/PaperSectionRepository.java`
- `src/main/java/com/yizhaoqi/smartpai/repository/PaperReadingElementRepository.java`

Responsibilities:

- Accept explicit `locationRefs` plus `ProductToolContext`.
- Resolve each location through `PaperLocationRepository`.
- Require the owning current Reading Model to be
  `READING_MODEL_READY`.
- Require paper visibility through `ProductPaperHandleService`.
- Require the location to belong to the current model version.
- Read original content only:
  - PAGE from `PaperPage.pageText`.
  - SECTION from `PaperSection.sectionText`.
  - TABLE from retained `PaperReadingElement.bodyText` or structured payload
    text.
  - FIGURE from retained `PaperReadingElement.captionText` or body text.
- Split deterministically by paragraph first, then fixed internal character
  windows.
- Upsert/reuse `PaperSourceQuote` rows by idempotency key.
- Register returned refs in `conversation_source_quotes`.
- Resolve `paperTitle` for output from durable paper data at read time; it is
  display metadata, not part of the Source Quote identity key.
- Return per-location status:
  `OK`, `EMPTY_LOCATION`, `CONTENT_TRUNCATED`, `UNREADABLE_LOCATION`,
  `CURRENT_LOCATION_NOT_FOUND`, or `LOCATION_UNAVAILABLE`.

Location resolution:

- Start with `PaperLocationRepository#findFirstByLocationRef(locationRef)`.
- Resolve the current model with
  `PaperReadingModelRepository#findFirstByPaperIdAndIsCurrentTrue(paperId)`.
- If the current model is missing, not `READING_MODEL_READY`, or has a different
  `modelVersion` from the selected location, return `CURRENT_LOCATION_NOT_FOUND`
  for that ref.
- PAGE reads use `paperId + modelVersion + pageNumber` against
  `PaperPageRepository`; blank page text or `TEXTLESS` status returns
  `EMPTY_LOCATION`.
- SECTION reads use `paperId + modelVersion + location.sourceObjectId` against
  `PaperSection.sectionId`; missing rows return `CURRENT_LOCATION_NOT_FOUND`.
- TABLE and FIGURE reads use
  `paperId + modelVersion + location.sourceObjectId` against
  `PaperReadingElement.readingElementId`; missing rows return
  `CURRENT_LOCATION_NOT_FOUND`.
- If a TABLE or FIGURE row exists but has no readable original body/caption text,
  return `EMPTY_LOCATION`.
- Do not infer a section/table/figure from page text when the owning row cannot
  be resolved.

Internal constants are allowed, but must not be exposed to the LLM:

- split policy version
- max locations per call
- max source quotes per location
- max quote characters
- max total quote characters

### Task 3: Expose `read_locations`

Modify:

- `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingToolRegistry.java`
- `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingToolAdapter.java`
- `src/main/java/com/yizhaoqi/smartpai/service/ReadingToolArgumentValidator.java`
- `src/main/java/com/yizhaoqi/smartpai/service/ReadingToolOutputMapper.java`

Tool surface becomes exactly:

```text
search_paper_candidates
find_reading_locations
read_locations
```

`read_locations` input:

```json
{
  "locationRefs": ["page_ref_..."]
}
```

Reject:

- `paperId`, `paperIds`, `modelVersion`, `chunkRef`, `readingElementId`
- `query`, `queryText`, `question`, `semanticNeed`, `readingNeed`,
  `subQuestions`, `coverageTargets`
- `limit`, `topK`, `pageSize`, `maxCandidates`, `maxChars`, `maxQuotes`,
  `maxCharsPerLocation`, `maxTotalChars`, `maxQuotesPerLocation`, `budget`
- `chunkSize`, `chunkOverlap`, `overlap`, `pageWindow`, `indexName`,
  `indexVersion`
- `splitPolicyVersion`, `contentHash`, `quoteKinds`, `sourceQuoteRef`
- ordinals or candidate-list references

LLM-visible output may include:

- `sourceQuoteRef`
- `locationRef`
- `paperHandle`
- `paperTitle`
- `locationType`
- `pageNumber`
- `pageEndNumber`
- `sectionTitle`
- `contentKind`
- `content`

LLM-visible output must not include:

- raw `paperId`
- `modelVersion`
- `readingElementId`
- parser ids
- `splitPolicyVersion`
- `contentHash`
- score/rank fields, matched fields, or routing diagnostics

### Task 4: Upgrade The Reading Harness

Modify:

- `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarness.java`
- `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarnessTest.java`

Required behavior:

- Require the three-tool surface in order:
  `search_paper_candidates`, `find_reading_locations`, `read_locations`.
- Prompt the LLM that `read_locations` is the only Source Quote tool in this
  slice.
- Track disclosed `paperHandle` values from `search_paper_candidates`.
- Track disclosed `locationRef` values from `find_reading_locations`.
- Track allowed `sourceQuoteRef` values from successful `read_locations`.
- Reject `find_reading_locations` calls for hidden `paperHandle` values unless a
  later clicked-paper entry point is added.
- Reject `read_locations` calls for hidden `locationRef` values unless a later
  explicit clicked-ref entry point is added.
- Allow `PRODUCT_STATE` answers only for candidate and navigation results.
- Allow `INSUFFICIENT_EVIDENCE` when no Source Quotes are returned.
- Allow `EVIDENCE_ANSWER` only when every cited `sourceQuoteRef` was returned by
  this turn's successful `read_locations`.
- For this reading path, `evidenceBasedClaims` entries must use
  `sourceQuoteRefs: ["source_quote_..."]`. Do not use legacy `evidenceRefs`,
  `evidenceRef`, or `citationRef` for Source Quote support.
- `EVIDENCE_ANSWER` requires at least one visible
  `{{sourceQuoteRef:source_quote_...}}` marker in `answer` and at least one
  `sourceQuoteRefs` entry in `evidenceBasedClaims`; every ref from both places
  must belong to this turn's support set.
- Render source quote markers to display citations and return `references`.
- Continue rejecting raw internal fields and model-generated numbered citations.
- Do not read `conversation_source_quotes` to authorize final answers in this
  slice. The final-answer support set is only this turn's successful
  `read_locations` output.
- Replace Phase 1's blanket rejection of any `sourceQuoteRef` text with strict
  marker validation: allow only `{{sourceQuoteRef:source_quote_...}}` markers
  whose refs are in the current-turn support set.
- Replace Phase 1's blanket final-answer token ban for common words such as
  `score` and `rank` with structured validation: reject raw id/internal field
  keys, but allow ordinary paper-content words when the answer is supported by
  returned `sourceQuoteRef` values.

Citation convention for this isolated reading path:

```text
{{sourceQuoteRef:source_quote_...}}
```

The harness renders those markers to display numbers in `finalAnswerMarkdown`.

### Task 5: Update Reading Trace Metadata

Modify:

- `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingTraceRecorder.java`
- `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingTraceRecorderTest.java`

Required behavior:

- Keep `artifactType=PRODUCT_READING_REACT_TURN`.
- Bump `traceVersion` for the source-quote loop.
- Add `readingLoopStage=SOURCE_QUOTE_MVP`.
- Keep or replace `harnessKind`, but traces must distinguish source-quote turns
  from navigation-only `READING_PHASE1` turns.
- Trace payloads for source-quote turns include `read_locations` tool calls,
  returned `sourceQuoteRef` values, rendered `references`, and stop/result
  status.

### Task 6: Keep Service Isolation

Modify only if needed:

- `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingConversationService.java`
- `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingConversationServiceTest.java`

Invariants:

- No dependency from `ProductConversationService` to reading classes.
- No dependency from `ProductReadingConversationService` to
  `ProductConversationService`, `ProductReActHarness`, `ProductToolRegistry`,
  `ConversationService`, or `ProductMemoryService`.
- Empty history and memory remain the Phase 2 reading default.
- `paperloom.react.reading-phase1.enabled=false` still fails closed.

### Task 7: Verification

Focused tests:

```bash
mvn -q -Dtest=ProductReadingLocationReadServiceTest test
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
rg -n '"(paperId|modelVersion|readingElementId|splitPolicyVersion|contentHash|score|rank)"' src/main/java/com/yizhaoqi/smartpai/service/ProductReadingToolAdapter.java src/main/java/com/yizhaoqi/smartpai/service/ReadingToolOutputMapper.java src/main/java/com/yizhaoqi/smartpai/service/ProductReadingLocationReadService.java src/main/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarness.java
```

The first two search gates should produce no matches. The third should only
match explicit forbidden-argument validation. It must not match output mapper
keys or `read_locations` response construction; absence from LLM-visible output
is also asserted by tests below.

Required negative tests:

- hidden `paperHandle` in `find_reading_locations` is rejected by the harness.
- hidden `locationRef` in `read_locations` is rejected by the harness.
- duplicate `read_locations` calls reuse the same `sourceQuoteRef`.
- final answer using an unreturned `sourceQuoteRef` is rejected.
- final answer with `sourceQuoteRefs` in claims but no visible marker is rejected.
- final answer with visible marker but no `sourceQuoteRefs` claim support is
  rejected.
- final answer mentioning ordinary words such as "score" passes when supported
  by a returned `sourceQuoteRef`.
- serialized LLM-visible `read_locations` output does not contain raw ids,
  model versions, parser ids, split policy, content hashes, or score/rank
  fields.

## Acceptance Criteria

- `ProductReadingToolRegistry#listTools()` returns exactly
  `search_paper_candidates`, `find_reading_locations`, and `read_locations`.
- `read_locations` accepts only explicit `locationRefs`.
- `read_locations` resolves each selected location through the current READY
  Reading Model.
- `read_locations` rejects stale `locationRefs` from older Reading Model
  versions.
- `read_locations` checks paper visibility and fixed search scope.
- `read_locations` reads original text only, not `searchableText`.
- Duplicate reads of the same split reuse the same `sourceQuoteRef`.
- New `sourceQuoteRef` values are random opaque tokens and do not encode product
  or parser ids.
- Returned Source Quotes are registered in `conversation_source_quotes`.
- `read_locations` returns statuses for unreadable or unavailable locations.
- No LLM-visible reading tool output exposes raw ids, model versions, parser ids,
  split policy, content hashes, or score/rank fields.
- The reading harness rejects hidden `paperHandle` values before
  `find_reading_locations`.
- The reading harness rejects hidden `locationRef` values before
  `read_locations`.
- The reading harness can complete a three-tool source-quoted answer.
- Source-quoted `EVIDENCE_ANSWER` claims use `sourceQuoteRefs`, not legacy
  `evidenceRefs`.
- Source-quoted `EVIDENCE_ANSWER` output has visible sourceQuoteRef markers and
  claim-level `sourceQuoteRefs`, all from this turn's `read_locations` support
  set.
- The reading harness rejects unreturned `sourceQuoteRef` values.
- The reading harness rejects location previews as evidence.
- The reading harness still rejects model-generated numbered citations.
- The reading harness allows source-quoted answers to discuss scores or rankings
  when those words come from supported paper content.
- Reading traces identify source-quote-loop turns separately from navigation-only
  Phase 1 turns.
- Normal product chat remains unchanged and isolated from this reading path.
- `trace_source_quotes`, UI wiring, and normal chat integration remain
  unimplemented in this slice.

## Next Slice After This

After this plan passes, add `trace_source_quotes` for clicked source chips and
conversation follow-ups. Only after trace is working should the project consider
a reading-specific HTTP/WebSocket entry point or a controlled merge into the
main product chat UX.
