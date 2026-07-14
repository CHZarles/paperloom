---
title: Reading Model 与 harness_py 工具
description: PaperLoom 如何建模论文，以及 harness_py 如何发现论文、定位内容、读取原文和校验提交。
aside: false
pageClass: architecture-page
---

# Reading Model 与 harness_py 工具

Reading Model 是产品长期保存的论文结构。它回答页面、章节、表格、图片、公式和来源身份怎样被稳定
表达。单次请求进入 `harness_py` 后，`DockerMySqlProductCorpusStore` 把其中一部分投影成
`GoldenDataset` 和 `ReadingDocument`，`ReadingCorpusTools` 再向 Agents SDK 暴露具体 Function Tool。

完整 Reading Model 比当前 Python 检索面更丰富。这个差异允许我继续调整 BM25、以后加入 Dense
Retrieval，或替换 Model Provider，同时保留论文和历史引用的长期身份。

## Reading Model 的构成

### 1. Model Version 与 Readiness

`paper_reading_models` 保存 `model_version`、`model_status`、`is_current`、Parser Identity、Page /
Character Count、Failure Reason 与 Diagnostics。

Ready 是显式产品状态。Live Corpus 只选择 Current `READING_MODEL_READY`，不会根据某个派生文件或
Index Document 是否存在来推断论文能不能读。

### 2. Physical Page

`paper_pages` 保存 PDF Page Number、Page Text、Text Status、Text Hash、Character Count、Source Span
与 Parser Provenance。Textless 或 Parser Missing 的页仍然留在论文模型中，产品可以明确知道这一页
存在但当前没有可靠文本。

### 3. Section

`paper_sections` 保存 Section Title、Level、Page Range、Reading-order Range、Display Order、Section
Text 与 Source Span。章节结构独立于后续的 Chunk 或 Ranker 策略。

### 4. Canonical Reading Element

`paper_reading_elements` 是当前 Live Retrieval 的主要内容面。

| 维度 | 典型字段或类型 |
| --- | --- |
| 内容类型 | Heading、Paragraph、List、Table、Image、Figure、Chart、Formula、Footnote、Aside、Code |
| 顺序与结构 | Page Number、Reading Order、Section Title、Content-list Index |
| 可读内容 | Caption Text、Body Text、Searchable Text |
| 来源 | Parser Element、Source Object、BBox、Source Span、Parser Name / Version |
| 结构化信息 | Structured Payload、Raw Attributes |
| 关系 | Parent Reading Element、Attachment Role、Association Status |
| 导航 | Location Ref、Location Type、Location-not-created Reason |

`body_text` 与 `caption_text` 保存可读来源，`searchable_text` 是给 Retriever 使用的 Projection。搜索文本
可以合并 Caption 和 Body，但不会取代原文，也不会成为历史 Citation 的唯一身份。

### 5. Location

`paper_locations` 为 Page、Section、Table 与合适的 Figure 建立正式 Navigation Identity。当前一部分
Text 或 Deferred Formula 会使用 `reading_element_id` 作为 Live Harness 的 Location Fallback。

我先保证 Retained Content 能被准确读取，再逐步提高高层 Location Coverage。没有可靠位置的 Parser
Element 会留下明确缺口，不会获得一个猜出来的 Location。

### 6. Visual Asset

`paper_visual_assets` 保存 Page Screenshot、Table Crop、Figure Crop、Chart Crop 与 Parser Image 的
Availability / Failure、Owner Element、Page、Source Object、BBox、Object Key、Size 与 Checksum。

Live Harness 当前只加载 Availability。Binary Asset 仍由 Java 代码从 MinIO 打开。

## Builder 做了哪些建模判断

`PaperReadingModelBuilder` 会处理 Parser 语义到产品语义的转换：

- 保留 Physical Page 与 Reading Order；
- 将 Parser Type 映射成 Product Type，同时保留 Raw Attribute；
- 把 Table Caption Fragment 和 Figure Panel Label 关联到 Parent Element；
- 区分 Full Caption 与 Panel-only Caption；
- 记录 Parent、Attachment Role 与 Association Status；
- 对 Ambiguous / Unattached Relationship 明确标记，不猜 Ownership；
- 建立 Page、Section、Table 和适合的 Figure Location；
- 保存 BBox、Source Span、Parser Provenance 与 Structured Payload；
- 输出 Retention、Association、Location 与 Visual Gap Diagnostics。

