# PaperLoom 运维指南

这份指南面向已经部署好 PaperLoom、但不打算每天写代码的维护者。它只覆盖当前实际运行的
`Docker Compose + systemd + Nginx + Cloudflare Tunnel` 结构。

开始前在 SSH 终端设置两个变量。把尖括号替换成实际值；不要把 `.env`、密码或 Tunnel token
发到聊天、提交到 Git，或写入文档。

```bash
export PAPERLOOM_HOME=<paperloom-home>
export PAPERLOOM_BACKUP_ROOT=<paperloom-backup-root>
export PAPERLOOM_DOMAIN=<public-domain>
cd "$PAPERLOOM_HOME"
```

## 先记住这四件事

1. 公网入口是 `https://$PAPERLOOM_DOMAIN`，不是服务器 IP，也不是 `18880` 端口。
2. 服务由 systemd 管理：`paperloom-backend`、`paperloom-harness`、`cloudflared`。
3. MySQL、MinIO、Redis、Kafka、Qdrant 由 Docker Compose 管理，数据在 Docker volume 中。
4. `.env` 是运行配置的唯一来源。改完它后，必须重启读取该配置的服务。

绝不能执行以下命令：

```bash
docker compose down -v
docker volume rm <任何 paperloom volume>
```

它们会删除论文、对象文件或索引数据。

## 日常健康检查

登录服务器后，先运行：

```bash
cd "$PAPERLOOM_HOME"
systemctl is-active paperloom-backend.service
systemctl is-active paperloom-harness.service
systemctl is-active cloudflared.service
docker compose --env-file .env -f docs/docker-compose.yaml ps
curl -o /dev/null -w 'frontend=%{http_code}\n' "https://$PAPERLOOM_DOMAIN/"
curl -o /dev/null -w 'anonymous-api=%{http_code}\n' \
  "https://$PAPERLOOM_DOMAIN/api/v1/users/me"
```

预期结果：三个 `systemctl` 都输出 `active`；Docker 中 MySQL、Redis、Kafka、Qdrant 为
`healthy`；前端是 `200`；未登录 API 是 `403`。`403` 在这里是正常的，它表示 API 可达而且
登录保护仍然有效。

再检查磁盘，PDF、MinIO 和 Docker 最容易把磁盘用满：

```bash
df -h
docker system df
```

## 正确地重启服务

先根据故障范围重启最小的服务，不要一上来就重启整台服务器。

| 症状 | 先执行 |
| --- | --- |
| 登录、论文列表、上传 API 异常 | `systemctl restart paperloom-backend.service` |
| 研究问答不返回、Agent 报内部错误 | `systemctl restart paperloom-harness.service` |
| 域名出现 Cloudflare 连接错误 | `systemctl restart cloudflared.service` |
| 前端页面是旧版本 | 重新构建 `frontend`，Nginx 无需重启 |
| 改了 Nginx 配置 | `nginx -t && nginx -s reload` |

重启后立即检查状态：

```bash
systemctl status paperloom-backend paperloom-harness cloudflared --no-pager
```

要正常停掉产品服务（例如服务器维护），只停应用进程即可：

```bash
systemctl stop paperloom-backend.service paperloom-harness.service cloudflared.service
```

这不会删除 Docker volume。恢复时使用：

```bash
systemctl start paperloom-harness.service paperloom-backend.service cloudflared.service
```

## 发布新代码

下面是一次普通发布的固定顺序。构建失败时，正在运行的旧版本仍会继续服务；不要在构建失败后
重启服务。

```bash
cd "$PAPERLOOM_HOME"
git status --short
git pull --ff-only

mvn -DskipTests package
corepack pnpm --dir frontend install --frozen-lockfile
corepack pnpm --dir frontend build

systemctl restart paperloom-harness.service paperloom-backend.service
```

然后执行“日常健康检查”。`git status --short` 必须为空；如果有输出，先停下确认那些改动是不是
你在服务器上手工做的。不要用 `git reset --hard` 清理服务器。

如果这次改动涉及 Python 依赖，再额外执行：

```bash
cd "$PAPERLOOM_HOME"
.venv-harness/bin/pip install --disable-pip-version-check -r harness_py/requirements.lock
systemctl restart paperloom-harness.service
```

## 修改配置时该重启什么

`.env` 改动不会自动生效。按下面的规则重启：

| 改动内容 | 必须执行 |
| --- | --- |
| `MINIMAX_*`、`RESEARCH_HARNESS_*`、Harness Python 配置 | `systemctl restart paperloom-harness.service` |
| `SPRING_*`、JWT、MinerU、Qdrant、MinIO、CORS、端口 | `systemctl restart paperloom-backend.service` |
| Docker 的端口、MySQL/Redis/MinIO/Qdrant 配置 | `docker compose --env-file .env -f docs/docker-compose.yaml up -d`，再重启后端和 Harness |
| `/www/server/panel/vhost/nginx/*.conf` | `nginx -t && nginx -s reload` |
| Cloudflare Dashboard 的 Tunnel Public Hostname | 不重启应用；确认 `cloudflared` 为 `active` |

