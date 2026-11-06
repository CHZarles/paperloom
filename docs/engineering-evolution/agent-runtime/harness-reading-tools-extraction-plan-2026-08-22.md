# Java Reading Tools 抽取计划

日期：2026-08-22

状态：待实施

## 1. 目标

把现有 5 个读论文 Tool 的无状态功能收敛到一个 Java 模块：

```text
search_paper_candidates
find_papers_by_identity
get_paper_structure
search_paper_content
read_paper_content
```

目标架构：

```text
                         Java PaperReadingService
                   五个函数 + 校验 + ACL + Reading Model
                              /              \
                             /                \
              Harness internal adapter       以后新增的 MCP adapter
              disclosure / Evidence          独立 Tool Schema
              Source Quote / final answer     只查询，不依赖 Harness
```

Java 模块是唯一的数据功能实现。Harness 和未来 MCP 只是两个不同的协议适配器，不共享
Python 业务代码，也不要求 Tool Schema 相同。

## 2. 本阶段范围

本阶段只完成 Java Reading Tools 抽取并让现有 Harness 改用它。

做：

- 将 `CorpusRetrievalService` 重构为一个明确的 `PaperReadingService`；
- Java 提供 5 个有类型的方法；
- Java 负责参数校验、Paper ACL、锁定 Scope、Current Reading Model、检索和精确读取；
- `read_paper_content` 根据 `location_ref` 从 MySQL Reading Model 读取正文；
- 现有 Harness internal controller 调用该模块；
- Python Harness 只保留 Agent 状态和协议适配。

不做：

- MCP Server；
- Java 公共查询接口；
- Field Extraction；
- 前端；
- 通用 Tool registry、动态 Tool executor、interface 或 factory。

## 3. Java 模块接口

直接重构现有 `CorpusRetrievalService`，不要在它外面再套一层只做转发的新 Service。完成后
删除旧类，避免两套实现。

```java
public class PaperReadingService {
    PaperSearchResult searchPaperCandidates(PaperSearchQuery query);
    IdentitySearchResult findPapersByIdentity(IdentitySearchQuery query);
    StructureResult getPaperStructure(StructureQuery query);
    ContentSearchResult searchPaperContent(ContentSearchQuery query);
    ContentReadResult readPaperContent(ContentReadQuery query);
}
```

方法名是 Java 内部接口，不要求未来 MCP 使用相同名称或参数。

所有 Query 都必须携带由协议 adapter 构造的执行上下文：

```text
user_id
scope_paper_ids
```

这两个字段不属于模型可填写的 Tool 参数。`scope_paper_ids` 只是本次查询的收窄范围，不是
授权证明；`PaperReadingService` 必须始终根据 `user_id` 查询数据库 ACL，并取 ACL 与 Scope 的
交集。Harness adapter 传入本 Run 的锁定 Paper Scope。未来 MCP adapter 从认证上下文取得
`user_id` 并构造 Scope，不接受客户端在 Tool 参数中传入或扩大执行上下文。

### 3.1 Validation And Errors

Java 负责不依赖 Harness Run 状态的校验：参数类型、数量、范围、Paper ACL、锁定 Scope、
Current Reading Model、索引状态和 Location 有效性。Java 使用一个具体的
`PaperReadingException(code, safeMessage)` 返回稳定错误码，不增加通用错误框架。

V1 至少区分：

```text
READING_ARGUMENTS_INVALID
PAPER_NOT_AVAILABLE
READING_MODEL_NOT_READY
RETRIEVAL_UNAVAILABLE
```

`InternalCorpusController` 以非 2xx 响应返回错误码和安全消息。Python Harness 以错误码为准做
固定映射，不按 HTTP status 将所有错误归为同一类：

```text
READING_ARGUMENTS_INVALID / PAPER_NOT_AVAILABLE
-> 现有 recoverable Tool result

READING_MODEL_NOT_READY / RETRIEVAL_UNAVAILABLE
-> 现有 ResearchSystemError，作为技术失败结束当前 Run
```

V1 直接在 Python adapter 中使用固定分支，不增加 disposition 类型、错误 registry 或通用错误
框架。`PAPER_NOT_AVAILABLE` 的安全消息不得暴露目标 Paper 是否存在或属于其他用户。

