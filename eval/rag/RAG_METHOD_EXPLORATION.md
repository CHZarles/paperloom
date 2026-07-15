# PaperLoom RAG Method Exploration

> 历史归档：本文记录 2026 年 6 月旧 Elasticsearch/Service-backed 检索路径的实验过程，不是当前
> Harness 运行指南。当前流程见 [`research/golden-data/README.md`](../../research/golden-data/README.md)。

This note keeps the method exploration separate from `CHEATSHEET.md`, so the cheatsheet can stay a
short score table.

Current decision snapshot: [`RETRIEVAL_STRATEGY_DECISION.md`](RETRIEVAL_STRATEGY_DECISION.md).

## Product Frame

PaperLoom is a research-paper RAG workbench for people who read papers with a concrete research
intent:

- researchers comparing methods, experiments, limitations, and related work
- students trying to understand a paper section by section
- engineers mining implementation details, datasets, metrics, tables, and figures
- reviewers or writers checking whether claims are backed by page-level evidence

The system should optimize for evidence-grounded paper reading, not generic knowledge-base chat.
Good answers must preserve `paperId`, `paperTitle`, `originalFilename`, page evidence, chunk
provenance, and durable reference mappings.

## Current RAG Shape

Current production path:

1. PDF parsing produces structured elements.
2. `PaperChunkBuilder` turns elements into chunks with page and provenance fields.
3. `PaperTextChunk` persists chunk text plus metadata in MySQL.
4. `VectorizationService` embeds chunks and indexes `PaperChunkDocument` into Elasticsearch.
5. `PaperQueryPlanner` normalizes the query, detects intent, and adds query expansions.
6. `HybridSearchService` runs semantic and keyword retrieval, then fuses chunk hits.
7. `PaperRetrievalService` filters unusable evidence and reranks experiment/table-like evidence.
8. `PaperPageWindowService` can inspect MySQL chunk windows by `paperId` and page range without
   changing the chat path.
9. `EvidenceToolExecutor` turns retrieval results into an evidence ledger.
10. `EvidenceAnswerGenerator` answers only from ledger evidence and verifies citation tokens.

Current route split:

| User intent | Retrieval unit | Current strategy |
|---|---|---|
| literature search / recommendations | paper first | `LITERATURE_SEARCH` planner intent, paper-level title/abstract/facet query expansion, then evidence ledger for recommendation snippets |
| paper QA / scoped reading | evidence first | hybrid chunk retrieval plus page-window inspection candidates |
| known page/reference follow-up | deterministic inspection | `INSPECT_REFERENCE` or `INSPECT_PAGE` once the paper/page anchor is trusted |

Existing metadata that is already useful for paper RAG:

| Field | Current use | Exploration use |
|---|---|---|
| `pageNumber` | source preview and citations | page-first retrieval, neighbor expansion |
| `anchorText` | preview anchor | locator API display |
| `sectionTitle` / `sectionLevel` | answer prompt context | section routing and boosting |
| `sourceKind` | table/figure/text distinction | table/figure-aware retrieval |
| `evidenceRole` | experiment-result boosting | intent-specific reranking |
| `bboxJson` | visual provenance | page screenshot / visual grounding |
| `paperTitle`, `abstractText`, `authors`, `venue`, `year`, `doi`, `arxivId` | paper metadata | paper-level retrieval and filtering |

Main gap: retrieval is still chunk-first. A long paper often needs a coarse locating step before
chunk-level evidence selection.

## Offline Page-Index Prototype

The repo now has a test-scoped page-index prototype under
`src/test/java/io/github/chzarles/paperloom/eval/`:

| Class | Role |
|---|---|
| `PaperPageDocument` | Page-level document with paper id, title, page number, page text, chunk ids, sections, source kinds, tables, and figures. |
| `PaperPageIndexBuilder` | Groups chunk-like `SearchResult` rows into page documents. |
| `PaperPageLocator` | Ranks pages for a query with lightweight title/text/section/source-kind signals and expands same-paper neighbors. |
| `PaperPageLocatorQueryPlanner` | Expands generic paper-reading requests and scientific-QA questions into locator terms from titles, keyword-like lines, section navigation hints, and evidence-oriented benchmark facets. |
| `PaperPageLocatorTool` | Eval-scoped shape of `locate_pages` and `inspect_page` over page windows. |
| `PaperPageWindow` / `PaperPageInspection` | Page-window response and inspected chunk/table/section provenance. |
| `PaperEvidenceHitScorer` | Scores whether chunk-first or inspected page-window evidence contains required benchmark evidence regexes. |
| `PaperPageLocatorScorer` | Computes `pageRecallAtK` and `pageMrr` for page-location experiments. |
| `PaperPageWindowScorer` | Computes window recall and MRR for locator-API experiments. |
| `PaperPageLocatorBenchmarkCli` | Runs page-location cases from JSONL chunks, optionally joins RAG cases, then writes retrieved page keys, scorecard, report, and cheatsheet rows. |

This is deliberately offline and eval-scoped. It gives us a cheap way to test whether locating
paper/page windows before selecting evidence is worth promoting into MySQL/Elasticsearch production
metadata.

Minimal page-location case JSONL:

```json
{"id":"noise_table","query":"高噪声实验 accuracy table","goldPageKeys":["paper-a:4"]}
```

