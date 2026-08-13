# Golden Retrieval 与回答评测重构分析

日期：2026-08-01

状态：技术分析与实施方案。本文不修改产品数据库、检索设计或线上回答功能。

> 后续决策更新：新的文本检索单位见
> [Passage 检索层设计 Proposal](../engineering-evolution/architecture/passage-retrieval-proposal-2026-08-01.md)。该 Proposal 本次只改
> 产品 Passage 链路，不修改 Golden Data 或评测代码。本文的评测重构分析作为独立后续工作保留；当前
> PAGE/SECTION Location Eval 仅用于链路 Smoke 和历史诊断。

## 1. 结论

当前 Golden Data 的低总分不能直接解释为“检索差”或“回答差”。现有真实 MiniMax 稳定集运行中，
Retriever 基本找到了要求的位置，Required Claim 也大多表达正确；总分主要被逐 Markdown Block 的引用
附着规则和额外事实审计拉低。

必须把评测拆成至少五层：

1. MinerU 与 Reading Model 是否保留了正确原文。
2. 已知正确论文时，Location Retriever 能否召回正确位置。
3. Agent 是否找到论文、生成有效查询、读取并引用正确位置。
4. 最终回答是否表达了必需 Claim，且没有矛盾和错误事实。
5. 每个事实单元是否由正确证据支撑，引用是否附着在正确单元。

最终仍可以保留严格端到端 Pass，规则是以上必要层全部通过。但不能再只展示这个 Pass Rate，否则无法
判断问题发生在解析、索引、召回、Agent 策略、答案表达还是引用协议。

针对用户提出的三个核心问题，结论如下：

- 可以而且应该独立按召回率评测 Qdrant 返回的 Location，不运行 Agent 和回答模型。
- Agent 产出仍要评测，但应与 Retriever 分开；Agent Trace 评测的是“会不会用 Retriever”，不是
  Retriever 自身质量。
- 产出机制也要解决。短期要求完整事实和引用位于同一 Block；长期不要从渲染后的 Markdown 反推
  Claim 与引用关系，应让最终提交工具直接输出结构化 Answer Unit。

## 2. 当前真实数据链

```text
PDF
  -> MinerU sidecar
  -> content_list.json + middle.json + markdown + images + raw zip
  -> ParsedPaper
  -> Current Reading Model in MySQL
       paper_pages
       paper_sections
       paper_reading_elements
       paper_locations
  -> one Qdrant point per indexable paper_location
       lexical_bm25_v1 sparse vector
       dense_embo01_v1 dense vector, when sparse-dense-v1 is active
       metadata payload
  -> sparse search + dense search + RRF
  -> MySQL validates current model and hydrates canonical text
  -> Python Harness exposes Location candidates and read evidence to Agent
  -> Agent submits Markdown with evidence IDs
  -> Harness renders numeric citations and Sources
```

这里有两个容易混淆的“Chunk”：

- `chunk_info` 是 PDF 分片上传记录，用于大文件上传和合并，不是 RAG 文本分块。
- 当前检索单元实际叫 `PaperLocation`。产品口语中的“召回 Chunk”，在代码里应理解为召回
  `PAGE`、`SECTION`、`TABLE` 或 `FIGURE` Location。

## 3. MinerU 解析后得到什么

### 3.1 原始产物

`MinerUParserClient` 向 MinerU 提交 PDF 时开启表格、公式、Markdown、`content_list`、
`middle_json`、图片和 Zip 返回。异步任务完成后，客户端从 Zip 中提取：

- `*_content_list.json`
- `*_middle.json`
- 第一份 `.md`
- 完整 `raw-result.zip`

对应代码：

- `MinerUParserClient.java:112-130`
- `MinerUParserClient.java:259-316`
- `MinerUPaperPdfParser.java:28-84`

仓库内真实 Transformer 论文的 `content_list.json` 是一个按阅读顺序排列的 JSON 数组。典型元素为：

```json
{
  "type": "text",
  "text": "Attention Is All You Need",
  "text_level": 1,
  "bbox": [341, 184, 653, 208],
  "page_idx": 0
}
```

表格元素还包含 `table_caption`、`table_body` 和 `img_path`；图和图表包含 caption、图片路径和
bbox；公式包含 LaTeX 文本。

`middle.json` 的顶层主要是 `pdf_info`。每页包含 `page_idx`、`page_size`、`preproc_blocks` 等字段；
Block 内继续包含 `lines -> spans -> content`。它保留了更接近物理页面的文字块、阅读顺序和坐标。

### 3.2 内部统一格式

`MinerUOutputMapper` 将三种原始产物映射成 `ParsedPaper`：

