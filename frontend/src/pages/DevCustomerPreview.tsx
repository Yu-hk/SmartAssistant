import { useState } from 'react';
import { CustomerSidebar } from '../components/CustomerSidebar';
import { CustomerChatPage } from './CustomerChatPage';
import type { Session } from '../types';

const PREVIEW_SESSION: Session = {
  id: 'preview-order-session',
  title: '查询我的订单物流进度',
  model: 'claude-sonnet-4',
  intent: 'order',
  status: 'active',
  satisfaction: null,
  satisfaction_comment: null,
  user_name: 'load_user_000001',
  agent_name: '小智客服',
  createdAt: new Date(),
  messages: [
    {
      id: 'preview-user-1',
      role: 'user',
      content: '查询我的订单物流进度',
      timestamp: new Date(),
    },
    {
      id: 'preview-assistant-1',
      role: 'assistant',
      model: 'claude-sonnet-4',
      content: '查到您最近的3笔订单：\n1. ORD-LOAD000001001｜食品 并发测试款 0048｜待付款｜2026-08-01 09:43\n2. ORD-LOAD000001002｜手机 并发测试款 0065｜待发货｜2026-08-01 09:42\n3. ORD-LOAD000001003｜平板电脑 并发测试款 0082｜已发货｜2026-08-01 09:41\n请选择下方要查看的订单，我会继续为您查询状态、物流或售后信息。',
      timestamp: new Date(),
    },
    {
      id: 'preview-user-2',
      role: 'user',
      content: 'ORD-LOAD000001001',
      timestamp: new Date(),
    },
    {
      id: 'preview-assistant-2',
      role: 'assistant',
      model: 'claude-sonnet-4',
      content: '我帮您看了一下，您选择的是“食品 并发测试款 0048”（订单号 ORD-LOAD000001001）。\n\n这笔订单当前状态是「待付款」，还没有进入发货流程，所以暂时不会有物流信息。完成付款后，商家才会安排发货并生成运单。\n\n订单金额为 ¥6675.00。如果您暂时不需要，也可以先了解取消订单的方式。',
      timestamp: new Date(),
    },
  ],
};

export function DevCustomerPreview() {
  const [inputValue, setInputValue] = useState('');
  const showWelcome = new URLSearchParams(window.location.search).get('view') === 'welcome';

  return (
    <div className="customer-app-shell relative z-10">
      <CustomerSidebar
        sessions={[PREVIEW_SESSION]}
        currentSessionId={showWelcome ? null : PREVIEW_SESSION.id}
        theme="light"
        onNewChat={() => {}}
        onSelectSession={() => {}}
        onDeleteSession={() => {}}
        onOpenAdmin={() => {}}
        onToggleTheme={() => {}}
        username="load_user_000001"
        onLogout={() => {}}
      />
      <main className="customer-main flex-1 flex flex-col min-w-0 relative">
        <div className="customer-topbar glass">
          <div className="customer-topbar__avatar" aria-hidden="true">智</div>
          <div className="customer-topbar__identity">
            <strong>小智客服</strong>
            <span><i aria-hidden="true" /> 在线 · 通常几秒内回复</span>
          </div>
          {!showWelcome && <div className="customer-topbar__session">查询我的订单物流进度</div>}
          <button type="button" className="customer-topbar__new">＋ 新咨询</button>
        </div>
        <CustomerChatPage
          currentSession={showWelcome ? undefined : PREVIEW_SESSION}
          isLoading={false}
          inputValue={inputValue}
          permissionRequest={null}
          faqSuggestions={[]}
          queuePosition={null}
          queueEstimatedWait={null}
          onSendMessage={() => setInputValue('')}
          onStop={() => {}}
          onInputChange={setInputValue}
          onPermissionAllow={() => {}}
          onPermissionDeny={() => {}}
        />
      </main>
    </div>
  );
}
