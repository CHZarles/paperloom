# PaperLoom Documentation

This directory contains the maintained public documentation for PaperLoom. It is intentionally
smaller than the project's historical working notes: current guides explain how the system works;
engineering-evolution records explain why it changed.

## Start Here

- [Quick Start](getting-started/quick-start.md): run the dependencies, harness, backend, and frontend.
- [Architecture Overview](architecture/overview.md): understand service boundaries and the main data flow.
- [Reading Model and Agent Tools](architecture/reading-model-and-agent-tools.md): understand the persisted paper model, live MySQL projection, BM25 retrieval, and tool authorization ladder.
- [Evidence and Citations](architecture/evidence-and-citations.md): follow evidence from parser output to a reopened reference.
- [Development Guide](guides/development.md): common commands, tests, and repository conventions.
- [Deployment Guide](guides/deployment.md): production-oriented configuration and process layout.
- [Configuration Reference](reference/configuration.md): environment-variable groups and ownership.

## Product and Research

- [Evaluation System](evaluation/README.md)
- [Retrieval Benchmark Workflow](evaluation/retrieval-method-benchmark.md)
- [Product Requirements](reference/product-requirements.md)
- [Domain Language](reference/domain-language.md)

The large reference documents are detailed contracts. They are not the fastest onboarding path;
begin with the architecture and development guides.

## Decisions and Evolution

- [Architecture Decision Records](adr/)
- [Engineering Evolution Index](engineering-evolution/README.md)
- [Engineering Evolution Timeline](engineering-evolution/timeline.md)
- [June 2026 Implementation Alignment](engineering-evolution/architecture/implementation-alignment-2026-06.md)
- [First Product ReAct Harness Design](engineering-evolution/agent-runtime/product-react-harness-design-2026-06.md)
- [Public Practice Journal](https://chzarles.github.io/paperloom/practice/)

Raw implementation plans, generated repository wikis, temporary debugging notes, and superseded
onboarding documents are deliberately excluded from the public documentation tree.

## Documentation Policy

A maintained document must satisfy at least one of these purposes:

1. Explain current behavior or an operational procedure.
2. Define a current product, architecture, or evaluation contract.
3. Preserve a consequential engineering decision with evidence and outcome.

Documents that no longer satisfy one of those purposes should be corrected, condensed into an
evolution record, or deleted.
