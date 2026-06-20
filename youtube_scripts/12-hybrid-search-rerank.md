# 第 12 集：混合检索——BM25 + 向量 + Rerank

> 系列：派聪明 RAG 后端演进全解
> 集数：12 / 20+
> 主题：BM25 vs 向量、RRF 倒数排名融合、Rerank 模型、查询改写
> 上集回顾：第 11 集 OrgTag 多租户搞定
> 本集目标：搜索质量提升一个数量级

---

## 【开场 Hook】

一个 RAG 系统的「检索」好不好，**直接决定回答质量**。

**但纯向量检索有三大问题**：

1. **专有名词不友好**：用户搜「`JDK 21`」——embedding 模型可能理解成「JDK 公司 21 周年庆」。
2. **拼写容错差**：用户搜「`vue`」——向量能匹配 `Vue.js`，但拼成「`vew`」就抓瞎。
3. **数字、ID 类信息弱**：订单号 `「20240512-A-001」`——**纯语义检索几乎没用**。

**纯 BM25（关键词）检索也有问题**：
- 「**苹果**公司财务」——`「苹果手机维修点」`也能匹配上——**字面命中但语义无关**。

`pai-smart` 的解法：**混合检索**——**BM25 + 向量 + Rerank**。

今天我们把 `HybridSearchService` 完整拆解。

---

## 【一、BM25 是什么？】

**BM25（Best Matching 25）** = 经典**关键词检索算法**。

**核心思想**：
- **TF（Term Frequency）**：词频——`「苹果」`出现越多越相关。
- **IDF（Inverse Document Frequency）**：逆文档频率——`「苹果」`在很多文档里出现 → 区分度低 → 权重低。
- **文档长度归一化**：长文档天然词多——要惩罚。

**ES 的 `match` 查询**默认就用 BM25。

**面试考点 1**：BM25 vs TF-IDF？

A：BM25 是 TF-IDF 的改进版：
- TF 部分加了**饱和函数**（`tf / (tf + k)`）——避免「`「苹果」`出现 100 次」比「出现 10 次」相关性高 10 倍。
- 文档长度归一化**更合理**。

**ES 8.x 默认 `similarity: BM25`**。

---

## 【二、向量检索 vs BM25：互补关系】

| 维度 | BM25 | 向量 |
|---|---|---|
| 专有名词 | ✅ 强 | ❌ 弱 |
| 拼写容错 | ❌ 弱（除非 fuzziness） | ✅ 强 |
| 语义理解 | ❌ 弱（同义词） | ✅ 强 |
| 数字 / ID | ✅ 强 | ❌ 弱 |
| 召回率 | 低（漏召） | 较高 |
| 精度 | 中 | 中（召回了再用 LLM 过滤） |

**结论**：**两者互补，必须结合**。

**面试考点 2**：为什么"必须"？

A：纯向量在 RAG 场景**召回率还不够**。**BM25 兜底**专有名词、ID、缩写。**两者结合**才能「**字面 + 语义**」全覆盖。

---

## 【三、HybridSearchService 的召回流程】

```java
public List<SearchResult> searchWithPermission(String query, String userId, int topK) {
    // 1. 取用户有效 org tags
    List<String> userEffectiveTags = getUserEffectiveOrgTags(userId);
    String userDbId = getUserDbId(userId);
    
    // 2. 生成查询向量
    List<Float> queryVector = embedToVectorList(query, userId);
    
    if (queryVector == null) {
        return textOnlySearchWithPermission(query, userId, topK);  // 降级
    }
    
    // 3. 构造混合查询
    SearchResponse<EsDocument> response = esClient.search(s -> {
        s.index("knowledge_base");
        int recallK = topK * 30;
        
        // a. 向量召回（kNN）
        s.knn(kn -> kn
            .field("vector")
            .queryVector(queryVector)
            .k(recallK)
            .numCandidates(recallK)
        );
        
        // b. BM25 召回（match）
        s.query(q -> q.match(m -> m.field("textContent").query(query)));
        
        // c. Rescore（精排）
        s.rescore(r -> r.windowSize(recallK).query(rq -> rq
            .queryWeight(0.2d)
            .rescoreQueryWeight(1.0d)
            .query(rqq -> rqq.match(m -> m.field("textContent").query(query)))
        ));
        
        // d. 权限过滤
        s.postFilter(pf -> pf.bool(b -> b
            .should(s -> s.term(t -> t.field("isPublic").value(true)))
            .should(s -> s.terms(t -> t.field("orgTag").terms(...)))
            .should(s -> s.term(t -> t.field("userId").value(userDbId)))
            .minimumShouldMatch("1")
        ));
    }, EsDocument.class);
    
    return parseResults(response);
}
```

