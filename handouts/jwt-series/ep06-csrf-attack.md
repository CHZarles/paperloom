# EP06 — CSRF 滥用 HttpOnly Cookie（**名场面 2**）

**时长：** 8-10 分钟
**配套代码：** commit `ep6: csrf vuln demo`
**⚠️ 警告：** 仅供本地学习，请勿对真实站点测试

---

## [00:00] Hook

**画面：** 分屏：bank.local 已登录 + evil.local 钓鱼站

**口播：**
> 假设你已经登录了银行站，cookie 存在 HttpOnly 里，JS 读不到。现在你点了一个论坛的链接，转账 1 万块给攻击者——**整个过程攻击者完全读不到你的 cookie**。怎么做到的？看。

---

## [00:30] 是什么：CSRF 的反直觉

**画面：** HttpOnly 防"读"不防"带"的对比

**口播：**
> 核心反直觉点：HttpOnly 防的是"读"，不防"自动带"。攻击者不需要"读"你的 cookie，只需要"浏览器自动带上你的 cookie"就够了。
>
> 想象一下：你去餐厅，服务员记住你的会员卡号（HttpOnly cookie）。服务员很专业，不会把卡号念出来（HttpOnly 防读）。但是你下次再来，服务员**自动**就把你的会员卡号记到账本上（浏览器自动带 cookie）。如果有个人能在你不知情的情况下让你走进这家餐厅——他不需要知道你的卡号，只要让你进门就行。
>
> CSRF 就是这个"让你进门"。

**画面：** 画图

**口播：**
> 看图：受害者浏览器里，已经登录 bank.local，cookie `SESSION=xxx; HttpOnly`。受害者点了一个论坛 / 邮件里的链接，跳到 evil.local。evil.local 页面写一个 `<form action="https://bank.local/transfer" method="POST">`，自动 submit。浏览器**自动**带上 bank.local 的 cookie，请求到 bank.local。转账完成。
>
> 攻击者从来没读过 cookie，但请求已经发了。

---

## [02:30] 怎么实现：搭场景

**画面：** /etc/hosts 配置 + nginx 反代

**口播：**
> **前置说明**：现代 Chrome 80+ 默认 `SameSite=Lax`，**跨站 POST 天然不带 cookie——这个攻击在默认设置下已经不成立**。我们今天要复现，得用旧版 Chrome（80 之前），或者启动时加 flag：
> ```bash
> google-chrome --disable-features=SameSiteByDefaultCookies,CookiesWithoutSameSiteMustBeSecure
> ```
> 真实生产中，**SameSite 是默认防线**，不用做额外防护。这也是为什么我把它放在"怎么防"的核心位置。
>
> 搭场景。本地 `/etc/hosts` 加两行：
> ```
> 127.0.0.1 bank.local
> 127.0.0.1 evil.local
> ```
>
> Spring Boot 配两个虚拟 host，或者用 nginx 反代，监听 80 端口，按 host 转发到不同后端。

**画面：** 受害者登录 bank.local

**口播：**
> 受害者登录 bank.local，cookie 设了 HttpOnly。devtools → Application → Cookies 看 `SESSION=xxx`，✅HttpOnly 勾选。`document.cookie` 读不到——HttpOnly 起作用了。

---

## [04:00] 怎么实现：evil.local 攻击页

**画面：** 写 evil.local/index.html

**口播：**
> 攻击者构造 evil.local 页面：
> ```html
> <h1>免费领取游戏皮肤</h1>
> <form action="https://bank.local/api/transfer" method="POST" id="f">
>   <input name="to" value="attacker">
>   <input name="amount" value="10000">
> </form>
> <script>document.getElementById('f').submit()</script>
> ```
>
> 一段诱饵标题（"免费领皮肤"），一个自动提交的表单，金额 1 万块给攻击者。

---

## [05:00] 现场攻击：受害者点链接

**画面：** 受害者点链接 → 自动跳转账

**口播：**
> 受害者点开 evil.local 链接。页面加载，`<script>` 立即执行，表单自动提交。浏览器跳到 `https://bank.local/api/transfer`，**自动带** SESSION cookie。
>
> 看 Network 面板——Request Headers：`Cookie: SESSION=xxx`。跨站请求**确实**带 cookie！HttpOnly 不影响这一点。

---

## [06:00] 名场面：cookie 自动带 + 转账成功

**画面：** bank.local 显示"转账成功"+ evil.local netcat 收到请求

**口播：**
> 🎬 **看 Network**：跨站 POST 请求，Request Headers 里**确实有** `Cookie: SESSION=xxx`。
>
> bank.local 收到请求，验证 cookie 有效，账号 1 万块转出。
>
> 攻击者**没读**到这个 cookie——HttpOnly 起作用了。但**请求已经发出去了**。这就是 CSRF 的精髓。

---

## [07:30] 怎么防：1 个核心方案

**画面：** SameSite 重点

**口播：**
> 怎么防？**SameSite=Strict** 是现代浏览器的标准答案。
>
> Set-Cookie 加 `SameSite=Strict`：
> ```
> Set-Cookie: SESSION=xxx; HttpOnly; Secure; SameSite=Strict
> ```
>
> 效果：跨站请求**完全不**带这个 cookie。evil.local 的表单请求过去，bank.local 看到没有 cookie，直接 401。
>
> `SameSite=Lax`（Chrome 默认）：跨站 GET top-level navigation 带，POST 不带。宽松一些但兼容老链接。
>
> 现场演示：把 SameSite 改成 Strict → evil.local 的表单过去 → bank.local 401。
>
> 老系统兜底用 CSRF Token（前端拿一个随机 token，每次关键请求带上来）。但 SameSite 是现代答案。

---

## [10:30] 一句话总结

**口播：**
> 一句话总结：HttpOnly 防 XSS 不防 CSRF。**SameSite=Strict 是现代浏览器的标准答案**，加上 CSRF Token 是双保险。

---

## [10:50] 下集预告

**口播：**
> 我们花了 5 集讲"防"，但还有个更根本的问题：单 token 体系被偷就完了。泄露后没法作废、过期时间矛盾。下集：双 token 体系。Access 短命扛业务、Refresh 长命扛"换新"、Refresh 存数据库可吊销。完整代码 + 6 步演示。系列收官集，下集见。
