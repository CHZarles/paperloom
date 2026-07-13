# PaperLoom Implementation Alignment

Status: initial scan recorded on 2026-06-27; eval data isolation completed on 2026-06-28;
conversation scope and collection target recorded on 2026-06-28.

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

Unlimited-OCR research note:

- Unlimited-OCR is not integrated in the current codebase.
- Official materials describe it as a Baidu 3B image-text OCR model for long-horizon document
  parsing and markdown generation. The vLLM recipe requires a dedicated image, a custom logits
  processor, an `<image>` prompt prefix, `skip_special_tokens=false`, and per-request n-gram
  settings. The recipe states that BF16 inference can run on a single GPU with at least 8 GB VRAM.
- The repository examples convert PDFs to page images with PyMuPDF and produce markdown/text
  output. Batch PDF inference processes converted pages and writes page markdown files.
- The output shape does not currently match PaperLoom's `ParsedPaper` contract. A production
  adapter would need to preserve page numbers, reading order, bbox/grounding tokens, table and
  formula structure, figure evidence, artifacts, and failure diagnostics.
- Recommendation: keep MinerU as the production default, keep OpenDataLoader as the local fallback,
  and evaluate Unlimited-OCR only behind a provider flag and real PDF parser benchmark. Promote it
  only if product-level evidence completeness improves, not merely because raw OCR text looks better.

Provider decision supplement recorded on 2026-06-29:

- Current local code has two parser beans: `MinerUPaperPdfParser` and
  `OpenDataLoaderPaperPdfParser`.
- `MinerUPaperPdfParser` is selected by default through `paper.parsing.provider=mineru` and maps
  MinerU `content_list`, `middle_json`, markdown, and raw result artifacts into PaperLoom's
  structured parser model.
- `OpenDataLoaderPaperPdfParser` is selected only when
  `paper.parsing.provider=opendataloader`. It runs the Java OpenDataLoader library inside a
  dedicated worker JVM through `OpenDataLoaderProcessRunner`, with
  `paper.parsing.opendataloader.timeout-seconds` defaulting to 300. If the worker exceeds the
  timeout, the process is terminated and the parser raises a visible `PaperParsingException`
  instead of holding a Kafka worker indefinitely.
- Unlimited-OCR should be modeled as a third provider only after a sidecar/adapter is written. The
  adapter must convert PDFs to page images, call the model with the required prompt/decode recipe,
  parse markdown and grounding tokens, and map results into `ParsedPaper`.
- The provider choice is therefore:
  - default target: MinerU
  - local fallback with process timeout boundary: OpenDataLoader
  - OCR experiment: Unlimited-OCR
- A provider cannot become default until it passes a real PDF parser benchmark that checks not just
  extracted text, but page numbers, reading order, bbox coverage, table/figure/formula evidence,
  parser artifacts, latency, resource use, and visible failure behavior.

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

- A new persistent conversation reference registry is the durable reference-evidence source of
  truth for chat history.
- `Conversation.referenceMappingsJson` may remain as a render snapshot, but it must not be the only
  source of truth for citations, pages, or evidence follow-up.
- Redis generation state is short-lived and must not be the only source of historical citation
  recovery.
- Product storage contains only product PDF papers after eval cleanup. Benchmark corpora use the
  eval-native `paperloom_eval` schema, not `paperloom.file_upload` or product chunk tables.

Confirmed new storage target:

- Conversations need durable scope fields: scope mode, lock state, scope status, source label,
  source recipe, and resolved source-set snapshot paper ids.
- Message history needs the effective scope used by each user message.
- Collections need product tables for collection metadata and static collection paper membership.
- Collections are management objects; locked sessions must not retrieve from live collection
  membership.
- Conversation memory needs durable structured JSON storage. Redis may cache it but cannot be the
  source of truth.
- Product ReAct trace is disk-only JSON in phase one and does not add trace pointers to business
  tables.

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

Runtime status after cleanup:

- Product `paper_chunks` and `paper_search` have zero LitSearch/QASPER-prefixed documents.
- LitSearch and QASPER records remain available through eval storage and eval-only indices.

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
- `TaskRouter`
- `LlmTaskRouter`
- `PaperAnswerService`
- `PaperConversationAgent`
- `PaperConversationToolRegistry`

