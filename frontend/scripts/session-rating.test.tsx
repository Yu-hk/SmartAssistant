import { test } from 'node:test';
import assert from 'node:assert/strict';
import React, { act } from 'react';
import { createRoot } from 'react-dom/client';
import { JSDOM } from 'jsdom';
import { useSessions } from '../src/hooks/useSessions';
import type { Session } from '../src/types';

const session = {
  id: 'rated-session', title: 'QA', status: 'active', satisfaction: null,
  messages: [{ id: 'answer', role: 'assistant', content: '已完成', timestamp: new Date() }],
  createdAt: new Date(),
} as Session;

async function exercise(closeStatus: number) {
  const dom = new JSDOM('<div id="root"></div>', { url: 'https://rating.test' });
  const previous = new Map<string, PropertyDescriptor | undefined>();
  for (const [key, value] of Object.entries({
    window: dom.window, document: dom.window.document,
    localStorage: dom.window.localStorage, sessionStorage: dom.window.sessionStorage,
    IS_REACT_ACT_ENVIRONMENT: true,
  })) {
    previous.set(key, Object.getOwnPropertyDescriptor(globalThis, key));
    Object.defineProperty(globalThis, key, { value, writable: true, configurable: true });
  }
  const calls: string[] = [];
  previous.set('fetch', Object.getOwnPropertyDescriptor(globalThis, 'fetch'));
  Object.defineProperty(globalThis, 'fetch', {
    configurable: true, writable: true,
    value: async (input: RequestInfo | URL) => {
      const url = String(input);
      calls.push(url);
      if (url.endsWith('/satisfaction')) return Response.json({ sessionId: session.id, rating: 5 });
      if (url.endsWith('/close')) return closeStatus === 200
        ? Response.json({ success: true, status: 'CLOSED' })
        : Response.json({ message: '会话仍在处理' }, { status: closeStatus });
      throw new Error(`Unexpected request: ${url}`);
    },
  });
  let current: ReturnType<typeof useSessions> | undefined;
  function Harness() { current = useSessions(); return null; }
  const root = createRoot(document.getElementById('root')!);
  const previousError = console.error;
  console.error = () => {};
  try {
    await act(async () => root.render(<Harness />));
    await act(async () => current!.setSessions([session]));
    let success = false;
    await act(async () => { success = await current!.rateSession(session.id, 5); });
    return { success, calls, sessions: current!.sessions, error: current!.sessionActionError };
  } finally {
    console.error = previousError;
    await act(async () => root.unmount());
    for (const [key, descriptor] of previous) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor);
      else Reflect.deleteProperty(globalThis, key);
    }
    dom.window.close();
  }
}

test('rating releases the server gate before marking the session closed', async () => {
  const result = await exercise(200);
  assert.equal(result.success, true);
  assert.deepEqual(result.calls, ['/api/sessions/rated-session/satisfaction', '/api/sessions/rated-session/close']);
  assert.equal(result.sessions[0].status, 'closed');
  assert.equal(result.sessions[0].satisfaction, 5);
});

test('failed gate release leaves the conversation open and explains retry', async () => {
  const result = await exercise(409);
  assert.equal(result.success, false);
  assert.equal(result.sessions[0].status, 'active');
  assert.equal(result.sessions[0].satisfaction, 5);
  assert.match(result.error ?? '', /评价已保存.*会话未能结束/);
});
