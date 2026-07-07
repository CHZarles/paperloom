# Product Launch Reading Flag Preflight Spec

## Problem

The launch sequence includes `ProductReadingLiveLaunchSmokeCli`, which is meant to exercise the 9-tool Product Reading ReAct path. That path is intentionally guarded by `paperloom.react.reading-phase1.enabled`, defaulting to `false`. The runtime preflight currently checks backend, infrastructure, MinerU, model keys, embedding keys, and trace config, but it does not check that the launch runtime explicitly enables Product Reading. A runtime can therefore appear closer to launch-ready while still being unable to run the live Product Reading gate.

## Required Behavior

1. Add an eleventh runtime preflight case named `reading_phase_flag`.
2. The case must inspect `PAPERLOOM_REACT_READING_PHASE1_ENABLED`, with shell environment values overriding `.env`.
3. The case must pass only when the value is exactly true in a case-insensitive boolean sense.
4. The case must fail with `CONFIG_MISSING` when the value is absent, blank, or not true.
5. `remediation.md` must include a non-secret fix telling the operator to set `PAPERLOOM_REACT_READING_PHASE1_ENABLED=true` for launch, without changing the product default.
6. Registry/docs must report `product-launch-runtime-preflight` as 11 cases.

## Non-Goals

- Do not change the default `paperloom.react.reading-phase1.enabled=false`.
- Do not bypass the flag in code.
- Do not start services or mutate `.env`.
- Do not add, remove, or rename Product Reading tools.
- Do not treat this flag as proof that the downstream live smoke passed; it is only a prerequisite.

## Verification

Run:

```bash
mvn -q -Dtest=ProductLaunchRuntimePreflightRunnerTest,ProductLaunchRuntimePreflightProbeTest,RagBenchmarkRegistryTest test
mvn -q test
git diff --check
```

Then run the local preflight:

```bash
mvn -q -DskipTests test-compile exec:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=com.yizhaoqi.smartpai.eval.ProductLaunchRuntimePreflightCli \
  -Dexec.args="--run-id 2026-07-07-product-launch-runtime-preflight-reading-flag-audit --timeout-seconds 5"
```

Expected current local status: not launch-ready, with `reading_phase_flag` failing until the active launch runtime sets `PAPERLOOM_REACT_READING_PHASE1_ENABLED=true`.
