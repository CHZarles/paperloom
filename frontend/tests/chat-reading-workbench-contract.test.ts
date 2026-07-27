import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const currentDir = dirname(fileURLToPath(import.meta.url));
const messageSource = readFileSync(resolve(currentDir, '../src/views/chat/modules/chat-message.vue'), 'utf8');
const listSource = readFileSync(resolve(currentDir, '../src/views/chat/modules/chat-list.vue'), 'utf8');
const inputSource = readFileSync(resolve(currentDir, '../src/views/chat/modules/input-box.vue'), 'utf8');
const indexSource = readFileSync(resolve(currentDir, '../src/views/chat/index.vue'), 'utf8');
const streamingMarkdownSource = readFileSync(
  resolve(currentDir, '../src/components/custom/streaming-markdown.vue'),
  'utf8'
);

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
assert.match(
  listSource,
  /sessionSwitchScrollPending\s*=\s*true;[\s\S]*isFollowingBottom\.value\s*=\s*true;/,
  'switching sessions should force the newly loaded conversation to its latest messages'
);
assert.match(
  listSource,
  /watch\(\s*conversationId,[\s\S]*loadCurrentConversationIfNeeded\(\)\.catch\(\(\) => \{\}\);[\s\S]*flush:\s*'sync'/,
  'switching sessions should load through the cache-aware path instead of forcing a refetch'
);
assert.match(
  listSource,
  /const SESSION_SWITCH_SCROLL_PASSES = 4;/,
  'session switching should keep scrolling for a few render frames while message content settles'
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
assert.match(
  messageSource,
  /class="assistant-progress-strip"/,
  'live retrieval progress should render inline in the assistant message'
);
assert.match(
  messageSource,
  /const INLINE_PROGRESS_EVENT_LIMIT = 4;/,
  'inline retrieval progress should keep the chat page lightweight'
);
assert.doesNotMatch(
  messageSource,
  /\.assistant-progress-strip\s*\{[\s\S]*overflow-y:\s*auto;/,
  'inline retrieval progress should roll latest items without an inner scrollbar'
);
assert.match(
  messageSource,
  /<StreamingMarkdown v-if="assistantIsStreaming"/,
  'active assistant streams should keep the lightweight markdown renderer until websocket completion'
);
assert.match(
  inputSource,
  /const STREAM_FLUSH_INTERVAL_MS = 140;/,
  'streamed answer chunks should be batched enough to avoid markdown render thrash'
);
assert.match(
  inputSource,
  /const RESEARCH_EVENT_FLUSH_INTERVAL_MS = 160;/,
  'research progress events should be batched before mutating chat state'
);
assert.match(
  streamingMarkdownSource,
  /<script lang="ts">[\s\S]*const markdown = new MarkdownIt/,
  'the streaming markdown renderer should be shared at module scope'
);
assert.match(
  streamingMarkdownSource,
  /const STREAMING_MARKDOWN_RENDER_INTERVAL_MS = 140;/,
  'streaming markdown renders should be throttled'
);
assert.doesNotMatch(indexSource, /transition:\s*width\b/, 'PDF review panel resizing should not animate layout width');
