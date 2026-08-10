# PaperLoom 七个项目难点面试稿

下面不是编故事，而是把仓库已有实现和实验记录压缩成可口述版本。

## 1. 为什么把检索从 Python Worker 迁到 Java/Qdrant？

**90 秒回答：**

> 早期 Python Harness 会在每次研究任务里加载授权论文全文，并在 Worker 内建立 BM25。这个方案实现
> 简单，语料加载完成后窄查询也很快，但 Worker 横向扩容时，每个副本都要重复占用内存和构建索引。
> 我们后来把候选检索收回 Java 数据面，把 Current Reading Model 的 Location 投影到 Qdrant；Python
> 只保留请求级工具状态，候选命中后再通过 Java 从 MySQL 精确读取。76 篇论文测试里，旧路径每个 Worker
> 增加约 243 MiB RSS，广查询 p50 是 1.8 到 2.1 秒；Java/Qdrant 是 0.38 到 0.49 秒，大约快
> 4.3 到 4.9 倍。但窄查询变慢，早期 Hybrid 的精确 Anchor 召回也下降，所以我不会说迁移让质量变好。
> 最终保留共享检索架构，再单独优化 Sparse BM25，避免把扩展性问题和相关性问题混成一个问题。

**必备追问：**为什么 MySQL 仍是权威数据、为什么 Qdrant 只能返回候选、BM25、Sparse/Dense、top-k、
召回下降如何定位、如何回滚。

## 2. 如何防止 Agent 读取越权论文？

**90 秒回答：**

> 我没有把权限只写在 Prompt 里，而是做成确定性的工具协议。Java 先根据用户身份计算可访问论文，并在
> 第一次消息时通过数据库悲观锁冻结会话论文范围。Python 只能收到这个固定范围。进入 Agent Loop 后权限
> 还会继续收紧：先通过候选或身份工具公开论文，再通过位置检索公开 location_ref，只有准确读取这个位置
> 才创建 Evidence ID，最终答案只能引用已知 Evidence。这样模型即使生成了别的 paper_id 或 location_ref，
> 工具层也会拒绝。代价是状态更多、调用次数更多，但权限和引用正确性不依赖模型自觉。

**必备追问：**悲观锁与乐观锁、首条消息并发、JWT 和资源权限区别、TOCTOU、为什么 Prompt 不可靠。

## 3. PDF 异步处理如何保证可重试和幂等？

**90 秒回答：**

> PDF 使用分片上传。分片本体写 MinIO，Redis Bitmap 记录快速上传进度，但真正的幂等边界是 MySQL
> `(file_md5, chunk_index)` 唯一约束。合并时使用状态条件更新，只有一个请求能把状态从 UPLOADING 改成
> MERGING。合并完成后按 paperId 作为 key 发送 Kafka 任务，Consumer 负责解析和构建检索索引。Producer
> 配置了 acks=all 和幂等生产，Consumer 有固定退避、最大重试次数和 DLT，同时消费前会检查论文是否已经
> 可检索，避免重复执行昂贵任务。所以我把它定义为“至少一次投递 + 业务幂等”，不是端到端 exactly-once。
> 当前 MySQL 状态写入和 Kafka 发送仍不是一个原子事务，如果要继续强化，我会用 Outbox。

**必备追问：**重复消息、消息顺序、分区和并发、消息积压、DLT 重放、Outbox、本地事务与 MQ 一致性。

## 4. Redis 在项目里到底做了什么？

**90 秒回答：**

> Redis 只承担短期、高频、可过期的状态，不保存论文权威正文。第一类是登录、注册、聊天和 Embedding
> 的固定窗口计数；第二类是模型 Token 的预占、结算和失败回退；第三类是上传分片 Bitmap，Redis 丢失后
> 可以从 MySQL 分片记录回填；第四类是生成态，包括流式内容、进度、引用和 active generation，TTL 是
> 30 分钟。生成态写入时先准备 meta 和 content，最后才发布 active key，避免读到一个指向空数据的指针。
> 当前固定窗口用 INCR 后再 EXPIRE，简单但不是原子操作，如果对严格限流有要求，我会改成 Lua 或成熟
> 限流组件。

**必备追问：**固定窗口缺点、Lua、缓存一致性、TTL、热点 Key、大 Key、Redis 宕机如何降级。

## 5. WebSocket 和异步任务怎么处理并发与取消？

**90 秒回答：**

> 项目把不同并发场景拆开处理。用户到 WebSocket Session 的映射使用 ConcurrentHashMap，每个用户的
> Session 列表使用 CopyOnWriteArrayList，因为发送遍历多、连接增删少。同一个 WebSocketSession 不能
> 并发写，所以发送时只锁这个 Session。Java 调 Python 的研究任务用 generationId 注册到并发 Map，
> FutureTask 负责可中断执行，CompletableFuture 向上层传递成功、失败和取消。产品 Trace 则使用有界
> ArrayBlockingQueue，满了就丢 Trace 并告警，不阻塞用户回答。关闭应用时会先停止接收，再等待线程池，
> 超时后中断。需要诚实说明的是，当前 HTTP 请求执行器是 cached thread pool，没有硬上限，生产强化时
> 应改成按下游容量测出来的有界线程池。

**必备追问：**CHM 原理、COW List 适用场景、线程池参数、拒绝策略、中断语义、优雅关闭、背压。

## 6. 如何定位 Qdrant 假健康故障？

