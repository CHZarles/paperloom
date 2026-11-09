# GitHub 登录 · 实施计划 v2

> 状态：规划稿，待开工。v1 的评审意见见 §0。
> 放在 `tmp/` 是因为 CLAUDE.md 规定原始 agent 计划不进入 `docs/`；落地后行为进
> `docs/reference/configuration.md`，决策进 `docs/adr/0017-*.md`。

## 0. v1 评审意见处理

| # | 意见 | 处理 | 落在 |
| --- | --- | --- | --- |
| 1 | 与 AGENTS.md「public OAuth deferred」冲突，无 sign-off | 本切片明确是产品边界变更：同一提交内改 AGENTS.md 并写 ADR 0017；澄清 "public OAuth" 指第三方客户端接入，不是第一方登录 | §2 |
| 2 | 免邀请码建号 + 1000 万初始额度 = 可刷成本 | 每日全局建号上限 20；GitHub 建号初始额度与邀请用户分开配置，默认 50 万 | §4 |
| 3 | 总开关是单向门，停用即锁死这些用户 | 补管理员重置密码的最小路径（切片 0），并把单向语义写进文档 | §5 |
| 4 | state 只证明"是我们发的"，挡不住登录 CSRF | state 同时写 `HttpOnly; SameSite=Lax` cookie，回调比对 | §8.1 |
| 5 | 三个匿名端点无频控；出站调用阻塞请求线程 2×15s | 按 IP / 用户限流；出站超时默认压到 5s 并校验上限 | §12 |
| 6 | 任何 `error` 都报成"用户取消授权" | 按 `error` 值分流：`access_denied` → `denied`，其余 → `misconfigured` 并 ERROR 日志 | §9 |
| 7 | `isAvailable()` 不看三处 URL | 保留 UI 判定语义，新增启动期配置校验；prod 下 URL 仍是 localhost 直接启动失败 | §10.2 |
| 8 | `CLOSED` 拦截与决策相反，Javadoc 也写反了 | 删除拦截分支与 `closed` 错误码，同步改 Javadoc | §18.1 |
| 9 | 用异常 message 做控制流 | `CustomException` 增加可选 `code`；错误码走枚举 + 穷举 switch | §18.2 |
| 10 | 先截 50 再拼 `_2`，上限失效 | 先算后缀再截断 base，保证总长 ≤ 50 | §18.3 |
| 11 | 并发绑定撞唯一约束变 500 | 捕获 `DataIntegrityViolationException` 转 `already_linked` / 重查 | §18.4 |
| 12 | `bind-url` 丢弃 `redirect`；绕过 `BearerToken` | `redirect` 进 state 并在绑定后回跳；改用 `Authentication` 参数 | §18.5 |
| 小 | `read:user` 过度授权 | 授权请求不带任何 scope，只读公开资料 | §8.2 |
| 小 | `Math.max(30, ttl)` 静默改配置 | 启动期校验 TTL ≥ 30，非法直接失败 | §10.2 |
| P3-1 | 缺验收标准与提交切片 | §17 给 5 个切片，每片写完成状态 | §17 |
| P3-2 | 测试漏关键用例 | 补：未配置 404、redirect 绕过、CLOSED 回归、限流 429 | §14 |
| P3-3 | 文档漏 code-map 与 domain-language | 两处都进文档清单 | §15 |
| P3-4 | 无可观测性计划 | 每个分支的 operation / result 取值列表 | §13 |
| P3-5 | 迁移只写 ddl-auto | 启动后 `SHOW CREATE TABLE` 验证；约束缺失时的 `ALTER TABLE` 兜底 | §16 |
| P3-6 | 混入无关改动 | 切片提交排除 `global-header/index.vue` 与 `tmp/` | §17 |

## 1. 目标与边界

目标：登录页提供「使用 GitHub 登录」。首次登录自动建号、不要邀请码；再次登录直接进站；已有本地账号可主动绑定 GitHub。

