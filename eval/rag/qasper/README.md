# QASPER Benchmark Source

QASPER is the selected open-source academic benchmark for PaperLoom's paper-RAG evaluation path.
It fits this project better than generic RAG benchmarks because it is built around research papers,
information-seeking questions, answerability, and paragraph/table/figure evidence.

## Downloaded Files

Local raw files are kept under `eval/rag/qasper/raw/` and ignored by git.

| File | Source | Size | SHA-256 |
|------|--------|------|---------|
| `qasper-train-dev-v0.3.tgz` | `https://qasper-dataset.s3.us-west-2.amazonaws.com/qasper-train-dev-v0.3.tgz` | 11M | `a28fdf966db827bcee3d873107d6b6669864fb7ca8fbf73a192f5e39191bdb5a` |
| `qasper-test-and-evaluator-v0.3.tgz` | `https://qasper-dataset.s3.us-west-2.amazonaws.com/qasper-test-and-evaluator-v0.3.tgz` | 3.7M | `72a52a41193e2838b8074f80ac074b94f956b84886c36a61c58a7df4171bdd72` |

The extracted files are:

- `qasper-train-v0.3.json`
- `qasper-dev-v0.3.json`
- `qasper-test-v0.3.json`
- `qasper_evaluator.py`
- upstream README files

## Dataset Shape

The QASPER JSON files are keyed by arXiv id. Each paper contains:

- `title`
- `abstract`
- `full_text`
- `figures_and_tables`
- `qas`

Observed local split sizes:

| Split | Papers | QA Pairs | Answers | Answers With Evidence |
|-------|--------|----------|---------|-----------------------|
| train | 888 | 2593 | 2675 | 2308 |
| dev | 281 | 1005 | 1764 | 1552 |
| test | 416 | 1451 | 3554 | 3110 |

## PaperLoom Use

Use QASPER as the external benchmark layer above `eval/rag/product-rescue-smoke.jsonl`.
The smoke suite catches product regressions for scoped source behavior and citation mapping.
QASPER should test broader paper-RAG behavior:

- retrieve gold evidence paragraphs from full-text papers
- answer research-paper questions with citations
- distinguish answerable vs unanswerable cases
- measure evidence recall before generation quality

The converter and service import bridge now exist. Keep test split sealed until the local method is
stable; use the dev split and generated service cases for iteration.

Do not run OCR for normal QASPER eval. QASPER already provides structured `abstract`, `full_text`,
question, answer, and evidence fields. Import the structured text into eval-scoped PaperLoom papers
and chunks only when measuring the live service path. Use OCR only in a separate PDF-ingestion
benchmark where the goal is to measure parsing/OCR quality.

Suggested PaperLoom import mapping for service-backed eval:

| PaperLoom field | QASPER value |
|---|---|
| `paperId` | `qasper:<arxivId>` |
| `paperTitle` | `title` |
| `originalFilename` | `qasper:<arxivId>.json` |
| chunk text | abstract and `full_text` paragraphs |
| `isEval` | `true` |
| `sourceDataset` | `qasper` |
| `externalCorpusId` | arXiv id |
| `evalSplit` | `dev` or `test` |
| `orgTag` | `eval-qasper` |

The service-backed import bridge is:

```text
src/test/java/io/github/chzarles/paperloom/eval/QasperPaperLoomImporter.java
src/test/java/io/github/chzarles/paperloom/eval/QasperPaperLoomImportCli.java
```

It reads generated paragraph chunks, imports `paperId=qasper:<arxivId>` into PaperLoom MySQL and
Elasticsearch, and writes service cases whose scoped paper ids match the imported ids.

## Paragraph-Window Prototype

QASPER does not provide PDF page numbers. For offline locator experiments, use structured-text
paragraph windows instead of pretending these are real PDF pages:

```text
QASPER paper -> abstract/full_text paragraphs -> PaperPageChunk JSONL
pageNumber   -> paragraph ordinal inside the paper
goldPageKeys -> paragraph ordinals containing required evidence
```

Generated local files stay ignored:

| File | Rows | SHA-256 |
|---|---:|---|
| `generated/qasper-dev-50-rag-cases.jsonl` | 50 | `fe2f48228733b446296320a0342733935086f7ba4f442376faafb7056c550212` |
| `generated/qasper-dev-50-page-cases.jsonl` | 44 | `78600bf5b430aa6041a4ce7c0dc5f1e8bc77694475e14fc68c3a78c803c4cf71` |
| `generated/qasper-dev-50-paragraph-chunks.jsonl` | 896 | `714184c37a653cc547a4c82c558cf2ff113b5ba0c7d2922b0824964b5a14d9a5` |
| `generated/qasper-dev-200-rag-cases.jsonl` | 200 | `02a91f02eee8d5ec7045ef40c0ee1ebf47d8ff6e918fd245233d4ac647c142f1` |
| `generated/qasper-dev-200-page-cases.jsonl` | 156 | `8636e2c274bf6fd1cf440b9db3377e274d6acf6089343e54b9f4313a05b8d4e4` |
| `generated/qasper-dev-200-paragraph-chunks.jsonl` | 3418 | `ff5a19e2d746ab8a668a17fd435f5531e7f87687e77d3d7895865a8c38af5246` |

