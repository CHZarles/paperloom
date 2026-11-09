# Agent Gateway Walking Skeleton（2026-11-06）

pi-native-agent PRD 的第一片实现记录。PRD：`docs/engineering-evolution/agent-runtime/pi-native-agent-refactor-prd-2026-11-06.md`；
代码：`agent_gateway/`。本文只记录**实测证据**与遗留缺口，不重复设计。

## 实测结论（2026-11-06，本机，Live Java + Live MiniMax）

| 项 | 结果 |
| --- | --- |
| pi 版本 | `@earendil-works/pi-coding-agent` / `pi-ai` / `pi-agent-core` 1.5.0，`typebox` 1.3.27；Node 22.23.1 strip-types 直跑，无构建链 |
| 真机探针 | `minimax-cn` / `MiniMax-M3.1-Flash`（`.env` 的 `MINIMAX_MODEL`），两步工具循环一次通过：`echo_probe` → `submit_probe` 自然结束，**无需提醒**，3 次模型调用，1918/60 tokens，3.4 s |
| 无模型冒烟 | 提交门（接受/UNCITED_CONTENT_BLOCK/UNKNOWN_SOURCE_REF/NUMERIC_CITATION/弃答/澄清/非法 outcome）、引用首现重排渲染、`paperloom-run-limits/v1` 严格 schema、请求解析全部通过 |
| 失败路径 E2E | user 99999 + 无效 scope → 模型走 `search_paper_candidates` 两次 → 第一次 `submit_answer` 被 UNCITED_CONTENT_BLOCK 拒 → 同一 agent 循环内自修复为合法 `needs_clarification`；result/事件契约正常 |
| 语料缺口路径 | 有效 user + 无效 paper_id scope（我错传了数字主键"205"）→ Java 返回 0 候选 → 模型按协议 `abstained/NO_MATCHING_PAPER`，**未编造**。fail-closed 行为成立 |
| 真实研究回合 E2E | user 1 + 论文（Speculative Decoding，paper_id=file_md5）+ 英文机制问题 → 2 次候选检索、结构查询、6 次 `read_paper_content`、提交一次被拒（UNCITED_CONTENT_BLOCK）后修复 → `COMPLETED`，10 条 evidence ledger、**9 条引用**（section/page/span 齐全），11 次模型调用，24,373/5,144 tokens，82.3 s |
| 合规观察 | 4/4 个真机回合以合法 `submit_answer` 自然结束，**提醒钩子（AGENT_GATEWAY_REMINDER_MAX=1）零次消耗**。样本太小，只是存在性证明；PRD Phase 0 的合规率测量仍需跑 golden 样本 |
| 断流取消 | 初版用 `req.on("close")` 检测断流，**不生效**（IncomingMessage 的 close 在 body 消费后触发），在途回合持续跑到自然终局；改为 `res.on("close") + !writableEnded` 后，断流 12 s 内 in_flight 归零、进程存活 |
| 运行时管理 | `/health` in-flight 记账、SIGTERM 排水窗口已接（未做 SIGTERM 实弹演练） |

## 发现的产品事实（实现期确认，非文档臆测）

- 产品 `paper_id` 是 `file_upload.file_md5`，不是数字主键。诊断时客户端必须用 md5 作为 scope.paper_ids。
- Java 只依赖 `status/outcome/citations/usage/trace` + 9 个事件名，PRD"契约面不动、行为重建"的假设被本次 E2E 直接证实。
- pi 目录内建了 `minimax-cn` 的 `anthropic-messages`，但 `.env` 现网走的是 openai-compatible `api.minimaxi.com/v1`。本次 anthropic 端点用同一把 key 成功真机通信（与 2026-08 探针一致）；openai-compatible 端点路径在接入 DB 热配置时需要显式决策。

## 遗留缺口（下一片清单，按价值排序）

1. golden 批量合规率：L3 17 case + 行为 case 面板跑一轮，回答 PRD 的 go/no-go（提醒钩子默认值→实测值）；
   这也是 `max_agent_turns` 定值的数据来源。
2. `submit_answer` 收编后的 catalog 答案 `answer_contract` 暂推导为 DIRECT——Java 侧 answer_mode 的
   catalog 区分缺失，需在 Phase 2 设计评审（PRD Q-2）一并定。corpus 计数类答案的验证也暂弱于旧 harness。
3. EVAL capture 等价（`EVAL_DUMP_DIR` 字段清单）未实现，trace 目前只有 tool_calls+ledger。
4. provider DB 热配置（`model_provider_configs`）未接，当前 env 单一 provider；解密兼容（PRD Q-4）未做。
5. usage 口径需抽查：多数回合 prompt_tokens 与已知调用规模吻合，但失败回合出现过 879 的可疑低值；
   上配额前须与 Java 结算对账。thinking level 对 MiniMax 的实际预算效果未做 payload 级核对。
6. 裁剪为白名单式（`tools` 数组只列出 9 个自定义工具 + `noTools` 语义由 allowlist 实现），但"加载清单 diff 审计 + 触线 fail-closed"（PRD R6.1）还没有自动化门。
7. `/turn` 实现了但仅手工 curl 过失败路径；进度事件（payload/tool_completed）暂不发射。
