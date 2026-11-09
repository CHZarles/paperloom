/* 临时验证脚本：管理员设置面板中的 AI Providers 与 Token Usage 两个新 section。 */
import fs from 'node:fs';
import { chromium } from '@playwright/test';

const ADMIN_USER = process.env.PL_ADMIN_USER;
const ADMIN_PASS = process.env.PL_ADMIN_PASS;
const BASE = 'http://localhost:9527';

if (!ADMIN_USER || !ADMIN_PASS) {
  console.error('PL_ADMIN_USER / PL_ADMIN_PASS required');
  process.exit(2);
}

const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'} ${name}${detail ? ` :: ${detail}` : ''}`);
};

const browser = await chromium.launch({ channel: 'chrome' });
const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
try {
  await page.goto(BASE, { waitUntil: 'networkidle' });
  if (page.url().includes('/login')) {
    await page.getByPlaceholder('请输入用户名').fill(ADMIN_USER);
    await page.getByPlaceholder('请输入密码').fill(ADMIN_PASS);
    await page.keyboard.press('Enter');
    await page.waitForURL(u => !u.href.includes('/login'), { timeout: 20000 });
  }
  await page.waitForLoadState('networkidle');
  check('login as admin', true, page.url());
  await page.goto(`${BASE}/#/discover`, { waitUntil: 'networkidle' });

  // 打开头像下拉 -> 管理页面 -> 设置面板
  await page.locator('.user-avatar__name').click();
  await page.getByText('管理页面').click();
  await page.waitForSelector('[data-testid="settings-modal"]', { timeout: 10000 });
  check('settings modal opens', true);

  // AI Providers section
  await page.getByRole('button', { name: 'AI Providers' }).click();
  await page.waitForSelector('text=AI Providers / 模型配置', { timeout: 10000 });
  await page.waitForSelector('text=MiniMax-M3', { timeout: 10000 });
  await page.waitForSelector('.model-provider-page', { timeout: 5000 });
  const keyTag = await page.locator('.model-provider-page').getByText('已配置').count();
  check('provider table shows seeded minimax', keyTag >= 1, `已配置 tags=${keyTag}`);
  await page.screenshot({ path: 'tmp/verify-provider-page.png', fullPage: false });

  // 新增 Provider 弹窗（不保存，仅验证表单可用）
  await page.getByRole('button', { name: '新增 Provider' }).click();
  await page.waitForSelector('text=Provider 标识', { timeout: 5000 });
  check('create-provider modal opens', true);
  await page.getByRole('button', { name: '取消' }).click();

  // Token Usage section
  await page.getByRole('button', { name: 'Token Usage' }).click();
  await page.waitForSelector('text=Token Usage / 用量', { timeout: 10000 });
  await page.waitForSelector('.usage-dashboard-page', { timeout: 5000 });
  await page.waitForSelector('.usage-trend-table .n-data-table-td', { timeout: 15000 });
  const trendRows = await page.locator('.usage-trend-table .n-data-table-tr').count();
  check('usage summary and trend table render', trendRows >= 7, `trend rows=${trendRows}`);
  await page.waitForSelector('.usage-ranking-grid .n-data-table-td', { timeout: 15000 });
  const rankCells = await page.locator('.usage-ranking-grid .n-data-table-td').count();
  check('ranking tables render', rankCells > 0, `rank cells=${rankCells}`);
  await page.screenshot({ path: 'tmp/verify-usage-page.png', fullPage: false });
} catch (error) {
  check('flow completed', false, String(error).slice(0, 300));
  await page.screenshot({ path: 'tmp/verify-error.png' }).catch(() => {});
} finally {
  await browser.close();
}

fs.writeFileSync('tmp/verify-results.json', JSON.stringify(results, null, 2));
process.exit(results.some(r => !r.ok) ? 1 : 0);
