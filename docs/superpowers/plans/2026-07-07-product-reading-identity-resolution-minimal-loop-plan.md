# Product Reading Identity Resolution Minimal Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the next smallest Product Reading product-state loop: `find_papers_by_identity`, so a user can refer to a specific READY paper by title, filename, DOI, arXiv id, author, or year before using the existing outline, location, read, and trace tools.

**Architecture:** Keep the isolated Product Reading path. Add exactly one remaining historical tool in this slice. `find_papers_by_identity` resolves specific-paper identity hints against the current READY scoped paper set, returns paper cards and match reasons only, and discloses handles for reading tools only when the identity result is unambiguous. Paper-content claims still require `read_locations` or `trace_source_quotes`.

**Tech Stack:** Java 17, Spring Boot, Spring Data JPA, Jackson, JUnit 5, Mockito, Maven.

## Global Constraints

- Keep the legacy Product path unchanged: do not modify `ProductReActHarness`, `ProductToolRegistry`, `ProductConversationService`, or `ChatHandler`.
- Keep the Product Reading entry point behind `paperloom.react.reading-phase1.enabled=true`; do not promote the flag.
- Add only `find_papers_by_identity` in this slice. Do not add new UI behavior, clicked-row plumbing, pagination, ranking, taxonomy, or content-reading features.
- The Product Reading tool surface after this slice is exactly nine tools in this order:

```text
get_session_state
list_papers
search_paper_candidates
find_papers_by_identity
get_paper_outline
list_paper_locations
find_reading_locations
read_locations
trace_source_quotes
```

- `find_papers_by_identity` is deterministic identity resolution, not browse and not semantic search.
- It returns only current READY Reading Model papers visible to the user and inside the locked `SourceScope`.
- It does not read paper content, Source Quote content, `sectionText`, page/table/figure content, or `searchableText`.
- It does not expose raw `paperId`, `modelVersion`, internal SQL ids, `chunkRef`, ranking scores, abstracts as evidence, citation refs, or Source Quote text.
- Unambiguous `paperHandle` values returned by `find_papers_by_identity` may authorize `get_paper_outline`, `list_paper_locations`, and `find_reading_locations` in the same turn.
- Ambiguous matches are product-state choices only. They should not authorize reading tools until the user clarifies or clicks a specific paper row in a later path.
- Returned paper cards are navigation only and cannot support paper-content claims.

---

## Grill Outcomes

The user requested recommended answers, so the recommended answers below are adopted without another interview round.

### 1. Is the current eight-tool Product Reading surface the full historical target?

Recommended answer: no.

Adopted. After `get_session_state` and `list_papers`, the remaining historical Product Reading tool is `find_papers_by_identity`.

### 2. Should the next slice add anything besides `find_papers_by_identity`?

Recommended answer: no.

Adopted. The next smallest closed loop is one tool. Adding UI clicked-row plumbing, pagination, taxonomy, or richer search at the same time would mix identity resolution with unrelated product surfaces.

### 3. Why is `find_papers_by_identity` still needed if `list_papers` already has metadata filters?

Recommended answer: because direct paper references need explicit identity semantics and ambiguity handling.

Adopted. `list_papers` is browse/filter. `find_papers_by_identity` is for user utterances such as "LoRA 那篇", a DOI, an arXiv id, or a filename. It can return `AMBIGUOUS` instead of letting the model guess or misuse semantic search.

### 4. Should identity lookup accept semantic fields such as `queryText`, `topicText`, or `readingNeed`?

Recommended answer: no.

Adopted. Topic discovery remains `search_paper_candidates`; deterministic browsing remains `list_papers`; paper identity resolution accepts identity hints only.

### 5. Should a year-only lookup be accepted?

Recommended answer: no.

Adopted. `year` is useful as a narrowing hint, but by itself it is browse-like and too broad for identity resolution. Require at least one textual or external-id identity hint.

### 6. Should ambiguous identity matches disclose handles to the harness authorization state?

Recommended answer: no.

