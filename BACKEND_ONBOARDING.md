# 派聪明（PaiSmart）后端新成员上手指南

> 由 `/understand --language zh` 知识图谱自动生成 · 2026-06-07 · commit `59ec5224`
>
> 范围：**仅后端**（Java 17 + Spring Boot 3.4.2），不含前端 monorepo/Vue 3
>
> 完整图谱：`/.understand-anything/knowledge-graph.json` · 1233 节点 / 2385 边

---

## 1. 项目全景（后端视角）

**派聪明（PaiSmart）** 是一套企业级 AI 知识库管理系统。**后端**是这套系统的「重活」承担者：

- **运行时**：Spring Boot 3.4.2 / Java 17 / Maven
- **持久化**：MySQL 8.0 + JPA/Hibernate + Spring Data
- **缓存 / 限流**：Redis 7.0 + Lua
- **消息**：Apache Kafka 3.2.1（异步文件处理）
- **对象存储**：MinIO（文档原文 + bootstrap PDF）
- **搜索**：Elasticsearch 8.10（向量 + 全文混合检索）
- **AI**：DeepSeek API（LLM 流式推理）+ 豆包 / 自定义 Embedding（向量化）+ LLM Provider Router（多 Provider 切换）
- **实时通信**：WebSocket（聊天流式响应）
- **鉴权**：Spring Security + JJWT + Redis 黑名单（access/refresh 双 Token）
- **支付**：微信支付（Recharge 充值）
- **可观测性**：Logback（业务 / 性能 / 聊天 / 错误四类日志）+ Spring Boot Actuator

**包名约定**：`com.yizhaoqi.smartpai`（Maven 坐标 `com.yizhaoqi:SmartPAI`）
**入口主类**：`src/main/java/com/yizhaoqi/smartpai/SmartPaiApplication.java`
**配置入口**：`src/main/resources/application.yml` + profile 文件（`dev` / `docker` / `prod` / `test`）

---

## 2. 后端架构分层（6 层）

整个后端在知识图谱里被切成 6 个 layer。建议新成员**自顶向下**理解：

| #   | Layer                                  | 职责                                                                                                          | 文件数     |
| --- | -------------------------------------- | ------------------------------------------------------------------------------------------------------------- | ---------- |
| 1   | **后端 API 接入层**                    | REST 控制器（`@RestController`）+ WebSocket 端点（`@MessageMapping`）                                         | 12         |
| 2   | **后端业务服务层**                     | 28 个 Service + 10 个对应单测，承载业务逻辑                                                                   | 38         |
| 3   | **后端数据访问层**                     | JPA 实体、Model、Repository、Exception                                                                        | 39         |
| 4   | **后端基础设施层**                     | Config 类、AI 客户端、Kafka 消费者、JWT/加密/JSON 工具、application\*.yml、logback、ES 索引 mapping、测试基座 | 57         |
| 5   | **数据库与模式层**                     | MySQL DDL（11 张表）+ ES 索引 mapping                                                                         | 12         |
| 6   | **部署与项目配置**（与后端强相关部分） | docker-compose、infra.sh、launch.sh、pom.xml、.env、application\*.yml                                         | 19（部分） |

**理解后端的小窍门**：从「**API 接入层**」看起（你点哪个 URL 会进哪个 Controller），跟着 fan-in（被多少个上游调用）逆流而上：Controller → Service → Repository → MySQL/ES/MinIO。

---

## 3. 关键概念与设计决策（后端必懂）

### 3.1 RAG 流水线（异步）

上传（HTTP）→ `UploadController` 收文件 → 落到 `MinIO` → 投递 Kafka 消息 → `FileProcessingConsumer` 消费 → `ParseService`（Apache Tika 解析 + 父子分块）→ `VectorizationService`（EmbeddingClient 调 API）→ `ElasticsearchService`（索引）。**「接口同步 + 消费异步」** 是 RAG 场景下的标准做法，让上传请求秒级返回。

