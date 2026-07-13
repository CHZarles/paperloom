<script setup lang="ts">
import { NScrollbar } from 'naive-ui';
import { VueMarkdownItProvider } from '@/vendor/vue-markdown-shiki';
import ChatMessage from './chat-message.vue';
import InputBox from './input-box.vue';

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
    }
  ): void;
  (e: 'openProcess', message: Api.Chat.Message): void;
  (e: 'retryMessage', message: string): void;
}>();

const chatStore = useChatStore();
const { list, sessionId, conversationId } = storeToRefs(chatStore);

const loading = ref(false);
const scrollbarRef = ref<InstanceType<typeof NScrollbar>>();

watch(() => [...list.value], scrollToBottom);

function scrollToBottom() {
  setTimeout(() => {
    scrollbarRef.value?.scrollBy({
      top: 999999999999999,
      behavior: 'auto'
    });
  }, 100);
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
    await chatStore.loadMessages(targetConversationId);
  } finally {
    if (targetConversationId === conversationId.value) {
      loading.value = false;
    }
  }
}

onMounted(() => {
  chatStore.scrollToBottom = scrollToBottom;
  loadCurrentConversationIfNeeded();
});

const showEmpty = computed(() => !loading.value && list.value.length === 0);
function handleRetry(index: number) {
  const message = getRetrievalQueryFallback(index).trim();
  if (!message) {
    return;
  }
  emit('retryMessage', message);
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

      <NScrollbar v-else ref="scrollbarRef" class="flex-1">
        <NSpin :show="loading">
          <div class="chat-message-stack">
            <VueMarkdownItProvider>
              <ChatMessage
                v-for="(item, index) in list"
                :key="index"
                :msg="item"
                :session-id="sessionId"
                :retrieval-query-fallback="getRetrievalQueryFallback(index)"
                evidence-mode="drawer"
                @open-reference="emit('openReference', $event)"
                @open-process="emit('openProcess', $event)"
                @retry="handleRetry(index)"
              />
            </VueMarkdownItProvider>
          </div>
        </NSpin>
      </NScrollbar>
    </div>
  </Suspense>
</template>

<style scoped lang="scss">
.chat-list-shell {
  display: flex;
  min-height: 0;
  flex: 1 1 0;
  flex-direction: column;
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
  inset: 0;
  background:
    linear-gradient(90deg, rgb(33 48 74 / 4%) 1px, transparent 1px),
    linear-gradient(180deg, rgb(33 48 74 / 4%) 1px, transparent 1px);
  background-size: 48px 48px;
  content: '';
  mask-image: radial-gradient(circle at center, black 0%, black 44%, transparent 76%);
  pointer-events: none;
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
  font-size: clamp(38px, 6vw, 72px);
  font-weight: 760;
  line-height: 0.98;
}

.welcome-input {
  width: min(960px, 100%);
}

.chat-message-stack {
  width: min(1120px, 100%);
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
    font-size: 38px;
  }


  .chat-message-stack {
    padding: 18px 16px 28px;
  }
}
</style>
