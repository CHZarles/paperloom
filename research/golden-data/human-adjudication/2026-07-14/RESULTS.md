# MiniMax-M3 与 GPT-5.5 盲审结果

日期：2026-07-14

## 审核口径

- 30 个 Case，每个 Case 同时审核 Answer A / B。
- `answer-labels-template.yaml` 在打开新的 `blind-map.json` 前已冻结，对应 Commit 为 `c9ce253`。
- 60 个答案都完成了 decision、task fulfillment、grounding、overall 和 note。
- 判定接受同论文中语义等价的证据，不要求命中唯一 authored Anchor。
- 本轮是单一人工审核者的工程裁决，没有计算多标注者一致性。

本页保留工程解读。可机械重生成的原始统计见
[`adjudication-report.md`](adjudication-report.md) 和 [`adjudication-report.json`](adjudication-report.json)。

## Golden 合同结论

7 个有争议的 Contract 全部标记为 `keep`，`outcome_valid` 和 `evidence_valid` 均为
`true`，同时全部允许同论文等价证据。因此当前 30 个 Case 的意图合约可以保留，
但不能继续把精确 Anchor 当成回答准确性的唯一判据。

## 人工结果

| 指标 | GPT-5.5 | MiniMax-M3 |
| --- | ---: | ---: |
| 人工总体通过 | `28/30 = 93.3%` | `22/30 = 73.3%` |
| 决策通过 | `28/30` | `29/30` |
| 任务完成通过 | `28/30` | `26/30` |
| 事实依据通过（排除 N/A） | `28/28 = 100%` | `18/25 = 72.0%` |
| 盲审偏好 | `24/30 = 80.0%` | `4/30 = 13.3%` |
| 平局 | `2/30 = 6.7%` | `2/30 = 6.7%` |

成对结果：

- 两个模型都通过：`20/30`；
- 只有 GPT-5.5 通过：`8/30`；
- 只有 MiniMax-M3 通过：`2/30`；
- 两个模型都失败：`0/30`。

## 合同/锚点一致性与人工判定

| 模型 | 合同/锚点一致性（原 `hard_pass`） | 人工通过 | 合同与人工均通过 | 假阴性 | 假阳性 | 一致率 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| GPT-5.5 | `14/30` | `28/30` | `14` | `14` | `0` | `16/30 = 53.3%` |
| MiniMax-M3 | `17/30` | `22/30` | `14` | `8` | `3` | `19/30 = 63.3%` |

合同/锚点一致性给出了 `MiniMax +3` 的顺序，人工总体评分给出了 `GPT-5.5 +6` 的顺序，
盲审偏好则是 `GPT-5.5 24 : 4 MiniMax-M3`。确定性 Scorer 不仅低估通过率，还会反转
模型排名，因此只能解释为“严格合约/指定 Anchor 覆盖率”，不能解释为回答准确率。

## 人工确认的真实失败

GPT-5.5 的 2 个失败都是模糊请求处理问题：

- `attention_paper_ambiguous_001`：直接假定用户指的是 *Attention Is All You Need*，没有澄清任务。
- `agent_benchmark_ambiguous_001`：把未限定的 benchmark 请求静默缩小到 3 篇论文。

MiniMax-M3 的 8 个失败中，1 个是技术失败，其余主要是证据边界和过度推断：

- `mint_vs_tau_interaction_comparison_001`：只返回技术失败，没有完成比较。
- `transformer_bert_confirmation_followup_001`：为 cross-entropy loss 增加了未被引用段落支持的结论。
- `react_to_agent_evaluation_genealogy_001`：错误否定 MINT 和 WebArena 与 ReAct 的可验证关系。
- `cross_benchmark_human_agent_gap_001`：把特定 WebArena 配置误说成 headline/best 结果。
- `webarena_reproduction_protocol_001`：漏掉 `fuzzy_match`，并添加未支持的 API 名称。
- `transformer_to_bert_genealogy_001`：过度扩张 BERT 与原 Transformer 的架构等同性。
- `mint_tau_apparent_conflict_001`：错误将 MINT 的不同 `k` 设置解释为同一轨迹上的单调改善。
- `transformer_optimizer_reproduction_001`：清单漏写 Adam，并为 `beta2=0.98` 附加了无论文支持的因果解释。

## 结论

1. **评分机制是当前数字失真的主因。** Exact Anchor 和 Forbidden Paper 规则产生大量假阴性，
   并且会反转模型排名。
2. **模型差异也确实存在。** 在相同 Prompt、工具、Corpus 和单路径 Runtime 下，GPT-5.5 的人工
   通过率、事实依据和盲审偏好都更高。
3. **Harness 与 Corpus 不是全局性阻断。** GPT-5.5 在同一机制下可以完成 `28/30`，说明当前
   工具和 Reading Model 足以支持绝大多数 Case。
4. **Candidate -> Read 约 66% 仍是有用的过程诊断，但不是回答准确率的直接上限。**

完整保存的模型 Run、原始确定性 Score 和漏斗产物位于
[`validation-runs/2026-07-14-single-path-model-comparison-v1`](../../validation-runs/2026-07-14-single-path-model-comparison-v1/README.md)。
