# Harness 核心上手指南

这份文档只讲两件事：**OpenAI Agents SDK 在本项目里怎么用**，以及 **PaperLoom Research
Harness 如何利用 SDK 组织一个可信的论文研究回合**。Agents SDK 是当前唯一运行时，代码中
不存在另一套手写工具循环或运行时选择开关。

读完后，你应该能够：

- 看懂 `Agent`、`Runner`、`FunctionTool`、Context、Session、Hooks 和 Model 各自负责什么；
- 沿着一次研究请求找到模型、工具、证据、最终答案和会话记忆的流转路径；
- 增加研究能力、工具或模型，而不破坏 Harness 的授权和引用边界。

如果还不熟悉 Agent、Runner 和 Function Tool，建议先读
[`OPENAI_AGENTS_SDK_GUIDE.md`](OPENAI_AGENTS_SDK_GUIDE.md)，再回到本文看项目结构。

`corpus/` 和 `transport/` 在本文只介绍契约。`evaluation/`、Golden Data、回归数据和测试不是
理解主流程的前置知识，可以暂时跳过。

## 先记住这张图

```text
CLI / HTTP 请求
      |
      v
LiveResearchChatHarness
  - 限定本轮论文范围
  - 把会话状态整理成 TurnExecutionInput
      |
      v
AgentsSdkHarnessRuntime
  - 创建本轮 ResearchRunContext
  - 创建 Agent、Model、Session、Tools、Hooks
  - 调用一个 Runner.run(...)
      |
      v
OpenAI Agents SDK 工具循环
  模型 -> 调工具 -> 工具结果回给模型 -> 继续
      |
      v
submit_research_answer
  - 确定性校验答案和 evidence_id
  - 校验失败：把错误返回模型，继续循环
  - 校验通过：成为 Runner 的 final_output
      |
      v
标准化 Run + 更新后的 ConversationState
```

这里最重要的设计是：**SDK 负责运行工具循环，模型负责选择研究路径，Harness 负责限制模型
能看什么、能读什么、能引用什么，以及什么答案可以结束本轮。**

## 推荐阅读顺序

只读下面七个文件，就能理解主干：

1. [`orchestration/agents/runtime.py`](orchestration/agents/runtime.py)：SDK 的组装和执行入口。
2. [`orchestration/agents/tools.py`](orchestration/agents/tools.py)：如何把项目工具接入 SDK，以及如何结束循环。
3. [`orchestration/agents/context.py`](orchestration/agents/context.py)：单次运行的可变状态。
4. [`orchestration/live_chat.py`](orchestration/live_chat.py)：单回合边界和异常收口。
5. [`orchestration/conversation.py`](orchestration/conversation.py)：跨回合记忆。
6. [`orchestration/research_contract.py`](orchestration/research_contract.py)：Agent 指令和最终答案契约。
7. [`orchestration/run_output.py`](orchestration/run_output.py)：引用渲染和标准 Run 构造。

然后按需要再看：

- [`orchestration/runtime.py`](orchestration/runtime.py)：上层依赖的最小 Runtime 契约。
- [`orchestration/memory.py`](orchestration/memory.py)：请求级 SDK Session 实现。
- [`orchestration/research_skills.py`](orchestration/research_skills.py)：可按需加载的研究方法指导。
- [`orchestration/agents/model.py`](orchestration/agents/model.py)：MiniMax Chat Completions 和
  GPT/Codex Responses Provider 如何适配成 SDK Model。
- [`corpus/tools.py`](corpus/tools.py)：语料工具契约和授权状态机。
- [`transport/service.py`](transport/service.py)：Java 与 Harness 的 HTTP 契约。

理解主干后，再读两份真实改造记录：

- [`Golden Data 与 Harness 演化`](../site/practice/evaluation/golden-data-harness-evolution.md)：
  Candidate 恢复后，已实现的 `submit_research_answer` 证据覆盖门及其验收边界。
- [`Reading Model 与检索实践复盘`](../research/golden-data/2026-07-13-reading-model-retrieval-practice.md)：
  从 `21/32 -> 16/32 -> 29/32` 理解如何区分数据、检索、读取、引用和真实模型行为。

## OpenAI Agents SDK 在这里怎么用

项目固定使用 `openai-agents==0.18.2`，版本来源是 `requirements.in`。核心组装可以简化成
下面这段：

