# LitSearch Benchmark Source

LitSearch is the professional literature-search benchmark layer for PaperLoom retrieval.
Use it to measure whether a RAG harness can find the right papers across a corpus, before
answer generation style is considered.

## Source

- Dataset: `https://huggingface.co/datasets/princeton-nlp/LitSearch`
- Code: `https://github.com/princeton-nlp/LitSearch`
- Paper: `https://arxiv.org/abs/2407.18940`

## Dataset Shape

Observed from the HuggingFace dataset server on 2026-06-23:

| Config | Split | Rows | Role |
|---|---|---:|---|
| `query` | `full` | 597 | Natural-language literature-search queries and gold `corpusids`. |
| `corpus_clean` | `full` | 64,183 | Clean title, abstract, citations, and full-paper text. |
| `corpus_s2orc` | `full` | 64,183 | S2ORC-style structured paper content and external ids. |

The full corpus is large, so keep raw and generated files under ignored local directories:

```text
eval/rag/litsearch/raw/
eval/rag/litsearch/generated/
```

## Local Query Snapshot

The lightweight query split has been downloaded locally and converted to PaperLoom retrieval cases.
Both files are ignored by git.

| File | Rows | Size | SHA-256 |
|---|---:|---:|---|
| `raw/litsearch-query-full.json` | 597 | 228K | `2c5eae64bd94b0e6485bfd750a0d1f0d6bd84128ec94498e566f737413bac686` |
| `generated/litsearch-full-query.jsonl` | 597 | 180K | `33008cba900a7f88e35d5ef09ac88e52b3ec0bc261edf8bcc6d433dac33c4aeb` |
| `raw/litsearch-corpus-clean-page-000.json` | 10 | 649K | `6ea0db30ca542e5e11bc4b3d600cfde4beab0113a70aaa4168546d6747d29c19` |
| `generated/litsearch-corpus-clean-sample-10.jsonl` | 10 | 648K | `a5b9d03b91e12c42f2c13883cc4bcea931484324320e64a106f781d9c81d7c87` |
| `raw/litsearch-corpus-clean-page-00010.json` | 10 | 748K | `6a95dddefdc285c54b34eb27a4d435616b2ee0e1030e2071c5b5ccdc4928b949` |
| `generated/litsearch-corpus-clean-sample-20.jsonl` | 20 | 1.4M | `bf45ca1d81266098b49f912560be0c43b5864d898f91624ac789247323871b1c` |
| `raw/litsearch-corpus-clean-page-00000.json` | 100 | 7.5M | `3935e31276d0eae42b80ede9e2ef0f02521e3323512f296ad3985c758ed4def9` |
| `raw/litsearch-corpus-clean-page-00100.json` | 100 | 6.9M | `22a5588dffc72c549ec1e8bb4cf32c100d7c59b7f8334e75ae5f8e5b98e87f76` |
| `raw/litsearch-corpus-clean-page-00200.json` | 100 | 6.6M | `1270d7dad2f2f6af383812cfaecfcb12f4680503ae91592315c0701d75613920` |
| `generated/litsearch-corpus-clean-sample-300.jsonl` | 300 | 21M | `b6c2d463a5808681aae89361ec0315556019f35f503beb121feac250de0e77b2` |
| `generated/litsearch-corpus-clean-sample-2300.jsonl` | 2,300 | 162M | `b2eb88cf41dc0a0d846fd6cce3b949a6ac985a7cee2d76ce1aa7e8195b7b8082` |
| `generated/litsearch-corpus-clean-sample-8000.jsonl` | 8,000 | 446M | `49c4b94273100d469e5b66015de2dbe01fbbcd4876be19b9ecf77533159cdb64` |
| `generated/litsearch-corpus-clean-sample-11000.jsonl` | 11,000 | 559M | `232448d022af60ee869ca1a791f590983dea7fe87fe42cba2938bad5cbbd3808` |
| `generated/litsearch-corpus-clean-sample-16000.jsonl` | 16,000 | 739M | `b80406f87d2104ae0aba27f223d5a203d991a062e160c93ae033e2df1908f018` |

Reproduce the download with paged requests to the HuggingFace dataset server:

