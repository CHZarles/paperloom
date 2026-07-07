# Product Launch CLI Hard Gates Plan

**Goal:** Make the existing launch eval CLIs behave like real shell gates, so failed scorecards stop launch automation instead of exiting successfully.

**Current evidence:** `ProductLaunchRuntimePreflightCli` still reports `5/10` in
`eval/rag/runs/2026-07-07-product-launch-runtime-preflight-continuation-audit`, but the CLI process currently exits successfully after writing the failed scorecard.

## Grill Decisions

1. Should we run the 30-PDF seed while preflight is failing?
   - Recommended answer: no. The preflight proves launch readiness is false. The next minimal loop should make that failure enforceable at the command boundary.

2. What is the smallest useful code change?
   - Recommended answer: add one shared scorecard gate helper and wire it into the product launch CLIs after they write eval artifacts.

3. Which CLIs should become hard gates?
   - Recommended answer: the five product launch gates: runtime preflight, 30-PDF data seed, live Product Reading smoke, Product Reading trace eval, and 30-PDF parser smoke.

4. Should `runCommand` helpers throw when a scorecard fails?
   - Recommended answer: no. Tests and debugging still need access to failed artifacts. Only `main` should convert failed scorecards to a non-zero process exit.

5. What counts as a passing launch gate?
   - Recommended answer: `caseCount > 0` and `failed == 0`; equivalently every case passed.

6. Should this hide the current runtime blockers?
   - Recommended answer: no. The CLI should still print the run directory and a concise failure summary so the operator can inspect `remediation.md` and `run.json`.

## Scope

- Add a test-scoped shared helper that reads `scorecard.json`.
- Update the five product launch CLI `main` methods to exit non-zero on failed scorecards.
- Keep failed eval artifact generation intact.
- Keep Product Reading at exactly 9 tools.
- Do not start services, edit `.env`, or add fake secrets.

## Tasks

- [x] Add focused tests for passing, failing, and zero-case scorecards.
- [x] Implement the shared scorecard gate helper.
- [x] Wire hard-gate behavior into the five launch CLI `main` methods.
- [x] Document that launch CLIs return non-zero on failed scorecards.
- [x] Verify with focused tests, full tests, `git diff --check`, and a local failing preflight process exit check.
