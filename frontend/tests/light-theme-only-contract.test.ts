import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const currentDir = dirname(fileURLToPath(import.meta.url));
const readSource = (path: string) => readFileSync(resolve(currentDir, path), 'utf8');

const settingsSource = readSource('../src/theme/settings.ts');
const storeSource = readSource('../src/store/modules/theme/index.ts');
const appSource = readSource('../src/App.vue');
const loginSource = readSource('../src/views/_builtin/login/index.vue');
const headerSource = readSource('../src/layouts/modules/global-header/index.vue');
const drawerSource = readSource('../src/layouts/modules/theme-drawer/modules/dark-mode.vue');

assert.match(settingsSource, /themeScheme:\s*'light'/, 'light should be the only configured theme');
assert.doesNotMatch(settingsSource, /\n\s*dark:\s*\{/, 'dark theme tokens should not be configured');
assert.match(storeSource, /const darkMode = computed\(\(\) => false\)/, 'the theme store should remain light');
assert.doesNotMatch(
  storeSource,
  /usePreferredColorScheme|\['light', 'dark', 'auto'\]/,
  'OS and rotating themes should be removed'
);
assert.doesNotMatch(appSource, /darkTheme|naiveDarkTheme/, 'the application should not install a dark Naive UI theme');
assert.doesNotMatch(loginSource, /ThemeSchemaSwitch/, 'login should not expose a theme switch');
assert.doesNotMatch(headerSource, /ThemeSchemaSwitch/, 'the global header should not expose a theme switch');
assert.doesNotMatch(drawerSource, /themeSchemaRecord|<NTabs/, 'the theme drawer should not expose theme modes');
