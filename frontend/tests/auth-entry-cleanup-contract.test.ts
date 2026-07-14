import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const currentDir = dirname(fileURLToPath(import.meta.url));
const loginSource = readFileSync(resolve(currentDir, '../src/views/_builtin/login/index.vue'), 'utf8');
const registerSource = readFileSync(resolve(currentDir, '../src/views/_builtin/login/modules/register.vue'), 'utf8');

assert.match(
  loginSource,
  /:deep\(\.card-wrapper\)\s*\{[\s\S]*border:\s*0;[\s\S]*box-shadow:\s*none;/,
  'the authentication panel should not draw an outer frame'
);
assert.doesNotMatch(registerSource, /invite-side-panel/, 'registration should not render the promotional side panel');
assert.doesNotMatch(registerSource, /公众号|二维码|正在内测/, 'registration should not contain promotional copy');
