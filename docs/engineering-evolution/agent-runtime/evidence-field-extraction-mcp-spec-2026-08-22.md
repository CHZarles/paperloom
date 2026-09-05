# Evidence-Grounded Field Extraction And MCP Server Specification

Date: 2026-08-22

Status: Draft for implementation.

Design source:
[`evidence-field-extraction-mcp-proposal-2026-08-22.md`](./evidence-field-extraction-mcp-proposal-2026-08-22.md)

Related contracts:

- [`controlled-research-harness-spec-2026-08-11.md`](./controlled-research-harness-spec-2026-08-11.md)
- [`governed-research-run-spec.md`](../../architecture/governed-research-run-spec.md)
- [`reading-model-and-agent-tools.md`](../../architecture/reading-model-and-agent-tools.md)
- [`evidence-and-citations.md`](../../architecture/evidence-and-citations.md)

本文使用 **MUST** 和 **MUST NOT** 表示实现与验收的强制要求，使用 **MAY** 表示兼容实现可以选择的行为。Proposal 解释取舍；本 Spec 定义 V1 必须实现的接口、状态、错误和验证证据。

## 1. Scope

本规范交付两个相互复用但职责不同的能力：

1. Harness 新增 `FIELD_EXTRACTION`：从一篇授权论文提取 1-10 个调用方指定字段；
2. PaperLoom 新增 MCP Server：只通过 5 个现有 Tools 向外部 Agent 暴露 Reading Model 能力。

V1 的完整闭环为：

```text
paper_id + requested fields
-> authorized Paper scope
-> Paper/Location disclosure
-> Exact Read
-> current-Run Source Quotes
-> validated structured extraction
-> deterministic Markdown
-> existing citation mapping and PDF reopening
```

### Normative requirements

| ID | Requirement |
| --- | --- |
| `EFX-SCOPE-001` | `FIELD_EXTRACTION` MUST 一次只处理一个 Paper ID 和 1-10 个请求字段。 |
| `EFX-SCOPE-002` | V1 MUST 复用现有 `AgentsSdkHarnessRuntime`、`ReadingCorpusTools`、Protocol Reducer、RunControl 和 Java Corpus 数据面；MUST NOT 新增第二个 Agent Runtime 或独立工作流引擎。 |
| `EFX-SCOPE-003` | V1 MUST NOT 新增数据库表、Extraction Template、跨论文比较、复现包、外部文献搜索或视觉模型读取。 |
| `MCP-SCOPE-001` | MCP Server V1 MUST 只声明 Tools Capability；`tools/list` MUST 只包含 5 个现有 Reading Model Tools，不新增公开 MCP Tool。 |
| `MCP-SCOPE-002` | MCP 层 MUST 是现有 Corpus 接口的 Adapter；MUST NOT 复制检索、Source Quote 创建、引用验证或 Agent 循环。 |
| `MCP-SCOPE-003` | V1 MUST NOT 声明 Prompts 或 Resources Capability；Hosted Harness Tools 继续延期。 |

## 2. Existing Owners And New Seam

职责所有权保持不变：

| State or behavior | Owner |
| --- | --- |
| 用户身份、Paper 访问、Current Reading Model | Java |
| Qdrant 候选检索、MySQL Canonical Read、Source Quote 持久化 | Java |
| Paper/Location disclosure、current-Run Evidence Ledger | `ReadingCorpusTools` |
| Agent/Tool 循环 | OpenAI Agents SDK Runner |
| Final Submission 状态和确定性结构校验 | `research_contract.py` |
| Deadline、用量、取消、技术失败 | `RunControl` / Live Harness |
| MCP 协议映射和 Session-local Corpus 实例 | MCP Adapter |
| MCP Tool 角色、账户、Paper Access、额度和审计 | Java Authenticated Agent Tool Facade |

MCP 新 Seam 为：

```text
MCP Client
  -> PaperLoom MCP interface
  -> ReadingCorpusTools.call(...)
  -> AuthenticatedAgentToolReader
  -> Java /api/v1/agent-tools/{tool_name}
  -> Spring Security + CorpusRetrievalService
```

### Normative requirements

