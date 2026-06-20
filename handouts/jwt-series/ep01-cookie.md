# EP01 — Cookie 是什么

**时长：** 8-10 分钟
**配套代码：** commit `ep1: cookie basics`

---

## [00:00] Hook

**画面：** 登录派聪明 → 关浏览器 → 重开 → 仍然登录

**口播：**
> 我刚登录派聪明，关掉浏览器，再打开——诶，还是登录状态？我啥也没保存啊。浏览器怎么记住我的？答案藏在一个 **1994 年诞生、1997 年标准化**的小机制里，名字叫 Cookie。今天我们把它彻底讲清楚。

---

## [00:30] 是什么：HTTP 是无状态的

**画面：** terminal 跑 `curl -v http://httpbin.org/get`

**口播：**
> 先说个反直觉的事实：HTTP 协议天生是"健忘"的。每个请求对服务器来说都是陌生人——服务器不会记得你 5 秒前发过什么。这就是 HTTP 的"无状态"。
>
> 你可以把 HTTP 想象成去便利店买东西：每次进门，店员都当你第一次来，不记得你昨天买过啥。

---

## [01:30] 是什么：Set-Cookie 给请求"贴标签"

**画面：** 浏览器抓包登录响应头，红框标 Set-Cookie

**口播：**
> 那为什么我登录一次就一直登录？因为服务器在响应里偷偷塞了一个小东西给我。F12 打开 Network，点这个 login 请求，看 Response Headers——`Set-Cookie: SESSION_ID=xxx; Path=/; HttpOnly`。看，"Set-Cookie"。这就是服务器跟我说："我给你贴个标签，下次来带着。"浏览器就把这个标签存起来了。

---

## [02:30] 是什么：浏览器自动把标签带回去

**画面：** 第二次请求的 Request Headers，红框标 Cookie

**口播：**
> 然后我刷新页面，看这个 /me 请求的 Request Headers——`Cookie: SESSION_ID=xxx`。看，浏览器自动把标签贴回去了。Cookie 干的事儿就这么简单：服务器给你贴标签，浏览器帮你自动带。
>
> 但是注意——"自动带"是 Cookie 区别于其他存储方式的最关键特性。这个我们后面讲 localStorage 的时候会再对比。

---

## [03:30] 是什么：Cookie 的 4 个核心属性

**画面：** devtools → Application → Cookies，逐字段讲解

**口播：**
> 好，那这个标签到底长啥样？打开 Application 面板 → Cookies，你看这一堆字段。我挑 4 个最关键的讲。

**画面：Domain、Path 字段**

**口播：**
> 第一个：Domain。`.charles.com` 这种带点的写法，表示这个 cookie 对 charles.com 所有子域都生效。第二个：Path，限制 cookie 的作用路径。`/` 表示整站。假设你设 `/api`，那只有访问 /api 开头的请求才会带这个 cookie。

**画面：Expires/Max-Age**

**口播：**
> 第三个：Expires / Max-Age。这俩是过期时间，区别是 Expires 给的是绝对时间，Max-Age 给的是相对秒数。设了 0 或者负数就是删 cookie。没设过期时间的叫"会话 cookie"，浏览器一关就没了——你刷新可以，但关浏览器再开就没了。`Set-Cookie: xxx; Max-Age=3600` 就是一小时后过期。

**画面：HttpOnly 勾选框**

**口播：**
> 第四个：HttpOnly。devtools 这里能勾选。HttpOnly 标志位是 2002 年 IE 6 加的，意思是"JS 读不到这个 cookie"。你打开 Console 输 `document.cookie` 试试，没有这个值。但浏览器自己还是会在请求里自动带。
>
> 所以 HttpOnly 不是防"带"，是防"读"——防 XSS 攻击偷 cookie。这块我们 EP5 现场演示，会让你看得很清楚。

---

## [05:30] 是什么：Secure 标志

**画面：** Secure 字段

