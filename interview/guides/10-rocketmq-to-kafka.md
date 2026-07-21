# 10 RocketMQ 篇：只抽取消息队列共性，映射到 Kafka

来源：`面渣逆袭RocketMQ篇.pdf`。原书有 23 个题号；PDF 在“刷盘”和“负载均衡”处把两个问题都标成了 Q22，本文记为 Q22a/Q22b，避免漏题。

PaperLoom **没有使用 RocketMQ**，实际使用的是 Kafka。因此下面每一题都先保留原书问题和共性知识，再把能在代码中核对的 Kafka 做法单独说清楚；没有代码证据的 RocketMQ、NameServer、CommitLog 等内容只能作为对照，不能背成项目经历。

## 先记住项目事实边界

- 上传合并后，`PaperUploadController` 先保存 MySQL 的 `PROCESSING` 状态，再用 `KafkaTemplate.executeInTransaction` 发送 `PaperProcessingTask`；Kafka 事务不包含 MySQL。
- `KafkaConfig` 配置 `acks=all`、幂等生产者、自动重试 3 次、事务前缀；消费者关闭自动提交，失败由 `DefaultErrorHandler` 每 3 秒重试，最多 4 次，随后投到 `paper-processing-dlt`。
- `application.yml` 默认创建 3 个分区、监听并发 3、复制因子 1。分区数要不小于有效消费者并发；复制因子为 1 只是当前配置，不能说已经验证 Broker 高可用。
- Producer 用 `paperId` 作为 key；Kafka 只能保证同一分区内有序，不能保证所有论文的全局顺序。
- `PaperProcessingConsumer` 先检查论文是否已 searchable，重复任务直接跳过；失败会重新抛出，不能吞掉异常，否则容器可能确认消息。
- 消息语义仍按至少一次处理来设计。`acks=all` 和幂等生产者不是业务幂等，也不能消除 MySQL 与 Kafka 的双写窗口。

## 原书 23 题总表

| 题号 | PDF 页 | 原题 | 取舍 | PaperLoom 关联 |
| --- | ---: | --- | --- | --- |
| Q1 | 1 | 为什么要使用消息队列呢？ | 必背项目题 | 上传与解析索引解耦、异步、削峰边界 |
| Q2 | 3 | 为什么要选择 RocketMQ？ | 必背对比 | 实际选 Kafka，按可靠性、吞吐、运维和生态比较 |
| Q3 | 5 | RocketMQ 有什么优缺点？ | 选背对比 | 不认领 RocketMQ 指标，准备 Kafka 取舍 |
| Q4 | 6 | 消息队列有哪些消息模型？ | 必背 | 队列模型与发布/订阅；映射 Kafka Topic/Group |
| Q5 | 7 | RocketMQ 的消息模型呢？ | 必背对照 | Topic、队列/分区、Group、Offset |
| Q6 | 8 | 消息的消费模式了解吗？ | 选背 | RocketMQ 集群/广播；Kafka Group 竞争消费 |
| Q7 | 9 | RocketMQ 基本架构了解吗？ | 选背对照 | RocketMQ NameServer/Broker 与 Kafka Broker/Controller 区别 |
| Q8 | 10 | 介绍这四部分 | 选背对照 | Producer、Consumer、Broker、注册/元数据边界 |
| Q9 | 13 | 如何保证消息可用、可靠、不丢失？ | 必背项目题 | acks、幂等、重试、手动提交、DLT |
| Q10 | 15 | 如何处理消息重复？ | 必背项目题 | searchable 状态、唯一约束、业务幂等 |
| Q11 | 15 | 怎么处理消息积压？ | 必背 | Lag、分区、并发、下游瓶颈；不能盲目扩容 |
| Q12 | 17 | 顺序消息如何实现？ | 必背项目题 | `paperId` key 只保证论文维度分区内顺序 |
| Q13 | 23 | 如何实现消息过滤？ | 了解 | 项目没有按 Tag/SQL 过滤，Consumer 按状态跳过 |
| Q14 | 24 | 延时消息了解吗？ | 了解对照 | 项目没有延时消息；重试是固定退避，不是延时业务消息 |
| Q15 | 25 | 如何实现分布式消息事务？半消息？ | 必背边界 | RocketMQ 半消息对照 Kafka 事务；不能覆盖 MySQL |
| Q16 | 26 | 死信队列知道吗？ | 必背项目题 | `paper-processing-dlt`、原分区、人工处理边界 |
| Q17 | 27 | 如何保证 RocketMQ 高可用？ | 必背对照 | 多副本/ISR 原理；当前复制因子默认 1 |
| Q18 | 29 | 说一下 RocketMQ 整体工作流程 | 选背对照 | 不把 NameServer 流程说成 Kafka 流程 |
| Q19 | 30 | 为什么 RocketMQ 不使用 ZooKeeper 注册中心？ | 了解对照 | Kafka 新旧元数据管理只作比较，不认领项目部署 |
| Q20 | 30 | Broker 是怎么保存数据的？ | 了解对照 | CommitLog/ConsumeQueue 对照 Kafka 分区日志 |
| Q21 | 34 | RocketMQ 怎么对文件进行读写？ | 了解 | PageCache、顺序写、零拷贝；项目未自实现 |
| Q22a | 35 | 消息刷盘怎么实现？ | 了解对照 | 同步/异步刷盘；Kafka `acks` 不等于磁盘策略全知晓 |
| Q22b | 36 | RocketMQ 的负载均衡如何实现？ | 必背项目题 | 分区数、Consumer Group、并发上限 |
| Q23 | 40 | RocketMQ 消息长轮询了解吗？ | 了解对照 | Kafka 拉取/等待与项目 Listener 抽象边界 |

