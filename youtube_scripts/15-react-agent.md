# 第 15 集：ReAct 与 Agent 工具——LLM 装上手和脚

> 系列：派聪明 RAG 后端演进全解
> 集数：15 / 20+
> 主题：ReAct 模式、Tool Calling、AgentToolRegistry、循环控制
> 上集回顾：第 14 集对话历史落地
> 本集目标：LLM 能主动调工具，多轮思考后给出答案

---

## 【开场 Hook】

传统 RAG 流程：

> 用户问 → 召回 → LLM 回答

**问题**：**召回什么 LLM 就用什么**——**没有"判断"过程**。

Agent 流程：

> 用户问 → LLM 思考 → **选择工具** → **调工具** → 拿到结果 → **再思考** → … → 回答

**LLM 拥有"判断力"**——**知道什么时候搜、什么时候不搜、什么时候生成摘要**。

`pai-smart` 用了 **ReAct 模式**——**Reason + Act**——**思考和行动交替**。

今天我们拆 `AgentToolRegistry` 和 `ChatHandler.runReActLoop`。

---

## 【一、ReAct 是什么？】

**ReAct = Reason + Act**，2022 年 Yao et al. 提出的范式。

**核心思想**：

```
Thought 1: 用户问的是派聪明部署方式，我需要先搜索内部文档。
Action 1: search_knowledge(query="派聪明 部署")
Observation 1: 找到 3 个相关文档...

Thought 2: 文档说要 Docker Compose 部署，用户可能想知道具体步骤。
Action 2: search_knowledge(query="派聪明 docker-compose 文件")
Observation 2: 找到 1 个相关文档...

Thought 3: 信息够全了，可以回答。
Final Answer: 派聪明支持 Docker Compose 部署，启动命令是...
```

**三步循环**：**Thought → Action → Observation**。

**vs 传统 Chain-of-Thought**：
- **CoT**：LLM **内部**一步步想。
- **ReAct**：LLM **外部**一步步做——**每个 Action 调用真的工具**。

**面试考点 1**：ReAct vs Function Calling？

A：
- **Function Calling**：LLM 决定调哪个函数 + 参数——**一次返回**。
- **ReAct**：LLM 多次思考 + 多次调工具——**多轮**。
- **OpenAI Tools API**：底层是 Function Calling，**支持多次调用循环**。

`pai-smart` 用 **Function Calling** 实现 **ReAct**。

---

## 【二、`AgentToolRegistry`：工具的注册表】

```java
@Service
public class AgentToolRegistry {

    private final List<AgentTool> tools;
    private final Map<String, ToolHandler> handlers;

    public AgentToolRegistry(...) {
        this.tools = List.of(
            searchKnowledgeTool(),
            generateSummaryTool(),
            submitFeedbackTool(),
            knowledgeStatsTool()
        );
        this.handlers = Map.of(
            "search_knowledge", this::executeSearchKnowledge,
            "generate_summary", this::executeGenerateSummary,
            "submit_feedback", this::executeSubmitFeedback,
            "knowledge_stats", this::executeKnowledgeStats
        );
    }
}
```

**`AgentTool` 定义**（推测）：
```java
public record AgentTool(
    String name,                    // 工具名
    String description,             // 工具描述（给 LLM 看）
    Map<String, Object> parameters  // 参数 schema（JSON Schema）
) {}
```

**四个内置工具**：
1. **`search_knowledge`**：搜索知识库。
2. **`generate_summary`**：生成摘要。
3. **`submit_feedback`**：提交反馈（第 19 集）。
4. **`knowledge_stats`**：查知识库统计。

**面试考点 2**：为什么用 Map<String, ToolHandler> 注册？

A：
- **解耦**：定义和实现分开。
- **扩展**：加新工具**只改这一处**。
- **测试**：可 mock 工具。

**这是「注册表模式」**——**Java 后端常见**。

---

## 【三、`search_knowledge` 工具的实现】

