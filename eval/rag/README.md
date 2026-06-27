# PaperLoom RAG Eval

This directory is the long-lived RAG evaluation ledger for PaperLoom. It keeps:

- `harnesses.yaml`: stable harness and benchmark registry
- `CHEATSHEET.md`: concise generated score table
- `PROJECT_CONTEXT.md`: product/user context and benchmark fit
- `RAG_METHOD_EXPLORATION.md`: method exploration notes for page-index, locator APIs, and metadata changes
- `RETRIEVAL_STRATEGY_DECISION.md`: current routed retrieval decision and promotion guardrails
- `STRATEGY_READINESS.md`: completion audit for what is proven, pending, or blocking final strategy promotion
- `runs/`: ignored local run artifacts with `run.json`, `scorecard.json`, and `report.md`
- benchmark source notes under `qasper/`, `litsearch/`, and `pdf-parser/`

The cheatsheet is intentionally short. Raw evidence, per-case failures, and diagnostics belong in
`runs/<run-id>/`, not in the top-level table.
The current retrieval decision is tracked separately in
[`RETRIEVAL_STRATEGY_DECISION.md`](RETRIEVAL_STRATEGY_DECISION.md) so benchmark interpretation does
not bloat the cheatsheet.
Harness rows in `harnesses.yaml` can set `benchmarkIds` to a comma-separated allowlist. Use that
field for harnesses that only make sense on one task family, such as LitSearch-only retrieval
baselines or scoped page-window QA, so `CHEATSHEET.md` stays compact instead of rendering every
pending cross-product.

## Method Prototypes

The current offline method prototype is a page-index locator under test scope:

```text
src/test/java/com/yizhaoqi/smartpai/eval/PaperPageDocument.java
src/test/java/com/yizhaoqi/smartpai/eval/PaperPageIndexBuilder.java
src/test/java/com/yizhaoqi/smartpai/eval/PaperPageLocator.java
src/test/java/com/yizhaoqi/smartpai/eval/PaperPageLocatorQueryPlanner.java
src/test/java/com/yizhaoqi/smartpai/eval/PaperPageLocatorTool.java
src/test/java/com/yizhaoqi/smartpai/eval/PaperPageWindow.java
src/test/java/com/yizhaoqi/smartpai/eval/PaperPageInspection.java
src/test/java/com/yizhaoqi/smartpai/eval/PaperEvidenceHitScorer.java
src/test/java/com/yizhaoqi/smartpai/eval/PaperPageLocatorScorer.java
src/test/java/com/yizhaoqi/smartpai/eval/PaperPageWindowScorer.java
src/test/java/com/yizhaoqi/smartpai/eval/PaperPageLocatorBenchmarkCli.java
```

It groups chunk-level evidence into page documents, ranks page windows, expands same-paper neighbor
pages, computes page/window recall and MRR, and can write standard PaperLoom run artifacts. When
the runner also receives the original RAG cases through `--rag-cases`, it records
`chunkEvidenceHitAtK` and `windowEvidenceHitAtK` so page-window inspection can be compared against
chunk-first evidence selection on the same `requiredEvidenceRegex` rules.
`eval/rag/page-location/product-rescue-curated-page-cases.jsonl` is a small exact-page prototype
dataset for this method. Keep the prototype offline until a broader benchmark run proves the
page-first approach beats chunk-first retrieval without hurting citation quality.
`eval/rag/product-rescue-paper-qa.jsonl` is the matching product RAG slice for service-backed
paper-QA runs. It contains 10 scoped manual-source cases covering summary, concept mining,
retrieval strategies, method/dataset setup, tables, limitations, conclusion, reference-context, and
multi-paper disambiguation, so page-window harnesses can be scored without mixing in route-control
or library inventory behavior.

The first service-backed bridge is now available without changing chat behavior:

```text
src/main/java/com/yizhaoqi/smartpai/service/PaperPageWindowService.java
src/main/java/com/yizhaoqi/smartpai/repository/PaperTextChunkRepository.java
src/test/java/com/yizhaoqi/smartpai/eval/ServiceBackedPageWindowHarness.java
src/test/java/com/yizhaoqi/smartpai/eval/ServiceBackedPageWindowBenchmarkRunner.java
src/test/java/com/yizhaoqi/smartpai/eval/ServiceBackedPageWindowBenchmarkCli.java
```

