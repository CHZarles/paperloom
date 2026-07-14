# Python 研究 Harness

这个包负责 PaperLoom 的论文研究回合。OpenAI Agents SDK 是唯一运行时：SDK `Runner` 执行
模型与工具循环，Harness 负责论文范围、工具授权、证据引用、最终答案校验和跨回合记忆。

## 阅读入口

- [OPENAI_AGENTS_SDK_GUIDE.md](OPENAI_AGENTS_SDK_GUIDE.md)：从零理解 `Agent`、`Runner`、
  Function Tool、Context、Session、Hooks 和 Model。
- [ONBOARDING.md](ONBOARDING.md)：用本项目核心代码理解一次研究回合，以及如何扩展 Harness。
- [Golden Data 与 Harness 演化](../site/practice/evaluation/golden-data-harness-evolution.md)：
  Candidate 已恢复后，如何在最终提交门处理 Not Read、Read Not Cited 和多论文引用缺口。
- [Reading Model 与检索实践复盘](../research/golden-data/2026-07-13-reading-model-retrieval-practice.md)：
  记录本轮数据变化、错误方案、分层诊断方法和可复用检查清单。
- [Provider 迁移实验](../site/practice/evaluation/provider-migration-experiment.md)：
  记录 Responses API 接入、凭据边界、Smoke Test、全量回归，以及为什么换 GPT 后通过率反而下降。

第一次接触 OpenAI Agents SDK 时，先读 SDK Guide；已经熟悉 SDK 时，直接读 ONBOARDING。

## 核心结构

```text
core/                         公共模型、状态和错误
corpus/                       论文导航、正文读取和 Evidence 授权
orchestration/
  runtime.py                  HarnessRuntime 契约和唯一 Runtime 构造入口
  live_chat.py                单回合产品边界
  conversation.py             跨回合产品记忆
  memory.py                   请求级 SDK Session 适配
  evidence_coverage.py        最终答案的跨论文 Candidate / Read / Cited 覆盖门
  research_contract.py        Agent 指令和最终答案契约
  run_output.py               引用渲染和标准 Run 构造
  agents/
    runtime.py                Agent、Runner、Session、Hooks 的组装
    tools.py                  FunctionTool 分发和最终提交门
    context.py                单次 Run 的业务状态
    model.py                  MiniMax Chat Completions 与 GPT/Codex Responses Model 适配
evaluation/                   离线校验、评分、Judge 和运行记录
transport/                    Provider 配置和供 Java 调用的 HTTP 服务
cli.py                        命令行入口及依赖组装
```

主执行路径只有一条：

```text
LiveResearchChatHarness
  -> AgentsSdkHarnessRuntime
  -> OpenAI Agents SDK Runner.run()（单路径）
  -> FunctionTools
  -> submit_research_answer
  -> build_harness_run()
  -> ConversationState.updated_from_run()
```

## 快速开始

在仓库根目录 `/path/to/paperloom` 运行：

```bash
python3 -m venv .venv-harness
.venv-harness/bin/python -m pip install -r harness_py/requirements.lock

export MINIMAX_API_BASE_URL=https://api.minimaxi.com/v1
export MINIMAX_API_KEY=...
export MINIMAX_MODEL=MiniMax-M3

.venv-harness/bin/python -m harness_py chat-shell --provider-source env
```

`chat` 和 `chat-shell` 从产品数据库加载论文。`--provider-source env` 只表示模型 Provider 来自
环境变量，不改变 Corpus 的来源。

启动供 Java 调用的内部服务：

```bash
.venv-harness/bin/python -m harness_py serve \
  --host 127.0.0.1 \
  --port 8091
```

Java 调用 `/v1/research/stream` 获取 NDJSON 进度和最终结果，或调用
`/v1/research/turn` 获取同步结果。Java 负责用户权限和长期会话持久化；Python 只加载请求中
`scope.paper_ids` 明确授权的论文。

## 常用命令

```bash
# 对产品库执行一次问题
.venv-harness/bin/python -m harness_py chat \
  --provider-source env \
  --question "这篇论文的核心方法是什么？"

# 运行一条真实研究 Case
.venv-harness/bin/python -m harness_py agent-run \
  --provider-source env \
  --case-id transformer_adam_params_001 \
  --out /tmp/paperloom-agent-run

# 运行 Python 测试
.venv-harness/bin/python -m unittest discover -s harness_py/tests
```

Golden Data 的结构和离线校验方式见
[`research/golden-data/README.md`](../research/golden-data/README.md)。运行记录通过
`EVAL_DUMP_DIR` 或 `--eval-dump DIR` 开启，每次执行写入
`<DIR>/<run_id>/events.jsonl` 和 `<DIR>/<run_id>/result.json`；运行时不会读取这些文件来驱动回答。
当前生产 Runtime 每轮创建一个 `ResearchRunContext`、一个 Model、一个 Session 和一个 SDK Runner。

Corpus 检索把 canonical Reading Element 和物理 `PaperPage` 分成两个检索面：元素负责语义
内容，页表面负责真实页码 Grounding。只有带正式物理页 provenance 的 Reading Model 才会启用
PAGE 候选；它不会改变语义元素的 BM25 统计，也不是首页或相邻页保底规则。
