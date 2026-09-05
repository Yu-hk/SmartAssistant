import { test } from 'node:test';
import React from 'react';
import assert from 'node:assert/strict';
import { renderToStaticMarkup } from 'react-dom/server';
import { ChatMessages } from '../src/components/ChatMessages';
import { SessionExecutionSteps } from '../src/components/SessionExecutionSteps';
import type { Message, ToolCall } from '../src/types';

const tool: ToolCall = { id: 'catalog', name: 'discoverProducts', status: 'completed', durationMs: 52 };
const reply = (patch: Partial<Message> = {}): Message => ({ id: 'reply', role: 'assistant',
  content: '暂无符合条件的商品', timestamp: new Date(), toolCalls: [tool], ...patch });
const chat = (message: Message) => renderToStaticMarkup(<ChatMessages messages={[message]}
  models={[]} messagesEndRef={React.createRef<HTMLDivElement>()} />);

test('live text blocks and history replies no longer duplicate steps inside the answer', () => {
  for (const message of [reply(), reply({ contentBlocks: [
    { type: 'tool_use', toolCall: tool }, { type: 'text', text: '暂无符合条件的商品' },
  ] }), reply({ contentBlocks: [{ type: 'tool_use', toolCall: tool }] })]) {
    const html = chat(message);
    assert.ok(html.includes('暂无符合条件的商品'));
    assert.ok(!html.includes('收起步骤'));
    assert.ok(!html.includes('商品目录查询'));
    const steps = renderToStaticMarkup(<SessionExecutionSteps messages={[message]} />);
    assert.ok(steps.includes('执行步骤'));
    assert.ok(steps.includes('商品目录查询'));
    assert.ok(steps.includes('52 ms'));
    assert.ok(steps.includes('已完成'));
  }
});

test('tool-only in-flight reply still shows processing feedback', () => {
  const html = chat(reply({ content: '', isStreaming: true,
    contentBlocks: [{ type: 'tool_use', toolCall: { ...tool, status: 'running' } }] }));
  assert.ok(html.includes('正在处理'));
});

test('narrow-screen steps start collapsed, keep per-turn identity and do not expose function names', () => {
  const html = renderToStaticMarkup(<SessionExecutionSteps defaultOpen={false}
    messages={[reply(), reply({ id: 'second', toolCalls: [{ ...tool, status: 'error' }] })]} />);
  assert.ok(!html.includes(' open=""'));
  for (const text of ['第 1 轮', '第 2 轮', '失败']) assert.ok(html.includes(text), text);
  assert.ok(!html.includes('discoverProducts'));
  assert.equal(renderToStaticMarkup(<SessionExecutionSteps messages={[]} />), '');
});
