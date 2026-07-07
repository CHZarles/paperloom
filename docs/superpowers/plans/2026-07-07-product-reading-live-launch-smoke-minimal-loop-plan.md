# Product Reading Live Launch Smoke Minimal Loop Plan

## Goal

Add the smallest live launch loop that can exercise Product Reading through the WebSocket chat boundary, capture front-protocol outcomes, and then let the existing trace eval decide whether the 9-tool behavior is launch-ready.

## Grill Decisions

1. Should the next loop add more Product Reading tools?
   - Recommended answer: no. The Product Reading tool surface is already 9 tools. The launch risk is missing live evidence, not missing tools.

2. What is the smallest useful launch loop?
   - Recommended answer: a live WebSocket smoke runner that logs in, creates a conversation, sends a fixed sequence of reading prompts, carries clicked paper/source quote anchors forward from prior completions, and records a standard eval run.

3. Should the smoke runner stub LLM or tool results?
   - Recommended answer: no. It must use the running backend and front chat protocol. Unit tests may fake the client, but the CLI is only launch evidence when pointed at a real running app.

4. How should the runner prove tool coverage?
   - Recommended answer: it should check visible `calling_tool` events and completion payloads, then rely on `ProductReadingLaunchTraceEvalCli` over `PRODUCT_READING_REACT_TURN` artifacts for authoritative 9-tool trace coverage.

5. How should dependent cases get anchors?
   - Recommended answer: paper-handle follow-ups use the first `READING_PAPER_CHOICE.paperHandle` from a prior case; source-quote follow-ups use the first `sourceQuoteRef` from a prior evidence case.

6. What counts as launch-ready for this loop?
   - Recommended answer: the live smoke passes, Product Reading launch trace eval passes 9/9 on traces produced by that live smoke, and the existing 30-PDF parser manifest gate passes. If any gate cannot run or fails, the product is not ready to launch.

## Scope

- Add a Product Reading live launch smoke dataset.
- Add a live WebSocket chat client that can send structured chat payloads with `conversationId` and optional `referenceFocus`.
- Add a runner/CLI that writes standard `run.json`, `scorecard.json`, and `report.md` artifacts.
- Chain `paperHandle` and `sourceQuoteRef` anchors between cases.
- Do not add new LLM tools.
- Do not bypass `paperloom.react.reading-phase1.enabled`.
- Do not synthesize passing traces.

## Verification

- Red-green tests for anchor chaining, product-state/reference assertions, and WebSocket payload shape.
- Focused Maven tests for the new smoke runner/client.
- Existing Product Reading registry/harness/chat tests.
- `ProductReadingLaunchTraceEvalCli` and `ProductPdfParserSmokeCli` remain the launch gates after live data exists.
