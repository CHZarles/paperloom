import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const currentDir = dirname(fileURLToPath(import.meta.url));
const chatSurfaceFiles = [
  '../src/views/chat/index.vue',
  '../src/views/chat/modules/conversation-sidebar.vue',
  '../src/views/chat/modules/chat-list.vue'
];

const chatSurfaceSource = chatSurfaceFiles.map(file => readFileSync(resolve(currentDir, file), 'utf8')).join('\n');

const blockedEmptyStateCopy = [
  'evidence-grounded paper reading desk',
  'evidence-grounded paper analysis',
  'structured paper reading',
  'Ask papers with cited evidence',
  '围绕论文、PDF、方法、实验和结论提问',
  'citation chip',
  '[PDF]',
  '[METHOD]',
  '[CLAIMS]',
  '[REFS]'
];

for (const copy of blockedEmptyStateCopy) {
  assert.equal(
    chatSurfaceSource.includes(copy),
    false,
    `chat empty state should not contain instructional or marketing copy: ${copy}`
  );
}
