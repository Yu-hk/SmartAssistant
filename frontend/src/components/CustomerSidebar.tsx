import React from 'react';
import { Session, INTENT_LABELS, INTENT_COLORS } from '../types';

interface CustomerSidebarProps {
  sessions: Session[];
  currentSessionId: string | null;
  theme: 'light' | 'dark';
  onNewChat: () => void;
  onSelectSession: (id: string) => void;
  onDeleteSession: (id: string) => void;
  onOpenAdmin: () => void;
  onToggleTheme: () => void;
  username: string;
  onLogout: () => void;
  mobileOpen?: boolean;
  onMobileClose?: () => void;
}

const STATUS_COLORS: Record<string, string> = {
  active: 'var(--nova-success)',
  human_transfer: 'var(--nova-warm)',
  closed: 'var(--nova-text-tertiary)',
};

const STATUS_LABELS: Record<string, string> = {
  active: '进行中',
  human_transfer: '转人工',
  closed: '已结束',
};

export function CustomerSidebar({
  sessions, currentSessionId, theme,
  onNewChat, onSelectSession, onDeleteSession, onOpenAdmin, onToggleTheme,
  username, onLogout, mobileOpen, onMobileClose,
}: CustomerSidebarProps) {
  return (
    <aside className={`customer-sidebar glass${mobileOpen ? ' customer-sidebar--open' : ''}`} style={{
      width: '270px',
      flexShrink: 0,
      display: 'flex',
      flexDirection: 'column',
      borderRight: '1px solid var(--nova-border)',
      height: '100vh',
      zIndex: 20,
    }}>
      {/* 品牌区域 */}
      <div style={{
        padding: '20px 18px 16px',
        borderBottom: '1px solid var(--nova-border)',
      }}>
        <button
          type="button"
          className="customer-sidebar__close"
          aria-label="关闭咨询记录"
          onClick={onMobileClose}
        >×</button>
        <div style={{
          display: 'flex', alignItems: 'center', gap: '12px',
          marginBottom: '16px',
        }}>
          {/* 客服品牌 Logo */}
          <div style={{
            width: '40px', height: '40px', borderRadius: '12px',
            background: 'var(--nova-accent)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            color: 'white',
            flexShrink: 0,
            boxShadow: 'var(--nova-shadow-sm)',
          }}>
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M4 14a8 8 0 0 1 16 0" />
              <path d="M18 19c0 1.1-.9 2-2 2h-3" />
              <path d="M4 14v3a2 2 0 0 0 2 2h1v-7H6a2 2 0 0 0-2 2Z" />
              <path d="M20 14v3a2 2 0 0 1-2 2h-1v-7h1a2 2 0 0 1 2 2Z" />
            </svg>
          </div>
          <div>
            <div style={{
              fontSize: '15px', fontWeight: 700,
              color: 'var(--nova-text-primary)',
              letterSpacing: '0.03em',
            }}>
              智服中心
            </div>
            <div style={{
              fontSize: '11px', color: 'var(--nova-text-secondary)',
              fontWeight: 500, letterSpacing: '0.02em',
            }}>
              智能客户服务平台
            </div>
          </div>
        </div>

        {/* 新建咨询按钮 */}
        <button
          onClick={onNewChat}
          className="neon-btn"
          style={{
            width: '100%',
            padding: '10px 12px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            gap: '8px',
            fontSize: '13px',
          }}
        >
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
            <line x1="12" y1="5" x2="12" y2="19" />
            <line x1="5" y1="12" x2="19" y2="12" />
          </svg>
          发起新咨询
        </button>
      </div>

      {/* 会话列表 */}
      <div style={{ flex: 1, overflow: 'auto', padding: '10px 8px' }}>
        {sessions.length === 0 ? (
          <div style={{
            textAlign: 'center',
            color: 'var(--nova-text-tertiary)',
            padding: '48px 16px',
            fontSize: '13px',
          }}>
            <svg width="40" height="40" viewBox="0 0 24 24" fill="none"
              stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"
              strokeLinejoin="round"
              style={{ margin: '0 auto 12px', display: 'block', opacity: 0.4 }}>
              <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
            </svg>
            暂无对话记录<br />
            <span style={{ fontSize: '11px', color: 'var(--nova-text-tertiary)' }}>
              点击「新建对话」开始咨询
            </span>
          </div>
        ) : (
          sessions.map(session => (
            <SessionItem
              key={session.id}
              session={session}
              isActive={session.id === currentSessionId}
              onSelect={() => onSelectSession(session.id)}
              onDelete={() => onDeleteSession(session.id)}
            />
          ))
        )}
      </div>

      {/* 底部工具栏 */}
      <div style={{
        padding: '12px',
        borderTop: '1px solid var(--nova-border)',
      }}>
        <div className="sidebar-account">
          <span className="sidebar-account__avatar">{username.slice(0, 1).toUpperCase()}</span>
          <span>
            <strong>{username}</strong>
            <small>已安全登录</small>
          </span>
        </div>
        <div style={{ display: 'flex', gap: '8px' }}>
          <button
            onClick={onOpenAdmin}
            className="glass-hover"
            style={{
              flex: 1, padding: '8px 12px', borderRadius: '8px',
              border: '1px solid var(--nova-border)',
              background: 'transparent',
              color: 'var(--nova-text-secondary)',
              fontSize: '12px', cursor: 'pointer',
              display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '6px',
              transition: 'all 0.2s',
            }}
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none"
              stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <rect x="3" y="3" width="7" height="7" />
              <rect x="14" y="3" width="7" height="7" />
              <rect x="3" y="14" width="7" height="7" />
              <rect x="14" y="14" width="7" height="7" />
            </svg>
            管理后台
          </button>
          <button
            onClick={onToggleTheme}
            title={theme === 'light' ? '切换深色模式' : '切换浅色模式'}
            className="glass-hover sidebar-icon-button"
          >
            {theme === 'light' ? '🌙' : '☀️'}
          </button>
          <button
            onClick={onLogout}
            title="退出登录"
            aria-label="退出登录"
            className="glass-hover sidebar-icon-button sidebar-logout-button"
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
              <polyline points="16 17 21 12 16 7" />
              <line x1="21" y1="12" x2="9" y2="12" />
            </svg>
          </button>
        </div>
      </div>
    </aside>
  );
}

