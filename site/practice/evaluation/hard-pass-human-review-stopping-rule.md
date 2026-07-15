---
title: MiniMax 17/30、GPT 14/30，人工盲审却给出相反结果
description: 自动严格评分与人工盲审给出相反排名，因此重新界定指标含义并停止继续增加校验层。
date: 2026-07-15
category: 评估
stage: evaluation
status: 已结束
result: 22/30 人工通过
topics: [Golden Data, 人工评估, MiniMax, Agent Harness]
background: >-
  同一批 30 个问题中，自动严格评分显示 MiniMax 17/30、GPT 14/30，看起来 MiniMax 更好。
problem: >-
  需要确认自动评分是否真的代表回答质量，以及剩余失败来自测试规则、模型还是技术故障。
approach: >-
  先检查测试问题和证据位置，再隐藏模型名称人工审核 60 份答案，并单独处理可复现的工具参数故障。
outcome: >-
  人工审核得到 GPT 28/30、MiniMax 22/30；Hard Pass 改按固定规则与指定证据位置的一致性解释，未增加新的模型自检层。
---

# MiniMax 17/30、GPT 14/30，人工盲审却给出相反结果

<PracticeArticleOverview />

## 背景

同一套 30 个固定问题中，MiniMax-M3 的 Hard Pass 是 `17/30`，GPT-5.5 是 `14/30`。只看自动
分数，MiniMax 多通过 3 个问题。

Hard Pass 会检查回答类型、必需论文、禁止论文、指定证据位置和引用。它适合做工具与提交规则回归，
但“通过率”这个说法容易让人把它理解成回答准确率。

为确认两者是否一致，这轮先检查测试数据，再把 30 组答案打乱并隐藏模型名称。人工标签冻结后才
恢复模型映射。结果变成 GPT-5.5 `28/30`、MiniMax-M3 `22/30`；人工偏好 GPT 24 次，偏好
MiniMax 4 次，另有 2 次平局。

## 要解决的问题

这轮需要回答三个问题：

1. 固定测试问题和指定证据位置是否可靠；
2. Hard Pass 失败是否等同于回答质量失败；
3. MiniMax 的剩余问题应该修测试规则、Harness、模型 API 兼容，还是继续增加提交检查。

如果不把这三层分开，任何分数变化都可能被误读成“检索变好”或“模型变差”。

## 怎么处理

### 先检查测试数据

评估先运行两层不调用模型的校验：

```text
固定测试数据：30/30
指定位置审计：29/29
```

30 个问题都能通过结构校验，29 个指定证据位置都能在正确页码唯一匹配 Reading Model。14 篇论文
的标题和版本也完成来源核对。这能证明测试数据可用，不能证明每份自然语言答案都判断正确。

审计还发现，14 篇论文中有 13 篇只保存了第一位或前两位作者。当前问题不检查完整作者列表，所以
这个缺口没有影响本轮结果，也没有为了提高分数临时修改数据。

### 隐藏模型名称，人工审核 60 份答案

`blind-map.json` 把每个问题的两份答案随机映射为 A 和 B。审核时看不到模型身份。30 个问题共有
60 份答案，每份记录：

- 是否完成用户任务；
- 事实是否有论文依据；
- 回答是否清楚且完整；
- 失败原因和备注。

人工标签先冻结，再恢复模型身份并计算成对结果：

| 指标 | GPT-5.5 对照 | MiniMax-M3 生产基线 |
| --- | ---: | ---: |
| 人工总体通过 | `28/30 = 93.3%` | `22/30 = 73.3%` |
| 事实依据通过 | `28/28 = 100%` | `18/25 = 72.0%` |
| 人工偏好 | `24/30` | `4/30` |
| 只有该模型通过 | `8/30` | `2/30` |

两个模型共同通过 20 个问题，没有共同失败的问题。7 个原本有争议的测试问题也单独审核，最终全部
保留，同时允许人工判断同论文中的等价证据。指定位置命中率和语义质量从此分开报告。

### 把自动评分与人工标签对齐

对齐后的结果如下：

| 模型 | 两边都通过 | 自动失败、人工通过 | 自动通过、人工失败 | 两边都失败 |
| --- | ---: | ---: | ---: | ---: |
| GPT-5.5 | 14 | 14 | 0 | 2 |
| MiniMax-M3 | 14 | 8 | 3 | 5 |

GPT 有 14 份答案没有通过固定规则，人工仍判为合格。MiniMax 有 3 份答案通过固定规则，人工认为任务
没有完成或依据不足。

另一次自动模型审核没有替代人工标签。`gpt-5.6-sol` 按冻结规则复核时，23 个成功返回的问题全部
判为失败，其中包含 8 个人工通过答案；另有 4 个请求超时。它对引用完整性的理解仍与人工口径不同。

## 遇到的问题

### 指定证据位置会拒绝语义正确的替代证据

GPT-5.5 在 `agentboard_progress_metric_001` 中引用了正文里的完整解释，没有引用测试指定的摘要
位置，因此自动失败。`swebench_instance_provenance_001` 也引用了 Benchmark Construction、
Execution-based Validation 和 Evaluation Procedure 中更细的内容，仍因没有命中 Figure 或
Abstract 中的指定位置而失败。

同一篇论文里可能有多段都能支撑答案。指定位置适合检查检索和引用链是否稳定，不能独立判断哪段
解释更完整。

### “禁止额外论文”会拒绝合理的补充来源

