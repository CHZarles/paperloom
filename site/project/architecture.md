---
title: 系统架构
description: PaperLoom 当前研究回答链路、状态生命周期、Harness Runtime 与工具协议。
aside: false
pageClass: architecture-page
---

# 系统架构

PaperLoom 同时运行两条生命周期完全不同的链路。一条把 PDF 建成长期保存的 Reading Model，另一条
在单次会话授权范围内启动 Agent，检索、阅读、引用并提交答案。把两条链路混在一起，很容易把
“仓库里存着什么”误写成“当前回答用到了什么”。

研究链路遵守一条硬规则：**论文内容声明必须连接到当前会话有权访问、并且 Agent 已经读取过的原文
证据。** 权限、工具状态、Evidence Ledger、最终校验和历史引用共同维护这条约束。

## 系统架构图

[![PaperLoom 当前系统架构图](/images/paperloom-system-architecture.png)](/images/paperloom-system-architecture.svg)

图里有三个运行边界：

| 代码范围 | 持有的状态和职责 | 不负责什么 |
| --- | --- | --- |
| Folio | 论文选择、结构化点击锚点、研究进度、答案与引用重开 | 不从回答文本反推权限或来源身份 |
| `ChatHandler` 到 `PythonResearchHarnessClient` | 用户权限、`SourceScope`、配额、取消、会话、Research Memory、Reference Mapping | 不替模型选择搜索词和阅读位置 |
| `harness_py` | 授权范围内的 Agent Loop、内存检索、工具状态、Evidence 与提交校验 | 不扩大用户论文范围，也不持久化产品权限 |

Java 保存产品必须长期信任的状态，Python 保存一次研究回合里不断变化的状态。OpenAI Agents SDK
负责通用的 Model / Tool Loop；工具能改变哪些集合、什么结果可以结束 Run，都由项目代码处理。

## 两条生命周期

### Reading Model 构建链

```text
Research PDF
-> MinerU parser artifact
-> PaperReadingModelBuilder
-> Pages / Sections / Typed Elements / Locations / Relationships
-> MySQL canonical Reading Model
-> MinIO PDF / parser artifacts / screenshots / crops
```

这条链路在上传和重新解析时运行。Parser Artifact 会被保留，方便重建和排障；产品长期依赖的是
`PaperReadingModelBuilder` 产出的版本化模型。换 Parser、调整检索或增加派生索引时，论文在产品里的
身份不需要跟着改变。

### Research Turn 运行链

```text
ChatHandler
-> ProductReadingConversationService
-> PythonResearchHarnessClient
-> POST /v1/research/stream
-> ResearchHarnessService
-> LiveResearchChatHarness
-> AgentsSdkHarnessRuntime
-> OpenAI Agents SDK Runner
```

这条链路每个用户回合都会重新创建。它拿到的 Corpus 已经被 Java 限定，Python Context、Session、
Tools 和 Model Client 只活到本次 `Runner.run` 结束。

## 一次研究回合怎样运行

### 1. Java 锁定产品范围

`ChatHandler` 接收 WebSocket 或 API 消息，确认用户、会话和当前可访问论文。论文选择、点击过的来源、
位置以及阅读动作以结构化字段进入 Effective Scope，不依赖模型从自然语言里猜。

### 2. 组装历史与 Research Memory

`ProductReadingConversationService` 读取近期会话、点击锚点和上一轮已经接受的引用。跨回合记忆只保留
进入过已接受答案的 Paper 与 Evidence；本轮搜索过但没有引用的候选不会自动成为长期事实。

### 3. 预留用量并建立流式请求

`PythonResearchHarnessClient` 在发请求前预留 Token 配额，将 `SourceScope`、History、Previous
Evidence 和模型选项序列化到请求中，再调用 `/v1/research/stream`。Python 返回 NDJSON Progress，
Java 负责取消、异常收口、用量结算以及向前端转发进度。

### 4. Python 只访问已授权论文