```text
ParsedPaper
  metadata
  elements[]       <- content_list，统一成 TITLE/HEADING/PARAGRAPH/LIST/TABLE/FIGURE/CHART/FORMULA
  pages[]          <- middle.json 的物理页和 preproc_blocks
  tables[]
  figures[]
  formulas[]
  artifacts[]      <- content_list、middle、markdown、raw zip
  rawParserJson
```

关键行为：

- `content_list` 决定语义元素和主要 Reading Order。
- `middle.json` 的 `preproc_blocks` 决定物理页文本投影。
- `middle.json` 中缺失于 `content_list` 的 `code body` 会补成 LIST 元素。
- Markdown 不作为主要切块输入，只用于标题兜底和原始产物保留。
- MinerU 的 0-based `page_idx` 转成产品内 1-based 页码。
- bbox 转成 `mineru_1000`、`top_left_1000` 坐标。

对应代码：

- `MinerUOutputMapper.java:22-93`
- `MinerUOutputMapper.java:99-155`
- `MinerUOutputMapper.java:158-208`
- `MinerUOutputMapper.java:271-386`

## 4. 当前怎么“分块”

当前系统没有通用的 Recursive Text Splitter，也没有按 512/1024 Token 加 Overlap 的传统 RAG Chunker。
它构造的是多粒度 Reading Location。

### 4.1 PAGE

每个 PDF 物理页对应一个 `PaperPage` 和一个 `PAGE` Location。

- 优先拼接 `middle.json.preproc_blocks` 中该页的所有文本。
- 如果没有物理页投影，则回退到该页 `content_list` 元素文本。
- Location 的 canonical text 是完整 `page_text`。

代码：`PaperReadingModelBuilder.java:151-209`。

### 4.2 SECTION

`content_list` 遇到 Heading 后创建一个 Section Group，直到下一个 Heading。Heading 自身也包含在该
Section 文本中。一个 Section 可以跨页。

- Section 文本是组内全部可读元素以空行连接。
- 当前不按长度继续拆 Section。
- 第一处 Heading 之前且没有可用 `sectionTitle` 的前言元素可能只有 PAGE Location，没有 SECTION
  Location。

代码：`PaperReadingModelBuilder.java:322-435`。

### 4.3 TABLE 与 FIGURE

- 表格 caption 与解析后的表体组合成独立 `TABLE` Location。
- 图和图表用 caption 与可搜索描述组合成独立 `FIGURE` Location。
- 无页码、无 ID 或无可搜索文本时不创建独立 Location，但元素仍可保留在
  `paper_reading_elements` 中。
- panel-only chart caption 不创建独立 Location，避免把子图标签误当完整图证据。

代码：`PaperReadingModelBuilder.java:438-572`、`PaperReadingModelBuilder.java:720-766`。

### 4.4 PARAGRAPH 与 FORMULA

- 普通段落、标题、列表会保存在 `paper_reading_elements`，并被 PAGE/SECTION 聚合。
- 当前没有独立 `PARAGRAPH` Location。
- Formula 保留文本与结构数据，但当前明确标记 `FORMULA_LOCATION_DEFERRED`，不创建独立 Location。

因此当前可检索 Location 类型只有：

| Location 类型 | 文本来源 | 粒度 |
| --- | --- | --- |
| `PAGE` | 一页物理文本 | 固定一页 |
| `SECTION` | 一个标题下的聚合元素 | 可跨页，长度不固定 |
| `TABLE` | caption + 表体 | 单表 |
| `FIGURE` | caption + 描述 | 单图或图表 |

### 4.5 一个重要的不对称

Location 写入 Qdrant 前，`searchableText` 最多保留前 12,000 个字符；MySQL canonical read 返回完整文本。

这意味着：

- Retriever 实际只看超长 Section 的前 12,000 字符。
- Agent 一旦命中该 Location，又能读到完整 Section。
- 正确证据若只在超长 Section 尾部，该 Section 对 Qdrant 不可见，但重叠 PAGE Location 仍可能找回。

代码：`ReadingModelQdrantIndexService.java:313-331`。

### 4.6 Location 身份稳定性

`page_ref_`、`section_ref_`、`table_ref_`、`figure_ref_` 都由 Reading Model 构建时随机 UUID 生成。

- 仅重建 Qdrant：Location Ref 不变。
- 重跑 MinerU 或重建 Reading Model：Location Ref 会变化。
- Golden `accepted_locations` 因此绑定当前产品 Reading Model，不是跨解析版本的永久语义 ID。

这也是 Claim Location Audit 必须保留的原因。

## 5. 怎么传入 Qdrant

### 5.1 每个 Point 的来源

`ReadingModelQdrantIndexService` 从 MySQL 读取当前 Reading Model 的 Location，并按类型找到 canonical
搜索文本：

