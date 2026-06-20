# EP04 — Token 存哪？localStorage vs Cookie

**时长：** 10-12 分钟
**配套代码：** commit `ep4: storage compare`

---

## [00:00] Hook

**画面：** 同时打开 3 个网站登录（派聪明 / Notion / 掘金），分别看 devtools → Application

**口播：**
> 派聪明把 token 放 localStorage，Notion 放普通 cookie，掘金放 HttpOnly cookie。三个网站，三个选择，谁对谁错？今天我们用最朴素的方式给它们排个序。

---

## [00:30] 是什么：3 种方案对比

**画面：** 表格对比 3 种方案

**口播：**
> 上集 JWT 替代了 Session，但 JWT 是个**字符串**——放哪？这不是单纯技术选择，是 **XSS 和 CSRF 两个攻击面之间的权衡**。
>
> 我做了一张表，4 个维度看 3 种方案。
>
> 第一列"自动带"——浏览器发请求时会不会自动把这个 token 加上。localStorage 不自动带，每次调 API 要手动加 Authorization header。普通 Cookie 和 HttpOnly Cookie 都自动带。
>
> 第二列"JS 可读"——`document.cookie` 或者 `localStorage.getItem()` 能不能读到这个值。localStorage 和普通 Cookie 都能读，HttpOnly Cookie 读不到。
>
> 第三列"防 XSS 偷"——被 XSS 攻击时 token 会不会被偷。localStorage 容易被偷，普通 Cookie 也容易被偷，HttpOnly Cookie 偷不到。
>
> 第四列"防 CSRF 滥用"——防不防跨站请求伪造。localStorage 防 CSRF（不自动带），普通 Cookie 和 HttpOnly Cookie 都不防。
>
> 看出问题了吗？**没有银弹**。

---

## [02:00] 是什么：逐个深入

**画面：** localStorage

**口播：**
> 先看 localStorage。纯前端存储，键值对字符串，**不参与 HTTP 请求**。每次调 API 你必须手动拼 header：`fetch('/api/me', { headers: { 'Authorization': 'Bearer ' + localStorage.getItem('token') } })`。
>
> 好处：防 CSRF——因为请求不自动带，攻击者发起的跨站请求不带这个 header。
>
> 坏处：XSS 一次偷光。任何能跑 JS 的漏洞都能 `localStorage.getItem('token')` 拿走。

**画面：** 普通 Cookie

**口播：**
> 再看普通 Cookie。`Set-Cookie: SESSION=xxx; Path=/;` 不加任何标志位。浏览器自动带，JS 也能读。
>
> 这是**最差的方案**——既不防 XSS（JS 能读），也不防 CSRF（自动带）。我怀疑很多公司这么写是因为"祖传代码懒得改"。

**画面：** HttpOnly Cookie

**口播：**
> HttpOnly Cookie：`Set-Cookie: SESSION=xxx; Path=/; HttpOnly; Secure`。
>
> 浏览器自动带，**但 JS 读不到**。我现场演示。

---

## [04:00] 现场演示：HttpOnly JS 读不到

**画面：** devtools → Application → Cookies 选 HttpOnly 那个，Console 输 `document.cookie`

**口播：**
> 看这个 HttpOnly 的 SESSION cookie，下方属性里 ✅HttpOnly 勾选。
>
> 切到 Console，输 `document.cookie`——看不到 SESSION。但是！切到 Network，看 /me 请求的 Request Headers——`Cookie: SESSION=xxx`，浏览器自动带了。
>
> 关键：**HttpOnly 防的是"读"，不是"带"**。这是 EP6 CSRF 攻击的核心入口。

---

## [05:00] 怎么实现：演示 2 种（跳过普通 Cookie）

**画面：** IDE 写最简 HTML 页面

**口播：**
> 撸一个最简 HTML 页面（不依赖任何前端框架，30 行）演示 2 种**有用的**方案。普通 Cookie 既不防 XSS 也不防 CSRF，是最差的方案——我们口头说一下就跳过。

**画面：** 演示 1：localStorage

**口播：**
> 演示 1：localStorage。`localStorage.setItem('token', 'eyJ...')`，调 API 用 `fetch('/api/me', { headers: { 'Authorization': 'Bearer ' + localStorage.getItem('token') } })`。
>
> 看 Network：Request Headers 里**手动**拼的 Authorization。

**画面：** 演示 2：HttpOnly Cookie

**口播：**
> 演示 2：HttpOnly Cookie。后端 `Set-Cookie: SESSION=xxx; Path=/; HttpOnly; Secure;`，JS 端 `document.cookie` 是空字符串 `""`，但请求里**还是自动带**。

---

## [08:00] 怎么选：没有银弹

**画面：** 总结选择树

**口播：**
> 那到底怎么选？我给一个决策树：
>
> 第一问：你的场景有没有 CSRF 风险？跨域、跨子域、第三方表单提交——有。
>
> 　 - 有 → 别用 localStorage（手动 header 防 CSRF，但需要前端 100% 配合）。
> 　 - 没有 → 可以用 localStorage（最简单的方案）。
>
> 第二问：你能接受登录态绑死一个浏览器？
>
> 　 - 能 → 用 HttpOnly Cookie，XSS 偷不到。
> 　 - 不能 → 考虑双 token 体系，EP7 讲。
>
> 第三问：你怕 XSS 还是怕 CSRF？
>
> 　 - 怕 XSS → 选 HttpOnly。
> 　 - 怕 CSRF → 加 SameSite=Strict + CSRF Token（EP6 详细讲）。
>
> 真实生产：99% 的情况是 **HttpOnly + SameSite=Strict + Secure + 双 token 体系**。这才是答案。

---

## [10:00] 攻防：各种方案的"防什么不防什么"

**画面：** 强调关键点

**口播：**
> 4 个关键点：
>
> 1. localStorage 唯一的优势是防 CSRF。代价是每次请求要手动加 header，前端漏一次就完蛋。
>
> 2. 普通 Cookie 是最差的方案——既不防 XSS 也不防 CSRF。如果你看到自己公司的代码用这种，赶紧跟 leader 说换成 HttpOnly。
>
> 3. HttpOnly 防 XSS 不防 CSRF。HttpOnly 不是"无敌"，它只防"读"不防"带"。下集 EP6 我们就演示怎么"带" HttpOnly cookie 发起跨站请求。
>
> 4. localStorage 容易被 XSS 偷光。下集 EP5 我们现场偷。

---

## [11:30] 一句话总结

**口播：**
> 一句话总结：localStorage 易 XSS、HttpOnly Cookie 易 CSRF，**没有银弹**。下两集我们要亲手攻击这两种方案。

---

## [11:50] 下集预告

**口播：**
> 都说 localStorage 不安全，到底多不安全？我搭一个最简留言板，token 存 localStorage，我们来偷。攻击者 netcat 监听，几秒钟就能收到你的 token。下集见。
