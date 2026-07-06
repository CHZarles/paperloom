# Product ReAct Grep Reading Tools Spec

Date: 2026-07-06

Status: implementation-ready Phase 1 spec.

## Summary

This spec defines the first LLM/ReAct-facing backend tool adapter over the current grep-based
PaperLoom Reading Model search work.

Phase 1 exposes exactly two LLM-facing tools:

```text
search_paper_candidates
find_reading_locations
```

These two tools are enough for paper candidate discovery and Reading Model navigation. They are not
enough for source-quoted paper-content answers. `read_locations`, Source Quote persistence, and
`trace_source_quotes` remain the required next phase before the Product ReAct harness may answer
paper-content questions with citations.

The important boundary is:

```text
search_paper_candidates -> paperHandle cards only
find_reading_locations  -> locationRef candidates only
read_locations          -> future Source Quotes
```

Search results and previews help the model decide where to read. They must not become evidence.

## Related Docs

- Long-term catalog:
  `docs/superpowers/specs/2026-07-02-product-react-tool-catalog-spec.md`
- Minimal search loop plan:
  `docs/superpowers/plans/2026-07-06-paperloom-minimal-search-loop-plan.md`
- Reading Model glossary:
  `CONTEXT.md`
- Reading location ADR:
  `docs/adr/0003-use-paper-locations-for-reading-structure.md`
- Reading element ADR:
  `docs/adr/0004-use-reading-elements-as-canonical-typed-content.md`
- Reading chunk ADR:
  `docs/adr/0005-derive-reading-chunks-from-current-reading-model.md`
- ReAct tool separation ADR:
  `docs/adr/0006-separate-react-search-and-reading-location-tools.md`

## Current Code Evidence

The implementation target is based on the current worktree:

- `PaperCandidateSearchService`
  - Searches accessible Product Papers through `PaperService.getAccessiblePapers(...)`.
  - Greps paper metadata fields.
  - Returns `PaperCandidate` rows with raw `paperId`, match fields, match reason, and rank.
- `ReadingModelGrepSearchService`
  - Finds the current `PaperReadingModel`.
  - Loads `PaperReadingElement`, `PaperSection`, `PaperPage`, and `PaperLocation`.
  - Builds internal lookup maps:
    - `locationsByRef`
    - `pageLocationsByPage`
    - `sectionLocationsBySectionId`
    - `elementsByReadingId`
  - Searches readable fields only:
    - `PaperReadingElement.searchableText`
    - `PaperReadingElement.captionText`
    - `PaperReadingElement.bodyText`
    - `PaperSection.sectionTitle`
    - `PaperSection.sectionText`
    - `PaperPage.pageText`
  - Routes every hit to a `PaperLocation.locationRef`.
  - Drops unresolved hits.
- `PaperRecommendationCandidateService`
  - Orchestrates metadata candidates plus Reading Model supporting locations.
  - Is useful for product QA/manual inspection.
  - Must not be exposed as one LLM-facing ReAct tool.
- `POST /api/v1/papers/recommendation-candidates`
  - Is a thin product QA endpoint.
  - Is not an LLM-facing tool.

## Problem

The current grep services prove that PaperLoom can:

```text
query text
-> find candidate papers
-> search selected current Reading Models
-> resolve readable hits to locationRef
```

But the current services expose internal fields that are not safe for LLM tool contracts:

```text
paperId
modelVersion
readingElementId
matchedFields
matchedReadingElementIds
routingSource
matchSource
rank
matchReason
```

The LLM needs product-level handles and navigation refs:

```text
paperHandle
locationRef
```

It must not receive raw ids, model versions, parser identities, chunk identities, index controls, or
ranking scores.

## Goals

