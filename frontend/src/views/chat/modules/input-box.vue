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

const cooldownText = computed(() => {
  if (!isRateLimited.value) {
    return '';
  }
  return `${rateLimitRemainingSeconds.value} 秒后可重新发送`;
});

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

  list.value.push({
    content: input.value.message,
    role: 'user'
  });
  list.value.push({
    content: '',
    role: 'assistant',
    status: 'pending',
    toolEvents: []
  });
  chatStore.wsSend(input.value.message);
  input.value.message = '';
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
      <textarea
        ref="inputRef"
        v-model.trim="input.message"
        placeholder="Search papers, claims, methods... Enter 发送，Shift+Enter 换行"
        class="max-h-32 min-h-6 w-full flex-1 resize-none border-none bg-transparent py-1 text-14px caret-[rgb(var(--primary-color))] outline-none"
        :style="{ color: 'var(--color-text)' }"
        @keydown="handShortcut"
      />
      <NButton
        :disabled="sendDisabled"
        class="shrink-0 self-end"
        size="small"
        circle
        :type="isSending ? 'warning' : 'primary'"
        @click="handleSend"
      >
        <template #icon>
          <icon-material-symbols:stop-rounded v-if="isSending" class="text-16px" />
          <icon-material-symbols:arrow-upward-rounded v-else class="text-16px" />
        </template>
      </NButton>
    </div>
    <div
      class="mx-auto mt-1.5 w-full flex items-center justify-between px-1"
      :class="props.variant === 'hero' ? 'max-w-[760px]' : 'max-w-[960px]'"
    >
      <div class="flex items-center gap-2">
        <div class="flex items-center gap-1">
          <span
            class="inline-block h-1.5 w-1.5 rounded-full"
            :style="{
              background:
                connectionStatus === 'OPEN'
                  ? 'var(--color-success)'
                  : connectionStatus === 'CONNECTING' || connectionStatus === 'RECONNECTING'
                  ? 'var(--color-warning)'
                  : 'var(--color-error)'
            }"
            :class="{ 'animate-pulse': connectionStatus === 'CONNECTING' || connectionStatus === 'RECONNECTING' }"
          />
          <span class="text-11px" :style="{ color: 'var(--color-text-muted)' }">{{ connectionText }}</span>
        </div>
        <span v-if="isRateLimited" class="text-11px text-[rgb(var(--primary-color))]">
          {{ cooldownText }}
        </span>
      </div>
      <span class="text-11px" :style="{ color: 'var(--color-text-muted)' }">Shift+Enter 换行</span>
    </div>
  </div>
</template>

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
