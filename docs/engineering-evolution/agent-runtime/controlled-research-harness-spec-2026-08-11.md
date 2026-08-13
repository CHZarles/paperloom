# Controlled Research Harness Specification

> Status: Accepted for implementation on 2026-08-11. Not yet implemented.
>
> Design source: [`controlled-research-harness-protocol-2026-08-11.md`](./controlled-research-harness-protocol-2026-08-11.md)
>
> Related execution contract: [`governed-research-run-spec.md`](../../architecture/governed-research-run-spec.md)

本文使用 **MUST** 表示实现和验收必须满足的要求，使用 **MUST NOT** 表示明确禁止的行为。设计来源文档解释为什么这样设计；本规范定义实现必须表现出的行为。

## 1. Scope

本规范定义 PaperLoom 在线 Research Harness 的**答案发布协议**：一次对话 Turn 可以发布哪类答案、发布前必须满足什么条件，以及提交被拒绝后允许执行什么动作。

### Normative requirements

| ID | Requirement |
| --- | --- |
| `CRH-SCOPE-001` | 每个可发布答案 MUST 且只能选择一个 Answer Contract：`DIRECT`、`CATALOG` 或 `RESEARCH`。 |
| `CRH-SCOPE-002` | Research Protocol MUST 只决定答案形状、可验证的来源追溯条件和拒绝后的合法动作。 |
| `CRH-SCOPE-003` | Research Protocol MUST NOT 声称能够确定性判断 Source Span 是否蕴含结论、推荐是否充分或用户任务是否完整完成；这些语义质量由离线 Eval 测量。 |
| `CRH-SCOPE-004` | Research Protocol MUST NOT 复制或接管 Corpus 授权状态、Exact Read 校验、超时、取消和技术故障处置；这些职责继续由现有 Corpus 与 Execution Control 合同拥有。 |
| `CRH-SCOPE-005` | 一个 Model Response MAY 请求多个非提交 Tool；Runtime MUST 按响应中的稳定顺序逐个执行，并在每次执行前使用最新 Protocol/Corpus State 校验该动作。 |
| `CRH-SCOPE-006` | 任一答案提交 Tool MUST 是其 Model Response 中唯一的 Tool Call；同组存在其他 Tool Call 时，该提交 MUST 在产生外部副作用前被拒绝。 |

### Out of scope

- 新增在线 LLM Router、在线 Judge 或第二个 Agent；
- 固定论文数量、Tool Call 数量或研究轮数；
- 提高 Function Tool 的实际执行并发度；首版继续使用 `max_function_tool_concurrency=1`，本规范允许模型一次规划多个 Tool，但不并发修改请求级状态；
- 修改 Java Corpus API、前端、MySQL 或 ConversationState；
- 用本协议替代现有 Paper、Location、Source Quote 授权链或 Run 执行限制。

## 2. State Model

一次在线 Research Run 的可治理状态由三个职责互斥的状态组成：

```text
RunState = (ProtocolState, CorpusState, ExecutionState)

ProtocolState = (
  phase,
  contract,
  submission_attempt,
  validation_issues
)

phase = ACTIVE | REPAIR | COMPLETE
contract = null | DIRECT | CATALOG | RESEARCH
```

`CorpusState` 是对现有 `ReadingCorpusTools` 请求级状态的概念名称，不要求新增一个平行的 State Class：

```text
CorpusState = (
  authorized_papers,     # 已由候选搜索或身份解析返回给 Agent 的 Paper ID
  disclosed_locations,  # 已由内容搜索或结构读取返回给 Agent 的 Location Ref
  source_quotes          # 已由 Exact Read 返回、可供答案绑定的 Source Quote
)
```

它对应现有字段 `authorized_paper_ids`、`disclosed_location_refs` 和 `observations_by_evidence_id`。一次 Run 内，Agent 只能沿以下授权链扩大这些集合：

```text
Discover Paper -> Authorize Paper
Locate Content -> Disclose Location
Exact Read     -> Create Source Quote
```

