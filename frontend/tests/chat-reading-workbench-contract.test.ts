import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const currentDir = dirname(fileURLToPath(import.meta.url));
const messageSource = readFileSync(resolve(currentDir, '../src/views/chat/modules/chat-message.vue'), 'utf8');
const listSource = readFileSync(resolve(currentDir, '../src/views/chat/modules/chat-list.vue'), 'utf8');
const inputSource = readFileSync(resolve(currentDir, '../src/views/chat/modules/input-box.vue'), 'utf8');

assert.doesNotMatch(messageSource, /evidence-spine/, 'assistant answers should not render a separate evidence spine');
assert.match(messageSource, /class="source-citation-chip/, 'assistant answers should keep inline citation controls');
assert.match(
  messageSource,
  /\.assistant-content\s*\{[\s\S]*border:\s*0;[\s\S]*background:\s*transparent;/,
  'assistant answers should be presented as unframed reading content'
);
assert.match(
  listSource,
  /\.chat-message-stack\s*\{[\s\S]*width:\s*min\(var\(--reading-width\), 100%\)/,
  'chat turns should share the bounded reading column'
);
assert.doesNotMatch(
  inputSource,
  /\.chat-input-wrap--dock\s*\{[^}]*border-top:/,
  'the docked composer should not draw a top frame'
);
assert.match(
  inputSource,
  /\.chat-input-wrap--dock\s*\{[^}]*background:\s*#fff;/,
  'the docked composer area should use a white background'
);
assert.doesNotMatch(
  inputSource,
  /\.chat-input-shell\s*\{[^}]*(?:border:|box-shadow:)/,
  'the composer should not draw an outer frame'
);
assert.match(
  inputSource,
  /\.chat-input-shell\s*\{[^}]*border-radius:\s*24px;[^}]*background:\s*#f5f5f5\s*!important;/,
  'the composer should use the soft rounded DeepSeek-style surface'
);
assert.match(
  inputSource,
  /\.chat-input-textarea:focus,[\s\S]*\.chat-input-textarea:focus-visible\s*\{[^}]*outline:\s*none\s*!important;/,
  'focusing the composer should not draw the global green outline'
);
