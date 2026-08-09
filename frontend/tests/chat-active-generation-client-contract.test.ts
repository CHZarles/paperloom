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
  /window\.sessionStorage\.getItem\(CHAT_CLIENT_ID_STORAGE_KEY\)[\s\S]*window\.sessionStorage\.setItem\(CHAT_CLIENT_ID_STORAGE_KEY, clientId\)/,
  'browser client id must survive a page refresh so active generation resume keeps its client scope'
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

assert.match(
  chatStoreSource,
  /function loadConversationDetails[\s\S]{0,800}fetchActiveGenerationSnapshot\(\)[\s\S]{0,400}snapshot\.conversationId\s*===\s*targetConversationId/,
  'loadConversationDetails must refetch the active generation snapshot and restore it when the snapshot belongs to the loaded conversation, so an in-flight answer survives a session switch and switch-back'
);
