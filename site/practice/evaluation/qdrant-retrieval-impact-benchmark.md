---
title: Qdrant 让 76 篇论文的广查询快了 4 倍，证据排序却退化了
description: 共享预构建索引省掉了 Python Worker 的重复加载；同预算下，指定证据命中从 BM25 的 42/48 降到 34/48。
date: 2026-07-16
category: 评估
stage: retrieval evaluation
status: 历史 Hybrid 结果，已由 Sparse BM25 替代
result: 34/48
outline: [2, 2]
topics: [Qdrant, BM25, 检索, 人工复核, 可靠性]
background: >-
  产品库有 76 篇论文。旧路径会在每个 Python Worker 中加载授权论文并临时构建 BM25，单次初始化需要 6.94 至 8.84 秒，RSS 增加约 243 MiB。
problem: >-
  候选检索迁到 Java/Qdrant 后，需要确认速度、内存和扩展性是否改善，同时检查指定证据排序、等价证据覆盖和运行可靠性有没有退化。
approach: >-
  固定 14 篇论文、69 次真实 MiniMax 查询和 48 个证据要求，分别比较实际输出与相同候选预算；再通过 Java 产品路径测试 1、8、32、76 篇论文，并人工复核精确位置不一致的候选。
outcome: >-
  76 篇广查询快 4.34 至 4.86 倍，也省掉了每个 Worker 的重复加载；同预算下，BM25 的指定证据命中为 42/48，Hybrid Qdrant 为 34/48。该负向结果推动产品在 7 月 18 日删除 Dense 与 RRF，Sparse Qdrant BM25 随后达到 48/48。
---

# Qdrant 让 76 篇论文的广查询快了 4 倍，证据排序却退化了

<PracticeArticleOverview />

> 2026-07-18 更新：本文记录的 Hybrid 排序已经退出产品。当前路径使用 Sparse Qdrant BM25，固定查询
> 达到 `48/48`。切换验收和 MiniMax 端到端结果见
> [`Qdrant BM25 找到 48/48 份证据后，MiniMax 为什么仍只通过 9/24`](./qdrant-bm25-cutover-minimax-evidence-gap.md)。

## 1. 背景、冲突、问题和结论

**背景。** PaperLoom 原来会在每个 Python Worker 中加载本轮授权论文的 Reading Model，再临时构建
BM25。BM25 根据词频、词语稀有程度和文本长度排列候选。论文少时，这条本地路径响应很快；论文与
Worker 增加后，同一批正文会被重复加载并占用多份内存。

产品库达到 76 篇论文时，每个 Worker 的语料加载和工具初始化需要 `6.94-8.84 s`，RSS 增加约
`243 MiB`。因此候选索引被迁到 Java/Qdrant。Qdrant 是共享检索服务，MySQL 继续保存可以引用的准确
原文。

**冲突。** 共享索引解决了重复加载，检索结果却不能只看“接口是否接通”。新的排序方法同时改变了
关键词权重、向量召回、候选融合和内容粒度。查询变快，不代表证据排得更准。

**问题。** 这轮评估要确认三件事：大范围查询是否更快，指定证据与等价证据是否仍能进入候选，
Qdrant 的索引和运行故障是否已经达到产品要求。

**结论。** Java/Qdrant 在本地 76 篇广查询中快 `4.34-4.86` 倍，也省掉了每个 Worker 的重复加载。
相同候选预算下，指定证据命中从 BM25 的 `42/48` 降到 Qdrant 的 `34/48`。人工复核后，两者的等价
证据覆盖为 `48/48` 和 `47/48`。共享索引继续使用，当前排序方法没有被当作质量升级。

具体架构切换记录在
[`Qdrant 检索切换后的上传与重建规则`](../architecture/qdrant-corpus-plane-cutover.md)。

## 2. 先固定四个概念

同一组数字可以回答不同问题。评估前先固定下面四个概念，避免比较时改变口径。

| 概念 | 含义 | 用途 |
| --- | --- | --- |
| 实际输出 | 保留迁移前后两条产品路径原本返回的候选数量 | 观察产品切换后实际发生了什么 |
| 相同预算 | 两种方法都停在每条查询请求的 `top_k` | 比较相同候选数量下的排序差异 |
| 指定证据命中 | 候选匹配人工预先标记的页码和原文位置 | 检查稳定位置与排名回归 |
| 等价证据覆盖 | 同论文中的其他原文也能支撑相同结论 | 判断模型是否仍有足够内容回答 |

迁移前的 BM25 会在 24 条广查询或多论文查询中扩展候选，最多返回 12 个位置。当前产品 Qdrant 严格
停在模型请求的 `top_k`。因此 `44/48` 对 `34/48` 描述实际产品行为；`42/48` 对 `34/48` 才是相同
候选预算下的排序比较。

