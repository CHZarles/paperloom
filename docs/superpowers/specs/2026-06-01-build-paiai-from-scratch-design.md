# 「从零撸 PaiSmart」教程系列 — 设计文档

> 日期：2026-06-01
> 作者：Claude (协作)
> 状态：待用户批准

## 一、目标

为已经读过 `youtube_scripts/01-20` 视频脚本的读者，写一份**配套的"从零撸"实战教程**：

- 用户跟着教程能**从空 Maven 项目撸出一个完整可跑的 RAG 系统**。
- **不砍功能**——分析系列的 20 个模块全部覆盖。
- 形式：**每集是「分析 + 完整代码」一体**——前半讲为什么，后半给能跑的代码。

## 二、形式决策（已和用户对齐）

| 决策项 | 选择 |
|---|---|
| 总集数 | 20+ 集 |
| 单集粒度 | 每集一个模块（与分析系列 1:1 对应） |
| 节奏 | **里程碑式**——每集后系统可跑（13 个里程碑） |
| 外部服务 | **真实 API**（DeepSeek / DashScope / 微信支付） |
| 代码完整度 | **主路生产 + 支路 TODO**——核心业务严谨，辅助功能用 TODO 标注 |
| 目录 | `youtube_scripts/build-from-scratch/` |
| 命名 | `bfs-XX-<topic>.md`（与原 `01-` 前缀区分） |

## 三、13 个里程碑 / 20 集 详细规划

**重要说明**：原分析系列按"演进历史"编号（ep10 聊天 / ep15 ReAct / ep16 router），但**教程必须按依赖顺序写**。本系列重新编号 `bfs-01` ~ `bfs-20`，与原分析集的对应关系在 `README.md` 的"对照表"里。

### M0 — 项目骨架（1 集）
- **bfs-01-spring-boot-skeleton.md**（对应分析集 01）
  - 完整 pom.xml（Java 17、Spring Boot 3.4.2，groupId=`charles`，artifactId=`easyrag`）
  - 主启动类（`charles.easyrag.EasyRagApplication`）、application.yml、dev/docker/prod 三 profile
  - 验证：`mvn spring-boot:run` 起得来

### M1 — 用户体系（3 集）
- **bfs-02-jpa-user-entity.md**（对应分析集 02）：User entity、UserRepository、Flyway migration、BCrypt 工具
- **bfs-03-jwt-auth.md**（对应分析集 03）：JwtUtils（生成/验证/双 token）、TokenCacheService（Redis）、@Value 注入密钥
- **bfs-04-spring-security.md**（对应分析集 04）：SecurityConfig（lambda DSL）、JwtAuthenticationFilter、CustomUserDetailsService、登录/注册/登出接口
- **验证**：注册→登录→拿 token→访问受保护接口

### M2 — 文件存储（2 集）
- **bfs-05-minio-upload.md**（对应分析集 05）：MinioConfig、UploadController（分片上传）、FileUpload 实体
- **bfs-06-tika-parsing.md**（对应分析集 06）：ParseService（Tika + PDFBox + HanLP）、父-子块策略
- **验证**：上传 PDF → 解析出 chunk → 落 DB

### M3 — 向量化与检索（2 集）
- **bfs-07-embedding-client.md**（对应分析集 07）：EmbeddingClient（WebClient）、配额预留/结算、批处理
- **bfs-08-elasticsearch-vector.md**（对应分析集 08）：EsConfig、EsIndexInitializer（dense_vector 1024）、ElasticsearchService
- **验证**：上传→自动向量化→ES 召回 top-K

### M4 — 异步流水线（1 集）
- **bfs-09-kafka-async.md**（对应分析集 09）：KafkaConfig、FileProcessingConsumer、状态机、死信队列
- **验证**：上传→立即返回 200→Kafka 异步处理→查状态 PENDING → COMPLETED

### M5 — 对话能力基础（1 集）
- **bfs-10-websocket-deepseek.md**（对应分析集 10）：WebSocketConfig、ChatWebSocketHandler（握手鉴权 + 心跳）、DeepSeekClient（流式）
- **验证**：浏览器连 WebSocket → 提问 → 流式回答（**不接 RAG、不接 Agent**——纯 LLM）

### M6 — 多租户（1 集）
- **bfs-11-multi-tenant-orgtag.md**（对应分析集 11）：OrganizationTag 实体、OrgTagAuthorizationFilter、PRIVATE_ 前缀
- **验证**：A 注册 → 上传文件 → B 搜不到

### M7 — 混合检索（1 集）
- **bfs-12-hybrid-search-rerank.md**（对应分析集 12）：HybridSearchService（BM25 + kNN + rescore）、Context 注入
- **验证**：搜「派聪明」能召回「pai-smart」

### M8 — 限流配额（1 集）
- **bfs-13-rate-limit-quota.md**（对应分析集 13）：RateLimitService、UsageQuotaService、配额预留/结算
- **验证**：脚本小子 1 秒刷 100 次 → 第 11 次 429

