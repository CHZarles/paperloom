---
title: 论文权限与索引生命周期如何收敛为四个事实
description: 上传统一进入个人空间，管理员单独发布全局论文；处理中论文不可检索，Qdrant 每篇只保留一份可重建索引。
date: 2026-07-17
category: 架构
stage: implementation
status: 已实现并完成聚焦验证
result: 四个事实
outline: [2, 3]
topics: [论文权限, Qdrant, Reading Model, 索引重建, 架构]
background: >-
  Java/Qdrant 检索链路已经接通，但上传仍携带组织标签和公开开关，索引重建还保留多 Generation 在线切换。产品处在开发阶段，没有团队论文空间和单篇无停机重建需求。
problem: >-
  权限、处理状态和索引维护混在同一组字段与接口中。普通用户可以影响公开范围，READY 论文可以重新解析，Qdrant 重建还要处理旧索引服务、条件激活和失败清理。
approach: >-
  把共享论文内容、个人空间记录、管理员发布记录和可检索状态拆成四个事实；上传统一私有；发布和 Qdrant 重建只开放给管理员；索引改为删除后重建并用 MySQL 原子抢占任务。
outcome: >-
  组织标签和公开开关退出论文授权，index_generation 及在线切换逻辑被删除。普通用户只管理个人空间，管理员维护全局发布和 Qdrant；服务端 PDF 哈希校验仍是对外开放前的安全缺口。
---

# 论文权限与索引生命周期如何收敛为四个事实

<PracticeArticleOverview />

## 1. 背景、冲突、问题与结论

**背景。** Qdrant 检索刚接通时，上传接口仍接收 `orgTag` 和 `isPublic`。访问规则同时依赖拥有者、公开
状态和组织关系。索引重建则通过多个 Generation 维持在线查询。

**冲突。** 旧设计同时预留团队共享、用户公开、READY 重新解析、单篇无停机重建、并发激活和失败回滚。
产品仍在开发阶段，论文通常只上传一次，也没有团队空间和在线重建要求。

**问题。** 如何让普通用户只管理自己的论文，让管理员单独控制全局内容，并让处理状态与 Qdrant
维护遵循一套可解释的规则？

**结论。** 系统只保留四个事实：一份按 `paper_id` 共享的底层论文数据；用户个人空间记录；管理员
全局发布记录；由 Current Reading Model 与 Qdrant 状态共同决定的可检索状态。

组织标签和公开开关不再参与权限。Qdrant 每篇只保留一份索引，重建期间暂停检索，失败后保持不可检索。

前一篇文章先完成了索引维护决策：
[`Qdrant 检索切换后的上传与重建规则`](./qdrant-corpus-plane-cutover.md)。本文记录该决策如何进入权限、
上传和删除流程。

## 2. 先固定四个事实

```text
paper_id
-> 一份共享 PDF、Parser 结果、Current Reading Model 和 Qdrant 数据

file_upload(user_id, paper_id)
-> 论文出现在某个用户的个人空间

paper_publications(paper_id)
-> 论文由管理员发布到全局论文库

Current Reading Model + Qdrant 状态
-> 论文当前能否参加检索
```

四个事实分别回答内容、个人拥有关系、全局发布和运行状态。访问规则不再从 Qdrant Payload、组织标签
或 Parser 状态推断。

## 3. 旧设计为什么会继续增加状态组合

旧上传记录同时保存用户、组织和公开状态。Qdrant 重建还维护当前 Generation、新 Generation、条件
激活、旧数据清理和失败回滚。READY 论文又允许普通用户重新运行 Parser。

**论点。** 权限、处理状态和派生索引需要分别建模。

**论据。** 上传参数、组织关系、公开状态、Reading Model 状态、Generation 和任务状态都可能改变
论文是否可见或可搜。

**论证。** 一个字段同时承担多个含义时，接口和测试必须覆盖组合。把四类事实拆开后，每条规则只依赖
对应的数据来源，权限变化也不需要重建 Qdrant。

## 4. 决策一：上传统一进入个人空间

**论点。** 普通上传只创建用户自己的 `file_upload` 记录，不接受组织共享或公开参数。

**论据。** 前端和后端原来都传递 `orgTag`、`isPublic`。当前产品没有团队论文空间，普通用户也不需要
在上传时决定全局发布。

**论证。** 上传负责证明用户拥有一份论文，不应该同时决定其他用户能否访问。权限选项退出上传后，
普通流程只剩上传、查看状态、失败重试和删除个人记录。

组织上传额度仍可按用户主组织读取，它只限制文件大小，不参与可见性判断。旧字段暂时保留用于表结构
兼容和一次性回填，运行时授权不再读取它们。

**决定。** 新上传固定进入个人空间，处理任务不再携带权限字段。

## 5. 决策二：全局发布由管理员单独执行

**论点。** 全局可见性使用 `paper_publications` 记录，只能由管理员增加或删除。

