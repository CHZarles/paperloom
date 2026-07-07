# Product Launch Frontend Runtime Preflight Spec

## Problem

Launch readiness is meant to cover frontend-to-backend behavior, but the current runtime preflight
does not prove that the browser frontend is served. The launch wrapper can therefore produce detailed
backend, provider, trace, and parsing evidence while a missing or mispointed frontend dev/prod server
remains invisible until a human opens the app.

## Required Behavior

### Runtime Case

Add one preflight case after `backend_login`:

`frontend_http`

- Reads `PAPERLOOM_FRONTEND_BASE_URL` from process env or `.env`.
- Defaults to `http://127.0.0.1:9527`.
- Sends a GET request to the normalized frontend base URL.
- Passes only when the response status is `200` and the body contains `id="app"`.
- Writes non-secret diagnostics:
  - `url`
  - `acceptedStatuses`
  - `requiredBodyContains`
  - `status`
  - `bodyMarkerPresent`

### Failure Classes

- Connection failures, timeout, non-200 status, or missing SPA marker fail as `RUNTIME_UNAVAILABLE`.
- The remediation artifact must point operators to `PAPERLOOM_FRONTEND_BASE_URL` and the current
  target URL.

### Local Wrapper

`scripts/paperloom-launch-readiness-local.sh --dry-run` must include the effective frontend base URL
without printing secrets. The wrapper may default `PAPERLOOM_FRONTEND_BASE_URL` to
`http://127.0.0.1:9527`; it must not start the frontend.

### Documentation

- Update `eval/rag/harnesses.yaml` preflight case count.
- Update `eval/rag/README.md` to list frontend serving as part of runtime preflight.
- Update `docs/launch/product-launch.env.example` with the optional frontend URL.

## Non-Goals

- Do not add Playwright/browser UI automation in this slice.
- Do not start Docker, backend, frontend, MinerU, or providers automatically.
- Do not relax provider credential smoke checks.
- Do not change Product Reading tools or application defaults.

## Verification

Run:

```bash
mvn -q -Dtest=ProductLaunchRuntimePreflightRunnerTest,ProductLaunchRuntimePreflightProbeTest,RagBenchmarkRegistryTest test
mvn -q test
git diff --check
scripts/paperloom-launch-readiness-local.sh --run-id 2026-07-07-product-launch-readiness-frontend-preflight-audit --timeout-seconds 5
```

Expected current local status: not launch-ready until product provider credentials are supplied and
the frontend is served at `PAPERLOOM_FRONTEND_BASE_URL`.
