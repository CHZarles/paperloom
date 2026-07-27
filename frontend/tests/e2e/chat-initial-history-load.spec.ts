import process from 'node:process';
import { expect, test } from '@playwright/test';

const storagePrefix = process.env.PAPERLOOM_E2E_STORAGE_PREFIX || 'CiteWeave_';

async function installMockLoginState(page: import('@playwright/test').Page) {
  await page.addInitScript(
    ({ prefix }) => {
      window.localStorage.setItem(`${prefix}token`, JSON.stringify('mock-token'));
      window.localStorage.setItem(`${prefix}refreshToken`, JSON.stringify('mock-refresh-token'));
    },
    { prefix: storagePrefix }
  );

  await page.route('**/users/me', route =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        data: {
          id: 1,
          username: 'admin',
          role: 'ADMIN'
        }
      })
    })
  );
}

async function fulfillApi<T>(route: import('@playwright/test').Route, data: T) {
  await route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      code: 200,
      data
    })
  });
}

test('chat page loads only the selected conversation history on entry', async ({ page }) => {
  await installMockLoginState(page);

  const currentSession = {
    id: 102,
    conversationId: 'current-session',
    title: 'Current session',
    status: 'ACTIVE',
    current: true,
    createdAt: '2026-07-01T08:00:00',
    updatedAt: '2026-07-01T09:00:00',
    scopeMode: 'AUTO_LIBRARY',
    scopeLocked: true,
    scopeStatus: 'READY',
    sourceLabel: 'All readable papers',
    sourcePaperCount: 30
  };
  const otherSession = {
    ...currentSession,
    id: 101,
    conversationId: 'other-session',
    title: 'Other session',
    current: false
  };
  const unscopedHistory = [
    {
      role: 'user',
      conversationId: 'other-session',
      content: 'Other session question',
      timestamp: '2026-07-01T08:00:00'
    },
    {
      role: 'assistant',
      conversationId: 'other-session',
      content: 'Other session answer',
      timestamp: '2026-07-01T08:00:00'
    },
    {
      role: 'user',
      conversationId: 'current-session',
      content: 'Current session question',
      timestamp: '2026-07-01T09:00:00'
    }
  ];
  const currentHistory = [
    {
      role: 'user',
      conversationId: 'current-session',
      content: 'Current session question',
      timestamp: '2026-07-01T09:00:00'
    },
    {
      role: 'assistant',
      conversationId: 'current-session',
      content: 'Current session answer',
      timestamp: '2026-07-01T09:00:00'
    }
  ];

  let unscopedConversationRequests = 0;
  let scopedConversationRequests = 0;
  let currentSessionRequests = 0;
  let paperCollectionsRequests = 0;

  await page.route('**/users/usage', route =>
    fulfillApi(route, {
      day: '2026-07-01',
      chatRequestCount: 0,
      llm: { enabled: true, usedTokens: 0, limitTokens: 1000, remainingTokens: 1000, requestCount: 0 },
      embedding: { enabled: true, usedTokens: 0, limitTokens: 1000, remainingTokens: 1000, requestCount: 0 }
    })
  );
  await page.route('**/chat/active-generation**', route => fulfillApi(route, null));
  await page.route('**/paper-collections', route => {
    paperCollectionsRequests += 1;
    return fulfillApi(route, []);
  });
  await page.route(/\/users\/conversations$/, route => fulfillApi(route, [currentSession, otherSession]));
  await page.route(/\/users\/conversations\/current$/, route => {
    currentSessionRequests += 1;
    return fulfillApi(route, currentSession);
  });
  await page.route(/\/users\/conversations\/current-session\/switch$/, route => fulfillApi(route, {}));
  await page.route(/\/users\/conversations\/current-session\/scope$/, route =>
    fulfillApi(route, {
      scopeMode: 'AUTO_LIBRARY',
      scopeLocked: true,
      scopeStatus: 'READY',
      sourceLabel: 'All readable papers',
      sourcePaperCount: 30,
      paperIds: [],
      sourceRecipe: null
    })
  );
  await page.route(/\/users\/conversation(?:\?|$)/, route => {
    const url = new URL(route.request().url());
    if (url.searchParams.has('conversationId')) {
      scopedConversationRequests += 1;
      return fulfillApi(route, currentHistory);
    }
    unscopedConversationRequests += 1;
    return fulfillApi(route, unscopedHistory);
  });

  await page.goto('/#/chat', { waitUntil: 'domcontentloaded' });

  await expect(page.locator('.message-block')).toHaveCount(2);
  await expect(page.locator('.chat-conversation')).toContainText('Current session question');
  await expect(page.locator('.chat-conversation')).toContainText('Current session answer');
  await expect(page.locator('.chat-conversation')).not.toContainText('Other session question');
  expect(unscopedConversationRequests).toBe(0);
  expect(scopedConversationRequests).toBe(1);
  expect(currentSessionRequests).toBe(0);
  expect(paperCollectionsRequests).toBe(0);
});

