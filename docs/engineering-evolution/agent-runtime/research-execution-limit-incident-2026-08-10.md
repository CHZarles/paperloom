# Research Execution Limit Incident

## 文档状态

- 事件状态：已解决（Commit `6fb894e` 已部署，线上同问题复现通过）
- 首次记录：2026-08-10 18:16 CST
- 线上环境：`https://paperloom.me`
- 影响入口：共享游客账户
- 调查原则：区分用户看到的文案、运行时终态和真正的触发原因；结论只写已取得证据的部分

## 事件背景

PaperLoom 提供共享游客模式。所有访问者以同一个普通用户“游客”登录，可以上传论文、管理论文集并发起研究问答。游客之间共享该账户下的会话、论文和额度。

2026-08-10，游客账户在询问“你详细讲解seedream4.0这个工作”时，先看到以下失败回答：

> This research request reached its execution limit before a verifiable answer was ready. Narrow the question or start a new turn.

线上最终保存了三个同问题 Run：一个真实 Deadline、一个快速完成但复述失败文案的 Run，以及一个生成了完整长答案的 Run。用户将完整长答案感知为第二次输出，说明调查时还必须区分用户可见交互次数与服务端持久化 Run 数量。

### 本文术语

- “第一次请求”或“失败 Run”：Conversation Record 15，由用户发起一次，内部包含 12 次模型调用。
- “第二个持久化 Run”或“复述 Run”：Conversation Record 16，内部只有 1 次模型调用，正常完成但输出了失败文案。
- “完整答案 Run”：Conversation Record 17，内部包含 11 次模型调用，最终生成完整长答案。用户将其感知为第二次输出。
- “第 N 轮模型调用”：同一个 Run 内部 Agent 与模型的第 N 次交互，不代表用户又发送了一次问题。

因此，性能表中的第 1 轮到第 11 轮都属于第一次失败请求；第二次请求不在该表中。

## 已确认的线上事实

### 第一次请求

- Conversation Record：`15`
- Conversation ID：`cf52f334-c8ca-4529-82ca-a31969578ee6`
- Generation ID：`6608dd90-a0ea-44d5-8f05-f6c0fd18ba5d`
- 运行终态：`LIMITED`
- 原因码：`RUN_DEADLINE_EXCEEDED`
- 运行时间：`600036 ms`
- 模型调用：`12`
- Prompt Token：`281660`
- Completion Token：`21144`
- 累计 Token：`302804`
- 运行行为：多次搜索、读取论文结构，并连续调用 `read_paper_content`；在截止时间前没有形成可验证答案

这一次是真实的运行时截止时间超限。

#### 第一次请求的性能时间线

Research Events 完整记录了每轮模型调用耗时、Token 和两轮之间执行的工具：

| 完成的模型轮次 | 本轮前完成的工具 | Prompt Token | Completion Token | 模型耗时 | 累计 Token |
| --- | --- | ---: | ---: | ---: | ---: |
| 1 | 无 | 2,827 | 105 | 2.1 s | 2,932 |
| 2 | 搜索论文候选 x2 | 3,168 | 152 | 4.5 s | 6,252 |
| 3 | 获取论文结构、按身份查论文 | 7,828 | 223 | 4.8 s | 14,303 |
| 4 | 读取正文 x3 | 13,302 | 205 | 5.8 s | 27,810 |
| 5 | 读取正文 x3 | 19,643 | 310 | 10.5 s | 47,763 |
| 6 | 读取正文 x5 | 27,505 | 209 | 2.8 s | 75,477 |
| 7 | 读取正文 x3 | 30,807 | 492 | 19.5 s | 106,776 |
| 8 | 读取正文 x8 | 38,404 | 3,279 | 64.6 s | 148,459 |
| 9 | 无工具 | 41,797 | 3,165 | 71.2 s | 193,421 |
| 10 | 无工具 | 45,035 | 6,013 | 174.2 s | 244,469 |
| 11 | 无工具 | 51,344 | 6,991 | 85.1 s | 302,804 |

随后第 12 次模型调用开始，但没有在 600 秒截止前完成。11 次已完成模型调用共占用约 `445 s`，约为整个 Run 的 `74%`；最长单次模型调用为 `174.2 s`。

这份记录同时显示两种性能现象：Run 进行了大量迭代，Prompt 从 2,827 增长到 51,344 Token；上下文增大后，后四次已完成模型调用的延迟显著升高。下一步需要区分“为什么 Agent 继续迭代”和“为什么大上下文模型调用变慢”这两个因果层次。

#### 第一次请求真正卡在哪个阶段

