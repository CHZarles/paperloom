# MiniMax-M3 问题定位基线

分析日期：2026-07-15

## 产品约束

生产研究模型固定使用 MiniMax。GPT-5.5 只用于对照测试和定位问题，不是生产候选，也不进入模型
切换或成本选型。本页不修改 Provider 配置，不建议把 GPT-5.5 设为默认模型。

GPT-5.5 对照的作用只有两个：

1. 判断相同 Harness、Prompt、Corpus 和 Reading Model 是否有能力完成当前任务；
2. 区分 Harness / 数据问题和 MiniMax 自身的行为问题。

## 保存运行对照

| 指标 | GPT-5.5 对照 | MiniMax-M3 生产基线 |
| --- | ---: | ---: |
| 人工总体通过 | `28/30 = 93.3%` | `22/30 = 73.3%` |
| 事实依据通过 | `28/28 = 100.0%` | `18/25 = 72.0%` |
| 模型调用 | `159` | `212` |
| 工具调用 | `173` | `167` |
| 已记录总 Token | `1,069,942` | `2,702,429` |
| 累计 Case 耗时 | 约 `28.3` 分钟 | 约 `36.1` 分钟 |
| 冻结运行技术失败 | `0/30` | `1/30` |

MiniMax 的 `mint_vs_tau_interaction_comparison_001` 没有保存完整 Token、模型调用和耗时，因此
MiniMax 的投入数字是下界。GPT 首轮有一次传输超时，只按既定规则重跑了该技术失败。

## 当前判断

1. **Corpus 和 Harness 不是全局阻断。** 相同环境下 GPT-5.5 能达到 `28/30`，说明现有论文、
   Reading Model 和工具足以支持绝大多数任务。
2. **检索不是当前首要矛盾。** 剩余 MiniMax 失败多数发生在找到或读到相关论文之后。
3. **技术故障已经单独处理。** 畸形工具参数恢复已有确定性测试和受影响 Case 的定向复跑。
4. **下一阶段只剩 7 个模型质量问题。** 它们集中在事实依据不足、错误否定、表格条件误读、实验
   设置误解和额外因果解释。
5. **不能用 Case 特判修复。** Golden Anchor、Case ID、固定关键词、首页 fallback、并行采样和
   在线 Judge 都不应进入生产编排。

## 下一阶段 Proposal

下一步不是继续扩大检索，而是验证一个 MiniMax-only 的通用事实依据收敛机制。

### 1. 先做离线失败契约

只读取已经保存的 7 个失败 Run，为每个 Case 整理：

- 用户要求的回答维度；
- 最终答案中的关键 Claim；
- Claim 实际引用的 Evidence；
- unsupported、overstated、misread condition 或 missing requested item 的失败类型。

这一步不调用模型，也不修改 Golden、Prompt、路径或 Reading Model。

### 2. 只设计一个通用实验

候选方案是在最终提交边界增加一次有上限的 MiniMax 自检：输入只包含当前问题、Draft 和真实引用
Span，输出 unsupported Claim 与遗漏维度；最多允许一次修复，然后必须结束。

设计约束：

- 保持单路径，仍然只使用 MiniMax；
- 不读取 Golden Case、Anchor、Human Label 或期望答案；
- 不增加检索 fallback、并行采样、Best-of-N 或第二个 Judge 模型；
- 不修改现有主 Agent Prompt，除非先单独评审并接受新的内部 verifier 接口；
- 最多新增一次验证和一次修复，防止再次出现无限补证和 Token 爆炸。

### 3. 先跑 14 个定向 Case

失败集使用已经确认的 7 个 MiniMax 事实依据问题。稳定对照集使用：

```text
bert_vs_transformer_comparison_001
agent_evaluation_recommendation_001
swebench_instance_provenance_001
adam_beta2_conflict_001
toolsandbox_constraint_selection_001
gpt3_to_transformer_multihop_001
corpus_paper_list_001
```

不先跑全量 30 Case。

### 4. 进入全量前的 Gate

- 7 个失败中至少 5 个通过新的人工事实依据审核；
- 7 个稳定对照没有新增人工失败；
- 每个 Case 最多增加一次验证和一次修复；
- 定向集合总 Token 增幅不超过 `30%`；
- 没有引入新的技术失败或结果协议变化。

只有满足这些 Gate，才值得运行全量 30 Case。全量结果仍同时报告合同/锚点一致性和人工语义质量，
不再把 `hard_pass` 当作准确率。

如果同一个 MiniMax 自检仍无法稳定识别这些错误，就不继续叠加 Harness 规则。下一种合理手段应是
MiniMax 系列内的模型版本升级或专门训练，而不是继续修改检索器。

## 数据来源

- 人工质量：[`adjudication-report.json`](adjudication-report.json)
- 完整模型 Run：
  [`2026-07-14-single-path-model-comparison-v1`](../../validation-runs/2026-07-14-single-path-model-comparison-v1/README.md)
- 缺陷处置：[`DEFECT_DISPOSITION.md`](DEFECT_DISPOSITION.md)

本轮只有一位人工审核者，Case 也集中在当前论文研究语料。GPT 对照只能证明本项目内存在可达的
更好行为，不能外推为通用模型排名。
