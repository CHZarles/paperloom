# Product Launch Frontend Starter Plan

**Goal:** Remove the new `frontend_http` launch blocker by giving the local launch flow a
repeatable, non-secret frontend starter/status wrapper, analogous to the existing MinerU sidecar
wrapper.

**Current evidence:** `ProductLaunchRuntimePreflightCli` now checks the frontend at
`PAPERLOOM_FRONTEND_BASE_URL`, defaulting to `http://127.0.0.1:9527`. The latest local readiness run
fails `frontend_http` because nothing is serving the SPA shell at that URL. Provider credentials are
still external blockers, but the frontend blocker is actionable inside the repo.

## Grill Decisions

1. Should the top-level readiness wrapper start the frontend automatically?
   - Recommended answer: no. Keep `paperloom-launch-readiness-local.sh` as an honest checker. Add a
     separate explicit starter so launch evidence never hides what was started.

2. Should this starter use the dev server or a production preview?
   - Recommended answer: use the existing Vite dev command on port `9527` for the local launch loop,
     because `PAPERLOOM_FRONTEND_BASE_URL` already defaults there and the repo has `pnpm dev`.

3. What is the smallest health check?
   - Recommended answer: GET `/` and require HTTP `200` plus `id="app"`, matching `frontend_http`.

4. Should the script print secrets?
   - Recommended answer: no. Frontend startup has no launch secrets; status output should show only
     pid, log path, URL, HTTP code, and SPA-marker state.

5. What if the frontend is already running?
   - Recommended answer: `start` should be idempotent. If the pid is alive or the target URL is
     already healthy, print status and exit 0.

6. Should this loop add browser E2E coverage?
   - Recommended answer: no. This loop only makes the served frontend runtime explicit. Browser
     upload/chat automation remains a later closed loop.

## Scope

- Add `scripts/paperloom-start-frontend.sh` with `start`, `status`, `restart`, `stop`, and
  `--dry-run`.
- Default host/port to `127.0.0.1:9527`.
- Write pid/log files under ignored `.runtime/`.
- Use `pnpm --dir frontend dev -- --host ... --port ... --strictPort`.
- Poll the served URL until it returns the SPA shell marker.
- Update launch docs to run the frontend starter before readiness.
- Do not change Product Reading tools, launch gate order, or provider credential handling.

## TDD Seams

- Script CLI dry-run output.
- Script status output when the frontend is not running.
- Live script start/status/stop against the local Vite runtime during verification.

## Tasks

- [x] Write frontend starter spec.
- [x] Add the shell wrapper.
- [x] Update docs.
- [x] Run script dry-run/status and frontend start/status checks.
- [x] Run focused/full verification and local readiness.
- [x] Commit the loop.
