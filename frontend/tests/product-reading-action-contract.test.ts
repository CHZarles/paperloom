import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const currentDir = dirname(fileURLToPath(import.meta.url));
const paperChoiceListSource = readFileSync(
  resolve(currentDir, '../src/views/chat/modules/product-reading-paper-choice-list.vue'),
  'utf8'
);
const artifactsPanelSource = readFileSync(
  resolve(currentDir, '../src/views/chat/modules/product-reading-artifacts-panel.vue'),
  'utf8'
);
const sourceEvidencePanelSource = readFileSync(
  resolve(currentDir, '../src/views/chat/modules/source-evidence-panel.vue'),
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

assert.match(
  artifactsPanelSource,
  /readingAction:\s*'READ_LOCATION'/,
  'reading-plan rows should expose location reading as an explicit structured action'
);

assert.match(
  artifactsPanelSource,
  /locationRef:\s*stringValue\(payload\.locationRef\)/,
  'READ_LOCATION actions must carry the concrete clicked locationRef'
);

assert.match(
  sourceEvidencePanelSource,
  /readingAction:\s*props\.sourceQuoteRef\s*\?\s*'TRACE_SOURCE_QUOTE'\s*:\s*undefined/,
  'source evidence follow-up should force trace_source_quotes through a structured action'
);