`Source Quote` 是现有 Reading Model 中的**可引用原文对象**，不是 Agent 任意复制的一段文字。逻辑上它至少关联：

```text
SourceQuote = (
  source_quote_ref,  # 稳定引用身份
  paper_id,
  location_ref,
  source_span,       # MinerU Reading Model 中的原文范围
  span_text,
  page/section/visual_regions
)
```

`read_paper_content` 对已披露 Location 完成 Exact Read 后，才能创建或复用 Source Quote 并把 `source_quote_ref` 返回给 Agent。Search Preview、Paper Card 和 Location Preview 都不是 Source Quote，不能被最终答案引用。Agent 可以用自己的语言总结原文；`source_quote_ref` 表示该回答块绑定到哪条可回到 PDF 位置的原文证据，并不要求把 `span_text` 逐字复制到答案中。

`CorpusState` 不是 Qdrant/MySQL 中保存的论文数据，也不是论文的 public/private 权限；它只记录**本次 Run 中 Agent 已被合法展示和读取过什么**。三个集合只增不减，并在 Run 结束时销毁；只有最终答案已接受的 Source Quote 才能通过现有 Conversation Memory 合同跨 Turn 保留。

`CorpusState` 继续由 `ReadingCorpusTools` 唯一拥有。`ExecutionState` 继续由现有 `RunControl` 和 Live Harness 拥有取消、Deadline 和技术终态。`ProtocolState` 不复制这两类状态，只在决策时读取其必要投影。

初始状态为：

```text
ProtocolState(
  phase = ACTIVE,
  contract = null,
  submission_attempt = 0,
  validation_issues = []
)
```

### Normative requirements

| ID | Requirement |
| --- | --- |
| `CRH-STATE-001` | `ProtocolState` MUST 是请求级、可序列化的数据；它 MUST NOT 从 Prompt 文本或 Trace 文本反向推断。 |
| `CRH-STATE-002` | `ProtocolState`、`CorpusState` 和 `ExecutionState` MUST 各自只有一个可变 Owner；实现 MUST NOT 在其他模块维护可独立变化的副本。 |
| `CRH-STATE-003` | `contract=null` 只允许存在于首次提交事件被处理之前；该提交无论被接受还是拒绝，MUST 把 Contract 固定为对应提交 Tool 的 Contract。 |
| `CRH-STATE-004` | Contract 一旦固定，后续提交 MUST 使用同一 Contract，直到 Run 到达 `COMPLETE` 或 Execution Terminal State。 |
| `CRH-STATE-005` | `COMPLETE` MUST 是 Protocol 的终态；进入后不得再执行 Agent Tool。 |

## 3. Answer Contracts

一次 Turn 只能通过下列一种 Contract 发布答案：

| Contract | 允许表达的内容 | 提交 Tool | 发布来源 |
| --- | --- | --- | --- |
| `DIRECT` | 问候、一个阻塞性澄清问题、能力说明、超范围响应 | `submit_direct_answer` | Runtime 固定模板或受限 question 字段 |
| `CATALOG` | 语料数量、论文身份、标题、作者、年份、Venue、DOI、arXiv ID | `submit_catalog_answer` | 当前 Turn 的权威 Paper Result |
| `RESEARCH` | 推荐、摘要、方法、贡献、比较、解释、适配性、影响及其他论文内容判断 | `submit_research_answer` | Agent 生成的 Markdown 与 Known Source Quotes |

判断 `CATALOG` 与 `RESEARCH` 的规范性测试是：

```text
MetadataSufficient(answer) =
  隐藏全部论文正文，只保留允许的结构化书目字段时，
  answer 中的全部用户可见内容是否仍能由这些字段直接产生

MetadataSufficient(answer) = true  -> CATALOG MAY publish it
MetadataSufficient(answer) = false -> only RESEARCH MAY publish it
```

### Normative requirements

