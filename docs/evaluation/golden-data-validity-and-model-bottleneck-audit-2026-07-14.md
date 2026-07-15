# Golden Data 准确性与模型瓶颈审计

日期：2026-07-14

## 结论

当前 Golden Data 的**文件结构和 Anchor 定位是稳定的**，但还不能把 `Hard Pass` 直接解释为
“回答准确率”。现有数据存在三类问题：

1. 少数 Case 的期望行为本身有争议，或者 Required Evidence 没覆盖问题要求的全部维度；
2. 确定性 Scorer 把“命中指定 Anchor”当成硬条件，却不接受同论文中的等价、更完整证据；
3. `facts`、`claims` 和 `review` 中的大部分语义要求没有进入 Hard Pass，导致既有假阴性，也有
   假阳性空间。

因此，当前 `MiniMax 17/30` 和 `GPT-5.5 14/30` 都是**严格 Anchor 合约通过率**，不是可靠的
端到端回答准确率。最终盲审结果为 GPT-5.5 `28/30`、MiniMax-M3 `22/30`，偏好为
`24 : 4`（另有 2 个 tie）。Strict Scorer 不仅低估两个模型，还反转了它们的排名。
这说明评分机制是当前数字失真的主因，同时 GPT-5.5 与 MiniMax-M3 之间也存在真实的
回答质量差异。

本轮没有修改 Prompt、Case、Anchor、Manifest、Reading Model 或运行路径。

## 审计范围

审计对象：

- `research/golden-data/manifest-expanded.yaml`
- 14 篇论文
- 30 个 Case
- 29 个 authored Anchor
- 当前单路径 OpenAI Agents SDK Runtime
- MiniMax-M3 与 GPT-5.5 的真实运行产物

审计方法：

1. 运行确定性 Fixture 校验；
2. 运行 Anchor 与 Reading Model 审计；
3. 对照论文来源检查标题、作者、版本和 Anchor 全文；
4. 用 GPT-5.5 对每个 Case 的 outcome、paper scope、required evidence 和 scoring coverage 做离线二审；
5. 人工抽查二审发现和真实 Hard Fail 产物；
6. 在不改 Prompt、工具、数据和检索器的前提下，用 GPT-5.5 跑完整 30 Case；
7. 对 MiniMax 与 GPT-5.5 运行做 Candidate -> Read -> Cited -> Hard Pass 漏斗对比。

GPT-5.5 二审只作为发现问题的辅助证据，不替代最终 Human Label。抽查时以 Case、论文原文、
实际引用和用户请求为准。

## 第一层：结构与 Anchor

### 确定性 Fixture

```text
case_count:   30
passed_count: 30
failed_count: 0
```

产物：`research/golden-data/validation-runs/2026-07-14-single-path-model-comparison-v1/audit/deterministic-validation.json`

这证明 Manifest、Case、Fixture 和确定性 Scorer 在结构上能够闭环。它不证明真实回答语义正确。

### Anchor 审计

```text
anchor_count: 29
passed_count: 29
failed_count: 0
```

产物：`research/golden-data/validation-runs/2026-07-14-single-path-model-comparison-v1/audit/anchor-audit.json`

29 个 Anchor 都能在指定正页码上唯一匹配 Reading Model。Anchor 没有丢失、错页或重复命中问题。

## 第二层：论文元数据

13 篇带 arXiv ID 的论文标题都能与 arXiv 元数据对齐。`tau-bench` 与 arXiv 的标题差异只是
ASCII `tau` 和数学符号 `τ` 的表示差异。

但 `authors` 目前不是完整作者列表。14 篇论文中，只有 Adam 的作者列表完整；其余 13 篇只保存
第一位或前两位作者。GPT-2 的 Reading Model 首页也明确列出 6 位作者，而 Pack 中只有
`Alec Radford`。

这不会影响当前 30 个 Case，因为它们不测试完整作者列表；但 Runtime 把 Paper Card 视为权威
Corpus Metadata，因此以后如果测试“列出所有作者”，现有数据会产生错误答案。这里应明确二选一：

- 将 `authors` 补全；或
- 把字段改成明确的 `display_authors` / `representative_authors` 语义。

在作出决定前，不应新增完整作者列表相关 Golden Case。

## 第三层：Case 合约准确性

GPT-5.5 离线二审结果：

```text
Case:                    30
目标基本有效:            23
存在重大非 Scorer 问题:   7
Scoring coverage sufficient: 0
Scoring coverage partial:   27
Scoring coverage insufficient: 3
```

机器可读产物：
`research/golden-data/validation-runs/2026-07-14-single-path-model-comparison-v1/audit/gpt55-semantic-audit.json`

