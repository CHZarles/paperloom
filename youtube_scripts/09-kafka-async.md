# 第 09 集：Kafka 登场——把同步变异步

> 系列：派聪明 RAG 后端演进全解
> 集数：09 / 20+
> 主题：Kafka 概念、Topic / Partition / Offset、生产者消费者、失败重试 + DLT
> 上集回顾：第 08 集 ES 集成完成，向量能存能查
> 本集目标：把「上传文件→解析→向量化」拆成异步流水线

---

## 【开场 Hook】

第 8 集结束时，我们有了一个**看似能用的 RAG 流程**：

```
HTTP 上传文件 → 同步解析 → 同步 embedding → 写 ES
```

**但是！** 一个 100MB 的 PDF：
- 解析：30 秒
- embedding：60 秒（1000 个 chunk × 60ms）
- 写 ES：5 秒

**总耗时 95 秒**。

**这 95 秒里，HTTP 连接一直挂着**。**Tomcat 默认超时 60 秒**——**直接 504**！**用户得重试**——**然后又挂了 95 秒**。

这是**经典的"长任务阻塞 Web 线程"问题**。

`pai-smart` 的解法：**Kafka**——把 95 秒的同步流程**拆成异步任务**。

```
HTTP 上传文件 → 立即返回（耗时 200ms）
                      ↓
                Kafka 消息
                      ↓
            后台 Worker 消费（耗时 95s）
                      ↓
                写 ES 完成
```

**用户立刻拿到 200**，**后台慢慢处理**。**这就是异步化**。

今天我们深挖 `FileProcessingConsumer` 和 `KafkaConfig` 怎么做到。

---

## 【一、为什么是 Kafka，不是 Redis Stream 或 RabbitMQ？】

**消息队列的本质**：**一个 FIFO 的队列**——生产者 put，消费者 take。

**三种常见选择**：

| 维度 | Redis Stream | RabbitMQ | Kafka |
|---|---|---|---|
| 持久化 | 有（但容量小） | 有 | **有（TB 级）** |
| 吞吐 | 10 万 / 秒 | 万级 | **百万级** |
| 顺序 | 单 partition | 单 queue | **单 partition** |
| 消费模型 | 至少一次 | push / pull | **pull** |
| 适用场景 | 轻量消息 | 复杂路由 | **日志 + 大数据** |

`pai-smart` 选 Kafka——**因为文档处理是"大块任务"**：
- 单条消息大（包含文件路径、元数据）。
- 处理慢（**95 秒/条**）。
- 顺序不重要（**不同文件之间**）。

**Kafka 的优势**：
- **持久化**：Broker 集群，**数据不丢**。
- **重平衡**：消费者增减，**自动 rebalance**。
- **死信队列**：处理失败的消息**有归宿**。

**面试考点 1**：Kafka 为什么吞吐这么高？

A：**顺序写磁盘** + **零拷贝**（`sendfile` 系统调用） + **page cache 优先**。**单 broker 100MB/s 写入**。

---

## 【二、`FileProcessingTask`：消息体的设计】

```java
public class FileProcessingTask {
    public static final String TASK_TYPE_PROCESS = "PROCESS";
    public static final String TASK_TYPE_REINDEX = "REINDEX";
    
    private String fileMd5;       // 哪个文件
    private String userId;        // 谁传的
    private String orgTag;        // 哪个组织
    private boolean isPublic;     // 是否公开
    private String filePath;      // 在哪（MinIO URL / 本地路径）
    private String taskType;      // 任务类型
    private String requesterId;   // 请求人（重索引时可能是管理员）
}
```

**几个设计点**：

1. **不带文件内容**：消息体只带**路径**。**文件本身在 MinIO**。**避免消息过大**（Kafka 单条消息默认 1MB 上限）。
2. **`taskType` 区分 PROCESS / REINDEX**：同一个 topic 支持**两种任务类型**——**避免维护多套队列**。
3. **包含权限信息**：`userId` / `orgTag` / `isPublic` ——**消费者处理时直接用**，**不用再查 DB**。

**面试考点 2**：消息体应该多大？

A：
- **< 1MB**：Kafka 默认。
- **< 10MB**：调 `message.max.bytes` 后可以。
- **> 10MB**：**必须存外部**（MinIO、S3），消息里带 URL。

`pai-smart` 选「消息里带路径」是**正确选择**。