| ID | Requirement |
| --- | --- |
| `CRH-CONTRACT-001` | Contract MUST 由模型调用的提交 Tool 唯一确定；首版 MUST NOT 增加独立 Router 或 `select_answer_contract` Tool。 |
| `CRH-CONTRACT-002` | `DIRECT` MUST NOT 接受自由 Markdown 或论文内容断言；用户可见文本 MUST 由 Runtime 根据受限结构渲染。 |
| `CRH-CONTRACT-003` | `CATALOG` MUST NOT 接受自由 Markdown、推荐理由、方法解释或模型提供的数量与书目字段值。 |
| `CRH-CONTRACT-004` | `CATALOG` 的数量和字段值 MUST 从当前 Turn 已保存的权威 Paper Result 读取；模型只能选择 Result Ref、其中的 Paper ID 子集、View 和字段白名单。 |
| `CRH-CONTRACT-005` | `RESEARCH` MAY 接受自由 Markdown，但 `ANSWERED` 和 `PARTIAL` 的发布 MUST 满足第 4 节 Provenance Contract。 |
| `CRH-CONTRACT-006` | Contract 选择错误 MUST NOT 放宽所选 Contract 的表达能力；Contract Accuracy MUST 由离线 Eval 报告。 |

## 4. Research Provenance Contract

Research Markdown 由现有确定性 Markdown Parser 切分为两类 Block：

```text
ContentBlock(b) = kind(b) in {paragraph, list_item, table_row}
StructuralBlock(b) = kind(b) in {heading, table_header, thematic_break}

Bound(b) = refs(b) is not empty
Known(ref) = ref in KnownSourceQuotes

KnownSourceQuotes =
  accepted prior-turn Source Quotes
  union current-run CorpusState.source_quotes
```

`ContentBlock` 由 Markdown 结构决定，不由“是否已经包含引用”决定。Source Quote Ref 使用现有 `[[source_quote_...]]` Marker 与同一 Block 绑定；Runtime 在接受答案后将内部 Marker 渲染为用户可见的数字引用。

Research 发布条件为：

```text
CanPublishResearch(submission) =
  case submission.outcome of
    ANSWERED | PARTIAL:
      markdown is present
      and for every ContentBlock b:
            Bound(b)
            and for every ref in refs(b): Known(ref)

    ABSTAINED:
      markdown is absent
      and abstention_reason in {
        NO_MATCHING_PAPER,
        NO_SUPPORTING_SOURCE,
        OUT_OF_SCOPE
      }
```

### Normative requirements

| ID | Requirement |
| --- | --- |
| `CRH-PROV-001` | `ANSWERED` 和 `PARTIAL` 中每个 `ContentBlock` MUST 绑定至少一个 Known Source Quote Ref。 |
| `CRH-PROV-002` | 每个提交的 Source Quote Ref MUST 属于 `KnownSourceQuotes`；模型构造、拼写错误或未读取的 Ref MUST 被拒绝。 |
| `CRH-PROV-003` | Heading、Table Header 和 Thematic Break MAY 不绑定 Source Quote；结构标签若以普通 Paragraph 或 List Item 提交，MUST 按 `ContentBlock` 校验。 |
| `CRH-PROV-004` | Protocol MUST 只校验 Ref 成员关系和现有 Corpus Provenance；它 MUST NOT 把该检查表述为语义蕴含验证。 |
| `CRH-PROV-005` | `ABSTAINED` MUST 只接受受限 reason enum；用户可见说明 MUST 由 Runtime 渲染，不得通过自由 Markdown 携带未验证论文断言。 |
| `CRH-PROV-006` | Research `fields` 若为兼容现有输出而保留，MUST NOT 被 Runtime 或前端作为独立于已校验 Markdown 的用户可见事实渲染。 |

## 5. Actions And State Transitions

### 5.1 Allowed actions

| State | Allowed actions |
| --- | --- |
| `ACTIVE(contract=null)` | 任意现有 Corpus Tool、`get_research_skill`、任一提交 Tool |
| `ACTIVE(contract=CATALOG)` | `search_paper_candidates`、`find_papers_by_identity`、`submit_catalog_answer` |
| `ACTIVE(contract=RESEARCH)` | 任意现有 Corpus Tool、`get_research_skill`、`submit_research_answer` |
| `REPAIR(contract=c)` | 仅允许与 `c` 对应的提交 Tool |
| `COMPLETE` | 无 |

