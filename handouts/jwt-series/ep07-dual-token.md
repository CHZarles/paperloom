# EP07 — Access + Refresh 双 token 体系（主集 2 + 收尾）

**时长：** 15-18 分钟
**配套代码：** commit `ep7: dual token final`

---

## [00:00] Hook

**画面：** 终端 + devtools 分屏

**口播：**
> 前 6 集我们都在讲"怎么防"，今天要解决一个更根本的问题：单 token 体系，**被偷就完了**。泄露后没法作废、过期就强制登出、过期长了又不安全。怎么办？双 token 体系——Access 短命、Refresh 长命、可吊销。今天我们撸一个完整版。

---

## [00:30] 是什么：单 token 的两个致命问题

**画面：** 画图：单 token 漏洞

**口播：**
> 两个致命问题。
>
> 第一个：泄露后无解。JWT 是无状态的，服务器不知道"这个 token 已经被偷了"。用户改密码、退出登录，攻击者手里的 token **仍然有效**直到 exp 过期。
>
> 第二个：过期时间矛盾。设短了用户体验差——用户写一篇文章 30 分钟没保存，重新登录就丢了。设长了又不安全——攻击者有充足时间。
>
> 业界答案是：**两个 token 各管一摊**。

---

## [01:30] 是什么：双 token 设计

**画面：** 双 token 流程图

**口播：**
> 双 token 体系：
>
> **Access token**：5-15 分钟寿命，**仅用于业务请求**。短命的目的是"泄露了影响窗口小"。
>
> **Refresh token**：7-30 天寿命，**仅用于换新 access**。长命的目的是"用户体验好"——只要 refresh 没过期，用户不用重新登录。
>
> 泄露 access：影响窗口小（15 分钟内），自动过期。
>
> 泄露 refresh：**可吊销**——数据库把对应记录标 `revoked=true`，立即失效。
>
> 关键：access 频繁出现，要藏好；refresh 极少出现，可以存得更安全。

---

## [03:00] 是什么：2 种典型存储（推荐 vs 最差）

**画面：** 对比表

**口播：**
> 双 token 都出来了，存哪？两种典型组合：
>
> 方案 A（推荐）：Access 放**内存**（JS 变量，不存 localStorage），Refresh 放 **HttpOnly Cookie**。XSS 偷 access 顶多让你重新登录一次，偷不到 refresh。
>
> 方案 C（反面教材）：两个都放 localStorage。**XSS 一次偷光两个**——很多公司这么干，等着被偷。
>
> 今天我们实现方案 A。

---

## [05:00] 怎么实现：数据库表

**画面：** 写 DDL

**口播：**
> 第一步，建 refresh_tokens 表：
> ```sql
> CREATE TABLE refresh_tokens (
>     id BIGINT PRIMARY KEY AUTO_INCREMENT,
>     user_id BIGINT NOT NULL,
>     token_hash CHAR(64) NOT NULL,
>     expires_at DATETIME NOT NULL,
>     revoked BOOLEAN DEFAULT FALSE,
>     INDEX idx_user (user_id)
> );
> ```
>
> 重点：存的是 `token_hash` 不是原文。SHA-256 哈希，64 字符。**永远不存 refresh token 原文**——数据库泄露也不会导致 refresh token 泄露。
>
> `revoked` 字段是吊销开关，`expires_at` 是过期时间。

---

## [07:00] 怎么实现：AuthService.login

**画面：** 写 Java 代码

**口播：**
> 写 `AuthService.login`：
> 1. 验密码（略，业务逻辑）。
> 2. 调 `jwt.generateAccess(userId)` 生成 access token，15 分钟。
> 3. 生成 refresh token 原文：`UUID.randomUUID().toString()`。
> 4. 算 SHA-256 哈希，存数据库。
> 5. 把 refresh 原文塞进 `Set-Cookie`，标志位：`HttpOnly; Secure; SameSite=Strict; Path=/api/refresh; Max-Age=2592000`（30 天）。`Path=/api/refresh` 是关键——这个 cookie 只在调 refresh 接口时带，业务请求不带，减少 CSRF 面。
> 6. 返回 access 给前端（JSON），refresh 通过 Set-Cookie 自动给浏览器。

---

## [09:00] 怎么实现：AuthService.refresh

**画面：** 写 Java 代码

**口播：**
> 写 `AuthService.refresh(HttpServletRequest req)`：
> 1. 读 cookie `REFRESH_TOKEN`，拿原文。
> 2. 算 SHA-256 哈希。
> 3. 查数据库：`SELECT user_id FROM refresh_tokens WHERE token_hash=? AND revoked=false AND expires_at>NOW()`。找不到 → 401。
> 4. 找到 → 调 `jwt.generateAccess(userId)` 生成新 access，返回。
>
> 重点：整个过程**access 重新生成**。旧 access 不动——它自己会在 15 分钟内过期。