非目标：其他第三方登录；解绑、多身份合并、邮箱找回；改动 MCP / OAuth2 授权服务器；改动 `APP_AUTH_REGISTRATION_MODE` 对账号密码注册的语义。

## 2. 产品边界与授权记录（P0-1）

GitHub 登录会落在 AGENTS.md 的 "public OAuth ... remain deferred" 字面上。本切片按"边界变更"处理，不是"边界内实现"：

1. 同一提交更新 `AGENTS.md` 的 Product Direction 段，建议措辞：

   > Field Extraction, public OAuth **client registration**, citation graphs, multimodal retrieval, and
   > any web-browsing exploration agent remain deferred. GitHub sign-in for the first-party
   > application is supported (ADR 0017); it is an inbound identity provider for Citewise, not
   > third-party OAuth client access.

2. `docs/adr/0017-use-github-oauth-for-sign-in.md` 的 Decision 段记录：批准人、日期、两条产品决定（免邀请码自动建号；`CLOSED` 不拦截 GitHub 建号）、以及"第一方登录 ≠ public OAuth"的判读。这是唯一的 sign-off 落点，`§19 风险` 里不再写"产品已确认"这种无处可查的说法。

3. `docs/reference/product-requirements.md` 的 "Java must remain authoritative for" 列表补一条：GitHub 身份关联与账号绑定。

## 3. 决策记录

| 决策 | 选择 | 理由 | 被否掉的方案 |
| --- | --- | --- | --- |
| 集成方式 | 手写授权码流程（WebClient + Redis） | 现有链路 STATELESS + 自签 JWT；已有 `OutboundWebClientFactory` 处理代理 | oauth2-client starter：默认依赖 Session 与第二条 filter chain |
| 身份键 | `github_id`（唯一） | `login` 可改名、可抢注；邮箱可隐藏 | 用 `login` 当键 |
| 账号关联 | 只按 `github_id`，不按邮箱合并 | 自动合并 = 账号接管路径 | 已验证邮箱自动合并 |
| 建号策略 | 未关联一律建号，不看注册模式 | 产品要求免邀请码 | 跟随注册模式 |
| CSRF | state + `HttpOnly` cookie 双绑定 | state 单独存 Redis 只证明来源，不证明同一浏览器 | 只用 state |
| JWT 交付 | 一次性票据（120s、单次） | JWT 不进 URL | 回跳地址直接带 JWT |
| 出站调用 | 请求线程内同步，超时 5s + 限流 | 与现有 `MinerUParserClient` 一致 | 引入队列异步化：本切片不值得 |

## 4. 成本与滥用控制（P0-2）

前提：新用户首次使用额度时按 `resolveInitToken` 发放 1000 万 LLM + 1000 万 embedding，记账事由「注册赠送」；管理员可通过已有的 `POST /api/v1/admin/users/{userId}/tokens/add` 补发。

因此 GitHub 建号必须自带两道闸，而不是只靠"有没有 GitHub 账号"：

| 控制 | 配置 | 默认 | 行为 |
| --- | --- | --- | --- |
| 每日建号上限 | `APP_AUTH_GITHUB_DAILY_SIGNUP_LIMIT` | `20` | Redis 日计数；超出 → `github_error=signup_limited`，不建号 |
| GitHub 建号初始额度 | `APP_AUTH_GITHUB_INIT_LLM_TOKENS` / `..._EMBEDDING_TOKENS` | `500000` | `-1` 表示沿用普通初始额度；`0` 表示不发 |

实现点：`UserTokenService.resolveInitToken` 增加一条分支——用户 `githubId != null` 且配置 ≥ 0 时用 GitHub 额度，否则沿用现有逻辑。默认值意味着单日最坏新增 2000 万 token 额度，比现在的 2 亿降两个数量级。

否掉的方案：
- 人工审核：与"自动注册"的产品要求直接冲突。
- 卡 GitHub 账号年龄（`/user.created_at`）：低成本绕过，且 GHE 行为不一致。
- 首额度固定为 0：本地体验与自建部署都会被卡死，管理员补发链路也没人愿意走。

## 5. 停用开关的语义（P0-3）