---

## 【三、`FileProcessingConsumer`：消费流程】

```java
@KafkaListener(topics = "#{kafkaConfig.getFileProcessingTopic()}", 
               groupId = "#{kafkaConfig.getFileProcessingGroupId()}")
public void processTask(FileProcessingTask task) {
    documentService.markVectorizationProcessing(task.getFileMd5(), false);

    if (FileProcessingTask.TASK_TYPE_REINDEX.equals(task.getTaskType())) {
        processReindexTask(task);
        return;
    }

    InputStream fileStream = null;
    try {
        // 1. 下载文件
        fileStream = downloadFileFromStorage(task.getFilePath());
        if (fileStream == null) throw new IOException("流为空");
        if (!fileStream.markSupported()) {
            fileStream = new BufferedInputStream(fileStream);
        }

        // 2. 解析文件
        parseService.parseAndSave(task.getFileMd5(), fileStream, 
                task.getUserId(), task.getOrgTag(), task.isPublic());

        // 3. 向量化
        VectorizationService.VectorizationUsageResult vectorizationResult = 
            vectorizationService.vectorizeWithUsage(
                task.getFileMd5(), task.getUserId(), task.getOrgTag(), 
                task.isPublic(), task.getUserId()
        );

        // 4. 标记完成
        documentService.markVectorizationCompleted(task.getFileMd5(), vectorizationResult);
    } catch (Exception e) {
        // 5. 标记失败 + 让 Kafka 重试
        documentService.markVectorizationFailed(task.getFileMd5(), e);
        throw new RuntimeException("Error processing task", e);
    } finally {
        if (fileStream != null) try { fileStream.close(); } catch (IOException e) { ... }
    }
}
```

### 3.1 `@KafkaListener`：Spring Kafka 的核心注解

```java
@KafkaListener(topics = "...", groupId = "...")
public void processTask(FileProcessingTask task) { ... }
```

**Spring Kafka 自动**：
1. 创建消费者（`KafkaConsumer`）。
2. **JSON 反序列化** `task` 参数（**`pai-smart` 配置了 `JsonDeserializer`**）。
3. 调用方法。
4. **方法正常返回 → 自动提交 offset**。
5. **方法抛异常 → 不提交 offset，触发重试**。

**面试考点 3**：`groupId` 是什么？

A：**消费者组**。**同一 group 的多个实例共同消费一个 topic**——**一个 partition 只能被 group 内一个实例消费**。
- **扩展消费者**：加机器即可，**Kafka 自动 rebalance**。
- **多个 group**：每个 group 都消费全量——**类似「发布订阅」**。

### 3.2 `downloadFileFromStorage`：MinIO / 文件系统 / URL 三合一

```java
private InputStream downloadFileFromStorage(String filePath) {
    // 1. 文件系统路径
    File file = new File(filePath);
    if (file.exists()) return new FileInputStream(file);

    // 2. HTTP/HTTPS URL
    if (filePath.startsWith("http://") || filePath.startsWith("https://")) {
        URL url = new URL(filePath);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        // ...
        return conn.getInputStream();
    }

    throw new IllegalArgumentException("Unsupported file path format: " + filePath);
}
```

**这是「适配器模式」的简化版**——一个方法支持三种来源。

**生产环境的问题**：
- **MinIO 没有专门分支**？看代码——**MinIO 流走 "filePath 是 MinIO presigned URL" 那个分支**。**但没看到显式判断**。
- **可能是后期重构留下的**——早期代码支持本地文件，后期支持 MinIO，但代码没更新注释。

**这种"代码赶不上注释"的情况在老项目里非常常见**。

### 3.3 失败处理：标记 + 重抛

```java
} catch (Exception e) {
    documentService.markVectorizationFailed(task.getFileMd5(), e);
    throw new RuntimeException("Error processing task", e);
}
```

**两件事**：
1. **业务失败标记**：`FileUpload.vectorizationStatus = FAILED`。
2. **重抛异常**：让 Spring Kafka 触发**重试 / 死信**。

**业务失败和重试是两件事**——**都做**：
- **业务失败**让用户能在 UI 看到「上传失败」状态。
- **重试**让 transient 错误（**网络抖动、ES 临时挂**）能恢复。

---

## 【四、失败重试 + 死信队列（DLT）】

`pai-smart` 的 `KafkaConfig` 应该配了 **DefaultErrorHandler**：

