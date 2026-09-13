import assert from 'node:assert/strict';
import { test } from 'node:test';
import { setTimeout as delay } from 'node:timers/promises';
import {
  createFeishuSdkLoader,
  FEISHU_LOGIN_SDK,
  FEISHU_QR_ORIGIN,
  mountFeishuQrCode,
  validateFeishuAuthorizationUri,
  type FeishuQrLoginInstance,
  type FeishuQrLoginOptions,
  type FeishuQrStatus,
} from '../src/utils/feishuQrLogin';

const state = 'state-with-&-and-中文';
const redirectUri = 'https://xiaoyuai.cloud/api/auth/oauth/feishu/callback?from=qr&lang=zh';

function authorizationUri(): URL {
  const uri = new URL(`${FEISHU_QR_ORIGIN}/suite/passport/oauth/authorize`);
  uri.searchParams.set('client_id', 'cli_test_app');
  uri.searchParams.set('response_type', 'code');
  uri.searchParams.set('redirect_uri', redirectUri);
  uri.searchParams.set('state', state);
  return uri;
}

class FakeScript extends EventTarget {
  src = '';
  async = false;
  referrerPolicy = '';
  removed = false;
  remove() { this.removed = true; }
}

function sdkHarness(timeoutMs = 100) {
  const scripts: FakeScript[] = [];
  const browser: Pick<Window, 'QRLogin'> = {};
  const doc = {
    querySelector: () => scripts.find(script => script.src === FEISHU_LOGIN_SDK && !script.removed) ?? null,
    createElement: (name: string) => {
      assert.equal(name, 'script');
      return new FakeScript();
    },
    head: { appendChild: (script: FakeScript) => { scripts.push(script); return script; } },
  } as unknown as Pick<Document, 'querySelector' | 'createElement' | 'head'>;
  return {
    scripts, browser,
    load: createFeishuSdkLoader(browser, doc, timeoutMs),
    makeAvailable() {
      browser.QRLogin = () => ({ matchOrigin: () => true, matchData: () => true });
    },
  };
}

class FakeFrame extends EventTarget {
  src = `${FEISHU_QR_ORIGIN}/suite/passport/sso/qr?goto=test`;
  title = '';
  contentWindow: object | null = {};
}

class FakeContainer {
  id = 'feishu-test-qr';
  frame: FakeFrame | null = null;
  replaceChildren() { this.frame = null; }
  querySelector(selector: string) { assert.equal(selector, 'iframe'); return this.frame; }
}

class FakeBrowser extends EventTarget {
  QRLogin?: (options: FeishuQrLoginOptions) => FeishuQrLoginInstance;
}

function qrHarness(options: {
  timeoutMs?: number;
  matchOrigin?: (origin: string) => boolean;
  matchData?: (data: unknown) => boolean;
  frameOrigin?: string;
} = {}) {
  const container = new FakeContainer();
  const browser = new FakeBrowser();
  const statuses: { status: FeishuQrStatus; message: string }[] = [];
  const redirects: string[] = [];
  const sdkOptions: FeishuQrLoginOptions[] = [];
  const frame = new FakeFrame();
  if (options.frameOrigin) frame.src = `${options.frameOrigin}/suite/passport/sso/qr`;
  browser.QRLogin = config => {
    sdkOptions.push(config);
    container.frame = frame;
    return {
      // Deliberately permissive: application validation must not rely on SDK regexes.
      matchOrigin: options.matchOrigin ?? (() => true),
      matchData: options.matchData ?? (() => true),
    };
  };
  const mount = () => mountFeishuQrCode({
    container: container as unknown as HTMLElement,
    authorizationUri: authorizationUri(),
    browser: browser as unknown as Window,
    timeoutMs: options.timeoutMs ?? 100,
    onStatus: (status, message) => statuses.push({ status, message }),
    onAuthorized: uri => redirects.push(uri),
  });
  const message = (data: unknown, origin = FEISHU_QR_ORIGIN, source: object | null = frame.contentWindow) => {
    const event = new Event('message');
    Object.defineProperties(event, {
      data: { value: data }, origin: { value: origin }, source: { value: source },
    });
    browser.dispatchEvent(event);
  };
  return { container, browser, statuses, redirects, sdkOptions, frame, mount, message };
}