`APP_AUTH_GITHUB_ENABLED=false` 的真实后果，必须显式记录：

- 新 GitHub 用户无法再注册；已建号用户当前 JWT（1 小时）与 refreshToken（7 天）到期后无法再登录——这些账号的密码是随机 UUID 的 BCrypt 串，账号密码登录走不通。
- 因此这是一个**单向选择**：关掉之前要么接受这些账号冻结，要么先给它们设密码。

补一条最小路径（切片 0）：

- `POST /api/v1/admin/users/{userId}/password`，管理员给指定用户重设密码；复用现有密码复杂度校验，成功后调用 `jwtUtils.invalidateAllUserTokens(userId)` 使旧会话失效。
- 该接口同时解决"任何用户忘记密码都无法恢复"这个既有缺口，不是纯为 GitHub 登录引入。

## 6. 数据模型

`users` 表加两列（`ddl-auto: update`）：

| 列 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `github_id` | BIGINT | UNIQUE，可空 | 身份键 |
| `github_login` | VARCHAR(100) | 可空 | 展示用 |

GitHub 建号：`username` 取 GitHub login（冲突加 `_2`、`_3`，**总长 ≤ 50**）；`password` 为随机 UUID 的 BCrypt 串；`role = USER`。

## 7. 接口

| 方法 | 路径 | 认证 | 作用 |
| --- | --- | --- | --- |
| GET | `/api/v1/auth/providers` | 公开 | `{"github":{"enabled":bool}}` |
| GET | `/api/v1/auth/github/authorize?redirect=/chat` | 公开 | 写 state + cookie，302 到 GitHub |
| GET | `/api/v1/auth/github/callback?code&state` | 公开 | 校验 cookie、state，换码，解析账号，302 回前端 |
| POST | `/api/v1/auth/github/exchange` | 公开（凭票据） | 票据换 `{token, refreshToken}` |
| GET | `/api/v1/auth/github/bind-url?redirect=/personal-center` | USER / ADMIN | 返回绑定用授权地址 |

Redis 键：

| 键 | 值 | TTL | 消费 |
| --- | --- | --- | --- |
| `oauth:github:state:<state>` | `{purpose, userId, redirect}` | 600s | `getAndDelete` |
| `oauth:github:ticket:<uuid>` | `{token, refreshToken}` | 120s | `getAndDelete` |
| `oauth:github:rl:*` | 计数 | 60s | `INCR` + `EXPIRE` |
| `oauth:github:signup:<yyyy-MM-dd>` | 计数 | 48h | `INCR` + `EXPIRE` |

## 8. 流程

### 8.1 登录（含 cookie 绑定）

```
/login/pwd-login
  └─ GET /api/v1/auth/github/authorize?redirect=/chat
       ├─ state = 32 字节随机值
       ├─ Redis: oauth:github:state:<state> = {LOGIN, null, /chat}，TTL 600s
       ├─ Set-Cookie: pl_gh_state=<sha256(state)>; HttpOnly; SameSite=Lax; Path=/; Max-Age=600[; Secure]
       └─ 302 github.com/login/oauth/authorize?client_id&redirect_uri&state   （不带 scope）
            └─ 用户授权 → 302 /api/v1/auth/github/callback?code&state
                 ├─ cookie 缺失 → github_error=state（不消费 Redis）
                 ├─ sha256(state) ≠ cookie 值（常量时间比较）→ github_error=state，并删除该 state
                 ├─ Redis getAndDelete 取 payload；缺失 → github_error=state
                 ├─ 清除 cookie
                 ├─ code → access_token（POST api.github.com/login/oauth/access_token）
                 ├─ access_token → GET /user（id, login）
                 ├─ 解析账号（§8.3）
                 ├─ 签发 JWT + refreshToken，写票据
                 └─ 302 /login/pwd-login?github_ticket=xxx&redirect=/chat
                      └─ 前端 POST /exchange {ticket}
                           └─ 写 localStorage，走现有 completeLogin
```

