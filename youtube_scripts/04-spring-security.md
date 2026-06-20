# 第 04 集：Spring Security 串起来——登录、注册、权限规则

> 系列：派聪明 RAG 后端演进全解
> 集数：04 / 20+
> 主题：Spring Security 6 的 lambda DSL、BCrypt、UserDetailsService、路径级授权
> 上集回顾：第 03 集我们写了 JwtAuthenticationFilter，但还没挂到 Spring Security 链上
> 本集目标：把 SecurityConfig 配完整，密码用 BCrypt 加密，注册能跑通

---

## 【开场 Hook】

很多同学学 Spring Security，**最大的障碍是「概念多」**——`AuthenticationManager`、`ProviderManager`、`UserDetailsService`、`PasswordEncoder`、`SecurityFilterChain`、`AccessDecisionManager`……

但你一旦在真实项目里**用 lambda DSL** 写过一遍 `HttpSecurity`，会发现：**新版 Spring Security 6.x 比老版简单太多了**。

`pai-smart` 用的是 Spring Boot 3.4.2，对应 Spring Security 6.x。本集我们就**一行一行**拆它的 `SecurityConfig`，然后扩展到登录、注册、密码加密。

---

## 【一、SecurityConfig.java 全文逐行解析】

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Autowired
    private OrgTagAuthorizationFilter orgTagAuthorizationFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/", "/test.html", ...).permitAll()
                .requestMatchers("/chat/**", "/ws/**").permitAll()
                .requestMatchers("/api/v1/users/register", "/api/v1/users/login").permitAll()
                .requestMatchers("/api/v1/test/**").permitAll()
                .requestMatchers("/api/v1/upload/**", ...).hasAnyRole("USER", "ADMIN")
                .requestMatchers("/api/v1/users/conversation/**").hasAnyRole("USER", "ADMIN")
                .requestMatchers("/api/search/**").hasAnyRole("USER", "ADMIN")
                .requestMatchers("/api/v1/chat/**").hasAnyRole("USER", "ADMIN")
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/users/primary-org").hasAnyRole("USER", "ADMIN")
                .anyRequest().authenticated())
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(orgTagAuthorizationFilter, JwtAuthenticationFilter.class);

        return http.build();
    }
}
```

### 1.1 `@EnableWebSecurity` 是什么？

Spring Security 5+ 之后，这个注解是**必需的**（Spring Boot Auto Configuration 默认会带上，但显式声明更稳）。它激活了 Spring Security 的 `@Configuration` 类和过滤器链支持。

### 1.2 整个文件就一个 `@Bean`：SecurityFilterChain

这是 Spring Security 6 的**新写法**。老版本是 `WebSecurityConfigurerAdapter` + 重写 `configure(HttpSecurity http)` 方法。**6.x 全部改成 `@Bean` 形式**——更灵活，可以有多个 `SecurityFilterChain`（比如 API 一套、管理后台一套）。

### 1.3 `csrf(csrf -> csrf.disable())`——为什么关？

CSRF（Cross-Site Request Forgery）**只对「浏览器 + Session Cookie」这种场景有意义**。我们的系统：

- **用 JWT 而不是 Session**：每次请求带 `Authorization` 头，**不像 Cookie 自动携带**。
- **前后端分离**：前端是 Vue SPA，不是表单提交。

所以 CSRF 攻击**构不成威胁**——直接关。

**什么时候要开？**
- 传统服务端渲染（Thymeleaf、JSP）。
- 用 Session + Cookie 鉴权的内部系统。

### 1.4 `authorizeHttpRequests` 的「白名单」+「黑名单」模型

```java
.requestMatchers("/api/v1/users/register", "/api/v1/users/login").permitAll()
.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
.anyRequest().authenticated()
```

**这是个**「**先 permit，再 deny**」**的链式匹配**。**第一次匹配上的规则生效，后面的不看了**。

- **`permitAll()`**：任何人都能访问（不检查 token）。
- **`hasRole("ADMIN")`**：必须有 `ROLE_ADMIN` 这个角色。
- **`hasAnyRole("USER", "ADMIN")`**：USER 或 ADMIN 都行。
- **`authenticated()`**：只要登录了就行（任何角色）。
- **`denyAll()`**：谁都不行。
- **`anyRequest()`**：兜底。

**面试考点 1**：`.requestMatchers("/admin/**")` 和 `.requestMatchers("/admin/*")` 区别？

- `/**` 匹配多级目录。
- `/*` 只匹配一级。

`/admin/user/list` 匹配 `/admin/**`，不匹配 `/admin/*`。

### 1.5 顺序敏感！

```java
.requestMatchers("/api/v1/users/primary-org").hasAnyRole("USER", "ADMIN")
.anyRequest().authenticated()
```

**这两条规则，位置写反就出问题**。

如果 `anyRequest().authenticated()` 在前，所有请求（包括 `permitAll` 的）都会被检查有没有登录。Spring Security 会**按顺序匹配**，**第一次匹配上就停**——所以 `permitAll` 必须在 `anyRequest().authenticated()` 之前。

**实战中常见的 BUG**：加了新接口忘了加 `permitAll`，被 `anyRequest().authenticated()` 兜底，**未登录用户调不通**。`pai-smart` 写得很细——每个公开接口都明确 `permitAll()`，**不依赖默认行为**。

### 1.6 `sessionManagement` 为什么设成 STATELESS？

```java
.sessionManagement(session -> session
    .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
```

**四种 Session 策略**：

| 策略 | 含义 |
|---|---|
| `STATELESS` | 不创建 Session，每个请求独立 |
| `NEVER` | 不主动创建，但已经存在的 Session 不会被销毁 |
| `IF_REQUIRED` | 需要时创建（默认） |
| `ALWAYS` | 总是创建 |

JWT 场景必须 `STATELESS`——**否则 Spring Security 会偷偷用 HttpSession 缓存 Authentication**，导致多实例部署时**用户状态不一致**。

### 1.7 `addFilterBefore` 顺序的艺术

```java
.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
.addFilterAfter(orgTagAuthorizationFilter, JwtAuthenticationFilter.class);
```

**Spring Security 过滤器链默认有几十个 filter**，顺序大致是：
1. `SecurityContextPersistenceFilter`
2. `HeaderWriterFilter`
3. **...**
4. **`UsernamePasswordAuthenticationFilter`**（表单登录用）
5. **...**
6. `AuthorizationFilter`（最后做权限决策）

**`addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)`** 把 JWT 过滤器插到「表单登录」之前——**这样 JWT 解析在 Spring Security 的标准认证之前完成**。

**`addFilterAfter(orgTagAuthorizationFilter, JwtAuthenticationFilter.class)`** 把组织标签授权放在 JWT 之后——**因为 org tag 鉴权依赖 SecurityContext 里的 Authentication**。

**面试考点 2**：filter 顺序写反了会怎样？

答：
- JWT 放在最后 → 走到 `AuthorizationFilter` 时 SecurityContext 是空的 → **所有请求 401**。
- OrgTag filter 放在 JWT 之前 → 拿不到 username → 鉴权逻辑全部失效 → **「能登录但无权限」**。

**这个 BUG 极难排查**，因为代码看起来都对。`pai-smart` 的 `addFilterAfter` 是正确选择。

---

## 【二、CustomUserDetailsService：把 User 变成 Spring Security 的 UserDetails】

```java
@Service
public class CustomUserDetailsService implements UserDetailsService {

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        return new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                user.getPassword(),
                getAuthorities(user.getRole())
        );
    }

    private Collection<? extends GrantedAuthority> getAuthorities(User.Role role) {
        return Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
}
```

**注意一个**「**坑**」**：这里的 `User` 是 `org.springframework.security.core.userdetails.User`，不是我们自己的 `com.yizhaoqi.smartpai.model.User`。

**`UserDetailsService` 的契约**：通过 `username` 找到用户，返回 `UserDetails` 接口的实现。

`User` 实体 → `UserDetails` 的转换发生在这一行。**关键转换**：
- `user.getPassword()`（BCrypt 加密后的字符串）。
- `getAuthorities(role)` → `"ROLE_USER"` / `"ROLE_ADMIN"`。

**`ROLE_` 前缀是约定俗成**——`hasRole("ADMIN")` 实际匹配的是 `"ROLE_ADMIN"`。**没有 `ROLE_` 前缀，`hasRole` 找不到**。这是 Spring Security 的设计，**记死**。

**面试考点 3**：`@PreAuthorize("hasRole('ADMIN')")` 和 `hasRole("ADMIN")` 等价吗？

答：等价。`@PreAuthorize` 的 SpEL 表达式里写的是 `hasRole(...)`，实际 Spring 会拼上 `ROLE_` 前缀。

如果用 `hasAuthority("ADMIN")`（不带 `ROLE_` 前缀），就要写**完整的 `ROLE_ADMIN`** 字符串。

---

## 【三、PasswordUtil：BCrypt 加密的正确姿势】

```java
public class PasswordUtil {
    private static final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public static String encode(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    public static boolean matches(String rawPassword, String encodedPassword) {
        return encoder.matches(rawPassword, encodedPassword);
    }
}
```

**`BCryptPasswordEncoder` 的核心特性**：

1. **不可逆**：单向哈希，**没有解码函数**。
2. **自动加盐**：每次 `encode` 都生成随机 salt，**同一个密码两次加密结果不同**。
3. **慢**：默认 `strength = 10`，**约 100ms/次**。**这是特性不是 bug**——慢到攻击者暴力破解也累。

**为什么 `encoder` 是 `static final`？**

`BCryptPasswordEncoder` **是线程安全的**，可以单例。**实例化它不需要 Spring**——所以放在 `utils/` 包的静态类，**调用方无依赖**。

**面试考点 4**：BCrypt 同一密码两次加密结果不同，怎么验证？

答：**自带 salt**。加密结果字符串是：

```
$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy
\__/\/ \____________________/\________________________________/
  |  |          |                          |
  |  |          salt (22 chars)            hash (31 chars)
  |  cost factor
  algorithm
```

`encoder.matches(rawPassword, encodedPassword)` 内部会**自动从 encoded 里取 salt**，再用同一算法加密，最后比对。**对调用方完全透明**。

**面试考点 5**：BCrypt vs Argon2 vs SCrypt 怎么选？

- **BCrypt**：最成熟，**大部分框架默认支持**。`pai-smart` 选它合理。
- **Argon2**：2015 年密码哈希竞赛冠军，**抗 GPU/ASIC 攻击更强**。Spring Security 5.x 之后有 `Argon2PasswordEncoder`。
- **SCrypt**：内存硬，更抗定制硬件攻击，但**慢**。

**业务上 99% 的项目用 BCrypt 就够了**。金融、加密货币用 Argon2/SCrypt。

---

## 【四、登录接口：触发 AuthenticationManager】

我们看 `UserController` 的登录接口（不在第 02 集 AuthController 里，因为登录是用户自己的事）：

```java
@PostMapping("/login")
public ResponseEntity<?> login(@RequestBody LoginRequest request) {
    // 1. 通过 CustomUserDetailsService 加载用户
    UserDetails userDetails = userDetailsService.loadUserByUsername(request.username());

    // 2. 验证密码
    if (!PasswordUtil.matches(request.password(), userDetails.getPassword())) {
        throw new CustomException("密码错误");
    }

    // 3. 生成 token
    String token = jwtUtils.generateToken(request.username());
    String refreshToken = jwtUtils.generateRefreshToken(request.username());

    return ResponseEntity.ok(Map.of(
        "token", token,
        "refreshToken", refreshToken,
        "user", Map.of("username", request.username(), "role", userDetails.getAuthorities())
    ));
}
```

**这个流程就是**「**Spring Security 手动版**」**——不依赖 `UsernamePasswordAuthenticationFilter`，自己用 `AuthenticationManager` 的核心组件。

**注意**：代码里**没有用 `AuthenticationManager`**——而是直接调 `userDetailsService.loadUserByUsername` + `PasswordUtil.matches`。**这是简化写法**。完整写法是：

```java
AuthenticationManager authManager = ...;
Authentication auth = authManager.authenticate(
    new UsernamePasswordAuthenticationToken(request.username(), request.password())
);
```

**两种写法的对比**：
- 手动写法：代码短，**完全控制**。
- Manager 写法：可以接 `AuthenticationProvider` 链，**更灵活**（比如加图形验证码、IP 白名单）。

`pai-smart` 选手动写法——**业务简单，没必要上 AuthenticationManager**。这是 KISS 原则。

---

## 【五、注册接口：写库 + 自动登录】

```java
@PostMapping("/register")
public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
    // 1. 校验用户名唯一
    if (userRepository.findByUsername(request.username()).isPresent()) {
        throw new CustomException("用户名已存在");
    }

    // 2. 加密密码
    String hashedPassword = PasswordUtil.encode(request.password());

    // 3. 保存
    User user = new User();
    user.setUsername(request.username());
    user.setPassword(hashedPassword);
    user.setRole(User.Role.USER);  // 默认普通用户
    user.setOrgTags("DEFAULT");    // 默认组织标签
    user.setPrimaryOrg("DEFAULT");
    userRepository.save(user);

    // 4. 直接发 token（免登录）
    String token = jwtUtils.generateToken(user.getUsername());
    return ResponseEntity.ok(Map.of("token", token, "user", user));
}
```

**几个设计点**：

1. **默认角色是 USER**：不开放前端注册管理员。**管理员通过 `AdminUserInitializer` 启动时建**（看 `config/AdminUserInitializer.java`）。
2. **默认组织标签 `DEFAULT`**：第 11 集会讲，这是多租户体系的基础。
3. **注册即发 token**：**省一次登录**。用户体验更好——但也意味着**注册即用，邮箱验证得另说**。`pai-smart` 没做邮箱验证——**这是简化**，生产环境应该加。

**面试考点 6**：注册接口应该返回哪些字段？

- **必须**：`token`、`refreshToken`、`user`（不含密码）。
- **可选**：`permissions`（前端用来动态显示菜单）。
- **绝对不能**：返回 `password` 字段（即使是加密的）。

`pai-smart` 用了 `Map.of(...)` 而非返回 User 实体——**避免不小心返回 password 字段**。这种小心思值得学。

---

## 【六、登出接口：让 token 失效】

```java
@PostMapping("/logout")
public ResponseEntity<?> logout(@RequestHeader("Authorization") String authHeader) {
    String token = authHeader.substring(7);
    jwtUtils.invalidateToken(token);
    return ResponseEntity.ok(Map.of("message", "Logged out"));
}
```

**就调一次 `invalidateToken`**。第 03 集讲过，它做了两件事：
1. 把 `tokenId` 加黑名单。
2. 从 `userId -> Set<tokenId>` 索引里删除。

**注意**：黑名单有 TTL，**和原 token 过期时间一致**——过期后自动清，**不占 Redis 内存**。

---

## 【七、思考题：为什么 SecurityConfig 没有用 `@EnableGlobalMethodSecurity`？**

**`@EnableGlobalMethodSecurity`（6.x 改名 `@EnableMethodSecurity`）** 用来开启方法级权限控制：

```java
@EnableMethodSecurity
public class SecurityConfig { ... }

@PreAuthorize("hasRole('ADMIN')")
public void deleteUser(Long id) { ... }
```

`pai-smart` **没用**——所有权限控制都在 `SecurityConfig` 里用 `requestMatchers` 配。

**两种风格的对比**：
- **路径级**（`pai-smart`）：粒度粗，**所有逻辑都在配置里**。
- **方法级**：粒度细，**每个 Service/Controller 方法单独标注**。

`pai-smart` 选路径级，**因为业务简单**——能用 URL 区分的场景就没必要上方法级。

**什么时候上方法级？**
- 「同一个 URL 不同方法不同权限」（比如 `GET /users` 任何人能看，`POST /users` 管理员能建）。
- **Controller 里**有复杂的条件权限（「你是这个资源的 owner 才能改」）。

---

## 【八、常见坑 & 面试问答】

**Q1：为什么 `JwtAuthenticationFilter` 报错后没有返回 401？**

A：看代码 catch 里只是 `logger.error`，**然后 `filterChain.doFilter` 继续走**。这意味着：
- Token 解析失败 → SecurityContext 是空的 → 走到 `AuthorizationFilter` → 401。
- **filter 本身不负责 401**，**Spring Security 的 `AuthorizationFilter` 负责**。

**这种设计的好处**：filter 链各司其职，**每个 filter 只关心自己的一部分**。`ExceptionTranslationFilter` 会把 `AccessDeniedException` 翻译成 401/403。

**Q2：怎么自定义 401 返回格式？**

A：写一个 `AuthenticationEntryPoint`：

```java
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(401);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":401,\"message\":\"未登录\"}");
    }
}

// SecurityConfig 里：
http.exceptionHandling(ex -> ex.authenticationEntryPoint(jwtAuthenticationEntryPoint));
```

`pai-smart` 没做这个——返回的是 Spring Security 默认的 401 响应体（HTML 或空）。**生产环境建议改成 JSON**。

**Q3：登录接口是公开的，为什么不直接放 `permitAll`？**

A：`/api/v1/users/login` 确实在 `permitAll` 列表里。**Spring Security 不会在登录时要求已有 token**——**这是显然的**。但 `permitAll` 之外的所有路径都要 token，包括「改密码」之类的登录后操作。

**Q4：BCrypt 慢，会不会拖慢登录？**

A：单次 100ms，**登录场景可以接受**。但是**每次 HTTP 请求都要 `BCrypt.matches` 吗**？**不会**——`JwtAuthenticationFilter` 只验签，**不查密码**。密码只在登录时校验一次。

**Q5：怎么防「撞库攻击」？**

A：
- **加图形验证码**（注册、登录、忘记密码时）。
- **加 IP 限流**（`RateLimitService`，第 13 集讲）。
- **加密码强度校验**（前端 + 后端）。
- **加账号锁定**（连续失败 N 次锁定 M 分钟）。

`pai-smart` 在 `AdminUserInitializer` 里**有默认管理员账号密码**——生产部署**必须改**，否则**裸奔**。这点原作者也在 README 反复强调。

---

## 【九、`@Bean` 还是 `@Component`？`SecurityConfig` 的归类问题】

`SecurityConfig` 上有 `@Configuration`——**和 `@Component` 的区别**：
- `@Configuration` 是 `@Component` 的特化，**额外支持 `@Bean` 方法的 CGLIB 增强**（多次调用返回同一个 bean）。
- 普通 `@Component` 里的 `@Bean` 方法**不会**被增强，**每次调用都 new 一个**——**这会导致单例失效**。

`pai-smart` 的 `SecurityConfig`、`RedisConfig`、`KafkaConfig` 等都是 `@Configuration`。**这就是规矩**。

---

## 【十、思考题 & 面试延伸】

**Q**：如果让你重写 `pai-smart` 的登录流程，**你会怎么设计**？

**我的参考答案**：
- **登录前**：图形验证码 + IP 限流。
- **登录时**：用户名密码 + 设备指纹 → JWT（含设备 ID）。
- **登录后**：返回 access + refresh token。
- **主动登出**：失效单端 token。
- **被动登出**：改密码 → 失效所有端 token（用反向索引）。
- **异地登录**：检测 IP/地理位置变化 → 强制二次验证。

**这是进阶内容**，等我们讲到「充值 + 配额」（第 17 集）时，会一并聊。

---

## 【十一、下集预告】

前 4 集我们搭好了**用户体系 + 鉴权**。

但是 RAG 系统核心是**「文档」**——没有文档，AI 答什么？

第 05 集，我们要：

- 引入 **MinIO**——文件存储从「服务器本地」变成「对象存储」。
- 看 `MinioConfig` 怎么初始化 bucket。
- 看 `FileUpload` 实体怎么设计。
- 看 `UploadController` 怎么处理分片上传。

**MinIO 是 S3 协议的国产实现，部署简单，是 RAG/AI 项目的标配。**

我们下期见。

---

> 觉得这一期的内容对你有帮助，**点赞、投币、一键三连**。
> 想看哪一集的深度展开？评论区留言。

下一集链接：[第 05 集：MinIO 登场——文件存储从本地到对象存储](./05-minio-object-storage.md)
