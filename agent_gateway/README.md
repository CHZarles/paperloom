# agent_gateway

chatbot 背后的研究运行时 walking skeleton：**pi（`@earendil-works/pi-coding-agent` 1.5.0 SDK）+ PaperLoom 定制工具层**。
对应 PRD：`docs/engineering-evolution/agent-runtime/pi-native-agent-refactor-prd-2026-11-06.md`，切片记录见
`research/design/agent-gateway-walking-skeleton-2026-11-06.md`。

harness_py 的契约面（`/v1/research/stream|turn` NDJSON、Bearer、result 字段、9 个事件名）在此等价出现；
编排核心换成每请求一个 `AgentSession`（`SessionManager.inMemory()`），工具=9 个自定义工具，无 pi 内建工具。

## 状态

Walking skeleton。已验证：真机 MiniMax 两步工具循环、端到端研究回合（真实 Java 语料、引用门、
拒绝后自修复、result 契约）、断流取消、SIGTERM 排水、无模型冒烟（提交门/引用渲染/限额 schema）。
未完成：golden 批量合规率测量、EVAL capture、provider DB 热配置、记忆 T2、裁剪清单审计自动化。

## 运行

```bash
cd agent_gateway
npm ci --ignore-scripts          # Node >= 22.19，复用仓库根 ../.env
npm run smoke                     # 无模型自检
npm run probe                     # 真机两步工具循环探针（~2k tokens）
npm start                         # 127.0.0.1:8092 起 HTTP 服务
```

环境变量全部复用 harness_py 的名字：`RESEARCH_HARNESS_INTERNAL_TOKEN`（入站 Bearer + 出站 corpus token）、
`JAVA_CORPUS_BASE_URL`、`MINIMAX_API_KEY|MINIMAX_CN_API_KEY`、`MINIMAX_MODEL`；新增
`AGENT_GATEWAY_PORT`（默认 8092）、`AGENT_GATEWAY_REMINDER_MAX`（默认 1）、`AGENT_GATEWAY_THINKING`。

Provider 固定走 pi 目录的 `minimax-cn`（`anthropic-messages`，`api.minimaxi.com/anthropic`）。
`MINIMAX_API_BASE_URL`（openai-compatible）在本骨架中被忽略：shortcut: 不支持 openai-compatible 端点，
若密钥只对该端点有效则需换 pi 自定义 provider，接入 DB 热配置时一并处理。

## 结构

```text
src/config.ts      环境与 paperloom-run-limits/v1 解析（严格 schema，字段漂移即 400）
src/corpus.ts      Java InternalCorpusController 客户端（唯一数据面；错误码→恢复类/系统类）
src/validation.ts  submit_answer 提交门：outcome/language/markdown 校验、[[source_quote_]] 引用规则、渲染
src/prompt.ts      系统提示词（自 research_contract.py 指令移植，单提交工具化）+ 回合注入
src/tools.ts       9 个自定义工具 + 授权状态（disclosed papers/locations/quotes ledger）
src/run.ts         每回合组装 AgentSession、提醒钩子（默认 1 次）、断流→abort、usage/result 投影
src/server.ts      HTTP 壳：stream/turn/health、in-flight 记账、断流取消、SIGTERM 排水
src/smoke.ts       无模型自检（submission gate、渲染、限额 schema、请求解析）
src/probe.ts       真机探针（echo+submit 两步循环，合规与 usage 观测）
```

设计不变量（与 harness_py 一致）：授权阶梯在工具层状态中单调增长；`read_paper_content` 是唯一产生
`source_quote_ref` 的入口；候选与结构 refs 不可引用；提交门校验引用覆盖；失败 fail-closed。
变更：三个 submit 收敛为单一 `submit_answer`（outcome 枚举承载 DIRECT/CATALOG/RESEARCH 的硬规则，
`answer_contract` 字段按 needs_clarification→DIRECT、有引用→RESEARCH 推导——catalog 类答案暂映射为
DIRECT，Java 侧 answer_mode 的 catalog 区分在下一片补齐）。
