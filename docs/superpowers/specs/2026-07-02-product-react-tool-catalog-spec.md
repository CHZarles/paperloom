# Product ReAct Tool Catalog Spec

Date: 2026-07-02

Status: user-confirmed revised design target. This spec formally supersedes the
previous LLM-facing Product ReAct function-catalog contract and the earlier
seven-tool draft.

## Supersedes

This document is the authoritative LLM-facing Product ReAct tool-catalog
contract for future implementation work.

It supersedes the prior 10-function LLM-facing catalog described in:

- `docs/PAPERLOOM_REACT_FUNCTION_DESIGN.md`
- the Product ReAct catalog subsection of
  `docs/PAPERLOOM_PRODUCT_REQUIREMENTS.md`

It also supersedes the earlier seven-tool draft that used overloaded tools such
as `inspect_session`, `inspect_catalog`, `select_papers`, `inspect_paper`,
`select_targets`, and `inspect_source`.

The supersession is limited to the LLM-facing Product ReAct tool catalog, tool
schemas, prompt rules, and source-quote support model. Existing product
requirements for paper-only product scope, eval separation, fixed conversation
search scope, permission filtering, persistent reference mappings, citation
ownership, final-answer validation, and disk-only trace boundaries still apply
unless this spec explicitly replaces them.

Before coding against this spec, update or annotate any old Product ReAct docs
that still present the 10-function catalog or the seven-tool draft as current.

## Goal

Redesign the PaperLoom Product ReAct harness so the LLM receives small,
single-capability paper-reading tools whose names describe the action to take.

The tool path should expose the auditable reading workflow:

```text
get session state when needed
list, search, or resolve paper candidates
get paper outlines for explicitly chosen papers
list paper locations when exact refs are needed
locate candidate reading locations when useful
read explicitly chosen locations
answer with verified `sourceQuoteRef` values
```

This replaces broad or black-box tools such as:

```text
find_papers
retrieve_evidence
resolve_papers
filter_papers
plan_reading
answer_without_product_state
inspect_catalog
inspect_source
select_papers
select_targets
```

The product must remain a source-quote-backed research-paper RAG system. Tools
may help the model find papers, locate where to read, and read selected
locations,
but final paper-content claims must be grounded in `sourceQuoteRef` values.

## Non-Goals

This spec does not implement the code.

Do not add:

- product-facing benchmark UI
- eval tables in product business schema
- web-scale search
- fallback answer paths
- hardcoded semantic phrase matching
- unbounded natural-language answer or source-quote retrieval query fields
- tool-side semantic recommendation decisions
- tool-side semantic Source Quote sufficiency judgments
- direct exposure of SQL, Elasticsearch query DSL, vector `topK`, rerank knobs,
  or raw `paperId`

Bounded natural-language fields are allowed only where this spec explicitly
defines them:

- `search_paper_candidates.searchNeed` for paper-level candidate search inside
  the fixed conversation search scope
- `find_reading_locations.readingNeed` for in-paper reading location inside
  explicitly chosen papers

Those fields are locate/search aids. They must not cause tools to return final
answers, Source Quotes, recommendation verdicts, or semantic sufficiency
judgments.

This spec does not choose a final PageIndex implementation. PageIndex-style
structured indexes may be used internally, but the LLM-facing catalog must stay
stable and product-level.

## Core Principles

### Tool Names Describe One Capability

Each LLM-facing tool should expose one original product capability. Do not hide
multiple unrelated capabilities behind generic names such as `inspect_catalog`
plus a `source.type` switch.

The LLM should be able to choose the next tool from the action name:

```text
Need current search scope label/readable paper count? use get_session_state.
Need browse/filter papers? use list_papers.
Need topic/need paper candidates? use search_paper_candidates.
Need a specific paper by title/file/DOI/arXiv? use find_papers_by_identity.
Need selected paper structure? use get_paper_outline.
Need deterministic section/page/table/figure refs? use list_paper_locations.
Need find where to read inside selected papers? use find_reading_locations.
Need source quote text? use read_locations.
Need previous source quote details? use trace_source_quotes.
```

### Stateless Tool Calls

Product ReAct tool calls must not create hidden mutable session state required by
later tools.

Any paper or reading-location choice must be passed explicitly in the consuming tool input
through durable opaque refs.

Allowed state reads:

- durable product data
- fixed conversation search scope
- current user permission state
- persistent Source Quote records
- disclosed opaque refs that resolve to durable product data or persisted
  reference records

Forbidden hidden state dependencies:

- current selected papers
- current selected reading locations
- `paperSelectionRef`
- implicit follow-up state that is not present in explicit input or current
  model context