`ResearchHarnessService` 要求 `user_id` 与 `scope.paper_ids` 非空，再通过 `JavaCorpusGatewayReader`
调用 Java Corpus API。Java 用 Qdrant 检索 Current Reading Model 候选并从 MySQL 精确读取。Python
没有“查全库再自行过滤”或整批加载 Reading Element 的步骤。

### 5. 创建本轮不可变输入

`LiveResearchChatHarness` 再次裁剪 Metadata Dataset，分配 `run_id`，可选地打开 `EvalRecorder`，然后创建
`TurnExecutionInput`。这个对象包含问题、文本历史、上一轮 Evidence、取消函数和进度监听器，交给
Runtime 后保持不变。

### 6. 装配一个请求级 Agent Runtime

`AgentsSdkHarnessRuntime` 为本轮创建：

- 一个 `ResearchRunContext`；
- 一个请求级 `ReadingCorpusTools`；
- 一个 Model Adapter；
- 一个 `RequestBackedSession`；
- 一组按当前 Corpus 生成的 Function Tools；
- 一个 Agents SDK `Agent` 和 `Runner`。

Runtime 不持有长期会话。下一轮会重新装配这些对象，避免授权集合、Evidence ID 或工具轨迹泄漏到
其他请求。

### 7. Runner 执行连续 Tool Loop

Agent 没有固定 Stage Machine，也没有预设“必须先规划再检索”的阶段序列。模型可以在一个连续循环里
改写 Query、换论文、补读位置、调用 Research Skill，或在提交被拒后继续修正。

Model Settings 要求每一步使用工具，`reset_tool_choice=False` 防止后续轮次退回自由文本。模型可以在
一次响应里提出多个 Function Call，但 `max_function_tool_concurrency=1` 会顺序执行它们。授权状态会被
前一个工具修改，串行执行可以保证后一个工具看到确定的 Paper、Location 和 Evidence 集合。

### 8. 只有接受的提交能结束 Run

普通工具结果都会回到同一个 Agent。`submit_research_answer` 也先作为工具执行，只有返回
`accepted=true` 时，`tools_to_final_output` 才让 SDK 结束 Run。未知 Evidence、错误引用、缺失 Coverage
或与其他工具同批提交都会返回结构化错误，模型仍在原来的 Runner 中继续修正。

### 9. Java 持久化产品结果

Python 把结构化 Outcome、Markdown、Citations、Usage、Research Memory 和可选 Trace 返回 Java。
Java 将接受的 Evidence 映射成 Product Reference，保存到会话，并在用户以后重开引用时重新检查权限。

## Python Runtime 里各对象的职责

| 对象 | 生命周期 | 负责的内容 |
| --- | --- | --- |
| `TurnExecutionInput` | 单次 Run，只读 | Question、History、Authorized Dataset、Previous Evidence、Progress、Cancellation |
| `ResearchRunContext` | 单次 Run，可变 | Tool Trace、Model Usage、Tool Call Group，以及本轮 Corpus Tool State |
| `ReadingCorpusTools` | 单次 Run，可变 | Paper Disclosure、Location Disclosure、BM25、Exact Read、Evidence Ledger |
| `RequestBackedSession` | 单次 SDK Run | 合并请求携带的文本历史和当前输入，不充当长期数据库 |
| `ResearchRunHooks` | 每次 Model Call | Model Call ID、Token、Latency、同批 Tool Call 关系 |
| Model Adapter | 单次 Run | Chat Completions / Responses 协议、HTTP Capture、纯文本响应归一化 |
| `Agent` | 单次 Run 配置 | Instructions、Model、Tools、Tool-use Behavior |
| `Runner` | 单次 Run 执行器 | 继续执行 Model -> Tool -> Model，直到提交被接受或发生取消/技术失败 |

`ResearchRunContext` 主要维护三组逐步增长的状态：已公开 Paper、已公开 Location、已创建 Evidence。
工具调用前后的快照会进入 Eval Capture，因此离线分析能区分 Retriever
没暴露位置、Agent 没读、读了没引，以及引用只有 Heading 的情况。

## harness_py 的工具与状态

[![harness_py 工具与状态变化图](/images/paperloom-evidence-flow.png)](/images/paperloom-evidence-flow.svg)

