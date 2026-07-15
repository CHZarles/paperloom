---
title: MiniMax 17/30、GPT 14/30，盲审后我停止追 Hard Pass
description: 一次把合同/锚点一致性、人工语义质量和真实技术故障拆开的 Harness 复盘，以及为什么评测做到最后要停。
date: 2026-07-15
category: Evaluation
stage: evaluation
status: closed
result: 22/30 human pass
topics: [Golden Data, Human Evaluation, MiniMax, Agent Harness]
---

# MiniMax 17/30、GPT 14/30，盲审后我停止追 Hard Pass

同一套 30 个 Golden Case，MiniMax-M3 的 Hard Pass 是 `17/30`，GPT-5.5 是 `14/30`。只看这组
数字，MiniMax 赢了 3 个 Case。

我把 30 组答案重新打乱，先冻结人工标签，再打开模型映射。人工结果变成 GPT-5.5 `28/30`、
MiniMax-M3 `22/30`，盲审偏好是 `24 : 4`，另有 2 个平局。严格评分给出的模型顺序和人工审核
完全相反。

Scorer 没有随机坏掉。它一直在测一件很具体的事：模型有没有满足 authored Contract，并命中指定
Anchor。问题出在我把这项指标叫成了“通过率”，随后又把它当作回答准确率使用。

这轮工程最后留下三项结果。Hard Pass 改名为“合同/锚点一致性”；人工语义质量独立报告；一个
MiniMax 工具参数故障在 Provider Adapter 边界修掉。其余模型问题没有继续写进 Harness。评测已经
解释清楚数据，再往下加 verifier 和 Gate，用户很难感受到区别。

## 这轮评估从哪里开始

PaperLoom 的 Research Harness 负责一次论文研究回合。模型先搜索 Corpus，再定位 Reading Model
中的位置，读取原文 Evidence，最后通过 `submit_research_answer` 提交答案。生产模型固定使用
MiniMax。GPT 只承担对照测试和问题定位，不是生产候选。

Golden Data 给每个 Case 定义可观察要求：

- outcome 应该是 answered、partial、needs clarification 还是 abstained；
- 哪些论文必须或禁止使用；
- 哪些 Evidence Anchor 必须读到和引用；
- 少量结构化事实和人工 Review 条件。

原始 `hard_pass` 会合并这些确定性检查。它适合做协议回归，却不会阅读最终答案的完整语义。一个
回答可能引用了同论文中更直接的段落，因为没有命中 authored Anchor 而失败；另一个回答也可能
引用了指定 Anchor，却漏掉用户要求的比较维度，仍然通过部分硬检查。

我在前几轮排障里已经把 Candidate、Read、Cited 和 Hard Pass 拆开。Reading Model 构建链修复后，
Required Anchor 的 Candidate Recall 从 `16/32` 回到 `29/32`，MiniMax 全量历史参考从 `13/30`
回到 `18/30`。召回恢复以后，继续提高 Hard Pass 越来越像 Evidence Selection 问题。

## 我先确认 Golden Data 没坏

如果 Case、Anchor 或 Reading Model 本身不稳定，后面的模型对比没有意义。我先跑了两层不调用模型
的校验。

```text
Deterministic Fixture：30/30
Anchor Audit：          29/29
```

30 个 Case 都能由确定性 Fixture 闭环，29 个 authored Anchor 都能在指定正页码唯一匹配 Reading
Model。14 篇论文的标题和版本也完成了来源核对。这一层证明数据结构可用，不能证明自然语言答案
正确。

审计还找到一个独立的数据问题。14 篇论文中，13 篇的 `authors` 只保存第一位或前两位作者。当前
Case 不测试完整作者列表，所以它没有影响这轮结果。我把它记为 Corpus Metadata 边界，没有为了
这次模型评分顺手改数据。

整个审计期间，Golden Case、Prompt、Anchor、Manifest、Reading Model 和运行路径都保持不变。

## Hard Pass 为什么把排名排反了

严格评分产生了两种相反的误差。

第一种是 Exact Anchor 假阴性。GPT-5.5 在 `agentboard_progress_metric_001` 中引用了正文对
progress rate 更完整的解释，没有引用指定的 Abstract Anchor，因此 Hard Fail。
`swebench_instance_provenance_001` 也一样，模型读了 Benchmark Construction、Execution-based
Validation 和 Evaluation Procedure，答案覆盖得更细，评分仍然要求 Figure 或 Abstract 中的
指定位置。

第二种来自 Forbidden Paper。`transformer_optimizer_reproduction_001` 中，GPT-5.5 先引用
Transformer 论文给出 Adam、beta1、beta2 和 epsilon，又引用 Adam 原论文解释算法来源。数值没有
错，额外的来源引用却触发 forbidden paper。`coding_benchmark_followup_001` 里，模型选择
SWE-bench，并引用 GAIA 和 WebArena 解释为什么它们不适合 coding agent，同样被当成错误论文。

Scorer 另一侧还有假阳性空间。答案没有输出 `fields` 时，内容评分会变成 `not_run`；很多 `claims`
和 `review` 条件只进入待审列表，不影响 Hard Pass。模型可以命中 Anchor，却没有完整完成比较、
推荐或复现清单。

