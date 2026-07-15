---
title: 评估方法
description: PaperLoom 保存了哪些 Agent 数据，如何定位失败，以及这些数据如何支持优化与本地模型蒸馏。
---

# 评估方法

最终答案只能显示一轮运行有没有通过。Evaluation 将 Retriever、Agent Reading、Evidence Selection、
Citation、Outcome、Model Call 和 Tool Call 分开记录，用于定位失败阶段。

## Evidence Funnel

| 层 | 要回答的问题 |
| --- | --- |
| Candidate | Retriever 是否把要求的位置暴露给 Agent？ |
| Read | Agent 是否读取了这个位置并创建 Evidence？ |
| Cited | 最终答案是否引用了这份 Evidence？ |
| Substantive | 引用的是可支持 Claim 的正文、表格或图表内容，还是只有 Heading？ |
| Outcome | 系统应该回答、澄清、部分回答还是 Abstain？ |
| Hard Pass | Outcome、Content、Evidence、Citation 与 Trace 是否整体成立？ |

Candidate Recall 提升以后，Agent 仍可能跳过目标位置。它也可能读对论文却选错 Evidence，或者在
Comparison 里只引用其中一方。分层结果可以区分 Retriever、Tool Policy 和 Submission Gate 的问题。

## Harness 具体保存什么

通过 `EVAL_DUMP_DIR` 或 `--eval-dump` 开启 Per-run Capture 后，每个 Run 会写出 Append-only
`events.jsonl` 和 Atomic `result.json`。

| 数据层 | 保存内容 |
| --- | --- |
| Run Input | Question、Conversation、Turn、Scope Paper IDs、Previous Evidence、Corpus IDs |
| Model Transport | 脱敏 Header、Request / Response Body、Status、Error、Retry Attempt |
| Tool Call | Tool Name、Raw / Parsed Arguments、Start / Complete / Error |
| Tool Result | Internal Result 与 Model-visible Result，避免把观测面和模型面混在一起 |
| Authorization | Tool 前后的 Disclosed Paper、Disclosed Location 与 Evidence State |
| Final Submission | Draft、Outcome、Citations、Accepted / Rejected 与 Validation Error |
| Run Result | Evidence Ledger、Citation Validation、Trace、Token、Latency、Finish Reason 与 Failure |

Event 按 Sequence 排序，并用 Event ID 去重。每条写入都会 Flush / `fsync`，最终 Result 通过临时文件
原子替换。Capture 失败不会改变 Product Answer。

Authorization、Cookie 与 API Key Header 会被删除，但 Body 仍可能包含用户问题、论文内容、Prompt
和模型输出，因此这些文件是敏感本地数据，不是可以直接公开的数据集。

## Offline Artifact

Golden Case Runner 还会保存 `harness_run`、`research_answer`、`evidence_ledger`、
`citation_validation`、`react_trace`、`paper_candidates`、`conversation_state` 与 `score_report`。

Raw Fact 与 Derived Score 分开保存。以后改变 Scoring Policy 时，过去的 Run 可以直接重算，无需再次
调用模型。

## Evidence-first Golden Case

Golden Case 允许答案使用不同措辞，但会定义这些约束：

- Conversation Messages 与 Fixed Paper Pack；
- Required / Forbidden Paper；
- Required / Forbidden Evidence Anchor；
- Expected Fact 与 Claim Obligation；
- Expected Outcome 与 Citation Policy；
- Answer Contract 与 Visible Trace Obligation；
- Human Label 与 Judge Calibration Result。

这些约束保留答案表达空间，同时支持比较不同模型的 Evidence、Behavior 和 Outcome。

## 数据用途

### 优化当前 BM25 Retrieval

Query、Candidate、Anchor、Read 与 Cited 之间的差异可以回答：

- Query 是否过宽或缺少关键术语；
- Section / Lead / Passage Weight 是否合理；
- Adjacent Paragraph 是否帮助了真实 Evidence；
- Multi-paper Coverage 是否只照顾了第一篇论文；
- Retriever 找到了，但 Agent 为什么没有读。

### 训练 Dense Retriever 或 Reranker

通过校验的 Query-Location Read 可以作为 Positive Pair。已经公开但被跳过、被提交校验拒绝，
或被 Human Label 标记为弱的 Location，经过筛选后可以形成 Hard Negative。

Dense Retrieval 是否有效，需要同时检查 Held-out Candidate、Read、Cited、Hard Pass、Latency 与 Cost。
Offline Similarity 只覆盖离线排序的一部分表现。

### 优化 Agent Tool Policy

Observable Trajectory 可以揭示 Agent 何时：

- Search 太宽却不 Reformulate；
- 重复读取相同 Location；
- 在 Comparison 中漏掉一篇论文；
- 读取了 Evidence 却没有引用；
- Final Submission 太早；
- 被 Validator 拒绝后能否正确修复。

这些记录可以用于调整 Prompt、Tool Description、Budget 和 Deterministic Gate，也可以整理成监督
数据，训练较小的 Tool Policy Model。

### Provider 与 Cost Routing

Provider 比较同时检查 Answer Score、Tool Adherence、Retry、Technical Failure、Token、Latency 与
Correction Ability。数据足够后，可以按 Question Complexity 路由模型，避免所有请求都使用成本最高的
Provider。

### Judge Calibration

Human Label 与 Deterministic Validator 可以测量 Judge 的 False Positive、False Negative 和 Threshold
Stability。Judge 是补充评分层，不能替代 Permission、Evidence ID 和 Citation 的硬校验。

### 用强 API 蒸馏本地小模型

强第三方 API 可以作为 Teacher。训练集只保留通过 Gate、必要时经过 Human Review 的样本。可用的
监督信号包括：

- Accepted User-visible Answer；
- Structured Outcome 与 Fields；
- Observable Tool Call 与 Arguments；
- Teacher 当时看得到的 Tool Result；
- Selected Evidence ID 与 Citation Placement；
- Final Submission 被拒绝后的 Correction Attempt。

训练数据只包含可观察 Action 和 Accepted Output，不采集 Hidden Chain-of-thought。导出前还要过滤
Technical Failure、Secret、Personal Data 与 Invalid Trajectory，检查 Paper / Provider License，去重，
并按 Paper、Conversation 和 Question Family 隔离 Train / Validation / Test，防止 Leakage。

## 当前限制

- Golden Anchor 是人工定义的 Evaluation View，不等同于所有合理 Evidence。
- Judge Model 需要持续校准，不能取代 Deterministic Protocol Check。
- Offline Case 不能覆盖真实用户的所有 Follow-up。
- Eval Dump 的内容敏感，不能因为“用于训练”就跳过 Privacy 和 License Review。
- 更强的模型、更多 Sampling 或更高 Candidate Count 仍要经过同一套 Product Quality 回归。

## 相关实践

- [Golden Data 与 Harness 演化](/practice/evaluation/golden-data-harness-evolution)
- [Best-of-2 Agent 编排实验](/practice/evaluation/best-of-two-agent-orchestration)
- [Provider 迁移实验](/practice/evaluation/provider-migration-experiment)