指定位置和等价证据也不能混成一个指标。指定位置适合做稳定回归，等价证据更接近回答所需内容。
等价证据排在第 9 名，而模型只请求前 5 名时，产品仍会丢失这份证据。

## 3. 论据怎样取得

### 3.1 固定输入

离线测试没有重新调用回答模型生成查询，而是重放已经保存的 MiniMax `find_reading_locations` 参数。

| 项目 | 数量 |
| --- | ---: |
| 论文 | `14` |
| 固定测试问题 | `30` |
| 有正文检索的问题 | `24` |
| 已保存查询 | `69` |
| 需要命中的证据 | `48` |
| 可以进入索引的指定位置 | `29/29` |

这样可以把查询词固定下来。候选变化来自检索器和索引，不来自模型临时换了一种问法。

### 3.2 拆开关键词、向量和融合排序

14 篇论文的 Reading Model 被整理成 789 个 Qdrant Point。一个 Point 对应一个稳定位置，可能包含同一
页或同一表格中的多个 Reading Element。

测试分别计算：

| 方法 | 排序依据 |
| --- | --- |
| BM25 | 词频、词语稀有程度和文本长度 |
| Qdrant Sparse | 查询词的哈希编号与词频 |
| Qdrant Dense | MiniMax `embo-01` 生成的查询和正文向量 |
| Dense + Sparse | 用 RRF 按两路候选名次合并 |
| 产品排序 | 融合结果再补足多论文覆盖 |

RRF 只看候选在两路结果中的名次。当前 Dense 与 Sparse 权重相同。

### 3.3 增加产品路径、人工复核与重复运行

离线对照之外，产品探针还经过 Python、Java、Query Embedding、Qdrant、Current Generation 校验和
MySQL 准确读取。延迟测试把授权范围扩展到 `1、8、32、76` 篇，每个条件先预热一次，再独立运行三次。

精确位置失败后，复核逐条打开候选原文，记录问题、查询、位置、排名和判断理由。脚本负责验证人工
判断引用的候选确实存在于对应方法和排名；语义是否等价仍由人工判断。

同一脚本、配置、数据和 69 条查询还完成了两次独立 Fresh Index，用于检查汇总结果与逐条排名是否
能够重复出现。

## 4. 论点一：共享索引改善了大范围查询

**论点。** Java/Qdrant 改善了 76 篇论文范围内的广查询，也删除了每个 Python Worker 的重复加载。

**论据。** 本地测量结果如下：

| 场景 | Python BM25 | Java/Qdrant |
| --- | ---: | ---: |
| 每轮语料加载和工具初始化 | `6.94-8.84 s` | Python 不再重复加载全文 |
| Python Worker RSS 增量 | 约 `243 MiB` | 使用共享 Qdrant 服务 |
| 一篇论文内的窄查询 P50 | `19-40 ms` | `345-456 ms` |
| 76 篇广查询 P50 | `1.838-2.139 s` | `0.378-0.493 s` |

三组 76 篇广查询中，Java/Qdrant 快 `4.34-4.86` 倍。

**推理。** BM25 在每个 Worker 中扫描已经加载的文本，扫描成本会随论文数量增长。Qdrant 的索引在
Reading Model 更新时预先构建，请求阶段主要承担 Query Embedding、共享索引查询和 MySQL 补全。论文
范围扩大后，预构建索引抵消了远程调用的固定成本。

**边界。** 窄查询中，已经加载好的本地 BM25 只需一次计算，因此比 Java/Qdrant 快约一个数量级。
现有数据只支持“大范围查询更快”，不支持“所有查询都更快”。测试也没有并发启动多个 Worker 或
Qdrant Replica。

## 5. 论点二：当前 Qdrant 排序弱于 BM25

**论点。** 当前 Sparse、Dense 和等权融合都没有超过 BM25。主要问题出在排序方法，候选数量差异只能
解释其中一小部分。

**论据。** 保留两条路径实际输出时，结果为：

| 方法 | 指定证据命中 | 完整问题 | MRR |
| --- | ---: | ---: | ---: |
| BM25，包含原有候选扩展 | `44/48` | `20/24` | `0.366` |
| Qdrant Sparse | `40/48` | `17/24` | `0.346` |
| Qdrant Dense | `15/48` | `4/24` | `0.058` |
| Dense + Sparse 等权融合 | `33/48` | `14/24` | `0.166` |
| 产品融合与多论文覆盖 | `34/48` | `15/24` | `0.170` |

MRR 越高，表示正确位置整体排得越靠前。

