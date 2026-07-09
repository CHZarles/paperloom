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

test('reading artifacts render after reload and source evidence resolves from sourceQuoteRef', async ({ page }) => {
  await installMockLoginState(page);

  const currentSession = {
    id: 120,
    conversationId: 'reading-session',
    title: 'Agent eval reading',
    status: 'ACTIVE',
    createdAt: '2026-07-08T08:00:00',
    updatedAt: '2026-07-08T08:05:00',
    scopeMode: 'AUTO_LIBRARY',
    scopeLocked: true,
    scopeStatus: 'READY',
    sourceLabel: 'All readable papers',
    sourcePaperCount: 30
  };

  const readingArtifacts = {
    artifactVersion: 'reading-turn-artifacts/v1',
    goalCard: {
      interpretedGoal: 'find beginner-friendly AI agent evaluation papers',
      scopeLabel: 'All readable papers',
      readablePaperCount: 30,
      scopeLocked: true,
      actions: [{ action: 'REFINE_GOAL', label: 'Refine goal', payload: {} }]
    },
    intentFrame: {
      originalUserRequest: 'find beginner-friendly AI agent evaluation papers',
      paperQueryTexts: ['AI agent evaluation'],
      locationQueryTexts: [],
      locationTypes: [],
      locationIntents: [],
      sourceLanguages: [],
      retrievalLanguages: [],
      sectionRoles: [],
      planningStatus: 'paper_query_observed',
      missing: []
    },
    paperShortlist: {
      items: [
        {
          paperId: 'paper-1',
          paperHandle: 'paper_handle_agent_eval',
          title: 'Agentic Eval Benchmark',
          originalFilename: 'agentic-eval.pdf',
          authors: ['Ada Lovelace'],
          year: 2025,
          venue: 'NeurIPS',
          role: '',
          roleEvidenceStatus: 'role metadata missing; no beginner role assigned',
          roleEvidenceSource: 'missing_role_metadata',
          matchReason: 'Matched paper metadata fields: title, abstract.',
          evidenceStatus: 'metadata-only; no quoted passage has been read yet',
          ambiguous: false,
          actions: [
            {
              action: 'LIST_LOCATIONS',
              label: 'Show readable locations',
              payload: {
                paperId: 'paper-1',
                paperHandle: 'paper_handle_agent_eval',
                paperTitle: 'Agentic Eval Benchmark',
                originalFilename: 'agentic-eval.pdf',
                readingAction: 'LIST_LOCATIONS'
              }
            }
          ]
        }
      ]
    },
    readingPlan: { steps: [] },
    claimEvidencePanel: {
      rows: [
        {
          claim: 'The selected passage discusses agent evaluation.',
          quote: 'Agent evaluation requires controlled task suites and auditable evidence.',
          citationMarker: '[1]',
          sourceQuoteRef: 'source_quote_agent_eval',
          paperId: 'paper-1',
          paperHandle: 'paper_handle_agent_eval',
          paperTitle: 'Agentic Eval Benchmark',
          locationRef: 'page_ref_agent_eval_7',
          locationLabel: 'Page 7',
          contentKind: 'TEXT',
          cannotProve: ['results outside the cited paragraph'],
          actions: [
            {
              action: 'OPEN_SOURCE_QUOTE',
              label: 'Open citation',
              payload: {
                sourceQuoteRef: 'source_quote_agent_eval',
                paperId: 'paper-1',
                paperHandle: 'paper_handle_agent_eval',
                paperTitle: 'Agentic Eval Benchmark',
                locationRef: 'page_ref_agent_eval_7',
                citationMarker: '[1]'
              }
            }
          ]
        }
      ]
    },
    missingEvidence: {
      missing: ['visual_pdf_page_evidence'],
      explanation: 'The quoted text is available; visual PDF/page evidence is only proven by citation detail.',
      nextActions: []
    },
    uncertaintyNotes: ['Visual PDF/page evidence may still be unavailable unless the citation panel shows it.'],
    traceSummary: {
      steps: [
        {
          stage: 'INTENT',
          label: 'Intent interpreted',
          detail: 'Answer shape: filtered shortlist. Capabilities: paper discovery',
          status: 'unambiguous'
        },
        {
          stage: 'RETRIEVAL',
          label: 'Semantic retrieval',
          detail: 'candidate papers',
          status: 'observed'
        },
        {
          stage: 'VERIFICATION',
          label: 'Verification pass',
          detail: 'Verification checks passed.',
          status: 'COMPLETED'
        }
      ],
      evidence: {
        acceptedCount: 1,
        rejectedCount: 0,
        missingCount: 1,
        missing: ['visual_pdf_page_evidence']
      },
      claims: {
        totalCount: 1,
        supportedCount: 1,
        underdeterminedCount: 0,
        contradictedCount: 0
      },
      verification: {
        valid: true,
        resultStatus: 'COMPLETED',
        stopReason: 'COMPLETED',
        requiredEvidenceStatus: 'satisfied',
        missingRequiredEvidenceCount: 0,
        failedObligationCount: 0
      }
    },
    uiActions: [
      {
        action: 'OPEN_SOURCE_QUOTE',
        label: 'Open citation',
        payload: {
          sourceQuoteRef: 'source_quote_agent_eval',
          paperId: 'paper-1',
          paperHandle: 'paper_handle_agent_eval',
          paperTitle: 'Agentic Eval Benchmark',
          locationRef: 'page_ref_agent_eval_7',
          citationMarker: '[1]'
        }
      }
    ]
  };

  const history = [
    {
      role: 'user',
      conversationId: 'reading-session',
      content: 'What should I read first for agent evaluation?',
      timestamp: '2026-07-08T08:00:00'
    },
    {
      role: 'assistant',
      conversationId: 'reading-session',
      conversationRecordId: 9001,
      content:
        'I understand your goal as: find beginner-friendly AI agent evaluation papers.\n\nShort answer: The selected passage discusses agent evaluation [1].',
      timestamp: '2026-07-08T08:01:00',
      referenceMappings: {
        '1': {
          referenceNumber: 1,
          sourceQuoteRef: 'source_quote_agent_eval',
          paperId: 'stale-paper',
          paperTitle: 'Stale persisted title',
          retrievalQuery: 'stale query',
          matchedChunkText: 'Stale persisted quote.',
          evidenceSnippet: 'Stale persisted quote.'
        }
      },
      readingArtifacts
    }
  ];

  let referenceDetailRequests = 0;

  await page.route('**/users/org-tags', route =>
    fulfillApi(route, {
      orgTags: ['default'],
      primaryOrg: 'default',
      orgTagDetails: [{ tagId: 'default', name: 'Default', description: 'Default workspace' }]
    })
  );
  await page.route('**/users/usage', route =>
    fulfillApi(route, {
      day: '2026-07-08',
      chatRequestCount: 0,
      llm: { enabled: true, usedTokens: 0, limitTokens: 1000, remainingTokens: 1000, requestCount: 0 },
      embedding: { enabled: true, usedTokens: 0, limitTokens: 1000, remainingTokens: 1000, requestCount: 0 }
    })
  );
  await page.route('**/chat/active-generation**', route => fulfillApi(route, null));
  await page.route(/\/users\/conversations$/, route => fulfillApi(route, [currentSession]));
  await page.route(/\/users\/conversations\/current$/, route => fulfillApi(route, currentSession));
  await page.route(/\/users\/conversations\/reading-session\/switch$/, route => fulfillApi(route, {}));
  await page.route(/\/users\/conversations\/reading-session\/scope$/, route =>
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
  await page.route(/\/users\/conversation(?:\?|$)/, route => fulfillApi(route, history));
  await page.route('**/papers/reference-detail**', route => {
    referenceDetailRequests += 1;
    return fulfillApi(route, {
      referenceNumber: 1,
      sourceQuoteRef: 'source_quote_agent_eval',
      paperId: 'paper-1',
      paperTitle: 'Agentic Eval Benchmark',
      originalFilename: 'agentic-eval.pdf',
      pageNumber: 7,
      sourceKind: 'TEXT',
      contentKind: 'TEXT',
      matchedChunkText: 'Agent evaluation requires controlled task suites and auditable evidence.',
      evidenceSnippet: 'Agent evaluation requires controlled task suites and auditable evidence.',
      pdfEvidenceAvailable: false,
      pageScreenshotAvailable: false,
      assetWarnings: ['pdf_page_visual_evidence_unavailable']
    });
  });

  await page.goto('/#/chat', { waitUntil: 'domcontentloaded' });

  await expect(page.locator('.reading-artifacts')).toBeVisible();
  await expect(page.locator('.reading-artifacts')).toContainText('find beginner-friendly AI agent evaluation papers');
  await expect(page.locator('.reading-artifacts')).toContainText('All readable papers · 30 papers · locked');
  await expect(page.locator('.reading-artifacts')).toContainText('Decision Path');
  await expect(page.locator('.reading-artifacts')).toContainText('Paper discovery');
  await expect(page.locator('.reading-artifacts')).toContainText('Verification');
  await expect(page.locator('.reading-artifacts')).toContainText('The verification check passed.');
  await expect(page.locator('.reading-artifacts')).not.toContainText('trace gate');
  await expect(page.locator('.reading-artifacts')).toContainText('1 accepted · 1 missing');
  await expect(page.locator('.reading-artifacts')).toContainText('1 total · 1 supported');
  await expect(page.locator('.reading-artifacts')).toContainText('Paper Shortlist');
  await expect(page.locator('.reading-artifacts')).toContainText('role metadata missing; no beginner role assigned');
  await expect(page.locator('.reading-artifacts')).toContainText('Evidence');
  await expect(page.locator('.product-reading-paper-choice-list')).toHaveCount(0);

  await page.locator('.reading-artifacts button[title="Open citation"]').click();
  await expect(page.locator('.reference-panel')).toBeVisible();
  await expect(page.locator('.source-evidence')).toContainText('Agentic Eval Benchmark');
  await expect(page.locator('.source-evidence')).not.toContainText('Stale persisted title');
  await expect(page.locator('.source-evidence')).not.toContainText('Stale persisted quote.');
  await expect(page.locator('.source-evidence')).toContainText('Page 7');
  await expect(page.locator('.source-evidence')).toContainText(
    'Agent evaluation requires controlled task suites and auditable evidence.'
  );
  await expect(page.locator('.source-evidence')).toContainText('PDF page visual evidence is unavailable.');

  await page.getByRole('button', { name: /Ask about this/ }).click();
  await expect(page.locator('.scope-chip')).toContainText('Agentic Eval Benchmark');
  await expect(page.locator('.scope-chip')).toContainText('Trace citation');
  await expect(page.locator('.scope-chip')).toContainText('p7');
  await expect(page.locator('.scope-chip')).toContainText('[1]');
  await expect(page.locator('textarea[placeholder="Ask about a paper, method, claim, table, or citation"]')).toHaveValue(
    '解释这个引用'
  );
  expect(referenceDetailRequests).toBe(1);
});

test('paper shortlist actions send structured focus and reading plan survives reload', async ({ page }) => {
  await installMockLoginState(page);

  const currentSession = {
    id: 121,
    conversationId: 'reading-session',
    title: 'Agent eval navigation',
    status: 'ACTIVE',
    createdAt: '2026-07-08T09:00:00',
    updatedAt: '2026-07-08T09:05:00',
    scopeMode: 'AUTO_LIBRARY',
    scopeLocked: true,
    scopeStatus: 'READY',
    sourceLabel: 'All readable papers',
    sourcePaperCount: 30
  };

  const shortlistArtifacts = {
    artifactVersion: 'reading-turn-artifacts/v1',
    goalCard: {
      interpretedGoal: 'find beginner-friendly AI agent evaluation papers',
      scopeLabel: 'All readable papers',
      readablePaperCount: 30,
      scopeLocked: true,
      actions: []
    },
    intentFrame: {
      originalUserRequest: 'find beginner-friendly AI agent evaluation papers',
      paperQueryTexts: ['AI agent evaluation'],
      locationQueryTexts: [],
      locationTypes: [],
      locationIntents: [],
      sourceLanguages: [],
      retrievalLanguages: [],
      sectionRoles: [],
      planningStatus: 'paper_query_observed',
      missing: []
    },
    paperShortlist: {
      items: [
        {
          paperId: 'paper-1',
          paperHandle: 'paper_handle_agent_eval',
          title: 'Agentic Eval Benchmark',
          originalFilename: 'agentic-eval.pdf',
          authors: ['Ada Lovelace'],
          year: 2025,
          venue: 'NeurIPS',
          role: 'benchmark',
          roleEvidenceStatus: 'role metadata provided by paperTypes',
          roleEvidenceSource: 'paperTypes',
          matchReason: 'Matched paper metadata fields: title, abstract.',
          evidenceStatus: 'metadata-only; no quoted passage has been read yet',
          ambiguous: false,
          actions: [
            {
              action: 'LIST_LOCATIONS',
              label: 'Show readable locations',
              payload: {
                paperId: 'paper-1',
                paperHandle: 'paper_handle_agent_eval',
                paperTitle: 'Agentic Eval Benchmark',
                originalFilename: 'agentic-eval.pdf',
                readingAction: 'LIST_LOCATIONS'
              }
            }
          ]
        }
      ]
    },
    readingPlan: { steps: [] },
    claimEvidencePanel: { rows: [] },
    missingEvidence: {
      missing: ['paper_content_quote'],
      explanation: 'No quote-backed evidence has been attached to this turn.',
      nextActions: []
    },
    uncertaintyNotes: ['The paper shortlist is metadata-only until a passage is read.'],
    traceSummary: {
      steps: [
        {
          stage: 'RETRIEVAL',
          label: 'Paper discovery',
          detail: 'candidate papers',
          status: 'observed'
        }
      ],
      evidence: {
        acceptedCount: 0,
        rejectedCount: 0,
        missingCount: 1,
        missing: ['paper_content_quote']
      },
      claims: {
        totalCount: 0,
        supportedCount: 0,
        underdeterminedCount: 0,
        contradictedCount: 0
      },
      verification: {
        valid: false,
        resultStatus: 'INCOMPLETE_PRECISE',
        stopReason: 'ANSWER_SCHEMA_INVALID',
        requiredEvidenceStatus: 'missing',
        missingRequiredEvidenceCount: 1,
        failedObligationCount: 0
      }
    },
    uiActions: []
  };

  const readingPlanArtifacts = {
    artifactVersion: 'reading-turn-artifacts/v1',
    goalCard: shortlistArtifacts.goalCard,
    intentFrame: {
      originalUserRequest: '列出这篇论文可阅读的位置',
      readingAction: 'LIST_LOCATIONS',
      paperQueryTexts: [],
      locationQueryTexts: [],
      locationTypes: ['PAGE', 'SECTION'],
      locationIntents: [],
      sourceLanguages: [],
      retrievalLanguages: [],
      sectionRoles: [],
      planningStatus: 'not_planned',
      missing: []
    },
    paperShortlist: { items: [] },
    readingPlan: {
      steps: [
        {
          paperId: 'paper-1',
          paperHandle: 'paper_handle_agent_eval',
          paperTitle: 'Agentic Eval Benchmark',
          locationRef: 'page_ref_agent_eval_intro',
          locationLabel: 'Introduction, page 1',
          preview: 'The introduction frames agent evaluation as a benchmark design problem.',
          evidenceStatus: 'navigation-only; this location has not been read as quoted evidence yet',
          actions: [
            {
              action: 'READ_LOCATION',
              label: 'Read location',
              payload: {
                paperId: 'paper-1',
                paperHandle: 'paper_handle_agent_eval',
                paperTitle: 'Agentic Eval Benchmark',
                originalFilename: 'agentic-eval.pdf',
                locationRef: 'page_ref_agent_eval_intro',
                locationLabel: 'Introduction, page 1'
              }
            }
          ]
        }
      ]
    },
    claimEvidencePanel: { rows: [] },
    missingEvidence: {
      missing: ['read_location_quote'],
      explanation: 'The reading locations are navigation targets, not quote-backed claims yet.',
      nextActions: []
    },
    uncertaintyNotes: ['The reading locations are navigation targets, not quote-backed claims yet.'],
    traceSummary: {
      steps: [
        {
          stage: 'RETRIEVAL',
          label: 'Deterministic locations',
          detail: 'readable locations for Agentic Eval Benchmark',
          status: 'observed'
        }
      ],
      evidence: {
        acceptedCount: 0,
        rejectedCount: 0,
        missingCount: 1,
        missing: ['read_location_quote']
      },
      claims: {
        totalCount: 0,
        supportedCount: 0,
        underdeterminedCount: 0,
        contradictedCount: 0
      },
      verification: {
        valid: false,
        resultStatus: 'INCOMPLETE_PRECISE',
        stopReason: 'ANSWER_SCHEMA_INVALID',
        requiredEvidenceStatus: 'missing',
        missingRequiredEvidenceCount: 1,
        failedObligationCount: 0
      }
    },
    uiActions: []
  };

  const readingStatePatch = {
    selectedPaper: {
      paperId: 'paper-1',
      paperHandle: 'paper_handle_agent_eval',
      title: 'Agentic Eval Benchmark',
      originalFilename: 'agentic-eval.pdf'
    },
    selectedLocation: {
      paperId: 'paper-1',
      paperHandle: 'paper_handle_agent_eval',
      locationRef: 'page_ref_agent_eval_intro',
      locationLabel: 'Introduction, page 1'
    },
    latestShortlist: []
  };

  const initialHistory = [
    {
      role: 'user',
      conversationId: 'reading-session',
      content: 'What should I read first for agent evaluation?',
      timestamp: '2026-07-08T09:00:00'
    },
    {
      role: 'assistant',
      conversationId: 'reading-session',
      conversationRecordId: 9101,
      content: 'I found a short reading path, not a fully verified paper-content answer yet.',
      timestamp: '2026-07-08T09:01:00',
      readingArtifacts: shortlistArtifacts
    }
  ];
  let history = initialHistory;
  const sentMessages: Array<Record<string, any>> = [];

  await page.routeWebSocket(/\/proxy-ws\/chat\//, ws => {
    ws.send(JSON.stringify({ type: 'connection', sessionId: 'mock-reading-ws' }));
    ws.onMessage(message => {
      if (message === '__chat_ping__') {
        ws.send('__chat_pong__');
        return;
      }
      const payload = JSON.parse(String(message));
      sentMessages.push(payload);
      if (payload.type !== 'user_message') return;

      if (payload.referenceFocus?.readingAction === 'LIST_LOCATIONS') {
        const generationId = 'generation-list-locations';
        const assistantContent = 'I found concrete places to inspect.';
        history = [
          ...initialHistory,
          {
            role: 'user',
            conversationId: 'reading-session',
            content: payload.message,
            timestamp: '2026-07-08T09:02:00'
          },
          {
            role: 'assistant',
            conversationId: 'reading-session',
            conversationRecordId: 9102,
            content: assistantContent,
            timestamp: '2026-07-08T09:03:00',
            readingArtifacts: readingPlanArtifacts,
            readingStatePatch
          }
        ];
        ws.send(
          JSON.stringify({
            type: 'start',
            generationId,
            conversationId: 'reading-session',
            timestamp: '2026-07-08T09:02:00'
          })
        );
        ws.send(JSON.stringify({ generationId, chunk: assistantContent }));
        ws.send(
          JSON.stringify({
            type: 'completion',
            generationId,
            conversationId: 'reading-session',
            status: 'finished',
            route: 'PAPER_QA',
            readingArtifacts: readingPlanArtifacts,
            readingStatePatch
          })
        );
      }
    });
  });

  await page.route('**/users/org-tags', route =>
    fulfillApi(route, {
      orgTags: ['default'],
      primaryOrg: 'default',
      orgTagDetails: [{ tagId: 'default', name: 'Default', description: 'Default workspace' }]
    })
  );
  await page.route('**/users/usage', route =>
    fulfillApi(route, {
      day: '2026-07-08',
      chatRequestCount: 0,
      llm: { enabled: true, usedTokens: 0, limitTokens: 1000, remainingTokens: 1000, requestCount: 0 },
      embedding: { enabled: true, usedTokens: 0, limitTokens: 1000, remainingTokens: 1000, requestCount: 0 }
    })
  );
  await page.route('**/chat/active-generation**', route => fulfillApi(route, null));
  await page.route(/\/users\/conversations$/, route => fulfillApi(route, [currentSession]));
  await page.route(/\/users\/conversations\/current$/, route => fulfillApi(route, currentSession));
  await page.route(/\/users\/conversations\/reading-session\/switch$/, route => fulfillApi(route, {}));
  await page.route(/\/users\/conversations\/reading-session\/scope$/, route =>
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
  await page.route(/\/users\/conversation(?:\?|$)/, route => fulfillApi(route, history));

  await page.goto('/#/chat', { waitUntil: 'domcontentloaded' });

  await expect(page.locator('.reading-artifacts').last()).toContainText('Paper Shortlist');
  await page.locator('.reading-artifacts button[title="Show readable locations"]').click();
  await expect(page.locator('.scope-chip')).toContainText('Agentic Eval Benchmark');
  await expect(page.locator('.scope-chip')).toContainText('List locations');
  await expect(page.locator('textarea[placeholder="Ask about a paper, method, claim, table, or citation"]')).toHaveValue(
    '列出这篇论文可阅读的位置'
  );

  await page.locator('textarea[placeholder="Ask about a paper, method, claim, table, or citation"]').press('Enter');
  await expect
    .poll(() => sentMessages.filter(item => item.type === 'user_message').length)
    .toBe(1);
  const listLocationsRequest = sentMessages.find(item => item.type === 'user_message');
  expect(listLocationsRequest?.conversationId).toBe('reading-session');
  expect(listLocationsRequest?.referenceFocus?.paperHandle).toBe('paper_handle_agent_eval');
  expect(listLocationsRequest?.referenceFocus?.readingAction).toBe('LIST_LOCATIONS');

  await expect(page.locator('.reading-artifacts').last()).toContainText('Reading Plan');
  await expect(page.locator('.reading-artifacts').last()).toContainText('Introduction, page 1');

  await page.reload({ waitUntil: 'domcontentloaded' });
  await expect(page.locator('.reading-artifacts').last()).toContainText('Reading Plan');
  await expect(page.locator('.reading-artifacts').last()).toContainText('Agentic Eval Benchmark');
  await page.locator('.reading-artifacts button[title="Read location"]').last().click();
  await expect(page.locator('.scope-chip')).toContainText('Agentic Eval Benchmark');
  await expect(page.locator('.scope-chip')).toContainText('Read location');
  await expect(page.locator('textarea[placeholder="Ask about a paper, method, claim, table, or citation"]')).toHaveValue(
    '读取这个位置'
  );

  await page.locator('textarea[placeholder="Ask about a paper, method, claim, table, or citation"]').press('Enter');
  await expect
    .poll(() => sentMessages.filter(item => item.type === 'user_message').length)
    .toBe(2);
  const readLocationRequest = sentMessages.filter(item => item.type === 'user_message').at(-1);
  expect(readLocationRequest?.referenceFocus?.paperHandle).toBe('paper_handle_agent_eval');
  expect(readLocationRequest?.referenceFocus?.locationRef).toBe('page_ref_agent_eval_intro');
  expect(readLocationRequest?.referenceFocus?.readingAction).toBe('READ_LOCATION');
});