Current behavior:

- `PaperChatRouter` defaults ambiguous paper queries to `AUTO_SOURCE_QA`
- `TaskRouter` uses an LLM JSON routing step to turn flexible natural-language `AUTO_SOURCE_QA`
  requests into typed task decisions such as `LIBRARY_STATUS`, `PAPER_DISCOVERY`, `PAPER_QA`,
  `REFERENCE_QA`, `FOLLOW_UP`, `CLARIFY`, or `SMALLTALK`
- `EvidencePlanner` chooses actions such as `DISCOVER_PAPERS`, `SEARCH_EVIDENCE`,
  `INSPECT_REFERENCE`, and `INSPECT_PAGE`
- `INSPECT_PAGE` is intended for trusted paper/page anchors
- `DISCOVER_PAPERS` forces the paper-discovery retrieval path
- A transitional `PaperConversationAgent` and `PaperConversationToolRegistry` exist, but they are
  not the final Product ReAct Harness contract. They still use old function names such as
  `get_library_status` and `discover_papers`, and their refs are not yet the persistent reference
  registry model.

Alignment note:

- The recent direction forbids production semantic decisions based on hardcoded phrases, regex
  alternations, or token lists.
- Product chat semantics should move into `ProductReActHarness`. The LLM receives full relevant
  history and the complete read-only product function catalog every turn.
- `PaperChatRouter`, `TaskRouter`, `LlmTaskRouter`, and `EvidencePlanner` must not remain the
  top-level semantic owners of product chat.
- `PaperQueryPlanner` may remain as a retrieval implementation detail behind product tools, but it
  must not infer user-facing task semantics from phrase lists.
- Parser evidence roles should come from parser-provided structure such as table, figure, chart, or
  formula elements, not from text-content words such as "experiment" or "accuracy".
- Chat routing must derive the paper universe from locked conversation scope and persistent opaque
  refs, not from mutable frontend `input.scope`, Redis `FocusState`, or LLM-maintained paper id
  sets.

Confirmed replacement target:

```text
ChatHandler / WebSocket entry
  -> ProductConversationService
  -> ProductReActHarness
  -> ProductToolRegistry
  -> ProductMemoryService
  -> ConversationReferenceRegistry
  -> ProductTraceRecorder
```

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

Alignment note:

- The final target is a fixed product answer envelope with `EVIDENCE_ANSWER`, `PRODUCT_STATE`,
  `INSUFFICIENT_EVIDENCE`, `NON_EVIDENCE`, and `CLARIFICATION_NEEDED`.
- The LLM must not directly own final Markdown structure or final citation numbers.
- The harness must validate evidence markers, generate final citation numbers, persist reference
  registry records, and persist `referenceMappingsJson` as a render snapshot.
- `PRODUCT_STATE` answers such as searchable paper counts must come from product-state tools and
  must not show citations or references.
- `INSUFFICIENT_EVIDENCE` means retrieval/inspection succeeded but found no adequate evidence.
  Tool failure is a failed turn, not insufficient evidence.

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
- the chat input source picker currently requests the first page of accessible papers and filters
  them in the browser
- source scope is currently attached to the outgoing input and cleared after send
- answer source actions currently set the next input scope inside the same session

Alignment note:

- The frontend has most of the required PDF evidence-display surface.
- Product frontend no longer contains eval/structured import branches; eval visualization belongs in
  a separate eval/debug surface if needed.
- Product language still contains legacy names and generic knowledge-base wording.
- The current chat source picker is not acceptable for large paper libraries because it only loads a
  fixed page and does not search paper-level candidates server-side.
- The current mutable input scope conflicts with the confirmed rule that a session has one locked
  evidence universe. Source changes must create a new session after the first accepted message.
- The current paper-library page has no collection management surface.
- The chat UI must add a new-session scope entry for all papers, collection, and title-match
  snapshot. Title match must support plain query and explicit regex mode with backend preview.
