# Product Reading Launch-Readiness Trace/Eval Minimal Loop Plan

Date: 2026-07-07

## Goal

Move the Product Reading path from feature-complete paper navigation toward a launch gate:

- keep the LLM-facing Product Reading surface at exactly 9 tools, not 4;
- make paper-card and evidence behavior visible in trace artifacts;
- add an eval gate that can score the live trace artifacts for all Product Reading tool families;
- add a committed 30-real-PDF parser manifest so launch readiness is not argued from a one-PDF smoke.

## Current Evidence

- Branch: `feature/paperloom-reading-retrieval-minimal-system`
- Latest feature commit: `943381a feat: add browse search reading paper choice cards`
- Product Reading tool registry exposes exactly:
  - `get_session_state`
  - `list_papers`
  - `search_paper_candidates`
  - `find_papers_by_identity`
  - `get_paper_outline`
  - `list_paper_locations`
  - `find_reading_locations`
  - `read_locations`
  - `trace_source_quotes`
- Only 3 paper-level selection tools create `READING_PAPER_CHOICE` Product State Items:
  - `list_papers`
  - `search_paper_candidates`
  - `find_papers_by_identity`
- Local corpus has 77 PDFs under `data/`.
- Existing parser smoke manifest has only 1 PDF.
- Existing Product Reading trace records tool calls, answer envelope, references, stop reason, and status, but not `productStateItems`.

## Grilled Decisions

### 1. Is 4 tools enough?

Recommended answer: No. Product Reading has 9 LLM-facing tools. The earlier 4-tool wording belonged to an earlier source-quote slice. The current launch gate must verify all 9 tools. The UI-card surface is narrower: 3 paper-level tools produce paper choices.

Adopted.

### 2. What is the next smallest launch-readiness loop?

Recommended answer: Do not add more reading tools. The next smallest loop is observability and eval: trace the Product State Items that the frontend receives, add a trace-eval runner, and create a 30-PDF parser manifest. This makes "can it launch?" answerable from artifacts instead of memory.

Adopted.

### 3. Should this slice import/reparse all 30 PDFs automatically?

Recommended answer: Not yet. Committing the 30-PDF manifest is the minimal data contract. The live runtime still owns upload/parse/import. The parser smoke CLI can then be run against the active database. Automatic upload/reparse orchestration is a later operational slice.

Adopted.

### 4. What must the trace eval prove?

Recommended answer: A launch-readiness trace run must find completed Product Reading traces covering all 9 tools, paper-choice Product State Items from the 3 card-producing tools, and evidence references from both Source Quote paths.

Adopted.

### 5. Does this make the product launchable by itself?

Recommended answer: No. It creates the launch gate. The product is not launch-ready until the 30-PDF parser smoke and the Product Reading trace eval both pass against the active environment, and frontend smoke still passes.

Adopted.

## Scope

- Add `productStateItems` to `PRODUCT_READING_REACT_TURN` trace artifacts.
- Bump Product Reading trace version because the artifact schema changes.
- Add a test-scoped Product Reading trace eval runner and CLI.
- Add trace-eval JSONL cases for all 9 Product Reading tools.
- Add a 30-PDF parser launch manifest from local real PDFs.
- Register the trace eval and 30-PDF parser benchmark in `eval/rag/harnesses.yaml`.
- Document how to run both gates.

## Out Of Scope

- No new LLM-facing tools.
- No legacy Product path changes.
- No feature-flag promotion.
- No long-term persistence of Product State Items.
- No automatic PDF upload/reparse pipeline.
- No live Playwright/browser automation changes in this slice.

## Implementation Steps

1. Add failing tests for Product Reading trace `productStateItems`.
2. Add failing tests for trace-eval matching and missing-trace failures.
3. Add failing tests for the 30-PDF manifest.
4. Implement trace schema update.
5. Implement trace-eval runner/CLI.
6. Add JSONL trace cases and 30-PDF manifest.
7. Update eval docs and registry.
8. Run focused backend tests, frontend typecheck, and diff checks.
9. Commit the completed slice.

## Acceptance

- Product Reading trace artifacts include sanitized `productStateItems`.
- Trace version is bumped.
- Trace eval can write standard `run.json`, `scorecard.json`, and `report.md`.
- Trace eval cases cover all 9 Product Reading tools.
- 30-PDF manifest contains exactly 30 unique existing `.pdf` files from `data/`.
- Registry exposes:
  - `product-reading-launch-trace`
  - `product-pdf-launch-30`
- Existing Product Reading card behavior remains unchanged.
