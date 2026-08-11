# PDF 首屏串行往返优化报告

日期：2026-08-11  
状态：已部署并完成线上前后对照  
优化提交：`34f40ec perf(pdf): skip redundant preview descriptor`

## 1. 背景

项目在 2026-07-26 已完成一轮 PDF 预览优化：后端权限查询改成单篇直查，Range 响应从 JSON/Base64 改为
二进制，前端增加 Metadata/Range 缓存。那轮工作解决了已知架构问题，但没有留下线上浏览器首屏延迟的
前后量化数据。

本轮没有先假定 Canvas 慢，而是从真实浏览器建立端到端基准，再按时间占比选择优化点。

## 2. 指标定义

核心指标定义为 First Page Ready：

```text
t0 = Chrome 点击 Preview 前的 performance.now()
t1 = PDF canvas 宽高均大于 0，且文档加载提示和页面渲染遮罩均已消失
First Page Ready = t1 - t0
```

测试同时通过浏览器 Resource Timing 记录预览链路请求数、传输字节、单请求耗时和最后一个关键网络响应
完成时间。

- 冷打开：每次使用新的 Browser Context，让 PDF Loader 的内存缓存为空；
- 热打开：在同一页面关闭后再次打开同一 PDF，保留 Loader 缓存；
- 每组执行 5 次，主要比较中位数；
- 浏览器会话写入临时 Storage State，前后测试复用同一游客身份，避免反复创建游客用户。

## 3. 测试环境

| 项目 | 配置 |
| --- | --- |
| 站点 | `https://paperloom.me` |
| PDF | `02-GPT-1.pdf` |
| Chrome | Headless Chrome 151 |
| 机器 | Apple M5，10 个逻辑核心，32 GB 内存，arm64 macOS |
| 视口 | 1440 x 1000 |
| 网络 | 当前真实公网，不做人为限速 |
| 样本 | 冷打开 5 次，热打开 5 次 |

原始数据：

- [优化前数据](pdf-preview-baseline-2026-08-11.json)
- [优化后数据](pdf-preview-optimized-2026-08-11.json)
- [可重复执行的 Chrome 脚本](../../frontend/scripts/benchmark-pdf-preview.mjs)

## 4. 优化前基准

| 场景 | 首屏中位数 | 首屏最大值 | 关键请求数 | 关键传输字节 |
| --- | ---: | ---: | ---: | ---: |
| 冷打开 | 3058.8 ms | 3220.6 ms | 3 | 541639 |
| 热打开 | 1379.5 ms | 1482.1 ms | 1 | 292 |

冷打开的关键网络完成时间中位数为 2539.4 ms，网络完成后渲染中位数约为 502.6 ms。约 83% 的首屏时间
位于网络链路，因此没有优先改 Canvas、Text Layer 或页面摘要算法。

## 5. 假设与验证

| 排名 | 假设 | 可证伪预测 | 结果 |
| --- | --- | --- | --- |
| 1 | PDF 打开前存在无信息增量的串行请求 | 跳过该请求后，关键请求数和中位数下降 | 确认 |
| 2 | 弹窗动画和异步组件让首个请求启动偏晚 | 提前加载组件后首个请求更早开始 | 未实施 |
| 3 | Text Layer 和页面摘要拖慢首屏 | 延后非首屏工作后，网络后渲染时间下降 | 未实施 |

源码链路证明排名 1：调用方已经持有 `paperId` 和 `.pdf` 文件名，但仍先请求：

```text
GET /papers/{paperId}/preview
```

后端对 PDF 只根据同一个 `paperId` 返回：

```text
/api/v1/papers/{paperId}/preview/pdf-data
```

随后前端才请求 Metadata 和 Range。第一个请求没有提供调用方未知的信息，却阻塞了后续请求。

## 6. 优化方案

在 `file-preview.vue` 增加 PDF 快速路径：

```text
文件扩展名是 PDF
-> 使用 encodeURIComponent(paperId) 直接构造 pdf-data 地址
-> 跳过通用 preview descriptor 请求

其他文件类型
-> 保留原有 preview descriptor 流程
```

生产逻辑只增加 6 行，没有增加依赖、缓存模型或后端接口。

### 可靠性边界

- 仅明确的 `.pdf` 文件进入快速路径；判断失败只会回退旧流程；
- `/pdf-data` 本身执行论文访问校验，跳过 descriptor 不会跳过后端安全边界；
- PaperLoom 当前上传边界只接受 PDF，且知识库调用方传入原始文件名；
- 非 PDF 的文本、图片和下载预览行为没有变化。

## 7. RED-GREEN 验证

