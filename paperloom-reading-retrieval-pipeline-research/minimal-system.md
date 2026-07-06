# PaperLoom Reading Retrieval Minimal System

Date: 2026-07-04
Status: working baseline

## Problem

The technical selection list is intentionally broad. The current product work needs a smaller system
that can be implemented first, verified end to end, and then expanded.

The minimum goal is not strongest RAG. The minimum goal is:

> PaperLoom can let an agent search papers, read paper locations, produce Source Quotes, and answer
> only with cited Source Quotes.

## MVP Pipeline

```text
PDF
-> MinerU
-> Reading Model Builder
-> Chunk + Index Builder
-> Agent Search / Read Tools
-> Source Quote Store
-> Answer Guard
-> Persistent Reference Memory
```

## MVP Modules

### 1. Reading Model Builder

Purpose:

Convert MinerU output into PaperLoom-owned reading objects.

MVP output:

- `PaperReadingModel`
- `PaperPage`
- `PaperLocation`
- `SourceSpan`
- READY gate result

MVP rules:

- READY is represented by `paper_reading_models`, not inferred from
  `paper.vectorizationStatus`.
- READY requires page-level readable content.
- PAGE location is required.
- SECTION / TABLE / FIGURE locations are optional enhancements and must not
  block READY.
- ReAct tools must not read MinerU raw artifacts directly.
- MinerU schema changes should be contained inside the builder.

### 2. Chunk + Index Builder

Purpose:

Make paper content searchable by the agent.

MVP chunking policy:

- PAGE -> paragraph chunks; if parser paragraphs are unavailable, use fixed
  internal character windows.
- SECTION -> paragraph chunks with `sectionTitle`.
- TABLE -> one table chunk.
- FIGURE -> one caption / readable figure text chunk.
- Every chunk binds to `modelVersion` and `locationRef`.
- Chunk size and overlap are internal product policy.
- LLM-facing tools must not expose `chunkRef`, chunk size, overlap, or `topK`.

MVP index:

- Elasticsearch index: `paperloom_reading_chunks`
- BM25
- dense vector
- metadata filter
- no MVP Elasticsearch alias switching

Required metadata:

- `paperId`
- `modelVersion`
- `locationRef`
- `chunkRef`
- permission / org scope fields
- page number
- location type
- section title or section handle, if available
- content type
- parser version
- index version

Index rules:

- Rebuild writes new documents with a new `modelVersion`.
- `find_reading_locations` filters by the current READY Reading Model's
  `modelVersion`.
- Old index documents may be cleaned asynchronously.
- LLM-facing tools must not expose or accept `indexName`, `modelVersion`, or
  `indexVersion`.

Text policy:

- `originalText` is the readable source block from the Reading Model.
- `searchText` is retrieval text and may include headings, parser labels,
  summary hints, or expanded terms.
- `find_reading_locations` may search `searchText`.
- `find_reading_locations` maps internal ReadingChunk hits back to their owning
  PAGE / SECTION / TABLE / FIGURE `locationRef`.
- `chunkRef` is internal only. It is not returned to the LLM and is not accepted
  by LLM-facing tools.
- `read_locations` must read only `originalText`, `PaperPage.pageText`, table
  text, or caption/figure text.
- `searchText`, summary text, and enriched text cannot become Source Quotes and
  cannot support final paper-content claims.

### 3. Agent Search / Read Tools

Purpose:

Let the agent search and read through tools, not through hidden state.

MVP tools:

- `search_paper_candidates`
- `find_reading_locations`
- `read_locations`
- `trace_source_quotes`

MVP behavior:

- `search_paper_candidates(queryText)` finds candidate READY papers.
- `search_paper_candidates` may return `preview` from title, abstract, tags, or
  metadata, but `preview` is not a Source Quote, recommendation reason, or
  final-answer support.
- `find_reading_locations(queryText)` performs fuzzy search inside selected
  READY papers.
- `find_reading_locations` returns ordered candidates only. It may rank
  internally with BM25, vector score, metadata boost, section/title boost, or
  deterministic ordering, but it must not expose score or rerank reason.
- `queryText` is supplied by the LLM. Search tools may normalize, tokenize,
  embed, filter, and rank it, but they do not understand hidden reading intent
  or rewrite the query with a generative LLM.