```bash
mkdir -p eval/rag/litsearch/raw eval/rag/litsearch/generated
for offset in 0 100 200 300 400 500; do
  page=$(printf 'eval/rag/litsearch/raw/litsearch-query-page-%03d.json' "$offset")
  curl --retry 3 --retry-delay 2 -fL "https://datasets-server.huggingface.co/rows?dataset=princeton-nlp%2FLitSearch&config=query&split=full&offset=${offset}&length=100" -o "$page"
done
```

Download and merge a small real corpus sample:

```bash
java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" \
  io.github.chzarles.paperloom.eval.LitSearchDatasetServerDownloader \
  --config corpus_clean \
  --output-dir eval/rag/litsearch/raw \
  --filename-format 'litsearch-corpus-clean-page-%05d.json' \
  --start-offset 0 \
  --total-rows 20 \
  --page-size 10 \
  --retries 5 \
  --retry-delay-millis 1500

java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" \
  io.github.chzarles.paperloom.eval.LitSearchCorpusJsonlWriter \
  --output eval/rag/litsearch/generated/litsearch-corpus-clean-sample-20.jsonl \
  eval/rag/litsearch/raw/litsearch-corpus-clean-page-00000.json \
  eval/rag/litsearch/raw/litsearch-corpus-clean-page-00010.json
```

For the full `corpus_clean` split, use the same downloader with `offset=0,100,...,64100`.
It skips non-empty existing pages, supports retry, and uses `HTTPS_PROXY`/`HTTP_PROXY` from the
environment when present. Existing pages are skipped only when their `rows` length matches the
expected page size and every `row_idx` matches the expected offset. That means a small sample page or
an offset-shifted/corrupt page will be redownloaded instead of silently contaminating a full-corpus
run:

```bash
java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" \
  io.github.chzarles.paperloom.eval.LitSearchDatasetServerDownloader \
  --config corpus_clean \
  --output-dir eval/rag/litsearch/raw \
  --filename-format 'litsearch-corpus-clean-page-%05d.json' \
  --start-offset 0 \
  --total-rows 64183 \
  --page-size 100 \
  --retries 5 \
  --retry-delay-millis 10000 \
  --page-delay-millis 4000

java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" \
  io.github.chzarles.paperloom.eval.LitSearchCorpusJsonlWriter \
  --output eval/rag/litsearch/generated/litsearch-corpus-clean-full.jsonl \
  $(for offset in $(seq 0 100 64100); do printf 'eval/rag/litsearch/raw/litsearch-corpus-clean-page-%05d.json ' "$offset"; done)
```

The HuggingFace parquet metadata observed on 2026-06-23 reports `corpus_clean` as six parquet files
with about 1.26GB on disk. The paged JSON route is larger but needs no parquet dependency and can be
resumed by re-running missing page offsets.

If the dataset-server row API remains unavailable but direct HuggingFace file downloads work, use
the parquet manifest instead:

```bash
mkdir -p eval/rag/litsearch/raw/parquet
awk 'NR > 1 {print $5, "eval/rag/litsearch/raw/parquet/" $4}' \
  eval/rag/litsearch/parquet_manifest.tsv \
| while read -r url output; do
    curl -fL -C - "$url" -o "$output"
  done

python3 eval/rag/litsearch/parquet_to_jsonl.py \
  --output eval/rag/litsearch/generated/litsearch-corpus-clean-full.jsonl \
  eval/rag/litsearch/raw/parquet/full-0000*-of-00006.parquet
```

`parquet_to_jsonl.py` requires local `pyarrow`, but keeps parquet/Arrow dependencies out of the
Maven service classpath. Its JSONL output uses the same `LitSearchPaperDocument` shape consumed by
`LitSearchKeywordBaselineCli`, `LitSearchFacetPaperBaselineCli`, and the PaperLoom import bridge.

## Corpus Progress

Current local full-size corpus coverage:

| Source path | Rows | Status |
|---|---:|---|
| `generated/litsearch-full-query.jsonl` | 597 queries | complete. |
| `raw/litsearch-corpus-clean-page-00000..15900.json` | 16,000 papers | dataset-server JSON pages; kept as resumable/sample source. |
| `raw/parquet/full-00000..00005-of-00006.parquet` | 64,183 papers | complete via `hf-mirror.com` parquet fallback. |
| `generated/litsearch-corpus-clean-full.jsonl` | 64,183 papers | complete PaperLoom eval JSONL, 2.5G, SHA-256 `744e90d0edf51cf9149874bfc14e49ce264d38ce7590d91b0f7e55f31bae23fb`. |

