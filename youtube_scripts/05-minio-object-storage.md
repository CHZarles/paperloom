# 第 05 集：MinIO 登场——文件存储从本地到对象存储

> 系列：派聪明 RAG 后端演进全解
> 集数：05 / 20+
> 主题：对象存储概念、MinIO 与 S3 协议、FileUpload 实体设计、分片上传
> 上集回顾：第 04 集我们写完了 SecurityConfig，登录注册跑通
> 本集目标：文档能上传、能存到 MinIO，FileUpload 实体落库

---

## 【开场 Hook】

前 4 集我们做的都是「**元数据**」——用户、Token、权限。**真正的内容（文档）还没登场**。

RAG 系统的第一步是「**有文档**」。这一步看似简单，**实际上埋了 8 个技术选型问题**：

1. 文件存哪里？**本地磁盘？NAS？对象存储？**
2. 文件怎么唯一标识？**文件名？MD5？SHA256？**
3. 大文件怎么传？**整文件上传？分片上传？断点续传？**
4. 重复文件怎么去重？**全文件哈希？分片哈希？**
5. 文件元数据存哪？**和文件一起？单独数据库？**
6. 文件名冲突怎么办？**UUID？雪花 ID？**
7. 上传失败怎么回滚？**事务？补偿？**
8. 多副本怎么保证？**单节点？集群？**

`pai-smart` 早期估计就是「**文件存本地磁盘**」——简单，但**生产环境一上来就遇到瓶颈**（磁盘满、单点、备份难）。

所以我们直接从「**对象存储**」开始讲。**用 MinIO。**

---

## 【一、为什么是对象存储，不是本地磁盘？】

先看一个真实场景：

> 用户上传了一个 100MB 的 PDF 文档。文件存到 `/var/smartpai/uploads/`。然后有一天磁盘满了。运维扩容了一台机器，把磁盘挂过来……结果发现新机器看不到老文件。

**本地存储的痛点**：
- **单点**：磁盘坏了文件就丢。
- **难扩展**：磁盘满了只能扩容量，不能扩节点。
- **难备份**：要写脚本 `rsync`、或者 `crontab` + `tar`。
- **多实例不共享**：两个后端 Pod 看到的文件不同步。

**对象存储的核心思想**：
- 文件变成「**对象**」，每个对象有**唯一的 key**（类似文件名）。
- 文件存在「**桶**」（bucket）里，桶是平级的，**没有目录层级**。
- **元数据**和**数据**分开存。元数据是「这个文件叫什么、多大、什么时候传的、谁传的」。
- 底层**分布式**，数据**自动多副本**。
- 通过 **HTTP/RESTful API** 访问——**任何语言、任何平台都能用**。

**国内常见的对象存储**：
- **AWS S3**（业界标准）。
- **阿里云 OSS**（兼容 S3）。
- **腾讯云 COS**（兼容 S3）。
- **MinIO**（**自建对象存储**，**100% 兼容 S3 协议**）。

`pai-smart` 选 MinIO——**国产开源、自建集群、和 S3 API 兼容**。开发环境用 Docker 一键起，生产环境可以扩到几十个节点。

**面试考点 1**：为什么不用 FastDFS？

FastDFS 也是国产对象存储（来自淘宝），**不支持 S3 协议**。社区活跃度下降，新项目基本不选它了。**S3 协议已经是事实标准**——`pai-smart` 选 MinIO 是正确的「面向未来」选择。

---

## 【二、MinioConfig：四行配置，初始化客户端】

```java
@Getter
@Configuration
public class MinioConfig {

    @Value("${minio.endpoint}")
    private String endpoint;

    @Value("${minio.accessKey}")
    private String accessKey;

    @Value("${minio.secretKey}")
    private String secretKey;

    @Value("${minio.publicUrl}")
    private String publicUrl;

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

    @Bean
    public String minioPublicUrl() {
        return publicUrl;
    }
}
```

