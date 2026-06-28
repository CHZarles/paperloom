# PaperLoom Eval Data Isolation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move LitSearch/QASPER benchmark data out of product tables and product Elasticsearch indices while preserving benchmark capability through an explicit eval corpus data domain.

**Architecture:** Product PaperLoom stores only uploaded PDF papers in `paismart` and product ES indices. Benchmark corpora live in `paperloom_eval` and eval ES indices, while retrieval strategy code is reused through an explicit `RetrievalCorpus` selector. Product controllers/chat can only use `PRODUCT_LIBRARY`; eval CLI/harness code must explicitly use `EVAL_LITSEARCH` or `EVAL_QASPER`.

**Tech Stack:** Spring Boot 3.4, Java 17+/21 runtime, JPA/MySQL, Elasticsearch 8.10, Maven, Vue 3/TypeScript, pnpm.

---

## Ground Truth

Authoritative product decisions:

- Product input is PDF only.
- Structured benchmark data is not product literature data.
- `paismart.file_upload` must not contain `is_eval`, `source_dataset`, `external_corpus_id`, or `eval_split`.
- Product API/DTOs must not expose `evalImport` or `structuredImport`.
- Product frontend must not contain `EVAL_IMPORT` or `STRUCTURED_IMPORT` display branches.
- Benchmark corpora use `paperloom_eval` MySQL schema and eval ES indices.
- Product code must not use an `includeEval` switch.

Current polluted runtime facts from 2026-06-28:

- `paismart.file_upload`: 64,255 rows.
- LitSearch eval rows in product DB: 64,183.
- QASPER eval rows in product DB: 71.
- Real product PDF rows in product DB: 1.
- Product `paper_chunks` ES index: 1,480,919 docs, including eval chunks.
- Product `paper_search` ES index: 64,254 docs, mostly eval paper candidates.

## Required Final State

Product data domain:

- `paismart.file_upload` contains only real product PDFs.
- `paismart.paper_text_chunks` contains only product PDF-derived chunks.
- Product ES `paper_search` and `paper_chunks` contain only product PDF-derived docs.
- Product conversations contain no references to LitSearch/QASPER paper IDs after cleanup.
- Product paper list/source picker/chat retrieval never returns eval corpus records.

Eval data domain:

- `paperloom_eval.eval_papers` contains LitSearch and QASPER paper records.
- `paperloom_eval.eval_chunks` contains benchmark structured text chunks.
- `paperloom_eval.eval_queries` contains benchmark query/label records.
- `paperloom_eval.eval_runs` stores benchmark run metadata and artifact pointers.
- ES indices such as `eval_litsearch_paper_search`, `eval_litsearch_chunks`,
  `eval_qasper_paper_search`, and `eval_qasper_chunks` support eval retrieval.

## File Responsibility Map

Product domain cleanup:

- `src/main/java/com/yizhaoqi/smartpai/model/Paper.java`: remove eval-only fields.
- `src/main/java/com/yizhaoqi/smartpai/repository/PaperRepository.java`: remove eval lookup methods.
- `src/main/java/com/yizhaoqi/smartpai/controller/PaperController.java`: expose PDF readiness only.
- `src/main/java/com/yizhaoqi/smartpai/service/HybridSearchService.java`: stop deriving eval readiness from product papers.
- `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java`: remove product reference `evalImport`/`structuredImport`.
- `src/main/java/com/yizhaoqi/smartpai/service/PaperAnswerService.java`: remove product reference `evalImport`/`structuredImport`.
- `src/main/java/com/yizhaoqi/smartpai/service/EvidenceToolExecutor.java`: remove product eval detail hydration.
- `frontend/src/typings/api.d.ts`: remove product `evalImport`/`structuredImport` types.
- `frontend/src/views/chat/modules/source-evidence-panel.vue`: remove eval/structured import UI branches.
- `frontend/src/views/chat/modules/reference-evidence-page.vue`: remove eval/structured import state.
- `frontend/src/views/chat/modules/chat-message.vue`: stop passing eval/structured fields.
- `frontend/src/views/chat/modules/chat-list.vue`: stop emitting eval/structured fields.
- `frontend/src/views/knowledge-base/index.vue`: show only PDF readiness states.

