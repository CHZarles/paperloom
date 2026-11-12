# Read External Paper Content Before Import

The discovery assistant is a pre-import workflow. A user asking what candidate papers say or why
those papers are relevant must not need to import them first, switch to the reading Chat, or grant a
library scope. Treating the Corpus API as discovery's content-acquisition backend creates that circular
dependency.

This decision extends the metadata-only discovery boundary of
[ADR 0016](0016-allow-arxiv-metadata-discovery-with-explicit-import.md) for the separate discovery
conversation. Ordinary reading Chat remains evidence-bounded to authorized library PDFs. Search
metadata is still navigation data, never paper-content evidence.

## Decision

Use narrow external-source adapters behind discovery's authenticated, running CHAT-turn boundary:

- arXiv metadata search retains its existing candidate-card and explicit import protocol.
- Crossref bibliographic search returns identity metadata, including DOI, without pretending it is
  full-text content or inserting non-arXiv records into arXiv candidate cards.
- External reading accepts an arXiv ID or DOI, not an arbitrary URL. It retrieves an arXiv PDF for
  standalone PDFBox text extraction, or resolves an exact DOI to a unique Europe PMC open-access
  article and reads its public full-text XML.
- External reads do not call the Corpus API, load a Reading Model, upload a PDF, invoke MinerU, write a
  library paper, or queue ingestion. No corpus disclosure state is needed for a not-yet-imported paper.
- Fixed upstream hosts, validated identifiers, bounded response sizes/timeouts, safe XML parsing, and
  no forwarding of internal credentials keep the adapter narrower than a general browsing agent.
- Results retain the actual source URL and returned text with PDF page or XML section context. They
  expose output truncation, continuation offset, unavailable content, and extraction limits. Metadata
  or a failed/empty extraction cannot be relabeled as a successful full-text read.

The assistant may explain and recommend from these returned excerpts within discovery, citing their
external URLs. It must distinguish verified content from abstract-only preliminary matching and
inference. It must not invent library paper IDs, source-quote references, or reading Chat evidence IDs.
The source material is untrusted data and cannot authorize tool actions or override instructions.

## Considered Alternatives

- Reuse the authorized library's reading tools. Rejected for this workflow: papers being discovered
  are not yet in the library. The reading APIs remain appropriate for ordinary library questions.
- Automatically import every paper needed for an answer. Rejected: reading should not mutate the
  library or consume ingestion capacity without a user decision.
- Remove the refusal prompt but keep metadata-only tools. Rejected: this changes tone without adding
  a way to substantiate explanations and content recommendations.
- Ship a general URL/PDF crawler or publisher browsing agent. Deferred: public scholarly adapters are
  a smaller slice. Arbitrary URLs, broader source coverage, authentication, OCR, and paywall handling
  require separate security, access, and extraction work.

## Consequences

Discovery can read supported public papers before import, but does not promise universal full-text
access. A Crossref DOI match is not proof of available full text. DOI-based reading currently covers
Europe PMC open-access content; other publishers/repositories may remain unavailable. PDFBox text
extraction is not OCR and cannot guarantee preservation of figures, tables, formula layout, or scan-only
pages. Output completion describes the extracted text window, not a claim of complete comprehension.

External downloading and extraction consume resources independently from ingestion. Existing turn
controls still apply; output and upstream payload bounds constrain each operation. Import and memory
writes remain separate explicit actions. External text never silently widens a reading Chat scope or
becomes a canonical product Reading Model.
