import assert from 'node:assert/strict';
import {
  applyGenerationStartToMessages,
  mergeLoadedConversationMessages
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

const retried = applyGenerationStartToMessages({
  currentConversationId: 'conversation-1',
  messages: [
    { role: 'user', content: 'What problem does LoRA solve?', conversationId: 'conversation-1' },
    {
      role: 'assistant',
      content: 'Old answer',
      status: 'finished',
      conversationId: 'conversation-1',
      generationId: 'generation-parent',
      answerSlotId: 12,
      answerRevision: 1
    },
    { role: 'user', content: 'Follow-up based on the old answer', conversationId: 'conversation-1' },
    {
      role: 'assistant',
      content: 'Follow-up answer',
      status: 'finished',
      conversationId: 'conversation-1',
      generationId: 'generation-descendant',
      answerSlotId: 13,
      answerRevision: 1
    }
  ],
  payload: {
    conversationId: 'conversation-1',
    generationId: 'generation-retry',
    retryOfGenerationId: 'generation-parent',
    retryOfConversationRecordId: 12,
    answerSlotId: 12,
    answerRevision: 2,
    replaceMessage: true
  }
});

assert.equal(retried.messages.length, 2);
assert.equal(retried.messages[1].content, '');
assert.equal(retried.messages[1].status, 'loading');
assert.equal(retried.messages[1].generationId, 'generation-retry');
assert.equal(retried.messages[1].retryOfGenerationId, 'generation-parent');
assert.equal(retried.messages[1].answerSlotId, 12);
assert.equal(retried.messages[1].answerRevision, 2);

assert.deepEqual(
  mergeLoadedConversationMessages({
    currentMessages: [
      { role: 'user', content: 'Earlier question', conversationId: 'conversation-1' },
      { role: 'assistant', content: 'Earlier answer', conversationId: 'conversation-1' },
      ...started.messages
    ],
    loadedMessages: [
      { role: 'user', content: 'Earlier question', conversationId: 'conversation-1' },
      { role: 'assistant', content: 'Earlier answer', conversationId: 'conversation-1' }
    ],
    targetConversationId: 'conversation-1'
  }),
  [
    { role: 'user', content: 'Earlier question', conversationId: 'conversation-1' },
    { role: 'assistant', content: 'Earlier answer', conversationId: 'conversation-1' },
    ...started.messages
  ],
  'loaded history and an in-flight answer must both remain visible'
);

assert.deepEqual(
  mergeLoadedConversationMessages({
    currentMessages: [],
    loadedMessages: [],
    targetConversationId: 'conversation-1'
  }),
  [],
  'empty history is valid when there is no in-flight answer'
);
