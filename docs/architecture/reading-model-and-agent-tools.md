# Reading Model And `harness_py` Tools

PaperLoom has one durable Reading Model. In the product runtime, Java indexes its canonical locations
into Qdrant and exposes a narrow Corpus API; Python keeps the model-visible tool contract and request
authorization state. Golden fixtures use the same tools through an in-memory adapter.

The durable model remains richer than the current Python retrieval surface. Retrieval can change
without redefining paper identity, source locations, or persisted references.

## Reading Model Composition

### `paper_reading_models`: Version And Readiness

One row describes one built model version for a paper. It stores:

- `model_version`, `model_status`, and `is_current`;
- parser name and version;
- page, readable-page, and readable-character counts;
- failure reason and structured diagnostics.

The live product corpus selects the current `READING_MODEL_READY` row. Readiness is an explicit
product state, not an inference from an index document or parser file.

### `paper_pages`: Physical Page Surface

Page rows preserve the PDF's physical page number, page text, text hash, character count, text
status, source span, parser provenance, and access metadata. Textless or parser-missing pages remain
representable instead of disappearing from the paper model.

### `paper_sections`: Readable Structure

Section rows model title, level, page range, reading-order range, display order, section text, and
source span. They provide a paper-level structural view independent of how retrieval later chunks or
ranks content.

### `paper_reading_elements`: Canonical Typed Content

Reading Elements are the primary live retrieval surface. The builder retains parser objects as
typed product content, including:

- heading, paragraph, list, table, image, figure, chart, and formula;
- footnote, aside, and code;
- page number, reading order, and section title;
- caption, body, and searchable text;
- bounding box and source span;
- structured payload and raw parser attributes;
- parser element and source-object identities;
- parent element, attachment role, and association status.

`body_text` and `caption_text` preserve readable source material. `searchable_text` is a retrieval
projection that may combine them; evidence still carries the exact retained element text rather
than a generated summary.

### `paper_locations`: Navigation Identity

Locations provide formal refs for pages, sections, tables, and suitable figures. They store the
paper, model version, location type, page range, section, source object, display order, content kind,
and source span.

Some current Reading Elements use `reading_element_id` as a fallback `location_ref` when no separate
formal location was created. This lets the live harness read retained content without pretending
that every parser element already has a perfect high-level navigation object.

### `paper_visual_assets`: Inspectable Visual Evidence

Visual assets record the original PDF page screenshots and table, figure, chart, or parser-image
assets. Each row can retain availability or failure state, owning Reading Element, page, source
object, parser path, bbox, object key, content type, size, checksum, and failure reason.

The live harness loads asset availability into evidence metadata. The actual binary objects remain
in MinIO and are reopened through the product boundary.

## Builder Semantics

`PaperReadingModelBuilder` does more than copy parser JSON into tables:

- it preserves physical page number and reading order;
- it maps parser types into product types while retaining unknown or raw attributes;
- it associates table-caption fragments and figure panel labels with parent elements;
- it distinguishes full captions from panel-only caption fragments;
- it records parent, attachment role, and association status;
- it marks ambiguous or unattached relationships instead of inventing ownership;
- it creates page, section, table, and suitable figure locations;
- it stores parser provenance, bbox, source spans, and structured typed payloads;
- it emits diagnostics for retained elements, deferred locations, ambiguous associations, and
  incomplete visual evidence.

This is why the Reading Model is a product model rather than a thin parser cache.

## Full Model Versus Live Projection

The complete Reading Model and the current Python corpus are intentionally not identical:

| Capability | Durable Reading Model | Current live Python projection |
| --- | --- | --- |
| Model metadata and readiness | Yes | Yes |
| Physical page rows | Yes | Not directly loaded |
| Section rows | Yes | Not directly loaded |
| Typed Reading Elements | Yes | Yes, primary retrieval surface |
| Formal location rows | Yes | Location fields carried from elements; separate table not directly loaded |
| Visual asset records | Yes | Availability flags only |
| Vector or embedding representation | Not required for canonical truth | Not used |

This distinction prevents documentation from claiming that a stored capability already participates
in the live answer path.

## How The Tools Are Assembled

`AgentsSdkHarnessRuntime` creates a fresh `ResearchRunContext`, `ReadingCorpusTools`, model adapter,
`RequestBackedSession`, `Agent`, and SDK `Runner` for every turn. `build_agent_tools` combines three
sources:

- `ResearchSkillRegistry.tool_definition()`;
- `ReadingCorpusTools.definitions()`;
- `final_answer_tool_definition()` for `submit_research_answer`.

The provider compatibility tool `_continue_research_turn` is also added when the model adapter needs
to convert a text-only response back into the required submission flow. It is not a product research
capability.

The SDK is configured with `tool_choice="required"` and `reset_tool_choice=False`. A model may emit
several Function Calls in one response, but `max_function_tool_concurrency=1` executes them serially.
That ordering matters because one call may disclose a paper or location required by the next call.

## Request-local State

