# Harness Golden Data V3

这个目录是 Python 研究 Harness 的线下评测工作区。运行时代码只负责生成完整的 Run 产物，
并在明确要求时保存原始、有序的评测事件。评分、审计、Judge 校准、结果对比和研究分析
都在这里或其他线下工具中完成。

下面的命令都应在仓库根目录 `/path/to/paperloom` 运行。

`research/golden-data/` 是唯一 Golden Data 管理根目录。两个 Paper Pack 的 PDF、Reading Model、
文本和解析资产都放在 `corpora/<pack-id>/`；Schema、Manifest、Case、评测脚本、人工标签和 Run
产物也在本目录管理。真实 Golden Runner 只经过 Java/Qdrant 产品链路，不保留内存检索兼容入口
或静默回退。

本地运行产物统一写到持久目录 `research/golden-data/local-runs/`。该目录已被 Git 忽略，但不会像
`/tmp` 一样在重启或系统清理时丢失；需要提交为 Baseline 的结果应经过人工审核后再复制到既有的
`validation-runs/`，不要覆盖历史 Run。

## Manifest

| Manifest | 范围 | 当前规模 | 用途 |
| --- | --- | --- | --- |
| `research/golden-data/manifest.yaml` | 原始 `transformer-bert-gpt` Pack | 5 篇论文，10 个检索 Case | 旧 Golden Data 的稳定回归门槛 |
| `research/golden-data/manifest-expanded.yaml` | 原始 Pack 加 `llm-agent-evaluation` | 14 篇论文，24 个检索 Case | 更广的研究和 Benchmark 覆盖 |

两个活动 Manifest 只保留必须读取论文证据的 Case，因此每个问题都会实际经过检索路径。语料清单、
模糊短语澄清和超出语料范围的问题不经过检索，不再占用 Golden/Qdrant 回归预算；对应的底层合同
继续由单元测试覆盖。历史 Run、人工复核和 Judge 校准产物保持冻结，其中仍可能出现已经退出活动
Manifest 的旧 Case。

两个 Manifest 使用不同的 `dataset_id`。扩展数据的 Run、Human Label 和报告不能冒充稳定数据，
也不能覆盖稳定数据的回归结论。

稳定 Manifest 应保持精简；原有 Pack、Case、路径和预期不能因为扩展数据而改变。探索性的 Pack
和 Case 先放进扩展 Manifest；数据、Anchor 和预期行为稳定后，再考虑提升到稳定回归集。

人工编写的数据分为：

- `manifest*.yaml`：Paper Pack 和 Case 文件的索引。
- `paper-packs/*.yaml`：论文身份、引用边、解析器/模型路径和审计用 Anchor。
- `claims/*.yaml`：可复用的事实陈述，以及每篇必需论文可接受的产品 `location_ref`。
- `cases/*.yaml`：会话历史、结果、Typed Fact、引用策略和 `required_claims`。
- `human-labels*.yaml`：针对已保存真实 Run 的固定人工判断，用于 Judge 校准。
- `validation-runs/`：供线下审核使用的已提交或本地 Baseline Run。
- `judge-calibration/`：保存 Judge 校准报告和分析。

运行阶段、Skill 选择、工具顺序、工具次数和逐字措辞都不是 Golden 预期。Answer Scorer 只按
Golden Claim、产品位置、同一 Markdown Block 的引用和 Typed Fact 判分。Anchor 仅用于离线解析器/
索引完整性审计，不能改变答案分数。Case ID、预期和 Paradigm 标签都不会暴露给模型。