Eval domain additions:

- Create `src/main/java/com/yizhaoqi/smartpai/eval/RetrievalCorpus.java`.
- Create eval-native JPA entities and repositories under `src/main/java/com/yizhaoqi/smartpai/eval/`.
- Create `EvalCorpusMigrationCli` to migrate existing polluted rows into `paperloom_eval`.
- Create `EvalCorpusCleanupCli` to delete migrated rows from product tables and product ES indices.
- Modify LitSearch/QASPER importers and benchmark CLIs under `src/test/java/com/yizhaoqi/smartpai/eval/`
  so they read/write eval schema and eval indices.

## Task 1: Product Boundary Documentation

**Files:**

- Modify: `docs/PAPERLOOM_PRODUCT_REQUIREMENTS.md`
- Modify: `docs/PAPERLOOM_IMPLEMENTATION_ALIGNMENT.md`
- Modify: `AGENTS.md`
- Modify: `CLAUDE.md`
- Create: `CONTEXT.md`
- Create: `docs/adr/0001-separate-product-papers-from-eval-corpora.md`

- [ ] **Step 1: Verify product docs mention PDF-only input**

Run:

```bash
rg -n "PDF only|product input is PDF|paperloom_eval|is_eval|structured import|eval import" \
  AGENTS.md CLAUDE.md CONTEXT.md docs/PAPERLOOM_PRODUCT_REQUIREMENTS.md docs/PAPERLOOM_IMPLEMENTATION_ALIGNMENT.md docs/adr
```

Expected:

- Product docs say product input is PDF only.
- Product docs say eval data belongs in `paperloom_eval`.
- Product docs do not allow structured imports into the product paper library.

- [ ] **Step 2: Commit documentation boundary**

Run:

```bash
git add AGENTS.md CLAUDE.md CONTEXT.md docs/adr/0001-separate-product-papers-from-eval-corpora.md docs/PAPERLOOM_PRODUCT_REQUIREMENTS.md docs/PAPERLOOM_IMPLEMENTATION_ALIGNMENT.md docs/superpowers/plans/2026-06-28-paperloom-eval-data-isolation-plan.md
git commit -m "docs: require eval corpus isolation from product papers"
```

Expected:

- Commit contains only documentation and plan files.

## Task 2: Eval Schema Model

**Files:**

- Create: `src/main/java/com/yizhaoqi/smartpai/eval/RetrievalCorpus.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/eval/model/EvalPaper.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/eval/model/EvalChunk.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/eval/model/EvalQuery.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/eval/model/EvalRun.java`
- Create: repositories under `src/main/java/com/yizhaoqi/smartpai/eval/repository/`
- Test: `src/test/java/com/yizhaoqi/smartpai/eval/EvalCorpusModelTest.java`

- [ ] **Step 1: Add retrieval corpus enum**

Create `RetrievalCorpus.java`:

```java
package com.yizhaoqi.smartpai.eval;

public enum RetrievalCorpus {
    PRODUCT_LIBRARY,
    EVAL_LITSEARCH,
    EVAL_QASPER
}
```

- [ ] **Step 2: Add eval model test**

Create `EvalCorpusModelTest` asserting:

```java
assertEquals("PRODUCT_LIBRARY", RetrievalCorpus.PRODUCT_LIBRARY.name());
assertEquals("EVAL_LITSEARCH", RetrievalCorpus.EVAL_LITSEARCH.name());
assertEquals("EVAL_QASPER", RetrievalCorpus.EVAL_QASPER.name());
```

- [ ] **Step 3: Add eval entities**

Create JPA entities mapped to schema `paperloom_eval`. `EvalPaper` must include:

