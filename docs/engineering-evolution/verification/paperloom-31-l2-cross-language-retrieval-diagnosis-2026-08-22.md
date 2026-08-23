# PaperLoom-31 L2 跨语言召回诊断

日期：2026-08-22
状态：根因已通过差分实验确认；分层 Benchmark 已实现并完成真实 Run，不宣称已有生产性能提升。
后续方案：[PaperLoom-31 产品对齐检索评测 Proposal](paperloom-31-product-aligned-retrieval-benchmark-proposal-2026-08-23.md)

## 1. 问题背景

代码版本 `5db2f1bdd7ae4f43539a1643f7e7d0646f666339` 的 PaperLoom-31 全量运行：

```text
research/benchmark/local/runs/20260822T113706Z-f9222712/run.json
```

L2 结果：

| 指标 | 结果 |
| --- | ---: |
| Recall@1 | `14/31 = 45.16%` |
| Recall@3 | `23/31 = 74.19%` |
| Recall@5 | `23/31 = 74.19%` |
| Recall@10 | `25/31 = 80.65%` |
| MRR | `0.5821` |

L2 不是论文级搜索，也不经过 Agent Loop。它先把正确 `paper_id` 作为 Oracle Scope 交给 Java Corpus API，
再用冻结 Query 在这一篇论文中检索，最后检查唯一冻结的 `location_ref` 是否进入 Top K，并验证精确读取和
Source Quote 创建。因此该结果用于定位 Location Retriever，不应归因于 MiniMax-M3 的 Agent Prompt 或
Harness 工具循环。

同一 Snapshot 从 2026-08-12 起的多次运行均为 Recall@5 `74.19%`、Recall@10 `80.65%`；本次只是
Recall@1 从 `41.94%` 变为 `45.16%`。因此它是稳定存在的检索能力缺口，不是最近提交引入的回归。

## 2. 最小复现

冻结 Snapshot：

```text
research/benchmark/local/snapshots/a61e8c1c240b2e8873b88d20da497b9ec0b98d9631c77a3e05c0634eeb92ecd3.json
```

从本次 L2 中取出 Top 5 之外的 8 个 Target，在相同 Java、Qdrant、论文 Scope、Element Type、
`top_k=20` 和目标 `location_ref` 下，只把中文 Query 改写为等义英文 Query。请求仍走真实接口：

```text
POST /internal/v1/corpus/locations/search
```

结果：

| Target | 中文 Query 排名 | 等义英文 Query 排名 |
| --- | ---: | ---: |
| Transformer-XL Table | 9 | 1 |
| DistilBERT Passage | 10 | 2 |
| FLAN Passage | Top 20 未命中 | 2 |
| InstructGPT Passage | Top 20 未命中 | 3 |
| LLM.int8() Passage | Top 20 未命中 | 15 |
| ReAct Passage | 20 | 1 |
| RLAIF Passage | Top 20 未命中 | 2 |
| Weak-to-Strong Passage | Top 20 未命中 | 2 |

8 个失败项中，改成英文后有 7 个进入 Top 3。这个单变量差分实验确认：主要问题是中文问题与英文论文
之间的查询语言错配，而不是最近的 Harness 改造。

## 3. 根因

### 3.1 Sparse 路径在跨语言查询下失去有效信号

`LexicalBm25Encoder` 使用下面的连续字母/数字规则提取 Token：

```java
Pattern.compile("[\\p{L}\\p{N}_]+")
```

连续中文短语会成为一个长 Token，无法命中英文正文。例如：

```text
Query:   附录 C 主要包含什么类型的内容？
Corpus:  C Detailed Outlier Feature Data
```

在 8 个失败 Target 中，6 个中文查询对应目标的 `sparse_score` 为 `0`。另外两个只依靠模型名、年份和
数字产生弱词面匹配，容易把同篇论文中的其他表格或同主题段落排到前面。

### 3.2 Dense 路径能跨语言，但不足以稳定定位精确证据

Sparse 没有信号后，排序主要依赖 MiniMax `embo-01` Dense 相似度。它能识别大致主题，但会把
“同一论文中语义相近的段落”排在“包含精确答案的段落”前。例如 ReAct 中文 Query 的前几名是 Ethics、
知识密集推理和决策任务概述，而目标是附录中的 Human-in-the-loop 行为修正细节。

