# OpenAI Agents SDK 保姆级入门

这份文档面向第一次接触 OpenAI Agents SDK 的 Python 开发者。先用最小示例理解 SDK，再把
每个概念对应到 PaperLoom Research Harness 的真实代码。

本仓库固定使用 `openai-agents==0.18.2`。官方文档会持续更新；概念以官方文档为准，项目内
具体类名、参数和行为以 `requirements.in` 及当前源码为准。

## 1. Agent 到底是什么

普通模型调用可以理解成：

```text
输入消息 -> 模型 -> 输出文本
```

Agent Run 则是：

```text
输入消息 -> 模型判断下一步
              |
              +-> 直接回答
              |
              +-> 调用工具 -> 工具结果回给模型 -> 再判断下一步
              |
              +-> 转交另一个 Agent
```

OpenAI Agents SDK 的核心价值是替你维护这个循环。你的代码负责定义 Agent、工具和业务边界，
`Runner` 负责不断调用模型、执行工具并把工具结果送回模型，直到 Run 结束。

在代码里，最重要的两个类是：

- `Agent`：配置对象，描述“谁来做、遵守什么指令、能用什么工具”；
- `Runner`：执行器，真正运行“模型 -> 工具 -> 模型”的循环。

不要把 `Agent` 想成后台常驻线程。创建 `Agent(...)` 只是准备配置，调用 `Runner.run(...)` 才
真正开始执行。

## 2. 安装和第一个 Agent

独立学习 SDK 时，可以这样安装：

```bash
python3 -m venv .venv
.venv/bin/python -m pip install openai-agents
export OPENAI_API_KEY=sk-...
```

最小程序：

```python
import asyncio

from agents import Agent, Runner


agent = Agent(
    name="Python tutor",
    instructions="用简洁中文解释 Python 问题。",
    model="gpt-5.6",
)


async def main() -> None:
    result = await Runner.run(agent, "列表和元组有什么区别？")
    print(result.final_output)


if __name__ == "__main__":
    asyncio.run(main())
```

执行过程只有三步：

1. `Agent(...)` 保存名字、指令和模型设置；
2. `Runner.run(...)` 调用模型并运行 Agent 循环；
3. `result.final_output` 是循环结束时的最终结果。

## 3. 给 Agent 增加工具

模型本身只能生成内容。工具让模型能够查询数据库、调用内部服务或执行确定性业务逻辑。

初学时最简单的方式是 `@function_tool`：

```python
import asyncio

from agents import Agent, Runner, function_tool


@function_tool
def get_order_status(order_id: str) -> str:
    """查询订单状态。"""
    return f"订单 {order_id} 已发货"


agent = Agent(
    name="Order assistant",
    instructions="帮助用户查询订单；需要状态时调用工具，不要猜测。",
    tools=[get_order_status],
)


async def main() -> None:
    result = await Runner.run(agent, "帮我查订单 A-100")
    print(result.final_output)


asyncio.run(main())
```

SDK 会从函数签名和 Docstring 生成模型可见的工具 Schema。模型不会直接执行 Python，它只会
生成类似下面的 function call：

```json
{
  "name": "get_order_status",
  "arguments": {"order_id": "A-100"}
}
```

Runner 收到后才会：

1. 找到注册的 Python 工具；
2. 校验并传入参数；
3. 执行函数；
4. 把返回值作为工具结果交还模型；
5. 再让模型组织最终回答。

### Harness 为什么不用装饰器

PaperLoom 的 Corpus 工具 Schema 由请求级 Dataset 动态产生，而且所有工具需要统一接入授权、
进度和最终答案校验。因此项目直接构造 `FunctionTool`：

```python
FunctionTool(
    name=name,
    description=description,
    params_json_schema=parameters,
    on_invoke_tool=invoke,
)
```

两种方式本质相同：都是向 Agent 提供“名称 + 描述 + 参数 Schema + 执行函数”。简单固定工具
优先使用 `@function_tool`；需要动态 Schema 或统一分发时再手动构造 `FunctionTool`。

对应代码：`orchestration/agents/tools.py`。

## 4. Context：把业务依赖交给工具

很多工具需要当前用户、数据库连接、授权范围或请求级缓存。不要把这些信息做成全局变量，也
不要全部放进模型参数；应该通过 SDK Context 传递。