```java
private ToolExecutionResult executeSearchKnowledge(Map<String, Object> arguments,
                                                   String userId, Consumer<String> onChunk) {
    requireUserId(userId);
    String query = getRequiredString(arguments, "query");
    int topK = getInt(arguments, "topK", DEFAULT_TOP_K, 1, MAX_SEARCH_DOCS);

    List<SearchResult> results = hybridSearchService.searchWithPermission(query, userId, topK);
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("query", query);
    data.put("topK", topK);
    data.put("results", results);
    return new ToolExecutionResult("search_knowledge", true, formatSearchResults(results), data);
}
```

**几个关键点**：

1. **`requireUserId(userId)`**：**必须传 userId**——**权限隔离**。
2. **`getRequiredString(arguments, "query")`**：从 LLM 给的参数里取 query。
3. **`MAX_SEARCH_DOCS = 20`**：**单次最多搜 20 个**——**避免 LLM 一次召回太多**。
4. **`formatSearchResults(results)`**：把 SearchResult 列表**格式化成 LLM 友好的字符串**。

**参数 schema**（推测）：
```json
{
  "type": "object",
  "properties": {
    "query": { "type": "string", "description": "搜索查询关键词" },
    "topK": { "type": "integer", "description": "返回结果数量", "default": 5, "minimum": 1, "maximum": 20 }
  },
  "required": ["query"]
}
```

**JSON Schema 告诉 LLM**：**这个工具接受什么参数、参数是什么类型**。

**面试考点 3**：JSON Schema 是什么？

A：**描述 JSON 数据结构的规范**。**OpenAI / DeepSeek 的 tool calling 接口**接受 JSON Schema 描述工具——**LLM 按 schema 生成符合规范的参数**。

---

## 【四、`ChatHandler.runReActLoop`：循环核心】

```java
private void runReActLoop(String userId, String userMessage, String conversationId,
                          String generationId, List<Map<String, String>> history,
                          CompletableFuture<String> responseFuture) {
    // 1. 初始 RAG 检索（如果需要）
    String initialContext = "";
    if (shouldUseInitialKnowledgeSearch(userMessage)) {
        InitialSearchOutcome outcome = runInitialKnowledgeSearch(userId, userMessage, generationId, conversationId);
        if (outcome.noHit()) {
            appendStreamChunk(userId, generationId, conversationId, buildNoKnowledgeHitResponse(userMessage));
            finalizeResponse(...);
            return;
        }
        initialContext = outcome.context();
    }

    // 2. 构造 messages
    List<Map<String, Object>> messages = llmProviderRouter.buildReActMessages(
        userMessage, initialContext, history, buildRecentFeedbackGuidance(userId)
    );

    int executedToolCalls = 0;
    int totalRounds = 0;
    int maxRounds = MAX_REACT_ROUNDS;  // 4
    int maxToolCalls = MAX_REACT_TOOL_CALLS;  // 8

    // 3. ReAct 循环
    while (totalRounds < maxRounds && executedToolCalls < maxToolCalls) {
        totalRounds++;
        
        // a. 调 LLM
        LlmProviderRouter.StreamCompletion completion = llmProviderRouter.streamCompletion(
            userId, messages, generationId, conversationId, ...);
        
        // b. 看 LLM 是不是要调工具
        if (completion.toolCallDecision() == null) {
            // 没有工具调用 → LLM 直接回答
            finalizeResponse(userId, userMessage, conversationId, generationId, responseFuture,
                responseBuilders.get(generationId), completion);
            return;
        }
        
        // c. LLM 要调工具
        LlmProviderRouter.ToolCallDecision toolCall = completion.toolCallDecision();
        sendToolCallStatus(userId, generationId, conversationId, toolCall, "executing");
        
        // d. 执行工具
        ExecutedToolResult toolResult = executeToolForReAct(userId, userMessage, generationId, conversationId, toolCall);
        executedToolCalls++;
        
        // e. 把工具结果塞回 messages
        Map<String, Object> toolMessage = Map.of(
            "role", "tool",
            "content", toolResult.content(),
            "tool_call_id", toolCall.id()
        );
        messages.add(toolMessage);
        
        // f. 继续循环
    }
    
    // 4. 超过最大轮次 → 强制结束
    finalizeResponse(...);
}
```

