# Allow arXiv Metadata Discovery With Explicit Single-Paper Import

The discovery assistant's metadata-only content boundary is extended by
[ADR 0017](0017-read-external-paper-content-before-import.md), which adds separate pre-import public
full-text reading. The import pipeline and metadata-as-navigation rules below remain unchanged.

PaperLoom adds one bounded discovery surface: a user can search arXiv metadata from the web
application and import one paper at a time into their own library. This narrows and replaces the
previous blanket "no all-web paper search" stance. Discovery stays metadata-only. A search result is
navigation data, never answer evidence, and never enters a conversation scope by itself.

The imported paper enters the existing PDF pipeline unchanged: the server downloads the PDF, computes
the same content MD5 used for uploads, writes `uploads/merged/{paperId}`, and queues the existing Kafka
parse and index task. Paper identity therefore stays content-addressed. The arXiv identifier is stored
as metadata only; it does not become the primary key and it does not gate deduplication.

Imports require an explicit per-paper user action with a per-user hourly budget. A paper that is
already searchable reports `READY`; a paper already queued or parsing reports `PROCESSING`; a new one
queues parsing and reports `IMPORTED`. Upstream failure, rate limiting, and oversized PDFs surface as
errors instead of empty result sets.

**Considered Options**

- Keep the blanket ban and require manual PDF upload. Rejected because the friction is in finding a
  paper, not in parsing it, and the existing pipeline already accepts any PDF.
- Make the arXiv identifier the paper identity. Rejected because every Reading Model, Qdrant payload,
  conversation scope, and reference mapping is already keyed by the content MD5; a second identity
  would create two paper records for one document and a silent duplicate path.
- Ingest search results automatically or in bulk. Rejected because parse cost is the scarce shared
  resource, the corpus would grow without a user decision, and the library's permission model is
  per-user ownership.
- Ship a web-enabled exploration agent in the same change. Deferred. A browsing agent answers from
  open-web text, which the evidence contract cannot validate, so it needs its own tool surface,
  refusal rules, and cost model before it exists.
- Proxy discovery through the existing product paper-search tools. Rejected: `search_paper_candidates`
  is scoped to authorized library papers, and widening it would break the scope invariant that the
  research path depends on.

**Consequences**

- Product boundary text changes from "no web paper search" to "metadata-only discovery plus explicit
  single-paper import"; answers remain grounded only in authorized library papers.
- The library can grow from an external source, so parse capacity and per-user import budgets become
  product concerns rather than deployment details.
- Discovery is limited to the `USER` and `ADMIN` roles, matching the upload boundary. Guests can ask
  questions but cannot grow a library or consume parse capacity through discovery.
- The hourly import budget is a cost guard, not an authorization boundary. If Redis is unavailable the
  check is skipped and the import proceeds; the skip is logged.
- Re-importing an arXiv id that is already `READY` or `PROCESSING` returns that paper without
  downloading again. A paper that never became searchable is retried; if its PDF has changed, that
  retry creates a second record because identity is the PDF hash, not the arXiv id.
- The arXiv client is the first outbound third-party dependency in the request path; its failures are
  user-visible and it must not degrade into an empty result list.
- The web client may add an imported paper to one of the user's paper sets. It applies that
  membership in a second call after the paper exists in the library; when the call fails the paper
  stays imported and the failure is reported, so the user is never told a set membership happened
  when it did not. Non-web callers add the paper to a set explicitly.
- arXiv exposes no popularity or citation signal. Discovery therefore stays a search surface: an
  importance ranking would need an external citation index and was removed rather than faked.
