# Product Reading Session Browse Minimal Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the next smallest Product Reading product-state loop: `get_session_state` + `list_papers`, so the reading harness can answer scope/count questions and deterministically browse READY papers before using the existing outline, location, read, and trace tools.

**Architecture:** Keep the isolated Product Reading path. Add exactly two historical tools in this slice because they form one closed browse loop: state/count first, then deterministic paper cards. Do not add `find_papers_by_identity` yet. The new tools return only product-state navigation data and paper handles; paper-content claims still require `read_locations` or `trace_source_quotes`.

**Tech Stack:** Java 17, Spring Boot, Spring Data JPA, Jackson, JUnit 5, Mockito, Maven.

## Global Constraints

- Keep the legacy Product path unchanged: do not modify `ProductReActHarness`, `ProductToolRegistry`, `ProductConversationService`, or `ChatHandler`.
- Keep the Product Reading entry point behind `paperloom.react.reading-phase1.enabled=true`; do not promote the flag.
- Add only `get_session_state` and `list_papers` in this slice. Do not add `find_papers_by_identity`.
- The Product Reading tool surface after this slice is exactly eight tools in this order:

```text
get_session_state
list_papers
search_paper_candidates
get_paper_outline
list_paper_locations
find_reading_locations
read_locations
trace_source_quotes
```

- `get_session_state` is compact product state, not paper discovery.
- `list_papers` is deterministic browse/filter, not semantic search.
- Both tools return only current READY Reading Model papers visible to the user and inside the locked `SourceScope`.
- Neither tool reads paper content, Source Quote content, `sectionText`, page/table/figure content, or `searchableText`.
- Neither tool exposes raw `paperId`, `modelVersion`, internal SQL ids, `chunkRef`, ranking scores, abstracts as evidence, or citation refs.
- Returned `paperHandle` values from `list_papers` may authorize `get_paper_outline`, `list_paper_locations`, and `find_reading_locations` in the same turn.
- Returned `paperHandle` values from `list_papers` are navigation only and cannot support paper-content claims.

---

## Grill Outcomes

### 1. Is the current six-tool Product Reading surface the full historical target?

Recommended answer: no.

Adopted. After `get_paper_outline`, the remaining historical tools are `get_session_state`, `list_papers`, and `find_papers_by_identity`.

### 2. Should the next slice add all three remaining tools?

Recommended answer: no.

Adopted. `find_papers_by_identity` is useful, but it is not required to close the next product-state loop. Adding it now would mix deterministic browsing with specific identity resolution.

### 3. Is `get_session_state` alone the smallest next loop?

Recommended answer: no.

Adopted. It can answer scope/count questions, but it does not let the model select a paper for outline-guided reading. It is too small to advance the project workflow by itself.

### 4. Is `list_papers` alone the smallest next loop?

Recommended answer: no.

Adopted. Browsing without a compact scope/count tool keeps the model guessing about whether it should answer library-state questions from browse results. `get_session_state` and `list_papers` together close one clean loop:

```text
get_session_state
-> list_papers
-> get_paper_outline or list_paper_locations
-> read_locations
-> EVIDENCE_ANSWER when paper-content claims are needed
```

### 5. Should `list_papers` accept semantic fields such as `queryText`?

Recommended answer: no.

Adopted. `list_papers` is deterministic browse/filter only. Topic discovery remains `search_paper_candidates`.

### 6. Should `list_papers` include facets in the MVP?

Recommended answer: yes, but only as deterministic product-state facets from available scalar paper metadata.

Adopted. Accept `includeFacets`. Compute years, authors, and venues from the current READY scoped paper set. Return `catalogTopics` and `paperTypes` as empty arrays until those product metadata dimensions exist; do not create schema or fake taxonomy in this slice.

### 7. Should returned paper cards include abstracts or previews?

Recommended answer: no.

Adopted. `list_papers` should return browse cards only: handles and metadata. Abstract previews can be mistaken for content support. Topic previews remain limited to `search_paper_candidates` and still cannot support content claims.

### 8. Should `list_papers` returned handles authorize reading tools?

Recommended answer: yes.

Adopted. A paper card selected through deterministic browse is a disclosed paper handle. It may feed `get_paper_outline`, `list_paper_locations`, and `find_reading_locations`. It still does not authorize Source Quote claims without `read_locations`.

### 9. Should `get_session_state` disclose paper handles?

Recommended answer: no.

Adopted. It returns scope and count only. Paper handles are disclosed by `list_papers`, `search_paper_candidates`, `trace_source_quotes`, or clicked paper rows.

## Tool Contracts

### `get_session_state`

Input:

```json
{}
```

Output:

