# 探索论文智能体（Discovery Agent）PRD — 落地版 v3（聊天单例形态）

Status: Draft v3（聊天单例 + 记忆系统重构；未实施，先评审）
Related: `docs/adr/0016`、`research/design/discovery-agent-s0-spike-2026-11-06.md`（S0 记录与安全验证）
Replaces: 同日 v2（表单三态形态，未上线即弃，见 §8）

## 0. 一句话

「探索论文」页只有一个聊天助理：多轮对话里做**发现论文、感知库内论文、帮入库**三件事
（不变）；它没有多 session，是每用户一个的**有记忆单例**——长期记忆用户可编辑（角色定位、
偏好、沉淀的洞察），短期记忆是滑动窗口可一键清除；支持 `/+指令`（清上下文、沉淀到长期记忆等）。

## 1. 决策

| # | 决策 | 状态 |
| --- | --- | --- |
| D1 | 智能体 = pi 官方 SDK（宿主模式，工具面 allowlist 锁死） | 已实现已验证（S0） |
| D2 | 入库 = 显式确认的批量勾选 | v3 变形：确认 = 用户在聊天中对可见候选的明确入库指令/点选，逐篇走现有 `importPaper` |
| D3 | 无行为预算（turn/检索次数不设终止上限，只记录） | 延续，粒度从 run 改为 turn |
| D4 | 无 compaction、上下文只涨不折叠；90% 窗口提示 | v3 新解：聊天窗口为**硬截断滑动窗**（不摘要不折叠，太老整段不带）+/clear；90% 提示保留 |
| D5 | **聊天单例 + 记忆系统 + 斜杠指令**（本版新增） | 待确认 |
| D6 | 入库边界 ADR 0017（修订 ADR 0016 逐篇点选表述） | S2 补 |

## 2. 形态：单例聊天助理（UX）

- 每用户只有一个 thread（无 session 概念）；页面 = 消息流 + 底部输入框 + 记忆抽屉。
- 消息类型：用户文本、助理文本（markdown）、**候选卡片组**（检索结果的可视化：标题/年份/
  库内状态/摘要首行，从工具事件渲染而不是让模型复述）、**入库结果卡片**（逐篇
  IMPORTED/PROCESSING/READY/FAILED）、系统行（/clear、/沉淀 的反馈）。
- 状态行常驻：上下文窗口用量百分比、Token 计数、快捷按钮 `/clear`、`/沉淀`、记忆面板入口。
- 关键词检索模式的去留见 §10 开放问题（默认保留为副入口）。

## 3. 记忆系统（专门设计）

### 3.1 三层职责

```text
长期记忆 (discovery_memory)     用户主权，可编辑、显式沉淀；每轮渲染进 system prompt
短期记忆 (discovery_chat_message) 滑动窗口：只带最近 W 条/cutoff 之后的消息，硬截断、不摘要
工作集 (cards 候选池)            结构化事实：本窗口内真实检索见过的候选 = 入库门的事实源
```

- **长期记忆**：`user_id + kind + content + source + active`；kind 三类：
  `ROLE`（角色定位/口吻，如「简洁，先给结论」「我是做推理加速的」）、
  `PREF`（研究偏好：领域、类目、年份习惯、排除项）、
  `INSIGHT`(沉淀出的洞察：「用户在写 speculative decoding 综述」「上次排除了 vLLM 工程类论文」)。
  source：`USER`（面板手写）/`DISTILLED`（/沉淀 生成）。
- **组装规则（每轮固定顺序）**：契约 system prompt → ROLE 条目 → PREF 条目 → INSIGHT 条目
  （按新到旧）→ 短期窗口。长期记忆块上限 4000 字符，超限丢最旧 INSIGHT 并在状态行提示。
- **短期窗口**：cutoff 之后（见 /clear）最近 N=40 条且 ≤24k 字符；本身**不做摘要、不做折叠**
  （D4 一致），旧了整段不带。窗口外的候选卡片仍显示在 UI（只读历史）但不再进入模型上下文，
  也不再是入库门的合法来源。
