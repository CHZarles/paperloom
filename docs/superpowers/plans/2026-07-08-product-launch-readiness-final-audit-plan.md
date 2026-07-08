# Product Launch Readiness Final Audit Plan

**Goal:** Close the smallest real launch-readiness loop after the MiniMax embedding switch: prove the active runtime uses current-pipeline 30-PDF data, Product Reading works from frontend/backend paths, traces are evaluable, and the launch gates fail only on real blockers.

## Grill Decisions

1. What is the next smallest loop?
   - Recommended answer: do not reset again unless evidence shows stale or mixed-dimension data. First make the hard gates understand the current runtime, then run them against the existing 30 PDFs.

2. What is the most likely false blocker?
   - Recommended answer: runtime preflight still checks `.env` DeepSeek/Aliyun keys, while the actual product runtime stores active MiniMax LLM/embedding providers in backend model-provider config. The gate must test the backend's active providers.

3. Should an already-seeded 30-PDF library be re-uploaded?
   - Recommended answer: no. The seed gate should be idempotent: if a manifest PDF's content hash is already uploaded, vectorized, and frontend-searchable, pass it as existing current-pipeline data instead of re-uploading and failing on completed papers.

4. What proves the data is not stale?
   - Recommended answer: DB/ES counts must agree on 30 papers and 10k+ chunks, the ES vector mapping must match MiniMax `1536`, all manifest paper IDs must be present, and current reading models must be ready for all 30.

5. What proves Product Reading rather than legacy chat?
   - Recommended answer: run the Product Reading live launch smoke and require fresh `PRODUCT_READING_REACT_TURN` traces, then run `ProductReadingLaunchTraceEvalCli` against those traces.

6. What should happen if any gate fails?
   - Recommended answer: do not declare launch-ready. Fix the smallest real blocker, rerun the focused gate, then rerun the top-level launch readiness wrapper.

## Scope

- Make runtime preflight verify active backend model-provider configs instead of static provider env keys.
- Make 30-PDF launch data seed idempotent for already-searchable manifest PDFs.
- Run focused tests, full launch gates, and a frontend/browser smoke if the hard gates pass.
- Commit only real readiness changes and leave unrelated `AGENTS.md.old` untouched.

## Tasks

- [x] Add tests for backend active-provider preflight and idempotent seeded PDFs.
- [x] Implement active-provider preflight and idempotent data seed behavior.
- [x] Run unit/type checks.
- [x] Run launch readiness gates against current MiniMax 30-PDF runtime.
- [x] Run a frontend interaction smoke and inspect generated traces.
- [x] Decide launch status from artifacts, not assumptions.

## Closure Evidence

- Runtime preflight: `eval/rag/runs/2026-07-08-product-launch-readiness-final-frontend-action-01-product-launch-runtime-preflight`, 12/12 passed.
- 30-PDF launch seed: `eval/rag/runs/2026-07-08-product-launch-readiness-final-frontend-action-02-product-pdf-launch-data-seed`, 30/30 passed, 30 unique paper IDs, 30 frontend-searchable, 30 already seeded through the current pipeline, 0 re-merged.
- Product Reading live smoke: `eval/rag/runs/2026-07-08-product-launch-readiness-final-frontend-action-03-product-reading-live-launch-smoke`, 9/9 passed.
- Product Reading trace eval: `eval/rag/runs/2026-07-08-product-launch-readiness-final-frontend-action-04-product-reading-launch-trace-eval`, 9/9 passed.
- Parser smoke: `eval/rag/runs/2026-07-08-product-launch-readiness-final-frontend-action-05-product-pdf-parser-smoke`, 30/30 passed.
- Top-level launch readiness: `eval/rag/runs/2026-07-08-product-launch-readiness-final-frontend-action`, 5/5 gates passed.
- Browser smoke: logged in through the real frontend, searched `2412.08972.pdf`, clicked the paper-card `LIST_LOCATIONS` action, saw the focus chip, submitted, and observed `calling list_paper_locations`.
