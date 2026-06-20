# 第 07 集：EmbeddingClient 登场——把文本变成向量

> 系列：派聪明 RAG 后端演进全解
> 集数：07 / 20+
> 主题：Embedding 模型、WebClient 调 API、批处理、配额预留与结算、限流
> 上集回顾：第 06 集文档解析 + 分块完成，文本块落库
> 本集目标：把每个 chunk 调用 Embedding API 向量化，存到 DocumentVector

---

## 【开场 Hook】

上一集我们把文档切成了 chunk，每个 chunk 是 500-1000 字符的文本。

**但 LLM 不认字符串。LLM 认的是数字。**

这一步叫 **Embedding**——把一段文本变成一个**高维向量**（`pai-smart` 用的是 DashScope 的 text-embedding-v4，**1024 维 float 数组**）。

> "派聪明" → [0.012, -0.034, 0.078, ..., 0.092]（1024 个数字）

这一步**是 RAG 的「灵魂」**。**没有 embedding，RAG 只能做关键词搜索**；有了 embedding，**RAG 才开始「理解语义」**。

今天我们深入 `EmbeddingClient` 这个类，看它是怎么：

1. 调通 Embedding API。
2. 批量处理（**省 token、省钱**）。
3. **配额预留**（先扣再用）。
4. 失败重试。
5. 多 Provider 路由（**`pai-smart` 后期加了**）。

---

## 【一、什么是 Embedding？】

**简单说**：embedding 是**一个函数** `f(text) → vector`，**把字符串映射到一个高维空间**。

**两个核心特性**：

1. **语义相似 → 向量相似**。
   - `f("苹果公司")` 和 `f("Apple Inc.")` 距离很近。
   - `f("苹果公司")` 和 `f("香蕉")` 距离中等。
   - `f("苹果公司")` 和 `f("太阳系")` 距离很远。
2. **固定维度**。
   - 不管输入多长（1 个字还是 1000 字），**输出都是 1024 维**。
   - **这让所有文本可以「放在同一个空间里比较」**。

**类比**：
- 文本是「地址」（北京市朝阳区建国路 88 号）。
- Embedding 是「经纬度」（116.48, 39.91）。
- **判断两个地址远近**比「看地址字符串」更精确。

**面试考点 1**：常见 Embedding 模型有哪些？

| 模型 | 厂商 | 维度 | 上下文长度 | 特点 |
|---|---|---|---|---|
| text-embedding-3-small | OpenAI | 1536 | 8191 | 性价比高 |
| text-embedding-3-large | OpenAI | 3072 | 8191 | 精度高 |
| text-embedding-v4 | 阿里通义 | 1024/1536/2048 可选 | 8192 | **pai-smart 用的** |
| bge-large-zh | BAAI | 1024 | 512 | 中文开源 |
| m3e | Moka | 1024 | 512 | 中文开源 |

`pai-smart` 选 v4 的**关键原因**是**国产、合规、便宜**。

---

## 【二、WebClient：为什么是 WebFlux 的 client？】

```java
import org.springframework.web.reactive.function.client.WebClient;
```

`pai-smart` **同时引入了** `spring-boot-starter-web` **和** `spring-boot-starter-webflux`。`WebClient` 是 `webflux` 里的。

**为什么不直接用 `RestTemplate` 或 `HttpClient`？**

- **`RestTemplate`**：Spring 自带，**同步阻塞**，**已经进入维护模式**（Spring 5 之后不再推荐）。
- **`HttpClient`**：JDK 11+ 自带，**同步**，要用的话**自己写一堆样板**。
- **`WebClient`**：**响应式**（Reactive），**支持流式、异步、自动重试**。

**Embedding API 调用的特性**：
- 单次调用耗时 200ms-1s（**网络 + 推理**）。
- **不是 CPU 密集型**——**网络 IO 密集型**。
- **WebClient 的非阻塞优势**在这里不显著（每次还是等 API 返回）。
- **但 WebClient 的 API 比 RestTemplate 现代**——**链式调用、可读性好**。

