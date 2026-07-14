# Reading Model 对齐与 Golden 检索恢复实践复盘

**日期：** 2026-07-13
**状态：** 已完成实践记录
**范围：** 新增 9 篇论文、15 个 Golden Case、Reading Model Pipeline、稀疏检索和 MiniMax 回归
**后续记录：** `site/practice/evaluation/golden-data-harness-evolution.md`

## 1. 这次实践真正解决了什么

最初看到的现象是：BM25 上线后，旧 Golden 仍有一定收益，但新增 15 个 Case 只通过 `1/15`，
保存查询 Candidate 命中也从 Baseline `21/32` 降到 `16/32`。

表面上像是“检索器只取第一个看起来相关的结果”。继续深入后发现，主要根因并不在 BM25，
而是新增 9 篇论文没有通过正式 Reading Model Pipeline：

```text
PDF
-> pdftotext
-> 空行分段
-> 按句号切句
-> 全部写成 PARAGRAPH
-> sectionTitle = null
```

这批数据使用了 Reading Model 的 JSON 外形，却没有满足正式 Reading Model 的语义和物理页
契约。若继续在 Harness 中增加首页保底、front-matter、邻接窗口或页码 fallback，模型编排层
就会长期承担数据生产层的错误。

最终实施的是：

```text
PDF
-> MinerU
-> MinerUOutputMapper
-> PaperReadingModelBuilder
-> paperloom-reading-model-export/v1
-> Golden Harness
```

Harness 保持消费统一契约，不识别“产品论文”和“Golden 论文”两套数据语义。

## 2. 数据变化

### 2.1 关键结果时间线

| 阶段 | Candidate | MiniMax 稳定 Pack | MiniMax 新增 Pack | 全量 |
| --- | ---: | ---: | ---: | ---: |
| 已保存 Baseline | `21/32` | `13/15` | `5/15` | 分开保存 |
| BM25 + 伪 Reading Model | `16/32` | `12/15` | `1/15` | `13/30` |
| 正式 Reading Model v2 | `29/32` | `12/15` | `6/15` | `18/30` |

Candidate Recall 的变化：

```text
21/32 = 65.6%   已保存 Baseline
16/32 = 50.0%   BM25 作用于低质量伪 Reading Model
29/32 = 90.6%   正式 Reading Model v2 + 当前检索
```

从错误阶段到最终阶段，Candidate 增加 13 个命中，提升 40.6 个百分点。新增 Pack 从 `1/15`
恢复到 `6/15`，比最初已保存 `5/15` Baseline 多 1 个 Case；全量从 `13/30` 提升到 `18/30`。

数据来源：

- 初始 Baseline：`research/golden-data/llm-agent-evaluation-baseline-2026-07-13.md`；
- 错误阶段记录：本次实践保留的 saved-query replay 与资产审计；
- 当前资产统计：`research/golden-data/corpora/llm-agent-evaluation/generated-audit/asset-inventory.json`；
- 当前 Candidate 重放：`research/golden-data/corpora/llm-agent-evaluation/generated-audit/saved-query-replay.json`；
- 最终 MiniMax 合并报告：`/tmp/paperloom-expanded-live-final-merged-20260713/score_report.json`。

### 2.2 Reading Model 结构变化

旧的新增语料：

| 指标 | 数值 |
| --- | ---: |
| Reading Element | `8225` |
| 有效 `sectionTitle` | `0` |
| HEADING | `0` |
| TABLE / IMAGE / CHART / FORMULA | `0` |
| Parser | `pdftotext` |

正式 v2 语料：

| 指标 | 数值 |
| --- | ---: |
| Canonical Reading Element | `3278` |
| 有效章节元素 | `2626`，约 `80.1%` |
| HEADING | `437` |
| TABLE | `108` |
| IMAGE + CHART | `139` |
| FORMULA | `16` |
| 物理页 | `336` |
| 来自物理页 projection 的页 | `336` |
| 来自语义元素 fallback 的页 | `0` |
| Parser | `MinerU` |

`8225 -> 3278` 不是丢失正文，而是把按句号切碎的 sentence chunk 收敛为 canonical semantic
element，同时恢复 heading、section、table、figure、formula、reading order 和 parser
provenance。元素数量减少约 60.1%，但结构信息和可定位性显著增加。

### 2.3 确定性 Gate

最终发布后：

```text
stable fixture:   15/15
expanded fixture: 30/30
anchor audit:     29/29
saved-query candidate recall: 29/32
```

9 个 Reading Model 以 `rm_golden_<paper>_mineru_v2` 发布。所有 `PaperPage` 都来自 MinerU
`middle.json.pdf_info[].preproc_blocks` 的物理页 projection，没有使用语义元素回填页文本。

