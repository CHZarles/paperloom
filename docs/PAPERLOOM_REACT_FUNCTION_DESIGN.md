# PaperLoom Product ReAct Harness Design

Date: 2026-06-29

Status: User-confirmed product target. This document is the implementation standard for the
PaperLoom chat refactor. If existing code conflicts with this document, treat the code as drift
unless the product requirements document says otherwise.

## Product Boundary

The Product ReAct Harness is for product chat sessions only.

It must not be coupled to benchmark harnesses, eval corpora, LitSearch, QASPER, PageIndex, or
parser/OCR experiments. Benchmarks may test product behavior externally, but they must not define
the product function catalog, product prompt, or product chat interface.

Product chat reads only the current user's permissioned product paper library and the locked session
scope. Eval data is unreachable by construction.

## Root Defect Being Fixed

The current chat path is split across router, planner, retrieval, focus state, renderer, and
citation code. This creates a fundamental context defect:

- the LLM does not consistently receive the complete relevant session history;
- product capabilities are not exposed as one stable function catalog every turn;
- `LlmTaskRouter` can call private tools, but the answer generator does not share that ReAct state;
- `PaperAnswerService` keeps routing, focus, retrieval, and rendering decisions in one broad
  service;
- Redis focus state and raw paper id sets can become implicit routing state;
- library status questions can accidentally be answered through paper-content retrieval.

The fix is not another phrase match. The target is one Product ReAct Harness that owns each user
turn end to end.

## Non-Negotiable Rules

- No production semantic routing through hardcoded phrases, topic regexes, or keyword alternations.
- No fallback answer that bypasses a failed product capability.
- No raw SQL, Elasticsearch DSL, retrieval parameters, or internal `paperId` sets exposed to the
  LLM.
- No `includeEval`, `is_eval`, eval indices, or benchmark data in product chat.
- No chunk retrieval for product-state questions such as paper count, paper list, processing state,
  or session scope.
- No citations unless a tool returned citeable evidence in the current turn or a durable reference
  registry resolves a previous reference.
- Every turn exposes the full product function catalog.
- Every turn uses full session context, with model-window-aware memory compression when needed.
- Backend tools enforce permission, locked session scope, product-only data, searchable state, and
  reference validity.

## Target Architecture

Default product chat should become:

```text
ChatHandler / WebSocket entry
  -> ProductConversationService
  -> ProductReActHarness
  -> ProductToolRegistry
  -> ProductMemoryService
  -> ConversationReferenceRegistry
  -> ProductTraceRecorder
```

Responsibilities:

```text
ProductConversationService
  auth/user/conversation/session-scope loading
  persisted history and memory loading
  ProductReActHarness invocation
  user and assistant message persistence
  reference registry persistence
  referenceMappingsJson render snapshot persistence
  memory update
  progress/final event emission

ProductReActHarness
  ReAct loop
  prompt construction
  tool catalog exposure
  LLM call execution
  tool call execution
  answer envelope schema validation
  evidence marker and citation validation
  stop reason and result status

ProductToolRegistry
  product-level read-only function catalog
  tool argument validation
  locked-scope enforcement
  opaque ref validation
  low-level service calls

ProductMemoryService
  structured conversation memory compression
  memory schema validation
  memory persistence

ConversationReferenceRegistry
  persistent paper/evidence/page/citation refs
  ref-to-source lookup
  conversation and scope validation

ProductTraceRecorder
  disk-only JSON trace recording
  no business-table dependency
```

Old code should not be preserved for compatibility by default. Keep or migrate old code only when it
has a clear, necessary, product-level responsibility.

Replace or remove from the product chat decision path:

- `PaperAnswerService` as a routing and retrieval decision owner;
- `LlmTaskRouter` as top-level product chat router;
- `EvidencePlanner` as top-level intent planner;
- Redis `FocusState` as follow-up truth;
- legacy `AgentToolRegistry` chat tools as the primary product catalog;
- `PaperConversationAgent` as the core abstraction.

Useful existing primitives may be reused behind the new seams:

