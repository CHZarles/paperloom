# PaperLoom Implementation Alignment

Status: initial scan recorded on 2026-06-27; eval data isolation drift added on 2026-06-28.

This document records the gap between the current implementation and the user-confirmed PaperLoom
product direction in `docs/PAPERLOOM_PRODUCT_REQUIREMENTS.md`.

## Scan Scope

Primary implementation scan:

- worktree: `.worktrees/adaptive-source-set-rag`
- branch: `feature/adaptive-source-set-rag`
- backend runtime observed on port `8081`
- frontend runtime observed on port `9527`
- infrastructure ports observed for MySQL, Elasticsearch, Kafka, MinIO, and Redis

The main worktree also had unrelated frontend/theme changes at scan time. Do not treat those as part
of the RAG implementation unless they are explicitly pulled into a product task.

## Current Implementation Summary

### Ingestion

Paper upload is PDF-only in the main product flow.

Relevant areas:

- `PaperUploadController`
- `FileTypeValidationService`
- `UploadService`
- `PaperProcessingConsumer`

The upload flow uses chunked upload, merge, MinIO storage, and Kafka-based asynchronous processing.

### Parser And OCR

The parser pipeline is centered on MinerU.

Relevant areas:

- `ParseService`
- `MinerUPaperPdfParser`
- `MinerUParserClient`
- `MinerUOutputMapper`
- `OpenDataLoaderPaperPdfParser`
- `PaperChunkBuilder`
- `PaperVisualAssetService`

Current behavior:

- MinerU is the intended default parser.
- OpenDataLoader exists as a fallback parser.
- MinerU unavailability is surfaced as an explicit failure with guidance to configure fallback.
- Parsed data can include text elements, tables, figures, formulas, chunks, parser artifacts, and
  visual assets.
- Chunking is text/table/figure/formula aware, but the retrieval and benchmark evidence is currently
  stronger for text and tables than for figure-heavy questions.

### Storage

The implementation still has legacy physical naming in places, but the product contract is
paper-centered and PDF-only.

Relevant areas:

- `Paper`
- `PaperTextChunk`
- `PaperParserArtifact`
- `PaperTable`
- `PaperFigure`
- `PaperFormula`
- `PaperVisualAsset`
- `Conversation`

Important product requirement:

- `Conversation.referenceMappingsJson` is the durable reference-evidence path for chat history.
- Redis generation state is short-lived and must not be the only source of historical citation
  recovery.
- Product storage must contain only product PDF papers. Benchmark corpora must use an eval-native
  schema such as `paperloom_eval`, not `paismart.file_upload` or product chunk tables.

### Indexing

There are two Elasticsearch retrieval surfaces:

- `paper_chunks`
- `paper_search`

Relevant areas:

- `PaperSearchIndex`
- `EsIndexInitializer`
- `ElasticsearchService`
- `VectorizationService`
- `PaperChunkDocument`
- `PaperSearchDocument`

Current direction:

- `paper_chunks` supports chunk-level evidence retrieval.
- `paper_search` supports paper-level discovery over title, abstract, authors, venue/year, external
  ids, filename, and combined search text.

Alignment requirement:

- Product `paper_chunks` and `paper_search` must contain only product PDF-derived documents.
- Eval corpora require separate indices such as `eval_litsearch_paper_search`,
  `eval_litsearch_chunks`, `eval_qasper_paper_search`, and `eval_qasper_chunks`.

### Retrieval

Relevant areas:

- `HybridSearchService`
- `PaperRetrievalService`
- `PaperQueryPlanner`

Current behavior:

- normal QA uses hybrid chunk retrieval
- literature discovery uses `paper_search` candidates first, then scoped representative evidence
- candidate/evidence fusion includes permission filtering
- multi-paper scope uses a terms-style filter path for large scopes
- metadata fallback preserves paper candidates when evidence chunks are sparse

This aligns with the confirmed product strategy.

### Routing And Planning

Relevant areas:

- `PaperChatRouter`
- `EvidencePlanner`
- `EvidenceToolExecutor`
- `PlannerActionType`
- `PaperAnswerService`

Current behavior:

- `PaperChatRouter` defaults ambiguous paper queries to `AUTO_SOURCE_QA`
- `EvidencePlanner` chooses actions such as `DISCOVER_PAPERS`, `SEARCH_EVIDENCE`,
  `INSPECT_REFERENCE`, and `INSPECT_PAGE`
- `INSPECT_PAGE` is intended for trusted paper/page anchors
- `DISCOVER_PAPERS` forces the paper-discovery retrieval path

Alignment note:

- The recent direction avoids production routing based on hardcoded phrases.
- `PaperQueryPlanner` and parts of `EvidencePlanner` still contain query cleanup and heuristic
  intent logic. This is acceptable only as replaceable planner strategy covered by regression tests,
  not as the product's core routing contract.

### Answering

Relevant areas:

- `PaperAnswerService`
- `EvidenceAnswerGenerator`
- `EvidenceVerifier`
- `ChatHandler`
- `ConversationService`

Current behavior:

- answers are generated from an evidence ledger
- citation tokens are rendered into `[n]`
- reference mappings are persisted with conversations
- the verifier rejects several unsupported citation and claim patterns
- recommendation answers group papers and cite evidence

This mostly aligns with the answer policy, but naming and some prompt language still need cleanup.

### Frontend

Relevant areas:

- `frontend/src/views/chat/index.vue`
- `frontend/src/views/chat/modules/input-box.vue`
- `frontend/src/views/chat/modules/chat-message.vue`
- `frontend/src/views/chat/modules/source-evidence-panel.vue`
- `frontend/src/views/chat/modules/reference-evidence-page.vue`
- `frontend/src/views/knowledge-base/index.vue`
- `frontend/src/views/knowledge-base/modules/upload-dialog.vue`
- `frontend/src/views/knowledge-base/modules/search-dialog.vue`
- `frontend/src/components/custom/file-preview.vue`
- `frontend/src/components/custom/pdf-document-viewer.vue`

Current behavior:

- chat can send source scope and retrieval budget
- citation chips can open evidence details
- evidence display supports text, page/PDF preview, table, figure, and source follow-up paths
- the knowledge base page shows upload, processing state, preview, parser artifact, and retry/delete
  controls

Alignment note:

- The frontend has most of the required PDF evidence-display surface.
- Product frontend must not contain eval/structured import branches; eval visualization belongs in a
  separate eval/debug surface if needed.
- Product language still contains legacy names and generic knowledge-base wording.

## Benchmark State

Relevant docs:

- `eval/rag/PROJECT_CONTEXT.md`
- `eval/rag/RETRIEVAL_STRATEGY_DECISION.md`
- `eval/rag/STRATEGY_READINESS.md`
- `eval/rag/RAG_METHOD_EXPLORATION.md`
- `eval/rag/litsearch/README.md`
- `eval/rag/page-location/README.md`

Accepted interpretation:

- Product smoke validates selected product end-to-end RAG behavior.
- QASPER validates structured scoped QA and evidence selection.
- LitSearch validates large-scale paper discovery.
- None of QASPER or LitSearch validates real PDF parser/OCR/page visual evidence.
- LitSearch and QASPER must run from isolated eval storage and eval indices, not product tables or
  product Elasticsearch indices.

Current benchmark facts from the scan:

- Product Paper-QA expanded slice: 10/10 for K3/K5/K7.
- QASPER Dev 200: K3 91/200, K5 106/200, K7 122/200, with scope leak 0.
- LitSearch Full service-backed: 597 queries, 64,183 papers, Recall@5 54.1%, Recall@20 64.5%,
  MRR 45.0%, scope leak 0.
- Offline LitSearch facet-paper baseline: Recall@20 67.4%, MRR 40.1%.
- SAG fast-mode standalone retrieval was a negative result and should not replace the current
  retrieval strategy.

## Alignment Register

### Aligned

- Product direction is already mostly paper-centered in backend domain concepts.
- PDF upload is the production ingestion path.
- MinerU is the intended default parser.
- The `paper_search` plus scoped evidence strategy matches the confirmed paper-discovery path.
- Chunk hybrid retrieval matches the confirmed paper-reading path.
- Reference mappings are persisted in MySQL conversation history.
- `INSPECT_PAGE` is scoped to trusted anchors rather than arbitrary page guessing.
- Benchmarks already separate LitSearch, QASPER, and product smoke.

### Partial Or Risky

- Query cleanup and intent heuristics still exist in planner code. They need regression coverage and
  must remain replaceable strategy logic.
- Figure/chart evidence is represented in data structures and UI, but figure-heavy QA has not been
  proven by benchmark.
- Metadata extraction is limited. Title inference exists, but authors, venue, year, DOI, and arXiv id
  are not yet a robust PDF-extraction guarantee.
- Current code still contains eval import compatibility fields and frontend branches. These are now
  product drift and should move to eval-only DTOs or be removed from product paths.
- PageIndex/page-location ideas exist in eval or test-scoped form. They are not production-default
  capability until benchmarks justify promotion.

### Product Drift

- Frontend and prompts still contain the legacy product name `CiteWeave`.
- Some UI copy still says generic "knowledge base", including search-dialog wording.
- Legacy physical names such as file-oriented table/entity concepts still exist. This may be
  acceptable internally, but public API and UI should stay paper-centered.
- Current benchmark documentation is strong for retrieval, but the product still needs a separate
  real PDF parser smoke benchmark.
- Runtime data is currently polluted: LitSearch/QASPER benchmark rows exist in product `file_upload`,
  product chunk tables, and product Elasticsearch indices. This must be corrected by migrating those
  rows to `paperloom_eval` and removing eval-only columns (`is_eval`, `source_dataset`,
  `external_corpus_id`, `eval_split`) from product tables.

## Development Guidance From This Alignment

When implementing future changes:

- start from the product requirements document
- identify which retrieval path the task affects
- preserve permission filtering and scope isolation
- preserve citation-to-referenceMappings persistence
- do not use fixed production phrases as routing logic
- do not claim OCR/page/visual capability from QASPER or LitSearch results
- do not store benchmark corpora in product tables or product Elasticsearch indices
- do not add `includeEval` switches to product controllers or chat paths
- validate UI evidence behavior in the browser when frontend evidence display is touched
- record any product drift instead of silently normalizing it away

High-priority cleanup items:

- replace visible `CiteWeave` product copy with PaperLoom where appropriate
- replace generic knowledge-base wording with paper-library or paper-search wording
- add or formalize a real PDF parser smoke benchmark
- migrate benchmark data to `paperloom_eval` and eval-only Elasticsearch indices
- remove product API/frontend support for eval/structured import states
- keep PageIndex or LOCATE_PAGES behind eval gates until scorecards justify production use
