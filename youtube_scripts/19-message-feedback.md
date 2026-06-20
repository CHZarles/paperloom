# 第 19 集：消息反馈与质量闭环——RAG 系统的耳朵

> 系列：派聪明 RAG 后端演进全解
> 集数：19 / 20+
> 主题：点赞/点踩、文字反馈、反馈对未来的 prompt 影响、质量看板
> 上集回顾：第 18 集邀请码拉新
> 本集目标：用户能反馈，AI 越来越懂用户

---

## 【开场 Hook】

ChatGPT 刚出来时，我经常吐槽：

> 「这个回答不对」——但 ChatGPT **没有耳朵**——**继续错下去**。

RAG 系统**必须给用户反馈的入口**——**让系统越用越聪明**。

`pai-smart` 后期加了 **消息反馈（Message Feedback）** 体系：
- **👍 点赞** / **👎 点踩**。
- **文字反馈**——「回答不准确」「答非所问」。
- **反馈影响未来的 prompt**——`buildRecentFeedbackGuidance`。
- **质量看板**——**管理员看哪些文档"差评多"**。

今天拆 `MessageFeedback`、`buildRecentFeedbackGuidance` 的实现。

---

## 【一、`MessageFeedback` 实体（推测）】

```java
@Entity
@Table(name = "message_feedbacks")
public class MessageFeedback {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(name = "conversation_id", length = 64, nullable = false)
    private String conversationId;

    @Column(name = "message_id", length = 64, nullable = false)
    private String messageId;  // 对应 assistant 的某条消息

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Rating rating;  // LIKE / DISLIKE

    @Column(name = "comment", length = 1000)
    private String comment;  // 文字反馈

    @Column(name = "referenced_chunk_ids", length = 1000)
    private String referencedChunkIds;  // AI 引用了哪些 chunk（用于追溯）

    @CreationTimestamp
    private LocalDateTime createdAt;
}
```

**关键字段**：
- **`messageId`**：关联到 `Conversation` 主键。
- **`rating`**：枚举——**LIKE / DISLIKE**。
- **`comment`**：可选文字反馈。
- **`referencedChunkIds`**：**AI 当时引用了哪些文档 chunk**——**回溯"哪个文档答错了"**。

**面试考点 1**：为什么记录 `referencedChunkIds`？

A：
- **追溯责任**：AI 错了——**到底是哪个 chunk 错了**？
- **优化目标**：多次反馈指向同一 chunk → **这个 chunk 有问题**。
- **重索引**：可能需要**重新分块或重新向量化**。

`pai-smart` **有这套**——**专业**。

---

## 【二、`FeedbackController`：用户入口】

```java
@PostMapping("/api/v1/conversations/{sessionId}/messages/{messageId}/feedback")
public ResponseEntity<?> submitFeedback(
        @PathVariable String sessionId,
        @PathVariable String messageId,
        @RequestBody FeedbackRequest request,
        @RequestAttribute("userId") String userId) {
    
    // 1. 鉴权：只能反馈自己的消息
    Conversation msg = conversationRepository.findById(Long.parseLong(messageId))
        .orElseThrow(() -> new CustomException("消息不存在"));
    if (!msg.getUserId().equals(userId)) {
        throw new CustomException("无权反馈");
    }
    
    // 2. 写反馈
    MessageFeedback feedback = new MessageFeedback();
    feedback.setUserId(userId);
    feedback.setSessionId(sessionId);
    feedback.setMessageId(messageId);
    feedback.setRating(request.getRating());
    feedback.setComment(request.getComment());
    feedback.setReferencedChunkIds(msg.getReferencedChunkIds());
    feedbackRepository.save(feedback);
    
    return ResponseEntity.ok(Map.of("message", "反馈已收到"));
}
```

**前端用法**：
- AI 回答下方：「👍 」「👎 」「💬 写反馈」。
- 弹窗写文字 → 提交。

**面试考点 2**：反馈要不要匿名？

A：**不匿名**。
- 匿名反馈**没法回溯用户**。
- 恶意反馈**没法封禁**。
- 业务上**需要追溯**。

`pai-smart` **不匿名**——**正确**。

---

## 【三、`buildRecentFeedbackGuidance`：反馈影响 prompt】

