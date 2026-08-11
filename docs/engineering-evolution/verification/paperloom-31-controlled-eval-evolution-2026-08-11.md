# PaperLoom-31 Controlled Eval 优化记录

日期：2026-08-11  
状态：Case Layout v4 已验证；发现一个真实的 Evidence Selection 失败，待修复 Search Preview。

## 1. 目标

本轮不是用 Benchmark 证明“模型永远正确”，而是把以下问题分开测量：

```text
Corpus 是否准备完成                  L0
普通用户是否只能访问公开语料        G0
论文身份是否能召回                  L1
目标 Source Span 是否能召回         L2
Agent 是否遵守 Contract/Protocol    L3 deterministic
答案是否正确且被引文蕴含            L3 semantic Judge
耗时、模型调用和 Token              Performance
```

Agent 与 Judge 均使用 `MiniMax-M3`，但由独立请求和不同 Prompt 执行。Judge Prompt 为
`paperloom-agent-judge-v1`。这能提供自动回归信号，但不等于使用独立模型裁判；结果仍需结合冻结的
Question、Expected Answer Span、Trace 和 Source Quote 审计。

## 2. Case Layout v3 基线

Snapshot：`e78ca9aa446d50f49de65e5ecc76acf7994530de7a9c70dea4c35126de7b1666`  
Run：`20260811T101156Z-1ad2869d`  
代码：`5ad2ec8`

结果：

| 指标 | 结果 |
|---|---:|
| L1 Recall@1 | 1.0000 |
| L2 Recall@5 | 0.6452 |
| L2 Recall@10 | 0.7419 |
| L3 Contract Accuracy | 1.0000 |
| L3 Protocol Replay Pass Rate | 1.0000 |
| L3 Provenance Pass Rate | 1.0000 |
| Agent Model Calls | 96 |
| Agent Total Tokens | 1,165,512 |
| Agent 累计耗时 | 825,954 ms |

### 2.1 发现：开放式推荐 Case 使用了错误 Oracle

Case `research_llm_principles_01` 的问题是：

> 推荐和大语言模型原理相关的论文，并说明推荐理由。

v3 却将它绑定到《Attention Is All You Need》当前自动 Target 的唯一 Expected Answer。该 Target
恰好描述 WMT 训练数据、词表和 Batch 大小。于是实际回答即使推荐多篇相关论文并提供有效引用，
Judge 仍会因为没有回答这组无关事实而判 `FAIL`。

形式化地说：

```text
Question domain = open-ended recommendation
Oracle domain   = one atomic fact from one predetermined paper
Question domain != Oracle domain
```

这是 Benchmark 定义错误，不是 Agent 产品错误。

## 3. Case Layout v4 修正

提交：`30110d8 fix(eval): judge open-ended recommendations on their own contract`

修改后的推荐 Case：

```text
required_target_ids = []
answer_spans        = []
citation_policy     = cite_recommendation_reasons
```

Judge 不再要求命中某篇预定论文，而是检查：

```text
Answer Quality = 推荐论文与主题直接相关
                 AND 每项推荐包含实质性理由

Grounding      = 重要推荐理由被实际引用的 Source Quote 支持
```

Runtime 的确定性要求没有放松：只要 `RESEARCH` Case 以 `answered` 完成，就必须引用本 Run 已读取、
可解析且通过 Block Binding 校验的 Source Quote。

## 4. Case Layout v4 实测

Snapshot：`c818d91e8a1a68f7e6216f7b8b152fb0ac9b2fe06a9fa6a090a32ca1fb9bee96`  
Run：`20260811T120216Z-f18bc263`  
代码：`30110d8`

结果：

| 指标 | 结果 |
|---|---:|
| L1 Recall@1 | 1.0000 |
| L2 Recall@5 | 0.5484 |
| L2 Recall@10 | 0.7097 |
| L3 Deterministic Hard Pass | 16 / 16 |
| 推荐 Case Answer Quality | PASS |
| 推荐 Case Grounding | PASS |
| Agent Model Calls | 101 |
| Agent Total Tokens | 1,836,352 |
| Agent 累计耗时 | 1,001,614 ms |