相同候选预算下，结果仍有明显差距：

| 方法 | 指定证据命中 | 完整问题 | MRR |
| --- | ---: | ---: | ---: |
| BM25 | `42/48` | `20/24` | `0.359` |
| 产品 Qdrant | `34/48` | `15/24` | `0.170` |

**推理。** 当前 Sparse 只使用哈希词项与 `1 + log(term_frequency)`，缺少 BM25 的词语稀有程度和长度
归一化，因此先少命中 4 个指定位置。Dense 只有 `15/48`，与 Sparse 等权合并后，又把较好的关键词
候选压低。多论文覆盖只恢复了 1 个证据。

把 BM25 截断到相同 `top_k` 后，它只减少 2 个命中。剩余 8 个命中差距无法用候选扩展解释。

## 6. 论点三：精确位置下降大于语义证据下降

**论点。** `34/48` 准确描述指定位置排序，却高估了语义证据损失。Qdrant 的多数精确失败中仍有等价
原文，但部分候选排得太深，用户请求较小 `top_k` 时仍会丢失。

**论据。** 产品 Qdrant 有 14 个精确位置没有命中。人工复核发现 13 个候选能够支撑相同结论，1 个仍
证据不足。

| 方法 | 指定证据命中 | 人工复核后 | 精确完整问题 | 复核后完整问题 |
| --- | ---: | ---: | ---: | ---: |
| BM25 | `44/48` | `48/48` | `20/24` | `24/24` |
| 产品 Qdrant | `34/48` | `47/48` | `15/24` | `23/24` |

唯一证据不足出现在 AgentBench 与 ReAct 的关系问题。候选描述了 ReAct 风格的执行过程，却没有返回
说明 ReAct 首创“推理与行动结合”的 Related Work 位置。

3 篇产品论文的小样本得到相同现象：BM25 精确命中 `6/6`，Java/Qdrant 为 `2/6`；人工复核后两者
都是 `6/6`。4 个 Qdrant 等价候选分别排在第 `9、4、7、1` 名。

**推理。** 同一事实可能同时出现在摘要、正文、图注和表格。固定测试只把其中一个位置设为 Anchor，
因此自动评分会拒绝语义等价的其他位置。这个口径适合检查稳定排序，无法单独代表回答所需证据。

排名仍然影响产品结果。等价证据位于第 `7、8、9、10` 名时，`top_k=5` 的请求看不到它。人工复核缩小
了真实缺口，排序问题仍然存在。

## 7. 论点四：共享服务还没有完成可靠性验收

**论点。** Qdrant 已经承担产品候选检索，当前数据还不足以证明内存、高可用和逐条排名稳定性已经
通过生产要求。

**论据一：单 Worker 内存。**

| 服务 | 数据 | RSS | 磁盘 |
| --- | --- | ---: | ---: |
| Qdrant | 76 篇，4,467 Point | `559.1 MiB` | `89.7 MB` |
| Elasticsearch | 23,707 Chunk + 76 Paper | `1.56 GiB` | `487.7 MB` |

Qdrant 的 `559.1 MiB` 高于单个 BM25 Worker 约 `243 MiB` 的增量。多个 Worker 共享一份索引时才可能
获得总内存收益，本轮没有启动多个副本验证这个推断。Elasticsearch 的数据粒度不同，也没有参与当前
Harness 检索，因此资源快照不能用于证明 Qdrant 的检索质量。

**论据二：索引成本。** 14 篇论文的 4,021 个 Reading Element 被整理成 789 个 Point。Embedding 使用
`697,528` Token，完整离线索引耗时 `61.70 s`，其中 `56.43 s` 用在 Embedding。成本从每轮加载转移到
Reading Model 更新时的共享重建。

**论据三：文件句柄故障。** 第一次全量运行写出 8 条查询后，Qdrant 报错：

```text
Too many open files
```

当时进程正好使用 `1024/1024` 个文件句柄。旧 Health Check 只测试 TCP 端口，Qdrant 已经不能返回
HTTP 响应，容器仍显示 Healthy。

后续把 Compose 的 `nofile` 上限提高到 `65,536`，Health Check 改为验证 `/healthz` 的 HTTP 200，
全量测试也改用独立 Qdrant 容器。第一次失败运行完整保留，没有并入成功结果。

**论据四：重复运行。** 两次 Fresh Index 的产品融合结果都是 `34/48`，MRR 分别为 `0.17005` 和
`0.16997`；`36/69` 条查询的候选排名发生变化，其中 17 条还改变了原请求范围内的候选集合。