- PAGE -> `paper_pages.page_text`
- SECTION -> `paper_sections.section_text`
- TABLE/FIGURE -> `paper_reading_elements.searchable_text`

每个可索引 Location 生成一个 Qdrant Point：

```json
{
  "id": "deterministic UUID from paperId + modelVersion + locationRef",
  "vector": {
    "lexical_bm25_v1": {"indices": [], "values": []},
    "dense_embo01_v1": []
  },
  "payload": {
    "paper_id": "...",
    "model_version": "...",
    "location_ref": "...",
    "location_type": "SECTION",
    "page_number": 7,
    "page_end_number": 7,
    "section_path": "5.3 Optimizer",
    "element_types": ["heading", "paragraph", "formula"],
    "text_hash": "...",
    "parser_name": "MinerU",
    "parser_version": "self-hosted"
  }
}
```

Qdrant 不存 canonical 原文，也不直接成为引用证据。搜索后必须回 MySQL 校验 `paper_id + model_version +
location_ref`，并读取完整 canonical text。

代码：

- `ReadingModelQdrantIndexService.java:278-341`
- `ReadingModelQdrantIndexService.java:343-432`
- `QdrantClient.java:133-161`
- `CorpusRetrievalService.java:178-220`

### 5.2 当前运行实例快照

2026-08-01 直接读取本地 Qdrant collection 得到：

- collection：`paperloom_reading_locations_hybrid_v1`
- status：green
- Point：9,383
- `PAGE`：3,053
- `SECTION`：4,292
- `TABLE`：1,050
- `FIGURE`：988
- sparse vector：`lexical_bm25_v1`，Qdrant `modifier=idf`，on-disk index
- dense vector：`dense_embo01_v1`，1,536 维，Cosine

仓库默认配置是 `sparse-only-v1`，但当前本地 `.env` 明确启用 `sparse-dense-v1`。Spring 启动时通过
`DotenvEnvironmentPostProcessor` 加载该值，所以本地实际目标合同是 Hybrid。

## 6. 稀疏向量怎么来

当前稀疏向量不是外部 BM25 服务，也不是 SPLADE。它由 Java 本地编码：

1. 文本做 Unicode NFKC、lowercase、空白归一化。
2. 用 `[Unicode Letter | Number | _]+` 抽 Token。
3. 长度小于 2 的 Token 丢弃。
4. 每个 Token 取 SHA-256 前 32 位并清除符号位，得到 31-bit Qdrant sparse index。
5. 文档端记录 Term Frequency，并做 BM25 TF 长度归一化：

```text
tf_weight = tf * (k1 + 1) / (tf + k1 * (1 - b + b * dl / avgdl))
k1 = 1.2
b = 0.75
```

6. Query 端同样 Tokenize，每个唯一 Token 权重为 1。
7. Qdrant 的 `modifier=idf` 在检索时补 IDF。

`avgdl` 在全量重建时按所有 Location 计算并写入 `paper_retrieval_control`；单篇初始索引会复用已经激活
的合同。

代码：

- `SearchText.java:42-49`
- `LexicalBm25Encoder.java:23-48`
- `LexicalBm25Encoder.java:54-99`
- `RetrievalIndexContractService.java:44-101`
- `QdrantReadingModelReindexService.java:61-78`

当前稀疏实现有四个应由 Retriever Benchmark 暴露出来的风险：

- 不做词干化、同义词归一化或科学符号专门归一化。
- 中文连续文本可能被视为一个长 Token，不是中文分词。
- MinerU 解析出的 LaTeX 常带字符间空格，例如 `0 . 9 8`，短 Token 丢弃后词法信号会弱化。
- Collision 检查只在单篇 Location 内发现同 index 的不同 Token，不能发现跨 Location 的全局 Hash
  Collision。

这些不代表必须马上重写 Encoder，但必须通过 sparse-only Recall 曲线量化影响。

## 7. 稠密向量怎么来

Hybrid 合同下，索引服务把与稀疏侧相同且已截断到 12,000 字符的 `searchableText` 批量传给当前
Embedding Provider。

当前 Provider 是：

- MiniMax `embo-01`
- 1,536 维
- `POST /v1/embeddings`
- 文档向量请求体使用 `type: db`

查询时 `HybridReadingLocationRetriever` 再调用同一个 `EmbeddingProvider.embed(query)`，然后用
`dense_embo01_v1` 做 Cosine Search。

这里存在一个需要单独验证的合同问题：`MiniMaxEmbeddingProvider` 当前对文档和查询都硬编码
`type: db`。如果 MiniMax 的当前 Embedding API 对 Query 与 DB 使用非对称类型，那么 Query 应使用
Query 类型；现在的接口本身无法表达这个区别。不能仅凭猜测修改线上行为，应先通过 dense-only
Recall A/B 和官方合同确认。