**关键参数**：
- **`MAX_REACT_ROUNDS = 4`**：**最多 4 轮 Thought-Action**。
- **`MAX_REACT_TOOL_CALLS = 8`**：**最多调 8 次工具**。
- **`REACT_MAX_COMPLETION_TOKENS = 2000`**：**每次 LLM 输出限 2000 token**。

**为什么有上限？**
- **成本控制**：每轮都调 LLM，**4 轮 = 4 倍花费**。
- **避免死循环**：LLM 偶尔"上头"——**一直调不收敛**。
- **响应延迟**：用户等不了。

**面试考点 4**：ReAct 循环的最大轮次怎么定？

A：
- **业务复杂度**：简单 RAG **2 轮**够；复杂多步推理 **5-10 轮**。
- **成本预算**：每轮多花 1 次 LLM 调用。
- **响应延迟**：每轮 1-3 秒。
- **经验值**：**3-5 轮**（`pai-smart` 4 轮）。

---

## 【五、`buildReActMessages`：构造初始 messages】

```java
public List<Map<String, Object>> buildReActMessages(String userMessage, String initialContext,
                                                     List<Map<String, String>> history,
                                                     String feedbackGuidance) {
    List<Map<String, Object>> messages = new ArrayList<>();
    
    // 1. system: 角色 + RAG context + 反馈指导
    String systemContent = "你是派聪明助手。基于以下信息回答：\n" + initialContext;
    if (feedbackGuidance != null) {
        systemContent += "\n\n" + feedbackGuidance;
    }
    messages.add(Map.of("role", "system", "content", systemContent));
    
    // 2. system: 工具定义
    messages.add(Map.of("role", "system", "content", "你可以使用以下工具：\n" + formatToolsDescription(tools)));
    
    // 3. 历史
    if (history != null) messages.addAll(history);
    
    // 4. 当前 user 问题
    messages.add(Map.of("role", "user", "content", userMessage));
    
    return messages;
}
```

**LLM 看到的关键信息**：
- **RAG context**（**初始召回**）。
- **工具列表**（**JSON Schema**）。
- **历史对话**。
- **当前问题**。
- **反馈指导**（**如果有负面反馈，告诉 LLM "注意"**——第 19 集）。

**面试考点 5**：为什么"初始 RAG 检索" + "ReAct 工具调用"并行？

A：
- **初始检索**：**热启动**——**已经有 context**。
- **ReAct 工具**：**扩展能力**——**搜索外部信息**。
- **两者互补**：**初始检索快**（**一次**），**ReAct 精**（**按需**）。

**`shouldUseInitialKnowledgeSearch`** 的判断：
```java
private boolean shouldUseInitialKnowledgeSearch(String userMessage) {
    // 简单问候语不搜
    if (matchesGreeting(userMessage)) return false;
    // 计算型问题不搜
    if (isCalculation(userMessage)) return false;
    return true;
}
```

**节省成本**——**「你好」「1+1=？」** 不需要 RAG。

---

## 【六、`executeToolForReAct`：工具执行细节】

```java
private ExecutedToolResult executeToolForReAct(String userId, String userMessage, String generationId,
                                               String conversationId,
                                               LlmProviderRouter.ToolCallDecision toolCall) {
    sendToolCallStatus(userId, generationId, conversationId, toolCall, "executing");
    AtomicBoolean summaryStreamStarted = new AtomicBoolean(false);
    try {
        logger.info("ReAct 执行 Agent Tool: name={}, userId={}, generationId={}, toolCallId={}, args={}",
                toolCall.name(), userId, generationId, toolCall.id(), toolCall.arguments());
        
        Consumer<String> toolChunkConsumer = "generate_summary".equals(toolCall.name())
                ? chunk -> {
                    if (chunk == null || chunk.isEmpty()) return;
                    if (summaryStreamStarted.compareAndSet(false, true)) {
                        appendStreamChunk(userId, generationId, conversationId, "\n\n");
                    }
                    appendStreamChunk(userId, generationId, conversationId, chunk);
                }
                : null;
        
        AgentToolRegistry.ToolExecutionResult toolResult = 
            agentToolRegistry.executeTool(toolCall.name(), toolCall.arguments(), userId, toolChunkConsumer);
        
        sendToolCallStatus(userId, generationId, conversationId, toolCall, "completed");
        // ...
    }
}
```

