# P0-1 Spike：`run_streamed` 迁移会绕过协议执行层

> Status: 阻塞性发现（blocking finding），非实现
> Date: 2026-11-05
> Related: [`harness-runtime-management-prd-2026-11-05.md`](../../docs/engineering-evolution/agent-runtime/harness-runtime-management-prd-2026-11-05.md)
> 范围：`harness_py/orchestration/agents/model.py`、`harness_py/orchestration/agents/runtime.py`

## 0. 结论

PRD 的 P0-1（`Runner.run` → `Runner.run_streamed` + `cancel("immediate")`）**不能按字面迁移**。
对已安装的 `openai-agents==0.18.2` 逐项实测后确认：`run_streamed` 只调用 `Model.stream_response`，
而产品协议执行层（plain-text→nudge、工具参数修复、协议违规、在线诊断）全部写在
`_ObservedOpenAIModel.get_response` 里，`stream_response` **从未被覆写**。直接迁移会让整个协议层静默失效。

## 1. 证据

### 1.1 SDK 侧：流式路径只用 `stream_response`

- `Runner.run_streamed` → `AgentRunner.run_streamed` 创建后台任务 `start_streaming`（`agents/run.py`）。
- `run_single_turn_streamed` 中模型调用唯一走
  `model.stream_response(...)`（`agents/run_internal/run_loop.py:1474` 附近的
  `stream_response_with_retry(get_stream=lambda: model.stream_response(...))`）。
- 非流式路径 `run_loop.py:1891` 走 `model.get_response(...)`。两条路径互不共用。

### 1.2 产品侧：协议层只在 `get_response`

`harness_py/orchestration/agents/model.py` 的 `_ObservedOpenAIModel` 只覆写 `get_response`，其中包含：

- plain-text 响应 → 合成 `TEXT_NUDGE_TOOL_NAME` 调用（`PLAIN_TEXT_RESPONSE_REQUIRES_SUBMISSION`）；
- 工具参数修复（`_repair_function_call` / `TOOL_ARGUMENTS_INVALID_OR_TRUNCATED`）；
- 协议违规判定（`_raise_protocol_violation`）；
- MiniMax 在线诊断（`_diagnose_plain_text_response`）。

`MiniMaxAgentsModel` / `OpenAIResponsesAgentsModel` 只额外实现 `research_settings` 与诊断，
**都没有覆写 `stream_response`**。实测 `grep -n stream_response harness_py/orchestration/agents/model.py`
无结果；全 harness 也从未调用 `run_streamed`。

因此迁移到 `run_streamed` 后，模型原始输出会直接进入 runner 的工具调度，跳过上述全部校验——
这违反 CLAUDE.md「不做静默 fallback、失败不能伪装成成功」，也会破坏 evidence-first 契约。

## 2. 影响范围

| 受影响能力 | 现状（`get_response`） | 迁移后（`stream_response`） |
| --- | --- | --- |
| plain-text → 提交 nudge | 有 | 失效 |
| 工具参数修复 | 有 | 失效 |
| 协议违规 fail-closed | 有 | 失效 |
| MiniMax 在线诊断 | 有 | 失效 |
| `on_llm_start/end` 用量记账 | 有（Hooks 仍可用） | 可能保留，需确认 |

> 注：`ResearchRunHooks.on_llm_start/end` 是 SDK 生命周期 Hook，与 `get_response`/`stream_response`
> 无关，迁移后大概率仍触发，但协议层本身无法保留。

## 3. 可行路径（待产品/设计决策，本 spike 不选型）

1. **在 `_ObservedOpenAIModel` 中同步覆写 `stream_response`**，把协议执行逻辑从流式事件流里重建。
   成本高：需在 `ResponseCompletedEvent` 边界上重放 `get_response` 的后处理（nudge / repair /
   违规判定 / 诊断），且流式事件是增量 item，语义不等价。
2. **`after_turn` 取消而非 `immediate`**：不迁移到 `run_streamed`，保留 `Runner.run`，在
   `should_cancel` 边界生效。这只能做到「当前模型调用返回后停止」，达不到 AC-1 的 ≤2s 中断，
   但无需动协议层。
3. **在 `get_response` 内部用可中断原语替换 `asyncio.wait_for`**：当前模型调用已用
   `asyncio.wait_for(..., timeout=remaining_seconds())` 包住，取消本可在事件循环层打断；需评估
   是否能在不迁移 `run_streamed` 的前提下，通过 `call_soon_threadsafe` 取消外层 task 达成
   `immediate` 语义。
4. **让 `stream_response` 委托回 `get_response`**（丢弃真流式）：把 `stream_response` 实现为
   「调 `get_response` 后 yield 单个 `ResponseCompletedEvent`」。可保留协议层 + 获得
   `cancel("immediate")` 的 task 取消语义，代价是失去 token 级流（PRD 明确 P0 不要求 token 流，
   见 `Q-4`）。**这是本 spike 认为最接近目标且风险最低的路径。**

## 4. 建议

在推进实现前，需确认路径 4（或 1）的取舍。路径 4 不改变 Agent/Tool/校验逻辑，只在 Model 适配层
加一个「非流式兜底的流式接口」，同时满足：

- `run_streamed` 可被取消（`RunResultStreaming.cancel("immediate")` 取消后台 task）；
- 协议层不失效；
- 不交付 token 级答案流（PRD 已列为非目标）。

## 5. 核对方式

```bash
.venv-harness/bin/python - <<'PY'
import inspect
from agents.run import Runner
from agents.result import RunResultStreaming
print(inspect.signature(Runner.run_streamed))
print(inspect.signature(RunResultStreaming.cancel))
PY
grep -n 'stream_response' harness_py/orchestration/agents/model.py  # 无结果
grep -rn 'model.stream_response' .venv-harness/lib/python3.12/site-packages/agents/run_internal/run_loop.py
```