`ACTIVE(contract=DIRECT)` 不作为正常恢复状态：Direct Submission 不依赖外部 Tool；其可恢复错误均进入 `REPAIR(contract=DIRECT)`。

### 5.2 Submission result classes

```text
ACCEPTED
  -> COMPLETE

FORMAT_ISSUE
  -> REPAIR(contract=submitted_contract)

MISSING_CONTRACT_INPUT
  -> ACTIVE(contract=submitted_contract)
```

`MISSING_CONTRACT_INPUT` 包括 Catalog 缺少合法 Paper Result，以及 Research 缺少合法 Source Quote 绑定。该分类允许 Agent 获取当前 Contract 所需输入，但不能切换 Contract。

### 5.3 Transition function

```text
decide(state, event, facts) -> decision

event = ACTION_REQUESTED(tool_name, sibling_tool_names)
      | SUBMISSION_REQUESTED(contract, payload)

decision = (
  next_state,
  accepted_answer,
  model_result
)
```

`decide` 是纯函数；它不调用模型、Corpus、数据库或 Trace Writer。外部副作用只能在 `decision` 允许后发生。

### Normative requirements

| ID | Requirement |
| --- | --- |
| `CRH-TRANS-001` | 每个 Agent Tool 在产生外部副作用前 MUST 经过同一个 Protocol Guard。 |
| `CRH-TRANS-002` | 含提交 Tool 且 Tool Call 数量不为一的 Model Response MUST 作为无副作用的非法调用组拒绝；该组中的 Corpus Tool 不得执行。 |
| `CRH-TRANS-003` | `FORMAT_ISSUE` MUST 进入 `REPAIR`；`REPAIR` 中不得调用 Corpus Tool 或 `get_research_skill`。 |
| `CRH-TRANS-004` | `MISSING_CONTRACT_INPUT` MUST 回到 `ACTIVE` 并冻结已选 Contract；允许的 Tool 集合 MUST 由该 Contract 决定。 |
| `CRH-TRANS-005` | Catalog 缺少合法 Paper Result 时 MUST 能在 `ACTIVE(contract=CATALOG)` 调用 Catalog Discovery Tool 后重试，不得形成只能改 Draft 却无法取得 Result 的死路。 |
| `CRH-TRANS-006` | Research 缺少合法 Source Quote 绑定时 MUST 能在 `ACTIVE(contract=RESEARCH)` 复用已有 Quote、继续读取或删除无来源 Content Block 后重试。 |
| `CRH-TRANS-007` | Contract 不匹配、状态不允许或 Tool Group 非法 MUST 返回结构化、可恢复的 `PROTOCOL_ERROR`，并且不得改变 Corpus State。 |

## 6. Submission And Product Output

三个提交 Tool 使用互斥的参数结构：

```text
DirectSubmission = {
  kind: GREETING | CLARIFICATION | CAPABILITIES | OUT_OF_SCOPE,
  language: ZH_CN | EN,
  question?: string
}

CatalogSubmission = {
  result_ref: PaperResultRef,
  view: COUNT | LIST,
  paper_ids?: PaperId[],
  fields?: CatalogField[],
  language: ZH_CN | EN
}

ResearchSubmission = {
  outcome: ANSWERED | PARTIAL | ABSTAINED,
  language: ZH_CN | EN,
  markdown?: string,
  fields?: map[string, string],
  abstention_reason?: NO_MATCHING_PAPER | NO_SUPPORTING_SOURCE | OUT_OF_SCOPE
}
```

`language` 由模型根据当前对话选择。Runtime 不推断语言；它只在需要渲染固定文案时按该枚举选择中文或英文模板。

任一提交被接受后，Runtime 将其规范化为现有产品链路可消费的答案：

```text
NormalizedAnswer = {
  status,
  outcome,
  markdown,
  fields,
  cited_source_quote_refs,
  answer_contract
}
```

