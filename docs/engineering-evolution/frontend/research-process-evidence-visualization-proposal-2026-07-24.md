# Research Process Evidence Visualization Proposal

日期：2026-07-24

## 结论

PaperLoom 不应该承诺“AI 一定不幻觉”。这个承诺不真实，也很难用在线 harness 证明。

更可靠的产品承诺应该是：

```text
回答
-> 它引用或读过的证据
-> 稳定的 locationRef / sourceQuoteRef
-> PDF 原页或页面截图
-> bbox / 表格 / 图片区域
-> 用户可以自己检查原文
```

也就是说，系统要优化的是**可追溯的 research 过程**，不是把 `submit_research_answer`
做成一个“真理裁判”。

`submit_research_answer` 只保留最小结构校验：

- final answer 不能为空；
- Markdown 结构不能明显坏掉；
- 不能手写不可解析的数字引用；
- 不能引用不存在的 evidence id；
- 不能把 final answer 和继续 research 的 tool step 混在一起。

它不应该因为下面这些情况直接拒绝：

- 模型读过某篇论文但最后没引用；
- 某个段落或列表项没逐条引用；
- 模型提到一篇论文但没把它作为引用；
- 某个 claim 是否真的被证据支持需要语义判断。

这些都应该放到 Golden Data / benchmark / 人工复核里做离线回归，而不是挡在线用户路径。

## 为什么

用户真正需要的不是“系统替我保证没错”，而是：

> 我能看到它怎么查的、读了什么、引用了什么，并能跳回 PDF 原文自己判断。

过严的在线规则会制造反效果：

- 如果模型读了错论文，强制“读过就必须引用”只会把错论文包装成可信来源；
- 每段都强制引用会增加 retry、延迟和失败率，但并不能证明语义正确；
- 在线再加一个语义 judge，会引入成本、波动和新的模型幻觉；
- 最后产品会变成“看起来很严格”，但用户反而更难理解 research 过程。

所以边界应该分清：

- **在线 harness**：保证过程和引用结构可检查；
- **Golden Data**：离线衡量答案质量、证据质量、trace 质量；
- **前端**：把 research 过程和 PDF 证据展示出来，让用户能判断。

## 当前基础

代码里已经有不少可复用能力：

- Python harness 会产出 progress events、`react_trace`、`evidence_ledger` 和 final citations；
- Java 会把 Python citation 映射成前端 reference mapping；
- Product ReAct 已经保存 `sourceQuoteRef` 和 reference mappings；
- `EvidenceItem` / `ReferenceEvidence` 已经有 `paperId`、`pageNumber`、`bboxJson`、
  `sourceKind`、截图可用标记、table/figure id、原文片段；
- 前端已经有：
  - `research-process-panel.vue`：展示模型/tool 过程；
  - `source-evidence-panel.vue`：展示证据文本、页面截图、表格/图片截图、PDF 下载和 bbox 画框。

主要缺口不是“从零做 PDF 画框”，而是这些信息还没有被组织成一个统一的
**research audit trail**。

现在用户还不能很顺地回答：

- 模型搜了什么？
- 返回了哪些候选论文？
- 打开了哪些位置？
- 读了哪些段落、表格或图片？
- 最后引用了哪些？
- 读过但没引用哪些？
- 任意一条证据能不能跳到 PDF 原文并画框？

## 非目标

- 不展示 chain-of-thought 或模型私有推理；
- 不把 Golden Data 标注、标准答案、scorer 判断塞进在线产品路径；
- 不让 `submit_research_answer` 做语义正确性裁判；
- 不靠解析 final Markdown 来反推论文身份或证据身份；
- 不为这个功能新增另一套向量库或检索 fallback。

## 建议的产品接口

在 Java 和前端之间新增一个面向展示的 payload：

```text
ResearchAuditTrail
```

它不是 raw trace，而是从现有 progress events、reference mappings、source quotes、
reading artifacts 投影出来的稳定结构。

示例：