**pai-smart` 选 WebClient 的实际理由**：
1. **DeepSeek 流式响应**（第 10 集）需要 Reactive Streams。
2. **统一栈**——既然 webflux 已经引了，**调外部 API 都用它**。
3. **WebClient 也能同步用**——`block()`。

**面试考点 2**：`RestTemplate` vs `WebClient`？

A：Spring 官方 5+ 推荐 `WebClient`。`RestTemplate` 还能用但**仅维护不开发新功能**。`pai-smart` 这种**新项目**用 `WebClient` 是对的。

---

## 【三、`embedWithUsage`：核心流程】

```java
public EmbeddingUsageResult embedWithUsage(List<String> texts, String requesterId, UsageType usageType) {
    try {
        String normalizedRequesterId = requesterId == null || requesterId.isBlank() ? "unknown" : requesterId;
        logger.info("开始生成向量，文本数量: {}", texts.size());

        List<float[]> all = new ArrayList<>(texts.size());
        int totalTokens = 0;
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(start + batchSize, texts.size());
            List<String> sub = texts.subList(start, end);
            
            // 1. 预留配额
            UsageQuotaService.TokenReservationBundle reservation = usageType == UsageType.QUERY
                    ? rateLimitService.reserveEmbeddingQueryUsage(normalizedRequesterId, sub)
                    : rateLimitService.reserveEmbeddingUploadUsage(normalizedRequesterId, sub);
            
            // 2. 调 API
            try {
                String response = callApiOnce(sub);
                EmbeddingApiResponse parsedResponse = parseEmbeddingResponse(response, sub);
                
                // 3. 结算配额（按实际 token 数）
                usageQuotaService.settleReservation(reservation, parsedResponse.totalTokens());
                all.addAll(parsedResponse.vectors());
                totalTokens += parsedResponse.totalTokens();
            } catch (Exception e) {
                // 4. 失败 → 释放预留
                usageQuotaService.abortReservation(reservation);
                throw e;
            }
        }
        return new EmbeddingUsageResult(all, totalTokens, currentModelVersion());
    } catch (Exception e) {
        logger.error("生成向量失败", e);
        throw new RuntimeException("生成向量失败", e);
    }
}
```

### 3.1 批处理：`batchSize = 100`

```java
@Value("${embedding.api.batch-size:100}")
private int batchSize;

for (int start = 0; start < texts.size(); start += batchSize) {
    int end = Math.min(start + batchSize, texts.size());
    List<String> sub = texts.subList(start, end);
    // ...
}
```

**为什么 100 一批？**

- **API 端**：DashScope v4 的 batch 上限是 10、25、100（**不同模型不同**）。**100 是常见上限**。
- **网络**：100 个文本 1 次请求 vs 100 次请求，**省了 99 次 HTTP 开销**。
- **超时**：1 个 100 文本请求 vs 100 个 1 文本请求，**前者更不容易超时**。

**`subList`** 是 `List` 的视图——**不复制数据**。**省内存**。

**面试考点 3**：批处理大小怎么选？

A：
- **API 文档**告诉你最大 batch——**用最大值**。
- **网络稳定**就调大，**网络差**就调小（**大 batch 失败成本高**）。
- **超时敏感**就调小，**长任务**就调大。
- **经验值**：Embedding **32-100**，LLM **1-10**（LLM 输出长，batch 不能太大）。

`pai-smart` 用 100 是合理的——**业务规模和 API 限制的平衡**。

### 3.2 「预留 → 结算」两步走

```java
// 1. 预留
UsageQuotaService.TokenReservationBundle reservation = usageType == UsageType.QUERY
        ? rateLimitService.reserveEmbeddingQueryUsage(normalizedRequesterId, sub)
        : rateLimitService.reserveEmbeddingUploadUsage(normalizedRequesterId, sub);

// 2. 调 API
String response = callApiOnce(sub);
EmbeddingApiResponse parsedResponse = parseEmbeddingResponse(response, sub);

// 3. 结算（按实际 token）
usageQuotaService.settleReservation(reservation, parsedResponse.totalTokens());

// 失败时
} catch (Exception e) {
    usageQuotaService.abortReservation(reservation);
    throw e;
}
```

**这是配额系统的"事务模式"**——**先扣后用，用完结算，失败回滚**。

**为什么要"预留"？**

**因为 Embedding API 是异步的**——**请求时不知道实际多少 token**（**API 响应才告诉你**）。如果**不预留**：
- 100 个用户**同时**上传大文件 → 都**并发**调 Embedding → 系统**超额使用**配额。
- 等到 API 返回才知道用了多少 → 配额的**实时性差**。

**预留机制的工作流程**：
1. **预估**：`sub.size() * 平均字符数 / 4` 估算 token（**粗估**）。
2. **预扣**：从用户配额里**先扣掉这个估计值**。
3. **结算**：API 回来后**按实际值结算**——多退少补。
4. **失败回滚**：调 API 出错，**预留全退**。

**这是金融系统的"预授权"模式**——**信用卡刷预授权 → 实际消费后结算 → 取消时解冻**。

**面试考点 4**：为什么不用"先实际跑、然后扣费"？

A：那是**串行**的——必须等所有 API 调用完才能知道结果。**预留机制可以并发**——**100 个用户**同时上传，**互相不影响**。**配额的"实时性"是底线**——**不能让用户超用**。

### 3.3 失败重试

代码里用 `reactor.util.retry.Retry`（在调用 `callApiOnce` 内部）：

```java
.webClient.post()
    .uri(apiUrl)
    .bodyValue(requestBody)
    .retrieve()
    .bodyToMono(String.class)
    .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
        .filter(throwable -> throwable instanceof WebClientResponseException))
    ...
