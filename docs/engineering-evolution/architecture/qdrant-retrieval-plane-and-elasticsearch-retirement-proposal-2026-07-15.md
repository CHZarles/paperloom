# Qdrant 检索平面与 Elasticsearch 退役 Proposal

日期：2026-07-15
状态：核心 Cutover 已实施，存量回填与产品 Smoke 待执行
范围：产品 Reading Model 检索、Java/Python 边界、Qdrant 引入、Elasticsearch 退役

## 结论

PaperLoom 不应把 Qdrant 嵌入每个 Python Harness 实例，也不应继续让产品 Harness 在每一轮把
授权范围内的全部 Reading Element 从 MySQL 加载进内存后再做全量 BM25。

目标架构是：

```text
Java 产品/检索平面
├── 权限与锁定论文范围
├── Current Reading Model
├── Embedding Provider 与额度
├── Qdrant 索引生命周期
└── 内部 Corpus Retrieval API

Python Harness 编排平面
├── OpenAI Agents SDK Runner
├── MiniMax 模型与工具循环
├── 会话研究记忆
├── Run Budget 与取消
└── 通过 Corpus Gateway 搜索和读取，不管理检索基础设施
```

Elasticsearch 可以退役，但不能直接删除。先让 Qdrant 基于 canonical
`paper_reading_elements` 完成索引、查询、重建和删除闭环，再切换 Harness 产品 Corpus，最后删除
旧的 `paper_chunks`、`paper_search` 及其运行依赖。

本 Proposal 不把 Qdrant 变成 Evidence 来源。Qdrant 只返回 Candidate `location_ref`；最终可引用
内容仍必须从 MySQL Current Reading Model 精确读取。

## 实施状态

代码落地提交：`652f4dc`。后续的索引原子性、响应大小和取消边界收敛在紧随其后的修复提交中。

| 范围 | 当前状态 |
| --- | --- |
| Java Corpus API、权限复核、Current Model 校验和 MySQL Exact Read | 已实现 |
| Java Qdrant Dense/Sparse Index、RRF、删除、重建和写后计数校验 | 已实现 |
| Python Product Runtime 切换到 Java Corpus Gateway | 已实现 |
| ASGI、复用连接池、Deadline、响应大小上限和请求取消检查 | 已实现 |
| 模型可见 Tool 名称、Schema、Prompt、Golden Data 和 Reading Model 路径 | 未修改 |
| Elasticsearch 生产代码、依赖、配置、Compose、脚本和主文档 | 已退役 |
| 现有 76 篇 Current READY Reading Model 的 Qdrant 回填 | 待显式执行；会产生 Embedding 用量 |
| 定向产品 Research / Citation Reopen Smoke | 待回填后执行 |
| Shadow 对照、100x 容量、HA 与多维度 Embedding 评估 | 不属于本次快速 Cutover，保留为扩展验证 |

这次实施按后续“尽快收敛、不要过度测试”的要求，完成产品边界切换和确定性验证，没有运行全量
MiniMax Golden、100x Corpus 或多副本容量实验。因此本文后半部分的这些内容仍是扩展 Gate，不应被
误读成已经取得的容量结论。

## 为什么现在需要作出这个决定

### 当前规模

2026-07-15 本地产品数据：

```text
Current READY papers:         76
Current Reading Elements: 25,625
Average elements per paper: 337.2

Elasticsearch paper_search:      76 docs
Elasticsearch paper_chunks:  23,707 docs
Elasticsearch index storage: 464.6 MB
Elasticsearch container RSS: about 1.61 GB
```

如果论文数量扩大 100 倍，并保持当前平均 Reading Element 数量：

```text
Papers:             about 7,600
Reading Elements:   about 2,562,500
2048-d float32 raw vectors: about 19.6 GiB
```

这还没有计算 HNSW、Sparse Index、Payload、文本、分片和副本。几千篇论文不是极端规模，不能继续
把“每轮完整加载授权 Corpus”当成长期产品边界。

### 当前产品路径的扩展瓶颈

当前一次研究回合会：

1. `ResearchHarnessService` 根据 `scope.paper_ids` 调用
   `DockerMySqlProductCorpusStore.load_dataset()`；
