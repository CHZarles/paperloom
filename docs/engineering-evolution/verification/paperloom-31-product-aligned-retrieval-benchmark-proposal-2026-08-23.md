# PaperLoom-31 产品对齐检索评测 Proposal

日期：2026-08-23
状态：Implemented locally and technically verified；独立 Query Review 待完成，未修改生产检索，尚未提交或部署。

关联诊断：
[PaperLoom-31 L2 跨语言召回诊断](paperloom-31-l2-cross-language-retrieval-diagnosis-2026-08-22.md)

执行规格：
[PaperLoom-31 产品对齐检索评测 Spec](paperloom-31-product-aligned-retrieval-benchmark-spec-2026-08-23.md)

## 1. 决策

当前不更换 Embedding，也不做 Oracle Dense、Reranker、RRF 调权或生产 Prompt 修改。先让 Benchmark 同时回答
三个不同的问题：

```text
L2-Raw
原始中文 Query -> 单篇英文论文 Retriever
用途：测底层 Retriever 自身的跨语言鲁棒性

L2-Source-Aligned
冻结、盲审的等义英文 Query -> 同一个 Retriever
用途：测 Query 与英文语料语言对齐后的检索能力

L3
中文用户问题 -> Agent -> Tool Query -> Evidence -> Answer
用途：测真实产品端到端能力
```

原有 `l2` 保持不变；新增 `l2_source_aligned`，不覆盖中文 Query、Qrel、Snapshot 或历史 Run。

这次改动不会提升线上准确率。它解决的是评测归因错误：避免把底层跨语言压力测试的低分直接解释成
产品失败，也避免用 Agent 的高分掩盖 Retriever 的技术债。

## 2. 为什么先改评测口径

冻结基线：

```text
Run:      research/benchmark/local/runs/20260822T113706Z-f9222712/run.json
Revision: 5db2f1bdd7ae4f43539a1643f7e7d0646f666339
Snapshot: a61e8c1c240b2e8873b88d20da497b9ec0b98d9631c77a3e05c0634eeb92ecd3
```

L2-Raw：

| 指标 | 结果 |
| --- | ---: |
| Recall@1 | `14/31 = 45.16%` |
| Recall@3 | `23/31 = 74.19%` |
| Recall@5 | `23/31 = 74.19%` |
| Recall@10 | `25/31 = 80.65%` |
| MRR | `0.5821` |

对 Top 5 外的 8 个 Target，只把中文 Query 改成等义英文 Query 后，7 个进入 Top 3。6 个 Target 在中文
Query 下的 Sparse Score 为 `0`。这证明中英文 Query/Corpus 错配会让 BM25 路径基本失去作用。

但同一轮真实 Agent 经常主动生成英文检索词，L3 为 `16/17` Hard Pass。产品链路并不是把用户中文原句
直接交给 Retriever：

```text
L2-Raw: 中文原句 -> Retriever
L3:     中文问题 -> Agent 形成检索词 -> Retriever -> 回答
```

因此，`23/31` 能说明 Retriever 的跨语言鲁棒性有限，不能单独说明线上回答准确率只有 74.19%。反过来，
`16/17` 也不能证明 Retriever 自身已经具备稳定的跨语言能力。

## 3. 三层指标各自负责什么

| 指标 | 固定输入 | 被测对象 | 不能证明 |
| --- | --- | --- | --- |
| L2-Raw | 原始中文 Query | Retriever 的跨语言鲁棒性 | 真实 Agent 最终成功率 |
| L2-Source-Aligned | 人工复核的等义英文 Query | 语言对齐后的索引、召回与排序 | Agent 一定能生成同样的 Query |
| L3 | 中文问题和完整产品链路 | Agent、工具、Retriever、读取、引用与回答 | 失败一定由 Retriever 引起 |

新增 L2-Source-Aligned 是增加一个诊断坐标，不是把题目翻译成英文后替换低分，也不设“必须提高到多少”的
验收线。

结果按下面方式解释：

| 现象 | 结论 | 下一步 |
| --- | --- | --- |
| Raw 低，Source-Aligned 高，L3 高 | 产品可用，但 Retriever 依赖 Agent 做语言对齐 | 记录跨语言鲁棒性技术债，不迁移 Provider |
| Raw 低，Source-Aligned 低 | 语言对齐后仍检索失败 | 再诊断索引、切分、Fusion 或 Embedding |
| Source-Aligned 高，L3 低 | Retriever 能找到，Agent 没形成或使用好 Query | 分析 L3 Tool Trace，不改 Retriever 提分 |
| 三者都高 | 当前链路在该数据集上表现稳定 | 保留回归，不新增系统 |

## 4. 最小改动

### 4.1 增加一个版本化 Query Companion

新增：

```text
research/benchmark/paperloom-31-v1/source-aligned-queries-v1.yaml
```

它只保存 31 个 Target 的：

- `target_id`；
- 原始中文 Query 的 SHA-256；
- 经人工复核的等义英文 Query。

原始中文 Query 仍只来自冻结 Snapshot。Companion 不保存正文、Expected Answer、`location_ref`、答案值或
当前排名。

翻译者只能看到原始中文 Query，不能看到目标正文、Expected Answer、`location_ref` 或检索结果。英文 Query
只能保留原问题语义，不能补充原问题中没有的答案词或目标原文短语。完成盲译后再由维护者按 Target 绑定并
校验 Hash。

翻译只改变语言：保持原问题的信息粒度、问句结构、实体、数字和限定条件，不改写成关键词检索式，也不主动
扩展同义词。否则实验会同时改变 Query Formulation，无法把差异归因于语言对齐。

