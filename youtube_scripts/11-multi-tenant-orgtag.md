# 第 11 集：多租户隔离——OrgTag 体系

> 系列：派聪明 RAG 后端演进全解
> 集数：11 / 20+
> 主题：多租户架构、OrgTag 树形层级、OrgTagAuthorizationFilter、PRIVATE_ 前缀
> 上集回顾：第 10 集 WebSocket + DeepSeek 流式聊天跑通
> 本集目标：公司 A 的文档，公司 B 的员工搜不到

---

## 【开场 Hook】

想象一个场景：

> 公司 A 上传了他们的内部财务文档到 `pai-smart`。
> 公司 B 的员工登录后，**也能搜到**。
>
> **这是数据泄露事故**——轻则客户流失，重则被起诉。

RAG 系统**只要有「多用户」就有「多租户」问题**。`pai-smart` 的解法是 **OrgTag（组织标签）**——**给用户和文档打标签，标签匹配才能访问**。

这是 SaaS 系统的**基石**。今天我们拆 `OrganizationTag` 实体、`OrgTagAuthorizationFilter` 过滤器、还有 `OrgTagCacheService` 缓存层。

---

## 【一、什么是多租户？三种隔离模型】

**多租户（Multi-Tenancy）** = **一套系统服务多个客户（租户）**，**数据要严格隔离**。

**三种隔离模型**：

| 模型 | 实现 | 优点 | 缺点 |
|---|---|---|---|
| **独立数据库** | 每个租户一个 DB | **强隔离**，故障不串 | 运维贵，连接数多 |
| **共享数据库 + 独立 Schema** | 同 DB 不同 schema | 中等隔离 | 跨租户查询难 |
| **共享数据库 + 共享 Schema** | 同表加 `tenant_id` 列 | **便宜** | 风险高，**应用层必须强校验** |

`pai-smart` 选**第三种**——**所有租户共享 MySQL 和 ES，靠 `orgTag` 字段做过滤**。

**这是 SaaS 系统的常见选择**——**成本最低**，**但应用层要非常小心**。

**面试考点 1**：怎么保证"应用层不漏过"？

A：
- **AOP 拦截**：`@PreAuthorize` + SpEL 强制拼 orgTag。
- **Filter 拦截**：`OrgTagAuthorizationFilter` URL 级校验。
- **Repository 封装**：`UserRepository.findXxxByOrgTag(...)` —— **业务代码只能调这种"已经带过滤"的方法**。
- **DB 权限**：MySQL Row-Level Security（**MySQL 8.0+ 支持**）。

`pai-smart` 用了**前 3 种**。

---

## 【二、`OrganizationTag`：组织标签的实体设计】

```java
@Data
@Entity
@Table(name = "organization_tags")
public class OrganizationTag {
    @Id
    @Column(name = "tag_id")
    private String tagId;  // 标签唯一标识

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "parent_tag", length = 255)
    private String parentTag;  // 父标签 ID

    @Column(name = "upload_max_size_bytes")
    private Long uploadMaxSizeBytes;  // 非管理员上传文件大小上限

    @ManyToOne
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
```

**关键设计点**：

1. **`tagId` 是 String**：是「**业务主键**」，不用自增 Long——**方便跨系统传递**。
2. **`parentTag`**：**树形结构**！`ROOT → 公司A → 部门X`——**继承关系**。
3. **`uploadMaxSizeBytes`**：**不同租户不同配额**——**业务规则硬编码到 tag 上**。
4. **`createdBy`**：谁建的——**审计**。

**面试考点 2**：为什么用树形结构？

A：**权限继承**。公司 A 的人可以访问「公司A」tag 下的所有子部门文档——**不用每个部门分别授权**。

**树形结构的设计**：
- `parentTag` 是 String 而不是 `@ManyToOne`——**避免 N+1 查询**。
- 父级 `tagId` 直接存字符串——**取整棵树要递归**（用 `OrgTagCacheService` 缓存）。

**Hibernate 里的 `@ManyToOne`**：
- `User createdBy` 用了 `@ManyToOne`——**懒加载**。
- 访问 `orgTag.getCreatedBy()` 会**自动**加载 User——**额外一次 SQL**。
- **N+1 风险**——批量查 `OrganizationTag` 列表时，**每个 tag 都触发一次 User 加载**。`pai-smart` 应该在 `OrgTagRepository` 用 `JOIN FETCH`——**这里没看到**。

---

## 【三、`PRIVATE_` 前缀：私人文档的特殊处理】

```java
private static final String PRIVATE_TAG_PREFIX = "PRIVATE_";
```

**`pai-smart` 的私有文档设计**：

