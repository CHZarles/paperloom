<script setup lang="ts">
import SessionScopePicker from './session-scope-picker.vue';

const props = withDefaults(
  defineProps<{
    variant?: 'dock' | 'hero';
  }>(),
  {
    variant: 'dock'
  }
);

const chatStore = useChatStore();
const { connectionStatus, conversationId, currentScope, input, list, referenceFocus, wsData } = storeToRefs(chatStore);
const sourceScopeUpdating = ref(false);
const canEditSourceScope = computed(() => {
  return props.variant === 'hero' && list.value.length === 0 && !currentScope.value?.scopeLocked;
});

function buildWsErrorMessage(data: Record<string, any>) {
  if (data.code === 429) {
    const retryAfterSeconds = Number(data.retryAfterSeconds || 0);
    const baseMessage = data.message || '聊天请求过于频繁';

    if (retryAfterSeconds > 0) {
      return `${baseMessage}，请在 ${retryAfterSeconds} 秒后重试`;
    }

    return `${baseMessage}，请稍后再试`;
  }

  if (typeof data.error === 'string' && data.error.trim()) {
    return data.error.trim();
  }

  if (typeof data.message === 'string' && data.message.trim()) {
    return data.message.trim();
  }

  return '服务器繁忙，请稍后再试';
}

const latestMessage = computed(() => {
  return list.value[list.value.length - 1] ?? {};
});

let generationStatusTimer: number | null = null;
let lastStreamContentLength = 0;
let lastStreamContentChangedAt = 0;
let streamFlushTimer: number | null = null;
let researchEventFlushTimer: number | null = null;
const STREAM_FLUSH_INTERVAL_MS = 140;
const RESEARCH_EVENT_FLUSH_INTERVAL_MS = 160;
const MAX_RESEARCH_EVENTS = 200;
const pendingStreamChunks = new Map<Api.Chat.Message, string>();
const pendingResearchEvents = new Map<Api.Chat.Message, Api.Chat.ResearchProgressEvent[]>();
const researchEventSequences = new WeakMap<Api.Chat.Message, Set<number>>();

function flushPendingStreamChunks(targetAssistant?: Api.Chat.Message) {
  const entries = targetAssistant
    ? ([[targetAssistant, pendingStreamChunks.get(targetAssistant) || '']] as const)
    : Array.from(pendingStreamChunks.entries());

  entries.forEach(([assistant, chunk]) => {
    if (!chunk) return;
    pendingStreamChunks.delete(assistant);
    assistant.content += chunk;
    lastStreamContentLength = assistant.content.length;
    lastStreamContentChangedAt = Date.now();
  });

  if (pendingStreamChunks.size === 0 && streamFlushTimer !== null) {
    window.clearTimeout(streamFlushTimer);
    streamFlushTimer = null;
  }
}

function scheduleStreamFlush() {
  if (streamFlushTimer !== null) return;
  streamFlushTimer = window.setTimeout(() => {
    streamFlushTimer = null;
    flushPendingStreamChunks();
  }, STREAM_FLUSH_INTERVAL_MS);
}

function discardPendingStreamChunks(assistant: Api.Chat.Message) {
  pendingStreamChunks.delete(assistant);
  if (pendingStreamChunks.size === 0 && streamFlushTimer !== null) {
    window.clearTimeout(streamFlushTimer);
    streamFlushTimer = null;
  }
}

function knownResearchSequencesFor(assistant: Api.Chat.Message) {
  let knownSequences = researchEventSequences.get(assistant);
  if (!knownSequences) {
    knownSequences = new Set(
      (assistant.researchEvents || []).map(item => Number(item.sequence || 0)).filter(item => item > 0)
    );
    researchEventSequences.set(assistant, knownSequences);
  }
  return knownSequences;
}

function releaseResearchSequences(knownSequences: Set<number>, events: Api.Chat.ResearchProgressEvent[] | undefined) {
  events?.forEach(item => {
    const sequence = Number(item.sequence || 0);
    if (sequence > 0) knownSequences.delete(sequence);
  });
}

