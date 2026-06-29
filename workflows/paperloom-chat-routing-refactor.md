# PaperLoom Chat Routing Refactor Workflow

## Status

Active refactor strategy. User-confirmed direction on 2026-06-29:

- development phase accepts no compromise fixes
- keep LLM intent recognition and make it the primary semantic router
- no production hardcoded phrase matching for route recognition
- no silent fallback that continues as the wrong task
- benchmark/eval data and orphan chunks must not appear in product retrieval

Current working-tree checkpoint on 2026-06-29:

- first backend guardrail slice has been implemented in the working tree
- the original WebSocket request `有多少论文可以检索` now routes to `LIBRARY_STATUS`
- orphan product rows for stale paper id `2667ad129bc904dfe930ca83d3642b96` have been cleaned from
  product MySQL tables after guardrails were in place
- production parser chunk evidence-role assignment no longer uses fixed text words such as
  "experiment", "accuracy", "evaluation", or "benchmark"
- product discovery reranking no longer uses a static stopword list that drops domain terms such as
  `method`
- normal paper-content WebSocket QA and product REST hybrid-search leak checks have fresh runtime
  verification after the latest code changes
- old fallback vocabulary was removed from the planner failure path, and the search tool now reports
  query expansion as `queryExpansionUsed` instead of `fallbackUsed`
- durable conversation scope, collection management, and large-library source picking remain a
  separate follow-up refactor track

## Purpose

Refactor PaperLoom chat from a default evidence-retrieval harness into a typed, LLM-routed,
capability-based system that refuses unsafe execution when it does not understand the user task.

This workflow exists because the current system answered `有多少论文可以检索` by searching paper chunks
and citing unrelated evidence. The fix must address the system shape, not a surface phrase.

## Trigger

Event-triggered: run when PaperLoom chat routes a user request to the wrong capability, produces a
fallback evidence summary after an upstream failure, or returns data from product retrieval that is
not part of the current product paper source of truth.

The immediate run is:

```text
Incident: "有多少论文可以检索" returned cited paper evidence instead of product library status.
Goal: redesign routing, fallback, and product retrieval source-of-truth behavior.
```

## Non-negotiable Rules

- Do not implement production route recognition with fixed phrase lists.
- Do not implement production semantic matching with static regex or token lists. This includes
  route recognition, paper QA sub-intent detection, query expansion, query cleanup, evidence
  sufficiency checks, unsupported-claim checks, and parser evidence-role inference from text words.
- Do not remove LLM intent recognition. Promote it to the primary semantic router.
- Do not let LLM classification failure fall through to `PAPER_QA`.
- Do not let planner failure fall through to `SEARCH_EVIDENCE`.
- Do not let answer-generation failure render a pseudo-answer from retrieved snippets.
- Do not let product chat retrieve orphan chunks or eval/benchmark data.
- Do not let `AUTO_SOURCE_QA` reach retrieval execution. It is an unresolved state, not an
  executable task.

## Target Shape

### Deep module: `TaskRouter`

Interface:

```text
route(TaskRoutingRequest) -> TaskRoutingResult
```

`TaskRoutingRequest` includes:

- user id
- conversation id
- user message
- locked conversation scope
- optional reference focus
- retrieval budget profile

`TaskRoutingResult` is either:

- `TaskDecision`
- `TaskRoutingFailure`

`TaskDecision` contains:

- `taskType`: one of `SMALLTALK`, `CLARIFY`, `LIBRARY_STATUS`, `PAPER_DISCOVERY`, `PAPER_QA`,
  `REFERENCE_QA`, `FOLLOW_UP`
- `operation`: task-specific operation, such as `COUNT_SEARCHABLE_PAPERS`,
  `LIST_ACCESSIBLE_PAPERS`, `SEARCH_PAPER_METADATA`, `ANSWER_FROM_EVIDENCE`, or
  `INSPECT_REFERENCE`
- normalized query or structured arguments when needed
- confidence
- reason

Routing failure contains:

- reason code, such as `LLM_UNAVAILABLE`, `INVALID_JSON`, `LOW_CONFIDENCE`, `UNSUPPORTED_TASK`
- user-visible failure message
- diagnostics

