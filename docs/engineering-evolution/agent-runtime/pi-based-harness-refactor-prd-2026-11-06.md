# 以 pi 为底座的研究 Harness 重构 PRD

> Status：**Superseded**——被同日同目录的
> [pi-native-agent-refactor-prd-2026-11-06.md](pi-native-agent-refactor-prd-2026-11-06.md) 取代。
> 方向变化：从"等价迁移 harness 到 pi"改为"删除自研 harness，chatbot 直接调 pi 封装的 agent"。
> 本文件保留其 §3.1 pi 实查事实、§3.3 已证结论与 R1/R2/R6 等仍适用的需求原文，新 PRD 直接引用。
> Date：2026-11-06
> Scope：`harness_py` 生产运行时的整体替换（Python/OpenAI Agents SDK → Node/pi）。本文覆盖四件事：
> 选型姿态、定制 tool、记忆对接、能力裁剪。Java / MySQL / Qdrant / 前端契约是**只读不变量**。
> Related：
> - [pi-agent-typescript-runtime-migration-design-2026-08-17.md](pi-agent-typescript-runtime-migration-design-2026-08-17.md)（历史评估，当时结论"未选择"）
> - [harness-runtime-management-prd-2026-11-05.md](harness-runtime-management-prd-2026-11-05.md)（运行时管理四件套，构成本次迁移的对等基线）
> - [harness-reading-tools-extraction-plan-2026-08-22.md](harness-reading-tools-extraction-plan-2026-08-22.md)（Java 侧工具抽取，待实施，互补不互斥）
> - [governed-research-run-spec.md](../../architecture/governed-research-run-spec.md)、[`research/design/harness-runtime-shape.md`](../../../research/design/harness-runtime-shape.md)（产品语义权威）

评审收敛以取代方案为准；ADR-0017 由取代 PRD 归档。

## 0. 一句话

把 Python + `openai-agents` 的自研 harness 运行时，替换为以 **pi**（`earendil-works/pi`，原
`badlogic/pi-mono`）为底座的 Node 研究运行时：**运行时机制归 pi**（模型循环、工具执行、会话/记忆原语、
流式事件、取消原语），**研究协议归 PaperLoom**（授权阶梯、Evidence Ledger、引用门、提交契约），
对外契约零变化。

## 1. 背景：为什么现在推翻 2026-08-17 的"不迁移"结论

2026-08-17 的评估结论是"TypeScript 迁移可行但不选择"，只在 Python 侧做了 Provider 协议加固
（ADR-0014、`paperloom-harness-runner-hardening-spec`）。当时方案的本质是：**只取 pi 的低层包
（`pi-ai` + `pi-agent-core` 0.84.1），自己重写 Worker 循环、会话、持久化、修复协议**。此后的事实变化：

| 维度 | 2026-08-17 评估时 | 现在（2026-11） |
| --- | --- | --- |
| pi 的形态 | 低层库（agentLoop / EventStream），其余自建 | 1.5.0：正式 **TypeScript SDK**（`AgentSession`、`SessionManager.inMemory()`）、扩展系统（`registerTool`、事件拦截、注入上下文、权限门）、print/JSON/RPC 模式、模型目录与 provider 管理 |
| 传输边界 | Redis Streams + 4 worker 进程 | 已收敛为**单条 HTTP NDJSON 流**（单进程多回合，2026-11 移除 Redis 传输）。新 runtime 只需替代一个薄壳 |
| 运行时管理 | 取消/记账/就绪/停机全缺 | 2026-11-05 PRD 四件套已落地：断流取消贯穿、in-flight registry、`/ready`、SIGTERM 排水。迁移有了**明确的对等基线**，不再靠猜 |
| 自研运行时维护事实 | 聚焦 MiniMax Provider 一次修复 | `openai-agents==0.18.2` 专用适配（`agents/model.py` 762 行）、`RequestBackedSession` 缝合、`_continue_research_turn` 内部修复工具 + 3 次预算 + 在线诊断，长期持有、随 provider 版本持续再修 |
| 品类事实 | 视作"另一个 SDK" | pi 已是品类级开源底座（120k+ stars、MIT、供应链加固、真实产品以 SDK 方式集成）。自研每多一行 loop/session/工具执行代码，都是在重写 pi 的产品 |

**判断**：PaperLoom 的差异化不在 agent runtime 机制本身，而在研究协议与语料授权。2026-08 的方案
当时的最大风险是"自己在 pi 之上再建一个 harness 框架"；今天 pi 把框架层做成了公共底座，那个风险
的载体消失了。本 PRD 与历史评估的关系是**取代其选型结论，继承其全部已证事实与纪律**（见 §3.3、§6）。

### 1.1 当前生产事实（新运行时必须对等的清单）

