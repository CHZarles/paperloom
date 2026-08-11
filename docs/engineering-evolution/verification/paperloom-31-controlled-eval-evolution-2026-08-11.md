# PaperLoom-31 Controlled Eval 优化记录

日期：2026-08-11  
状态：Search Preview 已修复；第三次实测推翻了 comparison_04 的单一 Oracle，并暴露推荐 Contract 不稳定。

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

## 5. 第二次 Run 的初步判断：comparison_04 Evidence Selection 失败

问题的第二部分要求从 Codex 论文读取：

```text
HumanEval
Codex-12B
random samples per problem = 8
temperature = 0.8
```

Agent 回答为 `100 samples` 和 `temperature 0.8`。其中 `100 samples` 来自 Section 4.3 的训练问题
过滤流程，不属于 HumanEval；Agent 将两个不同上下文中的数值错误拼接。

这是根据冻结 Target 和第二次 Run Judge 得出的初步判断。第三次 Run 找到了论文中另一处直接支持
`100 samples per problem at temperature 0.8` 的 Figure 1 图注，证明当前问题存在两种合理解释；见第 8 节。

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

### 6.1 直观解释

当前正文中存在两个可能的 500 字符窗口：

```text
窗口 A：pass@k 公式 ... samples ...
覆盖查询词：samples
得分：1

窗口 B：HumanEval ... Codex-12B ... 8 random samples per problem ... temperature 0.8
覆盖查询词：HumanEval、Codex-12B、random、samples、temperature
得分：5
```

旧算法选择“最早出现任意查询词”的窗口，因此选中 A。新算法选择“覆盖不同查询词最多”的窗口，
因此选中 B。Agent 随后能在 Search Result 中直接看到正确上下文，再调用 `read_paper_content` 读取并引用
该 Location，而不是从两个无关段落拼接数字。

这不是修改检索分数。Qdrant 已经把正确 Location 排在第一名；修改的是第一名结果展示给 Agent 的
导航摘要。

### 6.2 验收条件

```text
SearchText 单元回归：
  Preview 包含 "8 random samples per problem"
  Preview 包含 "temperature 0.8"
  Preview 不再停留在前部 pass@k 公式

PaperLoom-31 comparison_04：
  target_paper_14_codex.returned = true
  target_paper_14_codex.read = true
  target_paper_14_codex.cited = true
  answer 包含 samples_per_problem = 8
  answer 包含 temperature = 0.8
  Judge.answer_quality = PASS
  Judge.grounding = PASS
```

第三次 Run 证明上述 PaperLoom-31 条件中的固定答案 `8` 不能直接作为验收标准：当前 Question 没有说明
它询问的是附录 B 的示例展示，而不是论文总体 HumanEval 评测设置。Preview 的单元验收仍然有效；完整
Case 必须先补齐上下文限定词，再用于端到端验收。

只增加一个回归测试：输入同时包含“较早的通用 samples”和“较后的完整 HumanEval 句子”，断言 Preview
包含 `8 random samples per problem` 与 `temperature 0.8`。然后重跑 `comparison_04` 所在完整 Benchmark。

现有 `precision_fact_extraction` Skill 已要求校验数值、单位和来源上下文。暂不增加新的事实槽状态机或
第二次语义校验调用；只有 Preview 修复后仍出现跨上下文拼接，才有证据强化 Skill 为“多个限定词和数值
必须由同一个 Source Quote 共同支持”。

实现结果：`SearchText.preview` 已改为选择 Query Token 覆盖最多的窗口；聚焦回归
`SearchTextTest.previewChoosesWindowWithTheMostDistinctQueryTokens` 已通过。未增加依赖或 API 字段。

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

## 8. Preview 修复后的第三次 Run

Snapshot：`c818d91e8a1a68f7e6216f7b8b152fb0ac9b2fe06a9fa6a090a32ca1fb9bee96`

Run：`20260811T124853Z-c7adfbe2`

代码：`5fcd40e`

确定性结果：L1 Recall@1 为 1.0，L2 Recall@5 为 0.5484，L3 Hard Pass 为 15/16。

### 8.1 comparison_04 推翻了原单一 Oracle

Agent 回答 `100 samples per problem at temperature 0.8`，并引用 Figure 1 图注。第三次 Judge 将
Answer Quality 和 Grounding 均判为 `PASS`，理由是：

```text
100 samples per problem at temperature 0.8
  = 论文总体 HumanEval 生成/评测设置

8 random samples per problem at temperature 0.8
  = 附录 B 为 8 个随机问题展示的示例输出数量
```

当前 Question 只写“文中给出的代码生成设置”，没有“附录 B”“示例展示”或“总体评测协议”等限定词。
因此 `8` 和 `100` 分别对应两个真实但不同的上下文。第二次 Run 的 `FAIL` 与第三次 Run 的 `PASS`
不是 Preview 修复带来的答案对错翻转，而是 Benchmark Question 丢失了 Target 的上下文限定词。

形式化地说：

