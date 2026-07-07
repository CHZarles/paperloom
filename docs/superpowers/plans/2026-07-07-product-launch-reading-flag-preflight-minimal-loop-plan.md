# Product Launch Reading Flag Preflight Plan

**Goal:** Add the smallest missing runtime readiness check for the Product Reading launch path.

**Current evidence:** The live Product Reading launch smoke requires the backend to run with
`paperloom.react.reading-phase1.enabled=true`, but `ProductLaunchRuntimePreflightCli` currently does
not check `PAPERLOOM_REACT_READING_PHASE1_ENABLED`. A runtime could pass infrastructure checks and
still route the live smoke away from the 9-tool Product Reading path.

## Grill Decisions

1. Should we promote Product Reading by changing the default flag to `true`?
   - Recommended answer: no. Keep the product default fail-closed and require the launch runtime to set the flag explicitly.

2. Should the preflight enforce the flag?
   - Recommended answer: yes. Product launch-readiness requires the live smoke to exercise Product Reading, so the preflight should fail when `PAPERLOOM_REACT_READING_PHASE1_ENABLED` is not explicitly true.

3. Is this a new Product Reading tool?
   - Recommended answer: no. It is only a runtime prerequisite check. The LLM-facing surface remains exactly 9 tools.

4. What is the smallest implementation?
   - Recommended answer: add one `READING_FLAG` probe case, `reading_phase_flag`, with `CONFIG_MISSING` failure when the flag is absent or false, plus remediation guidance.

5. What proves the loop worked?
   - Recommended answer: runner/probe tests cover true and false values, registry/docs show 11 preflight cases, and a local preflight run reports the missing flag in `remediation.md`.

## Scope

- Add `reading_phase_flag` to `ProductLaunchRuntimePreflightRunner`.
- Add probe handling and tests.
- Update README, registry test, and `harnesses.yaml` case count to 11.
- Do not change `paperloom.react.reading-phase1.enabled` default.
- Do not add, remove, or rename Product Reading tools.

## Tasks

- [x] Add failing tests for the explicit Product Reading flag check.
- [x] Implement the new probe case and remediation bullet.
- [x] Update eval registry/docs case count from 10 to 11.
- [x] Run focused and full verification.
- [x] Run local preflight and inspect scorecard/remediation.
