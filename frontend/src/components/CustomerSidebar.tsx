import { Moon, Sun, Plus, X, MessageSquare, RotateCcw, Trash2, ChevronDown } from 'lucide-react';
import { Session } from '../types';

interface CustomerSidebarProps {
  sessions: Session[];
  currentSessionId: string | null;
  theme: 'light' | 'dark';
  onNewChat: () => void;
  onSelectSession: (id: string) => void;
  onDeleteSession: (id: string) => void;
  onResumeSession: (id: string) => void;
  onSelectAgent: (name: string) => void;
  onToggleTheme: () => void;
  isOpen?: boolean;
  onClose?: () => void;
}

const SERVICES = ['售前顾问', '技术支持', '订单助手', '投诉处理', '知识管家'];
const STATUS_LABELS: Record<string, string> = {
  active: '进行中', suspended: '已暂停', human_transfer: '转人工', closed: '已结束',
};

export function CustomerSidebar({
  sessions, currentSessionId, theme, onNewChat, onSelectSession, onDeleteSession,
  onResumeSession, onSelectAgent, onToggleTheme, isOpen = false, onClose,
}: CustomerSidebarProps) {
  const suspended = sessions.filter(session => session.status === 'suspended');
  const regular = sessions.filter(session => session.status !== 'suspended');

  const sessionItem = (session: Session) => (
    <div key={session.id} className={`customer-session ${session.id === currentSessionId ? 'is-active' : ''}`}>
      <button type="button" className="customer-session-select" onClick={() => onSelectSession(session.id)}
        aria-current={session.id === currentSessionId ? 'page' : undefined} title={session.title}>
        <MessageSquare size={15} aria-hidden="true" />
        <span><strong>{session.title}</strong><small>{STATUS_LABELS[session.status] || session.status}</small></span>
      </button>
      <div className="customer-session-actions">
        {session.status === 'suspended' && <button type="button" onClick={() => onResumeSession(session.id)}
          title="恢复会话" aria-label={`恢复会话：${session.title}`}><RotateCcw size={14} /></button>}
        <button type="button" onClick={() => onDeleteSession(session.id)} title="删除会话"
          aria-label={`删除会话：${session.title}`}><Trash2 size={14} /></button>
      </div>
    </div>
  );

  return <aside className={`customer-sidebar ${isOpen ? 'is-open' : ''}`} aria-label="会话导航">
    <div className="customer-brand">
      <img src="/icons/app-icon.svg" alt="" />
      <span><strong>智服</strong><small>SmartAssistant</small></span>
      <button type="button" className="sidebar-close-button" aria-label="关闭侧边栏" onClick={onClose}><X size={18} /></button>
    </div>
    <button type="button" className="customer-new-chat" onClick={onNewChat}><Plus size={17} />新建会话</button>
    <div className="customer-navigation-scroll">
      <details className="customer-services">
        <summary>服务入口<ChevronDown size={14} /></summary>
        <div>{SERVICES.map(name => <button key={name} type="button" onClick={() => onSelectAgent(name)}>{name}</button>)}</div>
      </details>
      <nav aria-label="我的会话">
        <h2 className="customer-nav-heading">我的会话 <span>{regular.length}</span></h2>
        {regular.map(sessionItem)}
        {sessions.length === 0 && <p className="customer-history-empty">还没有会话<br />发送第一个问题，开始咨询。</p>}
        {suspended.length > 0 && <section aria-label="已暂停的对话">
          <h2 className="customer-nav-heading">已暂停 <span>{suspended.length}</span></h2>
          {suspended.map(sessionItem)}
          <p className="customer-history-hint">上下文已保留，点击恢复即可继续。</p>
        </section>}
      </nav>
    </div>
    <div className="customer-sidebar-footer">
      <button type="button" onClick={onToggleTheme}>
        {theme === 'light' ? <Moon size={16} /> : <Sun size={16} />}
        {theme === 'light' ? '深色模式' : '浅色模式'}
      </button>
    </div>
  </aside>;
}
