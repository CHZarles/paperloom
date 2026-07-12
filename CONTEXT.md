# PaperLoom

PaperLoom is a research-paper RAG product. Its domain language separates the product paper library
from benchmark corpora so product behavior is not confused with eval data.

## Language

**Product Paper Library**:
The user's permissioned collection of research paper PDFs that have entered the product upload,
parsing, indexing, and Source Quote pipeline.
_Avoid_: benchmark library, structured import library, eval paper library

**Product Paper**:
A research paper PDF stored in the product data domain. It may be searchable only after the product
parser/indexer has produced text chunks and source metadata.
_Avoid_: eval paper, structured import, benchmark record

**Eval Corpus**:
A benchmark-only dataset such as LitSearch or QASPER, stored outside the product data domain and
used only by evaluation harnesses.
_Avoid_: product paper library, uploaded papers

**Retrieval Corpus**:
The explicit data domain selected for a retrieval run, such as `PRODUCT_LIBRARY`,
`EVAL_LITSEARCH`, or `EVAL_QASPER`.
_Avoid_: includeEval flag, mixed corpus

**Paper Pack**:
An eval-only curated paper situation containing target papers plus predecessor, successor,
distractor, contradiction, or boundary papers needed to test harness behavior.
_Avoid_: topic folder, product collection, paper dump

**Golden Case**:
An evidence-first benchmark item that defines the user question, expected intent, required
evidence anchors, claim obligations, answer contract, and trace obligations.
_Avoid_: flat QA row, final answer string, smoke test case

**Evidence Anchor**:
A gold reference to the smallest inspectable paper unit, such as a span, table cell, figure,
formula, algorithm step, or metadata fact, that supports or refutes a benchmark claim.
_Avoid_: citation text, answer regex, whole paper

**Answer Contract**:
The required answer shape for a Golden Case, such as exact fields, comparison axes, graph nodes, or
uncertainty sections, without requiring one canonical prose wording.
_Avoid_: gold prose answer, style guide, prompt template

**Trace Obligation**:
A required visible harness artifact or action, such as identity resolution, table retrieval,
claim-to-evidence linking, contradiction surfacing, or uncertainty verification.
_Avoid_: hidden heuristic, final answer requirement, debug log

**Harness Run Trace**:
An eval-facing record of one harness attempt, including intent frame, retrieval plan, evidence
ledger, claim graph, reasoning artifacts, verification pass, final answer, and diagnostics.
_Avoid_: product trace artifact, raw debug log, chat transcript

**Scoring Profile**:
The named set of scoring rules that determines how Golden Cases are validated across retrieval,
claim, reasoning, and trace layers.
_Avoid_: metric name, prompt rubric, benchmark id

**Compatibility Projection**:
A derived flat eval row generated from a Golden Case so existing `RagBenchmarkCase` runners can
smoke-test the same question without replacing the richer golden source of truth.
_Avoid_: canonical case, duplicate dataset, lossy migration

**PDF Reading Readiness**:
The product-facing state describing which PDF-derived reading assets exist for a product paper,
such as text chunks, page screenshots, table crops, figure crops, and parser artifacts.
Only Product Papers with ready PDF reading assets enter the Product ReAct paper-reading scope.
_Avoid_: eval import readiness, structured import readiness

**PaperLoom Reading Model**:
The product-owned readable representation of a Product Paper, including pages, readable locations,
and source spans. It is derived from parser output but is not the parser output itself.
_Avoid_: MinerU output, raw parser artifact, chunk index

**Current Reading Model**:
The one `paper_reading_models` row for a Product Paper with `is_current=true`,
`model_status=READING_MODEL_READY`, and `index_status=READING_INDEX_READY`. Product ReAct
paper-reading tools read, list, and search only this model. It is not `paper.vectorizationStatus`.
_Avoid_: vectorizationStatus, latest parser artifact, inferred readiness

**Source Span**:
A durable source-location range inside a Product Paper that connects readable text back to its paper
page or reading location. A Source Span is not a Source Quote until the text is read.
_Avoid_: Source Quote, bbox, chunk id, parser provenance

