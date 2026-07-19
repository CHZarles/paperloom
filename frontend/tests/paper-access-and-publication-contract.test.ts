import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const currentDir = dirname(fileURLToPath(import.meta.url));
const uploadDialogSource = readFileSync(
  resolve(currentDir, '../src/views/knowledge-base/modules/upload-dialog.vue'),
  'utf8'
);
const uploadStoreSource = readFileSync(resolve(currentDir, '../src/store/modules/knowledge-base/index.ts'), 'utf8');
const librarySource = readFileSync(resolve(currentDir, '../src/views/knowledge-base/index.vue'), 'utf8');

assert.doesNotMatch(
  uploadDialogSource,
  /orgTag|isPublic/,
  'paper upload must not expose organization or public controls'
);
assert.doesNotMatch(uploadStoreSource, /orgTag|isPublic/, 'paper upload requests must not send visibility fields');
assert.match(librarySource, /\/processing\/retry/, 'failed initial processing must use the processing retry route');
assert.match(
  librarySource,
  /row\.processingStatus === 'FAILED'/,
  'ordinary retry must be limited to failed initial processing'
);
assert.match(librarySource, /\/admin\/papers\/\$\{row\.paperId\}\/publication/, 'admins need publication actions');
assert.match(librarySource, /Global/, 'the library must display global scope');
assert.match(librarySource, /Private/, 'the library must display private scope');