**逐个看**：

- `endpoint`：MinIO 服务的访问地址。开发环境是 `http://localhost:9000`。
- `accessKey` / `secretKey`：访问密钥。**和 AWS IAM 一样**。
- `publicUrl`：给前端访问 MinIO 的 URL（**可以是 Nginx 转发后的公网地址**）。

**`@Getter` 是 Lombok 注解**——为 `endpoint` 等字段生成 getter。**这是为了其他类能读到这些值**（比如生成临时访问 URL）。

**`@Bean public String minioPublicUrl()`**——**这看起来很怪**：一个 String 类型的 bean？

**它是为了让 Spring 把 `publicUrl` 注入到任何需要它的地方**：

```java
@Autowired
@Qualifier("minioPublicUrl")
private String publicUrl;
```

**这是个值得学习的小技巧**——**用 `@Bean` 把单个配置值注册成 Spring bean**，**避免到处 `@Value`**。

**面试考点 2**：`@Value` vs `@Bean` 注册配置值，怎么选？

- **`@Value`**：简单直观，**适合少量**配置。
- **`@Bean` 注册**：适合**多个相关配置**、**或者想在 bean 创建时做点逻辑**（比如校验非空、转换格式）。

`pai-smart` 这里**两个都用**了，**算冗余设计**——`@Value` 已经注入了 `publicUrl`，**完全没必要再注册一个 bean**。这是 KISS 原则的反面例子。

---

## 【三、FileUpload 实体：把"文件"映射成数据库行】

```java
@Data
@Entity
@Table(name = "file_upload")
public class FileUpload {
    public static final int STATUS_UPLOADING = 0;
    public static final int STATUS_COMPLETED = 1;
    public static final int STATUS_MERGING = 2;
    public static final String VECTORIZATION_STATUS_PENDING = "PENDING";
    public static final String VECTORIZATION_STATUS_PROCESSING = "PROCESSING";
    public static final String VECTORIZATION_STATUS_COMPLETED = "COMPLETED";
    public static final String VECTORIZATION_STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_md5", length = 32, nullable = false)
    private String fileMd5;

    private String fileName;
    private long totalSize;
    private int status;  // 0-上传中 1-已完成 2-合并中

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(name = "org_tag")
    private String orgTag;

    @Column(name = "is_public", nullable = false)
    private boolean isPublic = false;

    @Column(name = "estimated_embedding_tokens")
    private Long estimatedEmbeddingTokens;

    @Column(name = "estimated_chunk_count")
    private Integer estimatedChunkCount;

    @Column(name = "actual_embedding_tokens")
    private Long actualEmbeddingTokens;

    @Column(name = "actual_chunk_count")
    private Integer actualChunkCount;

    @Column(name = "vectorization_status", length = 32)
    private String vectorizationStatus;

    @Column(name = "vectorization_error_message", length = 1000)
    private String vectorizationErrorMessage;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime mergedAt;
}
```

### 3.1 `file_md5`：文件的"身份证"

`@Column(name = "file_md5", length = 32, nullable = false) private String fileMd5;`

**为什么用 MD5？**

- 同一个文件，**内容相同 → MD5 相同**。
- 不同文件，**MD5 几乎不可能相同**（碰撞概率 2^-128）。
- **32 个字符**（128 bit），**长度可控**。

**MD5 在文件场景的合理用途**：
- ✅ **去重**：用户传同一个文件，**秒传**。
- ✅ **完整性校验**：下载后比对 MD5，**确认传输没出错**。
- ❌ **安全敏感场景**（密码、数字签名）：**别用 MD5**，用 SHA-256 或更安全。

**`pai-smart` 用 MD5 做文件指纹是合理的**——**不是用于密码学**，仅用于去重和校验。

**面试考点 3**：MD5 还能用吗？