There are no public selection-only tools. The backend may record trace events
showing which explicit refs were passed to consuming tools, but later tools must
not depend on an unspoken selection state.

### Progressive Disclosure

The LLM should see information in levels:

```text
session state -> paper candidates -> paper outline / paper locations -> reading-location candidates -> source quotes
```

Do not dump whole papers, whole libraries, or unbounded location lists by
default. Large tools must return bounded output. `get_paper_outline` returns
whole-paper structure without page ranges. `list_paper_locations` may use an
explicit page range when the user or context asks for specific pages. Reading
volume is controlled by internal product policy, not LLM-supplied
hyperparameters.

### Ready Paper Scope

LLM-facing paper retrieval scope contains only READY Product Papers.

Unparsed, processing, failed, or unreadable papers are outside Product ReAct
paper browse, paper candidate search, paper identity lookup, paper outlines,
paper location lists, reading locations, and Source Quotes. Product
upload/admin status may track those papers elsewhere, but the LLM-facing
paper-reading tools must not return them as candidates.

### Semantic Retrieval Boundary

Semantic retrieval is allowed in two bounded places.

| Tool | Scope | Search surface | Output |
| --- | --- | --- | --- |
| `search_paper_candidates` | fixed conversation search scope | paper-level fields such as title, abstract, catalog tags, metadata | paper candidate cards with `paperHandle` |
| `find_reading_locations` | explicitly chosen papers | selected paper content, parser artifacts, headings, captions, anchors | reading-location candidates with `locationRef` |

Neither tool returns Source Quotes, final recommendations, answer snippets,
or semantic sufficiency judgments.

### Built-In Reading Skills

The catalog is not only a restriction system. The Product ReAct prompt must
include built-in reading skills that give the LLM positive operating guidance
for research-paper reading tasks.

Examples:

- Summary/contribution questions: read Abstract, Introduction, and Conclusion;
  expand to Methods or Results when the answer needs technical or empirical
  detail.
- Method questions: locate/read Methods, Approach, Model, Algorithm, or related
  figures/formulas.
- Experiment/result questions: locate/read Experiments, Evaluation, Results,
  tables, figures, captions, and nearby explanatory text.
- Limitation/failure questions: locate/read Limitations, Discussion, Conclusion,
  future work, and failure-case mentions.
- Comparison questions: read matching task-relevant locations for each compared
  paper; do not compare a dimension unless each side has Source Quotes.
- Recommendation with content reasons: first search paper candidates, then read
  selected candidates before giving content-based reasons.
- Source follow-up: trace explicit `sourceQuoteRef` values first; read
  additional nearby locations only when broader context is requested.
- Expand reading when initial Source Quotes are empty, truncated, weak, or
  mismatched, while product reading policy allows another call. This is not a
  backend semantic sufficiency validator.

### Source Quote Boundary

Only Source Quotes returned by `read_locations`, or Source Quotes resolved by
`trace_source_quotes` from explicit `sourceQuoteRef` values, may support final
paper-content claims and rendered citations.

The following are not Source Quotes:

- product/session state
- paper identity
- paper candidate cards
- catalog tags
- abstract previews
- paper outlines
- paper location lists
- section headings
- page refs
- table/figure refs
- reading-location candidates
- model memory

## LLM-Facing Concepts

Keep prompt language small. The LLM only needs these working concepts:

| Concept | Meaning |
| --- | --- |
| Paper Card | A paper browse/search/identity result item with display metadata and a `paperHandle`. Its ordinal is only a UI label. |
| Paper Outline | Whole-paper structure and parser status for chosen papers. |
| Paper Location | A section/page/table/figure ref returned by `get_paper_outline`, `list_paper_locations`, `find_reading_locations`, or a clicked UI ref. |
| Reading Location Candidate | A possible reading location returned by `find_reading_locations`, with a `locationRef`. Its ordinal is only a UI label. |
| Source Quote | Text/table/caption content returned by `read_locations` or resolved from an existing `sourceQuoteRef`. |

Backend validation may use richer internal metadata, but the prompt should not
force the LLM to reason over unnecessary implementation vocabulary.

## Opaque Handles

The LLM must operate on opaque handles generated by PaperLoom.

Allowed handle types:

| Handle | Meaning |
| --- | --- |
| `paperHandle` | Opaque single-paper handle. |
| `sectionRef` | Opaque section handle from a Paper Outline or paper-location result. |
| `pageRef` | Opaque page handle from a paper-location result. |
| `tableRef` | Opaque table handle from a paper-location result. |
| `figureRef` | Opaque figure handle from a paper-location result. |
| `sourceQuoteRef` | Opaque ref to a Source Quote. |

Rules:

- LLM must never pass raw `paperId`, `chunkId`, SQL ids, ES ids, or vector ids.
- LLM-facing refs must be resolvable from durable product data or persisted
  reference records. They must not point to in-memory list snapshots.
- `paperHandles` are allowed only when disclosed by prior tool output or clicked
  refs.
- Ordinals are display labels only. They must not be passed to LLM-facing tools.
- Section/page/table/figure refs must be disclosed by `get_paper_outline`,
  `list_paper_locations`, `find_reading_locations`, or an explicit UI click.
- `locationRef` / `locationRefs` are field names that carry section/page/table/
  figure refs. They are not separate handle types.
- Reading must pass explicit `locationRefs` to `read_locations`; it must not rely
  on hidden current reading-location state.

## Passing Paper And Location Choices

Tools pass choices directly as paper handles and location refs.

Paper selection is passed directly to paper-reading tools:

```json
{
  "paperHandles": ["paper_handle_..."]
}
```

Reading-location selection is passed directly to `read_locations`:

```json
{
  "locationRefs": ["page_...", "table_..."]
}
```

Validation:

- `paperHandles` must be disclosed by prior tool output or clicked paper rows,
  visible to the user, READY, and inside fixed conversation search scope.
- `locationRefs` must be disclosed by prior `get_paper_outline`,
  `list_paper_locations`, `find_reading_locations` output, or clicked refs.
- `locationRefs` must be section/page/table/figure refs that resolve to READY
  papers visible to the user and inside fixed conversation search scope.
- Ordinals must not be accepted in tool input.
- hidden or unexposed refs are rejected.

## LLM-Facing Tool Catalog

The new catalog exposes nine public product tools:

```text
get_session_state
list_papers
search_paper_candidates
find_papers_by_identity
get_paper_outline
list_paper_locations
find_reading_locations
read_locations
trace_source_quotes
```

### 1. `get_session_state`

Purpose:

```text
Get the fixed conversation search-scope label and readable paper count.
```

Input:

```json
{}
```

Output:

```json
{
  "searchScope": {
    "label": "...",
    "readablePaperCountKnown": true,
    "readablePaperCount": 12
  }
}
```

Rules:

- It does not list historical refs.
- It does not discover papers.
- It does not read paper content.
- It returns a fixed compact state shape.
- It does not choose, change, or describe search-scope mode. Search scope is
  fixed when the conversation starts and enforced by the Harness.
- It can support product-state answers such as readable paper count and scope
  label.

### 2. `list_papers`

Purpose:

```text
Browse or explicitly filter papers inside the fixed conversation search scope.
```

Input:

```json
{
  "filters": {
    "titleContains": "",
    "titleExact": "",
    "filenameContains": "",
    "filenameExact": "",
    "authorName": "",
    "doiExact": "",
    "arxivIdExact": "",
    "yearRange": {"from": 2023, "to": 2026},
    "venue": "",
    "catalogTopicIds": ["topic_..."],
    "paperTypeIds": ["type_..."]
  },
  "includeFacets": false,
  "sort": "RECENT | TITLE | YEAR"
}
```

Output:

```json
{
  "facets": {},
  "items": [
    {
      "ordinal": 1,
      "paperHandle": "paper_handle_...",
      "title": "...",
      "originalFilename": "...",
      "authors": [],
      "year": 2025,
      "catalogTopics": [],
      "paperTypes": []
    }
  ]
}
```

Rules:

- It is deterministic browse/filter.
- It returns only READY papers.
- It must not accept `searchNeed`, `query`, `question`, `semanticNeed`, or topic
  text.
- It must not run BM25/vector/rerank topic discovery.
- Facets are included through `includeFacets=true`; there is no separate public
  facet tool.
- If `includeFacets=true`, the tool returns the product's fixed facet set:
  catalog topics, paper types, years, authors, and venues inside the fixed
  conversation search scope.
- `catalogTopicIds` and `paperTypeIds` must come from prior
  `list_papers(includeFacets=true)` output or clicked UI filters.
- It returns a fixed paper card shape; output size is controlled by internal
  product policy, not LLM-selected fields.
- `ordinal` is a display label only. Consuming tools must pass `paperHandle`.
- It returns paper cards for browsing/selection, not Source Quotes.

### 3. `search_paper_candidates`

Purpose:

```text
Search for candidate papers by topic or need inside the fixed conversation search scope.
```

Input:

```json
{
  "searchNeed": "papers related to agent evaluation",
  "metadataFilters": {
    "yearRange": {"from": 2020, "to": 2026},
    "catalogTopicIds": [],
    "paperTypeIds": []
  }
}
```

Output:

```json
{
  "items": [
    {
      "ordinal": 1,
      "paperHandle": "paper_handle_...",
      "title": "...",
      "originalFilename": "...",
      "authors": [],
      "year": 2025
    }
  ]
}
```

Rules:

- It is paper-level candidate recall, not a recommendation verdict.
- It returns only READY papers.
- It may use semantic retrieval over title, abstract, catalog tags, and metadata.
- Search fields, retrieval modes, reranking, and search terms are internal
  implementation decisions, not LLM inputs.
- `metadataFilters.catalogTopicIds` and `metadataFilters.paperTypeIds` must come
  from prior `list_papers(includeFacets=true)` output or clicked UI filters.
- Candidate order is the only public ranking signal. Internal scores must not be
  exposed to the LLM.
- It must not return source quote text, final recommendation reasons, or
  paper-content claims.
- If the final answer only uses this result, the answer must be a candidate list,
  not a source-quote-backed recommendation.
- `ordinal` is a display label only. Consuming tools must pass `paperHandle`.

### 4. `find_papers_by_identity`

Purpose:

```text
Resolve a specific paper mention by identity hints.
```

Input:

```json
{
  "identityHints": {
    "titleContains": "",
    "titleExact": "",
    "filenameContains": "",
    "filenameExact": "",
    "doiExact": "",
    "arxivIdExact": "",
    "authorName": "",
    "year": 2021
  }
}
```

Output:

```json
{
  "ambiguous": false,
  "matches": [
    {
      "ordinal": 1,
      "paperHandle": "paper_handle_...",
      "title": "...",
      "originalFilename": "...",
      "matchReason": "TITLE_CONTAINS | TITLE_EXACT | FILENAME_EXACT | DOI_EXACT | ARXIV_ID_EXACT"
    }
  ]
}
```

Rules:

- It is not semantic topic search.
- It must not accept `searchNeed`, `query`, `question`, `semanticNeed`,
  related-to topic, or recommendation need.
- It must not accept ordinal hints. Ordinals are display labels only.
- If the user refers to a displayed ordinal without clicking the row, ask them to
  click the paper row so the frontend can provide `paperHandle`.
- It returns only READY papers.
- If identity hints match multiple papers, return ambiguous paper cards rather
  than guessing.
- If the model already has a `paperHandle`, it should pass that handle directly
  to the consuming tool instead of calling this tool.
- Source Quote refs are resolved through `trace_source_quotes`, not this tool.
- It returns identity candidates, not Source Quotes.

### 5. `get_paper_outline`

Purpose:

```text
Get parser quality and whole-paper outline for explicitly chosen READY papers.
```

Input:

```json
{
  "paperHandles": ["paper_handle_..."]
}
```

Output:

```json
{
  "paperHandles": ["paper_handle_..."],
  "papers": [
    {
      "paperHandle": "paper_handle_...",
      "title": "...",
      "originalFilename": "...",
      "parserQuality": {
        "pageTextCoverage": 1.0,
        "outlineConfidence": "HIGH",
        "warnings": []
      },
      "sections": [
        {
          "sectionRef": "sec_...",
          "heading": "Methods",
          "sectionRole": "METHODS",
          "level": 1,
          "pageStart": 3,
          "pageEnd": 5
        }
      ]
    }
  ]
}
```

Rules:

- It does not read paper content.
- It returns whole-paper outline and parser quality state, not Source Quotes.
- It only accepts READY papers.
- `parserQuality` describes extraction quality for reading strategy; it is not a
  readiness signal.
- It must not accept `mapMode`, `pageRange`, output-size controls, or expansion
  depth controls.
- It returns section refs from the whole-paper outline. It does not list all
  page/table/figure refs.
- For multiple papers, returned outlines must be grouped by owning paper.
- Every returned `sectionRef` must resolve to its owning `paperHandle`.

### 6. `list_paper_locations`

Purpose:

```text
List explicit section/page/table/figure refs for chosen READY papers.
```

Input:

```json
{
  "paperHandles": ["paper_handle_..."],
  "pageRange": {"from": 1, "to": 10},
  "locationTypes": ["SECTION", "PAGE", "TABLE", "FIGURE"]
}
```

Required: `paperHandles`.

Optional: `pageRange`, `locationTypes`.

Output:

```json
{
  "paperHandles": ["paper_handle_..."],
  "locations": [
    {
      "ordinal": 1,
      "locationRef": "page_...",
      "locationType": "PAGE",
      "paperHandle": "paper_handle_...",
      "sectionRef": "sec_...",
      "pageNumber": 7,
      "label": "Page 7"
    }
  ]
}
```

Rules:

- It is deterministic navigation, not semantic search.
- It does not read paper content.
- It returns explicit refs usable by `read_locations`, not Source Quotes.
- `paperHandles` are required.
- `pageRange` is optional and is used only when the user or current context
  explicitly asks for specific pages.
- `pageRange.from` and `pageRange.to` are 1-based inclusive page numbers.
  `from` must be less than or equal to `to`, and the range must fit inside the
  selected paper's page count.
- `locationTypes` is an optional coarse location filter.
- `locationTypes` only accepts `SECTION`, `PAGE`, `TABLE`, and `FIGURE`. If
  omitted, the tool may return all supported location types subject to product
  output limits.
- It must not accept `readingNeed`, `searchNeed`, `query`, `question`, or
  semantic topic text.
- It returns ordered locations in document order. Internal output limits are
  controlled by product policy.
- `ordinal` is a display label only. Consuming tools must pass `locationRef`.
- Every returned location ref must resolve to its owning `paperHandle`.

### 7. `find_reading_locations`

Purpose:

```text
Search within explicitly chosen READY papers to find candidate reading locations.
```

Input:

```json
{
  "paperHandles": ["paper_handle_..."],
  "readingNeed": "where the paper discusses method limitations",
  "locationTypes": ["SECTION", "PAGE", "TABLE", "FIGURE"]
}
```

Required: `paperHandles`, `readingNeed`.

Optional: `locationTypes`.

Output:

```json
{
  "paperHandles": ["paper_handle_..."],
  "candidates": [
    {
      "ordinal": 1,
      "locationRef": "page_...",
      "locationType": "PAGE",
      "paperHandle": "paper_handle_...",
      "sectionRef": "sec_...",
      "pageNumber": 7,
      "sectionRole": "DISCUSSION"
    }
  ]
}
```

Rules:

- It does not discover or select papers.
- It searches only within explicitly supplied `paperHandles`.
- `paperHandles` must be visible, READY, and inside fixed conversation search
  scope.
- `readingNeed` is required. If there is no semantic reading need, use
  `list_paper_locations` instead.
- It may use lexical, BM25, vector, rerank, heading-role, caption, parser-anchor,
  and similar bounded signals.
- Retrieval basis is an internal implementation decision, not an LLM input.
- Section role is an internal location signal and returned location label, not an LLM
  input.
- `locationTypes` is an optional coarse content-location filter, not a semantic
  sufficiency signal.
- `locationTypes` only accepts `SECTION`, `PAGE`, `TABLE`, and `FIGURE`. If
  omitted, the tool may search all supported location types subject to product
  output limits.
- Search terms are derived internally from `readingNeed`, not supplied by
  the LLM as a separate input.
- It returns ordered `locationRef` reading-location candidates only.
- Candidate order is the only public ranking signal. Internal scores must not be
  exposed to the LLM.
- `locationRef` values are reading locations, not Source Quotes.
- `ordinal` is a display label only. Consuming tools must pass `locationRef`.
- It must not return source quote text, answer snippets, final recommendation
  reasons, or Source Quote sufficiency judgments.

### 8. `read_locations`

Purpose:

```text
Read explicitly chosen section/page/table/figure locations and return Source Quotes.
```

Input:

```json
{
  "locationRefs": ["page_...", "table_..."]
}
```

Output:

```json
{
  "sourceQuotes": [
    {
      "sourceQuoteRef": "source_quote_...",
      "locationRef": "page_...",
      "paperHandle": "paper_handle_...",
      "paperTitle": "...",
      "sectionRef": "sec_...",
      "pageRef": "page_...",
      "pageNumber": 3,
      "contentKind": "TEXT",
      "content": "..."
    }
  ],
  "readStatus": [
    {
      "locationRef": "page_...",
      "status": "OK | EMPTY_LOCATION | CONTENT_TRUNCATED | UNREADABLE_LOCATION"
    }
  ]
}
```

Rules:

- It mechanically reads selected locations up to product-defined internal
  output limits.
- It resolves the owning paper from each `locationRef`.
- Each returned Source Quote includes the input `locationRef` it came from.
- Each returned Source Quote includes `paperTitle` for display and answer
  wording.
- Returned content kind is determined by the selected location ref type and
  available parser artifacts.
- `sectionRef` and `pageRef` return `TEXT`; `tableRef` returns `TABLE`;
  `figureRef` returns `FIGURE_CAPTION`.
- `content` contains plain text for `TEXT`, table markdown for `TABLE`, and
  caption text for `FIGURE_CAPTION`.
- `readStatus.status=OK` means the location produced Source Quotes.
- It must not accept `question`, `query`, `semanticNeed`, `subQuestions`, or
  `coverageTargets`.
