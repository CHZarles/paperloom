# 第 10 集：WebSocket 流式聊天——DeepSeek 首次对话

> 系列：派聪明 RAG 后端演进全解
> 集数：10 / 20+
> 主题：WebSocket 握手鉴权、SSE 流式响应、DeepSeek Client、Reactive Streams
> 上集回顾：第 09 集 Kafka 异步化完成，文档处理流水线跑通
> 本集目标：用户能通过 WebSocket 提问，AI 一个 token 一个 token 推回前端

---

## 【开场 Hook】

文档能存了，能搜了，**但 RAG 还差最后一步——回答**。

`pai-smart` 用的是 **DeepSeek**（国产 LLM，**对标 GPT-4 级别**，**API 便宜**）。

**但是，HTTP 请求是"一问一答"**——用户问完得等 DeepSeek 把所有 token 生成完才能看到。**等 5-10 秒才有反应**——**体验糟糕**。

**WebSocket** 解决了这个：
1. 客户端和服务器建立**长连接**。
2. 用户提问 → 服务端调 DeepSeek → **一个 token 一个 token 推回前端**。
3. **用户能看到 AI "打字"的过程**——**类 ChatGPT 体验**。

今天我们拆 `ChatWebSocketHandler` 和 `DeepSeekClient`，看 RAG 系统的"对话能力"怎么实现。

---

## 【一、为什么是 WebSocket 不是 SSE？】

**两种"服务器推送"方案**：

| 维度 | WebSocket | SSE（Server-Sent Events） |
|---|---|---|
| 协议 | `ws://` / `wss://` | `http://` |
| 方向 | **双向** | 单向（服务器→客户端） |
| 数据格式 | 任意（文本/二进制） | 文本（`data: ...\n\n`） |
| 心跳 | 手动实现 | 自动（注释行） |
| 浏览器支持 | 100% | 100%（除 IE） |
| 代理穿透 | 偶尔需要配置 | **更友好** |
| Spring 支持 | `spring-websocket` | `spring-webmvc` 内置 |

`pai-smart` 选 WebSocket——**因为后续要做「客户端发心跳 + 服务端推送」**这种**双向**交互。

**面试考点 1**：WebSocket 握手时怎么鉴权？

A：**Sec-WebSocket-Protocol 头**或 **URL 参数**：
```
ws://localhost:8080/chat?token=eyJhbGciOiJIUzI1NiJ9...
```

**`pai-smart` 是 URL 参数**——看 `extractToken(session)` 方法。

---

## 【二、`ChatWebSocketHandler`：连接生命周期管理】

### 2.1 连接建立：握手 + 鉴权

```java
@Override
public void afterConnectionEstablished(WebSocketSession session) {
    String jwtToken;
    try {
        jwtToken = extractToken(session);
        if (!jwtUtils.validateToken(jwtToken)) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
    } catch (Exception exception) {
        session.close(CloseStatus.POLICY_VIOLATION);
        return;
    }

    String userId = extractUserId(jwtToken);
    String clientId = extractClientId(session);
    
    session.getAttributes().put(ATTR_USER_ID, userId);
    chatSessionRegistry.registerSession(userId, clientId, session);

    // 发送 connection 消息
    Map<String, String> connectionMessage = Map.of(
        "type", "connection",
        "sessionId", session.getId(),
        "message", "WebSocket连接已建立"
    );
    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(connectionMessage)));
}
```

**关键点**：

1. **握手时鉴权**——`validateToken` 失败直接 `POLICY_VIOLATION`。
2. **从 token 拿 userId**——**之后消息都关联到 userId**。
3. **`clientId`**：一个用户可能开多个客户端（**手机、PC**），**每个客户端一个 clientId**。
4. **`session.getAttributes()`**——存 userId，**之后消息处理直接读**。
5. **`ChatSessionRegistry`**：**内存里的 session 注册表**——`userId -> Map<clientId, WebSocketSession>`。**用来主动推送**（第 13 集限流告警推送会用到）。
6. **发送 connection 消息**——前端收到后**知道连接就绪**，**可以开始发消息**。

**面试考点 2**：`session.getAttributes()` 是什么？

A：**WebSocket session 级别的 Map**——**存这个连接相关的元数据**。**类似 HttpSession 的 attributes**。**注意**：**WebSocketSession 不是 HttpSession**——**生命周期、存储都不一样**。

### 2.2 心跳机制