### 3.2 混合检索（Hybrid Search）

`HybridSearchService` 把 **BM25 关键词打分**（强在专有名词 / 字面命中）和 **向量余弦相似度**（强在语义同义改写）做线性加权。工程上要在 ES 一个索引里同时存 `text` 字段和 `dense_vector` 字段，再各自打分合并，权重由线上 AB 测试调出。

### 3.3 多租户（组织标签 OrgTag）

- 用户 ↔ 多对多 ↔ `OrganizationTag`（**树形结构**，带父标签）
- 文件带 `orgTag` 字段
- `OrgTagAuthorizationFilter` 在每次请求时校验三段：「**资源拥有者** / **组织标签匹配** / **是否公开**」
- 私人文件用 `PRIVATE_` 前缀标识

### 3.4 JWT 双 Token + Redis 黑名单

- `access token`（短）+ `refresh token`（长）
- `TokenCacheService` 维护 `tokenId → 用户 + 过期时间` 在 Redis 中
- `JwtAuthenticationFilter` 在临近过期时自动刷新
- 登出走 Redis 黑名单

### 3.5 LLM Provider Router

- `LlmProviderRouter` 按运行时数据库配置选择 OpenAI 兼容或自定义端点
- `ModelProviderConfig` 表存 API Key / baseUrl / 模型名
- `SecretCryptoService` 加密 API Key 后存盘

### 3.6 限流配额

- `UsageQuotaService` 基于 Redis Lua 做分钟/天/全局三档令牌桶
- `RateLimitService` + `RateLimitConfigService` 提供注解式限流
- `UserTokenService` 维护每个用户的 LLM/Embedding token 账本

### 3.7 微信支付

- `WxPayConfig` 配置商户号
- `RechargeController` 创建充值订单
- `WxPayService` 处理支付回调
- `HttpRequestUtil` + `JsonUtil` 是 HTTP/JSON 工具

---

## 4. 后端 9 步导览（按推荐阅读顺序）

下表是知识图谱自带的 guided tour 中**只保留后端相关**的步骤：

| #   | 标题                         | 关键文件                                                                                                                                                                                                                          | 教学点                                                                            |
| --- | ---------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------- |
| 1   | 项目全景                     | `README.md`, `CLAUDE.md`                                                                                                                                                                                                          | —                                                                                 |
| 2   | 后端启动入口                 | `src/main/java/com/yizhaoqi/smartpai/SmartPaiApplication.java`                                                                                                                                                                    | —                                                                                 |
| 3   | 文档上传与 Kafka 异步流水线  | `controller/UploadController.java`, `consumer/FileProcessingConsumer.java`, `config/KafkaConfig.java`                                                                                                                             | **Spring Kafka @KafkaListener**：topic → 普通方法，消息偏移、并发、重试由容器托管 |
| 4   | 解析、切块与向量化           | `service/ParseService.java`, `service/VectorizationService.java`, `client/EmbeddingClient.java`, `model/FileUpload.java`, `model/ChunkInfo.java`                                                                                  | —                                                                                 |
| 5   | Elasticsearch 存储与混合检索 | `config/EsConfig.java`, `service/ElasticsearchService.java`, `service/HybridSearchService.java`, `entity/EsDocument.java`                                                                                                         | **Hybrid Search**：稀疏 + 稠密双索引融合                                          |
| 6   | AI 对话主链路                | `handler/ChatWebSocketHandler.java`, `service/ChatHandler.java`（fan-out 41，全仓第一）, `service/LlmProviderRouter.java`, `client/DeepSeekClient.java`                                                                           | —                                                                                 |
| 7   | 多租户认证与组织标签鉴权     | `config/SecurityConfig.java`, `config/JwtAuthenticationFilter.java`, `utils/JwtUtils.java`（fan-in 23）, `config/OrgTagAuthorizationFilter.java`, `service/UserService.java`, `model/User.java`, `repository/UserRepository.java` | **Spring Security 过滤器链**：按顺序叠加，每个过滤器只管自己                      |
| 8   | 数据库 Schema 全貌           | `data:docs/databases/ddl.sql`                                                                                                                                                                                                     | —                                                                                 |
| 13  | 基础设施编排与本地生命周期   | `docs/docker-compose.yaml`, `config/MinioConfig.java`, `infra.sh`                                                                                                                                                                 | **docker-compose healthcheck + depends_on.condition**：保证服务按正确顺序启动     |

