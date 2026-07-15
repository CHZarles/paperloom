# PaperLoom Product Requirements

Status: current product contract, verified against the live Java-to-Python harness path on
2026-07-14.

This document defines the behavior that maintained documentation and new product work should
preserve. Superseded designs belong in `docs/engineering-evolution/`, where they remain useful as
decision history without being mistaken for the current runtime.

## North Star

PaperLoom is an evidence-bounded agentic RAG workbench for reading research-paper PDFs.

The core experience is:

```text
authorized paper library
-> explicit conversation scope
-> tool-guided paper research
-> evidence-validated answer
-> reopenable reference
-> continued reading or follow-up
```

Users should be able to:

- discover papers inside the library they are permitted to access;
- ask questions about one paper or a selected group of papers;
- compare methods, experiments, findings, and limitations;
- inspect the source material behind an answer;
- continue from a previous paper, location, or citation without silently changing scope.

PaperLoom is not a generic enterprise knowledge-base assistant and not a web-scale literature search
engine.

## Product Boundary

The product answers only from research-paper PDFs in the current user's authorized library.

Confirmed non-goals:

- no all-web paper search;
- no answer that presents unsupported paper-content claims as fact;
- no hidden widening of a conversation's paper scope;
- no benchmark corpus mixed into the product paper library;
- no production behavior hardcoded around particular benchmark questions;
- no claim that independent or dormant infrastructure contributes to the current assistant path.

Recommendations are limited to papers already present and readable in the authorized product
corpus. Metadata can support inventory and filtering; claims about methods, results, or limitations
require read evidence.

## Accepted Input And Parsing

Production input is a research-paper PDF. MinerU is the current target parser.

The parser boundary must preserve enough information to build a product-owned Reading Model:

- physical pages and readable text;
- reading order and section context;
- tables, figures, charts, formulas, lists, footnotes, asides, and code when present;
- bounding boxes, source spans, parser identities, raw attributes, and structured payloads;
- parser artifacts and visual evidence assets useful for inspection and debugging.

Parser failure must remain visible. A failed or partial paper must not be presented as fully ready.
Alternative parsers are experiments until their adapters and real-PDF evaluations preserve the same
Reading Model and evidence guarantees.

## Reading Model Contract

The Reading Model is the canonical product representation of a paper. It is not the raw MinerU
response and not a search index.

The model must support:

- explicit model versions, currentness, readiness, failure state, counts, and diagnostics;
- physical pages, sections, typed Reading Elements, and navigable locations;
- readable caption and body text separated from retrieval-oriented searchable text;
- parent-child relationships for caption fragments, panels, tables, and figures;
- explicit ambiguous or unattached association state instead of invented ownership;
- parser provenance, bbox, source span, and visual-asset links;
- historical reference recovery even when retrieval policy later changes.

The complete model may be richer than the active retrieval projection. Documentation must say which
tables or fields the live harness actually loads.

## Live Research Runtime

The selected product path is:

```text
ChatHandler
-> ProductReadingConversationService
-> PythonResearchHarnessClient
-> POST /v1/research/stream
-> ResearchHarnessService
-> OpenAI Agents SDK Runner
```

### Java Responsibilities

Java must remain authoritative for:

- authentication and paper permission;
- locking the conversation's paper scope;
- quota reservation and settlement;
- cancellation and streamed product integration;
- durable history, research-memory exchange, and conversation ownership;
- product reference persistence and historical evidence reopening;
- Current Reading Model indexing, query embeddings, Qdrant retrieval, scope revalidation, and exact
  MySQL location reads.

### Python Responsibilities

Python must remain authoritative for:

- accepting only the paper IDs Java authorized and never widening that scope;
- calling the Java Corpus API through a request-bound gateway;
- the Agents SDK model-tool loop;
- paper and location disclosure state;
- Evidence ID creation only after a successful exact Java read;
- Candidate / Read / Cited / substantive-evidence coverage;
- final answer, outcome, and citation validation;
- optional per-run evaluation capture.

## Current Corpus And Retrieval

The Python product service loads only authorized paper metadata through the Java Corpus API. It does
not load all `paper_reading_elements` into each Harness process or call Docker MySQL directly.

Paper discovery uses deterministic metadata matching inside the locked scope. Reading-location
retrieval uses Java-owned query embeddings, Qdrant dense/sparse candidates, deterministic RRF,
Current Model and scope validation, and bounded MySQL hydration. Golden fixtures and offline tests
retain the in-memory BM25 adapter.

Current requirements:

- always filter Corpus API operations by Java-authorized Paper IDs;
- never treat a Candidate Preview as paper-content evidence;
- preserve deterministic ordering and bounded result limits;
- expose enough location identity for an exact later read;
- create citeable evidence only from retained source text;
- keep retrieval internals out of user-facing answer prose.

Qdrant vectors remain a derived candidate projection, not canonical evidence. Dense/sparse quality,
capacity, and any future reranker still require held-out Candidate, Read, Cited, Hard Pass, latency,
and cost evaluation before stronger product claims are made.

## Agent Tool Contract

Every research turn exposes one coherent tool protocol:

| Tool | Product role |
| --- | --- |
| `search_paper_candidates` | Discover or browse papers from metadata inside the fixed corpus |
| `find_papers_by_identity` | Resolve one specific paper from structured identity hints |
| `find_reading_locations` | Find non-citeable location candidates inside disclosed papers |
| `read_locations` | Read disclosed locations and create citeable Evidence IDs |
| `get_research_skill` | Retrieve optional research-method guidance without changing authorization |
| `submit_research_answer` | Submit the structured outcome and answer through deterministic validation |

Required authorization ladder:

```text
Java-authorized paper scope
-> disclosed paper
-> disclosed location
-> exact read
-> Evidence ID
-> accepted final submission
```

The Agent may reformulate searches, read multiple locations, combine skills, clarify, answer
partially, or abstain. It may not bypass disclosure state, cite previews, invent Evidence IDs, or
finish through arbitrary text.

`submit_research_answer` must be the only tool call in the final model step. A rejected submission
must return a structured correction signal to the same run.

## Answer And Citation Policy

Allowed outcomes are:

- `answered`;
- `needs_clarification`;
- `partial`;
- `abstained`;
- technical failure as a runtime result, not a model-selected research outcome.

Paper-content statements must be directly supported by known evidence. Corpus inventory and
metadata-filter answers can use current-turn paper cards without content citations, but metadata
cannot support claims about methods, findings, performance, or importance.

The model cites exact Evidence IDs in the structured submission. The harness validates them and
renders numeric references. The model must not construct final numeric references or a free-form
Sources section itself.

Java persists product reference mappings so a historical answer can reopen its evidence. Reopening
must recheck current paper permission.

## Conversation And Scope

- The first reading turn locks an effective paper scope for the conversation.
- Later turns inherit that scope unless the product explicitly starts a new conversation or changes
  scope through a defined product action.
- Clicked paper, location, and historical-reference anchors are structured current-turn inputs.
- Conversation memory can aid follow-up but cannot replace a current tool call for paper inventory,
  identity, location, permission, or new paper-content evidence.
- Ambiguous identity matches must produce a choice or clarification, not arbitrary authorization.
- Client-side conversation selection is not a substitute for the server-authoritative turn target.

## Product Evidence Display

The product should expose:

- cited paper title and reference number;
- section and page when known;
- exact retained source span;
- table, figure, chart, formula, bbox, screenshot, or crop availability when present;
- a stable action for reopening the owning paper evidence;
- explicit missing visual-evidence state rather than a fabricated preview.

The interface must not present internal tool names, scores, raw SQL IDs, hidden validation rules, or
provider infrastructure as paper content.

## Evaluation Contract

Product papers and evaluation corpora are separate data domains. A benchmark may exercise shared
retrieval and harness code, but it must not enter product paper scope.

Golden Cases should be evidence-first and may define:

- required and forbidden papers or evidence anchors;
- expected facts and claims;
- expected outcome and citation policy;
- answer-shape and trace obligations;
- human labels and judge-calibration data.

Every retrieval or model change should be evaluated across quality, latency, token use, retry rate,
technical failure, and behavior traces. A stronger model or more sampling is not automatically a
better product path.

## Saved Data And Model Improvement

Optional eval capture may record questions, conversation context, model transport payloads, tool
arguments and results, authorization transitions, Evidence IDs, submitted drafts, validation errors,
tokens, latency, and failures.

That data can support lexical retrieval tuning, future dense-retriever or reranker training,
tool-policy improvement, provider routing, judge calibration, and teacher-student distillation.

Training exports must use observable accepted answers and tool trajectories, not hidden
chain-of-thought. They also require privacy filtering, licensing review, deduplication, quality gates,
and split isolation that prevents paper, conversation, or question-family leakage.

## Promotion Gates

A new parser, retriever, reranker, model, or orchestration strategy enters the default product path
only when it:

1. preserves the permission and fixed-scope boundary;
2. preserves exact evidence and reference reopening;
3. improves or maintains held-out behavior metrics;
4. reports latency, cost, and technical failures;
5. passes product upload, research, follow-up, cancellation, and historical-reference regression;
6. updates maintained documentation to describe only the path that is actually live.
