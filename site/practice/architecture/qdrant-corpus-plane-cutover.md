---
title: Qdrant 检索切换后的上传与重建规则
description: 以论点、论据和推导逐项决定索引何时可用、谁能重建、重建期间能否检索，以及是否需要保留多个 Generation。
date: 2026-07-15
category: 架构
stage: implementation
status: 检索切换已验证，单份索引规则已实现
result: 4,467 Points
outline: [2, 2]
topics: [Qdrant, Harness, 检索, 索引重建, 架构]
background: >-
  产品库有 76 篇 Current Reading Model、25,625 个 Reading Element。改造前，Python Harness 会在每轮研究中加载授权正文并临时构建 BM25；迁移后，Java/Qdrant 提供共享候选检索，MySQL 继续保存准确原文。
problem: >-
  迁移阶段的代码把重新解析论文和重建 Qdrant 候选都称为 reindex，并为了支持重建期间继续检索，引入了 index_generation、MySQL 条件激活和多份索引并存。实际业务通常只上传一次论文，需要判断这套设计是否超过了当前需求。
approach: >-
  先固定上传、重新解析、Qdrant 候选重建和 Reading Model Version 的含义，再根据实际运行链路，分别论证重建入口、重建期检索、多 Generation、任务并发和失败处理。
outcome: >-
  Java/Qdrant、MySQL 与 Python Harness 的责任边界得到保留；重建被限制为维护操作，受影响论文在重建期间暂停检索，同一篇论文只允许一个全局任务。任务去重落地后，index_generation 及其配套逻辑已经删除。
---

# Qdrant 检索切换后的上传与重建规则

<PracticeArticleOverview />

> 2026-07-17 更新：本文提出的单份索引、任务互斥和失败停用规则已经进入代码。权限模型同时收敛为
> 个人空间与管理员全局发布。实现过程、失败记录和验证结果见
> [`论文权限与索引生命周期如何收敛为四个事实`](./paper-access-and-index-lifecycle-simplification.md)。

## 1. 背景、冲突、问题与结论

**背景。** PaperLoom 的 Agent 运行在 Python Harness 中。迁移前，每轮研究都会把授权论文正文加载到
Python Worker，再临时构建 BM25。迁移后，Java/Qdrant 负责共享候选检索，MySQL 继续提供准确原文。

**冲突。** 当前索引服务支持旧、新两个 Generation 并存。它可以让论文在重建期间继续被搜索，也带来
条件激活、成对过滤、并发清理和失败回滚等配套逻辑。

**问题。** 论文通常只上传一次，维护重建也很少发生。在这个业务前提下，是否还需要支持在线重建、
多个 Generation 和并发激活？

**结论。** 当前产品只保留一套简单规则：首次上传完成后才开放检索；重新解析和 Qdrant 候选重建只作
为维护操作；重建期间暂停受影响论文的检索；同一篇论文只运行一个全局任务；失败后保持不可检索并
等待重试。

这个结论不会改变 Agent 工具、Reading Model、Evidence 或引用规则。需要简化的是 Qdrant 索引的维护
方式。

## 2. 先固定四个概念

文章后面的推导依赖下面四个概念。它们需要始终保持同一含义。

| 概念 | 含义 | 是否属于普通使用 |
| --- | --- | --- |
| 首次上传 | 上传 PDF，运行 Parser，生成 Current Reading Model，再创建第一份 Qdrant 候选 | 是 |
| 重新解析 | 再次读取原始 PDF，删除旧 Parser 产物，重新生成 Reading Model 和 Qdrant 候选 | 否，属于维护 |
| 重建 Qdrant 候选 | 直接读取已有 Current Reading Model，只重新生成 Qdrant Point | 否，属于维护 |
| Reading Model Version | Parser 产出的结构化论文版本 | 会在重新解析后变化 |

当时代码还有一个 `index_generation`。它表示某次 Qdrant 写入任务生成的一批 Point，不代表论文版本，
也不代表 Reading Model Version。

仓库里有两个名字相近、动作不同的入口：

| 入口 | 当前动作 |
| --- | --- |
| `POST /api/v1/papers/{paperId}/reindex` | 重新读取 PDF，重新解析，再生成 Qdrant Point |
| `POST /api/v1/admin/retrieval/reindex-current` | 读取已有 Current Reading Model，只重新生成 Qdrant Point |

