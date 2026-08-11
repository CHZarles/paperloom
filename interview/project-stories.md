# PaperLoom 八个项目难点面试稿

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

**当前状态：核心修复已于 2026-08-10 21:39 CST 以提交 `2e418d1` 部署，浏览器匿名会话复用已于
2026-08-11 以提交 `cd39333` 部署。独立游客身份、GUEST 最小权限、Redis 每日创建尝试总闸门、共享
LLM/Embedding 预算、Embedding 原子预留和同浏览器身份复用已经生效；Cloudflare 来源限流和过期游客
清理尚未实施，面试时必须说明这个边界。**

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

**上线后发现的游客“闪退”回归：**游客登录本身返回 `200`，`/users/me` 也返回 `GUEST`；真正失败的是
聊天首页空状态中的 `SessionScopePicker` 挂载时调用 `GET /paper-collections`。角色收紧后，原有安全
规则只允许 USER/ADMIN，游客收到 `403`；前端旧的 403 兜底又把“无权限”误判成 Token 失效并执行
`resetStore()`，清空 Token 后跳回登录页，所以用户看到的是“登录后闪退”。修复不是放开整个集合接口，
而是只允许 GUEST `GET /paper-collections`（服务层按 ownerId 查询，游客只能得到自己的空列表），继续
禁止 GUEST 创建、修改、删除集合。提交 `4ff9987` 已部署并验证游客列表返回 `[]`、管理员接口仍为 `403`。

这个回归说明：权限收紧不能只看后端规则，还要枚举前端登录后的初始化请求；同时要区分 `401`（身份失效）
和 `403`（身份有效但无权限），不能把所有 403 都当成退出登录。

**继续发现的游客重复创建问题：**独立游客身份上线后，每次调用 `guest-login` 都会直接执行
`INSERT guest_<UUID>`；原有 Redis Key 只限制全站每日创建总量，不能让同一浏览器复用已有身份。修复时
没有把 IP 当作用户标识，因为公司、校园和家庭 NAT 会让多人共享 IP，移动网络也会让同一人的 IP 变化。
系统改为给浏览器写入 `HttpOnly + Secure + SameSite=Lax` 的匿名会话 Cookie，Cookie 复用现有 Refresh
Token，不新增 Session 表或 Redis 模型。再次登录时只有“Refresh Token 有效、数据库用户仍存在且角色
确实为 GUEST”才复用原用户并签发新的 Access Token；Cookie 缺失、无效或属于正式用户时才创建新游客，
也只有这时才消耗全站每日创建额度。复用时不重新生成 Refresh Token，避免每次登录继续堆积 Redis Token
记录。

这解决的是同一浏览器的正常重复访问，不是假装彻底防刷：清 Cookie、无痕窗口或换浏览器仍可创建新身份，
继续由现有全局创建上限和共享模型预算兜底；来源限流与过期游客清理仍是下一阶段。

**游客“可以上传论文”的疑似越权：**先用线上 GUEST Token 对分片上传和合并接口做无文件请求，两个接口
都返回 `403`，排除了后端上传授权失效；`/users/me` 同时确认角色确实为 GUEST。继续检查发现文献库页面
允许游客浏览公开论文，但 Upload 按钮没有角色判断，而且前端在发请求前就会添加本地上传任务，因此界面
表现得像上传已经开始。修复没有封禁整个文献库，而是用 `canUploadPapers = role != GUEST` 隐藏上传按钮和
弹窗，并让统一的 `canManageFile` 拒绝游客的续传和管理入口；后端 USER/ADMIN 限制保持不变。这个案例要
准确表述为“前后端权限展示不一致”，不能声称发生了真实论文越权写入。

**最终验证标准：**

- 真实 GUEST Token 请求 `/api/v1/admin/**` 返回 `403`；
- 游客 B 无法读取、切换、归档、删除游客 A 的真实对话；
- 重复调用游客登录会触发 `429`，且不能通过创建新游客绕过全局模型预算；
- 过期游客和关联数据可以按明确规则清理；
- 没有测试数据时不能声称性能提升或已经发生过大规模数据泄漏。

**本次部署实证：**

- 本地 `UserServiceTest`、`UsageBalanceQuotaServiceTest` 和前后端构建通过；服务端在独立 Git worktree
  构建新 JAR，再停旧进程并替换产物，避免运行中的 JVM 与 Maven 原地覆盖同一个 JAR；
- MySQL `users.role` 已扩展为 `GUEST/USER/ADMIN`，旧共享游客被重命名为
  `legacy_guest_disabled_10` 并改为 GUEST，使旧 JWT 无法继续按原用户名认证，同时保留历史数据；
- 两次真实 `guest-login` 分别创建用户 `11`、`12`，`/users/me` 都返回 GUEST；游客 Token 请求
  `/api/v1/admin/users/list` 返回 `403`；
- 游客 A 创建真实会话后，游客 B 对该会话执行范围读取、切换和删除均返回 `404`，A 随后成功清理；
- 两名游客的用量接口都读取共享余额；Redis 中存在 `guest-pool` 的 LLM/Embedding Key，不存在按
  游客 `11/12` 创建的独立 Token Key；公网首页 `200`、匿名 `/users/me` 为 `403`，Backend、Harness、
  Cloudflared 均为 `active`；
