# 真实缺陷处置清单

日期：2026-07-14

## 口径与边界

- 原始 `hard_pass` 保留，但只解释为**合同/锚点一致性**。
- 语义质量以冻结的人工标签为准，完整统计见
  [`adjudication-report.md`](adjudication-report.md)。
- 本轮不修改 Golden Case、Prompt、路径、Reading Model 或研究编排策略。
- 不为单个 Case 增加检索 fallback、关键词规则、额外采样或在线语义 Judge。
- 离线报告和文档更新不触发模型重跑；只有运行时或模型行为发生实质变化时才重跑。

## 处置总览

| 模型 | 类别 | 数量 | 本轮处置 |
| --- | --- | ---: | --- |
| MiniMax-M3 | Provider / SDK 技术故障 | 1 | 增加通用的畸形工具参数恢复，完成确定性回归测试和受影响 Case 的定向复跑。 |
| MiniMax-M3 | 事实依据不足或过度宣称 | 7 | 保留为模型质量基线，不增加 Case 特判或 Harness 补丁。 |
| GPT-5.5 | 模糊请求处理 | 2 | 保留为模型行为基线，不增加硬编码的请求预检规则。 |

## MiniMax 技术故障

Case：`mint_vs_tau_interaction_comparison_001`

原始运行在 MiniMax 到达输出长度上限后，返回了被截断的
`submit_research_answer` JSON。SDK 首先把它交给工具层并得到参数错误，随后又把这条畸形的
assistant tool call 放进下一次请求历史。MiniMax 在模型继续修复前就拒绝该请求：

```text
HTTP 400: invalid function arguments json string
```

本轮修复位于 Provider Adapter 边界：在 SDK 重放响应前，只把无法解析的 function-call 参数
转换成已有的内部 continuation tool，并要求模型使用更短、有效的 JSON 重新调用原工具。它不改变
Agent 循环、工具 Schema、研究提示词、检索策略或最终提交协议。

验证证据：

- `tests/test_agents_model.py`：畸形参数被转换为有效的内部 repair call；
- `tests/test_agents_tools.py`：repair 指令能够原样返回给模型；
- `tests/test_agents_runtime.py`：下一次 Provider 请求不再携带非法 JSON，并能完成提交；
- 定向真实运行：
  [`2026-07-14-minimax-tool-argument-repair-v1`](../../validation-runs/2026-07-14-minimax-tool-argument-repair-v1/README.md)。

定向真实运行从 `FAILED_TECHNICAL` 变为 `COMPLETED`，但合同/锚点一致性仍然失败，因为答案只完成
了 interaction design，没有覆盖问题要求的其余比较轴。这不是准确率提升，也不替换冻结基线。
该次随机运行没有再次产生畸形 JSON，因此 repair 分支的直接证据来自确定性测试，而不是这一次
真实样本。

## MiniMax 模型质量缺陷

| Case | 人工确认的问题 | 处置 |
| --- | --- | --- |
| `transformer_bert_confirmation_followup_001` | 为原始 Transformer 的 cross-entropy loss 增加了当前引用不能支持的断言。 | 保留为事实依据回归样本。 |
| `react_to_agent_evaluation_genealogy_001` | 错误否定 MINT、WebArena 与 ReAct 的可验证关系。 | 保留为跨论文关系与过度否定回归样本。 |
| `cross_benchmark_human_agent_gap_001` | 把带 UA hint 的 WebArena 配置误写成 headline / best 结果。 | 保留为表格条件和数值语境回归样本。 |
| `webarena_reproduction_protocol_001` | 漏掉 `fuzzy_match`，并增加论文未支持的 API 方法名。 | 保留为复现清单完整性回归样本。 |
| `transformer_to_bert_genealogy_001` | 过度扩张 BERT 与原 Transformer 的架构等同性。 | 保留为谱系关系和边界回归样本。 |
| `mint_tau_apparent_conflict_001` | 把不同 `k` 的独立评估误说成同一轨迹上的单调改善。 | 保留为实验设计解读回归样本。 |
| `transformer_optimizer_reproduction_001` | 清单未明确写出 Adam，并为 `beta2=0.98` 增加无依据的因果解释。 | 保留为精确事实与额外解释回归样本。 |

这些问题在相同 Harness、Prompt、Corpus 和 Reading Model 下没有统一的确定性修复点。针对单个
Case 修改检索器、Validator 或工具反馈，会把模型质量问题写进生产编排，因此本轮不修补。它们用于
后续模型选择，或者验证真正通用的事实依据约束改进。

## GPT-5.5 模型质量缺陷

| Case | 人工确认的问题 | 处置 |
| --- | --- | --- |
| `attention_paper_ambiguous_001` | 未澄清用户具体想对目标论文执行什么任务，直接开始识别论文。 | 保留为任务歧义回归样本。 |
| `agent_benchmark_ambiguous_001` | 把未限定的 benchmark 请求静默缩小到 3 篇论文。 | 保留为范围歧义回归样本。 |

这两个失败可以通过更强的通用澄清策略、Prompt 或模型选择处理，但不能在不改变生产行为的前提下
由 Harness 自动推断。除非后续明确修改这类行为，否则不重跑。

## 重跑策略

| 变化 | 是否调用模型 |
| --- | --- |
| 重生成离线报告、修改指标名称或文档 | 否，读取冻结标签和已保存 Score。 |
| 修改离线统计或报告渲染 | 否，运行确定性报告测试。 |
| 修复 Provider / SDK 恢复行为 | 是，先运行确定性回归测试，再定向复跑受影响 Case。 |
| 修改 Prompt、工具协议、检索、模型或通用澄清策略 | 是，先跑相关失败 Case 和稳定回归样本；只有确认影响范围后才考虑全量。 |
| 普通模型随机失败，但运行时没有变化 | 否，不通过重复采样替换冻结基线。 |

所有新运行必须写入新的永久目录，不覆盖
[`2026-07-14-single-path-model-comparison-v1`](../../validation-runs/2026-07-14-single-path-model-comparison-v1/README.md)。
