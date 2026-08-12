# Developer Specification (DEV_SPEC): Research Harness Redis Streams Queue

> 版本：0.2  
> 日期：2026-07-26  
> 状态：Proposed  
> 适用范围：把 Java -> `harness_py` 的在线研究回合从单 HTTP 直连改为 Redis Streams worker pool。  
> 参考格式：`/home/charles/MODULAR-RAG-MCP-SERVER/DEV_SPEC*.md`

---

## 目录

- 项目概述
- 核心特点
- 技术选型
- 系统架构与模块设计
- 数据契约
- 运行流程
- 失败、取消与重试
- 测试方案
- 项目排期
- 可扩展性与未来展望

---

## 1. 项目概述

当前 PaperLoom 的研究回答链路是：

```text
Java ChatHandler
-> ProductReadingConversationService
-> PythonResearchHarnessClient
-> POST /v1/research/stream
-> harness_py ResearchHarnessService
-> OpenAI Agents SDK Runner
-> Java Corpus API
-> MySQL / Qdrant
```

这条链路的数据职责已经相对清楚：

- Java 负责认证、授权、会话、Quota、MySQL、Qdrant、WebSocket 和引用持久化；
- `harness_py` 负责 Agent Runtime、工具编排、Evidence Ledger、最终答案结构校验；
- Python 不直接连接 MySQL/Qdrant，而是通过 Java Corpus API 请求已授权范围内的数据。

剩余问题是运行时形态：Java 现在只指向一个 `RESEARCH_HARNESS_BASE_URL`。如果要让
`harness_py` 横向扩展，最直接的 HTTP 方案需要在 Java 与 Python 之间增加一个 Load Balancer。
但本项目已经依赖 Redis，并且 Java 已经把 generation 状态、进度事件和 active generation 放在
Redis 中。因此，本规范选择 Redis Streams 作为在线研究任务队列，让多个 Python worker 主动抢任务。

目标链路：

```text
Frontend WebSocket
    |
    v
Java ChatHandler
    |
    v
Redis Stream: paperloom:research:harness:jobs
    |
    v
N x harness_py worker
    |
    +-> Redis Stream: paperloom:research:harness:events:{generationId}
    |
    +-> Java Corpus API
            |
            +-> MySQL / Qdrant
```

### 1.1 设计理念

> 核心定位：Redis 只承载在线 research turn 的任务分发和短期进度，不成为论文、会话或证据事实源。

本规范不改变现有产品职责：

- Java 仍是权限和数据事实源；
- MySQL 仍是 durable conversation 与 exact evidence 的事实源；
- Qdrant 仍是可重建 candidate index；
- `harness_py` 仍是无状态 worker，可水平增加或减少；
- Redis queue 是运行时调度接口，不是业务事实源。

### 1.2 要解决的问题

1. Java 不需要知道每个 `harness_py` 实例的地址。
2. 新增 `harness_py` worker 后可以自动分担新任务。
3. Java 可以对排队、运行中、失败、取消、完成做统一观测。
4. 前端 streaming 体验保持不变：Java 仍通过 WebSocket 推送 chunk、progress、completion。
5. 取消请求能跨 Java/Python 进程生效。
6. Python worker 异常退出后，Java 能给用户明确失败状态，而不是无限挂起。
7. 用户对已完成答案不满意时，可以在当前答案位重新生成；用户体验上覆盖旧答案，系统内部保留可追溯 fork。

### 1.3 非目标

- 不做 streaming 中途跨实例迁移。
- 不把在线聊天迁到 Kafka。
- 不让 Python 直连 MySQL 或 Qdrant。
- 不把 Redis 作为长期 conversation、reference 或 Evidence 存储。
- 不实现复杂优先级调度、多租户公平队列、工作窃取策略或自研调度平台。
- 不保证 LLM 调用已经开始后可以无损恢复。
- 不把“用户不满意答案 retry”等同于基础设施失败重试；它是一次新的 generation。
- 不破坏前端 WebSocket 协议；retry 只增加可忽略的可选字段。
- 不删除现有 HTTP harness transport；V1 保留为开发和回滚路径。

---

## 2. Grill Decisions

用户已授权“需要决策时使用推荐答案”。本节记录压问后的推荐答案，避免 spec 留空。

