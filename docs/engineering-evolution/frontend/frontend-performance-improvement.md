# Frontend Performance Improvement Proposal

- **Status:** Implemented
- **Date:** 2026-07-13
- **Scope:** `frontend/`, the frontend-facing conversation bootstrap APIs, and static asset delivery
- **Implementation in this change:** Core runtime, API, upload, delivery, and build-budget work completed

## Implementation Outcome

The plan was implemented on 2026-07-13 with the following results:

- Markdown/Shiki is no longer application-global. The renderer and its stylesheet load only when completed chat Markdown needs them, while streaming answers use a lightweight renderer.
- PDF preview and PDF.js load only after a preview is opened.
- Conversation lists now identify the current session, avoid per-session existence/count queries, and load scope/history in parallel without an initial switch request.
- The Knowledge Base sidebar loads session summaries without loading a transcript.
- User-info and session-index requests are deduplicated; normal route switches no longer refresh `/users/me`.
- WebSocket chunks are buffered to a 75 ms render cadence, and auto-scroll is bottom-aware and animation-frame-coalesced.
- Conversation history loads 15 records (30 messages) at a time and preserves the viewport when older messages are prepended.
- Research events are deduplicated in constant time, retained/rendered with explicit bounds, and presented from cached view models.
- File MD5 calculation runs in a Web Worker, and chunk uploads share a four-request aggregate concurrency limit.
- Nginx guidance now includes compression, SPA fallback, immutable caching for hashed assets, and revalidation for unhashed assets.
- Production builds emit a manifest and enforce Brotli JavaScript budgets. The implemented build reports 494.9 KB for login, 499.3 KB for the chat shell, and 555.1 KB for Knowledge Base.

Focused verification covered frontend type checking, production build/bundle budgets, the conversation service test suite, initial-history browser behavior, and the session-sidebar loading contract. Field RUM was not added because it requires a separate telemetry and privacy decision.

## 1. Executive Summary

The frontend has measurable main-thread stalls and avoidable route-loading work. The problem is not limited to Vite development mode: the production build also shows long tasks above one second under 4x CPU throttling, large initial JavaScript payloads, repeated full Markdown rendering while answers stream, and a serialized conversation bootstrap waterfall.

The recommended order of work is:

1. Establish automated performance scenarios and budgets.
2. Remove Markdown/Shiki and PDF.js from routes that do not need them.
3. Shorten the conversation and authentication request critical paths.
4. Batch streaming updates, render them cheaply, and centralize auto-scroll behavior.
5. Bound the amount of chat history and research-process UI retained in the DOM.
6. Move file hashing off the main thread and tune aggregate upload concurrency.

The first three implementation phases should produce the largest improvement. A framework rewrite is not justified; the current Vue/Vite stack can meet the proposed budgets with narrower loading boundaries and better control of reactive work.

## 2. Goals and Non-Goals

### Goals

- Make login, chat, and Knowledge Base routes visibly usable sooner on ordinary laptops.
- Keep scrolling, typing, navigation, and controls responsive while an answer streams.
- Keep long conversations responsive as message count and answer length grow.
- Avoid loading expensive capabilities before the user needs them.
- Remove avoidable frontend request waterfalls and duplicate requests.
- Add repeatable performance checks so regressions are caught before release.
- Preserve Markdown, code highlighting, citations, PDF evidence, reconnect behavior, and upload resume behavior.

### Non-Goals

- Replacing Vue, Pinia, Vite, or Naive UI.
- Redesigning the product or changing research-generation latency.
- Building a fully incremental Markdown parser before simpler batching is measured.
- Optimizing development-server module count as if it were a production metric.
- Changing source-evidence semantics, authorization, or conversation isolation.

## 3. Observed Baseline

These are local synthetic and read-only measurements collected on 2026-07-13 from the current workspace. Production builds were exercised in Chromium through Playwright/CDP; selected scenarios used 4x CPU throttling. API timings came from the current local runtime. They are diagnostic baselines, not production SLAs or field data.

### 3.1 Fresh Route Script Cost

| Route | Compressed JavaScript | Decoded JavaScript |
|---|---:|---:|
| Login | 947 KB | 3.02 MB |
| Chat | 961 KB | 3.07 MB |
| Knowledge Base | 1.18 MB | 3.77 MB |