初步标记为需要 Human Adjudication 的 7 个 Case：

| Case | 问题 |
| --- | --- |
| `attention_paper_ambiguous_001` | 当前 5 篇论文中只有 `Attention Is All You Need` 唯一命中标题，强制 `needs_clarification` 过严。 |
| `bert_vs_transformer_comparison_001` | 问题要求比较双方的 architecture、directionality 和 training objective，但 Required Evidence 没直接覆盖原始 Transformer 的全部对应维度。 |
| `transformer_bert_confirmation_followup_001` | 与上一 Case 相同；Required Transformer Anchor 甚至主要描述此前主流 encoder-decoder 模型，不足以独立支撑全部比较轴。 |
| `webarena_reproduction_protocol_001` | Required Evidence 覆盖域、reset 和 functional correctness，但没有完整覆盖 task execution 流程。 |
| `mint_vs_tau_interaction_comparison_001` | 五个比较轴中，MINT 的 environment-state 语义没有被当前 Required Evidence 明确约束。 |
| `cross_benchmark_score_ranking_partial_001` | 用户明确要求“按报告百分比排序”；提供带强 caveat 的数值排序也是合理答案，强制 `partial` 并拒绝排序并非唯一正确行为。 |
| `toolsandbox_constraint_selection_001` | 全文 Evidence 实际覆盖 milestone，但 Anchor selector 本身没有包含 milestone 片段；需要确认 Hard Gate 应针对 selector 还是完整 Evidence Span。 |

最终人工裁决对这 7 个 Case 全部选择 `keep`，并确认当前 outcome 和 Required Evidence 有效；
同时 7 个 Case 全部允许同论文中的语义等价证据。因此不修改 Golden Contract，而是将 Exact
Anchor 与回答语义准确性分开报告。

其中 ToolSandbox Case 的完整 Reading Element 已包含 intermediate/final milestones，因此更接近
Anchor 表达问题，而不是事实错误。

## 第四层：Scorer 与 Golden 语义不一致

### Exact Anchor 假阴性

当前 Scorer 要求每个 `required` Anchor 必须出现在 Evidence Ledger，并在 citation-required Case 中
被引用。Human Review Guide 和 LLM Judge 规则却明确允许“同论文中的等价 Evidence”。两者互相冲突。

真实反例：

- `agentboard_progress_metric_001`：GPT-5.5 引用了 §1、§2.2、§4.2 中更完整的 progress-rate 解释，
  回答正确且引用充分，但没有引用指定 Abstract Anchor，因此 Hard Fail。
- `swebench_instance_provenance_001`：MiniMax 和 GPT-5.5 都读取了 Benchmark Construction、
  Execution-based Validation 和 Evaluation Procedure 的详细原文，回答比 Figure/Abstract Anchor 更完整，
  仍因缺少指定 Anchor Hard Fail。
- `benchmark_topic_change_followup_001`：GPT-5.5 正确切换到 SWE-bench，并引用 issue、PR、patch 和
  test evaluation 的正文证据，但没有引用指定 Figure Anchor，因此 Hard Fail。
- `react_to_agent_evaluation_genealogy_001`：GPT-5.5 找到了 AgentBench、AgentBoard、tau-bench 和
  ToolSandbox 对 ReAct 的直接关系；它引用了 tau-bench 中更直接的 ReAct-vs-Act 实验，而不是指定
  Introduction Anchor，因此 Hard Fail。

这类失败不是“模型没找到证据”，而是“模型找到的证据没有 authored Anchor ID”。

错误类别也支持这一点：

```text
MiniMax Hard Fail 13 个：10 个仅由 Required Anchor / Citation Anchor 规则触发
GPT-5.5 Hard Fail 16 个：11 个仅由 Required Anchor / Citation Anchor 规则触发
                         2 个仅由 Forbidden Paper / Anchor 规则触发
                         2 个仅由 Outcome + Citation Policy 触发
                         1 个同时触发 Outcome 与 Anchor 规则
```

GPT-5.5 的 11 个 Anchor-only Fail 没有 `REQUIRED_PAPER_MISSING`：它已经进入正确论文并引用了证据，
只是没有命中指定 Anchor。这个分布进一步说明 Strict Hard Pass 主要测量 authored Anchor 一致性，
而不是完整回答质量。

对 GPT-5.5 的 16 个 Hard Fail 做逐答案 Evidence 抽查后，15 个看起来已经完成用户任务并提供了
直接证据；唯一明显的行为失败是 `agent_benchmark_ambiguous_001`，它没有澄清笼统请求，而且只列出
了部分可用 Benchmark。其余答案中包括：

