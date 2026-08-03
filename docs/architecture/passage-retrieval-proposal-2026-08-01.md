# Passage 检索方案 Proposal

日期：2026-08-01

状态：Proposed

## 1. 一句话结论

在现有 Reading Model 之上增加一个派生的 Passage 层：

```text
PASSAGE/TABLE/FIGURE
  -> 负责证据检索、排名和精确引用

PAPER SOURCE QUOTE
  -> 负责 Agent 的实际阅读和引用

PAGE/SECTION
  -> 负责论文结构查找和导航

Source Quote + MinerU Source Span
  -> 负责最终引用和前端 PDF 展示
```

Passage 是检索锚点。Agent 先用 `search_paper_content` 找到相关 Passage，再用 `read_source_quotes` 读取
精确原文。需要论文目录时调用 `get_paper_structure`，需要同一 Section 的其他内容时带 `section_ref` 继续
搜索，不引入额外的 Section Map。

Passage 不替代 PAGE、SECTION、MinerU Source Span 或 Source Quote，也不直接显示在前端。

当前还没有上线，不引入 Passage Contract 版本、Passage Build 版本、Blue/Green、Shadow 或兼容层。
切分规则变化时，直接重建 Passage 和 Qdrant 派生索引。

这里保留现有 `PaperReadingModel.model_version`。它不是语义化版本号，也不是 Agent 参数，而是“一次论文
解析快照”的内部身份。当前代码在每次重建 Reading Model 时生成 `rm_<timestamp>_<uuid>`；同一 Paper
重新解析后，Page、Section、Element、Location 和 Qdrant Point 都必须属于新的 Model Version。Query/Read
只接受 Current READY Version，避免旧索引和新正文混用。本方案不新增独立 Passage Version。

身份层级固定为：

```text
paper_id         -> 哪篇论文
model_version    -> 这篇论文的哪次解析快照，仅后端使用
location_ref     -> 该快照中的哪个 Page/Section/Table/Figure/Passage
source_quote_ref -> 从该 Location 读取出的哪个单页引用
```

## 2. 为什么要加 Passage

当前 Qdrant 的候选单位是：

```text
PAGE / SECTION / TABLE / FIGURE
```

PAGE 和 SECTION 适合论文导航，但对精确问答偏大：

- 一个 Section 可能包含多个主题，单个稠密向量会稀释局部语义；
- 同一段原文会同时出现在 PAGE 和 SECTION，重复占据 Top K；
- 当前超长 Location 只索引前 12,000 字符，Section 尾部可能不可检索；
- Agent 只能知道整页或整节命中，无法区分 Section 中真正相关的原文。

所以要把两个职责分开：

```text
PAGE/SECTION：大范围结构坐标
PASSAGE：用于排名和定位原文的文本锚点
SOURCE QUOTE：用于 Agent 精确阅读、引用和前端展示
```

TABLE、FIGURE 已经是合理的原子证据单位，继续独立检索。

## 3. 目标与边界

### 3.1 目标

- 普通文本以 Passage 参与 Sparse/Dense/RRF 排名；
- Passage 能回溯到所属 Section、Page、Reading Element 和 PDF BBox；
- Passage 命中后只读取明确选中的精确原文，不默认读取所属完整 Section；
- Agent 通过 `get_paper_structure` 理解论文结构，通过 `search_paper_content(section_refs=...)` 补充同节内容；
- Passage 保存全篇和 Section 内顺序，用于审计和规范读取；
- PAGE、SECTION 仍可通过 Agent Tool 查找和读取；
- Qdrant Payload 同时保存规范正文和 Source Span，Read 不再为每个 Hit 回 MySQL Hydrate 正文；
- 前端引用继续使用 Source Quote、页码、章节和 Visual Regions；
- 现有论文不重跑 MinerU，也能从 Current READY Reading Model 生成 Passage。

### 3.2 不做

- 不改 MinerU 请求和原始输出格式；
- 不使用 LLM 决定 Passage 边界；
- 不同时修改 Embedding、Sparse Encoder、RRF 或增加 Reranker；
- 不把 PAGE、SECTION、PASSAGE 放进同一个 Top K；
- 不做 TABLE Row/Cell 级切分；
- 不改变 Chat 和引用面板的视觉设计；
- 不保留旧 Tool 名称 Alias；
- 不修改 Golden Data、Golden Schema、Retriever Eval 或回答 Scorer；
- 不维护多套 Passage 切分版本；
- 不做 Blue/Green 或无停机发布设计。

## 4. 目标数据链

```text
PDF
  -> MinerU
  -> ParsedPaper
  -> Current READY Reading Model in MySQL
       paper_pages
       paper_sections
       paper_reading_elements
       paper_locations
  -> deterministic Passage builder
       paper_passages
       PASSAGE paper_locations
  -> Corpus Location Search
       evidence family
         -> Qdrant PASSAGE/TABLE/FIGURE
         -> Sparse + Dense + RRF
         -> Qdrant canonical read projection
         -> Current Model version validation
       structure family
         -> MySQL PAGE/SECTION lookup
  -> Agent evidence read
       exact matched Passage
       page-scoped PaperSourceQuotes
  -> Run disclosed_source_quote_refs
  -> submit_research_answer cites source_quote_ref
  -> frontend page/section/visualRegions
```

