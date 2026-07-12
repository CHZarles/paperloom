import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const currentDir = dirname(fileURLToPath(import.meta.url));
const chatStoreSource = readFileSync(resolve(currentDir, '../src/store/modules/chat/index.ts'), 'utf8');
const inputBoxSource = readFileSync(resolve(currentDir, '../src/views/chat/modules/input-box.vue'), 'utf8');
const sidebarSource = readFileSync(resolve(currentDir, '../src/views/chat/modules/conversation-sidebar.vue'), 'utf8');

// loadSessions should accept a silent flag so background refreshes (e.g. after
// sending a chat message) do not toggle the sidebar loading spinner.
assert.match(
  chatStoreSource,
  /async\s+function\s+loadSessions\s*\([^)]*\{[^}]*silent/s,
  'chat store loadSessions should accept an options bag with a silent flag'
);

assert.match(
  chatStoreSource,
  /if\s*\(\s*!silent\s*\)\s*\{?\s*sessionsLoading\.value\s*=\s*true/,
  'loadSessions should only flip sessionsLoading on when not silent'
);

assert.match(
  chatStoreSource,
  /if\s*\(\s*!silent\s*\)\s*\{?\s*sessionsLoading\.value\s*=\s*false/,
  'loadSessions should only flip sessionsLoading off when not silent'
);

// The message-start handler should opt into the silent refresh so the sidebar
// does not show its spinner while the user is actively chatting.
assert.match(
  inputBoxSource,
  /chatStore\.loadSessions\(\s*\{\s*silent:\s*true\s*\}\s*\)/,
  'input-box message-start handler should refresh sessions silently'
);

// Sidebar should still drive the spinner from sessionsLoading (regression guard).
assert.match(
  sidebarSource,
  /<NSpin\s+[^>]*:show="sessionsLoading"/,
  'conversation sidebar should keep showing the spinner bound to sessionsLoading'
);
