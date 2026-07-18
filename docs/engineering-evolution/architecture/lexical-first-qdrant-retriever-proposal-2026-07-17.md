# 以词法检索优先的 Qdrant 检索器 Proposal

日期：2026-07-17

状态：核心实现已完成；等待 Fresh Index、检索质量和产品切换 Gate。

## 结论

PaperLoom 应把 Reading Location 的默认检索改成 **Lexical First**：

```text
Java 生成 BM25 风格 Sparse Vector
-> Qdrant Sparse Index 召回和排序
-> Java 校验 Current Reading Model
-> MySQL Hydrate 精确 Evidence
```

默认路径不再调用 Query Embedding，不执行 Dense Search，也不做 Equal RRF。

第一版采用最简单的可验证方案：

- Java 保持当前 Analyzer 和稳定 Term ID；
- Java 计算 BM25 的 TF Saturation 和 Document Length Normalization；
- Qdrant Sparse Vector 使用 `modifier: "idf"`；
- Qdrant 负责 Collection-Level IDF 和 Sparse Dot Product；
- Dense 只保留在离线对照中，不进入新的产品 Collection。

这不是给现有 Hybrid 增加一个 BM25 分数，而是替换默认 Retriever。

## 1. 当前证据

权威结果见
[`Qdrant 检索影响量化报告`](../../evaluation/qdrant-retrieval-impact-2026-07-15.md)。

相同请求 `top_k` 下：

| 方法 | 精确证据义务 | 完整 Case | MRR |
| --- | ---: | ---: | ---: |
| 原内存 BM25 | `42/48` | `20/24` | `0.35923` |
| 当前 Qdrant Log-TF Sparse | `40/48` | `17/24` | `0.345531` |
| 当前产品 Hybrid + Coverage | `34/48` | `15/24` | `0.16997` |

人工复核后的等价证据：

| 方法 | 等价证据 | 完整 Case |
| --- | ---: | ---: |
| 原内存 BM25 | `48/48` | `24/24` |
| 当前产品 Qdrant | `47/48` | `23/24` |

工程判断：

- 当前 Sparse 已经明显强于 Dense；
- Dense 只有 `15/48`，Equal RRF 会压低较强的 Sparse 候选；
- 当前 Sparse 只有 `1 + ln(tf)`，没有 IDF 和长度归一化；
- Qdrant 已经证明共享数据面的扩展性，但当前 Ranking 还没有通过质量 Gate；
- 窄查询的 `345-456 ms` 包含 Query Embedding 和产品数据面耗时，移除 Query Embedding 后需要重新实测。

## 2. 目标与非目标

### 目标

- 默认检索质量至少恢复到严格预算下的原 BM25 Baseline；
- Qdrant 继续作为 Java 所有的共享 Candidate Index；
- Python Harness 继续专注 MiniMax 编排、Scope 和 Evidence Ledger；
- MySQL Current Reading Model 继续是 Canonical Evidence；
- 保留当前授权、Searchability、Current Model 校验和 Exact Hydration；
- 默认 Query 不依赖 Embedding Provider；
- 排序规则可解释、可复跑、可离线分析。

### 非目标

- 修改 MiniMax Prompt、工具名称、工具 Schema 或既有语料路径；
- 修改 Golden Data、Anchors、Reading Models、PDF 或 Extracted Text；
- 在 Python Harness 中重新维护生产 BM25；
- 同时改变 Point 粒度、Parser 或 Reading Model Pipeline；
- 引入 Online Judge、Best-of-N、数据集专用 Boost 或静默 Fallback；
- 兼容旧 Qdrant Collection、旧 Vector Schema 或旧 Hybrid Runtime。

## 3. Target Module

在授权和 Current Model 解析之后、MySQL Hydration 之前增加一个小 Interface：

```java
interface ReadingLocationRetriever {
    RetrievalCandidates retrieve(LocationRetrievalRequest request);
}
```

建议请求只包含：

```text
active paper_id -> model_version map
query_text
section_query
element_type hints
page range
top_k
```

这个 Module 隐藏：

