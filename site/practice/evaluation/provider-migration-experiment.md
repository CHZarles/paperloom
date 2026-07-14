---
title: 换成 GPT 之后，Research Harness 为什么反而从 60% 降到了 36.7%
description: 一次 Responses API Provider 迁移，以及模型、工具协议、预算和 Outcome 行为差异的复盘。
date: 2026-07-14
stage: provider migration
status: negative result
topics: [Provider, Responses API, Function Tools, Evaluation]
---

# 换成 GPT 之后，Research Harness 为什么反而从 60% 降到了 36.7%

> 当前状态：Responses API 适配边界被保留，但本文测试的模型没有成为默认 Provider。

Best-of-2 没有提高通过率以后，我把变量收窄到 Provider。此前实验主要使用 MiniMax-M3，这次要测的
问题是：保持 Harness 契约不变，GPT 系列模型能否改善多论文理解、Evidence Selection 和最终提交。

我选择了本机 Codex 配置中可用的 `gpt-5.3-codex-spark`。没有修改 Prompt、Golden Data、工具
Schema、Reading Model 或检索路径，只增加 Responses API 适配，然后用单 Sample 跑完 30 个 Case。

最终结果是：

```text
MiniMax 历史合并参考：     18/30，60.0%
MiniMax 当前单路径：       17/30，56.7%
MiniMax Best-of-2：        16/30，53.3%
GPT-5.3 Codex Spark：      11/30，36.7%
```

GPT 使用了更新的接口，也没有让这套 Harness 得到更高分数。

`18/30` 是历史 MiniMax 合并参考。旧 15 Case
由两组既有报告合并，后续全量结果还替换了单独重跑的技术失败 Case。因此它适合提供历史尺度，
不能当作与 GPT 本次运行严格同条件的 single-path baseline。

后续删除 Best-of-2 并恢复当前单路径 Runtime 后，MiniMax 在当前代码上得到 `17/30`。两次运行并非
同一供应商窗口的严格配对实验，`17/30` 仍比历史合并参考更接近当前生产路径。

这次实验完成了 Responses API 适配，也测出了 Provider 切换需要一起回归的内容：工具节奏、预算、
停止判断、Outcome 和 Evidence Selection。

## 为什么值得做这个对照

在 MiniMax 全量结果中，主要失败集中在：

- Required Anchor 没有进入最终 Evidence；
- 读到了同论文的其他位置，却没有选择 canonical Anchor；
- 多论文答案缺少其中一篇的引用；
- Coverage 修复消耗过多模型调用。

这些现象既可能来自 Harness，也可能来自模型本身：

```text
Harness 提供候选和约束
模型决定搜索词、阅读位置、引用和 outcome
```

相同工具、数据和 Prompt 下的结果，可以帮助我判断下一步应该做 Provider 选择，还是继续检查现有
Harness 契约。

这次使用单 Sample，避免把并行采样的影响混入模型比较。

## 如何安全复用本机 Codex 配置

本机 `~/.codex/config.toml` 提供了 Provider 的非敏感配置：

```toml
[model_providers.codex]
base_url = "..."
wire_api = "responses"
requires_openai_auth = true
```

API Key 来自 `~/.codex/auth.json`。实验代码只在进程内读取它，并遵守三个边界：

1. 不把 Key 写进项目配置；
2. 不把 Key 打印到终端结果；
3. Eval HTTP 记录继续过滤 `Authorization`、`x-api-key` 和 Cookie。

正式运行前，我先用原生 `AsyncOpenAI.responses.create` 做最小连通性测试：

```text
model:  gpt-5.3-codex-spark
input:  Reply with exactly OK.
status: completed
output: OK
```

这个测试只证明模型、Key、Base URL 和 Responses Endpoint 可用，不证明 Agent 工具协议可用。

## Harness 原来只支持 Chat Completions

原有 `MiniMaxAgentsModel` 继承 Agents SDK 的 `OpenAIChatCompletionsModel`：

```text
MiniMax Provider
-> OpenAI-compatible /chat/completions
-> OpenAIChatCompletionsModel
-> Agents SDK Runner
```

Codex Provider 在配置中声明的是 `wire_api=responses`。只替换 model 字符串时，Harness 仍会请求
Chat Completions Endpoint，协议并没有切换。

因此新增了第二条适配路径：

```text
Codex/GPT Provider
-> /responses
-> OpenAIResponsesModel
-> Agents SDK Runner
```

运行时根据 `ProviderConfig.api_style` 选择：

```text
responses / openai-responses
    -> OpenAIResponsesAgentsModel
其他 OpenAI-compatible Provider
    -> MiniMaxAgentsModel
```

两种 Model 共享以下逻辑：

- HTTP request/response 原始事实记录；
- Authorization Header 脱敏；
- ContextVar 绑定当前 Sample；
- 纯文本响应转换为内部 continue tool；
- Client 生命周期关闭；
- 最终答案必须通过 `submit_research_answer`。

