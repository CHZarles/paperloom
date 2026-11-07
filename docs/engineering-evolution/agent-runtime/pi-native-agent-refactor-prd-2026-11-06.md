# Citewise Agent Runtime PRD：删掉自研 harness，chatbot 背后直接调 pi 封装的 agent

> Status：Proposed（未实施、未评审）
> Date：2026-11-06
> Scope：产品 chatbot 背后的研究运行时重构。**`harness_py` 生产编排作为自研软件删除**，
> 由"pi 封装的 agent + PaperLoom 扩展层"取代。Java / MySQL / Qdrant / 前端契约保持只读不变量。
> Supersedes：[pi-based-harness-refactor-prd-2026-11-06.md](pi-based-harness-refactor-prd-2026-11-06.md)
> （同日成立的等价迁移 PRD，被本方向取代；其 §3.1 pi 实查事实与 §3.3 已证结论继续有效，不重复）。
> Related：
> - [harness-runtime-management-prd-2026-11-05.md](harness-runtime-management-prd-2026-11-05.md)（运行时管理四件套，agent 服务照单对等）
> - [harness-reading-tools-extraction-plan-2026-08-22.md](harness-reading-tools-extraction-plan-2026-08-22.md)（Java 侧工具抽取，互补）
> - [`research/design/harness-runtime-shape.md`](../../../research/design/harness-runtime-shape.md)（行为 case 清单的来源；其"模型做语义决策、运行时只管完整性"的原则在本方向下**加强**）

评审通过后收敛为 ADR-0017（采纳 pi 为 chatbot agent 运行时底座，废弃自研 harness）。

## 0. 一句话

chatbot 背后不再有一个自研 harness：Java 之后是一个 **pi AgentSession**——PaperLoom 只提供
三样扩展（**定制工具、记忆、能力裁剪**）和一个薄的 agent gateway 服务；harness 时代的协议机制
（契约状态机、修复工具、provider 适配、SDK 会话缝合）**删除而非移植**，产品不变量
（授权阶梯、引用门、fail-closed、配额、对外契约）由工具层与最终提交门承担。

## 1. 与等价迁移 PRD 的分界（为什么取代它）

上午的 PRD 是"把 harness 逐条对等地搬到 pi 上"：保留三向提交契约、16-turn 硬顶、修复预算、
fixture 双跑 parity 门。它成立，但它 **保守了一半的判断**：harness 里最重的部分——契约状态机、
`_continue_research_turn` 修复机制、provider 私有适配、Agents SDK 会话缝合——恰恰是自研运维性
债务的主体，等价迁移只会把它们完整带进新世界。

本 PRD 接受一个更诚实的命题：**这不是迁移，是产品运行时的重新基线（re-baseline）**。
引用 `harness-runtime-shape` 的原则——"模型做语义决策，运行时只管完整性"——harness 里大量的
确定性机器（intent 契约分支、turn 计数、修复预算）本质是在防御 MiniMax 打破协议、并在 agent
循环不可靠的年代强行提供语义路由。pi 的 agent 循环 + PaperLoom 工具层可以直接持有这些职责。

保留 / 删除 / 替换边界：

| 类别 | 处置 |
| --- | --- |
| **保留（产品不变量，不做任何让步）** | 授权阶梯（scope→公开 Paper→公开 Location→读取→`source_quote_ref` 入引用集）；`read_paper_content` 是唯一可引用入口；候选≠证据；会话真相只在 Java/MySQL；fail-closed；配额结算；`/v1/research/*` 端点、9 个事件名、result 字段（`status/outcome/citations/usage/trace`）；行为 case（greeting/澄清/追问/研究/语料缺口/换题） |
| **删除（harness 机制，不移植）** | DIRECT/CATALOG/RESEARCH 契约状态机（768 行 contract/validator 的状态机部分）；`_continue_research_turn` + 3 次修复预算 + 在线诊断（ADR-0014 的运行时实现）；`agents/model.py` 762 行 provider 适配；`RequestBackedSession`/SDK 缝合；16-turn parity；Evaluator parity 门 |
| **替换（pi 原生）** | agent 循环与工具执行 → pi；会话/轨迹 → `SessionManager.inMemory()`（每请求一个）；流式事件 → pi 事件流桥接；取消 → `AbortController`；thinking/provider → `pi-ai` |
| **重构（薄壳）** | trace/eval capture、run limits、result 组装、引用渲染 → 保留语义，按 pi 事件重写 |

