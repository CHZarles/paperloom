import { useWebSocket } from '@vueuse/core';
import { request } from '@/service/request';
import { applyGenerationStartToMessages, shouldApplyLoadedConversationMessages } from './message-list';

function createChatClientId() {
  if (globalThis.crypto?.randomUUID) {
    return globalThis.crypto.randomUUID();
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
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
const MAX_RETAINED_RESEARCH_EVENTS = 200;

function boundResearchEvents(message: Api.Chat.Message) {
  if (!message.researchEvents || message.researchEvents.length <= MAX_RETAINED_RESEARCH_EVENTS) return message;
  return {
    ...message,
    researchEvents: message.researchEvents.slice(-MAX_RETAINED_RESEARCH_EVENTS)
  };
}

function normalizeChatRoute(route: unknown): Api.Chat.Route | undefined {
  return typeof route === 'string' && CHAT_ROUTES.has(route as Api.Chat.Route) ? (route as Api.Chat.Route) : undefined;
}

export const useChatStore = defineStore(SetupStoreId.Chat, () => {
  const NON_RETRYABLE_CLOSE_CODES = new Set([1002, 1003, 1007, 1008]);
  const WS_HEARTBEAT_PING = '__chat_ping__';
  const WS_HEARTBEAT_PONG = '__chat_pong__';
  const chatClientId = createChatClientId();

  const conversationId = ref<string>('');
  const input = ref<Api.Chat.Input>({ message: '' });
  const list = ref<Api.Chat.Message[]>([]);
  const sessions = ref<Api.Chat.ConversationSession[]>([]);
  const sessionsLoading = ref(false);
  const hasOlderMessages = ref(false);
  const messagesLoadingOlder = ref(false);
  const activeTab = ref<'active' | 'archived'>('active');
  const currentScope = ref<Api.Chat.ConversationScope | null>(null);
  const referenceFocus = ref<Api.Chat.Scope | null>(null);

  const store = useAuthStore();

  const sessionId = ref<string>('');
  const allowReconnect = ref(true);
  const authFailureNotified = ref(false);
  const handshakeConfirmed = ref(false);
  const intentionalDisconnect = ref(false);
  const rateLimitUntil = ref<number | null>(null);
  const rateLimitRemainingSeconds = ref(0);
  let rateLimitTimer: ReturnType<typeof setInterval> | null = null;
  let scopedSessionCreationInFlight = false;
  let sessionIndexInFlight: Promise<Api.Chat.ConversationSession[] | null> | null = null;
  let loadedConversationDetailsId = '';
  let conversationSelectionVersion = 0;
  const conversationDetailsInFlight = new Map<string, Promise<void>>();
  const HISTORY_RECORD_PAGE_SIZE = 15;

  function mapGenerationStatus(status?: Api.Chat.GenerationStatus): Api.Chat.Message['status'] {
    if (status === 'COMPLETED' || status === 'CANCELLED') {
      return 'finished';
    }
    if (status === 'FAILED') {
      return 'error';
    }
    if (status === 'STREAMING') {
      return 'loading';
    }
    return 'pending';
  }

  function findAssistantIndexByGenerationId(generationId?: string) {
    if (!generationId) {
      return -1;
    }
    for (let i = list.value.length - 1; i >= 0; i -= 1) {
      const item = list.value[i];
      if (item?.role === 'assistant' && item.generationId === generationId) {
        return i;
      }
    }
    return -1;
  }

  function getPendingGenerationId() {
    for (let i = list.value.length - 1; i >= 0; i -= 1) {
      const item = list.value[i];
      if (item?.role === 'assistant' && ['pending', 'loading'].includes(item.status || '') && item.generationId) {
        return item.generationId;
      }
    }
    return '';
  }

  function upsertGenerationSnapshot(snapshot: Api.Chat.GenerationSnapshot | null) {
    if (!snapshot) {
      return;
    }
    conversationId.value = snapshot.conversationId || conversationId.value;

    const assistantIndex = findAssistantIndexByGenerationId(snapshot.generationId);
    const nextStatus = mapGenerationStatus(snapshot.status);

    if (assistantIndex >= 0) {
      const assistant = list.value[assistantIndex];
      assistant.content = snapshot.content || assistant.content || '';
      assistant.status = nextStatus;
      assistant.generationId = snapshot.generationId;
      assistant.conversationId = snapshot.conversationId;
      assistant.timestamp ||= snapshot.updatedAt;
      if (snapshot.referenceMappings && Object.keys(snapshot.referenceMappings).length > 0) {
        assistant.referenceMappings = snapshot.referenceMappings;
      }
      if (typeof snapshot.conversationRecordId === 'number') {
        assistant.conversationRecordId = snapshot.conversationRecordId;
      }
      if (snapshot.diagnostics) {
        assistant.diagnostics = snapshot.diagnostics;
        assistant.route = normalizeChatRoute(snapshot.diagnostics.route) || assistant.route;
      }
      if (snapshot.readingArtifacts) {
        assistant.readingArtifacts = snapshot.readingArtifacts;
      }
      if (snapshot.readingStatePatch) {
        assistant.readingStatePatch = snapshot.readingStatePatch;
      }
      if (snapshot.researchAuditTrail) {
        assistant.researchAuditTrail = snapshot.researchAuditTrail;
      }
      if (snapshot.progressEvents) {
        assistant.researchEvents = snapshot.progressEvents.slice(-MAX_RETAINED_RESEARCH_EVENTS);
      }
      return;
    }

    list.value.push({
      role: 'user',
      content: snapshot.question,
      conversationId: snapshot.conversationId,
      generationId: snapshot.generationId,
      timestamp: snapshot.createdAt
    });
    list.value.push({
      role: 'assistant',
      content: snapshot.content || '',
      status: nextStatus,
      conversationId: snapshot.conversationId,
      generationId: snapshot.generationId,
      timestamp: snapshot.updatedAt,
      conversationRecordId:
        typeof snapshot.conversationRecordId === 'number' ? snapshot.conversationRecordId : undefined,
      referenceMappings: snapshot.referenceMappings,
      diagnostics: snapshot.diagnostics,
      readingArtifacts: snapshot.readingArtifacts,
      readingStatePatch: snapshot.readingStatePatch,
      researchAuditTrail: snapshot.researchAuditTrail,
      researchEvents: snapshot.progressEvents?.slice(-MAX_RETAINED_RESEARCH_EVENTS),
      route: normalizeChatRoute(snapshot.diagnostics?.route)
    });
  }

  function applyGenerationStart(payload: {
    conversationId?: string;
    generationId?: string;
    timestamp?: string;
    route?: string;
  }) {
    const started = applyGenerationStartToMessages({
      currentConversationId: conversationId.value,
      messages: list.value,
      payload
    });

    conversationId.value = started.conversationId;
    list.value = started.messages;
    return started.assistant as Api.Chat.Message | null;
  }

  function applyLoadedMessages(messages: Api.Chat.Message[], targetConversationId?: string) {
    if (targetConversationId && targetConversationId !== conversationId.value) {
      return false;
    }
    const boundedMessages = messages.map(boundResearchEvents);
    if (
      !shouldApplyLoadedConversationMessages({
        currentMessages: list.value,
        loadedMessages: boundedMessages,
        targetConversationId
      })
    ) {
      return false;
    }

    list.value = boundedMessages;
    return true;
  }

  async function fetchGenerationSnapshot(generationId: string) {
    if (!generationId) {
      return null;
    }
    const { error, data } = await request<Api.Chat.GenerationSnapshot | null>({
      url: `chat/generation/${generationId}`
    });
    if (error) {
      return null;
    }
    return data || null;
  }

  async function fetchActiveGenerationSnapshot() {
    const { error, data } = await request<Api.Chat.GenerationSnapshot | null>({
      url: 'chat/active-generation',
      params: { clientId: chatClientId }
    });
    if (error) {
      return null;
    }
    return data || null;
  }

  async function syncGenerationAfterReconnect() {
    const pendingGenerationId = getPendingGenerationId();
    if (pendingGenerationId) {
      upsertGenerationSnapshot(await fetchGenerationSnapshot(pendingGenerationId));
      return;
    }
    upsertGenerationSnapshot(await fetchActiveGenerationSnapshot());
  }

  // ---- Session management ----

  async function fetchSessionIndex() {
    if (sessionIndexInFlight) return sessionIndexInFlight;

    sessionIndexInFlight = (async () => {
      const { error, data } = await request<Api.Chat.ConversationSession[]>({
        url: 'users/conversations'
      });
      if (error || !data) return null;
      sessions.value = data;
      return data;
    })();

    try {
      return await sessionIndexInFlight;
    } finally {
      sessionIndexInFlight = null;
    }
  }

  async function loadSessionIndex(options: { silent?: boolean } = {}) {
    const { silent = false } = options;
    if (!silent) sessionsLoading.value = true;
    try {
      const data = await fetchSessionIndex();
      if (!data) return [];

      const currentActive = data.find(session => session.status === 'ACTIVE' && session.current);
      const firstActive = data.find(session => session.status === 'ACTIVE');
      if (!conversationId.value) {
        conversationId.value = currentActive?.conversationId || firstActive?.conversationId || '';
      }
      return data;
    } finally {
      if (!silent) sessionsLoading.value = false;
    }
  }

  async function loadSessions(options: { silent?: boolean; loadDetails?: boolean } = {}) {
    const { silent = false, loadDetails = true } = options;
    const selectionVersion = conversationSelectionVersion;
    const data = await loadSessionIndex({ silent });
    if (!data.length || selectionVersion !== conversationSelectionVersion) return;

    const supportsCurrentMarker = data.some(session => Object.hasOwn(session, 'current'));
    if (loadDetails && !supportsCurrentMarker) {
      const currentSession = await fetchCurrentSession();
      if (selectionVersion !== conversationSelectionVersion) return;
      const currentActive = currentSession?.conversationId
        ? data.find(session => session.status === 'ACTIVE' && session.conversationId === currentSession.conversationId)
        : null;
      if (currentActive) conversationId.value = currentActive.conversationId;
    }

    if (loadDetails && conversationId.value) {
      await loadConversationDetails(conversationId.value);
    }
  }

  async function fetchCurrentSession() {
    const { error, data } = await request<Api.Chat.ConversationSession | Record<string, never>>({
      url: 'users/conversations/current'
    });
    if (error || !data || !('conversationId' in data)) {
      return null;
    }
    return data as Api.Chat.ConversationSession;
  }

  async function loadConversationScope(targetConversationId: string) {
    const { error, data } = await request<Api.Chat.ConversationScope>({
      url: `users/conversations/${targetConversationId}/scope`
    });
    if (targetConversationId === conversationId.value) {
      if (!error && data) currentScope.value = data;
      else currentScope.value = null;
    }
    return data || null;
  }

  async function updateConversationScope(
    targetConversationId: string,
    payload: Api.Chat.UpdateConversationScopePayload
  ) {
    const { error, data } = await request<Api.Chat.ConversationScope>({
      url: `users/conversations/${targetConversationId}/scope`,
      method: 'PUT',
      data: payload
    });
    if (!error && data && targetConversationId === conversationId.value) currentScope.value = data;
    return !error;
  }

  async function createSessionFromScope(payload: Api.Chat.UpdateConversationScopePayload) {
    if (scopedSessionCreationInFlight) return false;

    scopedSessionCreationInFlight = true;
    const previousConversationId = conversationId.value;
    const previousList = [...list.value];
    const previousScope = currentScope.value;
    const previousReferenceFocus = referenceFocus.value;

    try {
      const createdConversationId = await createNewSession();
      if (!createdConversationId) return false;
      const scoped = await updateConversationScope(createdConversationId, payload);
      if (scoped) {
        await loadSessionIndex({ silent: true });
        return true;
      }

      currentScope.value = null;
      referenceFocus.value = null;
      if (previousConversationId) {
        const restored = await switchSession(previousConversationId);
        if (restored) {
          list.value = previousList;
          currentScope.value = previousScope;
          referenceFocus.value = previousReferenceFocus;
        } else {
          list.value = [];
          await loadSessions();
        }
      } else {
        list.value = [];
        await loadSessions();
      }
      return false;
    } finally {
      scopedSessionCreationInFlight = false;
    }
  }

  function setReferenceFocus(scope: Api.Chat.Scope | null) {
    referenceFocus.value = scope;
  }

  async function createNewSession() {
    const { error, data } = await request<Api.Chat.ConversationSession>({
      url: 'users/conversations',
      method: 'POST'
    });
    if (!error && data) {
      conversationSelectionVersion += 1;
      conversationId.value = data.conversationId;
      list.value = [];
      hasOlderMessages.value = false;
      loadedConversationDetailsId = '';
      referenceFocus.value = null;
      await loadConversationScope(data.conversationId);
    }
    return !error && data ? data.conversationId : '';
  }

  async function switchSession(targetConversationId: string, options: { loadDetails?: boolean } = {}) {
    const { loadDetails = true } = options;
    if (targetConversationId === conversationId.value) {
      if (loadDetails) await loadConversationDetails(targetConversationId);
      return true;
    }
    const { error } = await request({
      url: `users/conversations/${targetConversationId}/switch`,
      method: 'PUT'
    });
    if (error) {
      return false;
    }
    conversationSelectionVersion += 1;
    conversationId.value = targetConversationId;
    list.value = [];
    hasOlderMessages.value = false;
    loadedConversationDetailsId = '';
    referenceFocus.value = null;
    currentScope.value = null;
    if (loadDetails) await loadConversationDetails(targetConversationId, { force: true });
    return true;
  }

  async function loadMessages(targetConversationId?: string, options: { older?: boolean } = {}) {
    const cid = targetConversationId || conversationId.value;
    if (!cid) {
      return false;
    }
    const { older = false } = options;
    let beforeRecordId: number | undefined;
    if (older) {
      const recordIds = list.value
        .map(message => message.conversationRecordId)
        .filter((id): id is number => typeof id === 'number');
      beforeRecordId = recordIds.length ? Math.min(...recordIds) : undefined;
      if (!beforeRecordId) {
        hasOlderMessages.value = false;
        return false;
      }
      messagesLoadingOlder.value = true;
    }

    const { error, data } = await request<Api.Chat.Message[]>({
      url: 'users/conversation',
      params: {
        conversationId: cid,
        limit: HISTORY_RECORD_PAGE_SIZE,
        ...(beforeRecordId ? { beforeRecordId } : {})
      }
    });
    if (older) messagesLoadingOlder.value = false;
    if (error || !data || cid !== conversationId.value) return false;

    const boundedData = data.map(boundResearchEvents);
    const fullPageMessageCount = HISTORY_RECORD_PAGE_SIZE * 2;
    hasOlderMessages.value = boundedData.length >= fullPageMessageCount;
    if (older) {
      const existingKeys = new Set(list.value.map(messageIdentity));
      const olderMessages = boundedData.filter(message => !existingKeys.has(messageIdentity(message)));
      if (olderMessages.length) list.value = [...olderMessages, ...list.value];
      return olderMessages.length > 0;
    }
    return applyLoadedMessages(boundedData, cid);
  }

  function messageIdentity(message: Api.Chat.Message) {
    return [message.conversationRecordId || '', message.generationId || '', message.timestamp || '', message.role].join(
      ':'
    );
  }

  async function loadOlderMessages() {
    if (!hasOlderMessages.value || messagesLoadingOlder.value) return false;
    return loadMessages(conversationId.value, { older: true });
  }

  async function loadConversationDetails(targetConversationId: string, options: { force?: boolean } = {}) {
    const { force = false } = options;
    if (!force && loadedConversationDetailsId === targetConversationId) return;

    const existing = conversationDetailsInFlight.get(targetConversationId);
    if (existing) {
      await existing;
      return;
    }

    const pending = Promise.all([loadConversationScope(targetConversationId), loadMessages(targetConversationId)]).then(
      () => {
        if (conversationId.value === targetConversationId) loadedConversationDetailsId = targetConversationId;
      }
    );
    conversationDetailsInFlight.set(targetConversationId, pending);
    try {
      await pending;
    } finally {
      conversationDetailsInFlight.delete(targetConversationId);
    }
  }

  async function archiveSession(targetConversationId: string) {
    const archivingCurrentSession = targetConversationId === conversationId.value;
    const { error } = await request({
      url: `users/conversations/${targetConversationId}/archive`,
      method: 'PUT'
    });
    if (!error) {
      if (archivingCurrentSession) {
        conversationSelectionVersion += 1;
        list.value = [];
        conversationId.value = '';
        currentScope.value = null;
        referenceFocus.value = null;
        hasOlderMessages.value = false;
        loadedConversationDetailsId = '';
      }
      await loadSessions();
    }
  }

  async function unarchiveSession(targetConversationId: string) {
    const { error } = await request({
      url: `users/conversations/${targetConversationId}/unarchive`,
      method: 'PUT'
    });
    if (!error) {
      await loadSessions();
    }
  }

  async function deleteSession(targetConversationId: string) {
    const deletingCurrentSession = targetConversationId === conversationId.value;
    const { error } = await request({
      url: `users/conversations/${targetConversationId}`,
      method: 'DELETE'
    });
    if (error) {
      return false;
    }

    sessions.value = sessions.value.filter(session => session.conversationId !== targetConversationId);
    if (deletingCurrentSession) {
      conversationSelectionVersion += 1;
      conversationId.value = '';
      list.value = [];
      currentScope.value = null;
      referenceFocus.value = null;
      hasOlderMessages.value = false;
      loadedConversationDetailsId = '';
    }
    await loadSessions();
    return true;
  }

  const filteredSessions = computed(() => {
    return sessions.value.filter(s => s.status === (activeTab.value === 'archived' ? 'ARCHIVED' : 'ACTIVE'));
  });

  // ---- WebSocket ----

  const socketUrl = computed(() => {
    const token = store.token?.trim();
    if (!token) {
      return undefined;
    }
    return `/proxy-ws/chat/${encodeURIComponent(token)}?clientId=${encodeURIComponent(chatClientId)}`;
  });

  const {
    status: wsStatus,
    data: wsData,
    send: rawWsSend,
    open: rawWsOpen,
    close: rawWsClose
  } = useWebSocket(socketUrl, {
    immediate: false,
    autoConnect: false,
    heartbeat: {
      message: WS_HEARTBEAT_PING,
      responseMessage: WS_HEARTBEAT_PONG,
      interval: 20_000,
      pongTimeout: 10_000
    },
    autoReconnect: {
      retries: () => allowReconnect.value,
      delay: 1500,
      onFailed: () => {
        if (allowReconnect.value && socketUrl.value) {
          window.$message?.warning('WebSocket 重连失败，请检查网络或刷新页面后重试');
        }
      }
    },
    onConnected: () => {
      allowReconnect.value = true;
      authFailureNotified.value = false;
      intentionalDisconnect.value = false;
    },
    onDisconnected: (_, event) => {
      if (intentionalDisconnect.value) {
        intentionalDisconnect.value = false;
        allowReconnect.value = Boolean(socketUrl.value);
        return;
      }
      const closedBeforeHandshake = !handshakeConfirmed.value;
      const isAuthOrProtocolFailure = NON_RETRYABLE_CLOSE_CODES.has(event.code) || closedBeforeHandshake;
      allowReconnect.value = !isAuthOrProtocolFailure;
      if (isAuthOrProtocolFailure && !authFailureNotified.value) {
        authFailureNotified.value = true;
        window.$message?.error('聊天连接鉴权失败，请重新登录后再试');
      }
    }
  });

  function syncRateLimitCountdown() {
    if (!rateLimitUntil.value) {
      rateLimitRemainingSeconds.value = 0;
      return;
    }
    const remainingMs = rateLimitUntil.value - Date.now();
    rateLimitRemainingSeconds.value = Math.max(0, Math.ceil(remainingMs / 1000));
    if (remainingMs <= 0) {
      clearRateLimitCountdown();
    }
  }

  function clearRateLimitTimer() {
    if (rateLimitTimer !== null) {
      window.clearInterval(rateLimitTimer);
      rateLimitTimer = null;
    }
  }

  function clearRateLimitCountdown() {
    clearRateLimitTimer();
    rateLimitUntil.value = null;
    rateLimitRemainingSeconds.value = 0;
  }

  function startRateLimitCountdown(retryAfterSeconds: number) {
    const normalizedSeconds = Math.max(0, Math.ceil(retryAfterSeconds));
    if (normalizedSeconds <= 0) {
      clearRateLimitCountdown();
      return;
    }
    rateLimitUntil.value = Date.now() + normalizedSeconds * 1000;
    syncRateLimitCountdown();
    clearRateLimitTimer();
    rateLimitTimer = setInterval(syncRateLimitCountdown, 1000);
  }

  function resetConnectionState() {
    handshakeConfirmed.value = false;
    sessionId.value = '';
    authFailureNotified.value = false;
  }

  function wsOpen() {
    if (!socketUrl.value) {
      return;
    }
    resetConnectionState();
    allowReconnect.value = true;
    intentionalDisconnect.value = wsStatus.value === 'OPEN' || wsStatus.value === 'CONNECTING';
    rawWsOpen();
  }

  function wsClose(code?: number, reason?: string) {
    intentionalDisconnect.value = true;
    allowReconnect.value = false;
    rawWsClose(code, reason);
  }

  function handleAuthReset() {
    clearRateLimitCountdown();
    resetConnectionState();
    conversationId.value = '';
    currentScope.value = null;
    referenceFocus.value = null;
    input.value = { message: '' };
    list.value = [];
    sessions.value = [];
    hasOlderMessages.value = false;
    loadedConversationDetailsId = '';
    conversationDetailsInFlight.clear();
    wsClose(1000, 'auth-reset');
  }

  watch(
    socketUrl,
    url => {
      resetConnectionState();
      if (!url) {
        wsClose();
        clearRateLimitCountdown();
        return;
      }
      wsOpen();
    },
    { immediate: true }
  );

  watch(wsData, val => {
    if (!val) return;
    try {
      const data = JSON.parse(val);
      if (data.type === 'connection' && data.sessionId) {
        handshakeConfirmed.value = true;
        sessionId.value = data.sessionId;
        syncGenerationAfterReconnect().catch(() => {});
      }
    } catch {
      // Ignore JSON parse errors for non-JSON messages
    }
  });

  const isRateLimited = computed(() => rateLimitRemainingSeconds.value > 0);
  const connectionStatus = computed(() => {
    if (wsStatus.value === 'OPEN') {
      return 'OPEN';
    }
    if (wsStatus.value === 'CONNECTING' && handshakeConfirmed.value) {
      return 'RECONNECTING';
    }
    return wsStatus.value;
  });

  return {
    input,
    conversationId,
    list,
    sessions,
    sessionsLoading,
    hasOlderMessages,
    messagesLoadingOlder,
    activeTab,
    currentScope,
    referenceFocus,
    filteredSessions,
    connectionStatus,
    isRateLimited,
    rateLimitRemainingSeconds,
    wsStatus,
    wsData,
    wsSend: rawWsSend,
    wsOpen,
    wsClose,
    sessionId,
    clearRateLimitCountdown,
    startRateLimitCountdown,
    handleAuthReset,
    fetchGenerationSnapshot,
    upsertGenerationSnapshot,
    applyGenerationStart,
    applyLoadedMessages,
    syncGenerationAfterReconnect,
    loadSessions,
    loadSessionIndex,
    loadConversationDetails,
    loadOlderMessages,
    loadConversationScope,
    updateConversationScope,
    createSessionFromScope,
    setReferenceFocus,
    createNewSession,
    switchSession,
    loadMessages,
    archiveSession,
    unarchiveSession,
    deleteSession
  };
});
