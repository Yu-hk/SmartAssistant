import { test } from 'node:test';
import assert from 'node:assert/strict';
import React, { act } from 'react';
import { createRoot } from 'react-dom/client';
import { JSDOM } from 'jsdom';
import { ClarificationCard } from '../src/components/ClarificationCard';
import { normalizeClarificationForm } from '../src/utils/clarificationForm';

test('history suggestion requires explicit replacement and confirmation before form submission', async () => {
  const dom = new JSDOM('<div id="root"></div>', { url: 'https://form.test' });
  const saved = new Map<string, PropertyDescriptor | undefined>();
  for (const [key, value] of Object.entries({ window: dom.window, document: dom.window.document,
    navigator: dom.window.navigator, localStorage: dom.window.localStorage,
    sessionStorage: dom.window.sessionStorage, IS_REACT_ACT_ENVIRONMENT: true })) {
    saved.set(key, Object.getOwnPropertyDescriptor(globalThis, key));
    Object.defineProperty(globalThis, key, { value, configurable: true, writable: true });
  }
  const originalFetch = globalThis.fetch;
  globalThis.fetch = (async () => new Response(JSON.stringify({ conflictingHistory: true, options: [
    { recipientName: '李四', recipientPhone: '13900000000', shippingAddress: '北京市朝阳区建国路1号', orderDate: '2026-09-01T10:00:00' },
    { recipientName: '李四', recipientPhone: '13700000000', shippingAddress: '北京市海淀区学院路2号', orderDate: '2026-08-01T10:00:00' },
  ] }), { status: 200 })) as typeof fetch;
  const form = normalizeClarificationForm({ version: 2, token: 'permit', expiresAt: Date.now() + 60000,
    domain: 'order', operation: 'CREATE_ORDER', fields: [
      { key: 'recipientName', value: '', label: '收货人姓名', type: 'text', unit: '', min: null, max: null, decimals: 0, maxLength: 100, hint: '请填写' },
      { key: 'recipientPhone', value: '13800000000', label: '联系电话', type: 'text', unit: '', min: null, max: null, decimals: 0, maxLength: 11, hint: '请填写' },
      { key: 'shippingAddress', value: '', label: '收货地址', type: 'text', unit: '', min: null, max: null, decimals: 0, maxLength: 200, hint: '请填写' },
    ] })!;
  const container = document.getElementById('root')!;
  const root = createRoot(container), sent: string[] = [];
  try {
    await act(async () => root.render(<ClarificationCard form={form} disabled={false}
      onSubmit={text => sent.push(text)} />));
    const button = (label: string) => [...container.querySelectorAll('button')]
      .find(item => item.textContent?.includes(label))!;
    await act(async () => button('从历史订单选择').click());
    assert.match(container.textContent!, /历史订单中的收货信息不一致/);
    await act(async () => button('使用这条资料').click());
    assert.match(container.textContent!, /与所选历史订单不同/);
    assert.equal((container.querySelector('[name=recipientPhone]') as HTMLInputElement).value, '13800000000');
    await act(async () => button('确认替换当前填写').click());
    assert.equal((container.querySelector('[name=recipientPhone]') as HTMLInputElement).value, '13900000000');
    await act(async () => container.querySelector('form')!.dispatchEvent(new window.Event('submit', { bubbles: true, cancelable: true })));
    assert.equal(sent.length, 0);
    await act(async () => (container.querySelector('input[type=checkbox]') as HTMLInputElement).click());
    await act(async () => container.querySelector('form')!.dispatchEvent(new window.Event('submit', { bubbles: true, cancelable: true })));
    assert.equal(sent.length, 1);
    assert.match(sent[0], /联系电话为13900000000/);
  } finally {
    globalThis.fetch = originalFetch;
    await act(async () => root.unmount());
    for (const [key, descriptor] of saved) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor); else Reflect.deleteProperty(globalThis, key);
    }
    dom.window.close();
  }
});

