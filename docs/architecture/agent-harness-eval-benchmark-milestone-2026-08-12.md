# Agent Harness、Eval 与 Benchmark 里程碑

> 状态：截至 2026-08-12 的已实现系统说明。
>
> 代码基线：`184d2b9`。
>
> 冻结 Snapshot：`a61e8c1c240b2e8873b88d20da497b9ec0b98d9631c77a3e05c0634eeb92ecd3`。
>
> 通过 Run：`20260812T020304Z-cd6e7648`。

本文说明三个不同对象：

```text
Agent Harness = 在线执行与发布控制系统
Eval          = 对已完成 Run 的评价方法、指标与评分器
Benchmark     = 一套冻结语料、问题、预期行为和执行程序，用于实际运行 Eval
```

形式化关系为：

```text
EvalReport = Eval(
  HarnessVersion,
  ModelVersion,
  CorpusSnapshot,
  BenchmarkCases,
  ExecutionTrace
)
```

## 1. 系统总览

PaperLoom 不是固定步骤的研究工作流，也不是完全不受约束的 ReAct Agent。当前结构是：

```text
Java Product / Redis Job
          |
          v
LiveResearchChatHarness
          |
          v
OpenAI Agents SDK Runner
    |                 |
    | free choice     | deterministic guard
    v                 v
Model <-> Tools -> ResearchProtocol
                    |
                    v
             Validated Submission
                    |
                    v
             Normalized Run + Trace
```

模型自由决定检索词、候选论文、阅读位置、Research Skill 和停止时机；确定性代码控制 Scope、Tool 授权、
Contract 状态、Source Quote 身份和答案发布条件。

主要实现入口：

