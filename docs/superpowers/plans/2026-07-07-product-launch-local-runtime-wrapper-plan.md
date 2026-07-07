# Product Launch Local Runtime Wrapper Plan

**Goal:** Turn the non-secret launch runtime steps that just improved preflight from `5/11` to
`9/11` into repeatable scripts, so the next launch attempt does not depend on manual shell state.

**Current evidence:** With Docker infrastructure running, the backend started with a MySQL
`13306` override, Product Reading explicitly enabled, and the local MinerU sidecar started on
`127.0.0.1:8000`, `ProductLaunchReadinessCli` now reaches runtime preflight `9/11`. The only
remaining preflight blockers are `DEEPSEEK_API_KEY` and `EMBEDDING_API_KEY`.

## Grill Decisions

1. Should this loop fabricate or repurpose unrelated API keys?
   - Recommended answer: no. Launch evidence must use explicit product model and embedding
     credentials. Do not silently reuse unrelated agent credentials.

2. Should this loop edit ignored `.env` or commit secrets?
   - Recommended answer: no. Keep secrets out of git. Use an optional ignored runtime env overlay.

3. What is the smallest useful improvement?
   - Recommended answer: add scripts that start/check the local MinerU sidecar and run the launch
     readiness wrapper with the known non-secret local overrides.

4. Should the script auto-enable Product Reading for launch runs?
   - Recommended answer: yes. The product default stays disabled; launch commands explicitly set
     `PAPERLOOM_REACT_READING_PHASE1_ENABLED=true`.

5. Should the script hard-code the observed Docker MySQL host port?
   - Recommended answer: no. Detect the published `pai_smart_mysql` port and rewrite only the local
     default `localhost:3306` JDBC target when the Docker port differs.

6. What proves this loop worked?
   - Recommended answer: shell syntax checks, dry-run output that shows no secrets, MinerU health
     succeeds, and a real readiness run still fails honestly on only the missing product credentials.

## Scope

- Add a local MinerU sidecar start/check script.
- Add a local launch readiness wrapper that sources `.env` plus an optional ignored env overlay,
  applies non-secret launch overrides, and runs `ProductLaunchReadinessCli`.
- Add a tracked example env overlay template that names required secret variables without values.
- Do not change Product Reading tools.
- Do not change the launch gate order.
- Do not fake model, embedding, parser, frontend, or backend evidence.

## Tasks

- [x] Write the local runtime wrapper spec.
- [x] Add `scripts/paperloom-start-mineru.sh`.
- [x] Add `scripts/paperloom-launch-readiness-local.sh`.
- [x] Add a tracked secret-free launch env overlay example.
- [x] Document the local launch wrapper commands.
- [x] Run shell syntax checks, script dry-runs, and local readiness.
- [x] Commit the loop.