这层模型把 Evidence 从某一次 Chunk Policy 中解耦出来。Parser 可以升级，Retriever 可以换，已经保存
的论文结构和来源身份仍然有稳定落点。

## 完整模型与 Live Projection 的差异

| 能力 | 完整 Reading Model | 当前 Python Live Projection |
| --- | --- | --- |
| Model Metadata / Readiness | 有 | 有 |
| Physical Page Table | 有 | 未直接加载 |
| Section Table | 有 | 未直接加载 |
| Typed Reading Element | 有 | 有，主要 Retrieval Surface |
| Formal Location Table | 有 | 主要使用 Element 携带的 Location 信息 |
| Visual Asset Record | 有 | 只加载 Availability |
| Vector / Embedding | 不属于 Canonical Truth 的必要字段 | 当前不使用 |

下面的工具设计只描述当前 Python Projection 已经提供的数据，不把完整模型里的潜在能力提前写进 Live
Answer Path。

## harness_py 怎样读取 Reading Model

[![harness_py 工具与状态变化图](/images/paperloom-evidence-flow.png)](/images/paperloom-evidence-flow.svg)

`build_agent_tools` 会把 `ResearchSkillRegistry`、`ReadingCorpusTools.definitions()` 和
`submit_research_answer` 组装成 SDK `FunctionTool`。每个工具只推进一种状态，下一步能做什么由
`ResearchRunContext` 与 `ReadingCorpusTools` 中已经记录的集合决定。

### Paper、Location、Evidence 是三种不同权限

Java 先把 Dataset 限定在当前用户可访问的 Paper ID 内。进入 Python 后，模型仍要通过 Candidate Search
或唯一身份解析看到某篇论文，才能检索它的位置；Location 必须先被检索结果公开，才能被准确读取；
只有读取结果会得到 Evidence ID。

```text
Java-authorized Dataset
-> disclosed Paper
-> disclosed Location
-> exact Read
-> Evidence Ledger
-> accepted Submission
```

Candidate Preview 只负责导航。把 Preview 和 Evidence 分开，可以阻止模型看到一段摘要后就把它当成
论文方法、结果或性能的引用依据。

### 状态保存在 Context，不交给模型自报

每个 `ResearchRunContext` 都创建独立的 `ReadingCorpusTools`，其中维护：

- `authorized_paper_ids`：在 Java Scope 内已经向模型公开、允许继续阅读的 Paper；
- `disclosed_location_refs`：`find_reading_locations` 已经返回过的 Location；
- `observations_by_evidence_id`：`read_locations` 创建的 Evidence Ledger；
- `trace`：模型调用过什么工具，以及模型实际看到了什么结果。

模型无法通过参数声明“我已经看过这个位置”。每次 Tool Call 都由业务代码检查集合成员关系，并在
调用前后保存状态快照。

### 工具结果有 Internal 与 Model-visible 两个版本

Corpus Tool 先产生 Internal Result，再经过 `model_facing_payload` 过滤后返回模型。Golden Anchor 等
评估字段不会出现在模型可见结果里。Eval Capture 可以同时保存两个版本，离线分析因而知道系统内部
发生了什么，也能复原模型当时实际获得了哪些信息。

### 工具错误是可修复反馈

无效 JSON、未公开 Paper、未公开 Location、未知 Evidence 和 Coverage 缺口都会返回结构化错误。同一个
Runner 会继续执行，模型可以改参数、补搜索、补读取，或删掉没有证据的声明。非预期业务异常才会上升
为技术失败。

### 最终答案也通过 Function Tool 提交

模型不能用一段自由文本直接结束研究。Model Settings 保持 `tool_choice=required`，供应商若返回纯文本，
Model Adapter 会把它归一化成内部 Continue Tool，提醒模型继续调用 `submit_research_answer`。