异步失败重试还会再次进入论文处理流程。后文使用“重新解析”和“重建 Qdrant 候选”区分两类维护操作，
不再用一个模糊的“重建索引”代替两者。

## 3. 论证所依据的迁移阶段事实

### 3.1 当前回答链路

Java 接收聊天请求，再把研究任务交给 Python Harness：

```text
Java ChatHandler
-> ProductReadingConversationService
-> PythonResearchHarnessClient
-> POST /v1/research/stream
-> Python Agents SDK Harness
```

MiniMax 在 Harness 中调用五个研究工具：

| 工具 | 作用 |
| --- | --- |
| `search_paper_candidates` | 按标题、作者、年份等信息查找论文 |
| `find_papers_by_identity` | 根据身份信息确认具体论文 |
| `find_reading_locations` | 在已公开论文中查找可能相关的位置 |
| `read_locations` | 从 MySQL 读取准确原文并创建 Evidence ID |
| `submit_research_answer` | 检查答案与引用后提交结果 |

候选检索经过下面的链路：

```text
find_reading_locations
-> Java 校验用户权限和 Current Reading Model
-> Java 生成 Query Embedding
-> Qdrant 返回候选 location_ref
-> Java 从 MySQL 补全位置预览
-> Python 向模型公开候选位置

read_locations
-> Java 从 MySQL 读取准确原文
-> Python 创建 Evidence ID
```

`location_ref` 是页面、表格、图片或正文位置的稳定标识。Qdrant 返回候选位置，MySQL 保存可以引用的
准确内容。

### 3.2 四个组件的责任

| 组件 | 负责的内容 | 不负责的内容 |
| --- | --- | --- |
| Java | 权限、Reading Model、Embedding 配额、Qdrant 查询和准确读取 | 不替 Agent 决定搜索词和阅读位置 |
| Qdrant | 保存可删除、可重新生成的候选 Point | 不保存最终 Evidence，不决定用户权限 |
| MySQL | 保存 Current Reading Model、Location 和准确原文 | 不承担 Agent 的临时状态 |
| Python Harness | Agent 工具循环、公开状态、Evidence Ledger 和提交校验 | 不持有长期全文索引 |

产品库已有 76 篇 Current Reading Model 和 25,625 个 Reading Element。迁移完成后，76 篇论文被回填
为 4,467 个 Qdrant Point。

旧 Elasticsearch 保存了 23,707 个 Chunk，占用约 `1.61 GB` RSS，但没有参与当时的 Assistant 回答。
旧运行路径已经删除，Qdrant 失败时也不会静默切回 Elasticsearch 或 Python 内存 BM25。

### 3.3 迁移阶段的多 Generation 流程

迁移阶段的实现为每次 Qdrant 写入分配新的 `index_generation`：

```text
读取当前 Generation
-> 写入新 Generation
-> 核对新 Generation 的 Point 数量
-> MySQL 条件激活新 Generation
-> 查询改用新 Generation
-> 删除被替换的旧 Generation
```

查询时，Java 按 `paper_id + index_generation` 的合法配对构造 Qdrant Filter。条件激活使用 MySQL
带预期旧值的更新（Compare-and-set），防止两个任务从同一个旧 Generation 同时切换成功。

这套实现能够支持在线重建。当时代码没有独立的全局单篇任务锁，所以索引层还要防御单篇重建、全量
重建和失败重试发生重叠。

## 4. 决策一：是否允许重建

**论点。** 允许维护者重新解析论文或重建 Qdrant 候选，普通用户流程只保留首次上传和失败重试。

**论据。** Qdrant Point 可以从 Current Reading Model 重新生成。Embedding 调整、索引结构变化或
Qdrant 数据恢复都可能需要再次生成候选。普通论文通常上传一次，READY 后很少重新解析。

**论证。** 完全删除维护能力会让索引损坏后只能重新上传全部论文。继续向普通用户公开重新解析入口，
又会增加权限、状态和失败处理。管理员维护入口可以覆盖恢复需求，同时保持普通使用简单。

**决定。** `reindex-current` 保留为管理员操作。`/papers/{paperId}/reindex` 收紧为维护能力，不再作为
论文所有者的常规功能。若维护入口长期没有调用记录，再直接删除。

## 5. 决策二：重建期间是否允许检索

**论点。** 重建期间暂停受影响论文的检索，其他论文继续可用。

