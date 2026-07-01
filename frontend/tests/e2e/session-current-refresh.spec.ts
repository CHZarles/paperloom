import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import process from 'node:process';
import { expect, test } from '@playwright/test';

type ApiEnvelope<T> = {
  code: number;
  message?: string;
  data: T;
};

type LoginToken = {
  token: string;
  refreshToken: string;
};

type ConversationSession = {
  conversationId: string;
  title: string;
  status: 'ACTIVE' | 'ARCHIVED';
};

const backendBaseURL = process.env.PAPERLOOM_E2E_API_BASE_URL || 'http://localhost:8081/api/v1';
const storagePrefix = process.env.PAPERLOOM_E2E_STORAGE_PREFIX || 'CiteWeave_';

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

async function listSessions(token: string) {
  return requestJson<ConversationSession[]>('/users/conversations', { token });
}

async function ensureTwoActiveSessions(token: string) {
  let activeSessions = (await listSessions(token)).filter(session => session.status === 'ACTIVE');

  if (activeSessions.length < 2) {
    await Promise.all(
      Array.from({ length: 2 - activeSessions.length }, () =>
        requestJson<ConversationSession>('/users/conversations', { method: 'POST', token })
      )
    );
    activeSessions = (await listSessions(token)).filter(session => session.status === 'ACTIVE');
  }

  if (activeSessions.length < 2) {
    throw new Error('Unable to create enough active sessions for refresh test');
  }

  return activeSessions;
}

test('refresh keeps the backend current conversation selected', async ({ page }) => {
  const loginToken = await login();
  const activeSessions = await ensureTwoActiveSessions(loginToken.token);
  const targetSession = activeSessions[1];

  await requestJson(`/users/conversations/${targetSession.conversationId}/switch`, {
    method: 'PUT',
    token: loginToken.token
  });

  const currentSession = await requestJson<ConversationSession>('/users/conversations/current', {
    token: loginToken.token
  });
  expect(currentSession.conversationId).toBe(targetSession.conversationId);

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

  await page.goto('/#/chat');

  const activeItem = page.locator('[data-testid="conversation-session"].session-item--active');
  await expect(activeItem).toHaveAttribute('data-conversation-id', targetSession.conversationId);

  await page.reload();
  await expect(activeItem).toHaveAttribute('data-conversation-id', targetSession.conversationId);
});
