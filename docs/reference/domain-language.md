# PaperLoom Domain Language

This glossary defines the canonical terms used by maintained product, architecture, harness, and
evaluation documentation. Historical records may preserve older vocabulary when quoting the design
that existed at that time.

## Product

**PaperLoom**<br>
The repository and evidence-bounded agentic RAG system for research-paper reading.

**Folio**<br>
The user-facing Vue research workbench: paper library, source selection, conversations, research
progress, and evidence inspection.

**Product Paper**<br>
A research-paper PDF stored in the product library with product ownership and permission metadata.
Evaluation-only papers are not Product Papers.

**Authorized Paper Scope**<br>
The exact set of Product Paper IDs Java permits one research turn to use. Python treats this set as
input authority and cannot widen it.

**Locked Conversation Scope**<br>
The effective Authorized Paper Scope fixed for a conversation by the product boundary. It prevents a
follow-up from silently searching unrelated papers.

**Chat Turn Target**<br>
The durable conversation that one outgoing user message will append to. It is server-authoritative
and must not be inferred only from the currently visible client tab.

**Reading Turn Anchor**<br>
A structured current-turn input created by a user action, such as clicking a paper, location, or
historical reference. It guides navigation but does not itself contain citeable paper content.

## Reading Model

**Parser Artifact**<br>
Raw MinerU output such as structured JSON, Markdown, images, and archives retained for
reproducibility and debugging. It is not the product's canonical paper model or answer evidence.

**PaperLoom Reading Model**<br>
The product-owned, versioned representation of a PDF: model status, pages, sections, Reading
Elements, locations, relationships, source spans, provenance, diagnostics, and visual assets.

**Current Ready Reading Model**<br>
The current `paper_reading_models` version whose `model_status` is `READING_MODEL_READY`. The live
Python corpus selects this model rather than inferring readiness from an external index.

**Physical Page**<br>
A numbered page surface in the source PDF. A page can exist even when it is textless or parser text is
missing.

**Paper Section**<br>
A readable structural range with title, level, physical page range, reading-order range, section
text, and source span.

**Reading Element**<br>
A canonical retained content object such as a heading, paragraph, list, table, image, figure, chart,
formula, footnote, aside, or code block. Reading Elements are the primary live retrieval surface.

**Parent Association**<br>
The explicit relationship from a Reading Element to a parent table or figure, with an attachment
role and association status. Ambiguous or unattached content remains marked as such instead of being
assigned speculatively.

**Body Text**<br>
Readable source content retained for a Reading Element.

**Caption Text**<br>
Readable caption content retained separately from the element body.

**Searchable Text**<br>
The retrieval-oriented text projection for a Reading Element. It may combine caption and body text,
but it does not replace the retained source content used as evidence.

**Source Span**<br>
Structured provenance connecting a page, section, element, or location back to its source paper
surface and parser output.

**Location Ref**<br>
A stable opaque navigation identity for a page, section, table, figure, or retained Reading Element.
The live harness uses it to separate location discovery from exact reading.

**Visual Asset**<br>
A page screenshot, table crop, figure crop, chart crop, or parser image, including explicit
availability or failure state and its relationship to the paper and Reading Element.

## Live Corpus And Retrieval

**Live Corpus Projection**<br>
The request-local Python dataset loaded directly from MySQL for the Authorized Paper Scope. It
currently contains paper metadata, current ready-model metadata, Reading Elements, and visual-asset
availability. It is narrower than the complete Reading Model.

**Agentic RAG**<br>
A retrieval-augmented research loop in which the model can choose and repeat bounded discovery,
search, reading, skill, and submission tools. Deterministic authorization and evidence validators
remain outside the model.

**Paper Candidate**<br>
A paper card returned by metadata discovery or identity resolution. It discloses a paper for later
location search but does not support paper-content claims.

**Navigation Preview**<br>
A short non-citeable text fragment returned to help the Agent choose a location to read. It is not
Evidence.

**In-Memory BM25**<br>
The current lexical location-ranking method applied inside Python to Reading Element text. It is
combined with deterministic passage, lead, section, phrase, adjacency, and coverage heuristics.

