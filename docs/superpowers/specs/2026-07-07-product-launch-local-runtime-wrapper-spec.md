# Product Launch Local Runtime Wrapper Spec

## Problem

Launch readiness currently depends on manual non-secret runtime setup:

- backend must use the Docker-published MySQL host port when it is not `3306`;
- Product Reading must be explicitly enabled for launch gates;
- the local MinerU sidecar must be started and health-checked;
- product LLM and embedding credentials must be supplied without committing secrets.

After starting backend and MinerU manually, runtime preflight reaches `9/11`; the remaining blockers
are only `DEEPSEEK_API_KEY` and `EMBEDDING_API_KEY`. The next closed loop should make this runtime
setup repeatable while preserving honest launch failure when credentials are absent.

## Required Behavior

### `scripts/paperloom-start-mineru.sh`

1. Start the local MinerU API sidecar from a configurable venv bin directory.
2. Default venv bin: `/home/charles/.local/share/paperloom-mineru/.venv/bin`.
3. Default host/port: `127.0.0.1:8000`.
4. Source `.runtime/mineru-gpu-env.txt` when present, without printing values.
5. Write PID and log files under ignored `.runtime/`.
6. Be idempotent when a live PID is already present.
7. Poll `/health` until HTTP 200 or timeout.
8. Support `status`, `start`, `restart`, `stop`, and `--dry-run`.

### `scripts/paperloom-launch-readiness-local.sh`

1. Source `.env` when present.
2. Source an optional ignored launch env overlay, default `.runtime/product-launch.env`.
3. Do not print secret values.
4. Export `PAPERLOOM_REACT_READING_PHASE1_ENABLED=true` for the launch command unless already set.
5. Default `PAPER_PARSING_MINERU_BASE_URL` to `http://127.0.0.1:8000` unless already set.
6. If Docker publishes `pai_smart_mysql` on a host port other than `3306`, rewrite only a local
   `jdbc:mysql://localhost:3306/...` datasource URL to that published port.
7. Run `ProductLaunchReadinessCli` with a generated run id unless `--run-id` is provided.
8. Support `--dry-run` that prints only non-secret launch settings and secret presence booleans.

### Env Overlay Example

Add a tracked, secret-free example file that shows the ignored overlay shape:

- `DEEPSEEK_API_KEY=`
- `EMBEDDING_API_KEY=`
- optional provider URL/model overrides

## Non-Goals

- Do not commit or print API keys.
- Do not silently reuse unrelated agent credentials such as `OPENAI_API_KEY`.
- Do not start Docker, backend, or frontend automatically from the readiness wrapper.
- Do not make Product Reading enabled by default in application config.
- Do not bypass `ProductLaunchRuntimePreflightCli`.

## Verification

Run:

```bash
bash -n scripts/paperloom-start-mineru.sh
bash -n scripts/paperloom-launch-readiness-local.sh
scripts/paperloom-start-mineru.sh --dry-run status
scripts/paperloom-launch-readiness-local.sh --dry-run --run-id local-wrapper-dry-run
mvn -q test
git diff --check
```

Then run:

```bash
scripts/paperloom-start-mineru.sh status
scripts/paperloom-launch-readiness-local.sh --run-id 2026-07-07-product-launch-readiness-local-wrapper-audit --timeout-seconds 5
```

Expected current local status: not launch-ready until `DEEPSEEK_API_KEY` and `EMBEDDING_API_KEY`
are supplied through the runtime environment or the ignored overlay.
