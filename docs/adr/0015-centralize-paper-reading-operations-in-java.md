# Centralize Paper Reading Operations In Java

PaperLoom will use one concrete `PaperReadingService` as the data implementation for paper search,
identity resolution, structure navigation, content search, and durable exact reads. Every query
includes adapter-built user and locked-Scope context. The service always intersects that Scope with
the database ACL and rechecks the Current Reading Model.

Qdrant remains a rebuildable candidate index. Exact content and canonical Source Spans come from the
MySQL Reading Model. `HarnessReadingService` is the internal Harness adapter for exact reads: it calls
`PaperReadingService.readPaperContent` and materializes `PaperSourceQuote` rows in one transaction.
Resolver-level missing locations retain batch partial success, while Source Quote failures roll back
the whole exact-read request.

Python retains the model-visible Tool schemas, Paper and Location disclosure, and the current-run
Evidence Ledger. It forwards ordinary arguments to Java and maps Java's stable reading error codes;
it does not cache or echo canonical content, hashes, or Source Spans.

**Considered Options**

- Keep `CorpusRetrievalService` and add a forwarding service. Rejected because it preserves two
  apparent ownership boundaries and creates a pass-through abstraction.
- Share Python `ReadingCorpusTools` with future query adapters. Rejected because disclosure and the
  Evidence Ledger are Harness protocol state, not paper-reading data functions.
- Create Source Quotes inside `PaperReadingService`. Rejected because Source Quotes belong to the
  Harness evidence lifecycle; query-only adapters must not create citation state.
- Trust the locked Scope as authorization. Rejected because Scope narrows a request but cannot prove
  current paper access.

**Consequences**

- The five reading operations have one Java implementation and can be tested without Python.
- Harness exact reads remain citation-producing and transactionally reopenable.
- Query-only adapters can call `PaperReadingService` without importing Harness state or creating
  Source Quotes.
- Java owns ordinary validation, ACL, Current Model, and retrieval availability errors; Python owns
  only Harness disclosure errors.