更换公网域名时，Cloudflare Public Hostname、`.env` 和 MinIO 文件地址必须一起更新：

```dotenv
SECURITY_ALLOWED_ORIGINS=https://<new-domain>
MINIO_PUBLIC_URL=https://<new-domain>/files
```

随后重启后端。若遗漏第二项，用户打开 PDF、截图或解析产物时会得到错误的文件地址。

## 看日志定位问题

三个常用日志入口：

```bash
journalctl -u paperloom-backend -f
journalctl -u paperloom-harness -f
journalctl -u cloudflared -f
```

快速判断路径：

| 现象 | 先看什么 |
| --- | --- |
| 域名打不开、Cloudflare 显示 502/1033 | `cloudflared` 日志，再检查 Nginx 本机 `18880` |
| 页面能打开，但登录、论文列表报错 | `paperloom-backend` 日志 |
| 聊天一直转圈、研究请求失败 | `paperloom-harness` 日志，再检查 MiniMax 额度和密钥 |
| 上传或解析失败 | 后端日志、MinerU token、MinIO container 状态 |
| PDF、表格截图、图像打不开 | `MINIO_PUBLIC_URL`、Nginx `/files/` 配置、MinIO 状态 |
| 重启服务器后服务没起来 | `systemctl status` 和 `docker compose ps` |

从服务器内部检查 Nginx 到后端的链路：

```bash
curl -o /dev/null -w 'nginx=%{http_code}\n' http://127.0.0.1:18880/
curl -o /dev/null -w 'backend=%{http_code}\n' \
  http://127.0.0.1:18082/api/v1/users/me
curl -fsS http://127.0.0.1:8091/health
```

这里后端返回 `403` 同样正常；Harness health 必须返回成功。

### 查看 Agent Action Trace

当研究回答超时、反复调用模型或 Tool Call 异常时，`journalctl` 只能看到服务级错误。完整 Agent
轨迹位于 `.env` 的 `AGENT_TRACE_DIR`，每个 Run 一个私有目录：

```text
<AGENT_TRACE_DIR>/<run_id>/events.jsonl
<AGENT_TRACE_DIR>/<run_id>/result.json
```

先从对话记录取得 `generationId`，再定位对应 Run：

```bash
TRACE_DIR="$(sed -n 's/^AGENT_TRACE_DIR=//p' "$PAPERLOOM_HOME/.env" | head -n 1)"
GENERATION_ID=<generation-id>
grep -rl --fixed-strings "\"request_id\":\"$GENERATION_ID\"" "$TRACE_DIR"/*/events.jsonl
```

查看模型交互、工具执行和答案校验：

```bash
jq -c 'select(.kind == "model.request" or .kind == "model.response" or
              .kind == "model.output_transformed" or .kind == "tool.started" or
              .kind == "tool.completed" or .kind == "tool.error" or
              .kind == "answer.validation")' \
  "$TRACE_DIR/<run_id>/events.jsonl"
```

这些文件含完整 Prompt、历史对话、论文正文、模型输出和工具参数，只能在服务器 SSH 中查看，不得放进
公网目录、工单附件或 Git。Header 中的认证、Cookie 和 API Key 已由 Recorder 删除。

Trace 会按 `AGENT_TRACE_RETENTION_DAYS` 删除过期 Run，并在总量超过 `AGENT_TRACE_MAX_BYTES` 时删除
最旧的已完成 Run。检查当前占用：

```bash
du -sh "$TRACE_DIR"
```

## 备份

一次可恢复的 PaperLoom 备份至少包含三部分：

1. MySQL：论文、用户、对话、额度、邀请码等关系数据。
2. MinIO：PDF、解析产物、截图和图片。
3. Qdrant snapshot：可重建，但保留它能显著缩短恢复时间。

不要只备份 MySQL；那样会得到指向不存在对象文件的论文记录。每次大版本发布、数据库迁移、批量上传
前都应先做一次备份。

以下命令会创建一个带时间戳的新备份目录，不会覆盖旧备份：

```bash
cd "$PAPERLOOM_HOME"
umask 077
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
BACKUP_DIR="$PAPERLOOM_BACKUP_ROOT/ops-$STAMP"
mkdir -p "$BACKUP_DIR/minio" "$BACKUP_DIR/qdrant"

docker exec paperloom-mysql sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --routines --triggers paperloom' \
  | gzip > "$BACKUP_DIR/paperloom.sql.gz"
gzip -t "$BACKUP_DIR/paperloom.sql.gz"
```

备份 MinIO。下面命令从 `.env` 读取 MinIO 凭据，不会把密钥打印出来：

```bash
MINIO_ACCESS_KEY="$(sed -n 's/^MINIO_ACCESS_KEY=//p' .env | head -n 1)"
MINIO_SECRET_KEY="$(sed -n 's/^MINIO_SECRET_KEY=//p' .env | head -n 1)"
docker run --rm --network paperloom_default \
  -e "MC_HOST_source=http://${MINIO_ACCESS_KEY}:${MINIO_SECRET_KEY}@paperloom-minio:9000" \
  -v "$BACKUP_DIR/minio:/backup" \
  minio/mc:RELEASE.2025-05-21T01-59-54Z \
  mirror --overwrite source/uploads /backup/uploads
unset MINIO_ACCESS_KEY MINIO_SECRET_KEY
```