进一步按事件顺序还原后，可以修正“Agent 一直读取论文直到超时”这一不准确描述：

- 第 1-7 次模型调用负责发现论文、读取结构和读取正文。
- 第 7 次模型调用之后完成最后 8 个 `read_paper_content`，此后没有再执行任何可见业务工具。
- 第 8-11 次模型调用分别输出 3,279、3,165、6,013 和 6,991 Completion Token，但都没有结束 Run。
- 第 12 次模型调用开始后，Run 在 600 秒 Deadline 到达时被中断。

这说明 Agent 大约在前 7 次模型调用后已经从“收集证据”进入“形成最终答案”阶段。真正消耗剩余时间的不是 Qdrant 召回或 MySQL 正文读取，而是最终答案没有被 Runtime 接受，Runner 持续让模型修正。

当前 Runtime 中，一个模型响应在没有继续执行业务工具时仍会进入下一轮，只有两种路径：

1. `submit_research_answer` 被确定性答案校验拒绝；
2. 模型返回的 Function Call 参数损坏或截断，被适配层转换为内部修复调用 `_continue_research_turn`。

现有 `research_events_json` 只记录业务工具和模型调用，不记录 `answer.validation`，也不记录内部修复调用。线上 `EVAL_DUMP_DIR` 为空，原始模型响应和校验结果没有落盘。因此可以确认第 8-11 次调用之间发生了“终止尝试未被接受”，但无法从现存数据严格区分每一次到底是引用校验失败、独占工具校验失败，还是 Function Call JSON 修复。这里不做猜测。

#### 为什么失败原因没有保存

系统目前有两套不同的观测出口，但生产环境只实际保留了其中一部分：

1. `Research Events`：通过 Progress Listener 进入 Java 并持久化到 `research_events_json`。当前只覆盖模型调用和普通业务工具，线上确实保留。
2. `EvalRecorder`：会记录 `answer.validation`、模型请求/响应和内部工具详情，但只有配置 `EVAL_DUMP_DIR` 才落盘。线上该配置为空，所以本事件没有对应文件。

具体缺口位于终止路径：`_invoke_final` 把校验结果写给 `EvalRecorder`，却没有调用 `context.emit_progress`；`_repair_function_call` 也没有发出持久化事件。另外，Deadline 异常被 `LiveResearchChatHarness` 收口后，`_limited_run` 会构造空的 `react_trace`，无法从最终 Run 反推此前的终止尝试。

只增加两个摘要事件不足以调查 Agent 行为。生产环境需要保存完整的 Agent Action Trace，使一次 Run 可以按顺序重放：模型看到了什么 JSON、模型返回了什么 JSON、返回中有哪些 Tool Call、工具收到什么参数、工具返回什么结果、答案校验为什么接受或拒绝。

代码中已经有基本符合该目标的 `EvalRecorder`，不应再实现一套重复 Recorder。它当前能够记录：

```text
run.started
model.request / model.response / model.error
tool.started / tool.completed / tool.error
answer.validation
run result
```

其中 `model.request` 和 `model.response` 保存 OpenAI-compatible HTTP JSON；`tool.completed` 同时保存内部结果、模型可见结果和授权状态前后快照；`answer.validation` 保存提交草稿和具体校验错误。这正是本事件缺少的原始证据。

需要把它从“可选离线 Eval Dump”提升为生产 Agent Trace，并补齐适配器改写事件，例如记录原始 Function Call 因参数损坏而被转换成 `_continue_research_turn`。Trace 与面向前端的 `Research Events` 分工如下：

| 记录 | 用途 | 数据粒度 | 保存位置 |
| --- | --- | --- | --- |
| Research Events | 前端进度和轻量运行状态 | 摘要 | MySQL Conversation |
| Agent Action Trace | 故障调查、性能分析、Agent 行为重放 | 完整模型/工具 JSON | 服务器私有日志目录 |

完整 JSON 包含用户问题、历史消息、System Prompt、论文正文和模型答案，属于敏感运行数据。现有 Recorder 已删除 Authorization、Cookie、API Key 等 Header，并使用目录 `0700`、文件 `0600`；生产部署还必须保证 Trace 目录不在 Nginx、MinIO 或项目静态目录下。

为了避免占满磁盘，Trace 必须滚动保留，而不是永久保存。当前服务器磁盘约 `908 GiB`，剩余约 `550 GiB`，仍然需要同时设置：

1. 保留时间：删除超过配置天数的已结束 Run；
2. 总量上限：超过配置字节数时，按完成时间删除最旧 Run，直到回到上限内；
3. 活跃保护：不删除仍在写入的 Run；进程崩溃留下的未完成 Run 在安全窗口后再清理；
4. 清理失败只报警，不影响 Agent 回答。

