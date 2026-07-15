---
title: 换成 GPT 后，严格评分为什么从 60% 降到 36.7%
description: 保持数据、工具和提示词不变，只更换模型与 API；通过数下降，预算耗尽增多。
date: 2026-07-14
category: 评估
stage: provider migration
status: 负向结果
result: 36.7%
topics: [Provider, Responses API, 工具调用, 评估]
background: >-
  MiniMax 的剩余失败既可能来自模型，也可能来自现有 Harness 的工具和提交规则。
problem: >-
  在数据、工具、提示词和检索都不变的条件下，换成 GPT 能否改善多论文回答和证据选择。
approach: >-
  只增加 Responses API 适配，用单路径运行相同的 30 个测试问题，并比较通过数、失败类型和成本。
outcome: >-
  测试模型的严格评分为 11/30，其中 10 个问题耗尽预算；API 适配保留，模型没有成为默认模型。
---

# 换成 GPT 后，严格评分为什么从 60% 降到 36.7%

> 当前状态：Responses API 适配代码保留，本文测试的模型没有成为默认模型。

<PracticeArticleOverview />

## 背景

MiniMax 在 30 个固定问题上的当前单路径结果是 `17/30`。剩余失败既可能来自模型，也可能来自
Harness 的工具说明、调用预算、提交检查或证据选择方式。

为了缩小范围，这次实验只更换模型和 API。Prompt、测试数据、工具定义、Reading Model、检索路径
和单路径运行方式都保持不变。测试模型是本机 Codex 配置中的 `gpt-5.3-codex-spark`。

文中的 Provider 指模型服务及其 API 适配。Outcome 指回答、部分回答、请求澄清或拒答等最终状态。
Anchor 指固定测试数据中预先标记的证据位置。

## 要解决的问题

MiniMax 的主要失败有四类：

- 指定证据位置没有进入最终引用；
- 读取了同论文的其他位置，却没有选择测试指定的位置；
- 多论文答案缺少其中一篇的引用；
- 为补齐引用消耗过多模型调用。

Harness 决定模型能看到哪些论文、能调用哪些工具以及提交需要满足哪些规则。模型决定检索词、阅读
位置、引用内容和最终回答状态。只替换模型，可以观察这些失败是否主要来自模型本身。

## 怎么处理

### 增加 Responses API 适配

原有 `MiniMaxAgentsModel` 使用 OpenAI 兼容的 Chat Completions API：

```text
MiniMax
-> /chat/completions
-> OpenAIChatCompletionsModel
-> Agents SDK Runner
```

本机 Codex 配置声明 `wire_api=responses`。只修改模型名称，Harness 仍会请求 Chat Completions，
协议不会自动切换。因此新增第二条适配路径：

```text
Codex / GPT
-> /responses
-> OpenAIResponsesModel
-> Agents SDK Runner
```

运行时根据 `ProviderConfig.api_style` 选择适配器。两种模型 API 共用 Agent、论文工具、Session、
提交检查、运行记录和最终结果构建。Model Adapter 只处理请求与响应格式，不处理论文范围、测试答案
或某个问题的特殊分支。

### 本机密钥不进入仓库

非敏感配置来自 `~/.codex/config.toml`，API Key 来自 `~/.codex/auth.json`。实验代码只在当前进程
读取密钥，没有写入项目 `.env` 或运行记录。HTTP 记录继续过滤 `Authorization`、`x-api-key` 和
Cookie，并有测试检查记录中不包含 API Key。

### 从最小请求逐步扩大测试

测试分三步进行：

```text
Responses API 最小请求
-> 单个工具链问题
-> 全量 30 个固定问题
```

最小请求只检查模型、认证、Base URL 和 API 是否可用。单个问题检查模型能否调用论文工具并完成
提交。全量测试再观察回答行为和成本。

最小请求返回 `OK`。随后用 `transformer_adam_params_001` 跑完整工具链：

```text
status:                  COMPLETED
严格评分:                true
model calls:             6
Corpus tool calls:       3
rejected submissions:    2
total tokens:            21,605
duration:                约 14 秒
```

这次运行经过 Responses API、工具调用、论文授权、位置查找、原文读取、答案提交和引用检查，说明
第二种模型 API 已经接通。

## 遇到的问题

### 全量结果低于 MiniMax

30 个问题使用相同上限：

```text
sample_count = 1
max_model_calls = 10
max_corpus_tool_calls = 12
max_rejected_final_submissions = 2
```

结果如下：

