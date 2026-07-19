# Qdrant 检索影响量化报告

日期：2026-07-15

量化复跑更新：2026-07-16

状态：历史报告。这里评估的 Dense/Sparse Hybrid 路径已在 2026-07-18 删除；当前 Sparse-only BM25
切换结果见
[`Lexical Qdrant Product Cutover Proposal`](../engineering-evolution/architecture/lexical-qdrant-product-cutover-proposal-2026-07-18.md)。

工程实践文章：[`Java/Qdrant 让 76 篇论文广查询快了 4.34 至 4.86 倍，实际候选命中却从 44/48 降到 34/48`](../../site/practice/evaluation/qdrant-retrieval-impact-benchmark.md)

## 结论

Qdrant 解决了 Python Harness 每轮重复加载论文正文的问题，但当前检索算法没有超过原内存 BM25。

在 14 篇 Golden 论文、69 次已保存 MiniMax 检索调用、24 个证据型 Case 和 48 个证据义务上：

下面的主表保留两条产品路径当时实际给模型的候选语义：BM25 会在广查询和多论文查询中扩展候选，
Qdrant 产品式路径严格停在请求的 `top_k`。因此 `44/48` 对 `34/48` 是迁移前后实际行为对照，不是
等候选预算的纯算法对照。把 BM25 同样截断到请求的 `top_k` 后，结果是 `42/48` 对 `34/48`。

| 方法（实际输出语义） | 精确 Anchor | 完整 Case | 人工复核后的等价证据 | 复核后完整 Case |
| --- | ---: | ---: | ---: | ---: |
| Python BM25，含既有候选扩展 | `44/48` | `20/24` | `48/48` | `24/24` |
| Qdrant Sparse | `40/48` | `17/24` | 未全量复核 | 未全量复核 |
| Qdrant Dense | `15/48` | `4/24` | 未全量复核 | 未全量复核 |
| Qdrant Hybrid RRF | `33/48` | `14/24` | 未全量复核 | 未全量复核 |
| 产品式 Hybrid + 多论文覆盖 | `34/48` | `15/24` | `47/48` | `23/24` |

精确 Anchor 结果显示明显退化。逐条读取候选原文后，产品式 Qdrant 的 14 个精确 Miss 中有 13 个
包含足以回答问题的等价证据，剩下 1 个经人工复核仍证据不足。BM25 的 4 个精确 Miss 都包含等价证据。

因此可以同时得到两个结论：

- Qdrant 当前没有改善指定证据位置的排序，Dense 分支还会破坏较强的 Sparse 结果；
- 在已保存查询和实际工具预算内，语义证据覆盖接近 BM25，只少 1 个证据义务，但部分证据落在
  第 7、8、9、10 名，较低候选预算下仍会丢失。

扩展性收益已经测到。76 篇论文时，旧路径每个 Python Worker 需要约 `6.94-8.84 s` 加载语料并创建
工具，RSS 增加约 `243 MiB`；三组广查询的 p50 为 `1.838-2.139 s`。Java/Qdrant 产品接口没有
这段 Python 全文加载成本，广查询 p50 为 `0.378-0.493 s`。窄查询则相反：已经加载好的 BM25
只需 `19-40 ms`，Java/Qdrant 需要 `345-456 ms`，主要时间来自远程 Query Embedding 和产品数据面。

架构切换保留。相关性优化不能宣称完成，下一步应处理 Sparse 评分、Dense/Sparse 融合和索引粒度，
而不是回退到每个 Harness Replica 都加载全文。

## 测试边界

本报告分开测试三个层级。

### 1. 离线算法隔离

输入保持不变：

- Manifest：`research/golden-data/manifest-expanded.yaml`；
- 论文：14 篇；
- Case：30 个，其中 24 个有正文证据义务和已保存查询；
- 查询：69 次真实 MiniMax-M3 `find_reading_locations` 调用；
- Case 级证据义务：48 个；
- Anchor：29 个，全部能够映射进 Qdrant 索引；
- Query Embedding：MiniMax `embo-01`，1536 维；
- 候选预算：Native 指标保留各方法当时实际给模型的输出。BM25 Adapter 在 `24/69` 条广查询或
  多论文查询中会扩展到最多 12 个候选；Qdrant 产品式路径严格停在请求的 `top_k`。另报告固定
  `1/5/8/12` 前缀，并在 `offline-budget-analysis-v5.json` 中把 BM25 重新截断到请求预算。为了计算
  这些切面，离线服务会取到 evaluation depth；这段耗时不能当作产品原生 `top_k` 延迟。