Adopted. Ambiguous results may display product-state choices, but the harness should not allow `get_paper_outline`, `list_paper_locations`, or `find_reading_locations` from those handles until the ambiguity is resolved. This prevents accidental content reading from the wrong paper.

### 7. Should an unambiguous identity result authorize reading-location search?

Recommended answer: yes.

Adopted. A specific paper resolved by identity is an explicit paper selection. Its handle may feed `get_paper_outline`, `list_paper_locations`, and `find_reading_locations`. It still does not authorize Source Quote claims without `read_locations`.

### 8. Should identity output include abstracts, previews, scores, or rank?

Recommended answer: no.

Adopted. Identity result cards should mirror browse cards plus deterministic match reasons. No abstracts, previews, scores, rank, or paper content.

### 9. Should this slice change `trace_source_quotes` behavior?

Recommended answer: no.

Adopted. Traced paper handles still authorize deterministic outline/location listing only, not semantic `find_reading_locations`.

## Review Outcomes

The user requested recommended answers for review decisions, so the recommended answers below are adopted.

### 10. Should ambiguous identity results return paper handles at all?

Recommended answer: yes, but only as non-authorizing product-state choices.

Adopted. Ambiguous result cards may include `paperHandle` values so the UI can render concrete paper choices, but the harness must not add those handles to same-turn reading authorization state. The model should ask the user to clarify or choose a paper instead of reading from an ambiguous result.

### 11. Should `find_papers_by_identity` accept `paperHandle` or `paperHandles`?

Recommended answer: no.

Adopted. If the model already has a `paperHandle`, identity resolution is unnecessary; it should pass the handle directly to `get_paper_outline`, `list_paper_locations`, or `find_reading_locations` if authorized. `paperHandle` and `paperHandles` are forbidden identity arguments.

### 12. Should DOI and arXiv exact matching be byte-exact?

Recommended answer: no.

Adopted. Treat them as exact after deterministic canonicalization. DOI comparison trims, lowercases, and removes common `doi:` / `https://doi.org/` prefixes. arXiv comparison trims, lowercases, removes common `arxiv:` / arXiv URL prefixes, and removes a version suffix such as `v2`.

### 13. Should a strong external-id match override contradictory extra hints?

Recommended answer: no.

Adopted. Supplied identity hints combine with AND semantics. If a DOI matches one paper but an extra title or year hint contradicts it, return `NO_MATCH` rather than silently correcting the user or guessing.

### 14. Should duplicate DOI or arXiv matches be force-picked?

Recommended answer: no.

Adopted. Collapse duplicate rows that resolve to the same underlying Product Paper identity, but if multiple distinct READY scoped Product Papers still match, return `AMBIGUOUS` rather than choosing one.

### 15. Should `authorName + year` be allowed even though it may be broad?

Recommended answer: yes.

Adopted. Author and year are valid identity hints. They may still return `AMBIGUOUS`; ambiguity handling is the point of this tool.

### 16. Is the ambiguous-match authorization policy ADR-worthy?

Recommended answer: yes.

Adopted. It is an authorization boundary that may be surprising because ambiguous cards still contain handles. Record it in ADR 0009 so future work does not accidentally treat visible ambiguous handles as selected papers.

### 17. Does the domain glossary need tightening?

Recommended answer: yes.

Adopted. Add glossary language for `Identity Hint` and `Ambiguous Paper Identity Match` so future plans distinguish identity clues, paper handles, ambiguous product-state choices, and reading authorization.

## Tool Contract

### `find_papers_by_identity`

Input:

```json
{
  "identityHints": {
    "titleContains": "LoRA",
    "titleExact": "",
    "filenameContains": "",
    "filenameExact": "",
    "doiExact": "",
    "arxivIdExact": "",
    "authorName": "",
    "year": 2021
  }
}
```

Output:

```json
{
  "status": "OK",
  "ambiguous": false,
  "total": 1,
  "returned": 1,
  "matches": [
    {
      "ordinal": 1,
      "paperHandle": "paper_handle_abc",
      "title": "LoRA: Low-Rank Adaptation of Large Language Models",
      "originalFilename": "lora.pdf",
      "authors": ["Edward Hu"],
      "year": 2021,
      "venue": "ICLR",
      "matchReasons": ["TITLE_CONTAINS", "YEAR"],
      "catalogTopics": [],
      "paperTypes": []
    }
  ],
  "constraints": {
    "paperCardIsSourceQuote": false,
    "paperContentClaimsAllowed": false,
    "ambiguousMatchesAuthorizeReading": false
  }
}
```

Status values:

```text
OK
AMBIGUOUS
NO_MATCH
INVALID_ARGUMENT
```

Allowed identity hints:

```text
titleContains
titleExact
filenameContains
filenameExact
doiExact
arxivIdExact
authorName
year
```

Match reasons:

```text
TITLE_CONTAINS
TITLE_EXACT
FILENAME_CONTAINS
FILENAME_EXACT
DOI_EXACT
ARXIV_ID_EXACT
AUTHOR_NAME
YEAR
```

Rules:

- Requires an `identityHints` object.
- Requires at least one nonblank textual or external-id hint among `titleContains`, `titleExact`, `filenameContains`, `filenameExact`, `doiExact`, `arxivIdExact`, or `authorName`.
- `year` is optional and can narrow identity matches, but `year` alone is `INVALID_ARGUMENT`.
- Rejects unsupported top-level arguments outside `identityHints`.
- Rejects unsupported nested identity hints outside the allowed identity hint list.
- Rejects forbidden arguments at any depth:

```text
paperId, paperIds, paperRef, paperRefs,
paperHandle, paperHandles,
locationRef, locationRefs, sourceQuoteRef, sourceQuoteRefs,
modelVersion, indexVersion, indexName, chunkRef, readingElementId,
query, queryText, question, readingNeed, semanticNeed, topicText,
recommendationNeed, relatedTo,
ordinal, ordinals, candidateOrdinal, resultOrdinal,
page, pageSize, pageRange, limit, topK, maxCandidates, budget,
rerank, rerankEnabled, searchMode, score, rank
```

- Validate `year` as a positive integer when present.
- Canonicalize DOI and arXiv hints before exact comparison:
  - DOI: trim, lowercase, remove leading `doi:` and `https://doi.org/` / `http://doi.org/`.
  - arXiv id: trim, lowercase, remove leading `arxiv:`, remove common arXiv URL prefixes, and remove a trailing version suffix such as `v2`.
- Match only current READY Reading Model papers visible to the user and inside the locked `SourceScope`.
- Combine supplied hints with AND semantics.
- De-duplicate duplicate accessible rows that resolve to the same Product Paper identity before computing `total`.
- Sort deterministically by strongest identity reason first (`DOI_EXACT`, `ARXIV_ID_EXACT`, `FILENAME_EXACT`, `TITLE_EXACT`, `FILENAME_CONTAINS`, `TITLE_CONTAINS`, `AUTHOR_NAME`, `YEAR`), then title ascending, then year descending, then internal id as a hidden tie-breaker.
- Use an internal product cap such as `PaperCandidateSearchRequest.DEFAULT_PAPER_LIMIT`; do not expose pagination, `limit`, or rank controls.
- `total` is the filtered match count before cap; `returned` is the number of returned cards.
- If `total == 0`, return `status=NO_MATCH`, `ambiguous=false`, and an empty `matches` list.
- If `total == 1`, return `status=OK`, `ambiguous=false`, and disclose returned handles to the same-turn harness state.
- If `total > 1`, return `status=AMBIGUOUS`, `ambiguous=true`, and do not disclose returned handles to the same-turn harness state for reading tools.
- Paper cards are not Source Quotes.

