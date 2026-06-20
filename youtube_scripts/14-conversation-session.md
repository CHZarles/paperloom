# 第 14 集：对话会话与历史——RAG 系统的记忆

> 系列：派聪明 RAG 后端演进全解
> 集数：14 / 20+
> 主题：Conversation vs ConversationSession、消息存储、滑动窗口上下文、重连续传
> 上集回顾：第 13 集限流配额到位
> 本集目标：用户的对话有记忆、可回溯、可恢复

---

## 【开场 Hook】

用户问 RAG 系统：

> 1. 「派聪明怎么部署？」
> 2. 「能详细说说吗？」
> 3. 「Docker Compose 文件在哪？」

**RAG 系统必须「记得」之前的对话**——**否则第 3 问就答不了**（**前文没提"派聪明"**）。

但**记忆不能无限保留**：
- LLM 上下文长度有限（**32K tokens ≈ 6 万中文字**）。
- 历史太长 → **token 浪费**。
- 历史太短 → **上下文丢失**。

`pai-smart` 怎么平衡？

---

## 【一、两个核心实体】

`pai-smart` 用了**两个实体**：

- **`Conversation`**：一条**消息**（**用户问的 / AI 答的**）。
- **`ConversationSession`**：一个**会话**（**多条消息的容器**）。

**关系**：
```
User 1 ──* ConversationSession 1 ──* Conversation N
                                         ↓
                                  user / assistant / system
```

**为什么拆开？**

- **`Session` 生命周期长**——**用户主动结束**才结束。
- **`Message` 数量多**——**一个 session 可能上千条**。

**分开存**：session 元数据少，**频繁查**；message 量大，**按需查**。

**面试考点 1**：为什么不用 JSON 字段存整个 session？

A：
- **查询能力**：JSON 字段**不能 LIKE**——历史检索差。
- **分页**：分页要 JS 解析。
- **多端同步**：每条消息独立记录——**多端更好同步**。

---

## 【二、`Conversation` 实体（推测）】

```java
@Entity
@Table(name = "conversations")
public class Conversation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;
    
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;  // user / assistant / system
    
    @Lob
    @Column(nullable = false)
    private String content;
    
    @Column(name = "referenced_chunk_ids", length = 1000)
    private String referencedChunkIds;  // 引用的 chunk id（逗号分隔）
    
    @Column(name = "referenced_file_md5s", length = 1000)
    private String referencedFileMd5s;  // 引用的文件
    
    @Column(name = "token_count")
    private Integer tokenCount;  // 本条消息的 token 数
    
    @Column(name = "generation_id", length = 64)
    private String generationId;  // 对应 ChatGenerationStateService
    
    @CreationTimestamp
    private LocalDateTime createdAt;
    
    public enum Role { USER, ASSISTANT, SYSTEM }
}
```

**关键字段**：

1. **`sessionId`**：归属哪个会话。
2. **`role`**：user / assistant / system——**LLM 用的消息格式**。
3. **`content`**：消息内容。
4. **`referencedChunkIds`**：AI 回答**引用了哪些 chunk**——**第 19 集消息反馈要用**。
5. **`tokenCount`**：记账——**配额扣减**。
6. **`generationId`**：关联到 `ChatGenerationStateService.GenerationSnapshot`——**状态追踪**。

**面试考点 2**：为什么 `content` 用 `@Lob`？

A：`@Lob` = Large Object。**TEXT 字段**——**MySQL 存长文本**。**普通 VARCHAR 长度限制 65535**——**LLM 回答可能超过**。

**`@Lob` 缺点**：**查询慢**（**TEXT 不在索引里**）。**生产环境**考虑 **MySQL FULLTEXT 索引**或 **ES 索引**。

---

## 【三、`ConversationSession` 实体（推测）】

```java
@Entity
@Table(name = "conversation_sessions")
public class ConversationSession {
    @Id
    @Column(name = "session_id", length = 64)
    private String sessionId;
    
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;
    
    @Column(name = "title", length = 255)
    private String title;  // 会话标题（首条消息摘要）
    
    @Column(name = "message_count")
    private Integer messageCount = 0;  // 消息数（denormalized）
    
    @Column(name = "total_tokens")
    private Long totalTokens = 0L;  // 累计 token
    
    @Column(name = "last_active_at")
    private LocalDateTime lastActiveAt;
    
    @CreationTimestamp
    private LocalDateTime createdAt;
    
    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
```

**为什么有 `messageCount` 和 `totalTokens`？**

**反范式设计**——**避免每次列表都 COUNT(*) conversations**。

**代价**：
- 每次插入消息**要 UPDATE 会话表**——**多一次写**。