本轮 Reading Model 对齐、检索恢复和错误方案复盘见
[`2026-07-13-reading-model-retrieval-practice.md`](2026-07-13-reading-model-retrieval-practice.md)。
Candidate 恢复后的下一阶段编排方案见
[`Golden Data 与 Harness 演化`](../../site/practice/evaluation/golden-data-harness-evolution.md)。
两份文档分别记录“已经验证了什么”和“如何实现下一阶段”，实际状态以方案顶部和实施结果为准。
面向工程分享的完整故事见
[`从 16/32 到 29/32，却只多过了 1 个 Case`](../../site/practice/evaluation/golden-data-harness-evolution.md)。
Qdrant 切换后的量化结论见
[`Qdrant 检索影响量化报告`](../../docs/evaluation/qdrant-retrieval-impact-2026-07-15.md)，工程实践记录见
[`Java/Qdrant 让 76 篇论文范围的广查询快了 4.5 倍，指定证据命中却从 44/48 降到 34/48`](../../site/practice/evaluation/qdrant-retrieval-impact-benchmark.md)。

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

命令把评分报告打印到标准输出。只有 `failed_count` 和 `review_required_count` 都为零时才以状态码
0 退出。当前版本的
预期摘要是：

```text
stable:   case_count=10, passed_count=10, failed_count=0
expanded: case_count=24, passed_count=24, failed_count=0
```

评分报告使用 `harness-score-report/v4`，并保存 `behavior-scorer/v4` 的完整 Scorer Contract 与
SHA-256。Fact Scorer 从用户可见 Markdown 读取 Typed Fact Assertion；只有声明
`fields_schema=golden-facts/v1` 的确定性 Fixture 才按结构化 `fields` 评分。普通模型输出中的任意
`fields` 不会激活隐藏 Fact Contract。不支持的 Fact 类型为 `review_required`，不能发布为最终结果。

修改 Manifest、Pack、Case、Schema、Fixture 生成或确定性评分后，先运行这一层。

已经保存的 Run 可以离线复评分，不调用模型：

```bash
.venv-harness/bin/python -m harness_py \
  --manifest research/golden-data/manifest-expanded.yaml \
  rescore \
  --runs research/golden-data/local-runs/<run>/minimax-expanded-final \
  --out research/golden-data/local-runs/<run>/score-report-v4.json
```

`rescore` 要求 Manifest 中每个 Case 都存在对应的 `<case_id>/harness_run.json`。报告有确定性失败时
仍会完整写入，并以状态码 1 退出；存在 `review_required` 时同样返回 1。它拒绝覆盖已有报告。
语义复评分还必须同时提供 `--semantic-judgments`、`--calibration-report` 和 `--holdout-report`。

已保存 Run 的离线语义判断通过 `judge-saved-runs --runs RUNS --out NEW.json` 生成；该命令不会
重新调用回答模型。Judge 结果只有在校准与 Holdout 都通过、且模型和 Prompt 完全一致时才能影响
最终 v4 分数。

The dedicated v4 claim judge is checked against the frozen claim/block labels before it can score a
saved run:

```bash
.venv-harness/bin/python -m harness_py \
  --manifest research/golden-data/manifest-expanded.yaml \
  claim-judge-calibrate \
  --provider-source env \
  --labels research/golden-data/human-labels-claim-judge-calibration.yaml \
  --out research/golden-data/local-runs/claim-judge-calibration-<timestamp>.json
```

Run the holdout label file only after calibration is accepted. Both outputs are immutable gate inputs;
the CLI refuses to overwrite them.

## 2. Claim Location 与 Anchor 审计

`claim-audit` 通过 Java 产品 Corpus API 读取 Claim Catalog 中每个 accepted location，并检查论文、
位置类型和 canonical text。它不调用模型，也不会自动修改 Catalog：

```bash
.venv-harness/bin/python -m harness_py \
  --manifest research/golden-data/manifest-expanded.yaml \
  claim-audit \
  --product-corpus-map research/golden-data/product-corpus-map-expanded.local.yaml \
  --out research/golden-data/local-runs/claim-location-audit-<timestamp>.json
```

`audit` 检查每个人工证据 Anchor 是否能在指定正页码上匹配对应的 Reading Model。它不会
调用模型。它只是检索/解析诊断，不能改变 `case_status`。