来源：`harness_py/README.md`、`transport/service.py`、`docs/architecture/code-map.md`。

| 事实 | 当前值 |
| --- | --- |
| 服务边界 | 单进程 Starlette，`POST /v1/research/turn`（同步）、`POST /v1/research/stream`（NDJSON 流）、`GET /health`、`GET /ready`；Bearer 共享密钥 |
| 编排核心 | `openai-agents==0.18.2` Runner；单回合一个 `ResearchRunContext` / `Model` / `RequestBackedSession`；最多 **16 个模型 Turn** |
| 模型可见工具 | 5 个语料工具（`search_paper_candidates`、`find_papers_by_identity`、`get_paper_structure`、`search_paper_content`、`read_paper_content`）+ `get_research_skill` + 3 个提交工具（`submit_direct_answer` / `submit_catalog_answer` / `submit_research_answer`）；另有内部协议修复工具 `_continue_research_turn` |
| 契约 | DIRECT / CATALOG / RESEARCH 互斥；`submit_*` 独占最终 Tool Step；引用必须来自本 Run 已读取的 `source_quote_ref`；校验失败返回同一个 Agent 修复 |
| 授权阶梯 | Java 锁定 scope → 候选/身份工具公开 Paper → 内容检索公开 Location → `read_paper_content` 读取并产生唯一可引用 Evidence → submit 校验 |
| Provider 配置 | `--provider-source db/env/openai-env`；db 与 Java 共用 `model_provider_configs` 表（解密密钥 `MODEL_PROVIDER_SECURITY_SECRET_KEY` 或 `JWT_SECRET_KEY`），**下一回合即生效**；未配置时 `/ready` 报 `provider_unconfigured`，回合 fail-closed |
| 关键模型 | MiniMax-M3（`minimax-cn`，`anthropic-messages` API） |
| 运行时管理 | 断流取消贯穿到在途模型调用、in-flight registry、`/ready` 依赖探测、SIGTERM 排水（2026-11-05 PRD 全部落地） |
| Run limits | `paperloom-run-limits/v1`：`max_wall_clock_ms`、`max_model_visible_tool_chars`、`max_history_chars`；协议修复预算 3 次 |
| Eval Capture | `EVAL_DUMP_DIR` 记录 per-run `events.jsonl` + `result.json`（模型请求/响应、Tool 前后授权快照、验证失败、Token、时延等） |
| 记忆 | 跨回合权威 `ConversationState`（Java/MySQL）；本轮投影 `ResearchMemory`（已选论文/已接受 Evidence）；SDK Session 仅进程内 |
| 转发语义 | Java 只认 `result` 与 `error` 事件，其余 NDJSON 事件原样透传前端 |
| 评测门 | PaperLoom-31：L1 Recall@1 1.0、L2 Recall@10 ~0.81、L3 Hard 17/17、Contract Accuracy / Protocol Replay / Provenance 1.0（历史基线，见 §6） |

### 1.2 维持现状的成本（痛点）

1. 每个 provider 差异（Thinking 回放、`tool_choice`、纯文本违规）都落在 Python 运行时代码里；
   `_continue_research_turn` + 诊断修复是**只服务于协议修复**的自研机制，却在每次 provider 变化时
   都要重新验证。
2. Agents SDK 的 Session 语义与产品记忆语义不一致，靠 `RequestBackedSession` 最小实现缝合
   （`orchestration/memory.py` 里明确注明"名字相近，生命周期完全不同"）。
3. 工具执行框架（分发、授权快照、提交门）自研于 `agents/tools.py`（617 行）+ `agents/runtime.py`（427 行），
   pi 的 `AgentTool` + hooks 已原生覆盖。
4. "做了会很有用"的能力（token 级流式推送、多 provider、上下文压缩、结构化 capture 复用）全部排在
   自研路径上（token 流是 2026-11-05 PRD 的显式非目标 Q-4）。

## 2. 目标与非目标

### 2.1 目标

| ID | 目标 | 验证方式 |
| --- | --- | --- |
| G-1 | **产品等价**：§1.1 清单所列契约、授权阶梯、引用门、Run limits、取消/停机、quota 记账、Eval Capture、事件流全部对等；`governed-research-run-spec` 与 `harness-runtime-shape` 语义零改动 | §6 硬门全部通过 |
| G-2 | **定制 tool**：9 个模型可见工具全部收敛为 pi 扩展注册的工具；Java Reading Tools 错误码映射不变；授权快照与 Evidence Ledger 行为逐字节等价 | 工具 fixture 双跑 + Java 实测 |
| G-3 | **记忆对接**：T0/T1 两层记忆与 pi 会话原语对齐，跨回合权威仍在 Java/MySQL；T2（跨会话研究记忆）以最小切片落地且按 user 隔离 | 记忆注入边界测试 + 授权过滤测试 |
| G-4 | **能力裁剪**：pi 的编码/会话/持久化能力一概不加载；被裁能力被触发时 fail-closed，绝不静默 | 裁剪清单审计 + 触发测试 |
| G-5 | **运行时管理对等**：消化 2026-11-05 PRD 四件套（取消、注册表、就绪、停机）到新 runtime | ops 探针测试 |
| G-6 | **配对评测过关**：PaperLoom-31 与行为硬门全绿后才谈切换 | §6 gate |
| G-7 | **维护面收敛**：自研模型适配/循环/会话代码清零；新 runtime 只保留"协议 + 适配"两层 | 模块处置表（§4.2）验收；产物行数记录进切换报告 |

