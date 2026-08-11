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
  const durations = samples.map(sample => sample.readyMs);
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

async function storedAuthorization(page) {
  return page.evaluate(() => {
    const raw = localStorage.getItem('CiteWeave_token');
    if (!raw) return '';
    try {
      return `Bearer ${JSON.parse(raw)}`;
    } catch {
      return `Bearer ${raw}`;
    }
  });
}

async function findCandidate(page) {
  await navigate(page, `${baseURL}/#/knowledge-base`);
  await page.getByRole('heading', { name: 'Library' }).waitFor();
  const authorization = await storedAuthorization(page);

  const papers = await page.evaluate(async authorizationHeader => {
    const response = await fetch('/api/v1/papers?scope=accessible&page=1&size=100', {
      headers: { Authorization: authorizationHeader }
    });
    if (!response.ok) throw new Error(`paper list failed: ${response.status}`);
    const body = await response.json();
    const payload = body.data ?? body;
    return Array.isArray(payload) ? payload : (payload.data ?? payload.content ?? []);
  }, authorization);

  for (const paper of papers) {
    const pageCount = Number(paper.visualAsset?.pageScreenshotCount || 0);
    for (let pageNumber = 1; pageNumber <= Math.min(pageCount, 10); pageNumber += 1) {
      const available = await page.evaluate(
        async ({ authorizationHeader, paperId, targetPage }) => {
          const response = await fetch(`/api/v1/papers/${encodeURIComponent(paperId)}/pages/${targetPage}/screenshot`, {
            headers: { Authorization: authorizationHeader }
          });
          return response.ok;
        },
        { authorizationHeader: authorization, paperId: paper.paperId, targetPage: pageNumber }
      );
      if (available) {
        return {
          paperId: paper.paperId,
          paperTitle: paper.paperTitle || paper.originalFilename || paper.paperId,
          pageNumber
        };
      }
    }
  }

  throw new Error('no accessible paper with a page screenshot was found');
}

function evidenceUrl(candidate) {
  const params = new URLSearchParams({
    evidence: 'reference',
    paperId: candidate.paperId,
    paperTitle: candidate.paperTitle,
    pageNumber: String(candidate.pageNumber),
    referenceNumber: '1'
  });
  return `${baseURL}/#/chat?${params}`;
}

async function measurePdf(browser, storageState, candidate) {
  const context = await browser.newContext({ storageState, viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage();
  await navigate(page, evidenceUrl(candidate));
  const button = page.getByRole('button', { name: 'View PDF evidence' });
  await button.waitFor();
  await page.evaluate(() => performance.clearResourceTimings());
  const start = performance.now();
  await button.click();
  await page.waitForFunction(
    () => {
      const viewer = document.querySelector('.source-evidence__pdf-viewer');
      const canvas = viewer?.querySelector('.pdf-canvas');
      return (
        canvas instanceof HTMLCanvasElement &&
        canvas.width > 0 &&
        canvas.height > 0 &&
        !viewer?.querySelector('.page-loading-mask') &&
        !viewer?.querySelector('.stage-feedback')
      );
    },
    null,
    { timeout: 60_000 }
  );
  const readyMs = Math.round((performance.now() - start) * 10) / 10;
  const resources = await page.evaluate(() =>
    performance
      .getEntriesByType('resource')
      .filter(entry => entry.name.includes('/preview/pdf-data'))
      .map(entry => ({
        durationMs: Math.round(entry.duration * 10) / 10,
        encodedBytes: 'encodedBodySize' in entry ? entry.encodedBodySize : 0,
        name: new URL(entry.name).pathname
      }))
  );
  await context.close();
  return { readyMs, resources };
}

async function measureImage(browser, storageState, candidate) {
  const context = await browser.newContext({ storageState, viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage();
  await navigate(page, evidenceUrl(candidate));
  await page.locator('.source-evidence').waitFor();
  const authorization = await storedAuthorization(page);
  const result = await page.evaluate(
    async ({ authorizationHeader, paperId, pageNumber }) => {
      performance.clearResourceTimings();
      const start = performance.now();
      const descriptorResponse = await fetch(
        `/api/v1/papers/${encodeURIComponent(paperId)}/pages/${pageNumber}/screenshot`,
        { headers: { Authorization: authorizationHeader } }
      );
      if (!descriptorResponse.ok) throw new Error(`page screenshot failed: ${descriptorResponse.status}`);
      const descriptor = await descriptorResponse.json();
      const descriptorReadyMs = performance.now() - start;
      const image = new Image();
      await new Promise((resolve, reject) => {
        image.onload = resolve;
        image.onerror = reject;
        image.src = descriptor.data.downloadUrl;
      });
      await image.decode();
      const readyMs = performance.now() - start;
      return {
        readyMs: Math.round(readyMs * 10) / 10,
        descriptorReadyMs: Math.round(descriptorReadyMs * 10) / 10,
        width: image.naturalWidth,
        height: image.naturalHeight,
        resources: performance
          .getEntriesByType('resource')
          .filter(entry => entry.startTime >= start)
          .map(entry => ({
            durationMs: Math.round(entry.duration * 10) / 10,
            encodedBytes: 'encodedBodySize' in entry ? entry.encodedBodySize : 0,
            name: new URL(entry.name).pathname
          }))
      };
    },
    { authorizationHeader: authorization, paperId: candidate.paperId, pageNumber: candidate.pageNumber }
  );
  await context.close();
  return result;
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
  const candidate = await findCandidate(seedPage);
  const storageState = await seedContext.storageState();
  if (storageStatePath) {
    await writeFile(storageStatePath, `${JSON.stringify(storageState, null, 2)}\n`, { encoding: 'utf8', mode: 0o600 });
  }
  const userAgent = await seedPage.evaluate(() => navigator.userAgent);
  await seedContext.close();

  const pdf = [];
  const image = [];
  for (let index = 0; index < iterations; index += 1) {
    if (index % 2 === 0) {
      pdf.push(await measurePdf(browser, storageState, candidate));
      image.push(await measureImage(browser, storageState, candidate));
    } else {
      image.push(await measureImage(browser, storageState, candidate));
      pdf.push(await measurePdf(browser, storageState, candidate));
    }
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
    candidate,
    pdf: { summary: summarize(pdf), samples: pdf },
    image: { summary: summarize(image), samples: image }
  };

  if (outputPath) await writeFile(outputPath, `${JSON.stringify(result, null, 2)}\n`, 'utf8');
  console.log(JSON.stringify(result, null, 2));
} finally {
  await browser.close();
}
