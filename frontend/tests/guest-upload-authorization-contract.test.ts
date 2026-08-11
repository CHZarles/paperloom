import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const currentDir = dirname(fileURLToPath(import.meta.url));
const librarySource = readFileSync(resolve(currentDir, '../src/views/knowledge-base/index.vue'), 'utf8');

assert.match(
  librarySource,
  /const canUploadPapers = computed\(\(\) => authStore\.userInfo\.role !== 'GUEST'\)/,
  'the library must derive upload capability from the authenticated role'
);
assert.match(
  librarySource,
  /function canManageFile[\s\S]*?if \(!canUploadPapers\.value\) return false;/,
  'guests must not receive upload resume or paper management controls'
);
assert.match(
  librarySource,
  /<NButton\s+v-if="canUploadPapers"[\s\S]*?@click="handleUpload"/,
  'guests must not see the upload entry point'
);
