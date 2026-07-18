# 检索方法 Benchmark 工作流

这套工作流用于评估候选检索改动。当前产品基线是 Java/Qdrant，内存 BM25 是冻结语料上的显式
对照 Adapter。两者必须分开报告，不能把“仓库中仍有 BM25 实现”写成“产品仍使用 BM25”，也不能
因为 Qdrant 已经上线就宣称它改善了检索质量或用户体验。

## 当前基线

产品请求经过：

```text
MiniMax 调用 find_reading_locations
-> Python Harness 校验本轮已公开和已授权的 paper_id
-> Java Corpus API 再次校验用户、Scope 和 Current Reading Model
-> Java 生成 BM25 Sparse Query
-> Qdrant `lexical_bm25_v1` 单次检索
-> Java 执行确定性的论文与 canonical Lead Coverage
-> MySQL Hydrate Candidate Preview
-> MiniMax 调用 read_locations
-> MySQL 精确读取 Canonical Reading Model
-> Python 创建 Evidence ID
```

Qdrant 只是可重建的 Candidate Index。MySQL Current Reading Model 仍是 Canonical Evidence，只有
`read_locations` 的准确读取能够生成 Evidence。产品失败时明确报错，不静默切回 BM25。

BM25 对照通过 Golden Manifest、冻结 Reading Model 和已保存 MiniMax 查询在进程内运行。它分别
回答两个问题：迁移前后各自实际给模型的候选发生了什么，以及截断到相同请求 `top_k` 后算法排序
发生了什么。它不覆盖 Java 权限、HTTP、Qdrant Index Contract 或 MySQL Hydration 的产品成本。

## 2026-07-18 当前产品结果

破坏式切换后的产品 Collection 是 `paperloom_reading_locations_bm25_v1`，唯一 Sparse Vector 是
`lexical_bm25_v1`，配置为 `modifier=idf` 和 `on_disk=true`。Collection 没有 Dense Vector，查询路径
不调用 Embedding Provider，也没有 RRF 或请求级 Fallback。

同一批 69 条已保存 MiniMax 查询、24 个证据型 Case 和 48 个指定证据义务上：

| 方法 | 精确 Anchor | 完整 Case | MRR |
| --- | ---: | ---: | ---: |
| 内存 BM25 对照 | `35/48` | `15/24` | `0.37838` |
| Java/Qdrant Sparse BM25 | `48/48` | `24/24` | `0.48019` |

76 篇范围的聚合延迟为：窄查询 p50 `64.821 ms`，广查询 p50 `132.100 ms`、p95
`371.559 ms`、最大 `390.086 ms`。全量 MiniMax-M3 运行均无技术失败，稳定集为 `6/10`，扩展集
为 `9/24`。后两项是模型证据选择和严格 Anchor 行为，不能用 Candidate 的 `48/48` 代替。

权威产物位于
`research/golden-data/local-runs/lexical-qdrant-cutover-20260718-173750/`。Schema、全局与逐论文 Point
数量由 `cutover-verification-final.json` 验证；查询质量与延迟见 `retrieval-probe-v4/`。

## 2026-07-16 历史切换前结果

离线 v5 是当前权威产物，v4 是使用相同代码、配置、14 篇 Golden 论文和 69 次已保存
MiniMax 查询的重复运行。24 个证据型 Case 共有 48 个证据义务：

下表保留各方法实际输出语义；BM25 在 24/69 条调用中包含既有候选扩展，Qdrant 停在请求 `top_k`：

| 方法（实际输出语义） | 精确 Anchor（v4 -> v5） | 完整 Case（v4 -> v5） | Native MRR（v4 -> v5） | v5 人工复核 |
| --- | ---: | ---: | ---: | ---: |
| 内存 BM25，含候选扩展 | `44/48 -> 44/48` | `20/24 -> 20/24` | `0.365641 -> 0.365641` | `48/48`，`24/24` |
| Qdrant Sparse | `40/48 -> 40/48` | `17/24 -> 17/24` | `0.346954 -> 0.345531` | 未全量复核 |
| Qdrant Dense | `15/48 -> 15/48` | `4/24 -> 4/24` | `0.057522 -> 0.057522` | 未全量复核 |
| Qdrant Hybrid RRF | `33/48 -> 33/48` | `14/24 -> 14/24` | `0.165312 -> 0.166008` | 未全量复核 |
| 产品式 Hybrid + Coverage | `34/48 -> 34/48` | `15/24 -> 15/24` | `0.170049 -> 0.169969` | `47/48`，`23/24` |

