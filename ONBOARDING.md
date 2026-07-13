# Folio（PaperLoom）新成员上手指南

> 由 `/.understand-anything/knowledge-graph.json` 自动生成 · 2026-06-07 · commit `59ec5224`
>
> 适合对象：第一次接触本项目的新成员（后端 / 前端 / 全栈 / 运维 / 产品）

---

## 1. 项目全景

**Folio（PaperLoom）** 是一套**企业级 AI 知识库管理系统**。核心是 **RAG（Retrieval-Augmented Generation）** 流水线：把企业内部的 PDF/Word 等异构文档解析、切片、向量化，落到 Elasticsearch 中按语义检索，再喂给 LLM（DeepSeek / 自定义 Provider）做带引用的回答。

- **后端**：Spring Boot 3.4.2（Java 17）+ Spring Data JPA + Spring Security + Spring WebFlux/WebSocket
- **前端**：Vue 3 + TypeScript + Vite + Naive UI + Pinia + vue-router + UnoCSS
- **存储**：MySQL（业务数据）+ Elasticsearch 8.10（向量 + 全文）+ MinIO（对象存储）+ Redis（缓存/限流）+ Kafka（异步消息）
- **AI**：DeepSeek API（LLM 推理）+ 豆包 / 自定义 Embedding（向量化）
- **多租户**：通过**组织标签（OrgTag）** 实现企业 / 部门 / 团队的数据隔离
- **业务亮点**：文档上传与异步处理、WebSocket 实时流式聊天、微信支付充值、邀请码注册、JWT + 组织标签鉴权、限流配额、可观测性

**版本**：1.3.13（前端 package.json）/ `0.1.0-SNAPSHOT`（后端 pom.xml）
**仓库**：https://github.com/CHZarles/paperloom
**入口文档**：
- [README.md](../README.md) — 项目介绍、架构、跑起来的步骤
- [CLAUDE.md](../CLAUDE.md) — Claude Code 协作约定（开发准则、运行命令、测试流程）

---

## 2. 架构分层（10 层）

整个项目在知识图谱里被切成 10 个**架构层（layer）**，建议新成员按以下顺序理解：

| 层级 | 名称 | 职责 | 文件数 |
|---|---|---|---|
| 1 | **后端 API 接入层** | REST 控制器、WebSocket 端点 | 12 |
| 2 | **后端业务服务层** | 28 个 Service 与对应测试，承载核心业务逻辑 | 38 |
| 3 | **后端数据访问层** | JPA 实体、Model、Repository、Exception | 39 |
| 4 | **后端基础设施层** | Config、DeepSeek/Embedding 客户端、Kafka 消费者、JWT/加密工具、application*.yml | 57 |
| 5 | **数据库与模式层** | MySQL DDL（11 张表）+ ES 索引映射 | 12 |
| 6 | **部署与项目配置** | docker-compose、infra.sh、launch.sh、pom.xml、package.json、tsconfig、.env | 19 |
| 7 | **前端 UI 组件层** | views、layouts、可复用业务组件 | 87 |
| 8 | **前端应用核心** | Pinia store、router、axios/alova、hooks、plugins、utils、locale、theme | 104 |
| 9 | **前端 monorepo 共享包** | pnpm workspace 下的 alova/axios/color/hooks/materials/ofetch/scripts/uno-preset/utils | 87 |
| 10 | **项目文档** | README/CLAUDE/AGENTS/TODO + `.qoder/repowiki/zh/` 架构 wiki + 20 集 YouTube 教程 + JWT 系列讲义 | 263 |

**理解架构的小窍门**：从「**API 接入层**」看起（你点哪个 URL 会进哪个 Controller），跟着 fan-in（被多少个上游调用）逆流而上：Controller → Service → Repository → Database。

---

## 3. 关键概念与设计决策

- **RAG 流水线（异步）**：上传（HTTP）→ MinIO 存文件 → Kafka 投递消息 → `FileProcessingConsumer` 异步消费 → `ParseService`（Tika 解析 + 父子分块）→ `VectorizationService`（Embedding）→ `ElasticsearchService`（索引）。这种「**接口同步 + 消费异步**」的拆分是大文件解析场景下的标准做法，能让上传请求秒级返回。
- **混合检索（Hybrid Search）**：把「**稀疏检索**」（BM25 关键词，强在专有名词 / 字面命中）和「**稠密检索**」（向量余弦相似度，强在语义同义改写）做线性加权，工程上需要在 ES 一个索引里同时存 `text` 和 `dense_vector` 两个字段。线上权重通常由 AB 测试调出。
- **多租户（组织标签）**：用户 → 多对多 → `OrganizationTag`（树形，带父标签），文件带 `orgTag` 字段。`OrgTagAuthorizationFilter` 在每次请求时校验「资源拥有者 / 组织标签匹配 / 是否公开」三段。
- **JWT 双 Token + Redis 黑名单**：access token（短）+ refresh token（长），`TokenCacheService` 维护 tokenId → 用户 + 过期时间，过期则失效。`JwtAuthenticationFilter` 在临近过期时自动刷新。
- **LLM Provider Router**：`LlmProviderRouter` 按运行时数据库配置选择 OpenAI 兼容或自定义端点（DeepSeek / 豆包 / Ollama 等），用 `SecretCryptoService` 加密存储 API Key。
- **限流配额**：`UsageQuotaService` 基于 Redis Lua 做分钟/天/全局三档令牌桶；`RateLimitService` + `RateLimitConfigService` 提供注解式限流。

