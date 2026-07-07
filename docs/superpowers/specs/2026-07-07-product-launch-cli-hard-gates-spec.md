# Product Launch CLI Hard Gates Spec

## Problem

The launch eval CLIs write useful scorecards, but a failed scorecard can still produce a successful process exit when no Java exception escapes. That is dangerous for launch automation: a shell script could continue from a failing preflight into the expensive 30-PDF seed, live Product Reading smoke, trace eval, or parser smoke. The current local preflight evidence is still `5/10`, so the command boundary must enforce the gate.

## Required Behavior

1. Add a shared test-scope scorecard gate helper.
   - Reads `scorecard.json` from a run directory.
   - Exposes a pass/fail result and concise summary.
   - Treats a gate as passing only when `caseCount > 0` and `failed == 0`.
   - Treats zero-case scorecards as failing.

2. Update product launch CLI `main` methods to check the scorecard after artifact generation:
   - `ProductLaunchRuntimePreflightCli`
   - `ProductPdfLaunchDataSeedCli`
   - `ProductReadingLiveLaunchSmokeCli`
   - `ProductReadingLaunchTraceEvalCli`
   - `ProductPdfParserSmokeCli`

3. Preserve debugging ergonomics.
   - `runCommand` methods must still return the run directory even when the scorecard failed.
   - Failed scorecards must still leave `run.json`, `scorecard.json`, `report.md`, and any extra artifacts such as `remediation.md`.
   - `main` must print the run directory before reporting the gate failure.

4. Failure reporting must be concise and non-secret.
   - Include run id, harness id, passed/case count, pass rate, and run directory.
   - Do not print passwords, API keys, embedding keys, or raw diagnostics.

## Non-Goals

- Do not start or stop runtime services.
- Do not edit `.env`.
- Do not fake passing scorecards.
- Do not change benchmark scoring semantics inside runners.
- Do not add, remove, or rename Product Reading tools.

## Verification

Run:

```bash
mvn -q -Dtest=RagEvalGateStatusTest,ProductLaunchRuntimePreflightRunnerTest,ProductLaunchRuntimePreflightProbeTest,RagBenchmarkRegistryTest test
mvn -q test
git diff --check
```

Then verify the local failing preflight exits non-zero while still writing artifacts:

```bash
mvn -q -DskipTests test-compile exec:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=com.yizhaoqi.smartpai.eval.ProductLaunchRuntimePreflightCli \
  -Dexec.args="--run-id 2026-07-07-product-launch-runtime-preflight-hard-gate-audit --timeout-seconds 5"
```

Expected current local status: command exits non-zero and the run directory contains the failed `5/10` preflight artifacts plus `remediation.md`.