function appendResearchEvents(assistant: Api.Chat.Message, events: Api.Chat.ResearchProgressEvent[]) {
  if (!events.length) return;
  const knownSequences = knownResearchSequencesFor(assistant);
  const nextEvents = [...(assistant.researchEvents || []), ...events];
  const overflow = Math.max(0, nextEvents.length - MAX_RESEARCH_EVENTS);

  if (overflow > 0) {
    releaseResearchSequences(knownSequences, nextEvents.slice(0, overflow));
  }

  assistant.researchEvents = overflow > 0 ? nextEvents.slice(overflow) : nextEvents;
}

function flushPendingResearchEvents(targetAssistant?: Api.Chat.Message) {
  const entries = targetAssistant
    ? ([[targetAssistant, pendingResearchEvents.get(targetAssistant) || []]] as const)
    : Array.from(pendingResearchEvents.entries());

  entries.forEach(([assistant, events]) => {
    if (!events.length) return;
    pendingResearchEvents.delete(assistant);
    appendResearchEvents(assistant, events);
  });

  if (pendingResearchEvents.size === 0 && researchEventFlushTimer !== null) {
    window.clearTimeout(researchEventFlushTimer);
    researchEventFlushTimer = null;
  }
}

function scheduleResearchEventFlush() {
  if (researchEventFlushTimer !== null) return;
  researchEventFlushTimer = window.setTimeout(() => {
    researchEventFlushTimer = null;
    flushPendingResearchEvents();
  }, RESEARCH_EVENT_FLUSH_INTERVAL_MS);
}

function discardPendingResearchEvents(assistant: Api.Chat.Message) {
  const pendingEvents = pendingResearchEvents.get(assistant);
  const knownSequences = knownResearchSequencesFor(assistant);
  releaseResearchSequences(knownSequences, pendingEvents);
  pendingResearchEvents.delete(assistant);
  researchEventSequences.delete(assistant);

  if (pendingResearchEvents.size === 0 && researchEventFlushTimer !== null) {
    window.clearTimeout(researchEventFlushTimer);
    researchEventFlushTimer = null;
  }
}

const CHAT_ROUTES = new Set<Api.Chat.Route>([
  'SMALLTALK',
  'LIBRARY_SEARCH',
  'AUTO_SOURCE_QA',
  'MANUAL_SOURCE_QA',
  'REFERENCE_QA',
  'FOLLOW_UP',
  'CLARIFY',
  'PAPER_QA'
]);

function normalizeChatRoute(route: unknown): Api.Chat.Route | undefined {
  return typeof route === 'string' && CHAT_ROUTES.has(route as Api.Chat.Route) ? (route as Api.Chat.Route) : undefined;
}

const isSending = computed(() => {
  return (
    latestMessage.value?.role === 'assistant' && ['loading', 'pending'].includes(latestMessage.value?.status || '')
  );
});

const sendDisabled = computed(() => {
  if (isSending.value) {
    return false;
  }
  if (sourceScopeUpdating.value) {
    return true;
  }
  return !input.value.message || ['CLOSED', 'CONNECTING'].includes(connectionStatus.value);
});

const connectionText = computed(() => {
  if (connectionStatus.value === 'OPEN') {
    return '已连接';
  }
  if (connectionStatus.value === 'RECONNECTING') {
    return '重连中';
  }
  if (connectionStatus.value === 'CONNECTING') {
    return '连接中';
  }
  return '未连接';
});
const connectionDotClass = computed(() => ({
  'connection-dot--open': connectionStatus.value === 'OPEN',
  'connection-dot--pending': connectionStatus.value === 'CONNECTING' || connectionStatus.value === 'RECONNECTING',
  'connection-dot--closed': !['OPEN', 'CONNECTING', 'RECONNECTING'].includes(connectionStatus.value),
  'animate-pulse': connectionStatus.value === 'CONNECTING' || connectionStatus.value === 'RECONNECTING'
}));