**推理。** 汇总分数接近，只能证明当前质量结论可以重复。逐条排名仍会变化，现有产物又没有保存每个
候选的原始分数，因此无法确定变化来自 Embedding、同分顺序或其他索引细节。后续需要保存原始分数、
固定第二排序规则，并保留多次运行结果。

Snapshot / Restore、Replica、Rolling Restart、数千篇论文容量和多副本并发仍未验证。

## 8. 五个不能从数据直接推出的结论

### 8.1 `44/48` 对 `34/48` 不能单独证明算法差距

两条实际产品路径返回的候选数量不同。纯排序比较需要使用相同预算，结果是 `42/48` 对 `34/48`。

### 8.2 `47/48` 不能证明排序已经足够好

人工复核找到了等价证据，部分候选位于第 7 至 10 名。较小 `top_k` 仍会丢失等价原文。

### 8.3 广查询快 4 倍不能推广到所有查询

一篇论文内的窄查询中，Java/Qdrant 明显慢于已经加载好的本地 BM25。

### 8.4 单个 Qdrant 不能直接证明节省内存

单个 Qdrant 的 RSS 高于一个 BM25 Worker 增量。共享服务的总内存收益需要多 Worker 实测。

### 8.5 MiniMax `1/3` 对 `0/3` 不能代表用户质量

最后的模型 Smoke 中，BM25 自动严格评分为 `1/3`，Java/Qdrant 为 `0/3`，每种方法每题只有一个样本。
多份答案引用了能支撑结论的等价证据，仍因没有命中指定位置而失败。这组结果只验证工具链和失败层级。

## 9. 工程决定

### 保留

- Java 管理 Qdrant 候选索引、Query Embedding、Current Generation 和权限校验；
- Python Harness 维护 Agent 编排、Paper / Location 公开状态和 Evidence Ledger；
- MySQL 保存准确原文，Qdrant 只保存可重建候选；
- 指定位置与人工复核后的等价证据分别报告；
- Qdrant 故障明确失败，不增加内存 BM25 静默回退。

### 当前结果不支持

- Dense 已经改善证据排序；
- Dense 与 Sparse 等权融合优于单独 Sparse；
- Qdrant 在所有查询范围内都更快；
- 三个 MiniMax 样本能够代表稳定用户体验；
- 当前部署已经通过完整高可用验收。

### 后续结果

后续实验保留 Qdrant 数据面，删除 Query / Index Embedding、Dense Search 和 RRF，改用 Java BM25
编码与 Qdrant 全局 IDF。69 条冻结查询达到 `48/48`、`24/24` 和 `0.48019` MRR。

当前结论限定在本次数据和运行条件内：Qdrant 改善了共享索引与大范围查询的扩展性，当前混合排序没有
超过 BM25。架构切换保留，相关性与高可用继续作为独立问题处理。

## 10. 复现资料

完整方法、原始数据和失败时间线：

- [`Qdrant 检索影响量化报告`](https://github.com/CHZarles/paperloom/blob/main/docs/evaluation/qdrant-retrieval-impact-2026-07-15.md)
- [`离线检索脚本`](https://github.com/CHZarles/paperloom/blob/main/research/golden-data/qdrant_impact_benchmark.py)
- [`离线测试配置`](https://github.com/CHZarles/paperloom/blob/main/research/golden-data/qdrant-impact-benchmark.yaml)
- [`等价证据复核表`](https://github.com/CHZarles/paperloom/blob/main/research/golden-data/qdrant-impact-benchmark-adjudication.yaml)
- [`复核校验脚本`](https://github.com/CHZarles/paperloom/blob/main/research/golden-data/qdrant_impact_adjudication.py)
- [`产品路径探针`](https://github.com/CHZarles/paperloom/blob/main/research/golden-data/qdrant_product_probe.py)
- [`Java 检索实现`](https://github.com/CHZarles/paperloom/blob/main/src/main/java/io/github/chzarles/paperloom/service/CorpusRetrievalService.java)

```bash
.venv-harness/bin/python research/golden-data/qdrant_impact_benchmark.py --preflight

# MYSQL_CONTAINER 需要指向本地运行中的 MySQL 容器。
QDRANT_BASE_URL=http://127.0.0.1:6335 \
PAPERLOOM_QDRANT_CONTAINER=paperloom-qdrant-benchmark \
.venv-harness/bin/python research/golden-data/qdrant_impact_benchmark.py \
  --mysql-container "$MYSQL_CONTAINER" \
  --out /tmp/paperloom-qdrant-impact \
  --cleanup

.venv-harness/bin/python research/golden-data/qdrant_impact_adjudication.py \
  --adjudication research/golden-data/qdrant-impact-benchmark-adjudication.yaml \
  --out /tmp/paperloom-qdrant-adjudication.json
```
