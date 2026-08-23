# Expose Stateless Read-Only MCP Tools

PaperLoom will expose five read-only MCP Tools through the standalone `paperloom_mcp` Python package
and an authenticated Java public reading facade. The MCP Tools own their schemas and map one-to-one to
`/api/v1/reading/**` endpoints; they do not reuse Harness Tool schemas or call the internal Corpus
routes.

Every MCP HTTP request carries the current PaperLoom user JWT. Python checks that a Bearer value is
present, forwards it unchanged, and never decodes it. Java Spring Security authenticates the user,
derives the database identity, applies Paper ACL and Current Reading Model checks through
`PaperReadingService`, and returns bounded public DTOs. The adapter keeps no Paper disclosure,
Location disclosure, Evidence Ledger, Source Quote, conversation, or Agent state.

MCP V1 advertises Tools only: `search_papers`, `get_paper`, `get_paper_structure`,
`search_paper_content`, and `read_paper_content`. Exact reads return canonical content but do not
materialize `PaperSourceQuote` rows.

**Considered Options**

- Reuse `ReadingCorpusTools`. Rejected because its disclosure ladder and Evidence Ledger are Harness
  protocol state and would make independent MCP queries order-dependent.
- Call `/internal/v1/corpus/**` with the Harness service token. Rejected because that authenticates an
  adapter, not the end user, and exposes internal response contracts.
- Decode or validate PaperLoom JWTs in Python. Rejected because it duplicates Java authentication,
  role, refresh, and revocation policy.
- Return internal Java results and redact them in Python. Rejected because the authenticated Java API
  is directly callable and must itself be the data-minimization boundary.

**Consequences**

- Calls from different users or clients share no application-level MCP state and may run in parallel.
- Missing, invalid, or revoked JWTs fail through the current Java authentication boundary.
- MCP responses contain no Source Quote refs, Source Spans, retrieval scores, parser metadata,
  storage paths, or Qdrant identity.
- The MCP server defaults to loopback, limits request/result size, and requires HTTPS before remote
  exposure.