## Task 1: Validator And Registry Contract

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ReadingToolArgumentValidator.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingToolRegistry.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/service/ReadingToolArgumentValidatorTest.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingToolRegistryTest.java`

- [x] Add `validateFindPapersByIdentity(Map<String, Object>)`.
- [x] Add parser helpers or records for `IdentityHints`.
- [x] Reject unsupported top-level arguments outside `identityHints`.
- [x] Reject unsupported nested identity hints outside the allowed identity hint list.
- [x] Reject forbidden arguments at any depth, including raw ids, paper handles, semantic fields, ordinals, page controls, output controls, retrieval tuning controls, `score`, and `rank`.
- [x] Reject missing `identityHints`, non-object `identityHints`, empty hints, and `year`-only hints.
- [x] Validate `year` as a positive integer.
- [x] Canonicalize DOI and arXiv id hints before validation handoff.
- [x] Register `find_papers_by_identity` after `search_paper_candidates` and before `get_paper_outline`.
- [x] Keep the schema closed with `additionalProperties=false` at the top level and inside `identityHints`.
- [x] Delegate validated calls to `ProductReadingToolAdapter`.

Verification:

```bash
mvn -q -Dtest=ReadingToolArgumentValidatorTest,ProductReadingToolRegistryTest test
```

Expected: PASS after implementation.

## Task 2: Adapter And Output Mapping

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingToolAdapter.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ReadingToolOutputMapper.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingToolAdapterTest.java`

- [x] Implement `findPapersByIdentity(IdentityHints, ProductToolContext)`.
- [x] Reuse the same READY scoped base set as `get_session_state` and `list_papers`: `PaperService#getAccessiblePapers(userId, null)`, locked `SourceScope`, and current `READING_MODEL_READY` Reading Model checks.
- [x] Do not use `PaperLibraryStatusService`, `PaperSearchabilityService`, `Paper.vectorizationStatus`, or legacy `paper_text_chunks` as the readiness source.
- [x] Filter deterministically by title, filename, DOI, arXiv id, author, and optional year.
- [x] Compare DOI and arXiv id through canonical forms, not raw byte-equality.
- [x] Compute deterministic `matchReasons` from the supplied hints.
- [x] De-duplicate duplicate accessible rows for the same Product Paper identity before computing `total`.
- [x] Sort by identity strength, then title, then year, then hidden internal id.
- [x] Cap returned paper cards by internal product policy.
- [x] Generate `paperHandle` values through `ProductPaperHandleService#handleForPaperId`.
- [x] Add mapper support for identity paper cards and constraints.
- [x] Ensure LLM-visible JSON omits raw `paperId`, `modelVersion`, internal ids, abstracts, scores, ranks, Source Quote text, and paper content.

Verification:

```bash
mvn -q -Dtest=ProductReadingToolAdapterTest test
```

Expected: PASS after implementation.