```python
context = ResearchRunContext(turn)

agent = Agent[ResearchRunContext](
    name="PaperLoom Research Harness",
    instructions=research_agent_instructions(context.skills),
    model=model,
    model_settings=settings,
    tools=build_agent_tools(context),
    tool_use_behavior=tools_to_final_output,
    reset_tool_choice=False,
)

result = await Runner.run(
    agent,
    input_items,
    context=context,
    session=RequestBackedSession(turn.run_id, turn.conversation_messages),
    hooks=ResearchRunHooks(),
    max_turns=None,
    run_config=run_config,
)
```

### Agent：声明模型如何工作

`Agent` 是一组运行配置，不是持久进程。这里给它：

- `instructions`：研究规则、引用规则、结束规则，以及可用 Research Skill 目录；
- `model`：SDK 的 `Model` 实现，本项目默认是 MiniMax 适配器；
- `tools`：模型可以调用的函数工具；
- `model_settings`：强制使用工具、允许模型提出并行工具调用、限制输出 Token；
- `tool_use_behavior`：决定某次工具结果是否应成为最终输出。

每个请求都会创建一个新的 Agent。业务记忆不放在 Agent 对象里。

### Runner：执行连续工具循环

`Runner.run()` 接管标准循环：

```text
组装输入 -> 调模型 -> 执行 function calls -> 把结果交还模型 -> 再调模型
```

本项目设置 `max_turns=None`，不人为规定“检索几轮后必须结束”。真正的结束条件是
`submit_research_answer` 通过校验。

`tool_choice="required"` 和 `reset_tool_choice=False` 让每个模型步骤都继续走工具协议。若
MiniMax 返回普通文本，`MiniMaxAgentsModel` 会把它转换为内部 `_continue_research_turn` 工具
调用，提醒模型继续并通过最终提交工具结束，而不是让 SDK 提前把文本当成结果。

### Context：向工具注入本轮依赖和状态

`ResearchRunContext` 是 SDK 的运行 Context。工具通过 `tool_context.context` 得到它，因此不需要
全局变量，也不需要把内部状态暴露给模型。

它持有：

- 当前 `TurnExecutionInput`；
- 本轮 `ReadingCorpusTools` 及其授权状态；
- 使用过的 Research Skill；
- 工具轨迹、最终草稿；
- 模型调用次数、Token 和耗时；
- 取消检查和进度回调。

这是**单次运行状态**。一次 `run_turn()` 结束后，不应把它当作会话数据库继续复用。

### FunctionTool：把业务函数交给模型调用

`build_agent_tools()` 把项目的 JSON Schema 转成 SDK `FunctionTool`。每个工具主要由三部分组成：

```python
FunctionTool(
    name=name,
    description=description,
    params_json_schema=parameters,
    on_invoke_tool=invoke,
)
```

`invoke` 的职责是：

1. 解析模型给出的 JSON 参数；
2. 从 `tool_context.context` 取得本轮状态；
3. 调用 Research Skill、Corpus 工具或最终答案校验；
4. 返回 JSON 字符串，让 SDK 自动放回下一次模型输入。

模型设置允许提出多个工具调用，但 `ToolExecutionConfig(max_function_tool_concurrency=1)` 会串行
执行它们。原因是论文授权、位置公开和证据生成会修改同一个 `ResearchRunContext`，串行执行
可以保证状态顺序确定。

### tool_use_behavior：定义什么算最终输出

普通工具完成后，SDK 应继续调用模型。只有 `submit_research_answer` 返回
`{"accepted": true, ...}` 时，`tools_to_final_output()` 才返回：

```python
ToolsToFinalOutputResult(is_final_output=True, final_output=draft)
```

因此，最终答案校验不是 Runner 结束后的补救步骤，而是**工具循环内部的结束门**。校验失败
时，错误会像普通工具结果一样回到模型，模型可以修正引用或答案结构后重新提交。

### Session：只负责给 SDK 组装输入历史

`RequestBackedSession` 实现了 SDK Session 所需的 `get_items()`、`add_items()`、`pop_item()` 和
`clear_session()`。它由请求里的用户/助手文本历史初始化，并且只活在本次运行中。

这里没有把 SDK Session 当作长期记忆库。真正的跨回合状态是 `ConversationState`，由调用方
持久化，再在下一次请求中传回。这样 Java、CLI 和 SDK 不会各自维护一份互相冲突的历史。

旧证据也不会作为完整工具轨迹回放，而是单独压缩成 evidence card 放入 system input。

### Hooks 和 Model：接入运行观测与供应商

`ResearchRunHooks` 在 `on_llm_start` 和 `on_llm_end` 中记录模型调用、用量，并把同一模型步骤
产生的工具调用登记为一组。最终提交必须独占一个模型步骤，正是靠这个分组信息校验。