## 3. 最容易走错的几条路

### 3.1 在确认输入契约前调 BM25

第一次看到 Candidate 从 `21/32` 降到 `16/32` 时，很自然会继续调整 BM25 权重、top-k、Query
Coverage 或 RRF。但这会把两个变量混在一起：

```text
排名变化 + 输入结构变化 = 无法解释的总分变化
```

更可靠的顺序是：

1. 检查两批语料是否满足同一 Reading Model 契约；
2. 固定相同查询，做确定性重放；
3. 修复数据生产层；
4. 再判断检索器是否仍有稳定回归。

### 3.2 用首页或 front-matter fallback 把 Case 变绿

新增 Anchor 大量位于第 1 页，首页保底看起来非常有效。但它把当前 Golden 分布写进了生产
检索器：

- 默认摘要在第一页；
- 默认第一页比正文更值得占候选位；
- 默认无章节输入是正常契约；
- 默认 Harness 应修复 Parser 丢失的结构。

这类 fallback 会快速提高局部分数，却让错误层次反转。最终拒绝了第 1 页保底、front-matter
虚拟章节、页码 region 和 Golden 分布相关的邻接窗口。

### 3.3 把语义元素和物理页当成同一个对象

WebArena 的跨页证据暴露了另一个问题：一条 MinerU `content_list` item 可能是一个完整语义
段落，但其物理文本会跨 PDF 页。如果按页强行切开 canonical element，会破坏稳定元素身份；
如果只保留元素所属首页，续页又无法被精确 Grounding。

正确建模是两个并行 projection：

```text
content_list item          -> canonical Reading Element
middle.json preproc_blocks -> physical PaperPage surface
```

语义检索仍以 Reading Element 为主。物理 PAGE 使用独立 BM25 统计，只在共享文本证明同一语义
内容延续到另一页时参与 Grounding。这样既不拆语义元素，也不猜相邻页。

### 3.4 把 Fixture 通过说成“大模型测试通过”

`validate 30/30` 只证明：Schema、Fixture 生成和确定性 Scorer 能按 authored expectation 工作。
它不调用 MiniMax，也不执行真实 Agents SDK 工具循环。

必须分别报告：

```text
Fixture：确定性评分逻辑
Audit：Parser / Reading Model Anchor 可定位性
Replay：固定查询的 Candidate 能力
Agent Run：真实模型编排、读取、引用和最终回答
Judge：自然语言质量与人工标准的一致性
```

少写其中一层，读者就容易把局部成功误解成完整成功。

### 3.5 用总 Hard Pass 反推检索问题

一个 Case Hard Fail 可能发生在完全不同的阶段：

```text
Candidate：位置没有返回
Read：位置返回了，但模型没有读取
Cited：读取了，但最终没有引用
Outcome：证据充分，但 answered / partial 选择错误
Content：结构通过，但自然语言结论仍不充分
Technical：供应商 529、连接或协议错误
```

如果只看 `18/30`，无法知道应该改 Parser、Retriever、Runner、提交门还是 Judge。

### 3.6 修改 authored Golden 来适应实现

Golden Manifest、Case、Anchor、页码和 Expected Fact 是评测定义；Reading Model JSON 和审计
报告是可重新生成的 derived asset。两者必须分开：

```text
authored data：冻结，用来判断实现
derived data：可重建，但必须经过 staging 和 Gate
```

不能通过改 Anchor、换 quote、降低预期或增加 accepted answer 让实现变绿。本轮只重建了派生
Reading Model 和 generated audit，没有改变 authored Golden、提示词、默认值或路径。

### 3.7 只拿第一个“看起来对的”候选

Phase 1 的检索改进证明，多论文和宽查询不能只保留第一个表面匹配。当前策略会：

- 对宽查询扩大候选面；
- 对多论文请求保留每篇论文的候选下限；
- 结合全文 BM25、局部 passage BM25、lead、section 和 query-term coverage；
- 完全去重后再做覆盖选择；
- 将 PAGE Grounding 与 semantic element 分开排序。

但“返回更多候选”只解决 Candidate。模型仍可能不读、读错或不引用，所以不能把候选扩张当成
最终答案策略。

## 4. 有效的实践方法

### 4.1 用固定查询隔离模型随机性

真实 MiniMax 会改变查询措辞、工具顺序和读取选择。保存上一次 Run 的
`find_reading_locations` 参数，再对当前检索器离线重放，可以回答一个明确问题：

> 在完全相同的查询下，当前检索实现是否仍能返回 Required Anchor？

这让 `21/32 -> 16/32 -> 29/32` 成为可复现的检索信号，而不是供应商采样波动。

### 4.2 一次只修一个层次

本轮最终采用的顺序：

