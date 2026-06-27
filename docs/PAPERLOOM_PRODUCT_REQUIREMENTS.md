# PaperLoom Product Requirements

Status: user-confirmed on 2026-06-27.

This document is an AI-visible product standard for future PaperLoom development. When old code,
UI copy, or implementation shortcuts conflict with this document, treat this document as the product
direction and record the mismatch before changing behavior.

## North Star

PaperLoom is an evidence-grounded RAG workbench for structured research-paper reading.

The core experience is:

```text
permissioned paper library -> trustworthy retrieval -> evidence-grounded answer -> clickable trace -> continued reading or follow-up
```

Users should be able to place a set of papers into PaperLoom and use natural language to:

- find relevant papers in the papers they are allowed to access
- read one paper or several papers
- compare methods, experiments, limitations, and conclusions
- click citations and inspect the supporting evidence
- continue from a previous citation, paper, or page without the system guessing a new source

PaperLoom is not a generic knowledge-base assistant and not a web-scale literature search engine.

## Product Boundary

PaperLoom only answers from the current user's permissioned paper library.

Confirmed non-goals:

- no all-web paper search
- no generic enterprise knowledge-base framing
- no unsupported factual answer that is not grounded in paper evidence
- no production route hardcoding based on fixed phrases
- no default page-location guessing before benchmarks justify it

The product may recommend papers, but recommendations are limited to papers already uploaded,
imported, and accessible to the current user.

## Accepted Literature Data

Production input is research paper PDF.

A production PDF should pass through parsing or OCR, structured extraction, chunking, indexing, and
evidence-asset generation before the UI treats it as fully searchable and fully traceable.

Structured literature data is allowed only for these cases:

- benchmark or eval imports such as QASPER and LitSearch
- administrator-controlled batch import of already structured paper data, such as title, abstract,
  authors, full text, venue, year, DOI, arXiv id, or external corpus id

Structured imports must keep source provenance. They must not pretend to have PDF page screenshots,
bounding boxes, table crops, or figure crops when those assets were not generated from a real PDF.

Practical UI rule:

- PDF-derived paper: may expose PDF preview, page evidence, bbox, table crop, figure crop, and parser
  artifacts when present.
- Structured or eval-imported paper: may expose metadata and text evidence, but must clearly mark
  missing PDF/page/visual assets.

## Parser And OCR Policy

Production default parser is MinerU.

OpenDataLoader is an explicit fallback for local development or MinerU-unavailable situations. It
must not be silently presented as equivalent to the default production parser quality.

A PDF is "fully readable" only when the pipeline has produced, as far as the parser can support:

- text chunks
- page numbers
- section or title context
- table, figure, chart, and formula records where present
- PDF page preview or page screenshot support
- anchor text, bbox, source kind, and provenance fields
- parser artifacts useful for debugging

Parser or OCR failure must be visible to the user. The UI should not present a failed or half-parsed
paper as fully searchable.

Required user-visible states include:

- uploading
- parsing
- parse failed
- parsed but not indexed
- indexing
- searchable
- partially available, for example structured import or missing visual assets

## Retrieval Strategy

PaperLoom has three primary retrieval paths.

### Paper Discovery

Use this path for questions such as:

- "What papers are about agent systems?"
- "Recommend RAG papers."
- "What papers do we have on hallucination detection?"

Policy:

- retrieve paper candidates first from paper-level metadata
- use title, abstract, authors, venue, year, DOI, arXiv id, original filename, and combined search text
- after candidate papers are found, attach representative evidence snippets
- optimize for finding the right papers before reading every chunk

This path is measured mainly by LitSearch-style benchmarks.

### Paper Reading And QA

Use this path for questions about the content of one paper or a selected set of papers.

Policy:

- use scoped chunk-level hybrid retrieval
- combine semantic vector search and keyword search
- respect explicit source scope
- avoid cross-paper contamination when a paper scope is known
- use page-window inspection only after trusted anchors or validated candidate selection

This path is measured mainly by Product paper QA, QASPER-style structured QA, and real PDF product
smoke tests.

### Deterministic Follow-Up

Use this path for:

- "Explain reference [2]."
- "Open page 5."
- "Tell me more about the second recommended paper."
- "What does this table mean?"

Policy:

- use persisted reference mappings, known paper id, known page number, or known chunk/table/figure id
- do not infer a different paper or page when no trusted anchor exists
- ask for clarification when the target cannot be resolved

## Retrieval Constraints

All retrieval must pass permission filtering.

Routing must be semantic and state-aware. Debug-only hardcoded probes are acceptable during diagnosis,
but production routing must not depend on a fixed list of phrases such as "有什么 agent 相关论文".

Query normalization and planner heuristics are allowed as implementation details, but they must be:

- documented as replaceable strategy logic
- covered by regression cases
- constrained by benchmarks

PageIndex, LOCATE_PAGES, and similar page-location methods must remain eval-gated until they improve
the relevant benchmarks without causing product regressions.

Retrieval budgets should be explicit:

- fast or interactive: lower latency, smaller evidence set
- high recall: larger candidate/window budget
- audit or deep recall: slower, broader evidence inspection for high-stakes review

## Answer Policy

PaperLoom answers must be evidence-grounded.

Rules:

- paper facts, methods, experiments, results, limitations, and comparisons need citation markers
- every rendered citation marker must map to persisted reference evidence
- citation mappings must be recoverable from MySQL history, not only Redis generation state
- if evidence is insufficient, say so directly
- do not fill gaps with generic knowledge or plausible claims
- recommendations must include paper title, reason, citation, and the limitation that results come
  only from the user's accessible paper library
- comparative claims require evidence for the compared sides

Smalltalk, system help, and UI operation guidance do not need paper citations.

## Frontend Evidence Display

Citation markers are not decorative. They are the user's path back to evidence.

When a user clicks a citation, the UI should display the evidence type accurately:

- text chunk: paper title, page, section, original text, and PDF/page entry when available
- table evidence: table text and table crop when available
- figure or chart evidence: caption, related text, and figure crop when available
- formula evidence: LaTeX, context, and page when available
- structured or eval-import evidence: text evidence and metadata, with no fake PDF/page asset
- missing visual asset: explicit "text evidence only" or equivalent state

Reference follow-up must work from persisted referenceMappings after chat history is reloaded.

## Benchmark Policy

Benchmarks are layered. One layer must not be used to claim another layer's capability.

### LitSearch

Use LitSearch to measure paper discovery and literature-search retrieval.

LitSearch can validate paper-level retrieval over title, abstract, metadata, and structured text. It
does not validate PDF OCR, parser quality, page screenshots, table crops, or figure crops.

Do not report a partial sample as LitSearch Full.

### QASPER

Use QASPER to measure scoped paper QA and evidence selection on structured paper text.

QASPER does not validate OCR/parser behavior or real PDF page evidence. It should not be routed
through OCR for normal retrieval evaluation.

### Product RAG Smoke

Use product smoke tests for real product behavior:

- route selection
- scoped retrieval
- answer generation
- citation mapping
- reference follow-up
- UI evidence opening when applicable

Product smoke must pass for every RAG route, planner, retrieval, citation, referenceMappings, or
evidence-panel change.

### Real PDF Parser Smoke

PaperLoom needs a separate real PDF parser benchmark for:

- parse success rate
- page/chunk coverage
- table/figure/formula extraction coverage
- visual asset generation
- citation click-through to page/table/figure evidence

This benchmark is required before claiming mature PDF OCR, page evidence, or visual evidence support.

### Figure And Table QA

Figure-heavy and table-heavy QA require their own benchmark before the product claims multimodal
paper RAG maturity.

## Hard Gates

These gates are product correctness requirements:

- Product smoke pass rate should be 100% for required smoke cases.
- Permission and scope leak rate must be 0.
- Rendered citation mappings must be recoverable and clickable.
- Structured benchmark results must not be used as proof of OCR/parser/page-evidence quality.
- A retrieval strategy change must not silently regress the current accepted benchmark baselines.

## Current Accepted Benchmark Meaning

As of the 2026-06-27 scan:

- LitSearch Full validates the paper-discovery path over 597 queries and 64,183 imported papers.
- QASPER Dev 200 validates structured scoped QA and evidence selection, not PDF parsing.
- Product smoke validates selected end-to-end product scenarios.
- Real PDF parser and figure-heavy QA are not yet sufficiently benchmarked as product claims.
