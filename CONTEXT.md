# PaperLoom

PaperLoom is a research-paper RAG product. Its domain language separates the product paper library
from benchmark corpora so product behavior is not confused with eval data.

## Language

**Product Paper Library**:
The user's permissioned collection of research paper PDFs that have entered the product upload,
parsing, indexing, and evidence pipeline.
_Avoid_: benchmark library, structured import library, eval paper library

**Product Paper**:
A research paper PDF stored in the product data domain. It may be searchable only after the product
parser/indexer has produced text chunks and evidence metadata.
_Avoid_: eval paper, structured import, benchmark record

**Eval Corpus**:
A benchmark-only dataset such as LitSearch or QASPER, stored outside the product data domain and
used only by evaluation harnesses.
_Avoid_: product paper library, uploaded papers

**Retrieval Corpus**:
The explicit data domain selected for a retrieval run, such as `PRODUCT_LIBRARY`,
`EVAL_LITSEARCH`, or `EVAL_QASPER`.
_Avoid_: includeEval flag, mixed corpus

**PDF Evidence Readiness**:
The product-facing state describing which PDF-derived evidence assets exist for a product paper,
such as text chunks, page screenshots, table crops, figure crops, and parser artifacts.
_Avoid_: eval import readiness, structured import readiness

**Trace Artifact**:
A JSON file written by the product runtime that records what happened during a Product ReAct turn or
memory compression call. It is offline eval input, not business state.
_Avoid_: eval result, business audit row, chat status, trace database record

**Append-Only Trace Artifact**:
A trace artifact that is written as a new JSON file and not modified later. Related artifacts, such
as a turn trace and its memory compression trace, are grouped offline by conversation and generation
ids instead of being merged in place.
_Avoid_: trace append, JSON patch, read-modify-write trace update

**Trace Drop**:
The intentional loss of a trace artifact when the asynchronous trace queue is full or the trace sink
cannot accept new work. Trace drop is logged but does not affect the product answer.
_Avoid_: business failure, degraded chat turn, retryable user error

**Raw Trace Data**:
Unredacted trace content such as user messages, prompts, model responses, tool arguments, tool
results, memory, diagnostics, and answer envelopes. It is allowed only for first-phase local trace
artifacts and requires a separate productionization decision before broader use.
_Avoid_: sanitized analytics, user-visible logs, production-safe telemetry

**Offline Eval**:
A separate future system or script that reads trace artifacts to analyze harness, LLM, and tool-call
quality. It is not part of the live product request path.
_Avoid_: online eval, product scoring, chat-time evaluation
