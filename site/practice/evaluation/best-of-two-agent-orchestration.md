---
title: 我让 Agent 同时跑两遍，结果通过率从 60% 降到了 53.3%
description: 一次 Best-of-2 研究编排实验，以及候选上限、Selector regret、并发隔离和 Token 成本的复盘。
date: 2026-07-14
stage: orchestration
status: negative result
topics: [Best-of-N, Agent Orchestration, Selector, Cost]
---

# 我让 Agent 同时跑两遍，结果通过率从 60% 降到了 53.3%

> 当前状态：这个实验没有进入默认运行时。PaperLoom 现在每个研究回合使用一个 SDK Runner；
> 本文保留的是停止该方向的证据与工程经验。

前一轮单路径实验里，同一个 Research Agent 会带着越来越长的上下文反复修补答案。我想测一件很
具体的事：把预算分给两条隔离路径，再从组内选一个答案，能否用更少的串行修复换来更高通过率。

实现借用了 GRPO 的组内比较思路，但没有训练模型或更新参数。同一个问题交给两个隔离的 MiniMax
Agent Sample 并行执行，每个 Sample 有固定预算，结果由一个不读取 Golden Data 的确定性 Selector
选择。

实验前的假设是两条路径能覆盖不同证据，同时降低墙钟延迟。全量 30 个 Golden Case 跑完后得到：

```text
历史 MiniMax 合并参考：18/30，60.0%
Best-of-2：    16/30，53.3%
Oracle pass@2：17/30，56.7%
Token：        3,437,297
```

它没有提高通过率，成本却非常高。

这里的 `18/30` 来自历史合并参考：旧 15 Case 使用此前 `10/12 + 3/3`
两组报告合并；Reading Model v2 阶段又将全量运行中的两个技术失败单独重跑，并与最终聚焦结果
合并。它适合作为历史参考上限，不能当作与当前代码严格同条件的 single-run baseline。

实验还把三个问题拆开了：模型有没有产生更好的候选，Selector 有没有选对，以及 Golden 失败发生在
论文覆盖还是 canonical evidence selection。并发运行同时暴露了记账、取消和状态隔离错误。

## 实验假设

在前一轮 Evidence Coverage 改造中，我给 `submit_research_answer` 增加了确定性覆盖门。模型
讨论某篇论文，却没有读取或引用同论文证据时，最终提交会被拒绝，Agent 必须继续研究或删除无关
声明。

这条规则确实改变了模型行为。四个焦点 Case 的 Hard Pass 从 `0/4` 变成 `1/4`，多论文答案也
不再轻易漏掉整篇论文的引用。

问题是成本失控。四个 Case 共使用：

| 指标 | 串行 Coverage 修复 |
| --- | ---: |
| 模型调用 | 约 45 次 |
| 工具调用 | 48 次 |
| Token | 约 608,855 |
| 墙钟时间 | 约 6.2 分钟 |

其中 `react_to_agent_evaluation_genealogy_001` 单独经历 19 次模型调用、26 次工具调用和 7 次
被拒提交，消耗约 36.4 万 Token 才通过。

串行修复会继承此前越来越长的历史。模型容易围绕第一个“看起来对的”位置继续打磨，很少重新
探索；Coverage 反馈还可能诱导它补读不重要的论文，让上下文进一步膨胀。

我把成本结构改成：

```text
两条短而独立的路径
-> 每条最多少量修复
-> 组内择优
```

要让方案成立，两条短路径带来的新候选必须抵消重复上下文的成本。

## 实际实现

生产 Provider 默认创建两个并行 Sample。它们共享只读输入，但不共享任何可变研究状态：

```text
TurnExecutionInput
├── Sample 0
│   ├── ResearchRunContext
│   ├── ReadingCorpusTools
│   ├── RequestBackedSession
│   └── MiniMaxAgentsModel
└── Sample 1
    ├── ResearchRunContext
    ├── ReadingCorpusTools
    ├── RequestBackedSession
    └── MiniMaxAgentsModel
```

