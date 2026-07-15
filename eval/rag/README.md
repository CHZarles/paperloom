# 旧 RAG 评测资料归档

本目录保留 2026 年 6 月期间的 RAG 方法实验、离线数据转换器、分数账本和历史运行结果，主要用于
追溯工程决策。这里曾经记录的 Elasticsearch、`PaperRetrievalService`、Service-backed Page Window
以及旧 Java Benchmark Runner 已经退出当前产品路径，相关命令不再作为可运行指南。

当前 Harness 的 Golden Data、MiniMax 运行方式、人工标注和结果分析统一以
[`research/golden-data/README.md`](../../research/golden-data/README.md) 为准。不要根据本目录的历史
命令恢复旧检索链路，也不要把旧结果当作当前 Qdrant Corpus Adapter 的回归结论。

仍可查阅的历史材料：

- [`PROJECT_CONTEXT.md`](PROJECT_CONTEXT.md)：当时的产品和用户背景；
- [`RAG_METHOD_EXPLORATION.md`](RAG_METHOD_EXPLORATION.md)：方法探索记录；
- [`RETRIEVAL_STRATEGY_DECISION.md`](RETRIEVAL_STRATEGY_DECISION.md)：旧检索策略决策；
- [`STRATEGY_READINESS.md`](STRATEGY_READINESS.md)：旧策略完成度审计；
- [`CHEATSHEET.md`](CHEATSHEET.md)：历史运行分数快照。

`harnesses.yaml` 只保留仍有实现支撑的离线工具或数据定义，不再登记已经删除的 Service-backed
Harness。历史数据文件和运行结果可以继续保留，但不得作为当前生产 Harness 的兼容层。