外部契约的可行性已核实：Java 侧消费面只有 `ResearchHarnessResultMapper`（`status` → `ProductResultStatus`、
citations、usage、trace）+ 9 个事件名 + `/health`、`/ready`。**三向契约是运行时内部结构，Java 从未感知。**

## 2. 现状与动机（简短，事实见引用文档）

自研 harness 维持着 ~6.8k 行生产 Python（core/corpus/orchestration/transport/utils）+ 213 个测试，
其中真正的产品高产出资是：授权阶梯、引用门、Corpus 工具投影、trace/capture。其余是"让一个不可靠的
agent 循环变得可靠"的防御工事。防御工事不能删除的那天，它们每一条都会随 provider 行为漂移重新收费
（ADR-0014 是这笔账的历史记录）。pi 1.5 把循环、工具执行、事件、会话、取消做成了底座职责后，
继续持有防御工事的机会成本变为纯支出。

预期（观测项，非承诺门）：生产 TS 代码收敛到 **<1.5k 行**（工具 + 门 + 记忆 + 壳），
其中 `minturns`、修复、provider 适配合计归零。

## 3. 目标架构

```text
Java（契约零改动；PythonResearchHarnessClient / ResearchHarnessResultMapper 面向 8091 拓扑位）
  ChatWebSocketHandler -> ProductReadingConversationService -> PythonResearchHarnessClient*
    *仅在最后切换时改为 AgentGatewayClient（改名不改契约；新代码可复用现 mapper/client）

  HTTP POST /v1/research/stream|/turn （Bearer, NDJSON）
   -> Agent Gateway（Node 22，接收现 8091 拓扑位）
      G1 HTTP 壳：认证、in-flight registry、/health、/ready、SIGTERM 排水、断流->AbortController
      G2 回合装配：ConversationState(T1) 装载、prompt 组装、outcome/event 映射、result-v1 输出
      G3 pi AgentSession（每请求一个，SessionManager.inMemory()，无文件、无 compaction）
          - X1 定制工具扩展（语料 5 + skill + 1 提交 + 记忆 2 = 9 个）
          - X2 记忆扩展（T1 注入、T2 存取）
          - X3 capture 扩展（trace/eval dump/usage）
          - 裁剪配置（noTools：pi 编码工具全禁；无 MCP/无 pi skills/无 session 文件）
      G4 Corpus adapter（fetch -> InternalCorpusController，唯一数据面）
      提交门：validator 语义保留（引用覆盖、known source_quote_ref、outcome 枚举），
             一次轻量拒绝 -> 事件返回同 agent 继续修复（去修复预算，见 R3.5）
```

```text
代码只有三类：product policy（工具语义 + 提交门 + 记忆规则）、adapter（G1/G2/G4/X3）、
pi 配置。没有第四类"框架/循环/协议机"。
```

## 4. 需求详述

优先级：P0 = 阻塞发布；P1 = 发布窗口内；P2 = 立项不实施。

### R1 服务壳（P0）

| ID | 要求 |
| --- | --- |
| R1.1 MUST | 端点、鉴权、NDJSON 事件名（`job_started/payload/answer_completed/job_completed/job_failed/job_cancelled/run_limited/result/error`）、result 字段与现状逐字节兼容；`status` 枚举（COMPLETED/NEEDS_CLARIFICATION/INCOMPLETE_PRECISE/…）与 `outcome` 枚举（answered/needs_clarification/partial/abstained）维持现映射 |
| R1.2 MUST | 运行时管理四件套对等（2026-11-05 PRD 原文适用）：断流取消贯穿到在途模型调用与 corpus 请求、进程内 registry、`/ready`（provider_unconfigured 等 fail-closed，不同步穿透）、SIGTERM 排水 |
| R1.3 MUST | 单进程异步 N 回合；部署用隔离 Node 22（pin）；quota、并发上限仍由 Java 约束，不新增并发参数面 |

### R2 Provider（P0)

继承等价迁移 PRD §3.3 的全部结论与 R2 需求原文（三来源配置、DB 热配置下一回合生效、
payload 证据定参、usage 进 result）；本 PRD 无新增，只有一条强化：**修复路径不存在后，
纯文本无工具调用 = pi 的自然 agent 终止**，此时必须是"无提交的未完成回合"，
走 `job_failed`（PROVIDER_TOOL_PROTOCOL_VIOLATION 关系成立才用；若 pi 循环下表现不同，P0 重定义该映射），
不发布、不合成继续。

