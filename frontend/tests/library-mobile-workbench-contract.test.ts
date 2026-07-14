import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const currentDir = dirname(fileURLToPath(import.meta.url));
const librarySource = readFileSync(resolve(currentDir, '../src/views/knowledge-base/index.vue'), 'utf8');
const mobileListSource = readFileSync(
  resolve(currentDir, '../src/views/knowledge-base/modules/paper-mobile-list.vue'),
  'utf8'
);

assert.match(
  librarySource,
  /const sidebarCollapsed = ref\(typeof window !== 'undefined' \? window\.innerWidth < 960 : false\)/,
  'the library sidebar should start collapsed on narrow viewports'
);

assert.match(
  librarySource,
  /<PaperMobileList[\s\S]*v-if="isMobileLibrary"/,
  'the library should render a dedicated paper list on mobile'
);

assert.match(
  librarySource,
  /<NDataTable[\s\S]*v-else/,
  'the desktop data table should not remain mounted beneath the mobile paper list'
);

assert.match(mobileListSource, /class="paper-mobile-list"/, 'the mobile paper list needs a stable visual root');
assert.match(mobileListSource, /emit\('preview'/, 'mobile paper rows should preserve the preview action');