## 5. Passage 从哪里生成

Passage 必须从 PaperLoom 已持久化的 Current READY Reading Model 生成。

允许输入：

```text
PaperReadingElement
PaperSection
PaperPage
PaperLocation
Current PaperReadingModel
```

不允许直接读取：

```text
MinerU result zip
content_list.json
middle.json
markdown
ParsedPaper
```

原因是线上授权、当前模型判断、Location Ref、Section、Page 和 BBox 都已经收敛到 Reading Model。
Passage 是 Reading Model 的派生检索投影，不是第二套解析结果。

现有论文可以直接从 MySQL 回填 Passage，不需要重新上传 PDF 或重新调用 MinerU。

## 6. 确定性切分算法

“确定性”表示：同一个 Reading Model 和同一组切分参数，每次生成相同的 Passage 边界和 Passage Ref。

第一版参数：

```text
minimum estimated tokens = 120
target estimated tokens  = 450
soft maximum             = 650
hard maximum             = 800
source overlap           = 0
```

这些只是当前开发参数。因为系统还未上线，调整参数后直接全量重建，不保留旧 Passage 版本。

### 6.1 元素规则

| Reading Element | 处理方式 |
| --- | --- |
| TITLE | 作为文档上下文，只在实际首个 Passage 中保留一次原文 |
| HEADING | 强制开始新的 Section Passage |
| PARAGRAPH/TEXT_BLOCK | 按 Reading Order 合并 |
| LIST/LIST_ITEM | 尽量保持完整，超长时按 Item 拆分 |
| FORMULA | 与相邻解释段落合并，必要时独立 |
| CODE | 保持原子，超长时按行拆分 |
| FOOTNOTE/ASIDE | 按阅读顺序保留，不跨 Section 强行合并 |
| TABLE | 不进入普通 Passage，继续使用 TABLE Location |
| IMAGE/FIGURE/CHART | 不进入普通 Passage，继续使用 FIGURE Location |
| HEADER/FOOTER | 排除，避免每页重复噪声 |
| UNKNOWN | 有正文则保留并记诊断，无正文则排除 |

已经附着到 TABLE/FIGURE 的 Caption Fragment 不再进入普通 Passage，避免同一 Caption 重复召回。

### 6.2 Section 和 Page 边界

- Section 是硬边界，Passage 不跨 Section；
- 第一处 Heading 前的内容可以是 `section_ref = null` 的 Unsectioned Passage；
- Page 是软边界，同一 Section 的连续正文可以跨相邻 Page；
- Passage 跨页时保存 `page_number_from/to`，每个 Source Span 保留自己的真实 Page 和 BBox；
- Page 必须连续，不能跳过中间 Page 后继续合并；
- 页码或 Reading Order 倒退时构建失败，不猜测顺序。

Section 归属优先通过 `PaperSection.reading_order_from/to` 判断，不只比较 Section Title 字符串。

跨页只属于 Passage 检索和阅读层。`read_source_quotes` 会按 Page get-or-create 并返回多个
`PaperSourceQuote`，每个 Quote 仍只对应一页，因此不要求前端展示一条跨页引用。

### 6.3 合并和拆分

对每个 Section：

1. 按 `reading_order` 排列元素；
2. 去掉 Header/Footer 和已归属 TABLE/FIGURE 的 Caption；
3. TABLE/FIGURE 是顺序屏障：先结束当前 Passage，再保留独立 TABLE/FIGURE Location；
4. 顺序合并同一 Section 内的相邻短文本元素，允许跨相邻 Page；
5. 达到 Target 后在下一个自然元素边界结束；
6. 加入下一个元素会超过 Soft Max 时先结束当前 Passage；
7. Paragraph 优先按句子，List 优先按 Item，Code 优先按行拆分；
8. 任何类型仍超过 Hard Max 时执行最终硬切分；
9. 最后一个 Passage 过短时，只在合并后不超过 Hard Max 的情况下向前合并；
10. 不跨 Section 合并短尾；
11. 生成 Source Span、Ordinal 和 Hash。

最终硬切分使用同一个 Estimated Token Counter，重复截取“不超过 Hard Max 的最长 Unicode Code Point
前缀”。前缀内有空白或标点边界时优先在最近边界结束；没有边界就直接按 Code Point 截断。该规则覆盖
超长单句、单个 List Item、单行 Code、Formula 和 UNKNOWN 文本，保留连续 `char_from/char_to`，不得丢字
或制造 Overlap，因此合法 Reading Model 不会因为原子文本超长而整篇构建失败。

第一版不复制正文做 Overlap。需要更多上下文时，Agent 使用 Section Ref 继续检索，不自动读取整个 Section。

