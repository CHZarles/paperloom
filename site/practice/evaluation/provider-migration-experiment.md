---
title: 换成 GPT 后，严格评分为什么从 60% 降到 36.7%
description: 在数据、工具、提示词和单路径编排不变的条件下，只更换模型与 API；Responses API 接入成功，但测试模型出现更多预算耗尽和证据错误。
date: 2026-07-14
category: 评估
stage: provider migration
status: 负向结果
result: 36.7%
topics: [Provider, Responses API, 工具调用, 评估]
background: >-
  MiniMax 当前单路径为 17/30，剩余失败既可能来自模型，也可能来自 Harness 的工具、预算和提交规则。
problem: >-
  需要在数据、工具、提示词、检索和编排保持不变时，判断更换模型与 API 能否改善多论文回答、证据选择和最终状态。
approach: >-
  增加 Responses API 适配，先验证最小请求和单个完整工具链，再用相同预算运行 30 个固定问题，比较通过数、失败类型和成本。
outcome: >-
  测试模型得到 11/30，其中 10 个问题耗尽预算；Responses API Adapter 保留，模型没有成为默认模型。
---

# 换成 GPT 后，严格评分为什么从 60% 降到 36.7%

<PracticeArticleOverview />

## 1. 背景、冲突、问题与结论

**背景。** MiniMax 在 30 个固定问题上的当前单路径结果为 `17/30`。剩余失败包括证据位置错误、漏引
论文、最终状态错误和预算耗尽。

**冲突。** Harness 决定模型能看到哪些论文、可以调用哪些工具以及提交需要满足哪些规则。模型决定
检索词、阅读位置、引用内容和最终回答状态。只看最终失败，无法判断限制来自模型还是 Harness。

**问题。** 保持 Prompt、Golden Data、工具、Reading Model、检索和单路径编排不变，只更换模型与 API，
能否改善多论文回答和证据选择？

**结论。** Responses API 接入成功，完整 Agent 工具链可以运行。测试模型最终得到 `11/30`，低于
MiniMax 当前单路径的 `17/30`，并有 10 个问题耗尽预算。API Adapter 保留，测试模型没有成为默认模型。

## 2. 先固定四个概念

| 概念 | 含义 |
| --- | --- |
| Provider | 模型服务、模型标识及其 API 适配 |
| Outcome | `answered`、`partial`、`needs_clarification` 或 `abstained` 等最终状态 |
| Anchor | Golden Data 中预先标记的指定证据位置 |
| Hard Pass | Outcome、指定论文、Anchor 和引用全部通过固定规则 |

本次测试模型是本机 Codex 配置中的 `gpt-5.3-codex-spark`。实验结论只覆盖该模型在当前 Harness、预算
和数据下的表现，不代表所有 GPT 模型或 Responses API 模型。

标题中的 `60%` 来自 MiniMax 历史合并结果 `18/30`。当前单路径 `17/30` 更接近同条件生产基线，后文
以两个数字分别标注历史参考和当前对照。

## 3. 因果比较需要固定哪些变量

本次实验固定：

- 30 个测试问题和指定论文；
- Reading Model 与检索路径；
- Agent Prompt 和工具定义；
- `submit_research_answer` 与证据覆盖检查；
- 单路径运行方式；
- 模型调用、论文工具调用和提交修复上限。

只改变两项：模型 Provider 和请求协议适配。

**论点。** 如果固定项保持一致，结果差异可以用于判断测试 Provider 是否适合当前 Harness。

**论据。** 两种 Provider 共用 Agent、论文工具、Session、提交检查、运行记录和最终结果构建。

**论证。** 固定 Harness 可以减少数据、检索和编排变化的干扰。模型与 API 仍作为一个组合变量，因此
本次实验能够回答“是否适合当前系统”，不能单独测出模型能力或协议影响。

## 4. 决策一：先证明 API 链路可用，再解释全量结果