| 问题 | 推荐答案 | 理由 |
| --- | --- | --- |
| 用 LB 还是 Redis queue？ | Redis Streams queue | Redis 已经是系统依赖；目标是加 Python worker 自动扩容，pull worker 更贴近目标。 |
| 用 Redis List、Pub/Sub 还是 Streams？ | Redis Streams + consumer group | Pub/Sub 会丢离线事件；List 缺少原生 pending/claim 语义；Streams 支持 ack、pending、重放和观测。 |
| 是否保留 HTTP 直连？ | 保留，配置开关 `research-harness.transport=http|redis` | 降低迁移风险，HTTP 继续作为 dev/debug fallback。 |
| 现有 Python HTTP 接口是否删除？ | V1 不删除，但 Redis mode 不依赖它；生产可不暴露 `/v1/research/*` | 删除会提高迁移风险；真正要去耦合的是运行时主链路，而不是马上删代码。 |
| Java 是否等待 Redis events？ | 是，Java 按 generation 读 event stream 并推 WebSocket | 前端协议不变，复杂度留在 Java transport adapter 内。 |
| Python worker 是否持有会话状态？ | 否 | 每个 job payload 带完整本轮输入；跨轮记忆仍由 Java 读取后放入 payload。 |
| Job payload 是否包含正文？ | 否 | 只包含 user、scope、history、memory、options；正文仍通过 Java Corpus API 精确读取。 |
| 是否自动重试运行中的 LLM generation？ | V1 不重试已开始的 generation | 避免重复扣费、重复输出、重复引用和 streaming 混乱。 |
| 不满意答案 retry 是否复用旧 `generationId`？ | 否，新建 `generationId`，但复用同一个 `answer_slot_id` 并把新版本设为 current | 用户看到的是覆盖旧答案；审计层仍能看出它从哪一版 fork 出来。 |
| Worker 死亡怎么处理？ | 未开始可重新 claim；已 `job_started` 后超时标记失败 | 在线回答优先给用户明确状态，不伪装无损恢复。 |
| 取消怎么做？ | Redis cancel key + worker 检查 | 跨 Java 副本、跨 Python worker 都能看到。 |
| 队列满了怎么办？ | Java fail fast，提示系统繁忙 | 在线聊天不能无限排队，排队过久比明确失败更差。 |

---

## 3. 核心特点

### 3.1 Worker Pool 横向扩展

`harness_py` 增加一个 worker 入口：

```bash
.venv-harness/bin/python -m harness_py worker \
  --redis-url redis://127.0.0.1:6379/0 \
  --worker-id harness-1
```

多个 worker 使用同一个 Redis consumer group：

```text
group: paperloom-research-harness
consumer: {worker_id}
```

新增 worker 不需要 Java 改配置，不需要维护 upstream 列表。

### 3.2 Java Transport 可切换

Java 新增一个最小接口：

```java
public interface ResearchHarnessTransport {
    CompletableFuture<ProductTurnResult> submit(
            ProductTurnRequest request,
            Consumer<Map<String, Object>> progressListener
    );

    void cancel(String generationId);
}
```

两个 adapter：

- `HttpResearchHarnessTransport`：包装当前 `PythonResearchHarnessClient` 逻辑；
- `RedisResearchHarnessTransport`：写 Redis job，读 Redis events，复用现有 result mapping。

`ProductReadingConversationService` 依赖 `ResearchHarnessTransport`，不关心底层是 HTTP 还是 Redis。

### 3.2.1 HTTP Compatibility Policy

V1 不删除现有 Python HTTP 接口，但把它降级为兼容入口：

- 生产主链路：Java 使用 `RedisResearchHarnessTransport`，不请求 `/v1/research/stream`。
- 本地开发：可以继续用 `harness_py serve` 和 HTTP transport 快速 debug。
- 回滚：Redis transport 出问题时，配置切回 `research-harness.transport=http`。
- 部署：Redis mode 下可以只启动 `harness_py worker`，不启动 HTTP serve；即使保留 HTTP，也只开放内网。

等 Redis mode 连续稳定后，再进入单独 cleanup：

- 删除或默认关闭 `/v1/research/turn` 和 `/v1/research/stream`；
- 保留 `/health`，或给 worker 增加独立 health/metrics 入口；
- 移除 Java 的 HTTP adapter 前，先确认没有 smoke/eval/script 仍依赖它。

### 3.3 短期事件流

每个 generation 一个短期 event stream：

```text
paperloom:research:harness:events:{generationId}
```

Python worker 写：

- `job_started`
- `retry_started`
- `retry_context_loaded`
- `model_call_started`
- `model_call_completed`
- `calling_tool`
- `tool_completed`
- `answer_completed`
- `result`
- `error`

Java transport 读这些事件并转发给现有 `ChatHandler` progress path。最终持久化仍由 Java 的
`ConversationService.recordConversation(...)` / `ConversationRevisionService` 完成。

### 3.4 分布式取消

取消 key：

```text
paperloom:research:harness:cancel:{generationId}
```

Java 停止响应时写：

```text
SET paperloom:research:harness:cancel:{generationId} 1 EX 1800
```

Python worker 在以下点检查：

- claim job 后、开始运行前；
- 每次调用 Java Corpus API 前后；
- 每次 tool execution 前；
- 模型调用返回后；
- submit answer 前。

### 3.5 Backpressure

Java 入队前检查队列长度和用户 active generation：

```text
XLEN paperloom:research:harness:jobs <= RESEARCH_HARNESS_QUEUE_MAX_DEPTH
```

超过阈值则拒绝创建新 research job，并返回结构化 busy error。默认建议：

```text
RESEARCH_HARNESS_QUEUE_MAX_DEPTH=200
RESEARCH_HARNESS_JOB_TIMEOUT_SECONDS=900
RESEARCH_HARNESS_EVENT_TTL_SECONDS=1800
```

---

## 4. 技术选型

### 4.1 Redis Streams

选择 Redis Streams，因为项目已经有 Redis，并且 Streams 的语义正好覆盖在线 worker pool 的最小需求：