备份 Qdrant。其返回内容用于取得刚创建的 snapshot 文件名：

```bash
QDRANT_BASE_URL="$(sed -n 's/^QDRANT_BASE_URL=//p' .env | head -n 1)"
QDRANT_API_KEY="$(sed -n 's/^QDRANT_API_KEY=//p' .env | head -n 1)"
QDRANT_COLLECTION="$(sed -n 's/^QDRANT_COLLECTION=//p' .env | head -n 1)"
SNAPSHOT_JSON="$(curl --fail-with-body -sS -X POST \
  -H "api-key: $QDRANT_API_KEY" \
  "$QDRANT_BASE_URL/collections/$QDRANT_COLLECTION/snapshots")"
SNAPSHOT_NAME="$(printf '%s' "$SNAPSHOT_JSON" | sed -n 's/.*"name":"\([^"]*\)".*/\1/p')"
test -n "$SNAPSHOT_NAME"
curl --fail-with-body -sS \
  -H "api-key: $QDRANT_API_KEY" \
  -o "$BACKUP_DIR/qdrant/$SNAPSHOT_NAME" \
  "$QDRANT_BASE_URL/collections/$QDRANT_COLLECTION/snapshots/$SNAPSHOT_NAME"
unset QDRANT_BASE_URL QDRANT_API_KEY QDRANT_COLLECTION
```

最后至少检查目录大小和文件是否存在：

```bash
du -sh "$BACKUP_DIR"
find "$BACKUP_DIR" -maxdepth 2 -type f -printf '%P\n'
```

恢复操作具有破坏性。先停止 Backend 和 Harness，确认目标实例为空或已经被明确决定要覆盖，再按照
[Wuyun + Cloudflare Tunnel 部署实录](wuyun-cloudflare-tunnel-deployment.md) 的“迁移已有论文语料”
章节恢复。不要在故障压力下直接对生产 MySQL、MinIO 或 Qdrant 执行导入命令。

## 管理员账号和密钥

上线前必须满足：

```dotenv
ADMIN_BOOTSTRAP_ENABLED=false
```

不要重新打开 bootstrap 来“重置密码”；它只负责创建尚不存在的管理员，不能安全地修改已存在账号。
当前产品没有自助修改管理员密码的页面。需要轮换密码时，先在维护窗口执行以下受控流程。

这台服务器已安装 `apache2-utils`；如果另一台服务器没有 `htpasswd`，先执行
`apt-get install -y apache2-utils`。

```bash
read -rsp '输入新的管理员密码: ' ADMIN_PASSWORD
echo
ADMIN_HASH="$(htpasswd -bnBC 12 '' "$ADMIN_PASSWORD" | tr -d ':\n')"
printf "UPDATE users SET password='%s' WHERE username='admin';\n" "$ADMIN_HASH" \
  | docker exec -i paperloom-mysql sh -c \
      'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot paperloom'
unset ADMIN_PASSWORD ADMIN_HASH
```

新密码应至少 12 位，包含字母和数字。修改后，用新的密码登录确认。现有 JWT 不会因改密码自动失效；如果
怀疑管理员密码已经泄露，另外生成新的 `JWT_SECRET_KEY`、更新 `.env` 并重启后端，使所有登录会话失效。

需要重点保护的文件和位置：

| 内容 | 位置 | 处理原则 |
| --- | --- | --- |
| 应用密钥、模型密钥、数据库密码 | `$PAPERLOOM_HOME/.env` | 不复制到仓库、截图和聊天记录 |
| Cloudflare Tunnel token | Cloudflare 管理的 systemd 配置 | 不输出、不提交；怀疑泄露就在 Cloudflare 轮换 token |
| 论文和聊天数据 | Docker volumes、备份目录 | 备份后再做破坏性操作 |

## 不要把这些问题当成故障

| 看到的结果 | 含义 |
| --- | --- |
| `https://<domain>/api/v1/users/me` 返回 `403` | 未登录访问被正确拒绝 |
| `https://<domain>/files/uploads` 返回 `403` | MinIO 拒绝没有签名的对象请求，正常 |
| `127.0.0.1:18880` 之外访问不到 Nginx | 正常，公网入口应是 Cloudflare Tunnel |
| `docker compose ps` 的 `minio-init` 已退出 | 初始化 bucket 的一次性任务完成，正常 |

## 当前未自动化的事情

这次部署已配置应用、数据服务和 Tunnel 的开机自启，但没有额外引入监控平台、告警服务或定时备份任务。
当前维护方式是：发布和批量操作前手工备份，按本指南检查服务状态和磁盘，再观察 Cloudflare、后端和
Harness 日志。这样足以支撑当前内测规模，同时不会额外引入一套需要维护的运维系统。