**Dense Retrieval**<br>
A future embedding-based candidate method. It is not part of the current assistant answer path and
must pass evaluation gates before promotion.

## Agent Tools

**Agent Tool Protocol**<br>
The ordered disclosure and validation rules governing how the Agents SDK model can access the live
corpus. The model may choose actions, but it cannot bypass the protocol.

**`search_paper_candidates`**<br>
Discovers or browses papers from metadata inside the fixed corpus and discloses returned papers.

**`find_papers_by_identity`**<br>
Resolves a specific paper from structured identity hints. One match discloses the paper; multiple
matches remain ambiguous.

**`find_reading_locations`**<br>
Ranks locations inside disclosed papers and returns Location Refs plus Navigation Previews. It does
not create Evidence.

**`read_locations`**<br>
Reads exact content from previously disclosed Location Refs. It is the only live content tool that
creates citeable Evidence IDs.

**`get_research_skill`**<br>
Returns optional research-method guidance. It cannot disclose papers, disclose locations, or create
Evidence.

**`submit_research_answer`**<br>
Submits the final outcome and answer through deterministic validation. It must be the only tool call
in the final model step.

**Authorization Ladder**<br>
The sequence `Authorized Paper Scope -> disclosed paper -> disclosed location -> exact read ->
Evidence ID -> accepted final submission`.

## Evidence And References

**Evidence ID**<br>
A deterministic `ev_...` identity created when exact retained content is read. It binds paper,
location, element type, and page identity to an Evidence Ledger item.

**Evidence Ledger**<br>
The run record of exact evidence the Agent read, including source text, paper and location metadata,
provenance, and visual-asset availability.

**Known Evidence**<br>
Evidence available to final validation from the current run plus explicitly retained evidence from
prior conversation turns.

**Citeable Evidence**<br>
Known Evidence that is not rejected or navigation-only and can support a paper-content claim.

**Substantive Evidence**<br>
Citeable content stronger than a heading-only navigation item. Coverage validation requires
substantive evidence for papers claimed in an answered or partial response.

**Persistent Reference Mapping**<br>
Java's durable mapping from a rendered answer reference to its Evidence ID, paper, location, source
span, and asset metadata. It lets a historical conversation reopen evidence after transient runtime
state has disappeared.

**Historical Reference Reopen**<br>
Resolving a persisted reference back to paper evidence while rechecking the current user's paper
permission.

## Outcomes And Evaluation

**Research Outcome**<br>
One of `answered`, `needs_clarification`, `partial`, or `abstained`. A technical failure is a runtime
status, not a research choice.

**Candidate Coverage**<br>
Whether required paper locations appeared in location-search results.

**Read Coverage**<br>
Whether the Agent opened the required source locations and created Evidence.

**Cited Coverage**<br>
Whether the final answer cited Evidence from the required papers.

**Hard Pass**<br>
The aggregate result after outcome, retrieval, content, grounding, citation, and trace obligations
are checked.

**Golden Case**<br>
An evidence-first evaluation case that can define messages, paper pack, required or forbidden papers
and evidence, expected facts, claims, outcome, citation policy, answer contract, and trace
obligations without requiring one exact prose answer.

**Eval Dump**<br>
Optional sensitive per-run capture consisting of append-only `events.jsonl` and atomically written
`result.json`.

**Observable Tool Trajectory**<br>
The sequence of model-visible tool calls, arguments, results, corrections, and accepted final output.
It can support evaluation or distillation without storing or training on hidden chain-of-thought.

**Judge Calibration**<br>
Measuring an LLM judge against human labels and deterministic protocol checks. A judge supplements
but does not replace hard authorization, evidence, and citation validators.

## Infrastructure Boundary

**Live Assistant Path**<br>
The Java-to-Python path that currently produces assistant answers from an authorized MySQL Reading
Model projection and in-memory lexical retrieval.

**Independent Infrastructure**<br>
Repository services that may support uploads, indexing, standalone search endpoints, experiments,
or legacy behavior but do not contribute evidence to the Live Assistant Path. Maintained project
introductions and live-path diagrams do not present them as active RAG components.
