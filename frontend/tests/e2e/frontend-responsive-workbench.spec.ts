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

async function installWorkbenchMocks(page: import('@playwright/test').Page) {
  await page.addInitScript(
    ({ prefix }) => {
      localStorage.setItem(`${prefix}token`, JSON.stringify('mock-token'));
      localStorage.setItem(`${prefix}refreshToken`, JSON.stringify('mock-refresh-token'));
    },
    { prefix: storagePrefix }
  );

  await page.route('**/users/me**', route =>
    fulfillApi(route, {
      id: 1,
      username: 'researcher',
      role: 'ADMIN',
      orgTags: ['default'],
      primaryOrg: 'default'
    })
  );
  await page.route('**/users/org-tags**', route =>
    fulfillApi(route, {
      orgTags: ['default'],
      primaryOrg: 'default',
      orgTagDetails: [{ tagId: 'default', name: 'Default', description: 'Default workspace' }]
    })
  );
  await page.route('**/users/usage**', route =>
    fulfillApi(route, {
      day: '2026-07-13',
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
    })
  );
  await page.route('**/chat/active-generation**', route => fulfillApi(route, null));
  await page.route('**/paper-collections', route => fulfillApi(route, []));
}

test('mobile library uses paper rows without horizontal overflow', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await installWorkbenchMocks(page);

  await page.route(/\/users\/conversations$/, route => fulfillApi(route, []));
  await page.route('**/papers?scope=accessible**', route =>
    fulfillApi(route, {
      data: [
        {
          id: 1,
          paperId: 'paper-0001',
          originalFilename: 'evidence-grounded-research-agents.pdf',
          paperTitle: 'Evidence-Grounded Research Agents',
          totalSize: 3_400_000,
          uploadStatus: 'COMPLETED',
          processingStatus: 'COMPLETED',
          estimatedEmbeddingTokens: 42_000,
          estimatedChunkCount: 240,
          actualEmbeddingTokens: 40_500,
          actualChunkCount: 232,
          pdfEvidenceAvailable: true,
          parserArtifact: { available: true },
          tableAsset: { tableCount: 4 },
          figureAsset: { figureCount: 8 },
          formulaAsset: { formulaCount: 3 },
          visualAsset: { pageScreenshotCount: 18 },
          isPublic: false,
          orgTagName: 'Research',
          createdAt: '2026-07-13T08:00:00'
        }
      ],
      content: [],
      number: 1,
      size: 10,
      totalElements: 20
    })
  );

  await page.goto('/#/knowledge-base', { waitUntil: 'domcontentloaded' });

  await expect(page.locator('.paper-mobile-item')).toHaveCount(1);
  await expect(page.locator('.library-table')).toHaveCount(0);
  await expect(page.locator('.paper-mobile-item__title')).toContainText('evidence-grounded-research-agents.pdf');
  await expect(page.locator('.library-mobile-pagination')).toBeVisible();
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);

  const sidebarPosition = await page
    .locator('.paper-library-sidebar')
    .evaluate(element => getComputedStyle(element).position);
  expect(sidebarPosition).toBe('fixed');
});

test('mobile chat opens the evidence sheet from an inline citation', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await installWorkbenchMocks(page);

  const session = {
    id: 1,
    conversationId: 'visual-session',
    title: 'Grounded comparison review',
    status: 'ACTIVE',
    current: true,
    createdAt: '2026-07-13T08:00:00',
    updatedAt: '2026-07-13T09:00:00',
    scopeMode: 'AUTO_LIBRARY',
    scopeLocked: true,
    scopeStatus: 'READY',
    sourceLabel: 'Evaluation paper set',
    sourcePaperCount: 12
  };

  await page.route(/\/users\/conversations\/current$/, route => fulfillApi(route, session));
  await page.route(/\/users\/conversations$/, route => fulfillApi(route, [session]));
  await page.route(/\/users\/conversations\/visual-session\/scope$/, route =>
    fulfillApi(route, {
      scopeMode: 'AUTO_LIBRARY',
      scopeLocked: true,
      scopeStatus: 'READY',
      sourceLabel: 'Evaluation paper set',
      sourcePaperCount: 12,
      paperIds: [],
      sourceRecipe: null
    })
  );
  await page.route(/\/users\/conversation(?:\?|$)/, route =>
    fulfillApi(route, [
      {
        role: 'user',
        conversationId: 'visual-session',
        content: 'How does the paper support its comparison?',
        timestamp: '2026-07-13T09:00:00'
      },
      {
        role: 'assistant',
        conversationId: 'visual-session',
        content: 'The comparison is tied to a stable reading location [1].',
        status: 'finished',
        timestamp: '2026-07-13T09:00:10',
        referenceMappings: {
          '1': {
            paperTitle: 'Evidence-Grounded Research Agents',
            paperId: 'paper-0001',
            pageNumber: 4,
            sectionTitle: 'Method',
            evidenceSnippet: 'The comparison is tied to a stable reading location.',
            matchedChunkText: 'The comparison is tied to a stable reading location.',
            sourceType: 'PDF',
            evidenceAssetLevel: 'TEXT',
            pdfEvidenceAvailable: false
          }
        }
      }
    ])
  );

  await page.goto('/#/chat', { waitUntil: 'domcontentloaded' });

  const composerInput = page.getByRole('textbox', { name: 'Ask about a paper, method, claim, table, or citation' });
  await composerInput.focus();
  await expect(composerInput).toHaveCSS('outline-style', 'none');
  await expect(composerInput).toHaveCSS('box-shadow', 'none');

  const inlineCitation = page.locator('.source-citation-chip');
  await inlineCitation.focus();
  await expect(inlineCitation).toHaveClass(/source-citation-chip--active/);
  await inlineCitation.click();

  const sheet = page.getByRole('dialog', { name: 'Research review' });
  await expect(sheet).toBeVisible();
  await expect(sheet).toBeFocused();
  await expect(page.getByRole('button', { name: 'Close research review' })).toBeVisible();
  await expect(sheet).toContainText('Source Evidence');
  await expect(sheet).toContainText('Evidence-Grounded Research Agents');
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);

  const bounds = await sheet.boundingBox();
  expect(bounds).not.toBeNull();
  expect(Math.round(bounds?.width || 0)).toBe(390);
  expect((bounds?.y || 0) + (bounds?.height || 0)).toBeLessThanOrEqual(845);
});