JSON 解析、字段类型和数组形状错误可能在进入 `PaperReadingService` 前被 Spring 拒绝。
`InternalCorpusController` 必须在本 Controller 内将这些绑定错误统一映射为
`READING_ARGUMENTS_INVALID`，不能让它们落入通用 503；不增加全局 `ControllerAdvice`。

Python 继续负责依赖当前 Harness Run 状态的两种校验：

```text
PAPER_ID_NOT_DISCLOSED
LOCATION_NOT_DISCLOSED
```

这两项不能移入 `PaperReadingService`，因为共享 Java 模块不拥有 Harness disclosure state。

## 4. 五个函数的职责

### 4.1 `searchPaperCandidates`

- 搜索或浏览 Scope 内可访问的 Paper；
- 支持现有标题、摘要、作者、Venue、年份和 Paper ID 条件；
- 返回有界 Paper metadata；
- 不管理 Harness Paper disclosure 状态。

### 4.2 `findPapersByIdentity`

- 按 Paper ID、标题、文件名、DOI、arXiv ID、作者和年份解析身份；
- 明确返回 resolved、ambiguous 或 not found；
- 不替 Harness 自动选择歧义结果。

### 4.3 `getPaperStructure`

- 从 Current Reading Model 返回 PAGE 或 SECTION Location；
- 只返回导航数据和 `location_ref`；
- 不管理 Harness Location disclosure 状态。

### 4.4 `searchPaperContent`

- 在指定 Paper 的 Current Reading Model 中检索 PASSAGE、TABLE 和 FIGURE；
- 返回排序后的 Location preview；
- Java 负责单次请求限制、索引状态和安全错误；
- Qdrant 只负责候选检索，不是精确正文的数据源。

### 4.5 `readPaperContent`

- 输入有界 `location_refs`；
- 重新检查 Scope、ACL 和 Current Reading Model；
- 从 MySQL durable Reading Model 解析精确正文：

```text
PAGE    -> PaperPage.pageText
SECTION -> PaperSection.sectionText
PASSAGE -> PaperPassage.contentText
TABLE / FIGURE -> PaperReadingElement searchableText / bodyText / captionText
```

- 返回 canonical content 和内部定位数据；
- 不读取客户端回传的正文、hash、Source Span、model version 或 Qdrant payload；
- 不创建 `PaperSourceQuote`。

canonical content 和 canonical Source Span 的来源固定为：

```text
PAGE    -> PaperPage.pageText / PaperPage.sourceSpanJson
SECTION -> PaperSection.sectionText / PaperSection.sourceSpanJson
PASSAGE -> PaperPassage.contentText / PaperPassage.sourceSpanJson
TABLE / FIGURE
        -> PaperReadingElement 正文选择规则 / PaperLocation.sourceSpanJson
```

`PaperLocation` 继续承担 `location_ref`、Paper/model 归属和 Source Quote 定位身份。

durable exact read 加载 PAGE、SECTION 或 PASSAGE 时，必须验证：

- canonical 实体与 `PaperLocation` 属于同一 Paper 和 model version；
- PAGE 的页码匹配，SECTION/PASSAGE 的 `PaperLocation.sourceObjectId` 指向对应实体；
- canonical 实体的 `sourceSpanJson` 与 `PaperLocation.sourceSpanJson` 完全一致。

加载 TABLE 或 FIGURE 时，必须以 `(paperId, modelVersion, readingElementId)` 定位
`PaperReadingElement`，不能只按 `readingElementId` 建立跨 Paper Map，并验证：

- `PaperLocation.sourceObjectId` 等于 `PaperReadingElement.readingElementId`；
- 两者的 Paper、model version、`locationRef` 和 `locationType` 一致；
- TABLE 只关联 TABLE Element，FIGURE 只关联 IMAGE、FIGURE 或 CHART Element。

`readPaperContent` 保持现有批量部分成功合同：任一 Location 不存在、越权、不是 Current Model、
正文无效或上述 resolver 一致性校验失败时，将其加入 `missing_location_refs`，其他有效 Location
继续返回。
`HarnessReadingService` 只为有效项创建 Source Quote。通过验证后，Source Quote 继续使用
`PaperLocation.sourceSpanJson` 执行现有分页拆分和引用定位。不能因为抽取 resolver 改变现有
citation identity。

部分成功只覆盖 `PaperReadingService.readPaperContent()` 能判定的不可用 Location。Source Quote
拆分、序列化或持久化异常仍作为技术失败抛出，并由 `HarnessReadingService` 的外层事务回滚整个
exact-read 请求；本阶段不引入逐 Location 事务。