- Product ReAct progress display should be minimal: `calling <toolName>`. Do not display tool
  arguments, tool results, hidden reasoning, prompts, SQL, Elasticsearch details, or provider
  internals.
- The frontend should remove free-text `Sources used` output. It should render only the structured
  references panel from final answer payload/reference registry data, and display no sources area
  when references are empty.

### Conversation Scope And Collections

Confirmed product target:

- A conversation starts unlocked with default `AUTO_LIBRARY` scope.
- The user may choose all searchable papers, an existing collection, or a custom source-set snapshot
  before the first accepted message.
- Custom source-set snapshot can be created from title match. Title match supports plain title
  query and explicit regex mode, with backend preview before confirmation.
- The backend locks the conversation scope when it accepts the first user message.
- Locked session scope is immutable. Changing sources requires a new session.
- Newly uploaded papers do not enter an already locked session. To retrieve a new paper, the user
  must start a new session after the paper becomes searchable.
- Reference focus is temporary and separate from session scope.
- Collections are static paper-id sets with `PRIVATE` or `ORG` visibility.
- Regex and metadata filters are batch-add tools for collections or custom snapshots, not dynamic
  live session scopes.
- Session retrieval uses resolved snapshot paper ids. Source recipe metadata is explanatory only.
- Chat source selection must search paper-level metadata server-side and show only searchable
  papers.

Current implementation gap:

- There is no collection model or API.
- There is no durable conversation scope model, lock state, or snapshot storage.
- There is no message-level effective-scope audit field.
- WebSocket chat still accepts scope payloads that can vary per message.
- Conversation session DTOs do not expose scope summary, paper count, lock state, or degraded scope
  status.
- The source picker is front-end filtered over a limited page of papers.
- Existing runtime chat data was created before this model and does not need compatibility in the
  development environment.

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
- LitSearch/QASPER data is isolated in the `paperloom_eval` data domain and eval-only ES indices.
- Product tables, product ES indices, product paper APIs, and product frontend branches no longer
  expose eval/structured import state.
- The `paper_search` plus scoped evidence strategy matches the confirmed paper-discovery path.
- Chunk hybrid retrieval matches the confirmed paper-reading path.
- Product ReAct now writes an independent `paper_conversation_reference` registry and also keeps
  `Conversation.referenceMappingsJson` as a render snapshot for frontend/history recovery.
- ProductConversationService now owns product turn MySQL persistence, including assistant answer,
  render snapshot reference mappings, and message effective-scope audit. ChatHandler keeps
  WebSocket streaming, generation state, and Redis short-term history update responsibilities.
- `INSPECT_PAGE` is scoped to trusted anchors rather than arbitrary page guessing.
- Benchmarks already separate LitSearch, QASPER, and product smoke.

### Partial Or Risky

- Product chat default WebSocket path now enters `ProductConversationService` and
  `ProductReActHarness`; `PaperAnswerService`, `TaskRouter`/`LlmTaskRouter`, and `EvidencePlanner`
  remain in the codebase but are no longer the default product chat semantic path.
- Product ReAct exposes the confirmed 10-tool catalog and fixed answer envelope.
  `answer_without_product_state`, `get_system_state`, `get_session_scope`, `list_papers`,
  `find_papers`, `resolve_papers`, `get_paper_metadata`, `retrieve_evidence`,
  `inspect_reference`, and `inspect_page` now execute through the product boundary without
  delegating to `PaperConversationToolRegistry`. ProductToolRegistry sanitizes public tool data,
  rejects raw ids/retrieval knobs at the product contract boundary, resolves paperRef constraints
  through the persistent reference registry, and calls product paper/evidence primitives directly.
- `find_papers` is now the product semantic paper-discovery/recommendation tool. `list_papers`
  remains a browsing/enumeration/explicit metadata-filter tool and must not be treated as semantic
  topic search.
- Current planner code no longer uses production phrase lists for route recognition or query
  expansion, but this remains a regression risk. Future changes must not reintroduce hardcoded
  semantic matching in routing, planning, verifier, query expansion, or parser evidence-role logic.
- Conversation memory is now persisted on `ConversationSession.conversation_memory_json` and updated
  through a separate LLM memory-compression call after successful Product ReAct turns.