**收益**：
- 列表查询**极快**——**直接读会话表**。

**面试考点 3**：反范式的代价与收益？

A：
- **收益**：查询快，**避免 JOIN**。
- **代价**：写入多一次，**要保证一致性**（**事务 / 乐观锁**）。
- **原则**：**读多写少**才反范式。

`pai-smart` 选反范式——**会话列表查询频繁**。

---

## 【四、`ConversationService.ensureConversationSession`】

```java
public void ensureConversationSession(Long userId, String conversationId, String firstMessage) {
    // 1. 查会话存不存在
    Optional<ConversationSession> existing = conversationSessionRepository.findById(conversationId);
    
    if (existing.isEmpty()) {
        // 2. 不存在 → 创建
        ConversationSession session = new ConversationSession();
        session.setSessionId(conversationId);
        session.setUserId(userId.toString());
        session.setTitle(summarize(firstMessage));  // 用首条消息做标题
        session.setMessageCount(0);
        session.setTotalTokens(0L);
        conversationSessionRepository.save(session);
    }
    
    // 3. 存在 → 啥也不做
}
```

**`summarize(firstMessage)`**：从首条消息提取标题——**取前 30 字符**或调 LLM 生成。

**简单做法**：

```java
private String summarize(String message) {
    String trimmed = message.trim();
    if (trimmed.length() > 30) {
        return trimmed.substring(0, 30) + "...";
    }
    return trimmed;
}
```

**进阶做法**：用 LLM 生成"标题"——**「用户询问派聪明部署方式」**。

`pai-smart` **前者**——**简单**。

**面试考点 4**：sessionId 怎么生成？

A：
- **UUID**：`UUID.randomUUID().toString()` ——**最常见**。
- **雪花 ID**：分布式唯一。
- **业务 ID**：用户首次打开聊天的时间戳——**可读但冲突**。

`pai-smart` 推测用 **UUID**——**安全、简单**。

---

## 【五、`getRecentHistory`：滑动窗口的上下文】

```java
public List<Map<String, String>> getRecentHistory(String conversationId, int limit) {
    Pageable pageable = PageRequest.of(0, limit);  // 取最近 limit 条
    List<Conversation> recent = conversationRepository
        .findBySessionIdOrderByCreatedAtDesc(conversationId, pageable);
    
    Collections.reverse(recent);  // 翻转成正序
    
    return recent.stream()
        .map(c -> Map.of("role", c.getRole().name().toLowerCase(), "content", c.getContent()))
        .toList();
}
```

**`PageRequest.of(0, limit)`**：**取最近 limit 条**——**典型分页**。

**`OrderByCreatedAtDesc`** + **`Collections.reverse`**：**倒序取出再翻正**——**保证最新消息在最后**。

**为什么 limit 通常 10？**
- **DeepSeek context 32K tokens**。
- 10 条对话平均 200 token/条 → **2000 token**。
- 留出余量给当前问题和 RAG context。

**面试考点 5**：limit 选多少合理？

A：
- **小 LLM**（4K context）：**4-6 条**。
- **中 LLM**（16K context）：**8-12 条**。
- **大 LLM**（128K context）：**20-30 条**。

`pai-smart` 10 条——**适配 DeepSeek**。

---

## 【六、`ChatHandler` 里写消息】

```java
public void processMessage(String userId, String userMessage, WebSocketSession session) {
    // 1. 限流
    rateLimitService.checkChatByUser(userId);
    
    // 2. 获取或创建会话
    String conversationId = getOrCreateConversationId(userId);
    conversationService.ensureConversationSession(Long.parseLong(userId), conversationId, userMessage);
    
    // 3. 创建 generation
    ChatGenerationStateService.GenerationSnapshot generation = 
        chatGenerationStateService.createGeneration(userId, conversationId, userMessage);
    
    // 4. 写 user 消息
    Conversation userMsg = new Conversation();
    userMsg.setSessionId(conversationId);
    userMsg.setUserId(userId);
    userMsg.setRole(Conversation.Role.USER);
    userMsg.setContent(userMessage);
    userMsg.setGenerationId(generation.generationId());
    conversationRepository.save(userMsg);
    
    // 5. ... ReAct 循环
    
    // 6. AI 回答完后写 assistant 消息
    Conversation aiMsg = new Conversation();
    aiMsg.setSessionId(conversationId);
    aiMsg.setUserId(userId);
    aiMsg.setRole(Conversation.Role.ASSISTANT);
    aiMsg.setContent(aiResponse);
    aiMsg.setReferencedChunkIds(referencedChunks);
    aiMsg.setReferencedFileMd5s(referencedFiles);
    aiMsg.setTokenCount(actualTokens);
    conversationRepository.save(aiMsg);
    
    // 7. 更新 session 计数
    conversationService.incrementMessageCount(conversationId, 2);  // user + assistant
}
```