- It must not search for new locations.
- It must not accept ordinals or a reading-location candidate list ref.
- It must not judge semantic sufficiency or claim support.
- It must not expose read-size, quote-count, or character-budget controls.
- It must not accept `quoteKinds`.
- Source Quote splitting and truncation are internal to the PDF/parser pipeline
  and product reading policy.
- If selected location content exceeds internal product limits, return
  `CONTENT_TRUNCATED`; the tool must not ask the user for narrower locations.
- Final paper-content answers may cite only `sourceQuoteRef` values returned here
  or resolved by `trace_source_quotes`.

### 9. `trace_source_quotes`

Purpose:

```text
Resolve explicit Source Quote refs to stored Source Quote text and source location.
```

Input:

```json
{
  "sourceQuoteRefs": ["source_quote_..."]
}
```

Output:

```json
{
  "sourceQuotes": [
    {
      "sourceQuoteRef": "source_quote_...",
      "locationRef": "page_...",
      "paperHandle": "paper_handle_...",
      "paperTitle": "...",
      "pageRef": "page_...",
      "sectionRef": "sec_...",
      "contentKind": "TEXT",
      "content": "..."
    }
  ]
}
```

Rules:

- It is a deterministic Source Quote resolver.
- It only accepts explicit `sourceQuoteRef` values supplied in the tool input.
- It returns stored Source Quote text and source-location metadata.
- Each returned Source Quote includes the stored `locationRef` it originally
  came from.
- Each returned Source Quote includes `paperTitle` for display and answer
  wording.
- It must not accept `paperHandle`, `sectionRef`, `pageRef`, `tableRef`, or
  `figureRef`.
- Returned source-location refs are metadata, not reading-location input.
- It must not expand surrounding page, section, table, or figure content.
- It must not search, rank, recommend, read new content, or judge sufficiency.
- To read broader context, call `list_paper_locations` for the traced paper
  and then call `read_locations` with refs returned by that tool.

## Final Answer Support

The public final AnswerEnvelope types are:

```text
GENERAL_ANSWER
PRODUCT_STATE_ANSWER
SOURCE_QUOTED_ANSWER
NOT_ENOUGH_SOURCE_QUOTES
CLARIFICATION_NEEDED
```

Support requirements:

| Public answer type | Required support |
| --- | --- |
| `GENERAL_ANSWER` | No product, paper, page, citation, or paper-content claim. |
| `PRODUCT_STATE_ANSWER` | Product-state, paper-list, paper-candidate, or identity results. Must not make paper-content claims. |
| `SOURCE_QUOTED_ANSWER` | `sourceQuoteRef` values from `read_locations` or `trace_source_quotes`. |
| `NOT_ENOUGH_SOURCE_QUOTES` | The model could not get enough Source Quotes to answer; it must say what was tried or unavailable without adding unsupported paper facts. |
| `CLARIFICATION_NEEDED` | No unsupported product or paper-content claims. |

The LLM chooses the final answer type. The Harness validates that choice against
tool results, accepted refs, and source-quote records. If the chosen type is not
compatible with available support, the Harness rejects the final answer; the LLM
does not get to self-certify support.

The Harness must reject final answers that use:

```text
catalog cards as Source Quotes
abstract summaries as Source Quotes
section summaries as Source Quotes
paper outlines as Source Quotes
paper location lists as Source Quotes
paper selections as Source Quotes
product state as paper content
model memory as Source Quotes
```

Final paper-content claims must cite `sourceQuoteRef` values, not rendered citation
numbers. The Harness maps `sourceQuoteRef` values to display citation numbers.

## Prompt Requirements

The system prompt must tell the LLM:

- Use tools only when the request needs product, paper, source, or source-quote
  support.
- Ordinary smalltalk can answer directly as `GENERAL_ANSWER`.
- Use action-named tools; do not call old tools.
- Do not display chain-of-thought.
- The frontend should show only `calling <toolName>`.
- Use progressively disclosed objects. Do not select hidden objects.
- Use `list_papers` only for browsing/filtering.
- Use `search_paper_candidates` for topic, need, related-paper, discovery, or
  recommendation candidate search.
- Use `find_papers_by_identity` only for specific-paper identity fields such as
  title, filename, DOI, arXiv id, author, or year.
- Do not resolve typed ordinal references such as "the ninth paper"; ask the
  user to click the paper row so the frontend can provide `paperHandle`.
- Use `get_paper_outline` after choosing papers when structure or parser quality
  is needed.
- Use `list_paper_locations` when exact section/page/table/figure refs are
  needed without semantic search.
- Use `find_reading_locations` to find where to read inside chosen papers only
  when there is a semantic `readingNeed`.
