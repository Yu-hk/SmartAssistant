import { test } from 'node:test';
import assert from 'node:assert/strict';
import React, { act } from 'react';
import { createRoot } from 'react-dom/client';
import { MemoryRouter, useNavigate } from 'react-router-dom';
import { JSDOM } from 'jsdom';
import { usePageVisit } from '../src/hooks/usePageVisit';
import { moduleForPath, sendVisit, trackServiceEntry, type VisitModule } from '../src/api/visits';
import { saveAuth } from '../src/api/authStorage';
import { AdminVisitsPage } from '../src/admin/AdminVisitsPage';

const modules: VisitModule[] = [
  { code: 'login', label: '登录', audience: 'public', kind: 'PAGE_VIEW', path: '/login', entry: '' },
  { code: 'conversation', label: '对话', audience: 'customer', kind: 'PAGE_VIEW', path: '/chat/:sessionId', entry: '' },
  { code: 'orders', label: '订单助手', audience: 'customer', kind: 'ENTRY_OPEN', path: '', entry: '订单助手' },
];
test('route normalization never submits conversation IDs, queries or arbitrary URLs', () => {
  assert.equal(moduleForPath(modules, '/chat/private-session')?.code, 'conversation');
  assert.equal(moduleForPath(modules, '/chat/a/nested'), undefined);
  assert.equal(moduleForPath(modules, '/not-a-page'), undefined);
});

test('administrator page renders visitor records and opens a filtered browsing trail', async () => {
  const dom = new JSDOM('<div id="root"></div>', { url: 'https://visits.test' });
  const saved = new Map<string, PropertyDescriptor | undefined>();
  const requests: string[] = [];
  const visitor = '6bde3e90-e832-4930-b112-8435ed7970bc';
  for (const [key, value] of Object.entries({ window: dom.window, document: dom.window.document,
    localStorage: dom.window.localStorage, sessionStorage: dom.window.sessionStorage, IS_REACT_ACT_ENVIRONMENT: true,
    fetch: async (input: string) => {
      if (input.includes('visit-modules')) return { ok: true, json: async () => modules };
      requests.push(input);
      const events = input.includes('view=events');
      return { ok: true, status: 200, json: async () => ({
        items: [{ id: events ? 'event-one' : undefined, visitorId: visitor, userId: 1, username: '测试访客', role: 'ROLE_USER',
          firstSeen: '2026-09-23T10:00:00Z', lastSeen: '2026-09-23T10:01:00Z', views: 2, modules: 1,
          module: '订单助手', kind: 'ENTRY_OPEN', browser: 'Edge', device: '桌面端', createdAt: '2026-09-23T10:01:00Z' }],
        total: 1, page: 0, size: 20, retentionDays: 90, summary: { visitors: 1, views: 2, modules: 1 },
        modules: [{ code: 'orders', label: '订单助手', views: 2, visitors: 1 }],
      }) };
    },
  })) {
    saved.set(key, Object.getOwnPropertyDescriptor(globalThis, key));
    Object.defineProperty(globalThis, key, { value, configurable: true, writable: true });
  }
  const root = createRoot(document.getElementById('root')!);
  try {
    await act(async () => root.render(<AdminVisitsPage refreshVersion={0} />));
    assert.match(document.querySelector('table')!.textContent!, /测试访客/);
    const trail = [...document.querySelectorAll('button')].find(button => button.textContent?.includes('浏览轨迹'))!;
    await act(async () => trail.click());
    assert.ok(requests.at(-1)?.includes(`visitor=${visitor}`));
    assert.match(document.querySelector('table')!.textContent!, /订单助手.*服务入口.*Edge/);
    assert.equal(document.querySelector<HTMLButtonElement>('button[aria-label="下一页"]')!.disabled, true);
  } finally {
    await act(async () => root.unmount());
    for (const [key, descriptor] of saved) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor); else Reflect.deleteProperty(globalThis, key);
    }
    dom.window.close();
  }
});

test('StrictMode, navigation and fast repeated entry clicks collect bounded anonymous/authenticated events', async () => {
  const dom = new JSDOM('<div id="root"></div>', { url: 'https://visits.test' });
  const saved = new Map<string, PropertyDescriptor | undefined>();
  const requests: { url: string; options: RequestInit }[] = [];
  for (const [key, value] of Object.entries({ window: dom.window, document: dom.window.document,
    localStorage: dom.window.localStorage, sessionStorage: dom.window.sessionStorage, IS_REACT_ACT_ENVIRONMENT: true,
    fetch: async (url: string, options: RequestInit = {}) => {
      if (url.includes('visit-modules')) return { ok: true, json: async () => modules };
      requests.push({ url, options }); return { ok: true, status: 204 };
    },
  })) {
    saved.set(key, Object.getOwnPropertyDescriptor(globalThis, key));
    Object.defineProperty(globalThis, key, { value, configurable: true, writable: true });
  }
  function Page() { usePageVisit(); const navigate = useNavigate(); return <button onClick={() => navigate('/chat/private-session?secret=hidden')}>下一页</button>; }
  const root = createRoot(document.getElementById('root')!);
  try {
    await act(async () => root.render(<React.StrictMode><MemoryRouter initialEntries={['/login?code=private']}><Page /></MemoryRouter></React.StrictMode>));
    assert.equal(requests.length, 1);
    assert.equal(requests[0].url, '/api/public/visits');
    saveAuth({ token: 'test-token', refreshToken: '', tokenType: 'Bearer', userId: 1, username: 'tester', role: 'ROLE_USER' });
    await act(async () => document.querySelector('button')!.click());
    await Promise.all([trackServiceEntry('订单助手'), trackServiceEntry('订单助手')]);
    assert.equal(requests.length, 3);
    assert.equal(requests[1].url, '/api/visits');
    const bodies = requests.map(request => JSON.parse(String(request.options.body)));
    assert.deepEqual(bodies.map(body => body.module), ['login', 'conversation', 'orders']);
    assert.equal(new Set(bodies.map(body => body.visitorId)).size, 1);
    assert.equal(new Set(bodies.map(body => body.eventId)).size, 3);
    for (const body of bodies) assert.deepEqual(Object.keys(body).sort(), ['eventId', 'module', 'visitorId']);
    assert.ok(!JSON.stringify(bodies).includes('private'));
    await sendVisit({ ...modules[1], audience: 'admin' });
    assert.equal(requests.length, 3);
    globalThis.fetch = async () => { throw new Error('offline'); };
    await assert.doesNotReject(sendVisit(modules[1]));
  } finally {
    await act(async () => root.unmount());
    for (const [key, descriptor] of saved) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor); else Reflect.deleteProperty(globalThis, key);
    }
    dom.window.close();
  }
});