这层把同一批 Reading Model 投影成 789 个 Qdrant Point，比较：

```text
Python BM25
Qdrant Sparse
Qdrant Dense
Qdrant Dense + Sparse RRF
产品参数下的 RRF + 多论文覆盖
```

它不经过 Java 权限、MySQL Hydration 和 Python HTTP Gateway，因此用于隔离算法，不代表完整产品延迟。

### 2. 产品数据面

产品库中有 3 个 Current Reading Model 能映射到扩展 Golden 语料中的 AgentBench、GAIA、AgentBoard。
探针冻结实际 `paper_id`、`model_version`、检索契约和输入 Trace；它证明的是 Reading Model 映射
与运行契约，没有校验底层 PDF 文件身份。产品探针经过：

```text
Python JavaCorpusGateway
-> Java Corpus API
-> Query Embedding
-> Qdrant Dense/Sparse + RRF
-> Current Generation 校验
-> MySQL Canonical Location Hydration
```

这层覆盖 3 个 Case 和 6 个证据 Anchor，并按 `1/8/32/76` 篇授权论文测试延迟与内存。

### 3. MiniMax 回答 Smoke

同 3 个 Case 分别运行一次 MiniMax-M3：

- 一次使用显式内存 BM25 Fixture Adapter；
- 一次使用在线 Java/Qdrant 产品路径。

每种方法只有 3 个 Sample，作用是确认接线和定位失败层级，不能估计稳定模型质量。

## 检索质量

### 精确位置结果

Case Union 按每个 Case 的证据义务只计一次，是本文主要质量指标。Query Micro 会让多次改写查询的
Case 被重复计数，只作为检索敏感度诊断。

这张表报告实际输出语义，包含 BM25 的既有候选扩展：

| 方法（实际输出语义） | Case Union Recall | 完整 Case | Native Query Recall | Native MRR |
| --- | ---: | ---: | ---: | ---: |
| BM25，含候选扩展 | `44/48`，91.7% | `20/24`，83.3% | 56.6% | 0.366 |
| Sparse | `40/48`，83.3% | `17/24`，70.8% | 52.2% | 0.346 |
| Dense | `15/48`，31.3% | `4/24`，16.7% | 18.6% | 0.058 |
| Hybrid RRF | `33/48`，68.8% | `14/24`，58.3% | 33.6% | 0.166 |
| 产品式 Hybrid + Coverage | `34/48`，70.8% | `15/24`，62.5% | 35.4% | 0.170 |

按每条查询请求的 `top_k` 做等预算重算：

| 方法（严格请求预算） | 精确 Anchor | 完整 Case | Native MRR |
| --- | ---: | ---: | ---: |
| BM25 | `42/48` | `20/24` | 0.35923 |
| 产品式 Hybrid + Coverage | `34/48` | `15/24` | 0.16997 |

候选扩展解释了 BM25 主表中额外的 2 个精确命中，但不是全部差距；在等预算下，当前产品式 Qdrant
仍少 8 个精确证据义务。

当前 Sparse 不是 BM25。它把标准化 Token 做 SHA-256 稳定哈希，权重为 `1 + ln(tf)`，没有 IDF，
也没有文档长度归一化。即使如此，Sparse 仍明显强于 Dense。

Dense 只有 `15/48`。等权 RRF 把弱 Dense 排名与较强 Sparse 排名合并后，Hybrid 从 Sparse 的
`40/48` 降到 `33/48`。多论文覆盖只恢复 1 个义务。当前结果不支持“Dense 加进来就会提高召回”的
假设。

### 等价证据复核

精确 Anchor 要求候选命中人工编写的页码和原文 Selector。Qdrant 以 Page、Table、Figure Location
分组，BM25 则把单个 Reading Element 作为候选。相同事实落在摘要、正文、图注或表格时，精确指标
会把其中一部分判成 Miss。

结构化复核记录位于：

`research/golden-data/qdrant-impact-benchmark-adjudication.yaml`

复核结论不是只靠手工汇总。`qdrant_impact_adjudication.py` 会重新读取 69 条查询和源报告，推导
48 个 Case-Anchor 义务，重算精确命中，并确认每条人工判断引用的 `location_ref` 确实位于声明的
方法、查询和排名。`adjudication-v5-check.json` 已验证 v5 的报告、查询、数据集指纹和 18 条人工
判断的来源绑定。工具只验证来源与计数；“等价”或“证据不足”仍是人工语义判断，不是脚本证明。

产品式 Qdrant 的 14 个精确 Miss：

| 复核结果 | 数量 |
| --- | ---: |
| 等价证据 | 13 |
| 证据不足 | 1 |

