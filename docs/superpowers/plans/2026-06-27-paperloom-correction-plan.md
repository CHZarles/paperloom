# PaperLoom Correction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Align the running PaperLoom implementation with the user-confirmed product standard: permissioned research-paper RAG, PDF-first ingestion, evidence-grounded answers, accurate evidence display, and layered benchmarks.

**Architecture:** Keep the existing Spring Boot, Vue 3, MySQL, Redis, Elasticsearch, Kafka, and MinIO architecture. Correct the product by tightening contracts at the boundaries: product documentation, paper provenance/readiness, semantic routing ownership, citation evidence payloads, frontend evidence states, and benchmark gates. Avoid broad rewrites and keep PageIndex/page-location methods eval-gated until benchmark evidence justifies production promotion.

**Tech Stack:** Java 17+/21 runtime, Spring Boot 3.4, JPA/MySQL, Elasticsearch 8.10, Kafka, Redis, MinIO, Vue 3, TypeScript, Vite, Naive UI, Maven, pnpm.

---

## Ground Truth

Use these files as the product authority:

- `AGENTS.md`
- `CLAUDE.md`
- `docs/PAPERLOOM_PRODUCT_REQUIREMENTS.md`
- `docs/PAPERLOOM_IMPLEMENTATION_ALIGNMENT.md`
- `eval/rag/RETRIEVAL_STRATEGY_DECISION.md`
- `eval/rag/STRATEGY_READINESS.md`
- `eval/rag/CHEATSHEET.md`

Current runtime observed on 2026-06-27:

- Backend process cwd: `/home/charles/PaiSmart/.worktrees/adaptive-source-set-rag`
- Frontend process cwd: `/home/charles/PaiSmart/.worktrees/adaptive-source-set-rag/frontend`
- Backend port: `8081`
- Frontend port: `9527`
- Main worktree has unrelated dirty frontend/theme files.
- RAG worktree branch is `feature/adaptive-source-set-rag` and has uncommitted RAG changes.
- The product docs currently exist in the main worktree. The active RAG worktree does not yet contain them.

## Required Invariants

- Do not implement production routing with fixed phrase lists.
- Do not present structured/eval imports as PDF-derived page evidence.
- Do not claim OCR/parser/page visual evidence quality from QASPER or LitSearch.
- Keep all retrieval permission-filtered.
- Keep rendered citations backed by persisted `referenceMappings`.
- Keep `LOCATE_PAGES`, external PageIndex, and arbitrary page guessing behind eval gates.
- Do not mix unrelated main-worktree frontend/theme changes into RAG correction commits.

## File Responsibility Map

Product context:

- `AGENTS.md`: first-read order and repo-local agent contract.
- `docs/PAPERLOOM_PRODUCT_REQUIREMENTS.md`: user-confirmed product standard.
- `docs/PAPERLOOM_IMPLEMENTATION_ALIGNMENT.md`: current implementation alignment and drift register.

Backend paper and evidence contract:

- `src/main/java/com/yizhaoqi/smartpai/model/Paper.java`: paper storage fields, including eval/import markers.
- `src/main/java/com/yizhaoqi/smartpai/controller/PaperController.java`: paper list/detail response, parser and asset endpoints.
- `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java`: WebSocket response and `ReferenceInfo` payload.
- `src/main/java/com/yizhaoqi/smartpai/service/PaperAnswerService.java`: answer route handling and reference mapping assembly.
- `src/main/java/com/yizhaoqi/smartpai/service/EvidenceToolExecutor.java`: planner action execution and evidence ledger conversion.
- `src/main/java/com/yizhaoqi/smartpai/service/EvidenceAnswerGenerator.java`: evidence-grounded answer prompt.
- `src/main/java/com/yizhaoqi/smartpai/service/EvidenceVerifier.java`: citation and unsupported-claim checks.

Backend retrieval and planning:

- `src/main/java/com/yizhaoqi/smartpai/service/PaperChatRouter.java`: high-level chat route, must remain semantic/state-based.
- `src/main/java/com/yizhaoqi/smartpai/service/EvidencePlanner.java`: action owner for paper discovery, QA, reference, and trusted page inspection.
- `src/main/java/com/yizhaoqi/smartpai/service/PaperQueryPlanner.java`: query normalization and retrieval expansions, not top-level product routing.
- `src/main/java/com/yizhaoqi/smartpai/service/PaperRetrievalService.java`: paper discovery and scoped QA retrieval paths.
- `src/main/java/com/yizhaoqi/smartpai/service/HybridSearchService.java`: permissioned chunk and paper-search queries.

Frontend paper UX:

- `frontend/src/typings/api.d.ts`: paper and reference evidence contract.
- `frontend/src/views/chat/index.vue`: chat shell and visible product title.
- `frontend/src/views/chat/modules/chat-message.vue`: assistant author and citation behavior.
- `frontend/src/views/chat/modules/conversation-sidebar.vue`: chat brand label.
- `frontend/src/views/chat/modules/source-evidence-panel.vue`: citation evidence panel.
- `frontend/src/views/chat/modules/reference-evidence-page.vue`: source evidence page.
- `frontend/src/views/knowledge-base/index.vue`: paper library table, status, assets, parser actions.
- `frontend/src/views/knowledge-base/modules/search-dialog.vue`: paper search dialog copy.
- `frontend/src/views/knowledge-base/modules/upload-dialog.vue`: PDF upload UI.
- `frontend/src/locales/langs/zh-cn.ts` and `frontend/src/locales/langs/en-us.ts`: product copy.
- `frontend/.env`: local app title and description. Treat `VITE_STORAGE_PREFIX` as a compatibility key unless a storage migration is added.

Benchmark and eval:

- `eval/rag/product-rescue-smoke.jsonl`
- `eval/rag/product-rescue-paper-qa.jsonl`
- `eval/rag/litsearch/README.md`
- `eval/rag/qasper/README.md`
- `eval/rag/page-location/README.md`
- `eval/rag/STRATEGY_READINESS.md`
- `eval/rag/CHEATSHEET.md`
- `src/test/java/com/yizhaoqi/smartpai/eval/*`

## Execution Order

1. Commit or otherwise preserve the product docs and this plan.
2. Sync product docs into the active RAG worktree before code changes.
3. Fix visible product framing and frontend copy.
4. Add paper provenance and readiness fields to backend responses and frontend types.
5. Correct citation evidence display for PDF-derived, structured, and missing-asset cases.
6. Tighten planner ownership so paper discovery is selected by `EvidencePlanner`, while `PaperQueryPlanner` stays a retrieval-plan helper.
7. Add benchmark gates for product smoke, structured QA, literature discovery, real PDF parser smoke, and figure/table evidence.
8. Re-run targeted tests and browser checks.

---

### Task 0: Preserve Product Context Before Implementation

**Files:**

- Modify: `AGENTS.md`
- Create or keep: `docs/PAPERLOOM_PRODUCT_REQUIREMENTS.md`
- Create or keep: `docs/PAPERLOOM_IMPLEMENTATION_ALIGNMENT.md`
- Create or keep: `docs/superpowers/plans/2026-06-27-paperloom-correction-plan.md`

- [ ] **Step 1: Confirm main worktree doc state**

Run:

```bash
git status --short --branch
git diff -- AGENTS.md docs/PAPERLOOM_PRODUCT_REQUIREMENTS.md docs/PAPERLOOM_IMPLEMENTATION_ALIGNMENT.md docs/superpowers/plans/2026-06-27-paperloom-correction-plan.md
```

Expected:

- `AGENTS.md` includes the two PaperLoom docs in First Read.
- The two PaperLoom docs exist.
- This correction plan exists.
- Dirty unrelated frontend files are not staged with these docs.

- [ ] **Step 2: Commit only the product context documents**

Run:

```bash
git add AGENTS.md docs/PAPERLOOM_PRODUCT_REQUIREMENTS.md docs/PAPERLOOM_IMPLEMENTATION_ALIGNMENT.md docs/superpowers/plans/2026-06-27-paperloom-correction-plan.md
git commit -m "docs: record paperloom product correction plan"
```

Expected:

- Commit contains only `AGENTS.md` and PaperLoom docs/plan.
- Existing unrelated frontend/theme changes remain unstaged.

- [ ] **Step 3: Sync context into the active RAG branch**

Run from the active RAG worktree:

```bash
cd /home/charles/PaiSmart/.worktrees/adaptive-source-set-rag
git status --short --branch
```

If the RAG branch can merge the doc commit cleanly, run:

```bash
git merge main --no-edit
```

If merging `main` would bring unrelated main-worktree changes, cherry-pick only the doc commit:

```bash
git cherry-pick <doc-commit-sha>
```

Expected:

- `AGENTS.md` in `.worktrees/adaptive-source-set-rag` lists the product docs in First Read.
- `docs/PAPERLOOM_PRODUCT_REQUIREMENTS.md` exists in the active RAG worktree.
- `docs/PAPERLOOM_IMPLEMENTATION_ALIGNMENT.md` exists in the active RAG worktree.
- This correction plan exists in the active RAG worktree.

### Task 1: Product Naming And Paper-Library Copy

**Files:**