1. Expose current metadata grep as `search_paper_candidates`.
2. Expose current Reading Model grep as `find_reading_locations`.
3. Keep the tools stateless: every consuming call passes explicit handles or refs.
4. Map raw `paperId` values to opaque `paperHandle` values before returning tool output.
5. Resolve `paperHandle` values back to `paperId` only inside the backend adapter.
6. Return Reading Model location candidates as `locationRef` values only.
7. Preserve the Source Quote boundary: previews are navigation only.
8. Reject raw ids, retrieval knobs, index knobs, and natural-language intent aliases.
9. Leave `read_locations` and Source Quote creation for Phase 2.

## Non-Goals

Do not implement in this phase:

- `read_locations`
- `trace_source_quotes`
- `PaperSourceQuote`
- Source Quote idempotency
- final answer Source Quote validation
- paper outline tools
- deterministic paper location listing tools
- identity lookup tools
- session state tools
- semantic paper vector search
- Reading Chunk Elasticsearch migration
- hidden query rewrite
- generative reranking
- recommendation reason generation

Do not expose:

- `PaperRecommendationCandidateService` as a single ReAct function
- the `/recommendation-candidates` QA endpoint as an LLM function
- raw `paperId`
- `modelVersion`
- `chunkRef`
- `readingElementId`
- parser ids
- internal scores or rank reasons

## Grill Decisions

### 1. Are Two Tools Enough?

Recommended answer:

Two tools are enough for Phase 1 navigation, not for paper-content answers.

Adopted.

Phase 1 intentionally stops at:

```text
candidate papers
reading locations
```

Any final answer that makes claims about methods, experiments, results, limitations, comparisons, or
recommendation reasons still needs Source Quotes from future `read_locations`.

### 2. Should The LLM Get A Combined Recommendation Tool?

Recommended answer:

No.

Adopted.

The existing `PaperRecommendationCandidateService` combines paper metadata search and Reading Model
location lookup. That shape is useful for product QA, but too broad for ReAct. It hides a critical
decision:

```text
which candidate papers should I inspect next?
```

The LLM should first get paper cards, then choose explicit `paperHandle` values for
`find_reading_locations`. This preserves progressive disclosure and avoids treating location
previews as recommendation reasons.

### 3. Should We Reuse Legacy `paperRef` Values?

Recommended answer:

No for this reading path.

Adopted.

Legacy `paperRef` values are conversation reference records used by the old `ProductToolRegistry`.
The new reading path should converge on durable `paperHandle` values. A `paperHandle` resolves to a
Product Paper identity, not to a conversation-local display artifact.

### 4. Should `search_paper_candidates` Return Papers Without Current Reading Models?

Recommended answer:

No for the ReAct tool.

Adopted.

The QA endpoint may show metadata-only candidates for product validation, but the LLM-facing
`search_paper_candidates` tool must return only papers that can be read through the Current Reading
Model path.

Current Phase 1 readiness means:

```text
paper_reading_models.is_current = true
paper_reading_models.model_status = READING_MODEL_READY
```

When `index_status` lands in the model, ReAct readiness becomes:

```text
is_current = true
model_status = READING_MODEL_READY
index_status = READING_INDEX_READY
```

### 5. Should The LLM Control Result Limits?

Recommended answer:

No.

Adopted.

Current services accept limits because internal services and QA endpoints need bounded results.
LLM-facing schemas must not expose `limit`, `topK`, `pageSize`, `maxCandidates`, `budget`,
`pageWindow`, chunk controls, index controls, or rerank controls.

The backend adapter applies product defaults internally.

### 6. How Should `NO_MATCHING_LOCATION_TYPE` Be Computed?

Recommended answer:

Compute it from current `paper_locations`, not from grep hits.

Adopted.

For selected READY papers and requested `locationTypes`:

```text
NO_MATCHING_LOCATION_TYPE
  none of the selected papers has any current PaperLocation with those location types

NO_MATCH
  selected papers have those location types, but queryText found no matching candidates
```

Neither status is a paper-content claim. The LLM must not say the paper lacks a concept because a
location search returned no candidates.

### 7. Should Internal Match Diagnostics Be LLM-Visible?