## 第一轮必须拿下

Q1、Q4-Q5、Q9-Q12、Q15-Q17、Q22b。二面最容易追问的是：为什么不用 RocketMQ、Kafka 事务为什么不能覆盖 MySQL、重复消息怎么收敛、DLT 后怎么办、分区和并发怎么匹配。

## Q1-Q3：为什么用 MQ，为什么是 Kafka 而不是 RocketMQ

### Q1 为什么使用消息队列

原书的三句话是**解耦、异步、削峰**。PaperLoom 的真实链路是：HTTP 合并请求完成后，把 `PaperProcessingTask` 放入 Kafka；解析 PDF、保存阅读模型和构建检索索引由 Consumer 完成，而不是让上传请求同步等待整条耗时链路。

```text
上传/合并请求
      │ 只保存 PROCESSING + 发任务
      ▼
Kafka paper-processing-topic
      ▼
Consumer：解析 → 保存分片事实 → 构建检索投影
      ├─成功：READY/searchable
      └─失败：重试 → DLT
```

这样做让上传接口和重处理解耦，也让突发任务先进入队列缓冲。代价是引入了最终一致、重复消费、消息积压、失败重放和可观测性问题；不能只说“用了 MQ 所以性能变高”。

### Q2 为什么选择 RocketMQ？项目为什么用 Kafka

原书给出的选型维度是可靠性、性能/吞吐、功能、可运维性、可扩展性和社区生态，并把 RocketMQ 描述为高吞吐、可靠、Java 生态友好，把 Kafka 描述为吞吐量和兼容性强但批量攒取可能带来延迟。面试时不要把书中结论当成绝对排名，要说明业务约束和版本部署。

项目的准确回答是：**PaperLoom 实际用了 Kafka，不是 RocketMQ。** Java/Spring Kafka 已经提供 Producer、Consumer、事务发送、JSON 序列化和 DLT 组件，足以承载“上传后异步解析索引”这条任务链。选择 Kafka 不是因为项目验证了某个吞吐数字，而是因为现有依赖和代码边界就是 Kafka。若迁移到 RocketMQ，需要重新评估客户端、运维、消费重试、事务和监控，不是改一个连接地址。

### Q3 RocketMQ 的优缺点

按原书准备四句即可：RocketMQ 常见优势是吞吐和堆积能力、消息功能较完整、Java 生态结合方便；代价是客户端语言和生态覆盖相对有限、迁移兼容成本可能更高。不要背“十万级”“十亿级”成 PaperLoom 的实测结果，项目没有这样的压测证据。

Kafka 的对照说法：日志分区模型、消费组和生态兼容性强，适合持续事件流；但分区规划、Broker 存储和消费 Lag 需要运维，低延迟配置与吞吐也要按实际场景压测。PaperLoom 只证明了功能链路，不证明生产级吞吐或高可用指标。

