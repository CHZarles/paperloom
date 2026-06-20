# 第 13 集：限流与配额——Redis 守护神

> 系列：派聪明 RAG 后端演进全解
> 集数：13 / 20+
> 主题：滑动窗口、令牌桶、固定窗口、Redis 限流、token 配额预算
> 上集回顾：第 12 集混合检索上线
> 本集目标：让系统能扛住恶意请求，每个用户的 token 消耗可控

---

## 【开场 Hook】

RAG 系统有几个"资源黑手"：

1. **Embedding API**——**花钱的**（DashScope v4 按 token 计费）。
2. **LLM API**——**更花钱**（DeepSeek 按 token 计费）。
3. **ES 集群**——**CPU 密集**（kNN 搜索）。
4. **MinIO**——**带宽密集**（下载大文件）。

**如果不限流**：
- 一个脚本小子 1 秒发 1000 次 query——**打爆 ES，账单爆表**。
- 一个用户上传 1 万个文件——**配额耗尽**。
- 一次爬虫扫全部文档——**MinIO 带宽打满**。

`pai-smart` 的解法：**限流（Rate Limit） + 配额（Quota）**——**两道防线**。

今天我们拆 `RateLimitService` 和 `UsageQuotaService`。

---

## 【一、限流 vs 配额：两个不同的概念】

**限流（Rate Limit）**：
- **时间窗口内**的**请求次数**上限。
- 例：每分钟最多 10 次查询。
- **防突发流量**。

**配额（Quota）**：
- **累计**的资源使用上限。
- 例：每月 100 万 token。
- **防资源滥用**。

**两者关系**：
- **限流快失败**——**秒级响应**。
- **配额慢累计**——**月结账**。

`pai-smart` **两个都有**——**限流在前、配额在后**。

**面试考点 1**：为什么需要两道防线？

A：
- **限流防 DoS**——拒绝**异常流量**。
- **配额防滥用**——限制**正常用户的累计使用**。
- 攻击者可以通过**时间换空间**绕过单一防线（**每分钟 10 次但跑 30 分钟 = 300 次**）。

---

## 【二、`RateLimitProperties`：配置抽象】

```java
@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitProperties {
    private Window register;     // 注册限流
    private Window login;        // 登录限流
    // ... 其他限流配置
    
    public static class Window {
        private long max;
        private long windowSeconds;
    }
}
```

**配置示例**（`application.yml`）：

```yaml
rate-limit:
  register:
    max: 5
    window-seconds: 60
  login:
    max: 10
    window-seconds: 60
  chat:
    max: 30
    window-seconds: 60
```

**把限流参数配置化**——**不改代码调限流**。**生产环境必备**。

**面试考点 2**：为什么不用 `@Value` 用 `@ConfigurationProperties`？

A：
- **多字段聚合**——`register.max`、`register.windowSeconds` 是一组。
- **类型安全**——`long max` vs `@Value("${...}") long max`。
- **IDE 友好**——配置自动补全。

---

## 【三、`checkSingleWindow`：固定窗口限流】

```java
private void checkSingleWindow(String key, long max, long windowSeconds, String message) {
    Long current = stringRedisTemplate.opsForValue().increment(key);
    if (current == null) {
        // 失败降级
        return;
    }
    if (current == 1L) {
        // 第一次设置 TTL
        stringRedisTemplate.expire(key, windowSeconds, TimeUnit.SECONDS);
    }
    if (current > max) {
        throw new RateLimitExceededException(message);
    }
}
```

**核心逻辑**：
1. **`INCR`**：原子加 1（Redis 单命令原子）。
2. **第一次设置 TTL**。
3. **超限抛异常**。

**Redis Key 设计**：`{业务}:{维度}:{标识}`——`chat:user:123`——**方便排查**。

**面试考点 3**：为什么 `INCR` 之后 `EXPIRE` 不原子？

A：**两条命令**！如果 `INCR` 完应用挂了，**key 永不过期**——**内存泄漏**。

