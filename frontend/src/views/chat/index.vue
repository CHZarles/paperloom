<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute } from 'vue-router';
import SettingsPanel from '../personal-center/modules/settings-panel.vue';
import ChatList from './modules/chat-list.vue';
import InputBox from './modules/input-box.vue';
import ConversationSidebar from './modules/conversation-sidebar.vue';
import ReferenceEvidencePage from './modules/reference-evidence-page.vue';
import SourceEvidencePanel from './modules/source-evidence-panel.vue';
import ResearchProcessPanel from './modules/research-process-panel.vue';

const route = useRoute();
const showReferenceEvidence = computed(() => route.query.evidence === 'reference');
const sidebarCollapsed = ref(typeof window !== 'undefined' ? window.innerWidth <= 960 : false);
let chatNavigationNarrow = typeof window !== 'undefined' ? window.innerWidth <= 960 : false;
const chatStore = useChatStore();
const { connectionStatus, conversationId, currentScope, list, sessions } = storeToRefs(chatStore);
const referencePanelVisible = ref(false);
const referencePanelRef = ref<HTMLElement | null>(null);
const reviewOverlayMode = ref(typeof window !== 'undefined' ? window.innerWidth < 1200 : false);
let reviewReturnFocus: HTMLElement | null = null;
const activeReviewTab = ref<'process' | 'evidence'>('evidence');
const processMessage = ref<Api.Chat.Message | null>(null);
const settingsVisible = ref(false);
const settingsModalHostStyle = { width: 'min(1480px, calc(100vw - 32px))' };
type ReferencePanelPayload = Partial<Omit<Api.Chat.ReferenceEvidence, 'paperId' | 'paperTitle'>> & {
  paperTitle: string;
  paperId?: string | null;
  conversationRecordId?: number;
  referenceNumber: number;
};
const referencePayload = ref<ReferencePanelPayload | null>(null);

const showDockInput = computed(() => list.value.length > 0);
const currentSessionTitle = computed(
  () => sessions.value.find(session => session.conversationId === conversationId.value)?.title || 'New research'
);
const currentSourceLabel = computed(() => currentScope.value?.sourceLabel || 'All readable papers');
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
  activeReviewTab.value = 'evidence';
  referencePanelVisible.value = true;
}

function handleOpenProcess(message: Api.Chat.Message) {
  processMessage.value = message;
  activeReviewTab.value = 'process';
  referencePanelVisible.value = true;
}

function closeReferencePanel() {
  referencePanelVisible.value = false;
}

function handleReviewPanelKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault();
    closeReferencePanel();
    return;
  }
  if (event.key !== 'Tab' || !reviewOverlayMode.value || !referencePanelRef.value) return;

  const focusable = Array.from(
    referencePanelRef.value.querySelectorAll<HTMLElement>(
      'button:not([disabled]), a[href], input:not([disabled]), textarea:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])'
    )
  );
  if (!focusable.length) {
    event.preventDefault();
    referencePanelRef.value.focus();
    return;
  }

  const first = focusable[0];
  const last = focusable.at(-1);
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault();
    last?.focus();
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault();
    first.focus();
  }
}

watch(referencePanelVisible, async visible => {
  if (visible) {
    reviewReturnFocus = document.activeElement as HTMLElement | null;
    await nextTick();
    if (reviewOverlayMode.value) referencePanelRef.value?.focus();
    return;
  }
  reviewReturnFocus?.focus();
  reviewReturnFocus = null;
});

