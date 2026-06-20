# EP03 — JWT 是什么（主集 1）

**时长：** 16-18 分钟
**配套代码：** commit `ep3: jwt manual`（**手撸 HS256，不依赖 jjwt 库**）

---

## [00:00] Hook

**画面：** 打开 jwt.io，粘贴一个 token，三段彩色字符串浮现

**口播：**
> 我手里这一串乱七八糟的字符：eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.xxx。看着像乱码，但它能让服务器在不存任何东西的情况下认出"我是谁"。这东西叫 JWT，今天我们手撸一个 HS256，不依赖任何 JWT 库。

---

## [00:30] 是什么：JWT 的本质 + 为什么是它（先讲观念，再讲技术）

**画面：** 画 Session 架构图 vs JWT 架构图 + 现代技术时间线（2010-2020）

**口播：**
> 这一集我们不直接讲 JWT 长啥样，先把 3 个观念讲透——**动机、本质、时代背景**。理解了这些，JWT 就不再是个神秘的字符串，而是一个看得见摸得着的工程方案。

### ① 动机：Session 的 3 个痛点

> 上集最后留了个问题：服务器必须存 100 万用户的 session。这事儿有 3 个痛点——
>
> **① 存储成本**：100 万用户 = 100 万条 Redis 记录。Redis 集群要多大？内存贵不贵？
>
> **② 跨服务 / 跨域**：session 存在机器 A，机器 B 验证就拿不到。微服务架构里 5 个服务都要鉴权，每个都得连 Redis 查——**强耦合**。
>
> **③ 移动端 / SPA**：cookie 模式在 native app 里水土不服（iOS / Android 没法像浏览器那样自动带 cookie）。App 端要鉴权，每次都得手动塞 token。
>
> **JWT 解决这一切**：让用户**自己携带**身份信息，签名防篡改，服务器**只验签不存任何东西**。

### ② 本质：JWT 不是"认证专用"

> 但是——**JWT 的本质是什么**？
>
> 它**不是**"认证专用"的东西。JWT 本质是**带签名的数据声明**（signed claim）——任何"我需要信任一段数据不被篡改"的场景都能用：
>
> - 身份认证（**最常见，但只是众多用途之一**）
> - 跨服务数据传递（Service A 签，Service B 验）
> - 单点登录（SSO）：OIDC 用 JWT 当 ID Token
> - 安全链接：Gravatar、CDN 签名 URL
> - 密码重置 token、邮箱验证 token
>
> **观念升级**：JWT = "**可信任的字符串**"，不是"= 身份认证"。这就像 SQL = "查询语言"，不只用于查用户——也用于查订单、查日志。工具的本质决定了它的**通用性**。

### ③ 时代背景：为什么 2015 年后 JWT 突然爆火？

> **三件事撞一起了**：
>
> - **2010-2015：微服务兴起**——服务多了，跨服务"信任"成刚需
> - **2015-2020：SPA + 移动 App 普及**——客户端多了，"无状态"成刚需
> - **2012-2020：OAuth 2.0 + OIDC 标准化**——JWT 成了身份交换的官方格式
>
> 所以 JWT 不只是"一个 token 格式"，它是**分布式系统时代的信任基础设施**。今天我们聚焦在身份认证这个最常见用途上，但记住它的本质远不止于此。
>
> 好，观念讲完了。下面我们看**它具体长啥样**。

---

## [03:30] 是什么：三段式结构

**画面：** jwt.io 三段式，红紫蓝高亮

**口播：**
> 先看 jwt.io 这个工具，我手里这串字符被两个点分成三段。红色这段叫 header，紫色这段叫 payload，蓝色这段叫 signature。每段都是 base64url 编码的，不是加密的——任何人都能解码。
>
> 我们看 header 段，点解码：`{"alg":"HS256","typ":"JWT"}`。就两个字段：算法是 HS256，类型是 JWT。
>
> 再看 payload 段：`{"sub":"1234567890","name":"John","iat":1516239022}`。"sub"是 subject，存 userId；"iat"是 issued at，签发时间；还有一个没写出来的 "exp"，过期时间。这几个都是 JWT 的"标准 claim"。

**画面：** 强调"签名 ≠ 加密"

**口播：**
> 重点来了——**payload 是明文**。你 userId、邮箱、甚至密码（如果有人犯傻）都明文存在这里。攻击者拿到 token，第一件事就是 base64 解码 payload，所有用户信息一览无余。
>
> 80% 的人误解 JWT 都栽在这一步。**签名 ≠ 加密**。签名只防"篡改"，不防"偷看"。

---

## [05:30] 是什么：HS256 流程

**画面：** HS256 流程图

