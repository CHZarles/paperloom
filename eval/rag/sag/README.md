# SAG Candidate Notes

SAG source:

- Repository: `https://github.com/Zleap-AI/SAG`
- Benchmark repository: `https://github.com/Zleap-AI/SAG-Benchmark`
- Paper: `https://arxiv.org/abs/2606.15971`

## PaperLoom Fit

SAG is a candidate retrieval strategy for PaperLoom's research-paper RAG path. The relevant idea is
structured expansion: extract entities/events from chunks, store them in relational form, and expand
retrieval through SQL joins before evidence selection.

The official SAG README describes the lightweight structure as:

```text
chunk -> event
chunk -> entities
event <-> entities
```

Each chunk contributes one semantic event and multiple entities. The event keeps the evidence unit
intact; entities make the event reachable through relational expansion. SAG also distinguishes a fast
mode, which matches query text against entities before multi-hop expansion and reranking, from a
standard mode, which uses an LLM to extract query entities before multi-route recall.

Do not treat SAG as a drop-in replacement for PaperLoom's current stack. PaperLoom already has:

- MySQL for durable paper/chunk metadata
- Elasticsearch for hybrid retrieval
- page/chunk provenance needed by citations
- an evidence ledger and verifier

The useful first experiment is an eval-scoped SAG-style harness:

```text
initial ES/page hit -> chunk events/entities -> SQL expansion -> page/window evidence -> answer ledger
```

SAG itself is TypeScript with PostgreSQL, pgvector, full-text search, Fastify APIs, and MCP support.
PaperLoom is Java/Spring with MySQL, Elasticsearch, Kafka, MinIO, and Redis. Therefore the first
experiment should port the retrieval shape, not the full workbench.

## Candidate Schema

Use eval-scoped tables or JSONL prototypes first:

| Structure | Key fields | Purpose |
|---|---|---|
| `paper_entity` | `paperId`, `entityId`, `name`, `type`, `aliases` | paper-local entities such as datasets, methods, metrics, baselines |
| `paper_event` | `paperId`, `eventId`, `type`, `trigger`, `summary` | claims, comparisons, evaluations, limitations |
| `paper_event_entity` | `eventId`, `entityId`, `role` | method/dataset/metric/result links |
| `paper_chunk_event` | `chunkId`, `eventId`, `pageNumber`, `sectionTitle` | provenance back to citable chunks/pages |

Keep these rows marked by `sourceDataset` / `evalSplit` for benchmark imports.

## Evaluation Plan

Start offline:

1. Extract one event and multiple entities from each QASPER Dev 200 paragraph chunk.
2. Prototype SAG fast mode: query terms -> entity match -> event expansion -> chunk/page windows.
3. Optionally prototype SAG standard mode: LLM query entities -> event/entity expansion.
4. Run the same page-window scorer as `page-index-scientific-qa`.
5. Compare against current QASPER Dev 200 floors:
   - raw `WindowEv@3=43.6%`
   - scientific-QA page planner `WindowEv@3=54.5%`
6. On LitSearch sample corpus, use entity/facet expansion for paper-level retrieval and compare
   Recall@20 against keyword-only sample floors.

Current offline prototype:

| Harness | Dataset | Cases | ChunkEv@1 | ChunkEv@3 | Notes |
|---|---|---:|---:|---:|---|
| `sag-style-fast-mode` | `qasper-dev-200-page-window` | 200 | 0.5% | 5.0% | Naive entity/event expansion over paragraph chunks. |

Run artifact:

```text
eval/rag/sag/generated/runs/2026-06-24T140000Z-sag-style-fast-mode-qasper-dev-200/
```

Interpretation: the naive fast-mode prototype is not competitive as a standalone retriever. It
extracts entities directly from chunk text with no LLM normalization, no typed scientific facets,
and no ES/page first-stage candidate set. It over-relies on lexical entity overlap and misses most
QASPER evidence. This is a useful negative result: SAG-style expansion should stay behind an
eval gate and should be tested as a constrained expansion after hybrid/page hits, not as the primary
paper QA retriever.

Then service-backed:

1. Import benchmark papers into eval-scoped MySQL/Elasticsearch.
2. Add a disabled-by-default SAG-style expansion stage after first-stage retrieval.
3. Feed expanded evidence to the existing evidence ledger.
4. Gate promotion on Product Rescue Smoke, QASPER Dev, and LitSearch sample/full.

## Risks

- Entity/event extraction can introduce noisy evidence and false positives.
- SQL expansion can over-expand context unless constrained by paper scope, page windows, and evidence
  budget.
- Extraction cost may be too high for interactive ingestion unless it is async and cached.

## Decision Rule

Keep SAG-style expansion only if it improves QASPER evidence hit or LitSearch Recall@20 without
breaking Product Rescue Smoke citation quality or increasing context beyond the interactive budget.
