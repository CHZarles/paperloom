# PaperLoom Handoff

更新日期：2026-08-09

## 当前状态

- 当前分支：`main`
- 最近提交：`1f9cb0017a725db13efbe18cbcb9ef1670a60cd4`
  - `feat(research): govern run limits, harden MinerU parsing, and enforce source-span stability`
- 当前工作区有大量未提交修改。它们包含 Dense/Hybrid 检索、Embedding 配额、Benchmark 和相关测试；不要在未确认前回退或清理。
- 本轮新增前端邀请码可见性修复：邀请码单元格不再截断；创建单条自动邀请码后，成功提示直接显示生成的代码；设置弹窗内移除会把 NDataTable body 算成 0 高度的 `flex-height/h-full` 组合。
- 本地内测默认初始额度已调整为 LLM/Embedding 各 `10,000,000`；现有用户余额也已补到至少该数值。
- 当前目标是本地内测，不做线上 Migration。
- 已完成一次真实普通用户 Smoke：邀请码注册后上传一篇新的私有 PDF，MinerU 解析、视觉资产生成和混合索引均完成（70 个 Location）；限定该论文的中文问答返回 3 条逐段引用。刷新后的历史对话仍保留答案和引用映射，`reference-detail` 能重开带 `sourceQuoteRef` 的 PDF Evidence。
- 该 Smoke 用户的 LLM/Embedding 用量都从 0 增长；将其 LLM 余额临时设为 0 后，聊天在启动后返回 `429`，且没有研究进度或 Embedding 消耗。测试结束后已恢复该测试用户余额。

## 已完成的产品链路

### 论文发现与正文证据

- `year_from/year_to=0` 视为未提供；论文发现为空时会用纯标题重试。
- Read Payload 对模型隐藏 Source Span、BBox、Parser 等内部字段。
- Read 超限时返回 `omitted_location_refs`，不再静默丢失。
- 使用正文工具后，`answered/partial` 必须有逐块引用；最终答案拒绝 `<think>`。
- Evidence Read 使用本 Run 缓存的 Qdrant 正文和 Source Span，并校验 Current Model、内容 Hash、Source Span；PAGE/SECTION 仍从 MySQL 读取。

### Hybrid Qdrant

- 已启用 `sparse-dense-v1`。
- 本地配置：

  ```text
  QDRANT_CONTRACT=sparse-dense-v1
  QDRANT_COLLECTION=paperloom_reading_locations_hybrid_v1
  ```

- Dense 模型：MiniMax `embo-01`，1536 维；文档使用 `type=db`，查询使用 `type=query`。
- Hybrid 采用 Sparse BM25 + Dense + 加权 RRF，Sparse 权重为 `3.0`。
- 全量重建已完成：34 个 Current Model 为 `READY`，Qdrant 状态为 `green`，共 2,911 points。
- 查询 Embedding 已接入 `UsageQuotaService`；无 Embedding 额度时不会绕过额度继续 Dense 检索。

## Benchmark 结果

- 数据集：`paperloom-31-v1`，31 篇未经业务预处理的 PDF。
- Snapshot：[`0f3a3ba6962f5a48f5830064e5a7ae50a1b5044c19c614b1008a9575cb87eb95.json`](research/benchmark/local/snapshots/0f3a3ba6962f5a48f5830064e5a7ae50a1b5044c19c614b1008a9575cb87eb95.json)
- Run：[`20260809T100552Z-b258e681/run.json`](research/benchmark/local/runs/20260809T100552Z-b258e681/run.json)

| 层级 | 结果 | 含义 |
| --- | --- | --- |
| L1 Paper Discovery | Recall@1/3/5 = `1.0` | 论文发现通过 |
| L2 Evidence Retrieval | Recall@1/3/5/10 = `0.3871/0.5806/0.7097/0.7419`，MRR `0.5000` | 23/31 个冻结目标 Location 进入 Top 10 |
| L3 Exact Read | `12/12` hard pass | 正文读取、Source Quote 和 Evidence 闭环通过 |
| Internal Beta Gate | 通过 | 当前本地内测门槛通过 |

L2 miss 是检索层问题：Benchmark 直接使用固定 Query 调用 `search_paper_content`，不运行 Agent。当前 8/31 个目标未进入 Qdrant Top 10；不能把它们归咎于 Agent。

- PASSAGE Recall@10：`18/22`
- TABLE Recall@10：`4/6`
- FIGURE Recall@10：`1/3`

如果目标已在 Top-K，但 Agent 没搜索、没读或没引用，才归因于 Agent 层。

最终 Run 有一个离线 Judge 技术噪声：`single_03` 没有调用 `submit_agent_judgment`。它已结构化记录，不影响产品 Gate；前一次同 Hybrid 配置的 Run 中 Judge 全部通过。

## 运行环境

- Backend：`http://127.0.0.1:8081`
- Harness：`http://127.0.0.1:8091/health`
- Benchmark 普通用户：`id=2`，用户名 `paperloom-benchmark`，密码未知；产品 Smoke 可直接注册新的普通用户。
- 管理员账号不能验证普通用户权限和 Embedding 配额行为，因此真实验收优先使用普通账号。

## 下一步

本地内测产品 Gate 已完成，当前只需冻结并提交 release snapshot。实际部署到邀请制内测环境时：通过服务器 Secret 提供 MinerU、MiniMax、JWT、Qdrant 和内部 Harness 凭据；使用 Redis Streams worker；再复跑同一普通用户 Smoke。

L2 剩余 miss 暂作为后续检索质量优化，不阻塞当前内测；不增加复杂 Token 预检或单轮上限。

## 已执行验证

- 14 个针对性 Java 测试通过。
- 47 个 Python 测试通过。
- 前端 `pnpm typecheck` 与生产构建通过；Chat shell 为 `511.1 KB / 520 KB`。
- 普通用户端到端 Smoke 通过；MinerU 配置缺失时上传会明确进入 `FAILED` 并给出原因，注入有效 Secret 后可由重试接口恢复至 `COMPLETED`。
- 未执行 Migration；当前没有线上环境，属预期行为。