代码：

- `ReadingModelQdrantIndexService.java:162-176`
- `MiniMaxEmbeddingProvider.java:38-52`
- `HybridReadingLocationRetriever.java:40-65`

## 8. Hybrid 怎么合并

查询链如下：

1. 稀疏侧最多取 100 个候选。
2. Hybrid 合同下，稠密侧最多取 100 个候选。
3. 用 RRF 合并，`k=10`：`score += 1 / (k + rank + 1)`。
4. Java 再校验 MySQL 当前 Reading Model。
5. 根据多论文覆盖和短查询 Lead Candidate 规则选择 Top K。
6. MySQL hydration 失败或旧 Model Location 会被丢弃。
7. API 最多向 Agent 返回 20 个 Location。

如果 Embedding Provider 配置、请求或 Dense Search 出错，Hybrid Retriever 会静默回退 Sparse。产品可用性
更好，但评测必须记录 `retrieval_mode=hybrid|sparse_fallback`，否则一次“Hybrid 结果”可能实际上没有 Dense。

代码：

- `QdrantReadingLocationRetriever.java:23-62`
- `HybridReadingLocationRetriever.java:33-114`
- `ReciprocalRankFusion.java:26-45`
- `CorpusRetrievalService.java:157-220`
- `CorpusRetrievalService.java:329-378`

## 9. 现有 Golden 到底测什么

稳定集当前有 5 篇论文、10 个 Case。Case 定义用户对话、Expected Outcome、Required Claim、Typed Fact
和 Citation Policy。Claim 定义：

- 必须表达的语义 Statement
- 可选 Fact Keys
- 每篇必要论文的 `accepted_locations`
- Claim-scoped Forbidden Papers

真实 Agent Run 的路径是：

```text
search_paper_candidates
  -> find_reading_locations
  -> read_locations
  -> evidence ledger
  -> submit_research_answer([[ev_...]])
  -> render [1] and Sources
  -> split rendered Markdown into blocks
  -> required claim judge + evidence/source rules + additional block judge
```

现有 Scorer 有六个维度：

- `outcome`
- `required_claims`
- `facts`
- `grounding`
- `source_policy`
- `additional_claims`

Case Status 使用严格 AND：任一维度 Fail，整个 Case Fail；无 Fail 但存在 `review_required` 时进入人工复核。

## 10. 为什么这次看起来特别差

### 10.1 真实结果分解

对 `minimax-real-stable-20260726-153035` 用当前分层 Scorecard 重新统计：

| 指标 | 结果 |
| --- | ---: |
| Strict End-to-End Pass | 2/10 = 20% |
| Required Answer Contract Pass | 9/10 = 90% |
| Evidence Contract Pass | 2/10 = 20% |
| Outcome | 10/10 Pass |
| Typed Facts | 5/5 applicable cases Pass |
| Required Claims | 9 Pass, 1 Fail |
| Source Policy | 10/10 Pass |
| Additional Claims | 2 Pass, 8 Fail |
| Agent Trace Location Candidate Recall | 100% |
| Agent Trace Read Recall | 93.3% |
| Agent Trace Citation Recall | 93.3% |

这组数据说明：

- 低总分不是由 Qdrant 大面积漏召回造成的。
- 必需答案语义总体不错，9/10 Case 通过。
- 主要失败集中在额外事实和逐 Block Evidence Contract。

### 10.2 Adam 示例

模型回答：

```markdown
Adam optimizer was used:

- beta1 = 0.9
- beta2 = 0.98
- epsilon = 1e-9

Note that beta2 differs from the standard Adam default of 0.999. [1]
```

Retriever 正确返回并读取了 `5.3 Optimizer`，其中明确包含 Adam、beta1、beta2 和 epsilon。但 Scorer 会：

1. 把引导句和三个 Bullet 分成四个 Block。
2. 三个 Bullet 没有同 Block Citation。
3. 没有一个 Block 单独表达“Adam + 三个参数”的完整 Required Claim。
4. 末尾 0.999 的比较虽然有 Citation，但该 Citation 只指向 Transformer 论文，不能支持 Adam 默认值。
5. `Sources` 被裁掉，不会把列表尾部来源反向附着到前面的 Bullet。

所以这个 Case 同时触发 Required Claim Missing 和多个 Unsupported Additional Block。这里既有模型多写了无证据
内容的真实问题，也有 Markdown 表达形式导致的脆弱性。

### 10.3 当前脆弱点

`answer_blocks()` 把 Paragraph、List Item、Table Row 分开；`Sources` 整段删除。Claim Judge 又要求一个
Block 单独表达完整 Claim，Grounding 只接受同 Block Evidence。对应代码：

