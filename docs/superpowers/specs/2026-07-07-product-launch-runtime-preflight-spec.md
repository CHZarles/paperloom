# Product Launch Runtime Preflight Spec

## Problem

The current launch gates fail before product behavior can be evaluated: backend login is unavailable, the configured MySQL port is not listening, MinerU is unavailable, no fresh Product Reading traces exist, and model/embedding keys are blank. We need a small preflight gate that makes runtime readiness explicit before running the 30-PDF seed and Product Reading gates.

## Required Behavior

1. Provide `ProductLaunchRuntimePreflightCli`.
   - Reads `.env` by default.
   - Supports `--env`, `--api-base`, `--username`, `--password`, `--runs-root`, `--run-id`, and `--timeout-seconds`.
   - Writes standard eval artifacts under `eval/rag/runs`.

2. Provide `ProductLaunchRuntimePreflightRunner`.
   - Parses key/value `.env` lines.
   - Generates one eval case per launch-critical runtime check.
   - Runs checks through an injected `RuntimeProbe`.
   - Records pass/fail, failure classes, and diagnostics per case.

3. Required checks:
   - `backend_login`: login through `/api/v1/users/login` returns a token.
   - `mysql_tcp`: host/port from `SPRING_DATASOURCE_URL` is reachable.
   - `redis_tcp`: `SPRING_DATA_REDIS_HOST` / `SPRING_DATA_REDIS_PORT` is reachable.
   - `kafka_tcp`: first host/port from `SPRING_KAFKA_BOOTSTRAP_SERVERS` is reachable.
   - `minio_health`: `MINIO_ENDPOINT/minio/health/live` returns 2xx.
   - `elasticsearch_health`: Elasticsearch host/port returns an accepted health status.
   - `mineru_health`: `PAPER_PARSING_MINERU_BASE_URL` plus health path returns 2xx.
   - `llm_key`: `DEEPSEEK_API_KEY` is nonblank.
   - `embedding_key`: `EMBEDDING_API_KEY` is nonblank.
   - `trace_config`: `PAPERLOOM_TRACE_ENABLED` is not false and `PAPERLOOM_TRACE_ROOT` is usable or defaults to `data/traces/product-react`.
   - `reading_phase_flag`: `PAPERLOOM_REACT_READING_PHASE1_ENABLED` is explicitly true for launch runs.

4. Failure classes:
   - `RUNTIME_UNAVAILABLE` for unreachable backend, TCP services, or HTTP health checks.
   - `CONFIG_MISSING` for missing required configuration or blank keys.
   - `CONFIG_INVALID` for unparseable host/port/URL configuration.

5. Register and document the gate.
   - Add `product-launch-runtime-preflight` to `eval/rag/harnesses.yaml`.
   - Document the preflight command and launch order in `eval/rag/README.md`.
   - Add registry tests.

## Non-Goals

- Do not start or stop services.
- Do not modify `.env`.
- Do not create users or mutate product data.
- Do not replace the 30-PDF data seed, live reading smoke, trace eval, or parser smoke.
- Do not validate database schema or parser output; downstream gates own those checks.
- Do not promote the default Product Reading flag; it stays disabled unless the launch runtime opts in.

## Launch Gate Order

```bash
mvn -q -DskipTests test-compile exec:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=com.yizhaoqi.smartpai.eval.ProductLaunchRuntimePreflightCli

mvn -q -DskipTests test-compile exec:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=com.yizhaoqi.smartpai.eval.ProductPdfLaunchDataSeedCli

mvn -q -DskipTests test-compile exec:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=com.yizhaoqi.smartpai.eval.ProductReadingLiveLaunchSmokeCli

mvn -q -DskipTests test-compile exec:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=com.yizhaoqi.smartpai.eval.ProductReadingLaunchTraceEvalCli

mvn -q -DskipTests test-compile exec:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=com.yizhaoqi.smartpai.eval.ProductPdfParserSmokeCli \
  -Dexec.args="--manifest eval/rag/pdf-parser/product-pdf-launch-30-manifest.jsonl"
```

Launch-ready means all five gates produce passing scorecards on the same active runtime.