**写消息的关键点**：
1. **user 和 assistant 都存**——**双向记忆**。
2. **`referencedChunkIds` 存引用**——**第 19 集反馈**。
3. **`tokenCount` 记账**——**配额结算**。
4. **`messageCount` 反范式更新**——**会话列表快**。

**事务边界**：**user 消息 + assistant 消息 + session 更新 在一个事务**——**失败回滚**。

---

## 【七、`getOrCreateConversationId`：会话怎么开？】

```java
private String getOrCreateConversationId(String userId) {
    WebSocketSession session = ...;  // 当前 session
    
    // 1. 先从 session attribute 取
    String convId = (String) session.getAttributes().get("conversationId");
    if (convId != null) return convId;
    
    // 2. 没有 → 用 WebSocket session id 当 conversationId
    String newConvId = session.getId();
    session.getAttributes().put("conversationId", newConvId);
    return newConvId;
}
```

**`pai-smart` 的策略**：
- **一个 WebSocket 连接 = 一个会话**。
- **断开重连 = 新会话**。

**这设计简单，但用户体验略差**：
- 长时间聊天中网络抖动 → **重连后新会话** → **历史对不上**。

**更好的设计**：
- 客户端**每条消息带 `conversationId`**。
- **断开重连用旧的 conversationId 续**。

`pai-smart` 后期应该会改成这样。

**面试考点 6**：会话和连接的关系？

A：
- **1 对 1**（`pai-smart` 早期）：简单，**断连即结束**。
- **多对 1**（**用户连续对话**）：**重连保留**会话。
- **多对多**：**多个 WebSocket 共享一个会话**（**多端协作**）。

---

## 【八、`ConversationController`：查询历史**

```java
@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

    @GetMapping("/sessions")
    public Page<ConversationSession> listSessions(
            @RequestAttribute("userId") String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return conversationService.listSessionsByUser(userId, page, size);
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public Page<Conversation> listMessages(
            @RequestAttribute("userId") String userId,
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        // 1. 鉴权：只能查自己的
        ConversationSession session = conversationSessionRepository.findById(sessionId)
            .orElseThrow(() -> new CustomException("会话不存在"));
        if (!session.getUserId().equals(userId)) {
            throw new CustomException("无权访问");
        }
        // 2. 分页查
        return conversationService.listMessagesBySession(sessionId, page, size);
    }
}
```

**两个 API**：
- **`/sessions`**：会话列表。
- **`/sessions/{id}/messages`**：某会话的消息。

**鉴权**：**只能查自己**——**`session.getUserId() == userId`**。

**面试考点 7**：分页查询的常见 bug？

A：
- **`OFFSET` 大 → 慢**（**MySQL 要扫到 OFFSET 位置**）。
- **分页期间数据变化**（**重复 or 漏数据**）。
- **解决方案**：**keyset pagination**（**上一页最后一条的 ID 作为下一页的 WHERE**）。

---

## 【九、`ChatSessionRegistry`：WebSocket 注册表**

回顾第 10 集讲过：

```java
public class ChatSessionRegistry {
    // userId -> clientId -> WebSocketSession
    private final Map<String, Map<String, WebSocketSession>> sessions = new ConcurrentHashMap<>();
    
    public void registerSession(String userId, String clientId, WebSocketSession session);
    public void unregisterSession(WebSocketSession session);
    public WebSocketSession getActiveSession(String userId, String clientId);
    public Map<String, WebSocketSession> getSessions(String userId);  // 所有端
}
```

**`ChatSessionRegistry` 是 WebSocket 层的**——**管连接**。
**`ConversationService` 是业务层的**——**管数据**。

**两套东西分开**：
- `ChatSessionRegistry` 内存，**不持久化**。
- `Conversation` 持久化到 DB。

**连接断了 → `ChatSessionRegistry` 删条目；DB 里的会话还在**。

**面试考点 8**：会话和连接分开管理的好处？

A：
- **会话是业务**——**必须持久化**。
- **连接是协议**——**断了就清**。
- **两者独立**——**业务可测**。

---

## 【十、`lastActiveAt`：会话排序**

```java
public Page<ConversationSession> listSessionsByUser(String userId, int page, int size) {
    Pageable pageable = PageRequest.of(page, size, 
        Sort.by(Sort.Direction.DESC, "lastActiveAt"));
    return conversationSessionRepository.findByUserId(userId, pageable);
}
```

**按 `lastActiveAt DESC`**——**最近活跃的在前**。

