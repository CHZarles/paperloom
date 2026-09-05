# Evidence-Grounded Field Extraction And Read-Only MCP Proposal

Date: 2026-08-22

Status: Proposed.

## 1. Decision

PaperLoom V1 delivers two related but independent capabilities:

1. a real product endpoint that extracts 1-10 caller-selected fields from one authorized Paper and
   returns an evidence-bound structured result;
2. a Tool-only MCP Server that exposes PaperLoom's Paper and Reading Model as stateless, read-only
   queries for external Agents.

The two capabilities share Java's data-query implementation, authorization, Current Reading Model
selection, quota, and audit. They do not share an Agent Tool contract.

```text
                         Java ReadingQueryService
                 canonical query + ACL + current model
                         /                     \
                        /                       \
        Harness adapter                           public query facade
        stateful Agent protocol                    safe bounded DTOs
        disclosure + Evidence Ledger                         |
        Source Quote materialization                         |
        final submission validation                 Python MCP adapter
                                                     stateless query Tools
```

The key decision is:

> Share Java query capability, not Harness Tool behavior.

`ReadingQueryService` is a concrete Java module, not a generic Tool framework. Harness and MCP keep
their own names, schemas, results, and protocol semantics.

## 2. Why This Is The Right Scope

PaperLoom already has the data needed for a useful research capability:

- `Paper` and `PaperAccessService` define the accessible corpus;
- `PaperReadingModel` identifies the Current Reading Model;
- `PaperLocation` gives stable navigation through `location_ref`;
- `PaperPassage.contentText` stores durable passage content;
- `PaperReadingElement` stores durable table, figure, formula, and other element content;
- `PaperSourceQuote` supports citations that can reopen the PDF at the correct page and region.

The missing product loop is not another open-ended research mode. It is a bounded operation over this
model:

```text
one paper + requested fields
-> retrieve and read evidence
-> return one result per field
-> preserve reopenable citations
```

This is small enough to implement and evaluate end to end. It also exercises the parts that distinguish
PaperLoom from a generic chat wrapper: structured Reading Model navigation, exact evidence reads, and
PDF-grounded citations.

MCP solves a different problem. It lets an external Agent query PaperLoom's corpus, but it does not run
PaperLoom's Harness protocol on the caller's behalf. Reusing `ReadingCorpusTools` in MCP would import
Paper disclosure state, Location disclosure state, Evidence Ledger mutation, Source Quote creation,
submission rules, and Run ordering into what should be ordinary queries.

## 3. Goals And Non-Goals

### 3.1 Goals

- Add a callable, authenticated Field Extraction product endpoint without requiring a frontend page.
- Reuse the existing Harness Runtime and evidence lifecycle for Field Extraction.
- Add a Java-owned query module over the existing Paper and Reading Model data.
- Resolve exact content from durable `location_ref` data, without trusting a client-supplied Qdrant
  Evidence Payload.
- Expose a separate read-only MCP query surface with Java-enforced authorization.
- Keep every MCP response bounded and free of internal retrieval or parser details.
- Preserve current Chat, Catalog, and Research behavior when no Field Extraction task is present.

### 3.2 Non-Goals

- A generic Tool registry, workflow engine, or second Agent Runtime.
- Contract parity between Harness Tools and MCP Tools.
- Running Field Extraction or answer generation through MCP.
- MCP Prompts or Resources in V1.
- MCP writes, Source Quote creation, Evidence Ledger mutation, or conversation memory.
- New database tables for extraction templates or extraction results.
- Cross-paper comparison, reproduction packages, external literature search, or visual-model reading.
- A dedicated Field Extraction frontend before API quality has been measured.

## 4. Ownership And Interfaces

### 4.1 Ownership