### 2.2 非目标（MUST NOT）

- 不扩大产品能力：不动 deferred 列表（Field Extraction、公开 OAuth、citation graph、多模态检索、
  web agent）；不新增 subagent、MCP、web 工具。
- 不修改 Java / MySQL / Qdrant / Redis / 前端契约；`/v1/research/stream` 的请求与 result 载荷字段
  只允许等价替换，不允许增删改语义。
- 不做 OpenAI 兼容公共出口。
- 不在本次迁移中改变并发拓扑：保持"单进程、N 个并发回合、上限由 Java 池约束"。并发变化须单独立项。
- 不动 Python 离线评测资产（golden 数据、scorer contract、judge 校准）；评测**驱动方式**的改造
  收敛在"从新运行时抓取"，不保留第二套模型编排（见 R6、§7 Phase 6）。
- 不以生产环境当测试环境；开发期生产零变化。

## 3. 选型与依据

### 3.1 pi 基线（2026-11-06 实查）

| 项 | 实查值 |
| --- | --- |
| 仓库 | `earendil-works/pi`（原 `badlogic/pi-mono`），MIT,依赖精确 pin、install-lock、`min-release-age=2`、CI `npm audit` |
| npm | `@earendil-works/pi-coding-agent` 1.5.0（SDK 所在包）、`@earendil-works/pi-ai` 1.5.0、`@earendil-works/pi-agent-core` 1.5.0（2026-11-06 实查）；实施时以 lockfile 精确 pin |
| 运行时 | Node `>=22.19.0`（strip-only TS）。生产机系统 Node 为 20.19.2（2026-08 观测）→ 必须安装**隔离的 Node 22**，不替换系统位 |
| Provider | 内置 MiniMax / MiniMax China（`MINIMAX_API_KEY`、`MINIMAX_CN_API_KEY`）；`models.json` 兼容端点与自定义 provider；thinking level、sampling、cost 记账内建 |
| SDK（in-process） | `AgentSession` 生命周期；`SessionManager.inMemory()`（不留 session 文件）；`resourceLoader` 装载扩展/skills/模板/上下文；`tools` / `noTools` / `excludeTools` / `customTools` 控制工具集；事件订阅 |
| 扩展 | `pi.registerTool()` 自定义工具；**事件拦截**（阻断或改写工具调用、注入上下文、自定义 compaction）；`pi.appendEntry()` 持久化扩展状态；权限门；TypeBox 参数校验 |
| 其他模式 | CLI print/JSON/RPC 模式（子进程集成备选）；`pi-server` 多 agent 编排；`pi-tui` |
| 记忆 | pi 核心**无内建记忆**；官方生态为扩展形态（`pi-memory` 系：文件型 markdown 记忆目录，prompt 前注入）。多用户产品不能直接落盘，见 R4 |

### 3.2 集成姿态（D-1：选 TS SDK，in-process）

| 姿态 | 说明 | 判断 |
| --- | --- | --- |
| A. 低层包（`pi-ai` + `pi-agent-core` 自建循环） | 2026-08-17 原方案 | **否**。等于回到自建 harness 框架，正是被放弃的老路 |
| B. CLI / RPC 子进程 | 语言无关、进程隔离 | **否**。每请求一进程 + 断流取消模型不适配；TUI/RPC 面板能力全数多余 |
| C. TypeScript SDK，in-process（**选定**） | Node HTTP 服务内嵌 `AgentSession`：会话、工具执行、事件流、取消直接用 pi 原语 | **是**。与"单机单进程、每请求一回合、无队列"的现有拓扑精确匹配 |

D-1 承担的已知代价：pi 升级的破坏性变更风险内嵌进业务进程。对策（不引入，均为纪律）：lockfile
精确 pin；升级作为一个独立的小 PR + 全量 §6 门；`pi-ai` / `pi-agent-core` 的类型只允许出现在
进程内部，**绝不进入 HTTP 载荷**（承袭 2026-08-17 §5.1 的"Pi 类型不出现于对外契约"规则）。

