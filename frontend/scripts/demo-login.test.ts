import { test, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { createDemoAccount } from '../src/api/demo';

const originalFetch = globalThis.fetch;
const storage = { getItem: () => null };
Object.defineProperty(globalThis, 'localStorage', { value: storage, configurable: true });
Object.defineProperty(globalThis, 'sessionStorage', { value: storage, configurable: true });
afterEach(() => { globalThis.fetch = originalFetch; });

function success(username: string, role = 'ROLE_USER') {
  return Response.json({ code: 0, data: {
    username, userId: 1, token: 'test-access-token', refreshToken: 'test-refresh-token',
    tokenType: 'Bearer', role,
  } });
}

test('visitors get distinct credentials through ordinary registration', async () => {
  const requests: Record<string, string>[] = [];
  globalThis.fetch = async (url, options) => {
    assert.equal(url, '/api/auth/register');
    assert.equal(options?.method, 'POST');
    const body = JSON.parse(options?.body as string);
    requests.push(body);
    return success(body.username);
  };
  const first = await createDemoAccount();
  const second = await createDemoAccount();
  assert.notEqual(first.username, second.username);
  assert.notEqual(requests[0].password, requests[1].password);
  for (const body of requests) {
    assert.match(body.username, /^demo_[0-9a-f]{32}$/);
    assert.match(body.password, /^[0-9a-f]{32}$/);
    assert.deepEqual(Object.keys(body).sort(), ['password', 'username']);
  }
});

test('rapid repeated clicks create only one account', async () => {
  let calls = 0;
  globalThis.fetch = async () => { calls++; return success('demo_test'); };
  const first = createDemoAccount();
  assert.equal(createDemoAccount(), first);
  await first;
  assert.equal(calls, 1);
});

test('registration failures stay failures and permit an explicit retry', async () => {
  globalThis.fetch = async () => Response.json({ message: '注册暂不可用' }, { status: 503 });
  await assert.rejects(createDemoAccount(), /注册暂不可用/);
  globalThis.fetch = async () => success('demo_retry');
  assert.equal((await createDemoAccount()).username, 'demo_retry');
});

test('an unexpected privileged identity is never returned as a demo login', async () => {
  globalThis.fetch = async () => success('not-a-demo', 'ROLE_ADMIN');
  await assert.rejects(createDemoAccount(), /演示账号权限异常/);
});
