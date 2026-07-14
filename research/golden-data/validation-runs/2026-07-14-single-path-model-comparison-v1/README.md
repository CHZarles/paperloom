# 2026-07-14 单路径模型对比产物

这个目录保存 MiniMax-M3 和 GPT-5.5 在同一 Golden Data、Prompt、工具和单路径 Runtime 下的
30 Case 运行产物。文件从本轮完成的临时运行目录原样复制并逐目录校验，不再依赖
`/tmp`。

## 目录

- `minimax-m3/`：MiniMax-M3 的 30 Case 产物和 `score_report.json`。
- `gpt-5.5/`：GPT-5.5 的 30 Case 产物和 `score_report.json`。
- `*-funnel.json` / `*-funnel.md`：Candidate -> Read -> Cited -> Hard Pass 漏斗。
- `audit/`：确定性 Fixture、Anchor、Semantic Audit 和 Saved Query Replay 产物。
- `gpt-5.5-agentbench-technical-rerun.log`：GPT-5.5 唯一技术超时 Case 的单独重试记录。

## 严格评分

| 模型 | Hard Pass | Candidate Recall | Candidate -> Read | Read -> Cited |
| --- | ---: | ---: | ---: | ---: |
| MiniMax-M3 | `17/30` | `41/48` | `27/41` | `25/27` |
| GPT-5.5 | `14/30` | `44/48` | `29/44` | `28/29` |

严格评分只表示指定 Contract/Anchor 覆盖，不表示人工回答准确率。盲审结果见
[`human-adjudication/2026-07-14/RESULTS.md`](../../human-adjudication/2026-07-14/RESULTS.md)。