```java
@Entity
@Table(name = "eval_papers", schema = "paperloom_eval")
public class EvalPaper {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 64)
    private String corpus;
    @Column(nullable = false, length = 64)
    private String split;
    @Column(nullable = false, length = 128)
    private String externalPaperId;
    @Column(nullable = false, length = 160)
    private String paperId;
    @Column(columnDefinition = "TEXT")
    private String title;
    @Column(columnDefinition = "TEXT")
    private String abstractText;
    @Column(columnDefinition = "TEXT")
    private String authors;
    private String venue;
    private Integer year;
    private String doi;
    private String arxivId;
    @Column(columnDefinition = "LONGTEXT")
    private String fullText;
    @Column(columnDefinition = "LONGTEXT")
    private String sourceJson;
}
```

Add analogous focused entities for `eval_chunks`, `eval_queries`, and `eval_runs` with corpus,
split, stable IDs, source JSON, and metrics/artifact fields.

- [ ] **Step 4: Run model test**

Run:

```bash
mvn -q -Dtest=EvalCorpusModelTest test
```

Expected:

- Test passes.

- [ ] **Step 5: Commit eval schema model**

Run:

```bash
git add src/main/java/com/yizhaoqi/smartpai/eval src/test/java/com/yizhaoqi/smartpai/eval/EvalCorpusModelTest.java
git commit -m "feat: add isolated eval corpus model"
```

## Task 3: Eval Migration CLI

**Files:**

- Create: `src/main/java/com/yizhaoqi/smartpai/eval/EvalCorpusMigrationCli.java`
- Create: `src/main/java/com/yizhaoqi/smartpai/eval/EvalCorpusIndexService.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/eval/EvalCorpusMigrationCliTest.java`

- [ ] **Step 1: Write migration CLI test**

Test that the CLI refuses to run without `--target-schema paperloom_eval` and prints:

```text
litsearch expected papers: 64183
qasper expected papers: 71
source schema: paismart
target schema: paperloom_eval
```

- [ ] **Step 2: Implement migration CLI dry-run**

Implement options:

```text
--source-schema paismart
--target-schema paperloom_eval
--corpus litsearch|qasper|all
--dry-run
--rebuild-indices
```

Dry-run must count product-polluted rows from `paismart.file_upload` and print counts without
writing eval tables.

- [ ] **Step 3: Implement migration writes**

Migration must read current polluted rows, write `EvalPaper`/`EvalChunk`/`EvalQuery`, and rebuild
eval ES indices when `--rebuild-indices` is supplied.

- [ ] **Step 4: Run CLI dry-run**

Run:

```bash
mvn -q -DskipTests test-compile
java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" \
  com.yizhaoqi.smartpai.eval.EvalCorpusMigrationCli \
  --source-schema paismart \
  --target-schema paperloom_eval \
  --corpus all \
  --dry-run
```

Expected:

- LitSearch count is `64183`.
- QASPER count is `71`.
- No writes occur.

- [ ] **Step 5: Commit migration CLI**

Run:

```bash
git add src/main/java/com/yizhaoqi/smartpai/eval src/test/java/com/yizhaoqi/smartpai/eval/EvalCorpusMigrationCliTest.java
git commit -m "feat: migrate benchmark data into eval corpus"
```

## Task 4: Benchmark Harness Corpus Routing

**Files:**

- Modify: `src/test/java/com/yizhaoqi/smartpai/eval/ServiceBackedLitSearchBenchmarkCli.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/eval/LitSearchPaperLoomImporter.java`
- Modify: `src/test/java/com/yizhaoqi/smartpai/eval/QasperPaperLoomImporter.java`
- Modify: `scripts/paperloom-rag-gates.sh`
- Test: relevant existing eval CLI tests

- [ ] **Step 1: Replace product repository lookup**

Remove:

```java
paperRepository.findByEvalTrueAndSourceDatasetAndEvalSplit(...)
```

Use:

```java
evalPaperRepository.findByCorpusAndSplit("litsearch", split)
```

- [ ] **Step 2: Require explicit corpus in eval CLI**

