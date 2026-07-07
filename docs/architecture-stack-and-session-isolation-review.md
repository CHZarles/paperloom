# Architecture Stack and Session Isolation Review

Date: 2026-07-07

## Executive Summary

The current technical stack does not need to be replaced for the near-term PaperLoom roadmap.
Spring AI, Spring AI Alibaba, and a database change are not required to solve the current product or
session-isolation risks.

The main architectural risk found in this review is not the AI framework or Elasticsearch. It is
same-user, multi-tab chat routing: a WebSocket chat turn can still depend on a mutable per-user
"current conversation" pointer. Cross-user durable data isolation appears mostly sound, but same-user
conversation correctness needs a targeted fix.

Recommended direction:

1. Keep the existing Spring Boot service stack.
2. Keep Elasticsearch for paper retrieval/search.
3. Do not introduce Spring AI or Spring AI Alibaba yet.
4. Fix WebSocket chat routing by requiring an explicit `conversationId` per structured chat message.
5. Treat the per-user current conversation as UI convenience only, not as authoritative routing
   state.

## Technology Stack Decision

### Spring AI

Spring AI can be useful when an application needs a general provider abstraction for chat models,
embeddings, vector stores, prompt templates, advisors, or common AI workflow primitives.

For this project, the current AI path is already domain-specific:

- paper-reading tools
- scoped retrieval
- evidence verification
- reference memory
- source quote tracing
- ReAct-style orchestration
- product-specific conversation semantics

Replacing this with Spring AI now would add migration cost without directly improving the main
system risk. Spring AI can remain a future option for a new bounded module or provider abstraction,
but it should not be introduced as a broad refactor at this stage.

Decision: **do not introduce Spring AI now**.

### Spring AI Alibaba

Spring AI Alibaba mainly helps Spring applications integrate Alibaba ecosystem AI capabilities and
model providers. It is useful when the system is intentionally standardizing on Alibaba Cloud model
services or wants Alibaba-specific Spring AI integrations.

This project does not currently need that abstraction to solve retrieval quality, evidence grounding,
or session isolation. Adding it now would increase framework surface area while leaving the core
conversation-routing issue untouched.

Decision: **do not introduce Spring AI Alibaba now**.

### Elasticsearch

Elasticsearch remains appropriate for this system because the product needs retrieval over paper
content, chunks, metadata, and search-oriented evidence. A relational database alone is not a good
replacement for full-text and retrieval workloads.

The better near-term work is to improve Elasticsearch usage rather than replace it:

- keep mappings explicit
- tune analyzers and ranking
- test hybrid retrieval behavior
- keep authoritative business records in the relational database
- use Elasticsearch as an index/search engine, not as the source of truth

Decision: **keep Elasticsearch**.

## Session Isolation Assessment

The current system has two different isolation layers:

- Cross-user isolation: durable conversation access is mostly protected by `userId + conversationId`
  checks.
- Same-user client isolation: weaker, because multiple tabs or devices can share one mutable
  per-user current-conversation pointer.

The risky pattern is:

```text
user:{userId}:current_conversation
```

If Tab A is viewing conversation A and Tab B switches to conversation B, Tab B can update the shared
current-conversation key. If Tab A then sends a WebSocket message without an explicit
`conversationId`, the backend can process Tab A's turn under conversation B.

This is not currently evidence of a cross-user leak, but it is a high-impact correctness bug for one
user's own conversations. It can persist messages, answers, memory, source scopes, and reference
follow-ups into the wrong conversation.

Decision: **same-user chat isolation depends on structured chat messages carrying an explicit
conversation target**. This branch applies that fix by sending and validating `conversationId` per
structured WebSocket turn.

## Required Fix

Make the chat turn target explicit:

```text
authenticated userId + explicit conversationId
```

Backend requirements:

1. Parse `conversationId` from structured WebSocket chat payloads.
2. Validate that the conversation belongs to the authenticated user.
3. Reject invalid, archived, deleted, or foreign conversation ids before generation starts.
4. Never fall back to `user:{userId}:current_conversation` when an explicit `conversationId` is
   invalid.
5. Keep the per-user current conversation only as UI convenience state.

Frontend requirements:

1. Send the visible `conversationId` with every structured chat message.
2. Create or select a conversation before submitting a message.
3. Ensure switching another tab does not alter the outgoing target of the current tab.

Generation-control requirements:

1. Prefer explicit `generationId` for stop/resume.
2. If no `generationId` is supplied, resolve active generation by requester `clientId`.
3. Avoid stopping another tab's generation through a single per-user active-generation key.

## Recommended Implementation Order

1. Add backend tests proving structured WebSocket payloads preserve `conversationId`.
2. Add handler tests proving explicit `conversationId` wins over Redis current-conversation state.
3. Add ownership validation for explicit conversation targets.
4. Update the frontend WebSocket payload to include `conversationId`.
5. Scope active generation lookup by requester client.
6. Add a two-tab regression test.

## Final Recommendation

Do not change the major technology stack right now. The highest-value fix is narrow and architectural:
make every chat turn carry an explicit, validated conversation target. After that is in place, the
existing Spring Boot plus Elasticsearch stack is a reasonable foundation for the current product.
