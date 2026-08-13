# Chat Retry 与 Cancel 加固记录

## 1. 背景

2026-08-12 将线上 Research Harness 从 HTTP 模式切换到 Redis Streams 后，重新检查了实时状态、
用户重新生成和分布式取消链路。主流程已经可以工作，但代码审查发现两个边界问题：

1. 前端从 MySQL 加载历史回答后仍会显示“重新生成”，而 Retry 后端只从 TTL 为 30 分钟的
   Redis Generation Snapshot 读取父任务。快照过期后，按钮仍可点击，但请求会失败。
2. Cancel 携带 `generationId`，后端收到后直接设置本地停止标志和 Redis Cancel Key，没有先证明
   该 Generation 属于当前登录用户。UUID 很难猜不是授权校验。

这两个问题分别属于“持久化数据与临时状态生命周期不一致”和“对象级授权缺失”。

## 2. 目标行为

### 2.1 Retry

- Redis 快照存在时继续走当前快速路径。
- 快照过期后，使用 MySQL 中按 `generationId + userId` 查询到的持久化回答恢复 Retry 上下文。
- 仍然只允许重试已完成且属于当前用户的回答。
- 新 Retry 创建新的 `generationId` 和 Revision，但复用原 `answerSlotId`。
- 默认最多允许每个 Answer Slot 重新生成 3 次。

不采用“把 Redis TTL 改得很长”。Redis 保存的是运行态，MySQL 保存的是业务事实；延长 TTL 只会延后
问题，同时增加 Redis 占用，不能解决历史数据的正确来源问题。

### 2.2 Cancel

- 后端必须用 `generationId + userId` 校验对象归属。
- 只允许取消 `STREAMING` Generation；已完成、失败或已取消的任务不再写 Cancel Key。
- 校验通过后才设置本地停止标志、Redis Cancel Key 和 `CANCELLED` 状态。
- Worker 继续在模型调用和工具调用边界协作式检查 Cancel Key。

不依赖内部 WebSocket 指令 Token 代替授权。该 Token 只能说明请求来自产品前端，不能证明当前用户
拥有目标 Generation。

## 3. 实现计划

### Phase 1：修复 Cancel 所有权校验（已完成）

1. 在 `ChatGenerationStateService` 提供“按 Generation ID 和 User ID 获取并校验运行态”的现有能力。
2. `ChatHandler.stopResponse` 在任何副作用发生前执行归属和状态校验。
3. 无权访问、Generation 不存在或已经终态时，不写 Redis Cancel Key。
4. 增加回归检查：用户 A 不能取消用户 B 的任务；合法用户可取消自己的 STREAMING 任务。

### Phase 2：让历史回答可以 Retry（已完成）

1. 已修复历史消息缺少 `status=finished`，页面刷新后可继续显示 Retry 入口。
2. `ConversationService` 已增加按 `generationId + userId` 构造 Retry Context 的持久化路径。
3. `ChatHandler.retryGeneration` 已在 Redis Snapshot 不存在时回退 MySQL。
4. 两条路径共用相同的 Revision 上限、Conversation 所有权和 Active Generation 检查。
5. 已增加回归检查：删除或模拟过期 Redis Snapshot 后，已持久化回答仍可开始新的 Generation。

### Phase 3：真实链路验收

1. 在线上完成一次正常问答，记录 Generation ID。
2. 验证正常 Cancel：状态变为 `CANCELLED`，Worker 最终 `XACK + XDEL`，队列 pending 回到 0。
3. 验证越权 Cancel：另一个用户请求同一 Generation，不产生 Cancel Key，不影响原任务。
4. 验证历史 Retry：让 Redis Snapshot 不可用后重试已持久化回答，新 Revision 成功完成。
5. 检查前端只显示一个 Answer Slot，版本历史中保留旧 Revision。

## 4. 证据记录