A：能，但要分场景：
- **密码、签名、证书**：**禁用**（已被王小云教授 2004 年攻破）。
- **文件指纹、数据去重**：**可以用**（不需要抗碰撞攻击，碰撞了也无所谓）。

### 3.2 `status`：上传状态机

```java
public static final int STATUS_UPLOADING = 0;
public static final int STATUS_COMPLETED = 1;
public static final int STATUS_MERGING = 2;
```

**这是分片上传的状态机**：

```
0 (UPLOADING)  -- 客户端分片传完 --> 2 (MERGING)
2 (MERGING)    -- 服务端合并分片 --> 1 (COMPLETED)
```

**为什么需要 `MERGING` 这个中间态？**

**因为分片上传是异步的**：
1. 客户端把文件切成 N 片，**一片一片传**。
2. 服务端先存到 MinIO 的临时桶（或者本地）。
3. **所有分片都到了**，客户端调「合并」接口。
4. 服务端做合并（merge）：把所有分片按顺序拼成一个完整文件，写回 MinIO 的正式桶。

**如果用户传了一半掉线**，`status` 还在 `UPLOADING`——下次可以从断点继续（**断点续传**）。

**面试考点 4**：为什么不用 `enum` 用 `int`？

A：作者图省事。**更好的设计**：
```java
@Enumerated(EnumType.STRING)
private UploadStatus status;

public enum UploadStatus {
    UPLOADING, MERGING, COMPLETED, FAILED
}
```

`pai-smart` 第 02 集用 `@Enumerated(EnumType.STRING)`，**这里又用 int**——**前后不一致**。这是**实战中常见的"技术债"**。

### 3.3 `vectorizationStatus`：第二个状态机

```java
public static final String VECTORIZATION_STATUS_PENDING = "PENDING";
public static final String VECTORIZATION_STATUS_PROCESSING = "PROCESSING";
public static final String VECTORIZATION_STATUS_COMPLETED = "COMPLETED";
public static final String VECTORIZATION_STATUS_FAILED = "FAILED";
```

这是文档**向量化**的状态机（第 07 集讲）。注意是 `String` 不是 `int`——**说明作者后来意识到类型安全的重要性**。

`PENDING → PROCESSING → COMPLETED / FAILED`。

**为什么用 String 不用 enum？** 项目里其他地方（如 `orgTags`）都是 String，**风格统一**。**这不是最佳实践**——enum 更安全。

### 3.4 `org_tag` 和 `is_public`：权限字段

```java
@Column(name = "org_tag")
private String orgTag;

@Column(name = "is_public", nullable = false)
private boolean isPublic = false;
```

这两个字段**直接关系到多租户体系**（第 11 集）。

- **`orgTag`**：文件归属的组织。**`DEFAULT` 表示默认租户**。
- **`isPublic`**：是否公开。**公开文件所有用户都能访问，私有文件只能同组织访问**。

**设计哲学**：**不要存"谁能看"的列表，存"属于哪个组织"**。**访问控制通过"组织关系"动态算**——更灵活、更可扩展。

### 3.5 `estimated_embedding_tokens` 和 `actual_embedding_tokens`：双口径

```java
@Column(name = "estimated_embedding_tokens")
private Long estimatedEmbeddingTokens;

@Column(name = "estimated_chunk_count")
private Integer estimatedChunkCount;

@Column(name = "actual_embedding_tokens")
private Long actualEmbeddingTokens;

@Column(name = "actual_chunk_count")
private Integer actualChunkCount;
```

**这是 RAG 系统的"账本"**。

- **预估**：上传时根据文件大小**估算**会消耗多少 token、要分多少块。
- **实际**：向量化跑完后**真实**消耗的 token 和 chunk 数。

**为什么要记两套？**

- **预估**用于「上传时即时反馈给用户」（"这个文件预计要花 1.5 万 token"）。
- **实际**用于「精准计费 / 配额扣减」（第 17 集讲充值时用）。

