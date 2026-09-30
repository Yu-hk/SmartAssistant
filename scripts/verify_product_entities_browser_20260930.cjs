/** Installed Chrome/Edge UI QA. Synthetic accounts/chats retained; no catalog or order writes. */
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

async function main() {
  const channel = process.env.QA_BROWSER_CHANNEL || 'chrome';
  assert(['chrome', 'msedge'].includes(channel));
  const base = 'https://xiaoyuai.cloud';
  const proxy = process.env.QA_BROWSER_PROXY ? { server: process.env.QA_BROWSER_PROXY } : undefined;
  const browser = await chromium.launch({ channel, headless: true, proxy });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  const username = 'qa_entity_' + crypto.randomBytes(5).toString('hex');
  try {
    const registration = await context.request.post(base + '/api/auth/register', {
      data: { username, password: crypto.randomBytes(24).toString('base64url') + 'A9!' }, timeout: 30000,
    });
    assert.equal(registration.status(), 200);
    const envelope = await registration.json();
    assert.equal(envelope.code, 0); assert(envelope.data?.token);
    const auth = envelope.data;
    console.log(JSON.stringify({ createdQaAccount: username }));
    await context.addInitScript(({ base, auth }) => {
      if (location.origin === base) {
        sessionStorage.setItem('smart-assistant-token', auth.token);
        sessionStorage.setItem('smart-assistant-user', JSON.stringify(auth));
      }
    }, { base, auth });
    await page.goto(base, { waitUntil: 'domcontentloaded', timeout: 30000 });
    const newChat = page.getByRole('button', { name: '新建会话', exact: true });
    await newChat.waitFor({ timeout: 20000 });
    const cases = [
      ['different-fields', true, '想了解AirPods Pro重量和MacBook Air M3价格？', ['AirPods', 'MacBook', '8999'], ['目录售价 1999']],
      ['reference', false, '前面两款合计多少钱？', ['10998'], []],
      ['ordinal', false, '第二款价格？', ['8999'], ['目录售价 1999']],
      ['quantities', true, 'AirPods Pro 2件和MacBook Air M3 1台合计多少钱？', ['12997', '2 件', '1 件'], []],
      ['missing', true, 'QA不存在的耳机XYZ和AirPods Pro合计多少钱？', ['未找到准确匹配', '暂不能核算总价'], []],
      ['isolated-reference', true, '第二款价格？', ['需要核实具体商品'], ['目录售价']],
      ['unknown-suffix', true, 'AirPods Pro Max价格？', ['不能自动选定型号'], ['目录售价']],
    ];
    for (const [label, fresh, question, expected, forbidden] of cases) {
      if (fresh) await newChat.click();
      const previous = await page.locator('.chat-markdown').count();
      await page.getByRole('textbox', { name: '输入你的问题', exact: true }).fill(question);
      await page.getByRole('button', { name: '发送', exact: true }).click();
      await page.locator('.flex-row-reverse').getByText(question, { exact: true }).last().waitFor({ timeout: 30000 });
      await page.waitForFunction(n => document.querySelectorAll('.chat-markdown').length > n
        && ![...document.querySelectorAll('button')].some(b => b.textContent?.trim() === '停止'), previous, { timeout: 180000 });
      const answer = await page.locator('.chat-markdown').last().innerText();
      console.log(JSON.stringify({ channel, label, path: new URL(page.url()).pathname, answer }));
      for (const word of expected) assert(answer.includes(word), label + ': missing ' + word);
      for (const word of forbidden) assert(!answer.includes(word), label + ': unexpected ' + word);
      if (process.env.QA_SCREENSHOT_DIR) await page.screenshot({
        path: path.join(process.env.QA_SCREENSHOT_DIR, 'product-entity-' + channel + '-' + label + '.png'), animations: 'disabled', fullPage: true,
      });
    }
    const denied = await context.request.get(base + '/api/admin/products/AIRPODS-PRO/identity', {
      headers: { Authorization: 'Bearer ' + auth.token }, timeout: 15000,
    });
    assert.equal(denied.status(), 403, 'ordinary QA cannot manage product identities');
    assert.equal(errors.length, 0, 'page errors');
    console.log(JSON.stringify({ result: 'passed', channel, cases: cases.length, adminDenied: true, pageErrors: 0, qaAccount: username }));
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
