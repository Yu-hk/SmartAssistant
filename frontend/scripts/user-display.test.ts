import { test } from 'node:test';
import assert from 'node:assert/strict';
import { getUserDisplayName } from '../src/utils/userDisplay';

test('generated demo accounts share a friendly nickname without changing their identity', () => {
  const first = { username: 'demo_0123456789abcdef0123456789abcdef', userId: 1 };
  const second = { username: 'demo_fedcba9876543210fedcba9876543210', userId: 2 };
  for (const user of [first, second]) {
    const before = { ...user };
    assert.equal(getUserDisplayName(user.username), '演示用户');
    assert.deepEqual(user, before);
  }
  assert.notEqual(first.username, second.username);
});

test('ordinary usernames and demo-like names retain their original display', () => {
  for (const username of ['yuhk_admin', '小雨', 'demo_test', 'demo_123',
    'demo_0123456789abcdef0123456789abcdef_extra']) {
    assert.equal(getUserDisplayName(username), username);
  }
});

test('missing names use the appropriate customer or audit fallback', () => {
  assert.equal(getUserDisplayName(undefined), '用户');
  assert.equal(getUserDisplayName(null, '未知用户'), '未知用户');
  assert.equal(getUserDisplayName('   ', '未知用户'), '未知用户');
});