简化示例：

```python
from dataclasses import dataclass

from agents import Agent, RunContextWrapper, Runner, function_tool


@dataclass
class AppContext:
    user_id: str
    allowed_order_ids: set[str]


@function_tool
def get_order(
    wrapper: RunContextWrapper[AppContext],
    order_id: str,
) -> str:
    """读取当前用户有权查看的订单。"""
    if order_id not in wrapper.context.allowed_order_ids:
        return "无权访问该订单"
    return f"订单 {order_id} 已发货"


context = AppContext(user_id="u_123", allowed_order_ids={"A-100"})
result = await Runner.run(agent, "查询 A-100", context=context)
```

关键点：

- `context=` 传给 Runner；
- SDK 在工具执行时提供 `RunContextWrapper`；
- 工具从 `wrapper.context` 读取业务对象；
- Context 不会因为传给 Runner 就自动出现在模型提示词里。

PaperLoom 中的 `ResearchRunContext` 保存本轮 Corpus 授权、证据、工具轨迹、取消检查和 Token
统计。它只活一个 Run，不承担跨回合持久化。

对应代码：`orchestration/agents/context.py`。

## 5. Session：让下一轮记得上一轮

若第二个问题依赖第一个问题，Runner 必须再次拿到必要历史。SDK Session 是一种历史存取
接口，官方也提供内存或 SQLite 等实现。

概念示例：

```python
from agents import Agent, Runner, SQLiteSession


agent = Agent(name="Assistant", instructions="简洁回答。")
session = SQLiteSession("conversation_123")

first = await Runner.run(agent, "我的名字是 Charles", session=session)
second = await Runner.run(agent, "我叫什么？", session=session)

print(second.final_output)
```

不要在同一个对话里随意混用“手动回放完整历史”和“Session 自动加载历史”，否则同一条消息
可能被重复发送。

PaperLoom 已经由 Java/CLI 管理长期会话，因此没有再使用 SQLiteSession。项目实现了
`RequestBackedSession`：每次请求用调用方传入的文本历史创建一个短期 Session，Run 结束后，
只有经过接受的答案和证据进入 `ConversationState`。

对应代码：

- `orchestration/memory.py`：SDK Session 适配；
- `orchestration/conversation.py`：产品跨回合记忆。

## 6. Hooks：观察模型和工具生命周期

Hooks 是 Runner 在关键阶段调用的回调。适合做：

- 模型调用次数和耗时统计；
- 日志、Tracing 和调试关联；
- 记录一次模型响应产生了哪些工具调用。

不适合把核心授权规则只放进 Hook，因为 Hook 更偏向生命周期观察；确定性业务校验应该留在
真正的工具或产品边界中。

PaperLoom 的 `ResearchRunHooks` 使用：

```python
class ResearchRunHooks(RunHooks[ResearchRunContext]):
    async def on_llm_start(...):
        context.context.begin_model_call()

    async def on_llm_end(...):
        context.context.register_tool_group(tool_calls)
        context.context.complete_model_call(...)
```

对应代码：`orchestration/agents/runtime.py`。

## 7. Model：SDK 与模型供应商的边界

Agent 使用 SDK `Model`，而不是在业务工具里直接发送 HTTP 请求。这样 Runner 不需要知道模型
来自 OpenAI、兼容供应商还是测试替身。

PaperLoom 的 MiniMax 接口兼容 OpenAI Chat Completions，因此：

1. 用 `AsyncOpenAI(base_url=..., api_key=...)` 创建兼容客户端；
2. 交给 `OpenAIChatCompletionsModel`；
3. 在 `ModelSettings` 中加入 MiniMax 特有参数；
4. 将纯文本响应纠正回项目要求的工具提交协议。

对应代码：`orchestration/agents/model.py`。

供应商差异应尽量止步于 Model 层。Corpus 工具不应该知道 API Base URL，ConversationState
也不应该知道底层模型使用 Chat Completions 还是其他传输协议。

## 8. Runner 如何结束循环

SDK 的普通默认行为是：模型没有更多工具调用并给出最终输出时结束。

PaperLoom 更严格。论文研究答案必须先经过引用和证据校验，因此把最终回答也设计成工具：

