# Product Launch Readiness Orchestrator Spec

## Problem

Launch readiness currently requires running five separate hard-gate CLIs in a specific order:

1. `ProductLaunchRuntimePreflightCli`
2. `ProductPdfLaunchDataSeedCli`
3. `ProductReadingLiveLaunchSmokeCli`
4. `ProductReadingLaunchTraceEvalCli`
5. `ProductPdfParserSmokeCli --manifest eval/rag/pdf-parser/product-pdf-launch-30-manifest.jsonl`

Each gate now writes artifacts and exits non-zero on failure, but there is no single top-level launch readiness command. Operators can accidentally run expensive downstream gates after the runtime preflight has already proved the product is not launch-ready. The launch attempt also lacks one top-level scorecard tying the five gate runs together.

## Required Behavior

1. Provide `ProductLaunchReadinessCli`.
2. The CLI must run the five existing launch gates in the documented order.
3. The CLI must stop invoking gate CLIs after the first failed gate.
4. The CLI must write a standard eval run under `eval/rag/runs` with one readiness row for each of the five gates.
5. Executed gate rows must include the child `runDir`, `runId`, `harnessId`, `passed`, `caseCount`, and `passRate` from that gate's `scorecard.json`.
6. Skipped downstream rows must fail with `SKIPPED_DUE_TO_PREVIOUS_GATE` and identify the blocking gate.
7. The top-level readiness scorecard must pass only when all five gates pass.
8. `main` must exit non-zero through the existing scorecard hard gate when any gate fails or is skipped.
9. Normal individual gate CLIs and their artifacts must remain unchanged.
10. The orchestrator must not print or persist passwords, tokens, model keys, embedding keys, or database credentials.

## Non-Goals

- Do not bypass runtime preflight.
- Do not fake any child gate result.
- Do not run downstream gates after a failed gate.
- Do not start Docker services, backend, frontend, MinerU, or model providers.
- Do not make Product Reading enabled by default.

## Verification

Run:

```bash
mvn -q -Dtest=ProductLaunchReadinessRunnerTest,ProductLaunchReadinessCliTest,RagEvalGateStatusTest,RagBenchmarkRegistryTest test
mvn -q test
git diff --check
```

Then run the local orchestrator:

```bash
set +e
mvn -q -DskipTests test-compile exec:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=com.yizhaoqi.smartpai.eval.ProductLaunchReadinessCli \
  -Dexec.args="--run-id 2026-07-07-product-launch-readiness-local-audit --timeout-seconds 5"
echo exit_status=$?
```

Expected current local status: command exits non-zero, writes a top-level readiness run, executes only the runtime preflight, and marks the four downstream gates as skipped because the preflight is still failing.
