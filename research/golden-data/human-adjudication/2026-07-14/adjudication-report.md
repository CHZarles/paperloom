# 人工盲审与合同一致性报告

本报告将原始 `hard_pass` 保留为**合同/锚点一致性**，不将它解释为回答准确率。
**人工语义质量**独立来自冻结的盲审标签。本报告只读取已保存产物，不重跑模型。

## 总览

| 模型 | 合同/锚点一致性 | 人工总体评分 | 事实依据 | 盲审偏好 |
| --- | ---: | ---: | ---: | ---: |
| `gpt-5.5` | `14/30 = 46.7%` | `28/30 = 93.3%` | `28/28 = 100.0%` | 24/30 |
| `minimax-m3` | `17/30 = 56.7%` | `22/30 = 73.3%` | `18/25 = 72.0%` | 4/30 |

## 评分器混淆矩阵

| 模型 | 合同与人工均通过 | 假阴性 | 假阳性 | 合同与人工均失败 | 一致率 |
| --- | ---: | ---: | ---: | ---: | ---: |
| `gpt-5.5` | 14 | 14 | 0 | 2 | 53.3% |
| `minimax-m3` | 14 | 8 | 3 | 5 | 63.3% |

## 成对人工总体判定

- 两个模型都通过：`20/30`
- 两个模型都失败：`0/30`
- 只有 `gpt-5.5` 通过：`8/30`
- 只有 `minimax-m3` 通过：`2/30`

## 人工确认的真实失败

### gpt-5.5 (2)

- `attention_paper_ambiguous_001` [decision, task_fulfillment, overall]：The cited first-page passages exactly support the paper title and arXiv identifier 1706.03762v7, but the response assumes the user wants Attention Is All You Need and never clarifies whether they meant that paper or wanted identification, summary, recommendation, comparison, or another task.
- `agent_benchmark_ambiguous_001` [decision, task_fulfillment, overall]：The descriptions of tau-bench, GAIA, and ToolSandbox are accurate and supported by the papers, but the answer silently narrows an underspecified request to three benchmarks despite several materially different benchmark families in the corpus.

### minimax-m3 (8)

- `transformer_bert_confirmation_followup_001` [grounding, overall]：The comparison is substantively correct and complete, but the material claim that the original Transformer was trained with cross-entropy against the reference translation is not entailed by the cited excerpts, which establish parallel data and next-token probabilities but not the loss.
- `react_to_agent_evaluation_genealogy_001` [task_fulfillment, grounding, overall]：The core AgentBench and tau-bench links are supported, but the corpus-wide negative claims are false. MINT's Yao et al. (2022) citations resolve in its bibliography to ReAct and explicitly cover its Thought format and modified ALFWorld prompts; WebArena's Yao et al. (2022b) citation likewise resolves to ReAct and supports its CoT baseline. Thus A incorrectly dismisses directly verifiable relationships.
- `mint_vs_tau_interaction_comparison_001` [decision, task_fulfillment, overall]：The answer only reports a technical failure and provides none of the requested comparison.
- `cross_benchmark_human_agent_gap_001` [grounding, overall]：It makes the requested comparison and correctly computes the gap for the reported 11.70% result, but materially misdescribes that WebArena configuration as CoT prompting only: Table 2 shows 11.70% uses both CoT and the UA hint, while removing the UA hint produces the paper headline/best GPT-4 score of 14.41%. Calling 66.54 points the headline WebArena gap is therefore misleading.
- `webarena_reproduction_protocol_001` [task_fulfillment, grounding, overall]：The paper defines three information-seeking scoring functions, but this answer repeatedly claims there are only two and omits fuzzy_match from both the evaluation checklist and build order. It also adds unsupported reset()/step() method names and is substantially more detailed than the requested concise checklist.
- `transformer_to_bert_genealogy_001` [grounding, overall]：It establishes the direct encoder lineage, but incorrectly implies that BERT uses the original Transformer's three forms of multi-head attention; those include encoder-decoder and decoder attention absent from encoder-only BERT. It also overstates that BERT changed only training and input/output layers, overlooking documented architectural differences such as GELU versus ReLU, and misuses the unified-architecture citation, which compares BERT pre-training with BERT fine-tuning rather than BERT with Vaswani et al.
- `mint_tau_apparent_conflict_001` [grounding, overall]：It reaches the correct high-level conclusion, but materially misstates MINT as measuring monotonic gains relative to earlier turns on the same trajectory. MINT restarts evaluation for each interaction limit k and estimates an aggregate SR_k regression slope; its reported success rates are not always monotonic.
- `transformer_optimizer_reproduction_001` [task_fulfillment, grounding, overall]：The numerical settings are correct, but the strict checklist never names Adam in its optimizer field. It also claims without paper support that beta2 = 0.98 was chosen specifically for the warmup/inverse-square-root schedule.

## 边界

- 本报告不修改 Golden Case、Prompt、路径、Reading Model 或生产编排。
- 只有运行时或模型行为发生实质变化后，才需要重跑模型。
