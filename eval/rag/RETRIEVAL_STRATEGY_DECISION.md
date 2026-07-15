# PaperLoom Retrieval Strategy Decision

> 历史归档：本文描述的生产检索实现已经被 Java Corpus API、Qdrant Candidate Index 和 MySQL Exact
> Read 取代。当前 Harness 测试流程见 [`research/golden-data/README.md`](../../research/golden-data/README.md)。

Date: 2026-06-27

## Decision

PaperLoom should use a routed retrieval strategy, not one universal retriever.
The product/user context that motivates this routing is tracked in
[`PROJECT_CONTEXT.md`](PROJECT_CONTEXT.md).
The completion audit for this strategy is tracked in
[`STRATEGY_READINESS.md`](STRATEGY_READINESS.md).

| Route | Strategy | Promotion state |
|---|---|---|
| Literature search | Use paper-level metadata retrieval first over title, abstract, authors, venue/year, external ids, and original filename, then select one representative evidence snippet per candidate paper. | Implemented as `paper_search` first-stage retrieval for `LITERATURE_SEARCH`, followed by scoped chunk evidence selection, metadata fallback for candidates without chunk evidence, paper-level rerank labels, and one-hit-per-paper collapse. Falls back to the chunk-index bridge when paper metadata candidate search is empty. |
| Scoped paper QA | Evidence-first retrieval with hybrid chunk retrieval plus page-window inspection. | Hybrid chunk retrieval is production path. `INSPECT_PAGE` exists for trusted page anchors. The best service-backed scoped-paper eval variants now use non-overlapping diverse windows: K3 for interactive budget, K5 for high-recall budget, and K7 for deep-recall budget/audit runs. This policy is verified on the expanded Product Paper-QA slice and QASPER Dev 200. `LOCATE_PAGES` should stay eval-gated. Target-term and chunk-aware page reranks remain negative explicit variants. |
| Known page/reference follow-up | Use deterministic inspection after a trusted `paperId`, reference number, or page number is known. | Safe boundary for `INSPECT_REFERENCE` and `INSPECT_PAGE`; do not use locator-style tools until eval scorecards justify them. |
| SAG-style structured expansion | Use SAG as post-retrieval expansion only, after first-stage hybrid/page hits establish a bounded candidate set. | Standalone fast-mode is rejected for now because QASPER Dev 200 scored only `ChunkEv@3=5.0%`. |

## Benchmark Evidence