It reads MySQL `paper_text_chunks` by `paperId` and page range, then returns ledger-ready
`SearchResult` rows that preserve page, section, source kind, table/figure/formula ids, bbox, and
paper title metadata. Use it after the caller has already established paper access. The next
service-backed eval run can use `ServiceBackedPageWindowHarness` to combine production hybrid search
for initial candidates with this page-window inspector. `ServiceBackedPageWindowBenchmarkRunner`
then batches RAG cases, computes `windowEvidenceHitAtK`, and writes normal `scorecard.json`
artifacts. `ServiceBackedPageWindowBenchmarkCli` wraps the runner in a web-disabled Spring command,
so an imported Product/QASPER paper-QA slice can be scored against the real PaperLoom services
without starting the backend.
The production planner/executor now also has a minimal `INSPECT_PAGE` action that calls
`PaperPageWindowService` once a trusted paper id and page number are known. Keep `LOCATE_PAGES`
eval-gated until service-backed scorecards prove page location beats chunk-first retrieval.

Page-location input rows are intentionally small:

```json
{"id":"noise_table","query":"高噪声实验 accuracy table","goldPageKeys":["paper-a:4"]}
{"paperId":"paper-a","paperTitle":"Agentic Retrieval","pageNumber":4,"chunkId":7,"sectionTitle":"Experiments","sourceKind":"TABLE","text":"Table 2 reports accuracy under increasing noise."}
```

## Benchmarks

| Benchmark | Tier | Task | Primary metric | Status |
|---|---|---|---|---|
| `product-rescue-smoke` | product | Paper chat regression | `passRate` | runnable |
| `product-rescue-paper-qa` | product | Scoped paper-QA slice | `passRate` | runnable |
| `product-pdf-parser-smoke` | product | Real PDF parser smoke | `passRate` | runnable |
| `qasper-dev-200` | professional | Research-paper evidence QA | `passRate` | runnable |
| `litsearch-full` | professional | Literature-search retrieval | `recallAt20` | offline full scored; service-backed full scored |

QASPER measures answer-and-evidence quality for full-text paper QA. LitSearch measures whether the
retrieval harness finds gold papers for literature-search queries; it should use retrieval metrics
such as `recallAt5`, `recallAt20`, and optionally MRR.

QASPER also has an offline paragraph-window smoke under `eval/rag/qasper/generated/`: structured
abstract/full-text paragraphs are converted into eval-scoped page-like chunks, then scored with
`ChunkEv@K` and `WindowEv@K`. These are paragraph windows, not real PDF pages. The current Dev 200
page-window run shows the scientific-QA page planner as the best local variant so far
(`WindowEv@3=54.5%` vs raw `43.6%`).
The first service-backed Product paper-QA slice run is also recorded:
`service-backed-page-window` passed `product-rescue-paper-qa` at 3/3 with `WindowEv@1=100.0%`,
`WindowEv@3=100.0%`, and `scopeLeakRate=0.0%`.
The first service-backed QASPER Dev 200 run is now recorded as well:
`service-backed-page-window` passed 84/200 with `WindowEv@1=22.5%`, `WindowEv@3=42.0%`, and
`scopeLeakRate=0.0%`. This proves the live PaperLoom service path is scoped and runnable on QASPER,
but it trails the offline scientific-QA paragraph-window result, so locator-style tools should stay
eval-gated while first-stage retrieval is improved.
The scoped-paper variant, which reads all chunks from the trusted scoped paper before page-window
location, passed 85/200 with `WindowEv@3=42.5%`, `CandidateEv=78.0%`, and `scopeLeakRate=0.0%`.
That removes paper-scope misses from the experiment and shows 71 cases where gold evidence exists
in the scoped-paper candidate set but is missed by the selected windows. The next work is better
locator/query/window evidence selection rather than promoting `LOCATE_PAGES`.
A target-term rerank probe was also run on the same scoped-paper setup and scored lower
(`WindowEv@3=41.5%`, `CandidateEv=78.0%`). Keep that boost behind the explicit
`scientific-qa-targets` planner variant; default `scientific-qa` should remain the stable scorecard
row until a reranker improves QASPER without hurting Product.
A chunk-aware page rerank probe was another negative result on the same scoped-paper setup
(`WindowEv@3=37.5%`, `CandidateEv=78.0%`). Keep `scientific-qa-chunk-window` and
`scientific-qa-chunk-rerank` as explicit eval variants rather than default behavior.
The first positive service-backed selector probe is `service-backed-scoped-diverse-window`: it keeps
the same scoped-paper candidates but avoids inspecting overlapping page windows. On QASPER Dev 200 it
passed 91/200 with `WindowEv@3=45.5%` and unchanged `CandidateEv=78.0%`; on the expanded Product
Paper-QA slice it passed 10/10. This makes coverage-aware window packing the leading scoped-paper
eval variant, though `LOCATE_PAGES` should still remain eval-gated until the QASPER
candidate/window gap shrinks further.
The high-recall K5 budget for the same selector passed 106/200 with `WindowEv@5=53.0%`, gaining 15
cases over K3 without losing K3 hits. It also raises average inspected chunks from 8.90 to 14.77, so
record it as a high-recall budget tier rather than the automatic interactive default.
The deep-recall K7 budget passed 122/200 with `WindowEv@7=61.0%`, gaining another 16 cases over K5
without losing K5 hits. Average inspected chunks rise to 20.13, so keep K7 for audit/high-recall
review modes rather than normal chat turns.