```java
private static final String HEARTBEAT_PING = "__chat_ping__";
private static final String HEARTBEAT_PONG = "__chat_pong__";

@Override
protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    String payload = message.getPayload();
    if (HEARTBEAT_PING.equals(payload)) {
        session.sendMessage(new TextMessage(HEARTBEAT_PONG));
        return;
    }
    // ... 进入聊天处理
}
```

**前端定时（30s）发 `__chat_ping__`**——**后端回 `__chat_pong__`**——**连接保活**。

**为什么需要心跳？**
- **Nginx 默认 60s 切断空闲 WebSocket**。
- **企业网关 / 防火墙**也会**超时切断**。
- **心跳让中间设备知道连接活着**。

**面试考点 3**：心跳的最佳间隔？

A：
- **太短**（< 10s）：浪费带宽、电池。
- **太长**（> 60s）：Nginx 切了不知道。
- **经验值**：**30-60s**。

`pai-smart` 用**协议字符串做心跳**——**比 ping/pong 帧更直接**（**WebSocket 协议自带 ping/pong 帧**——**Spring 抽象了**）。

### 2.3 业务消息处理

```java
@Override
protected void handleTextMessage(WebSocketSession session, TextMessage message) {
    String userId = extractUserId(extractToken(session));
    String payload = message.getPayload();
    if (HEARTBEAT_PING.equals(payload)) {
        session.sendMessage(new TextMessage(HEARTBEAT_PONG));
        return;
    }
    
    // 1. 解析 JSON（前端发 { type, content, ... }）
    // 2. 提取 userMessage
    // 3. 调 ChatHandler 处理
    chatHandler.processMessage(userId, userMessage, session);
}
```

**`ChatHandler.processMessage(userId, userMessage, session)`**——**第 15 集会详细讲 ReAct 循环**。这里**关键是 session 传进去**——**`ChatHandler` 通过 session 推流**。

---

## 【三、`DeepSeekClient`：流式调 LLM】

```java
public void streamResponse(String requesterId, String userMessage, String context,
                          List<Map<String, String>> history,
                          Consumer<String> onChunk, Consumer<Throwable> onError) {
    Map<String, Object> request = buildRequest(userMessage, context, history);
    List<Map<String, String>> messages = (List<Map<String, String>>) request.get("messages");
    
    // 1. 配额预留
    int estimatedPromptTokens = usageQuotaService.estimateChatTokens(messages);
    int maxCompletionTokens = aiProperties.getGeneration().getMaxTokens() != null
            ? aiProperties.getGeneration().getMaxTokens() : 2000;
    UsageQuotaService.TokenReservation reservation = usageQuotaService.reserveLlmTokens(
            requesterId, estimatedPromptTokens, maxCompletionTokens);
    StreamUsageTracker usageTracker = new StreamUsageTracker(reservation, estimatedPromptTokens);
    
    try {
        // 2. 流式调用
        webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)  // <-- Flux = 流
                .subscribe(
                    chunk -> processChunk(chunk, usageTracker, onChunk),
                    error -> { settleUsage(usageTracker); onError.accept(error); },
                    () -> settleUsage(usageTracker)
                );
    } catch (Exception e) {
        usageQuotaService.abortReservation(reservation);
        throw e;
    }
}
```

### 3.1 `bodyToFlux(String.class)`：流式响应的关键

**`Mono` vs `Flux`**：
- **`Mono<T>`**：0 或 1 个元素（**单值**）。
- **`Flux<T>`**：0 到 N 个元素（**流**）。

DeepSeek 的 `chat/completions` 接口**当 `stream: true` 时返回 SSE**——**每个 chunk 是一个 JSON**：

```
data: {"choices":[{"delta":{"content":"你"}}]}

data: {"choices":[{"delta":{"content":"好"}}]}

data: {"choices":[{"delta":{"content":"！"}}]}

data: [DONE]
```

**`bodyToFlux`** 把 HTTP body 的 stream 解析成 `Flux<String>`——**每个 data 块是一个元素**。

**`.subscribe(chunk, error, complete)`**：
- `chunk` consumer：**每个 token 来了怎么处理**。
- `error` consumer：**出错了怎么处理**。
- `complete` Runnable：**流结束了怎么处理**。

**整个流是非阻塞的**——**WebFlux 异步事件循环**。

**面试考点 4**：Flux 和 Stream 的区别？

A：
- **`Stream`**：JDK 8，**同步拉取**（`forEach` 阻塞）。
- **`Flux`**：Reactive Streams，**异步推送**（`subscribe` 不阻塞）。
- **WebFlux 必须用 Flux**——**否则失去异步优势**。