**Source Quote**:
A quote read from a product paper PDF, such as page text, table content, or a figure
caption, with source location back to the product paper. Source Quote creation is idempotent:
re-reading the same `paperId + modelVersion + locationRef + splitPolicyVersion + splitIndex +
contentHash` reuses the same `sourceQuoteRef` from MySQL. `splitPolicyVersion` is internal and
changes when the Source Quote splitting policy changes. Within one `splitPolicyVersion`, splitting
must be deterministic, so the same input text and location produce the same `splitIndex` sequence.
Final answers may cite only currently usable Source Quotes returned by this turn's read results or
this turn's successful trace results. Conversation Source Quote registry rows authorize trace
lookups; they do not support final paper-content claims until a current-turn trace returns the
stored Source Quote.
_Avoid_: paper handle, section handle, page handle, navigation anchor, Redis mapping, LLM-facing split policy, separate span-id

**Source Handle**:
An opaque reference to a product paper object or location, such as a paper, section, page, table, or
figure. Source handles are for identity and navigation; they cannot support final paper-content
claims unless resolved to a Source Quote. LLM-facing source handles must resolve from durable
product data or persisted reference records, not in-memory list snapshots.
_Avoid_: citation, proof, source quote

**Paper Handle**:
A stable product-level opaque token that resolves to one Product Paper through durable product data.
It is used by LLM-facing paper tools instead of raw `paperId`. It must not expose raw `paperId`,
SQL ids, user ids, org ids, filenames, or titles. Resolving a Paper Handle does not grant access;
tools still check user permission, fixed conversation search scope, and READY status.
_Avoid_: paperId, conversation-local ref, ordinal

**Location Ref**:
A stable opaque reading-location token inside the current READY Reading Model, such as
`page_ref_...`, `section_ref_...`, `table_ref_...`, or `figure_ref_...`. It resolves through
`paper_locations.location_ref`. It is not a search hit id, chunk id, SQL id, or Source Quote.
Reading Model rebuild may create new Location Refs; existing Source Quotes remain traceable through
stored Source Quote content and source span.
_Avoid_: chunkRef, search hit id, sourceQuoteRef, parser id

**Structured Reading Location**:
A PAGE, SECTION, TABLE, or FIGURE location stored in `paper_locations` for one Current Reading
Model. Structured Reading Locations are navigation and reading coordinates; PAGE and SECTION
locations read from page/section rows, while TABLE and FIGURE locations target canonical Reading
Model Elements rather than parser table or figure ids.
_Avoid_: parser block, table id, figure id, section title as coordinate

**Physical Page**:
A numbered page surface in the source PDF, whether or not the parser found readable text on it. A
Physical Page may have visual evidence and a PAGE location even when it cannot produce a textual
Source Quote.
_Avoid_: readable page, page text chunk

**Reading Model Element**:
A product-owned retained parser object inside one PaperLoom Reading Model, such as a heading,
paragraph, table, image, chart panel, formula, code block, footnote, or raw labeled visual fragment.
It may be a top-level location owner or a child attached to a parent table/figure element; either
way, it must remain persisted and retrievable by structured inspection. Every MinerU `content_list`
item should be represented by one canonical Reading Model Element; creating a Structured Reading
Location is a navigation policy and not a prerequisite for retention.
_Avoid_: skipped object, raw MinerU item, chunk, Source Quote, parser table id, parser figure id

**Visual Asset**:
A persisted visual evidence object or visual evidence gap for a Product Paper, such as a page
screenshot, a PDF-rendered table/figure crop, or a parser-provided image referenced by
`content_list.img_path`. Parser-provided Visual Assets must retain the parser image path, status,
and reverse link to the owning Reading Model Element when one exists, so visual-only tables, charts,
figures, and formulas remain inspectable even when they have no readable text or Structured Reading
Location.
_Avoid_: skipped figure, transient parser file, markdown image reference