具体保留天数和容量应作为部署配置，而不是写死在业务逻辑中。启用后，同类故障的调查入口应是 `generation_id -> run_id -> events.jsonl`，不再依赖从聚合 Research Events 猜测模型行为。

#### Agent Action Trace 实现状态

代码已经完成以下改造，并于 `2026-08-10 20:59 CST` 部署到线上：

- Live Harness 优先读取 `AGENT_TRACE_DIR`，每个 Run 保存 `events.jsonl` 和 `result.json`；
- `run.started` 保存上游 `request_id`，Harness 响应返回 `run_id`，Java Diagnostics 保存 `agentTraceRunId`；
- 原有模型请求/响应、工具输入/输出、授权状态和答案校验继续完整保存；
- 新增 `model.output_transformed`，记录纯文本转最终答案工具、损坏 Function Call 转内部修复工具的前后 JSON；
- 新增按保留天数、总字节数和未完成 Run 安全窗口清理的滚动策略；
- Trace 根目录、Run 目录和文件继续使用私有权限；
- 运维文档增加通过 Generation ID 查找完整 Agent Trace 的命令。

本地聚焦验证已通过：Python Recorder/Model/Service 共 9 个测试，Java `ResearchHarnessResultMapperTest` 通过。没有执行全量测试。线上 Trace 目录为 `/var/log/paperloom/agent-traces`，权限 `0700`，保留 7 天且总量上限为 10 GiB。

#### 开启 Trace 后的受控复现

使用相同问题“你详细讲解seedream4.0这个工作”进行一次受控复现：

- Conversation ID：`d6414216-3ec7-44a4-a908-3ab05a986355`
- Generation ID：`ea04d1ae-1ee5-4c6e-aedb-cfc81dcb3897`
- Agent Trace Run ID：`run_3d04d41abb1e41e993272f4c43763d88`
- Conversation Record：`18`
- 运行终态：`COMPLETED`
- 端到端耗时：`267727 ms`
- 模型调用：`18`
- Prompt Token：`604242`
- Completion Token：`50047`
- 累计 Token：`654289`
- 业务工具：搜索论文 1 次、读取结构 1 次、读取正文 9 次
- 完整 Trace：`events.jsonl` 约 3.06 MB，`result.json` 约 0.91 MB

第 12-18 次模型调用都在提交完整最终答案，Trace 中的 `answer.validation` 给出了确定原因：

| 模型调用 | 答案字符 | 引用标记 | 未引用 Material Block | 结果 |
| ---: | ---: | ---: | ---: | --- |
| 12 | 6,138 | 0 | 108 | 拒绝 |
| 13 | 11,594 | 69 | 28 | 拒绝 |
| 14 | 11,902 | 74 | 14 | 拒绝 |
| 15 | 12,557 | 86 | 4 | 拒绝 |
| 16 | 12,712 | 89 | 3 | 拒绝 |
| 17 | 12,712 | 89 | 3 | 拒绝 |
| 18 | 12,505 | 88 | 0 | 接受 |

最后两次拒绝都只剩三个块：

```text
block_45 paragraph: **单图编辑结论**：
block_49 paragraph: **多图编辑结论**：
block_55 paragraph: **主要观察**：
```

这些行只是后续引用列表的视觉分组标签，本身不包含论文事实。但 `answer_blocks` 只把 Markdown Heading 和 Table Header 视为可不引用的结构块；独立的粗体标签会被解析成普通 Paragraph，继而被当作必须引用的 Material Block。

第 18 次模型调用没有获得新证据。它把标签和后面的首个事实、引用合并到同一段，例如把独立的“`**单图编辑结论**：`”改为“`**单图编辑结论**：GPT-Image-1 ... [[source_quote_...]]`”，校验随即通过。

#### 已确认的根因链

本次复现确认了当前代码的完整因果链：

1. MiniMax 首次生成长答案时没有添加引用，确定性 Validator 正确拒绝了 108 个无引用事实块。
2. 后续每轮模型都会重新生成完整答案并增加引用，而不是只修复缺失位置；旧草稿和校验结果继续进入下一轮上下文。
3. 校验反馈只返回 `block_N`，没有返回对应文本。模型需要自己重新计算 Markdown Block 编号，导致最后三个位置连续两轮没有修正。
4. Block Parser 又把三个非事实性的粗体分组标签识别成 Material Paragraph，制造了最后的误报。
5. 七份长答案使 Prompt 累计到 60.42 万 Token，最终答案校验阶段成为主要延迟和成本来源。