原有 `MiniMaxAgentsModel` 使用 OpenAI 兼容的 Chat Completions API：

```text
MiniMax
-> /chat/completions
-> OpenAIChatCompletionsModel
-> Agents SDK Runner
```

本机 Codex 配置声明 `wire_api=responses`。只更换模型名称时，Harness 仍会请求 Chat Completions，协议
不会自动切换。为此增加第二条适配路径：

```text
Codex / GPT
-> /responses
-> OpenAIResponsesModel
-> Agents SDK Runner
```

运行时根据 `ProviderConfig.api_style` 选择 Adapter。Adapter 只处理请求和响应格式，不读取论文范围、
测试答案，也不包含问题专用分支。

### 4.1 密钥处理

非敏感配置来自 `~/.codex/config.toml`，API Key 来自 `~/.codex/auth.json`。实验进程读取密钥，但不写入
项目 `.env` 或运行记录。

HTTP 记录继续过滤 `Authorization`、`x-api-key` 和 Cookie，并通过测试检查运行文件不包含 API Key。

### 4.2 三步验证

```text
Responses API 最小请求
-> 单个完整工具链问题
-> 全量 30 个固定问题
```

最小请求返回 `OK`。随后运行 `transformer_adam_params_001`：

```text
status:                  COMPLETED
严格评分:                true
model calls:             6
Corpus tool calls:       3
rejected submissions:    2
total tokens:            21,605
duration:                约 14 秒
```

**论点。** Responses API Adapter 已经接通 Agent 工具链。

**论据。** 单个问题完成了认证、模型请求、论文授权、位置查找、原文读取、答案提交和引用检查。

**论证。** 最小请求排除认证和基础协议错误，完整问题排除“只能对话、不能调用论文工具”的情况。全量
结果下降不能简单归因于 Adapter 完全不可用。

## 5. 论点一：测试 Provider 没有达到切换标准

30 个问题使用相同上限：

```text
sample_count = 1
max_model_calls = 10
max_corpus_tool_calls = 12
max_rejected_final_submissions = 2
```

**论据。** 全量结果为：

| 指标 | GPT-5.3 Codex Spark |
| --- | ---: |
| Hard Pass | `11/30`，`36.7%` |
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

几组参考结果为：

```text
MiniMax 历史合并参考：18/30，60.0%
MiniMax 当前单路径：  17/30，56.7%
MiniMax Best-of-2：   16/30，53.3%
GPT 本次单路径：      11/30，36.7%
```

**论证。** GPT 在旧 15 个问题和新 15 个问题上都低于当前 MiniMax，下降无法只用“新增问题更难”解释。
10 次预算耗尽还表明当前调用上限与该模型的工具使用节奏不匹配。

**结论。** 在当前 Prompt、工具、数据和预算下，测试 Provider 没有达到替换默认模型的标准。

## 6. 论点二：相同调用上限不代表相同研究工作量

**论据。** GPT 有 10 个问题耗尽预算。有的在第 10 次模型调用后仍未形成可接受答案，有的连续三次
提交无效，超过两次修复上限。

不同模型的工具节奏存在差异：

- 有的模型一次安排多个动作，有的每轮只处理一个位置；
- 有的模型收集足够证据后提交，有的很早尝试提交；
- 有的模型在拒绝后修改答案，有的重新开始搜索。

**论证。** `10 / 12 / 2` 限制的是调用次数，不是已完成的研究步骤。相同上限可以控制账单，无法保证
两个模型拥有相同的有效工作量。

直接扩大预算可能减少部分技术失败，也会增加 Token。证据位置错误和 Outcome 错误仍然存在，提高
调用上限无法直接修复两类行为。

**决定。** 本轮不提高预算重跑全量。若以后为 GPT 修改 Prompt、预算或工具策略，需要建立新的调优
实验，不能覆盖本次固定条件的结果。

## 7. 论点三：最终状态需要独立回归