- ProductConversationService now applies a model-context history budget before calling
  ProductReActHarness: if full verbatim history exceeds the configured budget, the persisted
  structured memory remains available separately and only the recent verbatim tail is passed as
  turn history.
- Product ReAct trace is written as disk JSON under `data/traces/product-react/`; memory compression
  calls are appended to the same turn trace when available.
- Frontend chat protocol now handles `calling_tool` events and displays only `calling <toolName>`.
- Figure/chart evidence is represented in data structures and UI, but figure-heavy QA has not been
  proven by benchmark.
- Metadata extraction is limited. Title inference exists, but authors, venue, year, DOI, and arXiv id
  are not yet a robust PDF-extraction guarantee.
- PageIndex/page-location ideas exist in eval or test-scoped form. They are not production-default
  capability until benchmarks justify promotion.
- Conversation scope is not yet a durable backend-owned session invariant.
- Chat source selection is not yet suitable for large paper libraries.
- Collections are not implemented.

### Product Drift

- Frontend and prompts still contain the legacy product name `CiteWeave`.
- Some UI copy still says generic "knowledge base", including search-dialog wording.
- Legacy physical names such as file-oriented table/entity concepts still exist. This may be
  acceptable internally, but public API and UI should stay paper-centered.
- Chat output no longer renders the free-text `Sources used` block; citations continue through
  structured reference mappings and evidence panels.
- Product follow-up no longer treats frontend raw `paperId` focus as a scope or reference override;
  persisted reference numbers resolve through stored reference mappings/registry context.
- Business persistence must not grow trace pointer fields for phase-one Product ReAct trace; trace
  stays disk-only JSON.
- Runtime data pollution has been cleaned: LitSearch/QASPER rows were migrated to `paperloom_eval`,
  product DB/ES records were removed, and eval-only product columns (`is_eval`, `source_dataset`,
  `external_corpus_id`, `eval_split`) were dropped.
- Product runtime data should be reset for the new collection/scope model while preserving admin
  access and benchmark/eval corpora.

## Development Guidance From This Alignment

When implementing future changes:

- start from the product requirements document
- use `docs/superpowers/specs/2026-07-02-product-react-tool-catalog-spec.md` as the
  current LLM-facing Product ReAct tool-catalog contract for chat
- identify which retrieval path the task affects
- preserve permission filtering and scope isolation
- preserve reference registry and citation render snapshot persistence
- do not use fixed production phrases as routing logic
- do not claim OCR/page/visual capability from QASPER or LitSearch results
- do not store benchmark corpora in product tables or product Elasticsearch indices
- do not add `includeEval` switches to product controllers or chat paths
- validate UI evidence behavior in the browser when frontend evidence display is touched
- record any product drift instead of silently normalizing it away
- keep conversation scope backend-owned and immutable after the first accepted message
- use source-set snapshots as retrieval truth, not live collection membership or regex rules
- use paper-level server-side candidate search for chat source selection
- preserve benchmark corpora while resetting product runtime data during local development
- do not expose OCR provider, ES, Kafka, or parser internals through product chat tools
- keep Product ReAct trace disk-only until a later cleaning/analysis phase is designed

High-priority cleanup items:

- continue hardening `ProductReActHarness`, `ProductConversationService`, `ProductToolRegistry`,
  `ProductMemoryService`, `ConversationReferenceRegistry`, and `ProductTraceRecorder`
- remove `PaperAnswerService`, `LlmTaskRouter`, `EvidencePlanner`, Redis `FocusState`, and
  transitional `PaperConversationAgent` from the product chat semantic path unless a remaining
  class has a clear narrow responsibility
- replace remaining transitional ProductToolRegistry delegation where direct product tool
  implementations are clearer
- add new-session scope selection for all papers, collection, and title-match snapshot
- verify structured reference rendering and reference follow-up in a live browser
- replace visible `CiteWeave` product copy with PaperLoom where appropriate
- replace generic knowledge-base wording with paper-library or paper-search wording
- keep PageIndex or LOCATE_PAGES behind eval gates until scorecards justify production use
- add locked-scope display to chat