### 3.2 `buildRequest`：构造 messages 数组

```java
private Map<String, Object> buildRequest(String userMessage, String context, List<Map<String, String>> history) {
    List<Map<String, String>> messages = new ArrayList<>();
    
    // 1. system 角色：注入 RAG 召回的 context
    messages.add(Map.of(
        "role", "system",
        "content", "你是一个企业知识助手。基于以下信息回答用户问题：\n\n" + context
    ));
    
    // 2. 历史消息
    if (history != null) messages.addAll(history);
    
    // 3. 用户当前问题
    messages.add(Map.of("role", "user", "content", userMessage));
    
    Map<String, Object> request = new HashMap<>();
    request.put("model", model);
    request.put("stream", true);
    request.put("messages", messages);
    request.put("temperature", 0.7);
    return request;
}
```

**`pai-smart` 用的 prompt 结构**：
1. **system**：注入 RAG 召回的 context——**告诉 LLM "用这些信息回答"**。
2. **history**：之前的对话。
3. **user**：当前问题。

**这是 RAG 的标准 prompt 模式**——**"先给资料，再问问题"**。

**面试考点 5**：prompt 怎么写效果最好？

A：
- **明确角色**：「你是一个企业知识助手」比「你是一个 AI」好。
- **明确约束**：「仅基于以下信息回答，不知道就说不知道」减少幻觉。
- **明确格式**：「用 Markdown 列表回答」「每段不超过 100 字」。
- **引用标注**：「回答时标注信息来源（如 来源#1）」。

`pai-smart` 用了**前 3 个**——**是基础实践**。

### 3.3 配额预留：流式也走「先扣后用」

```java
int estimatedPromptTokens = usageQuotaService.estimateChatTokens(messages);
int maxCompletionTokens = ...;
UsageQuotaService.TokenReservation reservation = usageQuotaService.reserveLlmTokens(
        requesterId, estimatedPromptTokens, maxCompletionTokens);
```

**流式调用的配额**比 Embedding 复杂——**因为 LLM 还要生成**：
- `estimatedPromptTokens`：system + history + user 的 token（**用 `tiktoken` 类库估算**）。
- `maxCompletionTokens`：LLM 最多生成多少（**配置项**）。
- **预留总量 = `estimatedPromptTokens + maxCompletionTokens`**。

**`StreamUsageTracker`** 在流式过程中**累加实际消耗**：
- 收到每个 chunk 解析 `usage` 字段（DeepSeek 在最后一个 chunk 给 `prompt_tokens / completion_tokens`）。
- **最后 `settleUsage(usageTracker)` 按实际值结算**。

**面试考点 6**：流式响应中怎么知道 token 用了多少？

A：DeepSeek / OpenAI 的 SSE 格式里，**每个 chunk 有 `usage` 字段但默认是 null**，**只有最后一个 chunk 会带 `usage.total_tokens`**。**`pai-smart` 在 `processChunk` 里检测并累加**。

---

## 【四、`ChatHandler.processMessage`：业务编排】

```java
public void processMessage(String userId, String userMessage, WebSocketSession session) {
    String requestClientId = resolveClientId(session);
    String conversationId = null;
    String generationId = null;
    try {
        rateLimitService.checkChatByUser(userId);

        // 1. 获取或创建会话 ID
        conversationId = getOrCreateConversationId(userId);
        conversationService.ensureConversationSession(Long.parseLong(userId), conversationId, userMessage);
        ChatGenerationStateService.GenerationSnapshot generation =
                chatGenerationStateService.createGeneration(userId, conversationId, userMessage);
        generationId = generation.generationId();
        
        // 2. 推送"开始生成"消息
        sendGenerationStart(userId, generationId, conversationId);
        
        // 3. 创建响应构建器
        responseBuilders.put(generationId, new StringBuilder());
        CompletableFuture<String> responseFuture = new CompletableFuture<>();
        responseFutures.put(generationId, responseFuture);
        
        // 4. 获取对话历史
        List<Map<String, String>> history = conversationService.getRecentHistory(conversationId, 10);
        
        // 5. ReAct 循环（第 15 集细讲）
        runReActLoopSafely(userId, userMessage, conversationId, generationId, history, responseFuture);
        
    } catch (Exception e) {
        handleError(userId, generationId, e);
        sendCompletionNotification(userId, generationId, conversationId, true, false);
        cleanupGenerationState(generationId, e);
    }
}
```

