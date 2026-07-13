# Harness 上手指南

这份文档是 Python 研究 Harness 的代码地图。先记住一个最小模型：Java 提供已经授权的
论文范围和会话历史；Python 执行一个由模型驱动的研究回合；确定性代码校验模型提交的
答案，然后返回答案、证据、用量和更新后的研究记忆。

Harness 的核心职责是模型编排。Golden Data 分析和 LLM Judge 都在线下完成。运行时只在
需要时保存有序、去重的事件日志和最终结果，线上流程不会再读取这些文件。

## 从这里开始

在仓库根目录 `/home/charles/PaiSmart` 运行：

```bash
python3 -m venv .venv-harness
.venv-harness/bin/python -m pip install -r harness_py/requirements.lock

.venv-harness/bin/python -m harness_py --help
.venv-harness/bin/python -m harness_py validate
.venv-harness/bin/python -m unittest discover -s harness_py/tests
```

建议按这个顺序读代码：

1. `orchestration/live_chat.py`：请求级包装器，也是单回合与持久会话状态之间的边界。
2. `orchestration/runtime.py`：很小的运行时接口，以及运行时选择逻辑。
3. `orchestration/agents/runtime.py`：OpenAI Agents SDK 工具循环的组装和执行。
4. `orchestration/agents/tools.py`：工具分发、进度事件、答案校验和评测事件。
5. `corpus/tools.py`：论文发现和证据授权状态机。
6. `orchestration/conversation.py`：Harness 接收并返回的持久会话状态。
7. `transport/service.py`：Java 使用的内部 HTTP 契约。

## 目录地图

```text
harness_py/
  core/                    公共数据结构、契约、状态和错误
  corpus/                  语料加载、解析、检索和证据生成
  orchestration/           会话状态和与运行时无关的回合编排
    agents/                默认的 OpenAI Agents SDK 实现
    legacy/                手写工具循环和 MiniMax 直连客户端，仅用于回滚
  evaluation/              Golden 加载、fixture、审计、评分、judge 和评测记录
  transport/               模型供应商配置和内部 HTTP 服务
  tests/                   单元测试和集成测试
  cli.py                   命令行入口及依赖组装
  __main__.py              `python -m harness_py` 入口
  README.md                包的简短入口文档
  ONBOARDING.md            当前这份代码地图
```

应当维持下面的依赖方向：

```text
transport/cli -> orchestration -> corpus/core
evaluation   -> orchestration/corpus/core
```

线上路径只保留两个明确的评测挂点：`LiveResearchChatHarness.run_case()` 和
`EvalRecorder`。评分、Judge、校准和线下分析都不要塞进运行时工具循环。

## 公共入口

| 入口 | 所属模块 | 用途 |
| --- | --- | --- |
| `python3 -m harness_py` | `cli.py` | 本地校验、Golden 运行、聊天、服务和 Judge 校准 |
| `LiveResearchChatHarness.run_turn()` | `orchestration/live_chat.py` | 标准的单回合 API |
| `HarnessRuntime.run_turn()` | `orchestration/runtime.py` | Agents SDK 和 Legacy 运行时共同实现的边界 |
| `ResearchHarnessService.run_job()` | `transport/service.py` | 面向 Java 的请求适配器 |
| `/v1/research/stream` | `transport/service.py` | NDJSON 进度流和最终结果 |
| `/v1/research/turn` | `transport/service.py` | 用于本地诊断的同步接口 |

默认运行时是 `agents_sdk`。`legacy` 只用于回滚，不是后续设计的主方向。

## 一个回合如何执行

1. `ResearchHarnessService` 校验 `conversation_id`、`user_message` 和 Java 已经授权的论文 ID。
2. `DockerMySqlProductCorpusStore` 为这些论文构造请求级 `GoldenDataset`。
3. 请求中的历史消息和旧证据转换为 `ConversationState`。
4. `LiveResearchChatHarness` 缩小数据集范围，生成 `run_id`，并按需打开 `EvalRecorder`。
5. Harness 将持久状态转换为 `TurnExecutionInput`，其中包含文本历史、已选论文、旧证据、进度回调、取消检查和记录器。
6. `AgentsSdkHarnessRuntime` 创建新的 `ResearchRunContext`、请求级 SDK Session、Agent、工具和模型适配器。
7. 模型自己决定研究路径。Harness 不规定固定阶段，也不限制 ReAct 回合数。
8. 工具调用只能修改当前请求里的授权状态和证据状态。
9. 模型结束时，当前模型步骤只能调用一次 `submit_research_answer`，不能混入其他工具。
10. Python 根据本轮或旧回合真正读取过的证据校验草稿。校验失败会作为工具错误返回同一个模型循环，让模型修正。
11. 通过校验的草稿被整理为 Harness Run 产物；`ConversationState.updated_from_run()` 只保留后续回合需要的会话文本和已引用证据。
12. 服务层把 Run 转换为 Java 响应，并发送最后一条 NDJSON 结果。

