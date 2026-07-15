# PaperLoom

**PaperLoom 是一个面向研究论文 PDF 的、证据有界的 Agentic RAG 阅读工作台。**

用户看到的产品叫 **Folio**。它把论文转化为带页码和阅读顺序的 Reading Model，让 Research
Agent 只在当前会话明确授权的论文中使用工具检索和阅读，并让模型运行结束后的引用仍能重新打开。

[项目网站](https://chzarles.github.io/paperloom/) · [项目文档](docs/README.md) ·
[English README](README.md)

## 项目关注的问题

许多 RAG Demo 优化的是“从问题到一段看起来合理的文本”。PaperLoom 把“从声明回到原始证据”
也作为产品契约：

- PDF 被保留为有序页面、章节、类型化 Reading Element、Location 与视觉资产；
- Java 在研究开始前确定权限、论文范围、配额、会话和持久化边界；
- Python 在 Java 锁定的 Reading Model Scope 内运行一个工具型 Agent 循环；
- Candidate Preview 只负责导航，只有读取准确位置后才会产生可引用 Evidence；
- 最终提交必须通过 Evidence Ledger 和 Coverage Gate 校验；
- 历史引用持续连接到支持该声明的论文位置。

这个仓库也记录项目的工程演化。ADR 和筛选后的实验复盘会保留真实决策、失败路径、成本与
验证结果，而不是只保留最后的成功叙述。

## 当前真实链路

[![PaperLoom 系统架构图](site/public/images/paperloom-system-architecture.png)](site/public/images/paperloom-system-architecture.svg)

当前研究回合只有一条产品路径：

```text
ChatHandler
-> ProductReadingConversationService
-> PythonResearchHarnessClient
-> POST /v1/research/stream
-> ResearchHarnessService
-> OpenAI Agents SDK Runner
-> Java Corpus API
-> Qdrant Candidate + MySQL 精确读取
```

Java 传入 `user_id` 和已经锁定的论文 ID；Python 通过 Java Corpus API 向 Agent 暴露论文发现、
身份解析、位置检索、准确读取、研究方法指导和最终提交工具，不再把整批 Reading Element 加载到
每个 Harness Replica。

Java 将 Current Reading Model Location 索引到 Qdrant，执行 Dense + Sparse 双路召回和确定性
Rank Fusion，再从 MySQL Hydrate 并校验候选。Qdrant 只是 Candidate Index；`read_locations` 仍是
唯一产生可引用 Evidence 的正文入口。

## Reading Model

PaperLoom 不把 Parser 原始输出或搜索索引当成论文的长期产品表示。Reading Model 保存：

- 模型版本、Ready 状态、Parser 来源、统计与诊断；
- 带 Source Span 的物理页面和可阅读章节；
- Heading、Paragraph、List、Table、Figure、Chart、Formula、Footnote、Aside、Code 等
  canonical Reading Element；
- Page、Section、Table、Figure 等正式 Location；
- PDF 页面截图以及 Table、Figure、Chart Crop。

完整 Reading Model 由 Java 管理。Live Harness 只加载锁定范围内的论文 Metadata；Java 负责
Qdrant Projection、Current Model 校验和精确 Canonical Read。
详见 [Reading Model 与 Agent 工具](docs/architecture/reading-model-and-agent-tools.md)。

## Agent 工具协议

Agent 不能从一个授权 Paper ID 直接跳到 Citation。当前授权阶梯是：

```text
Java 授权论文范围
-> Candidate 或 Identity Tool 公开论文
-> Location Search 公开位置
-> 读取准确位置
-> 创建 Evidence ID
-> 最终答案针对已知 Evidence 校验
```

`read_locations` 是唯一会创建可引用内容证据的工具；`submit_research_answer` 必须独占最后一步。

## 架构边界

| 区域 | 当前责任 |
| --- | --- |
| Folio | Vue 3 研究工作台、论文选择、进度、会话与证据重开 |
| Java 产品边界 | 认证、授权、Source Scope 锁定、配额、取消、长期会话与 Reference Mapping |
| Python 研究边界 | Agents SDK 循环、工具执行、Disclosure State、Evidence Ledger、引用校验与最终提交 |
| Java Corpus 数据面 | 论文授权、Embedding、Qdrant 检索、Current Model 校验和精确读取 |
| MySQL | 产品论文、Canonical Reading Model、会话和长期 Reference 数据 |
| Qdrant | 以稳定 `location_ref` 为键的可重建 Dense/Sparse Candidate Index |
| MinIO | 原始 PDF、Parser Artifact、页面截图与局部裁剪图 |
| Model Provider | Agents SDK Runtime 使用的可配置 OpenAI-compatible 模型 |

Qdrant 只提供导航 Candidate，不直接提供 Evidence；准确 MySQL Read 保留声明到原始位置的连接。

## 评估与可积累数据

PaperLoom 同时记录结果和可观察的研究行为。可选的 Per-run Capture 会保存有序的模型请求与响应、
工具调用、授权状态变化、Evidence、校验、Token、延迟和失败事件。Golden Case 则定义 Required /
Forbidden Paper 与 Evidence、Expected Fact、Claim、Outcome、Citation Policy、Trace Obligation、
Human Label 和 Judge Calibration。

这些数据以后可以用于 BM25 与检索策略调优、Tool Policy 分析、Provider / Cost Routing、Judge
校准、Dense Retriever 或 Reranker 训练，以及用强第三方 API 蒸馏本地小模型。蒸馏目标应当是
可观察且已通过校验的工具轨迹与答案，而不是隐藏 Chain-of-thought。详见
[评估系统](docs/evaluation/README.md)。

## 快速开始

需要 Java 17、Maven 3.8+、Node.js 18.20+、pnpm 8.7+、Python 3.11+、Docker Compose v2。
真实 PDF 导入还需要单独安装 MinerU。

```bash
cp .env.example .env
# 填写数据库、对象存储、JWT、内部服务和模型相关配置。

docker compose --env-file .env -f docs/docker-compose.yaml up -d

python3 -m venv .venv-harness
.venv-harness/bin/pip install -r harness_py/requirements.lock
scripts/paperloom-start-harness.sh start

mvn spring-boot:run
```

如果这是从 Elasticsearch 升级的已有数据，后端启动后需要用管理员 Token 执行一次 Current
Reading Model 回填；新上传论文会自动写入 Qdrant：

```bash
curl -X POST http://localhost:8081/api/v1/admin/retrieval/reindex-current \
  -H "Authorization: Bearer $ADMIN_JWT"
```

这一步会调用 Embedding Provider，可能产生费用，不应在普通启动流程中重复执行。

另开一个终端：

```bash
cd frontend
corepack pnpm install
corepack pnpm dev
```

前端默认地址为 `http://localhost:9527`，后端默认地址为 `http://localhost:8081`。
完整步骤见[快速开始文档](docs/getting-started/quick-start.md)。

## 项目状态

PaperLoom 仍在持续开发。目前的明确边界是研究论文 PDF 导入、可检查的 Reading Model、授权范围内
的 Agentic Retrieval，以及有来源的研究问答。

项目暂不宣称已经稳定支持任意文档类型、坐标级 PDF 高亮、自动引文网络、Multimodal Retrieval、
大规模 Dense 检索质量与容量结论，或所有 PDF 的可靠元数据抽取。

## 继续阅读

- [文档索引](docs/README.md)
- [架构概览](docs/architecture/overview.md)
- [Reading Model 与 Agent 工具](docs/architecture/reading-model-and-agent-tools.md)
- [证据与引用模型](docs/architecture/evidence-and-citations.md)
- [评估系统](docs/evaluation/README.md)
- [工程演化记录](docs/engineering-evolution/README.md)
- [项目实践网站](https://chzarles.github.io/paperloom/)

PaperLoom 使用 [Apache License 2.0](LICENSE)。第三方组件继续遵循各自许可证，详见
[Third-Party Notices](THIRD_PARTY_NOTICES.md)。