- `ConversationScopeService`
- `ConversationService`
- `PaperLibraryStatusService`
- `PaperService`
- `ProductPaperCorpus`
- `PaperRetrievalService`
- `HybridSearchService`
- `EvidenceToolExecutor` primitives where still useful
- `EvidenceLedgerService`
- `EvidenceVerifier`
- `LlmProviderRouter`

## Main Harness Contract

The harness accepts product turn semantics only. It does not accept retrieval parameters.

```java
ProductTurnResult run(ProductTurnRequest request);
```

`ProductTurnRequest`:

```java
record ProductTurnRequest(
    Long userId,
    Long conversationId,
    String generationId,
    String userMessage,
    ProductSessionScope lockedScope,
    List<ConversationMessage> history,
    ConversationMemory memory,
    ProductModelContext modelContext
) {}
```

Forbidden request fields:

```text
searchMode
topK
rerankEnabled
pageWindow
includeEval
paperIds
esIndexName
sqlFilter
```

`ProductTurnResult`:

```java
record ProductTurnResult(
    String finalAnswerMarkdown,
    AnswerEnvelope envelope,
    List<ReferenceMapping> references,
    List<ToolProgressEvent> progressEvents,
    TraceWriteStatus traceWriteStatus,
    StopReason stopReason,
    ResultStatus resultStatus
) {}
```

`ResultStatus`:

```text
COMPLETED
DEGRADED
FAILED
```

`StopReason`:

```text
COMPLETED
MAX_REACT_ROUNDS
TOOL_FAILED
ANSWER_SCHEMA_INVALID
CITATION_VALIDATION_FAILED
REFERENCE_PERSISTENCE_FAILED
MEMORY_UPDATE_FAILED
TRACE_WRITE_FAILED
```

## Conversation Context And Memory

Every Product ReAct turn should receive complete usable session context.

Prompt input shape:

```text
system prompt
locked session scope
structured session memory JSON
recent verbatim message tail
current user message
full product tool catalog
```

Rules:

- If the full session history fits the model context budget, use the full verbatim history.
- If it approaches or exceeds the model context window, use persisted structured memory plus a
  recent verbatim tail.
- Memory compression is automatic based on model context budget.
- Memory is non-authoritative. It cannot override product tools, locked scope, reference registry,
  or evidence returned by tools.
- Memory must not be used as citeable paper evidence.
- Paper content questions still require `retrieve_evidence` or inspection tools.

Memory is persisted in MySQL as part of the conversation domain. Redis may cache it but is not the
source of truth.

Memory is incrementally updated after every successful turn:

```text
previousMemoryJson
+ currentUserMessage
+ final answer envelope
+ tool call summary
+ new reference ids
+ scope snapshot id
-> newMemoryJson
```

The memory update uses `LlmProviderRouter` as a separate LLM call:

```text
purpose = MEMORY_COMPRESSION
```

The main ReAct call uses:

```text
purpose = PRODUCT_REACT
```

Memory output schema:

```json
{
  "userGoals": [],
  "confirmedConstraints": [],
  "openQuestions": [],
  "papersDiscussed": [],
  "referencesDiscussed": [],
  "decisions": [],
  "failedAttempts": [],
  "sessionScope": {
    "scopeSnapshotId": "",
    "immutable": true
  }
}
```

If answer generation and citation validation succeed but memory update fails, the answer may be
saved, but the turn must return a visible `DEGRADED` status. It must not silently continue with old
memory.

## Session Scope

Session scope is locked outside the LLM and injected into every tool by the harness.

LLM tools cannot modify scope, expand scope, or pass arbitrary internal paper ids to define scope.

Scope creation happens before or at the first accepted user message:

```text
All accessible product papers
Collection-derived snapshot
Title-match source set snapshot
```

Title-match snapshot creation supports:

- plain title query by default;
- explicit regex mode;
- backend preview of matched papers;
- permission filtering;
- searchable product papers only;
- fixed source set snapshot after confirmation.

After a session is locked:

- scope is immutable;
- newly uploaded papers do not enter the existing session;
- collection edits do not affect the existing session;
- changing scope requires a new session.

