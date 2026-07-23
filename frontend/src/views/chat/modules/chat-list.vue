<script setup lang="ts">
import { defineAsyncComponent } from 'vue';
import { NScrollbar } from 'naive-ui';
import { researchMarkdownOptions } from '@/utils/research-markdown';
import InputBox from './input-box.vue';

const ChatMessage = defineAsyncComponent(() => import('./chat-message.vue'));
const MarkdownProvider = defineAsyncComponent(() =>
  import('@/vendor/vue-markdown-shiki').then(module => module.VueMarkdownItProvider)
);

defineOptions({
  name: 'ChatList'
});

const emit = defineEmits<{
  (
    e: 'openReference',
    payload: {
      retrievalMode?: Api.Chat.ReferenceEvidence['retrievalMode'];
      retrievalLabel?: string | null;
      retrievalQuery?: string | null;
      evidenceSnippet?: string | null;
      matchedChunkText?: string | null;
      score?: number | null;
      chunkId?: number | null;
      elementType?: string | null;
      sectionTitle?: string | null;
      sectionLevel?: number | null;
      bboxJson?: string | null;
      parserName?: string | null;
      parserVersion?: string | null;
      sourceKind?: Api.Chat.ReferenceEvidence['sourceKind'];
      tableId?: string | null;
      figureId?: string | null;
      formulaId?: string | null;
      evidenceRole?: string | null;
      retrievalRoute?: string | null;
      intent?: string | null;
      rankReason?: string | null;
      tableText?: string | null;
      tableMarkdown?: string | null;
      tableScreenshotAvailable?: boolean | null;
      sourceType?: Api.Chat.ReferenceEvidence['sourceType'];
      evidenceAssetLevel?: Api.Chat.ReferenceEvidence['evidenceAssetLevel'];
      pdfEvidenceAvailable?: boolean | null;
      pageScreenshotAvailable?: boolean | null;
      figureScreenshotAvailable?: boolean | null;
      assetWarnings?: string[] | null;
      paperTitle: string;
      originalFilename?: string | null;
      paperId?: string | null;
      pageNumber?: number | null;
      anchorText?: string | null;
      conversationRecordId?: number;
      referenceNumber: number;
      sourceQuoteRef?: string | null;
    }
  ): void;
  (e: 'openProcess', message: Api.Chat.Message): void;
  (e: 'retryMessage', message: string): void;
}>();

const chatStore = useChatStore();
const { conversationId, hasOlderMessages, list, messagesLoadingOlder, sessionId } = storeToRefs(chatStore);

const loading = ref(false);
const scrollbarRef = ref<InstanceType<typeof NScrollbar>>();
const scrollContainer = shallowRef<HTMLElement | null>(null);
const isFollowingBottom = ref(true);
let scrollFrame: number | null = null;
let preservingScroll = false;
const hasRichMarkdown = computed(() =>
  list.value.some(
    item =>
      item.role === 'assistant' &&
      Boolean(item.content) &&
      item.status !== 'error' &&
      !['pending', 'loading'].includes(item.status || '')
  )
);

watch(
  () => [list.value.length, list.value.at(-1)?.content.length || 0],
  () => {
    if (!preservingScroll) requestScrollToBottom();
  },
  { flush: 'post' }
);

watch(conversationId, () => {
  isFollowingBottom.value = true;
  requestScrollToBottom(true);
});

function requestScrollToBottom(force = false) {
  if ((!force && !isFollowingBottom.value) || scrollFrame !== null) return;
  scrollFrame = window.requestAnimationFrame(() => {
    scrollFrame = null;
    nextTick(() => {
      scrollbarRef.value?.scrollTo({
        top: Number.MAX_SAFE_INTEGER,
        behavior: 'auto'
      });
    });
  });
}

function handleScroll(event: Event) {
  const target = event.target as HTMLElement | null;
  if (!target) return;
  scrollContainer.value = target;
  if (preservingScroll) return;
  isFollowingBottom.value = target.scrollHeight - target.scrollTop - target.clientHeight < 120;
}

function jumpToLatest() {
  isFollowingBottom.value = true;
  requestScrollToBottom(true);
}

async function handleLoadOlderMessages() {
  const container = scrollContainer.value;
  const previousHeight = container?.scrollHeight || 0;
  const previousTop = container?.scrollTop || 0;
  preservingScroll = true;
  const loaded = await chatStore.loadOlderMessages();
  await nextTick();
  if (loaded && container) {
    scrollbarRef.value?.scrollTo({
      top: previousTop + Math.max(0, container.scrollHeight - previousHeight),
      behavior: 'auto'
    });
  }
  window.requestAnimationFrame(() => {
    preservingScroll = false;
    if (container) {
      isFollowingBottom.value = container.scrollHeight - container.scrollTop - container.clientHeight < 120;
    }
  });
}

