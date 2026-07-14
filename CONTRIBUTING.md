# Contributing to PaperLoom

PaperLoom is an active research-engineering project. Contributions are welcome when they preserve
the central contract: paper answers must remain bounded by authorized, inspectable evidence.

## Before Starting

1. Read the [documentation index](docs/README.md) and relevant ADRs.
2. Search existing issues and open a focused issue for non-trivial changes.
3. Keep product papers and evaluation corpora in separate data domains.
4. Do not introduce silent semantic fallbacks that turn an unresolved task into paper QA.

## Local Checks

Backend:

```bash
mvn test
```

Frontend:

```bash
cd frontend
pnpm typecheck
pnpm lint
```

Project site:

```bash
cd site
npm ci
npm run docs:build
```

Run narrower checks while iterating, then run the checks relevant to the full changed surface before
opening a pull request.

## Pull Requests

- Keep each pull request focused on one coherent behavior or documentation outcome.
- Explain the problem, design choice, user-visible effect, and verification performed.
- Add or update tests when changing shared behavior or public contracts.
- Record meaningful architectural decisions as ADRs; do not commit raw agent planning transcripts as
  public documentation.
- Never commit credentials, private corpora, production exports, or personal runtime paths.

By contributing, you agree that your contribution is licensed under the repository's Apache-2.0
license.