- Modify: `frontend/.env`
- Modify: `frontend/src/components/common/system-logo.vue`
- Modify: `frontend/src/theme/settings.ts`
- Modify: `frontend/src/locales/langs/zh-cn.ts`
- Modify: `frontend/src/locales/langs/en-us.ts`
- Modify: `frontend/src/views/chat/index.vue`
- Modify: `frontend/src/views/chat/modules/chat-message.vue`
- Modify: `frontend/src/views/chat/modules/conversation-sidebar.vue`
- Modify: `frontend/src/views/knowledge-base/index.vue`
- Modify: `frontend/src/views/knowledge-base/modules/search-dialog.vue`
- Modify: `frontend/src/views/_builtin/login/modules/register.vue`
- Modify: `frontend/src/layouts/modules/theme-drawer/modules/page-fun.vue`
- Modify: `frontend/src/layouts/modules/global-footer/index.vue`
- Modify: `frontend/src/constants/invite-channel.ts`
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/EvidenceAnswerGenerator.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/service/EvidenceAnswerGeneratorTest.java`

- [ ] **Step 1: Add a backend prompt naming regression test**

Add a test in `EvidenceAnswerGeneratorTest` that captures the first LLM system message and asserts:

```java
assertTrue(systemPrompt.contains("PaperLoom"));
assertTrue(!systemPrompt.contains("CiteWeave"));
```

Run:

```bash
mvn -q -Dtest=EvidenceAnswerGeneratorTest test
```

Expected before implementation:

- The new test fails if `EvidenceAnswerGenerator` still says `CiteWeave`.

- [ ] **Step 2: Replace visible backend assistant name**

Change `EvidenceAnswerGenerator` so the answer system prompt starts with `PaperLoom` instead of `CiteWeave`.

Run:

```bash
mvn -q -Dtest=EvidenceAnswerGeneratorTest test
```

Expected:

- `EvidenceAnswerGeneratorTest` passes.

- [ ] **Step 3: Replace visible frontend product name**

Replace visible `CiteWeave` copy with `PaperLoom` in frontend files listed above.

Compatibility rule:

- Change `VITE_APP_TITLE` and `VITE_APP_DESC` to `PaperLoom`.
- Keep or migrate `VITE_STORAGE_PREFIX` deliberately. If it remains `CiteWeave_`, add a one-line comment in this plan's implementation notes or commit message stating it is retained as a local-storage compatibility key. If it changes to `PaperLoom_`, add a storage migration that reads the old key before clearing it.

Run:

```bash
rg -n "CiteWeave" frontend/src src/main/java/com/yizhaoqi/smartpai/service frontend/.env
```

Expected:

- No visible UI or backend prompt occurrences remain.
- A remaining `VITE_STORAGE_PREFIX=CiteWeave_` is allowed only if the implementation explicitly documents compatibility.

- [ ] **Step 4: Replace generic knowledge-base wording in paper workflows**

Replace paper-workflow copy such as `知识库检索` with paper-centered wording such as `论文库检索` or `论文检索`.

Run:

```bash
rg -n "知识库|Knowledge Base|knowledge base" frontend/src/views/chat frontend/src/views/knowledge-base frontend/src/locales
```

Expected:

- No generic knowledge-base wording remains in chat or paper-library workflows.
- Billing or legacy admin pages may keep generic copy only if they are outside PaperLoom's paper workflow and the commit message names that scope.

- [ ] **Step 5: Verify frontend types and lint**

Run:

```bash
cd frontend && pnpm exec eslint src/views/chat/index.vue src/views/chat/modules/chat-message.vue src/views/chat/modules/conversation-sidebar.vue src/views/knowledge-base/index.vue src/views/knowledge-base/modules/search-dialog.vue src/locales/langs/zh-cn.ts src/locales/langs/en-us.ts
cd frontend && pnpm typecheck
```

Expected:

- ESLint passes for touched frontend files.
- Typecheck passes.

- [ ] **Step 6: Commit product framing cleanup**

Run:

```bash
git add frontend/.env frontend/src/components/common/system-logo.vue frontend/src/theme/settings.ts frontend/src/locales/langs/zh-cn.ts frontend/src/locales/langs/en-us.ts frontend/src/views/chat/index.vue frontend/src/views/chat/modules/chat-message.vue frontend/src/views/chat/modules/conversation-sidebar.vue frontend/src/views/knowledge-base/index.vue frontend/src/views/knowledge-base/modules/search-dialog.vue frontend/src/views/_builtin/login/modules/register.vue frontend/src/layouts/modules/theme-drawer/modules/page-fun.vue frontend/src/layouts/modules/global-footer/index.vue frontend/src/constants/invite-channel.ts src/main/java/com/yizhaoqi/smartpai/service/EvidenceAnswerGenerator.java src/test/java/com/yizhaoqi/smartpai/service/EvidenceAnswerGeneratorTest.java
git commit -m "chore: align visible product copy with paperloom"
```

Expected:

- Commit contains only product naming and paper-library copy changes.

### Task 2: Paper Provenance And Readiness Contract

**Files:**

- Modify: `src/main/java/com/yizhaoqi/smartpai/controller/PaperController.java`
- Modify: `frontend/src/typings/api.d.ts`
- Modify: `frontend/src/views/knowledge-base/index.vue`
- Test: `src/test/java/com/yizhaoqi/smartpai/controller/PaperControllerContractTest.java`

- [ ] **Step 1: Add contract tests for provenance fields**

Extend `PaperControllerContractTest` to verify list/detail paper DTOs expose:

```text
sourceType
evidenceAssetLevel
assetWarnings
pdfEvidenceAvailable
structuredImport
evalImport
```

Expected values:

- A normal uploaded PDF with parser artifact and page screenshots:
  - `sourceType=PDF`
  - `evidenceAssetLevel=PDF_VISUAL`
  - `pdfEvidenceAvailable=true`
  - `structuredImport=false`
  - `evalImport=false`
- A LitSearch or QASPER eval row:
  - `sourceType=EVAL_IMPORT`
  - `evidenceAssetLevel=TEXT_ONLY`
  - `pdfEvidenceAvailable=false`
  - `structuredImport=true`
  - `evalImport=true`
  - `assetWarnings` includes a message that PDF/page visual assets are unavailable.

Run:

```bash
mvn -q -Dtest=PaperControllerContractTest test
```

Expected before implementation:

- The new assertions fail because the DTO fields do not exist.

- [ ] **Step 2: Derive provenance without a database migration**

In `PaperController.convertPapersToResponse`, derive:

```text
evalImport = file.isEval()
structuredImport = file.isEval() || hasText(file.getSourceDataset()) || filename ends with ".json"
sourceType = file.isEval() ? "EVAL_IMPORT" : structuredImport ? "STRUCTURED_IMPORT" : "PDF"
pdfEvidenceAvailable = sourceType == "PDF" and visualAsset.pageScreenshotCount > 0
evidenceAssetLevel = pdfEvidenceAvailable ? "PDF_VISUAL" : structuredImport ? "TEXT_ONLY" : "PDF_PENDING_ASSETS"
assetWarnings = deterministic list based on missing parser artifact, missing page screenshots, or structured import
```

Keep this as response derivation first. Do not add a database migration until a real production import path needs a persisted source type.

Run:

```bash
mvn -q -Dtest=PaperControllerContractTest test
```

Expected:

- The contract tests pass.

- [ ] **Step 3: Update frontend API types**

Extend `Api.Paper.UploadTask` in `frontend/src/typings/api.d.ts` with:

```ts
sourceType?: 'PDF' | 'STRUCTURED_IMPORT' | 'EVAL_IMPORT';
evidenceAssetLevel?: 'PDF_VISUAL' | 'PDF_PENDING_ASSETS' | 'TEXT_ONLY';
assetWarnings?: string[];
pdfEvidenceAvailable?: boolean;
structuredImport?: boolean;
evalImport?: boolean;
```

Run:

```bash
cd frontend && pnpm typecheck
```

Expected:

- Typecheck passes.

- [ ] **Step 4: Show provenance and readiness in paper library**

Update `frontend/src/views/knowledge-base/index.vue` so each row visibly distinguishes:

- PDF with page/visual evidence
- PDF still parsing or missing visual assets
- structured import
- eval import

Display `assetWarnings` as concise status text or tooltip. Disable page/table/figure visual actions when the relevant asset is absent. Keep text evidence actions available for structured imports.

Run:

```bash
cd frontend && pnpm exec eslint src/views/knowledge-base/index.vue
cd frontend && pnpm typecheck
```

Expected:

- ESLint and typecheck pass.

- [ ] **Step 5: Commit provenance contract**

Run:

```bash
git add src/main/java/com/yizhaoqi/smartpai/controller/PaperController.java src/test/java/com/yizhaoqi/smartpai/controller/PaperControllerContractTest.java frontend/src/typings/api.d.ts frontend/src/views/knowledge-base/index.vue
git commit -m "feat: expose paper provenance and evidence readiness"
```

Expected:

- Commit contains backend DTO contract, tests, frontend types, and paper-library readiness display.

### Task 3: Reference Evidence Payload And Frontend Evidence Panel

**Files:**

- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/PaperAnswerService.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/EvidenceToolExecutor.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/ConversationService.java` only if serialization needs explicit compatibility handling.
- Modify: `frontend/src/typings/api.d.ts`
- Modify: `frontend/src/views/chat/modules/source-evidence-panel.vue`
- Modify: `frontend/src/views/chat/modules/reference-evidence-page.vue`
- Test: `src/test/java/com/yizhaoqi/smartpai/service/ChatHandlerReferenceEvidenceTest.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/service/PaperAnswerServiceTest.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/service/ConversationServiceTest.java`

