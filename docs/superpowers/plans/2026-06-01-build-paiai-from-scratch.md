# 「从零撸 PaiSmart」教程系列 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers-extended-cc:subagent-driven-development (recommended) or superpowers-extended-cc:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 PaiSmart RAG 系统的从零实战教程写 20 集长文稿（每集含分析 + 完整可跑代码），按 15 个里程碑分批交付，每批可验证。

**Architecture:** 在 `youtube_scripts/build-from-scratch/` 子目录写 21 个 markdown（1 README + 20 bfs-XX 集）。每集遵循 7 段固定结构（开场 / 业务驱动 / 设计决策 / 完整代码 / 验证步骤 / 常见坑 / 下集预告）。代码示例全部可跑，依赖 docker-compose 起中间件，真实 API key 在 README 提供申请指南。

**Tech Stack:** Spring Boot 3.4.2、Java 17、Maven 3.8+、MySQL 8、Redis 7、Elasticsearch 8.10、Kafka 3.2、MinIO、Tika、HanLP、DeepSeek API、DashScope Embedding API、微信支付 V3、Vue 3 前端（仅 curl 示例）。

**User Verification:** YES — 用户在每个里程碑完成后验证代码可跑，再批准进入下一里程碑。每个里程碑对应一个 `requiresUserVerification: true` 任务。

---

## File Structure

**创建的文件**（21 个）：

```
youtube_scripts/build-from-scratch/
├── README.md                            # 总目录 + 依赖清单 + 对照表
├── bfs-01-spring-boot-skeleton.md       # M0
├── bfs-02-jpa-user-entity.md            # M1
├── bfs-03-jwt-auth.md                   # M1
├── bfs-04-spring-security.md            # M1
├── bfs-05-minio-upload.md               # M2
├── bfs-06-tika-parsing.md               # M2
├── bfs-07-embedding-client.md           # M3
├── bfs-08-elasticsearch-vector.md       # M3
├── bfs-09-kafka-async.md                # M4
├── bfs-10-websocket-deepseek.md         # M5
├── bfs-11-multi-tenant-orgtag.md        # M6
├── bfs-12-hybrid-search-rerank.md       # M7
├── bfs-13-rate-limit-quota.md           # M8
├── bfs-14-conversation-session.md       # M9
├── bfs-15-react-agent.md                # M10
├── bfs-16-llm-provider-router.md        # M11
├── bfs-17-recharge-payment.md           # M12
├── bfs-18-invite-code.md                # M13
├── bfs-19-message-feedback.md           # M14
└── bfs-20-observability-production.md   # M15
```

**辅助文件**（在 M0 创建一次，永久使用）：
- `docs/superpowers/plans/build-paiai-helper.md` — 每集统一的写作模板 + 代码示例骨架

**依赖关系**：
- bfs-N 强依赖 bfs-1..N-1 的代码（教程累计型）
- 每集本身可独立成文（不依赖编辑器状态）

---

## Setup Task

### Task 0: 创建教程目录和写作模板

**Goal:** 准备好 21 个文件的占位 + 统一的写作模板

**Files:**
- Create: `youtube_scripts/build-from-scratch/README.md`（目录占位）
- Create: `docs/superpowers/plans/build-paiai-helper.md`（写作模板）

**Acceptance Criteria:**
- [ ] `youtube_scripts/build-from-scratch/` 目录存在
- [ ] README 占位文件存在
- [ ] 写作模板包含：每集 7 段结构骨架、代码示例的"主路生产 vs 支路 TODO"标记约定、frontmatter 模板

**Verify:** `ls youtube_scripts/build-from-scratch/ && cat docs/superpowers/plans/build-paiai-helper.md`

**Steps:**

- [ ] **Step 1: 创建目录**
```bash
mkdir -p /home/charles/PaiSmart/youtube_scripts/build-from-scratch
```

- [ ] **Step 2: 写 README 占位**
```markdown
# 从零撸 PaiSmart

20 集实战教程。每集：分析 + 完整可跑代码。

## 进度
- [ ] M0 项目骨架（bfs-01）
- [ ] M1 用户体系（bfs-02/03/04）
...（完成时勾选）

## 对照表
| 教程集 | 分析集 | 标题 |
|---|---|---|
| bfs-01 | 01 | Spring Boot 骨架 |
...
```

- [ ] **Step 3: 写写作模板**（`docs/superpowers/plans/build-paiai-helper.md`）—— 包含每集 7 段标题骨架、frontmatter 模板、代码块标记约定

- [ ] **Step 4: Commit**
```bash
git add youtube_scripts/build-from-scratch/ docs/superpowers/plans/build-paiai-helper.md
git commit -m "tutorial: 初始化从零撸 PaiSmart 教程目录与写作模板"
```

```json:metadata
{"files": ["youtube_scripts/build-from-scratch/README.md", "docs/superpowers/plans/build-paiai-helper.md"], "verifyCommand": "ls youtube_scripts/build-from-scratch/ && test -f docs/superpowers/plans/build-paiai-helper.md", "acceptanceCriteria": ["目录存在", "README 占位", "写作模板"], "requiresUserVerification": false}
```

---

## M0 — 项目骨架

### Task 1: 写 bfs-01-spring-boot-skeleton.md

**Goal:** 写完第 1 集，让读者能 `mvn spring-boot:run` 起来