---

## 4. 13 步导览（按推荐阅读顺序）

下表是知识图谱自带的 **guided tour**，从「看 README」开始，到「把 docker-compose 跑起来」结束。

| # | 标题 | 关键文件 | 教学点 |
|---|---|---|---|
| 1 | 项目全景 | `README.md`, `CLAUDE.md` | — |
| 2 | 后端启动入口 | `src/main/java/io/github/chzarles/paperloom/PaperLoomApplication.java` | — |
| 3 | 文档上传与 Kafka 异步流水线 | `controller/UploadController.java`, `consumer/FileProcessingConsumer.java`, `config/KafkaConfig.java` | **Spring Kafka @KafkaListener**：topic → 普通方法，消息偏移、并发、重试由容器托管 |
| 4 | 解析、切块与向量化 | `service/ParseService.java`, `service/VectorizationService.java`, `client/EmbeddingClient.java`, `model/FileUpload.java`, `model/ChunkInfo.java` | — |
| 5 | Elasticsearch 存储与混合检索 | `config/EsConfig.java`, `service/ElasticsearchService.java`, `service/HybridSearchService.java`, `entity/EsDocument.java` | **Hybrid Search**：稀疏 + 稠密双索引融合 |
| 6 | AI 对话主链路 | `handler/ChatWebSocketHandler.java`, `service/ChatHandler.java`（fan-out 41，全仓第一）, `service/LlmProviderRouter.java`, `client/DeepSeekClient.java` | — |
| 7 | 多租户认证与组织标签鉴权 | `config/SecurityConfig.java`, `config/JwtAuthenticationFilter.java`, `utils/JwtUtils.java`（fan-in 23）, `config/OrgTagAuthorizationFilter.java`, `service/UserService.java`, `service/OrgTagCacheService.java` | **Spring Security 过滤器链**：按顺序叠加，每个过滤器只管自己 |
| 8 | 数据库 Schema 全貌 | `docs/databases/ddl.sql` | — |
| 9 | 前端启动入口与根组件 | `frontend/src/main.ts`, `frontend/src/App.vue` | — |
| 10 | AI 对话页面 | `frontend/src/views/chat/index.vue` | — |
| 11 | 聊天 Pinia Store | `frontend/src/store/modules/chat/index.ts` | — |
| 12 | 前端路由与构建 | `frontend/src/router/elegant/routes.ts`, `frontend/vite.config.ts` | — |
| 13 | 基础设施编排与本地生命周期 | `docs/docker-compose.yaml`, `config/MinioConfig.java`, `infra.sh` | **docker-compose healthcheck + depends_on.condition**：保证服务按正确顺序启动 |

---

## 5. 各层关键文件（按 fan-in 排序）

### 5.1 后端 API 接入层（12 个文件）

| Fan-in | 文件 | 作用 |
|---|---|---|
| 17 | `controller/ChatWebSocketHandler.java` | WebSocket 消息处理器，转发用户查询到 `ChatHandler` |
| 13 | `controller/AdminController.java` | 管理员后台 REST 控制器（用户、活动、仪表盘、限流、模型提供商、邀请码、组织标签、充值、MinIO 迁移） |
| 12 | `controller/UploadController.java` | 文件上传 REST 控制器 |
| 9  | `controller/UserController.java` | 用户 REST 控制器（注册/登录/当前用户/组织标签/登出/配额/token 流水） |

### 5.2 后端业务服务层（38 个文件，28 Service + 10 单测）

| Fan-in | 文件 | 作用 |
|---|---|---|
| 17 | `service/VectorizationService.java` | 向量化服务 |
| 17 | `service/ParseService.java` | Tika 解析 + 父子分块 |
| 16 | `service/ElasticsearchService.java` | ES 索引 + DSL 查询 + 向量检索 + 高亮 |
| 15 | `service/ChatHandler.java` | **聊天业务核心**：混合检索 → 提示词 → LLM 流式 → 引用归一化 |
| 15 | `service/HybridSearchService.java` | BM25 + 向量余弦融合 |

### 5.3 后端数据访问层（39 个文件）

