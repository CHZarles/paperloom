# PaperLoom-31 产品对齐检索评测 Spec

日期：2026-08-23
状态：Implemented locally and technically verified；独立 Query Review 待完成，尚未提交或部署。

关联 Proposal：
[PaperLoom-31 产品对齐检索评测 Proposal](paperloom-31-product-aligned-retrieval-benchmark-proposal-2026-08-23.md)

## 1. 目标

在不改变现有 L2-Raw 的前提下，为 PaperLoom-31 增加 L2-Source-Aligned：使用冻结、人工复核的等义英文
Query 运行同一套单论文 Location Retrieval 和 Exact Read 校验。

完成后，一个 `run.json` 必须同时包含：

```text
l2                 原始中文 Query 的 L2-Raw
l2_source_aligned  等义英文 Query 的 L2-Source-Aligned
l3                 中文用户问题的端到端 Agent 结果
```

本 Spec 不定义生产检索优化，也不为 Source-Aligned 分数设置目标值。

## 2. 修改范围

| 文件 | 改动 |
| --- | --- |
| `research/benchmark/paperloom-31-v1/source-aligned-queries-v1.yaml` | 新增 31 条冻结 Query |
| `harness_py/evaluation/paperloom31.py` | 加载 Query 文件，并复用 `_run_l2` 执行第二组 L2 |
| `harness_py/cli.py` | 增加 Query 文件参数和第二组 L2 摘要 |
| `harness_py/tests/test_paperloom31.py` | 增加 Query 合同和 Raw/Source-Aligned 路径测试 |

不修改：

```text
src/main/**
frontend/**
Qdrant Collection 和索引配置
Embedding Provider
RRF、Top K、Reading Location、Qrel
Agent Prompt、Tool Schema、Loop
部署配置
```

不新增依赖。

## 3. Companion 文件合同

路径：

```text
research/benchmark/paperloom-31-v1/source-aligned-queries-v1.yaml
```

Schema：

```yaml
schema_version: paperloom-source-aligned-queries/v1
dataset_id: paperloom-31-v1
targets:
  target_paper_01_attention_is_all_you_need:
    original_query_sha256: <64 lowercase hex characters>
    source_aligned_query: How many sentence pairs are in the WMT 2014 English-German training dataset?
```

Hash 规则：

```python
sha256(snapshot_query.encode("utf-8")).hexdigest()
```

不做 Unicode Normalization、大小写转换或空白折叠。Companion 中的 `source_aligned_query` 必须非空，且
不得包含首尾空白；运行时不静默修正。

文件必须满足：

1. `schema_version` 精确等于 `paperloom-source-aligned-queries/v1`；
2. `dataset_id` 精确等于 Snapshot 的 `dataset_id`；
3. `targets` Key 与 Snapshot Target Key 集合完全相等；
4. 每个 `original_query_sha256` 与 Snapshot 中对应原始 Query 精确匹配；
5. 每个 `source_aligned_query` 是非空字符串，且等于自身的 `strip()` 结果。

任一校验失败，抛出 `PreparationError`，在创建 Run 目录、登录产品 API 或发起检索前终止。

## 4. Query 制作规则

Source-Aligned 的含义是“与英文论文语料语言对齐”，不是“包含目标答案的 Oracle Query”。

制作流程：

1. 翻译者只接收原始中文 Query；
2. 翻译者看不到论文正文、Expected Answer、`location_ref`、答案 Span、当前排名和失败列表；
3. 英文 Query 保留原问题中的实体、数字、章节和约束；
4. 保持原问题的信息粒度和问句结构，不改写成关键词检索式，不主动扩展同义词；
5. 不添加原问题没有的答案值、同义答案、目标标题或目标正文短语；
6. 人工复核语义等价后一次性冻结；
7. 冻结后不得根据 Benchmark 排名修改 Query。需要纠正真实翻译错误时，创建
   `source-aligned-queries-v2.yaml` 并保留 `v1` 文件；字段结构未变化时，文件内的 Schema Version 仍为
   `paperloom-source-aligned-queries/v1`。