```json
{
  "schemaVersion": "research-audit-trail/v1",
  "answer": {
    "status": "COMPLETED",
    "citationRefs": ["[1]", "[2]"]
  },
  "steps": [
    {
      "stepId": "tool-7",
      "kind": "search_paper_candidates",
      "status": "completed",
      "query": "ReAct agent benchmark",
      "paperIds": ["react_2023", "agentbench_2024"],
      "durationMs": 812
    }
  ],
  "evidence": [
    {
      "sourceQuoteRef": "source_quote_...",
      "evidenceRef": "ev_...",
      "citationRef": "[1]",
      "status": "cited",
      "paperId": "paper_...",
      "paperTitle": "REACT: SYNERGIZING REASONING AND ACTING IN LANGUAGE MODELS",
      "locationRef": "section_ref_...",
      "pageNumber": 1,
      "sectionTitle": "Abstract",
      "sourceKind": "TEXT",
      "content": "The source text shown to the user.",
      "bboxJson": "{\"coordinateSystem\":\"top_left_1000\",\"left\":...}",
      "pageScreenshotAvailable": true,
      "tableId": null,
      "figureId": null
    }
  ],
  "diagnostics": {
    "searchedPaperCount": 8,
    "readLocationCount": 12,
    "readEvidenceCount": 9,
    "citedEvidenceCount": 3,
    "uncitedReadEvidenceCount": 6,
    "visualEvidenceAvailableCount": 2
  }
}
```

这个接口只解决一件事：让前端稳定展示 research 过程和证据，不让前端去猜 raw trace。

## 证据状态

每条 evidence row 只需要一个展示状态：

| 状态 | 含义 |
| --- | --- |
| `candidate` | 找到过候选论文或候选位置，但没有读到精确原文。 |
| `read` | 读过精确文本、表格或图片位置，但最后没引用。 |
| `cited` | 最终答案引用了这条证据。 |
| `inspected` | 用户后来点开或复查过这条证据。 |
| `unavailable_visual` | 文本可用，但页面、表格或图片截图不可用。 |

这些状态不是正确性判决，只是 provenance label。

尤其重要的是：模型读错论文时，不应该强制它引用。正确展示应该是：

```text
这篇论文被 read 了，但没有 cited。
```

这样用户能看到模型走过弯路，而不是被迫把弯路包装成最终证据。

## 前端体验

一个 assistant answer 应该有两个稳定区域：

1. **答案区**
   - 正常展示最终回答；
   - citation chip 可以打开对应 evidence row。

2. **Research 区**
   - 展示 search、open/read、submit 等步骤；
   - evidence ledger 按状态分组：Cited、Read but not cited、Candidate only；
   - 每行展示论文、页码、section、原文片段、视觉证据可用性；
   - 点任意 evidence row 打开 `SourceEvidencePanel`。

3. **原文查看器**
   - 有页面截图：打开截图并按 bbox 画框；
   - 有 table/figure 截图：打开裁剪后的视觉资产；
   - 没有截图：展示文本和 PDF 下载入口；
   - 没有 bbox：跳到页码，但明确显示“区域不可用”。

现有 `source-evidence-panel.vue` 已经支持大部分能力。前端主要工作是让
`research-process-panel.vue` 不再只展示静态事件，而是能打开 evidence。

## 后端工作

### 1. 统一证据投影

保证 Python citations 和 Product ReAct source quotes 都能填到同一套前端字段：

- `paperId`
- `paperTitle`
- `originalFilename`
- `sourceQuoteRef` 或 `evidenceRef`
- `locationRef`
- `pageNumber`
- `sectionTitle`
- `sourceKind`
- `content`
- `bboxJson`
- `pageScreenshotAvailable`
- table / figure / formula id 和截图标记

### 2. 新增 `ResearchAuditTrailProjector`

输入：

- accepted answer envelope；
- reference mappings；
- progress events；
- reading artifacts。

输出：

- `research-audit-trail/v1`。

它应该是确定性的、可单测的、面向 UI 的投影层。

### 3. 保留读过但没引用的证据

现在 reference mapping 更偏向最终 cited sources。Audit trail 还需要 read-but-uncited
证据。

做法不是保存所有模型 token，而是保存 compact metadata：

- paper id；
- location ref；
- page / bbox；
- source kind；
- 展示用原文片段；
- 是否最终 cited。

### 4. 视觉资产懒加载

聊天 payload 不传截图二进制。

只传：

- paper id；
- page number；
- bbox；
- table/figure id；
- 可用性标记。

截图和 PDF 继续复用现有接口：

- page screenshot；
- table screenshot；
- figure screenshot；
- original PDF download。

## Python Harness 工作

- `submit_research_answer` 继续保持最小结构校验；
- `read_locations` 返回的 evidence 尽量带上稳定视觉字段：
  - `page`
  - `page_end`
  - `bbox_json`
  - `source_object_id`
  - table / figure / formula id
  - screenshot availability
- `evidence_coverage.py` 作为离线诊断，不作为在线 rejection；
- Golden runs 报 Candidate / Read / Cited 数量，用于回归对比。

## Golden Data 工作

Golden Data 分三层评估：

