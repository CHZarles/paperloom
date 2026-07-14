"""OpenAI Agents SDK 运行时。

第一次读这份文件时，可以把它当成整个 SDK 集成的“装配图”：

1. :class:`AgentsSdkHarnessRuntime` 把一次产品请求变成 SDK Run；
2. :class:`Agent` 描述模型、指令和工具；
3. :class:`Runner` 真正执行“模型 -> 工具 -> 模型”的循环；
4. :class:`ResearchRunContext` 保存这一次循环中的可变业务状态；
5. 最后把 SDK 的 ``final_output`` 转成产品统一的 Harness Run。

这里故意不把语料检索、会话持久化或答案校验都写进 Runner。Runner 负责循环，具体业务规则
分别留在 Context、Tools、ConversationState 和共享的最终答案函数中。
"""

from __future__ import annotations

import asyncio
import json
from time import perf_counter
from typing import Any

from agents import (
    Agent,
    Model,
    ModelResponse,
    ModelSettings,
    RunConfig,
    RunHooks,
    Runner,
    ToolExecutionConfig,
)

from ...core.models import JsonMap
from ...transport.provider_config import ProviderConfig
from ..memory import RequestBackedSession, request_session_input
from ..research_contract import research_agent_instructions
from ..run_output import build_harness_run
from ..runtime import HarnessRuntime, TurnExecutionInput, TurnExecutionResult
from .context import ResearchRunContext
from .model import bind_research_context, provider_agents_model
from .tools import build_agent_tools, tools_to_final_output


class ResearchRunHooks(RunHooks[ResearchRunContext]):
    """在每次模型调用前后接收 SDK 生命周期事件。

    Hooks 适合做观测和关联，不负责决定研究步骤。这里主要记录模型调用次数、耗时、Token，
    并记住“一次模型响应里同时产生了哪些工具调用”，供最终提交的独占性校验使用。
    """

    async def on_llm_start(
        self,
        context,
        agent,
        system_prompt: str | None,
        input_items: list[Any],
    ) -> None:
        # context 是 SDK 的 RunContextWrapper；真正的业务 Context 在 context.context。
        context.context.begin_model_call()

    async def on_llm_end(
        self,
        context,
        agent,
        response: ModelResponse,
    ) -> None:
        # SDK 将模型输出拆成不同 item。这里只关心 function_call，因为后面要把同批工具
        # 调用登记为一组，判断 submit_research_answer 是否独占最后一步。
        tool_calls = [
            (str(getattr(item, "call_id", "")), str(getattr(item, "name", "")))
            for item in response.output
            if getattr(item, "type", "") == "function_call"
            and getattr(item, "call_id", None)
            and getattr(item, "name", None)
        ]
        context.context.register_tool_group(tool_calls)

        # usage 属于当前这一次模型请求。ResearchRunContext 会把它累加成整个研究回合的用量。
        usage = response.usage
        context.context.complete_model_call(
            int(usage.input_tokens or 0),
            int(usage.output_tokens or 0),
            int(usage.total_tokens or 0),
        )