**论据。** 管理员发布前必须满足三项条件：调用者是管理员；管理员个人空间中存在该论文；论文当前
可检索。

**论证。** 发布记录只改变访问范围，不修改 PDF、Reading Model 或 Qdrant。管理员必须先拥有自己的
论文记录，可以避免从产品列表中直接发布另一个用户的私有论文。

两个用户上传同一份 PDF 时，可以各自拥有一条个人空间记录。底层 PDF、Parser 结果、Reading Model
和 Qdrant Point 继续按同一个 `paper_id` 复用。

**决定。** 管理员上传也先进入个人空间。发布动作只写入 `paper_publications`，不重新解析或重建索引。

## 6. 决策三：访问规则只看个人记录或发布记录

访问判断收敛为：

```text
用户拥有 file_upload(user_id, paper_id)
OR
paper_publications 中存在 paper_id
```

**论点。** Qdrant 不保存用户、组织或公开权限。

**论据。** Qdrant Point 是按论文共享的派生候选，同一篇论文不会为每个用户复制一份索引。

**论证。** Java 可以先根据个人记录和发布记录得到允许访问的 `paper_id`，再把该集合交给 Qdrant。
把权限放入索引会迫使每次权限变化触发 Point 更新，并增加旧权限残留风险。

**决定。** `PaperAccessService` 统一处理访问判断。论文列表、对话 Scope、候选搜索和准确读取使用同一
授权结果。

## 7. 决策四：可见与可检索分开判断

个人空间可以显示处理中或失败的论文，检索只能接收已经完成的论文。

`PaperSearchabilityService` 当前要求：

```text
Current Reading Model 存在
AND model_status = READING_MODEL_READY
AND retrieval_index_status = READY
AND retrieval_index_contract 非空
AND retrieval_indexed_location_count > 0
```

**论点。** 上传完成、Parser Artifact 存在或前端显示“已处理”都不能单独证明论文可检索。

**论据。** Agent 的候选来自 Qdrant，准确内容来自当前 Reading Model。任一侧缺失都会让搜索结果无法
读取或引用。

**论证。** 一条统一规则可以防止论文列表、对话 Scope、Location 检索和管理员发布使用不同的 READY
定义。

**决定。** 重建开始后状态进入 `REBUILDING`，论文立即退出检索；成功后回到 `READY`；失败后进入
`FAILED` 并保留错误信息。

## 8. 决策五：Qdrant 每篇只保留一份数据

单篇流程为：

```text
MySQL 原子抢占 paper_id
-> 标记 REBUILDING
-> 删除该论文原有 Qdrant Point
-> 从 Current Reading Model 重新生成 Point
-> 核对 Qdrant 写入数量
-> 使用 job_id 完成状态更新
-> 标记 READY
```

**论点。** 当前业务不保留多个 `index_generation`，也不在重建期间继续搜索受影响论文。

**论据。** 论文重建属于管理员维护。暂停检索后，旧索引不再承担用户请求，并发任务可以在入口阻止。

**论证。** 多 Generation 只为在线切换提供价值，却会引入成对过滤、条件激活和旧数据清理。单份索引
配合任务互斥可以覆盖当前需求。

`job_id` 充当完成写入的围栏。SQL 会同时检查运行状态与 `job_id`，旧任务晚到时无法覆盖新任务结果。
全量重建使用固定控制记录，一次只运行一个，再按 `paper_id` 顺序执行单篇流程。

**决定。** 删除 `index_generation`、多版本 Filter 和在线切换。失败时删除残留 Point，保留 MySQL
Reading Model，等待管理员重试；产品不切回其他 Retriever。

## 9. 决策六：删除个人记录时按共享引用处理

**论点。** 用户删除论文时先删除自己的个人空间记录，底层数据只在没有任何引用时清理。

**论据。** 同一个 `paper_id` 可能被多个用户上传，也可能存在全局发布记录。直接删除底层 PDF 和索引
会破坏其他用户或全局论文。

**论证。** `file_upload` 表示个人拥有关系，`paper_publications` 表示全局访问。两类引用都消失后，
底层 PDF、Parser 结果、Reading Model 和 Qdrant 才没有继续保留的调用者。

全局论文的最后一条个人记录不能直接删除。管理员需要先取消发布。

## 10. 重复上传如何复用底层论文

底层论文已经可检索时，新用户上传同一份 PDF，只需要新增个人空间记录。旧合并流程仍可能尝试重新
组合分片，但第一次处理后临时分片已经被清理。

**处理。** 合并前先检查共享论文是否已可检索。已有底层数据时，新个人记录直接进入完成状态，不再
调用 `mergeChunks`，也不再向 Kafka 发送 Parser 和索引任务。

对应 Controller 测试验证了共享 PDF、Reading Model 与 Qdrant 可以复用，同时保留两个用户各自的
个人空间记录。

## 11. 实现中暴露的三个问题

### 11.1 `reindex` 同时表示三种动作