- Use `read_locations` for Source Quotes.
- Use `trace_source_quotes` only for explicit `sourceQuoteRef` values from clicked
  source chips or current model context.
- Product policy, not the LLM, controls output size, candidate count, read size,
  Source Quote splitting, and truncation. Do not pass fields such as `limit`,
  `pageSize`, `maxCandidates`, `topK`, `budget`, `maxCharsPerLocation`,
  `maxTotalChars`, `maxQuotesPerLocation`, `quoteKinds`, or expansion-depth
  controls.
- If a bounded result is too broad, narrow with business inputs such as metadata
  filters, identity hints, paper handles, location types, or a clearer
  `searchNeed` / `readingNeed`. If the user intent is still ambiguous, ask for
  clarification. Do not try to increase internal limits.
- Do not resolve user-typed citation numbers such as `[2]`. If the user types a
  displayed citation number instead of clicking the source chip, ask them to
  click the chip. Click events should provide `sourceQuoteRefs`.
- Candidate paper cards are not Source Quotes.
- Paper Outlines, paper location lists, and reading-location candidates are
  navigation only.
- Final paper-content claims must cite `sourceQuoteRef` markers only.
- Never generate final numbered citations; the Harness maps `sourceQuoteRef` values to
  display numbers.
- If Source Quotes remain insufficient, answer with
  `NOT_ENOUGH_SOURCE_QUOTES` or ask for clarification. Do not fall back to
  unsupported answers.

## Validation Rules

The Harness must validate:

- unsupported tool names are rejected
- old public tools are rejected
- forbidden unbounded natural-language fields are rejected
- raw ids such as `paperId`, `chunkId`, `sql`, `esQuery`, `topK`, and rerank
  controls are rejected
- regex filter fields are rejected
- LLM-supplied output-size controls such as `limit`, `pageSize`,
  `maxCandidates`, and expansion-depth controls are rejected
- no tool call creates hidden mutable selection state required by later tools
- all `catalogTopicIds` and `paperTypeIds` are disclosed by prior
  `list_papers(includeFacets=true)` output or clicked UI filters
- selected `paperHandles` are disclosed by prior tool output or clicked paper
  rows, visible, READY, and inside fixed conversation search scope
- `get_paper_outline` rejects `mapMode`, `pageRange`, and output-shape controls
- `list_paper_locations` rejects semantic inputs such as `readingNeed`,
  `searchNeed`, `query`, `question`, or topic text
- `list_paper_locations` rejects invalid `pageRange` values: non-positive page
  numbers, `from > to`, or pages outside the selected paper's page count
- `find_reading_locations` rejects missing `readingNeed`
- `locationTypes` values outside `SECTION`, `PAGE`, `TABLE`, and `FIGURE` are
  rejected
- selected `locationRefs` are disclosed by prior `get_paper_outline`,
  `list_paper_locations`, `find_reading_locations` output, or clicked refs
- selected `locationRefs` resolve to READY papers visible to the user and inside
  fixed conversation search scope
- ordinals are rejected in tool input
- `read_locations` rejects LLM-supplied read-size, quote-count, and
  character-budget controls
- `read_locations` rejects `quoteKinds`
- `read_locations` rejects page/section/table/figure refs that came only from
  `trace_source_quotes` and were not separately disclosed by
  `get_paper_outline`, `list_paper_locations`, `find_reading_locations`, or a
  clicked reading-location ref
- `trace_source_quotes` rejects non-`sourceQuoteRef` inputs
- `trace_source_quotes` returns source quote text only for accepted
  `sourceQuoteRef` values
- Source Quote `contentKind` must be one of `TEXT`, `TABLE`, or
  `FIGURE_CAPTION`
- user-typed rendered citation numbers such as `[2]` are not accepted as source
  refs and are not resolved through chat-text counting
- `sourceQuoteRef` values used in final answer exist in current-turn results or
  persisted Source Quote records
- final answer type is compatible with available support
- final answer does not contain model-generated numbered citations
- final answer does not cite non-`sourceQuoteRef` values

## Example Flows

### Smalltalk

```text
User: hi
LLM: final GENERAL_ANSWER
Tools: none
```

### Paper Count

```text
User: 有多少论文可以检索
get_session_state()
final PRODUCT_STATE_ANSWER
```

### Browse Papers

```text
User: 列出最近上传的论文
list_papers(sort="RECENT")
final PRODUCT_STATE_ANSWER candidate/list answer
```

### Topic Candidate Search

```text
User: 有哪些 agent eval 相关论文
search_paper_candidates(searchNeed="agent eval related papers")
final PRODUCT_STATE_ANSWER candidate list
```

If no Source Quotes are read, the final answer must be a candidate list, not a
source-quote-backed recommendation.