代码不实现语言检测或自动翻译。防泄漏依赖上述制作流程、人工 Review 和版本控制，不引入额外模型调用。

## 5. CLI 合同

`paperloom31 run` 新增必填参数：

```text
--source-aligned-queries
```

该参数不设默认值，也不自动选择“最新版本”。Snapshot Query 变化时必须发布新的 Companion，并在命令中
显式配对，避免把一个 Query 集合误用于另一个 Snapshot。

示例：

```bash
.venv-harness/bin/python -m harness_py paperloom31 run \
  --snapshot research/benchmark/local/snapshots/a61e8c1c240b2e8873b88d20da497b9ec0b98d9631c77a3e05c0634eeb92ecd3.json \
  --source-aligned-queries research/benchmark/paperloom-31-v1/source-aligned-queries-v1.yaml
```

成功输出在现有字段基础上新增：

```json
{
  "l2_metrics": {},
  "l2_source_aligned_metrics": {}
}
```

不新增独立命令，不允许只跑 Source-Aligned 后把结果冒充完整 PaperLoom-31 Run。

## 6. Runner 行为

### 6.1 加载时机

`run_benchmark` 完成现有 Snapshot 和 Config Hash 校验后，立即加载并校验 Companion。校验通过后才创建
Run 目录和连接产品服务。

`run_benchmark` 增加一个显式参数：

```python
source_aligned_queries_path: str | Path
```

CLI 必须传入文件路径。Benchmark 内部不扫描目录，也不自动选择“最新版本”。

### 6.2 复用 L2

现有 `_run_l2` 增加最少的可选输入：

```python
query_by_target: dict[str, str] | None = None
stage_name: str = "L2"
request_label: str = "l2"
```

每个 Target 使用：

```python
query = (
    query_by_target[target_id]
    if query_by_target is not None
    else target["query"]
)
```

Companion 仍在创建 Run 目录前加载和校验，但 Source-Aligned 检索必须放在 L3 之后：

```python
l2 = _run_l2(snapshot, gateway, user_id)

# 按现有路径执行 L3。
l3 = _run_l3(...)

l2_source_aligned = _run_l2(
    snapshot,
    gateway,
    user_id,
    query_by_target=source_aligned_queries,
    stage_name="L2_SOURCE_ALIGNED",
    request_label="l2-source-aligned",
)
```

固定顺序为：

```text
L0 -> G0 -> L1 -> L2-Raw -> L3 -> L2-Source-Aligned
```

`HybridReadingLocationRetriever` 会为每次检索申请并结算 Embedding Token。Source-Aligned 新增的 31 次检索
如果放在 L3 前，可能通过余额、限流状态或缓存热度改变 L3 结果。此次只调整 Python Benchmark 顺序，不增加
Java 配额绕过或专用评测 Endpoint。

`stage_name` 只用于 Technical Error 标识，`request_label` 只用于区分 Request/Conversation ID。Raw 调用不传
新参数，其 Query、Request ID 和错误 Stage 保持现状。

禁止复制 `_run_l2`、`_retrieval_metrics`、Exact Read 或 Source Quote 校验逻辑。

### 6.3 检索合同

两次 L2 只有 `query_text` 不同。每个 Target 都必须：

1. 只 Scope 到 Target 的同一个 `product_paper_id`；
2. 使用同一个 `location_type` Filter；
3. 使用 `top_k=10`；
4. 以同一个唯一 `location_ref` 计算 1-based Rank；
5. 命中后执行同一个 `read_paper_content`；
6. 执行相同 Paper、Page、Content Hash、Source Span 和 Source Quote 校验；
7. 使用 `_retrieval_metrics(rows, (1, 3, 5, 10))` 和同一个 `by_type` 计算方式。