| ID | Requirement |
| --- | --- |
| `ARCH-001` | MCP Tool Schema MUST 从现有 `ReadingCorpusTools.definitions()` 投影，或通过共享定义函数生成；不得维护一份可独立漂移的 Corpus Tool Schema。 |
| `ARCH-002` | MCP 返回给外部 Agent 的 Corpus 结果 MUST 使用现有 `model_facing_payload()` 删除内部评分、Evidence Payload、Source Span 和基础设施字段。 |
| `ARCH-003` | V1 MCP Tool MUST 只适配 `ReadingCorpusTools`；MUST NOT 从 MCP Adapter 启动 Harness Run、Agent 或 Provider Model。 |
| `ARCH-004` | MCP 数据面 MUST 经过 Java 用户权限入口；MCP Adapter MUST NOT 使用 `/internal/v1/corpus/**` 或仅凭请求体 `user_id` 代表用户。 |

## 3. Field Extraction Request

### 3.1 Transport Shape

现有 `/v1/research/turn`、`/v1/research/stream` 和 Redis Worker 请求 MAY 携带顶层 `task`：

```json
{
  "request_id": "generation-request-id",
  "conversation_id": "conversation-id",
  "user_id": 42,
  "user_message": "Extract the requested fields from the selected paper.",
  "scope": {
    "paper_ids": ["paper_123"]
  },
  "task": {
    "type": "FIELD_EXTRACTION",
    "paper_id": "paper_123",
    "fields": ["optimizer", "learning rate", "batch size"]
  }
}
```

缺少或为空的 `task` MUST 保持当前 Chat/Catalog/Research 行为。未知 `task.type` MUST 在进入 Harness 前返回请求错误。

### 3.2 Canonicalization

在 `ResearchHarnessService` Trust Boundary 执行一次请求规范化：

```text
canonical_field = trim(original_field)
duplicate_key   = canonical_field.casefold()
```

规范化后的任务：

```json
{
  "type": "FIELD_EXTRACTION",
  "paper_id": "paper_123",
  "fields": ["optimizer", "learning rate", "batch size"]
}
```

### Normative requirements

| ID | Requirement |
| --- | --- |
| `EFX-REQ-001` | `task` MUST 是 JSON Object；`type` MUST 严格等于 `FIELD_EXTRACTION`。 |
| `EFX-REQ-002` | `paper_id` MUST 是 trim 后长度为 1-32 的字符串，与当前 `Paper.paperId` 合同一致。 |
| `EFX-REQ-003` | `fields` MUST 是包含 1-10 个字符串的数组；每个字段 trim 后长度 MUST 为 1-100。 |
| `EFX-REQ-004` | `fields` 按 `casefold()` 计算后 MUST 唯一；重复字段 MUST 拒绝，不得静默合并。 |
| `EFX-REQ-005` | `scope.paper_ids` MUST 恰好等于 `[task.paper_id]`；不得把更大 Chat Scope 交给 Field Extraction Agent。 |
| `EFX-REQ-006` | Task 规范化 MUST 在模型调用、Corpus 调用和 Eval Capture `run.started` 之前完成。 |
| `EFX-REQ-007` | `TurnExecutionInput` MUST 新增 `task: JsonMap = field(default_factory=dict)`；Runtime 的公共 Protocol 方法 `run_turn` MUST 保持不变。 |

无效请求统一抛出 `ValueError`，内部 HTTP Adapter 映射为 HTTP 400，Redis Worker 映射为非重试able请求失败：

| Condition | Safe message/code |
| --- | --- |
| task shape/type invalid | `FIELD_EXTRACTION_REQUEST_INVALID` |
| field count/content/duplicates invalid | `FIELD_EXTRACTION_FIELDS_INVALID` |
| scope does not equal target paper | `FIELD_EXTRACTION_SCOPE_INVALID` |

## 4. Field Extraction Submission Contract

### 4.1 Contract And Tool

新增：

```text
AnswerContract.FIELD_EXTRACTION = "FIELD_EXTRACTION"
FIELD_EXTRACTION_FINAL_TOOL_NAME = "submit_field_extraction"
```

对显式 Field Extraction Task，`ResearchRunContext` 初始 Protocol State MUST 为：

```text
ProtocolState(
  phase = ACTIVE,
  contract = FIELD_EXTRACTION,
  submission_attempt = 0,
  validation_issues = []
)
```

该模式的模型可见工具只有：

```text
search_paper_candidates
find_papers_by_identity
search_paper_content
get_paper_structure
read_paper_content
submit_field_extraction
```

