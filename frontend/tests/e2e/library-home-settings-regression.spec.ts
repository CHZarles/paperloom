/* eslint-disable no-await-in-loop */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import process from 'node:process';
import type { Page, Route } from '@playwright/test';
import { expect, test } from '@playwright/test';

type ApiEnvelope<T> = {
  code: number;
  data: T;
};

type LoginToken = {
  token: string;
  refreshToken: string;
};

const backendBaseURL = process.env.PAPERLOOM_E2E_API_BASE_URL || 'http://localhost:8081/api/v1';
const storagePrefix = process.env.PAPERLOOM_E2E_STORAGE_PREFIX || 'CiteWeave_';

function readRepoEnv() {
  const values: Record<string, string> = {};
  const text = readFileSync(resolve(process.cwd(), '..', '.env'), 'utf8');

  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (line && !line.startsWith('#')) {
      const separator = line.indexOf('=');

      if (separator >= 0) {
        const key = line.slice(0, separator).trim();
        let value = line.slice(separator + 1).trim();

        if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
          value = value.slice(1, -1);
        }
        values[key] = value;
      }
    }
  }

  return values;
}

async function requestJson<T>(path: string, options: { method?: string; body?: unknown } = {}) {
  const response = await fetch(`${backendBaseURL}${path}`, {
    method: options.method || 'GET',
    headers: options.body ? { 'Content-Type': 'application/json' } : {},
    body: options.body ? JSON.stringify(options.body) : undefined
  });
  const payload = (await response.json()) as ApiEnvelope<T>;

  if (!response.ok || payload.code !== 200) {
    throw new Error(`${options.method || 'GET'} ${path} failed: http=${response.status} code=${payload.code}`);
  }

  return payload.data;
}

async function login() {
  const env = readRepoEnv();
  const username = process.env.PAPERLOOM_E2E_USERNAME || env.ADMIN_BOOTSTRAP_USERNAME;
  const password = process.env.PAPERLOOM_E2E_PASSWORD || env.ADMIN_BOOTSTRAP_PASSWORD;

  if (!username || !password) {
    throw new Error('Missing PAPERLOOM_E2E_USERNAME/PAPERLOOM_E2E_PASSWORD or admin bootstrap credentials');
  }

  return requestJson<LoginToken>('/users/login', {
    method: 'POST',
    body: { username, password }
  });
}

async function installLoginState(page: Page, loginToken: LoginToken) {
  await page.addInitScript(
    ({ prefix, token, refreshToken }) => {
      window.localStorage.setItem(`${prefix}token`, JSON.stringify(token));
      window.localStorage.setItem(`${prefix}refreshToken`, JSON.stringify(refreshToken));
    },
    {
      prefix: storagePrefix,
      token: loginToken.token,
      refreshToken: loginToken.refreshToken
    }
  );
}

async function installMockLoginState(page: Page) {
  await page.addInitScript(
    ({ prefix }) => {
      window.localStorage.setItem(`${prefix}token`, JSON.stringify('mock-token'));
      window.localStorage.setItem(`${prefix}refreshToken`, JSON.stringify('mock-refresh-token'));
    },
    { prefix: storagePrefix }
  );

  await page.route('**/users/me**', route =>
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

async function installRegularUserState(page: Page) {
  await installLoginState(page, await login());

  await page.route('**/users/me**', route =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        data: {
          id: 9001,
          username: 'regular-user',
          role: 'USER',
          orgTags: ['default'],
          primaryOrg: 'default'
        }
      })
    })
  );

  await page.route('**/users/org-tags', route =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        data: {
          orgTags: ['default'],
          primaryOrg: 'default',
          orgTagDetails: [{ tagId: 'default', name: 'Default', description: 'Default workspace' }]
        }
      })
    })
  );

  await page.route('**/users/usage', route =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        code: 200,
        data: {
          day: '2026-07-01',
          chatRequestCount: 0,
          llm: {
            enabled: true,
            usedTokens: 0,
            limitTokens: 1000,
            remainingTokens: 1000,
            requestCount: 0
          },
          embedding: {
            enabled: true,
            usedTokens: 0,
            limitTokens: 1000,
            remainingTokens: 1000,
            requestCount: 0
          }
        }
      })
    })
  );
}