- 公开文档：`orgTag = "DEFAULT"` 或 `isPublic = true`。
- 私人文档：`orgTag = "PRIVATE_<userId>"`。

**这样设计的好处**：
- 私人文档**用一个字段标识**，**不用单独建表**。
- 过滤逻辑**简单**：`orgTag LIKE 'PRIVATE_<myUserId>' OR isPublic = true OR orgTag IN (myOrgTags)`。
- **不需要复杂的 owner 字段**。

**面试考点 3**：为什么不直接用 `userId` 字段？

A：
- `userId` 字段需要额外的 owner 关系。
- `orgTag LIKE 'PRIVATE_<userId>'` 可以**复用** orgTag 的所有过滤逻辑。
- 私人文档**也是一种"组织"**——**PRIVATE_<userId> 就是一个特殊组织**。

**这是「用统一抽象解决一类问题」的好例子**。

---

## 【四、`OrgTagAuthorizationFilter`：URL 级权限控制】

```java
@Component
public class OrgTagAuthorizationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) {
        try {
            String path = request.getRequestURI();
            
            // 路径模式匹配
            if (path.matches(".*/upload/chunk.*") || ... ) {
                // 这些 API 只需要 userId，不做资源级权限检查
                String token = extractToken(request);
                if (token != null) {
                    String userId = jwtUtils.extractUserIdFromToken(token);
                    String role = jwtUtils.extractRoleFromToken(token);
                    String orgTags = jwtUtils.extractOrgTagsFromToken(token);
                    request.setAttribute("userId", userId);
                    request.setAttribute("role", role);
                    request.setAttribute("orgTags", orgTags);
                }
                filterChain.doFilter(request, response);
                return;
            }
            
            String resourceId = extractResourceIdFromPath(request);
            if (resourceId == null) {
                filterChain.doFilter(request, response);
                return;
            }
            
            ResourceInfo resourceInfo = getResourceInfo(resourceId);
            if (resourceInfo == null) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            
            String resourceOrgTag = resourceInfo.getOrgTag();
            
            // 公开/默认 tag → 放行
            if (resourceInfo.isPublic() || resourceOrgTag == null || resourceOrgTag.isEmpty() || 
                "DEFAULT".equals(resourceOrgTag)) {
                filterChain.doFilter(request, response);
                return;
            }
            
            // 鉴权
            if (!isUserAuthorized(...)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
            
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            logger.error("...", e);
        }
    }
}
```

### 4.1 两种 URL 的处理方式

**第一类：不需要资源级权限的 API**——只设置 `userId` 给 controller 用：

```java
if (path.matches(".*/upload/chunk.*") || 
    path.matches(".*/upload/merge.*") || 
    path.matches(".*/documents/uploads.*") || 
    path.matches(".*/search/hybrid.*") || ...) {
    // 只设置 request attribute，不做权限检查
    request.setAttribute("userId", userId);
    filterChain.doFilter(request, response);
    return;
}
```

**这种 API 怎么鉴权？**

- **业务层自己处理**——`searchService.searchWithPermission(query, userId, topK)`。
- **Filter 只负责「告诉你 userId 是谁」**。

**第二类：需要资源级权限的 API**——**鉴权**：

```java
String resourceId = extractResourceIdFromPath(request);
ResourceInfo resourceInfo = getResourceInfo(resourceId);
if (resourceInfo == null) {
    response.setStatus(404);
    return;
}
if (resourceInfo.isPublic() || "DEFAULT".equals(resourceInfo.getOrgTag())) {
    filterChain.doFilter(request, response);
    return;
}
if (!isUserAuthorized(...)) {
    response.setStatus(403);
    return;
}
filterChain.doFilter(request, response);
```

**`extractResourceIdFromPath`**：从 URL 里抽出 `fileMd5`——`/documents/{fileMd5}/preview` → `{fileMd5}`。

**`getResourceInfo`**：查 DB / 缓存拿到文档的 orgTag 和 isPublic。

**`isUserAuthorized`**：判断用户能否访问——核心逻辑是 `resourceOrgTag IN (userOrgTags) OR resourceOrgTag LIKE 'PRIVATE_<userId>'`。

### 4.2 性能优化：缓存 org tag

**`OrgTagCacheService`**：把用户的 org tag 关系**缓存到内存**：

```java
// 推测实现
@Service
public class OrgTagCacheService {
    private final Cache<String, Set<String>> userOrgTagsCache;  // userId -> orgTags（含父级）
    
    public Set<String> getUserEffectiveOrgTags(String userId) {
        Set<String> tags = userOrgTagsCache.getIfPresent(userId);
        if (tags == null) {
            tags = loadFromDb(userId);  // 查 DB + 递归找父级
            userOrgTagsCache.put(userId, tags);
        }
        return tags;
    }
}
```