cookie 用 `Path=/` 是刻意取舍：开发环境走 Vite 代理（`/proxy-default/...`），后端无法预知代理前缀，窄路径在代理下不会回传。

**cookie 的主机必须与 `callback-url` 的主机一致。** cookie 是在 `/authorize` 那个主机名上种下的，回跳却落在 `callback-url`。本地用 `127.0.0.1` 打开前端、而 `callback-url` 写的是 `localhost`（或反过来），或者用隧道域名打开，每次回调都会因为拿不到 cookie 而报 `state`。这类失败在用户侧和真正的 state 过期长得一样，所以：

- `authorize` 的入口地址与 `callback-url` 必须同主机，写进 `quick-start.md` 与启动期 WARN（`authorize` 由前端拼当前 origin 时无法校验，退化为文档约束）；
- `state` 错误的提示文案要写成"登录状态校验失败，请重新点击 GitHub 登录"，不要写"state 已过期"。

### 8.2 绑定时序

```
个人中心 General
  └─ GET /api/v1/auth/github/bind-url?redirect=/personal-center   （带 Authorization）
       ├─ 从 Authentication 取当前用户名 → User
       ├─ state payload = {BIND, userId, /personal-center}
       ├─ 同一个 cookie 机制
       └─ 返回 {url}
            └─ 浏览器跳 GitHub → /callback → 写 github_id / github_login
                 └─ 302 /personal-center?github=bound
```

绑定目标只来自服务端 state，不接受前端传 `userId`。

### 8.3 账号解析

1. `github_id` 命中 → 登录，顺带刷新 `github_login`。
2. 未命中 → 检查每日建号上限 → 建号（不看注册模式）。
3. 用户名冲突 → 建新号加后缀，**不复用**同名本地账号。
4. `save` 撞 `DataIntegrityViolationException` → 重查 `github_id`；查到则按命中处理，否则报 `provider`。

### 8.4 授权请求的 scope

不带 scope。GitHub 的 `/user` 对公开资料不需要任何 scope，应用只读 `id` 与 `login`；授权页会显示"仅公开资料"。

## 9. 错误码

失败一律 302 回前端并带 `github_error=<code>`，前端提示后清理 URL 参数。

| code | 触发 | 日志级别 |
| --- | --- | --- |
| `state` | cookie 缺失 / state 不匹配 / 过期 / 已用 | WARN |
| `denied` | GitHub 回传 `error=access_denied` | INFO |
| `misconfigured` | GitHub 回传其它 `error`（`redirect_uri_mismatch`、`application_suspended` 等） | ERROR（带原始 error 值） |
| `provider` | 换 token 或读用户资料失败 | WARN |
| `already_linked` | 该 GitHub 身份已绑其他账号，或该账号已绑其他 GitHub | INFO |
| `signup_limited` | 当日建号上限已满 | WARN |

未配置（`isAvailable()=false`）时：`/providers` 返回 `enabled:false`；`authorize` / `callback` / `exchange` / `bind-url` 全部 404，不乐观降级。

## 10. 配置

### 10.1 变量

```bash
APP_AUTH_GITHUB_ENABLED=false
APP_AUTH_GITHUB_CLIENT_ID=
APP_AUTH_GITHUB_CLIENT_SECRET=
APP_AUTH_GITHUB_CALLBACK_URL=http://localhost:8081/api/v1/auth/github/callback
APP_AUTH_GITHUB_LOGIN_REDIRECT_URL=http://localhost:9527/login/pwd-login
APP_AUTH_GITHUB_BIND_REDIRECT_URL=http://localhost:9527/personal-center
APP_AUTH_GITHUB_WEB_BASE_URL=https://github.com
APP_AUTH_GITHUB_API_BASE_URL=https://api.github.com
APP_AUTH_GITHUB_STATE_TTL_SECONDS=600
APP_AUTH_GITHUB_TICKET_TTL_SECONDS=120
APP_AUTH_GITHUB_HTTP_TIMEOUT_SECONDS=5
APP_AUTH_GITHUB_DAILY_SIGNUP_LIMIT=20
APP_AUTH_GITHUB_INIT_LLM_TOKENS=500000
APP_AUTH_GITHUB_INIT_EMBEDDING_TOKENS=500000
# 仅本地开发：允许上面三个地址指向 loopback，默认 false
APP_AUTH_GITHUB_ALLOW_LOOPBACK_URLS=false
```