因此，Deadline 不是根因；它只是未收敛时的最后保险。系统性问题是“答案校验反馈不可定位 + Markdown 结构块分类不完整 + 每次失败都让模型重写整份长答案”的组合。

Record 15 的历史原始模型 JSON 已经丢失，不能断言它每一次校验错误与本次完全相同。但本次在相同代码、模型、论文和问题上复现了相同的“正文读取结束后连续多轮无业务工具、Prompt 增长、最终答案迟迟不结束”模式，并用完整 Trace 确认了当前系统的具体失败机制。

#### 本次排查经验

1. 先区分用户可见消息、Answer Slot、Revision、Generation Run 和单个 Run 内的模型调用，不能用前端显示的“轮数”推断后端执行次数。
2. 先确定 Run 的真实终态和时间分布，再追查终止条件。`RUN_DEADLINE_EXCEEDED` 只说明最终在哪里停止，不说明为什么没有及时完成。
3. 聚合事件只能回答“调用了几次、用了多久”；要解释 Agent 为什么继续执行，必须保留模型请求/响应、Tool Call、工具结果和答案校验结果的原始 Trace。
4. 对无法取得原始 Trace 的历史 Run，只记录能够证明的现象，不补写推测。部署 Trace 后，用相同问题、模型、论文和代码受控复现，再建立因果链。
5. 调查长尾性能时要按阶段拆开：本事件的检索和正文读取已经结束，主要耗时发生在最终答案反复提交和校验阶段，不能笼统归因于“RAG 慢”或“模型慢”。
6. 校验器不仅要判断对错，还要返回模型能直接定位的错误内容。只返回 `block_N` 会让修复依赖模型重新计算 Parser 的内部编号，增加整篇重写和不收敛的概率。
7. 确定性规则也必须检查语义边界。Markdown 语法分类中的 `paragraph` 不等于业务语义中的“事实陈述”；把两者直接等同会制造误报。

#### 修复设计：可定位反馈与有界修正上下文

本次修复不增加用于判断“某一行是不是视觉标签”的正则、中文字符串特例、独立 Subagent 或局部草稿编辑协议。Parser、Validator、主 Agent 和 Runtime 的职责定义如下：

| 组件 | 职责 | 不负责 |
| --- | --- | --- |
| Parser | 按 Markdown 语法把答案切成有序 Block，保留 Block 类型和原文 | 判断一段文字在业务语义上是不是事实 |
| Validator | 校验引用格式、引用 ID、引用存在性，并返回可定位的违规 Block | 猜测模型写作意图、改写答案 |
| 主 Agent | 研究、生成答案，并根据可定位反馈修正答案 | 决定最终答案是否通过 |
| Runtime | 保留必要研究上下文，删除已被后续版本替代的失败提交，再调用同一个主 Agent | 保存和编辑独立草稿版本 |

##### 1. 明确答案格式契约

结构标签必须使用 Markdown Heading；`paragraph`、`list_item` 和 `table_row` 等内容块仍按现有规则要求同块引用。例如：

```markdown
### 单图编辑结论

- GPT-Image-1 在该设置下表现更好。[[source_quote_...]]
```

独立粗体行在 Markdown 语法上仍是 `paragraph`。Parser 不应把所有粗体行自动归类为结构块，否则 `**该方法准确率提高 20%**` 这样的事实陈述也会绕过引用校验。系统 Prompt 应明确要求模型使用 Heading 表达结构标签，但正确性不能只依赖 Prompt。

##### 2. Validator 返回可直接定位的违规信息

当前错误只返回 `block_45`。修复后沿用现有字符串错误接口，但每个违规项同时包含 Block ID、类型和原文片段，例如：

```text
paper-content answer blocks require citations: block_45 paragraph: **单图编辑结论**：
```

当前模型只需要可读反馈即可修正，不为尚不存在的调用方增加结构化错误类型。

这里的“Block 级精确”仅表示能够定位哪个 Markdown Block 缺少引用，不表示 Validator 已经判断该段是否为事实，也不表示某个 Source Quote 在语义上必然支持该段。在线确定性 Validator 继续负责引用存在性；引用与陈述之间的语义蕴含仍属于独立 Eval 问题。

##### 3. 修正上下文只保留最新失败提交

`submit_research_answer` 继续接收完整答案，不增加第二套 Patch Tool。当前 Agents SDK 会把本 Run 已累积的模型输出、Tool Call 和工具结果全部放进下一次模型输入，因此连续失败时形成：

```text
C_n = C_research + D_1 + E_1 + D_2 + E_2 + ... + D_n + E_n
```

