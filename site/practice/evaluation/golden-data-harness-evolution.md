---
title: 我把检索召回从 16/32 修到 29/32，却只多通过 1 个问题
description: 测试论文的数据结构与产品论文不一致；修复检索后，最终回答仍有证据选择和成本问题。
date: 2026-07-13
category: 评估
stage: evaluation
status: 已验证
result: 29/32
topics: [Golden Data, Reading Model, 检索, 评估]
background: >-
  新增 9 篇论文和 15 个测试问题后，目标证据进入候选列表的次数从 21/32 降到 16/32，新增问题的通过数从 5/15 降到 1/15。
problem: >-
  需要确认下降发生在论文数据、检索、原文读取、最终引用，还是自动评分。
approach: >-
  固定查询重放检索，并让测试论文与产品论文共用正式的 Parser 和 Reading Model 构建流程，再逐层统计找到、读取、引用和最终评分。
outcome: >-
  目标证据进入候选列表恢复到 29/32，但最终只多通过 1 个问题；后续工作转向证据选择和研究成本。
---

# 我把检索召回从 16/32 修到 29/32，却只多通过 1 个问题

<PracticeArticleOverview />

## 背景

PaperLoom 的论文问答分成两部分。Java 负责论文权限、会话和接口；Python 中的 Research Harness
负责让模型搜索论文、读取原文、整理引用并提交答案。下文简称 Harness。

论文进入 Harness 前，会被整理成带章节、页码、内容类型和阅读顺序的结构。项目代码把这份结构称为
Reading Model。离线测试使用一组固定问题、指定论文和指定证据位置，项目中称为 Golden Data。

新增 9 篇 Agent Evaluation 论文和 15 个问题后，结果连续下降：

```text
新增问题通过数：          5/15 -> 1/15
指定证据进入候选列表：   21/32 -> 16/32
```

当时无法直接判断是检索算法退化，还是新增论文的数据结构有问题。即使检索已经返回正确位置，模型也
可能没有继续读取，或者读取后没有在答案中引用。

## 要解决的问题

这轮排查把一次回答拆成四个阶段：

```text
找到：检索结果中出现了指定位置
读取：模型读取了这个位置的原文
引用：最终答案引用了这段原文
严格评分：回答类型、指定位置和引用都通过固定规则
```

要回答的问题很具体：分数下降最早发生在哪一层，应该修数据、检索，还是最终提交规则。

文中保留几个代码里的名称。Candidate 指检索返回的候选位置，Read 指已经读取，Cited 指已经引用。
Hard Pass 指固定规则全部通过。后续人工审核表明，Hard Pass 只能表示规则和指定证据位置一致，不能
直接当作自然语言回答准确率。

## 怎么处理

### 先简化研究循环

早期 Harness 把一次研究拆成 Intent、Retrieval Plan、Evidence Ledger、Claim Graph 和
Verification Pass 等阶段。模型在连续的“思考、调用工具、读取结果”循环中已经完成了主要工作，
后面的阶段经常重复包装同一批内容，还会造成重复澄清、追问状态丢失和阶段切换错误。

同一批 12 个固定问题上，结果是：

| 运行方式 | Hard Pass |
| --- | ---: |
| 多阶段版本 | `3/12` |
| 多阶段版本加证据约束 | `4/12` |
| 连续的 Agent 工具循环 | `10/12` |

随后删除了 4,000 多行自建控制流，把通用的模型和工具循环交给 OpenAI Agents SDK。Harness 只保留
论文授权、位置公开、证据编号、引用检查和最终提交规则。这段工程演化与本轮检索问题不同，但它决定了
后续排查只需要维护一条运行路径。

### 固定查询，排除模型随机性

真实模型每次可能换一组检索词。为了只观察检索器，运行记录保存了
`find_reading_locations` 的参数，再用相同参数离线重放。

BM25 上线后，指定位置进入候选列表的次数从 `21/32` 降到 `16/32`。BM25 按词频给段落排序，最初
看起来需要继续调权重，或者强制把第一页、摘要和相邻段落加入候选。

