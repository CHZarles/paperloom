# Redis Live Job False Reclaim Incident

## 事件结论

- 状态：已解决，Commit `99fb62f` 已部署
- 时间：2026-08-12
- 线上现象：前端显示 `The research run stopped because of an internal service error...`
- 故障层面：Redis Streams 多 Worker 调度与故障恢复

这不是模型、检索、Agent 研究逻辑或前端渲染失败。一个正常执行的长任务被另一个 Worker
错误地判定为失联任务。

## 关键证据

目标 Generation：`ebaf9807-f991-46ca-962e-2736fe884890`。

- Java 在任务开始约 120 秒后收到 `StalePendingJob`：
  `research worker disappeared after starting this job`。
- 4 个 Worker 均未重启，系统没有 OOM 或进程退出记录。
- 原 Worker 随后继续完成 9 次模型调用，在约 256 秒时生成通过引用校验的完整答案。
- Redis Event Stream 同时出现了提前的错误事件和稍后的成功 `result`。

因此，“Worker 消失”不是事实，而是调度器的误判。

## XAUTOCLAIM 是什么

Worker 从 Redis Streams 领取消息后，消息会保持 Pending，直到 Worker 完成任务并 ACK。
`XAUTOCLAIM` 会把长时间未 ACK 的 Pending 消息转交给另一个 Worker。它用于恢复“Worker
领取任务后崩溃”的情况。

但 Pending 时间只表示任务尚未 ACK：

```text
Pending 时间很长
  = Worker 可能崩溃
  或 Worker 正在处理一个正常的长任务
```

Redis 不能仅凭 Pending 时间区分这两种情况。

## 根因

旧实现把 `XAUTOCLAIM` 的 120 秒 Pending 阈值同时当成 Worker 存活判断：

```text
Status = RUNNING 且 Pending > 120 秒
→ 直接发布 StalePendingJob
```

任务的 Redis Lock 实际上每 10 秒续期，已经能够证明原 Worker 仍在执行，但旧的 reclaim
分支没有检查这把 Lock。于是多个 Worker 对同一个 Generation 产生冲突终态：接管 Worker
先发布失败，原 Worker 后发布成功；Java 接受先到的失败，用户看到内部错误。

根本原因是：**用消息未确认时长代替 Worker 存活租约，造成假阳性的故障检测。**

## 修复

`RUNNING` Job 被 reclaim 后，先检查 Generation Lock：

```text
RUNNING 且 Lock 存在
→ 原 Worker 仍存活，不报错、不重复执行

RUNNING 且 Lock 不存在
→ 原 Worker 已无法证明存活，标记 StalePendingJob
```

实现位置：`harness_py/transport/redis_worker.py`。

没有把 120 秒简单调大。调大阈值只会让更长的正常任务再次触发同一错误，也会延迟真实崩溃的
发现。Lock 是代码中已有的正确存活信号，不需要引入新协调组件。

## 验证与部署

- 增加一项聚焦回归检查：Pending Job 已被 reclaim，但原 Worker Lock 仍存在时，不发送失败、
  不 ACK、也不删除任务消息。
- Redis Worker 聚焦检查共 7 项通过。
- Commit：`99fb62f fix(harness): do not reclaim live research runs`
- 线上 4 个 Worker 重启后均为 `active`。

## 排查方法

1. 先从 Java 日志取得 Generation ID 和直接异常，不以通用前端文案作为根因。
2. 用 Generation ID 定位 Agent Trace，确认原 Run 是否真的停止。
3. 对齐 Java 日志、Redis Event Stream、Agent Trace 三条时间线。
4. 检查 Worker 的 PID、重启次数、OOM 记录，验证“Worker 消失”这个假设。
5. 区分触发条件与根因：运行超过 120 秒只是触发条件；错误的存活判断才是根因。

## 保留的不变量

> 只要某个 Generation 的执行 Lock 仍有效，其他 Worker 就不得把它标记为失败或重复执行。