Tools may accept semantic constraints inside locked scope, for example `titleQuery`, `titleRegex`,
`author`, `yearRange`, `ordinal`, `userMention`, or opaque refs. They may not accept raw `paperId`
sets as scope.

## Opaque Reference Model

The LLM and frontend may use opaque refs generated by the harness. They may not use raw internal ids
as the tool contract.

Allowed refs:

```text
paperRef
evidenceRef
pageRef
citationRef
```

Forbidden public tool identifiers:

```text
paperId
chunkId
SQL id
ES document id
```

Each ref must bind to:

```text
conversationId
scopeSnapshotId
createdTurnId
refType
source entity
source payload
display payload
```

Tool execution must verify:

- the ref belongs to the current conversation;
- the ref is valid for the locked session scope;
- the ref type matches the requested operation.

## Persistent Reference Registry

References are product facts, not trace-only data and not Redis-only state.

Add an independent persistent reference registry. `Conversation.referenceMappingsJson` may remain
as a render snapshot, but it is not the only source of truth.

Target table:

```text
paper_conversation_reference
  ref_id
  conversation_id
  scope_snapshot_id
  turn_id
  ref_type
  source_entity_id
  source_payload_json
  display_payload_json
  created_at
```

Constraints:

```text
unique(conversation_id, ref_id)
index(conversation_id, ref_type)
index(scope_snapshot_id)
```

Reference registry writes are required for citation-bearing answers. If registry persistence or the
render snapshot fails, the turn fails. Do not return a citation-bearing answer whose citations will
not be recoverable after history reload.

## Citation Ownership

The LLM does not generate final citation numbers.

Tool results return evidence or reference ids:

```json
{
  "evidenceRef": "ev_...",
  "paperRef": "paper_...",
  "paperTitle": "...",
  "pageNumber": 7,
  "snippet": "...",
  "sectionTitle": "..."
}
```

The LLM writes answer content using evidence markers or structured claim references. The harness:

1. parses the markers;
2. verifies every marker came from an allowed tool result or durable ref;
3. verifies scope;
4. generates final `[1]`, `[2]` citation numbers;
5. writes the persistent reference registry;
6. writes `referenceMappingsJson` as a render snapshot;
7. emits the final frontend payload.

If the answer claims paper evidence but has no valid evidence refs, the harness rejects it and
requires another tool call or returns failure.

## Answer Envelope

The LLM must output a fixed product answer envelope. It must not freely decide the final Markdown
structure.

Fixed `answerType` values:

```text
EVIDENCE_ANSWER
PRODUCT_STATE
INSUFFICIENT_EVIDENCE
NON_EVIDENCE
CLARIFICATION_NEEDED
```

Envelope:

```json
{
  "answerType": "EVIDENCE_ANSWER",
  "answer": "Answer text with evidence markers.",
  "evidenceBasedClaims": [
    {
      "claim": "A paper-supported claim.",
      "evidenceRefs": ["ev_1"]
    }
  ],
  "stateClaims": [
    {
      "claim": "A product-state claim.",
      "sourceTool": "get_system_state"
    }
  ],
  "limitations": [],
  "nonEvidenceNotes": [],
  "missingFields": [],
  "reason": ""
}
```

Rules:

- `EVIDENCE_ANSWER` must cite valid evidence refs.
- `PRODUCT_STATE` must come from product-state tools such as `get_system_state`, `list_papers`,
  `find_papers`, `resolve_papers`, or `get_paper_metadata` and does not need citations.
- `INSUFFICIENT_EVIDENCE` is allowed only when relevant retrieval or inspection tools succeeded
  but found no adequate evidence.
- `NON_EVIDENCE` is for smalltalk, UI help, or general explanation that explicitly does not rely on
  paper evidence.
- `CLARIFICATION_NEEDED` is allowed without a tool only when missing fields cannot be resolved from
  history, memory, refs, or scope.
- Tool system failure is not `INSUFFICIENT_EVIDENCE`; it is a failed turn.
- LLM output must not include a free-text `Sources used` section.