// ===================================================
// 单条客户咨询会话
// ===================================================
function SessionItem({ session, isActive, onSelect, onDelete }: {
  session: Session;
  isActive: boolean;
  onSelect: () => void;
  onDelete: () => void;
}) {
  const [showDel, setShowDel] = React.useState(false);
  const intentColor = INTENT_COLORS[session.intent] || 'var(--nova-text-tertiary)';
  const statusColor = STATUS_COLORS[session.status] || 'var(--nova-text-tertiary)';
  const statusLabel = session.timedOut ? '已超时' : (STATUS_LABELS[session.status] || session.status);

  return (
    <div
      onClick={onSelect}
      onMouseEnter={() => {
        setShowDel(true);
        if (!isActive) {
          const el = document.getElementById(`session-${session.id}`);
          if (el) {
            el.style.background = 'var(--nova-bg-glass-hover)';
            el.style.borderColor = 'var(--nova-border)';
          }
        }
      }}
      onMouseLeave={() => {
        setShowDel(false);
        if (!isActive) {
          const el = document.getElementById(`session-${session.id}`);
          if (el) {
            el.style.background = 'transparent';
            el.style.borderColor = 'transparent';
          }
        }
      }}
      style={{
        padding: '10px 12px',
        borderRadius: '10px',
        marginBottom: '4px',
        cursor: 'pointer',
        position: 'relative',
        transition: 'all 0.2s cubic-bezier(0.4, 0, 0.2, 1)',
        background: isActive
          ? 'var(--nova-accent-light)'
          : 'transparent',
        border: isActive
          ? '1px solid var(--nova-accent-glow)'
          : '1px solid transparent',
      }}
      id={`session-${session.id}`}
    >
      {/* 左侧意图色条 */}
      {session.intent !== 'unknown' && (
        <div style={{
          position: 'absolute', left: '2px', top: '30%',
          width: '3px', height: '40%',
          borderRadius: '2px',
          background: intentColor,
        }} />
      )}

      {/* 标题行 */}
      <div style={{
        display: 'flex', alignItems: 'center', gap: '6px', marginBottom: '4px',
        paddingLeft: session.intent !== 'unknown' ? '8px' : '0',
      }}>
        <span style={{
          width: '5px', height: '5px', borderRadius: '50%',
          background: statusColor, flexShrink: 0,
        }} />
        <span style={{
          flex: 1, fontSize: '13px', fontWeight: isActive ? 600 : 400,
          color: 'var(--nova-text-primary)',
          overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
        }}>
          {session.title}
        </span>
        {showDel && (
          <button
            onClick={e => { e.stopPropagation(); onDelete(); }}
            style={{
              background: 'none', border: 'none', cursor: 'pointer',
              color: 'var(--nova-error)', fontSize: '14px',
              padding: '0 2px', flexShrink: 0, lineHeight: 1,
            }}
          >×</button>
        )}
      </div>

      {/* 元信息行 */}
      <div style={{
        display: 'flex', alignItems: 'center', gap: '6px',
        paddingLeft: session.intent !== 'unknown' ? '8px' : '0',
      }}>
        {session.intent !== 'unknown' && (
          <span style={{
            fontSize: '10px', padding: '1px 6px', borderRadius: '100px',
            background: intentColor + '18', color: intentColor,
            border: `1px solid ${intentColor}30`,
            fontWeight: 500,
          }}>
            {INTENT_LABELS[session.intent]}
          </span>
        )}
        <span style={{
          fontSize: '10px', color: statusColor, fontWeight: 500,
        }}>
          ● {statusLabel}
        </span>
      </div>
    </div>
  );
}