**Files:**
- Create: `youtube_scripts/build-from-scratch/bfs-01-spring-boot-skeleton.md`

**Acceptance Criteria:**
- [ ] 文件存在且 ≥ 500 行
- [ ] 含 7 段固定结构（开场、业务驱动、设计决策、完整代码、验证步骤、常见坑、思考题+下集预告）
- [ ] 完整代码段含：完整 `pom.xml`（groupId=`charles`、artifactId=`easyrag`、Java 17、Spring Boot 3.4.2）、主启动类 `charles.easyrag.EasyRagApplication`、`application.yml`、dev/docker/prod 三 profile
- [ ] 全文所有 Java 代码使用 `package charles.easyrag`（**不用** `com.yizhaoqi.smartpai`）
- [ ] 验证步骤含具体命令（`mvn spring-boot:run`）和预期输出
- [ ] 至少 3 个常见坑 + 面试问答
- [ ] 末尾有下集预告指向 bfs-02

**Verify:**
```bash
test -f youtube_scripts/build-from-scratch/bfs-01-spring-boot-skeleton.md && \
wc -l youtube_scripts/build-from-scratch/bfs-01-spring-boot-skeleton.md | awk '{print $1}' | grep -E '^[5-9][0-9]{2}|[0-9]{4,}$' && \
grep -c '^## ' youtube_scripts/build-from-scratch/bfs-01-spring-boot-skeleton.md | grep -E '^[6-9]$'
```

**Steps:**

- [ ] **Step 1: 读对应分析集** —— `Read /home/charles/PaiSmart/youtube_scripts/01-spring-boot-skeleton.md` 取素材

- [ ] **Step 2: 写 bfs-01** —— 按 7 段结构 + frontmatter：
  - 重点：从零视角（用户还没创建任何东西）
  - 完整 `pom.xml`（Java 17、Spring Boot 3.4.2）
  - 主启动类
  - 三个 profile 配置
  - 验证命令：`mvn spring-boot:run` + `curl localhost:8080/actuator/health`

- [ ] **Step 3: 自查** —— 通读检查：代码块语法高亮、命令可执行性、章节编号连贯

- [ ] **Step 4: Commit**
```bash
git add youtube_scripts/build-from-scratch/bfs-01-spring-boot-skeleton.md
git commit -m "tutorial: bfs-01 Spring Boot 骨架（含完整 pom.xml + 三 profile）"
```

```json:metadata
{"files": ["youtube_scripts/build-from-scratch/bfs-01-spring-boot-skeleton.md"], "verifyCommand": "wc -l youtube_scripts/build-from-scratch/bfs-01-spring-boot-skeleton.md | awk '{print $1}' | grep -E '^[5-9][0-9]{2}|[0-9]{4,}$' && grep -c '^## ' youtube_scripts/build-from-scratch/bfs-01-spring-boot-skeleton.md | grep -E '^[6-9]$'", "acceptanceCriteria": ["≥ 500 行", "7 段结构", "含完整 pom", "验证命令可跑", "≥ 3 常见坑", "下集预告"], "requiresUserVerification": false}
```

### Task 2: M0 里程碑验证

**Goal:** 用户验证 bfs-01 教程可跟着跑通

**Files:** 无（仅验证）

**Acceptance Criteria:**
- [ ] 用户确认跟着 bfs-01 跑通了 `mvn spring-boot:run`
- [ ] 用户确认 dev / docker / prod 三个 profile 都能切换

**Verify:** 用户通过 AskUserQuestion 确认

**User Verification Required:**
Before marking this task complete, you MUST call AskUserQuestion:
```yaml
AskUserQuestion:
  question: "M0 验证：跟着 bfs-01 跑下来，能 mvn spring-boot:run 起来并访问 actuator/health 吗？"
  header: "M0 验证"
  options:
    - label: "通过了"
      description: "三个 profile 都能起来，可以进入 M1"
    - label: "有问题"
      description: "需要修改 bfs-01（具体哪里说下）"
```

```json:metadata
{"files": [], "verifyCommand": "AskUserQuestion", "acceptanceCriteria": ["用户确认 bfs-01 可跑通"], "requiresUserVerification": true, "userVerificationPrompt": "M0 验证：跟着 bfs-01 跑下来，能 mvn spring-boot:run 起来吗？"}
```

---

## M1 — 用户体系

### Task 3: 写 bfs-02-jpa-user-entity.md

**Goal:** 写完 User 实体和 Repository 教程

**Files:**
- Create: `youtube_scripts/build-from-scratch/bfs-02-jpa-user-entity.md`

**Acceptance Criteria:**
- [ ] 文件存在且 ≥ 400 行
- [ ] 含完整 `User.java`（@Entity、@Table、@Data、枚举 Role）
- [ ] 含完整 `UserRepository.java`
- [ ] 含 Flyway migration（V1__create_users.sql）
- [ ] 含 `PasswordUtil.java`（BCrypt）
- [ ] 含 1 个 unit test：`UserRepositoryTest`
- [ ] 验证步骤：`mvn test` 跑通

**Verify:**
```bash
test -f youtube_scripts/build-from-scratch/bfs-02-jpa-user-entity.md && \
wc -l youtube_scripts/build-from-scratch/bfs-02-jpa-user-entity.md | awk '{print $1}' | grep -E '^[4-9][0-9]{2}|[0-9]{4,}$'
```