| Concern | Owner |
| --- | --- |
| User identity and role | Java / Spring Security |
| Paper ACL and published-Paper access | `PaperAccessService` |
| Current Reading Model | Java Reading Model services |
| Paper search, structure, content search, canonical exact read | `ReadingQueryService` |
| Embedding quota and audit | Java |
| Harness Paper/Location disclosure | `ReadingCorpusTools` |
| Current-Run Evidence Ledger | Harness |
| `PaperSourceQuote` materialization | Harness Java adapter through `PaperSourceQuoteReadService` |
| Agent loop and final submission validation | Harness |
| MCP protocol mapping | Python MCP adapter |
| MCP query response projection | Java public query facade |

"Read-only MCP" means that MCP does not modify Paper, Reading Model, Source Quote, conversation, or
Harness state. Java may still record operational audit and quota usage for a query.

### 4.2 Harness And MCP Are Different Adapters

| Property | Harness Tool layer | MCP Tool layer |
| --- | --- | --- |
| Purpose | Govern a PaperLoom Agent Run | Let an external Agent query PaperLoom |
| Scope | Java-originated locked Run scope | Authenticated user's accessible library |
| State | Disclosure sets, Evidence Ledger, Run facts | No application-level Session state |
| Ordering | Paper and Location disclosure rules | No disclosure ladder |
| Exact read | Materializes `PaperSourceQuote` | Returns content only |
| Output | Harness protocol payload and evidence refs | Safe public query DTO |
| Final answer | Harness validates submission | MCP never generates or submits an answer |

The adapters may happen to expose similar concepts, but they MUST NOT share Python schemas or require
payload equality. There is no `reading_tool_contract.py`, shared Tool registry, or dynamic Java
`/agent-tools/{tool_name}` dispatcher in V1.

## 5. Java Reading Query Module

### 5.1 Shape

Refactor the existing query logic in `CorpusRetrievalService` into one concrete
`ReadingQueryService`. This is a rename and responsibility cleanup, not a wrapper placed on top of the
existing class. Do not add an interface, factory, registry, or arbitrary query executor.

The module owns five data operations:

| Java operation | Behavior |
| --- | --- |
| `searchPapers` | Search or browse Papers visible to the effective user |
| `getPaper` | Return one accessible Paper by exact `paper_id` |
| `getStructure` | Return PAGE or SECTION navigation from the Current Reading Model |
| `searchContent` | Return ranked Location candidates from the Current Reading Model |
| `readContent` | Resolve exact durable content for bounded `location_ref` values |

Harness identity matching may call `searchPapers` with its existing identity filters or keep a small
Harness-specific matcher. MCP does not need to inherit the Harness operation
`find_papers_by_identity`.

Every operation receives an effective Java user identity, applies `PaperAccessService`, and uses only
the Current `READING_MODEL_READY` model. Missing and inaccessible Papers or Locations must be
indistinguishable to the caller.

### 5.2 Durable Exact Read

Current exact reads of PASSAGE, TABLE, and FIGURE Locations require Python to echo hidden content from
a previous Qdrant result. That is unnecessary because `PaperLocation.sourceObjectId` already points to
durable content:

```text
PASSAGE location
-> PaperLocation.sourceObjectId
-> PaperPassage.passageRef
-> PaperPassage.contentText + sourceSpanJson

TABLE / FIGURE location
-> PaperLocation.sourceObjectId
-> PaperReadingElement.readingElementId
-> searchableText / bodyText / captionText + sourceSpanJson
```

Move the existing resolution logic from
`ReadingModelQdrantIndexService.resolveLocationText()` into one batch-capable durable Location
resolver. Both Qdrant indexing and `ReadingQueryService.readContent` reuse that resolver. This avoids a
second interpretation of `sourceObjectId` and avoids per-Location database reads.

The canonical read flow becomes:

```text
location_ref
-> PaperLocation
-> Paper ACL check
-> Current Reading Model check
-> durable Passage or Reading Element
-> canonical content and public location metadata
```

Clients MUST NOT provide `content_text`, `content_hash`, `source_span_json`, parser version, model
version, or Qdrant payloads as proof. Qdrant remains a candidate index; MySQL Reading Model data is the
authority for exact reads.