Provider Adapter 只负责协议差异，不拥有 Corpus、Evidence Coverage 或 Golden 逻辑。我没有在
Model Adapter 中加入 Anchor 或 Case 分支，避免 Provider 层带入业务语义。

## Responses API 与工具循环跑通了

完整 Golden 前先运行 `transformer_adam_params_001` smoke case。

结果：

```text
status:                  COMPLETED
Hard Pass:               true
model calls:             6
Corpus tool calls:       3
rejected submissions:    2
total tokens:            21,605
duration:                约 14 秒
```

Smoke Case 经过了下面这条完整链路：

```text
Responses API
-> Function Tool Call
-> Corpus 授权
-> find_reading_locations
-> read_locations
-> submit_research_answer
-> Evidence/Citation Validation
-> 标准 Harness Run
```

Harness 因此有了第二种模型协议。Responses API Provider 复用了原有 Runtime、Tools 和 Context。

## 全量结果：模型换成功了，效果没有

使用以下边界运行 30 个 Golden Case：

```text
sample_count = 1
max_model_calls = 10
max_corpus_tool_calls = 12
max_rejected_final_submissions = 2
```

全量结果：

| 指标 | GPT-5.3 Codex Spark |
| --- | ---: |
| Hard Pass | `11/30`，`36.7%` |
| Stable Pack | `8/15`，`53.3%` |
| New Pack | `3/15`，`20.0%` |
| 技术失败 | `11/30` |
| Budget Exhausted | `10/30` |
| 连接错误 | `1` |
| 模型响应 | `160` |
| Corpus 工具调用 | `192` |
| Prompt Token | `1,626,871` |
| Completion Token | `88,793` |
| Total Token | `1,715,664` |
| 墙钟时间 | 约 `13.9` 分钟 |

通过的 11 个 Case 是：

```text
transformer_adam_params_001
bert_transformer_role_001
transformer_to_bert_genealogy_001
adam_beta2_conflict_001
gpt3_to_transformer_multihop_001
adam_constraint_refinement_followup_001
corpus_paper_count_001
corpus_paper_list_followup_001
agentbench_environment_inventory_001
gaia_dataset_gap_001
toolsandbox_constraint_selection_001
```

与 MiniMax 历史合并参考 `18/30` 相比，GPT 少通过 7 个 Case。Stable Pack 从 `12/15` 降到 `8/15`，
New Pack 从 `6/15` 降到 `3/15`。

回归同时出现在 New Pack 和旧的 Stable Pack，不能只归因于新增 Case 难度。

## 同一预算在两个模型上含义不同

GPT 运行中 10 个 Sample 因预算耗尽失败。主要有两种路径：

```text
模型调用达到 10 次仍未形成可接受答案
```

或者：

```text
第三次无效提交
-> 超过两次修复预算
-> Sample 失败
```

例如 `react_to_agent_evaluation_genealogy_001` 连续三次触发 Evidence Coverage 拒绝；
`agentboard_progress_metric_001` 多次提交没有引用的内容答案。

同样的 `10 / 12 / 2` 对 MiniMax 和 GPT 并不代表相同的研究容量。模型调用工具的节奏不同：有的
模型一次并行规划多个动作，有的模型每轮只推进一个位置；有的先收集足够证据再提交，有的更早
试交答案。

模型预算需要按 Provider 校准，同一个数字不会自动提供相同的研究容量。

但这不意味着应该立即扩大 GPT 预算。因为即使排除技术失败，仍然存在明显的 Outcome 和 Anchor
错误。扩大预算只可能减少部分 Technical Failure，不能自动解决这些行为差异。

## GPT 更容易提前选择 answered

两个稳定 Case 展示了明显的 Outcome 回归：

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

GPT 能从现有论文中组织出流畅答案，但这两个 Case 的对象存在歧义，或 Corpus 没有足够证据支持
结论。模型仍然选择了 `answered`。

Harness 当前对 citation 和 paper coverage 有确定性校验，却不能仅靠规则判断所有
“应该回答还是应该拒答”的语义边界。Outcome 仍然高度依赖模型对 Prompt 和上下文的理解。

这也是为什么替换模型不能只检查工具调用成功率，还必须回归：

- clarification；
- abstention；
- partial answer；
- topic change；
- forbidden paper；
- corpus metadata question。

## Anchor Selection 仍是主要失败

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

MiniMax Best-of-2 的主要错误同样是 Anchor Missing 和 Anchor Not Cited。换 GPT 后，这两个指标没有
下降，反而从各 26 次增加到各 32 次。

换到 GPT 后，下面几类错误仍然出现：

- 没有选择 required paper；
- 选择同论文中的另一个相关位置；
- 读取证据后不在最终答案中引用；
- 在 Follow-up 中继续使用旧主题 Evidence。