Minimal chunk JSONL:

```json
{"paperId":"paper-a","paperTitle":"Agentic Retrieval","originalFilename":"agentic.pdf","pageNumber":4,"chunkId":7,"sectionTitle":"Experiments","sourceKind":"TABLE","tableId":"table-2","text":"Table 2 reports accuracy under increasing noise."}
```

Run shape:

```bash
java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" \
  io.github.chzarles.paperloom.eval.PaperPageLocatorBenchmarkCli \
  --cases eval/rag/page-location/<cases>.jsonl \
  --rag-cases eval/rag/<rag-cases>.jsonl \
  --chunks eval/rag/page-location/<chunks>.jsonl \
  --retrieved eval/rag/page-location/<run>-retrieved.jsonl \
  --harness-id page-index-offline \
  --dataset-id page-location-dev \
  --query-planner page-locator \
  --window-radius 2 \
  --top-k 3
```

The output metrics are `pageRecallAt1`, `pageRecallAt3` or the requested top-k, `pageMrr`, and
positive-score variants such as `positivePageRecallAt3`. When `--window-radius` is nonzero, the run
also records `windowRecallAtK`, `windowMrr`, and positive-window variants. When `--rag-cases` is
provided, it also records `chunkEvidenceHitAtK` and `windowEvidenceHitAtK`, using the same
`requiredEvidenceRegex` rules as the answer/evidence benchmark. This lets us distinguish "the
locator found the right page" from "the inspected window actually contains answer-bearing evidence."

Current local product-smoke page-location results:

| Dataset | Planner | Radius | Cases | Page@1 | Page@3 | PosPage@3 | Window@1 | PosWindow@1 |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| `product-rescue-smoke-page-location` | none | 0 | 3 | 13.9% | 41.7% | 16.7% | 13.9% | 5.6% |
| `product-rescue-smoke-page-location` | page-locator | 0 | 3 | 13.9% | 41.7% | 41.7% | 13.9% | 13.9% |
| `product-rescue-curated-page-location` | none | 0 | 6 | 33.3% | 83.3% | 33.3% | 33.3% | 16.7% |
| `product-rescue-curated-page-location` | page-locator | 0 | 6 | 83.3% | 100.0% | 100.0% | 83.3% | 83.3% |
| `product-rescue-curated-page-location` | none | 2 | 6 | 33.3% | 83.3% | 33.3% | 83.3% | 33.3% |
| `product-rescue-curated-page-location` | page-locator | 2 | 6 | 83.3% | 100.0% | 100.0% | 100.0% | 100.0% |

Current local Product evidence-hit results:

| Dataset | Planner | Radius | Cases | ChunkEv@3 | WindowEv@1 | WindowEv@3 | PosPage@3 | PosWindow@1 |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| `product-rescue-smoke-page-location` | none | 2 | 3 | 0.0% | 100.0% | 100.0% | 16.7% | 22.2% |
| `product-rescue-smoke-page-location` | page-locator | 2 | 3 | 0.0% | 100.0% | 100.0% | 41.7% | 47.2% |

Current local QASPER paragraph-window results:

| Dataset | Planner | Radius | Cases | ChunkEv@3 | WindowEv@1 | WindowEv@3 | Page@3 |
|---|---|---:|---:|---:|---:|---:|---:|
| `qasper-dev-50-page-window` | none | 1 | 44 | 25.0% | 13.6% | 29.5% | 22.7% |
| `qasper-dev-50-page-window` | page-locator | 1 | 44 | 25.0% | 18.2% | 27.3% | 20.5% |
| `qasper-dev-50-page-window` | scientific-qa | 1 | 44 | 25.0% | 38.6% | 56.8% | 40.9% |
| `qasper-dev-200-page-window` | none | 1 | 156 | 34.6% | 21.2% | 43.6% | 33.3% |
| `qasper-dev-200-page-window` | page-locator | 1 | 156 | 34.6% | 23.7% | 43.6% | 31.4% |
| `qasper-dev-200-page-window` | scientific-qa | 1 | 156 | 34.6% | 28.8% | 54.5% | 37.8% |

Current service-backed QASPER result:

| Dataset | Harness | Cases | Pass | WindowEv@1 | WindowEv@K | CandidateEv | ScopeLeak |
|---|---|---:|---:|---:|---:|---:|---:|
| `qasper-dev-200` | `service-backed-page-window` | 200 | 42.0% | 22.5% | 42.0% | - | 0.0% |
| `qasper-dev-200` | `service-backed-scoped-page-window` | 200 | 42.5% | 22.5% | 42.5% | 78.0% | 0.0% |
| `qasper-dev-200` | `service-backed-scoped-diverse-window` | 200 | 45.5% | 22.5% | 45.5% | 78.0% | 0.0% |
| `qasper-dev-200` | `service-backed-scoped-diverse-window-k5` | 200 | 53.0% | 22.5% | 53.0% | 78.0% | 0.0% |
| `qasper-dev-200` | `service-backed-scoped-diverse-window-k7` | 200 | 61.0% | 22.5% | 61.0% | 78.0% | 0.0% |
| `qasper-dev-200` | `service-backed-scoped-center-diverse-window` probe | 200 | 44.5% | 22.5% | 44.5% | 78.0% | 0.0% |
| `qasper-dev-200` | `service-backed-scoped-page-window` + target-term boost probe | 200 | 41.5% | 22.0% | 41.5% | 78.0% | 0.0% |
| `qasper-dev-200` | `service-backed-scoped-page-window` + chunk-aware page rerank probe | 200 | 37.5% | 18.0% | 37.5% | 78.0% | 0.0% |

