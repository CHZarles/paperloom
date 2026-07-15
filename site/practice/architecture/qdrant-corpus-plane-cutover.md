---
title: 把向量检索放到 Java 数据面后，我又修了四个并发和召回问题
description: 退役 Elasticsearch 时保持 Python Harness 工具合同不变，并用 Qdrant Generation、MySQL CAS 和精确读取处理扩展与一致性。
date: 2026-07-15
category: 架构
stage: implementation
status: 核心切换已验证
result: 90 Python + 855 Java tests
topics: [Qdrant, Elasticsearch, Harness, 检索, 横向扩展]
background: >-
  产品库有 76 篇 Current Reading Model、25,625 个 Reading Element；旧 Elasticsearch 占用约 1.61 GB RSS，但 Python Harness 实际仍在每轮加载授权正文并在内存检索。
problem: >-
  需要让论文扩大到数千篇时不再重复加载全部正文，同时不能改变 MiniMax 看到的工具、Prompt、Golden Data、Anchor、Reading Model 或引用语义。
approach: >-
  把 Qdrant、Embedding、权限和索引生命周期放到 Java Corpus 数据面，Python 只保留编排与证据状态；先写合同和失败测试，再删除旧 Elasticsearch 路径，不保留兼容回退。
outcome: >-
  Python 90 个测试、稳定与扩展 Fixture 15/15 和 30/30、Java 855 个测试及认证 Real-Qdrant Smoke 全部通过；旧 ES 运行代码已删除，76 篇存量回填和产品端到端 Smoke 仍待执行。
---

# 把向量检索放到 Java 数据面后，我又修了四个并发和召回问题

<PracticeArticleOverview />

## 背景

PaperLoom 的 Python Harness 负责让 MiniMax 选择论文、搜索位置、读取原文并提交带引用的答案。Java
负责论文上传、权限、Reading Model、Embedding 配额和长期会话。Reading Model 是解析后的论文结构，
其中 `location_ref` 是正文、表格、图片或页面的稳定位置标识。

改造前，本地产品数据是：

| 数据 | 数量 |
| --- | ---: |
| Current READY 论文 | 76 |
| Current Reading Element | 25,625 |
| 平均每篇 Element | 337.2 |
| Elasticsearch `paper_search` | 76 |
| Elasticsearch `paper_chunks` | 23,707 |
| Elasticsearch 索引空间 | 464.6 MB |
| Elasticsearch 容器 RSS | 约 1.61 GB |

旧 Elasticsearch 索引来自 `paper_text_chunks`，没有 Current Reading Model 的稳定
`location_ref`。更关键的是，产品 Harness 没有使用这套索引：每个研究回合仍会读取授权范围内的全部
Reading Element，在 Python 内存里重新构造文档并计算 BM25。

按当前平均值扩大 100 倍，会得到约 7,600 篇论文和 2,562,500 个 Element。仅 2048 维 float32
原始向量就约 19.6 GiB，还没有计算 HNSW、Sparse Index、Payload 和副本。继续让每个 Harness
Replica 重复加载正文，不适合作为长期产品边界。

## 要解决的问题

这次工作只解决一个问题：把可扩展的候选检索移出 Python Harness，同时不改变 Harness 的研究合同。

固定不动的内容包括：

- MiniMax 仍是产品模型；
- Agent Prompt 不改；
- `search_paper_candidates`、`find_papers_by_identity`、`find_reading_locations`、
  `read_locations` 和 `submit_research_answer` 的模型可见名称与 Schema 不改；
- Golden Data、Anchor、Reading Model 和既有路径不改；
- 只有准确读取后的 MySQL 内容才能生成 Evidence ID。

Qdrant 只负责返回候选 `location_ref`，不能成为论文事实来源。产品路径失败时应明确失败，不能偷偷
切回内存 BM25，也不能保留 Elasticsearch 兼容层。

## 怎么处理

### 先固定 Harness 接口

Python 继续由 `ReadingCorpusTools` 管理论文公开、位置公开、Evidence Ledger 和最终引用校验。产品
Adapter 改为调用 Java 的三个内部接口：

```text
POST /internal/v1/corpus/papers/search
POST /internal/v1/corpus/locations/search
POST /internal/v1/corpus/locations/read
```

运行开始时，Python 只根据锁定的 `scope.paper_ids` 创建轻量论文外壳，不访问 Java。模型调用论文
发现或身份解析工具后，Metadata 才原地补全。正文不再整批进入 Harness。

位置搜索经过下面的链路：

```text
MiniMax / Python Harness
-> Java Corpus API
-> Qdrant Dense + Sparse 候选
-> Java 校验权限、Current Model 和 Active Generation
-> MySQL 精确读取
-> Python 创建 Evidence ID
```

测试同时比较 Java Adapter 和内存 Fixture Adapter 的模型可见工具定义。Qdrant 的 Dense、Sparse、
Fusion 分数和 Index Version 会保留在内部诊断中，但不会进入模型上下文。

### 把索引生命周期放在 Java

Java 从 Current Reading Model 构建 Dense Vector、稳定哈希 Sparse Vector 和最小路由 Payload。
Qdrant Payload 不保存原始 `searchable_text`。每次重建使用一个新的 `index_generation`：