- 完整的 BERT / Transformer 三轴比较；
- WebArena 可执行复现清单；
- SWE-bench 从 GitHub PR 到执行测试的完整 provenance；
- GAIA / WebArena 正确的人机差距与不可比性说明；
- 四类 Agent Benchmark 的正确推荐；
- MINT / tau-bench 的 interaction 与 reliability 区分；
- 识别到 AgentBoard 和 ToolSandbox 中额外的直接 ReAct 实验关系。

这个 `15/16` 只是工程抽查，不是最终 Human Label；它的价值是证明至少存在大量显著的 Hard-score
假阴性，而不是替代盲审。

### Forbidden Paper 假阴性

- `transformer_optimizer_reproduction_001`：GPT-5.5 给出完全正确的 Adam、β1、β2、ε，并首先引用
  Transformer Optimizer 段落；它额外引用 Adam 论文说明算法来源，因 `adam_2014` 被设为 forbidden
  而 Hard Fail。它没有用通用默认值替换论文值。
- `coding_benchmark_followup_001`：GPT-5.5 明确选择 SWE-bench，同时用 GAIA 和 WebArena 的证据解释
  为什么另外两个选项不适合 coding agent；Golden 将这些帮助用户比较的引用视为 forbidden。

Forbidden 应约束“使用错误论文支撑核心结论”，不能简单等价于“Evidence Ledger 中出现该论文”。

### 内容假阳性空间

`BehaviorScorer._score_content()` 在答案没有输出 `fields` 时返回 `not_run`，而 `not_run` 不会使
Hard Pass 失败。`claims` 和 `review` 也只进入待审列表，不参与确定性 Hard Pass。

因此：

- 一个答案可以引用正确 Anchor，却不写出 beta1、beta2、epsilon，仍有机会 Hard Pass；
- Corpus Count/List Case 只校验 `outcome=answered` 和无引用，不校验实际数量或标题；
- Comparison、Conflict Resolution 和 Recommendation Case 不校验最终文本是否完成语义任务。

这意味着当前 Scorer 同时存在：

```text
等价 Evidence 被拒绝 -> 假阴性
核心语义未被检查   -> 假阳性空间
```

## GPT-5.5 对照实验

实验保持以下内容不变：

- 单路径 Runtime；
- 相同 Prompt；
- 相同 Golden Data；
- 相同工具 Schema；
- 相同 Reading Model；
- 相同检索实现；
- `max_completion_tokens=3000`。

GPT-5.5 通过 Responses API 接入。第一次全量运行中
`agentbench_environment_inventory_001` 出现一次传输超时，只对这个技术失败 Case 重跑；没有重跑
或择优普通 Golden Fail。

### 严格 Anchor Hard Pass

| 指标 | MiniMax-M3 | GPT-5.5 |
| --- | ---: | ---: |
| Hard Pass | `17/30` | `14/30` |
| Candidate Recall | `41/48 = 85.4%` | `44/48 = 91.7%` |
| Candidate -> Read | `27/41 = 65.9%` | `29/44 = 65.9%` |
| Read -> Cited | `25/27 = 92.6%` | `28/29 = 96.6%` |
| Model Calls | `212` | `159` |
| Tool Calls | `167` | `173` |
| Total Tokens | `2,702,429` | `1,069,942` |
| Summed Case Time | `约 36.1 分钟` | `约 28.3 分钟` |

GPT-5.5 和 MiniMax 的完整保存产物位于：

- `research/golden-data/validation-runs/2026-07-14-single-path-model-comparison-v1/gpt-5.5/`
- `research/golden-data/validation-runs/2026-07-14-single-path-model-comparison-v1/minimax-m3/`
- `research/golden-data/validation-runs/2026-07-14-single-path-model-comparison-v1/*-funnel.json`

### 暂时排除 6 个明显争议 Case

在不修改 Golden 文件的前提下，暂时排除以下 Case 做敏感性分析：

```text
attention_paper_ambiguous_001
bert_vs_transformer_comparison_001
transformer_bert_confirmation_followup_001
webarena_reproduction_protocol_001
mint_vs_tau_interaction_comparison_001
cross_benchmark_score_ranking_partial_001
```

| 指标 | MiniMax-M3 | GPT-5.5 |
| --- | ---: | ---: |
| Strict Hard Pass | `16/24 = 66.7%` | `14/24 = 58.3%` |
| Candidate Recall | `30/33 = 90.9%` | `32/33 = 97.0%` |
| Candidate -> Read | `66.7%` | `68.8%` |

