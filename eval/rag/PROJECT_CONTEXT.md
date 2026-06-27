# PaperLoom RAG Project Context

Date: 2026-06-27

PaperLoom is an evidence-grounded research-paper RAG workbench. It is not a generic enterprise knowledge-base assistant.
The system should help users read, search, and verify papers with
recoverable page evidence, chunk provenance, and durable `referenceMappings`.

## Users

| User | Primary job | What they trust |
|---|---|---|
| Researcher | Ask scoped questions about one paper, compare methods/results, inspect limitations, and follow cited evidence. | Answers cite the right `paperId`, page evidence, section/chunk provenance, and source snippets. |
| Graduate student or literature-review writer | Find relevant papers for a research question, then decide which papers deserve close reading. | Literature-search retrieval ranks the right papers before answer style is considered. |
| Technical/product reviewer | Check whether PaperLoom chat is reliable enough for uploaded papers, reference follow-up, and source preview. | Product regressions catch false negatives, bad evidence, scope leaks, and missing citation mappings. |
| Admin or team librarian | Keep uploaded/eval papers scoped by organization, user access, and metadata. | Retrieval respects `orgTag`, visibility, source dataset, and eval split boundaries. |

## Core Workflows

| Workflow | User question shape | Retrieval route | Evidence requirement |
|---|---|---|---|
| Scoped paper QA | "In this paper, what happens under high noise?" | Hybrid chunk retrieval plus deterministic page-window inspection. | Cite same-paper chunks/pages and preserve `referenceMappings`. |
| Literature search | "Find papers about post-hoc hallucination detection." | `paper_search` first-stage metadata retrieval, then scoped chunk evidence per selected paper. | Retrieve correct paper ids first; cite one representative snippet per paper. |
| Known page/reference follow-up | "Explain reference [12]" or "look at page 7." | Deterministic `INSPECT_REFERENCE` or `INSPECT_PAGE` after a trusted anchor exists. | No locator guessing; inspect the requested paper/page/reference directly. |
| Audit/deep review | "Be thorough; do not miss evidence." | Scoped-paper diverse page windows with larger K budget. | K3 is interactive, K5 is high-recall, K7 is deep-recall/audit. |

## Benchmark Fit

| Benchmark | Why it belongs | What it does not prove |
|---|---|---|
| Product Rescue Smoke | Protects PaperLoom-specific chat behavior: routing, false negatives, bad evidence, source scope, citation mappings, and history rendering. | It is not a broad academic benchmark. |
| Product Paper-QA Slice | Keeps page-window harnesses focused on scoped paper evidence recovery without route-control cases mixed in; the current slice covers 10 text/table cases including methods, tables, limitations, reference-context, and multi-paper disambiguation. | It does not prove figure-heavy or multimodal QA because the current product paper has no figure chunks. |
| QASPER | Professional full-text research-paper QA benchmark for answer/evidence behavior on structured paper text. | It does not measure literature-search corpus recall. |
| LitSearch | Professional literature-review retrieval benchmark: find the right papers across a corpus before generation. | Partial samples are sanity floors, not `LitSearch Full`. |
| SAG probes | Tests whether structured entity/event expansion helps paper evidence retrieval. | Standalone SAG fast-mode is rejected for now; use SAG as post-retrieval expansion only. |

Do not run QASPER or LitSearch through OCR for normal RAG eval. They are structured-text
benchmarks. OCR belongs to a separate PDF-ingestion benchmark where parser quality is the variable.

## Current Strategy

PaperLoom should use routed retrieval, not one universal retriever:

- Literature search: `paper_search` over title, abstract, authors, venue/year, external ids, and
  original filename first; then retrieve scoped chunks for citeable evidence.
- Scoped paper QA: keep production hybrid retrieval, add page-window inspection only after paper
  scope is trusted, and measure coverage-aware window selection in eval.
- Known page/reference follow-up: use deterministic `INSPECT_PAGE` and `INSPECT_REFERENCE`; avoid
  locator-style guessing after the user gives a concrete anchor.
- SAG: keep as post-retrieval expansion only, after hybrid/page candidates bound the search space.

## Promotion Rules

- A method must beat the product smoke and the relevant professional benchmark before it becomes a
  default route.
- QASPER page-window improvements must report `WindowEv@K`, `CandidateEv`, `scopeLeakRate`, and
  context cost.
- K7 is a deep audit budget, not the default chat budget; product behavior needs an explicit budget
  selector before larger page-window K values reach users.
- LitSearch Full must use all 597 queries and all 64,183 corpus papers.
- Sample, mini, and partial-corpus runs must use sample-specific dataset ids.
- Metadata/storage changes should stay justified by route needs: `paper_search` for paper-level
  literature search, page/chunk metadata for scoped evidence, and eval markers for benchmark rows.