const referenceFocusLabel = computed(() => {
  const focus = referenceFocus.value;
  if (!focus) return '';
  let paper = focus.paperTitle || focus.originalFilename || '';
  if (!paper) {
    if (focus.paperHandle || focus.paperId) {
      paper = 'Selected paper';
    } else if (focus.sourceQuoteRef) {
      paper = 'Citation';
    } else {
      paper = 'Reference focus';
    }
  }
  const parts = [paper];
  if (focus.readingAction === 'LIST_LOCATIONS') parts.push('List locations');
  if (focus.readingAction === 'FIND_LOCATIONS') parts.push('Find locations');
  if (focus.readingAction === 'READ_LOCATION') parts.push('Read location');
  if (focus.readingAction === 'TRACE_SOURCE_QUOTE') parts.push('Trace citation');
  if (focus.pageNumber) parts.push(`p${focus.pageNumber}`);
  if (focus.referenceNumber) parts.push(`[${focus.referenceNumber}]`);
  return parts.join(' · ');
});

const searchScopeHint = computed(() => {
  const scope = currentScope.value;
  if (!scope) return '';
  if (scope.scopeStatus === 'INVALID') return 'Search scope unavailable';

  const isSnapshot = scope.scopeMode === 'SOURCE_SET_SNAPSHOT';
  const label = isSnapshot ? scope.sourceLabel || 'Selected papers' : scope.sourceLabel || 'All readable papers';
  let count = scope.sourcePaperCount;
  if (typeof count !== 'number') count = isSnapshot ? scope.paperIds?.length : undefined;
  const countText = typeof count === 'number' ? ` · ${count.toLocaleString()} papers` : '';
  const statusText = scope.scopeStatus === 'DEGRADED' ? ' · limited availability' : '';
  return `Search scope: ${label}${countText}${statusText}`;
});

function outgoingReferenceFocus(scope: Api.Chat.Scope | null): Api.Chat.Scope | null {
  if (!scope) return null;
  return { ...scope };
}

function clearReferenceFocus() {
  chatStore.setReferenceFocus(null);
}

function findAssistantBy(matcher: (message: Api.Chat.Message) => boolean) {
  for (let i = list.value.length - 1; i >= 0; i -= 1) {
    const item = list.value[i];
    if (item?.role === 'assistant' && matcher(item)) {
      return item;
    }
  }
  return null;
}

function findAssistantMessage(generationId?: string, payload?: Record<string, any>) {
  const matchers: Array<(message: Api.Chat.Message) => boolean> = [];
  if (generationId) {
    matchers.push(item => item.generationId === generationId);
  }

  const answerSlotId = Number(payload?.answerSlotId || 0);
  if (answerSlotId > 0) {
    matchers.push(item => item.answerSlotId === answerSlotId);
  }

  const retryOfGenerationId = typeof payload?.retryOfGenerationId === 'string' ? payload.retryOfGenerationId : '';
  if (retryOfGenerationId) {
    matchers.push(item => item.generationId === retryOfGenerationId);
  }

  for (const matcher of matchers) {
    const assistant = findAssistantBy(matcher);
    if (assistant) {
      return assistant;
    }
  }

  const latest = list.value[list.value.length - 1];
  if (latest?.role === 'assistant') {
    return latest;
  }

  return null;
}

function handleStartPayload(assistant: Api.Chat.Message, payload: Record<string, any>) {
  const timestamp = payload.timestamp ? new Date(payload.timestamp).toISOString() : undefined;
  const startedAssistant =
    chatStore.applyGenerationStart({
      generationId: typeof payload.generationId === 'string' ? payload.generationId : undefined,
      conversationId: typeof payload.conversationId === 'string' ? payload.conversationId : undefined,
      retryOfGenerationId: typeof payload.retryOfGenerationId === 'string' ? payload.retryOfGenerationId : undefined,
      retryOfConversationRecordId:
        typeof payload.retryOfConversationRecordId === 'number' ? payload.retryOfConversationRecordId : undefined,
      answerSlotId: typeof payload.answerSlotId === 'number' ? payload.answerSlotId : undefined,
      answerRevision: typeof payload.answerRevision === 'number' ? payload.answerRevision : undefined,
      replaceMessage: Boolean(payload.replaceMessage),
      route: normalizeChatRoute(payload.route),
      timestamp
    }) || assistant;

  if (payload.replaceMessage) {
    discardPendingStreamChunks(startedAssistant);
    discardPendingResearchEvents(startedAssistant);
    startedAssistant.content = '';
    startedAssistant.status = 'loading';
    startedAssistant.referenceMappings = undefined;
    startedAssistant.diagnostics = undefined;
    startedAssistant.readingArtifacts = undefined;
    startedAssistant.readingStatePatch = undefined;
    startedAssistant.researchAuditTrail = undefined;
    startedAssistant.researchEvents = [];
  }
  if (typeof payload.answerSlotId === 'number') startedAssistant.answerSlotId = payload.answerSlotId;
  if (typeof payload.answerRevision === 'number') startedAssistant.answerRevision = payload.answerRevision;
  if (typeof payload.retryOfGenerationId === 'string') {
    startedAssistant.retryOfGenerationId = payload.retryOfGenerationId;
  }
  if (typeof payload.retryOfConversationRecordId === 'number') {
    startedAssistant.retryOfConversationRecordId = payload.retryOfConversationRecordId;
  }

  if (payload.conversationId) {
    chatStore.loadConversationScope(payload.conversationId).catch(() => {});
  }
  if (!startedAssistant.timestamp && timestamp) {
    startedAssistant.timestamp = timestamp;
  }
}