- `answer_blocks.py:16-60`
- `answer_blocks.py:63-102`
- `claim_judge.py:402-424`
- `claim_judge.py:444-478`
- `scoring.py:285-382`

这个合同非常适合防止“把引用堆在答案末尾”，但不具备 Markdown 形式不变性。语义完全相同的 Paragraph、
Bullet List 和 Table 可能得到不同 Required Claim 结果。

另外，历史 `candidate_recall` 曾把“找到了正确论文”也算作“找到了正确 Location”。当前工作树已经将它拆成：

- `paper_discovery_recall`
- `candidate_recall`，只认 accepted Location

重新统计后这次真实运行的 Location Candidate Recall 仍为 100%，所以结论不依赖旧指标污染。

## 11. 目标评测架构

### 11.1 分层定义

| 层 | 测试对象 | 输入是否使用 Oracle | 核心指标 | 失败说明 |
| --- | --- | --- | --- | --- |
| L0 Corpus | MinerU、Reading Model、索引完整性 | Golden PDF/Anchor | parser coverage、location coverage、index coverage | 原文没有进入可检索语料 |
| L1 Paper Discovery | `search_paper_candidates` | 用户问题/身份线索 | Paper Recall@K、MRR | 没找到正确论文 |
| L2 Location Retriever | Java/Qdrant/MySQL Location Search | Oracle paper IDs + 固定 probe query | Location Recall@K、Claim Complete@K、MRR | 已知论文仍找不到证据位置 |
| L3 Agent Retrieval | Agent Tool Trace | 无 Oracle，真实对话 | discovery/candidate/read/cite funnel | Agent 不会生成查询、读取或选择证据 |
| L4 Answer Correctness | 最终答案文本 | Required Claims/Facts | Claim Coverage、Contradiction、Fact Accuracy | 答案意思错误或不完整 |
| L5 Evidence Contract | Answer Unit + Evidence | accepted locations | grounded unit rate、citation coverage、source policy | 事实没有正确证据或引用关系错误 |
| L6 End-to-End | 完整产品 Run | 无 | strict pass、cost、latency | 用户最终体验是否完整通过 |

L6 是发布 Gate，但 L0 到 L5 才是可诊断指标。

### 11.2 为什么 L2 必须独立

Agent Run 中没有召回正确 Location，可能有四种完全不同的原因：

- Agent 没发现正确论文。
- Agent 查询写差了。
- Retriever 排名差。
- Retriever 找到了，但 Agent 没读。

只看最终答案无法区分。L2 固定 Oracle Paper Scope 和人工 Probe Query，直接调用现有
`/internal/v1/corpus/locations/search`，才能测到产品 Retriever 本身。

## 12. Retriever 评测方案

### 12.1 最小可用版本

当前工作树已经有一个最小实现：

- Claim 增加独立的 `retrieval_queries`，不进入 Answer Claim Hash。
- `retrieval-eval` 不启动 Agent，直接调用 Java/Qdrant/MySQL 产品路径。
- `accepted_locations` 作为 Qrels。
- 输出 Recall@1/3/5/10/20、Claim Complete Rate 和 MRR。
- 多个 `accepted_locations` 是同一 Requirement 的可替代证据，不重复计分。

这一步能立刻回答：“正确论文已经知道时，产品 Location Retriever 能否找到支持 Claim 的位置？”

### 12.2 推荐的正式 Probe Schema

仅把 Query 挂在 Claim 上适合起步，但多论文 Claim、查询变体和语言鲁棒性需要显式 Probe：

```yaml
retrieval_probes:
  - probe_id: transformer_adam_en_natural_001
    claim_id: transformer_adam_hyperparameters
    query_text: Transformer Adam optimizer beta1 beta2 epsilon
    oracle_paper_ids: [attention_is_all_you_need_2017]
    query_family: natural
    language: en
    requirements:
      - paper_id: attention_is_all_you_need_2017
        accepted_locations:
          - section_ref_...
          - page_ref_...
```

每个 Claim 建议至少有：

- 原始自然问题改写
- 关键词式查询
- 不含答案值的语义改写
- 中文查询和英文查询，若产品支持中文用户问英文论文
- 多论文 Claim 的 per-paper probe 与 joint probe

不能直接把完整 Claim Statement 当 Query，否则容易把答案词泄漏给 Retriever，导致 Recall 虚高。

### 12.3 指标定义

以一个 `required_evidence` 为一个 Requirement：

```text
hit@K(requirement) = Top K 中存在同 paper_id 且 location_ref 属于 accepted_locations
Location Recall@K = hit 的 Requirement 数 / 总 Requirement 数
Claim Complete@K = 一个 Claim 的所有 Requirement 都 hit
MRR = 每个 Requirement 首个 Relevant Location 的 reciprocal rank 均值
```