## 状态归属

三个状态对象各管一件事。不要把它们混在一起。

| 状态 | 生命周期 | 负责内容 |
| --- | --- | --- |
| `ConversationState` | 跨回合 | 用户/助手消息、已选论文、已引用证据、上次 Run 引用 |
| `RequestBackedSession` | 单次运行 | Agents SDK 看到的请求历史和当前输入 |
| `ResearchRunContext` | 单次运行 | 工具授权、新读取的证据、轨迹、进度、Token 和延迟统计 |

需要守住这些规则：

- `ConversationState` 由 Java 或 CLI 持久化；Agents SDK Session 不是另一套独立记忆库。
- 下一轮只回放用户和助手文本，不把完整工具轨迹重新放进提示词。
- 旧证据单独以精简证据卡传入，并为追问预授权对应的论文和位置。
- `ConversationState.updated_from_run()` 只能根据已经接受的 Run 产物推进记忆。
- 重置会话时，要一起清空历史、已选论文、已选证据和上次 Run 引用。

## 工具授权

`ReadingCorpusTools` 使用逐级公开的授权链，模型不能凭空编造内部 ID：

```text
search_paper_candidates / find_papers_by_identity
  -> 授权本次返回的 paper ID
find_reading_locations
  -> 只接受已授权的 paper ID，并公开 location ref
read_locations
  -> 只接受已公开的 location ref，并生成可引用的 evidence ID
submit_research_answer
  -> 只接受旧记忆或本轮中真实存在的 evidence ID
```

只有数据集包含引用边时，才会提供 `get_citation_edges`。它要求起始论文已经授权，并会把
相连论文加入后续导航范围。论文卡片、位置预览和图边都只是导航信息；只有
`read_locations` 会产生能够支持论文内容声明的证据。

## 最终答案契约

最终工具 Schema 定义在 `orchestration/legacy/harness.py` 的 `_final_answer_tool()` 中，两个
运行时共用。校验逻辑同样共用 `_answer_validation_error()`。

答案只有满足以下条件才会被接受：

- 最后一个模型步骤只能调用 `submit_research_answer`；
- 结构以及 status/outcome 组合合法；
- 引用的 evidence ID 存在于旧记忆或当前回合；
- 论文内容声明使用规定的 `[[evidence_id]]` 格式；
- 引用和 evidence ledger 内部一致。

如果 MiniMax 只返回普通文本，模型适配器会把它转换为内部继续工具调用。SDK 循环因此
不会提前结束，而是要求模型最终通过规定的提交工具完成回合。

## 评测数据保存

从运行时角度看，评测记录只写不读。使用 `--eval-dump DIR` 或
`EVAL_DUMP_DIR=DIR` 开启：

```text
DIR/<run_id>/events.jsonl   只追加的有序事件
DIR/<run_id>/result.json    原子写入的最终结果
```

`EvalRecorder` 为每个事件生成稳定 ID，拒绝重复 ID，分配单调递增序号，每条 JSONL 都会
立即刷盘，最终结果通过原子重命名落盘。记录内容包括模型请求/响应、工具开始/完成/错误、
答案校验以及 Run 开始/错误。Authorization Header 和 API Key 不会写入文件。

保存失败不会改变模型结果，但会增加 `eval_capture_failures`。如果 `agent-run` 显式传入
`--eval-dump` 且保存失败，CLI 以状态码 2 退出。解释、聚合、Reward 构造和研究分析都
应在线下完成，不要继续扩展线上编排路径。

## Golden 评测

Golden 路径分为四层：

| 层级 | 模块 | 是否调用模型 | 回答的问题 |
| --- | --- | --- | --- |
| Fixture 校验 | `evaluation/golden_fixture.py`、`evaluation/scoring.py` | 否 | 人工预期和评分逻辑是否自洽？ |
| Anchor 审计 | `evaluation/audit.py` | 否 | 人工页码/Anchor 是否能匹配解析后的语料？ |
| 真实执行 | `LiveResearchChatHarness.run_case()` | 是 | 真实运行时是否满足可观察的 Golden 行为？ |
| Judge 校准 | `evaluation/judge.py` | 是 | Judge 对定性标准的判断是否与固定人工标签一致？ |

Golden 预期只约束可观察的结果、检索、结构化事实和 Grounding，不约束 Skill 选择、工具
顺序、工具次数或逐字措辞。Manifest 的划分和完整运行手册见
`research/golden-data/README.md`。

## 修改入口