LitSearch support covers query/corpus JSON conversion, parquet fallback conversion,
retrieval-output scoring, and scorecard/cheatsheet writing. Offline full-corpus runs are scored for
`keyword-only-baseline` and `facet-paper-baseline`; the service-backed full benchmark is also scored
after importing 64,183 papers into PaperLoom eval indexes.
The LitSearch score CLIs guard `litsearch-full`: offline baselines require a 64,183-paper corpus
JSONL, and service-backed scoped runs require 64,183 imported eval papers before they can write a
full benchmark row.
The real query split, 16,000-row dataset-server sample, six full parquet shards, and
`litsearch-corpus-clean-full.jsonl` are downloaded locally under ignored LitSearch directories. The
full parquet route and full-score commands are documented in `litsearch/README.md`.

For the current service-backed full run, use the unattended wrapper from the repository root:

```bash
cd /home/charles/PaiSmart
.worktrees/adaptive-source-set-rag/scripts/litsearch-full-pipeline.sh summary
```

It resumes interrupted imports, waits for `64183/64183` eval papers, then starts the full
service-backed LitSearch benchmark. Until that full count is reached, `current-evidence-ledger` on
`litsearch-full` must remain `pending` in `CHEATSHEET.md`.
The wrapper and its child import/benchmark scripts normalize Spring CLI execution back to the repo
root before startup, so the local `.env` is loaded consistently even when the command is launched
from the git worktree.

SAG is tracked as a retrieval-strategy candidate under `sag/README.md`. For PaperLoom, the relevant
idea is not a drop-in replacement, but an eval-gated structured expansion stage: initial hybrid/page
hits -> event/entity SQL expansion -> page/window evidence -> evidence ledger.
The first naive offline fast-mode run is a negative control on QASPER Dev 200 (`ChunkEv@3=5.0%`),
so SAG should be re-tested only as constrained post-retrieval expansion, not as the main retriever.

## Benchmark Import Policy

Professional text benchmarks should not enter PaperLoom through OCR by default. QASPER and LitSearch
already provide structured paper text, titles, abstracts, evidence paragraphs, and gold ids, so the
source-of-truth flow is:

1. Keep raw benchmark files under ignored `raw/` directories.
2. Convert them into normalized JSONL under ignored `generated/` directories.
3. Run offline baselines directly from JSONL when possible.
4. Import into PaperLoom only for product-backed eval runs, using eval-scoped paper ids, org tags,
   and indexes.

Use OCR only for a separate PDF-ingestion benchmark. Mixing OCR errors into QASPER or LitSearch
would make the score measure parsing quality instead of RAG retrieval, evidence grounding, and
answer behavior.

The real PDF parser smoke is that separate gate. It lives under `eval/rag/pdf-parser/`, checks
already processed PDF rows in MySQL, and rejects `Paper.isEval`, `sourceDataset`, and `.json`
structured imports. Use it before claiming PDF parser/OCR/page visual evidence quality.

Eval import markers now belong on `Paper` rows, with chunk rows linked by `paperId`:

| Field | Example |
|---|---|
| `isEval` | `true` |
| `sourceDataset` | `litsearch` or `qasper` |
| `externalCorpusId` | LitSearch `corpusid` or QASPER arXiv id |
| `evalSplit` | `full`, `dev`, `test`, or a sample name |
| `orgTag` | `eval-litsearch` or `eval-qasper` |
| `paperId` | `litsearch:<corpusid>` or `qasper:<arxivId>` |
| `vectorizationStatus` | `COMPLETED` for structured benchmark imports |

For PaperLoom-backed runs, model papers and chunks in MySQL with eval-scoped markers and org tags,
then index into the normal PaperLoom retrieval indexes. A dedicated Elasticsearch eval alias remains
an optional isolation step for larger full-corpus runs.

## Harness Baselines

`current-evidence-ledger` is the product RAG harness: router, adaptive hybrid retrieval, evidence
ledger, verifier, and answer rendering.