| Signal | Result | Interpretation |
|---|---|---|
| QASPER Dev 200 paragraph-window | scientific-QA page-window planner reached `WindowEv@3=54.5%` versus raw `43.6%`. | Page-window inspection is promising for scoped research-paper QA. |
| Product Paper-QA service-backed slice | `service-backed-page-window` passed 3/3 with `WindowEv@1=100.0%`, `WindowEv@3=100.0%`, and `scopeLeakRate=0.0%`. | The production hybrid -> page-window -> evidence-ledger bridge is runnable on scoped product paper-QA. |
| QASPER Dev 200 service-backed page-window | `service-backed-page-window` passed 84/200 with `WindowEv@1=22.5%`, `WindowEv@3=42.0%`, and `scopeLeakRate=0.0%`. | The live service path is scoped and runnable, but first-stage production retrieval under embedding rate-limit fallback trails the offline paragraph-window result. |
| QASPER Dev 200 service-backed scoped-paper page-window | `service-backed-scoped-page-window` passed 85/200 with `WindowEv@1=22.5%`, `WindowEv@3=42.5%`, `CandidateEv=78.0%`, and `scopeLeakRate=0.0%`. | Reading all chunks from the trusted scoped paper shows 156/200 cases have gold evidence in the candidate set, but 71 of those are missed by the selected windows. The bottleneck is locator/query/window evidence selection rather than paper scoping. |
| QASPER scoped-paper diverse-window selector | `service-backed-scoped-diverse-window` passed 91/200 with `WindowEv@1=22.5%`, `WindowEv@3=45.5%`, `CandidateEv=78.0%`, and `scopeLeakRate=0.0%`. | Avoiding overlapping inspected windows is the first positive service-backed selector probe. It gained 11 cases, lost 5, and reduced candidate-hit/window-miss cases from 71 to 65. |
| QASPER scoped-paper diverse-window K5 | `service-backed-scoped-diverse-window-k5` passed 106/200 with `WindowEv@1=22.5%`, `WindowEv@5=53.0%`, `CandidateEv=78.0%`, and `scopeLeakRate=0.0%`. | Five diverse windows gained 15 cases over K3 with no lost K3 hits, but average inspected chunks rose from 8.90 to 14.77. Use this as a high-recall budget tier. |
| QASPER scoped-paper diverse-window K7 | `service-backed-scoped-diverse-window-k7` passed 122/200 with `WindowEv@1=22.5%`, `WindowEv@7=61.0%`, `CandidateEv=78.0%`, and `scopeLeakRate=0.0%`. | Seven diverse windows gained 16 cases over K5 with no lost K5 hits, reducing candidate-hit/window-miss cases to 34. Average inspected chunks rose to 20.13, so use this as a deep-recall budget tier for audits or high-stakes review, not the default chat budget. |
| Active-runtime Product/QASPER budget gate | On 2026-06-27, the active backend/frontend runtime reran Product Paper-QA and QASPER Dev 200 for K3/K5/K7. The expanded Product Paper-QA slice passed 10/10 for all three profiles; QASPER reproduced 91/200, 106/200, and 122/200 with `scopeLeakRate=0.0%`. | The browser-exposed retrieval-budget selector and the service-backed budget ladder are consistent with the eval ledger. The current strategy is validated for text/table scoped paper QA, with figure-heavy QA left to a future figure-bearing benchmark. |
| QASPER center-diverse window probe | A looser selector that avoids adjacent center pages but allows edge-overlap passed 89/200 with `WindowEv@3=44.5%`. | This is positive versus default but below strict non-overlap, so strict diverse windows remain the leading eval variant. |
| QASPER target-term page rerank probe | Same scoped-paper setup with a baseline/dataset/model/annotation/performance target boost passed 83/200 with `WindowEv@3=41.5%` and unchanged `CandidateEv=78.0%`. | Simple target-word weighting regresses the default. Keep it behind `scientific-qa-targets` and look for evidence-aware reranking instead. |
| QASPER chunk-aware page rerank probe | Same scoped-paper setup with candidate-chunk lexical score carried into page ranking passed 75/200 with `WindowEv@3=37.5%` and unchanged `CandidateEv=78.0%`. | Naive chunk lexical carry-over regresses harder than the target-term boost. Keep it behind `scientific-qa-chunk-window` / `scientific-qa-chunk-rerank` and do not promote it. |
| LitSearch Full | On all 597 queries and 64,183 papers, `facet-paper-baseline` reached `Recall@5=48.4%`, `Recall@20=67.4%`, and `MRR=40.1%`; keyword-only reached `Recall@5=18.7%`, `Recall@20=42.9%`, and `MRR=12.8%`. | Literature search needs paper-level title/abstract/facet retrieval before chunk evidence selection. |
| LitSearch service-backed Full | `current-evidence-ledger` ran all 597 queries against all 64,183 imported papers at `Recall@5=54.1%`, `Recall@20=64.5%`, `MRR=45.0%`, and `scopeLeakRate=0.0%`. | The production `PaperRetrievalService` route now has full-corpus evidence. It is close to the offline facet-paper `Recall@20=67.4%` floor and exceeds its `MRR=40.1%`, with no scope leak and no empty retrieved rows. |
| LitSearch service-backed K5 candidate slice | `current-evidence-ledger` ran all 597 queries against 5,060 imported candidate papers with metadata fallback at `Recall@5=57.6%`, `Recall@20=58.4%`, `MRR=47.1%`, and `scopeLeakRate=0.0%`. | This remains a useful faster regression slice. The 64,183-paper service-backed full run is now the primary literature-search evidence. |
| LitSearch service-backed sample-20 | `current-evidence-ledger` ran against 20 imported eval papers with `0` out-of-scope retrieved ids. | The service import/scoring/scope path is runnable; this is not quality evidence because the sample does not cover the 597-query gold set. |
| SAG fast-mode on QASPER Dev 200 | `ChunkEv@3=5.0%`. | Do not replace retrieval with standalone entity/event matching. |
| Product Rescue Smoke | current evidence ledger scored 100.0% on the local product smoke run. | Product behavior is the regression gate for any method promotion. |

LitSearch Full is now scored locally through the parquet-to-JSONL corpus path. The 16,000-paper
scores remain useful sanity floors only; the strategy decision should use the full 597-query /
64,183-paper runs when judging literature-search retrieval quality.

## Next Implementation Boundary

The first production boundary for a dedicated paper-level search index now exists for literature
search, including paper_search first-stage retrieval:

```text
paper_search
paperId
paperTitle
abstract
authors
venue
year
doi
arxivId
originalFilename
searchText
access fields
```