```

**`Retry.backoff(3, Duration.ofSeconds(1))`**：

- 最多重试 3 次。
- 退避策略：第 1 次 1s 后重试，第 2 次 2s，第 3 次 4s（**指数退避**）。
- **只对 `WebClientResponseException` 重试**（**网络错误、5xx 错误**）——**业务错误（4xx）不重试**。

**面试考点 5**：为什么指数退避？

A：避免**「重试风暴」**。100 个客户端同时重试 → 服务端压力 × 2 → 雪崩。
**指数退避 + 随机抖动**让重试分散开。

---

## 【四、批处理的几个陷阱】

### 4.1 `subList` 的坑

```java
List<String> sub = texts.subList(start, end);
```

`subList` 是 `AbstractList` 的**视图**——**不是新 list**。**对 subList 的修改会影响原 list**。

**`pai-smart` 没问题**——它只读不写。但如果代码里出现 `subList.add(...)` 或 `subList.clear()`，**会有 ConcurrentModificationException** 或**意外修改原 list**。

**面试考点 6**：`subList` 的限制？

A：
- `subList` 是**原 list 的视图**，**结构修改会相互影响**。
- 对**原 list**的**结构修改**（add/remove）后，**subList 不可用**。
- **元素修改**（set）没问题。
- **需要独立 list 时用** `new ArrayList<>(subList)`。

### 4.2 批内 token 累计

```java
usageQuotaService.settleReservation(reservation, parsedResponse.totalTokens());
all.addAll(parsedResponse.vectors());
totalTokens += parsedResponse.totalTokens();
```

每个 batch 单独结算，**最后累加到 `totalTokens`**。**这是 "BATCH 级别配额"**——**细粒度**，**某批失败不影响其他批**。

**但有个问题**：**批内部分失败**怎么办？比如 100 个文本里第 50 个出问题？

**`pai-smart` 的策略**：**整批失败**。**不是"部分成功"**。

**更优雅的策略**：把 batch 再拆小，**逐个调用**。**但调用次数多 100 倍**。**业务上**整批重试**已经够用**。

---

## 【五、`callApiOnce`：构造请求 + 处理响应】

虽然没贴完整代码，但根据 `embedWithUsage` 的调用可以推断：

```java
private String callApiOnce(List<String> texts) {
    // 1. 构造请求体
    Map<String, Object> body = new HashMap<>();
    body.put("model", currentModelVersion());  // "text-embedding-v4"
    body.put("input", texts);
    body.put("dimension", "1024");
    body.put("encoding_format", "float");

    // 2. 构造 HTTP 请求
    return webClient.post()
        .uri(provider.apiBaseUrl() + "/compatible-mode/v1/embeddings")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + provider.apiKey())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .retrieve()
        .bodyToMono(String.class)
        .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
            .filter(t -> t instanceof WebClientResponseException))
        .block(Duration.ofSeconds(60));
}
```

**几个关键点**：

- **`provider.apiBaseUrl()`** + **`compatible-mode/v1/embeddings`**：DashScope 的**OpenAI 兼容端点**。
- **`Bearer apiKey`**：标准鉴权。
- **`bodyToMono`**：返回 `Mono<String>`，**WebFlux 的响应式类型**。
- **`.block(60s)`**：**同步等结果，最多 60 秒**。
- **`encoding_format = "float"`**：返回 float[] 而不是 base64 字符串。

**面试考点 7**：DashScope 的 "compatible-mode" 是什么？

A：阿里云为了**兼容 OpenAI API** 搞的"翻译层"——**同样的 OpenAI client 代码可以指向 DashScope 的 URL**。**pai-smart` 这么干的好处**：**换 provider 不用改代码**，**只换 baseUrl**。

---

## 【六、`ModelProviderConfigService`：多 Provider 路由**