Current local SAG-style fast-mode result:

| Dataset | Harness | Cases | ChunkEv@1 | ChunkEv@3 |
|---|---|---:|---:|---:|
| `qasper-dev-200-page-window` | `sag-style-fast-mode` | 200 | 0.5% | 5.0% |

This result is useful precisely because it separates accidental fallback ordering from real locator
signal. On broad regex-derived labels, the lightweight planner did not change broad `Page@3`, but it
turned all current hits into positive-scored hits. On curated exact-page labels, planner expansion
improved `PosPage@3` from 33.3% to 100.0% and `PosMRR` from 25.0% to 91.7%. The useful expansions are
not generic synonyms; they are paper-reading locator hints for related concepts, retrieval
strategies, method/dataset setup, table-oriented experiment results, and limitations/conclusion.
With a radius-2 page window, the planned locator reaches `PosWindow@1=100.0%`, meaning the first
`inspect_page` window contains the exact gold page for every curated case. The benchmark runner can
now also compare chunk-first evidence hit against inspected page-window evidence hit on matching RAG
cases. On the three current Product evidence cases, inspected page windows recovered all required
evidence at top-1 window while the lightweight chunk-first top-3 baseline recovered none. This is
not a claim about production hybrid retrieval yet; it is a strong enough offline signal to run the
same evidence-hit comparison on QASPER-style cases before promoting page windows into the production
retrieval path.

The QASPER paragraph-window smoke shows the difference between generic paper navigation and
scientific-QA evidence location. Radius-1 inspected windows improved top-3 evidence hit slightly over
the lightweight chunk-first baseline (`29.5%` vs `25.0%`) for raw lexical ranking. The
product-oriented `page-locator` planner improved top-1 window hit (`18.2%` vs `13.6%`) but reduced
top-3 hit (`27.3%` vs `29.5%`). The `scientific-qa` planner adds narrow benchmark facets for
baseline/comparison, dataset/corpus/language pairs, models/algorithms, results/performance, and
annotation/crowdsourcing. On QASPER Dev 50 it raised `WindowEv@3` to `56.8%` and `Page@3` to `40.9%`;
on the larger QASPER Dev 200 page-window slice it held up with `WindowEv@3=54.5%` and `Page@3=37.8%`.
The service-backed QASPER run reached `WindowEv@3=42.0%` with no scope leak. A scoped-paper variant
that reads all chunks from the trusted paper before locating pages reached only `42.5%`, while the
full scoped-paper candidate set reached `CandidateEv=78.0%`. Together they validate the import and
service bridge, but also show that simply bypassing first-stage candidate retrieval does not close
the gap to the offline paragraph-window oracle. A simple target-term boost for baseline/dataset/
model/annotation/performance questions was a negative probe: it kept `CandidateEv=78.0%` but lowered
`WindowEv@3` to `41.5%` and increased candidate-hit/window-miss cases from 71 to 73. That boost now
requires the explicit `scientific-qa-targets` planner variant and should not be treated as default
`scientific-qa`. A chunk-aware page rerank probe that carried local chunk lexical scores into page
ranking also regressed: it kept `CandidateEv=78.0%` but lowered `WindowEv@3` to `37.5%`, gaining 18
cases and losing 28 versus the stable default. It now requires the explicit
`scientific-qa-chunk-window` / `scientific-qa-chunk-rerank` variants. A non-overlapping
diverse-window selector is the first positive service-backed window-selection probe: with the same
scoped-paper candidates it raised QASPER Dev 200 to 91/200 (`WindowEv@3=45.5%`), gaining 11 cases
and losing 5 versus stable `scientific-qa`, while the original Product Paper-QA slice stayed 3/3. This points to
coverage-aware window packing as the next useful direction, not more broad term weighting. A looser
center-diverse probe that only avoids adjacent center pages scored 89/200 (`WindowEv@3=44.5%`):
positive versus default, but below strict non-overlap. It gained 4 cases and lost 6 versus strict
diverse, so strict non-overlap remains the leading scoped-paper selector. Increasing strict
diverse-window budget from three to five inspected windows is the first high-recall step: it reaches
106/200 (`WindowEv@5=53.0%`), gains 15 cases over top-3 diverse, and loses no previous hits. The cost
is context size: average inspected chunks rise from 8.90 to 14.77 per case. Treat this as a
high-recall budget tier, not a free replacement for interactive default settings.
Increasing the same strict diverse-window budget to seven inspected windows reaches 122/200
(`WindowEv@7=61.0%`), gains another 16 cases over K5, and loses no K5 hits. Candidate-hit/window-miss
cases fall to 34, but average inspected chunks rise to 20.13 per case. Treat this as a deep-recall
budget tier for audit or high-stakes review workflows.

The first SAG-style fast-mode run is a negative control. Naive lexical entity/event expansion over
QASPER paragraph chunks recovered only `ChunkEv@3=5.0%` on 200 cases. PaperLoom should not replace
hybrid/page retrieval with standalone SAG-style entity matching. If SAG remains in the candidate
set, it should be constrained to a second-stage expansion after `PaperRetrievalService` or
page-window hits, with typed scientific entities and strict context budgets.

