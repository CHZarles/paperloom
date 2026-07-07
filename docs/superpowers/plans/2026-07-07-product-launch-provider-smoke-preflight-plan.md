# Product Launch Provider Smoke Preflight Plan

**Goal:** Make launch preflight prove that configured model and embedding providers are callable,
not merely that `DEEPSEEK_API_KEY` and `EMBEDDING_API_KEY` are nonblank.

**Current evidence:** After starting the backend, Docker dependencies, Product Reading launch flag,
and MinerU sidecar through local wrappers, `ProductLaunchReadinessCli` reaches runtime preflight
`9/11`. The only current blockers are missing product LLM and embedding keys. Once keys are supplied,
the current preflight would accept any nonblank value and defer invalid-provider failures to the
30-PDF seed or live Product Reading smoke.

## Grill Decisions

1. Should this loop provide or infer model credentials?
   - Recommended answer: no. Credentials remain external runtime state and must not be guessed,
     copied from unrelated agent credentials, printed, or committed.

2. What is the smallest useful improvement?
   - Recommended answer: add two runtime preflight cases: one tiny chat-completions smoke and one
     tiny embeddings smoke against the configured launch providers.

3. Should the smoke run before the nonblank key checks pass?
   - Recommended answer: yes, but fail as `CONFIG_MISSING` without sending a request when the
     corresponding key is blank. This keeps the gate order and artifact shape explicit.

4. What should the smoke record?
   - Recommended answer: provider base URL, model, request path, status, and response shape only.
     Never record API keys, bearer headers, prompts with private data, or full provider responses.

5. How should invalid keys differ from unreachable providers?
   - Recommended answer: `401`/`403` and other 4xx provider responses are `CONFIG_INVALID`;
     connection failures, timeouts, and 5xx are `PROVIDER_UNAVAILABLE`.

6. What test seam should we use?
   - Recommended answer: `ProductLaunchRuntimePreflightRunner` for request construction/artifact
     behavior and `ProductLaunchRuntimePreflightProbe` with local `HttpServer` for HTTP smoke
     behavior.

## Scope

- Add `llm_api_smoke` and `embedding_api_smoke` to `ProductLaunchRuntimePreflightRunner`.
- Add provider smoke handling to `ProductLaunchRuntimePreflightProbe`.
- Extend remediation text with non-secret guidance for missing, invalid, or unavailable providers.
- Document the stronger preflight in `eval/rag/README.md`.
- Do not change Product Reading tools, launch gate order, or application defaults.

## Tasks

- [x] Write provider smoke preflight spec.
- [x] Add failing runner/probe tests for provider smoke.
- [x] Implement provider smoke requests and probe behavior.
- [x] Update docs.
- [x] Run focused tests, full tests, `git diff --check`, and local readiness.
- [x] Commit the loop.