其中 `D_n` 是第 `n` 份完整草稿，`E_n` 是对应校验结果。修复后使用 SDK 已提供的 `call_model_input_filter`，完整保留研究工具上下文，但在每次模型调用前只保留最新一组被拒绝的 `submit_research_answer` Function Call 及其配对输出：

```text
C_n = C_research + latest(D_n, E_n)
```

过滤时必须成对保留或删除 Function Call 与 Function Call Output，不能制造 Provider 无法接受的孤立工具消息。该逻辑属于 Runner 输入管理，不修改 MiniMax/OpenAI Provider 适配层，也不启动 Subagent。

该方案没有禁止模型再次提交完整答案；它通过可定位反馈减少重复修正概率，并保证即使发生多次修正，历史失败草稿也不会在线性累积到 Prompt 中。

##### 4. 局部 Patch 协议暂不实现

局部 Patch 需要额外引入 `pending_draft`、`draft_version`、Markdown Block 原文位置、替换后的重新编号、过期修改校验和两套提交协议。当前 Trace 尚不能证明在获得可定位反馈、且旧草稿不再累积后，模型仍会持续多次重写全文，因此这些复杂度没有现时依据。

第一版修复完成后，使用相同问题、模型、论文和代码做一次受控复现。如果 Trace 仍显示多个连续的完整答案提交，说明 Completion Token 和生成延迟仍然是独立瓶颈；届时再评估只提交 Block Replacement 的 Patch 协议。一次完整修正后通过不构成引入 Patch 协议的理由。

该设计不修改 Deadline、账户额度、Token 预检或单轮 Token 上限规则。

##### 5. 第一阶段实现状态

第一阶段已按上述范围实现：System Prompt 明确结构标签格式；Validator 错误包含 Block 类型和最多 200 字符的原文片段；`call_model_input_filter` 成对删除更早的失败提交和工具输出，仅保留最新失败提交。没有实现 Patch Tool 或草稿状态。

聚焦检查覆盖了可定位错误、结构块继续免引用、研究工具上下文不被过滤、仅保留最新失败提交，以及现有完整 Agent 提交链路，共 4 个测试通过。

##### 6. 第一阶段受控复现

`2026-08-11` 在本地产品 Corpus、相同 MiniMax 模型、同一篇 Seedream 4.0 论文和相同问题上完成受控复现：

- 问题：`你详细讲解seedream4.0这个工作`
- Request ID：`controlled-seedream-1786410999`
- Run ID：`run_431feda66e6b4150ad51f3724d1d4e61`
- 终态：`COMPLETED`
- 端到端耗时：`182694 ms`
- 模型调用：15
- Prompt Token：374928
- Completion Token：24627
- 总 Token：399555
- 业务工具：搜索论文 2 次、读取结构 1 次、读取正文 12 次

最终提交与校验过程为：

| 模型调用 | 草稿字符 | 引用标记 | 校验结果 |
| ---: | ---: | ---: | --- |
| 10 | 7607 | 16 | 纯文本适配为最终提交后，因包含内部 `<think>` 被拒绝 |
| 11 | 7087 | 17 | 拒绝；反馈包含每个无引用 Block 的类型与原文 |
| 12 | 13073 | 91 | 拒绝；只剩 14 个可直接定位的无引用 Block |
| 15 | 13643 | 94 | 接受 |

模型在第 12 次调用收到精确反馈后，没有立即盲目重写全文，而是在第 13、14 次调用读取了两个新的正文位置，再于第 15 次调用提交通过校验的答案。说明反馈中的 Block 原文已经能够驱动针对性修正。

输入过滤也按设计生效：从第 11 次到第 15 次模型请求，每个请求中都只有一组历史 `submit_research_answer` Function Call，研究工具调用与结果仍然保留。后六次 Prompt Token 为：

```text
31897 -> 35630 -> 37960 -> 39774 -> 44797 -> 46601
```

Prompt 仍会因为最新长草稿和新增正文证据而增长，但不再包含所有历史失败草稿。与修复前单次受控复现相比，本次耗时从 `267727 ms` 降至 `182694 ms`，总 Token 从 `654289` 降至 `399555`，最终校验尝试从 7 次降至 4 次。两次都只是单样本运行，以上只能作为同条件描述性对照，不能作为稳定性能增益结论。

本次 Trace 不支持立即增加局部 Patch 协议：精确反馈后的剩余流程包含两次必要的 Evidence Read，而不是同一错误上的连续全文重写。不过它新确认了一个确定性 Parser 缺口：标准 Markdown 分隔线 `---` 被解析为普通 `paragraph`，在第 12 次提交中制造了 7 个无引用误报。该语法可以由 Parser 确定性识别，不需要语义猜测或字符串业务特例。