Current paragraph-window smoke runs use `--window-radius 1` and `--top-k 3`:

| Run | Harness | Cases | ChunkEv@3 | WindowEv@1 | WindowEv@3 | Page@3 |
|---|---|---:|---:|---:|---:|---:|
| `2026-06-24T011000Z-page-index-offline-qasper-dev-50-page-window` | page-index-offline | 44 | 25.0% | 13.6% | 29.5% | 22.7% |
| `2026-06-24T011100Z-page-index-planned-qasper-dev-50-page-window` | page-index-planned | 44 | 25.0% | 18.2% | 27.3% | 20.5% |
| `2026-06-24T021000Z-page-index-scientific-qa-qasper-dev-50-page-window` | page-index-scientific-qa | 44 | 25.0% | 38.6% | 56.8% | 40.9% |
| `2026-06-24T023000Z-page-index-offline-qasper-dev-200-page-window` | page-index-offline | 156 | 34.6% | 21.2% | 43.6% | 33.3% |
| `2026-06-24T023100Z-page-index-planned-qasper-dev-200-page-window` | page-index-planned | 156 | 34.6% | 23.7% | 43.6% | 31.4% |
| `2026-06-24T023200Z-page-index-scientific-qa-qasper-dev-200-page-window` | page-index-scientific-qa | 156 | 34.6% | 28.8% | 54.5% | 37.8% |

Interpretation: paragraph-window inspection gives a small top-3 evidence-hit lift over the
lightweight chunk-first baseline. The current product-oriented locator planner improves top-1
window evidence hit but slightly hurts top-3 on this QASPER slice. The scientific-QA planner is a
better fit: it expands questions with evidence-location facets such as baseline/comparison,
dataset/corpus/language pairs, models/algorithms, evaluation/results, and annotation/crowdsourcing.
On the Dev 50 smoke slice it raises `WindowEv@3` to 56.8%. On the larger Dev 200 page-window slice it
still leads with `WindowEv@3=54.5%` versus 43.6% for raw/page-locator, so the next step is to test the
same locator idea through the live PaperLoom retrieval service path.

Current service-backed run:

| Run | Harness | Cases | Pass | WindowEv@1 | WindowEv@K | CandidateEv | ScopeLeak |
|---|---|---:|---:|---:|---:|---:|---:|
| `2026-06-24T125506Z-service-backed-page-window-qasper-dev-200` | `service-backed-page-window` | 200 | 42.0% | 22.5% | 42.0% | - | 0.0% |
| `2026-06-24T162100Z-service-backed-scoped-page-window-qasper-dev-200` | `service-backed-scoped-page-window` | 200 | 42.5% | 22.5% | 42.5% | 78.0% | 0.0% |
| `2026-06-24T162300Z-service-backed-scoped-diverse-window-qasper-dev-200` | `service-backed-scoped-diverse-window` | 200 | 45.5% | 22.5% | 45.5% | 78.0% | 0.0% |
| `2026-06-24T171100Z-service-backed-scoped-diverse-window-k5-qasper-dev-200` | `service-backed-scoped-diverse-window-k5` | 200 | 53.0% | 22.5% | 53.0% | 78.0% | 0.0% |
| `2026-06-24T173100Z-service-backed-scoped-diverse-window-k7-qasper-dev-200` | `service-backed-scoped-diverse-window-k7` | 200 | 61.0% | 22.5% | 61.0% | 78.0% | 0.0% |
| `2026-06-24T164100Z-service-backed-scoped-center-diverse-window-qasper-dev-200` | center-diverse window probe | 200 | 44.5% | 22.5% | 44.5% | 78.0% | 0.0% |
| `2026-06-24T134155Z-service-backed-scoped-page-window-qasper-dev-200-target-rerank` | `service-backed-scoped-page-window` + target-term boost probe | 200 | 41.5% | 22.0% | 41.5% | 78.0% | 0.0% |
| `2026-06-24T144100Z-service-backed-scoped-page-window-qasper-dev-200-chunk-window` | `service-backed-scoped-page-window` + chunk-aware page rerank probe | 200 | 37.5% | 18.0% | 37.5% | 78.0% | 0.0% |