test('authorization uses passport OAuth with intact client, callback and state encoding', () => {
  const uri = validateFeishuAuthorizationUri({ authorizationUri: authorizationUri().toString(), state });
  assert.equal(uri.origin, FEISHU_QR_ORIGIN);
  assert.equal(uri.pathname, '/suite/passport/oauth/authorize');
  assert.equal(uri.searchParams.get('client_id'), 'cli_test_app');
  assert.equal(uri.searchParams.get('response_type'), 'code');
  assert.equal(uri.searchParams.get('redirect_uri'), redirectUri);
  assert.equal(uri.searchParams.get('state'), state);
});

test('authorization rejects the old incompatible endpoint and lookalike origins', () => {
  for (const value of [
    'https://accounts.feishu.cn/open-apis/authen/v1/authorize',
    'https://passport.feishu.cn.attacker.invalid/suite/passport/oauth/authorize',
    'http://passport.feishu.cn/suite/passport/oauth/authorize',
    'https://attacker.invalid/suite/passport/oauth/authorize',
    'https://passport.feishu.cn/suite/passport/oauth/authorize/extra',
  ]) {
    const uri = new URL(value);
    uri.search = authorizationUri().search;
    assert.throws(() => validateFeishuAuthorizationUri({ authorizationUri: uri.toString(), state }));
  }
});

test('authorization rejects missing or incorrect OAuth parameters, userinfo and fragments', () => {
  const mutations: ((uri: URL) => void)[] = [
    uri => uri.searchParams.delete('client_id'),
    uri => uri.searchParams.set('client_id', '  '),
    uri => uri.searchParams.delete('redirect_uri'),
    uri => uri.searchParams.set('redirect_uri', '  '),
    uri => uri.searchParams.delete('response_type'),
    uri => uri.searchParams.set('response_type', 'token'),
    uri => { uri.username = 'user'; },
    uri => { uri.password = 'password'; },
    uri => { uri.hash = 'unexpected-fragment'; },
  ];
  for (const mutate of mutations) {
    const uri = authorizationUri();
    mutate(uri);
    assert.throws(() => validateFeishuAuthorizationUri({ authorizationUri: uri.toString(), state }));
  }
});

test('authorization requires one exact nonempty state', () => {
  for (const mutate of [
    (uri: URL) => uri.searchParams.delete('state'),
    (uri: URL) => uri.searchParams.set('state', 'other-state'),
    (uri: URL) => uri.searchParams.append('state', state),
  ]) {
    const uri = authorizationUri();
    mutate(uri);
    assert.throws(() => validateFeishuAuthorizationUri({ authorizationUri: uri.toString(), state }), /状态校验失败/);
  }
  assert.throws(() => validateFeishuAuthorizationUri({ authorizationUri: authorizationUri().toString(), state: '' }));
});

test('SDK requests are deduplicated, pinned to 1.0.3, and await actual SDK availability', async () => {
  const h = sdkHarness();
  const first = h.load();
  assert.equal(h.load(), first);
  assert.equal(h.scripts.length, 1);
  assert.equal(h.scripts[0].src, FEISHU_LOGIN_SDK);
  assert.match(h.scripts[0].src, /1\.0\.3\.js$/);
  assert.equal(h.scripts[0].async, true);
  assert.equal(h.scripts[0].referrerPolicy, 'no-referrer');
  h.makeAvailable();
  h.scripts[0].dispatchEvent(new Event('load'));
  await first;
  await h.load();
  assert.equal(h.scripts.length, 1);
  assert.equal(h.scripts[0].removed, false);
});

test('already available SDK does not inject another script', async () => {
  const h = sdkHarness();
  h.makeAvailable();
  await h.load();
  assert.equal(h.scripts.length, 0);
});

test('SDK load without QRLogin fails, removes the script, and supports retry', async () => {
  const h = sdkHarness();
  const first = h.load();
  const rejected = assert.rejects(first, /组件加载失败/);
  h.scripts[0].dispatchEvent(new Event('load'));
  await rejected;
  assert.equal(h.scripts[0].removed, true);
  const retry = h.load();
  assert.notEqual(retry, first);
  assert.equal(h.scripts.length, 2);
  h.makeAvailable();
  h.scripts[1].dispatchEvent(new Event('load'));
  await retry;
});