**论据。** 两个旧问题出现明确 Outcome 错误：

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

前一个问题缺少足够论文身份信息，后一个问题在授权论文中没有足够证据。模型仍然组织答案并提交为
`answered`。

**论证。** Harness 可以确定性检查引用编号和论文覆盖，无法通过简单规则判断所有澄清、拒答、部分回答、
话题切换和论文库信息问题。Provider 会改变最终状态选择，状态回归不能只看引用分数。

**决定。** 模型迁移评估继续单独统计 Outcome。`answered`、`needs_clarification` 和 `abstained` 的错误
不会并入普通证据缺失后再忽略。

## 8. 论点四：证据位置选择没有改善

**论据。** GPT 全量错误分布为：

| 错误 | 次数 |
| --- | ---: |
| `REQUIRED_ANCHOR_MISSING` | 32 |
| `REQUIRED_ANCHOR_NOT_CITED` | 32 |
| `REQUIRED_PAPER_MISSING` | 23 |
| `OUTCOME_MISMATCH` | 14 |
| `CITATIONS_REQUIRED` | 12 |
| `FORBIDDEN_PAPER_ACCEPTED` | 1 |
| `FORBIDDEN_ANCHOR_ACCEPTED` | 1 |

常见失败包括漏掉要求使用的论文、选择同论文的另一个相关位置、读取后没有引用，以及在追问中继续使用
旧话题证据。

**论证。** MiniMax 也存在相似错误。更换 Provider 后，Anchor 和论文覆盖计数没有改善，表明当前问题
不能仅靠替换模型解决。

**结论。** 证据选择仍需从候选排序、阅读策略、提交反馈和人工审核分别排查。

## 9. 工程决定：保留 Adapter，不切换默认模型

本次实验分别验证了三层内容：

| 层级 | 结果 |
| --- | --- |
| API 通信 | 请求、认证和响应解析正常 |
| Agent 工具流程 | 能调用论文工具并提交答案，但预算耗尽较多 |
| 产品行为 | Hard Pass、Outcome 和证据选择没有达到切换标准 |

**论点。** API 适配和模型切换需要分开决定。

**论据。** Adapter 已通过最小请求和完整工具链验证；模型全量结果为 `11/30`，并有 10 次预算耗尽。

**论证。** 删除 Adapter 会丢失已经验证的 Responses API 接入能力。把测试模型设为默认模型，会接受
当前评分和稳定性下降。两项代码具有不同价值，应该分别保留或停止。

**决定。** `OpenAIResponsesModel` Adapter 保留并复用现有 `AgentsSdkHarnessRuntime`。
`gpt-5.3-codex-spark` 不成为默认模型。

## 10. 证据边界

**能够支持的结论。** Responses API 可以接入当前 Agent 工具链；测试模型在固定 Harness 和预算下低于
MiniMax 当前单路径；相同调用次数没有提供相同研究工作量。

**尚不能支持的结论。** `11/30` 不能证明测试模型在所有任务上弱于 MiniMax，也不能把差异全部归因于
模型本身。模型专用 Prompt、预算或工具策略可能改变结果，需要新的对照实验。

另一轮 GPT-5.5 人工盲审表明，Hard Pass 也不能直接代表回答的语义质量。该结果不改变本文关于 API
接入、预算耗尽和默认模型切换的决定，详见
[`MiniMax 17/30、GPT 14/30，人工盲审却给出相反结果`](hard-pass-human-review-stopping-rule.md)。

## 11. 复现资料

公开记录保留配置边界、聚合指标和失败分类，不包含模型凭据或完整 HTTP 请求头。

相关代码与资料：

- `harness_py/orchestration/agents/model.py`
- `harness_py/orchestration/agents/runtime.py`
- `harness_py/tests/test_agents_model.py`
- [`同一个问题运行两次的实验`](best-of-two-agent-orchestration.md)
- [`检索召回恢复后的排查`](golden-data-harness-evolution.md)
