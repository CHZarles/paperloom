---
title: 现在
description: PaperLoom 当前尚未解决的技术问题。
---

# 现在

更新于 2026 年 7 月。以下问题仍在实现和评估中，暂时没有稳定结论。

## Canonical Evidence Selection

目标位置进入 Candidate 后，Agent 仍可能跳过它，或者引用同论文里的另一个相关位置。当前检查范围
包括位置检索、多位置阅读、Claim-to-Evidence 约束和不确定性表达。

## 研究预算与问题复杂度

固定的模型调用和工具调用上限会浪费简单问题的预算，也会让复杂问题提前失败。预算分配需要利用
可观察的任务结构，同时保留现有 SDK Tool Loop，避免重新引入重型阶段状态机。

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
