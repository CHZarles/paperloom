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

**PDF Reading Readiness**:
The product-facing state describing which PDF-derived reading assets exist for a product paper,
such as text chunks, page screenshots, table crops, figure crops, and parser artifacts.
Only Product Papers with ready PDF reading assets enter the Product ReAct paper-reading scope.
_Avoid_: eval import readiness, structured import readiness

**PaperLoom Reading Model**:
The product-owned readable representation of a Product Paper, including pages, readable locations,
and source spans. It is derived from parser output but is not the parser output itself.
_Avoid_: MinerU output, raw parser artifact, chunk index

**Source Span**:
A durable source-location range inside a Product Paper that connects readable text back to its paper
page or reading location. A Source Span is not a Source Quote until the text is read.
_Avoid_: Source Quote, bbox, chunk id, parser provenance

**Source Quote**:
A quote read from a product paper PDF, such as page text, table content, or a figure
caption, with source location back to the product paper.
_Avoid_: paper handle, section handle, page handle, navigation anchor

**Source Handle**:
An opaque reference to a product paper object or location, such as a paper, section, page, table, or
figure. Source handles are for identity and navigation; they cannot support final paper-content
claims unless resolved to a Source Quote. LLM-facing source handles must resolve from durable
product data or persisted reference records, not in-memory list snapshots.
_Avoid_: citation, proof, source quote

**`sourceQuoteRef`**:
An opaque field value that points to a Source Quote. Final paper-content claims may cite only
`sourceQuoteRef` values returned by `read_locations` or resolved by `trace_source_quotes`.
_Avoid_: citation number, paper handle, page ref, section ref

**Paper Candidate Search**:
The paper-level search operation, exposed as `search_paper_candidates`, that recalls candidate
papers from the fixed conversation search scope using topic or need signals over title, abstract,
catalog tags, metadata, BM25, vector search, and related paper-level retrieval methods. It finds READY
candidate papers, not Source Quotes or final recommendations.
_Avoid_: identity lookup, source quote retrieval, recommendation verdict

**Paper Identity Lookup**:
The paper-level lookup operation, exposed as `find_papers_by_identity`, that resolves a specific
paper mention from identity hints such as title, filename, DOI, arXiv id, author, or year. It
returns only READY papers. It is not semantic topic search and must not accept search needs, queries,
questions, semantic needs, related-to topics, recommendation needs, ordinal hints, or ref hints. If
identity hints match multiple papers, it returns ambiguous paper cards rather than guessing. If the
model already has a `paperHandle`, it passes that handle directly to the consuming tool instead of
calling identity lookup.
_Avoid_: paper candidate search, recommendation, source quote retrieval

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

**Paper Outline**:
A whole-paper structure view for one or more Product Papers, including section headings, section
roles, page ranges, and parser quality signals. A paper outline helps understand the paper shape; it
is not a Source Quote. Outline contents must remain grouped by Product Paper, and every returned
section location must resolve to its owning paper.
_Avoid_: Source Quote, paper summary, content answer

**Paper Location List**:
A deterministic navigation list of section, page, table, or figure refs for chosen Product Papers.
It may be narrowed by explicit page range when the user asks for specific pages. It returns refs for
`read_locations`; it is not semantic search and not a Source Quote.
_Avoid_: paper content, recommendation, semantic reading need, Source Quote

**Session State**:
The current conversation's fixed search-scope label, readable paper count, and similar
product-state facts. Search scope is fixed when the conversation starts and enforced by the
Harness. Session state supports product-state answers; it does not list historical refs or read
paper content.
_Avoid_: hidden ref store, paper source quote, citation trace

**Reference Memory**:
Persistent conversation memory about already-read Source Quotes, attempted papers, and unresolved
reading questions. Reference Memory helps navigation but cannot support final paper-content claims.
_Avoid_: evidence, Source Quote, hidden tool state

**Source Quote Trace**:
A deterministic lookup of explicit `sourceQuoteRef` values supplied by the current model context,
clicked source chips, or persistent Source Quote records. Source Quote Trace is used for follow-up
questions about already-read Source Quotes. It returns stored source quote text and source-location
metadata. It cannot trace model-invented refs, raw internal ids, paper handles,
page refs, section refs, table refs, or figure refs. Rendered citation numbers such as `[2]` are not
source refs. Returned source-location refs are metadata, not a shortcut for reading new paper
content; broader context reading must first list paper locations for the traced paper and then
read locations from that result.
_Avoid_: semantic search, new source quote retrieval, model memory, raw paper id lookup, reading shortcut,
rendered citation number lookup, page ref lookup, section ref lookup

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
