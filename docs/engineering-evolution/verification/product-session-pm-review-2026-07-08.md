# PaperLoom 产品提案：面向科研小白的论文阅读流程

日期：2026-07-08

## 决策

不要把当前的 Product Reading 聊天体验当作可发布的科研论文产品。

保留现有的解析、检索、大纲、Source Quote 和卡片能力，但要围绕“小白能读懂的回答契约”和“可持久化的证据 artifact”重建用户可见流程。

产品不应该把用户的阅读路径展示成一串工具输出。它应该引导用户从目标，到选论文，到理解 claim，到查看证据，再到下一步行动。

## 产品标准

已阅读参考文章：`an internal evaluation note`。

这篇文章的核心标准很简单：如果一个 AI 产物很难验证，那就是产品问题。好的 AI 产品会暴露 provenance、较小的可审阅单元、渐进披露，以及明确的不确定性。

对 PaperLoom 来说，每个科研回答都必须帮助初学者回答五个问题：

1. 系统把我的目标理解成了什么？
2. 我应该先看哪篇论文、哪个章节、哪个 claim？
3. 这为什么和我的目标相关？
4. 我能检查哪一条具体证据？
5. 哪些内容还没有被验证？

当前产品经常达不到这个标准。它先暴露句柄、工具名、Source Quote 标记、parser 术语和原始列表，然后才勉强给用户一个阅读路径。

## 目标用户

目标用户是科研论文阅读小白。

他们不是来要数据库 dump 的。他们真正的问题是：“我很迷茫。该先读哪篇？这篇讲了什么？我能相信这个回答吗？”

他们需要一个耐心的阅读助手，不是一个带聊天框的诊断控制台。

## 当前证据

评审时的实时数据库状态：

- 30 个 conversation session。
- 179 条已持久化 conversation record。
- 1 个空 session。
- 32 条 reading-model 记录。
- 30 篇当前 `READING_MODEL_READY` 论文。
- 30 个 assistant turn 带已持久化 reference mapping。
- 30 个 mapped turn 都包含 source quote ref。
- 30 个 mapped turn 都保存了 `paperId = null`。
- 没有 mapped turn 保存 `paperHandle`。
- 没有 mapped turn 具备 PDF/page visual availability。

179 条已持久化回答里的用户可见泄漏：

- 55 条回答包含 `paper_handle_*`。
- 22 条回答包含原始 location/page/section ref。
- 75 条回答提到 Source Quote 机制。
- 55 条回答包含类似 “Found readable...” 的 fallback 文案。
- 49 条回答暴露工具名。
- 14 条回答暴露 scope 内部概念。

这些证据已经足够做产品决策。问题不是个别文案差，而是系统模型正在泄漏到用户的阅读体验里。

## 提案

把 Product Reading 做成一个有引导的科研阅读流程，并提供四个可持久化界面：

1. 目标卡片。
2. 论文短名单。
3. 阅读计划。
4. claim-evidence 面板。

聊天回答应该短、自然、像人话。详细的机器状态应该是结构化、可持久化、可检查的，藏在回答背后的可展开界面里。

### 必须满足的回答契约

每个科研回答都应该遵循这个形状：

```text
我把你的目标理解为：<用自然语言复述目标>。

简短回答：<一到两句真正有用的话>。

先看这里：<论文或章节>，因为 <理由>。

如何验证：<用户可以检查的 quote/page/section/card>。

还没验证的部分：<只是 metadata 匹配、还没读取正文、parser warning、缺少页面视觉证据等>。

下一步：<一个具体动作>。
```

主回答里不应该出现 `paperHandle`、`locationRef`、`sourceQuoteRef`、工具名、parser enum 或 scope enum。

这些标识符可以存在于隐藏 UI payload、debug trace 和后端 artifact 里，但不能让用户背它们。

### 必须提供的 UI Artifact

`GoalCard`

- 展示系统理解到的研究目标。
- 展示范围：“All readable papers, 30 papers, locked”。
- 允许用户在深入阅读前修正目标。

`PaperShortlist`

- 展示 3 到 5 篇论文，不要展示 20 篇。
- 按初学者用途分组：“先读这篇”“benchmark”“方法细节”“背景”。
- 展示每篇为什么匹配。
- 标注这个匹配是 metadata-only，还是已经有正文证据支持。

`ReadingPlan`

- 给出跨一到三篇论文的有序阅读路径。
- 每一步说明用户会学到什么。
- 每一步都能打开具体的大纲章节、页面或 quote。

`ClaimEvidencePanel`