Invariant:

- No routing failure may execute retrieval.

### Deep module: `CapabilityExecutor`

Interface:

```text
execute(TaskDecision) -> CapabilityResult
```

Execution is deterministic. It does not infer user intent.

Capability mapping:

- `LIBRARY_STATUS` -> product library metadata and index consistency services
- `PAPER_DISCOVERY` -> paper-level metadata retrieval, then representative evidence only if needed
- `PAPER_QA` -> scoped chunk evidence retrieval and evidence-grounded answer generation
- `REFERENCE_QA` -> persisted reference mapping or explicit reference focus
- `FOLLOW_UP` -> prior resolved focus, otherwise clarification
- `CLARIFY` and `SMALLTALK` -> direct renderer

### Deep module: `PaperLibraryStatusService`

Interface:

```text
statusFor(userId, scope) -> PaperLibraryStatus
```

The implementation reports:

- accessible product paper count
- searchable product paper count
- parsing/indexing/failed counts
- selected session scope count
- index consistency problems, including orphan chunks and missing `paper_search` docs

This module reads product paper source-of-truth state. It does not query chunks to answer counts.

Scope rule:

- `LIBRARY_STATUS` is a control-plane capability, not a retrieval capability.
- It is valid in both `AUTO_LIBRARY` and `SOURCE_SET_SNAPSHOT` conversations.
- In a locked source-set conversation, it must not mutate the session scope and must not run chunk
  retrieval. It should report the selected session scope count when the user asks about the current
  session/source set, and report the accessible/searchable product-library count when the user asks
  about the library as a whole.
- A locked source-set session must not force all non-content questions into `PAPER_QA`.

### Deep module: `ProductPaperCorpus`

Interface:

```text
resolveAccessibleSearchablePaperIds(userId, scope) -> ProductPaperSet
```

All product retrieval paths receive this allowlist before querying Elasticsearch.

Invariant:

- A chunk result whose `paperId` is not in the resolved product paper set is not product evidence.

## Refactor Strategy

Strategy summary:

```text
fail closed first -> introduce typed task routing -> add explicit library-status capability ->
enforce product paper allowlist -> clean local orphan data -> verify from browser/runtime
```

This ordering is intentional. The current incident is not only missing a count endpoint. The system
is unsafe because several control-flow failures are allowed to continue into retrieval and answer
rendering. Those paths must be blocked before capability expansion, otherwise new capabilities will
still inherit the same failure mode.

## Current State

### Implemented in the current working tree

- `TaskRouter` / `LlmTaskRouter` exist as the primary semantic routing boundary for
  `AUTO_SOURCE_QA`.
- `AUTO_SOURCE_QA` is treated as unresolved state and is not intended to execute retrieval.
- `LIBRARY_STATUS` exists as a typed task and renders from product metadata/status services, not
  from chunk retrieval.
- `ProductPaperCorpus` exists and is used by product retrieval to resolve accessible/searchable
  product paper ids before Elasticsearch search.
- The product hybrid-search REST path now goes through `PaperRetrievalService`, so it shares the
  product paper allowlist instead of directly bypassing retrieval guardrails.
- The stale `PaperIntentClassifier` path has been removed from production/test references.

### Remaining follow-up after this guardrail slice

- Implement durable backend-owned conversation scope, immutable source-set snapshots, collections,
  and server-side source picking for large paper libraries.
- Run browser verification when frontend scope/source-picker changes are touched.

### Runtime verification already captured

- WebSocket request:
  `{"type":"chat","message":"有多少论文可以检索","retrievalBudgetProfile":"interactive"}`
- Latest generation id: `8ffaffb1-1eea-4136-8efe-b696307a456a`
- Conversation id: `882f218b-8ec7-4344-9e4e-8b1ff2028b38`
- Completion diagnostics:
  - `route=LIBRARY_STATUS`
  - `scopeMode=AUTO_SOURCE`
  - `scannedCount=0`
  - `acceptedEvidenceCount=0`
  - `sourceCount=0`
  - `plannerRounds=0`
  - `fallbackUsed=false`