Provider 兼容用 `_continue_research_turn` 仍是内部工具，不列入公开能力。`get_research_skill` 不对该模式暴露；Runtime 直接将 `precision_fact_extraction` 指导注入指令并在 `skills_used` 记录该 Skill ID。

### 4.2 Submission Schema

`submit_field_extraction` 参数 Schema：

```json
{
  "type": "object",
  "required": ["paper_id", "items"],
  "properties": {
    "paper_id": {"type": "string"},
    "items": {
      "type": "array",
      "minItems": 1,
      "maxItems": 10,
      "items": {
        "type": "object",
        "required": ["field", "status", "value", "source_quote_refs"],
        "properties": {
          "field": {"type": "string"},
          "status": {"type": "string", "enum": ["FOUND", "NOT_FOUND"]},
          "value": {
            "oneOf": [
              {"type": "string", "maxLength": 2000},
              {"type": "null"}
            ]
          },
          "source_quote_refs": {
            "type": "array",
            "maxItems": 10,
            "uniqueItems": true,
            "items": {"type": "string"}
          }
        },
        "additionalProperties": false
      }
    }
  },
  "additionalProperties": false
}
```

### 4.3 Protocol Facts

`ProtocolFacts` MUST 增加 Field Extraction Validator 所需的最小投影：

```text
field_extraction_task
current_run_source_quotes
authorized_paper_ids
successful_content_search_count
```

`current_run_source_quotes` 只来自 `context.corpus.observations_by_evidence_id`，不得包含 prior-turn Research Memory。`successful_content_search_count` 只统计对目标 Paper 成功完成、且未返回 `error` 的 `search_paper_content` 调用。

这些 Facts MUST 进入现有 `protocol.transition` Eval 记录，使相同输入可以重放相同决定。

### 4.4 Validation

Validator 按以下顺序执行：

1. 校验顶层和 Item Shape；
2. 校验提交 `paper_id` 等于 Task Paper ID；
3. 校验提交字段集合与请求字段集合按 `casefold()` 完全相等；
4. 按请求顺序建立结果；
5. 校验每个 Item 的 Status/Value/Refs；
6. 校验 Source Quote 属于 current Run 且 `paper_id` 等于目标 Paper；
7. 任一 Item 为 `NOT_FOUND` 时，校验至少执行过一次成功的目标 Paper 内容搜索；
8. 交给现有 Reducer 决定 `ACTIVE`、`REPAIR` 或 `COMPLETE`。

| Issue code | Class | Condition |
| --- | --- | --- |
| `FIELD_EXTRACTION_PAPER_MISMATCH` | `FORMAT_ISSUE` | submitted paper differs from task |
| `FIELD_EXTRACTION_FIELDS_MISMATCH` | `FORMAT_ISSUE` | missing, extra, or duplicate submitted fields |
| `FIELD_EXTRACTION_ITEM_INVALID` | `FORMAT_ISSUE` | item shape or status invalid |
| `FIELD_EXTRACTION_FOUND_VALUE_REQUIRED` | `FORMAT_ISSUE` | `FOUND` value missing/blank |
| `FIELD_EXTRACTION_NOT_FOUND_PAYLOAD_INVALID` | `FORMAT_ISSUE` | `NOT_FOUND` carries value or refs |
| `FIELD_EXTRACTION_SEARCH_REQUIRED` | `MISSING_CONTRACT_INPUT` | result contains `NOT_FOUND` but no successful content search |
| `FIELD_EXTRACTION_FOUND_SOURCE_REQUIRED` | `MISSING_CONTRACT_INPUT` | `FOUND` has no Source Quote refs |
| `UNKNOWN_SOURCE_REF` | `MISSING_CONTRACT_INPUT` | ref not read in current Run |
| `FIELD_EXTRACTION_SOURCE_PAPER_MISMATCH` | `MISSING_CONTRACT_INPUT` | known current-Run ref belongs to another paper |

### Normative requirements