每个 Sample 独立维护：

- paper 和 location 授权；
- Evidence ID 与 Evidence Ledger；
- SDK Session 与工具轨迹；
- 模型调用、工具调用、Token 和提交拒绝次数；
- Eval operation ID 命名空间。

最终配置的单 Sample 硬预算是：

```text
max_model_calls = 10
max_corpus_tool_calls = 12
max_rejected_final_submissions = 2
```

一个 Sample 失败不会取消另一个；只有用户取消才会结束整组任务。两个 Sample 都通过最终提交门
时，Selector 按以下字典序比较：

1. 当前问题明确要求的论文是否都有同论文实质引用；
2. 数值声明能否在引用 span 中找到对应数值；
3. 是否讨论了当前问题没有要求的额外论文；
4. 被拒提交、模型调用、工具调用和 Token 是否更少；
5. 完全相同时使用稳定的 Sample Index 决胜。

Selector 不读取 Case ID、Golden Anchor、Expected Fact、Human Label 或 Judge 分数，也不把两个
Draft 拼成第三个答案。每个成功 Sample 的完整标准 Run 都保存在 `sample.completed` 原始事件中，
因此线上只发布一个答案，线下仍能计算真实的 Oracle pass@2。

## 5 次模型调用到不了终点

第一版预算是：

```text
5 model / 8 tool / 1 rejected submission
```

四个焦点 Case 中，有三个因为预算耗尽成为技术失败。把模型预算提高到 7 次后，仍然有三个技术
失败。

原因并不神秘。多论文研究至少要完成这些动作：

```text
识别论文
-> 为多篇论文分别定位内容
-> 读取多个位置
-> 组织带引用的答案
-> 处理一次协议或 Coverage 修复
```

模型一次只推进一部分工具链时，5 次调用会在形成可提交答案前耗尽。8 次工具调用对四论文推荐也
过紧。

预算需要覆盖工具协议的最小深度、论文数量和一次合理修复，同时限制重复搜索与反复提交。只按
期望成本压上限，会稳定地产生技术失败。

最终我把预算调整为 `10 / 12 / 2`。它仍比此前单路径 19 次模型调用、26 次工具调用和 7 次
拒绝提交的极端情况更可控，但不再假装复杂研究能在五步内完成。

## 两个 Sample 的候选池只到 17/30

全量 MiniMax-M3 测试的结果如下：

| 指标 | 结果 |
| --- | ---: |
| Selected Hard Pass | `16/30`，`53.3%` |
| Stable Pack | `13/15`，`86.7%` |
| New Pack | `3/15`，`20.0%` |
| Oracle pass@2 | `17/30`，`56.7%` |
| 技术失败 Case | `5/30` |
| Sample budget exhausted | `16/60` |

两个固定 Sample 单独得分为：

```text
Sample 0：13/30
Sample 1：14/30
两者 Oracle：17/30
Selector：16/30
```

两个 Sample 有少量互补：Sample 0 独有 3 个通过 Case，Sample 1 独有 4 个，两者共同通过 10 个。
即使每次都选中通过的 Sample，上限也只有 `17/30`。

`17/30` 仍低于此前历史合并参考的 `18/30`。Selector 确实少选对一个 Case，两个 Sample 生成的
候选池本身也没有超过旧策略。

Best-of-N 需要采样分布覆盖不同且质量更高的路径。这次两个 Sample 使用了相近检索词，也围绕相近
候选 Anchor 组织答案。增加 N 主要增加了调用次数，有效探索并没有同步增加。

## Selector 无法识别 Golden 的 canonical Anchor

线上 Selector 不读取 Golden Data，这也决定了它能看到什么：