### 5.3 Two Java Entry Paths

The Java query implementation has two adapters because the trust and output requirements differ.

#### Internal Harness adapter

The existing `/internal/v1/corpus/**` routes remain restricted by the current internal token and
Java-originated Run identity/scope.

- paper, structure, and search routes delegate to `ReadingQueryService`;
- exact read calls `ReadingQueryService.readContent` and then
  `PaperSourceQuoteReadService.createQuotes`;
- the response may include internal diagnostics needed by Harness evaluation;
- Source Quote creation remains part of the Harness evidence lifecycle only.

#### Public query facade

Add explicit authenticated query endpoints:

```text
POST /api/v1/reading/papers/search
POST /api/v1/reading/papers/get
POST /api/v1/reading/structure
POST /api/v1/reading/content/search
POST /api/v1/reading/content/read
```

`POST` is used only to carry bounded query bodies; these endpoints still have read-only corpus
semantics.

Java MUST:

1. authenticate the JWT with existing Spring Security;
2. allow `USER` and `ADMIN` in V1;
3. derive the user ID from the authenticated principal;
4. reject identity, role, scope, credential, model-version, and internal-payload fields;
5. apply Paper ACL, Current Reading Model selection, applicable quota, and audit on every call;
6. return bounded public DTOs directly.

The public facade must not return a rich internal result and rely on Python MCP to redact it. A user
with a valid JWT can call these Java endpoints directly, so Java itself is the security and data
minimization boundary.

### 5.4 Public DTO

An exact-read item may contain:

```json
{
  "paper_id": "paper_123",
  "location_ref": "location_abc",
  "section": "Methods",
  "page": 7,
  "page_end": 7,
  "content_kind": "PASSAGE",
  "content": "..."
}
```

Public query DTOs MUST NOT contain:

- `source_quote_ref` or any newly materialized citation entity;
- hidden Evidence Payloads or Source Span JSON;
- dense, sparse, or fused retrieval scores;
- parser names, parser versions, object-storage paths, SQL identifiers, or Qdrant internals;
- another user's Paper existence or metadata.

## 6. Field Extraction Product Capability

### 6.1 Product Entry

Add one authenticated synchronous endpoint:

```http
POST /api/v1/papers/{paperId}/field-extractions
Authorization: Bearer <PaperLoom JWT>
Content-Type: application/json

{
  "fields": ["optimizer", "learning rate", "batch size"]
}
```

This is the minimal real product entry: it can be called by the frontend later, by an evaluation
runner now, or by another PaperLoom client. It does not require a chat conversation or a new frontend
workflow.

Java validates the user and Paper, fixes `SourceScope` to that one Paper, creates a request ID, and
forwards a typed `FIELD_EXTRACTION` task through the existing `ResearchHarnessTransport`. The request
does not enter conversation history and does not use conversation-generation serialization.

V1 is synchronous because `ResearchHarnessTransport.run()` already exists. Add async job persistence,
cancellation, or a frontend progress workflow only after measured latency makes them necessary.

### 6.2 Request And Result

The Harness receives:

```json
{
  "task": {
    "type": "FIELD_EXTRACTION",
    "paper_id": "paper_123",
    "fields": ["optimizer", "learning rate", "batch size"]
  },
  "scope": {
    "mode": "MANUAL_SOURCE",
    "paper_ids": ["paper_123"]
  }
}
```

The product endpoint returns structured data plus existing citation mappings:

```json
{
  "schema_version": "paper-field-extraction/v1",
  "request_id": "...",
  "paper_id": "paper_123",
  "items": [
    {
      "field": "learning rate",
      "status": "FOUND",
      "value": "1e-4",
      "source_quote_refs": ["source_quote_abc"]
    },
    {
      "field": "batch size",
      "status": "NOT_FOUND",
      "value": null,
      "source_quote_refs": []
    }
  ],
  "references": []
}
```

