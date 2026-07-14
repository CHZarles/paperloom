# Session Isolation Security Issue

Date: 2026-07-07

Review status: sharpened by `grill-with-docs`; recommended decisions applied.
Implementation status: fixed on branch `fix/session-isolation-chat-target`.

## Summary

PaperLoom's cross-user conversation isolation is mostly sound because durable conversation reads and
writes use `userId + conversationId`. The issue fixed here was same-user, multi-tab isolation: chat
routing depended on a single per-user Redis key, so one browser tab could accidentally affect
another tab's active conversation.

This was a correctness and isolation bug. It was not evidence of a cross-user data leak, but it
could cause messages, persisted answers, memory updates, source-scope locking, and reference
follow-ups to land in the wrong conversation for the same authenticated user.

Severity: **high for conversation correctness**, **medium for security**. The bug can corrupt a
user's own conversation state and evidence trail, but current evidence does not show a cross-user
read/write bypass.

## Fix Applied

The fix makes the WebSocket chat turn target explicit and validated.

- Structured WebSocket `user_message` payloads now require `conversationId`.
- `ChatHandler.ChatRequest` carries `conversationId`.
- `ChatHandler` validates explicit targets through
  `ConversationService.requireActiveOwnedConversationSession(userId, conversationId)`.
- Explicit invalid, archived, deleted, or foreign targets fail closed and do not fall back to
  `user:{userId}:current_conversation`.
- Legacy raw-text WebSocket messages still use the old current-conversation fallback for backward
  compatibility.
- Frontend chat sends the visible `conversationId` with each user message and creates a conversation
  before sending if none exists.
- Active generation lookup now supports `userId + clientId`, and stop/resume fallbacks prefer the
  requester client rather than a single per-user active generation.

## Recommended Decisions

These decisions are the recommended answers for the open design questions.

1. Structured WebSocket chat messages must carry an explicit `conversationId`.
2. The backend must treat that `conversationId` as the Chat Turn Target and validate ownership before
   creating a generation.
3. A missing `conversationId` in structured chat payloads should be rejected once the frontend sends
   explicit targets. If frontend and backend ship together, reject it immediately. Fallback remains
   only for legacy raw-text messages.
4. An explicit but invalid or foreign `conversationId` must fail closed. Do not fall back to
   `user:{userId}:current_conversation`.
5. `user:{userId}:current_conversation` may remain as a UI convenience for opening the app, but it
   must not be authoritative for structured message routing.
6. Stop/resume behavior should prefer explicit `generationId`, then a requester-client scoped active
   generation. A single per-user active generation is too broad for multiple tabs.

Domain terms recorded in `docs/reference/domain-language.md`: Chat Conversation, Chat Turn Target,
Conversation Selection.
Architectural decision recorded in `docs/adr/0008-use-explicit-chat-turn-targets.md`.

## Previous Behavior

The backend stores the active conversation in Redis:

```text
user:{userId}:current_conversation
```

`ChatHandler` uses that key when processing a WebSocket chat message. The frontend WebSocket payload
sends the message and optional reference focus, but does not send the intended `conversationId`.

As a result, the backend uses "whatever conversation is current for this user" at processing time,
not "the conversation visible in the tab that sent this message."

## Reproduction Scenario

1. User opens Tab A on conversation A.
2. User opens Tab B and switches to conversation B.
3. Tab B updates `user:{userId}:current_conversation` to conversation B.
4. User returns to Tab A and sends a message.
5. Backend reads the per-user Redis key and processes the Tab A message under conversation B.

Expected behavior: Tab A's message must be processed under conversation A.

Actual risk: Tab A's message can be persisted, scoped, and answered under conversation B.

The failure is especially important on the first message of an unlocked scoped conversation, because
the wrong conversation may be locked with the wrong evidence universe.

## Original Evidence In Code (Before Fix)

- `src/main/java/io/github/chzarles/paperloom/service/ChatHandler.java`
  - `getOrCreateConversationId()` read `user:{userId}:current_conversation`.
  - `processMessage()` did not receive an explicit `conversationId` from `ChatRequest`.