function applyCompletionStatus(assistant: Api.Chat.Message, payload: Record<string, any>) {
  if (payload.status === 'finished' && assistant.status !== 'error') {
    assistant.status = 'finished';
  } else if (payload.status === 'failed') {
    assistant.status = 'error';
  }
}

function applyCompletionMetadata(assistant: Api.Chat.Message, payload: Record<string, any>) {
  if (payload.referenceMappings) {
    assistant.referenceMappings = payload.referenceMappings;
  }
  if (typeof payload.conversationRecordId === 'number') {
    assistant.conversationRecordId = payload.conversationRecordId;
  }
  if (typeof payload.answerSlotId === 'number') {
    assistant.answerSlotId = payload.answerSlotId;
  }
  if (typeof payload.answerRevision === 'number') {
    assistant.answerRevision = payload.answerRevision;
  }
  if (typeof payload.retryOfGenerationId === 'string') {
    assistant.retryOfGenerationId = payload.retryOfGenerationId;
  }
  if (typeof payload.retryOfConversationRecordId === 'number') {
    assistant.retryOfConversationRecordId = payload.retryOfConversationRecordId;
  }
}

function applyCompletionArtifacts(assistant: Api.Chat.Message, payload: Record<string, any>) {
  if (payload.diagnostics) {
    assistant.diagnostics = payload.diagnostics;
  }
  if (Array.isArray(payload.productStateItems)) {
    assistant.productStateItems = payload.productStateItems as Api.Chat.ProductStateItem[];
  }
  if (payload.readingArtifacts && typeof payload.readingArtifacts === 'object') {
    assistant.readingArtifacts = payload.readingArtifacts;
  }
  if (payload.readingStatePatch && typeof payload.readingStatePatch === 'object') {
    assistant.readingStatePatch = payload.readingStatePatch;
  }
  if (payload.researchAuditTrail && typeof payload.researchAuditTrail === 'object') {
    assistant.researchAuditTrail = payload.researchAuditTrail as Api.Chat.ResearchAuditTrail;
  }
}

function handleCompletionPayload(assistant: Api.Chat.Message, payload: Record<string, any>) {
  flushPendingStreamChunks(assistant);
  flushPendingResearchEvents(assistant);
  applyCompletionStatus(assistant, payload);
  applyCompletionMetadata(assistant, payload);
  applyCompletionArtifacts(assistant, payload);
  assistant.route = normalizeChatRoute(payload.route) || assistant.route;
  assistant.route = normalizeChatRoute(payload.diagnostics?.route) || assistant.route;
  stopGenerationStatusMonitor();
  chatStore.loadSessionIndex({ silent: true }).catch(() => {});
}

function handleStopPayload(assistant: Api.Chat.Message) {
  flushPendingStreamChunks(assistant);
  flushPendingResearchEvents(assistant);
  if (assistant.status !== 'error') {
    assistant.status = 'finished';
  }
  stopGenerationStatusMonitor();
}