### M9 — 对话历史（1 集）
- **bfs-14-conversation-session.md**（对应分析集 14）：Conversation、ConversationSession、滑动窗口历史
- **验证**：问 A→问 B→回到 A 仍能续传

### M10 — Agent 进阶（1 集）
- **bfs-15-react-agent.md**（对应分析集 15）：AgentToolRegistry、ChatHandler.runReActLoop、MAX_REACT_ROUNDS
- **验证**：问"派聪明和 LangChain 哪个好"→ AI 自动决定要不要搜

### M11 — 多 LLM 路由（1 集）
- **bfs-16-llm-provider-router.md**（对应分析集 16）：ModelProviderConfig、ModelProviderConfigService、LlmProviderRouter、A/B 灰度
- **验证**：DB 改 isActive → 切换 provider

### M12 — 充值（1 集）
- **bfs-17-recharge-payment.md**（对应分析集 17）：UserTokenRecord（@Version 乐观锁）、RechargeOrder、WxPayService（V3 协议 + mock 回调）
- **验证**：创建订单→mock 微信回调→订单到账→token 余额增加

### M13 — 邀请码（1 集）
- **bfs-18-invite-code.md**（对应分析集 18）：InviteCode、InviteCodeService、绑定关系
- **验证**：A 创建码 → B 注册时填 → A 和 B 都加 token

### M14 — 反馈（1 集）
- **bfs-19-message-feedback.md**（对应分析集 19）：MessageFeedback、buildRecentFeedbackGuidance、Agent 工具
- **验证**：👎 某回答 → 下次同主题 AI 主动避开

### M15 — 可观测性（1 集）
- **bfs-20-observability-production.md**（对应分析集 20）：LogUtils、AsyncExecutorConfig、ProductionConfigValidator、DotenvEnvironmentPostProcessor
- **验证**：dev 环境能起 → prod 环境少配就 fail

**最终集数**：20 集 / 15 里程碑（修改后比初稿多 2 个里程碑：M10 拆分、M11 拆分）

## 四、每集的统一结构

```markdown
# {集号} {标题}

> 上集回顾
> 本集目标：{一句能验证的话}

## 一、业务驱动（开场 Hook + 为什么）
## 二、设计决策（取舍分析 + 架构图）
## 三、完整代码
   ### 3.1 实体/配置（如有）
   ### 3.2 Service
   ### 3.3 Controller/API
   ### 3.4 配置文件改动
   ### 3.5 数据库 migration
## 四、验证步骤
   - 启动命令
   - curl 示例
   - 预期输出
## 五、常见坑（3-5 个）
## 六、思考题
## 七、下集预告
```

**主路生产代码**：完整的 entity / service / controller，含 validation、exception、transaction。
**支路 TODO**：辅助功能（如 i18n、详细监控 dashboard）只放 stub + TODO 注释。

## 五、目录结构

```
youtube_scripts/build-from-scratch/
├── README.md                            # 总目录 + 依赖清单
├── bfs-01-spring-boot-skeleton.md
├── bfs-02-jpa-user-entity.md
├── ... 
└── bfs-20-observability-production.md
```

**README.md** 内容：
- 系列简介
- 环境准备（Java 17、Maven 3.8+、Docker、API key 申请指南）
- 13 个里程碑速查表
- 学习路径建议（前端/后端/全栈）

## 六、交付节奏

- **按里程碑写**——每完成一个里程碑（1-3 集），用户验证可跑 → 继续下一个。
- 每个里程碑内：
  - 业务代码完整（主路生产）
  - 关键单元测试（service 层覆盖）
  - 集成测试 stub（API 端到端）
- 跳过/简化的内容**必须 TODO 注释 + 文档说明**

## 七、不在范围内

- 前端 Vue 代码——只提供 curl / 浏览器调用示例
- K8s 部署 / CI/CD——ep20 只讲 application-{profile}.yml 切换
- 复杂监控告警——只用 LogUtils，TODO 标注 Micrometer 接入点
- 微服务拆分——单模块 monorepo，符合 KISS

## 八、风险与缓解

| 风险 | 缓解 |
|---|---|
| 集数太多，写作耗时长 | 按里程碑分批交付，每批用户验证 |
| 代码量大，容易笔误 | 关键 service / controller 配 1-2 个 unit test 作为 sanity check |
| API key 申请门槛 | README 提供免费 key 申请路径（DeepSeek 注册送额度） |
| 微信支付需要商户号 | 早期写一个 mock 回调端点，后期再换真 API |
| 用户环境差异 | 强依赖 Docker Compose 起中间件，跨平台一致 |

## 九、成功标准

- 用户按 20 集撸完后，**整套 RAG 系统能跑**。
- 关键流程（注册→上传→搜索→聊天→充值→邀请→反馈）**全链路通**。
- 代码可读性 ≥ PaiSmart 原项目 80% 水平。
- 主路业务代码无 TODO 注释（除明确标注的扩展点）。

## 十、下一步

待用户批准本设计后，按 M0 → M1 → M2 顺序开写。**M0 + M1（约 4 集）作为第一批交付**，用户验证可注册登录后再继续。