| 能力 | Redis Streams | 说明 |
| --- | --- | --- |
| 多 worker 抢任务 | Consumer group | 每条 job 默认只交给一个 consumer。 |
| 任务确认 | `XACK` | 成功、失败、取消都可以 terminal 后 ack。 |
| worker 崩溃发现 | `XPENDING` / `XAUTOCLAIM` | Reaper 可处理 stale pending job。 |
| 进度重放 | Stream ID | Java 断线后可从 last event id 继续读短期事件。 |
| 运维成本 | 低 | Redis 已经存在。 |

### 4.2 不选 Kafka

Kafka 更适合上传解析、批量评测、离线处理、审计事件。在线聊天 V1 不选 Kafka：

- 需要 producer/consumer/topic/partition/DLT/idempotency 一整套治理；
- streaming 进度需要两个 topic 或额外查询层；
- at-least-once 会放大重复结果处理；
- 当前吞吐目标不需要 Kafka 的复杂度。

### 4.3 不选单独 LB 作为主方案

LB 仍然是可行方案，但它引入一个新的网络入口，还需要维护后端实例列表或 service discovery。
本项目已经有 Redis；以 Redis Streams 为 job seam，可以避免 Java 直接依赖 Python 实例地址。

### 4.4 Python Redis Client

`harness_py` 新增依赖：

```text
redis>=5,<6
```

V1 使用同步 redis-py 即可，因为当前 `ResearchHarnessService.run_job(...)` 本身是同步入口。
不要为了队列先把整个 Agents Runtime 改成 async worker。

---

## 5. 系统架构与模块设计

### 5.1 依赖方向

唯一允许的依赖方向：

```text
Frontend WebSocket
  -> Java ChatHandler
  -> ProductReadingConversationService
  -> ResearchHarnessTransport
      -> Http adapter
      -> Redis adapter
  -> ChatGenerationStateService
  -> ConversationService

harness_py worker
  -> Redis job source
  -> ResearchHarnessService.run_job(...)
  -> JavaCorpusGateway
  -> Java InternalCorpusController
  -> CorpusRetrievalService
  -> MySQL / Qdrant
```

禁止方向：

- `harness_py` 禁止 import Java schema 之外的数据库模型。
- `harness_py` 禁止直接连接 MySQL/Qdrant。
- Java Redis adapter 禁止解析模型私有 reasoning。
- Redis event stream 禁止成为 conversation 历史事实源。
- 前端禁止直接读取 Redis。

### 5.2 Java 模块

| 模块 | 责任 |
| --- | --- |
| `ResearchHarnessTransport` | Java 对研究运行时的唯一接口。 |
| `HttpResearchHarnessTransport` | 当前 HTTP NDJSON stream adapter。 |
| `RedisResearchHarnessTransport` | 入队 job、读取 event stream、返回 `ProductTurnResult`。 |
| `ResearchHarnessRedisProperties` | Redis transport 配置。 |
| `ResearchHarnessJobEnvelope` | Job payload 契约。 |
| `ResearchHarnessEventEnvelope` | Event payload 契约。 |
| `ResearchHarnessResultMapper` | 把 harness response 映射成 `ProductTurnResult`，由 HTTP/Redis adapter 共用。 |
| `ChatRetryController` 或 `ChatController.retryGeneration(...)` | 校验用户 retry 请求，创建新的 generation，并复用原 `answer_slot_id`。 |
| `ConversationRevisionService` | 维护 answer revision/current/fork 元数据，隐藏“覆盖但可追溯”的存储细节。 |

建议先把 `PythonResearchHarnessClient.toProductResult(...)` 中的映射逻辑搬到
`ResearchHarnessResultMapper`，避免 HTTP/Redis 两套转换。

### 5.3 Python 模块

| 模块 | 责任 |
| --- | --- |
| `harness_py.transport.redis_worker` | CLI worker 入口和主循环。 |
| `RedisResearchJobSource` | `XREADGROUP`、claim、ack、reaper。 |
| `RedisResearchEventSink` | 写 progress/result/error 到 event stream。 |
| `RedisCancellationCheck` | 读取 cancel key。 |
| `ResearchHarnessService.run_job(...)` | 继续作为真实执行入口。 |

Python worker 不创建新的业务运行时。它只是把 Redis job 转成当前 `ResearchHarnessService.run_job`
调用，并把 progress listener 改为 Redis event sink。

### 5.4 CLI

新增命令：

```bash
python -m harness_py worker \
  --redis-url redis://127.0.0.1:6379/0 \
  --group paperloom-research-harness \
  --worker-id harness-${HOSTNAME}-${PID} \
  --block-ms 5000 \
  --max-concurrent-runs 1
```

V1 推荐 `max-concurrent-runs=1`，用进程数横向扩展。后续如果模型 provider 和 CPU 负载允许，再提高到
2 到 4。先不要在一个 Python 进程里塞太多并发。

生产初始值使用 4 个 worker 进程，通过 systemd 模板实例管理。当前 MiniMax-M3 凭证是至少 Max
档的 Token Plan 订阅 Key；官方说明高峰期 Plus、Max、Ultra 约支持 3-4、4-5、6-7 个 Agent。
先取已确认 Max 档的下界 4，不把 Java 的 16 路并发直接映射成 16 个 provider 并发。每个 research run 会连续
发起多次模型请求；最近一轮 17 Case 基准的中位数为 5 次模型调用、43,051 tokens，最大为 13 次、
306,277 tokens。按中位耗时折算，单个活跃 run 约消耗 8.1 RPM、57,949 TPM，4 个并发约为
32.4 RPM、231,796 TPM。只有连续观察不到 429/529、TPM 和 Token Plan 窗口仍有余量时，才逐次
增加 worker。