## Service-Backed Page-Window Bridge

The first production-side support for page-window eval is intentionally narrow:

| Component | Role |
|---|---|
| `PaperTextChunkRepository.findByPaperIdAndPageNumberBetweenOrderByPageNumberAscChunkIdAsc` | Reads MySQL chunks for a paper/page window. |
| `PaperPageWindowService.inspectPageWindow` | Converts those chunks into ledger-ready `SearchResult` rows with paper title, original filename, page, section, source kind, table/figure/formula ids, bbox, parser metadata, and evidence role. |
| `ServiceBackedPageWindowHarness` | Test-scoped coordinator for production first-stage retrieval, page-window ranking, MySQL inspection, and evidence-ledger packing. |
| `ServiceBackedPageWindowBenchmarkRunner` | Batches RAG benchmark cases through the harness, scores `windowEvidenceHitAtK`, and writes standard run artifacts. |
| `ServiceBackedPageWindowBenchmarkCli` | Spring-backed CLI for running the page-window bridge against imported Product/QASPER paper-QA cases and refreshing the cheatsheet. |
| `PlannerActionType.INSPECT_PAGE` / `EvidenceToolExecutor.inspectPage` | Minimal production tool shape for deterministic page-window inspection once a paper id and page number are known. |
| `paper_text_chunks` index metadata | Adds `paper_id,page_number,chunk_id` and `paper_id,chunk_id` indexes for page-window inspection and chunk lookup. |

This adds `inspect_page` as a deterministic page-window tool, but does not make it the default chat
path. The intended next harness is:

```text
PaperRetrievalService first-stage hits
-> group hits by paperId/pageNumber
-> rank page windows with the scientific-QA or page-locator planner
-> inspect selected windows through PaperPageWindowService
-> EvidenceLedgerService.fromSearchResults
-> ServiceBackedPageWindowBenchmarkRunner scorecard artifacts
```

For trusted scoped-paper QA, the diagnostic harness can instead use:

```text
PaperPageWindowService.inspectPaper over scoped paper ids
-> group all scoped-paper chunks by paperId/pageNumber
-> rank page windows with the scientific-QA or page-locator planner
-> inspect selected windows through PaperPageWindowService
-> EvidenceLedgerService.fromSearchResults
-> ServiceBackedPageWindowBenchmarkRunner scorecard artifacts
```

This is the right boundary for PaperLoom: use Elasticsearch to find likely papers/chunks, use MySQL
for deterministic page-window inspection, then let the existing evidence ledger enforce context
budget and provenance. The service-backed CLI now makes this path runnable without backend restart.
Production `RetrievalBudget` now carries the eval-backed page-window profile fields: interactive QA
uses K3 diverse windows, high-recall QA uses K5, and deep-audit QA uses K7. Backend chat scope can
now pass `retrievalBudgetProfile` into `SourceScope` and `EvidenceToolExecutor`, with missing or
unknown values defaulting to interactive/K3. The named frontend/product entry point is now the chat
input retrieval-depth selector (`快速`/`高召回`/`审计`), and browser runtime verification confirmed
that it sends the selected `scope.retrievalBudgetProfile` over WebSocket. Fresh Product/QASPER
reruns completed on 2026-06-27: the expanded Product Paper-QA slice passed 10/10 for K3/K5/K7, and
QASPER Dev 200 reproduced the expected 91/200, 106/200, and 122/200 ladder with
`scopeLeakRate=0.0%`.
`LOCATE_PAGES` deliberately stays out of the default production tool surface; the promoted
production boundary is the explicit retrieval-budget selector plus trusted `INSPECT_PAGE`.

Current API boundary: `INSPECT_PAGE` is safe when the planner already has a trusted `paperId` and
`pageNumber`. `LOCATE_PAGES` should remain eval-gated until the service-backed page-window runner
beats chunk-first retrieval on Product/QASPER; otherwise the system risks adding a tool that only
looks more agentic without improving evidence hit rate.

## Benchmark Ladder

| Layer | Dataset | Purpose | Primary signal |
|---|---|---|---|
| product | Product Rescue Smoke | catch PaperLoom regressions | pass rate, citation mapping, bad evidence |
| product QA | Product Paper-QA Slice | scoped product paper-QA for service-backed page-window runs | pass rate and evidence hit |
| professional QA | QASPER Dev (`https://allenai.org/data/qasper`) | paper QA with supporting evidence | pass rate and evidence hit rate |
| professional retrieval | LitSearch (`https://huggingface.co/datasets/princeton-nlp/LitSearch`) | literature-search paper retrieval | Recall@20 and MRR |
| method candidate | SAG (`https://github.com/Zleap-AI/SAG`) | event/entity graph expansion over retrieved chunks | QASPER evidence hit and LitSearch Recall@20 |
| next candidate | PeerQA (`https://github.com/UKPLab/peerqa`) | peer-review-style document QA | evidence retrieval + answer generation |
| next candidate | CSFCube (`https://github.com/iesl/CSFCube`) | faceted scientific paper retrieval | NDCG / Recall@20 / MRR |
| next candidate | DORIS-MAE (`https://github.com/kwang927/Doris-Mae-Dataset`) | aspect-based complex scientific retrieval | NDCG / Recall@20 / MRR |
| later multimodal | SPIQA (`https://github.com/google/spiqa`) | figure/table-heavy paper QA | multimodal QA accuracy |
| later biomedical | LitQA (`https://github.com/Future-House/litqa`) | full-text biomedical paper QA | source retrieval + MCQ accuracy |