即使在这一子集，Strict Hard Pass 仍偏向 MiniMax；但 GPT-5.5 的 Candidate Recall 更高，且多个
Hard Fail 是 Exact Anchor 或 Forbidden Paper 假阴性，因此不能把 `58.3%` 解释为 GPT-5.5 的
真实回答准确率。

## 瓶颈判断

### 已经可以确认的部分

1. **不是单纯的检索召回瓶颈。** GPT-5.5 Candidate Recall 达到 `91.7%`，争议 Case 暂时排除后为
   `97.0%`，但 Strict Hard Pass 没有同步提升。
2. **Candidate -> Read 仍是 Runtime 的主要漏斗。** 两个模型都只有约 `66%` 的候选转读取率，说明
   模型看到候选后，仍会选择正文中其他位置、停止继续读取，或围绕一个证据展开。
3. **模型差异真实存在。** GPT-5.5 调用更少、Token 更少、Candidate Recall 更高，并生成了更完整的
   多维比较和复现步骤；同时它更倾向直接回答模糊请求，并会引用额外的对比论文。
4. **当前“准确率”的首要瓶颈是评测机制。** Exact Anchor、Forbidden Paper 和未执行的语义要求
   共同扭曲 Hard Pass，已经不足以可靠比较 MiniMax 与 GPT-5.5 的最终回答质量。

### 人工盲审已完成

30 组 A/B 答案已在新的随机映射下盲审，标签先于 `blind-map.json` 冻结。结果为：

| 指标 | MiniMax-M3 | GPT-5.5 |
| --- | ---: | ---: |
| 人工总体通过 | `22/30 = 73.3%` | `28/30 = 93.3%` |
| 人工事实依据通过（排除 N/A） | `18/25 = 72.0%` | `28/28 = 100%` |
| 盲审偏好 | `4/30` | `24/30` |

两个模型都通过 20 个 Case，只有 GPT-5.5 通过 8 个，只有 MiniMax-M3 通过 2 个，
没有两者同时失败的 Case。完整结果见
`research/golden-data/human-adjudication/2026-07-14/RESULTS.md`。

自动 Judge 也不能直接替代人工。用更高版本的 `gpt-5.6-sol` 按冻结的 `llm-judge/v5` 规则复核
既有人工标签时，23 个成功返回的 Case 全部被判为 overall fail：

```text
completed:       23
full agreement:  12/23
overall match:   15/23
false failure:    8/23
false pass:       0/23
```

也就是说，它把 8 个既有人工 overall pass 全部判成 fail。另有 4 个请求超时。这不是一个可接受的
校准结果，说明冻结 Judge Prompt、现有人工标签和“严格 citation completeness”之间仍存在口径冲突。
因此不能把未校准的 GPT-5.5 或 GPT-5.6 Judge 当作新 Ground Truth。

## 实施状态与后续边界

上述离线报告方案已经实现：

1. 原始 `hard_pass` 保留，但在新报告中只命名为“合同/锚点一致性”，不再解释为回答准确率；
2. 报告读取冻结的 Human Label、`blind-map.json` 和两个已保存的 `score_report.json`，同时展示
   人工总体评分、事实依据、假阴性和假阳性；
3. 报告完全离线生成，没有为了重新计算指标而重跑模型；
4. JSON 与 Markdown 产物分别见
   [`adjudication-report.json`](../../research/golden-data/human-adjudication/2026-07-14/adjudication-report.json)
   和
   [`adjudication-report.md`](../../research/golden-data/human-adjudication/2026-07-14/adjudication-report.md)。

真实缺陷的处置见
[`DEFECT_DISPOSITION.md`](../../research/golden-data/human-adjudication/2026-07-14/DEFECT_DISPOSITION.md)：

- MiniMax-M3 的 1 个畸形工具参数技术故障已在 Provider Adapter 边界增加通用恢复，并完成确定性
  测试和单 Case 定向复跑；
- MiniMax-M3 的 7 个事实依据 / 过度宣称失败保留为模型质量基线，不增加 Case 特判；
- GPT-5.5 的 2 个歧义处理失败保留为模型行为基线，不增加硬编码预检；
- 只有运行时、Prompt、检索、工具协议或模型行为发生实质变化时才调用模型。离线报告、指标命名和
  文档变化不触发重跑。

表格/图像产物的发布缺口仍作为独立数据管道问题处理，不与本次评分机制和 Harness 编排混在一起。
这保持了职责边界：线上 Harness 只负责模型编排和完整记录，Golden 修订、语义评分、Provider
对比和研究分析全部留在线下。
