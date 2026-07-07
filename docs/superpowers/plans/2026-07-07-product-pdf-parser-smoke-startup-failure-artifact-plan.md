# Product PDF Parser Smoke Startup-Failure Artifact Plan

**Goal:** Keep the final 30-PDF parser launch gate evaluable even when Spring/JPA startup fails before repository checks can run.

**Current evidence:** The launch CLIs are hard gates and the live seed/live smoke paths now preserve failed artifacts for startup failures. `ProductPdfParserSmokeCli` still starts a Spring context before `ProductPdfParserSmokeRunner` writes `run.json`, `scorecard.json`, and `report.md`, so database or application-context startup failures can exit without a parser-smoke scorecard.

## Grill Decisions

1. Should we run downstream launch gates while runtime preflight is failing?
   - Recommended answer: no. The preflight remains the first launch gate. This loop only makes the parser gate leave artifacts if someone runs it against a broken runtime.

2. What is the smallest useful fix?
   - Recommended answer: catch Spring/application-context startup failures in `ProductPdfParserSmokeCli.runCommand` and write a failed parser-smoke eval run over the configured manifest cases.

3. How should every manifest case fail when startup fails?
   - Recommended answer: fail every case as `RUNTIME_UNAVAILABLE`, because the parser evidence boundary never opened. Do not misclassify these as missing paper rows, chunks, parser artifacts, or visual assets.

4. Should `main` still exit non-zero?
   - Recommended answer: yes. Artifacts are for diagnosis and traceability; the hard-gate scorecard must still fail.

5. Should this change parser validation semantics?
   - Recommended answer: no. Normal successful startup behavior stays unchanged, including all current checks for PDF provenance, chunks, parser artifacts, visual assets, and Reading Model elements.

## Scope

- Add a startup-failure run path to `ProductPdfParserSmokeRunner`.
- Catch Spring/application-context startup failures in `ProductPdfParserSmokeCli.runCommand`.
- Add a CLI test proving failed parser-smoke artifacts are written on startup failure.
- Document that parser smoke preserves failed artifacts for startup failures.

## Tasks

- [x] Add a test for startup failure writing a failed parser-smoke eval run for all manifest cases.
- [x] Implement startup-failure run writing with `RUNTIME_UNAVAILABLE`.
- [x] Document that parser smoke preserves failed artifacts for startup failures.
- [x] Run focused tests, full tests, and `git diff --check`.
- [ ] Commit the loop.
