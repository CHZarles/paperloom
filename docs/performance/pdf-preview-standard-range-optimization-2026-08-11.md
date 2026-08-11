# PDF 标准 Range 单往返优化报告

日期：2026-08-11  
状态：已部署并完成线上 Chrome 复测  
应用提交：`d14a835 perf(pdf): combine preview metadata and initial range`

## 1. 问题背景

上一轮删除了 PDF 打开前无信息增量的 descriptor 请求，冷打开中位数从 `3058.8 ms` 降到
`2561.0 ms`。剩余链路仍有两个严格串行请求：

```text
GET /preview/pdf-data          -> JSON：总大小、分块大小、Range URL
GET /preview/pdf-data/range    -> PDF 二进制
```

第一步只为第二步提供文件大小和 URL，本身不含 PDF 内容。对线上测试的 `541036 B` PDF，每次冷打开仍要
额外等待一次完整公网往返。

## 2. 先验证“换图片”假设

没有直接重写预览器，而是在同一线上论文、同一证据页、同一 Chrome 环境中交替执行 5 组 PDF 与页面截图
候选测试：

| 候选 | 首屏中位数 | 主体大小 |
| --- | ---: | ---: |
| PDF | 2276.1 ms | 541036 B |
| PNG 页面图 | 1818.2 ms | 355256 B |

图片候选中位数快 `457.9 ms`（`20.1%`），字节少约 `34.3%`，但仍要串行请求截图描述和图片本体。
它没有证明“图片格式本身”是主要收益，也会引入另一套预览、缩放和标注坐标链路。项目没有必要为一次尚未
隔离清楚的收益更换正确性边界，因此本轮暂缓图片方案，先删除已证实的串行往返。

原始数据：[pdf-evidence-image-candidate-baseline-2026-08-11.json](pdf-evidence-image-candidate-baseline-2026-08-11.json)

## 3. 最小方案

复用 HTTP 原生 Range，不增加接口和依赖：

```text
GET /preview/pdf-data
Range: bytes=0-1048575

HTTP/1.1 206 Partial Content
Content-Range: bytes 0-541035/541036
Content-Type: application/pdf
<PDF bytes>
```

同一个响应同时提供 PDF 内容和总大小：

- 小于首块 `1 MiB` 的 PDF 一次返回完整内容，冷打开由 2 个请求变成 1 个；
- 大文件先返回首块，pdf.js 再对同一 URL 发标准 Range 请求；
- 旧后端返回 JSON 时，前端继续走已有 Metadata/Range 兼容路径；
- 完整小 PDF 进入现有有界内存缓存，热打开仍为 0 个请求；
- 后端先完成论文访问校验，再读取文件，并校验单段 Range、边界和实际读取长度。

没有删除旧接口，回滚前端或滚动发布时不会中断预览。

## 4. 部署时发现的代理层问题

首次部署后，浏览器明确发出了 `Range: bytes=0-1048575`，线上却仍收到 `200 JSON` 并继续请求旧
`/range` 接口。三段探针定位结果是：

```text
直接请求 Spring Boot 127.0.0.1:18082 -> 206，32 B，Content-Range 正确
经过 Nginx 127.0.0.1:18880         -> 200，386 B JSON
经过公网 paperloom.me               -> 200，386 B JSON
```

根因是 BaoTa 全局 `proxy.conf` 启用了 `proxy_cache`。Nginx 在代理缓存开启时不会把客户端 `Range`
传给上游，即使响应的 `Cache-Control: no-store` 最终阻止了缓存。修复是在 PaperLoom 的 `/api/` location
显式增加 `proxy_cache off;`，执行 `nginx -t` 后热重载。随后同一探针变为 `206`。

这一步也避免认证 API 误继承全局代理缓存。部署文档已同步该配置。

代理修复前原始数据：
[pdf-preview-standard-range-before-nginx-cache-fix-2026-08-11.json](pdf-preview-standard-range-before-nginx-cache-fix-2026-08-11.json)

