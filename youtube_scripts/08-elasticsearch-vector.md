# 第 08 集：Elasticsearch 集成——向量索引与召回

> 系列：派聪明 RAG 后端演进全解
> 集数：08 / 20+
> 主题：ES 8.x dense_vector、HNSW 索引、kNN 召回、IkAnalyzer 分词
> 上集回顾：第 07 集 EmbeddingClient 调通了，1024 维向量生成了
> 本集目标：把向量存到 ES，召回用 kNN 搜索

---

## 【开场 Hook】

向量有了，**存哪？**

三种选择：
1. **MySQL**：8.0+ 有向量类型，**但慢**——B-tree 索引不适合高维向量。
2. **专用向量数据库**（Milvus、Qdrant、Chroma）：**功能专一**，**但运维成本高**。
3. **Elasticsearch 8.x**：**dense_vector + HNSW**——**和传统文本搜索同一个栈**。

`pai-smart` 选 ES——**一套系统同时搞定文本搜索 + 向量召回 + 元数据过滤**。

今天我们深挖：
- `EsConfig` 怎么初始化 ES Client。
- `EsIndexInitializer` 怎么建索引。
- `ElasticsearchService` 怎么写、怎么查。

---

## 【一、ES 8.x 的 `dense_vector`：向量字段的类型】

ES 在 8.0 引入 `dense_vector` 字段类型，专门存 float 数组。

**mapping 示例**（在 `EsIndexInitializer` 里推测）：

```json
{
  "mappings": {
    "properties": {
      "fileMd5":    { "type": "keyword" },
      "chunkId":    { "type": "integer" },
      "textContent":{ "type": "text", "analyzer": "ik_max_word" },
      "vector":     { "type": "dense_vector", "dims": 1024, "index": true, "similarity": "cosine" },
      "userId":     { "type": "keyword" },
      "orgTag":     { "type": "keyword" },
      "isPublic":   { "type": "boolean" },
      "pageNumber": { "type": "integer" }
    }
  }
}
```

**几个关键参数**：

- **`dims: 1024`**：向量维度。**必须和 Embedding 模型匹配**。
- **`index: true`**：建 HNSW 索引（HNSW = Hierarchical Navigable Small World，**图算法**）。
- **`similarity: cosine`**：余弦相似度。**也可以选** `dot_product` 或 `l2_norm`。
- **`textContent` 用 ik_max_word**：中文分词，**细粒度**。

**面试考点 1**：HNSW vs IVF vs PQ？

A：
- **HNSW**（图算法）：**召回率高**，**内存占用大**，**ES 默认**。
- **IVF**（倒排索引）：**快**，**召回率略低**，Milvus 默认。
- **PQ**（乘积量化）：**压缩向量**，**内存小**，**精度低**。

`pai-smart` 选 HNSW——**质量优先**。

---

## 【二、`EsConfig`：8.x Java Client 初始化】

```java
@Bean
public ElasticsearchClient elasticsearchClient() {
    RestClientBuilder builder = RestClient.builder(new HttpHost(host, port, scheme));

    if (username != null && !username.isEmpty()) {
        BasicCredentialsProvider credsProvider = new BasicCredentialsProvider();
        credsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(username, password));
        builder.setHttpClientConfigCallback(httpClientBuilder -> {
            if ("https".equalsIgnoreCase(scheme) && insecureTrustAllCertificates) {
                SSLContext sslContext = SSLContexts.custom()
                        .loadTrustMaterial(null, (X509Certificate[] chain, String authType) -> true)
                        .build();
                httpClientBuilder.setSSLContext(sslContext);
                httpClientBuilder.setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE);
            }
            return httpClientBuilder.setDefaultCredentialsProvider(credsProvider);
        });
    }

    RestClient restClient = builder.build();
    ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
    return new ElasticsearchClient(transport);
}
```

**三层结构**：
1. **`RestClient`**（Apache HttpClient 包装）：**低级客户端**，处理 HTTP。
2. **`RestClientTransport`**：传输层，**序列化请求**。
3. **`ElasticsearchClient`**：**高级客户端**，**强类型 API**。

