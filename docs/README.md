# PaperLoom Documentation

This directory contains the maintained public documentation for PaperLoom. It is intentionally
smaller than the project's historical working notes: current guides explain how the system works;
engineering-evolution records explain why it changed.

## Start Here

- [Quick Start](getting-started/quick-start.md): run the dependencies, harness, backend, and frontend.
- [Architecture Overview](architecture/overview.md): understand service boundaries and the main data flow.
- [Reading Model and Agent Tools](architecture/reading-model-and-agent-tools.md): understand the persisted paper model, live MySQL projection, BM25 retrieval, and tool authorization ladder.
- [Evidence and Citations](architecture/evidence-and-citations.md): follow evidence from parser output to a reopened reference.
- [Agent Harness, Eval, and Benchmark Milestone](architecture/agent-harness-eval-benchmark-milestone-2026-08-12.md): current formal architecture, evaluation layers, frozen benchmark, and implementation references.
- [Passage Retrieval Proposal](architecture/passage-retrieval-proposal-2026-08-01.md): proposed text-evidence unit, provenance, neighbor reading, indexing, evaluation, and rollout contract.
- [Development Guide](guides/development.md): common commands, tests, and repository conventions.
- [Deployment Guide](guides/deployment.md): production-oriented configuration and process layout.
- [Wuyun + Cloudflare Tunnel 部署实录](guides/wuyun-cloudflare-tunnel-deployment.md)：实际私有源站与
  公网域名接入步骤。
- [运维指南](guides/operations.md)：上线后的检查、发布、备份、排障与密钥轮换。
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