- [ ] **Step 1: Add persisted reference evidence tests**

Extend backend tests to cover these cases:

- reference mapping for PDF-derived text evidence includes `sourceType=PDF`
- reference mapping for eval import includes `sourceType=EVAL_IMPORT`
- missing page screenshot does not remove text evidence
- conversation history reload preserves the new fields in `referenceMappings`

Run:

```bash
mvn -q -Dtest=ChatHandlerReferenceEvidenceTest,PaperAnswerServiceTest,ConversationServiceTest test
```

Expected before implementation:

- New field assertions fail.

- [ ] **Step 2: Extend `ReferenceInfo` contract**

Add fields to `ChatHandler.ReferenceInfo`:

```text
sourceType
evidenceAssetLevel
pdfEvidenceAvailable
structuredImport
evalImport
pageScreenshotAvailable
figureScreenshotAvailable
assetWarnings
```

Populate them from paper metadata and visual-asset availability. Keep old constructors backward-compatible by defaulting:

```text
sourceType=PDF
evidenceAssetLevel=PDF_PENDING_ASSETS
pdfEvidenceAvailable=false
structuredImport=false
evalImport=false
pageScreenshotAvailable=false
figureScreenshotAvailable=false
assetWarnings=[]
```

Run:

```bash
mvn -q -Dtest=ChatHandlerReferenceEvidenceTest,PaperAnswerServiceTest,ConversationServiceTest test
```