2026-06-24T17:39Z: parquet fallback conversion wrote all 64,183 corpus rows:

```text
written=64183
64183 eval/rag/litsearch/generated/litsearch-corpus-clean-full.jsonl
```

The dataset-server JSON route still has only the first 16,000 rows locally; use the parquet-derived
JSONL for `litsearch-full` offline scoring. Keep sample-specific dataset ids for the 16k and smaller
JSON samples.

Verify local dataset-server raw-page coverage before merging or resuming the JSON route:

```bash
java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" \
  io.github.chzarles.paperloom.eval.LitSearchCorpusCoverageVerifier \
  --input-dir eval/rag/litsearch/raw \
  --filename-format 'litsearch-corpus-clean-page-%05d.json' \
  --start-offset 0 \
  --total-rows 64183 \
  --page-size 100
```

Current dataset-server JSON verifier output:

```text
complete=false
contiguousRows=16000
nextResumeOffset=16000
validPages=160
problem=missing page at offset 16000: eval/rag/litsearch/raw/litsearch-corpus-clean-page-16000.json
```

Full LitSearch runs over all 597 queries and all 64,183 corpus papers:

| Run | Candidate corpus | Recall@5 | Recall@20 | MRR |
|---|---:|---:|---:|---:|
| `2026-06-24T175200Z-keyword-only-baseline-litsearch-full` | 64,183 papers | 18.7% | 42.9% | 12.8% |
| `2026-06-24T174500Z-facet-paper-baseline-litsearch-full` | 64,183 papers | 48.4% | 67.4% | 40.1% |

The full retrieved files have 597 rows each:

```text
597 eval/rag/litsearch/generated/keyword-only-baseline-full-retrieved.jsonl
597 eval/rag/litsearch/generated/facet-paper-baseline-full-retrieved.jsonl
```

Earlier sanity runs over all 597 queries but only partial corpus samples produced:

| Run | Candidate corpus | Recall@5 | Recall@20 | MRR |
|---|---:|---:|---:|---:|
| `2026-06-23T220800Z-keyword-only-baseline-litsearch-sample-300` | 300 papers | 1.0% | 1.2% | 1.0% |
| `2026-06-23T221700Z-keyword-only-baseline-litsearch-sample-2300` | 2,300 papers | 8.0% | 9.5% | 5.7% |
| `2026-06-23T222800Z-keyword-only-baseline-litsearch-sample-8000` | 8,000 papers | 11.7% | 15.1% | 8.7% |
| `2026-06-23T143700Z-keyword-only-baseline-litsearch-sample-11000` | 11,000 papers | 13.6% | 17.4% | 9.6% |
| `2026-06-24T033000Z-keyword-only-baseline-litsearch-sample-16000` | 16,000 papers | 15.0% | 20.7% | 10.1% |
| `2026-06-24T151500Z-facet-paper-baseline-litsearch-sample-16000` | 16,000 papers | 22.2% | 26.9% | 18.6% |

These partial scores are not the professional LitSearch full score; they only verify that query
conversion, corpus merge, retrieval, scorecard writing, and ignored run artifacts work on real
downloaded corpus rows. The 16k score is backed by
`eval/rag/runs/2026-06-24T033000Z-keyword-only-baseline-litsearch-sample-16000/`.
The facet-paper 16k run is backed by
`eval/rag/runs/2026-06-24T151500Z-facet-paper-baseline-litsearch-sample-16000/`.
The scoring CLIs now guard the full benchmark id: `litsearch-full` requires the 597-query gold file
and either a 64,183-paper corpus JSONL for offline baselines or 64,183 imported eval papers for
service-backed scoped runs. Partial corpus runs must use sample-specific dataset ids.

## PaperLoom Use

LitSearch should be evaluated as a retrieval benchmark:

- Keep HuggingFace rows as the raw source of truth and convert them to normalized JSONL first.
- Index `corpus_clean` or `corpus_s2orc` papers into a dedicated eval paper set only when running
  the real PaperLoom retrieval service.