- `src/main/java/io/github/chzarles/paperloom/service/ConversationService.java`
  - `switchCurrentConversation()` overwrites `user:{userId}:current_conversation`.

- `src/main/java/io/github/chzarles/paperloom/handler/ChatWebSocketHandler.java`
  - Structured WebSocket messages parsed `message` and `referenceFocus`, but not `conversationId`.

- `frontend/src/views/chat/modules/input-box.vue`
  - The outgoing WebSocket payload sent `type`, `message`, and `referenceFocus`, but not `conversationId`.

- `src/main/java/io/github/chzarles/paperloom/service/ChatGenerationStateService.java`
  - Active generation was tracked by `chat:user:{userId}:active_generation`, so stop/resume behavior
    was also per user rather than per tab or per conversation.

- `frontend/src/store/modules/chat/index.ts`
  - `switchSession()` called the backend switch endpoint and updated shared current-conversation
    state before loading the selected conversation.

## Implementation Evidence

- `src/main/java/io/github/chzarles/paperloom/handler/ChatWebSocketHandler.java`
  - Parses `conversationId` from structured WebSocket messages.
  - Rejects structured chat messages that contain `message` but omit `conversationId`.

- `src/main/java/io/github/chzarles/paperloom/service/ChatHandler.java`
  - Carries `conversationId` on `ChatRequest`.
  - Uses explicit `conversationId` when present and does not read the Redis current-conversation key
    in that path.
  - Creates generation state with the requester `clientId`.
  - Resolves stop-without-generation by requester `clientId` when available.

- `src/main/java/io/github/chzarles/paperloom/service/ConversationService.java`
  - Exposes `requireActiveOwnedConversationSession(userId, conversationId)` for explicit target
    validation.

- `src/main/java/io/github/chzarles/paperloom/service/ChatGenerationStateService.java`
  - Stores active generation keys per user and per requester client.

- `frontend/src/views/chat/modules/input-box.vue`
  - Resolves or creates a target conversation before send.
  - Sends `conversationId` with every structured `user_message` payload.

- `frontend/src/store/modules/chat/index.ts`
  - Sends `clientId` when fetching active generation after reconnect.

## Related Existing Issue

GitHub issue #1, `WebSocket多设备登录消息丢失问题`, describes an older problem where one user's later
WebSocket connection could replace an earlier one. The current implementation has already moved to
multiple sessions per user and `clientId`-targeted delivery, so that issue is mostly stale.

The issue described in this document is newer and different: WebSocket delivery is now client-aware,
but chat routing still uses a per-user current conversation.

## Impact

Potential consequences:

- A message from one tab can be stored in another conversation.
- The wrong conversation scope can be locked on first message.
- Conversation memory can be updated for the wrong conversation.
- Reference follow-up resolution can use the wrong conversation's references.
- A stop request without `generationId` can stop the latest active generation for the user, not necessarily the tab's intended generation.

Non-impact based on current evidence:

- Cross-user conversation access remains guarded by `userId + conversationId` checks.
- Durable history queries use `findByUserIdAndConversationId...`.
- Generation polling validates the stored `userId`.

## Implemented Fix Contract

Make `conversationId` an explicit part of the WebSocket chat contract.

The canonical invariant:

```text
Every accepted chat turn has exactly one Chat Turn Target:
authenticated userId + explicit conversationId.
```

### Backend Contract

`ChatHandler.ChatRequest` now carries:

```java
String conversationId
```

For every structured chat message:

1. Parse `conversationId` from the payload.
2. Validate that the conversation belongs to the authenticated user.
3. Reject invalid, archived, deleted, or foreign conversations before creating generation state.
4. Process the message using that explicit conversation.
5. If absent, reject with a clear WebSocket error. Allow fallback only for legacy raw-text messages,
   not for structured `user_message` payloads.

The per-user Redis key may remain for UI convenience, but it must not be the source of truth for
WebSocket message routing once the frontend sends `conversationId`.

