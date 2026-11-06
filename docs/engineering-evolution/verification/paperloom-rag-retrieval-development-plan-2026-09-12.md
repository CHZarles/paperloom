# PaperLoom RAG 检索优化开发计划

日期：2026-09-12  
状态：Draft  
关联：[RAG 检索优化计划](./paperloom-rag-retrieval-optimization-plan-2026-09-12.md)

## 1. 开发目标

在不更换 Retriever、不新增翻译服务、不改变 Canonical Evidence 的前提下，先修复真实产品链路中的两个问题：

1. 中文用户问题没有稳定转换成英文论文可检索的 Query；
2. Appendix、Section、Table、Figure 类问题没有先做结构定位。

成功不是单纯提高一个离线 MRR，而是让真实 Agent 路径的 `search -> read -> cite -> answer` 漏斗改善，同时保持权限、引用和延迟合同不回归。

## 2. 现状基线

固定使用同一份 PaperLoom-31 Snapshot：

```text
research/benchmark/local/snapshots/a61e8c1c240b2e8873b88d20da497b9ec0b98d9631c77a3e05c0634eeb92ecd3.json
```

当前诊断基线：

| 层 | 指标 |
| --- | ---: |
| Raw L2 中文 Query | Recall@5 `74.19%`，Recall@10 `80.65%`，MRR `0.5821` |
| Source-aligned L2 英文 Query | Recall@3 `96.77%`，MRR `0.8441`，Exact Read `30/31` |
| 真实 Agent | 同一问题实测 5 次模型调用，约 `25.6 s`；历史 17-case 控制集 Hard Pass `16/17` |

本地当前索引是 `sparse-dense-v1` Hybrid。它可以作为本轮前后对照，但不能把结果写成 Sparse-only 产品基线。

## 3. 实施顺序

### D0：冻结基线，不改代码

**任务**

- 记录 Snapshot、代码 revision、Provider、Qdrant collection/contract、评测用户和运行时间。
- 运行 Raw L2 与 Source-aligned L2，保存逐 Target rank、Query、read、citation 状态。
- 选出 8 个已知跨语言差分 Case，保存其 Agent Trace 作为前后对照。

**产物**

- 本地 `research/benchmark/local/runs/<run-id>/run.json` 及 Trace；
- 一份不提交 Git 的基线摘要，注明当前使用 Hybrid contract。

**完成条件**

- 后续 Run 不更换 Snapshot、Qrel、Provider 或索引合同；
- Raw 结果可以原样复现；
- 没有把英文 Query 替换进 Raw 基线。

### D1：Query Formulation 合同

**修改文件**

- `harness_py/orchestration/research_contract.py`
- `harness_py/corpus/tools.py`
- `harness_py/tests/test_research_protocol.py`
- `harness_py/tests/test_corpus_gateway.py`

**实现规则**

将以下行为加入 Agent 指令和 `search_paper_content` 描述：

```text
英文论文使用简洁英文关键词搜索；中文问题不能整句原样作为 query_text。
保留论文名、模型名、数字、章节号和技术术语。
首次搜索无效时只允许一次等义 Query 改写；不要盲目扩大 top_k。
```

不新增翻译模型、不新增工具、不改变 Java Corpus API。仍由现有模型调用完成 Query Formulation。

**测试**

- Prompt 合同测试检查上述规则存在；
- Tool schema 测试保持 Java/In-memory 定义一致；
- 既有授权、paper_id disclosure、source quote 校验全部通过。

**完成条件**

- 8 个差分 Case 的 Agent Trace 中，至少 7 个使用与英文语料对齐的 Query 并进入目标证据候选 Top 3；
- 同一 Case 的模型调用数不高于基线；
- Raw L2 数字保持不变。

### D2：结构查询路由

**修改文件**

- `harness_py/orchestration/research_contract.py`
- `harness_py/tests/test_research_protocol.py`
- PaperLoom-31 Agent Case/回归数据；不改 Golden Target 身份。

**实现规则**

当问题包含 `Appendix`、`Section`、`Table`、`Figure`、页码或章节号时，Agent 必须优先：

```text
get_paper_structure
-> 选择 section_ref
-> search_paper_content(section_refs=[...])
-> read_paper_content
```