继续检查论文数据后，问题出在更早的位置。

### 让测试论文和产品论文共用构建流程

原来的 5 篇测试论文来自正式 MinerU 解析流程。新增 9 篇论文走了简化流程：

```text
PDF
-> pdftotext
-> 按空行分段
-> 按句号切句
-> 全部标成普通段落
-> 没有章节标题
```

这 9 篇论文共有 8,225 个句子片段，却没有有效章节、标题、表格、图片或公式。文件格式看起来像
Reading Model，内容结构与产品中的 Reading Model 不同。BM25 因此更容易把后文反复出现技术词的
长段落排在首页摘要前面。

最终没有增加“第一页优先”规则。测试论文改为共用产品构建流程：

```text
PDF
-> MinerU
-> MinerUOutputMapper
-> PaperReadingModelBuilder
-> paperloom-reading-model-export/v1
-> Golden Harness
```

重建前后的数据差异如下：

| 指标 | 简化数据 | 正式数据 |
| --- | ---: | ---: |
| Reading Model 元素 | `8225` | `3278` |
| 带有效章节的元素 | `0` | `2626`，约 `80.1%` |
| Heading | `0` | `437` |
| Table | `0` | `108` |
| Image + Chart | `0` | `139` |
| Formula | `0` | `16` |
| 可用物理页 | 弱页码近似 | `336` |

元素数量减少，是因为按句号切碎的内容重新合并成有语义边界的段落、标题、表格和公式。正文没有
丢失，章节、类型、阅读顺序和解析来源也恢复了。

### 检查最终答案是否覆盖了所讨论的论文

检索恢复后，仍有多论文答案只引用其中一篇。原来的提交检查只确认引用编号真实存在，并要求回答中
至少有一条本轮读取的引用。它无法发现“回答讨论了四篇论文，却只给一篇论文证据”。

因此在 `submit_research_answer` 前增加 `evaluate_evidence_coverage`：

```text
模型提交答案
-> 检查回答类型、格式和引用编号
-> 检查答案中提到的每篇论文是否已有原文证据
   -> 完整：结束本轮运行
   -> 不完整：返回具体错误，让同一轮继续处理
```

这项检查只读取产品运行时已经拥有的信息，不读取测试问题编号、指定证据位置、预期事实或人工标签。
它能拦住四类明显问题：只看论文卡片就回答、找到位置却没有读取、读取后没有引用，以及用标题等导航
文字支撑方法或数值结论。

## 遇到的问题

### 检索恢复，最终只多通过一个问题

统一构建流程后的主要结果是：

```text
指定证据进入候选列表：21/32 -> 16/32 -> 29/32
新增 15 个问题：       5/15  -> 1/15  -> 6/15
全量 MiniMax：                   13/30 -> 18/30
指定位置审计：                            29/29
```

候选列表已经恢复，剩余失败多发生在“找到”之后。模型有时没有继续读取，有时读了多篇论文却只引用
其中一篇，还有一次第二次提交被截断，答案只剩一半，旧检查仍然接受了它。

提交检查加入后，选择 4 个引用问题最明显的测试进行复跑：

| 问题 | 结果 | 变化 |
| --- | --- | --- |
| GAIA 与 WebArena 对比 | Fail | 两篇论文都有引用，但没引用 WebArena 的指定位置 |
| 四类 Agent Benchmark 推荐 | Fail | 四篇论文都有引用，只命中一个指定位置 |
| ReAct 到后续 Agent Evaluation | Pass | 三个指定位置都被找到、读取和引用 |
| Topic Change 到 GitHub Issue Agent | Fail | 不再无引用回答，但没引用指定位置 |

严格评分从 `0/4` 变成 `1/4`。论文覆盖改善了，证据位置选择没有一起解决。

### 提交错误会改变模型下一步的动作

Topic Change 问题第一次复跑时，模型已经给出 SWE-bench 推荐，又顺手比较 GAIA、tau-bench 和
ToolSandbox。提交检查要求为这些旁支内容补证，错误信息先写了“继续搜索”，模型随后真的搜索了三篇
不重要的论文，并把旧话题的 GAIA 证据带回答案。