唯一证据不足的是 `react_to_agent_evaluation_genealogy_001` 中的
`agentbench_react_pioneer_relationship`。候选返回了 AgentBench 的 ReAct 风格 Prompt 和
“thought + action”执行描述，却没有返回说明 ReAct 首创推理与行动结合的 Related Work 位置。

其他 Miss 包括：

- AgentBench 的 8 个环境在 Figure 2 排名 1；
- AgentBoard 的 Progress Rate 定义排名 1；
- AgentBoard 对 Success Rate 的限制排名 7；
- MINT 的反馈收益排名 7；
- SWE-bench 的 Claude 2 `1.96%` 结果表排名 10；
- WebArena 的四类自托管站点图注排名 10。

复核后：

| 方法 | 等价证据 Recall | 完整 Case |
| --- | ---: | ---: |
| BM25 | `48/48` | `24/24` |
| 产品式 Qdrant | `47/48` | `23/24` |

这组结果不能抹掉排序退化。排名 7 到 10 的等价证据在 `top_k=5` 时仍会成为用户可见的召回失败。

### 产品路径小样本

这层使用 `strict_requested_top_k`：每条保存查询只在它原本请求的 `top_k` 内评分。Java Adapter
为执行覆盖策略而产生的更深候选不会被算进精确命中，避免两种方法使用不同候选预算。

3 篇产品论文、6 个 Anchor 的结果：

| 方法 | 精确 Anchor | 完整 Case | MRR |
| --- | ---: | ---: | ---: |
| Python BM25 | `6/6` | `3/3` | 0.500 |
| Java/Qdrant | `2/6` | `1/3` | 0.111 |

4 个 Qdrant 精确 Miss 都在返回候选中找到等价证据，排名分别为 `9、4、7、1`。复核后是 `6/6`、
`3/3`。产品探针与 14 篇离线结果得出一致现象：精确位置对齐下降幅度很大，语义证据下降较小，
但部分有效位置排得过深。

### 独立复跑稳定性

离线 v4 和 v5 使用同一冻结数据、同一 69 条查询和同一配置。产品式 Hybrid + Coverage 的
Case Union 都是 `34/48`，完整 Case 都是 `15/24`，但 69 条 Native 排名中有 `36/69` 发生变化；
Native MRR 从 `0.17005` 变为 `0.16997`。其中 19 条变化只是同一候选集合的重排，另外 17 条还
改变了 Native 边界内的候选集合。现有产物没有保存逐候选原始分数，不能把根因直接断定为同分；
它只能证明当前 Qdrant/RRF 链路不能承诺完整排名逐项可复现。后续比较排序改动时应同时保存原始
分数、明确二级排序并报告多次复跑，而不能只保存一次 Run。

## 延迟与内存

### 76 篇论文

| 指标 | Python BM25 | Java/Qdrant |
| --- | ---: | ---: |
| 每轮语料加载 + 工具创建 | `6.94-8.84 s` | 无 Python 全文加载 |
| Worker RSS 增量 | 约 `243 MiB` | 共享 Qdrant 服务 |
| 窄查询 p50 | `19-40 ms` | `345-456 ms` |
| 广查询 p50 | `1.838-2.139 s` | `0.378-0.493 s` |

窄查询中，内存 BM25 已经完成索引准备，只做本地计算，因此比远程 Embedding + Qdrant 快约一个数量级。
广查询会扫描更多文档，BM25 延迟随论文数上升；Qdrant 的产品请求还包括 Query Embedding、
Dense/Sparse 检索、融合和 MySQL Hydration。

76 篇广查询上，三组 p50 对比显示 Qdrant 约快 `4.3-4.9` 倍，还避免了每个 Python Worker 的
全文加载和本地索引构建。这个收益来自共享检索服务和预构建索引，不是相关性算法更好。

### 共享服务资源

本地快照：

| 服务 | 数据 | RSS | Allocated Storage |
| --- | ---: | ---: | ---: |
| Qdrant | 76 篇，4,467 Point | 559.1 MiB | 89.7 MB |
| Elasticsearch | 23,707 Chunk + 76 Paper Doc | 1.56 GiB | 487.7 MB |

Elasticsearch 从未服务 Python Harness 当前检索，且两者索引粒度不同。这里只能比较本地运维占用，
不能据此声称 Qdrant 的检索质量优于 Elasticsearch。

单个 Worker 下，`559.1 MiB` 的共享 Qdrant RSS 并不比约 `243 MiB` 的 BM25 增量更小。横向扩展时差异
才会显现：BM25 内存随 Harness Replica 重复，Qdrant RSS 由多个 Java/Python Replica 共享。