The login route downloaded the Shiki runtime/WASM despite rendering no Markdown. The Knowledge Base route also included the PDF preview dependency chain before a preview was opened.

### 3.2 Main-Thread and Rendering Cost

| Scenario | Result |
|---|---|
| Empty production chat, normal CPU | LCP 0.73-1.02 s; total long-task time 0.43-0.66 s; longest task 237-307 ms |
| Empty production chat, 4x CPU | FCP 1.58 s; LCP 2.71 s; longest task 1.26 s |
| Knowledge Base, 4x CPU | LCP 2.93 s; total long-task time 2.67 s; longest task 1.24 s |
| Warm chat to Knowledge Base navigation, normal CPU | 365 ms; one 215 ms long task |
| Warm chat to Knowledge Base navigation, 4x CPU | 1.33 s; one 873 ms long task |
| Render 30 historical messages, normal CPU | 413 ms |
| Render 30 historical messages, 4x CPU | 1.45 s; longest task 610 ms |
| Render a 20,000-character answer with progress/artifacts, normal CPU | 250 ms |
| Same 20,000-character answer, 4x CPU | 1.10 s; maximum frame 487 ms |

Vite development mode amplifies the issue: the chat route loaded roughly 250 resources, 247 scripts, and 11.8 MB before settling, with the shell appearing after approximately 1.3-1.6 seconds. Those numbers are not proposed as release budgets, but they explain why local interaction feels particularly heavy.

### 3.3 Streaming Cost

A synthetic 60-chunk stream producing a 10,400-character answer caused:

- 62 `DOMParser` calls.
- 70 scroll calls.
- Repeated parsing and rendering of the complete accumulated Markdown string.
- A further complete render when completion metadata made citation mappings available.

CPU profiling attributed a material share of execution time to `vue-markdown-shiki` tokenization/rendering and repeated scrolling.

### 3.4 Conversation API Critical Path

| Request | Local elapsed time |
|---|---:|
| `GET /users/conversations` | 0.87 s for 11 sessions |
| `GET /users/conversations/current` | 0.114 s |
| Conversation scope | 0.120 s |
| Conversation history | 0.028 s |
| Paper list | 0.358 s |

The frontend currently serializes session list, current session, session switch, scope, and history work. The resulting critical path exceeds one second before Markdown rendering is considered. The conversation-list endpoint also warrants backend query profiling because 0.87 seconds for 11 rows is disproportionate.

## 4. Proposed Performance Budgets

Phase 0 should confirm and, where necessary, refine these budgets on CI hardware. They are intended to be release gates for a production build, not development-server targets.

| Area | Proposed budget |
|---|---|
| Login initial JavaScript | Less than 500 KB compressed; no Shiki, Markdown grammar, or PDF.js requests |
| Chat initial JavaScript | Less than 500 KB compressed before Markdown highlighting is needed |
| Knowledge Base initial JavaScript | Less than 700 KB compressed; no PDF.js runtime/worker before preview opens |
| Empty chat at 4x CPU | LCP at or below 2.5 s; no startup task longer than 200 ms |
| Warm chat to Knowledge Base navigation at 4x CPU | At or below 400 ms |
| Thirty-message history render at 4x CPU | At or below 500 ms |
| Streaming renderer | At most one visual commit per animation frame and no more than 20 commits per second |
| Syntax highlighting while streaming | No full Shiki pass per network chunk; one final pass after completion |
| Auto-scroll | At most once per animation frame; zero forced scrolls while the user is away from the bottom |
| Conversation bootstrap | No more than two critical-path requests; scope and history parallel once the active ID is known |
| Authentication | At most one in-flight `/users/me` request and no unconditional refresh on every route switch |
| Upload hashing at 4x CPU | No main-thread task longer than 100 ms; controls remain responsive |

## 5. Root Causes

### 5.1 Markdown and Shiki Are Application-Global

`frontend/src/main.ts` imports the Markdown stylesheet and installs the `vue-markdown-shiki` plugin for the complete application. As a result, login, administration, and library routes pay for a capability used primarily by chat and chat history. `frontend/src/views/chat/modules/chat-list.vue` and `frontend/src/views/chat-history/index.vue` also create Markdown providers; provider and highlighter ownership should be consolidated so only one route-scoped instance is initialized.

