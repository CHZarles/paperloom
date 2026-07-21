# Python Research Harness

这个包负责 PaperLoom 当前产品中的论文研究回合。OpenAI Agents SDK 是唯一 Live Runtime：SDK
`Runner` 执行模型与工具循环，Harness 负责 Corpus 投影、论文与位置授权、Evidence、最终答案
校验、跨回合研究记忆和可选 Eval Capture。

## 先理解产品边界

Live Path 是：

```text
Java ChatHandler
-> ProductReadingConversationService
-> PythonResearchHarnessClient
-> POST /v1/research/stream
-> ResearchHarnessService
-> LiveResearchChatHarness
-> AgentsSdkHarnessRuntime
-> OpenAI Agents SDK Runner
-> Java Corpus API
-> Qdrant 候选检索 + MySQL 精确读取
```

Java 是权限和语料数据源。每个请求都必须携带 `user_id` 与 `scope.paper_ids`，Python 不能扩大
当前会话范围，也不持有 Qdrant 管理权限。Java 同时负责 Current Reading Model、Qdrant Sparse BM25
索引生命周期、取消、长期会话和 Product Reference Mapping；Python 负责授权范围内的模型编排。

## 阅读入口

- [OPENAI_AGENTS_SDK_GUIDE.md](OPENAI_AGENTS_SDK_GUIDE.md)：理解 `Agent`、`Runner`、
  Function Tool、Context、Session、Hooks 和 Model。
- [ONBOARDING.md](ONBOARDING.md)：沿一次真实研究回合阅读核心代码。
- [Reading Model 与 `harness_py` 工具](../docs/architecture/reading-model-and-agent-tools.md)：理解持久化模型、
  Live Projection、BM25 和授权阶梯。
- [Evaluation System](../docs/evaluation/README.md)：理解 Run Capture、Golden Case 与数据利用方向。
- [Reading Model 与检索实践复盘](../research/golden-data/2026-07-13-reading-model-retrieval-practice.md)：
  数据变化、错误方案和分层诊断记录。
- [Qdrant 检索影响量化报告](../docs/evaluation/qdrant-retrieval-impact-2026-07-15.md)：
  同查询下的相关性、延迟、内存、索引、可靠性和 MiniMax 小样本对照。
- [Java/Qdrant 工程实践](../site/practice/evaluation/qdrant-retrieval-impact-benchmark.md)：
  记录引入共享向量索引的背景、收益、退化、故障和保留边界。

第一次接触 Agents SDK 时先读 SDK Guide；已经熟悉 SDK 时可直接读 ONBOARDING 和本页的
执行路径。

## 核心结构

```text
core/                         公共模型、状态和错误
corpus/
  gateway.py                  复用连接池调用 Java Corpus API
  product_db_dataset.py       Golden/CLI 显式使用的本地 MySQL 适配器
  tools.py                    统一 Tool Schema、授权状态和 Evidence Ledger
orchestration/
  live_chat.py                单回合产品边界与 Eval Recorder 生命周期
  conversation.py             跨回合研究记忆
  evidence_coverage.py        `evaluate_evidence_coverage` 与提交错误生成
  research_contract.py        Agent 指令和最终提交契约
  run_output.py               标准 Run、引用渲染和进度投影
  agents/
    runtime.py                Agent、Runner、Session、Hooks 的组装
    tools.py                  FunctionTool 分发、授权状态记录与最终提交门
    context.py                单次 Run 的可变业务状态
    model.py                  Chat Completions / Responses Provider 适配与 HTTP Capture
evaluation/                   Fixture、Golden Case、评分、Judge 和 Run Recorder
transport/
  service.py                  供 Java 调用的同步与 NDJSON Streaming HTTP 边界
  provider_config.py          Model Provider 配置
cli.py                        产品 Corpus Chat、真实 Case 与离线评估入口
```

每个产品回合创建一个新的 `ResearchRunContext`、Model、Request-backed Session 和 SDK Runner。
Runtime 本身不持有长期会话状态。

## Live Corpus Gateway

`ResearchHarnessService` 从请求取出 Java 已锁定的 Paper ID，并先用这些 ID 创建轻量 Metadata
外壳；这一步不访问 Java，也不枚举论文正文。只有模型真正调用论文发现或身份解析工具时，
`JavaCorpusGateway` 才读取并原地补全对应 Metadata。正文 Reading Element 不再整批进入 Harness：

- `search_paper_candidates` / `find_papers_by_identity` 调 Java Paper Corpus API；
- `find_reading_locations` 调 Java 的 Qdrant Sparse BM25 检索，只获得候选 `location_ref`；
- Java 对每个候选重新校验用户权限、锁定 Scope 和 Current Reading Model；
- Java 只查询各论文 Current Reading Model 对应的词法索引合同，并校验 Collection Schema；
- `read_locations` 再由 Java 从 MySQL 精确读取 Canonical Location；
- Python 只在真实 Read 后生成 `ev_...`，继续维护 Disclosure 和 Evidence Ledger。

`DockerMySqlProductCorpusStore` 只供 Qdrant 产品探针和离线迁移诊断使用，不是 CLI 或运行时入口。
Java Corpus Token 为空、Qdrant Collection 缺失、Current Model 不可检索或词法索引合同不一致时，
产品回合都会明确失败；Harness 不创建 Collection，也不会改走内存 BM25。

## 当前检索

