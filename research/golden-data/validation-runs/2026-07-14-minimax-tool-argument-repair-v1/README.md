# MiniMax 工具参数恢复定向复跑

本目录保存 `mint_vs_tau_interaction_comparison_001` 在通用畸形工具参数恢复实现后的单 Case
真实运行，不替换冻结的模型对比基线。

## 原始故障

冻结基线中的 MiniMax 响应在输出长度上限处截断了 `submit_research_answer` 参数。SDK 下一轮重放
这条非法 JSON tool call 后，Provider 返回 HTTP 400：`invalid function arguments json string`。

## 本次结果

- Runtime 状态：`COMPLETED`；
- Eval Capture：完整，`capture_ok=true`，没有重复事件；
- 合同/锚点一致性：`0/1`；
- 失败原因：回答只覆盖 interaction design，没有覆盖其余比较轴和 Required Evidence；
- 本次真实响应没有再次产生畸形 JSON，因此恢复分支由确定性测试直接证明。

本次定向复跑的意义是确认生产运行时发生实质变更后，受影响 Case 不再以原来的 HTTP 400
技术故障结束。它不证明 MiniMax 已修复该 Case 的语义质量，也不支持全量重跑。

详细处置见
[`DEFECT_DISPOSITION.md`](../../human-adjudication/2026-07-14/DEFECT_DISPOSITION.md)。