### 3.1 三阶段召回

**Stage 1: 向量召回（kNN）**

- 召回数：`topK * 30`（**30 倍粗排**——给 rerank 留空间）。
- 字段：`vector`（dense_vector 1024 维）。
- 算法：HNSW 近似最近邻。

**Stage 2: BM25 召回（match）**

- `s.query(q -> q.match(...))` — 文本匹配 `textContent`。
- BM25 打分。

**Stage 3: Rescore 精排**

- `windowSize: recallK` — 只在粗排结果上重打分。
- `queryWeight: 0.2` — 原始 BM25 权重 0.2。
- `rescoreQueryWeight: 1.0` — rescore 时 BM25 权重 1.0。
- **效果**：保留粗排多样性，**精排阶段更准**。

**面试考点 3**：为什么粗排用 kNN，主查询用 BM25，rescore 又用 BM25？

A：
- **粗排**：向量召回**召回率高**，**给足候选**。
- **主查询 BM25**：**让 BM25 命中的也在粗排里**（如果只 kNN，BM25 命中的就漏了）。
- **Rescore BM25**：对所有粗排 doc 重新算 BM25 分数，**比单次打分准**。
- **`queryWeight = 0.2`**：让 ES 知道**最终分数 ≈ 0.2 * 原始分 + 1.0 * rescore 分**。

**这是 ES 8.x 的「混合召回 + 二次精排」**——**不需要外部 rerank 库**。

### 3.2 权限过滤：`postFilter`

```java
s.postFilter(pf -> pf.bool(b -> b
    .should(s -> s.term(t -> t.field("isPublic").value(true)))
    .should(s -> s.terms(t -> t.field("orgTag").terms(...)))
    .should(s -> s.term(t -> t.field("userId").value(userDbId)))
    .minimumShouldMatch("1")
));
```

**`postFilter` vs `query`**：
- **`query`**：影响打分（**慢**）。
- **`postFilter`**：**只过滤不影响打分**（**快**）。

**权限过滤用 `postFilter`**——**快**。

### 3.3 召回量：30 倍经验值

```java
int recallK = topK * 30;
```

**为什么 30 倍？**

- 用户问 1 个问题 → 系统需要返回 top 5-10 个 chunk。
- 但**真正的相关 chunk 可能有 50-100 个**。
- 召回 30 倍（**如果 topK = 5，召 150 个**）——**给 rerank 足够空间**。

**面试考点 4**：召回太多会不会慢？

A：会。**30 倍是平衡点**。
- **10 倍**：召回不够，**好答案可能漏**。
- **100 倍**：太慢，**rerank 也跑不动**。
- **生产环境**根据业务调——`pai-smart` 的 30 是经验值。

---

## 【四、查询改写：Query Rewriting】

虽然 `pai-smart` 的 `HybridSearchService` 没显示做 query rewriting，但**生产环境 RAG 必备**：

**为什么需要？**

- 用户问「**派聪明怎么部署**」——`「派聪明`」是个具体词，但 `「怎么部署」`可能太宽。
- **改写成**「派聪明 部署 文档」或「派聪明 Docker Compose」——**更精准**。

**常见改写策略**：

1. **同义词扩展**：「Java」→「JDK」、「JVM」。
2. **HyDE（Hypothetical Document Embeddings）**：用 LLM 生成一个「**假设答案**」——对假设答案做 embedding，**比直接对 query 做 embedding 准**。
3. **Step-back prompting**：问「为什么」——先问「背景知识是什么」。
4. **Multi-query**：用 LLM 生成 3-5 个改写 query——**多路召回 + 合并**。