**面试考点 2**：ES Java Client 的演变？

- **TransportClient**（7.x 之前）：**基于 Netty**，**官方已废弃**。
- **RestClient** + **RestClientTransport**（7.x+）：**基于 HTTP**，**官方推荐**。
- **`elasticsearch-java`**（8.x）：**新强类型 API**，`pai-smart` 用的就是它。

---

## 【三、`EsIndexInitializer`：建索引的时机】

`EsIndexInitializer` 是个 `ApplicationRunner` 或 `@PostConstruct`——**应用启动时建索引**。

**为什么不在 `ddl-auto` 一样启动时建？**

A：ES 的 mapping 比 SQL DDL 复杂——**dense_vector 维度、analyzer、HNSW 参数**都要明确指定。**手工建更可控**。

**index 不存在** → 创建（with mapping）。
**index 已存在** → 不动（**避免覆盖数据**）。

**生产环境**用 `EsIndexInitializer` 自动化**首次部署**很方便，**后续修改 mapping** 要用 migration 工具（**ES 没有 Flyway**——**自己写脚本**）。

---

## 【四、`ElasticsearchService`：写入向量】

```java
public void indexDocument(EsDocument doc) {
    esClient.index(i -> i
        .index("knowledge_base")
        .id(doc.getId())
        .document(doc)
    );
}
```

**`EsDocument` 实体**（推测）：
```java
@Data
public class EsDocument {
    private String id;          // ES doc id
    private String fileMd5;
    private Integer chunkId;
    private String textContent;
    private List<Float> vector; // 1024 维
    private String userId;
    private String orgTag;
    private Boolean isPublic;
    private Integer pageNumber;
}
```

**`doc.getId()` 怎么生成？** 推测：

```java
String id = fileMd5 + "_" + chunkId;
```

**这样设计的好处**：
- **同一个 chunk 重复写入** → 覆盖原 doc，**幂等**。
- **删除文件** → `delete_by_query: { fileMd5: xxx }`，**简单**。

**面试考点 3**：ES doc id 的设计原则？

A：
- **有业务含义**（如 `fileMd5_chunkId`）：**好做幂等和删除**。
- **UUID 随机**：**唯一性强**，**但删除要按 query**。
- **自增 Long**：**不推荐**（ES 分布式不友好）。

`pai-smart` 选「业务 id」是**正确选择**。

---

## 【五、kNN 召回：核心查询】

```java
public List<SearchResult> searchWithPermission(String query, String userId, int topK) {
    List<Float> queryVector = embedToVectorList(query, userId);
    
    SearchResponse<EsDocument> response = esClient.search(s -> {
        s.index("knowledge_base");
        int recallK = topK * 30;  // 粗排召回 K*30
        s.knn(kn -> kn
            .field("vector")
            .queryVector(queryVector)
            .k(recallK)
            .numCandidates(recallK)
        );
        // ... 权限过滤
    }, EsDocument.class);
    
    return response.hits().hits().stream()
        .map(this::toSearchResult)
        .toList();
}
```

**关键参数**：

- **`k: topK * 30`**：**粗排**。**多召回 30 倍**（**30 是经验值**），**让 Rerank 有空间**。
- **`numCandidates: topK * 30`**：HNSW 搜索的候选数，**通常 = k**。
- **`field: "vector"`**：用 vector 字段做 ANN（Approximate Nearest Neighbor）。

**ES 8.x 的 kNN 是"过滤器后召回"还是"召回后过滤"？**

A：**过滤器后召回**。`knn` 和 `query`（bool filter）是**AND 关系**——**先过滤用户能看的数据，再在过滤后的子集里做 kNN**。

**这是 RAG 系统的"多租户 + 向量召回"**的**正确姿势**。

**面试考点 4**：为什么 kNN + filter 不会很慢？

A：ES 8.x 的 kNN 优化是**「先按 filter 选 doc id 集合，再在子集上建 HNSW 索引」**。**子集小** → 搜索快。

---

## 【六、`HybridSearchService`：BM25 + 向量】

