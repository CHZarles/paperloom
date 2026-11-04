# 代码地图（接手用）

本文件只回答一个问题：**要改某个东西，去哪个文件。** 产品语义、数据职责、为什么这样设计，
看 [Architecture Overview](overview.md) 和 [`docs/adr/`](../adr/)。

## 1. 一台机器上的进程

单机部署，没有跨机调用。容器由 `docs/docker-compose.yaml` 管理，Java/Python/前端由 `scripts/` 启动。

| 进程 | 端口 | 启动方式 | 职责 |
| --- | --- | --- | --- |
| MySQL | 3306 | Docker Compose | 论文、Reading Model、对话、引用映射等权威数据 |
| Redis | 6379 | Docker Compose | 会话、生成状态、配额、研究任务流 |
| MinIO | 9000 / 9001 | Docker Compose | PDF、解析产物、页面截图、图像裁剪 |
| Qdrant | 6333 / 6334 | Docker Compose | 可重建的候选检索索引 |
| Kafka | 9092 | Docker Compose | 上传后的解析任务投递 |
| Spring Boot | 8081 | `scripts/paperloom-start-backend.sh` | 全部 REST/WebSocket 接口、解析消费、OAuth 授权服务 |
| Research Harness | 8091 | `scripts/paperloom-start-harness.sh` | Python 研究回合（Agents SDK） |
| 前端 | 9527 | `scripts/paperloom-start-frontend.sh` | Citewise 界面 |
| MCP | stdio | `scripts/paperloom-mcp-stdio.sh` | 给外部 MCP 客户端暴露读写工具 |

后端没有 Actuator。判断存活用 `curl -o /dev/null -w '%{http_code}' http://localhost:8081/api/v1/users/me`，
返回 `403` 表示服务活着且登录保护生效。

## 2. 三条链路

### 上传 / 导入 → 解析 → 索引

```text
PaperUploadController          分片上传、合并
  -> UploadService             分片落 MinIO、合并为一篇 PDF
  -> PaperProcessingTask       投递 Kafka（topic: paper-processing-topic）
  -> PaperProcessingConsumer   消费、按 taskType 分派
  -> UploadService.getMergedFileStream
  -> ParseService.parseAndSave 调 MinerU、落 Reading Model
  -> MinerUPaperPdfParser / MinerUParserClient / MinerUOutputMapper
  -> PaperReadingModelBuilder  解析结果 -> 页/节/段落/公式/表格/图
  -> RetrievalIndexingService  构建检索单元
  -> ReadingModelQdrantIndexService -> Qdrant
```

失败路径：`PaperProcessingConsumer` 捕获异常后调用 `PaperService.markVectorizationFailed`，消息进入
`paper-processing-dlt`。不自动重试，重试由用户或管理接口触发（`PaperService.enqueueAsyncVectorizationRetry`）。

### 问答回合

```text
WebSocket /chat/{token}
  -> ChatWebSocketHandler      解析消息、鉴权
  -> ChatHandler               会话锁、配额、生成状态、取消、推送
  -> ProductReadingConversationService
  -> PythonResearchHarnessClient
  -> HTTP POST /v1/research/stream
  -> harness_py/transport/service.py    ResearchHarnessService
  -> harness_py/orchestration/live_chat.py
  -> harness_py/orchestration/agents/runtime.py   Agents SDK Runner
  -> harness_py/corpus/tools.py 工具
  -> InternalCorpusController   /internal/v1/corpus/**
  -> Qdrant 检索 + MySQL 精确读取
```

研究回合只有一条传输：`PythonResearchHarnessClient` 向本机 `harness_py serve` 发起一次 HTTP
请求，响应是 NDJSON 流——每行一个事件（进度或终态），Java 逐行读并转发给 WebSocket。没有消息
队列、没有 worker 池、没有负载均衡。

2026-11 之前存在第二条 Redis Streams 传输（`RedisResearchHarnessTransport` +
`harness_py/transport/redis_worker.py`），已整体移除。`docs/engineering-evolution/` 下的记录保留
了它当时的设计与事故复盘。

### 引用重开

```text
ConversationController / ReadingQueryController
  -> ConversationService               读到已落库的引用映射
  -> PaperReadingService + CanonicalReadingLocationService   按 location 精确定位
  -> PaperSourceQuoteReadService       取回原文
```

历史引用不能绕过当前授权：重开前会重新校验论文是否仍在该用户的可见范围。

## 3. 目录职责

### Java（`src/main/java/io/github/chzarles/paperloom/`）

