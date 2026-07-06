# PaperLoom RAG Strategy Readiness

Date: 2026-06-27

Goal status: complete for the current text/table-centered paper RAG retrieval strategy.

This audit tracks whether the active goal has enough evidence to claim that PaperLoom has a final,
business-fit retrieval strategy. It is intentionally stricter than the score cheatsheet: a good
single benchmark score is not enough to declare the system done.

## Proven So Far

| Requirement | Current evidence | Status |
|---|---|---|
| Project/user context | `PROJECT_CONTEXT.md` defines PaperLoom as a research-paper RAG workbench, identifies researcher/literature-review/admin users, and maps workflows to retrieval routes. | Proven enough for current strategy work. |
| Benchmark ledger | `CHEATSHEET.md`, `harnesses.yaml`, and `runs/` provide a repeatable score ledger for Product, QASPER, and LitSearch-shaped runs. | Proven for current local eval. |
| Product paper-QA bridge | Active-runtime service-backed runs on the expanded 10-case `product-rescue-paper-qa` score 10/10 across K3/K5/K7 variants with no scope leak: `2026-06-27T063345239159915Z-service-backed-scoped-diverse-window-product-rescue-paper-qa`, `2026-06-27T063403318239747Z-service-backed-scoped-diverse-window-k5-product-rescue-paper-qa`, and `2026-06-27T063421402493123Z-service-backed-scoped-diverse-window-k7-product-rescue-paper-qa`. The slice now covers summary, concept mining, retrieval strategies, method/dataset setup, tables, limitations, conclusion, reference-context, and multi-paper disambiguation. | Proven for the current text/table product slice. |
| Table evidence contract | `eval/rag/figure-table/product-figure-table-smoke.jsonl` isolates table evidence cases, and backend reference tests preserve table text, markdown, ids, and crop availability through citation mappings. | Gate added; table claims still need a fresh passing smoke run before updating the score ledger. |
| QASPER scoped paper QA | Active-runtime service-backed reruns reproduced the K3/K5/K7 ladder on QASPER Dev 200: 91/200 (`WindowEv@3=45.5%`), 106/200 (`WindowEv@5=53.0%`), and 122/200 (`WindowEv@7=61.0%`), with `CandidateEv=78.0%` and `scopeLeakRate=0.0%` throughout. | Strong eval signal for budgeted scoped QA. |
| Negative probes | Target-term rerank, chunk-aware page rerank, and SAG fast-mode were measured and rejected as defaults. SAG fast-mode rejected means SAG remains post-retrieval expansion only. | Proven for the tested probes. |
| Literature-search direction | LitSearch Full shows `facet-paper-baseline` at `Recall@20=67.4%` and `MRR=40.1%` versus keyword-only `Recall@20=42.9%` and `MRR=12.8%`, supporting paper-level metadata/facet retrieval before chunk evidence. | Proven for the offline full-corpus floor. |
| Service-backed LitSearch Full | `current-evidence-ledger` on `litsearch-full` runs all 597 queries over 64,183 imported papers at `Recall@20=64.5%`, `Recall@5=54.1%`, `MRR=45.0%`, and `scopeLeakRate=0.0%`. It produced 597 non-empty rows. | Proven that the production service path scales to the full LitSearch corpus and is close to the offline facet-paper full-corpus recall floor while exceeding its MRR. |
| Service-backed LitSearch slice | `current-evidence-ledger` on `litsearch-service-slice-k5` runs all 597 queries over 5,060 imported candidate papers with metadata fallback at `Recall@20=58.4%`, `Recall@5=57.6%`, `MRR=47.1%`, and `scopeLeakRate=0.0%`. | Superseded as the main evidence by full-corpus service-backed LitSearch, but still useful as a faster regression slice. |
| Frontend/product-mode entry point | The frontend/product-mode entry point is browser-checked through Chromium DevTools on `http://127.0.0.1:9527/#/chat`: proxy login returned `code=200`, the chat UI rendered `快速`/`高召回`/`审计`, WebSocket reached `已连接`, and a real mouse/keyboard send produced a structured chat frame with `scope.retrievalBudgetProfile=high_recall`. `ChatWebSocketHandlerTest` also proves missing profile values default to `INTERACTIVE` and explicit `deep_audit` reaches `AnswerScope`. | Proven for UI-to-WebSocket payload plumbing and default-profile guardrail. K7 remains explicit, not default chat. |

## Residual Follow-Ups

| Gap | Why it does not block this strategy decision | Required next evidence |
|---|---|---|
| Figure-heavy product QA | The current product paper has 142 chunks, 8 table chunks, and 0 figure chunks. The selected strategy is validated for text/table evidence, scoped paper QA, literature search, and trusted page inspection; figure grounding requires a figure-bearing product paper or a multimodal benchmark. This is not a blocker for the current text/table strategy decision. The figure/table gate currently records this as table-only with figure fixture pending. | Add a committed figure-bearing PDF slice and require both figure text/caption evidence and figure crop availability before claiming figure-heavy QA. |

## Current Recommendation

Use a routed retrieval strategy:

- Literature search: `paper_search` metadata retrieval first, then scoped chunk evidence per paper.
- Scoped paper QA: keep hybrid chunk retrieval as production default; use coverage-aware page-window
  selection for eval-backed higher-recall modes.
- Known page/reference follow-up: deterministic `INSPECT_REFERENCE` and trusted-anchor
  `INSPECT_PAGE`.
- SAG: post-retrieval expansion only after a bounded candidate set exists.

The best current scoped-QA budget policy is:

| Mode | Eval harness | Use |
|---|---|---|
| Interactive | `service-backed-scoped-diverse-window` K3 | Normal scoped paper QA. |
| High recall | `service-backed-scoped-diverse-window-k5` | User asks for more complete evidence. |
| Deep audit | `service-backed-scoped-diverse-window-k7` | Audit/high-stakes review, not default chat. |

The Product and QASPER K3/K5/K7 gates were rerun on 2026-06-27 under the active backend/frontend
runtime and matched the expected score ladder with no scope leak. The expanded 10-case Product
Paper-QA gate now verifies the selected budget policy against broader product coverage. This is
enough to close the current retrieval-strategy exploration goal for text/table evidence. The new
figure/table gate keeps figure-heavy QA marked unproven until a figure-bearing fixture has passing
text/caption and crop-availability runs; data structures or UI controls alone are not enough to
promote multimodal claims.