The production output contains a large Shiki runtime, WASM, grammar, and language asset surface. The highest-value bundle change is therefore architectural loading isolation, not another minifier setting.

### 5.2 Every Stream Chunk Invalidates the Complete Answer

`frontend/src/views/chat/modules/input-box.vue` appends each WebSocket chunk directly to reactive `assistant.content`. `frontend/src/views/chat/modules/chat-message.vue` then preprocesses the complete accumulated string for URLs and citations and passes it to `VueMarkdownIt`. The Markdown component reparses the answer, creates HTML, invokes `DOMParser`, and rebuilds the rendered tree.

Citation mappings are assigned at completion, so the complete answer is processed again even when its text has not changed. The cost grows with both chunk count and accumulated answer length.

### 5.3 Scrolling Is Coupled to Rendering

`frontend/src/views/chat/modules/chat-message.vue` calls `chatStore.scrollToBottom` from a computed getter. Computed evaluation should be pure; this side effect ties layout work to content processing. `frontend/src/views/chat/modules/chat-list.vue` adds another delayed scrolling path and watches the message list structurally.

The current behavior also forces the viewport downward after the user has scrolled up to inspect an earlier citation. This is both a responsiveness problem and an interaction defect.

### 5.4 Session Bootstrap Is a Serial Waterfall

`frontend/src/views/chat/modules/conversation-sidebar.vue` calls `loadSessions` on mount. In `frontend/src/store/modules/chat/index.ts`, initial loading can perform the following sequence:

```text
session list
-> current session
-> switch session
-> scope
-> history
```

Scope and history are fetched sequentially, and initial restoration sends a switch request even though the server has already identified its current session. The Knowledge Base mounts the same conversation sidebar, so it can initialize chat scope/history even though the route does not render a chat transcript.

### 5.5 PDF.js Is Eagerly Reachable From the Library Route

The static import chain is:

```text
knowledge-base/index.vue
-> file-preview.vue
-> pdf-document-viewer.vue
-> pdfjs-dist and its worker
```

The user pays the parsing and transfer cost before opening a document. PDF preview is an optional interaction and should be an asynchronous capability boundary.

### 5.6 Historical Messages Are Unbounded

`frontend/src/views/chat/modules/chat-list.vue` renders the complete message array with a `v-for`; every completed Markdown tree remains mounted. Rendering time consequently scales with message count and answer size. Index-based keys also make prepending older pages more expensive and less predictable than stable message identifiers would.

### 5.7 Research Events Still Scale With Total Event Count

The recent extraction of the research timeline into `frontend/src/views/chat/modules/research-process-panel.vue` is directionally correct because the full process is no longer rendered inside every message by default. Remaining costs are:

- Deduplication scans the full event array.
- Each append copies the full event array.
- Opening the panel renders every retained event.
- Formatting helpers are invoked repeatedly for the same event during a render.
- The transport contract has no explicit retained-event or research-round bound.

This is lower priority than Markdown and bootstrap work, but it will become visible in long research runs.

### 5.8 Authentication Refreshes More Often Than Necessary

`frontend/src/store/modules/route/index.ts` calls `authStore.initUserInfo()` on every logged-in route switch. Initial authenticated navigation also produced two `/users/me` requests in profiling. User identity should be cached with explicit invalidation and in-flight request deduplication rather than refreshed as a navigation side effect.

### 5.9 Static Delivery Has No Explicit Performance Policy

The sample `docs/nginx.conf` does not declare gzip/Brotli behavior or immutable caching for hashed assets. Production may have an upstream CDN policy, so this must be verified rather than assumed. If the sample is deployed directly, cold transfers and repeat navigation will be unnecessarily expensive.

### 5.10 Upload Preparation Can Occupy the Main Thread

`frontend/src/utils/common.ts` calculates MD5 in 5 MB blocks with SparkMD5 on the UI thread. `frontend/src/store/modules/knowledge-base/index.ts` permits up to four chunk requests per file and up to three active files, creating a possible aggregate of 12 requests. Hashing can produce UI stalls, while aggressive upload concurrency can contend with application API and preview traffic.

## 6. Proposed Architecture

### 6.1 Route-Owned Heavy Capabilities

Create explicit asynchronous boundaries for optional, expensive features:

- Keep the application shell independent of Markdown/Shiki.
- Load the chat Markdown renderer only from routes/components that display Markdown.
- Initialize one highlighter instance for the active chat route rather than one application plugin plus nested providers.
- Render ordinary Markdown without Shiki while text is streaming.
- Load Shiki only when a completed answer contains a fenced code block, or prewarm it during browser idle time after the chat shell is interactive.
- Load only the languages detected in the answer when the library permits it.
- Dynamically import `FilePreview` and the PDF viewer when the preview is first opened.
- Show an existing lightweight loading state during the first asynchronous import.

Before replacing `vue-markdown-shiki`, run a short spike to determine whether it can satisfy route-scoped initialization, lazy language loading, and final-only highlighting. Retain it if it meets the budgets; use the already-present Markdown tooling behind a local renderer interface only if the library prevents those boundaries.

### 6.2 Two-Stage Streaming Renderer

Separate transport frequency from render frequency:

1. Append incoming WebSocket chunks to a non-rendered buffer.
2. Flush the buffer to reactive state every 50-100 ms, capped at one flush per animation frame.
3. During generation, use a lightweight Markdown path without syntax highlighting and without citation enrichment that depends on completion metadata.
4. On completion, apply reference mappings and perform one full, highlighted render.
5. Cache the immutable compiled result for completed messages, preserving the current sanitization and delegated citation interaction.

This avoids the complexity of a true incremental Markdown parser unless batching and final-only highlighting still fail the budgets. Only the active assistant message should be eligible for streaming rerenders; completed messages should remain stable.

### 6.3 One Scroll Coordinator

Make the chat list the sole owner of scrolling:

- Remove scroll side effects from computed values and message components.
- Track a bottom sentinel or the distance from the scroll container's bottom.
- Auto-scroll only while the user is near the bottom.
- Coalesce requests with `requestAnimationFrame`.
- Stop following when the user scrolls upward; resume only after they return to the bottom or invoke an explicit jump-to-latest control.
- Preserve the visible anchor when older history is prepended.

### 6.4 Route-Specific Conversation Loading

Split the current all-in-one store action into independent responsibilities:

- `loadSessionIndex`: fetch a paginated summary list for sidebar navigation.
- `resolveActiveConversation`: determine the active ID without loading content.
- `loadConversationDetails`: fetch scope and the latest history page for the chat route.
- `switchConversation`: mutate the server's active conversation only after an explicit user selection.

Preferred API shape:

- Return the current conversation ID with the first session page, eliminating the separate current-session request.
- Alternatively add one bootstrap endpoint returning current ID, first session page, scope, and latest message page.
- Once an ID is known, request scope and history in parallel.
- Do not request scope/history from the Knowledge Base route solely because its shared sidebar mounted.
- Paginate/cursor the session index and profile the server query behind `/users/conversations`.
- Deduplicate in-flight loads and ignore/cancel stale results after rapid session switches.

Authentication should follow the same pattern: one in-flight user-info promise, a short freshness policy, and explicit invalidation after login, logout, account update, role change, or recharge completion.

### 6.5 Bounded History and Process Data

Use progressive loading before adopting complex virtualization:

- Fetch and mount only the latest 20-30 messages initially.
- Load older pages when the user requests them or reaches the top.
- Use stable keys based on persisted record ID, generation ID, or a stable local message ID.
- Preserve scroll position when prepending a page.
- Memoize completed message rendering by content plus citation-mapping version.
- If a 100-message scenario remains over budget after pagination and caching, add variable-height windowing with measured overscan.

For research progress:

- Normalize events by sequence in a keyed structure for constant-time deduplication.
- Keep the lightweight latest-status summary separate from full event detail.
- Mount detailed result rows only while the process panel is open.
- Compute event presentation data once per event rather than repeatedly in the template.
- Define a retained-detail limit in the frontend/server contract while preserving an exportable or server-side complete trace where required.

### 6.6 Background Upload Work

- Move SparkMD5 hashing into a Web Worker and report progress without transferring duplicate buffers.
- Centralize upload scheduling so aggregate chunk concurrency is explicit rather than the product of per-file and per-queue limits.
- Begin with four aggregate chunk requests, then tune from measurements instead of defaulting to twelve.
- Reduce concurrency when the browser reports a constrained connection, when document preview is active, or when request latency rises materially.
- Coalesce upload-progress UI updates to one animation frame.

