# Lexical Qdrant Product Cutover Proposal

Date: 2026-07-18

Status: Implemented. The destructive data-plane cutover and retrieval gates are complete; the final
MiniMax-M3 acceptance record is maintained in the persistent run directory named below.

Related design: [Lexical-First Qdrant Retriever](lexical-first-qdrant-retriever-proposal-2026-07-17.md).

## Decision

Cut over in one controlled sequence:

```text
apply verified DDL migration
-> deploy and restart Java
-> rebuild the sparse-only Qdrant collection
-> verify schema, counts, lifecycle, and representative searches
-> run all MiniMax-M3 Golden Data
-> accept the cutover or fix forward
```

The cutover is fail-closed. Do not keep the old Hybrid collection as a request-level fallback, and do
not modify prompts, Golden Data, Anchors, Reading Models, PDFs, extracted text, or corpus mappings to
make the migration pass.

Only the target runtime remains. The old Hybrid collection, Elasticsearch container, Elasticsearch
volume, environment variables, and request paths are deleted.

## Scope

This cutover changes the Java/Qdrant retrieval data plane. The Python Harness tool contract is
unchanged, so the Harness only needs a restart if its own deployed code or environment changed.

Target collection:

```text
paperloom_reading_locations_bm25_v1
```

Target sparse vector:

```text
lexical_bm25_v1
modifier: idf
on_disk: true
```

Target point projection and selection policy:

```text
canonical-location-v2
one sparse point per reopenable MySQL PAGE / SECTION / TABLE / FIGURE location
single Qdrant search per request
deterministic paper coverage
canonical lead coverage for single-paper and short entity-like queries
```

Lead coverage selects from the same lexical candidate pool. It does not issue a second search, alter
the raw BM25 score, call an embedding provider, or fall back to another retriever.

## Gate 0: Prepare a Releasable Build

Do not cut over from an uncommitted working tree. Create a release commit or tag containing the
lexical implementation, then record:

```text
git commit SHA
JAR SHA-256
DDL migration SHA-256
QDRANT_COLLECTION
Qdrant version
deployment timestamp
```

Run only the focused pre-deployment checks:

```bash
mvn clean test-compile -DskipTests
mvn -Dtest=LexicalBm25EncoderTest,RetrievalIndexContractServiceTest,\
QdrantReadingLocationRetrieverTest,QdrantClientTest,PaperSearchabilityServiceTest,\
ReadingModelQdrantIndexServiceTest,QdrantReadingModelReindexServiceTest,\
CorpusRetrievalServiceTest,PaperReadingModelRepositoryTest test
mvn -DskipTests package
```

Stop if compilation or any focused test fails.

## Gate 1: Apply DDL

`docs/databases/ddl.sql` describes the target schema, but `CREATE TABLE IF NOT EXISTS` does not add or
rename columns in an existing installation. Before cutover, create and review a one-time migration,
for example:

```text
docs/databases/migrations/2026-07-18-lexical-qdrant-cutover.sql
```

The migration must establish:

- `paper_reading_models.retrieval_index_status` and job/error timestamps;
- `paper_reading_models.retrieval_index_contract`;
- `paper_reading_models.retrieval_indexed_location_count`;
- the singleton `paper_retrieval_control` table;
- `active_index_contract` and `lexical_average_document_length`;
- the `QDRANT_FULL_REBUILD` control row.

If the installed database contains `retrieval_embedding_contract`, explicitly rename or replace it in
the migration. Do not assume the edited `ddl.sql` will migrate it.

Before applying DDL, stop Java index writers by stopping the backend. This migration is intentionally
destructive: remove obsolete columns and control rows outright.

After applying DDL, verify the required columns through `information_schema.columns` and verify the
singleton control row. Do not deploy Java until these checks pass.

## Gate 2: Deploy and Restart

Set the same collection in every runtime environment:

```bash
QDRANT_COLLECTION=paperloom_reading_locations_bm25_v1
```

Start the target data services, then restart Java from the built JAR:

```bash
docker compose --env-file .env -f docs/docker-compose.yaml up -d mysql qdrant
scripts/paperloom-start-backend.sh restart
scripts/paperloom-start-backend.sh status
```

Expected state before rebuilding:

- Java is reachable on port `8081`;
- Qdrant health is green;
- paper search is allowed to fail closed because no active lexical index exists yet;
- no query embedding request should occur.

## Gate 3: Full Rebuild

Obtain an administrator JWT through the existing login endpoint and start the synchronous rebuild:

```bash
ADMIN_JWT=$(curl -fsS http://127.0.0.1:8081/api/v1/users/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"<admin>","password":"<password>"}' \
  | jq -r '.data.token')

curl --fail-with-body --max-time 0 -X POST \
  http://127.0.0.1:8081/api/v1/admin/retrieval/rebuild-all \
  -H "Authorization: Bearer ${ADMIN_JWT}" \
  | tee research/golden-data/local-runs/lexical-qdrant-cutover-<run_stamp>/rebuild.json
```

The endpoint deletes the target collection and rebuilds it from unchanged Current Reading Models.
A second terminal may inspect progress:

```bash
curl --fail-with-body \
  http://127.0.0.1:8081/api/v1/admin/retrieval/rebuild-all/status \
  -H "Authorization: Bearer ${ADMIN_JWT}"
```

Rebuild acceptance:

- `fullRebuildStatus = SUCCEEDED`;
- `failedPaperCount = 0`;
- `completedPaperCount = snapshotPaperCount`;
- every Current READY model has `retrieval_index_status = READY`;
- every indexed model uses the active `retrieval_index_contract`;
- every indexed model has a positive location count.

## Gate 4: Verify Qdrant and Retrieval

First verify the actual collection schema through the Qdrant API:

```text
dense vectors: none
sparse vectors: exactly lexical_bm25_v1
modifier: idf
on_disk: true
```

Then compare exact counts:

```text
Qdrant exact global point count
    == SUM(Current READY model retrieval_indexed_location_count)

Qdrant exact count for each (paper_id, model_version)
    == that model's retrieval_indexed_location_count
```

Automate this comparison in a small cutover verifier rather than manually sampling papers. The
verifier should write JSON under:

```text
research/golden-data/local-runs/lexical-qdrant-cutover-<run_stamp>/
```

Run three product retrieval smokes before spending MiniMax tokens:

1. one narrow single-paper query;
2. one multi-paper comparison where coverage is expected;
3. one page-range or element-type-hint query.

For each smoke, confirm:

- only one lexical Qdrant search occurs;
- the candidate payload matches the active `(paper_id, model_version)`;
- MySQL hydrates every returned `location_ref`;
- no stale Reading Model candidate reaches the response;
- Query Embedding calls remain `0`;
- citations can reopen the hydrated evidence.

The saved-query probe must inspect `lexical_bm25_v1`, reject Dense vectors, and exercise the live
Java/Qdrant/MySQL path without an embedding or Elasticsearch assumption.

After updating the saved-query probe, replay the existing 69 MiniMax retrieval queries once without
calling the model. Do not continue to Golden Data unless the lexical product path reaches the agreed
minimum:

```text
exact evidence obligations >= 42/48
complete cases             >= 20/24
MRR                        >= 0.35923
```

This is the inexpensive retrieval gate. It localizes index or ranking failures before MiniMax token
cost and model variance are introduced.

## Gate 5: MiniMax-M3 Golden Data

MiniMax-M3 is the acceptance model. GPT is not part of this gate. Use the unchanged product corpus
map, prompts, manifests, and model-visible tool contract.

Run one stable and one expanded focus Case first. If both complete without a technical failure, run
both manifests in full by omitting `--case-id`:

```bash
run_stamp=$(date +%Y%m%d-%H%M%S)
run_root="research/golden-data/local-runs/lexical-qdrant-cutover-${run_stamp}"

.venv-harness/bin/python -m harness_py \
  --manifest research/golden-data/manifest.yaml \
  agent-run \
  --provider-source env \
  --product-corpus-map research/golden-data/product-corpus-map.local.yaml \
  --eval-dump "${run_root}/stable-eval" \
  --out "${run_root}/stable"

.venv-harness/bin/python -m harness_py \
  --manifest research/golden-data/manifest-expanded.yaml \
  agent-run \
  --provider-source env \
  --product-corpus-map research/golden-data/product-corpus-map-expanded.local.yaml \
  --eval-dump "${run_root}/expanded-eval" \
  --out "${run_root}/expanded"
```

Before running, verify that environment configuration resolves to `MiniMax-M3`. Do not silently
retry semantic failures. Only rerun a Case when the first run is classified as a transport or
provider technical failure, and preserve both attempts.

Golden acceptance:

- stable and expanded manifests both finish;
- no new authorization, scope, evidence-read, or citation regression appears;
- no new failure is attributable to missing or stale Qdrant candidates;
- results are compared against the previous MiniMax single-path baseline Case by Case, not only by
  one aggregate pass rate;
- all raw runs and evaluation events remain in the persistent run directory.

## Go/No-Go

Accept the cutover only when all five gates pass. Keep the system fail-closed and fix forward when any
of the following occurs:

- DDL does not match the entity/repository contract;
- the backend cannot start cleanly;
- rebuild status is not `SUCCEEDED`;
- global or per-paper point counts differ;
- Dense vectors, old sparse vectors, stale models, or query embeddings appear;
- representative retrieval cannot hydrate exact MySQL evidence;
- MiniMax-M3 introduces a new retrieval, scope, evidence, or citation regression.

This proposal deliberately separates implementation completion from production acceptance. The
product is switched only after this cutover record proves the DDL, deployed runtime, rebuilt index,
retrieval behavior, and complete MiniMax Golden Data together.

## Final Acceptance Record

Recorded on 2026-07-18. Decision: **accept the destructive lexical-Qdrant cutover**. This decision
means the product data plane is deployed and verified; it does not mean every MiniMax Golden Case
hard-passes.

Persistent evidence root:

```text
research/golden-data/local-runs/lexical-qdrant-cutover-20260718-173750/
```

### Release Identity

| Item | Accepted value |
| --- | --- |
| Main release commit | `a08f03804e5d079b633ce94318a9beb59f642028` |
| JAR SHA-256 | `153c0a605921c3663231ac2612325507ebdf5b5445b2b4fb8a37359a97adca72` |
| Lexical DDL migration SHA-256 | `39a920f2a8055bec6e23d80476d374555d1f4e12fc979347333e69c275705978` |
| Embedding cleanup migration SHA-256 | `d4b304e3e0e66bd0d956dfe73cbddb8c8876d18d236437bcdc9838af20ab4850` |
| Backend ready timestamp | `2026-07-18T22:52:39+08:00` |
| Qdrant version | `1.15.5`, commit `48203e414e4e7f639a6d394fb6e4df695f808e51` |
| Qdrant image | `qdrant/qdrant:v1.15.5`, image ID `sha256:0ad2e23181e5646b286253c04cea23f07040ca313cc377d9fa7b21db184b6406` |
| Collection | `paperloom_reading_locations_bm25_v1` |
| Sparse vector | `lexical_bm25_v1`, `modifier=idf`, `on_disk=true` |

The deployed process command points at the JAR built from the committed `a08f038` snapshot. Port
`8081` is reachable, the authenticated `/api/v1/users/me` probe returns the expected unauthenticated
`403`, and the process environment resolves `QDRANT_COLLECTION` to the accepted collection.

### Build And Schema Gates

- Clean Java test compilation passed.
- The nine focused cutover test classes passed: `35` tests, `0` failures.
- The complete Java suite passed from the committed snapshot: `685` tests, `0` failures, `0` errors,
  `3` skipped. The ignored launch PDF corpus was mounted into the isolated build worktree for the
  manifest existence test; no corpus file was copied or modified.