### R3 Agent 定义与提交门（P0）——新运行时的核心行为需求

| ID | 要求 |
| --- | --- |
| R3.1 MUST | Agent = pi 系统提示词 + X1 工具。系统提示词从现 Research instructions 移植（文本资产，非机制）；仿真人语气与"可自然提问一次澄清"的行为 case 不变 |
| R3.2 MUST | **单一提交工具** `submit_answer`（合并三个 submit）：参数含 `markdown`、`citations [[source_quote_ref]]`、`outcome`（answered/needs_clarification/partial/abstained）、可选 `fields`；validator 语义=现 `submit_research_answer` 门槛（引用覆盖、known quote、无手写数字引用）+ 现三契约的硬规则收编为校验分支（如"无引用的正文内容"按现规则拒绝或强制 partial/abstained，细则以现 validator 行为为准移植，不新造） |
| R3.3 MUST | 删除三向契约状态机：路由决策（直答/列表/研究）由 agent + 工具 + 提交校验承担；**fail-closed 不变量保留**——语料中歧义论文身份不许被当唯一目标（承 ADR-0009），语义未定不得静默进入论文 QA（CLAUDE.md 不变量 3 的表述为行为 case 验收而非状态机实现） |
| R3.4 MUST | 关闭 pi 的一切"自己决定工具集"机制（动态加载、tool_search），工具面在回合开始时固定为 X1 注册清单 |
| R3.5 MUST | 提交校验拒绝返回有界反馈进入同一 agent 继续修复；**删除 3 次预算与 `_continue_research_turn` 机制**；防护改为 run limits（R7）+ 无进展终止：同一校验错误连续重复 N 次（配置，默认 3）= `run_limited` 技术终态。修复次数不进模型可见提示词，不合成模型可见工具 |
| R3.6 P1 | `get_research_skill` 22 个技能数据保留、以工具形态加载；技能是否改写为 pi 原生 skill 体系是后置优化（Q-5），本 PRD 不动 |

### R4 工具层（P0）

| ID | 要求 |
| --- | --- |
| R4.1 MUST | Toolset：`search_paper_candidates`、`find_papers_by_identity`、`search_paper_content`、`get_paper_structure`、`read_paper_content`、`get_research_skill`、`submit_answer`（P1 后加 `save_research_note`、`recall_research_notes`）。名称/参数/投影与现模型可见语义等价（TypeBox 表达现 JSON schema） |
| R4.2 MUST | 授权阶梯与四个集合（disclosed papers/locations/quotes/引用集）作为 **gateway 的 per-run 状态**由工具层维护；`read_paper_content` 仍是唯一产生 `source_quote_ref` 的入口；capture 保留工具前后授权快照 |
| R4.3 MUST | `READING_ARGUMENTS_INVALID`/`PAPER_NOT_AVAILABLE` → 可恢复工具结果；`READING_MODEL_NOT_READY`/`RETRIEVAL_UNAVAILABLE` → 技术失败终态；错误码与映射不变 |
| R4.4 MUST | 数据面独占走 G4 → `InternalCorpusController`；Node 不接 MySQL/Qdrant、不扩 scope、无第二数据面 |
| R4.5 MUST | 工具顺序执行（授权状态随结果单调增长）；`submit_answer` 独占最终 tool step，同响应兄弟工具调用按现规则拒绝 |

### R5 记忆对接（P0 等价 + P1 产品最小切片）

pi 核心无内建记忆（§引用 pi 实查）；生态的文件式记忆不适用多用户服务。三层：

| 层 | 内容 | 载体 | 权威 |
| --- | --- | --- | --- |
| T0 回合内轨迹 | 本回合消息/工具轨迹 | pi `SessionManager.inMemory()`（每请求新建，丢弃） | 无 |
| T1 跨回合 ConversationState | 历史文本、已选论文、已接受 Evidence | Java 装载 → G2 注入（bounded；已接受 Evidence 为受信上下文） | Java/MySQL 唯一 |
| T2 跨会话研究记忆（P1 新能力） | 用户研究笔记/偏好/跨会话结论 | X2 扩展：`save/recall_research_notes` 工具 + 每回合 bounded 注入；存储=Java 新表（`user_id` 隔离） | Java/MySQL |