**`lastActiveAt` 怎么更新**？

每次写消息 → 同时 UPDATE `conversation_sessions.lastActive_at = NOW()`。

**为什么单独字段？**
- `updatedAt` 是 Hibernate 自动维护的——**粒度到任意字段变更**。
- `lastActiveAt` 是**业务事件**（**用户问了问题**）触发的——**更准确**。

---

## 【十一、上下文注入：`buildMessagesWithHistory`】

```java
public List<Map<String, String>> buildMessagesWithHistory(String conversationId, String userMessage, String ragContext) {
    // 1. 拿最近 10 条
    List<Map<String, String>> history = getRecentHistory(conversationId, 10);
    
    // 2. 构造 messages
    List<Map<String, String>> messages = new ArrayList<>();
    messages.add(Map.of("role", "system", "content", "你是派聪明助手。基于以下信息回答：\n" + ragContext));
    messages.addAll(history);
    messages.add(Map.of("role", "user", "content", userMessage));
    
    return messages;
}
```

**关键顺序**：
1. **`system` 角色**：注入 RAG context。
2. **历史消息**：10 条 user/assistant 交替。
3. **当前 user 消息**。

**LLM 看到的完整结构**：
```
[system] 你是派聪明助手。基于以下信息回答：
[context 内容]

[user] 派聪明怎么部署？
[assistant] 可以用 Docker Compose...
[user] docker-compose 文件在哪？
[assistant] 在 docs/docker-compose.yaml
[user] 怎么启动？
```

**LLM 基于完整上下文回答**——**多轮对话能力**。

**面试考点 9**：LLM 上下文顺序重要吗？

A：**重要**。**最新消息放最后**——**Transformer 的 attention 偏向末尾**（**"recency bias"**）。

`pai-smart` 顺序：**system → 历史 → 当前 user** ——**正确**。

---

## 【十二、常见坑 & 面试问答】

**Q1：消息量太大怎么清理？**

A：
- **业务规则**：30 天前的消息归档。
- **定时任务**：`@Scheduled` 每天跑——**删旧消息**。
- **分区表**：按月分区——**DROP PARTITION 比 DELETE 快**。

`pai-smart` **没看到归档**——**TODO**。

**Q2：消息加密吗？**

A：通常**不加密**（**性能**）。**敏感场景**（**医疗、金融**）用**字段级加密**（**AES-256**）。

**Q3：多人协作会话怎么做？**

A：加 `ConversationSession.participantUserIds: String`（**逗号分隔**）——**所有参与者都能访问**。

**Q4：长对话 token 太多怎么办？**

A：**摘要压缩**。**定期把前 N 条对话总结成几句话**——**代替原文**。

`pai-smart` **没做**——**未来优化**。

**Q5：会话 ID 用 UUID 还是数字 ID？**

A：
- **UUID**：**分布式友好**，**不可猜**——**防 IDOR 攻击**。
- **数字 ID**：**可枚举**——**有 IDOR 风险**。

`pai-smart` 用 UUID 风格——**正确**。

---

## 【十三、思考题：为什么 `Conversation` 和 `ConversationSession` 不合并？**

**合并方案**：
- `Conversation` 里加 `title`、`lastActiveAt`、`messageCount` 字段。
- 不用单独建表。

**为什么不合并？**

A：
- **查询效率**：会话列表**不需要消息内容**——**分表后查得快**。
- **更新频率**：消息频繁插入，**会话元数据偶尔更新**——**分开降低锁竞争**。
- **业务清晰**：会话和消息是**两个不同层级**——**领域模型更清晰**。

**YAGNI 反例**：**不要为了"少一张表"牺牲可读性**。

---

## 【十四、下集预告】

会话能保存了。**但！现在的 RAG 是"一问一答"**——**LLM 只能用 RAG 召回的 context**。

**如果用户问"派聪明和 LangChain 哪个好？"**——**派聪明内部文档没有 LangChain 信息**——**LLM 只能瞎答**。

第 15 集我们要：

- **`AgentToolRegistry`** 登场——**给 LLM 装"工具"**。
- **ReAct 循环**：**Reason（思考）→ Act（调工具）→ Observe（观察结果）→ Reason……**
- **`search` 工具**、**`get_current_time` 工具**、**`generate_summary` 工具**。
- **多轮工具调用**怎么控制成本。

**这是 RAG → Agent 的进化**。

我们下期见。

---

> 对话历史是 RAG 系统的"**记忆**"——**没有它就是金鱼**。
> 觉得有用，**点赞、投币、收藏**。

下一集链接：[第 15 集：ReAct 与 Agent 工具——LLM 装上手和脚](./15-react-agent.md)