---

## 5. 各层关键文件（按 fan-in 排序）

### 5.1 后端 API 接入层（12 个文件）

| Fan-in | 文件                                   | 作用                                                                                               |
| ------ | -------------------------------------- | -------------------------------------------------------------------------------------------------- |
| 17     | `controller/ChatWebSocketHandler.java` | WebSocket 消息处理器，转发用户查询到 `ChatHandler`                                                 |
| 13     | `controller/AdminController.java`      | 管理员后台 REST 控制器（用户、活动、仪表盘、限流、模型提供商、邀请码、组织标签、充值、MinIO 迁移） |
| 12     | `controller/UploadController.java`     | 文件上传 REST 控制器                                                                               |
| 9      | `controller/UserController.java`       | 用户 REST 控制器（注册/登录/当前用户/组织标签/登出/配额/token 流水）                               |
| 9      | `controller/AuthController.java`       | 认证辅助（token 刷新）                                                                             |
| 8      | `controller/DocumentController.java`   | 文档管理（删除/重索引/可见文件分页/PDF 预览）                                                      |
| 7      | `controller/ChatController.java`       | 聊天辅助（聊天历史的非流式入口）                                                                   |
| —      | `controller/ParseController.java`      | 文档解析触发                                                                                       |
| —      | `controller/RechargeController.java`   | 微信支付订单创建与回调                                                                             |
| —      | `controller/SearchController.java`     | 搜索接口                                                                                           |
| —      | `handler/ChatWebSocketHandler.java`    | WebSocket 长连接（已在 ChatWebSocketHandler 列出）                                                 |
| —      | `consumer/FileProcessingConsumer.java` | Kafka 消费者（已列入基础设施层）                                                                   |

### 5.2 后端业务服务层（38 个文件，28 Service + 10 单测）

| Fan-in | 文件                                                                                       | 作用                                                        |
| ------ | ------------------------------------------------------------------------------------------ | ----------------------------------------------------------- |
| 17     | `service/VectorizationService.java`                                                        | 向量化服务                                                  |
| 17     | `service/ParseService.java`                                                                | Tika 解析 + 父子分块                                        |
| 16     | `service/ElasticsearchService.java`                                                        | ES 索引 + DSL 查询 + 向量检索 + 高亮                        |
| 15     | `service/ChatHandler.java`                                                                 | **聊天业务核心**：混合检索 → 提示词 → LLM 流式 → 引用归一化 |
| 15     | `service/HybridSearchService.java`                                                         | BM25 + 向量余弦融合                                         |
| 14     | `service/DocumentService.java`                                                             | 文档管理：上传、解析、文本清洗                              |
| 13     | `service/TokenCacheService.java`                                                           | Redis JWT Token 缓存 + 黑名单                               |
| 12     | `service/UploadService.java`                                                               | 上传业务                                                    |
| 11     | `service/UserService.java`                                                                 | 用户管理：注册/认证/组织标签 CRUD/邀请码                    |
| —      | `service/EmbeddingClient.java`（若存在）                                                   | Embedding API 包装                                          |
| —      | `service/RateLimitService.java`, `RateLimitConfigService.java`                             | 限流                                                        |
| —      | `service/UsageQuotaService.java`, `UsageBalanceQuotaService.java`, `UserTokenService.java` | 配额账本                                                    |
| —      | `service/RechargeService.java`, `WxPayService.java`                                        | 微信支付                                                    |
| —      | `service/InviteCodeService.java`                                                           | 邀请码                                                      |
| —      | `service/OrgTagCacheService.java`                                                          | 组织标签 Redis 缓存                                         |
| —      | `service/CustomUserDetailsService.java`                                                    | Spring Security UserDetailsService                          |
| —      | `service/LlmProviderRouter.java`                                                           | LLM Provider 路由                                           |
| —      | `service/ChatSessionRegistry.java`                                                         | WebSocket 会话注册表                                        |
| —      | `service/ChatGenerationStateService.java`                                                  | 生成状态管理                                                |
| —      | `service/FileTypeValidationService.java`                                                   | 文件类型校验                                                |
| —      | `service/ModelProviderConfigService.java`                                                  | Provider 配置                                               |
| —      | `service/ConversationService.java`                                                         | 对话历史与回合                                              |
| —      | `service/FileProcessingTask.java`（model）                                                 | 处理任务状态                                                |
| —      | `service/AgentToolRegistry.java`                                                           | ReAct Agent 工具注册                                        |
| —      | `service/UsageDashboardService.java`                                                       | 监控仪表板聚合                                              |