**Reading Chunk**:
An internal retrieval chunk bound to a Current Reading Model and a Location Ref. `originalText` is
the readable source block. `searchText` is retrieval text and may include headings, parser labels,
summary hints, or expanded terms. Search tools may search `searchText`, but Source Quotes must be
created from original readable text, not `searchText`. MVP chunking is deterministic from reading
locations: page/section paragraph chunks, one table chunk, and one figure-caption chunk. Chunk size
and overlap are internal policy, not LLM-facing inputs. Reading Chunks are derived only from Current
Reading Model persistence: `PaperReadingElement`, `PaperLocation`, `PaperPage`, and `PaperSection`.
They must not be generated directly from MinerU artifacts, `ParsedPaper`, or legacy
`paper_text_chunks`. ReadingChunk hits are mapped back to their owning Location Ref before tool
output; `chunkRef` is not returned to the LLM.
_Avoid_: Source Quote, Paper Location, LLM-facing ref, search result, chunkRef, MinerU output,
ParsedPaper, legacy paper_text_chunks

**Reading Index**:
The internal Elasticsearch index for Reading Chunks. MVP uses `paperloom_reading_chunks` and filters
by the Current Reading Model's `modelVersion`. `indexName`, `modelVersion`, and `indexVersion` are
internal implementation fields, not LLM-facing inputs.
_Avoid_: tool parameter, Source Quote, Paper Location

**`sourceQuoteRef`**:
An opaque field value that points to a Source Quote. Final paper-content claims may cite only
`sourceQuoteRef` values returned by `read_locations` or resolved by `trace_source_quotes`.
_Avoid_: citation number, paper handle, page ref, section ref

**Source Quote Chip**:
A user-visible citation affordance for a Source Quote. Its display number is only rendering; the
stable product anchor is the carried `sourceQuoteRef`.
_Avoid_: rendered citation number, paper link, evidence detail row

**Paper Candidate Search**:
The paper-level search operation, exposed as `search_paper_candidates`, that recalls candidate
papers from the fixed conversation search scope using caller-supplied `queryText` over title,
abstract, catalog tags, metadata, BM25, vector search, and related paper-level retrieval methods. It
finds READY candidate papers, not Source Quotes or final recommendations. It may return a
non-citeable `preview` from title, abstract, tags, or metadata to help paper selection. The tool may
normalize, tokenize, embed, filter, and rank the query, but must not call a hidden generative LLM to
rewrite it or rerank candidates. Candidate order is the only LLM-facing ranking signal; internal
scores and rerank reasons are not exposed.
_Avoid_: identity lookup, source quote retrieval, recommendation verdict, score

**Navigation Preview**:
A short non-citeable text fragment returned by paper-candidate or reading-location search to help
choose the next reading action. It is not a Source Quote, recommendation reason, answer snippet, or
claim support.
_Avoid_: evidence, citation, source quote, recommendation reason

**Paper Identity Lookup**:
The paper-level lookup operation, exposed as `find_papers_by_identity`, that resolves a specific
paper mention from identity hints such as title, filename, DOI, arXiv id, author, or year. It
returns only READY papers. It is not semantic topic search and must not accept search needs, queries,
questions, semantic needs, related-to topics, recommendation needs, ordinal hints, or ref hints. If
identity hints match multiple papers, it returns ambiguous paper cards rather than guessing. If the
model already has a `paperHandle`, it passes that handle directly to the consuming tool instead of
calling identity lookup.
_Avoid_: paper candidate search, recommendation, source quote retrieval

**Identity Hint**:
A paper-level metadata clue used by Paper Identity Lookup, such as title, filename, DOI, arXiv id,
author, or year. Identity hints narrow Product Papers; they are not Paper Handles, ordinals, Source
Quotes, or paper-content evidence.
_Avoid_: search query, paper handle, citation, ordinal

**Ambiguous Paper Identity Match**:
A Paper Identity Lookup result where multiple READY scoped Product Papers satisfy the supplied
identity hints. It is a product-state choice set, not a selected paper and not same-turn reading
authorization.
_Avoid_: selected paper, hidden paper disclosure, source quote

**Product State Item**:
A backend-controlled Product Reading UI payload for the current chat turn. It can present
navigation choices to the user, but it is not evidence, not a Source Quote, and not durable
conversation history.
_Avoid_: citation, Source Quote, raw tool result, persisted evidence

**Reading Paper Choice**:
A Product State Item representing one concrete Product Paper option with a Paper Handle and display
metadata. Clicking it creates an explicit clicked paper anchor for a later turn; titles, filenames,
or ordinals are not the selection identity.
_Avoid_: ordinal selection, paper title anchor, Source Quote, recommendation reason

