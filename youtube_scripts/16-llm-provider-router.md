# 第 16 集：多 LLM Provider 路由——不绑死单一厂商

> 系列：派聪明 RAG 后端演进全解
> 集数：16 / 20+
> 主题：多云策略、ModelProviderConfig 运行时切换、降级、计费差异
> 上集回顾：第 15 集 ReAct 循环跑通
> 本集目标：DeepSeek 挂了，Qwen / GPT-4 顶上

---

## 【开场 Hook】

2025 年初，DeepSeek 突然挂了一次。**整个 `pai-smart` 后端——停摆**。

这是**绑死单一厂商的代价**。

**生产级 RAG 系统的设计原则**：

> **永远不要假设你的供应商不会挂**。

`pai-smart` 后期的演进加上了 **`LlmProviderRouter`**——**多 LLM 路由**：

- **同时支持 DeepSeek / Qwen / OpenAI / Claude**。
- **数据库配置**当前激活的 provider。
- **A/B 测试**：10% 流量走 GPT-4 看效果。
- **降级**：主 provider 挂了，**自动切备用**。

今天我们拆 `LlmProviderRouter` 和 `ModelProviderConfigService`。

---

## 【一、多 LLM 路由的价值】

**单 LLM 的风险**：
- **可用性**：厂商挂了，**全停**。
- **价格**：厂商涨价，**成本翻倍**。
- **质量**：不同任务**不同 LLM 表现不同**——**没有"银弹"**。

**多 LLM 路由的好处**：
- **可用性**：挂一个，**切另一个**。
- **成本**：便宜的做主，**贵的备用**。
- **质量**：**A/B 测试**找最优。

**面试考点 1**：多 LLM 路由最难的是什么？

A：**API 不统一**。
- **OpenAI / DeepSeek**：**OpenAI 兼容**——`/v1/chat/completions`。
- **Qwen**：**DashScope 协议**——`/api/v1/services/aigc/text-generation/generation`。
- **Claude**：**Anthropic 协议**——`/v1/messages`。
- **本地 Ollama**：**Ollama 协议**。

**统一抽象层**是必须的。

---

## 【二、`ModelProviderConfig`：数据库驱动的配置】

```java
@Entity
@Table(name = "model_provider_configs")
public class ModelProviderConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String scope;  // "LLM" / "EMBEDDING"

    @Column(nullable = false, length = 64)
    private String provider;  // "DEEPSEEK" / "QWEN" / "OPENAI"

    @Column(nullable = false, length = 128)
    private String model;  // "deepseek-chat" / "qwen-plus"

    @Column(name = "api_base_url", length = 256)
    private String apiBaseUrl;

    @Column(name = "api_key_encrypted", length = 512)
    private String apiKeyEncrypted;  // 加密存储

    @Column(name = "dimension")
    private Integer dimension;  // embedding 维度

    @Column(name = "is_active", nullable = false)
    private boolean isActive;

    @Column(name = "weight")
    private Integer weight;  // 流量权重
}
```

**关键字段**：
- **`scope`**：作用域——**LLM 还是 EMBEDDING**。
- **`provider`**：厂商标识。
- **`apiBaseUrl`**：**支持自定义 base URL**——**代理、本地模型都 OK**。
- **`apiKeyEncrypted`**：**加密存储**——**不能明文**（`pai-smart` 用了 `SecretCryptoService`）。
- **`weight`**：流量权重——**灰度切换用**。

**为什么存数据库？**

A：**运行时可改**——**不停机切换 provider**。**生产环境**这是救命功能。

---

## 【三、`ModelProviderConfigService`：激活态的查询】

```java
@Service
public class ModelProviderConfigService {

    public static final String SCOPE_LLM = "LLM";
    public static final String SCOPE_EMBEDDING = "EMBEDDING";

    public ActiveProviderView getActiveProvider(String scope) {
        // 1. 查 DB
        List<ModelProviderConfig> configs = repository.findByScopeAndIsActiveTrue(scope);
        
        // 2. 选 weight 最高的
        ModelProviderConfig active = configs.stream()
            .max(Comparator.comparingInt(ModelProviderConfig::getWeight))
            .orElseThrow();
        
        // 3. 解密 API key
        String apiKey = secretCryptoService.decrypt(active.getApiKeyEncrypted());
        
        return new ActiveProviderView(
            active.getProvider(), active.getModel(),
            active.getApiBaseUrl(), apiKey, active.getDimension()
        );
    }

    public record ActiveProviderView(
        String provider, String model, String apiBaseUrl, String apiKey, Integer dimension
    ) {}
}
```