test('switching back to a recently loaded session restores cached messages without refetching details', async ({ page }) => {
  await installMockLoginState(page);

  const sessionA = {
    id: 201,
    conversationId: 'session-a',
    title: 'Session A',
    status: 'ACTIVE',
    current: true,
    createdAt: '2026-07-01T08:00:00',
    updatedAt: '2026-07-01T10:00:00',
    scopeMode: 'AUTO_LIBRARY',
    scopeLocked: true,
    scopeStatus: 'READY',
    sourceLabel: 'All readable papers',
    sourcePaperCount: 30
  };
  const sessionB = {
    ...sessionA,
    id: 202,
    conversationId: 'session-b',
    title: 'Session B',
    current: false,
    updatedAt: '2026-07-01T09:00:00'
  };
  const histories: Record<string, Api.Chat.Message[]> = {
    'session-a': [
      {
        role: 'user',
        conversationId: 'session-a',
        content: 'Cached session A question',
        timestamp: '2026-07-01T10:00:00'
      },
      {
        role: 'assistant',
        conversationId: 'session-a',
        content: 'Cached session A answer',
        timestamp: '2026-07-01T10:00:00'
      }
    ],
    'session-b': [
      {
        role: 'user',
        conversationId: 'session-b',
        content: 'Session B question',
        timestamp: '2026-07-01T09:00:00'
      },
      {
        role: 'assistant',
        conversationId: 'session-b',
        content: 'Session B answer',
        timestamp: '2026-07-01T09:00:00'
      }
    ]
  };
  const scopes: Record<string, Api.Chat.ConversationScope> = {
    'session-a': {
      scopeMode: 'AUTO_LIBRARY',
      scopeLocked: true,
      scopeStatus: 'READY',
      sourceLabel: 'All readable papers',
      sourcePaperCount: 30,
      paperIds: [],
      sourceRecipe: null
    },
    'session-b': {
      scopeMode: 'AUTO_LIBRARY',
      scopeLocked: true,
      scopeStatus: 'READY',
      sourceLabel: 'All readable papers',
      sourcePaperCount: 30,
      paperIds: [],
      sourceRecipe: null
    }
  };

  const historyRequests = new Map<string, number>();
  const scopeRequests = new Map<string, number>();
  let paperCollectionsRequests = 0;

  await page.route('**/users/usage', route =>
    fulfillApi(route, {
      day: '2026-07-01',
      chatRequestCount: 0,
      llm: { enabled: true, usedTokens: 0, limitTokens: 1000, remainingTokens: 1000, requestCount: 0 },
      embedding: { enabled: true, usedTokens: 0, limitTokens: 1000, remainingTokens: 1000, requestCount: 0 }
    })
  );
  await page.route('**/chat/active-generation**', route => fulfillApi(route, null));
  await page.route('**/paper-collections', route => {
    paperCollectionsRequests += 1;
    return fulfillApi(route, []);
  });
  await page.route(/\/users\/conversations$/, route => fulfillApi(route, [sessionA, sessionB]));
  await page.route(/\/users\/conversations\/([^/]+)\/switch$/, route => fulfillApi(route, {}));
  await page.route(/\/users\/conversations\/([^/]+)\/scope$/, route => {
    const conversationId = route.request().url().match(/\/users\/conversations\/([^/]+)\/scope$/)?.[1] || '';
    scopeRequests.set(conversationId, (scopeRequests.get(conversationId) || 0) + 1);
    return fulfillApi(route, scopes[conversationId]);
  });
  await page.route(/\/users\/conversation(?:\?|$)/, route => {
    const conversationId = new URL(route.request().url()).searchParams.get('conversationId') || '';
    historyRequests.set(conversationId, (historyRequests.get(conversationId) || 0) + 1);
    return fulfillApi(route, histories[conversationId] || []);
  });

  await page.goto('/#/chat', { waitUntil: 'domcontentloaded' });
  await expect(page.locator('.chat-conversation')).toContainText('Cached session A answer');

  await page.locator('[data-conversation-id="session-b"]').click();
  await expect(page.locator('.chat-conversation')).toContainText('Session B answer');

  await page.locator('[data-conversation-id="session-a"]').click();
  await expect(page.locator('.chat-conversation')).toContainText('Cached session A answer');
  await page.waitForTimeout(250);

  expect(historyRequests.get('session-a')).toBe(1);
  expect(scopeRequests.get('session-a')).toBe(1);
  expect(historyRequests.get('session-b')).toBe(1);
  expect(scopeRequests.get('session-b')).toBe(1);
  expect(paperCollectionsRequests).toBe(0);
});
