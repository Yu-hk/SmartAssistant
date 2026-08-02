import assert from 'node:assert/strict';
import { JSDOM } from '../frontend/node_modules/jsdom/lib/api.js';

const dom = new JSDOM('<!doctype html><html><body><div id="root"></div></body></html>', {
  url: 'http://localhost/',
});

Object.assign(globalThis, {
  window: dom.window,
  document: dom.window.document,
  HTMLElement: dom.window.HTMLElement,
  SVGElement: dom.window.SVGElement,
  MouseEvent: dom.window.MouseEvent,
  getComputedStyle: dom.window.getComputedStyle,
  IS_REACT_ACT_ENVIRONMENT: true,
});
Object.defineProperty(globalThis, 'navigator', {
  configurable: true,
  value: dom.window.navigator,
});

async function main() {
  const React = await import('../frontend/node_modules/react/index.js');
  const { createRoot } = await import('../frontend/node_modules/react-dom/client.js');
  const { act } = React;
  const { OrderChoicePanel } = await import('../frontend/src/components/OrderChoicePanel.tsx');
  const { FollowUpSuggestionPanel } = await import('../frontend/src/components/FollowUpSuggestionPanel.tsx');
  const { getOrderFollowUpSuggestions } = await import('../frontend/src/utils/orderFollowUps.ts');

  const selected: string[] = [];
  const content = `查到您最近的3笔订单：
1. ORD-LOAD000001001 | 食品 并发测试款 0048 | 待付款 | 2026-08-01 09:43
2. ORD-LOAD000001002 | 手机 并发测试款 0065 | 待发货 | 2026-08-01 09:42
3. ORD-LOAD000001003 | 平板电脑 并发测试款 0082 | 已发货 | 2026-08-01 09:41`;

  const rootElement = document.getElementById('root');
  assert.ok(rootElement);
  const root = createRoot(rootElement);

  await act(async () => {
    root.render(React.createElement(OrderChoicePanel, {
      content,
      onSelect: (orderId: string) => selected.push(orderId),
    }));
  });

  const buttons = [...document.querySelectorAll<HTMLButtonElement>('button[aria-label^="选择订单 "]')];
  assert.equal(buttons.length, 3, 'the response should render three selectable order cards');

  await act(async () => {
    buttons[0].dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true }));
  });

  assert.deepEqual(selected, ['ORD-LOAD000001001']);
  assert.match(buttons[0].className, /order-choice-card--selected/);
  assert.equal(buttons[0].textContent?.includes('已选择'), true);
  assert.equal(buttons[1].disabled, true, 'other cards must lock after a selection');

  await act(async () => root.unmount());
  console.log('PASS order choice component: renders three cards and sends the clicked order');

  const logisticsContent = '订单 ORD-LOAD000001003 已发货，当前物流状态为运输中，预计明天送达。';
  assert.deepEqual(getOrderFollowUpSuggestions(logisticsContent), [
    '查看ORD-LOAD000001003的完整物流轨迹',
    '这个订单预计什么时候送达',
    '物流长时间未更新怎么办',
  ]);
  assert.equal(
    getOrderFollowUpSuggestions(content).length,
    0,
    'a multi-order response must show order choices instead of follow-up suggestions',
  );
  assert.equal(
    getOrderFollowUpSuggestions('订单 ORD-LOAD000001001 当前状态：待付款。')[0],
    '查看ORD-LOAD000001001支持的支付方式',
  );
  assert.equal(
    getOrderFollowUpSuggestions('订单 ORD-LOAD000001001 正在退款中。')[0],
    '查询ORD-LOAD000001001的退款进度',
  );

  const followUpRoot = createRoot(rootElement);
  const selectedSuggestions: string[] = [];
  await act(async () => {
    followUpRoot.render(React.createElement(FollowUpSuggestionPanel, {
      content: logisticsContent,
      onSelect: (suggestion: string) => selectedSuggestions.push(suggestion),
    }));
  });

  const suggestionButtons = [
    ...document.querySelectorAll<HTMLButtonElement>('button[aria-label^="继续咨询："]'),
  ];
  assert.equal(suggestionButtons.length, 3);
  await act(async () => {
    suggestionButtons[1].dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true }));
  });
  assert.deepEqual(selectedSuggestions, ['这个订单预计什么时候送达']);
  assert.match(suggestionButtons[1].className, /follow-up-chip--selected/);
  assert.equal(suggestionButtons[0].disabled, true);

  await act(async () => followUpRoot.unmount());
  console.log('PASS follow-up suggestions: contextual logistics actions render and continue the chat');
}

void main();