### 5.3 后端数据访问层（39 个文件）

| Fan-in | 文件                                                                                                                                                                                                               | 作用                      |
| ------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------- |
| 25     | `model/User.java`                                                                                                                                                                                                  | 用户 JPA 实体             |
| 23     | `repository/UserRepository.java`                                                                                                                                                                                   | 用户 JPA 仓库             |
| 17     | `exception/CustomException.java`                                                                                                                                                                                   | 自定义业务异常            |
| 16     | `model/FileUpload.java`                                                                                                                                                                                            | 文件上传 JPA 实体         |
| 16     | `repository/FileUploadRepository.java`                                                                                                                                                                             | 文件上传仓库              |
| 11     | `model/OrganizationTag.java`                                                                                                                                                                                       | 组织标签 JPA 实体（树形） |
| 11     | `repository/DocumentVectorRepository.java`                                                                                                                                                                         | 文档向量仓库              |
| —      | `model/ChunkInfo.java`                                                                                                                                                                                             | 分块元数据                |
| —      | `model/DocumentVector.java`                                                                                                                                                                                        | 文档向量元数据            |
| —      | `model/Conversation.java`, `ConversationSession.java`                                                                                                                                                              | 对话与回合                |
| —      | `model/InviteCode.java`                                                                                                                                                                                            | 邀请码                    |
| —      | `model/RechargeOrder.java`, `RechargePackage.java`                                                                                                                                                                 | 支付订单                  |
| —      | `model/RateLimitConfig.java`                                                                                                                                                                                       | 限流配置                  |
| —      | `model/ModelProviderConfig.java`                                                                                                                                                                                   | LLM Provider 配置         |
| —      | `model/UserDailyChatCount.java`, `DailyUsageStat.java`, `DailyReqCountStat.java`, `UserTokenRecord.java`                                                                                                           | 配额与统计                |
| —      | `model/Message.java`                                                                                                                                                                                               | WebSocket 消息            |
| —      | `model/RegistrationMode.java`                                                                                                                                                                                      | 注册模式枚举              |
| —      | `entity/EsDocument.java`                                                                                                                                                                                           | ES 文档实体               |
| —      | `entity/TextChunk.java`                                                                                                                                                                                            | ES 文本块                 |
| —      | `entity/SearchRequest.java`, `SearchResult.java`                                                                                                                                                                   | 搜索 DTO                  |
| —      | `repository/{ChunkInfo, Conversation, ConversationSession, InviteCode, ModelProviderConfig, OrganizationTag, RechargeOrder, RechargePackage, UserDailyChatCount, UserTokenRecord, Document, Redis}Repository.java` | Spring Data 仓库          |
| —      | `exception/{CustomException, InvalidTokenException, RateLimitExceededException}.java`                                                                                                                              | 异常                      |

### 5.4 后端基础设施层（57 个文件）