先增加 `file-preview-performance-contract.test.ts`，要求 PDF 快速路径位于通用请求之前，并准确构造带编码的
Metadata 地址。测试先因快速路径不存在而失败，加入最小实现后转为通过。

```bash
pnpm --dir frontend exec tsx tests/file-preview-performance-contract.test.ts
pnpm --dir frontend exec tsx tests/pdf-preview-loader.test.ts
pnpm --dir frontend typecheck
pnpm --dir frontend build
pnpm --dir frontend exec eslint \
  src/components/custom/file-preview.vue \
  tests/file-preview-performance-contract.test.ts \
  scripts/benchmark-pdf-preview.mjs
```

结果：契约测试、PDF Loader 测试、类型检查、目标文件 ESLint、生产构建和 Bundle Budget 全部通过。

## 8. 线上结果

| 指标 | 优化前 | 优化后 | 变化 |
| --- | ---: | ---: | ---: |
| 冷打开首屏中位数 | 3058.8 ms | 2561.0 ms | -497.8 ms，-16.3% |
| 热打开首屏中位数 | 1379.5 ms | 886.1 ms | -493.4 ms，-35.8% |
| 冷打开关键网络中位数 | 2539.4 ms | 2111.2 ms | -428.2 ms，-16.9% |
| 冷打开请求数 | 3 | 2 | -1 |
| 热打开请求数 | 1 | 0 | -1 |
| 冷打开传输字节 | 541639 | 541347 | -292 B |
| 热打开传输字节 | 292 | 0 | -292 B |

收益不是来自减少 PDF 字节，而是删除一次约 400 ms 的串行鉴权/响应往返，让 Metadata 和 Range 更早开始。
冷、热打开都减少约 0.5 秒，与根因预测一致。

## 9. 不能夸大的部分

- 冷打开最大值从 3220.6 ms 上升到 3450.7 ms，因为一次 Range 请求耗时 1440.4 ms；本轮不能声称冷启动
  尾延迟改善；
- 每组只有 5 个样本，所谓 p95 实际接近最大值，不足以做严格分位数结论；
- 数据只覆盖一份约 541 KB 的真实 PDF、一台机器和当前公网，不能代表所有文件大小和用户网络；
- 本轮优化没有减少 PDF 解析或 Canvas 绘制成本，也没有减少 Range 主体数据；
- 当前新的主要瓶颈是冷打开的 `/preview/pdf-data/range`，耗时在 945.3 到 1440.4 ms 之间。继续优化前应
  先分别测服务端读取、Cloudflare 传输和浏览器接收，不能直接把问题归因于 MinIO 或网络。

## 10. 复现

```bash
PAPERLOOM_BENCHMARK_ITERATIONS=5 \
PAPERLOOM_BENCHMARK_STORAGE=/tmp/paperloom-pdf-benchmark-storage.json \
PAPERLOOM_BENCHMARK_OUTPUT=/tmp/paperloom-pdf-result.json \
pnpm --dir frontend exec node scripts/benchmark-pdf-preview.mjs
```

脚本默认访问 `https://paperloom.me`，也可以通过 `PAPERLOOM_BENCHMARK_BASE_URL` 指定环境，通过
`PAPERLOOM_CHROME_PATH` 指定 Chrome。导航发生临时断连时最多重试 3 次；计时窗口内不重试 PDF 请求，
避免把失败样本悄悄美化。

## 11. 面试 Story

> 我在线上发现 PDF 首次显示约需 3 秒。没有先凭感觉改 pdf.js，而是用 Playwright 驱动真实 Chrome，定义
> “点击 Preview 到 Canvas 有尺寸且渲染遮罩消失”为 First Page Ready，对同一 PDF 做 5 组冷、热打开，
> 并用 Resource Timing 拆分网络与渲染。数据显示冷打开中位数 3059 ms，其中约 2539 ms 在网络链路。
> 沿请求链定位到前端已知 paperId 和 PDF 类型，却仍先请求 descriptor，而后端只返回可直接计算的
> pdf-data 地址，形成无信息增量的串行往返。我增加 PDF 快速路径，非 PDF 保持原流程，后端 pdf-data
> 继续鉴权。部署后同条件复测，冷打开中位数降到 2561 ms，提升 16.3%；热打开从 1380 ms 降到 886 ms，
> 提升 35.8%。同时我保留了边界：样本只有一份 PDF，冷启动最大值没有改善，下一瓶颈是 Range 请求，不能
> 把中位数结果包装成全量用户的 p95 提升。

**常见追问：**为什么用中位数、冷缓存与热缓存区别、为什么不是 Canvas 瓶颈、减少请求和减少字节的区别、
前端快速路径是否绕过鉴权、为何只测 5 次不能声称 p95、下一步如何拆分 Range 延迟。
