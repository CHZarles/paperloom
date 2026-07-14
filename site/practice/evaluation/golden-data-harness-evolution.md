---
title: 我把检索召回从 16/32 修到 29/32，却只多过了 1 个 Case
description: 一次从数据契约、Reading Model、Evidence Coverage 到 canonical evidence selection 的论文研究 Harness 复盘。
date: 2026-07-13
stage: evaluation
status: verified experiment
topics: [Golden Data, Reading Model, Evidence Coverage, RAG Evaluation]
---

# 我把检索召回从 16/32 修到 29/32，却只多过了 1 个 Case

这一轮我先换回正式 Parser，重建 Reading Model。Candidate Recall 从 `16/32` 回到 `29/32`，新增
Golden Pack 也从 `1/15` 恢复到 `6/15`。单看这组数字，检索问题似乎已经处理完了。

随后加入的 `evaluate_evidence_coverage` 改变了模型行为。MiniMax 会继续检索、补读论文，也开始为多篇
论文分别引用证据。四个焦点 Case 的 Hard Pass 却只从 `0/4` 变成 `1/4`，合计用了 45 次模型调用、
48 次工具调用和约 60.9 万 Token。

这篇记录会沿着数据契约、Candidate、Read、Cited 和 Hard Pass 一层层展开。召回修复解决了一部分
问题，也让 Evidence Selection 和过度研究变得更清楚。

## Harness 和 Golden Data

PaperLoom 的 Python Research Harness 负责一次论文研究回合。模型可以搜索论文、定位 Reading
Model 中的位置、读取原文证据，再通过 `submit_research_answer` 提交答案。Java 侧负责权限
和长期会话，Python 侧负责模型与工具的循环。

Golden Data 是这套 Harness 的离线试卷。每个 Case 不规定模型必须走几轮、按什么顺序调用工具，
只规定用户能观察到的结果：

- 应该回答、澄清、部分回答还是拒答；
- 应该涉及哪些论文；
- 哪些证据 Anchor 必须被读取和引用；
- 哪些结构化事实必须出现。

排障时我把结果拆成四个阶段：

```text
Candidate：find_reading_locations 返回了目标位置
Read：模型调用 read_locations 读取了目标位置
Cited：最终答案引用了读取后生成的 evidence_id
Hard Pass：整个 Case 的结果、证据和引用都通过评分
```

刚开始做这轮实验时，我还没有把这四层分开。

## Stage 越多，结果反而越差

最初的 Harness 有明显的“流程设计冲动”。Intent、Retrieval Plan、Evidence Ledger、Claim Graph、
Verification Pass，每个阶段都很像一个成熟系统应有的样子。

问题在于，模型经常在一个大的 ReAct 回合里已经做完主要工作，后面的结构只是把结果重新包装
一遍。随后我又尝试了更严格的 stage-driven runtime：每个研究范式有自己的阶段、允许的工具
和完成条件。它看上去更可控，实际却出现了重复澄清、追问状态丢失、ordinal selection 失效和
阶段转换错误。

同一批 12 个 Golden Case 上，几个版本的结果是：

| Runtime | MiniMax Hard Pass |
| --- | ---: |
| stage-heavy baseline | `3/12` |
| evidence-bounded stage rewrite | `4/12` |
| 简化后的 skill-guided ReAct | `10/12` |

删掉控制流以后，Hard Pass 的提升幅度最大。继续增加 Router 没有得到同样的收益。

随后我把通用模型/工具循环交给 OpenAI Agents SDK。Harness 只保留领域规则：

```text
SDK：模型调用、工具续跑、Session、Runner 生命周期
Harness：论文授权、位置公开、证据身份、引用校验、最终提交门
```

Agents SDK 处理通用循环，产品代码处理领域不变量。此前两套循环同时存在时，我也需要维护两套
状态、续跑和错误路径；删除其中一套后，12 个稳定 Case 从 `4/12` 提高到 `10/12`。

## BM25 回归来自两套输入契约

扩展 Golden Data 后，新增了 9 篇 Agent Benchmark 论文和 15 个 Case。第一轮 MiniMax 是
`5/15`。接着我改进稀疏检索，引入 BM25、passage score、lead、section 和 query coverage。

结果反而掉到 `1/15`。

固定相同查询离线重放后，Required Anchor 的 Candidate 命中也从 `21/32` 降到 `16/32`。这时
我一开始准备继续调 BM25，或者给第一页、摘要和 front matter 加保底。

当时写过这样一版方案：

```text
BM25 + Coverage Ranker + RRF
+ 首页候选
+ front-matter region
+ 相邻 chunk window
```

