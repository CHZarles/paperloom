# Product Launch Readiness Remediation Artifact Plan

**Goal:** Make the top-level launch readiness run actionable by writing a non-secret `remediation.md` next to its scorecard.

**Current evidence:** `ProductLaunchReadinessCli` now writes a top-level readiness scorecard and stops after the failing runtime preflight, but its run directory contains only `run.json`, `scorecard.json`, and `report.md`. Operators still have to know where to find child preflight remediation. A launch wrapper should leave one obvious top-level remediation artifact.

## Grill Decisions

1. Should we try to remediate runtime services or missing keys in code?
   - Recommended answer: no. Backend availability, MinerU, and model keys are external runtime state. This loop should not fake them or mutate secrets.

2. What is the smallest useful fix?
   - Recommended answer: after writing the launch readiness run, also write `remediation.md` summarizing status, executed child gate runs, skipped gates, and the first blocker.

3. Should the top-level remediation copy child preflight remediation?
   - Recommended answer: reference the child `remediation.md` path and include the blocking gate summary, but do not duplicate secrets or raw child logs.

4. What should a passing run say?
   - Recommended answer: say launch readiness passed and list the child gate run dirs as evidence.

5. What should a failing run say?
   - Recommended answer: say not launch-ready, name the first blocking gate, identify skipped downstream gates, and point to the child run/remediation artifact.

6. What test seam should we use?
   - Recommended answer: test `ProductLaunchReadinessRunner` by injecting fake child gates that write scorecards/remediation files; assert the top-level `remediation.md` exists for pass and fail.

## Scope

- Extend `ProductLaunchReadinessRunner` to write `remediation.md`.
- Keep individual gate behavior unchanged.
- Do not run downstream gates after a failure.
- Do not print or persist passwords, tokens, model keys, embedding keys, or database credentials.

## Tasks

- [x] Add failing tests for top-level remediation on failed and passing readiness runs.
- [x] Implement remediation writing in `ProductLaunchReadinessRunner`.
- [x] Document the top-level remediation artifact.
- [x] Run focused tests, full tests, `git diff --check`, and a local failing readiness command.
- [x] Commit the loop.