---

## 6. 数据契约

所有 Redis value 使用 JSON 字符串；字段命名用 `snake_case`。Java 对外 WebSocket 继续使用当前
camelCase payload，Redis 内部 contract 不直接暴露给前端。

### 6.1 Job Stream

Key：

```text
paperloom:research:harness:jobs
```

Entry fields：

```json
{
  "schema_version": "research-harness-job/v1",
  "generation_id": "uuid",
  "created_at_ms": 1780000000000,
  "attempt": 1,
  "payload_json": "{...}"
}
```

`payload_json` 是当前 HTTP `/v1/research/stream` 的 body 等价物：

```json
{
  "request_id": "generation-id",
  "conversation_id": "conversation-id",
  "user_id": 7,
  "user_message": "LoRA 的方法是什么？",
  "history": [
    {"role": "user", "content": "..."},
    {"role": "assistant", "content": "..."}
  ],
  "scope": {
    "mode": "SOURCE_SET_SNAPSHOT",
    "paper_ids": ["paper-a"],
    "reference_focus": {}
  },
  "research_memory": {
    "selected_paper_ids": [],
    "selected_evidence_ids": [],
    "previous_evidence": []
  },
  "options": {
    "include_trace": true,
    "max_completion_tokens": 3000
  },
  "retry": null
}
```

约束：

- `generation_id` 必须等于 `payload_json.request_id`。
- `payload_json.scope.paper_ids` 由 Java 生成，Python 不能扩大。
- `payload_json` 不包含论文正文、Qdrant payload、PDF、图片二进制。
- V1 最大 payload 建议 1 MiB，超过直接 fail fast。

### 6.1.1 Optional User Retry Context

用户点击“不满意/重新生成”时，Java 创建新的 generation，并在 `payload_json.retry` 放入可选上下文：

```json
{
  "kind": "USER_UNSATISFIED",
  "retry_of_generation_id": "previous-generation-id",
  "retry_of_conversation_record_id": 123,
  "answer_slot_id": 123,
  "target_revision": 2,
  "reason": "user_requested",
  "previous_answer_markdown": "上一版答案，按长度截断",
  "previous_cited_evidence_ids": ["ev_1", "ev_2"]
}
```

约束：

- `retry` 缺省或 `null` 表示普通 generation。
- `kind=USER_UNSATISFIED` 是产品层 regeneration，不是基础设施 retry。
- 新请求必须使用新的 `generation_id`，不能复用旧 generation。
- `retry_of_generation_id` 必须指向同一用户、同一 conversation 下的 terminal generation。
- `answer_slot_id` 是前端展示槽位。retry 成功后，新答案替换该槽位的 current 内容。
- `target_revision` 是 Java 预分配的下一版序号；Python 只透传，不负责持久化版本关系。
- `previous_answer_markdown` 只作为反例和上下文，建议上限 8 到 16 KiB，避免 Redis job 过大。
- `previous_cited_evidence_ids` 只引用 Java 已持久化的 evidence/reference id，不把 evidence 正文塞进 Redis。

### 6.2 Event Stream

Key：

```text
paperloom:research:harness:events:{generationId}
```

Entry fields：

```json
{
  "schema_version": "research-harness-event/v1",
  "generation_id": "uuid",
  "sequence": 12,
  "created_at_ms": 1780000000123,
  "type": "tool_completed",
  "payload_json": "{...}"
}
```

事件类型：

| Type | Payload |
| --- | --- |
| `job_started` | `worker_id`, `attempt` |
| `retry_started` | `kind`, `retry_of_generation_id`, `retry_of_conversation_record_id`, `answer_slot_id`, `target_revision` |
| `retry_context_loaded` | `previous_cited_evidence_count`, `previous_answer_chars` |
| `model_call_started` | 当前已有 progress payload |
| `model_call_completed` | 当前已有 progress payload |
| `calling_tool` | `tool`, `arguments` 的安全投影 |
| `tool_completed` | `tool`, `output` 的安全投影 |
| `answer_completed` | 空或 summary |
| `result` | 当前 HTTP result payload |
| `error` | `error_type`, `message` |
| `cancelled` | `message` |

约束：

- 同一 generation 的 `sequence` 从 1 递增。
- `result`、`error`、`cancelled` 是 terminal event，三者只能出现一个。
- terminal event 写入后，worker 必须在同一个 Redis transaction 中执行 `XACK` + `XDEL`。
- `jobs` stream 是一次性运行时队列，不是审计或永久重放存储；当前部署约束为所有 worker 使用同一个 consumer group。
- `XDEL` 删除已进入终态的 job，避免已完成任务长期占用 `XLEN` 队列深度；审计和结果由 Java 的 conversation/status/event 短期数据负责。
- event stream 设置 TTL，并使用 `XTRIM` 控制长度。

### 6.3 Status Key

Key：

```text
paperloom:research:harness:status:{generationId}
```

Value：