- MVP search tools must not call generative LLM/chat clients for query rewrite,
  rerank, summarization, or sufficiency judgment.
- If a future reranker is added, it may only score or order candidates. It must
  not generate text or decide what the answer should say.
- If a search misses, the LLM calls the search tool again with a different
  visible `queryText`.
- `find_reading_locations` searches only existing locations. If the LLM filters
  to an unavailable optional location type, such as TABLE on a paper with no
  extracted table locations, the tool returns `NO_MATCHING_LOCATION_TYPE` and
  empty candidates.
- `NO_MATCHING_LOCATION_TYPE` is a structure result, not a paper-content claim.
  It must not be used to say the paper lacks the requested concept.
- If the requested location type exists but `queryText` has no hit, return
  `NO_MATCH`.
- MVP accepts PAGE-level reads as coarse but valid. `read_locations` controls
  size by Source Quote splitting, not by accepting `queryText` or chunk refs.
- `read_locations` reads explicit `locationRef` values and creates Source Quotes.
- `trace_source_quotes` reopens existing Source Quotes.
- Tools are stateless.
- Tools do not expose raw ES, SQL, tuning parameters, or parser internals.
- Search tools do not call a hidden generative LLM for query rewrite, planning,
  summarization, or answer drafting.
- `find_reading_locations` may return `preview` for navigation, but `preview`
  is not a Source Quote and cannot support final paper-content claims.

This is enough for agentic search:

```text
search candidate papers
-> search likely reading locations
-> read locations
-> inspect Source Quotes
-> search again if evidence is insufficient
-> answer with cited Source Quotes
```

### 4. Source Quote Store And Answer Guard

Purpose:

Prevent final answers from using uncited or unsupported paper claims.

MVP Source Quote:

- `sourceQuoteRef`
- `paperId`
- `modelVersion`
- `locationRef`
- `splitPolicyVersion`
- `splitIndex`
- `contentHash`
- page number
- content kind
- original text
- source span

MVP splitting policy:

- PAGE text is split by parser paragraph boundaries first, then fixed internal
  character windows if needed.
- SECTION text is split by paragraph or by original ReadingChunk source blocks.
- TABLE content creates one Source Quote per table, with internal truncation if
  needed.
- FIGURE content creates one Source Quote per caption or readable figure text
  block.
- Splitting policy is not exposed as tool input.
- Source Quote creation is idempotent by
  `paperId + modelVersion + locationRef + splitPolicyVersion + splitIndex +
  contentHash`.
- Re-reading the same split returns the same `sourceQuoteRef`.
- `splitPolicyVersion` is internal and changes when the Source Quote splitting
  policy changes.
- Within one `splitPolicyVersion`, splitting is deterministic: same input text
  and location produce the same `splitIndex` sequence.
- MVP does not add a separate span-id system for split stability.
- The idempotency mapping is persisted in MySQL, not Redis or conversation
  memory.

MVP answer rule:

- Final paper-content claims must cite `sourceQuoteRef`.
- `sourceQuoteRef` must be currently usable: returned by this turn's
  `read_locations`, returned by this turn's `trace_source_quotes` with
  `status=OK`, or registered in the current conversation Source Quote registry
  and still passing current paper existence, permission, and fixed search-scope
  checks.
- Global lookup in `paper_source_quotes` is not enough to make a Source Quote
  citeable in the current final answer.
- Final-answer validation does not require the owning paper to have a current
  READY Reading Model.
- Final-answer validation does not judge semantic sufficiency.
- Summary, memory, search result snippets, and model knowledge cannot support final claims.
- Paper candidate previews cannot support recommendation reasons or
  paper-content claims.
- Reading-location candidate previews cannot support final paper-content claims.
- If no Source Quote supports the claim, answer with insufficient evidence.

### 5. Persistent Reference Memory

Purpose:

Keep already-read references recoverable without making memory a source of truth.

MVP storage:

- Current Reading Model identity is stored in `paper_reading_models`:
  `paperId + modelVersion + modelStatus + indexStatus + isCurrent`.
- READY for LLM-facing tools means `isCurrent=true`,
  `modelStatus=READING_MODEL_READY`, and `indexStatus=READING_INDEX_READY`.
