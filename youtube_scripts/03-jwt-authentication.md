# 第 03 集：JWT 鉴权登场——从「裸奔」到「有身份的请求」

> 系列：派聪明 RAG 后端演进全解
> 集数：03 / 20+
> 主题：JWT 原理、jjwt 库、JwtUtils 实现细节、OncePerRequestFilter、Token 自动刷新
> 上集回顾：第 02 集我们写了 `User` 实体和 `UserRepository`，能存能查
> 本集目标：所有 HTTP 请求都带身份，Token 还能在过期前自动续命

---

## 【开场 Hook】

你写了一个 `UserController`，前端请求过来，返回数据。

**但是！**任何一个人，只要知道你的 URL，就能拿到所有用户的数据。**你的系统现在「裸奔」。**

在 `pai-smart` 这种企业级项目里，**没有鉴权 = 没有产品**。

今天我们要做的就是：**让每一个请求都「有身份」**。我们用的方案不是老旧的 Session+Cookie，而是**JWT（JSON Web Token）**——这是 2015 年以后 Java 后端的事实标准。

很多人学 JWT 是看官方文档、或者博客，**看完还是似懂非懂**。今天我们直接看一个**生产级**的 JWT 实现，把每一个细节都掰开。

---

## 【一、JWT 是什么？三段式结构】

JWT 是一段字符串，分三段，用 `.` 分隔：

```
eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJjaGFybGVzIn0.abc123signature
```

第一段 **Header**（算法说明）：
```json
{ "alg": "HS256" }
```

第二段 **Payload**（业务数据，也叫 Claims）：
```json
{
  "sub": "charles",
  "exp": 1717228800,
  "userId": "1",
  "role": "USER",
  "orgTags": "DEFAULT,DEV",
  "primaryOrg": "DEV"
}
```

第三段 **Signature**（签名）：
```
HMACSHA256(base64Url(header) + "." + base64Url(payload), secret)
```

**面试考点 1**：JWT 的 Header 和 Payload 都是 **Base64 编码**，不是加密！**任何人都能解码看到内容**。所以你绝对不能在 JWT 里放密码、身份证号等敏感信息。**JWT 的安全性来自签名，不来自加密**。

**面试考点 2**：JWT 和 Session 的区别？

| 维度 | Session | JWT |
|---|---|---|
| 存储 | 服务端内存/Redis | 客户端（Authorization Header） |
| 状态 | 有状态 | 无状态 |
| 扩展性 | 集群需要 Session 共享 | 天然分布式 |
| 撤销 | 服务端删除 Session | 需要黑名单机制 |
| 性能 | 服务端查 Redis | 验签即可（无状态） |

`pai-smart` 选 JWT，**是因为后面会接 WebSocket**（第 10 集）。WebSocket 协议里 Session 机制不友好，用 JWT 客户端带 token 直接握手最自然。

---

## 【二、JwtUtils 核心实现：逐段拆解】

我们看 `JwtUtils.generateToken`：

```java
public String generateToken(String username) {
    SecretKey key = getSigningKey();

    User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new RuntimeException("User not found"));

    String tokenId = generateTokenId();
    long expireTime = System.currentTimeMillis() + EXPIRATION_TIME;

    Map<String, Object> claims = new HashMap<>();
    claims.put("tokenId", tokenId);
    claims.put("role", user.getRole().name());
    claims.put("userId", user.getId().toString());

    if (user.getOrgTags() != null && !user.getOrgTags().isEmpty()) {
        claims.put("orgTags", user.getOrgTags());
    }
    if (user.getPrimaryOrg() != null && !user.getPrimaryOrg().isEmpty()) {
        claims.put("primaryOrg", user.getPrimaryOrg());
    }

    String token = Jwts.builder()
            .setClaims(claims)
            .setSubject(username)
            .setExpiration(new Date(expireTime))
            .signWith(key, SignatureAlgorithm.HS256)
            .compact();

    tokenCacheService.cacheToken(tokenId, user.getId().toString(), username, expireTime);
    return token;
}
```

### 2.1 密钥派生：`getSigningKey()`

```java
@Value("${jwt.secret-key}")
private String secretKeyBase64; // Base64 编码后的密钥

private SecretKey getSigningKey() {
    byte[] keyBytes = Base64.getDecoder().decode(secretKeyBase64);
    return Keys.hmacShaKeyFor(keyBytes);
}
```

