import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const siteRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const mermaidCliPackage = '@mermaid-js/mermaid-cli@11.12.0';
const puppeteerPackage = 'puppeteer@23';
const config = path.join(siteRoot, 'diagrams', 'mermaid-config.json');
const browserPath = process.env.PUPPETEER_EXECUTABLE_PATH || [
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser',
  '/snap/bin/chromium'
].find(existsSync);
const childEnv = browserPath
  ? { ...process.env, PUPPETEER_EXECUTABLE_PATH: browserPath, PUPPETEER_SKIP_DOWNLOAD: 'true' }
  : process.env;
const outputs = [
  ['runtime-guest-auth.mmd', 'runtime-guest-auth.svg', '1700'],
  ['runtime-guest-auth.mmd', 'runtime-guest-auth.png', '1700'],
  ['runtime-paper-ingestion.mmd', 'runtime-paper-ingestion.svg', '2000'],
  ['runtime-paper-ingestion.mmd', 'runtime-paper-ingestion.png', '2000'],
  ['runtime-research-chat.mmd', 'runtime-research-chat.svg', '2000'],
  ['runtime-research-chat.mmd', 'runtime-research-chat.png', '2000'],
  ['runtime-agent-loop.mmd', 'runtime-agent-loop.svg', '1800'],
  ['runtime-agent-loop.mmd', 'runtime-agent-loop.png', '1800'],
  ['runtime-cancel.mmd', 'runtime-cancel.svg', '1600'],
  ['runtime-cancel.mmd', 'runtime-cancel.png', '1600'],
  ['runtime-retry.mmd', 'runtime-retry.svg', '1600'],
  ['runtime-retry.mmd', 'runtime-retry.png', '1600'],
  ['runtime-pdf-evidence.mmd', 'runtime-pdf-evidence.svg', '1600'],
  ['runtime-pdf-evidence.mmd', 'runtime-pdf-evidence.png', '1600'],
  ['runtime-registration-invite.mmd', 'runtime-registration-invite.svg', '1800'],
  ['runtime-registration-invite.mmd', 'runtime-registration-invite.png', '1800'],
  ['runtime-account-session.mmd', 'runtime-account-session.svg', '1800'],
  ['runtime-account-session.mmd', 'runtime-account-session.png', '1800'],
  ['runtime-token-accounting.mmd', 'runtime-token-accounting.svg', '1900'],
  ['runtime-token-accounting.mmd', 'runtime-token-accounting.png', '1900'],
  ['runtime-conversation-scope.mmd', 'runtime-conversation-scope.svg', '1900'],
  ['runtime-conversation-scope.mmd', 'runtime-conversation-scope.png', '1900'],
  ['runtime-paper-collections.mmd', 'runtime-paper-collections.svg', '1800'],
  ['runtime-paper-collections.mmd', 'runtime-paper-collections.png', '1800'],
  ['runtime-paper-operations.mmd', 'runtime-paper-operations.svg', '2100'],
  ['runtime-paper-operations.mmd', 'runtime-paper-operations.png', '2100'],
  ['runtime-admin-control.mmd', 'runtime-admin-control.svg', '1800'],
  ['runtime-admin-control.mmd', 'runtime-admin-control.png', '1800'],
  ['runtime-reference-reopen.mmd', 'runtime-reference-reopen.svg', '1800'],
  ['runtime-reference-reopen.mmd', 'runtime-reference-reopen.png', '1800'],
  ['runtime-generation-recovery.mmd', 'runtime-generation-recovery.svg', '1700'],
  ['runtime-generation-recovery.mmd', 'runtime-generation-recovery.png', '1700']
];

if (process.argv.includes('--all')) {
  outputs.unshift(
    ['evidence-flow.mmd', 'paperloom-evidence-flow.svg', '1800'],
    ['evidence-flow.mmd', 'paperloom-evidence-flow.png', '1800']
  );
}

for (const [sourceName, outputName, width] of outputs) {
  const result = spawnSync(
    'npx',
    [
      '--yes',
      '-p',
      mermaidCliPackage,
      '-p',
      puppeteerPackage,
      'mmdc',
      '--configFile',
      config,
      '--input',
      path.join(siteRoot, 'diagrams', sourceName),
      '--output',
      path.join(siteRoot, 'public', 'images', outputName),
      '--backgroundColor',
      '#f7f8f5',
      '--width',
      width
    ],
    { cwd: siteRoot, stdio: 'inherit', env: childEnv }
  );

  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }
}

const architectureSvg = path.join(siteRoot, 'public', 'images', 'paperloom-system-architecture.svg');
const architecturePng = path.join(siteRoot, 'public', 'images', 'paperloom-system-architecture.png');
const customArchitectureOutputs = [
  [architectureSvg, architecturePng, '1800,1100'],
  [
    path.join(siteRoot, 'public', 'images', 'runtime-business-architecture.svg'),
    path.join(siteRoot, 'public', 'images', 'runtime-business-architecture.png'),
    '1800,1040'
  ]
];

for (const [source, output, windowSize] of customArchitectureOutputs) {
  const rasterResult = browserPath
    ? spawnSync(
      browserPath,
      ['--headless', '--disable-gpu', `--screenshot=${output}`, `--window-size=${windowSize}`, `file://${source}`],
      { cwd: siteRoot, stdio: 'inherit' }
    )
    : spawnSync(
      'ffmpeg',
      ['-y', '-loglevel', 'error', '-i', source, '-frames:v', '1', output],
      { cwd: siteRoot, stdio: 'inherit' }
    );

  if (rasterResult.status !== 0) {
    process.exit(rasterResult.status ?? 1);
  }
}