**生产环境用 Lua 脚本**：

```lua
local current = redis.call('INCR', KEYS[1])
if current == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end
return current
```

`pai-smart` 这里**有 race condition 风险**——**INCR 和 EXPIRE 不是原子的**。**生产环境应该用 Lua**。

**更现代的方案**：`SET key value EX seconds NX`——**一条命令搞定**。**但 INCR 计数要返回当前值**——**只能 Lua**。

### 3.1 固定窗口的"突刺"问题

**固定窗口的经典 bug**：

> 限制每分钟 10 次。**00:59 发了 10 次，01:01 又发了 10 次**——**2 秒内 20 次**——**超限了**。

**这种"窗口边界突刺"**是固定窗口的固有缺陷。

**面试考点 4**：固定窗口 vs 滑动窗口 vs 令牌桶？

A：
- **固定窗口**：实现简单，**有突刺问题**。
- **滑动窗口**：用**多个小窗口**模拟（**Redis ZSET 实现**），**更精确**，**更耗内存**。
- **令牌桶**：以**恒定速率**往桶里放令牌，**请求取令牌**——**能容忍突发**。
- **漏桶**：以**恒定速率**处理请求——**绝对平滑**。

`pai-smart` **固定窗口**——**简单够用**。**生产高并发**考虑**滑动窗口**。

---

## 【四、`checkChatByUser`：限流 + 配额联动】

```java
public void checkChatByUser(String userId) {
    RateLimitConfigService.WindowLimitView limit = rateLimitConfigService.getCurrentSettings().chatMessage();
    checkSingleWindow("chat:user:" + userId, limit.max(), limit.windowSeconds(), "聊天请求过于频繁");
    usageQuotaService.recordChatRequest(userId);  // 配额记账
}
```

**两道防线**：
1. **`checkSingleWindow`**：限流——**每分钟 30 次**。
2. **`recordChatRequest`**：配额记账——**累加到每日/月度计数**。

**`RateLimitConfigService.getCurrentSettings()`**：从 DB 读**当前生效的限流配置**——**管理员可后台调整**。

---

## 【五、`reserveLlmUsage`：LLM token 预算**

```java
public UsageQuotaService.TokenReservationBundle reserveLlmUsage(
        String userId, int estimatedPromptTokens, int maxCompletionTokens) {
    RateLimitConfigService.TokenBudgetView limit = rateLimitConfigService.getCurrentSettings().llmGlobalToken();
    return usageQuotaService.reserveLlmTokensWithGlobalBudget(
            userId,
            estimatedPromptTokens, maxCompletionTokens,
            limit.minuteMax(), limit.minuteWindowSeconds(),  // 全网分钟预算
            limit.dayMax(), limit.dayWindowSeconds());        // 全网当日预算
}
```

**注意：是「全网」预算！**

**`llmGlobalToken`** = **所有用户共享**的 token 预算。**和单用户配额不同**。

**为什么需要"全网预算"？**

**DeepSeek 的账单是平台付的**——**如果 1 万个用户同时疯狂调 LLM**——**账单可能爆**。

**全网预算**是**成本控制**的最后一道：
- **单用户限流**挡住"个人疯狂"。
- **全网预算**挡住"集体疯狂"。

**面试考点 5**：全网预算怎么做？

A：
- **`INCRBY`** 一个全局 key，**设置 TTL**。
- 超过阈值 → **拒绝新请求**（**降级**到其他模型 / 排队等待）。

---

## 【六、`reserveEmbeddingUploadUsage` 和 `reserveEmbeddingQueryUsage`】

```java
public UsageQuotaService.TokenReservationBundle reserveEmbeddingUploadUsage(String userId, List<String> texts) {
    RateLimitConfigService.TokenBudgetView limit = rateLimitConfigService.getCurrentSettings().embeddingUploadToken();
    return usageQuotaService.reserveEmbeddingTokensWithGlobalBudget(
            userId, texts, "embedding-upload",
            "Embedding上传全网分钟Token预算已达上限",
            "Embedding上传全网当日Token预算已达上限",
            limit.minuteMax(), limit.minuteWindowSeconds(),
            limit.dayMax(), limit.dayWindowSeconds());
}
```