| 时间 | 动作 | 结果 |
| --- | --- | --- |
| 2026-08-12 | 追踪前端 Retry、Java Generation State、MySQL Conversation Revision | 确认历史 UI 数据来自 MySQL，而 Retry 父任务只来自 30 分钟 Redis Snapshot |
| 2026-08-12 | 追踪 WebSocket Stop 到 Redis Cancel Key | 确认写 Cancel Key 前缺少 `generationId + userId` 对象归属校验 |
| 2026-08-12 | 新增跨用户 Cancel 回归测试并先运行失败测试 | 用户 A 指定用户 B 的 Generation 后，现有代码仍调用 `markCancelled`，测试准确复现越权取消 |
| 2026-08-12 | 在 `ChatHandler.stopResponse` 的任何副作用前复用 `getGenerationForUser`，并要求状态为 `STREAMING` | 跨用户、未知和终态 Generation 均直接返回；合法用户取消路径保持不变 |
| 2026-08-12 | 运行 `mvn -q -Dtest=ChatHandlerStopResponseTest test` | 3 个 Cancel 定向测试全部通过，完成 Red -> Green |
| 2026-08-12 | 发现历史消息缺少前端要求的 `status` 字段 | 刷新或重新进入 Session 后，所有历史 assistant 消息都无法满足 Retry 的显示条件 |
| 2026-08-12 | `ConversationService.buildMessage` 为持久化 assistant 消息补 `status=finished`，并运行两个定向测试类 | `ConversationServiceTest`、`ChatHandlerStopResponseTest` 全部通过 |
| 2026-08-12 | Phase 2 前端入口修复 | 已完成；历史 assistant 消息会返回 `status=finished` |
| 2026-08-12 | 新增 `prepareUserRetry` 的 MySQL generation 回退红灯测试并运行 | 测试按预期失败：当时的方法只接受 `conversationRecordId`，传入 `null` 时返回空；证明回退入口尚未实现 |
| 2026-08-12 | 在 `ConversationService.prepareUserRetry` 中增加最小回退：有记录 ID 按记录查询，无记录 ID 按 `generationId + userId` 查询 | 两个上下文构造测试通过；MySQL 回退能力已具备 |
| 2026-08-12 | 调整 `ChatHandler.retryGeneration`：Redis Snapshot 存在时继续校验状态并使用记录 ID；Snapshot 缺失时将 `generationId` 和 `null` 记录 ID 交给 `prepareUserRetry` | 新增 Redis 过期场景测试通过；Retry 已能进入 MySQL 上下文恢复路径 |
| 2026-08-12 | 运行 `ConversationServiceTest`、`ChatHandlerProductHarnessTest`、`ChatHandlerStopResponseTest` 定向回归 | 命令退出码为 0；正常 Redis Retry、Redis 过期 MySQL 回退、版本构造和 Cancel 回归均通过 |
| 2026-08-12 | 检查 Redis 与 MySQL 两条路径的参数和权限边界，并在过期测试中断言不会伪造记录 ID | Redis 路径仍传递真实 `conversationRecordId`；过期路径只传 `generationId + userId`，Revision 上限继续由同一个 `prepareUserRetry` 执行 |
| 2026-08-12 | 推送 `cf9abfe`，服务器 fast-forward、构建并重启 `paperloom-backend.service` | 线上运行版本为 `cf9abfe`；Backend 和 4 个 Redis Worker 为 `active`，公网首页 `200`，未登录 API `403` |
| 待更新 | 线上真实链路验收 | 待完成 |

### 实现过程（2026-08-12）

本轮没有先改 Redis TTL，而是先建立一个能准确复现问题的红灯测试：

```text
Redis 中找不到 parent generation snapshot
MySQL 中存在同用户、同 generationId 的已完成 Conversation
调用 Retry
预期：成功构造 ConversationRetryContext，而不是返回“原回答不存在”
```

该测试证明问题确实是“Redis 运行态过期后没有持久化回退”，而不是其他 Retry 问题。随后实现
最小回退：按 `generationId + userId` 查询 MySQL，复用现有 `prepareUserRetry` 的版本上限、
Answer Slot 和上下文构造逻辑。

### 代码定位结果

- `ChatHandler.retryGeneration` 在 `getGenerationForUser(...)` 为空时，将空记录 ID 交给
  `prepareUserRetry(...)`，由后者按 `generationId + userId` 查询 MySQL。
- `ConversationRepository` 已经存在 `findFirstByGenerationIdAndUserId(...)`，因此不需要新增查询接口。
- `ConversationService.prepareUserRetry(...)` 已经负责用户归属、Revision 上限、Answer Slot 和上下文
  字段构造；优先复用，不复制一套 Retry 规则。
- 当时缺的不是 MySQL 查询能力，而是“Redis 找不到时，如何把 MySQL 查到的记录交给现有
  `prepareUserRetry`”这一小段连接逻辑；该连接现已实现。

## 5. 面试表达草稿

> 在 Redis Worker 上线后，我对实时状态、重新生成和取消链路做了端到端审查。发现 Retry 的父任务
> 依赖 30 分钟 Redis 快照，但历史回答来自 MySQL，形成生命周期不一致；同时 Cancel 只校验了
> WebSocket 指令，没有校验 Generation 的用户归属。我把运行态继续留在 Redis，把可重试的业务事实
> 回退到 MySQL，并在写 Cancel Key 前增加对象级授权和状态机校验。随后通过跨用户取消、快照过期
> Retry 和真实 Redis Worker 链路验证，确保安全性和历史功能同时成立。

