---
title: 边界与路线
description: PaperLoom 当前正在解决什么，以及哪些能力还没有完成。
---

# 边界与路线

路线只收录能指向具体失败、实验数据或产品缺口的工作。

## 当前重点

### Evidence Selection

当前 Candidate Coverage 已经好于早期版本，Agent 仍会在多个语义相关位置中选错 canonical
evidence。接下来会改进位置检索、多位置阅读和 claim-to-evidence 验证。现有 Best-of-2 数据不支持
继续增加采样次数。

### Bounded Research Orchestration

并行采样降低了部分墙钟延迟，同时显著增加总 Token，候选池上限也没有提高。预算和采样数需要跟随
问题复杂度，固定 Best-of-N 暂时不会进入默认路径。

### Reading Model Coverage

继续提高图表、公式、跨页结构和视觉资产的闭环能力，同时保持 Parser Artifact 与产品 Reading
Model 的边界。

### Eval-gated Dense Retrieval

当前 Live Harness 使用内存 BM25。以后会从 canonical Reading Element 派生 Embedding，测试 Lexical /
Dense Candidate Fusion，也可能用保存的 Query-Read 数据训练 Reranker。只有 Held-out Candidate、
Read、Cited、Hard Pass、Latency 和 Cost 一起改善，相关实现才会进入默认链路。

### Product Verification

让上传、解析、选择论文、研究、历史会话与引用重开形成稳定的浏览器级回归链路。

## 不采用的捷径

- 不用静态关键词模拟通用语义路由。
- 不用测试集特有的第一页或摘要特权掩盖 Parser 数据问题。
- 不在检索失败后生成一个看似正常的无证据答案。
- 不把评估语料混进产品论文库提高表面召回。
- 不把增加模型调用次数当作默认质量策略。
- 不因为仓库里已经存在 Index 或 Embedding，就把它写成当前 Assistant 能力。

## 进入产品前的检查

1. 明确用户问题和产品边界。
2. 保存当前行为和失败证据。
3. 用独立实验验证候选生成、编排和最终选择。
4. 同时报告质量、延迟、Token 与技术失败。
5. 通过产品行为回归后再进入默认路径。