## 5. 正确性验证

RED-GREEN 覆盖了以下契约：

- 首请求必须携带标准 Range；
- 小 PDF 的 `206` 响应直接成为完整数据；
- 大 PDF 根据 `Content-Range` 创建 pdf.js Range Transport；
- 缺失或不匹配的 `Content-Range` 必须拒绝，不能把残缺文件当完整 PDF；
- 热重开完整小 PDF 不再次请求；
- 后端非法 Range 返回 `416` 和 `Content-Range: bytes */total`；
- 后端必须在读取文件之前完成访问授权；
- 旧 JSON 协议继续兼容。

验证结果：前端 Loader 测试、TypeScript、ESLint、生产构建和 Bundle Budget 全部通过；后端
`PaperControllerContractTest` 共 `22/22` 通过。线上 Chrome 确认首屏 Canvas 成功渲染，冷打开只有一个
`206` 请求，响应 `Content-Range: bytes 0-541035/541036`，热打开为 0 个 PDF 请求。

## 6. 线上结果

同一台 Apple M5、同一份 `02-GPT-1.pdf`、同一游客 Storage State、Chrome 151、不限速公网，每组 5 次，
主要比较中位数：

| 指标 | 上一轮 | 标准 Range | 变化 |
| --- | ---: | ---: | ---: |
| 冷打开首屏 | 2561.0 ms | 2057.0 ms | `-504.0 ms`，`-19.7%` |
| 冷打开网络完成 | 2111.2 ms | 1213.5 ms | `-897.7 ms`，`-42.5%` |
| 冷打开 PDF 请求数 | 2 | 1 | `-1` |
| 冷打开传输字节 | 541347 B | 541036 B | 基本不变 |
| 热打开首屏 | 886.1 ms | 976.1 ms | `+90.0 ms`，本轮未改善 |
| 热打开 PDF 请求数 | 0 | 0 | 不变 |

从最初基准到本轮，冷打开中位数由 `3058.8 ms` 降到 `2057.0 ms`，累计减少 `1001.8 ms`，约
`32.8%`；关键请求由 3 个降到 1 个。收益来自减少串行等待，不是压缩 PDF 字节。

原始数据：[pdf-preview-standard-range-2026-08-11.json](pdf-preview-standard-range-2026-08-11.json)

## 7. 不能夸大的部分

- 每组只有 5 个样本，只报告中位数和范围，不能声称 p95；
- 只覆盖一份约 `541 KB` 的 PDF、当前公网和一台客户端；
- 热打开没有提升，约 `90 ms` 的变化不能包装成优化收益；
- 大于 `1 MiB` 的 PDF 会继续分块请求，本轮只验证了协议正确性，未量化其热重开性能；
- 图片方案只是候选实验，最终没有上线，不能把它的 `20.1%` 当成产品提升。

## 8. 复现

```bash
pnpm --dir frontend exec tsx tests/pdf-preview-loader.test.ts
pnpm --dir frontend typecheck
pnpm --dir frontend exec eslint \
  src/components/custom/pdf-preview-loader.ts \
  tests/pdf-preview-loader.test.ts \
  scripts/benchmark-pdf-preview.mjs \
  scripts/benchmark-evidence-preview.mjs
mvn -Dtest=PaperControllerContractTest test
pnpm --dir frontend build

PAPERLOOM_BENCHMARK_ITERATIONS=5 \
PAPERLOOM_BENCHMARK_STORAGE=/tmp/paperloom-pdf-benchmark-storage.json \
PAPERLOOM_BENCHMARK_OUTPUT="$PWD/docs/performance/pdf-preview-standard-range-2026-08-11.json" \
pnpm --dir frontend exec node scripts/benchmark-pdf-preview.mjs
```

部署验证还要分别探测直连后端、Nginx 和公网；三层都必须返回 `206` 和正确的 `Content-Range`，否则应用
测试通过也不能证明标准 Range 在线上生效。