```json
{
  "schema_version": "research-harness-status/v1",
  "generation_id": "uuid",
  "status": "QUEUED",
  "worker_id": null,
  "job_stream_id": "1780000000000-0",
  "attempt": 1,
  "created_at_ms": 1780000000000,
  "started_at_ms": null,
  "updated_at_ms": 1780000000000,
  "terminal_at_ms": null,
  "error_type": null,
  "message": null
}
```

Status enum：

```text
QUEUED
RUNNING
SUCCEEDED
FAILED
CANCELLED
STALE_FAILED
```

Status key 是运行时观测状态，不替代 `ChatGenerationStateService` 现有 generation meta。

### 6.4 Cancel Key

Key：

```text
paperloom:research:harness:cancel:{generationId}
```

Value：

```text
1
```

TTL：

```text
RESEARCH_HARNESS_CANCEL_TTL_SECONDS=1800
```

### 6.5 Lock Key

Key：

```text
paperloom:research:harness:lock:{generationId}
```

Value：

```json
{
  "worker_id": "harness-host-1234",
  "attempt": 1,
  "acquired_at_ms": 1780000000000
}
```

Acquisition：

```text
SET key value NX EX RESEARCH_HARNESS_JOB_TIMEOUT_SECONDS
```

Worker 执行中定期续期。若 worker 崩溃，lock 过期后 reaper 可以处理 pending job。

### 6.6 Java Conversation Revision Metadata

“不满意答案 retry”在 UI 上是覆盖，在存储上不要物理覆盖旧行。Java 用最小的 revision 元数据表达这个关系。

推荐在 `conversations` 表增加字段：

```sql
ALTER TABLE conversations
  ADD COLUMN generation_id VARCHAR(64) NULL,
  ADD COLUMN answer_slot_id BIGINT NULL,
  ADD COLUMN answer_revision INT NOT NULL DEFAULT 1,
  ADD COLUMN current_revision BOOLEAN NOT NULL DEFAULT TRUE,
  ADD COLUMN forked_from_conversation_record_id BIGINT NULL,
  ADD COLUMN retry_kind VARCHAR(64) NULL,
  ADD COLUMN retry_reason VARCHAR(255) NULL;

CREATE INDEX idx_conversations_answer_slot
  ON conversations(answer_slot_id, answer_revision);

CREATE INDEX idx_conversations_current_revision
  ON conversations(user_id, conversation_id, current_revision, timestamp);
```

回填规则：

- 旧数据：`answer_slot_id = id`，`answer_revision = 1`，`current_revision = true`。
- 首次回答：保存后 `answer_slot_id = conversation_record_id`。
- 用户 retry：插入新 `Conversation` 行，`answer_slot_id` 继承原答案槽位，`answer_revision = max + 1`。
- 同一事务里锁住该 `answer_slot_id` 的 revision 行，把旧 current 行设为 `current_revision=false`，新行设为 `true`。

查询规则：

- 默认聊天历史只返回 `current_revision=true` 的行，所以用户看到的是“旧答案被新答案覆盖”。
- 版本/fork 查看接口按 `answer_slot_id` 返回所有 revision，用 `forked_from_conversation_record_id` 展示血缘。
- 引用详情、反馈和 audit trail 绑定具体 `conversation_record_id`，不是只绑 `answer_slot_id`。

### 6.7 WebSocket Optional Retry Fields

现有 WebSocket `start/progress/chunk/completion` 类型不变。retry generation 的 `start` 和 `completion`
payload 可增加可选字段，旧前端忽略即可：

```json
{
  "generationId": "new-generation-id",
  "conversationId": "conversation-id",
  "retryOfGenerationId": "previous-generation-id",
  "retryOfConversationRecordId": 123,
  "answerSlotId": 123,
  "answerRevision": 2,
  "replaceMessage": true
}
```

前端规则：

- `replaceMessage=true` 时，用 `answerSlotId` 或 `retryOfGenerationId` 定位旧 assistant message。
- streaming chunk 写入该 message 的 current 内容，而不是追加新 assistant message。
- completion 后把该 message 的 `generationId` 和 `conversationRecordId` 更新为新 revision。

---

## 7. 运行流程

### 7.1 正常成功路径

```text
1. ChatHandler 创建 generation，发送 start 给前端。
2. ProductReadingConversationService 构造 ProductTurnRequest。
3. RedisResearchHarnessTransport:
   - reserve quota
   - 写 status=QUEUED
   - XADD jobs
   - 开始读取 events:{generationId}
4. harness_py worker:
   - XREADGROUP 读取 job
   - SETNX lock
   - 写 status=RUNNING
   - XADD job_started
   - 调 ResearchHarnessService.run_job(...)
   - progress_listener 写 event stream
   - 成功后写 result terminal event
   - transaction 内 XACK + XDEL job
5. Java transport:
   - 读取 progress events 并转给 ChatHandler
   - 读到 result 后转换 ProductTurnResult
6. ChatHandler:
   - append final answer chunk
   - recordConversation
   - markCompleted
   - send completion
```

### 7.2 用户取消路径