### 3.3 继承 2026-08-17 已证事实（仍然有效）

1. MiniMax-M3 真实两步工具循环在 pi 上可行（`anthropic-messages`、thinking 与 tool result 回放正确）；
2. `tool_choice: any` 是协议字段不是正确性证明——MiniMax 曾在带 tool_choice 时仍返回纯文本。因此
   **"无合法工具调用的纯文本 = `PROVIDER_TOOL_PROTOCOL_VIOLATION`，确定性技术终态，不发布、不合成
   继续"** 是行为需求，迁移后重测；
3. `effort` 参数对 MiniMax 无效，thinking 预算以实测 payload 为准，是 benchmark 参数不是设计参数；
4. Provider 收窄走 `models.stream()` 级别的显式选项（2026-08 为 `streamSimple` 不转发 tool_choice
   的教训；1.5.x 是否仍如此属 Phase 0 必验项）。

## 4. 目标架构

### 4.1 目标链路

```text
Java（全部契约不变）
  ChatWebSocketHandler -> ProductReadingConversationService -> PythonResearchHarnessClient*
  *仅实现替换：客户端类与请求/事件词汇不变

  HTTP POST /v1/research/stream|/turn （Bearer, NDJSON）
    -> Node research-harness 服务（接管 8091 拓扑位）
       -> HTTP 壳层 B1：认证、in-flight registry、/health、/ready、SIGTERM 排水、断流->AbortController
       -> 回合编排 B2：从 job 装配 prompt/记忆/history，产生事件与 result
       -> pi AgentSession（SDK, in-memory, 无编码工具）
            - B3 定制 tools 扩展（9 个工具 + 授权状态机 + Evidence Ledger）
            - B4 契约/校验门（DIRECT/CATALOG/RESEARCH + submit 独占 + 引用校验）
            - B5 Provider 适配（model_provider_configs 热配置 -> pi model 解析）
            - B6 Capture 扩展（Eval/Trace 投影、usage 记账）
       -> Corpus adapter B7（native fetch -> Java InternalCorpusController，独占数据面）
```

```text
Node 服务内部只有两块代码：协议层（B2/B4 + B3 的授权与投影规则）与适配层（B1/B5/B6/B7）。
没有第三块"框架层"。
```

### 4.2 模块处置映射（harness_py → 目标）

| 现文件（行数） | 现职责 | 处置 |
| --- | --- | --- |
| `orchestration/agents/model.py`（762） | MiniMax/OpenAI 兼容适配、HTTP capture、违规检测 | **删除自研**；capture 并入 B6，适配职责归 `pi-ai`（B5 只做配置解析与模型解析） |
| `orchestration/agents/runtime.py`（427） | Agent/Runner/Session/Hooks 组装 | **删除自研**；由 B2 + `AgentSession` 配置替代 |
| `orchestration/agents/tools.py`（617） | FunctionTool 分发、提交门、修复工具 | 语义并入 B3/B4；`_continue_research_turn` 机制**不移植**（处置见 R5.3） |
| `orchestration/agents/context.py`（257） | 单 Run 可变状态 | 移植 B2（`ResearchRunContext` 语义不变） |
| `orchestration/research_contract.py`（768） | 契约状态机 + 715 行级 Validator | **原样移植** B4，fixture 双跑（Phase 2 硬门） |
| `orchestration/conversation.py`（219）+ `memory.py`（69） | 跨回合状态与本轮记忆投影 | 投影逻辑移植 B2；权威仍 Java（R4） |
| `orchestration/live_chat.py`（428） | 回合边界、Recorder 生命周期 | 移植 B2/B6 交界 |
| `orchestration/run_output.py`（313） | result/引用渲染 | 移植 B2（result 字段逐字节对齐） |
| `orchestration/run_control.py`（91） | 限额/取消检查 | 移植 B2 + `AbortController` 贯穿 |
| `orchestration/research_skills.py`（235） | 22 个研究技能装载 | 数据不动，loader 移植（技能文本属内部 Prompt，无契约影响） |
| `corpus/gateway.py` | Java Corpus HTTP | 移植 B7（native fetch，鉴权/参数/超限约束不变） |
| `corpus/tools.py` | Tool schema/授权/Ledger | 移植 B3（TypeBox schema 表达现 schema） |
| `transport/service.py`（649） | HTTP 壳 | 重写 B1（同契约、同探针语义） |
| `transport/provider_config.py`（253） | db/env/openai-env 三来源 | 移植 B5；**解密兼容**列为开放问题 Q-4 |
| `evaluation/`、`corpus_test_fixtures/` | 离线评测 | **保留 Python**（离线域），仅驱动方式改造（R6.3） |
| `cli.py`（771） | 产品语料 chat / golden / rescore 入口 | rescore/scorer 留；agent 驱动入口改为 HTTP 调用新运行时（见 Q-5） |
| `corpus/product_db_dataset.py`、`pages.py` | 离线迁移诊断/golden 本地 MySQL | 保留 Python（非生产路径） |