```text
写入新 Generation
-> 精确 Count
-> MySQL Compare-and-set 激活
-> 清理实际被替换的旧 Generation
```

查询只接受 MySQL 当前记录的 Active Generation，并校验 Collection、Embedding Model 和向量维度。
查询路径只能验证现有 Collection，不能顺手创建基础设施。

## 遇到的问题

### Paper 与 Generation 分开过滤会产生交叉组合

第一版把 Qdrant Filter 写成：

```text
paper_id in [A, B]
index_generation in [genA, genB]
```

它同时允许 `A/genB` 和 `B/genA`。Java 最后的 Current Model 校验会丢弃这些旧 Hit，但旧数据仍可能
占满 Top K，让有效位置根本进不了候选列表。

修复后，Filter 是精确的 OR Pair：

```text
(paper_id=A AND generation=genA)
OR
(paper_id=B AND generation=genB)
```

单元测试检查 Pair 没有串线，认证 Real-Qdrant Smoke 再用四个交叉 Point 验证只返回两个 Active
Point。

### 只做逐论文查询会丢掉全局相关性

为了比较多篇论文，第一版对每篇论文单独查询。这样能保证每篇至少有候选，但每次查询的第一名都会
得到相同 RRF 排名贡献。当论文数大于 `top_k` 时，各论文分数不再可直接比较，最终选择可能退化成
位置字符串顺序。

当前实现始终先做一次全局查询，保留跨论文相关性。只有论文数不超过 8 且 `top_k` 足以覆盖每篇时，
才追加逐论文查询，用于补足比较覆盖。论文数大于 `top_k` 时只按全局结果选择。

### 删除“除新一代外的全部 Point”会破坏并发重建

两个 Java Replica 同时重建同一篇论文时，A 和 B 都可能写完新 Generation。A 激活后执行
“删除除 A 外的全部数据”，会把 B 尚未激活的 Point 删除；随后 B 又可能把数据库切到一个已经不存在
的 Generation。

修复后，MySQL 使用预期旧 Generation 做 Compare-and-set：

- 只有一个写入能从同一个旧 Generation 激活成功；
- 失败者只删除自己刚写入、从未激活的 Point；
- 成功者只删除它实际替代的上一代，不删除未知并发 Generation；
- 清理失败不会回滚已经验证并激活的新一代。

Qdrant Collection 首次创建也处理并发 `409`：失败方重新读取获胜方创建的 Collection，并验证命名
Dense/Sparse Vector 和维度，而不是直接让索引任务失败。

### 兼容层会让退役永远退不干净

旧搜索接口、前端 Evidence Search Dialog、Service-backed LitSearch/Page Window Runner 和三个全量
脚本最初仍留在仓库里。这些入口依赖已经删除的 Elasticsearch 路径，继续登记为 runnable 会让后续
维护者误以为两套检索都受支持。

最终删除了旧 Controller、检索实现、UI、脚本和测试。历史实验文档保留，但顶部明确标成归档，并
统一指向当前 Golden Data 指南。没有添加 Elasticsearch Fallback 或双写代码。

## 结果

| 验证 | 结果 |
| --- | ---: |
| Python Harness 测试 | 90/90 |
| 旧 Golden Fixture | 15/15 |
| 稳定 / 扩展确定性评分 | 15/15、30/30 |
| 稳定 / 扩展 Anchor Audit | 7/7、29/29 |
| Java Maven Test | 855，0 Failure，0 Error |
| Frontend Typecheck / Build | 通过 |
| 认证 Real-Qdrant Smoke | 通过 |

这组结果证明 Harness 合同、旧数据和扩展数据没有被这次数据面改造破坏，也证明 Qdrant 客户端的认证、
Collection 合同、Generation Filter、清理和错误路径能够在真实服务上工作。它没有证明 7,600 篇论文
的吞吐、HA 或检索质量，也没有替代 MiniMax 的最终回答质量评测。

当前仍未完成两件事：现有 76 篇 READY Reading Model 的显式回填，以及回填后的产品 Research 与
Citation Reopen Smoke。这两步会调用 Embedding Provider，不能伪装成普通启动流程。

## 复现资料

核心实现：

- `harness_py/corpus/gateway.py`
- `harness_py/corpus/tools.py`
- `src/main/java/io/github/chzarles/paperloom/service/CorpusRetrievalService.java`
- `src/main/java/io/github/chzarles/paperloom/service/ReadingModelQdrantIndexService.java`
- `src/main/java/io/github/chzarles/paperloom/service/QdrantClient.java`

验证命令：

```bash
.venv-harness/bin/python -m unittest discover -s harness_py/tests
.venv-harness/bin/python -m harness_py validate
.venv-harness/bin/python -m harness_py --manifest research/golden-data/manifest-expanded.yaml validate
mvn test
cd frontend && corepack pnpm typecheck && corepack pnpm build
```

详细架构、容量假设和后续 Gate 见
[`Qdrant 检索平面与 Elasticsearch 退役 Proposal`](https://github.com/CHZarles/paperloom/blob/main/docs/engineering-evolution/architecture/qdrant-retrieval-plane-and-elasticsearch-retirement-proposal-2026-07-15.md)。
