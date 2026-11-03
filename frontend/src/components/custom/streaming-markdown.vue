<script lang="ts">
import { onBeforeUnmount, shallowRef, watch } from 'vue';
import MarkdownIt from 'markdown-it';
import { configureResearchMarkdown, normalizeLegacyDisplayMathCitations } from '@/utils/research-markdown';

const STREAMING_MARKDOWN_RENDER_INTERVAL_MS = 60;
const STREAM_CARET_HTML = '<span class="stream-caret" aria-hidden="true"></span>';
const CLOSING_BLOCK_TAGS = [
  '</p>',
  '</li>',
  '</h1>',
  '</h2>',
  '</h3>',
  '</h4>',
  '</h5>',
  '</h6>',
  '</td>',
  '</th>',
  '</dd>',
  '</dt>',
  '</blockquote>',
  '</pre>'
];

const markdown = new MarkdownIt({
  breaks: true,
  html: false,
  linkify: true
});
configureResearchMarkdown(markdown);

const defaultLinkOpen = markdown.renderer.rules.link_open;
// markdown-it renderer rules use this five-argument signature.
// eslint-disable-next-line max-params
markdown.renderer.rules.link_open = (tokens, index, options, environment, renderer) => {
  tokens[index].attrSet('target', '_blank');
  tokens[index].attrSet('rel', 'noreferrer');
  return defaultLinkOpen
    ? defaultLinkOpen(tokens, index, options, environment, renderer)
    : renderer.renderToken(tokens, index, options);
};
</script>

<script setup lang="ts">
defineOptions({ name: 'StreamingMarkdown' });

const props = defineProps<{
  content: string;
  streaming?: boolean;
}>();

const renderedContent = shallowRef('');
let pendingContent = '';
let lastRenderedAt = 0;
let renderTimer: number | null = null;

function clearRenderTimer() {
  if (renderTimer === null) return;
  window.clearTimeout(renderTimer);
  renderTimer = null;
}

// The caret sits inside the last block element so it trails the final
// character instead of dropping onto its own line.
function appendStreamCaret(html: string) {
  if (!props.streaming) return html;
  const trimmed = html.replace(/\s+$/, '');
  if (!trimmed) return STREAM_CARET_HTML;
  // The last closing block tag is the end of the visible text, even when the
  // block sits inside a list or a table wrapper.
  let insertAt = -1;
  for (const tag of CLOSING_BLOCK_TAGS) {
    const index = trimmed.lastIndexOf(tag);
    if (index > insertAt) insertAt = index;
  }
  if (insertAt < 0) return `${trimmed}${STREAM_CARET_HTML}`;
  return `${trimmed.slice(0, insertAt)}${STREAM_CARET_HTML}${trimmed.slice(insertAt)}`;
}

function renderContent(value: string) {
  renderedContent.value = appendStreamCaret(markdown.render(normalizeLegacyDisplayMathCitations(value || '')));
  lastRenderedAt = Date.now();
}

function scheduleRender(value: string) {
  pendingContent = value || '';
  const elapsed = Date.now() - lastRenderedAt;
  const delay = Math.max(0, STREAMING_MARKDOWN_RENDER_INTERVAL_MS - elapsed);

  if (delay === 0) {
    clearRenderTimer();
    renderContent(pendingContent);
    return;
  }

  if (renderTimer !== null) return;
  renderTimer = window.setTimeout(() => {
    renderTimer = null;
    renderContent(pendingContent);
  }, delay);
}

watch(() => props.content, scheduleRender, { immediate: true });

watch(
  () => props.streaming,
  () => {
    clearRenderTimer();
    renderContent(pendingContent || props.content);
  }
);

onBeforeUnmount(clearRenderTimer);
</script>

<template>
  <!-- markdown-it runs with raw HTML disabled; this is the lightweight streaming renderer. -->
  <!-- eslint-disable-next-line vue/no-v-html -->
  <div class="streaming-markdown vp-doc" v-html="renderedContent" />
</template>

<style scoped>
.streaming-markdown {
  overflow-wrap: anywhere;
}

.streaming-markdown :deep(pre) {
  overflow-x: auto;
  border-radius: 6px;
  background: var(--color-surface-alt);
  padding: 12px;
  white-space: pre;
  word-break: normal;
  overflow-wrap: normal;
  tab-size: 4;
}

.streaming-markdown :deep(code) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}

.streaming-markdown :deep(.research-preformatted-block) {
  display: block;
  overflow-x: auto;
  border: 1px solid var(--color-border-soft);
  border-radius: 6px;
  background: var(--color-surface-alt);
  margin: 0 0 12px;
  padding: 12px 14px;
  color: var(--color-text);
  font-family: var(--font-utility);
  font-size: 14px;
  line-height: 1.5;
  white-space: pre;
  word-break: normal;
  overflow-wrap: normal;
  tab-size: 4;
}

.streaming-markdown :deep(.research-preformatted-block),
.streaming-markdown :deep(.research-preformatted-block *) {
  color: var(--color-text) !important;
}
</style>
