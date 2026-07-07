# Product Reading Live Launch Smoke Spec

## Problem

The Product Reading tool surface is complete at 9 tools, but launch readiness is still unproven because the local trace eval had zero `PRODUCT_READING_REACT_TURN` traces. The system needs a repeatable live smoke that drives the actual WebSocket chat boundary and produces enough front-protocol evidence to pair with the existing trace eval.

## Required Behavior

1. Provide `ProductReadingLiveLaunchSmokeCli`.
   - Logs in through the configured API base.
   - Creates a conversation through `/api/v1/users/conversations`.
   - Connects to `/chat/{token}?clientId=...`.
   - Sends structured JSON chat payloads containing `message`, `conversationId`, and optional `referenceFocus`.
   - Writes a standard RAG eval run under `eval/rag/runs`.

2. Provide `ProductReadingLiveLaunchSmokeRunner`.
   - Loads JSONL cases from `eval/rag/product-reading-live-launch-smoke-cases.jsonl`.
   - Runs cases sequentially against a `LiveReadingChatClient`.
   - Tracks paper handles from completion `productStateItems`.
   - Tracks source quote refs from completion `referenceMappings`.
   - Supplies chained `referenceFocus.paperHandle` or `referenceFocus.sourceQuoteRef` for dependent cases.
   - Fails a dependent case before calling the client if its required anchor is missing.

3. Capture front-protocol evidence.
   - Collect streamed markdown chunks.
   - Collect `calling_tool.toolName` events.
   - Collect completion `diagnostics`.
   - Collect completion `referenceMappings`.
   - Collect completion `productStateItems`.

4. Evaluate each smoke case.
   - `requiredToolNames` must all appear in visible tool events.
   - `requiredProductStateSourceTools` must all appear in completion `productStateItems[*].sourceTool`.
   - `requiresProductStateItem=true` requires at least one valid product state item.
   - `requiresReference=true` requires at least one reference with `sourceQuoteRef`.
   - Missing chained anchors fail with `ANCHOR_MISSING`.

5. Dataset must cover the 9 Product Reading tools:
   - `get_session_state`
   - `list_papers`
   - `search_paper_candidates`
   - `find_papers_by_identity`
   - `get_paper_outline`
   - `list_paper_locations`
   - `find_reading_locations`
   - `read_locations`
   - `trace_source_quotes`

## Non-Goals

- Do not create or fake successful traces.
- Do not replace the trace eval.
- Do not run the 30-PDF parser smoke inside this runner; keep that as the existing separate launch gate.
- Do not promote `paperloom.react.reading-phase1.enabled`.
- Do not change the Product Reading tool surface.

## Launch Command Sequence

After the backend is running with `paperloom.react.reading-phase1.enabled=true` and the 30 launch PDFs are uploaded/parsed/indexed:

```bash
mvn -q -DskipTests test-compile exec:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=com.yizhaoqi.smartpai.eval.ProductReadingLiveLaunchSmokeCli

mvn -q -DskipTests test-compile exec:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=com.yizhaoqi.smartpai.eval.ProductReadingLaunchTraceEvalCli

mvn -q -DskipTests test-compile exec:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=com.yizhaoqi.smartpai.eval.ProductPdfParserSmokeCli \
  -Dexec.args="--manifest eval/rag/pdf-parser/product-pdf-launch-30-manifest.jsonl"
```

Launch-ready means all three commands produce passing scorecards.
