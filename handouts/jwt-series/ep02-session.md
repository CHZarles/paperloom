# EP02 — Session 是什么

**时长：** 8-10 分钟
**配套代码：** commit `ep2: session redis store`

---

## [00:00] Hook

**画面：** Redis 客户端 → `KEYS session:*` → 一堆 session 数据滚出

**口播：**
> 上集我们说 cookie 只是个 ID，那服务器拿这个 ID 怎么知道"我是谁"？答案就是 Session——服务器自己有一张"档案柜"，cookie 上的 ID 就是档案柜的钥匙。今天我们把它彻底讲清楚。

---

## [00:30] 是什么：Cookie 是钥匙，Session 是档案柜

**画面：** 画图，浏览器侧 cookie + 服务器侧 session

**口播：**
> 用一张图把关系说清楚。浏览器这边，cookie 是"钥匙"，上面印着一串随机 ID。服务器这边，session 是"档案柜"，每个 ID 对应一个抽屉，抽屉里放着"用户是谁、什么时候登录的、买过啥"这些状态。
>
> 工作流：登录 → 服务器生成随机 ID → 在档案柜里开抽屉存 userId → 把 ID 印成 cookie 给你 → 下次访问带 cookie → 服务器拿 ID 找抽屉 → 找到 userId。
>
> 这就是 Session。
>
> ---
>
> **观念区分（小白必看）**：
> - **Session 不是一个东西**——它是"服务器为每个用户开抽屉"这件事的**抽象说法**。
> - 你**看不见** Session，只能看见它的实现：UUID 字符串、Redis 里的 key-value、Cookie 里的 SESSION_ID。
> - "Session"这个名字可以换成"会话"、"Token 池"、"用户态"——本质都是一回事：**服务器端的状态存储**。
> - 类比：会员卡的"卡"是 Cookie（真实存在），"会员体系"是 Session（理念）。你能拿出卡给人看，但"会员体系"是看不见的——它存在便利店的电脑里。
> - 这个区分很关键：**没有 Session ID 这个字符串，Session 一样存在**——只是没法跨请求记住你。下集 JWT 解决的就是"让 ID 本身携带信息，服务器不存任何东西"。

---

## [01:30] 是什么：4 种存储演进

**画面：** 4 种存储方案对比表

**口播：**
> 问题是，session 存在哪？我列了 4 种方案，新手最爱踩坑的就是选错。

**画面：** JVM 内存 `Map<>`

**口播：**
> 方案一：JVM 内存。`Map<String, UserSession> sessions = new HashMap<>();` 多简单。但有两个致命问题：服务重启全丢；多实例之间不共享。假设你部署 3 个 Tomcat，用户登录落到实例 1，session 存在实例 1 内存里，下次请求落到实例 2——对不起，不认识你，用户疯狂登入登出。

**画面：** Redis

**口播：**
> 方案二：Redis。跨实例共享；TTL 自动清理；性能好。这是 99% 的生产选择。今天的 demo 就用这个。

**画面：** MySQL

**口播：**
> 方案三：MySQL。你已经有数据库了，省一个组件。听起来不错？—— 不行。session 是高频读写，每次请求都查 session 表，再加 user 权限的 JOIN，数据库会被你打死。除非你的 QPS 极低，否则不要这么干。

**画面：** Spring Session

**口播：**
> 方案四：Spring Session 注解。几行配置切换底层存储，对业务代码零侵入。学习成本低，但你想搞懂原理还是得懂 Redis。

---

## [03:00] 是什么：UUID 为啥这么重要

**画面：** terminal 生成 UUID 演示

**口播：**
> 顺便说一句：session ID 必须用 UUID 这样的随机串，不要用自增 int。
>
> 为什么？假设你用自增 1, 2, 3... 攻击者拿自己的 session ID 把第二个字段改成 2，就能访问 userId=2 的账号。这叫"会话固定 + IDOR"，是 OWASP 经典漏洞。UUID 128 bit 不可枚举，攻击者穷举一辈子也猜不到。
>
> 我们用 `UUID.randomUUID().toString()` 就行。

---

## [04:00] 怎么实现：30 行撸 Redis session

**画面：** IDE 写 `MiniSessionStore.java`

**口播：**
> 开撸，30 行。先起 Redis：`docker run -d -p 6379:6379 redis:7`。
>
> 写一个 `MiniSessionStore`，两个方法：`create(userId)` 生成 UUID 写 Redis，`getUserId(sid)` 从 Redis 读。注意 Redis 的 SET 命令要带 TTL——`SET session:{sid} {userId} EX 1800`，30 分钟过期。不然 100 万用户的 session 永远占着内存。

**画面：** 改 `AuthController.java`

**口播：**
> 改 AuthController。`/login` 不再直接返回 sessionId，而是调 `sessionStore.create(userId)`，把返回的 UUID 塞进 Set-Cookie。`/me` 用 `@CookieValue` 拿 sessionId，调用 `sessionStore.getUserId(sid)` 拿 userId。

---

## [06:00] 怎么实现：演示完整流程

**画面：** terminal 演示三步

**口播：**
> 跑起来。三步演示：
>
> 第一步：`POST /login` → 拿到 SESSION_ID。立刻在 Redis 客户端 `KEYS session:*` 看——多了一行。这就是 session 真的存到 Redis 了。
>
> 第二步：`GET /me` 带 cookie → 后端从 Redis 拿到 userId → 返回用户信息。
>
> 第三步：模拟 session 失效。`redis-cli DEL session:{sid}` → 删掉 → 再调 `/me` → 返回 401。证明：cookie 没用了，session 才是身份的真相。

**画面：** 改 TTL 演示

**口播：**
> 再演示一个：把 `Duration.ofMinutes(30)` 改成 `Duration.ofSeconds(10)`，重新启动。10 秒后 `/me` 自动 401。session 自动过期，Redis 自动清掉。这就是 TTL 的作用——不设 TTL，session 永远占内存。

---

## [08:00] 攻防：3 个常见坑

**画面：** 强调关键点

**口播：**
> 三个常见坑。
>
> 第一：登出只删前端 cookie，没删 Redis。这是最经典的。`/logout` 接口要做两件事：`Set-Cookie: SESSION_ID=; Max-Age=0` 删前端 cookie；`redis.del("session:" + sid)` 删服务端 session。两边都删才安全。
>
> 第二：多实例不共享 session。前面说过，再强调一次。如果你用 JVM 内存 Map 存 session，部署 3 个实例就等着用户来骂。
>
> 第三：session ID 放 URL 里。`/page?sid=xxx`，这种写法 Referer 头会泄露、爬虫会记录、用户分享链接等于把 session 送人。session ID 必须放 cookie 里，不能放 URL。

---

## [09:00] 一句话总结

**口播：**
> 一句话总结：Session 就是服务器的一张"档案柜"，cookie 上的 ID 是钥匙。Session 必须存（Redis），服务器是有状态的。

---

## [09:20] 下集预告

**口播：**
> 服务器必须存 100 万用户的档案，这事儿有解吗？能不能让用户自己"携带"身份信息，让服务器无状态？下集：JWT。今天我们手撸一个 HS256，不依赖任何 JWT 库。
