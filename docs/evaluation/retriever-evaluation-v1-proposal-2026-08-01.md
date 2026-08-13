# Retriever 评测 v1：先把当前产品测出来

日期：2026-08-01

状态：Superseded as quality baseline

> 本文保留当前 PAGE/SECTION Location 检索评测的实现记录。它只适合作为 Corpus API/Qdrant/MySQL
> 链路 Smoke 和现状诊断，不再作为检索质量建设方向。新的产品检索单位见
> [Passage 检索层设计 Proposal](../engineering-evolution/architecture/passage-retrieval-proposal-2026-08-01.md)；该 Proposal 本次不
> 修改 Golden Data 或定义新的质量评测，Passage 评测另开后续工作。

## 目标

给当前产品 Retriever 加一套独立评测。

它只回答一个问题：

> 已知正确论文时，Java + Qdrant + MySQL 返回的前 K 个 Location，是否包含 Golden 标记的正确位置？

这套评测和 Agent、回答模型、最终 Markdown 分开。以后 Chunk 方案改变，仍然跑同一种评测；届时更新对应
的正确 Location 标注即可。

## 本次开发范围

只做稳定集 10 个 Claim，不扩展到全部 41 个 Claim。

每个 Claim 已经有：

- 一个固定检索 Query。
- 正确论文。
- 一个或多个可接受 `location_ref`。

评测程序直接调用现有产品检索接口，取前 20 个结果，然后计算：

- Recall@1/3/5/10/20：正确位置是否进入前 K。
- Claim Complete@K：需要多篇论文时，是否全部找齐。
- MRR：第一个正确位置平均排得多靠前。
- Technical Error：Java、Qdrant、MySQL 或网络错误。

## 明确不做

- 不新增 Evidence Target 或 Source Quote 评测体系。
- 不建设新的 Probe Catalog。
- 不评测论文发现。
- 不评测 Agent 查询质量。
- 不增加 Query 改写、中英文、无答案等测试集。
- 不做 Sparse-only、Dense-only、Hybrid 对照。
- 不增加重复率、上下文效率和成本指标。
- 不扩展 Golden Expanded Dataset。
- 不改 Chunk、Embedding、RRF、MySQL、Qdrant Schema 或前端。

这些都不属于 v1。

## 评测怎么运行

```text
Golden Claim
  -> 读取 retrieval query
  -> 使用 required_evidence 中的论文作为检索范围
  -> 调用 /internal/v1/corpus/locations/search
  -> 取前 20 个 Location
  -> 和 accepted_locations 对比
  -> 输出每题结果和总结果
```

这里故意提前给出正确论文。这样只测 Location Retriever，不把“Agent 没找到论文”混进来。

命令：

```bash
.venv-harness/bin/python -m harness_py retrieval-eval \
  --product-corpus-map research/golden-data/product-corpus-map.local.yaml \
  --out research/golden-data/local-runs/retrieval-baseline-<timestamp>.json
```

## 数据格式

继续使用当前 Claim，不增加新文件格式：

```yaml
claims:
  transformer_adam_hyperparameters:
    statement: >-
      Attention Is All You Need used Adam with beta1 = 0.9, beta2 = 0.98,
      and epsilon = 1e-9.
    retrieval_queries:
      - Transformer Adam optimizer beta1 beta2 epsilon
    required_evidence:
      - paper_id: attention_is_all_you_need_2017
        accepted_locations:
          - section_ref_...
          - page_ref_...
```

`retrieval_queries` 只用于 Retriever 评测，不参与回答正确性评分，也不改变 Semantic Judge 的 Claim 内容。

## 报告内容

总报告保存：

- Dataset ID 和内容 Hash。
- Claim Catalog Hash。
- Retrieval Query Hash。
- Product Corpus Map Hash。
- 评测时间。
- 10 个 Claim 的汇总指标。
- Technical Error 数量。

每道 Query 保存：

- Claim ID 和 Query 原文。
- 检索论文范围。
- 可接受 Location。
- 前 20 个实际候选及排名。
- Candidate 的 `paper_id`、`paper_version`、`location_ref`、类型、页码和三类 Score。
- 每个证据要求首次命中的排名。
- Recall@K、Claim Complete@K 和 MRR。

这样发生漏召回时，可以直接看实际候选，不需要重新跑 Agent。

## 需要完成的代码

当前工作树已经有初版：

- `harness_py/evaluation/retrieval.py`
- `harness_py/tests/test_retrieval_evaluation.py`
- `harness_py/cli.py` 中的 `retrieval-eval`
- Stable 10 个 Claim 的 `retrieval_queries`

剩余开发只包括：

1. 报告增加 Product Corpus Map Hash。
2. Candidate 报告保留 `paper_version`。
3. 补充这两个字段对应的测试。
4. 启动 Java，跑一次真实稳定集 Baseline。
5. 人工检查所有未命中的 Claim，确认是检索问题还是 `accepted_locations` 标注问题。

不在本次顺手增加其他抽象或指标。

## 测试

保留当前测试并补两个用例：

- PAGE 和 SECTION 都可接受时，命中任意一个就算 Requirement 通过。
- Product Corpus Map Hash 和 Candidate `paper_version` 正确写入报告。

验证命令：

```bash
.venv-harness/bin/python -m unittest \
  harness_py.tests.test_retrieval_evaluation \
  harness_py.tests.test_golden_data

.venv-harness/bin/python -m harness_py validate
```

真实运行前先执行 Claim Location Audit，避免用已经失效的 `location_ref` 测 Retriever。

## 完成标准

满足下面条件，v1 就结束：

- Stable 10 个 Claim 都有 Retrieval Query。
- Unit Test 和 Dataset Validate 通过。
- Claim Location Audit 通过。
- 真实 `retrieval-eval` 完成，Technical Error 为 0。
- 报告保留每道题的前 20 个候选。
- 所有 Miss 已人工检查并写明原因。
- 生成一份不覆盖历史结果的 Baseline JSON。

## v1 之后怎么用

以后无论保持 PAGE/SECTION，还是改成 Passage，都继续跑：

```text
同一批 Query
  -> 返回前 K 个检索单元
  -> 检查正确单元是否进入前 K
```

Chunk 改变后，需要重新审核和更新 `accepted_locations`，但评测程序、指标和报告形式不变。

v1 先把当前产品的 10 道题测清楚。是否扩数据、增加 Source Evidence 标注或比较 Chunk 方案，拿到这份
Baseline 后再决定。
