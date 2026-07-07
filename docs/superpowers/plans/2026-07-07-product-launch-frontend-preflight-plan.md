# Product Launch Frontend Runtime Preflight Plan

**Goal:** Make launch readiness prove that the Product frontend is actually served, not just that
backend and infrastructure dependencies are alive.

**Current evidence:** The top-level launch wrapper still stops at runtime preflight because product
LLM and embedding credentials are missing. The existing preflight covers backend login,
infrastructure, MinerU, providers, trace config, and Product Reading enablement, but it does not
check the browser frontend runtime even though launch readiness is meant to cover frontend-to-backend
behavior.

## Grill Decisions

1. Is the frontend part of launch readiness, or only the backend API/WebSocket boundary?
   - Recommended answer: the frontend is part of launch readiness. A product cannot be called
     launch-ready if the browser app is not served.

2. What is the smallest useful frontend check?
   - Recommended answer: add one runtime preflight case, `frontend_http`, that fetches the configured
     frontend base URL and requires the SPA shell marker.

3. Should this become a sixth launch gate?
   - Recommended answer: no. Frontend availability is a runtime dependency and should fail early in
     `ProductLaunchRuntimePreflightCli` before the 30-PDF seed and live smoke gates.

4. What should be configurable?
   - Recommended answer: `PAPERLOOM_FRONTEND_BASE_URL`, defaulting to `http://127.0.0.1:9527` for the
     local Vite dev server. The local wrapper should print this non-secret target in dry-run output.

5. What proves the frontend app rather than a random HTTP service?
   - Recommended answer: require HTTP 200 and a response body containing `id="app"`, which is present
     in both the dev `index.html` and built `dist/index.html`.

6. Should the preflight click through UI flows?
   - Recommended answer: not in this slice. Browser automation is a follow-up; this loop only prevents
     a missing frontend runtime from being invisible.

## Scope

- Add `frontend_http` to `ProductLaunchRuntimePreflightRunner`.
- Extend the HTTP probe to support an optional required response-body marker.
- Add remediation text for frontend runtime failures.
- Update launch docs, harness case count, local wrapper dry-run summary, and the secret-free launch
  env example.
- Do not start the frontend automatically.
- Do not fake browser evidence or claim full frontend E2E coverage.

## TDD Seams

- `ProductLaunchRuntimePreflightRunner`: request construction, scorecard case count, and remediation
  artifacts.
- `ProductLaunchRuntimePreflightProbe`: public `RuntimeProbe.check` behavior for HTTP body-marker
  validation.
- `scripts/paperloom-launch-readiness-local.sh`: dry-run output for non-secret frontend target.

## Tasks

- [x] Write frontend preflight spec.
- [x] Add failing runner/probe tests for `frontend_http`.
- [x] Implement frontend request construction, body-marker validation, and remediation.
- [x] Update docs, harness metadata, wrapper dry-run, and env example.
- [x] Run focused tests, full tests, `git diff --check`, and local readiness.
- [x] Commit the loop.
