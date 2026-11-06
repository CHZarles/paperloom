# PaperLoom RAG 检索优化计划

日期：2026-09-12  
状态：Draft  
范围：本地评测与 Agent 检索链路；未改变生产配置

## 1. 结论先行

当前主要问题不是 Qdrant 不会检索，而是 **中文用户 Query 与英文论文语料不对齐**，以及附录、章节、表格问题没有先走结构导航。

同一份 PaperLoom-31 Snapshot 的差分结果：

| 指标 | Raw 中文 Query | Source-aligned 英文 Query |
| --- | ---: | ---: |
| Recall@1 | 45.16% | 74.19% |
| Recall@3 | 74.19% | 96.77% |
| Recall@5 | 74.19% | 96.77% |
| Recall@10 | 80.65% | 96.77% |
| MRR | 0.5821 | 0.8441 |
| Exact Read | 25/31 | 30/31 |

`LLM.int8()` 的 Appendix C Target 即使使用英文 Query 仍排在第 17 名，说明它是结构定位问题，不应继续靠扩大普通语义检索的 `top_k` 解决。

## 2. 优化目标

1. 提高中文用户查询英文论文时的证据可发现性。
2. 让 Appendix、Section、Table、Figure 和页码问题复用现有结构工具。
3. 不牺牲 Evidence、Source Quote、权限和 Current Reading Model 合同。
4. 保持评测能区分 Retriever、Agent Query Formulation、Exact Location 和答案级证据覆盖。

## 3. 约束与非目标

- 不把英文 Query 替换原始中文 Query 后冒充 Retriever 提升；Raw L2 永久保留。
- 不新增独立翻译模型、翻译服务或整篇论文翻译索引。
- 不先调 RRF 权重、盲目扩大 `top_k` 或引入 Reranker。
- 不修改 Golden Target、`location_ref` 或论文正文来提高分数。
- 不在生产环境验证；所有改动先在本地 Snapshot 和独立 Run 目录完成。

## 4. 分阶段计划

### Slice 0：对齐基线

先固定可比较的运行条件：

- Snapshot：`a61e8c1c240b2e8873b88d20da497b9ec0b98d9631c77a3e05c0634eeb92ecd3.json`。
- 同时记录 Raw、Source-aligned、L3 Agent 三组结果。
- 记录 Qdrant collection、contract、代码 revision 和 Provider；本地当前是 `sparse-dense-v1` Hybrid，不能直接冒充 Sparse-only 报告。
- 保存逐 Target rank、Query、paper、location、element type、page、read 和 citation 状态。

完成标准：任何后续结果都能回答“改的是 Query、结构路由、Embedding 还是索引”，且不混用历史口径。

### Slice 1：稳定 Query Formulation

修改 Agent 的系统指令和 `search_paper_content` 工具说明：

```text
英文论文使用简洁英文关键词搜索；用户使用中文时不要把整句中文直接作为 query_text。
保留论文名、模型名、数字、章节号和技术术语。
首次结果不含答案时，只允许进行一次等义 Query 改写，再决定继续搜索或放弃。
```

实现位置：

- `harness_py/orchestration/research_contract.py`
- `harness_py/corpus/tools.py`

这一步不增加翻译调用，复用现有 Agent Loop。把已经在 BERT、DistilBERT 等 Trace 中偶尔出现的行为变成明确协议。

回归 Case 至少覆盖：

- 中文问题 -> 英文论文的单篇事实查询；
- 模型名、数字、章节号保留；
- 首次 Query 无结果时只改写一次；
- Query 改写后仍必须读取并引用 Exact Evidence。

### Slice 2：结构问题先导航

在同一套 Agent 指令中增加路由规则：

```text
问题包含 Appendix、Section、Table、Figure、页码或章节号时，先调用 get_paper_structure。
拿到 section_ref 后，再用 section_refs 限定 search_paper_content，最后 read_paper_content。
```

复用现有 API，不新增后端接口：

```text
get_paper_structure
-> section_refs
-> search_paper_content(section_refs=...)
-> read_paper_content
```

新增固定回归：`target_paper_22_llm_int8`。验收目标是 Appendix C 进入目标 Section，并通过 Exact Read；不以把单字符 `C` 加回 BM25 Token 作为修复。

### Slice 3：分层评测与 Gate

每个 Slice 都运行三层：

1. **L2 Raw**：原始中文 Query，作为跨语言压力基线；不能被覆盖。
2. **L2 Source-aligned**：等义英文 Query，测 Retriever 的语言对齐能力。
3. **L3 Agent**：真实中文用户问题，测 Query Formulation、工具策略、读取、引用和答案。

固定输出：

- Recall@1/3/5/10、MRR；
- Exact Location Recall；
- Equivalent Evidence Recall（人工附加指标）；
- read/cited/answer funnel；
- model calls、Token、TTFT、完整回答延迟；
- technical error 和 abstention reason。

Gate：

- Source-aligned Recall@3 不低于当前 `96.77%`；
- `target_paper_22_llm_int8` 通过结构路由和 Exact Read；
- L3 Hard Pass 不低于同一版本基线；
- 不新增独立翻译模型调用；
- 引用必须继续来自已授权、已读取的 Source Quote；
- Raw L2 的下降或上升都单独报告，不能合并成一个总分。

