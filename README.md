# PaperLoom

PaperLoom is an evidence-grounded RAG workbench for reading research paper PDFs.

It helps users upload papers, process them asynchronously with a self-hosted MinerU parser sidecar, persist parser artifacts and structured evidence, retrieve page-aware chunks, ask questions over indexed papers, and reopen the exact evidence references used in previous answers.

## Current Positioning

PaperLoom is not a generic enterprise knowledge-base assistant. The product subject is the research paper:

- PDF paper upload
- asynchronous MinerU parsing and indexing
- parser artifact persistence for audit/debug
- reading-order text and page-aware chunk storage
- page screenshot assets
- table extraction and table-aware retrieval
- figure/chart extraction with screenshot evidence
- formula extraction as source evidence
- agentic multi-query retrieval for paper intents such as experiments, methods, limitations, and summaries
- Elasticsearch hybrid retrieval
- source-grounded chat answers
- persistent reference mappings in MySQL
- paper library with basic editable metadata
- organization/public/private access control

The current system supports a research-paper RAG story:

> PaperLoom supports PDF paper upload, asynchronous MinerU parsing/indexing, parser artifact persistence, table/figure/formula-aware evidence extraction, page-aware chunk retrieval, source-grounded chat, and persistent evidence references.

The implementation persists parser artifacts, extracted paper tables, figures, formulas, page screenshots, and crop screenshots. Text, table, figure/chart caption, and formula evidence are stored as structured metadata in MySQL and enter the existing chunk/embedding/Elasticsearch flow through `sourceKind=TEXT|TABLE|FIGURE|CHART|FORMULA`.

## What It Does Not Claim Yet

The current codebase does not claim automatic research understanding features such as:

- automatic title/author/venue/DOI extraction from arbitrary PDFs
- claim/method/experiment/limitation extraction
- citation graph construction
- coordinate-level PDF highlighting
- fully structured multi-paper comparison
- image-content retrieval and multimodal embedding

Those can be added later, but they are not part of the current MVP contract.

## Architecture

- Backend: Spring Boot
- Frontend: Vue 3 + TypeScript
- Persistence: MySQL
- Cache/session/transient generation state: Redis
- Retrieval: Elasticsearch
- Async processing: Kafka
- Object storage: MinIO
- PDF parsing/chunking: self-hosted MinerU sidecar, mapped into PaperLoom's internal parser model
- Screenshot assets: PDFBox page rendering + MinerU bbox crop

## Core Workflow

1. A user uploads a PDF paper through `/api/v1/papers/upload/*`.
2. PaperLoom stores upload metadata in MySQL and the PDF in MinIO.
3. Kafka triggers asynchronous paper processing.
4. The backend submits the PDF to a self-hosted MinerU parser sidecar and downloads the result artifacts.
5. PaperLoom persists MinerU artifacts such as result zip, `content_list.json`, `middle.json`, and Markdown output in MinIO with MySQL metadata.
6. PaperLoom maps MinerU output into reading-order text, tables, figures/charts, formulas, page evidence, and chunk provenance.
7. PDFBox renders page screenshots and crops table/figure screenshots using MinerU bbox data when available.
8. Text/table/figure/formula chunks are embedded and indexed into the same Elasticsearch `paper_chunks` index.
9. Chat retrieval uses query planning, expanded paper-intent queries, hybrid search, and evidence reranking to construct source-grounded answers.
10. Assistant messages persist their reference mappings in MySQL, so historical citations can be reopened without relying on Redis.

Parser artifacts are not searched. They exist so the parser stage is auditable, debuggable, and credible as a research-system component.

## API Shape

The public PaperLoom API uses paper terminology:

- `paperId`: stable paper identifier, currently the PDF content hash
- `paperTitle`: display title, defaulting to the uploaded filename unless edited
- `originalFilename`: uploaded PDF filename
- `isPublic`: public visibility flag
- `uploadStatus`: upload/merge state
- `processingStatus`: parse/vectorization/indexing state such as `MINERU_RUNNING`, `MAPPING_STRUCTURED_CONTENT`, `RENDERING_VISUAL_ASSETS`, `CHUNKING`, `EMBEDDING`, `INDEXING`, `COMPLETED`, or `FAILED`
- `parserArtifact.available`: whether MinerU parser artifacts were persisted
- `tableAsset.tableCount`: number of extracted paper tables
- `figureAsset.figureCount`: number of extracted figures/charts
- `formulaAsset.formulaCount`: number of extracted formulas
- `visualAsset.pageScreenshotCount`: number of persisted PDF page screenshots
- `visualAsset.tableCropCount`: number of persisted table crop screenshots
- `visualAsset.figureCropCount`: number of persisted figure/chart crop screenshots

Common endpoints:

- `GET /api/v1/papers?scope=accessible`
- `POST /api/v1/papers/upload/chunk`
- `POST /api/v1/papers/upload/merge`
- `GET /api/v1/papers/upload/status?paperId=...`
- `GET /api/v1/papers/{paperId}/preview`
- `GET /api/v1/papers/{paperId}/download`
- `GET /api/v1/papers/{paperId}/parser-artifact`
- `GET /api/v1/papers/{paperId}/tables`
- `GET /api/v1/papers/{paperId}/tables/{tableId}`
- `GET /api/v1/papers/{paperId}/tables/{tableId}/screenshot`
- `GET /api/v1/papers/{paperId}/figures`
- `GET /api/v1/papers/{paperId}/figures/{figureId}`
- `GET /api/v1/papers/{paperId}/figures/{figureId}/screenshot`
- `GET /api/v1/papers/{paperId}/formulas`
- `GET /api/v1/papers/{paperId}/formulas/{formulaId}`
- `GET /api/v1/papers/{paperId}/pages/{pageNumber}/screenshot`
- `DELETE /api/v1/papers/{paperId}`
- `POST /api/v1/papers/{paperId}/reindex`
- `POST /api/v1/papers/{paperId}/vectorization/retry`
- `PATCH /api/v1/papers/{paperId}/metadata`
- `GET /api/v1/papers/search/hybrid?query=...&topK=...`
- `GET /api/v1/papers/reference-detail?conversationRecordId=...&referenceNumber=...`