Input is limited to one accessible Paper and 1-10 nonblank, case-insensitively unique fields. No
Extraction entity is persisted in V1. Source Quotes used by `FOUND` results remain persisted through
the existing Harness evidence path so the returned citations can reopen the PDF.

### 6.3 Harness Contract

Field Extraction reuses the existing Harness Runtime and reading Tools. It adds one internal terminal
Tool, `submit_field_extraction`, which is never exposed through MCP.

For every requested field, the model submits exactly one Item:

| Status | Value | Deterministic evidence rule |
| --- | --- | --- |
| `FOUND` | Required nonblank string | At least one current-Run Source Quote from the target Paper |
| `NOT_FOUND` | `null` | Empty Source Quote refs and at least one successful nonblank content search on the target Paper in the Run |

The validator proves only:

- request and result shape;
- exact field-set equality;
- current-Run Source Quote membership;
- target-Paper identity;
- whether a nonblank target-Paper search occurred before a `NOT_FOUND` result.

It cannot deterministically prove that a search query was semantically relevant to each field or that
the field is globally absent from the Paper. Do not add a field-to-search reference mechanism that only
creates the appearance of such proof. Search quality and `NOT_FOUND` correctness belong in Golden
evaluation.

### 6.4 Deterministic Rendering

The model submits structured Items only. Harness renders Markdown and appends trusted Source Quote
markers after validation.

Field names and values containing Markdown table delimiters, line breaks, backslashes, or
`[[source_quote_*]]` text must be escaped or neutralized before rendering. Untrusted model text must
never create a citation or crash citation parsing.

## 7. MCP Server

### 7.1 Tool Surface

MCP V1 declares Tools capability only. It exposes five MCP-specific query Tools:

| MCP Tool | Query |
| --- | --- |
| `search_papers` | Search or browse the authenticated user's accessible Papers |
| `get_paper` | Read metadata for one accessible `paper_id` |
| `get_paper_structure` | Read PAGE or SECTION navigation for one Paper |
| `search_paper_content` | Search one Paper and return bounded Location previews |
| `read_paper_content` | Read exact content for bounded Location refs in one Paper |

These are not aliases for the five Harness Tools. For example, MCP uses `get_paper` instead of
`find_papers_by_identity`, does not require a Paper disclosure call before content search, and never
returns a `source_quote_ref` from exact read.

V1 does not expose Prompts, Resources, `extract_paper_fields`, `research_papers`, submission Tools,
repair Tools, or administration Tools. External Agents own their orchestration and synthesis.

### 7.2 Stateless Adapter

The Python MCP adapter maps each MCP Tool to one explicit public Java query endpoint. It does not use
`ReadingCorpusTools` and does not call `/internal/v1/corpus/**`.

It keeps no application-level Session state:

- no authorized-Paper or disclosed-Location set;
- no Evidence Ledger or Source Quote cache;
- no Harness Run, Agent scratchpad, or conversation memory;
- no ordering lock, final submission, or idle-state cleanup.

Calls are independently authorized and may run in parallel. MCP transport request IDs do not become
PaperLoom business state.

### 7.3 Authentication And Errors

The MCP adapter forwards the current request's PaperLoom bearer JWT to Java. Python does not decode the
JWT and never accepts caller-supplied user or scope fields.

Java maps invalid arguments, authentication, ACL/not-found, quota, and unavailable failures to stable,
bounded errors. Python only translates those errors into MCP results. Neither layer exposes another
user's Paper existence, stack traces, internal URLs, credentials, SQL, or provider payloads.

Local development listens on loopback. Remote deployment requires HTTPS; a second service token is
not needed for V1 because it would authenticate the adapter, not authorize the end user.

## 8. Code Changes

### 8.1 Java Query And Adapters