```text
1. 前端发送 stop。
2. ChatHandler 标记 generation CANCELLED。
3. RedisResearchHarnessTransport 写 cancel key。
4. worker 在下一次 cancel check 抛 HarnessCancelled。
5. worker 写 cancelled terminal event，在 transaction 内 XACK + XDEL job。
6. Java transport 收到 cancelled 或本地 future 已取消，停止后续收尾。
```

如果 worker 正在等待模型 provider 返回，无法硬中断第三方 HTTP 请求时，取消会在该请求返回后生效。
V1 不承诺 provider-side cancellation。

### 7.3 Worker 崩溃路径

如果 worker 在 `job_started` 之前崩溃：

```text
reaper XAUTOCLAIM stale pending job -> 重新投递给另一个 worker
```

如果 worker 在 `job_started` 之后崩溃：

```text
reaper 标记 status=STALE_FAILED
-> 写 error terminal event
-> transaction 内 XACK + XDEL job
-> Java 给用户明确失败
```

V1 不自动重跑已经开始的 LLM generation。

### 7.4 用户不满意答案重试路径

这条路径是产品层“重新生成”，不是 Redis delivery retry：

建议入口：

```http
POST /api/v1/chat/generation/{generationId}/retry
Content-Type: application/json

{
  "reason": "user_unsatisfied",
  "clientId": "browser-tab-id"
}
```

```text
1. 前端只在 completed assistant message 上显示 retry/regenerate。
2. 用户点击后，前端把原 generationId 和可选 reason 发给 Java。
3. Java 读取原 generation snapshot 和 conversation record。
4. Java 校验：
   - 当前用户拥有该 conversation；
   - 原 generation 是 terminal succeeded；
   - 同一 conversation/client 当前没有 active generation；
   - 未超过单条消息 retry 次数上限。
5. Java 创建新的 generationId，锁定原答案的 answer_slot_id，并构造新的 ProductTurnRequest：
   - conversation_id 沿用原 conversation；
   - user_message 沿用原用户问题；
   - scope 默认复用原 effective scope snapshot；
   - research_memory 可带上旧引用 id，但不把旧答案当成最终事实；
   - retry 填入 parent generation / record 元数据。
6. RedisResearchHarnessTransport 正常 XADD 新 job。
7. 前端把原 assistant message 切到 retrying/loading 状态，并清空 current 展示内容；旧版本进入可展开的 fork 历史。
8. Worker 写 retry_started / retry_context_loaded，然后按普通 generation 执行。
9. Java 收到 result 后，插入新的 Conversation revision，并在同一事务里把同一 answer_slot_id 下的新 revision 设为 current。
10. 前端用新答案覆盖原消息槽位的 current 内容，同时保留“v2 / forked from v1”之类的版本提示。
```

默认 scope 策略：

- 推荐复用原 effective scope snapshot，让 retry 与旧答案可比较。
- 如果用户显式改变论文范围，应走新的普通提问或未来的“retry with edited scope”，不要悄悄改变旧 retry 语义。

---

## 8. 失败、取消与重试

### 8.1 幂等规则

- `generation_id` 是唯一幂等键。
- 同一 `generation_id` 只能有一个 non-terminal job。
- Java 入队前若 status 是 `RUNNING`，直接拒绝重复提交。
- Java 入队前若 status 是 terminal，返回已有 terminal 结果或失败状态。
- Worker 抢到重复 job 时：
  - status terminal：`XACK` 后跳过；
  - lock 被其他 worker 持有：不执行；
  - 无 lock 且未 started：可 claim 执行。

### 8.2 基础设施重试策略

V1 策略：

```text
max_attempts=1 after job_started
max_pre_start_reclaims=3
```

原因：

- 模型调用可能已经扣费；
- Java 可能已经把部分 progress 发给前端；
- 重新跑一遍可能得到不同引用和答案；
- 在线聊天更适合明确失败，再由用户触发一次新的 product retry。

### 8.3 用户不满意答案 Retry

语义分层：

```text
Infrastructure retry = 同一个 generation 的投递可靠性，只允许在 model start 前发生。
User retry = 新 generation、新 job、新 revision；UI 覆盖旧答案位，审计层保留 fork。
```

V1 规则：

- 用户 retry 必须新建 `generation_id`，完成时生成新的 `conversation_record_id`。
- 新旧答案共享同一个 `answer_slot_id`；默认聊天历史只展示该 slot 的 current revision。
- 旧答案、旧引用、旧 usage、旧 feedback 不物理删除，只标记 `current_revision=false`。
- 新答案记录：
  - `retry_kind=USER_UNSATISFIED`
  - `retry_of_generation_id`
  - `forked_from_conversation_record_id`
  - `answer_slot_id`
  - `answer_revision`
- Quota 按一次新的回答正常计费；不能因为“不满意”绕过 reserve/settle。
- 同一 assistant message 默认最多允许 3 次用户 retry：

  ```text
  RESEARCH_HARNESS_USER_RETRY_MAX_PER_MESSAGE=3
  ```

- 同一 conversation/client 已有 active generation 时拒绝 retry，避免两条 streaming 同时写入同一聊天视图。
- 如果原答案还没完成、已取消或失败，V1 不提供“不满意答案 retry”；失败 retry 走错误态上的“重新发送/再试一次”产品入口。
- Worker 不修改会话版本关系；它只根据 `retry` context 产出新答案，版本切换由 Java 完成。

### 8.4 超时