### 10.2 启动期校验（bean `@PostConstruct`，P1-7）

放在 `@PostConstruct` 而不是 `ApplicationReadyEvent`：后者触发时端口已经开了，请求可能先于校验被受理。

| 检查 | 不满足时 |
| --- | --- |
| `enabled=true` 但凭据缺失 | WARN：入口不显示 |
| state TTL < 30 或 ticket TTL < 30 | 启动失败 |
| `http-timeout-seconds` 不在 2..10 | 启动失败 |
| 可用且任一 URL 不是 http/https 绝对地址 | 启动失败 |
| 可用且任一 URL 指向 loopback（`localhost` / `127.0.0.1` / `[::1]`），且未显式放行 | 启动失败 |

不再用 `profile 含 prod` 做闸门：启动脚本是裸跑 `java -jar`，全仓只有一份部署文档设 `prod`，按 profile 判断等于线上永远不校验。改成按 URL 本身判断，任何环境一视同仁。

本地开发用显式开关放行：`APP_AUTH_GITHUB_ALLOW_LOOPBACK_URLS=true`（默认 false，只在文档里写成"仅本地"),否则本地根本起不来，切片 2 的浏览器验收也无从谈起。

## 11. 前端

| 文件 | 改动 |
| --- | --- |
| `service/api/auth.ts` | `fetchAuthProviders`、`fetchGithubExchange`、`fetchGithubBindUrl` |
| `store/modules/auth/index.ts` | `loginWithGithubTicket(ticket)`，复用 `completeLogin` |
| `views/_builtin/login/index.vue` | 挂载读 `github_ticket` / `github_error`，处理后 `router.replace` 清参数 |
| `views/_builtin/login/modules/pwd-login.vue` | GitHub 按钮（`enabled` 才渲染，内联 SVG，不依赖图标 CDN） |
| `views/personal-center/modules/settings-panel.vue` | General 区一行：已绑定显示 `@login`，未绑定显示绑定按钮；处理 `?github=bound` |
| `typings/api.d.ts` | `UserInfo.githubLogin`、`Auth.AuthProviders` |
| `locales/langs/{zh-cn,en-us}.ts` | 按钮、6 个错误码、绑定成功文案 |

路由表不动：回调落在已有 `/login/pwd-login`。

## 12. 安全与资源保护（P1-5）