| 职责 | 代码 |
| --- | --- |
| HTTP 请求转为 Harness Turn | [`ResearchHarnessService.run_job`](../../harness_py/transport/service.py#L54) |
| Redis Job 生命周期 | [`RedisResearchWorker`](../../harness_py/transport/redis_worker.py#L86) |
| 一轮产品编排与异常收口 | [`LiveResearchChatHarness.run_turn`](../../harness_py/orchestration/live_chat.py#L44) |
| Runtime 抽象与生产实现选择 | [`build_harness_runtime`](../../harness_py/orchestration/runtime.py#L67) |
| Agents SDK 主循环 | [`AgentsSdkHarnessRuntime._run_agent`](../../harness_py/orchestration/agents/runtime.py#L149) |
| Tool 适配与执行 | [`build_agent_tools`](../../harness_py/orchestration/agents/tools.py#L59) |
| Protocol 纯状态机 | [`research_contract.py`](../../harness_py/orchestration/research_contract.py) |
| 产品 Run 输出 | [`build_harness_run`](../../harness_py/orchestration/run_output.py#L10) |

## 2. Harness 输入与执行

### 2.1 Turn 输入

一次运行输入可写为：

```text
TurnInput = (
  user_question,
  conversation_history,
  authorized_corpus_scope,
  research_memory,
  retry_context,
  run_limits
)
```

对应实现：

- 请求的 `user_id` 与 `scope.paper_ids` 由 Java 传入，Python 不扩大授权范围：
  [`ResearchHarnessService.run_job`](../../harness_py/transport/service.py#L54)。
- `ConversationState` 保存跨 Turn 消息、已选择论文和已接受证据：
  [`ConversationState`](../../harness_py/orchestration/conversation.py#L17)。
- 只保留最近的完整 user/assistant 消息对，不截断单条历史：
  [`_bounded_history`](../../harness_py/orchestration/agents/runtime.py#L300)。
- 当前用户问题、历史、已接受 Evidence Card 与 Retry Context 最终装配到 SDK Run：
  [`AgentsSdkHarnessRuntime._run_agent`](../../harness_py/orchestration/agents/runtime.py#L149)。

### 2.2 ReAct 循环

```text
Model
  -> requests Tool Call(s)
  -> Protocol checks action
  -> Tool executes
  -> Tool Result returns to Model
  -> ...
  -> one Submission Tool
  -> Submission Validator
  -> COMPLETE
```

模型可以在一次响应中规划多个非提交 Tool，但请求级状态按顺序更新：

```text
parallel_tool_calls = true
max_function_tool_concurrency = 1
```

配置见 [`AgentsSdkHarnessRuntime._run_agent`](../../harness_py/orchestration/agents/runtime.py#L149)。只有被接受的
Submission Tool Result 才能终止 SDK Runner，见
[`tools_to_final_output`](../../harness_py/orchestration/agents/tools.py#L92)。

### 2.3 Tool 集合

```text
Research Skill:
  get_research_skill

Corpus:
  search_paper_candidates
  find_papers_by_identity
  search_paper_content
  get_paper_structure
  read_paper_content
  get_citation_edges（数据支持时）

Submission:
  submit_direct_answer
  submit_catalog_answer
  submit_research_answer
```

Tool Schema 由 [`ReadingCorpusTools.definitions`](../../harness_py/corpus/tools.py#L104) 和三个 Submission Tool
Definition 共同组成；统一 SDK 适配位于
[`build_agent_tools`](../../harness_py/orchestration/agents/tools.py#L59)。

### 2.4 Corpus 的实际服务路径

Python Tool 不直接访问 MySQL 或 Qdrant；`JavaCorpusGateway` 把带 `user_id`、Conversation Scope 和内部认证的
请求交给 Java Corpus API：

```text
ReadingCorpusTools
  -> JavaCorpusGateway
  -> InternalCorpusController
  -> CorpusRetrievalService
  -> HybridReadingLocationRetriever
  -> sparse Qdrant + optional dense Qdrant
  -> Reciprocal Rank Fusion
```

代码对照：

- 内部 Paper/Location API：
  [`InternalCorpusController`](../../src/main/java/io/github/chzarles/paperloom/controller/InternalCorpusController.java#L28)。
- Scope、论文可访问性与 Current Model 校验：
  [`CorpusRetrievalService.searchLocations`](../../src/main/java/io/github/chzarles/paperloom/service/CorpusRetrievalService.java#L138)。
- Sparse + Dense 检索与 RRF：
  [`HybridReadingLocationRetriever.retrieve`](../../src/main/java/io/github/chzarles/paperloom/service/HybridReadingLocationRetriever.java#L38)。
- Qdrant Sparse/Dense 查询：
  [`QdrantClient.searchLexical`](../../src/main/java/io/github/chzarles/paperloom/service/QdrantClient.java#L243) 与
  [`QdrantClient.searchDense`](../../src/main/java/io/github/chzarles/paperloom/service/QdrantClient.java#L253)。

## 3. 三类状态

```text
RunState = (ProtocolState, CorpusState, ExecutionState)
```

### 3.1 ProtocolState

```text
ProtocolState = (
  phase,
  contract,
  submission_attempt,
  validation_issues
)

phase    in {ACTIVE, REPAIR, COMPLETE}
contract in {null, DIRECT, CATALOG, RESEARCH}
```

类型定义见 [`ProtocolState`](../../harness_py/orchestration/research_contract.py#L76)，合法动作集合见
[`allowed_tool_names`](../../harness_py/orchestration/research_contract.py#L173)，状态转移纯函数见
[`decide`](../../harness_py/orchestration/research_contract.py#L187)。

### 3.2 CorpusState

```text
CorpusState = (
  authorized_papers,
  disclosed_locations,
  source_quotes
)
```

它表示本次 Run 中 Agent 被合法展示过什么，不表示数据库权限或论文 public/private 状态。授权链为：

```text
Discover Paper -> Authorize Paper
Locate Content -> Disclose Location
Exact Read     -> Create Source Quote
```

对应代码：

- Paper Discovery：[`search_paper_candidates`](../../harness_py/corpus/tools.py#L241)。
- Content Location：[`search_paper_content`](../../harness_py/corpus/tools.py#L268)。
- Structure Navigation：[`get_paper_structure`](../../harness_py/corpus/tools.py#L319)。
- Exact Read：[`read_paper_content`](../../harness_py/corpus/tools.py#L331)。
- 请求级状态 Owner：[`ResearchRunContext`](../../harness_py/orchestration/agents/context.py#L29)。

集合在 Run 内单调增长：

```text
P_t subseteq P_(t+1)
L_t subseteq L_(t+1)
Q_t subseteq Q_(t+1)
```

### 3.3 ExecutionState

`RunControl` 管理取消、Deadline、模型用量和最近完成的边界，见
[`RunLimits`](../../harness_py/orchestration/run_control.py#L11) 与
[`RunControl`](../../harness_py/orchestration/run_control.py#L24)。当前默认值为：

```text
max_wall_clock_ms            = 600000
max_model_visible_tool_chars = 16000
max_history_chars            = 32000
```

当前没有单轮 Token 上限或 Token 预检；Token 只累计到 Usage。

## 4. Answer Contract

```text
AnswerContract = DIRECT | CATALOG | RESEARCH
```

枚举定义见 [`AnswerContract`](../../harness_py/orchestration/research_contract.py#L23)。

| Contract | 允许输出 | 提交 Tool | 来源 |
| --- | --- | --- | --- |
| `DIRECT` | 问候、能力、超范围、一个阻塞澄清 | `submit_direct_answer` | Runtime 受限渲染 |
| `CATALOG` | Count、标题、作者、年份、Venue、DOI、arXiv ID | `submit_catalog_answer` | 当前 Turn 的 Paper Result |
| `RESEARCH` | 推荐、摘要、方法、贡献、比较和解释 | `submit_research_answer` | Agent Markdown + Source Quotes |

### 4.1 Catalog 与 Research 的边界

```text
MetadataSufficient(answer) =
  隐藏所有论文正文，只保留允许的书目字段后，
  答案是否仍能被完整产生

true  -> CATALOG
false -> RESEARCH
```

因此：

```text
“库里有多少篇论文”                -> CATALOG
“列出这些论文的标题和年份”        -> CATALOG
“推荐和 LLM 原理相关的论文并解释” -> RESEARCH
“A 与 B 哪篇更适合入门”           -> RESEARCH
```

共享 Prompt 规则见 [`research_agent_instructions`](../../harness_py/orchestration/research_contract.py#L589)，三个
提交 Schema 见 [`direct_answer_tool_definition`](../../harness_py/orchestration/research_contract.py#L650)、
[`catalog_answer_tool_definition`](../../harness_py/orchestration/research_contract.py#L667) 和
[`research_answer_tool_definition`](../../harness_py/orchestration/research_contract.py#L687)。

### 4.2 Contract 如何选择

当前不使用独立 Router：

```text
submit_direct_answer   -> DIRECT
submit_catalog_answer  -> CATALOG
submit_research_answer -> RESEARCH
```

模型根据 Prompt 选择 Submission Tool；Tool 唯一确定 Contract；Protocol Guard 固定 Contract。Contract 的初始
语义选择仍是软约束，确定性代码只限制选定 Contract 可以发布什么。

### 4.3 指代消歧的当前边界

当前规则为：

```text
存在唯一 Paper 先行词 -> 可继续研究
不存在唯一先行词     -> DIRECT / needs_clarification
```

该判断目前由 [`research_agent_instructions`](../../harness_py/orchestration/research_contract.py#L589) 中的 Prompt
驱动，不存在确定性 `resolve_reference(history)`。Benchmark 负责检测模型是否遵守它，不把一次通过解释为普遍证明。

## 5. Protocol 状态机

### 5.1 转移规则

```text
ACTIVE + ACCEPTED
  -> COMPLETE

ACTIVE + FORMAT_ISSUE
  -> REPAIR(contract = submitted_contract)

ACTIVE + MISSING_CONTRACT_INPUT
  -> ACTIVE(contract = submitted_contract)
```

`FORMAT_ISSUE` 只允许修复同一个 Submission；`MISSING_CONTRACT_INPUT` 允许继续取得当前 Contract 所需的
Paper Result 或 Source Quote。第一次 Submission 后 Contract 冻结，不能切换 Contract 逃避约束。

纯决策实现为 [`decide`](../../harness_py/orchestration/research_contract.py#L187)，Context Adapter 在
[`ResearchRunContext.apply_protocol`](../../harness_py/orchestration/agents/context.py#L163) 保存状态并记录
`protocol.transition`。

### 5.2 Submission 处理

```text
Submission Tool
  -> validate_submission(contract, payload, facts)
  -> decide(state, submission_event, facts)
  -> renderer(contract)
  -> accepted normalized draft
```

统一入口为 [`validate_submission`](../../harness_py/orchestration/research_contract.py#L280)，SDK Tool Adapter 位于
[`_invoke_submission`](../../harness_py/orchestration/agents/tools.py#L354)。

## 6. Research Provenance

### 6.1 Source Quote

```text
SourceQuote = (
  source_quote_ref,
  paper_id,
  location_ref,
  source_span,
  span_text,
  page,
  section,
  visual_regions
)
```

Paper Card、Search Preview、Location Preview 和模型自己复制的文字都不是可引用证据。只有 Exact Read 后进入
Known Source Quotes 的 `source_quote_ref` 可以发布。

### 6.2 Block 发布条件

```text
ContentBlock(b) = kind(b) in {paragraph, list_item, table_row}
StructuralBlock(b) = kind(b) in {heading, table_header, thematic_break}

CanPublishResearch =
  for every ContentBlock b:
    refs(b) is not empty
    and refs(b) subseteq KnownSourceQuotes
```

Agent 在 Markdown 同一 Block 中写入 `[[source_quote_...]]`。校验见
[`_validate_research_submission`](../../harness_py/orchestration/research_contract.py#L459)，接受后由
[`build_harness_run`](../../harness_py/orchestration/run_output.py#L10) 渲染成用户可见数字引用。

确定性保证：

```text
引用来自正式 Exact Read
引用能解析到 Paper / Location / Source Span
每个事实内容块至少绑定一个 Known Source Quote
模型不能伪造 Source Quote Ref
```

它不保证 Source Quote 在语义上蕴含结论；Grounding 由离线 Judge 测量。

## 7. 会话记忆与输出

长期记忆遵循：

```text
答案正式引用了什么 -> 下一轮才记住什么
```

已接受 Source Quote、其 Paper 和 user/assistant 文本进入 `ConversationState`；临时搜索结果和未引用位置不会
自动变成长期事实。更新逻辑见
[`ConversationState.updated_from_run`](../../harness_py/orchestration/conversation.py#L124)。

最终标准化 Run 包含：

```text
status
research_answer
memory_update
paper_candidates
evidence_ledger
citation_validation
react_trace
control / diagnostics
```

组装逻辑见 [`build_harness_run`](../../harness_py/orchestration/run_output.py#L10)。

## 8. Trace

Trace 是观测平面，不参与模型决策：

```text
run.started
model.request / model.response / model.error
tool.started / tool.completed / tool.error
protocol.transition
answer.validation
run.result / run.error
```

工具事件同时记录参数、内部结果、模型实际可见结果、执行前状态和执行后状态。Recorder 见
[`EvalRecorder`](../../harness_py/evaluation/eval_recorder.py#L32)，线上滚动保留见
[`TraceRetention`](../../harness_py/evaluation/eval_recorder.py#L22) 与
[`prune_trace_runs`](../../harness_py/evaluation/eval_recorder.py#L142)。Benchmark Trace 则保存在独立 Run 目录。

## 9. Eval 机制

Eval 是状态机之外的离线观测：

```text
EvalInput = (
  question,
  expected_contract,
  snapshot,
  trace,
  final_answer,
  evidence_ledger
)
```

| 层 | 评价对象 | 判定方式 | 主要实现 |
| --- | --- | --- | --- |
| Protocol | 状态转移是否合法 | 确定性 Replay | [`_assess_protocol_trace`](../../harness_py/evaluation/paperloom31.py#L1447) |
| Contract | 是否选择正确 Contract | Expected vs Actual | [`_assess_agent_case`](../../harness_py/evaluation/paperloom31.py#L1339) |
| Retrieval | Paper/Location/Span 是否召回 | 冻结 Target | [`_run_l1`](../../harness_py/evaluation/paperloom31.py#L1159)、[`_run_l2`](../../harness_py/evaluation/paperloom31.py#L1182) |
| Provenance | 引用身份和 Block Binding | 确定性 Validator | [`_assess_agent_case`](../../harness_py/evaluation/paperloom31.py#L1339) |
| Grounding | 引文是否支持结论 | LLM Judge | [`_judge_agent_case`](../../harness_py/evaluation/paperloom31.py#L1584) |
| Answer Quality | 是否完整、相关、有用 | LLM Judge | [`_judge_agent_case`](../../harness_py/evaluation/paperloom31.py#L1584) |
| Performance | 调用数、Token、时延 | Trace/Usage | [`_controlled_protocol_metrics`](../../harness_py/evaluation/paperloom31.py#L1557) 与 Run Usage |

确定性指标可以进入发布 Gate；Grounding 与 Answer Quality 是概率性测量，不参与在线发布，也不因低分自动
重新运行 Agent。Judge 客户端见 [`MiniMaxJudgeModel`](../../harness_py/evaluation/judge_model.py#L25)。

## 10. PaperLoom-31 Benchmark

### 10.1 三个冻结对象

```text
BenchmarkConfig
  = PDF manifest + hash + title + target policy + generator identity

ProductSnapshot
  = current Reading Model identity + 31 targets + generated cases

RunResult
  = code/model/snapshot identity + L0/G0/L1/L2/L3 + Trace + Usage
```

版本常量见 [`paperloom31.py`](../../harness_py/evaluation/paperloom31.py#L41)，Config 构造见
[`ensure_benchmark_config`](../../harness_py/evaluation/paperloom31.py#L121)，Snapshot 校验见
[`validate_snapshot`](../../harness_py/evaluation/paperloom31.py#L840)。

### 10.2 Snapshot 生成

```text
31 PDFs
  -> Product + MinerU Current Reading Model
  -> export Current Model Candidates
  -> deterministic Target selection
  -> automatic Question Generator
  -> Grounding Verifier
  -> Agent Case Builder
  -> immutable Product Snapshot
```

对应实现：

- 总入口：[`create_snapshot`](../../harness_py/evaluation/paperloom31.py#L190)。
- 导出 Current Model：[`export_current_model_candidates`](../../harness_py/evaluation/paperloom31.py#L301)。
- 选择每篇一个 Target：[`build_targets`](../../harness_py/evaluation/paperloom31.py#L328)。
- 稳定排序：[`_candidate_rank`](../../harness_py/evaluation/paperloom31.py#L484)。
- 自动出题与校验：[`_generate_target_question`](../../harness_py/evaluation/paperloom31.py#L514)。
- 构造 Agent Case：[`build_agent_cases`](../../harness_py/evaluation/paperloom31.py#L603)。

Hash Rank 只产生稳定选择顺序，不是相关性分数：

```text
rank(candidate) = SHA256(
  dataset_id
  + paper_key
  + location_type
  + page
  + content_hash
)
```

系统将 MinerU Reading Model 视为事实来源，不引入人工 Evidence Target。

### 10.3 Case Layout v5

```text
6  Single Paper Research
4  Cross-Paper Comparison
1  Follow-up with unique antecedent
1  Follow-up ambiguity control
1  Missing Evidence Control
1  Direct Greeting
1  Direct Clarification
1  Catalog Inventory
1  Open Research Recommendation
--------------------------------
17 Cases
```

Agent 只获得完整 31 篇 Scope、问题和 Case History；不会获得 Target ID、正确 Paper ID、Location Ref、Qrels 或
Expected Answer。Case 生成见 [`build_agent_cases`](../../harness_py/evaluation/paperloom31.py#L603)。

### 10.4 分层执行

`run_benchmark` 顺序执行五层，见
[`run_benchmark`](../../harness_py/evaluation/paperloom31.py#L930)：

| 层 | 检查内容 | 代码 |
| --- | --- | --- |
| L0 | PDF Hash、Processing、Current Model、Index、Target 完整性 | [`_run_l0`](../../harness_py/evaluation/paperloom31.py#L1056) |
| G0 | 管理员拥有、全局发布、普通用户可见、Scope 隔离 | [`_run_g0`](../../harness_py/evaluation/paperloom31.py#L1095) |
| L1 | 规范标题的 Metadata Retrieval | [`_run_l1`](../../harness_py/evaluation/paperloom31.py#L1159) |
| L2 | 单篇 Oracle Scope 内的 Location Retrieval + Exact Read | [`_run_l2`](../../harness_py/evaluation/paperloom31.py#L1182) |
| L3 | 完整 31 篇 Scope 下的真实 Agent Run | [`_run_l3`](../../harness_py/evaluation/paperloom31.py#L1263) |

L1/L2 指标为 Recall@K 与 MRR。L3 记录 Contract、Outcome、Protocol Replay、Provenance、Target
`returned/read/cited`、Judge 与 Usage。

### 10.5 Baseline 与 Internal Beta Gate

```text
BaselineEstablished = L0 and G0 and L1 and L2 and L3 all executed
```

Baseline 允许 Retrieval Miss 或语义低分；它表示系统已完整、可复现地测量，不表示通过发布条件。

当前 Internal Beta Gate 的确定性条件为：

```text
L0 passes
G0 passes
no Agent technical failure
no Scope leak
expected Contract matches actual Contract
Protocol Replay passes
Research Provenance passes
no fabricated or unresolvable citation
```

Gate 汇总见 [`run_benchmark`](../../harness_py/evaluation/paperloom31.py#L930)，单 Case 判定见
[`_assess_agent_case`](../../harness_py/evaluation/paperloom31.py#L1339)。

## 11. 当前里程碑结果

```text
Commit   = 184d2b9
Snapshot = a61e8c1c240b2e8873b88d20da497b9ec0b98d9631c77a3e05c0634eeb92ecd3
Run      = 20260812T020304Z-cd6e7648
```

```text
Internal Beta Gate = PASS

L1 Recall@1        = 1.0000
L2 Recall@1        = 0.4194
L2 Recall@5        = 0.7419
L2 Recall@10       = 0.8065

L3 Hard Pass       = 17 / 17
Contract Accuracy  = 17 / 17
Protocol Replay    = 1.0000
Provenance Pass    = 1.0000
```

正确解释是：

```text
协议控制在当前冻结 Case 上跑通
!= 所有 Retrieval 完美
!= 所有答案语义必然正确
!= Prompt 对任意表达都不会失效
```

当前最清晰的量化改进面是 L2 Evidence Retrieval，而不是 Protocol 状态机。

## 12. 保证边界

| 类别 | 当前机制 |
| --- | --- |
| 确定性保证 | Scope、Tool 授权链、状态转移、Contract 冻结、Submission Schema、Source Quote 身份、Block 引用覆盖、Trace Replay |
| Prompt 驱动 | 初始 Contract 选择、代词先行词判断、检索词、论文选择、Location 选择、停止时机 |
| 离线 Judge | Grounding、Answer Completeness、Recommendation Quality |

当前系统可以压缩为：

```text
自由语义决策
+ 确定性 Tool 授权
+ 确定性答案发布协议
+ 可回放 Trace
+ 分层离线 Eval
+ 冻结 Benchmark
```

## 13. 回归测试对照

| 不变式 | 聚焦测试 |
| --- | --- |
| 状态转移、Contract 冻结与合法动作 | [`test_submission_transitions_are_table_driven`](../../harness_py/tests/test_research_protocol.py#L25) |
| 每个 Research Content Block 绑定 Known Source Quote | [`test_research_validation_binds_every_content_block_to_known_quotes`](../../harness_py/tests/test_research_protocol.py#L205) |
| Catalog 与带理由 Recommendation 的边界 | [`test_submission_tools_distinguish_metadata_lists_from_recommendation_reasons`](../../harness_py/tests/test_research_protocol.py#L198) |
| v5 Case Layout、明确/歧义 Follow-up | [`test_agent_cases_are_deterministic_and_snapshot_is_valid`](../../harness_py/tests/test_paperloom31.py#L103) |
| Target Returned/Read/Cited 只作诊断 | [`test_agent_gate_treats_exact_target_coverage_as_diagnostic`](../../harness_py/tests/test_paperloom31.py#L167) |
| Prompt 身份冻结与推荐规则 | [`test_expanded_dataset_does_not_change_the_harness_contract`](../../harness_py/tests/test_golden_data.py#L179) |

这些聚焦测试验证代码不变式；`17/17` 全量 Benchmark 验证真实 Model、Tool、Corpus、Protocol 和 Judge 的组合
行为。两者不能互相替代。
