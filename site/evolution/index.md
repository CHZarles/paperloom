---
title: 工程演化
description: PaperLoom 如何从问题、失败与验证中形成当前架构。
---

# 工程演化

这里记录那些改过系统方向的认识。提交数量和过程稿不在这条时间线上。

## 2026.06 · 收敛产品边界

项目从宽泛的文档问答收敛到研究论文 PDF。我把产品论文与评估语料分开，将会话来源改成显式状态，
遇到论文身份歧义时也不再任意选择。

这次收敛留下的约束很简单：先确定产品允许回答什么，再谈 Retriever 能找回多少内容。

## 2026.06 · 修复错误任务路由

一次论文库数量问题错误进入 Paper QA，最后还返回了无关引用。我没有为这个问句增加特判，而是拆分
typed Task Router、Capability Executor 与 Product Paper Corpus，并让无法判断的语义路由明确失败。

从那以后，检索结果不能再用来掩盖任务没有被识别。

## 2026.07 · 建立 canonical Reading Model

正式 Parser 与简化 Golden 数据链路产生了不同结构，检索指标因此混入了输入契约差异。我让产品和
Golden 共用正式 Reading Model 构建链，重新带回章节、类型、物理页与 Parser provenance。

这次排障改变了我的顺序：先核对两批输入，再调 Ranker。

## 2026.07 · 从最终答案走向 Evidence Funnel

Candidate Recall 恢复后，总通过率只多了一个 Case。我开始分别记录 Candidate、Read、Cited、Outcome
与 Hard Pass，并在最终提交时调用 `evaluate_evidence_coverage`。

从 Candidate 到最终引用之间还有多次模型决策，召回数字无法代替这些行为指标。

## 2026.07 · 对编排和 Provider 做负结果实验

Best-of-2 没有提高通过率，模型 Provider 迁移也出现明显回归。实验过程中，我补上了 sample
isolation、Selector regret、Responses API adapter 和更完整的成本记账。

现在我会把模型、工具协议、预算和停止条件放在一起评估。只换模型名称或采样数，无法预测产品结果。

完整记录保存在仓库的
[engineering-evolution](https://github.com/CHZarles/paperloom/tree/main/docs/engineering-evolution) 目录。