- Convert each `query` row into a retrieval case with gold `corpusids`.
- Score `recallAt5`, `recallAt20`, and optionally MRR.
- Do not tune on the final full run repeatedly; use a smaller dev slice for harness iteration.

Do not run OCR for LitSearch. The benchmark already provides title, abstract, citation ids, and
full-paper text. OCR belongs in a separate PDF-ingestion benchmark; LitSearch should isolate
literature-search retrieval quality.

Suggested PaperLoom import mapping for service-backed eval:

| PaperLoom field | LitSearch value |
|---|---|
| `paperId` | `litsearch:<corpusid>` |
| `paperTitle` | `title` |
| `originalFilename` | `litsearch:<corpusid>.json` |
| chunk text | title, abstract, and full-paper text chunks |
| `isEval` | `true` |
| `sourceDataset` | `litsearch` |
| `externalCorpusId` | `corpusid` |
| `evalSplit` | `full`, `dev`, `test`, or a sample name |
| `orgTag` | `eval-litsearch` |
| Elasticsearch indexes | `paper_search`, `paper_chunks` |

The test-scoped importer prepares those rows for the live PaperLoom services. It writes
`file_upload`, `paper_text_chunks`, `paper_search`, and `paper_chunks` entries using structured text
from LitSearch; it does not run OCR.

Compile the test utilities, then import a small sample first:

```bash
mvn -q -DskipTests test-compile

# Use the same Spring profile and service credentials as the local backend, for example
# SPRING_PROFILES_ACTIVE=dev when Elasticsearch is running over local HTTP.
# The app loads `.env` from the current working directory. If your `.env` lives in the repository
# root while you are working in a git worktree, run the Java command from that root with absolute
# classpath/dataset paths, or export the same variables explicitly.
# Prefer a dedicated eval database user id and private rows; the scoring CLI can then scope
# retrieval to imported benchmark paper ids.
java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" \
  io.github.chzarles.paperloom.eval.LitSearchPaperLoomImportCli \
  --corpus eval/rag/litsearch/generated/litsearch-corpus-clean-sample-20.jsonl \
  --user-id <eval-user-db-id> \
  --org-tag eval-litsearch \
  --public false \
  --limit 20 \
  --max-chunk-characters 1800 \
  --eval-split sample-20
```

The import CLI streams JSONL rows from disk. It does not load the full corpus into memory, does not
call OCR, and uses structured LitSearch text plus placeholder vectors for the eval import. For large
imports, use `--start-offset` with `--limit` so the job is restartable:

```bash
# From the repository root, so `.env` is loaded from the same place as the local backend.
cd /home/charles/PaperLoom

java -cp "/home/charles/PaperLoom/.worktrees/adaptive-source-set-rag/target/test-classes:/home/charles/PaperLoom/.worktrees/adaptive-source-set-rag/target/classes:$(cat /home/charles/PaperLoom/.worktrees/adaptive-source-set-rag/target/test-classpath.txt)" \
  io.github.chzarles.paperloom.eval.LitSearchPaperLoomImportCli \
  --corpus /home/charles/PaperLoom/.worktrees/adaptive-source-set-rag/eval/rag/litsearch/generated/litsearch-corpus-clean-full.jsonl \
  --user-id eval-litsearch-user \
  --org-tag eval-litsearch \
  --public true \
  --eval-split full \
  --start-offset 0 \
  --limit 1000 \
  --max-chunk-characters 1800 \
  --index-batch-size 500
```

Only use `--eval-split full` for this complete import path. Smoke and partial imports should keep
sample-specific split names such as `full-window-smoke`.

For the full import, prefer the unattended wrapper instead of manually advancing offsets:

```bash
cd /home/charles/PaperLoom
.worktrees/adaptive-source-set-rag/scripts/litsearch-full-pipeline.sh start --interval-seconds 120
```

It waits for any existing import, resumes from the current batch boundary, imports until all 64,183
papers are present, then starts the full service-backed LitSearch benchmark. Use the compact status
command to avoid reading long logs:

```bash
cd /home/charles/PaperLoom
.worktrees/adaptive-source-set-rag/scripts/litsearch-full-pipeline.sh summary
```