最终提交必须独占一次 Model Step。`tools_to_final_output` 只接受 `accepted=true` 的 Draft，其他工具即使
成功也不会结束 Runner。

## 模型可调用的工具

| Tool | 设计目的 | 前置状态 | 返回给模型 | 状态变化 | 可否直接引用 |
| --- | --- | --- | --- | --- | --- |
| `get_research_skill` | 按需加载研究范式，保持一个连续 Runner Loop | 无 | 方法步骤、Evidence Standard、Answer Guidance | 记录 `skills_used` | 否 |
| `search_paper_candidates` | 主题发现、Corpus Inventory、Metadata Filter | Java-authorized Dataset | Paper Cards、数量、分页状态 | 公开返回的 Paper | Metadata 可回答；论文内容不可引用 |
| `find_papers_by_identity` | 用结构化线索消歧特定论文 | Java-authorized Dataset | Resolved / Ambiguous / Not Found | 只有唯一 Match 公开 Paper | 否 |
| `find_reading_locations` | 在已公开论文中找下一步阅读位置 | Paper 已公开 | `location_ref`、Section、Page、Element Type、Preview | 公开返回的 Location | 否 |
| `read_locations` | 读取准确来源并建立 Evidence 身份 | Location 已公开 | Exact Span、Provenance、Visual Availability、Evidence ID | 写入 Evidence Ledger | 是，唯一入口 |
| `submit_research_answer` | 提交用户可见结果并请求结束 Run | 已知 Evidence 或明确的非完整 Outcome | Accepted / Validation Error | Accepted 后形成 Final Output | 只能引用 Known Evidence |

## 每个工具为什么存在

### `get_research_skill`

Harness 当前提供 22 种研究范式，包括精确事实提取、方法复现、深度比较、研究谱系、多跳推理、知识
边界、矛盾处理、结构化信息读取和复杂约束组合。

Skill 返回 `when_to_use`、`instructions`、`evidence_standard` 与 `answer_guidance`。它只影响研究策略，
无权公开 Paper、Location 或 Evidence。这样可以给模型方法指导，又不会重新引入固定 Stage Runtime。

### `search_paper_candidates`

这个工具同时服务两类问题。

- 对 Corpus 数量、论文列表、作者、年份、Venue 和 Identifier 问题，Paper Card 是权威 Metadata 来源；
- 对论文内容问题，它负责发现候选，并把返回的 Paper 加入可继续阅读的集合。

它支持 Query、Paper ID、Author、Venue、Year Range、Offset 和 Limit。空 Query 配合足够大的 Limit 可以
枚举当前固定 Corpus。返回 Card 仍不能支撑 Method、Finding、Performance 或 Limitation Claim。

### `find_papers_by_identity`

这个工具处理“用户指的是哪一篇”这类身份问题，接受 Title、Filename、DOI、arXiv ID、Author、Year
和 Paper ID。只有一个 Match 时才公开论文；多个 Match 返回 `ambiguous`，不会任意挑一篇继续研究。

它不承担主题搜索和推荐。把 Identity Resolution 从 Topical Discovery 中拆出来，是为了让歧义能够
明确暴露给 Agent 和用户。

### `find_reading_locations`

这个工具只接受已经公开的 Paper ID。它可以使用 Query、Section Hint、Element Type Hint、Page Range
和 `top_k`，在请求内存中的 Reading Documents 上检索。

当前 Product Path 的排序主要来自：

- Reading Element 全文 BM25；
- Lead 与 Section BM25；
- Exact Phrase 和 Section Hint；
- Element Type 的轻量 Boost，Parser Label 不作为硬过滤；
- 相邻 Paragraph 的支持；
- Broad Query 与 Multi-paper Query 的候选扩展和 Query-term Coverage。

只有提供 Physical Page Projection 的 Dataset 才会启用 Passage Window 与 Page-grounding 补充面。当前
Product DB Projection 以 `paper_reading_elements` 为主，没有直接加载 Page Table 作为独立 Retrieval
Surface。

返回值包含不可引用 Preview 与 `location_ref`。所有返回 Location 会进入
`disclosed_location_refs`，为下一步 Exact Read 建立前置状态。

### `read_locations`