这套规则大概率能快速抬高当前 Golden 分数。继续检查语料后，我发现两批论文走的不是同一条
构建链。

原来的 5 篇论文来自正式 MinerU Pipeline；新增 9 篇论文却走了另一条简化链路：

```text
PDF
-> pdftotext
-> 空行分段
-> 按句号切句
-> 全部写成 PARAGRAPH
-> sectionTitle = null
```

这 9 篇论文共有 8,225 个 sentence chunk，却没有一个有效章节、Heading、Table、Figure 或
Formula。文件长得像 Reading Model，语义上却不是产品的 Reading Model。

BM25 仍按词频工作，于是后文中反复出现技术词的长段落压过了首页
的短摘要。旧的 token overlap 对这种伪 Reading Model 更“宽容”，反而掩盖了数据契约分裂。

## 我没有合并第一页保底

新增 Anchor 中有 18 个位于第一页。把第一页塞回候选集非常诱人，而且数据大概率会立刻好看。

我没有合并这条路。Parser 数据缺失发生在上游，Retriever 的 fallback 只能把问题藏起来：

- Parser 没产生章节，Harness 就发明 `front_matter`；
- 物理页不准确，Harness 就猜第一页或相邻页；
- Golden 摘要容易丢，Retriever 就长期保留首页特权。

一旦把当前测试集的分布写进生产语义，以后每次换 Parser、论文类型或 Benchmark，都要重新解释
这些规则为什么还存在。

我改成让 Golden 和产品共用同一条数据链：

```text
PDF
-> MinerU
-> MinerUOutputMapper
-> PaperReadingModelBuilder
-> paperloom-reading-model-export/v1
-> Golden Harness
```

重建后的 9 篇论文有了这些变化：

| 指标 | 简化资产 | 正式 v2 资产 |
| --- | ---: | ---: |
| Reading Element | `8225` | `3278` |
| 有效章节元素 | `0` | `2626`，约 `80.1%` |
| Heading | `0` | `437` |
| Table | `0` | `108` |
| Image + Chart | `0` | `139` |
| Formula | `0` | `16` |
| 物理页 | 弱页码近似 | `336` |
| 语义元素回填物理页 | 不可区分 | `0` |

`8225 -> 3278` 来自粒度收敛：按句号切碎的 sentence chunk 被重新建成 canonical semantic
element，章节、类型、reading order 和 parser provenance 也随之恢复。正文仍在正式资产中。

这次修复带来的数据变化很明确：

```text
Candidate Recall：21/32 -> 16/32 -> 29/32
新增 Pack：       5/15  -> 1/15  -> 6/15
全量 MiniMax：              13/30 -> 18/30
Anchor Audit：                       29/29
```

从这次排障开始，我会先确认两批输入是否满足同一契约，再讨论 Ranker。跳过这一步，调参结果很
可能只是在拟合偶然的数据格式差异。

## Candidate 恢复以后，Hard Pass 仍停在 18/30

`29/32` 一度让我以为主要问题已经解决。MiniMax 全量结果却只有 `18/30`。

进一步拆开失败后发现，很多 Required Anchor 已经进入候选，模型却没有继续读取；有时模型读取
了多篇论文，最终答案只引用其中一篇；还有一次，模型先生成了完整的 GAIA 与 WebArena 对比，
因为 `fields` 类型错误被拒绝，第二次提交被截断到只剩 GAIA，Harness 仍然接受了它。

原来的最终校验只回答两个问题：

```text
引用的 evidence_id 是否真实存在？
只要本轮读过论文，最终是否至少有一个引用？
```

它不知道一篇多论文答案是否只覆盖了一半。

于是我加了一个 Evidence Coverage Policy。它位于 `submit_research_answer` 的既有结构校验
之后，不改提示词和工具 Schema：

```text
模型提交 draft
-> 校验 outcome、Markdown、evidence_id
-> 检查被讨论论文的 Candidate / Read / Cited
   -> 完整：结束 Runner
   -> 不完整：返回错误，让同一个 Runner 继续研究
```

Policy 只使用产品状态：论文元数据、当前问题、工具轨迹、真实 Evidence 和最终 Markdown。它不
读取 Case ID、Anchor、Expected Fact 或 Human Label。

它能拦截四类明显问题：

- 只拿 paper card 或 candidate preview 就回答论文内容；
- Candidate 已返回，但模型没调用 `read_locations`；
- 证据已经读取，最终答案却没引用同一篇论文；
- 只引用 Heading 之类的导航文本支撑方法、结果或数值。