Expected:

- Tests pass.
- Existing persisted JSON without these fields still deserializes.

- [ ] **Step 3: Update frontend reference types**

Extend `Api.Chat.ReferenceEvidence` in `frontend/src/typings/api.d.ts` with the same fields and conservative optional types.

Run:

```bash
cd frontend && pnpm typecheck
```

Expected:

- Typecheck passes.

- [ ] **Step 4: Correct evidence panel behavior**

Update `source-evidence-panel.vue` and `reference-evidence-page.vue`:

- PDF visual evidence available: show PDF/page/table/figure actions as supported.
- PDF asset missing: show text evidence and a clear missing-asset state.
- structured/eval import: show text evidence and hide "download original PDF" as the main recovery path.
- table text without table crop: show table text and a "table image unavailable" state.
- figure caption/text without figure crop: show caption/text and a "figure image unavailable" state.

Run:

```bash
cd frontend && pnpm exec eslint src/views/chat/modules/source-evidence-panel.vue src/views/chat/modules/reference-evidence-page.vue
cd frontend && pnpm typecheck
```

Expected:

- ESLint and typecheck pass.

- [ ] **Step 5: Commit reference evidence contract**

Run:

```bash
git add src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java src/main/java/com/yizhaoqi/smartpai/service/PaperAnswerService.java src/main/java/com/yizhaoqi/smartpai/service/EvidenceToolExecutor.java src/main/java/com/yizhaoqi/smartpai/service/ConversationService.java src/test/java/com/yizhaoqi/smartpai/service/ChatHandlerReferenceEvidenceTest.java src/test/java/com/yizhaoqi/smartpai/service/PaperAnswerServiceTest.java src/test/java/com/yizhaoqi/smartpai/service/ConversationServiceTest.java frontend/src/typings/api.d.ts frontend/src/views/chat/modules/source-evidence-panel.vue frontend/src/views/chat/modules/reference-evidence-page.vue
git commit -m "feat: distinguish pdf and structured reference evidence"
```

Expected:

- Commit contains reference evidence payload and frontend display changes only.

### Task 4: Planner Ownership And Anti-Hardcode Guardrail

**Files:**