预期：生产路径 Python（`core/corpus/orchestration/transport/utils`，不含 evaluation）约 6.8k 行，
替换为 **<2.5k 行 TS**（只计协议+适配两层；作为切换报告的观测项，不作为承诺门）。

## 5. 需求详述

优先级：P0 = 迁移必需且阻塞切换；P1 = 迁移窗口内交付；P2 = 评审后再排（本 PRD 只立项不实施）。
每条 MUST 可测，MUST NOT 由审计与触发测试证明。

### R1 服务壳（P0）——接管 `transport/service.py`

| ID | 要求 |
| --- | --- |
| R1.1 MUST | 端点、方法、鉴权（Bearer 共享密钥）、NDJSON 事件序、result 终态语义与现状逐字节等价；请求/事件/result 载荷 schema 冻结，不允许因 pi 类型变动 |
| R1.2 MUST | lifespan 管理对等：进程注册表可枚举在途回合（`request_id`/`run_id`/`conversation_id`/`user_id`/起止），登记先于执行、注销后于终态事件 |
| R1.3 MUST | 断流即取消：连接中断 → `AbortController` → 中断在途模型调用与 Corpus 请求 → 发布现有 cancelled 终态事件；同步端点保持"不可取消"的现有语义 |
| R1.4 MUST | `SIGTERM` 先排水：在途回合发出终态事件、注销完成后才退出，不丢契约 |
| R1.5 MUST | `/health` 语义不变；`/ready` 探测 Java Corpus 与 Provider 配置，未配置时 `provider_unconfigured` fail-closed，不同步穿透 |
| R1.6 MUST | 单进程异步并发 N 回合（Node 事件循环），上限回压给 Java（沿用现 Hikari/池上限约束，不新增并发参数面） |
| R1.7 MUST | 部署：隔离 Node 22（pin 小版本），systemd 单元引用绝对路径；不触碰系统 Node、前端工具链 |

### R2 Provider 层（P0）——替掉 762 行自研适配

| ID | 要求 |
| --- | --- |
| R2.1 MUST | 三来源对等：`db`（`model_provider_configs`，与 Java 共表，**保存后下一回合生效**，无需重启）、`env`（MiniMax 逃生通道）、`openai-env`；`/ready` 语义不变 |
| R2.2 MUST | 每回合从 DB 配置解析 pi model/provider（含自定义 endpoint、API、thinking）；解析失败 = 技术失败终态，fail-closed |
| R2.3 MUST | Thinking/tool-choice/回放策略以 **Phase 0 录制的真实 payload 证据**定参；证据文件入库 `research/`（与 golden 数据同隔离级别，敏感数据本地处理）（承袭 §3.3-3/4） |
| R2.4 MUST | 每回合 usage（prompt/completion/总量、请求次数、时延）进入 result usage 字段，喂 Java 配额结算；口径与现 result 对齐 |
| R2.5 MUST NOT | 不出现 provider 名硬编码驱动的行为分支（现"M3 专用"行为一律来自配置与证据，不由代码 `if provider` 派生新语义） |

### R3 定制 tool（P0）——pi 扩展层承接全部模型可见能力

以一个 pi 扩展包（B3）注册 9 个工具，工具 schema 以 TypeBox 表达，与 `corpus/tools.py` 现有
JSON schema 字段对齐（参数、枚举、默认值）；`execute()` 内做参数复核（承 pi 文档约束：`Type.Refine`
等不生效处自行检查）。

| ID | 要求 |
| --- | --- |
| R3.1 MUST | 工具集 = 5 语料工具 + `get_research_skill` + 3 `submit_*`；名称、参数、返回投影（含 `location_ref`、`source_quote_ref`、Disclosure 卡片）与现模型可见语义等价 |
| R3.2 MUST | 授权阶梯与四个状态集合（disclosed papers / locations / quotes / 引用集）在 `ResearchRunContext` 语义下单调增长；`read_paper_content` 仍是唯一可引用入口 |
| R3.3 MUST | 工具顺序执行（sequential），因为授权状态随每个结果增长；submit 独占最终 tool step；同响应内 submit 与兄弟工具调用按现规则拒绝 |
| R3.4 MUST | Capture 保留工具调用前/后授权快照（评测离线可区分"没找到/没读/没引用/弱引用"） |
| R3.5 MUST | Java Reading Tools 错误码映射不变：`READING_ARGUMENTS_INVALID`/`PAPER_NOT_AVAILABLE` → 可恢复 tool result；`READING_MODEL_NOT_READY`/`RETRIEVAL_UNAVAILABLE` → `ResearchSystemError` 技术终态 |
| R3.6 MUST | 语料数据面独占走 B7 → `InternalCorpusController`；Node 不接 MySQL/Qdrant，不扩大 scope，不落地第二数据面。与 tools 抽取计划（Java `PaperReadingService`）互不阻塞：harness 永远只依赖 Java 契约 |
| R3.7 MUST | 输入投影/输出上限（`max_model_visible_tool_chars`、byte 限、超时）逐条移植；`db-config provider` 下的工具行为与现行为一致 |