`keyword-only-baseline` is now runnable for LitSearch retrieval scoring. It is an offline
keyword/BM25-style baseline that reads LitSearch gold queries plus a LitSearch corpus snapshot,
writes `retrievedCorpusIds`, then records a normal `scorecard.json`. It is the floor to beat before
spending time on embedding or hybrid retrieval variants.

`facet-paper-baseline` is a stronger offline LitSearch baseline that rewards query-facet coverage in
paper title and abstract while capping repeated full-text term matches. On LitSearch Full it
improves Recall@20 from the keyword floor's 42.9% to 67.4%, which is strong evidence that broad
literature-search questions need paper-level metadata retrieval before chunk/page evidence
selection.
Production now has the first search/index boundary for this route: vectorization writes one
`paper_search` metadata document per paper with title, abstract, authors, venue/year, filename, DOI,
arXiv id, access fields, and combined `searchText`. `LITERATURE_SEARCH` now uses `paper_search` as
first-stage retrieval, then scopes normal chunk retrieval to those paper ids for citeable evidence.
It now has service-backed Product and QASPER page-window scorecards plus offline LitSearch Full
floors, but it still needs service-backed LitSearch quality scorecards before the route can be called
broadly validated. The QASPER
service-backed scores also show that live locator evidence selection still lags the offline
paragraph-window method, even when first-stage retrieval is bypassed for a trusted scoped paper.
The newest scoped-paper run records `candidateEvidenceHitRate` and per-case `candidateEvidenceHit`
diagnostics so future QASPER failures can be split into "evidence not present in candidates" versus
"evidence present but missed by selected windows".
`ServiceBackedLitSearchBenchmarkRunner` is the test-scoped bridge for that validation: it loads
LitSearch gold query cases, calls production `PaperRetrievalService`, normalizes imported
`litsearch:<corpusid>` paper ids back to `retrievedCorpusIds`, and reuses the standard LitSearch
scorer/cheatsheet artifacts. A real run still requires the LitSearch sample/full rows to be imported
into MySQL and indexed into Elasticsearch.
`ServiceBackedLitSearchBenchmarkCli` wraps that runner in a Spring web-disabled command so the
production route can be scored directly after a sample import. For clean benchmark runs, pass
`--scope-imported-only true` so the CLI reads imported eval paper ids from `Paper.isEval`,
`sourceDataset`, and `evalSplit`, then scopes production retrieval to those papers. If the matching
imported paper set is empty, the CLI stops instead of running an unscoped benchmark by accident.
`LitSearchPaperLoomImportCli` is the matching import bridge for samples: it reads LitSearch corpus
JSONL, creates eval-scoped PaperLoom paper/chunk rows, writes `paper_search` metadata docs, and
writes chunk docs with eval placeholder vectors so keyword fallback and metadata search can run
without OCR.

`semantic-only-baseline` is still planned.

## Product Rescue Smoke

This benchmark is a product-rescue smoke suite for the paper RAG chat path. It is not a broad academic QA benchmark yet. Its job is to catch the failures that make the product feel unreliable:

- false negatives: the paper contains usable evidence, but chat refuses to answer
- bad evidence: citation points to page numbers, keyword lists, headers, footers, or parser fragments
- route failures: system/non-paper questions enter RAG
- scope failures: manual/reference source questions cite outside the intended paper or citation
- citation failures: rendered `[n]` has no matching `referenceMappings`

## Dataset

Seed cases live in:

```text
eval/rag/product-rescue-smoke.jsonl
```

External benchmark source:

```text
eval/rag/qasper/
eval/rag/litsearch/
```

QASPER v0.3 is downloaded locally under `eval/rag/qasper/raw/` and documented in
`eval/rag/qasper/README.md`. Keep the raw files out of git; use them to generate focused
PaperLoom benchmark cases after the product smoke suite is stable.

LitSearch is documented in `eval/rag/litsearch/README.md`. Keep its raw corpus out of git because
the public corpus configs are large.

Each JSONL row describes one user-facing behavior:

- `id`: stable case id
- `query`: user message
- `taskType`: human-readable task category
- `scopeMode`: `AUTO_SOURCE`, `MANUAL_SOURCE`, or `REFERENCE_SOURCE`
- `scope`: expected papers when the case is scoped
- `expectedRoute`: expected backend route
- `requiredAnswerRegex`: answer text that must appear
- `requiredEvidenceRegex`: source evidence text that must appear in cited references
- `forbiddenAnswerRegex`: refusal or hallucination patterns that must not appear
- `forbiddenEvidenceRegex`: unusable evidence patterns that must not be cited
- `expectedPaperIds`: allowed paper ids for scoped cases
- `requiresCitation`: whether answer must render `[n]` with reference mappings