人工标签与严格评分对齐后，混淆矩阵很直观：

| 模型 | 合同与人工均通过 | 假阴性 | 假阳性 | 合同与人工均失败 |
| --- | ---: | ---: | ---: | ---: |
| GPT-5.5 | 14 | 14 | 0 | 2 |
| MiniMax-M3 | 14 | 8 | 3 | 5 |

GPT-5.5 有 14 个答案被 Hard Pass 拒绝，人工仍判为通过。MiniMax 有 3 个 Hard Pass，人工判为失败。
这个指标可以继续保留，名字必须写清楚。

## 盲审怎样避免模型身份影响判断

我重新生成了 `blind-map.json`，把每个 Case 的两份答案随机映射为 A 和 B。人工审核时看不到模型
身份。30 个 Case 一共检查 60 份答案，每份都填写 decision、task fulfillment、grounding、overall
和 note。

标签在打开新映射前冻结并提交。之后才恢复模型身份，计算成对结果：

| 指标 | GPT-5.5 对照 | MiniMax-M3 生产基线 |
| --- | ---: | ---: |
| 人工总体通过 | `28/30 = 93.3%` | `22/30 = 73.3%` |
| 事实依据通过 | `28/28 = 100%` | `18/25 = 72.0%` |
| 盲审偏好 | `24/30` | `4/30` |
| 只有该模型通过 | `8/30` | `2/30` |

两个模型共同通过 20 个 Case，没有共同失败的 Case。7 个此前被标记为 Contract 有争议的 Case 也
单独审核，最终全部保留，同时允许同论文中的语义等价 Evidence。Golden 的任务意图没有被放宽，
Exact Anchor 从语义准确率中分离出来。

自动 Judge 没能替代这次人工审核。`gpt-5.6-sol` 按冻结规则复核时，23 个成功返回的 Case 全部被
判成 overall fail，其中 8 个是人工通过答案；另有 4 个请求超时。Judge Prompt 对 citation
completeness 的要求和人工口径仍然不一致，我没有把它升级成新的 Ground Truth。

## GPT 对照帮我排除了哪些方向

GPT-5.5 使用同一套 Prompt、工具 Schema、Reading Model、检索器和单路径 Runtime。它的 Candidate
Recall 是 `44/48`，MiniMax 是 `41/48`；两者 Candidate -> Read 都约为 `65.9%`。GPT 的人工结果
达到 `28/30`，足以证明 Harness 和 Corpus 没有把任务上限锁在 `22/30`。

这次对照没有改变生产模型选择。MiniMax 是产品约束，GPT 的结果只用来判断问题位置。对照数据还
给出一个成本提醒：

| 指标 | GPT-5.5 对照 | MiniMax-M3 生产基线 |
| --- | ---: | ---: |
| 模型调用 | `159` | `212` |
| 已记录 Token | `1,069,942` | `2,702,429` |
| 累计 Case 时间 | 约 `28.3` 分钟 | 约 `36.1` 分钟 |

MiniMax 剩余失败集中在读完 Evidence 之后：错误否定论文关系、误读表格条件、把不同实验设置说成
同一条轨迹、添加引用没有支持的因果解释。继续扩大 Candidate top-k 很难处理这些错误。

## 一次值得修的技术故障

MiniMax 的 `mint_vs_tau_interaction_comparison_001` 没有返回普通错误答案。模型在
`max_tokens=3000` 处截断了 `submit_research_answer` 的 JSON 参数。SDK 把参数错误返回工具层后，
下一次请求又重放了这条畸形 assistant tool call。MiniMax 在模型获得修复机会前直接返回：

```text
HTTP 400: invalid function arguments json string
```

修复放在 Provider Adapter 边界。Adapter 检测无法解析的 function-call arguments，把这条调用转成
已有的内部 continuation tool，要求模型用更短的有效 JSON 重试。Agent 循环、工具 Schema、主 Prompt、
检索和最终提交协议没有改。

我给这条路径加了三层确定性测试：

- Provider 响应中的畸形参数会转换成有效 repair call；
- repair 指令能回到模型；
- 下一次 Provider 请求不再包含非法 JSON，并能完成提交。

受影响 Case 随后做了一次真实定向复跑。Runtime 从 `FAILED_TECHNICAL` 变成 `COMPLETED`，但回答只
覆盖 interaction design，合同/锚点一致性仍为 `0/1`。这次随机响应没有再次截断参数，所以真实
复跑只能证明 Case 不再以原来的 HTTP 400 结束；repair 分支的直接证据来自确定性集成测试。

技术故障修好了，模型语义质量没有被包装成“修复成功”。这条边界值得保留。

## 我差点又给 Harness 加一层 verifier

人工审核确认 MiniMax 还有 7 个事实依据或过度宣称问题。我一度准备在最终提交前增加 MiniMax
自检，把当前问题、Draft 和引用 Span 再交给模型，最多允许一次验证和一次修复。计划还列了 7 个
失败 Case、7 个稳定对照和 `30%` Token 增幅上限。

