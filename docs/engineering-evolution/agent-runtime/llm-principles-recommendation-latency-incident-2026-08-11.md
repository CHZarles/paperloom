# LLM Principles Recommendation Latency Incident

## 文档状态

- 事件状态：适配器子问题已线上验证；发现更主要的 Agent 研究深度策略问题，继续调查
- 首次记录：2026-08-11 11:11 CST
- 线上环境：`https://paperloom.me`
- 用户问题：“推荐和llm原理相关的”
- 用户可见现象：提交问题后长时间没有得到及时的前端反馈或最终答案

## 调查目标

1. 定位这次用户请求对应的 Generation 和 Agent Trace Run。
2. 把端到端耗时拆成排队、模型调用、检索/读取、答案校验和持久化阶段。
3. 通过 Trace 提出可证伪的根因假设，不用“模型慢”或“RAG 慢”代替证据。
4. 建立可重复的性能验证方法，实施最小根因修复，再用同一信号验收。

## 当前已知与未知

### 已知

- 问题发生在线上最近一轮对话。
- 从用户视角看，提交后的响应不及时，产品体验已经构成问题。

### 未知

- 对应的 Run ID、真实终态和端到端耗时。
- 前端是否持续收到了 Research Progress，还是界面在某个阶段停止更新。
- 时间主要消耗在排队、工具、模型还是答案校验。
- Agent 是否重复了同一行为，或者每次执行都在获取新信息。
- 问题是稳定可复现，还是一次模型行为长尾。

## 调查规则

- 事实、假设、结论分开记录。
- 先建立可重复的快速判定信号，再读代码和提修复。
- 不改 Deadline、Token 额度或用户可见文案来遮蔽性能根因。
- 不增加新的 Trace 子系统；优先复用已上线的 Agent Trace。

## 第一步：定位目标 Run

通过最近 Agent Trace 中 `run.started.payload.question` 精确匹配到目标请求：

- Generation ID：`22f5e146-00c4-43e3-974d-bd657ceb1b00`
- Conversation ID：`2ec9ac35-ce36-46ca-8564-9f5aec09eff3`
- Agent Trace Run ID：`run_5e9ea4f9b518469aa86d7b246fdd5576`
- 开始时间：2026-08-11 11:05:37 CST
- 完成时间：2026-08-11 11:06:05 CST
- 终态：`COMPLETED`
- 端到端执行耗时：`28818 ms`
- 模型调用：3 次，共 `28663 ms`
- 业务工具：`search_paper_candidates` 2 次，共约 `124 ms`
- Token：Prompt `12303`，Completion `1758`，总计 `14061`

服务端事件时间线：

| 相对时间 | 事件 | 本段耗时 |
| ---: | --- | ---: |
| 0.000 s | Run 开始 | - |
| 0.020 s | 第 1 次模型调用开始 | - |
| 7.150 s | 第 1 次模型调用完成 | 7.130 s |
| 7.152-7.276 s | 两次论文搜索完成 | 约 0.124 s |
| 7.278 s | 第 2 次模型调用开始 | - |
| 21.442 s | 第 2 次模型调用完成 | 14.164 s |
| 21.444 s | 第 3 次模型调用开始 | - |
| 28.814 s | 第 3 次模型调用完成 | 7.369 s |
| 28.822 s | 答案与 Run 完成 | - |

第 2 次模型响应被适配为最终答案提交，但 Validator 以 `remove internal reasoning from markdown` 拒绝；第 3 次模型调用后答案通过。因此，最后约 7.37 秒是第一份答案携带内部推理后的修正轮。

当前可以下两个事实结论：

1. 这不是排队、Qdrant 或 MySQL 慢；99% 以上的 Harness 执行时间在等待三次模型响应。
2. 前端可用的服务端 Progress 事件已持久化，但第 2 次模型调用期间有 14.16 秒没有新的阶段事件。这仍不能证明浏览器是否及时收到和渲染了已有事件。

### 当前判定信号

第一个快速、确定性信号是对已保存 Trace 计算：总耗时、模型耗时占比、最大 Progress 事件间隔、因校验拒绝增加的模型轮次。当前信号为红：总耗时 28.8 秒，最大阶段事件间隔 14.16 秒，并有 1 次可明确归因于答案带内部推理的追加模型调用。

## 第二步：区分答案延迟与反馈延迟

从后端日志、持久化 Research Events 和前端实现对齐得到：