### R4 记忆对接（P0 等价 + P1 最小新增）

分层定义与归属：

| 层 | 内容 | pi 原语 | 权威 |
| --- | --- | --- | --- |
| T0 本 Run 轨迹 | 本次回合的 user/assistant/tool 消息、thinking | `AgentSession` + `SessionManager.inMemory()`；compaction 关闭，本回合完整轨迹自然回放（与现行为一致） | 无权威（丢弃） |
| T1 跨回合 ConversationState | 历史 user/assistant 文本、已选论文、**已接受** Evidence、tool traces | 每回合从 Java 装载 → 构造 prompt/上下文注入；T0 会话仅本回合有效，pi session 文件 MUST NOT 落盘 | Java/MySQL（唯一权威） |
| T2 跨会话研究记忆（P1，新增能力） | 用户显式沉淀的研究笔记/偏好/跨会话结论 | pi 记忆扩展形态：prompt 前注入摘要 + 显式存取工具；存储在 Java 新表（`user_id` 隔离） | Java/MySQL |

| ID | 要求 |
| --- | --- |
| R4.1 MUST | T0/T1 等价：上一轮已接受 Evidence 以 bounded 受信上下文带入（非伪造历史工具调用）；Retry/追问按现语义作为反馈上下文 |
| R4.2 MUST | T1 复用 `ResearchMemory` 投影语义（selected_paper_ids / selected_evidence_ids / evidence_items_by_id）；`/clear`、`/new` 语义不变 |
| R4.3 MUST | pi session 不落盘、无 JSONL/persisted entry tree 在产品路径出现；`SessionManager.inMemory()` 是唯一会话实现 |
| R4.4 MUST | T2 注入前过授权过滤：记忆内容只能引用用户当前可见论文 id 之外的结构化事实/笔记——不含越权位置或正文；记忆注入不影响引用门（记忆内容**不可作为** `source_quote_ref`） |
| R4.5 P1 | T2 最小切片：存储表 + 两个工具（`save_research_note` / `recall_research_notes`）+ 每回合 bounded 摘要注入；schema 以产品讨论后定稿（Q-3），表结构走 Java migration |
| R4.6 MUST NOT | 不引入"记忆写回语料"或任何以记忆替代 Evidence 的路径；记忆不是 evidence，引用门不感知记忆（承接 Product Direction："discovery 结果永远不是 evidence"的同族纪律） |

### R5 能力裁剪（P0）——以"不加载 + fail-closed"双重证明

| ID | 要求 |
| --- | --- |
| R5.1 MUST | pi 工具面只装载 B3 注册的工具：P0 为 9 个模型可见工具（R4.5 落地后另加 2 个记忆工具）；`bash/read/edit/write` 等编码工具、`codemode`、`tool_search` 一概不注册/不加载（SDK 注：这三者本就默认不在 SDK 会话激活，需显式排除确认） |
| R5.2 MUST | 裁剪清单审计作为切换门：对生成物做"已加载扩展/工具/资源"清单 diff，出现清单外能力即切换失败 |
| R5.3 MUST | `_continue_research_turn` 与在线诊断修复机制**不移植**。替代路径：Phase 0 验证 pi 下的天然行为（agent loop 对纯文本的处理 + 事件拦截注入一次性修复上下文）；若经 payload 证据证明 MiniMax 在 1.5 下违规率与修复产出与现状相当，则不需要重实现；若不等价，用 pi 事件拦截实现**有界（≤3 次）修复**，规则进 B4，且预算/终态行为与现状对齐（成功修复的草稿规则照旧） |
| R5.4 MUST | 不加载：MCP 扩展、pi skills/prompt templates、pi 上下文文件发现、steering/follow-up 队列、compaction、多 agent/pi-server、TUI 组件、pi 供应商目录全部 provider（只启用配置指定的）、pi 自带 session 持久化 |
| R5.5 MUST | 触线 fail-closed：任何被裁能力在运行期被请求/触发（例如模型自造工具名、env 泄漏导致扩展自动装载）→ 技术失败终态，绝不静默降级（承 CLAUDE.md"不得让失败看起来成功"） |
| R5.6 MUST | 上下文压缩不启用：现服务无 compaction（整历史 + bounded 投影），pi compaction 关闭；将来的 token 流/压缩是独立立项 |