The harness renders final Markdown and structured frontend blocks from the envelope.

## Function Catalog

First phase exposes exactly these 10 read-only product functions.

### `answer_without_product_state`

Purpose: allow a narrow response that does not depend on product state or paper evidence.

Allowed:

```text
smalltalk
PaperLoom capability explanation
clarification that does not require product state
unsupported non-paper request
```

Forbidden:

```text
paper counts
paper titles
filenames
paper existence
upload/processing state
collection/scope state
paper content
references/pages/citations
```

### `get_system_state`

Purpose: answer product-state and library-state questions.

Returns product semantic state only, not OCR/provider/internal infrastructure details:

```json
{
  "productPaperCount": 0,
  "searchablePaperCount": 0,
  "papersByProcessingStatus": {
    "AVAILABLE": 0,
    "PROCESSING": 0,
    "FAILED": 0,
    "NOT_IN_SCOPE": 0,
    "NOT_VISIBLE": 0
  },
  "indexingPendingCount": 0,
  "currentSessionScope": {},
  "visibleCollectionCount": 0,
  "warnings": []
}
```

Product statuses exposed to LLM/frontend:

```text
AVAILABLE
PROCESSING
FAILED
NOT_IN_SCOPE
NOT_VISIBLE
```

Do not expose:

```text
MinerU
Unlimited-OCR
OCR provider
Elasticsearch internals
Kafka internals
sidecar health
internal parser states
```

### `get_session_scope`

Purpose: explain the locked session evidence universe.

Returns scope mode, lock state, label, source count, status, and constraints. Does not return the
full paper list and does not read chunks.

### `list_papers`

Purpose: list paper-level metadata in the locked session scope.

Input may include pagination, product status, title query, explicit title regex, author, year range,
or sort. `titleQuery` is a plain title or filename substring filter, not semantic topic search.
It does not read chunks and does not create citations.

Use for:

```text
有哪些论文
哪两篇
列出当前范围内的论文
列出标题包含 LoRA 的论文
```

Do not use `list_papers.titleQuery` for recommendation or topic discovery. A query such as
`agent eval` does not have to be a literal title substring and must go through `find_papers`.

### `find_papers`

Purpose: find papers semantically related to a natural-language topic inside the locked session
scope.

Input:

```json
{
  "query": "agent eval",
  "limit": 12
}
```

Use for:

```text
推荐一下和 agent eval 有关的论文
有哪些论文是关于 agent benchmark 的
找一下 RAG evaluation 相关论文
```

Returns:

- opaque `paperRef` values;
- paper title, original filename, score, and match snippet when available;
- `selectionBasis = semantic_paper_search`;
- `citationsAvailable = false`;
- `requiresEvidenceToolForPaperClaims = true`.

The tool may rank by paper-level metadata and searchable text inside the locked product scope, but it
must not expose raw `paperId` values or retrieval knobs. It supports paper selection and
recommendation only. If the answer needs paper-content reasons, methods, experiments, results,
limitations, or comparisons, the LLM must call `retrieve_evidence` using returned opaque `paperRef`
constraints before making those claims.

### `resolve_papers`

Purpose: resolve user mentions into `paperRef` candidates inside locked scope.

Supported resolution inputs:

```text
ordinal such as "second paper"
previous citation/ref mention
title query
explicit title regex
author/year constraints
original filename mention
opaque paperRef
userMention text
```

If ambiguous, return candidates and require clarification. Do not guess.

### `get_paper_metadata`

Purpose: return bibliographic and product metadata for `paperRef` values.

Does not read content chunks and does not create citations. For methods, experiments, limitations,
tables, figures, formulas, or paper claims, use `retrieve_evidence`.

### `retrieve_evidence`

Purpose: retrieve citeable paper evidence for content questions.

Input is product semantic:

```json
{
  "question": "LoRA 的核心方法、实验和局限是什么？",
  "subQuestions": [
    "LoRA 的核心方法是什么？",
    "LoRA 的实验结果是什么？",
    "LoRA 的局限是什么？"
  ],
  "paperConstraints": [
    { "paperRef": "paper_1" }
  ]
}
```