**这种"预估 + 实际"的设计在 RAG 系统里非常重要**——**embedding API 是花钱的**，**必须能精确对账**。

**面试考点 5**：为什么 `estimated_embedding_tokens` 不是 `int` 是 `Long`？

A：单个文档可能很大。**int 上限 21 亿，token 数量可能超过**（虽然实际不太可能）。**Long 永远不溢出**——**业务上更稳**。`actualChunkCount` 用 `Integer` 足够（chunk 数不会超过百万级）。

---

## 【四、UploadController：分片上传的实现思路】

虽然没贴完整代码，但**从 `SecurityConfig` 的白名单和 `FileUpload` 实体**可以推断：

```java
@PostMapping("/upload/chunk")
public ResponseEntity<?> uploadChunk(
        @RequestParam("fileMd5") String fileMd5,
        @RequestParam("chunkIndex") int chunkIndex,
        @RequestParam("totalChunks") int totalChunks,
        @RequestParam("file") MultipartFile chunk) {
    // 1. 校验 token（filter 已经做了）
    // 2. 校验 fileMd5
    // 3. 存到 MinIO 的临时桶：{fileMd5}/{chunkIndex}
    // 4. 记录到 Redis：file:upload:{fileMd5} = 已上传的分片集合
    // 5. 返回已上传的分片索引列表（用于断点续传）
}

@PostMapping("/upload/merge")
public ResponseEntity<?> mergeChunks(
        @RequestParam("fileMd5") String fileMd5,
        @RequestParam("fileName") String fileName,
        @RequestParam("totalChunks") int totalChunks) {
    // 1. 检查所有分片都到了
    // 2. 从 MinIO 拉所有分片
    // 3. 合并成完整文件
    // 4. 上传到正式桶
    // 5. 创建 FileUpload 记录
    // 6. 触发异步向量化（Kafka 消息）
}
```

**分片上传的关键设计点**：

1. **分片大小**：常见 5MB。**太小**：请求次数多、签名开销大。**太大**：超过某些代理的 body 限制（Tomcat 默认 2MB）。
2. **临时桶 vs 正式桶**：**临时桶**存分片，TTL 7 天（自动清理）。**正式桶**存合并后的文件，永久。
3. **秒传**：如果 `fileMd5` 已存在且 MD5 相同，**直接返回成功**——不用真的上传。
4. **断点续传**：客户端重连时，服务端返回**已上传的分片列表**，客户端只传剩下的。

**面试考点 6**：分片上传的并发问题怎么解决？

A：两种方案：
- **乐观锁**：用 `version` 字段，更新时 `WHERE version = oldVersion`，影响行数 0 则重试。
- **分布式锁**：用 Redis `SETNX` 锁住 `file:{fileMd5}`，操作完释放。

`pai-smart` 用的是 Redis 锁（从 `TokenCacheService` 等的依赖推断）。

---

## 【五、MinIO 怎么访问？Presigned URL 模式**

对象存储不能直接给前端 accessKey——**那是 root 权限**。生产环境用 **Presigned URL**：

**原理**：服务端生成一个**有时效的 URL**，**带上签名**，前端拿这个 URL 直接传到 MinIO，**绕过应用服务器**。

```java
// 服务端
String presignedUrl = minioClient.getPresignedObjectUrl(
    GetPresignedObjectUrlArgs.builder()
        .method(Method.PUT)
        .bucket("documents")
        .object(fileMd5 + "/" + fileName)
        .expiry(60 * 60)  // 1 小时有效
        .build()
);

// 返回给前端
return Map.of("uploadUrl", presignedUrl);
```

**前端**：

```js
await fetch(presignedUrl, { method: 'PUT', body: file });
```

**好处**：
- **带宽不经过应用服务器**——大文件上传到 OSS 不挤压 Web 服务器。
- **accessKey 不暴露给前端**——签名有时间限制。
- **可吊销**——签名过期就废了。

