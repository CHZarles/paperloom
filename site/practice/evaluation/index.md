---
title: 评估实践
description: 记录论文数据、检索、Agent 工具、模型对照和人工审核中的问题与结果。
aside: false
pageClass: practice-index-page
prev: false
next: false
---

# 评估实践

这四篇文章来自同一轮评估。起点是新增测试论文后检索和回答同时下降。后续依次检查论文数据、
同一个问题运行两次、直接更换模型，以及自动评分与人工判断为什么不一致。

<PracticeArchive category="评估" :show-filters="false" />

## 背景

2026 年 7 月，Python Research Harness 增加了一批 Agent Evaluation 论文和 15 个新问题。Harness
是项目中负责模型搜索论文、读取原文、调用工具并提交答案的运行流程。

检索改用基于词频排序的 BM25 后，新问题从 `5/15` 掉到 `1/15`。排查后确认，新增论文使用了简化
PDF 文本流程，章节、内容类型、物理页和解析来源都没有进入 Reading Model。Reading Model 是论文
经过解析后得到的结构化阅读数据。

统一数据结构以后，指定证据进入候选列表的次数从 `16/32` 回到 `29/32`，全量 MiniMax 历史参考达到
`18/30`。检索恢复后，严格评分只多通过一个问题。后续实验因此分别观察检索是否找到、模型是否
读取、答案是否引用、最终回答类型和成本。

## 四次实验留下的结果

<div class="practice-result-list">
  <div class="practice-result-row">
    <strong>测试论文与产品论文共用正式解析流程</strong>
    <p>指定证据进入候选列表恢复到 <code>29/32</code>。这项修复已经保留。</p>
  </div>
  <div class="practice-result-row">
    <strong>同一个问题交给两个隔离的 Agent，再选择一份答案</strong>
    <p>最终结果为 <code>16/30</code>，低于单路径。并行方案已经删除，两次运行的离线比较方法保留。</p>
  </div>
  <div class="practice-result-row">
    <strong>保持数据和工具不变，只更换模型与 API</strong>
    <p>测试模型严格评分 <code>11/30</code>，10 个问题耗尽预算。API 适配保留，默认模型不切换。</p>
  </div>
  <div class="practice-result-row">
    <strong>隐藏模型名称，人工审核 60 份答案</strong>
    <p>GPT-5.5 人工通过 <code>28/30</code>，MiniMax-M3 人工通过 <code>22/30</code>。Hard Pass 改按固定规则与指定证据位置的一致性解释。</p>
  </div>
</div>

当前产品每个问题只运行一个 Agent，使用 MiniMax 模型，并在用户有权访问的论文中执行内存 BM25
检索。找到、读取、引用、固定规则评分和人工质量分别报告。目前没有增加新的模型自检层。

[查看全部实践记录](/practice/)