Recommended answer:

No.

Adopted.

Diagnostics such as `matchedFields`, `routingSource`, and `matchedReadingElementIds` are useful in
trace artifacts and tests. They should not be part of the public tool result.

The only public ranking signal is result order.

## Domain Model

### Paper Handle

An opaque product-level token that resolves to one Product Paper. It is returned to the LLM instead
of raw `paperId`.

Rules:

- Must use a stable prefix such as `paper_handle_`.
- Must not encode raw `paperId`, SQL ids, user ids, org ids, filenames, or titles.
- Must be resolved inside the backend before service calls.
- Does not grant access by itself.
- Every tool must still check permission, fixed conversation search scope, and readiness.

### Location Ref

An opaque reading-location ref from `paper_locations.location_ref`. It identifies a PAGE, SECTION,
TABLE, or FIGURE location inside the current READY Reading Model.

Rules:

- Must not be treated as a Source Quote.
- Must not be accepted from old traced Source Quote metadata unless separately returned by current
  location/list/search tooling.
- Must be passed explicitly to future `read_locations`.

### Navigation Preview

Short non-citeable text returned by candidate-search and location-search tools to help the LLM
choose a next tool call.

Rules:

- May come from paper metadata or readable Reading Model fields.
- Must not become Source Quote content.
- Must not enter the final answer citation set.
- Must not justify recommendation reasons.

## Tool Catalog

Phase 1 exposes exactly:

```text
search_paper_candidates
find_reading_locations
```

The adapter must not expose old legacy tools in the same registry:

```text
find_papers
retrieve_evidence
inspect_reference
inspect_page
get_paper_metadata
resolve_papers
answer_without_product_state
```

If the legacy registry still exists for old Product ReAct, keep the Phase 1 reading registry isolated
behind a separate experiment/runtime path.

## Tool 1: `search_paper_candidates`

### Purpose

Search for candidate READY papers by topic or need inside the fixed conversation search scope.

### Input Schema

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": ["queryText"],
  "properties": {
    "queryText": {
      "type": "string",
      "description": "Caller-authored paper candidate search text."
    }
  }
}
```

### Accepted Inputs

Only:

```text
queryText
```

### Rejected Inputs

Reject the call if any of these appear at any nesting depth:

```text
paperId
paperIds
paperRef
paperRefs
chunkId
chunkIds
chunkRef
readingElementId
modelVersion
indexVersion
indexName
query
question
semanticNeed
recommendationNeed
limit
topK
pageSize
maxCandidates
budget
rerank
rerankEnabled
searchMode
```

### Backend Flow

```text
queryText
-> validate nonblank SearchText.tokens(queryText)
-> PaperCandidateSearchService.search(...)
-> filter to papers inside fixed conversation search scope
-> filter to papers with Current Reading Model READY
-> map each paperId to paperHandle
-> return ordered paper cards
```

### Output Shape

```json
{
  "status": "OK",
  "items": [
    {
      "ordinal": 1,
      "paperHandle": "paper_handle_...",
      "title": "Agentic Eval Benchmark",
      "authors": "Ada Lovelace",
      "year": 2025,
      "venue": "NeurIPS",
      "preview": "..."
    }
  ],
  "constraints": {
    "previewIsSourceQuote": false,
    "paperContentClaimsAllowed": false
  }
}
```

If no candidates remain after permission, scope, and READY filtering:

```json
{
  "status": "NO_MATCH",
  "items": [],
  "constraints": {
    "previewIsSourceQuote": false,
    "paperContentClaimsAllowed": false
  }
}
```

### Mapping From Current Service

```text
PaperCandidate.paperId          -> paperHandle
PaperCandidate.title            -> title
PaperCandidate.authors          -> authors
PaperCandidate.publicationYear  -> year
PaperCandidate.venue            -> venue
PaperCandidate.abstractPreview  -> preview
```

### Fields Not Returned

Do not return:

```text
paperId
rank
matchedFields
matchReason
```

`matchReason` may be written to trace, but it must not be used as final recommendation prose.

### Status Values

Allowed:

```text
OK
NO_MATCH
INVALID_ARGUMENT
```

`INVALID_ARGUMENT` is a tool error result, not a final answer.

## Tool 2: `find_reading_locations`

### Purpose

Search within explicitly chosen READY papers to find candidate reading locations.

### Input Schema

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": ["paperHandles", "queryText"],
  "properties": {
    "paperHandles": {
      "type": "array",
      "description": "Opaque paper handles returned by PaperLoom tools or clicked paper rows.",
      "items": {"type": "string"}
    },
    "queryText": {
      "type": "string",
      "description": "Caller-authored in-paper location search text."
    },
    "locationTypes": {
      "type": "array",
      "description": "Optional coarse reading-location type filter.",
      "items": {
        "type": "string",
        "enum": ["PAGE", "SECTION", "TABLE", "FIGURE"]
      }
    }
  }
}
```