这套设计没有进入代码。此前的 Evidence Coverage Gate 已经给过一次警告。四个焦点 Case 从
`0/4` 变成 `1/4`，却用了约 60.9 万 Token；Best-of-2 又把通过率从历史参考 `18/30` 拉到
`16/30`，总 Token 达到 343.7 万。更严格的提交门会改变模型行动，模型可能为旁支 Claim 不断补证，
用户只会等得更久。

7 个失败是有效的模型质量记录，但当前没有真实用户反馈表明它们已经形成高频体验问题。继续为
Golden Case 增加 verifier，会提高延迟、Token 和维护成本。文章、报告和失败清单保留，实施路线在
这里停止。

## 成功留下了什么

### 指标终于按测量对象命名

原始 `hard_pass` 没有删除，报告里改成“合同/锚点一致性”。人工总体评分和事实依据独立展示。
旧数据仍然可复算，读者不会再把 `17/30` 直接读成回答准确率。

### 报告全部在线下生成

新的离线报告只读取冻结人工标签、盲测映射和两个保存的 `score_report.json`。JSON 和 Markdown
可以重复生成，连续两次 hash 完全一致。Harness 不读取报告，不用它控制检索、记忆、进度或回答。

Runtime 继续只保存原始事实：

```text
<run_id>/events.jsonl
<run_id>/result.json
```

语义评分、漏斗、Provider 对照和 Human Label 都在线下派生。评测字段没有继续扩散到生产对象。

### 技术错误在协议边界修复

畸形 JSON 是 Provider 与 SDK 重放之间的兼容问题，修复也留在 Model Adapter。没有为
`mint_vs_tau_interaction_comparison_001` 添加 Case 分支，其他工具调用同样能使用这条恢复路径。

### 重跑规则收紧了

离线改名、重新统计和文档变化都不调用模型。Runtime、Prompt、工具协议、检索或模型行为发生实质
变化时，先跑受影响 Case，再决定是否扩大范围。普通 Golden Fail 不通过重复采样覆盖。

## 失败也留下了可复用的数据

我花了不少时间追一个名字含糊的指标。Candidate Recall、Exact Anchor、Forbidden Paper、自动
Judge 和模型行为混在同一个 Hard Pass 里，任何分数变化都容易被解释成“检索变好”或“模型变差”。
盲审把这些解释拆开以后，前面的很多优化目标失去了依据。

GPT 对照一度又被我写成模型选择建议。产品约束一直是 MiniMax，GPT 只负责定位问题。我随后删掉
切换建议，把对照恢复成诊断基线。这次偏航提醒我，实验变量和产品约束要写在报告开头，不能等到
结论段再补。

真实定向复跑也没有命中 repair 分支。如果只看状态从 `FAILED_TECHNICAL` 到 `COMPLETED`，很容易
把它写成 Provider 问题已经被线上样本复现并修好。保存完整事件和确定性集成测试，让结论能够停在
证据支持的位置。

## 这套 Eval 以后怎么用

这批 Golden Data 继续承担三项工作：

- 修改编排、工具或检索后做行为回归；
- 出现真实用户问题时复现同类失败；
- MiniMax 版本升级时做同条件对照。

它不会继续驱动一轮独立的“提高 Golden 分数”工程。7 个模型质量失败保留为已知边界，没有用户
影响证据时不增加生产 Gate。

当前验证基线为：

```text
Python tests：          83/83
Stable Fixture：        15/15
Expanded Fixture：      30/30
Stable Anchor Audit：    7/7
Expanded Anchor Audit： 29/29
MiniMax Human Overall： 22/30
MiniMax Grounding：     18/25
```

这一轮评测改正了指标含义，找出一个 SDK 兼容故障，也证明了几条昂贵方案不值得继续。评测做到只能
解释旧数据、不能改变用户看到的速度、可靠性和交互时，我选择停下来。Harness 接下来的工作重新回到
用户能直接感受到的研究流程。

## 复现资料

- [`Golden Data 准确性与模型瓶颈审计`](https://github.com/CHZarles/paperloom/blob/main/docs/evaluation/golden-data-validity-and-model-bottleneck-audit-2026-07-14.md)
- [`人工盲审与合同一致性报告`](https://github.com/CHZarles/paperloom/blob/main/research/golden-data/human-adjudication/2026-07-14/adjudication-report.md)
- [`真实缺陷处置清单`](https://github.com/CHZarles/paperloom/blob/main/research/golden-data/human-adjudication/2026-07-14/DEFECT_DISPOSITION.md)
- [`MiniMax 问题定位基线与停止边界`](https://github.com/CHZarles/paperloom/blob/main/research/golden-data/human-adjudication/2026-07-14/MINIMAX_DIAGNOSTIC_BASELINE.md)
- [`MiniMax 工具参数恢复定向复跑`](https://github.com/CHZarles/paperloom/blob/main/research/golden-data/validation-runs/2026-07-14-minimax-tool-argument-repair-v1/README.md)
- [`Golden Data 与 Harness 演化`](golden-data-harness-evolution.md)
- [`Best-of-2 Agent 编排实验`](best-of-two-agent-orchestration.md)
