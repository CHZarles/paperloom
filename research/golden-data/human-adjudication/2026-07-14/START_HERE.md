# 当前状态

7 个 Golden Contract 和 30 组 Answer A / B 已经全部审核完成。标签在揭盲前已冻结，
结果见 [`RESULTS.md`](RESULTS.md)。不需要再修改标签，也不需要改 Golden Data YAML。

下面保留本轮盲审所使用的口径，供审计和复现。

## 审核顺序

按 [`REVIEW_ORDER.md`](REVIEW_ORDER.md) 中的随机顺序逐个审核。Answer A / B 的模型身份已重新随机分配。
全部标签冻结前，不要打开 `blind-map.json`。

## 怎么审核一组答案

打开对应的 `answer-review/*.md`，只根据用户问题、Answer A / B 和文件中附带的证据判断：

- `decision`：模型选择直接回答、澄清、部分回答或拒答是否合理；
- `task_fulfillment`：是否完成用户实际要求；
- `grounding`：论文性主张是否被所引证据支持，没有论文性主张时可填 `not_applicable`；
- `overall`：该答案整体是否应该通过；
- `preferred`：填 `A`、`B` 或 `tie`；
- `note`：只记录影响判断的具体原因。

不要因为没有命中 Golden 中某个精确 Anchor ID 就判失败。同一论文中语义等价、足以支持主张的证据应当接受。

把结果写入 `answer-labels-template.yaml`。也可以在聊天中告诉 Codex 简化结果，例如
"A 通过，B 失败，更喜欢 A，因为 B 漏了……"，由 Codex 代填完整字段。

## 冻结规则

先冻结 `answer-labels-template.yaml`，再打开 `blind-map.json`。随后按模型恢复身份，计算人工通过率、
A/B 偏好和评分器假阴性，用于区分评估机制问题、检索问题和模型推理问题。
