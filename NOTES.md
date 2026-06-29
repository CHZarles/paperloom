# PaperLoom Working Notes

## User-confirmed constraints

- Product is in development. Do not accept compromise or trade-off fixes when the correct design is known.
- Do not use production hardcoded semantic matching. Static regex or token lists must not drive
  route recognition, paper QA sub-intent detection, query expansion, query cleanup, evidence
  sufficiency checks, unsupported-claim checks, or parser evidence-role inference from text words.
  Hardcode is acceptable only as a temporary debug probe and must not remain committed.
- Do not design silent fallback paths that make the system continue as if it understood the user.
- LLM intent recognition should remain part of the system, but it must be the primary semantic router, not a late optional patch.
- If LLM intent recognition fails, the system must not default to `PAPER_QA` or perform retrieval.
- Planner should not do semantic intent recognition after the router has already produced a task decision.
- Product paper retrieval must use product PDF-derived papers only, and product source-of-truth must not include orphan chunks or eval data.

## Terms

- **Task Router**: the module that uses LLM-based structured intent recognition to convert a user message plus session context into a valid task decision.
- **Task Decision**: the typed output of routing, such as `LIBRARY_STATUS`, `PAPER_DISCOVERY`, `PAPER_QA`, `REFERENCE_QA`, `FOLLOW_UP`, `CLARIFY`, or `SMALLTALK`.
- **Capability Executor**: deterministic code that executes a task decision. It does not infer user intent.
- **Library Status**: product control-plane task for counts, searchable paper status, parsing/indexing states, and index consistency. It does not use chunk retrieval or citations.
- **Silent fallback**: any path where classification, planning, retrieval, or answer generation fails and the system proceeds as a different task without user-visible failure.
- **Orphan chunk**: a `paper_text_chunks` or `paper_chunks` record whose `paperId` has no corresponding product paper row in `file_upload`.

## Current incident

User asked `有多少论文可以检索`.

Observed behavior:

- The system answered with random cited evidence from paper chunks.
- Runtime logs showed route `PAPER_QA`, retrieval hit count above zero, and citation count above zero.
- Product paper table had 2 searchable papers.
- Chunk stores exposed 3 distinct paper ids because one `Attention Is All You Need` page-1 paper id existed as orphan chunk/parser data without a `file_upload` row.

Root direction:

- Fix capability architecture, not a phrase case.
- Eliminate silent fallbacks.
- Make LLM intent recognition produce typed task decisions.
- Make product retrieval obey a product paper source of truth.

## Refactor strategy decision

Adopt the strategy recorded in `workflows/paperloom-chat-routing-refactor.md`:

```text
fail closed first -> typed LLM TaskRouter -> explicit Library Status capability ->
ProductPaperCorpus allowlist -> guarded orphan cleanup -> browser/runtime verification
```

Implementation principles:

- First make wrong execution impossible, even if some requests temporarily return explicit failure.
- `AUTO_SOURCE_QA` is unresolved routing state and must not execute retrieval.
- `LIBRARY_STATUS` answers count/status/searchability questions from product metadata and index
  consistency state, with no chunk retrieval and no citations.
- `LIBRARY_STATUS` is allowed inside locked `SOURCE_SET_SNAPSHOT` sessions because it is a
  control-plane task, not a scope mutation. It must answer from metadata and clearly distinguish
  current-session source-set counts from whole-library counts when needed.
- `PAPER_DISCOVERY` finds papers at paper metadata level first.
- `PAPER_QA` reads evidence chunks only after the product paper universe has been resolved.
- Existing tests that assert fallback pseudo-answers after LLM/planner/generation failure must be
  rewritten because they encode rejected behavior.

Current checkpoint on 2026-06-29:

- First backend guardrail slice is present in the working tree: typed `TaskRouter`,
  `LIBRARY_STATUS`, `PaperLibraryStatusService`, and `ProductPaperCorpus`.
- The original WebSocket incident is verified through the backend runtime:
  `有多少论文可以检索` returned `LIBRARY_STATUS`, `acceptedEvidenceCount=0`, `sourceCount=0`,
  `plannerRounds=0`, and `fallbackUsed=false`. Latest verification generation:
  `8ffaffb1-1eea-4136-8efe-b696307a456a`.
- Normal paper-content QA is verified through WebSocket generation
  `e11550c8-e685-4052-b604-8fcd93f72dec`: route `PAPER_QA`,
  `acceptedEvidenceCount=129`, `sourceCount=2`, `fallbackUsed=false`, rendered citations `[1]`
  and `[2]`, and returned 2 reference mappings.
- Product REST hybrid search for `attention` returned HTTP 200, 187 results, and only product
  paper ids `6071803f93e4573eb7e0fb2c4e65976a` and
  `e15dfcc38059fc915434a17ef6875bcb`; orphan id
  `2667ad129bc904dfe930ca83d3642b96` was not returned.
- Product DB/ES state after guarded cleanup:
  `paper_text_chunks` orphans = 0, `paper_visual_assets` orphans = 0,
  `paper_parser_artifacts` orphans = 0, `paper_chunks=278`, `paper_search=2`.
- Eval/admin state preserved: `paperloom_eval.eval_papers=64254`,
  `paperloom_eval.eval_chunks=1480777`, admin user count = 1.
- Orphan product data cleanup was a state repair step after guardrail verification, not the safety
  mechanism.
- Latest backend verification after hardcoded semantic matching cleanup:
  targeted routing/retrieval/parser tests passed, `mvn -q -DskipTests compile` passed, and
  `git diff --check` passed.
- Follow-up cleanup after guardrail verification:
  `EvidencePlanner.fallbackAction(...)` was renamed to `plannerFailure(...)`, and
  `AgentToolRegistry` reports tool query expansion as `queryExpansionUsed` instead of
  `fallbackUsed`.
- Durable conversation scope, collections, and large-library source picking remain the next
  product-level refactor track after the chat-routing guardrail slice.