建议报告：

- Macro Recall@K：每个 Probe 等权，避免大 Claim 支配结果。
- Micro Recall@K：每个 Requirement 等权。
- Claim Complete@K：对多论文比较和多跳任务尤其重要。
- MRR：观察首个正确证据是否靠前。
- Technical Error Rate：Java、Qdrant、Embedding 失败独立计数，绝不当作零召回混入质量指标。
- Empty Result Rate 与 Hydration Drop Rate。

Precision@K 暂不作为主指标。当前 Qrels 只标了已知可接受位置，未穷举全部相关 PAGE/SECTION/TABLE；未标候选
不等于错误候选。等 Qrels 达到较完整覆盖后再加入 nDCG@K 或 graded relevance。

### 12.4 Sparse、Dense、Hybrid 要分别评测

正式报告必须同时输出：

| 模式 | 目的 |
| --- | --- |
| sparse-only | 测词法精确匹配、公式参数、名称与数字 |
| dense-only | 测同义改写、跨语言与低词面重合 |
| hybrid | 测实际产品结果 |
| oracle-union | Top K 预算不受限时，两塔潜在覆盖上限 |

还应记录每个 Relevant Location 的 Sparse Rank、Dense Rank、Fused Rank。这样 RRF 变差时能判断是哪一塔拖累，
而不是只看一个 Fused Score。

当前 Java API 只返回分数，不返回实际模式和单塔 Rank。建议增加内部诊断字段：

- `retrieval_mode`
- `sparse_candidate_count`
- `dense_candidate_count`
- `dense_fallback_reason`
- `index_contract`
- `embedding_model`
- `sparse_rank`、`dense_rank`、`fused_rank`

这些字段只用于内部 Trace/Eval，不成为用户界面合同。

### 12.5 召回鲁棒性

对纯 Retriever，最有价值的不是重复运行同一 Query，而是 Query Metamorphic Test：

- 大小写变化
- Unicode `beta2`、`beta_2`、`beta₂`、`β2`
- `epsilon`、`eps`、`ε`
- 自然问句与关键词式查询
- 同义词和语序变化
- 中文问法与英文论文
- 带或不带论文标题

同一 Query Family 应报告 Mean、Worst Case 和离散度。一个 Probe 召回好但轻微改写就掉出 Top 10，才是真正的
Retriever 鲁棒性问题。

## 13. Agent Retrieval 评测方案

L3 不替代 L2，而是测 Agent 是否正确使用检索系统。每个 Required Evidence Requirement 记录漏斗：

```text
paper_discovered
  -> relevant_location_returned
  -> relevant_location_read
  -> relevant_evidence_cited
```

对应指标：

- Paper Discovery Recall
- Location Candidate Recall
- Read Recall conditioned on Candidate
- Citation Recall conditioned on Read
- Query Count、Read Count、无效 Tool Call、重复读取
- Token、Latency、Embedding Call、LLM Call 成本

必须保留两组 Location Recall：

- `oracle_retriever_recall`：L2 固定 Query 和 Oracle Paper Scope。
- `agent_candidate_recall`：L3 使用 Agent 实际生成的 Query 和 Tool Trace。

两者差值就是 Agent Retrieval Policy 的损失。

## 14. 回答产出机制怎么改

### 14.1 短期：不改协议，约束输出形状

最小修改已经反映在当前工作树的 Agent Instruction：

- 每个事实 Claim 与 Evidence Marker 放在同一 Paragraph、List Item 或 Table Row。
- 精确事实问题优先用一个自包含句子表达主体和全部值。
- 不在无证据情况下补默认值、比较或解释。

推荐输出：

```markdown
The original Transformer used Adam with beta1 = 0.9, beta2 = 0.98, and epsilon = 1e-9. [[ev_x]]
```

不推荐输出：

```markdown
Adam was used:

- beta1 = 0.9
- beta2 = 0.98
- epsilon = 1e-9

[[ev_x]]
```

这能显著减少当前假阴性，但本质上仍依赖模型遵守 Markdown 形状。

### 14.2 中期：结构化 Answer Unit

长期方案是修改 `submit_research_answer`，让 Claim 与证据关系成为提交数据，不从渲染后 Markdown 猜：

```json
{
  "outcome": "answered",
  "answer_units": [
    {
      "markdown": "The original Transformer used Adam with beta1 = 0.9, beta2 = 0.98, and epsilon = 1e-9.",
      "evidence_ids": ["ev_x"]
    }
  ]
}
```

Harness 负责：