**这期我们重点关注 1-4，5 留到第 15 集。**

### 4.1 限流

```java
rateLimitService.checkChatByUser(userId);
```

**用户级限流**——**比如每分钟 10 次**。**超过就拒绝**。**第 13 集会深入讲**。

### 4.2 会话管理

```java
conversationId = getOrCreateConversationId(userId);
conversationService.ensureConversationSession(Long.parseLong(userId), conversationId, userMessage);
```

**会话** = **一个连续的对话流**——**保留 context**。

**`conversationId` 怎么存？** 推测：
- **WebSocketSession attribute**（**连接级别的 session**）。
- **Redis 缓存**（**跨连接保留**）。

`pai-smart` 在 `getOrCreateConversationId` 里**根据 session attribute 判断**——**新连接开新会话**。

### 4.3 Generation：一次"生成任务"的概念

```java
ChatGenerationStateService.GenerationSnapshot generation =
        chatGenerationStateService.createGeneration(userId, conversationId, userMessage);
generationId = generation.generationId();
```

**一次用户提问 → 一个 generation**。

**为什么需要这个抽象？**
- **多轮 ReAct**：一个用户问题可能要调 LLM 多次（**第 15 集**）。
- **状态追踪**：哪个 generation 在跑、跑多久、用了多少 token、是否完成。
- **取消支持**：用户主动取消，**generation 状态变 CANCELLED**。

**`ChatGenerationStateService`** 用 Redis 维护这个状态机——**分布式**——多实例都能查到。

### 4.4 历史获取

```java
List<Map<String, String>> history = conversationService.getRecentHistory(conversationId, 10);
```

**取最近 10 条对话**作为上下文。**为什么是 10？**
- **DeepSeek context 长度**：~32K tokens。
- **10 条对话 ≈ 2000 tokens**——**留出 LLM 生成的余量**。
- **更多历史会"挤掉"当前问题的 attention**。

**面试考点 7**：长对话怎么处理？

A：
- **滑动窗口**：只保留最近 N 条。
- **摘要压缩**：用 LLM 把早期对话**总结成几句话**。
- **层次化记忆**：把对话分成"主线程"和"分支线程"。

`pai-smart` 用**滑动窗口**——**最简方案**。

---

## 【五、流式推回前端：`appendStreamChunk`】

```java
private void appendStreamChunk(String userId, String generationId, String conversationId, String chunk) {
    if (chunk == null || chunk.isEmpty()) return;
    StringBuilder builder = responseBuilders.get(generationId);
    if (builder != null) builder.append(chunk);
    
    WebSocketSession session = chatSessionRegistry.getActiveSession(userId, requestClientId);
    if (session != null && session.isOpen()) {
        Map<String, Object> msg = Map.of(
            "type", "chunk",
            "generationId", generationId,
            "content", chunk
        );
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(msg)));
    }
}
```

**每个 token 推一次**。

**前端协议**（`type` 字段）：
- `connection`：连接建立。
- `start`：开始生成。
- `chunk`：流式 token。
- `done`：生成完成。
- `error`：出错。
- `tool_call`：Agent 工具调用（第 15 集）。

**WebSocket 是 message 边界**——**每个 `sendMessage` 是一帧**——**前端要自己拼接**。

**面试考点 8**：流式响应怎么保证顺序？

A：**WebSocket 本身保证消息顺序**——`sendMessage(A)` 一定在 `sendMessage(B)` 之前到。**TCP 有序性**。

---

## 【六、心跳之外的"服务端主动推送"】

`pai-smart` 的 WebSocket 不只是「客户端提问 → 服务端回答」——**还能主动推送**：

1. **处理进度通知**（文档处理完成、生成结束）。
2. **限流告警**（"你今天用了 90% 配额"）。
3. **系统公告**（"系统维护通知"）。

**`ChatSessionRegistry`** 是关键——`userId -> Map<clientId, WebSocketSession>`。

**主动推送流程**：
```java
// 1. 业务事件发生
fileVectorizationCompleted(fileMd5);

// 2. 找出这个用户的所有 session
Map<String, WebSocketSession> sessions = chatSessionRegistry.getSessions(userId);

// 3. 推消息
for (WebSocketSession session : sessions.values()) {
    if (session.isOpen()) {
        session.sendMessage(new TextMessage(notification));
    }
}
```

---

## 【七、WebSocket 的多实例问题】