## 索引成本与粒度

离线 v5 索引：

| 指标 | 结果 |
| --- | ---: |
| Reading Element | 4,021 |
| Qdrant Point | 789 |
| 平均 Element / Point | 5.10 |
| p95 Element / Point | 16 |
| 平均文本长度 | 1,647 字符 |
| p95 文本长度 | 4,719 字符 |
| 超过 12,000 字符被截断 | 0 |
| Embedding Token | 697,528 |
| 总索引时间 | 61.70 s |
| Embedding | 56.43 s |
| Collection / Payload Index 准备 | 3.54 s |
| Upsert | 1.66 s |

把 4,021 个 Element 合并成 789 个 Location 减少了向量数量，也把摘要、标题、正文或多个段落合并
到同一个 Page Point。SWE-bench 的 Abstract 就被合并进包含 13 个 Element、2,930 字符的 Page Point，
而 BM25 能单独排列该 Abstract。这个粒度变化可能稀释短证据，是精确位置退化的候选原因之一；
本轮没有做 Element Point 与 Location Point 的受控消融，不能把退化确定归因于粒度。

## 可靠性故障

第一次全量运行没有失败在 MiniMax Embedding。时间线为：

```text
22:17:40  789 个 Point 完成索引
22:17:44  保存前 8 条查询结果
22:17:45  Qdrant 首次报告 Too many open files
22:18:15  客户端等待 30 秒后超时
```

Qdrant 进程的软文件句柄上限只有 `1024`：

| FD 来源 | 数量 |
| --- | ---: |
| 产品 Collection | 811 |
| Benchmark Collection | 132 |
| Socket | 22 |
| 其他运行句柄 | 59 |
| 合计 | 1,024 |

产品 Collection 和运行开销在测试前已经使用约 892 个 FD。Benchmark Collection 只是把潜在问题
推过上限。Docker Health Check 当时只建立 TCP 连接；Qdrant 已经无法返回 HTTP 响应，容器仍显示
`healthy`。

修复包括：

- 运行中把软上限提高到 65,536，删除精确命名的 Benchmark Collection，确认产品 Collection 仍为
  Green、4,467 Point；
- Compose 固定 `nofile=65536`；
- Health Check 改为读取 `/healthz` 的 HTTP 200；
- 全量算法测试改用独立隔离 Qdrant 容器，不再与产品 Collection 共享 FD；
- Embedding 和 Qdrant 请求增加有限重试、指数退避、`Retry-After`、请求尝试日志；
- MiniMax Query Embedding 以 1.05 秒间隔限速，限速等待单独记录，不混进服务延迟。

当前本地产品容器是在 Compose 修改前创建的，且没有配置 API Key。完成审计时，它的软上限一度重新
显示为 `1024`；本轮再次通过 `prlimit` 提高到 `65,536` 并验证 `/healthz`。这项运行时调整不会跨
容器重启保存，而新版 Compose 又要求非空 API Key，因此没有盲目重建现有产品容器。永久生效仍需要
在确认 Secret、Volume 和回滚步骤后执行受控重建。

v5 共记录 370 个外部逻辑请求：Embedding 148 个、Qdrant 222 个。67 次主动限速等待共
34.08 秒，没有触发退避；逐次尝试和等待保存在 `request_attempts.jsonl`。Embedding 请求数比早期
报告高，是因为 v5 把批量大小修正为与 Java 索引器相同的 `10`，不能再引用早期运行的低请求数和
索引时间。

## MiniMax 回答 Smoke

| 方法 | Hard Pass | 总 Token | 总耗时 | 中位 Case 耗时 |
| --- | ---: | ---: | ---: | ---: |
| BM25 | `1/3` | 256,540 | 159.4 s | 65.2 s |
| Qdrant | `0/3` | 185,800 | 167.2 s | 46.5 s |

运行前已核对两条链路都使用 MiniMax-M3、`openai-compatible` API 和
`max_completion_tokens=3000`，模型契约一致。这里的 Hard Pass 是 authored exact Anchor、读取和
引用的严格评分，不是人工答案偏好；每种方法每个 Case 只有 1 个 Sample，也没有把这 6 个输出重新
做成人工语义质量对照。因此 `1/3` 与 `0/3` 只用于确认接线、暴露失败层级和阻止错误的模型归因，
不能估计稳定通过率，更不能据此声称 Qdrant 改善或损害了用户体验。

## 工程判断

### 保留的部分