1. 校验 Evidence ID 已读且可引用。
2. 在每个 Unit 末尾渲染数字引用。
3. 拼出最终 Markdown 和 Sources。
4. 将原始 `answer_units` 保存到 Run Artifact。
5. Scorer 直接按 Unit 评测，不再用正则重建 Citation Association。

这不会改变最终用户看到的设计，只会使内部产出合同更确定。

### 14.3 Answer Correctness 与 Evidence Contract 分开

推荐固定三个一级 Score：

```text
answer_correctness
  = outcome + required_claims + typed_facts + contradiction

evidence_contract
  = required_evidence + citation_attachment + source_policy + factual_unit_support

strict_end_to_end
  = answer_correctness AND evidence_contract AND no_technical_failure
```

严格 Pass 不放宽，但报告不再用 20% Strict Pass 掩盖 90% Required Answer Pass。

### 14.4 Additional Claims 应重命名并去重

当前 `additional_claims` 实际审计每个事实 Block，包括 Required Claim 的重复表达，所以名称会误导。建议改成
`factual_unit_support`：

- 每个事实 Answer Unit 都必须被其 Attached Evidence 支持。
- Required Claim Coverage 只判断“是否说对、是否完整”。
- Grounding 判断 Required Claim 对应 Unit 是否绑定必需论文与可接受 Location。
- Factual Unit Support 判断该 Unit 内有没有证据不支持的额外子句。

这样“Required Claim 正确，但多写了一个无证据的 0.999 默认值”会得到：

- Answer Correctness：Pass
- Required Grounding：Pass
- Factual Unit Support：Fail
- Strict End-to-End：Fail

诊断清楚，而且没有降低严谨性。

## 15. Scorer 自身怎么做鲁棒性验证

评测器也必须被测，不能只校准一个 Prompt 后默认稳定。

### 15.1 语义等价变体

为每个核心 Claim 固定多种人工等价答案：

- 单句 Paragraph
- Bullet List
- Markdown Table
- 主语放前或放后
- β/epsilon 的 Unicode、ASCII、LaTeX 写法
- 简洁回答与带非事实过渡句的回答

预期规则：

- `answer_correctness` 对这些表达形式应保持一致。
- `evidence_contract` 只在 Citation Association 真正改变时变化。

### 15.2 对照变体

同时固定最小错误变体：

- 0.98 改成 0.999
- Adam 改成 AdaMax
- BERT encoder 改成 decoder
- 删除多论文 Claim 的一侧
- Citation 换成错误论文
- 正确论文但未审核 Location
- 加入证据没有支持的因果解释

这些变体用于分别测 False Pass 和 False Fail，不能只要求 False Pass 为 0。

### 15.3 Judge 报告

Judge Gate 至少报告：

- Claim Match Precision/Recall/F1
- Contradiction Precision/Recall
- Factual Unit Support Precision/Recall
- 格式变体一致率
- `uncertain` 比例
- 重复运行一致率
- Calibration 与 Holdout 分开结果

Judge 不确定、协议错误和未知 Location 必须进入 `review_required`，不能直接算产品 Fail，也不能自动 Pass。

## 16. Golden 数据怎么调整

### 保留

- Claim Catalog
- Typed Facts
- `required_evidence` 和多个 `accepted_locations`
- Claim-scoped Forbidden Papers
- Parser Anchor Audit
- Current Product Corpus Map
- Saved Agent Runs 与不可覆盖的历史报告

### 增加

- 显式 Retrieval Probe Catalog
- Query Family 与语言标签
- per-paper 和 joint multi-paper probes
- Sparse/Dense/Hybrid Rank Observation
- Answer Unit 格式鲁棒性 Fixture
- Corpus/Index Snapshot Identity

### 不建议

- 不要回到“必须命中一个 exact quote anchor 才算回答正确”。
- 不要把 Claim Statement 直接当所有 Retrieval Query。
- 不要自动把正确论文中的任意新 Location 升级为 Accepted。
- 不要用一个总分混合 Retriever、Agent、Answer 和 Grounding。
- 不要为了提高 Pass Rate 放宽无证据额外事实。

## 17. 实施顺序

### Phase 1：独立 Retriever 基线

目标：先回答 Qdrant/Java Location Retrieval 到底好不好。

- 完成稳定集显式 Probe。
- 启动 Java 后运行 `retrieval-eval`。
- 输出 Recall@1/3/5/10/20、Complete@K、MRR 和 Technical Errors。
- 固化产品 corpus map、Reading Model Version、Qdrant Contract。
- 扩展到 24 Case 对应 Claim。

验收：同一次报告可以指出每个 Miss 的 Query、Requirement、Relevant Location 和实际 Top 20。

### Phase 2：单塔诊断与可观测性

目标：知道 Sparse、Dense 和 RRF 各自贡献。