**Paper Library Browse**:
The paper-level browsing operation, exposed as `list_papers`, that lists from the fixed conversation
search scope with explicit metadata filters such as title, filename, author, year, venue, type,
topic, and sort. It returns only READY papers. Each result item includes a `paperHandle`; any ordinal
is only a display label. It is deterministic browse/filter and must not accept search needs, queries,
questions, semantic needs, or topic text.
_Avoid_: paper candidate search, semantic retrieval, source quote retrieval, recommendation

**Paper Facets**:
The product paper-library facet view that returns available filtering dimensions and counts such as
catalog topics, paper types, years, authors, and venues inside the fixed conversation search scope.
Facets are an internal capability of paper listing, exposed through `list_papers(includeFacets=true)`
rather than a separate LLM-facing tool.
_Avoid_: paper-content source quote, recommendation, reading location

**Recommendation**:
A final answer that suggests papers to the user. Recommendation reasons that mention paper content,
methods, experiments, results, limitations, or comparisons must be supported by Source Quotes.
_Avoid_: candidate recall, catalog list, paper discovery without source quotes

**Recommendation Candidate**:
A paper-level candidate assembled before final answer generation. It may include metadata match
reasons and supporting Reading Locations, but it is not itself a final recommendation and does not
authorize paper-content claims unless those locations are read into Source Quotes.
_Avoid_: final recommendation, Source Quote, raw location hit, answer citation

**Beginner Paper Role**:
A product-facing classification for why a paper belongs in an entry-level reading path, such as
survey, benchmark, method, critique, background, or example. A Beginner Paper Role must come from
Product Paper metadata or quote-backed evidence. The model may not create this role from title
wording, hidden state claims, or hard-coded keyword matches.
_Avoid_: model-inferred role, title guess, recommendation proof, paper-content claim

**Paper Outline**:
A whole-paper structure view for one or more Product Papers, including section headings, section
roles, page ranges, and parser quality signals. A paper outline helps understand the paper shape; it
is not a Source Quote. Outline contents must remain grouped by Product Paper, and every returned
section location must resolve to its owning paper.
_Avoid_: Source Quote, paper summary, content answer

**Paper Location List**:
A deterministic navigation list of section, page, table, or figure refs for chosen Product Papers.
It may be narrowed by explicit page range when the user asks for specific pages. It returns refs for
`read_locations`; it is not semantic search and not a Source Quote. PAGE locations are required for
READY papers. SECTION, TABLE, and FIGURE locations are optional enhancements.
_Avoid_: paper content, recommendation, semantic reading need, Source Quote

**Reading Location Candidate**:
An ordered candidate returned by `find_reading_locations`. It has a `locationRef` and may have a
non-citeable `preview`. Candidate order is the only LLM-facing ranking signal; internal scores and
rerank reasons are not exposed. MVP ranking uses BM25, vector search, metadata boosts, and
deterministic ordering, not a generative LLM reranker.
_Avoid_: Source Quote, score, rerank reason, chunk id

**Reading Location Search**:
The in-paper search operation, exposed as `find_reading_locations`, that uses caller-supplied
`queryText` to find candidate reading locations inside explicitly selected READY papers. The tool may
normalize, tokenize, embed, filter, and rank the query, but it must not call a hidden generative LLM
to rewrite it, rerank candidates, summarize, or decide what the user should read. If the search
misses, the LLM calls the tool again with a different visible `queryText`. If requested
`locationTypes` do not exist in the selected papers, the tool returns `NO_MATCHING_LOCATION_TYPE`;
that means the structure is unavailable, not that the paper lacks the requested concept. If the
structure exists but the query has no hit, the tool returns `NO_MATCH`.
_Avoid_: reading need, hidden query rewrite, generative rerank, Source Quote, answer generation,
paper-content absence claim

**Session State**:
The current conversation's fixed search-scope label, readable paper count, and similar
product-state facts. Search scope is fixed when the conversation starts and enforced by the
Harness. Session state supports product-state answers; it does not list historical refs or read
paper content.
_Avoid_: hidden ref store, paper source quote, citation trace

