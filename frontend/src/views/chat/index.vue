<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute } from 'vue-router';
import ChatList from './modules/chat-list.vue';
import InputBox from './modules/input-box.vue';
import ConversationSidebar from './modules/conversation-sidebar.vue';
import ReferenceEvidencePage from './modules/reference-evidence-page.vue';
import SourceEvidencePanel from './modules/source-evidence-panel.vue';

const route = useRoute();
const showReferenceEvidence = computed(() => route.query.evidence === 'reference');
const sidebarCollapsed = ref(typeof window !== 'undefined' ? window.innerWidth <= 960 : false);
const chatStore = useChatStore();
const { connectionStatus, list } = storeToRefs(chatStore);
const referencePanelVisible = ref(false);
const referencePayload = ref<{
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
  paperTitle: string;
  paperId?: string | null;
  originalFilename?: string | null;
  pageNumber?: number | null;
  anchorText?: string | null;
  conversationRecordId?: number;
  referenceNumber: number;
} | null>(null);

const showDockInput = computed(() => list.value.length > 0);
const referenceEvidenceKey = computed(() => {
  if (!referencePayload.value) {
    return 'empty-reference';
  }
  return [
    referencePayload.value.paperId || referencePayload.value.paperTitle,
    referencePayload.value.pageNumber || '',
    referencePayload.value.referenceNumber
  ].join(':');
});
const connectionText = computed(() => {
  if (connectionStatus.value === 'OPEN') return '已连接';
  if (connectionStatus.value === 'CONNECTING') return '连接中';
  if (connectionStatus.value === 'RECONNECTING') return '重连中';
  return '未连接';
});

function handleOpenReference(payload: NonNullable<typeof referencePayload.value>) {
  referencePayload.value = payload;
  referencePanelVisible.value = true;
}

function closeReferencePanel() {
  referencePanelVisible.value = false;
}

function handleAskAboutReference(scope: Api.Chat.Scope) {
  chatStore.input.scope = scope;
  if (!chatStore.input.message.trim()) {
    chatStore.input.message = '解释这个引用';
  }
}

function handleSelectSourceScope(scope: Api.Chat.Scope) {
  chatStore.input.scope = scope;
}

function syncSidebarForViewport() {
  sidebarCollapsed.value = window.innerWidth <= 960;
}

onMounted(() => {
  syncSidebarForViewport();
  window.addEventListener('resize', syncSidebarForViewport);
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', syncSidebarForViewport);
});
</script>