Current local LitSearch floor:

| Corpus slice | Harness | Recall@5 | Recall@20 | MRR |
|---:|---|---:|---:|---:|
| 11,000 / 64,183 papers | `keyword-only-baseline` | 13.6% | 17.4% | 9.6% |
| 16,000 / 64,183 papers | `keyword-only-baseline` | 15.0% | 20.7% | 10.1% |
| 16,000 / 64,183 papers | `facet-paper-baseline` | 22.2% | 26.9% | 18.6% |
| 64,183 / 64,183 papers | `keyword-only-baseline` | 18.7% | 42.9% | 12.8% |
| 64,183 / 64,183 papers | `facet-paper-baseline` | 48.4% | 67.4% | 40.1% |
| 5,060 imported candidate papers | `current-evidence-ledger` service-backed slice + metadata fallback | 57.6% | 58.4% | 47.1% |
| 64,183 imported papers | `current-evidence-ledger` service-backed full | 54.1% | 64.5% | 45.0% |

The full-corpus result confirms the partial trend: paper-level title/abstract/facet retrieval is a
much better first stage for literature search than undifferentiated keyword overlap over full text.
The service-backed full result confirms that the production PaperLoom retrieval path can run all
597 LitSearch queries against 64,183 imported papers without scope leakage. It is close to the
offline facet-paper full-corpus `Recall@20=67.4%` floor and exceeds its `MRR=40.1%`. The older
service-backed candidate-slice result remains useful as a faster regression slice, but it is no
longer the main literature-search evidence because the full corpus has now been imported and scored.

Production boundary now reflects this result: `PaperQueryPlanner` marks recommendation/related-paper
queries as `LITERATURE_SEARCH`, strips recommendation boilerplate from the core query, and expands it
with title/abstract and related-work facets. `PaperRetrievalService` marks those hits as
`PAPER_LEVEL` or `EXPANDED_PAPER_LEVEL` and reranks candidates with paper-title and abstract-like
evidence coverage. It then collapses ranked hits to one best evidence snippet per `paperId`, because
recommendation output is a paper-candidate list, not a chunk list. This is a small production-safe
step toward the final two-stage literature-search strategy. The next boundary now includes a
dedicated `paper_search` document, mapping, startup index initialization, delete cleanup,
vectorization write path over title, abstract, authors, venue/year, filename, DOI, and arXiv id, and
`paper_search` first-stage retrieval for `LITERATURE_SEARCH`. The route then scopes normal chunk
retrieval to candidate paper ids so final answers still use citeable chunk/page evidence. If a
candidate has no scoped chunk evidence, the route now keeps the paper metadata candidate instead of
dropping the paper. If `paper_search` has no candidates, it falls back to the previous chunk-index
bridge.
`ServiceBackedLitSearchBenchmarkRunner` and `ServiceBackedLitSearchBenchmarkCli` now provide the
LitSearch scoring bridge for this route:
gold queries -> `PaperRetrievalService` -> normalized `retrievedCorpusIds` -> standard
`scorecard.json` and cheatsheet rows. A 5,060-paper service candidate slice has been imported into
PaperLoom's eval-scoped MySQL/Elasticsearch state and scored through the service-backed CLI:
`20260624T195908Z-current-evidence-ledger-litsearch-service-slice-k5-metadata-fallback` reaches
`Recall@5=57.6%`, `Recall@20=58.4%`, and `MRR=47.1%` over all 597 queries with
`scopeLeakRate=0.0%`. Compared with the previous service slice run, it gained 4 Recall@20 cases and
lost 0; the gained cases were all `inline_nonacl`. This local run saw 199 text-only fallbacks after
embedding rate-limit checks, so treat it as production-path evidence rather than a stable-embedding
ceiling. A smaller scoped run
`2026-06-24T121310Z-current-evidence-ledger-litsearch-sample-20-scope-fixed` kept retrieved ids
inside the 20 imported papers (`0` out-of-scope ids), but it is not retrieval-quality evidence:
the sample corpus does not cover the full 597-query gold set, and local embedding query rate limits
forced text fallback during the runtime smoke. The KNN scope filter itself is locked by unit test.
`LitSearchPaperLoomImportCli` now covers that import step for structured corpus JSONL by creating
PaperLoom paper rows, deterministic title/abstract and body chunks, `paper_search` metadata docs,
and chunk docs with eval placeholder vectors.
The full import is now complete: 64,183/64,183 LitSearch papers are present locally, and
`20260627T043549Z-current-evidence-ledger-litsearch-full` scored all 597 queries at
`Recall@5=54.1%`, `Recall@20=64.5%`, `MRR=45.0%`, and `scopeLeakRate=0.0%`, with 597 non-empty
retrieved rows.

