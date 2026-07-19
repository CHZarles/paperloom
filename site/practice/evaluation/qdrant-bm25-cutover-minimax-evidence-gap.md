---
title: Qdrant BM25 找到 48/48 份证据后，MiniMax 为什么仍只通过 9/24
description: 产品检索从 Hybrid Qdrant 切到 Sparse BM25 后，固定查询召回达到 48/48；MiniMax 实际运行有 47/48 份证据进入候选，只读取了 29/48。
date: 2026-07-19
category: 评估
stage: product retrieval cutover
status: 检索切换已通过，评分器缺陷已修复并离线复评分
result: 48/48 · v2 9/24 · v3 10/24
outline: [2, 2]
topics: [Qdrant, BM25, MiniMax, Golden Data, Evidence]
background: >-
  产品检索原来在每个 Python Worker 中加载 Reading Model 并临时构建 BM25。第一次迁到共享 Qdrant 时采用 Sparse、Dense 和 RRF 融合，解决了重复加载，却让相同候选预算下的指定证据命中从 42/48 降到 34/48。
problem: >-
  需要在 Prompt、Golden Data、Anchor、Reading Model、PDF、语料路径和模型可见工具合同不变的条件下恢复共享检索相关性，并定位检索恢复后的 MiniMax 失败层级。
approach: >-
  删除 Dense、Embedding、RRF、Elasticsearch 和请求级回退，由 Java 生成 BM25 风格 Sparse Vector，Qdrant 应用全局 IDF；先重放 69 次固定查询，再运行 Stable 与 Expanded MiniMax-M3 Golden Data。
outcome: >-
  固定查询达到 48/48 份证据、24/24 个完整 Case 和 0.48019 MRR；MiniMax-M3 的原始 v2 报告为 Stable 6/10、Expanded 9/24。同一批保存产物经修复后的 v3 评分器离线复评分为 7/10 和 10/24，没有重新调用模型。Sparse Qdrant 保留，先校正评分与人工复核，再决定是否修改 Harness 编排。
---

# Qdrant BM25 找到 48/48 份证据后，MiniMax 为什么仍只通过 9/24

<PracticeArticleOverview />

## 1. 背景、冲突、问题与结论

**背景。** 产品最早在每个 Python Worker 中加载授权论文的 Reading Model，再临时构建 BM25。第一轮
迁移把候选索引放到 Java/Qdrant，并组合 Sparse、Dense 和 RRF，解决了重复加载问题。

**冲突。** Hybrid Qdrant 在相同候选预算下只命中 `34/48` 份指定证据，低于历史内存 BM25 的
`42/48`。共享索引解决了运行边界，排序质量没有达到原词法基线。

**问题。** 能否保留共享 Qdrant，同时恢复 BM25 相关性？检索恢复后，MiniMax 的剩余失败发生在候选、
读取、引用还是最终答案？

**结论。** Sparse Qdrant BM25 在 69 次固定查询上达到 `48/48`，产品检索切换通过。MiniMax 实际运行
有 `47/48` 份所需证据进入候选，只读取 `29/48`。原始 v2 Expanded Hard Pass 为 `9/24`；修复
Content Scorer 后，同一保存产物的 v3 结果为 `10/24`。这个差异证明评分器本身会制造假回归，因此
不能先根据 `9/24` 修改 Harness。

## 2. 先固定四个指标

| 指标 | 检查内容 | 失败位置 |
| --- | --- | --- |
| Candidate | `find_reading_locations` 是否返回所需位置 | 检索词、排序、范围或候选预算 |
| Read | 模型是否用 `read_locations` 打开所需位置 | 候选选择与阅读策略 |
| Cited | 最终答案是否引用读取后生成的 Evidence ID | 证据绑定与提交行为 |
| Hard Pass | Outcome、论文、Anchor、内容与引用是否全部符合规则 | 端到端任一层 |

Candidate Preview 只用于导航。`read_locations` 从 MySQL 读取准确原文后，Harness 才生成可引用的
Evidence ID。`48/48` 与 `9/24` 衡量不同层级，不能互相替代。

## 3. 因果比较固定了哪些输入

本轮保持不变：

- Prompt 与模型可见 Tool Schema；
- Stable、Expanded Golden Data 与 Required / Forbidden 规则；
- Anchor、PDF、Extracted Text、Reading Model 和语料映射；
- MiniMax-M3 Provider 与单 Agent 运行方式；
- `find_reading_locations -> read_locations -> submit_research_answer` 工具合同。

允许变化的部分只有 Java/Qdrant 检索实现与索引合同。

