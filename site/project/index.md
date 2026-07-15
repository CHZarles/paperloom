---
title: 项目介绍
description: PaperLoom 是什么，它如何成为一个证据有界的 Agentic RAG 系统，以及当前真实边界。
---

# PaperLoom

PaperLoom 是面向研究论文 PDF 的 **evidence-bounded agentic RAG** 工作台，用户界面叫 Folio。

一次回答开始前，Java 会锁定当前用户有权访问的论文。Agent 随后决定搜索词、阅读位置和工具顺序。
`read_locations` 读到的原文才会获得 Evidence ID，最终答案还要通过引用与 Coverage 校验。会话保存
以后，用户仍能从历史引用回到原论文。

## 四个设计问题

| 命题 | PaperLoom 的回答 |
| --- | --- |
| PDF 如何成为长期可读的数据 | 用产品自己的 Reading Model 保存页面、章节、类型化元素、Location、关系与视觉资产 |
| Agent 可以有多大自主性 | 可以自主选择和重复研究工具，但不能扩大论文范围、跳过位置公开或伪造 Evidence |
| RAG 的 Evidence 何时成立 | Candidate 和 Preview 只负责导航；`read_locations` 读取准确位置后才创建 Evidence ID |
| 如何知道系统为什么失败 | 分开记录 Candidate、Read、Cited、Outcome、Trace、Token、Latency 与 Validation Failure |

## 当前真实运行路径

当前实现里，一次研究回合依次经过：

1. `ChatHandler` 接收产品消息并锁定当前用户可访问的论文集合。
2. `ProductReadingConversationService` 组装会话历史、研究记忆和结构化阅读锚点。
3. `PythonResearchHarnessClient` 预留配额并调用 `/v1/research/stream`。
4. Python `ResearchHarnessService` 只从 MySQL 加载 Java 明确授权的论文。
5. OpenAI Agents SDK `Runner` 在一个连续 Tool Loop 中执行研究。
6. Python 校验 Evidence 和最终提交，Java 持久化答案与 Reference Mapping。

[系统架构图与责任边界](/project/architecture)

## Agentic RAG 的依据

检索在这里是一段由 Agent 推进的研究过程。它可以根据问题选择：

- 浏览或搜索论文 Metadata；
- 用结构化身份线索解析一篇特定论文；
- 改写 Query 并多次寻找 Reading Location；
- 读取一个或多个准确位置；
- 调用研究方法 Skill；
- 回答、部分回答、澄清或 Abstain；
- 在最终提交被拒绝后补读、补引或删除无证据声明。

搜索词、工具顺序和补读策略交给模型；论文范围、Location 公开、Evidence 身份和最终提交规则由
Java Scope、Tool Disclosure、Evidence Ledger 与 Submission Validator 固定下来。

## 当前检索是什么

Python 直接从 MySQL 取得当前 Ready Reading Model 的 Metadata、Reading Element 和视觉资产可用性，
然后在内存中检索：

- Paper Candidate 使用 Title、Abstract、Author、Venue、Year、Filename 与 Identifier；
- Reading Location 使用 BM25；
- Passage、Lead、Section、Exact Phrase、Adjacent Paragraph 与 Coverage Heuristic 补充排序；
- Search Result 只给非引用 Preview，准确读取后才生成 Evidence。

当前 Assistant Answer Path **没有调用 Embedding 或 Dense Vector Retrieval**。向量召回是否进入产品，
取决于 Held-out Evaluation 对 Candidate、Read、Cited、Hard Pass、延迟和成本的完整检查。

## Reading Model 为什么重要

Parser 输出会随解析器版本变化。Parser 与 Agent 之间的 Reading Model 负责稳定表达：

- 物理页、Reading Order 与 Section Range；
- Paragraph、Table、Figure、Chart、Formula、Footnote、Aside、Code 等类型化内容；
- Caption Fragment、Panel 与 Parent Element 的关系；
- BBox、Source Span、Parser Provenance 与 Structured Payload；
- Page、Section、Table、Figure Location；
- 页面截图与 Table / Figure / Chart Crop。

[阅读 Reading Model 与 Agent Tool 的完整分析](/project/reading-model)

## 已经跑通的部分

- 研究论文 PDF 上传、MinerU 解析与 Parser Artifact 保留；
- Versioned Reading Model、页面、章节、类型化元素、Location 与视觉资产；
- 论文库、显式 Source Scope、持续会话与点击来源追问；
- 基于 OpenAI Agents SDK 的单路径 Python Research Harness；
- Java Corpus API、Qdrant Dense/Sparse Candidate Retrieval 与 MySQL Exact Read；
- Evidence Ledger、Citation Validation、`evaluate_evidence_coverage` 与可重开的历史引用；
- Evidence-first Golden Case、Per-run Trace、Human Label 与 Judge Calibration；
- Vue 3 论文库、研究进度、来源选择和 Evidence Inspection。

## 还没做到的部分

PaperLoom 仍在持续开发。目前还没有做到：

- 任意文档格式都能稳定进入同一 Reading Model；
- 所有 PDF 都能抽取可靠的作者、会议和 DOI；
- 已经实现坐标级高亮或完整 Citation Graph；
- 大规模 Dense Retrieval 的质量、容量和高可用验证；
- 在 Live Assistant 中使用 Multimodal Retrieval；
- Eval 分数可以直接代表所有真实用户满意度；
- 增加模型调用次数就一定能提高研究质量。

这些缺口构成当前产品边界，实验和路线更新都以现有实现为起点。

## 继续阅读

- [系统架构](/project/architecture)
- [Reading Model 与 `harness_py` 工具](/project/reading-model)
- [评估方法](/project/evaluation)
- [边界与路线](/project/roadmap)
- [工程演化](/evolution/)
- [实践文章](/practice/)