**场景**：用户 A 第一次连 WebSocket 到实例 1，第二次到实例 2。

**问题**：实例 2 不知道 A 的 session 状态。

**`pai-smart` 的解法**：
- **`ChatSessionRegistry` 是实例级别的**——`ConcurrentHashMap`——**不跨实例**。
- **靠 sticky session**（Nginx `ip_hash`）让用户总连同一实例。

**生产级方案**：
- **Redis Pub/Sub**：实例 1 处理完事件 → 发 Redis 消息 → 所有实例订阅 → 各自动作。
- **Kafka + WebSocket Gateway**：专门的推送服务。

`pai-smart` 当前用**简化方案**——**单实例够用**。

**面试考点 9**：WebSocket 多实例怎么实现广播？

A：
- **Redis Pub/Sub**：`redisTemplate.convertAndSend("event", message)`，所有实例订阅。
- **Kafka topic**：发到 Kafka，所有实例消费。
- **专用 WebSocket 网关**：所有长连接挂在网关，**业务无状态**。

---

## 【八、`WebSocketConfig`：注册 handler】

```java
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/chat")
                .setAllowedOriginPatterns("*");
    }
}
```

**`/chat` 是 WebSocket 端点**——前端用 `ws://host:8080/chat?token=xxx` 连。

**`setAllowedOriginPatterns("*")`**——**生产环境要限制**为前端域名。

---

## 【九、常见坑 & 面试问答】

**Q1：WebSocket 和 HTTP 共享端口吗？**

A：**是的**。Tomcat 监听 8080，**HTTP 和 WebSocket 共用**。**路径区分**：`/chat` → WS，`/api/...` → HTTP。

**Q2：WebSocket 连接断了怎么重连？**

A：前端用 **`reconnect-websocket`** 库：
```js
const ws = new ReconnectingWebSocket('ws://...', [], {
    maxRetries: 10,
    retryDecay: 1.5
});
```

**重连后**重新走握手 + 鉴权。

**Q3：WebSocket 怎么和 HTTP 鉴权统一？**

A：**复用 JWT**。**WebSocket 握手时**带 `Authorization` 头**（虽然 WebSocket spec 没要求 `Authorization` 头，**实际可用**）或** URL 参数**。

`pai-smart` 用 **URL 参数**——`?token=xxx`——**简单**。

**Q4：流式响应怎么知道"全部推完了"？**

A：DeepSeek 的 SSE 末尾有 `data: [DONE]`。`pai-smart` 在 `processChunk` 里检测：
```java
if (chunk.contains("[DONE]")) {
    // 流结束
}
```

**Q5：单条消息太大 WebSocket 会断开吗？**

A：**单帧上限 16KB**（WebSocket spec）。**`pai-smart` 的 chunk 是单个 token**——**几字节到几十字节**——**远不到上限**。

---

## 【十、思考题：`pai-smart` 为什么要拆 `ChatHandler` 和 `ChatWebSocketHandler`？**

**当前结构**：
- `ChatWebSocketHandler`：WebSocket 协议层（**握手、心跳、消息分发**）。
- `ChatHandler`：业务逻辑层（**限流、会话、ReAct、调 LLM**）。

**为什么拆？**
- **SRP**：每个类一个职责。
- **可测试**：`ChatHandler` 不依赖 WebSocket——**可以 mock session 单测**。
- **可替换协议**：以后接 gRPC-Web 或 SSE，**只换 handler，业务不动**。

**这是好设计**——**比"一个上帝类"好太多**。

---

## 【十一、下集预告】

RAG 流水线基本跑通了：**上传 → 解析 → 向量化 → 检索 → LLM 回答**。

**但！所有用户共享同一个文档库**——**公司 A 的机密文档，公司 B 的员工也能搜到**。

**这不行。**

第 11 集我们要：

- `OrganizationTag` 体系——**多租户隔离**。
- `OrgTagAuthorizationFilter`——**URL 级权限控制**。
- `OrgTagCacheService`——**缓存 org tag**。
- 用户注册时**默认打 DEFAULT tag**。

**多租户是 SaaS 系统的基石**——`pai-smart` 的实现值得学。

我们下期见。

---

> 这一期的 WebSocket + WebFlux 流式是**现代化 RAG 系统的标配**。
> 觉得有用，**点赞、投币、收藏**。

下一集链接：[第 11 集：多租户隔离——OrgTag 体系](./11-multi-tenant-orgtag.md)