工具按“研究权限如何逐步收窄”来设计：

| 阶段 | Tool | 前置条件 | 状态变化 |
| --- | --- | --- | --- |
| 方法指导 | `get_research_skill` | 无 | 只记录本轮采用的研究范式，不改变 Corpus 权限 |
| 论文发现 | `search_paper_candidates` | Java-authorized Corpus | 返回权威 Metadata，并公开返回的 Paper |
| 身份解析 | `find_papers_by_identity` | 结构化身份线索 | 只有唯一 Match 会公开 Paper；Ambiguous 不授权 |
| 位置检索 | `find_reading_locations` | Paper 已公开 | 返回不可引用 Preview，并公开 `location_ref` |
| 准确读取 | `read_locations` | Location 已公开 | 返回原文 Span，创建 `ev_...` 并写入 Ledger |
| 最终提交 | `submit_research_answer` | 已有足够状态，或明确选择 Clarify / Partial / Abstain | 校验 Outcome、Citation、Coverage；接受后结束 Run |

Candidate Preview 的用途是导航。它会让模型知道下一步读哪里，但不能支撑论文方法、结果、性能或
局限性声明。`read_locations` 是当前唯一能创建可引用 Paper-content Evidence 的工具。

[继续阅读 `harness_py` 的工具设计](/project/reading-model#harness-py-怎样读取-reading-model)

## 三种状态不会混在一起

| 保存位置 | 保存内容 | 会不会进入下一轮模型输入 |
| --- | --- | --- |
| `ConversationService` 与 MySQL | 权限、会话、已接受 Reference、Research Memory | 会，以精简 History 和 Evidence Card 进入 |
| `ResearchRunContext` | Disclosed Paper、Location、Evidence、Trace | Run 结束后释放；只有已引用 Evidence 被提升为长期记忆 |
| `EvalRecorder` 输出 | `events.jsonl`、`result.json`，以及离线派生的 Golden Score、Judge / Human Label | 不驱动产品回答，只用于离线分析 |

这个区分避免了两类隐性状态：SDK Session 不会成为第二个会话数据库，Eval Dump 也不会反过来参与
线上回答。

## 当前 Corpus 与 Retrieval

Python 从 MySQL 加载：

- Paper Title、Abstract、Authors、Venue、Year、DOI、arXiv ID 与 Filename；
- Current Ready Model 的 Version、Parser、Page / Character Count；
- `paper_reading_elements` 的类型、顺序、Section、Text、Location、BBox 与 Provenance；
- `paper_visual_assets` 推导出的 Page / Table / Figure 可查看状态。

当前 Product Corpus 以 Reading Element 为检索面。`find_reading_locations` 在请求内存中计算全文 BM25，
同时参考 Lead、Section、Exact Phrase、Element Type、相邻 Paragraph 和 Query Coverage；Broad Query 与
Multi-paper Query 会扩大候选面。当前 Live Product Projection 没有把 `paper_pages` 作为独立检索面，
也没有调用 Vector、Embedding 或 Dense Retrieval。

完整 Reading Model 仍然保存 `paper_pages`、`paper_sections` 和 `paper_locations`。这些是产品长期模型，
不等于已经接入当前 Python Retrieval 的独立 Surface。

## Evidence 如何变成历史引用

一次引用会经过下面的身份链：

```text
Reading Element / Location
-> read_locations exact span
-> deterministic Evidence ID
-> accepted [[evidence_id]] citation
-> Java Product Reference Mapping
-> historical permission check
-> reopen page / text / table / figure evidence
```

模型只生成结构化 Evidence Marker。最终数字编号和 Reference 列表由 Python 与 Java 根据已知 Evidence
渲染，模型不能手写一个 Sources 区域来绕过校验。

## 继续阅读

- [Reading Model 与 `harness_py` 工具](/project/reading-model)
- [评估方法](/project/evaluation)
- [完整 Architecture Documentation](https://github.com/CHZarles/paperloom/blob/main/docs/architecture/overview.md)
- [Architecture Decision Records](https://github.com/CHZarles/paperloom/tree/main/docs/adr)