| 要做的事 | 先看这里 | 通常还要修改 |
| --- | --- | --- |
| 修改 Agent 指令 | `orchestration/legacy/harness.py` | 真实 Golden Case |
| 增加或修改语料工具 | `corpus/tools.py` | `orchestration/agents/tools.py`、工具测试 |
| 增加 Research Skill | `orchestration/research_skills.py` | Skill 数量和注册测试 |
| 修改最终答案校验 | `orchestration/legacy/harness.py` | Agents 工具测试、Golden 评分 |
| 修改会话记忆 | `orchestration/conversation.py` | 服务测试和追问测试 |
| 修改 MiniMax/SDK 传输 | `orchestration/agents/model.py` | 模型适配器测试 |
| 增加运行时 | `orchestration/runtime.py` | `cli.py`、服务运行时选项 |
| 修改产品库加载 | `corpus/product_db_dataset.py` | 语料摘要和服务测试 |
| 修改 Java HTTP 契约 | `transport/service.py` | Java 调用方和服务测试 |
| 增加 Golden Data | `research/golden-data/` | 确定性校验、审计、选定的真实运行 |
| 修改评分 | `evaluation/scoring.py` | Fixture 和 Scorer 测试，不要改线上编排 |

## 排查顺序

发现 Run 不对时，按下面的顺序看：

1. 确认 Java 或 CLI 传入了正确的 `scope.paper_ids`、历史消息和旧证据。
2. 先检查 `ConversationState` 和请求级数据集，不要上来就归因于模型。
3. 查看 `react_trace` 中的工具参数和模型实际看到的工具结果。
4. 查看 `evidence_ledger` 和 `citation_validation` 中的确定性拒绝原因。
5. 查看 `diagnostics.finish_reason`、模型调用次数、用量和延迟。
6. 如果开启了评测记录，按 `sequence` 检查 `events.jsonl`，再与 `result.json` 对照。
7. 先用重复的 `--case-id` 复现一条 Golden Case，再决定是否跑完整 Manifest。

常见错误边界：

- `paper_not_authorized_for_reading`：模型跳过了论文搜索或身份解析。
- `location_not_disclosed_for_reading`：模型跳过了位置搜索，或者复用了未知 Location Ref。
- 最终校验错误：草稿引用了未读取的 ID、格式错误，或把最终提交与其他工具放在同一步。
- `FAILED_TECHNICAL`：模型供应商、网络传输、SDK 或运行时异常；查看 diagnostics 和评测事件。
- 硬评分正确但文字质量可疑：运行单独校准的 Judge，不要向运行时添加临时启发式规则。

## 验证命令

稳定 Golden Data 的确定性校验和 Anchor 审计：

```bash
.venv-harness/bin/python -m harness_py validate
.venv-harness/bin/python -m harness_py audit --out /tmp/paismart-anchor-audit.json
```

编排相关的重点测试：

```bash
.venv-harness/bin/python -m unittest \
  harness_py.tests.test_agents_model \
  harness_py.tests.test_agents_tools \
  harness_py.tests.test_agents_runtime \
  harness_py.tests.test_service \
  harness_py.tests.test_eval_recorder
```

全部 Python 测试：

```bash
.venv-harness/bin/python -m unittest discover -s harness_py/tests
```

运行一条真实 Golden Case，并保存评测记录：

```bash
.venv-harness/bin/python -m harness_py agent-run \
  --case-id transformer_adam_params_001 \
  --runtime agents_sdk \
  --eval-dump /tmp/paismart-agent-eval \
  --out /tmp/paismart-agent-run
```

## 维护风格

这个包采用删除优先的 Ponytail 风格：

- 先复用已有函数，再考虑增加抽象或依赖。
- 只有完全相同的行为才集中处理。`corpus/pages.py` 是页码解析和标准化 Anchor 匹配的唯一来源；CLI 公共函数集中维护不变的默认值和产物写入。
- Agent 提示词、命令默认值、HTTP 接口路径、模型供应商 URL 处理和产物路径都属于行为。不要在可读性重构中顺手改动。
- 中文注释只解释信任边界、状态归属、数据完整性和取消逻辑，不复述显而易见的 Python。
- 即使能少写几行，也不能删除防止数据丢失的处理、授权检查、最终答案校验和评测日志持久化。
- 重复解析优先改成一个清楚的局部变量；能够删除时，不要增加新类或新接口。

## 设计约束

- 保持 `HarnessRuntime` 足够小。运行时只接收 `TurnExecutionInput`，返回标准化 Run。
- 让模型在工具循环中做选择，不要重新引入固定的 Intent/Retrieval/Claim 流水线。
- 在信任边界使用确定性校验：论文范围、工具授权、最终答案、引用和持久化。
- 评测记录只追加、不分析。新的研究字段通常应在线下派生。
- 身份认证、Redis 状态、断线重连、权限、取消责任和用量结算继续由 Java 负责。
- 保持 Legacy 运行时可用于回滚，但新的编排行为优先实现到 Agents SDK 运行时。
- 优先扩展已有数据结构和工具，不要为尚不存在的调用方增加编排抽象。