稳定的旧 Golden Data：

```bash
python3 -m harness_py audit \
  --out research/golden-data/local-runs/stable-anchor-audit.json
```

扩展数据：

```bash
python3 -m harness_py \
  --manifest research/golden-data/manifest-expanded.yaml \
  audit \
  --out research/golden-data/local-runs/expanded-anchor-audit.json
```

JSON 报告严格写入 `--out` 指定的位置。当前版本的预期摘要是：

```text
stable:   anchor_count=7, passed_count=7, failed_count=0
expanded: anchor_count=29, passed_count=29, failed_count=0
```

修改 Anchor、页码、解析器输出、语料资产路径或重新生成 Reading Model 后，应运行这一层。
Fixture 校验通过不代表 Anchor 审计也会通过。

## 3. Qdrant 影响量化

当前产品请求通过 Python `serve` 入口调用 Java Corpus API，由 Java 使用 Qdrant 找候选，再从 MySQL
读取 Canonical Reading Model 内容。Golden `agent-run` 只使用这条产品链路；内存 BM25 仅可作为
离线算法报告中的历史比较项，不是 Runner Backend，也不能在产品链路失败时接管请求。

量化测试分为两层：

- 离线算法层：保留切换前 BM25、旧 Sparse、Dense 和 Hybrid 结果作为历史对照；
- 产品探针层：经过 Python Gateway、Java Corpus API、Sparse-only Qdrant、Current Model / Index
  Contract 校验和 MySQL Hydration，并可选运行 MiniMax Smoke。

2026-07-18 的当前产品切换结果位于
`local-runs/lexical-qdrant-cutover-20260718-173750/`：Java/Qdrant 词法路径在 69 条冻结查询上达到
`48/48` 指定证据、`24/24` 完整 Case 和 `0.48019` MRR；内存 BM25 对照为 `35/48`、`15/24`、
`0.37838`。稳定与扩展 MiniMax-M3 全量运行均无技术失败，严格 Hard Pass 分别为 `6/10` 和 `9/24`。
Candidate 指标与模型最终分数必须分开解释。

上面的 `6/10` 与 `9/24` 是历史 `harness-score-report/v2`。修复“任意非空 fields 激活隐藏 Fact
Contract”的缺陷后，同一保存产物用 `behavior-scorer/v3` 离线复评分为 Stable `7/10`、Expanded
`10/24`，没有重新调用 MiniMax。旧 MiniMax 在当前 24 个 Case 上用同一 v3 Contract 为 `11/24`。
产物位于 `local-runs/scorer-v3-rescore-20260719/`。

离线层同时报告两种口径。Native 保留迁移前后各方法实际给模型的输出：BM25 Adapter 在 `24/69`
条广查询或多论文查询中会扩展到最多 12 个候选，产品式 Qdrant 严格停在请求的 `top_k`。固定
`Recall@1/5/8/12` 和 `offline-budget-analysis-v5.json` 则用于等预算比较。产品探针始终使用
`strict_requested_top_k`。离线层为计算这些切面会查询到 evaluation depth，因此它记录的服务耗时
不是产品原生 `top_k` 请求延迟；产品延迟只看 `product-probe-v3`。

离线预检不访问 Embedding 或 Qdrant：

```bash
.venv-harness/bin/python research/golden-data/qdrant_impact_benchmark.py --preflight
```

全量算法测试必须使用独立 Qdrant 实例，结果写入持久目录。以下命令假定隔离容器
`paperloom-qdrant-benchmark` 已在 `6335` 端口运行：

