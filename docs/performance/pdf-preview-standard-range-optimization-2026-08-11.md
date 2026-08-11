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

## 9. 前端阶段定位：等待尚未挂载的容器

标准 Range 上线后，用户仍认为约 2 秒的首屏很慢。没有直接把 `renderAfterNetworkMs` 全部归因于 pdf.js，
而是在真实线上组件的以下边界加入 `performance.mark`：

```text
组件开始加载 -> PDF 数据就绪 -> 文档就绪 -> 页面对象就绪 -> Canvas 就绪 -> 文字层就绪
```

5 组冷、热打开的阶段中位数如下：

| 阶段 | 冷打开 | 热打开 |
| --- | ---: | ---: |
| Viewer 挂载前 | 267.9 ms | 395.1 ms |
| PDF 数据获取 | 1138.2 ms | 3.1 ms |
| 文档初始化 | 607.5 ms | 86.9 ms |
| 文档就绪到页面对象就绪 | **386.3 ms** | **387.8 ms** |
| Canvas 绘制 | 27.6 ms | 24.6 ms |
| 文字层绘制 | 17.9 ms | 12.0 ms |

`386 ms` 在冷热场景中都稳定出现，而 Canvas 和文字层合计只有约 `46 ms`，因此“图片可以省掉全部
`0.8 s` 渲染时间”的假设不成立。

沿 `loadDocument` 调用链定位到顺序错误：模板只有在 `documentLoading=false` 时才挂载
`.pdf-page-shell`，代码却先调用 `waitForStageReady()` 等待该节点，再把 `documentLoading` 改为 false。
节点不可能出现，函数只能等待上限 `24` 个动画帧后退出：

```text
24 帧 x 约 16 ms = 约 384 ms
```

修复只调整顺序：先结束文档加载态，`nextTick` 挂载页面容器，再等待容器尺寸稳定并开始渲染。没有删除真正
的尺寸稳定检查，也没有改变 PDF、文字层或证据框逻辑。契约测试先证明旧顺序失败，再约束“挂载必须发生在
等待之前”。

同条件线上复测后：

| 指标 | 修复前 | 修复后 | 变化 |
| --- | ---: | ---: | ---: |
| 热打开首屏中位数 | 915.3 ms | 528.7 ms | `-386.6 ms`，`-42.2%` |
| 文档就绪到页面对象就绪 | 387.8 ms | 1.9 ms | `-385.9 ms` |
| 热打开 PDF 请求 | 0 | 0 | 不变 |

热打开隔离了网络变量，端到端收益与被删除的等待时间一致，支持根因判断。冷打开阶段等待也从 `386.3 ms`
降到 `3.0 ms`，但本轮冷打开总中位数从 `2538.3 ms` 波动到 `2993.3 ms`：请求、动态组件挂载和 pdf.js
冷初始化出现秒级抖动，因此不能声称本轮改善了冷打开总耗时，也不能用热缓存结果冒充冷启动收益。

原始数据：

- [修复前阶段数据](pdf-preview-phase-baseline-2026-08-11.json)
- [修复后阶段数据](pdf-preview-phase-optimized-2026-08-11.json)

前端修复提交：`36d7e43 perf(pdf): mount render shell before readiness wait`。

## 10. 条件预加载实验

阶段数据继续显示，第一次点击后才加载 PDF Viewer 和 pdf.js Worker：生产构建中的原始文件分别约
`540 KB` 和 `1.2 MB`，线上压缩传输实际约为：

```text
Viewer JS + CSS：172010 B
Worker URL 包装：76 B
pdf.js Worker：390282 B
合计：562368 B
```

没有采用登录后全局预加载。实验只在用户进入文献库、论文列表非空且浏览器空闲时触发：动态导入 Viewer，
并用原生 `prefetch` 缓存 Worker。它不创建 Worker、不下载论文 PDF，也不会影响没有进入文献库的用户。

实验前先定义保留标准：在相同条件下，首次打开至少改善 `300 ms`，否则删除。为了给空闲预加载留出公平且
可复现的窗口，前后测试都在论文列表出现后等待 `3000 ms` 再点击 Preview；其余环境与前述基准一致，
各执行 5 次。

| 指标 | 无预加载 | 条件预加载 | 变化 |
| --- | ---: | ---: | ---: |
| 冷打开首屏中位数 | 1958.5 ms | 1239.0 ms | `-719.5 ms`，`-36.7%` |
| 点击到 Viewer 开始 | 277.9 ms | 66.6 ms | `-211.3 ms` |
| pdf.js 文档初始化 | 397.8 ms | 66.3 ms | `-331.5 ms` |
| PDF 网络完成 | 1272.0 ms | 1076.5 ms | `-195.5 ms`，公网波动 |
| 热打开首屏中位数 | 515.1 ms | 544.3 ms | `+29.2 ms`，未改善 |

Viewer 启动和文档初始化合计减少约 `542.8 ms`，超过预先设定的 `300 ms` 保留线；浏览器记录也确认
点击前已经加载 Viewer、CSS 和 Worker。总耗时减少的其余约 `196 ms` 与 PDF 请求波动一致，不能归因于
预加载。热打开本来就已有模块和 Worker 缓存，因此没有收益，符合预测。

本轮保留条件预加载，但边界必须说明：

- 当前只覆盖文献库，不是登录后全局预加载；
- 聊天引用场景尚未接入，不能声称全站首次 PDF 打开都提升；
- 用户进入文献库但不预览，会额外下载约 `562 KB` 压缩资源；
- 收益依赖预加载完成，测试中的空闲窗口是 3 秒；立即点击时收益会更小；
- 只测试了当前 Chrome、公网和一份 PDF，不报告 p95。

原始数据：

- [条件预加载前](pdf-preview-conditional-preload-baseline-2026-08-11.json)
- [条件预加载后](pdf-preview-conditional-preload-optimized-2026-08-11.json)

实现提交：`a282aa3 perf(pdf): preload viewer assets when library is idle`。
