import assert from 'node:assert/strict';
import { after, test } from 'node:test';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router-dom';
import { LoginPage } from '../src/pages/LoginPage';
import { getLoginChannels } from '../src/config/loginChannels';
import type { OAuthProviderStatus } from '../src/api/auth';

const originalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
Object.defineProperty(globalThis, 'localStorage', {
  configurable: true, value: { getItem: () => null },
});
after(() => {
  if (originalStorage) Object.defineProperty(globalThis, 'localStorage', originalStorage);
  else Reflect.deleteProperty(globalThis, 'localStorage');
});

function render(path = '/login') {
  return renderToStaticMarkup(<MemoryRouter initialEntries={[path]}><LoginPage /></MemoryRouter>);
}

test('login page no longer offers demo accounts or automatic trial registration', () => {
  const html = render();
  assert.doesNotMatch(html, /演示账号|先体验|免注册|重新体验|login-demo/);
  assert.match(html, /登录工作台/);
});

test('normal login and registration remain available with only DingTalk and Feishu channels', () => {
  const html = render();
  assert.match(html, /autoComplete="username"/);
  assert.match(html, /autoComplete="current-password"/);
  assert.match(html, /type="submit"/);
  assert.match(html, /还没有账号？立即注册/);
  assert.match(html, /记住我/);
  assert.match(html, /忘记密码/);
  for (const channel of ['钉钉', '飞书']) assert.ok(html.includes(channel));
  assert.doesNotMatch(html, /微信|wechat/i);
  assert.equal((html.match(/class="login-channel"/g) ?? []).length, 2);
});

test('provider responses cannot reintroduce WeChat, regardless of its enabled status', () => {
  for (const enabled of [true, false]) {
    const providers: OAuthProviderStatus[] = [
      { id: 'wechat', name: '微信', enabled },
      { id: 'feishu', name: '飞书', enabled: false },
      { id: 'dingtalk', name: '钉钉', enabled: true },
    ];
    const original = structuredClone(providers);
    assert.deepEqual(getLoginChannels(providers).map(({ id, name, enabled }) => ({ id, name, enabled })), [
      { id: 'dingtalk', name: '钉钉', enabled: true },
      { id: 'feishu', name: '飞书', enabled: false },
    ]);
    assert.deepEqual(providers, original);
  }
});

test('missing provider statuses keep the two visible channels disabled', () => {
  for (const providers of [undefined, [], [{ id: 'wechat', name: '微信', enabled: true }] as const]) {
    const channels = getLoginChannels(providers);
    assert.deepEqual(channels.map(channel => channel.id), ['dingtalk', 'feishu']);
    assert.ok(channels.every(channel => !channel.enabled));
  }
  assert.deepEqual(getLoginChannels([{ id: 'feishu', name: '飞书', enabled: true }])
    .map(channel => channel.enabled), [false, true]);
});

test('expired login still asks users to sign in without reintroducing a demo entry', () => {
  const html = render('/login?expired=1');
  assert.match(html, /role="alert"/);
  assert.match(html, /登录状态已过期，请重新登录/);
  assert.doesNotMatch(html, /演示账号|login-demo/);
});