```text
Target Span = 附录 B 的 illustrative setting
Question    = 未限定范围的 general setting
Oracle      = 8

存在另一 Source Span：general setting = 100
因此 Question -> Oracle 不是单值函数
```

这类 Case 不能用于判断 Agent 是否命中唯一事实。自动问题生成规则需要保留 Source Span 中决定语义范围的
章节、图表、示例/总体设置等限定词，而不是只保留数值和实体。

实现：Query Generator 和现有 Grounding Verifier 均已加入范围保真规则，Prompt 版本升级为
`paperloom-query-generator-v3`。Snapshot 创建现在会校验 Config Prompt Version，拒绝身份不一致的生成配置。

### 8.2 推荐 Case 暴露 Contract 不稳定

`research_llm_principles_01` 在第二次 Run 中执行 Research 并给出 7 篇推荐；第三次 Run 却在第一次模型
调用直接提交 `DIRECT / needs_clarification`，询问用户希望聚焦哪个子方向。结果：

```text
expected_contract = RESEARCH
actual_contract   = DIRECT
model_calls       = 1
tool_calls        = 0
```

这是 Agent Contract 选择不稳定，不是技术错误。问题已经给出“大语言模型原理”这一主题，产品预期是直接
执行 Deep Research；澄清可以改善范围，但不应替代用户明确请求的推荐任务。

### 8.3 修正后的下一步

```text
1. 保留 SearchText.preview 修复及其单元回归。
2. 修正自动问题生成规则：必须保留 Target 的语义范围限定词。
3. 重建 Snapshot，确认 comparison Case 不再存在多解。
4. 再评估推荐问题的 Contract 稳定性，不把两个问题混在一次修复中。
```

## 9. Eval 版本与 Snapshot 生命周期

一个 Snapshot 是不可修改的冻结试卷，但不是永久使用的唯一试卷。

```text
Benchmark Dataset
  paperloom-31-v1
  表示长期评测项目和 31 篇 PDF 的身份

Snapshot
  表示一次冻结的 Question、Expected Answer、Source Span、Case Layout、
  Generator、Prompt 和 Reading Model 身份

Run
  表示某个 Agent/Harness/Model/Index 在指定 Snapshot 上的一次作答
```

同一 Snapshot 的有效期内，所有回归 Run 必须复用它，不能每次重新生成问题。这样 Agent Prompt、Harness、
模型、检索算法或性能优化前后的结果才使用同一张试卷。

以下变化不重建 Snapshot：

```text
Agent Prompt 或 Tool 使用策略变化
Harness/Protocol 实现变化
被测回答模型变化
不改变 Reading Model/Index Contract 的查询或 Preview 实现变化
性能优化
```

这些都是被测对象，应该在同一 Snapshot 上做前后对比。

以下变化必须发布新 Snapshot：

```text
PDF 内容或清单变化
MinerU/Reading Model 导致 Source Span 身份变化
Retrieval Index Contract 变化
Target 选择规则变化
Question Generator 或 Prompt Version 变化
Case Layout 或 Snapshot Schema 变化
发现冻结 Question 存在歧义、错误 Oracle 或证据失效
```

发布新 Snapshot 后：

```text
旧 Snapshot 和旧 Run 保留，不覆盖、不迁移
新 Snapshot 先建立自己的 Baseline
不同 Snapshot 的绝对分数不直接宣称为代码回归
后续修改继续复用新 Snapshot，直到再次触发换版条件
```

因此准确说法是：

> 一个版本不是永久使用，而是在其语料、问题和证据契约仍然有效时持续复用；换版必须有明确原因，
> 换版后重新建立基线。

当前 `c818...` Snapshot 因 Query Generator 从 v2 升到 v3 且已确认存在歧义 Question，只作为历史记录；
下一次 v3 Snapshot 通过检查后，才成为新的回归基线。

## 10. Query Generator v3 Snapshot

Snapshot：`407768f029f38ad4fa362d006d31da38b68aece42c1a3428b504d9ae8808112a`

Generator 身份：

```text
provider            = minimax
model               = MiniMax-M3
prompt_version      = paperloom-query-generator-v3
case_layout_version = paperloom-agent-case-layout-v4
```

原歧义 Codex Target 重新生成的问题为：

> 附录B中展示的HumanEval随机问题及对应Codex-12B样本，其生成所使用的温度参数是多少？

该问题明确保留“附录 B 中展示”的范围，只询问温度 `0.8`，不再把附录示例的 `8 samples` 与总体评测的
`100 samples` 放入同一个未限定问题。Snapshot 结构校验通过：31 个 Target、16 个 Agent Case，Target
类型仍为 22 Passage、6 Table、3 Figure。

### 10.1 首次全链路 Run

Run：`20260811T134147Z-ebfe203a`

```text
baseline.established = true
internal_beta_gate   = false
L1 Recall@1          = 1.0000
L2 Recall@5          = 0.7097
L2 Recall@10         = 0.8387
L3 Hard Pass         = 14 / 16
Agent Model Calls    = 86
Agent Total Tokens   = 763,849
```