```java
@Bean
public DefaultErrorHandler errorHandler(KafkaTemplate<String, String> template) {
    return new DefaultErrorHandler(
        new DeadLetterPublishingRecoverer(template, 
            (record, ex) -> new TopicPartition("file-processing-dlt", record.partition())),
        new ExponentialBackOff(1000, 2.0)  // 初始 1s, 翻倍
    );
}
```

**工作流程**：
1. **消息处理失败** → 等待 1s。
2. **再处理** → 还失败 → 等待 2s。
3. **再处理** → 还失败 → 等待 4s。
4. ... 重试 N 次后（默认 10 次）。
5. **送到 DLT**（Dead Letter Topic，**死信队列**）。

**死信队列**：
- **保留所有失败消息**——**人工排查**。
- **可以重放**——修好 bug 后**重投回主 topic**。

**面试考点 4**：为什么不直接重试无限次？

A：
- **bug 不会自动修好**：如果是代码 bug，**重试 100 次也是 fail**。
- **占资源**：retry 消息**占着 partition**，**新消息进不来**。
- **消费阻塞**：单个 partition 顺序处理，**一条卡住后面都卡**。

`pai-smart` 选「指数退避 + 死信」是**正确选择**。

---

## 【五、状态机：上传 → 解析 → 完成】

```java
// 1. 开始处理
documentService.markVectorizationProcessing(task.getFileMd5(), false);

// 2a. 成功
documentService.markVectorizationCompleted(task.getFileMd5(), vectorizationResult);

// 2b. 失败
documentService.markVectorizationFailed(task.getFileMd5(), e);
```

**`FileUpload` 实体里的状态机**（第 5 集讲过）：
- `PENDING` → `PROCESSING` → `COMPLETED` / `FAILED`

**状态机的关键**：
- **UI 可以轮询**——`GET /documents/{fileMd5}/status` 拿到当前状态。
- **失败可重试**——`POST /documents/{fileMd5}/reindex` 重新发 Kafka 消息。

**`pai-smart` 后期加了** `markVectorizationProcessing(..., false)` 第二个参数——**应该是「是否静默」标记**——避免某些场景下重复发通知。

---

## 【六、`KafkaConfig`：生产者消费者配置】

虽然没贴完整代码，但标准配置应该包括：

```java
@Configuration
public class KafkaConfig {

    @Value("${kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, FileProcessingTask> producerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");  // 强持久化
        config.put(ProducerConfig.RETRIES_CONFIG, 3);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, FileProcessingTask> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    @Bean
    public ConsumerFactory<String, FileProcessingTask> consumerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "file-processing-group");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, FileProcessingTask> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, FileProcessingTask> factory = 
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(3);  // 3 个并发消费者
        factory.setCommonErrorHandler(errorHandler(kafkaTemplate()));
        return factory;
    }
}
```

**几个关键配置**：

- **`acks: "all"`**：生产者等所有副本都收到。**强持久化**。
- **`auto.offset.reset: "earliest"`**：新 group 从头开始消费。**避免漏消息**。
- **`concurrency: 3`**：单实例起 3 个消费者线程。**水平扩展的最小单元**。

**面试考点 5**：`acks` 的几个级别？

A：
- **`acks=0`**：fire and forget，**可能丢**。
- **`acks=1`**：leader 收到就返回，**follower 没同步可能丢**。
- **`acks=all`**：所有 in-sync 副本都收到，**强一致**。

`pai-smart` 选 `all` 是**正确选择**——**文件处理任务不能丢**。

---

## 【七、Producer 在哪发消息？】

在 `DocumentService` 或 `UploadController` 里：

```java
@Service
public class DocumentService {

    @Autowired
    private KafkaTemplate<String, FileProcessingTask> kafkaTemplate;

    @Autowired
    private KafkaConfig kafkaConfig;

    public void uploadComplete(String fileMd5, String fileName, ...) {
        // 1. 落 FileUpload 记录
        // 2. 发 Kafka 消息
        FileProcessingTask task = new FileProcessingTask();
        task.setFileMd5(fileMd5);
        task.setUserId(userId);
        // ...
        kafkaTemplate.send(kafkaConfig.getFileProcessingTopic(), fileMd5, task);  // 用 fileMd5 当 key
    }
}
```

