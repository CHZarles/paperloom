# PaperLoom Handoff

更新日期：2026-08-13

## 当前状态

- 分支：`main`
- 本地、GitHub、线上代码版本：`ffa2cca feat(auth): clarify guest login progress`
- 本地工作区有本交接文档和 Retry/Cancel 工程记录待提交。
- 线上目录：`/root/charles/paperloom`
- 公网入口：`https://paperloom.me`
- 线上 Backend、4 个 Redis Worker、Cloudflare Tunnel 均为 `active`。
- 服务器原有未跟踪文件不得删除：

  ```text
  .env.before-redis-20260812-130142
  frontend/dist.previous-34f40ec/
  frontend/dist.previous-df7b273/
  ```

## 线上架构

```text
Browser
  -> Cloudflare
  -> cloudflared HTTP/2 over TCP
  -> Nginx 127.0.0.1:18880
  -> Spring Boot 127.0.0.1:18082
  -> Redis Streams
  -> 4 Python Research Harness Workers, each max_concurrent_runs=1
  -> MiniMax
```

- 代码默认使用 HTTP；生产环境通过 `.env` 覆盖为 `RESEARCH_HARNESS_TRANSPORT=redis`。
- 4 个 Worker 共提供 4 个模型执行并发；不要把 Java 监控线程数当成模型执行并发。
- HTTP Harness 已禁用但保留为回滚入口。
- Redis Job 完成后执行 `XACK + XDEL`，正常空闲时应看到 `XLEN=0`、`XPENDING=0`。

## 本轮完成

### 主要业务 Runtime 图

- 用 CodeGraph 逐条核对 Controller、Service、Redis/Kafka/Worker、MySQL、MinIO 与 Qdrant 的真实调用后，
  在 `README.md` 和 `README.zh-CN.md` 补齐 16 张可点击的 Runtime 图。
- 覆盖注册/邀请码、登录/Token 刷新/登出、游客登录、Token 追加与结算、会话与范围锁定、论文集合、
  PDF 分片上传/解析/索引、论文重试/发布/重建/删除、Research Chat、Agent Loop、Cancel、Retry、
  断线状态恢复、PDF Range 预览、历史引用重开、Admin 用户/对话/用量审计。
- 图源位于 `site/diagrams/runtime-*.mmd`，PNG/SVG 位于 `site/public/images/runtime-*`；执行
  `cd site && npm run diagrams:render` 可复现。通用 Evidence Flow 只在显式 `--all` 时重绘，避免
  Runtime 文档变更顺手改写无关二进制文件。

### Redis Worker 与恢复

- `4706fc9`：完成任务从 Redis Stream 删除。
- `99fb62f`、`85f065b`：避免健康长任务被其他 Worker 误回收；使用可续租 Generation Lock 判断存活。
- `7e33368`：线上切换 Redis Streams；4 Worker，每 Worker 并发 1。

### Retry 与 Cancel

- `ff0816b`：Cancel 在产生副作用前按 `generationId + userId` 校验归属，只允许取消 `STREAMING`；
  历史 assistant 消息返回 `status=finished`，刷新后仍显示 Retry。
- `cf9abfe`：Redis Generation Snapshot 过期后，Retry 按 `generationId + userId` 从 MySQL 恢复上下文。
- `fe6516a`：历史 Retry 在前端替换原 Answer Slot，而不是错误追加到末尾。
- Retry 不覆盖历史：新增 Conversation Revision，复用 `answerSlotId`，旧版本设
  `currentRevision=false`，新版本设 `true`。
- 详细设计、测试证据和面试表达见：
  [`docs/engineering-evolution/agent-runtime/chat-retry-cancel-hardening-2026-08-12.md`](docs/engineering-evolution/agent-runtime/chat-retry-cancel-hardening-2026-08-12.md)。

### 游客与权限

- 后端 `admin/*` 已强制 Admin RBAC；前端菜单隐藏不再被当作权限控制。
- Guest 角色由后端产生并返回，游客不能上传论文。
- 同一浏览器携带有效的 `paperloom_guest_session` Cookie 时复用游客身份，避免每次创建新用户；
  限流仍是防刷的独立边界。
- `ffa2cca`：游客登录按钮使用独立加载状态、旋转图标和轻量进度动画；登录期间禁用其他入口，避免重复提交。

### PDF 与前端性能