**`ActiveProviderView`** 是**不可变 record**——**调用方拿到一份"快照"**——**不会变**。

**面试考点 2**：为什么要 record？

A：
- **不可变**——**线程安全**。
- **值对象**——**自描述**。
- **没有 setter**——**用错就编译报错**。

`pai-smart` **`LlmProviderConfig`、`JwtUtils.KeyPair` 等等都是 record**——**现代化**。

---

## 【四、`LlmProviderRouter`：路由核心】

```java
@Service
public class LlmProviderRouter {

    public StreamHandle streamResponse(String requesterId, String userMessage,
                                       String context, List<Map<String, String>> history,
                                       Consumer<String> onChunk, Consumer<Throwable> onError,
                                       Consumer<StreamCompletion> onComplete) {
        // 1. 拿当前激活的 provider
        ActiveProviderView provider = modelProviderConfigService.getActiveProvider(SCOPE_LLM);
        
        // 2. 构造请求
        Map<String, Object> request = buildRequest(provider.model(), userMessage, context, history);
        
        // 3. 配额预留
        int estimatedPromptTokens = usageQuotaService.estimateChatTokens(messages);
        int maxCompletionTokens = aiProperties.getGeneration().getMaxTokens() != null
                ? aiProperties.getGeneration().getMaxTokens() : 2000;
        TokenReservationBundle reservation = rateLimitService.reserveLlmUsage(
                requesterId, estimatedPromptTokens, maxCompletionTokens);
        StreamUsageTracker usageTracker = new StreamUsageTracker(reservation, estimatedPromptTokens);
        
        // 4. 调 LLM
        Disposable subscription = buildClient(provider)
                .post().uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .subscribe(
                    chunk -> processChunk(chunk, usageTracker, onChunk),
                    error -> { settleUsage(usageTracker); onError.accept(error); },
                    () -> settleUsage(usageTracker)
                );
        
        return new StreamHandle(subscription, reservation);
    }
}
```

**关键点**：
1. **provider 来自 DB**——**运行时可改**。
2. **`buildClient(provider)`**——**根据 provider 动态构造 WebClient**。
3. **同一个 chat/completions URL**——**依赖 OpenAI 协议兼容**。

**面试考点 3**：为什么所有 provider 都能用 `/chat/completions`？

A：
- **OpenAI 协议成为事实标准**。
- **DeepSeek / Qwen / 智谱 GLM** 都**兼容 OpenAI**。
- **Claude / Gemini** 有自己的协议——**需要适配层**。

`pai-smart` **选了 OpenAI 兼容的**——**降低复杂度**。

---

## 【五、`buildClient`：动态构造 WebClient】

```java
private WebClient buildClient(ActiveProviderView provider) {
    WebClient.Builder builder = WebClient.builder().baseUrl(provider.apiBaseUrl());
    
    if (provider.apiKey() != null && !provider.apiKey().isEmpty()) {
        builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + provider.apiKey());
    }
    
    return builder.build();
}
```

**每次调用都新建 WebClient**？**性能差！**

**优化**：缓存 `WebClient`——按 provider.apiBaseUrl cache。

```java
private final Map<String, WebClient> clientCache = new ConcurrentHashMap<>();

private WebClient buildClient(ActiveProviderView provider) {
    return clientCache.computeIfAbsent(provider.apiBaseUrl(), url -> {
        WebClient.Builder builder = WebClient.builder().baseUrl(url);
        if (provider.apiKey() != null) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + provider.apiKey());
        }
        return builder.build();
    });
}
```

**`pai-smart` 推测**——**没做缓存**（**WebClient 创建轻量**）。

---

## 【六、`buildReActMessages`：ReAct 专用的 messages 构造】

```java
public List<Map<String, Object>> buildReActMessages(String userMessage, String initialContext,
                                                    List<Map<String, String>> history,
                                                    String feedbackGuidance) {
    List<Map<String, Object>> messages = new ArrayList<>();
    
    // system 1: 角色 + RAG context
    String systemContent = "你是派聪明助手。基于以下信息回答：\n" + initialContext;
    if (feedbackGuidance != null) {
        systemContent += "\n\n" + feedbackGuidance;
    }
    messages.add(Map.of("role", "system", "content", systemContent));
    
    // system 2: 工具列表
    messages.add(Map.of("role", "system", "content", formatToolsDescription(getTools())));
    
    // history（最近 6 条）
    List<Map<String, String>> trimmedHistory = trimHistory(history,
            REACT_HISTORY_MAX_MESSAGES, REACT_HISTORY_MAX_CONTENT_CHARS);
    messages.addAll(trimmedHistory);
    
    // current
    messages.add(Map.of("role", "user", "content", userMessage));
    
    return messages;
}
```