这是 `pai-smart` 最巧妙的设计之一。

**逻辑**：
- 用户 N 次给某类问题点踩 → **下一次同类问题，LLM 被告知"注意"**。

```java
private String buildRecentFeedbackGuidance(String userId) {
    // 1. 查用户最近 5 条 DISLIKE 反馈
    List<MessageFeedback> recent = feedbackRepository
        .findTop5ByUserIdAndRatingOrderByCreatedAtDesc(userId, Rating.DISLIKE);
    
    if (recent.isEmpty()) return null;
    
    // 2. 取每个反馈对应的消息内容
    List<String> badExamples = recent.stream()
        .map(f -> conversationRepository.findById(Long.parseLong(f.getMessageId()))
            .map(Conversation::getContent).orElse(""))
        .filter(s -> !s.isEmpty())
        .limit(3)
        .toList();
    
    if (badExamples.isEmpty()) return null;
    
    // 3. 拼成 guidance
    StringBuilder sb = new StringBuilder("请避免以下回答模式（用户曾表示不满）：\n");
    for (String ex : badExamples) {
        sb.append("- ").append(ex.substring(0, Math.min(100, ex.length()))).append("\n");
    }
    return sb.toString();
}
```

**塞进 LLM system prompt**：

```java
String systemContent = "你是派聪明助手。\n"
    + ragContext + "\n"
    + (feedbackGuidance != null ? "\n" + feedbackGuidance : "");
```

**LLM 看到的 prompt 多了**：
> 请避免以下回答模式（用户曾表示不满）：
> - 派聪明需要 Java 11 才能运行。
> - 派聪明只支持 MySQL，不支持 PostgreSQL。
> - 派聪明是收费软件。

**LLM 会主动避开这些"已知错"**。

**面试考点 3**：这种"软反馈"有什么用？

A：
- **比 fine-tuning 便宜**：不用重新训练模型。
- **即时生效**：用户点了踩，**下次就有用**。
- **个性化**：每个用户自己的"避雷清单"。

`pai-smart` **有这套**——**太聪明了**。

---

## 【四、Agent 工具：`submit_feedback`**

回看第 15 集的 `AgentToolRegistry`：

```java
this.tools = List.of(
    searchKnowledgeTool(),
    generateSummaryTool(),
    submitFeedbackTool(),   // <-- 反馈工具
    knowledgeStatsTool()
);
```

**LLM 在 ReAct 循环中可以调反馈工具**——**AI 自己收集反馈**。

**`submit_feedback` 工具的 schema**（推测）：
```json
{
  "name": "submit_feedback",
  "description": "对当前回答打分。1=非常不满意，5=非常满意",
  "parameters": {
    "score": {"type": "integer", "minimum": 1, "maximum": 5},
    "comment": {"type": "string"}
  }
}
```

**LLM 决定调这个工具**——**意味着"我觉得我答得不好"**。

**这有用吗？**

**有用！**：
- LLM 的"自我评估"是**有用的弱信号**。
- 多个对话都自评低分 → **某类问题普遍答不好**。
- 收集起来做**模型微调数据**。

**也有争议**：
- LLM 经常**过度谦虚**。
- 自评分数**和用户主观感受**不一致。

`pai-smart` **集成这个工具**——**有想法**。

---

## 【五、质量看板：管理员视角】

```java
@GetMapping("/admin/feedback/stats")
public FeedbackStats getStats(@RequestParam(defaultValue = "30") int days) {
    return feedbackService.getStats(days);
}

public record FeedbackStats(
    long totalFeedbacks,
    long likeCount,
    long dislikeCount,
    double likeRate,
    List<ChunkFeedbackRank> worstChunks
) {}

public record ChunkFeedbackRank(
    String chunkId,
    long dislikeCount,
    long likeCount,
    double dislikeRate
) {}
```

**管理员看什么**：

1. **整体满意度**：`likeRate`（**90% 满意 → 系统好** / **60% 满意 → 需优化**）。
2. **差评最多的 chunk**：`worstChunks`——**哪些文档"反复被骂"**。
3. **趋势**：**7 天 / 30 天对比**——**质量在改善还是恶化**。

