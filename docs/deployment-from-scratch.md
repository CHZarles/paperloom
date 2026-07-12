# PaiSmart 从零部署到启动

这份文档按“先起基础服务，再起后端，最后起前端”的顺序写。目标不是讲原理，而是让你照着执行就能把项目跑起来。

## 1. 你需要先准备什么

- `Git`
- `Java 17`
- `Maven 3.8+`
- `Node.js 18.20+`
- `pnpm 8.7+`，或者直接用 `corepack` 调用
- `Docker` 和 `Docker Compose`

如果你在 Windows 上开发，建议把项目放在 WSL 里执行，不要把代码和运行目录混在 Windows 盘里来回拷贝。

## 2. 先拉代码

```bash
git clone <你的仓库地址> PaiSmart
cd PaiSmart
```

如果你已经有代码，就直接进入项目根目录：

```bash
cd /home/charles/PaiSmart
```

## 3. 准备根目录 `.env`

项目根目录的 `.env` 是后端启动时读取的主配置文件。第一次使用时，先从模板复制一份：

```bash
cp .env.example .env
```

然后检查这几个关键项：

- `SPRING_PROFILES_ACTIVE=dev`
- `SERVER_PORT=8081`
- `SPRING_DATASOURCE_URL=jdbc:mysql://localhost:23306/PaiSmart?...`
- `SPRING_DATASOURCE_USERNAME=root`
- `SPRING_DATASOURCE_PASSWORD=PaiSmart2025`
- `MINIO_ENDPOINT=http://localhost:19000`
- `SPRING_KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:29092`
- `ELASTICSEARCH_HOST=localhost`
- `ELASTICSEARCH_PORT=29200`
- `ELASTICSEARCH_SCHEME=http`
- `ADMIN_BOOTSTRAP_ENABLED=true`
- `ADMIN_BOOTSTRAP_USERNAME=admin`
- `ADMIN_BOOTSTRAP_PASSWORD=PaismartAdmin2025!`
- `MINIMAX_API_BASE_URL=https://api.minimaxi.com/v1`
- `MINIMAX_API_KEY=<研究 Harness 使用的 API Key>`
- `MINIMAX_MODEL=MiniMax-M3`
- `RESEARCH_HARNESS_INTERNAL_TOKEN=<Java 与 Python 之间共享的随机字符串>`

说明：

- 这个项目的本地基础服务端口来自 `docs/docker-compose.yaml`
- 后端默认监听 `8081`
- 管理员账号是 `admin`
- 这个 `ADMIN_BOOTSTRAP_ENABLED` 只建议在第一次建库、第一次起服务时保持 `true`

## 4. 启动 Docker 基础服务

`docs/docker-compose.yaml` 里启动的是基础依赖，不是后端本身。它包含：

- MySQL
- MinIO
- Redis
- Kafka
- Elasticsearch
- MinIO 初始化容器

推荐直接在项目根目录启动，这样会自动读取根目录 `.env`，不会把端口回落到默认值：

```bash
docker compose -f docs/docker-compose.yaml up -d
docker compose --env-file .env -f docs/docker-compose.yaml up -d
```

注意：

- 这个仓库的 `docs/docker-compose.yaml` 使用了顶层 `name:`，需要 `Docker Compose v2`
- 如果你执行的是旧版 `docker-compose`，它会把 `name` 当成非法字段，于是就会报你看到的这个错
- 所以这里优先用 `docker compose`，不要用 `docker-compose`
- 不要先 `cd docs` 再裸跑 `docker compose up -d`，那样它读不到根目录 `.env`，MySQL 会退回 `3306`，Redis 会退回 `6379`，Kafka 会退回 `9092`

如果你已经进入了 `docs` 目录，也必须显式带上根目录的环境文件：

```bash
docker compose --env-file ../.env up -d
```

如果你的环境只有旧命令，就先升级 Docker Compose，或者临时把 `docs/docker-compose.yaml` 顶部的 `name: pai_smart` 去掉再执行。

如果你的环境升级不了，也可以试：

```bash
docker-compose up -d
```

启动后检查状态：

```bash
docker compose ps
```

看日志：

```bash
docker compose logs -f mysql
docker compose logs -f minio
docker compose logs -f redis
docker compose logs -f kafka
docker compose logs -f es
```

返回项目根目录：

```bash
cd ..
```

### 4.1 基础服务端口