2. Python 通过 `docker exec ... mysql` 读取论文、全部 Current Reading Element 和 Visual Asset；
3. 这些行被完整转换为内存 `GoldenDataset`；
4. `ReadingCorpusTools` 为本轮构建所有 `ReadingDocument`；
5. 每次 `find_reading_locations` 再对范围内文档分词、计算 BM25 统计和排序。

这个实现适合 Golden Fixture 和少量论文的本地产品验证，不适合作为多机产品 Corpus Adapter：

- `docker exec` 依赖本机容器名，不能作为 Kubernetes/多机数据库连接方式；
- 相同论文在每轮、每个 Harness Replica 重复读取和构造；
- MySQL 流量随请求量和 Replica 数量近似线性放大；
- 大范围查询的 CPU 与内存随 Reading Element 数量线性增长；
- 产品 Corpus 被强行装进 `GoldenDataset`，让 Fixture 便利形状反过来限制线上架构。

### Elasticsearch 为什么不继续作为目标

Elasticsearch 本身能够做 Keyword、Dense Vector 和权限过滤，旧 RAG 实验也证明它适合大规模
文献搜索。但当前产品 Harness 不使用它，现有索引又来自 `paper_text_chunks`，不是 canonical
Reading Model：

- 旧 ES 文档没有稳定的 Current Reading Model `location_ref`；
- `PaperTextChunk` 和 `PaperReadingElement` 是两条不同投影；
- 通过页码、Chunk ID 或文本相似度回映射会产生不稳定 Evidence Identity；
- Harness 不需要 Elasticsearch 的完整搜索平台、JVM 和 IK Plugin 运行负担；
- 用户当前需要的是授权论文内的精确研究，不是全库推荐系统。

因此不把旧 ES 文档搬进 Qdrant，也不在 Harness 中重新接回 ES。新索引直接从 Current Reading
Model 构建。

## 设计原则

### 1. Java 管数据生命周期，Python 管模型编排

Java 已经负责 PDF 上传、Parser、Reading Model、权限、配额、Kafka 和会话锁定范围，因此也负责：

- Reading Model READY 后触发索引；
- 调用 Embedding Provider 并记录用量；
- Qdrant Upsert、Delete、Reindex 和版本切换；
- 校验用户、组织、公开论文和锁定范围；
- 向 Python 提供稳定的内部 Corpus Retrieval API。

Python 不持有 Qdrant 管理凭据，不创建 Collection，不执行索引迁移，也不自行决定可访问论文范围。

### 2. Qdrant 是 Candidate Index，不是 Source of Truth

Qdrant 可以保存用于搜索的文本、Dense/Sparse Vector 和导航 Payload，但这些内容都是可重建投影。

```text
MySQL Current Reading Model   canonical truth
Qdrant                       rebuildable candidate index
Python Run Context           request-local authorization and evidence state
```

任何 Qdrant Hit 都必须满足：

- `paper_id` 位于本次 Java 锁定范围；
- `model_version` 是该论文当前 READY 版本；
- `location_ref` 能在 MySQL Current Reading Model 中解析；
- `read_locations` 成功读取后，才能生成 Evidence ID。

### 3. 不改变模型可见协议

本次改造不修改：

- MiniMax 生产模型选择；
- Agent Prompt；
- `search_paper_candidates`、`find_papers_by_identity`、`find_reading_locations`、
  `read_locations` 和 `submit_research_answer` 的模型可见名称；
- Golden Case、Anchor、Reading Model 文件或现有路径；
- `location_ref -> Evidence ID -> Citation` 的核心链路。

变化只发生在 Corpus Tool 背后的产品 Adapter。

### 4. 不做静默语义降级

Qdrant 不可用、索引版本不一致或返回无法解析的 `location_ref` 时，产品 Adapter 返回明确结构化错误
和诊断，不猜测页面、不扩大论文范围、不把旧 ES Chunk 当成 Evidence，也不添加 Case-specific
Fallback。

Fixture 和本地测试可以显式选择 In-memory Adapter；这不是线上运行时的隐式回退。

## 目标架构

