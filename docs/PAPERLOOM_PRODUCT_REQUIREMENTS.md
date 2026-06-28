# PaperLoom Product Requirements

Status: user-confirmed on 2026-06-27; eval data isolation update confirmed on 2026-06-28;
conversation scope and collection rules confirmed on 2026-06-28.

This document is an AI-visible product standard for future PaperLoom development. When old code,
UI copy, or implementation shortcuts conflict with this document, treat this document as the product
direction and record the mismatch before changing behavior.

## North Star

PaperLoom is an evidence-grounded RAG workbench for structured research-paper reading.

The core experience is:

```text
permissioned paper library -> trustworthy retrieval -> evidence-grounded answer -> clickable trace -> continued reading or follow-up
```

Users should be able to place a set of papers into PaperLoom and use natural language to:

- find relevant papers in the papers they are allowed to access
- read one paper or several papers
- compare methods, experiments, limitations, and conclusions
- click citations and inspect the supporting evidence
- continue from a previous citation, paper, or page without the system guessing a new source

PaperLoom is not a generic knowledge-base assistant and not a web-scale literature search engine.

## Product Boundary

PaperLoom only answers from the current user's permissioned paper library.

Confirmed non-goals:

- no all-web paper search
- no generic enterprise knowledge-base framing
- no unsupported factual answer that is not grounded in paper evidence
- no production route hardcoding based on fixed phrases
- no default page-location guessing before benchmarks justify it

The product may recommend papers, but recommendations are limited to papers already uploaded from
PDF, parsed, indexed, and accessible to the current user.

Benchmark and eval corpora are not part of the product paper library. LitSearch, QASPER, PageIndex
experiments, and similar structured datasets must live in an eval-only data domain and must not be
returned by product paper search, source selection, chat retrieval, or chat history recovery.

## Accepted Literature Data

Production input is research paper PDF only.

A production PDF should pass through parsing or OCR, structured extraction, chunking, indexing, and
evidence-asset generation before the UI treats it as fully searchable and fully traceable.

Structured literature data is not a product ingestion path. Benchmark or eval datasets such as
QASPER and LitSearch must be stored in an isolated eval corpus, for example the `paperloom_eval`
MySQL schema and eval-only Elasticsearch indices. Administrator-controlled structured batch import
is not part of the current product; if it is needed later, it requires a separate PRD and a separate
data-domain design rather than reuse of the product paper library.

Product tables must not contain eval-only columns such as `is_eval`, `source_dataset`,
`external_corpus_id`, or `eval_split`. Product APIs must not expose `evalImport`,
`structuredImport`, `EVAL_IMPORT`, or `STRUCTURED_IMPORT`.

Practical UI rule: product papers are PDF-derived papers. They may expose PDF preview, page
evidence, bbox, table crop, figure crop, and parser artifacts when present. Missing product visual
assets should be shown as missing PDF evidence, not as structured-import or eval-import state.

## Parser And OCR Policy

Production default parser is MinerU.

OpenDataLoader is an explicit fallback for local development or MinerU-unavailable situations. It
must not be silently presented as equivalent to the default production parser quality.

A PDF is "fully readable" only when the pipeline has produced, as far as the parser can support:

- text chunks
- page numbers
- section or title context
- table, figure, chart, and formula records where present
- PDF page preview or page screenshot support
- anchor text, bbox, source kind, and provenance fields
- parser artifacts useful for debugging

Parser or OCR failure must be visible to the user. The UI should not present a failed or half-parsed
paper as fully searchable.

Required user-visible states include:

- uploading
- parsing
- parse failed
- parsed but not indexed
- indexing
- searchable
- partially available because PDF visual assets, parser artifacts, table crops, or figure crops are
  missing

## Retrieval Strategy

PaperLoom has three primary retrieval paths.

### Paper Discovery

Use this path for questions such as:

- "What papers are about agent systems?"
- "Recommend RAG papers."
- "What papers do we have on hallucination detection?"

Policy:

- retrieve paper candidates first from paper-level metadata
- use title, abstract, authors, venue, year, DOI, arXiv id, original filename, and combined search text
- after candidate papers are found, attach representative evidence snippets
- optimize for finding the right papers before reading every chunk

This path is measured mainly by LitSearch-style benchmarks.

### Paper Reading And QA

Use this path for questions about the content of one paper or a selected set of papers.

Policy:

- use scoped chunk-level hybrid retrieval
- combine semantic vector search and keyword search
- respect explicit source scope
- avoid cross-paper contamination when a paper scope is known
- use page-window inspection only after trusted anchors or validated candidate selection

This path is measured mainly by Product paper QA, QASPER-style structured QA, and real PDF product
smoke tests.

### Deterministic Follow-Up

Use this path for:

- "Explain reference [2]."
- "Open page 5."
- "Tell me more about the second recommended paper."
- "What does this table mean?"

Policy:

- use persisted reference mappings, known paper id, known page number, or known chunk/table/figure id
- do not infer a different paper or page when no trusted anchor exists
- ask for clarification when the target cannot be resolved

## Conversation Scope And Collections