我还写了一个完全离线的 Evidence Funnel Analyzer。它读取保存的 Run，重放原始查询，再把
每个 Anchor 归到 Candidate、Read、Cited、Outcome 或 Technical Failure。线上 Recorder 仍然
只保存原始事件，不负责计算研究指标。

## `evaluate_evidence_coverage` 改变了哪些行为

我选了四个最能暴露 Coverage 问题的 Case。改造前四个都失败。改造后的焦点运行结果是：

| Case | 改造后 | 实际变化 |
| --- | --- | --- |
| GAIA vs. WebArena 人机差距 | Fail | 最终同时读取并引用两篇论文，但没命中 WebArena authored Anchor |
| 四类 Agent Benchmark 推荐 | Fail | 四篇论文都有实质引用，但只有 tau-bench 命中指定 Anchor |
| ReAct 到后续 Agent Evaluation 的谱系 | **Pass** | 三个 Required Anchor 全部 Candidate、Read、Cited |
| Topic Change 到 GitHub Issue Agent | Fail | 不再无引用回答，也不再保留旧 GAIA 话题；引用了两条 SWE-bench 证据，但没命中指定 Anchor |

Hard Pass 是 `0/4 -> 1/4`。

总分只增加了一个 Case，工具轨迹里则出现了几项明确变化：

- 多论文推荐不再只引用其中一两篇；
- 跨 Benchmark 对比不再因为重提交截断而丢掉半边证据；
- ReAct 谱系 Case 补齐了三段证据；
- Topic Change 从“凭 paper card 回答”变成读取 SWE-bench 后回答。

剩下三个失败都已经读取并引用相关论文。模型选的是语义相关的替代证据，没有命中 Golden 指定的
canonical Anchor。Coverage Policy 约束到了论文级 Read / Cited，Anchor 级 evidence selection 和
语义蕴含仍未解决。

## 一个真实的回归：提交校验让模型研究过头了

第一次重跑 Topic Change 时，模型给出了正确的 SWE-bench 推荐，又顺手比较了 GAIA、tau-bench
和 ToolSandbox。第一版答案没有为这些旁支比较提供引用，`evaluate_evidence_coverage` 拒绝了它。

校验错误当时写的是：

```text
search those papers for relevant evidence or remove/qualify the unsupported claims
```

MiniMax 选择了前半句。它继续搜索三篇不重要的论文，最终把 GAIA Evidence 带进答案，触发了
Golden 的 forbidden paper 规则。

这个错误由新加的 Gate 间接触发。

我没有为 Topic Change 增加 Case 分支，只调整了通用反馈的优先级：

```text
remove them if they are not essential to the user's request,
otherwise search those papers for relevant evidence
```

再次运行后，模型删除了旁支比较，只保留 SWE-bench 和两条实质引用。Hard Pass 仍然失败，因为
它没有选中 authored Anchor，但“继续旧话题”和“无引用回答”两个行为问题已经消失。

这次回归让我开始把 Validator 的错误文本当成编排接口。确定性规则会约束提交，反馈措辞还会改变
模型下一步的工具选择。把“继续搜索”写在前面，MiniMax 就优先走了这条路。

## 四个 Case 用掉了 60.9 万 Token

四个焦点 Case 合并后大约消耗：

```text
模型调用：45
普通工具调用：48
供应商报告 Token：约 608,855
耗时：约 6.2 分钟
```

其中 ReAct 谱系 Case 经历了 7 次最终提交拒绝，最终通过时已经用了 19 次模型调用、26 次
工具调用和约 36.4 万 Token。

`evaluate_evidence_coverage` 会推动模型补证，也可能诱发过度研究。当前规则是“答案中被
明确讨论的论文都需要同论文实质证据”。当模型先写一段过宽的答案时，这条规则可能迫使它为
旁支内容逐一补证，而没有先缩小答案。

因此我同时保留 `1/4` 和成本曲线。一个 Harness 即使通过更严格的校验，只要每个回答都多跑十几轮，
仍然需要评估它是否值得进入产品。

## 这轮排障留下的做法

### 固定查询，先排除模型随机性

真实模型会改变检索词。我保存 `find_reading_locations` 参数并离线重放，用来确认 Candidate 变化
来自 Retriever。这样得到的 `21/32 -> 16/32 -> 29/32` 排除了 MiniMax 临时换问法的影响。

### authored data 和 derived asset 分开

Manifest、Case、Anchor 和 Expected Fact 是人工评测定义；Reading Model JSON、索引和审计报告
是可重建资产。这次没有修改 Anchor、替换 quote 或降低预期，只重建了 derived asset。

### 一次只修一层

