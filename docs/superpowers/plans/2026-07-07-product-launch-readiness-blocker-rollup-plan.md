# Product Launch Readiness Blocker Rollup Plan

**Goal:** Make the top-level launch readiness remediation self-contained enough to show which child gate cases failed without opening child `run.json`.

**Current evidence:** `ProductLaunchReadinessCli` now writes top-level `remediation.md` and points to the child preflight remediation. The local run still fails at runtime preflight `5/11`, but the top-level artifact only names the blocking gate, not the six failed child cases. Operators need the first blocker case IDs immediately visible at the launch wrapper level.

## Grill Decisions

1. Should this loop attempt to fix the runtime blockers?
   - Recommended answer: no. Backend, MinerU, model keys, and embedding keys remain external launch runtime state. This loop improves traceability/actionability without faking readiness.

2. What is the smallest useful improvement?
   - Recommended answer: when a child gate fails, read its `run.json` and roll up failed child case IDs and failure classes into the top-level readiness diagnostics/remediation.

3. Should the rollup include raw failure messages or diagnostics?
   - Recommended answer: no. Include only case IDs and failure classes. Raw diagnostics may contain hostnames or operational details and are already available in child artifacts.

4. Should skipped downstream gates get rollups?
   - Recommended answer: no. Skipped rows should keep pointing to the blocking gate. Only executed failed gates can have failed child cases.

5. What if child `run.json` is missing or malformed?
   - Recommended answer: keep the launch wrapper robust. The top-level gate still fails from `scorecard.json`, and remediation says child failed-case details were unavailable.

6. What test seam should we use?
   - Recommended answer: `ProductLaunchReadinessRunner` with injected fake gates that write child `run.json` rows with failed cases.

## Scope

- Extend `ProductLaunchReadinessRunner` to parse child failed case IDs/classes from child `run.json`.
- Include failed child case rollup in top-level `run.json` diagnostics and `remediation.md`.
- Do not change individual gate CLIs.
- Do not include raw child diagnostics, raw failure messages, passwords, tokens, model keys, embedding keys, or database credentials.

## Tasks

- [x] Add failing tests for failed child case rollup in diagnostics and remediation.
- [x] Implement child `run.json` failed-case rollup.
- [x] Document the rollup behavior.
- [x] Run focused tests, full tests, `git diff --check`, and local failing readiness command.
- [x] Commit the loop.