- **单例实现**：thread = 数据库行序（无独立会话表）；runner 完全无状态，每 turn 由 Java 重建
  in-memory pi 会话并注入历史（SDK 支持外部历史注入）。记忆的持久性在产品侧数据库，runner
  崩溃/重启不丢任何记忆。

### 3.2 记忆写入的唯一通道约束

- 模型普通轮**没有**写记忆的工具；只有 `/沉淀` 轮才注入 `save_memory` 工具（见 §5）——长期记忆
  只有两个来源：用户在面板手写、用户显式 `/沉淀`。防止记忆被悄悄污染。
- `/沉淀` 行为：模型读当前短期窗口，产出 1–N 条 INSIGHT 候选 → 直接入库（source=DISTILLED）→
  系统行反馈新增了哪些 → 建议用户 `/clear`（沉淀**不**自动清窗，清不清由用户决定）。

### 3.3 记忆面板（UI）

抽屉页：三组分类列表（ROLE/PREF/INSIGHT），增删改；显示 source（手写/沉淀）与时间。
角色定位另有一个常置文本框（快捷编辑 ROLE）。全部即时生效（下一轮组装即用新记忆）。

## 4. 斜杠指令（v1 全集，Java 侧注册表）

| 指令 | 处理层 | 行为 |
| --- | --- | --- |
| `/clear` | Java，不发模型 | cutoff 递增：短期窗口清零（含候选池），模型下轮起只见新窗口；UI 历史保留只读；系统行反馈 |
| `/沉淀` | 模型轮（kind=DISTILL，工具面仅 save_memory） | 提炼当前窗口 → 写长期记忆 → 反馈 + 建议 /clear |
| `/状态` | Java | 系统行：窗口条数/百分比、长期记忆条数、今日 turn 与 token 用量 |
| `/记忆` | UI | 打开记忆面板 |

指令冲突的处理：指令必须在消息开头，其余文本回普通消息。非法指令按普通文本发给模型。

## 5. 工具面（对模型）

| 工具 | 轮限定 | 说明 |
| --- | --- | --- |
| `search_arxiv_papers` | 普通轮 | 同 S1；年份过滤回到模型可传（year_from/year_to），Java 内部端点过滤（聊天无 run 级约束，模型级过滤正合适） |
| `import_arxiv_papers` | 普通轮 | 入库门：ids 必须 ∈ 当前窗口候选池（防编造、跨 /clear 隔离）；Java 逐篇 importPaper，逐篇回执 |
| `save_memory` | 仅 /沉淀 轮 | items: kind=INSIGHT + content（≤300 字/条），Java 校验落库 |

用户显式确认（D2 变形）：用户消息中「入库第 1、3 篇」「这些都要」或卡片点选续发同一语义消息，
即构成对可见清单的显式确认；工具只是执行它。

## 6. 系统形状与端点

```text
Discover 页 chatbot ⟷ POST/GET /api/v1/discovery/agent/chat(/messages|/clear|/memory)
     Java DiscoveryChatService（预算、轮状态机、窗口组装、三层门）
       ⟷ runner: POST /v1/discovery/turns {turnId,userId,kind,history[],memory[],lastMessage}
           每轮重建 in-memory pi 会话（延续：noTools allowlist、30min watchdog、usage 上报）
       ⟷ 内部检索端点 /internal/v1/discovery/search（不变）
     入库：confirm 即上一节 import 工具（现有 importPaper 与每小时预算不动）
```

- 表：`discovery_chat_message`（user_id、seq、cutoff_seq、role、content、cards JSON、
  tool_events、usage、turn_status、created_at）、`discovery_memory`（§3.1）。
- 轮状态机沿用 S1 词汇：RUNNING → COMPLETED / CANCELLED / FAILED（reason_code），快照轮询；
  单用户并发 1 turn。
