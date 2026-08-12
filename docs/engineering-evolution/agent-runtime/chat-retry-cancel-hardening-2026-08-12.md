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

### Phase 2：让历史回答可以 Retry（部分完成）

1. 已修复历史消息缺少 `status=finished`，页面刷新后可继续显示 Retry 入口。
2. 待为 `ConversationService` 增加按 `generationId + userId` 构造 Retry Context 的持久化路径。
3. 待让 `ChatHandler.retryGeneration` 在 Redis Snapshot 不存在时回退 MySQL。
4. 两条路径共用相同的 Revision 上限、Conversation 所有权和 Active Generation 检查。
5. 增加回归检查：删除或模拟过期 Redis Snapshot 后，已持久化回答仍可开始新的 Generation。

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
| 2026-08-12 | Phase 2 前端入口修复 | 已完成；Redis 过期后的后端 MySQL 回退仍待实现 |
| 待更新 | 线上真实链路验收 | 待完成 |

## 5. 面试表达草稿

> 在 Redis Worker 上线后，我对实时状态、重新生成和取消链路做了端到端审查。发现 Retry 的父任务
> 依赖 30 分钟 Redis 快照，但历史回答来自 MySQL，形成生命周期不一致；同时 Cancel 只校验了
> WebSocket 指令，没有校验 Generation 的用户归属。我把运行态继续留在 Redis，把可重试的业务事实
> 回退到 MySQL，并在写 Cancel Key 前增加对象级授权和状态机校验。随后通过跨用户取消、快照过期
> Retry 和真实 Redis Worker 链路验证，确保安全性和历史功能同时成立。

最终面试表述中的“通过验证”和性能、正确性数据，只填写实际执行结果，不预先编造。
