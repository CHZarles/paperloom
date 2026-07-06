# PaperLoom Minimal System Implementation Grill

Date: 2026-07-04
Branch: `feature/paperloom-reading-retrieval-minimal-system`
Status: adopted recommendations

Update: the follow-up grill explicitly says not to consider compatibility. The "No Compatibility
Grill" section below supersedes compatibility-driven choices in this document.

## Current Code Facts

- MinerU already enters through `PaperPdfParser` and `MinerUOutputMapper`.
- `ParseService` already receives `ParsedPaper` and persists `PaperTextChunk`.
- `PaperChunkBuilder` already creates page-aware chunks with section title, element type, table id,
  figure id, formula id, parser name, parser version, and bbox metadata.
- `VectorizationService` already embeds `PaperTextChunk` and writes `PaperChunkDocument` to
  Elasticsearch.
- `PaperRetrievalService` and `HybridSearchService` already provide product retrieval over indexed
  chunks.
- `ProductToolRegistry` already contains product tools, but current names and concepts predate the
  new minimal tool catalog.
- `PaperConversationReference` already persists conversation references, but Source Quote should be
  made explicit instead of treated as a generic evidence/citation reference.

## Grill Decisions

### 1. What Is The First Runnable Slice?

Recommended answer:

Build one vertical slice:

```text
MinerU ParsedPaper
-> Reading Model Builder
-> PaperPage + PaperLocation
-> existing PaperTextChunk / ES indexing
-> read_locations over PaperLocation refs
-> Source Quote persistence
-> final answer guard
```

Adopted.

Reason:

This proves the end-to-end invariant without rebuilding retrieval first.

```text
search finds candidate content;
read_locations creates Source Quotes;
final answers cite Source Quotes.
```

### 2. Should MVP Replace Existing Chunking And ES?

Recommended answer:

No. MVP reuses existing `PaperTextChunk`, `VectorizationService`, `PaperRetrievalService`, and
Elasticsearch index.

Adopted.

Reason:

The current system already has chunk persistence, embedding, hybrid retrieval, page numbers, section
titles, table ids, figure ids, formula ids, and permission metadata. Replacing it before Source Quote
reading works would delay the runnable slice.

MVP adds the missing reading model and explicit Source Quote path around the existing retrieval.

### 3. What New Persistent Objects Are Required First?

Recommended answer:

Add three product-owned objects:

```text
PaperPage
PaperLocation
PaperSourceQuote
```

Adopted.

Minimum fields:

```text
PaperPage
- paperId
- pageNumber
- pageText
- textHash
- charCount
- parserName
- parserVersion
- userId
- orgTag
- isPublic

PaperLocation
- locationRef
- paperId
- locationType: PAGE | SECTION | TABLE | FIGURE
- pageNumber
- sectionTitle optional
- tableId optional
- figureId optional
- sourceKind
- contentKind
- userId
- orgTag
- isPublic

PaperSourceQuote
- sourceQuoteRef
- paperId
- locationRef
- pageNumber
- contentKind
- content
- sourceSpanJson
- conversationId optional
- turnId optional
- userId
- orgTag
- isPublic
```

MVP does not need exact char offsets if MinerU does not provide stable offsets. Page/chunk/block
span is acceptable for the first runnable system.

### 4. Where Does Reading Model Builder Run?

Recommended answer:

Run it inside `ParseService` after `ParsedPaper` is created and before vectorization/indexing.

Adopted.

Target shape:

```text
ParsedPaper parsedPaper = paperPdfParser.parse(...)
paperReadingModelService.replaceFromParsedPaper(...)
paperTextChunkRepository.save(...)
```

Reason:

`ParseService` is the first place where PaperLoom has both:

- parser output
- product paper identity and access metadata

### 5. What Counts As READY In MVP?

Recommended answer:

MVP READY means:

```text
at least one readable PaperPage exists
every PaperPage has a PAGE PaperLocation
existing chunk persistence succeeds
vectorization/indexing succeeds
```