### Accepted Inputs

Only:

```text
paperHandles
queryText
locationTypes
```

### Rejected Inputs

Reject the call if any of these appear at any nesting depth:

```text
paperId
paperIds
paperRef
paperRefs
locationId
locationIds
chunkId
chunkIds
chunkRef
readingElementId
modelVersion
indexVersion
indexName
query
question
readingNeed
semanticNeed
coverageTargets
limit
topK
maxCandidates
pageSize
pageWindow
budget
rerank
rerankEnabled
searchMode
```

### Backend Flow

```text
paperHandles + queryText + optional locationTypes
-> validate nonblank queryText
-> validate at least one paperHandle
-> resolve each paperHandle to paperId
-> verify each paper is visible to current user
-> verify each paper is inside fixed conversation search scope
-> verify each paper has Current Reading Model READY
-> load supported PaperLocation types for each paper's current modelVersion
-> if requested locationTypes exist nowhere, return NO_MATCHING_LOCATION_TYPE
-> ReadingModelGrepSearchService.search(...)
-> map internal ReadingLocationCandidate rows to LLM-visible candidates
-> return ordered location candidates
```

### Supported Location Type Computation

For each resolved paper:

```text
current model = PaperReadingModel where paperId = X and isCurrent = true
required modelStatus = READING_MODEL_READY
locations = PaperLocation where paperId = X and modelVersion = current.modelVersion
supportedLocationTypes = distinct location.locationType
```

Then:

```text
if locationTypes input is empty:
  requestedTypes = supportedLocationTypes

if requestedTypes has no intersection with all supportedLocationTypes:
  status = NO_MATCHING_LOCATION_TYPE
```

PAGE is expected for READY papers. SECTION, TABLE, and FIGURE are optional enhancements.

### Output Shape

```json
{
  "status": "OK",
  "candidates": [
    {
      "ordinal": 1,
      "paperHandle": "paper_handle_...",
      "locationRef": "section_ref_...",
      "locationType": "SECTION",
      "pageNumber": 3,
      "pageEndNumber": 4,
      "sectionTitle": "Experiments",
      "preview": "..."
    }
  ],
  "supportedLocationTypesByPaper": {
    "paper_handle_...": ["PAGE", "SECTION", "TABLE"]
  },
  "constraints": {
    "previewIsSourceQuote": false,
    "locationRefIsSourceQuote": false,
    "paperContentClaimsAllowed": false
  }
}
```

If requested structure does not exist:

```json
{
  "status": "NO_MATCHING_LOCATION_TYPE",
  "candidates": [],
  "supportedLocationTypesByPaper": {
    "paper_handle_...": ["PAGE", "SECTION"]
  },
  "constraints": {
    "paperContentAbsenceClaimAllowed": false
  }
}
```

If structure exists but the query has no hits:

```json
{
  "status": "NO_MATCH",
  "candidates": [],
  "supportedLocationTypesByPaper": {
    "paper_handle_...": ["PAGE", "SECTION", "TABLE"]
  },
  "constraints": {
    "paperContentAbsenceClaimAllowed": false
  }
}
```

### Mapping From Current Service

```text
ReadingLocationCandidate.paperId       -> paperHandle
ReadingLocationCandidate.locationRef   -> locationRef
ReadingLocationCandidate.locationType  -> locationType
ReadingLocationCandidate.pageNumber    -> pageNumber
ReadingLocationCandidate.pageEndNumber -> pageEndNumber
ReadingLocationCandidate.sectionTitle  -> sectionTitle
ReadingLocationCandidate.preview       -> preview
```

### Fields Not Returned

Do not return:

```text
paperId
modelVersion
readingElementId
matchedFields
matchedReadingElementIds
routingSource
matchSource
score
bm25Score
vectorScore
rerankScore
rerankReason
```

### Status Values

Allowed:

```text
OK
NO_MATCH
NO_MATCHING_LOCATION_TYPE
INVALID_ARGUMENT
PAPER_HANDLE_UNAVAILABLE
READING_MODEL_UNAVAILABLE
```

`READING_MODEL_UNAVAILABLE` is for handles that resolve to visible papers but cannot be searched
because the current Reading Model is absent or not READY. It must not be phrased as a paper-content
absence claim.

## Backend Components

### `ProductReadingToolRegistry`

New registry for Phase 1 reading tools.

Responsibilities:

- Return only `search_paper_candidates` and `find_reading_locations` from `listTools()`.
- Validate tool arguments.
- Reject unsupported tool names.
- Delegate business execution to the adapter.
- Return `ProductToolResult`-compatible payloads only if this registry is wired through existing
  harness infrastructure.

Do not add the Phase 1 tools to the legacy `ProductToolRegistry` unless the harness can prevent old
evidence refs, old paper refs, and new reading refs from being mixed in one answer validation path.

### `ProductPaperHandleService`

Responsible for opaque paper handle mapping.

Required methods:

```java
String handleForPaperId(String paperId);
Optional<String> resolvePaperHandle(String paperHandle);
boolean isPaperVisibleToUser(String paperId, Long userId, SourceScope lockedScope);
boolean hasCurrentReadyReadingModel(String paperId);
```

Implementation recommendation:

- First implementation may use deterministic handle encoding only if it does not expose raw
  `paperId`.
- Target implementation should persist durable random handles, for example:

```text
product_paper_handles
- paper_handle
- paper_id
- created_at
```

The service must be the only code that knows the mapping.

### `ProductReadingToolAdapter`

Responsible for calling current grep services and mapping output.

Dependencies:

```text
PaperCandidateSearchService
ReadingModelGrepSearchService
PaperReadingModelRepository
PaperLocationRepository
ProductPaperHandleService
```

Responsibilities:

- Apply product default limits internally.
- Resolve paper handles.
- Enforce permission and fixed scope.
- Enforce Current Reading Model READY.
- Compute supported location types.
- Strip internal service fields.
- Return navigation-only outputs.

### `ReadingToolArgumentValidator`

Responsible for recursive forbidden-argument checks.

Forbidden argument categories:

```text
raw ids
retrieval controls
index controls
chunk controls
natural-language intent aliases
legacy paper/evidence refs
```

Use one shared validator for both tools so rejection behavior is consistent.

### `ReadingToolOutputMapper`

Responsible for LLM-visible output maps.

It must be tested as a hard boundary:

```text
internal service record in
-> public tool result out
-> no raw/internal fields remain
```

## Prompt Policy

When these tools are exposed, the Product ReAct prompt must tell the LLM:

- Use `search_paper_candidates` for topic or need based paper candidate search.
- Use `find_reading_locations` only after you have explicit `paperHandle` values.
- A paper candidate preview is not a Source Quote.
- A reading location preview is not a Source Quote.
- A `locationRef` is not a Source Quote.
- Do not make paper-content claims from these tools alone.
- If the user asks for paper content, use these tools only to locate where to read, then wait for
  `read_locations` support or return not enough source quotes.