The scoped Product paper-QA service-backed page-window smoke has a real scorecard:
`2026-06-24T124226Z-service-backed-page-window-product-rescue-paper-qa` passed 3/3 with
`WindowEv@1=100.0%`, `WindowEv@3=100.0%`, and `scopeLeakRate=0.0%`. The expanded 10-case Product
Paper-QA gate now covers summary, concept mining, retrieval strategies, method/dataset setup,
tables, limitations, conclusion, reference-context, and multi-paper disambiguation. It passes
10/10 for K3, K5, and K7 with `scopeLeakRate=0.0%`; the product paper has no figure chunks, so
figure-heavy QA remains a future asset/benchmark expansion rather than a blocker for the current
text/table strategy.
The QASPER service-backed gate now exists:
`2026-06-24T125506Z-service-backed-page-window-qasper-dev-200` passed 84/200 with
`WindowEv@1=22.5%`, `WindowEv@3=42.0%`, and `scopeLeakRate=0.0%`. During this runtime smoke, local
embedding limits pushed many queries through text-only fallback. Treat the result as production-path
evidence, but not as a reason to promote `LOCATE_PAGES`. The later 2026-06-27 K3/K5/K7 reruns are
the current stable budget-ladder evidence.
The scoped-paper diagnostic gate also exists:
`2026-06-24T162100Z-service-backed-scoped-page-window-qasper-dev-200` passed 85/200 with
`WindowEv@1=22.5%`, `WindowEv@3=42.5%`, `CandidateEv=78.0%`, and `scopeLeakRate=0.0%`. It changes
only five individual case outcomes versus first-stage retrieval and improves by one net pass. The
new per-case diagnostic splits the 200 cases into 85 window hits, 71 candidate-hit/window-miss
cases, and 44 cases where the benchmark evidence regex is not present in the scoped-paper candidate
chunks. The remaining QASPER gap is mostly locator/window evidence selection rather than paper-scope
selection. The target-term rerank probe
`2026-06-24T134155Z-service-backed-scoped-page-window-qasper-dev-200-target-rerank` scored lower at
83/200, so it is kept behind the explicit `scientific-qa-targets` variant. The chunk-aware page
rerank probe `2026-06-24T144100Z-service-backed-scoped-page-window-qasper-dev-200-chunk-window`
scored lower again at 75/200 with unchanged `CandidateEv=78.0%`, so local chunk lexical carry-over
is also kept behind explicit eval variants. The diverse-window selector
`2026-06-24T162300Z-service-backed-scoped-diverse-window-qasper-dev-200` is a positive eval variant:
it keeps `CandidateEv=78.0%`, raises QASPER to 91/200, and reduces candidate-hit/window-miss cases
from 71 to 65 by inspecting less-overlapping windows. The center-diverse follow-up
`2026-06-24T164100Z-service-backed-scoped-center-diverse-window-qasper-dev-200` scored 89/200, so
allowing edge-overlap recovers some default wins but gives back more strict-diverse gains.
The high-recall budget run
`2026-06-24T171100Z-service-backed-scoped-diverse-window-k5-qasper-dev-200` inspects five diverse
windows and reaches 106/200, reducing candidate-hit/window-miss cases to 50.
The deep-recall budget run
`2026-06-24T173100Z-service-backed-scoped-diverse-window-k7-qasper-dev-200` inspects seven diverse
windows and reaches 122/200, reducing candidate-hit/window-miss cases to 34.
Active-runtime reruns on 2026-06-27 reproduced the same QASPER budget ladder and expanded the
Product gate: Product Paper-QA passed 10/10 for K3, K5, and K7; QASPER Dev 200 stayed at 91/200,
106/200, and 122/200, with `CandidateEv=78.0%` and `scopeLeakRate=0.0%` throughout.

## Method Hypotheses

### H1: Metadata-Boosted Hybrid

Keep the current chunk-first architecture, but make scoring more paper-aware.

Changes to try:

- Boost `sectionTitle` matches for method, experiment, limitation, and summary intents.
- Boost `sourceKind=TABLE|FIGURE|CHART` and `evidenceRole=EXPERIMENT_RESULT` only for experiment
  questions.
- Add paper-title and abstract query matching before chunk rerank.
- Decontextualize chunk text for embedding by prefixing title, section, page, and source kind.

Expected win: low-risk improvement for QASPER and product smoke. It may help LitSearch only if
paper-level title/abstract signals are included.

Current production slice: ES chunk docs now keep raw `textContent` for evidence display and add
`retrievalTextContent` for keyword recall. Vectorization fills that retrieval field with paper title,
original filename, abstract, section, source kind, evidence role, and the raw chunk text; the
`HybridSearchService` keyword branch matches `retrievalTextContent`. This is still a chunk-index
bridge. A separate `paper_search` metadata index now exists for LitSearch-style candidate retrieval,
and `LITERATURE_SEARCH` uses it as the first stage before selecting chunk evidence.

### H2: Page-Index First

Add a coarse page index and retrieve pages before chunks.

Index unit:

```text
paperId
pageNumber
pageText
sectionTitles
sourceKindsOnPage
tableIds
figureIds
pageSummary
pageVector
```

Flow:

1. Retrieve candidate papers/pages from query, title, abstract, section titles, and page summaries.
2. Expand to neighboring pages when the page has tables/figures or section boundaries.
3. Retrieve chunks only inside the selected page window.
4. Answer from chunk evidence with page provenance.

