# Standalone Read-Only Reading MCP Spec

Date: 2026-08-23

Status: Implemented and verified on 2026-08-24.

This document governs the standalone Reading MCP only. It supersedes every MCP-specific requirement
in `evidence-field-extraction-mcp-spec-2026-08-22.md` and
`evidence-field-extraction-mcp-proposal-2026-08-22.md`. It does not supersede, authorize, or specify
Field Extraction.

## 1. Decision

PaperLoom will expose five stateless, read-only MCP Tools through a standalone Python package named
`paperloom_mcp` and an authenticated Java public query facade.

```text
MCP client
  -> Streamable HTTP /mcp + opaque PaperLoom Bearer JWT
  -> standalone paperloom_mcp protocol adapter
  -> /api/v1/reading/** with the same Bearer JWT
  -> Spring Security derives the User
  -> PaperReadingService applies ACL + Current Model + retrieval/read logic
  -> bounded public DTO
```

The MCP adapter MUST NOT import or depend on `harness_py`, `ReadingCorpusTools`, Paper/Location
disclosure state, Evidence Ledger, Source Quotes, conversation state, Agent Runtime, or submission
contracts.

## 2. Scope

### 2.1 In Scope

- five query-only MCP Tools;
- five authenticated Java public query endpoints;
- independent MCP schemas and public Java DTOs;
- JWT forwarding without Python JWT decoding;
- request/result size limits and safe error mapping;
- USER/ADMIN authorization, ACL isolation, Current Reading Model validation, quota attribution, and
  bounded audit;
- standalone dependency lock, CLI, documentation, protocol tests, and live loopback smoke test.

### 2.2 Out Of Scope

- Field Extraction, answer generation, or Harness Runs;
- MCP Prompts, Resources, sampling, elicitation, or write Tools;
- `PaperSourceQuote` creation, Evidence Ledger mutation, or conversation memory;
- `/internal/v1/corpus/**` access;
- frontend work, database migrations, Qdrant schema changes, Tool registry, dynamic dispatcher,
  interface, or factory;
- non-loopback MCP serving in V1.

## 3. Ownership

| Concern | Owner |
| --- | --- |
| MCP transport and Tool schemas | standalone `paperloom_mcp` |
| JWT validation, role, refresh/revocation policy | Java Spring Security |
| User identity | Java, derived from authenticated principal |
| Paper ACL and Current Model | `PaperReadingService` and existing Java access services |
| Candidate retrieval and canonical exact read | `PaperReadingService` |
| Public response projection and response limit | Java public query facade |
| MCP request and upstream-response byte limits | `paperloom_mcp` |
| Harness disclosure, Evidence, Source Quotes | existing Harness path only; never MCP |

## 4. Java Public Query Facade

### 4.1 Endpoints

| Endpoint | Operation |
| --- | --- |
| `POST /api/v1/reading/papers/search` | bounded accessible-Paper search/browse |
| `POST /api/v1/reading/papers/get` | exact accessible-Paper metadata lookup |
| `POST /api/v1/reading/structure` | PAGE or SECTION navigation for one Paper |
| `POST /api/v1/reading/content/search` | bounded Location preview search in one Paper |
| `POST /api/v1/reading/content/read` | durable exact content read for one Paper |

`POST` carries query bodies but does not imply write semantics.

### 4.2 Authentication And Inputs

`JAVA-AUTH-001`: `/api/v1/reading/**` MUST require role USER or ADMIN. GUEST and anonymous callers
MUST be rejected by Spring Security.

`JAVA-AUTH-002`: The controller MUST derive the database user from `Authentication`; request bodies
MUST NOT accept user ID, username, role, scope, credential, service token, model version, parser
identity, retrieval index identity, Qdrant payload, or Evidence payload fields.

`JAVA-AUTH-003`: Missing and inaccessible Papers MUST return the same status, error code, and safe
message. A caller MUST NOT learn whether another user's Paper exists.

`JAVA-AUTH-004`: ADMIN grants access to the public reading endpoints but MUST NOT bypass Paper ACL.
USER and ADMIN may read only Papers they own or Papers published through the existing access model.

`JAVA-ARG-001`: Java MUST validate types, blank values, duplicates, ranges, list sizes, and text
lengths before reading or retrieval work.

### 4.3 Public DTOs

Paper metadata may contain:

```text
paper_id, title, authors, year, venue, doi, arxiv_id, filename, preview
```

Location previews may contain:

```text
paper_id, title, location_ref, section, page, element_type, preview
```

Structure items may contain:

```text
paper_id, title, location_ref, structure_type, section_title, section_level,
section_path, display_order, page_from, page_to
```

Exact-read items may contain:

```text
paper_id, location_ref, section, page, page_end, content_kind, content
```

`JAVA-DTO-001`: Public responses MUST NOT contain `source_quote_ref`, Source Span JSON, bbox/parser
metadata, model version, SQL ID, storage path, dense/sparse/fused score, index version, Qdrant
identity, or another user's metadata.

`JAVA-DTO-002`: Every successful public response MUST be serialized and measured as UTF-8 before
being returned. The maximum is 16 KiB.