**Steps:** 读 `youtube_scripts/02-first-jpa-entity.md` 取素材 → 按 7 段写 bfs-02 → 完整代码 + Flyway migration + test → Commit

```json:metadata
{"files": ["youtube_scripts/build-from-scratch/bfs-02-jpa-user-entity.md"], "verifyCommand": "wc -l youtube_scripts/build-from-scratch/bfs-02-jpa-user-entity.md", "acceptanceCriteria": ["≥ 400 行", "完整 User entity", "Repository 接口", "Flyway migration", "1 unit test"], "requiresUserVerification": false}
```

### Task 4: 写 bfs-03-jwt-auth.md

**Goal:** 写完 JWT 工具和 Token 缓存

**Files:**
- Create: `youtube_scripts/build-from-scratch/bfs-03-jwt-auth.md`

**Acceptance Criteria:**
- [ ] ≥ 500 行
- [ ] 完整 `JwtUtils.java`（生成/验证/双 token/自动刷新）
- [ ] 完整 `TokenCacheService.java`（Redis）
- [ ] 完整 `application.yml` 片段（jwt.secret-key、Redis 配置）
- [ ] 含 1 个 unit test（`JwtUtilsTest`，用 mock Redis）
- [ ] 验证：`mvn test` 跑通 + 手动用 jwt.io 验签

```json:metadata
{"files": ["youtube_scripts/build-from-scratch/bfs-03-jwt-auth.md"], "verifyCommand": "wc -l youtube_scripts/build-from-scratch/bfs-03-jwt-auth.md", "acceptanceCriteria": ["≥ 500 行", "完整 JwtUtils", "TokenCacheService", "Redis 集成", "1 unit test"], "requiresUserVerification": false}
```

### Task 5: 写 bfs-04-spring-security.md

**Goal:** 写完 Spring Security 配置和登录/注册/登出接口

**Files:**
- Create: `youtube_scripts/build-from-scratch/bfs-04-spring-security.md`

**Acceptance Criteria:**
- [ ] ≥ 600 行
- [ ] 完整 `SecurityConfig.java`（lambda DSL）
- [ ] 完整 `JwtAuthenticationFilter.java`
- [ ] 完整 `CustomUserDetailsService.java`
- [ ] 完整 `UserController.java`（注册/登录/登出 3 个接口）
- [ ] 含 integration test：`@SpringBootTest` + `MockMvc`
- [ ] 验证：curl 注册 → 登录 → 拿 token → 调受保护接口

```json:metadata
{"files": ["youtube_scripts/build-from-scratch/bfs-04-spring-security.md"], "verifyCommand": "wc -l youtube_scripts/build-from-scratch/bfs-04-spring-security.md", "acceptanceCriteria": ["≥ 600 行", "SecurityConfig lambda DSL", "JwtFilter", "UserDetailsService", "登录/注册/登出", "1 integration test"], "requiresUserVerification": false}
```

### Task 6: M1 里程碑验证

**Goal:** 用户验证 M1（注册→登录→访问受保护接口）跑通

**User Verification Required:**
Before marking this task complete, you MUST call AskUserQuestion:
```yaml
AskUserQuestion:
  question: "M1 验证：跟着 bfs-02/03/04 跑下来，能注册、登录、拿 token、调受保护接口吗？"
  header: "M1 验证"
  options:
    - label: "通过了"
      description: "可以进入 M2"
    - label: "有问题"
      description: "需要修改哪些集（说下具体问题）"
```

```json:metadata
{"files": [], "verifyCommand": "AskUserQuestion", "acceptanceCriteria": ["用户确认 M1 全链路通"], "requiresUserVerification": true, "userVerificationPrompt": "M1 验证：注册/登录/token/受保护接口全跑通了吗？"}
```

---

## M2 — 文件存储

### Task 7: 写 bfs-05-minio-upload.md

**Goal:** 写完 MinIO 配置和分片上传接口

**Files:**
- Create: `youtube_scripts/build-from-scratch/bfs-05-minio-upload.md`

**Acceptance Criteria:**
- [ ] ≥ 500 行
- [ ] 完整 `MinioConfig.java`
- [ ] 完整 `FileUpload.java` 实体（含 status 枚举、vectorizationStatus 字符串）
- [ ] 完整 `UploadController.java`（分片上传、合并、状态查询）
- [ ] 含 1 unit test：`FileUploadTest`
- [ ] 验证：curl 模拟分片上传 → MinIO 存 → DB 落库

```json:metadata
{"files": ["youtube_scripts/build-from-scratch/bfs-05-minio-upload.md"], "verifyCommand": "wc -l youtube_scripts/build-from-scratch/bfs-05-minio-upload.md", "acceptanceCriteria": ["≥ 500 行", "MinioConfig", "FileUpload 实体", "UploadController 分片", "1 unit test"], "requiresUserVerification": false}
```

### Task 8: 写 bfs-06-tika-parsing.md

**Goal:** 写完 Tika 解析和父-子块策略

**Files:**
- Create: `youtube_scripts/build-from-scratch/bfs-06-tika-parsing.md`

**Acceptance Criteria:**
- [ ] ≥ 600 行
- [ ] 完整 `ParseService.java`（含流式 StreamingContentHandler）
- [ ] 完整 `DocumentVector.java` 实体
- [ ] 完整 `DocumentVectorRepository.java`
- [ ] 含配置：`application.yml` 的 file.parsing.* 段
- [ ] 1 unit test：`ParseServiceTest`（用小 PDF 测）
- [ ] 验证：上传 PDF → 解析 → 落 DocumentVector 表

