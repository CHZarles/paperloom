import process from 'node:process';
import { expect, test } from '@playwright/test';

const storagePrefix = process.env.PAPERLOOM_E2E_STORAGE_PREFIX || 'CiteWeave_';

async function fulfillApi<T>(route: import('@playwright/test').Route, data: T) {
  await route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ code: 200, data })
  });
}

test('a stale initial session load cannot replace a new scoped query', async ({ page }) => {
  await page.addInitScript(
    ({ prefix }) => {
      window.localStorage.setItem(`${prefix}token`, JSON.stringify('mock-token'));
      window.localStorage.setItem(`${prefix}refreshToken`, JSON.stringify('mock-refresh-token'));
    },
    { prefix: storagePrefix }
  );

  const oldSession = {
    id: 1,
    conversationId: 'old-session',
    title: 'Old Query',
    status: 'ACTIVE',
    createdAt: '2026-07-13T07:00:00',
    updatedAt: '2026-07-13T07:00:00'
  };
  const newSession = {
    ...oldSession,
    id: 2,
    conversationId: 'new-session',
    title: 'New Query',
    createdAt: '2026-07-13T08:00:00',
    updatedAt: '2026-07-13T08:00:00'
  };
  const autoScope = {
    scopeMode: 'AUTO_LIBRARY',
    scopeLocked: false,
    scopeStatus: 'READY',
    sourceLabel: 'All readable papers',
    sourcePaperCount: 30,
    paperIds: [],
    sourceRecipe: null
  };
  const paperSetScope = {
    scopeMode: 'SOURCE_SET_SNAPSHOT',
    scopeLocked: false,
    scopeStatus: 'READY',
    sourceLabel: 'Agent Evaluation',
    sourcePaperCount: 12,
    paperIds: ['paper-1'],
    sourceRecipe: { type: 'collection', collectionIds: [7] }
  };

  let releaseInitialSessions: (() => void) | null = null;
  const initialSessionsGate = new Promise<void>(resolve => {
    releaseInitialSessions = resolve;
  });
  let initialSessionsRequested = false;
  let currentSessionRequests = 0;

  await page.route('**/users/me', route =>
    fulfillApi(route, { id: 1, username: 'admin', role: 'ADMIN', orgTags: ['default'], primaryOrg: 'default' })
  );
  await page.route('**/users/org-tags', route =>
    fulfillApi(route, {
      orgTags: ['default'],
      primaryOrg: 'default',
      orgTagDetails: [{ tagId: 'default', name: 'Default', description: 'Default workspace' }]
    })
  );
  await page.route('**/users/usage', route =>
    fulfillApi(route, {
      day: '2026-07-13',
      chatRequestCount: 0,
      llm: { enabled: true, usedTokens: 0, limitTokens: 1000, remainingTokens: 1000, requestCount: 0 },
      embedding: { enabled: true, usedTokens: 0, limitTokens: 1000, remainingTokens: 1000, requestCount: 0 }
    })
  );
  await page.route('**/chat/active-generation**', route => fulfillApi(route, null));
  await page.route(/\/users\/conversations$/, async route => {
    if (route.request().method() === 'POST') return fulfillApi(route, newSession);
    initialSessionsRequested = true;
    await initialSessionsGate;
    return fulfillApi(route, [oldSession]);
  });
  await page.route(/\/users\/conversations\/current$/, route => {
    currentSessionRequests += 1;
    return fulfillApi(route, oldSession);
  });
  await page.route(/\/users\/conversations\/new-session\/scope$/, route =>
    fulfillApi(route, route.request().method() === 'PUT' ? paperSetScope : autoScope)
  );
  await page.route(/\/users\/conversations\/old-session\/scope$/, route =>
    fulfillApi(route, { ...autoScope, scopeLocked: true })
  );
  await page.route(/\/users\/conversation(?:\?|$)/, route =>
    fulfillApi(route, [
      { role: 'user', conversationId: 'old-session', content: 'Old question' },
      { role: 'assistant', conversationId: 'old-session', content: 'Old answer' }
    ])
  );
  await page.route('**/paper-collections', route =>
    fulfillApi(route, [
      {
        id: 7,
        name: 'Agent Evaluation',
        description: 'Agent evaluation papers',
        visibility: 'PRIVATE',
        paperCount: 12,
        searchablePaperCount: 12
      }
    ])
  );

  await page.goto('/#/chat', { waitUntil: 'domcontentloaded' });
  await expect.poll(() => initialSessionsRequested).toBe(true);
  await page.getByRole('button', { name: 'New Query' }).click();
  await expect(page.locator('.chat-input-wrap--hero')).toBeVisible();
  releaseInitialSessions?.();

  await page.locator('.session-scope-picker .n-base-selection').click();
  await page.getByText('Agent Evaluation · 12 searchable', { exact: true }).click();

  await expect(page.locator('.chat-input-wrap--hero')).toBeVisible();
  await expect(page.locator('.session-scope-picker')).toContainText('Agent Evaluation');
  expect(currentSessionRequests).toBe(0);
});
