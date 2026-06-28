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

const params = computed(() => {
  const p: Record<string, string> = {};
  if (conversationId.value) {
    p.conversationId = conversationId.value;
  }
  return p;
});

watchEffect(() => {
  getList();
});

async function getList() {
  loading.value = true;
  const { error, data } = await request<Api.Chat.Message[]>({
    url: 'users/conversation',
    params: params.value
  });
  if (!error) {
    list.value = data;
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
        <div class="welcome-logo">
          <SystemLogo class="text-56px" />
        </div>
        <div class="welcome-copy">
          <div class="welcome-kicker">evidence-grounded paper reading desk</div>
          <h1>Ask papers with cited evidence</h1>
          <p>围绕论文、PDF、方法、实验和结论提问，回答会用 citation chip 连接页码、chunk 与原文证据。</p>
          <div class="welcome-tags" aria-label="example scopes">
            <span>[PDF]</span>
            <span>[METHOD]</span>
            <span>[CLAIMS]</span>
            <span>[REFS]</span>
          </div>
        </div>
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

.welcome-logo {
  display: flex;
  height: 72px;
  width: 72px;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  background: var(--color-primary-soft-bg);
  color: var(--color-primary);
}

.welcome-copy {
  max-width: 860px;
}

.welcome-kicker {
  margin-bottom: 9px;
  color: var(--color-text-muted);
  font-family:
    system-ui,
    -apple-system,
    BlinkMacSystemFont,
    'Segoe UI',
    'PingFang SC',
    'Microsoft YaHei',
    sans-serif;
  font-size: 12px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.08em;
}

.welcome-copy h1 {
  margin: 0;
  color: var(--color-text);
  font-family:
    system-ui,
    -apple-system,
    BlinkMacSystemFont,
    'Segoe UI',
    'PingFang SC',
    'Microsoft YaHei',
    sans-serif;
  font-size: 28px;
  font-weight: 700;
  line-height: 1.2;
}

.welcome-copy p {
  margin: 12px auto 0;
  max-width: 590px;
  color: var(--color-text-muted);
  font-size: 14px;
  line-height: 1.8;
}

.welcome-tags {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
  margin-top: 16px;
}

.welcome-tags span {
  border-radius: 999px;
  background: var(--color-surface-alt);
  color: var(--color-text-muted);
  padding: 5px 12px;
  font-family:
    system-ui,
    -apple-system,
    BlinkMacSystemFont,
    'Segoe UI',
    'PingFang SC',
    'Microsoft YaHei',
    sans-serif;
  font-size: 11px;
  font-weight: 500;
}

.welcome-input {
  width: min(760px, 100%);
}

.chat-message-stack {
  width: min(960px, 100%);
  margin: 0 auto;
  padding: 26px 24px 36px;
}

.dark .welcome-logo {
  background: var(--color-primary-soft-bg);
}

.dark .welcome-copy h1 {
  color: var(--color-text);
}

.dark .welcome-copy p {
  color: var(--color-text-muted);
}

.dark .welcome-tags span {
  border-color: var(--color-border);
  background: var(--color-surface);
  color: var(--color-text-muted);
}

@media (max-width: 640px) {
  .welcome-copy h1 {
    font-size: 26px;
  }

  .chat-message-stack {
    padding: 18px 16px 28px;
  }
}
</style>
