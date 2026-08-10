# Wuyun + Cloudflare Tunnel 部署实录

本文记录 PaperLoom 第一次公网部署的实际步骤，并把它整理成可在另一台服务器复现的流程。

- 完成时间：2026-08-10
- 部署版本：`e97ea42`
- 项目目录：`<paperloom-home>`
- 公网域名：`paperloom.me`
- 当前架构不开放 PaperLoom 的任何公网端口；只有 Cloudflare 对外提供 HTTPS。

`paperloom.me` 这次被 Cloudflare 分配到的权威 Nameserver 是
`kareem.ns.cloudflare.com` 和 `karina.ns.cloudflare.com`。其他域名会得到不同的地址，不能照抄。

## 最终结构

```text
浏览器
  -> https://paperloom.me
  -> Cloudflare 边缘 HTTPS
  -> Cloudflare Tunnel
  -> Nginx（127.0.0.1:18880）
       -> 前端静态文件
       -> 后端 API（127.0.0.1:18082）
       -> MinIO 文件（127.0.0.1:9200）
            -> Research Harness（127.0.0.1:8091）
            -> MySQL / Redis / Kafka / Qdrant（全都仅本机监听）
```

| 组件 | 服务器监听地址 | 用途 |
| --- | --- | --- |
| Nginx | `127.0.0.1:18880` | Tunnel 的唯一入口；提供前端、API、WebSocket、文件 |
| Spring Boot | `127.0.0.1:18082` | PaperLoom 产品 API |
| Research Harness | `127.0.0.1:8091` | 内部研究执行服务 |
| MySQL | `127.0.0.1:13307` | 论文和产品元数据 |
| Redis | `127.0.0.1:16379` | 临时状态 |
| MinIO | `127.0.0.1:9200` | PDF、解析产物和图片 |
| Qdrant | `127.0.0.1:6333` | Current Reading Model 索引 |
| Kafka | `127.0.0.1:9092` | 上传处理消息 |

这意味着：即使知道服务器 IP，也不能直接访问数据库、后台、MinIO 或 Nginx。公网请求只能经过
Cloudflare Tunnel。

## 1. 准备服务器

本次使用 Debian、Docker Compose、BaoTa 管理的 Nginx、Node 20 + Corepack、Python 3.13。
Docker 和 Nginx 是前置条件；缺少运行时可安装：

```bash
apt-get install -y openjdk-21-jdk-headless python3.13 python3.13-venv acl jq
corepack enable
```

不要占用服务器已有项目的 `80` 和 `443`。选择未使用的本机端口，例如本文表格中的
`18082`、`18880` 等。

下面用 `PAPERLOOM_HOME` 和 `PAPERLOOM_BACKUP_ROOT` 代替服务器上的实际绝对路径。先按自己的目录
替换它们，后续命令都使用这两个变量：

```bash
export PAPERLOOM_HOME=<paperloom-home>
export PAPERLOOM_BACKUP_ROOT=<paperloom-backup-root>
git clone git@github.com:CHZarles/paperloom.git "$PAPERLOOM_HOME"
cd "$PAPERLOOM_HOME"
git pull --ff-only
```

这次首次部署中发现并提交了两个必要修复，新的部署从最新 `main` 拉取即可：

- `c2c872f fix(deploy): bind data-service ports to loopback`
- `e97ea42 fix(deploy): load backend runtime config before health check`

第一个修复保证 Docker 中的数据服务不会绑定到公网网卡；第二个修复保证启动脚本会先读取
`.env` 中的 `SERVER_PORT`，不会把别的项目的 `8081` 误当作 PaperLoom 后端。

## 2. 创建生产 `.env`

把 `.env.example` 复制为服务器项目根目录的 `.env`，只保存在服务器上，不提交 Git。以下是本次
实际使用的结构；尖括号内容必须替换成真实的部署密钥。