| 指标 | GPT-5.3 Codex Spark |
| --- | ---: |
| 严格评分通过 | `11/30`，`36.7%` |
| 旧 15 个问题 | `8/15`，`53.3%` |
| 新 15 个问题 | `3/15`，`20.0%` |
| 技术失败 | `11/30` |
| 预算耗尽 | `10/30` |
| 连接错误 | `1` |
| 模型响应 | `160` |
| 论文工具调用 | `192` |
| Prompt Token | `1,626,871` |
| Completion Token | `88,793` |
| Total Token | `1,715,664` |
| 总等待时间 | 约 `13.9` 分钟 |

几组可比较结果是：

```text
MiniMax 历史合并参考：18/30，60.0%
MiniMax 当前单路径：  17/30，56.7%
MiniMax Best-of-2：   16/30，53.3%
GPT 本次单路径：      11/30，36.7%
```

历史 `18/30` 来自多次结果合并，只提供大致尺度。当前单路径的 `17/30` 更接近生产运行方式。GPT 在
新旧两组问题上都下降，无法只归因于新增问题更难。

### 相同调用上限，不代表相同工作量

GPT 有 10 个问题耗尽预算。有的在第 10 次模型调用后仍没有形成可接受答案；有的连续三次提交无效，
超过两次修复上限。

不同模型调用工具的节奏不同。有的模型一次安排多个动作，有的每轮只处理一个位置；有的收集足够
证据后再提交，有的很早就尝试提交。同样的 `10 / 12 / 2`，能完成的研究步骤并不相同。

直接扩大预算只能减少部分技术失败。下面两类回答错误仍然存在，因此没有继续用更高预算重跑全量。

### 模型更早选择“已回答”

两个旧问题出现了明确的最终状态错误：

```text
attention_paper_ambiguous_001
expected: needs_clarification
actual:   answered
```

```text
gpt5_architecture_boundary_001
expected: abstained
actual:   answered
```

前一个问题需要用户补充所指论文，后一个问题在授权论文中没有足够证据。模型仍然组织出流畅答案并
提交为 `answered`。

Harness 能确定性检查引用编号和论文覆盖，无法仅靠固定规则判断所有澄清、拒答、部分回答、话题
切换和论文库信息问题。更换模型时，这些最终状态也需要单独回归。

### 证据位置选择没有改善

GPT 全量错误分布：

| 错误 | 次数 |
| --- | ---: |
| `REQUIRED_ANCHOR_MISSING` | 32 |
| `REQUIRED_ANCHOR_NOT_CITED` | 32 |
| `REQUIRED_PAPER_MISSING` | 23 |
| `OUTCOME_MISMATCH` | 14 |
| `CITATIONS_REQUIRED` | 12 |
| `FORBIDDEN_PAPER_ACCEPTED` | 1 |
| `FORBIDDEN_ANCHOR_ACCEPTED` | 1 |

常见情况包括漏掉要求使用的论文、选择同论文的另一个相关位置、读取后没有引用，以及在追问中继续
使用旧话题的证据。MiniMax 也有相似问题，GPT 没有改善这些指标。

## 结果

这次实验分别验证了三层内容：

| 层级 | 结果 |
| --- | --- |
| API 通信 | 请求、认证和响应解析正常 |
| Agent 工具流程 | 能调用论文工具并完成提交，但预算耗尽较多 |
| 产品行为 | 严格评分、最终状态和证据选择没有达到切换标准 |

Responses API Adapter 已保留。它复用了现有 `AgentsSdkHarnessRuntime`，以后测试其他 Responses API
模型时，不需要复制 Agent 工具循环。

`gpt-5.3-codex-spark` 没有成为默认模型。在当前 Prompt、工具、数据和预算下，它的严格评分从
MiniMax 当前单路径的 `17/30` 降到 `11/30`，并出现 10 次预算耗尽。后续若为 GPT 单独修改 Prompt、
预算或工具策略，应作为新的调优实验，不能用来改写本次结论。

另一轮 GPT-5.5 人工盲审表明，严格评分也不能直接代表回答的语义质量。那项结果不改变本文关于 API
接入和预算的结论，详见
[`MiniMax 17/30、GPT 14/30，人工盲审却给出相反结果`](hard-pass-human-review-stopping-rule.md)。

## 复现资料

公开记录保留配置边界、聚合指标和失败分类，不包含模型凭据或完整 HTTP 请求头。

相关代码与资料：

- `harness_py/orchestration/agents/model.py`
- `harness_py/orchestration/agents/runtime.py`
- `harness_py/tests/test_agents_model.py`
- [`同一个问题运行两次的实验`](best-of-two-agent-orchestration.md)
- [`检索召回恢复后的排查`](golden-data-harness-evolution.md)