**这个设计的讲究**：

- **`@Value` 注入密钥**：从配置文件 `application.yml` 里读，避免硬编码。生产环境会通过环境变量覆盖。
- **Base64 编码存储**：因为密钥是**任意字节**，直接写在 YAML 里会有编码问题。Base64 一次转换更安全（也兼容非 ASCII 字符）。
- **HS256 算法的密钥长度**：**至少 256 bit = 32 字节**。`hmacShaKeyFor` 会**自动检查**密钥长度，不够就抛异常。

**面试考点 3**：HS256 和 RS256 怎么选？

- **HS256（HMAC + SHA256）**：对称加密，签发和验证用**同一个密钥**。性能快。适合**单服务**或**可信服务集群内部**。
- **RS256（RSA + SHA256）**：非对称加密，签发用私钥，验证用公钥。性能慢。适合**第三方应用接入**（OAuth 2.0、OpenID Connect）。

`pai-smart` 选 HS256 合理——内部系统，没必要上 RS256。

### 2.2 Claims 设计：放什么不放什么？

```java
claims.put("tokenId", tokenId);  // 服务端缓存 key
claims.put("role", user.getRole().name());
claims.put("userId", user.getId().toString());
claims.put("orgTags", user.getOrgTags());
claims.put("primaryOrg", user.getPrimaryOrg());
```

**关键设计**：

1. **`tokenId` 是关键**：后面讲到「Token 注销」和「自动刷新」要靠它。**没有 `tokenId`，你做不到登出**——因为 JWT 一旦签发就管不了了。
2. **`userId` 单独存**：第 10 集 WebSocket 聊天时，业务逻辑要拿 `userId`（不是 `username`）去查库。
3. **`orgTags` 和 `primaryOrg`**：第 11 集多租户体系全靠这个。**注意是 String，不是 List<String>**——这是项目早期的妥协（后面会优化）。
4. **`username` 没显式 put**：因为 `setSubject(username)` 已经把 username 放进了 `sub` 字段。

**面试考点 4**：JWT Payload 应该放什么？

- **必须放**：`sub`（用户标识）、`exp`（过期时间）、`iat`（签发时间）、`jti`（token ID）。
- **按需放**：角色、权限 ID、组织、租户 ID。
- **绝对不放**：密码、身份证、银行卡号、手机号、邮箱等敏感信息。
- **控制大小**：JWT 每次请求都发一次。**通常建议不超过 4KB**。HTTP Header 太大影响性能。

### 2.3 签发 + 缓存的双写

```java
String token = Jwts.builder()
        .setClaims(claims)
        .setSubject(username)
        .setExpiration(new Date(expireTime))
        .signWith(key, SignatureAlgorithm.HS256)
        .compact();

tokenCacheService.cacheToken(tokenId, user.getId().toString(), username, expireTime);
```

**两个动作**：
1. **JWT 签发**：客户端拿到这个 token 就可以用了。
2. **Redis 缓存**：服务端记录这个 token 是有效的。

**为什么不只签发 JWT 就行？**

因为 **JWT 签发后就无法撤销**！用户退出登录、改密码、被封号，**JWT 还在有效期内就还能用**。

**所以**用 Redis 维护一个 `valid_tokens` 集合：
- 登出：把 `tokenId` 从集合里删掉（或加入黑名单）。
- 校验：先查 Redis，再验签名。

**这就是「双写」**——`pai-smart` 的 JWT 实际上是**有状态的 JWT**，不是纯无状态。

**面试考点 5**：有状态 JWT vs 无状态 JWT 怎么选？

- **无状态 JWT**：完全不存服务端。优点是极致性能。缺点是「签发即生效」，无法主动撤销。
- **有状态 JWT（`pai-smart` 这种）**：服务端维护 token 状态。优点是**可控**。缺点是每请求多一次 Redis 查询。

**折中方案**：把 Token 有效期调短（比如 15 分钟），用 Refresh Token 续期。`pai-smart` 用的就是这个：Access Token 1 小时 + Refresh Token 7 天。

---

## 【三、Token 校验：`validateToken` 的双重验证】