`comparison_04` 已满足修正目标：两个 Target 均 `returned/read/cited=true`，回答附录 B 的温度为 `0.8`，
Answer Quality 与 Grounding 均为 `PASS`。这证明 Query Scope 和 Preview 两项修复在真实链路中生效。

本轮确定性 Gate 失败来自两个独立 Agent Contract 问题：

```text
follow_up_01
  expected = RESEARCH
  actual   = DIRECT / needs_clarification

research_llm_principles_01
  expected = RESEARCH
  actual   = CATALOG / answered
```

推荐 Case 先执行多次 Paper Candidate Search，随后尝试 `submit_catalog_answer`；该提交把 Protocol 锁定到
CATALOG Repair，后续正文工具被正确拒绝，最终只返回论文标题而没有推荐理由和 Source Quote。此前同一问题
还出现过 `DIRECT / needs_clarification`，因此已经有重复证据证明 Contract 选择不稳定。

语义 Judge 另发现 `comparison_02` 的 Answer Quality 为 `PASS`、Grounding 为 `FAIL`：GPT-3 Target 已召回，
但没有正式读取或引用；回答使用了未被 Source Quote 支持的 Figure 3.13 细节。该问题不计入本轮 Contract
根因，后续单独处理。

该 Snapshot 可以作为包含已知失败的冻结回归 Baseline，因为五个 Stage 已完整执行；它不是一个通过
Internal Beta Gate 的发布基线。下一步不再重建试卷，而是在同一 Snapshot 上修复和比较 Agent 行为。

## 11. Contract 失败的 Trace 诊断

### 11.1 follow_up_01 是 Benchmark 上下文错误

Trace 证明完整 Model Input 已包含 Snapshot 中冻结的历史：

```text
user:      该论文发表于哪个会议？
assistant: Published as a conference paper at ICLR 2022
user:      请为刚才的结论提供对应论文中的原文证据，并保留引用。
```

历史没有出现论文标题、Paper ID 或其他可解析身份。“该论文”没有先行词，Agent 无法知道 Expected Target
是 FLAN。它先执行 Corpus Inventory，仍无法从 31 篇论文中唯一解析对象，随后提交
`DIRECT / needs_clarification`。这是合理行为。

因此该失败不是 Conversation History 丢失，也不是 Contract Router 错误，而是 Benchmark 构造时只写入
Target Query 和 Expected Answer，却遗漏了建立代词指代所需的论文身份。正式修正应让历史首问使用与
Single Paper Case 相同的标题限定，例如：

```text
请依据《FINETUNED LANGUAGE MODELS ARE ZERO-SHOT LEARNERS》回答：该论文发表于哪个会议？
```

### 11.2 research_llm_principles_01 是真实 Contract 选择失败

Model Input 中的问题完整且主题明确：

> 推荐和大语言模型原理相关的论文，并说明推荐理由。

System Prompt 已规定 Metadata 不支持方法、发现、重要性或技术贡献，并规定 Paper Content Judgment 使用
RESEARCH。但它没有用一个直接的 Contract 规则声明“带推荐理由的论文推荐属于 RESEARCH，而不是
metadata-only list”。本轮模型执行了多次 Paper Candidate Search，随后主动调用
`submit_catalog_answer`。

Protocol Trace：

```text
ACTIVE(contract=None)
  -> submit_catalog_answer
  -> payload 因 Paper ID/Result Ref 不一致被拒绝
  -> REPAIR(contract=CATALOG)
  -> 正文读取工具全部被 ACTION_NOT_ALLOWED 拒绝
  -> 修正 Catalog Payload
  -> COMPLETE(contract=CATALOG)
```

Protocol Guard 行为符合设计：第一次 Submission 将 Contract 固定为 CATALOG，Repair 期间禁止切换协议。
真正的问题发生在 Guard 之前：Runtime 没有一个独立、可校验的 Request Contract；它只能相信模型最终选择
哪个 Submission Tool。因此状态机可以保证“选定 Contract 后按协议完成”，却不能保证“选择的 Contract
符合用户意图”。

### 11.3 已排除与保留的假设

```text
已排除：Conversation History 没有传给模型
  证据：model.request 中存在全部三条历史消息

已排除：Protocol 在模型不知情时把 RESEARCH 改成 CATALOG
  证据：模型首先主动调用 submit_catalog_answer

确认：follow_up Case 本身缺少可解析的论文先行词
  证据：冻结 History 从未出现论文身份

确认：推荐 Case 的 Contract 由最终 Submission Tool 隐式决定
  证据：ACTIVE 初始 contract=None；首次 Submission 后锁定 CATALOG

待设计：在 Agent 执行前显式确定 Request Contract，或继续依赖 Prompt 软约束
```

这两个失败不能用同一个补丁处理：Follow-up 应修 Benchmark Context；推荐 Case 需要决定 Runtime Contract
是否从“模型最终提交时隐式选择”演进为“研究执行前显式选择并记录”。