- 公网使用同一个 Cookie Jar 连续请求两次 `guest-login`，两次都返回 `200`，随后 `/users/me` 都返回
  同一个游客 ID `22`；Cookie 的 Path 为 `/api/v1/users/guest-login` 且带 Secure 标记。Controller 回归
  测试同时验证复用分支不会调用 `createGuestUser`，也不会生成新的 Refresh Token；
- 没有为了验证 `429` 人为创建剩余 96 个游客，也没有在本次发布中调用真实模型消耗共享额度；这些
  不能表述成生产实测。当前每日 100 次全局创建上限把数据增长限制在最多约 36,500 行/年，明确保留
  Cloudflare 来源限流和过期游客清理作为下一阶段，而不是声称防刷已经完全结束。

**必备追问：**认证与授权、RBAC、BOLA/IDOR、为什么前端守卫不是安全边界、联合归属查询、`403` 与
`404`、共享账号的身份问题、限流、全局预算、临时数据生命周期、为什么不能只增加 GUEST 角色。

## 8. 如何定位并优化 PDF 首屏显示性能？

**90 秒回答：**

> 我在线上发现 PDF 首次显示约需 3 秒。没有先凭感觉改 pdf.js，而是用 Playwright 驱动真实 Chrome，定义
> “点击 Preview 到 Canvas 有尺寸且渲染遮罩消失”为 First Page Ready，对同一 PDF 做 5 组冷、热打开，
> 并用 Resource Timing 拆分网络与渲染。数据显示冷打开中位数 3059 ms，其中约 2539 ms 在网络链路。
> 第一轮定位并删除了无信息增量的 descriptor 请求，冷打开降到 2561 ms。第二轮我先实测“改成页面图”
> 候选：图片中位数快 20.1%，但仍有两次串行请求，而且会引入另一套缩放与标注坐标链路，所以没有为了
> 跑分牺牲正确性。继续沿 PDF 链路发现前端先取 JSON Metadata，再按其中的大小和 URL 请求 PDF，仍是一次
> 可删除的公网往返。我改用 HTTP 标准 Range，让第一次 `206` 响应通过 `Content-Range` 同时返回总大小和
> PDF 首块，小 PDF 一次完成，大 PDF 继续分块，旧 JSON 协议保留回退。
>
> 部署后第一次复测仍是两次请求。我用直连 Spring、Nginx、本地域名三段探针发现 Spring 返回 206，但
> Nginx 返回 200 JSON；根因是 BaoTa 的全局 `proxy_cache` 会吞掉上游 Range。对认证 API 显式关闭代理
> 缓存后，线上冷打开变成一个 206 请求，中位数从 2561 ms 降到 2057 ms，再提升 19.7%；从最初 3059 ms
> 累计下降 32.8%。热打开仍是 0 请求且本轮没有变快。我只报告 5 次样本的中位数，不声称 p95，也把大于
> 1 MiB 的 PDF 性能留作未验证边界。

**前端阶段定位补充：**用户仍觉得 2 秒很慢，我没有把“网络结束到页面可见”的约 0.8 秒全部算成 PDF
解析，而是在数据就绪、文档就绪、页面对象、Canvas 和文字层边界加入 Performance Mark。线上 5 组结果
显示 Canvas 中位数只有约 28 ms、文字层约 18 ms，真正异常的是文档就绪后固定等待约 386 ms。源码中
`.pdf-page-shell` 只有在 `documentLoading=false` 后才会挂载，但代码先等待该节点稳定，最多等 24 个动画
帧后才关闭 loading，形成“等待一个尚不可能存在的节点”。调整为“结束 loading -> nextTick 挂载 -> 等待
尺寸稳定”后，热打开中位数从 915.3 ms 降到 528.7 ms，减少 386.6 ms，与被删除的等待完全吻合；冷打开
仍受公网、动态组件和 pdf.js 冷初始化抖动影响，本轮没有宣称冷启动总耗时改善。

**被否决的条件预加载实验：**继续分析发现 Viewer 与 Worker 的线上压缩传输约 `562 KB`，都在第一次点击
后才开始。我实验性地只在文献库列表非空且浏览器空闲时预加载，不下载论文正文。让前后测试都在列表出现
后等待 3 秒再点击，5 组样本中的点击到首屏中位数从 1958.5 ms 降到 1239.0 ms，其中约 542.8 ms 可以归因
于 Viewer 和 Worker 已缓存。但复核后我主动回滚：这个指标把 3 秒内提前发生的下载排除在计时窗口之外，
没有减少总工作，还让不点击的用户多下载 562 KB；立即点击和聊天引用场景也没有得到证明。这个实验提醒我，
性能门槛不能只写“点击延迟降低多少”，还要同时约束完整用户旅程、额外流量和适用人群。实验数据保留，
生产预加载代码已删除。

**证据文件：**`docs/performance/pdf-preview-round-trip-optimization-2026-08-11.md`、
`docs/performance/pdf-preview-standard-range-optimization-2026-08-11.md`、各轮原始 JSON 和
`frontend/scripts/benchmark-pdf-preview.mjs`。

**必备追问：**中位数与 p95、冷缓存和热缓存、Resource Timing、串行网络往返、前端快速路径与后端鉴权、
为什么请求数比字节数更重要、HTTP Range/206/Content-Range、反向代理为什么会吞 Range、如何避免性能
测试自欺、为什么暂缓图片方案、Vue 条件渲染与 nextTick、为什么用热缓存隔离前端耗时、预加载的延迟与
带宽权衡、为什么先定义实验保留线。
