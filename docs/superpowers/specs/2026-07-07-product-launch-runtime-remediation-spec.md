# Product Launch Runtime Remediation Spec

## Problem

The launch runtime preflight now blocks expensive launch gates when the runtime is not ready, but the operator still has to inspect `run.json` manually and translate failures into actions. The current local evidence is `5/10`: backend login, MySQL TCP, MinerU health, LLM key, and embedding key fail. The next minimal loop should make those failures directly actionable without faking launch readiness.

## Required Behavior

1. `ProductLaunchRuntimePreflightRunner` must write `remediation.md` next to `run.json`, `scorecard.json`, and `report.md`.
2. The remediation file must include:
   - run-level launch readiness status;
   - a statement that downstream 30-PDF and Product Reading gates should not run until every current preflight case passes;
   - one bullet per failed case with a non-secret suggested fix;
   - the launch gate order to run after preflight passes.
3. The remediation file must never include secret values:
   - no login password;
   - no API key values;
   - no embedding key values.
4. Case-specific advice must cover:
   - `backend_login`;
   - `mysql_tcp`;
   - `redis_tcp`;
   - `kafka_tcp`;
   - `minio_health`;
   - `elasticsearch_health`;
   - `mineru_health`;
   - `llm_key`;
   - `embedding_key`;
   - `trace_config`;
   - `reading_phase_flag`;
   - unknown cases via a generic inspect-`run.json` fallback.
5. Preflight configuration resolution may use shell environment variables as overrides over `.env` values, matching normal launch practice.

## Non-Goals

- Do not start or stop runtime services.
- Do not edit `.env`.
- Do not add fake keys or local fake model services.
- Do not run the 30-PDF seed when preflight fails.
- Do not add, remove, or rename Product Reading tools.

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
  -Dexec.args="--run-id 2026-07-07-product-launch-runtime-preflight-remediation-audit --timeout-seconds 5"
```

Expected current local status: not launch-ready until backend login, configured MySQL reachability, MinerU health, `DEEPSEEK_API_KEY`, and `EMBEDDING_API_KEY` are fixed.