function handleErrorPayload(assistant: Api.Chat.Message, payload: Record<string, any>) {
  discardPendingStreamChunks(assistant);
  flushPendingResearchEvents(assistant);

  const message = buildWsErrorMessage(payload);
  assistant.status = 'error';
  assistant.content = message;
  stopGenerationStatusMonitor();

  window.$message?.error(message);
}

function handleChunkPayload(assistant: Api.Chat.Message, payload: Record<string, any>) {
  assistant.status = 'loading';
  const chunk = typeof payload.chunk === 'string' ? payload.chunk : '';
  if (!chunk) return;
  pendingStreamChunks.set(assistant, `${pendingStreamChunks.get(assistant) || ''}${chunk}`);
  scheduleStreamFlush();
}

function stopGenerationStatusMonitor() {
  if (generationStatusTimer !== null) {
    window.clearInterval(generationStatusTimer);
    generationStatusTimer = null;
  }
}

function startGenerationStatusMonitor() {
  stopGenerationStatusMonitor();
  lastStreamContentLength = 0;
  lastStreamContentChangedAt = Date.now();
  generationStatusTimer = window.setInterval(async () => {
    const assistant = findAssistantMessage(latestMessage.value?.generationId);
    if (!assistant || assistant.role !== 'assistant') {
      stopGenerationStatusMonitor();
      return;
    }
    if (!['pending', 'loading'].includes(assistant.status || '')) {
      stopGenerationStatusMonitor();
      return;
    }
    if (assistant.content.length !== lastStreamContentLength) {
      lastStreamContentLength = assistant.content.length;
      lastStreamContentChangedAt = Date.now();
      return;
    }
    if (Date.now() - lastStreamContentChangedAt < 8000) {
      return;
    }

    const snapshot = await chatStore.fetchGenerationSnapshot(assistant.generationId || '');
    if (!snapshot || snapshot.status === 'STREAMING') {
      return;
    }
    chatStore.upsertGenerationSnapshot(snapshot);
    const refreshedAssistant = findAssistantMessage(snapshot.generationId);
    if (refreshedAssistant?.status === 'finished') {
      stopGenerationStatusMonitor();
    } else if (refreshedAssistant?.status === 'error') {
      stopGenerationStatusMonitor();
    }
  }, 2000);
}

function handleResearchProgressPayload(assistant: Api.Chat.Message, payload: Record<string, any>) {
  assistant.status = 'loading';
  const event = payload as Api.Chat.ResearchProgressEvent;
  const sequence = Number(event.sequence || 0);
  const knownSequences = knownResearchSequencesFor(assistant);
  if (sequence > 0) {
    if (knownSequences.has(sequence)) return;
    knownSequences.add(sequence);
  }

  pendingResearchEvents.set(assistant, [...(pendingResearchEvents.get(assistant) || []), event]);
  scheduleResearchEventFlush();
}

watch(wsData, val => {
  if (!val) return;

  let payload: Record<string, any>;

  try {
    payload = JSON.parse(val);
  } catch {
    return;
  }

  const assistant = findAssistantMessage(payload.generationId, payload);

  if (!assistant) return;

  if (payload.type === 'start') {
    handleStartPayload(assistant, payload);
    return;
  }

  if (payload.type === 'completion') {
    handleCompletionPayload(assistant, payload);
    return;
  }

  if (payload.type === 'research_progress') {
    handleResearchProgressPayload(assistant, payload);
    return;
  }

  if (payload.type === 'stop') {
    handleStopPayload(assistant);
    return;
  }

  if (payload.error || Number(payload.code) >= 400) {
    handleErrorPayload(assistant, payload);
    return;
  }

  if (payload.chunk) {
    handleChunkPayload(assistant, payload);
  }
});

