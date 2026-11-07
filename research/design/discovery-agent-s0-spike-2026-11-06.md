# S0 Spike 记录：Discovery Agent pi 嵌入验证

Date: 2026-11-06
验收对象：`research/design/discovery-agent-prd-2026-11-06.md` §5 S0
材料：`tmp/discovery-spike/`（spike.mjs、models.json、spike-out/{t1,t2}.json）

## 环境

- pi：`@earendil-works/pi-coding-agent@1.5.0`（npm 锁版）+ `typebox@1.3.27`；Node v22.23.1
- 模型：`MiniMax-M3.1-Flash`，`https://api.minimaxi.com/v1`（api `openai-completions`），
  models.json 覆盖 provider `minimax`，auth 用 `MINIMAX_API_KEY` 环境变量（与 harness 同款）
- 会话：`tools: ["search_arxiv_papers","submit_discovery_shortlist"]` allowlist + `customTools` + 
  `SessionManager.inMemory()` + `SettingsManager.inMemory()` + 独立 agentDir/空 cwd +
  `systemPromptOverride`（探索契约全文见 spike.mjs）

## 验证结果（两项验收全过）

| 验收项 | 证据 |
| --- | --- |
| **工具面锁定** | 两个 run 的 `session.getActiveToolNames()` 均为 `["search_arxiv_papers","submit_discovery_shortlist"]`；内置 read/bash/edit/write 与 MCP 全不在场 |
| **闭环与质量** | 两主题各 1 次提交过门、0 次重交。t1：8 检索/65 观察/5 篇（EAGLE、Medusa、KIVI、KVQuant、QuantSpec）；t2：6 检索/53 观察/5 篇（含 canonical survey《Knowledge Conflicts for LLMs: A Survey》）。理由全部为元数据引用式中文，无内容性结论 |
| **成本量级** | t1 in 79,967 / out 7,871 tok，29s；t2 in 67,040 / out 6,621 tok，23s（flash 档，单 run 分级单位） |
| **D4 关闭 compaction** | `SettingsManager.inMemory({ compaction: { enabled: false } })`（settings-schema.d.ts:88） |
| **D4 context_usage** | `session.getContextUsage(): ContextUsage | undefined`（agent-session.d.ts:898），90% 判据可直接读 |

## S1 要带走的发现

1. **id 版本后缀**：arXiv feed 返回 `2401.15077v3` 带版本号；现有 `ArxivIdentifier.normalize`
   已处理 `/abs/`、`arXiv:` 前缀与 `v\d+$` 后缀——提交门与 import 侧先 normalize 再比对/入库，复用即可。
2. **事件面 → Runner 流式**：pi 事件实测含 `tool_execution_start/end`（步骤行）、`message_end`
   （usage 结算）、`agent_settled`（收尾判据）、`turn_start/end`（轮次计数）。Runner 进度投影就吃这四样。
3. **提交门 digest**：错误回包里的 `observed_digest`（id+title）两 run 均未触发（1 次过门），
   保留为修复路径；正式版照抄 spike.mjs 的 submit 工具写法。
4. **`.env` 有 CRLF**：`source .env` 会 parse error 连坐。Runner 正式部署用环境变量直接注入
   （容器 env），不经过 source；本地脚本用 `grep | cut | tr -d '\\r'` 取值。
5. **spike 专属 10 分钟 watchdog abort**：本次花费护栏，两 run 未触发。正式版由 30 分钟
   hard-kill 护栏替代（PRD §4）。
6. 模型开场输出一句英文计划语是常态（无需干预）；工具描述里写明"英文术语效果好"检索质量已达标
   （两个主题都自发做了同义/变体/算法名多角度检索）。

## 结论

pi 宿主形态定稿：**SDK 进程内宿主**（非 CLI 子进程）。S0 无阻断项，S1（接线：Runner 服务 +
Java 两端点 + Discover 页三态）按 PRD §5 开工。

## 附：shell 禁用验证（S1 后按产品负责人要求补）

双重证据，结论：pi 会话里 shell 不是「未激活」而是「能力不存在」。

1. **源码链**（`@earendil-works/pi-coding-agent@1.5.0`）：`agent.state.tools` 只装 ACTIVE 工具
   （agent-session.js "the active tools ... are declared to the model"）；模型发明的 `bash`/`read`
   等未声明调用在 agent-loop.js `prepareToolCall` 的 `tools.find()` 落空，立即返回
   `Tool <name> not found` 错误结果给模型，不执行任何东西。allowlist（runner 的
   `tools: ["search_arxiv_papers","submit_discovery_shortlist"]`）因此同时移除了「声明面」与
   「可调用集合」，两处都不是靠配置约定。
2. **对抗实测**（`tmp/discovery-spike/test-injection.mjs`，真 MiniMax）：检索结果摘要内嵌注入文本
   （诱导 `cat /etc/passwd && env`、覆写 runner.mjs）；工具事件流里从头到尾只有两个自定义工具，
   无任何发明调用；run 正常提交短名单，且模型在理由里主动标注了注入的存在并拒绝执行。
   运行时唯一持密面（env 的 MINIMAX_API_KEY + internal token）因此没有可达路径。