```java
@PostConstruct
public void init() {
    ModelProviderConfigService.ActiveProviderView provider = 
        modelProviderConfigService.getActiveProvider(ModelProviderConfigService.SCOPE_EMBEDDING);
    logger.info("EmbeddingClient 初始化 - Provider: {}, 模型: {}, 批次大小: {}, 维度: {}, API地址: {}", 
        provider.provider(), provider.model(), batchSize, provider.dimension(), provider.apiBaseUrl());
}
```

**这段代码的玄机**：

- **`SCOPE_EMBEDDING`**：这是「**Provider 作用域**」——同一个系统里**Embedding 用 A 提供商、LLM 用 B 提供商**。
- **`getActiveProvider`**：从**数据库**（`ModelProviderConfig` 表）读**当前激活的 Provider 配置**。
- **包含字段**：`provider`、`model`、`apiBaseUrl`、`apiKey`、`dimension`。

**为什么要在数据库里？**

**第 16 集会详细讲**。`pai-smart` 后来加了**多 LLM Provider 路由**——**同一个系统可以同时支持 DeepSeek、Qwen、OpenAI、Claude**。**管理员可以在管理后台切换**。

**但 Embedding 端提前就有了 Provider 路由**——**这是个渐进式设计**。

**面试考点 8**：为什么不直接用配置文件？

A：配置文件是**部署时**确定的。**数据库配置是运行时**可改的。**好处**：
- **A/B 测试**：新 provider 灰度切流量。
- **降级**：主 provider 挂了，**手动切到备用**。
- **多租户**：不同租户用不同 provider（**Enterprise 场景**）。

---

## 【七、`UsageType` 枚举：上传 vs 查询】

```java
public enum UsageType {
    UPLOAD,
    QUERY
}
```

**为什么要区分？**

**因为配额策略不同**：

- **UPLOAD**：上传文档 → 大量 embedding → **配额消耗大**。**可能走专门的"上传额度"**（**月限制 100 万 token**）。
- **QUERY**：用户搜索 → 单次 embedding → **配额消耗小**。**可能走专门的"查询额度"**（**月限制 50 万次**）。

**为什么不合并成一种？**

A：**业务规则**。比如「上传额度 10 万 token / 月，**查询额度无限**」——**鼓励用户用系统**。**如果合并**，**一次上传就把额度用完**。

**pai-smart` 选 UPLOAD/QUERY 二分法**——**简化设计**。**生产环境可能更细**（`UPLOAD/QUERY/ADMIN/EXPORT/...`）。

---

## 【八、`parseEmbeddingResponse`：解析 API 响应】

DashScope 的标准响应（OpenAI 格式）：

```json
{
  "object": "list",
  "data": [
    { "object": "embedding", "embedding": [0.012, -0.034, ...], "index": 0 },
    { "object": "embedding", "embedding": [0.045, -0.067, ...], "index": 1 }
  ],
  "model": "text-embedding-v4",
  "usage": {
    "prompt_tokens": 1000,
    "total_tokens": 1000
  }
}
```

**`parseEmbeddingResponse` 干什么**：

```java
private EmbeddingApiResponse parseEmbeddingResponse(String response, List<String> originalTexts) {
    JsonNode root = objectMapper.readTree(response);
    JsonNode data = root.get("data");
    JsonNode usage = root.get("usage");
    
    int totalTokens = usage.get("total_tokens").asInt();
    List<float[]> vectors = new ArrayList<>();
    for (JsonNode item : data) {
        JsonNode embedding = item.get("embedding");
        float[] vec = new float[embedding.size()];
        for (int i = 0; i < embedding.size(); i++) {
            vec[i] = (float) embedding.get(i).asDouble();
        }
        vectors.add(vec);
    }
    return new EmbeddingApiResponse(vectors, totalTokens);
}
```

**几个细节**：

- **顺序保持**：DashScope 返回的 `data` 数组**和请求的 `input` 顺序一致**——**这很重要**，否则会乱套。
- **`(float) embedding.get(i).asDouble()`**：JSON 默认是 `double`，**转 float 节省一半内存**。
- **`totalTokens`**：实际消耗的 token，**用来结算配额**。

**面试考点 9**：为什么 float 不用 double？

A：精度权衡。
- **double**：8 字节 / 数字。**1024 维** → 8KB / 向量。**1 万个向量** → 80MB。
- **float**：4 字节 / 数字。**1024 维** → 4KB / 向量。**1 万个向量** → 40MB。

**精度损失**：float 的精度约 1e-7，**对于相似度计算完全够用**（相似度是相对值，**不是绝对值**）。**生产环境 99% 用 float**。

---

## 【九、向量存到哪？DocumentVector 表】

第 06 集我们看到了 `DocumentVector` 实体，关键字段：

```java
@Column(name = "vector_id")
private Long vectorId;
private String fileMd5;
private Integer chunkId;
@Lob
private String textContent;
private Integer pageNumber;
private String anchorText;
private String modelVersion;
private String userId;
// ...
```

**`vector` 字段没看到**？可能：
- **存 MySQL**：用 `BLOB` 或 `JSON`（**性能差**）。
- **存 Elasticsearch**：用 `dense_vector` 类型（**专业工具**）。

**pai-smart` 选 Elasticsearch**——**第 08 集讲**。**MySQL 存向量是个反模式**。

