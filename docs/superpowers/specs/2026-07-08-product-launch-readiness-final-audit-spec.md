# Product Launch Readiness Final Audit Spec

## Problem

The runtime now uses MiniMax from persisted model-provider config, and the 30-PDF library has already been rebuilt through the current pipeline. Two launch-readiness gates can still give misleading results:

1. Runtime preflight checks static `.env` DeepSeek/Aliyun keys instead of the active backend provider configs.
2. The launch data seed gate attempts to upload every manifest PDF even when the same content-hash paper is already uploaded, vectorized, and searchable.

Both behaviors produce false launch blockers and encourage hacks. The gates should test the real product runtime and treat current ready data as evidence, not noise.

## Required Behavior

1. `ProductLaunchRuntimePreflightCli` must keep its infrastructure checks: backend login, frontend shell, MySQL, Redis, Kafka, MinIO, Elasticsearch, MinerU, trace config, and Product Reading flag.
2. Runtime preflight must replace static `DEEPSEEK_API_KEY`/`EMBEDDING_API_KEY` provider checks with two backend active-provider checks:
   - `llm_active_provider_smoke`
   - `embedding_active_provider_smoke`
3. Each active-provider smoke must log in through the backend, fetch `/admin/model-providers`, identify the active provider for the requested scope, then call `/admin/model-providers/{scope}/test` with a blank `apiKey` so the backend uses its stored secret.
4. Active-provider smoke diagnostics must include scope, provider, API style, model, dimension, `hasApiKey`, HTTP status, success flag, and latency/message if available. It must not include plaintext secrets, tokens, passwords, or ciphertext.
5. The embedding smoke must pass for MiniMax `minimax-embedding`/`embo-01` through the backend's native MiniMax test path.
6. `ProductPdfLaunchDataSeedRunner` must compute each manifest PDF's content-hash paper ID before upload. If the current uploaded-paper list already contains that ID and `isFrontendSearchable(status)` is true, it must skip upload/merge and pass the case as existing data.
7. Existing-seeded diagnostics must record `alreadySeeded=true`, `merged=false`, `frontendSearchable=true`, and the frontend status.
8. If an existing paper is present but not frontend-searchable, the seed gate must continue to upload/merge or fail normally; it must not hide unready data.
9. Existing CLIs, scorecard format, and hard-gate exit behavior must remain compatible.

## Non-Goals

- Do not restore DeepSeek/Aliyun as required launch providers.
- Do not bypass the backend model-provider config.
- Do not mark unready existing data as ready.
- Do not delete or reset runtime data in this spec.
- Do not make Product Reading enabled by default outside launch runs.

## Verification

Run:

```bash
mvn -q -Dtest=ProductLaunchRuntimePreflightRunnerTest,ProductLaunchRuntimePreflightProbeTest,ProductPdfLaunchDataSeedRunnerTest test
mvn -q test
pnpm --dir frontend typecheck
bash -n scripts/paperloom-launch-readiness-local.sh
git diff --check
```

Then run the local launch gates against the active MiniMax runtime:

```bash
scripts/paperloom-launch-readiness-local.sh --run-id 2026-07-08-product-launch-readiness-final-audit --timeout-seconds 10
```

Launch-ready requires all top-level gates to pass and fresh `PRODUCT_READING_REACT_TURN` traces to pass trace eval.

## Verified Artifact

The closing launch run is `eval/rag/runs/2026-07-08-product-launch-readiness-final-frontend-action`.
It passed 5/5 top-level gates:

- `product-launch-runtime-preflight`: 12/12
- `product-pdf-launch-data-seed`: 30/30
- `product-reading-live-launch-smoke`: 9/9
- `product-reading-launch-trace-eval`: 9/9
- `product-pdf-parser-smoke`: 30/30

The seed child run reports 30 unique manifest paper IDs, all frontend-searchable, all recognized as existing current-pipeline data, and no forced re-merge.