| ID | 要求 |
| --- | --- |
| R5.1 MUST | T1 语义与现 `ResearchMemory`/`ConversationState` 投影等价；`/clear`、`/new` 行为不变 |
| R5.2 MUST | T2 注入前做授权过滤；记忆内容**不得**成为 `source_quote_ref`、不进引用门；引用门对记忆无感知 |
| R5.3 P1 | T2 最小切片：新表（独立 Java migration）+ 2 个工具 + bounded 注入；schema 产品定稿走 Q-3 |
| R5.4 MUST NOT | pi session 文件落盘、任何第二会话真相、记忆写回语料、以记忆替代证据 |

### R6 能力裁剪（P0）

等价迁移 PRD R5 原文全部适用（noTools=编码工具全禁、无 MCP/codemode/tool_search/pi skills、
无 steering/compaction/多 agent/TUI、session 不落盘、裁剪清单审计 + 触线 fail-closed）。
本 PRD 增补一条：

| ID | 要求 |
| --- | --- |
| R6.1 MUST | 裁剪审计作为发布门之一：生成产物导出"已加载扩展/工具/资源"清单与白名单 diff，超集即发布失败 |
| R6.2 MUST NOT | 不因"agent 更自主"重新解释授权模型：工具数量不增加新能力入口（无 web、无 MCP、无文件下载）——Deferred 产品列表（Field Extraction、公开 OAuth、citation graph、多模态、web 搜索）不解禁 |

### R7 限额与预算（P0）

| ID | 要求 |
| --- | --- |
| R7.1 MUST | 三个现有限额原样（`max_wall_clock_ms`、`max_model_visible_tool_chars`、`max_history_chars`）；**turn 上限从 parity 16 改为配置项** `max_agent_turns`，初值由 P5 金基线实测确定（起步值=16），语义为墙钟/预算同类的运维控制，非协议机制 |
| R7.2 MUST | 无进展终止（R3.5）、配额油尽、deadline、取消 → 有界技术终态 + 现事件名映射（`run_limited`/`job_cancelled`/`job_failed`） |

### R8 观测与评测（P0）

| ID | 要求 |
| --- | --- |
| R8.1 MUST | trace 词汇不变（契约迁移、授权事实、验证失败、Quote 身份显式记录）；事件桥接表在仓内维护 |
| R8.2 MUST | `EVAL_DUMP_DIR` 等价 capture 字段清单对齐现 README 枚举；脱敏规则不变 |
| R8.3 MUST | 评测资产离线域保留（scorer v4、golden、judge 校准不动）；驱动 = HTTP 调用新服务后复评；无第二套模型编排（这是收尾完成条件） |

## 5. 验收：从 parity 门改为 acceptance 门

**前提共识：发布即行为变化**（语气、澄清节奏、工具节奏、修复路径都可能被用户感知）。
因此门重新定义为产品质量验收，而不再要求与旧行为逐例相同：

1. **质量门（硬）**：PaperLoom-31 产出质量不退化——L3 Hard ≥ 17/17 基线、Contract Accuracy（引用合法）
   与 Provenance 1.0、零 scope 泄漏/伪造引用/未发布草稿；
2. **行为门（硬）**：行为 case 面板（greeting、澄清、追问、有据研究、语料缺口、换题）通过
   人工评审 + 判例留档；fail-closed case（歧义身份、未授权引用）全绿；
3. **裁剪门（硬）**：R6.1 审计干净；触线 fail-closed；
4. **性能（软观察）**：记录新基线全量采样（模型调用数/token/端到端中位数），与旧基线 delta 超 10%
   必须归因但**不阻塞**（行为重建后旧数字只是参照；修复耗时与内部续跑耗时应为 0 或接近 0）；
5. **运维门（硬）**：四件套 + 并发满载长稳 + 配额结算口径零 diff。

**回归纪律**：任何质量门单例失败要么修复要么回滚，不许通过"重新解释 golden"吸收回归
（golden 数据本身如需演进，走独立的 golden 变更流程，与本次迁移分离）。

## 6. 切换与回滚

