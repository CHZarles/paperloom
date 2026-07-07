# Product Launch Readiness Orchestrator Minimal Loop Plan

**Goal:** Add the smallest single-command launch readiness loop that runs the existing hard gates in order, stops on the first failing gate, and writes a traceable/evaluable readiness artifact.

**Current evidence:** The runtime preflight still fails at `5/11`, and the five individual launch gates now preserve failed artifacts. What is still missing is a single operator-facing gate that encodes the launch order, prevents accidental downstream execution after a failed preflight, and leaves one top-level scorecard that says whether the product is launch-ready.

## Grill Decisions

1. Should we run the 30-PDF seed, live smoke, trace eval, or parser smoke while preflight is failing?
   - Recommended answer: no. The orchestrator must stop at the first failing gate. Current local evidence should remain a failed preflight, not noisy downstream failures.

2. What is the smallest useful orchestrator?
   - Recommended answer: a test-scope `ProductLaunchReadinessCli` plus runner that invokes the five existing gates in the documented order, reads each `scorecard.json`, and writes one top-level eval run.

3. How should skipped downstream gates appear?
   - Recommended answer: include them as failed readiness rows with `SKIPPED_DUE_TO_PREVIOUS_GATE`, and diagnostics pointing to the blocking gate/run. Do not execute their CLIs.

4. Should the orchestrator replace the individual gates?
   - Recommended answer: no. It is a launch wrapper over the existing gates. Individual gate CLIs remain runnable and authoritative for their own artifacts.

5. What proves launch-ready?
   - Recommended answer: all five gates pass in order: runtime preflight, 30-PDF data seed, live Product Reading smoke, 9-tool trace eval, and 30-PDF parser smoke. Anything else is not launch-ready.

6. What test seam should we use?
   - Recommended answer: test `ProductLaunchReadinessRunner` with injected fake gate commands that write scorecards, plus a CLI option test. This avoids starting backend services or requiring model keys during unit tests.

## Scope

- Add `ProductLaunchReadinessRunner`.
- Add `ProductLaunchReadinessCli`.
- Register/document the new `product-launch-readiness` harness and benchmark.
- Tests cover stop-on-first-failure, all-pass behavior, skipped rows, and CLI defaults.

## Tasks

- [x] Add failing tests for orchestrator stop-on-first-failure and all-pass behavior.
- [x] Implement runner and CLI over injected/default gate commands.
- [x] Register and document the launch readiness wrapper.
- [x] Run focused tests, full tests, `git diff --check`, and a local failing orchestrator command.
- [x] Commit the loop.