- 是否覆盖用户明确要求的论文；
- 是否有同论文引用；
- 引用是不是实质正文；
- 数值是否出现在引用证据中；
- 是否过度研究；
- 哪个 Sample 更便宜、更稳定。

它看不到“这条语义合理的证据是不是 Golden 指定的 canonical Anchor”。

全量失败中最主要的错误是：

| 错误 | 次数 |
| --- | ---: |
| `REQUIRED_ANCHOR_MISSING` | 26 |
| `REQUIRED_ANCHOR_NOT_CITED` | 26 |
| `REQUIRED_PAPER_MISSING` | 8 |
| `OUTCOME_MISMATCH` | 6 |
| `CITATIONS_REQUIRED` | 5 |

错误分布集中在 canonical evidence selection。模型经常已经读取同一篇论文，却没有在多个语义相关
位置中选中评分要求的 Anchor。

例如 Cross-benchmark Case 的选中答案同时读取并引用了 GAIA 和 WebArena，也正确报告了两篇
论文的人机差距，但仍因为没有命中 WebArena authored Anchor 而失败。Selector 能确认两篇论文
都有实质引用，却没有合法的产品信号判断哪个 WebArena span 更接近 Golden Anchor。

唯一一次 Selector regret 出现在 `toolsandbox_constraint_selection_001`：两个 Sample 中有一个
能 Hard Pass，Selector 选了另一个。这个问题可以继续改进通用 Selector；但即使修好这一次
regret，结果也只是 `17/30`，仍没有超过旧基线。

这组数据给出的上限很明确：Selector 可以减少候选浪费，无法创造 Sample 没有生成的正确证据。

## 墙钟时间下降，Token 没有下降

这次全量运行用了约 19.1 分钟。两个 Sample 并行后，单 Case 的墙钟时间接近较慢 Sample 的耗时，
没有把两条路径串行相加。这部分符合预期。

但供应商成本按两条路径的总用量计算：

| 成本指标 | 全量结果 |
| --- | ---: |
| 模型 HTTP 响应 | `364` |
| Corpus 工具调用 | `325` |
| Prompt Token | `3,290,152` |
| Completion Token | `147,145` |
| Total Token | `3,437,297` |

Prompt Token 占绝大多数。研究 Agent 的每一步都会携带问题、历史、工具定义以及越来越长的工具
结果；并行两条路径意味着这部分上下文成本几乎完整支付两次。

这批数据只能支持下面这句成本判断：

```text
并行可能更快，但通常不会更省 Token。
```

它只有在减少大量串行修复、并显著提高成功率时，才可能在“每个 Hard Pass 的成本”上更划算。
本次实验没有达到这个条件。

## 这次实现保留下来的部分

总分下降以后，我仍保留了几项可以复用的工程结果。

### 同时保存 Selected 与 Unselected Run

线上只返回一个答案，但两个 Sample 的完整 Run 都保存下来。这样可以同时计算：

```text
pass@1
oracle pass@2
selected pass@2
selector regret
```

如果只保存胜出答案，`16/30` 无法区分生成失败和选择失败。完整 Run 让我看到候选池上限是
`17/30`，Selector 额外损失了 1 个 Case。

### 在对象创建时隔离并发状态

两个 Sample 各自创建 `ResearchRunContext`、Corpus 授权、Evidence、Session 和 Model Client，只共享
只读 Dataset 与请求输入。

这让并发测试可以验证：Sample 1 不会看到 Sample 0 授权的位置或生成的 Evidence ID。相比在共享
Context 上加锁，这种隔离更简单，也更符合 Harness 的运行模型。

### Eval 继续只保存事实

运行时没有新增 Reward 数据库、在线 Judge 或实验平台。`EvalRecorder` 仍然只写：

```text
<run_id>/events.jsonl
<run_id>/result.json
```

新增的只是 `sample.started/completed/failed/selection` 原始事件。Hard Pass、Oracle、regret、成本
和错误分布都从这些事实离线计算。

