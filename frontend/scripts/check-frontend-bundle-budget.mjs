import { readFileSync } from 'node:fs';
import { brotliCompressSync, constants } from 'node:zlib';
import process from 'node:process';

const manifestPath = new URL('../dist/.vite/manifest.json', import.meta.url);
const distRoot = new URL('../dist/', import.meta.url);
const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));

const routes = [
  { name: 'login', entry: 'src/views/_builtin/login/index.vue', budget: 500 * 1024 },
  { name: 'chat shell', entry: 'src/views/chat/index.vue', budget: 500 * 1024 },
  { name: 'knowledge base', entry: 'src/views/knowledge-base/index.vue', budget: 700 * 1024 }
];

function collectStaticFiles(entryKey, files = new Set(), visited = new Set()) {
  if (visited.has(entryKey)) return files;
  visited.add(entryKey);

  const chunk = manifest[entryKey];
  if (!chunk) throw new Error(`Bundle manifest entry not found: ${entryKey}`);
  if (chunk.file?.endsWith('.js')) files.add(chunk.file);
  for (const importedKey of chunk.imports || []) collectStaticFiles(importedKey, files, visited);
  return files;
}

function compressedSize(files) {
  return Array.from(files).reduce((total, file) => {
    const source = readFileSync(new URL(file, distRoot));
    return (
      total +
      brotliCompressSync(source, {
        params: {
          [constants.BROTLI_PARAM_QUALITY]: 7
        }
      }).length
    );
  }, 0);
}

const mainFiles = collectStaticFiles('index.html');
let failed = false;

for (const route of routes) {
  const files = new Set([...mainFiles, ...collectStaticFiles(route.entry)]);
  const bytes = compressedSize(files);
  const heavyStartupFile = Array.from(files).find(file => /vue-markdown-shiki|file-preview|pdf\.worker/i.test(file));
  const withinBudget = bytes <= route.budget && !heavyStartupFile;
  const sizeLabel = `${(bytes / 1024).toFixed(1)} KB`;
  const budgetLabel = `${(route.budget / 1024).toFixed(1)} KB`;

  process.stdout.write(`${withinBudget ? 'PASS' : 'FAIL'} ${route.name}: ${sizeLabel} / ${budgetLabel}\n`);
  if (heavyStartupFile) process.stderr.write(`  Unexpected startup chunk: ${heavyStartupFile}\n`);
  if (!withinBudget) failed = true;
}

if (failed) process.exitCode = 1;