`JAVA-DTO-003`: Paper search, Structure, and content search MUST add ordered result items only while
the serialized response remains within 16 KiB. If more items exist, Java returns
`coverage=truncated` and the next offset where applicable. Existing preview bounds remain unchanged.

`JAVA-DTO-004`: Canonical exact content MUST NOT be silently truncated. An exact-read response
exceeding 16 KiB MUST fail with HTTP 422 and `READING_RESULT_TOO_LARGE`; the caller can request fewer
Locations.

`JAVA-READ-001`: Public exact read MUST call `PaperReadingService.readPaperContent`, recheck ACL and
Current Model, and cause zero inserts or updates to `paper_source_quotes`.

`JAVA-READ-002`: Every exact-read item MUST match the requested `paper_id`. A Location belonging to a
different Paper, an inaccessible Paper, or a non-current model enters `missing_location_refs`; its
actual ownership or model MUST NOT be returned.

`JAVA-AUDIT-001`: Each public query MUST log only bounded `principal_id`, `tool_name`, `request_id`,
and outcome. It MUST NOT log arguments, Paper content, JWTs, provider payloads, or internal URLs.

`JAVA-AUDIT-002`: MCP sends one non-model-controlled `X-Request-ID` per Tool call. Java accepts only a
bounded `[A-Za-z0-9._:-]{1,96}` value; otherwise it replaces it with a local UUID and never logs the
rejected value.

`JAVA-QUOTA-001`: `search_paper_content` reuses the existing Embedding quota attributed to the
authenticated Java user. Metadata search/get, Structure, and exact read add no new model quota.
Insufficient quota returns HTTP 429 and `READING_QUOTA_EXHAUSTED`.

## 5. Standalone MCP Package

### 5.1 Package Boundary

The only Python MCP files are:

```text
paperloom_mcp/
  __init__.py
  __main__.py
  server.py
  requirements.in
  requirements.lock
  tests/
    __init__.py
    test_server.py
```

`MCP-PKG-001`: `paperloom_mcp` MUST have no import from `harness_py` and no Harness dependency.

`MCP-PKG-002`: `paperloom_mcp` is a repository-local package, not a PyPI distribution. From the
repository root, a clean environment MUST install dependencies from `requirements.lock` and start
with:

```bash
python -m paperloom_mcp --host 127.0.0.1 --port 8090
```

`MCP-PKG-003`: `/.venv-mcp/` MUST be ignored by Git.

### 5.2 Tool Surface

MCP initialize MUST advertise Tools capability only. `tools/list` MUST return exactly:

| Tool | Required arguments | Limits |
| --- | --- | --- |
| `search_papers` | none | paper IDs/authors/venues <= 100; limit 1-100 |
| `get_paper` | `paper_id` | one nonblank ID |
| `get_paper_structure` | `paper_id` | PAGE or SECTION; offset >= 0; limit 1-100 |
| `search_paper_content` | `paper_id` | top_k 1-20; passage/table/figure only |
| `read_paper_content` | `paper_id`, `location_refs` | 1-20 refs |

`MCP-TOOL-001`: Tool schemas MUST contain no identity, role, scope, credential, service token,
model-version, parser, Qdrant, Source Quote, Evidence, or Harness fields.

`MCP-TOOL-002`: Results MUST contain equivalent compact JSON Text content and
`structuredContent`.

`MCP-TOOL-003`: Exact read MUST return canonical content without `source_quote_ref` and without
mutating application state.

`MCP-TOOL-004`: Use the MCP SDK low-level Server API so only Tool handlers are registered. The
implementation MUST NOT mutate private SDK handler tables.

## 6. MCP Authentication And Limits

`MCP-AUTH-001`: Every MCP HTTP request MUST contain `Authorization: Bearer <token>`. Query-string,
cookie, body, or Tool-argument tokens MUST be rejected.

`MCP-AUTH-002`: Python MUST treat the token as opaque and forward it unchanged to Java on every Tool
call. It MUST NOT decode, cache, refresh, or derive User/Scope from the JWT.

`MCP-AUTH-003`: Missing/malformed Bearer authentication MUST fail at the MCP transport with HTTP 401.
An invalid, expired, revoked, or disallowed-role token MUST fail through Java on the next Tool call.

`MCP-AUTH-004`: MCP does not propagate Java's `New-Token` refresh header and MUST NOT place any token
in Tool results. Clients own token renewal out of band and must reconnect or update their MCP
Authorization configuration with a current JWT.

`MCP-ISO-001`: The MCP server MUST be stateless. Calls with different JWTs, including concurrent
calls, MUST not share identity, disclosure, content, Evidence, or conversation state.

`MCP-LIMIT-001`: MCP request bodies are limited to 1 MiB before protocol parsing.

`MCP-LIMIT-002`: Java upstream responses are limited to 16 KiB while streaming. Python MUST check
`Content-Length` when present and enforce the same limit while reading chunks, before JSON parsing.

`MCP-LIMIT-003`: V1 MUST bind only to loopback addresses. Remote serving is a future change that
requires an explicit HTTPS deployment spec.

