# Research Harness 运行时管理 PRD

> Status: Proposed（未实施）
> Date: 2026-11-05
> Scope: `harness_py/transport`、`harness_py/orchestration`，以及一条可选的 Java 侧契约
> Related: [`harness_py/README.md`](../../../harness_py/README.md)、
> [`governed-research-run-spec.md`](../../architecture/governed-research-run-spec.md)

本文只讨论**运行时管理**：进程内的一次研究回合如何被取消、计数、探活和收尾。产品语义
（权限、Scope、Evidence、提交校验、fail-closed）不在本文范围内，且 MUST NOT 被改动。

## 0. 一句话

在产品边界完全不变的前提下，把缺失的四类运行时管理——**取消、注册表、就绪、停机**——补进
Python 壳层；优先复用已安装的 OpenAI Agents SDK 原生原语，不引入新框架，也不把权威状态搬回
Python。

## 1. 现状与问题

单进程 `harness_py serve` 承担全部研究回合，Java 持有权限、Scope、会话、配额与取消信号
（[`code-map.md`](../../architecture/code-map.md#L62-L68)）。这个边界是对的，但壳层没有随之补上
进程内管理能力。已知缺口全部可在代码中定位：

| 缺口 | 位置 | 当前行为 |
| --- | --- | --- |
| 取消只来自断流 | [`transport/service.py`](../../../harness_py/transport/service.py#L177-L229) | `disconnected.set()` → `should_cancel()` |
| 取消只在边界生效 | [`orchestration/run_control.py`](../../../harness_py/orchestration/run_control.py#L44-L64) | 模型调用前、工具调用前、边界后检查 |
| 在途模型调用不可中断 | [`orchestration/agents/runtime.py`](../../../harness_py/orchestration/agents/runtime.py#L237-L263) | `asyncio.wait_for` 只覆盖整个循环 |
| 同步端点没有取消 | [`transport/service.py`](../../../harness_py/transport/service.py#L51-L52) | `should_cancel=lambda: False` |
| 无在途记账 | [`transport/service.py`](../../../harness_py/transport/service.py#L221) | 每个请求一条裸 daemon thread |
| 无 lifespan、无排水 | [`transport/service.py`](../../../harness_py/transport/service.py#L119) | `uvicorn.run(app, ...)` |
| 只有 liveness | [`transport/service.py`](../../../harness_py/transport/service.py#L126-L132) | `/health` 不探测任何依赖 |
| 捕获失败不可见 | [`orchestration/live_chat.py`](../../../harness_py/orchestration/live_chat.py#L40) | 计数只出现在 CLI 输出 |
| 并发上限在 Java | [`AsyncExecutorConfig.java`](../../../src/main/java/io/github/chzarles/paperloom/config/AsyncExecutorConfig.java#L28-L40) | 线程池 `AbortPolicy` |

**后果。** 用户点停止后，最坏要等一次完整模型调用返回才真正停下；进程被 `SIGTERM` 时在途回合
直接丢失，Java 只能看到流中断；编排层异常无法被探针发现；Python 自身对突发并发没有防线。

## 2. 目标与非目标

### 目标

| ID | 目标 |
| --- | --- |
| `G-1` | 断流后取消在**一个边界内**生效，且能中断在途模型调用 |
| `G-2` | 进程内可枚举在途回合，供停机和观测使用 |
| `G-3` | 提供 liveness 之外的 readiness 探针 |
| `G-4` | 收到 `SIGTERM` 后先发终态事件再退出，不丢契约 |
| `G-5` | 管理路径自身 fail-closed，绝不把失败伪装成成功 |

### 非目标（MUST NOT）

- 不引入消息队列或 worker 池；不恢复 Redis Streams 传输；
- 不把 ConversationState、Scope、Evidence、引用映射的权威所有权搬回 Python；
- 不做跨机、跨重启的 durable execution；
- 不修改 Corpus 工具契约、提交校验、Evidence 授权阶梯；
- 不为"补齐管理"替换编排核心（LangGraph / Pydantic AI / ADK / 自研 Runtime）；
- 不在本 PRD 内交付 token 级答案流（那是产品变更，见 `Q-4`）。

## 3. 选型与依据

### 3.1 决策

```text
编排核心：OpenAI Agents SDK 0.18.2（不动）
壳层：Starlette 1.3.1 原生 lifespan + anyio（不引入 FastAPI）
取消：RunResultStreaming.cancel() + 进程内注册表
```

### 3.2 为什么不是"换框架"

已安装的 `openai-agents==0.18.2`（见 [`requirements.lock`](../../../harness_py/requirements.lock)）
本身已经提供本 PRD 需要的管理原语，实测确认：

| 需求 | SDK 原生能力（0.18.2 实测） |
| --- | --- |
| 立即取消 | `RunResultStreaming.cancel(mode="immediate" \| "after_turn")` |
| 断点续跑（暂不用） | `RunState.to_json()/from_json()`，`Runner.run(input=RunState)` |
| 人工中断（暂不用） | `RunState.get_interruptions()/approve()/reject()` |
| 会话接口 | `Session` 协议的五个方法，现有 `RequestBackedSession` 已合法实现 |
| 工具错误回传 | `RunConfig.tool_error_formatter`、`tool_execution` |
| 追踪导出 | `set_trace_processors()`（可替换为 OTel/Langfuse，需自建脱敏） |

`RunResultStreaming.cancel()` **只存在于流式 Run**，因此 `Runner.run` 必须迁移为
`Runner.run_streamed()`。这是本 PRD 里唯一一处触及编排循环的改动，且不改变 Agent、Tool、
指令或校验逻辑。

### 3.3 为什么不是 FastAPI

FastAPI 0.162 的元数据只声明 `starlette>=0.46.0`，没有上界；而本仓库把 `starlette` 钉在
`1.3.1`。"元数据兼容"不等于"集成已验证"，且 FastAPI 会新增 `opentelemetry-api` 与
`annotated-doc` 两个直接依赖。Starlette 1.3.1 已原生支持 `lifespan=` 参数，本 PRD 需要的
lifespan、排水、STREAMING 响应全部可得。**因此 P0 直接用 Starlette，FastAPI 降级为可选项**
（仅当需要 OpenAPI 客户端生成时再评估）。

### 3.4 对上一轮口头建议的修订（必须记录，避免扩散）

| 此前说法 | 结论 | 修正 |
| --- | --- | --- |
| "Temporal/Restate/DBOS 已是该 SDK 的集成" | **部分错误** | 文档有集成章节，但 0.18.2 包内只有 `extensions/memory/dapr_session.py` 和一个 Temporal patch 辅助；无 Temporal/Restate/DBOS 适配模块。它们是文档化的模式，不是内置依赖。 |
| "guardrails 可替代提交校验" | **错误** | `output_guardrails` 是 tripwire，命中会终止 Run；而提交校验 MUST 把结构化错误退回给同一模型继续修正。正确映射是 `tool_error_formatter` 加工具内校验，**不是** output guardrail。 |
| "FastAPI 换壳零成本" | **不准确** | 见 3.3：新增依赖 + Starlette 版本耦合风险。改用 Starlette 原生。 |
| "取消是一行改动" | **不准确** | 需迁移到 `run_streamed`；同步线程模型下的跨线程取消必须 `loop.call_soon_threadsafe`。 |
| "把 `RunState` 交给 Java 落库" | **成本被低估** | 需要新表、新生命周期与敏感载荷存储。列入非目标。 |

## 4. 需求

### `FR-1` 取消：断流即立即取消

- **现状**：断流只置位标志，下一个边界才生效；在途模型调用跑完才停。
- **要求**：
  - 每个回合 MUST 在 `run_streamed` 下执行，持有 `RunResultStreaming` 与所属事件循环；
  - 断流信号 MUST 通过 `loop.call_soon_threadsafe(result.cancel, "immediate")` 派发；
  - 取消后 MUST 产出既有终态：`status=CANCELLED`、`reason_code=RUN_CANCELLED`、一条
    `job_cancelled` 与最终 `result` 事件；
  - 重复取消、对已结束回合取消 MUST 是无副作用的 no-op；
  - `/v1/research/turn`（同步端点）保持不可取消，但 MUST 在文档中标注。
- **失败语义**：取消失败 MUST NOT 被吞成成功；MUST 记录 `run.error` 后按既有技术故障路径收口。

### `FR-2` 注册表：进程内可枚举在途回合

- **要求**：进程内维护 `{request_id: RunEntry}`，`request_id` 即 Java 的 `generationId`
  （[`ResearchHarnessPayloadFactory.java`](../../../src/main/java/io/github/chzarles/paperloom/service/ResearchHarnessPayloadFactory.java#L13)）；
  登记 MUST 在开始执行前完成，注销 MUST 在终态事件发出后完成；条目至少包含
  `run_id`、`conversation_id`、`user_id`、`started_at`、`state`（`running|cancelling|finished`）。
- **要求**：注册表 MUST 线程安全；它 MUST NOT 成为第二份权威业务状态，仅保存运行时索引。

### `FR-3` 就绪探针

- **要求**：新增 `GET /ready`。返回 200 仅当：provider 配置可加载、内部 token 配置一致、
  最近一次 Corpus 探测未失败。返回 503 时 body 必须包含 `reason`。
- **要求**：依赖探测 MUST 带缓存（建议 30s TTL）并在后台刷新；`/ready` MUST NOT 同步穿透到
  每一次请求。
- **要求**：`/health` 语义不变（liveness），MUST NOT 因依赖故障而失败，避免拉起循环抖动。

### `FR-4` 优雅停机

- **要求**：使用 `Starlette(lifespan=...)`；进入停机时先把应用标记为 `draining`。
- **要求**：`draining` 期间新请求 MUST 返回 `503` 并带 `Retry-After`。
- **要求**：在途回合 MUST 先收到取消（优先 `after_turn`，超时后 `immediate`），并 MUST 在
  关闭连接前发出 `job_cancelled` 与终态 `result`。
- **要求**：排水总时长 MUST 有上界（默认 30s，可配置）；超界时 MUST 记录未完成回合列表后退出。

### `FR-5` 并发自守（可选，默认关闭）

- **要求**：提供 `RESEARCH_HARNESS_MAX_CONCURRENT_TURNS`，默认 `0` 表示不限制，保持现状。
- **要求**：超限时 MUST 返回 `429` 且不排队；除非该变量显式开启，否则 MUST NOT 改变当前行为
  （Java 已在上游限流）。

### `FR-6` 配置收敛

- **要求**：新增运行时配置对象，用已在环境中的 `pydantic-settings==2.14.2` 声明；现有的
  `_positive_env_int` 一类手写解析 MUST 收敛到它。
- **要求**：非法配置 MUST 在启动时失败，不得延迟到首个回合。

### `FR-7` 运行时可观测

- **要求**：`/health` 与 `/ready` 输出 MUST 包含 `in_flight`、`eval_capture_failures`。
- **要求**：`EvalRecorder` 落盘失败 MUST 在可观测输出中体现（当前仅 CLI 可见）。

## 5. 对外契约

| 项 | 变化 | 兼容性 |
| --- | --- | --- |
| NDJSON 事件集合 | 不变（已有 `job_started/job_cancelled/job_failed/result`） | 前端无需改 |
| `POST /v1/research/stream` | 行为增强：取消更早生效 | 事件顺序不变 |
| `GET /health` | 新增字段 | 兼容 |
| `GET /ready` | 新增 | 兼容 |
| `POST /v1/research/turn` | 不变 | 兼容 |
| 503 / 429 | 新增语义 | Java 侧当前按非 200 失败处理，语义正确但可读性差，见 `Q-1` |

## 6. 非功能要求

- **fail-closed**：任何新增路径不得把取消、超时或依赖故障映射为成功 Run。
- **隐私**：不得在日志、探针或追踪中输出论文正文、Prompt、用户问题；沿用现有脱敏规则。
- **单机假设**：注册表为进程内结构；增加第二个宿主进程需要重新审视（与现有部署假设一致）。
- **依赖纪律**：P0 不新增直接依赖；P1 若启用配置对象，需把 `pydantic-settings` 写入
  `requirements.in`（当前仅为传递依赖）。

## 7. 交付计划（垂直切片）

| 阶段 | 切片 | 产出 | Java 改动 |
| --- | --- | --- | --- |
| P0-1 | `run_streamed` + 断流即取消 | `FR-1` | 无 |
| P0-2 | 注册表与计数 | `FR-2`、`FR-7`（部分） | 无 |
| P0-3 | lifespan + 排水 | `FR-4` | 无 |
| P0-4 | `/ready` 与缓存探测 | `FR-3` | 无 |
| P1-1 | 配置收敛 | `FR-6` | 无 |
| P1-2 | 并发自守（opt-in） | `FR-5` | 无（默认关闭） |
| P2 | OTel 追踪处理器 | 可观测 | 无 |

每个切片 MUST 独立可测、可回滚；P0 全部完成后 Java 侧无需改动即可受益。

## 8. 验收标准

| ID | 验收 |
| --- | --- |
| `AC-1` | 注入 60s 假模型调用，发送取消后 **≤2s** 内产出 `CANCELLED` 终态（现状：需等模型返回） |
| `AC-2` | 回合执行期间注册表可见该 `request_id`；终态后条目消失 |
| `AC-3` | `SIGTERM` 后：新请求得 `503`；在途回合 ≤30s 内收到 `job_cancelled` 与 `result`；进程退出码 0 |
| `AC-4` | provider 未配置时 `/ready` 返回 `503` 且含 `reason`；`/health` 仍返回 200 |
| `AC-5` | 既有 `harness_py/tests` 全绿；新增覆盖 FR-1/2/3/4 至少四个测试 |
| `AC-6` | 回归：不存在把取消/超时/依赖故障映射为成功 Run 的路径 |
| `AC-7` | 事件顺序回归：每个终态之前 MUST 恰好一条终态事件，不重复、不缺失 |

## 9. 风险与缓解

| 风险 | 缓解 |
| --- | --- |
| `run_streamed` 与现有 `tool_use_behavior` 的最终输出判定不一致 | 先写隔离测试：同一脚本化模型在两种 Runner 下产出相同 `final_output` |
| `cancel("immediate")` 后 `stream_events` 的终止形态未验证 | Spike：确认抛出/结束语义、`to_state()` 是否可用、是否有清理泄漏 |
| 跨线程取消竞态（线程已结束 / 循环已关闭） | 注册表内保存 `loop`，用 `call_soon_threadsafe` 并捕获 `RuntimeError`；幂等 |
| 停机排水导致 Java 超时 | 排水上界 30s << Java 930s 请求超时；先发终态事件再关流 |
| 流式改造引入 token 型事件，前端误解 | 明确本期只消费既有事件集合；token 流留待 `Q-4` |
| Starlette 1.3.1 与未来 FastAPI 的耦合 | 本期不引入 FastAPI；如引入需单独 spike |

## 10. 待决问题

- `Q-1`：`503/429` 是否需要在 Java 侧映射成产品级"服务繁忙"，而不是统一的
  `INTERNAL_UNEXPECTED`？
- `Q-2`：是否需要在断流之外提供显式 `POST /v1/research/cancel`（用于套接字未立即关闭的场景）？
  若需要，Java 侧要多一次内部调用与幂等处理。
- `Q-3`：并发上限的归属——继续只由 Java 控制，还是 Python 也保留一层防线？
- `Q-4`：是否上线 token 级答案流（`run_streamed` 已具备能力，但需要前端新增事件分支）？
- `Q-5`：是否启用 SDK 追踪处理器接入 OTel/Langfuse，还是继续只保留 `EvalRecorder`？

## 11. 附录：核对方式

本文的 SDK 能力不是从文档推断，而是对已安装版本逐项实测：

```bash
.venv-harness/bin/python - <<'PY'
import inspect, agents
from agents.result import RunResultStreaming
from agents.run import Runner
from agents.run_state import RunState
print(agents.__version__)
print(inspect.signature(Runner.run))
print(inspect.signature(RunResultStreaming.cancel))
print([m for m in dir(RunState) if not m.startswith("_")])
PY
```

FastAPI 的依赖约束来自 wheel 元数据：

```bash
pip download fastapi --no-deps --dest /tmp/fapi-meta
unzip -p /tmp/fapi-meta/fastapi-*.whl 'fastapi-*.dist-info/METADATA' | grep Requires-Dist
```