旧路由可能表示首次处理失败重试、重新解析 PDF，或只重建 Qdrant。三个动作修改的数据和权限不同。

普通用户现在只保留 `/papers/{paperId}/processing/retry`，并且只允许首次处理状态为 `FAILED` 时调用。
管理员的单篇与全量 Qdrant 维护入口统一放在 `/admin/retrieval`，不会重新运行 Parser。

### 11.2 客户端 `paper_id` 仍是安全边界

浏览器计算 PDF 的 MD5 并提交 `paper_id`。当前上传链路没有在合并后重新计算整份 PDF 哈希。

知道私有论文 `paper_id` 的调用方可能尝试创建个人空间记录。对外开放前，服务端需要核对合并文件哈希，
或增加能够证明调用方持有该 PDF 的上传协议。

### 11.3 初次前端生产构建没有完成

后端 `clean test-compile` 和 17 个聚焦测试类通过；前端 `typecheck`、目标 ESLint 和权限契约测试通过。
当时两次 `pnpm build` 都在 Vite 打包约 240 秒后超时，日志没有出现编译错误。

文章保留这个初始验证结果，不把超时写成通过。完整生产构建仍需在包含当前权限改动的工作树上补跑。

## 12. 工程结果

普通用户当前只拥有个人空间上传、状态查看、失败重试、删除个人记录和检索可用论文。全局发布、取消
发布、单篇 Qdrant 重建和全量重建属于管理员维护。

权限只读取个人空间与 `paper_publications`。索引生命周期使用 `PENDING / BUILDING / READY /
REBUILDING / FAILED`，任务通过 MySQL 原子抢占和 `job_id` 防止旧任务覆盖。

多 Generation Point Payload、查询 Filter、条件激活和清理逻辑已经删除。2026 年 7 月 18 日的词法
Qdrant 切换又删除了 Embedding、Dense 和 RRF，并把通用 `retrieval_index_contract` 作为当前索引合同；
权限与单份索引规则保持不变。

本轮仍有两项明确限制：团队论文空间没有实现；服务端 PDF 哈希校验尚未补齐。前一项等待真实需求，
后一项属于公开部署前的安全工作。

## 13. 证据边界

**能够支持的结论。** 个人空间、管理员发布、统一可检索状态和单份 Qdrant 生命周期已经进入当前代码，
聚焦后端测试、前端类型检查和权限契约测试通过。

**尚不能支持的结论。** 当前证据没有覆盖团队共享、恶意伪造 `paper_id`，也没有完成包含本轮权限改动的
前端生产 Bundle 验证。

## 14. 复现资料

主要实现：

- [`PaperAccessService`](https://github.com/CHZarles/paperloom/blob/main/src/main/java/io/github/chzarles/paperloom/service/PaperAccessService.java)
- [`PaperPublicationService`](https://github.com/CHZarles/paperloom/blob/main/src/main/java/io/github/chzarles/paperloom/service/PaperPublicationService.java)
- [`PaperSearchabilityService`](https://github.com/CHZarles/paperloom/blob/main/src/main/java/io/github/chzarles/paperloom/service/PaperSearchabilityService.java)
- [`ReadingModelQdrantIndexService`](https://github.com/CHZarles/paperloom/blob/main/src/main/java/io/github/chzarles/paperloom/service/ReadingModelQdrantIndexService.java)
- [`QdrantReadingModelReindexService`](https://github.com/CHZarles/paperloom/blob/main/src/main/java/io/github/chzarles/paperloom/service/QdrantReadingModelReindexService.java)
- [`论文权限与检索索引方案（通俗版）`](https://github.com/CHZarles/paperloom/blob/main/docs/engineering-evolution/architecture/paper-access-and-retrieval-lifecycle-simplified-proposal-2026-07-16.zh-CN.md)

```bash
mvn -q clean test-compile
mvn -q -Dtest=PaperAccessServiceTest,PaperPublicationServiceTest,PaperUploadControllerTest,\
BootstrapPaperInitializerTest,PaperProcessingConsumerTest,PaperControllerContractTest,\
PaperReadingModelRepositoryTest,ConversationScopeServiceTest,CorpusRetrievalServiceTest,\
PaperCollectionServiceTest,PaperSearchabilityServiceTest,PaperServiceCandidateSearchTest,\
PaperServiceTest,QdrantClientTest,QdrantReadingModelReindexServiceTest,\
ReadingModelQdrantIndexServiceTest,UploadServiceTest test

cd frontend
pnpm typecheck
pnpm exec eslint src/store/modules/knowledge-base/index.ts \
  src/views/knowledge-base/index.vue \
  src/views/knowledge-base/modules/paper-mobile-list.vue \
  src/views/knowledge-base/modules/upload-dialog.vue \
  tests/paper-access-and-publication-contract.test.ts
pnpm exec tsx tests/paper-access-and-publication-contract.test.ts
pnpm build
```