### 6.7 Static Asset Delivery

The deployment policy should explicitly provide:

- Brotli where supported and gzip as fallback for JavaScript, CSS, JSON, SVG, WASM, and compatible text assets.
- `Cache-Control: public, max-age=31536000, immutable` for content-hashed assets.
- Revalidation or `no-cache` for `index.html` so new deployments are discovered.
- Correct MIME types for `.mjs` and `.wasm`.
- Verification that dynamically imported chunks and workers use stable, hashed URLs.

Configuration should be applied at the actual production edge; changing only the repository's sample Nginx file is not sufficient evidence that users receive it.

## 7. Phased Delivery Plan

Estimates below are indicative engineering time and exclude product QA scheduling.

### Phase 0: Measurement and Guardrails (1-2 days)

Deliverables:

- Production-build Playwright scenarios for login, empty chat, Knowledge Base, warm navigation, 30-message history, long-answer streaming, and first PDF open.
- Normal-CPU and 4x-CPU runs with at least three samples; report median and worst sample.
- Captured FCP/LCP, long tasks, resource bytes, route transition time, renderer commits, and scroll calls.
- A route bundle report that fails CI when the agreed budgets are exceeded.
- A checked-in baseline result artifact with machine/context metadata.

Exit criteria:

- The measurements in this proposal can be reproduced within an agreed tolerance.
- Each subsequent phase has a before/after comparison rather than an anecdotal assessment.

### Phase 1: Remove Startup Blockers (3-5 days)

Deliverables:

- Route-scoped Markdown ownership and one highlighter instance.
- No Shiki/WASM/grammar request on login or other non-Markdown routes.
- Asynchronous PDF preview/PDF.js loading.
- Verified compression and immutable hashed-asset caching at the deployed edge.

Exit criteria:

- Login, chat, and Knowledge Base meet their initial-script budgets.
- PDF.js is absent from Knowledge Base network activity until preview opens.
- Markdown, code blocks, links, citations, and PDF preview pass regression tests.

### Phase 2: Shorten Bootstrap and Navigation (3-6 days)

Deliverables:

- Separate session-index and chat-detail actions.
- Current-session information folded into the session response or a dedicated bootstrap endpoint.
- Parallel scope/history fetches.
- No transcript fetch caused by mounting the Knowledge Base sidebar.
- In-flight deduplication for sessions and user information.
- Paginated session response and server-side query profiling.

Exit criteria:

- Initial chat has at most two critical-path requests.
- Knowledge Base does not request chat scope or history.
- A normal route switch does not issue `/users/me`.
- Rapid session switching cannot display stale history or scope.
- Warm chat-to-library navigation meets the 400 ms 4x-CPU budget.

### Phase 3: Make Streaming Responsive (4-7 days)

Deliverables:

- Buffered stream commits.
- Lightweight streaming Markdown and final-only Shiki/citation rendering.
- Pure computed values with scrolling removed from message rendering.
- One bottom-aware, animation-frame-coalesced scroll coordinator.
- Cached rendering for completed messages.

Exit criteria:

- The 60-chunk/10,400-character scenario stays within render and scroll budgets.
- The 20,000-character scenario has no post-start task above 200 ms at 4x CPU.
- Scrolling upward during generation is never overridden.
- Completion, stop, error, reconnect, citations, and copy behavior remain correct.

### Phase 4: Bound Long-Session Work (4-8 days)

Deliverables:

- Paginated history with the latest page mounted first.
- Stable message identity and scroll-anchor preservation.
- Keyed research-progress normalization and bounded detailed rendering.
- Variable-height message windowing only if pagination and caching do not meet the budget.

Exit criteria:

- Initial work remains approximately bounded when stored history grows from 30 to 100 messages.
- The 30-message rendering budget is met.
- Prepending history does not visibly jump the viewport.
- Browser find, keyboard navigation, citations, and accessibility remain usable.

### Phase 5: Upload Responsiveness and Field Validation (2-4 days)

Deliverables:

- Worker-based MD5 calculation.
- Explicit aggregate upload scheduling and throttled progress updates.
- Lightweight real-user measurement for route timing and long tasks, subject to privacy review.

