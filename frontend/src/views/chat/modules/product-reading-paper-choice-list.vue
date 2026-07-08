<script setup lang="ts">
defineOptions({ name: 'ProductReadingPaperChoiceList' });

const props = defineProps<{
  items?: Api.Chat.ProductStateItem[] | null;
}>();

const chatStore = useChatStore();
const paperHandlePattern = /^paper_handle_[A-Za-z0-9_-]+$/;
const paperChoiceSourceTools = new Set(['list_papers', 'search_paper_candidates', 'find_papers_by_identity']);

function isPaperChoice(item: Api.Chat.ProductStateItem): item is Api.Chat.ReadingPaperChoiceItem {
  return (
    item.kind === 'READING_PAPER_CHOICE' &&
    paperHandlePattern.test(item.paperHandle || '') &&
    paperChoiceSourceTools.has(item.sourceTool)
  );
}

const choices = computed(() => (props.items || []).filter(isPaperChoice));

function compactMeta(item: Api.Chat.ReadingPaperChoiceItem) {
  const parts: string[] = [];
  const authors = (item.authors || []).filter(Boolean).slice(0, 3).join(', ');
  if (authors) parts.push(authors);
  if (item.year) parts.push(String(item.year));
  if (item.venue) parts.push(item.venue);
  return parts.join(' · ');
}

function displayTitle(item: Api.Chat.ReadingPaperChoiceItem) {
  return item.title || item.originalFilename || 'Untitled paper';
}

function selectPaper(item: Api.Chat.ReadingPaperChoiceItem) {
  chatStore.setReferenceFocus({
    paperHandle: item.paperHandle,
    paperTitle: item.title || undefined,
    originalFilename: item.originalFilename || undefined
  });
  if (!chatStore.input.message.trim()) {
    chatStore.input.message = '看这篇论文';
  }
}

function listLocations(item: Api.Chat.ReadingPaperChoiceItem) {
  chatStore.setReferenceFocus({
    paperHandle: item.paperHandle,
    paperTitle: item.title || undefined,
    originalFilename: item.originalFilename || undefined,
    readingAction: 'LIST_LOCATIONS'
  });
  if (!chatStore.input.message.trim()) {
    chatStore.input.message = '列出这篇论文可阅读的位置';
  }
}

function findInPaper(item: Api.Chat.ReadingPaperChoiceItem) {
  chatStore.setReferenceFocus({
    paperHandle: item.paperHandle,
    paperTitle: item.title || undefined,
    originalFilename: item.originalFilename || undefined,
    readingAction: 'FIND_LOCATIONS'
  });
}
</script>

<template>
  <div v-if="choices.length" class="paper-choice-list">
    <div v-for="item in choices" :key="item.paperHandle" class="paper-choice-row">
      <div class="paper-choice-row__main">
        <div class="paper-choice-row__title">{{ displayTitle(item) }}</div>
        <div class="paper-choice-row__meta">
          <span v-if="item.originalFilename" class="paper-choice-row__filename">{{ item.originalFilename }}</span>
          <span v-if="compactMeta(item)">{{ compactMeta(item) }}</span>
        </div>
        <div v-if="item.matchReasons?.length || item.ambiguous" class="paper-choice-row__tags">
          <span v-if="item.ambiguous" class="paper-choice-tag paper-choice-tag--state">AMBIGUOUS</span>
          <span v-for="reason in item.matchReasons || []" :key="reason" class="paper-choice-tag">
            {{ reason }}
          </span>
        </div>
      </div>
      <div class="paper-choice-row__actions">
        <NTooltip trigger="hover">
          <template #trigger>
            <NButton
              circle
              secondary
              size="small"
              title="列出阅读位置"
              aria-label="列出阅读位置"
              @click="listLocations(item)"
            >
              <template #icon>
                <icon-lucide:list-tree />
              </template>
            </NButton>
          </template>
          列出阅读位置
        </NTooltip>
        <NTooltip trigger="hover">
          <template #trigger>
            <NButton
              circle
              secondary
              size="small"
              title="定位阅读位置"
              aria-label="定位阅读位置"
              @click="findInPaper(item)"
            >
              <template #icon>
                <icon-lucide:search />
              </template>
            </NButton>
          </template>
          定位阅读位置
        </NTooltip>
        <NTooltip trigger="hover">
          <template #trigger>
            <NButton circle secondary size="small" title="选择论文" aria-label="选择论文" @click="selectPaper(item)">
              <template #icon>
                <icon-lucide:message-square-plus />
              </template>
            </NButton>
          </template>
          选择论文
        </NTooltip>
      </div>
    </div>
  </div>
</template>

<style scoped>
.paper-choice-list {
  display: grid;
  width: 100%;
  gap: 8px;
}

.paper-choice-row {
  display: grid;
  width: 100%;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: color-mix(in srgb, var(--color-surface) 86%, var(--color-bg));
  padding: 10px 12px;
}

.paper-choice-row__main {
  display: grid;
  min-width: 0;
  gap: 5px;
}

.paper-choice-row__title {
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--color-text);
  font-size: 14px;
  font-weight: 650;
  line-height: 1.35;
}

.paper-choice-row__meta {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  gap: 6px 10px;
  color: var(--color-text-muted);
  font-size: 12px;
  line-height: 1.35;
}

.paper-choice-row__filename {
  overflow-wrap: anywhere;
}

.paper-choice-row__tags {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  gap: 6px;
}

.paper-choice-row__actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.paper-choice-tag {
  max-width: 100%;
  overflow-wrap: anywhere;
  border: 1px solid color-mix(in srgb, var(--color-border) 78%, transparent);
  border-radius: 999px;
  background: var(--color-surface-alt);
  padding: 2px 7px;
  color: var(--color-text-muted);
  font-size: 11px;
  line-height: 15px;
}

.paper-choice-tag--state {
  border-color: color-mix(in srgb, #b7791f 35%, var(--color-border));
  background: color-mix(in srgb, #f6d58a 18%, var(--color-surface));
  color: #8a5a12;
}

.dark .paper-choice-tag--state {
  border-color: rgb(246 213 138 / 32%);
  background: rgb(246 213 138 / 11%);
  color: #f1cf83;
}
</style>
