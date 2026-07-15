# 人工裁决包

先阅读 [`START_HERE.md`](START_HERE.md)。

当前状态：

- 7 个 Golden Contract 已完成审核，结果保存在 `contract-labels-template.yaml`；
- 30 组 Answer A / B 已完成盲审，标签保存在 `answer-labels-template.yaml`；
- 标签已于 Commit `c9ce253` 冻结，之后才用 `blind-map.json` 恢复模型身份；
- 可重现的离线报告见 [`adjudication-report.md`](adjudication-report.md) 和
  [`adjudication-report.json`](adjudication-report.json)；
- 统计解读和瓶颈结论见 [`RESULTS.md`](RESULTS.md)。
- 9 个模型质量缺陷和 1 个技术处置项见
  [`DEFECT_DISPOSITION.md`](DEFECT_DISPOSITION.md)。
- MiniMax 固定为生产模型后的问题定位基线和停止边界见
  [`MINIMAX_DIAGNOSTIC_BASELINE.md`](MINIMAX_DIAGNOSTIC_BASELINE.md)。

`REVIEW_ORDER.md` 保留当时的随机审核顺序，用于审计和复现本轮标注过程。

## 重新生成离线报告

在仓库根目录执行：

```bash
python3 -m harness_py.evaluation.adjudication_report \
  --labels research/golden-data/human-adjudication/2026-07-14/answer-labels-template.yaml \
  --blind-map research/golden-data/human-adjudication/2026-07-14/blind-map.json \
  --score-report gpt-5.5=research/golden-data/validation-runs/2026-07-14-single-path-model-comparison-v1/gpt-5.5/score_report.json \
  --score-report minimax-m3=research/golden-data/validation-runs/2026-07-14-single-path-model-comparison-v1/minimax-m3/score_report.json \
  --relative-to . \
  --json-out research/golden-data/human-adjudication/2026-07-14/adjudication-report.json \
  --markdown-out research/golden-data/human-adjudication/2026-07-14/adjudication-report.md
```

该命令只读取冻结标签和已保存评分，不调用模型。原始 `hard_pass` 在报告中只表示
**合同/锚点一致性**，人工总体评分和事实依据作为独立的语义质量指标。