- 展示 claim、quote、页码/章节、不确定性，以及这条 quote 不能证明什么。
- 用户可以检查源内容，而不需要重新跑完整搜索。

## 示例改写

当前 session 120 的回答风格：

- 列出 20 篇 agent-eval 候选论文。
- 打印很多 `paper_handle_*`。
- 没有说应该先读哪一篇。
- 没有区分标题/metadata 匹配和 quote-backed evidence。

建议的 session 120 回答：

```text
我把你的目标理解为：找适合入门的 AI agent 评测论文。

先读 "A Survey on Evaluation of LLM-based Agents"。它是地图型论文，可以先帮你理解 agent evaluation 的整体版图，再进入具体 benchmark。

然后读 "AGENTBENCH" 作为具体 benchmark 例子，再读 "AI Agents That Matter" 了解评测陷阱。

如何验证这个短名单：
- 这篇 survey 是根据标题和论文 metadata 选出的。我还没有读取正文。
- AGENTBENCH 被选为 benchmark 候选。下一步打开它的 abstract 来确认范围。
- AI Agents That Matter 被选为 critique/pitfall 论文。下一步打开 introduction。

下一步：打开 survey 的 abstract，我会用 quote-backed claims 来总结它。
```

这个回答没有那么“全”，但更有用。它给了初学者路径，也给了每一步如何验证的方法。

## 重构计划

### P0 - 先让回答值得信任

1. 实现小白友好的回答契约。

验收标准：

- 主回答不包含 raw handle 或工具名。
- 每个推荐回答都包含“先读哪篇”。
- 每个证据回答都说明“这条 quote 证明什么”和“它不能证明什么”。
- 缺失证据必须明确说明。

2. 增加持久化的 `reading_turn_artifacts`。

每个 turn 持久化：

- interpreted goal
- paper shortlist
- selected current paper
- selected locations
- source quote refs
- claim/evidence rows
- uncertainty notes
- UI action payloads

验收标准：

- 刷新或重载 session 后，可以还原同样的卡片和证据面板。
- citation click 在重载后仍然可用。
- WebSocket completion payload 不再是结构化 UI 状态的唯一来源。

3. 把 current reading target 做成一等状态。

把 selected paper、selected location、selected quote anchors 持久化为 session state。

验收标准：

- 用户在看到 paper card 后说“这篇论文”，系统能解析。
- 用户在证据回答后说“解释一下”，系统能解析。
- 用户点击或收到 citation 后说“这个引用”，系统能解析。
- 如果解析有歧义，产品只问一个聚焦的澄清问题。

4. 修复 citation detail resolution。

当 reference mapping 中有 `sourceQuoteRef` 时，通过 `paper_source_quotes` 和 `conversation_source_quotes` 解析。

验收标准：

- 能恢复 `paperId`、标题、页码、location、content kind 和 source quote text。
- 缺失 PDF/page visual 时明确展示，而不是默默保存 false。
- citation click 不依赖有损的 render-time JSON。

5. 强制校验 paper-card identity。

服务端生成的卡片必须携带 canonical paper id、title、filename、handle 和 match reason。发送前在服务端校验。

验收标准：

- session 106 的 title/handle mismatch 不可能再发生。
- `2412.08972.pdf` false-negative 成为回归测试。
- 卡片如果无法校验，就 fail closed，而不是指向错误论文。

### P1 - 让发现流程真正有用

6. 用 typed component 替换 fallback prose。

停止把 “Found readable paper choices”、“Found readable locations”、“Found source-quoted evidence” 当作用户可见回答。

验收标准：

- 论文结果渲染为 `PaperShortlist`。
- location 结果渲染为 `ReadingPlan` 或 `LocationList`。
- 证据结果渲染为 `ClaimEvidencePanel`。

7. 增加入门阅读计划。

对宽泛主题，返回有引导的路径，而不是大列表。

验收标准：

- 主题推荐默认返回 3 到 5 篇论文。
- 每篇论文都有角色：survey、benchmark、method、critique、background 或 example。
- 回答包含一个推荐的下一步动作。

8. 归一化多语言研究意图。

把中文小白意图映射到论文语言里的 retrieval plan。

验收标准：

- “方法”“实验设置”“主要结论”“局限”“数据集”“baseline”“ablation”“metric” 能映射到预期的英文 section/search role。
- query plan 被持久化为 artifact。
- semantic no-match 时记录缺失的 `semantic_location_evidence`，返回 `INCOMPLETE_PRECISE` 或只问一个聚焦澄清问题。
- 大纲只能在 `IntentFrame` 明确要求 outline/section evidence 时被计划使用，不能作为语义检索失败后的替代路线。