Exit criteria:

- Hashing meets the main-thread budget at 4x CPU.
- Upload, cancellation, resume, navigation, and PDF preview remain responsive during multi-file tests.
- Field measurements confirm that synthetic improvements translate to representative user devices.

## 8. Verification Plan

### Performance Scenarios

Run against a production build with deterministic fixture data:

1. Cold unauthenticated login.
2. Cold authenticated empty chat.
3. Cold Knowledge Base with ten papers.
4. Warm chat to Knowledge Base and return navigation.
5. Initial history with 30 messages, including long answers and code blocks.
6. Prepend two older history pages while preserving the viewport.
7. Stream 60 chunks into a roughly 10 KB answer.
8. Complete the answer with citation mappings and code fences.
9. Open the first PDF preview, then reopen it from a warm cache.
10. Hash and upload multiple large fixture files while typing, scrolling, and navigating.

Use a read-only live smoke test separately to expose backend latency, but keep CI gates deterministic and mocked where external services would add noise.

### Functional Regression Coverage

- Markdown paragraphs, lists, tables, links, inline code, fenced code, and formulas.
- Citation link creation before/after completion metadata and evidence-panel navigation.
- WebSocket start, chunk, completion, stop, error, tool, progress, and reconnect flows.
- Auto-scroll following, user opt-out, jump-to-latest, and history prepend.
- Session create, switch, archive, unarchive, delete, and scoped conversation creation.
- Authentication invalidation after identity-changing actions.
- PDF page targeting, text search/highlighting, download, close/reopen, and errors.
- Upload duplicate detection, resume, failure, cancellation, and progress reporting.

### Release Evidence

Each phase should attach:

- Before/after resource waterfall.
- Before/after production bundle table.
- Performance trace for the affected scenario.
- Budget result summary from CI.
- Functional test result summary.

## 9. Risks and Mitigations

| Risk | Mitigation |
|---|---|
| Lazy Markdown causes a visible first-use delay | Preload after the chat shell is interactive or when a response starts; keep a lightweight streaming renderer visible |
| Final rendering changes answer layout | Preserve the scroll anchor and perform the final swap once, after completion |
| Cached Markdown bypasses sanitization or stale citation mappings | Cache only the existing sanitized renderer result and include citation-mapping version in the cache key |
| Virtualization breaks variable-height messages, browser find, or accessibility | Implement pagination and caching first; add windowing only after the decision gate |
| A combined bootstrap endpoint creates consistency issues | Return one server-side snapshot/version and retain a fallback to separate requests during rollout |
| Lazy PDF loading makes first preview slower | Show immediate preview chrome/loading feedback and optionally prefetch during idle time on the library route |
| Immutable caching serves stale deployments | Cache only content-hashed assets immutably; revalidate `index.html` |
| A Worker conflicts with CSP or deployment paths | Add a production-build Worker smoke test and verify worker MIME/CSP rules before rollout |
| Lower upload concurrency reduces peak throughput | Measure total completion time and UI responsiveness together; tune the scheduler rather than assuming maximum parallelism is best |

## 10. Decision Gates

1. After Phase 1, inspect the network waterfall. If Shiki is absent from non-Markdown routes and the bundle budgets are met, do not replace the Markdown library merely for architectural neatness.
2. After Phase 3, rerun long-answer and 60-chunk scenarios. If batching and final-only highlighting meet the budgets, do not build a custom incremental Markdown parser.
3. After history pagination and completed-message caching, test 100-message sessions. Add virtualization only if measured rendering or retained-DOM cost still exceeds the budget.
4. Treat development-server module count as a developer-experience concern only after production budgets are met.

## 11. Recommended First Implementation Slice

The smallest high-value slice is:

1. Add the production performance scenarios and route bundle accounting.
2. Remove global Markdown/Shiki initialization and verify that login no longer loads it.
3. Dynamically load PDF preview and verify that library startup no longer loads PDF.js.
4. Separate `loadSessionIndex` from conversation detail loading so the Knowledge Base sidebar cannot trigger history work.
5. Batch streaming commits and remove scrolling from the message computed getter.

This slice directly addresses all three measured bottleneck classes: initial transfer/parse cost, network critical-path latency, and streaming main-thread stalls. It also creates objective evidence before the more complex history-windowing work begins.
