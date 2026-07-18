/* eslint-disable no-await-in-loop */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import process from 'node:process';
import type { Page } from '@playwright/test';
import { expect, test } from '@playwright/test';
import { generatedRoutes } from '../../src/router/elegant/routes';

type ApiEnvelope<T> = {
  code: number;
  message?: string;
  data: T;
};

type LoginToken = {
  token: string;
  refreshToken: string;
};

const backendBaseURL = process.env.PAPERLOOM_E2E_API_BASE_URL || 'http://localhost:8081/api/v1';
const storagePrefix = process.env.PAPERLOOM_E2E_STORAGE_PREFIX || 'CiteWeave_';

const adminRoutes = ['org-tag', 'usage-monitor', 'invite-code', 'recharge', 'recharge-manage', 'chat-history'];

function readRepoEnv() {
  const envPath = resolve(process.cwd(), '..', '.env');
  const values: Record<string, string> = {};
  const text = readFileSync(envPath, 'utf8');

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

async function requestJson<T>(
  path: string,
  options: {
    method?: string;
    token?: string;
    body?: unknown;
  } = {}
) {
  const response = await fetch(`${backendBaseURL}${path}`, {
    method: options.method || 'GET',
    headers: {
      ...(options.token ? { Authorization: `Bearer ${options.token}` } : {}),
      ...(options.body ? { 'Content-Type': 'application/json' } : {})
    },
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

test('admin management pages keep the Manus-style shell stable', async ({ page }) => {
  const failedRequests: string[] = [];
  const consoleErrors: string[] = [];

  page.on('response', response => {
    const status = response.status();

    if (status >= 400) {
      failedRequests.push(`${status} ${response.url()}`);
    }
  });
  page.on('console', message => {
    if (message.type() === 'error') {
      consoleErrors.push(message.text());
    }
  });
  page.on('pageerror', error => {
    consoleErrors.push(error.message);
  });

  await installLoginState(page, await login());

  for (const route of adminRoutes) {
    await page.goto(`/#/${route}`);
    await expect(page.locator('.admin-console-page')).toBeVisible();
    await expect(page.locator('.global-header-shell')).toBeVisible();
    await expect(page.locator('.admin-console-card').first()).toBeVisible();
    await page.waitForTimeout(700);

    const metrics = await page.evaluate(() => {
      const classNameOf = (element: Element) => {
        const className = (element as HTMLElement).className;
        return typeof className === 'string' ? className : String(className);
      };

      const overflow = Math.max(0, document.documentElement.scrollWidth - document.documentElement.clientWidth);
      const gradientElements = Array.from(document.querySelectorAll('.admin-console-page *'))
        .filter(element => {
          const style = window.getComputedStyle(element);
          return style.display !== 'none' && style.backgroundImage.includes('gradient');
        })
        .map(element => classNameOf(element))
        .slice(0, 8);

      const radiusSelectors = [
        '.admin-console-card',
        '.limit-card',
        '.summary-card',
        '.overview-section',
        '.package-card',
        '.payment-qr-box'
      ];
      const largeRadiusElements = radiusSelectors.flatMap(selector =>
        Array.from(document.querySelectorAll(selector))
          .filter(element => Number.parseFloat(window.getComputedStyle(element).borderTopLeftRadius) > 8)
          .map(element => `${selector}:${classNameOf(element)}`)
      );

      return {
        overflow,
        gradientElements,
        largeRadiusElements
      };
    });

    expect(metrics.overflow, route).toBe(0);
    expect(metrics.gradientElements, route).toEqual([]);
    expect(metrics.largeRadiusElements, route).toEqual([]);
  }

  expect(failedRequests).toEqual([]);
  expect(consoleErrors).toEqual([]);
});

test('management functions stay out of generated global navigation metadata', () => {
  const managementRouteNames = [
    'chat-history',
    'org-tag',
    'invite-code',
    'usage-monitor',
    'recharge',
    'recharge-manage'
  ];
  const exposedRoutes = generatedRoutes
    .filter(route => managementRouteNames.includes(route.name as string))
    .filter(route => !route.meta?.hideInMenu)
    .map(route => route.name);

  expect(exposedRoutes).toEqual([]);
});

test('user management is not exposed as a standalone route', () => {
  expect(generatedRoutes.some(route => route.path === '/user')).toBe(false);
});

test('chat return button keeps a neutral focus treatment', async ({ page }) => {
  await installLoginState(page, await login());
  await page.goto('/#/org-tag');

  const button = page.getByRole('button', { name: '返回 Chat' });
  await button.focus();

  const treatment = await button.evaluate(element => {
    const wave = element.querySelector('.n-base-wave');
    const style = getComputedStyle(element);

    return {
      outlineStyle: style.outlineStyle,
      boxShadow: style.boxShadow,
      border: style.getPropertyValue('--n-border').trim(),
      focusBorder: style.getPropertyValue('--n-border-focus').trim(),
      waveDisplay: wave ? getComputedStyle(wave).display : ''
    };
  });

  expect(treatment.outlineStyle).toBe('none');
  expect(treatment.boxShadow).toBe('none');
  expect(treatment.border).toBe('none');
  expect(treatment.focusBorder).toBe('none');
  expect(treatment.waveDisplay).toBe('none');
});

test('global avatar exposes the management entry', async ({ page }) => {
  await installLoginState(page, await login());

  await page.goto('/#/org-tag');
  await expect(page.locator('.global-header-shell .avatar-identicon')).toBeVisible();
  await page.locator('.global-header-shell .avatar-identicon').click();
  await page.getByText('管理页面').click();

  await expect(page).toHaveURL(/#\/org-tag/);
  await expect(page.locator('[data-testid="settings-modal"]')).toBeVisible();
});
