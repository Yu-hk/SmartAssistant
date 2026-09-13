import assert from 'node:assert/strict';
import { after, test } from 'node:test';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router-dom';
import { LoginPage } from '../src/pages/LoginPage';

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

test('normal login, registration entry and third-party channels remain available', () => {
  const html = render();
  assert.match(html, /autoComplete="username"/);
  assert.match(html, /autoComplete="current-password"/);
  assert.match(html, /type="submit"/);
  assert.match(html, /还没有账号？立即注册/);
  assert.match(html, /记住我/);
  assert.match(html, /忘记密码/);
  for (const channel of ['微信', '钉钉', '飞书']) assert.ok(html.includes(channel));
});

test('expired login still asks users to sign in without reintroducing a demo entry', () => {
  const html = render('/login?expired=1');
  assert.match(html, /role="alert"/);
  assert.match(html, /登录状态已过期，请重新登录/);
  assert.doesNotMatch(html, /演示账号|login-demo/);
});