重点 Case：`target_paper_22_llm_int8`。不通过放宽单字符 BM25 Token、增加索引噪声或扩大 `top_k` 修复。

**完成条件**

- Trace 中出现上述工具顺序；
- Appendix C 的目标 Section 被定位；
- Exact Read 和 Source Quote 创建成功；
- 不出现未授权 Section 或跨论文读取。

### D3：小范围真实回归

先跑 8 个差分 Case和结构 Case，不立即消耗完整 Expanded 评测预算。

**观察指标**

- Query language 与关键术语保留；
- Candidate rank；
- read / cited / Claim support；
- Hard Pass；
- model calls、Token、TTFT、完整回答延迟；
- technical error、abstention 和 citation validator rejection。

**失败处理**

- Query 仍为中文：修 D1，不调 Retriever；
- Query 已英文但 Appendix 失败：修 D2，不扩大 Top-K；
- Read 成功但答案漏事实：检查 Claim-to-Evidence 和提交校验；
- Candidate 命中但 Exact Read 失败：检查 Current Model、Source Span 和 Evidence reopen。

### D4：完整 Gate

D1、D2 的小范围回归通过后，运行同一版本的完整分层评测：

1. Raw L2：保留跨语言压力基线；
2. Source-aligned L2：验证 Retriever 能力；
3. 17-case L3：验证 Agent 行为；
4. Expanded 集：验证更大范围的 Hard Pass 和引用质量。

**成功条件**

| 维度 | Gate |
| --- | --- |
| Source-aligned Recall@3 | `>= 96.77%` |
| Source-aligned MRR | `>= 0.8441` |
| Source-aligned Exact Read | `>= 30/31` |
| 跨语言差分 Case | `>= 7/8` Top 3 |
| Appendix C | 结构路由 + Exact Read 通过 |
| 17-case Hard Pass | `>= 16/17` |
| Expanded Hard Pass | `>= 10/24` |
| Technical errors | `0` |
| Citation Integrity / unsupported citation | 不劣于基线 |
| 完整回答延迟 | 同一 Case 基线的 P50/P95 增幅不超过 `10%` |

若样本不足以稳定计算 P95，只记录并扩充同一条件下的重复次数，不把最大值写成 P95。

### D5：后置 Retriever A/B（条件触发）

只有 D1-D4 通过、且 Raw 中文 Query 仍是明确产品瓶颈时，才做多语言 Embedding A/B。

**固定变量**

- Snapshot、Sparse、RRF、Candidate budget、Top-K、Qrel、Agent Prompt 不变；
- 只替换 Dense Embedding；
- 只在本地新建实验 Collection，不覆盖当前产品 Collection。

**比较指标**

- Raw Recall@1/3/5/10、MRR；
- Source-aligned Recall 和 MRR；
- Equivalent Evidence Recall；
- L3 Hard Pass、Citation Integrity；
- Embedding Token、重建时间、查询延迟和内存。

若只有离线 Raw Recall 提升、L3 或延迟退化，则不切换产品 Embedding。

## 4. 测试与命令

代码 Slice 完成后先跑聚焦测试：

```bash
.venv-harness/bin/python -m pytest -q \
  harness_py/tests/test_research_protocol.py \
  harness_py/tests/test_corpus_gateway.py \
  harness_py/tests/test_paperloom31.py
```

随后按 D0-D4 的固定 Snapshot 运行本地评测。评测输出、Trace、临时 Corpus Map 和缓存不进入功能提交；功能提交只包含 Prompt/Tool/测试代码。

## 5. 提交边界与回滚

分三个功能提交：

1. D1：Query Formulation 合同和测试；
2. D2：结构路由合同和回归 Case；
3. D4：只有 Gate 通过后才提交评测口径或相关文档更新。

任何 Citation、权限、Hard Pass 或延迟回归，先回滚最近一个 Prompt/Tool Slice，不动 Canonical Evidence、Reading Model 和 Qdrant 数据。

## 6. 明确不做

- 不把 Source-aligned 分数写成中文用户 Recall；
- 不把 Equivalent Evidence 人工复核结果替代 Exact Location Recall；
- 不先加翻译微服务、Reranker 或新的状态机；
- 不在生产数据上做实验。