| File | Change |
| --- | --- |
| `CorpusRetrievalService.java` / `ReadingQueryService.java` | Rename and reshape the existing implementation into the concrete, query-only five-operation module; do not retain a pass-through wrapper |
| `CanonicalReadingLocationService.java` | Resolve exact evidence content from durable Passage and Reading Element data in batches |
| `ReadingModelQdrantIndexService.java` | Reuse the same durable Location resolver instead of owning a second resolution implementation |
| `InternalCorpusController.java` | Delegate queries to `ReadingQueryService`; materialize Source Quotes only on the Harness exact-read route |
| `ReadingQueryController.java` | Add the five explicit authenticated public query endpoints and safe DTO projection |
| `SecurityConfig.java` | Restrict `/api/v1/reading/**` to `USER` and `ADMIN` |

No database migration, Qdrant collection, or object-storage change is required.

### 8.2 Java Field Extraction Entry

| File | Change |
| --- | --- |
| `FieldExtractionController.java` | Add `POST /api/v1/papers/{paperId}/field-extractions` |
| `FieldExtractionService.java` | Validate one Paper and 1-10 fields, fix scope, call the existing Harness transport, and project the result |
| `ProductTurnRequest.java` | Add optional structured `task` data while preserving existing constructors |
| `ResearchHarnessPayloadFactory.java` | Forward the canonical task only when present |
| `ResearchHarnessResultMapper.java` | Preserve structured Field Extraction output and citation mappings |
| `SecurityConfig.java` | Restrict the Field Extraction endpoint to `USER` and `ADMIN` |

### 8.3 Python Harness

| File | Change |
| --- | --- |
| `harness_py/corpus/gateway.py` | Stop echoing hidden Evidence Payloads for exact reads |
| `harness_py/corpus/tools.py` | Keep existing Harness Tool definitions and stateful disclosure/Evidence behavior |
| `harness_py/orchestration/runtime.py` | Accept the optional canonical task and select the Field Extraction contract |
| `harness_py/orchestration/research_contract.py` | Add the submission schema, validator, and honest `NOT_FOUND` search-attempt check |
| `harness_py/orchestration/agents/tools.py` | Register the Harness-only `submit_field_extraction` Tool |
| `harness_py/orchestration/run_output.py` | Emit structured extraction data and safely rendered cited Markdown |
| `harness_py/transport/service.py` | Reject invalid tasks before Corpus or model work |

Do not add a shared Harness/MCP Tool contract module.

### 8.4 Python MCP Adapter

Add only:

```text
harness_py/transport/mcp_server.py
harness_py/tests/test_mcp_server.py
```

Also add `mcp-serve` to `harness_py/cli.py` and declare the already-locked MCP SDK explicitly in
`requirements.in`. The adapter owns MCP schemas and protocol mapping only; Java public query DTOs are
already safe and bounded.

### 8.5 Frontend

No V1 frontend change is required. The authenticated Field Extraction endpoint is the product entry
and evaluation surface. A dedicated UI is justified only after the Golden evaluation shows acceptable
field accuracy and citation quality.

## 9. Verification

### 9.1 Java Query Tests

- Each public query derives identity from Spring Security and rejects body-supplied identity or scope.
- `USER` and `ADMIN` can query only accessible or published Papers; `GUEST` is rejected.
- Missing and inaccessible Papers or Locations have indistinguishable external errors.
- Exact read succeeds from `paper_id` and `location_ref` alone.
- Exact read rejects stale Locations from a non-current Reading Model.
- Indexing and canonical read resolve the same durable content for PASSAGE, TABLE, and FIGURE.
- Public DTOs contain no Source Quote, Evidence Payload, Source Span, score, parser, storage, or Qdrant
  fields.
- Calling any public reading endpoint, including exact read, leaves the `paper_source_quotes` count
  unchanged.
- Quota and audit are attributed to the authenticated user.

### 9.2 Harness And Field Extraction Tests

- The internal exact-read route still creates reopenable, idempotent Source Quotes after Java access
  validation.