```java
public boolean validateToken(String token) {
    try {
        // 1. 先从 JWT 拿 tokenId
        String tokenId = extractTokenIdFromToken(token);
        if (tokenId == null) return false;

        // 2. 查 Redis
        if (!tokenCacheService.isTokenValid(tokenId)) return false;

        // 3. 验签名
        Jwts.parserBuilder()
                .setSigningKey(getSigningKey())
                .build()
                .parseClaimsJws(token);

        return true;
    } catch (ExpiredJwtException e) { ... }
    catch (SignatureException e) { ... }
    return false;
}
```

**三个检查，缺一不可**：

1. **解析 tokenId**：拿不到 → 拒绝（说明 token 格式有问题或者不是本系统签发的）。
2. **查 Redis**：tokenId 不在有效集合里 → 拒绝（说明被注销了）。
3. **验签名**：签名不匹配 → 拒绝（说明被篡改）。

**为什么 `parseClaimsJws` 会抛 `ExpiredJwtException`？**

因为 jjwt 库里**「过期」也是一种「验证失败」**。`exp` 字段小于当前时间，jjwt 就抛异常。这比手动比对时间更安全——避免你自己写代码时漏掉某个分支。

**面试考点 6**：`ExpiredJwtException` 在 `SignatureException` **之前**捕获，为什么？

答：因为「过期但签名正确」和「被篡改」是**两种不同情况**，处理方式不同：
- 过期：可以考虑自动刷新（项目里就这么干）。
- 被篡改：直接拒绝，记录日志，可能涉及攻击。

**实战中要小心**：如果你把 `ExpiredJwtException` 放在最外层 catch，**真正的攻击（伪造签名）也会被当过期处理**——这就有漏洞了。`pai-smart` 把它们分开 catch，是正确的。

---

## 【四、JwtAuthenticationFilter：拦截每个请求】

```java
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) {
        try {
            String token = extractToken(request);
            if (token != null) {
                String newToken = null;
                String username = null;

                if (jwtUtils.validateToken(token)) {
                    if (jwtUtils.shouldRefreshToken(token)) {
                        newToken = jwtUtils.refreshToken(token);
                    }
                    username = jwtUtils.extractUsernameFromToken(token);
                } else {
                    if (jwtUtils.canRefreshExpiredToken(token)) {
                        newToken = jwtUtils.refreshToken(token);
                        username = jwtUtils.extractUsernameFromToken(newToken);
                    }
                }

                if (newToken != null) {
                    response.setHeader("New-Token", newToken);
                }

                if (username != null && !username.isEmpty()) {
                    UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            logger.error("Cannot set user authentication: {}", e);
        }
    }

    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
```

### 4.1 `OncePerRequestFilter` 是什么？

它是 Spring Web 提供的一个**抽象类**，保证**每个请求只执行一次**（防止内部 forward、include 时被多次调用）。

**为什么不直接实现 `Filter`？**

实现 `Filter` 接口的话，**dispatcher 内部 forward 的时候**同一个 filter 可能跑两次。`OncePerRequestFilter` 内部用一个 `request` attribute 标记，保证只跑一次。

### 4.2 「无感知刷新」是怎么做到的？

**这是 `pai-smart` 的亮点设计**。

```java
if (jwtUtils.validateToken(token)) {
    if (jwtUtils.shouldRefreshToken(token)) {
        newToken = jwtUtils.refreshToken(token);
    }
    username = jwtUtils.extractUsernameFromToken(token);
} else {
    if (jwtUtils.canRefreshExpiredToken(token)) {
        newToken = jwtUtils.refreshToken(token);
        username = jwtUtils.extractUsernameFromToken(newToken);
    }
}

if (newToken != null) {
    response.setHeader("New-Token", newToken);
}
```

**两个阈值**：
- `REFRESH_THRESHOLD = 5 * 60 * 1000`（5 分钟）：**剩余时间不足 5 分钟**，主动刷新。
- `REFRESH_WINDOW = 10 * 60 * 1000`（10 分钟）：**过期不超过 10 分钟**，还可以续命。

**前端流程**：
1. 用户登录 → 拿到 `token` 和 `refreshToken`。
2. 用户操作 → 请求头带 `Authorization: Bearer xxx`。
3. 服务端 filter 发现 token 快过期 → 重新签发 → 把新 token 放在响应头 `New-Token` 里。
4. 前端 axios/fetch 拦截器：每次响应检查有没有 `New-Token`，有就更新本地存储。

**用户全程无感**。

**面试考点 7**：为什么用「无感知刷新」而不是「401 触发刷新」？