- 预算：`paper.discovery.agent.turns-per-hour`（默认 30，Redis 同模式）；入库预算照旧。
  D3/D4 延续：turn 内不设工具次数上限；90% 窗口提示保留；每 turn 30 分钟进程卫生兜底。
- 功能开关 `paper.discovery.agent.enabled` 从 S2 提前到本次实现（聊天是常驻入口，必须可一键关）。

## 7. 对 S1 代码的处置（未上线即重写，不留兼容包袱）

| S1 资产 | 处置 |
| --- | --- |
| runner：pi 宿主、安全 allowlist、selftest、30min watchdog | 保留，`/v1/discovery/runs` → `/v1/discovery/turns`，支持历史注入与 DISTILL 轮 |
| Java：内部检索端点、importPaper 编排、Redis 预算模式 | 保留 |
| `discovery_run` 表 + 4 个 run 端点 | 删除（migration v2：建 chat/memory 表，run 表不用） |
| 前端三态 `agent-console.vue` | 重写为 chat 组件（消息流+候选卡片+指令+记忆抽屉） |
| 安全验证（shell 禁用 + 注入对抗） | 全部延续，new chat 面不新增风险 |

## 8. 落地步骤

| 步 | 内容 | 验收 |
| --- | --- | --- |
| C1 记忆与聊天后端 | 两张表 migration、chat/memory 端点、窗口组装、turn 状态机、runner turns 协议改造 | 桩 e2e：两轮对话跨 turn 记忆/候选池生效；/clear 后池清空；沉淀入库可查 |
| C2 前端 chatbot | 消息流、卡片渲染、指令补全、记忆抽屉、状态行 | 手动验收四条指令 + 卡片入库 + 面板编辑即时生效 |
| C3 收尾 | 开关、ADR 0017、turns-per-hour 调参、README 部署段 | S2 原验收清单顺延 |

## 9. 风险（新增两条，其余延续 v2）

1. **友好度 vs 费用**：聊天把一次探索拆成多 turn，总 token 成本比三态表单高；缓解：候选卡片
   结构化渲染（模型不复述全量元数据）、turn 预算、每轮显示用量。
2. **窗口硬截断丢语义**：话题很长时旧指令被整段丢掉；缓解：90% 提示 + /沉淀 把关键固定进
   长期记忆；不做自动摘要（D4 一致）。
3. 延续：短名单质量上限由元数据决定；MiniMax 长循环任性 → 实时计数可取消；每 turn 30 分钟兜底。

## 10. 开放问题

1. **关键词检索模式去留**：v3 页面主体是 chatbot；关键词检索（现有 WIP 功能）保留为副入口
   还是删除？（建议保留——它是零成本入口，与聊天不冲突；删除请明示）
2. `/沉淀` 是否连带 `/clear`（当前设计：不连带，只建议）。
3. turn 流式 vs 快照轮询（当前设计：快照轮询，同 chat 前例；流式后续可加）。

## 11. 决策补遗

- **2026-11-07 · harness_py 迁移定案：不迁。** 主 Chat 证据研究继续由 harness_py
  （OpenAI Agents SDK）承载；pi runner 只服务探索聊天。理由：工具面移植仅占成本 <15%，
  本体是授权阶梯/提交契约/修复循环/跨轮证据记忆（ADR 0011-0015 的资产）；换 runtime 无
  用户收益，反而使 golden 行为基线作废。双轨分工固化：证据研究归 harness_py，元数据探索
 归 runner。若未来探索面被要求带证据，正确方向是把证据门下沉给 runner，而非把 harness
  搬上 pi。
- **2026-11-07 · provider 头注入（本地开发灰活）**：OpenCode Go 端点要求
  `x-opencode-session` + 非库客户端 UA（Cloudflare 1010 / MissingSessionID 实证）。
  harness_py 的模型适配层与 runner 的 models.json（pi 原生 headers 配置）按「仅 opencode
  go URL」注入。该端点不在长期供应商清单；换正式供应商后注入自动失效（空操作）。