### R6 观测、Eval、配额（P0）

| ID | 要求 |
| --- | --- |
| R6.1 MUST | Trace 词汇不变：PaperLoom 契约迁移、授权事实、验证失败、Source Quote 身份仍是显式 trace 记录（不靠 prose 事后推断）；pi 事件 → 现词汇的映射表在代码仓内维护 |
| R6.2 MUST | `EVAL_DUMP_DIR` 等价 capture：`events.jsonl` + `result.json` 字段清单对齐现 README 列举（Run Input、Conversation、Scope、Previous Evidence、Model Request/Response/Error、Retry、Tool Raw/Parsed、Internal/Model-visible Result、授权前后快照、Final Draft、Validation Failure、Evidence、Citation、Token、Latency、Failure）；敏感头脱敏规则不变 |
| R6.3 MUST | Python 离线评测资产保留：scorer（`harness-score-report/v4`）、golden 判据、judge 校准不动；驱动从"进程内 Runner"改为"HTTP 调用新运行时后复评已存 run"；**不保留第二套模型编排**（harness Py evaluation 不得 import 已删除的生产 runtime，Phase 6 完成条件之一） |
| R6.4 MUST | NDJSON 缓冲与背压：事件发射顺序、终态唯一性不变；Java 只认 `result`/`error` 的透传面不破坏 |
| R6.5 MUST | 日志/度量口径迁移：现有 Java 侧观测（配额结算、回合终态统计）不因语言切换漂移；新增 Node 指标仅限进程内（内存、事件循环停顿）诊断用途 |

### R7 限额与安全（P0）

| ID | 要求 |
| --- | --- |
| R7.1 MUST | `paperloom-run-limits/v1` 三字段对等；16 Turn 上限与"submit 通过即终局"行为等价（pi 的停止语义映射）。Turn/Token 级新断路器不允许混入本次迁移（承 2026-08-17 §9 的版本纪律） |
| R7.2 MUST | 密钥处理：`model_provider_configs` 解密密钥注入方式与 Java 侧一致；密钥不出进程、不进日志、不进 EVAL dump |
| R7.3 MUST | 依赖纪律：pi lockfile pin + install-lock 摄入；新增 npm 依赖须过 install-lock 允许清单审查（对齐 pi 供应链做法）；`package-lock` 为唯一真源 |

## 6. 验收与门

### 6.1 硬门（全部 MUST，任一失败即不切换）

1. **契约门**：现有 HTTP 契约 fixture 双跑零 diff——service 请求/事件序列（`tests/test_service.py`
   及部署契约测试）与 `docs/contracts/research-harness/result-v1.json`；DIRECT/CATALOG/RESEARCH、
   追问、歧义、缺证据、开放式推荐等行为 case 全过。
2. **质量门**：PaperLoom-31 全套（L1/L2/L3）：L3 Hard 17/17；Contract Accuracy、Protocol Replay、
   Provenance 均 1.0000；零新增 scope 泄漏、伪造引用、未发布草稿、技术失败。
3. **运行时管理门**：断流取消在一个边界内生效并中断在途模型调用；`/ready` fail-closed 语义对等；
   SIGTERM 排水；并发满载长稳（现 Hikari 上限下的 N 并发回合）。
4. **裁剪门**：加载清单 diff 干净（R5.2）；触线测试 fail-closed（R5.5）。
5. **配额门**：Token 记账与 Java 结算口径无 diff（含 retry/cancel 路径）。
6. **历史门继承**（2026-08-17 §10.2 逐条适用）：含"无工具调用的响应对应一个有界技术终态，而非再跑一轮"。

### 6.2 性能对比（硬门通过后进行，不承诺改善）

同一快照、同一 Java 语料、同 provider 配置下：有代表性的 Direct/Catalog/Research/追问/长修复
case ×3 次，取中位数对比模型调用数、prompt/completion token、端到端时长；中位数回归 >10% 必须归因。
报告记录全部采样（禁"最好一次"报告口径）。纯文本违规耗时与内部续跑耗时目标为 0（R5.3 的验收即此）。

### 6.3 切换与回滚

- 单机单进程拓扑无灰度可言：切换 = Java harness 客户端指向的进程替换（端口位对调）；**禁止**
  Python 与 Node 同时对外服务。
- 回滚 = 停 Node、起 Python（Python 生产路径在 Phase 7 前一版完整保留，不做半删除）。
- 发布窗口内每项 gate 失败都可独立回滚，无需数据回滚（无库结构变更——T2 的 Java migration 是唯一
  例外，它独立于本迁移发布）。

## 7. 里程碑（每个 Phase 独立可停）