- 内部 API 返回实际 retrieval mode 和单塔 rank。
- 对同一 Probe 跑 sparse-only、dense-only、hybrid。
- 统计 silent sparse fallback。
- 验证 MiniMax Query/DB Embedding Type 合同。
- 加 Query Metamorphic Families。

验收：每次 Hybrid Regression 都能定位到 Sparse、Dense、Fusion 或 Hydration。

### Phase 3：回答评测拆分

目标：严格但可诊断。

- 固化 `answer_correctness`、`evidence_contract`、`strict_end_to_end` 三个一级分数。
- 将 `additional_claims` 重命名为 `factual_unit_support`。
- 增加格式等价与最小错误 Fixtures。
- 重新校准 Claim Judge，报告 False Pass 与 False Fail。

验收：Adam 的正确 Bullet 与正确 Paragraph 在 Answer Correctness 上一致；Citation Association 不同只影响
Evidence Contract。

### Phase 4：结构化 Answer Unit

目标：删除从最终 Markdown 反推 Evidence Association 的脆弱路径。

- 扩展 Final Tool Schema。
- Harness 统一渲染 Citation 和 Sources。
- Run Artifact 保存 Answer Units。
- Scorer 直接消费 Answer Units。
- 前端继续显示相同 Markdown，不改视觉设计。

验收：Paragraph/List/Table 的渲染变化不再改变内部 Claim-to-Evidence Association。

## 18. 发布 Gate 建议

数据集目前较小，不建议立刻拍脑袋设一个百分比阈值。第一轮先固化 Count Baseline，之后用以下规则：

- Retriever：稳定集任何已通过 Requirement 不得从 Top 10 掉出；新增 Miss 必须人工解释。
- Expanded Retriever：按 Query Family 报告 Worst Case，不只报 Mean。
- Technical Error：独立 Gate，不能混进 Recall。
- Answer Correctness：Required Claim 和 Typed Fact Regression 为阻断项。
- Evidence Contract：错误论文、未知 Citation、无证据额外事实为阻断项。
- Judge：Holdout 出现 Unsafe False Pass 时阻断；False Fail 超过冻结基线也阻断。
- End-to-End：至少重复运行多次，报告 Pass 分布和失败层，不只报一次结果。

## 19. 需要优先确认的技术风险

按优先级排序：

1. 当前 Query 与 Document 都使用 MiniMax `type: db`，先验证官方合同和 dense-only Recall。
2. Hybrid 的 Dense Failure 静默回退 Sparse，Eval 报告必须可见。
3. 超长 Section 只索引前 12,000 字符，需要统计 Relevant Evidence 落在截断区后的比例。
4. `location_ref` 在 Reading Model 重建时变化，Retriever Baseline 必须绑定 Corpus Snapshot。
5. Sparse Analyzer 对中文、LaTeX 数值和希腊字母不够稳定，需要 Query Family 量化。
6. 31-bit Token Hash 的跨 Location Collision 没有全局检测。
7. Stable 10 Case 太小，必须同时维护 Expanded 与格式鲁棒性集。

## 20. 当前工作树状态与验证

本次分析前，工作树中已经存在一组未提交的评测改动，本文没有覆盖或回退它们：

- 独立 `retrieval-eval` 初版
- Claim `retrieval_queries`
- Location Candidate 与 Paper Discovery 指标拆分
- Answer/Evidence/Strict Scorecard
- Agent 同 Block Citation 指令

Focused Python Tests：

```text
13 tests passed
```

重新评分历史真实 Run 后，得到本文第 10.1 节的 90% Answer Contract、20% Evidence Contract 和 20%
Strict End-to-End。该重新评分没有重新调用 MiniMax。

本地 Java 服务在分析时未运行，因此本文没有把新 `retrieval-eval` 的 Live Recall 报告伪装成已完成结果。
Qdrant Collection 配置和 Point 统计已直接核验。

## 21. 最终决策建议

采用“分层评测 + 严格最终 Gate”，不要在“放宽评分”和“维持脆弱评分”之间二选一：

1. 先把 Retriever Recall 从 Agent Run 中剥离，建立 Oracle Paper Scope 的独立产品检索基线。
2. 保留 Agent Trace Funnel，专门评估 Agent 的检索策略损失。
3. 将 Answer Correctness 与 Evidence Contract 分开报告，但 Strict Pass 仍要求两者都通过。
4. 短期用自包含同 Block Claim 修复输出，长期迁移到结构化 Answer Unit。
5. 用格式等价、查询改写和最小错误对照集测试评测器自身的鲁棒性。

这个方案不会通过“降低标准”制造高分。它做的是让每一个低分都能明确说明：究竟是文本没解析出来、位置没
索引、Retriever 没召回、Agent 没读取、答案没说对，还是证据没有真正支持该事实。
