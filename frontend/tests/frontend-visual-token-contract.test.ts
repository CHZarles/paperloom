import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const currentDir = dirname(fileURLToPath(import.meta.url));
const tokensSource = readFileSync(resolve(currentDir, '../src/styles/css/tokens.css'), 'utf8');
const globalSource = readFileSync(resolve(currentDir, '../src/styles/css/global.css'), 'utf8');

assert.match(tokensSource, /--color-research:\s*#22508f;/i, 'light mode should expose the research accent');
assert.match(
  tokensSource,
  /\.dark\s*\{[^}]*--color-research:\s*#7fa3d1;/is,
  'dark mode should expose the dark research accent'
);
assert.match(tokensSource, /--color-citation:\s*#8a4f0c;/i, 'light mode should expose the citation accent');
assert.match(tokensSource, /--font-reading:/, 'the visual system should define an explicit reading font role');
assert.match(tokensSource, /--font-display:/, 'the visual system should define an explicit display font role');
assert.match(tokensSource, /--font-utility:/, 'the visual system should define an explicit utility font role');
assert.match(tokensSource, /--radius-composer:\s*24px;/, 'the composer card should get the softest radius');
assert.match(tokensSource, /--radius-panel:\s*12px;/, 'tool panels should use the shared restrained radius');
assert.match(globalSource, /\.reading-serif\s*\{/, 'reading content should have a reusable typography class');