## 5. Harness Adapter

Java internal adapter 继续使用 `/internal/v1/corpus/**`，现有 Harness HTTP 合同不改名。

```text
paper / identity / structure / search
-> InternalCorpusController
-> PaperReadingService

exact read
-> InternalCorpusController
-> HarnessReadingService.readAndCreateQuotes
   -> PaperReadingService.readPaperContent
   -> PaperSourceQuoteReadService.createQuotes
-> 返回现有 Harness Source Quote payload
```

`HarnessReadingService` 是一个具体 Java 类，只提供 Harness 需要的
`readAndCreateQuotes()`，并在一个事务内完成 canonical read 和 Source Quote materialization。
不增加 interface 或 factory。

Source Quote 是 Harness 的证据生命周期，不进入共享的 `PaperReadingService`。未来 MCP 只调
`PaperReadingService.readPaperContent()`，不会调用 `HarnessReadingService`。

Python `ReadingCorpusTools` 只保留：

- Agent Tool Schema；
- Paper disclosure；
- Location disclosure；
- current-Run Evidence Ledger；
- 按固定映射处理 Java 业务错误和技术错误；
- 把 Tool 调用转发给 Java。

Python 不再拥有：

- Paper 搜索或身份匹配算法；
- Reading Model 或 Qdrant 查询逻辑；
- canonical content 解析；
- ACL 或 Scope 判定；
- Source Quote 创建；
- 普通参数的类型、数量或范围校验；
- `evidence_payloads_by_location_ref` 缓存和回传。

## 6. 实施步骤

### Step 1：冻结现有 Harness Tool 合同

- 固定 5 个 Tool 的模型可见名称和 JSON Schema；
- 固定 disclosure、Evidence Ledger 和 Source Quote 输出；
- 固定现有 internal Corpus endpoint 的成功响应；唯一允许删除的字段是内部
  `evidence_payloads`；
- 错误响应允许按 3.1 收敛为稳定错误码；
- 记录当前 Java/Python 回归测试基线；
- 为 `paperloom31 run` 增加必填 `--benchmark-user-id`。Runner 只使用该 ID 对应的用户，并在运行
  任何 Stage 前通过现有 `/admin/users` 校验用户存在且角色为 `USER`；不再自动选择返回列表中的
  第一个普通用户；
- Runner 在运行任何 Stage 前通过现有 `/admin/users/list` 读取该用户的 LLM/Embedding
  `remainingTokens`，并将其作为 `benchmark_user.starting_token_balances` 写入 `run.json`；不新增生产
  API；
- 为 post-change 运行增加可选 `--baseline-run <run.json>`。提供该参数时，Runner 必须在运行任何
  Stage 前校验 Snapshot、Config、Source-Aligned Query、Provider、`ordinary_user_id` 和两项起始
  Token 余额均与 baseline 一致，不一致则直接失败；
- 先单独迁移 `harness_py/evaluation/paperloom31.py`：L2 按 Paper 缓存现有
  `/internal/v1/corpus/locations/export` 结果，从中取得目标 Location 的标准 Source Span，先验证
  其 hash 等于 Golden Case 已冻结的 `source_span_hash`，再与 `read_paper_content` 返回的
  `source_quotes[*].source_span_json` 做覆盖校验。不新增 Golden Snapshot 字段或版本，不再读取
  `ReadingCorpusTools.evidence_payloads_by_location_ref`；冻结 hash 校验就是新旧 oracle 使用同一
  标准的证明，不保留双 oracle 运行路径；
- 完成上述 Evaluation-only 修改和 `harness_py/tests/test_paperloom31.py` 后，在生产 Reading 链路
  仍保持原样时提交 Commit 1；
- Commit 1 后、修改生产代码前使用显式 `--benchmark-user-id` 运行一次完整 PaperLoom-31
  Benchmark，保存 baseline `run.json`。该文件必须包含 `code_revision`、Snapshot/Config/
  Source-Aligned Query hash、Provider、`ordinary_user_id` 及运行开始时的 LLM/Embedding Token
  余额。已有 Run 只有在这些身份与 Commit 1 完全一致且已经记录两项起始余额时才能复用，否则
  必须重跑；不得只用 L2 代替完整 baseline；
- baseline 成功落盘前不得开始 Step 2。

### Step 2：建立 `PaperReadingService`