错误信息调整为先删除与当前问题无关的内容，确有必要时再继续搜索。再次运行后，模型删除旁支比较，
只保留 SWE-bench 和两条正文引用。严格评分仍失败，但“继续旧话题”和“无引用回答”两个产品问题
消失了。

工具描述、拒绝原因和错误信息都会进入模型上下文。它们会影响模型接下来调用什么工具，不能只按
开发日志处理。

### 4 个问题用了约 60.9 万 Token

4 个重点问题合计使用：

```text
模型调用：45
普通工具调用：48
供应商报告 Token：约 608,855
耗时：约 6.2 分钟
```

其中 ReAct 相关问题经历 7 次提交拒绝，最终用了 19 次模型调用、26 次工具调用和约 36.4 万 Token。

提交检查能推动模型补证，也会在答案范围过宽时诱发过度搜索。严格评分增加一个问题，同时每个回答
多运行十几轮，还不足以证明这项规则适合直接进入产品。

## 结果

这轮排查保留了四项做法：

- 测试论文与产品论文共用正式 Parser 和 Reading Model 构建流程；
- 排查检索时固定查询并离线重放，避免把模型随机性算进检索变化；
- 运行时分别记录找到、读取和引用，最终评分在线下计算；
- 最终提交检查只约束论文级证据覆盖，不读取测试答案。

同时停止了三条路线：

- 不用“第一页优先”掩盖上游数据缺失；
- 不继续增加自建 Agent 阶段；
- 在没有人工判断替代证据质量前，不把指定位置失败直接归因于检索。

当前验证结果为：

```text
Python tests：                  76/76
固定测试数据：                 30/30
指定位置审计：                 29/29
固定查询的候选召回：           29/32
全量 MiniMax 历史参考：        18/30
提交检查重点问题：             1/4，改造前为 0/4
```

向量检索没有进入这轮修复。多数剩余目标证据与查询已有明显词语重合，主要问题发生在模型如何选择、
读取和引用已有候选。未来引入向量检索时，也需要继续分开报告这四个阶段。

## 保存了哪些评估数据

`EvalRecorder` 只保存原始运行记录：

```text
<run_id>/events.jsonl
<run_id>/result.json
```

事件记录模型响应、工具调用、候选位置、读取证据、最终引用、错误、耗时和 Token。候选召回、读取率、
引用率、成本和模型对比都由离线脚本计算。运行时不保存在线奖励，也不用评估分数控制回答。

这批数据以后可以用于定位回归、比较模型版本、分析常见工具路径，也可以整理成监督数据。若使用强
模型 API 生成并经过人工审核的高质量轨迹，还可以研究如何训练或蒸馏本地小模型；在进入训练前，
需要先处理隐私、授权、错误轨迹和人工标签质量。

## 复现资料

```bash
.venv-harness/bin/python -m unittest discover -s harness_py/tests

.venv-harness/bin/python -m harness_py \
  --manifest research/golden-data/manifest-expanded.yaml \
  validate

.venv-harness/bin/python research/golden-data/replay_saved_queries.py \
  --out /tmp/paperloom-saved-query-replay.json

.venv-harness/bin/python research/golden-data/analyze_evidence_funnel.py \
  --runs /tmp/paperloom-expanded-live \
  --eval-dump /tmp/paperloom-expanded-eval \
  --out /tmp/paperloom-evidence-funnel.json \
  --markdown-out /tmp/paperloom-evidence-funnel.md
```

相关资料：

- `harness_py/ONBOARDING.md`
- `research/golden-data/README.md`
- `research/golden-data/2026-07-13-reading-model-retrieval-practice.md`
- `docs/adr/0011-use-evidence-first-golden-cases-for-harness-eval.md`
- `docs/adr/0012-build-golden-schema-runtime-as-offline-eval-first.md`
- [`Best-of-2 Agent 编排实验`](best-of-two-agent-orchestration.md)