**口播：**
> 第五个：Secure。设了 Secure 的 cookie 只在 HTTPS 请求里自动带，HTTP 请求不带。这就是为什么生产环境必须 HTTPS——不然你 Set-Cookie 写的 Secure 跟你没设一样。`document.cookie` 也能读到 Secure cookie，只是浏览器不自动带 HTTP 请求。
>
> 最后提一句 SameSite，这个标志位是防 CSRF 的，我们 EP6 详细讲。今天你只需要知道有这个东西就行。
>
> ---
>
> **观念区分（小白必看）**：
> - **Cookie 是真实存在的东西**——它有 RFC 6265 规范、有明确格式（`name=value; Domain=...; Path=...; Max-Age=...`），devtools 里能看到，浏览器会存它。
> - **但 Cookie 装的不是"状态"本身，是"状态 ID"**——状态在服务器，cookie 只是 carrier（载体）。
> - 打个比方：Cookie 像便利店的**会员卡**（真实存在，看得见），里面只印着会员号（ID）；会员的余额、消费记录（状态）存在便利店的电脑里（服务器）。你拿卡去任何分店都能用——这就是 Cookie。

---

## [06:00] 怎么实现：写一个最小 demo

**画面：** IDE 新建 Spring Boot 项目

**口播：**
> 好，原理讲完了。30 行代码撸一个——我用 Spring Boot 演示，其它语言逻辑一样。建一个最简单的 controller。

**画面：** 写 `AuthController.java`

**口播：**
> `/login` 接口：生成一个 UUID 当 sessionId，塞进 Set-Cookie 响应头。注意这几个标志位：HttpOnly、Path、Max-Age，我都会加。`/me` 接口：用 `@CookieValue` 把 cookie 读出来。
>
> 真实的做法是用这个 sessionId 去 Redis 查 userId，今天先简化——直接返回 sessionId 给你看。

---

## [07:30] 怎么实现：跑起来

**画面：** terminal 跑 `mvn spring-boot:run`

**口播：**
> 跑起来——`mvn spring-boot:run`。

---

## [08:00] 怎么实现：演示完整流程

**画面：** curl POST /login，看 Set-Cookie

**口播：**
> 用 curl 模拟登录。`curl -i -X POST http://localhost:8080/login -d 'username=charles'`。看响应头，`Set-Cookie: SESSION_ID=xxx; Path=/; HttpOnly; Max-Age=3600`。完美。
>
> 现在再用这个 cookie 调 /me。

**画面：** curl GET /me 带 cookie

**口播：**
> `curl -H 'Cookie: SESSION_ID=xxx' http://localhost:8080/me`，返回 userId。这就是整个流程——HTTP 无状态，Cookie 给它"记忆"。

---

## [08:50] 攻防：3 个常见坑

**画面：** devtools 里改 cookie 标志位

**口播：**
> 最后说几个常见的坑。
>
> 第一个：没设 Max-Age = 浏览器关了就丢——很多人反馈"为啥我登录状态保留不住"，就是这个。
>
> 第二个：没设 Secure = HTTP 也能带 cookie，中间人攻击可偷。
>
> 第三个：没设 HttpOnly = XSS 一次偷光。
>
> 这三个标志位，新手最容易漏。
>
> 第四个经典坑——Domain 设错了。Domain=.charles.com = 所有子域都能访问，Cookie 泄露面就大了。比如你的 admin.charles.com 也用了 .charles.com 这个 cookie，那 blog.charles.com 的 XSS 就能偷到 admin 的 session。

---

## [09:30] 一句话总结

**口播：**
> 一句话总结：Cookie 就是一个浏览器自动帮你带回去的字符串，存的是 session ID，验证靠服务端。

---

## [09:50] 下集预告

**口播：**
> 但 cookie 只是个 ID 啊，服务器拿这个 ID 怎么知道"我是谁"？session 存哪？多实例怎么办？下集：Session 与 Redis。
