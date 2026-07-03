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

- `PaperPage`
- `PaperLocation`
- `SourceSpan`
- READY gate result

MVP rules:

- READY requires page-level readable content.
- PAGE location is required.
- SECTION / TABLE / FIGURE locations are best effort.
- ReAct tools must not read MinerU raw artifacts directly.
- MinerU schema changes should be contained inside the builder.

### 2. Chunk + Index Builder

Purpose:

Make paper content searchable by the agent.

MVP chunk types:

- page chunk
- section chunk, if section boundaries exist
- paragraph chunk, if paragraph blocks exist
- table-text chunk, if MinerU produced readable table text
- figure-caption chunk, if caption or figure text exists

MVP index:

- Elasticsearch BM25
- dense vector
- metadata filter

Required metadata:

- `paperId`
- permission / org scope fields
- page number
- location type
- section title or section handle, if available
- content type
- parser version
- index version

Text policy:

- original text is the source for Source Quotes.
- enriched search text may improve retrieval.
- summary text and enriched text cannot support final paper-content claims.

### 3. Agent Search / Read Tools

Purpose:

Let the agent search and read through tools, not through hidden state.

MVP tools:

- `search_paper_candidates`
- `find_reading_locations`
- `read_locations`
- `trace_source_quotes`

MVP behavior:

- `search_paper_candidates` finds candidate READY papers.
- `find_reading_locations` performs fuzzy search inside selected READY papers.
- `read_locations` reads explicit `locationRef` values and creates Source Quotes.
- `trace_source_quotes` reopens existing Source Quotes.
- Tools are stateless.
- Tools do not expose raw ES, SQL, tuning parameters, or parser internals.

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
- `locationRef`
- page number
- content kind
- original text
- source span

MVP answer rule:

- Final paper-content claims must cite `sourceQuoteRef`.
- `sourceQuoteRef` must come from `read_locations` or `trace_source_quotes`.
- Summary, memory, search result snippets, and model knowledge cannot support final claims.
- If no Source Quote supports the claim, answer with insufficient evidence.

### 5. Persistent Reference Memory

Purpose:

Keep already-read references recoverable without making memory a source of truth.

MVP storage:

- MySQL stores Source Quotes and reference mappings.
- Redis stores only short-lived context.

MVP memory rules:

- Memory can remember papers tried, locations read, unresolved questions, and sourceQuoteRefs.
- Memory can help the agent navigate back to Source Quotes.
- Memory cannot support final paper-content claims.
- Historical source preview must be recoverable from persistent records, not Redis.

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
