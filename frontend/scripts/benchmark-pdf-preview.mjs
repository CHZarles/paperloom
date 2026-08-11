/* eslint-disable no-await-in-loop */
import { readFile, writeFile } from 'node:fs/promises';
import os from 'node:os';
import process from 'node:process';
import { setTimeout as delay } from 'node:timers/promises';
import { chromium } from '@playwright/test';

const baseURL = process.env.PAPERLOOM_BENCHMARK_BASE_URL || 'https://paperloom.me';
const executablePath =
  process.env.PAPERLOOM_CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const iterations = Number(process.env.PAPERLOOM_BENCHMARK_ITERATIONS || 5);
const outputPath = process.env.PAPERLOOM_BENCHMARK_OUTPUT;
const storageStatePath = process.env.PAPERLOOM_BENCHMARK_STORAGE;

function percentile(values, ratio) {
  const sorted = [...values].sort((left, right) => left - right);
  return sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * ratio) - 1)];
}

function summarize(samples) {
  const durations = samples.map(sample => sample.firstPageReadyMs);
  return {
    medianMs: percentile(durations, 0.5),
    minMs: Math.min(...durations),
    maxMs: Math.max(...durations)
  };
}

async function navigate(page, url) {
  let lastError;
  for (let attempt = 0; attempt < 3; attempt += 1) {
    try {
      return await page.goto(url, { waitUntil: 'domcontentloaded' });
    } catch (error) {
      lastError = error;
      await delay(500);
    }
  }
  throw lastError;
}

async function waitForFirstPage(page) {
  await page.waitForFunction(
    () => {
      const preview = document.querySelector('.file-preview-container');
      const canvas = preview?.querySelector('.pdf-canvas');
      const rendering = preview?.querySelector('.page-loading-mask');
      const feedback = preview?.querySelector('.stage-feedback');
      return canvas instanceof HTMLCanvasElement && canvas.width > 0 && canvas.height > 0 && !rendering && !feedback;
    },
    null,
    { timeout: 60_000 }
  );
}

async function measureOpen(page, previewButton, cacheState) {
  const pdfRequests = [];
  const onResponse = response => {
    if (!response.url().includes('/preview/pdf-data')) return;
    const headers = response.headers();
    pdfRequests.push({
      contentRange: headers['content-range'] || '',
      name: new URL(response.url()).pathname,
      range: response.request().headers().range || '',
      status: response.status()
    });
  };
  page.on('response', onResponse);
  await page.evaluate(() => {
    window.paperloomPdfBenchmarkStart = performance.now();
  });
  await previewButton.click();
  await waitForFirstPage(page);

  const metrics = await page.evaluate(cache => {
    const start = window.paperloomPdfBenchmarkStart;
    const end = performance.now();
    const resources = performance
      .getEntriesByType('resource')
      .filter(entry => entry.startTime >= start && entry.responseEnd <= end + 5 && entry.name.includes('/preview'))
      .map(entry => ({
        durationMs: Math.round(entry.duration * 10) / 10,
        encodedBytes: 'encodedBodySize' in entry ? entry.encodedBodySize : 0,
        name: new URL(entry.name).pathname,
        responseEndMs: Math.round((entry.responseEnd - start) * 10) / 10,
        startMs: Math.round((entry.startTime - start) * 10) / 10
      }));
    const networkReadyMs = Math.max(0, ...resources.map(entry => entry.responseEndMs));

    return {
      cacheState: cache,
      firstPageReadyMs: Math.round((end - start) * 10) / 10,
      networkReadyMs,
      renderAfterNetworkMs: Math.round((end - start - networkReadyMs) * 10) / 10,
      previewRequestCount: resources.length,
      previewTransferBytes: resources.reduce((sum, entry) => sum + entry.encodedBytes, 0),
      resources
    };
  }, cacheState);
  page.off('response', onResponse);
  return {
    ...metrics,
    previewRequestCount: Math.max(metrics.previewRequestCount, pdfRequests.length),
    pdfRequests
  };
}

const browser = await chromium.launch({ headless: true, executablePath });

try {
  let savedStorageState;
  if (storageStatePath) {
    try {
      savedStorageState = JSON.parse(await readFile(storageStatePath, 'utf8'));
    } catch {
      savedStorageState = undefined;
    }
  }

  const seedContext = await browser.newContext({
    storageState: savedStorageState,
    viewport: { width: 1440, height: 1000 }
  });
  const seedPage = await seedContext.newPage();
  await navigate(seedPage, baseURL);
  const guestLoginButton = seedPage.getByRole('button', { name: /游客登录|Continue as guest/ });
  await guestLoginButton.waitFor({ state: 'visible', timeout: 10_000 }).catch(() => undefined);
  if (await guestLoginButton.isVisible()) {
    await guestLoginButton.click();
    await seedPage.waitForURL(/#\/chat/);
  }
  const storageState = await seedContext.storageState();
  if (storageStatePath) {
    await writeFile(storageStatePath, `${JSON.stringify(storageState, null, 2)}\n`, { encoding: 'utf8', mode: 0o600 });
  }
  const userAgent = await seedPage.evaluate(() => navigator.userAgent);
  await seedContext.close();

  const cold = [];
  const warm = [];
  let paperTitle = '';

  for (let index = 0; index < iterations; index += 1) {
    const context = await browser.newContext({ storageState, viewport: { width: 1440, height: 1000 } });
    const page = await context.newPage();
    await navigate(page, `${baseURL}/#/knowledge-base`);
    await page.getByRole('heading', { name: 'Library' }).waitFor();

    const previewButton = page.getByRole('button', { name: 'Preview', exact: true }).first();
    await previewButton.waitFor();
    paperTitle ||= (await page.locator('.library-file-cell__name').first().textContent())?.trim() || 'unknown';

    cold.push(await measureOpen(page, previewButton, 'cold'));
    await page.getByRole('button', { name: '关闭', exact: true }).click();
    await page.locator('.file-preview-container').waitFor({ state: 'detached' });
    warm.push(await measureOpen(page, previewButton, 'warm'));
    await context.close();
  }

  const result = {
    measuredAt: new Date().toISOString(),
    baseURL,
    browser: userAgent,
    machine: {
      arch: os.arch(),
      cpu: os.cpus()[0]?.model || 'unknown',
      logicalCores: os.cpus().length,
      memoryGiB: Math.round((os.totalmem() / 1024 ** 3) * 10) / 10,
      platform: os.platform()
    },
    viewport: { width: 1440, height: 1000 },
    network: 'unthrottled',
    iterations,
    paperTitle,
    cold: { summary: summarize(cold), samples: cold },
    warm: { summary: summarize(warm), samples: warm }
  };

  if (outputPath) {
    await writeFile(outputPath, `${JSON.stringify(result, null, 2)}\n`, 'utf8');
  }
  console.log(JSON.stringify(result, null, 2));
} finally {
  await browser.close();
}