建议配置：

```text
RESEARCH_HARNESS_JOB_TIMEOUT_SECONDS=900
RESEARCH_HARNESS_EVENT_READ_TIMEOUT_SECONDS=930
RESEARCH_HARNESS_WORKER_HEARTBEAT_SECONDS=10
RESEARCH_HARNESS_STALE_PENDING_SECONDS=120
```

Java 等不到 terminal event 时：

- 标记 generation failed；
- 发送 completion with error；
- 保留短期 progress events 供诊断。

### 8.5 Quota

Quota 继续由 Java reserve/settle：

- 入队前 reserve；
- 收到 `result.usage.total_tokens` 后 settle；
- 收到 `error/cancelled/timeout` 后 abort reservation；
- 如果 Java 进程重启导致 reservation 未 settle，沿用现有 quota TTL/清理策略，不在 Python 中结算 quota。

---

## 9. 测试方案

### 9.1 Java 单元测试

新增或调整：

- `ResearchHarnessResultMapperTest`
  - HTTP/Redis 共用 result mapping；
  - citation/reference/readingArtifacts 映射不回归。
- `RedisResearchHarnessTransportTest`
  - 入队 job payload shape；
  - 读取 progress event 并调用 listener；
  - result event 完成 future；
  - error/cancelled event 完成异常或取消；
  - 队列满 fail fast。
- `ProductReadingConversationServiceTest`
  - 只依赖 `ResearchHarnessTransport` interface；
  - 不直接依赖 HTTP client 细节。
- `ChatHandlerStopResponseTest`
  - stop 写 cancel key；
  - Java 本地 future 取消后不落 completed。
- `ChatRetryControllerTest`
  - 只能 retry 已完成且属于当前用户的 assistant answer；
  - retry 创建新的 generationId；
  - retry job 带 `retry` context、`answer_slot_id` 和 parent ids；
  - active generation 存在时拒绝 retry。
- `ConversationRevisionServiceTest`
  - retry 成功后插入新 row，不物理覆盖旧 row；
  - 同一 `answer_slot_id` 只有一个 `current_revision=true`；
  - 默认 history 只返回 current revision；
  - fork/revision 查询能返回旧版本及引用。

### 9.2 Python 单元测试

新增：

- `test_redis_worker_contract.py`
  - job payload -> `ResearchHarnessService.run_job(...)`；
  - progress listener -> Redis event；
  - result/error/cancelled terminal event；
  - terminal 后 ack；
  - cancel key 被检查。
- `test_redis_event_sink.py`
  - sequence 单调递增；
  - payload JSON 可解析；
  - stream TTL/trim 调用。
- `test_redis_job_source.py`
  - duplicate generation lock；
  - terminal status skip；
  - stale pending pre-start reclaim。

### 9.3 Contract Tests

维护一个共享 JSON fixture：

```text
docs/contracts/research-harness/job-v1.json
docs/contracts/research-harness/event-v1.json
docs/contracts/research-harness/result-v1.json
```

Java 和 Python 都读取同一 fixture 验证：

- 必填字段；
- snake_case/camelCase 分界；
- terminal event 唯一性；
- optional `retry` context 兼容普通 job；
- result payload 与现有 HTTP response shape 兼容。

### 9.4 Real Redis Smoke

默认不跑真实 Redis。显式设置时运行：

```text
RESEARCH_HARNESS_REDIS_SMOKE_URL=redis://127.0.0.1:6379/0
```

Smoke 覆盖：

1. Java/fake producer 入队；
2. Python/fake harness worker 消费；
3. worker 写 progress + result；
4. Java/fake event reader 读到 terminal；
5. job 被 ack；
6. keys 有 TTL。

### 9.5 Frontend 单元测试

新增：

- completed assistant message 才显示 retry/regenerate。
- retry start 后复用原 message slot，进入 loading/retrying，不追加新的 assistant 气泡。
- retry completion 后用新 `generationId`、新 `conversationRecordId`、新 answer 覆盖该 slot 的 current 展示。
- 旧版本入口能看到 `answerRevision` / `forkedFromConversationRecordId`，引用仍能按旧 `conversationRecordId` 打开。

### 9.6 Manual Verification

1. 启动 Redis、Java backend、两个 `harness_py worker`。
2. 设置：

   ```text
   RESEARCH_HARNESS_TRANSPORT=redis
   RESEARCH_HARNESS_REDIS_URL=redis://127.0.0.1:6379/0
   JAVA_CORPUS_BASE_URL=http://127.0.0.1:8081
   ```

3. 打开前端 chat，发一条论文内容问题。
4. 确认：
   - WebSocket 仍收到 start、progress、chunk、completion；
   - Redis `XPENDING` 无长期 pending；
   - conversation 被 Java 持久化；
   - citation 能重新打开 PDF/source evidence；
   - 停止按钮能让 worker 终止本轮。
5. 对完成答案点击 retry/regenerate。
6. 确认：
   - 前端同一个答案位进入 loading，而不是追加一个新气泡；
   - retry 完成后当前答案被新答案覆盖；
   - 版本入口能看到旧答案 fork；
   - 新旧版本的 citation 都能打开各自的 source evidence；
   - history reload 后仍只显示 current revision。

---

## 10. 项目排期