function syncSidebarForViewport() {
  const nextNavigationNarrow = window.innerWidth <= 960;
  if (nextNavigationNarrow !== chatNavigationNarrow) sidebarCollapsed.value = nextNavigationNarrow;
  chatNavigationNarrow = nextNavigationNarrow;
  reviewOverlayMode.value = window.innerWidth < 1200;
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
    <ConversationSidebar
      v-model:collapsed="sidebarCollapsed"
      class="chat-shell__sidebar"
      @open-settings="settingsVisible = true"
    />

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
          <div class="topbar-title">{{ currentSessionTitle }}</div>
          <div class="topbar-subtitle">{{ currentSourceLabel }}</div>
        </div>
        <div class="connection-pill" :class="`connection-pill--${connectionStatus}`">
          <span />
          {{ connectionText }}
        </div>
        <button
          class="topbar-icon-button"
          :class="{ 'topbar-icon-button--active': referencePanelVisible }"
          aria-label="Research review"
          @click="referencePanelVisible = !referencePanelVisible"
        >
          <icon-lucide:panel-right-open class="text-18px" />
        </button>
      </header>

      <section class="chat-workspace">
        <div class="chat-conversation">
          <ChatList
            @open-reference="handleOpenReference"
            @open-process="handleOpenProcess"
          />
          <InputBox v-if="showDockInput" />
        </div>

        <div
          v-if="referencePanelVisible && reviewOverlayMode"
          class="review-panel-mask"
          aria-hidden="true"
          @click="closeReferencePanel"
        />
        <aside
          v-if="referencePanelVisible"
          ref="referencePanelRef"
          class="reference-panel"
          :role="reviewOverlayMode ? 'dialog' : 'complementary'"
          :aria-modal="reviewOverlayMode ? 'true' : undefined"
          aria-label="Research review"
          tabindex="-1"
          @keydown="handleReviewPanelKeydown"
        >
          <div class="reference-panel__header">
            <div>
              <div class="reference-panel__title">Research Review</div>
              <div class="reference-panel__subtitle">Review retrieval activity and cited paper evidence</div>
            </div>
            <NButton quaternary circle size="small" aria-label="Close research review" @click="closeReferencePanel">
              <template #icon>
                <icon-lucide:x />
              </template>
            </NButton>
          </div>
          <div class="review-mode-switch" role="tablist" aria-label="Research review mode">
            <button
              type="button"
              role="tab"
              :aria-selected="activeReviewTab === 'process'"
              :class="{ 'is-active': activeReviewTab === 'process' }"
              @click="activeReviewTab = 'process'"
            >
              <icon-lucide:list-tree />
              Process
            </button>
            <button
              type="button"
              role="tab"
              :aria-selected="activeReviewTab === 'evidence'"
              :class="{ 'is-active': activeReviewTab === 'evidence' }"
              @click="activeReviewTab = 'evidence'"
            >
              <icon-lucide:file-text />
              Source Evidence
            </button>
          </div>
          <ResearchProcessPanel
            v-if="activeReviewTab === 'process'"
            :message="processMessage"
            @open-reference="handleOpenReference"
          />
          <SourceEvidencePanel
            v-else-if="referencePayload"
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
            :source-type="referencePayload.sourceType"
            :evidence-asset-level="referencePayload.evidenceAssetLevel"
            :pdf-evidence-available="referencePayload.pdfEvidenceAvailable"
            :page-screenshot-available="referencePayload.pageScreenshotAvailable"
            :figure-screenshot-available="referencePayload.figureScreenshotAvailable"
            :asset-warnings="referencePayload.assetWarnings"
            :conversation-record-id="referencePayload.conversationRecordId"
            :source-quote-ref="referencePayload.sourceQuoteRef || undefined"
            :visual-regions="referencePayload.visualRegions"
          />
          <div v-else class="reference-panel__empty">
            <icon-lucide:file-text class="text-34px" />
            <span>点击回答中的 source 引用后，会在这里显示证据来源。</span>
          </div>
        </aside>
      </section>
    </main>

    <NModal
      v-model:show="settingsVisible"
      :auto-focus="false"
      class="settings-modal-host"
      :style="settingsModalHostStyle"
    >
      <SettingsPanel @close="settingsVisible = false" />
    </NModal>
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

.settings-modal-host {
  width: min(1480px, calc(100vw - 32px));
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
  overflow: hidden;
  margin-top: 3px;
  color: var(--chat-text-muted);
  font-size: 11px;
  line-height: 1.1;
  text-overflow: ellipsis;
  white-space: nowrap;
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
  width: clamp(420px, 36vw, 620px);
  min-width: 420px;
  flex-shrink: 0;
  flex-direction: column;
  overflow: hidden;
  border-left: 1px solid var(--chat-line);
  background: var(--color-bg);
}

.review-panel-mask {
  display: none;
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

.review-mode-switch {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 2px;
  margin: 10px 14px 0;
  border: 1px solid var(--chat-line);
  border-radius: 6px;
  background: var(--color-card-band);
  padding: 2px;
}

.review-mode-switch button {
  display: inline-flex;
  min-width: 0;
  align-items: center;
  justify-content: center;
  gap: 7px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: var(--chat-text-muted);
  cursor: pointer;
  padding: 7px 9px;
  font-size: 12px;
  font-weight: 650;
}

.review-mode-switch button.is-active {
  background: var(--color-surface);
  box-shadow: var(--shadow-card);
  color: var(--chat-accent);
}

.review-mode-switch svg {
  width: 15px;
  height: 15px;
  flex: 0 0 auto;
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

.reference-panel :deep(.research-process) {
  min-height: 0;
  flex: 1 1 0;
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

.dark .connection-pill,
.dark .reference-panel__subtitle {
  color: var(--color-text-muted);
}

@media (max-width: 960px) {
  .chat-shell__sidebar {
    position: fixed;
    inset: 0 auto 0 0;
    z-index: 40;
    width: 276px !important;
    min-width: 276px !important;
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

@media (max-width: 1199px) {
  .review-panel-mask {
    position: fixed;
    inset: 58px 0 0;
    z-index: 19;
    display: block;
    background: color-mix(in srgb, var(--color-text) 18%, transparent);
  }
}

@media (min-width: 961px) and (max-width: 1199px) {
  .reference-panel {
    position: fixed;
    inset: 58px 0 0 auto;
    z-index: 20;
    width: min(100vw, 560px);
    min-width: 0;
    box-shadow: var(--shadow-card-soft);
  }
}

@media (max-width: 767px) {
  .connection-pill {
    display: none;
  }

  .reference-panel {
    inset: auto 0 0;
    width: 100vw;
    height: min(78vh, 720px);
    border-top: 1px solid var(--chat-line);
    border-left: 0;
    border-radius: 8px 8px 0 0;
    box-shadow: var(--shadow-card-soft);
  }

  .reference-panel::before {
    width: 42px;
    height: 4px;
    flex: 0 0 auto;
    align-self: center;
    margin-top: 7px;
    border-radius: 999px;
    background: var(--color-border);
    content: '';
  }
}
</style>