`DIRECT` 和 `CATALOG` 的 Markdown 由 Runtime 确定性渲染；`RESEARCH` 的 Markdown 来自已通过第 4 节校验的 Submission。现有顶层 `status`、`answer`、`citations`、`research_memory`、`usage` 和 `control` 响应边界保持不变。

### Normative requirements

| ID | Requirement |
| --- | --- |
| `CRH-OUTPUT-001` | 三个 Submission Schema MUST 互斥并拒绝未知字段；一个 Tool 的 Payload 不得被另一个 Contract 的 Validator 接受。 |
| `CRH-OUTPUT-002` | Runtime MUST 为 `DIRECT` 和 `CATALOG` 生成全部用户可见 Markdown；模型提供的 Catalog 数量、字段值或附加文案不得进入输出。 |
| `CRH-OUTPUT-003` | `NormalizedAnswer` MUST 保留现有 `status`、`outcome`、`markdown`、`fields` 和 `cited_source_quote_refs` 语义，并新增 `answer_contract` 供 Trace/Eval 使用。 |
| `CRH-OUTPUT-004` | Java 与前端继续只依赖现有产品响应字段；首版 MUST NOT 要求 Java 或前端识别 `answer_contract` 才能正确展示答案。 |
| `CRH-OUTPUT-005` | Provider Adapter 收到纯文本而非合法 Tool Call 时 MUST 将其转换为继续协议的内部事件；它 MUST NOT 猜测并伪造成 `DIRECT`、`CATALOG` 或 `RESEARCH` 提交。 |
| `CRH-OUTPUT-006` | 协议实现上线时 `harness_id` MUST 升级到 v2，使 Eval 和 Trace 能区分新旧发布协议。 |

## 7. Trace And Offline Eval

每次 Protocol 决策产生一条可回放的 `protocol.transition`：

```text
ProtocolTransition = {
  run_id,
  model_call_id,
  tool_call_id,
  before,
  event,
  facts: {
    known_source_quote_refs,
    catalog_result_refs,
    sibling_tool_names
  },
  decision: {
    accepted,
    issue_codes
  },
  after
}
```

完整 Model Response、Tool Arguments、Submission Draft 和 Tool Result 继续由现有对应 Trace Event 保存。`protocol.transition` 只保存决策所需投影并通过 ID 关联这些事件，不重复保存完整 Draft 或 Source Span 正文。

离线 Eval 扩展现有 PaperLoom-31，不建立第二套 Benchmark：

| Layer | Required metrics |
| --- | --- |
| Protocol | Replay Pass Rate、非法状态转移、Provenance Pass Rate |
| Contract | Contract Accuracy |
| Retrieval | Candidate、Location、Source Span Recall |
| Grounding/Answer | Citation Entailment、Coverage、Task Completeness、Recommendation Quality |
| Performance | 总时延、模型时延、模型调用数、重复 Tool Call、Token |

### Normative requirements

| ID | Requirement |
| --- | --- |
| `CRH-OBS-001` | 每次 `decide` 调用 MUST 记录 `before + event + facts + decision + after` 及稳定关联 ID。 |
| `CRH-OBS-002` | Protocol Trace MUST 足以使用相同初始状态和事件顺序重新调用同一个 `decide`；重放结果必须可以与记录结果逐项比较。 |
| `CRH-OBS-003` | `protocol.transition` MUST NOT 重复保存完整 Submission Draft、Model Response 或 Source Span 正文；这些内容继续只在其现有事件中保存一次。 |
| `CRH-EVAL-001` | PaperLoom-31 Case MUST 声明 `expected_contract`；Run MUST 记录 `answer_contract`，并按 Harness 版本分别报告。 |
| `CRH-EVAL-002` | Protocol、Contract、Retrieval 和 Performance 指标 MUST 可在不调用在线 Judge 的情况下独立计算。 |
| `CRH-EVAL-003` | Grounding 与 Answer Quality MAY 使用现有离线 Judge，但其结果 MUST NOT 阻塞在线发布、改变在线状态或触发自动重跑。 |
| `CRH-EVAL-004` | Eval 的语料与 Source Span 真值 MUST 来自版本化 MinerU Reading Model；首版 MUST NOT 要求人工 Evidence Target。 |