**亮点**：
1. **`sendToolCallStatus` 推前端**——**用户能看到 "正在调用 search_knowledge..."**。
2. **`generate_summary` 工具的流式输出**——**摘要也是流式生成**。
3. **错误处理**：异常被 try-catch——**不让一个工具失败搞挂整个流程**。

**面试考点 6**：工具失败怎么办？

A：
- **捕获异常**——**不让 ReAct 循环崩**。
- **返回错误信息给 LLM**——`{"error": "search timeout"}`。
- **LLM 看错误**——**可能换工具或换 query**。
- **优雅降级**——**实在不行**就**直接基于已有 context 回答**。

`pai-smart` **有这种容错**。

---

## 【七、`runInitialKnowledgeSearch`：初始检索的"短路"机制】

```java
private InitialSearchOutcome runInitialKnowledgeSearch(String userId, String userMessage,
                                                       String generationId, String conversationId) {
    // 1. 调 search_knowledge 工具
    AgentToolRegistry.ToolExecutionResult result = 
        agentToolRegistry.executeTool("search_knowledge", 
            Map.of("query", userMessage, "topK", 5), userId);
    
    // 2. 格式化 context
    String context = (String) result.data().get("formatted");
    
    // 3. 判断有没有命中
    if (context.isEmpty() || context.contains("未找到")) {
        return new InitialSearchOutcome(true, "");
    }
    return new InitialSearchOutcome(false, context);
}
```

**`noHit()` 短路**：
- 知识库没命中 → **直接回复"我不知道"**。
- **不进入 ReAct 循环**。
- **节省成本**。

**`buildNoKnowledgeHitResponse`**：
```java
private String buildNoKnowledgeHitResponse(String userMessage) {
    return "抱歉，我在派聪明知识库中没有找到与「" + userMessage + "」相关的信息。"
        + "您可以换个问法，或上传相关文档后再问我。";
}
```

**面试考点 7**：什么时候"不知道"比"瞎编"好？

A：**永远**。**LLM 幻觉是企业级 RAG 的头号问题**。**"我不知道"是负责任的回答**。

`pai-smart` **有这个机制**——**值得点赞**。

---

## 【八、`runReActLoopSafely`：异常兜底】

```java
private void runReActLoopSafely(...) {
    try {
        runReActLoop(...);
    } catch (Exception e) {
        logger.error("ReAct 循环执行失败: generationId={}", generationId, e);
        chatGenerationStateService.markFailed(generationId, e.getMessage());
        handleError(userId, generationId, e);
        sendCompletionNotification(userId, generationId, conversationId, true, false);
        cleanupGenerationState(generationId, e);
    }
}
```

**ReAct 循环最外层的 try-catch**：
- **任何异常** → 标记 generation 失败 + 推错误给前端 + 清理状态。
- **不让异常泄漏到 WebSocket**。

**面试考点 8**：异步任务怎么"善后"？

A：**try-finally + 状态机**。
- **进入任务**：标记 PROCESSING。
- **完成**：标记 COMPLETED。
- **失败**：标记 FAILED + 记录原因。
- **清理**：释放资源（**线程、Redis key**）。

`pai-smart` **有这套**。

---

## 【九、引用替换：`replaceReferencesFromSearchTool`**

```java
private void replaceReferencesFromSearchTool(String generationId, String userMessage,
                                             AgentToolRegistry.ToolExecutionResult toolResult) {
    // LLM 生成"来源#1"等引用 → 替换成"文件名 + 页码 + 跳转链接"
}
```

**产品功能**：
- LLM 回答：`「派聪明支持 Docker Compose 部署（来源#1）」`。
- 前端显示：`「派聪明支持 Docker Compose 部署（来源: 派聪明部署文档.pdf 第 5 页）」`。
- **点击跳转**到原文档。