```dotenv
SPRING_PROFILES_ACTIVE=prod
SERVER_ADDRESS=127.0.0.1
SERVER_PORT=18082

PAPERLOOM_DB_SCHEMA=paperloom
SPRING_DATASOURCE_URL=jdbc:mysql://localhost:13307/paperloom?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
SPRING_DATASOURCE_USERNAME=root
SPRING_DATASOURCE_PASSWORD=<mysql-root-password>

SPRING_DATA_REDIS_HOST=127.0.0.1
SPRING_DATA_REDIS_PORT=16379
SPRING_DATA_REDIS_PASSWORD=<redis-password>
REDIS_HOST_PORT=16379

MYSQL_HOST_PORT=13307
MINIO_ENDPOINT=http://127.0.0.1:9200
MINIO_PUBLIC_URL=http://127.0.0.1:18880/files
MINIO_ACCESS_KEY=<minio-access-key>
MINIO_SECRET_KEY=<minio-secret-key>
MINIO_BUCKET_NAME=uploads
MINIO_API_HOST_PORT=9200
MINIO_CONSOLE_HOST_PORT=9201

QDRANT_BASE_URL=http://127.0.0.1:6333
QDRANT_HTTP_HOST_PORT=6333
QDRANT_GRPC_HOST_PORT=6334
QDRANT_API_KEY=<qdrant-api-key>
QDRANT_COLLECTION=paperloom_reading_locations_hybrid_v1
QDRANT_CONTRACT=sparse-dense-v1

JWT_SECRET_KEY=<base64-secret>
PAPER_PARSING_PROVIDER=mineru
PAPER_PARSING_MINERU_API_TOKEN=<mineru-token>

MINIMAX_API_KEY=<minimax-key>
MINIMAX_MODEL=MiniMax-M3
RESEARCH_HARNESS_TRANSPORT=http
RESEARCH_HARNESS_BASE_URL=http://127.0.0.1:8091
RESEARCH_HARNESS_INTERNAL_TOKEN=<internal-token>
JAVA_CORPUS_BASE_URL=http://127.0.0.1:18082

AGENT_TRACE_DIR=/var/log/paperloom/agent-traces
AGENT_TRACE_RETENTION_DAYS=7
AGENT_TRACE_MAX_BYTES=10737418240
AGENT_TRACE_INCOMPLETE_GRACE_HOURS=24

PAPER_BOOTSTRAP_ENABLED=false
ADMIN_BOOTSTRAP_ENABLED=true
ADMIN_BOOTSTRAP_USERNAME=admin
ADMIN_BOOTSTRAP_PASSWORD=<新的强密码>
APP_AUTH_REGISTRATION_MODE=INVITE_ONLY
APP_AUTH_INVITE_REQUIRED=true
SECURITY_ALLOWED_ORIGINS=http://localhost:18880,http://127.0.0.1:18880
```

关键原则：

1. `SERVER_ADDRESS=127.0.0.1` 使 Java 后端不监听公网。
2. `MINIO_ENDPOINT` 永远是内部地址。`MINIO_PUBLIC_URL` 在域名还未接通时先写本机 Nginx
   路径，接通后再改为 `https://<域名>/files`。
3. 第一次启动前就设置新的管理员强密码。管理员创建成功后，立即把
   `ADMIN_BOOTSTRAP_ENABLED` 改为 `false`。不要把默认或临时密码暴露到公网。
4. `RESEARCH_HARNESS_TRANSPORT=http` 是这次实际运行的模式；不要仅因为通用部署文档提到
   Redis worker 就擅自切换模式。

## 3. 启动数据服务

`docs/docker-compose.yaml` 会启动 MySQL、MinIO、Redis、Kafka、Qdrant 和 MinIO 初始化任务：

```bash
cd "$PAPERLOOM_HOME"
docker compose --env-file .env -f docs/docker-compose.yaml up -d
docker compose --env-file .env -f docs/docker-compose.yaml ps
```

等待 MySQL、Redis、Kafka、Qdrant 显示 `healthy`。这些容器使用 Docker named volume 保存数据，
已经部署的服务器绝不能执行 `docker compose down -v`。

## 4. 可选：迁移已有论文语料

这次迁移了论文相关数据，但**没有**迁移用户、对话、额度和邀请码。服务器保留的迁移备份是：

```text
$PAPERLOOM_BACKUP_ROOT/
  paperloom-corpus.sql.gz
  minio/uploads/
  paperloom_reading_locations_hybrid_v1.snapshot
```

下面的恢复操作只能对新建且为空的 PaperLoom 实例执行。对已有数据的实例执行会替换或重复数据。

恢复 MySQL 中的论文元数据：

```bash
gunzip -c "$PAPERLOOM_BACKUP_ROOT/paperloom-corpus.sql.gz" \
  | docker exec -i paperloom-mysql sh -c \
      'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot paperloom'
```

恢复 MinIO 中的对象：

```bash
set -a
. ./.env
set +a
docker run --rm --network paperloom_default \
  -e "MC_HOST_target=http://${MINIO_ACCESS_KEY}:${MINIO_SECRET_KEY}@paperloom-minio:9000" \
  -v "$PAPERLOOM_BACKUP_ROOT/minio:/backup:ro" \
  minio/mc:RELEASE.2025-05-21T01-59-54Z \
  mirror --overwrite /backup/uploads target/uploads
```

仅在 Qdrant collection 不存在或为空时恢复 snapshot：

