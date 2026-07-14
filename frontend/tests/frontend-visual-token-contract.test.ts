import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const currentDir = dirname(fileURLToPath(import.meta.url));
const tokensSource = readFileSync(resolve(currentDir, '../src/styles/css/tokens.css'), 'utf8');
const globalSource = readFileSync(resolve(currentDir, '../src/styles/css/global.css'), 'utf8');

assert.match(tokensSource, /--color-research:\s*#16786e;/i, 'light mode should expose the research accent');
assert.match(tokensSource, /--color-citation:\s*#a86414;/i, 'light mode should expose the citation accent');
assert.match(tokensSource, /--font-reading:/, 'the visual system should define an explicit reading font role');
assert.match(tokensSource, /--font-utility:/, 'the visual system should define an explicit utility font role');
assert.match(tokensSource, /--radius-panel:\s*8px;/, 'tool panels should use the shared restrained radius');
assert.match(globalSource, /\.reading-serif\s*\{/, 'reading content should have a reusable typography class');
