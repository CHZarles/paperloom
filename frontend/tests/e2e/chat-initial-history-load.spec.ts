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
          role: 'ADMIN',
          orgTags: ['default'],
          primaryOrg: 'default'
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
    createdAt: '2026-07-01T08:00:00',
    updatedAt: '2026-07-01T09:00:00',
    scopeMode: 'AUTO_LIBRARY',
    scopeLocked: true,
    scopeStatus: 'READY',
    sourceLabel: 'All searchable papers',
    sourcePaperCount: null
  };
  const otherSession = {
    ...currentSession,
    id: 101,
    conversationId: 'other-session',
    title: 'Other session'
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

  await page.route('**/users/org-tags', route =>
    fulfillApi(route, {
      orgTags: ['default'],
      primaryOrg: 'default',
      orgTagDetails: [{ tagId: 'default', name: 'Default', description: 'Default workspace' }]
    })
  );
  await page.route('**/users/usage', route =>
    fulfillApi(route, {
      day: '2026-07-01',
      chatRequestCount: 0,
      llm: { enabled: true, usedTokens: 0, limitTokens: 1000, remainingTokens: 1000, requestCount: 0 },
      embedding: { enabled: true, usedTokens: 0, limitTokens: 1000, remainingTokens: 1000, requestCount: 0 }
    })
  );
  await page.route('**/chat/active-generation', route => fulfillApi(route, null));
  await page.route(/\/users\/conversations$/, route => fulfillApi(route, [currentSession, otherSession]));
  await page.route(/\/users\/conversations\/current$/, route => fulfillApi(route, currentSession));
  await page.route(/\/users\/conversations\/current-session\/switch$/, route => fulfillApi(route, {}));
  await page.route(/\/users\/conversations\/current-session\/scope$/, route =>
    fulfillApi(route, {
      scopeMode: 'AUTO_LIBRARY',
      scopeLocked: true,
      scopeStatus: 'READY',
      sourceLabel: 'All searchable papers',
      sourcePaperCount: null,
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

  await page.goto('/#/chat');

  await expect(page.locator('.message-block')).toHaveCount(2);
  await expect(page.locator('.chat-conversation')).toContainText('Current session question');
  await expect(page.locator('.chat-conversation')).toContainText('Current session answer');
  await expect(page.locator('.chat-conversation')).not.toContainText('Other session question');
  expect(unscopedConversationRequests).toBe(0);
  expect(scopedConversationRequests).toBe(1);
});