## Q4-Q8：消息模型、消费模式与架构

### Q4 队列模型与发布/订阅模型

队列模型里，多个 Consumer 竞争同一个队列，一条消息通常只交给其中一个 Consumer；发布/订阅模型里，不同订阅者或消费组各自得到一份完整消息流。现实中 MQ 常用“Topic + Consumer Group”同时表达两种关系：同组分摊，不同组各自消费。

### Q5 RocketMQ 消息模型，映射到 Kafka

原书概念对应关系如下：

| RocketMQ | Kafka | PaperLoom 含义 |
| --- | --- | --- |
| Topic | Topic | `paper-processing-topic` |
| Message Queue | Partition | 按 `paperId` key 分区 |
| Consumer Group | Consumer Group | `paper-processing-group` |
| Consumer Offset | Offset | 消费位置由容器管理 |
| Tag/业务 Key | Header、Key 或消息字段 | 任务体含 `paperId`，Producer key 也是 `paperId` |

同一个 Group 内，一个分区同一时刻只分配给一个 Consumer；不同 Group 可以各自从自己的 Offset 消费同一 Topic。不要把“一个 Topic 只有一个消费者”或“Kafka key 保证全局顺序”说错。

### Q6 集群消费与广播消费

RocketMQ 的 Clustering 是同组竞争消费，Broadcasting 是组内每个消费者都收到消息。PaperLoom 使用 Kafka Consumer Group 的竞争消费模型；没有实现“每个实例都收到完整任务”的广播消费。如果业务真的需要广播，应设计独立 Group 或显式 fan-out，而不是在一个 Group 里复制处理。

### Q7-Q8 RocketMQ 四部分与 Kafka 对照

原书四部分是 NameServer（路由发现）、Broker（存储转发）、Producer（发送）和 Consumer（消费）。Kafka 的现代部署通常由 Broker 保存分区日志并参与元数据/控制器机制，客户端从 bootstrap server 获取元数据；Kafka 不应直接套用 NameServer、CommitLog 和 RocketMQ Push/Pull 术语。PaperLoom 代码只直接依赖 `KafkaTemplate`、`@KafkaListener` 和 Spring Kafka 容器，未实现或运维 NameServer。

```text
PaperLoom Producer              Kafka Broker/Partition
KafkaTemplate --paperId key--> [paper-processing-topic]
                                      │
                                      ▼
                           Consumer Group: paper-processing-group
```

Q8 的发送方式可以这样背：同步发送等待确认，异步发送通过回调/未来值处理结果，单向发送不等待确认。项目的 `executeInTransaction` 是事务发送，不应简化成 RocketMQ 的“单向消息”；发送结果和事务提交仍需让异常可见。

## Q9-Q12：可靠性、重复、积压和顺序

### Q9 如何保证消息可靠、不丢失

按生产、Broker、消费三段回答：

1. **生产：** 等待发送结果，失败重试；幂等生产者减少重试造成的重复写入。PaperLoom 配置 `acks=all`、`enable.idempotence=true`、`retries=3`，并用事务前缀发送任务。
2. **存储：** 生产环境要检查副本、ISR、持久化和故障恢复。PaperLoom 默认复制因子为 1，只能说配置了可靠性参数，不能宣称 Broker 高可用。
3. **消费：** 业务成功后再提交 Offset；失败必须让错误处理器感知。项目关闭自动提交，Consumer 失败重新抛出，由 `DefaultErrorHandler` 每 3 秒重试，最多 4 次后交给 DLT。

“不丢失”不等于“绝不重复”。网络超时可能让发送方不知道 Broker 是否已写入；消费进程在业务完成、Offset 提交之间崩溃，也会再次收到消息，所以最终仍要靠业务幂等。

### Q10 如何处理重复消息

原书给两种方法：让消费逻辑幂等；或者用唯一业务编号和消费记录去重。PaperLoom 的可核对防线是：

- `paperId` 是任务业务键；Consumer 发现论文已经 searchable 就跳过重复解析和索引。
- `ChunkInfo` 的 `(file_md5, chunk_index)` 唯一约束收敛重复分片。
- 论文合并用状态条件更新；已经完成的请求按幂等成功返回，正在合并则返回冲突。
- 失败任务重新抛出交给重试，不能靠“吞异常后提交”假装去重。