- Modify: `src/main/java/com/yizhaoqi/smartpai/service/EvidencePlanner.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/PaperQueryPlanner.java`
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/PaperRetrievalService.java` only if forced intent call sites need clarification.
- Modify: `src/main/java/com/yizhaoqi/smartpai/service/AgentToolRegistry.java` only if direct tool paths still bypass paper discovery.
- Test: `src/test/java/com/yizhaoqi/smartpai/service/EvidencePlannerTest.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/service/PaperQueryPlannerTest.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/service/PaperRetrievalServiceTest.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/service/PaperChatRouterTest.java`

- [ ] **Step 1: Add ownership tests**

Add tests proving:

- `PaperChatRouter` does not route paper recommendations by phrase list.
- `EvidencePlanner` can choose `DISCOVER_PAPERS` for natural topic requests through LLM planner output.
- `PaperRetrievalService.discoverPapers(...)` forces `LITERATURE_SEARCH`.
- `PaperQueryPlanner.plan(query)` does not own top-level paper discovery routing for normal QA paths.
- `PaperQueryPlanner.plan(query, LITERATURE_SEARCH)` still normalizes literature-search topics for paper-level retrieval.

Run:

```bash
mvn -q -Dtest=PaperChatRouterTest,EvidencePlannerTest,PaperQueryPlannerTest,PaperRetrievalServiceTest test
```

Expected before implementation:

- Tests fail wherever `PaperQueryPlanner` still upgrades unforced queries to `LITERATURE_SEARCH`.

- [ ] **Step 2: Make task classification single-owner**

Adjust behavior so:

- `EvidencePlanner` owns `DISCOVER_PAPERS` versus `SEARCH_EVIDENCE`.
- `PaperRetrievalService.discoverPapers(...)` passes forced `LITERATURE_SEARCH`.
- `PaperQueryPlanner` keeps query expansion for method, experiment, limitation, summary, and forced literature search.
- Unforced `PaperQueryPlanner.plan(...)` does not turn recommendation phrases into top-level literature search.

Keep deterministic actions for reference and trusted page anchors.

Run:

```bash
mvn -q -Dtest=PaperChatRouterTest,EvidencePlannerTest,PaperQueryPlannerTest,PaperRetrievalServiceTest,EvidenceToolExecutorTest,PaperAnswerServiceTest test
```

Expected:

- Tests pass.
- Recommendation queries still go through `DISCOVER_PAPERS` when selected by `EvidencePlanner`.
- Scoped QA queries do not leak into paper discovery because of surface phrases alone.

- [ ] **Step 3: Add regression case for the previous failure**

Ensure the product rescue smoke includes a case for:

```text
有什么agent相关的论文吗？
```

Acceptance:

- route/action resolves to paper discovery.
- returned sources are relevant paper candidates.
- forbidden unrelated paper families such as diabetes, hate speech, or VQA do not appear.
- scope leak remains 0.

Run the existing product smoke command from `eval/rag/README.md` or the local harness command used for `current-evidence-ledger`.

Expected:

- Product smoke passes.

- [ ] **Step 4: Commit planner ownership**

Run:

```bash
git add src/main/java/com/yizhaoqi/smartpai/service/EvidencePlanner.java src/main/java/com/yizhaoqi/smartpai/service/PaperQueryPlanner.java src/main/java/com/yizhaoqi/smartpai/service/PaperRetrievalService.java src/main/java/com/yizhaoqi/smartpai/service/AgentToolRegistry.java src/test/java/com/yizhaoqi/smartpai/service/PaperChatRouterTest.java src/test/java/com/yizhaoqi/smartpai/service/EvidencePlannerTest.java src/test/java/com/yizhaoqi/smartpai/service/PaperQueryPlannerTest.java src/test/java/com/yizhaoqi/smartpai/service/PaperRetrievalServiceTest.java eval/rag/product-rescue-smoke.jsonl
git commit -m "refactor: keep paper discovery routing in evidence planner"
```

Expected:

- Commit contains planner/query/retrieval test and implementation changes only.

### Task 5: Real PDF Parser Smoke Benchmark

**Files:**

- Create: `eval/rag/pdf-parser/README.md`
- Create: `eval/rag/pdf-parser/product-pdf-smoke-manifest.jsonl`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/ProductPdfParserSmokeCli.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/ProductPdfParserSmokeRunner.java`
- Create: `src/test/java/com/yizhaoqi/smartpai/eval/ProductPdfParserSmokeRunnerTest.java`
- Modify: `eval/rag/harnesses.yaml`
- Modify: `eval/rag/README.md`
- Modify: `eval/rag/CHEATSHEET.md` only after a real run produces a score row.

- [ ] **Step 1: Add a parser smoke manifest**

Create `eval/rag/pdf-parser/product-pdf-smoke-manifest.jsonl` with one JSON object per checked PDF. Use fields:

```json
{"id":"paismart_pdf_smoke","path":"docs/paismart.pdf","expectedMinChunks":1,"expectedMinPages":1,"expectedParser":"mineru-or-configured","requiresTableOrFigure":false}
```

If `docs/paismart.pdf` is not representative enough for tables or figures, add a second local PDF path only when that file is committed or documented as ignored local fixture input.

- [ ] **Step 2: Add a runner that checks product parser outputs**

Implement `ProductPdfParserSmokeRunner` as a test-scoped runner that can:

- read the manifest
- locate the matching `Paper` row by file hash or configured `paperId`
- verify processing status is not failed
- verify chunks exist
- verify parser artifact exists for PDF-derived papers
- verify page screenshot count when PDF visual evidence is required
- verify table/figure/formula asset counts when manifest fields require them
- write a concise scorecard with pass/fail and failure reasons

The runner must not treat QASPER or LitSearch rows as PDF parser evidence.

- [ ] **Step 3: Add unit tests for runner classification**

In `ProductPdfParserSmokeRunnerTest`, cover:

- PDF row with chunks, parser artifact, and page screenshot passes.
- PDF row with chunks but missing parser artifact fails.
- Eval import row with `.json` filename is rejected as parser-smoke evidence.
- Missing page screenshot fails only when the manifest requires visual page evidence.

Run:

```bash
mvn -q -Dtest=ProductPdfParserSmokeRunnerTest test
```

Expected:

- Tests pass.

- [ ] **Step 4: Document the runtime command**

In `eval/rag/pdf-parser/README.md`, document:

```bash
mvn -q -DskipTests test-compile
java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" \
  com.yizhaoqi.smartpai.eval.ProductPdfParserSmokeCli \
  --manifest eval/rag/pdf-parser/product-pdf-smoke-manifest.jsonl \
  --runs-root eval/rag/runs
```

Document that MinerU must be available when the smoke imports or reparses a PDF. If the smoke only
checks already processed rows, document the required precondition: the PDF must already be uploaded
and parsed by the active runtime.

- [ ] **Step 5: Commit parser smoke benchmark**

Run:

```bash
git add eval/rag/pdf-parser/README.md eval/rag/pdf-parser/product-pdf-smoke-manifest.jsonl src/test/java/com/yizhaoqi/smartpai/eval/ProductPdfParserSmokeCli.java src/test/java/com/yizhaoqi/smartpai/eval/ProductPdfParserSmokeRunner.java src/test/java/com/yizhaoqi/smartpai/eval/ProductPdfParserSmokeRunnerTest.java eval/rag/harnesses.yaml eval/rag/README.md
git commit -m "test: add real pdf parser smoke benchmark"
```

Expected:

- Commit adds a PDF parser benchmark that cannot be satisfied by structured eval imports.

### Task 6: Figure And Table Evidence Gate

**Files:**

- Create: `eval/rag/figure-table/README.md`
- Create: `eval/rag/figure-table/product-figure-table-smoke.jsonl`
- Modify: `eval/rag/harnesses.yaml`
- Modify: `eval/rag/STRATEGY_READINESS.md`
- Modify: `frontend/src/views/chat/modules/source-evidence-panel.vue` only if Task 3 did not fully cover figure/table missing-asset states.
- Test: `src/test/java/com/yizhaoqi/smartpai/service/PaperAnswerServiceTest.java`
- Test: `src/test/java/com/yizhaoqi/smartpai/service/ChatHandlerReferenceEvidenceTest.java`

- [ ] **Step 1: Add figure/table smoke cases**

Create `product-figure-table-smoke.jsonl` with cases that require:

- table text evidence with citation
- table crop available when the PDF generated one
- figure caption/text evidence with citation
- figure crop available when the PDF generated one
- graceful text-only fallback when crop is missing

Use committed product PDFs only. If no committed figure-bearing PDF exists, create the harness file
with cases for existing table evidence and document that the figure-bearing fixture must be added
before claiming figure-heavy QA support.

- [ ] **Step 2: Add answer/reference tests for table and figure evidence**

Extend tests so:

- table evidence keeps `tableText`, `tableMarkdown`, `tableId`, and crop availability.
- figure evidence keeps `figureId`, caption/snippet, and crop availability.
- answer generation does not claim visual inspection when only text evidence exists.

Run:

```bash
mvn -q -Dtest=PaperAnswerServiceTest,ChatHandlerReferenceEvidenceTest test
```

Expected:

- Tests pass.

- [ ] **Step 3: Update readiness document**

Update `eval/rag/STRATEGY_READINESS.md`:

- Keep current text/table strategy marked proven for current scope.
- Keep figure-heavy QA marked unproven until the new figure/table smoke has passing runs.
- Do not promote multimodal claims based only on data structures or UI support.

- [ ] **Step 4: Commit figure/table gate**

Run:

```bash
git add eval/rag/figure-table/README.md eval/rag/figure-table/product-figure-table-smoke.jsonl eval/rag/harnesses.yaml eval/rag/STRATEGY_READINESS.md src/test/java/com/yizhaoqi/smartpai/service/PaperAnswerServiceTest.java src/test/java/com/yizhaoqi/smartpai/service/ChatHandlerReferenceEvidenceTest.java frontend/src/views/chat/modules/source-evidence-panel.vue
git commit -m "test: gate table and figure evidence claims"
```

Expected:

- Commit creates an explicit gate for table/figure evidence claims.

### Task 7: Benchmark Gate Script And Score Discipline

**Files:**

- Create: `scripts/paperloom-rag-gates.sh`
- Modify: `eval/rag/README.md`
- Modify: `eval/rag/harnesses.yaml`
- Modify: `eval/rag/CHEATSHEET.md` only when a real run updates the ledger.

- [ ] **Step 1: Add a gate script**

Create `scripts/paperloom-rag-gates.sh` with modes:

```text
product-smoke
qasper-dev-200
litsearch-full-summary
pdf-parser-smoke
all-light
```

The script must:

- run from repo root
- call existing Maven test/eval commands where available
- refuse to label partial LitSearch samples as `litsearch-full`
- print the run artifact path for each executed gate
- return non-zero on failed product smoke, citation mapping failure, or scope leak

- [ ] **Step 2: Document gate usage**

Update `eval/rag/README.md` with:

```bash
scripts/paperloom-rag-gates.sh product-smoke
scripts/paperloom-rag-gates.sh all-light
scripts/paperloom-rag-gates.sh litsearch-full-summary
```

State clearly:

- `all-light` is for daily development.
- `litsearch-full-summary` summarizes existing full state and should not be treated as a fresh full run unless it invokes the full benchmark.
- PDF parser smoke requires real PDF parser/runtime preconditions.

- [ ] **Step 3: Add script smoke test**

If the repo has shell test infrastructure, add a shell test. If not, add a dry-run mode:

```bash
scripts/paperloom-rag-gates.sh --dry-run all-light
```

Expected:

- Prints exact commands without executing long benchmarks.
- Exits 0.

- [ ] **Step 4: Commit gate script**

Run:

```bash
git add scripts/paperloom-rag-gates.sh eval/rag/README.md eval/rag/harnesses.yaml
git commit -m "chore: add paperloom rag gate runner"
```

Expected:

- Commit adds benchmark command discipline without rewriting existing harnesses.

### Task 8: Runtime Browser Verification

**Files:**

- No source changes unless the browser check exposes a bug.

- [ ] **Step 1: Compile backend without restarting**

Run from the active RAG worktree:

```bash
mvn -q -DskipTests compile
```

Expected:

- Compile succeeds.
- If backend hot reload is active, latest classes are available without manual restart.

- [ ] **Step 2: Verify frontend typecheck**

Run:

```bash
cd frontend && pnpm typecheck
```

Expected:

- Typecheck succeeds.

- [ ] **Step 3: Browser check paper library and chat**

Open:

```text
http://localhost:9527/#/chat
http://localhost:9527/#/chat-history
```

Verify:

- visible product name is PaperLoom
- retrieval budget selector still shows fast/high-recall/audit modes
- WebSocket connects
- query `有什么agent相关的论文吗？` returns paper recommendations from accessible library
- each recommendation has a clickable citation
- citation opens evidence with correct source type
- structured/eval import evidence does not show fake PDF page controls
- page/table/figure asset absence is shown as a state, not as a broken promise

- [ ] **Step 4: Inspect network responses**

Inspect browser network:

- `/proxy-default/.../papers` response includes provenance/readiness fields.
- WebSocket answer payload includes updated `referenceMappings`.
- Reference detail endpoint preserves source/evidence fields after history reload.

Expected:

- Browser evidence matches backend response fields.
- No console errors from the touched views.

### Task 9: Integration And Merge Discipline

**Files:**

- No source changes unless merge conflicts require conflict resolution.

- [ ] **Step 1: Run targeted backend tests**

Run:

```bash
mvn -q -Dtest=EvidenceAnswerGeneratorTest,PaperControllerContractTest,ChatHandlerReferenceEvidenceTest,ConversationServiceTest,PaperChatRouterTest,EvidencePlannerTest,PaperQueryPlannerTest,PaperRetrievalServiceTest,PaperAnswerServiceTest,ProductPdfParserSmokeRunnerTest test
```

Expected:

- All targeted tests pass.

- [ ] **Step 2: Run product smoke**

Run the current product smoke command documented in `eval/rag/README.md` or via:

```bash
scripts/paperloom-rag-gates.sh product-smoke
```

Expected:

- Product smoke pass rate is 100%.
- Citation mapping is 100%.
- Scope leak is 0.

- [ ] **Step 3: Run light benchmark gate**

Run:

```bash
scripts/paperloom-rag-gates.sh all-light
```

Expected:

- Product smoke passes.
- Fast structured checks pass.
- Script does not label partial LitSearch as full.

- [ ] **Step 4: Record score movement**

Update benchmark docs only with real run artifacts:

- `eval/rag/CHEATSHEET.md`
- `eval/rag/STRATEGY_READINESS.md`
- relevant benchmark README files

Expected:

- Every score row points to an actual run directory.
- The docs still state QASPER/LitSearch do not prove OCR/page evidence.

- [ ] **Step 5: Prepare merge**

Run:

```bash
git status --short --branch
git log --oneline --decorate --max-count=8
```

Expected:

- RAG correction branch has small commits matching the tasks above.
- Main worktree unrelated dirty frontend/theme files are not part of the RAG correction branch.
- No untracked benchmark raw datasets are staged.

## Promotion Rules

The correction is ready to merge only when:

- product docs exist in the branch being merged
- visible product copy says PaperLoom in paper workflows
- backend paper DTOs expose provenance/readiness
- referenceMappings persist source/evidence provenance
- frontend evidence panels distinguish PDF, structured import, and missing assets
- planner ownership avoids production phrase hardcoding
- Product smoke is 100%
- citation mapping is 100%
- scope leak is 0
- PDF parser smoke exists and cannot be satisfied by structured eval imports
- QASPER/LitSearch docs remain scoped to retrieval and structured QA claims

## Explicit Non-Promotions

Do not promote these as production-default capabilities in this correction cycle:

- external VectifyAI/PageIndex integration
- arbitrary `LOCATE_PAGES`
- web-scale paper search
- figure-heavy multimodal QA claims without the figure/table gate passing
- robust author/venue/DOI extraction from arbitrary PDFs

## Self-Review

Spec coverage:

- Product boundary: covered by Task 0 and Task 1.
- PDF-first ingestion and structured import distinction: covered by Task 2 and Task 3.
- MinerU/parser policy and visible readiness: covered by Task 2 and Task 5.
- Retrieval strategy and anti-hardcode routing: covered by Task 4.
- Frontend citation evidence display: covered by Task 3 and Task 8.
- Benchmark layering: covered by Task 5, Task 6, Task 7, and Task 9.
- Current implementation drift: covered by Task 1 through Task 7.

Placeholder scan target: run the standard writing-plans placeholder scan against this file and
confirm it reports no plan-content matches. Avoid putting the searched placeholder phrases directly
inside this document, because that makes the scan match the instruction text instead of the plan.