### Ingestion / Indexing

```text
PDF
-> MinerU ParsedPaper
-> PaperReadingModelService
-> Current READING_MODEL_READY
-> existing upload/Kafka vectorization job
-> Java ReadingModelQdrantIndexService
   -> read canonical PaperReadingElement rows
   -> build retrieval text
   -> generate dense embeddings
   -> generate sparse lexical representation
   -> upsert new model version
   -> verify counts and resolvable location refs
   -> retire old model-version points
```

索引动作必须幂等。重复消费同一事件不能产生重复 Point 或混合版本。

### Research Query

```text
ChatHandler
-> ProductReadingConversationService
-> PythonResearchHarnessClient
-> Python Harness Runner
-> model calls find_reading_locations
-> Java Corpus Retrieval API
-> Qdrant dense + sparse retrieval
-> Java revalidates scope/current model
-> MySQL batch hydrates top candidates
-> Python returns model-visible location cards
-> model calls read_locations
-> Java reads exact Current Reading Model content
-> Python creates Evidence ID and final citation
```

Java Conversation Service 调用 Python 后，Python 不能回调 Conversation/Chat Endpoint。内部 Corpus
Retrieval API 是独立、无模型、无会话副作用的数据平面接口，不会再次调用 Harness，因此不存在逻辑
递归。初期可作为 Spring 内部模块，吞吐需要时再拆成独立 Retrieval Service。

## Qdrant 数据模型

### Collection

建议使用按 Embedding Contract 版本管理的 Collection，而不是按论文创建 Collection：

```text
paperloom_reading_elements_dense_sparse_v1
```

Embedding 模型或维度不兼容时创建新 Collection，通过 Alias 完成切换，不在同一 Vector Name 下混用
不同维度。

### Point ID

```text
UUIDv8(first 128 bits of sha256(paper_id + "\n" + model_version + "\n" + location_ref))
```

Point ID 必须稳定、可重建、与 Java/Python 运行实例无关。

### Payload

最小 Payload：

```text
paper_id
model_version
location_ref
reading_element_id
source_object_id
element_type
page_number
section_path
text_hash
parser_name
parser_version
owner_user_id
org_tag
is_public
index_generation
```

`index_generation` 是 Java 写入批次标识。新一代 Point 全量 Upsert 并通过精确 Count 校验后，Java
才删除同一论文的旧 Generation，避免“先删后写”在失败时把可用 Candidate 整批清空。

可以保存 `searchable_text` 供 Lexical/Sparse 索引和诊断，但返回给模型的 Preview 应由 Java 从当前
MySQL Reading Model Hydrate，避免陈旧 Payload 直接进入回答上下文。

### Vector

至少包含两个命名检索面：

```text
dense   semantic embedding
sparse  lexical representation
```

Dense-only 不能替代论文检索中的公式、指标名、数据集名、缩写、引用编号和精确术语；Sparse-only 又
难以覆盖自然语言改写。两路召回取并集，使用确定性 RRF 或等价 Fusion，再执行现有多论文覆盖、元素
类型和 Page Grounding 选择。

2048 维不是永久决定。迁移前应在 768、1024、2048 维上比较 Candidate Recall、延迟、磁盘和内存，
再冻结生产 Embedding Contract。需要时采用 On-disk Vector 和 Scalar Quantization，但不能在没有
检索质量数据时先压缩再解释损失。

## Java 内部 Corpus API

模型看不到以下接口，它们只服务 Python Corpus Adapter。

### 1. Scope Paper Search

```http
POST /internal/v1/corpus/papers/search
```

用途：支持现有 `search_paper_candidates` 和 `find_papers_by_identity` 在锁定范围内读取论文 Metadata。

请求核心字段：

```json
{
  "request_id": "...",
  "conversation_id": "...",
  "user_id": 1,
  "scope_paper_ids": ["..."],
  "query_text": "...",
  "identity": {},
  "offset": 0,
  "limit": 20
}
```

### 2. Reading Location Search

```http
POST /internal/v1/corpus/locations/search
```

请求核心字段：

