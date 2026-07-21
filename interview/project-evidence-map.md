# PaperLoom 项目事实边界

这份文件解决一个问题：面试时哪些话可以说，哪些话不能说。

## 一句话介绍

PaperLoom 是一个面向研究论文 PDF 的 Agentic RAG 阅读工作台。Java 负责认证、论文权限、会话范围、
配额、持久化和检索数据面；Python 只在 Java 已锁定的论文范围内运行工具型 Agent；Qdrant 返回候选，
MySQL 返回可以引用的准确原文。

## 当前真实链路

```text
WebSocket / API
-> ChatHandler
-> 校验用户并锁定会话论文范围
-> ProductReadingConversationService
-> PythonResearchHarnessClient
-> POST /v1/research/stream
-> Python Agents SDK 工具循环
-> Java Corpus API
-> Qdrant 候选检索
-> MySQL 精确读取
-> Evidence 校验、会话持久化、引用重开
```

依据：

- `../README.zh-CN.md`
- `../docs/architecture/overview.md`
- `../docs/architecture/reading-model-and-agent-tools.md`
- `../src/main/java/io/github/chzarles/paperloom/service/ChatHandler.java`
- `../src/main/java/io/github/chzarles/paperloom/service/PythonResearchHarnessClient.java`

## 技术与项目落点

| 技术 | 项目中的真实用途 | 主要代码 |
| --- | --- | --- |
| Spring Boot | 应用装配、MVC、Security、配置、生命周期 | `pom.xml`、`config/` |
| Spring Security | 无状态 JWT 认证、论文资源授权 | `SecurityConfig`、两个 Filter |
| Spring 事务 | 会话范围锁定、引用持久化、任务状态更新 | `ConversationScopeService`、Repository |
| MySQL/JPA | 用户、论文、Reading Model、会话、引用的权威数据 | `model/`、`repository/` |
| Redis | 限流、Token 预占、上传 Bitmap、生成态 | 四个对应 Service |
| Kafka | PDF 合并后的异步解析和索引任务 | `KafkaConfig`、Consumer、UploadController |
| MinIO | PDF、分片、解析产物、视觉资产 | `UploadService` 等 |
| Qdrant | 可重建的稀疏 BM25 候选索引 | Qdrant/Index Service |
| WebSocket | 向指定用户和浏览器客户端推送进度 | Handler、`ChatSessionRegistry` |
| Java 并发 | HTTP 任务、取消、会话注册、有界 Trace 队列 | Client、ExecutorConfig、TraceSink |
| Python | 请求级 Agent Loop、工具状态、Evidence Ledger | `../harness_py/` |

## 可以重点讲的工程证据

### 1. 检索数据面迁移

旧路径会在每个 Python Worker 中加载授权论文全文并建立内存 BM25。迁到 Java/Qdrant 后，Python 不再
重复持有整批全文，Qdrant 只负责候选，准确内容仍由 MySQL 返回。

已记录的 76 篇论文测试：

- 旧路径每个 Worker 增加约 `243 MiB` RSS；
- 每轮语料加载和工具创建耗时 `6.94-8.84 s`；
- 广查询 p50 从 `1.838-2.139 s` 降到 `0.378-0.493 s`，约快 `4.3-4.9` 倍；
- 窄查询反而更慢；早期 Hybrid 排序的精确 Anchor 召回也下降。

所以正确说法是：**迁移解决了重复加载和横向扩展问题，但没有直接证明检索质量更好。**

依据：`../docs/evaluation/qdrant-retrieval-impact-2026-07-15.md`。

### 2. 会话论文范围隔离

第一次发送消息时，`ConversationSessionRepository` 使用 `PESSIMISTIC_WRITE` 锁住会话行，
`ConversationScopeService.lockForFirstMessage` 校验并冻结论文集合。后续用户点击的论文或位置也必须
在这个范围内。

这可以回答：悲观锁、事务、并发首条消息、TOCTOU、越权访问、为什么不能只靠 Prompt。

### 3. 索引任务抢占与归属

索引任务不是用 Java 进程内布尔变量控制，而是使用数据库条件更新：

- 只有前置状态合法才能进入 `BUILDING` 或 `REBUILDING`；
- 抢占成功后写入唯一 `job_id`；
- 完成时同时校验运行状态和 `job_id`；
- 全量重建运行时阻止普通任务抢占。

依据：`PaperReadingModelRepository`、`PaperRetrievalControlRepository`。

### 4. 分片上传和 Kafka

- 分片字节写入 MinIO；
- Redis Bitmap 是快速进度视图；
- MySQL `(file_md5, chunk_index)` 唯一约束是最终幂等边界；
- 合并使用状态条件更新，避免两个请求同时合并；
- Kafka 消息 key 使用 `paperId`；
- Producer 配置 `acks=all`、幂等生产者、重试；
- Consumer 失败重试后进入 DLT，并在处理前检查论文是否已经可检索。

正确语义是：**至少一次投递 + 业务幂等**，不能说整个链路端到端 exactly-once。

### 5. 并发流式响应

- `ChatSessionRegistry`：`ConcurrentHashMap<String, List<WebSocketSession>>`；
- 每个用户的 Session List 使用 `CopyOnWriteArrayList`；
- 向同一个 `WebSocketSession` 写消息时加 `synchronized`；
- Python 请求按 `generationId` 放入 `ConcurrentHashMap`，支持重复检测和取消；
- Trace 使用有界 `ArrayBlockingQueue`，队列满时丢 Trace 而不是卡业务；
- 线程池都有关闭或取消路径。

### 6. Qdrant 文件描述符故障

一次基准测试中 Qdrant 达到软文件描述符上限 `1024`，出现 `Too many open files`。旧健康检查只测试
TCP 连接，所以 HTTP 已不能正常响应时容器仍显示 healthy。

修复包括：提高 `nofile`、健康检查改成 `/healthz` 返回 HTTP 200、基准环境隔离、外部请求增加有限重试
和尝试日志。

## 绝对不能说错的边界

- 项目用的是 **Spring Data JPA**，不是 MyBatis。
- 项目用的是 **Kafka**，不是 RocketMQ。
- Redis 不是论文正文和引用的权威数据源。
- 当前限流是分开的 `INCR` 和 `EXPIRE`，没有使用 Lua 保证原子性。
- Kafka Producer 事务不能让 MySQL 和 Kafka 成为一个原子事务，仍有双写窗口。
- WebSocket Session Registry 是单个 Java 进程内的，不能直接支持多副本跨节点推送。
- 当前配置仍有 Hibernate `ddl-auto=update`，不能声称已经落地 Flyway/Liquibase。
- 没有完成 Qdrant Replica、Snapshot/Restore、Rolling Restart 的生产验证。
- 不能说 Dense 检索提高了质量；历史测试中 Dense 分支很弱，当前已经转为 Sparse-only BM25。
- Java 17 已确定，但仓库没有固定垃圾收集器参数，不能编造 JVM 调优成果。
- Qdrant 是 Candidate Index；只有 MySQL Exact Read 才能形成可引用 Evidence。