**Chat Conversation**:
A durable user-owned paper-reading thread whose history, memory, search scope, and reference
mappings form one auditable answer context.
_Avoid_: browser tab, WebSocket connection, current selection

**Chat Turn Target**:
The Chat Conversation that one outgoing user message is intended to append to and whose scope,
memory, and references govern answer generation for that turn.
_Avoid_: per-user current conversation, selected sidebar item, active generation

**Conversation Selection**:
The client-side navigation state showing which Chat Conversation a user is viewing. It may guide UI
defaults, but it is not the authoritative Chat Turn Target after a message is submitted.
_Avoid_: chat turn target, session scope, conversation ownership

**Reference Memory**:
Persistent conversation memory about already-read Source Quotes, attempted papers, and unresolved
reading questions. Reference Memory helps navigation but cannot support final paper-content claims.
_Avoid_: evidence, Source Quote, hidden tool state

**Reading Turn Anchor**:
An explicit, current-turn input anchor supplied by the reading entry point, such as a clicked
`sourceQuoteRef` from a source chip. A Reading Turn Anchor may be shown to the LLM as tool input,
but it is not quote content and cannot support a final answer until a current-turn tool returns the
corresponding Source Quote.
_Avoid_: conversation memory, user-typed ref, rendered citation number, final answer evidence

**Conversation Source Quote Registry**:
The current conversation's registered Source Quote set, stored in MySQL as
`conversation_id + sourceQuoteRef + firstSeenTurnId`. It controls whether an existing Source Quote
may be traced in this conversation after current permission and search-scope checks. It is not the
Source Quote storage table, not the Source Quote identity source, and not direct final-answer
evidence.
_Avoid_: global paper_source_quotes lookup, Redis memory, identity table

**Source Quote Trace**:
A deterministic lookup of explicit `sourceQuoteRef` values supplied as Reading Turn Anchors and
validated against the Conversation Source Quote Registry. Source Quote Trace is used for follow-up
questions about already-read Source Quotes. It returns stored source quote text and source-location
metadata. It does not require the paper to have a current READY Reading Model, but it must check the
owning paper still exists, the user currently has permission, and the paper is inside the fixed
conversation search scope. If not, it returns `SOURCE_QUOTE_UNAVAILABLE` without quote content. It
cannot trace model-invented refs, user-typed refs without an explicit turn anchor, raw internal ids,
paper handles, page refs, section refs, table refs, or figure refs. Rendered citation numbers such as
`[2]` are not source refs. Returned source-location refs are metadata, not a shortcut for reading new
paper content. Broader context reading must use traced metadata such as `paperHandle`, page number,
or section label to list current READY paper locations, then read current returned refs. If the
current location no longer exists, return `CURRENT_LOCATION_NOT_FOUND`.
_Avoid_: semantic search, new source quote retrieval, model memory, raw paper id lookup, reading shortcut,
rendered citation number lookup, page ref lookup, section ref lookup, old location ref reuse,
permission bypass

**Trace Artifact**:
A JSON file written by the product runtime that records what happened during a Product ReAct turn or
memory compression call. It is offline eval input, not business state.
_Avoid_: eval result, business audit row, chat status, trace database record

**Append-Only Trace Artifact**:
A trace artifact that is written as a new JSON file and not modified later. Related artifacts, such
as a turn trace and its memory compression trace, are grouped offline by conversation and generation
ids instead of being merged in place.
_Avoid_: trace append, JSON patch, read-modify-write trace update

**Trace Drop**:
The intentional loss of a trace artifact when the asynchronous trace queue is full or the trace sink
cannot accept new work. Trace drop is logged but does not affect the product answer.
_Avoid_: business failure, degraded chat turn, retryable user error

**Raw Trace Data**:
Unredacted trace content such as user messages, prompts, model responses, tool arguments, tool
results, memory, diagnostics, and answer envelopes. It is allowed only for first-phase local trace
artifacts and requires a separate productionization decision before broader use.
_Avoid_: sanitized analytics, user-visible logs, production-safe telemetry

**Offline Eval**:
A separate future system or script that reads trace artifacts to analyze harness, LLM, and tool-call
quality. It is not part of the live product request path.
_Avoid_: online eval, product scoring, chat-time evaluation