`pai-smart` 是否用 Presigned URL，**要看 UploadController 完整代码**——我推测**早期没用**（直接服务端中转），**后来优化时改的**。

---

## 【六、常见坑 & 面试问答】

**Q1：MinIO 的 bucket 名有什么限制？**

A：
- 3-63 字符。
- 只能小写字母、数字、点、连字符。
- 必须以小写字母或数字开头。
- **不能是 IP 地址形式**（虽然技术上能）。
- **全局唯一**——同一 region 内不能和别人重名（但自建 MinIO 不受这个限制）。

**Q2：`isPublic` 字段用 `boolean` 还是 `Boolean`？**

A：
- **`boolean`**：基本类型，**不能为 null**，默认 `false`。**适合业务上一定有默认值的字段**。
- **`Boolean`**：包装类型，**可以为 null**。**适合"未设置"也是合法状态的字段**。

`pai-smart` 用 `boolean` 且默认 `false`——**合理**。`isPublic` 业务上**必有值**（要么公开、要么私有），**没有"未知"状态**。

**Q3：分片上传失败后，怎么清理临时文件？**

A：两种方式：
- **TTL 策略**：MinIO 的 lifecycle rule 设 7 天过期。
- **定时任务**：每天扫一次 `file_upload` 表，`status = UPLOADING` 且 `createdAt < now - 7 days` 的，**删 MinIO 文件 + 删 DB 记录**。

`pai-smart` 看代码应该两种都做了（MinIO 本身的 lifecycle + 应用层定时清理）。

**Q4：怎么防"恶意传超大文件"？**

A：
- **前端**：上传前限制文件大小（防 99% 攻击）。
- **后端 multipart 限制**：`spring.servlet.multipart.max-file-size = 100MB`。
- **后端业务校验**：`if (file.getSize() > MAX_SIZE) throw ...`。
- **MinIO 端**：`minio.bucket.quota` 限制桶总容量。

**Q5：MD5 真的够安全吗？文件去重场景？**

A：够。**MD5 碰撞需要精心构造**，**两个不同文件**算出来 MD5 相同的概率是 2^-128——**宇宙毁灭也算不出来**。但**安全场景别用**（密码、签名、证书），用 SHA-256。

---

## 【七、思考题：为什么 FileUpload 用 `Long id` 而不用 `String fileMd5` 当主键？**

**两种设计**：

**方案 A：自增 Long ID**（`pai-smart` 当前）：
```java
@Id @GeneratedValue(strategy = IDENTITY)
private Long id;
private String fileMd5;
```

**方案 B：MD5 当主键**：
```java
@Id
private String fileMd5;  // 32 字符
```

**对比**：
- **方案 A**：DB 主键索引快（int 比较比 String 快）、自增 ID 在分布式环境会冲突。
- **方案 B**：去重直接由 DB 约束保证（PK 冲突 = 重复文件）、**业务意义强**（"MD5 一样的就是同一文件"）。

**`pai-smart` 选 A**——**保守稳妥**。**但 B 在文件去重场景其实更优雅**。这是设计权衡，没有标准答案。

---

## 【八、下集预告】

文件能上传了、能存 MinIO 了，**但里面的内容我们还不知道**。

第 06 集我们要：

- 用 **Apache Tika** 解析 PDF / Word / Excel / PPT。
- 把提取出来的文本**分块**（Chunking）。
- **HanLP 中文分词**让中文文档分得更好。
- 流式处理大文件——**不爆内存**。

**文档解析是 RAG 系统的"上游"——解析质量直接决定 RAG 效果。**

我们下期见。

---

> 如果你觉得这一集的"演进式"讲解比贴代码好懂，**点赞、投币、一键三连**。
> 评论区告诉我你最想听哪一集的深度展开。

下一集链接：[第 06 集：文档解析 Pipeline——Tika 与文本分块](./06-document-parsing-pipeline.md)
