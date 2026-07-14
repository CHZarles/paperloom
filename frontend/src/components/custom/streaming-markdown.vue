<script setup lang="ts">
import MarkdownIt from 'markdown-it';

defineOptions({ name: 'StreamingMarkdown' });

const props = defineProps<{
  content: string;
}>();

const markdown = new MarkdownIt({
  breaks: true,
  html: false,
  linkify: true
});

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

const renderedContent = computed(() => markdown.render(props.content || ''));
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
}

.streaming-markdown :deep(code) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}
</style>