**为什么需要缓存？**

**每次请求都查 DB 计算 org tag 太慢**——而且 **org tag 不常变**——**缓存是必须的**。

**用什么缓存？**
- **Caffeine**（**Java 本地内存缓存**）：`pai-smart` 大概率用的这个。
- **Redis**：跨实例，**但延迟比本地缓存高**。

**面试考点 4**：Caffeine vs Redis 缓存怎么选？

A：
- **Caffeine**：单实例，**微秒级**，**不跨实例**——**适合 org tag 这种每实例一份的小数据**。
- **Redis**：跨实例，**毫秒级**——**适合会话、限流这种共享数据**。

---

## 【五、`User.orgTags` 和 `primaryOrg` 的设计】

回顾第 02 集的 `User` 实体：

```java
@Column(name = "org_tags")
private String orgTags; // 用户所属组织标签，多个用逗号分隔

@Column(name = "primary_org")
private String primaryOrg; // 用户主组织标签
```

**两个字段**：
- `orgTags`：用户**所有**所属组织（**用逗号分隔**）。
- `primaryOrg`：用户的**主**组织（**默认显示用**）。

**为什么分开？**

A：
- `primaryOrg` 用于**默认显示**——前端展示「当前组织」。
- `orgTags` 用于**实际过滤**——所有匹配的 tag 都参与。
- 用户**可能属于多个组织**（**跨部门协作**）—— `primaryOrg` 只是"主"的。

**问题**：还是用 String 存**逗号分隔**——**没有正经的多对多表**。**业务简单可以接受**。

**面试考点 5**：为什么不建 `user_org_tag` 关联表？

A：
- **简单**：不用多一张表。
- **够用**：业务上**很少需要 JOIN**。
- **代价**：**关联查询** `SELECT * FROM user WHERE orgTag = 'X'` 要 `LIKE '%X%'` ——**慢**。

---

## 【六、检索时的权限过滤：`HybridSearchService`】

```java
public List<SearchResult> searchWithPermission(String query, String userId, int topK) {
    // 1. 取用户有效 org tags
    List<String> userEffectiveTags = getUserEffectiveOrgTags(userId);
    
    // 2. 构造 ES bool filter
    BoolQuery.Builder boolBuilder = new BoolQuery.Builder();
    
    // 公开 OR 用户的 org tags OR 用户的私人
    boolBuilder.filter(f -> f.bool(b -> b
        .should(s -> s.term(t -> t.field("isPublic").value(true)))
        .should(s -> s.terms(t -> t.field("orgTag").terms(tt -> tt.value(
            userEffectiveTags.stream().map(FieldValue::of).toList()
        ))))
        .should(s -> s.term(t -> t.field("userId").value(userId)))
        .minimumShouldMatch("1")
    ));
    
    // 3. 调 ES 搜索
    SearchResponse<EsDocument> response = esClient.search(s -> {
        s.index("knowledge_base");
        s.query(q -> q.bool(boolBuilder.build()));
        s.knn(kn -> kn.field("vector").queryVector(queryVector).k(topK * 30).numCandidates(topK * 30));
        // ...
    }, EsDocument.class);
    
    return parseResults(response);
}
```

**`should + minimumShouldMatch("1")`** 的逻辑：

- **公开**（`isPublic = true`）OR
- **匹配 org tag**（`orgTag IN userEffectiveTags`）OR
- **自己上传的**（`userId = currentUser`）

**三个条件**满足**任一**即可。

**面试考点 6**：ES 的 `should` 默认行为？

A：默认 `should` **不参与 must 评分**——**只起"加分"作用**。用 `minimumShouldMatch("1")` 强制要求**至少 1 个 should 命中**。

---

## 【七、`getUserEffectiveOrgTags`：含父级的 tag 集合】

```java
private List<String> getUserEffectiveOrgTags(String userId) {
    User user = userRepository.findById(Long.parseLong(userId)).orElseThrow();
    
    Set<String> result = new HashSet<>();
    String[] directTags = user.getOrgTags().split(",");
    
    for (String tag : directTags) {
        result.add(tag);
        // 递归加父级
        addParentTagsRecursively(tag, result);
    }
    
    return new ArrayList<>(result);
}
```

**关键点**：
- 用户直接 tag + 所有父级 tag。
- **`pai-smart` 用递归**——`getParentTag` 查 DB。
- **生产环境用 cache**——避免每次请求都查 DB。

**面试考点 7**：树形结构怎么高效查询所有祖先？