PaperLoom sessions must have a clear evidence universe. A user should always be able to tell which
papers a conversation can retrieve from, and that scope must not silently change after the session
has begun.

### Scope Modes

Supported conversation scope modes:

- `AUTO_LIBRARY`: the session retrieves from all searchable product papers currently accessible to
  the user. This is the default for a new session.
- `SOURCE_SET_SNAPSHOT`: the session retrieves from a fixed list of resolved product `paperIds`
  captured when the first chat request is accepted.

`COLLECTION` is not a live session scope mode. A collection may be used to create a
`SOURCE_SET_SNAPSHOT`, but locked sessions must retrieve from the snapshot paper ids, not from a
collection that can later be edited.

### Scope Locking

Conversation scope is editable only before the first user message is accepted by the backend.

Rules:

- New sessions default to `AUTO_LIBRARY`.
- Before the first accepted user message, the user may choose `AUTO_LIBRARY`, a collection-derived
  snapshot, or a custom source-set snapshot.
- The backend is the source of truth for scope locking.
- The lock happens when the backend accepts the first chat request and records the user message.
- Clicking send in the frontend must not be treated as a durable lock until the backend accepts the
  request.
- Once locked, the session scope cannot be changed. To use different papers, the user must start a
  new session.
- `AUTO_LIBRARY` also locks after the first accepted message; it cannot be changed to a source set
  inside the same session.
- The backend must reject attempts to send a chat request with a scope inconsistent with the locked
  conversation scope.
- If a locked source-set snapshot later contains papers that are deleted, lose permission, or become
  unsearchable, the session is degraded or invalid. It must not silently expand to all papers or
  dynamically replace the missing papers.

### Source Set Snapshot

A source set snapshot is the fixed evidence boundary for one session.

Rules:

- A snapshot stores resolved product `paperIds`.
- A snapshot may be created from one collection, multiple collections, manual selection, paper-level
  search results, title or metadata regex matches, answer sources, or combinations of these.
- Snapshot creation may preserve a source label and recipe for explanation, but retrieval and audit
  must use the resolved `paperIds`.
- Snapshot paper ids must be product papers only, must pass permission filtering, and must be
  searchable at the time the session locks.
- There is no hard paper-count limit for snapshots. Large snapshots are valid when the user chooses
  a broad collection or batch-matched paper set.

### Collections

A PaperLoom collection is a reusable, user-managed set of product papers.

Rules:

- Collections are first-class product objects, not chat-only temporary UI state.
- Collections store static `paperIds`; they are not dynamic smart folders.
- Regex, paper-level search, metadata conditions, and answer sources may be used as batch-add tools,
  but after the add operation the collection stores concrete paper ids.
- Collections may contain currently unsearchable papers because collection management is a paper
  organization workflow.
- Chat source selection must only use searchable papers from a collection. If a collection contains
  unavailable papers, snapshot creation excludes them and reports the searchable count. If no
  searchable papers remain, the session cannot start from that collection.
- Collection edits do not affect already locked sessions.
- Supported collection visibility is `PRIVATE` and `ORG`.
- `PRIVATE` collections are visible and editable by their owner.
- `ORG` collections are visible within the organization. Their creator and admins may edit them;
  ordinary organization users are read-only.
- Every paper inside a collection still requires normal paper permission checks before it can enter
  a snapshot.

### Reference Focus

Reference follow-up is allowed inside a locked session, but it is not a scope change.

Rules:

- A reference focus may include `referenceNumber`, `conversationRecordId`, `paperId`, `pageNumber`,
  `chunkId`, matched text, bbox, and source-kind metadata from persisted reference mappings.
- Reference focus is temporary and applies to one outgoing question.
- After the question is sent, the frontend clears the reference focus and the session scope remains
  unchanged.
- Reference focus must resolve to evidence inside the current session evidence universe.
- In a `SOURCE_SET_SNAPSHOT` session, reference focus cannot jump to a paper outside the snapshot.
- In an `AUTO_LIBRARY` session, reference focus must come from a persisted reference previously
  generated in that session; users must not be able to hand-write arbitrary paper/page targets to
  bypass scope rules.

### Source Selection UX

Large paper libraries require source selection to be paper-level, searchable, and server-backed.

Rules:

- Source selection for chat must search paper-level metadata, not chunk text.
- Searchable fields include title, original filename, authors, venue, publication year, DOI, arXiv
  id, paper id, and abstract when reliably extracted.
- The source picker must use backend pagination and filtering. It must not load the first fixed page
  of papers and filter locally.
- The picker used for chat source selection only shows searchable papers.
- Product paper-library management views may show unsearchable papers and their pipeline state.
- Answer source actions should create a new session from those sources, not mutate the current
  locked session.
- A locked session should display a read-only scope summary. A user who needs different sources
  should start a new session.

## Retrieval Constraints

All retrieval must pass permission filtering.

Product retrieval must use the product paper library only. Eval retrieval must select an explicit
eval corpus such as `EVAL_LITSEARCH` or `EVAL_QASPER`. Do not add an `includeEval` switch to product
controllers or product chat paths; eval data must be unreachable from those paths by construction.

