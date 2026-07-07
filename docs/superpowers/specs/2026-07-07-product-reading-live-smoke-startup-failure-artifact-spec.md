# Product Reading Live Smoke Startup-Failure Artifact Spec

## Problem

The Product Reading live launch smoke is one of the five launch gates. It currently logs in, creates a conversation, then drives WebSocket chat cases. If login or conversation creation fails, the CLI can fail before `ProductReadingLiveLaunchSmokeRunner` writes `run.json`, `scorecard.json`, and `report.md`. That leaves the launch attempt less evaluable than the data seed and preflight gates.

## Required Behavior

1. `ProductReadingLiveLaunchSmokeCli.runCommand` must return a run directory even when backend login or conversation creation fails.
2. The failed run must include every case from `eval/rag/product-reading-live-launch-smoke-cases.jsonl` or the configured `--cases` file.
3. Every case in a startup-failure run must fail with `RUNTIME_UNAVAILABLE`.
4. Diagnostics must include a non-secret startup failure message.
5. `main` must still exit non-zero through the existing scorecard hard gate.
6. The normal successful startup path must remain unchanged.

## Non-Goals

- Do not run live smoke when runtime preflight fails.
- Do not fake WebSocket events, tool calls, references, or product state items.
- Do not add, remove, or rename Product Reading tools.
- Do not print passwords, tokens, model keys, or embedding keys.

## Verification

Run:

```bash
mvn -q -Dtest=ProductReadingLiveLaunchSmokeCliTest,ProductReadingLiveLaunchSmokeRunnerTest,RagEvalGateStatusTest test
mvn -q test
git diff --check
```

Expected behavior: a login failure still produces a failed live-smoke eval run, and CLI `main` remains a hard gate via `scorecard.json`.