```json:metadata
{"files": ["youtube_scripts/build-from-scratch/bfs-06-tika-parsing.md"], "verifyCommand": "wc -l youtube_scripts/build-from-scratch/bfs-06-tika-parsing.md", "acceptanceCriteria": ["≥ 600 行", "ParseService 流式", "父-子块策略", "DocumentVector 实体", "1 unit test"], "requiresUserVerification": false}
```

### Task 9: M2 里程碑验证

**User Verification Required:**
Before marking this task complete, you MUST call AskUserQuestion:
```yaml
AskUserQuestion:
  question: "M2 验证：跟着 bfs-05/06 跑下来，能上传 PDF 并解析出 chunk 落库吗？"
  header: "M2 验证"
  options:
    - label: "通过了"
      description: "可以进入 M3"
    - label: "有问题"
      description: "需要修改哪些集"
```

```json:metadata
{"files": [], "verifyCommand": "AskUserQuestion", "acceptanceCriteria": ["用户确认 M2 跑通"], "requiresUserVerification": true, "userVerificationPrompt": "M2 验证：上传 PDF 解析出 chunk 了吗？"}
```

---

## M3 — 向量化与检索

### Task 10: 写 bfs-07-embedding-client.md

**Goal:** 写完 EmbeddingClient 和配额预留

**Files:**
- Create: `youtube_scripts/build-from-scratch/bfs-07-embedding-client.md`

**Acceptance Criteria:**
- [ ] ≥ 500 行
- [ ] 完整 `EmbeddingClient.java`（WebClient + 批处理 + 配额预留/结算）
- [ ] 完整 `EmbeddingUsageResult` record
- [ ] 1 unit test（用 WireMock mock DashScope）
- [ ] 验证：调一次 embed 真实 API（需 key）→ 拿到向量

```json:metadata
{"files": ["youtube_scripts/build-from-scratch/bfs-07-embedding-client.md"], "verifyCommand": "wc -l youtube_scripts/build-from-scratch/bfs-07-embedding-client.md", "acceptanceCriteria": ["≥ 500 行", "EmbeddingClient 完整", "WebClient 调用", "配额预留", "WireMock test"], "requiresUserVerification": false}
```

### Task 11: 写 bfs-08-elasticsearch-vector.md

**Goal:** 写完 ES 集成和 kNN 召回

**Files:**
- Create: `youtube_scripts/build-from-scratch/bfs-08-elasticsearch-vector.md`

**Acceptance Criteria:**
- [ ] ≥ 500 行
- [ ] 完整 `EsConfig.java`（含 HTTPS 证书可选配置）
- [ ] 完整 `EsIndexInitializer.java`（含 mapping：dense_vector 1024）
- [ ] 完整 `EsDocument.java` 实体
- [ ] 完整 `ElasticsearchService.java`（index + search + kNN）
- [ ] 1 unit test（用 Testcontainers 启 ES 测）
- [ ] 验证：调用 index + 调 kNN search

```json:metadata
{"files": ["youtube_scripts/build-from-scratch/bfs-08-elasticsearch-vector.md"], "verifyCommand": "wc -l youtube_scripts/build-from-scratch/bfs-08-elasticsearch-vector.md", "acceptanceCriteria": ["≥ 500 行", "EsConfig", "EsIndexInitializer", "kNN 召回", "Testcontainers test"], "requiresUserVerification": false}
```

### Task 12: M3 里程碑验证

**User Verification Required:**
```yaml
AskUserQuestion:
  question: "M3 验证：跟着 bfs-07/08 跑下来，能调 Embedding API + 写 ES + kNN 召回吗？"
  header: "M3 验证"
  options:
    - label: "通过了"
      description: "可以进入 M4"
    - label: "有问题"
      description: "需要修改哪些集"
```

```json:metadata
{"files": [], "verifyCommand": "AskUserQuestion", "acceptanceCriteria": ["用户确认 M3 跑通"], "requiresUserVerification": true, "userVerificationPrompt": "M3 验证：向量化 + ES kNN 召回跑通了吗？"}
```

---

## M4 — 异步流水线

### Task 13: 写 bfs-09-kafka-async.md

**Goal:** 写完 Kafka 异步处理

**Files:**
- Create: `youtube_scripts/build-from-scratch/bfs-09-kafka-async.md`

**Acceptance Criteria:**
- [ ] ≥ 500 行
- [ ] 完整 `KafkaConfig.java`（生产者/消费者/错误处理器）
- [ ] 完整 `FileProcessingTask.java`
- [ ] 完整 `FileProcessingConsumer.java`
- [ ] 1 integration test（用 EmbeddedKafka）
- [ ] 验证：上传→立即返回 200→Kafka 异步处理→状态变 COMPLETED

```json:metadata
{"files": ["youtube_scripts/build-from-scratch/bfs-09-kafka-async.md"], "verifyCommand": "wc -l youtube_scripts/build-from-scratch/bfs-09-kafka-async.md", "acceptanceCriteria": ["≥ 500 行", "KafkaConfig", "FileProcessingConsumer", "状态机", "EmbeddedKafka test"], "requiresUserVerification": false}
```

