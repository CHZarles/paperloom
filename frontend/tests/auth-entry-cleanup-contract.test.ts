import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const currentDir = dirname(fileURLToPath(import.meta.url));
const loginSource = readFileSync(resolve(currentDir, '../src/views/_builtin/login/index.vue'), 'utf8');
const registerSource = readFileSync(resolve(currentDir, '../src/views/_builtin/login/modules/register.vue'), 'utf8');

assert.match(loginSource, /class="landing__story"/, 'the entry page should keep the brand story column');
assert.match(loginSource, /class="auth-card"/, 'the entry page should render the authentication card');
assert.doesNotMatch(loginSource, /<NCard/, 'the authentication card is the only framed surface on the entry page');
assert.doesNotMatch(registerSource, /invite-side-panel/, 'registration should not render the promotional side panel');
assert.doesNotMatch(registerSource, /公众号|二维码|正在内测/, 'registration should not contain promotional copy');