Adopted.

Do not require:

```text
reliable section tree
table extraction
figure extraction
bbox
page screenshot
summary
PageIndex
```

Implementation implication:

`PaperSearchabilityService` should eventually check reading readiness, not just
`vectorizationStatus == COMPLETED` and `paper_text_chunks` count.

### 6. How Should `find_reading_locations` Work In MVP?

Recommended answer:

Use existing retrieval first. Convert search hits into location candidates.

Adopted.

MVP behavior:

```text
queryText -> PaperRetrievalService.retrieve(...)
SearchResult -> PaperLocation candidate
return locationRef, paper title, page number, section title, content kind, short preview
```

`queryText` is supplied by the LLM. The tool may normalize, tokenize, embed,
filter, and rank it, but must not call a hidden generative LLM for query rewrite,
planning, summarization, or answer drafting.

The LLM owns query rewriting in the visible ReAct loop. For example:

```text
User: 这篇论文的 ablation 实验说明了什么？
LLM call 1: find_reading_locations(queryText="ablation experiment results")
LLM call 2 if needed: find_reading_locations(queryText="ablation study component removal comparison")
```

The tool does not accept `readingNeed` or infer "what the user should read".
It returns ordered `locationRef` candidates only.

Do not expose:

```text
raw paperId
raw chunkId
ES query
topK
rerank knobs
score tuning
```

The tool can return `locationRef` because `read_locations` needs explicit refs.

### 7. What Should `read_locations` Read First?

Recommended answer:

Support PAGE first. Support SECTION / TABLE / FIGURE when those product
locations have readable text.

Adopted.

MVP behavior:

```text
PAGE locationRef -> read PaperPage.pageText
SECTION locationRef -> read matching section text if available
TABLE locationRef -> read table text if available
FIGURE locationRef -> read caption/figure text if available
```

If a location cannot be read, return a structured unreadable status. Do not invent content.

### 8. How Should Source Quote Be Created?

Recommended answer:

`read_locations` creates and persists Source Quotes.

Adopted.

MVP rule:

Search result previews are not Source Quotes. Existing memory is not Source Quote. A Source Quote is
created only when explicit readable content is returned by `read_locations` or reopened by
`trace_source_quotes`.

### 9. How Should Answer Guard Be Implemented First?

Recommended answer:

Start with structural validation before semantic verification.

Adopted.

MVP validation:

```text
final paper-content answer must cite sourceQuoteRef
each cited sourceQuoteRef must exist
each cited sourceQuoteRef must belong to the fixed conversation search scope
if no valid sourceQuoteRef exists, answer type must be insufficient evidence
```

Do not implement claim-level NLI verification in MVP. Put it in V1.

### 10. How Should Persistent Reference Memory Be Scoped?

Recommended answer:

Keep `PaperConversationReference` for conversation-level UI/source preview, but introduce explicit
Source Quote persistence for product reading.

Adopted.

MVP rule:

```text
MySQL stores Source Quotes and reference mappings.
Redis stores temporary context only.
Memory can help find existing sourceQuoteRefs.
Memory cannot support final paper-content claims.
```

### 11. What Tests Prove The MVP Slice?

Recommended answer:

Use narrow service tests before browser tests.

Adopted.

Minimum tests:

```text
ReadingModelBuilderTest
- ParsedPaper with two pages creates PaperPage and PAGE locations
- empty parser text does not mark readable

ReadLocationsTest
- PAGE location returns Source Quote
- readable SECTION / TABLE / FIGURE location returns Source Quote when available
- unreadable location returns structured status

SourceQuoteTraceTest
- persisted sourceQuoteRef can be reopened
- invented sourceQuoteRef cannot be traced

AnswerGuardTest
- paper-content answer without Source Quote is rejected
- answer with valid sourceQuoteRef passes structural validation
```

Integration smoke:

```text
upload/parse one PDF
vectorize/index
find_reading_locations
read_locations
final answer with sourceQuoteRef
```

## Implementation Order