- 将 `CorpusRetrievalService` 重命名并重构为 `PaperReadingService`；
- 保留现有 Paper Search、Identity Search、Structure 和 Content Search 行为；
- 将参数限制和 ACL/Scope 校验留在 Java；Scope 必须始终与数据库 ACL 取交集；
- 增加越权 Scope 和 Scope 外 Paper 的拒绝测试；
- 迁移测试到 `PaperReadingServiceTest`；
- 不保留旧 Service 或新增 pass-through wrapper。

### Step 3：实现 durable exact read

- 从 `ReadingModelQdrantIndexService.resolveLocationText()` 抽取正文选择规则；
- 为 `PaperReadingService.readPaperContent()` 批量读取所需 Passage、Element、Page 和 Section；
- 索引与 exact read 共用正文选择规则，但分别负责适合自身规模的批量加载；
- PAGE、SECTION、PASSAGE、TABLE、FIGURE 都增加测试；
- PAGE、SECTION、PASSAGE 增加 canonical 实体与 `PaperLocation` 关联或 Source Span 不一致时
  进入 `missing_location_refs`、且不创建 Source Quote 的测试；
- TABLE、FIGURE 增加 Paper/model、`locationRef`、`locationType` 或 Element 类型关联错误时进入
  `missing_location_refs` 的测试；
- 增加同一批次中有效和无效 Location 并存时仍返回有效项的测试；
- 删除 exact read 对客户端 `EvidencePayload` 的依赖；
- 从 Java `ContentSearchResult` 和 `/locations/search` 响应删除 `evidence_payloads`；
- 从 Java exact-read request 删除 `evidence_payloads` 字段和 DTO；
- Qdrant 正文、hash 和 Source Span 不再经过 Python 往返；
- 不得为了生产或评测保留 Payload cache 或回传字段；Commit 1 后的 Evaluation 已经不依赖它们。

### Step 4：让 Harness 成为薄适配器

- `InternalCorpusController` 的查询路由全部改调 `PaperReadingService`；
- Harness exact read 固定调用 `HarnessReadingService.readAndCreateQuotes()`；
- Python 删除 `_evidence_payloads` 和 payload cache；
- Python 删除普通参数的类型、数量和范围 preflight，只保留 disclosure 和 Evidence Ledger；
- Python gateway 不在 Java 前抢先解释业务参数，只负责协议字段投影和传输；
- `InternalCorpusController` 将 JSON 解析和字段绑定错误映射为 `READING_ARGUMENTS_INVALID`；
- Python 保留 disclosure 错误，并按 3.1 的固定映射处理其他 Java 错误；
- 模型可见 Tool 合同和最终引用结果保持不变。

### Step 5：回归

按顺序执行：

1. `PaperReadingServiceTest`，包含 ACL/Scope 越权、部分成功和 Source Span 不一致的负向用例；
2. durable Location resolver 测试；
3. `ReadingModelQdrantIndexServiceTest`；
4. `PaperSourceQuoteReadServiceTest`；
5. `HarnessReadingServiceTest`，验证 resolver 失败时不创建 Source Quote，Quote materialization
   异常时回滚整个事务；
6. `InternalCorpusControllerTest`，包含非法 JSON、字段类型和数组形状错误；
7. Corpus gateway 和 Agent Tool 的 Java 错误映射回归；
8. `harness_py/tests/test_paperloom31.py`，包含 L2 retrieval/read/citation consistency 回归；
9. Harness Tool、Agent Runtime 和 no-task Research 回归；
10. 按 5.1 运行完整 PaperLoom-31 Benchmark；
11. 真实 PDF 的 `search -> read -> citation reopen` 冒烟测试。

### Step 5.1：Benchmark Gate

完整 Benchmark 使用实现前后的同一份 Snapshot、Config、Source-Aligned Query、Provider 和
`ordinary_user_id`。baseline 前记录该用户 Redis 中 `user:token:llm:<user_id>` 和
`user:token:embedding:<user_id>` 的余额；post-change 前通过现有管理员 Token 追加能力将两项余额
恢复到相同起点。Runner 通过现有管理员查询接口读取余额并写入 `run.json`，不直接连接 Redis。
post-change 必须传入 baseline `run.json` 并在执行 Stage 前完成身份和余额校验。无法恢复相同用户
和起始余额时，不得开始严格回归对比：