| 项 | 做法 |
| --- | --- |
| 登录 CSRF | state 与 `HttpOnly; SameSite=Lax` cookie 双绑定（§8.1） |
| 重放 | state 与票据都是 `getAndDelete` 单次消费 |
| 开放重定向 | `redirect` 只接受 `/` 开头且不以 `//`、`/\` 开头的路径；不满足直接丢弃 |
| 凭据泄漏 | access token / client secret 不落库、不进日志、不进响应 |
| Redis 放大 | `authorize` 限 20 次/分钟/IP；`callback` 限 20 次/分钟/IP；`bind-url` 限 10 次/分钟/用户；`/authorize` 每 IP 并发未完成 state 上限 5 |
| 线程耗尽 | 出站超时 5s（上限 10）；限流与超时同时生效 |
| 客户端 IP | 依赖 `server.forward-headers-strategy=framework`；未配置时按代理 IP 计数，等价于全局限流——部署文档必须写明 |

限流实现沿用 `ArxivDiscoveryService.enforceImportBudget` 的写法（Redis 计数，Redis 异常时放行并 WARN）。限流放行是可用性取舍；state 写入失败仍然 fail-closed。

出站调用仍在请求线程内同步执行。异步化需要引入任务队列与回调查询接口，超出本切片；有超时上限和限流兜底。

## 13. 可观测性（P3-4）

统一走 `LogUtils.logUserOperation(userId, operation, resource, result)`，取值固定，便于检索：

| operation | result 取值 |
| --- | --- |
| `GITHUB_AUTHORIZE` | `SUCCESS` / `DISABLED` / `RATE_LIMITED` / `STATE_STORE_FAILED` |
| `GITHUB_CALLBACK` | `LOGIN_SUCCESS` / `BIND_SUCCESS` / `STATE_INVALID` / `DENIED` / `MISCONFIGURED` / `PROVIDER_FAILED` / `ALREADY_LINKED` / `SIGNUP_LIMITED` |
| `GITHUB_EXCHANGE` | `SUCCESS` / `INVALID_TICKET` / `DISABLED` |
| `GITHUB_BIND_URL` | `SUCCESS` / `DISABLED` / `UNAUTHENTICATED` |
| `GITHUB_PROVISION` | `CREATED` / `LIMITED` （resource 记 `githubLogin`，不记 client secret） |

不记录：access token、JWT、state 原值、票据原值、client secret。失败分支必须同时记 `LogUtils.logBusinessError`，否则无法区分攻击与配置错误。

## 14. 测试矩阵（P3-2）

| 用例 | 层 | 断言 |
| --- | --- | --- |
| 已关联账号登录 | `UserServiceTest` | 返回既有用户，刷新 `github_login` |
| 未关联 + `INVITE_ONLY` 建号 | `UserServiceTest` | 建号成功（回归：`CLOSED` 语义反转） |
| 未关联 + `CLOSED` 建号 | `UserServiceTest` | 仍然建号 |
| 用户名冲突 | `UserServiceTest` | 生成 `_2` 后缀，总长 ≤ 50 |
| 当天建号超限 | `GithubOAuthServiceTest` | 不建号，返回 `signup_limited` |
| 绑定冲突 | `UserServiceTest` | `already_linked`（409），并发时也是 409 不是 500 |
| state 缺失 / 不匹配 / 过期 | `GithubOAuthServiceTest` | 三种都返回 `state` |
| cookie 缺失 | `GithubAuthControllerTest` | 不消费 Redis，返回 `state` |
| ticket 二次使用 | `GithubOAuthServiceTest` | 第二次 400 |
| `redirect` 绕过 | `GithubOAuthServiceTest` | `//evil.com`、`/\evil.com`、`https://evil.com`、含控制字符 → 丢弃 |
| `error=access_denied` | `GithubOAuthServiceTest` | `denied` |
| `error=redirect_uri_mismatch` | `GithubOAuthServiceTest` | `misconfigured` |
| 未配置 + 已登录访问四个端点 | `GithubAuthSecurityTest` | 全部 404 |
| 未配置 + 匿名访问 `bind-url` | `GithubAuthSecurityTest` | 403（先被安全链拦，不是 404） |
| 配置不全（只有 enabled） | `GithubAuthSecurityTest` | `/providers` 仍 `enabled:false` |
| 匿名访问 `bind-url` | `GithubAuthSecurityTest` | 403（`Http403ForbiddenEntryPoint`，不是 401） |
| GUEST 访问 `bind-url` | `GithubAuthSecurityTest` | 403 |
| 限流 | `GithubAuthSecurityTest` | 第 N+1 次 429 |
| TTL / 超时非法 | 配置校验单测 | 启动失败 |

`GithubAuthSecurityTest` 照 `ArxivDiscoverySecurityTest` 的 `@WebMvcTest` + `@Import(SecurityConfig, JwtAuthenticationFilter, PaperAccessAuthorizationFilter, ...)` 写。

## 15. 文档（P3-3）

| 文件 | 内容 |
| --- | --- |
| `AGENTS.md` | Product Direction 段（§2） |
| `docs/adr/0017-use-github-oauth-for-sign-in.md` | 决策、边界判读、sign-off、后果（含单向门） |
| `docs/reference/configuration.md` | 认证表新增 14 个变量 + 关闭后的账号影响 |
| `docs/reference/product-requirements.md` | Java 职责补 GitHub 身份与绑定 |
| `docs/reference/domain-language.md` | `GitHub Identity`、`GitHub Account Binding`、`Provisioned Account` |
| `docs/architecture/code-map.md` | 目录计数更新；"按问题找代码"补一行 GitHub 登录 |
| `docs/guides/quick-start.md` | 本地配置 + 创建 OAuth App 步骤 |
| `.env.example` | 变量与注释 |