Keep this as a search/index boundary first. Do not add a new MySQL table, paper-level vectors, or
user-facing API changes until service-backed Product, QASPER, and service-backed LitSearch runs
justify the extra surface area. Product and QASPER service-backed page-window scorecards now exist,
and LitSearch Full now has offline full-corpus floors plus a full service-backed run. A test-scoped
LitSearch runner turns production `PaperRetrievalService` results into `retrievedCorpusIds` and
normal scorecard artifacts. A matching LitSearch importer creates eval-scoped PaperLoom paper/chunk
rows plus `paper_search` and `paper_chunks` documents from structured LitSearch JSONL, without OCR.
The full service-backed LitSearch run imports 64,183 papers and reaches `Recall@20=64.5%` with no
scope leak. This is the current production-route evidence for literature search. The older
`litsearch-service-slice-k5` run remains a useful faster regression slice at `Recall@20=58.4%`.
For scoped paper QA, the next evidence gate is not more import plumbing or simply bypassing
first-stage retrieval. The scoped-paper QASPER run still trails the offline scientific-QA
paragraph-window baseline even though `CandidateEv=78.0%`, so the method needs better locator query
planning, page/window ranking, or evidence selection before `LOCATE_PAGES` should become a default
production tool. The positive diverse-window run shows the next boundary should be coverage-aware
window packing over trusted scoped-paper candidates, with a budget policy: K3 for interactive use,
K5 when the user needs higher recall, and K7 for deep-recall audits where the extra inspected
context is acceptable. The target-term and
chunk-aware rerank probes have already ruled out broad facet boosts and naive candidate-chunk
lexical carry-over as defaults; future rerankers should be measured as separate harness variants
first.

## Production integration boundary

The K3/K5/K7 diverse-window results are now exposed as an eval-backed budget policy, not as a
default-route promotion. Current production code has these relevant boundaries:

- `RetrievalBudget` controls latency, token budget, minimum score, plateau delta, and page batch
  size, plus page-window `topK`, radius, and planner. `forQa()` keeps the interactive K3 diverse
  window profile; `forQaHighRecallPageWindows()` exposes K5; and
  `forQaDeepAuditPageWindows()` exposes K7.
- Backend chat scope can now carry `retrievalBudgetProfile=interactive|high_recall|deep_audit`.
  Missing or unknown values resolve to interactive/K3, and `EvidenceToolExecutor` uses the selected
  profile for scoped evidence search and trusted page inspection.
- The frontend chat input now exposes a three-option retrieval-depth selector and sends
  `scope.retrievalBudgetProfile` in structured websocket chat payloads. The default is
  `interactive`; clearing a paper scope does not promote the budget.
- Active browser/runtime verification on 2026-06-27 confirmed that proxy login succeeds, the
  chat page renders `快速`/`高召回`/`审计`, WebSocket reaches `已连接`, and selecting `高召回`
  sends a chat frame with `scope.retrievalBudgetProfile=high_recall`.
- `INSPECT_PAGE` is trusted-anchor only: `EvidencePlanner` may use it only after a paper id and
  page number are known, and `EvidenceToolExecutor` calls `PaperPageWindowService` for that trusted
  page window.

Guardrail: do not wire K7 into default chat. The safe product path is an explicit budget selector layered above
the current route, for example:

| Product mode | Eval budget | Intended use |
|---|---|---|
| Interactive | K3 diverse windows | Normal scoped paper QA when latency/context should stay modest. |
| High recall | K5 diverse windows | User asks for more complete evidence and accepts more inspected context. |
| Deep audit | K7 diverse windows | High-stakes review, debugging, or audit workflows where context cost is acceptable. |

Product Paper-QA plus QASPER K3/K5/K7 were rerun with the active frontend/backend runtime on
2026-06-27 and matched the expected score ladder. The Product gate now contains 10 scoped cases
covering summary, concept mining, retrieval strategies, method/dataset setup, tables, limitations,
conclusion, reference-context, and multi-paper disambiguation. LitSearch is unaffected by this
page-window budget policy because its primary task is paper-level corpus recall through
`paper_search`. The current strategy is promoted for text/table paper RAG; figure-heavy QA should be
evaluated later with a figure-bearing product paper or a multimodal benchmark.

## Guardrails

- Keep `eval/rag/CHEATSHEET.md` concise; put interpretation here or in `RAG_METHOD_EXPLORATION.md`.
- Do not report sample or mini LitSearch runs as `LitSearch Full`.
- Do not run QASPER or LitSearch through OCR for normal RAG eval; they are structured-text
  benchmarks. OCR belongs to a separate PDF-ingestion benchmark.
- Keep QASPER test sealed; iterate on dev/sample splits.
