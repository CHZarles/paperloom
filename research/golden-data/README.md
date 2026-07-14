# Harness Golden Data V2

这个目录是 Python 研究 Harness 的线下评测工作区。运行时代码只负责生成完整的 Run 产物，
并在明确要求时保存原始、有序的评测事件。评分、审计、Judge 校准、结果对比和研究分析
都在这里或其他线下工具中完成。

下面的命令都应在仓库根目录 `/path/to/paperloom` 运行。

## Manifest

| Manifest | 范围 | 当前规模 | 用途 |
| --- | --- | --- | --- |
| `research/golden-data/manifest.yaml` | 原始 `transformer-bert-gpt` Pack | 5 篇论文，15 个 Case | 旧 Golden Data 的稳定回归门槛 |
| `research/golden-data/manifest-expanded.yaml` | 原始 Pack 加 `llm-agent-evaluation` | 14 篇论文，30 个 Case | 更广的研究和 Benchmark 覆盖 |

稳定 Manifest 应保持精简并向后兼容。探索性的 Pack 和 Case 先放进扩展 Manifest；数据、
Anchor 和预期行为稳定后，再考虑提升到稳定回归集。

人工编写的数据分为：

- `manifest*.yaml`：Paper Pack 和 Case 文件的索引。
- `paper-packs/*.yaml`：论文身份、引用边、解析器/模型路径和稳定证据 Anchor。
- `cases/*.yaml`：会话历史，以及可观察的结果、检索、内容和引用预期。
- `human-labels*.yaml`：针对已保存真实 Run 的固定人工判断，用于 Judge 校准。
- `validation-runs/`：供线下审核使用的已提交或本地 Baseline Run。
- `judge-calibration/`：保存 Judge 校准报告和分析。

运行阶段、Skill 选择、工具顺序、工具次数和逐字措辞都不是 Golden 预期。每个证据 Anchor
必须提供可解析的正数 `page`。审计和运行时匹配共用 `harness_py/corpus/pages.py`，可读性
重构不能只改其中一边。Case ID、预期、Paradigm 标签和人工 Anchor 质量信号都不会暴露
给模型。

本轮 Reading Model 对齐、检索恢复和错误方案复盘见
[`2026-07-13-reading-model-retrieval-practice.md`](2026-07-13-reading-model-retrieval-practice.md)。
Candidate 恢复后的下一阶段编排方案见
[`Golden Data 与 Harness 演化`](../../site/practice/evaluation/golden-data-harness-evolution.md)。
两份文档分别记录“已经验证了什么”和“如何实现下一阶段”，实际状态以方案顶部和实施结果为准。
面向工程分享的完整故事见
[`从 16/32 到 29/32，却只多过了 1 个 Case`](../../site/practice/evaluation/golden-data-harness-evolution.md)。

## 扩展语料资产

`llm-agent-evaluation` Pack 的生成资产放在
`research/golden-data/corpora/llm-agent-evaluation/`，保证研究工作区能够自包含。构建脚本只做
离线编排，正式内容链固定为：

```text
PDF -> MinerU -> MinerUOutputMapper -> PaperReadingModelBuilder -> Export
```

其中 `content_list` 生成 canonical Reading Element，`middle.json` 的 `preproc_blocks` 生成物理
`PaperPage`。跨页段落不会被拆成多个 canonical element，也不会用页码 fallback 修补。

先生成 staging 资产：

```bash
python3 research/golden-data/build_llm_agent_assets.py
```

全部论文完成后，重新校验现有 staging，不重复调用 MinerU：

```bash
python3 research/golden-data/build_llm_agent_assets.py --validate-staging
```

只有 9 篇论文的结构和新增 22 个物理页 Anchor Gate 全部通过后才发布：

```bash
python3 research/golden-data/build_llm_agent_assets.py \
  --validate-staging \
  --publish
```

重建和发布后仍必须再跑确定性校验与完整 Anchor 审计。构建脚本执行成功，不代表模型检索
和真实 Agent 行为已经有效。

## 1. 确定性 Fixture 校验

`validate` 加载 Manifest，在内存中生成确定性 Fixture Run，并根据人工预期评分。它不会
调用 MiniMax，也不会测试真实的 Agents SDK 工具循环。

稳定的旧 Golden Data：

```bash
python3 -m harness_py validate
```

扩展数据：

```bash
python3 -m harness_py \
  --manifest research/golden-data/manifest-expanded.yaml \
  validate
```

命令把评分报告打印到标准输出。只有 `failed_count` 为零时才以状态码 0 退出。当前版本的
预期摘要是：

```text
stable:   case_count=15, passed_count=15, failed_count=0
expanded: case_count=30, passed_count=30, failed_count=0
```

修改 Manifest、Pack、Case、Schema、Fixture 生成或确定性评分后，先运行这一层。

## 2. Anchor 和解析器审计

`audit` 检查每个人工证据 Anchor 是否能在指定正页码上匹配对应的 Reading Model。它不会
调用模型。

稳定的旧 Golden Data：

```bash
python3 -m harness_py audit \
  --out /tmp/paperloom-stable-anchor-audit.json
```

扩展数据：

```bash
python3 -m harness_py \
  --manifest research/golden-data/manifest-expanded.yaml \
  audit \
  --out /tmp/paperloom-expanded-anchor-audit.json
```

JSON 报告严格写入 `--out` 指定的位置。当前版本的预期摘要是：

```text
stable:   anchor_count=7, passed_count=7, failed_count=0
expanded: anchor_count=29, passed_count=29, failed_count=0
```

修改 Anchor、页码、解析器输出、语料资产路径或重新生成 Reading Model 后，应运行这一层。
Fixture 校验通过不代表 Anchor 审计也会通过。