Allowed:

- natural-language `question`;
- natural-language `subQuestions`;
- `paperRef` constraints;
- semantic constraints inside locked scope.

Forbidden:

- `topK`;
- `searchMode`;
- rerank flags;
- page-window knobs;
- ES query;
- raw `paperId`.

The tool internally decides hybrid retrieval, query fan-out, deduplication, page-window expansion,
reranking, and diversity. It returns evidence refs that are sufficient for routine answers without
requiring `inspect_page` every time.

### `inspect_reference`

Purpose: inspect a durable `citationRef` or `evidenceRef`.

Uses the persistent reference registry. Does not perform unrelated retrieval if the ref cannot be
resolved.

### `inspect_page`

Purpose: inspect known page context from a `pageRef` or `paperRef + pageNumber`.

Use for explicit page requests, citation follow-up, table/figure context, or when retrieved snippets
are insufficient. It does not broaden scope.

## Functions Not In The Catalog

Do not expose these to the Product ReAct Harness in phase one:

```text
create_collection
edit_collection
delete_paper
rerun_ocr
change_scope
run_benchmark
raw_search
sql_query
es_query
set_retrieval_params
compare_papers
discover_papers
```

`discover_papers` is a legacy/non-product function name and must not be exposed to the Product
ReAct Harness. The product semantic discovery capability is `find_papers`, which enforces locked
scope, reference-registry persistence, and product-boundary sanitization.

Paper comparison does not need a first-phase `compare_papers` tool. The LLM should compose
`resolve_papers` and `retrieve_evidence` with natural-language subquestions.

## ReAct Loop

First phase loop limit:

```text
maxReActRounds = 6
```

Tools may perform internal batch retrieval. The LLM does not control low-level retrieval budgets.

If the limit is reached, the harness stops and returns:

```text
stopReason = MAX_REACT_ROUNDS
```

The answer should tell the user the question needs to be narrowed or continued in a new turn. Do
not fallback to guessing.

First-step behavior:

- Product state questions must call product-state tools.
- Paper content questions must call retrieval or inspection tools.
- Smalltalk and unrelated help may call `answer_without_product_state`.
- Narrow `CLARIFICATION_NEEDED` may be produced only when history, memory, refs, and scope are
  insufficient to resolve the missing fields.

## Tool Failure Semantics

Tool failure and no evidence are different.

```text
tool system failure -> FAILED
tool succeeds but returns no evidence -> INSUFFICIENT_EVIDENCE
```

Examples:

- `get_system_state` fails: cannot produce `PRODUCT_STATE`.
- `retrieve_evidence` fails: cannot answer paper content.
- `retrieve_evidence` succeeds with no usable evidence: return `INSUFFICIENT_EVIDENCE`.
- `inspect_reference` fails because a ref is invalid: explain that the ref is unavailable or fail,
  but do not invent a replacement.

## Frontend Protocol

The frontend and WebSocket protocol may change to match this contract. Do not preserve old payloads
if they make the backend design unclear.

Client to server:

```json
{
  "type": "user_message",
  "conversationId": 123,
  "generationId": "...",
  "message": "..."
}
```

Server to client progress event:

```json
{
  "type": "calling_tool",
  "toolName": "retrieve_evidence"
}
```

The UI should display only:

```text
calling retrieve_evidence
```

Do not display tool arguments, tool results, raw prompts, raw LLM responses, hidden reasoning, SQL,
or Elasticsearch details.

Final event:

```json
{
  "type": "final_answer",
  "conversationId": 123,
  "generationId": "...",
  "answerMarkdown": "...",
  "answerEnvelope": {},
  "references": [],
  "stopReason": "COMPLETED",
  "resultStatus": "COMPLETED"
}
```

Frontend evidence display:

- No free-text `Sources used` block.
- Show a structured references panel only when references exist.
- If there are no references, do not show a sources area.
- Citation click and follow-up use the persistent reference registry, not Redis or trace.