`pai-smart` 当前**没用**——**未来优化点**。

**面试考点 5**：HyDE 是什么原理？

A：**让 LLM 生成一个"假答案"**——这个假答案**语义上接近真答案**——**对假答案做 embedding** 比直接对 query 做 embedding 召回更准。

---

## 【五、`attachFileNames`：召回结果补全文件名】

```java
private List<SearchResult> attachFileNames(List<SearchResult> results) {
    // 取结果里所有 fileMd5
    Set<String> fileMd5s = results.stream().map(SearchResult::getFileMd5).collect(toSet());
    
    // 一次查 DB
    Map<String, String> fileNameMap = fileUploadRepository.findByFileMd5In(fileMd5s)
            .stream().collect(toMap(FileUpload::getFileMd5, FileUpload::getFileName));
    
    // 补全到结果里
    for (SearchResult r : results) {
        r.setFileName(fileNameMap.getOrDefault(r.getFileMd5(), "未知文档"));
    }
    return results;
}
```

**N+1 问题的经典解法**：
- **不要**：`for result: result.setFileName(fileUploadRepository.findByMd5(md5).getFileName())` —— **N+1**。
- **要**：先取所有 md5 → **一次 IN 查询** → **Map 反查**。

**面试考点 6**：什么是 N+1？

A：
- **1 次**查主表 → **N 次**查关联表。
- **优化**：`JOIN`、`IN`、批查询。

**`pai-smart` 这里用了正确的优化**。

---

## 【六、Context 注入：召回 → LLM】

```java
private String buildContext(List<SearchResult> results) {
    StringBuilder sb = new StringBuilder();
    int idx = 1;
    for (SearchResult r : results) {
        sb.append("来源#").append(idx++)
          .append(" 文件名: ").append(r.getFileName())
          .append(" (页码: ").append(r.getPageNumber()).append(")\n")
          .append(r.getTextContent().substring(0, Math.min(300, r.getTextContent().length())))
          .append("\n\n");
    }
    return sb.toString();
}
```

**注入给 LLM 的 context 格式**：

```
来源#1 文件名: 派聪明部署文档.pdf (页码: 5)
派聪明支持Docker部署，启动命令是 docker-compose up -d...

来源#2 文件名: 派聪明FAQ.md
派聪明的端口配置在 application.yml 中...

来源#3 ...
```

**关键设计**：
- **编号**：LLM 可以引用「来源#1」「来源#2」。
- **文件名 + 页码**：用户能跳转原文档。
- **截断到 300 字符**（`MAX_CONTEXT_SNIPPET_LEN`）：**节省 token**。
- **每个 chunk 单独成段**：LLM 看得清楚。

**面试考点 7**：context 长度怎么控制？

A：
- **chunk 数量**：3-10 个（**多了 LLM 关注不到重点**）。
- **每个 chunk 长度**：200-500 token（**多了浪费**）。
- **总 context**：1500-3000 token。

`pai-smart` 用 `MAX_CONTEXT_SNIPPET_LEN = 300` 字符（**约 150-200 token**）——**保守**。

---

## 【七、`SearchController`：REST 入口】

```java
@RestController
@RequestMapping("/api/search")
public class SearchController {

    @Autowired
    private HybridSearchService hybridSearchService;

    @GetMapping("/hybrid")
    public ResponseEntity<List<SearchResult>> hybridSearch(
            @RequestParam String query,
            @RequestAttribute("userId") String userId,
            @RequestParam(defaultValue = "5") int topK) {
        
        return ResponseEntity.ok(hybridSearchService.searchWithPermission(query, userId, topK));
    }
}
```

**注意 `@RequestAttribute("userId")`** —— **不是** `@RequestParam`！**这是 `OrgTagAuthorizationFilter` 设置的 request attribute**（**第 11 集讲的**）。

**面试考点 8**：`@RequestAttribute` vs `@RequestParam` vs `@PathVariable`？

A：
- **`@RequestParam`**：URL query 参数（`?xxx=yyy`）。
- **`@PathVariable`**：URL path 的一部分（`/{xxx}`）。
- **`@RequestAttribute`**：**request 域 attribute**（**由 filter / interceptor 设置**）。

