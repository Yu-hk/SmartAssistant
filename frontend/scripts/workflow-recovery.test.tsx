import assert from 'node:assert/strict';
import { test } from 'node:test';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { ChatMessages } from '../src/components/ChatMessages';
import { recoveryErrorMessage, publicRecoveryError } from '../src/utils/workflowRecovery';
import type { Message } from '../src/types';

const failure: Message = { id: 'message', role: 'assistant', content: '暂未完成',
  timestamp: new Date(), model: 'unknown', requestId: 'request', recoverable: true, deliveryStatus: 'failed' };
function render(available: boolean, extra: Partial<Message> = {}) {
  return renderToStaticMarkup(<ChatMessages messages={[{ ...failure, ...extra }]} models={[]}
    messagesEndRef={{ current: null }} onRecoverMessage={() => undefined} recoveryAvailable={available} />);
}
test('disabled recovery hides button, enabled recovery shows button', () => {
  assert.doesNotMatch(render(false), /恢复本次回答/);
  assert.match(render(true), /恢复本次回答/);
});
test('unknown attribution uses user-facing assistant name', () => {
  assert.match(render(false), /智能助手/);
  assert.doesNotMatch(render(false), />unknown</);
});
test('legacy stored error never exposes exception names or internal endpoints', () => {
  const html = render(false, { recoveryError: 'NoResourceFoundException: /api/router/workflows/private/recovery-requests' });
  assert.doesNotMatch(html, /NoResourceFoundException|api\/router|private/);
  assert.match(html, /恢复暂未完成/);
});
test('raw network/provider/job errors are sanitized', () => {
  assert.equal(recoveryErrorMessage(new Error('secret internal endpoint')), publicRecoveryError('private'));
  assert.equal(recoveryErrorMessage({ status: 503, body: 'secret' }), '回答恢复功能暂不可用，请稍后再试。');
  assert.equal(recoveryErrorMessage({ status: 404 }), '回答恢复功能暂不可用，请稍后再试。');
  assert.equal(recoveryErrorMessage({ body: '{"code":"ACTIVE_EXECUTION"}' }), '任务仍在执行，请稍后再试。');
});
test('stopped or completed messages cannot offer recovery', () => {
  assert.doesNotMatch(render(true, { deliveryStatus: 'stopped', recoverable: false }), /恢复本次回答/);
  assert.doesNotMatch(render(true, { deliveryStatus: 'completed', recoverable: false }), /恢复本次回答/);
});