Do not auto-create a new conversation when an explicit `conversationId` is unknown. An explicit id is
a routing claim; if it cannot be validated, the turn must fail closed.

### Frontend Contract

Send the visible conversation id with every user message:

```json
{
  "type": "user_message",
  "conversationId": "current-visible-conversation-id",
  "message": "question text",
  "referenceFocus": null
}
```

If no conversation exists, create one before sending, load its scope, then send with the created
`conversationId`. The send button should not submit a structured chat payload until a target
conversation id exists.

Session switching can still update the user's remembered current conversation, but message send must
read the target from the tab's local chat store at send time and include it in the payload.

### Generation State

Keep `generationId` as the primary lookup key, but scope "active generation" by requester client:

```text
chat:user:{userId}:client:{clientId}:active_generation
```

At minimum, stop requests without a `generationId` should resolve against the requester `clientId`, not only the user.

Recommended resolution order for stop/resume:

1. Use explicit `generationId` when provided.
2. Else use active generation for `userId + clientId`.
3. Else return "no active generation for this client" rather than stopping another tab's work.

## Migration Applied

1. Added `conversationId` to frontend WebSocket payloads.
2. Parsed and stored it in `ChatHandler.ChatRequest`.
3. Added `ConversationService.requireActiveOwnedConversationSession(userId, conversationId)`.
4. Updated `ChatHandler` to use explicit `conversationId` for structured messages.
5. Kept the old per-user Redis fallback only for legacy plain-text messages.
6. Scoped active generation by requester client.
7. Added focused backend and frontend contract tests for the same-user multi-tab isolation bug.

## Regression Coverage Added

Backend unit tests:

- `ChatWebSocketHandlerTest`
  - structured payload preserves `conversationId` in `ChatRequest`.
  - structured payload without `conversationId` is rejected.

- `ChatHandlerProductHarnessTest`
  - explicit `conversationId` is used even when Redis current conversation points elsewhere.
  - invalid explicit `conversationId` is rejected and never falls back to the Redis current
    conversation.

- `ChatGenerationStateServiceTest`
  - active generation can be resolved per client.
  - terminal generation states clear matching client-scoped active generation keys.

- `ChatHandlerStopResponseTest`
  - stop-without-generation uses the requester client's active generation.
  - stop-without-generation does not cancel another client's active generation.

- `ChatControllerTest`
  - active generation endpoint uses client-scoped lookup when `clientId` is supplied.

- `ConversationServiceTest`
  - explicit target validation accepts active owned sessions.
  - explicit target validation rejects archived sessions.

Frontend tests:

- `frontend/tests/chat-input-payload-contract.test.ts`
  - input-box resolves a target conversation and sends `conversationId` in the WebSocket payload.

- `frontend/tests/chat-active-generation-client-contract.test.ts`
  - reconnect active-generation lookup includes the browser `clientId`.

Manual release smoke test still recommended:

- Open two conversations as the same user.
- Switch Tab B to conversation B.
- Send from Tab A.
- Verify the message and assistant answer are persisted under conversation A.

## Acceptance Criteria

- A WebSocket message from a tab is always processed under the `conversationId` visible in that tab.
- Switching conversations in another tab cannot change the target conversation of already-open tabs.
- Cross-user ownership checks still reject foreign conversation ids.
- An invalid explicit `conversationId` fails closed without fallback.
- Stop-without-generation behavior is scoped to the requester client, or the UI always sends
  `generationId`.
- Existing durable history and reference mapping behavior remains unchanged except for the corrected target conversation.

## Non-Goals

- Do not replace JWT authentication or the existing conversation/session tables.
- Do not change the conversation-scope locking model from ADR 0002.
- Do not use WebSocket connection id as durable conversation identity.
- Do not solve multi-instance WebSocket delivery in this fix; that remains a separate deployment
  concern.

## Open Follow-Up

WebSocket authentication currently carries the JWT in the URL path. That is not the root cause of
this session-isolation bug, but it should be reviewed separately because URL tokens are easier to
leak through logs, proxies, and browser history than header-based or short-lived ticket-based
authentication.