```text
submit_research_answer
```

执行路径：

```text
模型提交 draft
    |
    v
_invoke_final 校验 outcome、markdown、evidence_id
    |
    +-- 失败 -> 工具返回错误 -> Runner 继续调用模型修正
    |
    +-- 通过 -> accepted=true
                     |
                     v
             tools_to_final_output
                     |
                     v
             result.final_output
```

核心代码：

```python
def tools_to_final_output(_run_context, results):
    for result in results:
        if result.tool.name != "submit_research_answer":
            continue
        payload = _json_map(result.output)
        if payload.get("accepted"):
            return ToolsToFinalOutputResult(
                is_final_output=True,
                final_output=payload["draft"],
            )
    return ToolsToFinalOutputResult(is_final_output=False, final_output=None)
```

这是 Harness 最值得复用的机制：**把“什么时候允许结束”变成确定性代码，而不是只在提示词
里请求模型自觉遵守。**

## 9. PaperLoom 一次完整运行

```text
LiveResearchChatHarness.run_turn
    |
    | 组装 TurnExecutionInput
    v
AgentsSdkHarnessRuntime.run_turn
    |
    | 创建 ResearchRunContext
    | 创建 Agent / Tools / Session / Hooks
    v
Runner.run
    |
    | 模型决定调用哪个工具
    v
FunctionTool.invoke
    |
    | Corpus 授权、读取证据，或校验最终答案
    v
tools_to_final_output
    |
    | 只有 accepted draft 才结束
    v
build_harness_run
    |
    | 生成产品状态、引用、证据账本和诊断
    v
ConversationState.updated_from_run
```

建议实际阅读顺序：

1. `orchestration/agents/runtime.py`
2. `orchestration/agents/tools.py`
3. `orchestration/agents/context.py`
4. `orchestration/memory.py`
5. `orchestration/agents/model.py`
6. `orchestration/live_chat.py`
7. `orchestration/conversation.py`

## 10. 常见误区

### Agent 等于模型

不是。模型负责推理和生成；Agent 还包含指令、工具和运行策略。

### Agent 创建后会自己运行

不会。必须调用 `Runner.run()` 或相应流式接口。

### 工具调用由模型直接执行

不是。模型只生成工具名称和参数；Runner 在你的进程内调用注册好的 Python 函数。

### Context 会自动进入提示词

不会。Context 是给代码和工具使用的。要让模型看到某个字段，必须由指令、输入或工具结果
显式提供。

### Session 就是业务数据库

不一定。Session 只是 SDK 的历史存取抽象。长期状态放哪里，仍由应用架构决定。

### 提示词足以保证权限安全

不够。提示词约束模型行为，确定性代码约束系统行为。权限、授权范围和最终答案接受条件必须
在工具或产品边界再次校验。

## 11. 下一步练习

按下面顺序学习，最容易形成完整理解：

1. 跑通一个没有工具的 Agent，打印 `result.final_output`。
2. 用 `@function_tool` 增加一个纯函数工具。
3. 用 Context 给工具传入用户 ID 和授权集合。
4. 用 Session 连续执行两个有上下文关系的问题。
5. 阅读 PaperLoom 的 `tools_to_final_output()`，理解为什么最终答案也是工具。
6. 在 `ResearchSkillRegistry` 增加一个 Skill，观察它如何自动进入 Agent 能力目录。

## 官方资料

- [Agents SDK 总览](https://developers.openai.com/api/docs/guides/agents)
- [Python/TypeScript Quickstart](https://developers.openai.com/api/docs/guides/agents/quickstart)
- [Agent 循环、Session 与连续对话](https://developers.openai.com/api/docs/guides/agents/running-agents)
- [Tools 指南](https://developers.openai.com/api/docs/guides/tools#usage-in-the-agents-sdk)
- [OpenAI Agents SDK Python 仓库](https://github.com/openai/openai-agents-python)

官方文档给出的选择原则很直接：如果希望自己维护每次模型调用和工具循环，可以使用
Responses API；如果希望 SDK 管理 Agent 循环、工具执行、Session、Tracing 和生命周期，则
使用 Agents SDK。PaperLoom 选择后者，并在 SDK 循环外增加产品会话边界，在循环内增加语料
授权和最终答案校验。
