# Product Launch Frontend Starter Spec

## Problem

The launch runtime preflight now correctly fails when the frontend is not served, but there is no
repo-local wrapper to start and check the frontend in the same style as `paperloom-start-mineru.sh`.
Operators must remember the frontend command and port manually, which makes local launch evidence
less repeatable.

## Required Behavior

Add `scripts/paperloom-start-frontend.sh`.

### Commands

- `start`
  - If the recorded pid is alive, print status and exit 0.
  - If the target URL already returns the SPA shell marker, print status and exit 0.
  - Otherwise start the frontend with:
    `pnpm --dir frontend dev -- --host <host> --port <port> --strictPort`
  - Write the pid to `.runtime/frontend-vite.pid`.
  - Write logs to `.runtime/logs/frontend-vite-<port>.log`.
  - Poll until the health URL returns HTTP `200` and a body containing `id="app"`.

- `status`
  - Print:
    - `frontend_pid`
    - `frontend_process_alive`
    - `frontend_url`
    - `frontend_http_code`
    - `frontend_spa_shell`

- `restart`
  - Stop the recorded pid, then start.

- `stop`
  - Stop only the recorded pid when it is alive.
  - Remove the pid file after stop.

### Options

- `--dry-run`
- `--host HOST`
- `--port PORT`
- `--frontend-dir PATH`
- `--pid-file PATH`
- `--log-file PATH`
- `start|status|restart|stop`

### Environment Defaults

- `PAPERLOOM_FRONTEND_HOST=127.0.0.1`
- `PAPERLOOM_FRONTEND_PORT=9527`
- `PAPERLOOM_FRONTEND_DIR=frontend`
- `PAPERLOOM_FRONTEND_PID_FILE=.runtime/frontend-vite.pid`
- `PAPERLOOM_FRONTEND_LOG_FILE=.runtime/logs/frontend-vite-<port>.log`
- `PAPERLOOM_FRONTEND_HEALTH_TIMEOUT_SECONDS=120`

## Non-Goals

- Do not start backend, Docker, MinerU, or providers.
- Do not print or manage provider credentials.
- Do not modify application defaults.
- Do not add browser E2E automation in this slice.

## Verification

```bash
scripts/paperloom-start-frontend.sh --dry-run start
scripts/paperloom-start-frontend.sh status
scripts/paperloom-start-frontend.sh start
scripts/paperloom-start-frontend.sh status
mvn -q -Dtest=ProductLaunchRuntimePreflightRunnerTest,ProductLaunchRuntimePreflightProbeTest,RagBenchmarkRegistryTest test
mvn -q test
git diff --check
scripts/paperloom-launch-readiness-local.sh --run-id 2026-07-07-product-launch-readiness-frontend-started-audit --timeout-seconds 5
```

Expected local readiness after this loop: `frontend_http` passes when the starter is running; launch
still remains not ready until product model and embedding provider credentials are present and
callable.
