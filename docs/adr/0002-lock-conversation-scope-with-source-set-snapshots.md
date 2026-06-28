# Lock conversation scope with source set snapshots

PaperLoom conversations need a stable evidence universe. After a session begins, the papers that can
support answers must not change silently because a user edits a collection, uploads a new matching
paper, or changes a regex-style selection rule.

**Decision**

- A new conversation starts with editable scope and defaults to `AUTO_LIBRARY`.
- The backend locks the conversation scope when it accepts the first user message.
- Locked scope is immutable. To use different papers, the user starts a new conversation.
- `SOURCE_SET_SNAPSHOT` is the retrieval truth for scoped conversations.
- A source set snapshot stores resolved product `paperIds` captured at lock time.
- Collections are reusable paper-management objects. They may create snapshots, but locked
  conversations do not retrieve from live collection membership.
- Regex and metadata filters are batch-add or snapshot-creation tools. They are not live session
  scopes.
- Reference follow-up is represented as temporary reference focus, not as a scope mutation.
- WebSocket chat requests must not be allowed to override a locked conversation by sending arbitrary
  `paperIds`.

**Considered Options**

- Let every chat message carry its own mutable `scope`.
  Rejected because a single conversation could mix unrelated evidence universes, making history and
  citations difficult to audit.
- Bind conversations directly to live collections.
  Rejected because editing a collection would retroactively change an old conversation's retrieval
  boundary.
- Use dynamic regex or saved-search scopes.
  Rejected for locked sessions because newly uploaded or edited papers could change historical
  behavior. Regex remains useful as a batch tool that resolves to paper ids.
- Require every session to choose a fixed source set before chatting.
  Rejected because `AUTO_LIBRARY` is a valid paper-discovery workflow.

**Consequences**

- Conversation/session storage needs scope mode, lock state, scope status, source label, optional
  recipe metadata, and resolved snapshot paper ids.
- Message history needs the effective scope used by each user message.
- Collection storage is required, but collection edits never mutate locked sessions.
- Chat source selection must use server-side paper-level candidate search over searchable papers.
- Answer source actions should start a new session from those papers instead of changing the current
  session.
- Development runtime chat and product-paper data can be reset for this migration. Admin access and
  eval benchmark corpora are preserved.