The wrapper may also be launched from the worktree; child Spring CLIs change back to
`/home/charles/PaperLoom` before startup so the repo-root `.env` is used for MySQL, Elasticsearch,
and model-provider encryption settings.

Current local import-tool validation:

| Date | Command shape | Result |
|---|---|---|
| 2026-06-25 | `--start-offset 1 --limit 2 --eval-split full-window-smoke` | imported 2 papers and 81 chunks into MySQL/Elasticsearch. |
| 2026-06-25 | `--start-offset 4 --limit 1 --eval-split full-window-smoke-quiet` | imported 1 paper and 52 chunks; Kafka admin and per-row SQL logging were disabled for the CLI. |
| 2026-06-25 | `--start-offset 0 --limit 1000 --eval-split full` | imported 1,000 formal full-corpus papers and 40,314 chunks. |
| 2026-06-25 | `--start-offset 1000 --limit 1000 --eval-split full` | imported 1,000 formal full-corpus papers and 40,850 chunks. |

Current formal full-import status is complete: `64183/64183` papers imported for `evalSplit=full`.
Any count below `64183/64183` is a partial import, not a valid `litsearch-full` service-backed
benchmark; the scoring guard rejects it until all 64,183 papers are present. Because the PaperLoom
`paperId` convention is `litsearch:<corpusid>`, formal full imports replace any matching sample or
service-slice rows for the same corpus id.

For service-backed scoring, keep the same `paperId` convention because
`ServiceBackedLitSearchBenchmarkRunner` normalizes `litsearch:<corpusid>` back to the benchmark
`corpusid` before writing `retrievedCorpusIds`.

After importing a sample or full eval corpus into PaperLoom, score the production retrieval route
through the Spring-backed CLI:

```bash
# Use the same Spring profile and service credentials as the local backend.
java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" \
  io.github.chzarles.paperloom.eval.ServiceBackedLitSearchBenchmarkCli \
  --gold eval/rag/litsearch/generated/litsearch-full-query.jsonl \
  --retrieved eval/rag/litsearch/generated/service-backed-litsearch-sample-20-retrieved.jsonl \
  --harness-id current-evidence-ledger \
  --dataset-id litsearch-sample-20 \
  --run-id 2026-06-24T171000Z-current-evidence-ledger-litsearch-sample-20 \
  --started-at 2026-06-24T17:10:00Z \
  --user-id <eval-user-db-id> \
  --top-k 20 \
  --scope-imported-only true \
  --source-dataset litsearch \
  --eval-split sample-20
```

Use `litsearch-full` only after all 64,183 corpus papers have been imported and indexed; sample
runs should keep sample-specific dataset ids. `--scope-imported-only true` reads `Paper` rows marked
with `isEval`, `sourceDataset`, and `evalSplit`, then passes those paper ids into production
retrieval so normal public libraries do not contaminate the score. If no imported papers match
those markers, the CLI fails fast instead of falling back to unscoped retrieval.

Latest service-backed full-corpus run:

| Run | Scope | Cases | Recall@5 | Recall@20 | MRR | Scope check |
|---|---|---:|---:|---:|---:|---|
| `20260627T043549Z-current-evidence-ledger-litsearch-full` | 64,183 imported papers | 597 | 54.1% | 64.5% | 45.0% | 597 non-empty rows, 0.0% scope leak |

Artifacts:

- `eval/rag/runs/20260627T043549Z-current-evidence-ledger-litsearch-full/`
- `eval/rag/litsearch/generated/service-backed-litsearch-full-20260627T043549Z-retrieved.jsonl`

Interpretation: this is the current production-service LitSearch Full quality signal. It validates
the `paper_search` -> scoped evidence service path over all 64,183 imported papers, with no empty
retrieved rows and no scope leak. It is close to the offline facet-paper full-corpus
`Recall@20=67.4%` floor and exceeds its `MRR=40.1%`. A prior run
`20260627T042031Z-current-evidence-ledger-litsearch-full` scored 0.0% because the full imported
paper scope was expanded into a 64k-clause bool query and Elasticsearch rejected it with
`maxClauseCount is set to 5242`; `HybridSearchService.paperScopeFilter` now uses a `terms` filter
for multi-paper scopes.

Previous service-backed candidate-slice run:

| Run | Scope | Cases | Recall@5 | Recall@20 | MRR | Scope check |
|---|---|---:|---:|---:|---:|---|
| `20260624T195908Z-current-evidence-ledger-litsearch-service-slice-k5-metadata-fallback` | 5,060 imported candidate papers | 597 | 57.6% | 58.4% | 47.1% | 597 non-empty rows, 0.0% scope leak |
| `2026-06-24T190836Z-current-evidence-ledger-litsearch-service-slice-k5` | 5,060 imported candidate papers | 597 | 57.4% | 57.8% | 47.0% | 597 non-empty rows, 0.0% scope leak |

Artifacts:

- `eval/rag/runs/20260624T195908Z-current-evidence-ledger-litsearch-service-slice-k5-metadata-fallback/`
- `eval/rag/litsearch/generated/service-backed-litsearch-service-slice-k5-metadata-fallback-20260624T195908Z-retrieved.jsonl`
- `eval/rag/runs/2026-06-24T190836Z-current-evidence-ledger-litsearch-service-slice-k5/`
- `eval/rag/litsearch/generated/service-backed-litsearch-service-slice-k5-retrieved.jsonl`

Interpretation: this was the first meaningful production-service LitSearch quality signal. The
candidate slice was built from all gold corpus ids plus top offline candidates and imported into
PaperLoom's MySQL/Elasticsearch eval state as `evalSplit=service-slice-k5`. It proves the
`paper_search` -> scoped evidence service path can return non-empty, permission-scoped candidates
for all 597 queries. The metadata-fallback run preserves `paper_search` candidates that do not have
scoped chunk evidence, gaining 4 Recall@20 cases and losing 0 versus the prior run. The gains were
all in `inline_nonacl`; average returned ids rose from 9.18 to 9.53, rows with 20 returned ids rose
from 171 to 188, and rows with fewer than 20 returned ids fell from 426 to 409. During this local
run 199 queries used text-only fallback after embedding rate-limit checks, so treat the score as a
production-path signal rather than a stable-embedding ceiling.

It is not `LitSearch Full`: the full benchmark has 64,183 candidate papers, while this slice has
5,060. Keep it as a faster regression slice, not as the primary full-corpus quality score.

Latest service-backed sample-20 smoke:

| Run | Scope | Cases | Recall@5 | Recall@20 | MRR | Scope check |
|---|---|---:|---:|---:|---:|---|
| `2026-06-24T121310Z-current-evidence-ledger-litsearch-sample-20-scope-fixed` | 20 imported papers | 597 | 0.0% | 0.0% | 0.0% | 20 unique retrieved ids, 0 out-of-scope ids |

Artifacts:

- `eval/rag/runs/2026-06-24T121310Z-current-evidence-ledger-litsearch-sample-20-scope-fixed/`
- `eval/rag/litsearch/generated/service-backed-litsearch-sample-20-scope-fixed-retrieved.jsonl`

Interpretation: this is an import/service/scope smoke, not a quality score. The 20-paper sample is
not expected to contain the gold corpus ids for the 597 LitSearch queries, so `Recall@20=0.0%` is
expected. This run confirms the production service can write scorecard artifacts without leaking
the local PaperLoom library into scoped LitSearch evaluation. During this local run the embedding
query rate limit was hit and retrieval fell back to the text branch; the KNN scope path is covered
by `HybridSearchServicePaperSearchTest.semanticKnnSearchCarriesPermissionAndPaperScopeFilters`.

## Retrieval Output Format

Any retrieval harness can enter the PaperLoom ledger by writing JSONL rows with retrieved corpus ids:

```json
{"caseId":"litsearch_inline_acl_0000","retrievedCorpusIds":["202719327","123"]}
```

Then score the output and refresh the generated cheatsheet:

```bash
java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" \
  io.github.chzarles.paperloom.eval.LitSearchRetrievalScoreCli \
  --gold eval/rag/litsearch/generated/litsearch-full-query.jsonl \
  --retrieved eval/rag/litsearch/generated/<harness>-retrieved.jsonl \
  --run-id 2026-06-23T220000Z-current-evidence-ledger-litsearch-full \
  --started-at 2026-06-23T22:00:00Z
```