1. **答案质量**
   - required claims 是否答到；
   - 是否完整；
   - 是否多出 unsupported claims。

2. **证据质量**
   - 引用是否来自正确论文；
   - 引用位置是否能支持 claim；
   - 表格、图片、段落是否对应正确。

3. **Trace 质量**
   - required paper/location 有没有出现在 candidate；
   - 有没有被 read；
   - 有没有最终 cited；
   - 有没有可展示的视觉证据。

建议 benchmark report 增加：

```json
{
  "trace_metrics": {
    "candidate_recall": 0.95,
    "read_recall": 0.71,
    "citation_recall": 0.64,
    "visual_evidence_coverage": 0.42
  }
}
```

这样失败能被拆清楚：

- 是检索没找到；
- 是找到了但没读；
- 是读了但没引用；
- 是引用了但答案没写好；
- 是证据没有 PDF/bbox 可视化。

## 实施计划

### Phase 1：接口和投影

- 新增 `ResearchAuditTrail` Java records；
- 新增 `ResearchAuditTrailProjector`；
- 先把现有 cited references 和 progress events 投影进去；
- 加 contract tests：一条 cited text source、一条带 page bbox 的 source、一条 read-but-uncited source。

退出标准：

- assistant message JSON 有 `researchAuditTrail`；
- 老消息没有该字段时前端照常渲染；
- 单测证明 cited 和 uncited evidence 都能保留下来。

### Phase 2：前端 Audit Pane

- 新增 `useResearchAuditTrail(message)`；
- 改造 `research-process-panel.vue`；
- evidence row 可点击；
- 复用 `SourceEvidencePanel` 打开原文和画框。

退出标准：

- 用户能点击 cited source、read-but-uncited source、candidate location；
- `bboxJson.coordinateSystem = top_left_1000` 时能在 page screenshot 上画框；
- 没有截图时降级成文本 + PDF 下载，不出现坏按钮。

### Phase 3：补齐视觉字段

- 审计 live Qdrant/Python evidence 里为什么缺 `bboxJson`、`pageScreenshotAvailable`、
  table/figure screenshot flags；
- 缺失字段优先从 MySQL hydration 补，不从 Qdrant payload 硬凑；
- 加一个后端测试：真实 `read_locations` response 在 Reading Model 有 page/bbox 时能带出来。

退出标准：

- live product run 至少能产出一条 page screenshot + bbox overlay 的 evidence；
- 缺 bbox 被展示成数据质量状态，不是前端错误。

### Phase 4：Golden Regression

- Golden score report 增加 trace metrics；
- 比较 MiniMax 和 GPT 系 provider：
  - candidate count；
  - read count；
  - cited count；
  - semantic pass/fail；
  - visual evidence coverage。

退出标准：

- benchmark report 能说明失败来源：检索、阅读、引用选择、答案表达，还是视觉资产缺失。

## 验收标准

一个用户打开任意 assistant answer 时，不需要相信隐藏推理，也能回答：

1. 模型搜了什么？
2. 它检查了哪些论文和位置？
3. 它最终引用了哪些精确证据？
4. 它读过但没引用哪些证据？
5. 每条 cited source 能不能打开 PDF 原文视觉证据？
6. 如果没有 bbox 或截图，UI 有没有明确说清楚？

## 测试

Java 单测：

- `ResearchAuditTrailProjectorTest`
- cited / uncited evidence 都能保留；
- 不暴露 raw chain-of-thought；
- source row 有视觉字段时能投影出来。

前端测试：

- audit pane 能渲染 cited / read / candidate 分组；
- 点击 evidence 打开 `SourceEvidencePanel`；
- bbox overlay 随图片尺寸缩放；
- 缺 screenshot 时 fallback 正常。

Golden / CLI 检查：

- saved run artifact 包含 Candidate / Read / Cited 计数；
- score report 包含 trace metrics；
- 不重新给 `submit_research_answer` 加在线语义 rejection。

## 风险

- 保存过多 raw trace 会泄漏 provider debug payload。产品 payload 必须 compact 且面向展示；
- bbox 坐标系可能不一致。前端只画自己认识的坐标系；
- 老 conversation 没有 audit trail。UI 必须 fallback 到现有 `referenceMappings` 和 `researchEvents`。

## 最终判断

这个方向是对的：

- harness 负责让过程结构化、可检查；
- Golden Data 负责离线回归调教质量；
- 前端负责把论文、证据、PDF 页面和画框展示出来；
- 产品不承诺“没有幻觉”，而是承诺“你能看到它为什么这么答，并能自己核查原文”。