`transformer_optimizer_reproduction_001` 中，GPT 先引用 Transformer 论文给出 Adam 参数，又
引用 Adam 原论文解释算法来源。数值没有错，额外来源却触发禁止论文规则。

`coding_benchmark_followup_001` 中，模型选择 SWE-bench，并引用 GAIA 和 WebArena 解释为什么它们
不适合 coding agent，也被自动判为使用了错误论文。

这些规则仍有回归价值，但需要按“是否符合固定测试合同”理解，不能直接替代人工质量判断。

### 自动评分也会放过不完整答案

答案没有输出 `fields` 时，内容评分会变成 `not_run`。部分声明和人工审核条件只进入待审列表，不会
阻止 Hard Pass。模型可能引用了指定位置，却漏掉比较维度、推荐理由或复现步骤。

这解释了 MiniMax 的 3 个“自动通过、人工失败”。命中规定格式和证据位置，仍不保证整份回答完成了
用户任务。

### MiniMax 有一个可复现的工具参数故障

`mint_vs_tau_interaction_comparison_001` 没有返回普通错误答案。模型在 `max_tokens=3000` 处截断
了 `submit_research_answer` 的 JSON 参数。SDK 把参数错误返回工具层后，下一次请求又重放这条非法
tool call，模型随后收到：

```text
HTTP 400: invalid function arguments json string
```

修复放在 Model Adapter。它检测无法解析的工具参数，把本次调用转换为已有的内部继续指令，要求模型
用更短的有效 JSON 重试。Agent 循环、工具定义、主 Prompt、检索和最终提交规则没有修改。

确定性测试覆盖三件事：非法参数会变成有效修复指令，修复指令能返回模型，下一次请求不会再携带
非法 JSON 并能完成提交。

定向复跑的状态从 `FAILED_TECHNICAL` 变成 `COMPLETED`，回答仍只覆盖 interaction design，固定
规则结果仍是 `0/1`。技术故障消失，语义问题没有被算成已解决。

## 为什么没有再加一层模型自检

人工审核确认 MiniMax 还有 7 个事实依据或过度宣称问题。原计划是在最终提交前，把问题、答案草稿
和引用原文再次交给 MiniMax 检查，最多允许一次验证和一次修复。

已有实验给出了成本警告：

```text
提交检查重点问题：0/4 -> 1/4，约 60.9 万 Token
Best-of-2：         18/30 历史参考 -> 16/30，约 343.7 万 Token
```

增加检查会改变模型行动。模型可能为次要声明继续搜索和补证，带来更多等待、Token 和维护成本。
当前也没有真实用户反馈表明这 7 个问题已经形成高频故障。因此模型自检计划没有进入代码，失败清单
继续保留，等待真实问题或模型版本变化再复测。

## 结果

这轮保留了四项改动和判断。

### 重新说明自动严格评分的含义

原始字段没有删除，公开报告将它写成“固定规则与指定证据位置的一致性”。人工总体质量和事实依据
单独报告。旧结果仍能复算，读者也不会把 `17/30` 直接理解为回答准确率。

### 人工报告保持离线

离线报告只读取冻结的人工标签、盲测映射和保存的 `score_report.json`。Harness 不读取这些报告，
也不让人工分数控制检索、会话、进度或回答。

运行时继续只保存原始事实：

```text
<run_id>/events.jsonl
<run_id>/result.json
```

语义评分、找到/读取/引用统计、模型对照和人工标签都在线下计算。

### 工具参数修复留在 API 适配层

被截断的 JSON 是模型 API 与 SDK 重放之间的兼容问题，修复也留在 Model Adapter。代码没有为某个
测试问题加入特殊分支，其他工具调用可以复用同一恢复路径。

### 评估工作在这里停止

固定测试数据继续用于三类工作：修改编排、工具或检索后的回归；复现真实用户问题；MiniMax 版本
升级时做同条件对照。它不再单独驱动一轮“提高测试分数”的开发。

当前基线为：

```text
Python tests：          83/83
Stable Fixture：        15/15
Expanded Fixture：      30/30
Stable Anchor Audit：    7/7
Expanded Anchor Audit： 29/29
MiniMax Human Overall： 22/30
MiniMax Grounding：     18/25
```

这一轮重新界定了自动指标，找出一个模型 API 兼容故障，也给继续增加校验层设定了停止条件。Harness
回到产品研究流程，已有失败数据留作后续回归和模型对照。

## 复现资料

- [`Golden Data 准确性与模型问题审计`](https://github.com/CHZarles/paperloom/blob/main/docs/evaluation/golden-data-validity-and-model-bottleneck-audit-2026-07-14.md)
- [`人工盲审与固定规则对照报告`](https://github.com/CHZarles/paperloom/blob/main/research/golden-data/human-adjudication/2026-07-14/adjudication-report.md)
- [`真实缺陷处置清单`](https://github.com/CHZarles/paperloom/blob/main/research/golden-data/human-adjudication/2026-07-14/DEFECT_DISPOSITION.md)
- [`MiniMax 问题定位基线与停止边界`](https://github.com/CHZarles/paperloom/blob/main/research/golden-data/human-adjudication/2026-07-14/MINIMAX_DIAGNOSTIC_BASELINE.md)
- [`MiniMax 工具参数恢复定向复跑`](https://github.com/CHZarles/paperloom/blob/main/research/golden-data/validation-runs/2026-07-14-minimax-tool-argument-repair-v1/README.md)
- [`检索召回恢复后的排查`](golden-data-harness-evolution.md)
- [`同一个问题运行两次的实验`](best-of-two-agent-orchestration.md)