当前 Hybrid 使用 RRF，Sparse 权重为 `3.0`、Dense 权重为 `1.0`。这不是可以直接改小的坏参数：英文
Query 与英文正文对齐后，Sparse 提供了最强的精确词面信号，7 个 Target 因而进入 Top 3。真正的问题是
该权重默认 Sparse Query 与语料使用相同语言；跨语言时 Sparse 要么缺席，要么只留下噪声。

### 3.3 结构定位问题不应只靠语义检索

LLM.int8() 的英文 Query 仍然只排第 15：

```text
What type of content is mainly included in Appendix C?
```

目标 Section 是 `C Detailed Outlier Feature Data`。BM25 会丢弃长度小于 2 的 Token，所以 Section
编号 `C` 不参与匹配；`Appendix` 又没有出现在目标 Section 标题中。这个问题本质上是结构导航，应该先用
现有 `get_paper_structure` 定位附录，再在 Section Scope 内搜索，而不是继续扩大语义检索 Top K。

### 3.4 Exact Location 指标低估了答案级召回

L2 只接受冻结的唯一 `location_ref`。FLAN 的目标结论段未进入 Top 10，但排名第 3 的 Abstract 已经写明：

```text
FLAN ... surpasses zero-shot 175B GPT-3 on 20 of 25 datasets
```

它足够支持问题答案 `GPT-3`，但仍被 Exact Location Recall 记为 Miss。因此当前 74.19% 同时混合了真正
漏掉答案和召回等价证据但没命中指定段落两类情况。Exact 指标仍应保留用于排序诊断，但不能单独代表
用户能否获得正确答案。

## 4. 与真实 Agent 链路的关系

本次 L3 Trace 表明，MiniMax-M3 已经经常把中文用户问题改写成英文检索词。例如：

```text
中文问题：DistilBERT 在保持多少能力的同时缩小多少模型规模？
Tool Query: 97% 95% model size reduction parameters performance retain
```

其他实际 Tool Query 也包括：

```text
Al-Rfou attention length 3800
compares favorably GPT-3 zero-shot
TACRED test set samples
```

这解释了为什么同一轮 Benchmark 的 L2 Exact Recall@5 只有 `23/31`，而 L3 仍有 `16/17` Hard Pass：
L2 使用原始冻结中文 Query 直接压测 Retriever；产品 Agent 在调用 Retriever 之前已经承担了部分
Query Formulation 工作。

## 5. 改进顺序

### 第一步：先把指标拆开，不通过改 Oracle 提分

PaperLoom-31 应同时报告：

```text
Raw Cross-language Recall  = 原始中文用户 Query -> 英文论文
Source-aligned Recall      = 等义英文 Query -> 英文论文
Exact Location Recall      = 是否命中冻结 location_ref
Equivalent Evidence Recall = 是否命中足以回答的其他证据段落
```

原始中文 Query 继续作为跨语言压力测试，不删除、不替换。Source-aligned Query 必须人工复核，不得包含
答案值或复制目标原文，避免 Query 泄漏。Equivalent Evidence 只作为附加人工复核结果，不能覆盖 Exact
Location 排名风险。

### 第二步：复用 Agent 已有的 Query Formulation，不新增翻译服务

当前 Agent 已能在一次模型调用中生成英文检索词。最小改动是把现有行为写进 `search_paper_content` 的
Tool 说明和回归 Case：

```text
英文论文 -> 使用简洁英文内容关键词搜索
结构问题 -> 先读取论文结构，再限制 Section Scope 搜索
首次结果不含答案 -> 改写一次 Query，而不是盲目扩大 Top K
```

不增加单独的“意图分析节点”、翻译模型调用或新消息类型。它们会增加延迟和失败点，而当前差分实验已经
证明源语言对齐本身能解决 8 个失败项中的 7 个。

这一步稳定的是实际产品链路和 L3，不会改变现有 Raw L2 数字，因为 L2 刻意绕过 Agent。不能通过把冻结
中文 Query 替换成英文来宣称 Retriever 已经提升；英文 Query 只能作为新增的 Source-aligned 对照指标。

### 第三步：让附录/章节问题走现有结构工具

复用 `get_paper_structure` 和已有 `section_refs`，为 `Appendix C` 这类 Query 增加一条端到端回归：

```text
列出 Section 结构
-> 选择 Appendix C 对应 Section Ref
-> 在该 Section 内 search_paper_content
-> read_paper_content 创建 Source Quote
```

不要为了一个 Case 直接保留所有单字符 BM25 Token。那会把英文冠词 `a`、代词 `I` 和大量 Section
编号一起放进 Sparse Index，扩大噪声和索引成本，却没有解决“结构问题误用语义检索”的根因。