```json
{
  "request_id": "...",
  "conversation_id": "...",
  "user_id": 1,
  "scope_paper_ids": ["..."],
  "paper_ids": ["..."],
  "query_text": "...",
  "section_query": "...",
  "element_types": ["paragraph", "table"],
  "page_from": null,
  "page_to": null,
  "top_k": 12
}
```

Java 必须验证 `paper_ids` 是 `scope_paper_ids` 子集，并重新检查用户权限。Qdrant 返回结果不能绕过
这两个检查。

响应可以包含内部分数，但 Python Model-facing Mapper 不暴露实现细节：

```json
{
  "locations": [
    {
      "paper_id": "...",
      "model_version": "...",
      "location_ref": "...",
      "section": "...",
      "page": 3,
      "element_type": "paragraph",
      "preview": "...",
      "dense_score": 0.0,
      "sparse_score": 0.0,
      "fused_score": 0.0
    }
  ],
  "matched_count": 0,
  "returned_count": 0,
  "index_version": "..."
}
```

### 3. Exact Location Read

```http
POST /internal/v1/corpus/locations/read
```

只接受已由本轮 Search 披露的 `location_ref`，由 Python Run Context 保持披露集合，Java 再次验证
范围、权限和 Current Model。响应包含创建 Evidence 所需的完整 Canonical 字段。

## Python Harness 改造

### Corpus Interface

产品路径不再把全部 Reading Element 转成 `GoldenDataset`。引入一个小而深的 Corpus Interface：

```text
CorpusReader
- search_papers(...)
- find_papers_by_identity(...)
- search_locations(...)
- read_locations(...)
```

两个真实 Adapter：

```text
InMemoryCorpusReader
  Golden Fixture、离线 Audit、单元测试

JavaCorpusGatewayReader
  产品 Runtime，通过内部 Corpus API 使用 Qdrant + MySQL
```

`ReadingCorpusTools` 继续拥有模型可见 Schema、授权阶梯、Disclosure 和 Evidence Ledger，但不再拥有
产品全量文档列表和索引实现。这样 Fixture 与产品通过同一个 Tool Interface 测试，复杂度不会泄漏到
Agent Runtime。

### Request Contract

Java 发送给 Python 的 Research Request 增加 `user_id` 和内部 Corpus Endpoint 配置/身份；现有
`scope.paper_ids` 保留并继续作为 Python 本轮不可扩大的授权上限。模型 Prompt 和 Tool Arguments 不
增加用户、权限、Qdrant 或 Embedding 字段。

### 连接和并发

产品 Harness 应同时完成：

- 用 ASGI Server 替换 `ThreadingHTTPServer`；
- 使用可复用、带连接池的 HTTP Client 调用 Java Corpus API；
- 设置连接池、请求 Deadline、最大响应体和取消传播；
- 不在每轮创建新的 Retrieval Client；
- 保持每个 Run 的 Disclosure/Evidence State 请求隔离。

## 横向扩展设计

### Harness Replica

Python Harness 完全无本地持久索引。新增 Replica 不需要复制向量文件或等待全库加载，只需要访问：

- MiniMax Provider；
- Java Corpus Retrieval API；
- 可选 Eval Dump 目标。

Harness 扩容主要受模型 Provider 并发、Corpus API 和请求连接数限制。

### Java Retrieval Replica

Java Retrieval Module 无本地索引状态，可以横向扩容，所有 Replica 共享 MySQL 与 Qdrant。Embedding
Query 可以在这一层统一执行、缓存、计费和限流，避免 Python 绕过现有 Provider/Quota 管理。

### Qdrant

目标规模约 2.5M Reading Element 时，不预设“一个轻量单节点永远够用”。实施前用合成 100x Corpus
完成容量测试，再确定：

- Shard 数量；
- Replication Factor；
- RAM/SSD 比例；
- On-disk Vector；
- Quantization；
- Snapshot/Restore 时间；
- Reindex 双写期间的额外空间。

生产 HA 至少需要跨故障域副本；单节点可以作为开发和迁移验证环境，不能作为最终高可用结论。

### 权限过滤

Qdrant Payload 不是最终权限源。Java 用用户/组织/公开状态构造候选过滤，并对 Hit 做权威后验验证。
锁定对话 Scope 仍然是不可扩大的上限。

