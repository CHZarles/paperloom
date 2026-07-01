import process from 'node:process';
import { defineConfig, devices } from '@playwright/test';

const baseURL = process.env.PAPERLOOM_E2E_BASE_URL || 'http://localhost:9527';

export default defineConfig({
  testDir: './tests/e2e',
  timeout: 45_000,
  expect: {
    timeout: 10_000
  },
  fullyParallel: false,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure'
  },
  outputDir: './test-results',
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] }
    }
  ]
});