async function fulfillApi<T>(route: Route, data: T) {
  await route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      code: 200,
      data
    })
  });
}

async function installDeletableConversationState(page: Page) {
  const deleteSession = {
    id: 801,
    conversationId: 'delete-me',
    title: 'Delete me',
    status: 'ACTIVE',
    scopeMode: 'AUTO_LIBRARY',
    scopeLocked: false,
    scopeStatus: 'READY',
    sourceLabel: 'All readable papers',
    sourcePaperCount: 30,
    createdAt: '2026-07-01T08:00:00',
    updatedAt: '2026-07-01T09:00:00'
  };
  const keepSession = {
    id: 802,
    conversationId: 'keep-me',
    title: 'Keep me',
    status: 'ACTIVE',
    scopeMode: 'AUTO_LIBRARY',
    scopeLocked: false,
    scopeStatus: 'READY',
    sourceLabel: 'All readable papers',
    sourcePaperCount: 30,
    createdAt: '2026-07-01T08:10:00',
    updatedAt: '2026-07-01T08:30:00'
  };
  let sessions = [deleteSession, keepSession];
  let deleted = false;

  await page.route(/\/users\/conversations$/, async route => {
    if (route.request().method() === 'GET') {
      await fulfillApi(route, sessions);
      return;
    }
    await route.fallback();
  });
  await page.route(/\/users\/conversations\/current$/, route => fulfillApi(route, deleted ? {} : deleteSession));
  await page.route(/\/users\/conversations\/delete-me$/, route => {
    deleted = true;
    sessions = sessions.filter(session => session.conversationId !== 'delete-me');
    return fulfillApi(route, {});
  });
  await page.route(/\/users\/conversations\/delete-me\/switch$/, route => fulfillApi(route, {}));
  await page.route(/\/users\/conversations\/keep-me\/switch$/, route => fulfillApi(route, {}));
  await page.route(/\/users\/conversations\/(delete-me|keep-me)\/scope$/, route =>
    fulfillApi(route, {
      scopeMode: 'AUTO_LIBRARY',
      scopeLocked: false,
      scopeStatus: 'READY',
      sourceLabel: 'All readable papers',
      sourcePaperCount: 30,
      paperIds: []
    })
  );
  await page.route(/\/users\/conversation(\?|$)/, route => fulfillApi(route, []));
}

async function installScopedConversationState(page: Page) {
  const session = {
    id: 701,
    conversationId: 'scoped-a',
    title: 'Scoped LoRA review',
    status: 'ACTIVE',
    scopeMode: 'SOURCE_SET_SNAPSHOT',
    scopeLocked: true,
    scopeStatus: 'READY',
    sourceLabel: 'LoRA Set',
    sourcePaperCount: 2,
    createdAt: '2026-07-01T08:00:00',
    updatedAt: '2026-07-01T09:00:00'
  };

  await page.route(/\/users\/conversations$/, route => fulfillApi(route, [session]));
  await page.route(/\/users\/conversations\/current$/, route => fulfillApi(route, session));
  await page.route(/\/users\/conversations\/scoped-a\/switch$/, route => fulfillApi(route, {}));
  await page.route(/\/users\/conversations\/scoped-a\/scope$/, route =>
    fulfillApi(route, {
      scopeMode: 'SOURCE_SET_SNAPSHOT',
      scopeLocked: true,
      scopeStatus: 'READY',
      sourceLabel: 'LoRA Set',
      sourcePaperCount: 2,
      paperIds: ['paper-a', 'paper-b']
    })
  );
  await page.route(/\/users\/conversation(\?|$)/, route =>
    fulfillApi(route, [
      {
        role: 'user',
        content: 'What is LoRA?',
        conversationId: 'scoped-a',
        timestamp: '2026-07-01T09:00:00'
      },
      {
        role: 'assistant',
        content: 'LoRA is a parameter-efficient adaptation method.',
        status: 'finished',
        conversationId: 'scoped-a',
        timestamp: '2026-07-01T09:00:05'
      }
    ])
  );
}