Routing must be semantic and state-aware. Debug-only hardcoded probes are acceptable during diagnosis,
but production routing must not depend on a fixed list of phrases such as "有什么 agent 相关论文".

Query normalization and planner heuristics are allowed as implementation details, but they must be:

- documented as replaceable strategy logic
- covered by regression cases
- constrained by benchmarks

PageIndex, LOCATE_PAGES, and similar page-location methods must remain eval-gated until they improve
the relevant benchmarks without causing product regressions.

Retrieval budgets should be explicit:

- fast or interactive: lower latency, smaller evidence set
- high recall: larger candidate/window budget
- audit or deep recall: slower, broader evidence inspection for high-stakes review

Conversation scope is part of retrieval correctness:

- Product chat must retrieve from the locked conversation scope.
- If a chat request does not include a reference focus, the backend must derive the effective paper
  universe from the conversation scope.
- The frontend must not be able to override a locked session by sending arbitrary `paperIds`.
- Message history should record the effective scope used for each user message so historical answers
  remain explainable.

## Answer Policy

PaperLoom answers must be evidence-grounded.

Rules:

- paper facts, methods, experiments, results, limitations, and comparisons need citation markers
- every rendered citation marker must map to persisted reference evidence
- citation mappings must be recoverable from MySQL history, not only Redis generation state
- if evidence is insufficient, say so directly
- do not fill gaps with generic knowledge or plausible claims
- recommendations must include paper title, reason, citation, and the limitation that results come
  only from the user's accessible paper library
- comparative claims require evidence for the compared sides

Smalltalk, system help, and UI operation guidance do not need paper citations.

## Frontend Evidence Display

Citation markers are not decorative. They are the user's path back to evidence.

When a user clicks a citation, the UI should display the evidence type accurately:

- text chunk: paper title, page, section, original text, and PDF/page entry when available
- table evidence: table text and table crop when available
- figure or chart evidence: caption, related text, and figure crop when available
- formula evidence: LaTeX, context, and page when available
- missing visual asset: explicit "text evidence only" or equivalent state

Reference follow-up must work from persisted referenceMappings after chat history is reloaded.

Frontend scope display requirements:

- New and unlocked sessions display the editable scope, defaulting to all searchable papers.
- Locked sessions display a read-only scope with a lock indicator and paper count.
- Source-set sessions must expose the snapshot label and paper list or a paged paper list.
- Reference focus is displayed separately from session scope and is cleared after send.
- The paper library page should have separate surfaces for product papers and collections.
- The chat page should not become the collection-management surface; it should select existing
  collections or build source-set snapshots for new sessions.

## Benchmark Policy

Benchmarks are layered. One layer must not be used to claim another layer's capability.

Benchmark data must not share product tables or product Elasticsearch indices. Eval harnesses may
reuse retrieval planning, query expansion, reranking, and metric logic, but they must read from
eval-native tables and eval-only Elasticsearch indices and write run results to eval artifacts or
`eval_runs`, not product `conversations`.

### LitSearch

Use LitSearch to measure paper discovery and literature-search retrieval over an eval corpus.

LitSearch can validate paper-level retrieval over title, abstract, metadata, and structured text. It
does not validate PDF OCR, parser quality, page screenshots, table crops, or figure crops.

Do not report a partial sample as LitSearch Full.

### QASPER

Use QASPER to measure scoped paper QA and evidence selection on structured paper text in an eval
corpus.

QASPER does not validate OCR/parser behavior or real PDF page evidence. It should not be routed
through OCR for normal retrieval evaluation.

### Product RAG Smoke

Use product smoke tests for real product behavior:

- route selection
- scoped retrieval
- answer generation
- citation mapping
- reference follow-up
- UI evidence opening when applicable

Product smoke must pass for every RAG route, planner, retrieval, citation, referenceMappings, or
evidence-panel change.

### Real PDF Parser Smoke

PaperLoom needs a separate real PDF parser benchmark for:

- parse success rate
- page/chunk coverage
- table/figure/formula extraction coverage
- visual asset generation
- citation click-through to page/table/figure evidence

This benchmark is required before claiming mature PDF OCR, page evidence, or visual evidence support.

### Figure And Table QA

Figure-heavy and table-heavy QA require their own benchmark before the product claims multimodal
paper RAG maturity.

## Hard Gates

These gates are product correctness requirements:

- Product smoke pass rate should be 100% for required smoke cases.
- Permission and scope leak rate must be 0.
- Rendered citation mappings must be recoverable and clickable.
- Product tables and product Elasticsearch indices must contain no LitSearch/QASPER benchmark data.
- Structured benchmark results must not be used as proof of OCR/parser/page-evidence quality.
- A retrieval strategy change must not silently regress the current accepted benchmark baselines.

## Current Accepted Benchmark Meaning

As of the 2026-06-27 scan:

- LitSearch Full validates the paper-discovery strategy over 597 queries and 64,183 eval-corpus
  papers, not product-library PDFs.
- QASPER Dev 200 validates structured scoped QA and evidence selection, not PDF parsing.
- Product smoke validates selected end-to-end product scenarios.
- Real PDF parser and figure-heavy QA are not yet sufficiently benchmarked as product claims.
