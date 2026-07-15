---
title: 工程演化
description: PaperLoom 如何从问题、失败与验证中形成当前架构。
---

# 工程演化

时间线只保留改变过系统方向的故障、决策和验证，不按提交数量罗列过程稿。

## 2026.06 · 收敛产品边界

项目从宽泛的文档问答收敛到研究论文 PDF。产品论文与评估语料被分到不同数据域，会话来源改成显式
状态，论文身份存在歧义时不再任意选择。

这次收敛留下的约束很简单：先确定产品允许回答什么，再谈 Retriever 能找回多少内容。

## 2026.06 · 修复错误任务路由

一次论文库数量问题错误进入 Paper QA，最后还返回了无关引用。修复没有增加问句特判，而是拆分
typed Task Router、Capability Executor 与 Product Paper Corpus，并让无法判断的语义路由明确失败。

从那以后，检索结果不能再用来掩盖任务没有被识别。

## 2026.07 · 建立 canonical Reading Model

正式 Parser 与简化 Golden 数据链路产生了不同结构，检索指标因此混入输入契约差异。产品与 Golden
改用同一条正式 Reading Model 构建链，重新带回章节、类型、物理页与 Parser provenance。

排障顺序随之调整为先核对两批输入，再调 Ranker。

## 2026.07 · 从最终答案走向 Evidence Funnel

Candidate Recall 恢复后，总通过率只多了一个 Case。Evaluation 开始分别记录 Candidate、Read、Cited、
Outcome 与 Hard Pass，并在最终提交时调用 `evaluate_evidence_coverage`。

从 Candidate 到最终引用之间还有多次模型决策，召回数字无法代替这些行为指标。

## 2026.07 · 对编排和 Provider 做负结果实验

Best-of-2 没有提高通过率，模型 Provider 迁移也出现明显回归。实验补上了 sample isolation、Selector
regret、Responses API adapter 和更完整的成本记账。

后续评估同时观察模型、工具协议、预算和停止条件。只换模型名称或采样数，无法预测产品结果。

完整记录保存在仓库的
[engineering-evolution](https://github.com/CHZarles/paperloom/tree/main/docs/engineering-evolution) 目录。