<template>
  <div v-if="showReferenceEvidence" class="h-full">
    <ReferenceEvidencePage />
  </div>
  <div v-else class="chat-shell">
    <div v-if="!sidebarCollapsed" class="mobile-sidebar-mask" @click="sidebarCollapsed = true" />
    <ConversationSidebar v-model:collapsed="sidebarCollapsed" class="chat-shell__sidebar" />

    <main class="chat-shell__main">
      <header class="chat-topbar">
        <button
          v-show="sidebarCollapsed"
          class="topbar-icon-button"
          aria-label="展开对话列表"
          @click="sidebarCollapsed = false"
        >
          <icon-lucide:panel-left-open class="text-18px" />
        </button>
        <div class="min-w-0 flex-1">
          <div class="topbar-title">PaperLoom</div>
          <div class="topbar-subtitle">evidence-grounded paper analysis</div>
        </div>
        <div class="connection-pill" :class="`connection-pill--${connectionStatus}`">
          <span />
          {{ connectionText }}
        </div>
        <button
          class="topbar-icon-button"
          :class="{ 'topbar-icon-button--active': referencePanelVisible }"
          aria-label="引用证据"
          @click="referencePanelVisible = !referencePanelVisible"
        >
          <icon-lucide:file-text class="text-18px" />
        </button>
      </header>

      <section class="chat-workspace">
        <div class="chat-conversation">
          <ChatList @open-reference="handleOpenReference" @select-source-scope="handleSelectSourceScope" />
          <InputBox v-if="showDockInput" />
        </div>

        <aside v-if="referencePanelVisible" class="reference-panel">
          <div class="reference-panel__header">
            <div>
              <div class="reference-panel__title">Source Evidence</div>
              <div class="reference-panel__subtitle">核对引用对应的论文原文</div>
            </div>
            <NButton quaternary circle size="small" @click="closeReferencePanel">
              <template #icon>
                <icon-lucide:x />
              </template>
            </NButton>
          </div>
          <SourceEvidencePanel
            v-if="referencePayload"
            :key="referenceEvidenceKey"
            :reference-number="referencePayload.referenceNumber"
            :paper-title="referencePayload.paperTitle"
            :paper-id="referencePayload.paperId || undefined"
            :original-filename="referencePayload.originalFilename || undefined"
            :page-number="referencePayload.pageNumber || undefined"
            :evidence-snippet="referencePayload.evidenceSnippet || undefined"
            :matched-chunk-text="referencePayload.matchedChunkText || undefined"
            :chunk-id="referencePayload.chunkId || undefined"
            :bbox-json="referencePayload.bboxJson || undefined"
            :source-kind="referencePayload.sourceKind || undefined"
            :table-id="referencePayload.tableId || undefined"
            :figure-id="referencePayload.figureId || undefined"
            :formula-id="referencePayload.formulaId || undefined"
            :table-text="referencePayload.tableText || undefined"
            :table-markdown="referencePayload.tableMarkdown || undefined"
            :table-screenshot-available="referencePayload.tableScreenshotAvailable"
            :conversation-record-id="referencePayload.conversationRecordId"
            @ask-about-this="handleAskAboutReference"
          />
          <div v-else class="reference-panel__empty">
            <icon-lucide:file-text class="text-34px" />
            <span>点击回答中的 source 引用后，会在这里显示证据来源。</span>
          </div>
        </aside>
      </section>
    </main>
  </div>
</template>

<style scoped>
.chat-shell {
  --chat-accent: var(--color-primary);
  --chat-accent-soft: var(--color-accent-soft-bg);
  --chat-surface: var(--color-surface);
  --chat-muted: var(--color-card-band);
  --chat-line: var(--color-border);
  --chat-text: var(--color-text);
  --chat-text-muted: var(--color-text-muted);
  display: flex;
  height: 100vh;
  width: 100%;
  overflow: hidden;
  background: var(--color-bg);
  color: var(--chat-text);
  font-family:
    system-ui,
    -apple-system,
    BlinkMacSystemFont,
    'Segoe UI',
    'PingFang SC',
    'Microsoft YaHei',
    sans-serif;
}

.chat-shell__main {
  display: flex;
  min-width: 0;
  flex: 1 1 0;
  flex-direction: column;
  overflow: hidden;
}

.chat-topbar {
  display: flex;
  height: 58px;
  flex-shrink: 0;
  align-items: center;
  gap: 12px;
  border-bottom: 1px solid var(--chat-line);
  background: var(--color-surface);
  padding: 0 16px;
}

.topbar-icon-button {
  display: inline-flex;
  height: 36px;
  width: 36px;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--chat-line);
  border-radius: 6px;
  background: var(--color-surface);
  color: var(--chat-text-muted);
  cursor: pointer;
  transition: all 0.16s ease;
}

.topbar-icon-button:hover,
.topbar-icon-button--active {
  border-color: var(--color-primary);
  background: var(--chat-accent-soft);
  color: var(--chat-accent);
}

.topbar-title {
  color: var(--chat-accent);
  font-family:
    system-ui,
    -apple-system,
    BlinkMacSystemFont,
    'Segoe UI',
    'PingFang SC',
    'Microsoft YaHei',
    sans-serif;
  font-size: 18px;
  font-weight: 700;
  line-height: 1.05;
}