Interpretation: the structured-text import and service path are working and scoped correctly, but
the live retrieval bridge trails the offline scientific-QA paragraph-window score. During the
first-stage service run, local embedding rate limits forced text-only fallback for many queries.
The scoped-paper variant removes first-stage paper-candidate misses by reading all chunks from the
trusted scoped paper, but it improves only one net case. Its `CandidateEv=78.0%` diagnostic means
156/200 cases have gold evidence somewhere in the scoped-paper candidate chunks, while only 85/200
are recovered by the selected page windows. The run contains 71 cases where `candidateEvidenceHit`
is true but the final window evidence is missing. The next QASPER method work should focus on
locator query planning, page/window ranking, and evidence selection, then rerun under stable
embedding conditions.

The target-term boost probe was a negative result: it kept the same `CandidateEv=78.0%` but dropped
the final window hit/pass rate to 83/200 and increased candidate-hit/window-miss cases to 73. The
boost is now available only through the explicit `scientific-qa-targets` / `scientific-qa-target-rerank`
planner variant. The chunk-aware page rerank probe was also negative: it kept
`CandidateEv=78.0%` but dropped the final pass rate to 75/200, with 18 gained cases and 28 lost
cases compared with the stable default. It is available only through
`scientific-qa-chunk-window` / `scientific-qa-chunk-rerank`. Treat default `scientific-qa` as the
stable service-backed QASPER row.

The diverse-window selector is the first positive service-backed selector probe. It uses the same
scientific-QA page scores, but greedily skips center pages whose inspected windows would overlap a
previous selected window, then fills any remaining budget from the ranked list. On QASPER Dev 200 it
raises the scoped-paper run from 85/200 to 91/200, with 11 gained cases and 5 lost cases versus the
stable default. `CandidateEv` stays at 78.0%, and candidate-hit/window-miss cases drop from 71 to
65. This is still below the offline paragraph-window score, so keep it as an eval harness until the
selector is validated on larger Product/QASPER slices.

The center-diverse follow-up only avoids adjacent center pages and allows page windows to overlap at
their edges. It scores 89/200: better than default, but below strict non-overlap. Against strict
diverse it gains 4 cases and loses 6, so the stricter non-overlap selector remains the leading
variant.

The high-recall K5 run keeps strict non-overlap but inspects five windows instead of three. It
scores 106/200 (`WindowEv@5=53.0%`), gains 15 cases over K3, and loses no K3 hits. Candidate-hit/
window-miss cases fall to 50. Average inspected chunks rise from 8.90 to 14.77, so this should be
treated as a high-recall budget tier rather than a free default.

The deep-recall K7 run keeps the same strict non-overlap policy and inspects seven windows. It
scores 122/200 (`WindowEv@7=61.0%`), gains 16 cases over K5, and loses no K5 hits. Candidate-hit/
window-miss cases fall to 34. Average inspected chunks rise to 20.13, so use this for audit or
high-stakes review budgets rather than normal interactive turns.

Rebuild commands:

```bash
java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" \
  io.github.chzarles.paperloom.eval.QasperPageWindowDatasetWriter \
  --qasper-json eval/rag/qasper/raw/extracted/qasper-dev-v0.3.json \
  --rag-cases-output eval/rag/qasper/generated/qasper-dev-50-rag-cases.jsonl \
  --chunks-output eval/rag/qasper/generated/qasper-dev-50-paragraph-chunks.jsonl \
  --page-cases-output eval/rag/qasper/generated/qasper-dev-50-page-cases.jsonl \
  --max-cases 50

java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" \
  io.github.chzarles.paperloom.eval.PaperPageLocatorBenchmarkCli \
  --cases eval/rag/qasper/generated/qasper-dev-50-page-cases.jsonl \
  --rag-cases eval/rag/qasper/generated/qasper-dev-50-rag-cases.jsonl \
  --chunks eval/rag/qasper/generated/qasper-dev-50-paragraph-chunks.jsonl \
  --retrieved eval/rag/qasper/generated/qasper-dev-50-page-retrieved-offline-window.jsonl \
  --runs-root eval/rag/qasper/generated/runs \
  --registry eval/rag/qasper/generated/page-window-harnesses.yaml \
  --cheatsheet eval/rag/qasper/generated/PAGE_WINDOW_CHEATSHEET.md \
  --harness-id page-index-offline \
  --dataset-id qasper-dev-50-page-window \
  --query-planner none \
  --window-radius 1 \
  --top-k 3

java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" \
  io.github.chzarles.paperloom.eval.PaperPageLocatorBenchmarkCli \
  --cases eval/rag/qasper/generated/qasper-dev-50-page-cases.jsonl \
  --rag-cases eval/rag/qasper/generated/qasper-dev-50-rag-cases.jsonl \
  --chunks eval/rag/qasper/generated/qasper-dev-50-paragraph-chunks.jsonl \
  --retrieved eval/rag/qasper/generated/qasper-dev-50-page-retrieved-scientific-qa-window.jsonl \
  --runs-root eval/rag/qasper/generated/runs \
  --registry eval/rag/qasper/generated/page-window-harnesses.yaml \
  --cheatsheet eval/rag/qasper/generated/PAGE_WINDOW_CHEATSHEET.md \
  --harness-id page-index-scientific-qa \
  --dataset-id qasper-dev-50-page-window \
  --query-planner scientific-qa \
  --window-radius 1 \
  --top-k 3

java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" \
  io.github.chzarles.paperloom.eval.QasperPageWindowDatasetWriter \
  --qasper-json eval/rag/qasper/raw/extracted/qasper-dev-v0.3.json \
  --rag-cases-output eval/rag/qasper/generated/qasper-dev-200-rag-cases.jsonl \
  --chunks-output eval/rag/qasper/generated/qasper-dev-200-paragraph-chunks.jsonl \
  --page-cases-output eval/rag/qasper/generated/qasper-dev-200-page-cases.jsonl \
  --max-cases 200

java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" \
  io.github.chzarles.paperloom.eval.PaperPageLocatorBenchmarkCli \
  --cases eval/rag/qasper/generated/qasper-dev-200-page-cases.jsonl \
  --rag-cases eval/rag/qasper/generated/qasper-dev-200-rag-cases.jsonl \
  --chunks eval/rag/qasper/generated/qasper-dev-200-paragraph-chunks.jsonl \
  --retrieved eval/rag/qasper/generated/qasper-dev-200-page-retrieved-scientific-qa-window.jsonl \
  --runs-root eval/rag/qasper/generated/runs \
  --registry eval/rag/qasper/generated/page-window-harnesses.yaml \
  --cheatsheet eval/rag/qasper/generated/PAGE_WINDOW_CHEATSHEET.md \
  --harness-id page-index-scientific-qa \
  --dataset-id qasper-dev-200-page-window \
  --query-planner scientific-qa \
  --window-radius 1 \
  --top-k 3
```

Service-backed import and run:

```bash
java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" \
  io.github.chzarles.paperloom.eval.QasperPaperLoomImportCli \
  --chunks eval/rag/qasper/generated/qasper-dev-200-paragraph-chunks.jsonl \
  --rag-cases eval/rag/qasper/generated/qasper-dev-200-rag-cases.jsonl \
  --cases-output eval/rag/qasper/generated/qasper-dev-200-service-cases.jsonl \
  --user-id 1 \
  --org-tag eval-qasper \
  --public true \
  --eval-split dev

java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" \
  io.github.chzarles.paperloom.eval.ServiceBackedPageWindowBenchmarkCli \
  --cases eval/rag/qasper/generated/qasper-dev-200-service-cases.jsonl \
  --harness-id service-backed-page-window \
  --dataset-id qasper-dev-200 \
  --user-id 1 \
  --query-planner scientific-qa \
  --window-radius 1 \
  --top-k 3

java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" \
  io.github.chzarles.paperloom.eval.ServiceBackedPageWindowBenchmarkCli \
  --cases eval/rag/qasper/generated/qasper-dev-200-service-cases.jsonl \
  --harness-id service-backed-scoped-page-window \
  --dataset-id qasper-dev-200 \
  --user-id 1 \
  --query-planner scientific-qa \
  --candidate-source scoped-paper \
  --window-radius 1 \
  --top-k 3
```

Use `--query-planner scientific-qa-targets` only for the target-term rerank probe. Keep that run
separate from default scorecards unless it beats the stable `scientific-qa` row.
Use `--query-planner scientific-qa-chunk-window` only for the chunk-aware page rerank probe; keep it
separate from default scorecards as well.
Use `--harness-id service-backed-scoped-diverse-window --query-planner scientific-qa-diverse-windows`
for the non-overlapping diverse-window selector.
Use `--harness-id service-backed-scoped-diverse-window-k5 --query-planner scientific-qa-diverse-windows --top-k 5`
for the high-recall K5 budget.
Use `--harness-id service-backed-scoped-diverse-window-k7 --query-planner scientific-qa-diverse-windows --top-k 7`
for the deep-recall K7 budget.
Use `--query-planner scientific-qa-center-diverse-windows` only for the center-diverse probe.