- Rendered answer reported 2 searchable papers and contained no citations or source snippets.
- Product DB orphan counts after cleanup:
  - `paper_text_chunks=0`
  - `paper_visual_assets=0`
  - `paper_parser_artifacts=0`
- Product ES status after cleanup:
  - `paper_chunks=278` across the two product papers
  - `paper_search=2`
- Eval/admin preserved:
  - `paperloom_eval.eval_papers=64254`
  - `paperloom_eval.eval_chunks=1480777`
  - admin user count remains 1
- REST hybrid search after backend reload:
  - query `attention`
  - HTTP status `200`
  - result count `187`
  - paper ids: `6071803f93e4573eb7e0fb2c4e65976a`,
    `e15dfcc38059fc915434a17ef6875bcb`
  - orphan id `2667ad129bc904dfe930ca83d3642b96` not returned
- Normal paper-content WebSocket QA after backend reload:
  - generation id `e11550c8-e685-4052-b604-8fcd93f72dec`
  - route `PAPER_QA`
  - `acceptedEvidenceCount=129`
  - `sourceCount=2`
  - `fallbackUsed=false`
  - answer rendered citations `[1]` and `[2]`
  - reference mapping count `2`
- Backend verification after hardcoded semantic matching cleanup:
  - targeted routing/retrieval/parser test suite passed
  - `mvn -q -DskipTests compile` passed
  - `git diff --check` passed
- Cleanup after guardrail verification:
  - `EvidencePlanner.fallbackAction(...)` renamed to `plannerFailure(...)`
  - `AgentToolRegistry` search tool diagnostic renamed query expansion from `fallbackUsed` to
    `queryExpansionUsed`

### Separate follow-up refactor track

The chat-routing incident is not the whole product refactor. The next product-level track is:

- backend-owned conversation scope storage and locking
- immutable `SOURCE_SET_SNAPSHOT` sessions
- collection tables and collection membership APIs
- server-side paper-level source picker for large libraries
- source changes creating a new session instead of mutating an active session
- frontend read-only locked-scope display and collection/source-set UX

### Phase 1: Make wrong execution impossible

Goal: remove unsafe silent fallbacks before adding new capabilities.

Changes:

- Remove old classifier fallback paths that convert uncertainty into `PAPER_QA`; route uncertainty
  must become an explicit routing failure or `CLARIFY`.
- Replace planner fallback to `SEARCH_EVIDENCE` with planner failure.
- Replace QA fallback evidence-summary rendering with answer-generation failure.
- Update tests that currently expect fallback pseudo-answers.
- Keep failure responses user-visible and citation-free.
- Preserve diagnostics so routing/generation failures are visible in logs and benchmark output.

Acceptance:

- Invalid classifier JSON does not call retrieval.
- Classifier quota failure does not call retrieval.
- Planner invalid JSON does not call retrieval.
- Answer-generation quota failure does not produce citations or evidence-summary markdown.
- `AUTO_SOURCE_QA` is never executed as a retrieval task.

### Phase 2: Introduce typed task routing

Goal: make LLM intent recognition the primary semantic routing module.

Changes:

- Create `TaskRouter`, `TaskRoutingRequest`, `TaskRoutingResult`, `TaskDecision`, and
  `TaskRoutingFailure`.
- Move semantic route ownership out of `PaperAnswerService`.
- Keep `PaperChatRouter` only for structural, non-semantic cases if still needed:
  explicit reference focus, locked manual scope, smalltalk fast path, and low-information follow-up.
- Ensure `AUTO_SOURCE_QA` is not an executable downstream route.
- The router prompt owns product task semantics, including the distinction between:
  - library status/control-plane requests
  - paper discovery/recommendation
  - paper-content QA
  - citation/reference follow-up
  - clarification
- The router must return typed JSON only. Invalid JSON, low confidence, unsupported route, or quota
  failure becomes `TaskRoutingFailure`.

Acceptance:

- The task router can produce `PAPER_QA` for explicit paper-content questions.
- The task router can produce `PAPER_DISCOVERY` for paper recommendation/discovery.
- The task router can produce `LIBRARY_STATUS` for library counts and searchability/status requests.
- Routing failure returns a user-visible failure and diagnostics.
- No production code recognizes `有多少论文可以检索` or similar status requests with fixed phrase
  lists.

### Phase 3: Add library status capability

Goal: answer library count/status questions without chunk retrieval or citations.

Changes:

- Create `PaperLibraryStatusService`.
- Add capability execution for `LIBRARY_STATUS`.
- Render count/status answers directly from metadata.
- Include consistency diagnostics when product chunks/indexes disagree with product paper rows.
- Treat `LIST_LIBRARY` as a deterministic capability under `LIBRARY_STATUS` or `PAPER_DISCOVERY`,
  not as a planner-discovered semantic shortcut.

Acceptance:

- `有多少论文可以检索` returns the current searchable product paper count.
- The answer has zero references.
- The answer has zero accepted evidence chunks.
- The route diagnostics show `LIBRARY_STATUS`.
- The runtime does not call chunk retrieval for this task.
- It can report parse/index/searchable/failed counts without reading `paper_chunks`.

### Phase 4: Enforce product paper source of truth

Goal: prevent orphan chunks from being searchable product evidence.

Changes:

- Create `ProductPaperCorpus`.
- Resolve accessible and searchable product paper ids before chunk retrieval.
- Apply the resolved allowlist to ES queries.
- Filter any returned chunk whose `paperId` is not in the product paper set.
- Add consistency checks for:
  - `paper_text_chunks` without `file_upload`
  - `paper_visual_assets` without `file_upload`
  - parser artifacts without `file_upload`
  - `paper_chunks` ES docs without product paper rows
  - product paper rows missing `paper_search` docs

Acceptance:

- Current orphan paper id `2667ad129bc904dfe930ca83d3642b96` is not retrievable through product chat.
- Product library count and searchable retrieval universe agree.
- Product retrieval can still retrieve valid `lora.pdf` evidence for real paper-content questions.
- Eval indices and eval schemas remain usable for benchmarks but are never included in product chat.

### Phase 5: Clean local orphan data after guardrails exist

Goal: remove current bad runtime state without relying on cleanup as the fix.

Changes:

- Add a guarded local/dev cleanup or repair script for orphan product paper data.
- Run it only after retrieval guardrails are implemented.
- Record before/after counts in QA notes.

Acceptance:

- `file_upload`, `paper_text_chunks`, `paper_chunks`, `paper_search`, parser artifacts, and visual
  assets agree on product paper ids.
- Admin account and eval corpora remain untouched.

### Phase 6: Browser and runtime verification

Goal: prove the product behavior from the user surface.

Verification path:

- Open `http://localhost:9527/#/chat`.
- Ask `有多少论文可以检索`.
- Inspect WebSocket completion diagnostics.
- Inspect network responses and console.
- Check DB and ES counts.

Acceptance:

- Answer is product library status, not evidence QA.
- No citations.
- No random paper snippets.
- No chunk retrieval for library status.
- Paper-content questions still route to `PAPER_QA`.
- Paper recommendation questions still route to `PAPER_DISCOVERY`.

## Implementation Order

Use test-driven slices. Do not start with broad rewrites.

1. Add failing tests for fail-closed behavior.
2. Implement typed routing result and route failure without changing retrieval semantics yet.
3. Integrate `TaskRouter` at the current `PaperAnswerService.answer(...)` entry point.
4. Add `LIBRARY_STATUS` and `PaperLibraryStatusService`.
5. Add `ProductPaperCorpus` and apply the allowlist before chunk retrieval.
6. Update planner so it plans only inside an already-decided capability.
7. Remove or quarantine obsolete fallback tests.
8. Run targeted backend tests and compile.
9. Verify the original browser incident.

Recommended first test targets:

- `PaperAnswerServiceTest`
- `EvidencePlannerTest`
- new `TaskRouterTest`
- new `PaperLibraryStatusServiceTest`
- new `ProductPaperCorpusTest`

## Module Boundaries

`TaskRouter`:

- owns semantic intent recognition
- may call LLM
- returns `TaskDecision` or `TaskRoutingFailure`
- never performs retrieval
- never silently converts failure to `PAPER_QA`

`CapabilityExecutor`:

- executes a valid `TaskDecision`
- does not infer semantic intent
- dispatches to library status, paper discovery, paper QA, reference QA, clarify, or smalltalk

`EvidencePlanner`:

- plans evidence operations inside a known capability
- may normalize retrieval query text
- must not decide top-level task semantics
- must not fallback to `SEARCH_EVIDENCE` when invalid or unavailable

`PaperLibraryStatusService`:

- reads product paper metadata and index consistency state
- answers count/status/searchability questions
- returns zero citations and zero evidence chunks

`ProductPaperCorpus`:

- resolves accessible/searchable product `paperIds`
- intersects manual/session scope with product source of truth
- is the mandatory allowlist before product ES chunk retrieval

## Current Hotspots

These files are expected first-change hotspots:

- `src/main/java/com/yizhaoqi/smartpai/service/PaperAnswerService.java`
- `src/main/java/com/yizhaoqi/smartpai/service/EvidencePlanner.java`
- `src/main/java/com/yizhaoqi/smartpai/service/EvidenceToolExecutor.java`
- `src/main/java/com/yizhaoqi/smartpai/service/PaperRetrievalService.java`
- `src/main/java/com/yizhaoqi/smartpai/service/HybridSearchService.java`

Existing tests that currently expect fallback pseudo-answers must be rewritten. A test that asserts
`fallbackUsed=true` after quota failure is now asserting the wrong product behavior unless it belongs
to an eval-only benchmark metric that is explicitly measuring historical behavior.

## Files Expected To Change

Likely create:

- `src/main/java/com/yizhaoqi/smartpai/service/TaskRouter.java`
- `src/main/java/com/yizhaoqi/smartpai/service/TaskRoutingRequest.java`
- `src/main/java/com/yizhaoqi/smartpai/service/TaskRoutingResult.java`
- `src/main/java/com/yizhaoqi/smartpai/service/TaskDecision.java`
- `src/main/java/com/yizhaoqi/smartpai/service/TaskRoutingFailure.java`
- `src/main/java/com/yizhaoqi/smartpai/service/TaskType.java`
- `src/main/java/com/yizhaoqi/smartpai/service/TaskOperation.java`
- `src/main/java/com/yizhaoqi/smartpai/service/PaperLibraryStatusService.java`
- `src/main/java/com/yizhaoqi/smartpai/service/ProductPaperCorpus.java`

Likely modify:

- `src/main/java/com/yizhaoqi/smartpai/service/ChatHandler.java`
- `src/main/java/com/yizhaoqi/smartpai/service/PaperAnswerService.java`
- `src/main/java/com/yizhaoqi/smartpai/service/EvidencePlanner.java`
- `src/main/java/com/yizhaoqi/smartpai/service/EvidenceToolExecutor.java`
- `src/main/java/com/yizhaoqi/smartpai/service/PaperRetrievalService.java`
- `src/main/java/com/yizhaoqi/smartpai/service/HybridSearchService.java`
- `src/test/java/com/yizhaoqi/smartpai/service/*`

## Decisions

- Fail closed first. During development, explicit failure is preferable to wrong retrieval.
- Keep LLM intent recognition. The problem is that it is late and fallback-prone, not that it exists.
- Do not implement status routing with phrase lists. The LLM router must understand the semantic
  task and emit typed decisions.
- Do not clean orphan product data before guardrails exist. Cleanup is a final state repair, not the
  safety mechanism.
- Do not add `is_eval` or `includeEval` to product paths. Eval remains a separate data domain.

## Non-Goals For This Refactor

- Do not redesign OCR/provider selection. MinerU remains the main OCR/parser path.
- Do not change benchmark corpus storage except to keep it isolated from product retrieval.
- Do not solve every frontend scope-selection issue in this slice unless needed for verification.
- Do not remove retrieval or planner modules wholesale; narrow their responsibility and block unsafe
  fallbacks first.
