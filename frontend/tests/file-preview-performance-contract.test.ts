import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const currentDir = dirname(fileURLToPath(import.meta.url));
const previewSource = readFileSync(resolve(currentDir, '../src/components/custom/file-preview.vue'), 'utf8');

const directPdfBranch = previewSource.indexOf("if (getFileExt(displayTitle.value)?.toLowerCase() === 'pdf')");
const genericPreviewRequest = previewSource.indexOf('const { error: requestError, data } = await request');

assert.ok(directPdfBranch >= 0, 'known PDF previews should bypass the generic preview descriptor request');
assert.ok(directPdfBranch < genericPreviewRequest, 'the direct PDF path must run before the generic preview request');
assert.match(
  previewSource,
  /previewUrl\.value = `\/api\/v1\/papers\/\$\{encodeURIComponent\(targetPaperId\)\}\/preview\/pdf-data`/,
  'the direct PDF path should use the authenticated range metadata endpoint'
);