## 16. 迁移与部署验证（P3-5）

1. 启动后执行 `SHOW CREATE TABLE users;`，确认 `github_id` 上有 `UNIQUE` 约束。Hibernate `update` 对既有表补约束并不可靠，缺失时手工执行：
   `ALTER TABLE users ADD CONSTRAINT uk_users_github_id UNIQUE (github_id);`
2. 回滚预案：关闭 `APP_AUTH_GITHUB_ENABLED` 只停用入口，不删列；两列可空，旧版本代码不受影响。
3. 关闭功能后残留的 `github_id` 数据保留，供后续重新启用或审计。

## 17. 提交切片与验收（P3-1）

| 切片 | 内容 | 完成状态 |
| --- | --- | --- |
| 0 | 管理员重置密码接口 + 单测 | 端点可用；被 GitHub 建号锁定的账号能恢复 |
| 1 | 后端 GitHub 登录（端点、策略、限流、配置校验）+ 全部后端单测 | `mvn test` 全绿；未配置时四端点 404；`SHOW CREATE TABLE` 验证通过 |
| 2 | 前端登录入口与票据兑换 | 浏览器完整走通真实 GitHub 登录 |
| 3 | 绑定（后端 `bind-url` + 前端入口） | 浏览器走通绑定、冲突提示、回跳 |
| 4 | DOCS：AGENTS.md、ADR 0017、6 份文档、`.env.example` | 文档与代码同一 PR；AGENTS.md 与 ADR 同提交 |

每片一个提交；提交前排除 `frontend/src/layouts/modules/global-header/index.vue`（既有无关改动）与 `tmp/`。

## 18. 代码现状与待修（P2）

已写、未提交、未跑测试：`User`、`UserRepository`、`GithubOAuthProperties`、`GithubApiClient`、`GithubOAuthService`、`GithubAuthController`、`AuthController#providers`、`SecurityConfig`、`UserService`、`UserController`、`application.yml`、`frontend/src/typings/api.d.ts`。

进入切片 1 前必须改：

| # | 位置 | 改动 |
| --- | --- | --- |
| 18.1 | `UserService.resolveGithubLogin` | 删除 `CLOSED` 拦截与 `closed` 错误码；Javadoc 改成"不看注册模式，仅受每日建号上限约束" |
| 18.2 | `GithubOAuthService.errorCode` | `CustomException` 增加可选 `code` 字段（保留现有构造器）；错误码用枚举常量，`switch` 穷举 |
| 18.3 | `UserService.availableGithubUsername` | 先算后缀长度再截 base，保证总长 ≤ 50 |
| 18.4 | `UserService.linkGithubAccount` / `resolveGithubLogin` | 捕获 `DataIntegrityViolationException`，绑定转 409，建号转重查 |
| 18.5 | `GithubAuthController#bindUrl` | 接收 `redirect` 并进 state；用 `Authentication` 参数取当前用户，删掉手工 `replace("Bearer ", "")` |
| 18.6 | `GithubOAuthService` | 去掉 `scope`；`Math.max(30, ttl)` 换成启动期校验；`error` 参数按值分流（§9） |
| 18.7 | 全部 | 补 cookie 绑定、限流、建号上限、`LogUtils` 分支记录 |

## 19. 风险

