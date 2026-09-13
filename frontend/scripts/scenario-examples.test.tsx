import { test } from 'node:test';
import assert from 'node:assert/strict';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router-dom';
import { ScenarioExamples } from '../src/components/ScenarioExamples';
import { SCENARIO_EXAMPLES } from '../src/config/scenarioExamples';
import { CustomerChatPage } from '../src/pages/CustomerChatPage';
import { CustomerSidebar } from '../src/components/CustomerSidebar';
import type { Session } from '../src/types';
import './document-examples.test';

function buttons(node: React.ReactNode): React.ReactElement[] {
  return React.Children.toArray(node).flatMap(child => {
    if (!React.isValidElement<{ children?: React.ReactNode }>(child)) return [];
    return child.type === 'button' ? [child] : buttons(child.props.children);
  });
}

test('three customer-service scenarios show complete questions without private order IDs or placeholders', () => {
  assert.deepEqual(SCENARIO_EXAMPLES.map(item => item.id), ['order', 'product', 'knowledge']);
  const html = renderToStaticMarkup(<ScenarioExamples onSelect={() => {}} />);
  for (const item of SCENARIO_EXAMPLES) {
    assert.ok(html.includes(item.title));
    assert.ok(html.includes(item.question));
    assert.ok(item.question.length > 10);
    assert.doesNotMatch(item.question, /ORD-|XXX|\{.*\}|[：:]$/);
  }
  assert.match(html, /点击示例填入输入框，确认后发送/);
  assert.doesNotMatch(html, /综合协助|使用计算工具|1999 乘以 2/);
  const knowledge = SCENARIO_EXAMPLES.find(item => item.id === 'knowledge');
  assert.equal(knowledge?.question, '可售库存如何计算？锁定库存能当作可售库存吗？请根据知识库回答。');
  assert.ok(!html.includes('A款耳机'));
});

test('three focused cards select full questions without duplicate quick entries or automatic sending', () => {
  const selected: string[] = [];
  const entries = buttons(ScenarioExamples({ onSelect: question => selected.push(question) }));
  assert.equal(entries.length, 3);
  entries.forEach(button => button.props.onClick());
  const questions = SCENARIO_EXAMPLES.map(item => item.question);
  assert.deepEqual(selected, questions);
});

test('all example entries are disabled and callbacks guarded while unavailable', () => {
  const selected: string[] = [];
  const entries = buttons(ScenarioExamples({ disabled: true, onSelect: question => selected.push(question) }));
  for (const button of entries) {
    assert.equal(button.props.disabled, true);
    button.props.onClick();
  }
  assert.deepEqual(selected, []);
});

test('homepage wires examples for new sessions and disables them for loading, closed or suspended sessions', () => {
  const noop = () => {};
  const props = { sessions: [], inputValue: '', permissionRequest: null, faqSuggestions: [],
    queuePosition: null, queueEstimatedWait: null, progressMessage: '', onSendMessage: noop,
    onStop: noop, onInputChange: noop, onPermissionAllow: noop, onPermissionDeny: noop,
    onRecoverMessage: noop, onRateSession: noop };
  for (const state of ['new', 'loading', 'closed', 'suspended'] as const) {
    const currentSession = state === 'closed' || state === 'suspended'
      ? { id: 'empty', status: state, messages: [] } as unknown as Session : undefined;
    const html = renderToStaticMarkup(<MemoryRouter><CustomerChatPage {...props}
      currentSession={currentSession} isLoading={state === 'loading'} /></MemoryRouter>);
    const cards = html.match(/<button[^>]*home-capability-card[^>]*>/g) ?? [];
    assert.equal(cards.length, 3);
    assert.doesNotMatch(html, /综合协助|使用计算工具|1999 乘以 2/);
    assert.doesNotMatch(html, /home-stats|home-quick-row/);
    assert.match(html, /<details class="home-documents">/);
    assert.match(html, /文档问答/);
    assert.match(html, /aria-label="输入你的问题"/);
    for (const card of cards) assert.equal(card.includes('disabled=""'), state !== 'new');
    for (const old of ['请帮我查询订单：', '帮我追踪最近一笔订单', '推荐现在的热门商品']) {
      assert.ok(!html.includes(old));
    }
  }
});

test('minimal sidebar preserves accessible selection, delete, resume and service actions', () => {
  const actions: string[] = [];
  const sessions = [
    { id: 'active-1', title: '查询我的订单', status: 'active', messages: [] },
    { id: 'paused-1', title: '商品咨询', status: 'suspended', messages: [] },
  ] as unknown as Session[];
  const props = { sessions, currentSessionId: 'active-1', theme: 'light' as const,
    onNewChat: () => actions.push('new'), onClose: () => actions.push('close'),
    onSelectSession: (id: string) => actions.push(`select:${id}`),
    onDeleteSession: (id: string) => actions.push(`delete:${id}`),
    onResumeSession: (id: string) => actions.push(`resume:${id}`),
    onSelectAgent: (name: string) => actions.push(`service:${name}`),
    onToggleTheme: () => actions.push('theme') };
  const html = renderToStaticMarkup(<CustomerSidebar {...props} />);
  assert.match(html, /aria-current="page"/);
  assert.match(html, /<details class="customer-services">/);
  assert.match(html, /aria-label="删除会话：查询我的订单"/);
  assert.match(html, /aria-label="恢复会话：商品咨询"/);
  buttons(CustomerSidebar(props)).forEach(button => button.props.onClick());
  for (const expected of ['new', 'close', 'theme', 'select:active-1', 'delete:active-1',
    'select:paused-1', 'delete:paused-1', 'resume:paused-1', 'service:订单助手']) {
    assert.ok(actions.includes(expected), `Missing action: ${expected}`);
  }
});