- Qdrant Vector 名称；
- Analyzer、Term Hash 和 BM25 参数；
- Candidate Depth；
- Qdrant Raw Payload；
- 未来是否存在实验性 Dense 通道。

职责分配：

| Module | 负责 |
| --- | --- |
| `CorpusRetrievalService` | Scope、访问授权、Searchability、Current Model、MySQL Validation、Coverage、Hydration |
| `ReadingLocationRetriever` | Query 编码、Qdrant Search、词法排序、稳定二级排序、最小诊断 |
| `ReadingModelQdrantIndexService` | Location Projection、Document Sparse Encoding、Point Build、写后计数 |
| `QdrantClient` | Collection、Point、Search 协议和 Schema 验证 |

Python Harness 和模型可见 Tool Contract 不变。

## 4. BM25 Sparse Contract

### Analyzer

第一轮保持当前 Java Sparse Analyzer，避免同时改变 Scoring 和 Tokenization：

```text
normalize: NFKC + lowercase(Locale.ROOT)
token: Unicode Letter / Number / underscore
minimum length: 2
stopwords: none
term id: current SHA-256-derived integer contract
```

Term Hash Collision 数量必须在构建时报告。发现可见 Collision 时升级 Term ID Contract 并完整重建，不能
在同一 Contract 下静默改变。

### Document Weight

对文档 `d` 中的词项 `t`：

```text
tf_norm(t, d) =
  tf(t, d) * (k1 + 1)
  / (tf(t, d) + k1 * (1 - b + b * dl(d) / avgdl))
```

第一版参数：

```text
k1 = 1.2
b  = 0.75
```

Document Sparse Vector：

```text
index = stable_term_id(t)
value = tf_norm(t, d)
```

### Query Weight

Query 中每个去重词项的 Weight 为 `1.0`。Qdrant 通过 `modifier: "idf"` 应用：

```text
idf(t) = ln((N - df(t) + 0.5) / (df(t) + 0.5) + 1)
```

最终得分为 Sparse Dot Product。

这是一条 **Collection-Level BM25 风格路径**，不是原 Python BM25 的数值复制，因为：

- Qdrant IDF 不按请求中的 Paper Filter 重算；
- 新路径按 Location 建 Point；
- 原 BM25 还包含 Passage、Lead、Section 和相邻段落逻辑；
- Java 与 Python Analyzer 不完全相同。

因此必须按结果验收，不能只按公式验收。

## 5. Collection 与 Index Contract

新产品 Collection 只保留 Lexical Sparse Vector：

```json
{
  "sparse_vectors": {
    "lexical_bm25_v1": {
      "modifier": "idf",
      "index": {
        "on_disk": true
      }
    }
  }
}
```

不保留 `dense`、旧 `sparse` 或旧 Collection。

用 `retrieval_index_contract` 替换当前过窄的 `retrieval_embedding_contract`。Contract 至少覆盖：

```text
collection schema version
point projection version
analyzer version
term-id version
BM25 scorer version
k1 / b / avgdl snapshot
Qdrant version and sparse modifier
```

`PaperSearchabilityService` 必须要求：

```text
Current Reading Model READY
AND retrieval_index_status = READY
AND retrieval_index_contract = active contract
AND retrieval_indexed_location_count > 0
```

Index-Time Embedding 被删除。Reading Model 生成不变，但 `VectorizationService` 中的 EMBEDDING Status、
Embedding Usage 和 Embedding Model 字段应改成 Lexical Index 语义，避免继续报告不存在的阶段。

## 6. Query Path

目标路径：

```text
validate locked scope
-> resolve accessible and searchable Current Models
-> combine query_text and section_query with the existing rule
-> encode Sparse Query
-> one global Qdrant lexical_bm25_v1 search
-> validate candidates against Current MySQL models
-> apply explicit paper coverage
-> hydrate exact locations from MySQL
-> return the existing model-visible result
```

默认路径明确不执行：

```text
Query Embedding
Dense Search
RRF
Online Judge
Request-Level Fallback
```