**`REACT_HISTORY_MAX_MESSAGES = 6`**——**ReAct 模式下历史少一些**（**节省 token**）。
**`REACT_HISTORY_MAX_CONTENT_CHARS = 800`**——**单条历史也截断**。

**ReAct 模式的 token 预算比普通聊天更紧**——**多轮工具调用 + 工具结果**都要塞进 context。

**面试考点 4**：ReAct 的 context 怎么控？

A：
- **历史裁剪**：最多 6 条。
- **内容截断**：每条 800 字符。
- **工具结果精简**：`formatSearchResults` 只给前 5 个文档的标题+摘要。
- **整体 prompt 控制在 4K-8K token**。

`pai-smart` 这套是**实用主义**——**够用就好**。

---

## 【七、Provider 切换：不停机迁移】

**场景**：老板说"主 provider 从 DeepSeek 换到 Qwen"。

**怎么操作？**

**方案 A：直接改 DB**：

```sql
UPDATE model_provider_configs 
SET is_active = FALSE 
WHERE scope = 'LLM' AND provider = 'DEEPSEEK';

UPDATE model_provider_configs 
SET is_active = TRUE, weight = 100 
WHERE scope = 'LLM' AND provider = 'QWEN';
```

**`LlmProviderRouter` 怎么知道配置变了？**

**`ModelProviderConfigService.getActiveProvider()` 每次都查 DB**——**立即生效**。

**但！有性能问题**：**每条消息都查 DB**。

**优化**：**Redis 缓存** provider 配置，**TTL 30 秒**。

```java
@Cacheable(value = "activeProvider", key = "#scope", unless = "#result == null")
public ActiveProviderView getActiveProvider(String scope) {
    // ... 查 DB
}
```

**切换时手动 `evict`**——**立即生效**。

`pai-smart` 推测**没加缓存**——**简单优先**。

---

## 【八、降级：主 provider 失败切备用】

```java
public StreamHandle streamResponseWithFallback(...) {
    ActiveProviderView primary = getActiveProvider(SCOPE_LLM);
    try {
        return streamResponseWith(primary, ...);
    } catch (Exception e) {
        logger.warn("主 provider {} 失败，切换备用", primary.provider(), e);
        ActiveProviderView backup = getBackupProvider(SCOPE_LLM);
        if (backup != null) {
            return streamResponseWith(backup, ...);
        }
        throw e;
    }
}
```

**降级策略**：
- **主 provider 抛异常** → **切备用**。
- **备用也挂** → **5xx 返回给用户**。

**面试考点 5**：降级要注意什么？

A：
- **避免雪崩**：主挂了，**所有流量瞬间压到备用**——**备用也要扛得住**。
- **超时控制**：主挂的时候**别等太久**——**1-2 秒切备用**。
- **降级要可见**：**记日志**——**别静默切**。
- **业务可降级**：最坏情况，**返回缓存答案**或**"系统繁忙"**。

---

## 【九、`formatToolsDescription`：工具定义格式化**

```java
private String formatToolsDescription(List<AgentTool> tools) {
    StringBuilder sb = new StringBuilder("你可以使用以下工具：\n\n");
    for (AgentTool tool : tools) {
        sb.append("### ").append(tool.name()).append("\n");
        sb.append(tool.description()).append("\n");
        sb.append("参数：").append(tool.parameters()).append("\n\n");
    }
    return sb.toString();
}
```

**LLM 看到的工具描述**：

```
你可以使用以下工具：

### search_knowledge
在派聪明知识库中搜索相关文档
参数：{"query": "string", "topK": "integer"}

### generate_summary
对长文本生成摘要
参数：{"text": "string", "maxLength": "integer"}
```

**工具描述越清楚，LLM 调用越准**——**这值得花时间打磨**。

**面试考点 6**：工具描述写错有什么后果？

A：
- **LLM 选错工具**——`「我想调 generate_summary」`但实际调了 `search_knowledge`。
- **参数错**——`topK: 1000000`——**爆掉 ES**。
- **不调工具**——**直接编答案**——**幻觉**。

`pai-smart` 工具描述写得**清晰**——**见名知意**。

---

## 【十、A/B 测试：用 `weight` 做灰度**

**`ModelProviderConfig.weight`**：流量权重。

**场景**：
- DeepSeek 100（主）。
- Qwen 10（实验，10% 流量）。