| 风险 | 影响 | 处理 |
| --- | --- | --- |
| 关闭开关后 GitHub 建号用户被冻结 | 只能靠管理员重置密码 | 切片 0 + §5 文档 |
| 建号成本 | 最多 20 个/天 × 50 万 token | §4 两道闸，按需再降 |
| `CLOSED` 不再兜底 | 运维误判 | §2 ADR + configuration.md 显式写明 |
| 代理未配置转发头 | 限流退化为全局限流 | 部署文档写明 `server.forward-headers-strategy` |
| GitHub 用户名冲突 | 站内用户名与 GitHub 名不一致 | 前端展示 `github_login` |
| Hibernate 未建唯一约束 | 并发重复建号 | §16 手工校验 + `DataIntegrityViolationException` 兜底 |

## 20. 修订记录

- v1：初版规划。
- v2：按评审意见修订——补产品边界与 sign-off（§2）、成本闸（§4）、停用语义与管理员重置（§5）、cookie 绑定 CSRF（§8.1）、限流与超时（§12）、错误码分流（§9）、启动期配置校验（§10.2）、可观测性（§13）、测试与文档补齐（§14、§15）、提交切片与验收（§17）、代码待修清单（§18）。
- v3（实现完成，2026-11-04）：按 v2 落地切片 0–4。修正 4 处、实施记录见下。未做（用户明确跳过）：额度参数归属、限流 302/429 统一、`/exchange` 限流、`Secure` 标志、多标签页、切片重叠、ADR 签字。

## 21. 实现落点

| 切片 | 落地文件 |
| --- | --- |
| 0 | `AdminController#resetUserPassword`（`POST /admin/users/{userId}/password`）+ `UserService#resetPassword` |
| 1 | `GithubOAuthProperties`、`GithubOAuthConfigurationCheck`（`@PostConstruct`）、`GithubAuthRateLimiter`、`GithubOAuthService`、`GithubApiClient`、`GithubAuthController`、`AuthController#providers`、`UserService`、`UserTokenService`、`User`、`UserRepository`、`SecurityConfig`、`application.yml` |
| 2 | `service/api/auth.ts`、`store/modules/auth`、`views/_builtin/login/index.vue`、`modules/pwd-login.vue`、i18n zh/en、`typings/app.d.ts` |
| 3 | `views/personal-center/modules/settings-panel.vue` |
| 4 | `docs/adr/0017`、`configuration.md`、`product-requirements.md`、`domain-language.md`、`code-map.md`、`quick-start.md`、`.env.example`、`AGENTS.md` |

测试：`UserServiceGithubLoginTest`(10)、`GithubOAuthServiceTest`(13)、`GithubAuthSecurityTest`(6)、`GithubOAuthConfigurationCheckTest`(7)；`mvn test` 510 全绿。

## 22. 评审第二轮修复（2026-11-04）

| 问题 | 处理 |
| --- | --- |
| 并发兜底是死代码 | `resolveGithubLogin` / `linkGithubAccount` 去掉 `@Transactional` + 改 `saveAndFlush`：唯一约束在 try 内触发，`DataIntegrityViolationException` 才抓得到（保留事务时抛在提交阶段，catch 永不执行） |
| 回归测试 | 新增 `UserServiceGithubConcurrencyTest`（H2 真库、双线程同 githubId）；把 `@Transactional` 加回去该测试立即报错，已实测 |
| `server.forward-headers-strategy` 只在文档里 | `application.yml` 加 `forward-headers-strategy: ${SERVER_FORWARD_HEADERS_STRATEGY:native}`，`.env.example` 暴露同名变量 |
| 注释与代码相反 | `resolveGithubLogin` javadoc 改成"不受 REGISTRATION_MODE 约束，总开关是 APP_AUTH_GITHUB_ENABLED" |
| AdminController 行尾被重写 | 从 HEAD 原始字节重建，只插入新端点与 record；diff 从 631 行降到 31 行 |
| `/authorize` 限流返回纯文本 429 | 改为 302 回登录页并带 `github_error=rate_limited`，前端补文案 |
| `/callback` 限流仍是纯文本 429 | 同样改 302 回登录页；两处共用 `loginWithError` + `redirectTo`，各配一条 MockMvc 断言 |
| `/exchange` 未限流 | `GithubAuthRateLimiter#allowExchange`（30 次/分钟/IP），`exchangeTicket` 收 client ip |