Companion 不设置“自动选择最新版本”的默认行为。运行 Benchmark 时必须显式传入文件路径；如果新 Snapshot
的 Query 发生变化，则发布新的 Companion 文件版本，不能继续套用旧文件。内容修正时创建
`source-aligned-queries-v2.yaml`；只要字段结构没有变化，Schema Version 仍为
`paperloom-source-aligned-queries/v1`。

### 4.2 复用现有 L2 链路

L2-Source-Aligned 继续调用当前 Python Harness、Java Corpus API 和 `ReadingCorpusTools`。除 `query_text`
外，下列输入全部与 L2-Raw 相同：

- 同一 Snapshot 和 31 个 Target；
- 同一单论文 `paper_id` Scope；
- 同一 `element_types`；
- 同一 `top_k=10`；
- 同一唯一 `location_ref` Qrel；
- 同一 Exact Read 和 Source Quote 校验。

实现只让现有 `_run_l2` 接受一组可选 Query；Raw 不传覆盖值，Source-Aligned 传 Companion 中的 Query。
不复制第二套检索与计分逻辑。

完整 Run 的执行顺序固定为：

```text
L0 -> G0 -> L1 -> L2-Raw -> L3 -> L2-Source-Aligned
```

Source-Aligned 放在 L3 后，因为额外 31 次检索会申请 Embedding Token，并可能改变余额、限流状态和缓存热度。
把它提前会污染需要与历史基线比较的 L3；不为评测新增 Java 配额绕过机制。

### 4.3 以新增字段输出

`run.json` 保留：

```json
"l2": { "...": "原有 L2-Raw 结果" }
```

并新增：

```json
"l2_source_aligned": {
  "query_set": {
    "schema_version": "paperloom-source-aligned-queries/v1",
    "file_sha256": "..."
  },
  "metrics": {},
  "by_type": {},
  "targets": [],
  "technical_errors": []
}
```

CLI 同时打印 `l2_metrics` 和 `l2_source_aligned_metrics`。新 Run Schema 升为 `v3`，但不改名或删除现有
结果字段；历史 `v2` Run 保持不变，不回填新指标。严格校验 Schema Version 的消费者必须显式接受 `v3`。

若任一 Source-Aligned Target 发生技术错误，Harness 仍完成剩余 Target 和 L3、写出 Artifact，但该组 Recall
不得用于比较，CLI 最终返回失败。技术故障不能被解释为普通 Retrieval Miss。

## 5. 实施顺序

1. 盲译并人工复核 31 个 Query，生成 Companion；冻结后不得根据排名反复改写。
2. 在 `paperloom31.py` 加载、校验显式传入的 Companion，让同一个 `_run_l2` 分别执行 Raw 和
   Source-Aligned，并保证 L3 先于 Source-Aligned 执行。
3. 在 `run.json` 和 CLI 增加 Source-Aligned 结果，不改变现有 Beta Gate。
4. 添加最小单元测试，再用冻结 Snapshot 跑一次完整 Benchmark，保存两组 L2 和 L3 结果。
5. 根据三层结果决定是否另开生产检索优化 Proposal；本次不顺带修改生产代码。

## 6. 验收

- Companion 恰好覆盖 Snapshot 的 31 个 Target，无缺失、无额外 Target；
- 每个原始 Query Hash 与 Snapshot 精确匹配，空 Query 或 Hash 漂移在发起产品请求前失败；
- L2-Raw 仍读取 Snapshot 中文 Query，输出结构和行为保持不变；
- L2-Source-Aligned 只替换 Query，其余检索、Qrel 和 Exact Read 合同相同；
- 完整 Run 按 `L2-Raw -> L3 -> L2-Source-Aligned` 执行，新增检索不污染 L3；
- 新 Run 同时包含 `l2`、`l2_source_aligned` 和 `l3`；
- CLI 同时显示两组 L2 指标；
- Source-Aligned 出现技术错误时仍保存 Artifact，但 CLI 返回非零，结果不得参与比较；
- 不修改 Java、Qdrant Collection、Embedding Provider、RRF、Agent Prompt 或生产配置；
- 不把 Source-Aligned 分数描述为生产性能提升。

## 7. 本轮明确不做

- 不做 Oracle Dense Runner 或 test-only Java Evaluation Framework；
- 不更换 Embedding，不建临时 Qdrant Collection；
- 不加入 Equivalent Evidence Judge、Tool Trace Replay 或 Held-out 数据集；
- 不调整 Top K、Sparse/Dense 权重或 Passage 切分；
- 不增加翻译节点、意图节点或新的模型调用；
- 不修改 L3 Prompt 来配合这次评测。

这些工作只有在三层指标暴露出真实产品失败，而且能明确归因到对应模块时再单独立项。

## 8. 面试表达

> 我发现单篇论文证据检索的 Recall@5 只有 74.19%，但没有直接换 Embedding。对 8 个失败 Case 做单变量
> 中英 Query 差分后，7 个进入 Top 3；同时端到端 Agent 是 16/17，因为 Agent 经常先生成英文检索词。
> 这说明原 Benchmark 把 Retriever 的跨语言压力测试和产品链路能力混在了一起。我保留原始中文 L2，
> 新增盲审冻结的 Source-Aligned L2，并继续用 L3 代表端到端表现。三层共用同一 Java 检索、Qrel 和读取
> 校验，只有 Query 不同。这样没有靠改题掩盖低分，也能把后续问题准确归因到 Retriever 或 Agent。
