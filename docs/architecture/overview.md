# Architecture Overview

PaperLoom is an evidence-bounded agentic RAG system. Its central invariant is that a paper-content
claim must remain connected to evidence that was read from a paper the current conversation was
authorized to use.

## Live Runtime Boundary

[![Rendered PaperLoom system architecture](../../site/public/images/paperloom-system-architecture.png)](../../site/public/images/paperloom-system-architecture.svg)

The diagram describes the live assistant-answer path and the Reading Model ingestion path. It is
not an inventory of every service or experimental subsystem in the repository.

The Spring-selected research path is:

```text
ChatHandler
-> ProductReadingConversationService
-> PythonResearchHarnessClient
-> POST /v1/research/stream
-> ResearchHarnessService
-> OpenAI Agents SDK Runner
```

`ProductReadingConversationService` is constructed with `PythonResearchHarnessClient` in the live
Spring application. Maintained architecture documentation follows that selected runtime.

## Responsibility Split

### Folio

The Vue application presents the paper library, source selection, streamed research progress,
conversations, and evidence reopening. Product state such as a paper choice or a reference target is
carried as structured data rather than inferred from assistant prose.

### Java Services

Java owns the hard product boundary:

- authentication and paper authorization;
- the conversation's locked paper scope;
- quota reservation, settlement, cancellation, and WebSocket/API integration;
- durable conversation history and research memory exchange;
- conversion of Python citations into product reference mappings;
- Current Reading Model indexing, Qdrant retrieval, and canonical MySQL reads;
- historical reference resolution and evidence reopening.

The harness does not discover its own global paper scope. `ChatHandler` computes the authorized
paper IDs and sends that fixed set with every research turn.

### `harness_py`

Python owns the research behavior inside the already-authorized scope:

- calling the narrow Java Corpus API inside the locked scope;
- creating the Agent, Session, Context, tools, and Agents SDK Runner;
- choosing when to discover papers, retrieve locations, and read exact content;
- the Evidence Ledger and Candidate / Read / Cited coverage state;
- deterministic final-answer and citation validation;
- optional per-run evaluation capture.

The Agents SDK supplies the generic model-tool loop. `ReadingCorpusTools`,
`answer_validation_error`, and `evaluate_evidence_coverage` decide what the model may search, read,
cite, and submit.

## Runtime Assembly For One Turn

`ResearchHarnessService` validates `user_id` and `scope.paper_ids`, then loads only paper metadata
through the Java Corpus API. Reading Elements are not copied into the Harness.
`LiveResearchChatHarness` creates the run ID, applies the scope again, opens optional Eval Capture, and
builds an immutable `TurnExecutionInput`.

`AgentsSdkHarnessRuntime` then creates one request-local instance of each runtime object:

| Object | Role |
| --- | --- |
| `ResearchRunContext` | Mutable tool state, trace, model-call association, token and latency totals |
| `ReadingCorpusTools` | Stable tool schemas, paper/location disclosure, exact-read gating, Evidence IDs |
| `RequestBackedSession` | Text history for this SDK Run; not the durable conversation database |
| Provider model adapter | Chat Completions or Responses transport and HTTP Capture |
| `Agent` | Instructions, tools, model settings, and tool-use behavior |
| SDK `Runner` | Repeats model and tool calls until an accepted submission or failure |

The model is required to use Function Tools. Tool execution is serialized because authorization state
can change between calls. `submit_research_answer` ends the Run only when it is the sole call in its
model step and both answer and evidence coverage validation accept it.

Java stores the durable conversation and reference mappings. Python's `ResearchRunContext` is released
after the turn. Eval files remain offline artifacts and are never read to drive a product answer.

## Live Corpus And Retrieval

For each turn, Python receives `user_id` and `scope.paper_ids` from Java. A request-bound
`JavaCorpusGatewayReader` uses one reusable HTTP client to call the Java data plane:

1. Paper discovery and identity lookup query authorized product metadata.
2. Java embeds the location query and asks Qdrant for dense and sparse candidates inside the scope.
3. Java fuses ranks deterministically, checks the current READY model, and hydrates previews from MySQL.
4. Search returns non-citeable previews and stable `location_ref` values.
5. `read_locations` revalidates scope/current model and reads exact canonical MySQL content.
6. Python creates Evidence IDs only after that exact read.

Qdrant is a rebuildable candidate index, not an evidence source. Golden fixtures and offline audits
still use the in-memory BM25 adapter without Java, Qdrant, or provider calls.

## Tool Authorization Ladder

The tool protocol narrows authority as research proceeds:

```text
Java-authorized paper scope
-> paper disclosed by candidate search or identity lookup
-> location disclosed by location search
-> exact location read
-> Evidence ID recorded
-> submitted answer validated against known evidence
```

A model cannot cite a Candidate Preview, read a location it was not shown, or finish with an unknown
Evidence ID. `submit_research_answer` must also be the only tool call in its final model step.

See [Reading Model and Agent Tools](reading-model-and-agent-tools.md) for the data model and each tool's
role.

## Reading Model Ingestion

The ingestion lane is separate from the research turn:

1. A research-paper PDF and its metadata enter the Java product boundary.
2. MinerU produces structured parser output and parser artifacts.
3. `PaperReadingModelBuilder` converts that parser-specific output into PaperLoom-owned pages,
   sections, typed elements, locations, relationships, provenance, and diagnostics.
4. MySQL stores the canonical Reading Model and durable product state.
5. MinIO stores the original PDF, parser artifacts, page screenshots, and table or figure crops.

Parser artifacts are retained for reproducibility and debugging. They are not searched directly and
do not replace the product-owned Reading Model.

## Durable Data

| Store | Live-path responsibility |
| --- | --- |
| MySQL | Users, access rules, papers, Reading Models, conversations, research memory, and persistent reference data; direct source of the Python corpus projection |
| MinIO | Original PDFs, parser artifacts, page screenshots, and visual evidence crops |

The repository also maintains independent indexing, upload-processing, and search infrastructure.
Those services may still receive writes or serve standalone endpoints, but they do not contribute to
current assistant answers and must not be confused with the live agentic RAG path.

## Evaluation Boundary

Product papers and evaluation corpora are separate domains. Golden cases may exercise the same
research tools, but benchmark papers do not become part of a user's library or default paper scope.
Saved runs are offline measurement artifacts, not product authorization or answer state.

See [ADR 0001](../adr/0001-separate-product-papers-from-eval-corpora.md) and the
[Evaluation System](../evaluation/README.md).

## Deeper Reading

- [Reading Model and Agent Tools](reading-model-and-agent-tools.md)
- [Evidence and Citations](evidence-and-citations.md)
- [Product Requirements](../reference/product-requirements.md)
- [Research Harness Guide](../../harness_py/README.md)
- [Architecture Decision Records](../adr/)
