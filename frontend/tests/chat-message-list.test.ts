import assert from 'node:assert/strict';
import {
  applyGenerationStartToMessages,
  shouldApplyLoadedConversationMessages
} from '../src/store/modules/chat/message-list';

const started = applyGenerationStartToMessages({
  currentConversationId: '',
  messages: [
    { role: 'user', content: 'What problem does LoRA solve?' },
    { role: 'assistant', content: '', status: 'pending' }
  ],
  payload: {
    conversationId: 'conversation-1',
    generationId: 'generation-1',
    route: 'AUTO_SOURCE_QA',
    timestamp: '2026-06-29T11:30:00Z'
  }
});

assert.equal(started.conversationId, 'conversation-1');
assert.equal(started.messages[0].conversationId, 'conversation-1');
assert.equal(started.messages[1].conversationId, 'conversation-1');
assert.equal(started.messages[1].generationId, 'generation-1');
assert.equal(started.messages[1].route, 'AUTO_SOURCE_QA');
assert.equal(started.messages[1].timestamp, '2026-06-29T11:30:00Z');

assert.equal(
  shouldApplyLoadedConversationMessages({
    currentMessages: started.messages,
    loadedMessages: [],
    targetConversationId: 'conversation-1'
  }),
  false,
  'empty history must not replace an in-flight first answer'
);

assert.equal(
  shouldApplyLoadedConversationMessages({
    currentMessages: [],
    loadedMessages: [],
    targetConversationId: 'conversation-1'
  }),
  true,
  'empty history is valid when there is no in-flight answer'
);