`MCP-AUDIT-001`: MCP generates one UUID per Tool call and forwards it only as `X-Request-ID`; it is
not a Tool argument or application Session state.

## 7. Error Contract

| Java/transport condition | MCP result |
| --- | --- |
| missing Bearer | HTTP 401 before MCP handling |
| Java 401 | Tool error `UNAUTHORIZED` |
| Java 403 | Tool error `FORBIDDEN` |
| MCP Tool schema validation failure | Tool error `READING_ARGUMENTS_INVALID` without a Java call |
| Java argument rejection after schema validation | Tool error `READING_ARGUMENTS_INVALID` |
| missing/inaccessible Paper | `PAPER_NOT_AVAILABLE` with one fixed safe message |
| Current Model unavailable | `READING_MODEL_NOT_READY` |
| Embedding quota unavailable | `READING_QUOTA_EXHAUSTED` |
| retrieval unavailable | `RETRIEVAL_UNAVAILABLE` |
| Java/public response too large | `READING_RESULT_TOO_LARGE` |
| unexpected/non-JSON upstream response | `INVALID_READING_RESPONSE` |

`MCP-ERR-001`: HTTP 401/403 MUST be classified before attempting to parse the response body.

`MCP-ERR-002`: Tool errors MUST NOT include JWTs, upstream URLs, raw response bodies, SQL, stack
traces, provider payloads, or Paper content.

## 8. Verification

### 8.1 Java

| Test | Requirement |
| --- | --- |
| USER/ADMIN allowed; GUEST/anonymous rejected with real SecurityFilterChain | `JAVA-AUTH-001` |
| caller-supplied identity/scope/internal fields rejected | `JAVA-AUTH-002` |
| unknown and inaccessible Paper responses are indistinguishable | `JAVA-AUTH-003` |
| argument, adaptive query pagination, and exact-read UTF-8 response bounds | `JAVA-ARG-001`, `JAVA-DTO-002/003/004` |
| Current Model, ACL, ADMIN-no-bypass, and cross-Paper failures | `JAVA-AUTH-004`, `JAVA-READ-001/002` |
| exact-read Source Quote count unchanged | `JAVA-READ-001` |
| quota attribution/429 and audit request-ID sanitization without content | `JAVA-QUOTA-001`, `JAVA-AUDIT-001/002` |

### 8.2 MCP

Tests MUST use the MCP client library against the in-process or loopback server, not direct Tool
handler calls.

| Test | Requirement |
| --- | --- |
| initialize advertises Tools only; exact five schemas | `MCP-TOOL-001/004` |
| all Tools call only `/api/v1/reading/**` | package decision |
| Text and structured result equivalence | `MCP-TOOL-002` |
| missing and Java-rejected auth, including empty/non-JSON 401/403 | `MCP-AUTH-003`, `MCP-ERR-001` |
| concurrent user-A/user-B calls with forced interleaving | `MCP-ISO-001` |
| 1 MiB request and 16 KiB streamed upstream result limits | `MCP-LIMIT-001/002` |
| exact read has no Source Quote and no persistence side effect | `MCP-TOOL-003` |
| clean locked install and CLI start | `MCP-PKG-002` |
| Java refresh header never enters an MCP result | `MCP-AUTH-004` |

### 8.3 Regression Commands

```bash
mvn test
.venv-harness/bin/python -m unittest discover -s harness_py/tests -p 'test_*.py'
.venv-mcp/bin/python -m unittest paperloom_mcp.tests.test_server
```

The final smoke test MUST use a real USER JWT and real PDF through MCP
`search_papers -> search_paper_content -> read_paper_content`. It MUST record the public response
fields and prove that Source Quote count did not change.

## 9. Delivery

Commit sequence:

1. `fix(reading): bound and secure public query facade`
2. `feat(mcp): add standalone stateless reading server`
3. `docs(mcp): document isolated setup and security contract`

No commit or push is allowed until all verification above is green.

## 10. Verification Record

Verified on 2026-08-24:

- Java: 430 run, 3 skipped, 427 passed;
- Harness regression: 207 run, 7 skipped, 200 passed;
- standalone MCP clean locked install: 11 run and passed;
- SecurityFilterChain tests recorded bounded anonymous/GUEST denial audits, and a live default-profile
  request confirmed query/response/provider payloads and provider URLs were absent from logs;
- malformed-JWT tests confirmed tokens and exception payloads were absent from logs; generic API
  logging skips Reading routes, framework per-request loggers remain at INFO, and non-Reading
  authentication behavior remains unchanged;
- loopback smoke used a real USER JWT and a ready, published PDF through all five Tools;
- observed top-level fields were `query_text/papers/counts/coverage`, Paper metadata,
  `structure_type/items/counts/coverage`, `query_text/locations/counts/coverage`, and
  `items/missing_location_refs`; exact items contained only
  `paper_id/location_ref/section/page/page_end/content_kind/content`;
- every Tool returned compact JSON Text equivalent to `structuredContent`;
- `paper_source_quotes` remained at 482 before and after the exact read.