```bash
curl --fail-with-body -X POST \
  -H "api-key: ${QDRANT_API_KEY}" \
  -F "snapshot=@$PAPERLOOM_BACKUP_ROOT/paperloom_reading_locations_hybrid_v1.snapshot" \
  "http://127.0.0.1:6333/collections/${QDRANT_COLLECTION}/snapshots/upload?priority=snapshot"
```

第一次迁移完成后的核对结果是：42 条上传记录、31 篇公开论文、35 篇已处理论文、3,193 个
MinIO 对象（约 892 MiB）、2,981 个 Qdrant points。不要因为服务已启动就删除
`$PAPERLOOM_BACKUP_ROOT`；它是可恢复备份，不是临时目录。

## 5. 构建前后端与 Harness

```bash
cd "$PAPERLOOM_HOME"
mvn -DskipTests package

/usr/bin/python3.13 -m venv .venv-harness
.venv-harness/bin/pip install --disable-pip-version-check -r harness_py/requirements.lock

corepack pnpm --dir frontend install --frozen-lockfile
corepack pnpm --dir frontend build
```

产物分别是：

- Java 后端：`target/paperloom-server-0.1.0-SNAPSHOT.jar`
- 前端静态文件：`frontend/dist`
- Python 虚拟环境：`.venv-harness`

前端留在项目目录中，由 Nginx 直接读取；没有复制到 `/www/wwwroot`，也不会在 BaoTa 常规站点列表
中显眼出现。

## 6. 让后端和 Harness 随开机自动运行

下面两个 systemd 文件中的 `<paperloom-home>` 必须替换成实际项目目录。创建
`/etc/systemd/system/paperloom-harness.service`：

```ini
[Unit]
Description=PaperLoom research harness
After=network-online.target docker.service
Wants=network-online.target docker.service

[Service]
Type=simple
WorkingDirectory=<paperloom-home>
EnvironmentFile=<paperloom-home>/.env
ExecStartPre=/usr/bin/docker compose --env-file .env -f docs/docker-compose.yaml up -d
ExecStart=<paperloom-home>/.venv-harness/bin/python -u -m harness_py serve --host 127.0.0.1 --port 8091
Restart=on-failure
RestartSec=10
TimeoutStopSec=30

[Install]
WantedBy=multi-user.target
```

创建 `/etc/systemd/system/paperloom-backend.service`：

```ini
[Unit]
Description=PaperLoom backend
After=network-online.target docker.service paperloom-harness.service
Wants=network-online.target docker.service
Requires=paperloom-harness.service

[Service]
Type=simple
WorkingDirectory=<paperloom-home>
EnvironmentFile=<paperloom-home>/.env
ExecStartPre=/usr/bin/docker compose --env-file .env -f docs/docker-compose.yaml up -d
ExecStart=/usr/bin/java -jar <paperloom-home>/target/paperloom-server-0.1.0-SNAPSHOT.jar
Restart=on-failure
RestartSec=10
TimeoutStopSec=30

[Install]
WantedBy=multi-user.target
```

启用并启动：

```bash
systemctl daemon-reload
systemctl enable --now paperloom-harness.service paperloom-backend.service
systemctl status paperloom-harness.service paperloom-backend.service
```

管理员创建并确认可以登录后：

```bash
# 编辑 .env：ADMIN_BOOTSTRAP_ENABLED=false
systemctl restart paperloom-backend.service
```

## 7. 配置仅本机监听的 Nginx

BaoTa Nginx 的 vhost 配置目录是 `/www/server/panel/vhost/nginx`。创建
`/www/server/panel/vhost/nginx/paperloom-internal.conf`：

```nginx
server {
  listen 127.0.0.1:18880;
  server_name _;

  root <paperloom-home>/frontend/dist;
  index index.html;

  location ^~ /assets/ {
    try_files $uri =404;
    expires 1y;
    add_header Cache-Control "public, max-age=31536000, immutable" always;
  }

  location / {
    try_files $uri $uri/ /index.html;
  }

  location /api/ {
    proxy_pass http://127.0.0.1:18082;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
  }

  location /files/ {
    proxy_pass http://127.0.0.1:9200/;
    proxy_set_header Host 127.0.0.1:9200;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_request_buffering off;
  }

  location /proxy-ws {
    rewrite ^/proxy-ws(/.*)$ $1 break;
    proxy_pass http://127.0.0.1:18082;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_read_timeout 3600s;
  }
}
```

Nginx worker 以 `www` 用户运行。授予它读取前端所需的最小权限；若项目的父目录不是 `755`，
还要为每级父目录单独授予 `www` 仅可穿越的 `x` 权限：

