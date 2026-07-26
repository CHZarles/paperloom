export interface ChatMessageListItem {
  role: 'user' | 'assistant';
  content: string;
  status?: 'pending' | 'loading' | 'finished' | 'error';
  conversationId?: string;
  generationId?: string;
  retryOfGenerationId?: string;
  answerSlotId?: number;
  answerRevision?: number;
  retryOfConversationRecordId?: number;
  replaceMessage?: boolean;
  timestamp?: string;
  route?: string;
}

export interface GenerationStartPayload {
  conversationId?: string;
  generationId?: string;
  retryOfGenerationId?: string;
  retryOfConversationRecordId?: number;
  answerSlotId?: number;
  answerRevision?: number;
  replaceMessage?: boolean;
  timestamp?: string;
  route?: string;
}

export function hasInFlightAssistant(messages: readonly ChatMessageListItem[], targetConversationId?: string) {
  return messages.some(message => {
    if (message.role !== 'assistant') return false;
    if (!['pending', 'loading'].includes(message.status || '')) return false;
    if (!targetConversationId) return true;
    return !message.conversationId || message.conversationId === targetConversationId;
  });
}

export function shouldApplyLoadedConversationMessages({
  currentMessages,
  targetConversationId
}: {
  currentMessages: readonly ChatMessageListItem[];
  loadedMessages: readonly ChatMessageListItem[];
  targetConversationId?: string;
}) {
  return !hasInFlightAssistant(currentMessages, targetConversationId);
}

export function applyGenerationStartToMessages<T extends ChatMessageListItem>({
  currentConversationId,
  messages,
  payload
}: {
  currentConversationId: string;
  messages: readonly T[];
  payload: GenerationStartPayload;
}) {
  const conversationId = payload.conversationId || currentConversationId;
  const assistantIndex = findStartedAssistantIndex(messages, payload);
  const userIndex = findNearestUserIndex(messages, assistantIndex);

  const nextMessages = messages.map((message, index) => {
    if (index !== assistantIndex && index !== userIndex) {
      return message;
    }

    return {
      ...message,
      conversationId: conversationId || message.conversationId,
      generationId: index === assistantIndex ? payload.generationId || message.generationId : message.generationId,
      timestamp: index === assistantIndex ? payload.timestamp || message.timestamp : message.timestamp,
      route: index === assistantIndex ? payload.route || message.route : message.route,
      retryOfGenerationId:
        index === assistantIndex ? payload.retryOfGenerationId || message.retryOfGenerationId : message.retryOfGenerationId,
      retryOfConversationRecordId:
        index === assistantIndex
          ? payload.retryOfConversationRecordId || message.retryOfConversationRecordId
          : message.retryOfConversationRecordId,
      answerSlotId: index === assistantIndex ? payload.answerSlotId || message.answerSlotId : message.answerSlotId,
      answerRevision:
        index === assistantIndex ? payload.answerRevision || message.answerRevision : message.answerRevision,
      replaceMessage: index === assistantIndex ? payload.replaceMessage || message.replaceMessage : message.replaceMessage,
      content: index === assistantIndex && payload.replaceMessage ? '' : message.content,
      status: index === assistantIndex && payload.replaceMessage ? 'loading' : message.status
    };
  }) as T[];

  return {
    conversationId,
    messages: nextMessages,
    assistant: assistantIndex >= 0 ? nextMessages[assistantIndex] : null
  };
}

function findStartedAssistantIndex(messages: readonly ChatMessageListItem[], payload: GenerationStartPayload) {
  if (payload.generationId) {
    for (let i = messages.length - 1; i >= 0; i -= 1) {
      const message = messages[i];
      if (message?.role === 'assistant' && message.generationId === payload.generationId) {
        return i;
      }
    }
  }

  if (payload.answerSlotId) {
    for (let i = messages.length - 1; i >= 0; i -= 1) {
      const message = messages[i];
      if (message?.role === 'assistant' && message.answerSlotId === payload.answerSlotId) {
        return i;
      }
    }
  }

  if (payload.retryOfGenerationId) {
    for (let i = messages.length - 1; i >= 0; i -= 1) {
      const message = messages[i];
      if (message?.role === 'assistant' && message.generationId === payload.retryOfGenerationId) {
        return i;
      }
    }
  }

  for (let i = messages.length - 1; i >= 0; i -= 1) {
    const message = messages[i];
    if (message?.role === 'assistant' && ['pending', 'loading'].includes(message.status || '')) {
      return i;
    }
  }

  return -1;
}

function findNearestUserIndex(messages: readonly ChatMessageListItem[], assistantIndex: number) {
  if (assistantIndex <= 0) {
    return -1;
  }

  for (let i = assistantIndex - 1; i >= 0; i -= 1) {
    if (messages[i]?.role === 'user') {
      return i;
    }
  }

  return -1;
}