第一轮保持当前 `top_k` 和内部 Candidate Depth。Paper Coverage 是显式 Selection Policy，不改变 Raw BM25
Score。Element Type 只作为确定性二级排序，不再使用隐藏的 `+0.001` Magic Boost。

为了隔离因果，Benchmark 分开报告：

```text
logtf_policy_control
bm25_scorer_only
bm25_target_policy
```

`bm25_scorer_only` 只替换 Sparse Weight，保留当前 Search/Merge/Coverage；
`bm25_target_policy` 再评估简化后的词法主路径。

## 7. Lifecycle 与迁移

沿用当前无 Generation、Fail-Closed 的索引状态：

```text
PENDING -> BUILDING -> READY / FAILED
READY / FAILED -> REBUILDING -> READY / FAILED
```

Schema Cutover 使用破坏式 Full Rebuild：

```text
claim global maintenance job
-> block new index mutations and drain running writers
-> snapshot exact (paper_id, model_version) pairs
-> compute avgdl
-> mark snapshot papers REBUILDING
-> delete old collection
-> create sparse-only collection
-> rebuild from unchanged Current Reading Models
-> verify exact total and per-paper point counts
-> activate retrieval_index_contract
-> mark papers READY
```

不保留旧 Collection，不做旧 Runtime Compatibility，也不做请求级回退。失败时保持不可检索，修复后重新
Full Rebuild。

Qdrant 1.15.5 的 IDF 会受 Collection/Segment 状态影响，删除后的 Posting 是否立即退出 IDF 统计需要真实
Probe。这个问题是产品 Gate：

- Fresh Index 质量通过但 Delete/Rebuild 后排序不稳定，则不切换产品；
- 下一方案是由 Java 持有 Frozen IDF、Qdrant 只做 `modifier: none` 的 Sparse Dot Product；
- 不用 Boost 或 Fallback 掩盖 Lifecycle 问题。

## 8. Evaluation Gates

扩展现有 `research/golden-data/qdrant_impact_benchmark.py`，使用相同 14 篇论文、69 次保存的 MiniMax Query、
相同 Paper Filter 和相同请求 `top_k`。

比较：

```text
原内存 BM25
当前 Log-TF Sparse
新 Qdrant BM25 scorer-only
新 Qdrant BM25 target policy
当前产品 Hybrid + Coverage
```

推广下限：

| 指标 | Gate |
| --- | ---: |
| 精确证据义务 | `>= 42/48` |
| 完整 Case | `>= 20/24` |
| MRR | `>= 0.35923` |
| 人工复核后的证据 | `48/48` |
| 人工复核后的完整 Case | `24/24` |

至少运行两次独立 Fresh Index，保存 Raw Score，并要求 Headline、Hit/Miss 和 First Relevant Rank 稳定。

现有集合已经是 Development Set。正式质量声明前需要一个按 Paper 隔离的 Blind Holdout，候选方法在精确
义务、完整 Case 和 MRR 上不得低于配对 BM25。

检索 Gate 通过后再运行 MiniMax-M3 全量 Golden Data：

- MiniMax-M3 是验收模型；
- GPT 只做诊断；
- 不做 Best-of-N；
- 不修改 Prompt、Anchors 或 Required Evidence；
- 检查新的 Scope、Evidence Read、Citation 和 Answer 回归。

### 性能 Gate

- Lexical Query 的 Query Embedding 调用数为 0；
- 同机 Narrow Query p50 明显低于当前 Hybrid；
- 76 篇 Broad Query p50 不高于当前约 `493 ms`；
- 任一 Scope 的 p95 不高于当前产品对照；
- 报告 Index Time、Point Count、Disk、RSS 和 Embedding Token。新索引的 Embedding Token 应为 0。

### 数据保存

保持简单，只保存：

```text
research/golden-data/local-runs/lexical-qdrant-<run_stamp>/
  run.json
  query_results.jsonl
```

每条 Query Record 包含 `run_id`、`query_id`、Retriever Contract、候选 Ref/Score/Rank、Latency 和 Typed
Outcome。`run.json` 保存输入 Fingerprint、Expected/Written Count 和完成状态。分析全部离线完成，不写
`/tmp`，不建设在线 Eval Schema。