| Fan-in | 文件                                                                                                                                                               | 作用                                                                   |
| ------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------- |
| 23     | `utils/JwtUtils.java`                                                                                                                                              | **JWT 签发 / 解析 / 刷新 / 失效**                                      |
| 22     | `resources/application.yml`                                                                                                                                        | Spring Boot 主配置                                                     |
| 20     | `config/KafkaConfig.java`                                                                                                                                          | Kafka 生产者/消费者配置                                                |
| 19     | `SmartPaiApplication.java`                                                                                                                                         | Spring Boot 启动类                                                     |
| 19     | `utils/LogUtils.java`                                                                                                                                              | 业务/性能/聊天日志工具                                                 |
| 17     | `config/LoggingInterceptor.java`                                                                                                                                   | HTTP 请求日志拦截器                                                    |
| 16     | `consumer/FileProcessingConsumer.java`                                                                                                                             | Kafka 消费者，驱动解析与向量化                                         |
| 14     | `config/SecurityConfig.java`                                                                                                                                       | Spring Security 过滤器链 + 公共/用户/管理员路径授权                    |
| 13     | `config/JwtAuthenticationFilter.java`                                                                                                                              | JWT 认证过滤器（解析 Authorization / 自动刷新 / 写入 SecurityContext） |
| 12     | `config/EsConfig.java`                                                                                                                                             | ES 客户端配置（host/port/scheme/Basic auth/可选 SSL）                  |
| 11     | `config/MinioConfig.java`                                                                                                                                          | MinIO 客户端配置                                                       |
| 11     | `config/QuotaConfiguration.java`                                                                                                                                   | 配额相关 bean 装配                                                     |
| —      | `config/EsIndexInitializer.java`                                                                                                                                   | 启动时检查/创建 ES 索引                                                |
| —      | `config/SecurityConfig.java`                                                                                                                                       | （已列）                                                               |
| —      | `config/AsyncExecutorConfig.java`                                                                                                                                  | 异步执行器                                                             |
| —      | `config/RedisConfig.java`                                                                                                                                          | Redis 客户端                                                           |
| —      | `config/WebConfig.java`                                                                                                                                            | Web MVC 配置（CORS / 拦截器）                                          |
| —      | `config/WebClientConfig.java`                                                                                                                                      | WebClient（DeepSeek 流式调用）                                         |
| —      | `config/OrgTagInitializer.java`, `AdminUserInitializer.java`, `BootstrapKnowledgeInitializer.java`                                                                 | 启动期初始化                                                           |
| —      | `config/AppAuthProperties.java`, `AiProperties.java`, `RateLimitProperties.java`, `UsageQuotaProperties.java`                                                      | `@ConfigurationProperties`                                             |
| —      | `config/ProductionConfigValidator.java`                                                                                                                            | 生产环境配置校验                                                       |
| —      | `config/DotenvEnvironmentPostProcessor.java`                                                                                                                       | 加载 `.env` 到 Spring Environment                                      |
| —      | `client/DeepSeekClient.java`, `EmbeddingClient.java`                                                                                                               | 外部 AI 客户端                                                         |
| —      | `utils/SecretCryptoService.java`, `HttpRequestUtil.java`, `JsonUtil.java`, `PriceUtil.java`, `PasswordUtil.java`, `MinioMigrationUtil.java`, `GenerateJwtKey.java` | 工具                                                                   |
| —      | `consumer/FileProcessingConsumer.java`                                                                                                                             | （已列）                                                               |
| —      | `resources/application-{dev, docker, prod, test}.yml`                                                                                                              | profile 配置                                                           |
| —      | `resources/logback-spring.xml`                                                                                                                                     | 日志（业务/性能/聊天/错误四类 appender）                               |
| —      | `resources/es-mappings/knowledge_base.json`                                                                                                                        | ES 索引 mapping                                                        |
| —      | `resources/META-INF/spring.factories`                                                                                                                              | Spring Boot SPI                                                        |
| —      | `resources/static/test.html`                                                                                                                                       | API 测试页                                                             |
| —      | `src/test/resources/application-test.yml`, `application.yml`, `mockito-extensions/org.mockito.plugins.MockMaker`                                                   | 测试基座                                                               |
| —      | `src/test/java/com/yizhaoqi/smartpai/{service, util, SmartPaiApplicationTests}.java`                                                                               | JUnit 单测                                                             |
| —      | `test/dto/TestResponse.java`, `test/{TestEntity, TestEntityRepository, TransactionTestService, TransactionTestController}.java`                                    | 事务测试基座                                                           |