`MiniMaxAgentsModel` 继承 `OpenAIChatCompletionsModel`，内部使用 `AsyncOpenAI` 连接
OpenAI-compatible API。它说明了接入兼容供应商的一般方法：

1. 创建带 `base_url`、`api_key` 和超时设置的 OpenAI Client；
2. 交给 SDK 的模型适配器；
3. 在 `ModelSettings` 中放供应商特有参数；
4. 保持工具调用结果符合 SDK 预期。

如果已有另一个 SDK `Model` 实现，也可以直接通过 `AgentsSdkHarnessRuntime(model=...)` 注入，
无需修改 Runner 或工具代码。

## Harness 的核心机制

### 一次请求如何执行

1. `LiveResearchChatHarness.run_turn()` 根据调用方授权范围裁剪 Dataset。
2. 它把问题、文本历史、旧证据、进度和取消函数整理为 `TurnExecutionInput`。
3. `AgentsSdkHarnessRuntime` 创建本轮独立的 Context、Model、Session、Tools 和 Hooks。
4. 一个 `Runner.run()` 执行模型与工具循环。
5. 每次工具调用都会经过 Harness 的确定性授权检查。
6. 模型用 `submit_research_answer` 提交用户可见答案。
7. Harness 校验 outcome、Markdown、引用格式、evidence_id 和跨论文 Evidence Coverage。
8. 校验失败会作为工具错误返回同一个 Runner；校验通过才形成 `final_output`。
9. `build_harness_run()` 生成统一 Run；`ConversationState.updated_from_run()` 只把已接受答案和
   已验证证据带入下一轮。

### 三种状态不要混用

| 状态 | 生命周期 | 作用 |
| --- | --- | --- |
| `ConversationState` | 跨回合 | 文本消息、已引用论文、已引用证据 |
| `RequestBackedSession` | 单次 SDK Run | 把请求历史与当前输入交给 Runner |
| `ResearchRunContext` | 单次 SDK Run | 工具授权、当前证据、轨迹、用量、取消和最终草稿 |

原则很简单：**ConversationState 是产品记忆，Session 是 SDK 输入适配器，Context 是本轮工作区。**

### Corpus 契约：逐级获得引用资格

不需要先理解语料如何加载和排序，只需记住工具之间的授权链：

```text
search_paper_candidates / find_papers_by_identity
  -> 公开并授权 paper_id

find_reading_locations
  -> 只接受已授权 paper_id，公开 location_ref

read_locations
  -> 只接受已公开 location_ref，生成可引用 evidence_id

submit_research_answer
  -> 只接受旧记忆或本轮真实生成的 evidence_id
```

论文卡片和位置预览只能用于导航。只有 `read_locations` 返回的正文片段可以支持论文内容
声明。这条规则由代码校验，不依赖模型自觉。

`find_reading_locations` 对多主题或多论文查询会主动扩大候选面：查询至少包含 6 个有效词，
或者一次比较多篇论文时，即使模型请求了更小的 `top_k`，也会返回至少 12 个候选；候选总
上限是 20。排序同时计算全文 BM25、局部 passage BM25、开头词项、章节标题和查询词覆盖，
多论文查询还会保留每篇论文的候选下限，避免比较任务只看到某一篇论文或第一个表面匹配。
单篇窄查询仍使用调用方指定的 `top_k`。

Reading Model 有两个不同检索面，不要把它们混成一种 chunk：

- `reading_elements` 是语义内容库存，一条 MinerU `content_list` item 对应一个 canonical element；
- `pages` 是物理 PDF 页表面，只在导出诊断确认来自 `middle.json` 物理 projection 时参与检索。

语义元素始终是主排名层。物理页使用独立 BM25 统计，最多占 3 个 grounding 候选，不会改变
语义元素的文档频率和原有排序。PAGE 候选只有在查询词能证明“同一语义内容出现在另一物理
页”时才进入结果，因此用于跨页续文和精确页码，不是找不到结果后猜首页或相邻页。Anchor
Audit 同样先检查指定页的 canonical element，只有该页没有对应语义元素时才使用真实 PAGE
surface。

### 最终答案契约

最终工具只接受：

- `outcome`：`answered`、`needs_clarification`、`partial` 或 `abstained`；
- `markdown`：用户最终看到的内容；
- `fields`：可选结构化字段。

论文内容引用写成 `[[evidence_id]]`。`answer_validation_error()` 会拒绝未知 evidence_id、错误
引用格式、非法 outcome，以及把最终提交和其他工具放在同一步的行为。`build_harness_run()` 再把
内部 evidence marker 渲染成最终引用，并生成标准化结果。