虽然第 12 集会专门讲，但 episode 8 我们先看 `HybridSearchService` 的"全貌"：

```java
public List<SearchResult> searchWithPermission(String query, String userId, int topK) {
    List<Float> queryVector = embedToVectorList(query, userId);

    if (queryVector == null) {
        return textOnlySearchWithPermission(query, userId, topK);  // 降级
    }

    SearchResponse<EsDocument> response = esClient.search(s -> {
        s.index("knowledge_base");
        int recallK = topK * 30;
        s.knn(kn -> kn
            .field("vector")
            .queryVector(queryVector)
            .k(recallK)
            .numCandidates(recallK)
        );
        s.query(q -> q.match(m -> m.field("textContent").query(query)));  // BM25
        s.rescore(r -> r.windowSize(recallK).query(rq -> rq
            .queryWeight(0.2d)              // 原始 BM25 权重 0.2
            .rescoreQueryWeight(1.0d)        // rescore 权重 1.0
            .query(rqq -> rqq.match(m -> m.field("textContent").query(query)))
        ));
        // ... 权限过滤
    }, EsDocument.class);

    return response.hits().hits().stream()
        .map(this::toSearchResult)
        .toList();
}
```

**这是 `pai-smart` 早期版本**——**`knn` + `query` + `rescore`** 三件套。

**三件套的协作**：
1. **`knn`**：粗排，**召回数 = topK * 30**。
2. **`query`**：BM25 文本匹配，**同样的粗排范围**。
3. **`rescore`**：精排，**用 BM25 重打分，权重 1.0**。

**为什么是 `queryWeight = 0.2`？** 让 BM25 在粗排阶段影响小，**在 rescore 阶段影响大**。**因为 rescore 算得更精细**。

**面试考点 5**：ES 的 rescore 是什么？

A：**二次打分**。`windowSize` 内的 doc 用 `rescoreQuery` 重算 score，**然后用 `queryWeight` 和 `rescoreQueryWeight` 加权**。**比单次 BM25 准**，**比改主查询代价小**。

---

## 【七、向量降级：API 失败怎么办？】

```java
List<Float> queryVector = embedToVectorList(query, userId);
if (queryVector == null) {
    return textOnlySearchWithPermission(query, userId, topK);
}
```

**降级策略**：
1. Embedding API 调用失败 → `queryVector` 是 `null`。
2. **降级到纯文本搜索**（`textOnlySearchWithPermission`）。
3. **用户体验不会完全崩溃**——只是召回质量下降。

**这是「优雅降级」的最佳实践**。

**面试考点 6**：还有什么降级策略？

- **缓存**：用 Redis 缓存最近 1000 条 query 的向量，**减少 API 调用**。
- **本地模型**：用 bge-small 这种**几十 MB 的本地模型**——**不花钱，速度快，质量差一些**。
- **查询改写**：把用户问题改写得更具体（**LLM 重写**）。

`pai-smart` 当前是"降级到 BM25"——**最简方案**。

---

## 【八、权限过滤：multi-tenant 的核心**

```java
// 在 search() 里
s.query(q -> q.bool(b -> b
    .filter(f -> f.terms(t -> t.field("isPublic").terms(v -> v.value(...))))
    .filter(f -> f.terms(t -> t.field("orgTag").terms(v -> v.value(...))))
    .filter(f -> f.terms(t -> t.field("userId").terms(v -> v.value(...))))
));
```

**三条 OR 起来的 filter**：
- `isPublic = true`（公开）
- `orgTag IN (用户的所有 orgTag)`（同组织）
- `userId = currentUser`（自己的）

**任何一个满足，就能搜到**。

**为什么用 `filter` 不用 `must`？**

A：`filter` **不参与打分**，**只做过滤**——**更快**。**`must` 既过滤又打分**——**浪费算力**。

**面试考点 7**：`filter` vs `must` 性能差异？

A：
- **`must`**：打分计算 score，**进入相关性排序**。
- **`filter`**：只做 yes/no 判断，**不计算 score**，**可缓存**。