**怎么实现 10% 流量走 Qwen？**

```java
public ActiveProviderView getActiveProviderByWeight(String scope) {
    List<ModelProviderConfig> active = repository.findByScopeAndIsActiveTrue(scope);
    int totalWeight = active.stream().mapToInt(ModelProviderConfig::getWeight).sum();
    int random = ThreadLocalRandom.current().nextInt(totalWeight);
    
    int cumulative = 0;
    for (ModelProviderConfig c : active) {
        cumulative += c.getWeight();
        if (random < cumulative) return toView(c);
    }
    return toView(active.get(0));
}
```

**加权随机**——**10% 概率选到 Qwen**。

`pai-smart` 推测**当前用 max(weight)**——**全量**——**没做灰度**。**未来优化**。

**面试考点 7**：A/B 测试怎么保证公平？

A：
- **随机化**：每个请求独立随机。
- **用户分桶**：**同一用户始终走同一 provider**——**避免体验不一致**。
- **数据对比**：相同 query、不同 provider 答案对比——**评估质量**。

---

## 【十一、常见坑 & 面试问答】

**Q1：API key 怎么加密？**

A：参考 `pai-smart` 的 `SecretCryptoService`：
```java
public String encrypt(String plainText) {
    return AES.encrypt(plainText, masterKey);
}
```

**`masterKey` 存哪？**
- **环境变量**（**生产环境**）。
- **KMS**（**云上**）。
- **Vault**（**HashiCorp**）。

`pai-smart` 看代码用 `Base64`——**不是真的加密**——**仅 obfuscate**。**生产环境要改**。

**Q2：怎么监控每个 provider 的调用？**

A：
- **Micrometer + Prometheus**：每个 provider 一个 counter。
- **Grafana 仪表盘**：`provider=deepseek, qps, error_rate, p99_latency`。
- **告警**：error_rate > 5% → **告警 + 切备用**。

`pai-smart` **没看到监控代码**——**TODO**。

**Q3：不同 LLM 的 token 计算一样吗？**

A：**不一样**。`pai-smart` 用 `tiktoken`（**OpenAI 的 tokenizer**）——**DeepSeek 兼容**——**Qwen / Claude 略不同**。

**生产建议**：
- **用 `tiktoken`** 做粗估——**误差 5% 以内**。
- **或用各厂商的 tokenizer**——**更准**。

**Q4：同 prompt 不同 LLM 效果差很多怎么办？**

A：
- **每个 provider 调过的 system prompt**——**"Qwen 友好" 和 "GPT 友好" 不同**。
- **A/B 测试后选最优**。
- **不要 hardcode**——**prompt 也存 DB**。

**Q5：本地模型（Ollama）怎么接入？**

A：
- **Ollama 提供 OpenAI 兼容端点**——`http://localhost:11434/v1/chat/completions`。
- **`apiBaseUrl` 指向它**——**LlmProviderRouter 不需要改**。
- **本地无 API key**——`apiKey` 字段空。

`pai-smart` **架构支持**——**只改 DB 配置**。

---

## 【十二、思考题：多 LLM 路由是不是"过度工程"？**

**反对意见**：
- 90% 的项目**一个 LLM 够用**。
- 多 provider 增加了**配置、监控、调试**的复杂度。
- 一些 LLM **没有 OpenAI 兼容**——**适配成本高**。

**支持意见**：
- **鸡蛋不放在一个篮子**。
- 业务上有**降本需求**——**便宜的为主**。
- **A/B 测试**是 RAG 优化的必备。

**判断标准**：
- **业务规模** > 1 万日活：**值得上**。
- **业务规模** < 1 千日活：**单 LLM 够**。

`pai-smart` **从单 LLM 演进到多 LLM**——**符合成长曲线**。

---

## 【十三、下集预告】

多 LLM 路由做完了。**但 LLM API 是要花钱的**——

- 用户的 token 怎么扣？
- 用户怎么充值？
- 微信支付怎么集成？

第 17 集我们要：

- `UsageBalanceQuotaService` 余额管理。
- `UserTokenRecord` token 消耗记录。
- `WxPayService` 微信支付集成。
- `RechargeService` 充值订单流程。

**商业化是开源项目的"成人礼"**——`pai-smart` 的实现**很完整**。

我们下期见。

---

> 多 LLM 路由是**生产级 RAG 的安全感**——`pai-smart` 的设计**简洁而强大**。
> 觉得有用，**点赞、投币、收藏**。

下一集链接：[第 17 集：充值与计费——商业化闭环](./17-recharge-payment.md)