- 开发期生产零变化；单机无灰度——切换即 8091 拓扑位进程替换（Java client 改指 Agent Gateway，甚至可不改类名只改实现）。
- Python harness 生产路径在切换发布前完整保留一个版本窗口；回滚 = 停 Node 起 Python（分钟级）。
- T2 的 Java migration 独立于本迁移发布与回滚。
- **不做双进程同抢**；不做行为侧 A/B（单机单进程模型下不存在该形态）。

## 7. 里程碑

| Phase | 内容 | 完成定义 |
| --- | --- | --- |
| 0 Spike（≤1 周） | pi 1.5 SDK + MiniMax-M3 真机：echo 工具 + `submit_answer` 两步 + 纯文本自然终止行为 + 断流取消 + payload 证据；22 技能注入跑 3 个行为 case 草测 | payload 证据齐；"纯文本现象在 pi 下的真实表现"结论落档；go/no-go |
| 1 Gateway 骨架 | G1/G2（无模型）：同端点/事件/result、四件套 | ops 探针绿 |
| 2 Agent 核心 | G3/X1/G4 + R3 提交门 + T1 记忆注入（fake provider → 真 provider） | 行为 case（dev 语料）+ 质量门样例通过 |
| 3 全工具与 capture | X3、错误映射、capture、R7 预算 | Java 实测；capture 字段对齐 |
| 4 记忆 T2 | Java 表 + 工具 + 注入（P1） | 授权过滤测试 |
| 5 金基线 | PaperLoom-31 + 行为面板 + `max_agent_turns` 定值 + 性能采样 | §5 门全绿 + 切换决策 |
| 6 切换 | 端口位替换 + 观察窗口 | 生产绿一个观察期 |
| 7 收尾 | 删 `harness_py` 生产路径（contract 状态机、修复、model.py、SDK 缝合、16-turn 机器）；评测驱动收口；文档/代码地图；ADR-0017 | 无生产路径 import 已删 runtime |

## 8. 风险（诚实版）

| 风险 | 应对 |
| --- | --- |
| **行为回归被"重建基线"话术吸收**——这是本 PRD 最大的道德风险 | §5 硬门 + 回归纪律 + golden 变更独立流程；质量门不是软指标 |
| Agent 自主性放大工具调用次数 → 配额/时延敏感 | R7 预算 + P5 全量性能采样 + `max_agent_turns` 配置 |
| 提交门简化引入越权引用路径 | R4.2 授权状态照单保留 + capture 快照 + 专项 case |
| pi 版本节奏（三个月一个大版本） | 精确 pin + 升级门 + pi 类型不出契约 |
| 删机制后 MiniMax 协议违规回潮 | Phase 0 必测；R3.5 无进展终止兜底 |
| 16-turn 变预算导致失控回合 | 初值=16 起步 + run_limited 长稳测试 |
| 多用户记忆泄漏 | T2 user 隔离 + 授权过滤测试 + 注入无引用能力 |

## 9. 开放问题

| ID | 问题 | 决策时点 |
| --- | --- | --- |
| Q-1 | 纯文本无工具在 pi 1.5 的真实表现（是自然终局还是需要 R3.5 映射微调） | Phase 0 |
| Q-2 | 单一 `submit_answer` 的校验细则收编：现三契约里哪些规则升级为硬校验、哪些交给提示词 | Phase 2 设计评审 |
| Q-3 | T2 记忆 schema 与用户可见面（是否做 UI 入口，还是先纯注入） | Phase 4 前 |
| Q-4 | `model_provider_configs` 解密 Node 兼容（承等价迁移 PRD Q-4） | Phase 1 |
| Q-5 | 22 技能是否后置迁移到 pi 原生 skills/上下文文件体系 | 切换后独立立项 |
| Q-6 | token 级输出流（pi 下几乎免费，但属产品体验扩张）——明确不在本 PRD，切换后单独立项 | — |
| Q-7 | `max_agent_turns` 初值与每个 case 类型的实测分布 | Phase 5 |

## 10. 成功度量（切换后 1 个月）

- §5 五门保持绿；新增 provider/模型接入的工程量从"改 provider 适配 + 修复机制回归"降为"配置 + payload 证据"。
- 生产 TS 代码量落档（目标 <1.5k 行，观测项）；`_continue_research_turn`/诊断/16-turn 契约行为零回归报告。
- 违规修复耗时 ≈ 0；无一个因为"agent 自由度"新增的授权事故。
- 用户可感知差异（语气/澄清节奏）在观察期内无投诉且回合满意度不降。
