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
- [June 2026 Implementation Alignment](architecture/implementation-alignment-2026-06.md)
- [Lexical Qdrant Product Cutover Proposal](architecture/lexical-qdrant-product-cutover-proposal-2026-07-18.md)
- [First Product ReAct Harness Design](agent-runtime/product-react-harness-design-2026-06.md)
- [Session Isolation Security](architecture/session-isolation-security.md)
- [Frontend Performance Improvement](frontend/frontend-performance-improvement.md)

## Verification Archive

The [`verification/`](verification/) directory contains dated audits and calibration reports that
support specific decisions. They may reference the local conditions of the original run and should
not be treated as current setup guides.

## Public Narrative

The project site turns selected records into readable practice articles. The source record remains
technical evidence; the article explains the problem, failed attempts, decision, measurement, and
lesson for a broader engineering audience.