- **401 触发刷新**：用户操作到一半 token 过期 → 请求 401 → 客户端用 refreshToken 调刷新接口 → 拿到新 token → 重发原请求。**整个过程用户能感受到延迟**，且复杂业务有竞态问题。
- **无感知刷新**：服务端提前续命。**用户从开始到结束用同一个 token**，体验丝滑。

`pai-smart` 选择**两者结合**：无感知刷新 + refresh token 兜底。

### 4.3 SecurityContextHolder 是什么鬼？

```java
SecurityContextHolder.getContext().setAuthentication(authentication);
```

这是 **Spring Security 的核心**。

**面试考点 8**：`SecurityContextHolder` 的存储策略？

- 默认是 `ThreadLocal` 模式（`MODE_THREADLOCAL`）。
- 同一线程内的 `Controller`、`Service` 都能从 `SecurityContextHolder` 拿到当前登录用户。
- 异步场景（`@Async`）会丢失，要用 `MODE_INHERITABLETHREADLOCAL` 或手动传递。

`pai-smart` 在 `ChatHandler` 里是这么用的：

```java
public void processMessage(String userId, String userMessage, WebSocketSession session) {
    // userId 是从 WebSocket 握手时塞进 session 的，不是从 SecurityContextHolder
    // 因为 WebSocket 不是普通的 HTTP 请求，SecurityContext 不会自动传播
}
```

**WebSocket 怎么鉴权是另一个大坑**——第 10 集再讲。

---

## 【五、TokenCacheService：Redis 缓存的实现细节】

我们看 token 是怎么缓存的：

```java
tokenCacheService.cacheToken(tokenId, user.getId().toString(), username, expireTime);
```

具体的 `TokenCacheService` 实现我们没看，但可以推测：

```java
public void cacheToken(String tokenId, String userId, String username, long expireTime) {
    long ttl = expireTime - System.currentTimeMillis();
    String key = "auth:token:" + tokenId;
    redisTemplate.opsForValue().set(key, userId, ttl, TimeUnit.MILLISECONDS);
    // 同时维护一个 userId -> Set<tokenId> 的反向索引
    String userKey = "auth:user-tokens:" + userId;
    redisTemplate.opsForSet().add(userKey, tokenId);
    redisTemplate.expire(userKey, REFRESH_TOKEN_EXPIRATION_TIME, MILLISECONDS);
}
```

**关键点**：
- **正向索引**：`tokenId -> userId`（校验时用）。
- **反向索引**：`userId -> Set<tokenId>`（批量登出时用）。
- **TTL 跟随 token 过期时间**：Redis 自动清理，**不用定时任务**。

**面试考点 9**：为什么不用 `redis.setex` 而用 `redisTemplate.opsForValue().set(key, value, ttl)`？

答：在 Spring Data Redis 里，`opsForValue().set(key, value, timeout, unit)` 内部就是 SETEX 命令，**等价**。但这种 API 更面向对象，类型安全。

---

## 【六、Refresh Token：另一条战线】

```java
public String generateRefreshToken(String username) {
    // ... 类似的签发逻辑
    claims.put("refreshTokenId", refreshTokenId);
    claims.put("userId", user.getId().toString());
    claims.put("type", "refresh");
    // ...
}
```

**Refresh Token 故意和 Access Token 分开**：
- **Access Token**：短（1 小时），每次请求都带，**权限大**。
- **Refresh Token**：长（7 天），**只在刷新时用**，权限小（只能调 `/auth/refresh`）。

**这叫「最小权限原则」**——万一 Refresh Token 泄露，攻击者也只能续期 Access Token，**拿不到用户数据**。

**面试考点 10**：Refresh Token 怎么存？

- **不能存 localStorage**（XSS 攻击能拿到）。
- **应该存 httpOnly cookie**（前端 JS 拿不到）。
- 或者存内存里，关闭浏览器就丢（最安全但用户体验差）。

`pai-smart` 的前端具体怎么存，我们看 `frontend/src` 再说。

---

## 【七、Token 撤销：登出怎么做？】

```java
public void invalidateToken(String token) {
    String tokenId = extractTokenIdFromToken(token);
    if (tokenId != null) {
        Claims claims = extractClaimsIgnoreExpiration(token);
        if (claims != null) {
            long expireTime = claims.getExpiration().getTime();
            String userId = claims.get("userId", String.class);
            tokenCacheService.blacklistToken(tokenId, expireTime);
            tokenCacheService.removeToken(tokenId, userId);
        }
    }
}
```

