# 人工裁决包

先阅读 [`START_HERE.md`](START_HERE.md)。

当前状态：

- 7 个 Golden Contract 已完成审核，结果保存在 `contract-labels-template.yaml`；
- 30 组 Answer A / B 已重新随机分配模型身份；
- 审核顺序已重新打乱，以 [`REVIEW_ORDER.md`](REVIEW_ORDER.md) 为准。

完成 `answer-labels-template.yaml` 之前，不要打开 `blind-map.json`。全部标签冻结后，
再用该文件恢复 Answer A / B 的模型身份并计算人工通过率。