该 Parser 缺口已随后修复：标准 Markdown Thematic Break 被识别为 `thematic_break`，并与 Heading、Table Header 一样允许不带引用。3 个聚焦 Parser/Validator 检查通过；没有再次调用模型复现，因为新增规则是确定性语法分支，重复完整模型运行不会增加相应置信度。

##### 7. 线上验收

Commit `6fb894e` 推送并部署后，`2026-08-11` 在 `https://paperloom.me` 再次提交同一问题“你详细讲解seedream4.0这个工作”：

- Agent Trace Run ID：`run_1ce98a516a8641b78d7db3ecf739d0a6`
- 终态：`COMPLETED`
- 端到端耗时：`184078 ms`
- 模型调用：11
- Prompt Token：193252
- Completion Token：20135
- 总 Token：213387
- 最终答案校验：3 次，前 2 次拒绝，第 3 次接受

第一次校验反馈直接包含缺少引用的 Block 类型与原文；第二次只剩两个引导段和八个列表项。Agent 随后读取了论文 `3.3` 相关正文，再次提交后通过；没有在同一错误上连续盲目重写。

输入过滤也按设计生效：第 8-11 次模型请求中，每次最多只有一个历史 `submit_research_answer` Function Call，不再累积多份失败长草稿。

线上 Trace 同时记录到一次 `_continue_research_turn`：MiniMax 已经生成修正答案，但该答案先被适配为内部继续调用，因此多了一次模型请求。该现象未导致本 Run 失败，与本次已修复的“校验反馈不可定位、失败草稿累积”属于独立问题。

这次验收证明该线上故障链已能收敛，不证明所有问题都会在同样耗时和 Token 内完成。当前 Trace 仍不支持增加局部 Patch 协议。

#### 与成功 Run 的对照

Record 17 也表现出相同模式：

- 第 1-6 次模型调用完成搜索和正文读取；
- 第 7-11 次模型调用之间没有任何可见业务工具；
- 第 7-10 次都没有结束 Run，第 11 次才形成最终答案；
- Prompt 从第 7 次的 24,250 Token 增长到第 11 次的 40,381 Token。

因此，这不是失败 Run 独有的偶然现象。当前全链路存在一个系统性的“最终答案提交/校验收敛”性能问题：成功与失败的区别，是同一种修正循环最终有没有在 Deadline 前碰巧收敛。

每次未被接受的长答案及其工具结果会进入后续 Agent 上下文，解释了为什么没有继续读取正文时 Prompt 仍持续增长，也解释了后期模型调用变慢。Deadline 只是最后的终止机制，不是最前面的系统性根因。

### 第二次请求

- Conversation Record：`16`
- Conversation ID：与第一次相同
- Generation ID：`083d36f2-c737-46c5-b288-5536fe63d1e4`
- 运行终态：`COMPLETED`
- 运行时间：`2174 ms`
- 模型调用：`1`
- 累计 Token：`2896`
- 事件序列：`job_started -> model_call_started -> model_call_completed -> answer_completed -> job_completed`
- 最终答案：仍然是第一次的 execution-limit 文案

这一次没有触发执行上限。运行时将模型返回的同一句文案当作正常最终答案完成。

### 完整答案请求

- Conversation Record：`17`
- Conversation ID：与前两个 Run 相同
- 运行终态：`COMPLETED`
- 运行时间：`171604 ms`
- 模型调用：`11`
- Prompt Token：`230213`
- Completion Token：`20809`
- 累计 Token：`251022`
- 工具行为：搜索论文候选 2 次、读取论文结构 1 次、读取正文 6 次
- 最终答案长度：`4325` 字符

因此，“长答案只调用一次模型”不成立。一次模型调用的是 Record 16，它只输出了 128 字符的失败文案；用户看到的完整长答案来自 Record 17，调用了 11 次模型。

### 为什么前端只显示两轮对话

后端的三个 Conversation Record 不对应前端的三轮对话。前端历史按 `answer_slot_id` 展示，每个 Answer Slot 只返回当前 Revision：

| 前端 Answer Slot | 当前显示 | 被折叠的版本 |
| --- | --- | --- |
| `15` | Record 15，Revision 1，真实超时 | 无 |
| `16` | Record 17，Revision 2，完整长答案 | Record 16，Revision 1，复述失败文案 |

Record 17 是对 Record 16 的 `USER_UNSATISFIED` 重试，两者共用 `answer_slot_id=16`。创建 Record 17 后，Record 16 被标记为 `current_revision=false`，Record 17 被标记为 `current_revision=true`。

