<script setup lang="ts">
const props = withDefaults(
  defineProps<{
    variant?: 'dock' | 'hero';
  }>(),
  {
    variant: 'dock'
  }
);

const chatStore = useChatStore();
const { connectionStatus, input, isRateLimited, list, rateLimitRemainingSeconds, wsData } = storeToRefs(chatStore);

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

type RetrievalBudgetProfile = NonNullable<Api.Chat.Scope['retrievalBudgetProfile']>;

const DEFAULT_RETRIEVAL_BUDGET_PROFILE: RetrievalBudgetProfile = 'interactive';
const retrievalBudgetOptions: Array<{
  value: RetrievalBudgetProfile;
  label: string;
  tooltip: string;
}> = [
  { value: 'interactive', label: '快速', tooltip: 'K3 interactive' },
  { value: 'high_recall', label: '高召回', tooltip: 'K5 high recall' },
  { value: 'deep_audit', label: '审计', tooltip: 'K7 deep audit' }
];

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
  if (isRateLimited.value) {
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

const cooldownText = computed(() => {
  if (!isRateLimited.value) {
    return '';
  }
  return `${rateLimitRemainingSeconds.value} 秒后可重新发送`;
});

const activeScope = computed(() => input.value.scope || null);
const sourcePickerVisible = ref(false);
const sourcePickerLoading = ref(false);
const sourcePickerQuery = ref('');
const sourcePapers = ref<Api.Paper.UploadTask[]>([]);
const selectedSourcePaperIds = ref<string[]>([]);
const activeRetrievalBudgetProfile = computed(
  () => input.value.retrievalBudgetProfile || DEFAULT_RETRIEVAL_BUDGET_PROFILE
);
const scopeLabel = computed(() => {
  const scope = activeScope.value;
  if (!scope) return '';
  if (!scope.referenceNumber && scope.paperTitles && scope.paperTitles.length > 1) {
    return `已选 ${scope.paperTitles.length} 篇论文`;
  }
  if (!scope.referenceNumber && scope.paperIds && scope.paperIds.length > 1) {
    return `已选 ${scope.paperIds.length} 篇论文`;
  }
  const paper = scope.paperTitle || scope.paperTitles?.[0] || 'Selected source';
  const parts = [paper];
  if (scope.pageNumber) parts.push(`p${scope.pageNumber}`);
  if (scope.referenceNumber) parts.push(`[${scope.referenceNumber}]`);
  return parts.join(' · ');
});

function clearScope() {
  input.value.scope = null;
}

function setRetrievalBudgetProfile(profile: RetrievalBudgetProfile) {
  input.value.retrievalBudgetProfile = profile;
  if (input.value.scope) {
    input.value.scope = {
      ...input.value.scope,
      retrievalBudgetProfile: profile
    };
  }
}

function outgoingScopeWithBudget(scope: Api.Chat.Scope | null): Api.Chat.Scope {
  return {
    ...(scope || {}),
    retrievalBudgetProfile: activeRetrievalBudgetProfile.value
  };
}

const filteredSourcePapers = computed(() => {
  const query = sourcePickerQuery.value.trim().toLowerCase();
  if (!query) {
    return sourcePapers.value;
  }
  return sourcePapers.value.filter(paper => {
    const haystack = [paper.paperTitle, paper.originalFilename, paper.authors, paper.venue]
      .filter(Boolean)
      .join(' ')
      .toLowerCase();
    return haystack.includes(query);
  });
});

function normalizePaperPayload(payload: Api.Paper.UploadTask[] | Api.Paper.List | null | undefined) {
  if (!payload) {
    return [];
  }
  if (Array.isArray(payload)) {
    return payload;
  }
  return payload.data || payload.content || [];
}

async function loadSourcePapers() {
  sourcePickerLoading.value = true;
  try {
    const { error, data } = await request<Api.Paper.UploadTask[] | Api.Paper.List>({
      url: '/papers?scope=accessible'
    });
    if (!error) {
      sourcePapers.value = normalizePaperPayload(data);
    }
  } finally {
    sourcePickerLoading.value = false;
  }
}

function openSourcePicker() {
  selectedSourcePaperIds.value = activeScope.value?.paperIds ? [...activeScope.value.paperIds] : [];
  sourcePickerVisible.value = true;
  if (!sourcePapers.value.length) {
    loadSourcePapers();
  }
}

function applySourceSelection() {
  const selected = sourcePapers.value.filter(paper => selectedSourcePaperIds.value.includes(paper.paperId));
  if (!selected.length) {
    clearScope();
    sourcePickerVisible.value = false;
    return;
  }
  input.value.scope = {
    paperIds: selected.map(paper => paper.paperId),
    paperTitles: selected.map(paper => paper.paperTitle || paper.originalFilename),
    retrievalBudgetProfile: activeRetrievalBudgetProfile.value,
    ...(selected.length === 1
      ? {
          paperId: selected[0].paperId,
          paperTitle: selected[0].paperTitle || selected[0].originalFilename,
          originalFilename: selected[0].originalFilename
        }
      : {})
  };
  sourcePickerVisible.value = false;
}

function useAutoSource() {
  selectedSourcePaperIds.value = [];
  clearScope();
  sourcePickerVisible.value = false;
}

function inferOutgoingRoute(message: string, scope: Api.Chat.Scope | null): Api.Chat.Message['route'] {
  if (isSmalltalkMessage(message)) {
    return 'SMALLTALK';
  }
  if (
    scope?.referenceNumber ||
    (scope?.paperId && (scope.matchedText || scope.matchedChunkText || scope.evidenceSnippet))
  ) {
    return 'REFERENCE_QA';
  }
  if (scope?.paperId || scope?.paperIds?.length) {
    return 'MANUAL_SOURCE_QA';
  }
  return 'AUTO_SOURCE_QA';
}

function isSmalltalkMessage(message: string) {
  const normalized = message
    .trim()
    .toLowerCase()
    .replace(/[\s!！?？。,.，;；:：、"'“”‘’()（）{}<>《》]+/g, '')
    .replace(/\[|\]/g, '');
  return ['hi', 'hello', 'hey', '你好', '您好', '在吗', '谢谢', 'thanks', 'ok', '好的'].includes(normalized);
}

function findAssistantMessage(generationId?: string) {
  if (generationId) {
    for (let i = list.value.length - 1; i >= 0; i -= 1) {
      const item = list.value[i];
      if (item?.role === 'assistant' && item.generationId === generationId) {
        return item;
      }
    }
  }

  const latest = list.value[list.value.length - 1];
  if (latest?.role === 'assistant') {
    return latest;
  }

  return null;
}

function handleStartPayload(assistant: Api.Chat.Message, payload: Record<string, any>) {
  assistant.generationId = payload.generationId || assistant.generationId;
  assistant.conversationId = payload.conversationId || assistant.conversationId;
  assistant.route = normalizeChatRoute(payload.route) || assistant.route;
  if (!assistant.timestamp && payload.timestamp) {
    assistant.timestamp = new Date(payload.timestamp).toISOString();
  }
}

function handleCompletionPayload(assistant: Api.Chat.Message, payload: Record<string, any>) {
  if (payload.status === 'finished' && assistant.status !== 'error') {
    assistant.status = 'finished';
  } else if (payload.status === 'failed') {
    assistant.status = 'error';
  }

  if (payload.referenceMappings) {
    assistant.referenceMappings = payload.referenceMappings;
  }
  if (payload.diagnostics) {
    assistant.diagnostics = payload.diagnostics;
  }
  assistant.route = normalizeChatRoute(payload.route) || assistant.route;
  assistant.route = normalizeChatRoute(payload.diagnostics?.route) || assistant.route;
  markExecutingToolsAsSuccess(assistant);
  stopGenerationStatusMonitor();
}

function handleStopPayload(assistant: Api.Chat.Message) {
  if (assistant.status !== 'error') {
    assistant.status = 'finished';
  }
  markExecutingToolsAsSuccess(assistant);
  stopGenerationStatusMonitor();
}

function handleErrorPayload(assistant: Api.Chat.Message, payload: Record<string, any>) {
  if (Number(payload.code) === 429) {
    chatStore.startRateLimitCountdown(Number(payload.retryAfterSeconds || 0));
  }

  const message = buildWsErrorMessage(payload);
  assistant.status = 'error';
  assistant.content = message;
  markExecutingToolsAsFailed(assistant);
  stopGenerationStatusMonitor();

  if (Number(payload.code) === 429) {
    window.$message?.warning(message);
  } else {
    window.$message?.error(message);
  }
}

function handleChunkPayload(assistant: Api.Chat.Message, payload: Record<string, any>) {
  assistant.status = 'loading';
  assistant.content += payload.chunk;
  lastStreamContentLength = assistant.content.length;
  lastStreamContentChangedAt = Date.now();
}

function stopGenerationStatusMonitor() {
  if (generationStatusTimer !== null) {
    window.clearInterval(generationStatusTimer);
    generationStatusTimer = null;
  }
}

function startGenerationStatusMonitor() {
  stopGenerationStatusMonitor();
  const startedAt = Date.now();
  lastStreamContentLength = 0;
  lastStreamContentChangedAt = startedAt;
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
    if (Date.now() - startedAt > 130_000) {
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
      markExecutingToolsAsSuccess(refreshedAssistant);
      stopGenerationStatusMonitor();
    } else if (refreshedAssistant?.status === 'error') {
      markExecutingToolsAsFailed(refreshedAssistant);
      stopGenerationStatusMonitor();
    }
  }, 2000);
}

function markExecutingToolsAsSuccess(assistant: Api.Chat.Message) {
  updateExecutingToolStatus(assistant, 'success');
}

function markExecutingToolsAsFailed(assistant: Api.Chat.Message) {
  updateExecutingToolStatus(assistant, 'failed');
}

function updateExecutingToolStatus(assistant: Api.Chat.Message, status: Api.Chat.AgentToolEvent['status']) {
  if (!assistant.toolEvents?.length) {
    return;
  }

  let changed = false;
  const timestamp = Date.now();
  const toolEvents = assistant.toolEvents.map(event => {
    if (event.status !== 'executing') {
      return event;
    }
    changed = true;
    return {
      ...event,
      status,
      timestamp
    };
  });

  if (changed) {
    assistant.toolEvents = toolEvents;
  }
}

function handleToolCallPayload(assistant: Api.Chat.Message, payload: Record<string, any>) {
  const id = typeof payload.toolCallId === 'string' ? payload.toolCallId : '';
  const tool = typeof payload.tool === 'string' ? payload.tool : '';
  const status = typeof payload.status === 'string' ? payload.status : 'executing';
  if (!tool || !['executing', 'success', 'failed'].includes(status)) {
    return;
  }

  assistant.status = 'loading';
  assistant.toolEvents ||= [];
  const event = {
    id,
    tool,
    status: status as Api.Chat.AgentToolEvent['status'],
    timestamp: Number(payload.timestamp || Date.now())
  };

  const matchEvent = (item: Api.Chat.AgentToolEvent) => {
    if (id && item.id) {
      return item.id === id;
    }
    if (!id && !item.id) {
      return item.tool === tool;
    }
    return false;
  };

  if (assistant.toolEvents.some(matchEvent)) {
    assistant.toolEvents = assistant.toolEvents.map(item => (matchEvent(item) ? event : item));
  } else {
    assistant.toolEvents = [...assistant.toolEvents, event];
  }
}

watch(wsData, val => {
  if (!val) return;

  let payload: Record<string, any>;

  try {
    payload = JSON.parse(val);
  } catch {
    return;
  }

  const assistant = findAssistantMessage(payload.generationId);

  if (!assistant) return;

  if (payload.type === 'start') {
    handleStartPayload(assistant, payload);
    return;
  }

  if (payload.type === 'completion') {
    handleCompletionPayload(assistant, payload);
    return;
  }

  if (payload.type === 'tool_call') {
    handleToolCallPayload(assistant, payload);
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

const handleSend = async () => {
  if (isRateLimited.value) {
    window.$message?.warning(`当前发送受限，${cooldownText.value}`);
    return;
  }

  if (isSending.value) {
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

    list.value[list.value.length - 1].status = 'finished';
    if (!latestMessage.value.content) list.value.pop();
    return;
  }

  const outgoingMessage = input.value.message;
  const outgoingScope = outgoingScopeWithBudget(input.value.scope || null);
  const route = inferOutgoingRoute(outgoingMessage, outgoingScope);

  list.value.push({
    content: outgoingMessage,
    role: 'user'
  });
  list.value.push({
    content: '',
    role: 'assistant',
    status: 'pending',
    route,
    toolEvents: []
  });
  chatStore.wsSend(
    JSON.stringify({
      type: 'chat',
      message: outgoingMessage,
      scope: outgoingScope
    })
  );
  input.value = { message: '', retrievalBudgetProfile: activeRetrievalBudgetProfile.value };
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
  stopGenerationStatusMonitor();
});
</script>

<template>
  <div class="chat-input-wrap" :class="props.variant === 'hero' ? 'chat-input-wrap--hero' : 'chat-input-wrap--dock'">
    <div
      v-if="props.variant === 'dock'"
      class="pointer-events-none absolute inset-x-0 h-6 from-[var(--color-bg)]/95 to-transparent bg-gradient-to-t -top-6 dark:from-[var(--color-bg)]/95"
    />
    <div
      class="chat-input-shell mx-auto w-full flex items-end gap-2 px-3.5 py-2.5"
      :class="props.variant === 'hero' ? 'max-w-[760px]' : 'max-w-[960px]'"
    >
      <div class="chat-input-main">
        <div v-if="activeScope" class="scope-chip">
          <icon-lucide:quote class="scope-chip__icon" />
          <span>{{ scopeLabel }}</span>
          <button type="button" aria-label="清除引用范围" @click="clearScope">
            <icon-lucide:x />
          </button>
        </div>
        <textarea
          ref="inputRef"
          v-model.trim="input.message"
          placeholder="Search papers, claims, methods... Enter 发送，Shift+Enter 换行"
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
        @click="handleSend"
      >
        <template #icon>
          <icon-lucide:square v-if="isSending" class="text-16px" />
          <icon-lucide:arrow-up v-else class="text-16px" />
        </template>
      </NButton>
    </div>
    <div
      class="mx-auto mt-1.5 w-full flex items-center justify-between px-1"
      :class="props.variant === 'hero' ? 'max-w-[760px]' : 'max-w-[960px]'"
    >
      <div class="flex items-center gap-2">
        <div class="flex items-center gap-1">
          <span class="connection-dot inline-block h-1.5 w-1.5 rounded-full" :class="connectionDotClass" />
          <span class="chat-input-muted text-11px">{{ connectionText }}</span>
        </div>
        <NButton quaternary size="tiny" class="source-picker-trigger" @click="openSourcePicker">
          <template #icon>
            <icon-lucide:library class="text-13px" />
          </template>
          重点检索论文
        </NButton>
        <div class="retrieval-budget-segment" aria-label="检索深度">
          <NTooltip v-for="option in retrievalBudgetOptions" :key="option.value" trigger="hover" placement="top">
            <template #trigger>
              <button
                type="button"
                class="retrieval-budget-button"
                :class="{ 'retrieval-budget-button--active': activeRetrievalBudgetProfile === option.value }"
                :aria-pressed="activeRetrievalBudgetProfile === option.value"
                @click="setRetrievalBudgetProfile(option.value)"
              >
                <icon-lucide:zap v-if="option.value === 'interactive'" class="text-12px" />
                <icon-lucide:search v-else-if="option.value === 'high_recall'" class="text-12px" />
                <icon-lucide:shield v-else class="text-12px" />
                <span>{{ option.label }}</span>
              </button>
            </template>
            {{ option.tooltip }}
          </NTooltip>
        </div>
        <span v-if="isRateLimited" class="text-11px text-[rgb(var(--primary-color))]">
          {{ cooldownText }}
        </span>
      </div>
      <span class="chat-input-muted text-11px">Shift+Enter 换行</span>
    </div>

    <NModal
      v-model:show="sourcePickerVisible"
      preset="dialog"
      title="重点检索论文"
      :show-icon="false"
      class="source-picker-modal"
    >
      <div class="source-picker">
        <NInput v-model:value="sourcePickerQuery" clearable placeholder="按标题、文件名、作者或会议筛选" size="small">
          <template #prefix>
            <icon-lucide:search class="text-14px" />
          </template>
        </NInput>
        <NSpin :show="sourcePickerLoading">
          <NEmpty v-if="!filteredSourcePapers.length" description="暂无可选择论文" class="py-8" />
          <NCheckboxGroup v-else v-model:value="selectedSourcePaperIds">
            <NScrollbar class="source-picker__list">
              <label v-for="paper in filteredSourcePapers" :key="paper.paperId" class="source-picker__row">
                <NCheckbox :value="paper.paperId" />
                <span class="source-picker__paper">
                  <span class="source-picker__title">{{ paper.paperTitle || paper.originalFilename }}</span>
                  <span class="source-picker__filename">{{ paper.originalFilename }}</span>
                </span>
              </label>
            </NScrollbar>
          </NCheckboxGroup>
        </NSpin>
        <div class="source-picker__footer">
          <NButton secondary size="small" @click="useAutoSource">使用自动发现</NButton>
          <NButton type="primary" size="small" @click="applySourceSelection">应用来源</NButton>
        </div>
      </div>
    </NModal>
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
}

.chat-input-muted {
  color: var(--color-text-muted);
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
  border: 1px solid color-mix(in srgb, #b7791f 35%, var(--color-border));
  border-radius: 999px;
  background: color-mix(in srgb, #f6d58a 18%, var(--color-surface));
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
  color: #a96c10;
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

.source-picker-trigger {
  color: var(--color-text-muted);
}

.retrieval-budget-segment {
  display: inline-flex;
  align-items: center;
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: 7px;
  background: var(--color-surface);
}

.retrieval-budget-button {
  display: inline-flex;
  min-height: 24px;
  align-items: center;
  gap: 4px;
  border: 0;
  border-right: 1px solid var(--color-border);
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
  font-size: 11px;
  line-height: 16px;
  padding: 3px 7px;
}

.retrieval-budget-button:last-child {
  border-right: 0;
}

.retrieval-budget-button:hover {
  color: var(--color-text);
}

.retrieval-budget-button--active {
  background: var(--color-primary-soft-bg);
  color: var(--color-primary);
}

.source-picker {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.source-picker__list {
  max-height: min(420px, 58vh);
}

.source-picker__row {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  border-bottom: 1px solid var(--color-border);
  cursor: pointer;
  padding: 10px 2px;
}

.source-picker__row:last-child {
  border-bottom: 0;
}

.source-picker__paper {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 2px;
}

.source-picker__title,
.source-picker__filename {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.source-picker__title {
  color: var(--color-text);
  font-size: 13px;
}

.source-picker__filename {
  color: var(--color-text-muted);
  font-size: 12px;
}

.source-picker__footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>

<style scoped>
.chat-input-wrap {
  position: relative;
  flex-shrink: 0;
}

.chat-input-wrap--dock {
  background: var(--color-bg);
  padding: 8px 16px 12px;
}

.chat-input-wrap--hero {
  width: 100%;
}

.chat-input-shell {
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-surface) !important;
  box-shadow: var(--shadow-card);
  transition:
    border-color 0.18s ease,
    box-shadow 0.18s ease,
    background-color 0.18s ease;
}

.chat-input-shell:focus-within {
  border-color: var(--color-primary);
  box-shadow:
    var(--shadow-card),
    0 0 0 3px var(--color-primary-soft-bg);
}

.dark .chat-input-wrap--dock {
  background: var(--color-bg);
}

.dark .chat-input-shell {
  border-color: var(--color-border);
  background: var(--color-surface) !important;
  box-shadow: none;
}
</style>