| ID | Requirement |
| --- | --- |
| `EFX-CONTRACT-001` | 每个请求字段 MUST 且只能对应一个提交 Item；输出顺序 MUST 按请求顺序确定性恢复。 |
| `EFX-CONTRACT-002` | `FOUND` MUST 有非空 `value` 和至少一个 current-Run Source Quote；Source Quote MUST 属于目标 Paper。 |
| `EFX-CONTRACT-003` | `NOT_FOUND` MUST 使用 JSON `null` value 和空 refs；不得携带解释性事实。 |
| `EFX-CONTRACT-004` | 任一 Item 为 `NOT_FOUND` 时 MUST 先成功执行至少一次目标 Paper 内容搜索；全部 `FOUND` 且 refs 合法时 MAY 不执行关键词搜索。 |
| `EFX-CONTRACT-005` | Field Extraction Submission MUST 是同一 Model Response 中唯一 Tool Call；复用现有 sibling Tool Group Guard。 |
| `EFX-CONTRACT-006` | `FORMAT_ISSUE` MUST 进入 `REPAIR(FIELD_EXTRACTION)`；`MISSING_CONTRACT_INPUT` MUST 回到 `ACTIVE(FIELD_EXTRACTION)` 并只允许当前 Task 的 Corpus Tools 与提交 Tool。 |
| `EFX-CONTRACT-007` | Validator MUST 只证明结构、Source Quote 成员关系和 Paper 一致性；MUST NOT 把这些检查描述为语义蕴含验证。 |

## 5. Normalized Run Output

接受的 Submission 规范化为：

```json
{
  "outcome": "answered",
  "markdown": "| Field | Result |\n| --- | --- |\n...",
  "fields": {},
  "cited_source_quote_refs": ["source_quote_abc"],
  "answer_contract": "FIELD_EXTRACTION",
  "field_extraction": {
    "schema_version": "paper-field-extraction/v1",
    "paper_id": "paper_123",
    "items": []
  }
}
```

Outcome 由 Runtime 确定：

```text
all items FOUND       -> answered
one or more NOT_FOUND -> partial
```

Markdown MUST 由 Runtime 生成，不接受模型提供的 Markdown：

```markdown
| Field | Result |
| --- | --- |
| learning rate | 1e-4 [[source_quote_abc]] |
| batch size | `NOT_FOUND` |
```

字段名和值中的 `|`、CR/LF 和反斜杠 MUST 被安全转义或折叠，且不得改变 `field_extraction` 中的结构化原值。Source Quote refs 按 Item 顺序和每个 Item 内的首次出现顺序去重。

`build_harness_run()` MUST 将 `field_extraction` 同时放入 `research_answer` 与 `final_answer`，并设置：

```text
answer_type = "field_extraction"
answer_contract = "FIELD_EXTRACTION"
diagnostics.finish_reason = "model_submitted_field_extraction"
```

### Normative requirements

| ID | Requirement |
| --- | --- |
| `EFX-OUT-001` | Structured result MUST 保留原始请求字段和值；Markdown 只作为其确定性产品投影。 |
| `EFX-OUT-002` | Existing citation renderer MUST 将 Markdown 中的 Source Quote markers 转换为现有数字引用和 Reference Mappings。 |
| `EFX-OUT-003` | `memory_update.selected_source_quote_refs` MUST 只包含 `FOUND` Items 使用的 refs。 |
| `EFX-OUT-004` | 无 Task 的现有 `DIRECT`、`CATALOG`、`RESEARCH` 输出 Shape 和行为 MUST 保持兼容。 |

## 6. MCP Transport And Java-Owned Authorization

### 6.1 V1 Transport

V1 提供一个独立命令：

```bash
python -m harness_py mcp-serve --host 127.0.0.1 --port 8092
```

Server 使用 MCP SDK 的 Streamable HTTP Transport，固定路径 `/mcp`。默认只监听 loopback。日志 MUST 写入 stderr 或标准日志设施，不得污染 MCP 消息。

远程部署 MAY 通过 HTTPS Reverse Proxy 暴露同一路径，但在第 9 节安全测试通过前 MUST NOT 监听非 loopback 地址。

### 6.2 Principal And Session Binding

每个 MCP HTTP 请求使用：

```http
Authorization: Bearer <PaperLoom access JWT>
```

MCP Adapter MUST 把该 Header 转发到现有：

```text
GET /api/v1/users/me
```

并从成功响应 `data.id` 获得 Session Principal。Python MUST NOT 自行解码 JWT，也不得接受 Tool 参数中的 User ID。401、403、无 `data.id` 或非整数 ID MUST 终止该 MCP 请求。