Use a separate `--dataset-id` for mini/dev slices so the cheatsheet does not report a small run as
the full 597-query benchmark. `LitSearchRetrievalScoreCli` rejects `--dataset-id litsearch-full`
unless the gold file contains exactly 597 query cases. The keyword and facet baseline CLIs also
reject corpus filenames that look like `mini` or `sample` when `--dataset-id litsearch-full` is
used; pass a sample-specific dataset id such as `litsearch-sample-16000` instead.

## Keyword Baseline

The repo includes a small offline keyword baseline for LitSearch. It is deliberately simple:
weighted token overlap with title > abstract > full text. Use it as a reproducible floor, not as the
target RAG method.

```bash
java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" \
  io.github.chzarles.paperloom.eval.LitSearchKeywordBaselineCli \
  --gold eval/rag/litsearch/generated/litsearch-full-query.jsonl \
  --corpus eval/rag/litsearch/generated/litsearch-corpus-clean-full.jsonl \
  --retrieved eval/rag/litsearch/generated/keyword-only-baseline-full-retrieved.jsonl \
  --harness-id keyword-only-baseline \
  --dataset-id litsearch-full \
  --run-id 2026-06-24T175200Z-keyword-only-baseline-litsearch-full \
  --started-at 2026-06-24T17:52:00Z \
  --top-k 20
```

The `--corpus` file may be a HuggingFace dataset-server JSON page, a merged JSON file with a
top-level `rows` array, or the `LitSearchCorpusJsonlWriter` / parquet converter JSONL output. For
JSONL, the baseline streams documents and builds query-token-only profiles, so a full professional
score does not need to keep 2.5G of full text resident in memory.

## Facet Paper Baseline

`facet-paper-baseline` is a stronger offline LitSearch baseline. It still avoids LLM calls, but it
rewards broad query-token coverage in title and abstract, caps repeated full-text term matches, and
adds small title/abstract phrase bonuses. On LitSearch Full it improves the keyword floor from
`Recall@20=42.9%` to `Recall@20=67.4%` and from `MRR=12.8%` to `MRR=40.1%`.

```bash
java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" \
  io.github.chzarles.paperloom.eval.LitSearchFacetPaperBaselineCli \
  --gold eval/rag/litsearch/generated/litsearch-full-query.jsonl \
  --corpus eval/rag/litsearch/generated/litsearch-corpus-clean-full.jsonl \
  --retrieved eval/rag/litsearch/generated/facet-paper-baseline-full-retrieved.jsonl \
  --harness-id facet-paper-baseline \
  --dataset-id litsearch-full \
  --run-id 2026-06-24T174500Z-facet-paper-baseline-litsearch-full \
  --started-at 2026-06-24T17:45:00Z \
  --top-k 20
```

Interpretation: PaperLoom's literature-search path should include a paper-level retrieval stage over
title, abstract, and normalized metadata before chunk/page evidence selection. Chunk-level retrieval
alone is the wrong first unit for broad literature-search questions.

## Local Converter

The local converter accepts HuggingFace dataset-server JSON rows such as:

```text
src/test/resources/eval/litsearch-mini-query.json
src/test/resources/eval/litsearch-mini-corpus.json
```

It produces:

- `LitSearchBenchmarkCase` rows for query and gold-paper evaluation.
- `LitSearchPaperDocument` rows for corpus import preparation.
- retrieval metrics from retrieved corpus ids via `LitSearchRetrievalScorer`.
- standard `run.json`, `scorecard.json`, and concise cheatsheet rows via `LitSearchRetrievalScoreCli`.
- offline `keyword-only-baseline` and `facet-paper-baseline` retrieval runs. Their JSONL corpus path
  streams documents and keeps query-token-only profiles for full-corpus scoring.

The parquet fallback converter accepts HuggingFace parquet shards and writes the same
`LitSearchPaperDocument` JSONL shape:

```bash
python3 eval/rag/litsearch/parquet_to_jsonl.py \
  --max-papers 1000 \
  --output eval/rag/litsearch/generated/litsearch-corpus-clean-sample-1000-from-parquet.jsonl \
  eval/rag/litsearch/raw/parquet/full-00000-of-00006.parquet
```

Use `--max-papers` only for smoke samples. A professional `LitSearch Full` run still requires all
64,183 corpus rows and must keep `dataset-id=litsearch-full` only after the full corpus is present.