## Failure Classes

- `INTENT_ROUTE`: wrong route, usually non-paper/system question entering RAG or library search not being recognized
- `FALSE_NEGATIVE`: answer refuses or misses required evidence
- `BAD_EVIDENCE`: citation points to unusable evidence
- `SCOPE_CONTROL`: answer cites outside scoped paper/reference
- `CITATION_MAPPING`: answer text and `referenceMappings` disagree
- `ANSWER_QUALITY`: answer misses required user-facing content

## Run

Gate runner:

```bash
scripts/paperloom-rag-gates.sh product-smoke
scripts/paperloom-rag-gates.sh all-light
scripts/paperloom-rag-gates.sh litsearch-full-summary
```

Use `all-light` for daily development. It runs unit and dataset gates only and does not create a
benchmark run artifact. Use `product-smoke`, `qasper-dev-200`, and `pdf-parser-smoke` when the
required runtime/database preconditions are available; those modes print the expected `runDir` and
check the resulting scorecard. `pdf-parser-smoke` requires a real uploaded/parsed PDF row and parser
artifacts in the active database. `litsearch-full-summary` summarizes existing full import and
benchmark state only; it is not a fresh LitSearch Full run unless the full benchmark command is
explicitly invoked by the LitSearch pipeline. Do not report a partial sample as `litsearch-full`.

Preview commands without running them:

```bash
scripts/paperloom-rag-gates.sh --dry-run all-light
```

Targeted smoke:

```bash
mvn -q -Dtest=RagBenchmarkEvaluatorTest,RagBenchmarkDatasetTest,RagBenchmarkReportWriterTest,RagScorecardTest,RagEvalRunWriterTest,RagBenchmarkRegistryTest,RagCheatsheetWriterTest,PaperEvidenceHitScorerTest,PaperPageLocatorBenchmarkCliTest test
```

Product-rescue backend regression set:

```bash
mvn -q -Dtest=EvidenceQualityTest,PaperQueryPlannerTest,PaperRetrievalServiceTest,PaperAnswerServiceTest,RagBenchmarkEvaluatorTest,RagBenchmarkDatasetTest,RagBenchmarkReportWriterTest test
```

Service-backed scoped paper-QA slice:

```bash
java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" \
  com.yizhaoqi.smartpai.eval.ServiceBackedPageWindowBenchmarkCli \
  --cases eval/rag/product-rescue-paper-qa.jsonl \
  --harness-id service-backed-page-window \
  --dataset-id product-rescue-paper-qa \
  --query-planner scientific-qa \
  --window-radius 1 \
  --top-k 3
```

When running this Spring-backed CLI from a git worktree that does not contain the repo-root `.env`,
pass a `-Dspring.config.location=...` system property before `-cp` so the command uses the same
local MySQL/Elasticsearch credentials as the backend instead of test-resource H2 defaults.

## Current Priority Cases

The seed cases reflect recent product failures and expanded scoped paper-QA coverage:

- `grep_high_noise_zh`: Chinese high-noise question should recover English `increasing noise` evidence.
- `grep_related_concepts_zh`: concept-mining should not collapse into a generic refusal.
- `grep_summary_should_not_use_keywords_only`: paper summary must not cite the Keywords line as primary evidence.
- `grep_retrieval_strategies_zh`: method questions should recover lexical, semantic, and hybrid retrieval evidence.
- `grep_method_dataset_zh`: method/dataset questions should recover the LongMemEval setup.
- `grep_experiment1_table_zh`: table QA should recover Table 1 evidence.
- `grep_limitations_zh`: limitation questions should recover the explicit `do not claim` caveat.
- `grep_conclusion_zh`: conclusion questions should recover the retrieval mechanics / harness / delivery-path summary.
- `grep_reference_context_zh`: reference-context questions should recover the cited RAG/tool-calling background paragraph.
- `grep_multi_paper_ambiguity_zh`: multi-paper scope should still recover the intended product paper evidence.
- `non_paper_session_id`: session/system questions should clarify, not enter paper RAG.
- `paper_inventory_single_library`: “what papers do I have?” should list accessible library papers, not say the system has no information.
- `reference_followup_requires_same_reference`: `[1]` follow-up must stay tied to the referenced evidence.

## Expansion Rules

When adding papers or datasets, add cases before tuning retrieval:

1. Include at least one scoped single-paper QA case.
2. Include one auto-source discovery case.
3. Include one source/reference follow-up case.
4. Include at least one forbidden evidence pattern if the parser can produce junk for that paper.
5. Prefer regex against evidence text over answer style. We are measuring grounded behavior first, prose second.