| 目录 | 文件数 | 职责 | 先看 |
| --- | ---: | --- | --- |
| `controller/` | 14 | REST 入口 | `InternalCorpusController`（Python 唯一入口）、`PaperUploadController` |
| `service/` | 96 | 业务主体 | `ChatHandler`(2256)、`PaperReadingModelBuilder`(1647)、`ConversationService`(1480) |
| `paper/parser/` | 17 | MinerU 输出映射 | `MinerUOutputMapper`(674) |
| `repository/` | 23 | JPA 仓库 | `PaperRepository`、`PaperReadingModelRepository` |
| `model/` | 32 | 实体与 DTO | `Paper`、`PaperReadingModel` |
| `config/` | 24 | 安全、OAuth2、Kafka、MinIO、异步、WebSocket | `SecurityConfig`、`KafkaConfig`、`AsyncExecutorConfig` |
| `golden/` | 2 | Golden Data 导出 | — |

### Python（`harness_py/`，86 文件 / 24.7k 行）

| 目录 | 职责 |
| --- | --- |
| `transport/` | HTTP 服务入口；Redis Streams worker |
| `orchestration/` | 研究回合、Agents SDK 运行时 |
| `corpus/` | 工具定义、授权状态、证据账本 |
| `evaluation/` | Golden 评测、裁判校准 |
| `core/` | 契约模型 |
| `tests/` | 213 个测试 |

### 前端（`frontend/src/`）

`views/` 下按产品面划分：`chat`、`chat-history`、`knowledge-base`、`discover`、`personal-center`、
`user`、`invite-code`、`_builtin`。接口调用在 `service/api/`。

## 4. 按问题找代码

| 现象 | 先看 |
| --- | --- |
| 上传失败、分片合并异常 | `PaperUploadController` → `UploadService` |
| 解析出来的结构不对、章节错位 | `MinerUOutputMapper` → `PaperReadingModelBuilder` |
| 检索不到、召回差 | `RetrievalIndexingService` → `ReadingModelQdrantIndexService` → `QdrantClient` |
| 回答没有引用，或引用点不开 | `harness_py/corpus/tools.py` → `ProductReadingConversationService` |
| 两个会话串话 | `ChatHandler` 的会话锁 → `ConversationScopeService` |
| 登录、权限、MCP 授权 | `SecurityConfig` → `AuthController` → `OAuth2AuthorizationServerConfig` |
| 任务卡在"解析中" | `paper_processing_tasks` 表 → `PaperProcessingConsumer` → Kafka topic 积压 |
| 前端调不通后端 | `frontend/src/service/api/` → Nginx `/api` 反代 |

## 5. 第一周读代码顺序

1. [overview.md](overview.md) 与 [runtime-diagrams.md](runtime-diagrams.md)
2. `ChatWebSocketHandler` → `ChatHandler`（先只读方法签名和主流程，别陷进 2256 行细节）
3. `ProductReadingConversationService` → `PythonResearchHarnessClient`
4. `harness_py/transport/service.py` → `orchestration/live_chat.py`
5. `PaperProcessingConsumer` → `ParseService` → `PaperReadingModelBuilder`
6. `InternalCorpusController` → `harness_py/corpus/tools.py`（Java 与 Python 的契约边界）

## 6. 本地起停

```bash
docker compose --env-file .env -f docs/docker-compose.yaml up -d
scripts/paperloom-start-backend.sh
scripts/paperloom-start-harness.sh
scripts/paperloom-start-frontend.sh
```

验证：

```bash
curl -o /dev/null -w 'backend=%{http_code}\n' http://localhost:8081/api/v1/users/me   # 403 正常
curl -fsS http://127.0.0.1:8091/health
curl -o /dev/null -w 'frontend=%{http_code}\n' http://localhost:9527/
```

测试：

```bash
mvn test
.venv-harness/bin/python -m unittest discover -s harness_py/tests
cd frontend && pnpm typecheck && pnpm build
```

## 7. 已知陷阱

- **MinerU 需要 token**。缺 `PAPER_PARSING_MINERU_API_TOKEN` 时上传会失败并落到
  `markVectorizationFailed`，不是静默降级。
- **Python 测试在本机需要修正 `NO_PROXY`**。环境里带方括号的 IPv6 地址会让 httpx 报
  `Invalid port: ':1]'`，先 `export NO_PROXY=localhost,127.0.0.1,::1 no_proxy=...` 再跑。
- **harness 进程挂了会快速失败**。回合标记失败、配额回滚、会话保留，用户重试即可；后端启动时会探
  一次 `/health` 并打警告日志。没有自动重试，也没有队列缓冲。
- **研究回合是一条 HTTP 流**。事件类型在 Python 侧发射、Java 侧只认 `result` 与 `error`，其余原样
  透传给前端。新增事件类型不需要改 Java，但要在前端 `research-process-panel.vue` 里加分支，否则
  它不会显示。
- **Kafka 不做自动重试**。消费失败直接进 DLT，恢复靠显式重试接口。
- **产品数据与评测数据是两个域**。`papers/`、`research/golden-data/` 不是产品语料，不要混进线上库。
- **单机假设**。共享内存、本地路径、`127.0.0.1` 回调都是成立的；引入第二个进程宿主要重新审视。
