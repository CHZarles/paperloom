# Current Product Eval Proposal

日期：2026-08-09

状态：Proposed

## 1. 决策

新版本的 Eval 分成两个独立被测对象：

```text
产品检索与精读
  = 给定查询，产品能否找到并读出正确证据

Agent
  = 在不提供正确查询或正确位置的前提下，是否会使用产品工具完成任务
```

两者不能用一个总分，也不能让 Agent Run 代替 Retrieval Eval。

在它们之前还有一个零模型调用的前置 Gate：当前 MinerU/Reading Model 是否仍包含每个已标注的
证据。它失败时，结论是数据链路失败，不是召回或 Agent 失败。

旧 Golden 的 `accepted_locations` 绑定旧 Reading Model 的 `page_ref`/`section_ref`，不能直接作为
Passage 新版本的质量基线。历史结果保留，但新版本单独建立 `current-product-v1` Baseline。

## 2. 被测链路

```text
固定 PDF 与人工语义证据目标
  -> 上传到真实产品
  -> Current READY Reading Model + Passage/Table/Figure 索引
  -> 评测快照                              [L0，不调用 LLM]
  -> Paper Discovery / Evidence Retrieval / Exact Read
                                                [L1-L3，不调用 Agent]
  -> 真实 Harness + 真实 Provider + 同一快照
                                                [L4-L6，Agent]
```

`PAGE` 和 `SECTION` 是结构和导航单位；新版本证据召回的正样本是 `PASSAGE`、`TABLE`、`FIGURE`。
Agent 可以在需要导航时读取 PAGE/SECTION，但它们不再被当作 Passage Retriever 的 Qrels。

## 3. 评测资产与快照

### 3.1 持久化的 Suite

新建受版本控制的 Suite，建议位置：

```text
research/golden-data/current-product/v1/suite.yaml
```

它只保存稳定的人工事实，不保存本机生成的 `paper_id`、`model_version` 或 `location_ref`：

```yaml
suite_id: current-product-v1
papers:
  - paper_key: transformer_2017
    source_pdf_sha256: "..."
    title: Attention Is All You Need

evidence_targets:
  transformer_adam:
    paper_key: transformer_2017
    element_types: [passage]
    expected_pages: [7]
    quote_needle: "beta1 = 0.9, beta2 = 0.98 and epsilon = 10^-9"

retrieval_probes:
  - probe_id: transformer_adam_en
    query_text: "Transformer Adam optimizer hyperparameters"
    oracle_paper_keys: [transformer_2017]
    required_targets: [transformer_adam]

agent_cases:
  - case_id: transformer_adam_question
    messages: [{role: user, content: "What optimizer settings did the Transformer use?"}]
    scope_paper_keys: [transformer_2017]
    expected_outcome: answered
    required_targets: [transformer_adam]
    answer_rubric: "Names Adam and all three values without unsupported explanation."
```

`quote_needle` 是人工确认的原文定位线索，经过空白、Unicode 和常见 OCR 归一化后匹配；它不是检索
Query。校验器必须拒绝检索 Query 包含完整答案值或完整 `quote_needle`，防止把答案泄漏给召回。

每个 Target 至少约束：论文、预期元素类型、页码范围和可在原文中确认的短文本。表格目标额外约束表格
caption/行文本；图片目标约束 caption。它们的用途是把人工语义标注重新绑定到一次新的 Reading Model，
不是要求 Agent 引用一个永久不变的 UUID。

### 3.2 一次运行的产品快照

上传完成后，由零模型调用的 `snapshot-audit` 生成不可覆盖的本地产物：

```text
research/golden-data/local-runs/current-product-<timestamp>/
  corpus-map.local.yaml
  corpus-snapshot.json
```

`corpus-map.local.yaml` 继续复用现有 `ProductCorpusMap`：稳定 `paper_key`/Golden ID 映射到本次上传
实际产生的 product `paper_id`，并且记录评测用户。它不提交到 Git。

`corpus-snapshot.json` 对每篇论文记录：

- product `paper_id`、Current READY `model_version`、parser 名称/版本；
- PDF hash、每个被测 Location 的 `content_hash`、Source Span hash、Qdrant `index_version`；
- 每个 `evidence_target` 本次解析得到的一个或多个可接受
  `passage_ref`/`table_ref`/`figure_ref`，以及页码和实际 element type。