**双轨限流**：
- **`embeddingUploadToken`**：上传文档时 embedding——**全网预算**。
- **`embeddingQueryToken`**：搜索时 embedding——**全网预算**。

**为什么分开？**
- **UPLOAD 烧钱多**（**一次上传可能 1 万 token**）。
- **QUERY 烧钱少**（**一次搜索几百 token**）。

**分开预算 = 精细化成本控制**。

---

## 【七、`UsageQuotaService`：`recordChatRequest` 和 `recordChatRequest`】

虽然没看到完整实现，但推测 `UsageQuotaService` 用 Redis 维护：

- **`chat:user:{userId}:day:{date}`**：INCR，**每日聊天次数**。
- **`embedding:user:{userId}:month:{yyyyMM}`**：INCRBY token，**每月 token 消耗**。

**Redis 比 DB 快**，**且支持原子计数**。

**面试考点 6**：为什么用 Redis 不用 DB 计数？

A：
- **Redis 单命令原子**（INCR、INCRBY）。
- **DB 计数要锁**（悲观锁 / 乐观锁）。
- **Redis 可以设 TTL**——**自然清旧数据**。
- **DB 计数要定时清理**。

---

## 【八、`RateLimitExceededException`：优雅的拒绝】

```java
throw new RateLimitExceededException(message);
```

`exception/` 包的 `RateLimitExceededException`：

```java
public class RateLimitExceededException extends RuntimeException {
    private final long retryAfterSeconds;
    // ...
}
```

**为什么要自定义异常？**

- **包含 retryAfter**——前端知道**等多久**。
- **全局异常处理器**（`@ControllerAdvice`）**统一返回 429**。
- **带业务信息**——前端能弹具体提示。

**面试考点 7**：HTTP 429 是什么？

A：**Too Many Requests**——**RFC 6585 定义的限流响应码**。**带 `Retry-After` 头**告诉客户端**等多久**。

---

## 【九、降级策略：超限后怎么办？**

**超限后**通常三种选择：

1. **直接拒绝**——返回 429。
2. **排队等待**——返回 202 Accepted + Job ID——**后台跑完通知**。
3. **降级到低配**——embedding 用本地小模型，LLM 用更便宜的模型。

`pai-smart` 当前是**方案 1**（**直接拒绝**）——**简单**。

**生产环境**：
- 限流超限 → **方案 1**（保护资源）。
- 配额耗尽 → **方案 2 / 3**（用户已付费）。

---

## 【十、`UsageType.UPLOAD` vs `UsageType.QUERY` 的双配额】

回顾第 07 集的 `EmbeddingClient.UsageType`：

```java
public enum UsageType {
    UPLOAD,
    QUERY
}
```

**两种使用场景、两种配额**：

- **`UPLOAD` 配额**：月限制 100 万 token——**大文件消耗**。
- **`QUERY` 配额**：月限制 50 万次 / 50 万 token——**搜索消耗**。

**为什么分开？**

- 用户搜索 1000 次**才花 1 万 token**——**但请求 1000 次**。
- 上传 1 个大文件**就花 5 万 token**。

**混合计数**会让"重度搜索用户"和"重度上传用户"抢资源。

---

## 【十一、限流的"维度"】

`pai-smart` 的限流有多个维度：

| 维度 | 例子 | 用途 |
|---|---|---|
| **IP** | `register:ip:1.2.3.4` | 防刷注册、登录 |
| **User** | `chat:user:123` | 防单个用户滥用 |
| **Action** | `embedding-upload` | 分场景精细控制 |
| **Global** | `llm-global` | 全平台成本控制 |

**维度越多越精细**——**但也越复杂**。

**面试考点 8**：限流维度怎么设计？