- Chat 页没有改成图片预览：图片可省 PDF 解析，但会损失文字层、缩放精度或框选坐标一致性，当前收益不足。
- 已完成 PDF 标准 Range 请求与代理层修复，详见：
  [`docs/performance/pdf-preview-standard-range-optimization-2026-08-11.md`](docs/performance/pdf-preview-standard-range-optimization-2026-08-11.md)。
- 已记录定位结论：主要延迟不只来自 PDF 解析，网络、首屏 JS 和内容请求同样显著；不能用“换图片必快”替代测量。

## 网络事件

2026-08-12 观察到多次公网超时，但 Backend 没有 OOM、5xx 或自动重启。Cloudflare Tunnel 日志显示
QUIC/UDP 连接反复断开，并多次出现 `timeout: no recent network activity`。

已修改服务器：

```text
/etc/systemd/system/cloudflared.service
ExecStart=/usr/bin/cloudflared --no-autoupdate tunnel --protocol http2 run --token-file /etc/cloudflared/token
```

备份：

```text
/etc/systemd/system/cloudflared.service.before-http2-20260812
```

切换后 4 条 Tunnel 连接均为 `protocol=http2`。Cloudflare 自检结果是 UDP region2 失败、TCP region1/2
均通过，并建议使用 HTTP/2。当前未再发现新的 HTTP/2 Tunnel 超时，需继续观察。

公网测速仅代表当时样本：

```text
首页连续请求：全部 200，典型 0.7-2.7s
主包压缩后约 475KB：约 125-237KB/s，2.0-3.8s
服务器本地 Nginx：首页约 0.5ms
Cloudflare 接入点：LAX
```

结论：服务器应用响应快，公网链路仍偏慢；切 HTTP/2 主要改善稳定性，不保证提速。

## 验证记录

- `mvn -q -Dtest=ConversationServiceTest,ChatHandlerProductHarnessTest,ChatHandlerStopResponseTest test`：通过。
- 前端 `vue-tsc --noEmit --skipLibCheck`：通过。
- 前端生产构建与 Bundle 预算：通过；Login `438.1/500KB`，Chat shell `511.4/520KB`，Knowledge Base `521.8/700KB`。
- 线上 `frontend=200`、未登录 `/api/v1/users/me=403`。
- 浏览器控制当时不可用，因此游客动画只完成类型检查、构建和线上 HTTP 验证，未完成截图验收。

## 部署方式

普通发布遵循 [`docs/guides/operations.md`](docs/guides/operations.md)：

```bash
ssh wuyun
cd /root/charles/paperloom
git status --short
git pull --ff-only
```

仅前端改动：

```bash
corepack pnpm --dir frontend install --frozen-lockfile
corepack pnpm --dir frontend build
```

Nginx 直接读取 `frontend/dist`，不需要重启 Backend、Worker 或 Nginx。

Java 改动：

```bash
mvn -DskipTests package
systemctl restart paperloom-backend.service
```

单 Backend 重启约有 10 秒不可用窗口。不要为了纯 Java 改动重启 4 个 Worker；不要执行
`docker compose down -v` 或 `git reset --hard`。

## 下一步

1. 观察 HTTP/2 Tunnel 24 小时日志，确认不再出现成批连接超时；有证据再决定是否进一步换线路或 CDN。
2. 用浏览器人工验收游客登录动画：只点一次、按钮状态清晰、失败可恢复、不会创建重复 Guest。
3. 如需严格验收 Retry MySQL 回退，在线上选择一个已超过 Redis TTL 的历史回答 Retry，确认生成新 Revision、原位置替换、旧 Revision 仍可查。
4. 不做无证据优化：Chat PDF 暂不换图片，不扩 Worker 数，不引入新的部署系统。

## 面试主线

> 线上 Redis Worker 化后，我沿前端、Java、Redis Streams、Python Worker 和 MySQL 追踪 Retry、Cancel
> 与运行状态。发现 Redis 临时快照 TTL 和 MySQL 历史生命周期不一致，于是采用 Redis 优先、MySQL
> 兜底恢复 RetryContext；同时给 Cancel 增加对象级授权，并用 Answer Slot + Revision 保留历史而不覆盖。
> 部署后又通过日志区分应用崩溃和 Tunnel 网络抖动，将不稳定的 QUIC/UDP 切换为 HTTP/2/TCP。
> 所有性能与稳定性结论都保留复现步骤和实测证据，不编造提升数据。