每个后续 MCP HTTP 请求 MUST 通过 `/api/v1/users/me` 重新验证当前 Bearer Token，并通过 MCP SDK Authorization Context 校验其 Principal 与 Session Owner 相同。缺少 Token、Token 被 Java 现有认证策略拒绝、用户不存在、Principal 变化或 Session Owner 不匹配 MUST 失败；不得继续使用旧身份。Java 接受的新 Token MAY 在 Principal 不变时继续使用同一 Session。

### 6.3 Authenticated Java Tool Facade

MCP Tool Call MUST 使用当前请求的原始 PaperLoom JWT 调用：

```http
POST /api/v1/agent-tools/{tool_name}
Authorization: Bearer <PaperLoom access JWT>
X-PaperLoom-Service-Token: <server-side service token>
Content-Type: application/json
```

Java `AgentToolController` MUST：

1. 通过 Spring Security 验证 JWT，并只允许 `USER`、`ADMIN`；`GUEST` 不属于 V1；
2. 独立验证 `X-PaperLoom-Service-Token`，但不得把它当作用户身份；
3. 从 Java `SecurityContext` 或可信 Request Attribute 获得 `user_id`；
4. 严格白名单五个 V1 Tool Names；
5. 固定 Account Library Scope，不接受 `user_id`、`scope_paper_ids`、Role 或权限绕过参数；
6. 调用现有 `CorpusRetrievalService`，使 Paper Access、Current Reading Model 和 Embedding Quota 继续由 Java 校验；
7. 对 `read_paper_content` 在授权成功后才允许创建幂等 Source Quote；
8. 返回现有 Corpus 结果，由 Python 再执行 `model_facing_payload()`。

MCP Adapter MUST NOT 使用 `/internal/v1/corpus/**`。该内部接口只保留给现有 Java 发起的 Harness 链路。用户 JWT 和 Service Token MUST 使用不同 Header；Service Token MUST NOT 发送到 `/api/v1/users/me`。

### Normative requirements

| ID | Requirement |
| --- | --- |
| `MCP-AUTH-001` | 每个 MCP HTTP 请求 MUST 使用 Java 验证的 Principal，并与 MCP Session Owner 匹配。 |
| `MCP-AUTH-002` | 每个 Tool Call MUST 把当前用户 JWT 转发到 Java Authenticated Agent Tool Facade；Java MUST 从认证上下文派生 User ID。 |
| `MCP-AUTH-003` | Tool 参数和 Facade Request Body MUST NOT 携带 `user_id`、`scope_paper_ids`、Role、Service Token 或权限绕过标志。 |
| `MCP-AUTH-004` | Java MUST 对每次 Tool Call 重新校验 Role、Paper Access、Current Reading Model 和适用额度；MCP Disclosure 不是产品权限。 |
| `MCP-AUTH-005` | JWT、Cookie、Authorization Header 和 Service Token MUST NOT 进入 Trace、Tool Result 或日志。 |
| `MCP-AUTH-006` | Service Token 单独出现、用户 JWT 单独出现或两者共用同一 Header MUST NOT 通过 Agent Tool Facade。 |
| `MCP-AUTH-007` | MCP Adapter MUST NOT 调用 `/internal/v1/corpus/**`；内部 Token 加 Body User ID 不属于 MCP V1 身份链。 |

## 7. MCP Session State

初始化成功后，Server 为一个 MCP Session 创建：

```text
McpSessionState = (
  principal_id,
  session_id,
  corpus_tools
)
```

`AuthenticatedAgentToolReader` 在每次 Tool Call 中读取当前 MCP 请求的 Bearer Credential，并与服务端 Service Token 一起调用 Java Agent Tool Facade。Session State MUST NOT 保存 JWT；Credential 不得进入 Eval、Trace、Tool Result 或日志。

Reader 固定使用：

```text
conversation_id = "mcp:" + session_id
scope_paper_ids = []
```

空 Scope 表示 Account Library，但只由 Java 根据认证 Principal 计算；客户端不能扩大它。`ReadingCorpusTools` 继续维护：

```text
authorized_paper_ids
disclosed_location_refs
observations_by_evidence_id
```

Session 关闭、空闲超时或 Server 重启时状态销毁。Server MUST 使用 MCP SDK `session_idle_timeout` 设置有界空闲超时。Source Quote 数据仍由 Java 持久化，但重新连接后的 Agent MUST 重新执行 Paper/Location Disclosure 才能继续 Primitive Tool 链。