### 第四步：若要直接提升 Raw L2，先比较多语言 Dense Embedding

Raw L2 的真实算法改进应发生在 Retriever 内部。先在同一冻结 Snapshot、相同 Sparse、RRF、Candidate
预算和 Top K 下，只替换 Dense Embedding，比较：

1. 当前 MiniMax `embo-01`；
2. 一个明确支持中英跨语言检索的 Embedding。

查询时翻译不是首选：它会为每次检索增加一次模型调用，而且产品 Agent 已经在做 Query Formulation。
索引时翻译整篇论文也不是首选：它会扩大索引和生成成本，并把翻译误差写入派生检索数据。多语言
Embedding 不改变用户问题和 Canonical Evidence，也不增加每次查询的 LLM 调用，因此是更干净的 A/B
变量。

只有候选 Embedding 已经把目标带进较深 Candidate Pool、但仍无法排进 Top K 时，才评估 Top 30 上的
轻量 Reranker。只有 Held-out Exact、Equivalent Evidence 和 L3 Hard Pass 同时不退化，且延迟、
Embedding Token、重建索引和运维成本可接受时才替换产品配置。当前没有证据支持先修改 RRF `3:1`
权重、直接扩大 Top K 或直接引入 Reranker。

## 6. 验收口径

第一轮实现完成后至少验证：

1. 当前 Snapshot 的原始中文 L2 结果仍被保留，不能用英文 Query 覆盖历史基线；
2. 同时产出 Source-aligned L2 指标和逐 Target 排名；
3. 上述 8 个差分 Target 中至少 7 个保持 Source-aligned Top 3；
4. LLM.int8() 的 Appendix C Case 通过结构工具进入目标 Section，并成功 Exact Read；
5. Agent 不增加额外翻译模型调用，PaperLoom-31 L3 Hard Pass 不低于当前 `16/17`；
6. 所有 Retrieval 改动继续保持 Java Scope 授权、Current Reading Model 过滤和 Source Quote Provenance。

## 7. 面试表达

> 在 PaperLoom-31 分层评测中，我发现单篇论文证据召回 Recall@5 只有 74.19%。我没有直接扩大 Top K
> 或调 RRF 权重，而是对 8 个失败 Case 做单变量中英 Query 差分：英文改写后 7 个进入 Top 3，从而定位
> 到中文问题检索英文论文导致 Sparse BM25 失效。进一步审计发现，附录编号属于结构导航问题，且 Exact
> Location 指标会把答案等价段落记为失败。最终方案是拆分跨语言、源语言对齐、精确位置和等价证据四个
> 指标，复用 Agent 已有英文 Query Formulation 与结构工具；只有残余 Miss 才考虑更换多语言 Embedding
> 或加入 Reranker，避免用更高成本掩盖错误的问题分层。

## 8. 实验结论与当前决策

2026-08-23 使用同一冻结 Snapshot、同一 Retriever、同一 Top K，只替换 Query 语言完成对照实验：

| 指标 | 中文原始 Query | 等义英文 Query |
| --- | ---: | ---: |
| Recall@5 | `23/31 = 74.19%` | `30/31 = 96.77%` |
| MRR | `0.5821` | `0.8441` |
| Technical Errors | `0` | `0` |

同一次 Run 的 L3 Hard Pass 为 `16/17`。唯一未被英文 Query 召回的
`target_paper_22_llm_int8` 是 Appendix C 结构导航问题，不属于普通跨语言语义召回问题。

因此当前结论不是“现有 Embedding 不行”，而是原始 L2 把“中文问题直接检索英文论文”的跨语言压力，
混入了 Retriever 效果评价。真实 Agent 通常会先生成英文 Tool Query，所以 L2-Raw 的 `74.19%` 不能直接
解释成线上准确率。

当前决策：

1. 暂不更换 Embedding，也不调整生产 Retriever；
2. 保留 L2-Raw，继续把它作为跨语言压力测试；
3. 增加 L2-Source-Aligned，用于区分语言问题和 Retriever 本身的问题；
4. 继续以 L3 代表真实 Agent 产品链路；
5. 将 Appendix C 结构导航和本次 L3 `single_06` 失败分别立项，不混入本次 Benchmark 校准。

证据 Artifact：
`research/benchmark/local/runs/20260823T042314Z-5fd8003b/run.json`。Source-Aligned Query 尚待独立人工
Review，因此正式签字前其数值标记为 provisional；这不影响“暂不迁移 Embedding”的保守决策。
