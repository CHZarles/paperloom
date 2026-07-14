# Development Guide

## Repository Areas

| Path | Purpose |
| --- | --- |
| `src/main/java/` | Spring Boot product backend |
| `src/test/java/` | Backend tests and evaluation contract tests |
| `frontend/` | Folio Vue application and Playwright tests |
| `harness_py/` | Python research harness and unit tests |
| `research/golden-data/` | Evidence-first golden assets and analysis tools |
| `eval/rag/` | Retrieval benchmarks and scorecard artifacts |
| `docs/` | Maintained product documentation and engineering evolution |
| `site/` | VitePress project site and practice journal |

## Backend

Compile after a narrow Java change:

```bash
mvn -q -DskipTests compile
```

Run all tests:

```bash
mvn test
```

Run a focused test class:

```bash
mvn -Dtest=PaperReadingModelBuilderTest test
```

The backend normally reads the repository-root `.env` and listens on port `8081`.

## Research Harness

```bash
.venv-harness/bin/python -m unittest discover -s harness_py/tests
.venv-harness/bin/python -m harness_py --help
```

Use saved runs for offline diagnosis where possible. Model-backed evaluation can be expensive and
should record provider, model, case set, budgets, token usage, and output artifacts.

## Frontend

```bash
cd frontend
pnpm typecheck
pnpm lint
pnpm build
pnpm test:e2e
```

The local Vite target is normally `http://localhost:9527`.

## Project Site

```bash
cd site
npm ci
npm run docs:dev
```

Production verification:

```bash
npm run docs:build
npm run docs:preview
```

## Documentation Rules

- Current guides describe current code and commands.
- ADRs record accepted architectural decisions.
- Evolution reports preserve consequential experiments, including failed ones.
- Raw prompts, generated repository wikis, temporary plans, and private runtime observations do not
  belong in public documentation.
- Claims involving quality, latency, recall, or cost must state the measurement context.
- Use repository-relative commands. Do not commit personal absolute paths.

## Before Handoff

1. Inspect the diff for unrelated user changes.
2. Run the checks relevant to the changed surface.
3. Verify new links and examples.
4. Scan for credentials, private data, and local paths.
5. Record checks that could not be run and why.
