# Session Switch Cache Optimization

Date: 2026-07-26

## Problem

用户从一个已经加载过的 query/session 切走，再切回来时，仍然会看到明显加载等待。这个场景不应该重新走完整历史加载链路，因为同一个前端运行期间刚刚已经拿到过消息和 scope。

## Reproduction

用 Playwright mock 两个 session：

1. 进入 Chat，加载 `session-a` 的消息和 scope。
2. 点击侧边栏切到 `session-b`，加载 `session-b`。
3. 再点回 `session-a`。

优化前第 3 步仍会重新请求：

- `GET /users/conversation?conversationId=session-a&limit=15`
- `GET /users/conversations/session-a/scope`

所以用户会再次看到聊天区 loading，体感像“刚看过的 session 也重新加载”。

## Root Cause

前端 store 只有一个 `loadedConversationDetailsId`，不是按 session 缓存。`switchSession` 每次切换都会：

1. 清空 `list`
2. 清空 `currentScope`
3. 重置 `loadedConversationDetailsId`
4. 强制调用 `loadConversationDetails(..., { force: true })`

因此即使目标 session 刚刚加载过，也会重新打 DB-backed API。后端接口已经优化到几十毫秒，但前端仍会制造等待、清空视图、触发重排和滚动恢复。

## Optimization Plan

采用前端内存级 session details cache：

1. 每次成功加载 conversation details 后，把 `messages + scope + hasOlderMessages` 按 `conversationId` 缓存在 Pinia store。
2. 切走当前 session 前，先保存当前内存里的最新视图。
3. 切回已缓存 session 时，先同步恢复缓存，不清空聊天区，不重拉 history/scope。
4. 保留后端 `PUT /switch`，继续维护当前 session 语义。
5. 归档、删除、更新 scope、登出时丢弃相关缓存，避免错误复用。
6. cache 只保留最近 8 个 session，避免长期占用内存。

没有做 localStorage/IndexedDB 持久化缓存：用户抱怨的是“刚刚切走又切回来”，内存缓存足够，且不会引入跨登录、跨版本、跨权限的陈旧数据问题。

## Implementation

Changed files:

- `frontend/src/store/modules/chat/index.ts`
  - 新增 `conversationDetailsCache`。
  - 新增 `cacheConversationDetails` / `restoreCachedConversationDetails` / `discardConversationCache`。
  - `switchSession` 切走前保存当前 session，切回时优先恢复缓存。
  - `loadConversationDetails` 成功后写入 cache。
  - `loadOlderMessages` 后同步刷新当前 session cache。
  - `archiveSession` / `deleteSession` / `updateConversationScope` / `handleAuthReset` 清理缓存。

- `frontend/src/views/chat/modules/chat-list.vue`
  - session 切换时走 cache-aware 的 `loadCurrentConversationIfNeeded()`，不再强制 `{ force: true }` 绕过缓存。

- `frontend/tests/e2e/chat-initial-history-load.spec.ts`
  - 新增回切已加载 session 的回归测试。
  - 断言回切 `session-a` 后 `history/scope` 请求次数仍为 1。

- `frontend/tests/chat-reading-workbench-contract.test.ts`
  - 更新静态契约：session 切换必须走 cache-aware 路径。

## Verification

Commands:

```bash
pnpm --dir frontend typecheck
pnpm --dir frontend exec tsx tests/chat-session-sidebar-loading.test.ts
pnpm --dir frontend exec tsx tests/chat-reading-workbench-contract.test.ts
pnpm --dir frontend exec playwright test tests/e2e/chat-initial-history-load.spec.ts --project=chromium
git diff --check
```

Playwright result:

```text
2 passed
```

The new e2e verifies:

- first load of `session-a`: one history request, one scope request
- first load of `session-b`: one history request, one scope request
- switching back to already loaded `session-a`: no second history/scope request
- no empty hero/source selector is rendered during existing session restoration, so no `paper-collections` request

## Expected UX

Within the same browser tab/runtime, switching back to a recently opened query should render immediately from memory. Hard refresh still loads from MySQL-backed APIs, which remains the correct source of truth.
