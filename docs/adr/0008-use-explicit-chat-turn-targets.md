# Use explicit chat turn targets

PaperLoom chat turns must target the conversation selected by the client that sent the message, not
a mutable per-user current-conversation pointer. The WebSocket chat contract will carry an explicit
`conversationId` as the Chat Turn Target, and the backend will validate ownership and use that
conversation's locked scope, memory, and references for the turn. A per-user current selection may
remain as UI convenience state, but not as the authoritative routing source for structured chat
messages.

**Considered Options**

- Keep routing through `user:{userId}:current_conversation`.
  Rejected because the same user can have multiple tabs or devices open and one client can change
  the per-user selection before another client sends.
- Use WebSocket `clientId` as the conversation boundary.
  Rejected because one browser client can switch conversations, and a conversation must outlive one
  connection.
- Require an explicit `conversationId` on structured chat messages.
  Accepted because it makes the user's intended conversation part of the turn contract and lets the
  backend enforce ownership and scope before generation.

**Consequences**

- The frontend must create or choose a conversation before sending a structured chat message.
- The backend must reject foreign conversation ids and avoid silently falling back to another
  conversation when an explicit target is invalid.
- Stop/resume helpers should prefer `generationId`, then requester-client active generation, rather
  than a single per-user active generation.