## 5. 优化成功的判定条件

优化只有同时满足 **可比、有效、无回归** 三组条件，才算成功。只满足 Source-aligned 分数提升，不算产品优化成功。

### 5.1 可比性条件

- Snapshot、Golden Target、论文 PDF、用户权限、Provider、模型版本和 Qdrant contract 不变；
- Raw、Source-aligned 和 L3 使用同一代码 revision 记录，不能用不同运行口径拼接结果；
- Raw Query、`location_ref`、Expected Evidence 和评分脚本不修改；
- 生产环境没有被用于测试，结果来自本地独立 Run 目录。

### 5.2 检索与结构条件

以下条件必须全部满足：

| 条件 | 成功线 |
| --- | --- |
| Raw L2 | 保留原始结果作为基线，不用英文 Query 覆盖；Prompt 改动不应改变它 |
| Source-aligned L2 Recall@3 | `>= 96.77%` |
| Source-aligned MRR | `>= 0.8441` |
| Source-aligned Exact Read | `>= 30/31` |
| 已知 8 个跨语言差分 Case | 至少 `7/8` 进入 Top 3 |
| Appendix C Case | 必须经过 `get_paper_structure -> section_refs -> search -> read` 并 Exact Read 通过 |
| Technical errors | `0`；权限、Current Model、Source Span 和引用身份校验不能回归 |

### 5.3 Agent 与用户体验条件

- 同一批 L3 Case 的 Hard Pass 不低于对应基线：17-case 控制集不低于 `16/17`，Expanded 集不低于当前 v3 基线 `10/24`；
- 8 个跨语言差分 Case 中，目标 Evidence 的 `read`、`cited` 和最终 Claim 支持率不能低于基线，且至少有一个漏读 Case 被修复；
- Query Formulation 在现有 Agent Loop 内完成，不增加独立翻译模型调用；同一 Case 的模型调用数不高于基线；
- Citation Integrity、Citation Location Success 和 Unsupported Citation Rate 不得比基线变差；
- 完整回答 P50/P95 延迟不得比同一 Case 基线增加超过 `10%`。若样本不足以稳定计算 P95，只记录结果，不把最大值冒充 P95。

### 5.4 失败判定

出现任一情况，就判定本轮优化失败或只能算诊断实验：

- 只提高 Source-aligned 分数，真实 L3 没有改善；
- 用替换中文 Query、修改 Qrel、扩大目标位置集合或人工改 Target 取得分数提升；
- Hard Pass 提升但出现未授权读取、错误 Source Quote 或 Unsupported Citation；
- 为了提高 Recall 直接把 `top_k` 扩大而导致延迟、Token 或候选噪声超过 Gate；
- 本地使用的索引 contract 与报告口径不一致，却把结果写成产品基线。

### Slice 4：只有前面不够时才做 Retriever A/B

如果 Query Formulation 和结构路由完成后，Raw L2 仍是产品目标的主要瓶颈，再在相同 Snapshot、Sparse、RRF、Candidate Budget 和 Top-K 下只替换 Dense Embedding：

1. 当前 MiniMax `embo-01`；
2. 明确支持中英跨语言检索的 Embedding。

先看目标是否进入 Candidate Pool，再看是否排进 Top-K。只有多语言 Embedding 在 Raw、Source-aligned、L3、Latency 和 Token Cost 上都不退化，才考虑切换产品索引并重建。

## 6. 失败归因规则

| 现象 | 归因 | 下一步 |
| --- | --- | --- |
| 英文 Query 仍找不到 | Retriever / Reading Model / Qdrant | 检查索引、分词、Embedding A/B |
| 英文能找到，中文找不到 | Query Formulation / 跨语言 | 修 Agent Query 规则，保留 Raw 压测 |
| 目标在 Top 20、但不在 Top 10 | 排序或候选预算 | 先检查 Query 和结构路由，不直接扩大 Top-K |
| Appendix/Section 问题排名很深 | 结构导航缺失 | `get_paper_structure` + `section_refs` |
| Candidate 命中但 Read 失败 | Source Span / Current Model | 修 L0/L3 数据链路，不归因于 Recall |
| Read 成功但回答漏事实 | Agent 阅读或 Claim 覆盖 | 检查 read trace、Claim-to-Evidence 和提交校验 |

## 7. 回滚与发布

- 每个 Slice 单独提交，Prompt、评测和生产索引不混在一个提交中。
- 任何 L3 Hard Pass、Citation Integrity 或延迟回归，先回滚 Agent 指令 Slice，不动 Canonical Evidence。
- 多语言 Embedding 只作为离线 A/B；通过 Gate 后才新建索引合同和重建任务。
- 未完成人工复核的 Equivalent Evidence 只能作为诊断，不能改写正式 Recall。

## 8. 预期结果

短期修复后，目标不是让 Raw 中文 L2 伪装成高分，而是让真实 Agent 路径稳定复用英文 Query Formulation，并让结构问题走正确工具。长期才评估多语言 Embedding，避免用更复杂的 Retriever 掩盖 Query 和导航问题。
