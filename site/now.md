---
title: 现在
description: PaperLoom 当前尚未解决的技术问题。
---

# 现在

更新于 2026 年 7 月 19 日。以下问题仍在实现和评估中，暂时没有稳定结论。

## Candidate 到 Read

产品链路已经切换到 Sparse-only Qdrant BM25。69 条冻结查询上的指定证据命中为 `48/48`，完整
Case 为 `24/24`，MRR 为 `0.48019`；76 篇广查询 p50 为 `132.1 ms`。MiniMax 实际运行中有 `47/48`
份所需证据进入候选，只读取 `29/48`。当前问题包括模型查询过宽、候选预算有限，以及 Agent 跳过已经
出现的准确位置。

## Read 到 Cited 与 Outcome

Agent 读取原文后，仍可能遗漏必要事实、接受 Forbidden Paper、只引用比较中的一方，或在证据不足时
提交 `answered`。当前实验保持 Retriever、Prompt 和 Golden 输入不变，只调整子问题覆盖、阅读完成度、
Claim-to-Evidence 约束和提交前检查。

## 研究预算与问题复杂度

固定的模型调用和工具调用上限会浪费简单问题的预算，也会让复杂问题提前失败。预算分配需要利用
可观察的任务结构，同时保留现有 SDK Tool Loop，避免重新引入重型阶段状态机。

## 长研究回合的上下文

当前 Runner 会在每次模型调用时重放本轮已有的工具结果和被拒答案。宽范围研究会让 Prompt 增长得比
有效工作状态更快。上下文压缩方案仍处于设计阶段，目标是保留准确 Evidence、权限和完整运行记录，
只缩小模型下一轮需要看到的投影。

## Trace 与 Runtime 的边界

原始事件需要支持离线重算指标，在线 Recorder 只记录事实。事实记录、派生分析和产品可见进度仍需
保持明确边界，避免 Recorder 演变成第二套运行时。

## 从真实 Run 生成训练数据

当前 Eval Capture 已保存 Model Transport、Tool Arguments、Internal / Visible Result、Authorization
State、Evidence、Validation Failure、Token 与 Latency。进入训练前还需要 Quality Gate、Privacy /
License Filter 和 Split Isolation。训练输入只使用经过验证的 Observable Trajectory，不包含 Hidden
Chain-of-thought。

完成的实验进入[实践记录](/practice/)，形成架构约束的决定进入
[ADR](https://github.com/CHZarles/paperloom/tree/main/docs/adr)。
