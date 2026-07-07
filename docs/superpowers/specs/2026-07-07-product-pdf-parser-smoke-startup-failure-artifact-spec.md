# Product PDF Parser Smoke Startup-Failure Artifact Spec

## Problem

`ProductPdfParserSmokeCli` is the final launch gate over the 30 real-PDF manifest. It starts a Spring application context, obtains repositories, then calls `ProductPdfParserSmokeRunner`. If Spring or JPA startup fails before the runner starts, the CLI can fail without writing `run.json`, `scorecard.json`, or `report.md`. That leaves the launch attempt less traceable than the runtime preflight, 30-PDF data seed, live smoke, and trace eval gates.

## Required Behavior

1. `ProductPdfParserSmokeCli.runCommand` must return a run directory when Spring/application-context startup fails after CLI options have been parsed.
2. The failed run must include every case from the configured `--manifest`.
3. Every case in a startup-failure run must fail with `RUNTIME_UNAVAILABLE`.
4. Diagnostics must include a non-secret startup failure message and the manifest fields already useful for parser-smoke debugging.
5. `main` must still exit non-zero through the existing scorecard hard gate.
6. The normal successful startup path must remain unchanged.

## Non-Goals

- Do not bypass runtime preflight.
- Do not fake parser output, chunks, parser artifacts, visual assets, Reading Model rows, or paper rows.
- Do not change parser-smoke pass/fail semantics for a successfully started Spring context.
- Do not print passwords, tokens, model keys, embedding keys, or database credentials.

## Verification

Run:

```bash
mvn -q -Dtest=ProductPdfParserSmokeCliTest,ProductPdfParserSmokeRunnerTest,RagEvalGateStatusTest test
mvn -q test
git diff --check
```

Expected behavior: a Spring startup failure still produces a failed parser-smoke eval run, and CLI `main` remains a hard gate via `scorecard.json`.
