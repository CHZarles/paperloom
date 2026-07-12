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
const router = useRouter();
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
const promptSuggestions = [
  {
    key: 'summarize',
    label: 'Summarize',
    prompt: 'Summarize the main claims across my searchable papers'
  },
  {
    key: 'compare',
    label: 'Compare',
    prompt: 'Compare methods, experiments, and limitations across the most relevant papers'
  },
  {
    key: 'evidence',
    label: 'Find evidence',
    prompt: 'Find the strongest paper evidence for this question: '
  },
  {
    key: 'tables',
    label: 'Tables / figures',
    prompt: 'Find tables or figures that support the strongest result'
  },
  {
    key: 'scope',
    label: 'Choose sources',
    prompt: '',
    route: { name: 'knowledge-base', query: { view: 'collections' } }
  }
];

function applySuggestion(action: (typeof promptSuggestions)[number]) {
  if ('route' in action && action.route) {
    router.push(action.route);
    return;
  }
  chatStore.input.message = action.prompt;
}

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
        <div class="welcome-suggestions" aria-label="Quick actions">
          <button
            v-for="action in promptSuggestions"
            :key="action.key"
            type="button"
            class="welcome-suggestion"
            @click="applySuggestion(action)"
          >
            <icon-lucide:file-text v-if="action.key === 'summarize'" class="welcome-suggestion__icon" />
            <icon-lucide:git-compare-arrows v-else-if="action.key === 'compare'" class="welcome-suggestion__icon" />
            <icon-lucide:search-check v-else-if="action.key === 'evidence'" class="welcome-suggestion__icon" />
            <icon-lucide:table-2 v-else-if="action.key === 'tables'" class="welcome-suggestion__icon" />
            <icon-lucide:library v-else class="welcome-suggestion__icon" />
            <span>{{ action.label }}</span>
          </button>
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
.welcome-input,
.welcome-suggestions {
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

.welcome-suggestions {
  display: flex;
  width: min(960px, 100%);
  flex-wrap: wrap;
  justify-content: center;
  gap: 9px;
}

.welcome-suggestion {
  display: inline-flex;
  min-height: 36px;
  max-width: min(100%, 180px);
  align-items: center;
  gap: 7px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: color-mix(in srgb, var(--color-surface) 86%, transparent);
  color: var(--color-text-muted);
  cursor: pointer;
  padding: 0 13px;
  font-size: 12px;
  font-weight: 650;
  line-height: 1.25;
  text-align: left;
  transition:
    border-color 0.16s ease,
    background 0.16s ease,
    color 0.16s ease,
    transform 0.16s ease;
}

.welcome-suggestion:hover {
  border-color: color-mix(in srgb, var(--color-primary) 44%, var(--color-border));
  background: var(--color-surface);
  color: var(--color-text);
  transform: translateY(-1px);
}

.welcome-suggestion__icon {
  flex: 0 0 auto;
  color: #b7791f;
  font-size: 15px;
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

  .welcome-title,
  .welcome-suggestions {
    justify-content: center;
  }

  .welcome-title {
    font-size: 38px;
  }

  .welcome-suggestion {
    width: 100%;
    max-width: 100%;
  }

  .chat-message-stack {
    padding: 18px 16px 28px;
  }
}
</style>