```bash
setfacl -m u:www:rx \
  <paperloom-home> \
  <paperloom-home>/frontend \
  <paperloom-home>/frontend/dist
nginx -t
nginx -s reload
```

`/files/` 不是多余配置。后端按内部 MinIO 地址生成签名 URL，再替换成公网的 `/files/` 地址；
Nginx 转发时去掉 `/files/` 前缀，并且把 `Host` 保持为 `127.0.0.1:9200`，MinIO 才能验证签名。

## 8. 用 Namecheap 域名接入 Cloudflare Tunnel

Namecheap 和 Cloudflare 的账户内操作必须由域名所有者完成：

1. 在 Cloudflare 添加根域名，选择 Free 计划。
2. 检查导入的 DNS。如果根域名完全用于 PaperLoom，删除旧根域名 `A` 记录。这次发现的是四条
   GitHub Pages 的 `185.199.*` 记录。保留 `MX` 和 SPF `TXT`，除非确认不再使用对应邮件转发。
3. Cloudflare 会显示两个 Nameserver。到 Namecheap 的
   `Domain List -> Manage -> Nameservers`，选择 `Custom DNS`，填入 Cloudflare 给出的两个值。
4. 等待 Cloudflare 显示 zone 为 `Active`，并用以下命令确认：

   ```bash
   dig +short NS <domain>
   ```

5. 在 Cloudflare Zero Trust 中创建：
   `Networks -> Tunnels -> Create a tunnel -> Cloudflared`。Tunnel 名称可用 `paperloom`，选择
   Debian 64-bit connector。
6. 服务器安装 Cloudflare 官方包，并执行 Dashboard 给出的安装命令。Tunnel token 是密钥，不能写入
   Git、文档或截图：

   ```bash
   curl -fsSL https://pkg.cloudflare.com/cloudflare-main.gpg \
     -o /usr/share/keyrings/cloudflare-main.gpg
   printf '%s\n' \
     'deb [signed-by=/usr/share/keyrings/cloudflare-main.gpg] https://pkg.cloudflare.com/cloudflared any main' \
     > /etc/apt/sources.list.d/cloudflared.list
   apt-get update
   apt-get install -y cloudflared
   cloudflared service install <tunnel-token>
   systemctl status cloudflared
   ```

7. 在该 Tunnel 的 `Public Hostname` 页面添加一条路由：

   ```text
   Subdomain: 留空
   Domain:    <domain>
   Path:      留空
   Service:   HTTP -> 127.0.0.1:18880
   ```

Cloudflare 会自动提供公网 HTTPS。因此不需要，也不应在服务器防火墙中开放 `80`、`443`、`18880`
或任何 PaperLoom 数据服务端口。

## 9. 域名接通后切换应用配置

Tunnel Public Hostname 配好、`https://<domain>` 已返回响应后，编辑 `.env`：

```dotenv
SECURITY_ALLOWED_ORIGINS=https://<domain>
MINIO_PUBLIC_URL=https://<domain>/files
```

后端读取这些配置的时机是进程启动，因此只需要重启后端：

```bash
systemctl restart paperloom-backend.service
```

## 10. 最终验收

下面是最小验证。未登录访问 API 返回 `403` 是预期结果，表示 API 已通且认证仍然生效。

```bash
curl -o /dev/null -w '%{http_code}\n' https://<domain>/
curl -o /dev/null -w '%{http_code}\n' https://<domain>/api/v1/users/me
systemctl is-active paperloom-harness.service
systemctl is-active paperloom-backend.service
systemctl is-active cloudflared.service
cd "$PAPERLOOM_HOME"
docker compose --env-file .env -f docs/docker-compose.yaml ps
```

本次正式上线的结果是：前端 `200`、未认证 API `403`、管理员登录 `200`，Harness、Backend、
Cloudflared 三个 systemd 服务均为 `active`。

上线后的检查、发布、备份与排障见[运维指南](operations.md)。

## 日常更新、排障与备份

更新代码：

```bash
cd "$PAPERLOOM_HOME"
git pull --ff-only
mvn -DskipTests package
corepack pnpm --dir frontend install --frozen-lockfile
corepack pnpm --dir frontend build
systemctl restart paperloom-harness.service paperloom-backend.service
```

看状态和日志：

```bash
systemctl status paperloom-backend paperloom-harness cloudflared
journalctl -u paperloom-backend -f
journalctl -u paperloom-harness -f
journalctl -u cloudflared -f
```

备份时必须把 MySQL 和 MinIO 一起备份，Qdrant snapshot 一起保存。Qdrant 可以由 Current Reading
Model 重建，但有 snapshot 能更快恢复。备份应放在运行项目目录外；已有
`$PAPERLOOM_BACKUP_ROOT` 不应随意删除。