```text
确认输入契约分裂
-> 拒绝检索 fallback
-> 正式 Parser / Builder Spike
-> 建立 Export CLI
-> staging 重建 9 篇论文
-> Anchor Audit
-> 发布 derived assets
-> 保存查询重放
-> MiniMax 焦点 Case
-> MiniMax 全量与技术失败重跑
```

每一步都有独立 Gate，因此能解释数据为什么变化。

### 4.3 先 staging，再发布

Reading Model 重建先写 staging 目录，检查：

- Parser 和 model version；
- PDF SHA-256；
- 章节和 typed element 分布；
- 物理页来源；
- Anchor Audit；
- Candidate 回归。

全部通过后才替换 derived assets。这样构建失败不会破坏当前可用语料。

### 4.4 Technical Failure 单独处理

MiniMax 529、连接错误和工具协议错误不等于模型行为失败。全量运行中两个 Technical Failure
单独重跑后正常完成，再与主报告合并。只有这样，`18/30` 才表示完成运行的行为结果，而不是
供应商可用性和 Harness 能力的混合分数。

### 4.5 运行时只存原始数据，研究在线下做

`EvalRecorder` 只做每 Run 的 append-only 保存：

```text
<eval-dir>/<run_id>/events.jsonl
<eval-dir>/<run_id>/result.json
```

它用 `event_id` 去重、`sequence` 排序、逐条 `fsync`，最终结果原子替换。Candidate Recall、
Evidence Funnel、供应商对比、Reward、Judge 和 Human Label 都在线下派生。

这个边界很重要：Harness 的主职责是模型编排，不应该因为某次研究需求就在 Runtime 内增加一套
统计模型和数据仓库。

## 5. 最终数据应该怎样解释

### 已经可以确认

- 新增 9 篇论文已经与产品 Reading Model Pipeline 对齐；
- 物理页和语义元素已经分开建模；
- authored Golden 没有为了分数被修改；
- Candidate Recall 已从错误阶段 `16/32` 恢复到 `29/32`；
- 新增 Pack 已从 `1/15` 恢复到 `6/15`，超过最初 `5/15` Baseline；
- 原稳定 Pack 的确定性 Gate 没有回退。

### 仍不能宣称

- 不能说全量 Golden 已通过，真实 MiniMax 仍是 `18/30`；
- 不能说所有剩余失败都是 Retriever；
- 不能说同论文引用就一定语义正确；
- 不能说 LLM Judge 可以替代 Human Label；
- 不能因为某个替代证据看起来合理就在线修改 authored Anchor。

## 6. 剩余问题已经转移到编排层

当前焦点失败具有很强的共同模式：

- Candidate 已出现，但模型没有读取；
- 读取了多篇论文，但最终漏掉其中一篇引用；
- 最终草稿被截断，Harness 仍因存在任意一个合法 citation 而接受；
- 只引用标题等导航文本支撑实质内容；
- Topic Change 后继续凭候选预览回答，没有生成 evidence。

因此后续实现已经在 `submit_research_answer` 前加入通用 Evidence Coverage Policy，而没有继续
提高 Candidate 数量；同时新增了完全离线的 Evidence Funnel 报告。完整复盘见
`site/practice/evaluation/golden-data-harness-evolution.md`。

实现后的四个 MiniMax 焦点 Case 从 `0/4` 提升到 `1/4`。更重要的阶段变化是：多论文推荐已经
为四篇论文分别提供实质引用，跨 Benchmark 答案同时覆盖 GAIA 和 WebArena，Topic Change 不再
无引用回答或继续接受旧 GAIA 话题。剩余三个 Hard Fail 都集中在“引用了合理替代证据，但没有
命中 authored canonical Anchor”，不再是简单的整篇论文漏读或漏引。

## 7. 可复用检查清单

以后再遇到 Golden 检索下降，按下面顺序检查：

1. 两批输入是否来自同一 Parser 和 Reading Model 契约？
2. authored data 和 derived asset 是否被清楚区分？
3. 相同查询能否离线复现 Candidate 变化？
4. 失败发生在 Candidate、Read、Cited、Outcome、Content 还是 Technical？
5. 修复是否位于产生错误的层，而不是调用方 fallback？
6. 语义内容和物理页是否被错误混为一个对象？
7. 是否一次只改变一个主要变量，并保留旧基线？
8. 线上实现是否读取了 Golden 专用字段？如果是，立即停止。
9. Runtime 是否只保存原始、完整、有序的数据，把分析留在线下？
10. 最终报告是否明确区分确定性测试和真实大模型运行？

这次最重要的经验不是某个 BM25 权重，而是：**先守住数据契约和层次，再用证据漏斗决定下一步
改哪里。**