**论据。** 首次上传期间，论文已经处于不可检索状态。维护重建发生频率低。Qdrant 只保存候选数据，
重建失败不会删除 MySQL 中的 Reading Model 和准确原文。

**论证。** 在线检索要求旧索引继续服务，新索引在旁边完整写入，再通过状态切换替换旧索引。暂停单篇
论文后，可以直接删除并重建该论文的 Qdrant Point，省去多个 Generation 和切换协议。

短暂不可用的代价可以直接观察：用户暂时搜不到受影响论文。多 Generation 的代价会进入写入、查询、
清理和故障恢复四条路径。当前业务没有提出单篇论文无停机重建的要求。

**决定。** 重建开始时把受影响论文设为不可检索；成功后恢复；失败后继续保持不可检索，并向维护者
返回错误。

## 6. 决策三：是否需要多个索引 Generation

**论点。** 当前业务只需要一份 Qdrant 候选，不需要长期保留 `index_generation`。

**论据。** 多 Generation 的直接用途是让旧索引在新索引构建期间继续处理查询。上一节已经决定暂停
受影响论文的检索。

**论证。** 推导过程可以写成三步：

1. 只有重建期间仍要检索时，旧索引才需要继续服务；
2. 当前决定禁止检索受影响论文；
3. 旧、新两份候选不再提供用户可见收益。

多 Generation 还引出了两个附加问题。查询必须把论文和 Generation 成对过滤；并发任务必须通过条件
激活决定谁能切换。这两个问题来自迁移阶段的实现选择，不应继续充当业务需求。

**决定。** 保留 `model_version`，因为候选必须对应当前 Reading Model。删除 Qdrant 写入批次使用的
`index_generation`，并把单篇论文的 Point 作为一份可以整体删除和重建的数据。

## 7. 决策四：是否允许并发重建

**论点。** 同一个 `paper_id` 同一时间只允许一个处理任务。全量重建同一时间也只允许一个。

**论据。** 用户权限彼此隔离，但 Qdrant Point 按 `paper_id` 全局保存。同一篇论文不会为每个用户复制
一份候选。当前单篇重建、全量重建和失败重试都可能调用同一个索引服务。

**论证。** 两个任务同时重建同一篇论文不会增加召回质量，只会重复计算 Embedding，并争用同一批
Point。任务入口禁止重叠后，索引层不再需要判断两个新 Generation 谁应该激活。

普通的“先查询、再创建任务”仍有竞争。两个 Java 实例可能同时查到没有任务，再分别创建。去重必须
由数据库的一次原子操作完成，例如唯一任务键或带状态条件的更新。

**决定。** 单篇任务按 `paper_id` 原子抢占。全量任务使用一个全局唯一任务键，再逐篇调用单篇流程。
第二个重复任务直接拒绝或跳过。

## 8. 决策五：失败后如何处理，是否需要回退

**论点。** 重建失败后保持不可检索，记录错误并等待重试；Qdrant 故障不触发 Elasticsearch 或内存
BM25 回退。

**论据。** MySQL Reading Model 仍然完整，Qdrant Point 可以重新生成。Elasticsearch 已退出回答链路，
Python BM25 与 Qdrant 的候选排序也不相同。

**论证。** 明确失败只有一套可观察状态。静默回退会让相同问题在不同时间使用不同排序，评测结果和
线上回答难以复现。保留旧检索实现还会恢复双写、双测和双维护。

**决定。** 维护者根据失败记录重试。产品调用方收到明确错误。MySQL 内容不删除，Qdrant 候选重建
成功后再恢复检索。

## 9. 最小实现流程

### 9.1 首次上传

```text
上传 PDF
-> Parser 生成 Current Reading Model
-> 根据 Reading Model 生成 Qdrant Point
-> 核对 Point 数量
-> 将论文设为可检索
```

任何一步失败，论文都保持不可检索。失败重试重新执行同一条流程，不需要创建第二份可用索引。

### 9.2 重建 Qdrant 候选

```text
管理员发起重建
-> 原子抢占 paper_id 对应的任务
-> 将论文设为不可检索
-> 删除该论文原有 Qdrant Point
-> 从 Current Reading Model 重新生成 Point
-> 核对 Point 数量
-> 将论文恢复为可检索
-> 释放任务
```

任务失败时记录原因，论文继续保持不可检索。下一次重试仍从 MySQL 读取完整的 Current Reading Model。

### 9.3 重新解析