**这是 RAG 系统的"可信度"设计**——**告诉用户"答案从哪来"**。

**面试考点 9**：为什么 RAG 要给引用？

A：
- **可验证**：用户能**查证**——**不是 LLM 瞎编**。
- **可追溯**：**审计**（**金融、医疗必备**）。
- **建立信任**：用户**敢用** AI 给的答案。

---

## 【十、常见坑 & 面试问答】

**Q1：LLM 不停调工具怎么办？**

A：
- **MAX_REACT_ROUNDS 上限**（`pai-smart` 4 轮）。
- **MAX_REACT_TOOL_CALLS 上限**（`pai-smart` 8 次）。
- **超时机制**：整个 ReAct 循环**全局超时**。
- **检测循环**：相同工具 + 相同参数 **2 次** → 强制终止。

**Q2：怎么评估 ReAct 效果？**

A：
- **完成率**：ReAct 循环**正常结束**的比例。
- **工具调用率**：多少对话**调了工具**。
- **答案质量**：人工评估 top-K。
- **token 成本**：平均每次对话多少 token。

`pai-smart` **没看到评估**——**未来**。

**Q3：ReAct 是不是过度设计？**

A：**看场景**。
- **简单 RAG**：LLM 直接基于 context 回答——**ReAct 没必要**。
- **复杂任务**：多步推理、外部 API 查数据——**ReAct 有用**。

`pai-smart` **两者都做**——**简单问题走 initial search，复杂问题走 ReAct**——**聪明**。

**Q4：JSON Schema 怎么写？**

A：参考 OpenAI 文档：
```json
{
  "type": "function",
  "function": {
    "name": "search_knowledge",
    "description": "在派聪明知识库中搜索相关文档",
    "parameters": {
      "type": "object",
      "properties": {
        "query": {"type": "string", "description": "搜索关键词"},
        "topK": {"type": "integer", "default": 5, "minimum": 1, "maximum": 20}
      },
      "required": ["query"]
    }
  }
}
```

**`description` 越清楚，LLM 调用越准**——**这值得花时间**。

**Q5：怎么让 LLM 选对工具？**

A：
- **工具描述写清楚**（**最重要**）。
- **Few-shot 提示**：`「示例：用户问 X → 应该调 search_knowledge」`。
- **强制选择**：业务上只允许某些工具——**过滤 schema**。

---

## 【十一、思考题：ReAct 怎么调试？**

**调试难点**：
- LLM 是黑盒——**"为什么调这个工具"** 不可知。
- 多轮循环——**错误传播**。
- **生产问题**难复现。

**调试手段**：
- **完整 trace**：`generationId` 关联**所有 messages + tool calls**——`pai-smart` 写 `Conversation` 表。
- **可视化**：前端展示**Thought → Action → Observation 流程**。
- **重放**：保存 prompt + 模型版本——**重放 ReAct 跑**。
- **A/B 测试**：不同 system prompt 对比。

`pai-smart` **`ChatGenerationStateService` 应该存了完整状态**——**未来可重放**。

---

## 【十二、下集预告】

LLM 装上了工具——**有"手和脚"**。

**但！所有 LLM 都用 DeepSeek 一个厂商**——**绑死了**。

- DeepSeek 涨价 → **成本翻倍**。
- DeepSeek 挂了 → **整个 RAG 不可用**。
- 想用 GPT-4 提升质量 → **改一堆代码**。

第 16 集我们要：

- `LlmProviderRouter` 登场——**多 LLM 路由**。
- `ModelProviderConfigService`——**数据库配置**。
- **A/B 测试**支持。
- **降级到备用 provider**。

**多 LLM 路由是生产级 RAG 的"成人礼"**。

我们下期见。

---

> ReAct 是 RAG → Agent 的**关键一步**——`pai-smart` 的实现**简洁且完整**。
> 觉得有用，**点赞、投币、收藏**。

下一集链接：[第 16 集：多 LLM Provider 路由——不绑死单一厂商](./16-llm-provider-router.md)