对于几千个任意 Paper ID 的超大 Terms Filter，必须做专门性能测试，不能假设它与几十个 ID 等价。
如果产品需要大型 Collection Scope，应使用稳定的 Collection/Library Membership 标识辅助过滤，并在
Java 侧验证最终 Paper ID 仍属于锁定快照。

## Elasticsearch 退役范围

### 不迁移旧 ES 数据

`paper_chunks` 和 `paper_search` 都是可重建投影。不要把旧 Vector 或 Chunk ID 原样复制到 Qdrant。
Qdrant 从 Current Reading Model 重新生成，借此修正旧索引与 Canonical Location 不一致的问题。

### Cutover 前必须替代的能力

1. Reading Model READY 后的索引任务；
2. Dense/Sparse 查询；
3. 论文删除和 Current Model 重建；
4. 索引健康、文档计数和 Orphan 检查；
5. Embedding Usage、Rate Limit 和失败状态；
6. 当前论文库搜索 UI 的保留、重写或明确删除；
7. Reset、Seed、Reindex、Deployment 和 Backup 流程。

### 最终删除对象

Cutover 通过后删除或重写：

- `EsConfig`；
- `EsIndexInitializer`；
- `PaperSearchIndex`；
- `ElasticsearchService`；
- `HybridSearchService`；
- 旧 `PaperRetrievalService` ES 查询实现；
- `PaperChunkDocument` 和 `PaperSearchDocument`；
- `paper_chunks.json`、`paper_search.json`；
- Maven Elasticsearch Client/RestClient 依赖；
- Docker Compose Elasticsearch Service 和 Volume；
- Elasticsearch 环境变量、生产校验、Health Check、Reset Script 和文档；
- 旧的 `paper_chunks`、`paper_search` Index；
- 仅服务旧 ES 的上传状态和 Reindex 行为。

不要在同一个 Commit 中同时切换检索和删除全部旧设施。先完成可验证 Cutover，再做删除 Commit，避免
失败时无法区分新检索问题和基础设施清理问题。

## 分阶段实施

### Phase 0：冻结合同与基线

目标：证明迁移没有偷偷改变模型协议和 Evidence 语义。

- 固定当前 Tool Schema 快照；
- 保存当前 BM25 Candidate/Read/Cited 基线；
- 保存产品 Scope、权限和 Citation Reopen Smoke；
- 记录当前 Corpus Load、BM25 Prepare、Retrieval、MySQL Rows/Bytes 和进程 RSS；
- 不调用模型即可复算的检索指标全部离线运行。

退出条件：基线可重复，Golden/Prompt/路径未修改。

### Phase 1：Java Corpus Retrieval API

目标：先移除 Python 直接 `docker exec mysql`，但暂时可以使用 Java/MySQL 现有检索实现。

- 新增内部 Paper/Location Search/Read API；
- Java 重新验证用户权限和锁定范围；
- Python 新增 `JavaCorpusGatewayReader`；
- Fixture 继续使用 `InMemoryCorpusReader`；
- 产品 Runtime 不再完整构建 `GoldenDataset`。

退出条件：模型可见 Tool 输出与现有合同一致，产品引用仍能重开。

### Phase 2：Qdrant Canonical Index

目标：建立从 Current Reading Model 到 Qdrant 的完整 Projection。

- 增加 Qdrant 开发服务和配置；
- 定义 Collection、Point、Payload、Dense/Sparse Contract；
- Reading Model READY 事件触发幂等 Upsert；
- Paper Delete/Model Replace 能删除旧 Point；
- 增加 Count、Orphan、Current-version Audit；
- 不接入线上回答，先 Shadow Build。

退出条件：所有 Qdrant Point 都能解析到 Current `location_ref`，不存在跨版本混合。

### Phase 3：Shadow Retrieval

目标：Qdrant 查询不影响答案，只保存最小候选事实供离线比较。

- 对同一 Query 同时运行当前检索和 Qdrant；
- 保存 Candidate `paper_id/location_ref/rank`、延迟和 Index Version；
- 不保存新的在线 Judge 结论；
- 离线比较 Candidate Recall@K、Required Anchor Recall、Page/Type Coverage 和 Scope Leak；
- 对差异 Case 读取原文分析，不修改 Golden Data。