```text
维护者发起重新解析
-> 原子抢占 paper_id 对应的任务
-> 将论文设为不可检索
-> 重新读取原始 PDF
-> 生成新的 Current Reading Model
-> 生成一份新的 Qdrant 候选
-> 核对后恢复检索
```

重新解析只比 Qdrant 候选重建多了 Parser 步骤，仍然使用相同的任务去重和可检索状态。

### 9.4 全量重建

全量任务先抢占全局唯一任务键，再枚举 Current Reading Model，逐篇执行单篇流程。单篇任务仍按
`paper_id` 去重，用来处理全量任务与失败重试之间的重叠。

## 10. 这套实现后来如何简化

多 Generation 实现当时已经完成并通过测试。后续代码按照下面的顺序完成了单份索引改造。

| 当前机制 | 现有作用 | 调整方式 |
| --- | --- | --- |
| 每次重建生成新的 `index_generation` | 让旧、新索引暂时共存 | 暂停检索后删除 |
| MySQL 条件激活 Generation | 处理并发任务的激活竞争 | 完成原子任务去重后删除 |
| `paper_id + index_generation` 成对过滤 | 防止错误 Generation 占用候选 | Generation 删除后简化为授权论文和当前 Reading Model 校验 |
| 失败任务删除未激活 Generation | 保护仍在服务的旧索引 | 单份索引失败后保持不可检索 |
| 论文所有者调用 `/reindex` | 提供用户侧重新解析入口 | 收紧为维护操作 |

代码调整遵守了下面的依赖顺序：

1. 为单篇重建、全量重建和失败重试增加统一的原子任务去重；
2. 重建开始时把受影响论文设为不可检索；
3. 验证重建中的查询会明确失败，其他论文仍可检索；
4. 收紧普通用户可调用的重新解析入口；
5. 删除 `index_generation`、条件激活和成对过滤；
6. 用单份索引测试替换旧 Generation 测试。

先建立任务互斥和不可检索状态，再删除 Generation。反过来执行会留下并发写入和清理竞争。

## 11. 随后单独处理的检索问题

索引维护可以简化，候选排序仍有独立问题。它们有直接运行证据，不依赖多 Generation 假设。

### 11.1 多篇论文必须先做统一排序

**论点。** 多篇论文的候选需要先放在同一次查询中排序。

**论据。** 如果论文 A、B、C 分别查询，每篇论文的第一名都会得到相同的名次贡献。合并后的 A1、B1、
C1 无法继续比较谁更符合用户问题。

**论证。** 每篇论文内部的第一名只表示论文内相对顺序。全局前 K 需要一个共同的比较范围。当前实现
先查询全部授权论文，再在条件允许时追加逐论文查询以补充覆盖。

```text
同时搜索论文 A、B、C
-> B1、C1、A1、B2……
```

只有论文数不超过 8，并且 `top_k` 足以覆盖每篇论文时，系统才追加逐论文查询。论文数大于 `top_k`
时，只保留全局排名。

### 11.2 当时的 Hybrid Qdrant 排序弱于 BM25

**论点。** 共享 Qdrant 索引改善了广查询速度，当时的 Hybrid 排序没有达到 BM25 水平。

**论据。** 14 篇固定论文和 69 条已保存查询得到下面的结果：

| 指标 | BM25 | Java/Qdrant |
| --- | ---: | ---: |
| 实际输出中的指定证据 | `44/48` | `34/48` |
| 相同候选预算 | `42/48` | `34/48` |
| 人工复核后的等价证据 | `48/48` | `47/48` |
| 76 篇广查询 P50 | `1.838-2.139 s` | `0.378-0.493 s` |

**论证。** 广查询快 `4.34-4.86` 倍，支持共享预构建索引的扩展价值。相同候选预算下少命中 8 个指定
证据，表明当时的 Sparse 评分、Dense 权重或 Point 粒度仍需调整。

人工复核后只剩 1 个语义证据缺口，但部分等价证据排在第 7 至 10 名。较小 `top_k` 仍会受到排序位置
影响。完整过程见
[`Qdrant 让 76 篇论文的广查询快了 4 倍，证据排序却退化了`](../evaluation/qdrant-retrieval-impact-benchmark.md)。

2026 年 7 月 18 日，产品删除 Dense、Embedding 和 RRF，切换到 Sparse Qdrant BM25。固定查询达到
`48/48`，后续结果见
[`Qdrant BM25 找到 48/48 份证据后，MiniMax 为什么仍只通过 9/24`](../evaluation/qdrant-bm25-cutover-minimax-evidence-gap.md)。