const handleSend = async (messageOverride?: string) => {
  if (sourceScopeUpdating.value) {
    return;
  }

  if (isSending.value) {
    if (messageOverride) {
      window.$message?.warning('当前回答还在生成，完成后再重试');
      return;
    }

    const { error, data: tokenData } = await request<Api.Chat.Token>({
      url: 'chat/websocket-token'
    });
    if (error) return;

    chatStore.wsSend(
      JSON.stringify({
        type: 'stop',
        generationId: latestMessage.value.generationId,
        _internal_cmd_token: tokenData.cmdToken
      })
    );

    const assistant = list.value[list.value.length - 1];
    flushPendingStreamChunks(assistant);
    flushPendingResearchEvents(assistant);
    assistant.status = 'finished';
    if (!latestMessage.value.content) list.value.pop();
    return;
  }

  const outgoingMessage = (messageOverride ?? input.value.message).trim();
  if (!outgoingMessage) {
    return;
  }

  let targetConversationId = conversationId.value;
  if (!targetConversationId) {
    targetConversationId = await chatStore.createNewSession();
    if (!targetConversationId) {
      window.$message?.error('无法创建对话，请稍后再试');
      return;
    }
  }

  const outgoingReferenceFocusPayload = outgoingReferenceFocus(referenceFocus.value);

  list.value.push({
    content: outgoingMessage,
    role: 'user',
    conversationId: targetConversationId
  });
  list.value.push({
    content: '',
    role: 'assistant',
    status: 'pending',
    conversationId: targetConversationId
  });
  chatStore.wsSend(
    JSON.stringify({
      type: 'user_message',
      conversationId: targetConversationId,
      message: outgoingMessage,
      referenceFocus: outgoingReferenceFocusPayload
    })
  );
  input.value = { message: '' };
  chatStore.setReferenceFocus(null);
  startGenerationStatusMonitor();
};

const inputRef = ref();
const insertNewline = () => {
  const textarea = inputRef.value;
  const start = textarea.selectionStart;
  const end = textarea.selectionEnd;

  input.value.message = `${input.value.message.substring(0, start)}\n${input.value.message.substring(end)}`;

  nextTick(() => {
    textarea.selectionStart = start + 1;
    textarea.selectionEnd = start + 1;
    textarea.focus();
  });
};

const handShortcut = (e: KeyboardEvent) => {
  if (e.key === 'Enter') {
    e.preventDefault();

    if (!e.shiftKey && !e.ctrlKey) {
      handleSend();
    } else insertNewline();
  }
};

onUnmounted(() => {
  flushPendingStreamChunks();
  flushPendingResearchEvents();
  stopGenerationStatusMonitor();
});
</script>