---

## [10:30] 怎么实现：前端

**画面：** 写最简 HTML + JS

**口播：**
> 前端逻辑：access 存内存变量，**不存 localStorage**。
>
> ```javascript
> let accessToken = null;
>
> async function api(url, opts = {}) {
>     opts.headers = { ...opts.headers, 'Authorization': 'Bearer ' + accessToken };
>     let r = await fetch(url, opts);
>     if (r.status === 401) {  // access 过期
>         const r2 = await fetch('/api/refresh', {
>             method: 'POST',
>             credentials: 'include'  // 关键：让浏览器带 cookie
>         });
>         const { access: newAccess } = await r2.json();
>         accessToken = newAccess;
>         opts.headers['Authorization'] = 'Bearer ' + accessToken;
>         r = await fetch(url, opts);  // 重试
>     }
>     return r;
> }
>
> // 登录
> const r = await fetch('/api/login', { method: 'POST', body: ... });
> const { access } = await r.json();
> accessToken = access;
> ```
>
> 关键：`credentials: 'include'` 让浏览器自动带 cookie。`accessToken = access` 存内存——浏览器关掉就没了，重新登录再拿。

---

## [12:30] 演示：4 步核心流程

**画面：** terminal 演示

**口播：**
> 4 步核心演示：
>
> 1. `POST /api/login` → 拿 access（JSON 响应）+ Set-Cookie REFRESH_TOKEN。看 devtools，两个都到了。
>
> 2. 调 `GET /api/me` 带 `Authorization: Bearer <access>` → 200，返回 userId。
>
> 3. 等 access 过期（或改 exp 到 5 秒重启），前端拦截器自动 `POST /api/refresh`（带 cookie）→ 拿到新 access → 重试原请求 → 200。**用户无感知**。
>
> 4. 管理员主动吊销：`UPDATE refresh_tokens SET revoked=true`。再次 `POST /api/refresh` → 401。**关键演示**：吊销生效，refresh token 立即失效。

---

## [14:30] 系列收尾

**画面：** 7 集回顾 + 生产环境提示

**口播：**
> 7 集下来，我们把 Web 身份认证的"是什么、怎么实现、怎么攻击、怎么防"都过了一遍。
>
> 回顾一下路线：Cookie → Session → JWT → 存储方案 → XSS 攻击 → CSRF 攻击 → 双 token 体系。从 1994 年的一个小机制讲到 2026 年大厂标准方案。
>
> **小科普**：这套双 token 模式（Access 短 + Refresh 长 + 可吊销）其实就是 **OAuth 2.0**（RFC 6749，2012）的简化版——OAuth 2.0 引入的 access_token + refresh_token 就是这个套路，JWT 当 access_token 用是 2015 年后的主流做法。下一个系列讲 OAuth 2.0 全栈通关，就是把这套机制扩展到第三方授权 + OIDC 单点登录。
>
> **生产环境你还要考虑这些**（提一嘴不展开）：
>
> - 滑动过期：refresh 一次就把 expires_at 往后推 30 天，长期活跃用户永远在线。
> - 异地登录检测：IP + 设备指纹突变，吊销所有 refresh。
> - refresh token 家族（rotation）：每次 refresh 旧 token 立即吊销，发新 token。攻击者偷了旧 refresh 一用就被发现。
> - 服务端黑名单：Redis 存被偷的 access，签名虽然通过但黑名单兜底拒绝。
> - 多端登录策略：踢人 / 共存 / 单设备——业务决策。
>
> **未来方向**：**Passkey / WebAuthn**（FIDO2 标准）正在替代密码 + token 组合——用户用设备生物识别（指纹 / Face ID）直接登录，**根本没有 token 可偷**。Apple/Google/Microsoft/1Password 都已支持。这是 2024-2026 年正在发生的演进，下一代身份认证的雏形。

**画面：** 行动号召

**口播：**
> 完整代码我放在 GitHub，链接在简介。觉得有用点赞、三连、加转发。
>
> 下一个系列——OAuth 2.0 全栈通关，或者 Spring Security 深度剖析——你们想先看哪个？评论区告诉我。
>
> 这个系列就到这里。下个系列见。

---

## [15:30] 一句话总结

**口播：**
> 一句话总结：Access 短命扛业务、Refresh 长命扛"换新"，Refresh 存数据库可吊销、HttpOnly 防 XSS 偷——**四件事缺一不可**。