Expected win: better long-paper navigation, fewer junk chunks, better page citations. This should be
measured first on product smoke and QASPER before it is used for LitSearch.

Current status: offline prototype exists for page aggregation, planner-expanded page ranking,
same-paper page windows, exact-page/window scoring, evidence-hit scoring, and standard scorecard
writing. On the curated single-paper page-location set, planner expansion improved
`positivePageRecallAt3` from 33.3% to 100.0%, and the radius-2 locator window achieved
`positiveWindowRecallAt1=100.0%`. On Product evidence-hit smoke, radius-2 inspected windows reached
`windowEvidenceHitAt1=100.0%` while the lightweight chunk-first top-3 baseline stayed at
`chunkEvidenceHitAt3=0.0%`. On QASPER Dev 50 paragraph windows, raw page-window inspection improved
`windowEvidenceHitAt3` to 29.5% over `chunkEvidenceHitAt3=25.0%`, the generic product planner
underperformed raw top-3, and the scientific-QA planner raised `windowEvidenceHitAt3` to 56.8%.
On the larger QASPER Dev 200 page-window slice, scientific-QA held a clear lead:
`windowEvidenceHitAt3=54.5%` versus `43.6%` for raw and product-planned locators. The service-backed
locator harness now exists and shows the live scoped-paper path still trails that offline
paragraph-window result (`42.5%`). A target-term boost probe hurt rather than helped, so the current
service-backed bottleneck is page/window evidence selection, not paper import or simple facet
weighting. A chunk-aware page rerank probe hurt more (`WindowEv@3=37.5%`), which suggests naive
lexical signal from candidate chunks does not reliably identify the best inspected windows. A
diverse-window selector is the first positive service-backed rerank: it keeps the same candidate
set but avoids overlapping inspected windows, raising QASPER Dev 200 to `WindowEv@3=45.5%`.
A center-diverse relaxation scored `44.5%`, which supports strict non-overlap as the better current
coverage heuristic.
With a five-window budget, the same selector reaches `WindowEv@5=53.0%`; this nearly matches the
offline scientific-QA paragraph-window `WindowEv@3=54.5%`, but at a higher inspected-context cost.
With a seven-window budget, it reaches `WindowEv@7=61.0%`. The 2026-06-27 active-runtime rerun
reproduced K3/K5/K7 exactly against QASPER Dev 200, so the remaining page-index risk is product-case
coverage breadth rather than local runtime drift.

### H3: Vector Retrieval Plus Locator APIs

Keep hybrid retrieval, but give the planner explicit paper-navigation tools instead of one generic
`search_papers` tool.

Candidate tools:

| Tool | Purpose |
|---|---|
| `find_papers(query, filters)` | return candidate papers from title/abstract/chunk evidence |
| `locate_pages(paperId, query, sourceKinds)` | return page windows and section hints |
| `inspect_page(paperId, pageNumber, radius)` | return chunks, tables, figures, and anchors around a page |
| `search_within_paper(paperId, query, sectionHint)` | scoped chunk retrieval |
| `inspect_reference(referenceNumber)` | already exists conceptually; keep it durable |

Expected win: multi-step evidence collection for difficult questions. This should reduce false
negatives when the first vector hit is nearby but not answer-bearing.

The local page-location smoke confirms this direction: generic Chinese requests such as "在论文里找相关概念"
and "这个文章讲了什么" need the planner to translate user intent into locator terms such as
`agent harnesses`, `semantic search`, `retrieval strategies`, `dataset`, `abstract`, `introduction`,
`conclusion`, `method`, or section hints before page ranking. The high-noise table query also shows
why the API should return page windows: page 5 and page 7 are both experiment-result pages, but the
answer-bearing table is on page 7. A top-1 radius-2 window centered at page 5 contains page 7, so the
planner can inspect the nearby evidence instead of over-committing to a single page.

### H4: Citation Neighborhood Expansion

After first-pass retrieval, expand from each hit to adjacent chunks and related structured assets.

Expansion rules:

- include previous/next chunk on the same page
- include table caption and table markdown when a table row is hit
- include figure caption and nearby paragraph when a figure is hit
- include first paragraph under the same section heading for method/limitation questions

Expected win: better answer completeness and citation usefulness. Risk is context bloat, so evaluate
with accepted evidence count and answer token budget.

### H5: Query Decomposition For Literature Search

For LitSearch-like queries, decompose into concept facets before retrieval.

Facets:

- task / problem
- method family
- dataset / domain
- metric / evaluation target
- citation-style related-work intent

Expected win: higher LitSearch Recall@20 than keyword-only. It needs paper-level metadata and
possibly a separate paper index; chunk-level retrieval alone is unlikely to be enough.

Current status: the offline `facet-paper-baseline` implements the low-cost version of this idea by
scoring title/abstract query-facet coverage, capping repeated body-term matches, and adding small
title/abstract phrase bonuses. On LitSearch Full it beats the keyword floor: `Recall@20=67.4%`
versus `42.9%`, and `MRR=40.1%` versus `12.8%`. This is strong evidence that PaperLoom needs a
paper-level retrieval stage for literature search, separate from page/window inspection for evidence
QA. The production planner now has a `LITERATURE_SEARCH` intent and `paper_search` first-stage index
boundary. The service-backed candidate-slice gate now has a score:
`litsearch-service-slice-k5` reaches `Recall@20=58.4%` and `MRR=47.1%` with no scope leak over
5,060 imported papers after preserving metadata candidates without chunk evidence. The full
service-backed corpus run now has a score as well: `litsearch-full` reaches `Recall@20=64.5%` and
`MRR=45.0%` over 64,183 imported papers with no scope leak. Use the full run as the main
literature-search evidence and the candidate slice as the faster regression gate.