## Task 3: Harness Authorization And Prompt Policy

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarness.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarnessTest.java`

- [x] Update required tool surface to the exact nine-tool order.
- [x] Update system prompt:

```text
Use find_papers_by_identity for a specific paper by title, filename, DOI, arXiv id, author, or year.
find_papers_by_identity is not semantic search; use search_paper_candidates for topic discovery.
Identity paper cards are navigation only, not Source Quotes.
Unambiguous paper handles returned by find_papers_by_identity may be used with get_paper_outline, list_paper_locations, or find_reading_locations.
If find_papers_by_identity returns AMBIGUOUS, ask the user to clarify or choose a paper before reading content.
```

- [x] In `updateState`, disclose `paperHandle` values from `find_papers_by_identity.matches[]` to both semantic and deterministic paper-handle sets only when `ambiguous=false`.
- [x] Do not disclose ambiguous identity matches for reading tools.
- [x] Add tests that:
  - direct paper request calls `find_papers_by_identity` and accepts a `PRODUCT_STATE` identity answer.
  - unambiguous identity handles can feed `get_paper_outline`.
  - unambiguous identity handles can feed `list_paper_locations`.
  - unambiguous identity handles can feed `find_reading_locations`.
  - ambiguous identity results do not authorize outline, deterministic location listing, or semantic location search.
  - ambiguous identity result handles may appear in tool output but are not added to the harness authorization state.
  - hidden handles are still rejected before browse/search/identity/trace disclosure.
  - traced paper handles still do not authorize `find_reading_locations`.

Verification:

```bash
mvn -q -Dtest=ProductReadingReActHarnessTest test
```

Expected: PASS after implementation.

## Task 4: Verification And Isolation Gates

- [x] Run focused Product Reading tests:

```bash
mvn -q -Dtest=ReadingToolArgumentValidatorTest,ProductReadingToolRegistryTest,ProductReadingToolAdapterTest,ProductReadingReActHarnessTest test
```

- [x] Run compile gate:

```bash
mvn -q -DskipTests compile
```

- [x] Run isolation search:

```bash
rg -n "find_papers_by_identity|ProductReadingReActHarness|ProductReadingToolRegistry" src/main/java/com/yizhaoqi/smartpai/service/ProductConversationService.java src/main/java/com/yizhaoqi/smartpai/service/ProductToolRegistry.java src/main/java/com/yizhaoqi/smartpai/service/ProductReActHarness.java
```

Expected: no output. Do not include `list_papers` in this search because the legacy Product path already has a legacy `list_papers`; rely on `git diff --name-only` to verify the legacy files were not edited.

- [x] Run LLM-visible output safety search:

```bash
rg -n '"(paperId|modelVersion|readingElementId|sectionText|chunkRef|score|rank|abstract)"' src/main/java/com/yizhaoqi/smartpai/service/ProductReadingToolAdapter.java src/main/java/com/yizhaoqi/smartpai/service/ReadingToolOutputMapper.java src/main/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarness.java
```

Expected: no hits in Product Reading LLM-visible output map keys. Java variable names and repository calls may still use internal identifiers.

- [x] Run diff hygiene:

```bash
git diff --check
```

Expected: PASS.

## Completion Criteria

- `ProductReadingToolRegistry.listTools()` exposes exactly nine tools in the planned order.
- `find_papers_by_identity` accepts only `identityHints` and closed nested identity fields.
- `find_papers_by_identity` rejects semantic fields, raw ids, ordinals, page controls, output controls, and retrieval tuning controls.
- `find_papers_by_identity` rejects `paperHandle` and `paperHandles`; already-disclosed handles go directly to consuming tools.
- `find_papers_by_identity` rejects empty identity hints and `year`-only hints.
- `find_papers_by_identity` filters only deterministic identity metadata.
- `find_papers_by_identity` canonicalizes DOI and arXiv hints before exact comparison.
- `find_papers_by_identity` uses AND semantics for all supplied hints.
- `find_papers_by_identity` returns only current READY Reading Model papers visible to the user and inside the locked `SourceScope`.
- `find_papers_by_identity` reports `total` as filtered matches before cap and `returned` as returned card count.
- `find_papers_by_identity` returns paper cards with `paperHandle`, title, filename, authors as a list, year, venue, match reasons, and empty catalog/paper-type arrays.
- `find_papers_by_identity` does not expose abstracts, raw `paperId`, `modelVersion`, internal ids, scores, ranks, Source Quote text, or paper content.
- Unambiguous identity handles authorize `get_paper_outline`, `list_paper_locations`, and `find_reading_locations` in the same turn.
- Ambiguous identity handles do not authorize reading tools in the same turn.
- `trace_source_quotes` behavior remains unchanged: traced paper handles still do not authorize `find_reading_locations`.
- No legacy Product harness or registry behavior changes.
- Focused Product Reading tests pass.
- `mvn -q -DskipTests compile` passes.
- `git diff --check` passes.

## Deferred Work

This slice intentionally does not add:

```text
clicked paper-row memory plumbing
LLM-controlled pagination or limits
catalog topic / paper type identity filters
abstract previews
content summaries
UI changes
```

Recommended next slice after this one:

```text
clicked paper-row anchors for Product Reading
```

Reason: once identity resolution completes the historical tool catalog, clicked paper-row anchors can become the clean way to resolve ambiguous product-state choices without passing ordinals or raw ids.