Kafka 的幂等 Producer 只解决 Producer 重试的一段，不会替代数据库唯一约束、状态机或 Consumer 幂等。当前没有一张独立的“消息消费记录表”可以声称已经实现。

### Q11 如何处理消息积压

先看 Consumer Lag、分区是否均衡、单条处理耗时、失败重试比例、Broker 磁盘和下游解析/Qdrant/数据库瓶颈，再决定动作。若分区多于消费者，可扩 Consumer；若分区已经成为上限，盲目加实例没有收益，需要增加分区或拆分任务，并同步评估顺序、数据库连接和下游容量。

项目默认 3 分区、监听并发 3、`max.poll.records=1`，处理一篇论文可能较慢，`max.poll.interval.ms` 默认 7200000 毫秒以避免长处理触发再均衡。这里是配置事实，不是“已经压测出每秒多少条”。扩容还要防止重复处理、重试风暴和 Qdrant/数据库被打满。

### Q12 如何实现顺序消息

原书把顺序分为全局顺序和局部顺序。局部顺序的做法是让同一业务 ID 的消息进入同一队列，并在消费端串行处理；全局顺序必须把整个 Topic 限制为单队列、单消费并发，吞吐会明显下降。

项目发送时使用 `kt.send(topic, request.paperId(), task)`，`paperId` 作为 Kafka key，因此同一论文的任务会路由到同一分区，获得论文维度的局部顺序；不同论文之间没有全局顺序保证，也没有业务需要。分区内仍要避免多个线程并行改同一业务状态，顺序不能只靠 key 口头保证。

## Q13-Q17：过滤、延时、事务、死信与高可用

### Q13 消息过滤

原书列出 Consumer 端过滤、Broker 端过滤，以及 Tag、SQL 表达式、Filter Server 等方式。Broker 过滤可减少无用传输但增加 Broker 负担，Consumer 过滤简单但消息已经到达客户端。

PaperLoom 没有按 RocketMQ Tag/SQL 订阅；`PaperProcessingConsumer` 的 searchable 判断是**业务幂等短路**，不是 Broker 消息过滤。不能把“发现已 searchable 就 return”说成 Kafka 已经过滤了消息。

### Q14 延时消息

延时消息是在未来时间点才让消费者看到，典型场景是订单超时取消。RocketMQ 有延时级别；Kafka 通常需要应用层时间轮、延迟 Topic、定时任务或外部调度器组合实现。

项目没有延时消息实现。`FixedBackOff(3000L, 4)` 只是失败重试间隔，不能回答成“我们用了延时消息”。

### Q15 分布式消息事务/半消息

RocketMQ 半消息流程要记住：Producer 先发不可投递的半消息，执行本地事务，成功则 Commit、失败则 Rollback；Broker 长时间收不到二次确认会回查 Producer 的本地事务状态。

项目用的是 Kafka 事务：

```text
MySQL save(PROCESSING)
        │ 事务边界在 MySQL
        ▼
Kafka executeInTransaction(send task)
        │ 事务边界在 Kafka
        ▼
Consumer 解析 / 索引 / 更新状态
```

`executeInTransaction` 可以保证 Kafka 事务会话内的生产可见性，但不能让前面的 MySQL `save` 与 Kafka `send` 共用一个原子提交。因此仍有“数据库成功、消息失败”和“消息已发、数据库提交异常”的双写窗口。更强的改进是 Outbox：在同一 MySQL 事务写业务状态和待发送事件，再由发布器可靠投递并按事件 ID 幂等；这只是改进方向，不是项目现状。

### Q16 死信队列

死信是消息多次处理失败、无法继续自动重试后转移的消息。它的价值是隔离坏消息、避免阻塞正常分区，同时保留排查、修复和重放入口。

项目 `DeadLetterPublishingRecoverer` 把重试耗尽的消息发送到 `paper-processing-dlt`，并保持原分区号。当前代码没有完整的告警、人工修复和自动重放流程，面试时只能说“已投递 DLT，后续需要运维流程”，不能声称 DLT 自动修复一切。

### Q17 RocketMQ 高可用

