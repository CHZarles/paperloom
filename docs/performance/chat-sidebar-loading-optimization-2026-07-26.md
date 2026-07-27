# Chat Sidebar Loading Optimization

Date: 2026-07-26

## Problem

每次进入前端 Chat 页面时，左侧 query/session 列表的加载态持续约 1.2s 到 1.5s。页面本身没有复杂渲染，慢点集中在侧边栏初始化请求。

## Reproduction

用 dev backend 和本地 MySQL 复现，登录后连续请求侧边栏相关接口：

| Endpoint | Before avg | After avg |
| --- | ---: | ---: |
| `GET /api/v1/users/conversations` | 1301 ms | 99 ms |
| `GET /api/v1/users/conversations/current` | 1358 ms | 65 ms |
| `GET /api/v1/users/conversations/{id}/scope` | 1370 ms | 61 ms |

Playwright 复查前端加载时，Vite dev server 下 `users/conversations` 为 72 ms，`scope` 为 76 ms；剩余首屏时间主要来自 dev server 模块加载，不是侧边栏 API。

二次复查时还发现一个前端放大因素：Chat 页面在 session index 第一次返回前会短暂渲染空态 hero input，`SessionScopePicker` 随之发起 `paper-collections` 请求。真实环境中它会增加首屏并发请求；测试环境中如果没有 mock 还会触发 403 并把页面踢回登录页。这个请求和“已有 query 的会话恢复”无关。

## Root Cause

侧边栏列表和当前 scope 都会展示 `sourcePaperCount`。当会话 scope 是 `AUTO_LIBRARY` 时，后端每次请求都会计算“当前用户可搜索论文数”。

旧实现的问题：

- 先取用户所有可访问论文。
- 对每篇论文重复执行 `canAccess`。
- 对每篇论文调用 `isSearchable(paper)`，内部再查一次 reading model。
- 又对每篇论文额外调用 `findFirstByPaperIdAndIsCurrentTrue` 检查 ready model。
- `isSearchable(model)` 还会反复读取 active retrieval contract。

当前库 88 篇论文时，单次侧边栏请求会变成大量小 SQL 往返，所以稳定慢 1 秒以上。

## Optimization Plan

采用后端批处理，前端设计不变：

1. 可访问论文只取 ID，不加载完整 `Paper` 对象。
2. 搜索可用性检查改成一条 reading model 批量查询。
3. active retrieval contract 每次批量计算只读取一次。
4. 保持 `sourcePaperCount` 语义不变：只统计当前用户可访问、当前 reading model ready、retrieval index ready、contract 当前有效、indexed location count > 0 的论文。
5. 前端在首次 session index 完成前不渲染空态 hero input，避免启动无关的 `paper-collections` 请求。

没有采用纯前端缓存作为主方案，因为慢点已由 HTTP timing 和 Hibernate SQL 证明在后端；前端缓存只能掩盖重复进入，不能解决首次加载。

## Implementation

Changed files:

- `ConversationScopeService`
  - `autoLibraryReadablePaperCount` 改为基于可访问 paper ID 批量统计。
  - 删除重复的 per-paper current-ready 检查。

- `PaperAccessService`
  - 新增 `accessiblePaperIds(userId)`，返回用户自有和已发布论文 ID。

- `PaperRepository`
  - 新增 `findDistinctPaperIdsByUserId`。

- `PaperPublicationRepository`
  - 新增 `findAllPaperIds`。

- `PaperReadingModelRepository`
  - 新增 `findSearchableCurrentPaperIds`，用 DB 一次性过滤当前可搜索 reading model。

- `PaperSearchabilityService`
  - 新增 `searchablePaperIdsById`。
  - 批量路径改为一次读取 active contract，一条 SQL 取 searchable paper IDs。

- `ChatList` / `ChatStore`
  - 新增 `sessionsLoaded`，首个 session index 请求完成前保持 chat 主区加载态。
  - 空态 hero input 只在确认没有 session 后渲染，避免已有历史会话加载时额外请求 paper collections。

## Verification

Commands:

```bash
mvn -q -DskipTests test-compile
mvn -q -Dtest=PaperSearchabilityServiceTest,ConversationScopeServiceTest,ConversationServiceTest,ConversationSessionScopeControllerTest test
pnpm exec tsx tests/chat-session-sidebar-loading.test.ts
pnpm exec playwright test tests/e2e/chat-initial-history-load.spec.ts --project=chromium
git diff --check
```

Runtime checks after restart:

```text
GET /api/v1/users/conversations                 avg 99 ms
GET /api/v1/users/conversations/current         avg 65 ms
GET /api/v1/users/conversations/{id}/scope      avg 61 ms
```

Latest revalidation:

```text
GET /api/v1/users/conversations                 avg 75 ms
GET /api/v1/users/conversations/{id}/scope      avg 67 ms
```

Hibernate log after optimization shows the sidebar list path now uses one `file_upload` ID query, one `paper_publications` ID query, one active contract query, and one batched `paper_reading_models` query, instead of per-paper loops.

The Playwright entry test now also asserts that restoring an existing current session does not request `paper-collections`; that request is only needed when the user is actually on the empty/new-query source selector.