```json
{
  "status": "OK",
  "searchScope": {
    "scopeMode": "AUTO_SOURCE",
    "label": "All readable papers",
    "readablePaperCountKnown": true,
    "readablePaperCount": 12,
    "immutable": true
  },
  "constraints": {
    "stateIsSourceQuote": false,
    "paperContentClaimsAllowed": false
  }
}
```

Status values:

```text
OK
INVALID_ARGUMENT
```

Rules:

- Accepts no arguments.
- Counts only accessible papers in the locked `SourceScope` that have a current READY Reading Model.
- Does not return paper handles.
- Does not return processing, failed, or unready papers.
- Does not return raw ids.

### `list_papers`

Input:

```json
{
  "filters": {
    "titleContains": "agent",
    "titleExact": "",
    "filenameContains": "",
    "filenameExact": "",
    "authorName": "",
    "doiExact": "",
    "arxivIdExact": "",
    "yearRange": {"from": 2023, "to": 2026},
    "venue": ""
  },
  "includeFacets": false,
  "sort": "RECENT"
}
```

Output:

```json
{
  "status": "OK",
  "total": 12,
  "returned": 12,
  "items": [
    {
      "ordinal": 1,
      "paperHandle": "paper_handle_abc",
      "title": "Agentic Eval Benchmark",
      "originalFilename": "agentic-eval.pdf",
      "authors": ["Ada Lovelace"],
      "year": 2025,
      "venue": "NeurIPS",
      "catalogTopics": [],
      "paperTypes": []
    }
  ],
  "facets": {
    "years": [2025],
    "authors": ["Ada Lovelace"],
    "venues": ["NeurIPS"],
    "catalogTopics": [],
    "paperTypes": []
  },
  "constraints": {
    "paperCardIsSourceQuote": false,
    "paperContentClaimsAllowed": false
  }
}
```

Status values:

```text
OK
NO_MATCH
INVALID_ARGUMENT
```

Sort values:

```text
RECENT
TITLE
YEAR
```

Rules:

- Returns only current READY Reading Model papers visible to the user and inside the locked `SourceScope`.
- Filters are deterministic metadata filters only.
- Rejects raw ids, ordinals, semantic query fields, page controls, output-size controls, and retrieval tuning controls.
- Output size is product-controlled. MVP should use an internal cap such as 20 returned paper cards.
- If `includeFacets=false`, return `facets` as `{}`.
- If `includeFacets=true`, return deterministic facets from the current READY scoped paper set, not from unready or inaccessible papers.
- `catalogTopics` and `paperTypes` remain empty until real product metadata exists.
- Paper cards are not Source Quotes.

## Task 1: Validator And Registry Contract

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ReadingToolArgumentValidator.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingToolRegistry.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/service/ReadingToolArgumentValidatorTest.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingToolRegistryTest.java`

- [ ] Add `validateGetSessionState(Map<String, Object>)`.
- [ ] Add `validateListPapers(Map<String, Object>)`.
- [ ] Add parser helpers or records for list filters, `includeFacets`, and sort.
- [ ] Reject unsupported top-level arguments outside `filters`, `includeFacets`, and `sort`.
- [ ] Reject forbidden arguments at any depth:

```text
paperId, paperIds, paperRef, paperRefs,
locationRef, locationRefs, sourceQuoteRef, sourceQuoteRefs,
modelVersion, indexVersion, indexName, chunkRef, readingElementId,
query, queryText, question, readingNeed, semanticNeed, topicText,
ordinal, ordinals, candidateOrdinal, resultOrdinal,
page, pageSize, pageRange, limit, topK, maxCandidates, budget,
rerank, rerankEnabled, searchMode, score, rank
```

- [ ] Validate `yearRange.from/to` as positive integers with `from <= to`.
- [ ] Validate `sort` as `RECENT`, `TITLE`, or `YEAR`, defaulting to `RECENT` when omitted.
- [ ] Register `get_session_state` and `list_papers` before `search_paper_candidates`.
- [ ] Keep all schemas closed with `additionalProperties=false`.
- [ ] Delegate validated calls to `ProductReadingToolAdapter`.

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

- [ ] Inject only focused dependencies needed for current READY scoped papers, likely `PaperService` plus existing `ProductPaperHandleService` and `PaperReadingModelRepository`.
- [ ] Implement `getSessionState(ProductToolContext)`.
- [ ] Implement `listPapers(filters, includeFacets, sort, ProductToolContext)`.
- [ ] Build the READY scoped base set from `PaperService#getAccessiblePapers(userId, null)`, locked `SourceScope`, and current READY Reading Model checks.
- [ ] Filter deterministically by title, filename, author, DOI, arXiv id, year range, and venue.
- [ ] Sort by RECENT, TITLE, or YEAR.
- [ ] Cap returned paper cards by internal product policy.
- [ ] Generate `paperHandle` values through `ProductPaperHandleService#handleForPaperId`.
- [ ] Add mapper methods for session state, paper browse card, list facets, and constraints.
- [ ] Ensure LLM-visible JSON omits raw `paperId`, `modelVersion`, internal ids, abstracts, `score`, `rank`, and Source Quote text.