- Do not invent `paperHandle`, `locationRef`, or `sourceQuoteRef` values.
- Do not use ordinals as tool input.
- Do not pass internal controls such as `limit`, `topK`, `modelVersion`, or `indexName`.

## Final Answer Policy For Phase 1

The Phase 1 tools can support final answers of this kind:

```text
candidate-list answer
navigation answer
clarification answer
not-enough-source-quotes answer
```

They cannot support:

```text
SOURCE_QUOTED_ANSWER
paper-content summary
paper-content recommendation reason
method/result/limitation claim
cross-paper comparison
citation rendering
```

If a user asks:

```text
推荐 agent eval 相关论文，并说明原因
```

Phase 1 may return:

```text
I found candidate papers and candidate reading locations, but source-quoted reasons require
read_locations / Source Quotes.
```

It must not turn candidate previews into recommendation reasons.

## Trace Requirements

Trace artifacts should record internal details that are hidden from the LLM:

- original tool name
- raw arguments
- rejected forbidden argument, if any
- `queryText`
- resolved `paperHandle -> paperId` mappings
- Current Reading Model version used internally
- supported location types per paper
- internal `PaperCandidate` rank and matched fields
- internal `ReadingLocationCandidate` matched fields and routing source
- public output payload
- status value

Trace remains offline eval/debug data. It must not be shown to the LLM as tool result content.

## Security And Safety Requirements

The adapter must:

- Never return raw `paperId`.
- Never accept raw `paperId`.
- Never return raw `modelVersion`.
- Never accept raw `modelVersion`.
- Never expose chunk ids, parser ids, SQL ids, ES ids, or vector ids.
- Never call a generative LLM for query rewrite, rerank, summarization, recommendation, or support
  judgment.
- Never create hidden selected-paper or selected-location state.
- Never treat prior visibility of a handle as authorization.
- Always recheck user permission and fixed conversation scope at execution time.
- Always mark previews as non-citeable by policy and constraints.

## Implementation Plan

### Step 1: Handle Boundary

Create:

```text
src/main/java/com/yizhaoqi/smartpai/service/ProductPaperHandleService.java
src/test/java/com/yizhaoqi/smartpai/service/ProductPaperHandleServiceTest.java
```

Minimum behavior:

- Same `paperId` returns the same handle.
- Handle starts with `paper_handle_`.
- Handle does not contain raw `paperId`.
- Unknown handle does not resolve.

### Step 2: Argument Validation

Create:

```text
src/main/java/com/yizhaoqi/smartpai/service/ReadingToolArgumentValidator.java
src/test/java/com/yizhaoqi/smartpai/service/ReadingToolArgumentValidatorTest.java
```

Minimum behavior:

- Reject forbidden fields recursively.
- Reject missing `queryText`.
- Reject empty `paperHandles` for `find_reading_locations`.
- Reject unsupported `locationTypes`.

### Step 3: Output Mapping

Create:

```text
src/main/java/com/yizhaoqi/smartpai/service/ReadingToolOutputMapper.java
src/test/java/com/yizhaoqi/smartpai/service/ReadingToolOutputMapperTest.java
```

Minimum behavior:

- `PaperCandidate` maps to paper card with `paperHandle`.
- `ReadingLocationCandidate` maps to location card with `paperHandle`.
- Internal fields are absent from serialized output.

### Step 4: Adapter

Create:

```text
src/main/java/com/yizhaoqi/smartpai/service/ProductReadingToolAdapter.java
src/test/java/com/yizhaoqi/smartpai/service/ProductReadingToolAdapterTest.java
```

Minimum behavior:

- `searchPaperCandidates` calls `PaperCandidateSearchService`.
- It filters candidates to visible, in-scope, Current Reading Model READY papers.
- `findReadingLocations` resolves handles and calls `ReadingModelGrepSearchService`.
- It returns `NO_MATCHING_LOCATION_TYPE` when requested types are unavailable.
- It returns `NO_MATCH` when types exist but no candidates match.

### Step 5: Registry

Create:

```text
src/main/java/com/yizhaoqi/smartpai/service/ProductReadingToolRegistry.java
src/test/java/com/yizhaoqi/smartpai/service/ProductReadingToolRegistryTest.java
```

Minimum behavior:

- `listTools()` returns only the two Phase 1 tools.
- Tool schemas have `additionalProperties=false`.
- Unsupported tools return tool error.
- Forbidden arguments return tool error.

### Step 6: Isolated Harness Wiring

Wire only after the registry tests pass.

Recommended first wiring:

```text
ProductReActHarness experiment path
or feature-flagged reading-tool registry
```

Do not replace the legacy Product ReAct catalog until Source Quote read/trace and final answer
validation exist.

## Focused Tests

### Candidate Tool Tests

- Returns `paperHandle`, title, authors, year, venue, preview.
- Does not return `paperId`, rank, matched fields, or match reason.
- Rejects `limit`, `topK`, `paperId`, `question`, and `modelVersion`.
- Filters out candidates without Current Reading Model READY.
- Returns `NO_MATCH` after filtering removes all candidates.

### Reading Location Tool Tests

- Resolves `paperHandles` before calling `ReadingModelGrepSearchService`.
- Calls grep only with resolved paper ids.
- Returns `locationRef`, `locationType`, page numbers, section title, and preview.
- Does not return `modelVersion`, `readingElementId`, matched fields, routing source, or scores.
- Returns `NO_MATCHING_LOCATION_TYPE` when selected current models have none of the requested
  location types.
- Returns `NO_MATCH` when requested types exist but grep returns no candidates.
- Rejects unsupported `locationTypes`.
- Rejects `query`, `question`, `readingNeed`, `chunkRef`, `paperId`, `limit`, and `topK`.

### Policy Tests

- Result constraints mark preview and location refs as non-Source-Quote.
- No output map contains keys from the raw/internal denylist.
- Tool schemas do not expose result-size controls.
- Tool schemas do not expose model/index/chunk controls.

## Acceptance Criteria

Phase 1 is done when:

```text
LLM calls search_paper_candidates(queryText)
-> receives READY paper cards with paperHandle only

LLM calls find_reading_locations(paperHandles, queryText, locationTypes?)
-> receives current Reading Model locationRef candidates only
```

and:

- No LLM-visible payload contains raw `paperId`.
- No LLM-visible payload contains `modelVersion`.
- No LLM-visible payload contains `chunkRef`.
- No LLM-visible payload contains `readingElementId`.
- No LLM-visible payload contains matched-field or routing diagnostics.
- No LLM-visible payload contains ranking scores.
- No Phase 1 tool returns Source Quote content.
- No Phase 1 tool returns `sourceQuoteRef`.
- Final paper-content claims remain disabled until Phase 2.

## Phase 2 Requirement

The next required tool is:

```text
read_locations
```

Phase 2 must add:

```text
PaperSourceQuote
sourceQuoteRef
Source Quote splitting policy
Source Quote idempotency
conversation Source Quote registry
```

Only after Phase 2 may the Product ReAct harness produce source-quoted paper-content answers.

## Phase 3 Requirement

The next follow-up after `read_locations` is:

```text
trace_source_quotes
```

Phase 3 enables follow-up questions over clicked source chips and previously read Source Quotes.

## Explicitly Deferred Long-Term Tools

The long-term catalog still includes:

```text
get_session_state
list_papers
find_papers_by_identity
get_paper_outline
list_paper_locations
read_locations
trace_source_quotes
```

This spec does not reject those tools. It only defines the smallest safe adapter over the grep
methods and Reading Model indexes that exist now.