**口播：**
> 那签名是怎么算的？HS256 流程：先用 base64url 把 header 和 payload 编码，然后拿 `header.payload` 这两段拼起来的字符串，加一个密钥 secret，过一遍 HMAC-SHA256 算法，输出一个 256 bit 的哈希，再 base64url 编码，就是 signature。
>
> 三段拼起来：`header.payload.signature`，就是完整的 JWT。
>
> 验签时，服务端拿同一个 secret 重算一遍 signature，跟客户端带来的对比。一致 = 没被篡改，不一致 = 拒绝。再加上 exp 时间校验——过期 = 拒绝。

---

## [07:30] 怎么实现：手撸 JwtUtils

**画面：** IDE 新建 `MiniJwtUtils.java`

**口播：**
> 开撸。**注意：我不用 jjwt 库，纯手撸**。为什么？因为手撸一遍，你才知道 JWT 真的就是字符串拼接 + HMAC，没啥神秘的。
>
> 两个方法：`generate(userId)` 生成 token，`verify(token)` 验签并返回 payload。

**画面：** 写 `generate` 方法

**口播：**
> generate 方法：先拼 header JSON 和 payload JSON，base64url 编码，中间用点连起来。算 HMAC-SHA256 需要 `javax.crypto.Mac` 类，初始化密钥 `new SecretKeySpec(secret.getBytes(), "HmacSHA256")`，调 `mac.doFinal()` 拿签名，再 base64url 编码。最后三段拼接返回。

**画面：** 写 `verify` 方法

**口播：**
> verify 方法：split 拿三段，重新算 signingInput 的 HMAC-SHA256，跟传来的 signature 对比。不一致抛异常"bad signature"。一致就 base64url 解码 payload 返回。
>
> base64url 和 base64 的区别：base64url 把 `+` 换成 `-`，`/` 换成 `_`，去掉 `=` 填充。因为 `+` `/` `=` 在 URL 里有特殊含义，会被 URL 编码变长。

---

## [10:30] 怎么实现：接入 controller

**画面：** 改 `AuthController.java`

**口播：**
> 改 controller。`/login` 不再写 Session ID 进 cookie 了——**JWT 替代的是 Session ID，不是 Cookie**。这一集我们故意先不解决"token 放哪"的问题，就返回 JSON `{token: "eyJ..."}`。`/me` 用 `@RequestHeader("Authorization")` 拿 Bearer token，调 `jwtUtils.verify(token)` 解析，提取 userId。
>
> **注意：真实生产你**绝对**不能这么写**——把 token 放 JSON 返回，客户端要存哪、怎么带、怎么防 XSS，全是问题。这一集我们只关心 token 本身的生成和验证。存哪的问题 EP4 讲。

---

## [11:30] 怎么实现：演示

**画面：** terminal 演示 + jwt.io 对照

**口播：**
> 演示。`POST /login` 拿 token。复制到 jwt.io，header、payload 都对得上。
>
> 改一下 secret 重新跑，再用旧 token 调 /me → 验签失败 → 401。这说明：签名是 secret 算出来的，secret 变了旧 token 全部失效。
>
> 改 payload 的 exp 改到 1 秒前，重新生成 token，调 /me → 验签虽然通过但 exp 过期 → 401。

---

## [13:30] 攻防：2 个致命反例

**画面：** 关键代码片段特写

**口播：**
> 两个新手最常犯的错。
>
> 反例一：把 password 放 payload。我见过有人把 `{"userId":1,"password":"123456"}` 放 payload，美其名曰"前端加密"。—— 兄弟，base64 不是加密啊！攻击者拿到 token 几秒解码出来。
>
> 反例二：secret 用 `secret` 或者 `123456`。我现场用 jwt.io 输入常见弱 secret（`secret`/`123456`/`admin`）字典尝试——5 秒命中。**注意**：jwt.io 不是破解工具，是**验证**工具，**真正暴力破解 256-bit HMAC 是不可能的**；这里的"5 秒"是因为 secret 就在弱 secret 字典里。**生产环境 secret 必须 32 字节以上随机串**，最好 `openssl rand -base64 32` 生成。

---

## [16:00] 一句话总结

**口播：**
> 一句话总结：JWT 本质是 **signed claim 格式**（带签名的数据声明），**不只用于认证**。三段式 `header.payload.signature`，签名防篡改、不防偷看，服务器拿到只验签不存。JWT 在 2015 年标准化，2020 年后靠 OAuth 2.0 + OIDC + 微服务 + SPA 全面爆发，是分布式系统时代的信任基础设施。

---

## [16:30] 下集预告

**口播：**
> 现在客户端拿到 token 了——放哪？浏览器有三个地方能放：localStorage、Cookie 普通、Cookie HttpOnly。它们的"自动带"和"JS 可读"有什么区别？下集揭晓。然后下两集我们要亲手攻击 localStorage 和 HttpOnly cookie，让你们看清楚"安全是相对的"。