.topbar-subtitle {
  color: var(--chat-text-muted);
  font-family:
    system-ui,
    -apple-system,
    BlinkMacSystemFont,
    'Segoe UI',
    'PingFang SC',
    'Microsoft YaHei',
    sans-serif;
  font-size: 11px;
}

.connection-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--chat-line);
  border-radius: 4px;
  background: var(--color-surface);
  color: var(--chat-text-muted);
  padding: 5px 10px;
  font-family:
    system-ui,
    -apple-system,
    BlinkMacSystemFont,
    'Segoe UI',
    'PingFang SC',
    'Microsoft YaHei',
    sans-serif;
  font-size: 12px;
}

.connection-pill span {
  height: 7px;
  width: 7px;
  border-radius: 999px;
  background: var(--color-text-muted);
}

.connection-pill--OPEN span {
  background: var(--color-success);
}

.connection-pill--CONNECTING span,
.connection-pill--RECONNECTING span {
  background: var(--color-warning);
}

.chat-workspace {
  display: flex;
  min-height: 0;
  flex: 1 1 0;
  overflow: hidden;
}

.chat-conversation {
  display: flex;
  min-width: 0;
  flex: 1 1 0;
  flex-direction: column;
  overflow: hidden;
  background: var(--color-surface);
}

.reference-panel {
  display: flex;
  width: min(860px, 48vw);
  min-width: 560px;
  flex-shrink: 0;
  flex-direction: column;
  overflow: hidden;
  border-left: 1px solid var(--chat-line);
  background: var(--color-bg);
}

.reference-panel__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--chat-line);
  padding: 12px 14px;
}

.reference-panel__title {
  color: var(--chat-accent);
  font-family:
    system-ui,
    -apple-system,
    BlinkMacSystemFont,
    'Segoe UI',
    'PingFang SC',
    'Microsoft YaHei',
    sans-serif;
  font-size: 17px;
  font-weight: 700;
}

.reference-panel__subtitle {
  margin-top: 2px;
  color: var(--chat-text-muted);
  font-family:
    system-ui,
    -apple-system,
    BlinkMacSystemFont,
    'Segoe UI',
    'PingFang SC',
    'Microsoft YaHei',
    sans-serif;
  font-size: 12px;
}

.reference-panel__empty {
  display: flex;
  min-height: 0;
  flex: 1 1 0;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: var(--chat-text-muted);
  padding: 24px;
  text-align: center;
}

.reference-panel :deep(.source-evidence) {
  min-height: 0;
  flex: 1 1 0;
  overflow: auto;
  padding: 12px 14px;
}

.mobile-sidebar-mask {
  display: none;
}

.dark .chat-shell {
  background: var(--color-bg);
  color: var(--color-text);
}

.dark .chat-topbar,
.dark .chat-conversation {
  border-color: var(--color-border);
  background: var(--color-surface);
}

.dark .topbar-icon-button,
.dark .connection-pill,
.dark .reference-panel {
  border-color: var(--color-border);
  background: var(--color-surface);
}

.dark .topbar-subtitle,
.dark .connection-pill,
.dark .reference-panel__subtitle {
  color: var(--color-text-muted);
}

@media (max-width: 960px) {
  .chat-shell__sidebar {
    position: fixed;
    inset: 0 auto 0 0;
    z-index: 40;
    width: 292px !important;
    min-width: 292px !important;
    transform: translateX(0);
  }

  .chat-shell__sidebar.w-0 {
    transform: translateX(-100%);
  }

  .mobile-sidebar-mask {
    position: fixed;
    inset: 0;
    z-index: 30;
    display: block;
    background: var(--color-surface-alt);
  }

  .reference-panel {
    position: fixed;
    inset: 58px 0 0 auto;
    z-index: 20;
    width: min(100vw, 560px);
    min-width: 0;
  }
}

@media (max-width: 640px) {
  .connection-pill {
    display: none;
  }

  .reference-panel {
    inset: 58px 0 0;
    width: 100vw;
  }
}
</style>