function getRetrievalQueryFallback(index: number) {
  for (let i = index - 1; i >= 0; i -= 1) {
    const candidate = list.value[i];
    if (candidate?.role === 'user') {
      return candidate.content || '';
    }
  }
  return '';
}

async function loadCurrentConversationIfNeeded() {
  if (!conversationId.value || list.value.length > 0) {
    return;
  }
  loading.value = true;
  const targetConversationId = conversationId.value;
  try {
    await chatStore.loadConversationDetails(targetConversationId);
  } finally {
    if (targetConversationId === conversationId.value) {
      loading.value = false;
    }
  }
}

onMounted(() => {
  loadCurrentConversationIfNeeded();
});

onBeforeUnmount(() => {
  if (scrollFrame !== null) window.cancelAnimationFrame(scrollFrame);
});

const showEmpty = computed(() => !loading.value && list.value.length === 0);

function handleRetry(index: number) {
  const message = getRetrievalQueryFallback(index).trim();
  if (!message) {
    return;
  }
  emit('retryMessage', message);
}

function messageKey(item: Api.Chat.Message, index: number) {
  if (item.conversationRecordId) return `${item.conversationRecordId}:${item.role}`;
  if (item.generationId) return `${item.generationId}:${item.role}`;
  if (item.timestamp) return `${item.timestamp}:${item.role}`;
  return `${item.role}:${index}`;
}
</script>

<template>
  <Suspense>
    <div class="chat-list-shell">
      <div v-if="showEmpty" class="welcome-panel">
        <h1 class="welcome-title">What should we read?</h1>
        <div class="welcome-input">
          <InputBox variant="hero" />
        </div>
      </div>

      <NScrollbar v-else ref="scrollbarRef" class="flex-1" @scroll="handleScroll">
        <NSpin :show="loading">
          <div class="chat-message-stack">
            <div v-if="hasOlderMessages" class="history-loader">
              <NButton quaternary size="small" :loading="messagesLoadingOlder" @click="handleLoadOlderMessages">
                <template #icon>
                  <icon-lucide:history />
                </template>
                Load earlier messages
              </NButton>
            </div>
            <component
              :is="hasRichMarkdown ? MarkdownProvider : 'div'"
              v-bind="hasRichMarkdown ? { options: researchMarkdownOptions } : {}"
            >
              <ChatMessage
                v-for="(item, index) in list"
                :key="messageKey(item, index)"
                :msg="item"
                :session-id="sessionId"
                :retrieval-query-fallback="getRetrievalQueryFallback(index)"
                evidence-mode="drawer"
                @open-reference="emit('openReference', $event)"
                @open-process="emit('openProcess', $event)"
                @retry="handleRetry(index)"
              />
            </component>
          </div>
        </NSpin>
      </NScrollbar>
      <button
        v-if="!isFollowingBottom"
        type="button"
        class="jump-to-latest"
        aria-label="Jump to latest"
        @click="jumpToLatest"
      >
        <icon-lucide:arrow-down />
      </button>
    </div>
  </Suspense>
</template>

<style scoped lang="scss">
.chat-list-shell {
  position: relative;
  display: flex;
  min-height: 0;
  flex: 1 1 0;
  flex-direction: column;
}

.history-loader {
  display: flex;
  justify-content: center;
  padding: 8px 0 14px;
}

.jump-to-latest {
  position: absolute;
  right: 22px;
  bottom: 18px;
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border: 1px solid var(--color-border);
  border-radius: 50%;
  background: var(--color-surface);
  box-shadow: var(--shadow-card);
  color: var(--color-text);
  cursor: pointer;
}

.jump-to-latest:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.welcome-panel {
  position: relative;
  display: flex;
  min-height: 0;
  flex: 1 1 0;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 20px;
  overflow: hidden;
  padding: 30px 20px 64px;
  text-align: center;
}

.welcome-panel::before {
  position: absolute;
  inset: auto 50% 18%;
  width: min(640px, 78vw);
  height: 1px;
  background: var(--color-border-soft);
  content: '';
  pointer-events: none;
  transform: translateX(-50%);
}

.welcome-title,
.welcome-input {
  position: relative;
  z-index: 1;
}

.welcome-title {
  margin: 0;
  max-width: 960px;
  color: var(--color-text);
  font-size: 44px;
  font-weight: 680;
  line-height: 1.08;
}

.welcome-input {
  width: min(960px, 100%);
}

.chat-message-stack {
  width: min(var(--reading-width), 100%);
  margin: 0 auto;
  padding: 26px 24px 36px;
}

@media (max-width: 640px) {
  .welcome-panel {
    align-items: stretch;
    padding: 26px 14px 46px;
  }

  .welcome-title {
    justify-content: center;
  }

  .welcome-title {
    font-size: 32px;
  }

  .chat-message-stack {
    padding: 18px 16px 28px;
  }
}
</style>