class AgentsSdkHarnessRuntime(HarnessRuntime):
    """默认 HarnessRuntime：使用 OpenAI Agents SDK 执行研究回合。

    构造时有两种模型来源：

    - 传 ``provider``：运行时创建项目默认的 MiniMax SDK Model；
    - 传 ``model``：调用方直接注入任意 SDK ``Model``，适合替换供应商或写隔离测试。

    Runtime 本身不保存会话状态。每次 ``run_turn`` 都会创建新的 Context、Agent 和 Session。
    """
    harness_id = "python_openai_agents_sdk_harness_v1"

    def __init__(
        self,
        provider: ProviderConfig | None = None,
        *,
        max_completion_tokens: int = 3000,
        model: Model | None = None,
        model_settings: ModelSettings | None = None,
    ) -> None:
        if model is None and provider is None:
            raise ValueError("provider or model is required")
        self.provider = provider
        self.injected_model = model
        self.max_completion_tokens = max_completion_tokens
        self.model_settings = model_settings

    def run_turn(self, turn: TurnExecutionInput) -> TurnExecutionResult:
        """同步执行一个产品回合，并返回统一的 Harness Run。

        产品层当前是同步接口，而 Agents SDK 的 Runner 是异步接口，所以这里用
        ``asyncio.run`` 建立事件循环。不要从已有事件循环中直接调用这个同步方法；如果未来
        服务层整体改为 async，应相应提供 async Runtime 边界，而不是嵌套事件循环。
        """

        # 单路径旧模式：每轮只有一个 Context、一个 Model、一个 SDK Runner。
        context = ResearchRunContext(turn)
        final = asyncio.run(self._run_agent(context))
        context.check_cancelled()
        run = build_harness_run(
            case_id=turn.case_id,
            final=final,
            prior_evidence=turn.research_memory.evidence_items_by_id,
            corpus=context.corpus,
            trace=context.trace,
            skills_used=context.skills_used,
            started_at=context.started_at,
            duration_ms=round((perf_counter() - context.started_monotonic) * 1000),
            diagnostics={
                "model_call_count": context.model_call_count,
                "prompt_tokens": context.prompt_tokens,
                "completion_tokens": context.completion_tokens,
                "total_tokens": context.total_tokens,
                "model_latency_ms": context.model_latency_ms,
            },
            harness_id=self.harness_id,
        )
        # build_harness_run 会生成稳定的内部 ID；在线请求使用请求开始时分配的真实 run_id。
        run["run_id"] = turn.run_id
        return TurnExecutionResult(run=run)

    async def _run_agent(self, context: ResearchRunContext) -> JsonMap:
        """装配 Agent 并让 SDK 跑到最终答案通过校验为止。"""

        turn = context.turn

        # 注入模型优先；否则根据 ProviderConfig 创建本轮专用的 MiniMax 适配器。
        model = self.injected_model or provider_agents_model(self.provider)  # type: ignore[arg-type]
        owns_model = self.injected_model is None

        # 这里得到的不是业务函数本身，而是 SDK 能识别的 FunctionTool 列表。
        tools = build_agent_tools(context)
        settings = self.model_settings
        if settings is None and hasattr(model, "research_settings"):
            settings = model.research_settings(self.max_completion_tokens)

        # 自定义 Model 没提供设置时使用保守默认值：每一步必须走工具协议，允许模型一次提出
        # 多个工具调用。实际工具执行仍会在下方 RunConfig 中串行化。
        settings = settings or ModelSettings(
            max_tokens=self.max_completion_tokens,
            tool_choice="required",
            parallel_tool_calls=True,
        )

        # Agent 是声明式配置，不是运行中的循环。真正开始工作的是后面的 Runner.run。
        agent = Agent[ResearchRunContext](
            name="PaperLoom Research Harness",
            instructions=research_agent_instructions(context.skills),
            model=model,
            model_settings=settings,
            tools=tools,
            # 普通工具完成后继续循环；只有通过校验的 submit_research_answer 才成为 final_output。
            tool_use_behavior=tools_to_final_output,
            # 保持 tool_choice=required，不让 SDK 在首轮之后自动放松成普通文本回答。
            reset_tool_choice=False,
        )

        # Session 只用请求提供的文本历史播种，并且只服务这一次 SDK Run。跨回合持久化由
        # ConversationState/Java 负责，避免出现两套互相冲突的会话记忆。
        session = RequestBackedSession(turn.run_id, turn.conversation_messages)
        input_items: list[JsonMap] = []
        if turn.research_memory.evidence_items_by_id:
            # 旧证据不伪装成历史工具调用，而是以精简 evidence card 明确告诉模型“这些证据可
            # 继续引用”。这样既保留追问能力，也不会回放上一轮冗长的 ReAct 轨迹。
            input_items.append({
                "role": "system",
                "content": "Previously cited evidence available for follow-up questions:\n" + json.dumps(
                    [_evidence_card(item) for item in turn.research_memory.evidence_items_by_id.values()],
                    ensure_ascii=False,
                ),
            })

        # 当前用户问题永远是本次新增输入的最后一项。
        input_items.append({"role": "user", "content": turn.question})
        run_config = RunConfig(
            # 项目有自己的运行记录边界，因此关闭 SDK 自带 tracing，避免重复或泄露敏感内容。
            tracing_disabled=True,
            # 明确规定 Session 历史和当前 input_items 如何合并。
            session_input_callback=request_session_input,
            # 授权状态会被工具逐步修改。即使模型一次提出多个工具，也必须按顺序执行。
            tool_execution=ToolExecutionConfig(max_function_tool_concurrency=1),
        )
        try:
            # Model 的 HTTP hooks 需要知道当前 ResearchRunContext。ContextVar 能跨 await 传递，
            # 又不会把项目参数硬塞进 SDK Model 的公共方法签名。
            with bind_research_context(context):
                result = await Runner.run(
                    agent,
                    input_items,
                    context=context,
                    max_turns=None,
                    hooks=ResearchRunHooks(),
                    run_config=run_config,
                    session=session,
                )
        finally:
            # 只关闭本方法自己创建的 Model。注入模型的生命周期由注入方管理。
            if owns_model:
                await model.close()

        # 正常路径由 tool_use_behavior 把已接受的 draft 放进 result.final_output。
        # context.final_draft 是兼容兜底，避免 SDK 返回包装类型变化时丢掉已接受结果。
        final = result.final_output if isinstance(result.final_output, dict) else context.final_draft
        if not isinstance(final, dict):
            raise RuntimeError("Agents SDK run ended without an accepted submit_research_answer")
        return final


def _evidence_card(item: JsonMap) -> JsonMap:
    """把完整证据压缩成适合下一轮提示词的最小卡片。"""

    return {
        "evidence_id": item.get("evidence_id"),
        "paper_id": item.get("paper_id"),
        "title": item.get("title"),
        "section": item.get("section"),
        "page": item.get("page"),
        "span_text": str(item.get("span_text") or "")[:900],
    }