### Source-Quote-Based Recommendation

```text
User: 推荐 agent eval 相关论文，并说明原因
search_paper_candidates(...)
get_paper_outline(paperHandles=["paper_handle_...", "paper_handle_...", "paper_handle_..."])
find_reading_locations(paperHandles=["paper_handle_...", "paper_handle_...", "paper_handle_..."],
                       readingNeed="why these papers are relevant to agent evaluation")
read_locations(locationRefs=["page_...", "sec_...", "table_..."])
final SOURCE_QUOTED_ANSWER with `sourceQuoteRef` values
```

### Direct Paper Request

```text
User: 细讲一下 LoRA 那篇论文的方法
find_papers_by_identity(identityHints.titleContains="LoRA")
get_paper_outline(paperHandles=["paper_handle_..."])
find_reading_locations(paperHandles=["paper_handle_..."],
                       readingNeed="method or approach of the paper")
read_locations(locationRefs=["sec_...", "page_..."])
final SOURCE_QUOTED_ANSWER with `sourceQuoteRef` values
```

If identity lookup is ambiguous, the model must ask the user to clarify or
choose a candidate before making paper-content claims.

### Clicked Paper Follow-Up

```text
User clicks a paper row, then asks: 细讲这篇
Frontend reference focus: paperHandle="paper_handle_..."
get_paper_outline(paperHandles=["paper_handle_..."])
find_reading_locations(...)
read_locations(...)
final SOURCE_QUOTED_ANSWER with `sourceQuoteRef` values
```

If the user only types an ordinal such as `细讲第九篇`, ask the user to click the
paper row instead of trying to resolve the ordinal from chat text or session
state.

### Source Follow-Up

```text
User clicks a source chip, then asks: 解释这个引用
Frontend reference focus: sourceQuoteRefs=["source_quote_..."]
trace_source_quotes(sourceQuoteRefs=["source_quote_..."])
final SOURCE_QUOTED_ANSWER with resolved `sourceQuoteRef` values
```

If the user only types a rendered citation number such as `解释引用 [2]`, ask the
user to click the source chip instead of trying to resolve `[2]` from
chat text.

If the user asks for broader page/section context, the model may then call
`list_paper_locations` for the traced paper and then `read_locations` with
explicit page/section refs from that result.

## Trace Requirements

Trace artifacts must record:

- current user message
- whether no-tool `GENERAL_ANSWER` path was used
- full prompt messages
- tool catalog version
- tool calls and arguments
- search/locate inputs and diagnostics
- current-turn embedding input used by Harness
- explicit paper handles
- paper outline outputs
- paper location list outputs
- explicit location refs
- `sourceQuoteRef` values returned
- final answer envelope
- Harness validation decisions and rejection reasons
- rendered citation mapping snapshot

Trace remains JSON artifact output and stays decoupled from business tables.

## Eval Implications

This catalog makes evaluation layerable:

| Layer | What to score |
| --- | --- |
| Paper browse/search | Candidate recall, bounded output behavior, facet use, scope isolation. |
| Paper identity | Title/file/DOI/arXiv resolution and ambiguity handling. |
| Paper outline | Parser quality and section outline coverage. |
| Paper location list | Section/page/table/figure ref coverage and page-range filtering. |
| Reading location | Whether `find_reading_locations` finds useful reading locations. |
| Reading | Source Quote availability and citation validity. |
| Final answer | Unsupported claim rate, source quote ref validity, answer envelope validity. |

Semantic quality is evaluated offline from traces. Product tools should not try
to score final semantic quality at runtime.

## Migration Notes

The new prompt must not expose these old tools:

```text
answer_without_product_state
get_system_state
get_session_scope
list_papers as semantic search
find_papers
resolve_papers
get_paper_metadata
retrieve_evidence
inspect_reference
inspect_page
inspect_session
inspect_catalog
select_papers
inspect_paper
select_targets
inspect_source
filter_papers
plan_reading
```

Old public LLM-facing tools must be removed or fully hidden from the new Product
ReAct runtime.

First implementation should prioritize:

1. New tool schema definitions and validation for the 9-tool catalog.
2. Stateless tool-call validation: no hidden current paper/location selection.
3. `get_session_state`, `list_papers`, `search_paper_candidates`, and
   `find_papers_by_identity`.
4. `get_paper_outline`, `list_paper_locations`, `find_reading_locations`,
   and `read_locations`.
5. `trace_source_quotes`.
6. Prompt rewrite with built-in reading skills.
7. Final-answer support validation and `sourceQuoteRef` citation validation.
8. Trace fields for explicit paper handles, outlines, location refs, Source Quotes,
   and validation decisions.