```bash
run_stamp=$(date +%Y%m%d-%H%M%S)
offline_out="research/golden-data/local-runs/2026-07-15-qdrant-impact/offline-algorithm-${run_stamp}"

QDRANT_BASE_URL=http://127.0.0.1:6335 \
PAPERLOOM_QDRANT_CONTAINER=paperloom-qdrant-benchmark \
.venv-harness/bin/python research/golden-data/qdrant_impact_benchmark.py \
  --mysql-container pai_smart_mysql \
  --out "$offline_out" \
  --cleanup
```

人工等价证据结论不是手填汇总数；验证工具会重新读取冻结的 `report.json` 和
`query_results.jsonl`，检查每个决定引用的候选确实存在，再计算总数：

```bash
run_stamp=$(date +%Y%m%d-%H%M%S)
.venv-harness/bin/python research/golden-data/qdrant_impact_adjudication.py \
  --out "research/golden-data/local-runs/2026-07-15-qdrant-impact/adjudication-v5-check-${run_stamp}.json"
```

该命令验证复核 YAML 固定引用的权威 `offline-algorithm-v5`，包括报告、查询和数据集 Hash、精确命中
重算、人工决定与具体排名的绑定及 Miss 覆盖。它不会自动判断语义等价，也不会自动复核刚生成的新
Run。独立重复运行保存在 `offline-algorithm-v4`；v4/v5 的产品式 Headline 都是 `34/48`、完整
Case 都是 `15/24`，但 `36/69` 条 Native 排名发生变化，MRR 分别为 `0.17005` 和 `0.16997`。
比较排序算法时不能把单次完整排名当成确定性输出。v5 的等请求预算重算保存在
`offline-budget-analysis-v5.json`。

产品探针要求本地 Java、Harness、MySQL 和产品 Qdrant 已经运行：

```bash
run_stamp=$(date +%Y%m%d-%H%M%S)
product_out="research/golden-data/local-runs/2026-07-15-qdrant-impact/product-probe-${run_stamp}"

.venv-harness/bin/python research/golden-data/qdrant_product_probe.py \
  --out "$product_out" \
  --run-model-smoke
```

以下是 2026-07-16 切换前 Hybrid 路径的历史固定结果：

| 方法（实际输出语义） | 精确 Anchor | 完整 Case | 人工复核后等价证据 |
| --- | ---: | ---: | ---: |
| 内存 BM25，含既有候选扩展 | `44/48` | `20/24` | `48/48` |
| 产品式 Qdrant | `34/48` | `15/24` | `47/48` |

等请求预算下，BM25 为 `42/48`、`20/24`、MRR `0.35923`，产品式 Qdrant 为 `34/48`、`15/24`、
MRR `0.16997`。因此实际行为差距中有 2 个命中来自 BM25 的候选扩展，但算法排序退化仍然存在。

这说明 Qdrant 避免了每个 Python Worker 重复加载全文，并改善了 76 篇论文广查询的延迟，但当前
Sparse、Dense 和等权 RRF 没有超过 BM25 排序。v5 构建 789 个 Point 共耗时 `61.70 s`，其中
Embedding `56.43 s`、Collection 准备 `3.54 s`、Upsert `1.66 s`；记录 370 个外部逻辑请求
（Embedding 148、Qdrant 222）和 67 次主动限速等待，共 `34.08 s`。

`product-probe-v3` 在 76 篇范围的三组查询中，广查询 p50 为 BM25 `1.838-2.139 s`、Qdrant
`0.378-0.493 s`；窄查询 p50 为 BM25 `19-40 ms`、Qdrant `345-456 ms`。本地快照中 Qdrant
RSS 为 `559.1 MiB`，Elasticsearch 为 `1.56 GiB`；两者索引粒度和职责不同，资源数不能解释为
质量对比。

MiniMax Smoke 已验证两条链路都使用 MiniMax-M3、相同 API Style 和
`max_completion_tokens=3000`。严格 Hard Pass 是 BM25 `1/3`、Qdrant `0/3`。每种方法每个 Case
只有一个 Sample，这一层只做接线和失败定位，不能估计稳定模型质量，也不能据此判断用户体验。