- Existing Paper/Location disclosure and Evidence Ledger rules remain unchanged.
- Empty, duplicate, oversized, or more-than-10 field requests fail before a model call.
- Missing, extra, or duplicate submitted fields are rejected.
- `FOUND` with a blank value, missing ref, prior-Run ref, or another-Paper ref is rejected.
- `NOT_FOUND` with a value or refs, or without a successful nonblank target-Paper search, is rejected.
- Tests and product copy do not claim that the deterministic `NOT_FOUND` check proves semantic
  relevance or global absence.
- Pipes, newlines, backslashes, and fake Source Quote markers in fields or values cannot alter
  citations or crash rendering.
- No-task Direct, Catalog, and Research regression suites remain compatible.
- The Java product endpoint enforces Paper ACL, fixes scope to one Paper, and returns the structured
  result plus reopenable citation mappings.

### 9.3 MCP Tests

- Initialize advertises Tools only; `tools/list` contains exactly the five MCP query names.
- MCP schemas contain no user, role, scope, credential, model-version, or hidden-payload fields.
- Every call uses one `/api/v1/reading/**` endpoint and never `/internal/v1/corpus/**`.
- `read_paper_content` returns content and Location metadata but no `source_quote_ref`.
- MCP exact reads do not insert or update `PaperSourceQuote`.
- Calls with different JWTs remain isolated without MCP Session state.
- Parallel calls do not share disclosure, Evidence, or conversation state.
- Java authorization and safe error responses survive MCP translation unchanged.

### 9.4 Golden Evaluation

Before adding a frontend, evaluate Field Extraction on at least 20 Papers with 5-10 labelled fields per
Paper, including values in prose, lists, tables, appendices, and genuinely absent fields.

Report:

- field-value accuracy;
- `FOUND` precision and recall;
- `NOT_FOUND` precision;
- citation accuracy and unsupported-citation rate;
- citation-location success;
- retrieval Recall@5 and Recall@10;
- evidence-reading coverage;
- TTFT P50/P95 and complete latency P50/P95.

The deterministic validator and Golden evaluation answer different questions: the validator enforces
protocol facts; the evaluation measures whether the Agent found the right fact and evidence.

## 10. Acceptance Criteria

1. Field Extraction has a real authenticated Java product endpoint and processes one Paper plus 1-10
   fields through the existing Harness.
2. Every requested field appears exactly once; every `FOUND` value references a current-Run Source
   Quote from the target Paper.
3. `NOT_FOUND` is represented as a bounded search outcome, not a proven global absence.
4. Deterministic rendering cannot create citations from untrusted field names or values.
5. Java canonical exact read uses durable Reading Model data and no client Evidence Payload.
6. Harness exact read still materializes Source Quotes and updates the Evidence Ledger.
7. MCP advertises only five stateless query Tools and never starts a Harness Run or generates an answer.
8. MCP exact read returns no Source Quote and causes no Source Quote insert.
9. Java, not Python, enforces identity, role, Paper ACL, Current Reading Model, quota, audit, response
   bounds, and public data minimization.
10. Harness and MCP have independent Tool contracts; only the Java query implementation is shared.
11. No new database model, generic Tool framework, MCP Prompt, MCP Resource, or frontend page is added.

## 11. Delivery Order

1. Make canonical exact read resolve durable Passage and Reading Element content from `location_ref`.
2. Route Qdrant indexing and exact read through the same durable Location resolver.
3. Refactor the existing corpus implementation into the pure `ReadingQueryService` and adapt the
   internal Harness route, preserving Harness Source Quote creation.
4. Run existing Corpus, disclosure, citation, and no-task Harness regressions.
5. Add the Field Extraction contract and the authenticated Java product endpoint.
6. Run deterministic tests and the first Golden Field Extraction evaluation.
7. Add the authenticated Java public query facade.
8. Add the stateless Python MCP adapter over the public query facade.
9. Expose MCP beyond loopback only after authorization, isolation, no-insert, and bounded-response tests
   pass.

This order proves the shared Java query seam first, closes the business loop second, and adds MCP last
without coupling MCP to Harness state.