### 6.4 Index Text 和引用原文分开

发送给 Sparse/Dense Encoder 的 `index_text` 可以是：

```text
[section title]

[passage content]
```

`content_text` 只保存 Reading Model 原文。Section Title 作为额外索引上下文时不能伪装成正文引用。

## 7. Passage 数据模型

只新增一个实体 `PaperPassage`，不新增 Passage Build/Contract 版本表。

```text
paper_passages
  id                       BIGINT PK
  passage_ref              VARCHAR(96) UNIQUE NOT NULL
  paper_id                 VARCHAR(32) NOT NULL
  model_version            VARCHAR(64) NOT NULL
  parent_section_id        VARCHAR(96) NULL
  parent_section_ref       VARCHAR(96) NULL
  section_title            VARCHAR(500) NULL
  page_number_from         INT NOT NULL
  page_number_to           INT NOT NULL
  reading_order_from       INT NOT NULL
  reading_order_to         INT NOT NULL
  document_ordinal         INT NOT NULL
  section_ordinal          INT NULL
  content_text             LONGTEXT NOT NULL
  index_text               LONGTEXT NOT NULL
  content_hash             CHAR(64) NOT NULL
  estimated_token_count    INT NOT NULL
  source_span_json         LONGTEXT NOT NULL
  created_at               DATETIME NOT NULL
```

约束：

```text
UNIQUE passage_ref
UNIQUE (paper_id, model_version, document_ordinal)
INDEX  (paper_id, model_version, parent_section_id, section_ordinal)
INDEX  (paper_id, model_version, parent_section_id, reading_order_from)
INDEX  (paper_id, model_version, page_number_from, page_number_to)
```

`reading_order_from/to` 取 Passage 第一个和最后一个 Source Span 的 Reading Order。Passage 全文顺序以
`document_ordinal` 为准，同一 Section 内顺序以 `section_ordinal` 为准；不存重复的 Prev/Next 指针。

每个 Passage 同时创建一个正式 `PaperLocation`：

```text
location_ref      = passage_ref
location_type     = PASSAGE
source_object_id  = passage_ref
page_number       = page_number_from
page_end_number   = page_number_to
section_title     = section_title
content_kind      = PASSAGE_TEXT
source_span_json  = Passage Source Span
```

这样现有 Search、Read、Authorization 和 Evidence Trace 仍然统一使用不透明的 `location_ref`。

### 7.1 Location Ref 的定义、作用和来源

`location_ref` 的本质是：**Reading Model 一次解析快照中，一个可读取内容单元的持久、不透明 ID**。
它不是即时生成的搜索结果，不是 PDF 坐标，也不是最终 Citation。形式化表示为：

```text
resolve(location_ref)
  -> (paper_id, model_version, location_type, canonical_content, source_span)
```

其中 `location_ref` 只负责回答“要读取哪个内容单元”；`source_span` 负责描述原文所在页码和坐标；
`source_quote_ref` 负责标识最终可以引用的单页原文。

它只承担三个作用：

1. Search/Structure 用它标识返回的内容单元，Agent 可以只根据 Preview 选择要继续读取的 Ref；
2. 后端用它连接 MySQL Reading Model、Qdrant Payload 和后续创建的 Source Quote；
3. Source Quote 保存其来源 `location_ref`，使最终引用可以追溯到原始 Passage/Table/Figure/Page/Section。

`xxx_ref` 不是一种新对象；`xxx` 只是类型占位符，`ref` 是 Reference 的缩写。Ref 是后端在创建记录时分配并
保存的唯一字符串 ID，作用类似公开版数据库主键，不包含需要 Agent 解析的业务含义。

Agent 合同中只需要理解两个字段：

```text
location_ref     -> 指向一个可读取位置，存于 paper_locations.location_ref
source_quote_ref -> 指向一条可引用单页原文，存于 paper_source_quotes.source_quote_ref
source_span      -> 记录该原文在 PDF 中的页码、Reading Element 和 BBox
```

`page_ref_...`、`section_ref_...`、`passage_ref_...` 只是 `location_ref` 字符串当前使用的不同前缀。Agent 根据
旁边的 `location_type` 判断类型，把整个 Ref 原样传给下一个 Tool，不拆解、不构造它。

Location Ref 在检索之前就已经存在，不是 Agent 或 Qdrant 临时生成的：

| Location Type | Ref 来源 | 例子 |
| --- | --- | --- |
| PAGE | Reading Model Builder 创建 `PaperLocation` | `page_ref_...` |
| SECTION | Reading Model Builder 创建 `PaperLocation` | `section_ref_...` |
| TABLE | Reading Element Location Builder 创建 `PaperLocation` | `table_ref_...` |
| FIGURE | Reading Element Location Builder 创建 `PaperLocation` | `figure_ref_...` |
| PASSAGE | Passage Builder 确定性生成 `passage_ref`，并创建对应 `PaperLocation` | `passage_ref_...` |