Benchmark CLI must accept and require:

```text
--retrieval-corpus EVAL_LITSEARCH
--retrieval-corpus EVAL_QASPER
```

Reject `PRODUCT_LIBRARY` for LitSearch/QASPER benchmark commands.

- [ ] **Step 3: Run eval CLI tests**

Run:

```bash
mvn -q -Dtest=ServiceBackedLitSearchBenchmarkCliTest,LitSearchPaperLoomImportCliTest,QasperPaperLoomImportCliTest test
```

Expected:

- CLI rejects missing corpus.
- CLI does not call product `PaperRepository` for eval imports.

- [ ] **Step 4: Commit harness routing**

Run:

```bash
git add src/test/java/com/yizhaoqi/smartpai/eval scripts/paperloom-rag-gates.sh
git commit -m "refactor: route benchmarks through eval corpus"
```

## Task 5: Remove Eval Concepts From Product API And Frontend

**Files:**

- Modify product Java services/controllers/entities that currently mention `isEval`, `sourceDataset`,
  `evalImport`, or `structuredImport`.
- Modify product frontend files that currently mention `EVAL_IMPORT`, `STRUCTURED_IMPORT`,
  `evalImport`, or `structuredImport`.

- [ ] **Step 1: Remove product entity fields**

Remove these fields from `Paper`:

```java
sourceDataset
externalCorpusId
evalSplit
eval
```

Remove repository methods that query eval fields.

- [ ] **Step 2: Remove product DTO eval fields**

Remove product API fields:

```text
evalImport
structuredImport
sourceType=EVAL_IMPORT
sourceType=STRUCTURED_IMPORT
```

Keep PDF readiness:

```text
sourceType=PDF
evidenceAssetLevel=PDF_VISUAL|PDF_PENDING_ASSETS
pdfEvidenceAvailable
pageScreenshotAvailable
figureScreenshotAvailable
assetWarnings
```

- [ ] **Step 3: Remove frontend eval branches**

Remove UI checks for:

```text
EVAL_IMPORT
STRUCTURED_IMPORT
evalImport
structuredImport
structured_import_text_only
```

- [ ] **Step 4: Run product tests**

Run:

```bash
mvn -q -Dtest=PaperControllerContractTest,ChatHandlerReferenceEvidenceTest,ConversationServiceTest,PaperAnswerServiceTest,HybridSearchServicePaperSearchTest test
cd frontend && pnpm typecheck
```

Expected:

- Tests pass after updating assertions to PDF-only product semantics.

- [ ] **Step 5: Commit product cleanup**

Run:

```bash
git add src/main/java frontend/src src/test/java
git commit -m "refactor: remove eval imports from product API"
```

## Task 6: Cleanup Polluted Product Data

**Files:**

- Create: `src/main/java/com/yizhaoqi/smartpai/eval/EvalCorpusCleanupCli.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/eval/EvalCorpusCleanupCliTest.java`

- [ ] **Step 1: Add cleanup CLI guardrails**

Cleanup CLI must require:

```text
--source-schema paismart
--eval-schema paperloom_eval
--confirm-delete-eval-from-product
```

Without the confirmation flag, it must exit non-zero.

- [ ] **Step 2: Implement cleanup**

Cleanup deletes from product data domain:

- `paismart.file_upload` rows whose IDs exist in `paperloom_eval.eval_papers`
- `paismart.paper_text_chunks` rows for eval paper IDs
- parser/table/figure/formula/visual asset rows for eval paper IDs
- product ES `paper_search` and `paper_chunks` docs for eval paper IDs
- conversations that contain `litsearch:` or `qasper:` reference mappings

- [ ] **Step 3: Run cleanup dry-run**

Run:

```bash
java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" \
  com.yizhaoqi.smartpai.eval.EvalCorpusCleanupCli \
  --source-schema paismart \
  --eval-schema paperloom_eval \
  --dry-run
```

Expected:

- Prints exact rows/docs that would be deleted.
- Does not delete anything.