- Each Product Paper may have at most one current READY Reading Model.
- `paper_locations` and `reading_chunks` bind to `modelVersion`.
- Product Paper identity is resolved from a durable paper handle mapping:
  `paperHandle -> paperId`.
- `paperHandle` is generated once as a stable opaque product token such as
  `paper_handle_...`.
- `paperHandle` must not expose raw `paperId`, SQL ids, user ids, org ids,
  filenames, or titles.
- Resolving `paperHandle` does not grant access; tools still check user
  permission, fixed conversation search scope, and READY status.
- Reading locations are resolved from `paper_locations.location_ref`.
- Location refs are stable inside the current READY Reading Model and use opaque
  prefixes such as `page_ref_...`, `section_ref_...`, `table_ref_...`, and
  `figure_ref_...`.
- Reading Model rebuild may create new location refs. Old location refs are not
  returned by new search/list tools unless still present in the current READY
  Reading Model.
- `read_locations` accepts only location refs from the current READY Reading
  Model.
- Source Quotes are resolved from `paper_source_quotes.source_quote_ref`.
- Existing Source Quotes remain traceable after Reading Model rebuild because
  they persist quoted content, original location ref, and source span.
- `trace_source_quotes` does not require the paper to have a current READY
  Reading Model, but it must check that the paper still exists, the user
  currently has permission, and the paper is inside the fixed conversation
  search scope.
- If those checks fail, return `SOURCE_QUOTE_UNAVAILABLE` without quote content.
- Old Source Quote location refs are metadata only. They must not be passed
  directly to `read_locations`.
- To read nearby current context for an old Source Quote, use traced
  `paperHandle`, `pageNumber`, or section label to call `list_paper_locations`
  against the current READY Reading Model, then call `read_locations` with the
  current returned `locationRef`.
- If no current location matches, return `CURRENT_LOCATION_NOT_FOUND`.
- MySQL stores Source Quotes and reference mappings.
- MySQL stores the conversation Source Quote registry:
  `conversationId + sourceQuoteRef + firstSeenTurnId`.
- The registry is the current conversation's citeable Source Quote set. It is
  not the Source Quote identity source; identity still resolves through
  `paper_source_quotes`.
- Redis stores only short-lived context.

MVP memory rules:

- Memory can remember papers tried, locations read, unresolved questions, and sourceQuoteRefs.
- Memory can help the agent navigate back to Source Quotes.
- Memory cannot support final paper-content claims.
- Historical source preview must be recoverable from persistent records, not Redis.
- Conversation reference records may support UI history, but they are not the
  source of truth for paper handles, location refs, or sourceQuoteRefs.

## MVP Non-Goals

Do not include these in MVP:

- PageIndex
- RAPTOR
- GraphRAG / LightRAG
- ColPali / multi-vector retrieval
- learned sparse retrieval
- late chunking
- visual retrieval
- reference traversal
- complex summary trees
- complex fast model router
- prompt / context cache

These are future enhancements. They may help find evidence, but they must not replace Source Quotes.

## V1 Expansion

V1 improves quality after the MVP loop works.

Add:

- stronger table / figure / formula typed chunks
- paper summary and section summary
- summary-only navigation and rerank
- read-window expansion: adjacent chunk, paragraph, page, section, table context
- simple evidence pack: supporting, conflicting, uncertain quotes
- claim-to-quote verifier
- basic retrieval evaluation harness
- simple FAST / STRONG model roles
- basic index versioning and reprocessing

V1 rule:

Summaries and memory still cannot support final claims.

## V2 Expansion

V2 is benchmark-gated.

Candidates:

- PageIndex
- RAPTOR-like summary tree
- GraphRAG / LightRAG
- learned sparse retrieval
- late chunking
- ColPali / multi-vector retrieval
- visual page index
- visual source attribution
- reference traversal
- prompt / context cache
- advanced fast model router

V2 rule:

Advanced retrieval can only help locate evidence. Final paper-content claims still need Source Quotes.

## Core Invariant

The whole system is built around one invariant:

```text
search and memory help find evidence;
read_locations creates Source Quotes;
final answers cite Source Quotes.
```
