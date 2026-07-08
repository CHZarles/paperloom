import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const currentDir = dirname(fileURLToPath(import.meta.url));
const paperChoiceListSource = readFileSync(
  resolve(currentDir, '../src/views/chat/modules/product-reading-paper-choice-list.vue'),
  'utf8'
);

assert.match(
  paperChoiceListSource,
  /readingAction:\s*'LIST_LOCATIONS'/,
  'paper cards should expose deterministic location listing as an explicit structured action'
);

assert.match(
  paperChoiceListSource,
  /readingAction:\s*'FIND_LOCATIONS'/,
  'paper cards should expose semantic location search as an explicit structured action'
);