**论点。** 固定查询重放可以用于判断检索实现，完整 MiniMax 运行用于判断端到端行为。

**论据。** 固定重放使用已经保存的 69 次 MiniMax 查询、论文范围和候选预算，不重新调用模型生成 Query。

**论证。** 固定输入后，候选差异主要来自索引与排序。完整运行仍包含模型采样、查询措辞和阅读选择，
因此不能把所有 Hard Pass 变化归到 Qdrant。

## 4. 论点一：第一次 Hybrid 方案混合了强弱不一的信号

**论据。** 切换前的固定查询结果为：

| 方法 | 指定证据命中 | 完整 Case | MRR |
| --- | ---: | ---: | ---: |
| 历史严格预算内存 BM25 | `42/48` | `20/24` | `0.35923` |
| Qdrant Log-TF Sparse | `40/48` | `17/24` | `0.34553` |
| Qdrant Dense | `15/48` | `4/24` | `0.058` |
| Hybrid + Coverage | `34/48` | `15/24` | `0.16997` |

**论证。** Log-TF Sparse 只计算词频，没有 BM25 的词语稀有程度和文档长度归一化。Dense 只有
`15/48`，等权 RRF 又把 Dense 与较强的 Sparse 当作同等信号，最终把部分正确候选压低。

Qdrant 是存储和检索执行器。退化来自 Sparse 编码与融合合同，数据不支持把问题直接归因于数据库。

**决定。** 不继续调整 RRF 权重，也不增加 Golden Case 专用 Boost。先删除 Dense 和融合变量，让共享
索引执行一条可解释的词法基线。

前一轮完整量化见
[`Qdrant 让 76 篇论文的广查询快了 4 倍，证据排序却退化了`](./qdrant-retrieval-impact-benchmark.md)。

## 5. 决策一：用 Sparse Qdrant 执行 BM25 风格检索

当前运行链路为：

```text
MiniMax Research Harness
-> find_reading_locations
-> Java 校验权限、Scope 和 Current Reading Model
-> Java 编码 BM25 风格 Sparse Query
-> Qdrant 在 lexical_bm25_v1 上执行一次检索
-> Java 校验候选并从 MySQL 回填 Preview
-> MiniMax 选择位置并调用 read_locations
-> Harness 生成 Evidence ID 并校验最终提交
```

Java 对文本执行 NFKC 归一化、小写化和 Unicode 词项切分，使用 `k1=1.2`、`b=0.75` 计算词频饱和与
文档长度归一化。Qdrant 的 `modifier=idf` 根据整个 Collection 的文档频率计算词语稀有程度。

全量重建冻结 `avgdl=240.6381754236385`。Analyzer、Term ID、BM25 参数、Point Projection、Qdrant
版本和 `avgdl` 一起写入 `retrieval_index_contract`。代码与索引合同不一致时，论文保持不可检索。

**论点。** 当前语料先采用单路 BM25 Sparse，不需要 Query Embedding、Dense Search 或 RRF。

**论据。** 历史 BM25 为 `42/48`，Log-TF Sparse 为 `40/48`，Dense 只有 `15/48`。词法信号已经覆盖
大部分证据，主要缺口来自 BM25 统计和 Point 组织。

**论证。** 恢复 IDF 与长度归一化可以直接处理已观察到的排序缺口。继续保留 Dense 会增加调用成本和
解释变量，却没有现有净收益证据。

## 6. 决策二：破坏式切换，不保留兼容路径

切换直接删除：

- Query 与索引 Embedding；
- Dense Vector、Dense Search 和 RRF；
- Elasticsearch 容器、环境变量和回答路径；
- 旧 Hybrid Collection 与请求级回退；
- 为旧索引合同保留的兼容状态。

**论点。** 产品只保留 Sparse Qdrant 一条检索路径，失败时明确失败。

**论据。** 多条路径会产生不同候选顺序，还需要双写、双测、状态切换和故障归因。Elasticsearch 没有
参与当前回答，内存 BM25 会恢复每个 Worker 的重复加载。

**论证。** 当前切换已经有固定查询 Gate 和完整 Golden 运行作为回归保护。保留请求级回退只会让同一
问题随运行状态进入不同检索器，降低结果可复现性。

**决定。** Python Harness 继续只负责编排、Scope、Evidence Ledger 和记录，不重新加载全文或维护生产
索引。

## 7. 论点二：产品检索切换已经通过

**论据一：构建与索引。**