现有 PAGE/SECTION/TABLE/FIGURE Ref 使用带类型前缀的 UUID，在一次 Model Version 内有效；重新解析论文会
生成新 Ref。Passage Ref 虽然是确定性的，但生成输入包含 `model_version`，所以它也只属于该解析快照。
`model_version` 不需要暴露给 Agent，Agent 只使用后端已校验并披露的 Ref。

Qdrant 自己的 Point ID 是 `paper_id + model_version + location_ref` 的确定性 Hash，只供 Qdrant 使用，不是
Agent Ref。

Qdrant Point 携带 `location_ref`、规范正文和 Source Span，作为可直接读取的派生投影；MySQL Reading Model
仍是构建和重建它的事实来源。

Ref 的披露和使用顺序：

```text
Reading Model / Passage Builder creates PaperLocation
  -> Qdrant indexes location_ref, or MySQL exposes structure ref
  -> search_paper_content / get_paper_structure discloses location_ref
  -> Agent selects relevant refs
  -> read_source_quotes(location_refs)
  -> validate authorization + Current Model version
  -> read canonical content + Source Span from Qdrant payload for evidence refs
  -> get-or-create page-scoped PaperSourceQuote
```

Agent 不能自行构造 Ref；`read_source_quotes` 只接受本 Run 已由 Search 或 Structure 披露的 Ref。

### 7.2 形式化示例

设论文、解析快照和 Passage 分别为：

```text
P = "paper_attention_2017"
M = "rm_20260803_abcd"
L = "passage_ref_91f2"
```

MySQL 中存在唯一 Location 记录：

```text
PaperLocation[L] = {
  paper_id: P,
  model_version: M,
  location_type: PASSAGE,
  page_number: 7,
  page_end_number: 8
}
```

定义 `loc(ref)` 为按 `paper_locations.location_ref` 查询 Location 的函数，则：

```text
loc("passage_ref_91f2") = PaperLocation[L]
```

`search_paper_content(P, query)` 返回 `L`。Agent 调用 `read_source_quotes([L])` 后，因为该 Passage 覆盖两页，
后端返回两个 Quote：

```text
Q7 = "source_quote_a71c"
Q8 = "source_quote_b82d"

PaperSourceQuote[Q7] = {
  location_ref: L,
  page_number: 7,
  content: page7_text,
  source_span: page7_span
}

PaperSourceQuote[Q8] = {
  location_ref: L,
  page_number: 8,
  content: page8_text,
  source_span: page8_span
}
```

定义 `quote(ref)` 为按 `paper_source_quotes.source_quote_ref` 查询 Quote 的函数，则：

```text
quote(Q7).location_ref = L
quote(Q8).location_ref = L
```

最终引用必须满足：

```text
cited_source_quote_refs ⊆ disclosed_source_quote_refs(current_run)
```

例如 Agent 只引用第 8 页：

```text
cited_source_quote_refs = [Q8]
answer_markdown = "... [[source_quote_b82d]]"
```

Passage Ref 根据以下内容确定性生成：

```text
paper_id
+ existing model_version
+ ordered reading_element_id and char ranges
+ content_hash
```

切分边界改变时 Source Range 会改变，因此直接生成新的 Ref。旧 Passage 行和 Qdrant Point 在全量重建时删除。

## 8. Source Span 与溯源

每个 Passage 保存组成它的完整 Source Span：

```json
{
  "spans": [
    {
      "reading_element_id": "reading_element_...",
      "parser_element_id": "debug-only-parser-id",
      "source_object_id": "...",
      "element_type": "PARAGRAPH",
      "role": "BODY",
      "page_number": 7,
      "page_location_ref": "page_ref_...",
      "reading_order": 128,
      "char_from": 0,
      "char_to": 356,
      "bbox": {}
    }
  ]
}
```

规则：

- `reading_element_id` 是 Current Reading Model 内的规范元素身份；
- `parser_element_id` 只用于审计，不作为 Agent 或前端主坐标；
- Element 被拆开时必须保存 `char_from/char_to`；
- Passage 可以包含连续多页的 Span，每个 Span 必须保留自己的 Page 和 BBox；
- 不能把多个不连续 BBox 合成一个大框。

## 9. Passage Read

每个 Passage 保存：

```text
document_ordinal
section_ordinal
```

Ordinal 是 Passage 顺序事实来源。构建时验证：

```text
ordinal 连续
```

Agent 把 `search_paper_content` 返回的 Passage Ref 传给 `read_source_quotes`：

```json
{
  "location_refs": ["passage_ref_seed_a", "passage_ref_seed_b"]
}
```

后端在一次 Read 中完成：

```text
Passage seeds
  -> 校验授权和 Current Reading Model
  -> 从 Run 缓存或 Qdrant 读取规范正文和 Source Span
  -> 按 Page get-or-create PaperSourceQuote
```

返回：

```text
source_quotes
  本次明确读取并可直接引用的 PaperSourceQuote
  单页 Passage 返回一个 Quote，跨页 Passage 按 Page 返回多个 Quote
  每个 Quote 有独立 source_quote_ref、共享的 passage_ref 和单页 Source Span
```