## Disk Trace

Trace phase one is record-only. It is not used for optimization, benchmark comparison, UI analysis,
or business logic.

Trace is stored on disk, not in business tables.

Recommended path:

```text
data/traces/product-react/
  conversation-<conversationId>/
    turn-<generationId>.json
```

Business tables do not store trace ids or trace paths. Trace files contain their own correlation
keys:

```json
{
  "traceVersion": 1,
  "conversationId": 123,
  "generationId": "...",
  "userId": 1,
  "scopeSnapshot": {},
  "startedAt": "...",
  "finishedAt": "...",
  "input": {
    "userMessage": "...",
    "historyMessageCount": 12,
    "memory": {}
  },
  "llmCalls": [],
  "toolCalls": [],
  "retrievals": [],
  "answerEnvelope": {},
  "references": [],
  "stopReason": "COMPLETED",
  "errors": []
}
```

Trace should record:

- full prompt messages;
- tool catalog JSON;
- raw LLM responses;
- tool call arguments;
- tool results;
- retrieval diagnostics;
- memory compression calls;
- errors and latency where available.

Trace write failure does not block an otherwise completed answer, but the turn must return a visible
`DEGRADED` status. It must not be silently successful.

## New Session Scope UI

The chat UI must provide scope choice before a session starts:

```text
All papers
Collection
Match papers by title
```

Title match supports:

- plain title query;
- explicit regex mode;
- preview of matched paper count/list;
- confirm to create immutable source set snapshot.

Collection phase one only needs:

- list visible collections;
- choose a collection for session scope;
- enforce the resulting snapshot in all tools.

Do not show collection management controls unless create/edit/delete are actually implemented.

## Required Flows

Library status:

```text
user: 现在有多少论文可以检索
LLM calls get_system_state
assistant returns PRODUCT_STATE, no references
```

Paper list follow-up:

```text
user: 哪两篇
LLM sees full history and calls list_papers
assistant returns PRODUCT_STATE or NON_EVIDENCE list, no references
```

Paper recommendation/discovery:

```text
user: 推荐一下和 agent eval 有关的论文
LLM calls find_papers
assistant returns PRODUCT_STATE list/recommendation, no citations
LLM calls retrieve_evidence only if it makes paper-content claims
```

Paper content:

```text
user: 第二篇主要讲什么
LLM calls resolve_papers
LLM calls retrieve_evidence
assistant returns EVIDENCE_ANSWER with validated citations
```

Insufficient evidence:

```text
LLM calls retrieve_evidence
tool succeeds with no usable evidence
assistant returns INSUFFICIENT_EVIDENCE
```

Reference follow-up:

```text
user: 打开引用 2
LLM calls inspect_reference(citationRef)
assistant answers from registry-backed reference data
```

## Required Tests

- Product chat path calls `ProductReActHarness`, not the legacy router/planner split.
- Every turn exposes the 10-function catalog.
- Product state question calls `get_system_state` or `list_papers`, not chunk retrieval.
- Product recommendation or topic discovery calls `find_papers`, not `list_papers.titleQuery`.
- `retrieve_evidence` accepts natural-language subquestions and opaque paper refs only.
- Raw `paperId`, SQL id, chunk id, ES id, and retrieval knobs are rejected from public tool input.
- `answer_without_product_state` cannot include product state or paper evidence.
- `CLARIFICATION_NEEDED` includes missing fields and reason.
- `EVIDENCE_ANSWER` without valid evidence refs is rejected.
- Final citation numbers are generated by the harness.
- Reference registry and render snapshot are persisted before returning citation-bearing answers.
- History reload can inspect previous citations from the reference registry.
- Memory compression persists structured memory and is traced.
- Memory does not override scope, refs, product tools, or evidence.
- Trace files are written to disk and ignored by Git.
- Trace write failure returns `DEGRADED`.
- Tool failure returns failed status rather than `INSUFFICIENT_EVIDENCE`.
- New-session UI can create All, Collection, and title-match snapshot scopes.
- Uploaded papers do not enter already locked session scope.