### Normative requirements

| ID | Requirement |
| --- | --- |
| `MCP-STATE-001` | 不同 User 或不同 MCP Session MUST 使用不同 `ReadingCorpusTools` 实例。 |
| `MCP-STATE-002` | Primitive Tool 顺序 MUST 保留现有 Paper Disclosure -> Location Disclosure -> Exact Read 梯级。 |
| `MCP-STATE-003` | MCP Session MUST NOT 持久化 Agent Scratchpad、Chain of Thought 或跨客户端 Memory。 |
| `MCP-STATE-004` | Session State 丢失 MUST fail closed；不得因为重连而放宽 Exact Read 前置条件。 |
| `MCP-STATE-005` | 不同 Principal 复用同一 MCP Session ID MUST 被拒绝，且不得触发 Java Tool Call。 |
| `MCP-STATE-006` | Session MUST 有空闲超时；销毁时 MUST 删除内存中的 Disclosure State。 |

## 8. MCP Tools

### 8.1 Primitive Reading Tools

V1 MUST 暴露以下现有 Tool Names 和 Schemas：

```text
search_paper_candidates
find_papers_by_identity
get_paper_structure
search_paper_content
read_paper_content
```

调用实现：

```text
validate MCP arguments against shared schema
-> session.corpus_tools.call(name, arguments)
-> authenticated_tool_reader calls Java /api/v1/agent-tools/{name}
-> Java derives user_id and enforces role/access/quota
-> model_facing_payload(result.payload)
-> MCP text content + equivalent structuredContent
```

`get_citation_edges` 不属于 V1，因为当前 Live Product Corpus 没有稳定 Citation Edge Interface。它只有在真实数据面、授权测试和非空产品用例均存在后 MAY 加入。

### 8.2 V1 Tool Boundary

V1 不暴露 `research_papers` 或 `extract_paper_fields`。Field Extraction 仍由内部 Harness
`FIELD_EXTRACTION` Task 提供；外部 MCP Agent 只能自行编排上述 Primitive Tools。内部
`submit_field_extraction` 不是公开 MCP Tool。

### Normative requirements

| ID | Requirement |
| --- | --- |
| `MCP-TOOL-001` | `tools/list` MUST 只列出 5 个 Primitive Tools。 |
| `MCP-TOOL-002` | Tool Result MUST 同时返回紧凑 Text `content` 和等价 `structuredContent`；两者 MUST 使用有界的 `model_facing_payload()` 投影并保留 Source Quote refs。 |
| `MCP-TOOL-003` | `research_papers`、`extract_paper_fields`、`submit_*`、`get_research_skill`、`_continue_research_turn` MUST NOT 出现在 MCP `tools/list`。 |
| `MCP-TOOL-004` | MCP Initialize Response MUST 只声明 Tools Capability；不得声明 Prompts 或 Resources。 |

## 9. Security, Limits, And Failure Mapping

### 9.1 Limits

| Surface | Limit |
| --- | ---: |
| MCP HTTP request body | 1 MiB |
| Primitive Tool result | existing `max_model_visible_tool_chars` |

### 9.2 Error Mapping

| Internal condition | MCP result |
| --- | --- |
| invalid arguments | Invalid Params with safe field-level reason |
| missing or Java-rejected JWT | Unauthorized; no Corpus call |
| missing/invalid Service Token | Forbidden; no Corpus call |
| disallowed Role | Forbidden; no Corpus call |
| inaccessible or unknown Paper | Not Found without existence disclosure |
| recoverable Corpus preflight error | Structured Tool result with existing `error_code` and `next_action` |
| Corpus unavailable | Tool error `CORPUS_UNAVAILABLE` |
| unexpected error | Tool error `INTERNAL_UNEXPECTED`; stack trace only in server log |

### Normative requirements