v5 按每条查询请求的 `top_k` 做等预算重算后，BM25 为 `42/48`、`20/24`、MRR `0.35923`；产品式
Hybrid + Coverage 为 `34/48`、`15/24`、MRR `0.16997`。候选扩展贡献了 2 个额外命中，但没有改变
Qdrant 严格预算排序仍低于 BM25 的结论。

头部 Case Union 指标在两次运行中一致，但候选顺序并非完全可重复。v4 与 v5 相比，Sparse、
Hybrid 和产品式 Hybrid 的 Native Candidate 序列分别有 `34/69`、`38/69`、`36/69` 不同；
其中 `22`、`22`、`19` 条只是同一候选集的重排，其余还改变了 `top_k` 边界内的候选集合。Dense
序列全部一致。当前产物没有保存逐候选原始分数，因此不能证明变化来自同分；产品级确定性测试需要
补充原始分数和显式二级排序合同，不能只看汇总 Recall。

当前 Qdrant Sparse 是 Hashed Log-TF，没有 BM25 的 IDF 和长度归一化；Dense 单路明显更弱，等权
RRF 会压低更强的 Sparse 候选。人工复核说明多数精确 Miss 含有等价证据，但部分证据位于第 7 到
第 10 名，较低工具预算下仍会成为实际召回失败。

离线 v5 将 4,021 个 Reading Element 投影为 789 个 Point，Embedding 用量为 `697,528` Token。v5
索引共用 `61.70 s`，其中 Embedding `56.43 s`；v4 重复运行分别为 `62.88 s` 和 `60.33 s`。
这是按 Java 同等的 10 条 Embedding Batch 得到的索引侧成本，不是查询延迟。

产品 v3 在 `strict_requested_top_k` 下只评分每条已保存查询实际请求的 Candidate 预算，不使用
Adapter 内部扩展的更深候选。3 篇产品论文、6 个 Anchor 上，BM25 为 `6/6`、`3/3`、MRR `0.500`；
Java/Qdrant 为 `2/6`、`1/3`、MRR `0.111`。冻结的人工复核将 Qdrant 的 4 个精确 Miss 判为等价
证据，复核后是 `6/6`、`3/3`，但不能用这个结果消除低 `top_k` 下的排名风险。

产品 v3 的 76 篇论文子集上，三个查询的 BM25 轮次初始化为 `6.94-8.84 s`，Worker RSS
增量为 `243.2-243.4 MiB`。窄查询 p50 为 BM25 `19.2-39.9 ms`、Java/Qdrant `344.6-456.0 ms`；
广查询 p50 为 BM25 `1.838-2.139 s`、Java/Qdrant `378.4-493.0 ms`。在 1/8/32/76 篇全部样本上，
聚合 p50 分别是：窄查询 `24.9 ms` 对 `418.8 ms`，广查询 `481.9 ms` 对 `460.7 ms`。这是
共享索引和扩展性取舍，不是相关性收益。

MiniMax-M3 Smoke 在两条路径上验证了 Provider、Model、OpenAI-compatible API Style 和
`max_completion_tokens=3000` 一致。每种方法每个 Case 只运行 1 次，精确 Hard Pass 为 BM25 `1/3`、
Qdrant `0/3`。这只是连线与失败定位 Smoke，不是稳定的模型质量或用户体验结论。

完整证据和复现命令见
[`Qdrant 检索影响量化报告`](qdrant-retrieval-impact-2026-07-15.md)。

## 候选方法

后续实验可以包括：

- 在未见论文上验证当前 BM25 Analyzer、IDF、长度归一化和 Location Projection；
- 评估查询改写、候选预算和通用的多论文 Coverage Policy；
- 比较 Reading Element、Location 和 Parent-Child 多粒度 Point；
- 改进分词、段落或章节候选策略；
- 在固定候选预算内加入 Cross-encoder 或小模型 Reranker；
- 通过 PaperLoom Corpus Interface 接入另一个外部检索系统。

Reading Model 始终保留 Canonical Content 和 Provenance。任何检索方法都是派生投影，不能替代论文
模型，也不能修改 Golden Data、Anchor、Prompt 或既有语料路径来提高分数。

## 评估问题

