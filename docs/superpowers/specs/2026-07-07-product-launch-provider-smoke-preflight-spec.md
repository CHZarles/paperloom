# Product Launch Provider Smoke Preflight Spec

## Problem

The launch runtime preflight currently checks only that `DEEPSEEK_API_KEY` and `EMBEDDING_API_KEY`
are nonblank. That is not strong enough for launch readiness: a typo, expired key, wrong base URL,
wrong model, or unreachable provider would pass preflight and fail later during the 30-PDF seed or
live Product Reading smoke. The next minimal loop should keep secrets external while proving the
configured providers are callable.

## Required Behavior

### Runtime Cases

Add two preflight cases after `llm_key` and `embedding_key`:

1. `llm_api_smoke`
   - Uses `DEEPSEEK_API_URL`, `DEEPSEEK_API_MODEL`, and `DEEPSEEK_API_KEY`.
   - Sends one tiny OpenAI-compatible `/chat/completions` request.
   - Passes only when the provider returns a 2xx response with a nonblank assistant message.

2. `embedding_api_smoke`
   - Uses `EMBEDDING_API_URL`, `EMBEDDING_API_MODEL`, `EMBEDDING_API_KEY`, and optional
     `EMBEDDING_DIMENSION`.
   - Sends one tiny OpenAI-compatible `/embeddings` request.
   - Passes only when the provider returns a 2xx response with at least one embedding vector.

### Failure Classes

- If the corresponding key is blank, the smoke case must fail as `CONFIG_MISSING` without sending an
  outbound provider request.
- If the provider returns `401`, `403`, or another 4xx, the smoke case must fail as
  `CONFIG_INVALID`.
- If the provider times out, cannot be reached, returns 5xx, or returns malformed success JSON, the
  smoke case must fail as `PROVIDER_UNAVAILABLE`.

### Secret Handling

Artifacts must never include:

- API key values;
- bearer headers;
- full provider responses;
- private prompts or user data.

Artifacts may include:

- case ID;
- provider base URL;
- model;
- request path;
- HTTP status;
- non-secret response shape booleans such as `assistantMessagePresent` or `embeddingPresent`.

### Remediation

`remediation.md` must include non-secret advice for:

- `llm_api_smoke`;
- `embedding_api_smoke`.

The guidance should distinguish fixing missing/invalid credentials from provider URL/model/runtime
availability, but it must not print any secret values.

## Non-Goals

- Do not add, remove, or rename Product Reading tools.
- Do not commit credentials.
- Do not silently reuse `OPENAI_API_KEY` or any unrelated agent credential.
- Do not run the 30-PDF seed when preflight fails.
- Do not change the launch gate order.

## Verification

Run:

```bash
mvn -q -Dtest=ProductLaunchRuntimePreflightRunnerTest,ProductLaunchRuntimePreflightProbeTest,RagBenchmarkRegistryTest test
mvn -q test
git diff --check
scripts/paperloom-launch-readiness-local.sh --run-id 2026-07-07-product-launch-readiness-provider-smoke-audit --timeout-seconds 5
```

Expected current local status: not launch-ready while the product LLM and embedding keys are
missing. Once keys are supplied, preflight should also prove both providers are callable before the
30-PDF seed starts.