Current recommendation: keep a routed retrieval strategy, not a single universal retriever.
Literature search should start with paper-level metadata retrieval over title, abstract, authors,
venue/year, and external ids, then select one representative evidence snippet per candidate paper.
Scoped paper QA should stay evidence-first with hybrid chunk retrieval plus page-window inspection.
Known reference/page follow-up should use deterministic inspection tools once `paperId` and
`pageNumber` are trusted.

### H6: SAG-Style Structured Expansion

SAG (`https://github.com/Zleap-AI/SAG`) is a candidate structured retrieval pattern: extract
event/entity records from chunks, store them in relational tables, then use query-time SQL joins to
expand from initially retrieved chunks to related evidence. For PaperLoom, the useful idea is the
schema and expansion loop, not necessarily importing the whole SAG workbench.

PaperLoom adaptation:

1. Keep Elasticsearch hybrid retrieval as the first-stage candidate finder.
2. Add eval-scoped extraction for paper chunks into structures such as `paper_event`,
   `paper_entity`, `paper_event_entity`, and `paper_chunk_event`.
3. For a query, classify facets such as method, dataset, metric, baseline, limitation, and claim.
4. Retrieve initial chunks/pages, then expand through event/entity joins inside the same paper or
   candidate paper set.
5. Feed expanded chunks/page windows into the evidence ledger and score with QASPER `WindowEv@K`,
   product smoke pass rate, and LitSearch Recall@20.

Expected win: multi-hop evidence where the answer-bearing paragraph does not share enough lexical or
embedding signal with the user question, but shares entities/events with a nearby hit. Main risk:
entity/event extraction noise can add expensive context and false positives, so this should be
introduced as an eval-gated harness before changing the production chat path.

Current status: a naive offline fast-mode prototype exists and is not competitive as a standalone
retriever (`ChunkEv@3=5.0%` on QASPER Dev 200). Treat this as evidence to keep SAG-style logic as a
post-retrieval expansion candidate only, unless typed/LLM-normalized extraction dramatically changes
the score.

## Metadata Changes Worth Considering

These fields can be added without changing the product contract:

| Target | Field | Why |
|---|---|---|
| `Paper` | `sourceDataset`, `externalCorpusId`, `evalSplit`, `isEval` | safe benchmark imports |
| `Paper` | normalized title, normalized venue, keyword list | paper-level retrieval |
| `PaperTextChunk` | `pageWindowKey` | page-neighbor expansion |
| `PaperTextChunk` | `sectionPath` | distinguish repeated section names |
| `PaperTextChunk` | `decontextualizedText` | better embeddings without losing raw text |
| ES chunk doc | `retrievalTextContent` | metadata-injected keyword recall while preserving raw evidence text |
| ES chunk doc | `sectionTitle.keyword`, `sectionPath`, `pageWindowKey` | exact filters and boosts |
| new page index | `pageText`, `pageSummary`, `pageVector` | page-first retrieval |
| new structured index | `paper_event`, `paper_entity`, `paper_event_entity`, `paper_chunk_event` | SAG-style SQL expansion |

## Experiment Order

1. Add a figure-bearing product paper slice or SPIQA-style multimodal gate once PaperLoom has
   first-class page image / figure assets.
2. Keep the 64,183-paper LitSearch Full service-backed run as the main literature-search gate and
   use the 5,060-paper service slice as a faster regression gate.
3. Run service-backed Product/QASPER harnesses after any production route or budget change,
   especially changes to `RetrievalBudget`, `PaperRetrievalService`, or page-window packing.
4. Keep SAG as constrained post-retrieval expansion only unless a typed extraction prototype beats
   page-window retrieval on QASPER.
5. Use QASPER dev for method iteration; keep QASPER test sealed.
6. Add PeerQA after QASPER is stable; add SPIQA only when multimodal page assets are first-class.

## Acceptance Criteria For A Method

A method is worth keeping only if it improves at least one professional benchmark without breaking
product behavior:

- Product Rescue Smoke remains 100% pass with no citation mapping regressions.
- QASPER Dev improves evidence hit rate or pass rate.
- LitSearch full Recall@20 beats the keyword-only floor.
- Latency and token usage stay within the interactive chat budget.
- Failure analysis shows fewer false negatives, not just prettier prose.

Page-index specific gates:

- `pageRecallAt1` and `pageRecallAt3` improve on paper QA cases with known evidence pages.
- Neighbor expansion increases evidence hit rate without pushing answer context over budget.
- Returned page windows contain the cited chunk, table, figure, or section needed for the answer.

SAG-specific gates:

- Do not promote standalone fast-mode entity matching; current QASPER Dev 200 `ChunkEv@3=5.0%` is
  far below the page-window and chunk-first floors.
- Re-test SAG only as a constrained post-retrieval expansion with typed entities/events and measure
  whether it improves `WindowEv@3` beyond the scientific-QA page-window baseline.