原书答案重点是 Broker 集群、主从复制、多个消息队列分布在不同 Broker 组，以及故障转移。Kafka 对应的是多 Broker、副本、Leader/ISR、重新选举和容量/跨机架规划。

PaperLoom 的 `replication-factor` 默认值是 1，Docker 示例也使用单副本；没有多 Broker 故障演练证据。因此只能回答高可用原理和生产规划，不能说项目已经做到“消息零丢失”或“Broker 自动故障转移”。

## Q18-Q20：工作流程、注册中心与存储

### Q18 RocketMQ 整体流程

按原书背：Broker 启动向 NameServer 注册并发送心跳；Producer/Consumer 从 NameServer 获取路由；Producer 发往 Broker，Consumer 按队列和 Offset 消费。补一句：这是 RocketMQ 流程，PaperLoom 的实际客户端是 Kafka，不要把 NameServer 画进项目架构。

Kafka 项目流程只需说清：Producer 从 bootstrap server 获取 Topic 元数据，按 key 选择分区；Broker 持久化分区日志；同一 Consumer Group 的实例分担分区；提交的 Offset 决定下次从哪里继续。

### Q19 为什么 RocketMQ 不用 ZooKeeper

原书从可用性、轻量扩展、注册中心不需要很重的持久化以及客户端缓存路由等角度解释 NameServer。面试时可以把它作为 RocketMQ 设计对比题，但不要把“项目不用 ZooKeeper”说成“项目使用了 NameServer”。PaperLoom 代码没有自建注册中心，Kafka 的元数据和控制器由 Kafka 集群负责，部署细节以实际环境为准。

### Q20 Broker 如何保存数据

RocketMQ 的核心文件是 CommitLog（消息主体顺序日志）、ConsumeQueue（按 Topic/队列组织的索引）和 IndexFile（按 key/时间查询的索引）。

Kafka 的对照是每个 Partition 的追加日志段、Offset 索引和时间索引；Consumer Group 保存消费 Offset。两者都利用顺序追加和索引降低读取成本，但文件名、复制协议和清理策略不同。PaperLoom 只使用 Kafka API，没有直接读写 Broker 文件，不能把 CommitLog 代码说成项目实现。

## Q21-Q23：文件 IO、刷盘、负载均衡与长轮询

### Q21 文件读写与零拷贝

原书把 PageCache、顺序读写和零拷贝作为 RocketMQ 高吞吐基础。传统路径会在内核态和用户态之间多次复制；`mmap`/`MappedByteBuffer`、`sendfile` 等机制可以减少复制和上下文切换，但具体收益取决于操作系统和硬件。

PaperLoom 没有实现 Broker 文件层或零拷贝，不能说“项目通过零拷贝优化了 Kafka”。项目 Java 代码中的文件读取属于业务层 PDF/流处理，和 Kafka Broker 存储实现是不同边界。

### Q22a 消息刷盘

RocketMQ 有同步刷盘和异步刷盘：同步刷盘等持久化完成再返回，可靠性更强但延迟更高；异步刷盘先返回、后台再落盘，吞吐更高但掉电窗口更大。Kafka 的 `acks=all` 表示 Producer 等待 ISR 确认，不等价于“我已经验证了磁盘每个缓存策略”；刷盘和副本策略仍由 Broker 配置决定。

### Q22b 负载均衡

RocketMQ Producer 在队列间选择目标，Consumer 在同一 Group 内把 MessageQueue 分配给不同实例；同一队列同一时刻只由一个消费者处理，实例变化会触发再均衡。

Kafka 的同构回答是：Topic 分区是并发基本单位，Group Coordinator 将分区分配给消费者；有效消费并发上限受分区数约束。PaperLoom 默认 3 分区、3 listener concurrency，单实例/多实例部署仍要根据实际分区和下游容量调整；扩并发不等于吞吐线性增长。

### Q23 长轮询

原书的 RocketMQ 长轮询是：Queue 暂时没有消息时，Broker 暂存 PullRequest，直到有新消息或超时再返回，减少空拉取。Kafka Consumer 的 `poll` 也可以等待数据，但实现和参数不同，不能把 RocketMQ 的 `PullRequestHoldService` 说成项目代码。PaperLoom 使用 Spring Kafka Listener 容器，没有直接控制 Broker 长轮询实现；只需关注 `poll` 间隔、处理耗时和 `max.poll.interval.ms` 不要触发再均衡。