```text
1. Add entities/repositories:
   PaperPage, PaperLocation, PaperSourceQuote

2. Add PaperReadingModelService:
   replaceFromParsedPaper(...)

3. Call PaperReadingModelService from ParseService.

4. Add SourceQuoteService:
   createFromLocation(...)
   trace(...)

5. Add read location service:
   read PAGE / basic SECTION / basic TABLE / basic FIGURE

6. Adapt ProductToolRegistry or add a new product tool slice:
   search_paper_candidates
   find_reading_locations
   read_locations
   trace_source_quotes

7. Add answer guard structural validation.

8. Add focused tests.
```

## Explicit Non-Goals For This Branch Phase

```text
PageIndex
RAPTOR
GraphRAG / LightRAG
ColPali / multi-vector
learned sparse retrieval
late chunking
visual retrieval
complex summary pipeline
complex fast model router
claim-level semantic verifier
```

## Final Check

This implementation plan supports the minimum agent loop:

```text
agent searches candidate papers
-> agent searches reading locations
-> agent reads explicit locations
-> system persists Source Quotes
-> agent searches again if needed
-> final answer cites Source Quotes
```

## No Compatibility Grill

This section answers the same implementation questions under one constraint:

```text
Do not preserve old product-tool names, old evidence/citation concepts, or old chunk-first behavior
if they make the minimum system harder to reason about.
```

### 1. What Is The Cleanest Runnable Slice?

Recommended answer:

Build the new reading pipeline as the primary path:

```text
MinerU ParsedPaper
-> PaperReadingModelBuilder
-> PaperPage + PaperLocation + PaperSourceQuote
-> ReadingChunk + ReadingLocationIndex
-> search_paper_candidates / find_reading_locations
-> read_locations / trace_source_quotes
-> SourceQuoteAnswerGuard
```

Adopted.

Reason:

The runnable slice should prove the new system, not wrap old "evidence" behavior in new names.

### 2. Should `PaperTextChunk` Remain The Core Model?

Recommended answer:

No. Treat `PaperTextChunk` as legacy storage or migration material. The new core model is:

```text
PaperPage
PaperLocation
ReadingChunk
PaperSourceQuote
```

Adopted.

Reason:

`PaperTextChunk` mixes source text, retrieval chunk, parser metadata, and evidence role. The minimum
system needs separate concepts:

```text
readable location
retrieval chunk
source quote
```

### 3. What Replaces Legacy Evidence/Citation Naming?

Recommended answer:

Use only the new names:

```text
Source Quote
Source Span
Paper Location
Reading Chunk
Answer Guard
```

Adopted.

Remove from the new path:

```text
evidence
citation
inspect_reference
inspect_page
retrieve_evidence
answer_without_product_state
get_session_scope
```

Reason:

The old names encode the wrong model. The LLM-facing API should describe exactly what the agent can
do: search papers, find reading locations, read locations, trace source quotes.

### 4. What Are The New Persistent Tables?

Recommended answer:

Create the new tables directly:

```text
product_paper_handles
paper_reading_models
paper_pages
paper_locations
reading_chunks
paper_source_quotes
```

Adopted.

Minimum model:

```text
product_paper_handles
- id
- paper_handle
- paper_id
- created_at

paper_reading_models
- id
- paper_id
- model_version
- model_status
- index_status
- is_current
- parser_name
- parser_version
- created_at

paper_pages
- id
- paper_id
- model_version
- page_number
- page_text
- text_hash
- char_count
- parser_name
- parser_version
- user_id
- org_tag
- is_public

paper_locations
- id
- location_ref
- paper_id
- model_version
- location_type
- page_number
- section_title
- table_id
- figure_id
- formula_id
- content_kind
- source_span_json
- user_id
- org_tag
- is_public

reading_chunks
- id
- chunk_ref
- paper_id
- model_version
- location_ref
- chunk_type
- page_number
- section_title
- original_text
- search_text
- content_kind
- source_span_json
- embedding_model_version
- index_version
- user_id
- org_tag
- is_public

paper_source_quotes
- id
- source_quote_ref
- paper_id
- location_ref
- page_number
- content_kind
- content
- source_span_json
- conversation_id
- turn_id
- user_id
- org_tag
- is_public
```