快照的正样本 Location Ref 只在这一次 Baseline 中有效。重跑 MinerU、重建 Reading Model 或改变 Passage
构建规则后，必须重新生成快照；不能悄悄拿旧 Ref 继续跑并把所有 Miss 归因于检索。

### 3.3 L0：Corpus Contract Gate

`snapshot-audit` 经真实 Java Corpus API 读取每个已解析的 Target，并验证：

```text
Current READY model
AND quote_needle is present in canonical text
AND element type/page are acceptable
AND Source Span is non-empty and belongs to that model/content hash
AND a PDF evidence reference can be opened
```

任一 Target 不能绑定，L0 失败，后续 L1-L6 不运行。报告原因必须是 `parser_gap`、`model_not_current`、
`source_span_invalid`、`pdf_evidence_unavailable` 或 `target_not_found`，而不是 `retrieval_miss`。

## 4. 不经过 Agent 的产品 Eval

### L1：Paper Discovery

仅对需要从多篇 scope 中找论文的 Probe，直接调用 `search_paper_candidates`。输入为用户问题或身份线索，
不提供正确 Paper ID。

```text
Paper Recall@1/3/5, MRR, empty-result rate, technical-error count
```

单篇论文已由用户选中的 Case 不跑 L1；它们直接进入 L2。这避免把“用户明确打开了这篇论文”的任务错误地
按论文发现失败计分。

### L2：Evidence Retrieval

固定 Probe Query，显式给出 oracle paper scope，直接调用 `search_paper_content` 对应的 Java
`/internal/v1/corpus/locations/search`。不运行 Provider 或 Harness。

```text
hit@K(target) = Top K 中有 snapshot 中该 target 的可接受 Location
Target Recall@1/3/5/10
Probe Complete@K = 该 Probe 的全部 required_targets 都 hit
MRR = 每个 target 第一个 hit 的倒数排名均值
```

报告按 `PASSAGE`/`TABLE`/`FIGURE` 分组，并保存每个候选的 rank、paper、location ref、type、page、
scores、model/content hash 和 index version。技术错误、空结果、Current Model/Hash 不一致单独计数，
绝不折算成零召回。

这里不以 Precision/nDCG 为 Gate：当前 Qrels 只标记“可支持目标的证据”，未穷尽每篇论文全部等价证据，
未标注候选不能被直接判为不相关。

### L3：Exact Read And Evidence Reopen

对 L2 Top-K 的命中候选调用 `read_paper_content`/`locations/read`，验证：

```text
read content contains the target needle
AND returned source quote matches current model/content hash/span
AND source quote opens the expected PDF page/region
```

这层不评模型“会不会读”，而是评产品的候选到规范原文、Source Quote 和前端 PDF Evidence 的闭环。

L2 hit 但 L3 失败是 Read/Source Span 问题；L2 本身不是成功。这个结果必须独立显示为
`candidate_hit_read_failed`。

## 5. Agent Eval

### L4：Tool Policy 漏斗

使用 L0 的同一快照，运行真实 `LiveResearchChatHarness`、真实 Provider、真实 Java/Qdrant/MySQL 路径。
不提供 oracle Query、oracle Paper 或 oracle Location；不使用 Fixture 伪造 Tool Result。

每个 Required Target 从 Trace 中计算：

```text
paper_discovered
  -> target_returned
  -> target_read
  -> source_quote_cited
```

由此得到可诊断结论：

| 现象 | 归因 |
| --- | --- |
| L2 命中，Agent 未返回 Target | Agent 查询/检索策略问题 |
| Agent 返回 Target，未读 | Agent 选择或工具调用问题 |
| 已读，未引用 | 证据选择或回答提交问题 |
| 已引用，答案仍错 | 回答推理/表达问题 |
| L2 未命中 | 产品检索问题，不能归咎 Agent |

记录实际 Query、每种 Tool 调用次数、重复读、recoverable error/retry、总 token、模型调用次数和 wall
time。它们是效率和治理观测值，不是新的强制 token 上限。

### L5：Evidence Contract

这层只判断可验证事实：

- 引用的 Source Quote 是否在本 Run 已真实读取；
- Source Quote 是否属于授权 scope 和 Current Model；
- 引用是否可重开到 PDF；
- 回答中的 Required Target 是否有相应 Source Quote；
- 无证据的额外事实是否被写成确定结论。