<template>
  <div class="chat-input-wrap" :class="props.variant === 'hero' ? 'chat-input-wrap--hero' : 'chat-input-wrap--dock'">
    <div v-if="props.variant === 'dock' && searchScopeHint" class="search-scope-hint mx-auto w-full px-1">
      {{ searchScopeHint }}
    </div>
    <div
      class="chat-input-shell mx-auto w-full flex items-end gap-2 px-3.5 py-2.5"
      :class="props.variant === 'hero' ? 'max-w-[960px]' : 'max-w-[960px]'"
    >
      <div class="chat-input-main">
        <div v-if="referenceFocus" class="scope-chip">
          <icon-lucide:quote class="scope-chip__icon" />
          <span>{{ referenceFocusLabel }}</span>
          <button type="button" aria-label="清除引用焦点" @click="clearReferenceFocus">
            <icon-lucide:x />
          </button>
        </div>
        <textarea
          ref="inputRef"
          v-model.trim="input.message"
          placeholder="Ask about a paper, method, claim, table, or citation"
          class="chat-input-textarea max-h-32 min-h-6 w-full flex-1 resize-none border-none bg-transparent py-1 text-14px caret-[rgb(var(--primary-color))] outline-none"
          @keydown="handShortcut"
        />
      </div>
      <NButton
        :disabled="sendDisabled"
        class="shrink-0 self-end"
        size="small"
        circle
        :type="isSending ? 'warning' : 'primary'"
        @click="() => handleSend()"
      >
        <template #icon>
          <icon-lucide:square v-if="isSending" class="text-16px" />
          <icon-lucide:arrow-up v-else class="text-16px" />
        </template>
      </NButton>
    </div>
    <div
      class="chat-input-footer mx-auto mt-1.5 w-full flex items-center justify-between gap-3 px-1"
      :class="props.variant === 'hero' ? 'max-w-[960px]' : 'max-w-[960px]'"
    >
      <SessionScopePicker
        v-if="props.variant === 'hero'"
        :conversation-id="conversationId"
        :scope="currentScope"
        :disabled="!canEditSourceScope"
        @update:busy="sourceScopeUpdating = $event"
      />
      <div class="flex items-center gap-2" :class="props.variant === 'hero' ? 'ml-auto' : ''">
        <div class="flex items-center gap-1">
          <span class="connection-dot inline-block h-1.5 w-1.5 rounded-full" :class="connectionDotClass" />
          <span class="chat-input-muted text-11px">{{ connectionText }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat-input-main {
  display: flex;
  min-width: 0;
  flex: 1 1 0;
  flex-direction: column;
  gap: 6px;
}

.chat-input-textarea {
  color: var(--color-text);
  line-height: 1.55;
  outline: none !important;
  box-shadow: none !important;
}

.chat-input-textarea:focus,
.chat-input-textarea:focus-visible {
  outline: none !important;
  box-shadow: none !important;
}

.chat-input-muted {
  color: var(--color-text-muted);
}

@media (max-width: 640px) {
  .chat-input-footer {
    align-items: stretch;
    flex-direction: column;
  }

  .chat-input-footer > :last-child {
    margin-left: 0;
  }
}

.search-scope-hint {
  max-width: 960px;
  margin-bottom: 6px;
  color: color-mix(in srgb, var(--color-text-muted) 72%, transparent);
  font-size: 11px;
  line-height: 16px;
}

.connection-dot--open {
  background: var(--color-success);
}

.connection-dot--pending {
  background: var(--color-warning);
}

.connection-dot--closed {
  background: var(--color-error);
}

.scope-chip {
  display: inline-flex;
  width: fit-content;
  max-width: 100%;
  align-items: center;
  gap: 6px;
  border: 1px solid color-mix(in srgb, var(--color-citation) 38%, var(--color-border));
  border-radius: 999px;
  background: var(--color-citation-soft-bg);
  padding: 3px 7px;
  color: var(--color-text);
  font-size: 12px;
  line-height: 16px;
}

.scope-chip span {
  overflow: hidden;
  max-width: min(520px, calc(100vw - 160px));
  text-overflow: ellipsis;
  white-space: nowrap;
}

.scope-chip__icon {
  flex: 0 0 auto;
  color: var(--color-citation);
}

.scope-chip button {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border: 0;
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
  padding: 0;
}

.scope-chip button:hover {
  color: var(--color-text);
}

.scope-chip button:focus,
.scope-chip button:focus-visible {
  outline: none !important;
  box-shadow: none !important;
}
</style>

<style scoped>
.chat-input-wrap {
  position: relative;
  flex-shrink: 0;
}

.chat-input-wrap--dock {
  background: #fff;
  padding: 8px 16px max(12px, env(safe-area-inset-bottom));
}

.chat-input-wrap--hero {
  width: 100%;
}

.chat-input-wrap--hero .chat-input-shell {
  min-height: 112px;
  align-items: flex-end;
  padding: 17px 18px;
}

.chat-input-wrap--hero .chat-input-textarea {
  min-height: 74px;
  font-size: 15px;
  line-height: 1.55;
}

.chat-input-shell {
  max-width: 960px !important;
  min-height: 68px;
  border-radius: 24px;
  background: #f5f5f5 !important;
  padding: 14px 16px;
  transition: background-color 0.18s ease;
}

.chat-input-shell:focus-within {
  background: #f1f2f4 !important;
}

.chat-input-shell :deep(.n-button:focus),
.chat-input-shell :deep(.n-button:focus-visible) {
  outline: none !important;
  box-shadow: none !important;
}

.chat-input-shell :deep(.n-button__state-border) {
  border: 0 !important;
}

.chat-input-shell :deep(.n-base-wave) {
  display: none !important;
}

.chat-input-footer {
  max-width: 960px !important;
}

@media (max-width: 640px) {
  .chat-input-wrap--dock {
    padding-inline: 12px;
  }

  .chat-input-shell {
    min-height: 58px;
    border-radius: 20px;
    padding: 10px 12px;
  }
}
</style>