| 项目 | 结果 |
| --- | ---: |
| Current READY Reading Model | `87/87` |
| Qdrant Point | `9,383` |
| 索引词项 Token | `2,257,908` |
| 失败论文 | `0` |
| 冻结 `avgdl` | `240.6381754236385` |

**论据二：固定查询。**

| 方法 | 指定证据命中 | 完整 Case | MRR |
| --- | ---: | ---: | ---: |
| 历史严格预算内存 BM25 | `42/48` | `20/24` | `0.35923` |
| 切换前 Hybrid 产品路径 | `34/48` | `15/24` | `0.16997` |
| 当前 Sparse Qdrant BM25 | `48/48` | `24/24` | `0.48019` |

**论据三：延迟。** 冻结的 76 篇范围中，窄查询 p50 为 `64.821 ms`；广查询 p50 为 `132.100 ms`，
p95 为 `371.559 ms`。

**论据四：验证。** 完整 Java 测试为 `685` 个通过、`3` 个跳过；Python Harness 为 `120` 个通过、
`1` 个跳过；前端类型检查、生产构建和 Bundle Budget 均通过。

**论证。** Schema、Collection、全局与逐论文 Point 数量、固定查询质量、延迟和完整应用测试共同覆盖
了数据迁移与运行链路。源码和运行检查也没有发现 Dense、Embedding、RRF、Elasticsearch 或请求回退。

**结论。** 现有证据支持接受 Sparse Qdrant 产品切换。证据范围仍是当前 87 篇论文、69 次保存查询和
冻结的 76 篇延迟测量，不能直接外推到数千篇或多副本并发。

## 8. 论点三：检索恢复没有自动转化为回答恢复

**论据。** MiniMax-M3 的原始验收报告与离线复评分为：

| Manifest | v2 原始报告 | v3 离线复评分 | 技术失败 |
| --- | ---: | ---: | ---: |
| Stable | `6/10` | `7/10` | `0` |
| Expanded | `9/24` | `10/24` | `0` |

v3 没有重新调用 MiniMax。旧评分器把任意非空 `fields` 当成完整 Golden Fact Contract：Stable 的
`bert_choice_followup_001` 明明写出 “bidirectional Transformer encoder”，Expanded 的
`adam_constraint_refinement_followup_001` 明明写出 `β₂ = 0.98`，都因为无关字段触发
`FACT_MISSING`。v3 改为检查用户可见 Markdown 后，两条假阴性转为通过。

Expanded 的 48 份 Required Evidence 分布为：

| 阶段 | 数量 |
| --- | ---: |
| 实际查询可见 Candidate | `47/48` |
| 被 MiniMax 读取 | `29/48` |
| v2 Hard Pass Case | `9/24` |
| v3 Hard Pass Case | `10/24` |

v2 的 15 个失败 Case 中，14 个已经具备全部 Required Candidate。v3 修复一条确定性假阴性后，剩余
失败包括候选出现后没有读取、接受 Forbidden Paper、没有命中规定 Anchor，以及应该返回 `partial`
时提交 `answered`。

**论证。** 检索器已经把多数所需位置交给 Agent，但 Hard Pass 同时受模型采样、精确 Anchor、Forbidden
规则和评分器影响。候选顺序与 Preview 仍会影响选择，`47/48` 也并非完美召回，所以不能把 Qdrant
影响归零，也不能把全部差距归到 Harness 编排。

**结论。** 先用同一 v3 Contract 比较旧、新保存产物并人工复核变化 Case。只有确认真实失败仍集中在
阅读或引用后，才进入 Harness 编排实验。

## 9. 论点四：旧内存 BM25 不能作为回退理由

**论据。** 旧 `18/30` 来自多次运行合并，并包含后来删除的 6 个非正文检索 Case，其中 5 个在
`17/30` 单路径运行中通过。当前 Expanded 只包含 24 个正文证据 Case。

旧内存 Adapter 还会在 `24/69` 条广查询或多论文查询中把候选扩展到最多 12 个，并使用不同的 Passage、
Lead、Section 和页面组织。固定相同候选预算后，历史 BM25 为 `42/48`，当前 Sparse Qdrant 为 `48/48`。

**论证。** `18/30` 与 `9/24` 的分母、采样、评分合同和候选行为都不同。使用同一 v3 Scorer 后，旧
MiniMax 的当前 24 Case 为 `11/24`，当前 Expanded 为 `10/24`，差距只有 1 个 Case，仍不能直接证明
内存路径更适合 MiniMax。回退还会恢复每个 Worker 的重复加载和两套产品实现。

