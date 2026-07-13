# Python 研究 Harness

这个包负责 PaiSmart 的论文研究回合。默认运行时基于 OpenAI Agents SDK 和 MiniMax，使用
请求级会话记忆、受控的语料工具，以及确定性的最终答案和引用校验。手写的 `legacy`
运行时只作为回滚方案保留。

代码尽量使用小型标准库函数和显式状态，不额外堆框架。重构时，提示词、命令默认值、
接口路径和产物路径都视为行为契约，不随可读性调整一起修改。

先阅读 [ONBOARDING.md](ONBOARDING.md)。其中介绍了整体架构、单回合执行流程、状态归属、
工具授权、评测数据保存、扩展位置和排查方法。

Golden Data 的结构和测试命令见
[`research/golden-data/README.md`](../research/golden-data/README.md)。

## 目录结构

```text
core/             公共模型、契约、状态和错误
corpus/           语料加载，以及生成证据的阅读工具
orchestration/    会话状态和与运行时无关的回合边界
  agents/         默认的 OpenAI Agents SDK 运行时
  legacy/         手写工具循环和 MiniMax 直连客户端，仅用于回滚
evaluation/       Golden fixture、审计、评分、judge 和评测数据记录
transport/        模型供应商配置和供 Java 调用的 HTTP 服务
tests/            Python 单元测试和集成测试
cli.py            命令行入口及依赖组装
```

## 快速开始

在仓库根目录运行：

```bash
python3 -m venv .venv-harness
.venv-harness/bin/python -m pip install -r harness_py/requirements.lock

.venv-harness/bin/python -m harness_py validate
.venv-harness/bin/python -m harness_py audit --out /tmp/paismart-anchor-audit.json
.venv-harness/bin/python -m unittest discover -s harness_py/tests
```

运行一条真实的 Golden Case：

```bash
.venv-harness/bin/python -m harness_py agent-run \
  --case-id transformer_adam_params_001 \
  --runtime agents_sdk \
  --eval-dump /tmp/paismart-agent-eval \
  --out /tmp/paismart-agent-run
```

启动供 Java 调用的内部服务：

```bash
export MINIMAX_API_BASE_URL=https://api.minimaxi.com/v1
export MINIMAX_API_KEY=...
export MINIMAX_MODEL=MiniMax-M3

.venv-harness/bin/python -m harness_py serve \
  --runtime agents_sdk \
  --host 127.0.0.1 \
  --port 8091
```

Java 调用 `/v1/research/stream`，接收 NDJSON 进度事件和一个最终结果。身份认证、Redis
状态、断线重连、权限和用量结算不由 Python Harness 负责。

## 评测数据保存

设置 `EVAL_DUMP_DIR`，或者传入 `--eval-dump`。每次执行只写入：

```text
<eval-dir>/<run_id>/events.jsonl
<eval-dir>/<run_id>/result.json
```

运行时不会读取或分析这些文件。所有评测研究都在线下完成。