结构校验通过后，`orchestration/evidence_coverage.py` 还会检查当前内容研究中被明确点名的
论文是否经过 Candidate、Read 和同论文 Cited 三个阶段。只检索未读取、读取后未引用、跨论文
借证据，以及只用 HEADING 导航文本支撑内容声明，都会让最终工具返回可行动的错误并继续同一个
Runner 循环。该 Policy 不读取 Golden Case、Anchor 或 Expected Fact，也不影响只使用论文卡片
回答的元数据问题。

### Transport 契约

Java 是权限和持久化的来源；Python 是一次研究回合的执行器。服务层只做以下适配：

- 请求必须提供 `conversation_id`、`user_message` 和 `scope.paper_ids`；
- Java 传入历史消息和旧证据，Python 重建 `ConversationState`；
- Python 只加载 `scope.paper_ids` 明确授权的论文；
- `/v1/research/stream` 输出进度事件和最终结果，`/v1/research/turn` 返回同步结果；
- 响应包含标准化 Run 所需结果和下一轮应保存的会话状态。

用户身份认证、论文权限判断、长期存储和断线策略不属于 Agent 或 Runner。Python 服务只可选
校验内部 Bearer Token，不能扩大 Java 传入的论文授权范围。

## 如何扩展

### 修改 Agent 行为

修改 `research_agent_instructions()`。它定义模型的决策原则，但不要把确定性安全规则只写在
提示词里；论文范围、工具授权和最终引用仍应由代码校验。

### 增加 Research Skill

在 `orchestration/research_skills.py` 的 `_SKILLS` 中增加一项。Registry 会自动：

- 把 Skill 简介加入 Agent 指令；
- 把 `skill_id` 加入 `get_research_skill` 的枚举；
- 在模型需要时返回详细步骤。

Research Skill 是给模型的按需方法指导，不是新的 Runner，也不是固定流程阶段。

### 增加 Corpus 工具

1. 在 `ReadingCorpusTools.definitions()` 增加工具 Schema。
2. 在 `ReadingCorpusTools.call()` 增加分发。
3. 实现实际方法，并明确它读取或修改哪些授权状态。

`build_agent_tools()` 会通过 `*context.corpus.definitions()` 自动把它注册成 SDK FunctionTool。
只有工具需要特殊进度展示、特殊最终行为或非 Corpus 依赖时，才需要修改
`orchestration/agents/tools.py`。

### 修改最终答案结构

这三个函数必须一起考虑：

- `final_answer_tool_definition()`：模型看到的提交 Schema；
- `answer_validation_error()`：确定性接受条件；
- `build_harness_run()`：内部草稿如何变成产品结果。

答案契约位于 `orchestration/research_contract.py`，结果构造位于
`orchestration/run_output.py`。

### 更换模型或供应商

优先实现或复用一个 SDK `Model`，再注入 `AgentsSdkHarnessRuntime`。如果供应商兼容 OpenAI
Chat Completions，可参考 `MiniMaxAgentsModel`；供应商差异应留在 Model 适配层，不要渗入
Corpus 工具和会话状态。

### 修改跨回合记忆

修改 `ConversationState` 及 `updated_from_run()`，并同步检查 `ResearchMemory` 和
`RequestBackedSession`。不要直接把完整工具轨迹塞回 SDK Session，否则上下文会膨胀，也会把
上一轮临时授权和失败步骤误当作当前事实。

## 本地运行主流程

在仓库根目录 `/path/to/paperloom`：

```bash
python3 -m venv .venv-harness
.venv-harness/bin/python -m pip install -r harness_py/requirements.lock

export MINIMAX_API_BASE_URL=https://api.minimaxi.com/v1
export MINIMAX_API_KEY=...
export MINIMAX_MODEL=MiniMax-M3

.venv-harness/bin/python -m harness_py chat-shell --provider-source env
```

这里 `--provider-source env` 只选择模型配置来源；论文 Corpus 仍从产品数据库加载。

启动内部服务：

```bash
.venv-harness/bin/python -m harness_py serve \
  --host 127.0.0.1 \
  --port 8091
```

## 最小心智模型

遇到代码问题时，用下面五句话定位：

1. `Agent` 定义模型、指令和工具。
2. `Runner` 执行模型与工具之间的循环。
3. `ResearchRunContext` 保存本轮工具状态。
4. `ConversationState` 保存跨回合产品记忆。
5. Harness 的价值不只是“让模型调用工具”，而是用授权链和最终提交工具把开放式 Agent
   行为收敛为可验证、可引用、可持久化的研究结果。
