export interface ChatMessageListItem {
  role: 'user' | 'assistant';
  content: string;
  status?: 'pending' | 'loading' | 'finished' | 'error';
  conversationId?: string;
  generationId?: string;
  timestamp?: string;
  route?: string;
}

export interface GenerationStartPayload {
  conversationId?: string;
  generationId?: string;
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
  loadedMessages,
  targetConversationId
}: {
  currentMessages: readonly ChatMessageListItem[];
  loadedMessages: readonly ChatMessageListItem[];
  targetConversationId?: string;
}) {
  if (loadedMessages.length > 0) {
    return true;
  }

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
  const assistantIndex = findStartedAssistantIndex(messages, payload.generationId);
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
      route: index === assistantIndex ? payload.route || message.route : message.route
    };
  }) as T[];

  return {
    conversationId,
    messages: nextMessages,
    assistant: assistantIndex >= 0 ? nextMessages[assistantIndex] : null
  };
}

function findStartedAssistantIndex(messages: readonly ChatMessageListItem[], generationId?: string) {
  if (generationId) {
    for (let i = messages.length - 1; i >= 0; i -= 1) {
      const message = messages[i];
      if (message?.role === 'assistant' && message.generationId === generationId) {
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