模型能力不能替代清晰的 evidence selection contract。

## Transport 通过后还要测产品行为

从基础设施角度，这次迁移是成功的：

```text
Responses Endpoint 可用
工具 Schema 可用
Function Call 可用
Token Usage 可记录
Evidence Validation 可执行
Eval 数据不重不漏
```

Smoke Test 只能覆盖前两层的一部分。一个 Provider Adapter 需要经过三层验收：

| 层级 | 问题 |
| --- | --- |
| Transport | 请求能否发送、认证和解析？ |
| Agent Protocol | 模型能否稳定调用工具并完成提交？ |
| Product Behavior | Outcome、Evidence、引用和会话行为是否正确？ |

这次 Transport 与部分 Agent Protocol 通过，Product Behavior 没有达到切换默认模型的标准。

## Provider 差异留在 Model Adapter

我没有为了 Responses API 改 `HarnessRuntime` 公共接口，也没有复制 Agent Loop。

```text
AgentsSdkHarnessRuntime
-> provider_agents_model(provider)
   -> Chat Completions Adapter
   -> Responses Adapter
```

Agent、Tools、Context、Session、`evaluate_evidence_coverage` 和 Run Builder 全部复用。SDK 继续处理通用循环，
Harness 继续处理领域规则。

如果接入第二个 Provider 就需要复制 Runtime，通常意味着第一版 Provider 细节已经泄漏到了业务
编排层。

## 本机凭据没有进入项目配置

实验参考 `~/.codex` 配置，但没有把个人凭据转换成项目 `.env`，也没有新增明文配置。

读取本机凭据只用于这次本地实验；生产 Provider 仍由项目自己的配置源管理。实验性的认证方式没有
固化成产品依赖。

同时，HTTP Eval Hook 的脱敏测试继续生效。新增 Responses Adapter 后仍然检查记录文件中不包含
测试 API Key。

## 从最小请求逐层扩大测试

如果直接跑 30 Case，Responses Schema、Tool Call 或认证配置中的一个小错误都可能浪费大量调用。

这次采用：

```text
原生 Responses 最小请求
-> 单 Golden Smoke Case
-> 全量 30 Case
```

每一层只证明一件事：

- 最小请求证明 Endpoint 与模型可用；
- Smoke 证明 Harness 工具链可用；
- 全量证明产品行为质量。

后续 Provider 实验会继续沿用这个顺序。

## 这次实验排除了直接换 GPT

在没有数据之前，“换更强模型”很容易成为一个无法证伪的建议：效果不好，可以说预算不够；预算
扩大后仍不好，可以说 Prompt 没调；Prompt 调完又可以说 Sampling 参数不对。

这次实验刻意保持 Prompt、Golden、检索和 Reading Model 不变，只替换 Provider 协议和模型。
因此它回答了一个明确问题：

> 在当前 Harness 契约下，直接把 MiniMax 换成 GPT-5.3 Codex Spark，是否能提高通过率？

本次答案是不能，Hard Pass 从当前 MiniMax 单路径的 `17/30` 降到了 `11/30`。

后续若为 GPT 单独修改 Prompt、预算或工具策略，我会把它定义为新的 Provider 调优实验。本次实验的
问题和变量已经固定，不会用后续调参改写这次结论。

## 当前结论

`gpt-5.3-codex-spark` 不适合作为当前 Research Harness 的直接默认替换：

```text
通过率低于 MiniMax；
Stable Pack 明显回归；
预算耗尽频繁；
Outcome 边界更差；
Anchor Selection 没有改善。
```

Responses API Adapter 可以保留。它已经形成一个干净的 Provider 扩展点，后续可以测试其他 GPT
模型，而不再修改主编排。

接下来优先做四项分析：

1. 对失败 Case 做 Candidate -> Read -> Cited 离线分层；
2. Human Label 判断替代 Evidence 是否语义有效；
3. 区分 Golden Anchor 过窄和模型 Evidence Selection 错误；
4. 修复 Outcome 与 Topic Change 这类模型无关的产品约束缺口。

Responses API Adapter 会保留，因为它已经通过真实工具链。`gpt-5.3-codex-spark` 不会成为当前默认
模型，因为它在同一套 Golden Data 上出现了通过率、Outcome 和预算回归。后续工作会从具体失败
类型出发，不再按模型名称猜测效果。

## 复现资料

Smoke Test 和全量测试的原始运行目录保留在实验环境中。公开记录只保留可复核的配置边界、聚合
指标与失败分类，不包含 Provider 凭据或完整 HTTP 请求头。

相关代码与文档：

- `harness_py/orchestration/agents/model.py`
- `harness_py/orchestration/agents/runtime.py`
- `harness_py/tests/test_agents_model.py`
- [`Best-of-2 Agent 编排实验`](best-of-two-agent-orchestration.md)
- [`Golden Data 与 Harness 演化`](golden-data-harness-evolution.md)