**「用户有权限看吗？」这种二元判断一定用 `filter`**。

---

## 【九、ES 写入：bulk vs 单条**

```java
// 单条
esClient.index(i -> i.index("kb").id(id).document(doc));

// 批量
esClient.bulk(b -> b.operations(ops));
```

**单条 vs bulk 性能差异巨大**：
- **单条**：1000 个文档 → 1000 次 HTTP → ~10s
- **bulk**：1000 个文档 → 1 次 HTTP → ~0.5s

**但 `pai-smart` 的向量写入是异步的**（第 09 集 Kafka），**单条也能接受**。**生产环境** 大批量导入要用 `bulk`。

**面试考点 8**：bulk 请求大小怎么选？

A：单次 bulk 1-15MB 最佳。
- **太小**：网络 round-trip 浪费。
- **太大**：单请求处理慢，**其他请求被阻塞**。

`pai-smart` 没贴 bulk 代码——可能是**单条写入为主**，**bulk 用于重建索引**。

---

## 【十、ES 8.x 的 Rerank 支持**

ES 8.12+ 原生支持 **Rerank**——`inference` 端点 + `text_similarity` reranker。

`pai-smart` 当前用 **rescore** 自建 Rerank——**已经够用**。**未来可以升级到原生 Rerank**——**效果更好，代码更少**。

---

## 【十一、常见坑 & 面试问答】

**Q1：vector 维度变了怎么办？**

A：**ES 不支持改 mapping 的 dims**——必须 **reindex**：
1. 建新 index（`knowledge_base_v2`，`dims: 2048`）。
2. 业务层 dual-write（同时写 v1 和 v2）。
3. 数据迁移（从 v1 读，embed 一次，写 v2）。
4. 切流量到 v2。
5. 删 v1。

`pai-smart` 早期没考虑这个——**如果换 Embedding 模型，要重写一套**。**生产环境**要在 entity 上记录 `modelVersion` 字段（**`pai-smart` 已经这么干了**）。

**Q2：HNSW 索引能改参数吗？**

A：能，**但要 reindex**。`pai-smart` 用的默认参数（`m: 16, ef_construction: 100`）。**调参经验**：
- **`m`**：图的出度。**越大越准，内存越多**。
- **`ef_construction`**：构建时的搜索宽度。**越大越准，构建越慢**。
- **`ef_search`**：查询时的搜索宽度。**越大越准，查询越慢**。

**Q3：ES 8.x 强类型 client 怎么写复杂查询？**

A：用 `withJson()` 传原始 JSON——**最后兜底方案**：

```java
String json = """
    {
      "query": {
        "bool": {
          "filter": [...]
        }
      }
    }
""";
esClient.withJson(new StringReader(json));
```

`pai-smart` 没这么做——**强类型 client 已经够用**。

**Q4：ES 集群怎么部署？**

A：生产环境**至少 3 节点**（master + data + ingest 角色分离）。`pai-smart` 用 `docs/docker-compose.yaml` 部署的是**单节点**——**仅开发**。

**Q5：HNSW 索引在 ES 里有多大？**

A：**粗估**：1 万个 1024 维向量 + HNSW → **约 50MB**。**1 亿向量** → **约 500GB**。**生产环境需要 SSD + 大内存**。

---

## 【十二、下集预告】

向量能写 ES 了，**但整个流程还是同步的**——用户上传文件 → 服务端立刻解析 + embedding → **几分钟后才能搜到**。

**用户等不了！**

第 09 集我们要：

- 引入 **Kafka**——把同步处理改成异步。
- `KafkaConfig` 怎么配置生产者和消费者。
- `FileProcessingConsumer` 怎么消费消息。
- **失败重试 + 死信队列**。

**Kafka 是分布式系统的"解耦神器"**——`pai-smart` 用了它，整个后端就活了。

我们下期见。

---

> 觉得这一期的 ES 集成讲得够深，**点赞、投币、收藏**。
> 关注我，**下一期讲 Kafka 异步处理**。

下一集链接：[第 09 集：Kafka 登场——把同步变异步](./09-kafka-async.md)