1. WebSocket 服务端于 11:05:36.810 收到用户问题，Harness 于 11:05:37.057 发出 `job_started`，启动延迟约 247 ms。
2. 服务端在 Run 期间产生并持久化了 13 个 Research Progress 事件，包括模型调用开始/完成、搜索开始/完成和终态。
3. 前端代码会直接处理 WebSocket `research_progress`，最多等待 160 ms 批量刷新，然后渲染 `Thinking`、`Searching papers` 和 `Thinking · pass N`。
4. 目标 Generation 在提交后约 8.19 秒收到了一次前端状态轮询 `GET /api/v1/chat/generation/{id}`，请求 10 ms 完成。这说明当时至少有一个持有该 Generation ID 的页面仍在运行恢复监视，不支持“整个前端主线程卡死”的解释。
5. 前端不会展示未校验的答案草稿。Java 只在 Harness 返回已通过校验的 `finalAnswerMarkdown` 后，才把完整答案作为一个 WebSocket Chunk 发送。因此这一轮在约 28.8 秒之前不会出现任何答案正文，即使 Research Progress 正在更新。
6. Java 侧总耗时 `28894 ms`，其中 Python Harness `28856 ms`，Harness 结束后的引用映射、持久化和 WebSocket 回送没有形成可见的额外延迟。

因此，目前将用户体感拆成两个指标：

- **Time to Progress**：服务端约 0.25 秒开始产生 Progress；代码设计上前端能在收到后 160 ms 内渲染。但当前没有浏览器端接收时间的 Telemetry，所以不宣称这次每个事件都已成功渲染。
- **Time to Answer**：约 28.8 秒，是用户感知到“答案迟迟不来”的直接指标。它主要由模型调用构成，其中最后 7.37 秒是可明确避免的校验修正轮。

当前证据不支持“最终答案已在后端准备好，但前端延迟了很久才显示”。它支持的是：后端 Harness 花了 28.8 秒才产生可发布答案，之后立即发送。

## 第三步：可证伪假设

按当前证据排序，先写预测，再动代码：

### H1：纯文本适配将 MiniMax 的 `<think>` 一起放入最终答案

- 已有证据：第 2 次模型输出进入 `PLAIN_TEXT_RESPONSE_ADAPTED_TO_FINAL_ANSWER`，随后 Validator 返回 `remove internal reasoning from markdown`。
- 预测：用已保存的第 2 次模型输出重放适配逻辑，得到的 `markdown` 会包含 `<think>...</think>` 并稳定被现有 Validator 拒绝。
- 若成立：这是可直接修复的根因，解释本 Run 多出的第 3 次模型调用和 7.37 秒。

### H2：即使没有 H1，当前 Agent 路径仍需要两次串行模型调用

- 已有证据：第 1 次模型调用决定搜索，第 2 次模型调用根据搜索结果生成答案，共耗时 21.29 秒。
- 预测：只修复 H1 会去掉额外修正轮，但同类推荐仍会保留“决策搜索 -> 用结果回答”两次串行模型调用。
- 若成立：约 21 秒是当前模型和 Agent 架构的基础 Time to Answer，不能通过优化 Qdrant 或 MySQL 解决。

### H3：静态 Progress 可能放大“卡住”体感，但不是本次后端耗时的根因

- 已有证据：模型调用期间只有起止事件，第 2 次调用中间 14.16 秒没有新事件；最终答案在校验前不会流式展示。
- 预测：即使界面正确显示 `Thinking · pass 2`，用户仍会在这 14.16 秒内感知不到新进展；增加纯前端计时或动画只会改善反馈，不会降低 28.8 秒 Time to Answer。
- 若成立：它是可用性改进项，应与 H1 的服务端额外轮次分开处理。

### 已排除的解释

- 搜索过慢：两次搜索共约 124 ms。
- 最终答案持久化过慢：Harness 结束后的 Java 收尾和回送为毫秒级。
- 整个前端线程卡死：目标 Generation 在运行中仍发起了状态轮询。

## 第四步：重放 H1

在现有 `MiniMaxAgentsModel` 适配层测试边界中，构造与线上第 2 次模型输出同形的响应：

```text
<think>Internal reasoning.</think>

A direct model answer.
```

新增聚焦测试期望适配后的 `submit_research_answer.markdown` 只等于 `A direct model answer.`。在修复前执行：

```bash
.venv-harness/bin/python -m unittest \
  harness_py.tests.test_agents_model.AgentsModelTest.test_text_only_response_does_not_publish_think_block
```

稳定失败：

```text
expected: A direct model answer.
actual:   <think>Internal reasoning.</think>\n\nA direct model answer.
```

H1 被确认。根因链是：