### 5.5 数据库与模式层（12 个节点）

**MySQL DDL（11 张表）** — `docs/databases/ddl.sql`：

- `users` — 账号、密码、角色、组织标签、主组织、审计时间戳
- `organization_tags` — 树形组织标签（多租户隔离）
- `file_upload` — MD5、大小、状态、orgTag、embedding/chunk 计数
- `chunk_info` — 文件分片（file_md5 + chunk_index 唯一）
- `document_vectors` — 文档向量
- `rate_limit_configs` — 限流配置
- `model_provider_configs` — LLM Provider 配置（API Key 加密）
- `recharge_packages` — 充值套餐
- `recharge_orders` — 充值订单
- `user_token_record` — Token 流水
- `user_daily_chat_count` — 日维度统计

**ES 索引** — `src/main/resources/es-mappings/knowledge_base.json`：`text` + `dense_vector` 双字段，支持 Hybrid Search。

### 5.6 部署与项目配置（后端相关）

- `docs/docker-compose.yaml` — 一键拉起 MySQL/MinIO/Redis/Kafka/Elasticsearch/minio-init（含 healthcheck + IK 分词插件安装 + Kafka 主题自动创建）
- `infra.sh` — 本地 minio/kafka/elasticsearch 生命周期管理（含 Java 版本探测、端口校验、HTTP 200 轮询）
- `launch.sh.example` — 服务器 jar 部署脚本
- `pom.xml` — 后端 Maven 父 POM（Spring Boot 3.4.2 + Java 17）
- `.env.example` — 50+ 环境变量示例（MySQL/Redis/Kafka/MinIO/ES/JWT/AI/部署目标/微信支付）

---

## 6. 后端复杂度热点（重点关注的复杂文件）

新成员第一次接触这些文件前，建议先看相关的单元测试：

| 文件                                                                                         | 风险点                                                                  |
| -------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------- |
| `config/BootstrapKnowledgeInitializer.java`                                                  | 启动时把 `docs/paismart.pdf` 自动入库，开箱即用；改这里会影响所有新部署 |
| `config/OrgTagAuthorizationFilter.java`                                                      | 多租户权限核心，改之前必须画清「资源 → 标签 → 用户」链路                |
| `service/ChatHandler.java`（fan-out 41）                                                     | **RAG 主链路**，**改之前先跑 `ChatHandlerRetrievalPolicyTest`**         |
| `service/UserService.java`                                                                   | 注册策略、邀请码、组织标签循环检测                                      |
| `utils/JwtUtils.java`（fan-in 23）                                                           | **鉴权核心**，改 token 签发逻辑会影响所有受保护接口                     |
| `service/UsageQuotaService.java` / `UsageBalanceQuotaService.java` / `UserTokenService.java` | **配额扣减逻辑**，改之前要理解 LLM token 预留 / 结算 / 流水             |
| `service/TokenCacheService.java`                                                             | JWT 黑名单 + 用户 token 集合，改之前要理解 Redis 数据结构               |
| `service/HybridSearchService.java`                                                           | BM25 + 向量融合，权重调整直接影响召回质量                               |
| `consumer/FileProcessingConsumer.java`                                                       | Kafka 消费者，改之前要理解重试 / 死信 / 幂等                            |

---

## 7. 第一次把后端跑起来（5 步）