跨页 Passage 的多个 Quote 按 Page 和 Source Span 顺序排列，拼接后仍是完整 Passage 正文；它们共享
`location_ref = passage_ref`，但 `source_quote_ref` 不同。Agent 可以只引用支持当前 Claim 的页面，也可以
同时引用多个页面。

需要同一 Section 的更多内容时，Agent 使用已披露的 `parent_section_ref` 再调用
`search_paper_content(section_refs=[...], query_text=...)`，不由 Read 自动扩展上下文。

这里的 Agent Run 是“一次用户问题开始，到 `submit_research_answer` 结束”，不是整个 Conversation。

Harness 只维护本 Run 已披露的 Location Ref 和可引用 `source_quote_ref` Set，不维护 Section Map 状态。

约束：

- Seed 必须已经由 `search_paper_content` 或 `get_paper_structure` 披露；
- `read_source_quotes.location_refs` 最多 20 项；
- Section、Passage 和结构化证据必须属于同一 Paper 和 Current Reading Model；
- Agent 只能引用本 Run 中 `read_source_quotes` 返回过的 `source_quote_ref`，不能引用 Search Preview；
- Unsectioned Passage 不伪造 Section Ref；
- Read 不自动加载父 Section、相邻 Passage 或其他 Location。

## 10. 检索和排名

### 10.1 Evidence Retrieval

不新增一个叫 `paperloom_reading_evidence` 的 Collection。直接使用当前 `QDRANT_COLLECTION` 配置指向的
现有 Collection，停止服务后删除并重建其中的派生 Point。

```text
当前本地配置示例：paperloom_reading_locations_hybrid_v1
```

重建后只索引：

```text
PASSAGE / TABLE / FIGURE
```

PAGE/SECTION 不进入 Evidence Qdrant，因此不会影响 Passage 的 BM25 文档长度、IDF、Dense Rank 和 Top K。

Passage Point 保存：

```json
{
  "paper_id": "...",
  "model_version": "...",
  "location_ref": "passage_ref_...",
  "location_type": "PASSAGE",
  "parent_section_ref": "section_ref_...",
  "page_number": 7,
  "page_end_number": 8,
  "reading_order_from": 128,
  "reading_order_to": 130,
  "document_ordinal": 42,
  "section_ordinal": 3,
  "source_element_types": ["HEADING", "PARAGRAPH"],
  "content_text": "Reading Model 中的规范原文",
  "source_span_json": {"spans": []},
  "content_hash": "...",
  "estimated_token_count": 436
}
```

第一版保持当前 Sparse Encoder、Dense Embedding 和 RRF 不变，只改变检索单位。

Qdrant Hit 必须执行：

```text
Qdrant hit
  -> authorized paper filter
  -> payload.model_version == Current READY model_version
  -> content_hash / source_span validation
  -> non-empty content validation
  -> API candidate
```

`index_text` 只用于 Sparse/Dense 编码，可以包含 Section Title 等索引上下文；`content_text` 和
`source_span_json` 是从 MySQL Reading Model 构建出的精确读取投影，不能使用 `index_text` 生成 Quote。

Qdrant Payload 的 Model Version、Hash、正文或 Source Span 不合法时直接拒绝该 Hit 并记录 Projection
Mismatch。由于本方案在停止服务后整体重建 Collection，不设计 Query 时逐 Hit 回 MySQL Hydrate 的双读路径。

`search_paper_content` 只负责这一条 Evidence Retrieval 路径。它接受 `paper_ids`、`query_text`、可选
`section_refs`、Page 范围、Element Type 和 Top K，返回不可引用的 Preview 与 Location Ref，不承担论文
结构查询。Qdrant Hit 虽然带完整正文，但模型可见结果仍只发送短 Preview；Harness 在本 Run 内按
`location_ref` 保留完整 Payload，只有后续 `read_source_quotes` 才把选中的正文送给 Agent。这样正文直接
来自 Qdrant，又不会让一次 Top K Search 把全部全文塞进上下文。

### 10.2 PAGE/SECTION Structure Lookup

PAGE、SECTION 没有被删除，继续保存在 `paper_pages`、`paper_sections` 和 `paper_locations`。

它们不需要向量排名，直接通过 MySQL 查找：

```text
page_from/page_to
  -> PAGE Locations

section_query
  -> Section Title 匹配

无条件列出
  -> 按 display_order 返回
```

`get_paper_structure` 只负责这一条 MySQL Structure Lookup 路径：

```json
{
  "paper_ids": ["paper_id_..."],
  "structure_type": "SECTION"
}
```

该请求按 `PaperSection.display_order` 返回 Section 标题、层级、页码和 Section Ref，就是按需 Outline。因为
这个能力已经存在于 Structure Lookup，`read_source_quotes` 不再自动附带整篇论文 Outline。

Structure Lookup 只返回结构元数据，不返回 `section_text`、`page_text`、Passage 正文或 `source_quote_ref`，也不能
作为引用：