会话历史查询 `findCurrentByUserIdAndConversationIdOrderBySlotAsc` 和 `findConversationHistoryPage` 都明确过滤 `currentRevision = true`。因此主对话只显示两个 Answer Slot，而不会把 Record 16 和 Record 17 展开成两轮。Record 16 没有丢失，可通过当前答案的“版本历史”入口读取。

这暴露出一个交互与可观测性问题：用户看到的是“对话轮次/当前答案”，运行日志记录的是“执行 Run”。一次答案重试会新增 Run 和 Revision，但不会新增可见对话轮次。调查时如果不显式关联 `conversation_record_id -> answer_slot_id -> answer_revision`，就会误把三个 Run 当成三轮用户对话。

#### 两轮显示的直接原因

代码链路确认这不是前端偶然漏渲染，而是“重新生成”功能的既定替换语义：

1. 某个使用游客账户的客户端调用 `POST /chat/generation/{generationId}/retry`。
2. `ChatHandler.retryGeneration` 调用 `ConversationService.prepareUserRetry`，沿用原回答的 `answer_slot_id`，并把 Revision 加一。
3. `ConversationRetryContext.toClientPayload` 明确返回 `replaceMessage=true`。
4. 前端 `retryGeneration` 根据 `answer_slot_id` 找到原 Assistant Message，清空其内容，并在原位置显示新 Generation；不会追加一组新的 User/Assistant Message。
5. 新答案持久化时，`clearCurrentRevision` 把同一 Slot 的旧 Record 标为非当前版本。
6. 会话历史查询只返回 `current_revision=true`，刷新后仍只保留每个 Slot 的最新答案。

因此最终可见序列是：

```text
Slot 15 / Record 15 v1：失败文案
Slot 16 / Record 16 v1：失败文案 -> 触发重新生成后在原位置被替换
Slot 16 / Record 17 v2：完整答案
```

Record 17 不是普通的新一轮提问，而是 Record 16 的重新生成。代码中没有自动触发 `USER_UNSATISFIED` 重试的路径；它只能经由重试 API 创建。由于游客账户由多人共享，现有持久化信息只能证明“某个游客客户端触发了重试”，不能据此认定是当前观察者本人操作。

需要关注的不是“是否应该把重新生成算作第三轮”，而是系统目前没有在主界面和运行诊断中清楚表达三种不同计数：用户提问轮次、Answer Slot 数、实际 Generation Run 数。这会直接误导故障调查和性能归因。

### 用户界面观察

失败会话的 Research Process 最后只显示：

```text
Reasoning
→ Reasoning
```

用户界面没有展示 `run_limited` 的原因码，也没有显示此前已经完成的连续论文读取。因此，当前 Research Process 视图不足以让用户判断 Run 是卡在工具读取、答案生成还是运行时截止。

## 当前问题定义

本事件至少包含两个需要分别解释的问题：

1. 为什么第一次 Run 在已经读取大量证据后仍未收敛，直到 600 秒截止？
2. 为什么第一次的运行失败文案进入同一会话的后续上下文，并在第二次被模型作为正常答案复述？
3. 为什么完整答案 Run 仍消耗 25.10 万累计 Token 和 171.6 秒；成功与失败 Run 的关键决策差异是什么？

游客账户是共享账户，因此共享会话会扩大第二个问题的影响范围。但目前没有证据表明游客角色、游客权限或游客额度触发了第一次超限。

## 产品预期

“详细讲解 Seedream 4.0”不是预先固定为单篇问答或跨论文调研。Agent 可以根据研究过程决定是否读取其他论文，但目标论文必须保持主要研究对象；其他论文只能服务于解释、比较或验证，不能让 Run 无边界扩张。

## 调查边界

- 不把两次相同显示文案视为同一种运行失败。
- 不先增加 Token 预检或单轮 Token 上限；此前产品约束是只在账户没有剩余额度时拒绝问答。
- 不用“缩小问题后重试”替代根因调查。
- 在确认 Agent 为什么不收敛、失败文案如何进入上下文之前，不修改线上行为。

## 调研学习目标

本次调查同时作为一次可复述的线上故障分析练习，重点保留三类材料：

1. 错误原因：区分用户可见症状、运行时终态、直接触发条件和系统性根因。
2. 性能分析：用模型调用次数、单轮上下文增长、工具调用序列、累计 Token 和端到端耗时解释 Run 为什么失控。
3. 面试表达：最终形成“发现问题 -> 建立证据 -> 排除错误假设 -> 定位根因 -> 修复 -> 验证 -> 防止复发”的完整叙述。