这个工具只读取已经公开的 Location Ref。每个读取结果保留：

- Paper、Version、Section、Page 与 Element Type；
- 准确 `span_text`；
- BBox、Parser Name / Version 与 Source Kind；
- Table / Figure / Formula Identity；
- Page、PDF、Table、Figure Screenshot Availability。

Evidence ID 由 Paper、Location、Element Type 和 Page 稳定生成。读取结果进入
`observations_by_evidence_id`，后面的 Citation Validator 只认可这个 Ledger 或上一轮已经接受的 Evidence。

### `submit_research_answer`

提交结构包括 `outcome`、`markdown` 和可选 `fields`。Outcome 只有四种：`answered`、
`needs_clarification`、`partial`、`abstained`。

提交会依次检查：

1. Outcome、Markdown 与 Fields 结构；
2. 是否手写数字引用，或引用未知 / 不可引用的 Evidence ID；
3. 本轮已经读过 Paper Content 时，Answered / Partial 是否实际引用 Evidence；
4. 答案提及的论文是否完成 Candidate、Read、Cited；
5. Citation 是否只有 Heading 等弱导航 Evidence；
6. `submit_research_answer` 是否独占最终 Tool Step。

`evaluate_evidence_coverage` 只使用当前问题、论文 Metadata、可见 Tool Trace 和 Known Evidence。它不读取 Golden Case、
Expected Anchor 或 Human Label。拒绝原因会返回 Agent，让它补读、补引、缩小答案，或调整 Outcome。

## 一个多论文比较会怎样走工具

假设用户要求比较两篇已选论文的方法和结果，常见轨迹会是：

```text
search_paper_candidates
-> 公开两篇 Paper
find_reading_locations(paper A, method query)
find_reading_locations(paper B, method query)
read_locations(method locations)
find_reading_locations(paper A + B, result query)
read_locations(result locations)
submit_research_answer
-> evaluate_evidence_coverage 检查 A / B 是否都 Read + Cited
-> Accepted，或返回缺口继续同一个 Runner
```

模型可以改变 Query、重复搜索或先读某一篇。协议不会规定固定顺序，但会保证每个 Evidence 都经过
Paper Disclosure、Location Disclosure 和 Exact Read。

## 跨回合记忆怎样收敛

SDK Session 只在当前 Run 内合并文本历史。长期 Conversation State 由 Java 保存，下一轮 Python 收到
的是精简 History 与 Previous Evidence Card。

本轮只有进入已接受答案的 Evidence 会被提升为下一轮 Research Memory。搜索过的 Paper、看过的
Preview 和未引用 Evidence 不会自动回灌。这个规则让 Follow-up 可以继续引用已有证据，也避免一次
探索把后续会话永久污染。

## 模型和代码各自决定什么

模型决定：

- 是否需要 Clarification；
- 用什么 Query，搜索几次；
- 阅读哪些 Location；
- 是否调用一个或多个 Research Skill；
- 怎样组织回答，何时 Partial 或 Abstain。

代码决定：

- 当前 Dataset 包含哪些 Paper；
- 哪些 Paper 和 Location 已经公开；
- 哪次 Read 能创建 Evidence；
- 哪些 Evidence ID 可以引用；
- 多论文答案是否完成 Candidate / Read / Cited / Substantive Coverage；
- 一个 Draft 是否有资格结束 Run。

当前实现把研究策略交给模型，证据资格和产品权限继续由代码判断。

## Vector 还没有进入 Live Harness

当前 Harness 没有 Vector Retrieval。未来可以从 canonical Reading Element 派生 Embedding，做 Lexical
+ Dense Candidate Fusion，或用 Eval Trace 训练 Reranker。是否进入默认链路，要看 Held-out Candidate、
Read、Cited、Hard Pass、Latency、Cost 和 Failure 的完整变化。

## 继续阅读

- [系统架构](/project/architecture)
- [评估方法](/project/evaluation)
- [Harness Source Guide](https://github.com/CHZarles/paperloom/blob/main/harness_py/README.md)
- [完整技术文档](https://github.com/CHZarles/paperloom/blob/main/docs/architecture/reading-model-and-agent-tools.md)
