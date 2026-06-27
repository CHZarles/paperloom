# PaperLoom RAG Eval Cheatsheet

Last updated: 2026-06-27T06:34:21.402493123Z

| Harness | Benchmark | Tier | Cases | Primary | Quality | Run |
|---|---|---|---:|---:|---|---|
| current-evidence-ledger | Product Rescue Smoke | product | 6 | Pass 100.0% | Ev 100.0%, Cite 100.0%, Bad 0.0% | `runs/2026-06-23T212834Z-current-evidence-ledger-product-rescue-smoke-litsearch-check/` |
| current-evidence-ledger | QASPER Dev 200 | professional | 200 | pending Pass | - | - |
| current-evidence-ledger | LitSearch Full | professional | 597 | Recall@20 64.5% | Ev 100.0%, Cite 100.0%, Bad 0.0% | `runs/20260627T043549Z-current-evidence-ledger-litsearch-full/` |
| current-evidence-ledger | LitSearch Service Slice K5 | professional | 597 | Recall@20 58.4% | Ev 100.0%, Cite 100.0%, Bad 0.0% | `runs/20260624T195908Z-current-evidence-ledger-litsearch-service-slice-k5-metadata-fallback/` |
| keyword-only-baseline | LitSearch Full | professional | 597 | Recall@20 42.9% | Ev 100.0%, Cite 100.0%, Bad 0.0% | `runs/2026-06-24T175200Z-keyword-only-baseline-litsearch-full/` |
| facet-paper-baseline | LitSearch Full | professional | 597 | Recall@20 67.4% | Ev 100.0%, Cite 100.0%, Bad 0.0% | `runs/2026-06-24T174500Z-facet-paper-baseline-litsearch-full/` |
| service-backed-page-window | Product Paper-QA Slice | product | 3 | Pass 100.0% | Ev 100.0%, Cite 100.0%, Bad 0.0% | `runs/2026-06-24T124226Z-service-backed-page-window-product-rescue-paper-qa/` |
| service-backed-page-window | QASPER Dev 200 | professional | 200 | Pass 42.0% | Ev 42.0%, Cite 100.0%, Bad 0.0% | `runs/2026-06-24T125506Z-service-backed-page-window-qasper-dev-200/` |
| service-backed-scoped-page-window | Product Paper-QA Slice | product | 3 | Pass 100.0% | Ev 100.0%, Cite 100.0%, Bad 0.0% | `runs/2026-06-24T162000Z-service-backed-scoped-page-window-product-rescue-paper-qa/` |
| service-backed-scoped-page-window | QASPER Dev 200 | professional | 200 | Pass 42.5% | Ev 42.5%, Cite 100.0%, Bad 0.0% | `runs/2026-06-24T162100Z-service-backed-scoped-page-window-qasper-dev-200/` |
| service-backed-scoped-diverse-window | Product Paper-QA Slice | product | 10 | Pass 100.0% | Ev 100.0%, Cite 100.0%, Bad 0.0% | `runs/2026-06-27T063345239159915Z-service-backed-scoped-diverse-window-product-rescue-paper-qa/` |
| service-backed-scoped-diverse-window | QASPER Dev 200 | professional | 200 | Pass 45.5% | Ev 45.5%, Cite 100.0%, Bad 0.0% | `runs/2026-06-27T060820675296649Z-service-backed-scoped-diverse-window-qasper-dev-200/` |
| service-backed-scoped-diverse-window-k5 | Product Paper-QA Slice | product | 10 | Pass 100.0% | Ev 100.0%, Cite 100.0%, Bad 0.0% | `runs/2026-06-27T063403318239747Z-service-backed-scoped-diverse-window-k5-product-rescue-paper-qa/` |
| service-backed-scoped-diverse-window-k5 | QASPER Dev 200 | professional | 200 | Pass 53.0% | Ev 53.0%, Cite 100.0%, Bad 0.0% | `runs/2026-06-27T061011343477880Z-service-backed-scoped-diverse-window-k5-qasper-dev-200/` |
| service-backed-scoped-diverse-window-k7 | Product Paper-QA Slice | product | 10 | Pass 100.0% | Ev 100.0%, Cite 100.0%, Bad 0.0% | `runs/2026-06-27T063421402493123Z-service-backed-scoped-diverse-window-k7-product-rescue-paper-qa/` |
| service-backed-scoped-diverse-window-k7 | QASPER Dev 200 | professional | 200 | Pass 61.0% | Ev 61.0%, Cite 100.0%, Bad 0.0% | `runs/2026-06-27T061247010856382Z-service-backed-scoped-diverse-window-k7-qasper-dev-200/` |
| semantic-only-baseline | Product Rescue Smoke | product | 6 | pending Pass | - | - |
| semantic-only-baseline | QASPER Dev 200 | professional | 200 | pending Pass | - | - |
