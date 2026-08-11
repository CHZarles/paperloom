import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const currentDir = dirname(fileURLToPath(import.meta.url));
const previewSource = readFileSync(resolve(currentDir, '../src/components/custom/file-preview.vue'), 'utf8');
const viewerSource = readFileSync(resolve(currentDir, '../src/components/custom/pdf-document-viewer.vue'), 'utf8');
const librarySource = readFileSync(resolve(currentDir, '../src/views/knowledge-base/index.vue'), 'utf8');

const directPdfBranch = previewSource.indexOf("if (getFileExt(displayTitle.value)?.toLowerCase() === 'pdf')");
const genericPreviewRequest = previewSource.indexOf('const { error: requestError, data } = await request');

assert.ok(directPdfBranch >= 0, 'known PDF previews should bypass the generic preview descriptor request');
assert.ok(directPdfBranch < genericPreviewRequest, 'the direct PDF path must run before the generic preview request');
assert.match(
  previewSource,
  /previewUrl\.value = `\/api\/v1\/papers\/\$\{encodeURIComponent\(targetPaperId\)\}\/preview\/pdf-data`/,
  'the direct PDF path should use the authenticated range metadata endpoint'
);

const documentReady = viewerSource.indexOf('const documentProxy = await loadingTask.promise');
const firstRender = viewerSource.indexOf('await forceRender(currentToken)', documentReady);
const initialRenderFlow = viewerSource.slice(documentReady, firstRender);

assert.ok(
  initialRenderFlow.indexOf('documentLoading.value = false') < initialRenderFlow.indexOf('await waitForStageReady'),
  'the PDF shell must be mounted before waiting for it to become renderable'
);

assert.match(
  librarySource,
  /const loadFilePreview = \(\) => import\('@\/components\/custom\/file-preview\.vue'\);[\s\S]*defineAsyncComponent\(loadFilePreview\)/,
  'the library should reuse one loader for conditional PDF viewer preloading'
);
assert.match(
  librarySource,
  /function preloadPdfPreviewAssets\(\) {[\s\S]*loadFilePreview\(\)[\s\S]*function schedulePdfPreviewPreload\(\) {[\s\S]*if \(!tableTasks\.value\.length\) return;[\s\S]*requestIdleCallback\(preloadPdfPreviewAssets/,
  'the library should preload the viewer only after it exposes PDF entries and becomes idle'
);
assert.match(
  librarySource,
  /link\.rel = 'prefetch';[\s\S]*link\.href = pdfWorkerSrc/,
  'conditional preloading should warm the pdf.js worker without starting it'
);