- Qdrant 继续作为 Java 所有的可重建 Candidate Index；
- MySQL 继续作为 Canonical Evidence；
- Python Harness 继续只负责 MiniMax 编排、授权状态和 Evidence Ledger；
- 产品失败时明确报错，不增加内存 BM25 静默回退；
- Elasticsearch 不重新接回 Harness。

### 尚未通过的部分

- 当前 Dense Embedding 没有证明能解决语义漏召回；
- Equal RRF 会让弱 Dense 排名压低强 Sparse 候选；
- Hashed Log-TF Sparse 缺少 BM25 的 IDF 和长度归一化；
- Page/Table/Figure 分组降低 Point 数量；它可能稀释短证据，但仍缺少粒度受控消融；
- 当前没有 Qdrant Snapshot/Restore、Replica 和 Rolling Restart 的生产 Gate。

### 下一轮实验

下一轮不应增加数据集专用规则。候选方向按顺序是：

1. 在同一 Qdrant 数据面实现可对照的 BM25/IDF Sparse 评分；
2. 分别评估 Sparse、Dense 和加权 Fusion，Dense 未达到 Gate 前不能默认等权；
3. 比较 Element Point、Location Point 和 Parent-Child 多粒度索引；
4. 在固定 `top_k=5/8/12` 下报告精确位置、等价证据、Read、Cited 和最终回答；
5. 只有 held-out 结果不低于 BM25，才调整产品默认检索参数。

## 复现资料

配置和工具：

- `research/golden-data/qdrant_impact_benchmark.py`
- `research/golden-data/qdrant_impact_adjudication.py`
- `research/golden-data/qdrant-impact-benchmark.yaml`
- `research/golden-data/qdrant-impact-benchmark-adjudication.yaml`
- `research/golden-data/qdrant_product_probe.py`
- `research/golden-data/qdrant-product-probes.yaml`
- `research/golden-data/qdrant-product-probe-adjudication.yaml`

主要原始产物：

- `research/golden-data/local-runs/2026-07-15-qdrant-impact/offline-algorithm-v5/`
- 独立复跑：`research/golden-data/local-runs/2026-07-15-qdrant-impact/offline-algorithm-v4/`
- 复跑差异：`research/golden-data/local-runs/2026-07-15-qdrant-impact/offline-repeatability-v4-v5.json`
- 候选预算重算：`research/golden-data/local-runs/2026-07-15-qdrant-impact/offline-budget-analysis-v5.json`
- 人工复核来源绑定：`research/golden-data/local-runs/2026-07-15-qdrant-impact/adjudication-v5-check.json`
- `research/golden-data/local-runs/2026-07-15-qdrant-impact/product-probe-v3/`
- 产品复核来源绑定：`research/golden-data/local-runs/2026-07-15-qdrant-impact/product-probe-v3-adjudication-check.json`
- 失败记录：`research/golden-data/local-runs/2026-07-15-qdrant-impact/offline-algorithm/`

离线预检：

```bash
.venv-harness/bin/python research/golden-data/qdrant_impact_benchmark.py --preflight
```

复核表确定性校验：

```bash
run_stamp=$(date +%Y%m%d-%H%M%S)
.venv-harness/bin/python research/golden-data/qdrant_impact_adjudication.py \
  --adjudication research/golden-data/qdrant-impact-benchmark-adjudication.yaml \
  --out "research/golden-data/local-runs/2026-07-15-qdrant-impact/adjudication-v5-check-${run_stamp}.json"
```

复核 YAML 固定引用上面的权威 v5 产物；该命令验证既有人工判断的来源绑定与计数，不会自动给新 Run
做语义复核。新 Run 的排名或 Hash 变化后，必须先重新核对每条人工决定，再更新冻结来源。

全量算法测试应使用独立 Qdrant 实例，并把结果写入持久目录：

```bash
run_stamp=$(date +%Y%m%d-%H%M%S)
offline_out="research/golden-data/local-runs/2026-07-15-qdrant-impact/offline-algorithm-${run_stamp}"

QDRANT_BASE_URL=http://127.0.0.1:6335 \
PAPERLOOM_QDRANT_CONTAINER=paperloom-qdrant-benchmark \
.venv-harness/bin/python research/golden-data/qdrant_impact_benchmark.py \
  --mysql-container pai_smart_mysql \
  --out "$offline_out" \
  --cleanup
```

产品探针和 MiniMax Smoke：

```bash
run_stamp=$(date +%Y%m%d-%H%M%S)
product_out="research/golden-data/local-runs/2026-07-15-qdrant-impact/product-probe-${run_stamp}"

.venv-harness/bin/python research/golden-data/qdrant_product_probe.py \
  --out "$product_out" \
  --run-model-smoke
```