1. **准备 `.env`** — 复制 `.env.example` 到 `.env`，填好 MySQL/Redis/Kafka/MinIO/ES/JWT_SECRET_KEY（用 `openssl rand -base64 32` 生成）。
2. **拉起基础服务** — `cd docs && docker-compose up -d`（或 `./infra.sh start`）。
3. **启动后端** — `mvn spring-boot:run`（或 IDE 跑 `SmartPaiApplication`），首次启动会触发 `BootstrapKnowledgeInitializer` 把 `docs/paismart.pdf` 自动入库。
4. **健康检查** — `curl http://localhost:8080/api/v1/health`（或 `actuator/health`）。
5. **联调 RAG** — 借助 `src/main/resources/static/test.html` 走一遍「注册 → 登录 → 上传 PDF → 聊天问问题 → 看引用」。

---

## 8. 推荐学习资源（后端优先）

- **20 集 YouTube 教程文案**（`youtube_scripts/01-20-*.md`）— 一集对应一个 PaiSmart 后端模块：
  - 01 Spring Boot 骨架
  - 02 JPA 实体
  - 03 JWT 认证
  - 04 Spring Security
  - 05 MinIO 对象存储
  - 06 文档解析流水线
  - 07 Embedding Client
  - 08 Elasticsearch 向量
  - 09 Kafka 异步
  - 10 WebSocket + DeepSeek
  - 11 多租户 OrgTag
  - 12 混合检索 rerank
  - 13 限流配额
  - 14 会话
  - 15 ReAct Agent
  - 16 LLM Provider Router
  - 17 充值支付
  - 18 邀请码
  - 19 消息反馈
  - 20 可观测性

- **JWT 系列讲义**（`handouts/jwt-series/ep01-07-*.md`）— 7 集 JWT 教程：Cookie → Session → JWT → 存储 → XSS → CSRF → 双 Token。
- **后端架构 wiki**（`.qoder/repowiki/zh/content/后端架构/`）— 中文后端架构文档（API 接口、安全机制、实时通信、数据库设计、核心模块的服务层/控制器层/数据访问层）。

---

## 9. 故障排查（后端常见问题）

| 现象                       | 排查路径                                                                                                                                     |
| -------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- |
| 启动报 `JWT_SECRET_KEY` 错 | `openssl rand -base64 32` 生成新密钥填到 `.env`                                                                                              |
| 文档上传后没有向量         | 看 `docs/chunking-optimization-plan.md`；检查 Kafka 消费者是否在跑（`./infra.sh status` + `kafka-console-consumer --topic file-processing`） |
| 跨租户数据串了             | 立即检查 `OrgTagAuthorizationFilter` 和 JWT 中的 `orgTags` 字段                                                                              |
| LLM 限流 429               | `UsageQuotaService` + `AdminController` 的限流配置                                                                                           |
| WebSocket 断开             | 看 `docs/chat-reconnect-smoke-test.md` 跑 `scripts/chat-reconnect-smoke-test.mjs`                                                            |
| 混合检索召回差             | 调 `HybridSearchService` 的 BM25/向量权重（线上 AB）                                                                                         |
| 启动找不到 Bean            | 检查 `@SpringBootApplication` 的 `scanBasePackages` + 各 `@Component`/`@Service` 注解                                                        |

完整故障清单：`.qoder/repowiki/zh/content/故障排除/`

---

## 附录：后端知识图谱统计

- **后端相关节点**：176（4 个 layer 的 file-level + 部署配置中后端相关部分）
- **核心 Java 包**：`com.yizhaoqi.smartpai.{client, config, consumer, controller, entity, exception, handler, model, repository, service, test, utils}`
- **总文件数**：710（其中后端 Java 约 90 + 资源 5 + 测试 5 = 约 100 个）
- **git commit**：`59ec52246bb387206bd2da2fda4f2d32c3122121`

可执行 `/understand-dashboard` 启动交互式图谱浏览器（仅看后端 layer），或 `/understand-chat <question>` 问后端相关问题。