不绕过 Java Corpus API，不直连 Qdrant，不缓存或复用 Raw 的检索结果。

## 7. Run Artifact

新生成的 `run.json` 使用 `paperloom-benchmark-run/v3`，保留完整 `l2` 对象并新增顶层对象：

```json
{
  "l2_source_aligned": {
    "executed": true,
    "target_count": 31,
    "query_set": {
      "schema_version": "paperloom-source-aligned-queries/v1",
      "file_sha256": "<sha256 of exact companion file bytes>"
    },
    "metrics": {
      "count": 31,
      "recall_at_1": 0.0,
      "recall_at_3": 0.0,
      "recall_at_5": 0.0,
      "recall_at_10": 0.0,
      "mrr": 0.0
    },
    "by_type": {},
    "exact_read_passed": 0,
    "targets": [],
    "technical_errors": []
  }
}
```

实际指标按运行结果填写，上述 `0.0` 只是结构示例，不是预期值。

`targets` 使用现有 L2 Target Row 结构，不保存 Query 原文。Source-Aligned 对象额外保存 `query_set`，使结果
可以追溯到固定文件；`l2` 对象不增加字段。

顶层 `technical_errors` 汇总必须包含 `l2_source_aligned.technical_errors`。现有 `baseline.stages`、
`internal_beta_gate` 和 L3 Gate 不因本次新增诊断指标而改变。

实现时把 `RUN_SCHEMA_VERSION` 从 `paperloom-benchmark-run/v2` 升为
`paperloom-benchmark-run/v3`。历史 `v2` Run 不修改、不回填 `l2_source_aligned`。新 Schema 保留
`l0`、`g0`、`l1`、`l2`、`l3` 和 Gate 的字段名，但严格校验 Schema Version 的消费者必须显式接受
`v3`；不能只凭字段同名宣称完全兼容。

## 8. 错误语义

### Companion 无效

缺文件、YAML 无法解析、Schema/Dataset 不匹配、Target 覆盖不完整、Hash 错误、Query 为空或包含首尾空白：

```text
PreparationError(stage="run", code="SOURCE_ALIGNED_QUERY_*", detail=...)
CLI exit code = 2
不创建 Run Artifact
```

### Source-Aligned 运行时错误

Java API、读取或其他产品调用失败时，沿用现有 L2 行为：

- 当前 Target 的 `rank` 为 `null`；
- 错误写入 `l2_source_aligned.technical_errors`，Stage 为 `L2_SOURCE_ALIGNED`；
- 其余 Source-Aligned Target 继续执行，已经完成的 L3 结果保持不变；
- 顶层 `technical_errors` 同时包含该错误。

现有 `_retrieval_metrics` 会把该 Target 的 `rank=null` 保留在 31 个 Target 的分母中，因此 Artifact 中仍会
出现一个数值结果。但只要 `l2_source_aligned.technical_errors` 非空：

1. 该组指标整体不可比较，不能把降低解释为普通 Retrieval Miss；
2. `run.json` 仍需写出，保留诊断现场；
3. CLI 在写出并打印 Artifact 路径后返回 Exit Code `2`。

不另加 `valid`、`comparable` 或自定义指标计算分支；`technical_errors` 和非零 Exit Code 已足够表达失败。

## 9. 测试

在现有 `unittest` 文件中增加最小覆盖：

1. 合法 Companion 能加载 31 个 Query；
2. 缺失 Target、额外 Target、原始 Query Hash 不匹配、空 Query 和首尾空白分别失败；
3. Raw 调用仍把 Snapshot 的中文 Query 传给 `search_paper_content`；
4. Source-Aligned 调用把 Companion 英文 Query 传给同一方法；
5. 两条路径使用相同的 Paper Scope、Element Type、Top K、Qrel 和 Exact Read 逻辑；
6. 完整 Run 中 L3 先于 L2-Source-Aligned 执行；
7. CLI 摘要包含 `l2_source_aligned_metrics`；Source-Aligned 技术错误存在时，Artifact 已写出且 CLI 返回
   Exit Code `2`。