### 11.3 Qdrant 运行状态需要单独验证

第一次全量量化把 Qdrant 的 `nofile=1024` 用满，旧 TCP Health Check 仍显示 Healthy。Compose 上限
随后提高到 65,536，并改用 HTTP `/healthz`。

文件句柄和健康检查属于运行问题。它们需要资源限制、恢复演练和监控，不构成保留多个索引 Generation
的理由。

## 12. 证据范围与后续验证

迁移完成时保留了模型可见工具、Prompt、Golden Data、Anchor、Reading Model 和 Evidence 创建条件。
当时的验证结果如下：

| 验证 | 结果 |
| --- | ---: |
| Python Harness 测试 | `90/90` |
| 旧 Golden Fixture | `15/15` |
| 稳定 / 扩展确定性评分 | `15/15`、`30/30` |
| 稳定 / 扩展 Anchor Audit | `7/7`、`29/29` |
| Java Maven Test | `855`，0 Failure，0 Error |
| 认证 Real-Qdrant Smoke | 通过 |
| 76 篇 Current Reading Model 回填 | 4,467 个 Point，Collection 为 Green |

**能够支持的结论。** 当时的候选检索已经迁到 Java/Qdrant；MySQL 准确读取和 Harness Evidence 合同
保持不变；多 Generation 实现能够运行并通过已有测试。

**当时尚不能支持的结论。** 上述测试没有验证简化后的单份索引流程，也没有验证数千篇容量、多个
Qdrant Replica、Snapshot / Restore 或 Rolling Restart。单份索引随后完成，容量与高可用仍未验证。

后续实现使用五项直接测试验收单份索引：

1. 首次上传完成前不能检索；
2. 重建中的论文不能检索，其他论文仍可检索；
3. 同一 `paper_id` 的第二个任务会被拒绝或跳过；
4. 重建失败后论文保持不可检索，MySQL Reading Model 不受影响；
5. 重试成功后论文恢复检索，Qdrant 中只留下当前 Point。

## 13. 工程结论

这次重新评审得到五项决定：

1. 普通使用只包含首次上传和失败重试；
2. 重新解析和 Qdrant 候选重建只作为维护操作；
3. 重建期间暂停受影响论文的检索；
4. 单篇任务和全量任务都在入口原子去重；
5. 任务约束落地后，删除 `index_generation`、条件激活和成对过滤。

第五项已在 2026 年 7 月 17 日完成，权限与生命周期实现记录见
[`论文权限与索引生命周期如何收敛为四个事实`](./paper-access-and-index-lifecycle-simplification.md)。

Qdrant 继续负责候选，MySQL 继续负责准确内容，Python Harness 继续负责 Agent 与 Evidence。索引维护
只保留当前业务实际使用的路径。

## 14. 复现资料

核心实现：

- `harness_py/corpus/gateway.py`
- `harness_py/corpus/tools.py`
- `src/main/java/io/github/chzarles/paperloom/service/CorpusRetrievalService.java`
- `src/main/java/io/github/chzarles/paperloom/service/ReadingModelQdrantIndexService.java`
- `src/main/java/io/github/chzarles/paperloom/service/QdrantReadingModelReindexService.java`
- `src/main/java/io/github/chzarles/paperloom/service/QdrantClient.java`

```bash
.venv-harness/bin/python -m unittest discover -s harness_py/tests
.venv-harness/bin/python -m harness_py validate
.venv-harness/bin/python -m harness_py \
  --manifest research/golden-data/manifest-expanded.yaml \
  validate
mvn test

.venv-harness/bin/python research/golden-data/qdrant_impact_benchmark.py --preflight
.venv-harness/bin/python research/golden-data/qdrant_product_probe.py \
  --out /tmp/paperloom-qdrant-product-probe \
  --run-model-smoke
```

详细资料：

- [`Qdrant 检索平面与 Elasticsearch 退役 Proposal`](https://github.com/CHZarles/paperloom/blob/main/docs/engineering-evolution/architecture/qdrant-retrieval-plane-and-elasticsearch-retirement-proposal-2026-07-15.md)
- [`Qdrant 检索影响量化报告`](https://github.com/CHZarles/paperloom/blob/main/docs/evaluation/qdrant-retrieval-impact-2026-07-15.md)