A：
- **最粗**：`global` 一个 key——**全平台限流**。
- **中**：`{action}:{userId}`——**按用户**。
- **最细**：`{action}:{userId}:{resourceId}`——**按资源**。

`pai-smart` **两层**：IP（防刷） + User（防滥用）。

---

## 【十二、Redis 挂了怎么办？**

`checkSingleWindow` 里：

```java
Long current = stringRedisTemplate.opsForValue().increment(key);
if (current == null) {
    return;  // 降级放行
}
```

**Redis 挂了 → INCR 返回 null → 放行**。

**这是「fail open」策略**——**Redis 故障时不再限流**。

**反之「fail closed」**——**Redis 故障时拒绝所有请求**。

**两种策略的对比**：
- **fail open**：业务可用性高，**但可能被打**。
- **fail closed**：资源安全，**但业务中断**。

`pai-smart` **fail open**——**业务优先**。**生产环境根据业务选**。

**面试考点 9**：fail open 还是 fail closed？

A：
- **支付类**：fail closed（**钱不能错**）。
- **内容类**：fail open（**宁愿有垃圾**）。
- **限流类**：fail open（**避免限流本身变故障源**）。

---

## 【十三、常见坑 & 面试问答】

**Q1：INCR 和 EXPIRE 不原子怎么办？**

A：用 Lua 脚本。**生产环境必做**——`pai-smart` 这里有 bug。

**Q2：滑动窗口怎么实现？**

A：Redis ZSET 存请求时间戳：

```lua
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1])  -- 删除窗口外
local current = redis.call('ZCARD', KEYS[1])  -- 当前数
if current < tonumber(ARGV[2]) then
    redis.call('ZADD', KEYS[1], ARGV[3], ARGV[3])
    return 1
end
return 0
```

**Q3：令牌桶怎么实现？**

A：Redis + Lua：

```lua
local tokens = redis.call('GET', KEYS[1]) or ARGV[2]
local lastRefill = redis.call('GET', KEYS[2]) or ARGV[3]
local now = ARGV[1]
-- 计算补充
tokens = min(ARGV[2], tokens + (now - lastRefill) * ARGV[4])
if tokens >= 1 then
    tokens = tokens - 1
    redis.call('SET', KEYS[1], tokens)
    redis.call('SET', KEYS[2], now)
    return 1
end
return 0
```

**Q4：限流 + 配额会不会冲突？**

A：**不会**——**两道独立防线**：
- 限流挡**短时突发**。
- 配额挡**长时累计**。

**Q5：怎么让前端知道还剩多少配额？**

A：返回 `X-RateLimit-Remaining` 头（**GitHub 风格**）：

```java
response.setHeader("X-RateLimit-Limit", max);
response.setHeader("X-RateLimit-Remaining", max - current);
response.setHeader("X-RateLimit-Reset", expireTime);
```

`pai-smart` **没看到这套**——**未来优化**。

---

## 【十四、思考题：限流是"前端友好"还是"后端友好"？**

**前端友好**：超限后等 1s 再试——**用户体验好**。
**后端友好**：超限后 429 拒绝——**保护资源**。

**平衡**：
- **限流用 fail open**（**前端友好**）。
- **配额用 fail closed**（**后端友好**）。
- **关键操作 fail closed**（**支付、删除**）。

`pai-smart` 的策略是**有意识的**——**值得学**。

---

## 【十五、下集预告】

限流配额定下来了。**但用户聊完天后，会话没保存**——下次登录啥都没有。

第 14 集我们要：

- `Conversation` 和 `ConversationSession` 实体设计。
- 历史消息的存储和查询。
- **滑动窗口**保留最近 N 条。
- **WebSocket 重连后如何续传历史**。

**对话历史是 RAG 系统的"记忆"**。

我们下期见。

---

> 限流和配额是**生产级系统的成人礼**——`pai-smart` 的 Redis 方案**简单直接**。
> 觉得有用，**点赞、投币、收藏**。

下一集链接：[第 14 集：对话会话与历史——RAG 系统的记忆](./14-conversation-session.md)
