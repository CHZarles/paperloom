# PaperLoom Documentation

This is the maintained technical-documentation index. Start with the smallest document that answers
your question; dated proposals, incidents, and measurements are evidence archives rather than setup
guides.

## Start Here

| Goal | Read |
| --- | --- |
| Run the system locally | [Quick Start](getting-started/quick-start.md) |
| Understand the current system | [Architecture Overview](architecture/overview.md) |
| Follow a specific business request | [Runtime Diagram Catalog](architecture/runtime-diagrams.md) |
| Develop or test the repository | [Development Guide](guides/development.md) |
| Configure or deploy it | [Configuration](reference/configuration.md) and [Deployment Guide](guides/deployment.md) |
| Understand why the design changed | [Engineering Evolution](engineering-evolution/README.md) |
| Prepare to explain the project | [Interview Materials](../interview/README.md) |

## Current System

These documents describe behavior that maintainers should keep synchronized with code.

- [Architecture Overview](architecture/overview.md): service boundaries, storage roles, and the live request path.
- [Runtime Diagram Catalog](architecture/runtime-diagrams.md): sequence diagrams grouped by business workflow.
- [Reading Model and Agent Tools](architecture/reading-model-and-agent-tools.md): durable paper model, retrieval, and tool authorization.
- [Evidence and Citations](architecture/evidence-and-citations.md): parser output through exact reads to reopened references.
- [Governed Research Run](architecture/governed-research-run-spec.md): implemented limits, failure classes, and promotion gate.
- [Naming Contract](architecture/naming.md): Folio and PaperLoom names used across product and code.
- [Domain Language](reference/domain-language.md): canonical product and architecture vocabulary.
- [Product Requirements](reference/product-requirements.md): maintained product contract.

## Operate The System

- [Quick Start](getting-started/quick-start.md)
- [Development Guide](guides/development.md)
- [Deployment Guide](guides/deployment.md)
- [Operations Guide](guides/operations.md)
- [Wuyun + Cloudflare Tunnel Deployment Record](guides/wuyun-cloudflare-tunnel-deployment.md)
- [Configuration Reference](reference/configuration.md)

Deployment assets stay beside these guides: [`docker-compose.yaml`](docker-compose.yaml),
[`nginx.conf`](nginx.conf), and [`launch/`](launch/). Database DDL and migrations live in
[`databases/`](databases/); machine-readable service contracts live in [`contracts/`](contracts/).

## Decisions, Specs, And History

| Type | Location | Meaning |
| --- | --- | --- |
| Accepted architecture decisions | [`adr/`](adr/) | Stable decision, rationale, and consequences |
| Current architecture and contracts | [`architecture/`](architecture/) | Maintained runtime or product behavior |
| Proposals and migration records | [`engineering-evolution/`](engineering-evolution/) | Why the system changed or may change |
| Incidents and verification | [`engineering-evolution/`](engineering-evolution/) | Evidence, diagnosis, correction, and outcome |
| Performance experiments | [`performance/`](performance/) | Reproduction steps and measured before/after data |
| Evaluation design | [`evaluation/`](evaluation/) | Scoring, benchmark, and Golden Data contracts |
| Historical implementation plans | [`superpowers/`](superpowers/) | Point-in-time work plans, not current truth |

Start with the [Engineering Evolution Index](engineering-evolution/README.md) or its
[Timeline](engineering-evolution/timeline.md). Do not use a dated proposal as current behavior unless
its status says it was implemented and the current architecture agrees.

## Specialized Collections

- [`harness_py/`](../harness_py/README.md): Python Harness operation and Agents SDK onboarding.
- [`research/golden-data/`](../research/golden-data/README.md): Golden cases, schemas, adjudication, and frozen runs.
- [`research/`](../research/README.md): research designs, benchmark data, exploratory notes, and Golden Data.
- [`interview/`](../interview/README.md): learning guides, project evidence map, and defensible project stories.
- [`site/`](../site/index.md): public project-site source and selected engineering narratives.
- [`diagrams/`](../diagrams/README.md): legacy review-diagram sources; current runtime sources live in `site/diagrams/`.

## Repository-Level Documents

- [`README.md`](../README.md) and [`README.zh-CN.md`](../README.zh-CN.md): concise public project entry points.
- [`CONTRIBUTING.md`](../CONTRIBUTING.md): contribution workflow and required checks.
- [`SECURITY.md`](../SECURITY.md): vulnerability reporting and operational security.
- [`HANDOFF.md`](../HANDOFF.md): temporary maintainer handoff; update or replace it as work changes.
- [`AGENTS.md`](../AGENTS.md) and [`CLAUDE.md`](../CLAUDE.md): instructions for coding agents, not product documentation.
- [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md): third-party license notices.

## Documentation Rules

1. Put current behavior in `architecture/`, `guides/`, or `reference/` and keep it synchronized with code.
2. Put consequential changes, incidents, and measurements in `engineering-evolution/` or `performance/`.
3. Put normative decisions in `adr/`; include status in dated specs and proposals.
4. Keep generated data and raw experiment output with its owning research or performance collection.
5. Link from this index instead of expanding the root README into a document dump.
