# Use explicit reading turn anchors for Source Quote trace

PaperLoom will authorize `trace_source_quotes` from explicit Reading Turn Anchors, such as
clicked source-chip `sourceQuoteRef` values passed into the isolated reading entry point, rather
than parsing rendered citation numbers, user-typed refs, conversation memory, or the full
Conversation Source Quote Registry directly in the final-answer validator.

**Considered Options**

- Let final answers cite any `sourceQuoteRef` registered in the conversation. Rejected because it
  turns the registry into hidden evidence context and bypasses current-turn tool disclosure.
- Parse display citations such as `[1]` from conversation history. Rejected for the minimal loop
  because display numbers are render artifacts, not stable source identities.
- Let user text containing `source_quote_...` authorize tracing. Rejected because model/user text is
  not a trusted product anchor.

**Consequences**

- A clicked Source Quote is not citeable until `trace_source_quotes` returns it in the current turn.
- The Conversation Source Quote Registry authorizes trace lookup, not final-answer support.
- Follow-up UI or route integrations must pass stable clicked `sourceQuoteRef` anchors explicitly.
