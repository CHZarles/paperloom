import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const currentDir = dirname(fileURLToPath(import.meta.url));
const chatStoreSource = readFileSync(resolve(currentDir, '../src/store/modules/chat/index.ts'), 'utf8');
const inputBoxSource = readFileSync(resolve(currentDir, '../src/views/chat/modules/input-box.vue'), 'utf8');

assert.match(
  chatStoreSource,
  /url:\s*'chat\/active-generation'[\s\S]*params:\s*\{\s*clientId:\s*chatClientId\s*\}/,
  'active generation resume lookup should be scoped to the browser client id'
);

assert.match(
  chatStoreSource,
  /assistant\.conversationRecordId\s*=\s*snapshot\.conversationRecordId/,
  'active generation snapshots must restore the durable conversation record id for citation detail clicks'
);

assert.match(
  inputBoxSource,
  /assistant\.conversationRecordId\s*=\s*payload\.conversationRecordId/,
  'completion payloads must attach the durable conversation record id before citation chips are clicked'
);