```text
SECTION -> section_ref, title, level, path, display_order, page_from/page_to
PAGE    -> page_ref, page_number, section_title
```

不传筛选条件时，SECTION 模式返回全部 Outline 条目，但仍然只是标题目录。需要正文时，Agent 再把明确选中
的 Section/Page Ref 交给 `read_source_quotes`，避免“看目录”就把整篇论文正文塞进上下文。

接口规则：

```text
structure_type = SECTION
  -> 可选 section_query；不传 query 时返回完整 Outline

structure_type = PAGE
  -> 使用 page_from/page_to 返回 PAGE Locations
```

Evidence Search 和 Structure Lookup 不再通过 `location_types` 塞进同一个 Tool，Agent 根据任务直接选择语义
明确的 Tool。

Passage Candidate 返回：

```text
parent_section_ref
section_title
section_path
section_ordinal/section_count
section_page_from/section_page_to
passage_ordinal_in_section/passage_count_in_section
page_location_refs
```

这些父级 Ref 经授权和 Current Model 校验后一起披露。Agent 可以从 Passage 跳到整页或整节，不能自行构造
父级 Ref。`section_path` 和 Section 顺序根据现有 `PaperSection.section_level + display_order` 现场生成，
不新增目录表。

`search_paper_content` 仍然按 Passage 排名，不把整个 Section 放回 Qdrant。Section 信息只是轻量结构坐标；
真正读取时，`read_source_quotes` 只返回明确选中 Ref 的精确正文和 Source Quote，不自动扩展上下文。

## 11. Passage 与前端证据

Passage 是检索对象，不是前端展示对象。

### 11.1 只保留一个可引用对象

可引用对象只保留现有 `PaperSourceQuote`。`read_source_quotes` 直接按 Page get-or-create 并返回它：

```text
PaperSourceQuote
  identity: source_quote_ref
  content: 规范单页原文
  source: paper_id + model_version + passage_ref + page + Source Span + MinerU BBox
  storage: paper_source_quotes
  consumer: Agent 引用、历史引用、Reference Detail、PDF Evidence Panel
```

Passage 构建时不创建 Quote。只有 Agent 调用 `read_source_quotes` 读取某个 Location Ref 时才创建或复用
Quote。`search_paper_content` 返回的 Preview 没有 `source_quote_ref`，因此不能引用。

正文读取来源按 Ref 类型固定：

```text
PASSAGE/TABLE/FIGURE -> 优先读取本 Run 缓存的 Qdrant Hit Payload
PAGE/SECTION         -> 读取 MySQL Reading Model；它们不进入 Qdrant
```

两条路径都先校验授权和 Current Model，不做“Qdrant 失败再回 MySQL”的隐式兜底。

### 11.2 读取与引用

```text
read_source_quotes(passage_ref)
  -> 单页 Passage: get-or-create 1 个 PaperSourceQuote
  -> 跨页 Passage: 每页 get-or-create 1 个 PaperSourceQuote
  -> Agent 直接引用 source_quote_ref
  -> submit_research_answer 校验该 Ref 本 Run 已披露
  -> 只把实际引用的 Ref 关联到 Conversation/Answer
```

`source_quote_ref` 在首次创建 Quote 时生成 `source_quote_<uuid>`。再次读取时先按现有幂等键
`paper_id + model_version + location_ref + split_policy_version + split_index + content_hash` 查询；命中就复用
原 Ref，不再创建新 Quote。

最终回答合同也只使用这一种 Ref：正文引用标记使用 `[[source_quote_...]]`，结构化字段使用
`cited_source_quote_refs`。删除 Agent 可见的 `evidence_id` 和 `cited_evidence_ids`，不保留双轨兼容。

Read 可能创建最终没有被回答采用的 `PaperSourceQuote`。这是本方案接受的简单化：Quote 按现有幂等键复用，
内容是不可变的规范原文，删除 Paper 时统一清理；未被引用的 Quote 不创建 Conversation 关联，也不会出现在
用户答案中。

跨页 Passage 的多个 Quote 共享 `location_ref = passage_ref`。现有 `split_policy_version` 使用
`passage-page-v1`，`split_index` 由后端内部记录 Page 在 Passage 中的顺序。每个 Quote 的
`page_number == page_end_number`，内容和 Source Span 只包含该页。

前端已经支持 `visualRegions[]`。当前后端 `sourceQuoteVisualRegions()` 只接受单个 BBox 或单元素 List，
Passage 接入时必须改为返回当前 Quote 同一页的全部区域。虽然 Passage 可以跨 Page，但每个 Source Quote
仍然是单页，因此继续使用当前前端的 `single-page-mode`，不增加跨页引用导航，也不修改 PDF Viewer
交互。跨页 Claim 由 Agent 同时引用多个 `source_quote_ref`，前端显示为多个普通引用。

最终用户仍然看到：

```text
论文标题
Section
页码
PDF 原文高亮
```

不会看到 `passage_ref` 或 Passage 卡片。