推荐 Case 本身推荐 7 篇论文、使用 9 个 Source Quote，耗时 267,915 ms，调用模型 18 次，消耗
638,648 tokens。该结果证明评测契约已经修正；同时也将开放式 Deep Research 的成本暴露为后续性能基线。
由于两次 Snapshot 的自动 Target 和两次 Agent 轨迹并不相同，不能只凭这两个样本把 Token 差异归因于
v4 代码回归。

## 5. 新发现：comparison_04 是真实产品失败

问题的第二部分要求从 Codex 论文读取：

```text
HumanEval
Codex-12B
random samples per problem = 8
temperature = 0.8
```

Agent 回答为 `100 samples` 和 `temperature 0.8`。其中 `100 samples` 来自 Section 4.3 的训练问题
过滤流程，不属于 HumanEval；Agent 将两个不同上下文中的数值错误拼接。

### 5.1 分层定位

| 层级 | 观测 | 结论 |
|---|---|---|
| L2 Retrieval | 正确 Location 排名第 1，`returned=true` | 不是 Qdrant 漏召回 |
| Search Preview | 预览显示前面的 pass@k 公式，没有显示位于字符 914 的正确句子 | 导航信息误导 Agent |
| Evidence Read | 正确 Location `read=false` | Agent 没有读取正确正文 |
| Evidence Read | 读取了“100 samples per curated problem”和“temperature 0.8”两个 Location | 证据上下文被错误合并 |
| Protocol | 引用均存在且可解析 | 结构协议按设计通过 |
| Semantic Judge | Answer Quality 与 Grounding 均为 `FAIL` | Judge 正确发现事实及蕴含错误 |

正确 Location：

```text
passage_ref_669433f6c7e2081a0f2620e51629281e64fdfa0f89c0be55883f64df59d69e03
```

其正文长度为 2,342 字符，正确答案从字符 914 开始。`search_paper_content` 已把它排在第一位，
但当前 Preview 没有展示查询最相关的窗口。

### 5.2 根因

`SearchText.preview` 当前计算方式是：

```text
matchIndex = min(indexOf(token) for token in queryTokens)
```

查询包含 `HumanEval Codex-12B random samples temperature`。正文前部较早出现了通用词 `samples`，
Preview 因而围绕第一次 `samples` 截取，而不是选择同时覆盖 `HumanEval`、`Codex-12B`、`random`、
`samples` 和 `temperature` 的窗口。检索排序正确，但展示给 Agent 的导航摘要不正确。

## 6. 解决方案

先只做一个根因修复，不增加新 Tool 或状态：

```text
SearchText.preview(content, queryTokens, maxLength)
    -> 枚举包含查询 Token 的候选窗口
    -> 选择 distinct query token coverage 最大的窗口
    -> coverage 相同时选择更早的窗口
```

该修改位于现有共享函数 `SearchText.preview`，不改变 Corpus API、Tool Schema、数据库或 Agent 状态机。
对于本 Case，包含正确句子的窗口覆盖全部关键 Token，而前部公式窗口只覆盖 `samples`，因此会稳定选择
正确导航摘要。

只增加一个回归测试：输入同时包含“较早的通用 samples”和“较后的完整 HumanEval 句子”，断言 Preview
包含 `8 random samples per problem` 与 `temperature 0.8`。然后重跑 `comparison_04` 所在完整 Benchmark。

现有 `precision_fact_extraction` Skill 已要求校验数值、单位和来源上下文。暂不增加新的事实槽状态机或
第二次语义校验调用；只有 Preview 修复后仍出现跨上下文拼接，才有证据强化 Skill 为“多个限定词和数值
必须由同一个 Source Quote 共同支持”。

## 7. Gate 的准确解释

当前 `internal_beta_gate.passed` 表示确定性的 Runtime 安全条件通过：Contract、Protocol Replay、
Provenance、Scope 和 Citation Resolution 没有硬失败。它不表示所有语义 Judge 都为 `PASS`。

因此报告必须同时读取：

```text
internal_beta_gate       Runtime 是否可控
judge.answer_quality     答案是否完成任务
judge.grounding          结论是否被引用证据蕴含
usage                    成本和时延是否退化
```

首轮仍以建立基线为目的，不在没有重复样本分布的情况下设置综合分或武断阈值。