## 实时调查记录

| 时间 | 操作 | 结果 |
| --- | --- | --- |
| 2026-08-10 18:10-18:16 CST | 查询游客用户、最近会话、Research Events 和 Harness 日志 | 确认 Record 15 是真实 Deadline，Record 16 是正常完成但复述失败文案 |
| 2026-08-10 18:16 CST | 建立事件记录 | 调查范围拆分为 Agent 收敛问题与失败上下文污染问题 |
| 2026-08-10 18:19 CST | 人工查看失败会话的 Research Process | 最后只显示 `Reasoning → Reasoning`，未暴露 Deadline 或连续读取信息 |
| 2026-08-10 18:26 CST | 明确该问题的产品预期 | Agent 可自主扩展调研，但必须以目标单篇论文为重点，扩展阅读需要服务于该目标 |
| 2026-08-10 18:27 CST | 从 Research Events 重建模型调用性能时间线 | 确认 11 次已完成模型调用占 445 秒，Prompt 增长约 18 倍，后期单次延迟最高 174.2 秒 |
| 2026-08-10 19:14 CST | 根据用户反馈重新核对后续线上记录 | 发现 Record 17 才是用户看到的完整长答案：11 次模型调用、171.6 秒、25.10 万累计 Token；修正此前“两次请求”的不完整表述 |
| 2026-08-10 19:24 CST | 核对三个 Record 的 Answer Slot、Revision 与历史查询 | 确认三个 Run 只形成两个可见 Answer Slot；Record 16 是 Slot 16 的旧 Revision，主对话只返回 Record 17 |
| 2026-08-10 19:32 CST | 追踪前端重新生成入口到持久化与历史查询的完整代码链路 | 确认 Record 17 来自显式重试 API；`replaceMessage=true`、复用 Answer Slot 和 `current_revision=true` 过滤共同造成“3 个 Run、2 轮显示” |
| 2026-08-10 20:28 CST | 对齐 Record 15/17 的模型调用与工具事件，并检查 Runtime 终止路径和线上 Eval 配置 | 确认两个 Run 都在正文读取结束后进入多轮不可见的答案终止尝试；现有线上记录缺少具体校验/修复原因，无法继续细分且不应猜测 |
| 2026-08-10 20:34 CST | 追踪 `answer.validation`、内部修复与线上持久化出口 | 确认校验细节只写可选 EvalRecorder，未进入生产 Research Events；决定复用现有事件链补两个无正文事件，不新增 Trace 子系统 |
| 2026-08-10 20:42 CST | 根据运维调查需求重新评估观测粒度，并检查现有 `EvalRecorder` | 修正“只补摘要事件”的方案：生产需要完整 Agent Action Trace；复用现有模型/工具 JSON Recorder，增加滚动保留和适配器改写记录 |
| 2026-08-10 20:53 CST | 产品化现有 Recorder，并增加滚动清理、Generation/Run 关联和适配器变换事件 | 本地实现完成；9 个聚焦 Python 测试和 1 个 Java 测试类通过，等待部署后用 Seedream 问题复现 |
| 2026-08-10 20:59 CST | 发布 Commit `578abab`，配置 7 天/10 GiB 私有 Trace 并重启 Harness、Backend | 两个服务健康；Trace 目录权限 `0700`，Generation 与 Run 可以互相关联 |
| 2026-08-10 21:04 CST | 用相同 Seedream 问题受控复现，并读取完整 Agent Action Trace | 复现成功；确认 7 次完整答案提交中前 6 次因无引用 Block 被拒，最后卡在三个被误判为 Material Paragraph 的粗体分组标签 |
| 2026-08-11 09:42-09:45 CST | 发布并部署 Commit `6fb894e` | 上线可定位校验反馈、有界失败草稿上下文和 Thematic Break 语法修复；Harness 与 Backend 分别于 09:45:09、09:45:10 进入 active |
| 2026-08-11 10:00-10:03 CST | 在线上使用相同 Seedream 问题验收 | Run `run_1ce98a516a8641b78d7db3ecf739d0a6` 在 184.1 秒后完成；3 次校验收敛，每个修正请求最多保留一份历史失败提交 |

## 后续观测

本事件关闭。保留现有滚动 Agent Trace，只在线上 Trace 再次证明“精确反馈后仍针对同一错误连续多次重写全文”时，才重新评估局部 Patch 协议。MiniMax 纯文本/内部继续调用的适配现象单独调查，不扩大本次修复范围。Deadline 和 Token 额度规则保持不变。
