# Product Launch Readiness Blocker Rollup Spec

## Goal

Make the top-level `product-launch-readiness` run actionable when a child gate fails by surfacing
the failed child case IDs and failure classes directly in the wrapper artifacts.

## Non-Goals

- Do not fix runtime preflight blockers in code.
- Do not change individual launch gate CLIs.
- Do not include raw child failure messages, diagnostics, credentials, tokens, hostnames, or
  environment values in the rollup.
- Do not add rollups for skipped downstream gates.

## Behavior

When an executed child gate fails:

1. `ProductLaunchReadinessRunner` reads the child run's `run.json`.
2. It collects rows under `cases` where `passed=false`.
3. For each failed row, it records:
   - `caseId`
   - `failureClass`
4. The top-level failed gate row in `run.json` includes the collection as
   `diagnostics.childFailedCases`.
5. The top-level `remediation.md` lists those failed child cases under the blocking gate.

When child `run.json` is missing, unreadable, or malformed:

1. The launch wrapper must still finish and preserve the top-level failed gate.
2. The top-level failed gate diagnostics must say child failed-case details are unavailable.
3. The top-level `remediation.md` must say failed child case details are unavailable.
4. The wrapper must not include raw parser or exception messages from the child artifact read.

When a child gate passes:

1. The top-level diagnostics may omit `childFailedCases` or include an empty collection.
2. The remediation stays in the launch-ready success format.

When downstream gates are skipped:

1. Skipped gate diagnostics continue to point at the blocking gate.
2. Skipped rows do not attempt to read or roll up child failed cases.

## Test Seam

Use `ProductLaunchReadinessRunner` with injected fake `LaunchGate` commands that write standard
child eval artifacts through `RagEvalRunWriter`.

Required tests:

- A failing child gate with multiple failed child cases rolls up only case IDs and failure classes
  into the top-level failed gate diagnostics and `remediation.md`.
- A failed child gate with missing child `run.json` does not crash the wrapper and marks child
  failed-case details unavailable.
- Existing fail-fast, skipped downstream gate, and all-pass behavior remains unchanged.
