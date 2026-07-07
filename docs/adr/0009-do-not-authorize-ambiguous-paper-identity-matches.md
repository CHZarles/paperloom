# Do not authorize ambiguous paper identity matches for reading

PaperLoom will treat ambiguous `find_papers_by_identity` results as product-state choice sets, not
as selected papers for same-turn reading authorization. Ambiguous result cards may include
`paperHandle` values so the UI can render concrete choices, but the Product Reading harness must not
allow those handles to feed `get_paper_outline`, `list_paper_locations`, or
`find_reading_locations` until the user clarifies or a later clicked-row anchor supplies an explicit
selection.

**Considered Options**

- Authorize every handle returned by identity lookup. Rejected because broad hints such as author
  plus year can match multiple papers and would let the model read from the wrong paper.
- Omit handles from ambiguous results. Rejected because the UI still needs concrete rows to present
  choices and future clicked-row anchors can carry the selected handle explicitly.

**Consequences**

- `AMBIGUOUS` identity results can support product-state answers and clarification prompts only.
- Unambiguous identity results can disclose paper handles for reading tools in the same turn.
- Future clicked paper-row integrations must be explicit anchors, not ordinal references or hidden
  reuse of ambiguous handles.