```text
MiniMax 返回“<think> + 可见答案”的纯文本
-> Agents SDK 输出中没有 Function Call
-> 兼容适配器把整段文本包成 submit_research_answer.markdown
-> Validator 正确阻止 <think> 泄漏
-> Agent 必须追加一次模型调用
-> 本 Run 增加 7.37 秒
```

这是**模型适配层的正确性 Bug，同时放大了性能延迟**。Validator 没有做错；它阻止了内部推理进入用户答案。问题在于上游适配器没有先区分内部推理和可见文本。

### 修复方案

1. 在现有纯文本兼容分支中，构造 `submit_research_answer` 前删除完整的 `<think>...</think>` Block，仅保留可见文本。
2. 保留 Validator 现有的 `<think>` 拒绝规则作为最后防线，不把安全性交给一次字符串清理。
3. 只修改共享 Model 适配层和这一个聚焦测试；不改 Runtime、Validator、检索工具、Deadline 或前端。
4. 修复后重放聚焦测试。线上验收只重复一次相同问题，检查是否从 3 次模型调用降为 2 次，不做过度测试。

该修复预计去掉本次可避免的 7.37 秒，不承诺消除前两次模型调用的约 21.29 秒基础耗时。若后续样本证明两轮调用仍达不到产品目标，再单独评估模型供应商/模型选型；不为“推荐”先增加一条旁路业务链路。

### 本地实现与验证

本地已在共享 Model 适配层的纯文本分支中，在构造 `submit_research_answer` 前删除完整 `<think>...</think>` Block。该改动不修改真正的 Function Call，也不改变 Validator。

执行新增回归测试与原有纯文本适配测试：

```bash
.venv-harness/bin/python -m unittest \
  harness_py.tests.test_agents_model.AgentsModelTest.test_text_only_response_does_not_publish_think_block \
  harness_py.tests.test_agents_model.AgentsModelTest.test_text_only_response_becomes_a_validated_final_submission
```

结果：`Ran 2 tests ... OK`。这完成了同一重放信号的 Red -> Green；未执行全量测试。

## 第五步：线上验收暴露的下一层问题

Commit `60683b0` 部署后，用户再次提交“推荐和llm原理相关的”：

- Generation ID：`c5ec6cfe-e8fd-4569-90c7-1650494eebbf`
- Agent Trace Run ID：`run_d46a4e4d97cb400089ebfd11ea1a43e5`
- 终态：`COMPLETED`
- 端到端耗时：`122729 ms`
- 模型调用：10
- 模型耗时：`121648 ms`
- 总 Token：`172744`

这次不能与原 Run 直接做 3 -> 2 次调用对照。原 Run 是“推荐”后的第二轮补充，历史中已有一次澄清；新 Run 是空历史的新会话。两者 Corpus 相同，但 Conversation Context 不同。

### 适配器修复已在线上生效

新 Run 的第 7 次 MiniMax 原始响应确实包含 `<think>`，且没有 Tool Call。适配后的 `submit_research_answer` 不再包含 `<think>`，Validator 没有再返回 `remove internal reasoning from markdown`。

因此 Commit `60683b0` 解决了它针对的适配器 Bug。但新 Run 的总延迟反而更长，说明该 Bug 只是原 Run 中可避免的一个追加轮次，不是所有推荐延迟的唯一根因。

### 新 Run 为什么扩展到 10 次模型调用

事件顺序为：

```text
调用 context_specific_brainstorming Research Skill
-> 并行搜索两组候选
-> 读取论文结构
-> 分 4 次读取 13 篇论文摘要
-> 第 7 次模型调用形成首份答案
-> 3 次答案提交，前 2 次因无引用 Block 被拒绝
-> 第 10 次模型调用通过
```

关键不是“模型随机多读了几篇”。`context_specific_brainstorming` 的现有指令明确要求：

```text
Recommend or generate options for a concrete user context.
Inspect a bounded candidate set.
Read evidence for why each selected item fits.
Return a bounded shortlist with a brief reason for each choice.
```

Agent 在第 2 次模型响应中明确表示，为了提供“grounded shortlist rather than guessing from titles”，需要读取基础论文的摘要。它后续的行为是在执行该 Skill 的证据要求。

因此新 Run 的根因是**研究深度策略对“广泛主题推荐”和“具体场景适配性推荐”没有划清边界**：

- “推荐一些 LLM 原理相关论文”是广泛主题发现，用论文卡片元数据返回有界列表即可。
- “我要复现长上下文 Agent，推荐三篇并解释为什么适合”才是具体场景适配性推荐，需要读取证据支持每个理由。