退出条件：Qdrant Hybrid 至少不低于当前 BM25，并证明 Dense 分支解决了真实语义漏召回，而不是只
改变排名。

### Phase 4：产品 Cutover

目标：`find_reading_locations` 使用 Qdrant，`read_locations` 继续精确读 MySQL。

- 默认产品 Adapter 切换到 Java Corpus Retrieval API + Qdrant；
- 保持 Tool Schema/Prompt 不变；
- Qdrant Hit 必须通过 Current Model 和 Scope Validation；
- 设置明确 Deadline、响应大小和错误状态；
- 运行真实 MiniMax 定向回归和浏览器引用重开验证。

退出条件：没有新增技术失败、权限泄漏或 Citation Mapping 回归。

### Phase 5：100x 容量与横向扩展验证

目标：证明架构不是只在当前 76 篇论文上工作。

- 构造约 7,600 篇、2.5M Reading Element 的合成或隔离数据集；
- 测试 1/3/6 个 Harness Replica；
- 测试 1/3 个 Java Retrieval Replica；
- 测试 Qdrant Shard/Replica 配置；
- 测试 Warm/Cold Query、Reindex、Snapshot Restore、Rolling Restart；
- 验证大 Scope Filter 和权限后验验证；
- 记录吞吐、p50/p95/p99、CPU、RSS、磁盘和 Network。

退出条件：检索平面扩容近似线性，不再把全量 Element 发送给 Harness，MySQL 查询量与 Top Candidate
数量相关而不是与全部 Scope Element 数量相关。

### Phase 6：Elasticsearch 退役

目标：删除重复基础设施，不保留两套长期索引真相。

- 关闭 ES 写入；
- 观察一个稳定窗口；
- 删除旧搜索 UI 或切换其后端；
- 删除 ES 代码、依赖、配置、脚本和文档；
- 删除 ES Container 和 Volume；
- 更新 Deployment、Quick Start、Configuration、Architecture 和 Onboarding；
- 重新执行 Upload、Research、Delete、Reindex、Reference Reopen 和 Restart Smoke。

退出条件：新环境不启动 Elasticsearch 也能完成完整产品主流程。

## 验证矩阵

### 确定性测试

- Point ID 对同一 `(paper_id, model_version, location_ref)` 稳定；
- Upsert 重放不增加 Point；
- New Model Version 不返回 Old Location；
- 删除论文后无残留可检索 Point；
- Qdrant Hit 不能绕过 `scope.paper_ids`；
- 未披露 `location_ref` 不能直接 `read_locations`；
- Qdrant Payload 文本不能直接生成 Evidence；
- Exact Read 返回的 Text Hash 与 Current Reading Model 一致；
- Java Corpus API 错误不会暴露 Qdrant、SQL、Token 或密钥。

### 检索验证

- Current/Expanded Golden Candidate Recall；
- Required Anchor Recall@K；
- Stable/Expanded Saved Query Replay；
- 多论文 Coverage；
- 表格、图片说明、公式和跨页位置；
- Dense-only、Sparse-only、Hybrid 对照；
- 768/1024/2048 维度对照；
- Scope Leak 必须为 0。

Golden 用来验证回归，不用来驱动 Case-specific Rule，也不通过修改 Anchor、Prompt、路径或 Reading
Model 提高分数。

### 产品验证

- 单论文、多论文和大 Scope Research；
- Follow-up 使用 Previous Evidence；
- Citation Reference Reopen；
- 上传后 Index Lag 可见；
- Reading Model Replace；
- Paper Delete；
- Qdrant Rolling Restart；
- 浏览器取消和 Harness Deadline；
- 新环境完全不启动 Elasticsearch。

## 观测指标

在线只记录事实，不引入新的语义 Judge：

```text
corpus_api_latency_ms
query_embedding_latency_ms
qdrant_dense_latency_ms
qdrant_sparse_latency_ms
qdrant_fusion_latency_ms
candidate_hydration_latency_ms
candidate_count
stale_candidate_count
unresolvable_location_count
scope_rejection_count
index_lag_seconds
qdrant_collection_points
qdrant_error_count
```

