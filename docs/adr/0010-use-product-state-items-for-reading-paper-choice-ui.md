# Use product-state items for reading paper choice UI

PaperLoom will render clickable Product Reading paper choices from backend-controlled Product State
Items sent with the current completion payload, not from LLM prose, raw tool-result streaming, or
Source Quote mappings. This keeps identity-disambiguation rows separate from evidence citations:
the row can carry a `paperHandle` for a later explicit clicked paper anchor, but it cannot support
paper-content claims.

**Considered Options**

- Parse paper choices from the assistant's final markdown. Rejected because prose is not a stable
  product contract and can accidentally turn titles or ordinals into selection identity.
- Stream raw tool results to the UI. Rejected because raw `find_papers_by_identity` results may
  contain fields that are useful to the model but should not become frontend product state.
- Persist paper choice cards in conversation history now. Rejected because the minimal loop only
  needs the current completion and long-term card replay requires a separate persistence contract.

**Consequences**

- Product State Items are navigation UI state, not Source Quotes or citations.
- Delivery code must sanitize product-state payloads at the backend boundary before sending them to
  the frontend.
- Future browse/search paper cards can reuse the same channel after identity choice cards prove the
  clicked-paper-anchor loop.