现有 Skill Catalog 中的 `Recommend or generate options` 足以让模型把第一类请求也路由到深研究 Skill，造成系统性过度研究。

### 对“只读标题”表述的修正

调查中一度将广泛主题发现简化为“搜索论文标题后就可以回答”。该表述不准确：只看标题不能充分判断一篇论文是否真正讨论 LLM 原理。

生产 `search_paper_candidates` 也不是只搜标题。它将 Query Token 与以下字段匹配和排序：

- 论文标题，权重 3；
- 论文摘要，权重 1；
- 作者、Venue、年份、DOI、arXiv ID 和文件名等元数据，权重 1。

因此正确分层是：

1. **候选召回**：用标题 + 摘要 + 元数据搜索生成相关候选，用于定位可能需要阅读的论文。候选预览不是可引用的研究结论。
2. **证据阅读**：根据问题覆盖度判断哪些候选会增加新证据，深读必要候选，为最终推荐提供可引用依据。

新 Run 读取 13 篇不能单凭数量定性为错误。已确定的性能事实是：研究被拆成 10 次串行模型交互，包含 4 次证据读取决策和 3 次答案提交。下一步应该判断这些串行往返中哪些是必要研究，哪些可在不牺牲深度的前提下批量执行或避免。

## 设计收敛记录

调查中先后否定了两个看似可以降低延迟、但不符合产品定位的方案：

1. **“用户没要理由就只返回论文卡片”不成立**：这会把科研推荐降级成普通检索，与产品的 Deep Research 价值冲突。推荐本身就是需要论文内容证据支持的判断。
2. **“未指定时最多 5 篇”不成立**：`5` 是人工常数，不表示研究是否充分。论文数量不应成为研究深度的控制器。

当前确认的原则是：

```text
候选召回
-> 判断每篇候选是否为回答增加新的相关证据
-> 深读所有必要的候选
-> 当剩余候选不再增加新的答案维度时停止
-> 给出有引用的推荐
```

因此，读 13 篇本身不一定错。数量应由问题覆盖度和证据饱和度产生，而不是由固定上限决定。

对完整 Trace 的进一步检查修正了“Agent 未批量读取”的判断：

- 第 1 次模型响应已在同一批中提交 `get_research_skill` 和 2 次候选搜索；
- 第 3 次模型响应一次请求读取全部 13 个 Abstract Location；
- `max_model_visible_tool_chars=16000` 使工具第一次只返回 3 个 Item，并把 10 个 Location 标记为 Omitted；
- 后续三次读取分别得到 `4 + 4 + 2` 个 Item，是工具输出分页，不是 Agent 每读一篇就改一次研究计划。

更大的延迟出现在答案阶段：

- 第 7、9、10 次模型调用都在生成完整答案，合计约 `70.8 s`；
- 第一份答案有 11 个推荐/阅读顺序 Block 没有同 Block 引用；
- 第二份答案仍有 2 个概括 Block 没有引用；
- 两次修正之间，第 8 次调用还重复加载了已经使用的同一 Skill，用时约 `12.8 s`。

因此当前优先级为：**先让第一份答案按现有引用合同生成，避免重复生成全文；再评估证据输出分页是否值得优化。**暂无证据需要新建调度器、修改 Runtime 并发模型或限制论文数量。

### 第一优先级的候选修复

不放宽 Validator，不自动猜测引用，不引入局部 Patch 协议。只收窄现有推荐 Skill 和校验失败后的行为合同：

```text
首次答案：
每个推荐或阅读顺序项 = 独立 Markdown Block + 该 Block 的证据引用
同一推荐不在第二个无引用列表中重复

校验失败后：
一次修正 Validator 列出的所有 Block
除非错误明确表示证据不足，否则不重新加载 Skill、不重新检索、不重新读取
直接重新提交修正后的完整答案
```

这不减少阅读论文、证据或回答内容，只消除重复表达和无关工具调用。如果上线 Trace 仍证明精确反馈后多次生成全文，再重新评估 Patch 协议；当前不增加这个复杂度。

### 实现状态

本地已按上述边界收窄：

- `context_specific_brainstorming` 要求每个推荐/阅读顺序项与其引用位于同一 Markdown Block，不再生成重复的第二份推荐列表；
- Agent 全局合同要求校验失败后一次修正全部报错项，不重复加载已用 Skill，只在现有证据确实不足时再调用 Corpus Tool；
- 新增 1 个聚焦合同测试，并更新预期 Prompt Hash；2 个聚焦测试通过，未运行全量测试。

## 实时调查记录