`pai-smart` 用 `@RequestAttribute` 接 userId——**避免和 query string 冲突**。

---

## 【八、BM25 调参：`boost`、`minimum_should_match`**

`pai-smart` 没显式调 BM25 参数（用默认），但生产环境要调：

```java
s.query(q -> q.match(m -> m
    .field("textContent")
    .query(query)
    .boost(1.5f)  // 提高 BM25 权重
    .minimumShouldMatch("75%")  // 至少匹配 75% 词
));
```

**经验**：
- `boost`：调整 BM25 在混合中的权重。
- `minimum_should_match`：避免「`「苹果」`」一个字就匹配上。

---

## 【九、常见坑 & 面试问答】

**Q1：BM25 和向量怎么融合？**

A：三种方式：
- **`score` 加权**：`finalScore = 0.5 * bm25 + 0.5 * vector`。
- **`rank` 倒数排名**（**RRF**）：`finalScore = 1/(k + rank_bm25) + 1/(k + rank_vector)`。
- **召回 + 融合 + rerank**：**pai-smart 用的方式**——**BM25 和向量各召回，最后 ES rescore 融合**。

**Q2：Rerank 模型怎么选？**

A：
- **BGE-Reranker**（BAAI）：**中文最强开源**。
- **Cohere Rerank**：**商业**，效果好。
- **Jina Rerank**：**多语言**。

`pai-smart` 当前**用 ES rescore**——**已经够用**。**BGE-Reranker 集成是未来优化**。

**Q3：检索效果差怎么排查？**

A：
- **看 top-5 结果**：人工判断相关性。
- **看 BM25 和向量各召回了什么**：是否互补。
- **看 doc 长度分布**：太长的 chunk 容易被切碎。
- **看 embedding 模型**：是否支持中文。
- **A/B 测试**：改参数对比。

**Q4：怎么评估检索质量？**

A：
- **Recall@K**：前 K 个里有多少真相关。
- **MRR**（Mean Reciprocal Rank）：第一个相关 doc 的排名倒数的平均。
- **NDCG**：归一化折损累积增益。

`pai-smart` **没看到评估代码**——**这是 TODO**。

**Q5：搜索性能瓶颈在哪？**

A：通常在 **kNN 召回**——**HNSW 索引的 query latency 是 O(log N)**——**10 亿向量也能 10ms 召回**。
**ES 集群**：网络、磁盘 IO、GC 都会影响。

---

## 【十、思考题：要不要加 HyDE？**

**HyDE 的代价**：
- 每次查询**多调一次 LLM**（生成假设答案）——**慢 + 花钱**。
- **对所有 query 都有用吗**？**不一定**——专有名词查询可能反而退化。

**HyDE 的收益**：
- 抽象 query（「为什么……」）→ **检索质量明显提升**。
- **复杂问题**受益大。

**生产建议**：
- **简单 query**（含专有名词）：**直接 BM25 + 向量**。
- **复杂 query**（抽象问题）：**用 LLM 改写**。
- **自动判断**：用 query 长度 / 词数启发式。

`pai-smart` **没做**——**未来**。

---

## 【十一、下集预告】

搜索质量上来了。**但！**

**用户疯狂刷接口怎么办？**

- 单用户 1 秒发 100 次查询——**打爆 ES**。
- 1 个用户上传 1000 个文件——**配额耗尽**。
- 恶意脚本调用——**资源耗尽**。

第 13 集我们要：

- `RateLimitService` 怎么用 Redis 实现限流。
- 滑动窗口 vs 令牌桶。
- `UsageQuotaService` 的配额扣减。
- **降级策略**——**超限后怎么办**。

**限流是后端工程化的"成人礼"**。

我们下期见。

---

> 混合检索是 RAG 系统的"**质量分水岭**"——`pai-smart` 的 BM25 + kNN + rescore 三件套值得学。
> 觉得有用，**点赞、投币、收藏**。

下一集链接：[第 13 集：限流与配额——Redis 守护神](./13-rate-limit-quota.md)
