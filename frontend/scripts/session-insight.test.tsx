import { test } from 'node:test';
import React from 'react';
import assert from 'node:assert/strict';
import { renderToStaticMarkup } from 'react-dom/server';
import { SessionInsightPanel } from '../src/components/SessionInsightPanel';
import type { Session } from '../src/types';

function session(patch: Partial<Session> = {}): Session {
  return { id: 'one', title: '演示会话', model: 'model', intent: 'general', status: 'active',
    satisfaction: null, satisfaction_comment: null, user_name: '访客', agent_name: '通用服务',
    createdAt: new Date(), messages: [], ...patch };
}
function render(current: Session) {
  return renderToStaticMarkup(<SessionInsightPanel sessions={[current]} currentSession={current}
    onCloseSession={() => {}} onRateSession={() => {}} />);
}
test('sidebar renders real totals and friendly tool outcome/duration labels', () => {
  const html = render(session({ messages: [{ id: 'reply', role: 'assistant', timestamp: new Date(),
    content: '结果', totalTokens: 1250, promptTokens: 1000, completionTokens: 250, toolUsageComplete: true,
    toolCalls: [{ id: 'weather', name: 'queryWeather', status: 'completed', durationMs: 38 },
      { id: 'search', name: 'searchWeb', status: 'error', durationMs: 25 }] }] }));
  for (const text of ['累计 Token', '1,250', '天气查询', '联网搜索', '已完成', '失败', '38 ms']) {
    assert.ok(html.includes(text), text);
  }
  assert.ok(!html.includes('queryWeather'));
  assert.ok(!html.includes('searchWeb'));
});
test('sidebar marks missing and in-flight usage without implying zero', () => {
  const empty = render(session());
  assert.ok(empty.includes('未采集'));
  assert.ok(!empty.includes('0 次'));
  const active = render(session({ messages: [{ id: 'r', role: 'assistant', content: '',
    timestamp: new Date(), isStreaming: true }] }));
  assert.ok(active.includes('等待调用记录'));
  assert.ok(active.includes('随服务端回传更新'));
});

test('finished catalog lookup shows friendly capability, real zero usage and finished reply', () => {
  const html = render(session({ agent_name: 'product_agent', messages: [{ id: 'r', role: 'assistant',
    content: '暂无匹配商品', timestamp: new Date(), isStreaming: false,
    promptTokens: 0, completionTokens: 0, totalTokens: 0, tokenUsageComplete: true, toolUsageComplete: true,
    toolCalls: [{ id: 'catalog', name: 'discoverProducts', status: 'completed', durationMs: 10 }] }] }));
  for (const text of ['商品服务', '商品目录查询', '1 次', '本轮回复', '已结束']) assert.ok(html.includes(text), text);
  for (const text of ['product_agent', 'discoverProducts', '正在识别需求', '未采集']) assert.ok(!html.includes(text), text);
  const fallback = render(session({ agent_name: null, messages: [{ id: 'r', role: 'assistant',
    content: '回答', timestamp: new Date(), isStreaming: false }] }));
  assert.ok(!fallback.includes('正在识别需求'));
});
