import assert from 'node:assert/strict';
import test from 'node:test';
import { clarificationReply, normalizeClarificationForm } from '../src/utils/clarificationForm.ts';

test('city-only shipping address is rejected before submission', () => {
  const form = normalizeClarificationForm({
    version: 2, token: 'test-token', expiresAt: Date.now() + 60_000,
    fields: [{ key: 'shippingAddress', label: '收货地址', type: 'text', unit: '', value: '',
      min: null, max: null, decimals: 0, minLength: 6, maxLength: 200, hint: '请填写详细地址' }],
  });
  assert.ok(form);
  assert.equal(clarificationReply(form, { shippingAddress: '北京市' }), null);
  assert.equal(clarificationReply(form, { shippingAddress: '北京市海淀区测试路1号' }),
    '补充信息：收货地址为北京市海淀区测试路1号。');
});

test('older short-lived shipping form still enforces minimum', () => {
  const form = normalizeClarificationForm({
    version: 2, token: 'test-token', expiresAt: Date.now() + 60_000,
    fields: [{ key: 'shippingAddress', label: '收货地址', type: 'text', unit: '', value: '',
      min: null, max: null, decimals: 0, maxLength: 200, hint: '请填写详细地址' }],
  });
  assert.ok(form);
  assert.equal(form.fields[0].minLength, 6);
  assert.equal(clarificationReply(form, { shippingAddress: '北京市' }), null);
});