### Task 14: M4 里程碑验证

**User Verification Required:**
```yaml
AskUserQuestion:
  question: "M4 验证：跟着 bfs-09 跑下来，上传文件后能异步处理完吗？"
  header: "M4 验证"
  options:
    - label: "通过了"
      description: "可以进入 M5"
    - label: "有问题"
      description: "需要修改 bfs-09"
```

```json:metadata
{"files": [], "verifyCommand": "AskUserQuestion", "acceptanceCriteria": ["用户确认 M4 跑通"], "requiresUserVerification": true, "userVerificationPrompt": "M4 验证：上传文件异步处理完状态变 COMPLETED 了吗？"}
```

---

## M5 — 对话能力基础

### Task 15: 写 bfs-10-websocket-deepseek.md

**Goal:** 写完 WebSocket 聊天（不接 RAG，纯 LLM）

**Files:**
- Create: `youtube_scripts/build-from-scratch/bfs-10-websocket-deepseek.md`

**Acceptance Criteria:**
- [ ] ≥ 600 行
- [ ] 完整 `WebSocketConfig.java`
- [ ] 完整 `ChatWebSocketHandler.java`（握手鉴权 + 心跳）
- [ ] 完整 `DeepSeekClient.java`（流式 WebClient）
- [ ] 完整 `ChatHandler.java`（最小版：直接调 DeepSeek，不接 RAG）
- [ ] 1 integration test（WebSocket 测试）
- [ ] 验证：浏览器开 WebSocket → 提问 → 流式回答

```json:metadata
{"files": ["youtube_scripts/build-from-scratch/bfs-10-websocket-deepseek.md"], "verifyCommand": "wc -l youtube_scripts/build-from-scratch/bfs-10-websocket-deepseek.md", "acceptanceCriteria": ["≥ 600 行", "WebSocketConfig", "ChatWebSocketHandler", "DeepSeekClient 流式", "WebSocket integration test"], "requiresUserVerification": false}
```

### Task 16: M5 里程碑验证

**User Verification Required:**
```yaml
AskUserQuestion:
  question: "M5 验证：跟着 bfs-10 跑下来，WebSocket 能流式聊 DeepSeek 吗？"
  header: "M5 验证"
  options:
    - label: "通过了"
      description: "可以进入 M6"
    - label: "有问题"
      description: "需要修改 bfs-10"
```

```json:metadata
{"files": [], "verifyCommand": "AskUserQuestion", "acceptanceCriteria": ["用户确认 M5 跑通"], "requiresUserVerification": true, "userVerificationPrompt": "M5 验证：WebSocket 流式聊天跑通了吗？"}
```

---

## M6 — 多租户

### Task 17: 写 bfs-11-multi-tenant-orgtag.md

**Goal:** 写完 OrgTag 体系

**Files:**
- Create: `youtube_scripts/build-from-scratch/bfs-11-multi-tenant-orgtag.md`

**Acceptance Criteria:**
- [ ] ≥ 500 行
- [ ] 完整 `OrganizationTag.java` 实体
- [ ] 完整 `OrgTagAuthorizationFilter.java`
- [ ] 完整 `OrgTagCacheService.java`（Caffeine 本地缓存）
- [ ] 1 unit test
- [ ] 验证：A 用户上传 → B 用户访问 404

```json:metadata
{"files": ["youtube_scripts/build-from-scratch/bfs-11-multi-tenant-orgtag.md"], "verifyCommand": "wc -l youtube_scripts/build-from-scratch/bfs-11-multi-tenant-orgtag.md", "acceptanceCriteria": ["≥ 500 行", "OrganizationTag", "OrgTagAuthorizationFilter", "缓存", "1 unit test"], "requiresUserVerification": false}
```

### Task 18: M6 里程碑验证

**User Verification Required:**
```yaml
AskUserQuestion:
  question: "M6 验证：A 上传的文件 B 访问不到吗？"
  header: "M6 验证"
  options:
    - label: "通过了"
      description: "可以进入 M7"
    - label: "有问题"
      description: "需要修改 bfs-11"
```

```json:metadata
{"files": [], "verifyCommand": "AskUserQuestion", "acceptanceCriteria": ["用户确认 M6 多租户隔离生效"], "requiresUserVerification": true, "userVerificationPrompt": "M6 验证：多租户隔离生效了吗？"}
```

---

## M7 — 混合检索

### Task 19: 写 bfs-12-hybrid-search-rerank.md

**Goal:** 写完 BM25 + 向量 + 重排序

**Files:**
- Create: `youtube_scripts/build-from-scratch/bfs-12-hybrid-search-rerank.md`

**Acceptance Criteria:**
- [ ] ≥ 500 行
- [ ] 完整 `HybridSearchService.java`（knn + match + rescore + postFilter）
- [ ] 1 unit test（Testcontainers ES）
- [ ] 验证：搜「派聪明」能召回「pai-smart」

```json:metadata
{"files": ["youtube_scripts/build-from-scratch/bfs-12-hybrid-search-rerank.md"], "verifyCommand": "wc -l youtube_scripts/build-from-scratch/bfs-12-hybrid-search-rerank.md", "acceptanceCriteria": ["≥ 500 行", "HybridSearchService", "BM25 + kNN + rescore", "postFilter 权限", "1 integration test"], "requiresUserVerification": false}
```

