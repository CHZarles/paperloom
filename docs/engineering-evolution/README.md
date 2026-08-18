# Engineering Evolution

This directory preserves consequential engineering transitions. It is not a dump of every plan or
debugging session.

An evolution record is worth keeping when it documents at least one of the following:

- an architectural boundary that materially changed;
- a production or security failure that changed the system contract;
- an experiment with reproducible evidence, including a negative result;
- a migration whose constraints remain relevant to current maintenance;
- a verification artifact supporting an accepted decision.

## Guided Reading

- [Timeline](timeline.md)
- [Reading Retrieval Minimal System](reading-model/minimal-system.md)
- [Reading Model Persistence Closure](reading-model/persistence-closure.md)
- [Chat Routing Refactor](chat-routing-refactor.md)
- [Lexical Qdrant Product Cutover Proposal](architecture/lexical-qdrant-product-cutover-proposal-2026-07-18.md)
- [First Product ReAct Harness Design](agent-runtime/product-react-harness-design-2026-06.md)
- [Research Harness Single-Session Context Management Proposal](agent-runtime/research-harness-context-compression-proposal-2026-07-16.md)
- [Research Harness Redis Streams Queue Spec](agent-runtime/research-harness-redis-streams-queue-spec-2026-07-26.md)
- [PaperLoom Harness Runner Hardening Spec](agent-runtime/paperloom-harness-runner-hardening-spec-2026-08-18.md)
- [PaperLoom Harness Draft Finalization Hardening Spec](agent-runtime/paperloom-harness-draft-finalization-hardening-spec-2026-08-18.md)
- [Pi Agent TypeScript Runtime Migration Design (historical)](agent-runtime/pi-agent-typescript-runtime-migration-design-2026-08-17.md)
- [Redis Worker Safe Lease Recovery Spec](agent-runtime/redis-worker-safe-lease-recovery-spec-2026-08-12.md)
- [Redis Live Job False Reclaim Incident](agent-runtime/redis-live-job-false-reclaim-incident-2026-08-12.md)
- [Session Isolation Security](architecture/session-isolation-security.md)
- [Frontend Performance Improvement](frontend/frontend-performance-improvement.md)
- [Agent Harness, Eval, and Benchmark Milestone](agent-harness-eval-benchmark-milestone-2026-08-12.md)

## Verification Archive

The [`verification/`](verification/) directory contains dated audits and calibration reports that
support specific decisions. They may reference the local conditions of the original run and should
not be treated as current setup guides.

## Records By Type

- [`architecture/`](architecture/): architecture proposals, migrations, and security reviews.
- [`agent-runtime/`](agent-runtime/): Harness, Redis dispatch, retry/cancel, limits, and runtime incidents.
- [`reading-model/`](reading-model/): minimal-model decisions and persistence closure.
- [`frontend/`](frontend/): product UI design and frontend evolution.
- [`verification/`](verification/): dated audits, QA, calibration, and controlled reproductions.
- [`../performance/`](../performance/): performance-specific baselines and before/after measurements.

## Public Narrative

The project site turns selected records into readable practice articles. The source record remains
technical evidence; the article explains the problem, failed attempts, decision, measurement, and
lesson for a broader engineering audience.
