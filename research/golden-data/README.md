# Harness Golden Data

一句话：这里测的是 Python Research Harness 的“论文回答质量回归”。它固定论文、问题、必须表达的
Claim 和可接受引用位置，用来判断一次 Agent Run 是否找对论文、读到证据、引用证据并给出符合证据
合同的回答。

这个目录只保留当前 Python Research Harness 会用到的评测资产：

- `manifest.yaml`：稳定集，5 篇论文，10 个检索型 Case。
- `manifest-expanded.yaml`：扩展集，14 篇论文，24 个检索型 Case。
- `paper-packs/`：论文身份、来源和人工 Anchor。
- `claims/`：可复用事实声明和可接受的产品 `location_ref`。
- `cases/`：会话输入、预期 Outcome、Required Claim、引用规则。
- `human-labels*.yaml`：固定人工标签，用于 Judge 校准。
- `validation-runs/`、`judge-calibration/`、`judge-holdout/`、`human-adjudication/`：已保存的离线复核输入。

本地输出写到 `research/golden-data/local-runs/`。该目录被 Git 忽略；需要作为 Baseline 的结果先人工审核，
再复制到受版本控制的评测目录。不要覆盖历史 Run。

Qdrant 的产品检索代码在 Java 侧；这个目录不再保留已经退出产品路径的离线 Qdrant 影响脚本、探针配置
或旧复现文档。

## 基本命令

稳定集确定性校验：

```bash
python3 -m harness_py validate
```

扩展集确定性校验：

```bash
python3 -m harness_py \
  --manifest research/golden-data/manifest-expanded.yaml \
  validate
```

Anchor 审计：

```bash
python3 -m harness_py audit \
  --out research/golden-data/local-runs/stable-anchor-audit.json

python3 -m harness_py \
  --manifest research/golden-data/manifest-expanded.yaml \
  audit \
  --out research/golden-data/local-runs/expanded-anchor-audit.json
```

Claim Location 审计，需要先从 `product-corpus-map.example.yaml` 创建本地映射：

```bash
.venv-harness/bin/python -m harness_py \
  --manifest research/golden-data/manifest-expanded.yaml \
  claim-audit \
  --product-corpus-map research/golden-data/product-corpus-map-expanded.local.yaml \
  --out research/golden-data/local-runs/claim-location-audit.json
```

真实 Agent 运行：

```bash
.venv-harness/bin/python -m harness_py agent-run \
  --product-corpus-map research/golden-data/product-corpus-map.local.yaml \
  --case-id transformer_adam_params_001 \
  --eval-dump research/golden-data/local-runs/stable-eval \
  --out research/golden-data/local-runs/stable-live
```

离线复评分，不重新调用回答模型：

```bash
.venv-harness/bin/python -m harness_py \
  --manifest research/golden-data/manifest-expanded.yaml \
  rescore \
  --runs research/golden-data/local-runs/<run> \
  --out research/golden-data/local-runs/<run>-score-report.json
```

LLM Judge 校准：

```bash
.venv-harness/bin/python -m harness_py judge-calibrate \
  --labels research/golden-data/human-labels-llm-agent-evaluation.yaml \
  --provider-source env \
  --out research/golden-data/local-runs/judge-calibration

.venv-harness/bin/python -m harness_py claim-judge-calibrate \
  --labels research/golden-data/human-labels-claim-judge-calibration.yaml \
  --provider-source env \
  --out research/golden-data/local-runs/claim-judge-calibration.json
```

## 资产重建

`llm-agent-evaluation` Pack 的构建脚本只做离线资产生成，正式内容链固定为：

```text
PDF -> MinerU -> MinerUOutputMapper -> PaperReadingModelBuilder -> Export
```

生成 staging：

```bash
python3 research/golden-data/build_llm_agent_assets.py
```

校验 staging：

```bash
python3 research/golden-data/build_llm_agent_assets.py --validate-staging
```

全部 Anchor Gate 通过后发布：

```bash
python3 research/golden-data/build_llm_agent_assets.py \
  --validate-staging \
  --publish
```

## 检查顺序

只改数据：

```text
validate -> audit -> selected agent-run -> optional judge calibration
```

改编排或评分代码：

```text
focused Python tests -> validate -> audit -> one live case -> selected expanded live cases
```

改 Java/Qdrant 产品检索：

```text
focused Python/Java tests -> validate -> audit -> product retrieval checks -> selected live cases
```

Golden 分数只说明固定合同是否满足。Candidate、Read、Cited、Outcome、Hard Pass、人工语义质量和
成本要分开报告。