### Task 20: M7 里程碑验证

**User Verification Required:**
```yaml
AskUserQuestion:
  question: "M7 验证：混合检索能召回专有名词 + 同义词吗？"
  header: "M7 验证"
  options:
    - label: "通过了"
      description: "可以进入 M8"
    - label: "有问题"
      description: "需要修改 bfs-12"
```

```json:metadata
{"files": [], "verifyCommand": "AskUserQuestion", "acceptanceCriteria": ["用户确认 M7 混合检索生效"], "requiresUserVerification": true, "userVerificationPrompt": "M7 验证：混合检索召回效果满意吗？"}
```

---

## M8 — 限流配额

### Task 21: 写 bfs-13-rate-limit-quota.md

**Goal:** 写完 Redis 限流和配额

**Files:**
- Create: `youtube_scripts/build-from-scratch/bfs-13-rate-limit-quota.md`

**Acceptance Criteria:**
- [ ] ≥ 500 行
- [ ] 完整 `RateLimitService.java`（固定窗口 + 全局预算）
- [ ] 完整 `UsageQuotaService.java`（预留/结算）
- [ ] 完整 `RateLimitProperties.java`（@ConfigurationProperties）
- [ ] 1 unit test（用 Testcontainers Redis 或 mock）
- [ ] 验证：脚本小子 1 秒刷 100 次 → 第 11 次 429

```json:metadata
{"files": ["youtube_scripts/build-from-scratch/bfs-13-rate-limit-quota.md"], "verifyCommand": "wc -l youtube_scripts/build-from-scratch/bfs-13-rate-limit-quota.md", "acceptanceCriteria": ["≥ 500 行", "RateLimitService", "UsageQuotaService", "配置抽象", "1 test"], "requiresUserVerification": false}
```

### Task 22: M8 里程碑验证

**User Verification Required:**
```yaml
AskUserQuestion:
  question: "M8 验证：刷接口能触发 429 限流吗？"
  header: "M8 验证"
  options:
    - label: "通过了"
      description: "可以进入 M9"
    - label: "有问题"
      description: "需要修改 bfs-13"
```

```json:metadata
{"files": [], "verifyCommand": "AskUserQuestion", "acceptanceCriteria": ["用户确认 M8 限流生效"], "requiresUserVerification": true, "userVerificationPrompt": "M8 验证：限流 429 触发了吗？"}
```

---

## M9 — 对话历史

### Task 23: 写 bfs-14-conversation-session.md

**Goal:** 写完会话和消息持久化

**Files:**
- Create: `youtube_scripts/build-from-scratch/bfs-14-conversation-session.md`

**Acceptance Criteria:**
- [ ] ≥ 500 行
- [ ] 完整 `Conversation.java` 实体
- [ ] 完整 `ConversationSession.java` 实体
- [ ] 完整 `ConversationService.java`（ensureConversationSession、getRecentHistory）
- [ ] 完整 `ConversationController.java`（sessions 列表 + messages 列表）
- [ ] 1 integration test
- [ ] 验证：问 A→问 B→回到 A 仍能续传

```json:metadata
{"files": ["youtube_scripts/build-from-scratch/bfs-14-conversation-session.md"], "verifyCommand": "wc -l youtube_scripts/build-from-scratch/bfs-14-conversation-session.md", "acceptanceCriteria": ["≥ 500 行", "Conversation + Session 实体", "历史滑动窗口", "REST API", "1 integration test"], "requiresUserVerification": false}
```

### Task 24: M9 里程碑验证

**User Verification Required:**
```yaml
AskUserQuestion:
  question: "M9 验证：对话历史能持久化和续传吗？"
  header: "M9 验证"
  options:
    - label: "通过了"
      description: "可以进入 M10"
    - label: "有问题"
      description: "需要修改 bfs-14"
```

```json:metadata
{"files": [], "verifyCommand": "AskUserQuestion", "acceptanceCriteria": ["用户确认 M9 历史持久化生效"], "requiresUserVerification": true, "userVerificationPrompt": "M9 验证：对话历史持久化和续传生效了吗？"}
```

---

## M10 — Agent 进阶

### Task 25: 写 bfs-15-react-agent.md

**Goal:** 写完 ReAct Agent 和工具调用

**Files:**
- Create: `youtube_scripts/build-from-scratch/bfs-15-react-agent.md`

**Acceptance Criteria:**
- [ ] ≥ 600 行
- [ ] 完整 `AgentTool.java` record
- [ ] 完整 `AgentToolRegistry.java`（4 个工具：search_knowledge、generate_summary、submit_feedback、knowledge_stats）
- [ ] 完整 `ChatHandler.runReActLoop`（含 MAX_REACT_ROUNDS 限制）
- [ ] 1 unit test（mock LLM 验证循环）
- [ ] 验证：问"派聪明和 LangChain 哪个好"→ AI 自动调 search_knowledge

```json:metadata
{"files": ["youtube_scripts/build-from-scratch/bfs-15-react-agent.md"], "verifyCommand": "wc -l youtube_scripts/build-from-scratch/bfs-15-react-agent.md", "acceptanceCriteria": ["≥ 600 行", "AgentToolRegistry", "4 个工具", "ReAct 循环", "1 test"], "requiresUserVerification": false}
```

### Task 26: M10 里程碑验证