## 9. Protected Inputs

以下内容保持不变：

```text
data/golden/
research/golden-data/corpora/
existing manifest*.yaml
paper-packs and cases
saved MiniMax traces
PDFs, extracted text and Reading Models
harness_py/orchestration/research_contract.py
harness_py/corpus/tools.py model-visible schema
harness_py/corpus/gateway.py paths
```

当前 v5 指纹继续固定：

```text
dataset: c283e968432b051461c01733adaf4dd67e0d0f1334a01c3c84ed05b9dfc6b114
saved queries: 1a3cd677e89854d010f70f9b2366e5b9aff849e4decc75530e765f0d604640e6
```

新的 Holdout 只能新增独立 Namespace 和 Manifest，不能修改既有基线。

## 10. Implementation Plan

### Phase 1：离线算法

- 在现有 Benchmark 增加 BM25 Scorer；
- 保持 Point Projection、Analyzer、Hash 和 Candidate Budget；
- 分开报告 Scorer-Only 与 Target Policy；
- 运行两次 Fresh Index；
- 验证质量和 Qdrant IDF Mutation 行为。

### Phase 2：Java Index Contract

- 增加 Lexical Analyzer/Encoder 和 `retrieval_index_contract`；
- 把 Qdrant Collection 改成 Sparse Only；
- 删除 Index-Time Embedding、Dense Vector 和旧 Sparse Vector；
- 更新 `VectorizationService` 的 Status、Usage 和日志；
- 增加 Real Qdrant Smoke 与 Contract Tests。

### Phase 3：Retriever Module

- 从 `CorpusRetrievalService` 移出 Embedding、Dense Search 和 RRF；
- 接入 `ReadingLocationRetriever`；
- 保留 Authorization、Searchability、MySQL Validation、Coverage 和 Hydration；
- 不修改 Python Tool Contract。

### Phase 4：完整验证

- 运行全部离线质量 Gate；
- 运行 Blind Holdout；
- 在隔离 Product Data Plane 复跑 69 次 Query；
- 运行 MiniMax-M3 全量 Golden Data；
- 运行 Latency、Delete/Rebuild 和 Failure Smoke。

### Phase 5：破坏式切换

- 进入维护窗口；
- 停止并 Drain Index Writer；
- 删除旧 Collection；
- Full Rebuild Sparse-Only Collection；
- 复跑 Product Query 和 MiniMax Wiring Smoke；
- 删除旧 Hybrid、Dense 和 Embedding 生产代码。

## 11. Go/No-Go Decision

只有以下条件全部满足才切换：

1. 新 Lexical Path 达到 `42/48`、`20/24`、MRR `0.35923`；
2. 人工等价证据达到 `48/48`、`24/24`；
3. Blind Holdout 不低于配对 BM25；
4. 两次 Fresh Index 的 Headline 和关键排名稳定；
5. Delete/Rebuild 不引入可见 IDF 排名漂移；
6. Product Authorization、Current Model Validation、Hydration、Read 和 Citation 无新错误；
7. MiniMax-M3 全量 Golden Data 无新回归；
8. Query Embedding 调用数为 0，Latency 不退化；
9. Golden Data、Prompt、Tool Schema、Reading Model 和路径未修改。

如果第 5 条失败，保留 Lexical-First Module 设计，但把 IDF 从 Qdrant 移到 Java Frozen Statistics；不要回到
Equal RRF，也不要增加 Case Patch。

## 12. 最终目标状态

```text
MiniMax Research Harness
-> unchanged find_reading_locations contract
-> Java validates locked paper scope
-> ReadingLocationRetriever encodes lexical query
-> Qdrant BM25 sparse retrieval
-> Java validates Current Reading Model
-> MySQL hydrates exact canonical locations
-> Harness reads and cites Evidence
```

Qdrant 负责共享、可扩展的候选检索，Java 负责权限、合同和 Canonical Validation，Python Harness 继续只负责
模型编排。Dense 是否重新进入默认路径，由后续独立证据决定。