```bash
.venv-harness/bin/python -m harness_py paperloom31 run \
  --snapshot research/benchmark/local/snapshots/a61e8c1c240b2e8873b88d20da497b9ec0b98d9631c77a3e05c0634eeb92ecd3.json \
  --source-aligned-queries research/benchmark/paperloom-31-v1/source-aligned-queries-v1.yaml \
  --config research/benchmark/paperloom-31-v1/benchmark.yaml \
  --runs-dir research/benchmark/local/runs \
  --benchmark-user-id <固定的普通用户ID> \
  --provider-source env
```

post-change 使用同一命令，并额外传入：

```bash
--baseline-run research/benchmark/local/runs/<baseline-run-id>/run.json
```

该命令固定执行：

```text
L0 -> G0 -> L1 -> L2-Raw -> L3 -> L2-Source-Aligned
```

通过条件：

1. L0、G0 通过，`technical_errors` 为空；
2. L1、L2-Raw、L2-Source-Aligned 的 Recall@K、MRR 和 `exact_read_passed` 不低于 baseline；
3. L3 不新增由本次重构导致的 technical failure、Contract failure、读取失败或引用失败；
4. L3 的随机 Judge 分数只作前后诊断，不因单次模型波动单独判定重构失败；
5. baseline 与 post-change 的 `ordinary_user_id` 和记录的起始 Token 余额一致；
6. 验收记录保存 baseline 和实现后两个 `run.json` 路径及逐项差异；
7. 不得重新生成 Snapshot、修改 Query、重建索引或更换 Provider 来让结果通过。

## 7. 提交拆分

### Commit 1：Decouple Benchmark Source Span Oracle

- 只修改 `paperloom31.py`、`harness_py/cli.py` 及其测试；
- L2 改用 `/locations/export` 和冻结的 `source_span_hash`，不再读取 Python Evidence Payload cache；
- 增加显式 Benchmark 用户选择、起始 Token 余额落盘和 post-change baseline 身份预检；
- 生产 Reading 请求、响应和 Harness 行为保持不变；
- Commit 后运行并保存完整 pre-change Benchmark baseline。

### Commit 2：Extract Java PaperReadingService

- 重构现有 `CorpusRetrievalService`；
- 迁移前四个查询函数及测试；
- Harness internal endpoints 的成功响应不变。

### Commit 3：Java-Owned Durable Exact Read

- 实现只凭 `location_ref` 的 exact read；
- 删除 Java search/read response/request 中的 Evidence Payload 链路；
- 增加事务化 `HarnessReadingService.readAndCreateQuotes()`；
- 保持 Harness Source Quote 输出不变。

### Commit 4：Thin Python Harness Adapter

- 删除 Python Evidence Payload cache/echo；
- 保留 disclosure 和 Evidence Ledger；
- 完成 Java/Python 回归及文档更新。

## 8. 完成标准

1. 5 个 Reading Tool 的数据功能只有一份 Java 实现；
2. `PaperReadingService` 可以脱离 Python Harness 被 Java 测试直接调用；
3. Python Harness 不包含检索、身份匹配、canonical read、ACL 或 Source Quote 创建逻辑；
4. exact read 只需要 `location_ref` 和 adapter 构造的执行上下文，Java 始终重新检查 ACL/Scope；
5. Java query 函数不创建 Source Quote；Harness adapter 仍能创建 Source Quote；
6. 现有 5 个 Harness Tool 的模型可见 Schema 和行为不变；
7. Java 返回稳定错误码，Python 只额外处理 disclosure state，并按固定映射区分模型可恢复错误
   和技术失败；
8. Java/Python 生产请求和响应中不存在 `evidence_payloads`；
9. PaperLoom 3.1 评测不依赖 Python Evidence Payload cache；
10. `PaperReadingService.readPaperContent()` 对 resolver 可判定的不可用 Location 保持
    `items` + `missing_location_refs` 的批量部分成功合同；
11. Scope 无法扩大数据库 ACL，搜索、读取、引用定位和 PDF 重开链路通过；
12. 使用相同 Benchmark 身份完成实现前后完整 PaperLoom-31 对比，并通过 5.1 Gate。

达到这些条件后，再单独实现 MCP Server。MCP 定义自己的只读 Tool Schema，通过新的 Java
公共查询 adapter 调用 `PaperReadingService`，完全不导入 `ReadingCorpusTools`、Evidence Ledger
或其他 Harness 业务代码。
