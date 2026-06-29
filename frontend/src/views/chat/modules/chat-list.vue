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

watchEffect(() => {
  getList();
});

async function getList() {
  loading.value = true;
  const targetConversationId = conversationId.value;
  const { error, data } = await request<Api.Chat.Message[]>({
    url: 'users/conversation',
    params: targetConversationId ? { conversationId: targetConversationId } : {}
  });
  if (!error) {
    chatStore.applyLoadedMessages(data, targetConversationId);
  }
  loading.value = false;
}

onMounted(() => {
  chatStore.scrollToBottom = scrollToBottom;
});

const showEmpty = computed(() => !loading.value && list.value.length === 0);
</script>

<template>
  <Suspense>
    <div class="chat-list-shell">
      <div v-if="showEmpty" class="welcome-panel">
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
  display: flex;
  min-height: 0;
  flex: 1 1 0;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 20px;
  padding: 32px 20px 64px;
  text-align: center;
}

.welcome-input {
  width: min(760px, 100%);
}

.chat-message-stack {
  width: min(960px, 100%);
  margin: 0 auto;
  padding: 26px 24px 36px;
}

@media (max-width: 640px) {
  .chat-message-stack {
    padding: 18px 16px 28px;
  }
}
</style>