**MySQL 8.0+ 已经有向量类型**，但**性能远不如 ES**（**ES 用 HNSW 索引**）。

---

## 【十、常见坑 & 面试问答】

**Q1：批处理大小可以超过 100 吗？**

A：DashScope v4 当前限制是 25/100/300（**不同 dimension 不同**）。**`pai-smart` 用 100 是平衡**。**如果未来 DashScope 提高限制，可以**无脑改 `application.yml`**。

**Q2：失败重试的"幂等性"问题？**

A：Embedding API 本身**不是幂等的**（**同一段文本多次调用也多次计费**）。**重试会增加成本**。
**`pai-smart` 用配额预留 + 失败回滚**——**避免重复扣费**。**但 API 端已经收费了**（**API 调用成功但客户端没收到响应**）——**这种"幽灵调用"无法完全避免**。

**Q3：`WebClient` 阻塞调用 (`block`) 不是反模式吗？**

A：是的，**严格说**是反模式。**但 `pai-smart` 是 WebMVC 应用**（`@RestController`）——**WebFlux 异步优势用不上**。
**真正的异步应该是** `bodyToFlux()` + Reactive Service + Reactive Controller。**`pai-smart` 没这么做**——**简化**。

**Q4：Embedding API 限流了怎么办？**

A：
- **`Retry.backoff`**：自动重试。
- **`RateLimitService.reserveEmbeddingUploadUsage`**：**配额预留阶段就拒绝**。
- **降级到本地模型**：用 bge-large-zh 等开源模型，**不花钱**。`pai-smart` 暂时没做。

**Q5：向量召回效果不好，怎么调？**

A：
- **换 Embedding 模型**：v3 → v4，**质量提升明显**。
- **调 chunk 大小**：500 → 800 字符，**保留更多上下文**。
- **调 overlap**：100 → 200 字符，**减少边界切分问题**。
- **加 rerank**：召回后再用 BGE-Reranker 精排。
- **混合检索**：BM25 + 向量（**第 12 集讲**）。

---

## 【十一、思考题：为什么 `EmbeddingClient` 不缓存结果？**

**场景**：同一个 chunk 被向量化两次。

**第一次**（上传时）：调 API，**存到 DB**。
**第二次**（重建索引时）：**又调一次 API**。

**为什么不缓存？**

A：**缓存是脏活**——失效、版本、内存都要管。**`pai-smart` 选择"按需调用"**：
- **同样的 chunk**：用 `fileMd5 + chunkId` 做去重，**避免重复存**。
- **不同 chunk**：必须调 API（**没有"复用"的可能**）。

**更优的设计**：**在 `DocumentVector` 加 UNIQUE(fileMd5, chunkId, modelVersion)**——**重复插入冲突就跳过**。**`pai-smart` 应该这么做**。

---

## 【十二、下集预告】

向量生成了，**存哪？** MySQL 存向量是反模式，**用 Elasticsearch**。

第 08 集我们要：

- `EsConfig` 怎么初始化 ES Client。
- `EsIndexInitializer` 怎么建索引（**包括 `dense_vector` 字段**）。
- `ElasticsearchService` 怎么把向量**写进** ES。
- **kNN 召回**怎么写查询。

**ES 是 RAG 系统的「数据库」**——**没有它就没有现代 RAG**。

我们下期见。

---

> 这一期的"配额预留 + 结算"是**金融级设计**——**很多 RAG 系统的 token 计费都很粗糙**，**`pai-smart` 这套值得学**。
> 觉得有用，**点赞、投币、收藏**。

下一集链接：[第 08 集：Elasticsearch 集成——向量索引与召回](./08-elasticsearch-vector.md)