**User Verification Required:**
```yaml
AskUserQuestion:
  question: "M10 验证：ReAct Agent 能自动调工具吗？"
  header: "M10 验证"
  options:
    - label: "通过了"
      description: "可以进入 M11"
    - label: "有问题"
      description: "需要修改 bfs-15"
```

```json:metadata
{"files": [], "verifyCommand": "AskUserQuestion", "acceptanceCriteria": ["用户确认 M10 ReAct 生效"], "requiresUserVerification": true, "userVerificationPrompt": "M10 验证：Agent 自动调工具了吗？"}
```

---

## M11 — 多 LLM 路由

### Task 27: 写 bfs-16-llm-provider-router.md

**Goal:** 写完多 LLM 路由

**Files:**
- Create: `youtube_scripts/build-from-scratch/bfs-16-llm-provider-router.md`

**Acceptance Criteria:**
- [ ] ≥ 500 行
- [ ] 完整 `ModelProviderConfig.java` 实体
- [ ] 完整 `ModelProviderConfigService.java`
- [ ] 完整 `LlmProviderRouter.java`（多 provider 动态切换）
- [ ] 含 Flyway migration（V16__create_model_provider_configs.sql）
- [ ] 1 unit test
- [ ] 验证：DB 改 isActive → 切换 provider 生效

```json:metadata
{"files": ["youtube_scripts/build-from-scratch/bfs-16-llm-provider-router.md"], "verifyCommand": "wc -l youtube_scripts/build-from-scratch/bfs-16-llm-provider-router.md", "acceptanceCriteria": ["≥ 500 行", "ModelProviderConfig", "多 provider 路由", "DB 切换", "1 test"], "requiresUserVerification": false}
```

### Task 28: M11 里程碑验证

**User Verification Required:**
```yaml
AskUserQuestion:
  question: "M11 验证：DB 改 isActive 能切换 LLM provider 吗？"
  header: "M11 验证"
  options:
    - label: "通过了"
      description: "可以进入 M12"
    - label: "有问题"
      description: "需要修改 bfs-16"
```

```json:metadata
{"files": [], "verifyCommand": "AskUserQuestion", "acceptanceCriteria": ["用户确认 M11 路由切换生效"], "requiresUserVerification": true, "userVerificationPrompt": "M11 验证：多 LLM 路由切换生效了吗？"}
```

---

## M12 — 充值

### Task 29: 写 bfs-17-recharge-payment.md

**Goal:** 写完充值订单 + 微信支付（mock 回调）

**Files:**
- Create: `youtube_scripts/build-from-scratch/bfs-17-recharge-payment.md`

**Acceptance Criteria:**
- [ ] ≥ 700 行
- [ ] 完整 `UserTokenRecord.java`（@Version 乐观锁）
- [ ] 完整 `RechargeOrder.java` 实体
- [ ] 完整 `RechargePackage.java` 实体
- [ ] 完整 `RechargeService.java`（createOrder、handlePaidOrder）
- [ ] 完整 `WxPayService.java`（V3 协议 + mock 模式）
- [ ] 完整 `RechargeController.java`
- [ ] 1 unit test（mock 微信回调）
- [ ] 验证：创建订单 → mock 回调 → token 余额增加

```json:metadata
{"files": ["youtube_scripts/build-from-scratch/bfs-17-recharge-payment.md"], "verifyCommand": "wc -l youtube_scripts/build-from-scratch/bfs-17-recharge-payment.md", "acceptanceCriteria": ["≥ 700 行", "乐观锁", "RechargeOrder", "WxPayService V3", "mock 回调", "1 test"], "requiresUserVerification": false}
```

### Task 30: M12 里程碑验证

**User Verification Required:**
```yaml
AskUserQuestion:
  question: "M12 验证：mock 微信回调能完成充值吗？"
  header: "M12 验证"
  options:
    - label: "通过了"
      description: "可以进入 M13"
    - label: "有问题"
      description: "需要修改 bfs-17"
```

```json:metadata
{"files": [], "verifyCommand": "AskUserQuestion", "acceptanceCriteria": ["用户确认 M12 充值流程通"], "requiresUserVerification": true, "userVerificationPrompt": "M12 验证：mock 微信回调充值完成了吗？"}
```

---

## M13 — 邀请码

### Task 31: 写 bfs-18-invite-code.md

**Goal:** 写完邀请码

**Files:**
- Create: `youtube_scripts/build-from-scratch/bfs-18-invite-code.md`

**Acceptance Criteria:**
- [ ] ≥ 500 行
- [ ] 完整 `InviteCode.java` 实体
- [ ] 完整 `InviteCodeService.java`（create、bind）
- [ ] 完整 `InviteCodeController.java`
- [ ] 注册接口集成邀请码
- [ ] 1 unit test
- [ ] 验证：A 创建码 → B 注册时填 → A 和 B 都加 token

```json:metadata
{"files": ["youtube_scripts/build-from-scratch/bfs-18-invite-code.md"], "verifyCommand": "wc -l youtube_scripts/build-from-scratch/bfs-18-invite-code.md", "acceptanceCriteria": ["≥ 500 行", "InviteCode 实体", "绑定关系", "注册集成", "1 test"], "requiresUserVerification": false}
```

### Task 32: M13 里程碑验证