| Fan-in | 文件 | 作用 |
|---|---|---|
| 25 | `model/User.java` | 用户 JPA 实体（账号/密码/角色/组织标签/时间戳） |
| 23 | `repository/UserRepository.java` | 用户 JPA 仓库 |
| 17 | `exception/CustomException.java` | 自定义业务异常 |
| 16 | `model/FileUpload.java` | 文件上传 JPA 实体 |
| 16 | `repository/FileUploadRepository.java` | 文件上传仓库 |

### 5.4 后端基础设施层（57 个文件）

| Fan-in | 文件 | 作用 |
|---|---|---|
| 23 | `utils/JwtUtils.java` | **JWT 签发 / 解析 / 刷新 / 失效** |
| 22 | `resources/application.yml` | Spring Boot 主配置 |
| 20 | `config/KafkaConfig.java` | Kafka 生产者/消费者配置 |
| 19 | `PaperLoomApplication.java` | Spring Boot 启动类 |
| 19 | `utils/LogUtils.java` | 业务/性能/聊天日志工具 |

### 5.5 数据库与模式层（12 个节点）

- `docs/databases/ddl.sql` — **MySQL 全部 11 张表的 DDL 真相源**（users, organization_tags, file_upload, chunk_info, document_vectors, rate_limit_configs, model_provider_configs, recharge_packages, recharge_orders, user_token_record, user_daily_chat_count）
- `resources/es-mappings/knowledge_base.json` — ES 索引 mapping

### 5.6 部署与项目配置（19 个文件）

- `docs/docker-compose.yaml` — 一键拉起 MySQL/MinIO/Redis/Kafka/Elasticsearch/minio-init
- `infra.sh` — 本地 minio/kafka/elasticsearch 生命周期管理
- `deploy-front.sh` — 前端构建 + scp 上传 + 健康检查
- `pom.xml` — 后端 Maven 父 POM
- `frontend/package.json` + `pnpm-workspace.yaml` — 前端 pnpm monorepo
- `.env.example` — 50+ 环境变量示例

---

## 6. 前端各层关键文件

### 6.1 前端应用核心（104 个文件）

- `frontend/src/main.ts` — Vue 真正起跑线（loading/NProgress/iconify/dayjs/Pinia/Router/i18n/版本通知）
- `frontend/src/App.vue` — 根组件 + 全局异常边界
- `frontend/src/router/elegant/{routes.ts, transform.ts}` — 声明式路由（Soybean 方案）
- `frontend/src/router/guard/{index, route, progress, title}.ts` — 路由守卫
- `frontend/src/store/modules/{auth, route, tab, theme, app, chat, knowledge-base}/index.ts` — 7 个 Pinia store
- `frontend/src/service/request/index.ts` — Alova + Axios 工厂，JWT 注入 + 401 自动登出
- `frontend/src/service/api/*.ts` — 业务 API 模块（auth、invite-code、org-tag、recharge、route、knowledge-base、chat）
- `frontend/src/hooks/business/{auth, captcha}.ts` + `hooks/common/{echarts, form, icon, router, table}.ts` — 业务 + 通用 hooks

### 6.2 前端 monorepo 共享包（87 个文件，pnpm workspace）

| 包 | 作用 |
|---|---|
| `packages/alova` | Alova 请求库二次封装（含 mock） |
| `packages/axios` | Axios 工厂（createCommonRequest / createRequest / createFlatRequest） |
| `packages/color` | HSL/RGB 转换 + AntD 调色板 + colord 封装 |
| `packages/hooks` | use-boolean / use-context / use-count-down / use-loading / use-request / use-signal / use-svg-icon-render / use-table |
| `packages/materials` | admin-layout + page-tab + simple-scrollbar 可复用组件 |
| `packages/ofetch` | ofetch 包装 |
| `packages/scripts` | sa 命令行（git-commit / changelog / release / router / cleanup） |
| `packages/uno-preset` | UnoCSS 项目级 preset |
| `packages/utils` | crypto / klona / nanoid / storage 工具 |

### 6.3 前端 UI 组件层（87 个文件）

- `frontend/src/views/{chat, knowledge-base, user, recharge, invite-code, org-tag, model-provider, usage-monitor, personal-center, chat-history}/index.vue` — 10 大业务页
- `frontend/src/layouts/{base-layout, blank-layout}/index.vue` + `layouts/modules/{global-header, global-sider, global-tab, global-menu, global-content, global-breadcrumb, global-footer, global-logo, global-search, theme-drawer}/` — 布局系统
- `frontend/src/components/{common, advanced, custom}/` — 通用 + 高级 + 业务组件

---

## 7. 复杂度热点（重点关注的复杂文件）

新成员第一次接触这些文件前，建议先看相关的单元测试和教程视频：