这轮实践形成了一个稳定顺序：

```text
检查输入契约
-> 修 Parser / Reading Model
-> 做 Anchor Audit
-> 固定查询测 Candidate
-> 真实模型测 Read / Cited
-> 最终提交门收敛答案
-> Judge / Human Label 看语义质量
```

Parser、Retriever、Prompt 和 Scorer 同时变化时，即使分数上升也很难归因。

### Runtime 只保存原始事实

`EvalRecorder` 仍然只有两个文件：

```text
<run_id>/events.jsonl
<run_id>/result.json
```

事件按顺序追加、按 `event_id` 去重，结果原子写入。Evidence Funnel、Token 分析、供应商比较、
Reward 和 Judge 都在线下派生。Harness 继续负责模型编排和事实落盘，在线分析没有进入它的职责。

## 四次判断修正

### Stage Machine 改善了图，没有改善结果

Stage Machine 让架构图更漂亮，却没有解决模型如何根据新证据调整下一步。删除 4,000 多行控制
流后，稳定 Case 反而从 `4/12` 到 `10/12`。

### 首页 Fallback 会掩盖坏契约

首页保底很可能能恢复多个 Anchor，但它会让 Harness 永久记住“摘要应该在第一页”。最终修复
放在 Reading Model Pipeline，Candidate 才从 `16/32` 到 `29/32`。

### 论文级 Coverage 没有解决 Anchor 命中

Cross-benchmark 和 Recommendation Case 已经给每篇论文提供了合理 Evidence，Golden 仍然失败。
这组数据还无法判定 Golden 过窄或模型选错。下一轮需要结合 Human Label 检查 canonical Anchor 与
替代证据的关系，继续扩大 top-k 暂时没有依据。

### Validator 反馈会改变工具选择

Topic Change 的 forbidden GAIA 是提交校验间接制造的。规则本身没要求读 GAIA，但反馈
把“去搜索”放在了“删掉旁支”前面。模型照做了。

Harness 的错误消息、工具描述和拒绝原因都会进入模型的行动空间。我因此把这些文本也纳入行为
回归，不再只把它们当开发日志。

## 当前基线

当前可以确认：

```text
Python tests：                  76/76
deterministic fixture：         30/30
anchor audit：                  29/29
saved-query candidate recall：  29/32
全量旧 MiniMax 基线：           18/30
Coverage 焦点 MiniMax：         1/4，改造前为 0/4
```

这组结果目前支持四个判断：

- 数据契约问题已经解决；
- Candidate 大面积缺失已经缓解；
- 论文级 Read / Cited Coverage 有了确定性约束；
- canonical evidence selection 和过度研究仍是下一阶段问题。

Embedding 暂时没有进入修复计划。剩余失败里，多数目标 Evidence 与查询有明显词法重合，模型没有
选择或引用指定位置；Sparse Retrieval 完全找不到同义改写内容并非这批失败的主要来源。

下一步有两项：让 Coverage Policy 更好地识别答案范围，减少旁支补证；在线下区分语义有效的替代
Evidence 与无法支撑 Claim 的 Evidence，再决定 Anchor 评分是否需要扩大接受范围。

## 复现与资料

确定性验证：

```bash
.venv-harness/bin/python -m unittest discover -s harness_py/tests

.venv-harness/bin/python -m harness_py \
  --manifest research/golden-data/manifest-expanded.yaml \
  validate

.venv-harness/bin/python research/golden-data/replay_saved_queries.py \
  --out /tmp/paperloom-saved-query-replay.json
```

离线 Evidence Funnel：

```bash
.venv-harness/bin/python research/golden-data/analyze_evidence_funnel.py \
  --runs /tmp/paperloom-expanded-live \
  --eval-dump /tmp/paperloom-expanded-eval \
  --out /tmp/paperloom-evidence-funnel.json \
  --markdown-out /tmp/paperloom-evidence-funnel.md
```

相关设计与数据记录：

- `harness_py/ONBOARDING.md`
- `research/golden-data/README.md`
- `research/golden-data/2026-07-13-reading-model-retrieval-practice.md`
- `docs/adr/0011-use-evidence-first-golden-cases-for-harness-eval.md`
- `docs/adr/0012-build-golden-schema-runtime-as-offline-eval-first.md`

我保留这份记录，是因为它改变了后续排障顺序：先核对输入契约，再拆 Candidate / Read / Cited，
随后观察 Validator 反馈如何改变模型行为。

后续的 Best-of-2 并行采样实验见：
[`Best-of-2 Agent 编排实验`](best-of-two-agent-orchestration.md)。