test('SDK network error is recoverable and stale load events cannot settle the new attempt', async () => {
  const h = sdkHarness();
  const first = h.load();
  const rejected = assert.rejects(first, /无法加载/);
  h.scripts[0].dispatchEvent(new Event('error'));
  await rejected;
  assert.equal(h.scripts[0].removed, true);
  const retry = h.load();
  let settled = false;
  void retry.then(() => { settled = true; });
  h.makeAvailable();
  h.scripts[0].dispatchEvent(new Event('load'));
  await Promise.resolve();
  assert.equal(settled, false);
  h.scripts[1].dispatchEvent(new Event('load'));
  await retry;
  assert.equal(settled, true);
});

test('SDK timeout removes the failed script and the next call can recover', async () => {
  const h = sdkHarness(10);
  await assert.rejects(h.load(), /加载超时/);
  assert.equal(h.scripts[0].removed, true);
  const retry = h.load();
  h.makeAvailable();
  h.scripts[1].dispatchEvent(new Event('load'));
  await retry;
});

test('an orphaned script is removed instead of waiting for an already-fired load event', async () => {
  const h = sdkHarness();
  const stale = new FakeScript();
  stale.src = FEISHU_LOGIN_SDK;
  h.scripts.push(stale);
  const result = h.load();
  assert.equal(stale.removed, true);
  assert.equal(h.scripts.length, 2);
  h.makeAvailable();
  h.scripts[1].dispatchEvent(new Event('load'));
  await result;
});

test('iframe load shows cautious ready guidance and clears the timeout', async t => {
  const h = qrHarness({ timeoutMs: 10 });
  t.after(h.mount());
  assert.deepEqual(h.statuses, []);
  assert.equal(h.frame.title, '飞书扫码登录二维码');
  assert.equal(h.sdkOptions[0].id, h.container.id);
  assert.equal(h.sdkOptions[0].goto, authorizationUri().toString());
  h.frame.dispatchEvent(new Event('load'));
  assert.equal(h.statuses[0].status, 'ready');
  assert.match(h.statuses[0].message, /若页面显示错误/);
  assert.deepEqual(h.redirects, []);
  await delay(25);
  assert.equal(h.statuses.length, 1);
});

test('iframe timeout and load error expose retry guidance without authorizing', async t => {
  const timeout = qrHarness({ timeoutMs: 10 });
  t.after(timeout.mount());
  await delay(25);
  assert.equal(timeout.statuses.at(-1)?.status, 'error');
  assert.match(timeout.statuses.at(-1)?.message ?? '', /加载超时/);
  assert.deepEqual(timeout.redirects, []);

  const failure = qrHarness({ timeoutMs: 10 });
  t.after(failure.mount());
  failure.frame.dispatchEvent(new Event('error'));
  assert.equal(failure.statuses.at(-1)?.status, 'error');
  assert.match(failure.statuses.at(-1)?.message ?? '', /加载失败/);
  await delay(25);
  assert.equal(failure.statuses.length, 1);
  assert.deepEqual(failure.redirects, []);
});

test('initialization rejects absent SDK, incompatible SDK shape, or foreign iframe', () => {
  const absent = qrHarness();
  absent.browser.QRLogin = undefined;
  assert.throws(absent.mount, /初始化失败/);
  const incompatible = qrHarness();
  incompatible.browser.QRLogin = () => {
    incompatible.container.frame = incompatible.frame;
    return { matchOrigin: () => true } as FeishuQrLoginInstance;
  };
  assert.throws(incompatible.mount, /版本或配置不正确/);
  assert.equal(incompatible.container.frame, null);
  const foreign = qrHarness({ frameOrigin: 'https://passport.feishu.cn.attacker.invalid' });
  assert.throws(foreign.mount, /版本或配置不正确/);
  assert.equal(foreign.container.frame, null);
});