### 5. What Should The New Index Contain?

Recommended answer:

Index `ReadingChunk`, not legacy `PaperTextChunk`. MVP uses one product reading
chunk index and filters by current `modelVersion`.

Adopted.

Index name:

```text
paperloom_reading_chunks
```

MVP index document:

```text
chunkRef
paperId
modelVersion
locationRef
chunkType
pageNumber
sectionTitle
contentKind
searchText
vector
userId
orgTag
isPublic
indexVersion
```

Index switching rule:

```text
MVP does not need Elasticsearch alias switching
Reading Model rebuild writes docs with a new modelVersion
find_reading_locations filters current READY modelVersion
after current READY model changes, new retrieval sees only the new modelVersion
old docs may be cleaned asynchronously
```

Do not expose:

```text
indexName
modelVersion
indexVersion
```

Search can return `locationRef` directly, so `read_locations` does not need to
reverse-engineer reading locations from index-hit ids.

`chunkRef` is internal. LLM-facing tools must not return `chunkRef` or expose
`CHUNK` as a reading-location type.

### 5A. Should `ReadingChunk` Store Both `originalText` And `searchText`?

Recommended answer:

Yes. They serve different purposes.

Adopted.

Definition:

```text
originalText = readable source block from the Reading Model
searchText = retrieval text, possibly enriched with headings, parser labels, summary hints, or expanded terms
```

Rules:

```text
find_reading_locations may search searchText
read_locations must read originalText / PaperPage.pageText / table text / caption text
searchText must not become Source Quote content
searchText must not support final paper-content claims
summary hints and enriched terms may enter searchText only
```

This improves retrieval without polluting Source Quotes.

### 5B. How Should `ReadingChunk` Be Split?

Recommended answer:

Use deterministic chunking from Reading Model locations. Do not do complex
semantic chunking in MVP.

Adopted.

MVP policy:

```text
PAGE -> paragraph chunks; if parser paragraphs are unavailable, fixed internal character windows
SECTION -> paragraph chunks with sectionTitle
TABLE -> one table chunk
FIGURE -> one caption / readable figure text chunk
```

Rules:

```text
chunk must bind modelVersion
chunk must bind locationRef
chunkRef is internal and never LLM-facing
chunk size is internal product policy
chunk overlap is internal product policy
LLM cannot pass chunkSize, overlap, chunkOverlap, or topK
```

This keeps retrieval tunable without making chunking part of the LLM-facing
tool contract.

### 6. What Should Replace `ProductToolRegistry`?

Recommended answer:

For the new path, create a new registry with only the minimal tools:

```text
ProductReadingToolRegistry
- get_session_state
- list_papers
- search_paper_candidates
- find_papers_by_identity
- get_paper_outline
- list_paper_locations
- find_reading_locations
- read_locations
- trace_source_quotes
```

Adopted.

Do not keep old tools for compatibility in this branch. If old code needs to compile while the new
path is built, isolate it instead of making it part of the target design.

### 7. What Should READY Mean Without Compatibility Constraints?

Recommended answer:

Introduce `paper_reading_models` as the source of truth for reading readiness.
Do not infer readiness from old `vectorizationStatus`.

Table:

```text
paper_reading_models
- paper_id
- model_version
- model_status
- index_status
- is_current
- parser_name
- parser_version
```

Status values:

```text
READING_MODEL_BUILDING
READING_MODEL_READY
READING_MODEL_FAILED
READING_INDEXING
READING_INDEX_READY
READING_INDEX_FAILED
```

Adopted.

READY for LLM-facing retrieval:

```text
paper_reading_models.is_current = true
&& paper_reading_models.model_status = READING_MODEL_READY
&& paper_reading_models.index_status = READING_INDEX_READY
```