**两步**：
1. **`blacklistToken`**：把 tokenId 加到黑名单（带 TTL，**和原 token 过期时间一致**——过期了黑名单自动清，节省 Redis 内存）。
2. **`removeToken`**：从正向索引里删除。

**注意**：`blacklist` 和 `remove` 都要做！否则会出现「正向索引没了但黑名单还在」的不一致。

**批量登出**：

```java
public void invalidateAllUserTokens(String userId) {
    tokenCacheService.removeAllUserTokens(userId);
}
```

比如「修改密码」后，让该用户**所有设备**的 token 全部失效。这就是**反向索引的价值**。

---

## 【八、SecurityConfig：怎么把 filter 串进 Spring Security？】

虽然第 04 集会重点讲 `SecurityConfig`，但这里先剧透一句：

```java
http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
```

**`addFilterBefore` 把 `JwtAuthenticationFilter` 放在 `UsernamePasswordAuthenticationFilter` 之前**。Spring Security 的过滤器链是有顺序的——**JWT 校验必须在用户名密码校验之前**，因为我们是「用 token 代替用户名密码」的认证方式。

---

## 【九、常见坑 & 面试问答】

**Q1：JWT 的 `exp` 设置多久合适？**

A：业务决定。**金融类 5-15 分钟**（高安全），**普通 To C 1-2 小时**（平衡安全和体验），**内部系统可以 8 小时**（一天工时）。`pai-smart` 1 小时，**配合 5 分钟提前刷新**，实际体验接近「永远在线」。

**Q2：JWT 里的 userId 能不能是数字 Long？**

A：技术上是，但 `pai-smart` 用了 `String`——**保持 JWT 自描述**（`Long` 经过 `Jwts` 序列化会变样）。如果业务需要保持类型一致，可以 `claims.put("userId", user.getId())`，但调试时看 JWT 内容会困惑。

**Q3：JWT 怎么防重放攻击？**

A：加 `jti`（JWT ID）+ 服务端校验 `jti` 一次性使用。`pai-smart` 的 `tokenId` 就是这个作用。**更高安全级别**可以加 `nbf`（not before，签发后多久才能用）。

**Q4：JWT 在 WebSocket 怎么传？**

A：握手时通过 `Sec-WebSocket-Protocol` 头、URL 参数、或者第一条消息带过来。`pai-smart` 是在握手时解析 Header——第 10 集详解。

**Q5：双 Token 机制的缺点是什么？**

A：复杂度高了。前端要管理两个 token、刷新逻辑、心跳检测。很多团队觉得不如直接用「长 Access Token + 黑名单」简单。`pai-smart` 选了双 Token，**因为它有多端登录场景**（手机、PC 同时登录），双 Token 让单端登出更优雅。

---

## 【十、思考题：为什么 `JwtAuthenticationFilter` 放在 `config/` 包？**

看代码：

```java
package com.yizhaoqi.smartpai.config;
```

它不是 `util/`，也不是 `security/`，而是 `config/`。这是项目的一个**不太规范的小细节**——`Filter` 严格说不是配置类（`@Configuration`），它是「过滤器」。

**更合理的位置**应该是 `security/` 或 `filter/`。但 `pai-smart` 在演进过程中没有专门建包，**很多东西都往 `config/` 里塞**——这是 KISS 原则的体现，也是个**未来重构的伏笔**。

---

## 【十一、下集预告】

第 03 集我们有了 JWT filter，**但还没串进 Spring Security**。光写 filter 不挂到过滤链上，**等于没写**。

第 04 集我们要把：

- `SecurityConfig` 写完整。
- 登录、注册、登出接口。
- 密码加密（`PasswordUtil`，BCrypt）。
- `UserDetailsService` 的实现。
- 路径级别的鉴权规则。

**Spring Security 是 90% Java 后端面试都会问的**——`pai-smart` 的实现值得好好学。

---

> 如果你正在面试，**这一集的内容直接背下来能过一面**。
> 觉得有用的话点赞、收藏、投币——你们的支持是我做 20 集的最大动力。

下一集链接：[第 04 集：Spring Security 串起来——登录、注册、权限规则](./04-spring-security.md)