### Phase 1: Interface And Result Mapper

- 抽出 `ResearchHarnessTransport`。
- 抽出 `ResearchHarnessResultMapper`。
- 当前 HTTP 路径迁到 `HttpResearchHarnessTransport`。
- 保证现有测试通过。

验收：

- `ProductReadingConversationService` 不再直接依赖 `PythonResearchHarnessClient`。
- HTTP 模式行为不变。

### Phase 2: Redis Job/Event Contract

- 新增 Java Redis transport 的 job enqueue 与 event reader。
- 新增 Python Redis worker skeleton。
- 加 contract fixture 和 fake tests。

验收：

- 不调用真实模型时，fake worker 可以完成完整 job -> event -> result 闭环。

### Phase 3: Worker Runtime Integration

- Python worker 调用 `ResearchHarnessService.run_job(...)`。
- Progress event 写 Redis Streams。
- Cancel key 接入现有 `should_cancel`。
- Worker lock、status、ack、TTL、trim 完成。

验收：

- 两个 worker 同时运行时，同一 job 只执行一次。
- stop 能让 Python 退出，并且 Java 不落 completed。

### Phase 4: Redis Mode Product Smoke

- 增加 `research-harness.transport=redis` 配置。
- 本地真实 Redis 跑一次产品 chat smoke。
- 保留 HTTP fallback。

验收：

- 前端体验不变。
- 引用、research audit trail、conversation 持久化不回归。
- Python worker 数量从 1 增到 3 后，新 job 能被不同 worker 消费。

### Phase 5: User Retry Revision

- 增加 `conversations` revision metadata migration。
- 增加 Java retry endpoint，创建新 generation 并复用 `answer_slot_id`。
- retry completion 时把新 revision 设为 current，把旧 revision 隐藏到 fork 历史。
- 前端 retry 时复用原 assistant message slot，并显示版本/fork 入口。

验收：

- 用户点击 retry 后，聊天主线只显示新答案。
- fork 历史里能看到旧答案、旧引用、旧 audit trail。
- 刷新页面后主线仍只显示 current revision。

### Phase 6: Cleanup And Docs

- 更新 `docs/reference/configuration.md`。
- 更新 `docs/guides/deployment.md`。
- 增加 `scripts/paperloom-start-harness-worker.sh` 或扩展现有 harness script。
- 明确 HTTP mode 是 dev/debug fallback。

---

## 11. 可扩展性与未来展望

### 11.1 什么时候够用

Redis Streams queue 足够覆盖：

- 中小规模在线聊天；
- Python harness worker 水平扩展；
- 短期 progress replay；
- 跨 Java 实例取消；
- 简单排队削峰。

### 11.2 什么时候升级

只有出现下面情况才考虑 Kafka 或专门任务系统：

| 信号 | 升级方向 |
| --- | --- |
| 每天大量离线 eval/batch research | Kafka job topic |
| 需要长期保留所有 progress event | MySQL/对象存储 trace archive |
| Redis memory 被短期 event 压爆 | 外部日志/trace sink |
| 需要跨地域多机房 worker | 专门消息系统 |
| 需要优先级、公平调度和租户配额 | 独立 scheduler |

### 11.3 仍然不做的事

- 不把已经 streaming 的 generation 搬到另一个 worker 继续。
- 不把 LLM provider 的连接状态持久化。
- 不让 worker 直接修改 conversation。
- 不把 Redis Streams 当作审计永久存储。

---

## 12. Acceptance Criteria

1. `research-harness.transport=http` 时，现有产品 chat 行为不变。
2. `research-harness.transport=redis` 时，Java 不直接请求 `/v1/research/stream`，而是写 Redis job。
3. 两个以上 `harness_py worker` 可以消费同一 jobs stream。
4. 同一 `generationId` 不会被两个 worker 同时执行。
5. Java 能把 Redis progress events 转成现有 WebSocket progress。
6. Java 能把 Redis result 映射成现有 `ProductTurnResult`。
7. 用户 stop 后 Redis cancel key 生效，worker 写 cancelled terminal event。
8. Worker 崩溃后不会让 Java 无限等待；超时后 generation failed。
9. Python 仍不直接连接 MySQL/Qdrant。
10. Redis event/status/lock/cancel keys 都有 TTL 或 trim 策略。
11. HTTP fallback 保留，便于本地调试和紧急回滚。
12. Contract tests 覆盖 job/event/result schema。
13. 用户可以对 completed assistant answer 点击 retry/regenerate。
14. retry 会创建新 `generationId` 和新 `conversationRecordId`，但复用原 `answerSlotId`。
15. retry 成功后聊天主线展示新答案，旧答案不在主线重复出现。
16. fork/revision 入口能打开旧答案、旧引用和旧 audit trail。
17. Quota、feedback、reference detail 都绑定具体 revision，不被 current 覆盖污染。

---

## 13. Rollback

回滚只需要配置切回 HTTP：

```text
RESEARCH_HARNESS_TRANSPORT=http
RESEARCH_HARNESS_BASE_URL=http://127.0.0.1:8091
```

Redis queue 中未完成 job 不进入 durable conversation。切回 HTTP 后，新 generation 走旧路径。
短期 Redis event/status keys 到 TTL 后自然清理。