它不要求命中旧 Golden 的精确 Anchor。新快照中同一 Target 的等价 Passage 都可接受。

### L6：Answer Quality

回答质量以 Case 的 `answer_rubric` 做人工小样本审核：

```text
outcome correctness
task fulfillment
factual correctness/completeness
grounding sufficiency
unsupported extrapolation
```

自动 Scorer 可保留为候选筛查和离线重评工具，但不能把 strict Anchor `hard_pass` 当作质量 Gate。已有
盲审已证明它会产生大量假阴性并可能反转模型排序。

最终报告保持三个一级列，而不是一个分数：

```text
retrieval_quality = L1 + L2 + L3
agent_execution   = L4 + L5
answer_quality    = L6
```

`strict_end_to_end` 仅可作为附加的发布合同：三列均通过且无技术错误。它不替代三列的诊断结果。

## 6. 最小 Suite 与运行节奏

第一轮只做 12 个 Case、3-5 篇真实上传论文，不重开旧 30 Case 项目：

| Case 类型 | 数量 | 必须覆盖的 Target |
| --- | ---: | --- |
| 单篇直接事实 | 3 | Passage |
| 实验/消融结论 | 2 | Table |
| 图或图注解释 | 1 | Figure |
| 段落级方法解释 | 2 | Passage，至少一题跨页 |
| 多论文比较 | 1 | 两篇论文的 Target |
| 连续对话追问 | 1 | 上一轮上下文 + 新证据 |
| 无证据/应 partial 或澄清 | 1 | 无正样本 |
| 私有 scope | 1 | scope 外论文绝不能读到 |

运行原则：

1. Parser、Passage、Qdrant、Source Span、MinerU 变化：先跑 L0-L3；L0 不通过立即停止。
2. 检索参数变化：跑全量 L2-L3，再按实际影响范围抽 3 个 Agent Case。
3. Agent prompt/tool/provider 变化：L0-L3 复用已冻结快照；跑全部 12 个 L4-L6。
4. 内测发布：完整跑一次并人工审核所有失败/partial，以及少量随机通过样本。

第一版每个 Case 每个模型只跑一次，明确标记为 Baseline，不声称稳定概率。比较模型或准备替换 prompt 时，
只对发生回归或高价值的 Case 做 3 次重复，报告每次结果和中位 token/耗时；不做无边界 Best-of-N。

## 7. 产物与 Gate

每次 Baseline 写入独立目录，绝不覆盖历史：

```text
corpus-snapshot.json
retrieval-report.json
agent-runs/<case_id>/{events.jsonl,result.json,harness_run.json,...}
agent-report.json
human-review.md
```

每份报告必须带：Suite hash、PDF hash、Corpus Map hash、Current Model/Parser/Index 身份、Java/Harness Git
revision、Provider/Model、Prompt/Tool Schema hash、执行时间和运行参数。

第一轮不设拍脑袋的百分比门槛。发布阻断条件是：

1. L0 全部通过，且 L1-L3 没有 Technical Error、Current Model/Hash/Span 不一致或 Scope Leak；
2. 已通过 Baseline 的 L2 Target 不得在相同预算下退化，任何新 Miss 都有人工归因；
3. L5 不得出现无授权读取、伪造/失效引用或不能打开的 PDF Evidence；
4. L6 的错误、partial 和模型差异都必须人工留下结论，不能用一个自动总分掩盖。

## 8. 最小实现顺序

复用现有 `ProductCorpusMap`、`GoldenJavaCorpusReader`、Java Corpus API、`retrieval-eval`、`agent-run` 和
Eval Dump，不重写第二套检索或 Agent Runner。

1. 增加 `current-product/v1/suite.yaml` 和 `snapshot-audit`：从语义 Target 生成当前 Snapshot，并完成 L0。
2. 将 `retrieval-eval` 扩展为读取 Snapshot Qrels，增加 L1、L3 和 Passage/Table/Figure 分组报告。
3. 将现有 `agent-run` 的真实 Run Artifact 接到 Snapshot 漏斗报告；不再用旧 `accepted_locations` 直接判
   Passage Agent 的成败。
4. 增加很薄的人工审核模板，完成第一份 12 Case Baseline 后再决定是否扩充数据集或调检索。

不在此阶段重写 Golden Schema、引入 LLM-as-a-judge 作为硬 Gate、增加 Reranker 对照、做全量多模型跑数，
或把评测论文混入普通用户 Library。
