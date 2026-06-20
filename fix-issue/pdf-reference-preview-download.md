# PDF Reference Preview Download Issue

## Problem

In the chat page, clicking a citation link such as `.source-file-link` should open the referenced PDF inside the right-side reference drawer. The observed behavior was:

- The browser appeared to trigger a PDF download.
- The drawer showed `PDF 加载失败，请重新预览或稍后再试。`
- DevTools showed a request like:

```text
GET /proxy-default/documents/page-preview?fileMd5=4bf9fa2add53bc1405679afa5d4dbe3c&pageNumber=1
```

This request itself is expected. The important distinction is whether it is a browser navigation/download or a script fetch.

In the failing reports, the useful request signal was:

```text
Accept: application/pdf
Sec-Fetch-Dest: empty
Sec-Fetch-Mode: cors
Sec-Fetch-Site: same-origin
```

`Sec-Fetch-Dest: empty` means the request is made by script, not by navigating the browser tab to the PDF URL.

## Root Cause

There were two separate issues that looked like one bug.

First, the frontend PDF viewer still had paths that could let PDF URLs be opened like normal browser resources. That is risky because a PDF endpoint with `Content-Disposition` can surface as a browser download or native PDF handling instead of staying inside the app.

Second, the backend source code had been changed to remove the PDF `Content-Disposition` header, but the running Java process on port `8081` had not loaded the new class. Compiling with:

```bash
mvn -q -DskipTests compile
```

updated `target/classes`, but the running backend still returned:

```text
content-disposition: inline; filename*=UTF-8''hw4_finalproject-page-1.pdf
```

Checking `strings target/classes/com/yizhaoqi/smartpai/controller/DocumentController.class` showed the new class no longer contained the filename/content-disposition string, so the mismatch was runtime state, not source code.

The backend had to be restarted for this change to take effect.

## Fix

### 1. Keep PDF preview inside the app

Updated `frontend/src/components/custom/pdf-document-viewer.vue` so PDF.js no longer receives the URL directly through `getDocument({ url })`.

Instead, the viewer now:

1. Calls `fetch(url, { headers: { Accept: 'application/pdf', Authorization }, credentials: 'same-origin' })`.
2. Reads the response as bytes.
3. Validates the first bytes are `%PDF`.
4. Passes the bytes to PDF.js with `getDocument({ data: bytes })`.

This makes `/documents/page-preview` a normal `Fetch` request and prevents browser-level PDF navigation or download handling from becoming part of the preview path.

The PDF viewer also no longer exposes or renders the `新窗口` action for PDF preview.

### 2. Use single-page preview URLs for PDFs

Updated `frontend/src/components/custom/file-preview.vue` so PDF references resolve to:

```text
/api/v1/documents/page-preview?fileMd5=<md5>&pageNumber=<page>
```

when a file MD5 is available.

This avoids handing the original MinIO/download URL to the embedded PDF viewer. The drawer uses the backend-generated single-page PDF snapshot for citation checking.

The explanatory card was also removed:

- `概览`
- `混合召回（语义相关 + 关键词命中）`
- `左侧展示的是本次 RAG 检索的问题与定位线索...`

### 3. Stop citation clicks from opening a separate browser target

Updated `frontend/src/views/chat/modules/chat-message.vue` so citation clicks are handled through event delegation with:

```ts
const sourceTarget = target.closest<HTMLElement>('.source-file-link');
event.preventDefault();
event.stopPropagation();
```

For `/chat`, the component emits `openReference`, and the existing ChatShell opens the right-side drawer.

The fallback path was changed from `window.open(...)` to `router.push(...)` so older/non-drawer contexts do not open a new tab for citation preview.

### 4. Remove `Content-Disposition` from backend page preview

Updated `src/main/java/com/yizhaoqi/smartpai/controller/DocumentController.java` so `/api/v1/documents/page-preview` returns the PDF bytes without:

```text
Content-Disposition
```

The endpoint still returns:

```text
Content-Type: application/pdf
Cache-Control: private, max-age=1800
X-Preview-Mode: single-page
X-Preview-Cache: HIT|MISS
X-Preview-Page: <pageNumber>
```

For PDF preview metadata, `/documents/preview` now returns a `page-preview` URL directly instead of a MinIO original-file URL.

## Runtime Pitfall

The fix did not appear to work until the running backend was restarted.

Evidence before restart:

```text
HTTP/1.1 200 OK
content-disposition: inline; filename*=UTF-8''hw4_finalproject-page-1.pdf
content-type: application/pdf
```

Evidence after restart:

```text
HTTP/1.1 200 OK
content-type: application/pdf
content-length: 147363
```

No `Content-Disposition` header remained.

The file body was valid PDF:

```text
%PDF-1.4
```

## Verification

Backend response was checked with:

```bash
curl -sS -D /tmp/paismart-page-preview.headers \
  -o /tmp/paismart-page-preview.pdf \
  -H 'Accept: application/pdf' \
  -H "Authorization: Bearer <token>" \
  'http://localhost:9527/proxy-default/documents/page-preview?fileMd5=4bf9fa2add53bc1405679afa5d4dbe3c&pageNumber=1'
```

Expected result:

- Status `200`.
- `content-type: application/pdf`.
- No `content-disposition`.
- File starts with `%PDF`.

Browser verification was done through Chromium DevTools Protocol on `http://localhost:9527/#/chat`:

- Clicked the `homework 4` conversation.
- Clicked `.source-file-link`.
- Confirmed the right reference drawer opened.
- Confirmed PDF canvas rendered.

Observed network sequence:

```text
/proxy-default/documents/preview?...       Type: XHR
/proxy-default/documents/page-preview?...  Type: Fetch
```

Observed final state:

```json
{
  "panel": true,
  "canvasWidth": 581,
  "canvasHeight": 751,
  "bodyHasPdfError": false,
  "hasOverview": false,
  "hasNewWindow": false,
  "downloads": []
}
```

Frontend build check:

```bash
pnpm --dir frontend build:test
```

Result: build passed.

## Takeaway

For embedded PDF citation preview, do not let the browser directly navigate to or open the PDF URL. Fetch the PDF bytes as application data, validate them, and pass the bytes to PDF.js. Also verify the running backend, not only the source code, because stale Java runtime state can keep old response headers alive even after `target/classes` has been updated.