## Local Development

Backend is usually run from the IDE with hot deployment. After Java changes, prefer compiling instead of restarting:

```bash
mvn -q -DskipTests compile
```

Run targeted backend tests:

```bash
mvn -q -Dtest=MinerUOutputMapperTest,PaperQueryPlannerTest,PaperChunkBuilderTest,ParseServiceStructuredParserTest,PaperVisualAssetServiceTest,EsMappingContractTest,ChatHandlerReferenceEvidenceTest test
```

Frontend typecheck:

```bash
cd frontend && pnpm typecheck
```

The local frontend development target is usually:

```text
http://localhost:9527
```

## Persistence Rule

Redis is used for cache, current session state, and short-lived streaming generation state. Persistent chat history and evidence references must live in MySQL.

For source evidence, the durable path is:

```text
Conversation.referenceMappingsJson -> /api/v1/papers/reference-detail -> paper/page/chunk/table/figure/formula evidence
```

## MinerU Parser Sidecar

PaperLoom self-hosts MinerU as its primary parser backend. The default local configuration is:

```yaml
paper:
  parsing:
    provider: mineru
    mineru:
      base-url: http://localhost:8000
      health-path: /health
      timeout-seconds: 900
      poll-interval-seconds: 3
      file-field-name: files
      backend: pipeline
      parse-method: auto
      return-md: true
      return-content-list: true
      return-middle-json: true
      response-format-zip: true
```

The backend talks to MinerU through `MinerUParserClient`, maps result files through `MinerUOutputMapper`, and persists parser artifacts through `PaperParserArtifactService`.

MinerU availability is treated as part of the parser contract. If the sidecar is not reachable at `paper.parsing.mineru.base-url`, paper processing fails with a clear pipeline error instead of silently falling back to a weaker parser. This keeps the UI honest: `Tables: 0` should mean MinerU ran and extracted no tables, not that the MinerU service was missing.

OpenDataLoader parser code may remain in the repository as a local fallback implementation behind an explicit `paper.parsing.provider=opendataloader` / `PAPER_PARSING_PROVIDER=opendataloader`, but the PaperLoom product story and default parser path are MinerU-based. Use that fallback only when you are intentionally testing text-only parsing.

### Cost And License Note

PaperLoom does not depend on a paid parser SaaS for its main workflow. Self-hosted MinerU does not introduce per-page parser API fees. The practical costs are compute, memory, disk, object storage, and operation.

The hosted MinerU API is not required for PaperLoom. It may be useful for experiments, but rate limits make it a poor default dependency for a reproducible open-source demo.

README attribution should be kept with the upstream project:

- MinerU GitHub: https://github.com/opendatalab/MinerU
- MinerU docs: https://opendatalab.github.io/MinerU/
- MinerU output files: https://opendatalab.github.io/MinerU/reference/output_files/
- MinerU API limit page: https://mineru.net/apiManage/limit

MinerU's upstream license should be reviewed before redistribution or commercial use. The implementation is designed as a sidecar integration so PaperLoom can document attribution clearly and keep parser replacement possible.

## Parser Artifacts, Evidence, And Screenshots

During `parseAndSave()` the backend persists:

- MinerU parser artifacts in MinIO under `paper-parser-artifacts/{paperId}/mineru/*`
- parser artifact metadata in MySQL table `paper_parser_artifacts`
- extracted table metadata/text/Markdown in MySQL table `paper_tables`
- extracted figure/chart metadata and captions in MySQL table `paper_figures`
- extracted formulas and surrounding context in MySQL table `paper_formulas`
- text/table/figure/formula evidence as searchable chunks in `paper_text_chunks`
- page screenshots, table crop screenshots, and figure crop screenshots in MinIO
- screenshot metadata in MySQL table `paper_visual_assets`

`estimateEmbeddingUsage()` intentionally has no parser-artifact/table/figure/formula/screenshot side effects. Reindex clears old parser artifacts, tables, figures, formulas, visual assets, chunks, and ES docs before rebuilding. Deleting the final paper record removes the PDF, parser artifacts, page screenshots, table/figure crops, and related metadata.

Table, figure, chart, and formula retrieval deliberately reuse the existing hybrid search pipeline. There is no second table or figure index in the current version.

## Agentic Paper Retrieval

PaperLoom does not treat raw user text as the only retrieval query. `PaperQueryPlanner` detects coarse paper-reading intents such as experiment results, methods, limitations, summaries, and general questions.

For questions such as `有实验数据吗` or `我说论文实验数据`, the planner expands the query into English paper terms such as `experiment results`, `evaluation results`, `accuracy`, `benchmark`, `dataset`, `table`, and `chart`. `PaperRetrievalService` runs multi-query hybrid retrieval, deduplicates evidence, and reranks table/chart/figure evidence above generic text for experiment-result intent.

The chat layer must not claim `当前论文库中没有相关内容` after a single raw-query miss. No-hit responses should only say that PaperLoom could not find enough reliable paper evidence after expanded retrieval attempts.