最终面试表述中的“通过验证”和性能、正确性数据，只填写实际执行结果，不预先编造。

## 6. Retry 版本字段速记

Retry 不会物理覆盖历史答案，而是新增一条记录，并用下面几个字段组织版本：

| 字段 | 含义 | Retry 后的变化 |
| --- | --- | --- |
| `conversationRecordId` | 某一条具体数据库记录的唯一 ID | 新增记录，ID 变化 |
| `answerSlotId` | 同一个答案位置的 ID | 保持不变 |
| `answerRevision` | 该答案位置的版本号 | 递增，例如 1 → 2 |
| `currentRevision` | 是否是当前展示版本 | 旧版本改为 `false`，新版本为 `true` |
| `forkedFromConversationRecordId` | 新版本从哪条数据库记录分叉 | 指向被 Retry 的旧记录 |
| `generationId` | 当前生成任务的 ID | 创建新的 Generation ID |
| `retryOfGenerationId` | 本次 Retry 针对的旧生成任务 ID | 指向旧 Generation ID |

示例：

```text
第一次回答：record=101, slot=101, revision=1, current=true, generation=gen-a
Retry 后：  record=205, slot=101, revision=2, current=true, generation=gen-b,
            forkedFrom=101, retryOfGenerationId=gen-a
旧记录：    record=101, slot=101, revision=1, current=false
```

因此，聊天页面只查询 `currentRevision=true`，默认显示最新答案；历史版本查询不限制该字段，
所以旧答案仍可用于审计、查看或恢复。事务保证切换当前版本和保存新版本要么同时成功，要么同时回滚。

面试表达：

> Retry 采用版本化而不是覆盖更新。`answerSlotId` 标识同一个回答位置，`answerRevision` 标识版本，
> `currentRevision` 控制前台展示；同时用 `forkedFromConversationRecordId` 和 `retryOfGenerationId`
> 分别保存数据库记录层和生成任务层的父子关系，因此历史可追溯、当前展示简单。

## 7. 逐步理解记录

这次讨论按“小白先建立词汇，再看代码”的顺序梳理了下面的结论：

1. **Retry 是重新生成，不是继续旧任务。** 它创建新的 `generationId`、新的 Redis Job，
   但复用原来的 `answerSlotId`，所以新答案会回到原来的聊天位置。
2. **一个 Session 不只允许 Retry 最后一条回答。** 只要回答已经完成、属于当前用户，
   就可以对对应的 Answer Slot Retry；前端按钮是否出现还取决于消息是否带有 `status=finished`。
3. **Redis 和 MySQL 分工不同。** Redis Generation Snapshot 保存短期运行态，TTL 为 30 分钟；
   MySQL Conversation 保存正式回答和版本事实。Redis 过期不应该导致历史回答失去 Retry 能力，
   所以后端需要 Redis 优先、MySQL 回退。
4. **`currentRevision` 不是答案内容，而是展示指针。** `true` 表示当前版本，`false` 表示保留的历史版本。
5. **字段要分层理解。** `conversationRecordId` 是一条记录的 ID；`answerSlotId` 是答案位置；
   `answerRevision` 是该位置的版本号；`forkedFromConversationRecordId` 是数据库记录父子关系；
   `generationId` 和 `retryOfGenerationId` 是生成任务及其 Retry 关系。
6. **为什么保留旧版本。** 便于审计、查看历史、恢复，以及证明 Retry 没有静默篡改原答案；
   前台只查询当前版本，因此用户通常只看到最新答案。
7. **事务保证切换的一致性。** 旧版本标记为非当前和新版本插入处于同一个事务中，失败时一起回滚，
   避免出现“旧答案不显示、新答案也没保存”的中间状态。

### 面试故事的最短版本

> 我先从用户反馈定位到 Retry 按钮和历史数据的关系，再沿前端、Java Handler、Redis Generation
> State 和 MySQL Conversation 追踪完整链路。发现运行态依赖有 TTL 的 Redis，而历史事实在 MySQL，
> 两者生命周期不一致；同时 Retry 使用版本化记录而不是覆盖原答案。最终方案是 Redis 优先、MySQL
> 兜底，使用 `answerSlotId + answerRevision + currentRevision` 管理展示和历史，并保留父记录与旧
> Generation 关系，保证可追溯。历史消息 `status` 修复、Cancel 所有权校验和 MySQL Retry 回退
> 均已完成定向回归；线上真实历史 Retry 仍需单独验收。