- MySQL: `localhost:23306`
- MinIO API: `localhost:19000`
- MinIO Console: `localhost:19001`
- Redis: `localhost:26379`
- Kafka: `localhost:29092`
- Kafka Controller: `localhost:29093`
- Elasticsearch: `localhost:29200`

## 5. 启动研究 Harness

Java 聊天请求会转发到本机 Python Harness。先启动它：

```bash
scripts/paperloom-start-harness.sh start
```

健康检查地址：`http://127.0.0.1:8091/health`。

Harness 的 LLM 配置只从部署环境读取，不再由前端模型配置页面控制。Java 通过内部 NDJSON
流接收检索进度和最终回答；Redis 仍由 Java 用于保存前端可恢复的生成状态，Python Harness
本身不连接 Redis。

## 6. 启动后端

回到项目根目录后启动 Spring Boot：

```bash
mvn spring-boot:run
```

后端启动后会自动读取根目录 `.env`，不需要你手动 `export` 一堆环境变量。

如果你想先打包再启动，也可以这样：

```bash
mvn clean package -DskipTests
java -jar target/SmartPAI-0.0.1-SNAPSHOT.jar
```

### 5.1 后端启动成功的判断

你看到类似这些日志就说明后端起来了：

- `Tomcat started on port 8081`
- `管理员账号 'admin' 创建成功`
- `管理员账号 'admin' 已存在，跳过创建步骤`

也可以使用后台启动脚本：

```bash
mvn clean package -DskipTests
scripts/paperloom-start-backend.sh start
```

## 7. 启动前端

打开另一个终端，进入前端目录：

```bash
cd frontend
corepack pnpm install
corepack pnpm run dev
```

前端开发模式默认会读取 `frontend/.env.test`，后端地址是：

```bash
http://localhost:8081/api/v1
```

前端启动后，通常访问：

```bash
http://localhost:9527
```

如果你本地 Vite 端口被占用，以终端输出为准。

## 8. 最终访问顺序

启动完成后，按这个顺序确认：

1. Docker 依赖容器都在运行
2. 后端 `8081` 能访问
3. 前端页面能打开
4. 用 `admin / PaismartAdmin2025!` 登录

如果你是在 WSL 里启动，Windows 侧不需要再单独起一套项目。直接在 Windows 浏览器里打开这些地址就行：

- 后端接口：`http://localhost:8081/api/v1`
- 前端页面：`http://localhost:9527`

如果浏览器打不开，先确认 WSL 里的后端和前端进程都在运行，再确认 Windows 侧没有把同一个端口的别的程序占住。

## 9. 常见问题

### 8.1 `docker compose` 报命令不存在

先试：

```bash
docker-compose up -d
```

如果还是不行，说明 Docker Compose 没装好，先确认 `docker` 和 `docker compose` 是否可用。

### 8.2 `name does not match any of the regexes: '^x-'`

这不是服务定义错了，是你在用旧版 `docker-compose` 解析现代 compose 文件。

解决办法：

- 用 `docker compose up -d`
- 或升级 Docker Compose 到 v2
- 或删掉 `docs/docker-compose.yaml` 顶层的 `name: pai_smart`

### 8.3 后端连不上数据库

先检查 MySQL 容器是否真的起来了：

```bash
docker compose ps
```

再确认根目录 `.env` 里的 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD` 是否和 `docs/docker-compose.yaml` 对得上。

### 8.4 Elasticsearch 起不来

这个项目的 Elasticsearch 容器会安装 `analysis-ik` 插件，第一次启动会慢一点。先看日志：

```bash
docker compose logs -f es
```

### 8.5 前端打不开后端接口

优先检查两件事：

- 后端是不是已经在 `8081` 跑起来了
- `frontend/.env.test` 里的 `VITE_SERVICE_BASE_URL` 是否仍然指向 `http://localhost:8081/api/v1`

### 8.6 `pnpm` 命令不存在

这台机器如果已经装了 Node，但没有单独安装 `pnpm`，可以直接用 `corepack`：

```bash
corepack pnpm install
corepack pnpm run dev
```

如果你想长期直接使用 `pnpm` 命令，再执行：

```bash
corepack prepare pnpm@8.15.9 --activate
```

## 10. 停止服务

停止 Docker 依赖：

```bash
docker compose -f docs/docker-compose.yaml down
```

停止后端：

```bash
# 如果你是前台运行，直接 `Ctrl+C`
# 如果你是自己单独开的 jar 进程，就按你自己的启动方式结束进程
```

停止前端：

```bash
# 前台运行时直接 `Ctrl+C`
```