Harness 在线负责编排和可靠落盘，Hard Pass、Oracle、regret 与成本继续在线下计算。

### 全量运行找出了记账错误

第一版实现只把胜出 Sample 的 Token 写入最终 Run，会少记失败或未选中 Sample 的真实成本。修复
后，成功和失败 Sample 的用量都会汇总。

全量运行又发现一个更隐蔽的边界：两个 Sample 都失败时，Runtime 抛出技术异常，外层生成新的
Technical Failure Run，原来的 Sample 用量没有被带过去。

原始 `model.response` 事件不重不漏，仍能还原 343.7 万 Token。随后我增加了带
diagnostics 的 `HarnessExecutionFailed`，让全部失败时的模型调用、工具调用和 Token 也进入最终
响应，并补了回归测试。

此外，审计还修复了：

- Topic Change 被历史用户问题中的旧论文污染；
- `get_research_skill` 被误计入 Corpus 工具预算；
- 用户取消后另一条 Sample 仍继续运行；
- 胜出 Sample 先结束时，请求完成时间被提前记录；
- 两条进度事件没有 `sampleIndex`，线上无法区分。

这些问题不一定改变 Golden 分数，却会直接影响计费、取消、可观测性和用户体验。真实模型运行因此
同时承担答案质量回归和系统边界回归。

## 我为什么保留这次失败实验

单独一句“分数下降了”没有多少复用价值。我检查了四个问题：

1. 原假设中的哪一环不成立？
2. 数据能否区分候选生成、选择和评分问题？
3. 是否发现了单元测试覆盖不到的系统错误？
4. 这些结果能否支持我删除一条路线？

这次四个问题都有答案：

- 两条并行路径没有生成足够强的候选池；
- Oracle `17/30` 与 Selected `16/30` 分离了生成和选择；
- 全量运行发现全部失败时的 Token 漏记；
- 可以停止把 Best-of-2 当成默认提分方案。

这些结果足以停止生产化。继续投入只会为同一个假设增加工程复杂度和模型账单。

## 我为什么回退它

当前 Best-of-2 不应作为生产默认策略。

当前 Best-of-2 有五个直接问题：

```text
候选池上限没有超过旧基线；
Selector 无法合法感知 canonical Anchor；
新增 Pack 明显退化；
预算耗尽仍然频繁；
总 Token 成本过高。
```

下一轮会回到剩余失败本身：

- 区分 authored canonical Anchor 与语义有效替代 Evidence；
- 改善模型如何在已有候选中选择和引用证据；
- 用 Human Label 判断 Golden Anchor 是否过窄；
- 删除 Best-of-2 的生产编排、Selector 和预算状态；实验结论只保留在文档与历史产物中。

并行实现证明了 Sample 可以隔离、记录和比较。全量数据也确认，多采一次没有解决证据选择，Token
成本反而明显上升。因此我删除了生产编排，只保留实验记录和其中可复用的 Eval 设计。

## 回退结果：恢复单路径后是 17/30

实验结束后，Runtime 已恢复为旧的单路径模式：每轮只创建一个 `ResearchRunContext`、一个 Model、
一个 Session 和一个 SDK `Runner.run(max_turns=None)`。并行 Sample、Selector、Sample Budget、
Sample Eval 事件和相关测试代码均已删除；Evidence Coverage、Reading Model v2、当前检索和
Responses Provider Adapter 保留。

恢复后重新运行全部 30 个 MiniMax Golden Case。首次顺序运行在 30 分钟超时前完成 25 个 Case，
剩余 5 个单独续跑，再按 Case ID 合并原始 Run；普通失败没有重试或择优。

| 指标 | 恢复后的 MiniMax 单路径 |
| --- | ---: |
| Hard Pass | `17/30`，`56.7%` |
| Stable Pack | `12/15`，`80.0%` |
| New Pack | `5/15`，`33.3%` |
| 技术失败 | `1/30` |
| 模型调用 | `212` |
| 工具调用 | `167` |
| Total Token | `2,702,429` |
| 累计 Case 耗时 | 约 `36.1` 分钟 |