Only one current READY Reading Model may exist per Product Paper.

MVP gate:

```text
at least one PaperPage with usable pageText
every PaperPage has PAGE PaperLocation
at least one ReadingChunk exists
ReadingChunk index write succeeds
```

Binding rules:

```text
paper_pages.model_version = paper_reading_models.model_version
paper_locations.model_version = paper_reading_models.model_version
reading_chunks.model_version = paper_reading_models.model_version
find_reading_locations searches only current READY model_version
list_paper_locations returns only current READY model_version
read_locations accepts only current READY model_version locationRef
trace_source_quotes does not depend on current model_version
```

Do not reuse `COMPLETED` as a proxy for readability.

### 8. How Should `read_locations` Work In The Clean Model?

Recommended answer:

Read from `PaperLocation`, not from search results.

Adopted.

Behavior:

```text
locationRef -> PaperLocation
PAGE -> PaperPage.pageText
SECTION -> ReadingChunks whose locationRef points to that section
TABLE -> table location text or ReadingChunks whose locationRef points to that table
FIGURE -> caption / figure text or ReadingChunks whose locationRef points to that figure
```

`read_locations` does not accept `queryText` and does not call a generative LLM.
It reads and internally splits selected locations by product policy.

Then persist `PaperSourceQuote`.

### 8A. Is PAGE-Level Reading Too Coarse?

Recommended answer:

Accept it for MVP. Do not expose chunks to fix this.

Adopted.

Rules:

```text
ReadingChunk is internal retrieval state.
find_reading_locations maps chunk hits back to locationRef.
LLM sees PAGE / SECTION / TABLE / FIGURE locationRef only.
read_locations reads by locationRef only.
read_locations does not accept queryText.
read_locations does not extract only relevant sentences.
```

Size control:

```text
PAGE -> paragraph Source Quotes, then internal character windows if needed
SECTION -> paragraph or original source-block Source Quotes
TABLE -> table Source Quote
FIGURE -> caption/readable figure text Source Quote
```

If the read is not precise enough, the LLM should search again with a different
`queryText`. Do not expose `chunkRef`, and do not turn `read_locations` into a
semantic extraction tool.

### 8B. What If Requested Location Type Does Not Exist?

Recommended answer:

Do not pretend the optional structure exists.

Adopted.

Rules:

```text
PAGE is required for READY.
SECTION / TABLE / FIGURE are optional enhancements.
find_reading_locations searches only existing locations.
If requested locationTypes do not exist, return NO_MATCHING_LOCATION_TYPE.
NO_MATCHING_LOCATION_TYPE is a structure result, not a paper-content result.
If requested locationTypes exist but queryText has no hit, return NO_MATCH.
```

Example:

```text
find_reading_locations(queryText="ablation results", locationTypes=["TABLE"])
-> status: NO_MATCHING_LOCATION_TYPE
-> candidates: []
```

The LLM should then retry without `locationTypes` or search PAGE locations. It
must not conclude that the paper has no ablation result.

### 8C. How Should `read_locations` Split Source Quotes?

Recommended answer:

Use a fixed internal splitting policy. Do not expose splitting controls to the
LLM.

Adopted.

MVP policy:

```text
PAGE -> paragraph boundaries first, then fixed internal character windows
SECTION -> paragraph boundaries or original ReadingChunk source blocks
TABLE -> one Source Quote per table, internally truncated if needed
FIGURE -> one Source Quote per caption or readable figure text block
```

Each Source Quote records:

```text
sourceQuoteRef
locationRef
pageNumber
content
sourceSpanJson
```

Do not expose:

```text
maxChars
topK
quoteCount
queryText
semanticNeed
```

### 8D. Should Repeated Reads Create New Source Quotes?

Recommended answer:

No. Use idempotent Source Quote creation.

Adopted.

Rules:

```text
idempotency key =
paperId + modelVersion + locationRef + splitPolicyVersion + splitIndex + contentHash

same key -> reuse existing sourceQuoteRef
different contentHash -> create new sourceQuoteRef
different modelVersion -> create new sourceQuoteRef
different splitPolicyVersion -> create new sourceQuoteRef
```

`splitPolicyVersion` is internal. It must not be exposed to the LLM or accepted
as tool input. Bump it when Source Quote splitting policy changes.

`splitIndex` stability is scoped to one `splitPolicyVersion`:

```text
same input text + same location + same splitPolicyVersion
-> same splitIndex sequence

splitting algorithm changes
-> bump splitPolicyVersion
```

Do not add a separate span-id system for MVP. Old Source Quotes are already
persisted and remain traceable.

Why:

```text
repeated reads do not pollute MySQL
historical citations stay stable
trace_source_quotes can reopen old Source Quotes
Redis is not the source of truth
```

### 8E. How Should Old Source Quote Context Be Read?

Recommended answer:

Do not let old `locationRef` values enter the current reading path.

Adopted.

Rules:

```text
trace_source_quotes can reopen old Source Quote content.
old locationRef/pageRef/sectionRef values returned by trace_source_quotes are metadata.
read_locations accepts only current READY model locationRef values.
old refs from trace_source_quotes must not be passed directly to read_locations.
```

Nearby context flow:

```text
trace_source_quotes(sourceQuoteRef)
-> paperHandle + pageNumber + sectionLabel metadata
list_paper_locations(paperHandle, pageRange or section hint)
-> current READY model locationRef
read_locations(current locationRef)
```

If no current location maps to the old metadata, return
`CURRENT_LOCATION_NOT_FOUND`.

### 8F. Can Old Source Quotes Bypass Current Permissions?

Recommended answer:

No. Persistent Source Quotes are not permission exemptions.

Adopted.

Rules:

```text
trace_source_quotes does not require current Reading Model READY.
trace_source_quotes must check current paper existence.
trace_source_quotes must check current user permission.
trace_source_quotes must check fixed conversation search scope.
if any check fails -> SOURCE_QUOTE_UNAVAILABLE
SOURCE_QUOTE_UNAVAILABLE returns no quote content.
```

This prevents historical access from bypassing current paper permissions.

### 8G. Is Source Quote Existence Enough For Final Answers?

Recommended answer:

No. Answer Guard must validate current usability.

Adopted.

Rules:

```text
sourceQuoteRef must exist
sourceQuoteRef must be currently usable
owning paper must still exist
current user must have permission
owning paper must be inside fixed conversation search scope
```

Currently usable means:

```text
returned by this turn's read_locations
returned by this turn's trace_source_quotes with status=OK
registered in the current conversation Source Quote registry
and still passing current permission/search-scope checks
```

Answer Guard does not require current Reading Model READY. It does not judge
semantic sufficiency.

The registry is conversation-scoped:

```text
conversation_id + sourceQuoteRef + firstSeenTurnId
```

Global lookup in `paper_source_quotes` is not enough to make a Source Quote
citeable in the current final answer.

### 8H. How Should Opaque Refs Stay Stateless?

Recommended answer:

Resolve refs from durable product tables, not from conversation-local mutable
state.

Adopted.

Target mapping:

```text
paperHandle -> durable product_paper_handles row -> Product Paper row
pageRef / sectionRef / tableRef / figureRef -> paper_locations.location_ref
sourceQuoteRef -> paper_source_quotes.source_quote_ref
```

Rules:

```text
LLM cannot mint refs
invalid refs are rejected
refs do not point to in-memory list snapshots
ConversationReferenceRegistry is not the new identity source of truth
conversation reference records may support UI history/source preview only
```

Implementation module:

```text
ProductReadingRefResolver
- resolvePaperHandle(...)
- resolveLocationRef(...)
- resolveSourceQuoteRef(...)
```

### 8I. How Should `paperHandle` Be Generated?

Recommended answer:

Use a stable product-level opaque handle, not a conversation-local ref and not
raw `paperId`.

Adopted.

Physical mapping:

```text
product_paper_handles
- id
- paper_handle
- paper_id
- created_at
```

Generation:

```text
paper_handle_<random non-meaningful token>
```

Rules:

```text
paperHandle can be reused across tool calls
paperHandle resolves after service restart
paperHandle does not depend on the current list_papers result
paperHandle does not expose paperId, SQL id, user id, org id, filename, or title
paperHandle resolution is not authorization
after resolve, every tool still checks user permission, fixed search scope, and READY
```

Implementation modules:

```text
ProductPaperHandleCodec
- createHandle()
- isPaperHandle(...)

ProductReadingRefResolver
- resolvePaperHandle(...)
```

### 8J. How Should `locationRef` Be Generated?

Recommended answer:

`locationRef` is a persistent reading-location ref for the current READY Reading
Model. It is not a search hit id and not a chunk id.

Adopted.

Physical mapping:

```text
paper_locations.location_ref
```

Generation:

```text
page_ref_<random non-meaningful token>
section_ref_<random non-meaningful token>
table_ref_<random non-meaningful token>
figure_ref_<random non-meaningful token>
```

MVP rebuild rules:

```text
Reading Model rebuild may generate new locationRef values
old locationRef values are not returned by new list/search tools unless still current
read_locations accepts only current READY Reading Model locationRef values
old Source Quotes remain valid because paper_source_quotes stores content + original locationRef + sourceSpan
trace_source_quotes can reopen old Source Quotes
```

This avoids forcing section/table/figure refs to stay stable across parser
versions in MVP.

### 9. How Should Search And Read Relate?

Recommended answer:

Search never creates Source Quotes. Search only returns `locationRef` candidates.

Adopted.

```text
find_reading_locations -> location candidates
read_locations -> Source Quotes
final answer -> Source Quote refs
```

This keeps search snippets from becoming hidden evidence.

### 9A. Should `find_reading_locations` Return `preview`?

Recommended answer:

Yes, but `preview` is navigation text only.

Adopted.

Rules:

```text
preview helps the LLM decide whether to call read_locations
preview is not Source Quote content
preview does not create sourceQuoteRef
preview does not enter Answer Guard's citeable support set
preview cannot be cited in final paper-content answers
```

Only `read_locations` and `trace_source_quotes` return content that can support
paper-content claims.

### 9B. Should `find_reading_locations` Expose Scores?

Recommended answer:

No. Return ordered candidates only.

Adopted.

Internal ranking may use:

```text
BM25
vector score
metadata boost
section/title boost
deterministic ordering
```

MVP must not use a generative LLM reranker. If a future reranker is added, it is
a scoring model only:

```text
input: queryText + candidate text
output: order or score
```

It must not:

```text
rewrite queryText
summarize
generate text
decide what the answer should say
judge Source Quote sufficiency
```

Output:

```text
ordinal
locationRef
locationType
paperHandle
pageNumber
sectionTitle
preview
```

Do not expose:

```text
score
vectorScore
bm25Score
rerankScore
rerankReason
ranking metadata
```

The LLM chooses whether to call `read_locations` from candidate order and
`preview`. Retrieval tuning happens through trace and eval, not tool arguments
or score-visible prompting.

### 9C. Should `search_paper_candidates` Return `preview`?

Recommended answer:

Yes, but it is candidate-selection text only.

Adopted.

Output:

```text
paperHandle
title
originalFilename
authors
year
venue
preview
```

Rules:

```text
preview may come from title / abstract / tags / metadata
preview helps the LLM decide which papers to inspect next
preview is not Source Quote content
preview does not justify recommendation reasons
preview does not support paper-content answers
recommendation reasons require read_locations and sourceQuoteRef
score and rerankReason are not exposed
```

This makes paper search easier for the LLM without weakening the Source Quote
boundary.

### 10. What Is The Implementation Order Without Compatibility?

Recommended answer:

```text
1. Add domain tables and repositories:
   ProductPaperHandle, PaperReadingModel, PaperPage, PaperLocation,
   ReadingChunk, PaperSourceQuote

2. Add PaperReadingModelBuilder and PaperReadingModelService:
   ParsedPaper -> PaperReadingModel + PaperPage + PaperLocation

3. Add ReadingChunkBuilder:
   PaperPage/PaperLocation -> ReadingChunk

4. Add ReadingIndexService:
   ReadingChunk -> paperloom_reading_chunks with modelVersion/indexVersion

5. Add ReadingReadyGate:
   current PaperReadingModel has model_status=READING_MODEL_READY and
   index_status=READING_INDEX_READY

6. Add ProductPaperHandleCodec and ProductReadingRefResolver:
   paperHandle / locationRef / sourceQuoteRef -> durable product rows

7. Add ProductReadingToolRegistry:
   new 9-tool catalog only

8. Add ReadLocationsService:
   PaperLocation -> PaperSourceQuote

9. Add SourceQuoteAnswerGuard:
   structural sourceQuoteRef validation

10. Wire the parse/vectorize pipeline to the new path.

11. Delete or quarantine old evidence/citation tool path from the target product ReAct flow.
```

Adopted.

### 11. What Tests Prove The Clean MVP?

Recommended answer:

```text
PaperReadingModelBuilderTest
PaperReadingModelServiceTest
ReadingChunkBuilderTest
ReadingChunkTextPolicyTest
ReadingChunkDeterministicChunkingTest
ReadingChunkHitLocationMappingTest
ReadingIndexDocumentBuilderTest
ReadingIndexModelVersionFilterTest
ProductPaperHandleCodecTest
ProductReadingRefResolverTest
LocationRefRebuildTest
SearchPaperCandidatesPreviewBoundaryTest
FindReadingLocationsToolTest
FindReadingLocationsNoHiddenRewriteTest
FindReadingLocationsNoGenerativeRerankTest
FindReadingLocationsUnavailableLocationTypeTest
FindReadingLocationsNoMatchTest
FindReadingLocationsDoesNotExposeChunkRefTest
FindReadingLocationsRankingOutputTest
ReadLocationsServiceTest
SourceQuoteIdempotencyTest
SourceQuoteSplitPolicyVersionTest
SourceQuoteSplitIndexDeterminismTest
SourceQuoteContentHashChangeTest
TraceSourceQuotesToolTest
TraceSourceQuotesOldLocationMetadataTest
TraceSourceQuotesPermissionBoundaryTest
TraceSourceQuotesDeletedPaperTest
TraceSourceQuotesSearchScopeTest
ReadLocationsRejectsTracedOldLocationRefTest
CurrentLocationNotFoundTest
SourceQuoteAnswerGuardTest
SourceQuoteAnswerGuardCurrentUsabilityTest
SourceQuoteAnswerGuardPermissionBoundaryTest
SourceQuoteAnswerGuardSearchScopeTest
SourceQuoteAnswerGuardNoReadyRequirementTest
SourceQuoteAnswerGuardNoSemanticSufficiencyTest
ConversationSourceQuoteRegistryScopeTest
ReadingReadyGateTest
```

Adopted.

Integration smoke:

```text
ParsedPaper fixture
-> PaperReadingModelBuilder
-> ReadingChunkBuilder
-> fake/indexed reading search
-> find_reading_locations returns locationRef
-> read_locations returns sourceQuoteRef
-> answer guard accepts cited answer
-> answer guard rejects uncited paper claim
```

## No Compatibility Final Decision

The target implementation is not:

```text
old evidence tools + renamed fields
old PaperTextChunk as the main reading model
old vectorizationStatus as READY
search result snippets treated as evidence
```

The target implementation is:

```text
PaperReadingModelBuilder
-> ReadingChunkBuilder
-> ReadingIndexService
-> ProductReadingToolRegistry
-> ReadLocationsService
-> PaperSourceQuote
-> SourceQuoteAnswerGuard
```

This is the implementation path for the branch when compatibility is not a constraint.
