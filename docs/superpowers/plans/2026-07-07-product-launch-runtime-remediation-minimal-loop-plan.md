# Product Launch Runtime Remediation Plan

**Goal:** Close the next smallest launch loop after the runtime preflight proved the product is not launch-ready.

**Current evidence:** `ProductLaunchRuntimePreflightCli` produced `5/10` in
`eval/rag/runs/2026-07-07-product-launch-runtime-preflight-after-commit-audit`.

## Grill Decisions

1. Should we run the 30-PDF data seed again now?
   - Recommended answer: no. The runtime preflight is already failing, so the data seed would only rediscover backend, database, MinerU, and key availability failures later and more noisily.

2. What is the smallest useful loop?
   - Recommended answer: make the preflight produce a non-secret `remediation.md` checklist in every run directory, so a failing launch gate immediately tells the operator what must be fixed before the expensive gates run.

3. Should the remediation mutate `.env`, start Docker, start the backend, or provide fake credentials?
   - Recommended answer: no. It should only report exact blockers and safe next actions. Launch evidence must remain honest.

4. Should shell environment variables be allowed to override `.env` for the preflight?
   - Recommended answer: yes. The preflight should match real launch practice where exported environment variables can override file defaults.

5. What proves this loop worked?
   - Recommended answer: focused tests verify the remediation artifact for passing and failing preflight runs; a local preflight run writes `remediation.md` with the current five blockers.

## Scope

- Add a remediation artifact to `ProductLaunchRuntimePreflightRunner`.
- Keep all existing scorecard/run artifacts unchanged.
- Do not change Product Reading tools.
- Do not change launch gate order.
- Do not hide or bypass the current launch blockers.

## Tasks

- [x] Add runner tests for `remediation.md` on passing and failing preflight runs.
- [x] Implement non-secret remediation markdown generation.
- [x] Document the new artifact in the RAG eval README.
- [x] Run focused tests, full tests, and `git diff --check`.
- [x] Run the local runtime preflight again and inspect the generated remediation artifact.