这比 Best-of-2 的 `16/30` 多通过 1 个 Case，同时少消耗约 73.5 万 Token。它仍低于历史合并参考
`18/30`。历史 `18/30` 包含技术失败重跑和聚焦结果替换，无法作为同条件 single-run 结果。

回退后的 `17/30` 才是当前代码、当前检索、当前 Evidence Coverage 和 MiniMax-M3 的有效单路径
参考。它再次确认：Best-of-2 增加了成本和复杂度，却没有提供质量收益。

合并产物：

```text
/tmp/paperloom-minimax-single-restored-merged-20260714
```

## 后续实验：换成 GPT-5.3 Codex Spark 会怎样

为了区分“编排策略不合适”和“MiniMax 模型能力不足”，我随后读取本机 `~/.codex/config.toml`
中的 Responses API Provider 配置，在不修改 Prompt、Golden、工具 Schema 和 Reading Model 的
前提下，用 `gpt-5.3-codex-spark` 做了一次单 Sample 全量测试。

Harness 为此增加了通用 Responses API Model Adapter。Provider 声明 `api_style=responses` 时使用
Agents SDK 的 `OpenAIResponsesModel`；MiniMax 仍走原有 Chat Completions Adapter。模型密钥只从
本机 Codex auth 读取，没有写入项目文件或 Eval 事件。

结果：

| 指标 | GPT-5.3 Codex Spark，单 Sample |
| --- | ---: |
| Hard Pass | `11/30`，`36.7%` |
| Stable Pack | `8/15`，`53.3%` |
| New Pack | `3/15`，`20.0%` |
| 技术失败 | `11/30` |
| Sample budget exhausted | `10/30` |
| 模型响应 | `160` |
| Corpus 工具调用 | `192` |
| Total Token | `1,715,664` |
| 墙钟时间 | 约 `13.9` 分钟 |

它通过了 Responses API 与 Function Tool 的真实协议测试，也在 smoke case
`transformer_adam_params_001` 上 Hard Pass，但全量效果明显低于 MiniMax 历史合并参考 `18/30` 和
MiniMax Best-of-2 `16/30`。

失败不只来自预算。10 个 Sample 因预算耗尽，另有一次连接错误；同时 GPT 更倾向于直接回答本应
澄清或拒答的问题，例如把 `attention_paper_ambiguous_001` 和 `gpt5_architecture_boundary_001`
都提交为 `answered`。主要错误仍是 32 次 Anchor Missing、32 次 Anchor Not Cited 和 23 次
Required Paper Missing。

这个对照说明：**换更强或更新的模型名称，不会自动适配现有 Harness 的研究协议。** 模型的工具
调用节奏、停止判断、证据消费和 outcome 校准同样重要。若为 GPT 单独扩大预算或改 Prompt，测试
将不再是单纯的模型替换实验，而是新的 Provider 调优项目。

因此当前数据不支持把默认模型切换到 `gpt-5.3-codex-spark`。Responses Adapter 可以保留，用于
后续受控 Provider 对比。当前优先分析 Anchor 选择与 Outcome 失败，暂不继续轮换模型。

完整的 Provider 迁移与模型对照过程另见
[`Provider 迁移实验`](provider-migration-experiment.md)。

## 复现资料

原始全量结果保存在实验运行目录中，没有作为产品运行时输入，也没有提交包含 Provider 凭据的
HTTP 记录。

相关文档：

- [`Golden Data 与 Harness 演化`](golden-data-harness-evolution.md)
- [`Provider 迁移实验`](provider-migration-experiment.md)
- `research/golden-data/2026-07-13-reading-model-retrieval-practice.md`
- `harness_py/ONBOARDING.md`