**决定。** 不恢复内存 BM25、Dense 或 Hybrid。新的 Agent 编排实验继续固定 Sparse Qdrant 与 Golden
输入。

## 10. 切换过程中遇到的三个问题

### 10.1 增量编译掩盖了过期测试

一次增量 Maven 编译沿用 `target` 产物，没有及时暴露仍使用旧 Embedding Contract 的测试。执行
`mvn clean test-compile` 后，过期合同才报错。

**决定。** 删除接口、字段或运行分支后，发布验收必须从干净编译开始。增量测试只用于快速反馈。

### 10.2 隔离 Worktree 缺少被忽略的 PDF

完整 Java 测试在隔离 Worktree 中找不到被 Git 忽略的 Golden PDF。主工作区有文件，隔离目录没有。

最终把原有只读 PDF 挂载到隔离环境，再运行完整测试。语料没有复制、修改或重新生成。该失败按环境
问题记录，没有混入检索质量结论。

### 10.3 实际 MiniMax 仍有一个候选预算缺口

`tau_react_baseline_relationship` 在固定查询探针中位于第 9 名，但实际 MiniMax 的宽查询预算没有覆盖
该位置。

因此 `48/48` 只代表冻结的 69 次查询重放。实际 MiniMax 归因为 `47/48`。该缺口继续作为宽查询与
`top_k` 问题处理，不增加关键词或 Anchor 专用规则。

## 11. 工程决定

### 保留

- Java BM25 编码、Sparse-only Qdrant、Current Model 校验和 MySQL 准确回填；
- Python Harness 的单 Agent 编排、Scope、Evidence Ledger 和提交合同；
- 固定查询 Gate，先定位检索回归，再运行 MiniMax；
- Candidate、Read、Cited 和 Hard Pass 分层报告；
- 简单、持久、可离线分析的运行产物。

### 删除后不恢复

- Elasticsearch 回答路径；
- Query / Index Embedding、Dense Search 和等权 RRF；
- 内存 BM25 产品回退；
- Golden Case 专用关键词、Anchor Boost 或静默补候选。

### 下一轮工作

保持 MiniMax-M3、Sparse Qdrant、Prompt 和 Golden 输入不变，先人工复核 v3 下旧 `11/24` 与当前
`10/24` 的变化 Case。评分器与人工判断一致后，再决定是否需要修改 Harness。任何编排实验都必须同时
观察 `Candidate -> Read`、`Read -> Cited`、Case-by-Case 结果、Token、预算耗尽和无关论文阅读。

## 12. 证据边界

**能够支持的结论。** Sparse Qdrant 恢复并超过冻结查询下的历史词法基线；产品检索切换完成；多数
MiniMax 失败发生时，所需 Candidate 已经可见。

**尚不能支持的结论。** `48/48` 不代表任意模型 Query 和 `top_k` 都能完整召回；v2 `9/24` 与 v3
`10/24` 都是确定性合同分数，不代表只有这些答案对用户有用；当前数据也没有验证数千篇、Replica、
Snapshot / Restore 或滚动重启。

## 13. 复现资料

- [`Sparse Qdrant 切换验收记录`](https://github.com/CHZarles/paperloom/blob/main/docs/engineering-evolution/architecture/lexical-qdrant-product-cutover-proposal-2026-07-18.md)
- [`Lexical-First Qdrant 设计`](https://github.com/CHZarles/paperloom/blob/main/docs/engineering-evolution/architecture/lexical-first-qdrant-retriever-proposal-2026-07-17.md)
- [`Java BM25 编码器`](https://github.com/CHZarles/paperloom/blob/main/src/main/java/io/github/chzarles/paperloom/service/LexicalBm25Encoder.java)
- [`Qdrant 检索器`](https://github.com/CHZarles/paperloom/blob/main/src/main/java/io/github/chzarles/paperloom/service/QdrantReadingLocationRetriever.java)
- [`切换校验脚本`](https://github.com/CHZarles/paperloom/blob/main/research/golden-data/lexical_qdrant_cutover_verify.py)
- [`产品检索探针`](https://github.com/CHZarles/paperloom/blob/main/research/golden-data/qdrant_product_probe.py)
- [`Content Scorer v3 改造计划与验收`](https://github.com/CHZarles/paperloom/blob/main/docs/evaluation/deterministic-content-scorer-v3-plan-2026-07-19.md)

完整运行产物保存在：

```text
research/golden-data/local-runs/lexical-qdrant-cutover-20260718-173750/
research/golden-data/local-runs/scorer-v3-rescore-20260719/
```