**90 秒回答：**

> 一次检索基准中，Qdrant 已完成索引，但后续 HTTP 请求超时。根据时间线和日志，第一次错误是
> `Too many open files`，进程软文件描述符上限正好是 1024。产品 Collection、Benchmark Collection、
> Socket 和其他句柄把 FD 用满了。问题更隐蔽的是容器仍显示 healthy，因为健康检查只做 TCP connect，
> 没有验证 HTTP 响应。处理上先提高 nofile 恢复服务，再把 Compose 固化为 65536，把健康检查改成要求
> `/healthz` 返回 200，并把 Benchmark Qdrant 与产品实例隔离，同时给外部请求增加有限重试和每次尝试
> 日志。这个问题说明健康检查必须验证真实服务契约，临时 prlimit 也不能代替持久部署配置。

**必备追问：**文件描述符、Socket、ulimit、Liveness/Readiness、重试风暴、退避、为什么 TCP 探活不够。

## 7. 如何审计游客越权，并避免修复引入新的防刷漏洞？

**当前状态：实施中。已在本地完成独立游客身份、GUEST 最小权限、Redis 每日创建尝试总闸门、共享
LLM/Embedding 预算和 Embedding 原子预留；全部改动尚未提交或部署，面试时不能说成已完成的线上
修复。**

**90 秒回答草稿：**

> 我收到过一份“游客能看到管理员对话”的疑似越权报告。没有根据前端隐藏菜单直接下结论，而是先区分
> 认证和授权：前端路由守卫只是交互控制，真正的安全边界是后端。沿请求链检查后，确认当前源码已在
> `SecurityConfig` 对 `/api/v1/admin/**` 执行 `hasRole("ADMIN")`，Controller 还有二次管理员校验；
> 用户对话的读写也使用 `authenticatedUserId + conversationId` 做对象归属查询。因此当前证据不能证明
> 游客绕过了管理员 RBAC。
>
> 继续检查身份模型时发现了更实际的风险：所有游客复用同一个数据库用户。这样即使对象级授权代码完全
> 正确，不同游客的 authenticatedUserId 仍然相同，系统就无法隔离他们的会话、论文和操作。根因修复是
> 为每个游客会话创建独立临时主体，让现有 userId 归属校验自然生效，而不是只把 USER 改名为 GUEST。
> 但独立主体又会让攻击者反复调用 guest-login 创建用户、重置单用户额度并消耗模型费用，所以不能把
> 这一半方案直接上线。完整闭环应同时包含独立游客身份、GUEST 最小权限、登录限流、全局游客预算和
> 过期数据清理。

**调查证据链：**

```text
前端 ADMIN 路由可绕过
-> 只能证明攻击入口存在，不能证明后端返回了数据
-> 检查 JWT Authority、SecurityFilterChain、Controller 二次校验
-> 检查 userId + conversationId 对象归属
-> 发现共享游客使不同访问者拥有同一 userId
-> 设计独立身份时继续识别账号创建、额度重置和 BCrypt CPU 防刷风险
```

**关键设计：资源身份与计费身份分离。**

```text
resourceOwnerId = 每个游客唯一，用于会话、论文和集合的归属隔离
quotaSubjectId  = GUEST_POOL，所有游客共享，用于限制模型总成本
```

当前普通新用户会按配置初始化 `10,000,000` LLM Token 和 `10,000,000` Embedding Token；如果独立游客
直接复用这条逻辑，攻击者每创建一个游客就能刷新一份额度。因此完整防刷包含三层，而不是只按 IP 限流：

1. Cloudflare 对 `POST /api/v1/users/guest-login` 按来源限流，保护 BCrypt、JWT、Redis 和数据库入口；
2. Redis 全局游客创建计数设置每日硬上限，超过返回 `429`，防止代理池无限写用户表；
3. 所有 GUEST 共享 LLM/Embedding 预算，确保更换 IP 或创建新身份也不能增加总模型额度。

IP 限流只是减速层，可能被代理池绕过，也可能误伤 NAT 用户；全局预算才是成本安全的最终硬边界。

**实现共享预算时发现的并发问题：**原有 LLM 额度通过 Redis Lua 原子执行“余额判断 + 扣减预留”，但
Embedding 只先调用 `hasEnoughEmbeddingTokens` 查询余额，模型完成后才扣款。两个并发请求可能同时
通过余额检查，随后把共享余额扣成负数，这是典型的 check-then-act 竞态。最终让 Embedding 复用
LLM 的 Redis Lua，按“原子预留 -> 成功后按实际用量结算 -> 失败时退款”的生命周期处理；Lua 脚本
提取为应用级静态常量，不在每次调用时重新创建，使共享预算真正成为成本硬边界。

**最终验证标准：**

- 真实 GUEST Token 请求 `/api/v1/admin/**` 返回 `403`；
- 游客 B 无法读取、切换、归档、删除游客 A 的真实对话；
- 重复调用游客登录会触发 `429`，且不能通过创建新游客绕过全局模型预算；
- 过期游客和关联数据可以按明确规则清理；
- 没有测试数据时不能声称性能提升或已经发生过大规模数据泄漏。

**必备追问：**认证与授权、RBAC、BOLA/IDOR、为什么前端守卫不是安全边界、联合归属查询、`403` 与
`404`、共享账号的身份问题、限流、全局预算、临时数据生命周期、为什么不能只增加 GUEST 角色。