A：
- **缓存**：把「tagId → 所有祖先 tagId 列表」缓存，**新增 tag 时失效**。
- **维护冗余字段**：`OrganizationTag` 加 `ancestors` 字段存「`/,A,A/B,A/B/C`」——一次查询拿到所有祖先。
- **`WITH RECURSIVE`**（MySQL 8.0+）：SQL 递归——**比 Java 递归少一次网络**。

---

## 【八、AdminUserInitializer：默认管理员 + DEFAULT tag】

`pai-smart` 启动时建：
- **默认管理员账号**（从配置读）。
- **DEFAULT 组织标签**——`OrgTagInitializer` 创建。
- **所有新用户注册**自动打 `DEFAULT` tag。

**为什么要有 DEFAULT？**

- **个人用户**不属于任何公司——**也得有组织**。
- **DEFAULT 是个"自由区"**——所有用户共享。
- 公开文档都在 DEFAULT 下。

**这是「单租户」升级到「多租户」的过渡设计**。

---

## 【九、`BootstrapKnowledgeInitializer`：内置知识库**

```java
@Component
public class BootstrapKnowledgeInitializer {
    // 应用启动时，向 DEFAULT org tag 下注入一些"内置知识"
    // 比如：项目说明、API 文档、FAQ
}
```

**设计意图**：
- 新用户注册后，**立刻有内容可看**。
- 内置知识**与用户上传的隔离**。
- **降低冷启动的"空状态"感**。

**面试考点 8**：内置知识有什么业务价值？

A：
- **降低新用户学习成本**——上手就能用。
- **测试场景**——QA 有现成数据。
- **演示场景**——给客户演示时**不用准备数据**。

---

## 【十、常见坑 & 面试问答】

**Q1：用户的 org tag 变了，要清缓存吗？**

A：**必须的**。`OrgTagCacheService` 通常有 `evict(userId)` 方法——**管理员在后台改 org tag 时调**。

`pai-smart` 看代码应该有**手动失效 + TTL 双保险**。

**Q2：跨 org tag 共享文档怎么办？**

A：两种方案：
- **`isPublic = true`**：所有用户都能看。
- **额外的"共享白名单"**：每个文档加 `sharedWithOrgTags: List<String>` ——**显式授权**。

`pai-smart` 当前**只用 `isPublic`** ——**简化**。

**Q3：管理员能不能访问所有文档？**

A：通常**可以**。`pai-smart` 看代码：
- SecurityConfig 里 `/api/v1/admin/**` 限 `hasRole("ADMIN")`。
- `OrgTagAuthorizationFilter` 看到 `role = ADMIN` 可能直接放行（**具体看 isUserAuthorized 完整代码**）。

**生产环境管理员最好用"超级角色"**，**不参与 org tag 过滤**。

**Q4：组织树最多支持几级？**

A：`pai-smart` 用 `parentTag` String + 递归——**理论上无限级**——**实际上 3-5 级够用**。**过深的树**导致查询慢、用户体验差。

**Q5：删除 org tag 怎么处理存量数据？**

A：
- **级联删除**：tag 删了，**关联文档全删**——**危险**。
- **移到 DEFAULT**：tag 删了，**关联文档改 org_tag = DEFAULT**——**安全**。
- **软删除**：tag 加 `deleted_at`，**保留数据**——**审计友好**。

`pai-smart` 用**软删除**——**生产级做法**。

---

## 【十一、思考题：为什么 `OrgTagAuthorizationFilter` 写在 `config/` 包？**

跟第 03 集的 `JwtAuthenticationFilter` 一样——**写在 config 包是"项目习惯"**。**严格说应该**：
- 单独的 `filter/` 或 `security/` 包。

**但 `pai-smart` 演进中没拆**——**KISS 原则**。

---

## 【十二、下集预告】

多租户搞定了。**但搜索质量还是不够**——

- 用户搜「派聪明」——`「pai-smart」`也能命中吗？
- 专有名词搜不到？拼写错了能命中？
- **关键字匹配**和**向量召回**怎么平衡？

第 12 集我们要：

- 深入 `HybridSearchService`——**BM25 + 向量 + 重排序**。
- `Rerank` 怎么用 BGE-Reranker 精排。
- 多种 query 改写策略。
- 召回的"过滤网"怎么设计。

**这是 RAG 系统的"质量灵魂"**。

我们下期见。

---

> 多租户是 SaaS 系统的**生死线**——`pai-smart` 的 OrgTag 设计**简单但完整**。
> 觉得有用，**点赞、投币、收藏**。

下一集链接：[第 12 集：混合检索——BM25 + 向量 + Rerank](./12-hybrid-search-rerank.md)