不为 31 条翻译写“必须命中”的单元测试，也不 Mock 一个虚假的 Recall 提升。

## 10. 实施验收

代码测试：

```bash
.venv-harness/bin/python -m unittest harness_py.tests.test_paperloom31
```

真实运行使用第 5 节命令。验收 Run 必须满足：

1. `l2.target_count == 31`；
2. `l2_source_aligned.target_count == 31`；
3. 两组 `technical_errors` 均为空；
4. L2-Raw 仍使用原始 Query，Recall@5 与冻结基线同为 `23/31`；若不同，先调查环境或索引漂移，不把差异
   归因于本改动；
5. Run 同时保存两组 Recall@1/3/5/10、MRR、分类型结果、逐 Target Rank 和 Exact Read 结果；
6. L3 在 Source-Aligned 前执行并正常报告结果；
7. Source-Aligned 的实际结果无论高低都原样记录，不按结果修改 Query。

## 11. 完成定义

以下条件全部满足才算完成：

- Companion 经过盲译和人工 Review 后提交；
- 代码和最小测试通过；
- 用冻结 Snapshot 生成一个同时包含三层指标的新 Run；
- 文档记录 Run ID、代码 Revision、两组 L2 和 L3 原始命中数；
- 没有生产代码、索引或配置变更；
- 后续优化决策按 Proposal 第 3 节解释矩阵作出，而不是只看一个 Recall 百分比。

本 Spec 完成后，不自动启动 Embedding 迁移。只有 Source-Aligned 或 L3 暴露出可复现的产品失败，才另开
对应生产优化 Proposal。

## 12. 实施与验证记录

执行时间：2026-08-23
Run：`research/benchmark/local/runs/20260823T042314Z-5fd8003b/run.json`
Artifact Code Revision：`5db2f1bdd7ae4f43539a1643f7e7d0646f666339`；Artifact 在本次实现提交前生成，因此记录的是基线 Revision
Snapshot：`a61e8c1c240b2e8873b88d20da497b9ec0b98d9631c77a3e05c0634eeb92ecd3`
Query Set SHA-256：`e927fe5d2846f0bc52a7055948998680587877bc4c429d4f07dc57f168721c4a`

本次 Companion 从只包含原始 Query 的提取结果生成，真实 Run 后未按排名修改。独立人工 Review 尚未完成，
因此下面的 Source-Aligned 数值在 Review 签字前属于 provisional measurement。

测试：

```text
.venv-harness/bin/python -m unittest harness_py.tests.test_paperloom31
12 passed

.venv-harness/bin/python -m unittest discover -s harness_py/tests
206 passed, 7 skipped
```

真实 Run：

| 指标 | L2-Raw | L2-Source-Aligned |
| --- | ---: | ---: |
| Recall@1 | `14/31 = 45.16%` | `23/31 = 74.19%` |
| Recall@3 | `23/31 = 74.19%` | `30/31 = 96.77%` |
| Recall@5 | `23/31 = 74.19%` | `30/31 = 96.77%` |
| Recall@10 | `25/31 = 80.65%` | `30/31 = 96.77%` |
| MRR | `0.5821` | `0.8441` |
| Exact Read | `25/31` | `30/31` |
| Technical Errors | `0` | `0` |

L3 为 `16/17` Hard Pass，Technical Error 为 `0`；`single_06` 失败，因此现有 Internal Beta Gate 未通过。
L3 在 Source-Aligned 前执行，该失败不是新增评测阶段造成的。唯一 Source-Aligned Miss 仍是
`target_paper_22_llm_int8` 的 Appendix C 结构导航问题。Raw L2 与冻结基线完全一致，证明实现没有通过覆盖
中文 Query 改写历史指标；Source-Aligned 的提升只作为语言对齐诊断，不宣称生产性能提升，也不自动触发
Embedding 迁移。
