---
title: 实践记录
description: PaperLoom 的连续工程实验：问题从哪里来，数据怎样改变判断，系统最后留下了什么。
---

# 实践记录

这些文章沿着 PaperLoom 的同一组问题展开：先把论文变成可检索的 Reading Model，再观察 Agent 找到
了什么、读了什么、引用了什么，最后用 Golden Data 判断改动有没有进入用户可见结果。

我会写下实验前的判断、实际改动、运行数据、成本和回退。没有跑过的想法留在[现在](/now)或
[边界与路线](/project/roadmap)，不会提前包装成经验。

[订阅 RSS](/feed.xml)

## 这一组实验从哪里开始

2026 年 7 月，我给 Harness 增加了一批 Agent Evaluation 论文和 15 个新 Case。BM25 改造后，新 Pack
反而从 `5/15` 掉到 `1/15`。继续往下查，我发现新增论文走了简化 PDF 文本链路，
章节、类型、物理页和 Parser Provenance 都丢了。

修复输入契约以后，Required Anchor 的 Candidate Recall 从 `16/32` 回到 `29/32`，全量 MiniMax 历史
参考达到 `18/30`。召回恢复以后，Hard Pass 只多了一个 Case。后面的两个实验都从这个落差出发：

```text
Retriever 已经暴露 Candidate
-> Agent 为什么没有读或引用 canonical Evidence
-> 增加采样能不能解决
-> 换 Provider 能不能解决
```

## 建议阅读顺序

### 01 · [我把检索召回从 16/32 修到 29/32，却只多过了 1 个 Case](evaluation/golden-data-harness-evolution)

新 Golden Pack 上 BM25 指标和通过率一起回归。排障后确认，产品与 Golden 使用了两套 Reading Model
输入契约。召回修好后，剩余失败集中到 Read、Cited、canonical evidence selection 和
`evaluate_evidence_coverage`。

这轮统一了正式 Parser / Reading Model 构建链，也开始分开记录 Candidate、Read 与 Cited。

`Golden data` · `Reading model` · `Evidence coverage` · `RAG evaluation`

### 02 · [我让 Agent 同时跑两遍，结果通过率从 60% 降到了 53.3%](evaluation/best-of-two-agent-orchestration)

单路径为了补齐 Coverage 反复修正，四个焦点 Case 用掉约 60.9 万 Token。我让两个隔离 MiniMax
Sample 并行运行，再由不读取 Golden Data 的 Selector 选择答案。

Selected 得分是 `16/30`，Oracle pass@2 是 `17/30`，总 Token 为 `3,437,297`。候选池没有超过旧参考，
Selector 还额外损失一个 Case。实验结束后，生产 Best-of-2、Selector 和 Sample Budget 都被删除；
Oracle / Regret 的离线分析方式和负结果保留下来。

`Best-of-N` · `Selector` · `Parallel orchestration` · `Cost`

### 03 · [换成 GPT 之后，Research Harness 为什么反而从 60% 降到了 36.7%](evaluation/provider-migration-experiment)

这个对照用来区分剩余失败来自 MiniMax，还是来自当前工具定义和预算。Prompt、Golden Data、Reading
Model、Retrieval 与 Tool Schema 保持不变，只增加 Responses API Adapter，再用
`gpt-5.3-codex-spark` 跑单路径 30 Case。

Hard Pass 是 `11/30`，其中 10 个 Case 预算耗尽，Outcome 与 Anchor Selection 都出现回归。Responses
API Adapter 留在 `harness_py/orchestration/agents/model.py`，本次模型没有成为默认 Provider。

`Provider` · `Responses API` · `Tool protocol` · `Budget`

## 三篇文章共同改了什么

| 观察 | 我原先的判断 | 数据给出的修正 | 当前系统里的处理 |
| --- | --- | --- | --- |
| Candidate Recall 下降 | 继续调 BM25 | 两批输入不满足同一 Reading Model 契约 | 产品与 Golden 共用正式构建链 |
| `evaluate_evidence_coverage` 触发多次修正 | 多采一条路径可能更划算 | Oracle 只到 `17/30`，Token 明显增加 | 恢复单 Runner，保留离线 Candidate / Selector 分析 |
| 更强模型名称 | 可能直接改善 Evidence Selection | GPT 实验降到 `11/30`，Outcome 也回归 | Provider Adapter 保留，默认模型不切换 |
| 剩余失败 | 召回还不够 | 多数失败发生在 Read、Cited 与 canonical Anchor 选择 | 继续做 Evidence Funnel 与 Human Label 分析 |

这组实验也改变了我的排障顺序。现在先确认输入数据是否同构，再固定 Query 看 Candidate，随后观察
真实模型的 Read / Cited，最后才讨论 Prompt、编排或 Provider。几层同时变化时，分数上升也很难知道
是哪一项起了作用。

## 这条研究线目前停在哪里

当前 Live Harness 已恢复为单个 Agents SDK Runner，检索仍是授权 Corpus 内的内存 BM25。Reading
Model 输入契约和论文级 Candidate / Read / Cited 检查已经进入提交校验，canonical evidence selection、Outcome
边界与研究预算仍然是主要问题。

下一篇实践记录要继续回答一个具体问题：目标位置已经进入 Candidate 后，怎样让 Agent 更稳定地读取
并引用它，同时避免把 Golden Anchor 写成生产规则。当前进展会先更新在[现在](/now)，形成稳定结论后
再进入这里。

项目的评估数据结构、可利用方向和本地模型蒸馏边界见[评估方法](/project/evaluation)。