test('library uses the same sidebar shell as the chat home', async ({ page }) => {
  await installLoginState(page, await login());

  await page.goto('/#/chat', { waitUntil: 'domcontentloaded' });
  await expect(page.locator('.chat-shell')).toBeVisible();
  const chatSidebar = await page.locator('.chat-sidebar').boundingBox();

  await page.getByRole('button', { name: '进入文献库' }).click();
  await expect(page).toHaveURL(/#\/knowledge-base/);
  await expect(page.locator('.paper-library-shell .chat-sidebar')).toBeVisible();
  await expect(page.locator('.paper-library-shell .library-sidebar')).toHaveCount(0);
  await expect(page.locator('.library-button--active')).toContainText('Paper Library');

  const librarySidebar = await page.locator('.paper-library-shell .chat-sidebar').boundingBox();
  expect(Math.round(librarySidebar?.width || 0)).toBe(Math.round(chatSidebar?.width || 0));
});

test('app uses the Folio brand name and mark', async ({ page }) => {
  await installLoginState(page, await login());

  await page.goto('/#/chat', { waitUntil: 'domcontentloaded' });

  await expect(page).toHaveTitle(/Folio$/);
  await expect(page.locator('.chat-shell .brand-title')).toHaveText('Folio');
  await expect(page.locator('.chat-shell .topbar-title')).toHaveText('Folio');
  await expect(page.locator('.chat-shell svg[aria-label="Folio"]').first()).toBeVisible();
  await expect(page.locator('.chat-shell')).not.toContainText('PaperLoom');
  await expect.poll(() => page.locator('head link[rel="icon"]').getAttribute('href')).toBe('/favicon.svg');
});

test('settings entry opens the real clickable settings modal in place', async ({ page }) => {
  await installLoginState(page, await login());

  await page.goto('/#/knowledge-base', { waitUntil: 'domcontentloaded' });
  await expect(page.locator('.paper-library-shell .chat-sidebar')).toBeVisible();
  await page.getByRole('button', { name: '进入管理页面' }).click();

  await expect(page).toHaveURL(/#\/knowledge-base/);
  await expect(page.locator('[data-testid="settings-modal"]')).toBeVisible();

  const navItems = [
    { name: 'General', heading: 'General' },
    { name: 'Usage', heading: 'Usage' },
    { name: 'Organization', heading: 'Organization' },
    { name: 'Token Ledger', heading: 'Token Ledger' }
  ];

  for (const item of navItems) {
    await page.getByRole('button', { name: item.name, exact: true }).click();
    await expect(page.locator('.settings-nav__item--active')).toContainText(item.name);
    await expect(page.getByRole('heading', { name: item.heading })).toBeVisible();
  }
});

test('chat settings entry opens the same modal without leaving the chat', async ({ page }) => {
  await installLoginState(page, await login());

  await page.goto('/#/chat', { waitUntil: 'domcontentloaded' });
  await expect(page.locator('.chat-shell .chat-sidebar')).toBeVisible();
  await page.getByRole('button', { name: '进入管理页面' }).click();

  await expect(page).toHaveURL(/#\/chat/);
  await expect(page.locator('[data-testid="settings-modal"]')).toBeVisible();
  await page.getByRole('button', { name: 'Usage', exact: true }).click();
  await expect(page.locator('.settings-nav__item--active')).toContainText('Usage');
  await expect(page.getByRole('heading', { name: 'Usage' })).toBeVisible();
});

test('search scope is shown above the detailed conversation input only', async ({ page }) => {
  await installLoginState(page, await login());
  await installScopedConversationState(page);

  await page.goto('/#/chat', { waitUntil: 'domcontentloaded' });

  const session = page.getByTestId('conversation-session').filter({ hasText: 'Scoped LoRA review' });
  await expect(session).toBeVisible();
  await expect(session.locator('.session-scope-chip')).toHaveCount(0);

  const hint = page.locator('.chat-input-wrap--dock .search-scope-hint');
  await expect(hint).toBeVisible();
  await expect(hint).toHaveText('Search scope: LoRA Set · 2 papers');

  const hintColor = await hint.evaluate(element => window.getComputedStyle(element).color);
  expect(hintColor).toMatch(/0\.72\)?$/);
});

test('conversation sidebar deletes a session and falls back to the remaining active session', async ({ page }) => {
  await installMockLoginState(page);
  await installDeletableConversationState(page);

  await page.goto('/#/chat', { waitUntil: 'domcontentloaded' });

  const deletedSession = page.getByTestId('conversation-session').filter({ hasText: 'Delete me' });
  await expect(deletedSession).toBeVisible();
  await expect(deletedSession).toHaveClass(/session-item--active/);

  await deletedSession.hover();
  await deletedSession.getByRole('button', { name: '删除 Delete me' }).click();
  await page.getByRole('button', { name: '删除', exact: true }).click();

  await expect(page.getByTestId('conversation-session').filter({ hasText: 'Delete me' })).toHaveCount(0);
  const remainingSession = page.getByTestId('conversation-session').filter({ hasText: 'Keep me' });
  await expect(remainingSession).toBeVisible();
  await expect(remainingSession).toHaveClass(/session-item--active/);
});

test('settings modal uses the wide desktop layout', async ({ page }) => {
  await page.setViewportSize({ width: 1600, height: 900 });
  await installLoginState(page, await login());

  await page.goto('/#/chat', { waitUntil: 'domcontentloaded' });
  await expect(page.locator('.chat-shell .chat-sidebar')).toBeVisible();
  await page.getByRole('button', { name: '进入管理页面' }).click();

  const modal = page.locator('[data-testid="settings-modal"]');
  const nav = page.locator('[data-testid="settings-modal"] .settings-nav');

  await expect.poll(async () => Math.round((await modal.boundingBox())?.width || 0)).toBeGreaterThanOrEqual(1440);
  await expect.poll(async () => Math.round((await nav.boundingBox())?.width || 0)).toBeGreaterThanOrEqual(260);
  await expect
    .poll(async () => Math.round((await page.locator('.settings-section:visible').boundingBox())?.width || 0))
    .toBeGreaterThanOrEqual(1060);
});

test('settings modal does not load token ledger until the ledger tab is opened', async ({ page }) => {
  await installLoginState(page, await login());

  let tokenRecordRequests = 0;
  await page.route('**/users/token-records**', route => {
    tokenRecordRequests += 1;
    route.continue();
  });

  await page.goto('/#/chat', { waitUntil: 'domcontentloaded' });
  await expect(page.locator('.chat-shell .chat-sidebar')).toBeVisible();
  await page.getByRole('button', { name: '进入管理页面' }).click();

  await expect(page).toHaveURL(/#\/chat/);
  await expect(page.locator('[data-testid="settings-modal"]')).toBeVisible();
  await page.waitForTimeout(300);
  expect(tokenRecordRequests).toBe(0);

  await page.getByRole('button', { name: 'Token Ledger', exact: true }).click();
  await expect.poll(() => tokenRecordRequests).toBe(1);
  await expect(page.locator('.settings-nav__item--active')).toContainText('Token Ledger');
  await expect(page.getByRole('heading', { name: 'Token Ledger' })).toBeVisible();
});

test('settings modal exposes admin and billing entries in place', async ({ page }) => {
  await installLoginState(page, await login());

  await page.goto('/#/chat', { waitUntil: 'domcontentloaded' });
  await expect(page.locator('.chat-shell .chat-sidebar')).toBeVisible();
  await page.getByRole('button', { name: '进入管理页面' }).click();

  await expect(page.locator('[data-testid="settings-modal"]')).toBeVisible();

  await expect(page.getByRole('button', { name: 'Recharge', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'User Management', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Org Tag Admin', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: /^Embedding Model/ })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Invite Codes', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Usage Monitor', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Recharge Management', exact: true })).toBeVisible();

  for (const item of [
    { entry: 'Recharge', heading: 'Billing / 余额充值' },
    { entry: 'User Management', heading: 'User Registry / 用户' },
    { entry: 'Org Tag Admin', heading: 'Taxonomy Tags / 分类标签' },
    { entry: /^Embedding Model/, heading: 'Embedding Model / 向量模型' },
    { entry: 'Invite Codes', heading: 'Invite Codes / 邀请码' },
    { entry: 'Usage Monitor', heading: 'Runtime Limits / 限流配置' },
    { entry: 'Recharge Management', heading: 'Billing Packages / 充值套餐' }
  ]) {
    await page.getByRole('button', { name: item.entry, exact: typeof item.entry === 'string' }).click();
    await expect(page).toHaveURL(/#\/chat/);
    await expect(page.locator('[data-testid="settings-modal"]')).toBeVisible();
    await expect(page.getByText(item.heading).first()).toBeVisible();
  }
});

test('settings model provider entry loads real configuration in place', async ({ page }) => {
  const modelProviderStatuses: number[] = [];
  page.on('response', response => {
    if (response.url().includes('/admin/model-providers') && response.request().method() === 'GET') {
      modelProviderStatuses.push(response.status());
    }
  });

  await installLoginState(page, await login());

  await page.goto('/#/chat', { waitUntil: 'domcontentloaded' });
  await expect(page.locator('.chat-shell .chat-sidebar')).toBeVisible();
  await page.getByRole('button', { name: '进入管理页面' }).click();
  await page.getByRole('button', { name: /^Embedding Model/ }).click();

  await expect(page).toHaveURL(/#\/chat/);
  await expect(page.locator('[data-testid="settings-modal"]')).toBeVisible();
  await expect(page.getByText('Embedding Model / 向量模型').first()).toBeVisible();
  await expect(page.getByText('LLM Provider').first()).toHaveCount(0);
  await expect(page.getByText('Embedding Provider').first()).toBeVisible();
  await expect.poll(() => modelProviderStatuses).toContain(200);
});

test('regular users do not see or load admin settings content', async ({ page }) => {
  const adminModuleFragments = [
    '/src/views/user/',
    '/src/views/org-tag/',
    '/src/views/model-provider/',
    '/src/views/invite-code/',
    '/src/views/usage-monitor/',
    '/src/views/recharge-manage/'
  ];
  const adminModuleRequests: string[] = [];
  let adminApiRequests = 0;

  page.on('request', request => {
    const url = request.url();
    if (adminModuleFragments.some(fragment => url.includes(fragment))) {
      adminModuleRequests.push(url);
    }
  });

  await page.route('**/admin/**', route => {
    adminApiRequests += 1;
    route.fulfill({
      status: 403,
      contentType: 'application/json',
      body: JSON.stringify({ code: 403, message: 'forbidden' })
    });
  });

  await installRegularUserState(page);

  await page.goto('/#/chat', { waitUntil: 'domcontentloaded' });
  await expect(page.locator('.chat-shell .chat-sidebar')).toBeVisible();
  await page.getByRole('button', { name: '进入管理页面' }).click();

  await expect(page.locator('[data-testid="settings-modal"]')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Recharge', exact: true })).toBeVisible();

  for (const adminEntry of [
    'User Management',
    'Org Tag Admin',
    'Invite Codes',
    'Usage Monitor',
    'Recharge Management'
  ]) {
    await expect(page.getByRole('button', { name: adminEntry, exact: true })).toHaveCount(0);
  }
  await expect(page.getByRole('button', { name: /^Embedding Model/ })).toHaveCount(0);

  await page.waitForTimeout(300);
  expect(adminApiRequests).toBe(0);
  expect(adminModuleRequests).toEqual([]);
});

test('regular users cannot view admin pages through direct routes', async ({ page }) => {
  await installRegularUserState(page);

  const adminRoutes = [
    { path: '/chat-history', heading: 'Chat History' },
    { path: '/user', heading: 'User Registry / 用户' },
    { path: '/org-tag', heading: 'Taxonomy Tags / 分类标签' },
    { path: '/model-provider', heading: 'Embedding Model / 向量模型' },
    { path: '/invite-code', heading: 'Invite Codes / 邀请码' },
    { path: '/usage-monitor', heading: 'Runtime Limits / 限流配置' },
    { path: '/recharge-manage', heading: 'Billing Packages / 充值套餐' }
  ];

  for (const route of adminRoutes) {
    await page.goto(`/#${route.path}`, { waitUntil: 'domcontentloaded' });
    await expect(page).toHaveURL(/#\/403/);
    await expect(page.getByText(route.heading).first()).toHaveCount(0);
  }
});