离线从这些事实派生 Recall、Miss 分类和容量报告。Harness Eval Capture 继续保持 Append-only，不把
Qdrant 评测字段扩散到回答对象。

## 接受标准

功能接受：

- 模型可见 Tool Schema 和 Prompt 不变；
- 返回的 `location_ref` 100% 能解析到 Current Reading Model；
- Scope Leak 为 0；
- `read_locations` 仍是唯一 Paper Content Evidence 入口；
- Citation Reopen 保持成功；
- 没有 Case-specific 检索规则。

质量接受：

- Hybrid Candidate Recall 不低于当前 BM25；
- Dense 分支至少解决一组可复现的语义漏召回，否则不进入默认路径；
- 不以更多 Model Call、Best-of-N 或在线 Judge 掩盖检索问题。

扩展接受：

- 100x 数据下 Harness 不加载全量 Scope Element；
- MySQL Hydration 行数受 Top Candidate/Read 数量约束；
- 1/3/6 Harness Replica 没有索引同步和本地持久化步骤；
- Qdrant Snapshot/Restore 和 Rolling Restart 通过；
- 资源预算由容量报告确认，不凭当前 76 篇数据外推。

退役接受：

- 完整产品启动不再依赖 Elasticsearch；
- Upload、Index、Research、Delete、Reindex、Health 和 Reset 均不引用 ES；
- Maven、Docker、Environment、Script、Test 和 Docs 中没有失效的 ES 生产依赖；
- 旧 ES Index 删除不会丢失 Canonical 数据。

## 风险与控制

### Qdrant 成为新的单点

控制：开发环境允许单节点；生产按容量测试确定 Shard/Replica，保留 Snapshot、Restore 和 Rolling
Restart Gate。不能因为 Harness 横向扩展就默认一个无副本 Qdrant 已经高可用。

### Java -> Python -> Java 调用链

控制：回调只进入无模型、无会话写入的 Corpus Data API；使用独立线程池、连接池、Deadline 和内部
认证。若负载证明同进程资源互相影响，再把 Retrieval Module 独立部署，不提前拆微服务。

### Embedding Model 迁移

控制：Collection/Alias 按 Embedding Contract 版本切换；新旧索引并行构建，验证后原子 Cutover，
不在同一 Vector Field 混合维度。

### 权限 Payload 陈旧

控制：Qdrant 过滤只做候选收窄，Java 对返回 Paper/Location 做权威后验验证；用户权限变化不依赖
向量索引更新速度才能生效。

### Hybrid 提升不成立

控制：先 Shadow，后 Cutover。若 Dense 没有解决真实 Miss，保留 Java Corpus API 和 Lazy Hydration，
不为“已经部署 Qdrant”强行让它进入默认检索。

## 明确不做

- 不把 Qdrant Client、Collection 管理和 Index Worker 塞进 Agent Runtime；
- 不修改 MiniMax Prompt 来教模型理解 Qdrant；
- 不把 ES Chunk ID 映射成虚假的 Reading Model Location；
- 不引入另一个在线 LLM Judge；
- 不通过扩大 Candidate 数量掩盖错误索引；
- 不同时长期维护 ES、Qdrant 和 Python 全量内存三套生产检索；
- 不在迁移期间修改 Golden Data、Anchor、Reading Model 文件或用户路径。

## 最终状态

迁移完成后的主流程：

```text
Java locks authorized paper scope
-> Python MiniMax Harness chooses research actions
-> Java/Qdrant retrieves candidate canonical locations
-> Java/MySQL reads exact Current Reading Model content
-> Python creates Evidence and citations
-> Elasticsearch no longer exists in the production stack
```

该状态把模型编排、产品权限、检索索引和 Canonical Evidence 分开：Harness 保持轻量且可横向扩展，
Java 保持产品数据和权限所有权，Qdrant 承担数百万 Reading Element 的共享 Candidate Retrieval，
MySQL 继续承担可审计、可重建、可重开的论文事实来源。