**User Verification Required:**
```yaml
AskUserQuestion:
  question: "M13 验证：邀请码双发 token 生效吗？"
  header: "M13 验证"
  options:
    - label: "通过了"
      description: "可以进入 M14"
    - label: "有问题"
      description: "需要修改 bfs-18"
```

```json:metadata
{"files": [], "verifyCommand": "AskUserQuestion", "acceptanceCriteria": ["用户确认 M13 邀请码双发生效"], "requiresUserVerification": true, "userVerificationPrompt": "M13 验证：邀请码双发 token 了吗？"}
```

---

## M14 — 反馈

### Task 33: 写 bfs-19-message-feedback.md

**Goal:** 写完消息反馈

**Files:**
- Create: `youtube_scripts/build-from-scratch/bfs-19-message-feedback.md`

**Acceptance Criteria:**
- [ ] ≥ 500 行
- [ ] 完整 `MessageFeedback.java` 实体
- [ ] 完整 `FeedbackService.java`（含 buildRecentFeedbackGuidance）
- [ ] 完整 `FeedbackController.java`
- [ ] 1 unit test
- [ ] 验证：👎 某回答 → 下次同主题 AI 主动避开

```json:metadata
{"files": ["youtube_scripts/build-from-scratch/bfs-19-message-feedback.md"], "verifyCommand": "wc -l youtube_scripts/build-from-scratch/bfs-19-message-feedback.md", "acceptanceCriteria": ["≥ 500 行", "MessageFeedback 实体", "buildRecentFeedbackGuidance", "REST API", "1 test"], "requiresUserVerification": false}
```

### Task 34: M14 里程碑验证

**User Verification Required:**
```yaml
AskUserQuestion:
  question: "M14 验证：负反馈影响后续 prompt 吗？"
  header: "M14 验证"
  options:
    - label: "通过了"
      description: "可以进入 M15"
    - label: "有问题"
      description: "需要修改 bfs-19"
```

```json:metadata
{"files": [], "verifyCommand": "AskUserQuestion", "acceptanceCriteria": ["用户确认 M14 反馈生效"], "requiresUserVerification": true, "userVerificationPrompt": "M14 验证：负反馈影响后续 prompt 了吗？"}
```

---

## M15 — 可观测性

### Task 35: 写 bfs-20-observability-production.md

**Goal:** 写完日志/性能/启动校验

**Files:**
- Create: `youtube_scripts/build-from-scratch/bfs-20-observability-production.md`

**Acceptance Criteria:**
- [ ] ≥ 500 行
- [ ] 完整 `LogUtils.java`（统一日志格式）
- [ ] 完整 `AsyncExecutorConfig.java`（chat-monitor 专用线程池）
- [ ] 完整 `ProductionConfigValidator.java`
- [ ] 完整 `DotenvEnvironmentPostProcessor.java`
- [ ] 1 unit test
- [ ] 验证：dev 能起 → prod 少配就 fail

```json:metadata
{"files": ["youtube_scripts/build-from-scratch/bfs-20-observability-production.md"], "verifyCommand": "wc -l youtube_scripts/build-from-scratch/bfs-20-observability-production.md", "acceptanceCriteria": ["≥ 500 行", "LogUtils", "AsyncExecutor", "prod 校验", "1 test"], "requiresUserVerification": false}
```

### Task 36: M15 里程碑验证 + README 完成

**Goal:** 用户最终验证整个教程跑通 + 收尾

**Files:**
- Modify: `youtube_scripts/build-from-scratch/README.md`（完成所有里程碑勾选 + 添加"完成时间"和"已知坑"段）

**Acceptance Criteria:**
- [ ] 用户确认所有 15 个里程碑全部跑通
- [ ] README.md 完整

**User Verification Required:**
```yaml
AskUserQuestion:
  question: "M15 验证：20 集全部跑通了吗？整教程完结？"
  header: "教程完结验证"
  options:
    - label: "全部跑通"
      description: "教程完结，README 收尾"
    - label: "还有问题"
      description: "具体哪集还需要修"
```

```json:metadata
{"files": ["youtube_scripts/build-from-scratch/README.md"], "verifyCommand": "AskUserQuestion", "acceptanceCriteria": ["用户确认全教程完结"], "requiresUserVerification": true, "userVerificationPrompt": "教程完结验证：20 集全跑通了吗？"}
```

---

## Self-Review

### 1. Spec coverage
所有 20 个 bfs 集都有任务覆盖。✓

### 2. Placeholder scan
- 无 "TBD"、"TODO"、"implement later" 占位。✓
- 所有 acceptance criteria 都是具体可验证的。✓

### 3. Type consistency
- 集号 bfs-01 ~ bfs-20 在 spec 和 plan 一致。✓
- 里程碑编号 M0 ~ M15 在 README 和任务一致。✓

### 4. Verification requirement scan
**原 prompt/spec 要求**：按里程碑分批交付，每个里程碑完成后用户验证 → YES

**计划中已包含**：15 个 `requiresUserVerification: true` 任务（M0 ~ M15 各自一个）。✓ HARD-GATE 通过。

---

## Execution Handoff

Plan 已写到 `docs/superpowers/plans/2026-06-01-build-paiai-from-scratch.md`，任务文件会写到 `docs/superpowers/plans/2026-06-01-build-paiai-from-scratch.md.tasks.json`。

按 brainstorming 设计文档和 writing-plans 流程，下一步：调用 AskUserQuestion 让用户选择执行方式。