- [ ] **Step 4: Run confirmed cleanup only after migration verification**

Run:

```bash
java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" \
  com.yizhaoqi.smartpai.eval.EvalCorpusCleanupCli \
  --source-schema paismart \
  --eval-schema paperloom_eval \
  --confirm-delete-eval-from-product
```

Expected:

- Product DB contains zero LitSearch/QASPER rows.
- Product ES contains zero LitSearch/QASPER docs.

- [ ] **Step 5: Commit cleanup CLI**

Run:

```bash
git add src/main/java/com/yizhaoqi/smartpai/eval/EvalCorpusCleanupCli.java src/test/java/com/yizhaoqi/smartpai/eval/EvalCorpusCleanupCliTest.java
git commit -m "chore: clean eval records from product corpus"
```

## Task 7: Runtime Acceptance

**Files:**

- No source changes unless acceptance exposes a bug.

- [ ] **Step 1: Verify product DB**

Run:

```bash
docker exec pai_smart_mysql sh -lc 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" paismart -N <<SQL
SHOW COLUMNS FROM file_upload LIKE "is_eval";
SHOW COLUMNS FROM file_upload LIKE "source_dataset";
SHOW COLUMNS FROM file_upload LIKE "external_corpus_id";
SHOW COLUMNS FROM file_upload LIKE "eval_split";
SELECT COUNT(*) FROM file_upload;
SELECT COUNT(*) FROM paper_text_chunks WHERE paper_id LIKE "litsearch:%" OR paper_id LIKE "qasper:%";
SQL'
```

Expected:

- No column rows are returned for eval-only columns.
- `file_upload` count equals the number of real product PDFs.
- Eval chunk count in product DB is `0`.

- [ ] **Step 2: Verify product ES**

Run:

```bash
ES_PASS=$(sed -n 's/^ELASTICSEARCH_PASSWORD=//p' .env | tail -1)
curl -sS -u "elastic:${ES_PASS}" -H 'Content-Type: application/json' \
  http://localhost:9200/paper_chunks/_count \
  -d '{"query":{"bool":{"should":[{"prefix":{"paperId":"litsearch:"}},{"prefix":{"paperId":"qasper:"}}]}}}'
curl -sS -u "elastic:${ES_PASS}" -H 'Content-Type: application/json' \
  http://localhost:9200/paper_search/_count \
  -d '{"query":{"bool":{"should":[{"prefix":{"paperId":"litsearch:"}},{"prefix":{"paperId":"qasper:"}}]}}}'
```

Expected:

- Both counts are `0`.

- [ ] **Step 3: Verify product browser behavior**

Open:

```text
http://localhost:9527/#/chat
```

Ask:

```text
有什么 agent 相关论文吗？
```

Expected:

- Answer uses only real product PDFs.
- If the product library has insufficient evidence, the answer says so directly.
- No LitSearch/QASPER citation appears.
- Evidence panel never displays eval/structured import labels.

- [ ] **Step 4: Verify eval benchmark still works**

Run:

```bash
scripts/paperloom-rag-gates.sh litsearch-full-summary --retrieval-corpus EVAL_LITSEARCH
scripts/paperloom-rag-gates.sh qasper-dev-200 --retrieval-corpus EVAL_QASPER
```

Expected:

- Eval commands read `paperloom_eval` and eval ES indices.
- Product `conversations` table is not written by benchmark runs.

## Self-Review

Spec coverage:

- Product PDF-only input: Task 1 and Task 5.
- Eval schema isolation: Task 2 and Task 3.
- Benchmark corpus routing: Task 4.
- Product eval field removal: Task 5.
- Product data cleanup: Task 6 and Task 7.
- Runtime browser acceptance: Task 7.

Placeholder scan:

- Check this plan against the forbidden placeholder patterns listed in
  `/home/charles/.codex/superpowers/skills/writing-plans/SKILL.md`.
- The plan should contain concrete paths, commands, expected outputs, and field names rather than
  open-ended implementation notes.