**用 `fileMd5` 当 key**：
- 同一文件的多个消息**进同一 partition**——**顺序处理**。
- **重试时也是同一 partition**——**避免并发问题**。

**面试考点 6**：Kafka 的 key 有什么用？

A：
- **相同 key 进相同 partition**。
- **相同 partition 顺序处理**。
- **保证同一文件的消息不乱序**——**重要**。

---

## 【八、消息幂等性：重复消费怎么办？**

**Kafka 是「至少一次」语义**——可能重复消费。

**重复消费的场景**：
1. **consumer 提交 offset 前挂掉**。
2. **重启后从上次 offset 重新消费**。

**`pai-smart` 的解法**：
- `FileUpload` 状态机：**PROCESSING 状态的消息**直接跳过（说明其他 consumer 正在处理）。
- `DocumentVector` 表 UNIQUE 约束：**重复插入忽略**。
- **业务操作幂等**（`markVectorizationCompleted` 检查状态）。

**面试考点 7**：幂等的三种实现方式？

A：
- **DB 唯一约束**：最可靠。
- **Redis SETNX**：用于分布式锁。
- **业务状态机**：状态判断防重。

`pai-smart` 用了**第 1 + 第 3 种**——**最稳**。

---

## 【九、WebSocket 推送：状态变化通知前端】

`pai-smart` 后面的版本里，用户上传文件后，**前端能实时看到「处理中 → 已完成」**——这是 WebSocket 推送的（第 10 集讲）。

**Kafka + WebSocket**：
1. Kafka consumer 处理完 → 调 `ChatSessionRegistry` 或 `WebSocketSession` 推送。
2. **前端无需轮询**——**体验流畅**。

---

## 【十、常见坑 & 面试问答】

**Q1：消息顺序重要吗？**

A：`pai-smart` 是单文件独立处理，**不同文件间不要求顺序**。**用 fileMd5 当 key，同一文件的消息进同一 partition**——保证**同文件顺序**。

**Q2：Kafka 集群挂了怎么办？**

A：消息**会堆积**（在 partition 里）。Kafka 恢复后**消费者从堆积处继续**。`pai-smart` **有 DLT**——**长期堆积会触发告警**。

**Q3：怎么处理 Kafka 消息体里的敏感信息？**

A：当前消息体**只有 fileMd5 / userId / orgTag**——**无敏感信息**。**不要把密码、token 放消息**——Kafka **不是加密存储**。

**Q4：为什么不用线程池替代 Kafka？**

A：
- **单实例**线程池**够用**——但**多实例怎么办**？Kafka 解决「多实例消费同一 topic 的并发问题」。
- **持久化**——线程池**消息在内存**——**重启就丢**。
- **重平衡**——线程池做不到。

**Q5：怎么监控 Kafka 消费 lag？**

A：
- **Kafka 自带**：`kafka-consumer-groups.sh --describe`。
- **Spring Boot Actuator**：暴露 `/actuator/metrics`。
- **告警**：用 `kafka-lag-exporter` + Prometheus + Grafana。

---

## 【十一、思考题：为什么不用 `ThreadPoolTaskExecutor` 走轻量路线？**

**轻量方案**：
```java
@Async
public void processFileAsync(String fileMd5) { ... }
```

**优点**：代码少，**没有外部依赖**。

**缺点**：
- **Web 应用重启** → 任务**全丢**。
- **多实例部署** → 每个实例都收到 HTTP 请求，**重复处理**。
- **背压控制**弱。

`pai-smart` 选 Kafka——**生产级异步**。**KISS 原则 vs 生产级**——**业务量小可以 `ThreadPoolTaskExecutor`**，**业务量大必须 Kafka**。

---

## 【十二、下集预告】

文档能上传、解析、向量化、写入 ES 了。

**但是！用户怎么"问"AI？**

第 10 集我们要：

- 引入 **WebSocket**——`ChatWebSocketHandler` 接收用户消息。
- 流式调用 **DeepSeek**——AI 回答一个 token 一个 token 推。
- 集成 **`ChatHandler`** 编排 RAG 流程。

**RAG 系统的"对话能力"登场**。

我们下期见。

---

> 这一期的「异步化」是后端工程师**分水岭级别**的技能。
> 觉得有用，**点赞、投币、收藏**。

下一集链接：[第 10 集：WebSocket 流式聊天——DeepSeek 首次对话](./10-websocket-deepseek.md)