| ID | Requirement |
| --- | --- |
| `MCP-SEC-001` | MCP Server MUST default to loopback and MUST reject missing Bearer auth；Java Agent Tool Facade MUST 同时要求用户 JWT 和 Service Token。 |
| `MCP-SEC-002` | Remote deployment MUST use HTTPS; query-string Token MUST NOT be accepted. |
| `MCP-SEC-003` | Tool errors MUST NOT reveal another用户的 Paper existence、内部 URLs、SQL、stack trace、Token 或 Provider Payload。 |
| `MCP-SEC-004` | MCP Adapter MUST enforce request and response size limits before serialization to the client. |
| `MCP-SEC-005` | Java MUST 记录有界审计字段 `principal_id`、`tool_name`、`request_id` 和 outcome；MUST NOT 记录参数正文、论文内容或 Credential。 |
| `MCP-SEC-006` | `read_paper_content` 的 Source Quote 持久化 MUST 在 Java Paper Access 校验之后执行，并保持现有幂等 identity。 |

## 10. Required Code Changes

### 10.1 Python Harness

| File | Required change |
| --- | --- |
| `harness_py/orchestration/runtime.py` | Add `TurnExecutionInput.task` |
| `harness_py/orchestration/live_chat.py` | Accept canonical task, include it in Eval `run.started`, forward to Runtime |
| `harness_py/orchestration/agents/context.py` | Initialize task-specific Protocol state; expose current-Run Facts and successful content-search count |
| `harness_py/orchestration/research_contract.py` | Add Contract, Tool definition, Validator, issue codes, renderer, and allowed-action rules |
| `harness_py/orchestration/agents/tools.py` | Build task-specific Tool catalog; dispatch and normalize Field Extraction Submission; adapt provider-repair guidance |
| `harness_py/orchestration/agents/runtime.py` | Inject Task and `precision_fact_extraction` guidance; keep same Agent/Runner implementation |
| `harness_py/orchestration/run_output.py` | Add deterministic table rendering metadata and structured `field_extraction` output |
| `harness_py/transport/service.py` | Validate/canonicalize task before Corpus/Model work |
| `harness_py/transport/redis_worker.py` | Preserve `task` unchanged through queued requests; no new lifecycle |

### 10.2 MCP Adapter

Add:

```text
harness_py/transport/mcp_server.py
harness_py/tests/test_mcp_server.py
```

Modify:

| File | Required change |
| --- | --- |
| `harness_py/cli.py` | Add `mcp-serve --host --port` |
| `harness_py/requirements.in` | Add explicit `mcp==1.28.1`, matching the current lock |
| `harness_py/requirements.lock` | Regenerate from `requirements.in`; do not hand-edit transitive versions |
| `harness_py/corpus/gateway.py` | Add `AuthenticatedAgentToolReader`; forward user JWT and separate Service Token to `/api/v1/agent-tools/{tool_name}` without using internal Corpus routes |

### 10.3 Java Authenticated Tool Facade

| File | Required change |
| --- | --- |
| `AgentToolController.java` | Add the JWT-authenticated, Service-Token-protected five-Tool whitelist; derive User ID from Java authentication and delegate to existing services |
| `SecurityConfig.java` | Require `USER` or `ADMIN` for `/api/v1/agent-tools/**`; reject `GUEST` |
| `InternalCorpusController.java` | Reuse response projection code where practical; do not expose or weaken the existing internal route |

No new retrieval service, Java Conversation, ProductTurnRequest, frontend, model entity, migration, Qdrant indexing or MinIO change is required. Existing `/internal/v1/corpus/**` behavior remains unchanged and is not used by MCP.

## 11. Verification And Traceability

### 11.1 Deterministic Harness Tests

| Test | Requirements |
| --- | --- |
| canonical valid request | `EFX-REQ-001` to `007` |
| empty/duplicate/over-limit fields rejected before Run | `EFX-REQ-003`, `004`, `006` |
| scope larger/different than target rejected | `EFX-REQ-005` |
| exact requested field coverage and request-order output | `EFX-CONTRACT-001` |
| FOUND value/ref/current-Run/target-Paper checks | `EFX-CONTRACT-002` |
| NOT_FOUND null/empty checks | `EFX-CONTRACT-003`, `004` |
| search-required transition returns ACTIVE | `EFX-CONTRACT-004`, `006` |
| submission sibling Tool rejected with no side effect | `EFX-CONTRACT-005` |
| table escaping and structured-value preservation | `EFX-OUT-001`, `002` |
| no-task regression across existing protocol/runtime suites | `EFX-OUT-004` |

### 11.2 MCP Protocol Tests

Use the MCP client library against the in-process/loopback Server, not direct handler calls.

