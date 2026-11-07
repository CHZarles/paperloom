# Frozen Validation Runs

已冻结的离线 Golden Run，供 replay、rescore 与审计复用。写入规则见
[上级 README](../README.md)：本地输出先进 `local-runs/`，人工审核后才有资格进入本目录。

保留的 Run 与其证据用途：

| Run | 用途 |
| --- | --- |
| `2026-07-13-llm-agent-evaluation-v1/` | `human-labels-llm-agent-evaluation*.yaml` 校准基线；LLM Agent 评测最初的冻结输入 |
| `2026-07-14-single-path-model-comparison-v1/` | `human-labels-claim-judge-holdout*` 与 `docs/evaluation/golden-data-validity-and-model-bottleneck-audit-2026-07-14.md` 的对照 Run |
| `2026-07-14-minimax-tool-argument-repair-v1/` | 工具参数修复行为对照；`human-adjudication/2026-07-14/` 人工复核引用 |

历史清理：2026-07 的 chat 抓包、evidence-bounded/skill-react 基线、corpus-metadata 探针
等 pre-v4、无引用的 Run 已于 2026-11-07 删除；删除前均为 v2/v3 评分口径且不被任何
标签、文档或测试引用。