`ProductReferenceFocus` 等内部 Ref Validator 需要接受 `passage_ref_`，但前端引用主路径继续使用
`source_quote_ref_`。

## 12. 对 Agent 的影响

现有 `find_reading_locations` 同时承担 Evidence Search 和 Structure Lookup，`read_locations` 又与它名称过于
接近。本次在未上线阶段直接替换为三个职责单一的 Tool，不保留 Alias：

```text
search_paper_content    -> 在已知论文内检索 PASSAGE/TABLE/FIGURE
get_paper_structure     -> 查看 SECTION Outline 或 PAGE 结构
read_source_quotes      -> 把 Location Ref 读取成可直接引用的 PaperSourceQuote
```

Tool 数量比当前固定集合增加一个，但删除了 `location_types` 路由和两个相似名称。每个 Tool 只有一条明显
路径，Agent 不需要先理解 Qdrant/MySQL Family 才能选 Tool。

整体流程不变：

```text
找论文
  -> search_paper_content
  -> read_source_quotes
  -> disclosed_source_quote_refs
  -> submit_research_answer
```

普通事实问题：

```text
search_paper_content
  -> 返回 PASSAGE/TABLE/FIGURE
  -> Passage 指出相关原文和所属 Section
  -> read_source_quotes 返回命中证据全文和 Source Quote
  -> 证据足够则回答
  -> 不足则带 parent_section_ref 和更具体 Query 再次搜索
  -> 需要整篇 Outline 时调用 get_paper_structure
```

指定结构问题：

```text
get_paper_structure
  -> 找到目标结构
  -> 可按父 Section Ref 或 Page Range 继续找子 Passage
```

Agent 不操作 Passage 边界，不生成 Passage Ref，也不负责把 Passage 转换成 Source Quote。

Tool 描述需要增加以下规则：

- 内容事实使用 `search_paper_content`；
- Outline、指定 Section 或 Page 使用 `get_paper_structure`；
- Passage 是检索锚点，`read_source_quotes` 只读取明确选中的 Ref，不自动读取整个 Section；
- 需要补充上下文时，使用 `search_paper_content(section_refs=[...])` 继续检索；
- 回答只能引用 `read_source_quotes` 返回的 `source_quote_ref`，不能引用 Search Preview；

## 13. Golden Data 不在本次范围

本次只改产品的 Passage 构建、检索、读取、引用和 Agent Tool 链路，不修改：

```text
research/golden-data/**
harness_py/evaluation/**
Golden Schema
accepted_locations
Retriever Scorer
Answer Scorer
```

也不为了兼容当前 PAGE/SECTION Golden Location 保留旧的默认检索行为。Passage 的独立召回评测和 Golden
迁移另开后续工作，不作为本次开发或完成 Gate。

## 14. 构建和重建

当前没有线上流量约束，直接执行：

```text
停止 Java
  -> 从全部 Current READY Reading Model 构建 Passage
  -> Passage Audit
  -> 删除并重建当前 QDRANT_COLLECTION 的派生 Point
  -> 校验 Point Count、Content Hash 和 Source Span Projection
  -> 启动 Java
  -> 跑集成测试和真实 Agent Smoke Case
```

Passage 构建过程：

1. 在内存中完成切分和 Audit；
2. Audit 通过后，在一个事务中删除当前模型旧的 PASSAGE Location/Passage 行并写入新结果；
3. 写入失败时事务回滚；
4. 使用现有 `PaperReadingModel.retrieval_index_status` 记录 PENDING/BUILDING/READY/FAILED；
5. Passage 和 Qdrant 任一步失败，论文不进入 Evidence Search；
6. 修复后直接重新构建；
7. 不删除 PAGE、SECTION、TABLE、FIGURE、Reading Model 或历史 Source Quote。

不新增 Passage Build 表，不保留旧 Passage 集合，不维护多套切分参数。

删除 Qdrant Collection 只删除可重建向量索引，不删除 MySQL Reading Model、PDF、PAGE/SECTION、历史会话
或 Source Quote。新的 Point 全部从 MySQL Current READY Reading Model 重新生成。

## 15. 代码范围

| 边界 | 主要改动 |
| --- | --- |
| Model | 新增 `PaperPassage`，`PaperLocationType.PASSAGE` |
| Repository | Passage CRUD、按 Current Model/Ordinal/Section/Page 查询 |
| Builder | 新增 `StructuralPassageBuilder` 和 Audit |
| Lifecycle | Reading Model READY 后可构建 Passage；删除论文时清理 Passage |
| Index | Qdrant 投影 PASSAGE/TABLE/FIGURE 的向量、规范正文和 Source Span |
| Search | `search_paper_content` 只检索 PASSAGE/TABLE/FIGURE |
| Structure | `get_paper_structure` 只查询 PAGE/SECTION |
| Read | 只为明确选中的 Ref 按 Page get-or-create `PaperSourceQuote` |
| Citation | 校验本 Run 已披露的 `source_quote_ref`，关联 Conversation/Answer，返回完整 `visualRegions` |
| Harness | Tool Schema、Gateway、Run 内 `source_quote_ref` Allowlist |
| DDL | 新增 `paper_passages` 表和索引 |