test('messages require exact Feishu origin and the currently mounted iframe window', t => {
  const h = qrHarness();
  t.after(h.mount());
  const payload = { source: 'qrcode', tmp_code: 'valid-code' };
  for (const origin of ['https://passport.feishu.cn.attacker.invalid', 'http://passport.feishu.cn', 'null', 'https://accounts.feishu.cn']) {
    h.message(payload, origin);
  }
  h.message(payload, FEISHU_QR_ORIGIN, {});
  h.message(payload, FEISHU_QR_ORIGIN, null);
  assert.deepEqual(h.statuses, []);
  assert.deepEqual(h.redirects, []);
});

test('provider layout/error/status messages and malformed temporary codes are ignored', t => {
  const h = qrHarness();
  t.after(h.mount());
  for (const payload of [
    null, undefined, 'legacy-code-string', 4401, [], {},
    { source: 'qrcode', error: 4401 },
    { source: 'qrcode', status: 'loading' },
    { source: 'other', tmp_code: 'valid' },
    { source: 'qrcode', tmp_code: '' },
    { source: 'qrcode', tmp_code: 123 },
    { source: 'qrcode', tmp_code: { value: 'code' } },
    { source: 'qrcode', tmp_code: 'has space' },
    { source: 'qrcode', tmp_code: 'has\nnewline' },
    { source: 'qrcode', tmp_code: 'has\u007fcontrol' },
    { source: 'qrcode', tmp_code: 'x'.repeat(4097) },
  ]) h.message(payload);
  assert.deepEqual(h.statuses, []);
  assert.deepEqual(h.redirects, []);
});

test('SDK origin and payload predicates remain required in addition to application guards', t => {
  for (const options of [{ matchOrigin: () => false }, { matchData: () => false }]) {
    const h = qrHarness(options);
    t.after(h.mount());
    h.message({ source: 'qrcode', tmp_code: 'valid' });
    assert.deepEqual(h.redirects, []);
    assert.deepEqual(h.statuses, []);
  }
});

test('valid structured result redirects exactly once, with encoded code and original OAuth state', async t => {
  const h = qrHarness({ timeoutMs: 10 });
  t.after(h.mount());
  const code = 'tmp+code&injected=bad/中文?value=%';
  h.message({ source: 'qrcode', tmp_code: code });
  h.message({ source: 'qrcode', tmp_code: 'second-code' });
  h.frame.dispatchEvent(new Event('load'));
  h.frame.dispatchEvent(new Event('error'));
  await delay(25);
  assert.equal(h.redirects.length, 1);
  const uri = new URL(h.redirects[0]);
  assert.equal(uri.origin, FEISHU_QR_ORIGIN);
  assert.equal(uri.pathname, '/suite/passport/oauth/authorize');
  assert.equal(uri.searchParams.get('tmp_code'), code);
  assert.equal(uri.searchParams.getAll('tmp_code').length, 1);
  assert.equal(uri.searchParams.get('state'), state);
  assert.equal(uri.searchParams.get('redirect_uri'), redirectUri);
  assert.equal(uri.searchParams.get('client_id'), 'cli_test_app');
  assert.equal(uri.searchParams.has('injected'), false);
  assert.deepEqual(h.statuses.map(item => item.status), ['redirecting']);
});

test('cleanup removes the iframe, message/load callbacks, and timeout', async () => {
  const h = qrHarness({ timeoutMs: 10 });
  const dispose = h.mount();
  dispose();
  assert.equal(h.container.frame, null);
  h.message({ source: 'qrcode', tmp_code: 'late-code' });
  h.frame.dispatchEvent(new Event('load'));
  h.frame.dispatchEvent(new Event('error'));
  await delay(25);
  assert.deepEqual(h.statuses, []);
  assert.deepEqual(h.redirects, []);
});

test('messages from a disposed previous iframe cannot authorize a replacement QR', t => {
  const previous = qrHarness();
  previous.mount()();
  const current = qrHarness();
  t.after(current.mount());
  current.message({ source: 'qrcode', tmp_code: 'stale-code' }, FEISHU_QR_ORIGIN, previous.frame.contentWindow);
  assert.deepEqual(current.redirects, []);
  current.message({ source: 'qrcode', tmp_code: 'current-code' });
  assert.equal(new URL(current.redirects[0]).searchParams.get('tmp_code'), 'current-code');
});
