# LLM Principles Recommendation Latency Incident

## 文档状态

- 事件状态：根因已确认，本地修复已通过，待线上验收
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