9. 清楚展示 workspace scope。

侧边栏和第一条回答应该像研究工作区一样展示范围，而不是展示后端 enum。

验收标准：

- Auto-library session 展示 “All readable papers, 30 papers, locked”。
- Auto-library session 不展示 null source count。
- `AUTO_LIBRARY`、`AUTO_SOURCE` 和 `immutable=true` 不出现在普通回答里。

### P2 - 把当前 session 变成产品 eval

10. 从当前 session 创建回归用例。

用例：

- `2412.08972.pdf` 精确文件名查找。
- session 106 的 handle/title integrity。
- session 107 的 card click 后 “this paper”。
- session 110 和 113 的中文 method/experiment lookup。
- session 97、100、104、109、114 和 118 的 source quote explanation。
- reload-and-click-citation。
- empty session cleanup。
- session 120 的 beginner recommendation flow。

11. 增加 artifact completeness checks。

每个 evidence answer 都必须证明：

- citation marker 存在。
- source quote 存在。
- paper id 可解析。
- page/location 可解析。
- quote text 存在。
- click endpoint 可以重新打开详情。
- 缺失 visual asset 时明确展示。

12. 增加 novice-readability checks。

评分一个回答是否：

- 说清用户目标。
- 避免 raw handle。
- 推荐第一个动作。
- 区分 metadata-only relevance 和 evidence-backed claim。
- 暴露验证路径。
- 说明不确定性。

## Session 发现

下面覆盖全部 30 个 session。表格把重复症状合并，避免这份文档再次变成 transcript inventory，而是保持可执行。

| Sessions | 发现 | 产品含义 |
| --- | --- | --- |
| 91 | 推荐质量弱，且没有可见 citation mapping。 | 推荐需要 reason 和 evidence status。 |
| 92-94 | 导航能工作，但回答仍然像工具输出。 | 保留后端能力，重做呈现层。 |
| 95, 98 | 已存在文件名的精确查找失败。 | 文件名查找需要确定性回归覆盖。 |
| 96-105 | 脚本化阅读流程能走到证据，但太手动。 | 做 guided reading plan，不要让用户自己发工具式 prompt。 |
| 106 | recent-list 输出出现 title/handle mismatch。 | Paper-card identity 必须在服务端校验。 |
| 107, 111, 116 | “这篇论文”“解释”“这个引用”会丢上下文。 | current target 和 clicked anchors 必须是持久化状态。 |
| 108 | 用户猜中论文语言时检索效果更好。 | 必须做 multilingual intent normalization。 |
| 109-114, 118 | 后续脚本化阅读 session 重复出现 evidence、location 和 explanation 问题。 | 修共享流程，不要逐个 prompt 打补丁。 |
| 110, 113 | method/experiment search 失败后不应该用大纲浏览伪装成命中。 | `IntentFrame` 必须生成 typed location query plan；计划无法满足时返回 `INCOMPLETE_PRECISE`。 |
| 115 | 空 session 仍可见。 | 空 session 应该归档或隐藏。 |
| 117, 119 | 精确查找和 location listing 能工作，但输出仍是原始导航。 | 成功 lookup 后应该进入 reading plan。 |
| 120 | 有机的小白入口有进步，但回答仍打印 handles 和 20 个候选。 | 推荐流程需要 beginner ranking 和验证步骤。 |

## 代码根因

- `ProductReadingConversationService` 把 `List.of()` 作为 turn history 传给 reading harness。
- `ChatHandler` 只通过 live completion state 发送 product-state items，没有把它们放进 durable conversation history。
- `ProductReadingReActHarness` 从结构化状态拼出 fallback prose。
- `ConversationService.findReferenceDetail(...)` 只读取 `referenceMappingsJson`，不读取权威 quote 表。
- `ConversationService.appendScopeSummary(...)` 对 auto-library session 返回 null source count。

## 建议的下一步切片

从 P0 第 1 项开始：小白友好的回答契约。

这是把产品从“工具 transcript”变成“阅读助手”的最小切片。它也会为后续所有后端 artifact 提供清晰的验收测试。

不要先做数据库迁移。先定义并测试回答形状，隐藏主文案里的内部标识符，并让 session 120 读起来像一个有引导的推荐流程。

## 退出条件

把 critique 转成这份 proposal 后，我没有新的 distinct product recommendation。下一步应该进入实现，或者根据这份 proposal 编写 eval cases。