**`pai-smart` 推测有管理后台**——`AdminController` 里有 `/api/v1/admin/**` 路由。

**面试考点 4**：质量看板的北极星指标是什么？

A：
- **👍 率**：用户主观满意。
- **采纳率**：用户**复制/使用** AI 回答的比例。
- **任务完成率**：用户**"达到目的"**的比例（**需要埋点**）。

`pai-smart` **用 👍 率**——**简单**。

---

## 【六、反馈与重索引的联动】

**`worstChunks` 怎么用？**

**场景**：某个 chunk 被点踩 50 次 → **这个 chunk 可能有问题**。

**管理员操作**：
1. **看 chunk 原文**——**内容是否错误？**
2. **重新分块**——**切太粗？切太细？**
3. **重新向量化**——**模型版本？**
4. **调文档 isPublic**——**不应该公开？**

`pai-smart` 有 `/api/v1/documents/{fileMd5}/reindex`——**手动触发重新向量化**（**第 09 集 Kafka 消费**）。

**面试考点 5**：怎么自动发现"差文档"？

A：
- **规则引擎**：`dislikeCount > 10 AND likeCount < 2` → 自动标记。
- **机器学习**：**用反馈训练 rerank 模型**——**冷启动后**。
- **人工 review**：**前 N 个差评 chunk 每周 review**。

`pai-smart` 当前**人工 review**——**简单**。

---

## 【七、常见坑 & 面试问答】

**Q1：用户恶意点踩怎么办？**

A：
- **同 IP 限频**：**Redis 计数**。
- **同会话限频**：**一个 session 最多点 5 次/小时**。
- **黑名单用户**：**管理员手动 ban**。
- **AI 检测**：**高频点踩 → 验证码**。

`pai-smart` **没看到反作弊**——**未来**。

**Q2：feedback guidance 会不会 prompt 注入？**

A：理论上**会**。
- 用户故意点踩某段"无害"内容 → 反馈被加进 prompt → **LLM 被"训练"避开无害内容**。
- **防护**：**只对正向/负向打标签**，**不进原文**——**只统计特征**。

`pai-smart` **取了原 content 100 字符**——**有 prompt 注入风险**——**未来要改**。

**Q3：反馈数据能用来微调模型吗？**

A：**可以**——但要清洗。
- 👍 的是**正例**。
- 👎 的是**负例**——**但 LLM 输出很难用**——**需要重新生成**。
- **多轮反馈**比单轮**质量高**。

`pai-smart` **收集反馈**——**未来可微调**。

**Q4：要不要 A/B 测试不同 prompt？**

A：**要**。
- **50% 用户**走 prompt A。
- **50% 用户**走 prompt B。
- **对比 👍 率**——**A 胜出**。

`pai-smart` **`weight` 字段支持**（**第 16 集**）——**可以做**。

**Q5：反馈数据隐私怎么办？**

A：
- **用户标识**脱敏：`userId` hash 化。
- **不存原文**：只存特征（**chunk id、score、长度**）。
- **GDPR 合规**：用户**要求删除**——**全部删**。

`pai-smart` **明文存 userId**——**改进空间**。

---

## 【八、思考题：反馈的"信号噪声比"**

**信号**：用户真实感受。
**噪声**：用户情绪、用户耐心、UI 引导。

**怎么提高信噪比？**

A：
- **5 星评分**比"👍/👎"**信噪比高**——**更多信号维度**。
- **必须文字反馈**——**过滤掉"手滑"**。
- **多轮对话的平均分**——**避免单条异常**。
- **"这条回答帮到你了吗？"**——**直接问任务完成**。

`pai-smart` **👍/👎**——**信噪比中等**。

---

## 【九、下集预告】

整套系统**几乎完成了**——但还有很多**生产级细节**没说。

第 20 集（最终集）：

- 异步执行器配置。
- 日志、监控、告警。
- 环境配置切换。
- **"最后一公里"的可观测性**。

我们下期——**也是这一季的最后一期**——见。

---

> 反馈是 RAG 的"**耳朵**"——`pai-smart` 的设计**专业**且**有想法**。
> 觉得有用，**点赞、投币、收藏**。

下一集链接：[第 20 集：可观测性与生产化——最后一公里](./20-observability-production.md)
