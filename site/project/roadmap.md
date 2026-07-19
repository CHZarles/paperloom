---
title: 边界与路线
description: PaperLoom 当前正在解决什么，以及哪些能力还没有完成。
---

# 边界与路线

路线只收录能指向具体失败、实验数据或产品缺口的工作。

## 当前重点

### Evidence Selection

MiniMax 实际运行中有 `47/48` 份所需证据进入 Qdrant Candidate，只读取 `29/48`。接下来会改进子问题
覆盖、多位置阅读和 claim-to-evidence 校验，同时继续记录候选顺序与 Preview 对选择行为的影响。现有
Best-of-2 数据不支持继续增加采样次数。

### 研究预算与上下文

并行采样降低了部分墙钟延迟，同时显著增加总 Token，候选池上限也没有提高。预算和采样数需要跟随
问题复杂度，固定 Best-of-N 暂时不会进入默认路径。

当前单路径 Runner 会在每次模型调用时重放本轮已经产生的工具结果和被拒答案。长研究回合需要压缩
旧结果，同时保留准确 Evidence、权限状态和可恢复的完整记录。相关方案仍处于设计阶段，当前 Harness
没有启用上下文压缩。

### Reading Model Coverage

继续提高图表、公式、跨页结构和视觉资产的闭环能力，同时保持 Parser Artifact 与产品 Reading
Model 的边界。

### Qdrant 排序与高可用

Live Harness 已经切到 Java/Qdrant 的 Sparse BM25 单路候选。冻结查询回放达到 `48/48` 个指定
Evidence Obligation、`24/24` 个完整 Case，MRR 为 `0.48019`；端到端 MiniMax 仍暴露模型改写查询、
读取和引用选择问题。下一步优先分析真实 Candidate / Read / Cited 缺口与大 Scope 性能，不重新引入
Dense、RRF 或请求级回退。

Snapshot / Restore、Replica、Rolling Restart、多副本并发和数千篇论文容量仍未通过生产验证。产品
不会为 Qdrant 增加静默 BM25 回退。

### Product Verification

让上传、解析、选择论文、研究、历史会话与引用重开形成稳定的浏览器级回归链路。

个人空间与管理员全局发布已经进入代码。对外开放前还需要由服务端核对合并 PDF 的内容哈希，避免只凭
客户端提交的 `paper_id` 建立个人拥有关系。

## 不采用的捷径

- 不用静态关键词模拟通用语义路由。
- 不用测试集特有的第一页或摘要特权掩盖 Parser 数据问题。
- 不在检索失败后生成一个看似正常的无证据答案。
- 不把评估语料混进产品论文库提高表面召回。
- 不把增加模型调用次数当作默认质量策略。
- 不因为仓库里已经存在 Index 或 Embedding，就把它写成当前 Assistant 能力。

## 进入产品前的检查

1. 明确用户问题和产品边界。
2. 保存当前行为和失败证据。
3. 用独立实验验证候选生成、编排和最终选择。
4. 同时报告质量、延迟、Token 与技术失败。
5. 通过产品行为回归后再进入默认路径。