- The Python Harness suite passed: `120` tests, `0` failures, `1` skipped.
- Frontend `vue-tsc` type checking and the production Vite build passed. Login, chat-shell, and
  knowledge-base bundle budgets all passed.
- The installed MySQL schema contains the lexical index columns and singleton
  `QDRANT_FULL_REBUILD` control row. Obsolete embedding/index-generation columns count is `0`, and
  `model_provider_configs` contains only `llm` rows.
- The two destructive migrations were already applied before this deployment and were not rerun.
  The live schema and repository contract were verified instead.

### Rebuild And Retrieval Gates

The saved synchronous rebuild completed with:

```text
Current READY models:       87/87
Indexed locations:          9,383
Indexed lexical tokens:     2,257,908
Failed papers:              0
Frozen average doc length:  240.6381754236385
```

The deployed verifier output
`cutover-verification-deployed-a08f038.json` reports `failure_count=0`. The global Qdrant exact
count is `9,383`, and all `87` per-`(paper_id, model_version)` exact counts equal MySQL. Qdrant is
green and contains only `paperloom_reading_locations_bm25_v1`; its dense vector map is empty and its
only sparse vector is `lexical_bm25_v1`.

The post-deployment replay in `retrieval-probe-deployed-a08f038/` exercised all `69` saved MiniMax
retrieval queries through Java, Qdrant, Current Model validation, and MySQL hydration:

| Metric | Java/Qdrant lexical result | Gate |
| --- | ---: | ---: |
| Exact evidence obligations | `48/48` | `>= 42/48` |
| Complete Cases | `24/24` | `>= 20/24` |
| MRR | `0.4801925505` | `>= 0.35923` |

The frozen 76-paper latency run remains the accepted scale measurement: narrow p50 `64.821 ms`;
broad p50 `132.100 ms`, p95 `371.559 ms`, and max `390.086 ms`. Source and runtime inspection found
no Elasticsearch path, Dense search, query embedding, RRF, Hybrid request path, or request-level
retrieval fallback. The retriever test and implementation verify one lexical Qdrant search per
request; coverage is selected from that same candidate pool.

### MiniMax-M3 Gate

Both unchanged manifests completed with MiniMax-M3 through the Agents SDK and the Java/Qdrant
product corpus path:

| Manifest | Hard Pass | Technical failures | Eval capture failures | Duplicate events |
| --- | ---: | ---: | ---: | ---: |
| Stable | `6/10` | `0` | `0` | `0` |
| Expanded | `9/24` | `0` | `0` | `0` |

Against the immediately preceding live-Qdrant MiniMax runs, stable remained `6/10` with one improved,
one regressed, and eight unchanged Cases. Expanded improved from `5/24` to `9/24`, with six improved,
two regressed, and sixteen unchanged Cases. Every newly regressed Case had all required candidates
available; the failures were model evidence-selection, citation, or content behavior rather than a
missing or stale Qdrant candidate.

Across the final expanded attribution, `47/48` required obligations were available to the actual
MiniMax queries and `29/48` were read. The remaining broad-query miss is
`tau_react_baseline_relationship`; the frozen replay retrieves it at rank `9`. That Case was already
failing in the preceding live-Qdrant baseline, so it is not a new cutover regression, but it remains
an explicit model-query/top-k limitation. The `6/10` and `9/24` results must therefore remain visible;
candidate recall must not be reported as end-to-end Golden success.

### Protected Inputs And Retirement Audit

The release diff does not modify prompts, Golden manifests, Golden Cases, Anchors, product corpus
maps, PDFs, extracted text, Reading Models, or corpus directories. Runtime and container inspection
also confirms:

- only MySQL and sparse-only Qdrant are required by this retrieval data plane;
- no Elasticsearch container or volume exists;
- no Elasticsearch or embedding environment variable is present in the backend process;
- the embedding client/provider runtime, Dense/Hybrid/RRF path, old collection contract, and
  request fallback are absent from current source.

All five cutover gates are therefore accepted. Future work may improve MiniMax evidence selection or
ranking under different query wording, but it must proceed as a new experiment rather than restoring
the deleted compatibility path.