`ReadingCorpusTools` owns the state that determines whether a call is allowed:

| Field | Meaning |
| --- | --- |
| `authorized_paper_ids` | Papers returned by candidate search or a unique identity match, within the dataset already limited by Java `scope.paper_ids` |
| `disclosed_location_refs` | Location refs returned by `find_reading_locations` |
| `observations_by_evidence_id` | Exact reads created by `read_locations`; the current run's Evidence Ledger |

The model cannot claim that it already opened a paper or location. Tool code checks these sets and
records snapshots before and after each call.

Corpus tools first produce an internal result. `model_facing_payload` removes evaluation-only fields
before the result is returned to the model. Eval Capture can store both the internal and model-visible
forms, which makes it possible to reconstruct what the model actually saw without exposing Golden
Anchor data during a run.

## Tools Available To The Model

| Tool | Required state | State change | Citeable paper-content evidence |
| --- | --- | --- | --- |
| `get_research_skill` | None | Adds the selected ID to `skills_used` | No |
| `search_paper_candidates` | Dataset already limited by Java scope | Adds returned papers to `authorized_paper_ids` | No; cards support metadata answers only |
| `find_papers_by_identity` | Dataset already limited by Java scope | Adds one uniquely resolved paper to `authorized_paper_ids` | No |
| `find_reading_locations` | Every requested paper is in `authorized_paper_ids` | Adds returned refs to `disclosed_location_refs` | No |
| `read_locations` | Every requested ref is in `disclosed_location_refs` | Adds exact reads to `observations_by_evidence_id` | Yes |
| `submit_research_answer` | Known evidence or a non-complete outcome | Ends the Runner only after validation accepts the draft | It may cite known Evidence IDs but creates none |

## Tool Behavior

### `search_paper_candidates`

Searches or browses paper metadata inside the fixed corpus. It can use title, abstract, authors,
venue, year, filename, identifiers, explicit paper IDs, filters, offset, and limit. Results disclose
papers for later reading but are not citeable paper-content evidence.

### `find_papers_by_identity`

Resolves a specific paper from structured identity hints. One unique match discloses the paper;
multiple matches return ambiguity rather than choosing one silently. It is not a topical search or
recommendation tool.

### `find_reading_locations`

Searches disclosed papers and returns `location_ref` values plus non-citeable previews. The product
adapter uses Qdrant dense/sparse retrieval, deterministic rank fusion, Current Model validation, and
bounded MySQL hydration. The in-memory fixture adapter combines:

- BM25 over full element text;
- BM25 over leading tokens and section text;
- exact-phrase and section-hint boosts;
- adjacent-paragraph support;
- broad-query and multi-paper candidate expansion;
- query-term coverage selection.

Passage windows and page-grounding candidates are enabled only when the dataset supplies a physical
page projection. The current product DB projection is Reading Element-led and does not load page rows
as an independent retrieval surface.

The product path never treats Qdrant payload text as Evidence. It exposes only candidates; exact
content still comes from the Current Reading Model in MySQL.

### `read_locations`

Reads exact content only from locations previously disclosed by `find_reading_locations`. It is the
only live content tool that creates Evidence IDs and enters items into the Evidence Ledger.

### `get_research_skill`

Returns one of 22 methodology guides from `ResearchSkillRegistry`. Each guide contains usage
conditions, steps, an evidence standard, and answer guidance. It can affect the model's strategy but
cannot widen paper authorization, disclose a location, or create evidence.

### `submit_research_answer`

Submits `outcome`, `markdown`, and optional `fields`. It must be the only tool call in the final model
step. `answer_validation_error` checks the draft shape, citation syntax, known IDs, and basic citation
requirements. `evaluate_evidence_coverage` checks Candidate, Read, Cited, and substantive evidence for
papers discussed in the answer.

Rejected submissions return structured feedback to the same Runner. An accepted result is the only
tool output that `tools_to_final_output` treats as final.

## Cross-turn Memory

The SDK Session is request-local. Java persists conversation history and reference mappings, then
sends a compact history and previously accepted evidence with the next turn. `ConversationState`
promotes evidence into research memory only when the accepted answer cited it. Search candidates,
previews, and uncited reads do not automatically become durable conversation state.

## Why This Is Agentic RAG

Retrieval is not one fixed query followed by one generation call. The model can decide to browse
papers, resolve an identity, reformulate a location search, read several locations, consult a
research skill, answer partially, ask for clarification, or abstain. Those choices happen inside one
Agents SDK loop.

The system remains bounded because authorization, disclosure, evidence creation, coverage, citation,
and final submission are deterministic tool and validator rules outside the model's discretion.

## Future Retrieval Work

Dense retrieval can be added later without replacing the Reading Model. A future implementation can
derive embeddings from canonical Reading Elements, fuse lexical and dense candidates, or train a
reranker from saved behavior. Promotion should depend on held-out Candidate / Read / Cited / Hard
Pass improvements, latency, cost, and failure analysis rather than on vector-search availability
alone.