Verification:

```bash
mvn -q -Dtest=ProductReadingToolAdapterTest test
```

Expected: PASS after implementation.

## Task 3: Harness Authorization And Prompt Policy

**Files:**
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarness.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarnessTest.java`

- [ ] Update required tool surface to the exact eight-tool order.
- [ ] Update system prompt:

```text
Use get_session_state for fixed search-scope label and readable paper count.
Use list_papers for deterministic browse/filter inside the fixed scope.
list_papers is not semantic search; use search_paper_candidates for topic discovery.
Paper cards from list_papers are navigation only, not Source Quotes.
Paper handles returned by list_papers may be used with get_paper_outline, list_paper_locations, or find_reading_locations.
```

- [ ] In `updateState`, disclose `paperHandle` values from `list_papers.items[]` to both semantic and deterministic paper-handle sets.
- [ ] Do not disclose anything from `get_session_state`.
- [ ] Add tests that:
  - `get_session_state` can support a `PRODUCT_STATE` count/scope answer.
  - `list_papers` can support a `PRODUCT_STATE` browse answer.
  - `list_papers` returned handles can feed `get_paper_outline`.
  - `list_papers` returned handles can feed `find_reading_locations`.
  - hidden handles are still rejected before browse/search/trace disclosure.

Verification:

```bash
mvn -q -Dtest=ProductReadingReActHarnessTest test
```

Expected: PASS after implementation.

## Task 4: Verification And Isolation Gates

- [ ] Run focused Product Reading tests:

```bash
mvn -q -Dtest=ReadingToolArgumentValidatorTest,ProductReadingToolRegistryTest,ProductReadingToolAdapterTest,ProductReadingReActHarnessTest test
```

- [ ] Run compile gate:

```bash
mvn -q -DskipTests compile
```

- [ ] Run isolation search:

```bash
rg -n "get_session_state|list_papers|ProductReadingReActHarness|ProductReadingToolRegistry" src/main/java/com/yizhaoqi/smartpai/service/ProductConversationService.java src/main/java/com/yizhaoqi/smartpai/service/ProductToolRegistry.java src/main/java/com/yizhaoqi/smartpai/service/ProductReActHarness.java
```

Expected: no Product Reading coupling introduced in the legacy path. Existing legacy-path `list_papers` mentions may appear because the old Product harness already has a legacy `list_papers`; do not edit those files in this slice.

- [ ] Run LLM-visible output safety search:

```bash
rg -n '"(paperId|modelVersion|readingElementId|sectionText|chunkRef|score|rank|abstract)"' src/main/java/com/yizhaoqi/smartpai/service/ProductReadingToolAdapter.java src/main/java/com/yizhaoqi/smartpai/service/ReadingToolOutputMapper.java src/main/java/com/yizhaoqi/smartpai/service/ProductReadingReActHarness.java
```

Expected: no hits in Product Reading LLM-visible output map keys. Java variable names and repository calls may still use internal identifiers.

- [ ] Run diff hygiene:

```bash
git diff --check
```

Expected: PASS.

## Completion Criteria

- `ProductReadingToolRegistry.listTools()` exposes exactly eight tools in the planned order.
- `get_session_state` accepts `{}` only and returns compact fixed scope/count state.
- `get_session_state` counts only current READY Reading Model papers visible to the user and inside the locked `SourceScope`.
- `list_papers` requires no semantic query text and rejects semantic fields, raw ids, ordinals, page controls, output controls, and retrieval tuning controls.
- `list_papers` filters and sorts only deterministic metadata.
- `list_papers` returns paper cards with `paperHandle`, title, filename, authors, year, venue, and empty catalog/paper-type arrays.
- `list_papers(includeFacets=true)` returns deterministic facets from current READY scoped papers.
- `list_papers` does not expose abstracts, raw `paperId`, `modelVersion`, internal ids, scores, ranks, Source Quote text, or paper content.
- `list_papers` returned handles authorize `get_paper_outline`, `list_paper_locations`, and `find_reading_locations` in the same turn.
- `get_session_state` discloses no handles.
- `trace_source_quotes` behavior remains unchanged: traced paper handles still do not authorize `find_reading_locations`.
- No legacy Product harness or registry behavior changes.
- Focused Product Reading tests pass.
- `mvn -q -DskipTests compile` passes.
- `git diff --check` passes.

## Deferred Historical Tool

This remains out of scope for this minimal closed loop:

```text
find_papers_by_identity
```

Recommended next slice after this one:

```text
find_papers_by_identity
```

Reason: after deterministic scope/count/browse is in place, identity resolution can reuse the same READY scoped paper-card contract and handle disclosure rules.