## 4. 真实 Agents SDK 执行

`agent-run` 通过 MiniMax 和默认 OpenAI Agents SDK 运行真实的
`LiveResearchChatHarness`。Golden 命令中，只有它会同时测试模型编排、工具调用、最终
答案校验和真实证据 Grounding。它固定经过 Python Gateway、Java Corpus API、Qdrant、Current
Model / Index Contract 校验和 MySQL Hydration，不提供其他 Backend 或内存静默回退。

先安装固定版本的环境：

```bash
python3 -m venv .venv-harness
.venv-harness/bin/python -m pip install -r harness_py/requirements.lock
```

运行 Case 前，先从
[`product-corpus-map.example.yaml`](product-corpus-map.example.yaml) 创建被 Git 忽略的
`product-corpus-map.local.yaml`，填入当前产品库的论文 ID：

```bash
.venv-harness/bin/python -m harness_py agent-run \
  --product-corpus-map research/golden-data/product-corpus-map.local.yaml \
  --case-id transformer_adam_params_001 \
  --eval-dump research/golden-data/local-runs/stable-qdrant-eval \
  --out research/golden-data/local-runs/stable-qdrant
```

映射必须覆盖所选 Case 的完整 Paper Pack。缺失、重复或错误 `dataset_id` 会在加载 Provider 和调用
模型之前失败。

重复使用 `--case-id`，可以运行多个扩展 Case：

```bash
.venv-harness/bin/python -m harness_py \
  --manifest research/golden-data/manifest-expanded.yaml \
  agent-run \
  --case-id agentbench_environment_inventory_001 \
  --case-id gaia_dataset_gap_001 \
  --eval-dump research/golden-data/local-runs/expanded-eval \
  --out research/golden-data/local-runs/expanded-live
```

省略 `--case-id` 会运行所选 Manifest 中的全部 Case。未知 Case ID 以状态码 2 退出。
`validate` 和 `audit` 始终处理完整 Manifest；只有真实 `agent-run` 支持按 Case 选择。

模型配置默认使用 `--provider-source db`。需要从环境变量读取
`MINIMAX_API_BASE_URL`、`MINIMAX_API_KEY` 和 `MINIMAX_MODEL` 时，传入
`--provider-source env`。

真实运行的硬评分失败与 Fixture 或 Anchor 失败不是一回事。模型行为会波动，建议先运行
一两个有代表性的 Case，检查保存的 Run，再决定是否为完整 Manifest 消耗 Token。

## 5. 可选的 LLM Judge 校准

确定性 Scorer 处理可观察结构和 Grounding。自然语言答案质量和语义标准由单独校准的
LLM Judge 根据固定人工标签评估。普通 Harness 开发不必每次运行 Judge 校准，更不能把
它接入线上答案路径。

校准集：

```bash
.venv-harness/bin/python -m harness_py judge-calibrate \
  --labels research/golden-data/human-labels-llm-agent-evaluation.yaml \
  --provider-source env \
  --out research/golden-data/local-runs/judge-calibration
```

冻结 Judge 提示词和决策规则后，再运行 Holdout：

```bash
.venv-harness/bin/python -m harness_py judge-calibrate \
  --labels research/golden-data/human-labels-llm-agent-evaluation-holdout.yaml \
  --provider-source env \
  --out research/golden-data/local-runs/judge-holdout
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

修改产品检索、Qdrant 索引或 Java Corpus Gateway 时：

```text
focused Python/Java tests -> stable/expanded validate and audit
-> Qdrant offline preflight -> isolated Qdrant algorithm benchmark
-> adjudication verification -> product probe -> selected MiniMax product smoke
```

某一层通过不等于完整 Golden 测试通过。报告结果时，应分别说明 Fixture 校验、Anchor
审计、内存 BM25 对照、Java/Qdrant 产品探针、真实硬评分和 Judge 校准结果。
