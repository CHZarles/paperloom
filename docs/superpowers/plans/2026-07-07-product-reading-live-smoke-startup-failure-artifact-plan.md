# Product Reading Live Smoke Startup-Failure Artifact Plan

**Goal:** Keep the launch gate evaluable even when the backend login or conversation bootstrap fails before the WebSocket smoke can start.

**Current evidence:** The runtime preflight is still failing, and `ProductReadingLiveLaunchSmokeCli` can throw before writing eval artifacts if login or conversation creation fails. The launch pipeline should leave a scorecard for every gate failure.

## Grill Decisions

1. Should we run the live smoke while preflight is failing?
   - Recommended answer: no. This loop is not to run downstream gates prematurely; it is to make the live smoke gate produce artifacts if someone does run it against an unavailable backend.

2. What is the smallest useful fix?
   - Recommended answer: catch login/conversation startup failures in `ProductReadingLiveLaunchSmokeCli` and write a failed eval run over the configured live-smoke cases.

3. How should dependent cases with anchors fail during startup failure?
   - Recommended answer: every case should fail as `RUNTIME_UNAVAILABLE`, not as anchor missing, because the root cause is that the live boundary never started.

4. Should `main` still exit non-zero?
   - Recommended answer: yes. Failed artifacts must be written, then the hard-gate scorecard check should return non-zero.

5. Should this change Product Reading behavior or tools?
   - Recommended answer: no. This changes only launch eval failure reporting. The 9-tool Product Reading surface stays unchanged.

## Scope

- Add a startup-failure run path to `ProductReadingLiveLaunchSmokeRunner`.
- Catch startup failures in `ProductReadingLiveLaunchSmokeCli.runCommand`.
- Add a CLI test proving failed artifacts are written on login failure.
- Do not bypass preflight or live smoke requirements.

## Tasks

- [x] Add a test for login failure writing a failed live-smoke eval run.
- [x] Implement startup-failure run writing with `RUNTIME_UNAVAILABLE`.
- [x] Document that live smoke preserves failed artifacts for startup failures.
- [x] Run focused tests, full tests, and `git diff --check`.
- [ ] Commit the loop.