| 文件 | 复杂度 | 风险点 |
|---|---|---|
| `config/BootstrapPaperInitializer.java` | complex | 启动时把 `data/2412.08972.pdf` 自动入库，开箱即用；改这里会影响所有新部署 |
| `config/OrgTagAuthorizationFilter.java` | complex | 多租户权限核心，改之前必须画清资源 → 标签 → 用户的链路 |
| `service/ChatHandler.java`（fan-out 41） | 全仓业务核心 | RAG 主链路，**改之前先跑 `ChatHandlerRetrievalPolicyTest`** |
| `service/UserService.java` | complex | 注册策略、邀请码、组织标签循环检测 |
| `utils/JwtUtils.java`（fan-in 23） | **鉴权核心** | 改 token 签发逻辑会影响所有受保护接口 |
| `service/UsageQuotaService.java` / `UsageBalanceQuotaService.java` / `UserTokenService.java` | complex | **配额扣减逻辑**，改之前要理解 LLM token 预留 / 结算 / 流水 |
| `store/modules/route/index.ts` + `shared.ts` | complex | 前端**动态路由**生成，权限变更会失效 |
| `router/elegant/transform.ts` | complex | 声明式路由 → Vue Router 转换，新增页面出问题先看这里 |
| `views/chat/index.vue` | — | 前端业务最复杂页，对应后端 `ChatWebSocketHandler` + `ChatHandler` |

---

## 8. 第一次跑起来的 5 步（环境搭建 → 跑通 RAG）

1. **准备 `.env`** — 复制 `.env.example` 到 `.env`，填好 MySQL / Redis / Kafka / MinIO / ES / JWT 密钥。
2. **拉起基础服务** — `cd docs && docker-compose up -d`（或 `./infra.sh start`）。
3. **启动后端** — `mvn spring-boot:run`（或 IDE 跑 `PaperLoomApplication`），首次启动会触发 `BootstrapPaperInitializer` 把 `data/2412.08972.pdf` 自动入库。
4. **启动前端** — `cd frontend && pnpm install && pnpm dev`，浏览器开 `http://localhost:9527`（或 8081，详见 `frontend/.env.test`）。
5. **测试 RAG 链路** — 注册账号 → 知识库页上传一个 PDF → 聊天页问一个与 PDF 相关的问题 → 应该看到「引用」标签能跳到 PDF 原文片段。

---

## 9. 推荐学习资源（项目自带）

- **20 集 YouTube 教程文案**（`youtube_scripts/01-20-*.md`）— 从 Spring Boot 骨架、JPA 实体、JWT、Spring Security、MinIO、Tika、Embedding、Elasticsearch、Kafka、WebSocket+DeepSeek、OrgTag 多租户、混合检索、限流、会话、ReAct Agent、LLM Router、微信支付、邀请码、消息反馈、可观测性 — 一集对应一个 PaperLoom 代码模块。
- **JWT 系列讲义**（`handouts/jwt-series/ep01-07-*.md`）— 7 集 JWT 教程：Cookie → Session → JWT → 存储 → XSS → CSRF → 双 Token。
- **架构 wiki**（`.qoder/repowiki/zh/content/`）— 中文架构文档：项目概述、后端架构（API/安全/实时通信/数据库/核心模块）、前端架构（状态/路由/组件/项目结构）、数据模型、API 参考、RAG 系统实现、部署指南、故障排除、技术面试文档。

---

## 10. 故障排查与帮助

- 启动报 `JWT_SECRET_KEY` 错 → 用 `openssl rand -base64 32` 生成新密钥填到 `.env`
- 聊天页连接断开 → 看 `docs/chat-reconnect-smoke-test.md` 跑 `scripts/chat-reconnect-smoke-test.mjs`
- 文档上传后没有向量 → 看 `docs/chunking-optimization-plan.md`，检查 Kafka 消费者是否在跑
- 跨租户数据串了 → 立即检查 `OrgTagAuthorizationFilter` 和 JWT 中的 `orgTags` 字段
- LLM 限流 429 → `UsageQuotaService` + `AdminController` 的限流配置
- 完整故障清单：`.qoder/repowiki/zh/content/故障排除/`

---

## 附录：知识图谱统计

- **1233 节点**（399 file · 94 class · 421 function · 261 document · 39 config · 11 table · 7 service · 1 data）
- **2385 边**（830 documents · 592 contains · 421 imports · 258 exports · 150 related · 49 configures · 42 calls · 31 depends_on · 8 tested_by · 2 deploys · 2 defines_schema）
- **710 个源文件**（388 code · 267 docs · 39 config · 11 markup · 3 script · 1 infra · 1 data）
- **git commit**：`59ec52246bb387206bd2da2fda4f2d32c3122121`

可执行 `/understand-dashboard` 启动交互式图谱浏览器深入探索，或 `/understand-chat` 提问。
