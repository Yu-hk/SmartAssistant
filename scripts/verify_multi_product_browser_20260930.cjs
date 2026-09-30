/** Real Chrome UI read-only QA. Creates one synthetic account; retains its conversations. */
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

async function main() {
  const base = 'https://xiaoyuai.cloud';
  const proxy = process.env.QA_BROWSER_PROXY ? { server: process.env.QA_BROWSER_PROXY } : undefined;
  const channel = process.env.QA_BROWSER_CHANNEL || 'chrome';
  assert(['chrome', 'msedge'].includes(channel), 'supported installed browser');
  const browser = await chromium.launch({ channel, headless: true, proxy });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const page = await context.newPage();
  const pageErrors = [];
  page.on('pageerror', error => pageErrors.push(error.message));
  const username = 'qa_multi_' + crypto.randomBytes(5).toString('hex');
  try {
    // Use the ordinary public registration API; tokens/password never printed or persisted.
    const registration = await context.request.post(base + '/api/auth/register', {
      data: { username, password: crypto.randomBytes(24).toString('base64url') + 'A9!' }, timeout: 30000,
    });
    assert.equal(registration.status(), 200, 'public QA registration');
    const envelope = await registration.json();
    assert.equal(envelope.code, 0, 'QA account created');
    assert(envelope.data?.token, 'QA session issued');
    const auth = envelope.data;
    console.log(JSON.stringify({ createdQaAccount: username }));
    await context.addInitScript(({ base, auth }) => {
      if (location.origin === base) {
        sessionStorage.setItem('smart-assistant-token', auth.token);
        sessionStorage.setItem('smart-assistant-user', JSON.stringify(auth));
      }
    }, { base, auth });
    await page.goto(base, { waitUntil: 'domcontentloaded', timeout: 30000 });
    await page.getByRole('button', { name: '新建会话', exact: true }).waitFor({ timeout: 20000 });
    const cases = [
      ['facts', 'AirPods Pro和MacBook Air M3分别多少钱？有货吗？', ['AirPods', 'MacBook', '1999', '8999']],
      ['total', 'AirPods Pro和MacBook Air M3合计不超过10000元可以吗？', ['10998', '超过', '10000']],
      ['missing', 'AirPods Pro和QA不存在的耳机XYZ合计多少钱？', ['未找到', '暂不能核算']],
    ];
    for (const [label, question, required] of cases) {
      await page.getByRole('button', { name: '新建会话', exact: true }).click();
      const composer = page.getByRole('textbox', { name: '输入你的问题' });
      await composer.fill(question);
      await page.getByRole('button', { name: '发送', exact: true }).click();
      await page.locator('.flex-row-reverse').getByText(question, { exact: true }).waitFor({ timeout: 30000 });
      await page.waitForFunction(() => {
        const answer = document.querySelector('.chat-markdown')?.textContent?.trim();
        const busy = [...document.querySelectorAll('button')].some(b => b.textContent?.trim() === '停止');
        return Boolean(answer) && !busy;
      }, null, { timeout: 180000 });
      const answer = await page.locator('.chat-markdown').last().innerText();
      console.log(JSON.stringify({ label, path: new URL(page.url()).pathname, answer }));
      if (process.env.QA_SCREENSHOT_DIR) await page.screenshot({
        path: path.join(process.env.QA_SCREENSHOT_DIR, 'multi-product-' + channel + '-' + label + '.png'), fullPage: true,
        animations: 'disabled',
      });
      for (const word of required) assert(answer.includes(word), label + ': missing ' + word);
      if (label === 'missing') assert.equal((answer.match(/未找到准确匹配/g) || []).length, 1, 'missing product appears once');
      if (label === 'facts') assert.equal((answer.match(/目录售价/g) || []).length, 2, 'one verified statement per product');
    }
    assert.equal(pageErrors.length, 0, 'browser page errors');
    console.log(JSON.stringify({ result: 'passed', channel, cases: cases.length, pageErrors: 0, qaAccount: username }));
  } finally {
    await browser.close();
  }
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
