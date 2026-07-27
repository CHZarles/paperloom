# PDF Preview Loading Optimization

Date: 2026-07-26

## Problem

Chat 里打开 PDF evidence / 知识库里预览 PDF 时体感卡。这个链路不只是前端渲染慢，还会在每个 PDF range 请求上重复做后端权限查询和 JSON/base64 编解码。

## Reproduction Signal

用现有契约测试和前端 loader 测试复现关键链路：

1. `PdfDocumentViewer` 通过 `createPdfPreviewSource` 先请求 `/papers/{paperId}/preview/pdf-data` 拿 range metadata。
2. pdf.js 再按需请求 `/papers/{paperId}/preview/pdf-data/range?begin=...&end=...`。
3. range 请求旧实现返回 JSON + `contentBase64`，浏览器每块都要解析 JSON、base64 解码。
4. 认证用户每次 range 请求都会进入 `findAccessiblePaper`，旧实现先加载整份 accessible paper list，再在内存里筛当前 `paperId`。

## Root Cause

主要卡点有三个：

1. 后端权限校验过重：单个 PDF range chunk 只需要确认一个 `paperId` 是否可访问，却加载用户可访问论文全集。
2. range payload 过重：二进制 PDF chunk 被包成 JSON/base64，体积增加约 33%，还多一次 JS 解码。
3. 前端没有完成态缓存：inline evidence viewer 打开后，再打开 large view 或再次查看同一 PDF，会重新请求 metadata 和相同 range。

## Optimization Plan

1. 后端加一个直接 paper lookup：先查用户自己的 `paperId`，没有再查 published paper，不再为单个 PDF 请求扫描整个库。
2. PDF range endpoint 支持 `Accept: application/pdf` 时直接返回 binary `206 Partial Content`，保留 JSON/base64 作为兼容路径。
3. 默认 range chunk 从 256 KiB 提到 1 MiB，减少 pdf.js 初始化阶段的 HTTP 往返次数。
4. 前端 `pdf-preview-loader` 缓存最近 PDF metadata 和 range chunks，避免同一 tab 内重复打开同一 PDF 时重新打后端。
5. 缓存按 token/url 隔离，range cache 上限 48 MiB，metadata cache 上限 16 项。

## Implementation

Changed files:

- `src/main/java/io/github/chzarles/paperloom/service/PaperAccessService.java`
  - 新增 `findAccessiblePaper(userId, paperId)`，直接查单篇论文权限。

- `src/main/java/io/github/chzarles/paperloom/controller/PaperController.java`
  - PDF preview/download 的认证用户路径改用直接 lookup。
  - `/preview/pdf-data/range` 支持 binary PDF chunk 响应。
  - preview metadata 的 `chunkSizeBytes` 改为 1 MiB。

- `frontend/src/components/custom/pdf-preview-loader.ts`
  - 新增 metadata LRU cache。
  - 新增 range chunk LRU cache 和 in-flight 去重。
  - range 请求优先接受 `application/pdf`，仍兼容 JSON/base64。

- `frontend/tests/pdf-preview-loader.test.ts`
  - 覆盖 metadata/range 完成态缓存。
  - 覆盖 binary range chunk 解码。

- `src/test/java/io/github/chzarles/paperloom/controller/PaperControllerContractTest.java`
  - 覆盖 1 MiB chunk metadata。
  - 覆盖 binary range response headers/body。
  - 覆盖认证 PDF preview 不再加载整份 library。

- `src/test/java/io/github/chzarles/paperloom/service/PaperAccessServiceTest.java`
  - 覆盖 direct lookup 不走 `findByUserId` / `findAll`。

## Verification

Commands:

```bash
pnpm --dir frontend exec tsx tests/pdf-preview-loader.test.ts
mvn -q -Dtest=PaperControllerContractTest,PaperAccessServiceTest test
pnpm --dir frontend typecheck
git diff --check
```

Result:

- Frontend PDF loader test passed.
- Backend Paper controller/access tests passed.
- Frontend typecheck passed.
- Diff whitespace check passed.

## Expected UX

首次打开一个 PDF 仍会按需拉 metadata 和必要 range，但每块更少往返、少 JSON/base64 开销，后端也不会为每个 chunk 扫整份论文库。同一个浏览器 tab 里重复打开同一 PDF evidence 或从 inline 切到 large view，会直接复用已拿到的 metadata/range chunks。