## 消息队列项目标准回答

> PaperLoom 使用 Kafka 把 PDF 上传合并和解析、阅读模型保存、检索索引解耦。Producer 以 `paperId` 为 key，配置 `acks=all`、幂等和事务发送；Consumer 关闭自动提交，失败由固定 3 秒退避、最多 4 次重试处理，耗尽进入 `paper-processing-dlt`。同一论文的 key 只能提供分区内局部顺序，业务重复还要靠 searchable 状态、合并状态和数据库唯一约束收敛。Kafka 事务只覆盖 Kafka，MySQL 状态与消息发送仍有双写窗口，不能说 exactly-once；Outbox 是后续改进方向。项目实际没有 RocketMQ、NameServer、延时消息、Broker 零拷贝优化或多副本高可用实测。

## 绝对不能说错的边界

| 追问 | 可以说 | 不可以说 |
| --- | --- | --- |
| 为什么用 MQ | 上传和耗时解析/索引解耦，异步化，队列缓冲突发任务 | MQ 自动提升所有接口性能、天然不丢消息 |
| RocketMQ 经验 | 了解其 Topic/Queue/Group/Offset 和半消息原理 | “项目使用 RocketMQ”“做过 RocketMQ 调优” |
| Kafka 可靠性 | `acks=all`、幂等、重试、关闭自动提交、DLT | 已验证零丢失、exactly-once 或多副本故障转移 |
| Kafka 事务 | Kafka 事务内发送可原子可见 | MySQL save 与 Kafka send 已经一个原子事务 |
| 重复消息 | searchable、状态条件更新、唯一约束 | 只要开启幂等 Producer 就不会重复 |
| 顺序 | `paperId` key 带来同论文分区内顺序 | Kafka 保证全局顺序 |
| 积压 | 看 Lag、分区、处理耗时和下游瓶颈，再扩容/拆分 | 只加 Consumer 实例就一定解决 |
| DLT | 重试耗尽进入 `paper-processing-dlt`，保留排查入口 | 已有自动告警、修复、重放闭环 |
| 高可用 | 解释副本、ISR、Leader 和分区规划 | 当前默认复制因子 1 已经生产高可用 |
| 零拷贝/刷盘 | 能解释 PageCache、mmap、同步/异步刷盘 | PaperLoom 自己实现了 Broker 零拷贝 |

## 对应代码

- `../src/main/java/io/github/chzarles/paperloom/config/KafkaConfig.java`：Topic、分区/副本、`acks=all`、幂等 Producer、事务前缀、关闭自动提交、重试和 DLT。
- `../src/main/java/io/github/chzarles/paperloom/controller/PaperUploadController.java:337`：合并状态条件更新；`385-396`：保存 MySQL `PROCESSING` 后发送 Kafka 事务任务。
- `../src/main/java/io/github/chzarles/paperloom/consumer/PaperProcessingConsumer.java:50`：Listener 的 Topic/Group；`52-61`：searchable 重复短路；`82-104`：失败重抛和流关闭。
- `../src/main/java/io/github/chzarles/paperloom/model/ChunkInfo.java`：`(file_md5, chunk_index)` 唯一约束。
- `../src/main/resources/application.yml:47-67`：Producer/Consumer、3 分区、3 并发、默认复制因子 1 和长处理 poll 间隔。

## 最后背一遍：Kafka 项目自我介绍

> 我们实际使用 Kafka，不是 RocketMQ。上传合并后先把论文状态写成 PROCESSING，再通过 `KafkaTemplate.executeInTransaction` 发送 `PaperProcessingTask`，Consumer 负责解析和索引。Producer 配置 `acks=all`、幂等和重试，Consumer 关闭自动提交，失败固定退避，耗尽进 `paper-processing-dlt`。重复任务由 searchable 状态、合并状态和数据库唯一约束收敛；`paperId` 作为 key 只保证同一论文在同一分区内有序。Kafka 事务不包含 MySQL，所以仍有双写窗口，不能把它说成 exactly-once。RocketMQ 的半消息、NameServer、CommitLog 和长轮询我可以讲原理，但都不是 PaperLoom 的已实现实践。
