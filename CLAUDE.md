# CLAUDE.md

This file gives coding agents a compact entry point. Public product and engineering documentation
lives under [`docs/`](docs/README.md); do not duplicate those documents here.

## Project Contract

PaperLoom is an evidence-grounded research-paper RAG system. Folio is its user-facing application.
The product subject is research-paper PDFs, not a general enterprise document assistant.

Core invariants:

- Product papers and evaluation corpora remain separate data domains.
- Paper identity and conversation source scope are explicit.
- Unresolved semantic routing fails closed instead of silently entering paper QA.
- Search candidates are not automatically answer evidence.
- Durable conversations and historical reference mappings live in MySQL, not only Redis.
- Search chunks are derived from the current Paper Reading Model.
- Historical references cannot bypass current authorization.

Read these before changing shared behavior:

1. [`docs/architecture/overview.md`](docs/architecture/overview.md)
2. [`docs/reference/product-requirements.md`](docs/reference/product-requirements.md)
3. [`docs/reference/domain-language.md`](docs/reference/domain-language.md)
4. [`docs/adr/`](docs/adr/)

## Development Commands

Backend:

```bash
mvn -q -DskipTests compile
mvn test
mvn -Dtest=PaperReadingModelBuilderTest test
```

Research harness:

```bash
.venv-harness/bin/python -m unittest discover -s harness_py/tests
.venv-harness/bin/python -m harness_py --help
```

Frontend:

```bash
cd frontend
pnpm typecheck
pnpm lint
pnpm test:e2e
```

Project site:

```bash
cd site
npm ci
npm run docs:build
```

The normal local targets are backend `http://localhost:8081`, frontend
`http://localhost:9527`, research harness `http://127.0.0.1:8091`, and MinerU
`http://127.0.0.1:8000`.

## Change Discipline

- Follow existing module boundaries before introducing new abstractions.
- Keep edits scoped to the requested behavior.
- Do not revert unrelated user changes in a dirty worktree.
- Add tests in proportion to behavior risk.
- Use structured parsers and typed contracts instead of ad hoc string handling.
- Do not add static phrase matching as a production semantic router.
- Do not add silent parser, retrieval, or answer fallbacks that make failure look successful.
- Verify user-facing workflows in a browser when practical.

## Documentation Discipline

- Current guides explain current behavior.
- ADRs record accepted decisions.
- Engineering-evolution records preserve consequential changes and verified experiments.
- Generated wikis, raw agent plans, personal runtime paths, and temporary debugging notes do not
  belong in public documentation.
- Quality, latency, cost, and benchmark claims must retain their measurement context.

See [`CONTRIBUTING.md`](CONTRIBUTING.md) for public contribution expectations.