切分逻辑放在独立 `StructuralPassageBuilder`，不继续塞进负责 Qdrant 编码的
`ReadingModelQdrantIndexService`。

## 16. 测试和完成标准

### 16.1 Passage Audit

- Eligible Text Source Coverage = 100%；
- Duplicate Source Span = 0；
- Cross-Section Passage = 0；
- Oversize Text Passage = 0；
- Page Range 与 Source Span 一致；
- Reading Order Range 与 Source Span 一致；
- Section Ref 属于同一 Current Model；
- Ordinal 连续；
- 同输入重复构建得到相同 Passage Ref。

### 16.2 Unit 和 Integration Tests

- 短 Paragraph 合并；
- Section 硬边界；
- Page 软边界和连续跨页 Passage；
- Paragraph/List/Code/Formula 的超长单个自然单元最终硬切分且不丢字；
- Formula 邻接；
- TABLE/FIGURE 和 Attached Caption 去重；
- TABLE/FIGURE 会结束当前 Passage，不被一个 Passage 的 Reading Order Range 跨过；
- Unsectioned 内容；
- Passage 事务回滚；
- Qdrant Point Count 与 Eligible PASSAGE/TABLE/FIGURE Count 一致；
- Qdrant `content_text/content_hash/source_span_json` 与构建源完全一致；
- 旧 Model Hit 被拒绝；
- PAGE/SECTION Structure Lookup；
- `search_paper_content` 不接受 PAGE/SECTION 参数；
- `get_paper_structure` 不接受 Evidence Query 参数；
- 未披露 Location Ref 不能读取正文或创建 Source Quote；
- 一次 `read_source_quotes` 最多接受 20 个 Ref；
- Read 不自动返回父 Section、相邻 Passage 或其他 Ref 的正文；
- Section Path、Ordinal、页码和前后标题与 Structure Lookup 一致；
- `get_paper_structure(structure_type=SECTION)` 可以按 `display_order` 返回完整 Outline；
- Search/Structure 披露的 Ref 可以批量读取，伪造或过期 Ref 被拒绝；
- Search Preview 没有 `source_quote_ref`，不能作为引用；
- 单页 Passage Read 返回一个 PaperSourceQuote，跨页 Passage 按 Page 返回多个 Quote；
- 多个 Quote 按顺序拼接后与 Passage 正文和 Source Span 完整一致；
- 相同 Page Quote 重复 Read 命中幂等键并复用 `source_quote_ref`；
- `submit_research_answer` 只接受本 Run 已披露的 `source_quote_ref`；
- 最终回答只接受 `[[source_quote_...]]` 和 `cited_source_quote_refs`，不再接受 Agent 可见 `evidence_id`；
- 同页多 Source Span 返回完整 Visual Regions；
- Passage 可以跨页，但每个 Source Quote 不跨页并兼容前端 `single-page-mode`；
- 前端不需要识别 Passage。

### 16.3 Index 和 Search Gate

- Qdrant 只包含 Current Model 的 PASSAGE/TABLE/FIGURE；
- Point Count 与 Eligible PASSAGE/TABLE/FIGURE Count 一致；
- 抽样 Qdrant Payload 与 MySQL 构建源的 Location Ref、正文 Hash 和 Source Span 完全一致；
- `read_source_quotes` 对 Evidence Ref 不执行逐 Hit MySQL 正文 Hydration；
- PAGE/SECTION Structure Lookup 不依赖 Qdrant；
- Technical Error = 0；
- Projection Mismatch = 0。

### 16.4 Product Gate

- 真实 Agent Case 完成 Candidate -> Read -> Cite；
- Agent 可以找到 PAGE/SECTION；
- Passage Read 只返回明确选中的精确正文和 Source Quote；
- Agent 能按 `parent_section_ref` 继续检索同节内容；
- Read 能生成或复用 Source Quote，提交答案能关联实际引用的 Quote；
- 跨页 Passage 可被 Agent 连续阅读，并返回多个单页 Source Quote；
- 前端在现有单页模式下分别打开正确 PDF Page，并显示该页全部 MinerU Visual Regions；
- Chat 和引用视觉设计不变。

## 17. 最终决策

```text
普通文本召回单位：PASSAGE
结构化证据单位：TABLE / FIGURE
AGENT 阅读上下文：明确选中的 SOURCE QUOTE
结构导航单位：PAGE / SECTION
前端引用单位：SOURCE QUOTE + SOURCE SPAN
检索索引：QDRANT
构建事实来源：MYSQL READING MODEL
Evidence 查询投影：QDRANT CONTENT_TEXT + SOURCE_SPAN
```

不新增 Passage 版本体系，也不新增 Qdrant Collection。切分规则或参数变化时，直接重建
`paper_passages` 和当前 `QDRANT_COLLECTION`。
