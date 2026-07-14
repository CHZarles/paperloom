---
title: 现在
description: Charles 当前围绕 PaperLoom 正在研究的问题。
---

# 现在

更新于 2026 年 7 月。这里列的是我当前正在处理、还没有得到稳定答案的问题。

## 当前技术问题

### 如何提高 canonical evidence selection

目标位置进入 Candidate 后，Agent 仍可能跳过它，或者引用同论文里的另一个相关位置。我正在检查
位置检索、多位置阅读、Claim-to-Evidence 约束和不确定性表达各自会影响哪一层结果。

### 如何让研究预算与问题复杂度匹配

固定的模型调用和工具调用上限会浪费简单问题的预算，也会让复杂问题提前失败。我在尝试用可观察
的任务结构分配预算，同时保持现有 SDK Tool Loop，不再引入一套很重的阶段状态机。

### 如何保存足够的 Trace，而不让 Trace 成为第二套运行时

原始事件要支持离线重算指标，在线 Recorder 则尽量只记事实。我还在收敛事实记录、派生分析和产品
可见进度的边界，避免 Recorder 慢慢长成第二套运行时。

### 如何把真实 Run 变成可训练的数据

当前 Eval Capture 已经能保存 Model Transport、Tool Arguments、Internal / Visible Result、Authorization
State、Evidence、Validation Failure、Token 与 Latency。下一步会补上 Quality Gate、Privacy / License
Filter 和 Split Isolation，再把通过验证的 Observable Trajectory 用于 Retriever、Tool Policy 与本地
小模型训练。Hidden Chain-of-thought 不进入这套数据。

## 当前写作计划

- `evaluate_evidence_coverage` 返回的错误如何改变模型下一步行为；
- 如何区分 Candidate 上限与 Selector regret；
- 如何从 BM25 Baseline 评估 Dense Retriever 与 Reranker；
- 如何把强 API 的 Accepted Trajectory 蒸馏成可运行的本地模型；
- 从真实前端性能基线到流式 Markdown 渲染策略；
- 如何让工程演化记录既诚实又不会变成文档仓库。

我会保持这个页面简短。完成的实验进入[实践记录](/practice/)，形成架构约束的决定进入 ADR。
