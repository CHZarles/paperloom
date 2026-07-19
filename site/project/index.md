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
4. Python `ResearchHarnessService` 用锁定的论文 ID 创建 `JavaCorpusGatewayReader`，不再整批加载正文。
5. OpenAI Agents SDK `Runner` 在一个连续 Tool Loop 中决定搜索、读取和提交动作。
6. 位置搜索通过 Java Corpus API 调用 Qdrant，准确读取再回到 MySQL Current Reading Model。
7. Python 校验 Evidence 和最终提交，Java 持久化答案与 Reference Mapping。

[系统架构图与责任边界](/project/architecture)

## 论文访问范围怎样形成

普通上传只进入用户个人空间。管理员可以把自己空间中已经可检索的论文发布到全局论文库，发布动作不
重新运行 Parser，也不重建 Qdrant。

同一份 PDF 继续按 `paper_id` 共享底层文件、Reading Model 和 Qdrant Point。用户能访问一篇论文，
需要满足“个人空间中有记录”或“管理员已经全局发布”之一。处理中和失败的论文可以显示在个人空间，
但不会进入 Agent Scope。

[论文权限与索引生命周期的实现记录](/practice/architecture/paper-access-and-index-lifecycle-simplification)

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

Python 只保留本轮授权状态和 Agent 工具状态。论文发现、位置检索与准确读取通过 Java Corpus API 完成：

- Paper Candidate 使用 MySQL 中的 Title、Abstract、Author、Venue、Year、Filename 与 Identifier；
- Reading Location 使用 Qdrant 的 Sparse BM25 候选，并执行确定性的论文与 Lead Coverage；
- Java 校验论文权限、Current Reading Model 和激活的词法索引合同；
- 候选只返回不可引用 Preview，`read_locations` 再从 MySQL 读取准确原文并创建 Evidence ID。

Qdrant 保存的是可以重建的候选投影，MySQL 仍是准确论文内容的来源。产品没有静默回退到内存 BM25；
BM25 只保留在固定测试和离线对照中。

当前词法 Qdrant 已通过产品切换 Gate。69 条冻结查询上的指定证据命中为 `48/48`，完整 Case 为
`24/24`，MRR 为 `0.48019`；同一批查询的内存 BM25 对照为 `35/48`、`15/24`、`0.37838`。76 篇
广查询 p50 为 `132.1 ms`。

检索通过不代表回答通过。MiniMax 实际运行中有 `47/48` 份所需证据进入候选，只读取 `29/48`，
Expanded Hard Pass 为 `9/24`。当前工作已经转向 Candidate 之后的证据选择、阅读和引用。

[阅读 Sparse Qdrant 切换与 MiniMax 证据缺口](/practice/evaluation/qdrant-bm25-cutover-minimax-evidence-gap)

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
- 用户个人论文空间、管理员全局发布和统一可检索状态；
- 基于 OpenAI Agents SDK 的单路径 Python Research Harness；
- Java Corpus API、Qdrant Sparse BM25 Candidate Retrieval 与 MySQL Exact Read；
- Evidence Ledger、Citation Validation、`evaluate_evidence_coverage` 与可重开的历史引用；
- Evidence-first Golden Case、Per-run Trace、Human Label 与 Judge Calibration；
- Vue 3 论文库、研究进度、来源选择和 Evidence Inspection。

## 还没做到的部分

PaperLoom 仍在持续开发。目前还没有做到：

- 任意文档格式都能稳定进入同一 Reading Model；
- 所有 PDF 都能抽取可靠的作者、会议和 DOI；
- 坐标级高亮和完整 Citation Graph；
- 团队共享论文空间与服务端 PDF 内容哈希校验；
- 数千篇论文、多副本并发、Snapshot / Restore 和滚动重启验证；
- 在未见论文和更大 Corpus 上验证当前词法检索合同；
- 在 Live Assistant 中使用 Multimodal Retrieval；
- Eval 分数可以直接代表所有真实用户满意度；
- 增加模型调用次数就一定能提高研究质量。

上面的缺口构成当前产品边界，实验和路线更新都以现有实现为起点。

## 继续阅读

- [系统架构](/project/architecture)
- [Reading Model 与 `harness_py` 工具](/project/reading-model)
- [评估方法](/project/evaluation)
- [边界与路线](/project/roadmap)
- [工程演化](/evolution/)
- [实践文章](/practice/)
