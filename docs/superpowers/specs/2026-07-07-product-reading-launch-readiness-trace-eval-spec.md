# Product Reading Launch-Readiness Trace/Eval Spec

Date: 2026-07-07

## Problem

Product Reading now has the expected 9-tool LLM-facing surface and paper-choice UI cards, but the launch gate is still weak:

- Product Reading traces do not record `productStateItems`, so the clickable paper-card behavior is not directly auditable.
- The parser smoke manifest has only 1 PDF, while launch readiness needs a broader real-PDF gate.
- There is no eval runner that can inspect live Product Reading trace artifacts and say whether all 9 tool families have been exercised.

## Required Tool Surface

The Product Reading LLM-facing tool surface remains exactly 9 tools:

1. `get_session_state`
2. `list_papers`
3. `search_paper_candidates`
4. `find_papers_by_identity`
5. `get_paper_outline`
6. `list_paper_locations`
7. `find_reading_locations`
8. `read_locations`
9. `trace_source_quotes`

Only these 3 tools produce `READING_PAPER_CHOICE` Product State Items:

1. `list_papers`
2. `search_paper_candidates`
3. `find_papers_by_identity`

No new tools are added in this spec.

## Trace Schema

`ProductReadingTraceRecorder` must write `productStateItems` and the canonical research trace into `PRODUCT_READING_REACT_TURN` trace JSON.

Rules:

- The field is always present.
- Null result items become `[]`.
- Items are copied into trace JSON so later mutation of the `ProductTurnResult` list does not mutate the submitted trace payload.
- The recorder does not promote Product State Items into evidence.
- Trace version is `5`.
- `researchTrace` is always present and follows `research-harness-artifacts/v1`: `intentFrame`, `retrievalPlan`, `evidenceLedger`, `claimGraph`, `reasoningArtifacts`, `verificationPass`, and `researchAnswer`.

Expected trace excerpt:

```json
{
  "artifactType": "PRODUCT_READING_REACT_TURN",
  "traceVersion": 5,
  "toolCalls": [
    { "toolName": "list_papers" }
  ],
  "answerEnvelope": {
    "answerType": "PRODUCT_STATE"
  },
  "productStateItems": [
    {
      "kind": "READING_PAPER_CHOICE",
      "sourceTool": "list_papers",
      "paperHandle": "paper_handle_abc",
      "title": "Agentic Eval Benchmark"
    }
  ],
  "researchTrace": {
    "schemaVersion": "research-harness-artifacts/v1",
    "intentFrame": {},
    "retrievalPlan": {},
    "evidenceLedger": {},
    "claimGraph": {},
    "reasoningArtifacts": [],
    "verificationPass": { "valid": true },
    "researchAnswer": {}
  },
  "references": []
}
```

## Trace Eval Runner

Add test-scoped eval tooling:

- `ProductReadingLaunchTraceEvalRunner`
- `ProductReadingLaunchTraceEvalCli`

Input:

- `--trace-root`, default `data/traces/product-react`
- `--cases`, default `eval/rag/product-reading-launch-trace-cases.jsonl`
- `--runs-root`, default `eval/rag/runs`
- `--harness-id`, default `product-reading-launch-trace-eval`
- `--dataset-id`, default `product-reading-launch-trace`

Case JSONL fields:

```json
{
  "id": "browse_cards",
  "requiredToolNames": ["list_papers"],
  "requiredAnswerType": "PRODUCT_STATE",
  "requiredProductStateKinds": ["READING_PAPER_CHOICE"],
  "requiredProductStateSourceTools": ["list_papers"],
  "requiresReference": false,
  "expectedResultStatus": "COMPLETED",
  "requiresResearchTrace": true,
  "requiresVerifiedResearchTrace": true
}
```

Matching rules:

- Only traces with `artifactType=PRODUCT_READING_REACT_TURN` are candidates.
- `expectedResultStatus` must match when present.
- `requiredAnswerType` must match `answerEnvelope.answerType` when present.
- Every `requiredToolNames[]` value must appear in `toolCalls[].toolName`.
- Every `requiredProductStateKinds[]` value must appear in `productStateItems[].kind`.
- Every `requiredProductStateSourceTools[]` value must appear in `productStateItems[].sourceTool`.
- If `requiresReference=true`, `references[]` must be non-empty.
- If `requiresResearchTrace=true`, `researchTrace` must include the full canonical artifact order.
- If `requiresVerifiedResearchTrace=true`, `researchTrace.verificationPass.valid` must be `true`.

Output:

- Standard PaperLoom eval artifacts through `RagEvalRunWriter`:
  - `run.json`
  - `scorecard.json`
  - `report.md`

Failure classes:

- `TRACE_MISSING`: no matching trace exists for a case.
- `TRACE_CASE_INVALID`: malformed or empty case input.

## Required Trace Cases

`eval/rag/product-reading-launch-trace-cases.jsonl` must cover:

- session state: `get_session_state`
- deterministic browse cards: `list_papers`
- semantic search cards: `search_paper_candidates`
- identity cards: `find_papers_by_identity`
- outline navigation: `get_paper_outline`
- deterministic locations: `list_paper_locations`
- semantic reading locations: `find_reading_locations`
- source quote read: `read_locations`
- clicked source quote trace: `trace_source_quotes`

Together these cases cover all 9 Product Reading tools.

## 30-PDF Parser Manifest

Add:

```text
eval/rag/pdf-parser/product-pdf-launch-30-manifest.jsonl
```

Rules:

- Exactly 30 non-comment cases.
- Every row points to an existing local `.pdf` file under `data/`.
- Paths are unique.
- Rows default to normal real-PDF parser smoke expectations:
  - `expectedMinChunks >= 1`
  - `expectedMinPages >= 1`
  - parser artifact required unless explicitly disabled
  - no structured JSON/eval imports

The manifest is a launch gate input, not proof that the active runtime has already parsed all 30 PDFs.

## Registry

Update `eval/rag/harnesses.yaml`:

- Add harness `product-reading-launch-trace-eval`.
- Add benchmark `product-reading-launch-trace`.
- Add benchmark `product-pdf-launch-30`.
- Keep existing `product-pdf-parser-smoke` unchanged.

## Verification

Focused tests:

```bash
mvn -q -Dtest=ProductReadingTraceRecorderTest,ProductReadingLaunchTraceEvalRunnerTest,ProductPdfLaunchManifestTest,RagBenchmarkRegistryTest test
```

Broader backend:

```bash
mvn -q -Dtest=ProductReadingToolRegistryTest,ProductReadingReActHarnessTest,ChatHandlerProductHarnessTest test
```

Frontend typecheck:

```bash
cd frontend && pnpm typecheck
```

Static diff check:

```bash
git diff --check
```

Live launch gate commands after the active environment has produced traces and parsed the 30 PDFs:

```bash
mvn -q -DskipTests test-compile dependency:build-classpath -Dmdep.outputFile=target/test-classpath.txt

java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" \
  com.yizhaoqi.smartpai.eval.ProductPdfParserSmokeCli \
  --manifest eval/rag/pdf-parser/product-pdf-launch-30-manifest.jsonl \
  --runs-root eval/rag/runs

java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" \
  com.yizhaoqi.smartpai.eval.ProductReadingLaunchTraceEvalCli \
  --trace-root data/traces/product-react \
  --cases eval/rag/product-reading-launch-trace-cases.jsonl \
  --runs-root eval/rag/runs
```

## Non-Goals

- No automatic upload/reparse orchestration.
- No generated PDFs.
- No new UI cards beyond existing `READING_PAPER_CHOICE`.
- No new Source Quote rules.
- No launch claim until live eval artifacts pass.
