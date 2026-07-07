# Product Launch Readiness Remediation Artifact Spec

## Problem

`ProductLaunchReadinessCli` writes a top-level launch readiness scorecard, but the run directory does not contain a top-level remediation artifact. When the first gate fails, the operator has to inspect `run.json` or know the child gate run layout to find the next action. Launch readiness should be traceable and actionable from the top-level run directory.

## Required Behavior

1. Every `ProductLaunchReadinessRunner` run must write `remediation.md` next to `run.json`, `scorecard.json`, and `report.md`.
2. A failed readiness run must state `not launch-ready`, identify the first blocking gate, list skipped downstream gates, and point to the blocking child `runDir`.
3. If the blocking child run contains `remediation.md`, the top-level artifact must point to that file.
4. A passing readiness run must state that launch readiness passed and list all child gate run dirs as evidence.
5. The artifact must include the five-gate launch order.
6. The artifact must not include passwords, tokens, model keys, embedding keys, or database credentials.
7. Individual gate CLIs and their artifacts must remain unchanged.

## Non-Goals

- Do not attempt to start or mutate runtime services.
- Do not set or infer model/embedding keys.
- Do not bypass the runtime preflight.
- Do not run downstream gates after a failed gate.
- Do not duplicate full child logs or raw secret-bearing exception text.

## Verification

Run:

```bash
mvn -q -Dtest=ProductLaunchReadinessRunnerTest,ProductLaunchReadinessCliTest,RagEvalGateStatusTest test
mvn -q test
git diff --check
```

Then run the local orchestrator:

```bash
set +e
mvn -q -DskipTests test-compile exec:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=com.yizhaoqi.smartpai.eval.ProductLaunchReadinessCli \
  -Dexec.args="--run-id 2026-07-07-product-launch-readiness-remediation-audit --timeout-seconds 5"
echo exit_status=$?
```

Expected current local status: command exits non-zero, writes top-level `remediation.md`, executes only runtime preflight, and points to the child preflight remediation artifact.
