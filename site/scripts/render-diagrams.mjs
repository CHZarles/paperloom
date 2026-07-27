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
  ['evidence-flow.mmd', 'paperloom-evidence-flow.svg', '1800'],
  ['evidence-flow.mmd', 'paperloom-evidence-flow.png', '1800']
];

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
const rasterResult = browserPath
  ? spawnSync(
    browserPath,
    [
      '--headless',
      '--disable-gpu',
      `--screenshot=${architecturePng}`,
      '--window-size=1800,1100',
      `file://${architectureSvg}`
    ],
    { cwd: siteRoot, stdio: 'inherit' }
  )
  : spawnSync(
    'ffmpeg',
    ['-y', '-loglevel', 'error', '-i', architectureSvg, '-frames:v', '1', architecturePng],
    { cwd: siteRoot, stdio: 'inherit' }
  );

if (rasterResult.status !== 0) {
  process.exit(rasterResult.status ?? 1);
}