| Test | Requirements |
| --- | --- |
| initialize advertises Tools only | `MCP-SCOPE-001`, `003`, `MCP-TOOL-004` |
| exact `tools/list` names and schemas | `ARCH-001`, `MCP-TOOL-001`, `003` |
| Tool Result has equivalent text and structured content | `MCP-TOOL-002` |
| primitive disclosure chain | `MCP-STATE-001` to `004` |
| missing or Java-rejected JWT | `MCP-AUTH-001`, `MCP-SEC-001` |
| user B cannot reuse user A Session ID | `MCP-STATE-005` |
| Session idle expiry clears disclosure state | `MCP-STATE-006` |
| outgoing calls use Agent Tool Facade, never internal Corpus path | `ARCH-004`, `MCP-AUTH-007` |
| outgoing body contains no identity or scope override | `MCP-AUTH-003` |
| oversized request/result rejected | `MCP-SEC-004` |

### 11.3 Java Authorization Tests

- Valid `USER` and `ADMIN` JWT plus Service Token can call only the five whitelisted Tools.
- `GUEST`, missing or Java-rejected JWT, missing Service Token, and Service-Token-only calls fail before Corpus work.
- Request body `user_id`, `scope_paper_ids`, Role, or unknown Tool name is rejected.
- User B cannot search, structure-read, content-search, or exact-read user A's private Paper.
- Published Paper access follows the existing `PaperAccessService` policy.
- A JWT rejected by Java's current authentication/revocation policy fails on the next Tool Call even when an MCP Session still exists.
- Embedding quota failures remain attributed to the Java-authenticated Principal.
- `read_paper_content` creates Source Quotes only after access validation and remains idempotent.
- Audit logs contain bounded identifiers and no JWT, Service Token, arguments, or paper content.

### 11.4 Quality Gate

Golden data MUST contain at least:

- 20 papers;
- 5-10 labelled fields per paper;
- values in prose, lists, tables and appendices;
- true missing/ambiguous fields;
- manually labelled Source Quote or accepted evidence location.

Report raw samples plus:

```text
field value exact/normalized accuracy
FOUND precision and recall
NOT_FOUND precision
citation accuracy
citation location success rate
evidence reading coverage
TTFT P50/P95
complete latency P50/P95
```

No fixed quality threshold is invented by this Spec. Product promotion MUST record the measured baseline and an explicit owner decision; deterministic, security and isolation tests MUST all pass.

## 12. Delivery Sequence

### Phase 1: Field Extraction Contract

1. Add request canonicalization and `TurnExecutionInput.task`.
2. Add Contract, Tool, Validator, renderer and protocol replay facts.
3. Add deterministic tests and no-task regressions.
4. Run the field-extraction Golden evaluation.

### Phase 2: Java-Authorized MCP Tools

1. Add the Java Authenticated Agent Tool Facade and authorization tests.
2. Add authenticated MCP Transport, owner-bound Session State, and idle expiry.
3. Adapt the five existing Corpus Tools through `AuthenticatedAgentToolReader`.
4. Verify Tool Schema, dual-credential isolation, Paper Access, quota, and audit behavior.

### Phase 3: Promotion

1. Run full Python and Java regression suites and capture MCP protocol evidence.
2. Keep loopback-only if auth/isolation evidence is incomplete.
3. Update architecture docs and MCP client configuration examples.
4. Frontend work remains outside this Spec.

## 13. Definition Of Done

Implementation is complete only when:

1. A valid single-Paper extraction returns every requested field exactly once.
2. Every `FOUND` value binds only to current-Run Source Quotes from the target Paper.
3. Every cited value reopens through the existing PaperLoom evidence chain.
4. `NOT_FOUND` remains a bounded Run outcome.
5. Existing no-task Chat/Catalog/Research behavior passes regression tests.
6. MCP advertises only Tools and lists exactly 5 existing Tools.
7. External Agents can orchestrate Primitive Tools for research and field extraction.
8. Every Tool Call reaches Java with the current user JWT and separate Service Token; Java derives User ID and enforces Role, Paper Access, Current Reading Model, quota, and audit policy.
9. MCP never uses `/internal/v1/corpus/**`, and cross-user Paper reads fail closed.
10. Session owner mismatch, Java-rejected JWT, disallowed Role, or invalid Service Token stops before Corpus work.
11. No Prompt, Resource, new public MCP Tool, database table, second Runtime, frontend page or general workflow layer has been added.
