// Independent, synthetic read-only Chrome/Edge acceptance. Credentials remain in memory only.
const {chromium, request} = require(process.env.SMARTASSISTANT_PLAYWRIGHT_MODULE || 'D:/workspace/SmartAssistant/.qa-playwright-20260926/node_modules/playwright');
const {randomBytes} = require('node:crypto');
const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict');
const base = 'https://xiaoyuai.cloud';
const root = process.env.SMARTASSISTANT_GUARD_QA_OUTPUT;
if (!root || !fs.statSync(root).isDirectory()) throw new Error('Existing QA output directory required');
const proxy = {server: 'http://127.0.0.1:7897'};
const username = 'qa_guard_' + randomBytes(6).toString('hex');
const password = randomBytes(24).toString('base64url');
const questions = [
  '请根据知识库回答：可售库存的计算规则是什么？锁定库存和质检库存分别如何处理？',
  '请根据知识库说明：库存锁定和库存释放流程如何区别？',
  'AirPods Pro 2件和MacBook Air M3 1台合计多少钱？',
  'AirPods Pro重量是多少，MacBook Air M3多少钱？'
];
const genericFailure = /检测到 Agent 报告被阻塞|检测到基础设施故障|循环守卫暂停|暂时无法进入处理队列|对话不存在|该会话已删除|处理失败[:：]/;
const missingDisclosure = /没有|不足|未提供|未说明|未包含|未找到|缺少|无法|未定义|未明确|暂无|未给出|未涉及/;
let stage = 'registration';
const records = [];

async function snapshot(page) {
  const answer = (await page.locator('.chat-markdown').first().innerText()).trim();
  const facts = await page.locator('.workbench-insight').evaluate(el => Object.fromEntries(
    [...el.querySelectorAll('.insight-kv')].filter(n => n.querySelector('span') && n.querySelector('strong'))
      .map(n => [n.querySelector('span').textContent.trim(), n.querySelector('strong').textContent.trim()])));
  return {answer, source: facts['回复来源'], tokens: facts['累计 Token'], tools: facts['工具调用']};
}

(async () => {
  const api = await request.newContext({baseURL: base, proxy, timeout: 30000});
  try {
    const response = await api.post('/api/auth/register', {data: {username, password}});
    const result = await response.json();
    assert(response.ok() && result.code === 0 && result.data?.token, 'Synthetic QA registration failed');
  } finally { await api.dispose(); }
  for (const channel of ['chrome', 'msedge']) {
    const browser = await chromium.launch({channel, headless: true, proxy});
    try {
      const page = await browser.newPage({viewport: {width: 1440, height: 900}});
      const errors = [];
      page.on('pageerror', error => errors.push(error.name));
      stage = channel + ' login';
      await page.goto(base + '/login', {waitUntil: 'domcontentloaded'});
      await page.getByPlaceholder('请输入用户名', {exact: true}).fill(username);
      await page.getByPlaceholder('请输入登录密码', {exact: true}).fill(password);
      await page.locator('button.login-submit').click();
      await page.getByRole('button', {name: '新建会话'}).waitFor({timeout: 30000});
      for (let turn = 0; turn < questions.length; turn++) {
        stage = channel + ' query ' + (turn + 1);
        await page.getByRole('button', {name: '新建会话'}).click();
        await page.getByRole('textbox', {name: '输入你的问题'}).fill(questions[turn]);
        await page.getByRole('button', {name: '发送', exact: true}).click();
        await page.waitForURL(/\/chat\/[^/]+$/, {timeout: 30000});
        await page.waitForFunction(() => document.querySelector('.chat-markdown')?.textContent?.trim()
          && ![...document.querySelectorAll('button')].some(b => b.textContent?.trim() === '停止'),
        null, {timeout: 120000});
        const live = await snapshot(page);
        const route = new URL(page.url()).pathname;
        await page.screenshot({path: path.join(root, channel + '-' + (turn + 1) + '.png'), animations: 'disabled'});
        assert(!genericFailure.test(live.answer), 'No generic execution blocker/session failure');
        assert(['实时处理', '缓存复用'].includes(live.source), 'Known response source required');
        if (channel === 'chrome') assert.equal(live.source, '实时处理', 'Fresh QA must test actual execution');
        if (turn === 0) assert(/库存/.test(live.answer) && missingDisclosure.test(live.answer), 'Missing formulas must remain explicit');
        if (turn === 1) assert(/库存/.test(live.answer), 'Knowledge answer required');
        if (turn === 2) assert(/12997/.test(live.answer), 'Exact two-product total');
        if (turn === 3) assert(/8999/.test(live.answer) && /未知|未核实|未提供|暂无|没有|缺少/.test(live.answer), 'Known and unknown facts both preserved');
        stage += ' reload';
        await page.reload({waitUntil: 'domcontentloaded'});
        await page.locator('.chat-markdown').first().waitFor({timeout: 25000});
        assert.deepEqual(await snapshot(page), live, 'History/source/usage must survive reload');
        records.push({channel, turn: turn + 1, route, ...live});
        console.log(JSON.stringify({browser: channel, turn: turn + 1, status: 'passed', source: live.source}));
      }
      if (channel === 'msedge') {
        stage = 'cross-browser restore';
        const first = records[0];
        await page.goto(base + first.route, {waitUntil: 'domcontentloaded'});
        await page.locator('.chat-markdown').first().waitFor({timeout: 25000});
        assert.deepEqual(await snapshot(page), {answer: first.answer, source: first.source, tokens: first.tokens, tools: first.tools});
      }
      assert.equal(errors.length, 0, 'No browser JS exceptions');
    } finally { await browser.close(); }
  }
  fs.writeFileSync(path.join(root, 'browser-report.json'), JSON.stringify({status: 'passed', username,
    queries: records.length, reloads: records.length, crossBrowserRestore: true, businessWrites: 0, records}, null, 2), {flag: 'wx', mode: 0o600});
})().catch(error => {
  console.error(JSON.stringify({status: 'failed', stage, errorType: error.name,
    reason: String(error.message).replaceAll(password, '[redacted]').replace(/Bearer\s+\S+/g, 'Bearer [redacted]')}));
  process.exitCode = 1;
});
