import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const currentDir = dirname(fileURLToPath(import.meta.url));
const inputBoxSource = readFileSync(resolve(currentDir, '../src/views/chat/modules/input-box.vue'), 'utf8');

assert.match(
  inputBoxSource,
  /(const|let)\s+targetConversationId\s*=/,
  'chat input should resolve a concrete target conversation before sending'
);

assert.match(
  inputBoxSource,
  /conversationId:\s*targetConversationId/,
  'WebSocket user_message payload should include the visible conversationId'
);