产品检索由 Java 数据面执行：Java 将查询编码成 BM25 风格 Sparse Vector，Qdrant 在
`lexical_bm25_v1` 上执行一次词法检索，Java 再做确定性的论文与 Lead Coverage，并从 MySQL Hydrate
和验证候选。Qdrant Payload 不是 Evidence，Tool 仍只返回非引用 Preview 与 `location_ref`；
`read_locations` 是唯一生成 Evidence ID 的入口。

Golden Fixture、离线 Audit 和单元测试继续使用 `ReadingCorpusTools` 的内存 BM25 Adapter，因此无需
启动 Java 或 Qdrant。两条路径共享同一套模型可见 Tool Schema 和授权状态机。

## Agent 可见工具

| Tool | 责任 | 是否产生可引用 Evidence |
| --- | --- | --- |
| `search_paper_candidates` | 在固定 Corpus 内发现或浏览论文 Metadata，并公开 Paper | 否 |
| `find_papers_by_identity` | 用 Title、Filename、DOI、arXiv ID、Author、Year 做唯一身份解析 | 否 |
| `find_reading_locations` | 在已公开论文中通过 Java/Qdrant 找 Current Location | 否 |
| `read_locations` | 读取已公开的准确 Location，并写入 Evidence Ledger | 是，唯一入口 |
| `get_research_skill` | 按需读取研究范式指导 | 否 |
| `submit_research_answer` | 提交 Outcome、Markdown 和 Fields，通过最终校验后结束 Run | 不创建；只能引用已知 Evidence |

供应商返回纯文本时，内部 `_continue_research_turn` Tool 会要求模型继续遵守最终提交协议。它不是
产品能力。

## 授权阶梯

```text
Java-authorized scope
-> Candidate / Identity Tool 公开 Paper
-> Location Search 公开 Location
-> read_locations 读取 Location
-> Evidence ID 进入 Ledger
-> submit_research_answer 针对 Known Evidence 校验
```

这几个集合在 `ResearchRunContext` 中独立记录。Tool Capture 同时保存调用前后的授权快照，因此可以
区分“Retriever 没找到”“Agent 没读”“读了没引用”和“只引用弱 Evidence”。

`submit_research_answer` 必须独占最终 Tool Step。校验失败不会直接结束 Run，而是把结构化错误返回给
同一个 Agent，让它补读、补引或删除无证据声明。

## 快速开始

在仓库根目录运行：

```bash
python3 -m venv .venv-harness
.venv-harness/bin/python -m pip install -r harness_py/requirements.lock

export MINIMAX_API_BASE_URL=https://api.minimaxi.com/v1
export MINIMAX_API_KEY=...
export MINIMAX_MODEL=MiniMax-M3

.venv-harness/bin/python -m harness_py serve \
  --host 127.0.0.1 \
  --port 8091
```

Java 使用 `/v1/research/stream` 获取 NDJSON Progress 和最终结果；`/v1/research/turn` 提供同步
边界。Corpus 固定经过 Java/Qdrant；Health Endpoint 是 `/health`。

## 常用命令

```bash
# 通过唯一的产品 Java/Qdrant 链路运行 Golden Case
.venv-harness/bin/python -m harness_py agent-run \
  --provider-source env \
  --product-corpus-map research/golden-data/product-corpus-map.local.yaml \
  --case-id transformer_adam_params_001 \
  --out research/golden-data/local-runs/agent-run-qdrant

# 运行 Python 测试
.venv-harness/bin/python -m unittest discover -s harness_py/tests

# 不调用模型，按当前 Scorer Contract 复评分已保存 Run
.venv-harness/bin/python -m harness_py \
  --manifest research/golden-data/manifest-expanded.yaml \
  rescore \
  --runs research/golden-data/local-runs/<run>/minimax-expanded-final \
  --out research/golden-data/local-runs/<run>/score-report-v4.json

# 运行 Java Corpus/Qdrant 与全仓后端回归
mvn test
```

Golden Data 的结构和离线校验方式见
[`research/golden-data/README.md`](../research/golden-data/README.md)。
当前 Score Report 为 `harness-score-report/v4`，包含 `behavior-scorer/v4` Contract Hash。答案按
Golden Claim、同一 Markdown Block 的 accepted product location 引用和 Typed Facts 评分；Anchor
只用于离线审计。普通模型输出的任意 `fields` 不作为隐藏评分合同。

真实 Qdrant 协议可用 `QdrantClientRealSmokeTest` 显式验证。测试默认跳过；只有同时设置
`QDRANT_REAL_SMOKE_URL`、`QDRANT_REAL_SMOKE_API_KEY` 和 `QDRANT_REAL_SMOKE_COLLECTION` 时才会运行，
并覆盖认证、Sparse-only Collection 创建、Current Model 过滤、Schema 校验、清理和缺失 Collection。

## Eval Capture

通过 `EVAL_DUMP_DIR` 或 `--eval-dump DIR` 开启：

```text
<DIR>/<run_id>/events.jsonl
<DIR>/<run_id>/result.json
```

Recorder 保存 Run Input、Conversation、Scope、Previous Evidence、Model Request / Response / Error、
Retry、Tool Raw / Parsed Arguments、Internal / Model-visible Result、Authorization State Before / After、
Final Draft、Validation Failure、Evidence、Citation、Token、Latency 与 Failure。

Authorization、Cookie、API Key 等 Header 会被删除，但 Body 仍可能包含用户问题、论文内容和模型
输出，因此这些文件必须按敏感本地数据处理。Runtime 不会读取 Eval Dump 来驱动产品回答。
