# 可控 Research Harness 协议

> 2026-08-22 修订：下文的固定 Direct 模板已被有界的 `outcome + markdown` 自然对话提交替代。Java 持久化映射后的
> `chat | catalog | research` 模式，前端将其显示为信任标签。见
> [research-answer-scope-regression-2026-08-21.md](research-answer-scope-regression-2026-08-21.md#follow-up-remove-the-canned-direct-response-contract)。

> 状态：已实现；2026-08-22 按上方说明修订。规范见 [`controlled-research-harness-spec-2026-08-11.md`](./controlled-research-harness-spec-2026-08-11.md)。

## 1. 目标

Harness 必须同时满足：

1. 保留 Agent 对研究问题、检索词、论文选择和原文是否支持结论的语义判断；
2. 不依赖 Agent 自觉执行产品不变式；
3. 不固定论文数量、Tool Call 数量或研究轮数；
4. 任何可发布答案都能解释“为什么这条路径合法”；
5. 答案格式、来源追溯、协议违规和技术故障不混合；
6. 在线 Runtime 不伪装成能够确定性判断“证据是否充分”。

## 2. 当前 Harness 的形式化模型

记一次 Run 为：

```text
H_current = (Input, Model, Tools, Trace, Validator, Output)
```

Model 在每轮可以任意选择：

```text
Action = DirectAnswer
       | Clarify
       | LoadSkill
       | DiscoverPaper
       | LocateContent
       | ReadContent
       | SubmitAnswer
```

当前 Runtime 已经确定性保证：

```text
DiscoverPaper(p)  before LocateContent(p)
LocateContent(l)  before ReadContent(l)
ReadContent(l)    creates valid SourceQuoteRef
SubmitAnswer      must be the only final Tool Call
CitedRef          must refer to known citeable evidence
```

当前引用覆盖校验的启用条件是：

```text
require_content_citations
    = Trace contains SearchPaperContent or ReadPaperContent
```

因此存在合法但错误的路径：

```text
RecommendationQuestion
-> DiscoverPaper
-> SubmitAnswer(with recommendation prose, without evidence)
-> require_content_citations = false
-> Accepted
```

结论：当前 Harness 控制了 Tool 授权和引用完整性，但没有控制**什么类型的答案必须进入研究**。业务路径仅存在于 Prompt，因此每个新失败都会诱导继续补 Prompt。

## 3. 核心分离

需要分开两个当前混在一起的决策：

```text
Answer Contract = 这轮允许产生什么类型的答案
Research Skill  = 确定研究后，使用什么研究方法
```

Research Skill 不再负责判断“要不要 Deep Research”。它只在 Research Contract 成立后指导如何搜索、比较、验证或综合。

这里的 Skill 和 Tool 是两个对象：`ResearchSkill` 是一份研究方法数据；`get_research_skill(skill_id)` 是把这份数据加载给模型的 Agent Tool。它只返回 `instructions`、`evidence_standard` 和 `answer_guidance`，不执行独立 Agent、不访问 Corpus，也不修改 Paper/Location/Source Quote 授权状态。

## 4. Answer Contract

一轮只能选择一种 Answer Contract：

```text
AnswerMode = DIRECT | CATALOG | RESEARCH
```

| Mode | 允许的内容 | 证据要求 | 输出形状 |
| --- | --- | --- | --- |
| `DIRECT` | 问候、澄清、能力说明和超范围响应 | 不允许论文内容断言 | 受限结构，由 Runtime 渲染 |
| `CATALOG` | 语料库数量、论文身份、作者、年份、Venue 等结构化事实 | 当前轮的 Paper Card/Query Result | 结构化卡片/字段，不接受自由的“推荐理由” |
| `RESEARCH` | 推荐、阅读顺序、摘要、方法、贡献、比较、解释、适配性、影响或其他论文内容判断 | 可追溯的 Source Quote | 每个 Content Block 绑定至少一个 Source Quote Ref |

这里不使用“正文必须绑定 Source Quote”这一含糊说法。准确规则是：**Research 回答中每个内容块（Content Block），必须绑定至少一个 Agent 已实际读取且可追溯的 Source Quote。**

首版沿用现有 Markdown Block Parser，以可确定执行的结构规则定义它：

```text
ContentBlock(b) = kind(b) in {paragraph, list_item, table_row}

StructuralBlock(b) = kind(b) in {heading, table_header, thematic_break}

Cited(b) = refs(b) is not empty
```

发布条件是 `for every ContentBlock b: Cited(b)`，而不是用“带引用的块”来定义 Content Block。论文全文本身不需要“绑定”；标题、表头和分隔线也不需要引用。需要绑定的是回答里的普通段落、列表项和表格数据行。结构标签必须写成 Markdown Heading 或 Table Header，不能伪装成普通事实段。该规则只证明回答块的原文来源可追溯，不证明 Source Quote 在语义上蕴含该结论。

判断 `CATALOG` 与 `RESEARCH` 的形式化问题是：

```text
如果隐藏所有论文正文，只保留结构化书目字段，
这个答案是否仍然可被证明？

yes -> CATALOG
no  -> RESEARCH
```

所以：

```text
“这个库里有多少篇论文”       -> CATALOG
“列出 2024 年的论文标题”        -> CATALOG
“推荐和 LLM 原理相关的论文” -> RESEARCH
“这两篇哪篇更适合入门”       -> RESEARCH
```

`RESEARCH` 是所有论文判断的默认路径；`CATALOG` 是只允许输出结构化元数据的窄路径。

Contract 选择是显式的最终协议动作，不是 Prompt 中的隐式推理。v1 不增加独立的 `select_answer_contract` Tool；Contract 由模型选择的提交 Tool 唯一确定：

```text
submit_direct_answer   -> DIRECT
submit_catalog_answer  -> CATALOG
submit_research_answer -> RESEARCH
```

这样首轮模型可以直接搜索，不必为了选择 Contract 多付一次模型往返。Contract 真正需要产生约束的时刻是提交答案，而不是检索之前。

三个提交 Tool 使用互斥的输出形状：

```text
DirectSubmission = {
  kind: GREETING | CLARIFICATION | PAPERLOOM_CAPABILITIES | OUT_OF_SCOPE,
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

`PAPERLOOM_CAPABILITIES` 只表示用户询问 PaperLoom 自身能做什么。单独的“你知道 X 吗”尚未提出
实质问题，进入 `CLARIFICATION` 简短确认并询问关注点；定义、原理和比较等实质问题进入 `RESEARCH`。

`language` 由模型根据当前对话选择，不由 Runtime 检测字符或推断。`ANSWERED/PARTIAL` 的正文语言仍由模型生成；`DIRECT`、`CATALOG` 和 Research `ABSTAINED` 的固定文案由 Runtime 按该枚举选择模板。

Runtime 根据提交 Tool 使用不同验收器和渲染器：

- `DIRECT` 只提交受限的问候/澄清结果，由 Runtime 渲染；它没有可写论文结论的自由文本槽位。
- `CATALOG` 引用本轮真实 `PaperResultRef`，只能选择其中的 Paper ID 和允许的元数据字段；数量与字段值由 Runtime 从工具结果解析并渲染，模型不能提交数字或“推荐理由”。
- `RESEARCH` 可使用完整 Corpus 阅读工具和自由研究答案，但发布门强制 Provenance Contract。
- 如果 Agent 错把推荐选为 `CATALOG`，它最多只能返回一份明显不完整的卡片列表，不能再生成本次故障中的无证据推荐理由。这类模式选择质量由在线 Trace 指标和离线 Eval 约束。
- 第一次提交被拒绝后 Contract 冻结；`RESEARCH` 不能改用 `CATALOG` 或 `DIRECT` 逃避 Provenance Contract。

## 5. Research Protocol

在线状态由三个各自拥有明确职责的状态组成，不复制同一份授权集合：

```text
RunState = (ProtocolState, CorpusState, ExecutionState)

ProtocolState = (
  phase,
  contract,
  submission_attempt,
  validation_issues
)

CorpusState = (
  authorized_papers,
  disclosed_locations,
  source_quotes
)

ExecutionState = existing RunControl + terminal Run disposition

phase = ACTIVE | REPAIR | COMPLETE
contract = null | DIRECT | CATALOG | RESEARCH
```

`ReadingCorpusTools` 继续唯一拥有 `CorpusState`；新状态机不复制 `authorized_paper_ids`、`disclosed_location_refs` 或 `observations_by_evidence_id`。三个授权集合保持单调增长：

```text
P_t = authorized_papers
L_t = disclosed_locations
Q_t = source_quotes

P_t subseteq P_(t+1)
L_t subseteq L_(t+1)
Q_t subseteq Q_(t+1)
```

`source_quotes` 只表示“模型实际读取过且能够追溯到论文位置的原文”，不表示“原文在语义上充分支持结论”。一个 Quote 必须包含稳定的 `source_quote_ref`，并绑定当前或已接受记忆中的 Paper、内容 Hash 和 Source Span。

初始协议状态为：

```text
S_0 = ProtocolState(
  phase = ACTIVE,
  contract = null,
  submission_attempt = 0,
  validation_issues = []
)
```

Corpus Tool 的成功结果只更新现有 `CorpusState`：

```text
CorpusState(P, L, Q)
  + PAPERS_DISCOVERED(ids)
  -> CorpusState(P union ids, L, Q)

CorpusState(P, L, Q)
  + LOCATIONS_DISCLOSED(refs)
  -> CorpusState(P, L union refs, Q)

CorpusState(P, L, Q)
  + SOURCE_QUOTES_READ(refs)
  -> CorpusState(P, L, Q union refs)
```

Protocol 状态只在提交、修复和终止时变化：

```text
ACTIVE(contract = null | c)
  + SUBMISSION_REQUESTED(contract = c, draft, accepted = true)
  -> COMPLETE(contract = c, submission_attempt += 1)

ACTIVE(contract = null | c)
  + SUBMISSION_REQUESTED(contract = c, draft, issue_class = FORMAT_ISSUE)
  -> REPAIR(contract = c, submission_attempt += 1)

ACTIVE(contract = null | c)
  + SUBMISSION_REQUESTED(contract = c, draft, issue_class = MISSING_CONTRACT_INPUT)
  -> ACTIVE(contract = c, submission_attempt += 1)

REPAIR(contract = c)
  + SUBMISSION_REQUESTED(contract = c, draft, accepted = true)
  -> COMPLETE(contract = c, submission_attempt += 1)

REPAIR(contract = c)
  + SUBMISSION_REQUESTED(contract = c, draft, issue_class = FORMAT_ISSUE)
  -> REPAIR(contract = c, submission_attempt += 1)

REPAIR(contract = c)
  + SUBMISSION_REQUESTED(contract = c, draft, issue_class = MISSING_CONTRACT_INPUT)
  -> ACTIVE(contract = c, submission_attempt += 1)

ACTIVE | REPAIR
  + PROTOCOL_REJECTED(error)
  -> same phase with validation_issues = [error]

```

动作 Guard 由 `phase + contract` 决定：

| 当前状态 | 允许的动作 |
| --- | --- |
| `ACTIVE(contract=null)` | 使用 Corpus Tool、调用 `get_research_skill`，或选择任一提交 Tool |
| `ACTIVE(contract=CATALOG)` | 使用 `search_paper_candidates`、`find_papers_by_identity`，或重新提交 Catalog Draft |
| `ACTIVE(contract=RESEARCH)` | 使用 Corpus Tool、调用 `get_research_skill`，或重新提交 Research Draft |
| `REPAIR(contract=c)` | 只允许同一 Contract 的提交 Tool；Corpus Tool 和 `get_research_skill` 关闭 |
| `COMPLETE` | 不再接受动作 |

纯格式错误进入 `REPAIR`。`CATALOG` 缺少合法 Paper Result 时回到 `ACTIVE(contract=CATALOG)`，只允许补做 Catalog Discovery；`RESEARCH` 缺少合法 Source Quote 绑定时回到 `ACTIVE(contract=RESEARCH)`，允许继续搜索和阅读。两者都不能降级或切换 Contract。

`ACTIVE` 内部仍是自由 ReAct：Agent 决定检索词、论文数量、阅读位置和停止时机。协议不固定论文数量、Tool Call 数量或研究轮数，也不引入不能被 Runtime 验真的 Obligation Graph。

发布条件按 Contract 固定，而不再由“模型是否主动调用过正文工具”触发：

```text
can_publish(contract, draft, CorpusState) =
  case contract of
    DIRECT:
      draft matches restricted direct-result schema

    CATALOG:
      draft.result_ref identifies a current-turn Paper Result
      and draft.paper_ids is a subset of that result
      and draft.fields is a subset of the Catalog field allowlist
      and all user-visible values are Runtime-rendered

    RESEARCH:
      if outcome in {ANSWERED, PARTIAL}:
        for every ContentBlock b in draft:
          refs(b) is not empty
          and refs(b) subseteq KnownSourceQuotes
          and every ref has valid Paper + ContentHash + SourceSpan provenance

      if outcome = ABSTAINED:
        markdown is absent
        and abstention_reason is an allowed enum
        and Runtime renders the user-visible limitation

KnownSourceQuotes = accepted prior-turn quotes union current-run CorpusState.source_quotes
```

Protocol 不自行重算 Current Model、Content Hash 或 Source Span。当前轮 Quote 只有在 Java Corpus Exact Read 已完成这些校验后才进入 `CorpusState.source_quotes`；Protocol 只校验 Ledger 成员关系。Prior-turn Quote 继续遵守现有“只有已接受 Citation 才进入 Conversation Memory”的信任规则。是否要在跨轮复用前重新验证 Current Model 属于 Corpus/Conversation Contract，不在本状态机重复实现。

该条件是 **Provenance Contract**，只保证“结论绑定了模型实际读过的真实原文”。它不证明 `Supports(source_span, claim)`；引用是否蕴含结论、推荐是否充分、回答是否完成用户任务，仍由离线 Eval 测量。

## 6. 校验失败的可控分流

Validator 不只返回文本，必须返回错误码、受影响 Block 和问题分类：

```text
ValidationIssue = {
  code,
  issue_class: FORMAT_ISSUE | MISSING_CONTRACT_INPUT,
  block_ids: BlockId[],
  unknown_source_quote_refs: SourceQuoteRef[]
}
```

| Code | Issue class | 允许的下一步 |
| --- | --- | --- |
| `ANSWER_FORMAT_ERROR` | `FORMAT_ISSUE` | 修改 Draft 后用同一提交 Tool 重试 |
| `INVALID_SOURCE_MARKER` | `FORMAT_ISSUE` | 改用合法 Marker 语法 |
| `UNKNOWN_CATALOG_RESULT_REF` | `MISSING_CONTRACT_INPUT` | 使用 Catalog Discovery Tool 获得当前 Turn Result 后重试 |
| `UNKNOWN_SOURCE_REF` | `MISSING_CONTRACT_INPUT` | 绑定已有 Quote、补读原文或删除该 Ref 对应的结论 |
| `UNCITED_CONTENT_BLOCK` | `MISSING_CONTRACT_INPUT` | 绑定已有 Quote、补读原文或删除 Block |
| `NO_KNOWN_SOURCE_QUOTE` | `MISSING_CONTRACT_INPUT` | 读取原文后再提交 |
| `PROTOCOL_ERROR` | 保持当前阶段 | 修正 Tool 参数、调用顺序或提交 Tool |

状态机不判断“现有 Quote 在语义上够不够”。纯格式问题进入 `REPAIR`；缺少当前 Contract 的权威输入时回到 `ACTIVE(contract=c)`。`phase` 已经完整决定允许的动作，因此不增加单独的修复范围状态字段。结构化反馈可以把“优先复用已有 Quote”标为首选动作，但 Runtime 不把这个语义决定伪装成确定性 Guard。

技术异常不是 Validator Error：

```text
TechnicalFailure = MODEL_UNAVAILABLE
                 | CORPUS_UNAVAILABLE
                 | RUN_DEADLINE_EXCEEDED
                 | CANCELLED
                 | UNEXPECTED_INTERNAL_ERROR
```

技术异常不进入 ResearchProtocol：现有 `RunControl`、Provider/Corpus typed error 和 `LiveResearchChatHarness` 继续把它们映射为 `LIMITED`、`CANCELLED` 或 `FAILED_TECHNICAL`。它们不能伪装成来源不足或回答质量问题。

这个分流在 Runtime 中体现为允许的状态转移，而不是一句“请不要重新读取”的 Prompt。

## 7. 职责

| 模块 | 负责 | 不负责 |
| --- | --- | --- |
| Answer Contract | 限定本轮可产生的答案类型和证据要求 | 选论文、写研究结论 |
| Agent | 选择 Contract 和 Skill、检索、阅读、判断原文是否支持结论、综合 | 放宽 Contract 或伪造 Source Quote Ref |
| Corpus | 候选召回、位置导航、正文读取、授权链 | 决定答案语义 |
| Validator | 状态前置条件、Block 引用绑定、Ref 来源追溯、错误分类 | 判断原文是否蕴含结论 |
| Runtime | 执行状态转移、工具调用、取消/超时、Trace 和持久化 | 代替 Agent 做研究判断 |
| Eval | Contract 正确性、引用蕴含、任务完整性、召回覆盖和性能回归 | 阻塞在线用户回答或改变在线状态 |

## 8. 保留与替换

保留现有深模块：

- `HarnessRuntime.run_turn` 单一外部接口；
- `ConversationState` 和“只记住已接受引用”原则；
- `ReadingCorpusTools` 的 Paper -> Location -> Source Quote 授权链；
- Qdrant/MySQL/Reading Model 读取路径；
- Source Quote 稳定身份、Block Parser、Trace、取消和超时；
- 单一 Agent 在 Research Loop 内的自由 ReAct。

替换当前的浅控制点：

- 用显式 Answer Contract 替换 Prompt 中隐式的答案路径；
- 用按 Mode 固定的 Provenance 发布前置条件，替换“只有 Agent 主动读了正文才要求引用”；
- 用类型化校验错误和允许的转移，替换校验失败后的自由 ReAct；
- Skills 保留为研究方法，不再承担业务路由。

当前不需要：

- 第二个在线 LLM Router 或 Judge；
- 关键词分类器；
- 固定论文数量、研究轮数或 Tool Call 数量；
- Obligation/Claim Graph、局部 Patch 协议或新的证据存储系统。

## 9. 状态机实现形状

### 9.1 Module 与 Seam

不增加框架，也不新建一组平行的 Runtime。现有 `harness_py/orchestration/research_contract.py` 已经拥有最终答案 Schema、Prompt Contract 和 Validator；它直接深化为 `ResearchProtocol` Module，避免把同一规则分散到新文件。

该 Module 的核心 Interface 是一个纯决策函数：

```python
def decide(
    state: ProtocolState,
    event: ProtocolEvent,
    facts: ProtocolFacts,
) -> ProtocolDecision:
    """Validate one action and return its deterministic next state/result."""
```

`decide` 不调用模型、Qdrant、MySQL、Java Corpus API 或 Trace Writer。它只消费当前协议状态和已经发生的事实，返回：

```python
@dataclass(frozen=True)
class ProtocolDecision:
    next_state: ProtocolState
    accepted_answer: JsonMap | None
    model_result: JsonMap
```

最小 `ProtocolState` 不复制 Corpus 授权状态：

```python
@dataclass(frozen=True)
class ProtocolState:
    phase: Phase                 # ACTIVE | REPAIR | COMPLETE
    contract: AnswerContract | None
    submission_attempt: int
    issue_codes: tuple[ValidationCode, ...]
```

`ProtocolFacts` 是对现有 Runtime 状态的只读投影：

```python
@dataclass(frozen=True)
class ProtocolFacts:
    known_source_quotes: Mapping[str, JsonMap]
    catalog_results: Mapping[str, JsonMap]
    sibling_tool_names: tuple[str, ...]
```

事件只需要覆盖协议会改变的两类动作：

```text
ACTION_REQUESTED(tool_name)
SUBMISSION_REQUESTED(contract, payload)
```

普通 Corpus 成功结果仍由 `ReadingCorpusTools` 更新它自己的授权集合，不需要再向 Protocol 复制一个完成事件。`SUBMISSION_REQUESTED` 在一次纯决策中直接得到 `COMPLETE`、`REPAIR` 或 `ACTIVE(contract=c)`；同步 Validator 不需要一个只能存在几微秒的 `VALIDATING` 状态。

### 9.2 Context Adapter

`ResearchRunContext` 增加最小请求级状态：

```python
protocol_state: ProtocolState
catalog_results_by_ref: dict[str, JsonMap]
```

以及一个薄 Adapter：

```python
def apply_protocol(
    self,
    event: ProtocolEvent,
    facts: ProtocolFacts,
    *,
    tool_call_id: str,
) -> ProtocolDecision:
    decision = decide(self.protocol_state, event, facts)
    record_transition(tool_call_id, self.protocol_state, event, facts, decision)
    self.protocol_state = decision.next_state
    return decision
```

Adapter 只负责保存新状态和记录 Trace；全部业务判断留在 `research_contract.py`。`HarnessRuntime.run_turn`、`TurnExecutionInput` 和 `ConversationState` 的 Interface 不变。

### 9.3 Tool 接入

`build_agent_tools` 保留现有 `get_research_skill` 与 Corpus Tool，再把单一最终 Tool 替换为三个互斥提交 Tool：

```text
get_research_skill
search_paper_candidates
find_papers_by_identity
search_paper_content
get_paper_structure
read_paper_content
get_citation_edges (when available)
submit_direct_answer
submit_catalog_answer
submit_research_answer
_continue_research_turn
```

工具列表可以保持静态，避免依赖 Agents SDK 的动态 Tool 能力。每次 Tool 真正执行前，统一入口调用：

```text
context.apply_protocol(ACTION_REQUESTED(tool_name), facts, tool_call_id=tool_call_id)
```

被状态拒绝的 Tool 不产生外部副作用，返回统一的 recoverable `PROTOCOL_ERROR`。允许的 Corpus Tool 继续走现有 `ReadingCorpusTools.call`；授权链和 Java 安全校验不迁移到 Protocol。

三个提交 Tool 仍必须是其 Model Response 中唯一的 Tool Call。包含提交 Tool 的多 Tool Group 在任何成员产生外部副作用前整体拒绝。`tools_to_final_output` 只在任一提交 Tool 返回 `accepted=true` 时结束 Agents SDK Runner。

### 9.4 Direct 与 Catalog Renderer

Direct Submission 不包含 `markdown`：

```text
GREETING / PAPERLOOM_CAPABILITIES / OUT_OF_SCOPE
  -> Runtime 按 language 渲染固定文案

CLARIFICATION
  -> 只接受一个有长度上限的 question 字段
  -> Normalized outcome = needs_clarification
```

这样 `DIRECT` 不能成为另一条自由输出通道。首版只维护 `ZH_CN` 和 `EN` 两组短模板；新增语言时增加模板，不增加新的 Agent 分支。

`CATALOG` 不能让模型重新手写搜索结果。每次 `search_paper_candidates` 或 `find_papers_by_identity` 成功后，Tool Adapter：

1. 用 `tool_call_id` 生成请求内稳定的 `paper_result_...`；
2. 先执行现有 model-visible bounded projection；
3. 将权威 `matched_count/coverage` 和实际展示给模型的 Paper Cards 保存到 `context.catalog_results_by_ref`；
4. 在模型可见结果中增加 `paper_result_ref`；
5. Catalog Submission 只提交该 Ref、展示方式、Paper ID 子集和字段白名单。

Catalog Renderer 的确定性规则：

```text
COUNT -> 使用结果中的 matched_count；忽略模型提供的任何数字
LIST  -> 只读取实际展示给模型的 Paper Card；paper_ids 必须是其子集
fields -> title | authors | year | venue | doi | arxiv_id
源结果或 bounded projection 未展示全部 Cards -> Runtime 明确渲染为 partial list
```

Identity Result 在写入 Ledger 时规范化为同一结构：`matched_count=len(matches)`，`coverage=complete`。

所有 Markdown、数字和字段值由 Runtime 渲染。v1 不支持模型为 Catalog 添加前言、推荐理由或排序解释。

### 9.5 Research Submission Validator

Validator 对一次 Submission 只解析一次，并尽量在一次拒绝中返回全部独立问题：

```text
1. 校验最终 Tool Call 独占当前 Model Response
2. 校验 Contract 对应的 JSON Shape
3. 解析 Markdown Blocks，并按固定 kind 集合识别 Content Blocks
4. 校验 Marker 语法
5. 校验所有 SourceQuoteRef 属于 KnownSourceQuotes
6. 对 ANSWERED/PARTIAL 检查每个 Content Block 都绑定至少一个 Ref
7. 对 ABSTAINED 禁止自由 Markdown，只接受结构化 reason enum
8. 计算 issue_class，并由它决定 `ACTIVE` 或 `REPAIR`
```

`issue_class` 的合并规则是：

```text
存在 UNKNOWN_CATALOG_RESULT_REF、UNKNOWN_SOURCE_REF、UNCITED_CONTENT_BLOCK
或 NO_KNOWN_SOURCE_QUOTE
  -> MISSING_CONTRACT_INPUT

否则存在格式或 Marker 语法问题
  -> FORMAT_ISSUE
```

模型可见拒绝结果使用稳定结构，不再只有一整段字符串：

```json
{
  "accepted": false,
  "error_code": "FINAL_SUBMISSION_REJECTED",
  "contract": "RESEARCH",
  "issue_class": "MISSING_CONTRACT_INPUT",
  "issues": [
    {
      "code": "UNKNOWN_SOURCE_REF",
      "block_ids": ["block_2"],
      "unknown_source_quote_refs": ["source_quote_bad"]
    }
  ],
  "allowed_next_actions": ["read_paper_content", "submit_research_answer"]
}
```

不增加局部 Patch 协议。`REPAIR` 仍重新提交一份完整 Draft，并继续使用现有 `_latest_final_submission_only` 删除更早的失败提交。只有 Trace 证明精确反馈后仍反复重写全文，才重新评估 Patch。

### 9.6 Provider 适配

有三个提交 Tool 后，Provider Adapter 无法从纯文本可靠推断 Contract。因此纯文本响应不再伪造成某一种最终答案，而是转换成现有 `_continue_research_turn` 内部 Tool，并要求下一轮调用一个明确的提交 Tool。Malformed Function Call 仍走当前参数修复逻辑；网络、限时和供应商协议错误仍是技术故障。

### 9.7 Normalized Output 与兼容性

三个提交 Renderer 最终都产生当前产品可消费的统一形状：

```text
NormalizedAnswer = {
  outcome,
  markdown,
  fields,
  answer_contract
}
```

`build_harness_run` 继续渲染数字引用、Evidence Ledger、Memory Update 和现有 `research_answer`。`answer_contract` 是新增诊断字段；现有 Java/前端所需的 `status`、`outcome`、`markdown`、`fields` 和 Citation 结构不变。

因此首版不需要：

- Java 或前端协议重写；
- MySQL Migration；
- ConversationState Migration；
- 新的 Corpus API；
- LangGraph、pytransitions 或 Temporal。

实现后将 `harness_id` 从 v1 升到 v2，便于离线结果按协议版本分组。

### 9.8 Trace

现有 `EvalRecorder` 增加 `protocol.transition` 事件：

```json
{
  "kind": "protocol.transition",
  "payload": {
    "before": {"phase": "ACTIVE", "contract": null},
    "event": {"kind": "SUBMISSION_REQUESTED", "contract": "RESEARCH"},
    "facts": {
      "known_source_quote_refs": ["source_quote_..."],
      "catalog_result_refs": [],
      "sibling_tool_names": ["submit_research_answer"]
    },
    "decision": {"accepted": false, "issue_codes": ["UNCITED_CONTENT_BLOCK"]},
    "after": {"phase": "ACTIVE", "contract": "RESEARCH"}
  }
}
```

不在该事件中重复保存 Source Span 正文或完整 Draft；它们已经分别存在于 Tool/Submission Trace。Tool Call ID 继续作为幂等键。离线重放按事件顺序重建 Corpus Facts，再调用同一个 `decide` 检查结果一致性。

### 9.9 文件改动边界

| 文件 | 设计内改动 |
| --- | --- |
| `harness_py/orchestration/research_contract.py` | Protocol 类型、三个提交 Schema、纯 `decide`、Validator、Direct/Catalog Renderer |
| `harness_py/orchestration/agents/context.py` | `protocol_state`、Paper Result Ledger、`apply_protocol` Adapter |
| `harness_py/orchestration/agents/tools.py` | Action Guard、三个提交 Tool、结构化拒绝结果、Paper Result Ref |
| `harness_py/orchestration/agents/runtime.py` | 多最终 Tool 的完成判定、`harness_id` v2 |
| `harness_py/orchestration/agents/model.py` | 纯文本改为协议 Nudge，不猜测 Contract |
| `harness_py/orchestration/run_output.py` | 消费 `NormalizedAnswer`，记录 `answer_contract` |
| `harness_py/evaluation/paperloom31.py` | Contract/Protocol 指标并入现有 L3 报告，复用当前 Grounding Judge |

不修改 `ReadingCorpusTools`、Java Corpus API、Java Chat 或前端。若实现中发现必须修改这些模块，先回到设计评审，而不是扩大 Diff。

### 9.10 实现顺序

```text
1. research_contract.py：纯状态转移、提交校验和表驱动测试
2. context.py + tools.py：接入 Action Guard、Paper Result Ref、三个提交 Tool
3. runtime.py + model.py + run_output.py：完成 Runner 和产品输出兼容
4. paperloom31.py：增加离线 Contract/Protocol 指标
5. 跑聚焦测试和两个首批回归 Case，再决定是否部署
```

关键实践：

1. **State as data**：状态是可序列化数据，不是散落在 Prompt、Context Set 和函数分支中的暗号。
2. **Pure decision, impure effect**：决策函数不调模型、Qdrant 或 MySQL；工具执行留在现有 Adapter。
3. **Guards before effects**：禁止的动作在调用外部系统前被拒绝。
4. **Typed failures**：格式错误、来源追溯错误和协议错误是不同事件；技术故障留在现有 Execution Control。
5. **Single ownership**：Corpus 授权集合只有 `ReadingCorpusTools` 一份，Protocol 不复制。
6. **Replayable trace**：同一初始状态、事件和 Facts 必须得到同一 Decision。
7. **Table-driven tests**：测试 State + Event + Facts，不为每种中文问句写分支。

现有 OpenAI Agents SDK `Runner` 继续负责单 Agent Tool Loop。`ResearchProtocol` 只控制最终答案合同和修复路径，不重写 Agent。

“可控”不表示 Runtime 能确定性理解任意自然语言，也不表示它能证明 Source Span 蕴含答案结论。状态机控制错误 Contract 的表达能力和错误提交后的合法动作；语义误选和引用蕴含质量由离线 Eval 发现。

## 10. 离线 Eval

Eval 是状态机之外的观测平面：消费已经完成的 Run，不参与在线状态转移，不阻塞用户答案，也不因低分自动重新执行研究。

### 10.1 输入与输出

```text
EvalInput = (
  user_question,
  expected_contract,
  selected_contract,
  trace,
  final_answer,
  cited_source_spans,
  corpus_snapshot
)

EvalOutput = (
  protocol_metrics,
  semantic_metrics,
  retrieval_metrics,
  performance_metrics
)
```

语料身份、正文、表格、图片和 Source Span 以版本化的 MinerU Reading Model 产物为事实来源；不要求人工标注 Evidence Target。自动生成的 Eval Case 必须保存生成依据和预期 Source Span，保证失败可以回放。

### 10.2 分层指标

| 层 | 指标 | 判定方式 | 回答的问题 |
| --- | --- | --- | --- |
| Protocol | 状态转移合法率、越权 Tool Call、Provenance Contract 通过率 | 确定性代码 | Harness 是否按协议执行 |
| Contract | Contract Accuracy | Eval Case 的已知问题类型对比 `selected_contract` | 该问题是否进入正确答案合同 |
| Retrieval | Candidate Recall、Location Recall、Source Span Recall | Reading Model 中保存的预期 Paper/Span | Agent 是否找到需要阅读的位置 |
| Grounding | Citation Entailment、Citation Coverage | Judge 比较 Content Block 与其 Source Span | 引用是否真的支持对应结论 |
| Answer | Task Completeness、Recommendation Quality | Judge 对照问题要求和全部已读 Span | 是否完成用户任务且结论有用 |
| Performance | 总时延、模型时延、模型调用数、重复 Tool Call、Token | Trace 直接计算 | 质量是否以不可接受的成本获得 |

Protocol、Contract、Retrieval 和 Performance 可以从冻结 Case、Trace 和 Source Span 确定性计算。Grounding 和 Answer 是语义质量指标；即使使用 LLM Judge，也必须视为概率性测量，不能成为在线发布 Guard。

### 10.3 执行方式

```text
离线 Benchmark Case
-> 执行真实 Harness Run
-> 固化 Question + Trace + Answer + Source Spans
-> 运行确定性 Protocol/Retrieval 评分
-> 运行可选的语义 Judge
-> 按 Case、模型、Prompt、Index 和 Harness 版本保存结果
-> 与上一基线比较质量和性能回归
```

生产数据只做离线抽样 Eval：读取现有 `EvalRecorder` 已落盘且脱敏的 Completed Run，不回写在线状态。v1 不增加在线 Eval 队列。若 Judge 不可用，Protocol、Contract、Retrieval 和 Performance 指标仍可独立运行。

### 10.4 首批回归场景

至少覆盖本次已观察到的两条对照路径：

```text
“这个论文库有多少篇”
  expected_contract = CATALOG

“推荐和 LLM 原理相关的论文”
  expected_contract = RESEARCH
  expected_behavior includes:
    read at least one exact Source Span
    every recommendation block binds known SourceQuoteRef
```

第二条 Case 的 Runtime 断言只检查路径和来源追溯；“推荐理由是否被引文蕴含”由离线 Grounding Eval 评分。

### 10.5 映射到现有 PaperLoom-31

不新建第二套 Benchmark。扩展现有 `harness_py/evaluation/paperloom31.py` 的 Snapshot 和 L3 Agent 报告：

1. 现有 `single_paper`、`cross_paper_comparison`、`follow_up` 和 `missing_evidence_control` Case 标记 `expected_contract=RESEARCH`；
2. 自动增加少量固定 `DIRECT` 协议 Case，例如问候和缺少研究主题的澄清；它们不承担证据评测；
3. 从 Snapshot 的 Paper Card 自动生成 `CATALOG` Case，例如论文总数和年份过滤；期望值直接由冻结 Snapshot 计算；
4. 每个 Run 从 `research_answer.answer_contract` 读取实际 Contract；
5. 复用当前 `_assess_agent_case` 的 Target Returned/Read/Cited 检查；
6. 复用当前 `_judge_agent_case` 的 `answer_quality` 和 `grounding` Judge，不增加新的 Judge Model 或 Prompt 链路。

实现版本将 Snapshot 升为 `paperloom-product-snapshot/v2`、Case Layout 升为
`paperloom-agent-case-layout-v4`、Run Report 升为 `paperloom-benchmark-run/v2`。L3 固定为 16 个 Case：
原有 12 个 Research Case，加问候、缺少主题的澄清、Corpus 总数和 LLM 原理推荐四个协议 Case。
Direct/Catalog Case 不调用 Judge；它们只计算确定性 Contract 与 Protocol 指标。
LLM 原理推荐是开放式 Case，不绑定任意单篇论文的自动生成答案；Judge 只按主题相关性、推荐理由质量和引文蕴含关系评分。

确定性指标定义为：

```text
contract_accuracy
  = count(actual_contract = expected_contract) / case_count

protocol_replay_pass_rate
  = count(replayed_decision = recorded_decision) / protocol_transition_count

provenance_pass_rate
  = count(RESEARCH completed with all cited refs resolvable and block-bound)
    / count(completed RESEARCH runs)

source_span_recall
  = count(required target Source Spans read)
    / count(required target Source Spans)

duplicate_tool_rate
  = count(repeated normalized Tool requests) / tool_call_count
```

语义指标直接使用现有 Judge 输出：

```text
grounding_pass_rate
  = count(judge.grounding = PASS) / judged_research_case_count

answer_quality_pass_rate
  = count(judge.answer_quality = PASS) / judged_research_case_count
```

首轮只建立基线和前后对比，不预设一个没有数据依据的综合分或阈值。唯一立即生效的硬条件是：Protocol Replay、越权调用和不可解析 Citation 必须为零失败；语义质量与时延不得相对当前冻结基线退化。

## 11. 开源实践与选型

### OpenAI Agents SDK：保留

[OpenAI 官方 Agents SDK 文档](https://developers.openai.com/api/docs/guides/agents#build-with-the-sdk) 明确将部署、Tool 实现、State Storage 和 Approval Decision 交给 Server，SDK 负责 Agent Loop 和 Tool Invocation。这与本设计一致：保留 Runner，将产品协议状态放在 PaperLoom Runtime。

### LangGraph：借鉴模型，暂不引入

[LangGraph](https://github.com/langchain-ai/langgraph) 将自己定位为长时间、有状态 Agent 的底层编排框架，提供 State Graph、Conditional Edge、Persistence 和 Durable Execution。它的 [Workflows and agents](https://docs.langchain.com/oss/python/langgraph/workflows-agents) 区分了确定代码路径的 Workflow 与自主决定过程的 Agent。PaperLoom 适合采用同样的混合形状：

```text
外层：确定性 Contract / Guard Workflow
内层：自由 Research Agent
```

但现在引入 LangGraph 会与已有 Agents SDK Runner、Session、Trace 和 Redis Worker 重叠。当需要跨进程 Checkpoint/恢复、复杂人工中断或大量子图时再评估迁移。

### pytransitions：暂不引入

[pytransitions](https://github.com/pytransitions/transitions) 是成熟的 Python 有限状态机库，支持条件转移、队列、层级状态和图。当前只有少量 State/Event/Guard，标准库 `Enum + frozen dataclass + decide()` 更小、更易审计。只在出现层级/并行状态或转移表已经难以维护时引入。

### Temporal：当前明确不选

[Temporal Python SDK](https://github.com/temporalio/sdk-python) 面向分布式、可恢复、长时间异步业务流程，并已有 OpenAI Agents SDK 集成预览。它适合小时/天级 Run、跨机重放、人工审批和多 Worker Activity。PaperLoom 当前的分钟级单 Run 已有 Redis Queue、取消、超时和 Trace，引入 Temporal 只会增加运维面。

当前选型结论：

```text
OpenAI Agents SDK Runner
+ PaperLoom 自有的纯 Python ResearchProtocol 状态机
+ 现有 Corpus / Validator / Trace
```

先不增加第三方状态机或工作流依赖。

## 12. 最小验收场景

| 问题 | 必须选择的 Contract | 发布前条件 |
| --- | --- | --- |
| “你好” | `DIRECT` | 受限 Direct Result，由 Runtime 渲染 |
| “这个论文库有多少篇” | `CATALOG` | 当前轮完整 Inventory Result |
| “列出 2024 年的论文标题” | `CATALOG` | 只输出卡片中的结构化字段 |
| “推荐和 LLM 原理相关的” | `RESEARCH` | 每个推荐 Block 绑定可追溯的 Source Quote；蕴含关系由 Eval 评分 |
| “为什么 A 比 B 更适合入门” | `RESEARCH` | A/B 的 Content Block 均绑定可追溯 Source Quote；完整性由 Eval 评分 |
| 答案已有 Quote 但 Ref 不在同 Block | `RESEARCH` | `UNCITED_CONTENT_BLOCK`，回到 `ACTIVE` 绑定已有 Quote、补读或删除 Block |
| 答案引用完整但 Markdown 格式错误 | `RESEARCH` | `ANSWER_FORMAT_ERROR`，进入 `REPAIR`，不重新研究 |

## 13. 当前结论

合理的 Harness 不是一条固定研究流水线，也不是完全自由的 ReAct。它是：

```text
少量显式 Answer Contract
+ Contract 内部的自由研究
+ 确定性的状态前置条件
+ 确定性的来源追溯发布门
+ 只评测语义质量的离线 Eval
```

这样新问题首先落到 Contract、状态转移或 Eval 缺口，而不是继续向长 Prompt 追加一条特例。