每次实验必须回答：

1. 固定候选预算后，Required Location 和等价证据覆盖是否增加？
2. Agent 是否实际读取并引用了新增候选？
3. Held-out Case 的 Hard Pass 是否改善或至少不退化？
4. 哪些问题、论文、Element Type 和索引粒度改善或退化？
5. 延迟、Token、模型调用、基础设施成本和技术失败发生了什么变化？
6. Java 授权阶梯、Current Model / Index Contract、Exact Read 和历史引用重开是否保持不变？

## 数据边界

- 产品论文和 Benchmark Corpus 保持隔离。
- Benchmark Adapter 可以复用 Corpus 与 Tool 代码，但不能把评测论文插入用户 Library 或默认 Scope。
- 学习型 Retriever 或 Reranker 必须按论文和问题家族隔离 Train、Validation 与 Test。
- Parser、索引投影、检索排序、Agent Policy 和最终答案分别报告，不能用一个总分掩盖失败层级。
- 原始 Run 写入 `research/golden-data/local-runs/`，不写入 `/tmp`，也不覆盖历史 Baseline。

## 必需运行

至少包含：

- Tool 授权、Candidate 顺序和 Evidence 创建的确定性测试；
- 稳定与扩展 Manifest 的 Fixture Validation 和 Anchor Audit；
- 同一批保存 MiniMax 查询上的当前产品 Retriever 与明确命名的对照方法；
- 未参与调参的 Held-out 集；
- 覆盖正文、表格、图片、公式和多论文问题的真实 PDF Case；
- 经过 Python Gateway、Java、Qdrant、MySQL、最终校验、持久化和引用重开的产品 Smoke；
- 必要时运行少量 MiniMax-M3 Smoke，明确 Sample 数和随机性。

小样本探索必须标记为 Smoke，不能报告为完整 Benchmark。GPT 只能用于诊断，产品模型结论必须使用
MiniMax-M3。

## 指标

报告原始数量和比例：

| 维度 | 示例 |
| --- | --- |
| 检索 | Required-paper Coverage、Exact Anchor、Equivalent Evidence、Recall@实际工具预算、MRR |
| Agent 行为 | Read、Cited、Query Reformulation、重复读取、提交纠正 |
| 答案 | Outcome、Expected Facts、Claim Obligations、Citation Policy、Hard Pass |
| 效率 | 索引时间与 Token、Candidate 数、模型/工具调用、总 Token、Wall Time、Provider Cost |
| 资源 | Python Worker RSS、Qdrant RSS/Storage/Point、Java 与网络开销 |
| 可靠性 | Parser Gap、FD、429、Retry、Timeout、Cancellation、Stale/Unresolvable Candidate |

相似度或精确 Anchor 指标可以用于诊断，但不能单独决定产品晋级。等价证据复核也不能抹掉排名靠后在
固定 `top_k` 下产生的真实失败。

## 产物

保留足够的事实来复现对照：

- 方法配置、代码 Revision、Retrieval Index Contract 和 Qdrant Collection；
- Corpus、Manifest、Split 和保存查询来源；
- 每个 Case 的 `harness_run`、Answer、Evidence Ledger、Citation Validation、Trace 和 Candidates；
- `query_results.jsonl`、`request_attempts.jsonl`、索引统计和结构化人工复核；
- 汇总 JSON、Markdown 报告，以及改善、退化、成本和未解决问题。

不要发布包含用户内容、Secret、受限论文正文或 Provider 限制数据的原始 Eval Dump。

## 晋级 Gate

候选方法只有在以下条件满足后才能替换当前产品参数：

1. 保持 Java 授权 Scope、论文/位置 Disclosure 和 Exact Read；
2. 保持 Canonical Evidence、历史引用重开和 Scope Leak 为 0；
3. Held-out Candidate、Read、Cited 和 Hard Pass 不低于对照；
4. 延迟、成本、索引和运维复杂度可接受；
5. 不用更大的 Candidate、模型调用或 Best-of-N 预算隐藏退化；
6. 通过产品 Smoke，并更新维护中的架构和评估文档。

当前 Sparse-only Qdrant BM25 已通过冻结查询上的相关性与性能 Gate。下一轮不应继续纠缠已删除的
Hybrid 方案，而应验证未见论文上的泛化，并把 Candidate 之后的模型查询、阅读和引用失败单独处理。