## 8. Implementation Boundary And Acceptance

### 8.1 Files in scope

| File | Required change |
| --- | --- |
| `harness_py/orchestration/research_contract.py` | Protocol 类型、三个 Submission Schema、纯 `decide`、Validator、Direct/Catalog Renderer |
| `harness_py/orchestration/agents/context.py` | `protocol_state`、Paper Result Ledger、Protocol Adapter |
| `harness_py/orchestration/agents/tools.py` | Group/Action Guard、三个提交 Tool、Paper Result Ref、结构化拒绝结果 |
| `harness_py/orchestration/agents/runtime.py` | 多提交 Tool 的完成判定、串行 Tool Group 执行、`harness_id` v2 |
| `harness_py/orchestration/agents/model.py` | Provider 纯文本响应改为继续协议事件 |
| `harness_py/orchestration/run_output.py` | `NormalizedAnswer` 与 `answer_contract` |
| `harness_py/evaluation/paperloom31.py` | Contract、Replay 和 Provenance 指标 |

实现 MUST NOT 修改 `ReadingCorpusTools`、Java Corpus API、Java Chat、前端、MySQL 或 ConversationState。若其中任何一项成为实现前置条件，必须先修改并重新评审本 Spec。

### 8.2 Acceptance scenarios

| ID | Given / When | Then |
| --- | --- | --- |
| `CRH-AC-001` | 问候通过 `submit_direct_answer` 提交 | Runtime 发布受限 Direct 文案；无 Corpus Citation |
| `CRH-AC-002` | 当前 Turn 已产生完整 Inventory Result，模型提交 Catalog Count | Runtime 使用 Result 的权威 `matched_count`；忽略模型自报数字 |
| `CRH-AC-003` | Catalog Submission 引用未知 Result Ref | 进入 `ACTIVE(contract=CATALOG)`；允许补做 Catalog Discovery 后重试 |
| `CRH-AC-004` | “推荐和 LLM 原理相关的论文”仅搜索 Paper Card 后提交无引用理由 | Submission 被拒绝；进入 `ACTIVE(contract=RESEARCH)`；不得发布推荐理由 |
| `CRH-AC-005` | Research Answer 的每个 Content Block 绑定 Known Source Quote | Provenance 校验通过；Runtime 渲染数字引用和现有 Citation Payload |
| `CRH-AC-006` | Research Answer 只有 Marker 格式错误且所需 Quote 已知 | 进入 `REPAIR(contract=RESEARCH)`；Corpus Tool 被拒绝；修正 Draft 后可完成 |
| `CRH-AC-007` | 一个 Model Response 同时包含提交 Tool 和其他 Tool | 整组无外部副作用地拒绝；不得更新 Corpus State 或发布答案 |
| `CRH-AC-008` | Provider 返回纯文本而不是提交 Tool Call | Adapter 产生继续协议事件；纯文本不得成为候选最终答案 |
| `CRH-AC-009` | Corpus 不可用、Run Deadline 或取消 | 由现有 Execution Control 映射技术终态；不得伪装成 Validator Issue |
| `CRH-AC-010` | 对完成 Run 重放全部 `protocol.transition` | 每个重放 Decision 与记录 Decision 相同 |

### 8.3 Required validation

1. 对 `decide(state, event, facts)` 运行一组表驱动单元测试，覆盖第 8.2 节状态路径；
2. 运行现有受影响 Harness 聚焦测试，不要求无关 Java、前端或全仓回归；
3. 运行 PaperLoom-31 中两个首批 Case：Catalog Inventory 与 LLM 原理推荐；
4. `git diff --check` 必须通过。

### 8.4 Implementation order

```text
1. research_contract.py：纯状态、Schema、Validator、Renderer
2. context.py + tools.py：Protocol Guard、Paper Result Ledger、提交 Tool
3. runtime.py + model.py + run_output.py：Runner、Provider、产品输出
4. paperloom31.py：离线指标
5. 聚焦验证；通过后再部署
```