| Phase | 内容 | 完成定义（gate） |
| --- | --- | --- |
| 0 Spike（≤1 周） | pin pi 版本；SDK 走通：in-memory session + 1 个 echo 工具 + submit 终局 + MiniMax 真机两步循环 + 纯文本终态 + 断流取消；录 payload 证据 | §3.3-3/R2.3 证据齐；R5.3 的 pi 天然行为结论（含违规率观察）；API 映射表落稿；成本/内存实测；go/no-go |
| 1 骨架（无模型） | B1：HTTP 壳、认证、registry、`/health`、`/ready`、排水、断流全链路（Fake streamfn） | ops 探针测试绿 |
| 2 契约核 | B2/B4：契约、validator、run_control、run_output、memory 投影移植；fixture 重放（fake model） | 双跑零 diff |
| 3 工具与授权 | B3/B7：9 工具 + 授权状态机 + Evidence + capture（连真实 Java 实测） | 工具 fixture + 授权快照对齐 |
| 4 Provider 与修复语义 | B5/B6：DB 热配置、payload 定参、使用记账、协议违规终态/有界修复（依 Phase 0 结论） | 配置热切换 + §3.3 门 |
| 5 记忆 | R4 T0/T1 等价；T2 最小切片（若 Q-3 已定稿） | 记忆边界测试 |
| 6 配对评测与切换 | Python 对照 × Node 候选；全量门；切换决策 | §6.1/6.2 报告 + 切换演练 + 回滚演练 |
| 7 收尾 | 删 Python 生产路径；评测驱动改造收口；代码地图/README 归档文档更新；ADR-0017 | 无路径再 import 已删 runtime；文档一致 |

## 8. 风险

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| pi 升级破坏性变更（1.x 内） | 业务进程内嵌，升级失败直达故障面 | 精确 pin；升级需立 PR + 全 §6 门；不追 minor |
| MiniMax 行为漂移（pi 0.84→1.5 三个月大版本跨度） | R5.3 的"不再需要修复机制"假设失效 | Phase 0 必测违规率；不等价则按 R5.3 有界移植 |
| 契约移植差异（768 行 validator 等） | 悄悄的语义漂移 | Phase 2 双跑 fixture 门先于任何模型接入 |
| Node 22 运维面新 | 解密兼容、依赖安装、运行时行为都与 Python 不同 | 隔离 Node + 独立 unit + Phase 1 ops 门（Q-4） |
| 评测资产耦合 Python 产物 | 评测失效或被迫保留第二编排 | R6.3 收口为硬完成条件 |
| 多用户记忆隔离出错 | 跨用户信息泄漏 | R4.4 授权过滤 + user 隔离测试 + 不依赖记忆做引用 |
| pi 记忆生态 ≠ 产品需求（文件式记忆） | 生搬生态扩展导致落盘、单用户假设 | T2 明确为 Java 表 + 扩展注入，不用 `~/.pi` 目录方案 |

## 9. 开放问题

| ID | 问题 | 决策时点 |
| --- | --- | --- |
| Q-1 | pi 1.5 中 MiniMax-M3 的 thinking/tool-choice/stream 选项真实 payload（含 `streamSimple` 类陷阱是否仍存在） | Phase 0 |
| Q-2 | 修复机制选型：pi 事件拦截注入 vs 完全依赖 agent 天然行为的等价性判据（违规率、消耗、成功率） | Phase 0/4 |
| Q-3 | T2 研究记忆的产品字段与可见面（只有注入摘要？是否暴露给用户 UI？） | Phase 5 前（影响 R4.5 细化 PRD） |
| Q-4 | `model_provider_configs` 加解密的 Node 跨语言兼容（算法/参数/性能），SECRET_KEY 注入方式 | Phase 1 |
| Q-5 | Python CLI 保留范围：`agent-run` 是否换为 HTTP 驱动而保留 rescore/scorer 原地 | Phase 6 前 |
| Q-6 | pi 依赖的版本 pin 策略与升级节奏（建议：季度级 + 门禁） | 切换前 |
| Q-7 | 前端事件面板兼容：Java 透传的新事件若未在 `research-process-panel.vue` 加分支则不显示——是否需要随迁移同步审查该面板的分支覆盖 | Phase 3 |

## 10. 成功度量（切换后 1 个月 lookback）

- §6.1 全门保持绿；gold 运行月度复评无新增失败类别。
- 协议层变更不再触碰 runtime：期间所有 provider 配置/技能/工具迭代的 PR 不依赖 runtime 行为变更（评审抽查）。
- 纯文本违规 / 内部续跑耗时为 0 且无回归（R5.3 验收的持续确认）。
- 新增 provider 或模型的接入工作量从"改 model.py"降为"配置 + payload 证据"（观测项，不设 KPI）。