### 保存查询重放

使用上一次真实 MiniMax Run 保存的 `find_reading_locations` 参数，离线重放当前检索器：

```bash
.venv-harness/bin/python research/golden-data/replay_saved_queries.py \
  --out /tmp/paperloom-expanded-saved-query-replay.json
```

它不调用模型，只统计 15 个新增 Case 的 Required Anchor 是否进入候选。当前结果为
`29/32`，高于发布 Gate `21/32`。这一层用于区分“模型没有继续读”与“检索器根本没有返回”。

### Evidence Funnel 报告

对一次已经保存的 `agent-run` 结果，离线拆分 Candidate、Read、Cited、Outcome 和 Technical
Failure：

```bash
.venv-harness/bin/python research/golden-data/analyze_evidence_funnel.py \
  --runs /tmp/paperloom-expanded-live \
  --eval-dump /tmp/paperloom-expanded-eval \
  --out /tmp/paperloom-evidence-funnel.json \
  --markdown-out /tmp/paperloom-evidence-funnel.md
```

脚本使用保存的 `find_reading_locations` 参数对当前冻结语料离线重放 Candidate，再根据 Run 中
真实保存的 evidence span 和 citation 统计 Read / Cited。它不调用模型，不修改 Runtime，也不把
Anchor 暴露给线上工具。`--case-id` 可以重复使用，只分析少量焦点 Case。

## 3. 真实 Agents SDK 执行

`agent-run` 通过 MiniMax 和默认 OpenAI Agents SDK 运行真实的
`LiveResearchChatHarness`。Golden 命令中，只有它会同时测试模型编排、工具调用、最终
答案校验和真实证据 Grounding。

先安装固定版本的环境：

```bash
python3 -m venv .venv-harness
.venv-harness/bin/python -m pip install -r harness_py/requirements.lock
```

运行一个稳定 Case：

```bash
.venv-harness/bin/python -m harness_py agent-run \
  --case-id transformer_adam_params_001 \
  --out /tmp/paperloom-stable-live
```

重复使用 `--case-id`，可以运行多个扩展 Case：

```bash
.venv-harness/bin/python -m harness_py \
  --manifest research/golden-data/manifest-expanded.yaml \
  agent-run \
  --case-id agentbench_environment_inventory_001 \
  --case-id gaia_dataset_gap_001 \
  --eval-dump /tmp/paperloom-expanded-eval \
  --out /tmp/paperloom-expanded-live
```

省略 `--case-id` 会运行所选 Manifest 中的全部 Case。未知 Case ID 以状态码 2 退出。
`validate` 和 `audit` 始终处理完整 Manifest；只有真实 `agent-run` 支持按 Case 选择。

模型配置默认使用 `--provider-source db`。需要从环境变量读取
`MINIMAX_API_BASE_URL`、`MINIMAX_API_KEY` 和 `MINIMAX_MODEL` 时，传入
`--provider-source env`。

真实运行的硬评分失败与 Fixture 或 Anchor 失败不是一回事。模型行为会波动，建议先运行
一两个有代表性的 Case，检查保存的 Run，再决定是否为完整 Manifest 消耗 Token。

## 4. 可选的 LLM Judge 校准

确定性 Scorer 处理可观察结构和 Grounding。自然语言答案质量和语义标准由单独校准的
LLM Judge 根据固定人工标签评估。普通 Harness 开发不必每次运行 Judge 校准，更不能把
它接入线上答案路径。

校准集：

```bash
.venv-harness/bin/python -m harness_py judge-calibrate \
  --labels research/golden-data/human-labels-llm-agent-evaluation.yaml \
  --provider-source env \
  --out /tmp/paperloom-judge-calibration
```

冻结 Judge 提示词和决策规则后，再运行 Holdout：

```bash
.venv-harness/bin/python -m harness_py judge-calibrate \
  --labels research/golden-data/human-labels-llm-agent-evaluation-holdout.yaml \
  --provider-source env \
  --out /tmp/paperloom-judge-holdout
```

每个 Label 文件都指向已保存的答案和证据产物。覆盖这些 Baseline Run 之前，必须重新审核
人工标签。命令写入 `agreement_report.json`；Judge 出现技术错误时以状态码 2 退出，未达到
配置的一致率门槛时以状态码 1 退出。

## 产物

`agent-run --out OUT` 写入可供线下处理的 Case 产物：

```text
OUT/score_report.json
OUT/<case_id>/harness_run.json
OUT/<case_id>/research_answer.json
OUT/<case_id>/evidence_ledger.json
OUT/<case_id>/citation_validation.json
OUT/<case_id>/skills_used.json
OUT/<case_id>/react_trace.json
OUT/<case_id>/paper_candidates.json
```

`--eval-dump EVAL_DIR` 额外写入原始运行日志：

```text
EVAL_DIR/<run_id>/events.jsonl
EVAL_DIR/<run_id>/result.json
```

JSONL 按 `sequence` 排序，并通过 `event_id` 去重；`result.json` 使用原子写入。线上 Harness
不会读取这些文件。工具统计、供应商对比、Reward 数据、轨迹摘要和新的研究字段都应在线
下派生，不要继续扩展运行时 Recorder。

## 推荐检查顺序

只修改数据时：

```text
validate -> audit -> selected live cases -> optional judge calibration
```

修改编排代码时：

```text
focused Python tests -> stable validate -> stable audit -> one stable live case
-> selected expanded live cases -> optional judge calibration
```

某一层通过不等于完整 Golden 测试通过。报告结果时，应分别说明 Fixture 校验、Anchor
审计、真实硬评分和 Judge 校准结果。