| 时间 | 操作 | 结果 |
| --- | --- | --- |
| 2026-08-11 CST | 建立事件记录 | 只记录用户可见现象和待证实项，尚未判定根因 |
| 2026-08-11 CST | 补充用户视角 | 不先把问题定性为“后端慢”；调查同时覆盖服务端执行耗时和前端进度反馈时序 |
| 2026-08-11 11:12 CST | 用问题原文匹配最近 Agent Trace 和 MySQL Conversation | 定位 Run `run_5e9ea4f9b518469aa86d7b246fdd5576`，确认 Run 成功完成且已持久化 |
| 2026-08-11 11:14 CST | 将 Agent Trace 与持久化 Research Events 对齐 | 总耗时 28.8 秒，模型耗时 28.7 秒；工具耗时可忽略；最后 7.37 秒来自第一份最终答案被 Validator 拒绝后的修正轮 |
| 2026-08-11 11:20 CST | 对齐 WebSocket 服务日志、Generation 状态轮询和前端渲染代码 | 前端并未在答案准备好后额外延迟；可发布答案本身在 28.8 秒后才形成。当前无浏览器端 Telemetry 证明每个 Progress 都成功渲染 |
| 2026-08-11 11:22 CST | 写出并排序三个可证伪假设 | H1 解释可避免的 7.37 秒；H2 解释修复后仍存在的基础两轮模型延迟；H3 解释用户对静态等待的体感 |
| 2026-08-11 11:25 CST | 用线上响应形状在 Model 适配层新增一个聚焦重放测试 | 测试稳定失败；适配后的 Markdown 包含完整 `<think>` Block，确认 H1 |
| 2026-08-11 11:31 CST | 在纯文本适配分支中删除完整 `<think>` Block，重放新旧两个聚焦测试 | 2 个测试通过；Validator 安全防线保留，未修改其他链路 |
| 2026-08-11 11:33 CST | 推送并部署 Commit `60683b0`，重启线上 Harness | Harness 于 11:33:10 完成启动并进入 `ready`，等待使用同一问题验收 |
| 2026-08-11 11:44-11:46 CST | 在新会话提交同一问题 | `<think>` 清理已在线上生效；但 Agent 加载推荐 Skill、读取 13 篇摘要并进行 3 次答案提交，最终耗时 122.7 秒 |
| 2026-08-11 11:49 CST | 对齐 Skill 详细指令与模型决策文本 | 确认 Agent 正在执行“读取每个入选项的适配证据”要求；问题是广泛主题发现被错误归入了具体场景深研究 |
| 2026-08-11 11:54 CST | 根据用户质疑检查候选检索实现 | 修正“只读标题”的不准确表述；候选搜索实际同时使用标题、摘要和元数据。正确问题是应先压缩 Shortlist，而不是为 13 篇候选全部读取证据 |
| 2026-08-11 12:00 CST | 形式化研究深度规则 | 将推荐拆为广泛主题发现和具体场景适配性判断；前者停在论文卡片 Shortlist，后者必须先入选再读取证据 |
| 2026-08-11 12:19 CST | 用产品定位复核上述方案 | 撤回“广泛推荐不深读”和“默认最多 5 篇”；推荐始终需要证据，阅读数量由问题覆盖度和证据饱和度决定 |
| 2026-08-11 12:42 CST | 检查 Runtime 多 Tool Call 执行语义 | Runtime 已允许模型一次提交多个 Tool Call；工具为授权一致性按顺序执行，但中间不会增加模型调用。优先候选方案是让 Skill 批量提交同一依赖层的独立读取，不新增调度架构 |
| 2026-08-11 12:51 CST | 按模型轮次重读完整 Trace | 修正“Agent 未批量读取”判断：Agent 已一次请求 13 个 Abstract，之后的 3 次读取由 16000 字符的模型可见 Tool Payload 分页触发。更大的延迟是 3 次完整答案生成和 1 次重复 Skill 加载；优先收敛首次答案的引用合同 |
| 2026-08-11 12:54 CST | 形式化首份答案收敛规则 | 候选修复只收窄推荐 Skill 的输出形状和校验失败后的行为：每个推荐 Block 自带引用，不在第二列表重复；一次修正所有报错 Block，非证据不足不重新调用研究工具。不放宽 Validator，不增加 Patch 协议 |
| 2026-08-11 13:01 CST | 用一个聚焦合同测试实现第一优先级修复 | Recommendation Skill 和 Agent 校验后行为已收窄；新测试先失败后通过，Prompt Hash 合同同步更新，2 个聚焦测试通过 |
